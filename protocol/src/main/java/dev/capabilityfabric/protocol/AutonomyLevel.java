package dev.capabilityfabric.protocol;

/**
 * How much a capability may change without a human in the loop. The kernel derives the level from what a capability
 * declares it produces.
 */
public enum AutonomyLevel {
    /** Reads or suggests only; writes nothing to the ledger. */
    L0,
    /** Reversible enrichment: annotations and core field updates (effort, priority, rank, due date). */
    L1,
    /** Structural change inside the goal it serves: creates tasks or dependencies. */
    L2,
    /** Destructive or external side effects: always needs human approval. */
    L3;

    /** Allows more than {@code other}. */
    public boolean exceeds(AutonomyLevel other) {
        return ordinal() > other.ordinal();
    }
}
