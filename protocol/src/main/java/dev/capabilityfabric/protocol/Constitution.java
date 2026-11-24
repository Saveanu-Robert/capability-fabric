package dev.capabilityfabric.protocol;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The person's constitution: rules written in plain words, compiled by a model into the deterministic form below,
 * shown back with what they would have changed in the past, and enforced by the kernel only after the person
 * ratifies them. The model never takes part in enforcement.
 */
public final class Constitution {

    private Constitution() {
    }

    public enum Effect {
        /** Matching changes wait for the person's yes (agents' steps become approvals). */
        ASK_FIRST,
        /** Matching changes are refused. */
        FORBID,
        /** At most {@code limit} open tasks may be due on the same day; breaches become inbox suggestions. */
        LIMIT_TASKS_PER_DAY
    }

    /** Whose changes a rule covers: the person's own, the capabilities' (agents'), or both. */
    public enum Actor { PERSON, AGENTS, ANYONE }

    public enum RuleStatus { ACTIVE, REPEALED }

    /**
     * A compiled rule. Empty lists mean "any". {@code goalTopic} is matched by meaning (embeddings) and
     * {@code topicKeywords} by words; a goal matches if either matches. {@code days} are the weekdays on which the
     * rule is in force, judged by the day a change is made (not by the day a task is due).
     */
    public record Rule(String id, String text, Effect effect, Actor actor, List<Conversation.ActionType> actions,
                       List<String> capabilities, List<String> capabilityTags, String goalTopic,
                       List<String> topicKeywords, List<DayOfWeek> days, Integer limit, RuleStatus status,
                       String createdBy, Instant createdAt) {
        public Rule {
            actions = actions == null ? List.of() : List.copyOf(actions);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            capabilityTags = capabilityTags == null ? List.of() : List.copyOf(capabilityTags);
            topicKeywords = topicKeywords == null ? List.of() : List.copyOf(topicKeywords);
            days = days == null ? List.of() : List.copyOf(days);
        }

        public Rule withStatus(RuleStatus next) {
            return new Rule(id, text, effect, actor, actions, capabilities, capabilityTags, goalTopic, topicKeywords,
                    days, limit, next, createdBy, createdAt);
        }

        /** The rule as ratified: its id, who ratified it and when, and status {@code ACTIVE}. */
        public Rule identified(String newId, String by, Instant at) {
            return new Rule(newId, text, effect, actor, actions, capabilities, capabilityTags, goalTopic, topicKeywords,
                    days, limit, RuleStatus.ACTIVE, by, at);
        }
    }

    /** What a rule would have changed over recent history: the dry run shown before ratification. */
    public record Impact(int days, int agentStepsAffected, int personActionsAffected, int dueDayBreaches,
                         List<String> examples) {
        public Impact {
            examples = examples == null ? List.of() : List.copyOf(examples);
        }
    }

    /**
     * Words that name no goal of their own ("goal", "task", "my", "plan"): as topic keywords they would make a rule
     * about one goal apply to every goal whose name holds them.
     */
    public static final Set<String> GENERIC_KEYWORDS = Set.of("a", "an", "the", "my", "mine", "our", "your", "his",
            "her", "their", "this", "that", "these", "those", "it", "me", "i", "and", "or", "of", "for", "to", "in",
            "on", "with", "about", "goal", "goals", "task", "tasks", "todo", "todos", "to-do", "item", "items", "plan",
            "plans", "planning", "project", "projects", "work", "thing", "things", "stuff", "list", "lists", "change",
            "changes", "any", "all", "every", "everything", "anything", "agent", "agents", "system", "ai",
            "assistant", "rule", "rules", "day", "days", "week", "weeks", "deadline", "deadlines", "date", "dates",
            "new", "old");

    private static final Pattern EVERY_CHANGE = Pattern.compile("(?i)\\b(?:any|every|all)"
            + "\\s+(?:single\\s+|other\\s+|kinds?\\s+of\\s+|sorts?\\s+of\\s+)?(?:changes?|edits?|modifications?|updates?"
            + "|actions?)\\b|\\b(?:anything|everything|nothing)\\b");

    /**
     * True when the person's words say a rule covers every kind of change: "any change", "every change", "all
     * edits", "anything", "everything", "nothing changes".
     */
    public static boolean coversEveryChange(String text) {
        return text != null && EVERY_CHANGE.matcher(text).find();
    }

    /** What capabilities can do to a goal, in the person's action vocabulary. */
    public static final Set<Conversation.ActionType> AGENT_ACTIONS = Set.of(Conversation.ActionType.ADD_TASK,
            Conversation.ActionType.UPDATE_TASK);

