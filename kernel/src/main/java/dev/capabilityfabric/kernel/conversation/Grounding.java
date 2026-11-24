package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.constitution.ConstitutionService;
import dev.capabilityfabric.kernel.ecosystem.Genesis;
import dev.capabilityfabric.kernel.execution.Approvals;
import dev.capabilityfabric.kernel.inbox.InboxService;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerRules;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.person.ProfileService;
import dev.capabilityfabric.kernel.policy.PolicyGuard;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.kernel.registry.Embeddings;
import dev.capabilityfabric.protocol.Constitution;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.DecisionRecord;
import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Person;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds what the interpreter may know: the person's open goals (one line each) and the few most recently cancelled
 * and completed ones, full task lists for the goals the person is most likely talking about, pending approvals, the
 * capabilities (live ones, and grown ones that are retired or wait to be kept, with their state), rules, the newest
 * unread messages, the profile and the recent conversation. Focus goals are those discussed in the last turns plus
 * the goals closest in meaning to what was just said (embeddings) that also share a word with it, chosen among all
 * open goals and the closed ones shown, so prompts stay small however many goals exist. The snapshot is trimmed to
 * fit the interpreter's context window, and its {@code omitted} object says how many open goals, unread messages and
 * capabilities it leaves out. Texts that agents wrote (capability names, annotations, approval reasons) go in on one
 * line each.
 */
@Component
public class Grounding {

    static final int MAX_GOALS = 15;
    /** The state a capability from an older version that waits to be kept is listed with. */
    static final String QUARANTINED = "quarantined, waiting to be kept";
    static final int MAX_FOCUS = 3;
    static final int MAX_CANCELLED = 5;
    static final int MAX_COMPLETED = 5;
    static final int MAX_TASKS = 15;
    static final int MAX_MESSAGES_PER_GOAL = 3;
    /** The newest unread messages the snapshot lists; {@code omitted.messages} counts the rest. */
    static final int MAX_MESSAGES = 10;
    /** A message text longer than this is cut ("..."), so a long admission text does not crowd out goals. */
    static final int MAX_MESSAGE_TEXT = 160;
    /** Trimming open goal lines stops at this many until messages, rules and capabilities are trimmed too. */
    static final int MIN_GOAL_LINES = 12;
    /**
     * A goal is a focus goal from this cosine similarity to the sentence up (with an embedding model), and only when
     * it also shares a content word with the sentence. Measured with qwen3-embedding:0.6b, unrelated goals score 0.5
     * to 0.7 against a sentence ("what should I do today?" scores 0.64 against one goal and 0.60 against another,
     * "I only have 2 hours a day" 0.62), while sentences about a goal score 0.62 to 0.66 and name it by a word
     * ("picnic", "portfolio", "bread"); so the meaning alone never makes a focus goal.
     */
    static final double FOCUS_COSINE = 0.6;
    /** With several focus goals, each lists at most this many tasks (and says it is {@code truncated}). */
    static final int FOCUS_TASKS_WHEN_SEVERAL = 5;
    /** Without an embedding model, a goal is a focus goal from this word overlap with the sentence up. */
    static final double FOCUS_LEXICAL = 0.1;

    /** The interpreter's context window when {@code fabric.model.num-ctx} is not set, in tokens. */
    static final int CONTEXT_TOKENS = 8192;
    /** Room kept free for the interpreter's answer, in tokens: measured answers stay under 350. */
    static final int ANSWER_TOKENS = 600;
    /**
     * Characters per token of the interpreter's model on the snapshot: measured 2.62 to 2.78 on real snapshots (JSON
     * with short ids, dates and task titles tokenises densely), so the lowest reading rounded down is used.
     */
    static final double CHARS_PER_TOKEN = 2.6;
    /** Characters per token of whole prompts, instructions and schema included: measured about 3.1. */
    static final double PROMPT_CHARS_PER_TOKEN = 3.1;
    /**
     * Room taken by the interpreter's own instructions and the fixed lines of its prompt (today, the calendar, the
     * headings), in characters, until a call has measured them (see {@link #measured}): the bound every provider of
     * the interpreter keeps to ({@link Conversation#INTERPRET_INSTRUCTION_CHARS}).
     */
    static final int INSTRUCTION_CHARS = Conversation.INTERPRET_INSTRUCTION_CHARS;
    /**
     * Room for the answer's JSON schema with the sentence that introduces it, which a model server without constrained
     * decoding puts in the prompt ({@link Conversation#INTERPRET_SCHEMA_CHARS}).
     */
    static final int SCHEMA_CHARS = Conversation.INTERPRET_SCHEMA_CHARS;
    /** The words a model server puts before the schema when it adds the schema to the instructions. */
    static final int SCHEMA_HEADER_CHARS = 80;
    /** Room a repair attempt adds: the rejected answer (cut at 3000 characters) and the reasons it was rejected. */
    static final int REPAIR_CHARS = 3500;
    /** Room for the {@code omitted} object, added after trimming. */
    static final int OMITTED_CHARS = 60;
    /** The smallest snapshot budget, in characters, however full the context window is. */
    static final int MIN_BUDGET = 2000;
    /** The most characters a token holds on any of these prompts, so a measurement never claims fewer tokens. */
    static final double MAX_CHARS_PER_TOKEN = 4.0;
    /** How large the snapshot may be with the default context window, in characters of JSON (about 7,400). */
    static final int SNAPSHOT_BUDGET = budget(CONTEXT_TOKENS);
    /** Trimming a focus goal's task list stops at this many tasks; then whole focus goals go. */
    static final int MIN_FOCUS_TASKS = 5;
    /** The most recent conversation turns, kept until everything but the goals in focus is trimmed. */
    static final int RECENT_TURNS = 2;

