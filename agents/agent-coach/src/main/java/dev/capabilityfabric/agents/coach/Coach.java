package dev.capabilityfabric.agents.coach;

import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.DecisionRecord;
import dev.capabilityfabric.protocol.Envelope;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Reply;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.FabricClient;
import dev.capabilityfabric.sdk.InvocationContext;
import dev.capabilityfabric.sdk.ReactiveHandler;
import dev.capabilityfabric.sdk.model.ModelRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The system speaking first. It watches goal facts (and, through {@link CoachSweep}, the clock), decides
 * deterministically when something deserves the person's attention ({@link Nudges}), lets the model phrase it,
 * and posts it to the person's inbox with a typed suggestion the person can accept with a yes. It never
 * changes a goal itself.
 */
@Component
public class Coach implements ReactiveHandler {

    private static final Logger log = LoggerFactory.getLogger(Coach.class);

    /**
     * Observations recently posted, with when, keyed by the observation and the plan it was made on: skips the
     * model call for them (the kernel drops duplicates anyway). A changed plan is a new key, so an observation the
     * kernel expired because the plan changed is posted again when it still holds. Entries expire, so a dismissed
     * or settled observation can come back once it holds again. A post the kernel refused or held back (the person
     * has too many unread messages) is not remembered: a later look posts it again.
     */
    private final Map<String, Instant> posted = new ConcurrentHashMap<>();
    static final Duration REMEMBER = Duration.ofHours(12);
    /**
     * Providers whose last post the kernel held back because the person has too many unread messages. While a
     * provider is held, its nudges are posted with their facts as they are, without asking the model to phrase them:
     * the kernel would most likely hold them back too, and a model call would compete with the person's own
     * sentences for nothing. A post the kernel accepts, or the start of the next sweep (see {@link #sweepStarting}),
     * ends the hold.
     */
    private final Set<String> held = ConcurrentHashMap.newKeySet();

    /** Facts longer than this are posted as they are: a phrasing that keeps every number would not be shorter. */
    static final int MAX_PHRASED = 600;
    /** Room the phrasing may take beyond the facts' own length. */
    static final int PHRASING_ROOM = 60;

    static final String SYSTEM = """
            You write one short, friendly message (one or two sentences) to a person about one of their goals, from
            the facts given. [goal] stands for the goal's name and T1, T2 and so on for its tasks: write them exactly
            as given, the system puts the names in. A goal is the whole thing the person wants to achieve and tasks
            are its steps: never call the goal a task. Start with what needs attention. Keep every number, date and
            task key exactly as given and in the order given, each number with its unit (h stays h), and keep the
            words "only", "but", "cannot" and "if" where the facts have them; add no new facts (no day, status or
            "not" the facts do not have, no claim that the work fits when the facts say it does not) and no sentence
            the facts do not support. Suggest, never
            order: say what could help, not what the person must do. Do not ask a question and do not describe the
            suggested change in detail; the person sees it separately. Write plain, correct English.
            Examples:
            Facts: [goal] has about 7 h of open work, but only 4 h of working time is left before 2026-11-21
            (2 h a day on Mon, Tue, Wed). Moving the deadline to 2026-11-25 would fit the work.
            Message: [goal] still needs about 7 h, but only 4 h fit before 2026-11-21 at 2 h a day; 2026-11-25
            would give the work room.
            Facts: Due today for [goal]: T1, T3.
            Message: T1 and T3 of [goal] are due today.
            Answer with JSON only.""";

    @Override
    public CapabilityManifest manifest() {
        return CapabilityManifest.builder("coach.nudge", "1.0.0")
                .name("Coach")
                .description("Speaks first: notices deadlines that no longer fit the person's pace, blocked chains, "
                        + "work due today, overdue and forgotten goals, and leaves a message with a suggestion the "
                        + "person can accept or dismiss.")
                .kind(CapabilityManifest.Kind.AI)
                .mode(CapabilityManifest.Mode.REACTIVE)
                .consumes(Vocabulary.TASKS, Vocabulary.TASK_TITLE, Vocabulary.TASK_STATUS, Vocabulary.TASK_DUE_DATE,
                        Vocabulary.TASK_EFFORT_HOURS, Vocabulary.TASK_DEPENDS_ON, Vocabulary.GOAL_TEXT,
                        Vocabulary.GOAL_DUE_DATE, Vocabulary.GOAL_TODAY, Vocabulary.PERSON_DAILY_HOURS,
                        Vocabulary.PERSON_WORKING_DAYS, Vocabulary.PERSON_HOLIDAYS)
                .triggers(Facts.RUN_FINISHED, Facts.TASK_STATUS_CHANGED, Facts.TASK_UPDATED, Facts.GOAL_RESCHEDULED)
                .tags("coaching", "inbox", "reminders", "deadline")
                .cost(2_000, 1)
                .build();
    }

