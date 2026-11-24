package dev.capabilityfabric.protocol;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Natural-language interaction. A person says something; the {@code intent.interpret} capability (a model)
 * turns it into actions from a closed vocabulary plus a reply; the kernel validates the actions, asks for
 * confirmation where policy requires it, executes them and reports what really happened.
 */
public final class Conversation {

    private Conversation() {
    }

    /**
     * The most characters the {@code intent.interpret} capability adds around the snapshot it is given: its system
     * instructions plus the fixed lines of its prompt (today, the calendar of the next 35 days, the "Goals:" heading
     * and the line that quotes the sentence). The prompt's other lines come from the snapshot's own entries. A
     * provider of intent.interpret keeps its fixed text within this bound, so the kernel can size the snapshot from
     * the model's context window.
     */
    public static final int INTERPRET_INSTRUCTION_CHARS = 9_200;

    /**
     * The most characters the answer's JSON schema adds to the {@code intent.interpret} prompt when the model server
     * cannot constrain decoding and the schema is written into the instructions, with the sentence that introduces it.
     */
    public static final int INTERPRET_SCHEMA_CHARS = 1_500;

    /**
     * How a sentence is answered. {@code CONVERSE} asks back whenever the kernel is unsure of a reading, and before
     * every consequential change. {@code ASSIST} acts and says what it did and how it read the words (every change can
     * be undone), and asks only before the security changes ({@link #SECURITY_ACTIONS}) and changes unusual for the
     * device; a goal or task the words do not name for sure is rejected with the candidates, never guessed.
     * {@code DECIDE}, for other services, never asks: every action is done or rejected with a reason, and the security
     * changes and the unusual ones are rejected (they need the owner on a device).
     */
    public enum Mode { CONVERSE, ASSIST, DECIDE }

    /** Changes to who may act: always confirmed by the owner on a device, never done by a sentence alone. */
    public static final Set<ActionType> SECURITY_ACTIONS = Set.of(ActionType.PAIR_DEVICE, ActionType.INVITE,
            ActionType.REVOKE_ACCESS, ActionType.ADMIT_PROVIDER);

    /** What a person said, in their own words, and how it is to be answered (null: {@link Mode#CONVERSE}). */
    public record Utterance(String text, String sessionId, String principal, Mode mode) {
        public Utterance(String text, String sessionId, String principal) {
            this(text, sessionId, principal, null);
        }
    }

