package dev.capabilityfabric.kernel.ecosystem;

import dev.capabilityfabric.kernel.ledger.LedgerEvent;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Earned standing. Providers win work mostly by what they did, and only a little by what they claim (their bid
 * confidence): calls that succeeded, estimates that matched the hours people really spent, proposals the kernel
 * accepted. A provider whose standing falls below the floor is quarantined, if another provider can take its place.
 */
@Component
public class Reputation {

    private static final Logger log = LoggerFactory.getLogger(Reputation.class);

    /** A provider with enough evidence and a standing below this is quarantined. */
    static final double QUARANTINE_BELOW = 0.35;
    /** Calls plus scored estimates a provider needs before it can be quarantined. */
    static final int MIN_EVIDENCE = 5;

    private final JdbcClient jdbc;
    private final LedgerService ledger;

    public Reputation(JdbcClient jdbc, LedgerService ledger) {
        this.jdbc = jdbc;
        this.ledger = ledger;
    }

    /** Counts one call of a provider and whether it succeeded. */
    public void recordCall(String capability, String provider, boolean ok) {
        if (capability == null || provider == null) {
            return;
        }
        upsert(capability, provider, "calls = reputation.calls + 1" + (ok ? "" : ", failures = reputation.failures + 1"));
    }

    /** Effects a provider returned but the ledger refused. */
    public void recordRejectedEffects(String capability, String provider) {
        upsert(capability, provider, "failures = reputation.failures + 1");
    }

    /** Counts one reactive proposal of a provider and whether the kernel accepted it. */
    public void recordProposal(String capability, String provider, boolean accepted) {
        upsert(capability, provider, accepted ? "accepted = reputation.accepted + 1" : "rejected = reputation.rejected + 1");
    }

    /**
     * A task is done and the person said how long it really took: score whoever estimated it. What is scored is
     * the task's latest effort report in the ledger (a change to done that carries hours), when it carries these
     * hours, against the latest capability estimate before that report; a correction the person made to the
     * estimate is skipped for the capability estimate before it. The error is |ln(actual / estimate)|, so being 2x
     * off in either direction costs the same.
     *
     * <p>Each estimate counts once. A later report for the same estimate (the person corrected the hours, or
     * finished the reopened task with other hours) replaces the error counted for it instead of adding another
     * outcome; the same report scored again changes nothing. Which report was counted for a task is kept in
     * {@code reputation_scored} by the report's event id. A row naming the estimate's own event id marks an
     * estimate counted without its report being kept: it stays as it is and is not scored again.
     */
    public void recordOutcome(String goalId, String taskId, double actualHours) {
        if (taskId == null || !(actualHours > 0) || Double.isInfinite(actualHours)) {
            return; // no task or no usable effort to compare with
        }
        List<LedgerEvent> events = ledger.events(goalId);
        int reportAt = -1;
        for (int i = events.size() - 1; i >= 0 && reportAt < 0; i--) {
            if (effortReported(events.get(i), taskId) != null) {
                reportAt = i;
            }
        }
        if (reportAt < 0 || Math.abs(effortReported(events.get(reportAt), taskId) - actualHours) > 1e-9) {
            return; // these hours are not the task's latest report; the call for that report scores it
        }
        Provenance provenance = new Provenance(events);
        int estimateAt = -1;
        Provenance.Source source = null;
        for (int i = reportAt - 1; i >= 0 && estimateAt < 0; i--) {
            LedgerEvent e = events.get(i);
            if (!Facts.TASK_UPDATED.equals(e.type())) {
                continue;
            }
            Facts.TaskUpdated u = e.payload(Facts.TaskUpdated.class);
            if (!taskId.equals(u.taskId()) || u.effortHours() == null || u.effortHours() <= 0) {
                continue;
            }
            Optional<Provenance.Source> by = provenance.of(e);
            if (by.isPresent() && by.get().provider() != null) {
                estimateAt = i;
                source = by.get();
            }
            // otherwise the person's own correction: score the capability estimate before it
        }
        if (estimateAt < 0) {
            return;
        }
        String reportId = events.get(reportAt).id();
        Set<String> counted = scoredEvents(taskId);
        if (counted.contains(reportId) || counted.contains(events.get(estimateAt).id())) {
            return; // this report already counted, or the estimate was counted without its report being kept
        }
        // Every report between the estimate and this one was scored against the same estimate.
        int previousAt = -1;
        for (int i = reportAt - 1; i > estimateAt && previousAt < 0; i--) {
            if (effortReported(events.get(i), taskId) != null && counted.contains(events.get(i).id())) {
                previousAt = i;
            }
        }
        if (!firstScoring(taskId, reportId)) {
            return; // another call counted this report meanwhile
        }
        double estimate = events.get(estimateAt).payload(Facts.TaskUpdated.class).effortHours();
        double error = logError(actualHours, estimate);
        if (previousAt < 0) {
            addOutcome(source.capability(), source.provider(), 1, error);
            log.info("{} by {} estimated {} h, actual {} h", source.capability(), source.provider(), estimate,
                    actualHours);
            return;
        }
        double previous = effortReported(events.get(previousAt), taskId);
        unscore(taskId, events.get(previousAt).id());
        addOutcome(source.capability(), source.provider(), 0, error - logError(previous, estimate));
        log.info("{} by {} estimated {} h, actual hours corrected from {} to {}", source.capability(),
                source.provider(), estimate, previous, actualHours);
    }

