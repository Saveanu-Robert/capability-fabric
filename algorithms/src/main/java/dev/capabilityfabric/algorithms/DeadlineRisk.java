package dev.capabilityfabric.algorithms;

import dev.capabilityfabric.protocol.GoalContext.TaskView;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Deadline risk of open tasks with a due date: compares the work that must finish before the task (its own effort plus
 * every open transitive dependency) with the working hours left. The working hours left are those of every working day
 * from today (when today is a working day) up to and including the due date, so work due today still has today's hours.
 * Work without an estimate makes the risk {@code UNKNOWN}, never "fits", unless the estimated part alone already
 * exceeds the time left, which is {@code HIGH} whatever the rest turns out to be.
 */
public final class DeadlineRisk {

    /**
     * {@code HIGH}: the work does not fit; {@code MEDIUM}: less than a quarter of the work's size to spare;
     * {@code LOW}: less than the work's size to spare; {@code NONE}: at least as much time to spare as the work
     * needs; {@code OVERDUE}: the due date has passed; {@code UNKNOWN}: estimates are missing.
     */
    public enum Level { NONE, LOW, MEDIUM, HIGH, OVERDUE, UNKNOWN }

    /**
     * @param taskId         the task assessed, or {@code null} for an assessment of work taken together
     * @param slackHours     hours to spare (negative when the work does not fit); {@code null} when not known
     * @param remainingHours the estimated hours; a lower bound when {@code unestimated} is not empty
     * @param workdays       working days of capacity before the due date ({@link WorkdayCalendar#capacityDays}),
     *                       negative once it has passed
     * @param unestimated    ids of open tasks counted in the work that have no estimate yet
     */
    public record Assessment(String taskId, Level level, Double slackHours, double remainingHours,
                             int workdays, String reason, List<String> unestimated) {

        public Assessment {
            unestimated = unestimated == null ? List.of() : List.copyOf(unestimated);
        }

        public Assessment(String taskId, Level level, Double slackHours, double remainingHours, int workdays,
                          String reason) {
            this(taskId, level, slackHours, remainingHours, workdays, reason, List.of());
        }

        /** Working hours before the due date at {@code hoursPerDay}; zero once it has passed. */
        public double availableHours(double hoursPerDay) {
            return Math.max(0, workdays) * hoursPerDay;
        }
    }

    private final WorkdayCalendar calendar;
    private final double hoursPerDay;

    /** @param hoursPerDay the hours one person works on each working day */
    public DeadlineRisk(WorkdayCalendar calendar, double hoursPerDay) {
        this.calendar = calendar;
        this.hoursPerDay = hoursPerDay;
    }

    /**
     * One assessment per open task with a due date, keyed by task id: each task with the open work it waits on
     * (its transitive dependencies within {@code tasks}) against its own due date. Tasks without a due date and
     * closed tasks are left out.
     */
    public Map<String, Assessment> assess(List<TaskView> tasks, LocalDate today) {
        Map<String, TaskView> byId = new LinkedHashMap<>();
        tasks.forEach(t -> byId.put(t.id(), t));
        Map<String, List<String>> edges = new LinkedHashMap<>();
        tasks.forEach(t -> edges.put(t.id(), t.dependsOn().stream().filter(byId::containsKey).toList()));
        TaskGraph graph = new TaskGraph(edges);

        Map<String, Assessment> result = new LinkedHashMap<>();
        for (TaskView t : tasks) {
            if (!t.status().isOpen() || t.dueDate() == null) {
                continue;
            }
            assessOne(t, byId, graph, today).ifPresent(a -> result.put(t.id(), a));
        }
        return result;
    }

    /**
     * All open work in {@code tasks} done by one person, one task after another, against a single deadline (a
     * goal's). Unlike {@link #assess}, independent tasks add up, because the same person does them all.
     */
    public Assessment assessTogether(List<TaskView> tasks, LocalDate due, LocalDate today) {
        int workdays = calendar.capacityDays(today, due);
        List<TaskView> open = tasks.stream().filter(t -> t.status().isOpen()).toList();
        if (workdays < 0) {
            return new Assessment(null, Level.OVERDUE, null, 0, workdays, "due " + due + " has passed");
        }
        double remaining = open.stream().filter(t -> t.effortHours() != null).mapToDouble(TaskView::effortHours).sum();
        List<String> unestimated = open.stream().filter(t -> t.effortHours() == null).map(TaskView::id).toList();
        return rate(null, due, workdays, remaining, unestimated);
    }

    private Optional<Assessment> assessOne(TaskView t, Map<String, TaskView> byId, TaskGraph graph, LocalDate today) {
        int workdays = calendar.capacityDays(today, t.dueDate());
        if (workdays < 0) {
            return Optional.of(new Assessment(t.id(), Level.OVERDUE, null, 0, workdays,
                    "due " + t.dueDate() + " has passed"));
        }
        List<String> unestimated = new ArrayList<>();
        if (t.effortHours() == null) {
            unestimated.add(t.id());
        }
        double remaining = t.effortHours() == null ? 0 : t.effortHours();
        for (String dep : graph.transitiveDependencies(t.id())) {
            TaskView d = byId.get(dep);
            if (d != null && d.status().isOpen()) {
                if (d.effortHours() == null) {
                    unestimated.add(d.id());
                } else {
                    remaining += d.effortHours();
                }
            }
        }
        return Optional.of(rate(t.id(), t.dueDate(), workdays, remaining, unestimated));
    }

    private Assessment rate(String taskId, LocalDate due, int workdays, double remaining, List<String> unestimated) {
        double available = workdays * hoursPerDay;
        double slack = available - remaining;
        if (!unestimated.isEmpty()) {
            if (slack < 0) {
                return new Assessment(taskId, Level.HIGH, round(slack), remaining, workdays, String.format(
                        Locale.ROOT, "at least %.1fh of work before %s (some not estimated yet), %.1fh "
                                + "available in %d workdays", remaining, due, available, workdays), unestimated);
            }
            return new Assessment(taskId, Level.UNKNOWN, null, remaining, workdays,
                    "effort estimates missing for this task or its dependencies", unestimated);
        }
        Level level;
        if (slack < 0) {
            level = Level.HIGH;
        } else if (slack < remaining * 0.25) {
            level = Level.MEDIUM;
        } else if (slack < remaining) {
            level = Level.LOW;
        } else {
            level = Level.NONE;
        }
        String reason = String.format(Locale.ROOT, "%.1fh of work before %s, %.1fh available in %d workdays",
                remaining, due, available, workdays);
        return new Assessment(taskId, level, round(slack), remaining, workdays, reason, List.of());
    }

    private static double round(double hours) {
        return Math.round(hours * 10) / 10.0;
    }
}
