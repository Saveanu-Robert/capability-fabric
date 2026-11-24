package dev.capabilityfabric.kernel.execution;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.policy.PolicyGuard;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.Facts.RunOutcome;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Vocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * At most one run per goal at a time. Requests that arrive while a run is active are coalesced into one
 * follow-up run, whose id they all get. A per-goal hourly limit stops runaway re-planning loops.
 *
 * <p>Every run gets one {@code plan.run-finished} fact, so clients waiting for its id stop waiting, and its goal is not
 * left planning or waiting for an approval:
 * <ul>
 *   <li>a run that throws while the kernel is not stopping is closed in the run store as failed, gets a failed
 *   run-finished fact and leaves its goal failed (no tasks but dropped ones) or degraded; each of these writes is
 *   tried even when the one before it failed;</li>
 *   <li>a follow-up run that cannot start gets a cancelled run-finished fact under the id its requests were given;</li>
 *   <li>what could not be written then (the database did not answer) is written by a sweep every
 *   {@link #SWEEP_INTERVAL} while this process runs ({@link #sweep}): the run-finished facts still owed are recorded,
 *   with the failed run's goal status, and every run the run store lists as unfinished although no slot holds it is
 *   closed, with the outcome of its run-finished fact when the ledger has one, otherwise as interrupted with a
 *   cancelled run-finished fact and the status its goal's tasks call for.</li>
 * </ul>
 * The ledger is read before a run-finished fact is recorded, so a run never gets two.
 *
 * <p>At start, the runs the previous process left unfinished (a stopping kernel leaves its runs unfinished, and so
 * does a database that failed while a run ended) are closed the same way and, unless their run-finished fact was
 * recorded, restarted once the registry has had time to see the live providers again; goals the previous process left
 * planning or waiting for an approval without a restarted run get their status back. Other facts still owed when a
 * process stops are lost with it. The coordinator starts before the registry and the kernel API ({@link #PHASE}), so
 * the runs it marks as interrupted at start are only those of the previous process, never runs started at this
 * start-up.
 */
@Component
public class RunCoordinator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RunCoordinator.class);
    /** Below the registry (0) and the kernel API (10). */
    static final int PHASE = -100;
    /** How often the sweep records owed run-finished facts and closes unfinished runs that no slot holds. */
    static final Duration SWEEP_INTERVAL = Duration.ofMinutes(1);
    /** The run store's outcome of a run that ended without recording how. */
    static final String INTERRUPTED = "INTERRUPTED";

    private final GoalRunner runner;
    private final RunStore runs;
    private final KernelProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Slot> slots = new HashMap<>();
    /** Run-finished facts that could not be recorded when their run ended, by run id; the sweep records them. */
    private final Map<String, Owed> owed = new ConcurrentHashMap<>();
    private volatile boolean running;

    /** The active run of a goal and the follow-up run requested meanwhile ({@code followUpId} once requested). */
    private static final class Slot {
        final String runId;
        String followUpId;
        String pendingTrigger;
        final Set<String> pendingTokens = new LinkedHashSet<>();
        final Set<String> pendingPinned = new LinkedHashSet<>();

        Slot(String runId) {
            this.runId = runId;
        }
    }

    /**
     * The run-finished fact a run is owed, with {@code outcome}; when {@code settle}, its goal also gets the status a
     * run of that outcome leaves ({@link #statusAfter}), with {@code reason}.
     */
    record Owed(String goalId, String runId, String trigger, RunOutcome outcome, boolean settle, String reason) {
    }

    private final LedgerService ledger;
    private final PolicyGuard policy;

    public RunCoordinator(GoalRunner runner, RunStore runs, KernelProperties properties,
                          LedgerService ledger, PolicyGuard policy) {
        this.runner = runner;
        this.runs = runs;
        this.properties = properties;
        this.ledger = ledger;
        this.policy = policy;
    }

    /** Starts (or schedules after the active one) a run. Returns the run id, or null when rate-limited. */
    public synchronized String request(String goalId, String trigger, Set<String> changedTokens) {
        return request(goalId, trigger, changedTokens, Set.of());
    }

    /**
     * {@code pinned} capabilities are part of the plan even if the planner would not choose them. While a run is
     * active the request joins the follow-up run (a create or refine trigger wins over refresh) and its id is
     * returned: the run that will see this request's change.
     */
    public synchronized String request(String goalId, String trigger, Set<String> changedTokens, Set<String> pinned) {
        Slot active = slots.get(goalId);
        if (active != null) {
            if (active.pendingTrigger == null || !"refresh".equals(trigger)) {
                active.pendingTrigger = active.pendingTrigger != null && !"refresh".equals(active.pendingTrigger)
                        ? active.pendingTrigger : trigger;
            }
            active.pendingTokens.addAll(changedTokens);
            active.pendingPinned.addAll(pinned);
            if (active.followUpId == null) {
                active.followUpId = UUID.randomUUID().toString();
            }
            return active.followUpId;
        }
        return start(goalId, UUID.randomUUID().toString(), trigger, changedTokens, pinned);
    }

    /** Starts a run under {@code runId} and returns that id, or {@code null} when the goal reached its hourly limit. */
    private String start(String goalId, String runId, String trigger, Set<String> changedTokens, Set<String> pinned) {
        if (runs.countSince(goalId, Instant.now().minusSeconds(3600)) >= properties.maxRunsPerGoalPerHour()) {
            log.warn("goal {} reached {} runs in the last hour; ignoring {}", goalId,
                    properties.maxRunsPerGoalPerHour(), trigger);
            return null;
        }
        Set<String> tokens = Set.copyOf(changedTokens);
        Set<String> pins = Set.copyOf(pinned);
        slots.put(goalId, new Slot(runId));
        boolean stored = false;
        try {
            runs.start(runId, goalId, trigger);
            stored = true;
            executor.submit(() -> execute(goalId, runId, trigger, tokens, pins));
        } catch (RuntimeException e) {
            // Nothing will run: free the goal so later requests are not queued behind a run that never started.
            slots.remove(goalId);
            if (stored) {
                finishQuietly(runId);
            }
            throw e;
        }
        return runId;
    }

    private void execute(String goalId, String runId, String trigger, Set<String> tokens, Set<String> pinned) {
        RunOutcome outcome = null;
        try {
            outcome = runner.run(goalId, runId, trigger, tokens, pinned);
        } catch (RuntimeException e) {
            if (!running || e instanceof GoalRunner.Interrupted) {
                // The kernel is stopping: the run stays unfinished and the next start restarts it.
                log.info("run {} for goal {} stopped with the kernel; it restarts at the next start", runId, goalId);
            } else {
                log.error("run {} for goal {} failed", runId, goalId, e);
                failed(goalId, runId, trigger, e);
            }
        } finally {
            // Paused part-way, then resumed before this run ended: resume did not see it, so run it again now.
            boolean resumedWhileRunning = outcome == RunOutcome.CANCELLED && running && resumed(goalId);
            Slot done;
            String followUpId = null;
            boolean followUpStarted = false;
            synchronized (this) {
                // The slot is freed and the follow-up started in one step: a request in between would start a run of
                // its own, and the follow-up's promised id would never get a run.
                done = slots.remove(goalId);
                if (done != null && done.pendingTrigger != null && running) {
                    followUpId = done.followUpId != null ? done.followUpId : UUID.randomUUID().toString();
                    followUpStarted = startFollowUp(goalId, followUpId, done);
                }
            }
            if (followUpId != null) {
                if (!followUpStarted) {
                    notStarted(goalId, followUpId, done.pendingTrigger);
                }
            } else if (resumedWhileRunning) {
                try {
                    request(goalId, "refresh".equals(trigger) ? "refresh" : "refine", tokens, pinned);
                } catch (RuntimeException e) {
                    log.warn("could not run goal {} again after the resume: {}", goalId, e.getMessage());
                }
            }
        }
    }

    /** Whether the system runs and the goal is not cancelled, so a run cut short by a pause should run again. */
    private boolean resumed(String goalId) {
        try {
            return !policy.paused() && ledger.load(goalId).map(g -> g.status() != GoalStatus.CANCELLED).orElse(false);
        } catch (RuntimeException e) {
            log.warn("could not tell whether goal {} should run again after a resume: {}", goalId, e.getMessage());
            return false;
        }
    }

    /**
     * Starts the follow-up run of the slot {@code done} under {@code id}, the id its requests were given. Called with
     * the lock held, right after {@code done} was freed. Returns false when it did not start (the hourly limit, or the
     * run store or the executor refused it); the caller then records it as cancelled.
     */
    private boolean startFollowUp(String goalId, String id, Slot done) {
        try {
            return start(goalId, id, done.pendingTrigger, done.pendingTokens, done.pendingPinned) != null;
        } catch (RuntimeException e) {
            log.warn("could not start the follow-up run of goal {}: {}", goalId, e.getMessage());
            return false;
        }
    }

    /** Records that a promised run will not happen, so clients waiting for its id stop waiting. */
    private void notStarted(String goalId, String runId, String trigger) {
        if (runId != null) {
            owe(new Owed(goalId, runId, trigger, RunOutcome.CANCELLED, false, null));
        }
    }

    /**
     * Ends a run that threw: closes it in the run store as failed (unless it was closed already), then records its
     * failed run-finished fact and, when it is still planning or waiting for an approval, its goal's failed or degraded
     * status. Each write is tried even when the one before it failed; what is not written is left to the sweep.
     */
    private void failed(String goalId, String runId, String trigger, RuntimeException e) {
        try {
            runs.close(runId, RunOutcome.FAILED.name());
        } catch (RuntimeException again) {
            log.warn("could not close failed run {}: {}", runId, again.getMessage());
        }
        owe(new Owed(goalId, runId, trigger, RunOutcome.FAILED, true, "run failed: " + e.getClass().getSimpleName()));
    }

    /** Records what a run is owed now, or leaves it to the sweep when the ledger does not take it. */
    private void owe(Owed ending) {
        try {
            settle(ending);
        } catch (RuntimeException e) {
            owed.put(ending.runId(), ending);
            log.warn("could not record the end of run {}: {}; trying again every {} s", ending.runId(),
                    e.getMessage(), SWEEP_INTERVAL.toSeconds());
        }
    }

    /**
     * Records the run-finished fact {@code ending} owes unless the ledger already has one for its run; then, when
     * asked, gives its goal the status a run of that outcome leaves, if the goal is still planning or waiting for an
     * approval and no other run holds it now (that run sets the status). Throws when the ledger fails.
     */
    private void settle(Owed ending) {
        if (recordedOutcome(ending.goalId(), ending.runId()).isEmpty()) {
            ledger.record(ending.goalId(), Facts.RUN_FINISHED, new Facts.RunFinished(ending.goalId(), ending.runId(),
                    ending.trigger(), ending.outcome(), 0, 0, 0, 0, ending.outcome() == RunOutcome.FAILED ? 1 : 0),
                    "kernel", ending.runId());
        }
        if (ending.settle() && !heldByAnother(ending.goalId(), ending.runId())) {
            ledger.load(ending.goalId()).filter(RunCoordinator::unsettled).ifPresent(g -> ledger.goalStatus(
                    ending.goalId(), statusAfter(ending.outcome(), g), ending.reason(), "kernel"));
        }
    }

    /** The outcome of the run-finished fact the ledger holds for the run; empty when it holds none. */
    Optional<RunOutcome> recordedOutcome(String goalId, String runId) {
        return ledger.events(goalId).stream()
                .filter(e -> Facts.RUN_FINISHED.equals(e.type()) && runId.equals(e.causationId()))
                .map(e -> e.payload(Facts.RunFinished.class))
                .filter(f -> runId.equals(f.runId()))
                .map(Facts.RunFinished::outcome)
                .findFirst();
    }

    /** A goal a run left planning or waiting for an approval. */
    private static boolean unsettled(GoalState goal) {
        return goal.status() == GoalStatus.PLANNING || goal.status() == GoalStatus.AWAITING_APPROVAL;
    }

    /**
     * The status of a goal whose run ended with {@code outcome} without settling it: after a failure, failed without
     * tasks (dropped tasks do not count, so a task creator that joins plans it again, see {@link Replanner}) and
     * degraded with some; otherwise what its tasks call for ({@link #settledStatus}).
     */
    static GoalStatus statusAfter(RunOutcome outcome, GoalState goal) {
        if (outcome == RunOutcome.FAILED) {
            return GoalRunner.liveTasks(goal).isEmpty() ? GoalStatus.FAILED : GoalStatus.DEGRADED;
        }
        return settledStatus(goal);
    }

    /** Whether a slot holds the run: it is still going on, so nothing may close it. */
    private synchronized boolean held(String runId) {
        return slots.values().stream().anyMatch(s -> s.runId.equals(runId));
    }

    /** Whether a run other than {@code runId} holds the goal now. */
    private synchronized boolean heldByAnother(String goalId, String runId) {
        Slot active = slots.get(goalId);
        return active != null && !active.runId.equals(runId);
    }

    /**
     * Writes what runs could not write when they ended: first the run-finished facts still owed, then the end of each
     * run the run store lists as unfinished although no slot holds it and no fact is owed for it ({@link
     * #closeUnfinished}, its goal settled). Does nothing while the kernel is stopping. What still fails is tried
     * again at the next sweep; one entry that fails does not hold back the others.
     */
    void sweep() {
        if (!running) {
            return;
        }
        for (Owed ending : List.copyOf(owed.values())) {
            try {
                settle(ending);
                owed.remove(ending.runId());
                log.info("recorded the end of run {} of goal {}", ending.runId(), ending.goalId());
            } catch (RuntimeException e) {
                log.debug("still cannot record the end of run {}: {}", ending.runId(), e.getMessage());
            }
        }
        List<RunStore.Unfinished> unfinished;
        try {
            unfinished = runs.unfinished();
        } catch (RuntimeException e) {
            log.debug("cannot read the unfinished runs: {}", e.getMessage());
            return;
        }
        for (RunStore.Unfinished u : unfinished) {
            if (!running) {
                return;
            }
            if (held(u.runId()) || owed.containsKey(u.runId())) {
                continue;
            }
            try {
                if (closeUnfinished(u, true)) {
                    log.info("run {} of goal {} ended without recording it; it is closed as interrupted", u.runId(),
                            u.goalId());
                }
            } catch (RuntimeException e) {
                log.debug("cannot close unfinished run {}: {}", u.runId(), e.getMessage());
            }
        }
    }

    /**
     * Closes a run that ended without closing its row in the run store: with the outcome of its run-finished fact
     * when the ledger has one, otherwise as interrupted, owing it a cancelled run-finished fact, and with
     * {@code settle} its goal the status its tasks call for. Returns whether it was closed as interrupted; false also
     * when it was closed meanwhile. Throws when the run store or the ledger cannot be read.
     */
    private boolean closeUnfinished(RunStore.Unfinished u, boolean settle) {
        Optional<RunOutcome> recorded = recordedOutcome(u.goalId(), u.runId());
        if (!runs.close(u.runId(), recorded.map(Enum::name).orElse(INTERRUPTED)) || recorded.isPresent()) {
            return false;
        }
        owe(new Owed(u.goalId(), u.runId(), u.trigger(), RunOutcome.CANCELLED, settle, "run interrupted"));
        return true;
    }

    /** Facts still owed, for tests. */
    int owedEndings() {
        return owed.size();
    }

    private void finishQuietly(String runId) {
        try {
            runs.finish(runId, "FAILED", 0, 0);
        } catch (RuntimeException again) {
            log.warn("could not close run {} that never started: {}", runId, again.getMessage());
        }
    }

    /** Goals with a run in progress. */
    public synchronized int activeRuns() {
        return slots.size();
    }

    /**
     * Closes the runs the previous process left unfinished ({@link #closeUnfinished}): those without a run-finished
     * fact are restarted in 10 s, as refine when they were create runs. Then settles the goals left planning or
     * waiting for an approval without a restarted run, and starts the sweep. A run store that cannot be read stops the
     * start.
     */
    @Override
    public void start() {
        running = true;
        Map<String, String> goals = new LinkedHashMap<>();
        for (RunStore.Unfinished u : runs.unfinished()) {
            if (closeUnfinished(u, false)) {
                goals.put(u.goalId(), "create".equals(u.trigger()) ? "refine" : u.trigger());
            }
        }
        settleStrandedGoals(goals.keySet());
        if (!goals.isEmpty()) {
            log.info("restarting {} interrupted runs in 10 s", goals.size());
            scheduler.schedule(() -> goals.forEach(this::restart), 10, TimeUnit.SECONDS);
        }
        scheduler.scheduleWithFixedDelay(this::sweep, SWEEP_INTERVAL.toMillis(), SWEEP_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void restart(String goalId, String trigger) {
        try {
            request(goalId, trigger, Set.of(Vocabulary.TASK_STATUS));
        } catch (RuntimeException e) {
            log.warn("could not restart the interrupted run of goal {}: {}", goalId, e.getMessage());
        }
    }

    /**
     * Goals the previous process left in PLANNING or AWAITING_APPROVAL although no run of theirs restarts (its last
     * status change was lost while it stopped) get the status their tasks call for. Goals whose interrupted run
     * restarts are left alone: that run sets their status.
     */
    void settleStrandedGoals(Set<String> restarted) {
        try {
            for (var goal : ledger.openGoals()) {
                if (unsettled(goal) && !restarted.contains(goal.goalId())) {
                    var status = settledStatus(goal);
                    log.info("goal {} was left {} without a run; it is {} again", goal.goalId(), goal.status(), status);
                    ledger.goalStatus(goal.goalId(), status, "no run left after a restart", "kernel");
                }
            }
        } catch (RuntimeException e) {
            log.warn("could not check goals left planning: {}", e.getMessage());
        }
    }

    /**
     * What a goal is without a run: failed without tasks (dropped tasks do not count), completed when every remaining
     * task is done, active otherwise.
     */
    static GoalStatus settledStatus(GoalState goal) {
        var live = GoalRunner.liveTasks(goal);
        if (live.isEmpty()) {
            return GoalStatus.FAILED;
        }
        return live.stream().noneMatch(t -> t.status().isOpen()) ? GoalStatus.COMPLETED : GoalStatus.ACTIVE;
    }

    /** Stops taking requests and interrupts the active runs, which stay unfinished for the next start. */
    @Override
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
