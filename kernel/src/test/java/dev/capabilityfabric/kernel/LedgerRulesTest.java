package dev.capabilityfabric.kernel;

import dev.capabilityfabric.algorithms.TaskGraph;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerEvent;
import dev.capabilityfabric.kernel.ledger.LedgerRules;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.TaskStatus;
import dev.capabilityfabric.protocol.Vocabulary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerRulesTest {

    private static final CapabilityManifest DECOMPOSER = manifest("goal.decompose", Vocabulary.TASKS);
    private static final CapabilityManifest ESTIMATOR = manifest("task.estimate", Vocabulary.TASK_EFFORT_HOURS);
    private static final CapabilityManifest SENTINEL = CapabilityManifest.builder("deadline.watch", "1.0.0")
            .description("Flags deadline risk on open tasks")
            .produces("task.ann.risk.deadline")
            .annotationSchemas(Map.of("risk.deadline", Json.tree("""
                    {"type":"object","required":["level"],"properties":{"level":{"enum":["LOW","HIGH"]}}}""")))
            .build().withInstance("agent-sentinel", "s1", Instant.now(), 15);

    private static CapabilityManifest manifest(String id, String... produces) {
        return CapabilityManifest.builder(id, "1.0.0").description("test capability " + id).produces(produces)
                .build().withInstance("p", "i", Instant.now(), 15);
    }

    private static Supplier<String> ids() {
        AtomicInteger n = new AtomicInteger();
        return () -> "id-" + n.incrementAndGet();
    }

    private static GoalState goal() {
        var created = LedgerRules.createGoal("g1", "Launch website", null, "human:test");
        return GoalState.fold("g1", created.events());
    }

    private static GoalState apply(GoalState state, LedgerRules.Decision decision) {
        assertThat(decision.errors()).isEmpty();
        GoalState s = state;
        for (LedgerEvent e : decision.events()) {
            s = s.apply(e);
        }
        return s;
    }

    private static Effects tasks(Effects.NewTask... tasks) {
        return new Effects(List.of(tasks), List.of(), Map.of(), null);
    }

    private static Effects.NewTask task(String ref, String title, String... deps) {
        return new Effects.NewTask(ref, title, null, null, List.of(deps), null);
    }

    @Test
    void createsTasksWithStableKeysAndResolvedDependencies() {
        GoalState s = apply(goal(), LedgerRules.applyEffects(goal(), tasks(task("a", "Buy domain"),
                task("b", "Build site", "a")), DECOMPOSER, "agent:d/1", "r1", 40, ids()));

        assertThat(s.tasks()).extracting("key").containsExactly("T1", "T2");
        assertThat(s.task("T2").orElseThrow().dependsOn()).containsExactly(s.task("T1").orElseThrow().id());
        assertThat(s.status()).isEqualTo(GoalStatus.PLANNING);
    }

    @Test
    void rejectsWritesToUndeclaredTokens() {
        GoalState s = apply(goal(), LedgerRules.applyEffects(goal(), tasks(task("a", "Buy domain")), DECOMPOSER,
                "agent:d/1", "r1", 40, ids()));
        Effects priorities = Effects.ofUpdates(List.of(new Effects.TaskUpdate("T1", null, 90, null, null, null, null)));

        var decision = LedgerRules.applyEffects(s, priorities, ESTIMATOR, "agent:e/1", "r2", 40, ids());

        assertThat(decision.ok()).isFalse();
        assertThat(decision.errors().getFirst()).contains("did not declare");
    }

    @Test
    void rejectsDependencyCycles() {
        GoalState s = apply(goal(), LedgerRules.applyEffects(goal(), tasks(task("a", "One"), task("b", "Two", "a")),
                DECOMPOSER, "agent:d/1", "r1", 40, ids()));
        CapabilityManifest linker = manifest("deps.link", Vocabulary.TASK_DEPENDS_ON);
        Effects loop = Effects.ofUpdates(List.of(new Effects.TaskUpdate("T1", null, null, null, null, List.of("T2"), null)));

        var decision = LedgerRules.applyEffects(s, loop, linker, "agent:l/1", "r2", 40, ids());

        assertThat(decision.ok()).isFalse();
        assertThat(decision.errors().getFirst()).contains("cycle");
    }

    @Test
    void unchangedValuesProduceNoFacts() {
        GoalState s = apply(goal(), LedgerRules.applyEffects(goal(), tasks(task("a", "One")), DECOMPOSER, "agent:d/1",
                "r1", 40, ids()));
        Effects estimate = Effects.ofUpdates(List.of(new Effects.TaskUpdate("T1", 3.0, null, null, null, null, null)));
        s = apply(s, LedgerRules.applyEffects(s, estimate, ESTIMATOR, "agent:e/1", "r2", 40, ids()));

        var again = LedgerRules.applyEffects(s, estimate, ESTIMATOR, "agent:e/1", "r3", 40, ids());

        assertThat(again.ok()).isTrue();
        assertThat(again.events()).isEmpty();
        assertThat(s.task("T1").orElseThrow().effortHours()).isEqualTo(3.0);
    }

    @Test
    void annotationValuesMustMatchTheDeclaredSchema() {
        GoalState s = apply(goal(), LedgerRules.applyEffects(goal(), tasks(task("a", "One")), DECOMPOSER, "agent:d/1",
                "r1", 40, ids()));
        Effects bad = Effects.ofUpdates(List.of(Effects.TaskUpdate.annotate("T1",
                Map.of("risk.deadline", Json.tree("{\"level\":\"SOMETIME\"}")))));
        Effects good = Effects.ofUpdates(List.of(Effects.TaskUpdate.annotate("T1",
                Map.of("risk.deadline", Json.tree("{\"level\":\"HIGH\"}")))));

        assertThat(LedgerRules.applyEffects(s, bad, SENTINEL, "agent:s/1", "p1", 40, ids()).ok()).isFalse();
        GoalState annotated = apply(s, LedgerRules.applyEffects(s, good, SENTINEL, "agent:s/1", "p2", 40, ids()));
        assertThat(annotated.task("T1").orElseThrow().annotations()).containsKey("risk.deadline");
    }

    @Test
    void aPersonMayStartATaskBeforeTheTasksPlannedAheadOfItAndTheGoalCompletesWhenAllDone() {
        GoalState s = apply(goal(), LedgerRules.applyEffects(goal(), tasks(task("a", "One"), task("b", "Two", "a")),
                DECOMPOSER, "agent:d/1", "r1", 40, ids()));

        assertThat(LedgerRules.changeStatus(s, "T2", TaskStatus.IN_PROGRESS, null, null, "human:x").ok())
                .as("the order is the plan's guess; the person says what happened").isTrue();

        s = apply(s, LedgerRules.changeStatus(s, "T1", TaskStatus.DONE, null, 2.0, "human:x"));
        s = apply(s, LedgerRules.changeStatus(s, "T2", TaskStatus.DONE, null, null, "human:x"));
        assertThat(s.status()).isEqualTo(GoalStatus.COMPLETED);
        assertThat(s.task("T1").orElseThrow().actualHours()).isEqualTo(2.0);
    }

    @Test
    void enforcesTaskBudget() {
        var decision = LedgerRules.applyEffects(goal(), tasks(task("a", "One"), task("b", "Two"), task("c", "Three")),
                DECOMPOSER, "agent:d/1", "r1", 2, ids());
        assertThat(decision.errors()).anySatisfy(e -> assertThat(e).contains("exceed 2 tasks"));
    }

    /** Folding stored events always reproduces the same state, whatever the random effects were. */
    @Property(tries = 100)
    void foldIsDeterministicAndGraphStaysAcyclic(@ForAll @IntRange(min = 1, max = 15) int n,
                                                 @ForAll long seed) {
        Random random = new Random(seed);
        List<Effects.NewTask> batch = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            List<String> deps = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (j != i && random.nextInt(4) == 0) {
                    deps.add("r" + j);
                }
            }
            batch.add(new Effects.NewTask("r" + i, "Task " + i, null, null, deps, null));
        }
        GoalState start = goal();
        var decision = LedgerRules.applyEffects(start, new Effects(batch, List.of(), Map.of(), null), DECOMPOSER,
                "agent:d/1", "r", 40, ids());
        if (!decision.ok()) {
            assertThat(decision.errors()).anySatisfy(e -> assertThat(e).contains("cycle"));
            return;
        }
        GoalState folded = apply(start, decision);
        List<LedgerEvent> all = new ArrayList<>(LedgerRules.createGoal("g1", "Launch website", null, "human:test").events());
        all.addAll(decision.events());
        // Round-trip events through JSON, as the store does.
        List<LedgerEvent> stored = all.stream().map(e -> new LedgerEvent(e.id(), e.type(), Json.tree(Json.write(e.payload())),
                e.principal(), e.causationId(), e.at())).toList();
        assertThat(GoalState.fold("g1", stored).contentHash()).isEqualTo(folded.contentHash());
        Map<String, List<String>> graph = new LinkedHashMap<>();
        folded.tasks().forEach(t -> graph.put(t.id(), t.dependsOn()));
        assertThat(new TaskGraph(graph).isAcyclic()).isTrue();
    }
}
