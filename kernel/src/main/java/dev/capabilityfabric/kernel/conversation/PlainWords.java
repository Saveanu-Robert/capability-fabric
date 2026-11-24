package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.algorithms.DateExpressions;
import dev.capabilityfabric.algorithms.Durations;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.TaskStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether the words that ask for one change are plain enough for the change to be applied without a question. The
 * default is to ask: a change is applied at once only when every word of the action's own part of the sentence is a
 * word this class knows for that change, so a phrasing nobody listed ("I'll have T3 done tonight", "we could move the
 * picnic", "I did some of the invitations", "T3 took 2 hours too many") is asked about, never applied. A word is plain
 * when it is one of these:
 * <ul>
 *   <li>a change verb for the action in its plain form: "move", "push ... back", "postpone", "reschedule" for a move;
 *   "done", "finished", "complete", "I did", "mark ... as done" for a completion; "started", "working on", "in
 *   progress" for work under way; "blocked", "stuck" for a task that cannot go on; "add", "new" for a new task;
 *   "reopen", "bring ... back" for a goal opened again; and a request for a new plan (see
 *   {@link ConversationService#replanSpans});</li>
 *   <li>a word that names the target: a task key, the goal's id, a word of the goal's text or of the task's title in
 *   any of its forms ("reserving", "reserved" and the irregular "sent" for "Send the invitations"), and the nouns
 *   "task", "goal", "step", "deadline", "due date";</li>
 *   <li>a word of the one date phrase of a move or a new deadline, with the word that puts it in place ("to
 *   Friday", "by the 4th", see {@link DateExpressions#simplePhrase}), when the words are simple (see
 *   {@link ActionDates#simple}: one date phrase, read whole, nothing that corrects, negates or hedges it);</li>
 *   <li>the one amount of time right after "took", "spent", "needed" or "done in" for a completion (after "takes",
 *   "needs", "to" or "at" for an estimate), with nothing after it in its clause;</li>
 *   <li>a word of {@link #FUNCTION}, a short fixed list ("the", "of", "to", "I", "is", "please", "now", ...), or of
 *   {@link #COURTESY} ("thanks", "great").</li>
 * </ul>
 * Only the past form of the verb that opens the task's title says that the task was done ("I reserved the spot" for
 * "Reserve the spot"), and only where it states what was done: opening its clause, after a subject pronoun or a
 * perfect auxiliary, or after "be" ("the spot was reserved"); "Sam replied" for "Reply to Sam" says what Sam did. A
 * past spelled as its base ("I read the contract") counts only after a subject. A word that states a status never
 * names the task of a status change, even when its title holds it. A word with letters beyond ASCII ("нет", "café")
 * is never plain. Words of {@link #NEVER_PLAIN} (modals, future and intention words, negations, conditions, partial
 * amounts, words of time still ahead) and words that name a date outside that one phrase are never plain, not even
 * when the goal's text or the task's title holds them: "will" in "Write my will" does not make "I will write my will"
 * a completion. Neither are an obligation ("I have to send the invitations"), words that leave a status open ("done
 * for now"), a status that someone else is made to bring about ("please have the spot reserved") and "done in" after
 * a present form of "be" ("T3 is done in 2 hours").
 *
 * <p>A part that is plain is not enough: the whole sentence must be plain (see {@link #wholePlain}), every word of it
 * in the plain part of one of its changes or a function or courtesy word, and nothing in it may make the changes
 * wait on something, take them back or correct them (see {@link #settled}). Whether the sentence is a question, and
 * whether a new goal or task gets the date its words name as its deadline, is decided elsewhere (see
 * {@link ConversationService#supposes} and {@link ActionDates#strictDeadline}).
 */
final class PlainWords {

    private PlainWords() {
    }

    /**
     * What a change is aimed at, as the words may name it: the goal's text and id, the task's title (null for a goal
     * action) and, for a new task, the title it gets.
     */
    record Target(String goalText, String goalId, String taskTitle, String newTitle) {
    }

    /** Words that may stand in any plain part of a sentence. */
    static final Set<String> FUNCTION = Set.of("the", "a", "an", "my", "our", "it", "this", "that", "of", "to", "for",
            "on", "at", "in", "as", "and", "with", "from", "i", "we", "is", "are", "am", "was", "has", "have", "been",
            "please", "ok", "okay", "yes", "now", "also", "too");

    /**
     * Words that are never plain: modals and future words ("will", "could", "may"), intentions and requests ("plan",
     * "want", "need", "going", "try", "remind", "let"), negations, conditions ("if", "unless", "when", "once"),
     * questions, hedges ("maybe", "probably", "almost"), partial amounts ("some", "part", "half", "few", "most") and
     * time still ahead ("soon", "later", "tomorrow", "tonight", "yet", "still"). The one date phrase of a move may
     * still hold "tomorrow" or "tonight" (see {@link #plain}).
     */
    static final Set<String> NEVER_PLAIN = Set.of("will", "would", "could", "should", "might", "may", "must", "can",
            "shall", "ought", "plan", "plans", "planning", "want", "wants", "wanted", "wanna", "need", "needs",
            "going", "gonna", "gotta", "try", "tries", "trying", "hope", "hopes", "hoping", "hopefully", "intend",
            "intends", "if", "unless", "when", "whenever", "once", "whether", "not", "no", "never", "nor", "neither",
            "remind", "let", "lets", "maybe", "perhaps", "probably", "possibly", "almost", "nearly", "about", "soon",
            "later", "tomorrow", "tonight", "yet", "still", "some", "part", "partly", "partially", "half", "most",
            "mostly", "few", "several", "except", "but", "or", "either", "what", "which", "who", "why", "how", "where",
            "think", "guess", "suppose", "imagine", "say", "said", "assume", "expect", "expected", "supposed",
            "kind", "sort", "pretty", "nowhere", "far", "close", "until");

    /** Words that name what a change is aimed at without naming which one: "the task", "the goal's deadline". */
    private static final Set<String> TARGET_NOUNS = Set.of("task", "tasks", "goal", "goals", "step");
    /** Words of a move: "move", "push back", "bring forward", "postpone", "the new deadline", "due date". */
    private static final Set<String> MOVE_WORDS = Set.of("move", "moved", "push", "pushed", "back", "bring", "brought",
            "forward", "put", "set", "change", "changed", "reschedule", "rescheduled", "postpone", "postponed",
            "delay", "delayed", "shift", "shifted", "up", "out", "deadline", "due", "date", "new");
    /** Words of a re-estimate: "set T3 to 4 hours", "change the estimate of T3 to 4 hours". */
    private static final Set<String> ESTIMATE_WORDS = Set.of("set", "change", "changed", "estimate", "estimated",
            "re-estimate", "effort");
    /** Words that set a status, whatever the status: "mark T3 as done", "set T3 to blocked". */
    private static final Set<String> STATUS_VERBS = Set.of("mark", "marked", "set", "as", "already", "finally", "just");
    /** The words that state each status plainly; a status word of another status is not plain for this one. */
    private static final Map<TaskStatus, Set<String>> STATUS_WORDS = Map.of(
            TaskStatus.DONE, Set.of("done", "finished", "complete", "completed", "did"),
            TaskStatus.IN_PROGRESS, Set.of("started", "working", "progress"),
            TaskStatus.BLOCKED, Set.of("blocked", "stuck"),
            TaskStatus.TODO, Set.of("todo", "to-do", "do", "reopen", "reopened", "open", "again"));
    /**
     * Every word that states a status, of any status, with the forms of its verbs: such a word in a task's title
     * never names the task for a status change ("I am finished with the literature review" does not name "Finish the
     * literature review" by "finished" when the change is to in progress).
     */
    private static final Set<String> ANY_STATUS_WORD = Set.of("done", "finish", "finished", "finishing", "complete",
            "completed", "completing", "did", "start", "started", "starting", "working", "progress", "block",
            "blocked", "blocking", "stuck", "todo", "to-do", "do", "reopen", "reopened", "open", "again");
    /** Present forms of "be" after which "on" says where the work stands: "I am on T3", "I'm stuck on T3". */
    private static final Set<String> PRESENT_BE = Set.of("am", "is", "are");
    /** Words of a new task: "add a task to buy napkins", "new task: call the venue". */
    private static final Set<String> ADD_WORDS = Set.of("add", "added", "create", "new", "put", "include", "item",
            "todo", "to-do", "list", "called", "named", "deadline", "due");
    /** Words around a request for a new plan: "make a new plan for the picnic". */
    private static final Set<String> REFINE_WORDS = Set.of("make", "create", "draw", "up");
    /** Words of a goal opened again: "reopen the picnic", "open the picnic again", "bring the picnic back". */
    private static final Set<String> REOPEN_WORDS = Set.of("reopen", "re-open", "reopened", "open", "again",
            "reactivate", "reactivated", "bring", "brought", "back");

    private static final Pattern TASK_KEY = Pattern.compile("t\\d{1,3}");
    /**
     * A word that names a date outside the one date phrase a change may hold: a number, a day or month name,
     * "today", "tomorrow", "tonight", "yesterday", "weekend". Such a word is never plain, not even as a word of a
     * title.
     */
    private static final Pattern DATE_WORD = Pattern.compile("\\d+(?:st|nd|rd|th|am|pm)?|monday|tuesday|wednesday"
            + "|thursday|friday|saturday|sunday|january|february|march|april|june|july|august|september|october"
            + "|november|december|today|tomorrow|tonight|yesterday|weekend");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{4,}");

    /**
     * A word as this class reads it, with where it stands in the words; a contraction is read as its words ("I'm" is
     * "i" and "am", "can't" is "can" and "not", "it's" is "it" and "is", "the picnic's" is "the" and "picnic").
     */
    private record Token(String word, int start, int end) {

        /** True when the word holds a letter or digit outside plain ASCII ("нет", "café"): it is never plain. */
        boolean foreign() {
            return word.chars().anyMatch(c -> c > 127);
        }
    }

    /** A word in any script: letters and digits, joined by apostrophes or hyphens inside it. */
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['-][\\p{L}\\p{N}]+)*'?");
    /**
     * Not right after a present form of "be" ("is", "am", "are", "'s", "'m", "'re", "be"): "T3 is done in 2 hours"
     * speaks of the time ahead, "T3 was done in 2 hours" of the time it took.
     */
    static final String NOT_AFTER_PRESENT_BE = "(?<!(?:\\bis|\\bam|\\bare|\\bbe|\\bbeing|'s|'m|'re)\\s{1,3})";
    /** "No later than Friday" sets a deadline as "by Friday" does; read as that one word. */
    private static final Pattern NO_LATER_THAN = Pattern.compile("\\bno(?:t)?\\s+later\\s+than\\b");

    /**
     * Amount words before an amount of time a completion took: "took", "it took me", "spent", "needed", "done in",
     * "finished it in", "finished T3 in". "Done in", "finished in" and "complete in" say what the work took only after
     * a past form: "T3 is done in 2 hours", "I'm done with T3 in 2 hours" and "it's finished in an hour" say when it
     * will be done, so a present form of "be" right before them leads to no amount.
     */
    private static final Pattern TOOK_LEAD = Pattern.compile("(?:\\b(?:it|that|this)\\s+)?\\b((?:they\\s+)?(?:took|taken)"
            + "(?:\\s+(?:me|us))?)\\s+$|\\b(spent|needed)\\s+$"
            + "|" + NOT_AFTER_PRESENT_BE + "\\b(?:done|finished|completed?)(?:\\s+(?:[a-z'-]+|t\\d{1,3})){0,4}?\\s+(in)\\s+$");
    /** Amount words before an estimate: "T3 takes 4 hours", "T3 needs 4 hours", "set T3 to 4 hours", "at 4 hours". */
    private static final Pattern ESTIMATE_LEAD = Pattern.compile("\\b(takes|take|needs|need|to|at)\\s+$");

    /**
     * Words that leave a status open however plain the rest is: "for now", "for the moment", "for the time being", "so
     * far" ("the layout is done for now" is no completion).
     */
    private static final Pattern PROVISIONAL = Pattern.compile("\\bfor\\s+now\\b|\\bfor\\s+the\\s+(?:moment|time\\s+being)\\b"
            + "|\\bso\\s+far\\b");
    /** An obligation: "I have to send the invitations", "T3 has to be done", "we got to", "gotta". */
    private static final Pattern OBLIGATION = Pattern.compile("\\b(?:have|has|had|got|having)\\s+to\\b|\\bgotta\\b");
    /** Verbs that, with an object between them and a participle, make someone else do it: "have the spot reserved". */
    private static final Set<String> CAUSATIVE = Set.of("have", "has", "had", "having", "get", "gets", "got", "getting");
    /** Forms of "be" before a participle: "T3 has been done", "the invitations were sent". */
    private static final Set<String> BE = Set.of("be", "been", "being", "is", "are", "was", "were", "am");
    /** Words that may stand between a subject or an auxiliary and its verb: "I just reserved", "I've already sent". */
    private static final Set<String> ADVERBS = Set.of("just", "already", "finally", "also", "now", "all", "both");
    /** Subjects before a verb whose past form is spelled as its base ("I read the contract", "we cut the hedge"). */
    private static final Set<String> SUBJECTS = Set.of("i", "we", "you", "he", "she", "they", "have", "has", "had");
    /**
     * Subject pronouns and perfect auxiliaries after which a past form states what was done: "I sent", "we've sent".
     */
    private static final Set<String> PAST_SUBJECTS = SUBJECTS;
    /** A punctuation mark that ends a clause, so the word after it opens one. */
    private static final Pattern CLAUSE_MARK = Pattern.compile("[,;:.!?]");

    /**
     * True when {@code words}, the part of the sentence that asks for a change of {@code type} (with {@code status}
     * for a status change), is plain for that change (see the class comment). A status change needs a word that
     * states its status, or for a completion the past form of the verb that opens the task's title ("I reserved the
     * spot" for "Reserve the spot"; "I read the contract" for "Read the contract", whose past is spelled as its base,
     * only after a subject). Never plain: an obligation ("I have to have the invitations sent"), words that leave the
     * status open ("done for now"), and a status word or past form that someone else is made to bring about ("please
     * have the spot reserved", "have T3 done"). False for null or blank words.
     */
    static boolean plain(ActionType type, TaskStatus status, String words, Target target, LocalDate today) {
        if (words == null || words.isBlank() || type == null) {
            return false;
        }
        String t = TargetCheck.lowerSameLength(TargetCheck.straightQuotes(words));
        if (PROVISIONAL.matcher(t).find() || OBLIGATION.matcher(t).find()) {
            return false;
        }
        t = NO_LATER_THAN.matcher(t).replaceAll(m -> "by" + " ".repeat(m.group().length() - 2));
        boolean[] covered = new boolean[t.length()];
        boolean move = type == ActionType.RESCHEDULE_GOAL || type == ActionType.UPDATE_TASK;
        boolean dated = move || type == ActionType.ADD_TASK || type == ActionType.CREATE_GOAL;
        if (dated && ActionDates.simple(words, today)) {
            // The one date phrase, read whole, with the word that puts it in place ("to Friday", "by the 4th").
            DateExpressions.simplePhrase(words, today).ifPresent(span -> cover(covered, span.start(), span.end()));
        }
        if (type == ActionType.SET_TASK_STATUS && status == TaskStatus.DONE) {
            coverAmount(t, words, covered, TOOK_LEAD);
        } else if (type == ActionType.UPDATE_TASK) {
            coverAmount(t, words, covered, ESTIMATE_LEAD);
        }
        if (type == ActionType.REFINE_GOAL) {
            for (int[] span : ConversationService.replanSpans(t, target == null ? null : target.goalText())) {
                cover(covered, span[0], span[1]);
            }
        }
        Words targetWords = Words.of(target);
        boolean stated = false;
        List<Token> tokens = tokens(t);
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.foreign()) {
                return false; // a word in another script, or with letters beyond ASCII, is never plain
            }
            if (covered[token.start()]) {
                continue;
            }
            String w = token.word();
            if (type == ActionType.ADD_TASK && targetWords.inNewTitle(w)) {
                continue; // a new task's title is the person's own words, whatever they are
            }
            if (w.equals("about") && targetWords.holds(w) && !numberAfter(tokens, i)) {
                continue; // "I called the bank about the card" for "Call the bank about the card"; not "about 2 hours"
            }
            if (targetWords.namesId(w)) {
                continue; // "replan 68790459": the start of the goal's id names it, though it is all digits
            }
            if (NEVER_PLAIN.contains(w) || DATE_WORD.matcher(w).matches()) {
                return false;
            }
            if (type == ActionType.SET_TASK_STATUS && status != null && STATUS_WORDS.getOrDefault(status, Set.of())
                    .contains(w)) {
                stated |= !causative(tokens, i);
                continue;
            }
            if (type == ActionType.SET_TASK_STATUS && status == TaskStatus.IN_PROGRESS && w.equals("on") && i > 0
                    && PRESENT_BE.contains(tokens.get(i - 1).word())) {
                stated = true; // "I am on T3": the work is under way
                continue;
            }
            if (!allowed(type, status, w, targetWords)) {
                return false;
            }
            if (type == ActionType.SET_TASK_STATUS && status == TaskStatus.DONE
                    && targetWords.statesDone(t, tokens, i)) {
                stated |= !causative(tokens, i);
            }
        }
        return type != ActionType.SET_TASK_STATUS || status == null || stated;
    }

    /** Number words that make "about" before them a hedge on an amount: "about 2 hours", "about two hours". */
    private static final Set<String> NUMBER_WORDS = Set.of("a", "an", "one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "half", "twenty", "thirty", "forty", "couple", "few");

    /** True when the word after {@code i} is a number or a number word ("about 2 hours", "about an hour"). */
    private static boolean numberAfter(List<Token> tokens, int i) {
        if (i + 1 >= tokens.size()) {
            return false;
        }
        String next = tokens.get(i + 1).word();
        return Character.isDigit(next.charAt(0)) || NUMBER_WORDS.contains(next);
    }

    /**
     * True when the status word or past form at {@code i} is something a person has someone else bring about: a
     * causative verb ("have", "get") with an object between it and the word ("please have the spot reserved", "have
     * T3 of the picnic done", "I got the invitations sent"). "I have reserved the spot", "T3 has been done" and "the
     * invitations got sent" have no object between, so they state what was done.
     */
    private static boolean causative(List<Token> tokens, int i) {
        boolean object = false;
        for (int k = i - 1; k >= 0 && k >= i - 8; k--) {
            String w = tokens.get(k).word();
            if (CAUSATIVE.contains(w)) {
                return object;
            }
            if (BE.contains(w)) {
                return false;
            }
            if (!ADVERBS.contains(w)) {
                object = true;
            }
        }
        return false;
    }

    /**
     * Words that tie one part of a sentence to another in a way no part says on its own: an alternative ("T3 or T4 is
     * done", "either", "whether") and a condition joined by "as soon as" or "as long as" ("T4 can start as soon as T3
     * is done"). The date parser cuts clauses at "or" and "as", so a part read alone loses them.
     */
    private static final Pattern TIED = Pattern.compile("(?i)\\b(?:or|nor|either|neither|whether|as\\s+soon\\s+as"
            + "|as\\s+long\\s+as|so\\s+long\\s+as)\\b");

    /**
     * True when the sentence ties none of its parts to another by an alternative or a condition (see {@link #TIED}),
     * so a part of it can be judged on its own words; a change in a sentence that does is never plain.
     */
    static boolean partsStandAlone(String said) {
        return said == null || !TIED.matcher(TargetCheck.straightQuotes(said)).find();
    }

    /**
     * Words anywhere in a sentence that make what it asks for wait on something, take it back or correct it: a
     * condition ("if", "unless", "in case", "provided", "assuming", "only if", "as soon as", "once Anna confirms";
     * "once a week" repeats and is none), a supposition ("imagine", "hypothetically"), a correction ("make that", "I
     * mean", "actually", "scratch that", "sorry", "rather", "no wait", "on second thought") and a retraction ("never
     * mind", "nope", "forget it", "undo that", "just kidding", "not really", "not yet", "I wish", "or not", "said no
     * one").
     */
    private static final Pattern UNSETTLED = Pattern.compile("(?i)\\b(?:if|unless|in\\s+case|provided|providing"
            + "|assuming|suppose|supposing|hypothetically|imagine|as\\s+soon\\s+as|as\\s+long\\s+as|so\\s+long\\s+as"
            + "|once(?!\\s+(?:a|an|per|every|each|more|again|or\\s+twice|in\\s+a\\s+while)\\b)"
            + "|make\\s+that|make\\s+it\\s+(?:t\\d|one|the\\s+other)|i\\s+mean|i\\s+meant|actually|scratch\\s+that|sorry"
            + "|rather|no\\s+wait|wait|on\\s+second\\s+thoughts?|never\\s*mind|nope|nah|forget\\s+(?:it|that|about\\s+it)"
            + "|undo\\s+(?:that|it)|just\\s+kidding|kidding|jk|not\\s+really|not\\s+yet|i\\s+wish|or\\s+not"
            + "|said\\s+no\\s+one|lol\\s+no|in\\s+my\\s+dreams?|not\\s+true)\\b");

    /**
     * True when nothing in the sentence makes what it asks for wait on something, take it back or correct it (see
     * {@link #UNSETTLED}); a sentence that does changes nothing without a question, whatever its other parts say.
     */
    static boolean settled(String said) {
        return said == null || !UNSETTLED.matcher(TargetCheck.straightQuotes(said)).find();
    }

    /**
     * Words anywhere in the words of a new goal or task that take it back, correct it, hedge it or make it wait on
     * something: a negation ("no", "not", "don't", "never"; "no later than" is none), a retraction ("cancel that",
     * "delete this", "ignore it", "disregard", "strike that", "oops", "hold on", "hang on", "my bad", "changed my
     * mind", "just joking", "wrong chat", "wait" before a pause, "stop" at the end), a correction ("correction", "I
     * mean", "actually", "sorry", "rather", "instead"), a condition ("if", "unless", "when", "after", "once", "in
     * case", "provided", "as soon as"; "once a week" repeats and is none) and a hedge ("maybe", "perhaps", "probably",
     * "I think", "not sure").
     */
    private static final Pattern NOT_A_STATEMENT = Pattern.compile("(?i)" + TargetCheck.NEGATION
            + "|\\b(?:cancel|delete|ignore|disregard|strike|scratch|forget|undo|drop|erase|remove)\\s+(?:that|this|it)\\b"
            + "|\\b(?:disregard|oops|whoops|hold\\s+on|hang\\s+on|my\\s+bad|changed?\\s+my\\s+mind"
            + "|change\\s+of\\s+plans?|just\\s+(?:joking|kidding)|joking|kidding|jk|correction|typo"
            + "|wrong\\s+(?:chat|window|one|person)|i\\s+mean|i\\s+meant|actually|sorry|rather|instead|never\\s*mind"
            + "|nope|nah|if|unless|when|whenever|after|in\\s+case|provided|providing|assuming|as\\s+soon\\s+as"
            + "|as\\s+long\\s+as|maybe|perhaps|probably|possibly|i\\s+think|i\\s+guess|not\\s+sure|hopefully|might)\\b"
            + "|\\bonce\\b(?!\\s+(?:a|an|per|every|each|more|again|or\\s+twice|in\\s+a\\s+while)\\b)"
            + "|\\bwait\\b(?=\\s*(?:[,.;:!?]|$))|\\bstop\\b(?=[\\s.!]*$)");
    /** A mark that ends a sentence before the end of the words: ";", "!", "?", or a full stop outside a number. */
    private static final Pattern SENTENCE_END = Pattern.compile("[;!?]|(?<!\\d)\\.|\\.(?!\\d)");
    /** What may close the words of a statement: spaces and closing marks. */
    private static final Pattern CLOSING = Pattern.compile("[\\s.!?;:,)\"']+$");

    /**
     * True when {@code words} are one plain statement, as a new goal's words or a new task's title must be to be used
     * without a question: one sentence and no question (no ".", ";" or "!" before their end, "2.5 km" aside, and no
     * "?" at all), nothing anywhere in them that takes them back, corrects them, hedges them or makes them wait on
     * something (see {@link #NOT_A_STATEMENT} and {@link #settled}), and only words in the Latin script. "new goal:
     * learn piano" is one; "new goal: learn piano. Cancel that.", "learn piano, no, guitar", "learn piano when Anna
     * agrees", "call Anna, cancel that" and "learn the balalaika, нет" are not. False for null or blank words.
     */
    static boolean plainStatement(String words) {
        if (words == null || words.isBlank()) {
            return false;
        }
        String t = TargetCheck.straightQuotes(words).strip();
        String body = CLOSING.matcher(t).replaceAll("");
        if (t.indexOf('?') >= 0 || SENTENCE_END.matcher(body).find() || NOT_A_STATEMENT.matcher(t).find()
                || !settled(t)) {
            return false;
        }
        return t.codePoints().noneMatch(c -> Character.isLetter(c)
                && Character.UnicodeScript.of(c) != Character.UnicodeScript.LATIN);
    }

    /**
     * Words that refer to what the system suggested: "like you suggested", "as you said", "as suggested", "as was
     * proposed", "your suggestion", "the idea".
     */
    private static final Pattern SUGGESTION_REFERENCE = Pattern.compile("(?i)\\b(?:like|as)\\s+(?:you\\s+|was\\s+)?"
            + "(?:suggested|said|proposed|recommended|advised|mentioned)\\b"
            + "|\\b(?:per|following)\\s+your\\s+(?:suggestion|advice|idea)\\b"
            + "|\\b(?:your|the)\\s+(?:suggestion|idea|proposal|recommendation)\\b");

    /**
     * The words with every reference to what the system suggested blanked (see {@link #SUGGESTION_REFERENCE}), every
     * character in its place: "push T3 of the picnic like you suggested" is judged as "push T3 of the picnic".
     */
    static String withoutSuggestionReference(String words) {
        if (words == null) {
            return null;
        }
        Matcher m = SUGGESTION_REFERENCE.matcher(TargetCheck.straightQuotes(words));
        StringBuilder out = new StringBuilder(words);
        while (m.find()) {
            for (int k = m.start(); k < m.end(); k++) {
                out.setCharAt(k, ' ');
            }
        }
        return out.toString();
    }

    /** A task key ("T10") or a word of letters then digits ("r11", "pm7") that names something, not an amount. */
    private static final Pattern LABEL = Pattern.compile("(?i)\\b[a-z]+\\d+[a-z0-9]*\\b");

    /**
     * The words with every task key and every word of letters then digits ("T10", "r11", "pm7") blanked, every
     * character in its place: the words the amounts of time are read from, so the digits of a name are never read as
     * an amount ("T10 of QA r11 is done, it took 2 hours" holds one amount).
     */
    static String withoutLabels(String words) {
        if (words == null) {
            return null;
        }
        Matcher m = LABEL.matcher(words);
        StringBuilder out = new StringBuilder(words);
        while (m.find()) {
            for (int k = m.start(); k < m.end(); k++) {
                out.setCharAt(k, ' ');
            }
        }
        return out.toString();
    }

    /** Courtesy that may stand anywhere in a plain sentence: "ok, thanks", "yes please", "great". */
    private static final Set<String> COURTESY = Set.of("ok", "okay", "thanks", "thank", "you", "please", "yes", "yeah",
            "yep", "great", "cool", "nice", "perfect", "sure", "alright", "hi", "hello", "hey", "cheers", "thx");
    /** Words that join two plain parts of a sentence: "move the picnic to Friday and mark T3 done". */
    private static final Set<String> JOINERS = Set.of("and", "then", "also", "but", "so", "plus");

    /**
     * True when the whole sentence is plain: every word of it lies in one of {@code plainParts}, the parts of the
     * sentence that were judged plain for one change each (see {@link #plain}; each part is the sentence with the
     * rest blanked, every character in its place), or is a function word, a word of courtesy ("ok", "thanks",
     * "please", "yes") or a word that joins two parts ("and", "then"). Any other word belongs to a part no change is
     * plain for: a condition ("if it rains, move the picnic to December 6 and mark T3 done"), a correction or a
     * retraction in a clause of its own ("mark T3 done. Never mind."), or a statement about something else ("mark T3
     * done and move the picnic, my sister leaves on December 11"). Then no change of the sentence is applied without
     * a question.
     */
    static boolean wholePlain(String said, List<String> plainParts) {
        if (said == null) {
            return false;
        }
        String t = TargetCheck.lowerSameLength(TargetCheck.straightQuotes(said));
        for (Token token : tokens(t)) {
            if (token.foreign()) {
                return false; // "mark T3 done, нет": a word the kernel cannot read is never plain
            }
            int at = token.start();
            boolean inPart = plainParts.stream().anyMatch(p -> p != null && p.length() == t.length()
                    && at < p.length() && p.charAt(at) != ' ');
            if (!inPart && !FUNCTION.contains(token.word()) && !COURTESY.contains(token.word())
                    && !JOINERS.contains(token.word())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Words that state something about a task's progress: a status word in any form, a word of waiting or holding,
     * a negation (which may state that it cannot go on), being "on" something ("I am on T3"), and words such as
     * "ready", "over", "sorted", "handled".
     */
    private static final Pattern STATUS_HINT = Pattern.compile("\\b(?:done|finish\\w*|complet\\w*|did|start\\w*"
            + "|began|begun|work\\w*|progress\\w*|block\\w*|stuck|wait\\w*|hold\\w*|paus\\w*|halt\\w*|todo|to-do"
            + "|reopen\\w*|open\\w*|again|ready|over|through|wrapped|sorted|handled|care|off|crossed|ticked|checked"
            + "|not|no|never|cannot|can't|won't|cant|wont)\\b|n't\\b"
            + "|\\b(?:am|is|are|was|were|been|i'm|im)\\s+on\\b");

    /**
     * True when the words state nothing about a task's progress: no status word or hint of one (see
     * {@link #STATUS_HINT}), no word ending in "ed" or "ing" ("reading the contract" may say it is under way) and no
     * irregular past ("great, the tiles look good now", "T3 of the picnic"). No status change is offered for such
     * words.
     */
    static boolean statesNoStatus(String words) {
        if (words == null || words.isBlank()) {
            return true;
        }
        String t = TargetCheck.lowerSameLength(TargetCheck.straightQuotes(words));
        if (STATUS_HINT.matcher(t).find()) {
            return false;
        }
        for (Token token : tokens(t)) {
            String w = token.word();
            if (w.endsWith("ed") || w.endsWith("ing") || ConversationService.IRREGULAR_PAST.containsKey(w)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The status a plain part of a sentence states for a task (see {@link #plain}), when it is plain for exactly one
     * of done, in progress and blocked: the reading offered when the model proposed another status. Empty otherwise.
     */
    static Optional<TaskStatus> plainStatus(String words, Target target, LocalDate today) {
        List<TaskStatus> plain = new ArrayList<>();
        for (TaskStatus s : List.of(TaskStatus.DONE, TaskStatus.IN_PROGRESS, TaskStatus.BLOCKED)) {
            if (plain(ActionType.SET_TASK_STATUS, s, words, target, today)) {
                plain.add(s);
            }
        }
        return plain.size() == 1 ? Optional.of(plain.getFirst()) : Optional.empty();
    }

    /**
     * True when the word is plain for the change, other than as a date word or a status word. A word that states a
     * status (see {@link #ANY_STATUS_WORD}) never names the target of a status change, whatever the title holds.
     */
    private static boolean allowed(ActionType type, TaskStatus status, String w, Words target) {
        boolean names = target.names(w) && !(type == ActionType.SET_TASK_STATUS && ANY_STATUS_WORD.contains(w));
        if (FUNCTION.contains(w) || COURTESY.contains(w) || TARGET_NOUNS.contains(w) || names) {
            return true;
        }
        boolean taskAction = type == ActionType.SET_TASK_STATUS || type == ActionType.UPDATE_TASK;
        if (taskAction && TASK_KEY.matcher(w).matches()) {
            return true;
        }
        return switch (type) {
            case SET_TASK_STATUS -> STATUS_VERBS.contains(w)
                    || status == TaskStatus.IN_PROGRESS && w.equals("on");
            case UPDATE_TASK -> MOVE_WORDS.contains(w) || ESTIMATE_WORDS.contains(w);
            case RESCHEDULE_GOAL -> MOVE_WORDS.contains(w);
            case ADD_TASK -> ADD_WORDS.contains(w);
            case REOPEN_GOAL -> REOPEN_WORDS.contains(w);
            case REFINE_GOAL -> REFINE_WORDS.contains(w);
            default -> false;
        };
    }

    /** Words after an amount that make it every task's own: "they took 2 hours each", "an hour apiece". */
    private static final Pattern EACH_AFTER = Pattern.compile("^\\s*(?:each(?:\\s+one)?|apiece|a\\s+piece"
            + "|per\\s+task)\\b");

    /**
     * Covers the one amount of time of the words with the words that lead to it ({@code lead}, right before it in its
     * clause), when it is the only amount, read with a number, plain by {@link Durations#simple} (or said plainly for
     * each task, see {@link Durations#each}: "they took 2 hours each", with the word "each" covered too), and nothing
     * but punctuation follows it in its clause. Covers nothing otherwise, so the amount's words are judged as words.
     */
    private static void coverAmount(String t, String words, boolean[] covered, Pattern lead) {
        String amountWords = withoutLabels(words);
        List<Durations.Amount> amounts = Durations.amounts(amountWords);
        boolean each = Durations.each(amountWords).isPresent();
        if (amounts.size() != 1 || amounts.getFirst().vague() || !Durations.simple(amountWords) && !each) {
            return;
        }
        Durations.Amount amount = amounts.getFirst();
        int start = Math.max(0, Math.min(amount.start(), t.length()));
        int end = Math.max(start, Math.min(amount.end(), t.length()));
        int clauseFrom = DateExpressions.clauseStart(t, start);
        Matcher m = lead.matcher(t.substring(Math.min(clauseFrom, start), start));
        if (!m.find()) {
            return;
        }
        if (each) {
            Matcher after = EACH_AFTER.matcher(t.substring(end));
            if (after.find()) {
                end += after.end();
            }
        }
        int clauseTo = Math.max(end, DateExpressions.clauseEnd(t, end));
        if (!t.substring(end, Math.min(t.length(), clauseTo)).replaceAll("[\\s.!,;:]+", "").isEmpty()) {
            return; // "it took 2 hours too many", "2 hours past the estimate": something follows the amount
        }
        // Only the amount and the words that lead to it are covered; a word of completion before them ("finished
        // T3 in 2 hours") is judged, and states the status, as any other word.
        int from = m.start();
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) != null) {
                from = m.start(g);
                break;
            }
        }
        cover(covered, Math.min(clauseFrom, start) + from, end);
    }

    private static void cover(boolean[] covered, int from, int to) {
        for (int k = Math.max(0, from); k < Math.min(covered.length, to); k++) {
            covered[k] = true;
        }
    }

    /** The words of a lower-case text with their contractions read as words (see {@link Token}). */
    private static List<Token> tokens(String t) {
        List<Token> tokens = new ArrayList<>();
        Matcher m = WORD.matcher(t);
        while (m.find()) {
            String w = m.group();
            int s = m.start();
            int e = m.end();
            if (w.endsWith("'")) {
                w = w.substring(0, w.length() - 1); // "the guests' list"
            }
            int apostrophe = w.indexOf('\'');
            if (apostrophe < 0) {
                switch (w) {
                    case "cannot" -> {
                        tokens.add(new Token("can", s, e));
                        tokens.add(new Token("not", s, e));
                    }
                    case "dont", "cant", "wont", "didnt", "doesnt", "isnt", "wasnt", "arent", "shouldnt", "couldnt",
                         "wouldnt", "havent", "hasnt", "werent" -> tokens.add(new Token("not", s, e));
                    case "im" -> {
                        tokens.add(new Token("i", s, e));
                        tokens.add(new Token("am", s, e));
                    }
                    case "ive" -> {
                        tokens.add(new Token("i", s, e));
                        tokens.add(new Token("have", s, e));
                    }
                    default -> tokens.add(new Token(w, s, e));
                }
                continue;
            }
            String head = w.substring(0, apostrophe);
            String tail = w.substring(apostrophe + 1);
            if (w.endsWith("n't")) {
                String base = w.substring(0, w.length() - 3);
                tokens.add(new Token(switch (base) {
                    case "ca" -> "can";
                    case "wo" -> "will";
                    case "sha" -> "shall";
                    default -> base;
                }, s, e));
                tokens.add(new Token("not", s, e));
                continue;
            }
            tokens.add(new Token(head, s, e));
            switch (tail) {
                case "m" -> tokens.add(new Token("am", s, e));
                case "re" -> tokens.add(new Token("are", s, e));
                case "ve" -> tokens.add(new Token("have", s, e));
                case "ll" -> tokens.add(new Token("will", s, e));
                case "d" -> tokens.add(new Token("would", s, e));
                case "s" -> {
                    if (Set.of("it", "that", "this", "there", "what", "he", "she", "who", "where", "here", "let")
                            .contains(head)) {
                        tokens.add(new Token(head.equals("let") ? "us" : "is", s, e));
                    }
                }
                default -> tokens.add(new Token(tail, s, e));
            }
        }
        return tokens;
    }

    /**
     * The words that name a target: every word of its texts and the stem of each (see {@link TargetCheck#stem}), so
     * "reserving" and "reserved" name "Reserve the spot"; the goal's id; and for a task title the irregular past forms
     * of its verbs ("sent" for "Send the invitations", see {@link ConversationService#IRREGULAR_PAST}). The verb that
     * opens the task's title ("reserve" of "Reserve the spot") is kept apart: only its past form states that the task
     * was done.
     */
    private record Words(Set<String> words, Set<String> stems, Set<String> titleStems, Set<String> newTitle,
                         String goalId, String titleVerb) {

        static Words of(Target target) {
            Set<String> words = new HashSet<>();
            Set<String> stems = new HashSet<>();
            Set<String> titleStems = new HashSet<>();
            Set<String> newTitle = new HashSet<>();
            String verb = "";
            if (target != null) {
                for (String text : Arrays.asList(target.goalText(), target.taskTitle(), target.newTitle())) {
                    add(text, words, stems);
                }
                for (String text : Arrays.asList(target.taskTitle(), target.newTitle())) {
                    add(text, new HashSet<>(), titleStems);
                }
                add(target.newTitle(), newTitle, new HashSet<>());
                String title = TargetCheck.normalise(target.taskTitle());
                verb = title.isEmpty() ? "" : title.split(" ")[0];
            }
            return new Words(words, stems, titleStems, newTitle,
                    target == null || target.goalId() == null ? "" : target.goalId().toLowerCase(Locale.ROOT), verb);
        }

        /** True when the word is a word of a new task's title as the model gave it. */
        boolean inNewTitle(String w) {
            return newTitle.contains(TargetCheck.normalise(w).replace(" ", ""));
        }

        /**
         * True when the word is the start of the goal's id as a person types it ("68790459", "3f9a"): hexadecimal, at
         * least 8 characters or holding a digit and at least 4, as {@link TargetCheck#namesId} reads an id.
         */
        boolean namesId(String w) {
            return !goalId.isEmpty() && HEX.matcher(w).matches() && (w.length() >= 8
                    || w.chars().anyMatch(Character::isDigit)) && goalId.replace("-", "").startsWith(w);
        }

        /**
         * True when one of the target's texts holds the word as written ("about" in "Call the bank about the card").
         */
        boolean holds(String w) {
            return words.contains(TargetCheck.normalise(w).replace(" ", ""));
        }

        private static void add(String text, Set<String> words, Set<String> stems) {
            for (String w : TargetCheck.normalise(text).split(" ")) {
                if (!w.isEmpty()) {
                    words.add(w);
                    stems.add(TargetCheck.stem(w));
                }
            }
        }

        /** True when the word names the target. */
        boolean names(String w) {
            String plainWord = TargetCheck.normalise(w).replace(" ", "");
            if (plainWord.isEmpty()) {
                return false;
            }
            if (words.contains(plainWord) || stems.contains(TargetCheck.stem(plainWord))) {
                return true;
            }
            if (HEX.matcher(plainWord).matches() && !goalId.isEmpty() && goalId.startsWith(plainWord)) {
                return true;
            }
            String base = ConversationService.IRREGULAR_PAST.get(plainWord);
            return base != null && (titleStems.contains(TargetCheck.stem(base)) || titleStems.contains(base));
        }

        /**
         * True when the word at {@code i} of {@code t} states that the person did the task: the past form of the verb
         * that opens the task's title, "reserved" for "Reserve the spot", "planned" for "Plan the route", "tidied" for
         * "Tidy the room", "sent" for "Send the invitations", standing where it states what was done (see
         * {@link #opensStatement}): "I reserved the spot", "reserved the spot", "the spot was reserved"; "Sam replied"
         * for "Reply to Sam" and "mom called" for "Call mom" say what someone else did. A past spelled as its base
         * ("read", "cut", "put", "set", "shut") counts only after a subject ("I read the contract", "we've cut the
         * hedge"): "please read the contract" asks for it. Other words of the title that end in "ed" ("shed", "bed",
         * "bread") state nothing.
         */
        boolean statesDone(String t, List<Token> tokens, int i) {
            String w = TargetCheck.normalise(tokens.get(i).word()).replace(" ", "");
            if (titleVerb.isEmpty() || w.isEmpty()) {
                return false;
            }
            String base = ConversationService.IRREGULAR_PAST.get(w);
            if (base != null) {
                if (!base.equals(titleVerb)) {
                    return false;
                }
                return base.equals(w) ? afterSubject(tokens, i) : opensStatement(t, tokens, i);
            }
            return regularPast(w, titleVerb) && opensStatement(t, tokens, i);
        }

        /**
         * True when the past form at {@code i} states what was done: it opens its clause (nothing but adverbs before
         * it since the start or a punctuation mark), or a subject pronoun, a perfect auxiliary or a form of "be"
         * stands before it, adverbs aside ("I just reserved", "we've sent", "the spot has been reserved"). A noun
         * right before it ("Sam replied", "the plumber called") makes it what someone else did.
         */
        private static boolean opensStatement(String t, List<Token> tokens, int i) {
            for (int k = i - 1; k >= 0; k--) {
                Token before = tokens.get(k);
                int gapFrom = Math.min(before.end(), tokens.get(k + 1).start());
                if (CLAUSE_MARK.matcher(t.substring(gapFrom, tokens.get(k + 1).start())).find()) {
                    return true;
                }
                String w = before.word();
                if (PAST_SUBJECTS.contains(w) || BE.contains(w)) {
                    return true;
                }
                if (!ADVERBS.contains(w)) {
                    return false;
                }
            }
            return true;
        }

        /** True when a subject or a perfect auxiliary stands before the word at {@code i}, adverbs aside. */
        private static boolean afterSubject(List<Token> tokens, int i) {
            for (int k = i - 1; k >= 0; k--) {
                String w = tokens.get(k).word();
                if (SUBJECTS.contains(w)) {
                    return true;
                }
                if (!ADVERBS.contains(w)) {
                    return false;
                }
            }
            return false;
        }

        /** True when {@code w} is the regular past of {@code verb}: "reserved", "planned", "tidied", "called". */
        private static boolean regularPast(String w, String verb) {
            if (!w.endsWith("ed") || verb.length() < 2) {
                return false;
            }
            int n = verb.length();
            char last = verb.charAt(n - 1);
            return w.equals(verb + "ed") || last == 'e' && w.equals(verb + "d")
                    || last == 'y' && "aeiou".indexOf(verb.charAt(n - 2)) < 0
                    && w.equals(verb.substring(0, n - 1) + "ied")
                    || "aeiouwxy".indexOf(last) < 0 && w.equals(verb + last + "ed");
        }
    }
}
