package dev.capabilityfabric.agents.calendar;

import dev.capabilityfabric.algorithms.WorkdayCalendar;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.CapabilityRequest;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.CapabilityHandler;
import dev.capabilityfabric.sdk.CapabilityOutcome;
import dev.capabilityfabric.sdk.InvocationContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Working days left until each task's due date, honouring weekends and configured holidays. */
@Component
public class WorkdaysCapability implements CapabilityHandler {

    private final WorkdayCalendar calendar;
    private final Set<LocalDate> holidays;

    /** @param holidays public holidays as comma-separated ISO dates; empty for none */
    public WorkdaysCapability(@Value("${fabric.calendar.holidays:}") String holidays) {
        Set<LocalDate> days = Arrays.stream(holidays.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(LocalDate::parse).collect(Collectors.toSet());
        this.holidays = days;
        this.calendar = new WorkdayCalendar(days);
    }

    @Override
    public CapabilityManifest manifest() {
        return CapabilityManifest.builder("calendar.workdays", "1.0.0")
                .name("Working-day calendar")
                .description("Counts the working days left until each task's due date, skipping the person's days "
                        + "off and public holidays, so deadlines reflect real available time.")
                .kind(CapabilityManifest.Kind.DETERMINISTIC)
                .consumes(Vocabulary.TASKS, Vocabulary.TASK_DUE_DATE, Vocabulary.TASK_STATUS, Vocabulary.GOAL_TODAY,
                        Vocabulary.PERSON_WORKING_DAYS, Vocabulary.PERSON_HOLIDAYS)
                .requires(Vocabulary.TASKS)
                .produces(Vocabulary.taskAnnotation("calendar.workdaysLeft"))
                .annotationSchemas(Map.of("calendar.workdaysLeft", Json.tree("{\"type\":\"integer\"}")))
                .tags("calendar", "deadline", "working days", "schedule")
                .cost(20, 0)
                .build();
    }

    @Override
    public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
        // The person's own working days when the kernel knows them, so priorities and deadline risk agree.
        EnumSet<DayOfWeek> worked = EnumSet.noneOf(DayOfWeek.class);
        request.context().person("workingDays").filter(JsonNode::isArray).ifPresent(node -> node.forEach(d -> {
            try {
                worked.add(DayOfWeek.valueOf(d.asString()));
            } catch (IllegalArgumentException ignored) {
                // not a day name
            }
        }));
        // Holidays configured here and the ones the kernel passes on (configured once for every capability).
        Set<LocalDate> allHolidays = new HashSet<>(holidays);
        request.context().person("holidays").filter(JsonNode::isArray).ifPresent(node -> node.forEach(d -> {
            try {
                allHolidays.add(LocalDate.parse(d.asString()));
            } catch (RuntimeException ignored) {
                // not a date
            }
        }));
        WorkdayCalendar effective = worked.isEmpty() && allHolidays.equals(holidays) ? calendar
                : new WorkdayCalendar(worked.isEmpty() ? WorkdayCalendar.WEEKDAYS : worked, allHolidays);
        return CapabilityOutcome.of(workdays(request.context(), effective));
    }

    /** The working days left before each open task's due date, as {@code calendar.workdaysLeft} annotations. */
    static Effects workdays(GoalContext context, WorkdayCalendar calendar) {
        LocalDate today = context.today() == null ? LocalDate.now() : context.today();
        List<Effects.TaskUpdate> updates = context.tasks().stream()
                .filter(t -> t.status().isOpen() && t.dueDate() != null)
                .map(t -> Effects.TaskUpdate.annotate(t.id(), Map.of("calendar.workdaysLeft",
                        Json.toTree(calendar.workdaysLeft(today, t.dueDate())))))
                .toList();
        return Effects.ofUpdates(updates);
    }
}
