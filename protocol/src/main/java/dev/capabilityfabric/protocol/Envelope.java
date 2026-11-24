package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * CloudEvents 1.0 structured-mode JSON envelope (spec 1.0.2). Extension attributes: {@code correlationid} (goal or
 * conversation), {@code causationid} (message that caused this one) and {@code principal} (human or agent identity that
 * acted).
 */
public record Envelope(
        String specversion,
        String id,
        String source,
        String type,
        String subject,
        Instant time,
        String datacontenttype,
        String correlationid,
        String causationid,
        String principal,
        JsonNode data) {

    public static final String SPEC_VERSION = "1.0";
    public static final String TYPE_PREFIX = "dev.capabilityfabric.";

    /** The type without the {@code dev.capabilityfabric.} prefix, for example {@code fact.task.added}. */
    public String shortType() {
        return type != null && type.startsWith(TYPE_PREFIX) ? type.substring(TYPE_PREFIX.length()) : type;
    }

    /** The payload read as {@code target}, for example {@link Facts.TaskAdded}. */
    public <T> T dataAs(Class<T> target) {
        return Json.MAPPER.treeToValue(data, target);
    }

    public byte[] toBytes() {
        return Json.bytes(this);
    }

    public static Envelope fromBytes(byte[] bytes) {
        return Json.read(bytes, Envelope.class);
    }
}
