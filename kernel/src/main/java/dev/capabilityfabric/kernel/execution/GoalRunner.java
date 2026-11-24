package dev.capabilityfabric.kernel.execution;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.constitution.ConstitutionService;
import dev.capabilityfabric.kernel.decisions.DecisionLog;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.memory.EpisodicMemory;
import dev.capabilityfabric.kernel.person.ProfileService;
import dev.capabilityfabric.kernel.planning.PlanComposer;
import dev.capabilityfabric.kernel.planning.PlanResolver;
import dev.capabilityfabric.kernel.policy.PolicyGuard;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.Facts.RunOutcome;
import dev.capabilityfabric.protocol.Facts.StepOutcome;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Plans.Plan;
import dev.capabilityfabric.protocol.Plans.PlanStep;
import dev.capabilityfabric.protocol.TaskStatus;
import dev.capabilityfabric.protocol.Vocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs one plan for one goal: compose, then execute step by step. Every step passes the policy guard, gets a fresh,
 * minimised context, and its effects pass the ledger rules. A failing provider is retried once on another instance
 * (when there is none, the step keeps the first failure as its reason); an optional step that still fails is skipped
 * (graceful degradation); a required step that fails also skips the later steps that require its output, unless
 * another completed step produced it or the goal already has it. The kill switch and a cancellation are checked again
 * right before a step's effects are applied, so a call that was running when either came changes nothing. A step
 * waiting for the person's approval is rejected by a "no" or by a request nobody answered in time; when the goal is
 * cancelled while it waits, it is skipped and the run ends as cancelled. A run whose thread is interrupted (the
 * kernel is stopping) ends at once without recording an outcome, so the next start restarts it. A step's detail is
 * one line of at most {@link CapabilityInvoker#MAX_REASON} characters. Once the run-finished fact is recorded the run
 * has ended: a failure to close its row in the run store or to record its episode is logged, never thrown.
 */
@Component
public class GoalRunner {

    private static final Logger log = LoggerFactory.getLogger(GoalRunner.class);

    private final LedgerService ledger;
    private final PlanComposer composer;
    private final CapabilityInvoker invoker;
    private final CapabilityRegistry registry;
    private final PolicyGuard policy;
    private final Approvals approvals;
    private final DecisionLog decisions;
    private final EpisodicMemory memory;
    private final RunStore runs;
    private final KernelProperties properties;
    private final ConstitutionService constitution;
    private final ProfileService profile;

    public GoalRunner(LedgerService ledger, PlanComposer composer, CapabilityInvoker invoker,
                      CapabilityRegistry registry, PolicyGuard policy, Approvals approvals, DecisionLog decisions,
                      EpisodicMemory memory, RunStore runs, KernelProperties properties,
                      ConstitutionService constitution, ProfileService profile) {
        this.constitution = constitution;
        this.profile = profile;
        this.ledger = ledger;
        this.composer = composer;
        this.invoker = invoker;
        this.registry = registry;
        this.policy = policy;
        this.approvals = approvals;
        this.decisions = decisions;
        this.memory = memory;
        this.runs = runs;
        this.properties = properties;
    }

    /** Thrown when the run's thread is interrupted: nothing more is recorded and the run stays unfinished. */
    public static final class Interrupted extends RuntimeException {
        Interrupted() {
            super("the run was interrupted", null, false, false);
        }
    }

    /**
     * Composes and executes a plan for the goal. {@code trigger} is create, refine or refresh; {@code changedTokens}
     * select the capabilities of a refresh; {@code pinned} capabilities are part of the plan whatever the planner
     * chooses. Returns the outcome recorded in the run-finished fact.
     *
     * @throws Interrupted when the thread is interrupted part-way
     */
    public RunOutcome run(String goalId, String runId, String trigger, Set<String> changedTokens, Set<String> pinned) {
        long start = System.currentTimeMillis();
        GoalState state = ledger.load(goalId).orElseThrow(() -> new IllegalStateException("unknown goal " + goalId));
        if (state.status() == GoalStatus.CANCELLED) {
            // Recorded like a paused run, so clients waiting for this run see it end.
            ledger.record(goalId, Facts.RUN_FINISHED, new Facts.RunFinished(goalId, runId, trigger,
                    RunOutcome.CANCELLED, 0, 0, 0, 0, 0), "kernel", runId);
            closeRow(runId, RunOutcome.CANCELLED, 0, 0);
            return RunOutcome.CANCELLED;
        }
        boolean refresh = "refresh".equals(trigger);
        GoalStatus before = state.status();
        Map<String, TaskStatus> statesBefore = taskStates(state);
        if (policy.paused()) {
            // Kill switch: no composition, no model calls. Resume restarts this goal.
            ledger.record(goalId, Facts.RUN_FINISHED, new Facts.RunFinished(goalId, runId, trigger,
                    RunOutcome.CANCELLED, 0, 0, 0, 0, 0), "kernel", runId);
            closeRow(runId, RunOutcome.CANCELLED, 0, 0);
            log.info("goal {} run {} cancelled: system paused", shortId(goalId), shortId(runId));
            return RunOutcome.CANCELLED;
        }
        if (!refresh) {
            ledger.goalStatus(goalId, GoalStatus.PLANNING, trigger, "kernel");
        }

        PlanComposer.Composed composed = composer.compose(state, runId, trigger, changedTokens, pinned);
        Plan plan = composed.plan();
        if (composed.planner() != null) {
            decisions.saveAll(goalId, runId, -1, composed.plannerDecisions(), DecisionLog.Source.of(composed.planner()));
        }
        ledger.record(goalId, Facts.PLAN_COMPOSED, new Facts.PlanComposed(goalId, runId, trigger, plan), "kernel", runId);
        runs.plan(runId, plan);
        log.info("goal {} run {} ({}): {} plan {}", shortId(goalId), shortId(runId), trigger, plan.source(),
                plan.steps().stream().map(PlanStep::capability).toList());

        registry.invokedCapabilities().forEach((id, m) -> producesSeen.put(id, m.produces()));
        int llmCalls = composed.plannerDecisions().size();
        int completed = 0;
        int skipped = 0;
        int failed = 0;
        boolean cancelled = false;
        Set<String> missing = new HashSet<>();
        for (PlanStep step : plan.steps()) {
            stopIfInterrupted();
            long stepStart = System.currentTimeMillis();
            CapabilityManifest manifest = registry.invokedCapabilities().get(step.capability());
            StepOutcome outcome;
            String detail;
            String provider = null;
            String instance = null;
            String requestId = null;
            int stepLlm = 0;
            String stopped = stopReason(goalId);
            Approvals.Answer answer = null;
            if (stopped != null) {
                outcome = StepOutcome.SKIPPED;
                detail = stopped;
                cancelled = true;
            } else if (manifest == null) {
                outcome = step.optional() ? StepOutcome.SKIPPED : StepOutcome.FAILED;
                detail = "provider left before the step started";
            } else if (!missing.isEmpty() && inputMissing(manifest.requires(), missing, presentTokens(goalId))) {
                outcome = StepOutcome.SKIPPED;
                detail = "required input was not produced";
            } else {
                PolicyGuard.Verdict verdict = policy.checkStep(manifest);
                var ruling = constitution.checkAgent(manifest, goalId);
                boolean ruleAsks = ruling.verdict() == ConstitutionService.Verdict.ASK_FIRST;
                if (verdict == PolicyGuard.Verdict.DENY) {
                    // Refusals first: never ask the person to approve something that would be refused anyway.
                    outcome = StepOutcome.REJECTED;
                    detail = "denied by policy";
                } else if (ruling.verdict() == ConstitutionService.Verdict.FORBID) {
                    outcome = StepOutcome.REJECTED;
                    detail = "refused by " + ruling.explanation();
                } else if ((ruleAsks || verdict == PolicyGuard.Verdict.APPROVAL)
                        && (answer = approvals.requestAndWait(goalId, runId, step.index(), manifest.id(),
                        manifest.autonomy(), ruleAsks ? "required by " + ruling.explanation()
                        : approvalReason(manifest), properties.approvalTimeout())) != Approvals.Answer.APPROVED) {
                    // One approval covers both the person's rule and the policy.
                    stopIfInterrupted();
                    if (answer == Approvals.Answer.CANCELLED) {
                        // The goal was cancelled while the step waited: nobody refused the step, the run stops.
                        outcome = StepOutcome.SKIPPED;
                        detail = "goal cancelled";
                        cancelled = true;
                    } else {
                        outcome = StepOutcome.REJECTED;
                        // A request nobody answered in time says so; a "no" says the step was not approved.
                        String why = answer == Approvals.Answer.EXPIRED
                                ? "the approval request expired without an answer" : "not approved";
                        detail = ruleAsks ? why + " under " + ruling.explanation() : why;
                    }
                } else if ((stopped = stopReason(goalId)) != null) {
                    // The kill switch or a cancellation may have come while the step waited for approval.
                    outcome = StepOutcome.SKIPPED;
                    detail = stopped;
                    cancelled = true;
                } else if (manifest.kind() == CapabilityManifest.Kind.AI && llmCalls >= properties.maxLlmCallsPerRun()) {
                    outcome = StepOutcome.SKIPPED;
                    detail = "model-call budget of " + properties.maxLlmCallsPerRun() + " exhausted";
                } else {
                    Set<String> excluded = new HashSet<>();
                    String failure = null;
                    outcome = null;
                    detail = null;
                    CapabilityManifest evaluated = manifest;
                    for (int attempt = 0; attempt < 2 && outcome == null; attempt++) {
                        var context = profile.context(ledger.load(goalId).orElseThrow());
                        CapabilityInvoker.Invocation invocation = invoker.invoke(goalId, runId, step.index(),
                                manifest.id(), manifest.major(), context, null, excluded,
                                candidate -> compatible(candidate, evaluated));
                        if (CapabilityInvoker.INTERRUPTED.equals(invocation.failure())) {
                            throw new Interrupted();
                        }
                        if (failure != null && invocation.instanceId() == null) {
                            // The retry found no other instance to call: the first attempt's failure says what
                            // went wrong, "no live provider" would hide it.
                            break;
                        }
                        if (invocation.result() != null) {
                            decisions.saveAll(goalId, runId, step.index(), invocation.result().decisions(),
                                    DecisionLog.Source.of(invocation.manifest()));
                            stepLlm += invocation.result().decisions().size();
                            // Who answered is the instance that was called, as the registry knows it.
                            provider = invocation.manifest().provider();
                            instance = invocation.manifest().instanceId();
                            requestId = invocation.result().requestId();
                        }
                        if (invocation.ok() && (stopped = stopReason(goalId)) != null) {
                            // The kill switch or a cancellation came while the call ran: its results are not applied.
                            outcome = StepOutcome.SKIPPED;
                            detail = stopped;
                            cancelled = true;
                        } else if (invocation.ok()) {
                            CapabilityManifest responder = invocation.manifest();
                            String actor = "agent:" + responder.provider() + "/" + responder.instanceId();
                            // The person's own efforts and due dates are kept, decided under the goal's lock.
                            LedgerService.Applied applied = ledger.applyEffectsKeepingHumanEdits(goalId,
                                    invocation.result().effects(), responder, actor, invocation.result().requestId());
                            if (applied.ok()) {
                                outcome = StepOutcome.COMPLETED;
                                detail = applied.events().size() + " facts";
                            } else if (ledger.load(goalId).map(g -> g.status() == GoalStatus.CANCELLED).orElse(true)) {
                                outcome = StepOutcome.SKIPPED; // cancelled meanwhile: not the provider's fault
                                detail = "goal cancelled";
                                cancelled = true;
                            } else {
                                failure = "effects rejected: " + String.join("; ", applied.errors());
                                invoker.rejected(responder);
                            }
                        } else {
                            failure = invocation.failureReason();
                        }
                        // A call that failed on the kernel's side says nothing about the instance: the retry may go
                        // to it again.
                        if (outcome == null && invocation.instanceId() != null && !invocation.kernelSide()) {
                            excluded.add(invocation.instanceId());
                        }
                    }
                    if (outcome == null) {
                        outcome = step.optional() ? StepOutcome.SKIPPED : StepOutcome.FAILED;
                        detail = failure;
                    }
                }
            }
            llmCalls += stepLlm;
            switch (outcome) {
                case COMPLETED -> completed++;
                case FAILED -> failed++;
                default -> skipped++;
            }
            if (outcome == StepOutcome.COMPLETED) {
                missing.removeAll(manifest.produces());
            } else {
                // Whatever this step would have written is missing: steps that require it are skipped.
                missing.addAll(manifest != null ? manifest.produces() : lastKnownProduces(step.capability()));
            }
            long ms = System.currentTimeMillis() - stepStart;
            // A failure or a refusal quotes text the provider or a rule supplied: one capped line.
            detail = CapabilityInvoker.capped(detail);
            ledger.record(goalId, Facts.STEP_FINISHED, new Facts.StepFinished(goalId, runId, step.index(),
                    step.capability(), outcome, provider, instance, ms, stepLlm, detail, requestId), "kernel", runId);
            runs.step(runId, new KernelApi.StepView(step.index(), step.capability(), outcome.name(), provider, ms,
                    stepLlm, detail));
            log.info("  step {} {}: {} ({})", step.index(), step.capability(), outcome, detail);
        }

        GoalState after = ledger.load(goalId).orElseThrow();
        RunOutcome outcome;
        if (cancelled) {
            outcome = RunOutcome.CANCELLED;
        } else if (failed > 0 && liveTasks(after).isEmpty()) {
            outcome = RunOutcome.FAILED;
        } else if (failed > 0 || skipped > 0) {
            outcome = RunOutcome.DEGRADED;
        } else {
            outcome = RunOutcome.COMPLETED;
        }
        GoalStatus goalStatus;
        if (cancelled) {
            // Paused or cancelled part-way: a goal with tasks is not left looking as if it were still planning. A
            // refresh run never set it planning, so its status stays as it is.
            goalStatus = after.status() == GoalStatus.CANCELLED || after.tasks().isEmpty() || refresh ? null
                    : GoalStatus.ACTIVE;
        } else {
            goalStatus = settledStatus(after, before, !statesBefore.equals(taskStates(after)), outcome, refresh);
        }
        if (goalStatus != null && after.status() != GoalStatus.CANCELLED) {
            ledger.goalStatus(goalId, goalStatus, "run " + outcome.name().toLowerCase(Locale.ROOT), "kernel");
        }
        long duration = System.currentTimeMillis() - start;
        ledger.record(goalId, Facts.RUN_FINISHED, new Facts.RunFinished(goalId, runId, trigger, outcome, duration,
                llmCalls, completed, skipped, failed), "kernel", runId);
        closeRow(runId, outcome, duration, llmCalls);
        if (!refresh) {
            try {
                memory.recordEpisode(runId, goalId, after.text(),
                        plan.steps().stream().map(PlanStep::capability).toList(), after.tasks().size(), outcome.name(),
                        llmCalls, duration);
            } catch (RuntimeException e) {
                log.warn("could not record the episode of run {}: {}", shortId(runId), e.getMessage());
            }
        }
        try {
            constitution.checkLimits();
        } catch (RuntimeException e) {
            log.warn("limit check failed: {}", e.getMessage());
        }
        log.info("goal {} run {} finished: {} in {} ms, {} model calls", shortId(goalId), shortId(runId), outcome,
                duration, llmCalls);
        return outcome;
    }

    /**
     * Closes the run's row in the run store, after its run-finished fact was recorded. A failure is logged and the
     * run still counts as ended: the row stays unfinished until {@link RunCoordinator}'s sweep (or the next start)
     * closes it with the outcome of that fact.
     */
    private void closeRow(String runId, RunOutcome outcome, long durationMs, int llmCalls) {
        try {
            runs.finish(runId, outcome.name(), durationMs, llmCalls);
        } catch (RuntimeException e) {
            log.warn("could not close run {} in the run store: {}", shortId(runId), e.getMessage());
        }
    }

    /**
     * Whether a step lacks a required input: one that an earlier step failed to produce, that no later completed
     * step produced and that the goal does not already have.
     */
    static boolean inputMissing(List<String> requires, Set<String> missing, Set<String> present) {
        return requires.stream().anyMatch(t -> missing.contains(t) && !present.contains(t));
    }

    private Set<String> presentTokens(String goalId) {
        return ledger.load(goalId).map(g -> PlanResolver.presentTokens(g.toContext(properties.today())))
                .orElse(Set.of());
    }

    /**
     * The goal's status after a run that was not stopped part-way, or {@code null} to leave it as it is. Dropped
     * tasks do not count: a goal with no other task has no tasks, which fails a planning run and leaves the status
     * alone after a refresh run. A goal whose remaining tasks are all done is completed, unless it was open (active or
     * degraded) when the run started and no task changed state since: the person reopened it, and a run that changed
     * no task does not close it again. Otherwise the run's outcome decides between active and degraded.
     *
     * @param before       the goal's status when the run started
     * @param tasksChanged whether a task was added or changed state while the run went on
     */
    static GoalStatus settledStatus(GoalState after, GoalStatus before, boolean tasksChanged, RunOutcome outcome,
                                    boolean refresh) {
        List<GoalContext.TaskView> live = liveTasks(after);
        if (live.isEmpty()) {
            return refresh ? null : GoalStatus.FAILED;
        }
        boolean keptOpen = !tasksChanged && (before == GoalStatus.ACTIVE || before == GoalStatus.DEGRADED);
        if (live.stream().noneMatch(t -> t.status().isOpen()) && !keptOpen) {
            return GoalStatus.COMPLETED;
        }
        return outcome == RunOutcome.COMPLETED ? GoalStatus.ACTIVE : GoalStatus.DEGRADED;
    }

    /** The goal's tasks that were not dropped. */
    static List<GoalContext.TaskView> liveTasks(GoalState goal) {
        return goal.tasks().stream().filter(t -> t.status() != TaskStatus.DROPPED).toList();
    }

    /** Each task's state by id, to tell whether a run added a task or saw one change state. */
    private static Map<String, TaskStatus> taskStates(GoalState goal) {
        Map<String, TaskStatus> states = new HashMap<>();
        goal.tasks().forEach(t -> states.put(t.id(), t.status()));
        return states;
    }

    /** Why the run must not go on with the goal now: the kill switch or a cancellation; {@code null} otherwise. */
    private String stopReason(String goalId) {
        if (policy.paused()) {
            return "system paused";
        }
        return ledger.load(goalId).map(g -> g.status() == GoalStatus.CANCELLED).orElse(true) ? "goal cancelled" : null;
    }

    private static void stopIfInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new Interrupted();
        }
    }

    /** Produces of a capability as last seen, for steps whose provider has left. */
    private final Map<String, List<String>> producesSeen = new ConcurrentHashMap<>();

    private List<String> lastKnownProduces(String capability) {
        registry.invokedCapabilities().forEach((id, m) -> producesSeen.put(id, m.produces()));
        return producesSeen.getOrDefault(capability, List.of());
    }

    /**
     * An instance may take the call only if it cannot do more than the manifest policy and rules were checked
     * against: no higher autonomy, no more side effects, no writes beyond the evaluated ones.
     */
    static boolean compatible(CapabilityManifest candidate, CapabilityManifest evaluated) {
        return candidate.autonomy().ordinal() <= evaluated.autonomy().ordinal()
                && candidate.sideEffect().ordinal() <= evaluated.sideEffect().ordinal()
                && evaluated.produces().containsAll(candidate.produces());
    }

    /** Why a step waits for approval, as shown to the person. */
    private String approvalReason(CapabilityManifest manifest) {
        if (properties.approvalRequired().contains(manifest.id())) {
            return "policy requires human approval for " + manifest.id();
        }
        return "autonomy " + manifest.autonomy() + " is above the automatic limit " + properties.maxAutoInvoked();
    }

    static String shortId(String id) {
        return id == null ? "-" : id.substring(0, Math.min(8, id.length()));
    }

    /** Tokens a human status change can affect, used to select refresh capabilities. */
    public static Set<String> statusTokens() {
        return Set.of(Vocabulary.TASK_STATUS, Vocabulary.TASK_ACTUAL_HOURS);
    }
}
