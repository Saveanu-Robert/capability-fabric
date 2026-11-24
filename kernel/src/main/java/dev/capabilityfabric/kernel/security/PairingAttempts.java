package dev.capabilityfabric.kernel.security;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Counts wrong pairing codes. Each key may try {@link #PER_KEY} times before it is locked out for {@link #LOCKOUT}.
 * Attempts on the machine's own broker and attempts through the gateway (from the network) are counted apart:
 * <ul>
 *   <li>On the broker, all keys together may fail {@link #GLOBAL} times per {@link #LOCKOUT}: the failure that
 *   reaches that ceiling is reported to the caller, which withdraws the code, and the count starts again for the
 *   next code. The ceiling never locks keys out, so the owner's device can pair with a new code at once.</li>
 *   <li>Through the gateway, all keys together may fail {@link #GLOBAL} times per {@link #LOCKOUT}; reaching that
 *   pauses pairing through the gateway for {@link #LOCKOUT} and leaves the code alone, so strangers on the network
 *   can neither guess the eight digits with many fresh keys nor take the code away from the owner.</li>
 * </ul>
 */
final class PairingAttempts {

    static final int PER_KEY = 5;
    static final int GLOBAL = 50;
    static final Duration LOCKOUT = Duration.ofMinutes(15);

    private record KeyState(Deque<Instant> failures, Instant lockedUntil) {
    }

    private final Map<String, KeyState> keys = new HashMap<>();
    private final Deque<Instant> recent = new ArrayDeque<>();
    private final Deque<Instant> recentRemote = new ArrayDeque<>();
    private Instant remotePausedUntil;

    /** Why this key may not try a code right now on the broker, or empty when it may. */
    synchronized Optional<String> refusal(String keyId, Instant now) {
        return refusal(keyId, now, false);
    }

    /** Why this key may not try a code right now, through the gateway ({@code remote}) or not; empty when it may. */
    synchronized Optional<String> refusal(String keyId, Instant now, boolean remote) {
        forgetOld(now);
        KeyState state = keys.get(keyId);
        if (state != null && state.lockedUntil() != null && now.isBefore(state.lockedUntil())) {
            return Optional.of("too many wrong codes from this device; try again in "
                    + minutesUntil(now, state.lockedUntil()) + " minutes");
        }
        if (remote && remotePausedUntil != null && now.isBefore(remotePausedUntil)) {
            return Optional.of("too many wrong codes came through the gateway; pairing over the network is paused "
                    + "for " + minutesUntil(now, remotePausedUntil) + " minutes (pairing on the machine that runs "
                    + "the system still works)");
        }
        return Optional.empty();
    }

    /**
     * Records a wrong code on the broker. Returns {@code true} when all keys together have now failed {@link #GLOBAL}
     * times within {@link #LOCKOUT}: the code they tried must be withdrawn. The shared count then starts again.
     */
    synchronized boolean failed(String keyId, Instant now) {
        return failed(keyId, now, false);
    }

    /**
     * Records a wrong code, through the gateway ({@code remote}) or not. Returns {@code true} when this attempt
     * reaches the shared ceiling of its kind, which then starts again: on the broker the caller withdraws the code;
     * through the gateway pairing over the network is now paused for {@link #LOCKOUT} (the code stays).
     */
    synchronized boolean failed(String keyId, Instant now, boolean remote) {
        forgetOld(now);
        KeyState state = keys.computeIfAbsent(keyId, k -> new KeyState(new ArrayDeque<>(), null));
        state.failures().addLast(now);
        if (state.failures().size() >= PER_KEY) {
            keys.put(keyId, new KeyState(new ArrayDeque<>(), now.plus(LOCKOUT)));
        }
        Deque<Instant> shared = remote ? recentRemote : recent;
        shared.addLast(now);
        if (shared.size() < GLOBAL) {
            return false;
        }
        shared.clear();
        if (remote) {
            remotePausedUntil = now.plus(LOCKOUT);
        }
        return true;
    }

    synchronized void succeeded(String keyId) {
        keys.remove(keyId);
    }

    synchronized int trackedKeys() {
        return keys.size();
    }

    /** Drops failures older than {@link #LOCKOUT} and keys with nothing left to remember. */
    private void forgetOld(Instant now) {
        Instant horizon = now.minus(LOCKOUT);
        for (Deque<Instant> shared : List.of(recent, recentRemote)) {
            while (!shared.isEmpty() && !shared.peekFirst().isAfter(horizon)) {
                shared.removeFirst();
            }
        }
        keys.values().forEach(s -> {
            while (!s.failures().isEmpty() && !s.failures().peekFirst().isAfter(horizon)) {
                s.failures().removeFirst();
            }
        });
        keys.values().removeIf(s -> s.failures().isEmpty() && (s.lockedUntil() == null || !now.isBefore(s.lockedUntil())));
    }

    private static long minutesUntil(Instant now, Instant until) {
        return Math.max(1, (Duration.between(now, until).toSeconds() + 59) / 60);
    }
}
