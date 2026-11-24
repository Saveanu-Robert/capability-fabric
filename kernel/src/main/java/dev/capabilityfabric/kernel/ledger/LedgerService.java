package dev.capabilityfabric.kernel.ledger;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.TaskStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Persists the ledger. Each command runs in one transaction under a per-goal advisory lock (single writer
 * per goal): load state, decide with {@link LedgerRules}, append facts, refresh the read model.
 * Facts are published to the broker afterwards by the outbox relay.
 */
@Service
public class LedgerService {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final KernelProperties properties;

    public LedgerService(JdbcClient jdbc, TransactionTemplate tx, KernelProperties properties) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.properties = properties;
    }

    /** The state after a command and the facts it recorded, or the unchanged state and why it was refused. */
    public record Applied(GoalState state, List<LedgerEvent> events, List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /** Creates a goal under a new id. */
    public Applied createGoal(String text, LocalDate dueDate, String principal) {
        String goalId = UUID.randomUUID().toString();
        return execute(goalId, state -> LedgerRules.createGoal(goalId, text, dueDate, principal));
    }

    /** Applies a capability's effects to a goal; see {@link LedgerRules#applyEffects}. */
    public Applied applyEffects(String goalId, Effects effects, CapabilityManifest manifest, String actor,
                                String causationId) {
        return execute(goalId, state -> LedgerRules.applyEffects(state, effects, manifest, actor, causationId,
                properties.maxTasksPerGoal(), LedgerRules.randomIds()));
    }

    /**
     * Applies a capability's effects without the effort and due date changes that would overwrite the person's own
     * (see {@link #keepHumanEdits(GoalState, List, Effects)}), deciding on the state and history read under the goal's
     * lock, so a change the person makes while the capability's result is on its way is never undone.
     */
    public Applied applyEffectsKeepingHumanEdits(String goalId, Effects effects, CapabilityManifest manifest,
                                                 String actor, String causationId) {
        return execute(goalId, state -> LedgerRules.applyEffects(state, keepHumanEdits(state, events(goalId), effects),
                manifest, actor, causationId, properties.maxTasksPerGoal(), LedgerRules.randomIds()));
    }

    /**
     * {@link #keepHumanEdits(GoalState, List, Effects)} on the goal as stored now, outside the goal's lock: for
     * checks before applying. Applying uses {@link #applyEffectsKeepingHumanEdits}.
     */
    public Effects keepHumanEdits(String goalId, Effects effects) {
        if (effects.taskUpdates().stream().noneMatch(u -> u.effortHours() != null || u.dueDate() != null)) {
            return effects;
        }
        GoalState state = load(goalId).orElse(null);
        return state == null ? effects : keepHumanEdits(state, events(goalId), effects);
    }

    /**
     * Capabilities never overwrite an effort or a due date the person set themselves: one that read the goal before
     * the person's change would otherwise silently undo it. The latest writer of each field, read from the goal's
     * {@code history}, decides.
     */
    static Effects keepHumanEdits(GoalState state, List<LedgerEvent> history, Effects effects) {
        if (!state.exists() || effects.taskUpdates().stream().noneMatch(u -> u.effortHours() != null
                || u.dueDate() != null)) {
            return effects;
        }
        Map<String, Boolean> humanEffort = new HashMap<>();
        Map<String, Boolean> humanDue = new HashMap<>();
        for (LedgerEvent e : history) {
            boolean human = e.byPerson();
            if (Facts.TASK_ADDED.equals(e.type())) {
                Facts.TaskAdded p = e.payload(Facts.TaskAdded.class);
                if (p.dueDate() != null) {
                    humanDue.put(p.taskId(), human);
                }
                if (p.effortHours() != null) {
                    humanEffort.put(p.taskId(), human);
                }
            } else if (Facts.TASK_UPDATED.equals(e.type())) {
                Facts.TaskUpdated p = e.payload(Facts.TaskUpdated.class);
                if (p.dueDate() != null) {
                    humanDue.put(p.taskId(), human);
                }
                if (p.effortHours() != null) {
                    humanEffort.put(p.taskId(), human);
                }
            }
        }
        List<Effects.TaskUpdate> kept = new ArrayList<>();
        for (Effects.TaskUpdate u : effects.taskUpdates()) {
            Optional<GoalContext.TaskView> t = state.task(u.task());
            boolean dropEffort = t.isPresent() && u.effortHours() != null && humanEffort.getOrDefault(t.get().id(), false);
            boolean dropDue = t.isPresent() && u.dueDate() != null && humanDue.getOrDefault(t.get().id(), false);
            kept.add(dropEffort || dropDue ? new Effects.TaskUpdate(u.task(), dropEffort ? null : u.effortHours(),
                    u.priority(), u.rank(), dropDue ? null : u.dueDate(), u.addDependsOn(), u.annotations()) : u);
        }
        return new Effects(effects.newTasks(), kept, effects.goalAnnotations(), effects.notes());
    }

    /** A person's status change of one task; see {@link LedgerRules#changeStatus}. */
    public Applied changeStatus(String goalId, String taskRef, TaskStatus to, String reason, Double actualHours,
                                String principal) {
        return execute(goalId, state -> LedgerRules.changeStatus(state, taskRef, to, reason, actualHours, principal));
    }

    /**
     * Moves the goal's deadline and the due dates of its open tasks with it, in one transaction: either the whole
     * plan moves or nothing does.
     */
    public Applied reschedulePlan(String goalId, LocalDate to, String reason, CapabilityManifest editor,
                                  String principal) {
        return execute(goalId, state -> LedgerRules.reschedulePlan(state, to, properties.today(), reason, editor,
                principal, properties.maxTasksPerGoal()));
    }

    /** A goal's new status; see {@link LedgerRules#goalStatus}. */
    public Applied goalStatus(String goalId, GoalStatus to, String reason, String principal) {
        return execute(goalId, state -> LedgerRules.goalStatus(state, to, reason, principal));
    }

    /** Appends history facts (plans, steps, approvals, proposals) that do not change goal state. */
    public void record(String goalId, String type, Object payload, String principal, String causationId) {
        execute(goalId, state -> LedgerRules.Decision.accept(List.of(LedgerEvent.of(type, payload, principal, causationId))));
    }

    /** Appends a system fact that belongs to no goal (capability joined or left, kill switch). */
    public void recordSystem(String type, Object payload, String principal) {
        LedgerEvent e = LedgerEvent.of(type, payload, principal, null);
        jdbc.sql("""
                INSERT INTO events (id, goal_id, goal_seq, type, payload, principal, causation_id, occurred_at)
                VALUES (:id, NULL, NULL, :type, CAST(:payload AS jsonb), :principal, NULL, :at)""")
                .param("id", e.id()).param("type", e.type()).param("payload", Json.write(e.payload()))
                .param("principal", principal).param("at", OffsetDateTime.ofInstant(e.at(), ZoneOffset.UTC))
                .update();
    }

    /**
     * Runs one command on a goal in a transaction under the goal's advisory lock: loads the state, lets {@code rule}
     * decide, appends the facts and saves the new snapshot. A refusal changes nothing and returns its reasons.
     */
    public Applied execute(String goalId, Function<GoalState, LedgerRules.Decision> rule) {
        return tx.execute(status -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(hashtext(:id))").param("id", goalId).query(Object.class).list();
            GoalState state = load(goalId).orElse(GoalState.empty(goalId));
            LedgerRules.Decision decision = rule.apply(state);
            if (!decision.ok()) {
                return new Applied(state, List.of(), decision.errors());
            }
            if (decision.events().isEmpty()) {
                return new Applied(state, List.of(), List.of());
            }
            GoalState next = state;
            for (LedgerEvent e : decision.events()) {
                next = next.apply(e);
                jdbc.sql("""
                        INSERT INTO events (id, goal_id, goal_seq, type, payload, principal, causation_id, occurred_at)
                        VALUES (:id, :goal, :seq, :type, CAST(:payload AS jsonb), :principal, :causation, :at)""")
                        .param("id", e.id()).param("goal", goalId).param("seq", next.version())
                        .param("type", e.type()).param("payload", Json.write(e.payload()))
                        .param("principal", e.principal()).param("causation", e.causationId())
                        .param("at", OffsetDateTime.ofInstant(e.at(), ZoneOffset.UTC))
                        .update();
            }
            // updated_at is the last time a person (the owner or a guest) touched the goal; system messages and runs
            // do not count, so a goal nobody works on still looks idle.
            boolean touched = decision.events().stream().anyMatch(e -> Facts.GOAL_CREATED.equals(e.type())
                    || e.byPerson());
            saveSnapshot(next, touched);
            return new Applied(next, decision.events(), List.of());
        });
    }

    private void saveSnapshot(GoalState s, boolean touched) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO goals (id, text, status, due_date, task_count, done_count, created_at, updated_at, version, snapshot)
                VALUES (:id, :text, :status, :due, :tasks, :done, :created, :now, :version, CAST(:snapshot AS jsonb))
                ON CONFLICT (id) DO UPDATE SET text = EXCLUDED.text, status = EXCLUDED.status, due_date = EXCLUDED.due_date,
                    task_count = EXCLUDED.task_count, done_count = EXCLUDED.done_count,
                    updated_at = CASE WHEN :touched THEN EXCLUDED.updated_at ELSE goals.updated_at END,
                    version = EXCLUDED.version, snapshot = EXCLUDED.snapshot""")
                .param("id", s.goalId()).param("text", s.text())
                .param("status", s.status() == null ? GoalStatus.PLANNING.name() : s.status().name())
                .param("due", s.dueDate()).param("tasks", s.tasks().size()).param("done", s.doneCount())
                .param("created", OffsetDateTime.ofInstant(s.createdAt() == null ? Instant.now() : s.createdAt(), ZoneOffset.UTC))
                .param("now", now).param("touched", touched).param("version", s.version()).param("snapshot", Json.write(s))
                .update();
    }

    /** The goal's stored snapshot; empty for an unknown goal. */
    public Optional<GoalState> load(String goalId) {
        return jdbc.sql("SELECT snapshot::text FROM goals WHERE id = :id").param("id", goalId)
                .query(String.class).optional().map(json -> Json.read(json, GoalState.class));
    }

    /** Folds the goal's facts from scratch; must always equal the stored snapshot. */
    public GoalState rebuild(String goalId) {
        return GoalState.fold(goalId, events(goalId));
    }

    /** The goal's complete fact history, in order. */
    public List<LedgerEvent> events(String goalId) {
        return jdbc.sql("""
                SELECT id, type, payload::text AS payload, principal, causation_id, occurred_at
                FROM events WHERE goal_id = :id ORDER BY goal_seq""")
                .param("id", goalId)
                .query((rs, i) -> new LedgerEvent(rs.getString("id"), rs.getString("type"),
                        Json.tree(rs.getString("payload")), rs.getString("principal"), rs.getString("causation_id"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Goals that recorded facts since the given moment. */
    public List<String> goalsActiveSince(Instant since) {
        return jdbc.sql("SELECT DISTINCT goal_id FROM events WHERE goal_id IS NOT NULL AND occurred_at > :since")
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC)).query(String.class).list();
    }

    /** What a goal id or its prefix may hold: hexadecimal digits and dashes, as a UUID is written. */
    private static final Pattern GOAL_REF = Pattern.compile("[0-9a-f-]{4,36}");

    /**
     * Resolves a goal by full id or a unique id prefix (4 to 36 characters of hexadecimal digits and dashes). Any
     * other reference resolves to nothing, so a LIKE wildcard ("%", "_") or escape in it never reaches the query.
     */
    public Optional<String> resolveGoal(String ref) {
        String prefix = ref == null ? "" : ref.strip().toLowerCase(Locale.ROOT);
        if (!GOAL_REF.matcher(prefix).matches()) {
            return Optional.empty();
        }
        List<String> ids = jdbc.sql("SELECT id FROM goals WHERE id LIKE :p ORDER BY created_at LIMIT 2")
                .param("p", prefix + "%").query(String.class).list();
        return ids.size() == 1 ? Optional.of(ids.getFirst()) : Optional.empty();
    }

    /** True for a text that can be a goal id or its prefix (see {@link #resolveGoal}). */
    static boolean goalRef(String ref) {
        return ref != null && GOAL_REF.matcher(ref.strip().toLowerCase(Locale.ROOT)).matches();
    }

    /**
     * Every goal that is neither completed nor cancelled, the one a person touched most recently first. The one
     * view of "open goals" that grounding, the agenda and the limit rules share.
     */
    public List<GoalState> openGoals() {
        return jdbc.sql("""
                SELECT snapshot::text FROM goals WHERE status NOT IN ('COMPLETED', 'CANCELLED')
                ORDER BY updated_at DESC, id""")
                .query(String.class).list().stream().map(json -> Json.read(json, GoalState.class)).toList();
    }

    /** The most recently cancelled goals, latest first, so "bring back the trip" can find them. */
    public List<GoalState> recentlyCancelled(int limit) {
        return recentlyClosed(GoalStatus.CANCELLED, limit);
    }

    /**
     * The most recently completed goals, latest first, so "reopen the move" or "T3 isn't done after all" can find them.
     */
    public List<GoalState> recentlyCompleted(int limit) {
        return recentlyClosed(GoalStatus.COMPLETED, limit);
    }

    private List<GoalState> recentlyClosed(GoalStatus status, int limit) {
        return jdbc.sql("SELECT snapshot::text FROM goals WHERE status = :status ORDER BY updated_at DESC, id LIMIT :n")
                .param("status", status.name()).param("n", limit)
                .query(String.class).list().stream().map(json -> Json.read(json, GoalState.class)).toList();
    }

    /** Summaries of the most recently created goals, newest first, at most {@code limit}, whatever their status. */
    public List<KernelApi.GoalSummary> list(int limit) {
        return list(limit, GoalCut.ALL, null);
    }

    /**
     * Which goals a list holds: all of them, the open ones (neither completed nor cancelled), the completed or the
     * cancelled ones, or the open ones by their deadline compared with today: past, today, tomorrow, from today to
     * the coming Sunday, or in the week after that (Monday to Sunday).
     */
    public enum GoalCut {
        ALL(""),
        OPEN(" WHERE status NOT IN ('COMPLETED', 'CANCELLED')"),
        COMPLETED(" WHERE status = 'COMPLETED'"),
        CANCELLED(" WHERE status = 'CANCELLED'"),
        OVERDUE(" WHERE status NOT IN ('COMPLETED', 'CANCELLED') AND due_date < :from"),
        DUE_TODAY(" WHERE status NOT IN ('COMPLETED', 'CANCELLED') AND due_date = :from"),
        DUE_TOMORROW(" WHERE status NOT IN ('COMPLETED', 'CANCELLED') AND due_date = :from"),
        DUE_THIS_WEEK(" WHERE status NOT IN ('COMPLETED', 'CANCELLED') AND due_date >= :from AND due_date <= :to"),
        DUE_NEXT_WEEK(" WHERE status NOT IN ('COMPLETED', 'CANCELLED') AND due_date >= :from AND due_date <= :to");

        private final String where;

        GoalCut(String where) {
            this.where = where;
        }

        /** The first day the cut's deadlines are compared with on {@code today}. */
        LocalDate from(LocalDate today) {
            return switch (this) {
                case DUE_TOMORROW -> today.plusDays(1);
                case DUE_NEXT_WEEK -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                default -> today;
            };
        }

        /** The last day of the cut's deadlines on {@code today}, for the cuts of a week. */
        LocalDate to(LocalDate today) {
            return this == DUE_NEXT_WEEK ? from(today).plusDays(6)
                    : today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        }

        boolean dated() {
            return where.contains(":from");
        }
    }

    /**
     * Summaries of the most recently created goals in {@code cut}, newest first, at most {@code limit}: the cut is
     * made in the query, before the limit, so a list of overdue goals misses none of them.
     */
    public List<KernelApi.GoalSummary> list(int limit, GoalCut cut, LocalDate today) {
        var query = jdbc.sql("SELECT id, text, status, due_date, task_count, done_count, created_at, updated_at"
                + " FROM goals" + cut.where + " ORDER BY created_at DESC LIMIT :limit").param("limit", limit);
        return dates(query, cut, today).query(LedgerService::summary).list();
    }

    /** How many goals {@code cut} holds. */
    public int count(GoalCut cut, LocalDate today) {
        Integer n = dates(jdbc.sql("SELECT count(*) FROM goals" + cut.where), cut, today).query(Integer.class).single();
        return n == null ? 0 : n;
    }

    private static JdbcClient.StatementSpec dates(JdbcClient.StatementSpec query, GoalCut cut, LocalDate today) {
        if (!cut.dated()) {
            return query;
        }
        LocalDate day = today == null ? LocalDate.now() : today;
        query = query.param("from", cut.from(day));
        return cut.where.contains(":to") ? query.param("to", cut.to(day)) : query;
    }

    /** One goal's summary as lists show it; empty for an unknown goal. */
    public Optional<KernelApi.GoalSummary> summary(String goalId) {
        return jdbc.sql("SELECT id, text, status, due_date, task_count, done_count, created_at, updated_at FROM goals WHERE id = :id")
                .param("id", goalId).query(LedgerService::summary).optional();
    }

    private static KernelApi.GoalSummary summary(ResultSet rs, int i) throws SQLException {
        return new KernelApi.GoalSummary(rs.getString("id"), rs.getString("text"),
                GoalStatus.valueOf(rs.getString("status")), rs.getObject("due_date", LocalDate.class),
                rs.getInt("task_count"), rs.getInt("done_count"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    /** Facts not yet published to the broker, oldest first. */
    public List<StoredEvent> unpublished(int limit) {
        return jdbc.sql("""
                SELECT seq, id, goal_id, type, payload::text AS payload, principal, causation_id, occurred_at
                FROM events WHERE NOT published ORDER BY seq LIMIT :limit""")
                .param("limit", limit)
                .query((rs, i) -> new StoredEvent(rs.getLong("seq"), rs.getString("id"), rs.getString("goal_id"),
                        rs.getString("type"), rs.getString("payload"), rs.getString("principal"),
                        rs.getString("causation_id"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Marks the facts with these sequence numbers as published to the broker. */
    public void markPublished(List<Long> seqs) {
        if (!seqs.isEmpty()) {
            jdbc.sql("UPDATE events SET published = TRUE WHERE seq IN (:seqs)").param("seqs", new ArrayList<>(seqs)).update();
        }
    }

    /** A stored fact as the outbox relay publishes it; {@code goalId} is null for a system fact. */
    public record StoredEvent(long seq, String id, String goalId, String type, String payload, String principal,
                              String causationId, Instant at) {
    }
}
