package dev.capabilityfabric.kernel.constitution;

import dev.capabilityfabric.algorithms.WorkdayCalendar;
import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.conversation.TargetCheck;
import dev.capabilityfabric.kernel.decisions.DecisionLog;
import dev.capabilityfabric.kernel.ecosystem.Provenance;
import dev.capabilityfabric.kernel.execution.CapabilityInvoker;
import dev.capabilityfabric.kernel.inbox.InboxService;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerEvent;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.person.ProfileService;
import dev.capabilityfabric.kernel.policy.PolicyGuard;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.kernel.registry.Embeddings;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Constitution;
import dev.capabilityfabric.protocol.Constitution.Rule;
import dev.capabilityfabric.protocol.Conversation.Action;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Hashing;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The person's constitution. A model compiles plain-language rules into {@link Rule} data; this class validates the
 * result, replays recent history to show what the rule would have changed, stores it once the person ratifies it,
 * and enforces it deterministically on the person's actions, on agents' plan steps and on reactive proposals.
 * Limit rules are checked after every run and breaches become inbox suggestions.
 */
@Component
public class ConstitutionService {

    private static final Logger log = LoggerFactory.getLogger(ConstitutionService.class);
    public static final String COMPILER = "policy.compile";

    /** How the rules treat a change: let it through, hold it for the person's yes, or refuse it. */
    public enum Verdict { ALLOW, ASK_FIRST, FORBID }

    /** The verdict of the strongest rule that applies to a change, with that rule; {@link #ALLOW} when none does. */
    public record Ruling(Verdict verdict, Rule rule) {
        public static final Ruling ALLOW = new Ruling(Verdict.ALLOW, null);

        /** The rule in the person's words for replies, such as: your rule "never on Sundays"; empty for no rule. */
        public String explanation() {
            return rule == null ? "" : "your rule \"" + rule.text() + "\"";
        }
    }

    /**
     * A sentence compiled into a rule draft not yet in force, with its description in words and the impact a replay
     * of recent history shows; or, with {@code rule} null, the {@code problem} to tell the person.
     */
    public record Compiled(Rule rule, String description, Constitution.Impact impact, String problem) {
    }

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final CapabilityRegistry registry;
    private final CapabilityInvoker invoker;
    private final InboxService inbox;
    private final KernelProperties properties;
    private final PolicyGuard policy;
    private final RuleMatcher matcher;
    private final ProfileService profile;
    private final DecisionLog decisions;

    public ConstitutionService(JdbcClient jdbc, LedgerService ledger, CapabilityRegistry registry,
                               CapabilityInvoker invoker, Embeddings embeddings, InboxService inbox,
                               KernelProperties properties, PolicyGuard policy, ProfileService profile,
                               DecisionLog decisions) {
        this.profile = profile;
        this.decisions = decisions;
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.registry = registry;
        this.invoker = invoker;
        this.inbox = inbox;
        this.properties = properties;
        this.policy = policy;
        this.matcher = new RuleMatcher(embeddings::embed,
                () -> ledger.openGoals().stream().map(GoalState::text).toList());
    }

    /** Shown when a sentence could not be compiled into a rule; the compiler's own detail goes to the log. */
    static final String RULE_HINT = "turn that into a rule. Say who it is for (you or the agents), what "
            + "it covers (a kind of change, a capability or a topic such as \"my interview goal\") and whether to "
            + "forbid it or ask you first, for example \"ask me before agents change my interview goal\".";

    /** Shown for a rule about which days tasks are due on; it points to the working days instead. */
    static final String DUE_DAY_HINT = "make that a rule: a rule cannot choose the days tasks are due on. Tell me "
            + "your working days instead, for example \"I don't work on weekends\": suggestions then move tasks only to "
            + "those days, and deadline warnings count only them.";

    /** How the compiler capability begins its refusal of a rule about due days; that refusal is shown as it is. */
    static final String DUE_DAYS_REFUSAL = "a rule cannot choose the days tasks are due on";