    @Override
    public Optional<ProposedChange> onFact(Envelope fact, String goalId, InvocationContext context) {
        context.fabric().flatMap(f -> f.goalContext(goalId))
                .ifPresent(goal -> Nudges.onChange(goal).forEach(n -> post(n, goal, context)));
        return Optional.empty(); // the coach suggests through the inbox, never by changing the goal
    }

    /** The sweep is about to look at every goal: nudges are phrased again until the kernel holds a post back. */
    void sweepStarting() {
        held.clear();
    }

    /**
     * Phrases and posts one nudge, with the goal's plan fingerprint as the basis it was made on; the kernel drops
     * duplicates, expires messages whose basis changed and validates the suggestion. While the provider is held back
     * (see {@link #held}) the nudge is posted with its facts, unphrased.
     */
    void post(Nudges.Nudge nudge, GoalContext goal, InvocationContext context) {
        Optional<FabricClient> fabric = context.fabric();
        Instant now = Instant.now();
        posted.values().removeIf(at -> at.plus(REMEMBER).isBefore(now));
        String seen = nudge.dedupeKey() + "|" + goal.planFingerprint();
        // Claimed atomically, so a sweep and a reaction cannot post the same observation at once.
        if (fabric.isEmpty() || posted.putIfAbsent(seen, now) != null) {
            return;
        }
        CapabilityManifest m = context.manifest();
        String provider = String.valueOf(m.provider());
        int before = context.decisions().size();
        String text = held.contains(provider) ? nudge.facts() : phrase(nudge, context);
        List<DecisionRecord> decisions = List.copyOf(context.decisions().subList(before, context.decisions().size()));
        Inbox.Post post = new Inbox.Post(nudge.kind(), nudge.goalId(), text, nudge.suggestions(), nudge.dedupeKey(),
                m.provider(), m.instanceId(), decisions, goal.planFingerprint());
        Reply reply = fabric.get().request(Subjects.INBOX_POST, post);
        if (!reply.ok()) {
            posted.remove(seen); // try again on the next look
            log.info("{} for \"{}\" not posted: {}", nudge.kind(), goal.goal().text(), reply.error());
            return;
        }
        if (reply.data() != null && reply.data().path("held").asBoolean(false)) {
            // Held back while the person has too many unread messages: not posted, so tried again on a later look,
            // and the provider's next nudges are not phrased until the hold ends.
            posted.remove(seen);
            held.add(provider);
            log.info("{} for \"{}\" held back: {}", nudge.kind(), goal.goal().text(),
                    reply.data().path("reason").asString(""));
            return;
        }
        held.remove(provider);
        if (reply.data() == null || !reply.data().path("duplicate").asBoolean(false)) {
            log.info("{} posted for \"{}\"", nudge.kind(), goal.goal().text());
        }
    }

    /**
     * The model's wording, with the names put back, when it keeps to the facts; the facts themselves otherwise.
     * The model sees only the nudge's template, so it never reads or rewrites a name a person wrote. Facts too
     * long to phrase within {@link #MAX_PHRASED} characters are not sent to the model.
     */
    static String phrase(Nudges.Nudge nudge, InvocationContext context) {
        String template = nudge.template();
        if (template.length() > MAX_PHRASED) {
            return nudge.facts();
        }
        try {
            JsonNode out = context.ask(new ModelRequest("nudge", SYSTEM, "Facts: " + template, schema(template),
                    node -> faithful(node.path("text").asString(""), template)), JsonNode.class);
            return nudge.fill(out.path("text").asString(template));
        } catch (RuntimeException e) {
            log.debug("phrasing failed, using the facts: {}", e.getMessage());
            return nudge.facts();
        }
    }