    /**
     * Why a compiled rule cannot be enforced as it is; empty when it can. Shared by the compiler (to repair its
     * drafts) and the kernel (which decides). A rule that is neither a limit nor about agents' changes must say what
     * it applies to (kinds of change, capabilities, a goal topic or days), unless the person's words say it covers
     * every change ({@link #coversEveryChange}); a rule about agents applies to all of their changes when it says no
     * more. Topic keywords must name the goal itself, not a word every goal may hold ({@link #GENERIC_KEYWORDS}).
     */
    public static List<String> validate(Rule rule, Set<String> knownCapabilities) {
        List<String> problems = new ArrayList<>();
        if (rule.text() == null || rule.text().isBlank()) {
            problems.add("text must repeat the person's rule");
        }
        if (rule.effect() == null) {
            problems.add("effect must be ASK_FIRST, FORBID or LIMIT_TASKS_PER_DAY");
            return problems;
        }
        if (rule.actor() == null) {
            problems.add("actor must be PERSON, AGENTS or ANYONE");
        }
        if (rule.effect() == Effect.LIMIT_TASKS_PER_DAY) {
            if (rule.limit() == null || rule.limit() < 1 || rule.limit() > 50) {
                problems.add("a LIMIT_TASKS_PER_DAY rule needs a limit between 1 and 50");
            }
        } else {
            for (Conversation.ActionType a : rule.actions()) {
                if (a.readOnly()) {
                    problems.add(a + " only shows information; rules apply to changes");
                } else if (rule.actor() == Actor.AGENTS && !AGENT_ACTIONS.contains(a)) {
                    problems.add("agents only add tasks (ADD_TASK) or change task fields (UPDATE_TASK), never " + a
                            + "; for \"any change\" leave actions empty");
                }
            }
            for (String c : rule.capabilities()) {
                if (!knownCapabilities.contains(c)) {
                    problems.add("unknown capability " + c + "; known: " + knownCapabilities);
                }
            }
            if (rule.actor() == Actor.PERSON && (!rule.capabilities().isEmpty() || !rule.capabilityTags().isEmpty())) {
                problems.add("a rule about your own changes cannot name capabilities; use actor AGENTS or ANYONE");
            }
            boolean scoped = !rule.actions().isEmpty() || !rule.capabilities().isEmpty()
                    || !rule.capabilityTags().isEmpty() || rule.goalTopic() != null || !rule.topicKeywords().isEmpty()
                    || !rule.days().isEmpty() || rule.actor() == Actor.AGENTS || coversEveryChange(rule.text());
            if (!scoped) {
                problems.add("the rule must say what it applies to (actions, capabilities, a goal topic or days); "
                        + "leave all of them empty only when the person says it covers every change");
            }
        }
        for (String k : rule.topicKeywords()) {
            if (k != null && GENERIC_KEYWORDS.contains(k.strip().toLowerCase(Locale.ROOT))) {
                problems.add("topic keyword \"" + k + "\" is too general: use a word from the name of the goal the rule "
                        + "is about");
            }
        }
        if (rule.goalTopic() != null && rule.goalTopic().length() > 80) {
            problems.add("goalTopic must be a few words");
        }
        return problems;
    }

    /** The compiled rule in plain words, built from data only, so the person sees exactly what will be enforced. */
    public static String describe(Rule r) {
        if (r.effect() == Effect.LIMIT_TASKS_PER_DAY) {
            return "At most " + r.limit() + " open tasks due on the same day" + goals(r)
                    + "; when that is exceeded I suggest moving tasks.";
        }
        String who = switch (r.actor() == null ? Actor.ANYONE : r.actor()) {
            case PERSON -> "your own changes";
            case AGENTS -> "changes by agents";
            case ANYONE -> "any change";
        };
        List<String> what = new ArrayList<>();
        if (!r.actions().isEmpty()) {
            what.add("of kind " + r.actions().stream().map(a -> a.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                    .collect(Collectors.joining(", ")));
        }
        if (!r.capabilities().isEmpty()) {
            what.add("made by " + String.join(", ", r.capabilities()));
        }
        if (!r.capabilityTags().isEmpty()) {
            what.add("made by capabilities tagged " + String.join(", ", r.capabilityTags()));
        }
        String when = r.days().isEmpty() ? "" : " on " + r.days().stream()
                .map(d -> d.name().charAt(0) + d.name().substring(1).toLowerCase(Locale.ROOT)).collect(Collectors.joining(", "));
        String then = r.effect() == Effect.FORBID ? "are refused" : "wait for your yes";
        return capitalise(who) + (what.isEmpty() ? "" : " " + String.join(" ", what)) + goals(r) + when + " " + then + ".";
    }

    private static String goals(Rule r) {
        if (r.goalTopic() == null && r.topicKeywords().isEmpty()) {
            return "";
        }
        String topic = r.goalTopic() == null ? "" : " about \"" + r.goalTopic() + "\"";
        String words = r.topicKeywords().isEmpty() ? "" : (topic.isEmpty() ? " mentioning " : " or mentioning ")
                + String.join(", ", r.topicKeywords());
        return " on goals" + topic + words;
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
