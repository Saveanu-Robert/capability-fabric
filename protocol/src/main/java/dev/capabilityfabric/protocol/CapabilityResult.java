package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * A provider's answer. {@code effects} are proposed ledger changes; {@code output} is free-form
 * data for the caller (the planner's draft, a rule draft, a capability design). {@code decisions} records every
 * model call made while handling the request, so the run can be audited and replayed.
 */
public record CapabilityResult(String requestId, String capability, String provider, String instanceId,
                               Status status, Effects effects, JsonNode output, List<DecisionRecord> decisions,
                               String error, long durationMs) {

    /** {@code OK}: the work was done; {@code ERROR}: it was not, and {@code error} says why. */
    public enum Status { OK, ERROR }

    public CapabilityResult {
        effects = effects == null ? Effects.none() : effects;
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public boolean ok() {
        return status == Status.OK;
    }

    /** Model calls made for this result: one recorded decision each. */
    public long llmCalls() {
        return decisions.size();
    }
}
