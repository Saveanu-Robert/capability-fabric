package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.CapabilityRequest;
import dev.capabilityfabric.protocol.ContractNet;

import java.util.Optional;

/**
 * A capability served on request. Declare one as a Spring bean and the {@link AgentRuntime} advertises it, renews its
 * lease, routes calls and calls for proposals to it, and validates what it returns. The handler never addresses other
 * services by name.
 */
public interface CapabilityHandler {

    /** The static contract (id, version, vocabulary, kind). Instance fields are filled by the runtime. */
    CapabilityManifest manifest();

    /**
     * Does the work for one call. An exception becomes an error result for the caller, with its type and message.
     */
    CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) throws Exception;

    /** Self-assessed suitability in [0, 1], used in contract-net bidding. */
    default double confidence(ContractNet.CallForProposals cfp) {
        return 0.5;
    }

    /** Answer to a call for proposals; return empty to abstain. */
    default Optional<ContractNet.Bid> bid(ContractNet.CallForProposals cfp, CapabilityManifest advertised) {
        return Optional.of(new ContractNet.Bid(cfp.cfpId(), advertised.id(), advertised.provider(),
                advertised.instanceId(), confidence(cfp), advertised.cost().expectedLatencyMs(),
                advertised.cost().llmCalls()));
    }
}
