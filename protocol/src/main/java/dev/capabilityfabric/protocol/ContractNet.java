package dev.capabilityfabric.protocol;

/**
 * Contract-net messages (Smith, 1980). When several providers offer the same capability, the kernel broadcasts a call
 * for proposals and awards the call to the best bid.
 */
public final class ContractNet {

    private ContractNet() {
    }

    /** Asks every instance of {@code capability} to bid for work on a goal with {@code taskCount} tasks. */
    public record CallForProposals(String cfpId, String capability, String goalId, int taskCount) {
    }

    /**
     * A provider's offer: how confident it is, how long it expects to take and how many model calls it needs. The
     * kernel decides the award, weighing the claimed confidence against the provider's earned standing.
     */
    public record Bid(String cfpId, String capability, String provider, String instanceId, double confidence,
                      long expectedLatencyMs, int llmCalls) {
    }
}