    /**
     * How large the snapshot may be for a context window of {@code contextTokens} before any call was measured, in
     * characters of JSON (an upper bound for the prompt text made from it): the window less the answer's room and the
     * tokens of the instructions and the schema, in characters of the snapshot, less a repair attempt.
     */
    static int budget(int contextTokens) {
        double fixedTokens = (INSTRUCTION_CHARS + SCHEMA_CHARS) / PROMPT_CHARS_PER_TOKEN;
        return (int) ((contextTokens - ANSWER_TOKENS - fixedTokens) * CHARS_PER_TOKEN) - REPAIR_CHARS;
    }

    /**
     * What one interpreter call measured: the characters of its fixed part (the system prompt, and the answer's schema
     * with its header, counted as if the model server put it in the prompt), the characters of the person's part
     * (the rendered snapshot, the calendar and the sentence), the characters of the snapshot's JSON, and the prompt's
     * tokens as the model counted them (null when none were reported).
     */
    record Measure(int fixedChars, int personChars, int snapshotChars, Integer promptTokens) {

        /**
         * The tokens of the fixed part: the prompt's tokens less the person's part counted at the snapshot's rate
         * ({@link Grounding#CHARS_PER_TOKEN}, the densest measured, so the person's part is never counted short), or
         * the fixed characters at the whole prompt's rate when no tokens were reported; never fewer than the fixed
         * characters at {@link Grounding#MAX_CHARS_PER_TOKEN}.
         */
        double fixedTokens() {
            double floor = fixedChars / MAX_CHARS_PER_TOKEN;
            if (promptTokens == null || promptTokens <= 0) {
                return Math.max(floor, fixedChars / PROMPT_CHARS_PER_TOKEN);
            }
            return Math.max(floor, promptTokens - personChars / CHARS_PER_TOKEN);
        }
    }

    /**
     * How large the snapshot may be for a context window of {@code contextTokens}, from what the last call measured
     * (see {@link #budget(int)} when nothing was): the window less the answer's room and the measured tokens of the
     * instructions and schema (see {@link Measure#fixedTokens}), in characters at the snapshot's rate, less the
     * person's part beyond the snapshot's own JSON (the calendar, headings and sentence; the rendered snapshot is no
     * longer than its JSON), less a repair attempt. Never below {@link #MIN_BUDGET}.
     */
    static int budget(int contextTokens, Measure measure) {
        if (measure == null || measure.fixedChars() <= 0) {
            return Math.max(MIN_BUDGET, budget(contextTokens));
        }
        double room = (contextTokens - ANSWER_TOKENS - measure.fixedTokens()) * CHARS_PER_TOKEN
                - Math.max(0, measure.personChars() - measure.snapshotChars()) - REPAIR_CHARS;
        return Math.max(MIN_BUDGET, (int) room);
    }

    private final LedgerService ledger;
    private final Approvals approvals;
    private final PolicyGuard policy;
    private final Embeddings embeddings;
    private final KernelProperties properties;
    private final CapabilityRegistry registry;
    private final Genesis genesis;
    private final ConstitutionService constitution;
    private final InboxService inbox;
    private final ProfileService profile;
    private final TargetCheck targets;
    /** The interpreter's context window, in tokens. */
    private final int contextTokens;
    /** What the last interpreter call measured; null before the first. */
    private volatile Measure measure;

