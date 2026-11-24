package dev.capabilityfabric.kernel.ledger;

import dev.capabilityfabric.algorithms.TaskGraph;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.SchemaValidator;
import dev.capabilityfabric.protocol.TaskStatus;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The ledger's invariants as pure functions: a command plus the current state gives either a list of new facts or a
 * list of reasons for rejection, never both. No model output reaches the ledger without passing through here (LLM
 * proposes, kernel disposes).
 */
public final class LedgerRules {

    private LedgerRules() {
    }

    /** New facts to record, or the reasons a command was refused; never both. */
    public record Decision(List<LedgerEvent> events, List<String> errors) {
        public Decision {
            events = List.copyOf(events);
            errors = List.copyOf(errors);
        }

        public static Decision accept(List<LedgerEvent> events) {
            return new Decision(events, List.of());
        }

        public static Decision reject(List<String> errors) {
            return new Decision(List.of(), errors);
        }

        public static Decision reject(String error) {
            return reject(List.of(error));
        }

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * The text without control characters (C0, DEL and C1): a tab or line break becomes a space, every other control
     * character goes, so a stored text can never steer a terminal that shows it. Null stays null.
     */
    public static String printable(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                sb.append(' ');
            } else if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** How many years ahead a goal's deadline or a task's due date may lie. */
    static final int MAX_YEARS_AHEAD = 10;

    /** The latest date a deadline or a due date may have: {@link #MAX_YEARS_AHEAD} years from today. */
    static LocalDate latestDate() {
        return LocalDate.now().plusYears(MAX_YEARS_AHEAD);
    }

    /**
     * A new goal in status PLANNING; its text, without control characters, must be neither empty nor longer than
     * 2000 characters, and its deadline, when it has one, at most {@link #MAX_YEARS_AHEAD} years away.
     */
    public static Decision createGoal(String goalId, String text, LocalDate dueDate, String principal) {
        text = printable(text);
        if (text == null || text.isBlank()) {
            return Decision.reject("goal text is empty");
        }
        if (text.length() > 2000) {
            return Decision.reject("goal text is longer than 2000 characters");
        }
        if (dueDate != null && dueDate.isAfter(latestDate())) {
            return Decision.reject("the deadline " + dueDate + " is more than " + MAX_YEARS_AHEAD + " years away");
        }
        return Decision.accept(List.of(
                LedgerEvent.of(Facts.GOAL_CREATED, new Facts.GoalCreated(goalId, text.strip(), dueDate, principal),
                        principal, null),
                LedgerEvent.of(Facts.GOAL_STATUS_CHANGED,
                        new Facts.GoalStatusChanged(goalId, null, GoalStatus.PLANNING, "accepted"), principal, null)));
    }

    /** A goal's new status: no fact when it is unchanged, refused for an unknown or a cancelled goal. */
    public static Decision goalStatus(GoalState state, GoalStatus to, String reason, String principal) {
        if (!state.exists()) {
            return Decision.reject("unknown goal");
        }
        if (state.status() == to) {
            return Decision.accept(List.of());
        }
        if (state.status() == GoalStatus.CANCELLED) {
            return Decision.reject("goal is cancelled");
        }
        return Decision.accept(List.of(LedgerEvent.of(Facts.GOAL_STATUS_CHANGED,
                new Facts.GoalStatusChanged(state.goalId(), state.status(), to, reason), principal, null)));
    }

    static final int MAX_TITLE = 200;
    static final int MAX_DESCRIPTION = 2000;
    static final int MAX_NEW_TASKS = 30;
    /** The largest annotation value, in bytes of its JSON. */
    static final int MAX_ANNOTATION_BYTES = 4 * 1024;
    /** The largest sum of the annotation values of one task update, or of a goal's annotations, in bytes of JSON. */
    static final int MAX_ANNOTATIONS_BYTES = 16 * 1024;

