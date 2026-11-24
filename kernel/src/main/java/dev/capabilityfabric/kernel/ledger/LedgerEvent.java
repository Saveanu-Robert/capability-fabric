package dev.capabilityfabric.kernel.ledger;

import dev.capabilityfabric.protocol.Json;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One fact in a goal's event stream. {@code payload} is a {@link dev.capabilityfabric.protocol.Facts} record when
 * the event is new and a JSON tree when it was loaded from the store; {@link #payload(Class)} reads both.
 */
public record LedgerEvent(String id, String type, Object payload, String principal, String causationId, Instant at) {

    /** A new fact with a fresh id and the current time. */
    public static LedgerEvent of(String type, Object payload, String principal, String causationId) {
        return new LedgerEvent(UUID.randomUUID().toString(), type, payload, principal, causationId, Instant.now());
    }

    public <T> T payload(Class<T> type) {
        if (type.isInstance(payload)) {
            return type.cast(payload);
        }
        JsonNode tree = payload instanceof JsonNode n ? n : Json.toTree(payload);
        return Json.MAPPER.treeToValue(tree, type);
    }

    /** True when a person (the owner or a guest) made this change, not an agent or the kernel. */
    public boolean byPerson() {
        return byPerson(principal);
    }

    /**
     * True for the principals people act under: {@code human:<name>} for the owner, {@code guest:<name>} for guests.
     */
    public static boolean byPerson(String principal) {
        return principal != null && (principal.startsWith("human:") || principal.startsWith("guest:"));
    }

    /** {@link #byPerson(String)} as an SQL condition on a {@code principal} column. */
    public static final String BY_PERSON_SQL = "(principal LIKE 'human:%' OR principal LIKE 'guest:%')";

    /**
     * An SQL condition on a {@code principal} column for the owner's own changes ({@code human:<name>}), leaving out
     * guests: only the owner's work describes the owner.
     */
    public static final String BY_OWNER_SQL = "principal LIKE 'human:%'";
}