    private static final Pattern DUE_WORDS = Pattern.compile("\\b(?:due|deadlines?|scheduled|dated)\\b");

    /**
     * True when a sentence speaks of the days tasks are due on ("no tasks due on weekends", "no deadlines on
     * Fridays"): it names days and speaks of due dates.
     */
    static boolean aboutDueDays(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return DUE_WORDS.matcher(t).find() && !ProfileService.days(t).isEmpty();
    }

    /** See {@link RuleMatcher#appliesToPerson}. */
    boolean appliesToPerson(Rule rule, ActionType type, String goalText, Instant when) {
        return matcher.appliesToPerson(rule, type, goalText, when);
    }

    /** See {@link RuleMatcher#applies}. */
    boolean applies(Rule rule, CapabilityManifest m, String goalText, Instant when) {
        return matcher.applies(rule, m, goalText, when);
    }

    /** See {@link RuleMatcher#topicMatches}. */
    boolean topicMatches(Rule rule, String goalText) {
        return matcher.topicMatches(rule, goalText);
    }

    // ---- compile, dry-run, ratify, repeal -------------------------------------------------------------------

    /**
     * Compiles a sentence into a rule draft (not yet in force): the compiler capability drafts it, the kernel checks
     * the draft, keeps the person's own words as its text and only the topic keywords that name goals in particular
     * (see {@link #topicKeywords}), and replays the last 14 days to show its impact. A rule meant for some goals whose
     * keywords name none in particular is refused, and so is a sentence about due days, with a hint, before the
     * compiler is asked. The compiler's model calls are recorded.
     */
    public Compiled compile(String text, String principal) {
        if (!registry.has(COMPILER)) {
            return new Compiled(null, null, null, "make rules right now: the assistant that understands them is not "
                    + "running (start agent-assistant)");
        }
        if (aboutDueDays(text)) {
            return new Compiled(null, null, null, DUE_DAY_HINT);
        }
        ObjectNode params = Json.object();
        params.put("text", text);
        params.put("today", properties.today().toString());
        ArrayNode actions = params.putArray("actions");
        for (ActionType t : ActionType.values()) {
            if (!t.readOnly()) {
                actions.add(t.name());
            }
        }
        ArrayNode capabilities = params.putArray("capabilities");
        Set<String> seen = new HashSet<>();
        for (KernelApi.CapabilityView v : registry.views()) {
            if (seen.add(v.id())) {
                ObjectNode c = capabilities.addObject();
                c.put("id", v.id());
                c.put("name", v.name());
            }
        }
        var invocation = invoker.invoke(null, "constitution", -1, COMPILER, 1, null, params, Set.of());
        if (invocation.result() != null) {
            decisions.saveAll(null, "constitution", -1, invocation.result().decisions(),
                    invocation.manifest() == null ? null : DecisionLog.Source.of(invocation.manifest()));
        }
        if (!invocation.ok() || invocation.result().output() == null) {
            String reason = invocation.failureReason();
            log.info("rule \"{}\" not compiled: {}", text, reason);
            int refusal = reason == null ? -1 : reason.indexOf(DUE_DAYS_REFUSAL);
            return new Compiled(null, null, null, refusal < 0 ? RULE_HINT
                    : "make that a rule: " + reason.substring(refusal));
        }
        Rule draft;
        try {
            draft = Json.MAPPER.treeToValue(invocation.result().output(), Rule.class);
        } catch (RuntimeException e) {
            log.info("rule \"{}\" not compiled: unreadable draft ({})", text, e.getMessage());
            return new Compiled(null, null, null, RULE_HINT);
        }
        List<String> problems = Constitution.validate(draft, seen);
        if (!problems.isEmpty()) {
            log.info("rule \"{}\" not compiled: {}", text, String.join("; ", problems));
            return new Compiled(null, null, null, RULE_HINT);
        }
        List<String> openGoals = ledger.openGoals().stream().map(GoalState::text).toList();
        List<String> keywords = topicKeywords(text, draft.topicKeywords(), openGoals);
        if (keywords.isEmpty() && !draft.topicKeywords().isEmpty() && draft.goalTopic() == null) {
            // The rule was meant for some goals, but its words name none in particular: widening it to every goal
            // would enforce more than the person said.
            log.info("rule \"{}\" not compiled: keywords {} name no goal in particular", text, draft.topicKeywords());
            return new Compiled(null, null, null, vagueTopic(draft.topicKeywords()));
        }
        Rule rule = new Rule(null, text, draft.effect(), draft.actor(), draft.actions(), draft.capabilities(),
                draft.capabilityTags(), draft.goalTopic(), keywords, draft.days(), draft.limit(),
                Constitution.RuleStatus.ACTIVE, principal, Instant.now());
        return new Compiled(rule, Constitution.describe(rule), dryRun(rule, 14), null);
    }

