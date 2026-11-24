package dev.capabilityfabric.kernel.planning;

import dev.capabilityfabric.algorithms.TaskGraph;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.Plans.PlanStep;
import dev.capabilityfabric.protocol.TaskStatus;
import dev.capabilityfabric.protocol.Vocabulary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a set of chosen capabilities into an ordered, executable plan. Deterministic, no model involved:
 * <ol>
 *   <li>keeps only goal-processing capabilities (they produce something);</li>
 *   <li>skips task creation when the goal already has tasks;</li>
 *   <li>adds providers for required inputs nobody supplies yet (backward chaining), and drops such a helper again
 *       when nothing left in the plan needs what it supplies;</li>
 *   <li>orders steps so that producers of a token run before its consumers;</li>
 *   <li>marks a step optional unless a later step requires what only it produces.</li>
 * </ol>
 * A newly joined capability takes part simply by declaring what it consumes and produces. The tokens a goal has
 * come from {@link #presentTokens(GoalContext)}, which leaves dropped tasks out: a goal whose tasks were all dropped
 * has no tasks, so task creation is planned again.
 */
public final class PlanResolver {

    /** The ordered steps and what was ignored, added, dropped or truncated on the way, in words. */
    public record Resolution(List<PlanStep> steps, List<String> notes) {
    }

    /**
     * The vocabulary tokens the goal has for planning: those of its context without the dropped tasks, which are
     * no longer part of the goal's work. When every task was dropped, the goal has no {@code tasks} token.
     */
    public static Set<String> presentTokens(GoalContext context) {
        List<GoalContext.TaskView> kept = context.tasks().stream()
                .filter(t -> t.status() != TaskStatus.DROPPED).toList();
        if (kept.size() == context.tasks().size()) {
            return context.presentTokens();
        }
        return new GoalContext(context.goal(), kept, context.today(), context.person()).presentTokens();
    }

    /**
     * Resolves {@code selected} capability ids against the {@code available} manifests (by id), given the tokens
     * the goal already has ({@code present}), into at most {@code maxSteps} ordered steps.
     */
    public Resolution resolve(List<String> selected, Map<String, CapabilityManifest> available, Set<String> present,
                              int maxSteps) {
        List<String> notes = new ArrayList<>();
        boolean tasksExist = present.contains(Vocabulary.TASKS);
        Set<String> chosen = new LinkedHashSet<>();
        for (String id : selected) {
            CapabilityManifest m = available.get(id);
            if (m == null) {
                notes.add("ignored " + id + ": not available");
            } else if (m.produces().isEmpty()) {
                notes.add("ignored " + id + ": produces nothing for the goal");
            } else if (tasksExist && m.produces().contains(Vocabulary.TASKS)) {
                notes.add("skipped " + id + ": the goal already has tasks");
            } else {
                chosen.add(id);
            }
        }

        // Backward chaining and pruning until nothing changes. A capability dropped for an unsatisfiable input is
        // never added back, so the loop cannot oscillate; it ends after at most one add and one drop per capability.
        Set<String> dropped = new HashSet<>();
        // Capabilities added by chaining, with the consumers they were added for.
        Map<String, Set<String>> helpers = new LinkedHashMap<>();
        boolean changed = true;
        for (int pass = 0; changed && pass < 2 * available.size() + 2; pass++) {
            changed = false;
            for (String id : List.copyOf(chosen)) {
                CapabilityManifest m = available.get(id);
                for (String required : m.requires()) {
                    if (present.contains(required) || producedBy(chosen, available, required, id)) {
                        continue;
                    }
                    Optional<CapabilityManifest> provider = available.values().stream()
                            .filter(p -> !chosen.contains(p.id()) && !dropped.contains(p.id())
                                    && p.produces().contains(required))
                            .filter(p -> !(tasksExist && p.produces().contains(Vocabulary.TASKS)))
                            .min(Comparator.comparingLong((CapabilityManifest p) -> p.cost().expectedLatencyMs())
                                    .thenComparing(CapabilityManifest::id));
                    if (provider.isPresent()) {
                        chosen.add(provider.get().id());
                        helpers.computeIfAbsent(provider.get().id(), k -> new LinkedHashSet<>()).add(id);
                        notes.add("added " + provider.get().id() + " to supply " + required + " for " + id);
                    } else {
                        chosen.remove(id);
                        dropped.add(id);
                        notes.add("dropped " + id + ": nobody supplies required " + required);
                    }
                    changed = true;
                    break;
                }
            }
        }
        // Whatever still lacks a required input after chaining is dropped too, until the set is consistent.
        for (boolean pruned = true; pruned; ) {
            pruned = false;
            for (String id : List.copyOf(chosen)) {
                for (String required : available.get(id).requires()) {
                    if (!present.contains(required) && !producedBy(chosen, available, required, id)) {
                        chosen.remove(id);
                        notes.add("dropped " + id + ": nobody supplies required " + required);
                        pruned = true;
                        break;
                    }
                }
            }
        }

        // A helper that no remaining capability needs (its consumer was dropped) is dropped too, until none is left.
        for (boolean pruned = true; pruned; ) {
            pruned = false;
            for (String id : List.copyOf(chosen)) {
                if (helpers.containsKey(id) && !neededByOthers(id, chosen, available, present)) {
                    chosen.remove(id);
                    notes.add("dropped " + id + ": it was only added for " + String.join(", ", helpers.get(id))
                            + ", which left the plan");
                    pruned = true;
                }
            }
        }

        // Order by data flow. Insertion order (task creators first, then id) breaks ties.
        List<String> ordered = chosen.stream()
                .sorted(Comparator.comparing((String id) -> !available.get(id).produces().contains(Vocabulary.TASKS))
                        .thenComparing(id -> id))
                .toList();
        Map<String, List<String>> after = edges(ordered, available, false);
        TaskGraph graph = new TaskGraph(after);
        if (!graph.isAcyclic()) {
            after = edges(ordered, available, true);
            graph = new TaskGraph(after);
            notes.add("data-flow cycle: ordered by required inputs only");
            if (!graph.isAcyclic()) {
                after = new LinkedHashMap<>();
                for (String id : ordered) {
                    after.put(id, List.of());
                }
                graph = new TaskGraph(after);
                notes.add("data-flow cycle persists: ordered by id");
            }
        }
        List<String> sequence = graph.topologicalOrder();
        if (sequence.size() > maxSteps) {
            notes.add("plan truncated to " + maxSteps + " steps");
            sequence = sequence.subList(0, maxSteps);
        }

        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < sequence.size(); i++) {
            String id = sequence.get(i);
            CapabilityManifest m = available.get(id);
            boolean required = m.produces().contains(Vocabulary.TASKS) && !tasksExist;
            for (String later : sequence.subList(i + 1, sequence.size())) {
                for (String token : available.get(later).requires()) {
                    if (m.produces().contains(token) && !present.contains(token)
                            && !producedBy(Set.copyOf(sequence), available, token, id)) {
                        required = true;
                    }
                }
            }
            steps.add(new PlanStep(i, id, m.major(), m.autonomy(), !required, after.getOrDefault(id, List.of()), null));
        }
        return new Resolution(steps, notes);
    }

    /** Edges "consumer runs after producer". With {@code requiredOnly}, only required inputs create edges. */
    private static Map<String, List<String>> edges(List<String> ordered, Map<String, CapabilityManifest> available,
                                                   boolean requiredOnly) {
        Map<String, List<String>> after = new LinkedHashMap<>();
        for (String consumer : ordered) {
            CapabilityManifest c = available.get(consumer);
            Set<String> inputs = new HashSet<>(requiredOnly ? c.requires() : c.consumes());
            inputs.addAll(c.requires());
            List<String> producers = new ArrayList<>();
            for (String producer : ordered) {
                if (!producer.equals(consumer)
                        && available.get(producer).produces().stream().anyMatch(inputs::contains)) {
                    producers.add(producer);
                }
            }
            after.put(consumer, producers);
        }
        return after;
    }

    /** Some other chosen capability requires a missing input that {@code helper} produces. */
    private static boolean neededByOthers(String helper, Set<String> chosen, Map<String, CapabilityManifest> available,
                                          Set<String> present) {
        List<String> supplies = available.get(helper).produces();
        return chosen.stream().filter(id -> !id.equals(helper))
                .anyMatch(id -> available.get(id).requires().stream()
                        .anyMatch(r -> !present.contains(r) && supplies.contains(r)));
    }

    /** Whether a chosen capability other than {@code except} produces the token. */
    private static boolean producedBy(Set<String> chosen, Map<String, CapabilityManifest> available, String token,
                                      String except) {
        return chosen.stream().filter(id -> !id.equals(except))
                .anyMatch(id -> available.get(id).produces().contains(token));
    }
}
