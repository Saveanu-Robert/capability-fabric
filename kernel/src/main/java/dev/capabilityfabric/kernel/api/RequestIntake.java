package dev.capabilityfabric.kernel.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The requests of one subscription the kernel works on at a time. A request is handed to a worker only while fewer
 * than {@code limit} requests of the subscription are in work; any other is dropped at once, unanswered, so a flood
 * holds neither a thread nor the memory of its messages while they would wait. Dropped requests are counted and
 * logged at most once per {@link #LOG_INTERVAL} for each subscription.
 */
final class RequestIntake {

    private static final Logger log = LoggerFactory.getLogger(RequestIntake.class);
    /** How often at most a subscription's dropped requests are logged. */
    static final Duration LOG_INTERVAL = Duration.ofMinutes(1);

    private final String subscription;
    private final int limit;
    private final Semaphore slots;
    private final Executor workers;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong unlogged = new AtomicLong();
    private final AtomicLong loggedAt;

    /** {@code limit} requests of {@code subscription} at a time, each run by {@code workers}. */
    RequestIntake(String subscription, int limit, Executor workers) {
        if (limit < 1) {
            throw new IllegalArgumentException("an intake needs room for at least one request");
        }
        this.subscription = subscription;
        this.limit = limit;
        this.slots = new Semaphore(limit);
        this.workers = workers;
        this.loggedAt = new AtomicLong(System.nanoTime() - LOG_INTERVAL.toNanos());
    }

    /**
     * Hands {@code work} to a worker when a slot is free, and frees the slot when it ends, however it ends. Returns
     * whether it was taken: false when every slot is in use or the workers take no more work (the kernel stops).
     */
    boolean offer(Runnable work) {
        if (!slots.tryAcquire()) {
            drop();
            return false;
        }
        try {
            workers.execute(() -> {
                try {
                    work.run();
                } finally {
                    slots.release();
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            slots.release();
            return false;
        }
    }

    private void drop() {
        dropped.incrementAndGet();
        unlogged.incrementAndGet();
        long now = System.nanoTime();
        long last = loggedAt.get();
        if (now - last >= LOG_INTERVAL.toNanos() && loggedAt.compareAndSet(last, now)) {
            log.warn("dropped {} requests on {}: {} of its requests are in work already", unlogged.getAndSet(0),
                    subscription, limit);
        }
    }

    /** How many of the subscription's requests are in work now. */
    int inWork() {
        return limit - slots.availablePermits();
    }

    /** How many requests were dropped since the intake was made. */
    long dropped() {
        return dropped.get();
    }

    /** How many requests of the subscription may be in work at a time. */
    int limit() {
        return limit;
    }
}
