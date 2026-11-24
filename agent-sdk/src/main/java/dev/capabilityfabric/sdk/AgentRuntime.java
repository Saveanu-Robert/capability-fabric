package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.Admission;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.CapabilityRequest;
import dev.capabilityfabric.protocol.CapabilityResult;
import dev.capabilityfabric.protocol.ContractNet;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Envelope;
import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Proposal;
import dev.capabilityfabric.protocol.Reply;
import dev.capabilityfabric.protocol.SchemaValidator;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.SignedManifest;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.model.StructuredModel;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Makes a process a member of the fabric. For every handler it:
 * <ol>
 *   <li>validates the manifest and fills in provider and instance identity;</li>
 *   <li>subscribes to the capability subject (queue group), its instance subject and its CFP subject;</li>
 *   <li>accepts calls signed by the kernel only, and opens those the kernel sealed for this instance;</li>
 *   <li>registers the manifest with the kernel and renews the lease every heartbeat: a signed request on
 *   {@link Subjects#REGISTRY_HEARTBEAT} carrying every entry, whose answer also says where this process's admission
 *   stands;</li>
 *   <li>for reactive handlers, follows the kernel's signals (which name a goal and a fact, never the fact's data)
 *   with a plain subscription, and turns reactions into proposals. Agents have no JetStream rights: a signal sent
 *   while the process was disconnected is not delivered again (the agents' periodic sweeps cover that).</li>
 * </ol>
 * Replies (results and bids) go only to the kernel's reply inbox ({@link Subjects#INBOX_KERNEL}), since calls and calls
 * for proposals come from the kernel alone; a request naming any other reply subject is dropped unanswered. A request
 * that names a reply seal key gets its result sealed for it. A call the kernel sends again with the same request id
 * runs once ({@link Repeats}). A capability withdrawn while the process runs, and on shutdown every capability, is
 * withdrawn with a signed request on {@link Subjects#REGISTRY_WITHDRAW} and leaves the kernel's registry at once; calls
 * still running after a short grace are answered with an {@code ERROR} result ({@link #STOPPED}), so the kernel can
 * turn to another instance at once. A process that dies without withdrawing leaves when its lease ends
 * ({@code fabric.agent.ttl}, 15 s by default).
 */
public class AgentRuntime implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    /** The error of a call this process stopped before it finished; the kernel may try another instance. */
    public static final String STOPPED = "stopped: the provider shut down before it finished this call";
    /** How long stop() lets running calls finish before it answers them with {@link #STOPPED}. */
    static final Duration STOP_GRACE = Duration.ofSeconds(2);

    private final Connection connection;
    private final FabricProperties properties;
    private final List<CapabilityHandler> handlers;
    private final List<ReactiveHandler> reactiveHandlers;
    private final FabricClient fabric;
    private final StructuredModel model;
    private final String provider;
    private final String instanceId;
    private final FabricSecurity security;
    private final RequestVerifier verifier;
    /**
     * Held while a heartbeat or a withdrawal is sent and answered, so a lease renewal running at the same time can
     * never put back an entry that was just withdrawn.
     */
    private final Object leases = new Object();
    /** Whether the process has joined the fabric (heartbeats run). */
    private volatile boolean joined;
    /** How many entries the kernel listed at the last heartbeat; nothing to withdraw while it is 0. */
    private volatile int listed;
    /** What the last heartbeat answered, to log only what changed. */
    private volatile String lastAnswer;

    /**
     * The advertised manifests by registry key, in the order they were first advertised (the handlers given at the
     * start, then those registered later). Heartbeats carry them in this order and the kernel looks at the first
     * entries of a heartbeat only ({@code CapabilityRegistry.MAX_ENTRIES_PER_KEY}), so a capability added later can
     * never push out one served from the start. Read it through {@link #advertisedNow()}.
     */
    private final Map<String, CapabilityManifest> advertised = Collections.synchronizedMap(new LinkedHashMap<>());
    /** The fact each reactive handler reacts to next, per goal (see {@link #schedule}). */
    private final Map<String, Pending> debounced = new ConcurrentHashMap<>();
    private final Map<String, List<Subscription>> subscriptions = new ConcurrentHashMap<>();
    private ExecutorService workers;
    private ScheduledExecutorService scheduler;
    private Dispatcher dispatcher;
    /** Capability calls being worked on now, so stop() can answer those still running. */
    private final Set<Call> calls = ConcurrentHashMap.newKeySet();
    /** The calls being worked on and the results sent lately, by caller, capability and request id. */
    private final Repeats repeats = new Repeats(System::nanoTime);
    private volatile boolean running;

    public AgentRuntime(Connection connection, FabricProperties properties, List<CapabilityHandler> handlers,
                        List<ReactiveHandler> reactiveHandlers, FabricClient fabric, StructuredModel model,
                        String provider) {
        this(connection, properties, handlers, reactiveHandlers, fabric, model, provider, fabric.security(),
                (message, subject) -> fabric.security().refuseUnlessKernelRequest(message, subject));
    }

    public AgentRuntime(Connection connection, FabricProperties properties, List<CapabilityHandler> handlers,
                        List<ReactiveHandler> reactiveHandlers, FabricClient fabric, StructuredModel model,
                        String provider, FabricSecurity security, RequestVerifier verifier) {
        this.security = security;
        this.verifier = verifier;
        this.connection = connection;
        this.properties = properties;
        this.handlers = List.copyOf(handlers);
        this.reactiveHandlers = List.copyOf(reactiveHandlers);
        this.fabric = fabric;
        this.model = model;
        this.provider = provider;
        this.instanceId = CapabilityManifest.instancePrefix(provider) + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * This process's instance id: its provider's prefix ({@link CapabilityManifest#instancePrefix}) and eight random
     * characters.
     */
    public String instanceId() {
        return instanceId;
    }

    /** The manifests advertised now, with provider and instance filled in, in the order they were advertised. */
    public List<CapabilityManifest> manifests() {
        return advertisedNow();
    }

    /** A copy of the advertised manifests, in the order they were first advertised. */
    private List<CapabilityManifest> advertisedNow() {
        synchronized (advertised) {
            return List.copyOf(advertised.values());
        }
    }

    @Override
    public void start() {
        workers = Executors.newVirtualThreadPerTaskExecutor();
        scheduler = Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("agent-runtime-", 0).factory());
        dispatcher = connection.createDispatcher();

        for (CapabilityHandler handler : handlers) {
            serveHandler(handler);
        }
        for (ReactiveHandler handler : reactiveHandlers) {
            CapabilityManifest manifest = complete(handler.manifest());
            if (manifest.mode() != CapabilityManifest.Mode.REACTIVE || manifest.triggers().isEmpty()) {
                throw new IllegalStateException(manifest.id() + ": reactive handlers need mode REACTIVE and triggers");
            }
            advertised.put(manifest.registryKey(), manifest);
            log.info("reacting as {} to {} as {}", manifest.id(), manifest.triggers(), instanceId);
        }
        running = true;
        scheduler.execute(this::joinFabric);
    }

    private CapabilityManifest serveHandler(CapabilityHandler handler) {
        CapabilityManifest manifest = complete(handler.manifest());
        List<Subscription> subs = List.of(
                dispatcher.subscribe(manifest.subject(), Subjects.queueGroup(manifest.id(), manifest.major()),
                        msg -> workers.submit(() -> serve(handler, manifest, msg))),
                dispatcher.subscribe(manifest.instanceSubject(), msg -> workers.submit(() -> serve(handler, manifest, msg))),
                dispatcher.subscribe(Subjects.cfp(manifest.id()), msg -> workers.submit(() -> bid(handler, manifest, msg))));
        subscriptions.put(manifest.registryKey(), subs);
        advertised.put(manifest.registryKey(), manifest);
        log.info("serving {} v{} on {} as {}", manifest.id(), manifest.version(), manifest.subject(), instanceId);
        return manifest;
    }

    /**
     * Adds a capability while the process runs (used by hosts of capabilities defined as data). It is registered
     * at once (the kernel asks the owner for a widened admission when needed) and leased like every other capability.
     */
    public synchronized CapabilityManifest register(CapabilityHandler handler) {
        if (dispatcher == null) {
            throw new IllegalStateException("runtime not started");
        }
        CapabilityManifest manifest = serveHandler(handler);
        if (joined) {
            heartbeat();
        }
        return manifest;
    }

    /** Withdraws a capability while the process keeps running; it leaves the kernel's registry at once. */
    public synchronized boolean unregister(String capabilityId) {
        String key = capabilityId + "." + instanceId;
        List<Subscription> subs = subscriptions.remove(key);
        if (subs != null) {
            subs.forEach(dispatcher::unsubscribe);
        }
        CapabilityManifest removed;
        synchronized (leases) {
            removed = advertised.remove(key);
            if (joined && removed != null) {
                withdraw(List.of(removed));
            }
        }
        if (removed != null) {
            log.info("withdrew {} from {}", capabilityId, instanceId);
        }
        return removed != null;
    }

    /** How long one registration request waits for the kernel's answer. */
    private Duration registrationTimeout() {
        Duration heartbeat = properties.agent().heartbeat();
        return heartbeat.compareTo(Duration.ofSeconds(1)) < 0 ? Duration.ofSeconds(1)
                : heartbeat.compareTo(Duration.ofSeconds(5)) > 0 ? Duration.ofSeconds(5) : heartbeat;
    }

    /** Asks the kernel to withdraw these entries (called while holding the lease lock). */
    private void withdraw(List<CapabilityManifest> manifests) {
        if (listed == 0) {
            return; // the kernel lists none of them
        }
        String grant = security.grant().map(Grant::encode).orElse(null);
        Reply reply = fabric.request(Subjects.REGISTRY_WITHDRAW, new KernelApi.Registration(manifests.stream()
                .map(m -> registryEntry(m, grant)).toList()), registrationTimeout());
        if (!reply.ok()) {
            log.warn("could not withdraw {}: {}", manifests.stream().map(CapabilityManifest::id).toList(),
                    reply.error());
        }
    }

    private CapabilityManifest complete(CapabilityManifest declared) {
        int ttl = (int) Math.max(3, properties.agent().ttl().toSeconds());
        CapabilityManifest manifest = declared.withInstance(provider, instanceId, Instant.now(), ttl);
        if (manifest.kind() == CapabilityManifest.Kind.AI && model != null) {
            manifest = manifest.withModel(model.chatModel());
        }
        List<String> errors = SchemaValidator.validateBundled("manifest", Json.toTree(manifest));
        if (!errors.isEmpty()) {
            throw new IllegalStateException("invalid manifest for " + declared.id() + ": " + errors);
        }
        for (String token : manifest.produces()) {
            if (!Vocabulary.isValidToken(token)) {
                throw new IllegalStateException(declared.id() + " produces unknown vocabulary token " + token);
            }
            if (Vocabulary.isTaskAnnotation(token) || Vocabulary.isGoalAnnotation(token)) {
                JsonNode schema = manifest.annotationSchemas().get(Vocabulary.annotationKey(token));
                if (schema == null || !SchemaValidator.isSchema(schema)) {
                    throw new IllegalStateException(declared.id() + " must declare a JSON Schema for annotation " + token);
                }
            }
        }
        return manifest;
    }

    /**
     * Joins the fabric: asks for admission once with everything this process offers (see {@link #needsAdmission}),
     * then registers and renews its entries every heartbeat.
     */
    private void joinFabric() {
        if (!running) {
            return;
        }
        try {
            askAdmission();
        } catch (RuntimeException e) {
            log.warn("admission request failed: {}", e.getMessage());
        }
        joined = true;
        heartbeat();
        long period = properties.agent().heartbeat().toMillis();
        try {
            scheduler.scheduleAtFixedRate(this::heartbeat, period, period, TimeUnit.MILLISECONDS);
            followSignals();
        } catch (RejectedExecutionException | IllegalStateException e) {
            if (running) {
                throw e;
            }
            // stopped while joining: the scheduler or the dispatcher is closed
        }
    }

    /** Whether this process must be admitted by the owner: security is on and its key is not the trusted kernel key. */
    private boolean needsAdmission() {
        return security.enabled() && !security.identity().publicKeyText().equals(security.kernelKey().orElse(null));
    }

    /** The first request for admission, with the contract (every manifest); heartbeats tell how it stands later. */
    private void askAdmission() {
        if (!needsAdmission()) {
            return;
        }
        Reply reply = fabric.request(Subjects.SECURITY_ADMISSION, new Admission.Request(provider,
                instanceId, advertisedNow()), registrationTimeout());
        if (reply.ok() && reply.data() != null) {
            admission(Json.MAPPER.treeToValue(reply.data(), Admission.Answer.class));
        }
    }

    /**
     * Renews every lease. Never throws: it runs on a fixed-rate schedule, which stops for good after an exception,
     * and an expired lease would silently remove this process from discovery.
     */
    void heartbeat() {
        try {
            if (!joined || !running) {
                return;
            }
            renewLeases(Instant.now());
        } catch (RuntimeException e) {
            log.warn("heartbeat failed: {}", e.getMessage());
        }
    }

    /** Sends one heartbeat with every entry and reads its answer. */
    private void renewLeases(Instant now) {
        Reply reply;
        synchronized (leases) {
            if (!running || advertised.isEmpty()) {
                return; // stop() withdraws the entries; renewing now would put them back
            }
            String grant = security.grant().map(Grant::encode).orElse(null);
            reply = fabric.request(Subjects.REGISTRY_HEARTBEAT, new KernelApi.Registration(advertisedNow().stream()
                    .map(m -> registryEntry(m.withHeartbeat(now), grant)).toList()), registrationTimeout());
            if (reply.ok() && reply.data() != null) {
                listed = reply.data().path("listed").asInt(0);
            }
        }
        registered(reply);
    }

    /**
     * Reads a heartbeat's answer: how many entries are listed, why others are not, and where the admission stands.
     * Only a change is logged.
     */
    private void registered(Reply reply) {
        String now = reply.ok() ? reply.data() == null ? "" : reply.data().path("status").asString("") + "|"
                + reply.data().path("message").asString("") + "|" + reply.data().path("listed").asInt(0) + "|"
                + reply.data().path("refused") : "error|" + reply.error();
        boolean changed = !now.equals(lastAnswer);
        lastAnswer = now;
        if (!reply.ok()) {
            if (changed) {
                log.warn("lease renewal failed for {}: {}", instanceId, reply.error());
            }
            return;
        }
        if (reply.data() == null) {
            return;
        }
        if (changed && reply.data().path("refused").isArray() && !reply.data().path("refused").isEmpty()) {
            log.warn("the kernel does not list some entries of {}: {}", instanceId, reply.data().path("refused"));
        }
        if (reply.data().hasNonNull("status") && needsAdmission()) {
            Admission.Answer answer;
            try {
                answer = Json.MAPPER.treeToValue(reply.data(), Admission.Answer.class);
            } catch (RuntimeException e) {
                log.warn("unreadable admission answer for {}", provider);
                return;
            }
            if (changed) {
                admission(answer);
            } else if (answer.status() == Admission.Status.ADMITTED) {
                adopt(answer, false);
            }
        }
    }

    /**
     * Signed, so nobody else can register in this provider's name or widen its contract; the seal key it carries
     * is covered by the signature. Without security the manifest goes unsigned.
     */
    private SignedManifest registryEntry(CapabilityManifest current, String grant) {
        return security.enabled()
                ? SignedManifest.sign(current, security.identity(), security.sealKeyText(), grant)
                : new SignedManifest(current, null, null, null, null);
    }

    /**
     * Acts on where the admission stands. Admitted: the grant is kept (and with it the kernel's key, unless pinned).
     * Waiting for the owner (also while an admitted process asks for more than its admission covers): the grant it
     * holds stays, since the kernel keeps listing what that grant covers. Refused or revoked: the grant is dropped.
     */
    private void admission(Admission.Answer answer) {
        if (answer == null || answer.status() == null) {
            log.warn("unreadable admission answer for {}", provider);
            return;
        }
        switch (answer.status()) {
            case ADMITTED -> adopt(answer, true);
            case PENDING -> log.info("{} waits for the owner to admit it: key {} ({})", provider, answer.fingerprint(),
                    answer.message());
            case REJECTED, REVOKED -> {
                security.revoked();
                log.warn("{} is not admitted: {}", provider, answer.message());
            }
        }
    }

    /** Keeps the grant an admitted answer carries; {@code tell} logs a grant that is new or ignored. */
    private void adopt(Admission.Answer answer, boolean tell) {
        Grant.decode(answer.grant()).ifPresentOrElse(g -> {
            boolean changed = security.grant().map(old -> !old.id().equals(g.id())).orElse(true);
            if (!changed) {
                return;
            }
            Optional<String> refused = security.admittedWith(g);
            if (refused.isPresent()) {
                if (tell) {
                    log.warn("{} ignores the grant it was sent: {}", provider, refused.get());
                }
            } else {
                log.info("{} admitted by the owner (key {})", provider, answer.fingerprint());
            }
        }, () -> {
            if (tell) {
                log.warn("{} was admitted without a readable grant", provider);
            }
        });
    }

    /**
     * Follows the kernel's signals for the fact types the reactive handlers trigger on, from now on, with a plain
     * subscription per signal subject (the broker keeps it across reconnects). Agents may not read the facts
     * themselves: a reaction asks the kernel for the goal context its manifest may see.
     */
    private void followSignals() {
        Set<String> subjects = signalSubjects(reactiveHandlers);
        for (String subject : subjects) {
            dispatcher.subscribe(subject, message -> workers.submit(() -> onSignal(message)));
        }
        if (!subjects.isEmpty()) {
            log.info("following signals {}", subjects);
        }
    }

    /** The signal subjects of every fact type these handlers trigger on ({@code signal.task.added}). */
    static Set<String> signalSubjects(List<ReactiveHandler> handlers) {
        Set<String> subjects = new LinkedHashSet<>();
        handlers.forEach(h -> h.manifest().triggers().forEach(t -> subjects.add(Subjects.signal(t))));
        return subjects;
    }

    /**
     * One signal: accepted only when the kernel signed it for the subject it arrived on. Its type (without the
     * {@code signal.} or {@code fact.} prefix) picks the handlers; its correlation id names the goal.
     */
    void onSignal(Message message) {
        if (security.enabled() && security.refuseUnlessKernel(message, message.getSubject()).isPresent()) {
            return; // only signals the kernel signed (the broker also forbids anyone else to publish them)
        }
        Envelope signal;
        try {
            signal = Envelope.fromBytes(message.getData());
        } catch (RuntimeException e) {
            log.warn("ignoring unreadable signal on {}", message.getSubject());
            return;
        }
        String type = factType(signal.shortType());
        String goalId = signal.correlationid();
        if (type == null || signal.principal() != null && signal.principal().startsWith("agent:" + provider + "/")) {
            return; // never react to our own changes
        }
        for (ReactiveHandler handler : reactiveHandlers) {
            if (!handler.manifest().triggers().contains(type) || goalId == null) {
                continue;
            }
            schedule(handler, signal, goalId, properties.agent().reactionDebounce().toMillis());
        }
    }

    /** The fact type a signal's short type names: {@code signal.task.added} and {@code fact.task.added} both. */
    static String factType(String shortType) {
        if (shortType == null) {
            return null;
        }
        for (String prefix : List.of(Subjects.SIGNAL_PREFIX, Subjects.FACT_PREFIX)) {
            if (shortType.startsWith(prefix)) {
                return shortType.substring(prefix.length());
            }
        }
        return shortType;
    }

    /** Reactions running now, per handler and goal: a second fact waits for the first reaction to finish. */
    private final Set<String> reacting = ConcurrentHashMap.newKeySet();

    /**
     * Reacts to {@code fact} after {@code delayMs}, unless another fact about the same goal comes first: then only the
     * later one is reacted to.
     */
    private void schedule(ReactiveHandler handler, Envelope fact, String goalId, long delayMs) {
        String key = handler.manifest().id() + "|" + goalId;
        Pending mine = new Pending();
        Pending previous = debounced.put(key, mine);
        if (previous != null) {
            previous.cancel();
        }
        arm(mine, handler, fact, goalId, key, delayMs);
    }

    /** One fact waiting to be reacted to, the pending entry of its handler and goal while no later fact replaced it. */
    private static final class Pending {
        private volatile ScheduledFuture<?> timer;

        void cancel() {
            ScheduledFuture<?> t = timer;
            if (t != null) {
                t.cancel(false);
            }
        }
    }

    /**
     * Reacts to {@code fact} after {@code delayMs} when {@code mine} is still the pending entry of {@code key} then; a
     * later fact has taken its place otherwise. While a reaction for the same key still runs it waits as long again.
     * A reaction removes only its own entry, so a fact that arrives while it starts keeps its entry and is still
     * reacted to.
     */
    private void arm(Pending mine, ReactiveHandler handler, Envelope fact, String goalId, String key, long delayMs) {
        mine.timer = scheduler.schedule(() -> workers.submit(() -> {
            if (debounced.get(key) != mine) {
                return;
            }
            if (!reacting.add(key)) {
                arm(mine, handler, fact, goalId, key, delayMs);
                return;
            }
            try {
                debounced.remove(key, mine);
                react(handler, fact, goalId);
            } finally {
                reacting.remove(key);
            }
        }), delayMs, TimeUnit.MILLISECONDS);
    }

    private void react(ReactiveHandler handler, Envelope fact, String goalId) {
        CapabilityManifest manifest = advertisedNow().stream()
                .filter(m -> m.id().equals(handler.manifest().id())).findFirst().orElseThrow();
        InvocationContext context = new InvocationContext(manifest, model, fabric);
        try {
            handler.onFact(fact, goalId, context).ifPresent(change -> {
                Effects effects = change.effects();
                Proposal proposal = new Proposal(UUID.randomUUID().toString(), manifest.id(), provider, instanceId,
                        change.goalId(), effects.taskUpdates(), effects.goalAnnotations(), change.reason(),
                        context.decisions(), fact.id());
                Reply reply = fabric.propose(proposal);
                log.info("proposal from {} for goal {}: {}", manifest.id(), goalId,
                        reply.ok() ? "accepted" : "rejected (" + reply.error() + ")");
            });
        } catch (Exception e) {
            log.warn("{} failed to react to {}: {}", manifest.id(), fact.type(), e.getMessage());
        }
    }

    /**
     * One capability call being worked on: the message it answers (the latest one of its request id, see
     * {@link Repeats}), the nonce of the kernel's stamp once it verified, the request id once read, and whether it
     * was answered.
     */
    static final class Call {
        volatile Message message;
        final CapabilityManifest manifest;
        final AtomicBoolean answered = new AtomicBoolean();
        volatile String nonce;
        volatile String requestId;
        /** The call's key in {@link Repeats} once it was claimed there. */
        volatile String repeatKey;

        Call(Message message, CapabilityManifest manifest) {
            this.message = message;
            this.manifest = manifest;
        }
    }

    /**
     * Whether a request may be served: it names no reply subject, or one under the kernel's reply inbox
     * ({@link Subjects#INBOX_KERNEL}), the only caller of capabilities. A request naming any other subject is dropped
     * before any work, so nobody can make this process publish into a stream or bucket, or into another role's
     * reply inbox, where a reply signed by this agent could pass for the answer to a request of someone else's.
     */
    static boolean answerable(Message message) {
        String to = message.getReplyTo();
        if (to == null || NatsSupport.isReplySubjectOf(to, Subjects.INBOX_KERNEL)) {
            return true;
        }
        log.debug("dropped a request on {}: its reply subject {} is not the kernel's reply inbox",
                message.getSubject(), to);
        return false;
    }

    private void serve(CapabilityHandler handler, CapabilityManifest manifest, Message message) {
        if (!answerable(message)) {
            return;
        }
        Call call = new Call(message, manifest);
        calls.add(call);
        try {
            CapabilityResult result = answer(handler, manifest, message, call);
            if (result != null) {
                repeats.finished(call, result);
                respond(call, result);
            }
        } finally {
            repeats.release(call);
            calls.remove(call);
        }
    }

    /**
     * Capability calls by caller, capability and request id ({@link #key}), so a call the kernel sends again (after
     * the broker said nobody was subscribed, or after a reconnect, always with the same request id and a fresh stamp)
     * runs once. While a call runs, another message of the same request is not run: the running call answers it
     * instead of the earlier one, since after a reconnect only a message received on the current connection can be
     * answered. A message of a request answered in the last {@link #MEMORY} gets that result again without running,
     * so an answer the broker lost is not lost for good. At most {@link #MAX_RESULTS} results are kept, the oldest
     * forgotten first. Only verified calls whose request id was read get here, so nobody else can claim a request id.
     */
    static final class Repeats {

        /** How long a sent result answers repeats of its request. */
        static final Duration MEMORY = Duration.ofMinutes(2);
        /** At most this many sent results are kept. */
        static final int MAX_RESULTS = 128;

        /**
         * What a call's message is: one to run (neither field set), one the running call of its request answers
         * ({@code running}), or one of a request answered lately, with that {@code result}.
         */
        record Seen(boolean running, CapabilityResult result) {
        }

        private record Sent(CapabilityResult result, long at) {
        }

        private final LongSupplier nanos;
        private final Map<String, Call> working = new HashMap<>();
        private final LinkedHashMap<String, Sent> sent = new LinkedHashMap<>();

        Repeats(LongSupplier nanos) {
            this.nanos = nanos;
        }

        /** The key of a call: who signed it ({@code ""} unsigned), the capability and the request id. */
        static String key(Message message, CapabilityManifest manifest, String requestId) {
            String signer = FabricSecurity.stampOf(message).map(Security.Stamp::key).orElse("");
            return signer + "|" + manifest.id() + "|" + requestId;
        }

        /** Claims {@code key} for {@code call}, unless a call of that key runs or was answered lately. */
        synchronized Seen claim(String key, Call call) {
            forgetOld();
            Sent done = sent.get(key);
            if (done != null) {
                return new Seen(false, done.result());
            }
            Call running = working.get(key);
            if (running != null) {
                running.message = call.message;
                return new Seen(true, null);
            }
            working.put(key, call);
            call.repeatKey = key;
            return new Seen(false, null);
        }

        /** Keeps the result of a claimed call for its repeats; called before the result is sent. */
        synchronized void finished(Call call, CapabilityResult result) {
            String key = call.repeatKey;
            if (key != null && working.remove(key, call)) {
                sent.put(key, new Sent(result, nanos.getAsLong()));
                forgetOld();
            }
        }

        /** Lets go of a call's key however the call ended, so a call that left no result can be sent again. */
        synchronized void release(Call call) {
            if (call.repeatKey != null) {
                working.remove(call.repeatKey, call);
            }
        }

        private void forgetOld() {
            long now = nanos.getAsLong();
            var oldestFirst = sent.values().iterator();
            while (oldestFirst.hasNext()) {
                Sent s = oldestFirst.next();
                if (now - s.at() < MEMORY.toNanos() && sent.size() <= MAX_RESULTS) {
                    break;
                }
                oldestFirst.remove();
            }
        }

        synchronized int kept() {
            return sent.size();
        }
    }

    /** Sends a call's result once: a call stop() already answered gets nothing more. */
    private void respond(Call call, CapabilityResult result) {
        if (call.message.getReplyTo() == null || !call.answered.compareAndSet(false, true)) {
            return;
        }
        publishReply(call.message, resultScope(result.requestId(), call.nonce), Json.bytes(result));
    }

    /**
     * The scope a result is signed for, which the kernel matches to its call: {@code result:<request id>} when the
     * request's id was read; otherwise, for a request whose kernel stamp verified but whose body could not be
     * opened or read (or that was stopped before it was read), {@link #refusalScope} of that stamp's nonce. A request
     * whose stamp did not verify gets {@code result:null}, which matches no call: it may not come from the kernel,
     * and a matched refusal would let anyone end the kernel's call by sending its request again, altered.
     */
    static String resultScope(String requestId, String verifiedNonce) {
        if (requestId != null) {
            return "result:" + requestId;
        }
        return verifiedNonce != null ? refusalScope(verifiedNonce) : "result:null";
    }

    /**
     * The scope of a result for a request whose id could not be read: {@code refused:} and the nonce of the
     * kernel's stamp on the request, which only the kernel and this instance know belong to that call.
     */
    public static String refusalScope(String requestNonce) {
        return "refused:" + requestNonce;
    }

    /**
     * Publishes a reply signed for {@code scope}, sealed for the key the request names in
     * {@link Security#HEADER_REPLY_SEAL}, if any. A request naming something else than a seal key there gets no reply.
     */
    private void publishReply(Message request, String scope, byte[] body) {
        try {
            FabricSecurity.SignedReply reply = security.reply(scope, body, FabricSecurity.replySealOf(request));
            connection.publish(request.getReplyTo(), reply.headers(), reply.body());
        } catch (IllegalArgumentException e) {
            log.warn("no reply on {}: {}", request.getSubject(), e.getMessage());
        }
    }

    /**
     * The result for one capability call. The stamp is checked over the bytes as they arrived; a body the kernel
     * sealed for this instance is opened only afterwards. A call that fails either check is refused without
     * reaching the handler (see {@link #resultScope} for how the kernel can tell which call a refusal answers).
     * Served calls are also looked up in {@link Repeats}; there the answer is {@code null} for a repeat, which the
     * running call answers or which was answered here already.
     */
    CapabilityResult answer(CapabilityHandler handler, CapabilityManifest manifest, Message message) {
        return answer(handler, manifest, message, null);
    }

    private CapabilityResult answer(CapabilityHandler handler, CapabilityManifest manifest, Message message,
                                    Call call) {
        long start = System.nanoTime();
        CapabilityRequest request = null;
        InvocationContext context = new InvocationContext(manifest, model, fabric);
        CapabilityResult result;
        Optional<String> refused = verifier.refuse(message, message.getSubject());
        byte[] body = null;
        if (refused.isEmpty()) {
            if (call != null) {
                call.nonce = FabricSecurity.stampOf(message).map(Security.Stamp::nonce).orElse(null);
            }
            try {
                body = security.plaintext(message);
            } catch (IllegalArgumentException e) {
                refused = Optional.of("cannot open the sealed request: " + e.getMessage());
            }
        }
        if (refused.isPresent()) {
            log.warn("refused a request for {}: {}", manifest.id(), refused.get());
            return new CapabilityResult(null, manifest.id(), provider, instanceId, CapabilityResult.Status.ERROR,
                    Effects.none(), null, List.of(), "refused: " + refused.get(), 0);
        }
        try {
            request = Json.read(body, CapabilityRequest.class);
            if (call != null) {
                call.requestId = request.requestId();
                if (request.requestId() != null && repeated(message, manifest, request.requestId(), call)) {
                    return null;
                }
            }
            CapabilityOutcome outcome = handler.handle(request, context);
            Effects effects = outcome.effects() == null ? Effects.none() : outcome.effects();
            List<String> errors = SchemaValidator.validateBundled("effects", Json.toTree(effects));
            if (!errors.isEmpty()) {
                throw new IllegalStateException("effects violate the effects schema: " + errors);
            }
            result = new CapabilityResult(request.requestId(), manifest.id(), provider, instanceId,
                    CapabilityResult.Status.OK, effects, outcome.output(), context.decisions(), null, millis(start));
        } catch (Exception e) {
            log.warn("{} failed: {}", manifest.id(), e.getMessage());
            result = new CapabilityResult(request == null ? null : request.requestId(), manifest.id(), provider,
                    instanceId, CapabilityResult.Status.ERROR, Effects.none(), null, context.decisions(),
                    e.getClass().getSimpleName() + ": " + e.getMessage(), millis(start));
        }
        return result;
    }

    /**
     * Whether the call is a repeat of a request ({@link Repeats}) that needs no run: one already running, which
     * answers this message instead, or one answered lately, whose result is sent again here.
     */
    private boolean repeated(Message message, CapabilityManifest manifest, String requestId, Call call) {
        Repeats.Seen seen = repeats.claim(Repeats.key(message, manifest, requestId), call);
        if (seen.running()) {
            log.debug("{} already works on call {}; that run answers this message", manifest.id(), requestId);
            return true;
        }
        if (seen.result() != null) {
            if (message.getReplyTo() != null) {
                publishReply(message, resultScope(requestId, null), Json.bytes(seen.result()));
            }
            return true;
        }
        return false;
    }

    private void bid(CapabilityHandler handler, CapabilityManifest manifest, Message message) {
        try {
            if (!answerable(message) || verifier.refuse(message, message.getSubject()).isPresent()) {
                return; // calls for proposals come from the kernel only
            }
            ContractNet.CallForProposals cfp = Json.read(message.getData(), ContractNet.CallForProposals.class);
            handler.bid(cfp, manifest).ifPresent(bid -> {
                if (message.getReplyTo() != null) {
                    publishReply(message, "bid:" + cfp.cfpId(), Json.bytes(bid));
                }
            });
        } catch (RuntimeException e) {
            log.warn("bid for {} failed: {}", manifest.id(), e.getMessage());
        }
    }

    /** Answers every call still being worked on with {@link #STOPPED}; returns how many were answered. */
    int answerUnfinished() {
        int answered = 0;
        for (Call call : List.copyOf(calls)) {
            if (call.message.getReplyTo() == null || call.answered.get()) {
                continue;
            }
            respond(call, new CapabilityResult(call.requestId, call.manifest.id(), provider, instanceId,
                    CapabilityResult.Status.ERROR, Effects.none(), null, List.of(), STOPPED, 0));
            answered++;
        }
        return answered;
    }

    /**
     * Withdraws every entry before closing, so the kernel stops routing calls here at once. The scheduler is stopped
     * first and the withdrawal is sent under the lease lock, so no renewal still running can put an entry back. No
     * new call arrives once the dispatcher is closed; calls still running after {@link #STOP_GRACE} are answered
     * with {@link #STOPPED} and their workers interrupted.
     */
    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("lease renewal did not stop within 5 s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (leases) {
            if (joined && !advertised.isEmpty()) {
                try {
                    withdraw(advertisedNow());
                } catch (RuntimeException e) {
                    log.warn("could not withdraw the entries of {}: {}", instanceId, e.getMessage());
                }
            }
        }
        if (dispatcher != null) {
            connection.closeDispatcher(dispatcher);
        }
        if (workers != null) {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(STOP_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    int stopped = answerUnfinished();
                    if (stopped > 0) {
                        log.info("{} answered {} unfinished calls with an error", instanceId, stopped);
                    }
                    workers.shutdownNow();
                    workers.awaitTermination(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("{} withdrew {} capabilities", instanceId, advertised.size());
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Start late, stop early: advertise only when everything else is ready.
        return Integer.MAX_VALUE - 100;
    }

    private static long millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
