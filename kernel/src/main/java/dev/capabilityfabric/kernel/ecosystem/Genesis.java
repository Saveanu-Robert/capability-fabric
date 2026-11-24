package dev.capabilityfabric.kernel.ecosystem;

import dev.capabilityfabric.kernel.decisions.DecisionLog;
import dev.capabilityfabric.kernel.execution.CapabilityInvoker;
import dev.capabilityfabric.kernel.inbox.InboxService;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.kernel.security.SecurityService;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.SignedSpec;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.FabricSecurity;
import io.nats.client.Connection;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Capability genesis: the system grows new capabilities from needs people express. A model designs the capability
 * as data; this class enforces the rules (annotations only, safe value shapes, allowed inputs, free id) and, after
 * the person's confirmation, publishes the spec. The forge then hosts it without new code. Grown capabilities
 * start on probation and are kept or retired by the person.
 *
 * <p>Every spec goes into the genesis bucket signed with the kernel's key ({@link SignedSpec}), and only specs whose
 * signature verifies against that key are read back and given to the host: a value someone else wrote there, or one
 * that is not a signed spec at all, is skipped, with one warning per entry. A well-formed spec signed by another key
 * is left in place: a changed kernel key is the likely cause, and removing it would lose the person's capability for
 * good.
 *
 * <p>The ledger is the source of truth for what was grown. Every lifecycle fact this class records
 * ({@code capability.grown}, {@code capability.kept}, {@code capability.retired}) carries the spec in its published
 * form ({@link LifecycleFact}), and an id that ever had a {@code capability.grown} fact is never given to a new
 * capability ({@link #takenIds()}). Once the kernel has started, the bucket is reconciled with the ledger
 * ({@link #reconcile()}):
 * <ul>
 *   <li>an unsigned spec filed under its own id (written before specs were signed) whose id has a grown fact, and
 *   whose facts carry no spec (they were recorded before facts carried one), stays in the bucket, quarantined: it is
 *   not given to the host and not listed with the grown capabilities ({@link #all()}), only with the quarantined ones
 *   ({@link #quarantined()}); unless it is retired, the owner is asked once, in the inbox, to keep it; keeping it
 *   ({@link #keepQuarantined}) signs it, and retiring it ({@link #retireQuarantined}) removes it;</li>
 *   <li>every other value that is not a signed spec (an unsigned spec nobody grew or whose facts carry the spec, a
 *   spec filed under another id, a value that is not a spec) is removed;</li>
 *   <li>a grown capability that is not retired and has no value in the bucket is published again from the spec its
 *   facts carry, or, when they carry none, recorded as retired, and the owner is told once in the inbox.</li>
 * </ul>
 * A capability removed from the bucket, quarantined or retired this way is also taken out of the grants of the agents
 * that host grown capabilities, so nobody keeps the right to serve a capability the kernel does not vouch for.
 *
 * <p>A spec is published without the sentence the person asked with ({@code need}) and without who asked
 * ({@code createdBy}): the agent hosting grown capabilities is given that published form ({@link #signedSpecs()}) and
 * needs neither. Both stay in Postgres, in the ledger's {@code capability.grown} fact, and {@link #all()} adds them
 * back for the person's own views. At startup, specs published before that are written again without them.
 */
@Component
public class Genesis {

    private static final Logger log = LoggerFactory.getLogger(Genesis.class);
    public static final String DESIGNER = "capability.design";
    /** The provider that hosts grown capabilities, as lifecycle facts name it. */
    static final String HOST = "agent-forge";
    /** The status of the {@code capability.quarantined} fact recorded when an unsigned spec is reported. */
    static final String UNSIGNED = "UNSIGNED";
    /** Why a grown capability whose spec is in neither the bucket nor the ledger is retired at start. */
    static final String LOST = "its spec was lost in an upgrade; grow it again";
    /** Most characters of the person's sentence or a capability's name quoted in an inbox message. */
    static final int MAX_QUOTE = 120;

    private final Connection connection;
    private final CapabilityInvoker invoker;
    private final CapabilityRegistry registry;
    private final LedgerService ledger;
    private final JdbcClient jdbc;
    private final DecisionLog decisions;
    private final Security.Identity kernel;
    private final SecurityService access;
    private final InboxService inbox;
    /** The genesis entries (key and revision) already warned about, so each is reported once. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    /** More reported entries than this and the record starts over, so it stays small whatever is in the bucket. */
    static final int MAX_REPORTED = 1024;

    public Genesis(Connection connection, CapabilityInvoker invoker, CapabilityRegistry registry, LedgerService ledger,
                   JdbcClient jdbc, DecisionLog decisions, FabricSecurity security,
                   @Lazy SecurityService access, InboxService inbox) {
        this.connection = connection;
        this.invoker = invoker;
        this.registry = registry;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.decisions = decisions;
        this.kernel = security.identity();
        this.access = access;
        this.inbox = inbox;
    }

    /**
     * The payload of the lifecycle facts this class records: the fields of {@link Facts.CapabilityLifecycle} and, for
     * a spec the kernel vouches for, that spec in its published form ({@link #published}), so the ledger alone can
     * publish it again. {@code spec} is null on the facts about a spec that is unsigned or lost.
     */
    public record LifecycleFact(String capability, String provider, String status, String reason, String by,
                                Ecosystem.CapabilitySpec spec) {
    }

    /** What a value in the genesis bucket is to this kernel. */
    enum Entry {
        /** A spec this kernel signed, filed under its own id. */
        SPEC,
        /** A well-formed spec filed under its own id with a signature that does not verify against this kernel. */
        FOREIGN,
        /** A well-formed spec filed under its own id without a signature, as stored before specs were signed. */
        UNSIGNED,
        /** Anything else: a spec filed under another id, a value that is not a spec at all. */
        UNREADABLE
    }

    /** Classifies the value stored under {@code key}. */
    static Entry classify(String key, byte[] value, String kernelKey) {
        if (SignedSpec.read(key, value, kernelKey).isPresent()) {
            return Entry.SPEC;
        }
        if (SignedSpec.parse(value).filter(s -> s.signature() != null && !s.signature().isBlank()
                && s.spec().id().equals(key)).isPresent()) {
            return Entry.FOREIGN;
        }
        return unsigned(key, value).isPresent() ? Entry.UNSIGNED : Entry.UNREADABLE;
    }

    /**
     * The spec in {@code value} when it is an unsigned spec filed under its own id: a bare spec, or a signed spec
     * without a signature. It must have an id, a scope and a status. Empty for anything else; never throws.
     */
    static Optional<Ecosystem.CapabilitySpec> unsigned(String key, byte[] value) {
        Optional<SignedSpec> wrapped = SignedSpec.parse(value);
        if (wrapped.isPresent()) {
            String signature = wrapped.get().signature();
            return signature == null || signature.isBlank()
                    ? Optional.of(wrapped.get().spec()).filter(s -> s.id().equals(key)) : Optional.empty();
        }
        if (value == null || value.length == 0) {
            return Optional.empty();
        }
        try {
            Ecosystem.CapabilitySpec bare = Json.read(value, Ecosystem.CapabilitySpec.class);
            return bare != null && key.equals(bare.id()) && bare.scope() != null && bare.status() != null
                    ? Optional.of(bare) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Once the kernel has started, the genesis bucket is reconciled with the ledger ({@link #reconcile()}) and specs
     * that still carry the person's sentence or name are written again without them ({@link #unpublishOrigins()}).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void started() {
        Reconciled done = reconcile();
        if (!done.isEmpty()) {
            log.info("genesis reconciled: removed {}, quarantined {}, published again {}, retired {}", done.removed(),
                    done.quarantined(), done.restored(), done.retired());
        }
        unpublishOrigins();
    }

    /**
     * Writes every spec this kernel signed that still names its {@code need} or {@code createdBy} again without
     * them ({@link #published}), so they leave the published spec. Returns the keys written. A bucket that cannot be
     * read is left as it is; an entry that cannot be written stays as it was and the others are still written.
     */
    List<String> unpublishOrigins() {
        List<String> written = new ArrayList<>();
        for (Ecosystem.CapabilitySpec spec : stored()) {
            if (spec.need() == null && spec.createdBy() == null) {
                continue;
            }
            try {
                put(spec);
                written.add(spec.id());
            } catch (RuntimeException e) {
                log.warn("could not rewrite genesis entry {}: {}", spec.id(), e.getMessage());
            }
        }
        return written;
    }

    /** The capability ids {@link #reconcile()} removed, quarantined, published again from the ledger and retired. */
    record Reconciled(List<String> removed, List<String> quarantined, List<String> restored, List<String> retired) {
        boolean isEmpty() {
            return removed.isEmpty() && quarantined.isEmpty() && restored.isEmpty() && retired.isEmpty();
        }
    }

    /**
     * Brings the genesis bucket in line with the ledger, as the class description lists: unsigned specs of grown
     * capabilities whose facts carry no spec are quarantined (the owner is asked once), every other value that is not
     * a signed spec is removed, and every grown capability that is not retired and has no value left in the bucket is
     * published again from its facts' spec or, without one, retired (the owner is told once). Signed specs, and specs
     * signed by another key, stay as they are.
     *
     * <p>The ledger is read before the bucket, so a capability grown meanwhile (its spec is written before its fact)
     * is never taken for lost. Nothing happens when the bucket cannot be read. When the ledger cannot be read,
     * unsigned specs are left alone (nobody can tell whether they were grown) and no capability is published again
     * or retired; values that are not specs are still removed. An entry that cannot be read or removed counts as
     * present, and the others are still handled.
     */
    Reconciled reconcile() {
        Optional<Map<String, History>> ledgerView = history();
        List<String> removed = new ArrayList<>();
        List<String> quarantined = new ArrayList<>();
        List<String> restored = new ArrayList<>();
        List<String> retired = new ArrayList<>();
        KeyValue kv;
        List<String> keys;
        try {
            kv = connection.keyValue(Subjects.KV_GENESIS);
            keys = kv.keys();
        } catch (Exception e) {
            log.debug("genesis bucket not readable: {}", e.getMessage());
            return new Reconciled(removed, quarantined, restored, retired);
        }
        Map<String, History> histories = ledgerView.orElse(null);
        Set<String> present = new HashSet<>();
        for (String key : keys) {
            try {
                KeyValueEntry entry = kv.get(key);
                byte[] value = entry == null ? null : entry.getValue();
                Entry kind = classify(key, value, kernel.publicKeyText());
                if (kind == Entry.SPEC || kind == Entry.FOREIGN || (kind == Entry.UNSIGNED && histories == null)) {
                    present.add(key);
                } else if (kind == Entry.UNSIGNED && histories.containsKey(key) && histories.get(key).spec() == null) {
                    present.add(key);
                    quarantined.add(key);
                    quarantine(key, value, histories.get(key));
                } else {
                    kv.purge(key);
                    removed.add(key);
                    log.warn("removed genesis entry {}: not a spec signed by this kernel", key);
                    access.withdrawGrowth(key, tokenOf(key, value));
                }
            } catch (Exception e) {
                present.add(key);
                log.warn("could not reconcile genesis entry {}: {}", key, e.getMessage());
            }
        }
        if (histories == null) {
            return new Reconciled(removed, quarantined, restored, retired);
        }
        for (History h : histories.values()) {
            if (h.retired() || present.contains(h.capability())) {
                continue;
            }
            try {
                if (h.spec() != null) {
                    restore(h);
                    restored.add(h.capability());
                } else {
                    retireLost(h);
                    retired.add(h.capability());
                }
            } catch (RuntimeException e) {
                log.warn("could not reconcile grown capability {}: {}", h.capability(), e.getMessage());
            }
        }
        return new Reconciled(removed, quarantined, restored, retired);
    }

    /**
     * Quarantines the unsigned spec of a grown capability: no host may serve it, and unless it is retired or was
     * already reported since it was last grown or kept, a {@code capability.quarantined} fact records that it was
     * reported and the owner's inbox gets one message asking them to keep it. The fact comes first, so the message is
     * never posted twice.
     */
    private void quarantine(String key, byte[] value, History history) {
        access.withdrawGrowth(key, tokenOf(key, value));
        if (history.retired() || history.reported()) {
            return;
        }
        Ecosystem.CapabilitySpec spec = unsigned(key, value).orElseThrow();
        ledger.recordSystem(Facts.CAPABILITY_QUARANTINED, new LifecycleFact(key, HOST, UNSIGNED,
                "from an older version: waits for the owner's yes", "kernel", null), "kernel");
        // The suggestion lets a yes to this message keep the capability, exactly as saying "keep <id>" does.
        Conversation.Action keep = new Conversation.Action(Conversation.ActionType.KEEP_CAPABILITY, null, null, key,
                null, null, null, null, null, null);
        inbox.post(new Inbox.Post(Inbox.Kind.SECURITY, null, keepText(spec), List.of(keep), keepKey(key), "kernel",
                "kernel", List.of(), null), null);
        log.warn("quarantined genesis entry {}: an unsigned spec from an older version; the owner was asked to keep it",
                key);
    }

    /**
     * Publishes a grown capability again from the spec its facts carry, as kept (ACTIVE) when its latest fact is a
     * {@code capability.kept} and on probation otherwise, and lets the agents that host grown capabilities serve it.
     */
    private void restore(History history) {
        Ecosystem.CapabilitySpec spec = history.spec().withStatus(Facts.CAPABILITY_KEPT.equals(history.last())
                ? Ecosystem.SpecStatus.ACTIVE : Ecosystem.SpecStatus.PROBATION);
        put(spec);
        access.extendForGrowth(spec);
        log.warn("published grown capability {} again from the ledger: the genesis bucket had no spec for it",
                spec.id());
    }

    /**
     * Retires a grown capability whose spec is gone from the bucket and was never in the ledger: a
     * {@code capability.retired} fact records why ({@link #LOST}), the owner's inbox gets one notice, and its id
     * leaves the hosts' grants. The fact comes first, so the notice is never posted twice. The scope of such a
     * capability is not known, so the task annotation (the usual scope) is the token withdrawn; without the
     * capability id in its grant no agent may serve it whatever token remains.
     */
    private void retireLost(History history) {
        String id = history.capability();
        ledger.recordSystem(Facts.CAPABILITY_RETIRED, new LifecycleFact(id, HOST,
                Ecosystem.SpecStatus.RETIRED.name(), LOST, "kernel", null), "kernel");
        inbox.post(new Inbox.Post(Inbox.Kind.SECURITY, null, lostText(history), List.of(), lostKey(id), "kernel",
                "kernel", List.of(), null), null);
        access.withdrawGrowth(id, Vocabulary.taskAnnotation(id));
        log.warn("retired grown capability {}: {}", id, LOST);
    }

    /** The inbox message asking the owner to keep a quarantined capability from an older version. */
    static String keepText(Ecosystem.CapabilitySpec spec) {
        String name = spec.name() == null || spec.name().isBlank() ? "" : "\"" + quote(spec.name()) + "\" ";
        return "The grown capability " + name + "(" + spec.id() + ") from an older version needs your yes before it "
                + "runs again: say \"keep " + spec.id() + "\".";
    }

    /** The inbox notice about a grown capability whose spec was lost. */
    static String lostText(History history) {
        String asked = history.need() == null || history.need().isBlank() ? ""
                : " (you asked: \"" + quote(history.need()) + "\")";
        return "The grown capability " + history.capability() + " was retired: its spec was lost in an upgrade. "
                + "Ask for it again if you still need it" + asked + ".";
    }

    private static String quote(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= MAX_QUOTE ? line : line.substring(0, MAX_QUOTE - 3) + "...";
    }

    /**
     * The dedupe key of the message asking the owner to keep the quarantined capability {@code id}. It ends with a
     * colon, so as a prefix it names this capability only and never one whose id merely starts with {@code id}.
     */
    static String keepKey(String id) {
        return "security:capability-unsigned:" + id + ":";
    }

    /** The dedupe key of the notice that the grown capability {@code id} was lost. */
    static String lostKey(String id) {
        return "security:capability-lost:" + id + ":";
    }

    /**
     * The token a grown capability filed under {@code key} wrote: a goal annotation when the value names that id with
     * the goal scope, a task annotation (the usual scope) otherwise.
     */
    static String tokenOf(String key, byte[] value) {
        return scopeOf(key, value) == Ecosystem.Scope.GOAL ? Vocabulary.goalAnnotation(key)
                : Vocabulary.taskAnnotation(key);
    }

    private static Ecosystem.Scope scopeOf(String key, byte[] value) {
        try {
            JsonNode tree = Json.tree(value);
            JsonNode spec = tree.has("spec") ? tree.get("spec") : tree;
            JsonNode id = spec.get("id");
            JsonNode scope = spec.get("scope");
            if (id != null && id.isString() && key.equals(id.asString()) && scope != null && scope.isString()
                    && Ecosystem.Scope.GOAL.name().equals(scope.asString())) {
                return Ecosystem.Scope.GOAL;
            }
        } catch (RuntimeException e) {
            // Not JSON or not an object: nothing names a scope.
        }
        return Ecosystem.Scope.TASK;
    }

    /** A checked spec, or why there is none ({@code problem}). */
    public record Design(Ecosystem.CapabilitySpec spec, String problem) {
    }

    /**
     * Asks whichever provider offers {@code capability.design} for a spec for {@code need}, records the designer's
     * model calls, then checks the spec. The designer is given the person's request ({@code need}) and, when
     * {@code goalId} is given, the goal's text and the titles of its first 15 tasks: what the terms of
     * {@code capability.design} disclose to the owner at admission ({@code Contract.KERNEL_SUPPLIED}). Besides that it
     * gets only the capability ids a new spec may not use ({@code taken}, see {@link #takenIds()}); no goal context,
     * task details or other goals are sent.
     */
    public Design design(String need, String goalId, String principal) {
        if (!registry.has(DESIGNER)) {
            return new Design(null, "no capability designer is running (start agent-forge)");
        }
        ObjectNode params = Json.object();
        params.put("need", need);
        if (goalId != null) {
            ledger.load(goalId).ifPresent(g -> {
                ObjectNode goal = params.putObject("goal");
                goal.put("text", g.text());
                ArrayNode tasks = goal.putArray("tasks");
                g.tasks().stream().limit(15).forEach(t -> tasks.add(t.title()));
            });
        }
        ArrayNode taken = params.putArray("taken");
        takenIds().forEach(taken::add);
        var invocation = invoker.invoke(goalId, "genesis", -1, DESIGNER, 1, null, params, Set.of());
        if (invocation.result() != null && invocation.manifest() != null) {
            decisions.saveAll(goalId, "genesis", -1, invocation.result().decisions(),
                    DecisionLog.Source.of(invocation.manifest()));
        }
        if (!invocation.ok() || invocation.result().output() == null) {
            String reason = invocation.failureReason();
            log.warn("capability design failed: {}", reason);
            return new Design(null, reason != null && reason.contains("no live provider")
                    ? "no live provider designs capabilities: start agent-forge"
                    : "the designer could not produce a capability for that");
        }
        Ecosystem.CapabilitySpec spec;
        try {
            spec = Json.MAPPER.treeToValue(invocation.result().output(), Ecosystem.CapabilitySpec.class);
        } catch (RuntimeException e) {
            spec = null;
        }
        if (spec == null) {
            return new Design(null, "the designer's answer is not a capability spec");
        }
        spec = new Ecosystem.CapabilitySpec(spec.id(), "0.1.0", spec.name(), spec.description(), spec.scope(),
                spec.valueSchema(), spec.instructions(), spec.consumes(), spec.tags(), Ecosystem.SpecStatus.PROBATION,
                need, principal, Instant.now());
        List<String> problems = Ecosystem.validate(spec, takenIds());
        return problems.isEmpty() ? new Design(spec, null) : new Design(null, String.join("; ", problems));
    }

    /**
     * Publishes a confirmed spec and records its {@code capability.grown} fact, which carries the spec; the forge
     * starts hosting it. When the fact cannot be recorded the spec is taken out of the bucket again and the error is
     * thrown, so no capability is published that the ledger does not know. Waits up to {@code waitForLive} until it
     * is live and returns whether it is.
     *
     * @throws IllegalArgumentException when the spec breaks a rule (for example its id is taken or reserved)
     */
    public boolean grow(Ecosystem.CapabilitySpec spec, String principal, Duration waitForLive) {
        List<String> problems = Ecosystem.validate(spec, takenIds());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        put(spec);
        try {
            ledger.recordSystem(Facts.CAPABILITY_GROWN, new LifecycleFact(spec.id(), HOST, spec.status().name(),
                    spec.need(), principal, published(spec)), principal);
        } catch (RuntimeException e) {
            unpublish(spec.id());
            throw e;
        }
        log.info("grew capability {} for need \"{}\"", spec.id(), spec.need());
        long deadline = System.currentTimeMillis() + waitForLive.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (registry.has(spec.id())) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return registry.has(spec.id());
    }

    /**
     * A grown capability by its exact id, else the first whose name contains the words or whose id or name the words
     * contain.
     */
    public Optional<Ecosystem.CapabilitySpec> find(String ref) {
        return match(all(), ref);
    }

    /** The spec in {@code specs} that {@code ref} names, by the rule of {@link #find}. */
    static Optional<Ecosystem.CapabilitySpec> match(List<Ecosystem.CapabilitySpec> specs, String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String r = ref.strip().toLowerCase(Locale.ROOT);
        return specs.stream().filter(s -> s.id().equalsIgnoreCase(r)).findFirst()
                .or(() -> specs.stream().filter(s -> s.name() != null && s.name().toLowerCase(Locale.ROOT).contains(r)
                        || r.contains(s.id()) || (s.name() != null && r.contains(s.name().toLowerCase(Locale.ROOT))))
                        .findFirst());
    }

    /**
     * Keeps or retires a grown capability: the spec is published with the new status and a fact, carrying the spec,
     * records why.
     */
    public Ecosystem.CapabilitySpec setStatus(Ecosystem.CapabilitySpec spec, Ecosystem.SpecStatus status,
                                              String principal, String reason) {
        Ecosystem.CapabilitySpec next = spec.withStatus(status);
        put(next);
        ledger.recordSystem(status == Ecosystem.SpecStatus.RETIRED ? Facts.CAPABILITY_RETIRED : Facts.CAPABILITY_KEPT,
                new LifecycleFact(spec.id(), HOST, status.name(), reason, principal, published(next)), principal);
        return next;
    }

    /**
     * The quarantined capabilities: grown specs without a signature, filed under their own id, whose id has a
     * {@code capability.grown} fact and whose facts carry no spec. They are not hosted and not in {@link #all()};
     * each waits for the owner to keep it ({@link #keepQuarantined}). Each comes with the sentence the person asked
     * with and who asked, from the ledger, and as retired when its latest lifecycle fact retired it. Empty when the
     * bucket or the ledger cannot be read.
     */
    public List<Ecosystem.CapabilitySpec> quarantined() {
        Optional<Map<String, History>> histories = history();
        if (histories.isEmpty() || histories.get().isEmpty()) {
            return List.of();
        }
        List<Ecosystem.CapabilitySpec> specs = new ArrayList<>();
        KeyValue kv;
        List<String> keys;
        try {
            kv = connection.keyValue(Subjects.KV_GENESIS);
            keys = kv.keys();
        } catch (Exception e) {
            log.debug("genesis bucket not readable: {}", e.getMessage());
            return specs;
        }
        for (String key : keys) {
            History history = histories.get().get(key);
            if (history == null || history.spec() != null) {
                continue;
            }
            try {
                KeyValueEntry entry = kv.get(key);
                if (entry == null || classify(key, entry.getValue(), kernel.publicKeyText()) != Entry.UNSIGNED) {
                    continue;
                }
                Ecosystem.CapabilitySpec spec = withOrigin(unsigned(key, entry.getValue()).orElseThrow(), history);
                specs.add(history.retired() ? spec.withStatus(Ecosystem.SpecStatus.RETIRED) : spec);
            } catch (Exception e) {
                log.debug("genesis entry {} not readable: {}", key, e.getMessage());
            }
        }
        return specs;
    }

    /** A quarantined capability ({@link #quarantined()}) that {@code ref} names, by the rule of {@link #find}. */
    public Optional<Ecosystem.CapabilitySpec> findQuarantined(String ref) {
        return match(quarantined(), ref);
    }

    /**
     * Keeps a quarantined capability ({@link #quarantined()}) at the owner's word: its spec is signed with the
     * kernel's key and published as kept (ACTIVE), a {@code capability.kept} fact carrying the spec records it, the
     * agents that host grown capabilities may serve it again, and the inbox message that asked for it is settled.
     * Returns the kept spec with the sentence the person asked with and who asked.
     *
     * @param id the capability's exact id, as {@link #findQuarantined} gives it
     * @throws IllegalArgumentException when no quarantined spec is filed under {@code id}, or its spec breaks a rule
     *                                  for grown capabilities (the message says which); nothing is changed then
     * @throws IllegalStateException    when the genesis bucket cannot be written; nothing is changed then
     */
    public Ecosystem.CapabilitySpec keepQuarantined(String id, String principal) {
        Ecosystem.CapabilitySpec waiting = quarantined().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no capability from an older version waits under "
                        + id));
        List<String> problems = Ecosystem.validate(waiting, Set.of());
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(id + " cannot be kept: " + String.join("; ", problems));
        }
        Ecosystem.CapabilitySpec kept = waiting.withStatus(Ecosystem.SpecStatus.ACTIVE);
        put(kept);
        ledger.recordSystem(Facts.CAPABILITY_KEPT, new LifecycleFact(id, HOST, kept.status().name(),
                "kept by a person after an upgrade", principal, published(kept)), principal);
        access.extendForGrowth(kept);
        inbox.settleByKey(keepKey(id), Inbox.Status.ACCEPTED, principal);
        log.info("kept grown capability {} from an older version; it is signed now", id);
        return kept;
    }

    /**
     * Retires a quarantined capability ({@link #quarantined()}) at the owner's word. A {@code capability.retired}
     * fact records it without a spec (the kernel never signed the unsigned spec, so no fact carries it), its value is
     * purged from the genesis bucket, its id leaves the grants of the agents that host grown capabilities, and the
     * inbox message that asked to keep it is settled as dismissed. A capability whose latest fact already retired it
     * gets no second fact. The fact comes before the purge: when the bucket cannot be written, the value stays there,
     * still quarantined, listed as retired and never hosted, and retiring it again removes it. Once purged it can no
     * longer be kept; its id stays taken ({@link #takenIds()}).
     *
     * <p>Returns the retired spec with the sentence the person asked with and who asked.
     *
     * @param id the capability's exact id, as {@link #findQuarantined} gives it
     * @throws IllegalArgumentException when no quarantined spec is filed under {@code id}; nothing is changed then
     */
    public Ecosystem.CapabilitySpec retireQuarantined(String id, String principal) {
        Ecosystem.CapabilitySpec waiting = quarantined().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no capability from an older version waits under "
                        + id));
        if (waiting.status() != Ecosystem.SpecStatus.RETIRED) {
            ledger.recordSystem(Facts.CAPABILITY_RETIRED, new LifecycleFact(id, HOST,
                    Ecosystem.SpecStatus.RETIRED.name(), RETIRED_QUARANTINED, principal, null), principal);
        }
        access.withdrawGrowth(id, waiting.token());
        unpublish(id);
        inbox.settleByKey(keepKey(id), Inbox.Status.DISMISSED, principal);
        log.info("retired grown capability {} from an older version; its unsigned spec was removed", id);
        return waiting.withStatus(Ecosystem.SpecStatus.RETIRED);
    }

    /** Why a quarantined capability was retired, as its {@code capability.retired} fact says. */
    static final String RETIRED_QUARANTINED = "retired by a person after an upgrade";

    /**
     * Every grown capability's spec that the kernel signed, filed under its own id, with the sentence the person
     * asked with and who asked, read from the ledger's {@code capability.grown} facts (a spec without such a fact
     * keeps what it carries); empty when the genesis bucket cannot be read. Any other value in the bucket is
     * skipped, quarantined ones included ({@link #quarantined()}). For the person's own views; agents get
     * {@link #signedSpecs()}.
     */
    public List<Ecosystem.CapabilitySpec> all() {
        List<Ecosystem.CapabilitySpec> specs = stored();
        if (specs.isEmpty()) {
            return specs;
        }
        Map<String, History> histories = history().orElse(Map.of());
        return specs.stream().map(s -> withOrigin(s, histories.get(s.id()))).toList();
    }

    /**
     * Every grown capability's spec in its published form ({@link #published}), signed with the kernel's key: what
     * the agent hosting grown capabilities is given. Retired specs are included, so the host stops serving them.
     * Empty when the genesis bucket cannot be read.
     */
    public List<SignedSpec> signedSpecs() {
        return stored().stream().map(s -> SignedSpec.sign(published(s), kernel)).toList();
    }

    /**
     * What the ledger says about one grown capability: the sentence the person asked with ({@code need}) and who
     * asked ({@code createdBy}), from its latest {@code capability.grown} fact; the type of its latest grown, kept or
     * retired fact ({@code last}); the latest spec those facts carry ({@code spec}, null when none carries one, as
     * with facts recorded before they carried the spec); and whether its unsigned spec was reported since it was
     * last grown or kept ({@code reported}).
     */
    record History(String capability, String need, String createdBy, String last, Ecosystem.CapabilitySpec spec,
                   boolean reported) {
        boolean retired() {
            return Facts.CAPABILITY_RETIRED.equals(last);
        }
    }

    /** One lifecycle fact as the ledger holds it: its type, its payload as JSON text, and who recorded it. */
    record Row(String type, String payload, String principal) {
    }

    /** The lifecycle facts of capabilities (system facts, without a goal), oldest first. */
    static final String HISTORY_SQL = """
            SELECT type, payload::text AS payload, principal FROM events
            WHERE type IN (:grown, :kept, :retired, :quarantined) AND goal_id IS NULL ORDER BY seq""";

    /**
     * The history of every capability that has a {@code capability.grown} fact, by id; empty when the ledger cannot
     * be read, which callers must tell apart from a ledger without grown capabilities.
     */
    Optional<Map<String, History>> history() {
        try {
            List<Row> rows = jdbc.sql(HISTORY_SQL).param("grown", Facts.CAPABILITY_GROWN)
                    .param("kept", Facts.CAPABILITY_KEPT).param("retired", Facts.CAPABILITY_RETIRED)
                    .param("quarantined", Facts.CAPABILITY_QUARANTINED)
                    .query((rs, i) -> new Row(rs.getString("type"), rs.getString("payload"), rs.getString("principal")))
                    .list();
            return Optional.of(fold(rows));
        } catch (RuntimeException e) {
            log.debug("grown capabilities' history not readable: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Folds lifecycle facts, oldest first, into one {@link History} per grown capability. Facts about an id before
     * its first grown fact, facts without a capability id, and {@code capability.quarantined} facts other than the
     * report of an unsigned spec (a provider quarantined for its record) are passed over. A spec a fact carries
     * counts only when it is filed under the fact's capability and has a scope and a status.
     */
    static Map<String, History> fold(List<Row> rows) {
        Map<String, History> histories = new LinkedHashMap<>();
        for (Row row : rows) {
            JsonNode payload = payload(row.payload());
            String id = text(payload, "capability");
            History before = id == null ? null : histories.get(id);
            if (id == null || row.type() == null || (before == null && !Facts.CAPABILITY_GROWN.equals(row.type()))) {
                continue;
            }
            Ecosystem.CapabilitySpec spec = specOf(payload, id);
            Ecosystem.CapabilitySpec latest = spec != null ? spec : before == null ? null : before.spec();
            switch (row.type()) {
                case Facts.CAPABILITY_GROWN -> histories.put(id, new History(id, text(payload, "reason"),
                        row.principal(), row.type(), latest, false));
                case Facts.CAPABILITY_KEPT -> histories.put(id, new History(id, before.need(), before.createdBy(),
                        row.type(), latest, false));
                case Facts.CAPABILITY_RETIRED -> histories.put(id, new History(id, before.need(), before.createdBy(),
                        row.type(), latest, before.reported()));
                case Facts.CAPABILITY_QUARANTINED -> {
                    if (UNSIGNED.equals(text(payload, "status"))) {
                        histories.put(id, new History(id, before.need(), before.createdBy(), before.last(),
                                before.spec(), true));
                    }
                }
                default -> {
                }
            }
        }
        return histories;
    }

    private static JsonNode payload(String json) {
        try {
            return json == null ? null : Json.tree(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    /** The spec a lifecycle fact carries when it is filed under {@code id} with a scope and a status; else null. */
    private static Ecosystem.CapabilitySpec specOf(JsonNode payload, String id) {
        JsonNode node = payload == null ? null : payload.get("spec");
        if (node == null || !node.isObject()) {
            return null;
        }
        try {
            Ecosystem.CapabilitySpec spec = Json.MAPPER.treeToValue(node, Ecosystem.CapabilitySpec.class);
            return spec != null && id.equals(spec.id()) && spec.scope() != null && spec.status() != null ? spec : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The spec with the need and principal of {@code history}; unchanged without one. */
    static Ecosystem.CapabilitySpec withOrigin(Ecosystem.CapabilitySpec spec, History history) {
        if (history == null) {
            return spec;
        }
        return new Ecosystem.CapabilitySpec(spec.id(), spec.version(), spec.name(), spec.description(), spec.scope(),
                spec.valueSchema(), spec.instructions(), spec.consumes(), spec.tags(), spec.status(), history.need(),
                history.createdBy(), spec.createdAt());
    }

    /**
     * Every spec in the genesis bucket that the kernel signed, filed under its own id, as stored; empty when the
     * bucket cannot be read.
     */
    private List<Ecosystem.CapabilitySpec> stored() {
        List<Ecosystem.CapabilitySpec> specs = new ArrayList<>();
        KeyValue kv;
        List<String> keys;
        try {
            kv = connection.keyValue(Subjects.KV_GENESIS);
            keys = kv.keys();
        } catch (Exception e) {
            log.debug("genesis bucket not readable: {}", e.getMessage());
            return specs;
        }
        for (String key : keys) {
            read(kv, key).ifPresent(specs::add);
        }
        return specs;
    }

    /**
     * The spec under {@code key} when the kernel signed it; empty for anything else or when it cannot be read. Each
     * revision of an entry that is skipped is warned about once.
     */
    private Optional<Ecosystem.CapabilitySpec> read(KeyValue kv, String key) {
        try {
            KeyValueEntry entry = kv.get(key);
            Optional<Ecosystem.CapabilitySpec> spec = entry == null ? Optional.empty()
                    : SignedSpec.read(key, entry.getValue(), kernel.publicKeyText());
            if (spec.isEmpty() && entry != null && entry.getValue() != null && firstReport(key, entry.getRevision())) {
                log.warn("skipping genesis entry {}: not a spec signed by this kernel", key);
            }
            return spec;
        } catch (Exception e) {
            log.debug("genesis entry {} not readable: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /** Whether this revision of the entry under {@code key} has not been warned about yet; records it if so. */
    boolean firstReport(String key, long revision) {
        if (reported.size() >= MAX_REPORTED) {
            reported.clear();
        }
        return reported.add(key + "@" + revision);
    }

    /**
     * Capability ids a new spec may not use: those live now, every key in the genesis bucket (a new spec would
     * overwrite the value there), and every id that ever had a {@code capability.grown} fact, retired, quarantined
     * and lost ones included, so a new capability never inherits an old one's record. A bucket or ledger that cannot
     * be read adds nothing.
     */
    public Set<String> takenIds() {
        Set<String> taken = new HashSet<>();
        registry.live().stream().map(CapabilityManifest::id).forEach(taken::add);
        try {
            taken.addAll(connection.keyValue(Subjects.KV_GENESIS).keys());
        } catch (Exception e) {
            log.debug("genesis bucket not readable: {}", e.getMessage());
        }
        history().ifPresent(h -> taken.addAll(h.keySet()));
        return taken;
    }

    /**
     * The spec as it is published: the same capability without the sentence the person asked with ({@code need})
     * and without who asked ({@code createdBy}).
     */
    static Ecosystem.CapabilitySpec published(Ecosystem.CapabilitySpec spec) {
        return new Ecosystem.CapabilitySpec(spec.id(), spec.version(), spec.name(), spec.description(), spec.scope(),
                spec.valueSchema(), spec.instructions(), spec.consumes(), spec.tags(), spec.status(), null, null,
                spec.createdAt());
    }

    /** Writes the published form of the spec ({@link #published}) under its id, signed with the kernel's key. */
    private void put(Ecosystem.CapabilitySpec spec) {
        try {
            connection.keyValue(Subjects.KV_GENESIS).put(spec.id(), SignedSpec.sign(published(spec), kernel).bytes());
        } catch (Exception e) {
            throw new IllegalStateException("cannot publish capability " + spec.id() + ": " + e.getMessage(), e);
        }
    }

    /** Takes the value under {@code id} out of the genesis bucket; a failure is logged and leaves it there. */
    private void unpublish(String id) {
        try {
            connection.keyValue(Subjects.KV_GENESIS).purge(id);
        } catch (Exception e) {
            log.warn("could not take capability {} out of the genesis bucket: {}", id, e.getMessage());
        }
    }

    /** The text of a goal, for questions about growing a capability for it; null for no goal or an unknown one. */
    public String goalText(String goalId) {
        return goalId == null ? null : ledger.load(goalId).map(GoalState::text).orElse(null);
    }
}
