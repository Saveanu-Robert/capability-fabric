package dev.capabilityfabric.kernel.api;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.constitution.ConstitutionService;
import dev.capabilityfabric.kernel.conversation.ActionExecutor;
import dev.capabilityfabric.kernel.conversation.ConversationService;
import dev.capabilityfabric.kernel.conversation.SystemControl;
import dev.capabilityfabric.kernel.decisions.DecisionLog;
import dev.capabilityfabric.kernel.ecosystem.Genesis;
import dev.capabilityfabric.kernel.ecosystem.Reputation;
import dev.capabilityfabric.kernel.execution.RunCoordinator;
import dev.capabilityfabric.kernel.inbox.InboxService;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.memory.EpisodicMemory;
import dev.capabilityfabric.kernel.person.ProfileService;
import dev.capabilityfabric.kernel.policy.PolicyGuard;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.kernel.security.GuestView;
import dev.capabilityfabric.kernel.security.Quota;
import dev.capabilityfabric.kernel.security.RefusalBudget;
import dev.capabilityfabric.kernel.security.SecurityService;
import dev.capabilityfabric.protocol.Admission;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Proposal;
import dev.capabilityfabric.protocol.Reply;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.SignedManifest;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.FabricSecurity;
import dev.capabilityfabric.sdk.NatsSupport;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The kernel's interface on the broker. People's devices send sentences ({@code intent.submit}), typed actions
 * ({@code intent.execute}), answers to confirmation questions and to inbox messages, reads of the inbox and of
 * goals, the emergency stop and resume (both work without any model), and pairing and joining requests. Providers
 * register their capabilities (heartbeats and withdrawals), and use the read model, the memory of finished tasks,
 * recorded decisions (replay), the grown capabilities' specs (for their host) and proposals, each within a quota per
 * signing key ({@link #AGENT_REQUESTS_PER_MINUTE}).
 *
 * <p>What the kernel takes in is bounded. Each subscription (a subject, and its gateway subject on its own) has at
 * most {@link #IN_WORK} requests in work at a time, by who may use the subject; a request beyond that is dropped
 * unanswered before any work ({@link RequestIntake}), so a flood holds neither threads nor the memory of its messages,
 * and floods on one subject leave the others their room. The dispatcher holds at most {@link #PENDING_MESSAGES}
 * messages and {@link #PENDING_BYTES} bytes it has not handed on yet. A key the kernel does not know uses the subject's
 * budget for strangers before the database is asked who it is ({@link SecurityService#verify}).
 *
 * <p>The subjects the gateway may forward ({@link #GATEWAY_SUBJECTS}) are served twice: on the plain subject for the
 * machine's own broker and on {@code gw.<subject>} ({@link Subjects#viaGateway}), which the broker lets only the
 * gateway publish. A request "came through the gateway" exactly when it arrived on a {@code gw.} subject; no header
 * says so. Signatures, claims and replies always use the plain subject, which is what the device signed.
 *
 * <p>A reply goes only to the request's own reply subject, and only when that subject is in a reply inbox the sender
 * may use ({@link #replyPrefixes}): the gateway's for a {@code gw.} subject, the agents' for an agent, people's for a
 * device, and for a key the kernel does not know the inbox of the role that publishes the subject; for
 * {@link Subjects#QUERY_GOALS}, which both roles publish, the agents' or people's. A request naming any other reply
 * subject is dropped unanswered, so nobody can make the kernel write into a stream, a bucket, a request
 * subject or the reply inbox of another role (where a kernel-signed answer could pass for the answer to a request of
 * the kernel's own). A request that names a reply seal key ({@link Security#HEADER_REPLY_SEAL}, covered by its
 * signature) gets its reply sealed for that key, so the gateway and other connections see only ciphertext. Every
 * reply carries the kernel's date ({@link Reply#today()}, the date the conversation uses), so a device on another
 * clock counts days the way the kernel does. A reply larger than the broker accepts is replaced by a refusal that says
 * so, and a failure inside the kernel is answered with {@link #FAILED}, never with the text of the exception.
 */
@Component
public class KernelNatsApi implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KernelNatsApi.class);
    private static final String QUEUE = "kernel";

    private final Connection connection;
    private final ConversationService conversation;
    private final ActionExecutor executor;
    private final SystemControl control;
    private final LedgerService ledger;
    private final RunCoordinator coordinator;
    private final CapabilityRegistry registry;
    private final DecisionLog decisions;
    private final PolicyGuard policy;
    private final Reputation reputation;
    private final ConstitutionService constitution;
    private final ProfileService profile;
    private final InboxService inbox;
    private final SecurityService security;
    private final EpisodicMemory memory;
    private final Genesis genesis;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final SentenceGate sentences = new SentenceGate();
    private final Supplier<LocalDate> today;
    private Dispatcher dispatcher;
    private volatile boolean running;

    /** Every reply carries {@link KernelProperties#today()}, the date the conversation uses. */
    @Autowired
    public KernelNatsApi(Connection connection, ConversationService conversation, ActionExecutor executor,
                         SystemControl control, LedgerService ledger, RunCoordinator coordinator,
                         CapabilityRegistry registry, DecisionLog decisions, PolicyGuard policy, Reputation reputation,
                         ConstitutionService constitution, ProfileService profile, InboxService inbox,
                         SecurityService security, EpisodicMemory memory, Genesis genesis,
                         KernelProperties properties) {
        this(connection, conversation, executor, control, ledger, coordinator, registry, decisions, policy, reputation,
                constitution, profile, inbox, security, memory, genesis, properties::today);
    }

    /** Replies carry the system clock's date. */
    KernelNatsApi(Connection connection, ConversationService conversation, ActionExecutor executor,
                  SystemControl control, LedgerService ledger, RunCoordinator coordinator,
                  CapabilityRegistry registry, DecisionLog decisions, PolicyGuard policy, Reputation reputation,
                  ConstitutionService constitution, ProfileService profile, InboxService inbox,
                  SecurityService security, EpisodicMemory memory, Genesis genesis) {
        this(connection, conversation, executor, control, ledger, coordinator, registry, decisions, policy, reputation,
                constitution, profile, inbox, security, memory, genesis, LocalDate::now);
    }

    /** {@code today} gives the date every reply carries. */
    KernelNatsApi(Connection connection, ConversationService conversation, ActionExecutor executor,
                  SystemControl control, LedgerService ledger, RunCoordinator coordinator,
                  CapabilityRegistry registry, DecisionLog decisions, PolicyGuard policy, Reputation reputation,
                  ConstitutionService constitution, ProfileService profile, InboxService inbox,
                  SecurityService security, EpisodicMemory memory, Genesis genesis, Supplier<LocalDate> today) {
        this.today = today;
        this.security = security;
        this.memory = memory;
        this.genesis = genesis;
        this.reputation = reputation;
        this.constitution = constitution;
        this.profile = profile;
        this.inbox = inbox;
        this.connection = connection;
        this.conversation = conversation;
        this.executor = executor;
        this.control = control;
        this.ledger = ledger;
        this.coordinator = coordinator;
        this.registry = registry;
        this.decisions = decisions;
        this.policy = policy;
    }

    /** Subscribes every kernel subject in the queue group {@code kernel}, each with the kinds of caller it serves. */
    @Override
    public void start() {
        dispatcher = connection.createDispatcher();
        dispatcher.setPendingLimits(PENDING_MESSAGES, PENDING_BYTES);
        handle(Subjects.INTENT_SUBMIT, Access.PERSON, this::submit);
        handle(Subjects.INTENT_CONFIRM, Access.PERSON, (m, c) -> conversationReply(conversation.confirm(
                Json.read(m.getData(), Conversation.Confirm.class), c)));
        handle(Subjects.INTENT_EXECUTE, Access.PERSON, (m, c) -> conversationReply(conversation.execute(
                Json.read(m.getData(), Conversation.Command.class), c)));
        handle(Subjects.CONTROL_STOP, Access.OWNER, (m, c) -> {
            control.pause(principal(m, c), "emergency stop");
            return Reply.ok(Map.of("paused", true));
        });
        handle(Subjects.CONTROL_RESUME, Access.OWNER, (m, c) -> Reply.ok(Map.of("paused", false, "restartedRuns",
                control.resume(principal(m, c)))));
        handle(Subjects.QUERY_GOAL, Access.PERSON, this::goal);
        handleLimited(Subjects.QUERY_CONTEXT, Access.AGENT, (m, c) -> contextGoal(m, c)
                .map(state -> Reply.ok(contextFor(c, profile.context(state)))).orElse(Reply.error("unknown goal")));
        handleLimited(Subjects.QUERY_GOALS, Access.AGENT_OR_OWNER, (m, c) -> Reply.ok(goalsFor(c)));
        handle(Subjects.INTENT_INBOX, Access.OWNER, (m, c) -> Reply.ok(unreadReply(inbox.unread(), maxReplyBytes())));
        handle(Subjects.INTENT_INBOX_ANSWER, Access.OWNER, (m, c) -> conversationReply(conversation.answer(
                Json.read(m.getData(), Inbox.Answer.class), c)));
        handle(Subjects.INBOX_POST, Access.AGENT, this::inboxPost);
        handleLimited(Subjects.QUERY_DECISION_LOOKUP, Access.AGENT, (m, c) -> decisions.lookup(ref(m),
                lookupScope(c)).map(Reply::ok).orElse(Reply.error("no recorded decision")));
        handleLimited(Subjects.PROPOSAL_SUBMIT, Access.AGENT, this::proposal);
        handleLimited(Subjects.QUERY_MEMORY, Access.AGENT, this::recall);
        handleLimited(Subjects.QUERY_GENESIS, Access.AGENT, this::genesisSpecs);
        handleLimited(Subjects.REGISTRY_WITHDRAW, Access.AGENT, this::withdraw);
        // Open to unknown keys: learning the kernel's keys, asking to pair, to join or to be admitted, and
        // registering (an agent the owner has not admitted yet learns where its admission stands, nothing is listed).
        handle(Subjects.SECURITY_HELLO, Access.ANY_SIGNED, (m, c) -> Reply.ok(new KernelApi.Hello(
                security.identity().publicKeyText(), security.sealKeyText())));
        handle(Subjects.REGISTRY_HEARTBEAT, Access.ANY_SIGNED, this::heartbeat);
        handle(Subjects.SECURITY_PAIR, Access.ANY_SIGNED, (m, c) -> {
            OpenedClaim opened = claim(m, Subjects.SECURITY_PAIR, c.publicKey());
            if (opened.problem() != null) {
                return Reply.error(opened.problem());
            }
            KernelApi.Claim claim = opened.claim();
            String problem = security.pair(c.publicKey(), claim.code(), claim.deviceName(), viaGateway(m));
            // The answer names the device as the kernel keeps it, which "who has access?" shows.
            return problem == null ? Reply.ok(Map.of("paired", true, "fingerprint",
                    Security.fingerprint(c.publicKey()), "kernel",
                    security.identity().publicKeyText(), "name", SecurityService.deviceName(claim.deviceName(),
                            Security.keyId(c.publicKey())))) : Reply.error(problem);
        });
        handle(Subjects.SECURITY_JOIN, Access.ANY_SIGNED, (m, c) -> {
            OpenedClaim opened = claim(m, Subjects.SECURITY_JOIN, c.publicKey());
            if (opened.problem() != null) {
                return Reply.error(opened.problem());
            }
            KernelApi.Claim claim = opened.claim();
            // The answer names the guest the device now acts as (the invitation's name), which "who has access?" shows.
            return security.join(c.publicKey(), claim.code(), claim.deviceName(), viaGateway(m)).map(Reply::error)
                    .orElseGet(() -> {
                        Optional<Grant> grant = security.grantOf(c.publicKey());
                        return Reply.ok(Map.of("joined", true, "kernel", security.identity().publicKeyText(),
                                "grant", grant.map(Grant::encode).orElse(""),
                                "name", grant.map(g -> g.rights().name()).orElse("")));
                    });
        });
        handle(Subjects.SECURITY_ADMISSION, Access.ANY_SIGNED, (m, c) -> Reply.ok(security.admission(c.publicKey(),
                Json.read(m.getData(), Admission.Request.class))));
        running = true;
        log.info("kernel API listening{}", security.enabled() ? " (signed requests only)" : " (security off)");
    }

    /**
     * The subjects the gateway forwards from the network ({@link Subjects#GATEWAY_SUBJECTS}), each served on
     * {@code gw.<subject>} as well: the broker lets the gateway publish only there and nobody else publish there.
     */
    static final Set<String> GATEWAY_SUBJECTS = Subjects.GATEWAY_SUBJECTS;

    /**
     * Requests one admitted agent's signing key may send per minute on each subject listed, whatever its instances:
     * proposals (each accepted one records facts and starts a refresh run, so they are also limited per hour,
     * {@link #PROPOSALS_PER_HOUR}), withdrawals of registry entries and the agents' queries. The memory query embeds
     * its text with the model. Heartbeats have their own budget per key in the registry
     * ({@link CapabilityRegistry#renew}).
     */
    static final Map<String, Integer> AGENT_REQUESTS_PER_MINUTE = Map.of(
            Subjects.PROPOSAL_SUBMIT, 120,
            Subjects.REGISTRY_WITHDRAW, 30,
            Subjects.QUERY_CONTEXT, 600,
            Subjects.QUERY_GOALS, 600,
            Subjects.QUERY_DECISION_LOOKUP, 600,
            Subjects.QUERY_GENESIS, 600,
            Subjects.QUERY_MEMORY, 300);
    /** Proposals one admitted agent's signing key may send per hour. */
    static final int PROPOSALS_PER_HOUR = 1200;
    private final Map<String, Quota> agentQuotas = AGENT_REQUESTS_PER_MINUTE.entrySet()
            .stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                    e -> new Quota(e.getValue(), Duration.ofMinutes(1))));
    private final Quota proposalsPerHour = new Quota(PROPOSALS_PER_HOUR, Duration.ofHours(1));
    /** The time the agents' request quotas count with. */
    Supplier<Instant> now = Instant::now;

    /**
     * {@code handler} within the quota of {@code subject} ({@link #AGENT_REQUESTS_PER_MINUTE}), counted per signing
     * key of an admitted agent: a request beyond it is refused before any work, and the refusal is written down as
     * sampled as every other refused request ({@link SecurityService#refused}). The kernel, people and every caller
     * while security is off are not counted.
     *
     * @throws IllegalArgumentException when {@code subject} has no quota
     */
    BiFunction<Message, SecurityService.Caller, Reply> agentsLimited(String subject,
            BiFunction<Message, SecurityService.Caller, Reply> handler) {
        Quota perMinute = agentQuotas.get(subject);
        if (perMinute == null) {
            throw new IllegalArgumentException("no request quota for " + subject);
        }
        Quota perHour = Subjects.PROPOSAL_SUBMIT.equals(subject) ? proposalsPerHour : null;
        return (m, c) -> {
            if (security.enabled() && c.role() == SecurityService.Role.AGENT) {
                Instant at = now.get();
                String why = !perMinute.take(c.publicKey(), at)
                        ? AGENT_REQUESTS_PER_MINUTE.get(subject) + " requests on " + subject + " within a minute"
                        : perHour != null && !perHour.take(c.publicKey(), at)
                        ? PROPOSALS_PER_HOUR + " proposals within an hour" : null;
                if (why != null) {
                    security.refused(c.keyId(), subject, "more than " + why);
                    return Reply.error("refused: this agent sent " + why + "; try again later");
                }
            }
            return handler.apply(m, c);
        };
    }

    /**
     * Who may use a subject. Unknown keys reach only the subjects for learning the kernel's keys, pairing, joining,
     * admission and registration.
     */
    enum Access { PERSON, OWNER, AGENT, AGENT_OR_OWNER, ANY_SIGNED }

    /** Whether a verified caller of this role may use a subject of this kind; with security off everyone may. */
    static boolean allowed(Access access, SecurityService.Role role) {
        if (role == SecurityService.Role.ANONYMOUS) {
            return true; // security is off
        }
        return switch (access) {
            case PERSON -> role == SecurityService.Role.OWNER || role == SecurityService.Role.GUEST;
            case OWNER -> role == SecurityService.Role.OWNER;
            case AGENT -> role == SecurityService.Role.AGENT || role == SecurityService.Role.KERNEL;
            case AGENT_OR_OWNER -> role == SecurityService.Role.AGENT || role == SecurityService.Role.KERNEL
                    || role == SecurityService.Role.OWNER;
            case ANY_SIGNED -> true;
        };
    }

    /** {@link #handle} with the subject's quota for admitted agents ({@link #agentsLimited}). */
    private void handleLimited(String subject, Access access,
                               BiFunction<Message, SecurityService.Caller, Reply> handler) {
        handle(subject, access, agentsLimited(subject, handler));
    }

    /**
     * Requests of one subscription the kernel works on at a time, by who may use its subject; a request beyond that
     * is dropped unanswered ({@link RequestIntake}). Sentences can wait for the model, so people's subjects have more
     * room than the owner's controls; the subjects open to keys the kernel does not know have the least.
     */
    static final Map<Access, Integer> IN_WORK = Map.of(Access.PERSON, 16, Access.OWNER, 8, Access.AGENT, 16,
            Access.AGENT_OR_OWNER, 16, Access.ANY_SIGNED, 8);
    /** Messages the dispatcher holds before handing them on; beyond that the client library drops them. */
    static final int PENDING_MESSAGES = 10_000;
    /** Bytes of messages the dispatcher holds before handing them on; beyond that the client library drops them. */
    static final long PENDING_BYTES = 32L * 1024 * 1024;
    private final Map<String, RequestIntake> intakes = new ConcurrentHashMap<>();

    /**
     * Serves one subject (see {@link #serve}), and its gateway subject too when it is one the gateway forwards, each
     * with its own {@link RequestIntake} of {@link #IN_WORK} requests on virtual threads. Both are served as the
     * plain subject.
     */
    private void handle(String subject, Access access, BiFunction<Message, SecurityService.Caller, Reply> handler) {
        subscribe(subject, subject, access, handler);
        if (GATEWAY_SUBJECTS.contains(subject)) {
            subscribe(Subjects.viaGateway(subject), subject, access, handler);
        }
    }

    /** Subscribes {@code subscribed}, whose requests are served as the plain {@code subject}, through its intake. */
    private void subscribe(String subscribed, String subject, Access access,
                           BiFunction<Message, SecurityService.Caller, Reply> handler) {
        RequestIntake intake = new RequestIntake(subscribed, IN_WORK.get(access), workers);
        intakes.put(subscribed, intake);
        dispatcher.subscribe(subscribed, QUEUE, message -> intake.offer(() -> serve(subject, access, handler,
                message)));
    }

    /** The intake of a subscription: a subject or its gateway subject; {@code null} before {@link #start()}. */
    RequestIntake intake(String subscribed) {
        return intakes.get(subscribed);
    }

    /**
     * Serves one request on the plain {@code subject} (also when it arrived on its gateway subject). A request whose
     * reply subject is not a reply inbox is dropped before any work. Every other one is verified (signature over the
     * subject and any reply seal key, the budget of its sender or of strangers, freshness, nonce, sender; see
     * {@link SecurityService#verify}) and dropped when its reply subject is not under the reply prefix of the role
     * that sent it ({@link #replyPrefixes}); otherwise it is refused when the sender's kind may not use the subject,
     * and answered with a reply the kernel signs for that request, sealed for the reply seal key it names. A reply
     * seal key that is not an X25519 key gets a plain refusal. Every reply, refusals included, carries the kernel's
     * date.
     */
    void serve(String subject, Access access, BiFunction<Message, SecurityService.Caller, Reply> handler,
               Message message) {
        String replyTo = message.getReplyTo();
        if (replyTo != null && !NatsSupport.isReplySubject(replyTo)) {
            log.debug("dropped a request on {}: its reply subject {} is not a reply inbox", subject, replyTo);
            return;
        }
        SecurityService.Verdict verdict;
        try {
            verdict = security.verify(message, subject);
        } catch (RuntimeException e) {
            log.warn("{} failed: {}", subject, e.getMessage(), e);
            verdict = null;
        }
        if (replyTo != null && !replyFits(replyTo, message, subject, verdict)) {
            log.debug("dropped a request on {}: its reply subject {} is not the sender's reply inbox", subject, replyTo);
            return;
        }
        String seal = FabricSecurity.replySealOf(message);
        Reply reply;
        if (verdict == null) {
            seal = null;
            reply = Reply.error(FAILED);
        } else if (seal != null && !FabricSecurity.isSealKey(seal)) {
            seal = null;
            reply = Reply.error("refused: the reply seal key is not an X25519 public key");
        } else {
            reply = answer(subject, access, handler, message, verdict);
        }
        if (replyTo != null) {
            try {
                FabricSecurity.SignedReply signed = security.reply(subject, requestNonce(message),
                        fitting(subject, reply.withToday(today.get())), seal);
                connection.publish(replyTo, signed.headers(), signed.body());
            } catch (RuntimeException e) {
                log.warn("no reply on {}: {}", subject, e.getMessage());
            }
        }
    }

    /**
     * The subjects only people's devices publish on the machine's own broker: sentences, typed actions, answers, the
     * inbox, the emergency stop and resume, one goal's details, pairing, joining and learning the kernel's keys. The
     * broker lets agents publish none of them; {@link Subjects#QUERY_GOALS} both may.
     */
    static final Set<String> PERSON_SUBJECTS = Set.of(Subjects.INTENT_SUBMIT, Subjects.INTENT_CONFIRM,
            Subjects.INTENT_EXECUTE, Subjects.INTENT_INBOX, Subjects.INTENT_INBOX_ANSWER, Subjects.CONTROL_STOP,
            Subjects.CONTROL_RESUME,
            Subjects.QUERY_GOAL, Subjects.SECURITY_PAIR, Subjects.SECURITY_JOIN, Subjects.SECURITY_HELLO);

    /**
     * The reply prefixes (each with its trailing dot) a request's reply may go to: the reply inbox of the broker role
     * that sent it. Through the gateway (a {@code gw.} subject, which only the gateway publishes) that is the
     * gateway's. Otherwise the verified sender decides: an agent gets {@code _INBOX_agent.}, the kernel itself
     * {@code _INBOX_kernel.} and an owner's or guest's device {@code _INBOX_person.}. A key the kernel does not
     * know, or a request it could not verify ({@code role} is {@code null}), gets the inbox of the role that publishes
     * the subject: people's for {@link #PERSON_SUBJECTS}, agents' for the others, and for {@link Subjects#QUERY_GOALS},
     * which both roles publish, the agents' or people's, so an agent not admitted yet and a device not paired yet
     * each hear their refusal. Either way the reply goes to the one reply subject the request names, never to more.
     * While security is off ({@link SecurityService.Role#ANONYMOUS}) every role's inbox is allowed.
     */
    static Set<String> replyPrefixes(boolean viaGateway, String subject, SecurityService.Role role) {
        String agent = Subjects.INBOX_AGENT + ".";
        String person = Subjects.INBOX_PERSON + ".";
        if (viaGateway) {
            return Set.of(Subjects.INBOX_GATEWAY + ".");
        }
        if (role == null) {
            role = SecurityService.Role.UNKNOWN;
        }
        return switch (role) {
            case ANONYMOUS -> Set.of(agent, person, Subjects.INBOX_GATEWAY + ".", Subjects.INBOX_KERNEL + ".");
            case AGENT -> Set.of(agent);
            case KERNEL -> Set.of(Subjects.INBOX_KERNEL + ".");
            case OWNER, GUEST -> Set.of(person);
            case UNKNOWN -> Subjects.QUERY_GOALS.equals(subject) ? Set.of(agent, person)
                    : PERSON_SUBJECTS.contains(subject) ? Set.of(person) : Set.of(agent);
        };
    }

    /** Whether {@code replyTo} is under one of the {@link #replyPrefixes} of this request and its sender. */
    static boolean replyFits(String replyTo, Message message, String subject, SecurityService.Verdict verdict) {
        SecurityService.Role role = verdict == null || verdict.caller() == null ? null : verdict.caller().role();
        return replyPrefixes(viaGateway(message), subject, role).stream()
                .anyMatch(p -> replyTo.length() > p.length() && replyTo.startsWith(p));
    }

    /** What is left of the broker's message size for a reply body: room for the headers, the seal and the subject. */
    static final int REPLY_HEADROOM = 16 * 1024;
    /** The broker's own default message size, used while the connection does not tell. */
    static final long DEFAULT_MAX_PAYLOAD = 1024 * 1024;

    /** The largest reply body the broker accepts on this connection, less {@link #REPLY_HEADROOM}. */
    int maxReplyBytes() {
        long max = DEFAULT_MAX_PAYLOAD;
        try {
            var info = connection.getServerInfo();
            if (info != null && info.getMaxPayload() > 0) {
                max = info.getMaxPayload();
            }
        } catch (RuntimeException e) {
            log.debug("cannot read the broker's message size: {}", e.getMessage());
        }
        return (int) Math.max(REPLY_HEADROOM, Math.min(Integer.MAX_VALUE, max) - REPLY_HEADROOM);
    }

    /**
     * The reply's body, or a refusal saying the answer was too large (with the same date) when the body exceeds
     * {@link #maxReplyBytes()}: the broker would drop it and the caller would only see a timeout.
     */
    byte[] fitting(String subject, Reply reply) {
        byte[] body = reply.toBytes();
        if (body.length <= maxReplyBytes()) {
            return body;
        }
        log.warn("the answer on {} has {} bytes, more than the broker accepts", subject, body.length);
        return Reply.error("the answer is too large to send; ask for less").withToday(reply.today()).toBytes();
    }

    private Reply answer(String subject, Access access, BiFunction<Message, SecurityService.Caller, Reply> handler,
                         Message message, SecurityService.Verdict verdict) {
        try {
            if (verdict.refused()) {
                return Reply.error("refused: " + verdict.refusal());
            }
            SecurityService.Role role = verdict.caller().role();
            if (!allowed(access, role)) {
                // Through the gateway nobody is told whether a device is paired yet.
                return Reply.error(refusal(role, verdict.problem(),
                        viaGateway(message) || role != SecurityService.Role.UNKNOWN || security.hasOwner()));
            }
            return handler.apply(message, verdict.caller());
        } catch (RuntimeException e) {
            log.warn("{} failed: {}", subject, e.getMessage(), e);
            return Reply.error(FAILED);
        }
    }

    /**
     * The answer to a request that failed inside the kernel (an unreadable payload or a bug): the exception's text
     * goes to the kernel's log only, since it can describe the kernel's state to whoever asked.
     */
    static final String FAILED = "could not handle the request";

    /**
     * Whether the gateway forwarded the request from the network: it arrived on a {@code gw.} subject, which only
     * the gateway may publish. Headers play no part ({@link Security#HEADER_VIA} is ignored).
     */
    static boolean viaGateway(Message message) {
        String subject = message.getSubject();
        return subject != null && subject.startsWith(Subjects.GATEWAY_PREFIX);
    }

    /**
     * The nonce of a request's stamp, which binds the reply to it; empty for an unsigned request and for a nonce not
     * in the form a stamp carries ({@link Security#isValidNonce}), which no verified request has.
     */
    static String requestNonce(Message message) {
        Headers headers = message.getHeaders();
        String nonce = headers == null ? null : headers.getFirst(Security.HEADER_NONCE);
        return Security.isValidNonce(nonce) ? nonce : "";
    }

    /** The time a request's stamp was signed; {@code fallback} for an unsigned request. */
    static Instant signedAt(Message message, Instant fallback) {
        return FabricSecurity.stampOf(message).map(s -> Instant.ofEpochMilli(s.ts())).orElse(fallback);
    }

    // ---- sentences and the inbox ---------------------------------------------------------------------------

    /** The longest sentence the kernel takes; longer ones are refused before the model or any parsing sees them. */
    static final int MAX_SENTENCE = 2000;

    /** One sentence of a person, refused when it has more than {@link #MAX_SENTENCE} characters. */
    Reply submit(Message message, SecurityService.Caller caller) {
        Conversation.Utterance said = Json.read(message.getData(), Conversation.Utterance.class);
        if (said.text() != null && said.text().length() > MAX_SENTENCE) {
            return Reply.error("refused: a sentence may have at most " + MAX_SENTENCE + " characters; say it in "
                    + "shorter parts");
        }
        return sentences.run(caller, () -> conversationReply(conversation.handle(said, caller)));
    }

    /** The newest unread inbox messages one reply carries; the reply also says how many there are in all. */
    static final int INBOX_PAGE = 100;

    /**
     * A conversation reply with at most the newest {@link #INBOX_PAGE} inbox messages that fit one message. When
     * messages were left out, the inbox line says so: "250 unread messages; the newest 100 are shown".
     */
    Reply conversationReply(Conversation.Reply reply) {
        return Reply.ok(withInboxPage(Json.toTree(reply), maxReplyBytes()));
    }

    /** {@link #conversationReply} on the reply as JSON, for a reply of at most {@code maxBytes}. */
    static JsonNode withInboxPage(JsonNode tree, int maxBytes) {
        if (!(tree instanceof ObjectNode object) || !tree.path("inbox").isArray()
                || tree.path("inbox").isEmpty()) {
            return tree;
        }
        List<Inbox.Message> all = List.of(Json.MAPPER.treeToValue(tree.path("inbox"), Inbox.Message[].class));
        int rest = Json.bytes(tree).length - Json.bytes(tree.path("inbox")).length;
        List<Inbox.Message> shown = newest(all, maxBytes - rest - 200);
        if (shown.size() == all.size()) {
            return tree;
        }
        object.set("inbox", Json.toTree(shown));
        for (JsonNode executed : tree.path("executed")) {
            if (executed instanceof ObjectNode line
                    && Conversation.ActionType.SHOW_INBOX.name().equals(line.path("type").asString(""))) {
                line.put("message", all.size() + " unread messages; the newest " + shown.size() + " are shown");
            }
        }
        return tree;
    }

    /**
     * The answer to {@code intent.inbox}: {@code messages}, the newest {@link #INBOX_PAGE} unread messages (oldest
     * first) that fit in {@code maxBytes}, and {@code total}, how many are unread in all.
     */
    static Map<String, Object> unreadReply(List<Inbox.Message> unread, int maxBytes) {
        List<Inbox.Message> shown = newest(unread, maxBytes - 64);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("messages", shown);
        answer.put("total", unread.size());
        return answer;
    }

    /**
     * The newest {@link #INBOX_PAGE} of {@code messages} (given oldest first, returned oldest first), fewer when
     * they would take more than {@code maxBytes} as JSON: then the oldest of them are left out.
     */
    static List<Inbox.Message> newest(List<Inbox.Message> messages, int maxBytes) {
        List<Inbox.Message> kept = new ArrayList<>(
                messages.subList(Math.max(0, messages.size() - INBOX_PAGE), messages.size()));
        long size = 2 + kept.stream().mapToLong(m -> Json.bytes(m).length + 1).sum();
        while (!kept.isEmpty() && size > maxBytes) {
            size -= Json.bytes(kept.removeFirst()).length + 1;
        }
        return kept;
    }

    // ---- pairing and invitations ---------------------------------------------------------------------------

    /** A pairing or invitation claim as the kernel read it, or why it was not read. */
    record OpenedClaim(KernelApi.Claim claim, String problem) {
    }

    /**
     * The claim in a pairing or joining request on the plain {@code subject}, signed by {@code signerKey} (the key the
     * request's verified stamp names). A body sealed for the kernel ({@link Security#HEADER_SEALED}) is opened with
     * the kernel's seal key for {@link Security#claimContext}: the device sealed it for this subject and its own
     * signing key, so the same sealed bytes signed by any other key (a relay that re-signs them to pair itself) do not
     * open. Without a verified key (security off) the key part is empty. A claim that came through the gateway must
     * be sealed, so the gateway (or whoever runs it) never sees a code it could redeem first; on the machine's own
     * broker a plain claim still works (the first pairing).
     */
    OpenedClaim claim(Message message, String subject, String signerKey) {
        Headers headers = message.getHeaders();
        String scheme = headers == null ? null : headers.getFirst(Security.HEADER_SEALED);
        byte[] body = message.getData();
        if (scheme != null) {
            if (!Security.SEAL_SCHEME.equals(scheme)) {
                return new OpenedClaim(null, "refused: unknown sealing scheme " + plainScheme(scheme));
            }
            try {
                body = security.openSealed(Security.claimContext(subject, signerKey == null ? "" : signerKey), body);
            } catch (IllegalArgumentException e) {
                return new OpenedClaim(null, "refused: the code is not sealed for this kernel; ask it for its keys "
                        + "again");
            }
        } else if (viaGateway(message)) {
            return new OpenedClaim(null, "refused: a code sent through the gateway must be sealed for the kernel; "
                    + "update the fabric client");
        }
        return new OpenedClaim(Json.read(body, KernelApi.Claim.class), null);
    }

    /** A scheme name from a request, cut short and without control characters, for a refusal. */
    private static String plainScheme(String scheme) {
        String plain = scheme.replaceAll("[^A-Za-z0-9._-]", "");
        return plain.length() > 40 ? plain.substring(0, 40) : plain;
    }

    // ---- registration and memory ---------------------------------------------------------------------------

    /**
     * An agent's heartbeat: its registry entries are listed or renewed ({@link CapabilityRegistry#renew}) and the
     * answer says where its admission stands, as {@code security.admission} would (asking the owner when needed) for
     * the manifests of the first {@link CapabilityRegistry#MAX_ENTRIES_PER_KEY} entries, the ones the registry looks
     * at: {@code status}, {@code grant}, {@code fingerprint} and {@code message}, then {@code listed} (how many of its
     * entries are listed now) and {@code refused} (why the others are not). The kernel itself and, with
     * security off, everyone get only the last two.
     */
    Reply heartbeat(Message message, SecurityService.Caller caller) {
        KernelApi.Registration registration = Json.read(message.getData(), KernelApi.Registration.class);
        Instant now = Instant.now();
        CapabilityRegistry.Renewal renewal = registry.renew(security.enabled() ? caller.publicKey() : null,
                registration.entries(), signedAt(message, now), now);
        if (renewal.limited()) {
            return Reply.error("refused: " + String.join("; ", renewal.refused()));
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        List<CapabilityManifest> manifests = registration.entries().stream()
                .limit(CapabilityRegistry.MAX_ENTRIES_PER_KEY)
                .filter(Objects::nonNull).map(SignedManifest::manifest)
                .filter(Objects::nonNull).toList();
        if (security.enabled() && caller.role() != SecurityService.Role.KERNEL && !manifests.isEmpty()) {
            CapabilityManifest first = manifests.getFirst();
            Admission.Answer status = security.admission(caller.publicKey(),
                    new Admission.Request(first.provider(), first.instanceId(), manifests));
            answer.put("status", status.status());
            answer.put("grant", status.grant());
            answer.put("fingerprint", status.fingerprint());
            answer.put("message", status.message());
        }
        answer.put("listed", renewal.listed());
        answer.put("refused", renewal.refused());
        return Reply.ok(answer);
    }

    /**
     * An agent withdraws registry entries it listed; they leave at once. Answers with the keys that left. A key's
     * withdrawals count against its quota for the subject ({@link #AGENT_REQUESTS_PER_MINUTE}).
     */
    Reply withdraw(Message message, SecurityService.Caller caller) {
        KernelApi.Registration registration = Json.read(message.getData(), KernelApi.Registration.class);
        Instant now = Instant.now();
        List<String> removed = registry.withdraw(security.enabled() ? caller.publicKey() : null,
                registration.entries(), signedAt(message, now), now);
        return Reply.ok(Map.of("withdrawn", removed));
    }

    /**
     * One goal's details ({@code query.goal}). The owner's devices get any goal whole; a guest only a goal its grant
     * lets it show ({@link SecurityService.Caller#may} for {@code SHOW_GOAL}: the goal is among the grant's goals and
     * showing a goal among its actions), and then as {@link GuestView#details} leaves it, without the owner's pace and
     * the plan runs. Every other goal is unknown to the caller.
     */
    Reply goal(Message message, SecurityService.Caller caller) {
        return goalRef(message).filter(id -> caller.may(Conversation.ActionType.SHOW_GOAL, id))
                .map(id -> Reply.ok(caller.owner() ? executor.details(id)
                        : GuestView.details(executor.details(id))))
                .orElse(Reply.error("unknown goal"));
    }

    /** At most this many finished tasks answer one memory query; 5 when the query asks for none. */
    static final int MAX_RECALL = 20;

    /**
     * Finished tasks like the text, with their hours ({@link KernelApi.RecallResult}), as far as the caller may read
     * them. Every admitted agent gets the tasks' titles, as every goal context carries them
     * ({@link dev.capabilityfabric.kernel.security.Contract#STRUCTURE}); it sees the goals' text only when it was
     * admitted to read {@code goal.text}, and the hours only when it reads {@code task.actualHours} or
     * {@code task.effortHours} ({@link #readsOf}). The kernel, and every caller while security is off, sees everything.
     */
    Reply recall(Message message, SecurityService.Caller caller) {
        KernelApi.MemoryQuery query = Json.read(message.getData(), KernelApi.MemoryQuery.class);
        int limit = query.limit() <= 0 ? 5 : Math.min(query.limit(), MAX_RECALL);
        String text = query.text() == null ? "" : query.text();
        text = text.length() > MAX_SENTENCE ? text.substring(0, MAX_SENTENCE) : text;
        Set<String> reads = readsOf(caller);
        return Reply.ok(reads == null ? new KernelApi.RecallResult(memory.recall(text, limit))
                : memory.recall(text, limit, reads));
    }

    /**
     * The grown capabilities' specs ({@code query.genesis}), each signed by the kernel, for the agents that host
     * them: admitted agents whose grant covers {@code capability.design} (and the kernel, and everyone while
     * security is off). Retired specs are included, so the host withdraws what it should no longer serve. The specs
     * are in their published form ({@link Genesis#signedSpecs}): without the sentence that asked for them and without
     * who asked.
     */
    Reply genesisSpecs(Message message, SecurityService.Caller caller) {
        if (security.enabled() && caller.role() == SecurityService.Role.AGENT
                && (caller.grant() == null || !SecurityService.hostsGrownCapabilities(caller.grant()))) {
            return Reply.error("refused: only the agent that hosts grown capabilities reads their specs");
        }
        return Reply.ok(genesis.signedSpecs());
    }

    /**
     * What a caller may read from memory: for an agent the tokens its admitted contract reads, or (for an admission
     * kept without a contract) what its live entries consume or require; {@code null} (everything) for the kernel
     * and while security is off.
     */
    Set<String> readsOf(SecurityService.Caller caller) {
        if (!security.enabled() || caller.role() != SecurityService.Role.AGENT) {
            return null;
        }
        return security.admittedReads(caller.publicKey())
                .orElseGet(() -> consumed(registry.signedBy(caller.publicKey())));
    }

    static final String NOT_PAIRED = "refused: this device is not paired: on a paired device say \"I want to use my "
            + "laptop too\" to get a pairing code, or ask the owner for an invitation (an agent waits until the owner "
            + "admits it)";
    /** The refusal for an unknown key while no device of the owner is paired yet. */
    static final String NO_DEVICE_YET = "refused: no device is paired yet: the first pairing code is in the kernel's "
            + "log: docker compose logs kernel | grep \"fabric --pair\"";

    /**
     * Why a verified caller may not use a subject: an unknown key is told how to pair (where the first code is while
     * {@code ownerPaired} is false), a key whose grant no longer counts is told why ({@code problem}), and a known
     * caller is told the subject is not for its kind.
     */
    static String refusal(SecurityService.Role role, String problem, boolean ownerPaired) {
        return switch (role) {
            case UNKNOWN -> problem != null ? "refused: " + problem : ownerPaired ? NOT_PAIRED : NO_DEVICE_YET;
            case GUEST -> "refused: guests may not do this; only the owner's devices may";
            case AGENT -> "refused: agents may not do this";
            case OWNER -> "refused: this is for agents, not for the owner's devices";
            default -> "refused: not allowed for " + role.name().toLowerCase(Locale.ROOT);
        };
    }

    /**
     * The goal context an agent may read ({@link #readable}): what its admitted, live manifests declare they
     * consume or require, and the annotations they write. Like every context, it keeps the blackboard's structure
     * (each task's title, status and dependencies). The kernel itself, and every caller while security is off,
     * gets it whole.
     */
    GoalContext contextFor(SecurityService.Caller caller, GoalContext context) {
        if (!security.enabled() || caller.role() != SecurityService.Role.AGENT) {
            return context;
        }
        return context.minimise(readable(registry.signedBy(caller.publicKey())));
    }

    /**
     * Every token these manifests consume or require, and every annotation they write: an agent always reads back
     * its own annotations, so it can see what it said before and clear or change it.
     */
    public static Set<String> readable(List<CapabilityManifest> manifests) {
        Set<String> tokens = consumed(manifests);
        manifests.forEach(m -> m.produces().stream().filter(t -> Vocabulary.isTaskAnnotation(t)
                || Vocabulary.isGoalAnnotation(t)).forEach(tokens::add));
        return tokens;
    }

    /**
     * The goals a caller may list. Agents get every open goal with its id, status, due date and last change only
     * (no text or counts: what they need to decide which goal to look at); owners get the most recent goals whole.
     */
    List<KernelApi.GoalSummary> goalsFor(SecurityService.Caller caller) {
        if (caller.role() == SecurityService.Role.AGENT) {
            return forAgents(ledger.list(Integer.MAX_VALUE));
        }
        return ledger.list(OWNER_GOALS);
    }

    /** At most this many goals, the most recently created first, answer an owner's {@code query.goals}. */
    static final int OWNER_GOALS = 1000;

    /** Open goals reduced to id, status, due date and last change. */
    static List<KernelApi.GoalSummary> forAgents(List<KernelApi.GoalSummary> goals) {
        return goals.stream()
                .filter(g -> g.status() != GoalStatus.COMPLETED && g.status() != GoalStatus.CANCELLED)
                .map(g -> new KernelApi.GoalSummary(g.id(), null, g.status(), g.dueDate(), 0, 0, null, g.updatedAt()))
                .toList();
    }

    /**
     * Which recorded decisions a caller may replay: an agent only those of the capabilities its live entries serve;
     * the kernel, and every caller while security is off, any ({@code null}).
     */
    Collection<String> lookupScope(SecurityService.Caller caller) {
        if (!security.enabled() || caller.role() != SecurityService.Role.AGENT) {
            return null;
        }
        return registry.signedBy(caller.publicKey()).stream().map(CapabilityManifest::id).collect(Collectors.toSet());
    }

    /** The decision source of a live instance: its id and every capability it serves. */
    DecisionLog.Source sourceOf(String instanceId) {
        return new DecisionLog.Source(instanceId, registry.live().stream()
                .filter(m -> m.instanceId().equals(instanceId)).map(CapabilityManifest::id).distinct().toList());
    }

    /** Every token these manifests consume or require. */
    static Set<String> consumed(List<CapabilityManifest> manifests) {
        Set<String> tokens = new LinkedHashSet<>();
        manifests.forEach(m -> {
            tokens.addAll(m.consumes());
            tokens.addAll(m.requires());
        });
        return tokens;
    }

    /**
     * A reactive provider's suggestion. Accepted only if the provider is live with a reactive manifest for the
     * capability (an invoked capability changes goals only through the calls the kernel makes), wrote only what it
     * declared, stays within the reactive autonomy limit, and the ledger rules accept the change. The kernel names the
     * proposal itself: the facts it causes and the decision on it carry that id, never the one the agent chose,
     * which could match a capability call's request id or another proposal's and so claim facts it did not cause.
     */
    Reply proposal(Message message, SecurityService.Caller caller) {
        Proposal proposal = Json.read(message.getData(), Proposal.class);
        String causation = proposalCause();
        if (security.enabled() && !caller.publicKey().equals(registry.keyOf(proposal.instanceId()).orElse(""))) {
            return Reply.error("refused: the proposal is not signed by the instance it names");
        }
        if (policy.paused()) {
            return Reply.error("system paused");
        }
        Optional<CapabilityManifest> manifest = registry.instance(proposal.capability(), proposal.instanceId());
        String goalId = proposal.goalId();
        if (manifest.isEmpty()) {
            return Reply.error("provider is not live"); // nothing to credit or blame: the sender is unknown
        }
        if (manifest.get().mode() != CapabilityManifest.Mode.REACTIVE) {
            return Reply.error("refused: only reactive capabilities send proposals");
        }
        if (ledger.load(goalId).isEmpty()) {
            return Reply.error("unknown goal");
        }
        Effects effects = ledger.keepHumanEdits(goalId, proposal.effects());
        var ruling = constitution.checkAgent(manifest.get(), goalId);
        if (ruling.verdict() != ConstitutionService.Verdict.ALLOW) {
            // Proposals are not queued for approval; a rule holds them back without counting against the provider.
            return decideProposal(proposal, causation, manifest.get(), goalId, false,
                    "held back by " + ruling.explanation(), false);
        }
        if (policy.requiresApproval(manifest.get())) {
            // Like a rule that asks first: held back, not held against the provider.
            return decideProposal(proposal, causation, manifest.get(), goalId, false,
                    "held back: " + manifest.get().id() + " needs the person's approval", false);
        }
        Optional<String> refused = policy.checkProposal(manifest.get(), effects);
        if (refused.isPresent()) {
            return decideProposal(proposal, causation, manifest.get(), goalId, false, refused.get(), true);
        }
        decisions.saveAll(goalId, null, -1, proposal.decisions(), sourceOf(manifest.get().instanceId()));
        String actor = "agent:" + manifest.get().provider() + "/" + manifest.get().instanceId();
        // The person's own efforts and due dates are kept once more under the goal's lock, so an edit made while
        // the proposal was checked is not undone.
        LedgerService.Applied applied = ledger.applyEffectsKeepingHumanEdits(goalId, effects, manifest.get(), actor,
                causation);
        if (!applied.ok()) {
            // A goal cancelled meanwhile refuses every change; that is not the provider's fault.
            boolean cancelled = ledger.load(goalId).map(g -> g.status() == GoalStatus.CANCELLED).orElse(true);
            return decideProposal(proposal, causation, manifest.get(), goalId, false,
                    String.join("; ", applied.errors()), !cancelled);
        }
        if (applied.events().isEmpty()) {
            // Nothing changed: neither credited nor recorded as a decision.
            return Reply.ok(Map.of("accepted", false, "facts", 0));
        }
        decideProposal(proposal, causation, manifest.get(), goalId, true, applied.events().size() + " facts", true);
        Set<String> changed = new LinkedHashSet<>(effects.producedTokens());
        coordinator.request(goalId, "refresh", changed);
        return Reply.ok(Map.of("accepted", true, "facts", applied.events().size()));
    }

    /** A new proposal id of the kernel's: never one a capability call or an agent could have chosen. */
    static String proposalCause() {
        return "proposal:" + UUID.randomUUID();
    }

    /** Inbox kinds only the kernel writes. */
    static final Set<Inbox.Kind> KERNEL_KINDS = Set.of(Inbox.Kind.ADMISSION, Inbox.Kind.RULE_LIMIT,
            Inbox.Kind.SECURITY);
    /** Dedupe-key prefixes of messages the kernel writes; a provider's message may not reuse them. */
    static final List<String> KERNEL_KEY_PREFIXES = List.of("admission:", "limit:", "security:");

    /** Characters an agent's inbox message may have. */
    static final int MAX_POST_TEXT = 2000;
    /** Suggestions one inbox message may carry. */
    static final int MAX_SUGGESTIONS = 10;
    /** Inbox messages one signing key may post per hour, whatever its instances. */
    static final int POSTS_PER_HOUR = 120;
    private final Quota posts = new Quota(POSTS_PER_HOUR, Duration.ofHours(1));

    /**
     * A provider's suggestion for the person's inbox: only from live providers, within {@link #POSTS_PER_HOUR} per
     * signing key, with at most {@link #MAX_POST_TEXT} characters and {@link #MAX_SUGGESTIONS} suggestions, only
     * suggestible actions on the message's goal ({@link #suggestionProblem}), never a kind or dedupe key the kernel
     * owns. The provider recorded is the one the registry knows for the signer, never the one the payload names. The
     * model calls behind a counted post are recorded whatever becomes of it (stored, a duplicate, held back, or
     * refused).
     */
    Reply inboxPost(Message message, SecurityService.Caller caller) {
        Inbox.Post given = Json.read(message.getData(), Inbox.Post.class);
        if (security.enabled() && !caller.publicKey().equals(registry.keyOf(given.instanceId()).orElse(""))) {
            return Reply.error("refused: the message is not signed by the instance it names");
        }
        if (policy.paused()) {
            return Reply.error("system paused");
        }
        Optional<CapabilityManifest> from = registry.live().stream()
                .filter(m -> m.instanceId().equals(given.instanceId())).findFirst();
        if (from.isEmpty()) {
            return Reply.error("provider is not live");
        }
        String sender = security.enabled() ? caller.publicKey() : given.instanceId();
        if (!posts.take(sender, Instant.now())) {
            return Reply.error("refused: this agent posted " + POSTS_PER_HOUR + " messages within the hour; "
                    + "try again later");
        }
        boolean openGoal = given.goalId() != null && inbox.goalIsOpen(given.goalId());
        Reply reply = store(given, caller, from.get(), openGoal);
        decisions.saveAll(openGoal ? given.goalId() : null, null, -1, given.decisions(), sourceOf(given.instanceId()));
        return reply;
    }

    /**
     * Checks a counted post from a live provider and stores it. The answer is the stored message;
     * {@code {"duplicate": true}} when the same observation is already there (posting it again changes nothing);
     * or {@code {"held": true, "reason": ...}} when the provider has too many unread messages ({@link #HELD_REASON}),
     * so the agent can post it again later.
     */
    private Reply store(Inbox.Post given, SecurityService.Caller caller, CapabilityManifest from, boolean openGoal) {
        Optional<String> problem = postProblem(given);
        if (problem.isPresent()) {
            return Reply.error("refused: " + problem.get());
        }
        if (!openGoal) {
            return Reply.error("unknown or settled goal");
        }
        Optional<String> unsuggestible = suggestionProblem(given);
        if (unsuggestible.isPresent()) {
            return Reply.error(unsuggestible.get());
        }
        String provider = caller.role() == SecurityService.Role.AGENT ? caller.provider() : from.provider();
        Inbox.Post post = new Inbox.Post(given.kind(), given.goalId(), given.text(), given.suggestions(),
                given.dedupeKey(), provider, given.instanceId(), given.decisions(), given.basis());
        String suggestion = post.suggestions().stream().map(a -> executor.describe(a, a.goal()))
                .collect(Collectors.joining("; "));
        InboxService.Posted posted = inbox.offer(post, suggestion.isEmpty() ? null : suggestion);
        if (posted.held()) {
            return Reply.ok(Map.of("held", true, "reason", HELD_REASON));
        }
        return posted.message().<Reply>map(Reply::ok).orElseGet(() -> Reply.ok(Map.of("duplicate", true)));
    }

    /**
     * Why a post was held back rather than stored: its provider already has
     * {@link InboxService#MAX_UNREAD_PER_PROVIDER} unread messages and the post replaces none of them. Unlike a
     * duplicate, the same observation can be posted again once fewer are unread: the person answered or dismissed some
     * of them, or some expired. Reading a message leaves it unread.
     */
    static final String HELD_REASON = InboxService.MAX_UNREAD_PER_PROVIDER
            + " unread messages from you; try again later";

    /** Why a provider's post is refused before anything is looked up: its size, or a kind or key the kernel owns. */
    static Optional<String> postProblem(Inbox.Post post) {
        if (post.text() != null && post.text().length() > MAX_POST_TEXT) {
            return Optional.of("an inbox message may have at most " + MAX_POST_TEXT + " characters");
        }
        if (post.suggestions().size() > MAX_SUGGESTIONS) {
            return Optional.of("an inbox message may carry at most " + MAX_SUGGESTIONS + " suggestions");
        }
        return reservedProblem(post);
    }

    /**
     * Why a suggestion of a provider's post cannot be stored: an action the person may not be offered from the inbox
     * ({@link InboxService#SUGGESTIBLE}), an action on another goal than the message's, or a goal move without the
     * new due date: a stored move always names the date the person is asked to accept.
     */
    static Optional<String> suggestionProblem(Inbox.Post post) {
        for (Conversation.Action a : post.suggestions()) {
            if (a == null || !InboxService.SUGGESTIBLE.contains(a.type())) {
                return Optional.of((a == null ? "an empty suggestion" : a.type()) + " cannot be suggested");
            }
            if (post.goalId() == null || !post.goalId().equals(a.goal())) {
                return Optional.of("suggestions must concern the message's goal");
            }
            if (a.type() == Conversation.ActionType.RESCHEDULE_GOAL && a.dueDate() == null) {
                return Optional.of("refused: a suggestion to move a goal must give the new due date");
            }
        }
        return Optional.empty();
    }

    /** Why a provider may not post this message: a kernel-owned kind or dedupe-key prefix. */
    static Optional<String> reservedProblem(Inbox.Post post) {
        if (post.kind() == null || KERNEL_KINDS.contains(post.kind())) {
            return Optional.of("only the kernel posts " + (post.kind() == null ? "messages without a kind"
                    : post.kind().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " messages"));
        }
        String key = post.dedupeKey() == null ? "" : post.dedupeKey().strip().toLowerCase(Locale.ROOT);
        return KERNEL_KEY_PREFIXES.stream().filter(key::startsWith).findFirst()
                .map(p -> "the key prefix \"" + p + "\" is reserved for the kernel");
    }

    /**
     * Records the decision on a proposal under the kernel's id for it ({@code causation}). The provider is the
     * registry's, never the one the proposal claims; {@code counts} is false for rule holds. Every accepted proposal
     * becomes a fact; rejected ones only within {@link #rejections}, so an agent sending refused proposals in a loop
     * cannot fill the goal's history (the next recorded rejection says how many were left out).
     */
    private Reply decideProposal(Proposal proposal, String causation, CapabilityManifest from, String goalId,
                                 boolean accepted, String reason, boolean counts) {
        if (counts) {
            reputation.recordProposal(proposal.capability(), from.provider(), accepted);
        }
        if (goalId != null && ledger.load(goalId).isPresent()) {
            OptionalInt recorded = accepted ? OptionalInt.of(0)
                    : rejections.admit(from.instanceId(), Instant.now());
            if (recorded.isPresent()) {
                String detail = recorded.getAsInt() == 0 ? reason
                        : reason + " (and " + recorded.getAsInt() + " more rejected proposals not recorded)";
                ledger.record(goalId, Facts.PROPOSAL_DECIDED, new Facts.ProposalDecided(causation, goalId,
                        proposal.capability(), proposal.instanceId(), accepted, detail), "kernel", causation);
            }
        }
        return accepted ? Reply.ok(reason) : Reply.error(reason);
    }

    /** Rejected proposals recorded as facts: one per instance every 6 s, 30 a minute in all. */
    private final RefusalBudget rejections = new RefusalBudget(30, Duration.ofSeconds(6));

    /** Who a control command is recorded as: the verified key's principal, or with security off the payload's. */
    private static String principal(Message message, SecurityService.Caller caller) {
        if (caller.principal() != null) {
            return caller.principal(); // the verified key decides, never the payload
        }
        if (message.getData().length == 0) {
            return "human:unknown";
        }
        KernelApi.ControlCommand command = Json.read(message.getData(), KernelApi.ControlCommand.class);
        return command.principal() == null ? "human:unknown" : command.principal();
    }

    /** The goal a request names by full id or unique id prefix. */
    private Optional<String> goalRef(Message message) {
        return ledger.resolveGoal(ref(message));
    }

    /** A goal id as the kernel makes them (a UUID in lower case); agents name goals only this way. */
    static final Pattern GOAL_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * The goal whose context a {@code query.context} request asks for. An agent names it by its whole id, as signals
     * and the goal list give it, and gets only an open goal: a completed or cancelled goal is unknown to agents, as
     * it is in their goal list ({@link #forAgents}). The kernel itself, and every caller while security is off, may
     * name any goal by its id or a unique prefix of it.
     */
    Optional<GoalState> contextGoal(Message message, SecurityService.Caller caller) {
        if (caller.role() != SecurityService.Role.AGENT) {
            return goalRef(message).flatMap(ledger::load);
        }
        String ref = ref(message);
        if (ref == null || !GOAL_ID.matcher(ref).matches()) {
            return Optional.empty();
        }
        return ledger.load(ref).filter(g -> g.status() != GoalStatus.COMPLETED && g.status() != GoalStatus.CANCELLED);
    }

    private static String ref(Message message) {
        return Json.read(message.getData(), KernelApi.Ref.class).ref();
    }

    @Override
    public void stop() {
        running = false;
        if (dispatcher != null) {
            connection.closeDispatcher(dispatcher);
        }
        workers.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 10;
    }
}