    /** The hours {@code e} reports for the task when it is a change of that task to done with positive hours. */
    static Double effortReported(LedgerEvent e, String taskId) {
        if (!Facts.TASK_STATUS_CHANGED.equals(e.type())) {
            return null;
        }
        Facts.TaskStatusChanged c = e.payload(Facts.TaskStatusChanged.class);
        return taskId.equals(c.taskId()) && c.to() == TaskStatus.DONE && c.actualHours() != null
                && c.actualHours() > 0 ? c.actualHours() : null;
    }

    /** How far an estimate was off, the same for 2x too high as for 2x too low. */
    static double logError(double actualHours, double estimatedHours) {
        return Math.abs(Math.log(actualHours / estimatedHours));
    }

    /** The event ids recorded as scored for the task. */
    Set<String> scoredEvents(String taskId) {
        return new HashSet<>(jdbc.sql("SELECT event_id FROM reputation_scored WHERE task_id = :t")
                .param("t", taskId).query(String.class).list());
    }

    /** Records the event as scored for the task; false when it already was. */
    boolean firstScoring(String taskId, String eventId) {
        return jdbc.sql("""
                INSERT INTO reputation_scored (task_id, event_id, scored_at) VALUES (:t, :e, :at)
                ON CONFLICT DO NOTHING""")
                .param("t", taskId).param("e", eventId).param("at", OffsetDateTime.now(ZoneOffset.UTC))
                .update() == 1;
    }

    /** Forgets that the event was scored for the task: a later report replaced it. */
    void unscore(String taskId, String eventId) {
        jdbc.sql("DELETE FROM reputation_scored WHERE task_id = :t AND event_id = :e")
                .param("t", taskId).param("e", eventId).update();
    }

    /**
     * Adds {@code outcomes} scored estimates and {@code errorChange} to the provider's error sum (negative when a
     * correction made an estimate look better); the sum never drops below zero.
     */
    void addOutcome(String capability, String provider, int outcomes, double errorChange) {
        ensureRow(capability, provider);
        jdbc.sql("""
                UPDATE reputation SET outcomes = reputation.outcomes + :n,
                    log_error_sum = GREATEST(0, reputation.log_error_sum + :d), updated_at = :at
                WHERE capability = :c AND provider = :p""")
                .param("n", outcomes).param("d", errorChange).param("at", OffsetDateTime.now(ZoneOffset.UTC))
                .param("c", capability).param("p", provider).update();
    }

    /** The provider's standing in [0, 1]; 0.5 for a provider with no record yet. */
    public double score(String capability, String provider) {
        return standing(capability, provider).map(Ecosystem.Standing::score).orElse(0.5);
    }

    /** Whether the provider is quarantined for this capability (it gets work only when nobody else can do it). */
    public boolean quarantined(String capability, String provider) {
        return standing(capability, provider).map(Ecosystem.Standing::quarantined).orElse(false);
    }