    /** A grounding for an interpreter with the default context window. */
    public Grounding(LedgerService ledger, Approvals approvals, PolicyGuard policy, Embeddings embeddings,
                     KernelProperties properties, CapabilityRegistry registry, Genesis genesis,
                     ConstitutionService constitution, InboxService inbox, ProfileService profile) {
        this(ledger, approvals, policy, embeddings, properties, registry, genesis, constitution, inbox, profile,
                CONTEXT_TOKENS);
    }

    /**
     * {@code contextTokens} is the interpreter's context window, the same {@code fabric.model.num-ctx} the
     * assistant's model is called with.
     */
    @Autowired
    public Grounding(LedgerService ledger, Approvals approvals, PolicyGuard policy, Embeddings embeddings,
                     KernelProperties properties, CapabilityRegistry registry, Genesis genesis,
                     ConstitutionService constitution, InboxService inbox, ProfileService profile,
                     @Value("${fabric.model.num-ctx:" + CONTEXT_TOKENS + "}") int contextTokens) {
        this.contextTokens = contextTokens;
        this.constitution = constitution;
        this.inbox = inbox;
        this.profile = profile;
        this.registry = registry;
        this.genesis = genesis;
        this.ledger = ledger;
        this.approvals = approvals;
        this.policy = policy;
        this.embeddings = embeddings;
        this.properties = properties;
        this.targets = TargetCheck.using(embeddings);
    }

    /** How large the snapshot may be now, from the context window and what the last call measured. */
    int snapshotBudget() {
        return budget(contextTokens, measure);
    }

    /**
     * Learns the size of the interpreter's prompt from the model calls of one interpretation of {@code snapshot}: the
     * first attempt's system prompt, schema and person's part as the decision records them, and its prompt tokens
     * as the model counted them. Later snapshots are cut to fit the context window by these measures instead of
     * fixed estimates. Calls without such a record change nothing.
     */
    public void measured(List<DecisionRecord> decisions, Snapshot snapshot) {
        if (decisions == null || snapshot == null) {
            return;
        }
        for (DecisionRecord d : decisions) {
            JsonNode request = d == null ? null : d.request();
            if (d == null || d.attempt() != 1 || request == null || !request.path("system").isString()) {
                continue;
            }
            JsonNode schema = request.path("schema");
            int schemaChars = schema.isMissingNode() || schema.isNull() ? 0
                    : Json.write(schema).length() + SCHEMA_HEADER_CHARS;
            measure = new Measure(request.path("system").asString("").length() + schemaChars,
                    request.path("user").asString("").length(), Json.write(snapshot.params()).length(),
                    d.promptTokens());
            return;
        }
    }

    /** The snapshot sent to the interpreter, and how its short ids map back to real ones. */
    public record Snapshot(ObjectNode params, Map<String, String> goalIds, Map<String, String> approvalIds,
                           Map<String, List<String>> documents) {

        /**
         * The full id of the goal a reference names: a short id, an id prefix of at least 4 characters, or a text
         * holding an 8-character id; empty when it names none or more than one.
         */
        public Optional<String> goal(String ref) {
            return resolve(goalIds, ref);
        }

        /** The full id of the pending approval a reference names, read like {@link #goal}. */
        public Optional<String> approval(String ref) {
            return resolve(approvalIds, ref);
        }

        /** A short id as the snapshot gives them: the first 8 hexadecimal characters of the full id. */
        private static final Pattern SHORT_ID = Pattern.compile("\\b([0-9a-f]{8})\\b");

        private static Optional<String> resolve(Map<String, String> ids, String ref) {
            if (ref == null || ref.isBlank()) {
                return Optional.empty();
            }
            Matcher hex = SHORT_ID.matcher(ref.toLowerCase(Locale.ROOT));
            String r = hex.find() ? hex.group(1) : ref.strip().toLowerCase(Locale.ROOT);
            if (ids.containsKey(r)) {
                return Optional.of(ids.get(r));
            }
            List<String> matches = ids.values().stream().filter(id -> id.startsWith(r) && r.length() >= 4).toList();
            return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
        }
    }