    /**
     * Words that speak of goals and changes in general and never name one goal ("my goal", "the tasks"): the
     * compiler's own list ({@link Constitution#GENERIC_KEYWORDS}), in the form {@link RuleMatcher#words} gives them.
     */
    static final Set<String> GENERIC_KEYWORDS = Constitution.GENERIC_KEYWORDS.stream()
            .flatMap(w -> RuleMatcher.words(w).stream()).collect(Collectors.toUnmodifiableSet());

    /**
     * The keywords a compiled rule keeps, so a rule about one goal does not spread to every goal that shares a word
     * with it: words that speak of goals in general are taken out ("goal", "my", "task"); when the sentence names a
     * goal by several words ("my garden fence goal") and the keywords are only words of that name, the name stays one
     * keyword, matching only goals with all its words; and a keyword that more than half of the {@code openGoals}
     * hold (and at least two) is dropped, since it names none of them in particular.
     */
    static List<String> topicKeywords(String sentence, List<String> keywords, List<String> openGoals) {
        List<String> kept = new ArrayList<>();
        for (String k : keywords) {
            List<String> all = RuleMatcher.words(k);
            List<String> specific = all.stream().filter(w -> !GENERIC_KEYWORDS.contains(w)).toList();
            if (!specific.isEmpty()) {
                kept.add(specific.size() == all.size() ? k.strip() : String.join(" ", specific));
            }
        }
        // The name is read from the whole sentence: a rule names what it forbids in a negated clause ("never let
        // agents change my garden fence goal").
        List<String> name = RuleMatcher.words(String.join(" ", TargetCheck.nameWordsAsSaid(sentence))).stream()
                .filter(w -> !GENERIC_KEYWORDS.contains(w)).toList();
        if (name.size() >= 2 && !kept.isEmpty()
                && kept.stream().allMatch(k -> new HashSet<>(name).containsAll(RuleMatcher.words(k)))) {
            kept = new ArrayList<>(List.of(String.join(" ", name)));
        }
        kept.removeIf(k -> {
            long holding = openGoals.stream().filter(g -> RuleMatcher.keywordMatches(k, g)).count();
            return holding >= 2 && holding * 2 > openGoals.size();
        });
        return kept.stream().distinct().toList();
    }

    /** Why a rule whose keywords name no goal in particular is not made, and how to say it. */
    static String vagueTopic(List<String> keywords) {
        return "turn that into a rule: " + String.join(", ", keywords.stream().map(k -> "\"" + k + "\"").toList())
                + " name no goal in particular (or most of your goals). Say the goal's name as it appears in your "
                + "list, for example \"ask me before agents change my interview goal\".";
    }

