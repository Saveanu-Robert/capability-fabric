package dev.capabilityfabric.kernel.person;

import dev.capabilityfabric.algorithms.Durations;
import dev.capabilityfabric.algorithms.WorkdayCalendar;
import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerEvent;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.memory.EpisodicMemory;
import dev.capabilityfabric.kernel.registry.Embeddings;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Person;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The system's model of the person. Built from evidence of the owner's own work (finished tasks with real hours, when
 * tasks get done; guests' and agents' work does not count) and corrected by the person in words; what the person says
 * always beats what the numbers suggest. The profile reaches capabilities as {@code person.*} vocabulary, through the
 * same contract as everything else: the estimator calibrates with the person's bias, the sentinel measures risk
 * against their real daily capacity.
 */
@Component
public class ProfileService {

    static final double DEFAULT_DAILY_HOURS = 6.0;
    static final List<String> DEFAULT_DAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY");

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final Embeddings embeddings;
    private final KernelProperties properties;
    private final EpisodicMemory memory;
    private final Map<String, String> kindOfTitle = new ConcurrentHashMap<>();
    /** The profile last read, with when and at which {@link #version} it was read; null when none is kept. */
    private volatile Cached cached;
    /** Counts the corrections stored: a profile read while one was stored is never kept (see {@link #profile}). */
    private final AtomicLong version = new AtomicLong();

    /** A profile as read at {@code version}, {@code at} milliseconds since the epoch. */
    private record Cached(Person.Profile profile, long at, long version) {
    }

    private final List<String> holidays;

