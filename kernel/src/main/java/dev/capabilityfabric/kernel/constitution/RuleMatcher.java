package dev.capabilityfabric.kernel.constitution;

import dev.capabilityfabric.kernel.registry.Embeddings;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Constitution;
import dev.capabilityfabric.protocol.Constitution.Rule;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.Vocabulary;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Decides whether a compiled rule applies to a change. Pure apart from the embedding lookup used to match a goal
 * to the rule's topic by meaning; with no embedding model it falls back to words.
 */
final class RuleMatcher {

    /** A goal can be "about" the rule's topic by meaning alone from this cosine similarity up. */
    static final double TOPIC_COSINE = 0.75;
    /** By how much the goal must be nearer to the topic than any other open goal. */
    static final double TOPIC_MARGIN = 0.05;

    private final Function<String, Optional<float[]>> embed;
    private final Supplier<List<String>> openGoalTexts;

    /** {@code openGoalTexts} gives the texts of the person's open goals, the competitors for a topic. */
    RuleMatcher(Function<String, Optional<float[]>> embed, Supplier<List<String>> openGoalTexts) {
        this.embed = embed;
        this.openGoalTexts = openGoalTexts;
    }

    /**
     * True when the rule governs the person's own change: a rule for the person or for anyone, not a limit and not
     * about capabilities, that lists the action (or no action), is in force on that day and covers the goal's topic.
     */
    boolean appliesToPerson(Rule rule, ActionType type, String goalText, Instant when) {
        if (rule.effect() == Constitution.Effect.LIMIT_TASKS_PER_DAY || rule.actor() == Constitution.Actor.AGENTS) {
            return false;
        }
        if (!rule.capabilities().isEmpty() || !rule.capabilityTags().isEmpty()) {
            return false;
        }
        return (rule.actions().isEmpty() || rule.actions().contains(type)) && onDay(rule, when)
                && topicMatches(rule, goalText);
    }

    /**
     * True when the rule governs a capability's step: a rule for agents or for anyone, not a limit, on a capability
     * that writes something and matches the rule's capabilities, tags and actions (through what it writes), in
     * force on that day and covering the goal's topic.
     */
    boolean applies(Rule rule, CapabilityManifest m, String goalText, Instant when) {
        if (rule.effect() == Constitution.Effect.LIMIT_TASKS_PER_DAY || rule.actor() == Constitution.Actor.PERSON) {
            return false;
        }
        if (m.produces().isEmpty()) {
            return false; // reading and suggesting are never restricted
        }
        if (!rule.capabilities().isEmpty() && !rule.capabilities().contains(m.id())) {
            return false;
        }
        if (!rule.capabilityTags().isEmpty() && rule.capabilityTags().stream().noneMatch(t -> m.tags().contains(t))) {
            return false;
        }
        if (!rule.actions().isEmpty() && rule.actions().stream().noneMatch(equivalentActions(m)::contains)) {
            return false;
        }
        return onDay(rule, when) && topicMatches(rule, goalText);
    }

    /** What a capability's writes amount to, in the person's action vocabulary. */
    static Set<ActionType> equivalentActions(CapabilityManifest m) {
        Set<ActionType> actions = new HashSet<>();
        for (String token : m.produces()) {
            if (token.equals(Vocabulary.TASKS)) {
                actions.add(ActionType.ADD_TASK);
            } else if (token.equals(Vocabulary.TASK_DEPENDS_ON) || token.equals(Vocabulary.TASK_DUE_DATE)
                    || token.equals(Vocabulary.TASK_EFFORT_HOURS) || token.equals(Vocabulary.TASK_PRIORITY)
                    || token.equals(Vocabulary.TASK_RANK)) {
                actions.add(ActionType.UPDATE_TASK);
            }
        }
        return actions;
    }

    private boolean onDay(Rule rule, Instant when) {
        return rule.days().isEmpty() || rule.days().contains(when.atZone(ZoneId.systemDefault()).getDayOfWeek());
    }

