package dev.capabilityfabric.kernel.security;

import dev.capabilityfabric.kernel.inbox.InboxService;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.protocol.Admission;
import dev.capabilityfabric.protocol.AutonomyLevel;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Hashing;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.SignedManifest;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.FabricSecurity;
import dev.capabilityfabric.sdk.FabricProperties;
import io.nats.client.Message;
import io.nats.client.impl.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Who may do what, without accounts. Every message names its sender by public key and is signed; the kernel knows
 * three kinds of keys: the owner's devices (paired with a one-time code the kernel shows), agents (admitted by the
 * owner, each with a grant that pins the contract it may serve and the vocabulary it may write), and guests (who
 * claimed an invitation, or hold a grant an owner device or a guest narrowed and handed on). Anyone else is
 * unknown: they can ask to pair, to join or to be admitted, and nothing more.
 */
@Component
public class SecurityService {

    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);

    public enum Role { OWNER, GUEST, AGENT, KERNEL, UNKNOWN, ANONYMOUS }

    /** What a guest may do unless the invitation says "view only" ({@link Grant.Rights#GUEST_ACTIONS}). */
    public static final List<ActionType> GUEST_ACTIONS = Grant.Rights.GUEST_ACTIONS;
    /** What a guest invited "view only" may do ({@link Grant.Rights#VIEW_ACTIONS}). */
    public static final List<ActionType> VIEW_ACTIONS = Grant.Rights.VIEW_ACTIONS;
    /**
     * Actions that govern the system rather than a goal: pairing, invitations, admission and revocation, rules, the
     * emergency stop, the person's profile and inbox, step approvals and the lifecycle of capabilities. Only the
     * owner's devices may take them, whatever a grant lists.
     */
    public static final Set<ActionType> ADMIN_ACTIONS = Set.of(ActionType.PAIR_DEVICE, ActionType.INVITE,
            ActionType.ADMIT_PROVIDER, ActionType.REVOKE_ACCESS, ActionType.LIST_ACCESS, ActionType.ADD_RULE,
            ActionType.REMOVE_RULE, ActionType.PAUSE, ActionType.RESUME, ActionType.CORRECT_PROFILE,
            ActionType.SHOW_PROFILE, ActionType.SHOW_INBOX, ActionType.ANSWER_MESSAGE, ActionType.APPROVE_STEP,
            ActionType.REJECT_STEP, ActionType.REQUEST_CAPABILITY, ActionType.KEEP_CAPABILITY,
            ActionType.RETIRE_CAPABILITY);

    /** The verified sender of a message. {@code grant} limits a guest; {@code provider} names an agent. */
    public record Caller(Role role, String keyId, String publicKey, String name, Grant grant, String provider,
                         Instant since) {

        public static Caller anonymous() {
            return new Caller(Role.ANONYMOUS, null, null, null, null, null, null);
        }

        /** The principal recorded with every change; {@code null} when security is off (the payload's is used). */
        public String principal() {
            return switch (role) {
                case OWNER -> "human:" + name;
                case GUEST -> "guest:" + name;
                case AGENT -> "agent:" + provider;
                case KERNEL -> "kernel";
                default -> null;
            };
        }

        /** The owner's devices, and every caller while security is off. */
        public boolean owner() {
            return role == Role.OWNER || role == Role.ANONYMOUS;
        }

        /**
         * What this caller may do with an action on a goal. Owners may do everything; a guest only what its grant
         * lists (a grant without a list allows {@link #GUEST_ACTIONS}) on the goals it names, and never an
         * {@link #ADMIN_ACTIONS admin action}.
         */
        public boolean may(ActionType action, String goalId) {
            if (owner()) {
                return true;
            }
            if (role != Role.GUEST || grant == null || ADMIN_ACTIONS.contains(action)) {
                return false;
            }
            return actions().contains(action) && (goalId == null || grant.rights().allowsGoal(goalId));
        }

        /** The kinds of action the caller's grant covers ({@link #GUEST_ACTIONS} when it lists none). */
        public List<ActionType> actions() {
            if (grant == null || grant.rights() == null) {
                return List.of();
            }
            return grant.rights().actions() == null ? GUEST_ACTIONS : grant.rights().actions();
        }

        /** Whether the caller may see the goal: owners see every goal, a guest only those its grant names. */
        public boolean seesGoal(String goalId) {
            return owner() || role == Role.GUEST && grant != null && grant.rights().allowsGoal(goalId);
        }
    }

    /**
     * A verified caller, or why the message was refused. {@code problem} says why a signed but unknown caller has
     * no standing (for example a revoked or expired grant); it is {@code null} for a key the kernel never saw.
     */
    public record Verdict(Caller caller, String refusal, String problem) {

        public Verdict(Caller caller, String refusal) {
            this(caller, refusal, null);
        }

        public boolean refused() {
            return refusal != null;
        }
    }

    /**
     * An agent's request for admission as the owner is asked to judge it; {@code contractHash} binds the answer.
     * Besides the capabilities and what they write ({@code produces}) it carries what they read ({@code reads}: every
     * token they consume or require), the capabilities with side effects outside the ledger ({@code sideEffects}, like
     * "notify.send (external)"), every capability's terms in words ({@code terms}, see {@link Contract#describe()};
     * {@code null} for a request recorded before contracts were kept), what the kernel itself gives the capabilities it
     * feeds ({@code kernelSupplied}, like "intent.interpret: your sentences, ...", see
     * {@link Contract#KERNEL_SUPPLIED}) and the capabilities with the names they give themselves ({@code offers}, like
     * {@code task.estimate "Effort estimator"}, see {@link Contract#offers()}; the bare ids of {@code capabilities}
     * when the request recorded no names). The names come from the agent and are shown to the interpreter, so a
     * question about admitting the agent shows {@code offers} rather than the bare ids.
     */
    public record PendingAdmission(String keyId, String provider, List<String> capabilities, List<String> produces,
                                   String contractHash, List<String> reads, List<String> sideEffects, String terms,
                                   List<String> kernelSupplied, List<String> offers) {

        public PendingAdmission {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            reads = reads == null ? List.of() : List.copyOf(reads);
            sideEffects = sideEffects == null ? List.of() : List.copyOf(sideEffects);
            kernelSupplied = kernelSupplied == null ? List.of() : List.copyOf(kernelSupplied);
            offers = offers == null || offers.isEmpty() ? capabilities : List.copyOf(offers);
        }

        /** A request whose capabilities are shown by their ids. */
        public PendingAdmission(String keyId, String provider, List<String> capabilities, List<String> produces,
                                String contractHash, List<String> reads, List<String> sideEffects, String terms,
                                List<String> kernelSupplied) {
            this(keyId, provider, capabilities, produces, contractHash, reads, sideEffects, terms, kernelSupplied,
                    null);
        }

        /** A request for capabilities the kernel gives nothing beyond the goal context. */
        public PendingAdmission(String keyId, String provider, List<String> capabilities, List<String> produces,
                                String contractHash, List<String> reads, List<String> sideEffects, String terms) {
            this(keyId, provider, capabilities, produces, contractHash, reads, sideEffects, terms, List.of());
        }

        /** A request without its reads, side effects and terms. */
        public PendingAdmission(String keyId, String provider, List<String> capabilities, List<String> produces,
                                String contractHash) {
            this(keyId, provider, capabilities, produces, contractHash, List.of(), List.of(), null, List.of());
        }
    }

    /**
     * Someone with access, as found from the person's words: a key id (or invitation id), how to show it and what
     * kind of holder it is ({@code null} when not known).
     */
    public record Holder(String keyId, String label, HolderKind kind) {

        public Holder(String keyId, String label) {
            this(keyId, label, null);
        }

        /**
         * The exact reference to this holder, {@code <kind>:<id>} such as {@code agent:3f9a11c277b0d41e}: given to
         * {@link #revoke} or {@link #match}, it means this one row of this kind and nothing else, whatever names
         * other holders have. Without a kind it is the bare id.
         */
        public String ref() {
            return kind == null ? keyId : kind.name().toLowerCase(Locale.ROOT) + ":" + keyId;
        }
    }

    /** A row of the devices table. */
    record DeviceRow(Role role, String name, Grant grant, Instant pairedAt, boolean revoked) {
    }

    /**
     * An admitted agent: its provider name, the grant it was admitted with and the contract the owner admitted
     * ({@code null} for an admission recorded before contracts were kept, which covers capability ids and written
     * tokens only).
     */
    record AdmittedRow(String provider, Grant grant, Contract contract) {

        AdmittedRow(String provider, Grant grant) {
            this(provider, grant, null);
        }
    }

    /**
     * Who a key is, why it has no standing when it has none, and the sender whose allowance its requests use
     * ({@link #KEY_REQUESTS_PER_MINUTE}): the key itself, or {@code grant:<root grant id>} for a delegate.
     */
    private record Found(Caller caller, String problem, String sender) {

        Found(Caller caller, String problem) {
            this(caller, problem, caller.publicKey());
        }
    }

    /** The kinds of holder {@link #match} finds: a paired device (owner or guest), an agent, an open invitation. */
    public enum HolderKind { DEVICE, AGENT, INVITATION }

    /**
     * Someone with access as {@link #match} considers them: the holder, its lower-case name and its kind. A holder
     * given without a kind gets this one.
     */
    record Candidate(Holder holder, String name, HolderKind kind) {

        Candidate {
            if (holder.kind() == null) {
                holder = new Holder(holder.keyId(), holder.label(), kind);
            }
        }
    }

    /** How long a pairing code is valid. */
    static final Duration CODE_LIFETIME = Duration.ofMinutes(30);
    /**
     * At most this many agents wait for admission at once, however long ago they asked; further requests are not
     * stored or shown until the owner decided on one or a waiting request expires ({@link #ADMISSION_EXPIRY}).
     */
    static final int MAX_PENDING_ADMISSIONS = 20;
    /**
     * At most this many keys wait for admission under one provider name; another key asking under that name is not
     * stored or shown until the owner decided on one of them, so one agent's NKey cannot fill the owner's inbox with
     * requests under a familiar name.
     */
    static final int MAX_PENDING_PER_PROVIDER = 2;
    /**
     * At most this many admission messages are unread in the owner's inbox (new agents and admitted agents asking
     * for more together); a key that has none unread gets none until the owner answered some.
     */
    static final int MAX_UNREAD_ADMISSIONS = 20;
    /**
     * A request for admission that has not been asked again for this long is dropped with its unread message: the
     * agent is gone, and asking again starts over.
     */
    static final Duration ADMISSION_EXPIRY = Duration.ofDays(7);
    static final int REFUSALS_PER_MINUTE = 20;
    /**
     * Requests a minute on one subject from keys the kernel does not know ({@link #knownKey}), all such keys
     * together; a subject the gateway forwards has a second budget for what arrives through the gateway. A request
     * beyond it is refused before the database is asked who sent it.
     */
    public static final int STRANGER_REQUESTS_PER_MINUTE = 600;
    /**
     * Requests a minute from keys the kernel does not know on all subjects together. A flood on one subject is cut
     * at that subject's budget first, so it uses at most {@link #STRANGER_REQUESTS_PER_MINUTE} of this one; only a
     * flood on many subjects at once reaches it. It bounds what strangers can make the replay guard keep
     * ({@link #STRANGER_REPLAY_ENTRIES}).
     */
    static final int STRANGER_REQUESTS_IN_ALL_PER_MINUTE = 10 * STRANGER_REQUESTS_PER_MINUTE;
    static final String TOO_MANY_STRANGERS = "too many requests from keys the kernel does not know; try again in a "
            + "minute";
    /**
     * Requests a minute one known sender may send on all subjects together ({@link #knownKey}; the kernel's own key
     * is not counted), as an {@link Allowance} of {@link #KEY_REQUEST_BURST} at once refilled at this pace. It is
     * more than an admitted agent sends when it uses each of its quotas per subject in full together with a
     * heartbeat a second; people's devices send far fewer. A sender is one key of a device or an agent; every
     * delegate of one grant (keys holding grants narrowed from the same root grant, {@link Grant#root()}) is one
     * sender together, so handing on grants to new keys does not multiply the allowance.
     */
    public static final int KEY_REQUESTS_PER_MINUTE = 3_000;
    /** Requests one known sender may send at once before {@link #KEY_REQUESTS_PER_MINUTE} paces it. */
    static final int KEY_REQUEST_BURST = 600;
    static final String TOO_MANY_REQUESTS = "too many requests from this sender; try again in a minute";
    /** How long the replay guard keeps a message it accepted: twice {@link Security#CLOCK_SKEW} after it arrived. */
    static final Duration REPLAY_RETENTION = Security.CLOCK_SKEW.multipliedBy(2);
    /**
     * Entries the replay guard keeps for one signing key: what a sender spending its whole allowance sends while the
     * guard keeps an entry ({@link #REPLAY_RETENTION}), so a known sender is held back by its allowance and not by
     * the guard.
     */
    static final int REPLAY_ENTRIES_PER_KEY = KEY_REQUEST_BURST
            + (int) (KEY_REQUESTS_PER_MINUTE * REPLAY_RETENTION.toSeconds() / 60);
    /**
     * The most entries keys the kernel does not know can make the replay guard keep: what the budget for strangers
     * in all ({@link #STRANGER_REQUESTS_IN_ALL_PER_MINUTE}) lets through while an entry is kept, with one minute
     * more because that budget counts a sliding minute.
     */
    static final int STRANGER_REPLAY_ENTRIES = STRANGER_REQUESTS_IN_ALL_PER_MINUTE
            * (int) (REPLAY_RETENTION.toMinutes() + 1);
    /**
     * Room in the replay guard that strangers can never take: the guard holds {@link #STRANGER_REPLAY_ENTRIES} and
     * this much more, as much as eight known senders spending their whole allowance keep, so owner devices (also
     * through the gateway), guests, agents and the kernel are not refused for a full guard however many requests
     * strangers send. One guard serves every key, so a request first seen while its key was unknown is still a
     * replay once the key is known.
     */
    static final int KNOWN_REPLAY_ENTRIES = 8 * REPLAY_ENTRIES_PER_KEY;
    /**
     * How long the kernel goes by the keys it read from its tables ({@link #keysOnRecord}) before it reads them again.
     */
    static final Duration RECORDED_KEYS_REFRESH = Duration.ofSeconds(30);
    /** At most this many keys identified between two readings of the tables are remembered besides them. */
    static final int MAX_IDENTIFIED_KEYS = 1_000;
    /**
     * One key's request for one contract is recorded at most once in this window; asking more often changes nothing.
     */
    static final Duration ASK_INTERVAL = Duration.ofSeconds(30);
    /** One key's admission requests are recorded at most this often per minute, whatever contracts they ask for. */
    static final int ASKS_PER_KEY_PER_MINUTE = 4;
    /** Admission requests under one provider name are recorded at most this often per minute, all keys together. */
    static final int ASKS_PER_PROVIDER_PER_MINUTE = 10;
    /**
     * The latest requests of at most this many keys are remembered ({@link RecentAdmissions}), stored or not, so the
     * owner can admit an agent by the fingerprint in its log while the waiting list is full.
     */
    static final int MAX_RECENT_REQUESTS = 256;
    /**
     * The remembered requests together keep at most this many characters ({@link RecentAdmissions.Request#weight()}),
     * so keys that ask with large contracts cannot make the kernel hold much memory for requests nobody stored.
     */
    static final long MAX_RECENT_CHARS = 2L * 1024 * 1024;
    static final String INVITE_PREFIX = "fabric-invite:";
    private static final Pattern PROVIDER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    /** A capability id as the manifest schema allows it (at most {@link #MAX_ID_LENGTH} characters). */
    private static final Pattern CAPABILITY_ID = Pattern.compile("[a-z][a-z0-9]*(\\.[a-z][a-zA-Z0-9-]*)+");
    private static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    /** A capability version as the manifest schema allows it: three numbers of at most nine digits. */
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}");
    static final int MAX_ID_LENGTH = 80;
    /** At most this many capabilities in one admission request, and this many tokens in each of their lists. */
    static final int MAX_CAPABILITIES = 32;
    static final int MAX_TOKENS = 40;
    static final int MAX_TOKEN_LENGTH = 120;
    /** A capability's name and description may have at most this many characters (as the manifest schema says). */
    static final int MAX_CAPABILITY_NAME = 120;
    static final int MAX_DESCRIPTION = 1000;
    /** A control or format character: line breaks, terminal escape sequences, direction overrides. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    /**
     * A capability may declare at most {@link #MAX_TOKENS} annotation schemas, each at most this many characters as
     * JSON and nested at most {@link #MAX_SCHEMA_DEPTH} levels deep, and at most {@link #MAX_SCHEMAS_CHARS} characters
     * for all of them together. The built-in agents' schemas have a few hundred characters; a grown capability's
     * schema has at most 2000.
     */
    static final int MAX_SCHEMA_CHARS = 4096;
    static final int MAX_SCHEMAS_CHARS = 16 * 1024;
    static final int MAX_SCHEMA_DEPTH = 8;
    /** Device and guest names are kept to this many characters. */
    static final int MAX_NAME_LENGTH = 64;
    /**
     * The answer to a pairing attempt through the gateway that fails for any reason (no device paired yet, no live
     * code, a wrong code): the same words in every case, so nobody on the network learns whether an owner exists.
     */
    static final String NETWORK_PAIRING_REFUSED = "that code is wrong or has expired; the first device must be paired "
            + "on the machine that runs the system";
    static final String WRONG_CODE = "that code is wrong or has expired";
    private static final Set<String> RESERVED_PROVIDERS = Set.of("kernel");

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final InboxService inbox;
    private final FabricSecurity fabric;
    private final TrustService trust;
    private final String caFile;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Security.ReplayGuard replays = new Security.ReplayGuard(REPLAY_ENTRIES_PER_KEY,
            STRANGER_REPLAY_ENTRIES + KNOWN_REPLAY_ENTRIES);
    private final RefusalBudget refusals = new RefusalBudget(REFUSALS_PER_MINUTE, Duration.ofMinutes(1));
    /**
     * How often admission requests are written down and shown: one key's request for one contract once per
     * {@link #ASK_INTERVAL}, one key at most {@link #ASKS_PER_KEY_PER_MINUTE} times and one provider name at most
     * {@link #ASKS_PER_PROVIDER_PER_MINUTE} times a minute. There is no budget for all requests together, so keys
     * asking under other names cannot keep an agent's request from being recorded.
     */
    private final RefusalBudget askIntervals = new RefusalBudget(Integer.MAX_VALUE, ASK_INTERVAL);
    private final Quota keyAsks = new Quota(ASKS_PER_KEY_PER_MINUTE, Duration.ofMinutes(1));
    private final Quota providerAsks = new Quota(ASKS_PER_PROVIDER_PER_MINUTE, Duration.ofMinutes(1));
    private final RecentAdmissions recent = new RecentAdmissions(MAX_RECENT_REQUESTS, MAX_RECENT_CHARS);
    private final PairingAttempts pairings = new PairingAttempts();
    private final Quota strangers = new Quota(STRANGER_REQUESTS_PER_MINUTE, Duration.ofMinutes(1));
    private final Quota strangersInAll = new Quota(STRANGER_REQUESTS_IN_ALL_PER_MINUTE, Duration.ofMinutes(1));
    private final Allowance senders = new Allowance(KEY_REQUEST_BURST,
            Duration.ofMinutes(1).dividedBy(KEY_REQUESTS_PER_MINUTE));
    /** The keys of devices and admitted agents as last read ({@link #keysOnRecord}), and when that was. */
    private volatile Set<String> recordedKeys = Set.of();
    private volatile Instant recordedKeysRead = Instant.MIN;
    private final Object recordedKeysLock = new Object();
    /**
     * Keys identified as a device, a guest or an agent since the recorded keys were last read, each with the sender
     * whose {@link #KEY_REQUESTS_PER_MINUTE allowance} it uses: the key itself, or for a delegate its root grant.
     */
    private final Map<String, String> identifiedKeys = new ConcurrentHashMap<>();
    private final Security.SealKey sealKey;
    private volatile String pairingCode;
    private volatile Instant pairingCodeUntil;
    private volatile String caPin;

    /**
     * With security on, the kernel's X25519 seal key lives next to its signing key ({@code <key file>.seal}) and is
     * created on first start; with security off a key for this run only is used.
     */
    @Autowired
    public SecurityService(JdbcClient jdbc, LedgerService ledger, InboxService inbox, FabricSecurity fabric,
                           TrustService trust, FabricProperties properties, ApplicationEventPublisher events) {
        this(jdbc, ledger, inbox, fabric, trust, properties.nats().caFile(), Clock.systemUTC(), events,
                fabric.enabled() ? Security.SealKey.loadOrCreate(properties.security().sealPath("kernel"))
                        : Security.SealKey.generate());
    }

    SecurityService(JdbcClient jdbc, LedgerService ledger, InboxService inbox, FabricSecurity fabric,
                    TrustService trust, String caFile, Clock clock) {
        this(jdbc, ledger, inbox, fabric, trust, caFile, clock, event -> { });
    }

    SecurityService(JdbcClient jdbc, LedgerService ledger, InboxService inbox, FabricSecurity fabric,
                    TrustService trust, String caFile, Clock clock, ApplicationEventPublisher events) {
        this(jdbc, ledger, inbox, fabric, trust, caFile, clock, events, Security.SealKey.generate());
    }

    /**
     * {@code caFile} is the broker's certificate authority (PEM) pinned in invitations; {@code events} receives
     * {@link CapabilityRegistry.KeyRevoked} when an agent's access ends; {@code sealKey} opens what devices seal for
     * the kernel (pairing and invitation codes).
     */
    SecurityService(JdbcClient jdbc, LedgerService ledger, InboxService inbox, FabricSecurity fabric,
                    TrustService trust, String caFile, Clock clock, ApplicationEventPublisher events,
                    Security.SealKey sealKey) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.inbox = inbox;
        this.fabric = fabric;
        this.trust = trust;
        this.caFile = caFile;
        this.clock = clock;
        this.events = events;
        this.sealKey = sealKey;
    }

    public boolean enabled() {
        return fabric.enabled();
    }

    public Security.Identity identity() {
        return fabric.identity();
    }

    /** The public half of the kernel's seal key, which devices seal pairing and invitation codes for. */
    public String sealKeyText() {
        return sealKey.publicKeyText();
    }

    /**
     * What a device sealed for the kernel with {@link Security#seal} for {@code context} ({@link
     * Security#claimContext}: the plain subject it sent it on and its own signing key).
     *
     * @throws IllegalArgumentException when it was sealed for another key or context, or altered
     */
    public byte[] openSealed(String context, byte[] sealed) {
        return Security.open(sealKey, context, sealed);
    }

    // ---- verification ------------------------------------------------------------------------------------

    /**
     * Checks a message's signature, budget, freshness and nonce, and finds out who sent it. {@code subject} is the
     * plain subject the request was served on: the signature must cover it and the reply seal key the request names,
     * if any ({@link Security#requestScope}), so nobody on the way can add, swap or remove that key. A request whose
     * signature holds then counts on a budget before the replay guard remembers its nonce, so only requests within a
     * budget take room there: from a key the kernel does not know ({@link #knownKey}), one request of the subject's
     * budget for strangers ({@link #STRANGER_REQUESTS_PER_MINUTE}; what arrived through the gateway counts on a
     * budget of its own) and one of the budget for strangers in all ({@link #STRANGER_REQUESTS_IN_ALL_PER_MINUTE});
     * from a known key other than the kernel's own, one request of its sender's allowance ({@link
     * #KEY_REQUESTS_PER_MINUTE}). A request beyond its budget is refused before the database is asked who sent it; a
     * stale or repeated request still uses its budget. What strangers can make the replay guard keep leaves room for
     * every known key ({@link #KNOWN_REPLAY_ENTRIES}).
     */
    public Verdict verify(Message message, String subject) {
        if (!enabled()) {
            return new Verdict(Caller.anonymous(), null);
        }
        Optional<Security.Stamp> stamp = FabricSecurity.stampOf(message);
        if (stamp.isEmpty()) {
            return refuse(null, subject, "unsigned request");
        }
        if (!stamp.get().verifies(FabricSecurity.requestScope(message, subject), message.getData())) {
            return refuse(stamp.get(), subject, "invalid signature");
        }
        String key = stamp.get().key();
        Optional<String> over = overBudget(key, message, subject);
        if (over.isPresent()) {
            return refuse(stamp.get(), subject, over.get());
        }
        Optional<String> replay = replays.check(stamp.get());
        if (replay.isPresent()) {
            return refuse(stamp.get(), subject, replay.get());
        }
        Headers headers = message.getHeaders();
        Found found = find(key, headers == null ? null : headers.getFirst(Security.HEADER_GRANT));
        identified(key, found);
        return new Verdict(found.caller(), null, found.problem());
    }

    /**
     * Takes one request of the budget a verified request counts on (see {@link #verify}); empty when it is within
     * it, otherwise why it is refused. The kernel's own key counts on none.
     */
    private Optional<String> overBudget(String key, Message message, String subject) {
        if (key.equals(identity().publicKeyText())) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<String> sender = sender(key);
        if (sender.isEmpty()) {
            return strangers.take(strangerBudget(message, subject), now) && strangersInAll.take("", now)
                    ? Optional.empty() : Optional.of(TOO_MANY_STRANGERS);
        }
        return senders.take(sender.get(), now) ? Optional.empty() : Optional.of(TOO_MANY_REQUESTS);
    }

    /** The stranger budget a request counts on: its plain subject, or its gateway subject when it came that way. */
    static String strangerBudget(Message message, String subject) {
        String arrived = message.getSubject();
        return arrived != null && arrived.startsWith(Subjects.GATEWAY_PREFIX) ? Subjects.viaGateway(subject) : subject;
    }

    /**
     * Whether the kernel knows {@code key} without asking the database now: its own key, a key of a device whose
     * access was not revoked or of an admitted agent as the tables said at their last reading (read again when
     * older than {@link #RECORDED_KEYS_REFRESH}), or a key identified as a device, a guest or an agent since then.
     * It decides only which budget a request counts on (the strangers' or its sender's); who the sender is, and what
     * it may do, is always looked up.
     */
    boolean knownKey(String key) {
        return key.equals(identity().publicKeyText()) || sender(key).isPresent();
    }

    /**
     * The sender whose allowance a key other than the kernel's uses ({@link #KEY_REQUESTS_PER_MINUTE}): the key itself
     * when the tables held it at their last reading (read again when older than {@link #RECORDED_KEYS_REFRESH}), or
     * what it was identified as since; empty for a key the kernel does not know.
     */
    private Optional<String> sender(String key) {
        Instant now = clock.instant();
        if (!recordedKeysRead.plus(RECORDED_KEYS_REFRESH).isAfter(now)) {
            synchronized (recordedKeysLock) {
                if (!recordedKeysRead.plus(RECORDED_KEYS_REFRESH).isAfter(now)) {
                    try {
                        recordedKeys = keysOnRecord();
                        identifiedKeys.clear();
                    } catch (RuntimeException e) {
                        log.debug("cannot read the keys of devices and agents: {}", e.getMessage());
                    }
                    recordedKeysRead = now;
                }
            }
        }
        return recordedKeys.contains(key) ? Optional.of(key) : Optional.ofNullable(identifiedKeys.get(key));
    }

    /** The public keys of devices whose access was not revoked and of admitted agents. */
    Set<String> keysOnRecord() {
        List<String> keys = jdbc.sql("""
                SELECT public_key FROM devices WHERE revoked_at IS NULL
                UNION SELECT public_key FROM admissions WHERE status = 'ADMITTED'""").query(String.class).list();
        return keys == null ? Set.of() : keys.stream().filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Remembers a key found to be a device, a guest or an agent until the tables are read again (at most
     * {@link #MAX_IDENTIFIED_KEYS} of them), with the sender whose allowance it uses ({@link Found#sender()}), and
     * forgets one found unknown.
     */
    private void identified(String key, Found found) {
        Role role = found.caller().role();
        if (role == Role.OWNER || role == Role.GUEST || role == Role.AGENT) {
            if (identifiedKeys.size() < MAX_IDENTIFIED_KEYS || identifiedKeys.containsKey(key)) {
                identifiedKeys.put(key, found.sender());
            }
        } else if (role == Role.UNKNOWN) {
            identifiedKeys.remove(key);
        }
    }

    /** Who a key is: the kernel, an owner device, a guest device, an admitted agent, a delegate, or unknown. */
    public Caller identify(String key, String grantHeader) {
        return find(key, grantHeader).caller();
    }

    /**
     * The device row decides first. A revoked device stays revoked: no grant it presents in a header counts, so a
     * grant it held earlier (or was handed) cannot bring it back. Rows are found by key id and the whole public key,
     * so another key with the same 64-bit key id is not taken for the device or agent.
     */
    private Found find(String key, String grantHeader) {
        String keyId = Security.keyId(key);
        if (key.equals(identity().publicKeyText())) {
            return new Found(new Caller(Role.KERNEL, keyId, key, "kernel", null, "kernel", null), null);
        }
        String problem = null;
        boolean revokedDevice = false;
        Optional<DeviceRow> device = device(keyId, key);
        if (device.isPresent() && !device.get().revoked()) {
            DeviceRow d = device.get();
            if (d.role() != Role.GUEST) {
                return new Found(new Caller(d.role(), keyId, key, d.name(), d.grant(), null, d.pairedAt()), null);
            }
            problem = d.grant() == null ? "your access is not valid: it carries no grant"
                    : grantProblem(d.grant(), key, false);
            if (problem == null) {
                return new Found(new Caller(Role.GUEST, keyId, key, d.name(), d.grant(), null, d.pairedAt()), null);
            }
        } else if (device.isPresent()) {
            problem = "the access of this device was revoked";
            revokedDevice = true;
        }
        Optional<AdmittedRow> admitted = admitted(keyId, key);
        if (admitted.isPresent()) {
            return new Found(new Caller(Role.AGENT, keyId, key, admitted.get().provider(), admitted.get().grant(),
                    admitted.get().provider(), null), null);
        }
        if (!revokedDevice && grantHeader != null && !grantHeader.isBlank()) {
            Optional<Grant> delegated = Grant.decode(grantHeader);
            if (delegated.isEmpty()) {
                return new Found(unknown(keyId, key), "the grant this device presents cannot be read");
            }
            String delegateProblem = grantProblem(delegated.get(), key, true);
            if (delegateProblem == null) {
                Grant g = delegated.get();
                return new Found(new Caller(Role.GUEST, keyId, key, delegateName(g), g, null, g.issuedAt()), null,
                        "grant:" + g.root().id());
            }
            problem = delegateProblem;
        }
        return new Found(unknown(keyId, key), problem);
    }

    /** A delegate is named after the person whose grant it came from, then its own name: "Ana/Ana's friend". */
    static String delegateName(Grant grant) {
        String leaf = grant.rights().name();
        if (grant.parent() == null) {
            return leaf;
        }
        return grant.root().rights().name() + "/" + leaf;
    }

    private static Caller unknown(String keyId, String key) {
        return new Caller(Role.UNKNOWN, keyId, key, null, null, null, null);
    }

    /**
     * Why a grant does not count for {@code holderKey}, in words for the person, or {@code null} when it counts: its
     * chain must verify back to the kernel or an owner device, nothing in it may have ended or been revoked, no link
     * may be held by a device whose access was revoked, and the key using it must be its holder. A {@code delegated}
     * grant must be a person's grant through its whole chain: no link of it, its root included, may be an agent's.
     */
    String grantProblem(Grant grant, String holderKey, boolean delegated) {
        if (grant.holder() == null || !grant.holder().equals(holderKey)) {
            return "your access is not valid: the grant belongs to another device";
        }
        if (grant.rights() == null || delegated && hasProviderLink(grant)) {
            return "your access is not valid: the grant is not a person's grant";
        }
        Instant now = clock.instant();
        for (Grant g = grant; g != null; g = g.parent()) {
            if (g.until() != null && now.isAfter(g.until())) {
                return "your access ended on " + date(g.until());
            }
        }
        List<String> problems = grant.verify(trustedRoots(), now);
        if (!problems.isEmpty()) {
            return "your access is not valid: " + plain(problems.getFirst());
        }
        if (anyRevoked(grant.chainIds()) || anyHolderRevoked(holders(grant))) {
            return "your access was revoked";
        }
        return null;
    }

    /**
     * Whether any link of a grant's chain, from the grant itself up to its root, is an agent's (or names no rights).
     */
    static boolean hasProviderLink(Grant grant) {
        for (Grant g = grant; g != null; g = g.parent()) {
            if (g.rights() == null || "provider".equals(g.rights().role())) {
                return true;
            }
        }
        return false;
    }

    /** The keys holding each link of a grant's chain, from the grant itself up to its root. */
    static List<String> holders(Grant grant) {
        List<String> keys = new ArrayList<>();
        for (Grant g = grant; g != null; g = g.parent()) {
            if (g.holder() != null) {
                keys.add(g.holder());
            }
        }
        return keys;
    }

    private static String plain(String problem) {
        if (problem.contains("too long")) {
            return "it was handed on too many times";
        }
        if (problem.contains("claims more") || problem.contains("lasts longer")) {
            return "it claims more than the grant it came from";
        }
        if (problem.contains("unknown key") || problem.contains("not issued by the holder")) {
            return "it was not issued by the owner";
        }
        return "its signature does not check out";
    }

    /** Keys whose root grants are trusted: the kernel and the owner's devices. */
    public Set<String> trustedRoots() {
        Set<String> roots = new HashSet<>(jdbc.sql("SELECT public_key FROM devices WHERE role = 'OWNER' AND revoked_at IS NULL")
                .query(String.class).list());
        roots.add(identity().publicKeyText());
        return roots;
    }

    /** The device row of a key (its key id and public key), revoked or not. */
    Optional<DeviceRow> device(String keyId, String publicKey) {
        return jdbc.sql("""
                SELECT role, name, grant_body, paired_at, revoked_at FROM devices
                WHERE key_id = :k AND public_key = :pub""")
                .param("k", keyId).param("pub", publicKey).query((rs, i) -> new DeviceRow(Role.valueOf(rs.getString("role")), rs.getString("name"),
                        Grant.decode(rs.getString("grant_body")).orElse(null),
                        rs.getObject("paired_at", OffsetDateTime.class).toInstant(), rs.getObject("revoked_at") != null))
                .optional();
    }

    /** The admission of a key (its key id and public key), only while it is admitted. */
    Optional<AdmittedRow> admitted(String keyId, String publicKey) {
        return jdbc.sql("""
                SELECT provider, grant_body, admitted_contract::text AS contract FROM admissions
                WHERE key_id = :k AND public_key = :pub AND status = 'ADMITTED'""")
                .param("k", keyId).param("pub", publicKey).query((rs, i) -> new AdmittedRow(rs.getString("provider"),
                        Grant.decode(rs.getString("grant_body")).orElse(null),
                        Contract.fromJson(rs.getString("contract")).orElse(null))).optional();
    }

    /** Whether any of these grants was revoked. */
    boolean anyRevoked(List<String> grantIds) {
        return jdbc.sql("SELECT count(*) FROM revoked_grants WHERE id IN (:ids)").param("ids", grantIds)
                .query(Integer.class).single() > 0;
    }

    /** Whether any of these public keys belongs to a device whose access was revoked. */
    boolean anyHolderRevoked(List<String> publicKeys) {
        if (publicKeys.isEmpty()) {
            return false;
        }
        return jdbc.sql("SELECT count(*) FROM devices WHERE public_key IN (:keys) AND revoked_at IS NOT NULL")
                .param("keys", publicKeys).query(Integer.class).single() > 0;
    }

    /** Whether the key ever asked for admission, whatever came of it. */
    boolean hasAdmissionRow(String keyId) {
        return jdbc.sql("SELECT count(*) FROM admissions WHERE key_id = :k").param("k", keyId)
                .query(Integer.class).single() > 0;
    }

    private Verdict refuse(Security.Stamp stamp, String scope, String why) {
        refused(stamp == null ? "unknown" : safeKeyId(stamp.key()), scope, why);
        return new Verdict(null, why);
    }

    /**
     * Writes down that a request of the key {@code keyId} on {@code scope} was refused for {@code why}, whether it
     * failed verification or a verified sender used up a quota: a log line and a {@link Facts#REQUEST_REFUSED} fact,
     * sampled by one budget for every refusal ({@link RefusalBudget}: the same key and reason at most once a minute,
     * {@link #REFUSALS_PER_MINUTE} a minute in all, the next recorded one saying how many were left out), so a flood
     * of refused requests cannot fill the log or the ledger.
     */
    public void refused(String keyId, String scope, String why) {
        OptionalInt recorded = refusals.admit(keyId + "|" + why, clock.instant());
        if (recorded.isPresent()) {
            String detail = recorded.getAsInt() == 0 ? why
                    : why + " (and " + recorded.getAsInt() + " more refusals not recorded)";
            log.warn("refused {} from {}: {}", scope, keyId, detail);
            ledger.recordSystem(Facts.REQUEST_REFUSED, new Facts.SecurityEvent(keyId, null, scope, detail), "kernel");
        }
    }

    private static String safeKeyId(String key) {
        try {
            return Security.keyId(key);
        } catch (RuntimeException e) {
            return "invalid-key";
        }
    }

    /**
     * Signs a reply so the sender can check it came from the kernel and answers its own request: the scope names the
     * request's subject and the nonce of the request's stamp ({@code ""} for an unsigned request).
     */
    public Headers replyHeaders(String subject, String requestNonce, byte[] body) {
        return fabric.headers(Security.replyScope(subject, requestNonce), body);
    }

    /**
     * The reply to a request on {@code subject}: signed like {@link #replyHeaders}, and sealed for
     * {@code replySealKey} with the reply's scope as context when the request named one, so only the requester can
     * read it (the gateway and other connections that see the reply subject get ciphertext).
     *
     * @throws IllegalArgumentException when {@code replySealKey} is not an X25519 public key
     */
    public FabricSecurity.SignedReply reply(String subject, String requestNonce, byte[] body, String replySealKey) {
        return fabric.reply(Security.replyScope(subject, requestNonce), body, replySealKey);
    }

    // ---- pairing -----------------------------------------------------------------------------------------

    /** Whether at least one device of the owner is paired and not revoked. */
    public boolean hasOwner() {
        Integer owners = jdbc.sql("SELECT count(*) FROM devices WHERE role = 'OWNER' AND revoked_at IS NULL")
                .query(Integer.class).single();
        return owners != null && owners > 0;
    }

    /** While no device is paired, the kernel keeps a one-time code in its log for the owner to type. */
    @Scheduled(initialDelay = 2_000, fixedDelay = 60_000)
    public void offerFirstPairing() {
        if (!enabled() || hasOwner()) {
            return;
        }
        if (pairingCode == null || pairingCodeUntil == null || pairingCodeUntil.isBefore(clock.instant())) {
            newPairingCode();
            log.warn("No device is paired yet. On your computer run:  fabric --pair {}   (valid for {} minutes; "
                    + "'docker compose logs kernel' shows this line again)", pairingCode, CODE_LIFETIME.toMinutes());
        }
    }

    /** A new single-use code for pairing another device of the owner. */
    public synchronized String newPairingCode() {
        pairingCode = Security.code();
        pairingCodeUntil = clock.instant().plus(CODE_LIFETIME);
        return pairingCode;
    }

    /** {@link #pair(String, String, String, boolean)} for a request on the machine's own broker. */
    public String pair(String key, String code, String deviceName) {
        return pair(key, code, deviceName, false);
    }

    /**
     * Pairs the device that signed the request as one of the owner's, if the code matches. Returns the problem, or
     * {@code null} when the device is now paired. The row of another public key with the same key id is left alone.
     *
     * <p>On the machine's own broker, wrong codes count only while a code is live: five from one key lock that key
     * out for 15 minutes, and when all keys together reach {@link PairingAttempts#GLOBAL} the code is withdrawn (a
     * new one is needed) instead of locking everyone out.
     *
     * <p>A request that came through the gateway ({@code viaGateway}) can pair only a further device: while no
     * device is paired, the first one must pair on the machine that runs the system. Every failure through the
     * gateway counts and gets {@link #NETWORK_PAIRING_REFUSED}, whatever its reason, so the answers never tell
     * whether an owner exists; their shared ceiling pauses pairing through the gateway and leaves the code alone.
     * A device paired through the gateway is announced in the owner's inbox with its name and fingerprint, so a code
     * someone else redeemed does not go unnoticed.
     */
    public synchronized String pair(String key, String code, String deviceName, boolean viaGateway) {
        Instant now = clock.instant();
        String keyId = Security.keyId(key);
        Optional<String> locked = pairings.refusal(keyId, now, viaGateway);
        if (locked.isPresent()) {
            return locked.get();
        }
        boolean live = pairingCode != null && !pairingCodeUntil.isBefore(now);
        if (viaGateway) {
            if (!live || !hasOwner() || !Security.same(pairingCode, normalise(code))) {
                if (pairings.failed(keyId, now, true)) {
                    networkPairingPaused();
                }
                return NETWORK_PAIRING_REFUSED;
            }
        } else {
            if (!live) {
                return WRONG_CODE; // nothing to guess, so nothing is counted
            }
            if (!Security.same(pairingCode, normalise(code))) {
                if (pairings.failed(keyId, now, false)) {
                    withdrawGuessedCode();
                }
                return WRONG_CODE;
            }
        }
        pairings.succeeded(keyId);
        String name = deviceName(deviceName, keyId);
        jdbc.sql("""
                INSERT INTO devices (key_id, public_key, role, name, paired_at) VALUES (:k, :pub, 'OWNER', :name, :at)
                ON CONFLICT (key_id) DO UPDATE SET role = EXCLUDED.role, name = EXCLUDED.name, revoked_at = NULL
                WHERE devices.public_key = EXCLUDED.public_key""")
                .param("k", keyId).param("pub", key).param("name", name)
                .param("at", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)).update();
        pairingCode = null;
        ledger.recordSystem(Facts.DEVICE_PAIRED, new Facts.SecurityEvent(keyId, name, "paired", Role.OWNER.name()),
                "human:" + name);
        log.info("device {} ({}) paired as {}", name, Security.fingerprint(key), Role.OWNER);
        if (viaGateway) {
            announce("paired:" + keyId, "The device \"" + name + "\" (key " + Security.fingerprint(key)
                    + ") was paired as one of your devices through the gateway. If this was not you, say: revoke "
                    + name, "device " + name + " paired through the gateway");
        }
        return null;
    }

    /**
     * A SECURITY message in the owner's inbox about something that happened through the gateway; {@code key} keeps
     * one message per event.
     */
    private void announce(String key, String text, String summary) {
        inbox.post(new Inbox.Post(Inbox.Kind.SECURITY, null, text, List.of(), "security:" + key, "kernel", "kernel",
                List.of(), null), summary);
    }

    /**
     * The name a device is stored and shown under: letters, digits, spaces, '.', '_' and '-' only, at most
     * {@link #MAX_NAME_LENGTH} characters, so it cannot carry terminal control sequences or line breaks; without a
     * usable name, "device-" and the start of its key id. A name that reads as a key ({@link #looksLikeKey}) is not
     * usable: "who has access?" would show another holder's key as this device's name.
     */
    public static String deviceName(String given, String keyId) {
        String name = given == null ? "" : given.replaceAll("[^A-Za-z0-9 ._-]", "").strip();
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH).strip();
        }
        return name.isEmpty() || looksLikeKey(name) ? "device-" + keyId.substring(0, 6) : name;
    }

    /**
     * Text from outside the kernel that is shown or recorded (a guest's name): without control or format characters
     * (no line breaks, terminal escape sequences or direction overrides), spaces collapsed, at most {@code max}
     * characters.
     */
    static String plainText(String text, int max) {
        if (text == null) {
            return null;
        }
        String plain = text.replaceAll("[\\p{Cc}\\p{Cf}]+", " ").replaceAll("\\s+", " ").strip();
        return plain.length() <= max ? plain : plain.substring(0, max).strip();
    }

    /**
     * Too many wrong codes were tried against the live code: it is withdrawn and the attempt is recorded. The owner
     * asks for a new code (or, before the first pairing, the kernel's log shows a new one within a minute).
     */
    private void withdrawGuessedCode() {
        pairingCode = null;
        pairingCodeUntil = null;
        String detail = PairingAttempts.GLOBAL + " wrong pairing codes within " + PairingAttempts.LOCKOUT.toMinutes()
                + " minutes; the code was withdrawn";
        log.warn("{}: ask for a new pairing code", detail);
        ledger.recordSystem(Facts.REQUEST_REFUSED, new Facts.SecurityEvent(null, null, Subjects.SECURITY_PAIR, detail),
                "kernel");
        if (hasOwner()) {
            inbox.post(new Inbox.Post(Inbox.Kind.SECURITY, null, "Someone tried " + PairingAttempts.GLOBAL
                    + " wrong pairing codes, so the open pairing code was withdrawn. To pair a device, ask for a new "
                    + "code (\"I want to use my laptop too\").", List.of(),
                    "security:pairing-withdrawn:" + date(clock.instant()), "kernel", "kernel", List.of(), null),
                    "pairing code withdrawn");
        }
    }

    /**
     * Too many wrong codes came through the gateway: pairing over the network is paused (the code stays valid on the
     * machine's own broker). The pause is recorded and, once a device is paired, the owner is told.
     */
    private void networkPairingPaused() {
        String detail = PairingAttempts.GLOBAL + " wrong pairing codes through the gateway within "
                + PairingAttempts.LOCKOUT.toMinutes() + " minutes; pairing over the network is paused";
        log.warn("{} for {} minutes", detail, PairingAttempts.LOCKOUT.toMinutes());
        ledger.recordSystem(Facts.REQUEST_REFUSED, new Facts.SecurityEvent(null, null, Subjects.SECURITY_PAIR, detail),
                "kernel");
        if (hasOwner()) {
            inbox.post(new Inbox.Post(Inbox.Kind.SECURITY, null, "Someone on the network tried "
                    + PairingAttempts.GLOBAL + " wrong pairing codes, so pairing through the gateway is paused for "
                    + PairingAttempts.LOCKOUT.toMinutes() + " minutes. Pairing on this machine still works.", List.of(),
                    "security:pairing-paused:" + date(clock.instant()), "kernel", "kernel", List.of(), null),
                    "network pairing paused");
        }
    }

    private static String normalise(String code) {
        String digits = code == null ? "" : code.replaceAll("[^0-9]", "");
        return digits.length() == 8 ? digits.substring(0, 4) + "-" + digits.substring(4) : code;
    }

    // ---- admission ---------------------------------------------------------------------------------------

    /**
     * An agent asks to take part. Admitted with a grant and a contract that cover what it now offers (the same
     * capabilities, reading, needing and writing nothing more, with the same side effects, names and annotation
     * schemas): it gets the grant. An admission recorded before contracts were kept counts when its grant covers the
     * capabilities and what they write and the terms asked now do no more than that grant shows the owner agreed to
     * ({@link Contract#withinLegacyGrant}: reading only the blackboard's structure and what it writes, no side
     * effects); then the contract asked now is recorded as the admitted one without asking again. Terms recorded
     * before names and annotation schemas were part of a contract get them recorded the same way. Asking for more,
     * or unknown: the owner is asked (an inbox message with the key's fingerprint and the contract) and the agent
     * waits. A request that is malformed (a reserved or malformed provider name, malformed ids or tokens, too many
     * entries) is refused without asking anyone.
     */
    public Admission.Answer admission(String key, Admission.Request request) {
        String keyId = Security.keyId(key);
        String fingerprint = Security.fingerprint(key);
        Optional<String> bad = requestProblem(request);
        if (bad.isPresent()) {
            return new Admission.Answer(Admission.Status.REJECTED, null, fingerprint, bad.get());
        }
        Contract asked = Contract.of(request.manifests());
        Optional<String> status = jdbc.sql("SELECT status FROM admissions WHERE key_id = :k AND public_key = :pub")
                .param("k", keyId).param("pub", key).query(String.class).optional();
        if (status.isPresent() && !"PENDING".equals(status.get())) {
            Admission.Status s = Admission.Status.valueOf(status.get());
            if (s == Admission.Status.ADMITTED) {
                Optional<AdmittedRow> row = admitted(keyId, key);
                if (row.isPresent() && !row.get().provider().equals(request.provider())) {
                    return new Admission.Answer(Admission.Status.REJECTED, null, fingerprint, "this key was admitted as \""
                            + row.get().provider() + "\"; another name needs another key");
                }
                if (row.isPresent() && covers(row.get(), asked)) {
                    Contract kept = row.get().contract();
                    if (kept == null || kept.incomplete()) {
                        recordAdmittedContract(keyId, kept == null ? asked : kept.completedWith(asked));
                    }
                    return new Admission.Answer(s, row.get().grant().encode(), fingerprint, "admitted");
                }
                Optional<String> notAsked = askOwner(keyId, key, request.provider(), asked, true);
                return new Admission.Answer(Admission.Status.PENDING, null, fingerprint,
                        "it now offers more than its admission covers; " + notAsked.orElse("the owner was asked"));
            }
            return new Admission.Answer(s, null, fingerprint, s == Admission.Status.REVOKED ? "access was revoked"
                    : "the owner refused this agent");
        }
        Optional<String> notAsked = askOwner(keyId, key, request.provider(), asked, false);
        return new Admission.Answer(Admission.Status.PENDING, null, fingerprint,
                notAsked.orElse("waiting for the owner"));
    }

    /**
     * Whether an admission covers what is asked now: its grant lists every capability and token, and the contract
     * the owner admitted allows every capability's terms; without a kept contract the terms must stay within what
     * the grant shows ({@link Contract#withinLegacyGrant}).
     */
    static boolean covers(AdmittedRow row, Contract asked) {
        return row.grant() != null && row.grant().rights() != null
                && covers(row.grant(), asked.ids(), asked.produces())
                && (row.contract() == null ? asked.withinLegacyGrant(row.grant().rights().produces())
                : row.contract().covers(asked));
    }

    /**
     * Stores {@code contract} as what an admitted key was admitted for. An unread admission message of that key is
     * settled with it: the agent asks for nothing its admission does not cover.
     */
    void recordAdmittedContract(String keyId, Contract contract) {
        jdbc.sql("UPDATE admissions SET admitted_contract = CAST(:c AS jsonb) WHERE key_id = :k AND status = 'ADMITTED'")
                .param("c", contract.toJson()).param("k", keyId).update();
        inbox.settleByKey("admission:" + keyId + ":", Inbox.Status.ACCEPTED, "kernel");
        log.info("recorded the contract of admitted agent {}: {}", keyId, contract.describe());
    }

    /**
     * What an admitted agent may read: every token the capabilities of its admitted contract consume or require.
     * Empty when the key is not admitted or its admission kept no contract.
     */
    public Optional<Set<String>> admittedReads(String key) {
        if (key == null || !Security.isCanonicalKey(key)) {
            return Optional.empty();
        }
        return admitted(Security.keyId(key), key).map(AdmittedRow::contract).map(Contract::reads);
    }

    /**
     * Why an admission request is refused without asking the owner: the provider name (see {@link #providerProblem}),
     * an instance id that is not a plain id, more than {@link #MAX_CAPABILITIES} capabilities, or a capability whose
     * provider, id or tokens are malformed.
     */
    static Optional<String> requestProblem(Admission.Request request) {
        Optional<String> name = providerProblem(request.provider());
        if (name.isPresent()) {
            return name;
        }
        if (request.instanceId() != null && !INSTANCE_ID.matcher(request.instanceId()).matches()) {
            return Optional.of("the instance id may use letters, digits and '-' only (at most 64)");
        }
        if (request.manifests().size() > MAX_CAPABILITIES) {
            return Optional.of("an agent may offer at most " + MAX_CAPABILITIES + " capabilities");
        }
        for (CapabilityManifest m : request.manifests()) {
            if (m == null || !request.provider().equals(m.provider())) {
                return Optional.of("every capability must name the provider that asks for admission");
            }
            Optional<String> problem = manifestProblem(m);
            if (problem.isPresent()) {
                return problem;
            }
        }
        return Optional.empty();
    }

    /**
     * Why a capability cannot be part of a contract: an id the manifest schema does not allow, a version that is not
     * three numbers of at most nine digits, a provider name or an instance id beyond letters, digits and a few
     * separators or longer than 64 characters, a list of more than {@link #MAX_TOKENS} tokens or with a token that is
     * not in the vocabulary, a name longer than {@link #MAX_CAPABILITY_NAME} characters or with a control or format
     * character (a line break, a terminal escape sequence, a direction override), a description longer than
     * {@link #MAX_DESCRIPTION} characters, or annotation schemas beyond their limits ({@link #schemaProblem}). What
     * passes is safe to show in the owner's inbox (ids and tokens have no spaces, line breaks or control characters,
     * names stay on one line) and small enough to keep, also in the facts that name the capability: a request is
     * checked before anything of it is remembered.
     */
    static Optional<String> manifestProblem(CapabilityManifest m) {
        if (m.id().length() > MAX_ID_LENGTH || !CAPABILITY_ID.matcher(m.id()).matches()) {
            return Optional.of("a capability id must be like \"task.estimate\" (at most " + MAX_ID_LENGTH
                    + " characters)");
        }
        if (m.version() != null && !VERSION.matcher(m.version()).matches()) {
            return Optional.of(m.id() + " has a version that is not like \"1.0.0\"");
        }
        if (m.provider() != null && !PROVIDER_NAME.matcher(m.provider()).matches()) {
            return Optional.of(m.id() + " names a provider that is not a plain name (at most 64 characters)");
        }
        if (m.instanceId() != null && !INSTANCE_ID.matcher(m.instanceId()).matches()) {
            return Optional.of(m.id() + " has an instance id other than letters, digits and '-' (at most 64)");
        }
        for (List<String> tokens : List.of(m.consumes(), m.requires(), m.produces())) {
            if (tokens.size() > MAX_TOKENS) {
                return Optional.of(m.id() + " lists more than " + MAX_TOKENS + " tokens");
            }
            for (String token : tokens) {
                if (token == null || token.length() > MAX_TOKEN_LENGTH || !Vocabulary.isValidToken(token)) {
                    return Optional.of(m.id() + " names a token outside the vocabulary");
                }
            }
        }
        if (m.name() != null && m.name().length() > MAX_CAPABILITY_NAME) {
            return Optional.of(m.id() + " has a name of more than " + MAX_CAPABILITY_NAME + " characters");
        }
        if (m.name() != null && CONTROL.matcher(m.name()).find()) {
            return Optional.of(m.id() + " has a name with a line break or another control character");
        }
        if (m.description() != null && m.description().length() > MAX_DESCRIPTION) {
            return Optional.of(m.id() + " has a description of more than " + MAX_DESCRIPTION + " characters");
        }
        return schemaProblem(m);
    }

    /**
     * Why a capability's annotation schemas cannot be part of a contract: more than {@link #MAX_TOKENS} of them, a
     * key that is not an annotation key, a schema of more than {@link #MAX_SCHEMA_CHARS} characters as JSON or
     * nested deeper than {@link #MAX_SCHEMA_DEPTH} levels, or more than {@link #MAX_SCHEMAS_CHARS} characters for all
     * of them.
     */
    static Optional<String> schemaProblem(CapabilityManifest m) {
        Map<String, JsonNode> schemas = m.annotationSchemas();
        if (schemas.size() > MAX_TOKENS) {
            return Optional.of(m.id() + " declares more than " + MAX_TOKENS + " annotation schemas");
        }
        long total = 0;
        for (Map.Entry<String, JsonNode> schema : schemas.entrySet()) {
            String key = schema.getKey();
            if (key == null || key.length() > MAX_TOKEN_LENGTH
                    || !Vocabulary.isValidAnnotationKey(key)) {
                return Optional.of(m.id() + " declares a schema for something that is not an annotation");
            }
            if (depth(schema.getValue(), MAX_SCHEMA_DEPTH + 1) > MAX_SCHEMA_DEPTH) {
                return Optional.of(m.id() + " declares a schema nested more than " + MAX_SCHEMA_DEPTH
                        + " levels deep");
            }
            int chars = schema.getValue() == null ? 0 : Json.write(schema.getValue()).length();
            if (chars > MAX_SCHEMA_CHARS) {
                return Optional.of(m.id() + " declares a schema of more than " + MAX_SCHEMA_CHARS + " characters");
            }
            total += chars;
        }
        if (total > MAX_SCHEMAS_CHARS) {
            return Optional.of(m.id() + " declares schemas of more than " + MAX_SCHEMAS_CHARS + " characters in all");
        }
        return Optional.empty();
    }

    /**
     * How deeply objects and arrays nest in {@code node}: 0 for a plain value, 1 for an object or array of plain
     * values. Stops counting at {@code limit}, so a very deep value costs no more than a value of that depth.
     */
    static int depth(JsonNode node, int limit) {
        if (node == null || !node.isContainer() || limit <= 0) {
            return 0;
        }
        int deepest = 0;
        for (JsonNode child : node) {
            deepest = Math.max(deepest, depth(child, limit - 1));
            if (deepest >= limit - 1) {
                break;
            }
        }
        return deepest + 1;
    }

    /**
     * Why a provider name cannot be admitted: missing, not a plain name, reserved for the kernel, or read as a key
     * ({@link #looksLikeKey}), since the owner names agents by their keys too and such a name would be taken for
     * another agent's key.
     */
    static Optional<String> providerProblem(String provider) {
        if (provider == null || provider.isBlank()) {
            return Optional.of("the agent gives no provider name");
        }
        if (!PROVIDER_NAME.matcher(provider).matches()) {
            return Optional.of("the provider name may use letters, digits, '.', '_' and '-' only (at most 64)");
        }
        if (looksLikeKey(provider)) {
            return Optional.of("the provider name \"" + provider + "\" reads as a key id or a fingerprint (hex "
                    + "digits and dashes only)");
        }
        if (RESERVED_PROVIDERS.contains(provider.toLowerCase(Locale.ROOT))) {
            return Optional.of("the provider name \"" + provider + "\" is reserved");
        }
        return Optional.empty();
    }

    /**
     * Hash of an admission recorded before contracts were kept (capability ids and the vocabulary they write),
     * independent of order. Admissions with a contract use {@link Contract#hash()}.
     */
    public static String contractHash(Collection<String> capabilities, Collection<String> produces) {
        return Hashing.sha256(Json.write(new TreeSet<>(capabilities)) + "|" + Json.write(new TreeSet<>(produces)));
    }

    /**
     * Records the request and asks the owner through the inbox. Every request is first remembered in
     * {@link RecentAdmissions}, so the owner can admit the key by its fingerprint whatever happens next. The same key
     * asking for the same contract is written down at most once per {@link #ASK_INTERVAL}, one key at most
     * {@link #ASKS_PER_KEY_PER_MINUTE} and one provider name at most {@link #ASKS_PER_PROVIDER_PER_MINUTE} times a
     * minute. A request of a key without a row while {@link #MAX_PENDING_ADMISSIONS} agents already wait, a request
     * under a name {@link #MAX_PENDING_PER_PROVIDER} other keys already wait under (also when a waiting key asks
     * under another name than before), and a request of a key without an unread admission message while
     * {@link #MAX_UNREAD_ADMISSIONS} are unread is not stored or shown. Returns why the request is not on record, or
     * empty when it is. The message shows every capability's terms and how many agents wait now, and its suggestion
     * carries the contract's hash in {@code evidence}, so accepting it admits exactly the contract it showed.
     */
    private Optional<String> askOwner(String keyId, String key, String provider, Contract asked, boolean widening) {
        if (providerProblem(provider).isPresent()) {
            return Optional.of("the provider name cannot be admitted");
        }
        Instant now = clock.instant();
        Optional<String> earlier = recent.get(keyId, now.minus(ADMISSION_EXPIRY)).map(RecentAdmissions.Request::problem);
        Optional<String> problem = recordRequest(keyId, key, provider, asked, widening, now, earlier);
        recent.put(RecentAdmissions.Request.of(keyId, key, provider, asked, now, problem.orElse(null)));
        return problem;
    }

    /** {@link #askOwner} after the request is remembered: writes it down and posts the message, or says why not. */
    private Optional<String> recordRequest(String keyId, String key, String provider, Contract asked, boolean widening,
                                           Instant now, Optional<String> earlier) {
        String hash = asked.hash();
        if (askIntervals.admit(keyId + "|" + hash, now).isEmpty()) {
            // Asked a moment ago (agents ask on every heartbeat): nothing new to record, the answer stays the same.
            return earlier.isPresent() || hasAdmissionRow(keyId) ? earlier : Optional.of(laterNote(key));
        }
        if (!keyAsks.take(keyId, now) || !providerAsks.take(provider.toLowerCase(Locale.ROOT), now)) {
            return hasAdmissionRow(keyId) ? Optional.empty()
                    : Optional.of("asking too often; asking again later" + byKeyNote(key));
        }
        boolean hasRow = hasAdmissionRow(keyId);
        if (!hasRow && pendingAdmissionCount() >= MAX_PENDING_ADMISSIONS) {
            log.debug("not recording the admission request of {} ({}): too many agents are waiting", provider, keyId);
            return Optional.of("too many agents are waiting for admission; asking again later" + byKeyNote(key));
        }
        boolean renamed = hasRow && waitingProvider(keyId, key).filter(p -> !p.equalsIgnoreCase(provider)).isPresent();
        if ((!hasRow || renamed) && pendingUnder(provider, keyId) >= MAX_PENDING_PER_PROVIDER) {
            log.debug("not recording the admission request of {} ({}): {} other keys wait under that name", provider,
                    keyId, MAX_PENDING_PER_PROVIDER);
            return Optional.of(MAX_PENDING_PER_PROVIDER + " other keys already wait for admission as \"" + provider
                    + "\"; asking again later" + byKeyNote(key));
        }
        if (!hasUnreadAdmissionMessage(keyId) && unreadAdmissionMessages() >= MAX_UNREAD_ADMISSIONS) {
            log.debug("not recording the admission request of {} ({}): {} admission messages are unread", provider,
                    keyId, MAX_UNREAD_ADMISSIONS);
            return Optional.of("the owner has " + MAX_UNREAD_ADMISSIONS + " unread admission messages; asking again "
                    + "later" + byKeyNote(key));
        }
        OffsetDateTime at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO admissions (key_id, public_key, provider, capabilities, produces, contract, status,
                    requested_at, asked_at)
                VALUES (:k, :pub, :provider, CAST(:caps AS jsonb), CAST(:prod AS jsonb), CAST(:contract AS jsonb),
                    'PENDING', :at, :at)
                ON CONFLICT (key_id) DO UPDATE SET capabilities = EXCLUDED.capabilities, produces = EXCLUDED.produces,
                    contract = EXCLUDED.contract, asked_at = EXCLUDED.asked_at,
                    provider = CASE WHEN admissions.status = 'ADMITTED' THEN admissions.provider ELSE EXCLUDED.provider END,
                    status = CASE WHEN admissions.status = 'ADMITTED' THEN 'ADMITTED' ELSE 'PENDING' END
                WHERE admissions.public_key = EXCLUDED.public_key""")
                .param("k", keyId).param("pub", key).param("provider", provider)
                .param("caps", Json.write(asked.ids())).param("prod", Json.write(asked.produces()))
                .param("contract", asked.toJson()).param("at", at).update();
        String text = "The agent \"" + provider + "\" " + (widening ? "now also asks" : "asks") + " to join with key "
                + Security.fingerprint(key) + ". It offers " + (asked.capabilities().isEmpty() ? "nothing"
                : asked.describe()) + "." + otherKeysNote(keyId, provider) + waitingNote(pendingAdmissionCount())
                + " Compare the key with the one in its log before you admit it.";
        // A request with another contract replaces the unread message of the same key (same dedupe prefix).
        Optional<Inbox.Message> posted = inbox.post(new Inbox.Post(Inbox.Kind.ADMISSION, null, text,
                List.of(new Conversation.Action(ActionType.ADMIT_PROVIDER, null, null, provider, null, null, null, keyId,
                        null, hash)), "admission:" + keyId + ":" + hash.substring(0, 12), "kernel", "kernel", List.of(),
                        null),
                "admit " + provider + " (key " + Security.fingerprint(key) + ")");
        if (posted.isPresent()) {
            ledger.recordSystem(Facts.PROVIDER_ADMISSION_REQUESTED, new Facts.SecurityEvent(keyId, provider, "asks",
                    String.join(",", asked.ids())), "agent:" + provider);
        }
        return Optional.empty();
    }

    /** " (the owner can still admit this agent by its key 3F9A-11C2-77B0-D41E)", for an agent's log. */
    private static String byKeyNote(String key) {
        return " (the owner can still admit this agent by its key " + Security.fingerprint(key) + ")";
    }

    /** Why a request not stored a moment ago is still not, when nothing more is known. */
    private static String laterNote(String key) {
        return "not shown to the owner yet; asking again later" + byKeyNote(key);
    }

    /** The provider name a key waits (or was refused) under, if it has such a row. */
    Optional<String> waitingProvider(String keyId, String key) {
        return jdbc.sql("""
                SELECT provider FROM admissions WHERE key_id = :k AND public_key = :pub
                    AND status IN ('PENDING', 'REJECTED')""")
                .param("k", keyId).param("pub", key).query(String.class).optional();
    }

    /** A sentence for the admission message when another key is already admitted under the same provider name. */
    String otherKeysNote(String keyId, String provider) {
        int others = jdbc.sql("SELECT count(*) FROM admissions WHERE provider = :p AND status = 'ADMITTED' AND key_id <> :k")
                .param("p", provider).param("k", keyId).query(Integer.class).single();
        return others == 0 ? "" : " Another key is already admitted as \"" + provider
                + "\"; admitting this one lets both act under that name.";
    }

    /** "3 agents are waiting for admission now." when more than one waits, otherwise nothing. */
    static String waitingNote(int waiting) {
        return waiting > 1 ? " " + waiting + " agents are waiting for admission now." : "";
    }

    /** Other keys waiting for admission under {@code provider}, however long ago they asked. */
    int pendingUnder(String provider, String keyId) {
        return jdbc.sql("""
                SELECT count(*) FROM admissions WHERE status = 'PENDING' AND lower(provider) = lower(:p)
                    AND key_id <> :k""")
                .param("p", provider).param("k", keyId).query(Integer.class).optional().orElse(0);
    }

    /** Agents waiting for admission, however long ago they asked. */
    int pendingAdmissionCount() {
        return jdbc.sql("SELECT count(*) FROM admissions WHERE status = 'PENDING'").query(Integer.class).optional()
                .orElse(0);
    }

    /** Unread admission messages in the owner's inbox (only the kernel posts them). */
    int unreadAdmissionMessages() {
        return jdbc.sql("SELECT count(*) FROM inbox WHERE status = 'UNREAD' AND kind = 'ADMISSION'")
                .query(Integer.class).optional().orElse(0);
    }

    /** Whether the owner has an unread admission message about this key (a new request replaces it). */
    boolean hasUnreadAdmissionMessage(String keyId) {
        return jdbc.sql("SELECT count(*) FROM inbox WHERE status = 'UNREAD' AND kind = 'ADMISSION' AND dedupe_key LIKE :p")
                .param("p", "admission:" + keyId + ":%").query(Integer.class).optional().orElse(0) > 0;
    }

    /**
     * Drops what agents that went away left waiting: a request for admission nobody asked again for
     * {@link #ADMISSION_EXPIRY} is removed, and its unread message expires with it; an admitted agent's unread
     * request for more expires the same way, its admission stays. An agent still running asks again on its next
     * heartbeat and starts over.
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void expireStaleAdmissions() {
        OffsetDateTime cutoff = OffsetDateTime.ofInstant(clock.instant().minus(ADMISSION_EXPIRY), ZoneOffset.UTC);
        List<Map<String, Object>> stale = jdbc.sql("""
                SELECT key_id, status FROM admissions WHERE status IN ('PENDING', 'ADMITTED')
                    AND COALESCE(asked_at, requested_at) < :cutoff""").param("cutoff", cutoff).query().listOfRows();
        for (Map<String, Object> row : stale) {
            String keyId = (String) row.get("key_id");
            if ("PENDING".equals(row.get("status"))) {
                jdbc.sql("DELETE FROM admissions WHERE key_id = :k AND status = 'PENDING'").param("k", keyId).update();
                log.info("dropped the admission request of {}: not asked again for {} days", keyId,
                        ADMISSION_EXPIRY.toDays());
            }
            inbox.settleByKey("admission:" + keyId + ":", Inbox.Status.EXPIRED, "kernel");
        }
    }

    /** Whether a provider grant lists every one of these capabilities and every token they write. */
    static boolean covers(Grant grant, Set<String> capabilities, Set<String> produces) {
        return grant.rights().capabilities() != null && grant.rights().capabilities().containsAll(capabilities)
                && grant.rights().produces() != null && grant.rights().produces().containsAll(produces);
    }

    /**
     * The owner admits the agent with this whole key ({@link #wholeKeyId}) without checking which contract they saw.
     */
    public Optional<String> admit(String keyRef, String principal) {
        return admit(keyRef, null, principal);
    }

    /**
     * The owner admits the agent whose whole key id or fingerprint is {@code keyRef} ({@link #wholeKeyId}): it gets
     * a grant for what it asked, and the contract it asked for is kept as the one admitted. Only the row of exactly
     * that key is looked at; a provider name or a key id prefix admits nothing, since any key can ask under any name
     * and a prefix is cheap to match with a made-up key ({@link #pendingAdmission} resolves those to the whole key
     * first, for the owner to see). With a {@code contractHash} (from {@link #pendingAdmission}), the admission is
     * refused when the agent has asked for a different contract since. A request the owner refused earlier can still
     * be admitted, and so can a key whose request was not stored (the waiting list or the inbox was full), with the
     * contract it asked for last ({@link RecentAdmissions}).
     */
    public Optional<String> admit(String keyRef, String contractHash, String principal) {
        Optional<String> whole = wholeKeyId(keyRef);
        if (whole.isEmpty()) {
            return Optional.of(notAWholeKey(keyRef));
        }
        Optional<Map<String, Object>> row = admissionByKey(whole.get());
        if (row.isEmpty()) {
            Optional<RecentAdmissions.Request> recorded = recordedRequest(whole.get());
            return recorded.isPresent() ? admitRecorded(recorded.get(), contractHash, principal)
                    : Optional.of("no agent is waiting for admission under \"" + keyRef + "\"");
        }
        String keyId = (String) row.get().get("key_id");
        String key = (String) row.get().get("public_key");
        String provider = (String) row.get().get("provider");
        Optional<Contract> contract = Contract.fromJson((String) row.get().get("contract"));
        List<String> capabilities = contract.map(c -> List.copyOf(c.ids()))
                .orElseGet(() -> List.of(Json.read((String) row.get().get("capabilities"), String[].class)));
        List<String> produces = contract.map(c -> List.copyOf(c.produces()))
                .orElseGet(() -> List.of(Json.read((String) row.get().get("produces"), String[].class)));
        String hash = contract.map(Contract::hash).orElseGet(() -> contractHash(capabilities, produces));
        if (contractHash != null && !contractHash.equals(hash)) {
            return Optional.of(provider + " changed what it asks for since you saw it; look at its new request first");
        }
        Grant grant = Grant.issue(identity(), key, Grant.Rights.provider(provider, capabilities, produces), null, null);
        int changed = jdbc.sql("""
                UPDATE admissions SET status = 'ADMITTED', grant_body = :g, admitted_contract = CAST(:c AS jsonb),
                    decided_at = :at, decided_by = :by
                WHERE key_id = :k AND public_key = :pub AND status IN ('PENDING', 'REJECTED', 'ADMITTED')""")
                .param("g", grant.encode()).param("c", contract.map(Contract::toJson).orElse(null))
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("by", principal).param("k", keyId).param("pub", key).update();
        if (changed == 0) {
            return Optional.of("the request of " + provider + " (key " + Security.fingerprint(key)
                    + ") ended meanwhile (revoked or expired); nothing was admitted");
        }
        admitted(keyId, key, provider, capabilities, principal);
        return Optional.empty();
    }

    /** Records that the owner admitted a key, settles its admission messages and forgets its remembered request. */
    private void admitted(String keyId, String key, String provider, List<String> capabilities, String principal) {
        ledger.recordSystem(Facts.PROVIDER_ADMITTED, new Facts.SecurityEvent(keyId, provider, "admitted",
                String.join(",", capabilities)), principal);
        inbox.settleByKey("admission:" + keyId + ":", Inbox.Status.ACCEPTED, principal);
        recent.forget(keyId);
        log.info("{} ({}) admitted by {} for {}", provider, Security.fingerprint(key), principal, capabilities);
    }

    /**
     * The request a key made last that was not stored, found by its whole key id or fingerprint (case, dashes and
     * spaces ignored), when it asked within {@link #ADMISSION_EXPIRY} and its contract was kept
     * ({@link RecentAdmissions#MAX_CONTRACT_CHARS}). A shorter prefix or a provider name finds nothing here: any key
     * can ask under any name, and a short prefix is cheap to match with a made-up key.
     */
    Optional<RecentAdmissions.Request> recordedRequest(String ref) {
        return wholeKeyId(ref).flatMap(id -> recent.get(id, clock.instant().minus(ADMISSION_EXPIRY)))
                .filter(r -> r.contractText() != null);
    }

    private static final Pattern KEY_ID = Pattern.compile("[0-9a-f]{16}");
    /** A key id prefix as the owner may type it: hex digits, with dashes where a fingerprint has them. */
    private static final Pattern KEY_PREFIX = Pattern.compile("[0-9a-f]+(-[0-9a-f]+)*");
    private static final Pattern KEY_LIKE = Pattern.compile("[0-9A-Fa-f\\s-]+");
    /** The fewest characters a key id (or invitation id) prefix needs to name a holder. */
    static final int MIN_ID_PREFIX = 4;

    /**
     * The whole key id {@code ref} names: its 16 hex digits, or the fingerprint form ({@code 3F9A-11C2-77B0-D41E}),
     * case, dashes and spaces ignored. Empty for anything else, such as a shorter prefix, a name or other words.
     */
    public static Optional<String> wholeKeyId(String ref) {
        if (ref == null) {
            return Optional.empty();
        }
        String id = ref.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s-]", "");
        return KEY_ID.matcher(id).matches() ? Optional.of(id) : Optional.empty();
    }

    /**
     * Whether {@code name} reads as a key: hex digits, dashes and spaces only, with at least {@link #MIN_ID_PREFIX}
     * hex digits, the way a key id, a key id prefix or a fingerprint is written. Wherever the owner names a holder
     * by its key, such a name would be taken for another holder's key.
     */
    static boolean looksLikeKey(String name) {
        return name != null && KEY_LIKE.matcher(name).matches()
                && name.chars().filter(c -> Character.digit(c, 16) >= 0).count() >= MIN_ID_PREFIX;
    }

    /** The refusal when an admission decision is given something other than a whole key ({@link #wholeKeyId}). */
    static String notAWholeKey(String ref) {
        return "an agent is admitted or refused by its whole key (its fingerprint, such as 3F9A-11C2-77B0-D41E), not by "
                + "\"" + plainText(ref == null ? "" : ref, MAX_NAME_LENGTH) + "\"";
    }

    /**
     * Admits a key from its remembered request: the contract it asked for becomes the admitted one, under the
     * provider name it asked with. Refused when {@code contractHash} names another contract, and when the key's
     * access was revoked meanwhile.
     */
    private Optional<String> admitRecorded(RecentAdmissions.Request request, String contractHash, String principal) {
        Optional<Contract> kept = request.contract();
        if (kept.isEmpty()) {
            return Optional.of("no agent is waiting for admission under \"" + Security.fingerprint(request.publicKey())
                    + "\"");
        }
        Contract contract = kept.get();
        if (contractHash != null && !contractHash.equals(request.contractHash())) {
            return Optional.of(request.provider() + " changed what it asks for since you saw it; look at its new "
                    + "request first");
        }
        List<String> capabilities = List.copyOf(contract.ids());
        List<String> produces = List.copyOf(contract.produces());
        Grant grant = Grant.issue(identity(), request.publicKey(), Grant.Rights.provider(request.provider(),
                capabilities, produces), null, null);
        if (storeAdmitted(request, grant, principal) == 0) {
            return Optional.of("the access of " + request.provider() + " (key "
                    + Security.fingerprint(request.publicKey()) + ") was revoked; it cannot be admitted again");
        }
        admitted(request.keyId(), request.publicKey(), request.provider(), capabilities, principal);
        return Optional.empty();
    }

    /**
     * Stores a remembered request as admitted with {@code grant}; returns how many rows changed: 0 when the key's
     * row belongs to another public key or its access was revoked.
     */
    int storeAdmitted(RecentAdmissions.Request request, Grant grant, String principal) {
        Contract contract = request.contract().orElseThrow();
        OffsetDateTime at = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        return jdbc.sql("""
                INSERT INTO admissions (key_id, public_key, provider, capabilities, produces, contract, status,
                    grant_body, admitted_contract, requested_at, asked_at, decided_at, decided_by)
                VALUES (:k, :pub, :provider, CAST(:caps AS jsonb), CAST(:prod AS jsonb), CAST(:c AS jsonb),
                    'ADMITTED', :g, CAST(:c AS jsonb), :asked, :asked, :at, :by)
                ON CONFLICT (key_id) DO UPDATE SET status = 'ADMITTED', provider = EXCLUDED.provider,
                    capabilities = EXCLUDED.capabilities, produces = EXCLUDED.produces, contract = EXCLUDED.contract,
                    grant_body = EXCLUDED.grant_body, admitted_contract = EXCLUDED.admitted_contract,
                    asked_at = EXCLUDED.asked_at, decided_at = EXCLUDED.decided_at, decided_by = EXCLUDED.decided_by
                WHERE admissions.public_key = EXCLUDED.public_key AND admissions.status <> 'REVOKED'""")
                .param("k", request.keyId()).param("pub", request.publicKey()).param("provider", request.provider())
                .param("caps", Json.write(contract.ids())).param("prod", Json.write(contract.produces()))
                .param("c", contract.toJson()).param("g", grant.encode())
                .param("asked", OffsetDateTime.ofInstant(request.at(), ZoneOffset.UTC)).param("at", at)
                .param("by", principal).update();
    }

    /**
     * The owner refuses the waiting agent whose whole key id or fingerprint is {@code keyRef} ({@link #wholeKeyId});
     * it can still be admitted later. Like {@link #admit}, only the row of exactly that key is looked at, never one
     * found by a provider name or a key id prefix.
     */
    public Optional<String> reject(String keyRef, String principal) {
        Optional<String> whole = wholeKeyId(keyRef);
        if (whole.isEmpty()) {
            return Optional.of(notAWholeKey(keyRef));
        }
        Optional<Map<String, Object>> row = admissionByKey(whole.get()).filter(r -> "PENDING".equals(r.get("status")));
        if (row.isEmpty()) {
            return Optional.of("no agent is waiting for admission under \"" + keyRef + "\"");
        }
        String keyId = (String) row.get().get("key_id");
        jdbc.sql("UPDATE admissions SET status = 'REJECTED', decided_at = :at, decided_by = :by WHERE key_id = :k AND status = 'PENDING'")
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).param("by", principal)
                .param("k", keyId).update();
        inbox.settleByKey("admission:" + keyId + ":", Inbox.Status.DISMISSED, principal);
        ledger.recordSystem(Facts.ACCESS_REVOKED, new Facts.SecurityEvent(keyId, (String) row.get().get("provider"),
                "rejected", "AGENT"), principal);
        return Optional.empty();
    }

    /**
     * The request {@code ref} names, as {@link #admit} would admit it, so the owner can be shown the exact contract
     * and the whole key first: its capabilities, what they read and write, their side effects, what the kernel gives
     * them and every capability's terms. {@code ref} is read one way only, by its form ({@link #admissionRow}): a
     * whole key id or fingerprint finds exactly that key's row, a shorter key id prefix the one row it starts, and
     * anything else the one row asking under that provider name. A key whose request was not stored is found by its
     * whole key only ({@link #recordedRequest}). The {@link PendingAdmission#keyId} returned is the whole key id the
     * owner's yes is to carry to {@link #admit} or {@link #reject}.
     */
    public Optional<PendingAdmission> pendingAdmission(String ref) {
        return admissionRow(ref).map(SecurityService::pendingAdmission)
                .or(() -> recordedRequest(ref).flatMap(r -> r.contract().map(c -> pendingAdmission(r.keyId(),
                        r.provider(), c, r.contractHash()))));
    }

    /** A row of the admissions table (key_id, provider, capabilities, produces, contract) as the owner judges it. */
    static PendingAdmission pendingAdmission(Map<String, Object> row) {
        Optional<Contract> contract = Contract.fromJson((String) row.get("contract"));
        List<String> capabilities = List.of(Json.read((String) row.get("capabilities"), String[].class));
        List<String> produces = List.of(Json.read((String) row.get("produces"), String[].class));
        return new PendingAdmission((String) row.get("key_id"), (String) row.get("provider"), capabilities, produces,
                contract.map(Contract::hash).orElseGet(() -> contractHash(capabilities, produces)),
                contract.map(c -> List.copyOf(c.reads())).orElse(List.of()),
                contract.map(Contract::sideEffects).orElse(List.of()),
                contract.map(Contract::describe).orElse(null),
                contract.map(Contract::kernelSupplied).orElse(List.of()),
                contract.map(Contract::offers).orElse(null));
    }

    /** A request for {@code contract} (whose hash is {@code hash}) as the owner judges it. */
    static PendingAdmission pendingAdmission(String keyId, String provider, Contract contract, String hash) {
        return new PendingAdmission(keyId, provider, List.copyOf(contract.ids()), List.copyOf(contract.produces()),
                hash, List.copyOf(contract.reads()), contract.sideEffects(), contract.describe(),
                contract.kernelSupplied(), contract.offers());
    }

    /** An agent that waits for the owner's yes: its key id, the fingerprint the owner admits it by, its name. */
    public record WaitingAgent(String keyId, String fingerprint, String provider) {
    }

    /**
     * The admission rows that have an unread admission message of the kernel's in the owner's inbox, the request
     * asked longest ago first: the rows {@link #waitingFor} looks at.
     */
    static final String WAITING_SQL = """
            SELECT a.key_id, a.public_key, a.provider, a.status, a.capabilities::text AS capabilities,
                a.contract::text AS contract, a.admitted_contract::text AS admitted, a.grant_body
            FROM admissions a
            WHERE a.status IN ('PENDING', 'ADMITTED') AND EXISTS (
                SELECT 1 FROM inbox i WHERE i.status = 'UNREAD' AND i.kind = 'ADMISSION' AND i.provider = 'kernel'
                    AND i.dedupe_key LIKE 'admission:' || a.key_id || ':%')
            ORDER BY COALESCE(a.asked_at, a.requested_at)""";

    /**
     * The agents offering {@code capability} that wait for the owner's yes: the owner has an unread admission message
     * of the kernel's about the key, and the key either waits for its first admission ({@code PENDING}) or is
     * admitted but asks for a contract its admission does not cover (for example after an upgrade changed what the
     * kernel gives the capability), so it is not listed until the owner admits the new contract. An admitted agent
     * whose admission covers what it asks for is not waiting: when it does not run, it is simply stopped. Empty when
     * the rows cannot be read.
     */
    public List<WaitingAgent> waitingFor(String capability) {
        try {
            return jdbc.sql(WAITING_SQL).query().listOfRows().stream().filter(row -> waits(row, capability))
                    .map(row -> new WaitingAgent((String) row.get("key_id"),
                            Security.fingerprint((String) row.get("public_key")), (String) row.get("provider")))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("cannot read the agents waiting for admission: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Whether a row of {@link #WAITING_SQL} waits for the owner with a request that offers {@code capability}: its
     * latest request offers it, and the row is {@code PENDING}, or {@code ADMITTED} with a request its admission
     * ({@link #covers(AdmittedRow, Contract)}) does not cover.
     */
    static boolean waits(Map<String, Object> row, String capability) {
        Optional<Contract> asked = Contract.fromJson((String) row.get("contract"));
        Set<String> offered = asked.map(Contract::ids).orElseGet(() -> {
            String legacy = (String) row.get("capabilities");
            return legacy == null ? Set.of() : Set.of(Json.read(legacy, String[].class));
        });
        if (!offered.contains(capability)) {
            return false;
        }
        if ("PENDING".equals(row.get("status"))) {
            return true;
        }
        if (!"ADMITTED".equals(row.get("status")) || asked.isEmpty()) {
            return false;
        }
        AdmittedRow admitted = new AdmittedRow((String) row.get("provider"),
                Grant.decode((String) row.get("grant_body")).orElse(null),
                Contract.fromJson((String) row.get("admitted")).orElse(null));
        return !covers(admitted, asked.get());
    }

    /** The columns of an admission row as the owner's decisions read it. */
    private static final String ADMISSION_COLUMNS = """
            SELECT key_id, public_key, provider, status, capabilities::text AS capabilities, produces::text AS produces,
                contract::text AS contract
            FROM admissions""";

    /**
     * The admission row {@code ref} names, read by its form and never two ways at once: a whole key id or
     * fingerprint ({@link #wholeKeyId}) is exactly that key's row; a shorter key id prefix (at least
     * {@link #MIN_ID_PREFIX} hex digits, dashes ignored) is the row whose key id starts so, and anything else is the
     * row asking under that provider name. A prefix or a name counts only when exactly one row that waits, was
     * refused or is admitted matches it: two keys under one name (any key can ask under any name) are named by
     * their keys instead.
     */
    Optional<Map<String, Object>> admissionRow(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        Optional<String> whole = wholeKeyId(ref);
        if (whole.isPresent()) {
            return admissionByKey(whole.get());
        }
        String r = ref.strip().toLowerCase(Locale.ROOT);
        boolean prefix = KEY_PREFIX.matcher(r).matches() && r.replace("-", "").length() >= MIN_ID_PREFIX;
        List<Map<String, Object>> rows = prefix
                ? jdbc.sql(ADMISSION_COLUMNS + " WHERE key_id LIKE :p AND " + DECIDABLE).param("p",
                        r.replace("-", "") + "%").query().listOfRows()
                : jdbc.sql(ADMISSION_COLUMNS + " WHERE lower(provider) = :r AND " + DECIDABLE).param("r", r)
                        .query().listOfRows();
        return rows.size() == 1 ? Optional.of(rows.getFirst()) : Optional.empty();
    }

    /** The admission rows the owner can still decide on: waiting, refused, or admitted (perhaps asking for more). */
    private static final String DECIDABLE = "status IN ('PENDING', 'REJECTED', 'ADMITTED')";

    /** The admission row of exactly this key id while it waits, was refused or is admitted (never a revoked one). */
    Optional<Map<String, Object>> admissionByKey(String keyId) {
        return jdbc.sql(ADMISSION_COLUMNS + " WHERE key_id = :k AND " + DECIDABLE)
                .param("k", keyId).query().listOfRows().stream().findFirst();
    }

    /**
     * Whether a registry entry may be listed: signed by its key, the key admitted under the provider name the
     * manifest gives, the grant the kernel's and unrevoked, and the manifest within the admitted contract (no more
     * consumed, required or written tokens, the same side effects, name and annotation schemas, no lower autonomy).
     * An admission recorded before contracts were kept is checked against its grant and lists only entries that do
     * no more than the grant shows ({@link Contract#withinLegacyGrant}) until the owner admits the rest. Only a
     * check: asking the owner is {@link #admission}'s part, which a heartbeat goes through first.
     */
    public boolean admits(SignedManifest entry) {
        if (!enabled()) {
            return true;
        }
        if (!entry.verifies() || !Security.isCanonicalKey(entry.key())) {
            return false;
        }
        if (entry.key().equals(identity().publicKeyText())) {
            return true; // the kernel's own capabilities
        }
        String keyId = Security.keyId(entry.key());
        CapabilityManifest m = entry.manifest();
        if (m == null || m.provider() == null || manifestProblem(m).isPresent()) {
            return false;
        }
        Optional<AdmittedRow> admitted = admitted(keyId, entry.key());
        if (admitted.isEmpty() || admitted.get().grant() == null) {
            return false;
        }
        Grant grant = admitted.get().grant();
        Contract contract = admitted.get().contract();
        return admitted.get().provider().equals(m.provider()) && admitted.get().provider().equals(grant.rights().name())
                && grantProblem(grant, entry.key(), false) == null
                && covers(grant, Set.of(m.id()), Set.copyOf(m.produces()))
                && (contract == null ? Contract.of(List.of(m)).withinLegacyGrant(grant.rights().produces())
                : contract.allows(m));
    }

    /**
     * The terms of a grown capability, taken from its signed spec, so the agent that hosts it is listed for it only
     * with the manifest the spec describes: the spec's name, its one annotation under the spec's value schema, and no
     * side effects. It reads what the spec consumes (only tokens grown capabilities may read,
     * {@link Ecosystem#CONSUMABLE}) and, as every hosted grown capability does, the goal's text and what its scope
     * needs: the tasks for a capability that annotates each task, the goal's text for one that annotates the goal; that
     * is also what it requires. The name and the schema are recorded, so a heartbeat of the host cannot fill them in
     * with others.
     */
    static Contract.Terms grownTerms(Ecosystem.CapabilitySpec spec) {
        String required = spec.scope() == Ecosystem.Scope.GOAL ? Vocabulary.GOAL_TEXT : Vocabulary.TASKS;
        Set<String> reads = new TreeSet<>(spec.consumes());
        reads.retainAll(Ecosystem.CONSUMABLE);
        reads.add(required);
        reads.add(Vocabulary.GOAL_TEXT);
        Map<String, JsonNode> schemas = new TreeMap<>();
        schemas.put(spec.annotationKey(), spec.valueSchema());
        String token = spec.token();
        return new Contract.Terms(List.copyOf(reads), List.of(required), List.of(token),
                CapabilityManifest.SideEffect.NONE, Vocabulary.STRUCTURAL.contains(token) ? AutonomyLevel.L2
                : AutonomyLevel.L1, spec.name() == null ? "" : spec.name(), schemas);
    }

    /**
     * The owner confirmed growing a capability (or keeping one): the agents that host grown capabilities (the
     * forge) may now serve it too, so their grants and contracts are widened by exactly that capability, its one
     * annotation and the terms its signed spec gives ({@link #grownTerms}). Terms an earlier spec of the same id
     * left in a host's contract are replaced.
     */
    public void extendForGrowth(Ecosystem.CapabilitySpec spec) {
        Contract.Terms terms = grownTerms(spec);
        for (Map<String, Object> host : admittedRows()) {
            Grant old = Grant.decode((String) host.get("grant_body")).orElse(null);
            // A host is an agent whose grant (not merely its latest request) covers the designer.
            if (old == null || !hostsGrownCapabilities(old)) {
                continue;
            }
            Set<String> caps = new LinkedHashSet<>(old.rights().capabilities());
            Set<String> prod = new LinkedHashSet<>(old.rights().produces());
            caps.add(spec.id());
            prod.add(spec.token());
            updateHost(host, caps, prod, c -> c.with(spec.id(), terms));
        }
    }

    /**
     * Undoes {@link #extendForGrowth}: every host whose grant names {@code capabilityId} gets a grant and contract
     * without it and without {@code token}. Used when the capability could not be grown after the grants were
     * widened, and when the kernel stops vouching for a grown capability: the owner retires it, or its spec is
     * quarantined, lost or removed from the genesis bucket.
     */
    public void withdrawGrowth(String capabilityId, String token) {
        for (Map<String, Object> host : admittedRows()) {
            Grant old = Grant.decode((String) host.get("grant_body")).orElse(null);
            if (old == null || old.rights() == null || old.rights().capabilities() == null
                    || !old.rights().capabilities().contains(capabilityId)) {
                continue;
            }
            Set<String> caps = new LinkedHashSet<>(old.rights().capabilities());
            Set<String> prod = new LinkedHashSet<>(old.rights().produces() == null ? List.of() : old.rights().produces());
            caps.remove(capabilityId);
            prod.remove(token);
            updateHost(host, caps, prod, c -> c.without(capabilityId));
        }
    }

    /** Every admitted agent: key id, public key, provider, grant and both contracts (as JSON text, or null). */
    private List<Map<String, Object>> admittedRows() {
        return jdbc.sql("""
                SELECT key_id, public_key, provider, grant_body, contract::text AS contract,
                    admitted_contract::text AS admitted_contract
                FROM admissions WHERE status = 'ADMITTED'""").query().listOfRows();
    }

    /**
     * Gives a host a new grant for these capabilities and tokens, and applies {@code change} to its requested and
     * admitted contracts (one recorded before contracts were kept stays without).
     */
    private void updateHost(Map<String, Object> host, Set<String> caps, Set<String> prod,
                            UnaryOperator<Contract> change) {
        Grant grant = Grant.issue(identity(), (String) host.get("public_key"),
                Grant.Rights.provider((String) host.get("provider"), List.copyOf(caps), List.copyOf(prod)), null, null);
        String requested = Contract.fromJson((String) host.get("contract")).map(change).map(Contract::toJson)
                .orElse(null);
        String admitted = Contract.fromJson((String) host.get("admitted_contract")).map(change).map(Contract::toJson)
                .orElse(null);
        jdbc.sql("""
                UPDATE admissions SET grant_body = :g, capabilities = CAST(:c AS jsonb), produces = CAST(:p AS jsonb),
                    contract = COALESCE(CAST(:requested AS jsonb), contract),
                    admitted_contract = COALESCE(CAST(:admitted AS jsonb), admitted_contract)
                WHERE key_id = :k""")
                .param("g", grant.encode()).param("c", Json.write(caps)).param("p", Json.write(prod))
                .param("requested", requested).param("admitted", admitted)
                .param("k", host.get("key_id")).update();
    }

    /**
     * The grown capabilities the owner retired and has not kept since: the ids whose latest lifecycle fact (grown,
     * kept or retired) is {@code capability.retired}. Empty when the ledger cannot be read.
     */
    public Set<String> retiredCapabilities() {
        try {
            return Set.copyOf(jdbc.sql(RETIRED_SQL).param("retired", Facts.CAPABILITY_RETIRED)
                    .param("kept", Facts.CAPABILITY_KEPT).param("grown", Facts.CAPABILITY_GROWN)
                    .query(String.class).list());
        } catch (RuntimeException e) {
            log.debug("cannot read the retired capabilities: {}", e.getMessage());
            return Set.of();
        }
    }

    /** The ids of grown capabilities whose latest lifecycle fact (a system fact, without a goal) is a retirement. */
    static final String RETIRED_SQL = """
            SELECT capability FROM (
                SELECT DISTINCT ON (payload->>'capability') payload->>'capability' AS capability, type FROM events
                WHERE type IN (:retired, :kept, :grown) AND goal_id IS NULL
                ORDER BY payload->>'capability', seq DESC) latest
            WHERE type = :retired""";

    /** Whether a grant lets its agent serve {@code capability.design}, which makes it a host of grown capabilities. */
    public static boolean hostsGrownCapabilities(Grant grant) {
        return grant.rights() != null && grant.rights().capabilities() != null
                && grant.rights().capabilities().contains("capability.design");
    }

    // ---- invitations and delegation ----------------------------------------------------------------------

    /**
     * An invitation for a guest: rights on one goal until a date. The returned code is shown to the owner once and
     * only its hash is stored; the first device that presents it gets a grant bound to its own key. The code is
     * {@code fabric-invite:<id>.<secret>.<caPin>.<kernelKeyId>}: the guest's device checks the gateway's certificate
     * authority against {@code caPin} and the kernel's reply against {@code kernelKeyId} before it trusts either.
     */
    public String invite(String name, String goalId, boolean viewOnly, Instant until, String principal) {
        name = plainText(name, MAX_NAME_LENGTH);
        String id = "i" + Security.randomToken(6);
        String secret = Security.randomToken(18);
        jdbc.sql("""
                INSERT INTO invitations (id, secret_hash, name, goal_id, actions, until, created_by, created_at, status)
                VALUES (:id, :h, :name, :goal, CAST(:actions AS jsonb), :until, :by, :at, 'OPEN')""")
                .param("id", id).param("h", Hashing.sha256(secret)).param("name", name).param("goal", goalId)
                .param("actions", Json.write(viewOnly ? VIEW_ACTIONS : GUEST_ACTIONS))
                .param("until", until == null ? null : OffsetDateTime.ofInstant(until, ZoneOffset.UTC))
                .param("by", principal).param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
        ledger.record(goalId, Facts.INVITATION_CREATED, new Facts.SecurityEvent(null, name, "invited",
                viewOnly ? "view" : "edit"), principal, id);
        return invitationCode(id, secret, caPin(), identity().keyId());
    }

    static String invitationCode(String id, String secret, String caPin, String kernelKeyId) {
        return INVITE_PREFIX + id + "." + secret + "." + caPin + "." + kernelKeyId;
    }

    /** {id, secret} of an invitation code in its current four-part form or the older two-part one. */
    static Optional<String[]> invitationParts(String code) {
        if (code == null || !code.strip().startsWith(INVITE_PREFIX)) {
            return Optional.empty();
        }
        String[] parts = code.strip().substring(INVITE_PREFIX.length()).split("\\.", -1);
        if (parts.length != 2 && parts.length != 4) {
            return Optional.empty();
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.of(new String[]{parts[0], parts[1]});
    }

    /** The certificate authority's pin shown in invitations ("-" without a configured CA file). */
    String caPin() {
        String pin = caPin;
        if (pin == null) {
            pin = caPin(caFile);
            caPin = pin;
        }
        return pin;
    }

    /** First 32 hex characters of the SHA-256 of the (first) certificate's DER in a PEM file, or "-". */
    static String caPin(String caFile) {
        if (caFile == null || caFile.isBlank() || !Files.isRegularFile(Path.of(caFile))) {
            return "-";
        }
        try (InputStream in = Files.newInputStream(Path.of(caFile))) {
            var certificate = CertificateFactory.getInstance("X.509").generateCertificate(in);
            return HexFormat.of().formatHex(Security.sha256(certificate.getEncoded())).substring(0, 32);
        } catch (Exception e) {
            log.warn("cannot read the certificate authority {} for invitations: {}", caFile, e.getMessage());
            return "-";
        }
    }

    /** {@link #join(String, String, String, boolean)} for a request on the machine's own broker. */
    public Optional<String> join(String key, String code, String deviceName) {
        return join(key, code, deviceName, false);
    }

    /**
     * A guest's device claims an invitation; returns the problem, or empty when the device now holds the grant. A
     * device holds one grant: a second invitation replaces the first one, which is voided. When both give the same
     * rights until the same day, the new grant covers the goals of both; otherwise only the new invitation's goal.
     * An invitation claimed through the gateway ({@code viaGateway}) is announced in the owner's inbox with the
     * device's name and fingerprint.
     */
    public synchronized Optional<String> join(String key, String code, String deviceName, boolean viaGateway) {
        Optional<String[]> parts = invitationParts(code);
        if (parts.isEmpty()) {
            return Optional.of("that is not an invitation");
        }
        String keyId = Security.keyId(key);
        Optional<DeviceRow> existing = device(keyId, key);
        if (existing.isPresent() && !existing.get().revoked() && existing.get().role() == Role.OWNER) {
            return Optional.of("this device is already one of the owner's devices; it needs no invitation");
        }
        String id = parts.get()[0];
        String secret = parts.get()[1];
        Optional<Map<String, Object>> row = openInvitation(id);
        if (row.isEmpty() || !Security.same((String) row.get().get("secret_hash"), Hashing.sha256(secret))) {
            return Optional.of("that invitation is unknown or was already used");
        }
        Instant until = row.get().get("until") == null ? null : ((Timestamp) row.get().get("until")).toInstant();
        if (until != null && until.isBefore(clock.instant())) {
            return Optional.of("that invitation has expired");
        }
        String name = (String) row.get().get("name");
        String goalId = (String) row.get().get("goal_id");
        List<ActionType> actions = List.of(Json.read((String) row.get().get("actions"), ActionType[].class));
        Grant earlier = existing.filter(d -> !d.revoked() && d.role() == Role.GUEST).map(DeviceRow::grant).orElse(null);
        List<String> goals = goalsAfterJoin(earlier == null || grantProblem(earlier, key, false) != null ? null
                : earlier.rights(), earlier == null ? null : earlier.until(), goalId, actions, until);
        Grant grant = Grant.issue(identity(), key, Grant.Rights.person("guest", name, goals, actions), until, null);
        if (earlier != null) {
            // A device holds one grant: the one it replaces ends here, with every grant handed on from it.
            voidGrant(earlier.encode(), "guest:" + name);
        }
        storeGuestDevice(keyId, key, name, grant);
        jdbc.sql("UPDATE invitations SET status = 'CLAIMED', claimed_key = :k WHERE id = :id")
                .param("k", keyId).param("id", id).update();
        String device = deviceName(deviceName, keyId);
        ledger.record(goalId, Facts.INVITATION_CLAIMED, new Facts.SecurityEvent(keyId, name, "joined", device),
                "guest:" + name, id);
        if (viaGateway && hasOwner()) {
            announce("joined:" + keyId, "The invitation for " + name + " was claimed through the gateway by the device \""
                    + device + "\" (key " + Security.fingerprint(key) + "). If this was not you, say: revoke " + name,
                    "invitation for " + name + " claimed through the gateway");
        }
        return Optional.empty();
    }

    /** An open invitation's row (id, secret_hash, name, goal_id, actions as JSON text, until). */
    Optional<Map<String, Object>> openInvitation(String id) {
        return jdbc.sql("""
                SELECT id, secret_hash, name, goal_id, actions::text AS actions, until FROM invitations
                WHERE id = :id AND status = 'OPEN'""").param("id", id).query().listOfRows().stream().findFirst();
    }

    /**
     * Stores (or replaces) a guest device with its grant; a device revoked earlier gets access again. The row of
     * another public key with the same key id is left alone.
     */
    void storeGuestDevice(String keyId, String key, String name, Grant grant) {
        jdbc.sql("""
                INSERT INTO devices (key_id, public_key, role, name, grant_body, paired_at)
                VALUES (:k, :pub, 'GUEST', :name, :g, :at)
                ON CONFLICT (key_id) DO UPDATE SET role = 'GUEST', name = EXCLUDED.name, grant_body = EXCLUDED.grant_body,
                    paired_at = EXCLUDED.paired_at, revoked_at = NULL
                WHERE devices.public_key = EXCLUDED.public_key""")
                .param("k", keyId).param("pub", key).param("name", name).param("g", grant.encode())
                .param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)).update();
    }

    /**
     * The goals of the grant a device gets for an invitation to {@code goalId}: also those of the grant it held
     * ({@code earlier}, {@code null} when it held none that still counts) when that grant allowed the same actions
     * until the same moment, so merging widens nothing.
     */
    static List<String> goalsAfterJoin(Grant.Rights earlier, Instant earlierUntil, String goalId,
                                       List<ActionType> actions, Instant until) {
        if (earlier == null || earlier.goals() == null || !Objects.equals(earlierUntil, until)) {
            return List.of(goalId);
        }
        List<ActionType> before = earlier.actions() == null ? GUEST_ACTIONS : earlier.actions();
        if (!Set.copyOf(before).equals(Set.copyOf(actions))) {
            return List.of(goalId);
        }
        Set<String> goals = new LinkedHashSet<>(earlier.goals());
        goals.add(goalId);
        return List.copyOf(goals);
    }

    /** The grant a guest device holds, so it can narrow it further for someone else. */
    public Optional<Grant> grantOf(String key) {
        return jdbc.sql("SELECT grant_body FROM devices WHERE key_id = :k AND public_key = :pub AND revoked_at IS NULL")
                .param("k", Security.keyId(key)).param("pub", key).query(String.class).optional()
                .flatMap(Grant::decode);
    }

    // ---- revocation and overview -------------------------------------------------------------------------

    private static final Set<String> NOT_NAMES = Set.of("access", "device", "phone", "laptop", "computer", "the", "of",
            "my", "remove", "revoke", "agent", "guest", "key", "end", "for", "from", "invitation", "invite", "rights");
    private static final Set<String> DEVICE_WORDS = Set.of("device", "phone", "laptop", "computer");

    /**
     * Finds who "Ana", "Ana's phone", "the estimator", "Ana's invitation" or "3f9a11c2" means among devices,
     * guests, agents and open invitations. A holder's exact reference ({@link Holder#ref}) means that one holder of
     * that kind. A whole key id or fingerprint ({@link #wholeKeyId}), given alone or as the one key in the words,
     * means the device or agent with exactly that key and is never compared with names. Anything else is a key-id
     * (or invitation-id) prefix, the exact name, or the name's words; the words "invitation", "agent" or "device"
     * (phone, laptop) limit the search to that kind. Empty when nobody matches.
     */
    public List<Holder> match(String ref) {
        return candidates(ref).stream().map(Candidate::holder).toList();
    }

    /** Everyone with access (devices, agents, open invitations) that {@code ref} means; see {@link #pick}. */
    List<Candidate> candidates(String ref) {
        if (ref == null || ref.isBlank()) {
            return List.of();
        }
        return pick(holders(), ref);
    }

    /** Everyone with access: unrevoked devices, agents that wait, were refused or are admitted, open invitations. */
    List<Candidate> holders() {
        List<Candidate> all = new ArrayList<>();
        jdbc.sql("SELECT key_id, name, role, public_key FROM devices WHERE revoked_at IS NULL")
                .query((rs, i) -> new Candidate(new Holder(rs.getString("key_id"),
                        rs.getString("role").toLowerCase(Locale.ROOT) + " device " + rs.getString("name") + " ("
                                + Security.fingerprint(rs.getString("public_key")) + ")", HolderKind.DEVICE),
                        rs.getString("name").toLowerCase(Locale.ROOT), HolderKind.DEVICE))
                .list().forEach(all::add);
        jdbc.sql("SELECT key_id, provider, public_key FROM admissions WHERE status IN ('ADMITTED', 'PENDING', 'REJECTED')")
                .query((rs, i) -> new Candidate(new Holder(rs.getString("key_id"), "agent " + rs.getString("provider")
                        + " (" + Security.fingerprint(rs.getString("public_key")) + ")", HolderKind.AGENT),
                        rs.getString("provider").toLowerCase(Locale.ROOT), HolderKind.AGENT))
                .list().forEach(all::add);
        jdbc.sql("SELECT id, name FROM invitations WHERE status = 'OPEN'")
                .query((rs, i) -> new Candidate(new Holder(rs.getString("id"), "the open invitation for "
                        + rs.getString("name") + " (" + rs.getString("id") + ")", HolderKind.INVITATION),
                        rs.getString("name").toLowerCase(Locale.ROOT), HolderKind.INVITATION))
                .list().forEach(all::add);
        return all;
    }

    /** {@code <kind>:<id>} as {@link Holder#ref} writes it; the id of a device or an agent is a whole key id. */
    private static final Pattern HOLDER_REF = Pattern.compile("(?i)(device|agent|invitation):([A-Za-z0-9_-]{1,64})");
    /** A whole key id or fingerprint standing as one word among others. */
    private static final Pattern KEY_IN_WORDS = Pattern.compile(
            "(?i)(?<![0-9a-z-])([0-9a-f]{16}|[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4})(?![0-9a-z-])");

    /** The holder an exact reference ({@link Holder#ref}) names: its kind and id; empty for anything else. */
    static Optional<Holder> holderRef(String ref) {
        Matcher m = HOLDER_REF.matcher(ref == null ? "" : ref.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        HolderKind kind = HolderKind.valueOf(m.group(1).toUpperCase(Locale.ROOT));
        if (kind == HolderKind.INVITATION) {
            return Optional.of(new Holder(m.group(2), null, kind));
        }
        return wholeKeyId(m.group(2)).map(id -> new Holder(id, null, kind));
    }

    /**
     * The whole key {@code ref} names ({@link #wholeKeyId}), or the one whole key id or fingerprint written among
     * its words ("revoke 3F9A-11C2-77B0-D41E"); empty when there is none, or more than one.
     */
    static Optional<String> keyNamed(String ref) {
        Optional<String> whole = wholeKeyId(ref);
        if (whole.isPresent()) {
            return whole;
        }
        Set<String> keys = KEY_IN_WORDS.matcher(ref).results().map(m -> wholeKeyId(m.group(1)).orElseThrow())
                .collect(Collectors.toSet());
        return keys.size() == 1 ? Optional.of(keys.iterator().next()) : Optional.empty();
    }

    /** The candidates {@code ref} means; see {@link #match}. */
    static List<Candidate> pick(List<Candidate> all, String ref) {
        Optional<Holder> exact = holderRef(ref);
        if (exact.isPresent()) {
            return all.stream().filter(c -> c.kind() == exact.get().kind() && (c.kind() == HolderKind.INVITATION
                    ? c.holder().keyId().equals(exact.get().keyId())
                    : c.holder().keyId().equalsIgnoreCase(exact.get().keyId()))).toList();
        }
        Optional<String> key = keyNamed(ref);
        if (key.isPresent()) {
            return all.stream().filter(c -> c.kind() != HolderKind.INVITATION
                    && c.holder().keyId().equalsIgnoreCase(key.get())).toList();
        }
        String r = ref.strip().toLowerCase(Locale.ROOT);
        Set<String> words = new LinkedHashSet<>(List.of(r.replace("'s", "").split("[^a-z0-9]+")));
        Optional<HolderKind> kind = words.contains("invitation") || words.contains("invite")
                ? Optional.of(HolderKind.INVITATION) : words.contains("agent") ? Optional.of(HolderKind.AGENT)
                : words.stream().anyMatch(DEVICE_WORDS::contains) ? Optional.of(HolderKind.DEVICE) : Optional.empty();
        List<Candidate> pool = all.stream().filter(c -> kind.isEmpty() || c.kind() == kind.get()).toList();
        List<Candidate> named = pool.stream().filter(c -> idMatches(c, r) || c.name().equals(r)).toList();
        if (!named.isEmpty()) {
            return named;
        }
        Set<String> names = words.stream().filter(w -> w.length() >= 3 && !NOT_NAMES.contains(w))
                .collect(Collectors.toSet());
        if (names.isEmpty()) {
            return List.of();
        }
        return pool.stream().filter(c -> Set.copyOf(Arrays.asList(c.name().split("[^a-z0-9]+"))).stream().anyMatch(names::contains))
                .toList();
    }

    private static boolean idMatches(Candidate c, String r) {
        if (r.length() < MIN_ID_PREFIX) {
            return false;
        }
        String id = c.holder().keyId().toLowerCase(Locale.ROOT);
        return c.kind() == HolderKind.INVITATION ? id.startsWith(r) : id.startsWith(r.replace("-", ""));
    }

    /**
     * Revokes a device, a guest, an agent or an open invitation, found as {@link #match} finds them: given a
     * holder's exact reference ({@link Holder#ref}, what a confirmation question resolved), exactly that row ends,
     * or nothing when it no longer has access. Grants issued to the revoked key are voided, and with them every grant
     * handed on from them; a revoked device's key also voids every chain it is a link of. A revoked agent's registry
     * entries leave at once. Returns the problem, or empty when the access ended.
     */
    public Optional<String> revoke(String ref, String principal) {
        if (ref == null || ref.isBlank()) {
            return Optional.of("whose access should end?");
        }
        List<Candidate> found = candidates(ref);
        if (found.size() > 1) {
            return Optional.of("\"" + ref + "\" matches more than one: "
                    + found.stream().map(c -> c.holder().label()).toList());
        }
        if (found.isEmpty()) {
            return Optional.of(holderRef(ref).isPresent() ? "that access has already ended"
                    : "nobody with access matches \"" + ref + "\"");
        }
        Candidate target = found.getFirst();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String id = target.holder().keyId();
        switch (target.kind()) {
            case INVITATION -> {
                jdbc.sql("UPDATE invitations SET status = 'REVOKED' WHERE id = :id AND status = 'OPEN'").param("id", id)
                        .update();
                ledger.recordSystem(Facts.ACCESS_REVOKED, new Facts.SecurityEvent(null, target.name(), "revoked",
                        "INVITATION"), principal);
            }
            case DEVICE -> {
                Map<String, Object> d = jdbc.sql("SELECT key_id, name, role, grant_body FROM devices WHERE key_id = :k")
                        .param("k", id).query().singleRow();
                if ("OWNER".equals(d.get("role")) && jdbc.sql("SELECT count(*) FROM devices WHERE role = 'OWNER' AND revoked_at IS NULL")
                        .query(Integer.class).single() <= 1) {
                    return Optional.of("that is your only paired device; pair another one before revoking it");
                }
                jdbc.sql("UPDATE devices SET revoked_at = :at WHERE key_id = :k").param("at", now).param("k", id).update();
                voidGrant((String) d.get("grant_body"), principal);
                trust.forget(id);
                ledger.recordSystem(Facts.ACCESS_REVOKED, new Facts.SecurityEvent(id, (String) d.get("name"), "revoked",
                        (String) d.get("role")), principal);
            }
            case AGENT -> {
                Map<String, Object> a = jdbc.sql("SELECT key_id, public_key, provider, grant_body FROM admissions WHERE key_id = :k")
                        .param("k", id).query().singleRow();
                jdbc.sql("UPDATE admissions SET status = 'REVOKED', decided_at = :at, decided_by = :by WHERE key_id = :k")
                        .param("at", now).param("by", principal).param("k", id).update();
                inbox.settleByKey("admission:" + id + ":", Inbox.Status.DISMISSED, principal);
                voidGrant((String) a.get("grant_body"), principal);
                ledger.recordSystem(Facts.ACCESS_REVOKED, new Facts.SecurityEvent(id, (String) a.get("provider"),
                        "revoked", "AGENT"), principal);
                // Its registry entries leave now; their leases would otherwise keep them listed for a while.
                events.publishEvent(new CapabilityRegistry.KeyRevoked((String) a.get("public_key")));
            }
        }
        return Optional.empty();
    }

    /** Records a grant (given encoded) as revoked, which voids every grant handed on from it too. */
    void voidGrant(String encoded, String principal) {
        Grant.decode(encoded).ifPresent(g -> jdbc.sql("INSERT INTO revoked_grants (id, revoked_at, by_principal) VALUES (:id, :at, :by) ON CONFLICT DO NOTHING")
                .param("id", g.id()).param("at", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .param("by", principal).update());
    }

    /** Devices, guests, agents and open invitations, for "who has access?", in plain words. */
    public List<String> overview() {
        ZoneId zone = ZoneId.systemDefault();
        Function<String, String> title = this::goalTitle;
        List<String> lines = new ArrayList<>();
        jdbc.sql("SELECT name, role, public_key, grant_body, paired_at FROM devices WHERE revoked_at IS NULL ORDER BY paired_at")
                .query((rs, i) -> {
                    String head = rs.getString("role").toLowerCase(Locale.ROOT) + " device " + rs.getString("name") + " ("
                            + Security.fingerprint(rs.getString("public_key")) + ")";
                    Optional<Grant> grant = Grant.decode(rs.getString("grant_body"));
                    return grant.isPresent() && Role.GUEST.name().equals(rs.getString("role"))
                            ? head + " " + describeRights(grant.get().rights(), grant.get().until(), title, zone)
                            : head + ", paired " + date(rs.getObject("paired_at", OffsetDateTime.class).toInstant(), zone);
                }).list().forEach(lines::add);
        jdbc.sql("""
                SELECT provider, status, public_key, capabilities::text AS caps, grant_body FROM admissions
                WHERE status <> 'REVOKED' ORDER BY provider""")
                .query((rs, i) -> agentLine(rs.getString("provider"), rs.getString("status"),
                        Security.fingerprint(rs.getString("public_key")),
                        List.of(Json.read(rs.getString("caps"), String[].class)),
                        Grant.decode(rs.getString("grant_body")).orElse(null)))
                .list().forEach(lines::add);
        jdbc.sql("SELECT id, name, goal_id, actions::text AS actions, until FROM invitations WHERE status = 'OPEN' ORDER BY created_at")
                .query((rs, i) -> {
                    OffsetDateTime until = rs.getObject("until", OffsetDateTime.class);
                    Grant.Rights rights = Grant.Rights.person("guest", rs.getString("name"),
                            rs.getString("goal_id") == null ? null : List.of(rs.getString("goal_id")),
                            List.of(Json.read(rs.getString("actions"), ActionType[].class)));
                    return "open invitation " + rs.getString("id") + " for " + rs.getString("name") + " "
                            + describeRights(rights, until == null ? null : until.toInstant(), title, zone);
                })
                .list().forEach(lines::add);
        return lines;
    }

    /**
     * One agent for "who has access?": an admitted agent with what its grant covers (and what it asks for beyond
     * that, if anything), any other agent with what it asked for.
     */
    static String agentLine(String provider, String status, String fingerprint, List<String> requested, Grant grant) {
        String head = "agent " + provider + " " + admissionWords(status) + " (" + fingerprint + ") for ";
        if (!"ADMITTED".equals(status) || grant == null || grant.rights() == null
                || grant.rights().capabilities() == null) {
            return head + readableList(requested);
        }
        List<String> granted = grant.rights().capabilities();
        List<String> more = requested.stream().filter(c -> !granted.contains(c)).toList();
        return head + readableList(granted) + (more.isEmpty() ? "" : "; also asks for " + readableList(more));
    }

    private String goalTitle(String goalId) {
        return ledger.load(goalId).map(GoalState::text).orElse("a goal that no longer exists");
    }

    /** "on "Plan the trip" until 2026-12-01, view only" or "on all goals, may list goals, add task". */
    static String describeRights(Grant.Rights rights, Instant until, Function<String, String> goalTitle, ZoneId zone) {
        String goals = rights.goals() == null ? "all goals" : rights.goals().isEmpty() ? "no goals"
                : rights.goals().stream().map(g -> "\"" + goalTitle.apply(g) + "\"").collect(Collectors.joining(", "));
        List<ActionType> actions = rights.actions() == null ? GUEST_ACTIONS : rights.actions();
        String may = Set.copyOf(actions).equals(Set.copyOf(VIEW_ACTIONS)) ? "view only"
                : Set.copyOf(actions).equals(Set.copyOf(GUEST_ACTIONS)) ? "may view and change tasks"
                : "may " + readableList(actions.stream().map(a -> a.name().toLowerCase(Locale.ROOT).replace('_', ' '))
                .toList());
        return "on " + goals + (until == null ? "" : " until " + date(until, zone)) + ", " + may;
    }

    private static String admissionWords(String status) {
        return switch (status) {
            case "ADMITTED" -> "admitted";
            case "PENDING" -> "waiting for admission";
            case "REJECTED" -> "refused";
            default -> status.toLowerCase(Locale.ROOT);
        };
    }

    static String readableList(List<String> items) {
        return items.isEmpty() ? "nothing" : String.join(", ", items);
    }

    private static String date(Instant instant) {
        return date(instant, ZoneId.systemDefault());
    }

    /** yyyy-MM-dd in the given zone. */
    static String date(Instant instant, ZoneId zone) {
        return LocalDate.ofInstant(instant, zone).toString();
    }
}
