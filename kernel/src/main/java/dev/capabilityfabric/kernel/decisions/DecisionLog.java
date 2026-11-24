package dev.capabilityfabric.kernel.decisions;

import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.DecisionRecord;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Stores every model call for audit and replay. Records come from agents, so what they claim about themselves is
 * not trusted: each record is stored under the instance that verifiably sent it and one of the capabilities that
 * instance serves, with the kernel's own time, and only so many per signing key (all instances of one key share a
 * quota; without a known key the instance has its own); a replay lookup by an agent finds only records of
 * capabilities it serves.
 */
@Component
public class DecisionLog {

    private static final Logger log = LoggerFactory.getLogger(DecisionLog.class);

    /** Records taken from one answer, proposal or message; the rest are dropped. */
    static final int MAX_PER_BATCH = 50;
    /** Characters of request, response, parsed answer and errors one record may have together. */
    static final int MAX_RECORD_CHARS = 200_000;
    /** Records stored per signing key (or per instance without a known key) per {@link #QUOTA_WINDOW}. */
    static final int MAX_PER_INSTANCE = 600;
    static final Duration QUOTA_WINDOW = Duration.ofHours(1);
    /** Quota holders remembered; beyond it the oldest windows are forgotten. */
    static final int MAX_TRACKED = 1_000;

    private final JdbcClient jdbc;
    private final Clock clock;
    /** The key an instance signs with, as the registry bound it; empty when the instance is not known. */
    private final Function<String, Optional<String>> keyOf;
    private final Map<String, Window> quota = new HashMap<>();

    /**
     * The verified sender of a batch of records: the instance and the capabilities it serves. A record keeps the
     * capability it names only when it is one of these; otherwise it is stored under the first.
     */
    public record Source(String instanceId, List<String> capabilities) {

        public Source {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        }

        /** The instance a manifest describes, serving that manifest's capability. */
        public static Source of(CapabilityManifest manifest) {
            return new Source(manifest.instanceId(), List.of(manifest.id()));
        }

        String capabilityFor(String claimed) {
            if (claimed != null && capabilities.contains(claimed)) {
                return claimed;
            }
            return capabilities.isEmpty() ? claimed : capabilities.getFirst();
        }
    }

    private record Window(Instant start, int stored) {
    }

    @Autowired
    public DecisionLog(JdbcClient jdbc, @Lazy CapabilityRegistry registry) {
        this(jdbc, Clock.systemUTC(), registry::keyOf);
    }

    DecisionLog(JdbcClient jdbc, Clock clock) {
        this(jdbc, clock, instance -> Optional.empty());
    }

