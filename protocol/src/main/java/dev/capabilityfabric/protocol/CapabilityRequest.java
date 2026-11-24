package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

/**
 * A call to a capability. {@code context} is already minimised to what the provider consumes. {@code params} carries
 * capability-specific arguments (for example the candidate list for the planner).
 */
public record CapabilityRequest(String requestId, String capability, String goalId, String runId, int step,
                                GoalContext context, JsonNode params, String principal) {
}
