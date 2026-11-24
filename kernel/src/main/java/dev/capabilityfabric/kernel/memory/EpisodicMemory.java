package dev.capabilityfabric.kernel.memory;

import dev.capabilityfabric.kernel.registry.Embeddings;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Vocabulary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * What the system remembers: completed tasks with the model's estimate and the real effort, and which capabilities
 * processed which goal. Recall is by meaning (pgvector cosine distance) when embeddings are available, otherwise by
 * word overlap. Agents reach recall through the kernel's memory query, which hands them only what their admitted
 * contract reads ({@link #recall(String, int, Collection)}).
 *
 * <p>A finished task is remembered by its title alone ({@link #recallText}): the stored vector is the title's, and
 * the word-overlap fallback compares with the title too. A caller looking for tasks like one of its own therefore
 * asks with that task's title only; a title with its description added scores lower against the same task.
 *
 * <p>Who finished a task is not stored here: the ledger keeps it with the task's status change. Both the memory
 * query ({@link #recall(String, int)}) and what the person's profile learns from ({@link #ownerOutcomes()}) are
 * limited to tasks the owner finished ({@link #FINISHED_BY_OWNER}), so the hours a guest reports never pass for the
 * owner's, neither as the owner's pace nor as a similar task an agent recalls for the owner's other goals.
 *
 * <p>Whether a task is finished is not stored here either: a remembered task counts only while its latest status
 * change in the ledger is to done and was made by the owner. A task reopened after it was finished is neither
 * recalled nor learned from until it is finished again; finishing it again updates the same row. A task whose latest
 * status change a guest or an agent made (finishing it, or reporting its hours again) is left out the same way until
 * the owner changes its status.
 */
@Component
public class EpisodicMemory {

    private final JdbcClient jdbc;
    private final Embeddings embeddings;

    public EpisodicMemory(JdbcClient jdbc, Embeddings embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    /**
     * Remembers a finished task. The estimate stored is the estimator's own model hours before its pace factor (see
     * {@link #modelEstimate}), so learning the person's pace compares real effort with the model and not with an
     * estimate the pace already corrected; the estimator's kind of work is stored with it. Finishing a task again
     * without hours keeps the hours recorded before ({@code actualHours} {@code null}).
     */
    public void recordTaskOutcome(String goalId, String goalText, TaskView task, Double actualHours) {
        Optional<float[]> vector = embeddings.embed(recallText(task));
        Estimate estimate = modelEstimate(task);
        jdbc.sql("""
                INSERT INTO task_outcomes (task_id, goal_id, goal_text, title, estimated_hours, actual_hours, embedding,
                    embedding_model, completed_at, kind)
                VALUES (:task, :goal, :goalText, :title, :est, :act, CAST(:vec AS vector), :model, :at, :kind)
                ON CONFLICT (task_id) DO UPDATE SET
                    actual_hours = COALESCE(EXCLUDED.actual_hours, task_outcomes.actual_hours),
                    estimated_hours = EXCLUDED.estimated_hours, completed_at = EXCLUDED.completed_at,
                    kind = COALESCE(EXCLUDED.kind, task_outcomes.kind)""")
                .param("task", task.id()).param("goal", goalId).param("goalText", goalText)
                .param("title", task.title()).param("est", estimate.hours())
                .param("act", actualHours != null ? actualHours : task.actualHours())
                .param("vec", vector.map(Embeddings::literal).orElse(null))
                .param("model", vector.isPresent() ? embeddings.modelName() : null)
                .param("at", OffsetDateTime.now(ZoneOffset.UTC))
                .param("kind", estimate.kind())
                .update();
    }

    /** The text a finished task is recalled by: its title, without the description. */
    static String recallText(TaskView task) {
        return task.title();
    }

    /** The hours a model estimated for a task before calibration, and the kind of work it named (or {@code null}). */
    record Estimate(Double hours, String kind) {
    }

    /** How far the task's effort may be from model hours times factor and still be the estimator's own figure. */
    static final double ROUNDING = 0.3;

    /**
     * The model's own estimate of a task, read from the estimator's {@code estimate.calibration} annotation:
     * {@code modelHours}, else the effort divided by {@code factor}, else the effort itself. When the effort no longer
     * matches the annotation (the person set the hours since), the effort is the estimate and no kind is known.
     */
    static Estimate modelEstimate(TaskView task) {
        Double effort = task.effortHours();
        JsonNode calibration = task.annotation(CALIBRATION).orElse(null);
        if (calibration == null || !calibration.isObject()) {
            return new Estimate(effort, null);
        }
        Double factor = positive(calibration.get("factor"));
        Double modelHours = positive(calibration.get("modelHours"));
        JsonNode kindNode = calibration.get("kind");
        String kind = kindNode == null || !kindNode.isString() || kindNode.asString().isBlank() ? null
                : kindNode.asString().strip();
        if (modelHours != null) {
            double calibrated = modelHours * (factor == null ? 1 : factor);
            if (effort != null && Math.abs(effort - calibrated) > Math.max(ROUNDING, 0.05 * effort)) {
                return new Estimate(effort, null);
            }
            return new Estimate(modelHours, kind);
        }
        if (effort != null && factor != null) {
            return new Estimate(effort / factor, kind);
        }
        return new Estimate(effort, kind);
    }

    /** The task annotation in which the estimator explains its calibration. */
    static final String CALIBRATION = "estimate.calibration";

    private static Double positive(JsonNode node) {
        return node != null && node.isNumber() && node.asDouble() > 0 ? node.asDouble() : null;
    }

    /** Remembers which capabilities a run used for a goal and how it went. */
    public void recordEpisode(String runId, String goalId, String goalText, List<String> capabilities, int taskCount,
                              String outcome, int llmCalls, long durationMs) {
        jdbc.sql("""
                INSERT INTO episodes (run_id, goal_id, goal_text, capabilities, task_count, outcome, llm_calls,
                    duration_ms, created_at)
                VALUES (:run, :goal, :text, :caps, :tasks, :outcome, :llm, :ms, :at)
                ON CONFLICT (run_id) DO NOTHING""")
                .param("run", runId).param("goal", goalId).param("text", goalText)
                .param("caps", String.join(",", capabilities)).param("tasks", taskCount).param("outcome", outcome)
                .param("llm", llmCalls).param("ms", durationMs).param("at", OffsetDateTime.now(ZoneOffset.UTC))
                .update();
    }

    /**
     * The memory query's answer: tasks the owner finished most similar to {@code text} (a task title, see
     * {@link #recallText}; {@code limit} of them, 5 when it is not positive, at most 20; see
     * {@link #recall(String, int)}), minimised to what the caller reads. The goal's text is kept only when
     * {@code reads} holds {@link Vocabulary#GOAL_TEXT}; the estimated and actual hours only when it holds
     * {@link Vocabulary#TASK_ACTUAL_HOURS} or {@link Vocabulary#TASK_EFFORT_HOURS}. Task titles and similarity are
     * always kept: they are what the query is about. No {@code reads} (null) reads nothing.
     */
    public KernelApi.RecallResult recall(String text, int limit, Collection<String> reads) {
        Collection<String> r = reads == null ? List.of() : reads;
        boolean goalText = r.contains(Vocabulary.GOAL_TEXT);
        boolean hours = r.contains(Vocabulary.TASK_ACTUAL_HOURS) || r.contains(Vocabulary.TASK_EFFORT_HOURS);
        return new KernelApi.RecallResult(recall(text == null ? "" : text, limit <= 0 ? DEFAULT_RECALL : limit)
                .stream().map(i -> new KernelApi.RecallItem(goalText ? i.goalText() : null, i.taskTitle(),
                        hours ? i.estimatedHours() : null, hours ? i.actualHours() : null, i.similarity()))
                .toList());
    }

    /** How many tasks the memory query recalls when the caller names no positive limit. */
    static final int DEFAULT_RECALL = 5;

    /** A finished task the owner did, as the person's profile learns from it. */
    public record OwnerOutcome(String title, String kind, double estimatedHours, double actualHours,
                               Instant completedAt) {
    }

    /**
     * Finished tasks with a positive estimate and positive real hours that the owner finished, oldest first
     * ({@link #FINISHED_BY_OWNER}).
     */
    public List<OwnerOutcome> ownerOutcomes() {
        return jdbc.sql(OWNER_OUTCOMES_SQL).param("type", Facts.TASK_STATUS_CHANGED)
                .query((rs, i) -> new OwnerOutcome(rs.getString("title"), rs.getString("kind"),
                        rs.getDouble("estimated_hours"), rs.getDouble("actual_hours"),
                        rs.getObject("completed_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * SQL condition on a {@code task_outcomes} row named {@code o}: the owner finished the task and it is still done,
     * that is its latest status change in the ledger (parameter {@code type} = {@link Facts#TASK_STATUS_CHANGED}) is
     * a change to done made by the owner. The owner acts as {@code human:<name>}, guests as {@code guest:<name>} and
     * agents as {@code agent:<provider>/<instance>}. A task reopened since, one whose latest status change a guest or
     * an agent made, and one without any status change are left out (the condition is false or null).
     */
    static final String FINISHED_BY_OWNER = """
            (SELECT e.payload->>'to' = 'DONE' AND e.principal LIKE 'human:%' FROM events e
             WHERE e.goal_id = o.goal_id AND e.type = :type AND e.payload->>'taskId' = o.task_id
             ORDER BY e.goal_seq DESC LIMIT 1)""";

    static final String OWNER_OUTCOMES_SQL = """
            SELECT o.title, o.kind, o.estimated_hours, o.actual_hours, o.completed_at FROM task_outcomes o
            WHERE o.estimated_hours > 0 AND o.actual_hours > 0
            """ + "  AND " + FINISHED_BY_OWNER + "\n" + """
            ORDER BY o.completed_at""";

    /** The {@code k} tasks the owner finished with real effort whose title vector of the current model is nearest. */
    static final String RECALL_NEAREST_SQL = """
            SELECT o.goal_text, o.title, o.estimated_hours, o.actual_hours,
                   1 - (o.embedding <=> CAST(:vec AS vector)) AS similarity
            FROM task_outcomes o
            WHERE o.embedding IS NOT NULL AND o.embedding_model = :model AND o.actual_hours IS NOT NULL
            """ + "  AND " + FINISHED_BY_OWNER + "\n" + """
            ORDER BY o.embedding <=> CAST(:vec AS vector) LIMIT :k""";

    /** The 200 most recently completed tasks the owner finished with real effort. */
    static final String RECALL_RECENT_SQL = """
            SELECT o.goal_text, o.title, o.estimated_hours, o.actual_hours FROM task_outcomes o
            WHERE o.actual_hours IS NOT NULL
            """ + "  AND " + FINISHED_BY_OWNER + "\n" + """
            ORDER BY o.completed_at DESC LIMIT 200""";

    /**
     * Tasks the owner finished, still done, with real effort ({@link #FINISHED_BY_OWNER}), most similar to the text
     * (at most 20), with estimated and actual hours: the nearest by embedding when vectors of the current model
     * exist, otherwise the best word overlaps among the 200 most recent.
     */
    public List<KernelApi.RecallItem> recall(String text, int limit) {
        int k = Math.max(1, Math.min(limit, 20));
        Optional<float[]> vector = embeddings.embed(text);
        if (vector.isPresent()) {
            List<KernelApi.RecallItem> items = jdbc.sql(RECALL_NEAREST_SQL)
                    .param("vec", Embeddings.literal(vector.get())).param("model", embeddings.modelName())
                    .param("k", k).param("type", Facts.TASK_STATUS_CHANGED)
                    .query((rs, i) -> new KernelApi.RecallItem(rs.getString("goal_text"), rs.getString("title"),
                            (Double) rs.getObject("estimated_hours"), (Double) rs.getObject("actual_hours"),
                            rs.getDouble("similarity")))
                    .list();
            if (!items.isEmpty()) {
                return items;
            }
        }
        return jdbc.sql(RECALL_RECENT_SQL).param("type", Facts.TASK_STATUS_CHANGED)
                .query((rs, i) -> new KernelApi.RecallItem(rs.getString("goal_text"), rs.getString("title"),
                        (Double) rs.getObject("estimated_hours"), (Double) rs.getObject("actual_hours"),
                        Embeddings.lexicalSimilarity(text, rs.getString("title"))))
                .list().stream()
                .filter(item -> item.similarity() > 0)
                .sorted(Comparator.comparingDouble(KernelApi.RecallItem::similarity).reversed())
                .limit(k)
                .toList();
    }
}