    /** Everything known about the provider's work on this capability, or empty when it has no record. */
    public Optional<Ecosystem.Standing> standing(String capability, String provider) {
        return jdbc.sql("SELECT * FROM reputation WHERE capability = :c AND provider = :p")
                .param("c", capability).param("p", provider)
                .query((rs, i) -> standing(rs)).optional();
    }

    /** Every provider's standing, by capability and provider. */
    public List<Ecosystem.Standing> all() {
        return jdbc.sql("SELECT * FROM reputation ORDER BY capability, provider").query((rs, i) -> standing(rs)).list();
    }

    /**
     * Quarantines a provider whose evidence is strong and bad, but only when a healthy alternative exists:
     * a system with one flaky decomposer still needs its decomposer.
     */
    public boolean considerQuarantine(String capability, String provider, boolean alternativeExists) {
        Optional<Ecosystem.Standing> s = standing(capability, provider);
        if (s.isEmpty() || s.get().quarantined() || !alternativeExists) {
            return false;
        }
        int evidence = s.get().calls() + s.get().outcomes();
        if (evidence >= MIN_EVIDENCE && s.get().score() < QUARANTINE_BELOW) {
            jdbc.sql("UPDATE reputation SET quarantined = TRUE WHERE capability = :c AND provider = :p")
                    .param("c", capability).param("p", provider).update();
            ledger.recordSystem(Facts.CAPABILITY_QUARANTINED, new Facts.CapabilityLifecycle(capability, provider,
                    "QUARANTINED", String.format(Locale.ROOT, "standing %.2f after %d observations",
                    s.get().score(), evidence), "kernel"), "kernel");
            log.warn("quarantined {} from {} (standing {})", capability, provider, s.get().score());
            return true;
        }
        return false;
    }

    /**
     * Lifts the quarantine of every provider of the capability and clears their call counts, so their reliability
     * starts again from the neutral prior; their scored estimates and proposals still count. Returns how many
     * providers were reinstated.
     */
    public int reinstate(String capability) {
        return jdbc.sql("UPDATE reputation SET quarantined = FALSE, calls = 0, failures = 0 WHERE capability = :c AND quarantined")
                .param("c", capability).update();
    }

    /** Creates the provider's row when it has none, then applies {@code change} (a SET clause of constant code). */
    private void upsert(String capability, String provider, String change) {
        ensureRow(capability, provider);
        jdbc.sql("UPDATE reputation SET " + change + ", updated_at = :at WHERE capability = :c AND provider = :p")
                .param("c", capability).param("p", provider).param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
    }

    /** Creates the provider's row for the capability when it has none. */
    private void ensureRow(String capability, String provider) {
        jdbc.sql("""
                INSERT INTO reputation (capability, provider, updated_at) VALUES (:c, :p, :at)
                ON CONFLICT (capability, provider) DO NOTHING""")
                .param("c", capability).param("p", provider).param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
    }

    private static Ecosystem.Standing standing(ResultSet rs) throws SQLException {
        int calls = rs.getInt("calls");
        int failures = rs.getInt("failures");
        int outcomes = rs.getInt("outcomes");
        double errorSum = rs.getDouble("log_error_sum");
        int accepted = rs.getInt("accepted");
        int rejected = rs.getInt("rejected");
        Double meanError = outcomes == 0 ? null : errorSum / outcomes;
        return new Ecosystem.Standing(rs.getString("capability"), rs.getString("provider"), null, false, null,
                calls, failures, outcomes, meanError, accepted, rejected,
                score(calls, failures, outcomes, errorSum, accepted, rejected), rs.getBoolean("quarantined"));
    }

    /**
     * Standing in [0, 1]: 50% reliability, 35% accuracy, 15% acceptance, each with a neutral prior so that a new
     * provider starts in the middle rather than at either extreme.
     */
    static double score(int calls, int failures, int outcomes, double logErrorSum, int accepted, int rejected) {
        double reliability = (calls - failures + 1.0) / (calls + 2.0);
        double accuracy = outcomes == 0 ? 0.6 : Math.exp(-logErrorSum / outcomes);
        double acceptance = (accepted + 1.0) / (accepted + rejected + 2.0);
        return 0.5 * reliability + 0.35 * accuracy + 0.15 * acceptance;
    }
}
