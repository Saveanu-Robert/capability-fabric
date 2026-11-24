package dev.capabilityfabric.algorithms;

import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.TaskStatus;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deterministic priority (0-100) and rank (1..n) for the tasks of one goal.
 *
 * <p>Score = 100 x (0.40 urgency + 0.30 unblock value + 0.20 shortness + 0.10) + boosts, where
 * urgency grows as the due date approaches (working days when a calendar is given), unblock value is the share of the
 * goal's open tasks that wait on the task, directly or through other open tasks (a closed task holds nothing up, so
 * no task waits through one), and shortness favours small tasks (only when an effort estimate exists).
 * Tasks that cannot start yet are halved. Optional inputs (effort, deadline risk) refine the score when some provider
 * supplies them. An open task that another open task waits on, directly or through other open tasks, scores at least as
 * high as the one that waits, since it has to be done first; so a task never ranks above an open task it waits on.
 */
public final class PriorityScorer {

    /**
     * One task to score.
     *
     * @param workdaysLeft working days until the task's due date as of the scoring day; used only by a scorer
     *                     without a calendar (a scorer with one counts them itself, so a value computed on an
     *                     earlier day never counts)
     */
    public record Input(TaskView task, Integer workdaysLeft, String riskLevel) {
    }

    /**
     * {@code priority} 0 to 100, {@code rank} 1 for the task to do first (never before an open task it waits on),
     * {@code reason} the factors in words.
     */
    public record Score(String taskId, int priority, int rank, String reason) {
    }

    private final WorkdayCalendar calendar;

    /** Urgency from calendar days, or from {@link Input#workdaysLeft()} when the caller supplies it. */
    public PriorityScorer() {
        this(null);
    }

    /** Urgency from the working days between the scoring day and each due date, counted with {@code calendar}. */
    public PriorityScorer(WorkdayCalendar calendar) {
        this.calendar = calendar;
    }