    DecisionLog(JdbcClient jdbc, Clock clock, Function<String, Optional<String>> keyOf) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.keyOf = keyOf;
    }

    /**
     * Stores records the kernel received from {@code source}, in its name. {@code goalId}, {@code runId} and
     * {@code step} say where the call happened ({@code -1} for no step).
     */
    public void saveAll(String goalId, String runId, int step, List<DecisionRecord> decisions, Source source) {
        if (decisions == null || decisions.isEmpty()) {
            return;
        }
        List<DecisionRecord> accepted = accepted(decisions, source, clock.instant());
        for (DecisionRecord d : accepted) {
            try {
                save(goalId, runId, step, d);
            } catch (RuntimeException e) {
                log.warn("could not store decision {}: {}", d.id(), e.getMessage());
            }
        }
    }

    /**
     * Stores records whose sender the caller cannot name: the capability and instance they claim are kept, the
     * time and the limits are the kernel's.
     */
    public void saveAll(String goalId, String runId, int step, List<DecisionRecord> decisions) {
        saveAll(goalId, runId, step, decisions, null);
    }

    /**
     * The records that will be stored, rewritten in the sender's name with the kernel's time: complete ones, not too
     * large, at most {@link #MAX_PER_BATCH} per batch and within the quota of the sender's signing key. Records of one
     * batch get times one microsecond apart in batch order, so a lookup tells the attempts of one call apart.
     */
    List<DecisionRecord> accepted(List<DecisionRecord> decisions, Source source, Instant now) {
        List<DecisionRecord> complete = decisions.stream()
                .filter(d -> {
                    if (d == null || d.id() == null || d.promptHash() == null) {
                        log.warn("ignoring an incomplete decision record from {}", source == null
                                ? (d == null ? "?" : d.capability()) : source.instanceId());
                        return false;
                    }
                    if (size(d) > MAX_RECORD_CHARS) {
                        log.warn("ignoring decision {}: larger than {} characters", d.id(), MAX_RECORD_CHARS);
                        return false;
                    }
                    return true;
                })
                .limit(MAX_PER_BATCH)
                .toList();
        String instance = source != null ? source.instanceId() : null;
        int allowed = take(quotaHolder(instance), complete.size(), now);
        if (allowed < complete.size()) {
            log.warn("storing {} of {} decision records from {}: the hourly quota of {} of its key is used up",
                    allowed, complete.size(), instance, MAX_PER_INSTANCE);
        }
        // The table keeps microseconds: start from a whole microsecond so each record keeps its own time.
        Instant first = now.truncatedTo(ChronoUnit.MICROS);
        List<DecisionRecord> stored = new ArrayList<>();
        for (DecisionRecord d : complete.subList(0, allowed)) {
            stored.add(new DecisionRecord(d.id(),
                    source == null ? d.capability() : source.capabilityFor(d.capability()),
                    source == null ? d.instanceId() : source.instanceId(), d.purpose(), d.model(), d.promptHash(),
                    d.request(), d.responseText(), d.parsed(), d.valid(), d.validationErrors(), d.attempt(),
                    d.latencyMs(), d.promptTokens(), d.completionTokens(), d.replayed(),
                    first.plus(stored.size(), ChronoUnit.MICROS)));
        }
        return stored;
    }

    /** Whose quota a batch from {@code instance} counts against: its signing key when known, else the instance. */
    String quotaHolder(String instance) {
        if (instance == null) {
            return "?";
        }
        return keyOf.apply(instance).map(key -> "key:" + key).orElse("instance:" + instance);
    }

    /** Characters a record would take in the table. */
    static long size(DecisionRecord d) {
        long chars = d.responseText() == null ? 0 : d.responseText().length();
        chars += d.request() == null ? 0 : Json.write(d.request()).length();
        chars += d.parsed() == null ? 0 : Json.write(d.parsed()).length();
        chars += d.validationErrors().stream().mapToLong(e -> e == null ? 0 : e.length()).sum();
        return chars;
    }

    /** How many of {@code wanted} records {@code holder} may still store in its current window. */
    synchronized int take(String holder, int wanted, Instant now) {
        Window window = quota.get(holder);
        if (window == null || !now.isBefore(window.start().plus(QUOTA_WINDOW))) {
            if (quota.size() >= MAX_TRACKED) {
                quota.values().removeIf(w -> !now.isBefore(w.start().plus(QUOTA_WINDOW)));
                if (quota.size() >= MAX_TRACKED) {
                    quota.clear();
                }
            }
            window = new Window(now, 0);
        }
        int granted = Math.max(0, Math.min(wanted, MAX_PER_INSTANCE - window.stored()));
        quota.put(holder, new Window(window.start(), window.stored() + granted));
        return granted;
    }

    private void save(String goalId, String runId, int step, DecisionRecord d) {
        jdbc.sql("""
                INSERT INTO decisions (id, goal_id, run_id, step, capability, instance_id, purpose, model, prompt_hash,
                    request, response_text, parsed, valid, errors, attempt, latency_ms, prompt_tokens,
                    completion_tokens, replayed, created_at)
                VALUES (:id, :goal, :run, :step, :cap, :instance, :purpose, :model, :hash, CAST(:request AS jsonb),
                    :response, CAST(:parsed AS jsonb), :valid, CAST(:errors AS jsonb), :attempt, :latency, :pt, :ct,
                    :replayed, :at)
                ON CONFLICT (id) DO NOTHING""")
                .param("id", d.id()).param("goal", goalId).param("run", runId).param("step", step)
                .param("cap", d.capability()).param("instance", d.instanceId()).param("purpose", d.purpose())
                .param("model", d.model()).param("hash", d.promptHash())
                .param("request", d.request() == null ? null : Json.write(d.request()))
                .param("response", d.responseText())
                .param("parsed", d.parsed() == null ? null : Json.write(d.parsed()))
                .param("valid", d.valid()).param("errors", Json.write(d.validationErrors()))
                .param("attempt", d.attempt()).param("latency", d.latencyMs())
                .param("pt", d.promptTokens()).param("ct", d.completionTokens())
                .param("replayed", d.replayed())
                .param("at", OffsetDateTime.ofInstant(d.at(), ZoneOffset.UTC))
                .update();
    }

    /** The original (not replayed) answer recorded for a prompt hash, newest first, whatever its capability. */
    public Optional<DecisionRecord> lookup(String promptHash) {
        return lookup(promptHash, null);
    }

    /**
     * The original (not replayed) answer recorded for a prompt hash, newest first, among records of
     * {@code capabilities} ({@code null} for any capability; empty finds nothing). Records stored at the same moment
     * are told apart by their attempt number, the later attempt first, then by id, so a lookup always finds the same
     * record.
     */
    public Optional<DecisionRecord> lookup(String promptHash, Collection<String> capabilities) {
        if (capabilities != null && capabilities.isEmpty()) {
            return Optional.empty();
        }
        String scope = capabilities == null ? "" : " AND capability IN (:caps)";
        var statement = jdbc.sql("SELECT id, capability, instance_id, purpose, model, prompt_hash, response_text, valid,"
                        + " attempt, latency_ms, prompt_tokens, completion_tokens, replayed, created_at"
                        + " FROM decisions WHERE prompt_hash = :hash" + scope
                        + " ORDER BY replayed ASC, created_at DESC, attempt DESC, id DESC LIMIT 1")
                .param("hash", promptHash);
        if (capabilities != null) {
            statement = statement.param("caps", Set.copyOf(capabilities));
        }
        return statement.query((rs, i) -> new DecisionRecord(rs.getString("id"), rs.getString("capability"),
                        rs.getString("instance_id"), rs.getString("purpose"), rs.getString("model"),
                        rs.getString("prompt_hash"), null, rs.getString("response_text"), null,
                        rs.getBoolean("valid"), List.of(), rs.getInt("attempt"), rs.getLong("latency_ms"),
                        (Integer) rs.getObject("prompt_tokens"), (Integer) rs.getObject("completion_tokens"),
                        rs.getBoolean("replayed"), rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /** Every model call recorded for a goal, oldest first, without prompts and answers. */
    public List<KernelApi.DecisionView> forGoal(String goalId) {
        return jdbc.sql("""
                SELECT id, capability, purpose, model, valid, attempt, latency_ms, prompt_tokens, completion_tokens,
                       replayed, created_at
                FROM decisions WHERE goal_id = :goal ORDER BY created_at""")
                .param("goal", goalId)
                .query((rs, i) -> new KernelApi.DecisionView(rs.getString("id"), rs.getString("capability"),
                        rs.getString("purpose"), rs.getString("model"), rs.getBoolean("valid"), rs.getInt("attempt"),
                        rs.getLong("latency_ms"), (Integer) rs.getObject("prompt_tokens"),
                        (Integer) rs.getObject("completion_tokens"), rs.getBoolean("replayed"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}
