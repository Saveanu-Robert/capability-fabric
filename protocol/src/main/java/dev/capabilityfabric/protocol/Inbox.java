package dev.capabilityfabric.protocol;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The system speaks first. Providers notice things (risk, stale goals, rule breaches) and post a message with a
 * typed suggestion; the person's single yes executes exactly that suggestion, under their constitution. A message is
 * bound to the goal version it was based on: if the goal changed since, the person is told before anything runs.
 */
public final class Inbox {

    private Inbox() {
    }

    /**
     * What a message is about. {@code ADMISSION} (an agent asking to join), {@code SECURITY} (a notice about access,
     * such as a pairing code withdrawn after guessing) and {@code RULE_LIMIT} (days with more tasks due than one of the
     * person's rules allows) come from the kernel only.
     */
    public enum Kind { DEADLINE_RISK, DUE_TODAY, BLOCKED_CHAIN, STALE_GOAL, RULE_LIMIT, OVERDUE, ADMISSION, SECURITY }

    /** {@code EXPIRED}: no longer true (the facts it rested on changed) and no longer shown. */
    public enum Status { UNREAD, ACCEPTED, DISMISSED, EXPIRED }

    /**
     * What a provider posts. {@code dedupeKey} prevents the same observation from being posted twice; {@code basis}
     * is the {@link GoalContext#planFingerprint()} of the goal as the provider saw it, so a change made while the
     * provider was thinking still counts as a change.
     */
    public record Post(Kind kind, String goalId, String text, List<Conversation.Action> suggestions,
                       String dedupeKey, String provider, String instanceId, List<DecisionRecord> decisions,
                       String basis) {
        public Post {
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
        }
    }

    /** {@code basis} fingerprints the goal facts the suggestion rests on (tasks, statuses, dates, effort). */
    public record Message(String id, Kind kind, String goalId, String goalText, String text,
                          List<Conversation.Action> suggestions, String suggestionText, Status status, String basis,
                          String provider, Instant createdAt) {
        public Message {
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }
    }

    /** The person's answer; {@code force} confirms a suggestion whose goal changed since it was posted. */
    public record Answer(String messageId, boolean accept, boolean force, String sessionId, String principal,
                         String receipt) {
        public Answer(String messageId, boolean accept, boolean force, String sessionId, String principal) {
            this(messageId, accept, force, sessionId, principal, null);
        }
    }

    /**
     * Suggestions that need the person's explicit, receipted yes however the message is answered: anything that
     * needs confirmation on its own (cancelling a goal, dropping a task, also when the drop is written as a task
     * status), moving a goal's deadline (which moves every open task with it) and moving the dates of several tasks
     * at once. The kernel and every client apply this one test, so what a client asks about is what the kernel
     * requires. A null or empty list is not destructive; null entries are skipped.
     */
    public static boolean destructive(List<Conversation.Action> suggestions) {
        if (suggestions == null) {
            return false;
        }
        long moves = 0;
        for (Conversation.Action s : suggestions) {
            if (s == null || s.type() == null) {
                continue;
            }
            if (s.type().needsConfirmation() || s.type() == Conversation.ActionType.RESCHEDULE_GOAL
                    || s.type() == Conversation.ActionType.SET_TASK_STATUS && s.status() == TaskStatus.DROPPED) {
                return true;
            }
            if (s.type() == Conversation.ActionType.UPDATE_TASK && s.dueDate() != null) {
                moves++;
            }
        }
        return moves > 1;
    }

    /** The {@link Conversation#receipt} of a message: its text and every suggestion accepting it would carry out. */
    public static String receipt(Message m) {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("id", m.id());
        shown.put("text", m.text());
        shown.put("suggestions", m.suggestions());
        return Hashing.canonicalHash(shown);
    }
}
