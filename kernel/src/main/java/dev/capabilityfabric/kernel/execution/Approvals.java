package dev.capabilityfabric.kernel.execution;

import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.protocol.AutonomyLevel;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.KernelApi;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Human approval for steps above the automatic autonomy level. The run waits (on a virtual thread) until the person
 * decides, the request expires, or the goal is cancelled; the run is told which of these ended the wait
 * ({@link Answer}).
 */
@Component
public class Approvals {

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final Map<String, CompletableFuture<Answer>> waiting = new ConcurrentHashMap<>();

    /** How a request for approval ended; each value is also the status its row keeps. */
    public enum Answer {
        /** The person approved the step. */
        APPROVED,
        /** The person said no. */
        REJECTED,
        /** Nobody answered in time, or the wait ended without an answer (the kernel is stopping). */
        EXPIRED,
        /** The goal was cancelled while the step waited: nobody decided about the step. */
        CANCELLED;

        /** The answer a row's stored status records; empty for a request still pending or an unknown status. */
        static Optional<Answer> stored(String status) {
            for (Answer answer : values()) {
                if (answer.name().equals(status)) {
                    return Optional.of(answer);
                }
            }
            return Optional.empty();
        }
    }

    /** Requests still pending from a previous process can no longer be answered: they expire at start-up. */
    public Approvals(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        jdbc.sql("UPDATE approvals SET status = 'EXPIRED' WHERE status = 'PENDING'").update();
    }

