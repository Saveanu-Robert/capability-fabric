package dev.capabilityfabric.kernel.security;

import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.KernelApi;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a guest sees of a goal it may show: the goal and its tasks without the owner's pace (the calibration
 * annotations estimates carry, any annotation whose key contains {@link #CALIBRATION}) and without the plan runs,
 * whose steps name the providers and how long each took.
 */
public final class GuestView {

    /** The part of an annotation key that marks the owner's pace, as in "estimate.calibration". */
    public static final String CALIBRATION = "calibration";

    private GuestView() {
    }

    /** {@code details} as a guest sees them: no calibration annotations on the goal or its tasks, and no runs. */
    public static KernelApi.GoalDetails details(KernelApi.GoalDetails details) {
        List<TaskView> tasks = details.tasks() == null ? List.of() : details.tasks().stream()
                .map(t -> new TaskView(t.id(), t.key(), t.title(), t.description(), t.status(), t.dueDate(),
                        t.dependsOn(), t.effortHours(), t.priority(), t.rank(), t.actualHours(),
                        withoutCalibration(t.annotations())))
                .toList();
        return new KernelApi.GoalDetails(details.goal(), withoutCalibration(details.annotations()), tasks, List.of());
    }

    /** The annotations whose key does not contain {@link #CALIBRATION}, in any case. */
    static Map<String, JsonNode> withoutCalibration(Map<String, JsonNode> annotations) {
        if (annotations == null) {
            return Map.of();
        }
        Map<String, JsonNode> kept = new LinkedHashMap<>();
        annotations.forEach((k, v) -> {
            if (k == null || !k.toLowerCase(Locale.ROOT).contains(CALIBRATION)) {
                kept.put(k, v);
            }
        });
        return kept;
    }
}
