package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.kernel.ledger.LedgerRules;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.Inbox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which inbox message a sentence answers. With several messages unread, "yes, do it" does not say which one, and the
 * model's pick is not trusted on its own: the person's words must point at the message.
 */
final class MessageCheck {

    private MessageCheck() {
    }

    /**
     * The message a sentence answers. A message that is no longer unread is returned as named (answering it says
     * so). With at most one unread message, the named one, or that one when the sentence points at it (see
     * {@link #pointsAt}): a bare "yes" or "don't" may be about something else. With several, the sentence itself
     * must point at one message more than at any other; when the model named a different one, or nothing stands
     * out, empty, so the person is asked which.
     */
    static Optional<Inbox.Message> answered(Optional<Inbox.Message> named, String said, List<Inbox.Message> unread,
                                            Predicate<Inbox.Message> aboutItsGoal) {
        if (named.isPresent() && named.get().status() != Inbox.Status.UNREAD) {
            return named;
        }
        if (unread.size() <= 1) {
            return named.isPresent() ? named
                    : unread.stream().filter(m -> pointsAt(said, m, aboutItsGoal) > 0).findFirst();
        }
        Inbox.Message best = null;
        int bestScore = 0;
        int runnerUp = 0;
        for (Inbox.Message m : unread) {
            int score = pointsAt(said, m, aboutItsGoal);
            if (score > bestScore) {
                runnerUp = bestScore;
                bestScore = score;
                best = m;
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        if (best == null || bestScore == runnerUp) {
            return Optional.empty();
        }
        return named.isPresent() && !named.get().id().equals(best.id()) ? Optional.empty() : Optional.of(best);
    }

    private static final Pattern HEX = Pattern.compile("\\b[0-9a-f]{4,}\\b");
    private static final Map<Inbox.Kind, Pattern> KIND_WORDS = Map.of(
            Inbox.Kind.DEADLINE_RISK, Pattern.compile("\\b(?:deadline|risk|warning|in time|won'?t make)\\b"),
            Inbox.Kind.DUE_TODAY, Pattern.compile("\\b(?:due today|today)\\b"),
            Inbox.Kind.BLOCKED_CHAIN, Pattern.compile("\\b(?:blocked|stuck|chain|waiting)\\b"),
            Inbox.Kind.STALE_GOAL, Pattern.compile("\\b(?:stale|idle|untouched|neglected|no progress|forgotten)\\b"),
            Inbox.Kind.RULE_LIMIT, Pattern.compile("\\b(?:limit|rule|too many|overload\\w*|spread|busy days?)\\b"),
            Inbox.Kind.OVERDUE, Pattern.compile("\\b(?:overdue|late|past due|missed)\\b"),
            Inbox.Kind.ADMISSION, Pattern.compile("\\b(?:admit\\w*|admission|agent|join\\w*)\\b"),
            Inbox.Kind.SECURITY, Pattern.compile("\\b(?:security|pairing|pair|code|access)\\b"));

    /** Words that speak of a message in general ("the suggestion", "your message", "what you suggested"). */
    private static final Pattern MESSAGE_WORDS = Pattern.compile("\\b(?:messages?|suggest\\w*|inbox|notifications?"
            + "|warnings?|reminders?|you said|you mentioned|recommend\\w*)\\b");

    /**
     * How strongly a sentence points at a message: its id decides on its own; otherwise one point each for the words
     * of its kind ("the deadline warning"), a task key it is about ("T3"), the agent it admits, and its goal, and one
     * for words that speak of a message in general ("the suggestion"), which every message shares.
     */
    static int pointsAt(String said, Inbox.Message m, Predicate<Inbox.Message> aboutItsGoal) {
        String t = said == null ? "" : said.toLowerCase(Locale.ROOT);
        Matcher hex = HEX.matcher(t);
        while (hex.find()) {
            String h = hex.group();
            if ((h.length() >= 8 || h.chars().anyMatch(Character::isDigit)) && m.id().startsWith(h)) {
                return 100;
            }
        }
        int score = (KIND_WORDS.get(m.kind()).matcher(t).find() ? 1 : 0) + (MESSAGE_WORDS.matcher(t).find() ? 1 : 0);
        Set<String> keys = ConversationService.namedKeys(TargetCheck.withoutNegated(said));
        if (!keys.isEmpty() && (ConversationService.namedKeys(m.text()).stream().anyMatch(keys::contains) || m.suggestions().stream()
                .anyMatch(s -> s.task() != null && keys.contains(s.task().toUpperCase(Locale.ROOT))))) {
            score++;
        }
        if (m.suggestions().stream().anyMatch(s -> s.type() == ActionType.ADMIT_PROVIDER && s.text() != null
                && !s.text().isBlank() && t.contains(s.text().toLowerCase(Locale.ROOT)))) {
            score++;
        }
        return aboutItsGoal.test(m) ? score + 1 : score;
    }

    /** The question when the sentence does not say which of several messages it answers. */
    static String which(List<Inbox.Message> unread) {
        return which(unread, unread.size());
    }

    /**
     * The question when the sentence does not say which message it answers: the {@code total} unread messages are
     * counted and at most five of {@code candidates} listed (the messages the words point at equally, or all).
     */
    static String which(List<Inbox.Message> candidates, int total) {
        if (total == 0 || candidates.isEmpty()) {
            return "there is no unread message to answer";
        }
        List<String> lines = candidates.stream().limit(5).map(m -> "[" + m.id().substring(0, 8) + "] "
                + LedgerRules.readable(m.kind()) + (m.goalText() == null ? "" : " about \"" + m.goalText() + "\""))
                .toList();
        boolean all = candidates.size() == total;
        return "Which message? You have " + total + " unread" + (all ? "" : "; your words fit " + candidates.size())
                + ": " + String.join("; ", lines) + (candidates.size() > lines.size() ? "; ..." : "")
                + ". Say its id or what it is about.";
    }

    /**
     * The messages the words point at most strongly when they do not single one out (see {@link #pointsAt}); every
     * unread message, newest first, when the words point at none.
     */
    static List<Inbox.Message> tied(String said, List<Inbox.Message> unread, Predicate<Inbox.Message> aboutItsGoal) {
        int best = 0;
        for (Inbox.Message m : unread) {
            best = Math.max(best, pointsAt(said, m, aboutItsGoal));
        }
        int top = best;
        List<Inbox.Message> newestFirst = new ArrayList<>(unread);
        Collections.reverse(newestFirst);
        return top == 0 ? newestFirst : newestFirst.stream().filter(m -> pointsAt(said, m, aboutItsGoal) == top).toList();
    }

    /** Words that answer a message or speak of one, which say nothing about the goal it is about. */
    private static final Pattern ANSWER_WORDS = Pattern.compile("(?i)\\b(?:yes|yeah|yep|sure|ok|okay|no|nope|nah"
            + "|accept\\w*|agree\\w*|dismiss\\w*|reject\\w*|declin\\w*|ignor\\w*|refus\\w*|skip|fine|go ahead"
            + "|do it|please|suggest\\w*|recommend\\w*|propos\\w*|messages?|warnings?|notifications?|reminders?"
            + "|alerts?|idea|answer|inbox|one|about|regarding)\\b");

    /**
     * A sentence without its answer and message words ("yes, accept the suggestion for the bake sale" keeps "the bake
     * sale").
     */
    static String withoutAnswerWords(String said) {
        return said == null ? "" : ANSWER_WORDS.matcher(said).replaceAll(" ").replaceAll("\\s+", " ").strip();
    }

    /** Words a bare answer is made of besides its answer words: "it", "that", "now", "not", "let", ... */
    private static final Set<String> BARE = Set.of("it", "that", "this", "the", "a", "an", "to", "for", "of", "and",
            "or", "then", "now", "just", "i", "me", "my", "we", "you", "your", "is", "are", "be", "will", "would",
            "can", "do", "does", "did", "let", "go", "with", "all", "right", "so", "in", "on", "at", "sounds", "good",
            "great", "not", "don", "dont", "t", "s", "never", "them", "those", "these", "there", "here", "way",
            "course", "why", "thing", "really", "definitely", "absolutely", "certainly", "alright", "agreed", "like",
            "as", "said");
    /** The words that name each kind of change a suggestion makes: "replan it" names a suggestion to re-plan. */
    private static final Map<ActionType, String> CHANGE_WORDS = Map.of(
            ActionType.REFINE_GOAL, "replan re plan plan again refine",
            ActionType.RESCHEDULE_GOAL, "move push postpone reschedule deadline later",
            ActionType.UPDATE_TASK, "move push reschedule postpone estimate later",
            ActionType.SET_TASK_STATUS, "mark done finished complete",
            ActionType.REMOVE_TASK, "drop remove",
            ActionType.CANCEL_GOAL, "cancel",
            ActionType.ADD_TASK, "add",
            ActionType.ADMIT_PROVIDER, "admit let in join");

    /** Every word that names a kind of change, whichever suggestion it belongs to: "cancel", "move", "done", ... */
    private static final Set<String> ANY_CHANGE_WORD = changeWords(CHANGE_WORDS.values());
    /**
     * Words that state a task's status or a change: "done", "finished", "complete", "started", "blocked", "stuck",
     * "cancel", "drop". Unless the suggestion itself makes that kind of change, a sentence holding one says something
     * else than "yes" ("yes, T3 is done" to a suggestion to re-plan).
     */
    private static final Set<String> STATUS_OR_CHANGE = changeWords(List.of("done finished finish complete completed "
            + "started start starting begun working progress blocked stuck reopen reopened cancel cancelled canceled "
            + "drop dropped remove removed delete deleted skip skipped"));

    private static Set<String> changeWords(Collection<String> lists) {
        Set<String> words = new HashSet<>();
        lists.forEach(list -> words.addAll(List.of(list.split(" "))));
        return Set.copyOf(words);
    }

    /**
     * True when the sentence is nothing but an answer to the message: what is left after its answer words ("yes",
     * "no", "accept", "go ahead"), the words a bare answer is made of ("it", "not now", "let it in", "like you
     * said"), the words of the message's kind ("the deadline warning"), and the words of its suggestion (the
     * suggestion's own description, the tasks, titles and agent it names, the kind of change it makes, "replan", and
     * its goal) is nothing. The message's own free text never counts: a stale-goal message that says "or cancel it"
     * does not make "yes, cancel it" an answer to its suggestion to re-plan. A word that states a status or names a
     * change of another kind than the suggestion's ("done", "started", "cancel" for a suggestion to re-plan), and the
     * past form of a verb the suggestion names ("ok, the spot is reserved" for a suggestion to move "Reserve the
     * spot"), say something else than yes: such a sentence is no bare answer. "Yes", "no, not now", "sure, do it",
     * "yes, replan it like you suggested" and "yes, accept the suggestion for the bake sale" are answers; "ok thanks",
     * "ok, mark T3 as done" (for a suggestion to move T4), "yes, T3 is done" and "sure, add a task to buy napkins" are
     * not.
     */
    static boolean onlyAnswers(String said, Inbox.Message m) {
        String t = withoutAnswerWords(TargetCheck.straightQuotes(said)).toLowerCase(Locale.ROOT);
        t = MESSAGE_WORDS.matcher(KIND_WORDS.get(m.kind()).matcher(t).replaceAll(" ")).replaceAll(" ");
        Set<String> refs = new HashSet<>();
        Set<String> ownChanges = new HashSet<>();
        List<String> texts = new ArrayList<>(Arrays.asList(m.suggestionText(), m.goalText()));
        m.suggestions().forEach(s -> {
            texts.add(s.task());
            texts.add(s.text());
            String change = CHANGE_WORDS.get(s.type());
            texts.add(change);
            if (change != null) {
                ownChanges.addAll(List.of(change.split(" ")));
            }
        });
        for (String text : texts) {
            for (String w : TargetCheck.normalise(text).split(" ")) {
                if (!w.isEmpty()) {
                    refs.add(w);
                    refs.add(TargetCheck.stem(w));
                }
            }
        }
        for (String w : TargetCheck.normalise(t).split(" ")) {
            if (w.isEmpty() || BARE.contains(w)) {
                continue;
            }
            if ((STATUS_OR_CHANGE.contains(w) || ANY_CHANGE_WORD.contains(w)) && !ownChanges.contains(w)) {
                return false; // "yes, T3 is done", "yes, cancel it": another kind of change than the suggestion's
            }
            if (pastForm(w) && !ownChanges.contains(w)) {
                return false; // "ok, the spot is reserved": a status stated, not an answer
            }
            if (!refs.contains(w) && !refs.contains(TargetCheck.stem(w))) {
                return false;
            }
        }
        return true;
    }

    /** True for a past form: a word ending in "-ed" or an irregular one ("sent", "bought", see the conversation). */
    private static boolean pastForm(String w) {
        return w.length() > 3 && w.endsWith("ed") || ConversationService.IRREGULAR_PAST.containsKey(w);
    }

    /** True when the sentence names the message by its id ("accept 3f9a11c2"). */
    static boolean namesMessage(String said, Inbox.Message m) {
        return pointsAt(said, m, x -> false) >= 100;
    }

    /**
     * The sentence with every word that names the message by its id blanked ("accept feedbeef" is "accept"), as
     * {@link #pointsAt} reads an id: what is left must be an answer and nothing else (see {@link #onlyAnswers}).
     */
    static String withoutMessageId(String said, Inbox.Message m) {
        if (said == null) {
            return "";
        }
        Matcher hex = HEX.matcher(TargetCheck.lowerSameLength(said));
        StringBuilder out = new StringBuilder(said);
        while (hex.find()) {
            String h = hex.group();
            if ((h.length() >= 8 || h.chars().anyMatch(Character::isDigit)) && m.id().startsWith(h)) {
                for (int k = hex.start(); k < hex.end() && k < out.length(); k++) {
                    out.setCharAt(k, ' ');
                }
            }
        }
        return out.toString();
    }
}