    /**
     * Asks for approval and waits; returns how the request ended. While it waits the goal shows
     * {@code AWAITING_APPROVAL}; afterwards it gets back the status it had before, unless something else (a
     * cancellation) changed it meanwhile. When the wait times out just as the person decides or the goal is
     * cancelled, the stored answer wins. An interrupted wait expires the request, returns {@link Answer#EXPIRED} and
     * leaves the thread's interrupt flag set.
     *
     * <p>When storing the request, recording its fact or setting the goal's status fails, the error is thrown, the
     * request no longer counts as waiting and its row (if it was stored) is expired, so a later "approve" is refused
     * instead of approving a step that will never run; the goal gets back its status as above. A failure while
     * cleaning up after such an error is added to it as suppressed.
     */
    public Answer requestAndWait(String goalId, String runId, int step, String capability, AutonomyLevel autonomy,
                                 String reason, Duration timeout) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<Answer> decision = new CompletableFuture<>();
        GoalStatus before = ledger.load(goalId).map(GoalState::status).orElse(null);
        waiting.put(id, decision);
        RuntimeException failure = null;
        try {
            jdbc.sql("""
                    INSERT INTO approvals (id, goal_id, run_id, step, capability, autonomy, reason, status, created_at)
                    VALUES (:id, :goal, :run, :step, :cap, :autonomy, :reason, 'PENDING', :at)""")
                    .param("id", id).param("goal", goalId).param("run", runId).param("step", step)
                    .param("cap", capability).param("autonomy", autonomy.name()).param("reason", reason)
                    .param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
            ledger.record(goalId, Facts.APPROVAL_REQUESTED, new Facts.ApprovalRequested(id, goalId, runId, step,
                    capability, autonomy, reason), "kernel", runId);
            ledger.goalStatus(goalId, GoalStatus.AWAITING_APPROVAL, "waiting for approval of " + capability, "kernel");
            return decision.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return answerAfterTimeout(expire(id), storedStatus(id));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Answer.EXPIRED;
        } catch (ExecutionException e) {
            // The future is only ever completed with an answer; should it fail, the wait ends like a timed-out one.
            return answerAfterTimeout(expire(id), storedStatus(id));
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            waiting.remove(id);
            // An interrupted wait (the kernel is stopping) still puts the goal's status back: the flag is cleared
            // for these writes, which would fail with it set, and set again afterwards.
            boolean interrupted = Thread.interrupted();
            try {
                settle(id, goalId, before, interrupted || failure != null);
            } catch (RuntimeException e) {
                if (failure == null) {
                    throw e;
                }
                if (e != failure) {
                    failure.addSuppressed(e);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * After a wait: expires the request when it can no longer be answered ({@code expire}), and puts back the goal
     * status it had {@code before} the wait ({@link #statusToRestore}). The status is put back even when expiring
     * fails; the first error is thrown afterwards, with a second one added as suppressed.
     */
    private void settle(String id, String goalId, GoalStatus before, boolean expire) {
        RuntimeException error = null;
        if (expire) {
            try {
                expire(id);
            } catch (RuntimeException e) {
                error = e;
            }
        }
        try {
            GoalStatus now = ledger.load(goalId).map(GoalState::status).orElse(null);
            statusToRestore(before, now).ifPresent(s -> ledger.goalStatus(goalId, s, "approval settled", "kernel"));
        } catch (RuntimeException e) {
            if (error == null) {
                error = e;
            } else if (e != error) {
                error.addSuppressed(e);
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /** Marks a still pending request as expired; returns how many rows changed (0 when it was decided first). */
    int expire(String id) {
        return jdbc.sql("UPDATE approvals SET status = 'EXPIRED' WHERE id = :id AND status = 'PENDING'")
                .param("id", id).update();
    }

    Optional<String> storedStatus(String id) {
        return jdbc.sql("SELECT status FROM approvals WHERE id = :id").param("id", id).query(String.class).optional();
    }

    /**
     * The outcome of a wait that timed out: expired when the request could still be expired; otherwise the answer
     * stored just before (an approval or a "no" given at the last moment counts, and so does a cancellation of the
     * goal). A row that is gone or holds no answer counts as expired.
     */
    static Answer answerAfterTimeout(int expiredRows, Optional<String> storedStatus) {
        if (expiredRows > 0) {
            return Answer.EXPIRED;
        }
        return storedStatus.flatMap(Answer::stored).orElse(Answer.EXPIRED);
    }

    /**
     * The goal status to put back after a wait: the one before it, but only while the goal still shows
     * {@code AWAITING_APPROVAL} (a cancellation during the wait is kept).
     */
    static Optional<GoalStatus> statusToRestore(GoalStatus before, GoalStatus now) {
        if (now != GoalStatus.AWAITING_APPROVAL) {
            return Optional.empty();
        }
        return Optional.of(before == null || before == GoalStatus.AWAITING_APPROVAL ? GoalStatus.PLANNING : before);
    }

    /**
     * A cancelled goal's waiting steps are released at once with {@link Answer#CANCELLED} instead of waiting for the
     * timeout. A request the person decided (or that expired) just before keeps that answer: only a row still
     * pending is marked cancelled, and only then is its step released here.
     */
    public void cancelFor(String goalId) {
        List<String> ids = jdbc.sql("SELECT id FROM approvals WHERE goal_id = :goal AND status = 'PENDING'")
                .param("goal", goalId).query(String.class).list();
        for (String id : ids) {
            int changed = jdbc.sql("UPDATE approvals SET status = 'CANCELLED' WHERE id = :id AND status = 'PENDING'")
                    .param("id", id).update();
            CompletableFuture<Answer> future = waiting.get(id);
            if (changed > 0 && future != null) {
                future.complete(Answer.CANCELLED);
            }
        }
    }

    /**
     * Records the person's decision on a pending request (found by id prefix) and releases the waiting step.
     * Returns why it could not be decided, or {@code null} when it was. A reference that is empty or holds anything
     * but hex digits and hyphens (the characters of an id) matches no request; letters match in either case, as ids
     * are stored in lower case.
     */
    public String decide(String approvalId, boolean approve, String principal) {
        if (approvalId == null || !approvalId.strip().matches("[0-9a-fA-F-]{1,36}")) {
            return "no pending approval " + approvalId;
        }
        String prefix = approvalId.strip().toLowerCase(Locale.ROOT);
        List<String[]> found = jdbc.sql("SELECT id, goal_id FROM approvals WHERE id LIKE :id AND status = 'PENDING'"
                        + " LIMIT 2")
                .param("id", prefix + "%").query((rs, i) -> new String[]{rs.getString("id"), rs.getString("goal_id")})
                .list();
        if (found.size() != 1) {
            return "no pending approval " + approvalId;
        }
        String id = found.getFirst()[0];
        String goalId = found.getFirst()[1];
        CompletableFuture<Answer> waitingFor = waiting.get(id);
        if (waitingFor == null) {
            jdbc.sql("UPDATE approvals SET status = 'EXPIRED' WHERE id = :id AND status = 'PENDING'").param("id", id).update();
            return "that step is no longer waiting (its run ended or the kernel restarted)";
        }
        int changed = jdbc.sql("""
                UPDATE approvals SET status = :status, decided_by = :by, decided_at = :at
                WHERE id = :id AND status = 'PENDING'""")
                .param("status", approve ? "APPROVED" : "REJECTED").param("by", principal)
                .param("at", OffsetDateTime.now(ZoneOffset.UTC)).param("id", id).update();
        if (changed == 0) {
            return "that approval was already decided or expired";
        }
        ledger.record(goalId, Facts.APPROVAL_DECIDED, new Facts.ApprovalDecided(id, goalId, approve, principal),
                principal, id);
        CompletableFuture<Answer> future = waiting.get(id);
        if (future != null) {
            future.complete(approve ? Answer.APPROVED : Answer.REJECTED);
        }
        return null;
    }

    /** Requests waiting for a decision, oldest first. */
    public List<KernelApi.ApprovalView> pending() {
        return jdbc.sql("""
                SELECT id, goal_id, capability, autonomy, reason, status, created_at FROM approvals
                WHERE status = 'PENDING' ORDER BY created_at""")
                .query((rs, i) -> new KernelApi.ApprovalView(rs.getString("id"), rs.getString("goal_id"),
                        rs.getString("capability"), rs.getString("autonomy"), rs.getString("reason"),
                        rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