    /**
     * Limits a well-behaved provider never reaches, checked for every provider: sizes, ranges and dates, and the size
     * of annotation values ({@link #MAX_ANNOTATION_BYTES} each, {@link #MAX_ANNOTATIONS_BYTES} per task update and for
     * the goal), so every fact stays far below what the message broker carries.
     */
    static List<String> bounds(Effects effects) {
        List<String> errors = new ArrayList<>();
        if (effects.newTasks().size() > MAX_NEW_TASKS) {
            errors.add("at most " + MAX_NEW_TASKS + " new tasks at once");
        }
        annotationSizes(effects.goalAnnotations(), "the goal", errors);
        for (Effects.TaskUpdate u : effects.taskUpdates()) {
            annotationSizes(u.annotations(), "task " + shortened(printable(String.valueOf(u.task()))), errors);
        }
        LocalDate earliest = LocalDate.now().minusYears(1);
        LocalDate latest = latestDate();
        for (Effects.NewTask t : effects.newTasks()) {
            if (t.title() != null && t.title().length() > MAX_TITLE) {
                errors.add("a task title is longer than " + MAX_TITLE + " characters");
            }
            if (t.description() != null && t.description().length() > MAX_DESCRIPTION) {
                errors.add("a task description is longer than " + MAX_DESCRIPTION + " characters");
            }
            checkEffort(t.effortHours(), errors);
            checkDate(t.dueDate(), earliest, latest, errors);
        }
        for (Effects.TaskUpdate u : effects.taskUpdates()) {
            checkEffort(u.effortHours(), errors);
            checkDate(u.dueDate(), earliest, latest, errors);
            if (u.priority() != null && (u.priority() < 0 || u.priority() > 100)) {
                errors.add("priority " + u.priority() + " is outside 0..100");
            }
            if (u.rank() != null && u.rank() < 1) {
                errors.add("rank " + u.rank() + " must be 1 or more");
            }
        }
        return errors;
    }

    /** Adds an error for each annotation value of {@code annotations} that is too large, and for too large a sum. */
    private static void annotationSizes(Map<String, JsonNode> annotations, String of, List<String> errors) {
        long total = 0;
        for (Map.Entry<String, JsonNode> a : annotations.entrySet()) {
            int size = Json.write(a.getValue()).getBytes(StandardCharsets.UTF_8).length;
            total += size;
            if (size > MAX_ANNOTATION_BYTES) {
                errors.add("annotation " + shortened(printable(a.getKey())) + " of " + of + " is larger than "
                        + MAX_ANNOTATION_BYTES + " bytes");
            }
        }
        if (total > MAX_ANNOTATIONS_BYTES) {
            errors.add("the annotations of " + of + " are larger than " + MAX_ANNOTATIONS_BYTES + " bytes together");
        }
    }

    /** A provider's reference as an error names it: at most 40 characters. */
    private static String shortened(String ref) {
        return ref.length() <= 40 ? ref : ref.substring(0, 40) + "...";
    }

    private static void checkEffort(Double hours, List<String> errors) {
        if (hours != null && (hours <= 0 || hours > 1000 || hours.isNaN())) {
            errors.add("effort " + hours + " h is outside (0, 1000]");
        }
    }

    private static void checkDate(LocalDate date, LocalDate earliest, LocalDate latest, List<String> errors) {
        if (date != null && (date.isBefore(earliest) || date.isAfter(latest))) {
            errors.add("date " + date + " is not plausible");
        }
    }

    /** A cancelled or completed goal opens again; its tasks keep their state. */
    public static Decision reopen(GoalState state, String principal) {
        if (!state.exists()) {
            return Decision.reject("unknown goal");
        }
        if (state.status() != GoalStatus.CANCELLED && state.status() != GoalStatus.COMPLETED) {
            return Decision.reject("the goal is not closed");
        }
        return Decision.accept(List.of(LedgerEvent.of(Facts.GOAL_STATUS_CHANGED,
                new Facts.GoalStatusChanged(state.goalId(), state.status(), GoalStatus.ACTIVE, "reopened"), principal, null)));
    }

