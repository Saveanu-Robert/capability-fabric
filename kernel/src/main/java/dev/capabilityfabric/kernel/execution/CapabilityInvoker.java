package dev.capabilityfabric.kernel.execution;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.ecosystem.Reputation;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.CapabilityRequest;
import dev.capabilityfabric.protocol.CapabilityResult;
import dev.capabilityfabric.protocol.ContractNet;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.AgentRuntime;
import dev.capabilityfabric.sdk.FabricSecurity;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Calls a capability by id. With one provider its first healthy instance gets the call; when several different
 * providers offer it, a contract-net round collects bids and the best bid wins the call. Every call is addressed to
 * one instance, and the context sent is minimised to what that instance may read ({@link #reads}): what it declared
 * it consumes or requires, and the annotations it produces itself.
 *
 * <p>Each call listens on its own reply subject and takes the first answer signed by the called instance's key for
 * that request; answers from anyone else are ignored. An instance that could not read the call (its stamp did not
 * verify, or the sealed body did not open) cannot know the request id, so it signs its refusal for the nonce of the
 * call's stamp ({@link #refusalScope}); such a refusal ends the call at once as a failure of the instance.
 *
 * <p>A "no responders" status says the broker found nobody subscribed on the instance's subject when a send arrived,
 * so that send was not delivered. This happens for a while after the broker restarts: an instance subscribes again
 * only once its own connection is re-established, which may be after the kernel's. The call is then sent again at the
 * next check, up to {@link #MAX_RESENDS} sends, with the same request id. The status carries no signature and anyone
 * who saw the request could send one, so on its own it never ends the call: the first signed answer to any send
 * still counts until the call's time runs out. It ends the wait early only once the registry also sees the instance
 * stop renewing its entry for the capability (unless the broker was away while the call was sent, when the instance
 * could not renew): such an instance is skipped for this call (and hidden by the registry until it renews its lease)
 * and another instance is tried. When the latest send still found nobody subscribed as the time runs out, the
 * instance was never reached and the call fails on the kernel's side.
 *
 * <p>While waiting, the registry is checked every {@link #POLL_INTERVAL}: when the lease of the instance's entry for
 * the called capability ends (it expired, or the instance withdrew that capability while it keeps offering others),
 * the wait ends and another instance is tried. Leases are always those of the (capability, instance) entry, never of
 * the instance as a whole. None of these, nor a timeout of an instance that stopped renewing that entry, counts
 * against the provider's standing. A lease is renewed by messages through the broker, so when it ends while the
 * kernel's connection to the broker is down, or within one lease of the call seeing that connection down or
 * re-established, the renewals may have been lost with the connection: the call then fails on the kernel's side
 * ({@link #LEASE_LOST}). Another instance is still tried at once, and the caller may call the same instance again.
 *
 * <p>A call still waiting when the kernel's connection to the broker is re-established may have lost its request or
 * its answer (an instance can answer only on the broker connection the call reached it on), so it is sent again with
 * the same request id, and the first signed answer to any send counts; when that cannot finish it, the call fails on
 * the kernel's side. A call that fails on the kernel's side ({@link Invocation#kernelSide()}) does not count against
 * the provider and lets the caller call the same instance again.
 */
@Component
public class CapabilityInvoker {

    private static final Logger log = LoggerFactory.getLogger(CapabilityInvoker.class);

    private final Connection connection;
    private final CapabilityRegistry registry;
    private final KernelProperties properties;
    private final Reputation reputation;

    private final FabricSecurity security;
    private volatile Duration pollInterval = POLL_INTERVAL;

    public CapabilityInvoker(Connection connection, CapabilityRegistry registry, KernelProperties properties,
                             Reputation reputation, FabricSecurity security) {
        this.security = security;
        this.connection = connection;
        this.registry = registry;
        this.properties = properties;
        this.reputation = reputation;
    }

    /** Checks the registry, and sends again a call that found nobody subscribed, this often (tests shorten it). */
    void pollInterval(Duration interval) {
        this.pollInterval = interval;
    }

    /**
     * The outcome of one call: the result the called instance signed (or {@code null}), that instance's manifest,
     * the bids of a contract-net round (empty without one) and why the call failed ({@code null} when it did not).
     */
    public record Invocation(CapabilityResult result, CapabilityManifest manifest, List<ContractNet.Bid> bids,
                             String failure) {
        public boolean ok() {
            return failure == null && result != null && result.ok();
        }

        /**
         * Why the call failed, on one line of at most {@link #MAX_REASON} characters ({@link #capped}): the failure,
         * or the status and error text of the result. The error text, an instance id named in the failure and the
         * bytes quoted from an unreadable answer come from the instance, which can make them as long as a message.
         */
        public String failureReason() {
            if (failure != null) {
                return capped(failure);
            }
            if (result == null) {
                return "no result";
            }
            return result.error() == null ? String.valueOf(result.status())
                    : result.status() + ": " + capped(result.error());
        }

        /** The instance that was called (the only one whose answer counts), so a retry can avoid it. */
        public String instanceId() {
            return manifest == null ? null : manifest.instanceId();
        }

        /**
         * Whether the call failed on the kernel's side (its connection to the broker failed or was re-established
         * while it waited, no send of the call reached the instance because nobody was subscribed on its subject, or
         * the instance's lease ended while renewals could have been lost with the connection): the instance is not
         * to blame and may get the call again.
         */
        public boolean kernelSide() {
            return failure != null && failure.startsWith(KERNEL_SIDE);
        }
    }

    /** The bytes and headers of one call as they go on the wire. */
    record Outgoing(byte[] body, Headers headers) {
    }

    /** Calls a live instance of {@code capabilityId} (major version {@code major}) other than the excluded ones. */
    public Invocation invoke(String goalId, String runId, int step, String capabilityId, int major,
                             GoalContext context, JsonNode params, Set<String> excludedInstances) {
        return invoke(goalId, runId, step, capabilityId, major, context, params, excludedInstances, m -> true);
    }

    /**
     * {@code compatible} restricts the call to instances that policy and the person's rules were checked against:
     * a provider declaring more side effects or more writes than the one evaluated never gets the call.
     */
    public Invocation invoke(String goalId, String runId, int step, String capabilityId, int major,
                             GoalContext context, JsonNode params, Set<String> excludedInstances,
                             Predicate<CapabilityManifest> compatible) {
        return invoke(goalId, runId, step, capabilityId, major, context, params, excludedInstances, compatible, true);
    }

    /** A call whose outcome leaves the provider's standing untouched (what-if replays such as the counterfactual). */
    public Invocation invokeQuietly(String goalId, String runId, int step, String capabilityId, int major,
                                    GoalContext context, JsonNode params, Set<String> excludedInstances) {
        return invoke(goalId, runId, step, capabilityId, major, context, params, excludedInstances, m -> true, false);
    }

    private Invocation invoke(String goalId, String runId, int step, String capabilityId, int major,
                              GoalContext context, JsonNode params, Set<String> excludedInstances,
                              Predicate<CapabilityManifest> compatible, boolean counts) {
        Set<String> skipped = new HashSet<>(excludedInstances);
        Invocation unanswered = null;
        while (true) {
            Optional<Choice> chosen = choose(capabilityId, major, goalId, context, skipped, compatible);
            if (chosen.isEmpty()) {
                return unanswered != null ? unanswered
                        : new Invocation(null, null, List.of(), "no live provider of " + capabilityId);
            }
            Invocation invocation = call(goalId, runId, step, capabilityId, major, context, params,
                    chosen.get().target(), chosen.get().bids(), counts);
            if (!NO_RESPONDERS.equals(invocation.failure()) && !leaseGone(invocation.failure())
                    && !stopped(invocation.result())) {
                return invocation;
            }
            // The instance stopped (nobody listens on its subject, its lease ended meanwhile, or it answered that it
            // shut down before finishing), or its lease ended while the kernel's connection was down. Try another
            // one; only this call skips it.
            skipped.add(chosen.get().target().instanceId());
            unanswered = invocation;
        }
    }

    /** The instance chosen for a call and the bids of the contract-net round that chose it (empty without one). */
    private record Choice(CapabilityManifest target, List<ContractNet.Bid> bids) {
    }

    /**
     * The instance that gets the call: healthy ones before quarantined ones; among several providers the best bid
     * of a contract-net round, by earned standing more than by claimed confidence.
     */
    private Optional<Choice> choose(String capabilityId, int major, String goalId, GoalContext context,
                                    Set<String> skipped, Predicate<CapabilityManifest> compatible) {
        List<CapabilityManifest> candidates = registry.instances(capabilityId, major).stream()
                .filter(m -> !skipped.contains(m.instanceId())).filter(compatible).toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // Quarantined providers get no work while a healthy one is available.
        List<CapabilityManifest> healthy = candidates.stream()
                .filter(m -> !reputation.quarantined(capabilityId, m.provider())).toList();
        List<CapabilityManifest> instances = healthy.isEmpty() ? candidates : healthy;
        CapabilityManifest target = instances.getFirst();
        List<ContractNet.Bid> bids = List.of();
        Set<String> providers = new HashSet<>();
        instances.forEach(m -> providers.add(m.provider()));
        if (providers.size() > 1) {
            bids = callForProposals(capabilityId, goalId, context == null ? 0 : context.tasks().size(), instances);
            Map<String, CapabilityManifest> byInstance = new HashMap<>();
            instances.forEach(m -> byInstance.putIfAbsent(m.instanceId(), m));
            Optional<ContractNet.Bid> best = bids.stream()
                    .filter(b -> byInstance.containsKey(b.instanceId()))
                    .max(Comparator.comparingDouble((ContractNet.Bid b) ->
                                    award(capabilityId, b, byInstance.get(b.instanceId())))
                            .thenComparing(ContractNet.Bid::instanceId, Comparator.reverseOrder()));
            if (best.isPresent()) {
                target = byInstance.get(best.get().instanceId());
            }
        }
        return Optional.of(new Choice(target, bids));
    }

    /**
     * The tokens a call to {@code target} may read: what it declared it consumes or requires, and every task or goal
     * annotation it produces. A capability always reads back its own annotations, so it can see what it wrote before
     * and correct or clear it; core fields it writes (an effort, a due date) are read only when it consumes them.
     */
    public static Set<String> reads(CapabilityManifest target) {
        Set<String> tokens = new LinkedHashSet<>(target.consumes());
        tokens.addAll(target.requires());
        target.produces().stream().filter(t -> Vocabulary.isTaskAnnotation(t) || Vocabulary.isGoalAnnotation(t))
                .forEach(tokens::add);
        return tokens;
    }

    /** One call to {@code target}, credited to or held against its provider when {@code counts}. */
    private Invocation call(String goalId, String runId, int step, String capabilityId, int major,
                            GoalContext context, JsonNode params, CapabilityManifest target, List<ContractNet.Bid> bids,
                            boolean counts) {
        // Always address one instance: the shared queue group could deliver to an excluded or quarantined one, and
        // a retry must know which instance failed.
        String subject = target.instanceSubject();
        GoalContext minimised = context == null ? null : context.minimise(reads(target));
        CapabilityRequest request = new CapabilityRequest(UUID.randomUUID().toString(), capabilityId, goalId, runId,
                step, minimised, params, "kernel");
        Duration timeout = properties.callTimeout()
                .plus(Duration.ofMillis(Math.min(600_000, target.cost().expectedLatencyMs() * 2)));
        Outcome outcome = call(subject, request, target, timeout);
        if (NO_RESPONDERS.equals(outcome.failure())) {
            registry.unreachable(target.instanceId(), Instant.now());
        } else if (counts && countsAgainst(outcome, target)) {
            observe(capabilityId, major, target.provider(), outcome.ok());
        }
        return new Invocation(outcome.result(), target, bids, outcome.failure());
    }

    /**
     * Whether an outcome says something about the provider: not when the kernel itself stopped waiting or the call
     * failed on the kernel's side (its connection to the broker was down or re-established, no send reached the
     * instance, or the instance's lease ended while its renewals may have been lost with the connection), not when
     * the lease of the instance's entry ended or the instance answered that it stopped, and not when the call timed
     * out after the instance stopped renewing (or withdrew) its entry for the capability (it was shutting down or no
     * longer offers it).
     */
    private boolean countsAgainst(Outcome outcome, CapabilityManifest target) {
        if (outcome.interrupted() || outcome.kernelSide() || outcome.leaseEnded() || stopped(outcome.result())) {
            return false;
        }
        return !(outcome.timedOut() && registry.silent(target.id(), target.instanceId(), Instant.now()));
    }

    /** An answer saying the provider shut down before it finished the call; it says nothing about its work. */
    static boolean stopped(CapabilityResult result) {
        return result != null && !result.ok() && AgentRuntime.STOPPED.equals(result.error());
    }

    /** What one call to one instance came to: the result it signed (or {@code null}) and why it failed, if it did. */
    record Outcome(CapabilityResult result, String failure) {
        boolean ok() {
            return failure == null && result != null && result.ok();
        }

        /** The kernel itself stopped waiting: nothing to credit or blame. */
        boolean interrupted() {
            return INTERRUPTED.equals(failure);
        }

        boolean timedOut() {
            return failure != null && failure.startsWith(TIMED_OUT);
        }

        /**
         * The lease of the instance's entry for the capability ended while the kernel waited: it went away or
         * withdrew the capability, which says nothing about its work.
         */
        boolean leaseEnded() {
            return LEASE_ENDED.equals(failure);
        }

        /**
         * The call failed on the kernel's side: it could not send the call or read its replies, its connection to
         * the broker was down or re-established while the call waited, no send reached the instance, or the lease
         * of the instance's entry ended when its renewals may have been lost with the connection.
         */
        boolean kernelSide() {
            return failure != null && failure.startsWith(KERNEL_SIDE);
        }
    }

    /** The failure of a call the kernel stopped waiting for (its thread was interrupted). */
    public static final String INTERRUPTED = "interrupted";
    /** The failure of a call whose instance had nobody subscribed on its subject and stopped renewing its entry. */
    static final String NO_RESPONDERS = "no responder";
    /** The failure of a call whose instance stopped offering the capability (lease ended or withdrawn) first. */
    static final String LEASE_ENDED = "the instance stopped offering the capability before it answered";
    /**
     * Why a call ends on the kernel's side when the lease of the instance's entry ended while the kernel's
     * connection to the broker was down, or within one lease of the call seeing it down or re-established: the
     * renewals that would have kept the lease may have been lost with the connection.
     */
    static final String LEASE_LOST = "the instance's lease ended while the connection to the broker was down or "
            + "had just been re-established";

    /** Whether a call ended because the lease of the instance's entry ended, whether or not the kernel is to blame. */
    static boolean leaseGone(String failure) {
        return LEASE_ENDED.equals(failure) || (KERNEL_SIDE + LEASE_LOST).equals(failure);
    }

    /** The start of the failure of a call the instance refused because it could not read it. */
    static final String REFUSED = "the instance refused the call";
    /**
     * How often a waiting call asks the registry whether the called instance is still there; a send that found
     * nobody subscribed is repeated after this long.
     */
    static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final String TIMED_OUT = "timed out after ";
    /** The start of the failure of a call that ended on the kernel's side; it says nothing about the instance. */
    static final String KERNEL_SIDE = "the call failed on the kernel's side: ";
    /** Why a call ends when the connection to the broker was re-established again after the call was sent again. */
    static final String RECONNECTED_AGAIN = "the connection to the broker was re-established again while the call "
            + "waited";
    /**
     * Why a call ends when its time runs out while the latest send still found nobody subscribed on the instance's
     * subject, usually because the instance has not subscribed again since its connection to the broker was
     * re-established.
     */
    static final String NOT_SUBSCRIBED_AGAIN = "the call never reached the instance: nobody was subscribed on its "
            + "subject";
    /** Why a call ends when its time runs out while the kernel is not connected to the broker. */
    static final String NOT_CONNECTED = "not connected to the broker when the call timed out";
    /**
     * At most this many sends of a call answered by "no responders", the first one included; the send after a
     * reconnect starts the count again.
     */
    static final int MAX_RESENDS = 10;

    /**
     * Sends one request to the target instance on a reply subject of its own and waits for the answer: the first
     * message signed by the instance's key for this request id, or a refusal it signed for the nonce of one of this
     * call's stamps. Messages signed by anyone else (another agent that saw the request and answered first) are
     * ignored, so they can neither stand in for the instance nor make its call fail. A signed answer that names
     * another instance or request is the instance's own fault.
     *
     * <p>A "no responders" status means the latest send found nobody subscribed on the instance's subject. The call
     * is then sent again at the next check ({@link #pollInterval}), with the same request id and a fresh stamp, up to
     * {@link #MAX_RESENDS} sends; resending never moves the call's deadline. The status is unsigned and anyone who
     * saw the request could send one, so it never ends the call by itself, and the first signed answer to any send
     * counts until the deadline. The wait ends early when the registry finds the instance's entry for the capability
     * silent (no renewal for half a lease) after the latest send found nobody, as an instance that stopped without
     * withdrawing; or when the lease of that entry ends. Silence is not asked about once a send of the call went out
     * on a connection made after it was published: the broker was away, so the instance could not renew meanwhile.
     *
     * <p>When the kernel's connection to the broker is re-established while a call waits (usually the broker
     * restarted), the request or its answer may have been lost, and an instance whose own connection was
     * re-established cannot answer at all: the broker lets it answer only on the connection the request reached it
     * on. Once such a reconnect is seen ({@link #connectionNumber}), the call is sent again with the same request id,
     * a fresh stamp and its full timeout, and the count of sends starts again. Another reconnect after that, a
     * timeout while the kernel is not connected, and a timeout while the latest send still found nobody subscribed
     * all end the call as a failure on the kernel's side, which does not count against the provider.
     *
     * <p>Each check notes whether the kernel's connection is down or was re-established since the latest send, and a
     * send the client held back counts as seen down. When the lease of the instance's entry ends while the
     * connection is down, or less than one lease ({@link #lease}) after the call last saw it down or re-established,
     * the call ends on the kernel's side ({@link #LEASE_LOST}); a lease ending later than that ends it as
     * {@link #LEASE_ENDED}.
     */
    private Outcome call(String subject, CapabilityRequest request, CapabilityManifest target, Duration timeout) {
        byte[] json = Json.bytes(request);
        Optional<String> sealKey = security.enabled() ? registry.sealKeyOf(target.instanceId()) : Optional.empty();
        Outgoing first;
        try {
            first = outgoing(subject, json, sealKey, security);
        } catch (IllegalArgumentException e) {
            return new Outcome(null, "cannot seal the call for " + target.instanceId() + ": " + e.getMessage());
        }
        String inbox;
        Subscription replies;
        try {
            inbox = connection.createInbox();
            replies = connection.subscribe(inbox);
        } catch (RuntimeException e) {
            return new Outcome(null, KERNEL_SIDE + e.getMessage());
        }
        // The nonces of the stamps the call went out with; none when security is off (no refusal can be matched).
        Set<String> nonces = new HashSet<>();
        try {
            Sent sent = send(subject, inbox, first, nonces);
            long sentAt = System.nanoTime();
            long deadline = sentAt + timeout.toNanos();
            // A send of this call went out on a connection made after it was published: the broker was away, so the
            // instance's entry may look silent though the instance is still there.
            boolean afterReconnect = sent.held();
            // The call was sent again because the connection was re-established while it waited.
            boolean resumed = false;
            // Whether the call saw the kernel's connection down or re-established, and when it last did (nanoTime).
            boolean outageSeen = sent.held();
            long outageSeenAt = System.nanoTime();
            // Sends since the first one, or since the send after the reconnect, that one included.
            int sends = 1;
            // The broker answered the latest send with "no responders": nobody was subscribed on the subject.
            boolean undelivered = false;
            while (true) {
                if (connectionNumber() > sent.connection()) {
                    if (resumed) {
                        return new Outcome(null, KERNEL_SIDE + RECONNECTED_AGAIN);
                    }
                    log.info("sending call {} to {} again: the connection to the broker was re-established",
                            request.requestId(), target.instanceId());
                    sent = send(subject, inbox, outgoing(subject, json, sealKey, security), nonces);
                    sentAt = System.nanoTime();
                    deadline = sentAt + timeout.toNanos();
                    afterReconnect = true;
                    resumed = true;
                    outageSeen = true;
                    outageSeenAt = sentAt;
                    sends = 1;
                    undelivered = false;
                } else if (undelivered && sends < MAX_RESENDS && System.nanoTime() - sentAt >= pollInterval.toNanos()) {
                    log.debug("sending call {} to {} again: nobody was subscribed on its subject", request.requestId(),
                            target.instanceId());
                    sent = send(subject, inbox, outgoing(subject, json, sealKey, security), nonces);
                    sentAt = System.nanoTime();
                    afterReconnect |= sent.held();
                    if (sent.held()) {
                        outageSeen = true;
                        outageSeenAt = sentAt;
                    }
                    sends++;
                    undelivered = false;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return timedOut(timeout, undelivered);
                }
                Message reply = replies.nextMessage(Duration.ofNanos(Math.min(left, pollInterval.toNanos())));
                if (reply != null && reply.isStatusMessage()) {
                    undelivered |= reply.getStatus() != null && reply.getStatus().getCode() == 503;
                } else if (reply != null && signedBy(reply, "result:" + request.requestId(), target)) {
                    return answer(reply.getData(), request, target);
                } else if (reply != null && nonces.stream().anyMatch(n -> signedBy(reply, refusalScope(n), target))) {
                    return refusal(reply.getData(), request, target);
                } else if (reply != null) {
                    log.debug("ignoring an answer to {} not signed by {}", request.requestId(), target.instanceId());
                }
                // Nothing that counts arrived: is the instance still there to answer?
                long now = System.nanoTime();
                if (connection.getStatus() != Connection.Status.CONNECTED || connectionNumber() > sent.connection()) {
                    outageSeen = true;
                    outageSeenAt = now;
                }
                boolean outage = outageSeen && now - outageSeenAt < lease(target).toNanos();
                Optional<String> gone = gone(target, undelivered && !afterReconnect, outage, Instant.now());
                if (gone.isPresent()) {
                    return new Outcome(null, gone.get());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(null, INTERRUPTED);
        } catch (RuntimeException e) {
            return new Outcome(null, KERNEL_SIDE + e.getMessage());
        } finally {
            try {
                replies.unsubscribe();
            } catch (RuntimeException ignored) {
                // The connection is closing; the subscription goes with it.
            }
        }
    }

    /**
     * One send of a call: the number of the broker connection it goes out on ({@link #connectionNumber}) and whether
     * the client held it back because the kernel was not connected, so it goes out once that next connection is made.
     */
    private record Sent(long connection, boolean held) {
    }

    /** Publishes one send of a call and keeps the nonce of its stamp. */
    private Sent send(String subject, String inbox, Outgoing outgoing, Set<String> nonces) {
        // The number is read before the status: a reconnect in between makes the call go out again, never be lost.
        long number = connectionNumber();
        boolean connected = connection.getStatus() == Connection.Status.CONNECTED;
        connection.publish(subject, inbox, outgoing.headers(), outgoing.body());
        String nonce = outgoing.headers().getFirst(Security.HEADER_NONCE);
        if (nonce != null) {
            nonces.add(nonce);
        }
        return connected ? new Sent(number, false) : new Sent(number + 1, true);
    }

    /**
     * Which connection to the broker the kernel is on: how many times the client has re-established it. A higher
     * number than the one a call went out on means the call may have been lost with that connection.
     */
    private long connectionNumber() {
        return connection.getStatistics().getReconnects();
    }

    /**
     * A call whose time ran out: a failure on the kernel's side when the kernel is not connected to the broker (no
     * answer could arrive) or the latest send found nobody subscribed on the instance's subject; otherwise a timeout.
     */
    private Outcome timedOut(Duration timeout, boolean undelivered) {
        if (connection.getStatus() != Connection.Status.CONNECTED) {
            return new Outcome(null, KERNEL_SIDE + NOT_CONNECTED);
        }
        if (undelivered) {
            return new Outcome(null, KERNEL_SIDE + NOT_SUBSCRIBED_AGAIN);
        }
        return new Outcome(null, TIMED_OUT + timeout.toSeconds() + " s");
    }

    /** The answer the instance signed for the request: its result, unless it names another instance or request. */
    private static Outcome answer(byte[] data, CapabilityRequest request, CapabilityManifest target) {
        CapabilityResult result;
        try {
            result = Json.read(data, CapabilityResult.class);
        } catch (RuntimeException e) {
            return new Outcome(null, "unreadable answer: " + e.getMessage());
        }
        return answerProblem(result, request, target).map(p -> new Outcome(null, p))
                .orElseGet(() -> new Outcome(result, null));
    }

    /**
     * The scope an instance signs its answer for when it could not read the call and so cannot know the request
     * id: the nonce of the call's own stamp, which only this call carries. The agent runtime defines it, so both
     * sides use the same text.
     */
    static String refusalScope(String nonce) {
        return AgentRuntime.refusalScope(nonce);
    }

    /** At most this many characters of a text an instance controls are kept in a failure ({@link #capped}). */
    public static final int MAX_REASON = 200;

    /**
     * {@code text} on one line, runs of white space as one space, cut to at most {@link #MAX_REASON} characters with
     * "..." marking the cut; {@code null} stays {@code null}. Step details and plan notes go into facts, and a fact
     * must fit in one broker message, so text an instance controls is capped before it gets there.
     */
    public static String capped(String text) {
        if (text == null) {
            return null;
        }
        StringBuilder line = new StringBuilder(Math.min(text.length(), MAX_REASON + 1));
        boolean space = false;
        for (int i = 0; i < text.length() && line.length() <= MAX_REASON; i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                space = !line.isEmpty();
            } else {
                if (space) {
                    line.append(' ');
                    space = false;
                }
                line.append(c);
            }
        }
        if (line.length() <= MAX_REASON) {
            return line.toString();
        }
        // Never cut between the two halves of a character outside the basic plane.
        int end = Character.isHighSurrogate(line.charAt(MAX_REASON - 1)) ? MAX_REASON - 1 : MAX_REASON;
        return line.substring(0, end) + "...";
    }

    /**
     * What an answer the instance signed for the call's nonce means: it refused the call (a failure of the instance,
     * with at most {@link #MAX_REASON} characters of its reason on one line; nothing else in the answer is used) or,
     * when it says the instance stopped before reading the call, the same as any {@link AgentRuntime#STOPPED} answer,
     * so another instance is tried and nobody is blamed.
     */
    static Outcome refusal(byte[] data, CapabilityRequest request, CapabilityManifest target) {
        CapabilityResult result;
        try {
            result = Json.read(data, CapabilityResult.class);
        } catch (RuntimeException e) {
            result = null;
        }
        if (stopped(result)) {
            return new Outcome(new CapabilityResult(request.requestId(), target.id(), target.provider(),
                    target.instanceId(), CapabilityResult.Status.ERROR, Effects.none(), null,
                    List.of(), result.error(), result.durationMs()), null);
        }
        // One line: the reason ends up in the step's detail.
        String reason = result == null || result.error() == null ? null
                : capped(result.error().replaceFirst("^\\s*refused:\\s*", ""));
        if (reason == null || reason.isEmpty()) {
            return new Outcome(null, REFUSED);
        }
        return new Outcome(null, REFUSED + ": " + reason);
    }

    /**
     * Why a waiting call should stop waiting for {@code target}: the lease of its entry for the capability ended
     * (expired or withdrawn, whatever the instance's other entries do), or, when {@code noResponders} (the latest
     * send found nobody subscribed and silence is evidence), that entry has stopped being renewed. Empty while the
     * instance still offers the capability. A lease that ends during an {@code outage} (the kernel's connection is
     * down or was down or re-established within the last lease) ends the call on the kernel's side.
     */
    private Optional<String> gone(CapabilityManifest target, boolean noResponders, boolean outage, Instant now) {
        if (registry.leaseEnded(target.id(), target.instanceId(), now)) {
            return Optional.of(outage ? KERNEL_SIDE + LEASE_LOST : LEASE_ENDED);
        }
        if (noResponders && registry.silent(target.id(), target.instanceId(), now)) {
            return Optional.of(NO_RESPONDERS);
        }
        return Optional.empty();
    }

    /**
     * How long the instance's entry is leased for (its {@code ttl}, between 1 and
     * {@link CapabilityRegistry#MAX_TTL_SECONDS} seconds, as the registry counts it). A live instance renews its entry
     * several times per lease, so one lease after the broker is back is enough for a renewal to arrive.
     */
    static Duration lease(CapabilityManifest target) {
        return Duration.ofSeconds(Math.clamp(target.ttlSeconds(), 1, CapabilityRegistry.MAX_TTL_SECONDS));
    }

    /** Why an answer does not count for this call: it names another instance or another request. */
    static Optional<String> answerProblem(CapabilityResult result, CapabilityRequest request, CapabilityManifest target) {
        if (!target.instanceId().equals(result.instanceId())) {
            return Optional.of("the answer names instance " + result.instanceId() + ", not " + target.instanceId());
        }
        if (!request.requestId().equals(result.requestId())) {
            return Optional.of("the answer is for another request");
        }
        return Optional.empty();
    }

    /**
     * The request as sent: sealed for the instance when it published a seal key (the subject is the sealing context
     * and {@code Fabric-Sealed} names the scheme), then signed over exactly the bytes on the wire.
     */
    static Outgoing outgoing(String subject, byte[] json, Optional<String> sealKey, FabricSecurity security) {
        byte[] body = sealKey.map(k -> Security.seal(k, subject, json)).orElse(json);
        Headers headers = security.headers(subject, body);
        if (sealKey.isPresent()) {
            headers.put(Security.HEADER_SEALED, Security.SEAL_SCHEME);
        }
        return new Outgoing(body, headers);
    }

    /**
     * Earned standing outweighs claimed confidence. The provider is that of {@code bidder}, the registry entry the
     * call chose among, never the one the bid names, so a bidder cannot borrow another provider's standing;
     * confidence is clamped.
     */
    private double award(String capabilityId, ContractNet.Bid bid, CapabilityManifest bidder) {
        double confidence = Math.max(0, Math.min(1, bid.confidence()));
        return 0.3 * confidence + 0.7 * reputation.score(capabilityId, bidder.provider())
                - Math.min(Math.max(0, bid.expectedLatencyMs()), 120_000) / 1_200_000.0;
    }

    /** With security on, a message counts only when signed by the key the instance was admitted with. */
    private boolean signedBy(Message message, String scope, CapabilityManifest instance) {
        if (!security.enabled()) {
            return true;
        }
        var stamp = FabricSecurity.stampOf(message);
        String expected = registry.keyOf(instance.instanceId()).orElse(null);
        return stamp.isPresent() && expected != null && expected.equals(stamp.get().key())
                && stamp.get().verifies(scope, message.getData());
    }

    /** Effects a provider returned were invalid: that counts against it like a failed call. */
    public void rejected(CapabilityManifest responder) {
        reputation.recordRejectedEffects(responder.id(), responder.provider());
        boolean alternative = registry.instances(responder.id(), responder.major()).stream()
                .anyMatch(m -> !m.provider().equals(responder.provider())
                        && !reputation.quarantined(responder.id(), m.provider()));
        reputation.considerQuarantine(responder.id(), responder.provider(), alternative);
    }

    private void observe(String capabilityId, int major, String provider, boolean ok) {
        reputation.recordCall(capabilityId, provider, ok);
        if (!ok) {
            boolean alternative = registry.instances(capabilityId, major).stream()
                    .anyMatch(m -> !m.provider().equals(provider) && !reputation.quarantined(capabilityId, m.provider()));
            reputation.considerQuarantine(capabilityId, provider, alternative);
        }
    }

    /** Contract net: broadcast a call for proposals and collect bids for a short window. */
    List<ContractNet.Bid> callForProposals(String capabilityId, String goalId, int taskCount,
                                           List<CapabilityManifest> instances) {
        String inbox = connection.createInbox();
        Subscription subscription = connection.subscribe(inbox);
        List<ContractNet.Bid> bids = new ArrayList<>();
        try {
            ContractNet.CallForProposals cfp = new ContractNet.CallForProposals(UUID.randomUUID().toString(),
                    capabilityId, goalId, taskCount);
            byte[] cfpBody = Json.bytes(cfp);
            connection.publish(Subjects.cfp(capabilityId), inbox, security.headers(Subjects.cfp(capabilityId), cfpBody),
                    cfpBody);
            long deadline = System.nanoTime() + properties.cfpWindow().toNanos();
            Set<String> eligible = new HashSet<>();
            instances.forEach(m -> eligible.add(m.instanceId()));
            // Only bids from eligible instances count towards "everyone answered".
            while (bids.size() < eligible.size()) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    break;
                }
                Message message = subscription.nextMessage(Duration.ofNanos(left));
                if (message == null) {
                    break;
                }
                try {
                    ContractNet.Bid bid = Json.read(message.getData(), ContractNet.Bid.class);
                    CapabilityManifest bidder = instances.stream().filter(m -> m.instanceId().equals(bid.instanceId()))
                            .findFirst().orElse(null);
                    if (bidder != null && signedBy(message, "bid:" + cfp.cfpId(), bidder)
                            && cfp.cfpId().equals(bid.cfpId()) && eligible.contains(bid.instanceId())
                            && bids.stream().noneMatch(b -> b.instanceId().equals(bid.instanceId()))) {
                        bids.add(bid);
                    }
                } catch (RuntimeException malformed) {
                    // A malformed bid is ignored; the window stays open for the others.
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            subscription.unsubscribe();
        }
        return bids;
    }
}
