package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Envelope;

import java.util.Optional;

/**
 * A provider that watches facts (blackboard knowledge source) and proposes changes on its own initiative. Its manifest
 * must use mode {@code REACTIVE} and list the fact types it reacts to. It hears of facts through the kernel's signals:
 * each names the fact (id, type, time and goal) but carries none of its data, so a reaction reads what its manifest
 * consumes through the goal context the kernel gives it. Reactions are debounced per goal, and facts caused by the
 * provider itself are ignored.
 */
public interface ReactiveHandler {

    CapabilityManifest manifest();

    /**
     * Reacts to one fact about {@code goalId}, given as its signal (no data payload). A change returned is sent to
     * the kernel as a proposal; empty proposes nothing. An exception is logged and the fact is skipped.
     */
    Optional<ProposedChange> onFact(Envelope fact, String goalId, InvocationContext context) throws Exception;

    /** A change to one goal, with the reason recorded with the proposal. */
    record ProposedChange(String goalId, Effects effects, String reason) {
    }
}