    /**
     * Applies a capability's effects. The capability may only write the vocabulary tokens it declared in
     * {@code produces}; annotation values must match the schema it declared; new tasks and dependencies
     * must keep the graph acyclic; unchanged values produce no facts. New tasks on a completed goal open it again. An
     * update that moves a task's due date without setting its deadline risk marks an assessed risk as not assessed
     * yet ({@link #UNASSESSED_RISK}), so a risk measured against the old date is not shown after the move.
     */
    public static Decision applyEffects(GoalState state, Effects effects, CapabilityManifest manifest, String actor,
                                        String causationId, int maxTasks, Supplier<String> ids) {
        if (!state.exists()) {
            return Decision.reject("unknown goal");
        }
        if (state.status() == GoalStatus.CANCELLED) {
            return Decision.reject("goal is cancelled");
        }
        Set<String> undeclared = new LinkedHashSet<>(effects.producedTokens());
        undeclared.removeAll(manifest.produces());
        if (!undeclared.isEmpty()) {
            return Decision.reject(manifest.id() + " wrote tokens it did not declare: " + undeclared);
        }
        // The kernel validates every effect itself; it does not rely on a provider having used the SDK.
        List<String> schemaErrors = SchemaValidator.validateBundled("effects", Json.toTree(effects));
        if (!schemaErrors.isEmpty()) {
            return Decision.reject("effects do not match the contract: " + String.join("; ", schemaErrors));
        }
        List<String> boundErrors = bounds(effects);
        if (!boundErrors.isEmpty()) {
            return Decision.reject(String.join("; ", boundErrors));
        }
        List<String> errors = new ArrayList<>();
        List<LedgerEvent> events = new ArrayList<>();
        String goalId = state.goalId();

        // New tasks: assign ids and keys, resolve dependency references.
        Map<String, String> refToId = new LinkedHashMap<>();
        Map<String, String> idToKey = new HashMap<>();
        int nextKey = state.nextKey();
        for (Effects.NewTask nt : effects.newTasks()) {
            if (nt.title() == null || printable(nt.title()).isBlank()) {
                errors.add("a new task has no title");
                continue;
            }
            if (refToId.containsKey(nt.ref())) {
                errors.add("duplicate task ref " + nt.ref());
                continue;
            }
            String id = ids.get();
            refToId.put(nt.ref(), id);
            idToKey.put(id, "T" + nextKey++);
        }
        long counted = state.tasks().stream().filter(t -> t.status() != TaskStatus.DROPPED).count();
        if (counted + refToId.size() > maxTasks) {
            errors.add("goal would exceed " + maxTasks + " tasks");
        }
        Map<String, List<String>> graph = new LinkedHashMap<>();
        state.tasks().forEach(t -> graph.put(t.id(), new ArrayList<>(t.dependsOn())));
        for (Effects.NewTask nt : effects.newTasks()) {
            String id = refToId.get(nt.ref());
            if (id == null) {
                continue;
            }
            List<String> deps = new ArrayList<>();
            for (String ref : nt.dependsOn()) {
                Optional<String> target = Optional.ofNullable(refToId.get(ref))
                        .or(() -> state.task(ref).map(TaskView::id));
                if (target.isEmpty()) {
                    errors.add("task " + nt.ref() + " depends on unknown " + ref);
                } else if (!target.get().equals(id) && !deps.contains(target.get())) {
                    deps.add(target.get());
                }
            }
            graph.put(id, deps);
            // Titles and descriptions come from providers and people alike; control characters never reach the ledger.
            events.add(LedgerEvent.of(Facts.TASK_ADDED, new Facts.TaskAdded(goalId, id, idToKey.get(id),
                    printable(nt.title()).strip(), printable(nt.description()), nt.dueDate(), deps, nt.effortHours(),
                    actor), actor, causationId));
        }

        // Updates to existing tasks.
        Map<String, TaskView> current = new HashMap<>();
        state.tasks().forEach(t -> current.put(t.id(), t));
        for (Effects.TaskUpdate update : effects.taskUpdates()) {
            Optional<TaskView> found = state.task(update.task());
            if (found.isEmpty()) {
                errors.add("update for unknown task " + update.task());
                continue;
            }
            TaskView t = current.get(found.get().id());
            Double effort = changed(update.effortHours(), t.effortHours());
            Integer priority = changed(update.priority(), t.priority());
            Integer rank = changed(update.rank(), t.rank());
            LocalDate due = changed(update.dueDate(), t.dueDate());
            if (effort != null || priority != null || rank != null || due != null) {
                events.add(LedgerEvent.of(Facts.TASK_UPDATED,
                        new Facts.TaskUpdated(goalId, t.id(), effort, priority, rank, due, actor), actor, causationId));
            }
            Map<String, JsonNode> annotations = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> a : update.annotations().entrySet()) {
                String problem = annotationProblem(manifest, a.getKey(), a.getValue());
                if (problem != null) {
                    errors.add(problem);
                } else if (!Objects.equals(t.annotations().get(a.getKey()), a.getValue())) {
                    annotations.put(a.getKey(), a.getValue());
                }
            }
            if (due != null && !update.annotations().containsKey(DEADLINE_RISK) && staleRisk(t)) {
                // The risk was assessed against the old due date: it is no longer known until it is assessed again.
                annotations.put(DEADLINE_RISK, UNASSESSED_RISK);
            }
            if (!annotations.isEmpty()) {
                events.add(LedgerEvent.of(Facts.TASK_ANNOTATED,
                        new Facts.TaskAnnotated(goalId, t.id(), annotations, actor), actor, causationId));
            }
            for (String ref : update.addDependsOn()) {
                Optional<String> target = Optional.ofNullable(refToId.get(ref)).or(() -> state.task(ref).map(TaskView::id));
                if (target.isEmpty()) {
                    errors.add("dependency on unknown task " + ref);
                } else if (!graph.get(t.id()).contains(target.get())) {
                    graph.get(t.id()).add(target.get());
                    events.add(LedgerEvent.of(Facts.DEPENDENCY_ADDED,
                            new Facts.DependencyAdded(goalId, t.id(), target.get(), actor), actor, causationId));
                }
            }
        }

