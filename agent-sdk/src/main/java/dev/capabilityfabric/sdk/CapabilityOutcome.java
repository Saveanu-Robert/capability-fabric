package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Json;
import tools.jackson.databind.JsonNode;

/** What a handler returns: ledger effects, free-form output for the caller, or both. */
public record CapabilityOutcome(Effects effects, JsonNode output) {

    /** Ledger effects only. */
    public static CapabilityOutcome of(Effects effects) {
        return new CapabilityOutcome(effects, null);
    }

    /** Output for the caller only (as JSON), no ledger change. */
    public static CapabilityOutcome output(Object output) {
        return new CapabilityOutcome(Effects.none(), Json.toTree(output));
    }
}
