package dev.capabilityfabric.kernel.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * How often each sender may do something: at most {@code max} times in any {@code window}, counted per key (a
 * signing key, a root grant). At most {@link #MAX_KEYS} keys are remembered; beyond that, keys without a use inside
 * the window are forgotten first, then all of them.
 */
public final class Quota {

    static final int MAX_KEYS = 1_000;

    private final int max;
    private final Duration window;
    private final Map<String, Deque<Instant>> uses = new HashMap<>();

    public Quota(int max, Duration window) {
        this.max = max;
        this.window = window;
    }

    /** Takes one use for {@code key}: true when it is within the quota (and counted), false when it is used up. */
    public synchronized boolean take(String key, Instant now) {
        Deque<Instant> times = uses.get(key);
        if (times == null) {
            if (uses.size() >= MAX_KEYS) {
                prune(now);
            }
            times = new ArrayDeque<>();
            uses.put(key, times);
        }
        trim(times, now);
        if (times.size() >= max) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    private void prune(Instant now) {
        uses.values().removeIf(times -> {
            trim(times, now);
            return times.isEmpty();
        });
        if (uses.size() >= MAX_KEYS) {
            uses.clear();
        }
    }

    private void trim(Deque<Instant> times, Instant now) {
        Instant horizon = now.minus(window);
        while (!times.isEmpty() && !times.peekFirst().isAfter(horizon)) {
            times.removeFirst();
        }
    }

    synchronized int trackedKeys() {
        return uses.size();
    }
}