    /**
     * Typed actions for a caller that knows what it wants (another service, a script): each names its goal by id (or
     * short id) and its task by key, and is done or rejected with a reason, without a language model and without a
     * question (see {@link Subjects#INTENT_EXECUTE}).
     */
    public record Command(List<Action> actions, String sessionId, String principal) {
        public Command {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    /**
     * How a turn ended, for a caller that needs one word: every change done ({@code COMPLETED}), some done and some
     * rejected ({@code PARTIAL}), nothing done because every change was rejected ({@code REJECTED}), a question waits
     * ({@code ASKED}, never in {@link Mode#DECIDE}), or only data or a text was given back ({@code ANSWERED}).
     */
    public enum Outcome { COMPLETED, PARTIAL, REJECTED, ASKED, ANSWERED }

    /**
     * How the kernel's text begins when it could not read a sentence, or did nothing it asked for ("Nothing was
     * changed: I cancel or drop one goal or task at a time"): such a turn is {@link Outcome#REJECTED} (see
     * {@link #notUnderstood}), not answered.
     */
    public static final List<String> NOT_UNDERSTOOD = List.of("Sorry, I could not", "That was a lot at once",
            "I only understand English", "No assistant is connected", "Nothing was changed");

    /** True when a turn did nothing and its text says the sentence could not be read (see {@link #NOT_UNDERSTOOD}). */
    public static boolean notUnderstood(Reply reply) {
        return reply.executed().isEmpty() && reply.confirmation() == null && reply.reply() != null
                && NOT_UNDERSTOOD.stream().anyMatch(reply.reply()::startsWith);
    }

    /** How {@code reply} ended (see {@link Outcome}). */
    public static Outcome outcome(Reply reply) {
        if (reply.confirmation() != null) {
            return Outcome.ASKED;
        }
        if (notUnderstood(reply)) {
            return Outcome.REJECTED;
        }
        long done = reply.executed().stream().filter(e -> e.ok() && !e.type().readOnly()).count();
        long rejected = reply.executed().stream().filter(e -> !e.ok()).count();
        return rejected == 0 ? (done > 0 ? Outcome.COMPLETED : Outcome.ANSWERED)
                : done > 0 ? Outcome.PARTIAL : Outcome.REJECTED;
    }

    /**
     * Why an action was not done, as a stable code a calling service can act on: AMBIGUOUS_TARGET (the words fit
     * several goals or tasks; the message names them), UNKNOWN_TARGET (no goal or task matches), DATE_PASSED,
     * UNCLEAR_VALUE (a date, hours or status that could not be read for sure), FORBIDDEN (outside the caller's
     * grant), RULE (one of the owner's rules forbids it or asks for the owner's yes), NEEDS_OWNER (a security change),
     * INVALID (the change does not fit the task or goal as it stands); NOT_DONE for any other reason. Null for an
     * action that was done. A sentence that could not be read at all is NOT_UNDERSTOOD (see {@link #notUnderstood}).
     */
    public static String reasonCode(Executed e) {
        if (e.ok()) {
            return null;
        }
        String m = e.message() == null ? "" : e.message().toLowerCase(java.util.Locale.ROOT);
        return m.contains("tell which goal") || m.contains("tell which task") || m.contains("fits more than one")
                ? "AMBIGUOUS_TARGET"
                : m.contains("find a goal matching") || m.contains("no goal or task of yours")
                || m.contains("find the task") || m.contains("no task") || m.contains("matches no") ? "UNKNOWN_TARGET"
                : m.contains("has already passed") || m.contains("is in the past") ? "DATE_PASSED"
                : m.startsWith("say the ") ? "UNCLEAR_VALUE"
                : m.contains("not part of what you were invited") ? "FORBIDDEN"
                : m.contains("forbids it") || m.contains("ask the owner") || m.contains("says to ask") ? "RULE"
                : m.contains("needs the owner") ? "NEEDS_OWNER"
                : m.contains("only open tasks") || m.contains("is still open") || m.contains("already") ? "INVALID"
                : "NOT_DONE";
    }

    /** The actions the system can take. The interpreter may only choose from these. */
    public enum ActionType {
        // read
        LIST_GOALS, SHOW_GOAL, SHOW_AGENDA, LIST_CAPABILITIES, SHOW_DECISIONS, LIST_APPROVALS, SYSTEM_STATUS,
        VERIFY_GOAL,
        EXPLAIN_IMPACT, SHOW_ECOSYSTEM, LIST_RULES, SHOW_PROFILE, SHOW_INBOX,
        // change
        CREATE_GOAL, REFINE_GOAL, ADD_TASK, SET_TASK_STATUS, UPDATE_TASK, RESCHEDULE_GOAL, REOPEN_GOAL, RESUME,
        CORRECT_PROFILE, ANSWER_MESSAGE,
        // read
        LIST_ACCESS,
        // change that needs the person's confirmation first (RESUME too, see CONFIRM)
        CANCEL_GOAL, REMOVE_TASK, APPROVE_STEP, REJECT_STEP, PAUSE, REQUEST_CAPABILITY, KEEP_CAPABILITY,
        RETIRE_CAPABILITY, ADD_RULE, REMOVE_RULE, ADMIT_PROVIDER, INVITE, PAIR_DEVICE, REVOKE_ACCESS;

        /**
         * Actions carried out only after the person says yes to a question that names them. RESUME is one: a paused
         * system restarts all automatic work, so a model's reading alone never restarts it. So are the actions that
         * change who or what may act: approving a waiting step, keeping a grown capability, admitting an agent,
         * inviting a guest and pairing a device.
         */
        private static final Set<ActionType> CONFIRM = Set.of(CANCEL_GOAL, REMOVE_TASK, APPROVE_STEP, REJECT_STEP,
                PAUSE, RESUME, REQUEST_CAPABILITY, KEEP_CAPABILITY, RETIRE_CAPABILITY, ADD_RULE, REMOVE_RULE,
                ADMIT_PROVIDER, INVITE, PAIR_DEVICE, REVOKE_ACCESS);
        private static final Set<ActionType> READ = Set.of(LIST_GOALS, SHOW_GOAL, SHOW_AGENDA, LIST_CAPABILITIES,
                SHOW_DECISIONS, LIST_APPROVALS, SYSTEM_STATUS, VERIFY_GOAL, EXPLAIN_IMPACT, SHOW_ECOSYSTEM, LIST_RULES,
                SHOW_PROFILE, SHOW_INBOX, LIST_ACCESS);
        private static final Set<ActionType> TARGETS_GOAL = Set.of(SHOW_GOAL, SHOW_DECISIONS, VERIFY_GOAL,
                REFINE_GOAL, ADD_TASK, SET_TASK_STATUS, UPDATE_TASK, REMOVE_TASK, RESCHEDULE_GOAL, CANCEL_GOAL, REOPEN_GOAL,
                EXPLAIN_IMPACT, REQUEST_CAPABILITY);

        /** The person must say yes to a question naming this action before it is carried out. */
        public boolean needsConfirmation() {
            return CONFIRM.contains(this);
        }

        /** Shows information and changes nothing. */
        public boolean readOnly() {
            return READ.contains(this);
        }

        /** Actions aimed at one existing goal; their target must be grounded in what the person said. */
        public boolean targetsGoal() {
            return TARGETS_GOAL.contains(this);
        }
    }

    /**
     * One action. Only the fields the type needs are set: {@code goal} is a goal id prefix, {@code task} a task key
     * such as {@code T3}, {@code text} a goal or task description (or a rule, a capability, a profile key),
     * {@code approval} an approval or inbox message id prefix, {@code hours} effort or a profile value.
     * {@code evidence} quotes the person's own words that identify the goal or task; the kernel checks the quote
     * independently before acting on it.
     */
    public record Action(ActionType type, String goal, String task, String text, LocalDate dueDate, TaskStatus status,
                         Double hours, String approval, String reason, String evidence) {
    }

    /** One line of the agenda: an open task that can be started now, across all goals. */
    public record AgendaItem(String goalId, String goalText, String task, String title, LocalDate dueDate,
                             Double effortHours, String risk, Integer priority, boolean overdue) {
    }

    /** What the interpreter returns. {@code reply} speaks to the person; actions are proposals, not facts. */
    public record Interpretation(String reply, List<Action> actions) {
        public Interpretation {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    /** The outcome of one action, decided by the kernel. */
    public record Executed(ActionType type, boolean ok, String message, String goalId, String runId) {
    }

    /**
     * A consequential action waiting for the person's yes or no. A request to grow a capability carries the
     * designed spec, so what is confirmed is exactly what will be created.
     */
    public record PendingConfirmation(String id, String question, List<Action> actions, Ecosystem.CapabilitySpec spec,
                                      Constitution.Rule rule) {
    }

    /**
     * The person's answer to a confirmation question. {@code receipt} is the {@link #receipt} of the question as the
     * person's device displayed it; signed with the rest, it proves the person said yes to exactly that.
     */
    public record Confirm(String confirmationId, boolean accepted, String sessionId, String principal, String receipt) {
        public Confirm(String confirmationId, boolean accepted, String sessionId, String principal) {
            this(confirmationId, accepted, sessionId, principal, null);
        }
    }

    /**
     * What you see is what you sign: a hash of the question and of every action it would carry out. The device
     * computes it from what it showed; the kernel from what it stored; a yes counts only if they match.
     */
    public static String receipt(PendingConfirmation p) {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("id", p.id());
        shown.put("question", p.question());
        shown.put("actions", p.actions());
        return Hashing.canonicalHash(shown);
    }

    /** Everything the person sees after a turn: the reply, what was executed, data to show, a pending question. */
    public record Reply(String reply, List<Executed> executed, PendingConfirmation confirmation,
                        List<KernelApi.GoalSummary> goals, List<KernelApi.GoalDetails> details,
                        List<KernelApi.CapabilityView> capabilities, List<KernelApi.DecisionView> decisions,
                        List<KernelApi.ApprovalView> approvals, KernelApi.SystemStatus status,
                        List<KernelApi.LedgerHash> hashes, List<AgendaItem> agenda,
                        List<Ecosystem.ImpactReport> impacts, List<Ecosystem.Standing> ecosystem,
                        List<Ecosystem.CapabilitySpec> grown, List<Constitution.Rule> rules, Person.Profile profile,
                        List<Inbox.Message> inbox, Constitution.Impact ruleImpact) {
        public Reply {
            executed = executed == null ? List.of() : List.copyOf(executed);
            goals = goals == null ? List.of() : List.copyOf(goals);
            details = details == null ? List.of() : List.copyOf(details);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
            approvals = approvals == null ? List.of() : List.copyOf(approvals);
            hashes = hashes == null ? List.of() : List.copyOf(hashes);
            agenda = agenda == null ? List.of() : List.copyOf(agenda);
            impacts = impacts == null ? List.of() : List.copyOf(impacts);
            ecosystem = ecosystem == null ? List.of() : List.copyOf(ecosystem);
            grown = grown == null ? List.of() : List.copyOf(grown);
            rules = rules == null ? List.of() : List.copyOf(rules);
            inbox = inbox == null ? List.of() : List.copyOf(inbox);
        }

        public static Reply text(String reply) {
            return new Reply(reply, List.of(), null, List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of(),
                    List.of(), List.of(), List.of(), List.of(), List.of(), null, List.of(), null);
        }
    }
}
