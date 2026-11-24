package dev.capabilityfabric.kernel.api;

import dev.capabilityfabric.kernel.security.Quota;
import dev.capabilityfabric.kernel.security.SecurityService;
import dev.capabilityfabric.protocol.Reply;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Who gets the interpreter (the language model behind {@code intent.submit}). The owner's sentences always run at
 * once. Guests (invited people and everyone they handed a grant on to) take turns, one sentence at a time, and only
 * while no sentence of the owner is running. Each root grant has one sentence in the kernel at a time: while one of
 * its sentences runs or waits for its turn, another from the same root (its own guest or any delegate of it) is
 * refused at once, so delegates minted without asking the kernel cannot pile up waiting requests (and with them the
 * gateway's slots for requests in flight). Together they may say {@link #GUEST_SENTENCES} sentences per
 * {@link #GUEST_WINDOW} per root grant, counted when a sentence starts waiting, so a sentence turned away after its
 * wait counts too.
 */
final class SentenceGate {

    static final int GUEST_SENTENCES = 30;
    static final Duration GUEST_WINDOW = Duration.ofHours(1);
    /** How long a guest's sentence waits for its turn before it is turned away. */
    static final Duration GUEST_WAIT = Duration.ofSeconds(20);
    static final String BUSY = "the assistant is busy with the owner's requests; try again in a moment";
    static final String USED_UP = "refused: the sentences this invitation allows for the hour are used up; try again "
            + "later";
    static final String IN_FLIGHT = "refused: a sentence of this invitation is still being answered; send the next "
            + "one when it is done";

    private final Quota budget;
    private final Semaphore guests = new Semaphore(1, true);
    private final AtomicInteger owners = new AtomicInteger();
    /** The budget keys ({@link #budgetKey}) of the guest sentences running or waiting now. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Duration wait;
    private final Clock clock;

    SentenceGate() {
        this(GUEST_SENTENCES, GUEST_WINDOW, GUEST_WAIT, Clock.systemUTC());
    }

    SentenceGate(int sentences, Duration window, Duration wait, Clock clock) {
        this.budget = new Quota(sentences, window);
        this.wait = wait;
        this.clock = clock;
    }

    /**
     * Runs one sentence of {@code caller}: at once for the owner; for a guest only while no other sentence of its
     * root grant runs or waits, within the budget, and in turn.
     */
    Reply run(SecurityService.Caller caller, Supplier<Reply> sentence) {
        if (caller.role() != SecurityService.Role.GUEST) {
            owners.incrementAndGet();
            try {
                return sentence.get();
            } finally {
                owners.decrementAndGet();
            }
        }
        String key = budgetKey(caller);
        if (!inFlight.add(key)) {
            return Reply.error(IN_FLIGHT);
        }
        try {
            if (!budget.take(key, clock.instant())) {
                return Reply.error(USED_UP);
            }
            return inTurn(sentence);
        } finally {
            inFlight.remove(key);
        }
    }

    /** A guest's sentence once it is counted: it waits up to {@code wait} for the guests' turn and for the owner. */
    private Reply inTurn(Supplier<Reply> sentence) {
        long deadline = System.nanoTime() + wait.toNanos();
        try {
            if (!guests.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS)) {
                return Reply.error(BUSY);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error(BUSY);
        }
        try {
            while (owners.get() > 0) {
                if (System.nanoTime() >= deadline) {
                    return Reply.error(BUSY);
                }
                Thread.sleep(20);
            }
            return sentence.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.error(BUSY);
        } finally {
            guests.release();
        }
    }

    /** The budget a guest draws on: its root grant's (shared with every delegate of it), else its key's. */
    static String budgetKey(SecurityService.Caller caller) {
        return caller.grant() == null ? "key:" + caller.keyId() : "grant:" + caller.grant().root().id();
    }

    /** Owner sentences running now. */
    int ownersRunning() {
        return owners.get();
    }
}