        Map<String, JsonNode> goalAnnotations = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> a : effects.goalAnnotations().entrySet()) {
            String problem = annotationProblem(manifest, a.getKey(), a.getValue());
            if (problem != null) {
                errors.add(problem);
            } else if (!Objects.equals(state.annotations().get(a.getKey()), a.getValue())) {
                goalAnnotations.put(a.getKey(), a.getValue());
            }
        }
        if (!goalAnnotations.isEmpty()) {
            events.add(LedgerEvent.of(Facts.GOAL_ANNOTATED, new Facts.GoalAnnotated(goalId, goalAnnotations, actor),
                    actor, causationId));
        }

        Optional<List<String>> cycle = new TaskGraph(graph).findCycle();
        if (cycle.isPresent()) {
            Map<String, String> keys = new HashMap<>(idToKey);
            state.tasks().forEach(t -> keys.put(t.id(), t.key()));
            errors.add("dependency cycle " + cycle.get().stream().map(id -> keys.getOrDefault(id, id)).toList());
        }
        if (errors.isEmpty() && state.status() == GoalStatus.COMPLETED && !refToId.isEmpty()) {
            events.add(LedgerEvent.of(Facts.GOAL_STATUS_CHANGED, new Facts.GoalStatusChanged(goalId,
                    GoalStatus.COMPLETED, GoalStatus.ACTIVE, "task added"), actor, causationId));
        }
        return errors.isEmpty() ? Decision.accept(events) : Decision.reject(errors);
    }

    /** The annotation that holds a task's deadline risk. */
    static final String DEADLINE_RISK = "risk.deadline";
    /**
     * A deadline risk not assessed yet, written when a task's due date moves: the risk measured against the old date
     * is not shown as current, and the agent that assesses risk writes a new one.
     */
    static final JsonNode UNASSESSED_RISK = Json.object().put("level", "UNKNOWN")
            .putNull("slackHours").put("reason", "the due date moved; not assessed again yet");

    /** True when the task carries a deadline risk that was assessed (any level but unknown). */
    private static boolean staleRisk(TaskView t) {
        JsonNode risk = t.annotations().get(DEADLINE_RISK);
        return risk != null && risk.isObject() && !"UNKNOWN".equals(risk.path("level").asString(""));
    }

    private static String annotationProblem(CapabilityManifest manifest, String key, JsonNode value) {
        JsonNode schema = manifest.annotationSchemas().get(key);
        if (schema == null) {
            return manifest.id() + " has no schema for annotation " + key;
        }
        List<String> problems = SchemaValidator.validate(schema, value);
        return problems.isEmpty() ? null : "annotation " + key + " is invalid: " + problems;
    }

    private static <T> T changed(T proposed, T current) {
        if (proposed == null) {
            return null;
        }
        if (proposed instanceof Double p && current instanceof Double c && Math.abs(p - c) < 1e-9) {
            return null;
        }
        return proposed.equals(current) ? null : proposed;
    }

    /**
     * Human status change. A task cannot start while one of its dependencies is open. When the last open task
     * closes and at least one task is done, the goal completes (a goal whose tasks were all dropped keeps its
     * status); reopening a task reactivates a completed goal. A done task can be reported done again with its real
     * hours: that records the hours (a change from done to done) and nothing else.
     */
    public static Decision changeStatus(GoalState state, String taskRef, TaskStatus to, String reason,
                                        Double actualHours, String principal) {
        Optional<TaskView> found = state.task(taskRef);
        if (found.isEmpty()) {
            return Decision.reject("unknown task " + taskRef);
        }
        if (state.status() == GoalStatus.CANCELLED) {
            return Decision.reject("goal is cancelled");
        }
        TaskView task = found.get();
        if (actualHours != null && (actualHours <= 0 || actualHours > 1000)) {
            return Decision.reject("actual hours must be between 0 and 1000");
        }
        if (task.status() == to && to == TaskStatus.DONE && actualHours != null
                && (task.actualHours() == null || Math.abs(task.actualHours() - actualHours) > 1e-9)) {
            return Decision.accept(List.of(LedgerEvent.of(Facts.TASK_STATUS_CHANGED, new Facts.TaskStatusChanged(
                    state.goalId(), task.id(), TaskStatus.DONE, TaskStatus.DONE, reason, actualHours, principal),
                    principal, null)));
        }
        if (task.status() == to) {
            return Decision.reject(task.key() + " is already " + readable(to)
                    + (actualHours != null ? " with " + actualHours + " h recorded" : ""));
        }
        if (!task.status().canMoveTo(to)) {
            return Decision.reject(task.key() + " cannot move from " + readable(task.status()) + " to " + readable(to));
        }
        // A person may start or finish a task before the tasks planned ahead of it: the order is the plan's guess, the
        // status is what the person says happened.
        List<LedgerEvent> events = new ArrayList<>();
        events.add(LedgerEvent.of(Facts.TASK_STATUS_CHANGED, new Facts.TaskStatusChanged(state.goalId(), task.id(),
                task.status(), to, reason, actualHours, principal), principal, null));
        long openAfter = state.tasks().stream().filter(t -> !t.id().equals(task.id()) && t.status().isOpen()).count()
                + (to.isOpen() ? 1 : 0);
        long doneAfter = state.tasks().stream().filter(t -> !t.id().equals(task.id()) && t.status() == TaskStatus.DONE)
                .count() + (to == TaskStatus.DONE ? 1 : 0);
        if (openAfter == 0 && doneAfter > 0 && state.status() != GoalStatus.COMPLETED) {
            events.add(LedgerEvent.of(Facts.GOAL_STATUS_CHANGED, new Facts.GoalStatusChanged(state.goalId(),
                    state.status(), GoalStatus.COMPLETED, "all tasks done"), principal, null));
        } else if (openAfter > 0 && state.status() == GoalStatus.COMPLETED) {
            events.add(LedgerEvent.of(Facts.GOAL_STATUS_CHANGED, new Facts.GoalStatusChanged(state.goalId(),
                    state.status(), GoalStatus.ACTIVE, "task reopened"), principal, null));
        }
        return Decision.accept(events);
    }

    /**
     * A new deadline for the goal; it may lie neither in the past nor more than {@link #MAX_YEARS_AHEAD} years away.
     */
    public static Decision reschedule(GoalState state, LocalDate to, LocalDate today, String reason, String principal) {
        if (!state.exists()) {
            return Decision.reject("unknown goal");
        }
        if (state.status() == GoalStatus.CANCELLED) {
            return Decision.reject("goal is cancelled");
        }
        if (to == null) {
            return Decision.reject("a new deadline is needed");
        }
        if (to.isBefore(today)) {
            return Decision.reject("the new deadline " + to + " is in the past");
        }
        if (to.isAfter(latestDate())) {
            return Decision.reject("the new deadline " + to + " is more than " + MAX_YEARS_AHEAD + " years away");
        }
        if (to.equals(state.dueDate())) {
            return Decision.accept(List.of());
        }
        return Decision.accept(List.of(LedgerEvent.of(Facts.GOAL_RESCHEDULED,
                new Facts.GoalRescheduled(state.goalId(), state.dueDate(), to, reason, principal), principal, null)));
    }

    /**
     * Moves the goal's deadline and, when it had one, the due dates of its open tasks by the same number of days
     * (never before today), as one decision: either every fact is recorded or none is. The task moves are written
     * as {@code editor}, the person's manifest, so they count as the person's own edits.
     */
    public static Decision reschedulePlan(GoalState state, LocalDate to, LocalDate today, String reason,
                                          CapabilityManifest editor, String principal, int maxTasks) {
        Decision deadline = reschedule(state, to, today, reason, principal);
        if (!deadline.ok() || deadline.events().isEmpty() || state.dueDate() == null) {
            return deadline;
        }
        long delta = ChronoUnit.DAYS.between(state.dueDate(), to);
        List<Effects.TaskUpdate> updates = new ArrayList<>();
        for (TaskView t : state.tasks()) {
            if (t.status().isOpen() && t.dueDate() != null) {
                LocalDate shifted = t.dueDate().plusDays(delta);
                updates.add(new Effects.TaskUpdate(t.key(), null, null, null,
                        shifted.isBefore(today) ? today : shifted, null, null));
            }
        }
        if (updates.isEmpty()) {
            return deadline;
        }
        GoalState moved = state;
        for (LedgerEvent e : deadline.events()) {
            moved = moved.apply(e);
        }
        Decision tasks = applyEffects(moved, Effects.ofUpdates(updates), editor, principal, null, maxTasks, randomIds());
        if (!tasks.ok()) {
            return tasks;
        }
        List<LedgerEvent> all = new ArrayList<>(deadline.events());
        all.addAll(tasks.events());
        return Decision.accept(all);
    }

    /** An enum value as a person reads it: {@code IN_PROGRESS} becomes "in progress". */
    public static String readable(Enum<?> value) {
        return value == null ? "" : value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Random UUIDs, the ids of new tasks. */
    public static Supplier<String> randomIds() {
        return () -> UUID.randomUUID().toString();
    }
}
