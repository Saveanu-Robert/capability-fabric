package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.algorithms.DateExpressions;
import dev.capabilityfabric.algorithms.Durations;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the date an action is aimed at from the person's own words; models are unreliable at date arithmetic.
 * The order is always the same: an explicit target ("by Friday", "due April 12", "to Monday"), then a move relative
 * to the current date ("push it back a week"), then any date in the words that is not ruled out, then the model's
 * date. A date the words rule out ("not Friday", "Friday doesn't work", "I'm busy on Monday", "Friday's taken", "any
 * day but Friday") is never used, not even when the model proposes it or the words seem to name it; every date of a
 * list ruled out together counts ("Friday and Saturday are both out", "not Thursday, Friday or Saturday", "neither
 * Friday nor Monday", "I'm away Friday to Monday"). An exclusion ("Monday rather than Friday", "instead of Friday
 * move it to Monday") rules out only the dates it names, and a negation ends before "until" ("not due until Friday"
 * names Friday); being away or busy until a date rules out the days before it, not the date ("I'm away until Friday").
 * Days named in the plural rule out every such day ("Fridays are out", "I can't do weekends"), and a week every day of
 * it ("busy all of next week"). A self-correction replaces what came before it ("Friday, no wait, Monday" is Monday).
 */
final class ActionDates {

    private ActionDates() {
    }

    /** Where the date of a {@link Reading} came from. */
    enum Source {
        /** Read from the person's words. */
        WORDS,
        /** The words hold no date at all; the model's date is a guess the words do not support. */
        MODEL,
        /**
         * The words name a date the kernel cannot work out ("next spring"): the model's reading of it is the only
         * date there is, kept with a note for a new goal or task whose words hold a deadline phrase ("by next
         * spring") and asked about for a move.
         */
        MODEL_READING,
        /** Neither the words nor the model give a date. */
        NONE,
        /**
         * The date chosen, the one read from the words or else the model's, is one the words also rule out ("next
         * Friday, not this Friday" when both name the same day); there is no date.
         */
        RULED_OUT
    }

    /**
     * A date for an action and where it came from; {@code date} is null for {@link Source#NONE} and RULED_OUT.
     * {@code proposed} is the model's date, and for RULED_OUT the date that was chosen and is ruled out.
     */
    record Reading(LocalDate date, Source source, LocalDate proposed) {
    }

    /**
     * Right after an amount ("a week before Christmas", "a week on Friday") or "the" and a day or a period ("the
     * Friday before Christmas", "the weekend before Christmas", "the week before Easter", "the month before the
     * move", "the year before"), with or without a word of closeness between ("the Friday just before Christmas", "the
     * Monday right before Easter", "the day immediately before"), "before" and "on" are no marker: the phrase counts
     * from its anchor and is read whole, as the date parser reads it.
     */
    private static final String NOT_AFTER_AMOUNT = "(?<!\\b(?:days?|weeks?|fortnights?|months?)\\s{1,3})"
            + "(?<!\\b(?:days?|weeks?|fortnights?|months?)\\s{1,3}(?:just|right|immediately|directly|straight)\\s{1,3})"
            + "(?<!\\bthe\\s{1,3}(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|day|weekend|week-end"
            + "|week|fortnight|month|year)\\s{1,3})"
            + "(?<!\\bthe\\s{1,3}(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|day|weekend|week-end"
            + "|week|fortnight|month|year)\\s{1,3}(?:just|right|immediately|directly|straight)\\s{1,3})";
    /** Words that put a date in place as a deadline. */
    private static final Pattern DEADLINE = Pattern.compile("\\b(?:by|until|till|due)\\b|" + NOT_AFTER_AMOUNT
            + "\\bbefore\\b");
    /** "to" names where something moves. */
    private static final Pattern TO = Pattern.compile("\\bto\\b");
    /** Words that may put a date in place, but also say when something happens. */
    private static final Pattern WEAK = Pattern.compile(NOT_AFTER_AMOUNT + "\\b(?:on|for)\\b");
    /**
     * "Is", "are", "was", "were", "'s" or "'re" right before a weak marker: "the birthday is on the 25th" states when
     * something is, so another weak marker later in the words ("plan the party for the 24th") is the target.
     */
    private static final Pattern STATED = Pattern.compile("(?:\\b(?:is|are|was|were)|'s|'re)\\s+$");
    /** The clause end found where a clause stops (see {@link DateExpressions#clauseEnd}): punctuation or a word. */
    private static final Pattern SEPARATOR = Pattern.compile("\\s*(?:[,.;:!?]|[-\\u2013\\u2014]{1,2}"
            + "|(?i:because|since|as|but|and|so|or|although|though|while)\\b)");
    /**
     * Words that say a day does not work, whatever their place in the clause: "busy", "unavailable", being away, off
     * or out ("I'm away on Friday", "I'm off on Friday"), and a day that "is out", "'s taken", "are both booked", "is
     * too soon", in the words the date parser rules dates out with ({@link DateExpressions#RULED_OUT_STATE}), so
     * both read the same words the same way.
     */
    private static final Pattern RULES_OUT_STATE = Pattern.compile("(?i)\\b(?:busy|unavailable)\\b"
            + "|\\b(?:i'?m|i am|we'?re|we are|be|being)\\s+(?:away|off(?!\\s+to\\b)|out)\\b|"
            + DateExpressions.RULED_OUT_STATE);
    /** "Nothing on Friday please" rules Friday out as "not on Friday" does. */
    private static final Pattern NOTHING = Pattern.compile("(?i)\\bnothing\\b");
    /** "Neither Friday nor Monday" excludes both, as "not Friday or Monday" does. */
    private static final Pattern NEITHER = Pattern.compile("(?i)\\bneither\\b");
    /**
     * Words that rule out the date of their clause: every negation and exclusion, "nothing" ("nothing on Friday"),
     * "neither" and the words of {@link #RULES_OUT_STATE}.
     */
    private static final Pattern RULES_OUT = Pattern.compile(TargetCheck.NEGATED.pattern() + "|"
            + NOTHING.pattern() + "|" + NEITHER.pattern() + "|" + RULES_OUT_STATE.pattern());
    /** The negations among {@link #RULES_OUT}: a negated part ends before "until" or "till". */
    private static final Pattern NEGATION = Pattern.compile("(?i)" + TargetCheck.NEGATION);
    /** "Not due until Friday", "don't need it until December 4": the date after "until" is the one meant. */
    private static final Pattern UNTIL = Pattern.compile("(?i)\\b(?:until|till)\\b");
    /**
     * Words that, right before "not", "no" or "never", make it negate a verb ("Friday is not good", "Monday would
     * not work"): the date before the negation is its subject and is ruled out with it.
     */
    private static final Pattern AUXILIARY = Pattern.compile("(?i)(?:\\b(?:is|are|was|were|am|be|been|do|does|did"
            + "|will|would|can|could|should|shall|may|might|must|has|have|had)|'s|'re|'m)\\s+$");
    /** A word that names a day, for telling whether an exclusion has a date after it. */
    private static final Pattern DAY_WORD = Pattern.compile("(?i)\\d|\\b(?:monday|tuesday|wednesday|thursday"
            + "|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|january|february|march|april|may"
            + "|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov"
            + "|dec|today|tomorrow|tonight|weekend|christmas|xmas|easter|halloween)\\b");
    /** "Any day but Friday" excludes Friday as "except" does. */
    private static final Pattern ANY_BUT = Pattern.compile(
            "(?i)\\b(any\\s*(?:day|time|date|other day)|anything|anytime|whenever|all)\\s+but\\b");
    /** "c'mon" is "come on", never Monday. */
    private static final Pattern COME_ON = Pattern.compile("(?i)\\bc'?mon\\b");
    /** A word of a list of dates: a weekday, a month, a day of the month or a word that only qualifies one. */
    private static final Pattern DATE_TOKEN = Pattern.compile("(?:(?:monday|tuesday|wednesday|thursday|friday"
            + "|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)s?|(?:january|february|march|april|may"
            + "|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)"
            + "|today|tomorrow|tonight|weekend|weekends|\\d{1,2}(?:st|nd|rd|th)?)");
    private static final Set<String> LIST_FILLER = Set.of("the", "on", "next", "this", "either", "both", "neither",
            "of", "or", "nor", "and", "morning", "afternoon", "evening", "week", "also", "then", "too");
    /** What joins the dates of a list inside a clause: "&", "/" between words, "and", "or", "nor". */
    private static final Pattern LIST_SPLIT = Pattern.compile("\\s*(?:&|(?<=[a-z])\\s*/\\s*(?=[a-z])"
            + "|\\b(?:and|or|nor|neither|either|both)\\b)\\s*");
    /** A word of the words, or "&" or "/", for walking along a list of dates. */
    private static final Pattern TOKEN = Pattern.compile("[a-z0-9']+|[&/]");
    /** Words that join list items without being dates themselves. */
    private static final Set<String> JOINERS = Set.of("and", "or", "nor", "&", "/");
    /** Clause ends a list of dates continues across. */
    private static final Set<String> LIST_SEPARATORS = Set.of("and", "or", "nor");
    /**
     * "Friday to Monday", "from the 20th to the 23rd", "from the 28th until the 2nd": every day from the first date to
     * the second.
     */
    private static final Pattern SPAN = Pattern.compile("\\b(?:from\\s+)?((?:(?:this|next|the)\\s+)?[a-z0-9]+)"
            + "\\s+(?:to|through|thru|until|till)\\s+((?:(?:this|next|the)\\s+)?[a-z0-9]+)");
    /** The longest span of days that is read as every day in it. */
    private static final int MAX_SPAN_DAYS = 31;

    /**
     * How many matches of one marker pattern a reader looks at in one text. A person names a date with one or two
     * markers; a text of many markers in a row ("by by by ...") is read only this far. With {@link #MAX_CLAUSES} this
     * bounds every reader of this class to a fixed number of passes over the text, so its time grows with the length
     * of the text, not with its square.
     */
    static final int MAX_MARKERS = 8;
    /**
     * How many clauses a text is cut into (see {@link #clauses}): finding where a clause ends reads the rest of the
     * text, so the clauses after this many are read as one last clause. A person's sentence has a handful of clauses;
     * a text of thousands of commas is cut this far and no further.
     */
    static final int MAX_CLAUSES = 48;
    /**
     * How many characters after a marker are handed to the date parser as that marker's phrase, and how far before a
     * marker its own clause is searched for a negation or a state: a date phrase is a few words long.
     */
    static final int WINDOW = 200;

    /**
     * The date for an action, from {@code words} (the sentence, or the action's own clause of it).
     *
     * @param from     the date being changed, for relative moves; null when there is none
     * @param proposed the model's date, used only when the words give none and do not rule it out
     * @param move     the action moves an existing date (then "to" is the strongest marker); otherwise it sets a
     *                 deadline (then "by", "until" and "due" are)
     */
    static LocalDate resolve(String words, LocalDate from, LocalDate today, LocalDate proposed, boolean move) {
        return read(words, from, today, proposed, move).date();
    }

    /**
     * {@link #resolve} with where the date came from. A relative move is read from the whole words, which the date
     * parser reads with their negations and self-corrections ("don't push it back a week, just two days" moves two
     * days back, and so does "push it back a week, no, two days"); everything else from the words after the last
     * self-correction ({@link DateExpressions#corrected}).
     */
    static Reading read(String words, LocalDate from, LocalDate today, LocalDate proposed, boolean move) {
        String plain = plain(words);
        String spoken = spoken(words);
        String kept = withoutRuledOut(plain);
        Optional<LocalDate> date = target(plain, kept, today, move)
                .or(() -> DateExpressions.shift(spoken, from))
                .or(() -> DateExpressions.find(kept, today));
        Set<LocalDate> ruledOut = ruledOut(plain, from, today);
        if (date.isPresent()) {
            // Words that both name and rule out a day ("next Friday, not this Friday" read as the same Friday) do not
            // say which date is meant.
            return ruledOut.contains(date.get()) ? new Reading(null, Source.RULED_OUT, date.get())
                    : new Reading(date.get(), Source.WORDS, proposed);
        }
        if (proposed == null) {
            return new Reading(null, Source.NONE, null);
        }
        if (ruledOut.contains(proposed)) {
            return new Reading(null, Source.RULED_OUT, proposed);
        }
        // Only a date the words do not rule out makes the model's date a reading of them.
        return new Reading(proposed, statesDate(kept, from, today) ? Source.MODEL_READING : Source.MODEL, proposed);
    }

    /**
     * The words as the date parser reads them (see {@link #spoken}), with everything before the last self-correction
     * blanked ("move it to Friday, no wait, Monday" keeps "Monday", see {@link DateExpressions#corrected}).
     */
    private static String plain(String words) {
        return DateExpressions.corrected(spoken(words));
    }

    /**
     * The words with straight apostrophes, "c'mon" as "come on", "any day but" as "except", and, when the words name
     * "this Friday", their "next Friday" as the Friday after it (see {@link DateExpressions#contrastNext}). The
     * contrast is made on the whole sentence before it is cut into clauses, so "move it to next Friday, not this
     * Friday" sees both. Every character keeps its place except where these phrases are rewritten.
     */
    private static String spoken(String words) {
        if (words == null) {
            return null;
        }
        String t = COME_ON.matcher(TargetCheck.straightQuotes(words)).replaceAll("come on");
        return DateExpressions.contrastNext(joinedDaySpans(ANY_BUT.matcher(t).replaceAll("$1 except")));
    }

    /** A dash with spaces around it between two day names ("Fri - Sun", "Friday - Sunday"), short or long. */
    private static final Pattern SPACED_DAY_DASH = Pattern.compile("(?i)\\b((?:mon|tue|wed|thu|fri|sat|sun)[a-z]*)"
            + "(\\s+)([-\\u2013\\u2014]{1,2})(\\s+)(?=(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b)");

    /**
     * The words with a dash between spaces that joins two day names moved next to the second day ("Fri - Sun" becomes
     * "Fri  -Sun"), so the span stays in one clause: a dash between spaces otherwise ends a clause. Every character
     * keeps its place.
     */
    static String joinedDaySpans(String words) {
        Matcher m = SPACED_DAY_DASH.matcher(words);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String spaces = " ".repeat(m.group(2).length() + m.group(4).length());
            m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + spaces + m.group(3)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * The date after a target marker, read from the first date phrase of the marker's clause (see
     * {@link #firstPhrase}: "to Friday for the party on the 4th" is Friday), so an explicit "due Friday" beats an
     * incidental "today" elsewhere in the sentence. Markers are tried strongest first, each in sentence order. A
     * marker is skipped when its clause rules its date out: a negation before it ("I don't want it due Friday",
     * though not one that ends at an "until" before the marker: "it's not due until Friday" is Friday), or a negation
     * after its date with no other date ("due Friday is not possible"). A negation that names another date excludes
     * that one instead ("due Friday rather than Monday" is Friday). "Before" and "on" right after an amount or "the
     * Friday" are no markers ("a week before Christmas"), and neither is an "on" or "for" right after "is" or "are"
     * when another one follows ("my birthday is on the 25th, plan the party for the 24th" is the 24th). Only the
     * words after a self-correction count ("to Friday, no wait, Monday", see {@link DateExpressions#corrected}). At
     * most {@link #MAX_MARKERS} markers of each kind are tried, each with at most {@link #WINDOW} characters of its
     * clause.
     */
    static Optional<LocalDate> target(String words, LocalDate today, boolean move) {
        return target(words, null, today, move);
    }

    /**
     * {@link #target(String, LocalDate, boolean)} that also skips a marker standing in a part of the words that
     * {@code kept} (the words as {@link #withoutRuledOut} leaves them, same length) blanks: the "on" of "I'm busy on
     * Friday and on Monday" is in a list ruled out with its clause.
     */
    private static Optional<LocalDate> target(String words, String kept, LocalDate today, boolean move) {
        if (words == null) {
            return Optional.empty();
        }
        String t = TargetCheck.lowerSameLength(TargetCheck.straightQuotes(DateExpressions.corrected(words)));
        List<Pattern> order = move ? List.of(TO, DEADLINE, WEAK) : List.of(DEADLINE, TO, WEAK);
        for (Pattern marker : order) {
            Matcher m = marker.matcher(t);
            int tried = 0;
            while (tried++ < MAX_MARKERS && m.find()) {
                if (kept != null && kept.length() == t.length() && kept.charAt(m.start()) == ' '
                        && t.charAt(m.start()) != ' ') {
                    continue; // a marker inside a ruled-out part
                }
                if (marker == WEAK && endsWith(STATED, t, m.start()) && WEAK.matcher(t).find(m.end())) {
                    continue; // "my birthday is on the 25th, plan the party for the 24th": the 25th is a fact
                }
                if (negatedBefore(t, Math.max(clauseStart(t, m.start()), m.start() - WINDOW), m.start(), m.end())) {
                    continue; // "not by Friday", "I don't want it due Friday", "unable to"
                }
                String clause = t.substring(m.start(), Math.min(clauseEnd(t, m.end()), m.end() + WINDOW));
                Matcher negation = RULES_OUT.matcher(clause);
                if (negation.find()) {
                    if (DateExpressions.find(clause.substring(negation.end()), today).isEmpty()) {
                        continue;
                    }
                    clause = clause.substring(0, negation.start());
                }
                Optional<LocalDate> date = DateExpressions.findTarget(firstPhrase(clause), today);
                if (date.isPresent()) {
                    return date;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The words of a marker's clause up to its second date phrase (see {@link DateExpressions#datePhraseSpans}): the
     * date a marker puts in place is the phrase right after it, so "to Friday for the party on the 4th" is read as "to
     * Friday", never as the 4th. A clause with one date phrase is kept whole. Such words are never simple either (see
     * {@link #simple}): they name more than one date.
     */
    private static String firstPhrase(String clause) {
        List<DateExpressions.Span> spans = DateExpressions.datePhraseSpans(clause);
        return spans.size() < 2 ? clause : clause.substring(0, spans.get(1).start());
    }

    /**
     * True when a word before the marker at {@code markerStart} (in its clause, which starts at {@code from}) rules
     * the marker's date out. A negation stops covering at the first "until" or "till" after it, so the marker of "not
     * due until Friday" that is "until" is not covered, unless "from" or a date stands between the negation and that
     * "until": "can't do Friday till Sunday" and "not available from the 28th until the 2nd" rule out the whole span.
     */
    private static boolean negatedBefore(String t, int from, int markerStart, int markerEnd) {
        Matcher out = RULES_OUT.matcher(t).region(from, markerEnd);
        while (out.find()) {
            if (!NEGATION.matcher(out.group()).matches()) {
                return true;
            }
            Matcher until = UNTIL.matcher(t).region(out.end(), markerEnd);
            if (!until.find() || until.start() > markerStart || spanBefore(t, out.end(), until.start())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code ending}, a pattern anchored at its end ("is on" before a marker), matches the text that ends at
     * {@code at}, read at most {@link #WINDOW} characters back; word boundaries see the text around that window.
     */
    private static boolean endsWith(Pattern ending, String t, int at) {
        return ending.matcher(t).region(Math.max(0, at - WINDOW), at).useTransparentBounds(true).find();
    }

    /** True when "from" or a date stands in {@code [from, to)}, so an "until" at {@code to} closes a span. */
    private static boolean spanBefore(String lower, int from, int to) {
        return FROM.matcher(lower).region(from, to).find() || DAY_WORD.matcher(lower).region(from, to).find();
    }

    /** One clause of a sentence: where it starts and ends, and the clause end that follows it (empty at the end). */
    private record Clause(int start, int end, String separator) {
    }

    /**
     * The part {@code [from, to)} of the words whose dates are ruled out; empty when {@code from >= to}. {@code until}
     * is where the words after the "until" of "I'm away until Friday" start (-1 for none): every day before that date
     * is ruled out, the date itself is not.
     */
    private record Range(int from, int to, int until) {

        Range(int from, int to) {
            this(from, to, -1);
        }

        boolean whole(Clause c) {
            return from <= c.start() && to >= c.end();
        }

        boolean reachesEnd(Clause c) {
            return from < to && to >= c.end();
        }
    }

    /**
     * Which part of each clause of the words (see {@link #clauses}) rules a date out. A clause with a negation or a
     * word that says a day does not work rules out all of it ("Friday doesn't work", "I'm busy on Monday"), except
     * that an exclusion with a date after it rules out only the dates it names ("Monday rather than Friday", "instead
     * of Friday move it to Monday", "Monday not Friday": "not" without a verb before it), and that a negation, or a
     * word that says a day does not work, ends before "until" or "till" ("not due until Friday", "I'm away until
     * Friday"; the days before such a date are ruled out apart, see {@link #ruledOut}) unless "from" or a date comes
     * between them ("busy from the 28th until the 2nd" is a span). Clauses that only list dates are ruled out with the
     * clause their list belongs to: those before a clause ruled out whole ("Thursday and Friday don't work", "Friday,
     * Saturday or Monday are out"), and those after one whose ruled-out part reaches its end, joined by "and", "or"
     * or "nor" ("I'm busy Friday and Monday", "not Thursday or Friday"), or by commas when the list goes on to such an
     * item ("not Thursday, Friday or Saturday"). A comma alone may start the date meant ("not Friday, Monday"), and so
     * may an item with "then" ("Friday is out and Monday then").
     */
    private static Range[] ruledOutClauses(String lower, List<Clause> clauses) {
        Range[] out = new Range[clauses.size()];
        for (int i = 0; i < clauses.size(); i++) {
            out[i] = ruledOutPart(lower, clauses.get(i));
        }
        for (int i = clauses.size() - 2; i >= 0; i--) {
            Clause c = clauses.get(i);
            String sep = c.separator().strip().toLowerCase(Locale.ROOT);
            if (empty(out[i]) && out[i + 1].whole(clauses.get(i + 1))
                    && (LIST_SEPARATORS.contains(sep) || sep.equals(",")) && onlyDates(text(lower, c))) {
                out[i] = new Range(c.start(), c.end());
            }
        }
        for (int i = 1; i < clauses.size(); i++) {
            Clause c = clauses.get(i);
            String sep = clauses.get(i - 1).separator().strip().toLowerCase(Locale.ROOT);
            if (!empty(out[i]) || !out[i - 1].reachesEnd(clauses.get(i - 1)) || !listItem(text(lower, c))) {
                continue;
            }
            if (LIST_SEPARATORS.contains(sep) || sep.equals(",") && listGoesOn(lower, clauses, i)) {
                out[i] = new Range(c.start(), c.end());
            }
        }
        return out;
    }

    private static boolean empty(Range r) {
        return r.from() >= r.to();
    }

    private static String text(String lower, Clause c) {
        return lower.substring(c.start(), c.end());
    }

    /** A clause that is an item of a list of dates ruled out: only dates, and no "then" that starts the date meant. */
    private static boolean listItem(String clause) {
        return onlyDates(clause) && !clause.matches("(?s).*\\bthen\\b.*");
    }

    /**
     * True when the comma-joined date items from clause {@code i} on end in an item joined by "and", "or" or "nor"
     * ("not Thursday, Friday or Saturday"), so the comma continues the list rather than starting the date meant.
     */
    private static boolean listGoesOn(String lower, List<Clause> clauses, int i) {
        int j = i;
        while (j + 1 < clauses.size() && clauses.get(j).separator().strip().equals(",")
                && listItem(text(lower, clauses.get(j + 1)))) {
            j++;
        }
        return j + 1 < clauses.size()
                && LIST_SEPARATORS.contains(clauses.get(j).separator().strip().toLowerCase(Locale.ROOT))
                && listItem(text(lower, clauses.get(j + 1)));
    }

    /** The part of one clause that its own words rule out (see {@link #ruledOutClauses}). */
    private static Range ruledOutPart(String lower, Clause c) {
        Matcher out = RULES_OUT.matcher(lower).region(c.start(), c.end());
        if (!out.find()) {
            return new Range(c.end(), c.end());
        }
        int from = c.start();
        int to = c.end();
        boolean negation = NEGATION.matcher(out.group()).matches();
        boolean verb = negation && (out.group().contains("n't") || !out.group().matches("(?i)not|no|never")
                || AUXILIARY.matcher(lower.substring(c.start(), out.start())).find());
        boolean exclusion = !negation && !RULES_OUT_STATE.matcher(out.group()).matches() || negation && !verb;
        Matcher day = DAY_WORD.matcher(lower).region(out.end(), c.end());
        if (exclusion && day.find()) {
            // "Monday rather than Friday", "instead of Friday move it to Monday": only Friday is ruled out
            from = out.start();
            to = listEnd(lower, day.start(), c.end());
        }
        boolean state = !negation && RULES_OUT_STATE.matcher(out.group()).matches();
        if (negation || state) {
            Matcher until = UNTIL.matcher(lower).region(out.end(), c.end());
            if (until.find() && !spanBefore(lower, out.end(), until.start())) {
                // "not due until Friday": Friday is the date meant; "I'm away until Friday": the days before Friday
                // are ruled out, Friday is not; "busy from the 28th until the 2nd", "away Friday until Monday" and
                // "can't do Friday till Sunday" are spans, read whole (see SPAN)
                return new Range(from, Math.min(to, until.start()), state ? until.end() : -1);
            }
        }
        return new Range(from, to);
    }

    /** "From" before "until" makes a span of two dates ("busy from the 28th until the 2nd"). */
    private static final Pattern FROM = Pattern.compile("\\bfrom\\b");

    /**
     * Where the dates an exclusion names end: after the last date word of the list that starts at {@code first},
     * which goes on over words that qualify dates ("the 20th", "next Friday") and over "and", "or", "nor", "&" and
     * "/" between dates ("neither Friday nor Monday"), and stops at the first other word ("instead of Friday move it
     * to Monday" stops before "move").
     */
    private static int listEnd(String lower, int first, int clauseEnd) {
        Matcher token = TOKEN.matcher(lower).region(first, clauseEnd);
        int end = first;
        while (token.find()) {
            String w = token.group();
            if (DATE_TOKEN.matcher(w).matches() || DAY_WORD.matcher(w).matches()) {
                end = token.end();
            } else if (!LIST_FILLER.contains(w) && !JOINERS.contains(w) || w.equals("then")) {
                break;
            }
        }
        return end;
    }

    /** True when a clause names at least one date and holds nothing else but words that only qualify dates. */
    private static boolean onlyDates(String clause) {
        boolean date = false;
        for (String w : clause.strip().split("[\\s']+")) {
            if (w.isEmpty() || LIST_FILLER.contains(w)) {
                continue;
            }
            if (!DATE_TOKEN.matcher(w).matches()) {
                return false;
            }
            date = true;
        }
        return date;
    }

    /**
     * The clauses of a text as the date parser cuts them ({@link DateExpressions#clauseEnd}): a comma inside a date
     * ("June 1, 2028", "Friday, June 2nd") and a full stop before a number ("Nov. 10") do not cut it. At most
     * {@link #MAX_CLAUSES} clauses: the rest of a longer text is its last clause.
     */
    private static List<Clause> clauses(String text) {
        List<Clause> clauses = new ArrayList<>();
        int from = 0;
        while (true) {
            int end = clauses.size() + 1 >= MAX_CLAUSES ? text.length()
                    : Math.max(from, DateExpressions.clauseEnd(text, from));
            if (end >= text.length()) {
                clauses.add(new Clause(from, text.length(), ""));
                return clauses;
            }
            Matcher separator = SEPARATOR.matcher(text).region(end, text.length());
            int next = separator.lookingAt() && separator.end() > end ? separator.end() : end + 1;
            clauses.add(new Clause(from, end, text.substring(end, next)));
            from = next;
        }
    }

    /**
     * The words with every part that rules a date out blanked (see {@link #ruledOutClauses}): "Friday doesn't work,
     * Monday does" keeps only "Monday does"; "I cannot do it today, so tomorrow" keeps "tomorrow"; "Thursday and
     * Friday don't work, Monday then" keeps "Monday then"; "Monday rather than Friday" keeps "Monday". Clauses are
     * found in a lower-case copy of the same length, so the words keep their own letters whatever the language.
     */
    static String withoutRuledOut(String words) {
        if (words == null) {
            return null;
        }
        String t = plain(words);
        String lower = TargetCheck.lowerSameLength(t);
        List<Clause> clauses = clauses(lower);
        Range[] out = ruledOutClauses(lower, clauses);
        StringBuilder kept = new StringBuilder(t);
        for (Range r : out) {
            for (int k = r.from(); k < r.to(); k++) {
                kept.setCharAt(k, ' ');
            }
        }
        return kept.toString();
    }

    /**
     * The dates the words rule out: those named in the parts that rule a date out (see {@link #ruledOutClauses}),
     * read as if the negation were not there ("I can't make Friday" rules out Friday, "don't push it back a week"
     * rules out a week after {@code from}, "Monday instead of Friday" only Friday). Every date of a list counts
     * ("can't do Thursday & Friday", "not Friday/Monday"), a weekend counts with both its days, a span counts with
     * every day in it ("I'm away Friday to Monday", "Friday-Sunday", "from the 28th until the 2nd"), days named in the
     * plural with every such day for a year ("Fridays are out", "I can't do weekends"), a week with each of its days
     * from today ("busy all of next week"), and being away or busy until a date with every day from today to the day
     * before it ("I'm away until Friday" leaves Friday; a date that has passed rules out no day).
     */
    static Set<LocalDate> ruledOut(String words, LocalDate from, LocalDate today) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        if (words == null) {
            return dates;
        }
        String t = plain(words);
        String lower = TargetCheck.lowerSameLength(t);
        List<Clause> clauses = clauses(lower);
        for (Range r : ruledOutClauses(lower, clauses)) {
            if (empty(r)) {
                continue;
            }
            String part = RULES_OUT.matcher(lower.substring(r.from(), r.to())).replaceAll(" ");
            DateExpressions.findTarget(part, today).ifPresent(dates::add);
            DateExpressions.find(part, today).ifPresent(dates::add);
            DateExpressions.shift(part, from).ifPresent(dates::add);
            for (String item : LIST_SPLIT.split(part)) {
                DateExpressions.find(item, today).ifPresent(d -> {
                    dates.add(d);
                    if (item.contains("weekend")) {
                        LocalDate saturday = d.getDayOfWeek() == DayOfWeek.SUNDAY ? d.minusDays(1) : d;
                        dates.add(saturday);
                        dates.add(saturday.plusDays(1));
                    }
                });
            }
            for (Pattern spans : List.of(SPAN, DAY_SPAN)) {
                Matcher span = spans.matcher(part);
                while (span.find()) {
                    spanDays(span.group(1), span.group(2), today).forEach(dates::add);
                }
            }
            Matcher plural = PLURAL_DAYS.matcher(part);
            while (plural.find()) {
                // "Fridays are out", "I can't do weekends": every such day
                String day = plural.group(1);
                today.datesUntil(today.plusDays(EVERY_WEEK_DAYS)).filter(d -> day.equals("weekend")
                        ? d.getDayOfWeek().getValue() >= 6
                        : d.getDayOfWeek().name().toLowerCase(Locale.ROOT).equals(day)).forEach(dates::add);
            }
            Matcher week = WEEK.matcher(part);
            while (week.find()) {
                if (DAY_BEFORE.matcher(part.substring(0, week.start())).find()) {
                    continue; // "Monday and Tuesday next week" names those days only
                }
                // "busy all of next week": every day of that week
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                        .plusWeeks("next".equals(week.group(1)) ? 1 : 0);
                monday.datesUntil(monday.plusDays(7)).filter(d -> !d.isBefore(today)).forEach(dates::add);
            }
            if (r.until() >= 0) {
                // "I'm away until Friday": every day from today to the day before Friday; "I was busy until last
                // Friday" rules out no day still to come
                DateExpressions.find(lower.substring(r.until(), clauseEnd(lower, r.until())), today)
                        .filter(last -> last.isAfter(today))
                        .ifPresent(last -> today.datesUntil(last).limit(EVERY_WEEK_DAYS).forEach(dates::add));
            }
        }
        return dates;
    }

    /**
     * Every day from the date {@code first} names to the one {@code last} names, at most {@link #MAX_SPAN_DAYS}
     * apart; a last date that falls before the first is read in the next week or month ("from the 28th until the
     * 2nd" ends on the 2nd after the 28th). Empty when either cannot be read.
     */
    private static List<LocalDate> spanDays(String first, String last, LocalDate today) {
        Optional<LocalDate> start = DateExpressions.find(fullDayName(first), today);
        Optional<LocalDate> end = DateExpressions.find(fullDayName(last), today);
        if (start.isEmpty() || end.isEmpty()) {
            return List.of();
        }
        LocalDate to = end.get();
        if (to.isBefore(start.get())) {
            to = DAY_NAME.matcher(last).find() ? to.plusWeeks(1) : to.plusMonths(1);
        }
        if (to.isBefore(start.get()) || to.isAfter(start.get().plusDays(MAX_SPAN_DAYS))) {
            return List.of();
        }
        return start.get().datesUntil(to.plusDays(1)).toList();
    }

    /**
     * "Friday-Sunday", "Mon-Wed", and "Fri - Sun" once its dash is joined to the second day (see
     * {@link #joinedDaySpans}): every day from the first day to the second.
     */
    private static final Pattern DAY_SPAN = Pattern.compile("\\b((?:(?:this|next)\\s+)?(?:mon|tue|wed|thu|fri|sat|sun)"
            + "[a-z]*)\\s*[-\\u2013\\u2014]{1,2}\\s*((?:mon|tue|wed|thu|fri|sat|sun)[a-z]*)\\b");
    /** A day of the week by its name or short name. */
    private static final Pattern DAY_NAME = Pattern.compile("\\b(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b");
    /** A short day name that the date parser does not read on its own ("wed", "sat", "sun" are ordinary words too). */
    private static final Pattern SHORT_DAY_NAME = Pattern.compile("\\b(mon|tues?|weds?|thu(?:rs?)?|fri|sat|sun)\\b");
    private static final Map<String, String> FULL_DAY = Map.of("mon", "monday", "tue", "tuesday",
            "wed", "wednesday", "thu", "thursday", "fri", "friday", "sat", "saturday", "sun", "sunday");

    /**
     * A day of a span with its short name written out ("sun" is "sunday", "next wed" is "next wednesday"), so the date
     * parser reads it: inside a span the short name can only be a day.
     */
    private static String fullDayName(String day) {
        Matcher m = SHORT_DAY_NAME.matcher(day);
        return m.find() ? day.substring(0, m.start()) + FULL_DAY.get(m.group(1).substring(0, 3)) + day.substring(m.end())
                : day;
    }

    /** Days named in the plural: every such day ("Fridays", "weekends"). */
    private static final Pattern PLURAL_DAYS = Pattern.compile("\\b(monday|tuesday|wednesday|thursday|friday"
            + "|saturday|sunday|weekend)s\\b");
    /** A day right before a week, which the week only places: "Monday next week", "the 24th this week". */
    private static final Pattern DAY_BEFORE = Pattern.compile("(?:\\b(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*"
            + "|\\d(?:st|nd|rd|th)?)\\s*$");
    /** A whole week: "this week", "next week", "all week". */
    private static final Pattern WEEK = Pattern.compile("\\b(?:(this|next)\\s+week|all\\s+(week))\\b");
    /** How far ahead "every Friday" and "until" rule days out, in days. */
    private static final int EVERY_WEEK_DAYS = 371;

    /** Words that read as a date even when the kernel cannot work one out: names of days and months, parts of them. */
    private static final String DATE_LIKE_WORDS = "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
            + "|mon|tue|wed|thu|fri|sat|sun)[a-z]*\\b|\\b(?:end|mid|middle|start|beginning|week|weeks|weekend|month"
            + "|months|year|years|day|days|tomorrow|tonight|today|christmas|easter|new year\\w*|summer|winter|spring"
            + "|autumn|fall|holidays?|next|this)\\b";
    /** Words after a deadline marker that make it read as a date, even one the kernel cannot work out. */
    private static final Pattern DATE_LIKE = Pattern.compile("\\d|" + DATE_LIKE_WORDS);
    /**
     * {@link #DATE_LIKE} without a bare number: after "for", "on", "in" or "to" a number is mostly an amount or part of
     * a name ("for 12 people", "for the pm7 shed door").
     */
    private static final Pattern DATE_LIKE_NAMED = Pattern.compile(DATE_LIKE_WORDS);
    private static final Pattern DEADLINE_OR_WITHIN = Pattern.compile("\\b(?:by|until|till|due|before|within)\\b");

    /**
     * The deadline phrase of the words ("by the end of March", "within two weeks"), when the words state one that is
     * not ruled out; empty when they state none. Used to say so when no date could be read from it.
     */
    static Optional<String> deadlinePhrase(String words) {
        if (words == null) {
            return Optional.empty();
        }
        String t = TargetCheck.straightQuotes(words).toLowerCase(Locale.ROOT);
        Matcher m = DEADLINE_OR_WITHIN.matcher(t);
        int tried = 0;
        while (tried++ < MAX_MARKERS && m.find()) {
            if (RULES_OUT.matcher(t).region(Math.max(clauseStart(t, m.start()), m.start() - WINDOW), m.end()).find()) {
                continue;
            }
            String phrase = t.substring(m.start(), Math.min(clauseEnd(t, m.end()), m.end() + WINDOW)).strip();
            if (DATE_LIKE.matcher(phrase.substring(m.end() - m.start())).find()) {
                return Optional.of(phrase);
            }
        }
        return Optional.empty();
    }

    /**
     * The words that make a date a new goal's or task's deadline beyond doubt: "by", "before", "due", "deadline" and
     * "no later than", with the words that may stand between such a word and its date ("due on Friday", "deadline is
     * Friday", "by the end of March", "deadline: Friday"). "Until" and "till" say how long something lasts ("hold off
     * on buying a car until December 4"), and "on", "for", "next", "this", "today" and "tomorrow" say when something
     * happens, so none of them does.
     */
    private static final Pattern STRICT_MARKER = Pattern.compile("\\b(?:by|before|due|deadline"
            + "|no(?:t)?\\s+later\\s+than)(?:\\s*[:,]?\\s*(?:on|is|of|date|at|the)\\b)*\\s*[:,]?\\s*$");
    /** What may follow a trailing deadline: punctuation and spaces only. */
    private static final Pattern TRAILING = Pattern.compile("[\\s.!?,;:)\"']*");
    /** A label before a new goal's or task's words ("new goal:", "add a task to the picnic:"): up to its last colon. */
    private static final Pattern LABEL = Pattern.compile("^[^:]*:");
    /** Courtesy that may open the words of a new goal or task ("ok, ", "please "). */
    private static final Pattern OPENING_COURTESY = Pattern.compile("^\\s*(?:(?:ok|okay|please|hey|so|right)[,\\s]+)+");
    /**
     * What makes the words before a deadline more than one goal's or task's text: a second clause, started by
     * punctuation, a dash between spaces, a word that joins a clause ("but", "because", "so", "while", "if", "when",
     * "until", "who", "which", ...), "and" or "or" before a subject pronoun, or a verb of state or a contraction of one
     * ("is", "was", "has", "it's", "we're"). Such words may hold a date of their own ("QA mow the lawn, rain lasts
     * until Friday"), so the date is no deadline beyond doubt.
     */
    private static final Pattern SECOND_CLAUSE = Pattern.compile("[,;.!?]|\\s[-\\u2013\\u2014]{1,2}\\s"
            + "|\\b(?:but|because|cause|so|since|while|whilst|although|though|unless|if|once|when|whenever|whereas"
            + "|after|until|till|where|who|whom|whose|which)\\b|\\bas\\s+(?:soon|long)\\s+as\\b"
            + "|\\b(?:and|or|then)\\s+(?:i|you|he|she|it|we|they|there)\\b"
            + "|\\b(?:is|are|was|were|am|been|being|has|had|will|would|shall)\\b"
            + "|\\b(?:it|that|this|he|she|there|what|who|here)'s\\b|'(?:re|m|ve|d|ll)\\b");
    /**
     * A subject pronoun: after the first word of a goal's or task's text it starts a clause of its own ("learn the
     * song I heard", "frame the photo we took"); as the first word it is the person's own voice ("I want to run a
     * marathon").
     */
    private static final Pattern SUBJECT_PRONOUN = Pattern.compile("\\b(?:i|you|he|she|we|they)\\b");

    /**
     * Where the one strict deadline of a new goal's or task's words stands, from its marker to the end of its date
     * phrase: a strong marker (see {@link #STRICT_MARKER}) right before the words' one date phrase (see {@link
     * DateExpressions#datePhraseSpans}), that phrase the last words (only punctuation after it), and the words before
     * the marker one text with no second clause (see {@link #SECOND_CLAUSE}, and "that" starting a clause, see {@link
     * #RELATIVE_THAT}), no subject pronoun after its first word, and no past form right before the marker that the
     * date may describe, unless someone is to have it done ("get the taxes done by April 15"). "new goal: plant
     * tulips by Friday" and "add a task to buy milk and bread by the 4th" have one; "learn the song I heard before
     * Christmas", "read Fences by August Wilson", "QA mow the lawn, rain lasts until Friday", "buy a cake by Friday
     * for Anna", "sort the photos taken before Christmas" and "file the letters that arrived before Christmas" have
     * none. A label up to a colon ("new goal:") and opening courtesy ("ok,") are no part of the text. Empty for null.
     * This depends on no day: whether the phrase can be read is {@link #strictDeadline}'s question.
     */
    static Optional<int[]> trailingDeadline(String words) {
        return Optional.ofNullable(trailing(words).span());
    }

    /** A deadline marker right before a date phrase: "on", "by", "before", "for", "due". */
    private static final Pattern MARKER_BEFORE = Pattern.compile("\\b(?:on|by|before|for|due)\\s+$");

    /**
     * Where the date phrase that ends {@code text} starts, with a deadline marker right before it ("plan the party
     * next Saturday" gives the start of "next Saturday", "prepare for the interview on Friday" that of "on Friday");
     * empty when no date phrase ends the text, or one does after "until", "after", "since" and the like ("hold off
     * on the car until December 4").
     */
    static OptionalInt endingDateStart(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        String lower = TargetCheck.lowerSameLength(TargetCheck.straightQuotes(text));
        List<DateExpressions.Span> spans = DateExpressions.datePhraseSpans(lower);
        if (spans.isEmpty()) {
            return OptionalInt.empty();
        }
        DateExpressions.Span span = spans.getLast();
        if (!TRAILING.matcher(lower.substring(Math.min(span.end(), lower.length()))).matches()) {
            return OptionalInt.empty();
        }
        String before = lower.substring(0, span.start());
        String first = lower.substring(span.start(), Math.min(span.end(), lower.length())).strip().split("\\s+")[0];
        if (SPAN_WORD.matcher(before).find() || SPAN_WORD.matcher(first).find()) {
            return OptionalInt.empty();
        }
        Matcher marker = MARKER_BEFORE.matcher(before);
        return OptionalInt.of(marker.find() ? marker.start() : span.start());
    }

    /** Why a date that ends the words is no strict deadline when only its marker is missing. */
    static final String NO_MARKER = "you did not say \"by\" or \"before\" in front of the date";
    /**
     * A word that makes the date after it say something else than a deadline: "until", "after", "since", also with
     * "the", "a" or "at" between them and the date ("until at the end of the day").
     */
    private static final Pattern SPAN_WORD = Pattern.compile("\\b(?:until|till|til|after|since|from|around|through"
            + "|past|between|starting|beginning)(?:\\s+(?:the|a|an|at))*\\s*$");

    /**
     * True when the words' one date phrase ends them and would be their strict deadline with "by" in front of it:
     * only the marker is missing ("plan a picnic with friends next Sunday", "prepare for the interview on Friday").
     * Such a date is asked about as the deadline, never set unasked. A date after "until", "after", "since" and the
     * like says something else ("hold off on the car until December 4"), and so does one a clause or a past form
     * claims (see {@link #trailingDeadline}).
     */
    static boolean onlyMarkerMissing(String words) {
        if (words == null || !NO_MARKER.equals(trailing(words).problem())) {
            return false;
        }
        String text = TargetCheck.straightQuotes(words);
        String lower = TargetCheck.lowerSameLength(text);
        DateExpressions.Span span = DateExpressions.datePhraseSpans(lower).getFirst();
        String first = lower.substring(span.start(), Math.min(span.end(), lower.length())).strip().split("\\s+")[0];
        if (SPAN_WORD.matcher(lower.substring(0, span.start())).find() || SPAN_WORD.matcher(first).find()) {
            return false;
        }
        return trailing(text.substring(0, span.start()) + "by " + text.substring(span.start())).span() != null;
    }

    /**
     * Where the strict deadline of the words stands ({@code span}, see {@link #trailingDeadline}), or, when they hold
     * none, why not, in words for the note that says no deadline was set ({@code problem}).
     */
    private record Trailing(int[] span, String problem) {

        static Trailing none(String problem) {
            return new Trailing(null, problem);
        }
    }

    private static Trailing trailing(String words) {
        if (words == null || words.isBlank()) {
            return Trailing.none("your words name no date");
        }
        String t = TargetCheck.lowerSameLength(TargetCheck.straightQuotes(words));
        List<DateExpressions.Span> spans = DateExpressions.datePhraseSpans(t);
        if (spans.isEmpty()) {
            return Trailing.none("your words name no date");
        }
        if (spans.size() != 1) {
            return Trailing.none("your words name more than one date");
        }
        DateExpressions.Span span = spans.getFirst();
        if (!TRAILING.matcher(t.substring(Math.min(span.end(), t.length()))).matches()) {
            return Trailing.none("the date is not at the end of your words");
        }
        int markerStart;
        Matcher inside = STRICT_MARKER_START.matcher(t).region(span.start(), span.end());
        if (inside.lookingAt()) {
            markerStart = span.start();
        } else {
            Matcher before = STRICT_MARKER.matcher(t).region(Math.max(0, span.start() - 40), span.start())
                    .useTransparentBounds(true);
            if (!before.find()) {
                return Trailing.none(NO_MARKER);
            }
            markerStart = before.start();
        }
        String text = t.substring(0, markerStart);
        Matcher label = LABEL.matcher(text);
        if (label.lookingAt()) {
            text = text.substring(label.end());
        }
        text = OPENING_COURTESY.matcher(text).replaceFirst("").strip();
        if (SECOND_CLAUSE.matcher(text).find() || RELATIVE_THAT.matcher(text).find()) {
            return Trailing.none("a second clause comes before the date, so it may not be the deadline");
        }
        Matcher pronoun = SUBJECT_PRONOUN.matcher(text);
        while (pronoun.find()) {
            if (pronoun.start() > 0) {
                return Trailing.none("the date follows a clause with a subject of its own (\"" + pronoun.group()
                        + "\"), so it may not be the deadline");
            }
        }
        String[] before = text.split("[^a-z0-9'-]+");
        String last = before.length == 0 ? "" : before[before.length - 1];
        if (pastForm(last) && !CAUSATIVE.matcher(text).find()) {
            // "sort the photos taken before Christmas": the date says which photos, not when the work is due
            return Trailing.none("the date may say which ones are meant (\"" + last + " " + t.substring(markerStart,
                    span.end()).strip() + "\") rather than when it is due");
        }
        return new Trailing(new int[]{markerStart, span.end()}, null);
    }

    /**
     * Why the words of a new goal or task give it no deadline beyond doubt (see {@link #strictDeadline}), in words for
     * the note that says so: they name no date, or several, the date is not at their end, no strong marker stands
     * right before it, a second clause or a word that the date may describe comes before it, or the one date phrase
     * cannot be read for sure ("by 27/11"). Empty when the deadline is strict.
     */
    static String notStrictReason(String words, LocalDate today) {
        Trailing trailing = trailing(words);
        if (trailing.span() == null) {
            return trailing.problem();
        }
        if (simple(words, today)) {
            return "";
        }
        String phrase = TargetCheck.straightQuotes(words).substring(trailing.span()[0],
                Math.min(words.length(), trailing.span()[1])).strip();
        return "I cannot read \"" + phrase + "\" as one date for sure";
    }

    /**
     * "That" that starts a clause of its own: before a subject pronoun, a verb of state or a past form ("the letters
     * that arrived before Christmas", "the book that I borrowed"); "finish that report by Friday" has none.
     */
    private static final Pattern RELATIVE_THAT = Pattern.compile("\\bthat\\s+(?:i|you|he|she|we|they|it|was|were|is"
            + "|are|has|have|had|will|would|could|can|should|came|went|got|[a-z]+ed)\\b");
    /**
     * Words that make someone have something done, so a past form right before a deadline is the work itself ("get
     * the taxes done by April 15", "have the garage cleaned by Friday").
     */
    private static final Pattern CAUSATIVE = Pattern.compile("\\b(?:get|gets|getting|got|have|having)\\b");
    /** Past forms and participles that do not end in "-ed": "taken", "written", "done". */
    private static final Set<String> PARTICIPLES = Set.of("taken", "written", "given", "chosen", "broken", "stolen",
            "forgotten", "hidden", "driven", "eaten", "spoken", "shown", "known", "grown", "drawn", "seen", "done",
            "gone", "begun", "sung", "worn", "torn", "frozen", "fallen", "ridden", "risen", "shaken", "came");
    /** Words that end in "-ed" and are no past form. */
    private static final Set<String> NOT_PAST = Set.of("need", "speed", "greed", "breed", "bleed", "steed", "tweed",
            "embed", "proceed", "succeed", "exceed", "indeed", "hundred", "kindred", "sacred", "naked", "wicked",
            "rugged", "ragged", "beloved", "crooked", "shed", "seed", "feed", "weed", "deed", "sled", "bred");

    /**
     * True for a past form or a participle: a word of four letters or more ending in "-ed" ("arrived", "borrowed";
     * not "shed" or "speed"), an irregular past ("sent", "bought", see the conversation's list; not one spelled as
     * its base, such as "read"), or a participle such as "taken" or "done".
     */
    static boolean pastForm(String w) {
        if (w == null || w.isEmpty() || NOT_PAST.contains(w)) {
            return false;
        }
        String base = ConversationService.IRREGULAR_PAST.get(w);
        return w.length() >= 4 && w.endsWith("ed") || PARTICIPLES.contains(w) || base != null && !base.equals(w);
    }

    /** A strong marker at the start of a date phrase that holds it ("before Christmas"). */
    private static final Pattern STRICT_MARKER_START = Pattern.compile("(?:by|before|due|deadline"
            + "|no(?:t)?\\s+later\\s+than)\\b");

    /**
     * True when the words of a new goal or task give it a deadline beyond doubt: they hold a strict trailing deadline
     * (see {@link #trailingDeadline}) and that one date phrase is simple on {@code today} (see {@link #simple}: read
     * whole, nothing that negates, corrects or hedges it). Otherwise a date the words name is asked about when the
     * model also gives one, and left out with a note when it does not.
     */
    static boolean strictDeadline(String words, LocalDate today) {
        return trailingDeadline(words).isPresent() && simple(words, today);
    }

    /**
     * The words with every clause (as {@link #clauses} cuts them) that {@code which} accepts blanked, every character
     * in its place.
     */
    static String blankClauses(String words, Predicate<String> which) {
        if (words == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(words);
        for (Clause c : clauses(words)) {
            if (which.test(words.substring(c.start(), c.end()))) {
                for (int k = c.start(); k < c.end(); k++) {
                    out.setCharAt(k, ' ');
                }
            }
        }
        return out.toString();
    }

    /** Words that put a date in place in any way: a move's "to", a deadline's "by", "on Friday", "in March". */
    private static final Pattern PHRASE_MARKER = Pattern.compile("\\b(?:to|until|till|by|due|before|after|on|for|in"
            + "|within)\\b");

    /**
     * The phrase of the words that names a date, as the person wrote it, for saying which words a date the kernel
     * could not read comes from: from the marker nearest before the first word that reads as a date in the clause of a
     * marker ("to the week after Christmas", "by the first Monday after Easter", "until December 4" of "hold off on
     * buying chairs until December 4"), else the clause of the first date word ("the 31st of November"). Empty when
     * the words name no date.
     */
    static Optional<String> datePhrase(String words) {
        if (words == null) {
            return Optional.empty();
        }
        String text = TargetCheck.straightQuotes(words);
        // a task key ("T4") names no date; blanked letter by letter so every index stays an index into the words
        Matcher key = TASK_KEY.matcher(text);
        StringBuilder blanked = new StringBuilder(TargetCheck.lowerSameLength(text));
        while (key.find()) {
            for (int i = key.start(); i < key.end(); i++) {
                blanked.setCharAt(i, ' ');
            }
        }
        String t = blanked.toString();
        Matcher m = PHRASE_MARKER.matcher(t);
        int tried = 0;
        while (tried++ < MAX_MARKERS && m.find()) {
            int end = Math.min(clauseEnd(t, m.end()), m.end() + WINDOW);
            String after = t.substring(m.end(), end);
            Matcher named = DATE_LIKE_NAMED.matcher(after);
            Matcher word = DATE_WORD.matcher(after);
            int first = Math.min(named.find() ? named.start() : Integer.MAX_VALUE,
                    word.find() ? word.start() : Integer.MAX_VALUE);
            if (first != Integer.MAX_VALUE) {
                // The marker nearest before the date word puts it in place: "until December 4", not "on buying chairs
                // until December 4".
                int from = m.start();
                Matcher nearer = PHRASE_MARKER.matcher(t).region(m.end(), m.end() + first);
                while (nearer.find()) {
                    from = nearer.start();
                }
                return Optional.of(text.substring(from, end).strip());
            }
        }
        Matcher date = DATE_WORD.matcher(t);
        if (date.find()) {
            return Optional.of(text.substring(clauseStart(t, date.start()), clauseEnd(t, date.end())).strip())
                    .filter(p -> !p.isEmpty());
        }
        return Optional.empty();
    }

    /**
     * A word that reads as a date or a day: a date in digits ("6.12.", "06-12-2026", "27/11"), a day of the month
     * ("the 6th"), a time ("3pm"), a day or month name or its short form ("Sat", "Dec"), "day" ("Boxing Day"),
     * "midnight", "noon", "eve", a holiday or a span of the calendar ("weekend", "month"). A number on its own ("a 5 km
     * walk", "12 people", "Q1") is none.
     */
    private static final Pattern DATE_HINT = Pattern.compile("(?i)\\b\\d{1,4}[./-]\\d{1,2}(?:[./-]\\d{2,4})?"
            + "|\\b\\d{1,2}(?:st|nd|rd|th)\\b|\\b\\d{1,2}\\s*(?:am|pm)\\b|\\b(?:monday|tuesday|wednesday|thursday"
            + "|friday|saturday|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|january|february|march|april|june"
            + "|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"
            + "|today|tonight|tomorrow|yesterday|weekend|week|month|year|christmas|xmas|easter|halloween"
            + "|midnight|noon|midday|eve|day)s?\\b");

    /**
     * True when the words hold a word that reads as a date or a day (see {@link #DATE_HINT}; task keys aside), even
     * one the kernel cannot work out ("Sat", "6.12.", "06-12-2026", "Boxing Day", "St Patrick's Day", "midnight").
     * Used to say that a date could not be read rather than that the words name none.
     */
    static boolean dateLike(String words) {
        if (words == null) {
            return false;
        }
        return DATE_HINT.matcher(TASK_KEY.matcher(TargetCheck.straightQuotes(words)).replaceAll(" ")).find();
    }

    /**
     * True when the words name a date in any form, even one the kernel cannot work out ("next spring"), or a move
     * relative to the current date ("push it back a week"); false for "yes, move the deadline like you said" and for
     * a task key ("T4").
     */
    static boolean statesDate(String words, LocalDate from, LocalDate today) {
        if (words == null) {
            return false;
        }
        String t = TASK_KEY.matcher(plain(words)).replaceAll("  ").toLowerCase(Locale.ROOT);
        return DATE_WORD.matcher(t).find() || DateExpressions.shift(t, from == null ? today : from).isPresent()
                || DateExpressions.find(t, today).isPresent();
    }

    /**
     * Words that name a date or a span of time: a day or month name, a day of the month ("the 4th"), a date in digits
     * ("2026-12-04", "12/4"), a holiday or season, or an amount of days, weeks, months or years ("a week", "two
     * months", "next week"). "Sat", "sun", "wed", "mar" and "may" are ordinary words too: the first three never count
     * on their own, "mar" and "may" only before a number ("May 3"). A number on its own is no date: "a 5 km walk", "the
     * Q1 report", "the pm7 shed door" name none, nor do "weekly", "the holiday photos" or "days" without an amount.
     */
    private static final Pattern DATE_WORD = Pattern.compile("\\b(?:monday|tuesday|wednesday|thursday|friday"
            + "|saturday|sunday|mon|tue|tues|thu|thur|thurs|fri|january|february|march|april|june|july|august"
            + "|september|october|november|december|jan|feb|apr|jun|jul|aug|sep|sept|oct|nov|dec|today|tomorrow"
            + "|tonight|weekend|fortnight|christmas|xmas|easter|halloween|new year|spring|summer|autumn|winter)s?\\b"
            + "|\\b(?:may|mar)\\s+\\d|\\b\\d{1,2}(?:st|nd|rd|th)\\b|\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b"
            + "|\\b\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?\\b"
            + "|\\b(?:\\d+|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|few|couple(?:\\s+of)?"
            + "|next|this|last|coming|following|that|the)\\s+(?:days?|weeks?|fortnights?|months?|years?)\\b"
            + "|\\b(?:after|over|during|before|until|till|by)\\s+the\\s+holidays?\\b|\\bthe\\s+holidays\\b");

    /** Where the clause holding {@code index} starts: just after the last clause end before it. */
    private static int clauseStart(String t, int index) {
        return DateExpressions.clauseStart(t, index);
    }

    /** Where the clause running on from {@code index} ends: at the next clause end, or the end of the words. */
    private static int clauseEnd(String t, int index) {
        return DateExpressions.clauseEnd(t, index);
    }

    /** A task key as people type it: T3, t12. */
    private static final Pattern TASK_KEY = Pattern.compile("(?i)\\bT\\d{1,3}\\b");
    /** Words and signs that join a part of a sentence to the next one, cut off the end of a task's part. */
    private static final Pattern TRAILING_JOIN = Pattern.compile("(?i)(?:[\\s,;:]|\\b(?:and|but|or|then|so|while)\\b)+$");

    /**
     * The part of a sentence with several actions that speaks about one of them. With a task key ("T3") that the
     * sentence names, the part runs from that key to the next task key named (from the start of the sentence for the
     * first key, so "move T3 to Friday" keeps its verb), whatever joins them ("move T3 to Monday but T4 to
     * Tuesday"); a part that names no date runs on over the next one ("move T3 and T4 to Friday"). Otherwise the
     * clause sharing strictly the most words with the action's title, the sentence cut at every clause end as the
     * date parser cuts clauses, so a comma inside a date ("June 1, 2028") does not split it. Null when no part can be
     * told apart, so no other action's date is borrowed.
     */
    static String clause(String said, String key, String title) {
        return part(said, key, title, ActionDates::namesDate);
    }

    /**
     * The part of a sentence that speaks about one task and nothing else, for its hours and its status: with a task
     * key the sentence names, from that key (from the start of the sentence for the first key) to the next key, run
     * on only over keys listed together with it ("T3 and T4 took 2 hours each", "T3, T4 and T5 are done"); so "T3 is
     * done and T4 needs 4 hours" gives T3 "T3 is done", and "T3 is done but T4 is not" gives T4 "T4 is not".
     * Otherwise as {@link #clause} cuts it, without running on. Null when no part can be told apart.
     */
    static String ownPart(String said, String key, String title) {
        if (said == null) {
            return null;
        }
        if (key != null && !key.isBlank()) {
            List<int[]> keys = new ArrayList<>();
            Matcher m = TASK_KEY.matcher(said);
            int mine = -1;
            while (m.find()) {
                if (m.group().equalsIgnoreCase(key.strip()) && mine < 0) {
                    mine = keys.size();
                }
                keys.add(new int[]{m.start(), m.end()});
            }
            if (mine >= 0) {
                if (keys.stream().allMatch(k -> said.substring(k[0], k[1]).equalsIgnoreCase(key.strip()))) {
                    return said;
                }
                int last = mine;
                while (last + 1 < keys.size()
                        && LISTED.matcher(said.substring(keys.get(last)[1], keys.get(last + 1)[0])).matches()) {
                    last++;
                }
                int start = mine == 0 ? 0 : keys.get(mine)[0];
                int end = last + 1 < keys.size() ? keys.get(last + 1)[0] : said.length();
                return TRAILING_JOIN.matcher(said.substring(start, end)).replaceAll("").strip();
            }
        }
        return part(said, null, title, part -> true);
    }

    /** A task as a sentence may name it: by its key ("T3") or by the words of its title ("the invitations"). */
    record TaskName(String key, String title) {
    }

    /**
     * The part of a sentence about one task, for its hours and its status, and whether that part speaks of other
     * tasks too ("T3 and T4 are done, it took 2 hours": the 2 hours are T3's and T4's together). Each text is the
     * sentence with everything outside it blanked, every character in its place (see {@link #taskPart}).
     *
     * @param text   the part, in the order said
     * @param named  the clauses of the part that name this task, without the clauses that only belong to them; the
     *               whole part when no clause names it
     * @param alone  the clauses of the part that speak of this task and no other one (with the clauses that only
     *               belong to them): "T3 took 1 hour" of "T3 and T4 are done, T3 took 1 hour"
     * @param shared true when a clause of the part names another task as well
     * @param own    true when a clause of the part names this task, false when the part is only what names no task
     */
    record TaskPart(String text, String named, String alone, boolean shared, boolean own) {
    }

    /** What joins tasks listed together: "and", "or", "&", a comma. */
    private static final Pattern LIST_JOINER = Pattern.compile("(?i)\\s*(?:and|or|&|,)\\s*");
    /** A clause that ends with a task key ("done with T2"). */
    private static final Pattern ENDS_WITH_KEY = Pattern.compile("(?i)\\bT\\d{1,3}\\W*$");
    /** A clause that starts with a task key ("T3 in 3 hours"). */
    private static final Pattern STARTS_WITH_KEY = Pattern.compile("(?i)^\\W*T\\d{1,3}\\b");

    /** What a clause is made of when it only lists tasks: keys, "and", "&", "both", "the", "task". */
    private static final Pattern ONLY_KEYS = Pattern.compile("(?i)(?:\\s|,|&|\\band\\b|\\bboth\\b|\\bthe\\b"
            + "|\\btasks?\\b|\\bT\\d{1,3}\\b)*");

    /**
     * The part of a sentence that speaks about the task {@code me} (see {@link TaskPart}). The sentence is cut into
     * clauses as the date parser cuts them; a clause that only lists tasks ("T3 and T4 are done" begins with "T3"),
     * or that ends with a task key the next clause continues with another ("done with T2 and T3 in 3 hours"), joins
     * the next one. A clause names a task by its key or by a word of its title that neither the goal's text
     * nor another task's title uses ("sending the invitations" names "Send the invitations"); a clause that names no
     * task belongs to the clause before it that does ("T3 is done, it took 2 hours"), or to the next one when it
     * comes first. The part is every clause that names this task, with the clauses that belong to them: "I reserved
     * the spot, the invitations took 2 hours" gives "Reserve the spot" only "I reserved the spot", and "T2 and T5 are
     * done, T2 took 1 hour and T5 took 2 hours" gives T2 "T2 and T5 are done, T2 took 1 hour". When no clause names
     * this task, the part is the clauses that name no task and belong to none (the whole sentence when it names no
     * task at all). The part is the sentence itself with the other parts blanked (see {@link #kept}), so its words,
     * its amounts ("an hour and a half", "1,5 hours") and where they stand are the person's own. Null when nothing is
     * left.
     *
     * @param goalText the goal's text, whose words name no task of it
     * @param others   the goal's other tasks
     */
    static TaskPart taskPart(String said, TaskName me, String goalText, List<TaskName> others) {
        if (said == null) {
            return null;
        }
        if (me == null || (me.key() == null || me.key().isBlank()) && (me.title() == null || me.title().isBlank())) {
            return new TaskPart(said, said, said, false, false); // a task the words cannot name: the whole sentence
        }
        // Each part is one clause, or clauses that only list tasks joined to the clause that follows them; a part
        // keeps where its words stand in the sentence ({start, end}).
        List<int[]> spans = new ArrayList<>();
        int pendingStart = -1;
        List<Clause> cut = clauses(said);
        for (int i = 0; i < cut.size(); i++) {
            Clause c = cut.get(i);
            int[] own = stripped(said, c.start(), c.end());
            if (own[0] >= own[1]) {
                continue;
            }
            String text = said.substring(own[0], own[1]);
            if (pendingStart < 0) {
                pendingStart = own[0];
            }
            boolean listed = ONLY_KEYS.matcher(text).matches() && TASK_KEY.matcher(text).find()
                    || i + 1 < cut.size() && LIST_JOINER.matcher(c.separator()).matches()
                    && ENDS_WITH_KEY.matcher(text).find()
                    && STARTS_WITH_KEY.matcher(said.substring(cut.get(i + 1).start(), cut.get(i + 1).end())).find();
            if (!listed) {
                spans.add(new int[]{pendingStart, own[1]});
                pendingStart = -1;
            }
        }
        if (pendingStart >= 0) {
            spans.add(new int[]{pendingStart, stripped(said, pendingStart, said.length())[1]});
        }
        List<String> parts = spans.stream().map(sp -> said.substring(sp[0], sp[1])).toList();
        List<TaskName> all = new ArrayList<>();
        all.add(me);
        if (others != null) {
            all.addAll(others);
        }
        Set<String> common = TargetCheck.stems(goalText == null ? "" : goalText);
        List<Set<String>> titleWords = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Set<String> words = new HashSet<>(TargetCheck.stems(all.get(i).title() == null ? "" : all.get(i).title()));
            words.removeAll(common);
            titleWords.add(words);
        }
        // Words two tasks' titles share tell neither apart: each title keeps only the words no other title holds, all
        // compared with the titles as written ("strings" in three titles names none of them).
        List<Set<String>> written = List.copyOf(titleWords);
        for (int i = 0; i < written.size(); i++) {
            Set<String> mine = new HashSet<>(written.get(i));
            for (int j = 0; j < written.size(); j++) {
                if (j != i) {
                    mine.removeAll(written.get(j));
                }
            }
            titleWords.set(i, mine);
        }
        List<Set<Integer>> named = new ArrayList<>();
        for (String part : parts) {
            Set<Integer> tasks = new LinkedHashSet<>();
            Set<String> keys = ConversationService.namedKeys(part);
            Set<String> stems = TargetCheck.stems(TASK_KEY.matcher(part).replaceAll(" "));
            for (int i = 0; i < all.size(); i++) {
                String key = all.get(i).key();
                if (key != null && keys.contains(key.strip().toUpperCase(Locale.ROOT))
                        || titleWords.get(i).stream().anyMatch(stems::contains)) {
                    tasks.add(i);
                }
            }
            for (String key : keys) {
                if (all.stream().noneMatch(n -> n.key() != null && n.key().strip().equalsIgnoreCase(key))) {
                    tasks.add(-1); // a key of no task of this goal still names another task
                }
            }
            named.add(tasks);
        }
        // A clause that names no task belongs to the nearest one before it that does, or else after it.
        int[] owner = new int[parts.size()];
        int last = -1;
        for (int i = 0; i < parts.size(); i++) {
            owner[i] = named.get(i).isEmpty() ? last : i;
            if (!named.get(i).isEmpty()) {
                last = i;
            }
        }
        int next = -1;
        for (int i = parts.size() - 1; i >= 0; i--) {
            if (!named.get(i).isEmpty()) {
                next = i;
            } else if (owner[i] < 0) {
                owner[i] = next;
            }
        }
        boolean mentioned = named.stream().anyMatch(s -> s.contains(0));
        List<Integer> mine = new ArrayList<>();
        List<Integer> naming = new ArrayList<>();
        List<Integer> alone = new ArrayList<>();
        boolean shared = false;
        for (int i = 0; i < parts.size(); i++) {
            Set<Integer> tasks = owner[i] < 0 ? Set.of() : named.get(owner[i]);
            if (mentioned ? tasks.contains(0) : tasks.isEmpty()) {
                mine.add(i);
                shared |= tasks.size() > 1;
                if (tasks.size() <= 1) {
                    alone.add(i);
                }
                if (!mentioned || named.get(i).contains(0)) {
                    naming.add(i);
                }
            }
        }
        if (mine.isEmpty()) {
            return null;
        }
        List<Durations.Amount> amounts = Durations.amounts(said);
        return new TaskPart(kept(said, spans, mine, amounts), kept(said, spans, naming, amounts),
                kept(said, spans, alone, amounts), shared, mentioned);
    }

    /**
     * The sentence with only the parts {@code chosen} (indices into {@code spans}, in order) kept, every character in
     * its place: what joins two chosen parts that follow each other is kept as said ("T3 is done, it took an hour
     * and a half"), and where a part that is not chosen stood between two chosen ones a comma keeps them two clauses.
     * An amount of time is never cut: an amount a chosen part holds only in part is kept whole ("1,5 hours", "an hour
     * and a half" that a clause end cuts).
     */
    private static String kept(String said, List<int[]> spans, List<Integer> chosen, List<Durations.Amount> amounts) {
        char[] out = " ".repeat(said.length()).toCharArray();
        boolean[] keep = new boolean[said.length()];
        for (int n = 0; n < chosen.size(); n++) {
            int[] span = spans.get(chosen.get(n));
            int to = span[1];
            if (n + 1 < chosen.size() && chosen.get(n + 1) == chosen.get(n) + 1) {
                to = spans.get(chosen.get(n + 1))[0]; // the words that join it to the next chosen part
            }
            for (int k = span[0]; k < to; k++) {
                keep[k] = true;
            }
        }
        for (Durations.Amount amount : amounts) {
            int from = Math.max(0, amount.start());
            int to = Math.min(said.length(), amount.end());
            boolean touched = false;
            for (int k = from; k < to && !touched; k++) {
                touched = keep[k];
            }
            if (touched) {
                for (int k = from; k < to; k++) {
                    keep[k] = true;
                }
            }
        }
        for (int k = 0; k < said.length(); k++) {
            if (keep[k]) {
                out[k] = said.charAt(k);
            }
        }
        for (int n = 0; n + 1 < chosen.size(); n++) {
            int end = spans.get(chosen.get(n))[1];
            if (chosen.get(n + 1) != chosen.get(n) + 1 && end < said.length() && !keep[end]) {
                out[end] = ','; // a part left out stood here
            }
        }
        return new String(out);
    }

    /** What lies between two task keys listed together: commas, "&", "and" ("T3, T4 and T5"). */
    private static final Pattern LISTED = Pattern.compile("(?i)[\\s,&]*(?:\\band\\b)?[\\s,&]*");

    private static String part(String said, String key, String title, Predicate<String> names) {
        int[] span = partSpan(said, key, title, names);
        if (span == null) {
            return null;
        }
        return span[0] == 0 && span[1] == said.length() ? said : said.substring(span[0], span[1]);
    }

    /**
     * Where the part {@link #clause} returns stands in the sentence, as {start, end} without the spaces and joining
     * words around it; the whole sentence when it is one part. Null when no part can be told apart.
     */
    static int[] clauseSpan(String said, String key, String title) {
        return partSpan(said, key, title, ActionDates::namesDate);
    }

    private static int[] partSpan(String said, String key, String title, Predicate<String> names) {
        if (said == null) {
            return null;
        }
        if (key != null && !key.isBlank()) {
            int[] byKey = keyPartSpan(said, key.strip(), names);
            if (byKey != null) {
                return byKey;
            }
        }
        List<int[]> split = new ArrayList<>();
        for (Clause c : clauses(said)) {
            int[] stripped = stripped(said, c.start(), c.end());
            if (stripped[0] < stripped[1]) {
                split.add(stripped);
            }
        }
        if (split.size() <= 1) {
            return new int[]{0, said.length()};
        }
        if (title == null || title.isBlank()) {
            return null;
        }
        Set<String> words = TargetCheck.stems(title);
        int[] best = null;
        int bestOverlap = 0;
        int runnerUp = 0;
        for (int[] part : split) {
            Set<String> partWords = TargetCheck.stems(said.substring(part[0], part[1]));
            int overlap = (int) words.stream().filter(partWords::contains).count();
            if (overlap > bestOverlap) {
                runnerUp = bestOverlap;
                bestOverlap = overlap;
                best = part;
            } else if (overlap > runnerUp) {
                runnerUp = overlap;
            }
        }
        return bestOverlap > runnerUp ? best : null;
    }

    /** {@code [from, to)} of the text without the spaces at either end. */
    private static int[] stripped(String text, int from, int to) {
        while (from < to && Character.isWhitespace(text.charAt(from))) {
            from++;
        }
        while (to > from && Character.isWhitespace(text.charAt(to - 1))) {
            to--;
        }
        return new int[]{from, to};
    }

    /** At most this many task keys of one sentence are walked when a part runs on over the next keys. */
    private static final int MAX_KEYS = 32;

    /**
     * Where the part of the sentence about the task {@code key} stands (see {@link #clause}): the whole sentence when
     * it names no other task key, null when it does not name this one. A part runs on over the next task's part while
     * {@code names} finds nothing in it, over at most {@link #MAX_KEYS} keys.
     */
    private static int[] keyPartSpan(String said, String key, Predicate<String> names) {
        List<int[]> keys = new ArrayList<>();
        Matcher m = TASK_KEY.matcher(said);
        int mine = -1;
        while (m.find()) {
            if (m.group().equalsIgnoreCase(key) && mine < 0) {
                mine = keys.size();
            }
            keys.add(new int[]{m.start(), m.end()});
        }
        if (mine < 0) {
            return null;
        }
        if (keys.stream().allMatch(k -> said.substring(k[0], k[1]).equalsIgnoreCase(key))) {
            return new int[]{0, said.length()};
        }
        int start = mine == 0 ? 0 : keys.get(mine)[0];
        int next = mine + 1;
        int[] part;
        do {
            int end = next < keys.size() ? keys.get(next)[0] : said.length();
            part = withoutTrailingJoin(said, start, end);
            next++;
        } while (next <= keys.size() && next - mine <= MAX_KEYS && !names.test(said.substring(part[0], part[1])));
        return part;
    }

    /** {@code [from, to)} without the joining words and signs at its end (see {@link #TRAILING_JOIN}) and spaces. */
    private static int[] withoutTrailingJoin(String said, int from, int to) {
        Matcher join = TRAILING_JOIN.matcher(said).region(from, to);
        int end = join.find() ? join.start() : to;
        return stripped(said, from, end);
    }

    /**
     * The sentence with everything outside {@code [from, to)} blanked, every character in its place: a part of the
     * sentence that keeps where its words stand, so a reader of the part reads the person's own words.
     */
    static String mask(String said, int from, int to) {
        if (said == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(" ".repeat(said.length()));
        for (int k = Math.max(0, from); k < Math.min(said.length(), to); k++) {
            out.setCharAt(k, said.charAt(k));
        }
        return out.toString();
    }

    /**
     * True when a part of a sentence names a date or a relative move (see {@link #statesDate}), its task keys left
     * out ("move T3" names none). Only whether a date is named matters here, so any day serves as today.
     */
    private static boolean namesDate(String part) {
        LocalDate anyDay = LocalDate.of(2000, 1, 3);
        return statesDate(TASK_KEY.matcher(part).replaceAll(" "), anyDay, anyDay);
    }

    // ---- how sure a reading is ----

    /**
     * True when the kernel's own reading of a date is simple, the first of the two readers that must agree for a date
     * to be applied without asking (the model's date is the second): the words state one date phrase and nothing that
     * corrects, negates, contrasts or excludes it, read on {@code today} (see {@link DateExpressions#simple(String,
     * LocalDate)}, which also leaves out a weekday that refers back, a "next Friday" within the next days and a date
     * just past read as next year's), and no repeating day besides it ({@link #recurrence}: "every day until
     * Friday"). Words that make the date a guess ("by Friday I think", "around the 20th") are not simple either.
     * "Move the picnic to next Monday" said on a Wednesday is simple; "move it to Friday, make it Monday", "move it
     * to Monday, I'm busy on Friday" and "not this Friday but next" are not.
     */
    static boolean simple(String words, LocalDate today) {
        return DateExpressions.simple(words, today) && recurrence(words).isEmpty()
                && (words == null || !HEDGE.matcher(words).find());
    }

    /**
     * True when {@code own}, the part of the sentence a change's date is read from, names its date by a weekday that
     * refers back ("T4 to that Friday", "the following Monday") while the sentence holds another date phrase
     * ("move T3 to December 1 and T4 to that Friday"): the weekday may count from that other date, so the kernel
     * offers no reading of its own. "The Friday after Christmas" and "the Friday the 27th" name their own anchor and
     * refer to nothing (see {@link DateExpressions#anaphoricWeekday}).
     */
    static boolean anaphoricWeekday(String own, String said) {
        if (own == null || said == null || !DateExpressions.anaphoricWeekday(TargetCheck.straightQuotes(own))) {
            return false;
        }
        return DateExpressions.datePhraseSpans(said).size() > 1;
    }

    /** Words that make a date a guess: "I think", "maybe", "probably", "around", "roughly", "-ish". */
    private static final Pattern HEDGE = Pattern.compile("(?i)\\b(?:i\\s+think|i\\s+guess|i\\s+believe|i\\s+suppose"
            + "|maybe|perhaps|probably|possibly|hopefully|apparently|roughly|around|approximately|ish)\\b");

    /** "On Sunday", "on the 1st": the day a repeating phrase repeats on, read with it. */
    private static final String ON_DAY = "(?:\\s+on\\s+(?:the\\s+)?(?:(?:mon|tues|wednes|thurs|fri|satur|sun)days?"
            + "|\\d{1,2}(?:st|nd|rd|th)?))?";
    /**
     * Wording that repeats: "every Friday", "each Sunday", "every other Saturday", "every week on Sunday", "every
     * first Monday of the month", "weekly", "daily", "on Fridays", "the first Monday of each month", and frequencies
     * ("once a week", "twice a month", "3 times a week", "once a month on the 1st", "20 minutes a day"). A day that
     * repeats is no deadline.
     */
    private static final Pattern RECURRENCE = Pattern.compile("(?i)\\b(?:every|each)\\s+(?:other\\s+|second\\s+"
            + "|third\\s+|single\\s+|first\\s+|last\\s+)?(?:day|morning|afternoon|evening|night|week|weekend|month"
            + "|year|(?:mon|tues|wednes|thurs|fri|satur|sun)day|mon|tue|wed|thu|fri|sat|sun)s?\\b" + ON_DAY
            + "|\\b(?:daily|weekly|fortnightly|biweekly|monthly|yearly|annually)\\b" + ON_DAY
            + "|\\b(?:every|each)\\s+(?:weekday|working\\s+day|work\\s*day|business\\s+day|school\\s+day)s?\\b"
            + "|\\b(?:on\\s+)?(?:weekdays|workdays|working\\s+days|weeknights)\\b"
            + "|\\b(?:\\d+|an?|one|two|three|four|five|six|seven|eight|nine|ten|a\\s+few|a\\s+couple\\s+of)\\s+"
            + "(?:[a-z]+\\s+){0,2}?(?:a|per|each|every)\\s+(?:day|weekday|week|month)\\b"
            + "|\\b(?:first|second|third|fourth|last)\\s+(?:mon|tues|wednes|thurs|fri|satur|sun)day\\s+of\\s+"
            + "(?:the|each|every)\\s+month\\b|\\bon\\s+(?:mon|tues|wednes|thurs|fri|satur|sun)days\\b"
            + "|\\b(?:once|twice|thrice|(?:\\d+|two|three|four|five|six|seven|several|a\\s+few)\\s+times)\\s+(?:a|per"
            + "|each|every)\\s+(?:day|week|fortnight|month|year)\\b" + ON_DAY
            + "|\\b(?:\\d+(?:\\.\\d+)?|an?|one|two|three|half\\s+an)\\s*(?:minutes?|mins?|hours?|hrs?|h)\\s+(?:a|per"
            + "|each|every)\\s+(?:day|week|month)\\b" + ON_DAY);

    /** The words that make a date repeat (see {@link #RECURRENCE}) as the person wrote them; empty when none. */
    static Optional<String> recurrence(String words) {
        if (words == null) {
            return Optional.empty();
        }
        Matcher m = RECURRENCE.matcher(TargetCheck.straightQuotes(words));
        return m.find() ? Optional.of(words.substring(m.start(), m.end()).strip()) : Optional.empty();
    }

    /** The words with their repeating wording blanked (see {@link #recurrence}), every character in its place. */
    static String withoutRecurrence(String words) {
        if (words == null) {
            return null;
        }
        Matcher m = RECURRENCE.matcher(TargetCheck.straightQuotes(words));
        StringBuilder out = new StringBuilder(words);
        while (m.find()) {
            for (int i = m.start(); i < m.end(); i++) {
                out.setCharAt(i, ' ');
            }
        }
        return out.toString();
    }

    /**
     * Words that move a date later, the words {@link DateExpressions#shift} moves later by: a verb with "back",
     * "out", "ahead" or "later" ("push it back", "push the picnic out", "move it ahead"); "push", "bump", "slide" or
     * "slip" with no word for the other direction after it ("push the picnic to Monday", "bump it to Wednesday", "it
     * can slip to Friday"); "can wait"; "postpone", "delay", "defer", "extend", "later", "put it off"; and more time
     * ("give it more time", "another week", "two more days", "add a week").
     */
    private static final Pattern LATER = Pattern.compile("(?i)\\b(?:push|put|set|mov|shift|bump|slid|kick)\\w*"
            + "\\s+(?:\\S+\\s+){0,5}?(?:back|out|ahead|later)\\b"
            + "|\\b(?:push|bump|slid|slip)\\w*\\b(?!(?:\\s+\\S+){0,5}?\\s+(?:up|forwards?|in|earlier|sooner)\\b)"
            + "|\\bcan\\s+wait\\b"
            + "|\\b(?:postpon\\w*|delay\\w*|later|extend\\w*|defer\\w*)\\b|\\bput\\s+(?:\\S+\\s+){0,4}?off\\b"
            + "|\\bmore\\s+time\\b|\\b(?:another|extra|more)\\s+(?:\\S+\\s+)?(?:days?|weeks?|fortnights?|months?)\\b"
            + "|\\badd\\s+(?:on\\s+)?(?:another\\s+)?\\S+\\s+(?:days?|weeks?|fortnights?|months?)\\b");
    /**
     * Words that move a date earlier, the words {@link DateExpressions#shift} moves earlier by: "bring it forward",
     * "pull it in", "move it forward", "move the picnic up", "bump it up", "earlier", "sooner", "advance".
     */
    private static final Pattern EARLIER = Pattern.compile("(?i)\\b(?:bring\\w*|brought|pull\\w*)\\s+"
            + "(?:\\S+\\s+){0,5}?(?:forwards?|in|up)\\b"
            + "|\\b(?:mov|shift|push|bump|slid)\\w*\\s+(?:\\S+\\s+){0,5}?(?:forwards?|up)\\b"
            + "|\\b(?:earlier|sooner|advanc\\w*)\\b");

    /**
     * Says so when the words move a date in one direction and the date read for the move lies on the other side of
     * the date it moves from: "push the piano goal back to Thursday" when Thursday comes before the current deadline,
     * "bring it forward to the 30th" when the 30th comes after it. Words that are negated do not count, and words for
     * both directions decide nothing. Empty when the date fits the words, or there is no date to compare.
     */
    static String wrongDirection(String words, LocalDate from, LocalDate to) {
        if (words == null || from == null || to == null) {
            return "";
        }
        String t = TargetCheck.withoutNegated(words);
        boolean later = LATER.matcher(t).find();
        boolean earlier = EARLIER.matcher(t).find();
        if (later && !earlier && !to.isAfter(from)) {
            return " (" + to + " is not later than the current date " + from + ")";
        }
        if (earlier && !later && !to.isBefore(from)) {
            return " (" + to + " is not earlier than the current date " + from + ")";
        }
        return "";
    }
}