    private static final Pattern CHECKED = Pattern.compile(
            "(?<![\\w.])(\\d{4}-\\d{2}-\\d{2}|T\\d+|\\d+(?:\\.\\d+)?)(?![\\d.]\\d)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?]+(?=\\s|$)");
    private static final Pattern TASK_WORD = Pattern.compile("(?i)\\btasks?\\b");
    /**
     * Numbers written as words. "one", "zero" and "a couple of" count only before what they would count ("one
     * task", "one hour"), since "one" is also a pronoun ("the one due today").
     */
    private static final Pattern NUMBER_WORD = Pattern.compile("(?i)\\b(two|three|four|five|six|seven|eight|nine|ten"
            + "|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|a dozen|dozen"
            + "|(?:one|zero|a couple of|a single)(?=\\s+(?:more\\s+|open\\s+|of\\s+(?:the|its|your)\\s+)?"
            + "(?:tasks?|steps?|hours?|h|days?|weeks?|goals?|deadlines?)\\b))\\b");
    private static final List<String> NUMBER_WORDS = List.of("zero", "one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
            "seventeen", "eighteen", "nineteen", "twenty");
    /**
     * Words that place a day: relative days, weekdays (short forms too: the facts write "Mon, Tue"), months and named
     * periods. "May" is left out: it is a verb too.
     */
    private static final Pattern DAY_WORD = Pattern.compile("(?i)\\b(today|tonight|tomorrow|yesterday|weekend|week"
            + "|month|year|monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues?|wed|thu(?:rs?)?|fri|sat"
            + "|sun|january|february|march|april|june|july|august|september|october|november|december|jan|feb|mar"
            + "|apr|jun|jul|aug|sept?|oct|nov|dec)s?\\b");
    /** Words that state where a task stands, by meaning: a message may use one only when the facts use one alike. */
    private static final List<Set<String>> STATUS_WORDS = List.of(Set.of("done", "finished", "completed", "complete"),
            Set.of("overdue", "late", "behind"), Set.of("blocked", "stuck"), Set.of("due"),
            Set.of("started", "begun", "underway"), Set.of("cancelled", "canceled"));
    /** A name for each meaning of {@link #STATUS_WORDS}, in the same order. */
    private static final List<String> STATUS_NAMES = List.of("done", "overdue", "blocked", "due", "started",
            "cancelled");
    private static final Pattern STATUS_WORD = Pattern.compile("(?i)\\b(done|finished|completed|complete|overdue|late"
            + "|behind|blocked|stuck|due|started|begun|underway|cancelled|canceled)\\b");
    /** A negation: "not", "no", "never", "nothing", "none", "cannot", "without", "neither", "nor", "n't". */
    private static final Pattern NEGATION = Pattern.compile("(?i)\\b(?:not|no|never|nothing|none|cannot|without"
            + "|neither|nor)\\b|n't\\b");
    /** Words that order rather than suggest; allowed only when the facts themselves use them. */
    private static final Pattern ORDERS = Pattern.compile(
            "(?i)\\b(must|have to|has to|need to|needs to|got to|immediately|asap|urgent|urgently)\\b");

    /**
     * Why a phrasing of {@code facts} (a nudge template) cannot be posted; empty when it can. Wording may change,
     * nothing else may: every date, task key and number (in digits or in words) in the message must come from the
     * facts and every date and number in the facts must stay; {@code [goal]} stays when the facts name the goal and
     * no other bracketed placeholder or quoted name appears (the system puts the names in); the dates and numbers
     * stay in the order of the facts (swapping the hours of work and the hours left, or the deadline and the
     * suggested date, changes what the message says); a word that places a day ("tomorrow", "Friday", "next week"), a
     * word that states where a task stands ("overdue", "done", "blocked", "due") and a negation appear only when the
     * facts have one alike; the message has no more sentences than the facts; it calls the goal a task only when the
     * facts speak of tasks; and it orders ("must", "need to") only when the facts do. Besides, the message names the
     * same task keys as the facts, in the same order of first mention (a swapped or dropped key changes who is
     * blocked or late); each number has the unit it has in the facts ("4 h" never becomes "4 days"); "only", "but",
     * "cannot" and "if" of the facts stay in some form (dropping them turns "only 4 h is left" into "4 h is left" or
     * "if it no longer matters" into "it no longer matters"); and when the facts say the work does not fit, the
     * message claims nothing like "on track", "plenty" or "it fits". A status word stands with the same task keys as
     * in the facts ("T2 is done" when the facts say T2 is blocked and others wait until it is done is refused, see
     * {@link #statusByKey}), and the message claims no change or action as made ("I moved them to 2026-11-19", "the
     * deadline was moved", "I emailed your team", see {@link #CHANGE_CLAIM}): the coach only suggests.
     */
    static List<String> faithful(String text, String facts) {
        List<String> problems = new ArrayList<>();
        if (text.isBlank()) {
            problems.add("text is empty");
        }
        Set<String> known = new HashSet<>();
        Matcher f = CHECKED.matcher(facts);
        while (f.find()) {
            known.add(f.group(1));
        }
        Matcher t = CHECKED.matcher(text);
        boolean citesTask = false;
        while (t.find()) {
            if (!known.contains(t.group(1))) {
                problems.add("\"" + t.group(1) + "\" is not in the facts");
            }
            citesTask |= t.group(1).matches("T\\d+");
        }
        if (!citesTask && known.stream().anyMatch(k -> k.matches("T\\d+"))) {
            problems.add("name the tasks by their keys (for example T3) so the person can find them");
        }
        Set<String> written = new HashSet<>();
        Matcher w = CHECKED.matcher(text);
        while (w.find()) {
            written.add(w.group(1));
        }
        known.stream().filter(k -> !k.matches("T\\d+") && !written.contains(k)).sorted()
                .forEach(k -> problems.add("keep " + (k.contains("-") ? "the date " : "the number ") + k));
        problems.addAll(numberWords(text, facts, known));
        problems.addAll(order(text, facts));
        problems.addAll(dayWords(text, facts));
        problems.addAll(statusWords(text, facts));
        problems.addAll(keyStatuses(text, facts));
        problems.addAll(changeClaims(text, facts));
        if (NEGATION.matcher(text).find() && !NEGATION.matcher(facts).find()) {
            problems.add("the facts negate nothing: leave out the negation");
        }
        problems.addAll(names(text, facts));
        problems.addAll(keyOrder(text, facts));
        problems.addAll(units(text, facts));
        problems.addAll(keptWords(text, facts));
        if (DOES_NOT_FIT.matcher(facts).find() && FITS_CLAIM.matcher(text).find()) {
            problems.add("the facts say the work does not fit: claim nothing like \"" + firstMatch(FITS_CLAIM, text)
                    + "\"");
        }
        int sentences = sentences(text);
        int allowed = sentences(facts);
        if (sentences > allowed) {
            problems.add("add no sentences: the facts have " + allowed + " and the message has " + sentences);
        }
        if (TASK_WORD.matcher(text).find() && !TASK_WORD.matcher(facts).find()
                && known.stream().noneMatch(k -> k.matches("T\\d+"))) {
            problems.add("the facts are about the goal: call it the goal, not a task");
        }
        Matcher order = ORDERS.matcher(text);
        while (order.find()) {
            if (!Pattern.compile("(?i)\\b" + Pattern.quote(order.group(1)) + "\\b").matcher(facts).find()) {
                problems.add("suggest rather than order: leave out \"" + order.group(1) + "\"");
            }
        }
        return problems;
    }

