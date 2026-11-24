package dev.capabilityfabric.protocol;

/** Goal lifecycle as seen by humans. */
public enum GoalStatus {
    /** Accepted; the fabric is composing or running a plan. */
    PLANNING,
    /** A plan step waits for the person's yes: the policy or one of the person's rules asks for it. */
    AWAITING_APPROVAL,
    /** Tasks exist and the plan ran completely. */
    ACTIVE,
    /** Tasks exist but at least one plan step failed or was skipped. */
    DEGRADED,
    /** Every task that was not dropped is done. */
    COMPLETED,
    /** No usable plan could be produced. */
    FAILED,
    /** The person cancelled it; it can be reopened. */
    CANCELLED
}