    /**
     * Scores and ranks every task, in the order of {@code inputs}. Closed tasks score 0 and rank last; a task without
     * a due date is judged by {@code goalDue}. An open task scores at least as high as every open task that waits on
     * it (its reason then says which: "needed first by T5"). Ties break by dependency order, then by task key, so a
     * task always ranks after the open tasks it waits on.
     */
    public List<Score> score(List<Input> inputs, LocalDate today, LocalDate goalDue) {
        Map<String, TaskView> byId = new LinkedHashMap<>();
        inputs.forEach(i -> byId.put(i.task().id(), i.task()));
        Map<String, List<String>> edges = new LinkedHashMap<>();
        byId.values().forEach(t -> edges.put(t.id(), t.dependsOn().stream().filter(byId::containsKey).toList()));
        TaskGraph graph = new TaskGraph(edges);
        List<String> topo = graph.topologicalOrder();
        Map<String, Integer> topoIndex = new HashMap<>();
        for (int i = 0; i < topo.size(); i++) {
            topoIndex.put(topo.get(i), i);
        }
        TaskGraph open = openGraph(byId);
        Map<String, Integer> openDependents = new HashMap<>();
        int maxDependents = 0;
        for (TaskView t : byId.values()) {
            int n = t.status().isOpen() ? open.transitiveDependents(t.id()).size() : 0;
            openDependents.put(t.id(), n);
            maxDependents = Math.max(maxDependents, n);
        }

        Map<String, Double> raw = new HashMap<>();
        Map<String, String> reasons = new HashMap<>();
        for (Input input : inputs) {
            TaskView t = input.task();
            if (!t.status().isOpen()) {
                raw.put(t.id(), 0.0);
                reasons.put(t.id(), t.status().name().toLowerCase(Locale.ROOT));
                continue;
            }
            List<String> why = new ArrayList<>();
            LocalDate due = t.dueDate() != null ? t.dueDate() : goalDue;
            double urgency;
            if (due == null || today == null) {
                urgency = 0.2;
            } else {
                boolean workdays = calendar != null || input.workdaysLeft() != null && t.dueDate() != null;
                long daysLeft = calendar != null ? calendar.workdaysLeft(today, due)
                        : workdays ? input.workdaysLeft() : ChronoUnit.DAYS.between(today, due);
                urgency = daysLeft < 0 ? 1.0 : 1.0 / (1.0 + daysLeft / 7.0);
                why.add(daysLeft < 0 ? "overdue" : "due in " + daysLeft + (workdays ? " workdays" : " days"));
            }
            double unblock = maxDependents == 0 ? 0 : openDependents.get(t.id()) / (double) maxDependents;
            if (openDependents.get(t.id()) > 0) {
                why.add("unblocks " + openDependents.get(t.id()));
            }
            double shortness = 0.5;
            if (t.effortHours() != null) {
                shortness = 1.0 / (1.0 + t.effortHours() / 8.0);
                why.add(t.effortHours() + "h");
            }
            double score = 100 * (0.40 * urgency + 0.30 * unblock + 0.20 * shortness + 0.10);
            if ("HIGH".equalsIgnoreCase(input.riskLevel())) {
                score += 15;
                why.add("deadline risk high");
            } else if ("OVERDUE".equalsIgnoreCase(input.riskLevel())) {
                score += 25;
                why.add("deadline overdue");
            }
            if (t.status() == TaskStatus.IN_PROGRESS) {
                score += 10;
                why.add("in progress");
            }
            boolean waiting = t.status() == TaskStatus.BLOCKED || t.dependsOn().stream()
                    .map(byId::get).anyMatch(d -> d != null && d.status().isOpen());
            if (waiting) {
                score *= 0.5;
                why.add("waiting on dependencies");
            }
            raw.put(t.id(), Math.max(0, Math.min(100, score)));
            reasons.put(t.id(), String.join(", ", why));
        }
        inheritFromWaiting(byId, open, raw, reasons);

        List<TaskView> ordered = byId.values().stream()
                .sorted(Comparator.<TaskView, Boolean>comparing(t -> !t.status().isOpen())
                        .thenComparing(t -> -Math.round(raw.get(t.id())))
                        .thenComparing(t -> topoIndex.get(t.id()))
                        .thenComparing(TaskView::key, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            rank.put(ordered.get(i).id(), i + 1);
        }
        return inputs.stream()
                .map(i -> new Score(i.task().id(), (int) Math.round(raw.get(i.task().id())), rank.get(i.task().id()),
                        reasons.get(i.task().id())))
                .collect(Collectors.toList());
    }

    /**
     * The open tasks, each with the open tasks it waits on: closed tasks wait on nothing and hold nothing up, so an
     * open task that waits only on a done one can start now and waits on nothing through it.
     */
    private static TaskGraph openGraph(Map<String, TaskView> byId) {
        Map<String, List<String>> openEdges = new LinkedHashMap<>();
        byId.values().stream().filter(t -> t.status().isOpen()).forEach(t -> openEdges.put(t.id(), t.dependsOn()
                .stream().map(byId::get).filter(d -> d != null && d.status().isOpen()).map(TaskView::id).toList()));
        return new TaskGraph(openEdges);
    }

    /**
     * Raises the score of every open task to that of the highest-scoring open task that waits on it, directly or
     * through other open tasks (see {@link #openGraph}), and names that task in its reason. The scores compared are
     * the tasks' own, so a chain passes on the score of its most urgent task.
     */
    private static void inheritFromWaiting(Map<String, TaskView> byId, TaskGraph open, Map<String, Double> raw,
                                           Map<String, String> reasons) {
        Map<String, Double> own = new HashMap<>(raw);
        for (String id : open.nodes()) {
            String highest = null;
            for (String waiting : open.transitiveDependents(id)) {
                if (own.get(waiting) > own.get(id) && (highest == null || own.get(waiting) > own.get(highest))) {
                    highest = waiting;
                }
            }
            if (highest != null) {
                raw.put(id, own.get(highest));
                String key = byId.get(highest).key() == null ? highest : byId.get(highest).key();
                String reason = reasons.get(id);
                reasons.put(id, (reason.isEmpty() ? "" : reason + ", ") + "needed first by " + key);
            }
        }
    }
}
