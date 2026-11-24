package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.kernel.registry.Embeddings;
import dev.capabilityfabric.protocol.GoalContext;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Two independent resolvers must agree before the system acts on a reference. The interpreter (a language
 * model) names a target goal and quotes the person's words that identify it; this check verifies that the quote
 * really is in what the person said, and then resolves the quote on its own, with embeddings (or word overlap
 * when no embedding model is available), against every goal. If the kernel's own best match is not the model's
 * choice, or the match is weak, or the words the quote leaves out speak for another goal or for a name no goal has,
 * the reference is ungrounded: a change becomes a question, and a read is answered from the kernel's own reading
 * instead.
 */
public final class TargetCheck {

    /** Whether the kernel's own reading agrees with the model's target. */
    public enum Verdict { GROUNDED, UNGROUNDED }

    /** The verdict, and its reason in words for logs and questions ("no goal clearly matches ..."). */
    public record Result(Verdict verdict, String reason) {
        public boolean grounded() {
            return verdict == Verdict.GROUNDED;
        }
    }

    /** Words that refer back instead of naming something. */
    private static final Set<String> REFERRING = Set.of("it", "that", "this", "one", "them", "those", "these", "the",
            "first", "last", "next", "previous", "task", "goal", "same", "thing", "a", "an");

    /**
     * A goal matches by meaning from this cosine similarity up (with an embedding model). Measured with
     * qwen3-embedding:0.6b on real goals: sentences about a goal score 0.62 to 0.91 against it, while sentences
     * about something else score 0.50 to 0.93 against the nearest goal, so meaning alone never decides; the shared
     * words do (see {@link #MIN_SHARE}).
     */
    static final double MIN_COSINE = 0.6;
    /**
     * A goal matches only when it holds this share of the quote's distinctive word weight: "my dentist appointment"
     * shares only "appointment" with a passport goal's task "Schedule an appointment" (half the weight), and "write
     * the first chapter of my thesis" shares "write" and "chapter" with a novel but not "thesis"; neither matches.
     * Measured on real goals, sentences about a goal hold 0.78 to 1.0 of the weight and sentences about something
     * else at most 0.69.
     */
    static final double MIN_SHARE = 0.7;
    /** How much the distinctive-word overlap adds to a goal's score by meaning. */
    static final double DISTINCTIVE_WEIGHT = 0.25;
    /**
     * The chosen goal must score this much above the runner-up; a near tie means the words do not decide. Without
     * embeddings the score is the weighted word overlap alone, so the margin is weighted the same way.
     */
    static final double GOAL_MARGIN = 0.05;
    /**
     * For a read, the model's goal stands when it is the kernel's own top-ranked goal by this margin over the
     * runner-up (with an embedding model) and shares a content word with the sentence, even when the shared words
     * fall short of {@link #MIN_SHARE}. Measured with qwen3-embedding:0.6b, "how is the dental appointment going?"
     * ranks "Book the dentist" first by 0.13, but sentences about a goal nobody has reach margins of 0.17 to 0.22
     * too when they share one word with a goal ("show me the kitchen renovation" against "Renovate the guest
     * bathroom"). So this margin only decides what a read shows, where a wrong goal costs nothing; it never chooses
     * what a change acts on or which inbox message an answer settles.
     */
    static final double READ_MARGIN = 0.1;

    private final Function<String, Optional<float[]>> embed;

    /** {@code embed} turns a text into a vector, or gives empty when no embedding model answers. */
    public TargetCheck(Function<String, Optional<float[]>> embed) {
        this.embed = embed;
    }

    public static TargetCheck using(Embeddings embeddings) {
        return new TargetCheck(embeddings::embed);
    }

    /**
     * @param documents     goal id to texts describing it: the goal text, then each task title with the goal text
     * @param recentGoals   goals acted on or shown in the previous turn; "it" and "that one" may only refer to them
     */
    public Result check(String utterance, String evidence, String targetGoalId, Map<String, List<String>> documents,
                        Set<String> recentGoals) {
        return check(utterance, evidence, targetGoalId, documents, recentGoals, null);
    }

    /**
     * {@link #check(String, String, String, Map, Set)} for an action that brings new words of its own: the words of
     * {@code newText} (the title of a task being added) say what is added, not where, so they do not count.
     */
    public Result check(String utterance, String evidence, String targetGoalId, Map<String, List<String>> documents,
                        Set<String> recentGoals, String newText) {
        Result strict = check(withoutNegated(utterance), evidence == null ? null : withoutNegated(evidence), utterance,
                evidence, targetGoalId, documents, recentGoals, newText);
        String kept = utterance == null ? null : withoutExcluded(utterance);
        if (strict.grounded() || kept == null || namesAnyGoal(withoutNegated(utterance), documents)
                || kept.equals(withoutNegated(utterance))) {
            return strict;
        }
        // Only a negated verb names a goal here ("I'm not doing the hiking trip anymore", "I can't pick a venue"):
        // the words are read again with it, still without what they exclude.
        return check(kept, evidence == null ? null : withoutExcluded(evidence), utterance, evidence, targetGoalId,
                documents, recentGoals, newText);
    }

    /**
     * The check on {@code utterance} and {@code evidence} as read (without negated or excluded parts); reasons quote
     * the person's words as said ({@code saidAsIs}, {@code evidenceAsIs}).
     */
    private Result check(String utterance, String evidence, String saidAsIs, String evidenceAsIs, String targetGoalId,
                         Map<String, List<String>> documents, Set<String> recentGoals, String newText) {
        String said = normalise(utterance);
        String quote = normalise(evidence);
        Set<String> ignored = newText == null ? Set.of() : words(newText);
        if (quote.isEmpty() || !containsPhrase(said, quote)
                || !ignored.isEmpty() && ignored.containsAll(words(quote)) && !ignored.containsAll(words(said))) {
            // A missing or invented quote is replaced by the person's own words, and so is a quote that is only the
            // new task's title ("add buy flour"): its words say what is added, the rest of the sentence says where.
            quote = said;
            evidence = saidAsIs;
        } else {
            evidence = evidenceAsIs;
        }
        if (namesId(quote, targetGoalId)) {
            return new Result(Verdict.GROUNDED, "named by id");
        }
        if (Arrays.stream(quote.split(" ")).allMatch(REFERRING::contains)) {
            return recentGoals.size() == 1 && recentGoals.contains(targetGoalId)
                    ? new Result(Verdict.GROUNDED, "refers to the goal just discussed")
                    : new Result(Verdict.UNGROUNDED, "\"" + evidence + "\" could mean several things");
        }
        Ranking ranking = rank(quote, documents, ignored);
        boolean matches = targetGoalId.equals(ranking.best()) && ranking.decides();
        if (matches) {
            Optional<String> against = quote.equals(said) ? Optional.empty()
                    : outsideTheQuote(said, quote, targetGoalId, documents, ignored);
            return against.map(reason -> new Result(Verdict.UNGROUNDED, reason))
                    .orElseGet(() -> new Result(Verdict.GROUNDED, "matches what you said"));
        }
        boolean refersBack = Arrays.stream(said.split(" ")).anyMatch(w -> Set.of("it", "that", "this", "them").contains(w));
        // "it" means the goal just discussed, unless the sentence names words that only another goal uses.
        boolean namesAnother = ranking.best() != null && !ranking.best().equals(targetGoalId)
                && ranking.share(ranking.best()) > 0;
        if (refersBack && !namesAnother && recentGoals.size() == 1 && recentGoals.contains(targetGoalId)) {
            return new Result(Verdict.GROUNDED, "refers to the goal just discussed");
        }
        boolean weak = ranking.share(targetGoalId) < MIN_SHARE
                || ranking.embedded() && ranking.similarity(targetGoalId) < MIN_COSINE;
        return new Result(Verdict.UNGROUNDED, !targetGoalId.equals(ranking.best()) && ranking.decides()
                ? "\"" + evidence + "\" matches another goal better"
                : weak ? "no goal clearly matches \"" + evidence + "\""
                : "\"" + evidence + "\" fits more than one goal equally well");
    }

