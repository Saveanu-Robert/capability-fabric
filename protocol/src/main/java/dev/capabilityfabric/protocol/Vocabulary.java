package dev.capabilityfabric.protocol;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shared vocabulary capabilities use to declare what they consume and produce. The resolver orders plan steps by
 * matching these tokens; the kernel rejects writes to tokens a capability did not declare.
 *
 * <p>Core tokens are fixed. Annotation tokens ({@code task.ann.<ns>.<name>},
 * {@code goal.ann.<ns>.<name>}) are open: a new capability can introduce a new kind of knowledge without any kernel
 * change, as long as it declares a JSON Schema for the value.
 */
public final class Vocabulary {

    public static final String TASKS = "tasks";

    public static final String GOAL_TEXT = "goal.text";
    public static final String GOAL_DUE_DATE = "goal.dueDate";
    public static final String GOAL_TODAY = "goal.today";

    public static final String TASK_TITLE = "task.title";
    public static final String TASK_DESCRIPTION = "task.description";
    public static final String TASK_STATUS = "task.status";
    public static final String TASK_DUE_DATE = "task.dueDate";
    public static final String TASK_DEPENDS_ON = "task.dependsOn";
    public static final String TASK_EFFORT_HOURS = "task.effortHours";
    public static final String TASK_PRIORITY = "task.priority";
    public static final String TASK_RANK = "task.rank";
    public static final String TASK_ACTUAL_HOURS = "task.actualHours";

    public static final String TASK_ANN_PREFIX = "task.ann.";
    public static final String GOAL_ANN_PREFIX = "goal.ann.";
    /** The person's profile: computed by the kernel from evidence, readable by capabilities, never written by them. */
    public static final String PERSON_PREFIX = "person.";
    public static final String PERSON_DAILY_HOURS = "person.dailyHours";
    public static final String PERSON_ESTIMATE_BIAS = "person.estimateBias";
    public static final String PERSON_WORKING_DAYS = "person.workingDays";
    /** Public holidays (ISO dates) configured once in the kernel; every calendar-aware capability skips them. */
    public static final String PERSON_HOLIDAYS = "person.holidays";

    public static final Set<String> CORE = Set.of(TASKS, GOAL_TEXT, GOAL_DUE_DATE, GOAL_TODAY,
            TASK_TITLE, TASK_DESCRIPTION, TASK_STATUS, TASK_DUE_DATE, TASK_DEPENDS_ON,
            TASK_EFFORT_HOURS, TASK_PRIORITY, TASK_RANK, TASK_ACTUAL_HOURS);

    /** Tokens whose change alters goal structure (autonomy level L2). */
    public static final Set<String> STRUCTURAL = Set.of(TASKS, TASK_DEPENDS_ON);

    private static final Pattern ANNOTATION_KEY = Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-zA-Z][a-zA-Z0-9]*)+");
    private static final Pattern PERSON_NAME = Pattern.compile("[a-z][a-zA-Z0-9]*");

    private Vocabulary() {
    }

    /**
     * A core token, or a task or goal annotation token with a valid key ({@code task.ann.<key>},
     * {@code goal.ann.<key>}), or a profile token ({@code person.<name>}).
     */
    public static boolean isValidToken(String token) {
        if (CORE.contains(token)) {
            return true;
        }
        if (token.startsWith(TASK_ANN_PREFIX)) {
            return isValidAnnotationKey(token.substring(TASK_ANN_PREFIX.length()));
        }
        if (token.startsWith(GOAL_ANN_PREFIX)) {
            return isValidAnnotationKey(token.substring(GOAL_ANN_PREFIX.length()));
        }
        if (token.startsWith(PERSON_PREFIX)) {
            return PERSON_NAME.matcher(token.substring(PERSON_PREFIX.length())).matches();
        }
        return false;
    }

    /** At least two dot-separated parts, each starting with a letter: {@code risk.deadline}. */
    public static boolean isValidAnnotationKey(String key) {
        return key != null && ANNOTATION_KEY.matcher(key).matches();
    }

    public static String taskAnnotation(String key) {
        return TASK_ANN_PREFIX + key;
    }

    public static String goalAnnotation(String key) {
        return GOAL_ANN_PREFIX + key;
    }

    public static boolean isTaskAnnotation(String token) {
        return token.startsWith(TASK_ANN_PREFIX);
    }

    public static boolean isGoalAnnotation(String token) {
        return token.startsWith(GOAL_ANN_PREFIX);
    }

    /** {@code calendar.workdaysLeft} for {@code task.ann.calendar.workdaysLeft}. */
    public static String annotationKey(String token) {
        if (isTaskAnnotation(token)) {
            return token.substring(TASK_ANN_PREFIX.length());
        }
        if (isGoalAnnotation(token)) {
            return token.substring(GOAL_ANN_PREFIX.length());
        }
        throw new IllegalArgumentException("not an annotation token: " + token);
    }
}
