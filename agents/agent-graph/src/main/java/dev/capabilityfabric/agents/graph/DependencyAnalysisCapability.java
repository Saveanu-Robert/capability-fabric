package dev.capabilityfabric.agents.graph;

import dev.capabilityfabric.algorithms.TaskGraph;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.CapabilityRequest;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.CapabilityHandler;
import dev.capabilityfabric.sdk.CapabilityOutcome;
import dev.capabilityfabric.sdk.InvocationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds blocked work, dependency depth, execution order and the critical path. When effort estimates
 * exist (from whichever provider supplied them), the critical path gets a length in hours.
 */
@Component
public class DependencyAnalysisCapability implements CapabilityHandler {

    @Override
    public CapabilityManifest manifest() {
        return CapabilityManifest.builder("deps.analyze", "1.0.0")
                .name("Dependency analysis")
                .description("Analyses task dependencies: which tasks are blocked by unfinished work, the order "
                        + "to do them in, and the critical path (in hours when effort estimates exist).")
                .kind(CapabilityManifest.Kind.DETERMINISTIC)
                .consumes(Vocabulary.TASKS, Vocabulary.TASK_STATUS, Vocabulary.TASK_DEPENDS_ON,
                        Vocabulary.TASK_EFFORT_HOURS)
                .requires(Vocabulary.TASKS)
                .produces(Vocabulary.taskAnnotation("deps.blockedBy"), Vocabulary.taskAnnotation("deps.depth"),
                        Vocabulary.taskAnnotation("deps.critical"), Vocabulary.goalAnnotation("deps.order"),
                        Vocabulary.goalAnnotation("deps.criticalPathHours"))
                .annotationSchemas(Map.of(
                        "deps.blockedBy", Json.tree("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"),
                        "deps.depth", Json.tree("{\"type\":\"integer\",\"minimum\":0}"),
                        "deps.critical", Json.tree("{\"type\":\"boolean\"}"),
                        "deps.order", Json.tree("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"),
                        "deps.criticalPathHours", Json.tree("{\"type\":[\"number\",\"null\"],\"minimum\":0}")))
                .tags("dependencies", "blocked", "critical path", "scheduling")
                .cost(50, 0)
                .build();
    }

    @Override
    public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
        return CapabilityOutcome.of(analyse(request.context()));
    }

    /**
     * Annotations from the dependency graph: what blocks each task, its depth, whether it lies on the critical
     * path, the goal's task order and the critical path's hours. The critical path is the longest chain of open
     * tasks, each waiting on the one before: a closed task holds nothing up, so its edges join no chain (an open task
     * that waits only on a done one can start now). An open task without an estimate weighs one hour on the path,
     * and the goal's critical-path hours are {@code null} until every open task is estimated.
     */
    static Effects analyse(GoalContext context) {
        Map<String, TaskView> byId = context.tasksById();
        Map<String, List<String>> edges = new LinkedHashMap<>();
        byId.values().forEach(t -> edges.put(t.id(), t.dependsOn().stream().filter(byId::containsKey).toList()));
        TaskGraph graph = new TaskGraph(edges);
        Map<String, Integer> depth = graph.depth();
        Map<String, List<String>> openEdges = new LinkedHashMap<>();
        Map<String, Double> weights = new HashMap<>();
        boolean allEstimated = true;
        for (TaskView t : byId.values()) {
            if (t.status().isOpen()) {
                if (t.effortHours() == null) {
                    allEstimated = false;
                }
                weights.put(t.id(), t.effortHours() == null ? 1.0 : t.effortHours());
                openEdges.put(t.id(), t.dependsOn().stream().map(byId::get)
                        .filter(d -> d != null && d.status().isOpen()).map(TaskView::id).toList());
            }
        }
        TaskGraph.CriticalPath critical = new TaskGraph(openEdges).criticalPath(weights);

        List<Effects.TaskUpdate> updates = new ArrayList<>();
        for (TaskView t : byId.values()) {
            List<String> blockedBy = t.dependsOn().stream().map(byId::get)
                    .filter(d -> d != null && d.status().isOpen()).map(TaskView::key).toList();
            Map<String, JsonNode> annotations = new LinkedHashMap<>();
            annotations.put("deps.blockedBy", Json.toTree(t.status().isOpen() ? blockedBy : List.of()));
            annotations.put("deps.depth", Json.toTree(depth.get(t.id())));
            annotations.put("deps.critical", Json.toTree(t.status().isOpen() && critical.nodes().contains(t.id())));
            updates.add(Effects.TaskUpdate.annotate(t.id(), annotations));
        }
        Map<String, JsonNode> goal = new LinkedHashMap<>();
        goal.put("deps.order", Json.toTree(graph.topologicalOrder().stream().map(id -> byId.get(id).key()).toList()));
        goal.put("deps.criticalPathHours", allEstimated ? Json.toTree(critical.totalHours()) : Json.toTree(null));
        return new Effects(List.of(), updates, goal, null);
    }
}
