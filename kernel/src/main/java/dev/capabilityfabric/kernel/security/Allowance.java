package dev.capabilityfabric.kernel.security;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * How fast each sender may go: a sender holds up to {@code burst} uses and gets one back every {@code refill}, so a
 * sender keeping a steady pace below that is never refused while one going faster is. A clock that goes back gives
 * nothing back. Each sender costs two numbers, however large the burst. At most {@link #MAX_KEYS} senders are
 * remembered; beyond that, senders with a full allowance are forgotten first, then all of them.
 */
public final class Allowance {

    static final int MAX_KEYS = 1_000;

    private final int burst;
    private final Duration refill;
    private final Map<String, double[]> allowance = new HashMap<>();
    private final Map<String, Instant> updated = new HashMap<>();

    public Allowance(int burst, Duration refill) {
        if (burst < 1 || refill.isZero() || refill.isNegative()) {
            throw new IllegalArgumentException("an allowance needs a burst of at least 1 and a positive refill");
        }
        this.burst = burst;
        this.refill = refill;
    }

    /** Takes one use for {@code key}: true when it has one left (and it is used), false otherwise. */
    public synchronized boolean take(String key, Instant now) {
        if (!allowance.containsKey(key) && allowance.size() >= MAX_KEYS) {
            prune(now);
        }
        double[] left = allowance.computeIfAbsent(key, k -> new double[]{burst});
        Instant last = updated.getOrDefault(key, now);
        left[0] = available(left[0], last, now);
        updated.put(key, now.isAfter(last) ? now : last);
        if (left[0] < 1) {
            return false;
        }
        left[0] -= 1;
        return true;
    }

    /** What a sender holds at {@code now}: what it held at {@code last} and what came back since, at most a burst. */
    private double available(double left, Instant last, Instant now) {
        long elapsed = Math.max(0, Duration.between(last, now).toNanos());
        return Math.min(burst, left + (double) elapsed / refill.toNanos());
    }

    private void prune(Instant now) {
        allowance.entrySet().removeIf(e -> available(e.getValue()[0], updated.get(e.getKey()), now) >= burst);
        updated.keySet().retainAll(allowance.keySet());
        if (allowance.size() >= MAX_KEYS) {
            allowance.clear();
            updated.clear();
        }
    }

    synchronized int trackedKeys() {
        return allowance.size();
    }
}
