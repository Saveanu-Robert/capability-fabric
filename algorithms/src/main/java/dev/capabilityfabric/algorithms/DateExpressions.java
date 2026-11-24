package dev.capabilityfabric.algorithms;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.MonthDay;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Finds a deadline in everyday English, deterministically. Language models are unreliable at date arithmetic ("next
 * Saturday" computed as a Tuesday), so the kernel trusts this parser over the model when both see a date.
 *
 * <p>Understood: today, tomorrow, in or within N days/weeks/fortnights/months, (next|this|on|by) weekday, "this coming"
 * or "the following" weekday, last or past weekday (a date before today), weekend, end of the week/month/year, next
 * week/month, month day, day month, "the 20th" (day of the month, next occurrence), "the 5th of next month", "Friday
 * the 27th" or "Friday 27" (the next 27th, when it is a Friday), dates in digits ("27/11", "11/27", "27.11.2026",
 * "27-11-2026"; with a full stop or a dash the day comes first; "4/12" only with a weekday that fits the nearer
 * reading: "Friday 4/12"; "4.12" only with a weekday it fits, since it may be a time of day: "Friday 4.12."),
 * "Wednesday 3rd March 2027" (that date, when it is a Wednesday), the same with ordinal words ("by the twenty-fifth",
 * "the first of next month", "March first"), "the first Monday of December", "the second Tuesday of next month", "the
 * last Friday of the month", "the first day of next month", "the last day of February", ISO dates, and after a date
 * word ("by", "until", "in", "before", ...) a month alone ("by March" is March 31, "before March" is the last day of
 * February), "the end of March", "mid December", "December the 3rd", the fixed holidays Christmas (Eve), New Year's Eve
 * and Day, Halloween and Valentine's Day, and Thanksgiving, Black Friday, Cyber Monday, Good Friday and Easter (a month
 * or holiday followed by a word that makes it part of an event or a name is none: "by the Christmas party", "by the
 * November newsletter", "Fences by August Wilson"; a time of the day may follow a holiday: "Easter Monday morning"). A
 * weekday in the name of another day ("Palm Sunday", "Ash Wednesday", "Super Bowl Sunday") is no weekday and names no
 * date. A year may follow a date: "June 1 2028", "December of 2027", "Dec 5 '27", "by Christmas next year", "by
 * November next year", "end of year 2027". "Before" a day is the day before it ("before Christmas" is December 24,
 * "before December 5" December 4, "before the 25th" the 24th, "before Friday" Thursday, "before tomorrow" today,
 * "before the weekend" Friday, "before next week" this Sunday, "before next month" this month's last day), "before the
 * end of" a period is its last day, and a "before" date that has passed is that of the day's next occurrence ("before
 * Christmas" said on Christmas Day is next year's Christmas Eve), and so is a day before an anchor that has passed:
 * "the Friday before the 20th" said on the 18th is the Friday before next month's 20th. A weekday of a named week
 * ("Friday next week", "next week Friday", "Friday of the week after next"), the start, end or early part of next week
 * or of the week after next, "the weekend after next", the start, beginning, early, mid, late or end part of a month
 * ("early next month" is the 5th, "late March" the 25th), an amount from an anchor ("two weeks from today", "3 days
 * from tomorrow", "a week from Friday", "a week on Friday", "a week Friday", "a week today", "today in two weeks", "a
 * fortnight on Tuesday"), a week or a fortnight after a weekday, today or tomorrow ("Friday week", "next Tuesday week",
 * "Tuesday fortnight", "tomorrow week", "today fortnight") and a weekday, day or weekend around an anchor ("the Monday
 * after next", "the Monday after Christmas", "Friday before Christmas", "the Friday just before Christmas", "the Monday
 * following the conference" (no date: the conference names none), "the day before the 20th", "the weekend after
 * Christmas", "the weekend of December 12" when that is a Saturday or a Sunday). "Next Saturday" means the first
 * Saturday after today, unless the sentence also names "this Saturday": then it is the Saturday a week after that one
 * (see {@link #contrastNext}). A date in a negated part of a sentence ("not Friday", "I can't do it today", "Friday
 * doesn't work", "Thursday and Friday don't work", "Friday is out", "any day but Friday", "nothing on Friday") is never
 * the answer; "not due until Friday" is Friday, and "Monday rather than Friday" or "Monday not Friday" is Monday. A
 * date the person takes back ("Friday, no wait, Monday", "Friday. Actually no, Monday", "Friday, make it Monday",
 * "Friday, or Monday rather") is never the answer either: the one after the correction is. A day that recurs ("every
 * Friday", "each Friday", "weekly on Tuesday", "the 1st of every month", "once a week on Sunday", "twice a month on the
 * 1st") names no date. {@link #simple} tells whether a sentence states one date so plainly that a date read from it may
 * be applied without asking.
 *
 * <p>A phrase is read whole or not at all. When an ordinal counts weekdays, days or weeks in a way the parser cannot
 * place ("the second Tuesday", "the last Friday before Christmas", "the first week of December", "the fifth Monday" of
 * a month that has four), when "after" puts the time after a date ("after Friday", "after the weekend"), when an amount
 * is no whole number of days or weeks ("in a week and a half", "in a week or two", "in a week and a day"), when only a
 * part of the phrase has a reading ("the end of the month after next", "the end of the month after", "the week before
 * Christmas", "this time next week", "mid next week", "the early part of next week", "the Friday shortly before
 * Christmas", "the week of December 7", "a month on Friday", "Friday plus a week", "Friday 2 weeks from now", "Friday
 * (the 27th)"), when a weekday and "week" said on that weekday may count from today or a week on ("Wednesday week" on a
 * Wednesday), or when the words of a phrase read whole name no date (an anchor that names none, a weekday the date said
 * with it does not fall on: "Friday the 5th" when the 5th is a Saturday, digits after a weekday that fit no single date
 * on it: "Friday 13.11", a year no deadline may have, "before" a day that has passed), {@link #find} and
 * {@link #findTarget} find no date: a date they return is never one read from only a part of such a phrase ("next
 * month" of "the first week of next month", "Friday" of "Friday the 5th"). An ordinal followed by a noun is a position,
 * not a day ("before the first release").
 *
 * <p>The time a text takes grows with its length, whatever words it repeats: a reader follows a clause for at most 200
 * characters from a date word, a date counted from an anchor of an anchor of an anchor ("a week from a week from a week
 * from Friday") names no date, and {@link #findTarget} reads at most 48 clause parts before it finds no target date.
 */
public final class DateExpressions {

    // "sat", "sun" and "wed" are left out: they are ordinary words too. A weekday never follows an apostrophe, so the
    // "mon" of "c'mon" is not Monday, nor a word that makes it part of a named day (see NAMED_DAY).
    private static final String WEEKDAY_NAMES = "monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|tues"
            + "|thu|thur|thurs|fri";
    /**
     * Words before a weekday that make it part of the name of a day of its own: "Black Friday", "Good Friday", "Cyber
     * Monday", "Easter Monday", "Palm Sunday", "Ash Wednesday", "Shrove Tuesday", "Fat Tuesday", "Giving Tuesday",
     * "Maundy Thursday", "Holy Saturday", "Whit Monday", "Super Bowl Sunday", "Small Business Saturday". Such a
     * weekday is never read as the coming one; the named days the parser knows are read as holidays (see
     * {@link #HOLIDAY}), the others name no date.
     */
    private static final String NAMED_DAY = "(?<!\\b(?:black|good|cyber|easter|palm|ash|shrove|fat|giving|maundy|holy"
            + "|whit|super\\s{1,3}bowl|small\\s{1,3}business)\\s{1,3})";
    private static final String WEEKDAY = "(?<!')" + NAMED_DAY + "(" + WEEKDAY_NAMES + ")";
    private static final String MONTH_NAMES = "january|february|march|april|may|june|july|august|september|october"
            + "|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec";
    private static final String MONTH = "(" + MONTH_NAMES + ")";
    private static final String NUMBER = "(\\d{1,3}|a couple of|a couple|a|an|one|two|three|four|five|six|seven|eight"
            + "|nine|ten|eleven|twelve)";
    private static final String UNIT = "(day|days|week|weeks|fortnight|fortnights|month|months)";
    private static final String ORDINAL = "(\\d{1,2})(?:st|nd|rd|th)";
    /**
     * Words between a weekday, day or weekend and "after", "before", "following" or "preceding" that keep it the
     * nearest one to its anchor: "the Friday just before Christmas" is the Friday before Christmas.
     */
    private static final String CLOSE = "(?:just|right|immediately|directly|straight)";
    /**
     * Words in the same place that leave how near it is open: "the Friday soon after Christmas", "the weekend
     * shortly before Christmas". Such a phrase names no date (see {@link #PARTIAL}).
     */
    private static final String LOOSE = "(?:shortly|soon|well|long|way|sometime|not\\s+long|a\\s+(?:bit|little|while)"
            + "|closely|close|nearly|almost)";
    /** Nouns after an ordinal that make it a position ("the 2nd task", "the 3rd Monday"), not a day of the month. */
    private static final String POSITION_NOUN = "(?!\\s+(?:task|step|item|goal|one|time|place|floor|row|line|"
            + "option|week|weekend|month|year|day|attempt|draft|version|round|part|chapter|page|thing|priority|half"
            + "|quarter|meeting|session|lesson|class|call|phase|stage|level|choice|person|slot|" + WEEKDAY_NAMES
            + ")s?\\b)";

    private static final Pattern ISO = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
    private static final Pattern IN_N = Pattern.compile("\\b(?:in|within)\\s+(?:the\\s+next\\s+)?" + NUMBER
            + "\\s+" + UNIT + "\\b");
    private static final Pattern WEEKDAY_REF = Pattern.compile(
            "\\b(?:(next|this|on|by|before|until|last|past)\\s+)?(?:(?:the\\s+)?(?:coming|following)\\s+)?" + WEEKDAY
                    + "\\b");
    /** "December 3", "Dec. 3rd", "December the 3rd", "December, the 3rd". */
    private static final Pattern MONTH_DAY = Pattern.compile("\\b" + MONTH + "\\.?(?:\\s+|,?\\s+the\\s+)(\\d{1,2})"
            + "(?:st|nd|rd|th)?\\b");
    private static final Pattern DAY_MONTH = Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH + "\\b");
    /**
     * Words after an ordinal that make it a position ("the 2nd task", "the first time") or part of a longer date
     * phrase ("the 1st Monday of", "the 2nd day of"), not a day of the month on its own.
     */
    private static final String NOT_A_DAY = "(?!\\s+of\\b)" + POSITION_NOUN;
    /**
     * What may follow a day of the month written as an ordinal ("by the 20th", "on the third at noon", "the 3rd,
     * please", "by the first I need it", "the 25th December"): the end of its clause, a dash, a month, a year or a
     * word that starts what comes next. Any other word makes the ordinal a position: "before the first release", "the
     * 2nd interview", "on the first try".
     */
    private static final String DAY_END = "(?=\\s*(?:$|[,.;:!?)]|[-\\u2013\\u2014]\\s|\\b(?:" + MONTH_NAMES
            + "|i|we|you|he|she|they|it|my|our|your|his|her|their|at|in|on|please|instead|then|so|because|and|or|but"
            + "|latest|if|when|unless|next|this|for|to|from|is|was|would|will|works?|does|do|did|suits?|fits?|sounds?"
            + "|seems?|might|should|could|can|\\d{4})\\b))";
    /**
     * What may follow a month or a holiday named on its own ("by March", "before Christmas", "by Christmas next
     * year", "in May '27", "by December of 2027", "by March of next year", "by Easter, please"): what may follow a
     * day of the month (see {@link #DAY_END}), a year, or "with", "as", "since" or "too". Any other word makes the
     * name part of another name or of an event: "the Christmas party", "the December board meeting", "Fences by
     * August Wilson", "by Christmas week".
     */
    private static final String NAME_END = "(?:(?=\\s*(?:'\\d{2}\\b|of\\s+(?:\\d{4}|(?:next|this|last)\\s+year)\\b"
            + "|\\b(?:with|as|since|too)\\b))|"
            + DAY_END + ")";
    /**
     * "the 20th" right after a date word ("by the 20th", "on 3rd"), after "is", "be", "it" or "but" ("make it the
     * 25th", "not Friday but the 30th"), or at the start of a sentence or after a comma, when what follows lets it be
     * a day (see {@link #DAY_END}).
     */
    private static final Pattern DAY_OF_MONTH = Pattern.compile("(?:^\\s*|,\\s*|\\b(?:on|by|until|till|before|due|for|to|from"
            + "|is|be|it|but)\\s+)"
            + "(?:the\\s+)?" + ORDINAL + "\\b(?!\\s+of\\b)" + DAY_END);
    /** "the 5th of next month", "the 20th of this month", "the 3rd of the month". */
    private static final Pattern NTH_OF_MONTH = Pattern.compile("\\b(?:the\\s+)?(\\d{1,2})(?:st|nd|rd|th)?\\s+of\\s+"
            + "(next|this|the|the\\s+next|the\\s+coming|the\\s+following)\\s+month\\b");
    /**
     * What may not follow a bare number for it to be a day of the month: more digits, a time ("3pm", "3:30", "3.30",
     * "3 o'clock"), a span ("10-11am", "2 to 3pm"), a unit ("2 hours", "3 days", "50%") or the rest of a numeric
     * date ("27/11", "27.11").
     */
    private static final String NOT_A_BARE_DAY = "(?![\\d/.:]|\\s*(?:[-\\u2013]|to|till|until|through)\\s*\\d"
            + "|\\s*(?:am|pm|a\\.m|p\\.m|h|hrs?|hours?|mins?|minutes?"
            + "|days?|weeks?|months?|years?|times|x|o'clock|%)(?![a-z]))";
    /**
     * "Friday the 27th", "friday, 27th", "Friday 27": the next 27th, which must fall on that weekday. A month or a
     * year after the day makes it a {@link #WEEKDAY_DATE}. A bare number counts only when nothing makes it a time, an
     * amount or part of a numeric date ("Friday 3pm", "Friday 2 hours", "Friday 27/11", see {@link #NOT_A_BARE_DAY}).
     */
    private static final Pattern WEEKDAY_DAY = Pattern.compile("\\b" + WEEKDAY
            + ",?\\s+(?:the\\s+(\\d{1,2})(?:st|nd|rd|th)?(?![/.:]\\d)|" + ORDINAL + "|(\\d{1,2})" + NOT_A_BARE_DAY + ")\\b"
            + NOT_A_DAY + "(?!,?\\s+(?:" + MONTH_NAMES + ")\\b)(?!,?\\s*\\d{4}\\b)");
    /**
     * A weekday with a day and a month: "Wednesday 3rd March", "Friday, 27 November", "Friday the 5th of December",
     * "Wednesday March 3rd", "Fri 20th Nov". A year may follow. The date decides and must fall on that weekday.
     */
    private static final Pattern WEEKDAY_DATE = Pattern.compile("\\b" + WEEKDAY + ",?\\s+(?:(?:the\\s+)?(\\d{1,2})"
            + "(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH + "|" + MONTH + "\\.?(?:\\s+|,?\\s+the\\s+)(\\d{1,2})"
            + "(?:st|nd|rd|th)?)\\b");
    /** Ordinal words for the days of a month: "first" to "thirty-first". */
    private static final String ORDINAL_WORDS = "(?:twenty|thirty)[- ](?:first|second|third|fourth|fifth|sixth"
            + "|seventh|eighth|ninth)|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|eleventh"
            + "|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|eighteenth|nineteenth|twentieth"
            + "|thirtieth";
    /** The position of a weekday in its month: "first" to "fifth", "1st" to "5th", or "last". */
    private static final String NTH = "(first|second|third|fourth|fifth|last|1st|2nd|3rd|4th|5th)";
    /** The month a position counts in: "the month", "this month", "next month" or a named month. */
    private static final String OF_MONTH = "(?:(the|this)\\s+month|(next)\\s+month|" + MONTH + ")\\b";
    /**
     * "the first Monday of December", "the second Tuesday of next month", "the 3rd Thursday in January", "the last
     * Friday of the month".
     */
    private static final Pattern NTH_WEEKDAY_OF = Pattern.compile("\\b(?:the\\s+)?" + NTH + "\\s+" + WEEKDAY
            + "\\s+(?:of|in)\\s+" + OF_MONTH);
    /** "the first day of next month", "the last day of February", "the 10th day of the month". */
    private static final Pattern NTH_DAY_OF = Pattern.compile("\\b(?:the\\s+)?(" + ORDINAL_WORDS
            + "|\\d{1,2}(?:st|nd|rd|th)|last)\\s+day\\s+of\\s+" + OF_MONTH);
    /**
     * A phrase that starts with an ordinal and counts weekdays, days or weeks the parser cannot place on its own:
     * "the second Tuesday" (of which month?), "the last Friday before Christmas", "the first week of December".
     * When one is not read whole by {@link #NTH_WEEKDAY_OF}, {@link #NTH_DAY_OF} or {@link #AROUND}, the text has no
     * date: reading only its weekday or month would be a confident wrong answer.
     */
    private static final Pattern ORDINAL_PHRASE = Pattern.compile("\\b(?:(" + ORDINAL_WORDS
            + "|\\d{1,2}(?:st|nd|rd|th))\\s+(?:" + WEEKDAY_NAMES + ")s?\\b"
            + "|(?:" + ORDINAL_WORDS + "|\\d{1,2}(?:st|nd|rd|th)|last)\\s+(?:" + WEEKDAY_NAMES
            + ")s?\\s+(?:of|in|after|before|from)\\b"
            + "|(?:" + ORDINAL_WORDS + "|\\d{1,2}(?:st|nd|rd|th)|last)\\s+(?:day|week|weekend|month)s?\\s+(?:of|in)\\s+"
            + "(?:the\\s+)?(?:" + MONTH_NAMES + "|next|this|coming|following|month|year)\\b)");
    /**
     * An ordinal word that names a day of the month: after a date word, a weekday or a month and "the" ("by the
     * twenty-fifth", "on the third", "Friday the twenty-seventh", "December the third") when what follows lets it be a
     * day (see {@link #DAY_END}: "before the first release" names no date), after a month ("March first", "by May
     * third") or before "of" and a month ("the first of next month", "the third of December"). After "for", "to",
     * "is", "be", "it" or "but" and "the" it is one only at the end of its clause ("make it the first", "plan it for
     * the twenty-fourth, please"), so "for the first and last time" names no date.
     */
    private static final Pattern ORDINAL_WORD_DATE = Pattern.compile("(?:(?<=\\b(?:on|by|until|till|before|due|after|"
            + WEEKDAY_NAMES + "|january|february|march|april|june|july|august|september|october|november|december)"
            + ",?\\s{1,3}the\\s{1,3})|(?<=\\b(?:on|by|until|till|before|due|in)\\s{1,3}may,?\\s{1,3}the\\s{1,3}))("
            + ORDINAL_WORDS + ")\\b(?!\\s+of\\b)" + DAY_END
            + "|(?:(?<=\\b(?:january|february|march|april|june|july|august|september|october|november|december)"
            + "\\s{1,3})|(?<=\\b(?:on|by|until|till|before|due|in)\\s{1,3}may\\s{1,3}))(" + ORDINAL_WORDS + ")\\b"
            + NOT_A_DAY
            + "|(?<=\\b(?:for|to|is|be|it|but)\\s{1,3}the\\s{1,3})(" + ORDINAL_WORDS + ")(?=\\s*(?:$|[,.;!?)]"
            + "|\\b(?:please|instead|then|at|so|because)\\b))"
            + "|\\b(" + ORDINAL_WORDS + ")(?=\\s+of\\s+(?:(?:the|this|next|the\\s+next|the\\s+coming|the\\s+following)"
            + "\\s+month|" + MONTH_NAMES + ")\\b)");
    /** "the end of March", "end of may 2028". */
    private static final Pattern END_OF_MONTH_NAMED = Pattern.compile("\\b(?:the\\s+)?end\\s+of\\s+" + MONTH + "\\b");
    /** "mid December", "mid-march", "the middle of June": the 15th. */
    private static final Pattern MID_MONTH = Pattern.compile("\\b(?:the\\s+)?(?:mid|middle\\s+of)(?:\\s+|-)" + MONTH + "\\b");
    /**
     * A month on its own ("by March"), spelt out and not followed by a day number or by "the" and an ordinal ("by
     * December the 3rd" is no month on its own), nor by a word that makes it part of a name or an event ("by June
     * Carter", "by the November newsletter", see {@link #NAME_END}). Abbreviations are left out: "Jan" and "May" are
     * names too ("a gift for Jan").
     */
    private static final Pattern PLAIN_MONTH = Pattern.compile("^(?:the\\s+)?(?:month\\s+of\\s+)?"
            + "(january|february|march|april|may|june|july|august|september|october|november|december)"
            + "\\b(?!\\.?\\s*\\d{1,2}(?!\\d))(?!,?\\s+the\\s+(?:\\d{1,2}(?:st|nd|rd|th)|" + ORDINAL_WORDS + ")\\b)"
            + NAME_END);
    /** Date words after which a month on its own is a deadline; "to May" and "for June" may name a person. */
    private static final Set<String> MONTH_PREPOSITIONS = Set.of("by", "until", "till", "in", "before", "due");
    /**
     * "end of the year", "end of this year", "year end", "year-end", "end of next year", "the last day of the year",
     * "end of 2027"; a year may follow ("end of year 2027", "year-end 2027", see {@link #year}).
     */
    private static final Pattern YEAR_END = Pattern.compile("\\b(?:(?:the\\s+)?(?:end|last\\s+day)\\s+of\\s+"
            + "(?:(this)\\s+year|(next)\\s+year|(?:the\\s+)?year|(\\d{4}))|year[- ]end)\\b");
    /**
     * What may follow a holiday besides what may follow a month (see {@link #NAME_END}): a time of that day
     * ("Christmas morning", "Easter Monday afternoon", "Halloween night", "Easter Monday 10am", "Easter Sunday after
     * church"), and a meal after a name that says the day ("Easter Sunday lunch", "Christmas Eve dinner"). After a
     * name alone a meal is an event that may fall on another day: "the Christmas lunch" of an office.
     */
    private static final String HOLIDAY_END = "(?:" + NAME_END + "|(?=\\s+(?:(?:morning|afternoon|evening|night|noon"
            + "|midday|midnight)\\b|\\d{1,2}(?::\\d{2})?\\s*(?:(?:am|pm|o'clock)\\b|a\\.m\\.|p\\.m\\.)"
            + "|(?:after|before)\\s+(?:(?:the|my|our|your)\\s+)?(?:work|school|class|church|mass|service|lunch|dinner"
            + "|breakfast|brunch|supper|noon)\\b)|(?<=(?:day|eve|sunday|monday|friday))\\s+(?:lunch(?:time)?|dinner"
            + "|breakfast|brunch|supper)\\b))";
    /**
     * A holiday after a date word: the fixed ones, and Thanksgiving (the fourth Thursday of November), Black Friday
     * and Cyber Monday after it, Easter, Easter Monday and Good Friday. A word after it that makes it part of an event
     * or a name ("the Christmas party", "the Easter break", "Christmas Humphreys", see {@link #HOLIDAY_END}) leaves it
     * no holiday.
     */
    private static final Pattern HOLIDAY = Pattern.compile("^(?:the\\s+)?(christmas eve|christmas day|christmas"
            + "|xmas eve|xmas day|xmas|new year'?s eve|new year'?s day|new year'?s|new year|halloween|valentine'?s day"
            + "|valentine'?s|thanksgiving day|thanksgiving|black friday|cyber monday|good friday|easter sunday"
            + "|easter monday|easter day|easter)\\b" + HOLIDAY_END);
    /**
     * A date word right before an explicit calendar date: "by April 12", "due the 3rd of May", "in March". The "in"
     * of "early in March" belongs to the part of the month.
     */
    private static final Pattern EXPLICIT_AFTER_PREPOSITION = Pattern.compile(
            "(?<!\\b(?:start|beginning|early|mid|middle|late|end)\\s{1,3})\\b(by|until|till|due|on|to|before|in|for)\\s+");
    /**
     * "the start of next month", "early next month", "the beginning of March", "late May 2028", "mid next month",
     * "the end of next month". The part decides the day: see {@link #partDay}.
     */
    private static final Pattern PART_OF_MONTH = Pattern.compile("\\b(?:the\\s+)?(start|beginning|early|mid|middle"
            + "|late|end)(?:\\s+of|\\s+in)?(?:\\s+|-)(?:(next|this|the)\\s+month|" + MONTH + ")\\b");

    /** "Friday next week", "Friday of next week", "on Tuesday the following week", "Friday this week". */
    private static final Pattern WEEKDAY_OF_WEEK = Pattern.compile("\\b" + WEEKDAY + "\\s+(?:of\\s+|in\\s+)?"
            + "(next|this|the\\s+following|the\\s+coming)\\s+week\\b");
    /** "next week Friday", "next week on Friday", "next week's Friday". */
    private static final Pattern WEEK_THEN_WEEKDAY = Pattern.compile("\\b(next|this)\\s+week(?:'s)?\\s+(?:on\\s+)?"
            + WEEKDAY + "\\b");
    /** "the week after next" (its Friday), "Monday of the week after next". */
    private static final Pattern WEEK_AFTER_NEXT = Pattern.compile("\\b(?:" + WEEKDAY + "\\s+(?:of\\s+|in\\s+)?)?"
            + "the\\s+week\\s+after\\s+next\\b");
    /** "the start of next week", "early next week", "the end of next week", "the end of the week after next". */
    private static final Pattern PART_OF_NEXT_WEEK = Pattern.compile("\\b(?:the\\s+)?(start|beginning|early|end)"
            + "(?:\\s+of)?\\s+(next\\s+week|the\\s+week\\s+after\\s+next)\\b");
    /** "the weekend after next", "the week-end after next": the Sunday a week after next weekend's. */
    private static final Pattern WEEKEND_AFTER_NEXT = Pattern.compile("\\b(?:the\\s+)?week-?end\\s+after\\s+next\\b");
    /**
     * "the end of the week", "this weekend", "the weekend": this week's Sunday; "after", "before", "following",
     * "preceding" or "of" makes it another ("the weekend after next", "the weekend after Christmas", "the weekend
     * just before Christmas", see {@link #WEEKEND_AROUND}; "the weekend of December 12", see {@link #WEEKEND_OF}).
     */
    private static final Pattern THIS_WEEKEND = Pattern.compile("\\b(?:end\\s+of\\s+(?:the\\s+|this\\s+)?week"
            + "|this\\s+week-?end|the\\s+week-?end)\\b(?!\\s+(?:(?:" + CLOSE + "|" + LOOSE + ")\\s+)?(?:after|before"
            + "|following|preceding|of)\\b)");
    /**
     * "the weekend after Christmas", "the weekend before the 20th", "the weekend following Christmas", "the weekend
     * right after Christmas": the Sunday of that weekend.
     */
    private static final Pattern WEEKEND_AROUND = Pattern.compile("\\b(?:the\\s+)?week-?end\\s+(?:" + CLOSE + "\\s+)?"
            + "(after|before|following|preceding)\\s+");
    /**
     * "the weekend of December 12", "the weekend of the 13th": the Sunday of the weekend that holds that day, which
     * must be a Saturday or a Sunday.
     */
    private static final Pattern WEEKEND_OF = Pattern.compile("\\b(?:the\\s+)?week-?end\\s+of\\s+");
    /**
     * A weekday and then "week" or "fortnight": "Friday week", "next Tuesday week", "Tuesday fortnight", "on Monday
     * week", a week or two after that weekday.
     */
    private static final Pattern WEEKDAY_WEEK = Pattern.compile("\\b(?:(next|this|on|by|before|until)\\s+)?" + WEEKDAY
            + "\\s+(week|fortnight)\\b");
    /**
     * An amount of weeks and an anchor without a word between: "a week today", "a week Friday", "two weeks tomorrow".
     */
    private static final Pattern WEEKS_ANCHOR = Pattern.compile("(?<!\\b(?:for|in|within|takes?|took|lasts?|about|over)"
            + "\\s{1,3})\\b" + NUMBER + "\\s+(weeks?|fortnights?)\\s+"
            + "((?:this\\s+|next\\s+)?(?:" + WEEKDAY_NAMES + ")|today|tonight|tomorrow)\\b");
    private static final Pattern NEXT_WEEKEND = Pattern.compile("\\bnext\\s+week-?end\\b");
    /** Today or tomorrow and then "week" or "fortnight": "tomorrow week", "today fortnight", a week or two after it. */
    private static final Pattern DAY_WORD_WEEK = Pattern.compile("\\b(today|tomorrow)\\s+(week|fortnight)\\b");

    /**
     * An amount counted from an anchor: "two weeks from today", "3 days from tomorrow", "a week after Christmas",
     * "two days before the 20th", "a week on Friday", "a fortnight on Tuesday" ("on" only for weeks or fortnights
     * counted from a weekday; "a month on Friday" names no date, see {@link #PARTIAL}).
     */
    private static final Pattern AMOUNT_FROM = Pattern.compile("\\b" + NUMBER + "\\s+" + UNIT
            + "\\s+(from|after|before|on)\\s+");
    /**
     * Right after an amount ("a week on Friday", "two days before the 20th") or "the" and a day or a period ("the
     * Friday before Christmas", "the weekend before Christmas", "the week before Christmas") "on" and "before" are no
     * date word: the phrase counts from its anchor.
     */
    private static final String NOT_AFTER_AMOUNT = "(?<!\\b(?:days?|weeks?|fortnights?|months?)\\s{1,3})"
            + "(?<!\\bthe\\s{1,3}(?:" + WEEKDAY_NAMES + "|day|weekend|week-end|week|fortnight|month|year)\\s{1,3}"
            + "(?:" + CLOSE + "\\s{1,3})?)";
    /**
     * Right after a weekday, "before" counts from it ("Friday before Christmas", "Friday just before Christmas", see
     * {@link #AROUND}).
     */
    private static final String NOT_AFTER_WEEKDAY = "(?<!\\b(?:" + WEEKDAY_NAMES + ")\\s{1,3}(?:" + CLOSE
            + "\\s{1,3})?)";
    /** An anchor followed by an amount: "today in two weeks", "Friday in three weeks". */
    private static final Pattern ANCHOR_IN = Pattern.compile("\\b(?<!')" + NAMED_DAY + "(today|tonight|now|tomorrow|"
            + WEEKDAY_NAMES + ")\\s+in\\s+" + NUMBER + "\\s+" + UNIT + "\\b");
    /**
     * A weekday or day just after or before an anchor: "the Monday after next", "the Monday after Christmas", "the
     * Friday before the 20th", "the day after Christmas", "the Monday following the conference", "the Friday
     * preceding Christmas", "the Friday just before Christmas", "the day right after Christmas" (see {@link #CLOSE}).
     * A weekday needs no "the" ("Friday before Christmas"); a day does. A weekday followed by a time of day ("Friday
     * after work", "Monday before noon") is that weekday.
     */
    private static final Pattern AROUND = Pattern.compile("\\b(?:(the)\\s+(?:first\\s+)?)?(?:" + WEEKDAY
            + "|(day))\\s+(?:" + CLOSE + "\\s+)?(after|before|following|preceding)\\s+");
    /** A time of day at the start of the words after a weekday and "after" or "before": "work", "noon", "5pm". */
    private static final Pattern TIME_OF_DAY = Pattern.compile("^(?:(?:the|my|our|your|his|her|their)\\s+)?(?:noon"
            + "|midday|midnight|lunch(?:time)?|dinner|breakfast|brunch|supper|work|school|class|church|gym|practice"
            + "|training|morning|afternoon|evening|night|tonight|close\\s+of\\s+business|end\\s+of\\s+(?:the\\s+)?"
            + "(?:work\\s*)?day|eod|cob|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.|h|o'clock)?)(?![\\w:/]|\\.\\d)");
    /** "after next" as a whole anchor: "next" not followed by what it would qualify. */
    private static final Pattern AFTER_NEXT = Pattern.compile("^next\\b(?!\\s+(?:week|month|year|weekend|"
            + WEEKDAY_NAMES + ")\\b)");
    private static final Pattern NOW = Pattern.compile("^(?:now|today|tonight)\\b");
    /** A weekday as the anchor of "a week on Friday". */
    private static final Pattern WEEKDAY_ANCHOR = Pattern.compile("^(?:(?:this|next)\\s+)?" + WEEKDAY + "\\b");
    /** Ways of naming the weekday of this week: "this Friday", "this coming Friday", "Friday this week". */
    private static final List<Pattern> THIS_WEEKDAY = List.of(
            Pattern.compile("\\bthis\\s+(?:coming\\s+)?" + WEEKDAY + "\\b"),
            Pattern.compile("\\b" + WEEKDAY + "\\s+(?:of\\s+|in\\s+)?this\\s+week\\b"),
            Pattern.compile("\\bthis\\s+week(?:'s)?\\s+(?:on\\s+)?" + WEEKDAY + "\\b"));
    private static final Pattern NEXT_WEEKDAY = Pattern.compile("\\bnext\\s+" + WEEKDAY + "\\b");
    /**
     * A "next" that stands for the weekday just named, after "but", a comma or a semicolon and before the end of
     * its clause: "not this Friday but next", "not this Friday, the next one".
     */
    private static final Pattern BARE_NEXT = Pattern.compile("(?:(?<=\\bbut)|(?<=[,;]))\\s+((?:the\\s+)?next"
            + "(?:\\s+one)?|the\\s+one\\s+after)(?=\\s*(?:[,.;!?]|$|\\b(?:please|then|instead|but|and)\\b))");
    /**
     * A pronoun for this week's weekday: "not this one", "rather than this one", "not the one this week". Set against
     * "next Friday" it names "this Friday".
     */
    private static final Pattern THIS_ONE = Pattern.compile("\\bthis\\s+(?:coming\\s+)?one\\b"
            + "|\\bthe\\s+one\\s+(?:of\\s+|in\\s+)?this\\s+week\\b|\\bthis\\s+week'?s\\s+one\\b");
    /** "The Friday after this one": a weekday after "this one", which then stands for this week's same weekday. */
    private static final Pattern AFTER_THIS_ONE = Pattern.compile("\\b" + WEEKDAY + "\\s+after\\s+this\\s+(one)\\b");
    /**
     * "Friday after this Friday": the {@link #contrastNext} form of a "next Friday" set against "this Friday", the
     * first Friday after the date "this Friday" names.
     */
    private static final Pattern AFTER_THIS = Pattern.compile("\\b(?:the\\s+)?" + WEEKDAY + "\\s+after\\s+this\\s+"
            + WEEKDAY + "\\b");

    private DateExpressions() {
    }

    // ---- bounds ----
    //
    // Every reader looks at a date word and the few words after or before it. A reader that follows a clause from a
    // date word reads at most CLAUSE_REACH characters of it, a date counted from an anchor reads at most
    // MAX_ANCHOR_DEPTH anchors deep, a phrase whose anchor names no date is read once, the clause ends and date words
    // a mask looks at are found once per text (see Marks), and findTarget reads each clause part once and at most
    // MAX_TARGET_READS of them (see TargetClauses), so the time a text takes grows with its length and not with how
    // often its words repeat.

    /** How many characters of a clause a reader follows from the word it starts at. */
    private static final int CLAUSE_REACH = 200;
    /**
     * How many anchors deep a date counted from an anchor is read: "two weeks from the day after Christmas" counts
     * from an anchor ("the day after Christmas") that counts from another ("Christmas"). Deeper phrases name no date.
     */
    private static final int MAX_ANCHOR_DEPTH = 2;
    /** How many dates joined into one list ("Thursday, Friday or Saturday don't work") a negation reaches. */
    private static final int MAX_LIST_ITEMS = 12;
    /** How often a reader looks again after a phrase it read is followed by a year it did not take. */
    private static final int MAX_REREADS = 8;

    /**
     * The end of the word that holds the character {@code reach} characters after {@code from} in {@code t}, or the
     * end of {@code t}: a bound for a scan that never cuts a word in two.
     */
    private static int reachEnd(String t, int from, int reach) {
        int end = from + reach;
        if (end >= t.length()) {
            return t.length();
        }
        while (end < t.length() && wordChar(t.charAt(end))) {
            end++;
        }
        return end;
    }

    /**
     * Where the clause that starts at {@code from} in {@code t} ends (see {@link #clauseEnd}), looking at most
     * {@link #CLAUSE_REACH} characters ahead: a clause that goes on further ends there.
     */
    private static int clauseEndNear(String t, int from) {
        int limit = reachEnd(t, from, CLAUSE_REACH);
        Matcher end = CLAUSE_END.matcher(t).region(from, limit).useTransparentBounds(true).useAnchoringBounds(false);
        return end.find() ? end.start() : limit;
    }

    /**
     * Where the clause that holds {@code at} in {@code t} starts (see {@link #clauseStart}), looking at most
     * {@link #CLAUSE_REACH} characters back: a clause that began further back starts at the first whole word there.
     */
    private static int clauseStartNear(String t, int at) {
        int from = reachStart(t, at);
        Matcher end = CLAUSE_END.matcher(t).region(from, at).useTransparentBounds(true);
        int start = from;
        while (end.find()) {
            start = end.end();
        }
        return start;
    }

    /**
     * The start of the first whole word at most {@link #CLAUSE_REACH} characters before {@code at} in {@code t}, or 0:
     * the bound of a scan back from {@code at} that never cuts a word in two.
     */
    private static int reachStart(String t, int at) {
        int from = Math.max(0, at - CLAUSE_REACH);
        while (from > 0 && from < at && wordChar(t.charAt(from - 1)) && wordChar(t.charAt(from))) {
            from++;
        }
        return from;
    }

    /**
     * Where the word that ends right before the white space before {@code at} starts in {@code t}; -1 when no white
     * space or no word is there. It stands in for a pattern that ends with {@code \s+$} and is matched against all of
     * the text before {@code at}, at the cost of the white space and the word only.
     */
    private static int wordBeforeStart(String t, int at) {
        int end = at;
        while (end > 0 && Character.isWhitespace(t.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && wordChar(t.charAt(start - 1))) {
            start--;
        }
        return end == at || start == end ? -1 : start;
    }

    /** The word that ends right before the white space before {@code at} (see {@link #wordBeforeStart}), or "". */
    private static String wordBefore(String t, int at) {
        int start = wordBeforeStart(t, at);
        if (start < 0) {
            return "";
        }
        int end = start;
        while (end < at && wordChar(t.charAt(end))) {
            end++;
        }
        return t.substring(start, end);
    }

    /** The index of the first value in {@code sorted} (ascending, each value once) that is at least {@code at}. */
    private static int firstAtLeast(int[] sorted, int at) {
        int i = Arrays.binarySearch(sorted, at);
        return i >= 0 ? i : -i - 1;
    }

    /**
     * {@code pattern} matched at {@code from} in {@code t}, the rest of {@code t} seen as the whole text; null when it
     * does not match there.
     */
    private static Matcher startingAt(Pattern pattern, String t, int from) {
        Matcher m = pattern.matcher(t).region(from, t.length());
        return m.lookingAt() ? m : null;
    }

    private static final String AMOUNT = NUMBER + "\\s+" + UNIT;
    /** An amount on its own: "just two days". */
    private static final Pattern BARE_AMOUNT = Pattern.compile("\\b" + AMOUNT + "\\b");
    /**
     * "back a week", "forward by 3 days", "out two weeks". The direction word must stand alone: the "up" of a
     * hyphenated noun ("the check-up a week later") is not one.
     */
    private static final Pattern SHIFT_BEFORE = Pattern.compile(
            "(?<![\\w-])(back|later|out|forward|earlier|ahead|up)\\s+(?:by\\s+)?" + AMOUNT + "\\b");
    /** "two weeks later", "3 days earlier". */
    private static final Pattern SHIFT_AFTER = Pattern.compile("\\b" + AMOUNT + "\\s+(later|earlier|sooner|back|forward)\\b");
    /**
     * A verb that moves a date later, with an amount and no direction word: "postpone it a couple of days", "delay
     * the launch by a week", "push it a week", "push it by 3 days", "extend the deadline by a week".
     */
    private static final Pattern SHIFT_VERB = Pattern.compile(
            "\\b(postpone|delay|push|bump|slide|extend)\\b(?:\\s+[a-z']+){0,4}?\\s+(?:by\\s+)?" + AMOUNT + "\\b");
    /** "add a week", "add 3 days to the deadline", "add another week". */
    private static final Pattern SHIFT_ADD = Pattern.compile("\\badd\\s+(?:on\\s+)?(?:another\\s+)?" + AMOUNT
            + "\\b(?!\\s+off\\b)");
    /** "two more days", "3 extra days", "one more week": that much later. */
    private static final Pattern SHIFT_MORE = Pattern.compile("\\b" + NUMBER + "\\s+(?:more|extra)\\s+" + UNIT + "\\b");
    /** "another week", "another 3 days", "an extra week": that much later. */
    private static final Pattern SHIFT_ANOTHER = Pattern.compile("\\b(?:another|an?\\s+extra)\\s+(?:" + NUMBER
            + "\\s+)?" + UNIT + "\\b");

    /**
     * A move relative to an existing date: "push it back a week", "two days later", "bring it forward by 3 days",
     * "postpone it a couple of days". The direction word next to the amount decides, and "later", "earlier" or
     * "sooner" right after the amount wins over a word before it; without a direction word, "push", "postpone",
     * "delay", "extend", "add", "N more" and "another" move later ("push it by a week", "extend it by two days", "add
     * a week", "give me two more days", "I need another week"). A bare "2 days" ("it takes 2 days") is not a move.
     * A move in a negated part of the sentence is not made: "don't push it back a week" is no move, and "don't push
     * it back a week, just two days" moves back two days (the negated move's direction with the amount left).
     * Empty when the text names no relative move, or an amount that is no whole number of days, weeks or months
     * ("a week and a half", "a day or two").
     */
    public static Optional<LocalDate> shift(String text, LocalDate from) {
        if (text == null || from == null) {
            return Optional.empty();
        }
        String raw = normalise(text);
        String t = mask(raw, false);
        if (UNREAD_AMOUNT.matcher(t).find()) {
            return Optional.empty();
        }
        return moveIn(t)
                .or(() -> moveIn(raw).flatMap(negated -> bareAmount(t)
                        .map(amount -> new Move(negated.earlier(), amount.amount(), amount.unit()))))
                .map(m -> plus(from, (m.earlier() ? -1L : 1L) * number(m.amount()), m.unit()));
    }

    /** A relative move: its direction and amount. */
    private record Move(boolean earlier, String amount, String unit) {
    }

    private static Optional<Move> moveIn(String t) {
        Matcher m = SHIFT_AFTER.matcher(t);
        while (m.find()) {
            if (!Set.of("back", "forward").contains(m.group(3))) {
                return Optional.of(new Move(earlier(m.group(3)), m.group(1), m.group(2)));
            }
        }
        m = SHIFT_BEFORE.matcher(t);
        if (m.find()) {
            return Optional.of(new Move(earlier(m.group(1)), m.group(2), m.group(3)));
        }
        m = SHIFT_AFTER.matcher(t);
        if (m.find()) {
            return Optional.of(new Move(earlier(m.group(3)), m.group(1), m.group(2)));
        }
        m = SHIFT_VERB.matcher(t);
        if (m.find()) {
            return Optional.of(new Move(false, m.group(2), m.group(3)));
        }
        for (Pattern later : List.of(SHIFT_ADD, SHIFT_MORE, SHIFT_ANOTHER)) {
            m = later.matcher(t);
            if (m.find()) {
                return Optional.of(new Move(false, m.group(1) == null ? "a" : m.group(1), m.group(2)));
            }
        }
        return Optional.empty();
    }

    private static Optional<Move> bareAmount(String t) {
        Matcher m = BARE_AMOUNT.matcher(t);
        return m.find() ? Optional.of(new Move(false, m.group(1), m.group(2))) : Optional.empty();
    }

    private static boolean earlier(String direction) {
        return Set.of("forward", "earlier", "ahead", "up", "sooner").contains(direction);
    }

    private static LocalDate plus(LocalDate from, long n, String unit) {
        if (unit.startsWith("day")) {
            return from.plusDays(n);
        }
        if (unit.startsWith("week")) {
            return from.plusWeeks(n);
        }
        return unit.startsWith("fortnight") ? from.plusWeeks(2 * n) : from.plusMonths(n);
    }

    /**
     * Lower case, with typographic apostrophes as plain ones, "on or before" as "on or by" ("on or before Friday"
     * includes Friday, unlike "before Friday"), and "ahead of" and "prior to" as "before". Each character is lowered
     * on its own and the rewrites keep the length, so every character keeps its place and an index into the result
     * is an index into {@code text} ("İ" becomes "i", not the two characters "i̇" that lowering the whole string
     * gives).
     */
    private static String normalise(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = Character.toLowerCase(chars[i]);
            chars[i] = c == '\u2018' || c == '\u2019' ? '\'' : c;
        }
        String lower = ON_OR_BEFORE.matcher(new String(chars)).replaceAll("on or     by");
        return BEFORE_WORDS.matcher(lower).replaceAll("before  ");
    }

    private static final Pattern ON_OR_BEFORE = Pattern.compile("\\bon or before\\b");
    private static final Pattern BEFORE_WORDS = Pattern.compile("\\b(?:ahead of|prior to)\\b");

    /** Words that aim a change at a date. Strong ones say so outright; weak ones often just place an event. */
    private static final Pattern STRONG_TARGET = Pattern.compile("\\b(?:to|until|till|by|due)\\s+");
    private static final Pattern WEAK_TARGET = Pattern.compile(NOT_AFTER_AMOUNT + "(?:\\b(?:on|for)|" + NOT_AFTER_WEEKDAY
            + "\\bbefore)\\s+");
    private static final Pattern ANY_TARGET = Pattern.compile(NOT_AFTER_AMOUNT
            + "(?:\\b(?:to|until|till|by|due|on|for)|" + NOT_AFTER_WEEKDAY + "\\bbefore)\\s+");
    /** The last word of a fixed holiday's name, after which a year may follow a comma ("by Christmas, 2027"). */
    private static final String HOLIDAY_NAMES = "christmas|xmas|eve|day|halloween|year|years|year's|valentines"
            + "|valentine's|thanksgiving|friday|monday|sunday|easter";
    /**
     * A comma that belongs to the date around it: between a weekday and a day of the month or a month ("Friday,
     * 27th", "Friday, June 2nd", "Friday, 27 November", "Tuesday, 1st of December"), and between a day, month or
     * holiday and a year ("June 1, 2028", "1 June, 2028", "Dec 25th, 2027", "Christmas, 2027"), and between a month and
     * "the" with a day ("December, the 3rd").
     */
    private static final String COMMA_IN_DATE = "(?<=(?:" + WEEKDAY_NAMES + "),)\\s*(?:(?:the\\s+)?\\d{1,2}"
            + "(?:st|nd|rd|th)|\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?" + MONTH + "|" + MONTH + "|\\d{1,2}" + NOT_A_BARE_DAY
            + "|(?:the\\s+)?\\d{1,2}[/.-]\\d{1,2}(?=[/.\\s,;!?)-]|$))\\b"
            + "|(?<=(?:\\d|\\d(?:st|nd|rd|th)|" + MONTH_NAMES + "|" + HOLIDAY_NAMES + "),)\\s*(?:of\\s+)?(?:\\d{4}|'\\d{2}"
            + "|(?:next|this)\\s+year)\\b"
            + "|(?<=(?:" + MONTH_NAMES + "),)\\s*the\\s+(?:\\d{1,2}(?:st|nd|rd|th)|" + ORDINAL_WORDS + ")\\b";
    /**
     * Ends a clause: punctuation, a dash between spaces ("not urgent - by Friday") or a joining word, with the white
     * space before it. A comma inside a date ({@link #COMMA_IN_DATE}) and a full stop before a number ("Nov. 10")
     * belong to the date. The white space is taken only from where it begins, so a scan over a long run of it does
     * not try every place in it.
     */
    private static final Pattern CLAUSE_END = Pattern.compile("(?:(?<!\\s)\\s++)?(?:,(?!" + COMMA_IN_DATE + ")|\\.(?!\\s*\\d)"
            + "|[;!?]|:(?=\\s|$)|(?<=\\s)[-\\u2013\\u2014]{1,2}(?=\\s)"
            + "|\\b(?:because|since|as|but|and|so|or|although|though|while)\\b)");
    /** The clause ends that join items of a list: "Thursday and Friday", "Thursday or Friday". */
    private static final Pattern LIST_JOIN = Pattern.compile("\\s*(?:and|or)");
    /**
     * Starts a negated part of the sentence: its dates are ones the change is not aimed at ("nothing on Friday,
     * Monday" is Monday). "No later than", "not later than" and "don't forget" or "don't miss" are not negations of
     * a date. The "but" of "any day but", "anything but" or "every day but" excludes what follows it.
     */
    private static final Pattern NEGATION = Pattern.compile(
            "\\b(?:not|never|no|nothing|cannot|can not|unable to|except|instead of|rather than|other than|[a-z]+n't"
                    + "|dont|doesnt|didnt|cant|wont|isnt|arent|wasnt|werent|wouldnt|shouldnt|couldnt|mustnt"
                    + "|(?<=\\b(?:any|every)\\s?(?:day|days|time|date|thing|one|weekday)?\\s)but)\\b"
                    + "(?!\\s+(?:later|more)\\s+than\\b)(?!\\s+(?:forget|miss)\\b)");
    /**
     * Words that, after a date and "is", "are", "'s" or "'re", say the date does not work: "Friday is out", "Monday's
     * taken", "the 20th is too soon", "Friday's fully booked". A regular expression alternation in lower case, without
     * groups, so other readers of the person's words rule out the same dates (see {@link #RULED_OUT_STATE}).
     */
    public static final String RULED_OUT_STATES = "out|off|impossible|bad|taken|booked|full|busy|unavailable"
            + "|a\\s+no[- ]?go|no\\s+good|too\\s+(?:soon|early|late|close|tight|far)";
    /**
     * A copula and a word of {@link #RULED_OUT_STATES}, with words that may stand between them ("are both out", "is
     * fully booked", "'s really too soon"). A regular expression in lower case, without groups.
     */
    public static final String RULED_OUT_STATE = "(?:\\b(?:is|are)|'s|'re)\\s+(?:(?:both|all|also|really|just"
            + "|definitely|completely|totally|fully|already|now|pretty|way|far|much)\\s+)*(?:" + RULED_OUT_STATES
            + ")\\b";
    /**
     * Words after a date that say it does not work ({@link #RULED_OUT_STATE}). They rule out the date only when the
     * words before them are a date and nothing else (see {@link #dateIsSubject}).
     */
    private static final Pattern RULED_OUT_AFTER = Pattern.compile(RULED_OUT_STATE);
    /**
     * Negations that set what follows them against what comes before ("Monday rather than Friday", "Monday instead
     * of Friday"): they rule out only the dates after them, never a date before them as their subject.
     */
    private static final Pattern EXCLUSION = Pattern.compile("except|instead of|rather than|other than|but");
    /** "Not", "no" or "never" right before a date ("Monday not Friday") excludes that date, as "rather than" does. */
    private static final Pattern PLAIN_NOT = Pattern.compile("not|no|never");
    private static final Pattern DATE_NEXT = Pattern.compile("\\s+(?:(?:on|this|next|the|by|for)\\s+)*(?:today|tonight"
            + "|tomorrow|weekend|christmas|xmas|halloween|easter|thanksgiving|" + WEEKDAY + "|" + MONTH + "|\\d{4}-\\d{2}-\\d{2}|" + ORDINAL
            + ")\\b");
    /** "Not until Friday" names Friday: a negated part ends before "until" or "till". */
    private static final Pattern UNTIL = Pattern.compile("\\b(?:until|till)\\b");
    /** A word that names a day, used to find where a negated date ends. */
    private static final Pattern DATE_WORD = Pattern.compile("\\b(?:today|tonight|tomorrow|weekend|week|month|year"
            + "|christmas|xmas|halloween|easter|thanksgiving|" + WEEKDAY + "|" + MONTH + "|\\d{4}-\\d{2}-\\d{2}|" + ORDINAL + ")\\b");
    /** "from" that starts where a move comes from; the "from" of "two weeks from today" counts from an anchor. */
    private static final Pattern FROM = Pattern.compile("(?<!\\b(?:days?|weeks?|fortnights?|months?)\\s{1,3})\\bfrom\\b");
    /** A date word right after the start of a masked part, kept inside it ("from by Friday"). */
    private static final Pattern LEADING_PREPOSITION = Pattern.compile("^\\s+(?:on|by|to|until|till|for|due|before)\\b");
    /** Words that may stand with a date as the subject of a negated verb: "next Friday is really not good". */
    private static final Pattern SUBJECT_FILLER = Pattern.compile("next|this|the|of|on|for|by|morning|afternoon|evening"
            + "|night|is|are|was|does|do|will|would|really|just|probably|definitely|also|\\d{1,4}");
    /** Negations of being able to: "today I can't" makes today the date that does not work. */
    private static final Pattern UNABLE = Pattern.compile("can't|cannot|can not|won't|unable to|couldn't|cant|wont|couldnt");

    /**
     * The date a change is aimed at. The first clause introduced by "to", "until", "till", "by" or "due" that
     * holds a date decides ("move it to Saturday because I'm busy on Friday" is Saturday); within that clause the
     * last date word wins ("to tell Ana about Friday's party by monday" is Monday). Without such a clause the first
     * one introduced by "on", "for" or "before" decides; otherwise the first date anywhere. The date word stays with
     * its date, so "by Wednesday" said on a Wednesday is today. A date after "from" ("move it from Friday to Monday"
     * is Monday) or in a negated part of the sentence ("not Friday") is never the answer. An "on", "for" or "before"
     * after a copula ("the birthday is on the 25th, plan the party for the 24th") places another event: it decides
     * only when no other such word gives a date. Empty for a phrase the parser cannot read whole (see the class
     * comment).
     */
    public static Optional<LocalDate> findTarget(String text, LocalDate today) {
        if (text == null || today == null) {
            return Optional.empty();
        }
        String masked = prepared(text, true);
        if (unread(masked, today)) {
            return Optional.empty();
        }
        TargetClauses clauses = new TargetClauses(masked, today);
        List<Integer> describing = new ArrayList<>();
        for (Pattern keywords : List.of(STRONG_TARGET, WEAK_TARGET)) {
            Matcher m = keywords.matcher(masked);
            while (m.find()) {
                if (keywords == WEAK_TARGET && COPULA.matcher(wordBefore(masked, m.start())).matches()) {
                    describing.add(m.start()); // "the birthday is on the 25th" says when something else is
                    continue;
                }
                Optional<LocalDate> found = clauses.dateFrom(m.start());
                if (found.isPresent() || clauses.exhausted()) {
                    return found;
                }
            }
        }
        for (int start : describing) {
            Optional<LocalDate> found = clauses.dateFrom(start);
            if (found.isPresent() || clauses.exhausted()) {
                return found;
            }
        }
        return findIn(masked, today);
    }

    /**
     * How many clause parts {@link #findTarget} reads from date words before it gives up: a text with more date words
     * that may start a date than that names no target date. An everyday sentence has a handful.
     */
    private static final int MAX_TARGET_READS = 48;

    /**
     * A copula, the word right before "on", "for" or "before" that makes it place an event instead of aiming at a
     * date: "the party is on Friday", "it's on the 25th".
     */
    private static final Pattern COPULA = Pattern.compile("is|are|was|were|falls?|fell|.*'s|.*'re");

    /**
     * The target clauses of one prepared text (see {@link #findTarget}), each word of them read once. In a clause
     * that a date word starts, the date after the last date word that names one decides, else the first date in the
     * clause; every date word and the clause's first word are read at most once, whichever date word the clause is
     * asked for, and each from at most {@link #CLAUSE_REACH} characters of its clause. A part without a word that may
     * start a date (see {@link #DATE_HINT}) names none; after {@link #MAX_TARGET_READS} parts read, no clause names a
     * date.
     */
    private static final class TargetClauses {
        private final String text;
        private final LocalDate today;
        /** Where the date words of {@link #ANY_TARGET} start, in order. */
        private final int[] words;
        /** Where the clause ends of {@link #CLAUSE_END} start, in order. */
        private final int[] ends;
        /** Where the words of {@link #DATE_HINT} start, in order. */
        private final int[] hints;
        /**
         * Per clause end: the lowest index into {@link #words} read so far going back from the clause end, and the
         * index of the last date word of the clause that names a date (-1 while none is known).
         */
        private final Map<Integer, int[]> scans = new HashMap<>();
        /** The date read from each start, up to its clause end. */
        private final Map<Integer, Optional<LocalDate>> dates = new HashMap<>();
        /** How many parts were read. */
        private int reads;

        TargetClauses(String text, LocalDate today) {
            this.text = text;
            this.today = today;
            this.words = starts(ANY_TARGET.matcher(text));
            this.ends = starts(CLAUSE_END.matcher(text));
            this.hints = starts(DATE_HINT.matcher(text));
        }

        private static int[] starts(Matcher m) {
            List<Integer> starts = new ArrayList<>();
            while (m.find()) {
                starts.add(m.start());
            }
            return starts.stream().mapToInt(Integer::intValue).toArray();
        }

        /** The date of the clause that starts at {@code start}: see the class comment. */
        Optional<LocalDate> dateFrom(int start) {
            int next = firstAtLeast(ends, start + 1);
            int end = next < ends.length ? ends[next] : text.length();
            int first = firstAtLeast(words, start);
            int[] scan = scans.computeIfAbsent(end, e -> new int[]{firstAtLeast(words, e), -1});
            while (scan[1] < 0 && scan[0] > first && !exhausted()) {
                scan[0]--;
                if (date(words[scan[0]], end).isPresent()) {
                    scan[1] = scan[0];
                }
            }
            if (scan[1] >= first) {
                return date(words[scan[1]], end);
            }
            return scan[1] < 0 && scan[0] > first ? Optional.empty() : date(start, end);
        }

        /** True once {@link #MAX_TARGET_READS} parts were read: then no clause names a date. */
        boolean exhausted() {
            return reads >= MAX_TARGET_READS;
        }

        private Optional<LocalDate> date(int from, int end) {
            Optional<LocalDate> known = dates.get(from);
            if (known != null) {
                return known;
            }
            int to = Math.min(end, reachEnd(text, from, CLAUSE_REACH));
            int hint = firstAtLeast(hints, from);
            Optional<LocalDate> date = Optional.empty();
            if (hint < hints.length && hints[hint] < to && !exhausted()) {
                reads++;
                date = findIn(text.substring(from, to), today);
            }
            dates.put(from, date);
            return date;
        }
    }

    /**
     * The start of a word without which no date can be read: a digit, or a word that starts like a weekday, a month,
     * today, tonight, tomorrow, a unit of calendar time or a holiday. Every date phrase holds one ("by Friday", "the
     * 20th", "in two weeks", "by Christmas", "the end of the month"), so a text without one names no date. Words that
     * only start the same way ("money", "market") are fine: they only make the text worth reading.
     */
    private static final Pattern DATE_HINT = Pattern.compile("\\d|\\b(?:mon|tue|wed|thu|fri|sat|sun|jan|feb|mar"
            + "|apr|may|jun|jul|aug|sep|oct|nov|dec|today|tonight|tomorrow|day|week|fortnight|month|year|christmas|xmas"
            + "|halloween|easter|thanksgiving|valentine|new)");

    /**
     * The text as {@link #findIn} reads it: lower case, a contrasted "next Friday" rewritten (see
     * {@link #contrastNext}), recurring days blanked (see {@link #RECURRENCE}), the end of the day as today (see
     * {@link #endOfDay}), ordinal words that name a day as digits (see {@link #ordinals}), and the parts that name
     * dates a change is not aimed at blanked (see {@link #mask}).
     */
    private static String prepared(String text, boolean from) {
        return mask(ordinals(endOfDay(withoutRecurrences(normalise(contrastNext(text))))), from);
    }

    /** The end of a working day: "(at) the end of the day", "end of day", "EOD", "close of business". */
    private static final Pattern END_OF_DAY = Pattern.compile("(?:\\bat\\s+)?\\b(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?"
            + "(?:work(?:ing)?\\s*|business\\s+)?(?:to)?day\\b|\\beod\\b|\\bclose\\s+of\\s+business\\b|\\bcob\\b");
    /** A day named right before the end of the day ("Friday end of day", "tomorrow at the end of the day"). */
    private static final Pattern DAY_BEFORE = Pattern.compile("(?:" + WEEKDAY_NAMES + "|" + MONTH_NAMES
            + "|tomorrow|today|tonight|\\d)\\W*(?:\\w+\\W+){0,2}$");
    /** A day named right after the end of the day ("by end of day Friday", "end of day on the 5th"). */
    private static final Pattern DAY_AFTER = Pattern.compile("^\\W*(?:(?:on|of)\\s+)?(?:the\\s+)?(?:"
            + WEEKDAY_NAMES + "|" + MONTH_NAMES + "|tomorrow|today|tonight|\\d)\\b");

    /**
     * The end of the day standing alone ("pack until at the end of the day", "send it by EOD") written as "today",
     * with spaces before it for the letters it replaces, so every index into the result is an index into {@code t}.
     * The end of a named day ("Friday end of day", "by end of day Friday") is left to that day.
     */
    static String endOfDay(String t) {
        Matcher m = END_OF_DAY.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String before = t.substring(Math.max(0, m.start() - 30), m.start());
            boolean namedDay = DAY_BEFORE.matcher(before).find() || DAY_AFTER.matcher(t.substring(m.end())).find();
            String replacement = namedDay || m.end() - m.start() < "today".length()
                    ? m.group() : " ".repeat(m.end() - m.start() - "today".length()) + "today";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        return m.appendTail(out).toString();
    }

    /**
     * Ordinal words that name a day of the month (see {@link #ORDINAL_WORD_DATE}) written as digits, with spaces
     * before them for the letters they replace ("the twenty-fifth" is "the         25th"), so every character keeps
     * its place and an index into the result is an index into {@code t}.
     */
    static String ordinals(String t) {
        Matcher m = ORDINAL_WORD_DATE.matcher(t);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String word = null;
            for (int g = 1; g <= m.groupCount() && word == null; g++) {
                word = m.group(g);
            }
            String digits = digitOrdinal(ordinalValue(word));
            m.appendReplacement(out, " ".repeat(m.end() - m.start() - digits.length()) + digits);
        }
        return m.appendTail(out).toString();
    }

    /**
     * "After" and a date ("after Friday", "after the weekend", "after next week", "after Christmas"): a time after
     * that date, not a day. The "after" of "the day after tomorrow", "the Monday after Christmas", "the Monday just
     * after Christmas", "a week after Friday" and "the weekend after next" belongs to a phrase that is read whole.
     */
    private static final Pattern AFTER_DATE = Pattern.compile("(?<!\\b(?:day|days|week|weeks|fortnight|fortnights|month"
            + "|months|year|years|weekend|week-end|today|tomorrow|the|" + WEEKDAY_NAMES + ")\\s{1,3})"
            + "(?<!\\b(?:day|weekend|week-end|" + WEEKDAY_NAMES + ")\\s{1,3}" + CLOSE + "\\s{1,3})\\bafter\\s+"
            + "(?=(?:the\\s+|this\\s+|next\\s+|that\\s+)?(?:week-?end|week|month|year|today|tonight|tomorrow|christmas"
            + "|xmas|halloween|new\\s+year|easter|thanksgiving|valentine|black\\s+friday|" + WEEKDAY_NAMES + "|"
            + MONTH_NAMES + "|\\d)\\b)");
    /**
     * An amount of days, weeks or months that is no whole number of them, or no single one: "in a week and a half",
     * "a month and a half from now", "in a week or two", "a day or so", "in a week and a day", "two weeks and 3 days".
     */
    private static final Pattern UNREAD_AMOUNT = Pattern.compile("\\b" + AMOUNT + "\\s+(?:and\\s+a\\s+half|or\\s+(?:two"
            + "|three|so|more|\\d+)|and\\s+" + AMOUNT + ")\\b");

    /**
     * True when {@code t} holds a date phrase the parser cannot read whole, so a date read from its parts would be a
     * wrong one: an ordinal phrase (see {@link #unreadOrdinal}), "after" a date ({@link #AFTER_DATE}), an amount that
     * is not whole ({@link #UNREAD_AMOUNT}), or a phrase of which only a part has a reading ({@link #PARTIAL}: "the
     * end of the month after next" is not the end of this month, "mid next week" not next week's Friday).
     */
    private static boolean unread(String t, LocalDate today) {
        return AFTER_DATE.matcher(t).find() || UNREAD_AMOUNT.matcher(t).find() || PARTIAL.matcher(t).find()
                || unreadOrdinal(t, today);
    }

    /**
     * True when {@code t} holds a phrase that starts with an ordinal (see {@link #ORDINAL_PHRASE}) that no form of
     * the parser reads whole, or that names a day the month lacks ("the fifth Monday of next month").
     */
    private static boolean unreadOrdinal(String t, LocalDate today) {
        Matcher m = ORDINAL_PHRASE.matcher(t);
        while (m.find()) {
            int at = m.start();
            Matcher nthWeekday = NTH_WEEKDAY_OF.matcher(t).region(at, t.length());
            Matcher nthDay = NTH_DAY_OF.matcher(t).region(at, t.length());
            boolean read;
            if (nthWeekday.lookingAt()) {
                read = nthWeekdayOf(nthWeekday, nthWeekday.group(5) != null ? year(t, nthWeekday.end(), today)
                        : Year.none(nthWeekday.end()), today).isPresent();
            } else if (nthDay.lookingAt()) {
                read = nthDayOf(nthDay, nthDay.group(4) != null ? year(t, nthDay.end(), today)
                        : Year.none(nthDay.end()), today).isPresent();
            } else {
                read = YEAR_END.matcher(t).region(at, t.length()).lookingAt()
                        || FIRST_AROUND.matcher(t).region(at, t.length()).lookingAt()
                        && wordBefore(t, at).equals("the");
            }
            if (!read) {
                return true;
            }
        }
        return false;
    }

    /** "the first Monday after Christmas": read whole by {@link #AROUND}. */
    private static final Pattern FIRST_AROUND = Pattern.compile("first\\s+" + WEEKDAY + "\\s+(?:after|before)\\s+");

    /** The text from {@code start} up to the end of its clause, at most {@link #CLAUSE_REACH} characters of it. */
    private static String clause(String text, int start) {
        return text.substring(start, clauseEndNear(text, start));
    }

    /**
     * Where the clause that starts at {@code from} ends in {@code text}: at punctuation, a dash between spaces or a
     * joining word ("because", "but", "and", ...), else at the end of the text. A comma inside a date ("June 1,
     * 2028", "1 June, 2028", "Friday, June 2nd", "Friday, 27th") and a full stop before a number ("Nov. 10") do not
     * end it. Indices are those of {@code text}, whatever its case.
     */
    public static int clauseEnd(String text, int from) {
        Matcher end = CLAUSE_END.matcher(normalise(text).substring(from));
        return end.find() ? from + end.start() : text.length();
    }

    /**
     * When the sentence names "this Friday" ("this coming Friday", "Friday this week"), its "next Friday" is the
     * Friday a week after that one ("move it to next Friday, not this Friday"), and so is a "next" that stands alone
     * after "but" or a comma ("not this Friday but next", "not this Friday, the next one", "not this coming Friday,
     * the one after") when only one weekday is named that way. "This one" set against the only weekday said with
     * "next" names that weekday of this week ("next Friday, not this one", "next Friday rather than this one", "next
     * Friday, not the one this week"), and "the Friday after this one" is "the Friday after this Friday". Each such
     * phrase is rewritten as "Friday after this Friday", which the parser reads as the first Friday after the date
     * "this Friday" names, whatever day today is. Letters are matched in any case and everything else in the text is
     * kept as it is, so the result can be read like the original sentence. Apply it to the whole sentence before
     * cutting it into clauses: the contrast often sits in another clause. Null stays null.
     */
    public static String contrastNext(String text) {
        if (text == null) {
            return null;
        }
        text = namedThisOne(text);
        String lower = normalise(text);
        Set<DayOfWeek> named = EnumSet.noneOf(DayOfWeek.class);
        for (Pattern thisWeekday : THIS_WEEKDAY) {
            Matcher m = thisWeekday.matcher(lower);
            while (m.find()) {
                named.add(weekday(m.group(1)));
            }
        }
        if (THIS_ONE.matcher(lower).find()) {
            Set<DayOfWeek> next = EnumSet.noneOf(DayOfWeek.class);
            Matcher m = NEXT_WEEKDAY.matcher(lower);
            while (m.find()) {
                next.add(weekday(m.group(1)));
            }
            if (next.size() == 1) {
                named.addAll(next);
            }
        }
        if (named.isEmpty()) {
            return text;
        }
        TreeMap<Integer, int[]> spans = new TreeMap<>();
        Map<Integer, String> names = new HashMap<>();
        Matcher next = NEXT_WEEKDAY.matcher(lower);
        while (next.find()) {
            if (named.contains(weekday(next.group(1)))) {
                spans.put(next.start(), new int[]{next.start(), next.end()});
                names.put(next.start(), text.substring(next.start(1), next.end(1)));
            }
        }
        if (named.size() == 1) {
            String name = named.iterator().next().name().toLowerCase(Locale.ROOT);
            Matcher bare = BARE_NEXT.matcher(lower);
            while (bare.find()) {
                spans.put(bare.start(1), new int[]{bare.start(1), bare.end(1)});
                names.put(bare.start(1), name);
            }
        }
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (Map.Entry<Integer, int[]> span : spans.entrySet()) {
            String name = names.get(span.getKey());
            out.append(text, last, span.getValue()[0]).append(name).append(" after this ").append(name);
            last = span.getValue()[1];
        }
        return out.append(text, last, text.length()).toString();
    }

    /** {@code text} with the "one" of "the Friday after this one" replaced by the weekday it stands for. */
    private static String namedThisOne(String text) {
        Matcher m = AFTER_THIS_ONE.matcher(normalise(text));
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start(2)).append(text, m.start(1), m.end(1));
            last = m.end(2);
        }
        return last == 0 ? text : out.append(text, last, text.length()).toString();
    }

    /**
     * Blanks out the parts of {@code text} (already lower case) that name dates a change is not aimed at, keeping
     * every other character at its place:
     * <ul>
     *   <li>everything before a self-correction ("Friday, no wait, Monday" keeps "Monday", see {@link
     *   #corrected});</li>
     *   <li>a negated part, from the negation to the end of its clause, or to the next date word after the first
     *   date it names ("don't put it on Friday put it on Monday"), or to "until" ("not due until Friday");</li>
     *   <li>the start of a clause before its negation when that start is only a date, the subject of the negated
     *   verb ("Friday doesn't work", "tomorrow I can't", see {@link #dateIsSubject}), together with the dates
     *   listed before it ("Thursday and Friday don't work"); the same for a date followed by words that rule it out
     *   ("Friday is out"). A negation that sets a date after it against the one before it ("Monday rather than
     *   Friday", "Monday not Friday") leaves the date before it;</li>
     *   <li>when {@code from} is set, the part after "from", up to the end of its clause or the next date word.</li>
     * </ul>
     */
    static String mask(String text, boolean from) {
        String t = blankBeforeCorrection(text);
        char[] out = t.toCharArray();
        Marks marks = new Marks(t);
        maskNegations(marks, out);
        maskRuledOut(marks, out);
        if (from) {
            maskAfter(FROM, marks, out);
        }
        return new String(out);
    }

    /**
     * Where the clause ends ({@link #CLAUSE_END}), date words ({@link #DATE_WORD}), "until" ({@link #UNTIL}) and the
     * words that aim a change at a date ({@link #ANY_TARGET}) are in one text, found once for all the masks that
     * look at them.
     */
    private static final class Marks {
        final String text;
        private final int[][] ends;
        private final int[][] dates;
        private final int[] untils;
        private final int[] targets;

        Marks(String text) {
            this.text = text;
            this.ends = spans(CLAUSE_END.matcher(text));
            this.dates = spans(DATE_WORD.matcher(text));
            this.untils = spans(UNTIL.matcher(text))[0];
            this.targets = spans(ANY_TARGET.matcher(text))[0];
        }

        /** The starts and the ends of the matches of {@code m}, in order. */
        private static int[][] spans(Matcher m) {
            List<int[]> found = new ArrayList<>();
            while (m.find()) {
                found.add(new int[]{m.start(), m.end()});
            }
            int[][] spans = new int[2][found.size()];
            for (int i = 0; i < found.size(); i++) {
                spans[0][i] = found.get(i)[0];
                spans[1][i] = found.get(i)[1];
            }
            return spans;
        }

        /**
         * The index of the first clause end that starts at or after {@code from}; the number of them when none does.
         */
        int endAfter(int from) {
            return firstAtLeast(ends[0], from);
        }

        int endStart(int i) {
            return i < ends[0].length ? ends[0][i] : text.length();
        }

        int endEnd(int i) {
            return ends[1][i];
        }

        boolean hasEnd(int i) {
            return i < ends[0].length;
        }

        /** Where the clause that holds {@code at} starts: after the last clause end that ends by {@code at}, else 0. */
        int clauseStart(int at) {
            int i = firstAtLeast(ends[1], at + 1) - 1;
            return i >= 0 ? ends[1][i] : 0;
        }

        /** The index of the clause end that ends exactly at {@code at}, or -1. */
        int endEndingAt(int at) {
            int i = Arrays.binarySearch(ends[1], at);
            return i >= 0 ? i : -1;
        }

        /** Where the first date word that starts in [{@code from}, {@code to}) ends, or -1. */
        int dateWordEnd(int from, int to) {
            int i = firstAtLeast(dates[0], from);
            return i < dates[0].length && dates[0][i] < to ? dates[1][i] : -1;
        }

        /** Where the first "until" that starts in [{@code from}, {@code to}) starts, or -1. */
        int until(int from, int to) {
            int i = firstAtLeast(untils, from);
            return i < untils.length && untils[i] < to ? untils[i] : -1;
        }

        /** Where the first target word that starts in [{@code from}, {@code to}) starts, or -1. */
        int target(int from, int to) {
            int i = firstAtLeast(targets, from);
            return i < targets.length && targets[i] < to ? targets[i] : -1;
        }
    }

    /**
     * Words with which a person takes back what they just said: "no", "nope", "sorry", "wait", "oh wait", "actually",
     * "oops", "I mean", "I meant", "how about", "what about", "scratch that", "make that", "make it", "change that",
     * "let's say", "let's do", "better", "or rather", "rather", "on second thought", "second thoughts",
     * "correction", alone or together ("no wait", "actually no", "better make it"), at the start of the text or after
     * punctuation or a dash; the ones that cannot be read otherwise ("actually", "I mean", "I meant", "or rather", "no
     * wait", "oh wait", "make that", "scratch that", "on second thought") also after a space ("Friday actually
     * Monday").
     */
    private static final Pattern CORRECTION = Pattern.compile("(?:^|[,.;!?]|\\s[-\\u2013\\u2014]{1,2}"
            + "|(?=\\s(?:actually|i\\s+mean|i\\s+meant|or\\s+rather|no\\s+wait|oh\\s+wait|make\\s+that|scratch\\s+that"
            + "|on\\s+second\\s+thoughts?)\\b))"
            + "\\s*((?:(?:no|nope|sorry|oh\\s+wait|wait|actually|oops|i\\s+meant|i\\s+mean|how\\s+about|what\\s+about"
            + "|scratch\\s+that|make\\s+that|make\\s+it|change\\s+(?:that|it)|let'?s\\s+say|let'?s\\s+do|better"
            + "|or\\s+rather|rather|on\\s+second\\s+thoughts?|second\\s+thoughts?|correction)"
            + "(?:\\s*[,.!:;]\\s*|\\s*[-\\u2013\\u2014]{1,2}\\s*|\\s+|$))+)");
    /**
     * "Rather" after a date at the end of its clause: "Friday, or Monday rather" takes Friday back for Monday. The
     * "rather" of "Monday rather than Friday" is an exclusion (see {@link #EXCLUSION}).
     */
    private static final Pattern TRAILING_RATHER = Pattern.compile("\\brather\\b(?!\\s+than\\b)(?=\\s*(?:$|[,.;!?]))");
    /** A marker that is not a correction when only a space follows it: "Friday, no Monday" excludes Monday. */
    private static final Pattern BARE_NO = Pattern.compile("(?:^|\\s)(?:no|nope)\\s+$");
    /**
     * What a correction replaces the earlier date with: a date or an amount ("Friday, no wait, Monday", "a week, no,
     * two days", "by Friday, actually mid December", "no, the day after tomorrow", "actually, the following Monday",
     * "Friday, actually before Christmas", "actually next week").
     */
    private static final Pattern CORRECTED_VALUE = Pattern.compile("(?:(?:make\\s+it|let'?s\\s+say|let'?s\\s+do|say|do"
            + "|it'?s|on|to|by|for|until|till|due|before|after)\\s+)*(?:(?:this|the|in)\\s+)*"
            + "(?:today|tonight|tomorrow|week-?end|christmas|xmas|halloween|easter|thanksgiving|new\\s+year|end\\s+of"
            + "|(?:start|beginning|early|mid|middle|late)(?:\\s+of|\\s+in)?(?:\\s+|-)(?:(?:next|this|the)\\s+(?:month|week)"
            + "|" + MONTH + ")|(?:next|this|following|coming)\\s+(?:week|month|year|fortnight|day|" + WEEKDAY_NAMES + ")"
            + "|day\\s+after|day\\s+before|" + WEEKDAY + "|" + MONTH + "|\\d{4}-\\d{2}-\\d{2}|" + ORDINAL + "|"
            + ORDINAL_WORDS + "|" + AMOUNT + ")\\b");
    /**
     * After the date a correction marker introduces, in its clause, words that state something about that date
     * instead of choosing it: "Move it to Friday. Sorry, tomorrow I'm busy", "Sorry, Monday is a holiday". Words that
     * prefer it ("Monday is better", "Monday's fine") keep the correction.
     */
    private static final Pattern STATES_ABOUT_DATE = Pattern.compile("\\b(?:i'm|im|i\\s+am|i've|i\\s+have|we're"
            + "|we\\s+are|we\\s+have|is|are|was|were|has|had|isn't|aren't|doesn't|don't|won't|can't|cannot|wouldn't"
            + "|isnt|doesnt|dont|wont|cant)\\b(?!\\s+(?:better|fine|good|ok|okay|best|easier|great|perfect|free"
            + "|available)\\b)|'s\\b(?!\\s+(?:better|fine|good|ok|okay|best|easier|great|perfect|free|available)\\b)");

    /**
     * Where the words a person corrected themselves with end: after the last correction marker (see {@link
     * #CORRECTION}) that a date or an amount follows ("move it to Friday, no wait, Monday", "Friday. Actually no,
     * Monday", "push it back a week, no, two days", "Friday, make it Monday"), or before a date that a trailing
     * "rather" prefers ("Friday, or Monday rather", see {@link #TRAILING_RATHER}); 0 when there is none. "No"
     * followed by a date with only a space between ("Friday, no Monday") excludes that date instead, and a date the
     * words after it state something about ("Sorry, tomorrow I'm busy", see {@link #STATES_ABOUT_DATE}) corrects
     * nothing. {@code t} is lower case.
     */
    private static int correctionEnd(String t) {
        Matcher m = CORRECTION.matcher(t);
        int end = 0;
        while (m.find()) {
            if (BARE_NO.matcher(m.group(1)).find()) {
                continue;
            }
            Matcher value = CORRECTED_VALUE.matcher(t).region(m.end(), t.length());
            if (value.lookingAt() && !STATES_ABOUT_DATE.matcher(t).region(value.end(),
                    Math.max(value.end(), clauseEndNear(t, value.end()))).find()) {
                end = m.end();
            }
        }
        Matcher rather = TRAILING_RATHER.matcher(t);
        while (rather.find()) {
            int start = clauseStartNear(t, rather.start());
            while (start < rather.start() && Character.isWhitespace(t.charAt(start))) {
                start++;
            }
            Matcher value = CORRECTED_VALUE.matcher(t).region(start, rather.start());
            if (start > 0 && start > end && value.lookingAt()) {
                end = start;
            }
        }
        return end;
    }

    private static String blankBeforeCorrection(String t) {
        int end = correctionEnd(t);
        return end == 0 ? t : " ".repeat(end) + t.substring(end);
    }

    /**
     * {@code text} with everything before a self-correction blanked, so only the corrected date or amount is read:
     * "move it to Friday, no wait, Monday" keeps only "Monday", "push it back a week, no, two days" only "two days"
     * (the direction of the move is still in the text it was cut from). Every other character keeps its letters and
     * place; a text without a correction is returned as it is. Null stays null.
     */
    public static String corrected(String text) {
        if (text == null) {
            return null;
        }
        int end = correctionEnd(normalise(text));
        return end == 0 ? text : " ".repeat(end) + text.substring(end);
    }

    private static void maskNegations(Marks marks, char[] out) {
        Matcher m = NEGATION.matcher(marks.text);
        while (m.find()) {
            if (!excludesWhatFollows(marks.text, m)) {
                maskSubject(marks, out, m.start(), m.group());
            }
            blank(out, m.start(), negatedEnd(marks, m.end()));
        }
    }

    /**
     * True for a negation that only sets the date after it against the rest ("Monday rather than Friday", "Monday
     * not Friday"), as opposed to one that negates a verb or a state of the date before it ("Friday doesn't work",
     * "Friday is not good").
     */
    private static boolean excludesWhatFollows(String text, Matcher negation) {
        String word = negation.group();
        return EXCLUSION.matcher(word).matches() || PLAIN_NOT.matcher(word).matches()
                && DATE_NEXT.matcher(text).region(negation.end(), text.length()).lookingAt();
    }

    private static void maskRuledOut(Marks marks, char[] out) {
        Matcher m = RULED_OUT_AFTER.matcher(marks.text);
        while (m.find()) {
            maskSubject(marks, out, m.start(), "");
        }
        Matcher person = PERSON_RULED_OUT.matcher(marks.text);
        while (person.find()) {
            maskSubject(marks, out, person.start(), "");
        }
    }

    /**
     * The person saying they are not available, after the date it is about: "tomorrow I'm busy", "Friday we're
     * away", "Monday I'm not free". The date before it, alone in its clause, does not work.
     */
    private static final Pattern PERSON_RULED_OUT = Pattern.compile("\\b(?:i'm|im|i\\s+am|we're|we\\s+are)\\s+"
            + "(?:(?:really|fully|already|completely|totally|just|also|pretty|so)\\s+)*(?:busy|booked|away|out|off"
            + "|unavailable|swamped|tied\\s+up|not\\s+(?:free|around|available))\\b");

    /**
     * Blanks the start of the clause before {@code at} when it is only a date, the subject of what follows, and then
     * each date-only item joined to it by "and", "or" or a comma before it ("Thursday, Friday or Saturday don't work").
     */
    private static void maskSubject(Marks marks, char[] out, int at, String negation) {
        String text = marks.text;
        int start = Math.max(marks.clauseStart(at), reachStart(text, at));
        if (!dateIsSubject(text.substring(start, at), negation)) {
            return;
        }
        blank(out, start, at);
        for (int items = 0; start > 0 && items < MAX_LIST_ITEMS; items++) {
            int join = marks.endEndingAt(start);
            if (join < 0) {
                return;
            }
            int joinStart = marks.endStart(join);
            String word = text.substring(joinStart, start);
            if (!(LIST_JOIN.matcher(word).matches() || word.strip().equals(","))) {
                return;
            }
            int itemStart = Math.max(marks.clauseStart(joinStart), reachStart(text, joinStart));
            if (!dateIsSubject(text.substring(itemStart, joinStart), negation)) {
                return;
            }
            blank(out, itemStart, start);
            start = itemStart;
        }
    }

    /**
     * True when the words before a negation in its clause are a date and nothing else ("Friday", "tomorrow is",
     * "on the 20th"), so the negation is about that date; or a date with "I" or "we" when the negation says they
     * are unable ("today I can't", not "on Friday I don't have meetings").
     */
    static boolean dateIsSubject(String head, String negation) {
        boolean date = false;
        boolean person = false;
        for (String word : head.strip().split("\\s+")) {
            String w = word.endsWith("'s") ? word.substring(0, word.length() - 2) : word;
            if (w.isEmpty() || SUBJECT_FILLER.matcher(w).matches()) {
                continue;
            }
            if (DATE_WORD.matcher(w).matches()) {
                date = true;
            } else if (w.equals("i") || w.equals("we")) {
                person = true;
            } else {
                return false;
            }
        }
        return date && (!person || UNABLE.matcher(negation).matches());
    }

    /**
     * Where the clause holding {@code at} starts in {@code text}: just after the last clause end before it (see
     * {@link #clauseEnd}), else 0. Indices are those of {@code text}, whatever its case.
     */
    public static int clauseStart(String text, int at) {
        Matcher end = CLAUSE_END.matcher(normalise(text).substring(0, at));
        int start = 0;
        while (end.find()) {
            start = end.end();
        }
        return start;
    }

    /**
     * Where a negated part that starts at {@code from} ends: at the end of its clause, extended over dates joined to
     * it by "and" or "or" ("not Thursday or Friday"); before "until"; or before the next date word that follows the
     * first date it names.
     */
    private static int negatedEnd(Marks marks, int from) {
        int end = marks.endAfter(from);
        int stop = marks.endStart(end);
        boolean atClauseEnd = marks.hasEnd(end);
        int until = marks.until(from, stop);
        if (until >= 0) {
            stop = until;
            atClauseEnd = false;
        }
        int dateEnd = marks.dateWordEnd(from, stop);
        if (dateEnd >= 0) {
            int next = marks.target(dateEnd, stop);
            if (next >= 0) {
                stop = next;
                atClauseEnd = false;
            }
        }
        return atClauseEnd ? listEnd(marks, end) : stop;
    }

    /**
     * The end of a list of dates that starts with the clause ending at clause end {@code end} (an index of
     * {@link Marks}): each following item joined by "and" or "or" that is only a date belongs to it ("not Thursday or
     * Friday, Monday" rules out both days).
     */
    private static int listEnd(Marks marks, int end) {
        int stop = marks.endStart(end);
        for (int items = 0; items < MAX_LIST_ITEMS && marks.hasEnd(end); items++) {
            if (!LIST_JOIN.matcher(marks.text.substring(marks.endStart(end), marks.endEnd(end))).matches()) {
                break;
            }
            int itemStart = marks.endEnd(end);
            int itemEnd = marks.endStart(end + 1);
            if (!dateIsSubject(marks.text.substring(itemStart, itemEnd), "")) {
                break;
            }
            stop = itemEnd;
            end++;
        }
        return stop;
    }

    private static void blank(char[] out, int from, int to) {
        for (int i = from; i < to; i++) {
            out[i] = ' ';
        }
    }

    private static void maskAfter(Pattern starts, Marks marks, char[] out) {
        Matcher m = starts.matcher(marks.text);
        while (m.find()) {
            int end = m.end();
            Matcher lead = startingAt(LEADING_PREPOSITION, marks.text, end);
            if (lead != null) {
                end = lead.end();
            }
            int stop = marks.endStart(marks.endAfter(end));
            int next = marks.target(end, stop);
            blank(out, m.start(), next >= 0 ? next : stop);
        }
    }

    /**
     * The first date anywhere in {@code text}, leaving out dates in negated parts and dates taken back; empty for a
     * phrase the parser cannot read whole (see the class comment).
     */
    public static Optional<LocalDate> find(String text, LocalDate today) {
        if (text == null || today == null) {
            return Optional.empty();
        }
        String masked = prepared(text, false);
        return unread(masked, today) ? Optional.empty() : findIn(masked, today);
    }

    // ---- recurring days ----

    /** A weekday, short forms included, or its plural ("Fridays"). */
    private static final String ANY_WEEKDAY = "(?:" + WEEKDAY_NAMES + "|wed|sat|sun)s?";
    /** More weekdays joined to a recurring one: "every Monday and Thursday", "each Mon, Wed and Fri". */
    private static final String MORE_WEEKDAYS = "(?:\\s*(?:,|and|or|&)\\s*(?:on\\s+)?" + ANY_WEEKDAY + "\\b)*";
    /** "of the month", "of each month", "in every month" after a recurring day of the month. */
    private static final String OF_EVERY_MONTH = "(?:\\s+(?:of|in)\\s+(?:the|each|every)\\s+month)?";
    /** How many times something happens: "once", "twice", "3 times", "a couple of times", "several times". */
    private static final String COUNTED_TIMES = "\\b(?:once|twice|thrice|(?:\\d{1,3}|a\\s+couple\\s+of|a\\s+few|several"
            + "|one|two|three|four|five|six|seven|eight|nine|ten)\\s+times)";
    /**
     * Words that make a day recur instead of naming one date: "every Friday", "each Friday", "every other Saturday",
     * "every Monday and Thursday", "every first Monday of the month", "weekly on Tuesday", "Friday every week",
     * "Fridays each week", "every 15th", "the 1st of every month", "the first Monday of each month", "monthly on
     * the 3rd", "once a week on Sunday", "twice a month on the 1st", "3 times a week". A recurring day is no
     * deadline: {@link #find} and {@link #findTarget} read no date from it, so "practise piano each Friday" has none
     * and "every Friday until Christmas" is Christmas.
     */
    private static final Pattern RECURRENCE = Pattern.compile(COUNTED_TIMES + "\\s+(?:a|an|per|each|every)\\s+(?:day"
                    + "|week|weekend|fortnight|month|year)\\b(?:,?\\s+(?:on\\s+)?(?:the\\s+)?(?:" + ANY_WEEKDAY
                    + "|\\d{1,2}(?:st|nd|rd|th)|" + ORDINAL_WORDS + ")\\b" + MORE_WEEKDAYS + ")?"
                    + "|\\b(?:every|each)\\s+(?:(?:other|alternate|single|second|third|fourth|first|last|2nd|3rd|4th"
                    + "|1st)\\s+)?"
                    + ANY_WEEKDAY + "\\b" + MORE_WEEKDAYS + OF_EVERY_MONTH
                    + "|\\b(?:(?:bi-?)?weekly|fortnightly|every\\s+(?:other\\s+|second\\s+)?week|each\\s+week)"
                    + ",?\\s+(?:on\\s+)?" + ANY_WEEKDAY + "\\b" + MORE_WEEKDAYS
                    + "|\\b" + ANY_WEEKDAY + "\\b" + MORE_WEEKDAYS + "\\s+(?:each|every)\\s+(?:other\\s+|second\\s+)?week\\b"
                    + "|\\b(?:every|each)\\s+(?:month\\s+on\\s+)?(?:the\\s+)?(?:\\d{1,2}(?:st|nd|rd|th)|" + ORDINAL_WORDS
                    + ")(?:\\s+day)?\\b" + OF_EVERY_MONTH
                    + "|\\bmonthly,?\\s+on\\s+(?:the\\s+)?(?:\\d{1,2}(?:st|nd|rd|th)|" + ORDINAL_WORDS + ")\\b"
                    + "|\\b(?:the\\s+)?(?:(?:\\d{1,2}(?:st|nd|rd|th)|" + ORDINAL_WORDS + "|last)(?:\\s+(?:"
                    + ANY_WEEKDAY + "|day))?)\\s+(?:of|in)\\s+(?:each|every)\\s+month\\b");

    /** {@code t} (lower case) with every recurring day (see {@link #RECURRENCE}) blanked; indices are kept. */
    private static String withoutRecurrences(String t) {
        Matcher m = RECURRENCE.matcher(t);
        if (!m.find()) {
            return t;
        }
        char[] out = t.toCharArray();
        do {
            blank(out, m.start(), m.end());
        } while (m.find());
        return new String(out);
    }

    // ---- simple sentences ----

    /**
     * What may not follow a date in digits: more of a number, or a unit or word that makes it a fraction ("1/2
     * hour", "3/4 done", "2/3 of the tasks").
     */
    private static final String NUMERIC_DATE_END = "(?![\\w/]|[.-]\\d)(?!\\s*(?:of\\b|an?\\s+(?:hour|minute)|hours?"
            + "|hrs?|h\\b|minutes?|mins?|days?|weeks?|months?|years?|done|finished|complete|completed|ready|through"
            + "|(?:am|pm|a\\.m|p\\.m|o'clock)(?![a-z])|%))";
    /** A weekday right before the digits that follow it: "Friday 4.12", "friday, the 27-11". */
    private static final String AFTER_WEEKDAY = "(?<=\\b(?:" + WEEKDAY_NAMES + "),?\\s{1,3}(?:the\\s{1,3})?)";
    /**
     * A date in digits: day and month or month and day with a slash ("27/11", "11/27", "4/12/2026"), with full
     * stops or dashes and a year ("27.11.2026", "27-11-2026"; read day first, see {@link #numeric}), and day and
     * month with a full stop when the day can be no hour ("27.11"; "10.30" may be a time of day). Right after a
     * weekday, day and month with a full stop ("Friday 4.12.") and with a dash when one of the numbers can be no hour
     * ("Friday 27-11"; "Friday 10-11" may be a span of hours) are dates in digits too. A regular expression in lower
     * case, without groups.
     */
    private static final String NUMERIC_DATE_FORM = "(?<![\\w./-])(?:\\d{1,2}/\\d{1,2}(?:/(?:\\d{4}|\\d{2}))?"
            + "|\\d{1,2}\\.\\d{1,2}\\.(?:\\d{4}|\\d{2})|\\d{1,2}-\\d{1,2}-(?:\\d{4}|\\d{2})|(?:2[5-9]|3[01])\\.\\d{1,2}"
            + "|" + AFTER_WEEKDAY + "(?:\\d{1,2}\\.\\d{1,2}|\\d{1,2}-(?:2[5-9]|3[01])|(?:2[5-9]|3[01])-\\d{1,2}))"
            + NUMERIC_DATE_END;
    /**
     * A word that names a date or a part of one: an ISO date, today, tomorrow, yesterday, a unit of calendar time
     * ("week", "days"), a holiday, a weekday, a month (a short or ambiguous name only next to a day number or after a
     * date word: "Dec 5", "5 Jan", "by May"), a day of the month as an ordinal ("the 20th", not "the 2nd task") or a
     * year ("'27", and "2027" after "of", "in", "by", "until", "before", "due" or "year"). A unit that gives a rate
     * ("2 hours a day", "per week", "every month", "twice a week", "/day") names no date, and neither does the "day"
     * of "end of day", a time of day.
     */
    private static final Pattern DATE_TOKEN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b"
            + "|" + NUMERIC_DATE_FORM
            + "|(?<![\\w'])'\\d{2}\\b"
            + "|(?<=\\b(?:of|in|by|until|till|before|due|year)\\s{1,3})(?:19|20|21)\\d{2}\\b"
            + "|\\b(?:today|tonight|tomorrow|yesterday|christmas|xmas|halloween|easter|thanksgiving|new\\s+year'?s?"
            + "|valentine'?s)\\b"
            + "|(?<!\\bper\\s{1,3})(?<!\\b(?:every|each)\\s{1,3})(?<!\\b(?:every|each)\\s{1,3}other\\s{1,3})(?<!/\\s{0,3})"
            + "(?<!\\b(?:hours?|hrs?|h|minutes?|mins?|times|once|twice)\\s{1,3}an?\\s{1,3})"
            + "(?!(?<=\\bend\\s{1,3}of\\s{1,3}(?:the\\s{1,3})?)day\\b)"
            + "\\b(?:week-?ends?|weeks?|fortnights?|months?|years?|days?)\\b"
            + "|(?<!')\\b(?:" + WEEKDAY_NAMES + ")\\b"
            + "|\\b(?:january|february|march|april|june|july|august|september|october|november|december)\\b"
            + "|\\b(?:may|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\b(?=\\.?\\s*\\d)"
            + "|(?<=\\d(?:st|nd|rd|th)?\\s{1,3}(?:of\\s{1,3})?)(?:may|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)\\b"
            + "|(?<=\\b(?:in|by|until|till|before|due|early|mid|late|of)\\s{1,3})may\\b"
            + "|\\b\\d{1,2}(?:st|nd|rd|th)\\b" + POSITION_NOUN);
    /**
     * What may stand between two date words of one date phrase: "Friday the 27th", "the Monday after Christmas", "two
     * weeks from today", "Friday of next week", "Friday at 5pm", "Friday week", "the first Monday of December".
     */
    private static final Pattern DATE_GLUE = Pattern.compile("(?:\\s+|[-.']|(?:the|of|next|this|coming|following|after"
            + "|before|preceding|from|on|in|at|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|couple|s"
            + "|\\d+(?::\\d{2})?(?:am|pm|st|nd|rd|th)?|am|pm|noon|midday|midnight|morning|afternoon|evening|night|end"
            + "|start|beginning|early|mid|middle|late|last|first|second|third|fourth|fifth|eve|time|latest|close"
            + "|business|just|right|immediately|directly|straight)\\b)*");
    private static final Pattern WEEKDAY_OR_MONTH = Pattern.compile("(?:" + WEEKDAY_NAMES + "|" + MONTH_NAMES + ")");
    private static final Pattern ORDINAL_OR_MONTH = Pattern.compile("\\d{1,2}(?:st|nd|rd|th)|(?:" + MONTH_NAMES + ")"
            + "|\\d{1,2}[/.-]\\d{1,2}(?:[/.-]\\d{2}(?:\\d{2})?)?");
    /**
     * Words that make a sentence's date more than a plain statement of it: a correction ("actually", "sorry", "I
     * mean", "make that", "scratch that", "on second thought", "how about"), a contrast or exclusion ("but",
     * "rather", "instead", "however", "except", "apart from", "other than"), a pronoun for a date said elsewhere
     * ("this one", "the one after"), a hedge ("maybe", "or so", "-ish", "around", "at the earliest"), a span
     * ("between"), or words that make the date an old one ("originally", "previously", "used to", "was due").
     */
    private static final Pattern NOT_SIMPLE = Pattern.compile("\\b(?:actually|sorry|oops|wait|i\\s+mean|i\\s+meant"
            + "|scratch\\s+that|make\\s+that|change\\s+that|rather|instead|second\\s+thoughts?|how\\s+about"
            + "|what\\s+about|correction|but|however|though|although|whereas|otherwise|except|excluding|apart\\s+from"
            + "|aside\\s+from|besides|other\\s+than|maybe|perhaps|possibly|or\\s+so|ish|around|roughly|approximately"
            + "|between|sometime|at\\s+the\\s+earliest|or\\s+(?:later|earlier|sooner)|(?:this|the\\s+other|the\\s+next)\\s+one"
            + "|the\\s+one\\s+(?:after|before|this|next)|make\\s+it|let'?s\\s+(?:say|do|make\\s+it)|originally"
            + "|previously|formerly|initially|used\\s+to|(?:was|were)\\s+(?:due|planned|scheduled|set))\\b");
    /**
     * An instruction that opens the sentence and only introduces its value: "make it Monday", "let's say Friday",
     * "ok, let's do the 20th". There it corrects nothing said in the same sentence.
     */
    private static final Pattern OPENING_INSTRUCTION = Pattern.compile("^\\s*(?:(?:please|ok|okay|yes|yeah|so|then|fine)"
            + "[,!]?\\s+)*(?:make\\s+it|let'?s\\s+(?:say|do|make\\s+it))\\b");
    /**
     * Phrases the parser would read only in part: "the month after next" and "the year after next" ("the end of the
     * month after next" holds "end of the month"), a period after or before a date or on its own ("the week before
     * Christmas" holds "before Christmas", "the end of the month after" holds "end of the month", "the Friday of the
     * week after"; "the week after next" is read whole), a weekday, day or weekend a word like "soon" or "shortly"
     * puts at an open distance from its anchor ("the Friday shortly before Christmas" holds "before Christmas", see
     * {@link #LOOSE}), "this time next week" (holds "next week"), "mid", "late" or "towards the end of" next week, this
     * week or the week after next, a part or half of a period ("the early part of next week", "the latter part of
     * next week", "the second half of December" hold "next week" and "December"), the week of a date ("the week of
     * December 7" holds "December 7"), a weekday of a date ("the Friday of December 12" holds "December 12"), months
     * counted on from a weekday ("a month on Friday" holds "Friday"), an amount added to a date ("Friday plus a week",
     * "next Friday plus two days", "Friday and a week" hold "Friday"), a weekday before an amount counted from an
     * anchor ("Friday 2 weeks from now" holds "2 weeks from now") and a date said again in brackets ("Friday (the
     * 27th)", "the 27th (Friday)", "Friday (27/11)": either part may be read, and the brackets may correct the other).
     */
    private static final Pattern PARTIAL = Pattern.compile("\\b(?:month|year)\\s+after\\s+next\\b"
            + "|\\bthe\\s+(?:week|fortnight|month|year)\\s+(?:(?:" + CLOSE + "|" + LOOSE + ")\\s+)?(?:after|before"
            + "|following|preceding)\\b(?!\\s+next\\b)"
            + "|\\b(?:" + WEEKDAY_NAMES + "|day|week-?end)\\s+(?:" + CLOSE + "\\s+)?(?:after|before|following|preceding)"
            + "(?=\\s*(?:$|[,.;:!?)]))"
            + "|\\b(?:" + WEEKDAY_NAMES + "|day|week-?end)\\s+" + LOOSE + "\\s+(?:after|before|following|preceding)\\b"
            + "|\\b(?:part|parts|half|halves)\\s+of\\s+(?:(?:the|this|next|that|the\\s+next|the\\s+coming"
            + "|the\\s+following)\\s+)?(?:week-?end|week|fortnight|month|year|" + MONTH_NAMES + ")\\b"
            + "|\\b(?:the|that)\\s+(?:week|fortnight)\\s+of\\b"
            + "|\\b(?:" + WEEKDAY_NAMES + ")\\s+of\\s+(?:the\\s+)?(?:\\d{1,2}(?:st|nd|rd|th)?\\b|(?:" + MONTH_NAMES
            + ")\\.?\\s+\\d{1,2}(?!\\d))"
            + "|\\bthis\\s+time\\s+(?:next|last)\\s+(?:week|month|year)\\b"
            + "|\\b(?:mid|middle\\s+of|late|later|towards?\\s+the\\s+end\\s+of)(?:\\s+|-)(?:the\\s+)?(?:next\\s+week"
            + "|week\\s+after\\s+next|this\\s+week)\\b"
            + "|\\b" + NUMBER + "\\s+months?\\s+on\\s+(?:(?:this|next)\\s+)?(?:" + WEEKDAY_NAMES + ")\\b"
            + "|\\bplus\\s+(?:another\\s+|an?\\s+extra\\s+)?" + NUMBER + "\\s+" + UNIT + "\\b"
            + "|\\b(?:" + WEEKDAY_NAMES + "|today|tomorrow)\\s+and\\s+" + NUMBER + "\\s+" + UNIT + "\\b"
            + "|\\b(?:" + WEEKDAY_NAMES + ")\\s+" + NUMBER + "\\s+" + UNIT + "\\s+(?:from|after|before)\\b"
            + "|\\b(?:" + WEEKDAY_NAMES + "|" + MONTH_NAMES + "|\\d{1,2}(?:st|nd|rd|th)?|today|tomorrow)\\.?\\s*\\(\\s*"
            + "(?:(?:the|a|an|on)\\s+)?(?:(?:" + WEEKDAY_NAMES + "|" + MONTH_NAMES + "|\\d{1,2}(?:st|nd|rd|th))\\b"
            + "|\\d{1,2}[/.-]\\d{1,2}|\\d{1,2}\\s*\\))");

    /**
     * Words that give a start instead of a deadline: "starting Monday", "start on the 1st", "beginning next week",
     * "commencing Friday", "as of the 1st", "effective tomorrow". "The beginning of next month" is a part of a month.
     */
    private static final Pattern START_WORDING = Pattern.compile("\\b(?:start(?:s|ing)?|begin(?:s|ning)?"
            + "|commenc(?:e|es|ing)|kicks?\\s+off|as\\s+of|as\\s+from|effective)\\s+(?:on\\s+|from\\s+|in\\s+)?"
            + "(?=(?:(?:the|this|next|coming)\\s+)*(?:(?:today|tonight|tomorrow|week-?end|week|month|year|"
            + WEEKDAY_NAMES + "|" + MONTH_NAMES + ")\\b|\\d))");
    /**
     * Words that make something happen again and again: "once a week", "3 times a month", "20 minutes a day", "an
     * hour and a half a day", "2h/day", "every day", "each morning", "daily", "weekly", "every weekday",
     * "weekdays", "on weekends", "2 posts per day", "3 pages a week".
     */
    private static final Pattern FREQUENCY = Pattern.compile(COUNTED_TIMES + "\\s+(?:a|an|per|each|every)\\s+(?:day"
            + "|week|weekend|fortnight|month|year)\\b"
            + "|\\b(?:minutes?|mins?|hours?|hrs?|h)\\s+(?:a|an|per|each|every)\\s+(?:day|weekday|week|weekend|fortnight"
            + "|month|morning|evening|night)\\b"
            + "|(?<=\\band\\s{1,3}an?\\s{1,3})(?:half|quarter)\\s+(?:a|an|per|each|every)\\s+(?:day|week)\\b"
            + "|\\b(?:minutes?|mins?|hours?|hrs?|h)\\s*/\\s*(?:day|week|month)\\b"
            + "|\\b(?:every|each)\\s+(?:other\\s+|second\\s+|single\\s+)?(?:day|morning|afternoon|evening|night|week"
            + "|weekend|fortnight|month|year|time)\\b"
            + "|\\b(?:daily|nightly|weekly|fortnightly|bi-?weekly|monthly|yearly|annually)\\b"
            + "|\\b(?:every|each)\\s+(?:single\\s+)?(?:weekday|workday|work\\s+day|working\\s+day|business\\s+day"
            + "|school\\s+day|weeknight)\\b"
            + "|(?<!\\b(?:\\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|few|couple\\s+of|several|next"
            + "|this|last|the|coming)\\s{1,3})\\b(?:weekdays|weekends|weeknights|workdays)\\b"
            + "|\\b(?:\\d{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|a\\s+few|a\\s+couple\\s+of|several)\\s+"
            + "[a-z]+\\s+(?:a|an|per|each|every)\\s+(?:day|weekday|week|weekend|fortnight|month|year|morning|evening"
            + "|night)\\b");

    /**
     * {@link #simple(String, LocalDate)} with today's date from the system clock. Prefer the form that takes the day
     * the words are read on: whether a weekday falls on the date said with it ("Friday the 27th") depends on it.
     */
    public static boolean simple(String text) {
        return simple(text, LocalDate.now());
    }

    /**
     * True when {@code text} is a simple statement of one date, so a date read from its words alone may be applied
     * without asking: it names exactly one date phrase (see {@link #datePhrases}), the parser reads that phrase whole
     * on {@code today} ("next Friday", "by Christmas", "in 2 weeks", "the Monday after Christmas", "Friday the 27th at
     * 5pm" when the 27th is a Friday, "by November 27", "on the 25th", "by Christmas next year"), and every date word
     * of the sentence lies inside the words the date was read from. The date words are those of {@link #DATE_TOKEN}
     * with the words next to them that belong to their phrase: "next", "this", "last", "coming", "following", "before",
     * "after", "early", "mid", "late", "end", "start" and "beginning" before them, a count before a unit ("2 weeks"),
     * a day number next to a month ("27 November", "November 27"), a year after them ("2027", "of 2027", "'27"), and
     * "next" or "last" right after them ("Friday next") or "after" and "before" that end the phrase ("the month
     * after"). So "December next year" read as December, "Wednesday 3rd March 2027" read as a Wednesday the 3rd,
     * "the end of the month after" read as the end of this month and "before next month" read as next month are not
     * simple, and neither is a phrase that names no date ("Friday the 5th" when the 5th is no Friday, "by 2030", "in
     * a year"). Besides, the sentence has no word that makes the reading depend on more than that phrase:
     * <ul>
     *   <li>a negation ("not", "no", "can't", "don't", ...; "no later than" and "don't forget" are none) or a date
     *   that is ruled out ("Friday is out", "tomorrow I'm busy");</li>
     *   <li>a correction, contrast, exclusion, pronoun or hedge (see {@link #NOT_SIMPLE}); "make it", "let's say" and
     *   "let's do" count only after the start of the sentence, since "make it Monday" corrects nothing;</li>
     *   <li>"from" before a date ("move it from Friday"), "after" a date ("after Friday"), a recurring day ("every
     *   Friday", "once a week on Sunday"), words that repeat ("3 times a week", "20 minutes a day", "daily", see
     *   {@link #FREQUENCY}), words that give a start ("starting Monday", see {@link #START_WORDING}), an amount that
     *   is not whole ("in a week and a half") or a phrase read only in part ("the month after next", "the week before
     *   Christmas", "this time next week");</li>
     *   <li>a day number joined to another ("December 11 or 12", "December 11-12", "between December 11 and 13", see
     *   {@link #DAY_ALTERNATIVE}), a date in digits without a weekday that settles which way round it is ("27/11";
     *   "Friday 27/11" and "Friday 27-11-2026" are simple when November 27 is a Friday, see {@link #numericDate}),
     *   or digits after a weekday that may be a time of day ("Friday 4.12", read as December 4 when that is a
     *   Friday, but never simple);</li>
     *   <li>a weekday that may count from a date said elsewhere ("that Friday", "the Wednesday", "the following
     *   Monday", see {@link #anaphoricWeekday}), or "next" with a weekday that comes within two days, or three in the
     *   same calendar week ("next Friday" said on a Thursday may mean tomorrow or the Friday after; said on a Monday it
     *   is simple);</li>
     *   <li>a month, a holiday or a day and month in digits without a year, read as next year's while this year's day
     *   passed less than 90 days ago ("November 5" or "by Halloween" said on 2026-11-19; "by March 3" is simple).</li>
     * </ul>
     * False for null, blank and text without a date.
     */
    public static boolean simple(String text, LocalDate today) {
        return simplePhrase(text, today).isPresent();
    }

    /** Where a date phrase stands in a text: from {@code start} up to {@code end}, indices into that text. */
    public record Span(int start, int end) {
        /** The words of the span in {@code text}. */
        public String in(String text) {
            return text.substring(start, end);
        }
    }

    /**
     * The words of the one date phrase {@code text} states simply: present exactly when
     * {@link #simple(String, LocalDate)} is true, from the first to the last character of the words the date was read
     * from, the date word before it included ("to November 27" of "move T3 to November 27", "by Friday" of "new goal:
     * plant tulips by Friday", "next Friday" of "push it to next Friday"; "Monday" of "make it Monday"). Every date
     * word of the text lies inside it, so the words outside it name no date. Indices are those of {@code text}.
     */
    public static Optional<Span> simplePhrase(String text, LocalDate today) {
        if (text == null || text.isBlank() || today == null) {
            return Optional.empty();
        }
        String t = ordinals(normalise(text));
        Matcher opening = OPENING_INSTRUCTION.matcher(t);
        String rest = opening.lookingAt() ? " ".repeat(opening.end()) + t.substring(opening.end()) : t;
        if (NOT_SIMPLE.matcher(rest).find() || NEGATION.matcher(t).find() || RULED_OUT_AFTER.matcher(t).find()
                || PERSON_RULED_OUT.matcher(t).find() || FROM.matcher(t).find() || AFTER_DATE.matcher(t).find()
                || UNREAD_AMOUNT.matcher(t).find() || PARTIAL.matcher(t).find() || RECURRENCE.matcher(t).find()
                || FREQUENCY.matcher(t).find() || START_WORDING.matcher(t).find() || DAY_ALTERNATIVE.matcher(t).find()
                || ANAPHORIC_WEEKDAY.matcher(t).find() || nearNext(t, today) || unconfirmedNumeric(t, today)) {
            return Optional.empty();
        }
        List<Run> runs = dateRuns(t);
        if (runs.size() != 1 || unread(t, today)) {
            return Optional.empty();
        }
        Run run = runs.getFirst();
        int start = extendedStart(t, run);
        int end = extendedEnd(t, run);
        // Read as find reads it: an opening "make it" or "let's say" introduces the date and is no part of it.
        return readIn(rest, today).filter(r -> r.start() <= start && r.end() >= end)
                .filter(r -> !justPassed(t.substring(r.start(), r.end()), r.date(), today))
                .map(r -> new Span(textStart(text, t, r.start()), r.end()));
    }

    /**
     * A weekday that may count from a date said elsewhere: "that Friday", "that same Friday", "the Wednesday", "the
     * following Monday", "the next Friday", "following Monday". Alone it is read as the coming weekday, but next to
     * another date the person may count it from that one ("move T3 to December 1 and T4 to that Friday"), and alone
     * it may point back at a date said before. Words after it that place it themselves make it none: "the Friday
     * after Christmas", "the Friday before the 20th", "the Friday of next week", "the Friday 27th", "the Friday,
     * December 4".
     */
    private static final Pattern ANAPHORIC_WEEKDAY = Pattern.compile("\\b(?:(?:that|the)\\s+(?:(?:same|very|following"
            + "|next)\\s+)?|(?<!\\b(?:the|this|that)\\s{1,3})following\\s+)" + WEEKDAY + "\\b(?!\\s+(?:(?:" + CLOSE + "|"
            + LOOSE + ")\\s+)?(?:after|before|following|preceding|of|in|week|fortnight|next|this)\\b)"
            + "(?!,?\\s+(?:the\\s+)?\\d)(?!,?\\s+(?:" + MONTH_NAMES + ")\\b)");

    /**
     * True when {@code text} names a weekday that may count from a date said elsewhere ("that Friday", "the
     * Wednesday", "the following Monday", "the next Friday"; see {@link #ANAPHORIC_WEEKDAY}). The parser reads such a
     * weekday as the coming one, which is a guess when the sentence names another date or the words point back at
     * one said before; {@link #simple(String, LocalDate)} is never true for such text. False for null.
     */
    public static boolean anaphoricWeekday(String text) {
        return text != null && ANAPHORIC_WEEKDAY.matcher(normalise(text)).find();
    }

    /**
     * True when {@code t} says "next" and a weekday whose coming occurrence is near: within the next two days, or
     * three days ahead in the same calendar week (Monday to Sunday). Said on a Thursday, "next Friday" is read as
     * tomorrow, but the person may mean the Friday a week later; said on a Monday, "next Friday" is four days ahead
     * and plainly the coming one.
     */
    private static boolean nearNext(String t, LocalDate today) {
        Matcher m = NEXT_WEEKDAY.matcher(t);
        while (m.find()) {
            LocalDate coming = today.with(TemporalAdjusters.next(weekday(m.group(1))));
            long days = coming.toEpochDay() - today.toEpochDay();
            boolean sameWeek = coming.getDayOfWeek().getValue() > today.getDayOfWeek().getValue();
            if (days <= 2 || sameWeek && days <= 3) {
                return true;
            }
        }
        return false;
    }

    /** A word that places a day in the calendar year: a month, a holiday, or a day and month in digits. */
    private static final Pattern CALENDAR_NAME = Pattern.compile("\\b(?:" + MONTH_NAMES + "|christmas|xmas|halloween"
            + "|easter|thanksgiving|valentine'?s?|new\\s+year'?s?|black\\s+friday|cyber\\s+monday|good\\s+friday)\\b"
            + "|\\d{1,2}[/.-]\\d{1,2}");
    /** Words that say a year: "2027", "'27", "next year", "this year", the year of a date in digits ("6/12/27"). */
    private static final Pattern YEAR_SAID = Pattern.compile("\\b(?:19|20|21)\\d{2}\\b|'\\d{2}\\b"
            + "|\\b(?:next|this|last)\\s+year\\b|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2}");
    /** How recently the same day of the year may have passed for a date read without a year to be a plain one. */
    private static final int JUST_PASSED_DAYS = 90;

    /**
     * True when {@code words} (the words a date was read from) place a day in the calendar year without saying the
     * year (see {@link #CALENDAR_NAME}: "November 5", "by October", "mid November", "by Halloween", "two months before
     * Christmas", "Saturday 13/11") and {@code date}, the next such day, comes a year after the same day passed less
     * than {@link #JUST_PASSED_DAYS} days before {@code today}: "move it to November 5" said on 2026-11-19 is read as
     * 2027-11-05, while the person may mean the day that just passed. "By March 3" said in November is next March
     * either way.
     */
    private static boolean justPassed(String words, LocalDate date, LocalDate today) {
        if (!CALENDAR_NAME.matcher(words).find() || YEAR_SAID.matcher(words).find()) {
            return false;
        }
        LocalDate yearBefore = date.minusYears(1);
        return yearBefore.isBefore(today) && !yearBefore.isBefore(today.minusDays(JUST_PASSED_DAYS));
    }

    /**
     * Where the word of {@code text} that holds index {@code at} of {@code t} (its {@link #ordinals} form) starts: an
     * ordinal word written as digits keeps its place with spaces before the digits ("twenty-fifth" is " 25th"), so an
     * index at the digits lies inside the word the person wrote.
     */
    private static int textStart(String text, String t, int at) {
        while (at > 0 && t.charAt(at - 1) == ' ' && (text.charAt(at - 1) != ' '
                || at > 1 && t.charAt(at - 2) == ' ' && text.charAt(at - 2) != ' ')) {
            at--;
        }
        return at;
    }

    /**
     * Where each date phrase of {@code text} stands, in order: a run of date words (see {@link #DATE_TOKEN}: "Friday",
     * "November 27", "the 20th", "2 weeks", "Christmas", "27/11", "27-11-2026", "Friday 4.12") with the words next to
     * it that belong to its phrase (see {@link #simple(String, LocalDate)}: "next", "the end of", a count before a
     * unit, a day number after a month, a year). "move T3 of the picnic to November 27" has one phrase, "move it from
     * Friday to Monday" two, "the library is closed next Monday" one ("next Monday"). The words outside every span
     * name no date. "The end of the day" is a phrase of its own, as {@link #find} reads it ("pack by the end of the
     * day"). Empty for null and for text without a date word. Indices are those of {@code text}.
     */
    public static List<Span> datePhraseSpans(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String words = ordinals(normalise(text));
        String t = endOfDay(words);
        List<int[]> endsOfDay = new ArrayList<>();
        Matcher m = END_OF_DAY.matcher(words);
        while (m.find()) {
            endsOfDay.add(new int[]{m.start(), m.end()});
        }
        return dateRuns(t).stream().map(run -> {
            int start = extendedStart(t, run);
            for (int[] end : endsOfDay) {
                if (start >= end[0] && start < end[1]) {
                    start = end[0]; // the whole phrase, not the "today" that stands in for it
                }
            }
            return new Span(textStart(text, t, start), extendedEnd(t, run));
        }).toList();
    }

    /**
     * A day number joined to another by "or", "and", "to", "through", "until" or a dash: "December 11 or 12",
     * "December 11-12", "11 to 13 December", "the 11th and 12th". The words name a choice or a span of days, not one
     * date. A time ("10-11am") or an amount ("2 to 3 hours") after the second number makes it none.
     */
    private static final Pattern DAY_ALTERNATIVE = Pattern.compile("(?<![\\w./-])\\d{1,2}(?:st|nd|rd|th)?\\s*(?:,?\\s*\\b"
            + "(?:or|and|to|through|thru|till|until|nor)\\b\\s*(?:the\\s+)?|[-\\u2013\\u2014&]\\s*)\\d{1,2}(?:st|nd|rd|th)?\\b"
            + "(?![-/.]\\d)(?!\\s*(?:am|pm|a\\.m|p\\.m|h|hrs?|hours?|mins?|minutes?|%)(?![a-z]))");

    /**
     * True when {@code t} holds a date in digits (see {@link #numericDate}) with no weekday said with it, or whose
     * digits may also be a time of day ("Friday 4.12"): day and month may be either way round, or the person may
     * have written them the other way round, so only a weekday that fits one reading settles it, and digits that may
     * be a time settle nothing.
     */
    private static boolean unconfirmedNumeric(String t, LocalDate today) {
        Matcher m = NUMERIC_DATE.matcher(t);
        while (m.find()) {
            NumericDate numeric = numeric(t, m, today);
            if (numeric != null && (!numeric.weekday() || numeric.mayBeTime())) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many date phrases {@code t} (lower case) names: runs of date words (see {@link #DATE_TOKEN}) with only
     * words that hold a date phrase together between them (see {@link #DATE_GLUE}). A comma joins only a weekday or a
     * month to a day or a month after it ("Friday, 27th", "Friday, June 2nd"), and a date to a year after it ("Dec 5,
     * '27", "Christmas, next year"). "Friday or Monday", "move it from Friday to Monday" and "push it to Friday, the
     * trip takes 3 days" name two, and so does "move the weekend trip to Friday": a date word in a name counts like
     * any other.
     */
    static int datePhrases(String t) {
        return dateRuns(t).size();
    }

    /** A run of date words (see {@link #datePhrases}): where it starts and ends, and its first and last word. */
    private record Run(int start, int end, String first, String last) {
    }

    private static List<Run> dateRuns(String t) {
        Matcher m = DATE_TOKEN.matcher(t);
        List<Run> runs = new ArrayList<>();
        while (m.find()) {
            Run last = runs.isEmpty() ? null : runs.getLast();
            if (last != null && sameDatePhrase(t.substring(last.end(), m.start()), last.last(), m.group())) {
                runs.set(runs.size() - 1, new Run(last.start(), m.end(), last.first(), m.group()));
            } else {
                runs.add(new Run(m.start(), m.end(), m.group(), m.group()));
            }
        }
        return runs;
    }

    private static boolean sameDatePhrase(String gap, String before, String after) {
        if (gap.indexOf(',') < 0) {
            return DATE_GLUE.matcher(gap).matches();
        }
        if (gap.indexOf(',') != gap.lastIndexOf(',') || !DATE_GLUE.matcher(gap.replace(',', ' ')).matches()) {
            return false;
        }
        return WEEKDAY_OR_MONTH.matcher(before).matches() && ORDINAL_OR_MONTH.matcher(after).matches()
                || YEAR_AFTER_COMMA.matcher(after).matches();
    }

    /** A date word that may follow a date across a comma: a year ("Dec 5, '27", "Christmas, next year"). */
    private static final Pattern YEAR_AFTER_COMMA = Pattern.compile("years?|\\d{4}|'\\d{2}");
    /**
     * Words before a run of date words that belong to its phrase: "next" Friday, "before" the weekend, "the end of"
     * the month, "mid" December.
     */
    private static final Set<String> LEADING = Set.of("next", "this", "last", "past", "coming", "following", "after",
            "before", "early", "mid", "middle", "late", "end", "start", "beginning");
    /** Words that may stand between those and the run: "the end of the" month, "late in the" month. */
    private static final Set<String> BETWEEN = Set.of("the", "of", "in");
    private static final Pattern UNIT_WORD = Pattern.compile("(?:day|week|fortnight|month|year)s?|week-?ends?");
    private static final Pattern MONTH_WORD = Pattern.compile(MONTH_NAMES);
    private static final Pattern COUNT_WORD = Pattern.compile("\\d{1,3}|a|an|one|two|three|four|five|six|seven|eight"
            + "|nine|ten|eleven|twelve|couple|few|several");
    private static final Pattern DAY_NUMBER = Pattern.compile("\\d{1,2}(?:st|nd|rd|th)?");
    private static final Pattern YEAR_WORD = Pattern.compile("(?:19|20|21)\\d{2}|'\\d{2}");
    /** What makes two digits after a comma part of a span or a time, not a year: "Friday, 10-11am", "Friday, 10:30". */
    private static final Pattern SPAN_AFTER = Pattern.compile("[-\\u2013:./]\\d");
    /** What ends a phrase after a trailing "after" or "before": the end of the text or of its clause, or "next". */
    private static final Pattern END_OF_PHRASE = Pattern.compile("\\s*(?:$|[,.;:!?)]|\\b(?:next|and|but|or|so|then"
            + "|please)\\b)");

    private static boolean wordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'';
    }

    /**
     * Where the phrase of {@code run} starts once the words before it that belong to it are added (see
     * {@link #LEADING}).
     */
    private static int extendedStart(String t, Run run) {
        int start = run.start();
        int at = start;
        boolean afterOf = false;
        while (true) {
            int wordEnd = at;
            while (wordEnd > 0 && (t.charAt(wordEnd - 1) == ' ' || t.charAt(wordEnd - 1) == '-')) {
                wordEnd--;
            }
            int wordStart = wordEnd;
            while (wordStart > 0 && wordChar(t.charAt(wordStart - 1))) {
                wordStart--;
            }
            if (wordStart == wordEnd) {
                return start;
            }
            String w = t.substring(wordStart, wordEnd);
            boolean count = UNIT_WORD.matcher(run.first()).matches() && COUNT_WORD.matcher(w).matches()
                    && (at == start || afterOf);
            boolean dayNumber = at == run.start() && MONTH_WORD.matcher(run.first()).matches()
                    && DAY_NUMBER.matcher(w).matches();
            if (LEADING.contains(w) || count || dayNumber) {
                start = wordStart;
                at = wordStart;
                afterOf = false;
            } else if (BETWEEN.contains(w)) {
                at = wordStart;
                afterOf = w.equals("of");
            } else {
                return start;
            }
        }
    }

    /**
     * Where the phrase of {@code run} ends once the words after it that belong to it are added: a year (two digits
     * after a comma count: "Dec 5, 27"), a day number after a month, "next" or "last" right after it, and "after" or
     * "before" that end the phrase.
     */
    private static int extendedEnd(String t, Run run) {
        int end = run.end();
        int at = end;
        while (true) {
            int wordStart = at;
            while (wordStart < t.length() && t.charAt(wordStart) == ' ') {
                wordStart++;
            }
            boolean comma = wordStart < t.length() && t.charAt(wordStart) == ',';
            if (comma) {
                wordStart++;
                while (wordStart < t.length() && t.charAt(wordStart) == ' ') {
                    wordStart++;
                }
            }
            int wordEnd = wordStart;
            while (wordEnd < t.length() && wordChar(t.charAt(wordEnd))) {
                wordEnd++;
            }
            if (wordEnd == wordStart) {
                return end;
            }
            String w = t.substring(wordStart, wordEnd);
            boolean direct = at == end && !comma;
            boolean phraseWord = YEAR_WORD.matcher(w).matches()
                    || comma && at == end && w.matches("\\d{2}")
                    && !SPAN_AFTER.matcher(t).region(wordEnd, t.length()).lookingAt()
                    || direct && end == run.end() && MONTH_WORD.matcher(run.last()).matches()
                    && DAY_NUMBER.matcher(w).matches()
                    || direct && (w.equals("next") || w.equals("last"))
                    || direct && (w.equals("after") || w.equals("before"))
                    && END_OF_PHRASE.matcher(t).region(wordEnd, t.length()).lookingAt();
            if (phraseWord) {
                end = wordEnd;
                at = wordEnd;
            } else if (!comma && (w.equals("of") || w.equals("the"))) {
                at = wordEnd;
            } else {
                return end;
            }
        }
    }

    /** A date read from a text, and where the words it was read from start and end in that text. */
    private record Reading(LocalDate date, int start, int end) {
    }

    /**
     * Reads one kind of date phrase from {@code t}: the first one it finds, or nothing. A phrase it reads whole that
     * names no date it blanks in {@code rest} (a copy of {@code t}), so the readers after it read no date from a part
     * of that phrase.
     */
    private interface Reader {
        Optional<Reading> read(String t, LocalDate today, char[] rest);
    }

    /**
     * The readers of {@link #readIn}, by precedence, for each depth of anchors (see {@link #MAX_ANCHOR_DEPTH}): at the
     * deepest one no date is counted from an anchor.
     */
    private static final List<List<Reader>> READERS = IntStream.rangeClosed(0, MAX_ANCHOR_DEPTH)
            .mapToObj(DateExpressions::readers).toList();

    private static List<Reader> readers(int depth) {
        Reader anchored = (t, today, rest) -> {
            if (depth < MAX_ANCHOR_DEPTH) {
                return anchored(t, today, rest, depth);
            }
            if (ANCHORED_FORMS.stream().anyMatch(form -> form.matcher(t).find())) {
                Arrays.fill(rest, ' '); // a phrase counted from an anchor this deep names no date at all
            }
            return Optional.empty();
        };
        return List.of(DateExpressions::iso, anchored,
                DateExpressions::explicitAfterPreposition, DateExpressions::dayWord, DateExpressions::inAmount,
                DateExpressions::periodEnd, DateExpressions::yearEnd,
                (t, today, rest) -> weekPhrase(t, today).filter(r -> r.date() != null),
                DateExpressions::numericDate, DateExpressions::calendarDate, DateExpressions::weekdayRef,
                DateExpressions::nextPeriod);
    }

    /** The date of {@link #readIn}. */
    private static Optional<LocalDate> findIn(String t, LocalDate today) {
        return readIn(t, today).map(Reading::date);
    }

    /** {@link #readIn(String, LocalDate, int)} of a text that is no anchor. */
    private static Optional<Reading> readIn(String t, LocalDate today) {
        return readIn(t, today, 0);
    }

    /**
     * The first date in {@code t} and where its words are, by precedence: an ISO date, an amount or day counted from
     * an anchor, a calendar date or named day right after a date word, today or tomorrow, an amount from today, end
     * of the month or a weekend, end of the year, a weekday of a named week, a calendar date anywhere, a weekday,
     * next week, next month. A phrase read whole that names no date (an anchor that names none, a weekday the date
     * said with it does not fall on, "before" a day that has passed, a year no deadline may have) leaves no date in
     * its words. {@code depth} is how many anchors deep {@code t} is (see {@link #MAX_ANCHOR_DEPTH}).
     */
    private static Optional<Reading> readIn(String t, LocalDate today, int depth) {
        char[] rest = t.toCharArray();
        for (Reader reader : READERS.get(depth)) {
            Optional<Reading> found = reader.read(new String(rest), today, rest);
            for (int rereads = 0; found.isPresent(); rereads++) {
                Matcher year = YEAR.matcher(t).region(found.get().end(), t.length());
                if (!year.lookingAt()) {
                    return found;
                }
                // A year the phrase did not take ("Friday next year", "the 5th of next month 2027") is not applied:
                // the phrase names no date, and the reader looks for another.
                blank(rest, found.get().start(), year.end());
                found = rereads < MAX_REREADS ? reader.read(new String(rest), today, rest) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Optional<Reading> iso(String t, LocalDate today, char[] rest) {
        Matcher m = ISO.matcher(t);
        while (m.find()) {
            try {
                return Optional.of(new Reading(LocalDate.parse(m.group(1)), m.start(1), m.end(1)));
            } catch (RuntimeException ignored) {
                // not a real date; keep looking
            }
        }
        return Optional.empty();
    }

    private static final Pattern TODAY_WORD = Pattern.compile("\\b(?:today|tonight)\\b");
    private static final Pattern DAY_AFTER_TOMORROW = Pattern.compile("\\bday after tomorrow\\b");
    private static final Pattern TOMORROW = Pattern.compile("\\btomorrow\\b");
    private static final Pattern END_OF_THIS_MONTH = Pattern.compile("\\bend of (?:the |this )?month\\b");
    private static final Pattern NEXT_WEEK = Pattern.compile("\\bnext week\\b");
    private static final Pattern NEXT_MONTH = Pattern.compile("\\bnext month\\b");

    /** Today or tonight anywhere in the text, else the day after tomorrow, else tomorrow. */
    private static Optional<Reading> dayWord(String t, LocalDate today, char[] rest) {
        return at(TODAY_WORD, t, today).or(() -> at(DAY_AFTER_TOMORROW, t, today.plusDays(2)))
                .or(() -> at(TOMORROW, t, today.plusDays(1)));
    }

    /** "in 2 weeks", "within three days", "in the next 10 days". */
    private static Optional<Reading> inAmount(String t, LocalDate today, char[] rest) {
        Matcher m = IN_N.matcher(t);
        return m.find() ? Optional.of(new Reading(plus(today, number(m.group(1)), m.group(2)), m.start(), m.end()))
                : Optional.empty();
    }

    /** The end of this month, the weekend after next, this weekend (its Sunday), next weekend. */
    private static Optional<Reading> periodEnd(String t, LocalDate today, char[] rest) {
        return at(END_OF_THIS_MONTH, t, today.with(TemporalAdjusters.lastDayOfMonth()))
                .or(() -> at(WEEKEND_AFTER_NEXT, t, nextWeekend(today).plusWeeks(1)))
                .or(() -> at(THIS_WEEKEND, t, thisWeekend(today)))
                .or(() -> at(NEXT_WEEKEND, t, nextWeekend(today)));
    }

    /** "next week" (its Friday), else "next month" (its last day). */
    private static Optional<Reading> nextPeriod(String t, LocalDate today, char[] rest) {
        return at(NEXT_WEEK, t, today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(DayOfWeek.FRIDAY))
                .or(() -> at(NEXT_MONTH, t, today.plusMonths(1).with(TemporalAdjusters.lastDayOfMonth())));
    }

    /** {@code date}, read from the first match of {@code words} in {@code t}, if there is one. */
    private static Optional<Reading> at(Pattern words, String t, LocalDate date) {
        Matcher m = words.matcher(t);
        return m.find() ? Optional.of(new Reading(date, m.start(), m.end())) : Optional.empty();
    }

    /**
     * The end of the year (see {@link #YEAR_END}); a phrase whose words name no year a deadline may have is blanked.
     */
    private static Optional<Reading> yearEnd(String t, LocalDate today, char[] rest) {
        Matcher m = YEAR_END.matcher(t);
        if (!m.find()) {
            return Optional.empty();
        }
        Year said = year(t, m.end(), today);
        Optional<LocalDate> date = yearEnd(m, said, today);
        if (date.isEmpty()) {
            blank(rest, m.start(), said.end());
            return Optional.empty();
        }
        return Optional.of(new Reading(date.get(), m.start(), said.end()));
    }

    /**
     * December 31 of the year the words of a {@link #YEAR_END} phrase name: this year, next year, a year in digits
     * ("end of 2027") or the year said after the phrase ("end of year 2027", "year-end 2027"). Empty when the words
     * name two different years or a year no deadline may have.
     */
    private static Optional<LocalDate> yearEnd(Matcher m, Year said, LocalDate today) {
        if (said.unreadable()) {
            return Optional.empty();
        }
        TreeSet<Integer> years = new TreeSet<>();
        if (m.group(1) != null) {
            years.add(today.getYear());
        }
        if (m.group(2) != null) {
            years.add(today.getYear() + 1);
        }
        if (m.group(3) != null) {
            years.add(Integer.parseInt(m.group(3)));
        }
        if (said.value() != null) {
            years.add(said.value());
        }
        if (years.size() > 1) {
            return Optional.empty();
        }
        int year = years.isEmpty() ? today.getYear() : years.first();
        return year >= MIN_YEAR && year <= MAX_YEAR ? Optional.of(LocalDate.of(year, 12, 31)) : Optional.empty();
    }

    /** The forms of {@link #anchored}: each holds an anchor or counts from one. */
    private static final List<Pattern> ANCHORED_FORMS = List.of(AFTER_THIS, AMOUNT_FROM, WEEKDAY_WEEK, WEEKS_ANCHOR,
            DAY_WORD_WEEK, WEEKEND_AROUND, WEEKEND_OF, ANCHOR_IN, AROUND);

    /**
     * The first date counted from an anchor, in text order: "two weeks from today", "a week from Friday", "a week on
     * Friday", "a week Friday", "a week today", "Friday week", "Tuesday fortnight", "tomorrow week", "today
     * fortnight", "today in two weeks", "the Monday after next", "the Monday after Christmas", "the day before the
     * 20th", "the weekend after Christmas", "the weekend of December 12", "Friday after this Friday". A phrase whose
     * anchor names no date ("two weeks from the launch", "the weekend of December 9" when that is a Wednesday) is
     * blanked in {@code rest}, so the date words around it are not read as the date either, and no phrase that starts
     * inside it is read. A reading ends where the words its anchor was read from end. {@code depth} is how many
     * anchors deep {@code t} is: an anchor is read one deeper.
     */
    private static Optional<Reading> anchored(String t, LocalDate today, char[] rest, int depth) {
        List<Candidate<Reading>> found = new ArrayList<>();
        Matcher afterThis = AFTER_THIS.matcher(t);
        while (afterThis.find()) {
            DayOfWeek day = weekday(afterThis.group(1));
            DayOfWeek named = weekday(afterThis.group(2));
            LocalDate thisDay = today.getDayOfWeek() == named ? today : today.with(TemporalAdjusters.next(named));
            int start = afterThis.start();
            int end = afterThis.end();
            found.add(new Candidate<>(start, end, () -> Optional.of(new Reading(
                    thisDay.with(TemporalAdjusters.next(day)), start, end))));
        }
        Matcher from = AMOUNT_FROM.matcher(t);
        while (from.find()) {
            int anchorAt = from.end();
            String anchor = anchorText(t, anchorAt);
            if (from.group(3).equals("on") && !((from.group(2).startsWith("week") || from.group(2).startsWith("fortnight"))
                    && WEEKDAY_ANCHOR.matcher(anchor).find())) {
                continue;
            }
            String amount = from.group(1);
            String unit = from.group(2);
            boolean before = from.group(3).equals("before");
            long sign = before ? -1 : 1;
            int start = from.start();
            found.add(new Candidate<>(start, anchorAt + anchor.length(), () -> fromAnchor(t, anchorAt, start, today, depth,
                    before, a -> plus(a, sign * number(amount), unit))));
        }
        Matcher weekdayWeek = WEEKDAY_WEEK.matcher(t);
        while (weekdayWeek.find()) {
            String qualifier = weekdayWeek.group(1);
            DayOfWeek day = weekday(weekdayWeek.group(2));
            int weeks = weekdayWeek.group(3).equals("week") ? 1 : 2;
            int start = weekdayWeek.start();
            int end = weekdayWeek.end();
            found.add(new Candidate<>(start, end, () -> weekdayThenWeeks(qualifier, day, weeks, today)
                    .map(d -> new Reading(d, start, end))));
        }
        Matcher weeksAnchor = WEEKS_ANCHOR.matcher(t);
        while (weeksAnchor.find()) {
            String amount = weeksAnchor.group(1);
            String unit = weeksAnchor.group(2);
            String anchor = weeksAnchor.group(3);
            int start = weeksAnchor.start();
            int end = weeksAnchor.end();
            found.add(new Candidate<>(start, end, () -> anchorDate(anchor, today, depth)
                    .map(a -> new Reading(plus(a, number(amount), unit), start, end))));
        }
        Matcher dayWordWeek = DAY_WORD_WEEK.matcher(t);
        while (dayWordWeek.find()) {
            LocalDate base = dayWordWeek.group(1).equals("today") ? today : today.plusDays(1);
            int weeks = dayWordWeek.group(2).equals("week") ? 1 : 2;
            int start = dayWordWeek.start();
            int end = dayWordWeek.end();
            found.add(new Candidate<>(start, end, () -> Optional.of(new Reading(base.plusWeeks(weeks), start, end))));
        }
        Matcher weekend = WEEKEND_AROUND.matcher(t);
        while (weekend.find()) {
            int anchorAt = weekend.end();
            String anchor = anchorText(t, anchorAt);
            boolean after = weekend.group(1).equals("after") || weekend.group(1).equals("following");
            if (after && AFTER_NEXT.matcher(anchor).find()) {
                continue; // "the weekend after next"
            }
            int start = weekend.start();
            found.add(new Candidate<>(start, anchorAt + anchor.length(), () -> fromAnchor(t, anchorAt, start, today, depth,
                    !after, a -> after ? a.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).plusDays(1)
                            : a.with(TemporalAdjusters.previous(DayOfWeek.SUNDAY)))));
        }
        Matcher weekendOf = WEEKEND_OF.matcher(t);
        while (weekendOf.find()) {
            int anchorAt = weekendOf.end();
            int start = weekendOf.start();
            found.add(new Candidate<>(start, anchorAt + anchorText(t, anchorAt).length(),
                    () -> fromAnchor(t, anchorAt, start, today, depth, false, DateExpressions::sundayOfWeekend)));
        }
        Matcher in = ANCHOR_IN.matcher(t);
        while (in.find()) {
            String anchor = in.group(1);
            String amount = in.group(2);
            String unit = in.group(3);
            int start = in.start();
            int end = in.end();
            found.add(new Candidate<>(start, end, () -> anchorDate(anchor, today, depth)
                    .map(a -> new Reading(plus(a, number(amount), unit), start, end))));
        }
        Matcher around = AROUND.matcher(t);
        while (around.find()) {
            int anchorAt = around.end();
            String anchor = anchorText(t, anchorAt);
            boolean after = around.group(4).equals("after") || around.group(4).equals("following");
            int start = around.start();
            if (around.group(2) != null && after && AFTER_NEXT.matcher(anchor).find()) {
                DayOfWeek day = weekday(around.group(2));
                int end = anchorAt + "next".length();
                found.add(new Candidate<>(start, end, () -> Optional.of(new Reading(
                        today.with(TemporalAdjusters.next(day)).plusWeeks(1), start, end))));
                continue;
            }
            if (around.group(1) == null && (around.group(2) == null || TIME_OF_DAY.matcher(anchor.strip()).find())) {
                continue;
            }
            int end = anchorAt + anchor.length();
            if (around.group(2) != null) {
                DayOfWeek day = weekday(around.group(2));
                found.add(new Candidate<>(start, end, () -> fromAnchor(t, anchorAt, start, today, depth, !after,
                        a -> a.with(after ? TemporalAdjusters.next(day) : TemporalAdjusters.previous(day)))));
            } else {
                found.add(new Candidate<>(start, end, () -> fromAnchor(t, anchorAt, start, today, depth, !after,
                        a -> a.plusDays(after ? 1 : -1))));
            }
        }
        found.sort(Comparator.comparingInt(Candidate::start));
        int failedStart = -1;
        int failedEnd = -1;
        for (Candidate<Reading> candidate : found) {
            if (candidate.start() > failedStart && candidate.start() < failedEnd) {
                continue; // inside a phrase whose anchor names no date: its own anchor is part of that one
            }
            int beforeAt = wordBeforeStart(t, candidate.start());
            int start = beforeAt >= 0 && wordBefore(t, candidate.start()).equals("before") ? beforeAt
                    : candidate.start();
            Optional<Reading> reading = candidate.value().get()
                    .map(r -> new Reading(eve(start < candidate.start(), r.date(), today), start, r.end()));
            if (reading.isPresent() && reading.get().date() != null) {
                return reading;
            }
            int end = Math.max(candidate.end(), reading.map(Reading::end).orElse(0));
            blank(rest, start, end);
            failedStart = candidate.start();
            failedEnd = Math.max(failedEnd, end);
        }
        return Optional.empty();
    }

    /** The Sunday of the weekend {@code day} falls in; null when {@code day} is no Saturday or Sunday. */
    private static LocalDate sundayOfWeekend(LocalDate day) {
        return switch (day.getDayOfWeek()) {
            case SATURDAY -> day.plusDays(1);
            case SUNDAY -> day;
            default -> null;
        };
    }

    /**
     * "Friday week": {@code weeks} weeks after the weekday the words name ("next Tuesday week" counts from next
     * Tuesday); "before Friday week" is the day before that. A bare weekday said on that weekday ("Wednesday week" on
     * a Wednesday) may count from today or from a week on: no date.
     */
    private static Optional<LocalDate> weekdayThenWeeks(String qualifier, DayOfWeek day, int weeks, LocalDate today) {
        LocalDate base;
        if ("this".equals(qualifier)) {
            base = today.with(TemporalAdjusters.nextOrSame(day));
        } else if (today.getDayOfWeek() == day && !"next".equals(qualifier)) {
            return Optional.empty();
        } else {
            base = today.with(TemporalAdjusters.next(day));
        }
        LocalDate date = base.plusWeeks(weeks);
        return Optional.of("before".equals(qualifier) ? date.minusDays(1) : date);
    }

    /** Anchors that name today or a day counted from it; they have no later occurrence. */
    private static final Pattern FIXED_ANCHOR = Pattern.compile("^(?:now|today|tonight|tomorrow|the\\s+day\\s+after"
            + "\\s+tomorrow)\\b|\\d{4}");

    /**
     * The date {@code offset} gives from the date the anchor at {@code anchorAt} names, read from {@code start} to
     * the end of the anchor's own words. A date before an anchor that has already passed ("the Friday before the
     * 20th" said on the 18th, "two months before Christmas" said in November) is counted from the anchor's next
     * occurrence instead (the 20th of next month, next year's Christmas), as a "before" date that has passed this
     * year is next year's. Anchors that name today, tomorrow or a year keep their date.
     */
    private static Optional<Reading> fromAnchor(String t, int anchorAt, int start, LocalDate today, int depth,
                                                boolean before, UnaryOperator<LocalDate> offset) {
        String anchor = anchorText(t, anchorAt);
        Optional<Reading> named = anchorReading(anchor, today, depth);
        if (named.isEmpty()) {
            return Optional.empty();
        }
        int end = anchorAt + named.get().end();
        LocalDate anchorDay = named.get().date();
        LocalDate date = offset.apply(anchorDay);
        if (before && !FIXED_ANCHOR.matcher(anchor.strip()).find()) {
            for (int i = 0; i < 24 && date.isBefore(today); i++) {
                Optional<LocalDate> later = anchorDate(anchor, anchorDay.plusDays(1), depth);
                if (later.isEmpty() || !later.get().isAfter(anchorDay)) {
                    break;
                }
                anchorDay = later.get();
                date = offset.apply(anchorDay);
            }
        }
        return Optional.of(new Reading(date, start, end));
    }

    /** The anchor words after "from", "after" or "before": up to the end of their clause (see {@link #clause}). */
    private static String anchorText(String t, int start) {
        return clause(t, start);
    }

    /** The date an anchor names (see {@link #anchorReading}). */
    private static Optional<LocalDate> anchorDate(String anchor, LocalDate today, int depth) {
        return anchorReading(anchor, today, depth).map(Reading::date);
    }

    /**
     * The date an anchor names, with where its words are in {@code anchor}: now, today, tomorrow, a weekday, a holiday
     * or any date a date word may point at. {@code depth} is how many anchors deep the phrase the anchor belongs to
     * is; the anchor is read one deeper.
     */
    private static Optional<Reading> anchorReading(String anchor, LocalDate today, int depth) {
        int lead = anchor.length() - anchor.stripLeading().length();
        String a = anchor.strip();
        Matcher now = NOW.matcher(a);
        if (now.find()) {
            return Optional.of(new Reading(today, lead, lead + now.end()));
        }
        if (a.isEmpty()) {
            return Optional.empty();
        }
        // Read as if after "on", so a holiday ("after Christmas") counts and a bare weekday is the next one.
        String on = "on ";
        return readIn(on + a, today, depth + 1).map(r -> new Reading(r.date(), lead + Math.max(0, r.start() - on.length()),
                lead + Math.max(0, r.end() - on.length())));
    }

    /**
     * The first day of a named week in {@code t}: "Friday next week", "next week Friday", "Friday this week", "the
     * week after next" (its Friday), "the start of next week" (its Monday), "early next week" (its Tuesday), "the end
     * of next week" (its Sunday), the same parts of the week after next.
     */
    private static Optional<Reading> weekPhrase(String t, LocalDate today) {
        List<Candidate<LocalDate>> found = new ArrayList<>();
        for (Pattern form : WEEK_FORMS) {
            Matcher m = form.matcher(t);
            if (m.find()) {
                LocalDate day = weekDay(m, today, false);
                found.add(new Candidate<>(m.start(), m.end(), () -> Optional.ofNullable(day)));
            }
        }
        return found.stream().min(Comparator.comparingInt(Candidate::start))
                .map(c -> new Reading(c.value().get().orElse(null), c.start(), c.end()));
    }

    /**
     * The day of a named week (see {@link #weekPhrase}) whose words start at {@code from} in {@code t}. With
     * {@code before} (the phrase follows "before") the date is the day before that day, and the day before the week
     * starts for "the week after next" alone; "the end of" a week stays its Sunday. The reading's date is null when
     * that day has passed.
     */
    private static Optional<Reading> weekPhraseAt(String t, int from, LocalDate today, boolean before) {
        for (Pattern form : WEEK_FORMS) {
            Matcher m = startingAt(form, t, from);
            if (m != null) {
                return Optional.of(new Reading(weekDay(m, today, before), m.start(), m.end()));
            }
        }
        return Optional.empty();
    }

    /** The forms of {@link #weekPhrase}, in the order they are tried. */
    private static final List<Pattern> WEEK_FORMS = List.of(WEEKDAY_OF_WEEK, WEEK_THEN_WEEKDAY, WEEK_AFTER_NEXT,
            PART_OF_NEXT_WEEK);

    /** The day the {@link #WEEK_FORMS} match {@code m} names (see {@link #weekPhraseAt} for {@code before}). */
    private static LocalDate weekDay(Matcher m, LocalDate today, boolean before) {
        Pattern form = m.pattern();
        if (form == WEEKDAY_OF_WEEK) {
            return eve(before, weekOf(today, m.group(2).equals("this") ? 0 : 1, weekday(m.group(1))), today);
        }
        if (form == WEEK_THEN_WEEKDAY) {
            return eve(before, weekOf(today, m.group(1).equals("this") ? 0 : 1, weekday(m.group(2))), today);
        }
        if (form == WEEK_AFTER_NEXT) {
            return m.group(1) != null ? eve(before, weekOf(today, 2, weekday(m.group(1))), today)
                    : before ? weekOf(today, 2, DayOfWeek.MONDAY).minusDays(1) : weekOf(today, 2, DayOfWeek.FRIDAY);
        }
        DayOfWeek weekday = switch (m.group(1)) {
            case "early" -> DayOfWeek.TUESDAY;
            case "end" -> DayOfWeek.SUNDAY;
            default -> DayOfWeek.MONDAY;
        };
        LocalDate day = weekOf(today, m.group(2).startsWith("next") ? 1 : 2, weekday);
        return m.group(1).equals("end") ? day : eve(before, day, today);
    }

    /** The Sunday of next weekend: a week after this weekend's Sunday, or the coming one when today is Sunday. */
    private static LocalDate nextWeekend(LocalDate today) {
        return today.with(TemporalAdjusters.next(DayOfWeek.SUNDAY)).plusWeeks(
                today.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : 1);
    }

    /** The Sunday of this weekend: today when today is Sunday. */
    private static LocalDate thisWeekend(LocalDate today) {
        return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
    }

    /** {@code day} of the calendar week (Monday to Sunday) {@code weeks} weeks after this one. */
    private static LocalDate weekOf(LocalDate today, int weeks, DayOfWeek day) {
        return today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weeks).with(day);
    }

    /** {@code date} itself, or with {@code before} the day before it; null when that day is before today. */
    private static LocalDate eve(boolean before, LocalDate date, LocalDate today) {
        if (date == null || !before) {
            return date;
        }
        return notPast(date.minusDays(1), today);
    }

    /** {@link #eve} of a date that may be missing. */
    private static LocalDate eve(boolean before, Optional<LocalDate> date, LocalDate today) {
        return eve(before, date.orElse(null), today);
    }

    /** {@code date}, or null when it is before today. */
    private static LocalDate notPast(LocalDate date, LocalDate today) {
        return date.isBefore(today) ? null : date;
    }

    /**
     * "the start of next month" (the 1st), "early next month" (the 5th), "mid next month" (the 15th), "late March"
     * (the 25th), "the end of next month" (its last day). "Of the month" is the next such day; a named month is
     * this year's unless that day has passed, then next year's (unless a year is given).
     */
    private static Optional<LocalDate> partOfMonth(Matcher m, Year year, LocalDate today) {
        String part = m.group(1);
        String which = m.group(2);
        if ("the".equals(which)) {
            return part.equals("end") ? Optional.of(today.with(TemporalAdjusters.lastDayOfMonth()))
                    : dayOfMonth(partDay(part, 31), today);
        }
        if (which != null) {
            LocalDate month = (which.equals("this") ? today : today.plusMonths(1)).withDayOfMonth(1);
            return Optional.of(month.withDayOfMonth(partDay(part, month.lengthOfMonth())));
        }
        return partOfNamedMonth(part, month(m.group(3)), year, today);
    }

    /** {@link #partOfNamedMonth(String, Month, Integer, LocalDate)} with a said year; empty for an unreadable one. */
    private static Optional<LocalDate> partOfNamedMonth(String part, Month month, Year year, LocalDate today) {
        return year.unreadable() ? Optional.empty() : Optional.of(partOfNamedMonth(part, month, year.value(), today));
    }

    /**
     * The day of a part of a named month ("mid November", "late March"): in {@code year} when given, else this
     * year's unless that day has passed, then next year's ("mid November" said on November 18 is next November's).
     */
    private static LocalDate partOfNamedMonth(String part, Month month, Integer year, LocalDate today) {
        LocalDate first = occurrence(month, year, today);
        LocalDate day = first.withDayOfMonth(partDay(part, first.lengthOfMonth()));
        return year == null && day.isBefore(today)
                ? first.plusYears(1).withDayOfMonth(partDay(part, first.plusYears(1).lengthOfMonth())) : day;
    }

    /** The day a part of a month stands for. */
    private static int partDay(String part, int lengthOfMonth) {
        return switch (part) {
            case "start", "beginning" -> 1;
            case "early" -> 5;
            case "mid", "middle" -> 15;
            case "late" -> 25;
            default -> lengthOfMonth;
        };
    }

    /** One date form found in a text: where it starts, where its words end and how to read it. */
    private record Candidate<T>(int start, int end, Supplier<Optional<T>> value) {
    }

    /** {@link #NUMERIC_DATE_FORM} with its parts as groups: one number, the separator, the other and the year. */
    private static final Pattern NUMERIC_DATE = Pattern.compile("(?<![\\w./-])(\\d{1,2})([/.-])(\\d{1,2})"
            + "(?:\\2(\\d{4}|\\d{2}))?" + NUMERIC_DATE_END);
    /** A weekday right before a date in digits: "Friday 27/11", "friday, the 27.11.2026". */
    private static final Pattern WEEKDAY_BEFORE_NUMERIC = Pattern.compile("\\b" + WEEKDAY + ",?\\s+(?:the\\s+)?$");
    /** A weekday and "the" that may stand between a date word and a date in digits: "by Friday 27/11". */
    private static final Pattern WEEKDAY_LEAD = Pattern.compile("(?:" + WEEKDAY + ",?\\s+)?(?:the\\s+)?");

    /**
     * The first date in digits (see {@link #NUMERIC_DATE_FORM}). With a slash day and month may be either way round;
     * with a full stop or a dash the day comes first. Each way that names a date is read (the next one on or after
     * today, or in the year given), and with a weekday right before it only a date on that weekday counts, and only
     * when it is the nearer of two readings (see {@link #numeric}). It names a date when exactly one remains:
     * "Friday 27/11", "27/11" and "Friday 27-11-2026" are November 27, "4/12" names none (April 12 or December 4),
     * "Friday 4/12" and "Friday 4.12." are December 4 when that is a Friday, "Saturday 6.12" names none when December
     * 6 is a Sunday, and so does "Saturday 6/12" when only June 12 is a Saturday. One that names none is blanked in
     * {@code rest}, its weekday with it, so "Friday 13.11" (no Friday, and maybe a time of day) names no date at all.
     */
    private static Optional<Reading> numericDate(String t, LocalDate today, char[] rest) {
        Matcher m = NUMERIC_DATE.matcher(t);
        while (m.find()) {
            NumericDate numeric = numeric(t, m, today);
            if (numeric == null) {
                continue;
            }
            if (numeric.dates().size() == 1) {
                return Optional.of(new Reading(numeric.dates().getFirst(), numeric.start(), m.end()));
            }
            blank(rest, numeric.start(), m.end());
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * A date in digits read both ways: where its words start (at the weekday said with it, if any), whether a
     * weekday was said with it, whether the digits may also be something else (a time of day: "Friday 4.12"), and
     * the dates it may name.
     */
    private record NumericDate(int start, boolean weekday, boolean mayBeTime, List<LocalDate> dates) {
    }

    /**
     * The dates the date in digits {@code m} may name (see {@link #numericDate}); null when it is no date. With a
     * slash the digits are read both ways round, with a full stop or a dash day first only ("6.12" is December 6,
     * "11-27-2026" no date). Without a year, day and month with a full stop are a date only when the day can be no
     * hour ("27.11"; "10.30" may be a time of day, "1.5" a number) or a weekday stands right before them ("Friday
     * 4.12"), and day and month with a dash only after a weekday and when one of the numbers can be no hour ("Friday
     * 27-11"; "Friday 10-11" may be a span of hours). With a weekday only the dates on it remain, and none when it fits
     * only the later of two readings that are still to come ("Saturday 6/12" said on 2026-11-19: December 6 is a
     * Sunday, June 12 2027 a Saturday).
     */
    private static NumericDate numeric(String t, Matcher m, LocalDate today) {
        int first = Integer.parseInt(m.group(1));
        int second = Integer.parseInt(m.group(3));
        String separator = m.group(2);
        Matcher weekday = WEEKDAY_BEFORE_NUMERIC.matcher(t).region(Math.max(0, m.start() - 24), m.start())
                .useTransparentBounds(true);
        boolean named = weekday.find();
        boolean mayBeTime = false;
        if (m.group(4) == null && separator.equals(".") && first <= 24) {
            if (!named) {
                return null;
            }
            mayBeTime = true;
        }
        if (m.group(4) == null && separator.equals("-") && (!named || first <= 24 && second <= 24)) {
            return null;
        }
        Integer year = m.group(4) == null ? null
                : m.group(4).length() == 2 ? 2000 + Integer.parseInt(m.group(4)) : Integer.parseInt(m.group(4));
        TreeSet<LocalDate> dates = new TreeSet<>();
        if (year == null || year >= MIN_YEAR && year <= MAX_YEAR) {
            if (second >= 1 && second <= 12) {
                monthDay(Month.of(second), first, year, today).ifPresent(dates::add);
            }
            if (separator.equals("/") && first >= 1 && first <= 12) {
                monthDay(Month.of(first), second, year, today).ifPresent(dates::add);
            }
        }
        if (!named) {
            return new NumericDate(m.start(), false, false, List.copyOf(dates));
        }
        DayOfWeek day = weekday(weekday.group(1));
        List<LocalDate> onWeekday = dates.stream().filter(d -> d.getDayOfWeek() == day).toList();
        // A weekday that fits only the later of two readings still to come fits no date the person is likely to mean:
        // the nearer one, whose weekday they would know, falls on another day.
        List<LocalDate> coming = dates.stream().filter(d -> !d.isBefore(today)).toList();
        boolean onlyTheLater = coming.size() == 2 && onWeekday.size() == 1 && onWeekday.getFirst().equals(coming.getLast());
        return new NumericDate(weekday.start(), true, mayBeTime, onlyTheLater ? List.of() : onWeekday);
    }

    /**
     * The date in digits that starts at {@code from} in {@code t}, a weekday and "the" before it allowed ("Friday
     * 27/11", "the 4.12.", "27-11-2026"), read as {@link #numericDate} reads it, the next one on or after
     * {@code dayFrom}; with {@code before} the day before it, which may not be before {@code today}. The reading's
     * date is null when the digits name no date or no single one ("4/12", "Friday 28/11"). Empty when no date in
     * digits starts there.
     */
    private static Optional<Reading> numericAt(String t, int from, LocalDate dayFrom, boolean before,
                                               LocalDate today) {
        Matcher lead = startingAt(WEEKDAY_LEAD, t, from);
        Matcher m = startingAt(NUMERIC_DATE, t, lead == null ? from : lead.end());
        if (m == null) {
            return Optional.empty();
        }
        NumericDate numeric = numeric(t, m, dayFrom);
        if (numeric == null) {
            return Optional.empty();
        }
        LocalDate date = numeric.dates().size() == 1 ? numeric.dates().getFirst() : null;
        return read(eve(before, date, today), m.end());
    }

    /**
     * The earliest calendar date in the text: a weekday with a day and a month, month day, day month, the Nth of a
     * month, a weekday with a day of the month, the first to fifth or last weekday of a month, the first or last day
     * of a month, the end or middle of a named month, or a day of the month after a date word. A year said after it
     * belongs to it. When the earliest one names no date ("Friday the 5th" when the 5th is no Friday), its words are
     * blanked in {@code rest} and no calendar date is read.
     */
    private static Optional<Reading> calendarDate(String t, LocalDate today, char[] rest) {
        List<Candidate<LocalDate>> found = new ArrayList<>();
        Matcher weekdayDate = WEEKDAY_DATE.matcher(t);
        if (weekdayDate.find()) {
            Year year = year(t, weekdayDate.end(), today);
            found.add(new Candidate<>(weekdayDate.start(), year.end(), () -> weekdayDate(weekdayDate, year, today)));
        }
        Matcher monthDay = MONTH_DAY.matcher(t);
        if (monthDay.find()) {
            Year year = year(t, monthDay.end(), today);
            found.add(new Candidate<>(monthDay.start(), year.end(), () -> monthDay(month(monthDay.group(1)),
                    Integer.parseInt(monthDay.group(2)), year, today)));
        }
        Matcher dayMonth = DAY_MONTH.matcher(t);
        if (dayMonth.find()) {
            Year year = year(t, dayMonth.end(), today);
            found.add(new Candidate<>(dayMonth.start(), year.end(), () -> monthDay(month(dayMonth.group(2)),
                    Integer.parseInt(dayMonth.group(1)), year, today)));
        }
        Matcher nth = NTH_OF_MONTH.matcher(t);
        if (nth.find()) {
            found.add(new Candidate<>(nth.start(), nth.end(), () -> nthOfMonth(nth, today)));
        }
        Matcher weekdayDay = WEEKDAY_DAY.matcher(t);
        if (weekdayDay.find()) {
            found.add(new Candidate<>(weekdayDay.start(), weekdayDay.end(), () -> weekdayDay(weekdayDay, today)));
        }
        Matcher nthWeekday = NTH_WEEKDAY_OF.matcher(t);
        if (nthWeekday.find()) {
            Year year = nthWeekday.group(5) != null ? year(t, nthWeekday.end(), today) : Year.none(nthWeekday.end());
            found.add(new Candidate<>(nthWeekday.start(), year.end(), () -> nthWeekdayOf(nthWeekday, year, today)));
        }
        Matcher nthDay = NTH_DAY_OF.matcher(t);
        if (nthDay.find()) {
            Year year = nthDay.group(4) != null ? year(t, nthDay.end(), today) : Year.none(nthDay.end());
            found.add(new Candidate<>(nthDay.start(), year.end(), () -> nthDayOf(nthDay, year, today)));
        }
        Matcher endOf = END_OF_MONTH_NAMED.matcher(t);
        if (endOf.find()) {
            Year year = year(t, endOf.end(), today);
            found.add(new Candidate<>(endOf.start(), year.end(), () -> monthEnd(month(endOf.group(1)), year, today)));
        }
        Matcher mid = MID_MONTH.matcher(t);
        if (mid.find()) {
            Year year = year(t, mid.end(), today);
            found.add(new Candidate<>(mid.start(), year.end(), () -> partOfNamedMonth("mid", month(mid.group(1)), year,
                    today)));
        }
        Matcher dayOfMonth = DAY_OF_MONTH.matcher(t);
        if (dayOfMonth.find()) {
            found.add(new Candidate<>(dayOfMonth.start(1), dayOfMonth.end(),
                    () -> dayOfMonth(Integer.parseInt(dayOfMonth.group(1)), today)));
        }
        Matcher part = PART_OF_MONTH.matcher(t);
        if (part.find()) {
            Year year = part.group(3) != null ? year(t, part.end(), today) : Year.none(part.end());
            found.add(new Candidate<>(part.start(), year.end(), () -> partOfMonth(part, year, today)));
        }
        Optional<Candidate<LocalDate>> first = found.stream().min(Comparator.comparingInt(Candidate::start));
        if (first.isEmpty()) {
            return Optional.empty();
        }
        Candidate<LocalDate> candidate = first.get();
        Optional<LocalDate> date = candidate.value().get();
        if (date.isEmpty()) {
            blank(rest, candidate.start(), candidate.end());
            return Optional.empty();
        }
        return Optional.of(new Reading(date.get(), candidate.start(), candidate.end()));
    }

    /**
     * A weekday: "last" or "past" ones are before today; "this Friday" or "by Friday" said on a Friday is today;
     * "next Friday", "the coming Friday", "until Friday" (waiting until today would be no wait) and a bare "Friday"
     * are the next one after today; "before Friday" is the day before that (see {@link #beforeWeekday}).
     */
    private static Optional<Reading> weekdayRef(String t, LocalDate today, char[] rest) {
        Matcher m = WEEKDAY_REF.matcher(t);
        if (!m.find()) {
            return Optional.empty();
        }
        DayOfWeek day = weekday(m.group(2));
        String qualifier = m.group(1);
        LocalDate date;
        if ("last".equals(qualifier) || "past".equals(qualifier)) {
            date = today.with(TemporalAdjusters.previous(day));
        } else if ("before".equals(qualifier)) {
            date = beforeWeekday(null, day, today);
        } else {
            boolean sameDay = today.getDayOfWeek() == day && qualifier != null
                    && Set.of("this", "by").contains(qualifier);
            date = sameDay ? today : today.with(TemporalAdjusters.next(day));
        }
        return Optional.of(new Reading(date, m.start(), m.end()));
    }

    /**
     * The day before a weekday: "before Friday" and "before next Friday" are the day before the next Friday after
     * today; "before this Friday" the day before this week's, null when that has passed (said on a Friday or later).
     */
    private static LocalDate beforeWeekday(String qualifier, DayOfWeek day, LocalDate today) {
        if ("this".equals(qualifier)) {
            return notPast(today.with(TemporalAdjusters.nextOrSame(day)).minusDays(1), today);
        }
        return today.with(TemporalAdjusters.next(day)).minusDays(1);
    }

    /**
     * "by April 12", "until the 3rd of May", "due the 20th", "by March", "before Christmas", "by the end of the
     * year": a date that a date word points at, read from the date word on. One that names no date (see
     * {@link #afterPreposition}) is blanked in {@code rest}.
     */
    private static Optional<Reading> explicitAfterPreposition(String t, LocalDate today, char[] rest) {
        Matcher m = EXPLICIT_AFTER_PREPOSITION.matcher(t);
        while (m.find()) {
            if (m.group(1).equals("in") && WEEKDAY_WORD.matcher(wordBefore(t, m.start())).matches()) {
                continue; // the "in" of "the third Thursday in January" belongs to the weekday
            }
            Optional<Reading> found = afterPreposition(m.group(1), t, m.end(), today);
            if (found.isEmpty()) {
                continue;
            }
            int end = found.get().end();
            if (found.get().date() == null) {
                blank(rest, m.start(), end);
                continue;
            }
            return Optional.of(new Reading(found.get().date(), m.start(), end));
        }
        return Optional.empty();
    }

    /** A weekday, or its plural, as a word on its own. */
    private static final Pattern WEEKDAY_WORD = Pattern.compile("(?:" + WEEKDAY_NAMES + ")s?");
    /** "The" at the start of the words after a date word. */
    private static final Pattern THE_PREFIX = Pattern.compile("the\\s+");
    /** "the end of the month", "end of this week": the last day of this month, this week's Sunday. */
    private static final Pattern END_OF_PERIOD = Pattern.compile("^(?:the\\s+)?end\\s+of\\s+(?:the\\s+|this\\s+)?"
            + "(week|month)\\b(?!\\s+(?:after|before)\\b)");
    private static final Pattern BEFORE_NOW = Pattern.compile("^(?:today|now)\\b");
    private static final Pattern BEFORE_TONIGHT = Pattern.compile("^tonight\\b");
    private static final Pattern BEFORE_TOMORROW = Pattern.compile("^(?:the\\s+)?(day\\s+after\\s+)?tomorrow\\b");
    private static final Pattern BEFORE_WEEKDAY = Pattern.compile("^(?:(next|this)\\s+)?(?:(?:the\\s+)?(?:coming"
            + "|following)\\s+)?" + WEEKDAY + "\\b");
    private static final Pattern BEFORE_NEXT_WEEKEND = Pattern.compile("^next\\s+week-?end\\b");
    private static final Pattern BEFORE_NEXT_PERIOD = Pattern.compile("^next\\s+(week|month|year)\\b");
    private static final Pattern BEFORE_WEEKEND = Pattern.compile("^(?:(?:the|this)\\s+)?week-?end\\b"
            + "(?!\\s+(?:(?:" + CLOSE + "|" + LOOSE + ")\\s+)?(?:after|before|following|preceding|of)\\b)");

    /** A reading of the words after a date word, ending at {@code end} in the text; a null date names no date. */
    private static Optional<Reading> read(LocalDate date, int end) {
        return Optional.of(new Reading(date, end, end));
    }

    /**
     * The date a date word points at, read from the words that start at {@code from} in {@code t} (the words after
     * it), with where those words end in {@code t}; the reading's date is null when the words are read whole but name
     * no date. Only the words from {@code from} on are looked at, as if the text began there. After "before" a day is
     * the day before it ("before December 5" is December 4, "before the 25th" the 24th, "before Christmas" December
     * 24, "before Friday" Thursday, see {@link #beforeDay}), a day that recurs is counted from tomorrow ("before
     * December 5" said on December 5 is next year's December 4), and a day before today is no date; "before March" is
     * the last day of February; "before the end of" a month, week or year is its last day, as with "by".
     */
    private static Optional<Reading> afterPreposition(String preposition, String t, int from, LocalDate today) {
        boolean before = "before".equals(preposition);
        LocalDate dayFrom = before ? today.plusDays(1) : today;
        Matcher the = startingAt(THE_PREFIX, t, from);
        int withoutThe = the == null ? from : the.end();
        Matcher weekdayDate = startingAt(WEEKDAY_DATE, t, from);
        if (weekdayDate != null) {
            Year year = year(t, weekdayDate.end(), today);
            return read(eve(before, weekdayDate(weekdayDate, year, dayFrom), today), year.end());
        }
        Optional<Reading> numeric = numericAt(t, from, dayFrom, before, today);
        if (numeric.isPresent()) {
            return numeric;
        }
        Matcher monthDay = startingAt(MONTH_DAY, t, from);
        if (monthDay != null) {
            Year year = year(t, monthDay.end(), today);
            return read(eve(before, monthDay(month(monthDay.group(1)), Integer.parseInt(monthDay.group(2)), year,
                    dayFrom), today), year.end());
        }
        Matcher dayMonth = startingAt(DAY_MONTH, t, withoutThe);
        if (dayMonth != null) {
            Year year = year(t, dayMonth.end(), today);
            return read(eve(before, monthDay(month(dayMonth.group(2)), Integer.parseInt(dayMonth.group(1)), year,
                    dayFrom), today), year.end());
        }
        Matcher nth = startingAt(NTH_OF_MONTH, t, from);
        if (nth != null) {
            return read(eve(before, nthOfMonth(nth, today), today), nth.end());
        }
        Matcher weekdayDay = startingAt(WEEKDAY_DAY, t, from);
        if (weekdayDay != null) {
            return read(eve(before, weekdayDay(weekdayDay, dayFrom), today), weekdayDay.end());
        }
        Matcher nthWeekday = startingAt(NTH_WEEKDAY_OF, t, from);
        if (nthWeekday != null) {
            Year year = nthWeekday.group(5) != null ? year(t, nthWeekday.end(), today) : Year.none(nthWeekday.end());
            return read(eve(before, nthWeekdayOf(nthWeekday, year, today), today), year.end());
        }
        Matcher nthDay = startingAt(NTH_DAY_OF, t, from);
        if (nthDay != null) {
            Year year = nthDay.group(4) != null ? year(t, nthDay.end(), today) : Year.none(nthDay.end());
            return read(eve(before, nthDayOf(nthDay, year, today), today), year.end());
        }
        Matcher dayOfMonth = startingAt(DAY_OF_MONTH, t, from);
        if (dayOfMonth != null) {
            return read(eve(before, dayOfMonth(Integer.parseInt(dayOfMonth.group(1)), dayFrom), today),
                    dayOfMonth.end());
        }
        Matcher endOf = startingAt(END_OF_MONTH_NAMED, t, from);
        if (endOf != null) {
            Year year = year(t, endOf.end(), today);
            return read(monthEnd(month(endOf.group(1)), year, today).orElse(null), year.end());
        }
        Matcher mid = startingAt(MID_MONTH, t, from);
        if (mid != null) {
            Year year = year(t, mid.end(), today);
            return read(eve(before, partOfNamedMonth("mid", month(mid.group(1)), year, today), today), year.end());
        }
        Matcher yearEnd = startingAt(YEAR_END, t, from);
        if (yearEnd != null) {
            Year year = year(t, yearEnd.end(), today);
            return read(yearEnd(yearEnd, year, today).orElse(null), year.end());
        }
        Matcher part = startingAt(PART_OF_MONTH, t, from);
        if (part != null) {
            Year year = part.group(3) != null ? year(t, part.end(), today) : Year.none(part.end());
            Optional<LocalDate> date = partOfMonth(part, year, today);
            return read(part.group(1).equals("end") ? date.orElse(null) : eve(before, date, today), year.end());
        }
        Optional<Reading> ofWeek = weekPhraseAt(t, from, today, before);
        if (ofWeek.isPresent()) {
            return ofWeek;
        }
        Matcher endOfPeriod = startingAt(END_OF_PERIOD, t, from);
        if (endOfPeriod != null) {
            return read(endOfPeriod.group(1).equals("month") ? today.with(TemporalAdjusters.lastDayOfMonth())
                    : thisWeekend(today), endOfPeriod.end());
        }
        if (before) {
            Optional<Reading> eve = beforeDay(t, from, today);
            if (eve.isPresent()) {
                return eve;
            }
        }
        Matcher plainMonth = startingAt(PLAIN_MONTH, t, from);
        if (MONTH_PREPOSITIONS.contains(preposition) && plainMonth != null) {
            Year year = year(t, plainMonth.end(), today);
            if (year.unreadable()) {
                return read(null, year.end());
            }
            LocalDate first = occurrence(month(plainMonth.group(1)), year.value(), today);
            // "by March" gives all of March; "before March" ends with February, next year's once this one has
            // passed ("before November" said in November).
            if (!before) {
                return read(first.with(TemporalAdjusters.lastDayOfMonth()), year.end());
            }
            return read(year.value() == null && first.minusDays(1).isBefore(today)
                    ? first.plusYears(1).minusDays(1) : first.minusDays(1), year.end());
        }
        Matcher holiday = startingAt(HOLIDAY, t, from);
        if (holiday != null) {
            Year year = year(t, holiday.end(), today);
            if (year.unreadable()) {
                return read(null, year.end());
            }
            if (year.value() != null) {
                LocalDate day = holiday(holiday.group(1), year.value());
                return read(before ? day.minusDays(1) : day, year.end());
            }
            LocalDate day = holiday(holiday.group(1), today);
            if (!before) {
                return read(day, year.end());
            }
            // "before Christmas" said on Christmas Day is next year's Christmas Eve.
            return read(day.minusDays(1).isBefore(today)
                    ? holiday(holiday.group(1), today.plusDays(1)).minusDays(1) : day.minusDays(1), year.end());
        }
        return Optional.empty();
    }

    /**
     * After "before", a day or period whose eve the words name: "before tonight" and "before tomorrow" are today,
     * "before the day after tomorrow" tomorrow, "before Friday" the day before the weekday (see
     * {@link #beforeWeekday}), "before next week" this Sunday, "before next month" this month's last day, "before next
     * year" December 31, "before the weekend" this week's Friday and "before next weekend" next week's. "Before today"
     * names no date, and neither does a day that has passed ("before the weekend" said on a Saturday). The words
     * start at {@code from} in {@code t}.
     */
    private static Optional<Reading> beforeDay(String t, int from, LocalDate today) {
        Matcher m = startingAt(BEFORE_NOW, t, from);
        if (m != null) {
            return read(null, m.end());
        }
        m = startingAt(BEFORE_TONIGHT, t, from);
        if (m != null) {
            return read(today, m.end());
        }
        m = startingAt(BEFORE_TOMORROW, t, from);
        if (m != null) {
            return read(m.group(1) == null ? today : today.plusDays(1), m.end());
        }
        m = startingAt(BEFORE_WEEKDAY, t, from);
        if (m != null) {
            return read(beforeWeekday(m.group(1), weekday(m.group(2)), today), m.end());
        }
        m = startingAt(BEFORE_NEXT_WEEKEND, t, from);
        if (m != null) {
            return read(nextWeekend(today).minusDays(2), m.end());
        }
        m = startingAt(BEFORE_NEXT_PERIOD, t, from);
        if (m != null) {
            LocalDate date = switch (m.group(1)) {
                case "week" -> weekOf(today, 1, DayOfWeek.MONDAY).minusDays(1);
                case "month" -> today.with(TemporalAdjusters.lastDayOfMonth());
                default -> LocalDate.of(today.getYear(), 12, 31);
            };
            return read(date, m.end());
        }
        m = startingAt(BEFORE_WEEKEND, t, from);
        if (m != null) {
            return read(notPast(thisWeekend(today).minusDays(2), today), m.end());
        }
        return Optional.empty();
    }

    /** The day of a fixed holiday, the next one on or after today. */
    private static LocalDate holiday(String name, LocalDate today) {
        LocalDate date = holiday(name, today.getYear());
        return date.isBefore(today) ? holiday(name, today.getYear() + 1) : date;
    }

    /** The day of a fixed holiday in {@code year} ("Christmas 2027"). */
    private static LocalDate holiday(String name, int year) {
        String n = name.replace("'", "");
        switch (n) {
            case "thanksgiving", "thanksgiving day" -> {
                return thanksgiving(year);
            }
            case "black friday" -> {
                return thanksgiving(year).plusDays(1);
            }
            case "cyber monday" -> {
                return thanksgiving(year).plusDays(4);
            }
            case "easter", "easter day", "easter sunday" -> {
                return easter(year);
            }
            case "easter monday" -> {
                return easter(year).plusDays(1);
            }
            case "good friday" -> {
                return easter(year).minusDays(2);
            }
            default -> {
                // a fixed holiday
            }
        }
        MonthDay day = switch (n) {
            case "christmas eve", "xmas eve" -> MonthDay.of(12, 24);
            case "christmas", "christmas day", "xmas", "xmas day" -> MonthDay.of(12, 25);
            case "new years eve" -> MonthDay.of(12, 31);
            case "new years day", "new years", "new year" -> MonthDay.of(1, 1);
            case "halloween" -> MonthDay.of(10, 31);
            default -> MonthDay.of(2, 14); // Valentine's Day
        };
        return day.atYear(year);
    }

    /** Thanksgiving in the United States: the fourth Thursday of November. */
    private static LocalDate thanksgiving(int year) {
        return LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY));
    }

    /** Easter Sunday in the Gregorian calendar (the anonymous Gregorian computus). */
    static LocalDate easter(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = (h + l - 7 * m + 114) % 31 + 1;
        return LocalDate.of(year, month, day);
    }

    /**
     * The first day of the month named, in {@code year} when given, else this year's when the month has not ended
     * yet, else next year's.
     */
    private static LocalDate occurrence(Month month, Integer year, LocalDate today) {
        if (year != null) {
            return LocalDate.of(year, month, 1);
        }
        int y = month.getValue() >= today.getMonthValue() ? today.getYear() : today.getYear() + 1;
        return LocalDate.of(y, month, 1);
    }

    /**
     * The last day of the month named, in the year said or as {@link #occurrence} picks it; empty for an unreadable
     * year.
     */
    private static Optional<LocalDate> monthEnd(Month month, Year year, LocalDate today) {
        return year.unreadable() ? Optional.empty()
                : Optional.of(occurrence(month, year.value(), today).with(TemporalAdjusters.lastDayOfMonth()));
    }

    /**
     * "the 5th of next month" is day 5 of next month; "of this month" this month's; "of the month" the next one. A
     * day the month lacks is its last day ("the 31st of next month" in a month of 30 days is the 30th).
     */
    private static Optional<LocalDate> nthOfMonth(Matcher m, LocalDate today) {
        int day = Integer.parseInt(m.group(1));
        String which = m.group(2).replaceAll("\\s+", " ");
        if (which.equals("the")) {
            return dayOfMonth(day, today);
        }
        LocalDate month = (which.equals("this") ? today : today.plusMonths(1)).withDayOfMonth(1);
        return day >= 1 && day <= 31 ? Optional.of(month.withDayOfMonth(Math.min(day, month.lengthOfMonth())))
                : Optional.empty();
    }

    /**
     * "Friday the 27th": the next 27th on or after today, when it falls on a Friday. When it does not, the words
     * disagree with the calendar and name no date: the reading never moves to a later month to find a Friday.
     */
    private static Optional<LocalDate> weekdayDay(Matcher m, LocalDate today) {
        DayOfWeek weekday = weekday(m.group(1));
        int day = Integer.parseInt(m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4));
        return dayOfMonth(day, today).filter(date -> date.getDayOfWeek() == weekday);
    }

    /**
     * "Wednesday 3rd March 2027", "Friday, 27 November": the date of the day and month (in the year said, else the
     * next one on or after today), when it falls on that weekday; no date when it does not.
     */
    private static Optional<LocalDate> weekdayDate(Matcher m, Year year, LocalDate today) {
        boolean dayFirst = m.group(2) != null;
        Month month = month(dayFirst ? m.group(3) : m.group(4));
        int day = Integer.parseInt(dayFirst ? m.group(2) : m.group(5));
        DayOfWeek weekday = weekday(m.group(1));
        return monthDay(month, day, year, today).filter(date -> date.getDayOfWeek() == weekday);
    }

    /**
     * "the first Monday of December", "the second Tuesday of next month", "the last Friday of the month". Of "the" or
     * "this" month it is this month's, or the next month's that has one once this month's has passed; of a named
     * month this year's (or the year given), next year's once it has passed. Empty when that month has no such day
     * ("the fifth Monday of next month" in a month with four).
     */
    private static Optional<LocalDate> nthWeekdayOf(Matcher m, Year said, LocalDate today) {
        if (said.unreadable()) {
            return Optional.empty();
        }
        int n = switch (m.group(1).substring(0, 3)) {
            case "fir", "1st" -> 1;
            case "sec", "2nd" -> 2;
            case "thi", "3rd" -> 3;
            case "fou", "4th" -> 4;
            case "fif", "5th" -> 5;
            default -> -1;
        };
        DayOfWeek weekday = weekday(m.group(2));
        if (m.group(5) != null) {
            Integer year = said.value();
            LocalDate month = occurrence(month(m.group(5)), year, today);
            Optional<LocalDate> day = nthInMonth(month, n, weekday);
            if (year != null || day.isPresent() && !day.get().isBefore(today)) {
                return day;
            }
            return nthInMonth(month.plusYears(1), n, weekday);
        }
        if (m.group(4) != null) {
            return nthInMonth(today.plusMonths(1).withDayOfMonth(1), n, weekday);
        }
        LocalDate month = today.withDayOfMonth(1);
        for (int i = 0; i < 12; i++, month = month.plusMonths(1)) {
            Optional<LocalDate> day = nthInMonth(month, n, weekday);
            if (day.isPresent() && !day.get().isBefore(today)) {
                return day;
            }
        }
        return Optional.empty();
    }

    /**
     * The {@code n}th {@code weekday} of the month that starts at {@code first} ({@code -1} the last), if it has one.
     */
    private static Optional<LocalDate> nthInMonth(LocalDate first, int n, DayOfWeek weekday) {
        if (n < 0) {
            return Optional.of(first.with(TemporalAdjusters.lastInMonth(weekday)));
        }
        LocalDate day = first.with(TemporalAdjusters.dayOfWeekInMonth(n, weekday));
        return day.getMonth() == first.getMonth() ? Optional.of(day) : Optional.empty();
    }

    /**
     * "the first day of next month" (its 1st), "the last day of February" (its last day), "the 10th day of the month"
     * (the next 10th). A named month is read as in "February 10"; a day the month lacks is no date.
     */
    private static Optional<LocalDate> nthDayOf(Matcher m, Year year, LocalDate today) {
        String nth = m.group(1);
        boolean last = nth.equals("last");
        int day = last ? 0 : ordinalValue(nth);
        if (m.group(4) != null) {
            Month month = month(m.group(4));
            return last ? monthEnd(month, year, today) : monthDay(month, day, year, today);
        }
        if (m.group(3) != null) {
            LocalDate next = today.plusMonths(1).withDayOfMonth(1);
            return last ? Optional.of(next.with(TemporalAdjusters.lastDayOfMonth()))
                    : day >= 1 && day <= next.lengthOfMonth() ? Optional.of(next.withDayOfMonth(day)) : Optional.empty();
        }
        if (last) {
            return Optional.of(today.with(TemporalAdjusters.lastDayOfMonth()));
        }
        return "this".equals(m.group(2)) ? thisMonthsDay(day, today) : dayOfMonth(day, today);
    }

    private static Optional<LocalDate> thisMonthsDay(int day, LocalDate today) {
        return day >= 1 && day <= today.lengthOfMonth() ? Optional.of(today.withDayOfMonth(day)) : Optional.empty();
    }

    /** The years a deadline may have. */
    private static final int MIN_YEAR = 2000;
    private static final int MAX_YEAR = 2200;
    /** A year right after a date: "2028", ", 2028", "of 2027", "in 2027", "'27", "next year", "of this year". */
    private static final Pattern YEAR = Pattern.compile("^,?\\s*(?:(?:of|in)\\s+)?(?:(\\d{4})|'(\\d{2})"
            + "|(next|this|last)\\s+year)\\b");

    /**
     * A year said right after a date (see {@link #year}): its value (null when none is said), where its words end,
     * and whether the words name a year no deadline may have ("1999", "last year").
     */
    private record Year(Integer value, int end, boolean unreadable) {
        static Year none(int at) {
            return new Year(null, at, false);
        }
    }

    /**
     * The year said right after a date that ends at {@code after}: "June 1 2028", "1 June, 2028", "December of 2027",
     * "Dec 5 '27" (2027), "Christmas next year", "December this year". A date with an unreadable year names no date.
     * Where the year's words end is an index into {@code text}.
     */
    private static Year year(String text, int after, LocalDate today) {
        Matcher m = startingAt(YEAR, text, after);
        if (m == null) {
            return Year.none(after);
        }
        int end = m.end();
        if (m.group(1) != null) {
            int y = Integer.parseInt(m.group(1));
            return y >= MIN_YEAR && y <= MAX_YEAR ? new Year(y, end, false) : new Year(null, end, true);
        }
        if (m.group(2) != null) {
            return new Year(2000 + Integer.parseInt(m.group(2)), end, false);
        }
        return switch (m.group(3)) {
            case "next" -> new Year(today.getYear() + 1, end, false);
            case "this" -> new Year(today.getYear(), end, false);
            default -> new Year(null, end, true);
        };
    }

    /** {@link #monthDay(Month, int, Integer, LocalDate)} with a said year; empty for an unreadable one. */
    private static Optional<LocalDate> monthDay(Month month, int day, Year year, LocalDate today) {
        return year.unreadable() ? Optional.empty() : monthDay(month, day, year.value(), today);
    }

    private static Optional<LocalDate> monthDay(Month month, int day, Integer year, LocalDate today) {
        if (day < 1 || day > month.maxLength()) {
            return Optional.empty();
        }
        if (year != null) {
            return day <= month.length(java.time.Year.isLeap(year)) ? Optional.of(LocalDate.of(year, month, day))
                    : Optional.empty();
        }
        // The next occurrence on or after today; Feb 29 is the next leap year's.
        for (int y = today.getYear(); y <= today.getYear() + 8; y++) {
            if (day <= month.length(java.time.Year.isLeap(y))) {
                LocalDate candidate = LocalDate.of(y, month, day);
                if (!candidate.isBefore(today)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    /** The next date on or after today that has this day of the month ("the 31st" skips 30-day months). */
    private static Optional<LocalDate> dayOfMonth(int day, LocalDate today) {
        if (day < 1 || day > 31) {
            return Optional.empty();
        }
        LocalDate month = today.withDayOfMonth(1);
        for (int i = 0; i < 12; i++, month = month.plusMonths(1)) {
            if (day <= month.lengthOfMonth()) {
                LocalDate candidate = month.withDayOfMonth(day);
                if (!candidate.isBefore(today)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private static final List<String> ORDINAL_NAMES = List.of("first", "second", "third", "fourth", "fifth", "sixth",
            "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth",
            "sixteenth", "seventeenth", "eighteenth", "nineteenth", "twentieth");

    /** The number an ordinal names: "1st" is 1, "third" 3, "twenty-first" 21, "thirtieth" 30. */
    private static int ordinalValue(String ordinal) {
        String o = ordinal.strip();
        if (Character.isDigit(o.charAt(0))) {
            return Integer.parseInt(o.replaceAll("\\D", ""));
        }
        if (o.equals("thirtieth")) {
            return 30;
        }
        String[] parts = o.split("[- ]");
        if (parts.length == 2) {
            return (parts[0].equals("twenty") ? 20 : 30) + ORDINAL_NAMES.indexOf(parts[1]) + 1;
        }
        return ORDINAL_NAMES.indexOf(o) + 1;
    }

    /** "1st", "2nd", "3rd", "4th", "11th", "21st": a day of the month as digits. */
    private static String digitOrdinal(int n) {
        String suffix = n % 100 >= 11 && n % 100 <= 13 ? "th"
                : switch (n % 10) {
                    case 1 -> "st";
                    case 2 -> "nd";
                    case 3 -> "rd";
                    default -> "th";
                };
        return n + suffix;
    }

    private static int number(String s) {
        return switch (s) {
            case "a", "an", "one" -> 1;
            case "two", "a couple of", "a couple" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            case "eleven" -> 11;
            case "twelve" -> 12;
            default -> Integer.parseInt(s);
        };
    }

    private static DayOfWeek weekday(String s) {
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().toLowerCase(Locale.ROOT).startsWith(s.substring(0, 3))) {
                return d;
            }
        }
        throw new IllegalArgumentException(s);
    }

    private static Month month(String s) {
        for (Month m : Month.values()) {
            if (m.name().toLowerCase(Locale.ROOT).startsWith(s.substring(0, 3))) {
                return m;
            }
        }
        throw new IllegalArgumentException(s);
    }
}