    /**
     * Puts a draft in force under a new id and records the fact; a new limit rule is checked against the plan at
     * once, so an existing overload becomes a suggestion right away.
     */
    public Rule ratify(Rule rule, String principal) {
        Rule stored = rule.identified("r" + UUID.randomUUID().toString().substring(0, 7), principal, Instant.now());
        jdbc.sql("INSERT INTO rules (id, body, status, created_at) VALUES (:id, CAST(:b AS jsonb), 'ACTIVE', :at)")
                .param("id", stored.id()).param("b", Json.write(stored))
                .param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
        ledger.recordSystem(Facts.RULE_RATIFIED, new Facts.RuleChanged(stored.id(), stored.text(), principal), principal);
        log.info("rule {} ratified: {}", stored.id(), Constitution.describe(stored));
        if (stored.effect() == Constitution.Effect.LIMIT_TASKS_PER_DAY) {
            try {
                checkLimits();
            } catch (RuntimeException e) {
                log.warn("limit check after ratifying {} failed: {}", stored.id(), e.getMessage());
            }
        }
        return stored;
    }

    /** Takes a rule out of force; it stays stored as repealed, and the repeal is recorded as a fact. */
    public void repeal(Rule rule, String principal) {
        jdbc.sql("UPDATE rules SET status = 'REPEALED', body = CAST(:b AS jsonb) WHERE id = :id")
                .param("b", Json.write(rule.withStatus(Constitution.RuleStatus.REPEALED))).param("id", rule.id()).update();
        ledger.recordSystem(Facts.RULE_REPEALED, new Facts.RuleChanged(rule.id(), rule.text(), principal), principal);
    }

    /** The rules in force, oldest first. */
    public List<Rule> active() {
        return jdbc.sql("SELECT body::text FROM rules WHERE status = 'ACTIVE' ORDER BY created_at")
                .query(String.class).list().stream().map(b -> Json.read(b, Rule.class)).toList();
    }

    /** A rule id as {@link #ratify} makes them: "r" and seven hexadecimal digits. */
    private static final Pattern RULE_ID = Pattern.compile("\\b(r[0-9a-f]{7})\\b");

    /** By id, or by the words of the rule. */
    public Optional<Rule> find(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        List<Rule> rules = active();
        Matcher id = RULE_ID.matcher(ref.toLowerCase(Locale.ROOT));
        String r = id.find() ? id.group(1) : ref.strip().toLowerCase(Locale.ROOT);
        return rules.stream().filter(x -> x.id().equalsIgnoreCase(r)).findFirst()
                .or(() -> rules.stream().max(Comparator.comparingDouble(x -> Embeddings.lexicalSimilarity(ref,
                        x.text() + " " + Constitution.describe(x)))).filter(x -> Embeddings.lexicalSimilarity(ref,
                        x.text() + " " + Constitution.describe(x)) > 0.1));
    }