    /**
     * {@code visible} limits what the interpreter may see to the goals the caller may see (a guest sees only the
     * goals shared with them, and no inbox, rules, profile or capability list).
     */
    public Snapshot build(String utterance, List<Session.Turn> history, Set<String> sessionFocus,
                          Predicate<String> visible, boolean owner) {
        LocalDate today = properties.today();
        List<GoalState> open = ledger.openGoals().stream().filter(g -> visible.test(g.goalId())).toList();
        // Every open goal can be the target, shown or not: references are grounded against all of them.
        Map<String, List<String>> documents = new LinkedHashMap<>();
        open.forEach(s -> documents.put(s.goalId(), texts(s)));
        Set<String> pinned = new LinkedHashSet<>();
        sessionFocus.stream().filter(documents::containsKey).forEach(pinned::add);
        targets.clearMatch(utterance, documents).ifPresent(pinned::add);
        // The goal the words point at most clearly stays in view even when the strict test is not met: pinning it
        // only shows it to the interpreter, whose choice is still checked.
        targets.likely(utterance, documents).ifPresent(pinned::add);
        // The few most recently cancelled and completed goals are shown too, so "bring back the trip", "reopen the
        // move" and "T3 isn't done after all" can find them.
        List<GoalState> closed = new ArrayList<>(ledger.recentlyCancelled(MAX_CANCELLED));
        closed.addAll(ledger.recentlyCompleted(MAX_COMPLETED));
        closed = closed.stream().filter(s -> visible.test(s.goalId())).toList();
        // Focus goals are chosen among all open goals (not only those that fit in the window) and the closed ones
        // shown; open focus goals are pinned into the window.
        Map<String, GoalState> candidates = new LinkedHashMap<>();
        open.forEach(s -> candidates.put(s.goalId(), s));
        closed.forEach(s -> candidates.putIfAbsent(s.goalId(), s));
        List<String> focusIds = focus(utterance, candidates, sessionFocus);
        pinned.addAll(focusIds);
        Map<String, GoalState> states = new LinkedHashMap<>();
        window(open, pinned, MAX_GOALS).forEach(s -> states.put(s.goalId(), s));
        for (GoalState s : closed) {
            states.put(s.goalId(), s);
            documents.put(s.goalId(), texts(s));
        }
        Set<String> keep = new LinkedHashSet<>();
        pinned.forEach(id -> keep.add(id.substring(0, 8)));

        ObjectNode params = Json.object();
        params.put("utterance", utterance);
        params.put("today", today.toString());
        params.put("weekday", today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
        params.put("paused", policy.paused());

        Map<String, String> goalIds = new LinkedHashMap<>();
        documents.keySet().forEach(id -> goalIds.put(id.substring(0, 8), id));
        ArrayNode goalList = params.putArray("goals");
        for (GoalState s : states.values()) {
            String shortId = s.goalId().substring(0, 8);
            ObjectNode g = goalList.addObject();
            g.put("id", shortId);
            g.put("text", s.text());
            g.put("status", s.status() == null ? "PLANNING" : s.status().name());
            g.put("dueDate", s.dueDate() == null ? null : s.dueDate().toString());
            g.put("open", (int) s.tasks().stream().filter(t -> t.status().isOpen()).count());
            g.put("done", s.doneCount());
            ArrayNode next = g.putArray("next");
            openByRank(s).stream().limit(3).forEach(t -> next.add(t.key() + " " + t.title()
                    + (t.dueDate() == null ? "" : " (due " + t.dueDate() + ")")));
        }

        ArrayNode focus = params.putArray("focus");
        List<String> shownFocus = focusIds.stream().filter(states::containsKey).toList();
        for (String goalId : shownFocus) {
            focusGoal(focus.addObject(), states.get(goalId), shownFocus.size() > 1 ? FOCUS_TASKS_WHEN_SEVERAL
                    : MAX_TASKS);
        }

        Map<String, String> approvalIds = new LinkedHashMap<>();
        ArrayNode pending = params.putArray("approvals");
        for (KernelApi.ApprovalView a : approvals.pending()) {
            String shortId = a.id().substring(0, 8);
            approvalIds.put(shortId, a.id());
            ObjectNode n = pending.addObject();
            n.put("id", shortId);
            n.put("goal", a.goalId().substring(0, 8));
            n.put("capability", oneLine(a.capability()));
            n.put("reason", oneLine(a.reason()));
        }

        ArrayNode capabilities = params.putArray("capabilities");
        Map<String, String> grown = new LinkedHashMap<>();
        genesis.all().forEach(spec -> grown.put(spec.id(), spec.status().name().toLowerCase(Locale.ROOT)));
        Set<String> listed = new HashSet<>();
        for (KernelApi.CapabilityView v : registry.views()) {
            if (listed.add(v.id())) {
                ObjectNode c = capabilities.addObject();
                c.put("id", oneLine(v.id()));
                c.put("name", oneLine(v.name()));
                c.put("grown", grown.containsKey(v.id()) ? grown.get(v.id()) : null);
            }
        }
        // Grown capabilities that no agent serves now (retired ones) and quarantined ones that wait to be kept are
        // listed with their state, so "keep shopping.list" can name them.
        for (Ecosystem.CapabilitySpec spec : genesis.all()) {
            if (listed.add(spec.id())) {
                ObjectNode c = capabilities.addObject();
                c.put("id", oneLine(spec.id()));
                c.put("name", oneLine(spec.name()));
                c.put("grown", grown.get(spec.id()));
            }
        }
        for (Ecosystem.CapabilitySpec spec : genesis.quarantined()) {
            if (listed.add(spec.id())) {
                ObjectNode c = capabilities.addObject();
                c.put("id", oneLine(spec.id()));
                c.put("name", oneLine(spec.name()));
                c.put("grown", QUARANTINED);
            }
        }

        if (!owner) {
            ArrayNode turns = params.putArray("history");
            for (Session.Turn turn : history) {
                ObjectNode n = turns.addObject();
                n.put("person", turn.person());
                n.put("assistant", turn.assistant());
            }
            params.putArray("approvals");
            params.putArray("capabilities");
            fit(params, snapshotBudget() - OMITTED_CHARS, keep);
            omitted(params, open.size(), -1);
            return new Snapshot(params, goalIds, Map.of(), documents);
        }
        ArrayNode rules = params.putArray("rules");
        for (Constitution.Rule r : constitution.active()) {
            ObjectNode n = rules.addObject();
            n.put("id", r.id());
            n.put("text", r.text());
        }

        ArrayNode messages = params.putArray("inbox");
        List<Inbox.Message> unread = inbox.unread();
        for (Inbox.Message m : newestPerGoal(unread, MAX_MESSAGES_PER_GOAL).stream().limit(MAX_MESSAGES).toList()) {
            ObjectNode n = messages.addObject();
            n.put("id", m.id().substring(0, 8));
            n.put("kind", m.kind().name().toLowerCase(Locale.ROOT).replace('_', ' '));
            n.put("goal", m.goalId() == null ? null : m.goalId().substring(0, 8));
            n.put("text", cut(oneLine(m.text()), MAX_MESSAGE_TEXT));
            n.put("suggestion", cut(oneLine(m.suggestionText()), MAX_MESSAGE_TEXT));
        }

        Person.Profile person = profile.profile();
        ObjectNode p = params.putObject("profile");
        p.put("dailyHours", person.dailyHours());
        p.put("workingDays", String.join(",", person.workingDays()));
        p.put("estimateBias", person.overallBias());

        ArrayNode turns = params.putArray("history");
        for (Session.Turn turn : history) {
            ObjectNode n = turns.addObject();
            n.put("person", turn.person());
            n.put("assistant", turn.assistant());
        }
        int capabilityCount = params.path("capabilities").size();
        fit(params, snapshotBudget() - OMITTED_CHARS, keep);
        omitted(params, open.size(), unread.size());
        int cut = capabilityCount - params.path("capabilities").size();
        if (cut > 0) {
            ((ObjectNode) params.get("omitted")).put("capabilities", cut);
        }
        return new Snapshot(params, goalIds, approvalIds, documents);
    }

    /**
     * Adds {@code omitted}: how many of the {@code openGoals} open goals and the {@code unread} unread messages the
     * snapshot does not show ({@code unread} below 0 for a caller who sees no inbox), so the interpreter knows its
     * lists are not complete.
     */
    static void omitted(ObjectNode params, int openGoals, int unread) {
        int shownOpen = 0;
        for (JsonNode g : params.path("goals")) {
            if (!isClosed(g)) {
                shownOpen++;
            }
        }
        ObjectNode omitted = params.putObject("omitted");
        omitted.put("goals", Math.max(0, openGoals - shownOpen));
        if (unread >= 0) {
            omitted.put("messages", Math.max(0, unread - params.path("inbox").size()));
        }
    }

    /**
     * A focus goal with its tasks: open ones by rank, then closed ones, at most {@link #MAX_TASKS}. {@code taskCount}
     * and {@code truncated} say whether the list is complete, so a task key that is not in it is known to be unknown.
     */
    static ObjectNode focusGoal(ObjectNode g, GoalState s) {
        return focusGoal(g, s, MAX_TASKS);
    }

    /** {@link #focusGoal(ObjectNode, GoalState)} listing at most {@code maxTasks} tasks. */
    static ObjectNode focusGoal(ObjectNode g, GoalState s, int maxTasks) {
        g.put("id", s.goalId().substring(0, 8));
        g.put("text", s.text());
        List<TaskView> ordered = new ArrayList<>(openByRank(s));
        s.tasks().stream().filter(t -> !t.status().isOpen()).forEach(ordered::add);
        g.put("taskCount", s.tasks().size());
        g.put("truncated", ordered.size() > maxTasks);
        ArrayNode tasks = g.putArray("tasks");
        for (TaskView t : ordered.subList(0, Math.min(maxTasks, ordered.size()))) {
            ObjectNode n = tasks.addObject();
            n.put("key", t.key());
            n.put("title", t.title());
            n.put("status", t.status().name());
            n.put("dueDate", t.dueDate() == null ? null : t.dueDate().toString());
            n.put("effortHours", t.effortHours());
            n.put("rank", t.rank());
            n.put("risk", oneLine(t.annotation("risk.deadline").map(r -> r.path("level").asString(null))
                    .orElse(null)));
            n.put("blockedBy", oneLine(t.annotation("deps.blockedBy").filter(JsonNode::isArray)
                    .filter(b -> !b.isEmpty()).map(JsonNode::toString).orElse(null)));
            n.put("why", oneLine(t.annotation("priority.reason").map(JsonNode::asString).orElse(null)));
        }
        return g;
    }

    /**
     * A text an agent wrote, on one line: control characters, line and paragraph separators become spaces, so it
     * cannot start a line of its own in the interpreter's prompt. Null stays null.
     */
    static String oneLine(String text) {
        String printable = LedgerRules.printable(text);
        return printable == null ? null : printable.replace('\u2028', ' ').replace('\u2029', ' ');
    }

    /** A text cut to at most {@code max} characters, ending in "..." when cut. Null stays null. */
    static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 3).stripTrailing() + "...";
    }

    /** What a goal is known by when grounding a reference: its text, then each task title with the goal text. */
    private static List<String> texts(GoalState s) {
        List<String> texts = new ArrayList<>();
        texts.add(s.text());
        s.tasks().forEach(t -> texts.add(t.title() + " (" + s.text() + ")"));
        return texts;
    }

    /**
     * The goals shown in the snapshot: the {@code max} most recently touched open goals, with the pinned ones (just
     * discussed, or the clear match for the sentence) swapped in for the least recent when they would not be shown.
     */
    static List<GoalState> window(List<GoalState> open, Set<String> pinned, int max) {
        List<GoalState> shown = new ArrayList<>(open.subList(0, Math.min(max, open.size())));
        for (String id : pinned) {
            if (shown.stream().anyMatch(s -> s.goalId().equals(id))) {
                continue;
            }
            Optional<GoalState> wanted = open.stream().filter(s -> s.goalId().equals(id)).findFirst();
            if (wanted.isEmpty()) {
                continue;
            }
            for (int i = shown.size() - 1; i >= 0; i--) {
                if (!pinned.contains(shown.get(i).goalId())) {
                    shown.set(i, wanted.get());
                    break;
                }
            }
        }
        return shown;
    }

    /** Unread messages newest first, at most {@code perGoal} about each goal (goal-less messages count as one goal). */
    static List<Inbox.Message> newestPerGoal(List<Inbox.Message> unread, int perGoal) {
        Map<String, Integer> counts = new HashMap<>();
        List<Inbox.Message> kept = new ArrayList<>();
        List<Inbox.Message> newest = new ArrayList<>(unread);
        newest.sort(Comparator.comparing(Inbox.Message::createdAt).reversed());
        for (Inbox.Message m : newest) {
            if (counts.merge(String.valueOf(m.goalId()), 1, Integer::sum) <= perGoal) {
                kept.add(m);
            }
        }
        return kept;
    }

    /** {@link #fit(ObjectNode, int, Set)} with no goal that must stay. */
    static void fit(ObjectNode params, int budget) {
        fit(params, budget, Set.of());
    }

    /**
     * Trims the snapshot until its JSON fits {@code budget} characters, least needed first: conversation turns older
     * than the last {@link #RECENT_TURNS}, the "next" tasks of the goals (first of those whose full task list is shown
     * in focus), closed goals, the texts of admission messages, then of every inbox message (oldest first; their ids
     * stay, so an answer can still name them) and their suggestions, the least recently touched open goals down to
     * {@link #MIN_GOAL_LINES}, the texts of rules (their ids stay), capabilities (live ones before the grown ones no
     * agent serves now, see {@link #trimCapabilities}), the remaining open goal lines, the tail of long focus task
     * lists (closed tasks first, down to {@link #MIN_FOCUS_TASKS}), the last turns, and last the least relevant focus
     * goals together with their goal lines. A goal line whose id is in focus or in {@code keep} (short ids of the goals
     * just discussed or clearly named) is never dropped while its focus entry stays, and neither is the sentence.
     */
    static void fit(ObjectNode params, int budget, Set<String> keep) {
        Set<String> focused = new HashSet<>();
        params.path("focus").forEach(f -> focused.add(f.path("id").asString()));
        Predicate<JsonNode> held = g -> keep.contains(g.path("id").asString())
                || focused.contains(g.path("id").asString());
        trimOldTurns(params, budget, RECENT_TURNS);
        dropNext(params, budget, focused::contains);
        dropNext(params, budget, id -> true);
        trimGoals(params, budget, g -> isClosed(g) && !held.test(g));
        dropField(params, "inbox", "text", budget, m -> m.path("kind").asString("").equals("admission"));
        dropField(params, "inbox", "text", budget);
        dropField(params, "inbox", "suggestion", budget);
        trimGoals(params, budget, g -> !isClosed(g) && !held.test(g), MIN_GOAL_LINES);
        dropField(params, "rules", "text", budget);
        trimCapabilities(params, budget);
        trimGoals(params, budget, g -> !isClosed(g) && !held.test(g));
        trimFocusTasks(params, budget);
        trim(params, "history", budget, true);
        trimFocus(params, budget, keep);
    }

    /** Removes the oldest conversation turns while over budget, keeping the last {@code recent}. */
    private static void trimOldTurns(ObjectNode params, int budget, int recent) {
        if (params.get("history") instanceof ArrayNode history) {
            while (history.size() > recent && over(params, budget)) {
                history.remove(0);
            }
        }
    }

    /** Removes {@code field} from the entries of an array, from its end (the oldest entries), while over budget. */
    private static void dropField(ObjectNode params, String array, String field, int budget) {
        dropField(params, array, field, budget, e -> true);
    }

    /** {@link #dropField(ObjectNode, String, String, int)} for the entries {@code which} accepts. */
    private static void dropField(ObjectNode params, String array, String field, int budget,
                                  Predicate<JsonNode> which) {
        if (params.get(array) instanceof ArrayNode entries) {
            for (int i = entries.size() - 1; i >= 0 && over(params, budget); i--) {
                if (entries.get(i) instanceof ObjectNode entry && which.test(entry)) {
                    entry.remove(field);
                }
            }
        }
    }

    /**
     * Removes focus goals from the end of the list (the least relevant) that are not in {@code keep}, each together
     * with its goal line, while over budget.
     */
    private static void trimFocus(ObjectNode params, int budget, Set<String> keep) {
        if (!(params.get("focus") instanceof ArrayNode focus)) {
            return;
        }
        for (int i = focus.size() - 1; i >= 0 && over(params, budget); i--) {
            String id = focus.get(i).path("id").asString();
            if (keep.contains(id)) {
                continue;
            }
            focus.remove(i);
            if (params.get("goals") instanceof ArrayNode goals) {
                for (int k = goals.size() - 1; k >= 0; k--) {
                    if (goals.get(k).path("id").asString().equals(id)) {
                        goals.remove(k);
                    }
                }
            }
        }
    }

    private static boolean over(ObjectNode params, int budget) {
        return Json.write(params).length() > budget;
    }

    private static boolean isClosed(JsonNode goal) {
        String status = goal.path("status").asString("");
        return status.equals("CANCELLED") || status.equals("COMPLETED");
    }

    /**
     * Removes capabilities from the end of the list while over budget: live ones first, and only then the grown ones
     * that are retired or wait to be kept, which a sentence can name only through this list ("keep shopping.list").
     */
    private static void trimCapabilities(ObjectNode params, int budget) {
        if (!(params.get("capabilities") instanceof ArrayNode capabilities)) {
            return;
        }
        for (boolean waiting : new boolean[]{false, true}) {
            for (int i = capabilities.size() - 1; i >= 0 && over(params, budget); i--) {
                if (notServed(capabilities.get(i)) == waiting) {
                    capabilities.remove(i);
                }
            }
        }
    }

    /** True for a capability entry that no agent serves now: a retired grown one, or one waiting to be kept. */
    private static boolean notServed(JsonNode capability) {
        String state = capability.path("grown").asString("");
        return state.equals("retired") || state.equals(QUARANTINED);
    }

    private static void trim(ObjectNode params, String field, int budget, boolean fromStart) {
        JsonNode node = params.get(field);
        if (!(node instanceof ArrayNode array)) {
            return;
        }
        while (!array.isEmpty() && over(params, budget)) {
            array.remove(fromStart ? 0 : array.size() - 1);
        }
    }

    /** Removes goals that {@code removable} allows, from the end of the list (the least recent), while over budget. */
    private static void trimGoals(ObjectNode params, int budget, Predicate<JsonNode> removable) {
        trimGoals(params, budget, removable, 0);
    }

    /**
     * {@link #trimGoals(ObjectNode, int, Predicate)} that stops while at most {@code min} open goal lines are left.
     */
    private static void trimGoals(ObjectNode params, int budget, Predicate<JsonNode> removable, int min) {
        if (!(params.get("goals") instanceof ArrayNode goals)) {
            return;
        }
        for (int i = goals.size() - 1; i >= 0 && over(params, budget) && openLines(goals) > min; i--) {
            if (removable.test(goals.get(i))) {
                goals.remove(i);
            }
        }
    }

    private static int openLines(ArrayNode goals) {
        int open = 0;
        for (JsonNode g : goals) {
            if (!isClosed(g)) {
                open++;
            }
        }
        return open;
    }

    /** Removes the "next" tasks of the goals whose short id {@code which} accepts, from the end, while over budget. */
    private static void dropNext(ObjectNode params, int budget, Predicate<String> which) {
        if (!(params.get("goals") instanceof ArrayNode goals)) {
            return;
        }
        for (int i = goals.size() - 1; i >= 0 && over(params, budget); i--) {
            if (goals.get(i) instanceof ObjectNode g && which.test(g.path("id").asString()) && g.has("next")) {
                g.remove("next");
            }
        }
    }

    /**
     * Shortens the longest focus task list by its last task (closed tasks are listed last) until the snapshot fits
     * or every list is down to {@link #MIN_FOCUS_TASKS}; a shortened list says it is {@code truncated}.
     */
    private static void trimFocusTasks(ObjectNode params, int budget) {
        if (!(params.get("focus") instanceof ArrayNode focus)) {
            return;
        }
        while (over(params, budget)) {
            ObjectNode longest = null;
            for (JsonNode f : focus) {
                if (f instanceof ObjectNode g && g.path("tasks").size() > MIN_FOCUS_TASKS
                        && (longest == null || g.path("tasks").size() > longest.path("tasks").size())) {
                    longest = g;
                }
            }
            if (longest == null) {
                return;
            }
            ArrayNode tasks = (ArrayNode) longest.get("tasks");
            tasks.remove(tasks.size() - 1);
            longest.put("truncated", true);
        }
    }

    private static List<TaskView> openByRank(GoalState s) {
        return s.tasks().stream().filter(t -> t.status().isOpen())
                .sorted(Comparator.comparing((TaskView t) -> t.rank() == null ? Integer.MAX_VALUE : t.rank())
                        .thenComparing(TaskView::key))
                .toList();
    }

    /**
     * Goals discussed recently first, then goals with a task whose title shares content words with the utterance (most
     * shared words first), then the goals of {@code states} (every open goal and the closed ones shown) closest in
     * meaning to the utterance that also share a content word with it (see {@link #FOCUS_COSINE}). Without an
     * embedding model the word overlap decides alone.
     */
    private List<String> focus(String utterance, Map<String, GoalState> states, Set<String> sessionFocus) {
        Set<String> focus = new LinkedHashSet<>();
        sessionFocus.stream().filter(states::containsKey).forEach(focus::add);
        // A goal whose task the words name ("I've started on the photo appointment" for "Renew my passport") is in
        // focus too, so the model sees that task and its key: the goals sharing most words with a task title first.
        Set<String> words = TargetCheck.stems(utterance);
        record Named(String id, long words) {
        }
        List<Named> named = new ArrayList<>();
        for (GoalState s : states.values()) {
            long most = s.tasks().stream().mapToLong(t -> TargetCheck.stems(t.title()).stream()
                    .filter(words::contains).count()).max().orElse(0);
            if (most > 0) {
                named.add(new Named(s.goalId(), most));
            }
        }
        named.sort(Comparator.comparingLong(Named::words).reversed());
        for (Named n : named) {
            if (focus.size() < MAX_FOCUS) {
                focus.add(n.id());
            }
        }
        Optional<float[]> said = embeddings.embed(utterance);
        record Scored(String id, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (GoalState s : states.values()) {
            double score = said.flatMap(v -> embeddings.embed(s.text()).map(g -> Embeddings.cosine(v, g)))
                    .orElseGet(() -> Embeddings.lexicalSimilarity(utterance, s.text()));
            scored.add(new Scored(s.goalId(), score));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        double threshold = said.isPresent() ? FOCUS_COSINE : FOCUS_LEXICAL;
        for (Scored s : scored) {
            if (focus.size() >= MAX_FOCUS) {
                break;
            }
            if (s.score() >= threshold
                    && (said.isEmpty() || TargetCheck.sharesWord(utterance, texts(states.get(s.id()))))) {
                focus.add(s.id());
            }
        }
        return focus.stream().limit(MAX_FOCUS).toList();
    }
}
