package dev.capabilityfabric.kernel.ecosystem;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.execution.CapabilityInvoker;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerEvent;
import dev.capabilityfabric.kernel.ledger.LedgerRules;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.planning.PlanResolver;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.Plans;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The counterfactual twin: what would this goal look like had one capability never contributed? The goal is
 * rebuilt in memory from its facts without that capability's contributions, and every deterministic capability
 * that worked on the goal runs again on the result (no model is called). The same is done with nothing removed,
 * so both sides are equally fresh and every difference comes from the removed capability alone. Nothing is
 * written to the ledger.
 */
@Component
public class Counterfactual {

    private final LedgerService ledger;
    private final CapabilityRegistry registry;
    private final CapabilityInvoker invoker;
    private final KernelProperties properties;

    public Counterfactual(LedgerService ledger, CapabilityRegistry registry, CapabilityInvoker invoker,
                          KernelProperties properties) {
        this.ledger = ledger;
        this.registry = registry;
        this.invoker = invoker;
        this.properties = properties;
    }

    /**
     * What the goal would look like without {@code capability}'s contributions: tasks ranked differently, tasks
     * that would lose fields and the critical path, in a report with a one-line summary. Capabilities that are not
     * running, or are not deterministic, keep their recorded contributions on both sides.
     */
    public Ecosystem.ImpactReport explain(String goalId, String capability) {
        List<LedgerEvent> events = ledger.events(goalId);
        Provenance provenance = new Provenance(events);
        String goalText = ledger.load(goalId).map(GoalState::text).orElse(goalId);
        List<String> notes = new ArrayList<>();
        if (!provenance.contributed(capability)) {
            return new Ecosystem.ImpactReport(goalId, goalText, capability, 0, 0, 0, null, null, List.of(), List.of(),
                    capability + " has not contributed to this goal, so removing it changes nothing.");
        }
        Map<String, CapabilityManifest> live = registry.invokedCapabilities();
        Set<String> rerun = new LinkedHashSet<>();
        for (String c : provenance.capabilities()) {
            CapabilityManifest m = live.get(c);
            if (m != null && m.kind() == CapabilityManifest.Kind.DETERMINISTIC && !m.produces().isEmpty()) {
                rerun.add(c);
            } else if (m == null && !c.equals(capability)) {
                notes.add(c + (registry.has(c) ? " reacts on its own and was not re-run"
                        : " is not running") + "; its recorded contributions are kept as they are");
            }
        }
        GoalState with = replay(goalId, events, provenance, rerun, null, live, notes);
        Set<String> rerunWithout = new LinkedHashSet<>(rerun);
        rerunWithout.remove(capability);
        GoalState without = replay(goalId, events, provenance, rerunWithout, capability, live, notes);
        return compare(goalId, goalText, capability, with, without, List.copyOf(new LinkedHashSet<>(notes)));
    }

    /**
     * Folds the facts, dropping contributions of {@code removed} and of the capabilities to re-run, then re-runs them.
     */
    private GoalState replay(String goalId, List<LedgerEvent> events, Provenance provenance, Set<String> rerun,
                             String removed, Map<String, CapabilityManifest> live, List<String> notes) {
        GoalState state = GoalState.empty(goalId);
        for (LedgerEvent e : events) {
            if (Provenance.CONTENT.contains(e.type())) {
                String source = provenance.of(e).map(Provenance.Source::capability).orElse(null);
                if (source != null && (source.equals(removed) || rerun.contains(source))) {
                    continue;
                }
            }
            state = state.apply(e);
        }
        Map<String, CapabilityManifest> available = new LinkedHashMap<>();
        rerun.forEach(c -> available.put(c, live.get(c)));
        var present = PlanResolver.presentTokens(state.toContext(properties.today()));
        List<Plans.PlanStep> steps = new PlanResolver().resolve(List.copyOf(rerun), available, present, 20).steps();
        int index = 0;
        for (Plans.PlanStep step : steps) {
            CapabilityManifest m = available.get(step.capability());
            // A what-if call: it must not change anyone's standing.
            var invocation = invoker.invokeQuietly(goalId, "counterfactual", index++, m.id(), m.major(),
                    state.toContext(properties.today()), null, Set.of());
            if (!invocation.ok()) {
                notes.add(m.id() + " could not be re-run: " + invocation.failureReason());
                continue;
            }
            var decision = LedgerRules.applyEffects(state, invocation.result().effects(), invocation.manifest(),
                    "counterfactual", null, properties.maxTasksPerGoal(), LedgerRules.randomIds());
            if (decision.ok()) {
                for (LedgerEvent e : decision.events()) {
                    state = state.apply(e);
                }
            }
        }
        return state;
    }

