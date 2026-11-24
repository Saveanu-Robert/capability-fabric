package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * One model call: everything needed to audit it and to replay it without a model. {@code promptHash} is the SHA-256 of
 * the canonical request (model, messages, schema, options), which is the replay lookup key.
 */
public record DecisionRecord(String id, String capability, String instanceId, String purpose, String model,
                             String promptHash, JsonNode request, String responseText, JsonNode parsed,
                             boolean valid, List<String> validationErrors, int attempt, long latencyMs,
                             Integer promptTokens, Integer completionTokens, boolean replayed, Instant at) {

    public DecisionRecord {
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }
}
