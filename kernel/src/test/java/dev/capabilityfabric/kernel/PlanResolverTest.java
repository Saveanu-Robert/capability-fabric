package dev.capabilityfabric.kernel;

import dev.capabilityfabric.kernel.planning.PlanComposer;
import dev.capabilityfabric.kernel.planning.PlanResolver;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Plans.PlanStep;
import dev.capabilityfabric.protocol.Vocabulary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlanResolverTest {

    private static CapabilityManifest cap(String id, CapabilityManifest.Kind kind, List<String> consumes,
                                          List<String> requires, List<String> produces) {
        return new CapabilityManifest(id, "1.0.0", id, "p-" + id, "i-" + id, "test capability " + id, kind,
                CapabilityManifest.Mode.INVOKED, consumes, requires, produces, Map.of(), null, List.of(), List.of(),
                null, null, Instant.now(), 15);
    }

    private static final CapabilityManifest DECOMPOSE = cap("goal.decompose", CapabilityManifest.Kind.AI,
            List.of(Vocabulary.GOAL_TEXT), List.of(Vocabulary.GOAL_TEXT), List.of(Vocabulary.TASKS));
    private static final CapabilityManifest DEPS = cap("deps.analyze", CapabilityManifest.Kind.DETERMINISTIC,
            List.of(Vocabulary.TASKS, Vocabulary.TASK_EFFORT_HOURS), List.of(Vocabulary.TASKS),
            List.of("task.ann.deps.blockedBy"));
    private static final CapabilityManifest PRIORITY = cap("priority.score", CapabilityManifest.Kind.DETERMINISTIC,
            List.of(Vocabulary.TASKS, Vocabulary.TASK_EFFORT_HOURS, "task.ann.calendar.workdaysLeft"),
            List.of(Vocabulary.TASKS), List.of(Vocabulary.TASK_PRIORITY, Vocabulary.TASK_RANK));
    private static final CapabilityManifest ESTIMATE = cap("task.estimate", CapabilityManifest.Kind.AI,
            List.of(Vocabulary.TASKS), List.of(Vocabulary.TASKS), List.of(Vocabulary.TASK_EFFORT_HOURS));
    private static final CapabilityManifest CALENDAR = cap("calendar.workdays", CapabilityManifest.Kind.DETERMINISTIC,
            List.of(Vocabulary.TASK_DUE_DATE), List.of(Vocabulary.TASKS), List.of("task.ann.calendar.workdaysLeft"));

    private static Map<String, CapabilityManifest> available(CapabilityManifest... caps) {
        Map<String, CapabilityManifest> map = new LinkedHashMap<>();
        for (CapabilityManifest c : caps) {
            map.put(c.id(), c);
        }
        return map;
    }

    private static List<String> order(PlanResolver.Resolution r) {
        return r.steps().stream().map(PlanStep::capability).toList();
    }

    @Test
    void ordersByDataFlowAndBackwardChainsMissingInputs() {
        var resolution = new PlanResolver().resolve(List.of("priority.score", "deps.analyze"),
                available(DECOMPOSE, DEPS, PRIORITY), Set.of(Vocabulary.GOAL_TEXT), 12);

        assertThat(order(resolution)).containsExactly("goal.decompose", "deps.analyze", "priority.score");
        assertThat(resolution.notes()).anySatisfy(n -> assertThat(n).contains("added goal.decompose"));
        assertThat(resolution.steps().getFirst().optional()).isFalse();
    }

    @Test
    void newcomerIsPlacedBeforeTheCapabilitiesThatConsumeItsOutput() {
        var withoutEstimator = new PlanResolver().resolve(List.of("goal.decompose", "deps.analyze", "priority.score"),
                available(DECOMPOSE, DEPS, PRIORITY), Set.of(Vocabulary.GOAL_TEXT), 12);
        var withEstimator = new PlanResolver().resolve(
                List.of("goal.decompose", "deps.analyze", "priority.score", "task.estimate", "calendar.workdays"),
                available(DECOMPOSE, DEPS, PRIORITY, ESTIMATE, CALENDAR), Set.of(Vocabulary.GOAL_TEXT), 12);

        assertThat(order(withoutEstimator)).containsExactly("goal.decompose", "deps.analyze", "priority.score");
        List<String> steps = order(withEstimator);
        assertThat(steps.indexOf("task.estimate")).isLessThan(steps.indexOf("deps.analyze"));
        assertThat(steps.indexOf("task.estimate")).isLessThan(steps.indexOf("priority.score"));
        assertThat(steps.indexOf("calendar.workdays")).isLessThan(steps.indexOf("priority.score"));
        assertThat(withEstimator.steps().stream().filter(s -> s.capability().equals("task.estimate")).findFirst()
                .orElseThrow().optional()).isTrue();
    }

    @Test
    void doesNotDecomposeAGoalThatAlreadyHasTasks() {
        var resolution = new PlanResolver().resolve(List.of("goal.decompose", "priority.score"),
                available(DECOMPOSE, PRIORITY), Set.of(Vocabulary.GOAL_TEXT, Vocabulary.TASKS), 12);

        assertThat(order(resolution)).containsExactly("priority.score");
    }

    @Test
    void dropsCapabilitiesWhoseRequiredInputsNobodySupplies() {
        var resolution = new PlanResolver().resolve(List.of("priority.score"), available(PRIORITY),
                Set.of(Vocabulary.GOAL_TEXT), 12);

        assertThat(resolution.steps()).isEmpty();
        assertThat(resolution.notes()).anySatisfy(n -> assertThat(n).contains("nobody supplies"));
    }

    @Test
    void refreshSelectsDeterministicConsumersOfChangedTokensAndTheirDownstream() throws Exception {
        var method = PlanComposer.class.getDeclaredMethod("refreshSelection", Map.class, Set.class);
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> selected = (List<String>) method.invoke(null,
                available(DECOMPOSE, DEPS, PRIORITY, ESTIMATE, CALENDAR), Set.of(Vocabulary.TASK_EFFORT_HOURS));

        assertThat(selected).containsExactlyInAnyOrder("deps.analyze", "priority.score");
    }

    @Test
    void anUnsatisfiableChainIsDroppedInsteadOfOscillating() {
        // A requires x; B supplies x but requires y; nobody supplies y.
        var a = cap("a.need", CapabilityManifest.Kind.DETERMINISTIC, List.of("task.ann.x.v"), List.of("task.ann.x.v"),
                List.of(Vocabulary.TASK_PRIORITY));
        var b = cap("b.supply", CapabilityManifest.Kind.DETERMINISTIC, List.of("task.ann.y.v"), List.of("task.ann.y.v"),
                List.of("task.ann.x.v"));

        var resolution = new PlanResolver().resolve(List.of("a.need"), available(a, b), Set.of(Vocabulary.TASKS), 10);

        assertThat(resolution.steps()).isEmpty();
        assertThat(resolution.notes()).anyMatch(n -> n.contains("dropped a.need"));
    }

    private static final String X = "task.ann.x.v";
    private static final String Y = "task.ann.y.v";
    private static final String W = "task.ann.w.v";

    @Test
    void aHelperAddedOnlyForAConsumerThatWasDroppedIsDroppedToo() {
        // Z requires a (x) and b (y); A supplies x; nobody supplies y.
        var z = cap("z.need", CapabilityManifest.Kind.DETERMINISTIC, List.of(X, Y), List.of(X, Y),
                List.of(Vocabulary.TASK_PRIORITY));
        var a = cap("a.supply", CapabilityManifest.Kind.DETERMINISTIC, List.of(), List.of(), List.of(X));

        var resolution = new PlanResolver().resolve(List.of("z.need"), available(z, a), Set.of(Vocabulary.TASKS), 10);

        assertThat(resolution.steps()).isEmpty();
        assertThat(resolution.notes()).anyMatch(n -> n.contains("added a.supply to supply " + X + " for z.need"))
                .anyMatch(n -> n.contains("dropped z.need"))
                .anyMatch(n -> n.contains("dropped a.supply: it was only added for z.need"));
    }

    @Test
    void aHelperStaysWhileAnotherChosenCapabilityNeedsIt() {
        var z = cap("z.need", CapabilityManifest.Kind.DETERMINISTIC, List.of(X, Y), List.of(X, Y),
                List.of(Vocabulary.TASK_PRIORITY));
        var other = cap("o.need", CapabilityManifest.Kind.DETERMINISTIC, List.of(X), List.of(X),
                List.of(Vocabulary.TASK_RANK));
        var a = cap("a.supply", CapabilityManifest.Kind.DETERMINISTIC, List.of(), List.of(), List.of(X));

        var resolution = new PlanResolver().resolve(List.of("z.need", "o.need"), available(z, other, a),
                Set.of(Vocabulary.TASKS), 10);

        assertThat(order(resolution)).containsExactly("a.supply", "o.need");
        assertThat(resolution.steps().getFirst().optional()).as("o.need requires what only it supplies").isFalse();
    }

    @Test
    void aChainOfHelpersGoesWithTheConsumerItWasBuiltFor() {
        // Z requires x and y; A supplies x but requires w; B supplies w; nobody supplies y.
        var z = cap("z.need", CapabilityManifest.Kind.DETERMINISTIC, List.of(X, Y), List.of(X, Y),
                List.of(Vocabulary.TASK_PRIORITY));
        var a = cap("a.supply", CapabilityManifest.Kind.DETERMINISTIC, List.of(W), List.of(W), List.of(X));
        var b = cap("b.supply", CapabilityManifest.Kind.DETERMINISTIC, List.of(), List.of(), List.of(W));

        var resolution = new PlanResolver().resolve(List.of("z.need"), available(z, a, b), Set.of(Vocabulary.TASKS),
                10);

        assertThat(resolution.steps()).isEmpty();
    }

    @Test
    void aSelectedCapabilityIsNeverPrunedAsAHelper() {
        var a = cap("a.supply", CapabilityManifest.Kind.DETERMINISTIC, List.of(), List.of(), List.of(X));

        var resolution = new PlanResolver().resolve(List.of("a.supply"), available(a), Set.of(Vocabulary.TASKS), 10);

        assertThat(order(resolution)).containsExactly("a.supply");
    }
}
