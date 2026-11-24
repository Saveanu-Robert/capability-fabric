package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * A change suggested by a reactive provider that noticed something, after a kernel signal or on its own timer. The
 * kernel accepts it only if the provider is alive, declared the tokens it writes, and the autonomy level allows it
 * without approval.
 */
public record Proposal(String proposalId, String capability, String provider, String instanceId, String goalId,
                       List<Effects.TaskUpdate> taskUpdates, Map<String, JsonNode> goalAnnotations, String reason,
                       List<DecisionRecord> decisions, String causationFactId) {

    public Proposal {
        taskUpdates = taskUpdates == null ? List.of() : List.copyOf(taskUpdates);
        goalAnnotations = goalAnnotations == null ? Map.of() : Map.copyOf(goalAnnotations);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public Effects effects() {
        return new Effects(List.of(), taskUpdates, goalAnnotations, reason);
    }
}
