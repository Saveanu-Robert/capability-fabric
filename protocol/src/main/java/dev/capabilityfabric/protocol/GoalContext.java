package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The standard input of every goal-processing capability: a snapshot of one goal and its tasks. The kernel minimises it
 * to what the capability declared it consumes (context minimisation), so a provider never sees fields it has no use
 * for.
 */
public record GoalContext(GoalView goal, List<TaskView> tasks, LocalDate today, Map<String, JsonNode> person) {

    public GoalContext {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        person = person == null ? Map.of() : Map.copyOf(person);
    }

    public GoalContext(GoalView goal, List<TaskView> tasks, LocalDate today) {
        this(goal, tasks, today, Map.of());
    }

    /** The same context with the person's profile values (keys without the {@code person.} prefix). */
    public GoalContext withPerson(Map<String, JsonNode> profile) {
        return new GoalContext(goal, tasks, today, profile);
    }

    /**
     * Fingerprint of the facts a suggestion about this goal rests on: its deadline, each task's status, due date,
     * effort and dependencies, the date it was computed on and the person's capacity. Status of the goal, scores and
     * annotations are left out, so routine re-scoring and run bookkeeping do not invalidate a suggestion. A new day
     * changes it, because the working time left before a deadline shrinks. Providers and the kernel compute it from
     * the context they looked at.
     */
    public String planFingerprint() {
        String base = fingerprint(goal == null ? null : goal.dueDate(), tasks);
        String capacity = String.valueOf(person.get("dailyHours")) + person.get("workingDays") + person.get("holidays");
        return Hashing.sha256(base + "|" + today + "|" + capacity).substring(0, 16);
    }

    /** 16 hex characters over a deadline and each task's key, status, due date, effort and dependency count. */
    public static String fingerprint(LocalDate dueDate, List<TaskView> tasks) {
        StringBuilder sb = new StringBuilder().append(dueDate);
        for (TaskView t : tasks) {
            sb.append('|').append(t.key()).append(':').append(t.status()).append(':').append(t.dueDate()).append(':')
                    .append(t.effortHours()).append(':').append(t.dependsOn().size());
        }
        return Hashing.sha256(sb.toString()).substring(0, 16);
    }

    /** A profile value, for example {@code dailyHours} for token {@code person.dailyHours}. */
    public Optional<JsonNode> person(String key) {
        return Optional.ofNullable(person.get(key));
    }

    public record GoalView(String id, String text, LocalDate dueDate, GoalStatus status,
                           Map<String, JsonNode> annotations) {
        public GoalView {
            annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        }
    }

    /**
     * One task. {@code dependsOn} holds task ids. {@code key} is a short, stable per-goal key ({@code T1}, {@code T2},
     * ...) that prompts use instead of ids so that recorded decisions replay.
     */
    public record TaskView(String id, String key, String title, String description, TaskStatus status,
                           LocalDate dueDate, List<String> dependsOn, Double effortHours, Integer priority,
                           Integer rank, Double actualHours, Map<String, JsonNode> annotations) {
        public TaskView {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
            annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        }

        public Optional<JsonNode> annotation(String key) {
            return Optional.ofNullable(annotations.get(key));
        }
    }

    /** The tasks by id, in their order. */
    public Map<String, TaskView> tasksById() {
        return tasks.stream().collect(Collectors.toMap(TaskView::id, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
    }

    /**
     * Returns a copy that keeps identity and structure fields (goal id and status; task id, key, title, status,
     * dependsOn) and only the other fields, annotations and profile values named in {@code consumes}. Today's date
     * is kept when a date token (today, a due date) is consumed.
     */
    public GoalContext minimise(Collection<String> consumes) {
        Set<String> c = Set.copyOf(consumes);
        GoalView g = goal == null ? null : new GoalView(goal.id(),
                c.contains(Vocabulary.GOAL_TEXT) ? goal.text() : null,
                c.contains(Vocabulary.GOAL_DUE_DATE) ? goal.dueDate() : null,
                goal.status(),
                filterAnnotations(goal.annotations(), c, Vocabulary.GOAL_ANN_PREFIX));
        List<TaskView> t = tasks.stream().map(task -> new TaskView(task.id(), task.key(), task.title(),
                c.contains(Vocabulary.TASK_DESCRIPTION) ? task.description() : null,
                task.status(),
                c.contains(Vocabulary.TASK_DUE_DATE) ? task.dueDate() : null,
                task.dependsOn(),
                c.contains(Vocabulary.TASK_EFFORT_HOURS) ? task.effortHours() : null,
                c.contains(Vocabulary.TASK_PRIORITY) ? task.priority() : null,
                c.contains(Vocabulary.TASK_RANK) ? task.rank() : null,
                c.contains(Vocabulary.TASK_ACTUAL_HOURS) ? task.actualHours() : null,
                filterAnnotations(task.annotations(), c, Vocabulary.TASK_ANN_PREFIX))).toList();
        Map<String, JsonNode> p = new HashMap<>();
        person.forEach((k, v) -> {
            if (c.contains(Vocabulary.PERSON_PREFIX + k)) {
                p.put(k, v);
            }
        });
        return new GoalContext(g, t, c.contains(Vocabulary.GOAL_TODAY) || c.contains(Vocabulary.TASK_DUE_DATE)
                || c.contains(Vocabulary.GOAL_DUE_DATE) ? today : null, p);
    }

    private static Map<String, JsonNode> filterAnnotations(Map<String, JsonNode> annotations, Set<String> consumes,
                                                           String prefix) {
        Map<String, JsonNode> kept = new HashMap<>();
        annotations.forEach((k, v) -> {
            if (consumes.contains(prefix + k)) {
                kept.put(k, v);
            }
        });
        return kept;
    }

    /** Vocabulary tokens that currently have at least one value in this context. */
    public Set<String> presentTokens() {
        Set<String> present = new HashSet<>();
        if (goal != null) {
            if (goal.text() != null) {
                present.add(Vocabulary.GOAL_TEXT);
            }
            if (goal.dueDate() != null) {
                present.add(Vocabulary.GOAL_DUE_DATE);
            }
            goal.annotations().keySet().forEach(k -> present.add(Vocabulary.goalAnnotation(k)));
        }
        if (today != null) {
            present.add(Vocabulary.GOAL_TODAY);
        }
        person.keySet().forEach(k -> present.add(Vocabulary.PERSON_PREFIX + k));
        if (!tasks.isEmpty()) {
            present.add(Vocabulary.TASKS);
            present.add(Vocabulary.TASK_TITLE);
            present.add(Vocabulary.TASK_STATUS);
            present.add(Vocabulary.TASK_DEPENDS_ON);
        }
        for (TaskView t : tasks) {
            if (t.description() != null) {
                present.add(Vocabulary.TASK_DESCRIPTION);
            }
            if (t.dueDate() != null) {
                present.add(Vocabulary.TASK_DUE_DATE);
            }
            if (t.effortHours() != null) {
                present.add(Vocabulary.TASK_EFFORT_HOURS);
            }
            if (t.priority() != null) {
                present.add(Vocabulary.TASK_PRIORITY);
            }
            if (t.rank() != null) {
                present.add(Vocabulary.TASK_RANK);
            }
            if (t.actualHours() != null) {
                present.add(Vocabulary.TASK_ACTUAL_HOURS);
            }
            t.annotations().keySet().forEach(k -> present.add(Vocabulary.taskAnnotation(k)));
        }
        return present;
    }
}
