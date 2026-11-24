package dev.capabilityfabric.agents.sentinel;

import dev.capabilityfabric.algorithms.DeadlineRisk;
import dev.capabilityfabric.algorithms.WorkdayCalendar;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Envelope;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.InvocationContext;
import dev.capabilityfabric.sdk.ReactiveHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Watches task facts and flags deadline risk: own effort plus open dependencies against the working hours left,
 * today's included when today is a working day. It proposes only changed assessments, so its own accepted
 * proposals do not trigger it again. A task that is closed or no longer has a due date keeps no stale risk: its
 * value is set to {@code NONE} with the reason. {@link SentinelSweep} runs the same assessment on a timer, because
 * the risk grows as days pass without any fact announcing it.
 */
@Component
public class DeadlineWatch implements ReactiveHandler {

    /** The reason recorded for a closed task, whose risk no longer applies. */
    static final String CLOSED = "the task is closed";
    /** The reason recorded for an open task without a due date. */
    static final String NO_DUE_DATE = "the task has no due date";

    private final double hoursPerDay;

    /** @param hoursPerDay working hours a day when the person's profile does not say */
    public DeadlineWatch(@Value("${fabric.sentinel.hours-per-day:6}") double hoursPerDay) {
        this.hoursPerDay = hoursPerDay;
    }

    @Override
    public CapabilityManifest manifest() {
        return CapabilityManifest.builder("deadline.watch", "1.0.0")
                .name("Deadline sentinel")
                .description("Watches tasks and flags deadline risk by comparing remaining effort, including "
                        + "unfinished dependencies, with the working hours left before each due date.")
                .kind(CapabilityManifest.Kind.DETERMINISTIC)
                .mode(CapabilityManifest.Mode.REACTIVE)
                .consumes(Vocabulary.TASKS, Vocabulary.TASK_DUE_DATE, Vocabulary.TASK_EFFORT_HOURS,
                        Vocabulary.TASK_STATUS, Vocabulary.TASK_DEPENDS_ON, Vocabulary.GOAL_TODAY,
                        Vocabulary.PERSON_DAILY_HOURS, Vocabulary.PERSON_WORKING_DAYS, Vocabulary.PERSON_HOLIDAYS)
                .produces(Vocabulary.taskAnnotation("risk.deadline"))
                .annotationSchemas(Map.of("risk.deadline", Json.tree("""
                        {"type":"object","required":["level","reason"],
                         "properties":{"level":{"enum":["NONE","LOW","MEDIUM","HIGH","OVERDUE","UNKNOWN"]},
                                       "slackHours":{"type":["number","null"]},
                                       "reason":{"type":"string","maxLength":300}}}""")))
                .triggers(Facts.TASK_ADDED, Facts.TASK_UPDATED, Facts.TASK_STATUS_CHANGED, Facts.DEPENDENCY_ADDED,
                        Facts.RUN_FINISHED)
                .tags("deadline", "risk", "monitoring")
                .cost(50, 0)
                .build();
    }

    @Override
    public Optional<ProposedChange> onFact(Envelope fact, String goalId, InvocationContext context) {
        Optional<GoalContext> goal = context.fabric().flatMap(f -> f.goalContext(goalId));
        return goal.flatMap(this::assess);
    }

    /**
     * The changed risk values of one goal's tasks as a proposal, or empty when every value is current. Tasks with
     * a risk value that no longer applies (closed, or without a due date) get a {@code NONE} value saying why.
     */
    Optional<ProposedChange> assess(GoalContext goal) {
        LocalDate today = goal.today() == null ? LocalDate.now() : goal.today();
        // The person's own capacity and working days, when the kernel knows them; the configured default otherwise.
        double hours = goal.person("dailyHours").filter(JsonNode::isNumber).map(JsonNode::asDouble)
                .filter(h -> h > 0).orElse(hoursPerDay);
        List<String> names = new ArrayList<>();
        goal.person("workingDays").filter(JsonNode::isArray).ifPresent(days -> days.forEach(d -> names.add(d.asString())));
        List<String> holidays = new ArrayList<>();
        goal.person("holidays").filter(JsonNode::isArray).ifPresent(days -> days.forEach(d -> holidays.add(d.asString())));
        WorkdayCalendar calendar = WorkdayCalendar.of(names, holidays);
        Map<String, DeadlineRisk.Assessment> risks = new DeadlineRisk(calendar, hours).assess(goal.tasks(), today);
        List<Effects.TaskUpdate> updates = new ArrayList<>();
        int high = 0;
        for (TaskView task : goal.tasks()) {
            DeadlineRisk.Assessment risk = risks.get(task.id());
            JsonNode current = task.annotations().get("risk.deadline");
            ObjectNode value = Json.object();
            if (risk == null) {
                if (current == null || current.isNull()) {
                    continue; // never assessed: nothing stale to clear
                }
                value.put("level", DeadlineRisk.Level.NONE.name());
                value.put("reason", task.status().isOpen() ? NO_DUE_DATE : CLOSED);
                if (!current.path("level").asString("").equals(DeadlineRisk.Level.NONE.name())
                        || !current.path("reason").asString("").equals(value.path("reason").asString())) {
                    updates.add(Effects.TaskUpdate.annotate(task.id(), Map.of("risk.deadline", value)));
                }
                continue;
            }
            value.put("level", risk.level().name());
            value.put("slackHours", risk.slackHours());
            value.put("reason", risk.reason());
            if (!Objects.equals(current, value)) {
                updates.add(Effects.TaskUpdate.annotate(task.id(), Map.of("risk.deadline", value)));
                if (Set.of(DeadlineRisk.Level.HIGH, DeadlineRisk.Level.OVERDUE).contains(risk.level())) {
                    high++;
                }
            }
        }
        if (updates.isEmpty()) {
            return Optional.empty();
        }
        String reason = "deadline risk changed for " + updates.size() + " task(s)" + (high > 0 ? ", " + high + " at high risk" : "");
        return Optional.of(new ProposedChange(goal.goal().id(), Effects.ofUpdates(updates), reason));
    }
}
