package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;

/**
 * Uniform answer to request/reply interactions with the kernel. {@code today} is the kernel's own date in the
 * person's time zone, so a client on another clock counts "due today", "days left" and "overdue" the way the kernel
 * does; it is {@code null} when the sender does not set it.
 */
public record Reply(boolean ok, String error, JsonNode data, LocalDate today) {

    /** An answer without the kernel's date. */
    public Reply(boolean ok, String error, JsonNode data) {
        this(ok, error, data, null);
    }

    /** The same answer carrying {@code date} as the kernel's date. */
    public Reply withToday(LocalDate date) {
        return new Reply(ok, error, data, date);
    }

    /** A successful answer carrying {@code data} as JSON ({@code null} for none). */
    public static Reply ok(Object data) {
        return new Reply(true, null, data == null ? null : Json.toTree(data));
    }

    public static Reply error(String message) {
        return new Reply(false, message, null);
    }

    /** The data read as {@code type}; {@code null} when there is none. */
    public <T> T dataAs(Class<T> type) {
        return data == null ? null : Json.MAPPER.treeToValue(data, type);
    }

    public byte[] toBytes() {
        return Json.bytes(this);
    }

    public static Reply fromBytes(byte[] bytes) {
        return Json.read(bytes, Reply.class);
    }
}
