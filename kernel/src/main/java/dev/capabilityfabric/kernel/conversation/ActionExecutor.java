package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.constitution.ConstitutionService;
import dev.capabilityfabric.kernel.decisions.DecisionLog;
import dev.capabilityfabric.kernel.ecosystem.Counterfactual;
import dev.capabilityfabric.kernel.ecosystem.Genesis;
import dev.capabilityfabric.kernel.ecosystem.Reputation;
import dev.capabilityfabric.kernel.execution.Approvals;
import dev.capabilityfabric.kernel.execution.GoalRunner;
import dev.capabilityfabric.kernel.execution.RunCoordinator;
import dev.capabilityfabric.kernel.execution.RunStore;
import dev.capabilityfabric.kernel.inbox.InboxService;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerRules;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.memory.EpisodicMemory;
import dev.capabilityfabric.kernel.person.ProfileService;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.kernel.registry.Embeddings;
import dev.capabilityfabric.kernel.security.SecurityService;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Constitution;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Conversation.Action;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.Conversation.Executed;
import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Person;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.TaskStatus;
import dev.capabilityfabric.protocol.Vocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Executes validated actions. Every change goes through the same ledger rules, policy and run coordination as
 * capability effects; the interpreter's words never reach the ledger directly. Results describe what really
 * happened, which may differ from what the interpreter said it would do.
 */
