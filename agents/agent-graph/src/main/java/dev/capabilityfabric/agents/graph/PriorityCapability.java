package dev.capabilityfabric.agents.graph;

import dev.capabilityfabric.algorithms.PriorityScorer;
import dev.capabilityfabric.algorithms.WorkdayCalendar;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic priority and rank. Optional inputs (effort hours, deadline risk) are used when some provider has
 * supplied them; this code does not know or care which provider that was. Working days left are counted here, on
 * the scoring day, with the person's working days and holidays: a count stored on an earlier day would be stale.
 */
@Component
public class PriorityCapability implements CapabilityHandler {

    @Override
    public CapabilityManifest manifest() {
        return CapabilityManifest.builder("priority.score", "1.0.0")
                .name("Priority scoring")
                .description("Scores task priority (0-100) and rank from due dates, how much other work each "
                        + "task unblocks, and, when known, effort estimates, working days left and deadline risk.")
                .kind(CapabilityManifest.Kind.DETERMINISTIC)
                .consumes(Vocabulary.TASKS, Vocabulary.TASK_STATUS, Vocabulary.TASK_DUE_DATE,
                        Vocabulary.TASK_DEPENDS_ON, Vocabulary.TASK_EFFORT_HOURS, Vocabulary.GOAL_DUE_DATE,
                        Vocabulary.GOAL_TODAY, Vocabulary.PERSON_WORKING_DAYS, Vocabulary.PERSON_HOLIDAYS,
                        Vocabulary.taskAnnotation("risk.deadline"))
                .requires(Vocabulary.TASKS)
                .produces(Vocabulary.TASK_PRIORITY, Vocabulary.TASK_RANK, Vocabulary.taskAnnotation("priority.reason"))
                .annotationSchemas(Map.of("priority.reason", Json.tree("{\"type\":\"string\",\"maxLength\":300}")))
                .tags("priority", "ranking", "what to do next")
                .cost(50, 0)
                .build();
    }

    @Override
    public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
        return CapabilityOutcome.of(score(request.context()));
    }

    /** Priority, rank and the reason in words for every task, with the person's calendar and the deadline risk. */
    static Effects score(GoalContext context) {
        LocalDate today = context.today() == null ? LocalDate.now() : context.today();
        LocalDate goalDue = context.goal() == null ? null : context.goal().dueDate();
        List<PriorityScorer.Input> inputs = context.tasks().stream()
                .map(t -> new PriorityScorer.Input(t, null, riskLevel(t))).toList();
        List<PriorityScorer.Score> scores = new PriorityScorer(calendar(context)).score(inputs, today, goalDue);
        List<Effects.TaskUpdate> updates = scores.stream().map(s -> new Effects.TaskUpdate(s.taskId(), null,
                s.priority(), s.rank(), null, null, Map.of("priority.reason", Json.toTree(s.reason())))).toList();
        return Effects.ofUpdates(updates);
    }

    /** The person's working days and holidays when the kernel knows them; Monday to Friday otherwise. */
    static WorkdayCalendar calendar(GoalContext context) {
        List<String> days = new ArrayList<>();
        context.person("workingDays").filter(JsonNode::isArray).ifPresent(n -> n.forEach(d -> days.add(d.asString())));
        List<String> holidays = new ArrayList<>();
        context.person("holidays").filter(JsonNode::isArray).ifPresent(n -> n.forEach(d -> holidays.add(d.asString())));
        return WorkdayCalendar.of(days, holidays);
    }

    private static String riskLevel(TaskView t) {
        return t.annotation("risk.deadline").map(n -> n.path("level").asString(null)).orElse(null);
    }
}
