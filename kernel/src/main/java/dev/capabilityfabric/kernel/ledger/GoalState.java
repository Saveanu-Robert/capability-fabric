package dev.capabilityfabric.kernel.ledger;

import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Hashing;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.TaskStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The state of one goal, derived only by folding its events. Immutable: {@link #apply} returns a new state.
 * Tasks are kept in creation order ({@code T1}, {@code T2}, ...), which makes folding deterministic.
 */
public record GoalState(String goalId, String text, LocalDate dueDate, GoalStatus status,
                        Map<String, JsonNode> annotations, List<TaskView> tasks, int nextKey, int version,
                        Instant createdAt, String principal) {

    public GoalState {
        annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /** The state before the goal's first fact. */
    public static GoalState empty(String goalId) {
        return new GoalState(goalId, null, null, null, Map.of(), List.of(), 1, 0, null, null);
    }

    /** The state the facts give when applied in order. */
    public static GoalState fold(String goalId, List<LedgerEvent> events) {
        GoalState state = empty(goalId);
        for (LedgerEvent e : events) {
            state = state.apply(e);
        }
        return state;
    }

    /** The state after one more fact; every fact advances the version, also those that change nothing else. */
    public GoalState apply(LedgerEvent event) {
        GoalState s = this;
        switch (event.type()) {
            case Facts.GOAL_CREATED -> {
                Facts.GoalCreated p = event.payload(Facts.GoalCreated.class);
                s = new GoalState(goalId, p.text(), p.dueDate(), status, annotations, tasks, nextKey, version,
                        event.at(), p.principal());
            }
            case Facts.GOAL_STATUS_CHANGED -> {
                Facts.GoalStatusChanged p = event.payload(Facts.GoalStatusChanged.class);
                s = new GoalState(goalId, text, dueDate, p.to(), annotations, tasks, nextKey, version, createdAt, principal);
            }
            case Facts.GOAL_RESCHEDULED -> {
                Facts.GoalRescheduled p = event.payload(Facts.GoalRescheduled.class);
                s = new GoalState(goalId, text, p.to(), status, annotations, tasks, nextKey, version, createdAt, principal);
            }
            case Facts.GOAL_ANNOTATED -> {
                Map<String, JsonNode> merged = new HashMap<>(annotations);
                merged.putAll(event.payload(Facts.GoalAnnotated.class).annotations());
                s = new GoalState(goalId, text, dueDate, status, merged, tasks, nextKey, version, createdAt, principal);
            }
            case Facts.TASK_ADDED -> {
                Facts.TaskAdded p = event.payload(Facts.TaskAdded.class);
                List<TaskView> next = new ArrayList<>(tasks);
                next.add(new TaskView(p.taskId(), p.key(), p.title(), p.description(), TaskStatus.TODO, p.dueDate(),
                        p.dependsOn(), p.effortHours(), null, null, null, Map.of()));
                int keyNumber = Integer.parseInt(p.key().substring(1));
                s = new GoalState(goalId, text, dueDate, status, annotations, next, Math.max(nextKey, keyNumber + 1),
                        version, createdAt, principal);
            }
            case Facts.TASK_UPDATED -> {
                Facts.TaskUpdated p = event.payload(Facts.TaskUpdated.class);
                s = replaceTask(p.taskId(), t -> new TaskView(t.id(), t.key(), t.title(), t.description(), t.status(),
                        p.dueDate() != null ? p.dueDate() : t.dueDate(), t.dependsOn(),
                        p.effortHours() != null ? p.effortHours() : t.effortHours(),
                        p.priority() != null ? p.priority() : t.priority(),
                        p.rank() != null ? p.rank() : t.rank(), t.actualHours(), t.annotations()));
            }
            case Facts.TASK_ANNOTATED -> {
                Facts.TaskAnnotated p = event.payload(Facts.TaskAnnotated.class);
                s = replaceTask(p.taskId(), t -> {
                    Map<String, JsonNode> merged = new HashMap<>(t.annotations());
                    merged.putAll(p.annotations());
                    return new TaskView(t.id(), t.key(), t.title(), t.description(), t.status(), t.dueDate(),
                            t.dependsOn(), t.effortHours(), t.priority(), t.rank(), t.actualHours(), merged);
                });
            }
            case Facts.TASK_STATUS_CHANGED -> {
                Facts.TaskStatusChanged p = event.payload(Facts.TaskStatusChanged.class);
                s = replaceTask(p.taskId(), t -> new TaskView(t.id(), t.key(), t.title(), t.description(), p.to(),
                        t.dueDate(), t.dependsOn(), t.effortHours(), t.priority(), t.rank(),
                        p.actualHours() != null ? p.actualHours() : t.actualHours(), t.annotations()));
            }
            case Facts.DEPENDENCY_ADDED -> {
                Facts.DependencyAdded p = event.payload(Facts.DependencyAdded.class);
                s = replaceTask(p.taskId(), t -> {
                    List<String> deps = new ArrayList<>(t.dependsOn());
                    if (!deps.contains(p.dependsOnTaskId())) {
                        deps.add(p.dependsOnTaskId());
                    }
                    return new TaskView(t.id(), t.key(), t.title(), t.description(), t.status(), t.dueDate(), deps,
                            t.effortHours(), t.priority(), t.rank(), t.actualHours(), t.annotations());
                });
            }
            default -> {
                // Plan, approval and proposal facts are part of the goal's history but do not change its state.
            }
        }
        return new GoalState(s.goalId, s.text, s.dueDate, s.status, s.annotations, s.tasks, s.nextKey, version + 1,
                s.createdAt, s.principal);
    }

    private GoalState replaceTask(String taskId, UnaryOperator<TaskView> change) {
        List<TaskView> next = tasks.stream().map(t -> t.id().equals(taskId) ? change.apply(t) : t).toList();
        return new GoalState(goalId, text, dueDate, status, annotations, next, nextKey, version, createdAt, principal);
    }

    /** Finds a task by id, id prefix (8+ characters) or key ({@code T3}, case-insensitive). */
    public Optional<TaskView> task(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String r = ref.trim();
        for (TaskView t : tasks) {
            if (t.id().equals(r) || t.key().equalsIgnoreCase(r)) {
                return Optional.of(t);
            }
        }
        if (r.length() >= 8) {
            List<TaskView> matches = tasks.stream().filter(t -> t.id().startsWith(r.toLowerCase(Locale.ROOT))).toList();
            if (matches.size() == 1) {
                return Optional.of(matches.getFirst());
            }
        }
        return Optional.empty();
    }

    /** True once the goal was created. */
    public boolean exists() {
        return text != null;
    }

    /**
     * Fingerprint of the facts a suggestion about this goal rests on; see {@link GoalContext#planFingerprint()}.
     */
    public String planFingerprint() {
        return GoalContext.fingerprint(dueDate, tasks);
    }

    public int doneCount() {
        return (int) tasks.stream().filter(t -> t.status() == TaskStatus.DONE).count();
    }

    /** The goal as capabilities see it, with today's date. */
    public GoalContext toContext(LocalDate today) {
        return new GoalContext(new GoalContext.GoalView(goalId, text, dueDate, status, annotations), tasks, today);
    }

    /**
     * Hash of the goal's content (text, tasks by key, fields, annotations), excluding ids and timestamps,
     * so two runs that make the same decisions produce the same hash.
     */
    public String contentHash() {
        Map<String, String> keyOf = new HashMap<>();
        tasks.forEach(t -> keyOf.put(t.id(), t.key()));
        ObjectNode root = Json.object();
        root.put("text", text);
        root.put("dueDate", dueDate == null ? null : dueDate.toString());
        root.put("status", status == null ? null : status.name());
        root.set("annotations", Json.toTree(annotations));
        var list = root.putArray("tasks");
        for (TaskView t : tasks) {
            ObjectNode n = list.addObject();
            n.put("key", t.key());
            n.put("title", t.title());
            n.put("description", t.description());
            n.put("status", t.status().name());
            n.put("dueDate", t.dueDate() == null ? null : t.dueDate().toString());
            n.set("dependsOn", Json.toTree(t.dependsOn().stream().map(keyOf::get).sorted().toList()));
            n.put("effortHours", t.effortHours());
            n.put("priority", t.priority());
            n.put("rank", t.rank());
            n.put("actualHours", t.actualHours());
            n.set("annotations", Json.toTree(t.annotations()));
        }
        return Hashing.canonicalHash(root);
    }
}