    /**
     * The differences between the goal with and without the capability, task by task (matched by key): rank,
     * priority, and the fields or the task itself that would be missing. The summary names every kind of
     * difference found.
     */
    static Ecosystem.ImpactReport compare(String goalId, String goalText, String capability, GoalState with,
                                          GoalState without, List<String> notes) {
        Map<String, TaskView> withoutByKey = new HashMap<>();
        without.tasks().forEach(t -> withoutByKey.put(t.key(), t));
        List<Ecosystem.TaskDelta> deltas = new ArrayList<>();
        int reordered = 0;
        int losing = 0;
        int reprioritised = 0;
        for (TaskView t : with.tasks()) {
            TaskView w = withoutByKey.get(t.key());
            List<String> lost = new ArrayList<>();
            if (w == null) {
                lost.add("the task itself");
            } else {
                if (t.effortHours() != null && w.effortHours() == null) {
                    lost.add("effortHours");
                }
                if (t.dueDate() != null && w.dueDate() == null) {
                    lost.add("dueDate");
                }
                for (String key : t.annotations().keySet()) {
                    if (!w.annotations().containsKey(key)) {
                        lost.add(key);
                    }
                }
            }
            boolean moved = w != null && !Objects.equals(t.rank(), w.rank());
            if (moved) {
                reordered++;
            }
            if (!lost.isEmpty()) {
                losing++;
            }
            boolean priorityChanged = w != null && !Objects.equals(t.priority(), w.priority());
            if (priorityChanged) {
                reprioritised++;
            }
            if (moved || !lost.isEmpty() || priorityChanged) {
                deltas.add(new Ecosystem.TaskDelta(t.key(), t.title(), t.rank(), w == null ? null : w.rank(),
                        t.priority(), w == null ? null : w.priority(), lost));
            }
        }
        Double cpWith = number(with.annotations().get("deps.criticalPathHours"));
        Double cpWithout = number(without.annotations().get("deps.criticalPathHours"));
        return new Ecosystem.ImpactReport(goalId, goalText, capability, with.tasks().size(), reordered, losing,
                cpWith, cpWithout, deltas, notes, summary(capability, with.tasks().size(), reordered, losing,
                reprioritised, cpWith, cpWithout, deltas));
    }

    private static String summary(String capability, int tasks, int reordered, int losing, int reprioritised,
                                  Double cpWith, Double cpWithout, List<Ecosystem.TaskDelta> deltas) {
        if (deltas.isEmpty() && Objects.equals(cpWith, cpWithout)) {
            return "Without " + capability + " this goal would look exactly the same.";
        }
        List<String> parts = new ArrayList<>();
        if (reordered > 0) {
            parts.add(reordered + " of " + tasks + " tasks would be ranked differently");
        }
        if (losing > 0) {
            Set<String> fields = new LinkedHashSet<>();
            deltas.forEach(d -> fields.addAll(d.lostFields()));
            parts.add(losing + " tasks would lose " + String.join(", ", fields));
        }
        if (reprioritised > 0) {
            parts.add(reprioritised + " of " + tasks + " tasks would have a different priority");
        }
        if (!Objects.equals(cpWith, cpWithout)) {
            parts.add("the critical path would be " + (cpWithout == null ? "unknown" : cpWithout + " h")
                    + " instead of " + (cpWith == null ? "unknown" : cpWith + " h"));
        }
        return "Without " + capability + ": " + String.join("; ", parts) + ".";
    }

    private static Double number(JsonNode node) {
        return node == null || node.isNull() || !node.isNumber() ? null : node.asDouble();
    }
}