    public ProfileService(JdbcClient jdbc, LedgerService ledger, Embeddings embeddings, KernelProperties properties,
                          EpisodicMemory memory, @Value("${fabric.kernel.holidays:}") String holidays) {
        this.memory = memory;
        this.holidays = Arrays.stream(holidays.split(",")).map(String::strip).filter(h -> !h.isEmpty()).toList();
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    /**
     * A finished task as the profile learns from it: {@code estimated} is the model's own hours before any pace was
     * applied, {@code kind} the kind of task the estimator saw (null when it recorded none).
     */
    record Outcome(String title, String kind, double estimated, double actual, Instant completedAt) {
    }

    /**
     * The person's profile: pace, capacity and rhythm measured from finished tasks and done times, with every value
     * the person stated taking precedence; kept for 20 seconds. A correction makes the kept profile stale at once:
     * the profile is kept only with the number of corrections stored before it was read, and served only while no
     * correction was stored since, so a read that overlapped a correction is never served after it.
     */
    public Person.Profile profile() {
        long readAt = version.get();
        Cached kept = cached;
        if (kept != null && kept.version() == readAt && System.currentTimeMillis() - kept.at() < 20_000) {
            return kept.profile();
        }
        Map<String, JsonNode> overrides = new HashMap<>();
        jdbc.sql("SELECT key, value::text AS value FROM profile_overrides")
                .query((rs, i) -> Map.entry(rs.getString("key"), Json.tree(rs.getString("value"))))
                .list().forEach(e -> overrides.put(e.getKey(), e.getValue()));
        // Only tasks the owner finished describe the owner's pace; a guest's or an agent's do not.
        List<Outcome> outcomes = memory.ownerOutcomes().stream()
                .map(o -> new Outcome(o.title(), o.kind(), o.estimatedHours(), o.actualHours(), o.completedAt()))
                .toList();
        // A done task whose real hours are recorded later is not finished again. Only the owner's own completions
        // describe when the owner works; a guest's do not.
        List<Instant> doneTimes = jdbc.sql("""
                SELECT occurred_at FROM events WHERE type = :type AND payload->>'to' = 'DONE'
                  AND payload->>'from' IS DISTINCT FROM 'DONE'
                  AND """ + " " + LedgerEvent.BY_OWNER_SQL).param("type", Facts.TASK_STATUS_CHANGED)
                .query((rs, i) -> rs.getObject("occurred_at", OffsetDateTime.class).toInstant()).list();
        // Tasks the owner dropped, each counted once however often it was reopened and dropped again; a guest's or
        // an agent's drops do not describe the owner.
        int dropped = jdbc.sql(DROPPED_SQL).param("type", Facts.TASK_STATUS_CHANGED).query(Integer.class).single();

        Map<String, String> sources = new LinkedHashMap<>();
        // Estimation bias: geometric mean of actual / estimated, overall and per kind of task.
        double overall = geometricBias(outcomes);
        sources.put("estimateBias", outcomes.isEmpty() ? "default" : "evidence");
        if (overrides.containsKey("estimateBias")) {
            overall = clamp(overrides.get("estimateBias").asDouble());
            sources.put("estimateBias", "person");
        }
        List<Person.KindBias> kinds = new ArrayList<>();
        byKind(outcomes, this::kind).forEach((k, list) -> kinds.add(new Person.KindBias(k, geometricBias(list),
                list.size(), "evidence")));
        kinds.sort(Comparator.comparingInt(Person.KindBias::samples).reversed());

        // Rhythm: when finished work happens.
        ZoneId zone = ZoneId.systemDefault();
        Map<String, Integer> partOfDay = new LinkedHashMap<>();
        Map<String, Integer> weekday = new LinkedHashMap<>();
        for (Instant t : doneTimes) {
            ZonedDateTime z = t.atZone(zone);
            int h = z.getHour();
            String part = h < 5 ? "night" : h < 12 ? "morning" : h < 17 ? "afternoon" : h < 22 ? "evening" : "night";
            partOfDay.merge(part, 1, Integer::sum);
            weekday.merge(z.getDayOfWeek().name(), 1, Integer::sum);
        }
        double perWeek = perWeek(doneTimes, Instant.now());

        // Capacity: what the person said, else the real hours per active day, else a default.
        double dailyHours = DEFAULT_DAILY_HOURS;
        sources.put("dailyHours", "default");
        Map<String, Double> hoursPerDay = new HashMap<>();
        for (Outcome o : outcomes) {
            hoursPerDay.merge(o.completedAt().atZone(zone).toLocalDate().toString(), o.actual(), Double::sum);
        }
        if (hoursPerDay.size() >= 3) {
            dailyHours = Math.max(1, Math.min(12, hoursPerDay.values().stream().mapToDouble(d -> d).average().orElse(6)));
            dailyHours = Math.round(dailyHours * 2) / 2.0;
            sources.put("dailyHours", "evidence");
        }
        if (overrides.containsKey("dailyHours")) {
            dailyHours = overrides.get("dailyHours").asDouble();
            sources.put("dailyHours", "person");
        }
        List<String> days = DEFAULT_DAYS;
        sources.put("workingDays", "default");
        if (overrides.containsKey("workingDays")) {
            days = new ArrayList<>();
            for (JsonNode d : overrides.get("workingDays")) {
                days.add(d.asString());
            }
            sources.put("workingDays", "person");
        }

        List<String> insights = new ArrayList<>();
        if (!outcomes.isEmpty()) {
            // What the finished tasks measured, not the pace the person stated (which estimates use instead).
            insights.add(String.format(Locale.ROOT, "Tasks take you %.1fx the estimated time on average (%d finished tasks).",
                    geometricBias(outcomes), outcomes.size()));
        }
        for (Person.KindBias b : kinds) {
            if (b.samples() >= 2 && Math.abs(Math.log(b.factor())) > Math.log(1.3)) {
                insights.add(String.format(Locale.ROOT, "%s takes you %.1fx as long as estimated (%d tasks).",
                        capitalise(b.kind()), b.factor(), b.samples()));
            }
        }
        partOfDay.entrySet().stream().max(Map.Entry.comparingByValue()).filter(e -> doneTimes.size() >= 3)
                .ifPresent(e -> insights.add("You finish most tasks in the " + e.getKey() + "."));
        if (perWeek > 0) {
            insights.add(String.format(Locale.ROOT, "You complete about %.1f tasks a week.", perWeek));
        }
        if (dropped > 0) {
            insights.add("You dropped " + dropped + " task(s) so far.");
        }
        Person.Profile read = new Person.Profile(dailyHours, days, overall, outcomes.size(), kinds, partOfDay, weekday,
                doneTimes.size(), dropped, perWeek, sources, insights);
        cached = new Cached(read, System.currentTimeMillis(), readAt);
        return read;
    }

    /** The most weeks the weekly pace looks back over. */
    static final int PACE_WEEKS = 4;

    /**
     * Tasks finished per week, rounded to one decimal: those finished in the last {@link #PACE_WEEKS} weeks divided by
     * the weeks since the first completion, at most {@link #PACE_WEEKS}; 0 (not shown) while the first completion is
     * less than a week old, so one busy day does not read as a weekly pace.
     */
    static double perWeek(List<Instant> doneTimes, Instant now) {
        Instant first = doneTimes.stream().min(Comparator.naturalOrder()).orElse(null);
        if (first == null) {
            return 0;
        }
        double weeks = Math.min(PACE_WEEKS, Duration.between(first, now).toSeconds() / (7.0 * 86400));
        if (weeks < 1) {
            return 0;
        }
        Instant since = now.minusSeconds(PACE_WEEKS * 7L * 86400);
        long recent = doneTimes.stream().filter(t -> t.isAfter(since)).count();
        return Math.round(recent / weeks * 10) / 10.0;
    }

    /** How many distinct tasks the owner dropped: one per goal and task id, only the owner's own status changes. */
    static final String DROPPED_SQL = "SELECT count(DISTINCT (goal_id, payload->>'taskId')) FROM events"
            + " WHERE type = :type AND payload->>'to' = 'DROPPED' AND "
            + LedgerEvent.BY_OWNER_SQL;

    /**
     * Finished tasks grouped by kind of task: the kind the estimator recorded when it estimated the task, so the
     * pace per kind is learned for the same kinds the estimator later applies it to; {@code kindOfTitle} (nearest
     * kind by meaning) only for tasks with none recorded.
     */
    static Map<String, List<Outcome>> byKind(List<Outcome> outcomes, Function<String, String> kindOfTitle) {
        Map<String, List<Outcome>> byKind = new LinkedHashMap<>();
        for (Outcome o : outcomes) {
            String k = o.kind() != null && !o.kind().isBlank() ? o.kind() : kindOfTitle.apply(o.title());
            byKind.computeIfAbsent(k, x -> new ArrayList<>()).add(o);
        }
        return byKind;
    }

    /** The person's working days and the configured holidays, as a calendar. */
    public WorkdayCalendar calendar() {
        return WorkdayCalendar.of(profile().workingDays(), holidays);
    }

    /** The profile as {@code person.*} vocabulary for capabilities. */
    public Map<String, JsonNode> tokens() {
        Person.Profile p = profile();
        Map<String, JsonNode> tokens = new LinkedHashMap<>();
        tokens.put("dailyHours", Json.toTree(p.dailyHours()));
        tokens.put("workingDays", Json.toTree(p.workingDays()));
        tokens.put("holidays", Json.toTree(holidays));
        tokens.put("estimateBias", biasToken(p));
        return tokens;
    }

    /**
     * The {@code person.estimateBias} token: the overall factor, its samples, the factor per kind of task, and its
     * {@code source}: "person" when the person stated their pace, "evidence" when it was measured, "default" otherwise.
     */
    static ObjectNode biasToken(Person.Profile p) {
        ObjectNode bias = Json.object();
        bias.put("overall", p.overallBias());
        bias.put("samples", p.outcomes());
        bias.put("source", p.sources().getOrDefault("estimateBias", "default"));
        ObjectNode byKind = bias.putObject("byKind");
        ObjectNode kindSamples = bias.putObject("kindSamples");
        p.byKind().forEach(k -> {
            byKind.put(k.kind(), k.factor());
            kindSamples.put(k.kind(), k.samples());
        });
        return bias;
    }

    /** The goal as capabilities see it, with today's date and the profile as {@code person.*} tokens. */
    public GoalContext context(GoalState state) {
        return state.toContext(properties.today()).withPerson(tokens());
    }

    /**
     * The outcome of a profile correction: the value stored and how it was understood, in plain words ("Working
     * days: Monday to Thursday"), or why nothing was stored.
     */
    public record Correction(String key, JsonNode value, String understood, String problem) {

        public boolean ok() {
            return problem == null;
        }

        static Correction refused(String problem) {
            return new Correction(null, null, null, problem);
        }
    }

    /**
     * The person corrects their profile with the value a question showed them. Keys: {@code dailyHours} (the number),
     * {@code workingDays} (the days as stored, "MONDAY,TUESDAY", are used as they are; other words are read against
     * the current days, see {@link #workingDays}), {@code estimateBias} (the number, a factor: 1.5 means "I take 50%
     * longer than estimated"; read from the words only when no number is given).
     */
    public Correction correct(String key, Double number, String words, String principal) {
        String k = key == null ? "" : key.replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
        JsonNode value;
        String canonical;
        String understood;
        if (k.contains("hour") || k.contains("capacity")) {
            if (number == null || number <= 0 || number > 16) {
                return Correction.refused("set your daily hours: say a number of hours between 0 and 16");
            }
            canonical = "dailyHours";
            value = Json.toTree(number);
            understood = "Daily hours: " + hours(number);
        } else if (k.contains("day")) {
            // The days the question showed, or else the person's own words, never the model's paraphrase.
            String said = words == null || words.isBlank() ? key : words;
            List<String> days = storedDays(said).orElseGet(() -> workingDays(said, profile().workingDays()));
            if (days.isEmpty()) {
                return Correction.refused("set your working days: say which days you work");
            }
            canonical = "workingDays";
            value = Json.toTree(days);
            understood = "Working days: " + describeDays(days);
        } else if (k.contains("bias") || k.contains("estimate") || k.contains("pace") || k.contains("slow")
                || k.contains("fast")) {
            // The factor the question showed; the words are read only when no number came with them.
            Double f = number != null ? number : words == null ? null : factor(words).orElse(null);
            if (f == null || f < 0.25 || f > 4) {
                return Correction.refused("set your pace: say how far estimates are off, for example \"50% longer\"");
            }
            canonical = "estimateBias";
            value = Json.toTree(f);
            understood = "Pace: " + paceWords(f);
        } else {
            return Correction.refused("learn that: only your daily hours, your working days and how far "
                    + "estimates are off can be learned");
        }
        jdbc.sql("""
                INSERT INTO profile_overrides (key, value, by_principal, updated_at)
                VALUES (:k, CAST(:v AS jsonb), :by, :at)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, by_principal = EXCLUDED.by_principal,
                    updated_at = EXCLUDED.updated_at""")
                .param("k", canonical).param("v", Json.write(value)).param("by", principal)
                .param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
        ledger.recordSystem(Facts.PROFILE_CORRECTED, new Facts.ProfileCorrected(canonical, value, principal), principal);
        version.incrementAndGet();
        cached = null;
        return new Correction(canonical, value, understood, null);
    }

    /** 2.0 as "2", 1.5 as "1.5". */
    static String hours(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Working days as they are stored: day names in capitals, separated by commas ("MONDAY,TUESDAY"). */
    private static final Pattern STORED_DAYS = Pattern.compile(
            "(?:MONDAY|TUESDAY|WEDNESDAY|THURSDAY|FRIDAY|SATURDAY|SUNDAY)(?:,(?:MONDAY|TUESDAY|WEDNESDAY|THURSDAY"
                    + "|FRIDAY|SATURDAY|SUNDAY))*");

    /** The days of a text written as they are stored ("MONDAY,TUESDAY"), in week order; empty for any other text. */
    public static Optional<List<String>> storedDays(String text) {
        if (text == null || !STORED_DAYS.matcher(text.strip()).matches()) {
            return Optional.empty();
        }
        return Optional.of(Arrays.stream(text.strip().split(",")).map(DayOfWeek::valueOf).distinct()
                .sorted().map(DayOfWeek::name).toList());
    }

    /** Working days in the form {@link #correct} uses exactly as they are. */
    public static String stored(List<String> days) {
        return String.join(",", days.stream().map(DayOfWeek::valueOf).distinct().sorted().map(DayOfWeek::name)
                .toList());
    }

    /**
     * A pace in words with its direction: 2 is "tasks take you twice the estimate (you are slower)", 0.5 "tasks take
     * you half the estimate (you are faster)", 1.5 "tasks take you 1.5 times the estimate (50% longer: you are
     * slower)", 0.8 "tasks take you 0.8 times the estimate (20% less time: you are faster)".
     */
    public static String paceWords(double factor) {
        double f = clamp(factor);
        if (f == 1.0) {
            return "tasks take you as long as the estimate";
        }
        String direction = f > 1 ? "you are slower" : "you are faster";
        if (f == 2.0 || f == 0.5) {
            return "tasks take you " + (f == 2.0 ? "twice" : "half") + " the estimate (" + direction + ")";
        }
        long percent = Math.round(Math.abs(f - 1) * 100);
        return "tasks take you " + hours(f) + " times the estimate (" + percent + "% "
                + (f > 1 ? "longer" : "less time") + ": " + direction + ")";
    }

    /**
     * Day names as a person writes them: runs of three or more days as a range ("Monday to Thursday"), the rest
     * listed ("Tuesday and Thursday", "Monday to Wednesday and Friday").
     */
    public static String describeDays(List<String> days) {
        List<DayOfWeek> sorted = days.stream().map(DayOfWeek::valueOf).distinct().sorted().toList();
        if (sorted.size() == 7) {
            return "every day";
        }
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1).getValue() == sorted.get(j).getValue() + 1) {
                j++;
            }
            if (j - i >= 2) {
                parts.add(dayName(sorted.get(i)) + " to " + dayName(sorted.get(j)));
            } else {
                for (int d = i; d <= j; d++) {
                    parts.add(dayName(sorted.get(d)));
                }
            }
            i = j + 1;
        }
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    private static String dayName(DayOfWeek d) {
        return d.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /**
     * Words after which the days of a clause are excluded: negations, "except", "instead of", "stopped", "used to"
     * ("I used to work Fridays" no longer works Fridays), and "but" before days or the weekend ("every day but
     * Sunday", "every day but the weekend").
     */
    private static final Pattern EXCLUSION = Pattern.compile(
            "\\b(?:don'?t|do not|doesn'?t|can'?t|cannot|won'?t|isn'?t|aren'?t|never|no longer|not|no|nothing|none"
                    + "|except(?: for| on)?"
                    + "|apart from|other than|excluding|besides|instead of|rather than|off on|free on|but not|stopped"
                    + "|quit|gave up|used to|but(?=\\s+(?:on\\s+)?(?:the\\s+)?(?:(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*"
                    + "|weekends?)\\b))\\b");
    /**
     * Days, then the person, before a negation of working: "Fridays I don't work", "weekends I never work", "on
     * Mondays we don't work". The days are the ones not worked, though they come before the negation.
     */
    private static final Pattern DAYS_BEFORE_PERSON = Pattern.compile(
            "\\s*(?:(?:and|but|so|also|now|then)\\s+)*(?:on\\s+)?(?:(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*|weekends?"
                    + "|weekdays?)(?:\\s*(?:,|&|and|or)\\s*(?:on\\s+)?(?:(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*"
                    + "|weekends?|weekdays?))*\\s*,?\\s*(?:i|we)\\s+(?:(?:really|usually|just|actually|now|also)\\s+)?");
    /** A verb of working right after a negation: "don't work", "never work on". */
    private static final Pattern WORK_AFTER = Pattern.compile("\\s*work\\b");
    /**
     * Only day names (with "and", "or", commas, "on"), followed by "is", "are" or "will be": the days a negation
     * right after them is said of ("Tuesday is no longer a working day", "Mondays and Fridays aren't", "Tuesday is
     * not a working day anymore").
     */
    private static final Pattern DAYS_AS_SUBJECT = Pattern.compile(
            "\\s*(?:(?:and|but|so|also|now|then)\\s+)*(?:(?:on\\s+)?(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*"
                    + "(?:\\s*(?:,|&|and|or)\\s*|\\s+|(?='s|'re)))+(?:(?:is|are|was|were|will\\s+be)"
                    + "(?:\\s+(?:now|really|just))?\\s*|'s\\s*|'re\\s*)");
    /**
     * True when a negation is said of the days just before it: they are its subject with "is" or "are" ("Tuesday is
     * no longer"), or the negation holds the verb ("Tuesday isn't"). "Monday not Friday" is no such statement.
     */
    private static boolean saidOfDays(String before, String negation) {
        return DAYS_AS_SUBJECT.matcher(before).matches() || NEGATED_VERB.matcher(negation).matches()
                && DAYS_AS_SUBJECT.matcher(before + "is ").matches();
    }

    /** "Isn't" and "aren't": the verb is in the negation itself ("Tuesday isn't a working day"). */
    private static final Pattern NEGATED_VERB = Pattern.compile("(?:is|are)n'?t");
    /**
     * Words that make every day of their clause a day off, wherever the day stands: "I'm busy on Tuesdays",
     * "Fridays are my day off", "I have Fridays off", "I'm away at weekends", "I have football on Tuesdays" (but not
     * "I have time on Tuesdays" or "I have 2 hours on weekdays").
     */
    private static final Pattern DAY_OFF = Pattern.compile(
            "\\b(?:off|busy|unavailable|not available|away|days? off)\\b"
                    + "|\\bhave\\s+(?!(?:some\\s+|more\\s+|free\\s+|spare\\s+|the\\s+)?(?:time|hours?|capacity|\\d))"
                    + "[^,;.!?]*?\\bon\\b");
    private static final Pattern SIX_DAYS = Pattern.compile("\\b(?:six|6) days\\b");
    /** Days named in addition to the working days already set: "I work Saturdays too", "also Sundays". */
    private static final Pattern ADDS = Pattern.compile("\\b(?:too|also|as well)\\b");
    /** Days off that are the only ones: "my only day off", "the only day I don't work", "I only take Mondays off". */
    private static final Pattern ONLY_OFF = Pattern.compile(
            "\\bonly\\s+days?\\s+off\\b|\\bonly\\s+(?:days?|one)\\s+i\\s+(?:don'?t|do not|never)\\s+work\\b"
                    + "|\\bonly\\s+(?:take|have|get)\\s+[^,;.!?]*\\boff\\b");
    /**
     * Ends a clause: punctuation, and "but", "and" or "or" unless a day follows ("Monday and Friday" is one list, so
     * is "every day but Sunday"; "I'm off Fridays and work Saturdays" is two clauses).
     */
    private static final Pattern CLAUSE_END = Pattern.compile(
            "[,;.!?]|\\b(?:but|and|or)\\b(?!\\s+(?:on\\s+|the\\s+)?(?:mon|tue|wed|thu|fri|sat|sun|week|every)[a-z]*\\b)");
    /**
     * Every day of the week at once: "every day", "every single day", "all days", "any day", "the whole week", "all
     * week", "daily", "seven days". "Daily" next to hours or capacity ("my daily hours", "3 hours daily", "daily
     * capacity") speaks of hours, not of days.
     */
    private static final Pattern EVERY_DAY = Pattern.compile(
            "\\b(?:every\\s+(?:single\\s+)?day|everyday|all\\s+(?:the\\s+)?days|any\\s+(?:other\\s+)?day"
                    + "|(?:the\\s+)?whole\\s+week|all\\s+week|seven days|7 days)\\b"
                    + "|(?<!\\b(?:hours?|hrs?|h|minutes?|mins?)\\s{1,3})\\bdaily\\b(?!\\s+(?:hours?|hrs?|capacity|limit"
                    + "|budget|time|amount|work\\s*load|workload|goal|target|maximum|max)\\b)");
    /** Names a whole set of days at once: a range, weekdays, weekends, every day, six days. */
    private static final Pattern WHOLE_SET = Pattern.compile(
            EVERY_DAY.pattern() + "|\\b(?:six days|6 days|weekdays?|weekends?)\\b"
                    + "|\\b(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\\s*(?:-|to|through|thru|until|till)\\s*"
                    + "(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b");

    /**
     * The working days a sentence states, clause by clause. In a clause, the days before an exclusion word count and
     * the days after it are excluded ("every day except Sunday", "Mon to Thu, not Fridays", "work Sundays instead of
     * Mondays", "every day but the weekend"), except that days a negation is said of are excluded ("Tuesday is no
     * longer a working day", "Tuesday isn't a working day any more", "Fridays I don't work", "weekends I never work"),
     * and days the person "used to" work are taken away ("I used to work Fridays, now I work Saturdays"); a clause that
     * makes days days off excludes every day it names ("I'm busy on Tuesdays", "Fridays are my day off", "I'm off
     * Fridays"). A sentence that names a whole set of days (a range, weekdays, every day) states them all, minus its
     * exclusions. A sentence that names single days and excludes others changes {@code current} ("I don't work Fridays
     * anymore but I do Saturdays"), as does one that only excludes days ("I stopped working Fridays") or adds them with
     * "too", "also" or "as well". "Six days a week, not Sunday" is every day but that one, and so are "Sunday is my
     * only day off", "all days but Sunday", "any day except Sunday" and "the whole week except Sunday". Empty when the
     * sentence names no day or nothing is left.
     */
    public static List<String> workingDays(String said, List<String> current) {
        String t = said == null ? "" : said.toLowerCase(Locale.ROOT).replace('\u2019', '\'').replace('\u2018', '\'');
        StringBuilder positive = new StringBuilder();
        StringBuilder negative = new StringBuilder();
        Matcher end = CLAUSE_END.matcher(t);
        int from = 0;
        while (from <= t.length()) {
            int to = end.find(from) ? end.start() : t.length();
            String clause = t.substring(from, to);
            if (DAY_OFF.matcher(clause).find()) {
                negative.append(clause).append(" , ");
            } else {
                Matcher exclusion = EXCLUSION.matcher(clause);
                if (exclusion.find() && (saidOfDays(clause.substring(0, exclusion.start()), exclusion.group())
                        || DAYS_BEFORE_PERSON.matcher(clause.substring(0, exclusion.start())).matches()
                        && WORK_AFTER.matcher(clause.substring(exclusion.end())).lookingAt())) {
                    // "Tuesday is no longer a working day", "Tuesdays aren't working days any more", "Fridays I don't
                    // work": the days the negation is said of are the ones taken away.
                    negative.append(clause).append(" , ");
                } else if (exclusion.find(0)) {
                    // An exclusion runs to the end of its clause: "except Sunday, and I start at nine".
                    positive.append(clause, 0, exclusion.start()).append(" , ");
                    negative.append(clause.substring(exclusion.end())).append(" , ");
                } else {
                    positive.append(clause).append(" , ");
                }
            }
            if (to >= t.length()) {
                break;
            }
            from = Math.max(end.end(), to + 1);
        }
        List<String> named = days(positive.toString());
        List<String> excluded = days(negative.toString());
        boolean adds = ADDS.matcher(t).find();
        boolean whole = WHOLE_SET.matcher(positive).find();
        Set<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
        if (!excluded.isEmpty() && ONLY_OFF.matcher(t).find()) {
            // "Monday is my only day off": every other day is a working day
            result.addAll(EnumSet.allOf(DayOfWeek.class));
        } else if (named.isEmpty() && excluded.size() == 1 && SIX_DAYS.matcher(t).find()) {
            result.addAll(EnumSet.allOf(DayOfWeek.class));
        } else if (named.isEmpty() || adds || !excluded.isEmpty() && !whole) {
            if (named.isEmpty() && excluded.isEmpty()) {
                return List.of();
            }
            current.forEach(d -> result.add(DayOfWeek.valueOf(d)));
        }
        named.forEach(d -> result.add(DayOfWeek.valueOf(d)));
        excluded.forEach(d -> result.remove(DayOfWeek.valueOf(d)));
        return result.stream().map(DayOfWeek::name).toList();
    }

    /**
     * Wording that states a habit ("Fridays", "weekends", "every Friday", "usually", "never", "from now on", "any
     * more", "no longer", "my working days", "my day off"): a day named once in such a clause names the habit, not one
     * date.
     */
    private static final Pattern HABIT = Pattern.compile(
            "\\b(?:mon|tues|wednes|thurs|fri|satur|sun)days\\b|\\b(?:weekdays|weekends|weeknights|workdays)\\b"
                    + "|\\b(?:every|each)\\s+(?:single\\s+)?(?:day|week|mon|tue|wed|thu|fri|sat|sun|weekday|weekend)"
                    + "|\\b(?:usually|always|never|normally|generally|typically|regularly|nowadays|these days)\\b"
                    + "|\\bfrom now on\\b|\\bgoing forward\\b|\\bany ?more\\b|\\bno longer\\b"
                    + "|\\bwork(?:ing)? ?days\\b|\\bmy\\s+(?:only\\s+)?days?\\s+off\\b");
    /** A specific day: "this Friday", "next Monday", "tomorrow", "on the 20th", "December 4". */
    private static final Pattern ONE_DATE = Pattern.compile(
            "\\b(?:this|next|coming|last)\\s+(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b"
                    + "|\\b(?:today|tomorrow|tonight|yesterday)\\b|\\b\\d{1,2}(?:st|nd|rd|th)\\b"
                    + "|\\b(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?"
                    + "|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\.?\\s+\\d{1,2}\\b"
                    + "|\\bon\\s+the\\s+\\d{1,2}\\b|\\b\\d{4}-\\d{2}-\\d{2}\\b");
    /** Part of a day: "afternoons", "Wednesday mornings", "half days". */
    private static final Pattern PART_OF_DAY = Pattern.compile(
            "\\b(?:mornings?|afternoons?|evenings?|half[- ]days?|half\\s+a\\s+day|lunch\\s*times?)\\b");
    /** A while: "this week", "until Christmas", "for now", "for two weeks", "over the holidays", "temporarily". */
    private static final Pattern WHILE = Pattern.compile(
            "\\b(?:this|next|coming)\\s+(?:week|weekend|month|fortnight|year|term|semester)\\b"
                    + "|\\b(?:until|till|til|through|thru)\\b(?!\\s*(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b)[^,;.!?]*"
                    + "|\\bfor\\s+(?:now|a\\s+while|the\\s+time\\s+being|the\\s+next\\b[^,;.!?]*|the\\s+rest\\s+of\\b"
                    + "[^,;.!?]*|(?:a|one|two|three|four|a\\s+few|\\d+)\\s+(?:days?|weeks?|months?))"
                    + "|\\btemporar\\w*|\\b(?:during|over)\\s+(?:the\\s+)?(?:holidays?|christmas|summer|easter|winter"
                    + "|break)\\b|\\bthese\\s+(?:two|few|next)\\s+(?:days|weeks)\\b");
    /** A month or a season: "in December", "during the summer", "over winter", "in the summer months". */
    private static final Pattern SEASON = Pattern.compile(
            "\\b(?:in|during|over|throughout)\\s+(?:the\\s+)?(?:january|february|march|april|may|june|july|august"
                    + "|september|october|november|december|summer|winter|spring|autumn|fall)(?:\\s+months?"
                    + "|\\s+holidays?|\\s+break)?\\b");
    /**
     * A condition or a time that starts or ends what was said: "while the kids are off school", "during exams", "once
     * school starts", "after Christmas", "before the move", "since I'm sick", "starting Monday", "from January", "from
     * next week". "After work" and "before bed" say when in a day, not for how long, and "as long as" after a
     * comparison ("twice as long as you think", "1.5x as long as estimated") compares, it sets no condition.
     */
    private static final Pattern CONDITION = Pattern.compile(
            "\\b(?:while|whilst|once|during)\\s+[^,;.!?]+"
                    + "|(?<!\\b(?:twice|double|half|thrice|times|\\dx)\\s{1,3})\\bas long as\\b"
                    + "(?!\\s+(?:the\\s+|my\\s+|your\\s+|you\\s+|i\\s+|it\\s+)?(?:estimat\\w*|think|thought|expect\\w*"
                    + "|plann\\w*|predict\\w*|guess\\w*|said|says|should|would)\\b)[^,;.!?]+"
                    + "|\\b(?:when|after|before|since)\\s+(?!(?:work|dinner|lunch|breakfast|bed|school)\\b)[^,;.!?]+"
                    + "|\\b(?:starting|beginning|as of)\\b(?!\\s+(?:now|today)\\b)[^,;.!?]*"
                    + "|\\bfrom\\s+(?:next|this|tomorrow|(?:the\\s+)?\\d{1,2}(?:st|nd|rd|th)|january|february|march|april"
                    + "|may|june|july|august|september|october|november|december|mid|early|late|the\\s+(?:start"
                    + "|beginning|end|middle))\\b[^,;.!?]*");
    /**
     * Part of the weeks: "every other Friday", "alternate Fridays", "the first Monday of each month", "most Fridays".
     */
    private static final Pattern PART_OF_WEEKS = Pattern.compile(
            "\\b(?:every\\s+(?:other|second|third)|alternate|alternating)\\s+\\w+"
                    + "|\\b(?:first|second|third|fourth|last)\\s+(?:mon|tues|wednes|thurs|fri|satur|sun)day\\s+of\\s+"
                    + "(?:the|each|every|a)\\s+month\\b"
                    + "|\\b(?:most|some|a few|several|many|certain)\\s+(?:mon|tues|wednes|thurs|fri|satur|sun)days\\b");
    /** A reason that holds for once: being sick, an appointment, a funeral, being on holiday. */
    private static final Pattern ONE_OFF = Pattern.compile(
            "\\bon\\s+(?:holiday|vacation|leave|sick leave)\\b|\\b(?:sick|ill|unwell|flu|a cold|injured|dentist"
                    + "|doctor'?s?|appointment|funeral|wedding|surgery|operation|hospital|bank holiday|birthday"
                    + "|exams?|conference|trip)\\b");
    /** One piece of work, not a pace: "my last task", "that task". */
    private static final Pattern ONE_CASE = Pattern.compile(
            "\\b(?:my|the|that|this)\\s+(?:last|latest|previous|recent|current)\\s+(?:task|one|job|project)\\b"
                    + "|\\b(?:that|this)\\s+(?:task|job)\\b");
    /** A single day named with "on": "on Wednesday" ("on Wednesdays" is a habit). */
    private static final Pattern ON_ONE_DAY = Pattern.compile("\\bon\\s+(?:mon|tues|wednes|thurs|fri|satur|sun)day\\b");
    /** The days a number of hours is stated for: "on weekends", "at weekends", "on Saturdays". */
    private static final Pattern HOURS_SCOPE = Pattern.compile(
            "\\b(?:on|at|during)\\s+(?:the\\s+)?(?:weekends?|(?:mon|tues|wednes|thurs|fri|satur|sun)days?)\\b");
    /** Ends the part of a sentence a one-off reason is quoted with. */
    private static final Pattern REASON_END = Pattern.compile("[,;.!?]|\\bbut\\b");

    /**
     * The words of a profile statement that limit it to one date, a while, part of the weeks or part of a day, or that
     * make it a one-off ("in December", "this week", "until Christmas", "while the kids are off", "every other
     * Friday", "I'm sick", "my dentist appointment", "on Wednesday", "my last task"), as the person wrote them; empty
     * when the words state the value with no such limit. {@code key} is the profile value the statement changes:
     * parts of a day and a single day named with "on" limit working days only, one piece of work limits the pace
     * only. A profile value is one lasting value, so these words only make the question that asks about the change
     * say so; every change a sentence asks for is asked about.
     */
    public static Optional<String> limitWording(String said, String key) {
        if (said == null || said.isBlank()) {
            return Optional.empty();
        }
        String t = lowerSameLength(said);
        boolean days = "workingDays".equals(key);
        List<Pattern> limits = new ArrayList<>(List.of(ONE_DATE, WHILE, CONDITION, SEASON, PART_OF_WEEKS));
        if (days) {
            limits.add(PART_OF_DAY);
        }
        for (Pattern limit : limits) {
            Matcher m = limit.matcher(t);
            if (m.find()) {
                return Optional.of(said.substring(m.start(), m.end()).strip());
            }
        }
        Matcher reason = ONE_OFF.matcher(t);
        if (reason.find()) {
            return Optional.of(around(said, t, reason.start(), reason.end()));
        }
        if ("estimateBias".equals(key)) {
            Matcher one = ONE_CASE.matcher(t);
            if (one.find()) {
                return Optional.of(said.substring(one.start(), one.end()).strip());
            }
        }
        if (days) {
            Matcher day = ON_ONE_DAY.matcher(t);
            while (day.find()) {
                if (!HABIT.matcher(around(t, t, day.start(), day.end())).find()) {
                    return Optional.of(said.substring(day.start(), day.end()).strip());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The days a statement of hours a day is limited to ("3 hours a day on weekends" is "on weekends"); weekdays and
     * every day are no limit. Empty when it names no such days. The person has one number of hours for every working
     * day, so the question that asks about the change says so.
     */
    public static Optional<String> hoursScope(String said) {
        if (said == null) {
            return Optional.empty();
        }
        Matcher m = HOURS_SCOPE.matcher(lowerSameLength(said));
        return m.find() ? Optional.of(said.substring(m.start(), m.end()).strip()) : Optional.empty();
    }

    /** The part of {@code said} around {@code [start, end)} up to the nearest punctuation or "but" on either side. */
    private static String around(String said, String lower, int start, int end) {
        int from = 0;
        Matcher before = REASON_END.matcher(lower).region(0, start);
        while (before.find()) {
            from = before.end();
        }
        Matcher after = REASON_END.matcher(lower).region(end, lower.length());
        int to = after.find() ? after.start() : lower.length();
        String part = said.substring(from, to).strip();
        return part.length() <= 60 ? part : said.substring(start, end).strip();
    }

    /** Lower case letter by letter with straight apostrophes, so every index is an index into {@code said}. */
    private static String lowerSameLength(String said) {
        char[] chars = (said == null ? "" : said).toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = Character.toLowerCase(chars[i]);
            chars[i] = c == '\u2019' || c == '\u2018' ? '\'' : c;
        }
        return new String(chars);
    }

    /** Pace words; "no longer" and "any longer" say that something stopped, not how long tasks take. */
    private static final Pattern PACE = Pattern.compile(
            "%|\\d\\s*x\\b|\\b(?:percent|(?<!\\bno )(?<!\\bany )longer|slower|faster|quicker|times|twice|double"
                    + "|half the time|half as|less time|more time|estimat\\w*)\\b");

    /**
     * The sentence speaks of capacity per day ("2 hours a day", "each day", "daily", "on weekdays", "every evening"),
     * read by {@link Durations#perDay}, the same reader the assistant uses.
     */
    public static boolean perDay(String said) {
        return Durations.perDay(said);
    }

    /**
     * The sentence speaks of pace, how long tasks take compared with their estimates ("50% longer", "slower",
     * "twice as long", "1.5x", "than estimated"), never of hours per day. The same words the assistant reads as pace.
     */
    public static boolean pace(String said) {
        return said != null && PACE.matcher(said.toLowerCase(Locale.ROOT)).find();
    }

    /** Nearest kind by meaning (embeddings), or by keywords when no embedding model is available. */
    String kind(String title) {
        return kindOfTitle.computeIfAbsent(title, t -> {
            Optional<float[]> v = embeddings.embed(t);
            String best = Person.KINDS.getFirst();
            double bestScore = -1;
            for (String k : Person.KINDS) {
                double score = v.flatMap(x -> embeddings.embed(k).map(y -> Embeddings.cosine(x, y)))
                        .orElseGet(() -> Embeddings.lexicalSimilarity(t, k));
                if (score > bestScore) {
                    bestScore = score;
                    best = k;
                }
            }
            return best;
        });
    }

    /** The pace: the geometric mean of actual over estimated hours, clamped; 1.0 without outcomes. */
    static double geometricBias(List<Outcome> outcomes) {
        if (outcomes.isEmpty()) {
            return 1.0;
        }
        double sum = 0;
        for (Outcome o : outcomes) {
            sum += Math.log(o.actual() / o.estimated());
        }
        return clamp(Math.exp(sum / outcomes.size()));
    }

    /** A factor kept between 0.25 and 4, rounded to two decimals. */
    static double clamp(double factor) {
        return Math.round(Math.max(0.25, Math.min(4, factor)) * 100) / 100.0;
    }

    private static final Pattern WEEKDAYS = Pattern.compile("\\bweekdays?\\b");
    private static final Pattern RANGE = Pattern.compile(
            "\\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\s*(?:[-\u2013\u2014]|to|through|thru|until|till)\\s*"
                    + "(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b");
    /**
     * Short day names. "Sat", "sun" and "wed" are ordinary words too, so those days count by their short name only
     * next to another day ("mon, wed and fri").
     */
    private static final Pattern SHORT_DAY = Pattern.compile("\\b(mon|tue|tues|thu|thur|thurs|fri)s?\\b");
    private static final Pattern AMBIGUOUS_DAY = Pattern.compile("\\b(wed|weds|sat|sun)s?\\b");
    /** A day's name or short name, for telling whether an ambiguous short name stands in a list of days. */
    private static final Pattern ANY_DAY = Pattern.compile(
            "(?:mon|tue|tues|wed|weds|thu|thur|thurs|fri|sat|sun)s?|(?:mon|tues|wednes|thurs|fri|satur|sun)days?");
    private static final Set<String> LIST_JOINERS =
            Set.of("and", "or", "to", "through", "thru", "until", "till", "plus");

    /**
     * Every day a text names, in week order: day names and their short forms ("Tues", "Thurs", and "wed", "sat" or
     * "sun" in a list of days), ranges ("Mon-Thu", "Friday to Monday"), "weekdays", "weekends", "every day" and
     * "daily". All mentions count together, so "Monday to Friday and Saturday" is six days.
     */
    public static List<String> days(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        Set<DayOfWeek> found = EnumSet.noneOf(DayOfWeek.class);
        if (EVERY_DAY.matcher(t).find()) {
            found.addAll(EnumSet.allOf(DayOfWeek.class));
        }
        if (WEEKDAYS.matcher(t).find()) {
            DEFAULT_DAYS.forEach(d -> found.add(DayOfWeek.valueOf(d)));
        }
        if (t.contains("weekend")) {
            found.add(DayOfWeek.SATURDAY);
            found.add(DayOfWeek.SUNDAY);
        }
        Matcher range = RANGE.matcher(t);
        while (range.find()) {
            DayOfWeek to = dayOf(range.group(2));
            for (DayOfWeek d = dayOf(range.group(1)); ; d = d.plus(1)) {
                found.add(d);
                if (d == to) {
                    break;
                }
            }
        }
        Matcher shortDay = SHORT_DAY.matcher(t);
        while (shortDay.find()) {
            found.add(dayOf(shortDay.group(1).substring(0, 3)));
        }
        Matcher ambiguous = AMBIGUOUS_DAY.matcher(t);
        while (ambiguous.find()) {
            if (nextToAnotherDay(t, ambiguous.start(), ambiguous.end())) {
                found.add(dayOf(ambiguous.group(1).substring(0, 3)));
            }
        }
        for (DayOfWeek d : DayOfWeek.values()) {
            if (t.contains(d.name().toLowerCase(Locale.ROOT))) {
                found.add(d);
            }
        }
        return found.stream().map(DayOfWeek::name).toList();
    }

    /** True when the nearest word before or after a span, past "and", "or" and "to", is the name of a day. */
    private static boolean nextToAnotherDay(String t, int start, int end) {
        String[] before = t.substring(0, start).split("[^a-z]+");
        for (int i = before.length - 1; i >= 0; i--) {
            if (before[i].isEmpty() || LIST_JOINERS.contains(before[i])) {
                continue;
            }
            if (ANY_DAY.matcher(before[i]).matches()) {
                return true;
            }
            break;
        }
        for (String w : t.substring(end).split("[^a-z]+")) {
            if (w.isEmpty() || LIST_JOINERS.contains(w)) {
                continue;
            }
            return ANY_DAY.matcher(w).matches();
        }
        return false;
    }

    /** A percentage, the whole number: "1000%" is never read as "000%". */
    private static final Pattern PERCENT = Pattern.compile("(?<![\\d.,])(\\d+(?:\\.\\d+)?)\\s*(?:%|percent)");
    /** A factor, the whole number: "10x" is ten times, "12 times" twelve, never "0x" or "2 times". */
    private static final Pattern TIMES = Pattern.compile(
            "(?<![\\d.,])\\b(\\d+(?:\\.\\d+)?|two|three|four)\\s*(?:x|times)\\b");
    /** Words that say tasks take less time than estimated. */
    private static final Pattern FASTER = Pattern.compile(
            "\\b(?:fast|faster|quick|quicker|quickly|shorter|sooner|less time|under the estimate)\\b"
                    + "|\\bless than (?:the )?estimat");
    /** Words that say tasks take more time than estimated. */
    private static final Pattern SLOWER = Pattern.compile(
            "\\b(?:(?<!\\bno )(?<!\\bany )longer|slower|slowly|more time|as long|extra time|over the estimate"
                    + "|behind)\\b");
    /** Estimates that are too long mean the work goes faster than estimated, and the other way round. */
    private static final Pattern ESTIMATES_TOO_LONG = Pattern.compile(
            "\\boverestimat|\\bestimat\\w*\\b.*\\btoo (?:long|high|big|large|much|generous|pessimistic)\\b");
    private static final Pattern ESTIMATES_TOO_SHORT = Pattern.compile(
            "\\bunderestimat|\\bestimat\\w*\\b.*\\btoo (?:short|low|small|little|optimistic)\\b");
    /** Words that qualify an amount ("no less than 50% slower"), not the direction of the pace. */
    private static final Pattern AMOUNT_QUALIFIER = Pattern.compile(
            "\\b(?:no|not) (?:less|more) than\\b|\\bat (?:least|most)\\b");
    /** After a factor: the estimate it multiplies ("1.5x the estimate", "twice what I estimate"). */
    private static final Pattern OF_ESTIMATE = Pattern.compile(
            "^\\s*(?:(?:of\\s+)?(?:the|my|your|an|its)\\s+)?(?:estimat|planned)|^\\s*what\\s+(?:i|you)\\s+estimat");
    private static final Pattern TWICE = Pattern.compile("\\b(twice|double)\\b");
    private static final Pattern HALF = Pattern.compile("\\bhalf (?:the time|as long|(?:of )?(?:the|my) estimat)");

    /**
     * Which way the words say the pace goes: true for faster than estimated ("faster", "quicker", "less time",
     * "estimates are too long", "I overestimate"), false for slower ("longer", "slower", "more time", "as long",
     * "estimates are too short", "I underestimate"). Empty when the words say neither, or both. Negated clauses do not
     * count ("I'm not faster, I'm slower" is slower).
     */
    public static Optional<Boolean> faster(String words) {
        return direction(readable(words));
    }

    private static Optional<Boolean> direction(String t) {
        boolean faster = ESTIMATES_TOO_LONG.matcher(t).find() || !ESTIMATES_TOO_SHORT.matcher(t).find()
                && FASTER.matcher(t).find();
        boolean slower = ESTIMATES_TOO_SHORT.matcher(t).find() || !ESTIMATES_TOO_LONG.matcher(t).find()
                && SLOWER.matcher(t).find();
        return faster == slower ? Optional.empty() : Optional.of(faster);
    }

    /**
     * True when pace words give a number ("30%", "1.5x", "three times", "twice", "half"), whether or not the
     * kernel can read which way it goes.
     */
    public static boolean paceAmount(String words) {
        String t = readable(words);
        return PERCENT.matcher(t).find() || TIMES.matcher(t).find() || TWICE.matcher(t).find()
                || t.matches("(?s).*\\bhalf\\b.*");
    }

    /**
     * A self-correction: "no", "sorry", "actually", "I mean", "make that", "or rather", "wait" after a comma, a full
     * stop or a dash (or at the start), as in "30% longer, no, 50% longer".
     */
    private static final Pattern PACE_CORRECTION = Pattern.compile(
            "(?:^|[,.;!?]|\\s[-\\u2013\\u2014]{1,2})\\s*(?:no|nope|sorry|actually|i mean|make that|make it|or rather"
                    + "|rather|wait|correction|scratch that)\\b");
    /** A pace said to have changed: "it used to be 30% slower, now 50%", "I was twice as slow, these days 1.5x". */
    private static final Pattern NOW_AFTER_PAST = Pattern.compile(
            "\\b(?:used to|previously|in the past|last (?:year|month|week)|was|were)\\b.*?\\b(now|these days"
                    + "|nowadays)\\b");

    /**
     * The words a pace is read from: those after the last self-correction that a pace number follows ("30% longer,
     * no, 50% longer" is read from "50% longer"), and after "now" when an earlier pace is said to be past ("it used to
     * be 30% slower, now 50%" is read from "now 50%"). The whole words, lower case, when there is neither.
     */
    static String latest(String words) {
        String t = words == null ? "" : words.toLowerCase(Locale.ROOT).replace('\u2019', '\'');
        int from = 0;
        Matcher c = PACE_CORRECTION.matcher(t);
        while (c.find()) {
            if (paceAmounts(t.substring(c.end())) > 0) {
                from = c.end();
            }
        }
        Matcher now = NOW_AFTER_PAST.matcher(t);
        while (now.find()) {
            if (now.start(1) > from && paceAmounts(t.substring(now.start(1))) > 0) {
                from = now.start(1);
            }
        }
        return t.substring(from);
    }

    /**
     * How many pace numbers the words give ("30%", "1.5x", "three times", "twice", "half the time"), negated ones
     * included: "30% longer, no, 50% longer" gives two.
     */
    public static int paceAmounts(String words) {
        String t = words == null ? "" : words.toLowerCase(Locale.ROOT);
        int n = 0;
        for (Pattern amount : List.of(PERCENT, TIMES, TWICE, HALF)) {
            Matcher m = amount.matcher(t);
            while (m.find()) {
                n++;
            }
        }
        return n;
    }

    /** The words lower case, without amount qualifiers ("no less than") and without negated clauses. */
    private static String readable(String words) {
        return withoutNegatedClauses(AMOUNT_QUALIFIER.matcher(words == null ? ""
                : words.toLowerCase(Locale.ROOT).replace('\u2019', '\'')).replaceAll(" "));
    }

    /**
     * A pace factor in plain words: "50% longer" is 1.5, "20% faster" or "30% less time" is 0.8 or 0.7, "150% of the
     * estimate" is 1.5, "twice as long" is 2, "twice as fast" and "half the time" are 0.5, "1.5x the estimate" 1.5,
     * "3x faster" 0.33. Phrased about the estimates, the direction turns: "estimates are 30% too long" and "I
     * overestimate by 30%" are 0.7, "estimates are 30% too short" and "I underestimate by 30%" are 1.3. A negated
     * clause does not count ("I'm not faster, I'm 30% slower" is 1.3); numbers are read whole ("10x longer" is 10,
     * kept at 4). The number is the one the words end on (see {@link #latest}: "30% longer, no, 50% longer" is 1.5,
     * "it used to be 30% slower, now 50%" is 1.5), its direction read from those words or else from the whole
     * sentence. Empty when the words give no number, or give one without saying which way it goes ("estimates are
     * double what I actually need", "50% more productive", "I beat the estimates by 20%"): the kernel does not guess
     * a direction.
     */
    public static Optional<Double> factor(String words) {
        String whole = readable(words);
        String t = readable(latest(words));
        Optional<Boolean> faster = direction(t).or(() -> direction(whole));
        Matcher m = PERCENT.matcher(t);
        if (m.find()) {
            double p = Double.parseDouble(m.group(1)) / 100;
            // "150% of the estimate" is the whole time; "50% longer" is on top of it.
            if (t.substring(m.end()).matches("\\s*of\\b.*")) {
                return Optional.of(clamp(p));
            }
            return faster.map(f -> clamp(f ? 1 - p : 1 + p));
        }
        m = TIMES.matcher(t);
        if (m.find()) {
            double f = switch (m.group(1)) {
                case "two" -> 2;
                case "three" -> 3;
                case "four" -> 4;
                default -> Double.parseDouble(m.group(1));
            };
            if (OF_ESTIMATE.matcher(t.substring(m.end())).find()) {
                return Optional.of(clamp(f));
            }
            return faster.map(quick -> clamp(quick ? 1 / f : f));
        }
        if (HALF.matcher(t).find()) {
            return Optional.of(0.5);
        }
        m = TWICE.matcher(t);
        if (m.find()) {
            if (OF_ESTIMATE.matcher(t.substring(m.end())).find()) {
                return Optional.of(2.0);
            }
            return faster.map(quick -> quick ? 0.5 : 2.0);
        }
        return Optional.empty();
    }

    /** A negation: "not", "never", "no" and the "n't" words. */
    private static final Pattern NEGATION = Pattern.compile(
            "\\b(?:not|never|no)\\b|n't\\b|\\b(?:dont|doesnt|didnt|isnt|arent|cant|wont)\\b");
    /** Ends a clause; a full stop or comma inside a number ("1.5x") does not. */
    private static final Pattern PACE_CLAUSE_END = Pattern.compile("[;!?]|[.,](?!\\d)|\\bbut\\b");

    /**
     * The words without their negated clauses, so "I'm not faster, I'm 30% slower" is read from "I'm 30% slower"
     * alone. Amount qualifiers ("no less than") are taken out before.
     */
    private static String withoutNegatedClauses(String t) {
        StringBuilder kept = new StringBuilder();
        for (String clause : PACE_CLAUSE_END.split(t)) {
            if (!NEGATION.matcher(clause).find()) {
                kept.append(clause).append(" , ");
            }
        }
        return kept.toString();
    }

    private static DayOfWeek dayOf(String prefix) {
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return d;
            }
        }
        throw new IllegalArgumentException(prefix);
    }

    private static String capitalise(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