    /**
     * Replays the last {@code days} days of facts as if the rule had existed: which agent steps would have been
     * refused or held for approval, which of the person's actions would have been, and which days break a limit.
     */
    public Constitution.Impact dryRun(Rule rule, int days) {
        Instant since = Instant.now().minusSeconds(days * 86400L);
        int agentSteps = 0;
        int personActions = 0;
        List<String> examples = new ArrayList<>();
        Map<String, CapabilityManifest> manifests = new HashMap<>();
        registry.live().forEach(m -> manifests.putIfAbsent(m.id(), m));
        for (String goalId : ledger.goalsActiveSince(since)) {
            List<LedgerEvent> events = ledger.events(goalId);
            String goalText = ledger.load(goalId).map(GoalState::text).orElse("");
            Provenance provenance = new Provenance(events);
            Set<String> countedSteps = new HashSet<>();
            for (LedgerEvent e : events) {
                if (e.at().isBefore(since)) {
                    continue;
                }
                if (Provenance.CONTENT.contains(e.type()) && e.principal() != null && e.principal().startsWith("agent:")) {
                    Optional<Provenance.Source> source = provenance.of(e);
                    if (source.isEmpty() || !countedSteps.add(e.causationId())) {
                        continue;
                    }
                    CapabilityManifest m = manifests.get(source.get().capability());
                    if (m != null && applies(rule, m, goalText, e.at())) {
                        agentSteps++;
                        String example = m.id() + " on \"" + goalText + "\" would " + consequence(rule);
                        if (examples.size() < 4 && !examples.contains(example)) {
                            examples.add(example);
                        }
                    }
                } else if (e.byPerson()) {
                    ActionType type = personAction(e);
                    if (type != null && appliesToPerson(rule, type, goalText, e.at())) {
                        personActions++;
                        String example = "your " + type.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " on \""
                                + goalText + "\" would " + consequence(rule);
                        if (examples.size() < 4 && !examples.contains(example)) {
                            examples.add(example);
                        }
                    }
                }
            }
        }
        Map<LocalDate, List<Due>> overloaded = rule.effect() == Constitution.Effect.LIMIT_TASKS_PER_DAY
                ? overloadedDays(rule) : Map.of();
        // The count is in the impact itself; the examples name the busiest days.
        overloaded.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<LocalDate, List<Due>> e)
                        -> e.getValue().size()).reversed().thenComparing(Map.Entry::getKey))
                .limit(Math.max(0, Math.min(2, 4 - examples.size())))
                .forEach(e -> examples.add(e.getKey() + " has " + e.getValue().size() + " open tasks due"));
        return new Constitution.Impact(days, agentSteps, personActions, overloaded.size(), examples);
    }

    /** The person's action a fact records, in the vocabulary rules use; null for facts no rule is about. */
    static ActionType personAction(LedgerEvent e) {
        return switch (e.type()) {
            case Facts.TASK_STATUS_CHANGED -> e.payload(Facts.TaskStatusChanged.class).to()
                    == TaskStatus.DROPPED ? ActionType.REMOVE_TASK : ActionType.SET_TASK_STATUS;
            case Facts.GOAL_CREATED -> ActionType.CREATE_GOAL;
            case Facts.TASK_ADDED -> ActionType.ADD_TASK;
            case Facts.TASK_UPDATED -> ActionType.UPDATE_TASK;
            case Facts.GOAL_RESCHEDULED -> ActionType.RESCHEDULE_GOAL;
            case Facts.GOAL_STATUS_CHANGED -> {
                Facts.GoalStatusChanged p = e.payload(Facts.GoalStatusChanged.class);
                // Completing the last task or reopening one also changes the goal's status; those are task actions.
                boolean reopened = "reopened".equals(p.reason())
                        && (p.from() == GoalStatus.CANCELLED || p.from() == GoalStatus.COMPLETED);
                yield p.to() == GoalStatus.CANCELLED ? ActionType.CANCEL_GOAL : reopened ? ActionType.REOPEN_GOAL : null;
            }
            default -> null;
        };
    }

    private static String consequence(Rule rule) {
        return rule.effect() == Constitution.Effect.FORBID ? "have been refused" : "have waited for your yes";
    }

    // ---- enforcement ---------------------------------------------------------------------------------------

    /** The strongest active rule on the person's own change to a goal; read actions are always allowed. */
    public Ruling checkPerson(ActionType type, String goalId) {
        return checkPerson(type, goalId, null);
    }

    /** {@code newGoalText} is the text of a goal about to be created, so topic rules can apply to it. */
    public Ruling checkPerson(ActionType type, String goalId, String newGoalText) {
        if (type.readOnly()) {
            return Ruling.ALLOW;
        }
        String goalText = goalId != null ? ledger.load(goalId).map(GoalState::text).orElse(null) : newGoalText;
        Ruling strongest = Ruling.ALLOW;
        for (Rule rule : active()) {
            if (appliesToPerson(rule, type, goalText, Instant.now())) {
                strongest = stronger(strongest, rule);
            }
        }
        return strongest;
    }

    /** The strongest active rule on a capability's step on a goal. */
    public Ruling checkAgent(CapabilityManifest manifest, String goalId) {
        String goalText = goalId == null ? null : ledger.load(goalId).map(GoalState::text).orElse(null);
        Ruling strongest = Ruling.ALLOW;
        for (Rule rule : active()) {
            if (applies(rule, manifest, goalText, Instant.now())) {
                strongest = stronger(strongest, rule);
            }
        }
        return strongest;
    }

    private static Ruling stronger(Ruling current, Rule rule) {
        Verdict v = rule.effect() == Constitution.Effect.FORBID ? Verdict.FORBID : Verdict.ASK_FIRST;
        return v.ordinal() > current.verdict().ordinal() ? new Ruling(v, rule) : current;
    }

    /**
     * Active rules whose topic covers a goal with this text, so the person can be told when a goal they just created
     * falls under one. Rules without a topic apply to every goal and are not listed.
     */
    public List<Rule> rulesCovering(String goalText) {
        return active().stream().filter(r -> r.goalTopic() != null || !r.topicKeywords().isEmpty())
                .filter(r -> topicMatches(r, goalText)).toList();
    }

    // ---- limits --------------------------------------------------------------------------------------------

    /** An open task due on some day, with its goal and the goal's deadline (null when it has none). */
    record Due(String goalId, String goalText, TaskView task, LocalDate goalDue) {
    }

    /** Days with more open tasks due than a limit rule allows, with the tasks due that day. */
    Map<LocalDate, List<Due>> overloadedDays(Rule rule) {
        Map<LocalDate, List<Due>> byDay = new TreeMap<>();
        for (GoalState s : ledger.openGoals()) {
            if (!topicMatches(rule, s.text())) {
                continue;
            }
            s.tasks().stream()
                    .filter(t -> t.status().isOpen() && t.dueDate() != null && !t.dueDate().isBefore(properties.today()))
                    .forEach(t -> byDay.computeIfAbsent(t.dueDate(), d -> new ArrayList<>())
                            .add(new Due(s.goalId(), s.text(), t, s.dueDate())));
        }
        byDay.entrySet().removeIf(e -> e.getValue().size() <= rule.limit());
        return byDay;
    }

    /**
     * For every limit rule, one message: move the least urgent tasks of overloaded days to the next working days
     * (the person's working days, not holidays) that still have room, never past their goal's deadline or past a
     * task that waits for them. Deterministic: same ledger, same suggestion, so the message is posted once. Nothing is
     * suggested while automatic work is paused.
     */
    public synchronized void checkLimits() {
        if (policy.paused()) {
            return;
        }
        WorkdayCalendar calendar = profile.calendar();
        for (Rule rule : active()) {
            if (rule.effect() != Constitution.Effect.LIMIT_TASKS_PER_DAY) {
                continue;
            }
            Map<LocalDate, List<Due>> overloaded = overloadedDays(rule);
            if (overloaded.isEmpty()) {
                continue;
            }
            Map<LocalDate, Integer> load = new HashMap<>();
            Map<String, GoalState> states = new HashMap<>();
            for (GoalState s : ledger.openGoals()) {
                if (!topicMatches(rule, s.text())) {
                    continue; // only the goals the rule is about count towards a day's load
                }
                states.put(s.goalId(), s);
                s.tasks().stream().filter(t -> t.status().isOpen() && t.dueDate() != null)
                        .forEach(t -> load.merge(t.dueDate(), 1, Integer::sum));
            }
            List<Action> suggestions = new ArrayList<>();
            List<String> moves = new ArrayList<>();
            Set<String> goals = new LinkedHashSet<>();
            for (Map.Entry<LocalDate, List<Due>> day : overloaded.entrySet()) {
                List<Due> tasks = new ArrayList<>(day.getValue());
                tasks.sort(Comparator.comparing((Due d) -> d.task().priority() == null ? 0 : d.task().priority()));
                for (Due d : tasks.subList(0, tasks.size() - rule.limit())) {
                    LocalDate latest = latestDate(d, states.get(d.goalId()));
                    LocalDate target = nextRoom(day.getKey(), latest, load, rule.limit(), calendar);
                    if (target == null) {
                        continue; // no room in time; left to the person
                    }
                    load.merge(target, 1, Integer::sum);
                    load.merge(day.getKey(), -1, Integer::sum);
                    suggestions.add(new Action(ActionType.UPDATE_TASK, d.goalId(), d.task().key(), null, target, null,
                            null, null, "spread the load", null));
                    moves.add(d.task().key() + " \"" + d.task().title() + "\" " + day.getKey() + " -> " + target);
                    goals.add(d.goalId());
                }
            }
            Map.Entry<LocalDate, List<Due>> busiest = overloaded.entrySet().stream()
                    .max(Comparator.comparingInt(e -> e.getValue().size())).orElseThrow();
            String breach = overloaded.size() + " day(s) have more than " + rule.limit() + " open tasks due (the busiest is "
                    + busiest.getKey() + " with " + busiest.getValue().size() + "), but your rule \"" + rule.text()
                    + "\" allows " + rule.limit() + ".";
            if (suggestions.isEmpty()) {
                // Nothing fits before the deadlines: say so, so the person can decide what to give up or move.
                inbox.post(new Inbox.Post(Inbox.Kind.RULE_LIMIT, null, breach + " No task can move to a day with room "
                                + "before its deadline, so this needs your decision.", List.of(),
                        "limit:" + rule.id() + ":" + Hashing.sha256(overloaded.keySet().toString())
                                .substring(0, 12), "kernel", "kernel", List.of(), null), null);
                continue;
            }
            String text = breach + " I can move " + suggestions.size() + " task(s) of " + goals.size()
                    + " goal(s) to days that still have room before their deadlines: "
                    + String.join("; ", moves.subList(0, Math.min(5, moves.size())))
                    + (moves.size() > 5 ? "; and " + (moves.size() - 5) + " more" : "") + ".";
            inbox.post(new Inbox.Post(Inbox.Kind.RULE_LIMIT, null, text, suggestions,
                            "limit:" + rule.id() + ":" + Hashing.sha256(String.join("|", moves)).substring(0, 12),
                            "kernel", "kernel", List.of(), null),
                    "move " + suggestions.size() + " task(s) of " + goals.size() + " goal(s) to later days");
        }
    }

    /** How far ahead the spreader looks for a day with room when nothing limits a task's date. */
    static final int SPREAD_HORIZON_DAYS = 366;

    /**
     * The first working day after {@code day} with fewer than {@code limit} tasks due, no later than {@code latest}
     * (when set) and within {@link #SPREAD_HORIZON_DAYS}; null when there is none.
     */
    static LocalDate nextRoom(LocalDate day, LocalDate latest, Map<LocalDate, Integer> load, int limit,
                              WorkdayCalendar calendar) {
        LocalDate last = latest != null ? latest : day.plusDays(SPREAD_HORIZON_DAYS);
        for (LocalDate target = day.plusDays(1); !target.isAfter(last); target = target.plusDays(1)) {
            if (calendar.isWorkday(target) && load.getOrDefault(target, 0) < limit) {
                return target;
            }
        }
        return null;
    }

    /** The last day a task can move to: its goal's deadline, or the earliest due date of a task waiting for it. */
    static LocalDate latestDate(Due d, GoalState state) {
        LocalDate latest = d.goalDue();
        if (state != null) {
            for (TaskView other : state.tasks()) {
                if (other.status().isOpen() && other.dependsOn().contains(d.task().id()) && other.dueDate() != null
                        && (latest == null || other.dueDate().isBefore(latest))) {
                    latest = other.dueDate();
                }
            }
        }
        return latest;
    }
}
