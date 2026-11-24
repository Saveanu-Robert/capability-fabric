package dev.capabilityfabric.kernel.security;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * Decides which of many similar events are written down (refused requests, rejected proposals, admission requests).
 * The same key is recorded at most once per {@code perKey} window, and all keys together at most {@code perMinute}
 * times a minute; the next recorded event says how many were left out before it, so a flood cannot fill the ledger,
 * the database or the log. At most {@link #MAX_KEYS} keys are remembered.
 */
public final class RefusalBudget {

    static final int MAX_KEYS = 1_000;

    private final int perMinute;
    private final Duration perKey;
    private final Map<String, Instant> lastRecorded = new HashMap<>();
    private Instant windowStart = Instant.EPOCH;
    private int recordedInWindow;
    private int leftOut;

    public RefusalBudget(int perMinute, Duration perKey) {
        this.perMinute = perMinute;
        this.perKey = perKey;
    }

    /**
     * Empty when this refusal is not recorded; otherwise the number of refusals left out since the last recorded
     * one (0 when none were).
     */
    public synchronized OptionalInt admit(String key, Instant now) {
        if (!now.isBefore(windowStart.plus(Duration.ofMinutes(1)))) {
            windowStart = now;
            recordedInWindow = 0;
            prune(now);
        }
        Instant last = lastRecorded.get(key);
        if (last != null && now.isBefore(last.plus(perKey)) || recordedInWindow >= perMinute) {
            leftOut++;
            return OptionalInt.empty();
        }
        if (lastRecorded.size() >= MAX_KEYS) {
            prune(now);
        }
        lastRecorded.put(key, now);
        recordedInWindow++;
        int before = leftOut;
        leftOut = 0;
        return OptionalInt.of(before);
    }

    /** Forgets senders whose window has passed; when that is not enough, forgets them all. */
    private void prune(Instant now) {
        lastRecorded.values().removeIf(at -> !now.isBefore(at.plus(perKey)));
        if (lastRecorded.size() >= MAX_KEYS) {
            lastRecorded.clear();
        }
    }

    synchronized int trackedKeys() {
        return lastRecorded.size();
    }
}