    /**
     * True when the words name {@code goalId} and no other goal: it holds some of their distinctive words (outside
     * their negated parts, see {@link #withoutNegated}, or, when those parts hold every word that names a goal, outside
     * what the words exclude, see {@link #withoutExcluded}) and no other goal holds any. Such words may fall short of
     * grounding the goal only because of words no goal uses ("T3 of the picnic is almost done", "move the picnic to
     * Monday, I'm busy on Friday"): a change on that goal may be asked about, never applied unasked. Words that another
     * goal holds too, or that the goal does not hold at all, name no goal for sure.
     */
    public boolean namesOnly(String utterance, String goalId, Map<String, List<String>> documents) {
        if (utterance == null || goalId == null) {
            return false;
        }
        String read = namesAnyGoal(withoutNegated(utterance), documents) ? withoutNegated(utterance)
                : withoutExcluded(utterance);
        Set<String> quoteWords = compounds(normalise(read), documents);
        Map<String, Double> shares = distinctiveOverlap(quoteWords, documents);
        return shares.getOrDefault(goalId, 0.0) > 0 && shares.entrySet().stream()
                .noneMatch(e -> !e.getKey().equals(goalId) && e.getValue() > 0);
    }

    /**
     * Why the rest of the sentence speaks against the goal a quote taken from it matches; empty when nothing does. A
     * quote is part of what was said, so the words left out may name something else: the whole sentence may decide
     * for another goal; a word outside the quote may belong to other goals only ("I finished gathering the inventory
     * list for the garage sale" quoted as "gathering the inventory list"); or a content word outside the quote may
     * belong to no goal at all, so the quote may be part of a name nobody has ("move the bike ride to Sunday" quoted
     * as "bike", "move my sister's picnic to the 30th" quoted as "picnic", "the picnic at Anna's").
     */
    private Optional<String> outsideTheQuote(String said, String quote, String targetGoalId,
                                             Map<String, List<String>> documents, Set<String> ignored) {
        Ranking whole = rank(said, documents, ignored);
        if (whole.decides() && !targetGoalId.equals(whole.best())) {
            return Optional.of("what you said as a whole matches another goal better");
        }
        Map<String, Set<String>> docWords = new HashMap<>();
        documents.forEach((id, texts) -> docWords.put(id, withJoined(String.join(" ", texts))));
        Set<String> outside = new HashSet<>(compounds(said, documents));
        outside.removeAll(compounds(quote, documents));
        outside.removeAll(ignored);
        for (String w : outside) {
            boolean target = uses(docWords.getOrDefault(targetGoalId, Set.of()), w);
            boolean other = docWords.entrySet().stream().anyMatch(e -> !e.getKey().equals(targetGoalId)
                    && uses(e.getValue(), w));
            if (other && !target) {
                return Optional.of("\"" + w + "\" in what you said belongs to another goal");
            }
        }
        for (String w : outside) {
            if (!Seasons.STEMS.contains(w) && docWords.values().stream().noneMatch(set -> uses(set, w))) {
                return Optional.of("no goal matches \"" + w + "\" in what you said");
            }
        }
        String[] spoken = (" " + said + " ").split(" ");
        String[] quoted = quote.split(" ");
        for (int i = 0; i + quoted.length <= spoken.length; i++) {
            if (!Arrays.equals(Arrays.copyOfRange(spoken, i, i + quoted.length), quoted)) {
                continue;
            }
            for (int at : new int[]{i - 1, i + quoted.length}) {
                if (at < 0 || at >= spoken.length) {
                    continue;
                }
                for (String w : words(spoken[at])) {
                    if (!Seasons.STEMS.contains(w) && docWords.values().stream().noneMatch(set -> uses(set, w))) {
                        return Optional.of("no goal matches \"" + (at < i ? spoken[at] + " " + quote
                                : quote + " " + spoken[at]) + "\"");
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * True when {@code phrase} stands in {@code text} as whole words: "art" is not in "start the art class" because
     * of "start", but it is because of "art". Both are compared as {@link #normalise} leaves them.
     */
    static boolean containsPhrase(String text, String phrase) {
        String t = normalise(text);
        String p = normalise(phrase);
        return !p.isEmpty() && (" " + t + " ").contains(" " + p + " ");
    }

    /** A goal id or its start as a person types it: hexadecimal, with a digit or at least 8 characters long. */
    private static final Pattern ID_WORDS = Pattern.compile("(?=[0-9a-f ]*[0-9])[0-9a-f ]{4,}|[0-9a-f ]{8,}");

    /**
     * True when the quote is the start of the goal's id ("3f9a11c2", "3f9a"): only hexadecimal words, together at
     * least 4 characters and holding a digit, or at least 8 characters. Ordinary words that happen to be
     * hexadecimal ("dead", "cafe", "added") name no id.
     */
    static boolean namesId(String quote, String goalId) {
        String q = normalise(quote);
        if (!ID_WORDS.matcher(q).matches()) {
            return false;
        }
        String compact = q.replace(" ", "");
        return compact.length() >= 4 && goalId.replace("-", "").toLowerCase(Locale.ROOT).startsWith(compact);
    }

    /**
     * The goals ranked for a quote: each goal's best cosine similarity to the quote over its texts (0 without an
     * embedding model) and its share of the quote's distinctive words, scored as similarity plus
     * {@link #DISTINCTIVE_WEIGHT} times the share.
     */
    record Ranking(String best, double bestScore, double runnerUp, boolean embedded, Map<String, Double> similarities,
                   Map<String, Double> shares) {

        double similarity(String goalId) {
            return similarities.getOrDefault(goalId, 0.0);
        }

        double share(String goalId) {
            return shares.getOrDefault(goalId, 0.0);
        }

        /**
         * True when the best goal is the kernel's own clear answer: it shares content words with the quote, holding
         * at least {@link #MIN_SHARE} of their distinctive weight, and it is close enough in meaning
         * ({@link #MIN_COSINE}, with an embedding model); then either it is the one goal that holds every distinctive
         * word of the quote (see {@link #whole}: "QA r11 repaint the garden fence" decides for that goal over "QA
         * repaint the garden fence before Christmas", which lacks "r11"), or no other goal holds {@link #MIN_SHARE} of
         * that weight too (then the words do not decide, and meaning never does: "garage sale" with two goals about a
         * garage sale) and it scores clearly above the runner-up. A single candidate is compared with a score of 0.
         */
        boolean decides() {
            if (best == null || share(best) < MIN_SHARE || embedded && similarity(best) < MIN_COSINE) {
                return false;
            }
            if (whole(best)) {
                return true;
            }
            if (shares.entrySet().stream().anyMatch(e -> !e.getKey().equals(best) && e.getValue() >= MIN_SHARE)) {
                return false;
            }
            double margin = embedded ? GOAL_MARGIN : GOAL_MARGIN * DISTINCTIVE_WEIGHT;
            return bestScore - runnerUp >= margin;
        }

        /** True when {@code goalId} holds every distinctive word of the quote and no other goal does. */
        boolean whole(String goalId) {
            return goalId != null && share(goalId) >= WHOLE_SHARE && shares.entrySet().stream()
                    .noneMatch(e -> !e.getKey().equals(goalId) && e.getValue() >= WHOLE_SHARE);
        }
    }

    /** The share of the quote's distinctive weight that counts as all of it (rounding aside). */
    private static final double WHOLE_SHARE = 1 - 1e-9;

    /** Ranks every goal for a quote; see {@link Ranking}. */
    Ranking rank(String quote, Map<String, List<String>> documents) {
        return rank(quote, documents, Set.of());
    }

    /**
     * Ranks every goal for a quote whose words in {@code ignored} do not count for the shared words. Two words of the
     * quote that a goal writes as one ("tree house" for "treehouse") count as that one word; a season or holiday that
     * no goal uses does not count (see {@link Seasons}).
     */
    Ranking rank(String quote, Map<String, List<String>> documents, Set<String> ignored) {
        Set<String> quoteWords = new HashSet<>(compounds(quote, documents));
        quoteWords.removeAll(ignored);
        quoteWords.removeIf(w -> Seasons.STEMS.contains(w) && documents.values().stream()
                .noneMatch(texts -> texts.stream().anyMatch(text -> uses(words(text), w))));
        Map<String, Double> shares = distinctiveOverlap(quoteWords, documents);
        Optional<float[]> quoteVector = embed.apply(quote);
        Map<String, Double> similarities = new HashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> doc : documents.entrySet()) {
            double similarity = 0;
            if (quoteVector.isPresent()) {
                for (String text : doc.getValue()) {
                    Optional<float[]> v = embed.apply(text);
                    if (v.isPresent()) {
                        similarity = Math.max(similarity, Embeddings.cosine(quoteVector.get(), v.get()));
                    }
                }
            }
            similarities.put(doc.getKey(), similarity);
            // Words that only this goal uses ("hiking", "portfolio") separate goals that share generic tasks.
            scores.put(doc.getKey(), similarity + DISTINCTIVE_WEIGHT * shares.getOrDefault(doc.getKey(), 0.0));
        }
        // The one goal that holds every distinctive word of the quote leads, whatever its meaning scores; otherwise
        // the goal with the highest score.
        List<String> whole = shares.entrySet().stream().filter(e -> e.getValue() >= WHOLE_SHARE)
                .map(Map.Entry::getKey).toList();
        String best = null;
        double bestScore = -1;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (whole.size() == 1 ? e.getKey().equals(whole.getFirst()) : e.getValue() > bestScore) {
                best = e.getKey();
                bestScore = e.getValue();
            }
        }
        double runnerUp = 0;
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            if (!e.getKey().equals(best)) {
                runnerUp = Math.max(runnerUp, e.getValue());
            }
        }
        return new Ranking(best, bestScore, runnerUp, quoteVector.isPresent(), similarities, shares);
    }

    /**
     * The content words of a quote (see {@link #words}), with two neighbouring words joined when a goal's texts use
     * them as one word ("tree house" is "treehouse" when a goal is about a treehouse), and the quote's words of letters
     * then digits ("r11", see {@link #labels}).
     */
    static Set<String> compounds(String quote, Map<String, List<String>> documents) {
        String[] t = normalise(quote).split(" ");
        Set<String> known = new HashSet<>();
        documents.values().forEach(texts -> texts.forEach(text -> known.addAll(words(text))));
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < t.length; i++) {
            if (i + 1 < t.length && t[i].length() >= 3 && t[i + 1].length() >= 3
                    && known.contains(stem(t[i] + t[i + 1]))) {
                joined.append(t[i]).append(t[i + 1]).append(' ');
                i++;
            } else {
                joined.append(t[i]).append(' ');
            }
        }
        Set<String> all = new HashSet<>(words(joined.toString()));
        all.addAll(labels(quote));
        return all;
    }

    /**
     * A word of letters then digits that names a goal ("r11" of "QA r11 repaint the fence", "pm7", "ai8"): neither a
     * task key ("T3") nor a word of hexadecimal letters and digits only (an id such as "3f9a11c2" or "aaaa1111").
     */
    private static final Pattern LABEL_WORD = Pattern.compile(
            "\\b(?!t\\d{1,3}\\b)(?![0-9a-f]+\\b)[a-z]+\\d+[a-z0-9]*\\b");

    /**
     * The words of letters then digits a text names its goal by (see {@link #LABEL_WORD}): {@link #words} leaves out
     * words with digits, yet "r11" is what tells "QA r11 repaint the garden fence" from an older goal with the same
     * other words.
     */
    static Set<String> labels(String text) {
        Set<String> found = new HashSet<>();
        Matcher m = LABEL_WORD.matcher(normalise(text));
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    /**
     * The kernel's own top-ranked goal for what was said when it stands out by {@link #READ_MARGIN} in meaning (an
     * embedding model is needed) and shares at least one content word with it; empty otherwise. Used where a wrong
     * goal costs nothing: to show a goal, to keep its lines in the interpreter's snapshot, or to ask whether an
     * answer is about the inbox message of that goal.
     */
    public Optional<String> likely(String utterance, Map<String, List<String>> documents) {
        Ranking ranking = rank(normalise(withoutNegated(utterance)), documents);
        return ranking.best() != null && ranking.embedded() && ranking.similarity(ranking.best()) >= MIN_COSINE
                && ranking.share(ranking.best()) > 0 && ranking.bestScore() - ranking.runnerUp() >= READ_MARGIN
                ? Optional.of(ranking.best()) : Optional.empty();
    }

    /**
     * True when a read may show {@code goalId}: the strict check grounds it (see {@link #check}), or it is the
     * kernel's own top-ranked goal by a clear margin (see {@link #likely}).
     */
    public boolean readable(String utterance, String evidence, String goalId, Map<String, List<String>> documents,
                            Set<String> recentGoals) {
        if (check(utterance, evidence, goalId, documents, recentGoals).grounded()) {
            return true;
        }
        String quote = evidence != null && containsPhrase(utterance, evidence) ? evidence : utterance;
        return likely(quote, documents).filter(goalId::equals).isPresent()
                || likely(utterance, documents).filter(goalId::equals).isPresent();
    }

    /**
     * The one goal that a word the sentence names a goal by belongs to ("attic" in "is my attic goal on track?" when
     * only one goal mentions an attic). Empty when the sentence names no goal, when no such word belongs to exactly
     * one goal, or when its words point at different goals.
     */
    public static Optional<String> distinctiveMatch(String said, Map<String, List<String>> documents) {
        List<String> name = nameWords(said);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Set<String>> docWords = new HashMap<>();
        documents.forEach((id, texts) -> docWords.put(id, withJoined(String.join(" ", texts))));
        Set<String> found = new HashSet<>();
        for (String word : compounds(String.join(" ", name), documents)) {
            List<String> users = docWords.entrySet().stream().filter(e -> uses(e.getValue(), word))
                    .map(Map.Entry::getKey).toList();
            if (users.size() == 1) {
                found.add(users.getFirst());
            }
        }
        return found.size() == 1 ? Optional.of(found.iterator().next()) : Optional.empty();
    }

    /**
     * True when the sentence and one of the texts share a content word (see {@link #words}): "mark T2 of the picnic
     * done" and "Plan a picnic in the park" share "picnic", "what should I do today?" shares nothing with any goal.
     */
    public static boolean sharesWord(String said, List<String> texts) {
        Set<String> spoken = words(said);
        if (spoken.isEmpty() || texts == null) {
            return false;
        }
        for (String text : texts) {
            for (String w : words(text)) {
                if (spoken.contains(w)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Above this similarity, with a clear margin over the next task, the meaning alone identifies a task. */
    static final double TASK_COSINE = 0.70;
    static final double TASK_MARGIN = 0.08;

    /**
     * Whether what the person said points at this task rather than another task of the same goal. Words the goal
     * text uses ("picnic") do not count: they do not tell its tasks apart. A task is grounded when it shares the most
     * distinctive words with the sentence, or, when no task shares any, when its meaning is clearly the closest.
     */
    public boolean taskGrounded(String utterance, String evidence, String goalText, List<GoalContext.TaskView> tasks,
                                GoalContext.TaskView chosen) {
        utterance = withoutNegated(utterance);
        evidence = evidence == null ? null : withoutNegated(evidence);
        Set<String> goalWords = stems(goalText);
        Set<String> said = stems(utterance + " " + (evidence == null ? "" : evidence));
        said.removeAll(goalWords);
        int chosenOverlap = overlap(said, stems(chosen.title()), goalWords);
        int bestOther = 0;
        for (GoalContext.TaskView t : tasks) {
            if (!t.id().equals(chosen.id())) {
                bestOther = Math.max(bestOther, overlap(said, stems(t.title()), goalWords));
            }
        }
        if (chosenOverlap > 0) {
            return chosenOverlap > bestOther; // a tie with another task does not tell them apart
        }
        if (bestOther > 0) {
            return false; // the words point at another task
        }
        Optional<float[]> v = embed.apply(utterance);
        if (v.isEmpty()) {
            return false;
        }
        double chosenScore = -1;
        double second = -1;
        for (GoalContext.TaskView t : tasks) {
            Optional<float[]> tv = embed.apply(t.title());
            if (tv.isEmpty()) {
                continue;
            }
            double score = Embeddings.cosine(v.get(), tv.get());
            if (t.id().equals(chosen.id())) {
                chosenScore = score;
            } else {
                second = Math.max(second, score);
            }
        }
        return chosenScore >= TASK_COSINE && chosenScore - second >= TASK_MARGIN;
    }

    /**
     * The one other task the words point at when they do not point at the chosen one: the task sharing strictly the
     * most distinctive words with the sentence, more than the chosen task does. Empty when no task stands out.
     */
    public Optional<GoalContext.TaskView> likelierTask(String utterance, String evidence, String goalText,
                                                       List<GoalContext.TaskView> tasks, GoalContext.TaskView chosen) {
        utterance = withoutNegated(utterance);
        evidence = evidence == null ? null : withoutNegated(evidence);
        Set<String> goalWords = stems(goalText);
        Set<String> said = stems(utterance + " " + (evidence == null ? "" : evidence));
        said.removeAll(goalWords);
        int chosenOverlap = overlap(said, stems(chosen.title()), goalWords);
        GoalContext.TaskView best = null;
        int bestOverlap = 0;
        int runnerUp = 0;
        for (GoalContext.TaskView t : tasks) {
            if (t.id().equals(chosen.id())) {
                continue;
            }
            int n = overlap(said, stems(t.title()), goalWords);
            if (n > bestOverlap) {
                runnerUp = bestOverlap;
                bestOverlap = n;
                best = t;
            } else if (n > runnerUp) {
                runnerUp = n;
            }
        }
        return best != null && bestOverlap > chosenOverlap && bestOverlap > runnerUp ? Optional.of(best)
                : Optional.empty();
    }

    /**
     * The words of a new text (a task title, a goal) that the person did not say, compared as rough stems ("Pack
     * the picnic items" was said as "add packing for the picnic items"). Empty when every word was said.
     */
    public static List<String> unsaid(String text, String said) {
        Set<String> spoken = stems(said);
        List<String> missing = new ArrayList<>();
        for (String w : stems(text)) {
            if (spoken.stream().noneMatch(s -> similarWords(w, s))) {
                missing.add(w);
            }
        }
        return missing;
    }

    private static int overlap(Set<String> said, Set<String> title, Set<String> goalWords) {
        int n = 0;
        for (String t : title) {
            if (goalWords.contains(t)) {
                continue;
            }
            for (String s : said) {
                if (similarWords(s, t)) {
                    n++;
                    break;
                }
            }
        }
        return n;
    }

    /**
     * Two words that are the same word: their stems are equal ("invites" and "invitations", "pack" and "packing",
     * "bake" and "baking"). Words that only begin alike are different words ("repair" and "repaint", "camping" and
     * "campsite", "interview" and "internet").
     */
    static boolean similarWords(String a, String b) {
        return stem(a).equals(stem(b));
    }

    /**
     * The content words of a text as stems ("packing" is "pack", "invites" is "invit", see {@link #stem}); stop words
     * and words shorter than three letters are left out.
     */
    static Set<String> stems(String text) {
        Set<String> words = new HashSet<>();
        for (String w : normalise(text).split(" ")) {
            if (w.length() < 3 || STOP.contains(w)) {
                continue;
            }
            words.add(stem(w));
        }
        return words;
    }

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "will", "take",
            "takes", "took", "hour", "hours", "minute", "minutes", "done", "finished", "task", "move", "moved", "make",
            "about", "from", "into", "have", "has", "had", "was", "are", "can", "need", "please", "today", "tomorrow",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "week", "day", "days",
            "mark", "set", "drop", "remove", "estimate", "due", "now", "just", "also", "all", "it's", "its");

    /**
     * The kernel's own resolution of a sentence to a goal, used when the interpreter named none: returns a goal only
     * when it clearly stands out by the same strict test that grounds the interpreter's choice (see
     * {@link Ranking#decides}).
     */
    public Optional<String> clearMatch(String utterance, Map<String, List<String>> documents) {
        Ranking ranking = rank(normalise(withoutNegated(utterance)), documents);
        return ranking.decides() ? Optional.of(ranking.best()) : Optional.empty();
    }

    /**
     * A word or phrase that negates what follows: not, never, no, any "n't" word (also typed without the apostrophe),
     * cannot, can not, unable to. "No later than" and "not later than" set a deadline, "no problem" agrees, and "don't
     * forget" or "don't hesitate" asks for something, so none of them negates.
     */
    static final String NEGATION = "(?:\\b(?:not|never|no)\\b(?!\\s+(?:later|earlier|sooner|more|less)\\s+than\\b)"
            + "(?!\\s+(?:problem|worries)\\b)|\\b[a-z]+n't\\b|\\b(?:dont|cant|wont|didnt|doesnt|isnt|wasnt|arent|shouldnt"
            + "|couldnt|wouldnt|havent|hasnt)\\b|\\bcannot\\b|\\bcan not\\b|\\bunable to\\b)"
            + "(?!\\s+(?:forget|hesitate)\\b)";
    /** A negation or an exclusion ("except", "instead of", "rather than", "other than", "apart from", ...). */
    static final Pattern NEGATED = Pattern.compile("(?i)(?:" + NEGATION
            + "|\\bexcept(?:\\s+for)?\\b|\\bother than\\b|\\bapart from\\b|\\bexcluding\\b|\\bbesides\\b"
            + "|\\binstead of\\b|\\brather than\\b)");
    private static final Pattern EXCLUDED = Pattern.compile(
            NEGATED.pattern() + "[^,;:.!?]*?(?=\\s+and\\s+|\\s+but\\s+|[,;:.!?]|$)");

    /**
     * The sentence without what it excludes, so excluded things never count as the target: "mark T4 done, not T3"
     * keeps T4; "everything except the venue" drops "the venue"; "I did not finish T3" drops T3. An exclusion runs
     * to the end of its clause (a comma, a colon, "and" or "but"). Curly apostrophes count as straight ones.
     */
    public static String withoutNegated(String said) {
        return said == null ? null : EXCLUDED.matcher(straightQuotes(said)).replaceAll(" ");
    }

    /**
     * An exclusion of a thing: "except", "other than", "instead of" and the like, or a negation right before what it
     * excludes ("not the venue", "not T3", "not my picnic"). A negated verb ("not doing", "can't pick") is not one.
     */
    private static final Pattern THING_EXCLUDED = Pattern.compile("(?i)(?:\\bexcept(?:\\s+for)?\\b|\\bother than\\b"
            + "|\\bapart from\\b|\\bexcluding\\b|\\bbesides\\b|\\binstead of\\b|\\brather than\\b"
            + "|\\b(?:not|never|no)\\s+(?=(?:the|my|our|your|this|that|these|those|a|an|any|t\\d+)\\b))"
            + "[^,;:.!?]*?(?=\\s+and\\s+|\\s+but\\s+|[,;:.!?]|$)");

    /**
     * The sentence without the things it excludes ("everything except the venue" drops "the venue", "T4, not T3" drops
     * T3), keeping what a negated verb is about: "I'm not doing the hiking trip anymore" and "I can't pick a venue
     * until Friday" still name the trip and the venue. Read only when {@link #withoutNegated} leaves no word that
     * names a goal.
     */
    static String withoutExcluded(String said) {
        return said == null ? null : THING_EXCLUDED.matcher(straightQuotes(said)).replaceAll(" ");
    }

    /** True when the words hold a distinctive word of some goal. */
    static boolean namesAnyGoal(String words, Map<String, List<String>> documents) {
        if (words == null || documents.isEmpty()) {
            return false;
        }
        return distinctiveOverlap(compounds(normalise(words), documents), documents).values().stream()
                .anyMatch(share -> share > 0);
    }

    /**
     * Lower case letter by letter, so every index into the result is the same index into {@code s}: a match found
     * in the lower-case copy can be cut out of the original. {@link String#toLowerCase} can change the length (the
     * Turkish dotted capital I becomes two characters).
     */
    static String lowerSameLength(String s) {
        if (s == null) {
            return null;
        }
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = Character.toLowerCase(chars[i]);
        }
        return new String(chars);
    }

    /** Curly single quotes as the straight apostrophe, so "don\u2019t" reads like "don't". */
    public static String straightQuotes(String s) {
        return s == null ? null : s.replace('\u2018', '\'').replace('\u2019', '\'');
    }

    /** Words that name what a goal is called: "the lighthouse goal", "my tax project". */
    private static final Set<String> HEADS = Set.of("goal", "goals", "project", "projects", "plan", "plans");
    /** Words that end a goal's name when reading back from its head word. */
    private static final Set<String> BOUNDARY = Set.of("the", "my", "our", "your", "a", "an", "of", "for", "on", "in",
            "about", "show", "me", "to", "with", "and", "or", "is", "are", "was", "how", "what", "which", "whats",
            "this", "that", "these", "those", "all", "any", "many", "much", "called", "named", "open", "see", "view");
    /** Words that describe goals in general rather than name one. */
    private static final Set<String> GENERIC = Set.of("overdue", "late", "open", "closed", "done", "finished",
            "completed", "cancelled", "canceled", "active", "current", "new", "old", "other", "next", "last", "first",
            "latest", "recent", "big", "small", "main", "important", "urgent", "upcoming", "due", "every", "each",
            "some", "status", "progress", "details", "whole", "entire", "same", "it", "its", "them", "tasks", "agenda",
            "list", "things", "everything", "todo", "todos", "inbox", "rules", "profile", "messages", "capabilities",
            "approvals", "decisions", "history", "please");

    /**
     * The words a sentence uses as the name of a goal, read from its shape: the words before "goal", "project" or
     * "plan" ("show me the lighthouse goal"), the words after "goal called" or "goal named", the words between
     * "how is the" and "going"; a request to show a goal names it by every word after "show me" (see
     * {@link #shownName}). Words that only describe goals ("overdue", "open") are left out. Empty when the
     * sentence names no goal ("how many goals are overdue?").
     */
    public static List<String> nameWords(String said) {
        return nameWords(said, true);
    }

    /**
     * The words a sentence uses as the name of a goal (see {@link #nameWords(String)}) read from the whole sentence,
     * negated parts included: a rule says what it forbids in a negated clause ("never let agents change my garden
     * fence goal" names "garden fence").
     */
    public static List<String> nameWordsAsSaid(String said) {
        return nameWords(said, false);
    }

    private static List<String> nameWords(String said, boolean withoutNegation) {
        String[] t = normalise(withoutNegation ? withoutNegated(said) : said).split(" ");
        List<String> shown = shownName(t);
        if (!shown.isEmpty()) {
            return shown;
        }
        List<String> words = new ArrayList<>();
        for (int i = 0; i < t.length; i++) {
            if (HEADS.contains(t[i])) {
                int j = i - 1;
                while (j >= 0 && i - j <= 4 && !BOUNDARY.contains(t[j]) && !HEADS.contains(t[j])) {
                    j--;
                }
                for (int k = j + 1; k < i; k++) {
                    words.add(t[k]);
                }
                if (i + 2 < t.length && (t[i + 1].equals("called") || t[i + 1].equals("named"))) {
                    for (int k = i + 2; k < t.length && k <= i + 5 && !BOUNDARY.contains(t[k]); k++) {
                        words.add(t[k]);
                    }
                }
            }
        }
        for (int i = 0; i + 4 < t.length && words.isEmpty(); i++) {
            // "how is the lighthouse going", "how's my move coming along"
            if (t[i].equals("how") && Set.of("is", "s", "are").contains(t[i + 1])
                    && Set.of("the", "my", "our").contains(t[i + 2])) {
                for (int k = i + 3; k < t.length && k <= i + 6 && !Set.of("going", "doing", "coming").contains(t[k]);
                     k++) {
                    words.add(t[k]);
                }
            }
        }
        for (int i = 0; i + 1 < t.length && words.isEmpty(); i++) {
            // "show me the lighthouse", "open my tax return"
            if (Set.of("show", "open", "display").contains(t[i])) {
                int k = t[i + 1].equals("me") ? i + 2 : i + 1;
                if (k < t.length && Set.of("the", "my", "our").contains(t[k])) {
                    for (k++; k < t.length && words.size() < 4 && !BOUNDARY.contains(t[k]); k++) {
                        words.add(t[k]);
                    }
                }
            }
        }
        words.removeIf(w -> w.length() < 3 || HEADS.contains(w) || GENERIC.contains(w) || STOP.contains(w)
                || w.chars().allMatch(Character::isDigit));
        return words;
    }

    /** Words that start a question or a list after "show me" rather than a goal's name: "show me what is due". */
    private static final Set<String> NOT_A_NAME_START = Set.of("what", "whats", "which", "how", "hows", "when",
            "where", "who", "why", "all", "any", "everything", "anything", "something", "every", "each", "some");
    /** The most words read as a goal's name after "show me". */
    private static final int MAX_SHOWN_NAME = 10;

    /**
     * The name a request to show a goal gives it, read whole: every word after "show", "open" or "display" (and
     * "me", "the", "my" or "our") up to "goal", "project", "plan" or the end, at most {@link #MAX_SHOWN_NAME} words,
     * so "show me QA r11 repaint the garden fence" and "show me the QA pm11 water the balcony plants goal" name the
     * goal by all of its words. Words that only describe goals, function words and task keys are left out. Empty when
     * the sentence asks to show nothing by name ("show me what is due today", "show me my overdue goals").
     */
    private static List<String> shownName(String[] t) {
        for (int i = 0; i + 1 < t.length; i++) {
            if (!Set.of("show", "open", "display").contains(t[i])) {
                continue;
            }
            int k = t[i + 1].equals("me") ? i + 2 : i + 1;
            if (k < t.length && Set.of("the", "my", "our").contains(t[k])) {
                k++;
            }
            if (k >= t.length || NOT_A_NAME_START.contains(t[k])) {
                return List.of();
            }
            List<String> words = new ArrayList<>();
            for (int n = 0; k < t.length && n < MAX_SHOWN_NAME && !HEADS.contains(t[k]); k++, n++) {
                words.add(t[k]);
            }
            words.removeIf(w -> w.length() < 3 && !LABEL_WORD.matcher(w).matches() || HEADS.contains(w)
                    || GENERIC.contains(w) || STOP.contains(w) || FILLER.contains(w) || BOUNDARY.contains(w)
                    || w.matches("t\\d{1,3}") || w.chars().allMatch(Character::isDigit));
            return words;
        }
        return List.of();
    }

    /**
     * The name a sentence gives a goal when no goal uses any of its words ("lighthouse" when no goal or task
     * mentions a lighthouse). Empty when the sentence names no goal, or names one the words can find.
     */
    public static Optional<String> unknownName(String said, Map<String, List<String>> documents) {
        List<String> name = nameWords(said);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        Set<String> known = new HashSet<>();
        documents.values().forEach(texts -> texts.forEach(text -> known.addAll(stems(text))));
        for (String word : stems(String.join(" ", name))) {
            if (known.stream().anyMatch(k -> similarWords(word, k))) {
                return Optional.empty();
            }
        }
        return Optional.of(String.join(" ", name));
    }

    /**
     * Up to {@code limit} goals whose texts come closest to the words, best first: by meaning with embeddings,
     * otherwise by word overlap. Goals that share nothing with the words are left out.
     */
    public List<String> closest(String words, Map<String, List<String>> documents, int limit) {
        Optional<float[]> v = embed.apply(words);
        record Scored(String id, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Map.Entry<String, List<String>> doc : documents.entrySet()) {
            double score = 0;
            for (String text : doc.getValue()) {
                double s = v.flatMap(x -> embed.apply(text).map(y -> Embeddings.cosine(x, y)))
                        .orElseGet(() -> Embeddings.lexicalSimilarity(words, text));
                score = Math.max(score, s);
            }
            if (score > (v.isPresent() ? CLOSE_COSINE : 0)) {
                scored.add(new Scored(doc.getKey(), score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        return scored.stream().limit(limit).map(Scored::id).toList();
    }

    /** With embeddings, a goal counts as close to a name from this cosine similarity up. */
    static final double CLOSE_COSINE = 0.3;

    /**
     * Share of the quote's content word weight found in each goal (see {@link #words}). A word's weight is higher
     * the fewer goals use it (inverse document frequency); a word no goal uses weighs as much as a word only one goal
     * uses, so "dentist appointment" is only half found in a goal that mentions an appointment. Words match by
     * {@link #similarWords} ("booked" is "book").
     */
    static Map<String, Double> distinctiveOverlap(String quote, Map<String, List<String>> documents) {
        return distinctiveOverlap(words(quote), documents);
    }

    private static Map<String, Double> distinctiveOverlap(Set<String> quoteWords, Map<String, List<String>> documents) {
        Map<String, Set<String>> docWords = new HashMap<>();
        documents.forEach((id, texts) -> docWords.put(id, withJoined(String.join(" ", texts))));
        int n = Math.max(1, documents.size());
        double unknown = Math.log(1.0 + n);
        Map<String, Set<String>> usedBy = new HashMap<>();
        Map<String, Double> weight = new HashMap<>();
        double total = 0;
        for (String w : quoteWords) {
            Set<String> goals = new HashSet<>();
            docWords.forEach((id, set) -> {
                if (uses(set, w)) {
                    goals.add(id);
                }
            });
            double idf = goals.isEmpty() ? unknown : Math.log(1.0 + n / (double) goals.size());
            usedBy.put(w, goals);
            weight.put(w, idf);
            total += idf;
        }
        Map<String, Double> overlap = new HashMap<>();
        for (String id : docWords.keySet()) {
            double sum = 0;
            for (String w : quoteWords) {
                if (usedBy.get(w).contains(id)) {
                    sum += weight.get(w);
                }
            }
            overlap.put(id, total == 0 ? 0 : sum / total);
        }
        return overlap;
    }

    /**
     * The content words of a text plus each two neighbouring words joined ("tree house" also gives "treehouse") and its
     * words of letters then digits ("r11", see {@link #labels}).
     */
    private static Set<String> withJoined(String text) {
        Set<String> all = new HashSet<>(words(text));
        all.addAll(labels(text));
        String[] t = normalise(text).split(" ");
        for (int i = 0; i + 1 < t.length; i++) {
            if (t[i].length() >= 3 && t[i + 1].length() >= 3 && !STOP.contains(t[i]) && !STOP.contains(t[i + 1])) {
                all.add(stem(t[i] + t[i + 1]));
            }
        }
        return all;
    }

    private static boolean uses(Set<String> words, String word) {
        if (words.contains(word)) {
            return true;
        }
        for (String w : words) {
            if (similarWords(word, w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Words that never name a goal: function and question words, command verbs, words of a task's state ("stuck",
     * "blocked"), times, amounts and fillers.
     */
    private static final Set<String> FILLER = Set.of("you", "your", "yours", "our", "ours", "their", "they", "she",
            "her", "his", "him", "mine", "myself", "not", "but", "yet", "any", "than", "then", "there", "here", "when",
            "what", "whats", "which", "who", "whom", "whose", "where", "why", "how", "hows", "let", "lets", "get",
            "got", "gets", "did", "does", "doing", "dont", "don", "isn", "aren", "wasn", "didn", "doesn", "can",
            "cant", "won", "wont", "could", "would", "should", "shall", "may", "might", "must", "very", "really",
            "more", "less", "much", "many", "too", "only", "still", "again", "off", "out", "over", "under", "after",
            "before", "until", "till", "since", "because", "while", "both", "another", "such", "own", "been", "being",
            "were", "yes", "yeah", "okay", "sure", "thanks", "thank", "want", "wants", "like", "think", "know", "say",
            "said", "tell", "told", "going", "coming", "along", "left", "anymore", "already", "maybe", "now",
            "moving", "push", "pushed", "pushing", "back", "forward", "postpone", "postponed", "delay", "delayed",
            "reschedule", "rescheduled", "shift", "bring", "cancel", "cancelled", "canceled", "dropped", "removed",
            "delete", "deleted", "marked", "add", "added", "show", "reopen", "close", "finish", "complete", "start",
            "started", "change", "changed", "update", "updated", "put", "made", "give", "yesterday", "tonight",
            "weekend", "weeks", "month", "months", "year", "years", "morning", "evening", "night", "january",
            "february", "march", "april", "june", "july", "august", "september", "october", "november", "december",
            "deadline", "date", "dates", "time", "later", "earlier", "sooner", "soon", "one", "two", "three", "four",
            "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "twenty", "thirty", "half", "couple",
            "few", "step", "steps", "item", "items", "thing", "things", "everything", "something", "anything",
            "bought", "picked", "chose", "chosen", "sent", "wrote", "written", "took", "taken", "paid", "gave",
            "given", "went", "gone", "came", "gotten", "ready", "finally", "work", "worked",
            "working", "removing", "dropping", "cancelling", "canceling", "marking", "adding", "finishing",
            "completing", "moved", "arrived", "received", "happened", "sorted", "handled", "dealt", "managed",
            "tried", "remind", "help", "instead", "extend", "extends", "extended", "extending", "need", "needs",
            "needed", "end", "ends", "hold", "holds", "slip", "slips", "slipped", "slipping", "wait", "waits", "waiting",
            "waited", "blocked", "stuck", "halted", "ongoing", "underway", "progressing", "continue");
    /**
     * Seasons and holidays: they say when, and name a goal only when a goal uses them ("the summer party"), so a
     * quote's season that no goal uses does not count against the goal it names ("repaint the fence in spring").
     */
    private static final class Seasons {
        /** The stems, made once the stemmer's own tables exist. */
        static final Set<String> STEMS = Stream.of("spring", "summer", "autumn", "fall", "winter",
                        "christmas", "xmas", "easter", "halloween", "thanksgiving", "holiday", "holidays")
                .map(TargetCheck::stem).collect(Collectors.toUnmodifiableSet());
    }
    /** Two-letter words that never name anything; other two-letter words ("TV", "PC") are content words. */
    private static final Set<String> SHORT_FILLER = Set.of("to", "on", "in", "at", "of", "is", "it", "my", "me", "we",
            "us", "an", "or", "do", "go", "up", "so", "no", "be", "by", "as", "if", "am", "oh", "ok", "hi", "he", "im",
            "id", "ve", "ll", "re", "st", "nd", "rd", "th", "vs");

    /**
     * The content words of a text, the ones that can name a goal: lower case, as rough stems (see {@link #stem});
     * words that refer back, describe goals in general, stop words, command verbs, times, words with digits (task
     * keys, amounts such as "1h30") and short words other than names such as "TV" are left out ("move my dentist
     * appointment to Friday" is "dentist" and "appointment").
     */
    static Set<String> words(String text) {
        return Arrays.stream(normalise(text).split(" "))
                .filter(w -> (w.length() > 2 || w.length() == 2 && !SHORT_FILLER.contains(w)) && !REFERRING.contains(w)
                        && !STOP.contains(w) && !GENERIC.contains(w) && !HEADS.contains(w) && !FILLER.contains(w)
                        && w.chars().noneMatch(Character::isDigit))
                .map(TargetCheck::stem)
                .collect(Collectors.toSet());
    }

    /**
     * A word's stem, by suffix stripping in the manner of the Porter stemmer's first steps, so the forms of one word
     * meet and different words stay apart: plurals ("tiles" is "tile", "glasses" is "glass", "parties" and "party" are
     * "parti", "cookies" and "cookie" are "cooki"), then "-ied", "-ed" and "-ing" with the letter they dropped put back
     * ("tidied" and "tidy" are "tidi", "baking" is "bake", "planned" is "plan", "packing" is "pack"), a doubled final
     * "l" of a longer word made single ("travelled" and "cancelled" are "travel" and "cancel"), a final "y" after a
     * consonant as "i", then "-ation", "-ment", "-ate" or "-al" after at least two syllables ("invitations" is
     * "invit", "renovation" and "renovate" are "renov", "renewal" is "renew", "appointment" is "appoint"), then a
     * silent final "e" of a longer word ("invite" is "invit", "remove" is "remov"; "bake" keeps it). Words of up to
     * three letters stay as they are.
     */
    static String stem(String w) {
        if (w.length() <= 3) {
            return w;
        }
        String s = w;
        if (s.endsWith("sses")) {
            s = s.substring(0, s.length() - 2);
        } else if (s.endsWith("ies")) {
            s = s.substring(0, s.length() - (s.length() > 4 ? 2 : 1)); // "parties" is "parti", "ties" is "tie"
        } else if (s.endsWith("s") && !s.endsWith("ss") && !s.endsWith("us") && !s.endsWith("is")) {
            s = s.substring(0, s.length() - 1);
        }
        String base = null;
        if (s.endsWith("ied")) {
            s = s.substring(0, s.length() - (s.length() > 4 ? 2 : 1)); // "tidied" is "tidi", "tied" is "tie"
        } else if (s.endsWith("eed")) {
            if (measure(s.substring(0, s.length() - 3)) > 0) {
                s = s.substring(0, s.length() - 1); // "agreed" is "agree"; "need" stays
            }
        } else if (s.endsWith("ed") && hasVowel(s, s.length() - 2)) {
            base = s.substring(0, s.length() - 2);
        } else if (s.endsWith("ing") && hasVowel(s, s.length() - 3)) {
            base = s.substring(0, s.length() - 3);
        }
        if (base != null) {
            int n = base.length();
            if (base.endsWith("at") || base.endsWith("bl") || base.endsWith("iz")) {
                s = base + "e"; // "dating" is "date"
            } else if (n >= 2 && base.charAt(n - 1) == base.charAt(n - 2) && !vowel(base, n - 1)
                    && "lsz".indexOf(base.charAt(n - 1)) < 0) {
                s = base.substring(0, n - 1); // "planned" is "plan"; "filled" stays "fill"
            } else if (measure(base) == 1 && shortSyllable(base)) {
                s = base + "e"; // "baking" is "bake"
            } else {
                s = base;
            }
        }
        if (s.endsWith("ll") && measure(s.substring(0, s.length() - 1)) > 1) {
            s = s.substring(0, s.length() - 1); // "travelled" and "travel" are "travel"; "filled" stays "fill"
        }
        if (s.endsWith("y") && s.length() > 3 && "aeiou".indexOf(s.charAt(s.length() - 2)) < 0
                && hasVowel(s, s.length() - 1)) {
            s = s.substring(0, s.length() - 1) + "i"; // "tidy" is "tidi", as "tidied" and "tidies" are
        }
        if (s.endsWith("ation") && measure(s.substring(0, s.length() - 5)) > 1) {
            s = s.substring(0, s.length() - 5); // "invitation" is "invit", as "invite" is
        } else {
            for (String suffix : LONG_SUFFIXES) {
                if (s.endsWith(suffix) && measure(s.substring(0, s.length() - suffix.length())) > 1) {
                    s = s.substring(0, s.length() - suffix.length()); // "renewal" is "renew", "renovate" "renov"
                    break;
                }
            }
        }
        if (s.endsWith("e") && s.length() > 3) {
            String r = s.substring(0, s.length() - 1);
            int m = measure(r);
            if (m > 1 || m == 1 && !shortSyllable(r)) {
                s = r;
            }
        }
        return s;
    }

    /** Suffixes taken off a word of at least two syllables before them: "-ment", "-ate", "-al". */
    private static final List<String> LONG_SUFFIXES = List.of("ment", "ate", "al");

    /** True for a vowel at {@code i}: a, e, i, o, u, and y after a consonant. */
    private static boolean vowel(String s, int i) {
        char c = s.charAt(i);
        return "aeiou".indexOf(c) >= 0 || c == 'y' && i > 0 && !vowel(s, i - 1);
    }

    /** True when {@code s} has a vowel before index {@code end}. */
    private static boolean hasVowel(String s, int end) {
        for (int i = 0; i < end; i++) {
            if (vowel(s, i)) {
                return true;
            }
        }
        return false;
    }

    /** How many vowel-consonant sequences the word has ("tree" 0, "bak" 1, "invit" 2): its length in syllables. */
    private static int measure(String s) {
        int m = 0;
        boolean previousVowel = false;
        for (int i = 0; i < s.length(); i++) {
            boolean v = vowel(s, i);
            if (!v && previousVowel) {
                m++;
            }
            previousVowel = v;
        }
        return m;
    }

    /** True when the word ends consonant, vowel, consonant, the last not w, x or y ("bak", "hik", not "pack"). */
    private static boolean shortSyllable(String s) {
        int n = s.length();
        return n >= 3 && !vowel(s, n - 3) && vowel(s, n - 2) && !vowel(s, n - 1) && "wxy".indexOf(s.charAt(n - 1)) < 0;
    }

    /**
     * Lower case with accents taken off ("café" is "cafe", the Turkish dotted capital I is "i"), with every run of
     * characters other than the letters a to z and the digits as one space, so letters of other scripts ("нет") drop
     * out.
     */
    public static String normalise(String s) {
        if (s == null) {
            return "";
        }
        String plain = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return plain.replaceAll("[^a-z0-9]+", " ").strip();
    }
}
