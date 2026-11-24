package dev.capabilityfabric.kernel.execution;

import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Plans;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Read model of plan runs: what ran, in which order, with what outcome. */
@Component
public class RunStore {

    private final JdbcClient jdbc;

    public RunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records that a run started; it counts as unfinished until {@link #finish}. */
    public void start(String runId, String goalId, String trigger) {
        jdbc.sql("INSERT INTO runs (run_id, goal_id, trigger, started_at) VALUES (:run, :goal, :trigger, :at)")
                .param("run", runId).param("goal", goalId).param("trigger", trigger)
                .param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
    }

    /** Stores the plan a run composed. */
    public void plan(String runId, Plans.Plan plan) {
        jdbc.sql("UPDATE runs SET plan = CAST(:plan AS jsonb), source = :source WHERE run_id = :run")
                .param("plan", Json.write(plan)).param("source", plan.source().name()).param("run", runId).update();
    }

    /** Appends one finished step to the run. */
    public void step(String runId, KernelApi.StepView step) {
        jdbc.sql("UPDATE runs SET steps = steps || CAST(:step AS jsonb) WHERE run_id = :run")
                .param("step", "[" + Json.write(step) + "]").param("run", runId).update();
    }

    /** Records how a run ended (COMPLETED, DEGRADED, FAILED, CANCELLED or INTERRUPTED). */
    public void finish(String runId, String outcome, long durationMs, int llmCalls) {
        jdbc.sql("""
                UPDATE runs SET outcome = :outcome, finished_at = :at, duration_ms = :ms, llm_calls = :llm
                WHERE run_id = :run""")
                .param("outcome", outcome).param("at", OffsetDateTime.now(ZoneOffset.UTC)).param("ms", durationMs)
                .param("llm", llmCalls).param("run", runId).update();
    }

    /**
     * Records how a run ended when it has not ended yet, without a duration or model calls (it ended without
     * recording them). Returns whether the run was still unfinished; false leaves an ended run as it is.
     */
    public boolean close(String runId, String outcome) {
        return jdbc.sql("""
                UPDATE runs SET outcome = :outcome, finished_at = :at, duration_ms = 0, llm_calls = 0
                WHERE run_id = :run AND finished_at IS NULL""")
                .param("outcome", outcome).param("at", OffsetDateTime.now(ZoneOffset.UTC)).param("run", runId)
                .update() > 0;
    }

    /** The goal's latest runs, newest first. */
    public List<KernelApi.RunView> recent(String goalId, int limit) {
        return jdbc.sql("""
                SELECT run_id, trigger, source, outcome, started_at, duration_ms, llm_calls, steps::text AS steps
                FROM runs WHERE goal_id = :goal ORDER BY started_at DESC LIMIT :limit""")
                .param("goal", goalId).param("limit", limit)
                .query((rs, i) -> new KernelApi.RunView(rs.getString("run_id"), rs.getString("trigger"),
                        rs.getString("source"), rs.getString("outcome"),
                        rs.getObject("started_at", OffsetDateTime.class).toInstant(),
                        (Long) rs.getObject("duration_ms"), rs.getInt("llm_calls"),
                        List.of(Json.read(rs.getString("steps"), KernelApi.StepView[].class))))
                .list();
    }

    /** Runs of the goal started after {@code since}, refresh runs not counted (the hourly limit). */
    public int countSince(String goalId, Instant since) {
        return jdbc.sql("SELECT count(*) FROM runs WHERE goal_id = :goal AND started_at > :since AND trigger <> 'refresh'")
                .param("goal", goalId).param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Integer.class).single();
    }

    /** Goals whose most recent run was cancelled (by the kill switch), with that run's trigger. */
    public List<Unfinished> cancelledLatest() {
        return jdbc.sql("""
                SELECT run_id, goal_id, trigger FROM (
                    SELECT DISTINCT ON (goal_id) run_id, goal_id, trigger, outcome FROM runs
                    ORDER BY goal_id, started_at DESC) latest
                WHERE outcome = 'CANCELLED'""")
                .query((rs, i) -> new Unfinished(rs.getString("run_id"), rs.getString("goal_id"), rs.getString("trigger")))
                .list();
    }

    /** A run and the goal and trigger it was started for. */
    public record Unfinished(String runId, String goalId, String trigger) {
    }

    /**
     * Runs that never recorded how they ended (the kernel stopped during them, or the database failed while they
     * ended), oldest first.
     */
    public List<Unfinished> unfinished() {
        return jdbc.sql("SELECT run_id, goal_id, trigger FROM runs WHERE finished_at IS NULL ORDER BY started_at")
                .query((rs, i) -> new Unfinished(rs.getString("run_id"), rs.getString("goal_id"), rs.getString("trigger")))
                .list();
    }
}