    private static final Pattern KEY = Pattern.compile("(?<![\\w.])T\\d+(?!\\d)");

    /**
     * The task keys of the message, each at its first mention, must be those of the facts in the same order. A
     * message that names no key at all is told so by its own check.
     */
    private static List<String> keyOrder(String text, String facts) {
        List<String> inFacts = firstMentions(KEY, facts);
        List<String> inText = firstMentions(KEY, text);
        if (inFacts.isEmpty() || inText.isEmpty() || inFacts.equals(inText)) {
            return List.of();
        }
        return List.of("name the tasks " + String.join(", ", inFacts) + " in this order, each by its key");
    }

    private static List<String> firstMentions(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (!found.contains(m.group())) {
                found.add(m.group());
            }
        }
        return found;
    }

    /** A number on its own (no date, no task key), as {@link #CHECKED} finds it. */
    private static final Pattern PLAIN_NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    /** The unit right after a number, an adjective between them allowed: "4 h", "9 working days", "3 open tasks". */
    private static final Pattern UNIT_AFTER = Pattern.compile("\\s*(?:(?:working|open|more|whole|full)\\s+)?"
            + "(h|hrs?|hours?|minutes?|mins?|days?|weeks?|months?|years?|tasks?|steps?|%)(?![\\w%])");

    /** Each number of the message has a unit the same number has in the facts, or none when it has none there. */
    private static List<String> units(String text, String facts) {
        Map<String, Set<String>> allowed = new HashMap<>();
        Matcher f = CHECKED.matcher(facts);
        while (f.find()) {
            if (PLAIN_NUMBER.matcher(f.group(1)).matches()) {
                allowed.computeIfAbsent(f.group(1), k -> new HashSet<>()).add(unitAfter(facts, f.end()));
            }
        }
        List<String> problems = new ArrayList<>();
        Matcher t = CHECKED.matcher(text);
        while (t.find()) {
            Set<String> units = allowed.get(t.group(1));
            if (units == null) {
                continue; // reported as not in the facts, or a date or a key
            }
            String unit = unitAfter(text, t.end());
            if (!units.contains(unit)) {
                String said = units.stream().filter(u -> !u.isEmpty()).findFirst().orElse("");
                problems.add("write " + t.group(1) + (said.isEmpty() ? " without a unit" : " " + said)
                        + " as the facts do");
            }
        }
        return problems;
    }

    /** The unit after the number that ends at {@code at}, as compared: "h" for hours, "day" for days; "" for none. */
    private static String unitAfter(String text, int at) {
        Matcher m = UNIT_AFTER.matcher(text).region(at, text.length());
        if (!m.lookingAt()) {
            return "";
        }
        String unit = m.group(1).toLowerCase(Locale.ROOT);
        if (unit.startsWith("h")) {
            return "h";
        }
        if (unit.startsWith("min")) {
            return "min";
        }
        return unit.endsWith("s") ? unit.substring(0, unit.length() - 1) : unit;
    }

    /**
     * Words of the facts that carry their meaning, each with the words that may stand for it in the message: "only"
     * (a limit), "but" (a contrast), "cannot" (what is not possible) and "if" (a condition).
     */
    private static final List<Pattern[]> KEPT_WORDS = List.of(
            new Pattern[]{Pattern.compile("(?i)\\bonly\\b"), Pattern.compile("(?i)\\b(?:only|just|merely)\\b")},
            new Pattern[]{Pattern.compile("(?i)\\bbut\\b"),
                    Pattern.compile("(?i)\\b(?:but|however|yet|though|although|while|whereas)\\b")},
            new Pattern[]{Pattern.compile("(?i)\\b(?:cannot|can't|can\\s+not)\\b"),
                    Pattern.compile("(?i)\\b(?:cannot|can\\s+not|unable|not)\\b|n't\\b")},
            new Pattern[]{Pattern.compile("(?i)\\bif\\b"), Pattern.compile("(?i)\\b(?:if|unless|in\\s+case|should)\\b")});

    /** "Only", "but", "cannot" and "if" of the facts stay in the message in some form. */
    private static List<String> keptWords(String text, String facts) {
        List<String> problems = new ArrayList<>();
        for (Pattern[] word : KEPT_WORDS) {
            Matcher inFacts = word[0].matcher(facts);
            if (inFacts.find() && !word[1].matcher(text).find()) {
                problems.add("keep \"" + inFacts.group() + "\": it carries what the facts say");
            }
        }
        return problems;
    }

    /** Facts that say the work does not fit the time left: "only 4 h of working time is left", "does not fit". */
    private static final Pattern DOES_NOT_FIT = Pattern.compile("(?i)\\bbut\\s+only\\b|\\bonly\\s+\\d+(?:\\.\\d+)?\\s*h\\b"
            + "|\\b(?:does|do|will|would)\\s+not\\s+fit\\b|\\b(?:doesn't|don't|won't)\\s+fit\\b");
    /** Words that claim the work fits or is fine: "on track", "plenty", "enough time", "it fits", "fits easily". */
    private static final Pattern FITS_CLAIM = Pattern.compile("(?i)\\b(?:it|this|that|everything|all|work|plan)\\s+"
            + "(?:still\\s+|easily\\s+|now\\s+)?fits\\b|\\bfits\\s+(?:easily|comfortably|fine|well|in)\\b"
            + "|\\b(?:on\\s+track|enough\\s+(?:time|room|hours?)|plenty|in\\s+good\\s+shape|all\\s+good|comfortably"
            + "|easily|will\\s+make\\s+it|no\\s+(?:problem|worries|rush))\\b");

    private static String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group() : "";
    }

    /**
     * Numbers the message writes as words ("Four tasks are due today") must be numbers of the facts too, or the
     * count of the tasks the facts name.
     */
    private static List<String> numberWords(String text, String facts, Set<String> known) {
        List<String> problems = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        Matcher key = KEY.matcher(facts);
        while (key.find()) {
            keys.add(key.group());
        }
        Matcher m = NUMBER_WORD.matcher(text);
        while (m.find()) {
            String word = m.group(1).toLowerCase(Locale.ROOT);
            int value = switch (word) {
                case "a dozen", "dozen" -> 12;
                case "a couple of" -> 2;
                case "a single" -> 1;
                default -> NUMBER_WORDS.indexOf(word);
            };
            boolean inFacts = known.contains(String.valueOf(value))
                    || Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(facts).find();
            if (!inFacts && value != keys.size()) {
                problems.add("\"" + m.group(1) + "\" is not in the facts; write only the numbers the facts give");
            }
        }
        return problems;
    }

    /**
     * The dates and numbers of the message, each where it first appears, must come in the order they first appear in
     * the facts.
     */
    private static List<String> order(String text, String facts) {
        List<String> inFacts = firstOccurrences(facts);
        int last = -1;
        for (String value : firstOccurrences(text)) {
            int at = inFacts.indexOf(value);
            if (at < 0) {
                continue; // reported as not in the facts
            }
            if (at < last) {
                return List.of("keep the dates and numbers in the order of the facts: " + String.join(", ", inFacts));
            }
            last = at;
        }
        return List.of();
    }

    /** The dates and numbers of {@code text} (task keys left out), each once, in the order they first appear. */
    private static List<String> firstOccurrences(String text) {
        List<String> values = new ArrayList<>();
        Matcher m = CHECKED.matcher(text);
        while (m.find()) {
            if (!m.group(1).matches("T\\d+") && !values.contains(m.group(1))) {
                values.add(m.group(1));
            }
        }
        return values;
    }

    /** Words that place a day ("tomorrow", "Friday", "next week") must be words of the facts. */
    private static List<String> dayWords(String text, String facts) {
        Set<String> allowed = new HashSet<>();
        Matcher f = DAY_WORD.matcher(facts);
        while (f.find()) {
            allowed.add(dayKey(f.group(1)));
        }
        List<String> problems = new ArrayList<>();
        Matcher t = DAY_WORD.matcher(text);
        while (t.find()) {
            if (!allowed.contains(dayKey(t.group(1)))) {
                problems.add("\"" + t.group(1) + "\" is not in the facts; write only the days and dates they give");
            }
        }
        return problems;
    }

    /** A day word as compared: weekdays and months by their first three letters ("Mon" is "Monday"). */
    private static String dayKey(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return w.length() > 3 && !Set.of("today", "tonight", "tomorrow", "yesterday", "weekend", "week", "month", "year")
                .contains(w) ? w.substring(0, 3) : w;
    }

    /** Words that state where a task stands must have one of like meaning in the facts. */
    private static List<String> statusWords(String text, String facts) {
        Set<String> inFacts = new HashSet<>();
        Matcher f = STATUS_WORD.matcher(facts);
        while (f.find()) {
            inFacts.add(f.group(1).toLowerCase(Locale.ROOT));
        }
        List<String> problems = new ArrayList<>();
        Matcher t = STATUS_WORD.matcher(text);
        while (t.find()) {
            String word = t.group(1).toLowerCase(Locale.ROOT);
            boolean alike = STATUS_WORDS.stream().filter(g -> g.contains(word))
                    .anyMatch(g -> g.stream().anyMatch(inFacts::contains));
            if (!alike) {
                problems.add("\"" + t.group(1) + "\" is not in the facts; say only where tasks stand as the facts do");
            }
        }
        return problems;
    }

    /** Where a sentence ends: ".", "!", "?" or ";" before white space or the end (not the dot of "13.5"). */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("[.!?;]+(?=\\s|$)");
    /**
     * Where a clause of a sentence ends: a comma or a colon not followed by a task key ("T1, T3 are overdue" is one
     * clause), or before "and", "but", "so", "while", "whereas" or "yet" between a word that is no task key and a
     * task key ("T2 is blocked and T4 cannot start" is two, "T3 and T4 cannot start" one).
     */
    private static final Pattern CLAUSE_BREAK = Pattern.compile("[,:](?!\\s*T\\d)|(?<!\\bT\\d{1,3})\\s+(?=(?:and|but"
            + "|so|while|whereas|yet)\\s+T\\d)");

    /**
     * For each task key of {@code text}, the status words said of it, by meaning (indices into {@link #STATUS_WORDS}):
     * a status word belongs to the task keys of its clause, or to those of its sentence when its clause names none
     * ("Due today for [goal]: T1, T3." says T1 and T3 are due; "T2 is blocked, and T4 cannot start until it is done."
     * says T2 is blocked, and "done" of T4).
     */
    static Map<String, Set<Integer>> statusByKey(String text) {
        Map<String, Set<Integer>> byKey = new LinkedHashMap<>();
        for (String sentence : SENTENCE_BREAK.split(text)) {
            List<String> sentenceKeys = firstMentions(KEY, sentence);
            for (String clause : CLAUSE_BREAK.split(sentence)) {
                Set<Integer> groups = new HashSet<>();
                Matcher m = STATUS_WORD.matcher(clause);
                while (m.find()) {
                    String word = m.group(1).toLowerCase(Locale.ROOT);
                    for (int g = 0; g < STATUS_WORDS.size(); g++) {
                        if (STATUS_WORDS.get(g).contains(word)) {
                            groups.add(g);
                        }
                    }
                }
                if (groups.isEmpty()) {
                    continue;
                }
                List<String> clauseKeys = firstMentions(KEY, clause);
                for (String key : clauseKeys.isEmpty() ? sentenceKeys : clauseKeys) {
                    byKey.computeIfAbsent(key, k -> new HashSet<>()).addAll(groups);
                }
            }
        }
        return byKey;
    }

    /**
     * Every status word the message says of a task key must be one the facts say of that key (see
     * {@link #statusByKey}). Keys the facts do not name are reported by their own check.
     */
    private static List<String> keyStatuses(String text, String facts) {
        Map<String, Set<Integer>> inFacts = statusByKey(facts);
        List<String> problems = new ArrayList<>();
        statusByKey(text).forEach((key, groups) -> {
            if (!firstMentions(KEY, facts).contains(key)) {
                return;
            }
            Set<Integer> allowed = inFacts.getOrDefault(key, Set.of());
            groups.stream().filter(g -> !allowed.contains(g)).sorted().forEach(g -> problems.add("the facts do not say "
                    + key + " is " + STATUS_NAMES.get(g) + "; say where each task stands as the facts do"));
        });
        return problems;
    }

    /**
     * Verbs that change a goal or report something done, in a past form that is no other form of the verb: wherever
     * one stands it claims the change was made ("the deadline moved to 2026-11-25", "moved to 2026-11-20 to give it
     * room", "replanning [goal] fixed it", "found a way around it", "gave it room"), unless a modal or a condition
     * comes before it in its clause (see {@link #changeClaims}).
     */
    private static final String CHANGED = "moved|changed|rescheduled|shifted|postponed|pushed|delayed|extended|updated"
            + "|adjusted|cancell?ed|canceled|dropped|removed|deleted|added|created|marked|replanned|re-planned"
            + "|reorgani[sz]ed|rearranged|reassigned|booked|scheduled|emailed|e-mailed|mailed|messaged|notified|informed"
            + "|reminded|fixed|solved|resolved|arranged|placed|gave|found|unblocked";
    /**
     * Past forms that are also another form of the verb or another word ("put", "set" and "reset" are the same in the
     * present, "finished" and "started" also state where a task stands, "called" also names): they claim a change
     * only after a subject or a form of "be", "have" or "get" that makes it done ("I put it on", "it's been set").
     */
    private static final String CHANGED_AFTER_SUBJECT = "put|set|reset|planned|assigned|sent|called|phoned|contacted"
            + "|told|asked|confirmed|started|began|begun|finished|completed|handled|organi[sz]ed|progressed|advanced"
            + "|took\\s+care|took\\s+the\\s+liberty|went\\s+ahead|gone\\s+ahead";
    /** The same verbs in their "-ing" form, which after a form of "be" claims a change under way ("I'm moving it"). */
    private static final String CHANGING = "moving|changing|rescheduling|shifting|postponing|pushing|delaying|extending"
            + "|updating|adjusting|cancell?ing|dropping|removing|deleting|adding|creating|marking|replanning|re-planning"
            + "|reorgani[sz]ing|rearranging|reassigning|assigning|booking|scheduling|emailing|e-mailing|mailing|messaging"
            + "|sending|calling|phoning|contacting|notifying|telling|informing|reminding|starting|finishing|completing"
            + "|fixing|solving|resolving|handling|arranging|organi[sz]ing|putting|placing|setting|resetting"
            + "|taking\\s+care|going\\s+ahead";
    /** Words that may stand between a subject or a form of "be", "have" or "get" and the verb: "I've already moved". */
    private static final String FILLER = "(?:(?:already|just|now|also|since|recently|successfully|finally|been|being)\\s+)*";
    /**
     * A change or action claimed as made or under way: a past form of {@link #CHANGED} anywhere (after "the", "a",
     * "as" or "than" it describes something instead: "the planned date", "as planned"); a past form of
     * {@link #CHANGED_AFTER_SUBJECT} after "I" or "we" ("I put it on", "we've set it") or after a form of "be", "have"
     * or "get" that makes it done ("it has been set", "it's been put", "it is being set", "it got put"); a form of
     * {@link #CHANGING} after a form of "be" ("I'm moving it", "it is being moved"); and a date the words place now
     * ("it is now on 2026-11-19", "its deadline now sits at 2026-11-25", "it's now due 2026-11-25", "your deadline is
     * now 2026-11-20", "it is 2026-11-25 now"). "Could be moved", "moving it would help", "if it moved" and "the hours
     * you have set" claim nothing (see {@link #changeClaims}).
     */
    static final Pattern CHANGE_CLAIM = Pattern.compile("(?i)\\b(?:(?:i|we)(?:'ve|'d)?|(?:i|we)\\s+(?:have|had)"
            + "|(?<!\\byou\\s{1,3})(?:has|have|had)(?:\\s+been)?|(?<!\\byou)(?:'s|'ve|'d)\\s+been|was|were|got|gotten"
            + "|(?:is|are|am)\\s+(?:now|being))\\s+" + FILLER + "(?:" + CHANGED + "|" + CHANGED_AFTER_SUBJECT + ")\\b"
            + "|\\b(?:i'm|i\\s+am|we're|we\\s+are|is|are|am|was|were|'s|'re)\\s+" + FILLER + "(?:" + CHANGING + ")\\b"
            + "|(?<!\\b(?:the|a|an|its|your|their|this|that|these|those|his|her|our|my|any|each|every|some|no|as|than)"
            + "\\s{1,3})\\b(?:" + CHANGED + ")\\b"
            + "|\\bnow\\s+(?:(?:sits|moves|lies|stands|falls|goes|is|are|on|at|due|for|to|until|till|set|scheduled|planned"
            + "|fixed)\\s+){0,3}(?:the\\s+)?\\d{4}-\\d{2}-\\d{2}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\s+now\\b");
    /**
     * Words before a claimed change, in its clause, that make it a possibility, a condition or a denial instead: a
     * modal ("could be moved", "it would move"), a condition ("if it moved", "once T2 is finished", "until it is
     * done") or a negation ("it has not been moved"). Their own checks keep what the facts allow of them.
     */
    private static final Pattern NOT_A_CLAIM = Pattern.compile("(?i)\\b(?:can|could|would|should|might|may|must|will"
            + "|shall|cannot|can't|couldn't|wouldn't|shouldn't|won't|if|unless|until|till|once|when|whenever|whether"
            + "|not|never|n't)\\b|'d\\b|'ll\\b|n't\\b");
    /** "You" right before a claimed change: the person did it ("the hours you set", "as you have planned"). */
    private static final Pattern YOU_BEFORE = Pattern.compile("(?i)\\byou(?:'ve|'d)?(?:\\s+(?:have|had|already|just"
            + "|also))*\\s+$");
    /** Where a clause starts: after punctuation or a dash between spaces. */
    private static final Pattern CLAUSE_START = Pattern.compile("[.,;:!?()]|\\s[-\u2013\u2014]\\s");

    /**
     * The message claims no change or action as made (see {@link #CHANGE_CLAIM}) unless the facts say the same
     * words. A claim whose clause has a modal, a condition or a negation before it ({@link #NOT_A_CLAIM}), or whose
     * subject is "you", is none.
     */
    private static List<String> changeClaims(String text, String facts) {
        List<String> problems = new ArrayList<>();
        Matcher m = CHANGE_CLAIM.matcher(text);
        while (m.find()) {
            int clauseStart = 0;
            Matcher start = CLAUSE_START.matcher(text).region(0, m.start());
            while (start.find()) {
                clauseStart = start.end();
            }
            String before = text.substring(clauseStart, m.start());
            if (NOT_A_CLAIM.matcher(before).find() || YOU_BEFORE.matcher(before).find()) {
                continue;
            }
            if (!Pattern.compile("(?i)\\b" + Pattern.quote(m.group()) + "\\b").matcher(facts).find()) {
                problems.add("\"" + m.group() + "\" claims something was done; nothing was changed: only suggest");
            }
        }
        return problems;
    }

    /** Placeholders and quoted names: the goal's placeholder must stay, nothing else may be bracketed or quoted. */
    private static List<String> names(String text, String facts) {
        List<String> problems = new ArrayList<>();
        if (facts.contains(Nudges.GOAL) && !text.contains(Nudges.GOAL)) {
            problems.add("keep " + Nudges.GOAL + " where the goal is meant; the system puts its name in");
        }
        Matcher p = PLACEHOLDER.matcher(text);
        while (p.find()) {
            if (!p.group().equals(Nudges.GOAL)) {
                problems.add("\"" + p.group() + "\" is not in the facts; the only placeholder is " + Nudges.GOAL);
            }
        }
        if (text.matches("(?s).*[\"\u201c\u201d\u00ab\u00bb].*")) {
            problems.add("write no quoted names; the system puts the names in");
        }
        return problems;
    }

    private static int sentences(String text) {
        Matcher m = SENTENCE_END.matcher(text.strip());
        int count = 0;
        while (m.find()) {
            count++;
        }
        return text.isBlank() ? 0 : Math.max(1, count + (text.strip().matches("(?s).*[.!?]$") ? 0 : 1));
    }

    /** The answer's shape: one text, long enough for every number and date of {@code facts}. */
    static JsonNode schema(String facts) {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        schema.putArray("required").add("text");
        schema.putObject("properties").putObject("text").put("type", "string").put("minLength", 10)
                .put("maxLength", Math.max(320, facts.length() + PHRASING_ROOM));
        return schema;
    }
}