@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final LedgerService ledger;
    private final RunCoordinator coordinator;
    private final RunStore runs;
    private final CapabilityRegistry registry;
    private final DecisionLog decisions;
    private final Approvals approvals;
    private final EpisodicMemory memory;
    private final SystemControl control;
    private final Embeddings embeddings;
    private final KernelProperties properties;
    private final Genesis genesis;
    private final Counterfactual counterfactual;
    private final Reputation reputation;
    private final ConstitutionService constitution;
    private final ProfileService profile;
    private final InboxService inbox;
    private final SecurityService security;
    /** The broker's certificate authority (PEM); its pin lets a new device trust the gateway on first contact. */
    private final String caFile;
    private volatile String caPin;

    public ActionExecutor(LedgerService ledger, RunCoordinator coordinator, RunStore runs, CapabilityRegistry registry,
                          DecisionLog decisions, Approvals approvals, EpisodicMemory memory, SystemControl control,
                          Embeddings embeddings, KernelProperties properties, Genesis genesis,
                          Counterfactual counterfactual, Reputation reputation, ConstitutionService constitution,
                          ProfileService profile, InboxService inbox, SecurityService security,
                          @Value("${fabric.nats.ca-file:}") String caFile) {
        this.security = security;
        this.caFile = caFile;
        this.constitution = constitution;
        this.profile = profile;
        this.inbox = inbox;
        this.genesis = genesis;
        this.counterfactual = counterfactual;
        this.reputation = reputation;
        this.ledger = ledger;
        this.coordinator = coordinator;
        this.runs = runs;
        this.registry = registry;
        this.decisions = decisions;
        this.approvals = approvals;
        this.memory = memory;
        this.control = control;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    /** A key id as the kernel resolves it: 16 lower-case hex characters. */
    static final Pattern KEY_ID = Pattern.compile("[0-9a-f]{16}");

    /**
     * Data gathered for the person while executing read actions. {@code sees} limits the goal lists to the goals the
     * caller may see before they are cut to length or counted; null means every goal.
     */
    public static final class Shown {
        final Predicate<String> sees;
        final List<KernelApi.GoalSummary> goals = new ArrayList<>();
        final List<KernelApi.GoalDetails> details = new ArrayList<>();
        final List<KernelApi.CapabilityView> capabilities = new ArrayList<>();
        final List<KernelApi.DecisionView> decisions = new ArrayList<>();
        final List<KernelApi.ApprovalView> approvals = new ArrayList<>();
        final List<KernelApi.LedgerHash> hashes = new ArrayList<>();
        final List<Conversation.AgendaItem> agenda = new ArrayList<>();
        final List<Ecosystem.ImpactReport> impacts = new ArrayList<>();
        final List<Ecosystem.Standing> ecosystem = new ArrayList<>();
        final List<Ecosystem.CapabilitySpec> grown = new ArrayList<>();
        final List<Constitution.Rule> rules = new ArrayList<>();
        final List<Inbox.Message> messages = new ArrayList<>();
        Person.Profile profile;
        Constitution.Impact ruleImpact;
        KernelApi.SystemStatus status;

        /** Data for a caller who sees every goal. */
        public Shown() {
            this(null);
        }

        /** Data for a caller who sees only the goals {@code sees} accepts (a guest). */
        public Shown(Predicate<String> sees) {
            this.sees = sees;
        }

        /** True when the caller may see the goal. */
        boolean sees(String goalId) {
            return sees == null || sees.test(goalId);
        }
    }

    /** What the agenda says to the owner before there is any open goal. */
    static final String NO_GOALS_YET = "You have no open goals yet. Tell me something you want to get done, for "
            + "example: \"Plan my sister's birthday party by Saturday\".";

    /** How many goals a list shows, how many tasks the agenda shows, and how many unread messages the inbox shows. */
    static final int LIST_LIMIT = 50;
    static final int AGENDA_LIMIT = 10;
    static final int INBOX_LIMIT = 100;

    /**
     * Lists the goals in {@code cut}, cut in the query before the list is limited. A guest's list is also limited to
     * their goals before it is cut to length, so it neither misses their goals nor counts the owner's. The line counts
     * every goal in the cut, not only those shown: "N goals" for the whole list ("N goals (M open)" when some are
     * closed), else the number of goals in the cut and its {@code label} ("0 overdue goals"); a list cut to
     * {@link #LIST_LIMIT} adds ", showing 50".
     */
    public Executed listGoals(LedgerService.GoalCut cut, String label, Shown shown) {
        LocalDate today = properties.today();
        List<KernelApi.GoalSummary> all = shown.sees == null ? ledger.list(LIST_LIMIT, cut, today)
                : ledger.list(Integer.MAX_VALUE, cut, today);
        List<KernelApi.GoalSummary> visible = all.stream().filter(g -> shown.sees(g.id())).toList();
        visible.stream().limit(LIST_LIMIT).forEach(shown.goals::add);
        int count = shown.sees == null ? Math.max(ledger.count(cut, today), shown.goals.size()) : visible.size();
        String line;
        if (cut == LedgerService.GoalCut.ALL || label == null) {
            int openShown = (int) visible.stream().filter(g -> g.status() != GoalStatus.COMPLETED
                    && g.status() != GoalStatus.CANCELLED).count();
            int open = shown.sees == null ? Math.max(ledger.count(LedgerService.GoalCut.OPEN, today), openShown)
                    : openShown;
            line = count + " goals" + (open < count ? " (" + open + " open)" : "");
        } else {
            line = count + " " + label;
        }
        return ok(ActionType.LIST_GOALS, line + showing(count, shown.goals.size()), null, null);
    }

    /** ", showing N" when a list shows fewer rows than it counts; empty otherwise. */
    static String showing(int count, int shownRows) {
        return shownRows < count ? ", showing " + shownRows : "";
    }

    /**
     * Which tasks a question about due dates asks for, among the open tasks of open goals, whether or not they can
     * start now: past their due date, due today, due tomorrow, due from today to the coming Sunday, or due in the week
     * after that (Monday to Sunday).
     */
    public enum TaskCut {
        OVERDUE, DUE_TODAY, DUE_TOMORROW, DUE_THIS_WEEK, DUE_NEXT_WEEK;

        /** True when a task due on {@code due} (null for none) belongs to this cut on {@code today}. */
        boolean holds(LocalDate due, LocalDate today) {
            if (due == null) {
                return false;
            }
            return switch (this) {
                case OVERDUE -> due.isBefore(today);
                case DUE_TODAY -> due.equals(today);
                case DUE_TOMORROW -> due.equals(today.plusDays(1));
                case DUE_THIS_WEEK -> !due.isBefore(today) && !due.isAfter(
                        today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)));
                case DUE_NEXT_WEEK -> {
                    LocalDate monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                    yield !due.isBefore(monday) && !due.isAfter(monday.plusDays(6));
                }
            };
        }
    }

    /**
     * Lists the open tasks of open goals in {@code cut}, the ones that wait on other tasks too, cut before the list is
     * limited to {@link #AGENDA_LIMIT} and ordered like the agenda. The line counts every task in the cut with its
     * {@code label} ("25 tasks due tomorrow"), says how many of them wait on other tasks, and adds ", showing 10"
     * when the list is cut.
     */
    public Executed listTasks(TaskCut cut, String label, Shown shown) {
        LocalDate today = properties.today();
        return listTasks(due -> cut.holds(due, today), label, shown);
    }

    /**
     * Lists the open tasks of open goals due from {@code from} to {@code to}, both included, the way
     * {@link #listTasks(TaskCut, String, Shown)} lists a cut ("2 tasks due this weekend").
     */
    public Executed listTasks(LocalDate from, LocalDate to, String label, Shown shown) {
        return listTasks(due -> due != null && !due.isBefore(from) && !due.isAfter(to), label, shown);
    }

    private Executed listTasks(Predicate<LocalDate> holds, String label, Shown shown) {
        LocalDate today = properties.today();
        List<Conversation.AgendaItem> items = new ArrayList<>();
        int waiting = 0;
        for (GoalState state : ledger.openGoals()) {
            if (!shown.sees(state.goalId())) {
                continue;
            }
            for (GoalContext.TaskView t : state.tasks()) {
                if (t.status().isOpen() && holds.test(t.dueDate())) {
                    items.add(item(state, t, today));
                    waiting += waits(state, t) ? 1 : 0;
                }
            }
        }
        items.sort(AGENDA_ORDER);
        items.stream().limit(AGENDA_LIMIT).forEach(shown.agenda::add);
        String what = items.size() == 1 ? label.replaceFirst("\\btasks\\b", "task") : label;
        return ok(ActionType.SHOW_AGENDA, items.size() + " " + what + (waiting == 0 ? ""
                : " (" + waiting + " waiting on other tasks)") + showing(items.size(), Math.min(AGENDA_LIMIT,
                items.size())), null, null);
    }

    /**
     * Carries out one action the conversation has validated and says what really happened; read actions put their
     * data into {@code shown}. {@code goalId} and {@code approvalId} are already resolved to full ids (or null when
     * not applicable).
     */
    public Executed execute(Action action, String goalId, String approvalId, String principal, Shown shown) {
        ActionType type = action.type();
        return switch (type) {
            case LIST_GOALS -> listGoals(LedgerService.GoalCut.ALL, null, shown);
            case SHOW_GOAL -> withGoal(type, goalId, id -> {
                shown.details.add(details(id));
                return ok(type, "showing goal", id, null);
            });
            case SHOW_AGENDA -> {
                List<Conversation.AgendaItem> all = agenda(Integer.MAX_VALUE, shown::sees);
                shown.agenda.addAll(all.subList(0, Math.min(AGENDA_LIMIT, all.size())));
                if (all.isEmpty() && shown.sees == null && ledger.openGoals().isEmpty()) {
                    yield ok(type, NO_GOALS_YET, null, null);
                }
                yield ok(type, all.size() + " tasks you can do now" + showing(all.size(), shown.agenda.size()), null,
                        null);
            }
            case EXPLAIN_IMPACT -> withGoal(type, goalId, id -> {
                String capability = resolveCapability(action.text());
                if (capability == null) {
                    return failed(type, "which capability? say its name, for example the estimator", id);
                }
                Ecosystem.ImpactReport report = counterfactual.explain(id, capability);
                shown.impacts.add(report);
                return ok(type, report.summary(), id, null);
            });
            case SHOW_ECOSYSTEM -> {
                shown.ecosystem.addAll(standings());
                shown.grown.addAll(genesis.all());
                long providers = shown.ecosystem.stream().map(Ecosystem.Standing::provider).distinct().count();
                yield ok(type, providers + " providers", null, null);
            }
            case KEEP_CAPABILITY -> {
                var spec = grownSpec(action.text());
                if (spec.isPresent()) {
                    boolean retired = spec.get().status() == Ecosystem.SpecStatus.RETIRED;
                    if (retired) {
                        // A retired capability's grant was withdrawn: keeping it hands it back to its host first.
                        security.extendForGrowth(spec.get());
                    }
                    genesis.setStatus(spec.get(), Ecosystem.SpecStatus.ACTIVE, principal, "kept by a person");
                    yield ok(type, "kept " + spec.get().id() + (retired ? "; it takes part in plans again"
                            : "; it is no longer on probation"), null, null);
                }
                var older = genesis.findQuarantined(action.text());
                if (older.isPresent()) {
                    // A grown capability without a signature waits in quarantine: keeping it signs it and hosts it.
                    try {
                        var kept = genesis.keepQuarantined(older.get().id(), principal);
                        yield ok(type, "kept " + kept.id() + " (\"" + kept.name() + "\") from the older version; it "
                                + "takes part in plans again", null, null);
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        yield failed(type, "keep " + older.get().id() + ": " + e.getMessage(), null);
                    }
                }
                String capability = resolveCapability(action.text());
                int reinstated = capability == null ? 0 : reputation.reinstate(capability);
                yield reinstated > 0 ? ok(type, "reinstated " + reinstated + " quarantined provider(s) of " + capability,
                        null, null) : failed(type, "no grown or quarantined capability matches \"" + action.text() + "\"", null);
            }
            case RETIRE_CAPABILITY -> {
                var spec = grownSpec(action.text());
                if (spec.isEmpty()) {
                    var older = genesis.findQuarantined(action.text());
                    if (older.isPresent()) {
                        // A grown capability without a signature waits in quarantine: retiring it removes its spec.
                        try {
                            var retired = genesis.retireQuarantined(older.get().id(), principal);
                            yield ok(type, "retired " + retired.id() + " (\"" + retired.name() + "\") from the older "
                                    + "version; its unsigned spec was removed", null, null);
                        } catch (IllegalArgumentException | IllegalStateException e) {
                            yield failed(type, "retire " + older.get().id() + ": " + e.getMessage(), null);
                        }
                    }
                    yield failed(type, "no capability the system grew, nor one from an older version that waits to be "
                            + "kept, matches \"" + action.text() + "\"; a built-in capability cannot be retired this "
                            + "way, stop its provider instead", null);
                }
                genesis.setStatus(spec.get(), Ecosystem.SpecStatus.RETIRED, principal, "retired by a person");
                // The host's grant stops covering it, so no agent may serve it or write its value any more.
                security.withdrawGrowth(spec.get().id(), spec.get().token());
                yield ok(type, "retired " + spec.get().id() + "; it no longer takes part in plans", null, null);
            }
            case REQUEST_CAPABILITY -> failed(type, "growing a capability needs a design first", goalId);
            case ADD_RULE, REMOVE_RULE, ANSWER_MESSAGE -> failed(type, "handled by the conversation", goalId);
            case LIST_ACCESS -> {
                List<String> lines = security.overview();
                yield ok(type, lines.isEmpty() ? "nobody has access yet" : String.join("\n    ", lines), null, null);
            }
            case PAIR_DEVICE -> {
                String code = security.newPairingCode();
                String kernelKey = security.identity() == null ? null : security.identity().keyId();
                yield ok(type, pairingText(reachable(properties.gatewayUrl()), caPin(), code, kernelKey), null, null);
            }
            case ADMIT_PROVIDER -> {
                // The exact key id the kernel resolved: from the question the person said yes to, or from the
                // kernel's own admission message. An agent is never admitted by a provider name, which any holder of
                // the agents' credentials may choose.
                String keyId = action.approval() == null ? "" : action.approval().strip();
                if (!KEY_ID.matcher(keyId).matches()) {
                    yield failed(type, "which agent? Run fabric without a sentence to review the agents waiting for "
                            + "admission", null);
                }
                // The hash of the contract the person was shown: carried by the question, or by the evidence of the
                // kernel's admission message's suggestion.
                yield security.admit(keyId, action.evidence(), principal).map(p -> failed(type, p, null))
                        .orElseGet(() -> ok(type, "the agent " + (action.text() == null ? keyId : action.text())
                                + " (key " + ConversationService.fingerprint(keyId) + ") is admitted", null, null));
            }
            case INVITE -> withGoal(type, goalId, id -> {
                if (action.dueDate() != null && action.dueDate().isBefore(properties.today())) {
                    return failed(type, action.dueDate() + " is in the past; an invitation can last until today or "
                            + "later", id);
                }
                String name = action.text() == null || action.text().isBlank() ? "guest" : action.text().strip();
                boolean viewOnly = viewOnly(action.reason());
                Instant until = action.dueDate() == null ? null
                        : action.dueDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
                String code = security.invite(name, id, viewOnly, until, principal);
                return ok(type, invitationText(name, reachable(properties.gatewayUrl()), code, viewOnly,
                        action.dueDate()), id, null);
            });
            case REVOKE_ACCESS -> {
                // The holder's exact reference the question resolved ("agent:<key id>", see
                // SecurityService.Holder#ref).
                String ref = action.text() != null ? action.text() : action.approval();
                String who = security.match(ref).stream().findFirst().map(h -> h.label()).orElse(ref);
                yield security.revoke(ref, principal).map(p -> failed(type, p, null))
                        .orElseGet(() -> ok(type, "access of " + who + " revoked; grants handed on from it end too",
                                null, null));
            }
            case LIST_RULES -> {
                shown.rules.addAll(constitution.active());
                yield ok(type, shown.rules.size() + " rules", null, null);
            }
            case SHOW_PROFILE -> {
                shown.profile = profile.profile();
                yield ok(type, "your profile", null, null);
            }
            case SHOW_INBOX -> {
                // The newest messages, so a reply stays small however many are unread; the line counts them all.
                List<Inbox.Message> unread = inbox.unread();
                shown.messages.addAll(unread.subList(Math.max(0, unread.size() - INBOX_LIMIT), unread.size()));
                yield ok(type, unread.size() + " unread messages", null, null);
            }
            case CORRECT_PROFILE -> {
                var correction = profile.correct(action.text(), action.hours(), action.reason(), principal);
                yield correction.ok() ? ok(type, "noted. " + correction.understood(), null, null)
                        : failed(type, correction.problem(), null);
            }
            case UPDATE_TASK -> withGoal(type, goalId, id -> {
                GoalState before = ledger.load(id).orElseThrow();
                var task = before.task(action.task());
                if (task.isEmpty() || (action.dueDate() == null && action.hours() == null)) {
                    return failed(type, "which task, and what should change: its due date or its hours?", id);
                }
                // The conversation has already read the date from the person's words.
                LocalDate due = action.dueDate();
                if (due != null && due.isBefore(properties.today())) {
                    return failed(type, due + " is in the past; " + task.get().key() + " can move to today or later", id);
                }
                Effects effects = Effects.ofUpdates(List.of(new Effects.TaskUpdate(task.get().key(), action.hours(),
                        null, null, due, null, null)));
                LedgerService.Applied applied = ledger.execute(id, state -> LedgerRules.applyEffects(state, effects,
                        person(principal), principal, null, properties.maxTasksPerGoal(), LedgerRules.randomIds()));
                if (!applied.ok()) {
                    return failed(type, String.join("; ", applied.errors()), id);
                }
                String runId = coordinator.request(id, "refresh", Set.of(Vocabulary.TASK_DUE_DATE,
                        Vocabulary.TASK_EFFORT_HOURS));
                return ok(type, task.get().key() + " \"" + task.get().title() + "\""
                        + (due == null ? "" : " is now due " + due)
                        + (action.hours() == null ? "" : " is now estimated at " + action.hours() + " h"), id, runId);
            });
            case REMOVE_TASK -> withGoal(type, goalId, id -> {
                LedgerService.Applied applied = ledger.changeStatus(id, action.task(), TaskStatus.DROPPED,
                        action.reason() == null ? "no longer needed" : action.reason(), null, principal);
                if (!applied.ok()) {
                    return failed(type, String.join("; ", applied.errors()), id);
                }
                String runId = coordinator.request(id, "refresh", GoalRunner.statusTokens());
                String title = applied.state().task(action.task()).map(t -> " \"" + t.title() + "\"").orElse("");
                return ok(type, action.task() + title + " dropped from \"" + text(id) + "\" (kept in its history)", id,
                        runId);
            });
            case RESCHEDULE_GOAL -> withGoal(type, goalId, id -> reschedule(action, id, principal));
            case LIST_CAPABILITIES -> {
                shown.capabilities.addAll(registry.views());
                yield ok(type, shown.capabilities.size() + " capabilities live", null, null);
            }
            case SHOW_DECISIONS -> withGoal(type, goalId, id -> {
                shown.decisions.addAll(decisions.forGoal(id));
                return ok(type, shown.decisions.size() + " model calls", id, null);
            });
            case LIST_APPROVALS -> {
                shown.approvals.addAll(approvals.pending());
                yield ok(type, shown.approvals.size() + " pending approvals", null, null);
            }
            case SYSTEM_STATUS -> {
                int providers = (int) registry.live().stream().map(CapabilityManifest::provider).distinct().count();
                shown.status = new KernelApi.SystemStatus(control.paused(), providers, coordinator.activeRuns(),
                        embeddings.modelName());
                yield ok(type, control.paused() ? "automatic work is paused" : "automatic work is running", null, null);
            }
            case VERIFY_GOAL -> withGoal(type, goalId, id -> {
                GoalState rebuilt = ledger.rebuild(id);
                GoalState stored = ledger.load(id).orElseThrow();
                boolean same = rebuilt.contentHash().equals(stored.contentHash());
                shown.hashes.add(new KernelApi.LedgerHash(id, rebuilt.contentHash(), rebuilt.tasks().size()));
                return new Executed(type, same, same ? "state matches its fact history"
                        : "state differs from its fact history", id, null);
            });
            case CREATE_GOAL -> {
                // The conversation has already read the deadline from the person's words.
                LocalDate due = action.dueDate();
                if (due != null && due.isBefore(properties.today())) {
                    yield failed(type, "the deadline " + due + " has already passed; say a date from today on", null);
                }
                LedgerService.Applied created = ledger.createGoal(action.text(), due, principal);
                if (!created.ok()) {
                    yield failed(type, String.join("; ", created.errors()), null);
                }
                String id = created.state().goalId();
                String runId = coordinator.request(id, "create", Set.of());
                yield ok(type, "created goal \"" + created.state().text() + "\""
                        + (created.state().dueDate() == null ? "" : " due " + created.state().dueDate())
                        + "; planning started" + rulesNote(created.state().text()), id, runId);
            }
            case REFINE_GOAL -> withGoal(type, goalId, id -> {
                if (cancelled(id)) {
                    return failed(type, "\"" + text(id) + "\" is cancelled, so it is not planned again; say \"bring it "
                            + "back\" to reopen it first", id);
                }
                // "plan it again with the shopping list": a named capability is pinned into the plan.
                String pinned = action.text() == null ? null : resolveCapability(action.text());
                String runId = coordinator.request(id, "refine", Set.of(), pinned == null ? Set.of() : Set.of(pinned));
                return runId == null ? failed(type, "too many runs for this goal in the last hour", id)
                        : ok(type, "re-planning \"" + text(id) + "\" with the capabilities live now"
                        + (pinned == null ? "" : ", including " + pinned), id, runId);
            });
            case ADD_TASK -> withGoal(type, goalId, id -> {
                // The conversation has already read the due date from the person's words.
                if (action.dueDate() != null && action.dueDate().isBefore(properties.today())) {
                    return failed(type, action.dueDate() + " is in the past; a new task can be due today or later", id);
                }
                Effects effects = new Effects(List.of(new Effects.NewTask("n1", action.text(), null, action.dueDate(),
                        List.of(), null)), List.of(), Map.of(), null);
                LedgerService.Applied applied = ledger.execute(id, state -> LedgerRules.applyEffects(state, effects,
                        person(principal), principal, null, properties.maxTasksPerGoal(), LedgerRules.randomIds()));
                if (!applied.ok()) {
                    return failed(type, String.join("; ", applied.errors()), id);
                }
                String runId = coordinator.request(id, "refine", Set.of(Vocabulary.TASKS));
                String key = applied.state().tasks().getLast().key();
                return ok(type, "added " + key + " \"" + action.text() + "\"" + (action.dueDate() == null ? ""
                        : " due " + action.dueDate()) + " to \"" + text(id) + "\"", id, runId);
            });
            case SET_TASK_STATUS -> withGoal(type, goalId, id -> {
                if (action.task() == null || action.status() == null) {
                    return failed(type, "which task, and which status?", id);
                }
                boolean already = ledger.load(id).flatMap(g -> g.task(action.task()))
                        .map(t -> t.status() == action.status()).orElse(false);
                LedgerService.Applied applied = ledger.changeStatus(id, action.task(), action.status(),
                        action.reason(), action.hours(), principal);
                if (!applied.ok()) {
                    return failed(type, String.join("; ", applied.errors()), id);
                }
                GoalContext.TaskView task = applied.state().task(action.task()).orElseThrow();
                // Only the owner's own work teaches the system how long work takes: a guest's finished tasks and
                // hours are kept in the ledger, but never in the memory of outcomes or the providers' reputation, so
                // they cannot steer the estimates of the owner's goals.
                boolean owners = ownerPrincipal(principal);
                if (already) {
                    // A done task reported done again with its real hours: only the hours are new.
                    if (owners) {
                        memory.recordTaskOutcome(id, applied.state().text(), task, action.hours());
                        reputation.recordOutcome(id, task.id(), action.hours());
                    }
                    return ok(type, task.key() + " \"" + task.title() + "\" was already done; " + action.hours()
                            + " h recorded", id, null);
                }
                if (action.status() == TaskStatus.DONE && owners) {
                    memory.recordTaskOutcome(id, applied.state().text(), task, action.hours());
                    if (action.hours() != null) {
                        reputation.recordOutcome(id, task.id(), action.hours());
                    }
                }
                String runId = coordinator.request(id, "refresh", GoalRunner.statusTokens());
                return ok(type, task.key() + " \"" + task.title() + "\" is now " + LedgerRules.readable(action.status())
                        + (action.hours() == null ? "" : " (" + action.hours() + " h recorded)"), id, runId);
            });
            case APPROVE_STEP, REJECT_STEP -> {
                if (approvalId == null) {
                    yield failed(type, approvals.pending().isEmpty() ? ConversationService.NOTHING_TO_APPROVE
                            : "tell which step you mean; say its id or its capability", null);
                }
                String error = approvals.decide(approvalId, type == ActionType.APPROVE_STEP, principal);
                yield error == null ? ok(type, type == ActionType.APPROVE_STEP ? "approved" : "rejected", null, null)
                        : failed(type, error, null);
            }
            case CANCEL_GOAL -> withGoal(type, goalId, id -> {
                LedgerService.Applied applied = ledger.goalStatus(id, GoalStatus.CANCELLED, "cancelled in conversation",
                        principal);
                if (applied.ok()) {
                    approvals.cancelFor(id); // steps waiting for approval on this goal no longer matter
                }
                return applied.ok() ? ok(type, "cancelled \"" + text(id) + "\" (say \"bring it back\" to reopen it)", id, null)
                        : failed(type, String.join("; ", applied.errors()), id);
            });
            case REOPEN_GOAL -> withGoal(type, goalId, id -> {
                LedgerService.Applied applied = ledger.execute(id, state -> LedgerRules.reopen(state, principal));
                if (!applied.ok()) {
                    return failed(type, String.join("; ", applied.errors()), id);
                }
                String runId = coordinator.request(id, "refresh", GoalRunner.statusTokens());
                return ok(type, "\"" + text(id) + "\" is open again", id, runId);
            });
            case PAUSE -> {
                control.pause(principal, action.reason());
                yield ok(type, "automatic work paused", null, null);
            }
            case RESUME -> {
                int restarted = control.resume(principal);
                yield ok(type, "automatic work resumed" + (restarted > 0 ? "; " + restarted + " runs restarted" : ""),
                        null, null);
            }
        };
    }

    /**
     * The confirmation question for an action that needs the person's yes, phrased from facts, not from the model:
     * it names the goal, the task with its title, the step or the capability that changes.
     */
    public String question(Action action, String goalId, String approvalId) {
        return switch (action.type()) {
            case CANCEL_GOAL -> "Cancel the goal " + goal(goalId) + "? You can bring it back later.";
            case REJECT_STEP -> "Reject " + step(approvalId) + "?";
            case PAUSE -> "Pause all automatic work (no capability calls until you resume)?";
            case RESUME -> "Resume automatic work (capability calls start again and runs the pause stopped restart)?";
            case REMOVE_TASK -> "Drop " + task(goalId, action.task()) + " from " + goal(goalId)
                    + "? It stays in the goal's history.";
            case RETIRE_CAPABILITY -> "Retire " + capability(action.text()) + "? It no longer takes part in plans.";
            case APPROVE_STEP -> "Approve " + step(approvalId) + "? It runs as soon as you say yes.";
            case KEEP_CAPABILITY -> keepQuestion(action.text());
            case PAIR_DEVICE -> "Pair a new device as owner? The code shown next lets the device that uses it act as "
                    + "you, with full access, until you revoke it; it works once, for 30 minutes.";
            default -> capitalise(describe(action, goalId, approvalId)) + "?";
        };
    }

    /**
     * The question before a capability is kept, naming it and its state: a grown capability on probation ("Keep ...
     * for good? It is on probation now."), a retired one ("It was retired; keeping it hands its grant back so it takes
     * part in plans again."), a quarantined grown capability without a signature ("It is quarantined:
     * keeping it signs it again and hosts it."), or a live capability whose quarantined providers it reinstates.
     */
    String keepQuestion(String ref) {
        var spec = grownSpec(ref);
        if (spec.isPresent()) {
            boolean retired = spec.get().status() == Ecosystem.SpecStatus.RETIRED;
            return "Keep " + capability(ref) + (retired
                    ? "? It was retired; keeping it hands its grant back so it takes part in plans again."
                    : " for good? It is " + (spec.get().status() == Ecosystem.SpecStatus.PROBATION ? "on probation"
                    : spec.get().status().name().toLowerCase(Locale.ROOT).replace('_', ' ')) + " now.");
        }
        return genesis.findQuarantined(ref).map(sp -> "Keep the grown capability " + sp.id() + " (\""
                        + Grounding.oneLine(sp.name()) + "\") from the older version? It is quarantined: keeping it "
                        + "signs it again and hosts it, so it takes part in plans.")
                .orElse("Reinstate the quarantined providers of " + named(ref, "a capability")
                        + "? They take part in plans again.");
    }

    /**
     * The name of a capability by its id: a grown capability's, a quarantined one's that waits to be kept, or a live
     * one's; null when none is known.
     */
    public String capabilityName(String id) {
        if (id == null) {
            return null;
        }
        return grownSpec(id).map(Ecosystem.CapabilitySpec::name)
                .or(() -> genesis.findQuarantined(id).map(Ecosystem.CapabilitySpec::name))
                .or(() -> registry.views().stream().filter(v -> v.id().equals(id)).map(KernelApi.CapabilityView::name)
                        .filter(Objects::nonNull).findFirst())
                .orElse(null);
    }

    /**
     * Moves the goal's deadline to the action's date; when the goal had one, open tasks' due dates move by the same
     * number of days, so "move the trip a week" moves the whole plan. Deadline and task dates move in one transaction.
     * The date is the one the conversation read from the person's words (or the one a suggestion carries): an action
     * without a date is refused, and no date is ever read from the action's free text.
     */
    private Executed reschedule(Action action, String goalId, String principal) {
        GoalState before = ledger.load(goalId).orElseThrow();
        LocalDate to = action.dueDate();
        if (to == null) {
            return failed(ActionType.RESCHEDULE_GOAL, "say the date you mean: no new deadline was given", goalId);
        }
        if (to.isBefore(properties.today())) {
            return failed(ActionType.RESCHEDULE_GOAL, to + " is in the past; a deadline can move to today or later", goalId);
        }
        LedgerService.Applied applied = ledger.reschedulePlan(goalId, to, action.reason(), person(principal), principal);
        if (!applied.ok()) {
            return failed(ActionType.RESCHEDULE_GOAL, String.join("; ", applied.errors()), goalId);
        }
        long moved = applied.events().stream().filter(e -> Facts.TASK_UPDATED.equals(e.type())).count();
        String runId = coordinator.request(goalId, "refresh", Set.of(Vocabulary.GOAL_DUE_DATE, Vocabulary.TASK_DUE_DATE));
        return ok(ActionType.RESCHEDULE_GOAL, "\"" + before.text() + "\" is now due " + to
                + (moved > 0 ? "; " + moved + " task dates moved with it" : ""), goalId, runId);
    }

    /**
     * What a deadline move does to the goal's tasks, as the move does it (see {@link LedgerRules#reschedulePlan}),
     * for the question that asks about the move: "(3 open task dates move 7 days later with it)", with ", none before
     * today" when some would land before today and stop there. Empty when the goal has no deadline, the deadline
     * stays, or no open task date moves.
     */
    static String cascade(GoalState goal, LocalDate to, LocalDate today) {
        if (goal == null || to == null || goal.dueDate() == null) {
            return "";
        }
        long delta = ChronoUnit.DAYS.between(goal.dueDate(), to);
        long moved = 0;
        boolean stopped = false;
        for (GoalContext.TaskView t : goal.tasks()) {
            if (!t.status().isOpen() || t.dueDate() == null) {
                continue;
            }
            LocalDate shifted = t.dueDate().plusDays(delta);
            if (shifted.isBefore(today)) {
                shifted = today;
                stopped = true;
            }
            if (!shifted.equals(t.dueDate())) {
                moved++;
            }
        }
        if (delta == 0 || moved == 0) {
            return "";
        }
        long days = Math.abs(delta);
        return " (" + moved + (moved == 1 ? " open task date moves " : " open task dates move ") + days
                + (days == 1 ? " day " : " days ") + (delta > 0 ? "later" : "earlier") + " with it"
                + (stopped ? ", none before today" : "") + ")";
    }

    static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * A profile correction in words, as it would be stored: "remember that you work Monday to Thursday" rather than
     * the key and the model's number (see {@link #lastingValue}); the person's words follow in quotes, unless the
     * correction carries the days as they are stored.
     */
    static String describeCorrection(Action action, List<String> currentDays) {
        String said = action.reason() == null ? "" : action.reason();
        String quoted = said.isBlank() || ProfileService.storedDays(said).isPresent() ? "" : " (\"" + said + "\")";
        String label = profileLabel(action.text());
        String value = lastingValue(action, currentDays);
        if (value != null) {
            return "remember that " + value + quoted;
        }
        return switch (label) {
            case "daily hours" -> "remember your daily hours" + quoted;
            case "working days" -> "remember your working days" + quoted;
            default -> label.startsWith("pace") ? "remember how long tasks take you compared with their estimates"
                    + quoted : "remember that your " + label + quoted;
        };
    }

    /**
     * The value a profile correction stores, in words that state it as lasting: "you work Monday to Thursday", "you
     * have 2 h a day for your tasks", "tasks take you half the estimate (you are faster)" (see
     * {@link ProfileService#paceWords}). The days are the stored days the correction carries ("MONDAY,TUESDAY"), else
     * those its words give against {@code currentDays}; the hours and the pace are its number (for the pace, else the
     * factor its words give). Null when the correction gives no value.
     */
    static String lastingValue(Action action, List<String> currentDays) {
        String said = action.reason() == null ? "" : action.reason();
        String label = profileLabel(action.text());
        if (label.equals("daily hours")) {
            return action.hours() == null ? null : "you have " + number(action.hours()) + " h a day for your tasks";
        }
        if (label.equals("working days")) {
            List<String> days = ProfileService.storedDays(said)
                    .orElseGet(() -> ProfileService.workingDays(said, currentDays));
            return days.isEmpty() ? null : "you work " + ProfileService.describeDays(days);
        }
        if (label.startsWith("pace")) {
            Double factor = action.hours() != null ? action.hours() : ProfileService.factor(said).orElse(null);
            return factor == null ? null : ProfileService.paceWords(factor);
        }
        return null;
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** A profile key as the person reads it. */
    static String profileLabel(String key) {
        String k = key == null ? "" : key.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        return k.contains("hour") || k.contains("capacity") ? "daily hours"
                : k.contains("day") ? "working days"
                : k.contains("bias") || k.contains("estimate") || k.contains("pace") ? "pace (how long tasks take "
                + "compared with their estimates)" : key;
    }

    /** How long a grown capability may take to go live before its goal is re-planned with it. */
    static final Duration GROWTH_WAIT = Duration.ofSeconds(40);

    /**
     * Grows a designed and confirmed capability, while there is room for one more (see {@link #growthFull}). The spec
     * is checked first; then the grants of the agents that host grown capabilities widen by exactly this capability,
     * so the host may serve it the moment it sees it; then the spec is published. The confirmation does not wait for
     * the capability to join: when it was asked for a goal, that goal is re-planned with it in the background once it
     * is live (within {@link #GROWTH_WAIT}). A failure is reported as a failed result, so the rest of a confirmation
     * still runs.
     */
    public Executed grow(Ecosystem.CapabilitySpec spec, String goalId, String principal) {
        Optional<String> full = growthFull();
        if (full.isPresent()) {
            return failed(ActionType.REQUEST_CAPABILITY, "grow " + spec.id() + ": " + full.get(), goalId);
        }
        // Every id that is live, filed in the genesis bucket or ever had a grown fact: a new capability never takes
        // an old one's id and with it its record.
        List<String> problems = Ecosystem.validate(spec, genesis.takenIds());
        if (!problems.isEmpty()) {
            return failed(ActionType.REQUEST_CAPABILITY, "the capability " + spec.id() + " cannot be grown: "
                    + String.join("; ", problems), goalId);
        }
        security.extendForGrowth(spec);
        try {
            genesis.grow(spec, principal, Duration.ZERO);
        } catch (RuntimeException e) {
            security.withdrawGrowth(spec.id(), spec.token());
            return failed(ActionType.REQUEST_CAPABILITY, "grow " + spec.id() + ": " + e.getMessage(), goalId);
        }
        String message = "grew " + spec.id() + " (\"" + spec.name() + "\") on probation";
        boolean replan = goalId != null && !cancelled(goalId);
        if (registry.has(spec.id())) {
            if (!replan) {
                return ok(ActionType.REQUEST_CAPABILITY, message + cancelledNote(goalId), goalId, null);
            }
            String runId = coordinator.request(goalId, "refine", Set.of(), Set.of(spec.id()));
            return ok(ActionType.REQUEST_CAPABILITY, message + "; re-planning \"" + text(goalId) + "\" with it", goalId,
                    runId);
        }
        if (replan) {
            Thread.ofVirtual().name("growth-" + spec.id()).start(() -> replanWhenLive(spec.id(), goalId));
        }
        return ok(ActionType.REQUEST_CAPABILITY, message + "; it is joining now (agent-forge serves it)"
                + (replan ? " and \"" + text(goalId) + "\" is re-planned with it once it is live" : cancelledNote(goalId)),
                goalId, null);
    }

    /**
     * The most grown capabilities in use at once: the one agent that serves them all lists each, and its own
     * designer, in a registry that takes at most {@link CapabilityRegistry#MAX_ENTRIES_PER_KEY} entries from one key.
     */
    static final int MAX_GROWN = CapabilityRegistry.MAX_ENTRIES_PER_KEY - 1;

    /**
     * Why no capability can be grown now: {@link #MAX_GROWN} grown capabilities that are not retired are in use, and
     * one more would leave the agent that serves them unable to list them all. Empty while there is room.
     */
    public Optional<String> growthFull() {
        long inUse = genesis.all().stream().filter(g -> g.status() != Ecosystem.SpecStatus.RETIRED).count();
        return inUse < MAX_GROWN ? Optional.empty() : Optional.of(inUse
                + " grown capabilities are in use, the most one agent can serve; retire one first (\"retire <its id>\", "
                + "\"how are the capabilities doing?\" lists them)");
    }

    /** Says that a cancelled goal is not re-planned; empty for no goal or an open one. */
    private String cancelledNote(String goalId) {
        return goalId != null && cancelled(goalId) ? "; \"" + text(goalId) + "\" is cancelled, so it is not planned again"
                : "";
    }

    /**
     * Waits for a grown capability to go live, then re-plans the goal with it unless the goal was cancelled meanwhile.
     */
    void replanWhenLive(String capabilityId, String goalId) {
        if (!waitUntilLive(capabilityId, GROWTH_WAIT)) {
            log.warn("grown capability {} did not go live within {} s; \"{}\" was not re-planned with it",
                    capabilityId, GROWTH_WAIT.toSeconds(), goalId);
            return;
        }
        if (!cancelled(goalId)) {
            coordinator.request(goalId, "refine", Set.of(), Set.of(capabilityId));
        }
    }

    private boolean waitUntilLive(String capabilityId, Duration wait) {
        long deadline = System.currentTimeMillis() + wait.toMillis();
        while (!registry.has(capabilityId) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return registry.has(capabilityId);
    }

    private boolean cancelled(String goalId) {
        return ledger.load(goalId).map(g -> g.status() == GoalStatus.CANCELLED).orElse(false);
    }

    /** Tells the person which of their rules already cover a goal they just created. */
    private String rulesNote(String goalText) {
        List<Constitution.Rule> rules = constitution.rulesCovering(goalText);
        if (rules.isEmpty()) {
            return "";
        }
        return "; your rule" + (rules.size() > 1 ? "s " : " ") + String.join(", ", rules.stream()
                .map(r -> "\"" + r.text() + "\"").toList()) + (rules.size() > 1 ? " apply" : " applies") + " to it";
    }

    /** Words of an invitation that ask for viewing: "see", "view", "look", "read", "watch", "follow". */
    private static final Pattern VIEW_WORDS = Pattern.compile("\\b(?:see|view|look|read|watch|follow)\\b");
    /** Words of an invitation that ask for changing: "add", "change", "edit", "mark", "update", "help", "move". */
    private static final Pattern EDIT_WORDS = Pattern.compile("\\b(?:add|change|edit|mark|update|help|move)\\b");

    /** Whether an invitation sentence asks for viewing only ("let Tom see the picnic"), over any line breaks. */
    static boolean viewOnly(String said) {
        String t = said == null ? "" : said.toLowerCase(Locale.ROOT);
        return VIEW_WORDS.matcher(t).find() && !EDIT_WORDS.matcher(t).find();
    }

    /** What a guest may do, in words: view only or add and update tasks, and until when. */
    static String guestRights(boolean viewOnly, LocalDate until) {
        return (viewOnly ? "view only" : "can add tasks, update them and mark them done")
                + (until == null ? ", no end date" : ", until " + until);
    }

    /**
     * The gateway address as another machine reaches it: a loopback address ("https://localhost:8443") means nothing
     * there, so it becomes a placeholder for this machine's address with the same port.
     */
    static String reachable(String gatewayUrl) {
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            return "https://<this machine's address>:8443";
        }
        try {
            URI uri = URI.create(gatewayUrl.strip());
            String host = uri.getHost();
            if (host == null || !loopback(host)) {
                return gatewayUrl.strip();
            }
            int port = uri.getPort() < 0 ? ("http".equalsIgnoreCase(uri.getScheme()) ? 80 : 443) : uri.getPort();
            return (uri.getScheme() == null ? "https" : uri.getScheme()) + "://<this machine's address>:" + port;
        } catch (IllegalArgumentException e) {
            return gatewayUrl.strip();
        }
    }

    /** True for "localhost" and loopback IP literals (127.x.x.x, ::1); other names are not looked up. */
    static boolean loopback(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost") || h.toLowerCase(Locale.ROOT).endsWith(".localhost")) {
            return true;
        }
        if (!h.matches("[0-9.]+") && !h.matches("[0-9a-fA-F:]*:[0-9a-fA-F:]*")) {
            return false;
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** The invitation to hand to a guest: the command to run on their device, and what it allows. */
    static String invitationText(String name, String gatewayUrl, String code, boolean viewOnly, LocalDate until) {
        return "give " + name + " this invitation (it works once; " + guestRights(viewOnly, until) + "):\n    fabric "
                + "--gateway " + gatewayUrl + " --join " + code;
    }

    /**
     * The pairing instruction for the person's other device. {@code --trust-gateway} lets that device trust the
     * gateway's certificate authority on first contact, {@code --kernel} lets it check that it talks to this kernel
     * before it trusts anything; on this machine the default gateway works, so both gateway options can go.
     *
     * @param caPin the certificate authority's pin, or null when the CA certificate cannot be read
     */
    static String pairingText(String gatewayUrl, String caPin, String code, String kernelKeyId) {
        return "on your other device run:\n    fabric --gateway " + gatewayUrl
                + (caPin == null ? "" : " --trust-gateway " + caPin) + " --pair " + code
                + (kernelKeyId == null ? "" : " --kernel " + kernelKeyId)
                + "\n  (valid for 30 minutes, once; on this machine you can leave out --gateway"
                + (caPin == null ? "" : " and --trust-gateway") + ")";
    }

    /** The pin of the configured certificate authority, read once it is readable; null while it is not. */
    private String caPin() {
        String pin = caPin;
        if (pin == null) {
            pin = caPin(caFile).orElse(null);
            caPin = pin;
        }
        return pin;
    }

    /**
     * The first 32 lowercase hex characters of the SHA-256 of the (first) certificate's DER in a PEM file; empty
     * when no file is configured or it cannot be read.
     */
    static Optional<String> caPin(String caFile) {
        if (caFile == null || caFile.isBlank() || !Files.isRegularFile(Path.of(caFile))) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(Path.of(caFile))) {
            var certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            return Optional.of(HexFormat.of().formatHex(Security.sha256(certificate.getEncoded())).substring(0, 32));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Finds a capability by id or by (part of) its name, among live and grown ones. */
    public String resolveCapability(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String r = TargetCheck.normalise(ref);
        List<KernelApi.CapabilityView> views = registry.views();
        for (KernelApi.CapabilityView v : views) {
            if (TargetCheck.normalise(v.id()).equals(r)) {
                return v.id();
            }
        }
        String best = null;
        double bestScore = 0;
        for (KernelApi.CapabilityView v : views) {
            String text = v.id() + " " + (v.name() == null ? "" : v.name());
            double score = Embeddings.lexicalSimilarity(ref, text.replace('.', ' '))
                    + (TargetCheck.normalise(text).contains(r) ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = v.id();
            }
        }
        return bestScore > 0.15 ? best : genesis.find(ref).map(Ecosystem.CapabilitySpec::id).orElse(null);
    }

    /** The state shown for a grown capability from an older version that waits for the owner to keep it. */
    static String waitingToBeKept(String id) {
        return "waiting for your yes: say keep " + id;
    }

    /**
     * Every live provider with its earned standing; grown capabilities show their lifecycle. A provider with a record
     * that is not live shows as "not running", unless its capability is a quarantined grown one that waits to be kept
     * (see {@link #waitingToBeKept}) or one that was retired (including one whose spec is missing), which shows as
     * retired. A quarantined capability that waits to be kept is listed even when it has no record, without a
     * provider.
     */
    public List<Ecosystem.Standing> standings() {
        Map<String, Ecosystem.CapabilitySpec> specs = new HashMap<>();
        genesis.all().forEach(sp -> specs.put(sp.id(), sp));
        Set<String> waiting = new HashSet<>();
        genesis.quarantined().forEach(sp -> waiting.add(sp.id()));
        Set<String> retired = security.retiredCapabilities();
        List<Ecosystem.Standing> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (KernelApi.CapabilityView v : registry.views()) {
            seen.add(v.id() + "|" + v.provider());
            Ecosystem.Standing earned = reputation.standing(v.id(), v.provider()).orElse(null);
            Ecosystem.CapabilitySpec spec = specs.get(v.id());
            result.add(new Ecosystem.Standing(v.id(), v.provider(), v.kind(), spec != null,
                    spec != null ? spec.status().name() : "live",
                    earned == null ? 0 : earned.calls(), earned == null ? 0 : earned.failures(),
                    earned == null ? 0 : earned.outcomes(), earned == null ? null : earned.meanLogError(),
                    earned == null ? 0 : earned.accepted(), earned == null ? 0 : earned.rejected(),
                    earned == null ? 0.5 : earned.score(), earned != null && earned.quarantined()));
        }
        for (Ecosystem.Standing earned : reputation.all()) {
            if (seen.add(earned.capability() + "|" + earned.provider())) {
                String id = earned.capability();
                String state = waiting.contains(id) ? waitingToBeKept(id)
                        : retired.contains(id) ? Ecosystem.SpecStatus.RETIRED.name() : "not running";
                result.add(new Ecosystem.Standing(id, earned.provider(), null,
                        specs.containsKey(id) || waiting.contains(id) || retired.contains(id), state, earned.calls(),
                        earned.failures(), earned.outcomes(), earned.meanLogError(), earned.accepted(),
                        earned.rejected(), earned.score(), earned.quarantined()));
            }
        }
        Set<String> shownIds = new HashSet<>();
        result.forEach(r -> shownIds.add(r.capability()));
        for (String id : waiting) {
            if (shownIds.add(id)) {
                // Kept by nobody yet and so never called: listed so the owner sees what waits for their yes.
                result.add(new Ecosystem.Standing(id, "", null, true, waitingToBeKept(id), 0, 0, 0, null, 0, 0, 0.5,
                        false));
            }
        }
        return result;
    }

    /** {@link #describe(Action, String, String)} with the approval the action names. */
    public String describe(Action action, String goalId) {
        return describe(action, goalId, action.approval());
    }

    /**
     * What an action would do, in words, for "Did you mean ...?" and "confirm it" questions and for refusals: every
     * kind of action names what it is about (the goal, the task with its title, the step, the capability, the rule).
     */
    public String describe(Action action, String goalId, String approvalId) {
        String goal = goal(goalId);
        return switch (action.type()) {
            case LIST_GOALS -> "list your goals";
            case SHOW_GOAL -> "show " + goal;
            case SHOW_AGENDA -> "show what you can do now";
            case LIST_CAPABILITIES -> "list the live capabilities";
            case SHOW_DECISIONS -> "show the model calls made for " + goal;
            case LIST_APPROVALS -> "list the steps waiting for approval";
            case SYSTEM_STATUS -> "show the state of the system";
            case VERIFY_GOAL -> "check " + goal + " against its fact history";
            case EXPLAIN_IMPACT -> "explain what " + named(action.text(), "a capability") + " changed in " + goal;
            case SHOW_ECOSYSTEM -> "show how the capabilities have done";
            case LIST_RULES -> "list your rules";
            case SHOW_PROFILE -> "show your profile";
            case SHOW_INBOX -> "show your unread messages";
            case LIST_ACCESS -> "show who has access";
            case CREATE_GOAL -> "create the goal " + named(action.text(), "with no text")
                    + (action.dueDate() == null ? " with no deadline" : " due " + action.dueDate());
            case REFINE_GOAL -> {
                // The question names the capability that would be pinned, never the model's words for it.
                String pinned = resolveCapability(action.text());
                yield "re-plan " + goal + (pinned == null ? "" : " with " + pinned);
            }
            case ADD_TASK -> "add the task " + named(action.text(), "with no title") + " to " + goal
                    + (action.dueDate() == null ? "" : " due " + action.dueDate());
            case SET_TASK_STATUS -> "mark " + task(goalId, action.task()) + " in " + goal + " as "
                    + LedgerRules.readable(action.status()) + (action.hours() == null ? "" : " (" + action.hours() + " h)");
            case UPDATE_TASK -> "set " + task(goalId, action.task()) + " in " + goal
                    + (action.dueDate() == null ? "" : " due " + action.dueDate())
                    + (action.hours() == null ? "" : " to " + action.hours() + " h");
            case RESCHEDULE_GOAL -> "move the deadline of " + goal + " to "
                    + (action.dueDate() == null ? "a date I could not read" : action.dueDate()
                    + cascade(goalId == null ? null : ledger.load(goalId).orElse(null), action.dueDate(),
                    properties.today()));
            case REOPEN_GOAL -> "reopen " + goal;
            case CANCEL_GOAL -> "cancel " + goal;
            case REMOVE_TASK -> "drop " + task(goalId, action.task()) + " from " + goal;
            case APPROVE_STEP -> "approve " + step(approvalId);
            case REJECT_STEP -> "reject " + step(approvalId);
            case RESUME -> "resume automatic work";
            case PAUSE -> "pause all automatic work";
            case KEEP_CAPABILITY -> grownSpec(action.text()).isPresent()
                    ? "keep " + capability(action.text()) + " for good"
                    : genesis.findQuarantined(action.text()).map(sp -> "keep the grown capability " + sp.id() + " (\""
                    + sp.name() + "\") from the older version, signing it again so it takes part in plans")
                    .orElse("reinstate the quarantined providers of " + named(action.text(), "a capability"));
            case RETIRE_CAPABILITY -> "retire " + capability(action.text());
            case REQUEST_CAPABILITY -> "grow a new capability for " + named(action.text(), "this need");
            case CORRECT_PROFILE -> describeCorrection(action, profile.profile().workingDays());
            case ANSWER_MESSAGE -> (ConversationService.DISMISS.equals(action.text()) ? "dismiss" : "accept")
                    + " the message" + (action.approval() == null ? "" : " " + action.approval().substring(0,
                    Math.min(8, action.approval().length())));
            case PAIR_DEVICE -> "pair a new device as owner (a code that lets it act as you)";
            case ADD_RULE -> "make the rule " + named(action.text(), "you described");
            case REMOVE_RULE -> "repeal the rule " + named(action.text(), "you named");
            case ADMIT_PROVIDER -> "admit the agent " + (action.text() != null ? action.text() : action.approval());
            case INVITE -> "invite " + action.text() + " to " + goal + " ("
                    + guestRights(viewOnly(action.reason()), action.dueDate()) + ")";
            case REVOKE_ACCESS -> "end the access of " + security.match(action.text()).stream().findFirst()
                    .map(SecurityService.Holder::label).orElse(action.text());
        };
    }

    /** A goal by its text in quotes, or a plain phrase when no goal was resolved. */
    private String goal(String goalId) {
        return goalId == null ? "a goal I could not tell" : "\"" + text(goalId) + "\"";
    }

    /** A task by key and title ("T3 \"Book the venue\""), or by key alone when the title is unknown. */
    private String task(String goalId, String key) {
        if (key == null) {
            return "a task I could not tell";
        }
        String title = goalId == null ? null : ledger.load(goalId).flatMap(s -> s.task(key))
                .map(GoalContext.TaskView::title).orElse(null);
        return key + (title == null ? "" : " \"" + title + "\"");
    }

    /** A step waiting for approval by its capability and goal, or by its id when it is no longer pending. */
    private String step(String approvalId) {
        if (approvalId == null) {
            return "a step I could not tell";
        }
        return approvals.pending().stream().filter(a -> a.id().startsWith(approvalId)).findFirst()
                .map(a -> "the step " + a.capability() + " on " + goal(a.goalId()) + " ("
                        + a.id().substring(0, Math.min(8, a.id().length())) + ")")
                .orElse("the step " + approvalId.substring(0, Math.min(8, approvalId.length())));
    }

    /** A grown capability by id and name, or the words used when none matches. */
    private String capability(String ref) {
        return grownSpec(ref).map(s -> "the grown capability " + s.id() + " (\"" + s.name() + "\")")
                .orElse("the capability " + named(ref, "you named"));
    }

    /** Text in quotes, or {@code otherwise} when there is none. */
    private static String named(String text, String otherwise) {
        return text == null || text.isBlank() ? otherwise : "\"" + text.strip() + "\"";
    }

    /**
     * The grown capability the words name: by exact id first, then by name or id within the words. Empty for blank
     * words, so a missing reference never matches some capability.
     */
    public Optional<Ecosystem.CapabilitySpec> grownSpec(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String r = ref.strip();
        return genesis.all().stream().filter(s -> s.id().equalsIgnoreCase(r)).findFirst().or(() -> genesis.find(r));
    }

    /**
     * Open tasks that can start now (no open dependencies, not blocked), across open goals: overdue first, then by
     * due date, deadline risk and priority ({@link #AGENDA_ORDER}). Deterministic, so the answer to "what should I do
     * today?" is repeatable.
     */
    public List<Conversation.AgendaItem> agenda(int limit) {
        return agenda(limit, goalId -> true);
    }

    /** {@link #agenda(int)} over the goals {@code sees} accepts, filtered before the list is cut to {@code limit}. */
    public List<Conversation.AgendaItem> agenda(int limit, Predicate<String> sees) {
        LocalDate today = properties.today();
        List<Conversation.AgendaItem> items = new ArrayList<>();
        for (GoalState state : ledger.openGoals()) {
            if (!sees.test(state.goalId())) {
                continue;
            }
            for (GoalContext.TaskView t : state.tasks()) {
                if (t.status().isOpen() && !waits(state, t)) {
                    items.add(item(state, t, today));
                }
            }
        }
        items.sort(AGENDA_ORDER);
        return items.subList(0, Math.min(limit, items.size()));
    }

    /** Overdue first, then by due date (none last), deadline risk and priority. */
    private static final Comparator<Conversation.AgendaItem> AGENDA_ORDER = Comparator
            .comparing((Conversation.AgendaItem i) -> !i.overdue())
            .thenComparing(i -> i.dueDate() == null ? LocalDate.MAX : i.dueDate())
            .thenComparing(i -> !"HIGH".equals(i.risk()))
            .thenComparing(i -> -(i.priority() == null ? 0 : i.priority()));

    /** True when a task cannot start yet: it is blocked, or a task it depends on is still open. */
    private static boolean waits(GoalState state, GoalContext.TaskView t) {
        return t.status() == TaskStatus.BLOCKED || t.dependsOn().stream().anyMatch(dep ->
                state.tasks().stream().anyMatch(o -> o.id().equals(dep) && o.status().isOpen()));
    }

    /** A task as a row of the agenda, with its deadline risk and whether it is overdue on {@code today}. */
    private static Conversation.AgendaItem item(GoalState state, GoalContext.TaskView t, LocalDate today) {
        String risk = t.annotation("risk.deadline").map(r -> r.path("level").asString(null)).orElse(null);
        return new Conversation.AgendaItem(state.goalId(), state.text(), t.key(), t.title(), t.dueDate(),
                t.effortHours(), risk, t.priority(), t.dueDate() != null && t.dueDate().isBefore(today));
    }

    /** A goal for showing: its summary, annotations, tasks and its five most recent runs. */
    public KernelApi.GoalDetails details(String goalId) {
        GoalState state = ledger.load(goalId).orElseThrow();
        return new KernelApi.GoalDetails(ledger.summary(goalId).orElseThrow(), state.annotations(), state.tasks(),
                runs.recent(goalId, 5));
    }

    private String text(String goalId) {
        return ledger.load(goalId).map(GoalState::text).orElse(goalId);
    }

    private interface GoalAction {
        Executed run(String goalId);
    }

    private static Executed withGoal(ActionType type, String goalId, GoalAction body) {
        return goalId == null ? failed(type, "tell which goal you mean", null) : body.run(goalId);
    }

    private static Executed ok(ActionType type, String message, String goalId, String runId) {
        return new Executed(type, true, message, goalId, runId);
    }

    private static Executed failed(ActionType type, String message, String goalId) {
        return new Executed(type, false, message, goalId, null);
    }

    /**
     * True when the principal is the owner: the owner acts as {@code human:<name>} (or {@code human:anonymous} with
     * security off), a guest as {@code guest:<name>}.
     */
    static boolean ownerPrincipal(String principal) {
        return principal != null && principal.startsWith("human:");
    }

    /** People edit goal structure directly; they act at autonomy L2 within their own goals. */
    private static CapabilityManifest person(String principal) {
        return CapabilityManifest.builder("person.edit", "1.0.0")
                .description("Changes a person makes through conversation")
                .produces(Vocabulary.TASKS, Vocabulary.TASK_DEPENDS_ON, Vocabulary.TASK_DUE_DATE,
                        Vocabulary.TASK_EFFORT_HOURS)
                .build().withInstance("person", principal.replaceAll("[^A-Za-z0-9-]", "-"), Instant.now(), 60);
    }
}
