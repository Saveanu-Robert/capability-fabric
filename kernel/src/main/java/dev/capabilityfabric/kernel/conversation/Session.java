package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.protocol.Conversation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One conversation: the last turns (so "mark the first one done" has a referent), the goals in focus, the questions
 * waiting for a yes or no (the changes asked about before they are made), the changes the last turn made (so "undo
 * that" and "what did you change?" have an answer) and the last change with a date (so "no, I meant Saturday" can
 * correct it). Kept in memory; it expires after two idle hours.
 */
final class Session {

    static final int MAX_TURNS = 6;

    /** One exchange: what the person said and what was answered. */
    record Turn(String person, String assistant) {
    }

    private final Clock clock;
    private final Deque<Turn> turns = new ArrayDeque<>();
    private final Set<String> focus = new LinkedHashSet<>();
    /** Questions expire: a yes given much later may no longer mean what it meant. */
    static final Duration QUESTION_LIFETIME = Duration.ofMinutes(15);

    private record Waiting(Conversation.PendingConfirmation confirmation, Instant asked, String keyId) {
    }

    private final Map<String, Waiting> pending = new LinkedHashMap<>();
    private Instant lastUsed;

    /**
     * A change made in this conversation: the kernel's line for it ({@code done}), the change itself with its goal id
     * ({@code made}), and the change that takes it back ({@code undo}), or why none can ({@code why}).
     */
    record Change(String done, Conversation.Action made, Conversation.Action undo, String why) {
    }

    private List<Change> changes = List.of();
    private Conversation.Action dated;
    /** Turns remembered so far, and the turn count when {@link #dated} was set: a correction is for a recent date. */
    private int turnCount;
    private int datedAt;

    Session(Clock clock) {
        this.clock = clock;
        this.lastUsed = clock.instant();
    }

    /** The last turns, oldest first. */
    synchronized List<Turn> history() {
        return List.copyOf(turns);
    }

    /** Adds a turn, keeping the last {@link #MAX_TURNS}, and marks the session as used. */
    synchronized void remember(String person, String assistant) {
        turns.addLast(new Turn(person, assistant));
        turnCount++;
        while (turns.size() > MAX_TURNS) {
            turns.removeFirst();
        }
        lastUsed = clock.instant();
    }

    /** The goals the last turn acted on or showed. */
    synchronized Set<String> focus() {
        return Set.copyOf(focus);
    }

    /** The goals the last turn acted on or showed; a turn about nothing in particular clears the focus. */
    synchronized void focusOn(Set<String> goalIds) {
        focus.clear();
        focus.addAll(goalIds);
    }

    /** Remembers the changes a turn or an answer made; one that made none leaves the last ones in place. */
    synchronized void changed(List<Change> made) {
        if (!made.isEmpty()) {
            changes = List.copyOf(made);
        }
    }

    /** The changes the last turn or answer that changed something made, in order. */
    synchronized List<Change> lastChanges() {
        return changes;
    }

    /** Remembers the last change with a date that was made or asked about, with its goal id; null is ignored. */
    synchronized void dated(Conversation.Action action) {
        if (action != null) {
            dated = action;
            datedAt = turnCount;
        }
    }

    /** The last change with a date that was made or asked about in the last two turns, or null. */
    synchronized Conversation.Action lastDated() {
        return turnCount - datedAt <= 2 ? dated : null;
    }

    /** A new sentence makes earlier unanswered questions void. */
    synchronized void newTurn() {
        pending.clear();
    }

    /**
     * Several questions may wait at once (a chat question and an inbox re-ask); each is answered by its id.
     * {@code keyId} is the device the question was asked on; only that device may answer it.
     */
    synchronized void await(Conversation.PendingConfirmation confirmation, String keyId) {
        pending.put(confirmation.id(), new Waiting(confirmation, clock.instant(), keyId));
    }

    /** The question with this id, once: null when it is unknown, asked on another device or expired. */
    synchronized Conversation.PendingConfirmation take(String id, String keyId) {
        Waiting waiting = pending.get(id);
        if (waiting == null || waiting.keyId() != null && !waiting.keyId().equals(keyId)) {
            return null;
        }
        pending.remove(id);
        return waiting.asked().plus(QUESTION_LIFETIME).isBefore(clock.instant()) ? null : waiting.confirmation();
    }

    /** True when the session was last used before {@code cutoff}. */
    synchronized boolean idleSince(Instant cutoff) {
        return lastUsed.isBefore(cutoff);
    }
}
