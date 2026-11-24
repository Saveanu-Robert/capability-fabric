package dev.capabilityfabric.agents.coach;

import dev.capabilityfabric.algorithms.DeadlineRisk;
import dev.capabilityfabric.algorithms.WorkdayCalendar;
import dev.capabilityfabric.protocol.Conversation.Action;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.TaskStatus;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Decides, deterministically, when the system should speak first and what it should suggest. The model only
 * phrases the message; whether there is a message, and what accepting it does, comes from here.
 */
public final class Nudges {

    static final double DEFAULT_DAILY_HOURS = 6;

    private Nudges() {
    }

    /** Stands for the goal's name in a {@link Nudge#template()}; {@link Nudge#fill} puts the name in. */
    static final String GOAL = "[goal]";
    private static final Pattern PLACEHOLDER = Pattern.compile(Pattern.quote(GOAL) + "|(?<![\\w.])T\\d+(?!\\d)");

    /**
     * One thing worth telling the person. {@code template} is the complete message in plain words with names left
     * out: {@link #GOAL} for the goal's name, task keys without their titles. It is the only text the model phrases
     * from, so the names people wrote never reach it and never come back reworded. {@code titles} maps
     * {@link #GOAL} and each task key to the name that {@link #fill} puts back.
     */
    public record Nudge(Inbox.Kind kind, String goalId, String template, Map<String, String> titles,
                        List<Action> suggestions, String dedupeKey) {

        public Nudge {
            titles = titles == null ? Map.of() : Map.copyOf(titles);
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }

        /** The complete message with the names in: what is posted when no model phrases it. */
        public String facts() {
            return fill(template);
        }

        /**
         * {@code text} with the names put back: every {@link #GOAL} becomes the goal's name in quotes, and the first
         * mention of each task key gets the task's title in quotes after it. Inserted names are never read again,
         * so a title that contains a key or the placeholder changes nothing.
         */
        public String fill(String text) {
            Matcher m = PLACEHOLDER.matcher(text);
            StringBuilder out = new StringBuilder();
            Set<String> named = new HashSet<>();
            while (m.find()) {
                String token = m.group();
                String title = titles.get(token);
                String replacement = token.equals(GOAL) ? (title == null ? "the goal" : "\"" + title + "\"")
                        : title != null && named.add(token) ? token + " \"" + title + "\"" : token;
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(out);
            return out.toString();
        }
    }

    /** The names {@link Nudge#fill} puts back: the goal's and those of {@code tasks}. */
    private static Map<String, String> titles(GoalContext goal, List<TaskView> tasks) {
        Map<String, String> titles = new HashMap<>();
        if (goal.goal().text() != null && !goal.goal().text().isBlank()) {
            titles.put(GOAL, goal.goal().text());
        }
        tasks.stream().filter(t -> t.title() != null && !t.title().isBlank()).forEach(t -> titles.put(t.key(), t.title()));
        return titles;
    }

    /** Checks worth running whenever the goal's facts change. */
    public static List<Nudge> onChange(GoalContext goal) {
        List<Nudge> nudges = new ArrayList<>();
        if (!open(goal)) {
            return nudges;
        }
        deadlineRisk(goal).ifPresent(nudges::add);
        nudges.addAll(blockedChains(goal));
        return nudges;
    }

    /** Checks that depend on the calendar rather than on a change: run by the periodic sweep. */
    public static List<Nudge> onSweep(GoalContext goal, Instant updatedAt, int staleDays) {
        List<Nudge> nudges = new ArrayList<>(onChange(goal));
        if (!open(goal)) {
            return nudges;
        }
        dueToday(goal).ifPresent(nudges::add);
        overdue(goal).ifPresent(nudges::add);
        stale(goal, updatedAt, staleDays).ifPresent(nudges::add);
        return nudges;
    }

    private static boolean open(GoalContext goal) {
        return goal.goal() != null && goal.goal().status() != GoalStatus.CANCELLED
                && goal.goal().status() != GoalStatus.COMPLETED;
    }

    /** Tasks with their own due date named in one deadline message at most; the rest wait for the next one. */
    static final int MAX_LATE_TASKS = 3;

    /**
     * The deadline no longer fits the person's pace, judged with the shared {@link DeadlineRisk} algorithm: all open
     * work against the goal's deadline (one person does it all, so independent tasks add up) and each task with an
     * earlier due date of its own against that date (with the open work it waits on). A task without an estimate is
     * never counted as zero: when the estimated part already does not fit the message says "at least" and names the
     * unestimated tasks; when it does fit, the risk is unknown and nothing is said until the estimates arrive (the
     * coach looks again on every task update).
     *
     * <p>One message suggests one kind of change. When the goal's deadline is at risk it suggests moving the
     * deadline alone: the kernel moves the open tasks' dates with it, so task dates computed before that move would
     * be wrong after it. Only when the deadline still fits does it suggest moving the tasks that are late for their
     * own dates.
     */
    static Optional<Nudge> deadlineRisk(GoalContext goal) {
        LocalDate today = today(goal);
        LocalDate due = goal.goal().dueDate();
        if (due == null || due.isBefore(today) || openTasks(goal).isEmpty()) {
            return Optional.empty();
        }
        double perDay = dailyHours(goal);
        WorkdayCalendar calendar = calendar(goal);
        DeadlineRisk risk = new DeadlineRisk(calendar, perDay);
        DeadlineRisk.Assessment together = risk.assessTogether(goal.tasks(), due, today);
        boolean goalAtRisk = together.level() == DeadlineRisk.Level.HIGH;
        List<DeadlineRisk.Assessment> late = lateTasks(goal, due, today, risk);
        if (!goalAtRisk && late.isEmpty()) {
            return Optional.empty();
        }
        Map<String, TaskView> byId = goal.tasksById();
        List<String> facts = new ArrayList<>();
        List<Action> suggestions = new ArrayList<>();
        List<TaskView> named = new ArrayList<>();
        if (goalAtRisk) {
            LocalDate fits = firstFit(today, together.remainingHours(), perDay, calendar);
            boolean partial = !together.unestimated().isEmpty();
            facts.add(GOAL + " has " + (partial ? "at least " : "about ")
                    + hours(together.remainingHours()) + " h of open work, but only "
                    + hours(together.availableHours(perDay)) + " h of working time is left before " + due + " ("
                    + hours(perDay) + " h a day on " + days(goal) + "). Moving the deadline to " + fits
                    + " would fit the " + (partial ? "estimated " : "") + "work.");
            if (partial) {
                List<TaskView> unestimated = together.unestimated().stream().map(byId::get).toList();
                named.addAll(unestimated);
                facts.add(keys(unestimated) + (unestimated.size() == 1 ? " has" : " have") + " no estimate yet.");
            }
            suggestions.add(new Action(ActionType.RESCHEDULE_GOAL, goal.goal().id(), null, null, fits, null, null, null,
                    "the open work does not fit before " + due, null));
        } else {
            for (DeadlineRisk.Assessment a : late) {
                TaskView t = byId.get(a.taskId());
                named.add(t);
                LocalDate fits = firstFit(today, a.remainingHours(), perDay, calendar);
                facts.add(t.key() + " is due " + t.dueDate() + ", but "
                        + (a.unestimated().isEmpty() ? "about " : "at least ") + hours(a.remainingHours())
                        + " h of work is needed before then and only " + hours(a.availableHours(perDay))
                        + " h is left; " + fits + " would fit.");
                suggestions.add(new Action(ActionType.UPDATE_TASK, goal.goal().id(), t.key(), null, fits, null, null,
                        null, "its work does not fit before " + t.dueDate(), null));
            }
        }
        // The key names the deadlines, not the suggested dates: the suggestions move with each passing day, the
        // observation ("this deadline does not fit") does not. The person's capacity is part of the observation:
        // after "I only have 2 hours a day" it is a new message that replaces the old one.
        String lateKey = goalAtRisk ? "" : late.stream().map(a -> a.taskId() + "@" + byId.get(a.taskId()).dueDate())
                .collect(Collectors.joining(","));
        return Optional.of(new Nudge(Inbox.Kind.DEADLINE_RISK, goal.goal().id(), String.join(" ", facts),
                titles(goal, named), suggestions, "risk:" + goal.goal().id() + ":" + due + ":" + hours(perDay) + ":"
                + days(goal).hashCode() + (lateKey.isEmpty() ? "" : ":" + Integer.toHexString(lateKey.hashCode()))));
    }

    /** Open tasks due before the goal's deadline whose own work does not fit before their date, earliest first. */
    private static List<DeadlineRisk.Assessment> lateTasks(GoalContext goal, LocalDate due, LocalDate today,
                                                           DeadlineRisk risk) {
        Map<String, TaskView> byId = goal.tasksById();
        return risk.assess(goal.tasks(), today).values().stream()
                .filter(a -> a.level() == DeadlineRisk.Level.HIGH)
                .filter(a -> byId.get(a.taskId()).dueDate().isBefore(due))
                .sorted(Comparator.comparing((DeadlineRisk.Assessment a) -> byId.get(a.taskId()).dueDate())
                        .thenComparing(a -> byId.get(a.taskId()).key()))
                .limit(MAX_LATE_TASKS)
                .toList();
    }

    /**
     * The first date by which {@code hours} of work fit at the person's pace, counting today when it is a working
     * day (as {@link DeadlineRisk} does) and every working day after it. Scans ten years at most.
     */
    private static LocalDate firstFit(LocalDate today, double hours, double perDay, WorkdayCalendar calendar) {
        int neededDays = Math.max(1, (int) Math.ceil(hours / perDay));
        LocalDate fits = today;
        int counted = calendar.isWorkday(today) ? 1 : 0;
        for (int scanned = 0; counted < neededDays && scanned < 3660; scanned++) {
            fits = fits.plusDays(1);
            if (calendar.isWorkday(fits)) {
                counted++;
            }
        }
        return fits;
    }

    /** One message per blocked task that open tasks wait on, suggesting to plan the goal again; repeated weekly. */
    static List<Nudge> blockedChains(GoalContext goal) {
        List<TaskView> open = openTasks(goal);
        List<Nudge> nudges = new ArrayList<>();
        for (TaskView t : open) {
            if (t.status() != TaskStatus.BLOCKED) {
                continue;
            }
            List<TaskView> waiting = open.stream().filter(o -> o.dependsOn().contains(t.id())).toList();
            if (waiting.isEmpty()) {
                continue;
            }
            String facts = t.key() + " is blocked, and " + keys(waiting) + " cannot start until it is done. Planning "
                    + GOAL + " again could find a way around it.";
            List<TaskView> named = new ArrayList<>(waiting);
            named.add(t);
            nudges.add(new Nudge(Inbox.Kind.BLOCKED_CHAIN, goal.goal().id(), facts, titles(goal, named),
                    List.of(new Action(ActionType.REFINE_GOAL, goal.goal().id(), null, null, null, null, null, null,
                            "plan around the blocked task " + t.key(), null)),
                    // Weekly: a chain that stays blocked is mentioned again next week, not every day.
                    "blocked:" + t.id() + ":" + waiting.size() + ":" + today(goal).with(DayOfWeek.MONDAY)));
        }
        return nudges;
    }

    /** The open tasks due today, once a day; nothing to accept. */
    static Optional<Nudge> dueToday(GoalContext goal) {
        LocalDate today = today(goal);
        List<TaskView> due = openTasks(goal).stream().filter(t -> today.equals(t.dueDate())).toList();
        if (due.isEmpty()) {
            return Optional.empty();
        }
        String facts = "Due today for " + GOAL + ": " + list(due) + ".";
        return Optional.of(new Nudge(Inbox.Kind.DUE_TODAY, goal.goal().id(), facts, titles(goal, due),
                List.of(), "today:" + goal.goal().id() + ":" + today));
    }

    /** Overdue tasks move to the next working day; if that is past the goal's deadline, the deadline moves too. */
    static Optional<Nudge> overdue(GoalContext goal) {
        LocalDate today = today(goal);
        List<TaskView> late = openTasks(goal).stream()
                .filter(t -> t.dueDate() != null && t.dueDate().isBefore(today)).toList();
        if (late.isEmpty()) {
            return Optional.empty();
        }
        WorkdayCalendar calendar = calendar(goal);
        LocalDate next = today;
        while (!calendar.isWorkday(next)) {
            next = next.plusDays(1);
        }
        List<Action> suggestions = new ArrayList<>();
        LocalDate goalDue = goal.goal().dueDate();
        String extra = "";
        if (goalDue != null && goalDue.isBefore(next)) {
            suggestions.add(new Action(ActionType.RESCHEDULE_GOAL, goal.goal().id(), null, null, next, null, null, null,
                    "overdue work moved past the deadline", null));
            extra = " The goal's deadline (" + goalDue + ") would move to " + next + " as well.";
        }
        for (TaskView t : late) {
            suggestions.add(new Action(ActionType.UPDATE_TASK, goal.goal().id(), t.key(), null, next, null, null, null,
                    "overdue since " + t.dueDate(), null));
        }
        String facts = list(late) + (late.size() == 1 ? " is" : " are") + " overdue in " + GOAL
                + ". I can move " + (late.size() == 1 ? "it" : "them") + " to " + next + "." + extra;
        String ids = late.stream().map(TaskView::id).sorted().collect(Collectors.joining(","));
        // Dated: an overdue message is about today; tomorrow's suggestion (a later date) is a new message.
        return Optional.of(new Nudge(Inbox.Kind.OVERDUE, goal.goal().id(), facts, titles(goal, late),
                suggestions, "overdue:" + goal.goal().id() + ":" + today + ":" + Integer.toHexString(ids.hashCode())));
    }

    /** {@link #stale(GoalContext, Instant, int, ZoneId)} in this machine's time zone. */
    static Optional<Nudge> stale(GoalContext goal, Instant updatedAt, int staleDays) {
        return stale(goal, updatedAt, staleDays, ZoneId.systemDefault());
    }

    /**
     * A goal nobody has touched for {@code staleDays} of the person's working days (days off do not make a goal
     * forgotten). The last change is read as a date in {@code zone}, the zone the kernel's "today" is in, so both
     * dates are on the same calendar. Running goals (active, or degraded after a failed step) qualify.
     */
    static Optional<Nudge> stale(GoalContext goal, Instant updatedAt, int staleDays, ZoneId zone) {
        GoalStatus status = goal.goal().status();
        if (updatedAt == null || status != GoalStatus.ACTIVE && status != GoalStatus.DEGRADED) {
            return Optional.empty();
        }
        List<TaskView> open = openTasks(goal);
        LocalDate last = updatedAt.atZone(zone).toLocalDate();
        LocalDate today = today(goal);
        if (open.isEmpty() || !last.isBefore(today)) {
            return Optional.empty();
        }
        int idle = calendar(goal).workdaysLeft(last, today);
        if (idle < staleDays) {
            return Optional.empty();
        }
        String facts = "Nothing has happened on " + GOAL + " for " + idle + " working days, and "
                + open.size() + " task" + (open.size() == 1 ? " is" : "s are") + " still open. Planning it again "
                + "could make it easier to restart; or cancel it if it no longer matters.";
        return Optional.of(new Nudge(Inbox.Kind.STALE_GOAL, goal.goal().id(), facts, titles(goal, List.of()),
                List.of(new Action(ActionType.REFINE_GOAL, goal.goal().id(), null, null, null, null, null, null,
                        "idle for " + idle + " working days", null)),
                "stale:" + goal.goal().id() + ":" + last));
    }

    private static List<TaskView> openTasks(GoalContext goal) {
        return goal.tasks().stream().filter(t -> t.status().isOpen()).toList();
    }

    private static LocalDate today(GoalContext goal) {
        return goal.today() == null ? LocalDate.now() : goal.today();
    }

    /** The person's working hours a day, else {@link #DEFAULT_DAILY_HOURS}. */
    static double dailyHours(GoalContext goal) {
        return goal.person("dailyHours").filter(JsonNode::isNumber).map(JsonNode::asDouble).filter(h -> h > 0)
                .orElse(DEFAULT_DAILY_HOURS);
    }

    /** The person's working days and holidays; Monday to Friday when the profile does not say. */
    static WorkdayCalendar calendar(GoalContext goal) {
        List<String> names = new ArrayList<>();
        goal.person("workingDays").filter(JsonNode::isArray).ifPresent(days -> days.forEach(d -> names.add(d.asString())));
        List<String> holidays = new ArrayList<>();
        goal.person("holidays").filter(JsonNode::isArray).ifPresent(days -> days.forEach(d -> holidays.add(d.asString())));
        return WorkdayCalendar.of(names, holidays);
    }

    private static String days(GoalContext goal) {
        Function<String, String> shortName = d -> d.charAt(0) + d.substring(1, 3).toLowerCase(Locale.ROOT);
        return goal.person("workingDays").filter(JsonNode::isArray).map(days -> {
            List<String> names = new ArrayList<>();
            days.forEach(d -> names.add(shortName.apply(d.asString())));
            return String.join(", ", names);
        }).orElse("Mon to Fri");
    }

    private static String keys(List<TaskView> tasks) {
        List<String> keys = tasks.stream().map(TaskView::key).toList();
        return keys.size() == 1 ? keys.getFirst()
                : String.join(", ", keys.subList(0, keys.size() - 1)) + " and " + keys.getLast();
    }

    /** Task keys separated by commas ("T1, T3"); {@link Nudge#fill} adds the titles. */
    private static String list(List<TaskView> tasks) {
        return tasks.stream().map(TaskView::key).collect(Collectors.joining(", "));
    }

    private static String hours(double h) {
        return h == Math.rint(h) ? String.valueOf((long) h) : String.valueOf(Math.round(h * 10) / 10.0);
    }
}