    /**
     * The words of a text as keywords are compared: lower case, curly apostrophes straightened, "'s" and other
     * apostrophes and hyphens removed ("e-mail" is "email", "interview's" is "interview"), every other sign a space,
     * and each word reduced to its singular ("interviews" is "interview", "taxes" is "tax", "movies" and "movie" are
     * one word; see {@link #singular}).
     */
    static List<String> words(String text) {
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'');
        t = t.replaceAll("'s\\b", "").replaceAll("['-]", "").replaceAll("[^a-z0-9]+", " ").strip();
        return t.isEmpty() ? List.of() : Arrays.stream(t.split(" ")).map(RuleMatcher::singular).toList();
    }

    /**
     * A word as its singular is compared: "-sses", "-xes", "-ches" and "-shes" lose "es", other plurals their "s";
     * then the word ends that a singular and its plural spell differently are written one way: a final "ie" or a "y"
     * after a consonant as "i" ("movie" and "movies", "party" and "parties", "cookie" and "cookies"), a final "oe" as
     * "o" ("potato" and "potatoes", "hero" and "heroes").
     */
    private static String singular(String w) {
        String s = w;
        if (s.length() > 4 && (s.endsWith("sses") || s.endsWith("xes") || s.endsWith("ches") || s.endsWith("shes"))) {
            s = s.substring(0, s.length() - 2);
        } else if (s.length() > 3 && s.endsWith("s") && !s.endsWith("ss")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.length() > 3 && s.endsWith("ie")) {
            return s.substring(0, s.length() - 1);
        }
        if (s.length() > 3 && s.endsWith("y") && "aeiou".indexOf(s.charAt(s.length() - 2)) < 0) {
            return s.substring(0, s.length() - 1) + "i";
        }
        if (s.length() > 3 && s.endsWith("oe")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * True when a goal text holds a keyword: every word of it, compared as {@link #words} so spelling variants and
     * plurals match ("e-mail" and "emails"). A keyword of several words is one name and matches only a goal that has
     * all of them, in any order ("hallway repaint" matches "repaint the hallway", not "repaint the fence").
     */
    static boolean keywordMatches(String keyword, String goalText) {
        List<String> k = words(keyword);
        return !k.isEmpty() && goalText != null && new HashSet<>(words(goalText)).containsAll(k);
    }

    /**
     * Keywords first (see {@link #keywordMatches}). Without a keyword, by meaning: the goal must be close to the
     * rule's topic and clearly the nearest of the person's open goals to it, so a rule about one goal does not spread
     * to merely related ones. With no embedding model, by word overlap.
     */
    boolean topicMatches(Rule rule, String goalText) {
        if (rule.goalTopic() == null && rule.topicKeywords().isEmpty()) {
            return true;
        }
        if (goalText == null) {
            return false;
        }
        for (String k : rule.topicKeywords()) {
            if (keywordMatches(k, goalText)) {
                return true;
            }
        }
        if (rule.goalTopic() == null) {
            return false;
        }
        Optional<float[]> topic = embed.apply(rule.goalTopic());
        Optional<float[]> goal = topic.isEmpty() ? Optional.empty() : embed.apply(goalText);
        if (topic.isEmpty() || goal.isEmpty()) {
            return Embeddings.lexicalSimilarity(rule.goalTopic(), goalText) > 0.15;
        }
        double similarity = Embeddings.cosine(topic.get(), goal.get());
        if (similarity < TOPIC_COSINE) {
            return false;
        }
        double runnerUp = 0;
        for (String other : openGoalTexts.get()) {
            if (other != null && !other.equals(goalText)) {
                Optional<float[]> v = embed.apply(other);
                if (v.isPresent()) {
                    runnerUp = Math.max(runnerUp, Embeddings.cosine(topic.get(), v.get()));
                }
            }
        }
        return similarity - runnerUp >= TOPIC_MARGIN;
    }
}
