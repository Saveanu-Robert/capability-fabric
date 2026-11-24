package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The standard output of goal-processing capabilities: proposed ledger changes. The kernel validates every effect
 * against the provider's declared {@code produces} before turning it into facts.
 */
public record Effects(List<NewTask> newTasks, List<TaskUpdate> taskUpdates,
                      Map<String, JsonNode> goalAnnotations, String notes) {

    public Effects {
        newTasks = newTasks == null ? List.of() : List.copyOf(newTasks);
        taskUpdates = taskUpdates == null ? List.of() : List.copyOf(taskUpdates);
        goalAnnotations = goalAnnotations == null ? Map.of() : Map.copyOf(goalAnnotations);
    }

    /** No change at all. */
    public static Effects none() {
        return new Effects(List.of(), List.of(), Map.of(), null);
    }

    public static Effects ofUpdates(List<TaskUpdate> updates) {
        return new Effects(List.of(), updates, Map.of(), null);
    }

    /**
     * A task to create. {@code ref} is the provider's local reference (for example {@code t1});
     * {@code dependsOn} may name other refs from the same batch or keys/ids of existing tasks.
     */
    public record NewTask(String ref, String title, String description, LocalDate dueDate,
                          List<String> dependsOn, Double effortHours) {
        public NewTask {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }

    /** Changes to one existing task, addressed by id or key. Null fields mean "no change". */
    public record TaskUpdate(String task, Double effortHours, Integer priority, Integer rank, LocalDate dueDate,
                             List<String> addDependsOn, Map<String, JsonNode> annotations) {
        public TaskUpdate {
            addDependsOn = addDependsOn == null ? List.of() : List.copyOf(addDependsOn);
            annotations = annotations == null ? Map.of() : Map.copyOf(annotations);
        }

        /** An update that only sets annotations on {@code task}. */
        public static TaskUpdate annotate(String task, Map<String, JsonNode> annotations) {
            return new TaskUpdate(task, null, null, null, null, null, annotations);
        }

        /** The vocabulary tokens this update would write. */
        public Set<String> producedTokens() {
            Set<String> tokens = new LinkedHashSet<>();
            if (effortHours != null) {
                tokens.add(Vocabulary.TASK_EFFORT_HOURS);
            }
            if (priority != null) {
                tokens.add(Vocabulary.TASK_PRIORITY);
            }
            if (rank != null) {
                tokens.add(Vocabulary.TASK_RANK);
            }
            if (dueDate != null) {
                tokens.add(Vocabulary.TASK_DUE_DATE);
            }
            if (!addDependsOn.isEmpty()) {
                tokens.add(Vocabulary.TASK_DEPENDS_ON);
            }
            annotations.keySet().forEach(k -> tokens.add(Vocabulary.taskAnnotation(k)));
            return tokens;
        }
    }

    /** Every vocabulary token these effects would write. */
    public Set<String> producedTokens() {
        Set<String> tokens = new LinkedHashSet<>();
        if (!newTasks.isEmpty()) {
            tokens.add(Vocabulary.TASKS);
            if (newTasks.stream().anyMatch(t -> t.effortHours() != null)) {
                tokens.add(Vocabulary.TASK_EFFORT_HOURS);
            }
        }
        taskUpdates.forEach(u -> tokens.addAll(u.producedTokens()));
        goalAnnotations.keySet().forEach(k -> tokens.add(Vocabulary.goalAnnotation(k)));
        return tokens;
    }

    /** No new task, no update and no goal annotation. */
    public boolean isEmpty() {
        return newTasks.isEmpty() && taskUpdates.isEmpty() && goalAnnotations.isEmpty();
    }

    /** Both effects together; for a goal annotation set by both, {@code other}'s value wins. Notes are joined. */
    public Effects merge(Effects other) {
        List<NewTask> nt = new ArrayList<>(newTasks);
        nt.addAll(other.newTasks);
        List<TaskUpdate> tu = new ArrayList<>(taskUpdates);
        tu.addAll(other.taskUpdates);
        Map<String, JsonNode> ga = new HashMap<>(goalAnnotations);
        ga.putAll(other.goalAnnotations);
        String n = notes == null ? other.notes : other.notes == null ? notes : notes + "; " + other.notes;
        return new Effects(nt, tu, ga, n);
    }
}
