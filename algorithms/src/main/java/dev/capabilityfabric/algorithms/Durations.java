package dev.capabilityfabric.algorithms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads amounts of time from everyday English, in hours, deterministically: "2 hours", "1.5h", "90 minutes", "half
 * an hour", "a quarter of an hour", "an hour and a half", "two and a half hours", "1h30", "1h30m", "1 h 30", "1:30
 * hours", "1 hour 30 minutes", "2 hours and 15 minutes", "1½ hours", "1/2 hour", "2 1/2 hours", "2 and 1/2
 * hours", "3/4 of an hour". Number words go up to ninety-nine ("forty-five minutes"). An amount that names no single
 * number ("a couple of hours", "a few hours", "2 or 3 hours", "2-3 hours", "between 2 and 3 hours") is a
 * {@link Amount#vague() vague} amount: the words state a duration, but no number of hours. Also tells whether the
 * words speak of an amount per day ("a day", "per day", "/day", "daily", "each day", "every day", "on weekdays",
 * "each weekday", "every evening") and reads the hours they give per day.
 *
 * <p>{@link #amounts} reads every amount as said. {@link #affirmed} reads only the amounts the words hold true: a
 * negated amount ("I don't have 3 hours a day"), the old value of a change ("from 4 hours to 2"), an amount the
 * person takes back ("3 hours, sorry, 2") or that is the subject of a negated state ("3 hours a day isn't enough") is
 * left out, and a bare number that stands for an amount ("only 2" of "not 3 hours, only 2") counts in that amount's
 * unit. {@link #perDayHours} reads the affirmed amounts. {@link #simple} tells whether the words state the time one
 * task took so plainly that it may be used without asking, {@link #each} reads the time each of several tasks took
 * ("they took 2 hours each") as plainly, and {@link #simplePerDay} tells the same of an amount per day.
 */
public final class Durations {

    private Durations() {
    }

    /**
     * One amount of time in a text: its hours (0 for a vague amount), whether it is vague, and where it starts and
     * ends in the text.
     */
    public record Amount(double hours, boolean vague, int start, int end) {
    }

    private static final String UNITS = "one|two|three|four|five|six|seven|eight|nine";
    private static final String NUMBER_WORD = "(?:(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)"
            + "(?:[- ](?:" + UNITS + "))?|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen"
            + "|nineteen|zero|" + UNITS + ")";
    /** A number in digits: "2", "1.5", "1,5", "2,000" (two thousand: see {@link #number}), "1½", "½". */
    private static final String DIGITS = "\\d+(?:[.,]\\d+)?½?|½";
    /** A number: in digits or in words. */
    private static final String NUMBER = "(?:" + DIGITS + "|" + NUMBER_WORD + ")";
    private static final String HOUR = "(?:hours?|hrs?|h)";
    private static final String MINUTE = "(?:minutes?|mins?)";
    /**
     * Nothing that continues a word or a number may stand right before an amount; a slash neither, so the "2 hours"
     * of "1/2 hours" is no amount of its own.
     */
    private static final String START = "(?<![\\w.,½/])";
    /**
     * A number and its unit: digits may touch the unit ("2h", "2-hour"), words stand apart from it ("two hours",
     * "two-hour"), so the "eight" of "eighth" is no amount.
     */
    private static final String COUNTED = "(?:(?:" + DIGITS + ")\\s*-?\\s*|(?:" + NUMBER_WORD + "|an?)(?:\\s+|-))"
            + "(?:" + HOUR + "|" + MINUTE + ")(?![a-z])";
    /** Words after bare minutes ("2 hours 15") that make the number count something else ("2 hours 3 days"). */
    private static final String NOT_MINUTES_AFTER = "(?!\\s*(?:" + HOUR + "|days?|weeks?|months?|years?|tasks?|times?"
            + "|goals?|steps?|people|persons?|x|%)(?![a-z]))";
    /**
     * What may follow minutes joined to hours across a comma ("2 hours, 15 minutes"): the end of the clause or a
     * word that adds nothing to the amount. Other words make the minutes an amount of their own ("3 hours, 30
     * minutes more than planned").
     */
    private static final String COMMA_JOIN_END = "(?=\\s*(?:$|[.,;:!?)]|\\b(?:and|but|so|then|today|yesterday"
            + "|in\\s+total|total|altogether|overall|tops|max|or\\s+so)\\b))";

    /**
     * "1/2 hour", "1/2 an hour", "2 1/2 hours", "2 and 1/2 hours", "one and 3/4 hours", "3/4 of an hour", "1/2 hr":
     * a fraction, with a whole number before it (and "and" between them, which a number in words needs).
     */
    private static final Pattern SLASH_FRACTION = Pattern.compile(START + "(?:(\\d{1,3})\\s+(?:and\\s+)?|("
            + NUMBER_WORD + ")\\s+and\\s+)?(\\d{1,2})/([1-9]\\d?)\\s*(?:of\\s+)?(?:an?\\s+)?(" + HOUR + "|" + MINUTE
            + ")(?![a-z])");
    /** "1:30 hours", "1:30h". */
    private static final Pattern CLOCK = Pattern.compile(START + "(\\d{1,2}):([0-5]\\d)\\s*" + HOUR + "(?![a-z])");
    /** "1h30", "1h30m", "1h30min", "1 h 30", "2 hours 15", "2 hours, 15 minutes", "1 hour and 30 minutes". */
    private static final Pattern HOURS_MINUTES = Pattern.compile(START + "(\\d+)\\s*" + HOUR
            + "(?:\\s*,\\s*(?:and\\s+)?(\\d{1,2})\\s*" + MINUTE + "(?![a-z])" + COMMA_JOIN_END
            + "|(?:\\s+and\\s+|\\s*)(\\d{1,2})\\s*" + MINUTE + "(?![a-z])|\\s*(\\d{1,2})m(?![a-z])"
            + "|\\s*([0-5]?\\d)(?!\\w|[.,]\\d)" + NOT_MINUTES_AFTER + ")");
    /**
     * A range or an inexact count: "2 or 3 hours", "2-3 hours", "2 to 3 hours", "between 2 and 3 hours", "an hour or
     * two", "a few hours", "a couple of hours", "several minutes".
     */
    private static final Pattern VAGUE = Pattern.compile("\\bbetween\\s+" + NUMBER + "\\s+and\\s+" + COUNTED
            + "|" + START + "(?:" + DIGITS + ")\\s*[-–]\\s*(?:" + DIGITS + ")\\s*-?\\s*(?:" + HOUR + "|" + MINUTE
            + ")(?![a-z])"
            + "|" + START + NUMBER + "\\s+(?:to|or)\\s+" + COUNTED
            + "|" + START + COUNTED + "\\s+or\\s+" + NUMBER + "(?![\\w.,])"
            + "|\\b(?:a\\s+couple\\s+(?:of\\s+)?|couple\\s+of\\s+|a\\s+few\\s+|few\\s+|several\\s+|some\\s+|many\\s+)"
            + "(?:more\\s+)?(?:hours?|hrs?|" + MINUTE + ")(?![a-z])");
    /** "two and a half hours", "2 and a quarter hours", "one and three quarters hours". */
    private static final Pattern AND_FRACTION_BEFORE = Pattern.compile(START + "(" + NUMBER + ")\\s+and\\s+(a\\s+half"
            + "|a\\s+quarter|three[- ]quarters?)\\s+(" + HOUR + "|" + MINUTE + ")(?![a-z])");
    /** "half an hour", "a half hour", "a quarter of an hour", "three quarters of an hour", "half a minute". */
    private static final Pattern FRACTION = Pattern.compile("\\b(?:(?:a|one)\\s+)?(half|quarter|three[- ]quarters?)"
            + "\\s+(?:of\\s+)?(?:an?\\s+)?(hour|minute)(?![a-z])");
    /**
     * One amount: "2 hours", "1.5h", "90 min", "an hour", "forty-five minutes", "1½ hours", with "and a half" or
     * "and a quarter" after it ("an hour and a half").
     */
    private static final Pattern SINGLE = Pattern.compile(START + "(?:(" + DIGITS + ")\\s*-?\\s*|(" + NUMBER_WORD
            + "|an?)(?:\\s+|-))(" + HOUR + "|" + MINUTE + ")(?![a-z])"
            + "(?:\\s+and\\s+(a\\s+half|a\\s+quarter|three[- ]quarters?)(?![a-z]))?");
    /** What may stand between an amount of hours and the minutes that complete it: "2 hours and 15 minutes". */
    private static final Pattern JOIN = Pattern.compile("\\s*(?:and\\s+)?");
    /**
     * The same across a comma ("2 hours, 15 minutes"), when the minutes end the clause (see {@link #COMMA_JOIN_END}).
     */
    private static final Pattern COMMA_JOIN = Pattern.compile("\\s*,\\s*(?:and\\s+)?");
    private static final Pattern AFTER_COMMA_JOIN = Pattern.compile(COMMA_JOIN_END);

    /**
     * Every amount of time {@code text} states, vague ones included, in the order said. Hours followed by minutes
     * ("1 hour 30 minutes", "an hour and 20 minutes") are one amount.
     */
    public static List<Amount> amounts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return amountsIn(normalise(text));
    }

    /** {@link #amounts} of a text already lower case. */
    private static List<Amount> amountsIn(String t) {
        StringBuilder rest = new StringBuilder(t);
        TreeMap<Integer, Amount> found = new TreeMap<>();
        read(SLASH_FRACTION, rest, found, m -> inHours((m.group(1) != null ? Integer.parseInt(m.group(1))
                : m.group(2) != null ? number(m.group(2)) : 0)
                + Integer.parseInt(m.group(3)) / (double) Integer.parseInt(m.group(4)), m.group(5)), false);
        read(CLOCK, rest, found, m -> Integer.parseInt(m.group(1)) + Integer.parseInt(m.group(2)) / 60.0, false);
        // The hours may be any run of digits ("99999999999 hours 30"): read as a double, never overflowing an int.
        read(HOURS_MINUTES, rest, found, m -> Double.parseDouble(m.group(1)) + Integer.parseInt(
                first(m.group(2), m.group(3), m.group(4), m.group(5))) / 60.0, false);
        read(VAGUE, rest, found, m -> 0.0, true);
        read(AND_FRACTION_BEFORE, rest, found, m -> inHours(number(m.group(1)) + fraction(m.group(2)), m.group(3)),
                false);
        read(FRACTION, rest, found, m -> inHours(fraction(m.group(1)), m.group(2)), false);
        read(SINGLE, rest, found, m -> inHours(number(first(m.group(1), m.group(2)))
                + (m.group(4) == null ? 0 : fraction(m.group(4))), m.group(3)), false);
        return joined(new ArrayList<>(found.values()), t);
    }

    /**
     * The hours of every amount {@code text} states with a number, in the order said: "2 hours" is 2, "90 minutes"
     * 1.5, "half an hour" 0.5, "an hour and a half" 1.5, "1h30" 1.5. Vague amounts ("a few hours") are left out.
     */
    public static List<Double> hours(String text) {
        return amounts(text).stream().filter(a -> !a.vague()).map(Amount::hours).toList();
    }

    /** True when {@code text} states an amount of time, even a vague one ("a couple of hours"). */
    public static boolean states(String text) {
        return !amounts(text).isEmpty();
    }

    /**
     * The hours of the one amount {@code text} states, when it states exactly one, with a number, and no other number
     * ("an hour and a half" is 1.5, "1 hour 30 minutes" 1.5); empty for "2 or 3 hours", "a few hours", "2 hours, maybe
     * 3", "3 tasks in 2 hours" or no amount.
     */
    public static Optional<Double> only(String text) {
        List<Amount> amounts = amounts(text);
        if (amounts.size() != 1 || amounts.getFirst().vague()) {
            return Optional.empty();
        }
        StringBuilder outside = new StringBuilder(normalise(text));
        Amount a = amounts.getFirst();
        outside.replace(a.start(), a.end(), " ".repeat(a.end() - a.start()));
        return NUMBER_ANYWHERE.matcher(outside).find() ? Optional.empty() : Optional.of(a.hours());
    }

    /**
     * A number outside an amount. Digits that follow letters belong to a key or a name ("T3", "T10", "r11"), and
     * "one" after a word that makes it a pronoun ("each one", "that one", "the one") stands for a thing: neither is
     * a number.
     */
    private static final Pattern NUMBER_ANYWHERE = Pattern.compile("(?<![a-z\\d])\\d|\\b(?!one\\b)" + NUMBER_WORD
            + "\\b|(?<!\\b(?:the|this|that|which|any|every|each|some|another|no)\\s{1,3})\\bone\\b");

    /**
     * Words that make an amount more than a plain statement of it: a correction ("actually", "sorry", "I mean", "make
     * that", "scratch that", "on second thought"), a contrast or exclusion ("but", "rather", "instead", "however",
     * "except", "other than"), a hedge ("maybe", "perhaps", "or so", "-ish") or a bound ("more than", "at least", "up
     * to", "over", "max", "about", "nearly", "barely", "give or take", "a good").
     */
    private static final Pattern NOT_SIMPLE = Pattern.compile("\\b(?:actually|sorry|oops|wait|i\\s+mean|i\\s+meant"
            + "|scratch\\s+that|make\\s+that|change\\s+that|rather|instead|second\\s+thoughts?|how\\s+about"
            + "|what\\s+about|correction|but|however|though|although|whereas|otherwise|except|excluding|apart\\s+from"
            + "|aside\\s+from|besides|other\\s+than|maybe|perhaps|possibly|or\\s+so|ish|(?:more|less|fewer|longer"
            + "|shorter)\\s+than|over|under|above|below|beyond|at\\s+(?:most|least)|up\\s+to|max|maximum|minimum"
            + "|about|around|roughly|approximately|nearly|almost|barely|hardly|give\\s+or\\s+take|a\\s+good"
            + "|make\\s+it|let'?s\\s+(?:say|do|make\\s+it))\\b");
    /** "Make it 3 hours", "ok, let's say 2 hours": an opening instruction that corrects nothing in the sentence. */
    private static final Pattern OPENING_INSTRUCTION = Pattern.compile("^\\s*(?:(?:please|ok|okay|yes|yeah|so|then"
            + "|fine)[,!]?\\s+)*(?:make\\s+it|let'?s\\s+(?:say|do|make\\s+it))\\b");

    /**
     * Words that make an amount relative, comparative, a rate, a repeat or a total instead of the time one task took:
     * "2 hours more", "an extra hour", "another 2 hours", "2 hours longer", "less", "quicker", "2 hours early" or
     * "late", "2 hours ago", "short of", "saved 2 hours", "an hour too long", "2 hours too many", "2 hours past the
     * estimate", "4 hours ahead of" or "behind schedule", "on top of", "in addition", "plus", "so far", "2 hours a
     * day", "an hour a week", "2 hours a session", "an hour at a time", "a piece", "per", "each", "every", "for a
     * week", "for 3 days", "twice", "3 times", "again", "together", "in total", "both", "combined", "altogether",
     * "overall", "on average", "apiece".
     */
    private static final Pattern RELATIVE = Pattern.compile("\\b(?:more|extra|additional|additionally|another|longer"
            + "|shorter|less|fewer|quicker|faster|slower|sooner|early|earlier|late|later|ago|short\\s+of|saved?|saving"
            + "|too\\s+(?:long|much|little|short|many|few)|past\\s+(?:the|my|our|its|what)|ahead\\s+of"
            + "|behind\\s+(?:schedule|plan|time|the|my|our)|on\\s+top|in\\s+addition|plus"
            + "|so\\s+far|(?:a|an|per|at\\s+a)\\s+(?:session|time|piece|pop|go|sitting|stretch|visit|class|lesson)"
            + "|overtime|per|each|every|apiece|twice|thrice|times|again|together|in\\s+total|total"
            + "|totals|both|combined|altogether|overall|on\\s+average|average"
            + "|for\\s+(?:a|an|one|two|three|four|five|six|seven|\\d+|several|a\\s+few|a\\s+couple\\s+of)\\s+(?:days?"
            + "|weeks?|months?|evenings?|nights?|mornings?|sessions?))\\b"
            + "|\\b(?:a|an)\\s+(?:week|weekend|fortnight|month|year)\\b(?!\\s+(?:or\\s+(?:two|so)|ago|later|earlier)\\b)");

    /**
     * True when {@code text} is a simple statement of one amount of time for one task, so an amount read from its
     * words alone may be used without asking: it states exactly one amount with a number and no other number (see
     * {@link #only}: "it took 2 hours", "1h30", "an hour and a half"), the words hold that amount true (see
     * {@link #affirmed}: not "down from 4 hours"), and there is no negation, no correction, contrast, exclusion, hedge
     * or bound (see {@link #NOT_SIMPLE}), and no word that makes the amount relative, comparative, a rate, a repeat or
     * a total (see {@link #RELATIVE}: "2 hours longer", "an extra hour", "2 hours a day", "2 hours twice", "3 hours in
     * total"). "Make it" and "let's say" count only after the start of the sentence: "make it 3 hours" is simple. An
     * amount per day is no time a task took; {@link #simplePerDay} tells whether one is stated plainly. False for
     * null, blank and text without an amount.
     */
    public static boolean simple(String text) {
        return plain(text) && !RELATIVE.matcher(normalise(text)).find() && !perDay(text);
    }

    /** A plain amount of time a task took and where its words stand: from the verb before the amount to its end. */
    public record Took(double hours, int start, int end) {
    }

    /**
     * The verb right before the time a task took: "took", "took me", "spent", "needed", "done in", "finished it in".
     */
    private static final Pattern TOOK_BEFORE = Pattern.compile("\\b(?:took|spent|needed|lasted"
            + "|(?:done|finished|completed)(?:\\s+(?:it|that|this|t\\d+))?\\s+in)(?:\\s+(?:me|us))?\\s+$");
    /** The end of the clause right after an amount: the end of the text, punctuation or a dash between spaces. */
    private static final Pattern NOTHING_AFTER = Pattern.compile("\\s*(?:$|[,.;:!?)]|[-\\u2013\\u2014]{1,2}\\s)");

    /**
     * The plain time a task took in {@code clause}: present when the clause states one amount as plainly as
     * {@link #simple} asks, right after "took", "spent", "needed", "lasted" (with "me" or "us" after them) or "done
     * in", "finished in", "completed in" (with "it" or a task key between: "finished T3 in"), and nothing but the end
     * of its clause follows the amount. "it took 2 hours" and "T3 is done, it took 90 minutes." are 2 and 1.5; "it took
     * 2 hours too many", "I spent 3 hours on it", "it took about 2 hours", "4 hours ahead of schedule" and "2 hours"
     * have none. The span runs from the verb to the end of the amount; indices are those of {@code clause}.
     */
    public static Optional<Took> plainTook(String clause) {
        if (!simple(clause)) {
            return Optional.empty();
        }
        String t = normalise(clause);
        Amount amount = amounts(clause).getFirst();
        Matcher verb = TOOK_BEFORE.matcher(t).region(Math.max(0, amount.start() - WORDS_BEFORE), amount.start())
                .useTransparentBounds(true);
        if (!verb.find() || !NOTHING_AFTER.matcher(t).region(amount.end(), t.length()).lookingAt()) {
            return Optional.empty();
        }
        return Optional.of(new Took(amount.hours(), verb.start(), amount.end()));
    }

    /**
     * Words that make one amount the time of each of several tasks: "each", "each one", "each of them", "apiece", "a
     * piece", "per task", "for each". "Each" followed by what repeats ("each day", "each time", "each session")
     * gives a rate or a repeat, not a time per task.
     */
    private static final Pattern PER_TASK = Pattern.compile("\\b(?:(?:for\\s+)?each(?:\\s+(?:one|of\\s+them|task|item"
            + "|of\\s+(?:the|those|these)\\s+tasks))?(?!\\s+(?:day|weekday|workday|week|weekend|fortnight|month|year"
            + "|morning|afternoon|evening|night|time|session|sitting|go|visit|class|lesson|round|attempt|try|pass|run"
            + "|single|other)\\b)|apiece|a\\s+piece|per\\s+(?:task|item|piece))\\b");

    /**
     * The hours each of several tasks took, when {@code text} states one amount for each of them: "T5 and T7 are
     * done, they took 2 hours each" is 2, "each took 90 minutes" 1.5, "an hour apiece" 1, "2 hours per task" 2. The
     * words must be as plain as {@link #simple} asks, apart from the word that makes the amount one per task (see
     * {@link #PER_TASK}): "about 2 hours each", "2 hours each, 5 in total", "2 hours each day", "an extra hour each"
     * and "2 hours" (no word for each task) give none.
     */
    public static Optional<Double> each(String text) {
        if (text == null || text.isBlank() || perDay(text)) {
            return Optional.empty();
        }
        String t = normalise(text);
        Matcher each = PER_TASK.matcher(t);
        StringBuilder rest = new StringBuilder(t);
        boolean found = false;
        while (each.find()) {
            found = true;
            for (int i = each.start(); i < each.end(); i++) {
                rest.setCharAt(i, ' ');
            }
        }
        // The words that make the amount one per task are read as such, not as a number ("each one").
        String others = rest.toString();
        return found && plain(others) && !RELATIVE.matcher(others).find() ? only(others) : Optional.empty();
    }

    /**
     * True when {@code text} plainly states one amount of time per day ("I have 2 hours a day", "from now on, 3 hours
     * per day", "4h/day"), so it may be stored as the hours a person has each day without asking: one amount per day
     * (see {@link #perDayHours}), as plain as {@link #simple} asks of an amount, and no word of {@link #RELATIVE}
     * besides the words that make it per day ("an extra hour a day", "2 hours a day for a week" and "2 hours a day,
     * 5 days a week" are not plain).
     */
    public static boolean simplePerDay(String text) {
        if (!plain(text) || perDayHours(text).isEmpty()) {
            return false;
        }
        String t = normalise(text);
        Matcher rate = PER_DAY.matcher(t);
        StringBuilder rest = new StringBuilder(t);
        while (rate.find()) {
            for (int i = rate.start(); i < rate.end(); i++) {
                rest.setCharAt(i, ' ');
            }
        }
        return !RELATIVE.matcher(rest).find();
    }

    /**
     * Digits in an amount that may be read two ways: a comma and three digits ("2,000 minutes" is two thousand
     * minutes, or two minutes written with a decimal comma) and hours with two decimals that may be minutes ("1.30
     * hours" is 1.3 hours or an hour and 30 minutes, "1.45 h" 1.45 hours or 1 hour 45; "1.75 hours" and "1.00 hours"
     * are read one way only).
     */
    private static final Pattern TWO_WAY_DIGITS = Pattern.compile("(?<![\\d.,])\\d+(?:,\\d{3}(?![\\d.,])"
            + "|[.,](?!00)[0-5]\\d(?![\\d.,])\\s*-?\\s*" + HOUR + "(?![a-z]))");

    /**
     * One amount with a number and no other number, held true by the words, with no negation, correction, contrast,
     * exclusion, hedge or bound, and with digits that read one way only (see {@link #TWO_WAY_DIGITS}).
     */
    private static boolean plain(String text) {
        Optional<Double> one = only(text);
        if (one.isEmpty()) {
            return false;
        }
        String t = normalise(text);
        Amount amount = amounts(text).getFirst();
        if (TWO_WAY_DIGITS.matcher(t).region(amount.start(), amount.end()).find()) {
            return false;
        }
        Matcher opening = OPENING_INSTRUCTION.matcher(t);
        String rest = opening.lookingAt() ? " ".repeat(opening.end()) + t.substring(opening.end()) : t;
        if (NOT_SIMPLE.matcher(rest).find() || NEGATION.matcher(t).find()) {
            return false;
        }
        List<Amount> held = affirmed(text);
        return held.size() == 1 && !held.getFirst().vague() && Math.abs(held.getFirst().hours() - one.get()) < 1e-9;
    }

    // ---- amounts the words hold true ----

    /**
     * A number with no unit of its own that may stand for an amount: the "2" of "not 3 hours, only 2" or of "from 4
     * hours a day to 2". It is followed by the end of its clause or by a word that names nothing it could count ("2
     * on tasks", "2 at most", "2 of them"); "3 tasks", "5 days", "9am", "T3", "the 3rd", "1/2", "50%", a year and a
     * clock time ("at 9", "by 5") are none. A number word after "the", "this", "that", "which" and the like is a
     * pronoun ("that one", "the one I estimated"); digits there are a number ("make that 3", "of which 2").
     */
    private static final Pattern BARE = Pattern.compile("(?<![\\w.,/:½'-])"
            + "(?<!\\b(?:at|by|until|till|since|past|top|number|chapter|page|step|task|goal|level|version)\\s{1,3})"
            + "(\\d{1,3}(?:[.,]\\d+)?½?|½|(?<!\\b(?:the|this|that|which|any|every|each|some|another|a)\\s{1,3})"
            + NUMBER_WORD + ")(?![\\w½/:%-]|[.,]\\d)"
            + "(?=\\s*(?:$|[,.;:!?)]|\\s[-–—]|(?:on|for|in|at|to|with|of|each|a|an|per|every|daily|max|maximum|tops"
            + "|top|or|and|but|so|then|now|instead|please|really|maybe|probably|actually|though|anymore|more|less|is"
            + "|are|was|would|will|should|could|can|if|when|because|since|i|we|it|it's|that|from)\\b))");
    /** Two numbers with no unit that span a time of day: "from 9 to 5", "between 9 and 5", "9-5". */
    private static final Pattern CLOCK_RANGE = Pattern.compile("\\b(?:from\\s+)?\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\s*"
            + "(?:-|to|till|until)\\s*\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?(?![\\w.,]\\d)|\\bbetween\\s+\\d{1,2}(?::\\d{2})?"
            + "\\s*(?:am|pm)?\\s+and\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?\\b");
    /** "From" right before an amount: "from 4 hours to 2" speaks of the old value. */
    private static final Pattern FROM_BEFORE = Pattern.compile("\\bfrom\\s+(?:about\\s+|around\\s+|roughly\\s+"
            + "|just\\s+)?$");
    /** "from 4 to 2 hours": the "4 to" that would otherwise read as the range "4 to 2 hours". */
    private static final Pattern FROM_RANGE = Pattern.compile("\\bfrom\\s+(?:about\\s+|around\\s+|roughly\\s+)?("
            + NUMBER + "\\s+to)\\s+(?=" + COUNTED + ")");
    /**
     * Words that take back the amounts after them, up to the end of their part of the sentence (see
     * {@link #SCOPE_END}): "not", "never", "no", "don't" and the like, "no longer", "used to", "instead of",
     * "rather than", "previously". "Don't forget" and "don't worry" take nothing back; "no longer than" is a limit.
     */
    private static final Pattern NEGATION = Pattern.compile("\\b(?:not|never|no\\s+longer(?!\\s+than)|no|nothing"
            + "|cannot|can\\s+not|unable\\s+to|[a-z]+n't|dont|doesnt|didnt|cant|wont|isnt|arent|wasnt|werent|havent"
            + "|hasnt|hadnt|wouldnt|shouldnt|couldnt|aint|used\\s+to|instead\\s+of|rather\\s+than|other\\s+than"
            + "|except|previously|formerly|originally)\\b(?!\\s+(?:forget|worry)\\b)");
    /** Where a negation stops taking amounts back: punctuation, a dash, or a word that starts another part. */
    private static final Pattern SCOPE_END = Pattern.compile("[,;:!?]|\\.(?!\\d)|\\s[-–—]{1,2}\\s|\\b(?:but|only"
            + "|just|now|instead|rather|because|since|so|and|or|although|though|while|then|actually|yet|however)\\b");
    /**
     * A bound right before an amount: "no more than 2 hours", "I can't do more than 2 hours", "not over 3 hours" state
     * the amount as a limit, which a negation before them does not take back.
     */
    private static final Pattern LIMIT_BEFORE = Pattern.compile("(?:\\b(?:more|longer|less|fewer|shorter)\\s+than"
            + "|\\b(?:over|under|above|below|beyond|exceeding|exceed|at\\s+most|at\\s+least|up\\s+to|in\\s+excess\\s+of"
            + "|max|maximum|min|minimum))\\s+(?:about\\s+|around\\s+)?$");
    /**
     * After an amount, in its clause, a state that says the amount does not hold: "3 hours a day isn't enough", "2
     * hours is not possible", "3 hours a day is too much", "4 hours doesn't work".
     */
    private static final Pattern NEGATED_STATE_AFTER = Pattern.compile("(?:\\s+(?!(?:but|and|or|so|only|just|now"
            + "|then|i|we|you|he|she|they)\\b)[a-z']+){0,4}?\\s+(?:(?:is|are|was|were|has|have)\\s+(?:not|never|no\\s+longer)\\b"
            + "|(?:isn't|aren't|wasn't|weren't|isnt|arent|wasnt|werent|doesn't|doesnt|don't|dont|won't|wont|didn't"
            + "|didnt|ain't)\\b|(?:is|are|was|were)\\s+(?:(?:way|far|much|just|simply)\\s+)?(?:too\\s+(?:much|many"
            + "|long|little|few)|not\\s+enough|out|gone)\\b)");
    /**
     * Words with which a person takes back what they just said, at the start of the text or after punctuation or a
     * dash: "no,", "sorry", "wait", "actually", "oops", "I mean", "I meant", "oh wait", "scratch that", "make that",
     * "make it", "change that", "let's say", "better", "or rather", "on second thought", "second thoughts", alone or
     * together ("no wait", "no, make that").
     */
    private static final Pattern CORRECTION = Pattern.compile("(?:^|[,.;!?]|\\s[-–—]{1,2})\\s*"
            + "((?:(?:no|nope|sorry|wait|oh\\s+wait|actually|oops|i\\s+mean|i\\s+meant|scratch\\s+that|make\\s+that"
            + "|make\\s+it|change\\s+(?:that|it)|let'?s\\s+say|better|or\\s+rather|rather|on\\s+second\\s+thoughts?"
            + "|second\\s+thoughts?|correction)(?:\\s*[,.!:;]\\s*|\\s*[-–—]{1,2}\\s*|\\s+|$))+)");
    /** A correction that is only "no" and a space ("3 hours, no 2"): it may take back either side. */
    private static final Pattern BARE_NO = Pattern.compile("^(?:no|nope)\\s+$");
    /** Words between a correction and the amount it gives: "sorry, make it 2", "actually more like 3 hours". */
    private static final Pattern CORRECTION_FILLER = Pattern.compile("(?:(?:it'?s|it\\s+was|make\\s+it|say|more"
            + "\\s+like|only|just|about|like|maybe|to)\\s+)*");

    /**
     * An amount or a bare number in a text (as an amount in the unit it stands for) and whether the words take it back.
     */
    private record Said(Amount amount, boolean bare, boolean takenBack) {
        int start() {
            return amount.start();
        }

        int end() {
            return amount.end();
        }
    }

    /**
     * The amounts of time {@code text} holds true, in the order said. Like {@link #amounts}, but without the amounts
     * the words take back:
     * <ul>
     *   <li>a negated amount, after "not", "never", "no", "don't", "can't", "no longer", "used to", "instead of",
     *   "rather than" up to the end of its part ("I don't have 3 hours a day, only 2" keeps 2); an amount after a
     *   bound ("I can't do more than 2 hours", "no more than 2 hours") is a limit and stays;</li>
     *   <li>the old value of a change: the amount after "from" ("from 4 hours a day to 2", "went from 3 hours to 2
     *   hours", "from 4 to 2 hours" keep 2);</li>
     *   <li>an amount the person corrects ("2 hours, sorry, 3" keeps 3; "3 hours, no 2" keeps neither, it may
     *   mean either);</li>
     *   <li>an amount that is the subject of a negated state ("3 hours a day isn't enough, make it 4" keeps 4).</li>
     * </ul>
     * A bare number that stands for an amount ("only 2" above) is an amount in the unit of the nearest amount
     * before it (else after it): "not 90 minutes, only 45" keeps 0.75. Bare numbers in a text with no amount count
     * nothing.
     */
    public static List<Amount> affirmed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return said(normalise(text)).stream().filter(s -> !s.takenBack()).map(Said::amount).toList();
    }

    /**
     * The hours of every amount {@code text} holds true with a number (see {@link #affirmed}), in the order said:
     * "3 hours, not 5 hours" is [3], "I don't have 3 hours a day, only 2" [2], "from 4 to 2 hours" [2].
     */
    public static List<Double> affirmedHours(String text) {
        return affirmed(text).stream().filter(a -> !a.vague()).map(Amount::hours).toList();
    }

    /** Every amount and bare number in {@code t} (lower case), with what the words take back marked. */
    private static List<Said> said(String t) {
        char[] readable = t.toCharArray();
        Matcher range = FROM_RANGE.matcher(t);
        while (range.find()) {
            blank(readable, range.start(1), range.end(1));
        }
        List<Amount> amounts = amountsIn(new String(readable));
        char[] outside = readable.clone();
        for (Amount a : amounts) {
            blank(outside, a.start(), a.end());
        }
        List<Said> all = new ArrayList<>();
        for (Amount a : amounts) {
            all.add(new Said(a, false, false));
        }
        Matcher clock = CLOCK_RANGE.matcher(new String(outside));
        while (clock.find()) {
            blank(outside, clock.start(), clock.end());
        }
        Matcher bare = BARE.matcher(new String(outside));
        while (bare.find()) {
            Amount unit = unitSource(amounts, bare.start());
            if (unit != null) {
                double n = number(bare.group(1));
                all.add(new Said(new Amount(minutesUnit(t, unit) ? n / 60 : n, false, bare.start(), bare.end()),
                        true, false));
            }
        }
        all.sort(Comparator.comparingInt(Said::start));
        boolean[] back = takenBack(t, all);
        List<Said> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Said s = all.get(i);
            out.add(new Said(s.amount(), s.bare(), back[i]));
        }
        return out;
    }

    /** The amount a bare number at {@code at} takes its unit from: the nearest before it, else the nearest after it. */
    private static Amount unitSource(List<Amount> amounts, int at) {
        Amount before = null;
        for (Amount a : amounts) {
            if (a.end() <= at) {
                before = a;
            } else if (before == null) {
                return a;
            }
        }
        return before;
    }

    /** Which of {@code all} (in text order) the words of {@code t} take back. */
    private static boolean[] takenBack(String t, List<Said> all) {
        boolean[] back = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            Said s = all.get(i);
            if (endsRightBefore(FROM_BEFORE, t, s.start())) {
                back[i] = true;
            }
            int clauseEnd = scopeEnd(t, s.end());
            if (NEGATED_STATE_AFTER.matcher(t).region(s.end(), clauseEnd).lookingAt()) {
                back[i] = true;
            }
        }
        List<int[]> markers = correctingMarkers(t, all);
        boolean[] limit = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            limit[i] = endsRightBefore(LIMIT_BEFORE, t, all.get(i).start());
        }
        Matcher negation = NEGATION.matcher(t);
        while (negation.find()) {
            int at = negation.start();
            if (markers.stream().anyMatch(r -> at >= r[0] && at < r[1])) {
                continue; // the "no" of "no wait, 3" or "no, make that 3" belongs to the correction
            }
            int end = scopeEnd(t, negation.end());
            for (int i = 0; i < all.size(); i++) {
                Said s = all.get(i);
                if (s.start() >= negation.end() && s.start() < end && !limit[i]) {
                    back[i] = true;
                }
            }
        }
        Matcher correction = CORRECTION.matcher(t);
        while (correction.find()) {
            Matcher filler = CORRECTION_FILLER.matcher(t).region(correction.end(), t.length());
            int at = filler.lookingAt() ? filler.end() : correction.end();
            boolean corrects = all.stream().anyMatch(s -> s.start() == at);
            if (!corrects) {
                continue;
            }
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).end() <= correction.start(1)) {
                    back[i] = true;
                }
            }
            if (BARE_NO.matcher(correction.group(1)).find()) {
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).start() == at) {
                        back[i] = true;
                    }
                }
            }
        }
        return back;
    }

    /**
     * Where the words of each correction that gives a new amount stand ("no wait" of "4h a day, no wait 3h"), as
     * {start, end} pairs. A bare "no" ("3 hours, no 2") is not one of them: it may take back either side.
     */
    private static List<int[]> correctingMarkers(String t, List<Said> all) {
        List<int[]> out = new ArrayList<>();
        Matcher correction = CORRECTION.matcher(t);
        while (correction.find()) {
            Matcher filler = CORRECTION_FILLER.matcher(t).region(correction.end(), t.length());
            int at = filler.lookingAt() ? filler.end() : correction.end();
            if (!BARE_NO.matcher(correction.group(1)).find() && all.stream().anyMatch(s -> s.start() == at)) {
                out.add(new int[]{correction.start(1), correction.end(1)});
            }
        }
        return out;
    }

    /**
     * How far back from an amount the words right before it ({@link #FROM_BEFORE}, {@link #LIMIT_BEFORE}) are looked
     * for.
     */
    private static final int WORDS_BEFORE = 64;

    /**
     * True when {@code pattern} (one that ends with {@code $}) matches words that end right before {@code at} in
     * {@code t}, looking at most {@link #WORDS_BEFORE} characters back, so a text with many amounts is read in time
     * that grows with its length.
     */
    private static boolean endsRightBefore(Pattern pattern, String t, int at) {
        return pattern.matcher(t).region(Math.max(0, at - WORDS_BEFORE), at).useTransparentBounds(true).find();
    }

    private static int scopeEnd(String t, int from) {
        Matcher m = SCOPE_END.matcher(t).region(from, t.length());
        return m.find() ? m.start() : t.length();
    }

    private static final Pattern HOUR_WORD = Pattern.compile("\\b(?:hours?|hrs?|h)\\b|\\dh");

    /**
     * True when an amount is counted in minutes only ("90 minutes", "several minutes"), not "an hour and 20 minutes".
     */
    private static boolean minutesUnit(String t, Amount a) {
        String words = t.substring(a.start(), a.end());
        return minutes(t, a) && !HOUR_WORD.matcher(words).find();
    }

    private static void blank(char[] chars, int from, int to) {
        for (int i = from; i < to; i++) {
            chars[i] = ' ';
        }
    }

    // ---- amounts per day ----

    /**
     * Words that give an amount per day: "a day", "per day", "/day", "each day", "every day", "daily", "a weekday",
     * "each weekday", "on weekdays", "per working day", "every evening", "each night", "on weeknights". "Half a day",
     * "in a day", "a day or two" and "a day off" are no rate; "an hour and a half a day" is one.
     */
    private static final Pattern PER_DAY = Pattern.compile("(?:(?<=\\band\\s{1,3}an?\\s{1,3}half\\s{1,3})"
            + "|(?<!\\b(?:half|in|within|for|than|of|whole|full|entire|another|such)\\s{1,3}))"
            + "\\b(?:a|an|per|each|every)\\s+(?:single\\s+)?(?:day|weekday|workday|work\\s+day"
            + "|working\\s+day|business\\s+day|evening|night|morning|weeknight)(?![\\w-])"
            + "(?!\\s+(?:or\\s+(?:two|so|more)|off|ahead|later|earlier|early|before|after|ago)\\b)"
            + "|(?<=[\\w\\s])\\s*/\\s*(?:day|weekday|workday|working\\s+day|business\\s+day|evening|night)\\b"
            + "|\\b(?:daily|nightly)\\b"
            + "|\\b(?:on|in\\s+the)\\s+(?:weekdays|workdays|work\\s+days|working\\s+days|business\\s+days|weeknights"
            + "|evenings|mornings|nights)\\b");
    /**
     * Words that give an amount for other days ("5 hours on Saturdays", "4 on weekends", "3 hours each Friday"): next
     * to a per-day amount they give another one, so the two compete.
     */
    private static final Pattern OTHER_DAYS = Pattern.compile("\\b(?:on|at|over)\\s+(?:the\\s+)?(?:weekends?"
            + "|saturdays?|sundays?|mondays?|tuesdays?|wednesdays?|thursdays?|fridays?)\\b"
            + "|\\b(?:each|every)\\s+(?:weekend|saturday|sunday|monday|tuesday|wednesday|thursday|friday)\\b");
    /**
     * Where the words stop speaking of one amount: a sentence end (a full stop not followed by a digit, so "1.5h" is
     * whole) or a word that sets one part against another.
     */
    private static final Pattern PART_END = Pattern.compile("[;!?]|\\.(?!\\d)|\\b(?:but|while|whereas|although|though)\\b");

    /**
     * True when {@code text} speaks of an amount per day ("2 hours a day", "4h/day", "daily", "on weekdays", "every
     * evening").
     */
    public static boolean perDay(String text) {
        return text != null && PER_DAY.matcher(normalise(text)).find();
    }

    /**
     * The hours per day {@code text} states, when it states exactly one such amount (see {@link #perDayAmounts}): "I
     * spend 30 minutes commuting and 2 hours a day on tasks" is 2, "each day I can do an hour and a half" 1.5, "I
     * don't have 3 hours a day, only 2" 2, "drop it from 4 hours a day to 2" 2. Empty when no per-day phrase has an
     * amount with a number ("a few hours a day", "2 hours", "daily standups") and when several amounts per day compete
     * ("I work 8 hours a day but only 2 on tasks", "2 hours a day, 4 on weekends").
     */
    public static Optional<Double> perDayHours(String text) {
        List<Double> amounts = perDayAmounts(text);
        return amounts.size() == 1 ? Optional.of(amounts.getFirst()) : Optional.empty();
    }

    /**
     * Every different number of hours per day {@code text} holds true, in the order said; more than one means they
     * compete and the words alone do not settle it. A per-day phrase (see {@link #perDay}) qualifies the amount
     * before it in the same part of the sentence, else the first one after it; when the words take that amount back
     * (see {@link #affirmed}), the first amount held true after it stands in its place ("I don't have 3 hours a
     * day, only 2 hours" is [2]). A bare number that stands for a per-day amount is one too ("8 hours a day but only
     * 2 on tasks" is [8, 2]), and so is an amount for other days next to a per-day one ("4 hours on weekdays and 6
     * hours on weekends" is [4, 6]). Empty when the text states no per-day amount, or only a vague one ("a few hours
     * a day").
     */
    public static List<Double> perDayAmounts(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String t = normalise(text);
        List<Said> all = said(t);
        List<Said> perDay = new ArrayList<>();
        List<Said> chosen = new ArrayList<>();
        Matcher phrase = PER_DAY.matcher(t);
        boolean anyPhrase = false;
        while (phrase.find()) {
            anyPhrase = true;
            qualified(t, all, phrase.start(), phrase.end()).ifPresent(chosen::add);
        }
        if (!anyPhrase) {
            return List.of();
        }
        Matcher other = OTHER_DAYS.matcher(t);
        while (other.find()) {
            qualified(t, all, other.start(), other.end()).ifPresent(chosen::add);
        }
        for (Said c : chosen) {
            if (!c.takenBack()) {
                perDay.add(c);
                continue;
            }
            int partEnd = partEnd(t, c.end());
            all.stream().filter(s -> !s.takenBack() && s.start() >= c.end() && s.end() <= partEnd).findFirst()
                    .ifPresent(perDay::add);
        }
        for (Said s : all) {
            if (s.bare() && !s.takenBack()) {
                Amount unit = unitSource(all.stream().filter(a -> !a.bare()).map(Said::amount).toList(), s.start());
                if (chosen.stream().anyMatch(c -> c.amount().equals(unit))) {
                    perDay.add(s);
                }
            }
        }
        if (perDay.stream().anyMatch(s -> s.amount().vague())) {
            return List.of();
        }
        List<Double> values = new ArrayList<>();
        perDay.stream().sorted(Comparator.comparingInt(Said::start)).map(s -> s.amount().hours())
                .forEach(h -> {
                    if (values.stream().noneMatch(v -> Math.abs(v - h) < 1e-6)) {
                        values.add(h);
                    }
                });
        return List.copyOf(values);
    }

    /**
     * The amount or bare number a phrase between {@code from} and {@code to} qualifies: the last one before it in its
     * part of the sentence, else the first one after it.
     */
    private static Optional<Said> qualified(String t, List<Said> all, int from, int to) {
        int partStart = partStart(t, from);
        int partEnd = partEnd(t, to);
        Said before = null;
        Said after = null;
        for (Said s : all) {
            if (s.end() <= from && s.start() >= partStart) {
                before = s;
            } else if (s.start() >= to && s.end() <= partEnd && after == null) {
                after = s;
            }
        }
        return Optional.ofNullable(before != null ? before : after);
    }

    private static int partStart(String t, int at) {
        Matcher m = PART_END.matcher(t).region(0, at);
        int start = 0;
        while (m.find()) {
            start = m.end();
        }
        return start;
    }

    private static int partEnd(String t, int from) {
        Matcher m = PART_END.matcher(t).region(from, t.length());
        return m.find() ? m.start() : t.length();
    }

    /** Reads every match of {@code pattern} as an amount and blanks it, so later patterns do not read it again. */
    private static void read(Pattern pattern, StringBuilder rest, Map<Integer, Amount> found,
                             ToDoubleFunction<Matcher> hours, boolean vague) {
        Matcher m = pattern.matcher(rest.toString());
        while (m.find()) {
            found.put(m.start(), new Amount(hours.applyAsDouble(m), vague, m.start(), m.end()));
            for (int i = m.start(); i < m.end(); i++) {
                rest.setCharAt(i, ' ');
            }
        }
    }

    /**
     * Hours followed by minutes ("an hour and 20 minutes", "2 hours, 15 minutes") become one amount; across a comma
     * only when the minutes end the clause ("3 hours, 30 minutes more than planned" is two amounts).
     */
    private static List<Amount> joined(List<Amount> amounts, String t) {
        List<Amount> out = new ArrayList<>();
        for (Amount a : amounts) {
            Amount last = out.isEmpty() ? null : out.getLast();
            if (last != null && !last.vague() && !a.vague() && a.hours() < 1 && minutes(t, a) && !minutes(t, last)
                    && joins(t, last, a)) {
                out.set(out.size() - 1, new Amount(last.hours() + a.hours(), false, last.start(), a.end()));
            } else {
                out.add(a);
            }
        }
        return List.copyOf(out);
    }

    private static boolean joins(String t, Amount hours, Amount minutes) {
        if (JOIN.matcher(t).region(hours.end(), minutes.start()).matches()) {
            return true;
        }
        return COMMA_JOIN.matcher(t).region(hours.end(), minutes.start()).matches()
                && AFTER_COMMA_JOIN.matcher(t).region(minutes.end(), t.length()).lookingAt();
    }

    private static final Pattern MINUTES_UNIT = Pattern.compile("(?:minutes?|mins?)$");

    private static boolean minutes(String t, Amount a) {
        return MINUTES_UNIT.matcher(t.substring(a.start(), a.end()).strip()).find();
    }

    private static double inHours(double amount, String unit) {
        return unit.startsWith("m") ? amount / 60 : amount;
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        throw new IllegalArgumentException("no value");
    }

    private static double fraction(String words) {
        String w = words.replaceAll("[\\s-]+", " ");
        if (w.startsWith("three")) {
            return 0.75;
        }
        return w.endsWith("quarter") ? 0.25 : 0.5;
    }

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(Map.entry("zero", 0), Map.entry("one", 1),
            Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4), Map.entry("five", 5), Map.entry("six", 6),
            Map.entry("seven", 7), Map.entry("eight", 8), Map.entry("nine", 9), Map.entry("ten", 10),
            Map.entry("eleven", 11), Map.entry("twelve", 12), Map.entry("thirteen", 13), Map.entry("fourteen", 14),
            Map.entry("fifteen", 15), Map.entry("sixteen", 16), Map.entry("seventeen", 17), Map.entry("eighteen", 18),
            Map.entry("nineteen", 19), Map.entry("twenty", 20), Map.entry("thirty", 30), Map.entry("forty", 40),
            Map.entry("fifty", 50), Map.entry("sixty", 60), Map.entry("seventy", 70), Map.entry("eighty", 80),
            Map.entry("ninety", 90));

    /** Digits with a comma before exactly three more: a thousands separator ("2,000"). */
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3},\\d{3}");

    /**
     * The value of a number as written: "2", "1.5", "1,5" (1.5), "2,000" (2000: a comma before exactly three digits
     * separates thousands), "1½", "½", "an", "forty-five", "twenty five".
     */
    private static double number(String written) {
        String n = written.strip();
        if (THOUSANDS.matcher(n).matches()) {
            return Double.parseDouble(n.replace(",", ""));
        }
        if (n.equals("a") || n.equals("an")) {
            return 1;
        }
        if (n.endsWith("½")) {
            String whole = n.substring(0, n.length() - 1);
            return (whole.isEmpty() ? 0 : Double.parseDouble(whole.replace(',', '.'))) + 0.5;
        }
        if (Character.isDigit(n.charAt(0))) {
            return Double.parseDouble(n.replace(',', '.'));
        }
        int value = 0;
        for (String part : n.split("[- ]")) {
            value += NUMBER_WORDS.getOrDefault(part, 0);
        }
        return value;
    }

    /** Lower case with plain apostrophes; every character keeps its place, so indices are those of the text. */
    private static String normalise(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = Character.toLowerCase(chars[i]);
            chars[i] = c == '\u2018' || c == '\u2019' ? '\'' : c;
        }
        return new String(chars);
    }
}
