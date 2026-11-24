package dev.capabilityfabric.kernel.registry;

import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.security.Allowance;
import dev.capabilityfabric.kernel.security.SecurityService;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.SchemaValidator;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.SignedManifest;
import dev.capabilityfabric.sdk.FabricSecurity;
import io.nats.client.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The live set of capabilities. Providers register through the kernel: every few seconds an agent sends a signed
 * heartbeat with its registry entries ({@link #renew}) and when it stops offering one it withdraws it
 * ({@link #withdraw}). Nothing is read from a bucket others can write, and nothing is configured by hand.
 *
 * <p>A provider is live while its lease holds: {@code ttl} seconds (at most {@link #MAX_TTL_SECONDS}) from the moment
 * the kernel received its heartbeat, whatever time the manifest claims. With security on an entry counts only when the
 * key that signed the heartbeat signed the entry too, the key is admitted for it ({@link SecurityService#admits}), the
 * entry carries a valid X25519 seal key ({@link #validSealKey}; every call to the instance is sealed for it, so no
 * other process on the broker can read it) and its instance id starts with the provider's name
 * ({@link CapabilityManifest#instancePrefix}); an instance id stays bound to the key that listed it until its last
 * entry leaves, so another key can neither take it over nor withdraw it. One key may list at most
 * {@link #MAX_ENTRIES_PER_KEY} entries (of a longer heartbeat the first ones are looked at and the rest refused) and
 * send a burst of {@link #HEARTBEAT_BURST} heartbeats, then one a second. A withdrawn entry leaves at once, and a
 * heartbeat signed before the withdrawal that arrives after it does not bring it back. A grown capability the owner
 * retired is not listed, whoever offers it ({@link SecurityService#retiredCapabilities}).
 *
 * <p>Withdrawn, expired and revoked providers disappear at once. Facts record an entry joining and leaving, each at
 * most once per entry in {@link #REJOIN_QUIET}: an entry listed soon after an entry of its key left is live at once,
 * but its joining is recorded only when that time has passed ({@link #REJOIN_QUIET} says how). An instance that did not
 * answer a call (no subscriber on its subject) is not offered for calls again until its next heartbeat arrives. After a
 * kernel restart the registry is empty until the agents' next heartbeats.
 *
 * <p>While the kernel's own connection to the broker is down no heartbeat can reach it, so no lease runs out
 * meanwhile: every entry whose lease held when the kernel was last found connected stays live, and once the
 * connection is back (or the client counts a reconnect the registry has not seen) each such lease runs at least one
 * more lease from that moment, the time its agent needs to renew after the broker returns ({@link BrokerLink}). An
 * entry whose agent does not renew within that lease leaves, and that leaving starts no {@link #REJOIN_QUIET} time,
 * so the agent's joining is recorded at once when it comes back.
 *
 * <p>A joining or leaving the ledger could not record (the database is away) is not lost: the entry is live or gone
 * at once as usual, and the sweep records the fact later, every leaving before a later joining of the same entry. The
 * {@link Joined} event follows its recorded fact; a listener that fails is logged and not called again.
 */
@Component
public class CapabilityRegistry {

    private static final Logger log = LoggerFactory.getLogger(CapabilityRegistry.class);
    /**
     * Entries one signing key may have listed at a time, and entries of one heartbeat that are looked at. Quotas the
     * kernel keeps per key (decision records, inbox posts) would mean little if a key could list any number of entries.
     */
    public static final int MAX_ENTRIES_PER_KEY = 32;
    /** How long the registry goes by the list of retired grown capabilities it read before it reads it again. */
    static final Duration RETIRED_REFRESH = Duration.ofSeconds(5);
    /** The longest lease one heartbeat buys, whatever ttl a manifest asks for. */
    public static final int MAX_TTL_SECONDS = 60;
    /** Heartbeats one key may send at once; afterwards one per second. */
    static final int HEARTBEAT_BURST = 3;
    /**
     * How long a withdrawal is remembered. A heartbeat signed before it is refused by the replay guard once it is
     * older than {@link Security#CLOCK_SKEW}; twice that covers a signer whose clock runs ahead.
     */
    static final Duration WITHDRAWAL_MEMORY = Security.CLOCK_SKEW.multipliedBy(2);
    /**
     * How long after the recorded leaving of an entry of a key (withdrawn, expired, refused at a heartbeat or
     * revoked; a lease that ran out after the connection to the broker came back starts no such time, see {@link
     * #sweep}) no joining of that key's entries is recorded. An entry listed within that time is live and called at
     * once, but its {@link Facts#CAPABILITY_JOINED} fact and its {@link Joined} event come only when the time has
     * passed and the entry is still listed (the sweep records them); if it leaves before that, its leaving is not
     * recorded either. The time is counted per key, not per entry, so new instance ids do not start it afresh: a key
     * that withdraws and lists its entries again and again, or lets their leases run out between heartbeats, causes
     * at most one recorded joining and one recorded leaving per entry in this time. With security off the provider's
     * name stands for the key.
     */
    static final Duration REJOIN_QUIET = Duration.ofMinutes(5);

    private final LedgerService ledger;
    private final Map<String, CapabilityManifest> live = new ConcurrentHashMap<>();
    /** The public key that signed each live entry, by registry key. */
    private final Map<String, String> signers = new ConcurrentHashMap<>();
    /** The public key each live instance signs with: results, bids, proposals and messages must carry it. */
    private final Map<String, String> instanceKeys = new ConcurrentHashMap<>();
    /** The X25519 key each live instance receives sealed calls with, when it published one. */
    private final Map<String, String> sealKeys = new ConcurrentHashMap<>();
    /** When each live entry's lease ends, by registry key. */
    private final Map<String, Instant> leaseEnds = new ConcurrentHashMap<>();
    /** When the kernel last received each live entry, by registry key. */
    private final Map<String, Instant> received = new ConcurrentHashMap<>();
    /** Instances that had no responder for a call, with the moment that was noticed; hidden until they renew. */
    private final Map<String, Instant> unreachable = new ConcurrentHashMap<>();
    /** When each recently withdrawn entry's withdrawal was signed, by registry key. */
    private final Map<String, Instant> withdrawn = new HashMap<>();
    private final Allowance heartbeats = new Allowance(HEARTBEAT_BURST, Duration.ofSeconds(1));
    /** When an entry of each key last left with a recorded fact, kept for {@link #REJOIN_QUIET}; see {@link #party}. */
    private final Map<String, Instant> lastLeft = new HashMap<>();
    /** Live entries, by registry key, whose joining is not recorded yet ({@link #REJOIN_QUIET}). */
    private final Set<String> quiet = new HashSet<>();
    /** Live entries, by registry key, whose joining the ledger could not record yet; the sweep tries again. */
    private final Set<String> unrecordedJoins = new LinkedHashSet<>();
    /** Entries that left but whose leaving the ledger could not record yet, by registry key; the sweep tries again. */
    private final Map<String, Leaving> unrecordedLeaves = new LinkedHashMap<>();
    /** Live entries, by registry key, whose lease was extended when the connection to the broker came back. */
    private final Set<String> extended = new HashSet<>();
    private final BrokerLink link;
    /** When the sweep first found the kernel away from the broker, while it still is; {@code null} otherwise. */
    private volatile Instant awaySince;
    /** The last time the sweep found the kernel connected to the broker. */
    private volatile Instant lastConnected = Instant.MIN;
    /** How many reconnects the client had counted when the sweep last looked ({@link BrokerLink#reconnects()}). */
    private volatile long reconnectsSeen;
    /** When the sweep last found the connection to the broker back; {@code null} before that. */
    private volatile Instant reconnectedAt;
    /** The retired grown capabilities as last read, and when; guarded by this registry's lock. */
    private Set<String> retired = Set.of();
    private Instant retiredRead = Instant.MIN;
    private final SecurityService security;
    private final ApplicationEventPublisher events;

    /**
     * Published when a capability's joining is recorded, so goals that were waiting for it can be planned again: when
     * it is listed, or, for an entry listed within {@link #REJOIN_QUIET} of a leaving of its key, once that time has
     * passed.
     */
    public record Joined(CapabilityManifest manifest) {
    }

    /** Published when the owner revokes a key: every entry that key signed leaves at once. */
    public record KeyRevoked(String publicKey) {
    }

    /**
     * What one heartbeat did: {@code limited} when it came too soon after the key's previous ones (nothing was
     * looked at), how many of its entries are listed now, and why each other one is not.
     */
    public record Renewal(boolean limited, int listed, List<String> refused) {

        public Renewal {
            refused = List.copyOf(refused);
        }
    }

    /**
     * What the registry needs to know of the kernel's own connection to the broker: whether it is connected now, and
     * how many times the client has re-established it so far.
     */
    public interface BrokerLink {

        /** A connection that is always up: leases run out by the clock alone. */
        BrokerLink ALWAYS = new BrokerLink() {
            @Override
            public boolean connected() {
                return true;
            }

            @Override
            public long reconnects() {
                return 0;
            }
        };

        boolean connected();

        long reconnects();

        /** The kernel's NATS connection as a link. */
        static BrokerLink of(Connection connection) {
            return new BrokerLink() {
                @Override
                public boolean connected() {
                    return connection.getStatus() == Connection.Status.CONNECTED;
                }

                @Override
                public long reconnects() {
                    return connection.getStatistics().getReconnects();
                }
            };
        }
    }

    /** An entry's leaving as it is to be recorded; {@code outage} when it starts no {@link #REJOIN_QUIET} time. */
    private record Leaving(CapabilityManifest manifest, String reason, String party, Instant at, boolean outage) {
    }

    /** Follows the kernel's NATS connection, when there is one, for {@link BrokerLink}. */
    @Autowired
    public CapabilityRegistry(LedgerService ledger, @Lazy SecurityService security, ApplicationEventPublisher events,
                              ObjectProvider<Connection> connection) {
        this(ledger, security, events, linkOf(connection.getIfAvailable()));
    }

    /** A registry whose connection to the broker is always up ({@link BrokerLink#ALWAYS}). */
    public CapabilityRegistry(LedgerService ledger, SecurityService security, ApplicationEventPublisher events) {
        this(ledger, security, events, BrokerLink.ALWAYS);
    }

    public CapabilityRegistry(LedgerService ledger, SecurityService security, ApplicationEventPublisher events,
                              BrokerLink link) {
        this.events = events;
        this.ledger = ledger;
        this.security = security;
        this.link = link;
        this.reconnectsSeen = link.reconnects();
    }

    private static BrokerLink linkOf(Connection connection) {
        return connection == null ? BrokerLink.ALWAYS : BrokerLink.of(connection);
    }

    /** The public key a live instance signs with (results, bids, proposals, messages). */
    public Optional<String> keyOf(String instanceId) {
        return Optional.ofNullable(instanceId == null ? null : instanceKeys.get(instanceId));
    }

    /** The key calls to this instance are sealed for, as published in its verified registry entry. */
    public Optional<String> sealKeyOf(String instanceId) {
        return Optional.ofNullable(instanceId == null ? null : sealKeys.get(instanceId));
    }

    /** Live manifests whose entries were signed by {@code publicKey}. */
    public List<CapabilityManifest> signedBy(String publicKey) {
        if (publicKey == null) {
            return List.of();
        }
        return live().stream().filter(m -> publicKey.equals(signers.get(m.registryKey()))).toList();
    }

    /**
     * One heartbeat: renews the lease of every entry that may be listed and lists new ones. {@code signer} is the
     * verified key of the request ({@code null} with security off), {@code signedAt} when it was signed (the
     * receive time with security off) and {@code now} when it arrived. Only the first {@link #MAX_ENTRIES_PER_KEY}
     * entries are looked at; the others are refused together, so an entry too many costs the key that entry only.
     * An entry the signer listed earlier that no longer passes the admission check, or whose grown capability the
     * owner retired, leaves at once.
     */
    public synchronized Renewal renew(String signer, List<SignedManifest> entries, Instant signedAt, Instant now) {
        if (security.enabled()) {
            if (signer == null) {
                return new Renewal(false, 0, List.of("the heartbeat is not signed"));
            }
            if (!heartbeats.take(signer, now)) {
                return new Renewal(true, 0, List.of("more than one heartbeat a second"));
            }
        }
        int listed = 0;
        List<String> refused = new ArrayList<>();
        Set<String> retiredNow = retired(now);
        for (SignedManifest entry : entries.subList(0, Math.min(entries.size(), MAX_ENTRIES_PER_KEY))) {
            Optional<String> problem = accept(signer, entry, signedAt, now, retiredNow);
            if (problem.isEmpty()) {
                listed++;
            } else {
                String key = entry == null || entry.manifest() == null ? "an entry" : entry.manifest().registryKey();
                refused.add(key + ": " + problem.get());
            }
        }
        int beyond = entries.size() - MAX_ENTRIES_PER_KEY;
        if (beyond > 0) {
            refused.add(beyond + (beyond == 1 ? " more entry" : " more entries") + ": a heartbeat may carry at most "
                    + MAX_ENTRIES_PER_KEY + " entries");
        }
        return new Renewal(false, listed, refused);
    }

    /** The retired grown capabilities, read again when the last reading is older than {@link #RETIRED_REFRESH}. */
    private Set<String> retired(Instant now) {
        if (!retiredRead.plus(RETIRED_REFRESH).isAfter(now)) {
            Set<String> read = security.retiredCapabilities();
            retired = read == null ? Set.of() : read;
            retiredRead = now;
        }
        return retired;
    }

    /** Lists or renews one entry of a heartbeat; empty when it is listed now, otherwise why not. */
    private Optional<String> accept(String signer, SignedManifest entry, Instant signedAt, Instant now,
                                    Set<String> retiredNow) {
        if (entry == null || entry.manifest() == null) {
            return Optional.of("no manifest");
        }
        CapabilityManifest manifest = entry.manifest();
        String key = manifest.registryKey();
        if (retiredNow.contains(manifest.id())) {
            if (live.containsKey(key) && (signer == null || signer.equals(signers.get(key)))) {
                remove(key, "retired by the owner", now);
            }
            return Optional.of("the owner retired this capability");
        }
        List<String> errors;
        try {
            errors = SchemaValidator.validateBundled("manifest", Json.toTree(manifest));
        } catch (RuntimeException e) {
            errors = List.of(String.valueOf(e.getMessage()));
        }
        if (!errors.isEmpty()) {
            log.debug("ignoring invalid manifest {}: {}", key, errors);
            return Optional.of("invalid manifest: " + errors);
        }
        String sealKey = null;
        if (security.enabled()) {
            if (!signer.equals(entry.key())) {
                return Optional.of("signed by another key than the heartbeat");
            }
            if (!security.admits(entry)) {
                if (signer.equals(signers.get(key))) {
                    remove(key, "not admitted", now); // its own key lost its admission, or the entry left its contract
                }
                return Optional.of("not admitted for this capability");
            }
            sealKey = entry.sealKey();
            if (!sealKey(manifest.instanceId(), sealKey)) {
                if (signer.equals(signers.get(key))) {
                    remove(key, "no seal key", now);
                }
                return Optional.of("no valid seal key: with security on every call is sealed for its instance");
            }
        }
        String prefix = CapabilityManifest.instancePrefix(manifest.provider());
        if (!manifest.instanceId().startsWith(prefix)) {
            return Optional.of("the instance id must start with \"" + prefix + "\"");
        }
        Instant gone = withdrawn.get(key);
        if (gone != null && !signedAt.isAfter(gone)) {
            return Optional.of("withdrawn");
        }
        if (signer != null) {
            String bound = instanceKeys.get(manifest.instanceId());
            if (bound != null && !bound.equals(signer)) {
                log.warn("refused entry {}: instance {} is bound to another key", key, manifest.instanceId());
                return Optional.of("the instance id is bound to another key");
            }
            if (!signer.equals(signers.get(key)) && entriesOf(signer) >= MAX_ENTRIES_PER_KEY) {
                return Optional.of("its key already lists " + MAX_ENTRIES_PER_KEY + " entries");
            }
            signers.put(key, signer);
            instanceKeys.put(manifest.instanceId(), signer);
            if (sealKey != null) {
                sealKeys.put(manifest.instanceId(), sealKey);
            } else {
                sealKeys.remove(manifest.instanceId());
            }
        }
        withdrawn.remove(key);
        leaseEnds.put(key, leaseEnd(manifest, now));
        received.put(key, now);
        extended.remove(key);
        unreachable.computeIfPresent(manifest.instanceId(), (id, since) -> now.isAfter(since) ? null : since);
        CapabilityManifest previous = live.put(key, manifest);
        if (previous == null) {
            if (unrecordedLeaves.containsKey(key)) {
                unrecordedJoins.add(key);
                log.debug("capability listed again: {} from {} ({}); its joining is recorded after its earlier "
                        + "leaving", manifest.id(), manifest.provider(), manifest.instanceId());
            } else if (recentlyLeft(party(key, manifest), now)) {
                quiet.add(key);
                log.debug("capability listed again: {} from {} ({}); its joining is recorded once {} have passed since "
                        + "an entry of its key left", manifest.id(), manifest.provider(), manifest.instanceId(),
                        REJOIN_QUIET);
            } else {
                join(key, manifest);
            }
        }
        return Optional.empty();
    }

    /**
     * Who the {@link #REJOIN_QUIET} time is counted for: the key that signed the entry, or with security off (no
     * signer) the provider's name.
     */
    private String party(String key, CapabilityManifest manifest) {
        String signer = signers.get(key);
        return signer != null ? signer : "provider:" + manifest.provider();
    }

    /**
     * Whether an entry of {@code party} left with a recorded fact less than {@link #REJOIN_QUIET} before {@code now}.
     */
    private boolean recentlyLeft(String party, Instant now) {
        Instant at = lastLeft.get(party);
        return at != null && at.plus(REJOIN_QUIET).isAfter(now);
    }

    /**
     * Records that a live entry joined, then publishes the event that lets waiting goals be planned again. When the
     * ledger cannot record the fact, neither happens now: the entry is kept in {@link #unrecordedJoins} and the sweep
     * tries again. A listener of the event that fails is logged; the fact stays recorded and the event is not sent
     * again.
     */
    private void join(String key, CapabilityManifest manifest) {
        try {
            ledger.recordSystem(Facts.CAPABILITY_JOINED, new Facts.CapabilityJoined(manifest.id(), manifest.version(),
                    manifest.provider(), manifest.instanceId(), manifest.kind(), manifest.mode(), manifest.produces()),
                    "agent:" + manifest.provider() + "/" + manifest.instanceId());
        } catch (RuntimeException e) {
            if (unrecordedJoins.add(key)) {
                log.warn("cannot record that {} from {} ({}) joined, trying again at the next sweep: {}", manifest.id(),
                        manifest.provider(), manifest.instanceId(), e.getMessage());
            }
            return;
        }
        unrecordedJoins.remove(key);
        log.info("capability joined: {} v{} from {} ({})", manifest.id(), manifest.version(), manifest.provider(),
                manifest.instanceId());
        try {
            events.publishEvent(new Joined(manifest));
        } catch (RuntimeException e) {
            log.warn("a listener failed on the joining of {} from {} ({}): {}", manifest.id(), manifest.provider(),
                    manifest.instanceId(), e.getMessage(), e);
        }
    }

    /**
     * Whether {@code sealKey} is one calls to {@code instanceId} can be sealed for: the key already recorded for the
     * instance, or else a {@linkplain #validSealKey valid} one.
     */
    private boolean sealKey(String instanceId, String sealKey) {
        return sealKey != null && (sealKey.equals(sealKeys.get(instanceId)) || validSealKey(sealKey));
    }

    /**
     * Whether {@code text} is an X25519 public key in the one spelling {@link Security.SealKey#publicKeyText()}
     * writes ({@link FabricSecurity#isSealKey}) and a payload can be sealed for it: a key of low order, with which no
     * secret can be agreed, is refused like a malformed one.
     */
    static boolean validSealKey(String text) {
        if (!FabricSecurity.isSealKey(text)) {
            return false;
        }
        try {
            Security.seal(text, "seal key check", new byte[0]);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** {@link #withdraw(String, List, Instant, Instant)} arriving now. */
    public List<String> withdraw(String signer, List<SignedManifest> entries, Instant signedAt) {
        return withdraw(signer, entries, signedAt, Instant.now());
    }

    /**
     * Withdraws entries at once: with security on only those {@code signer} listed itself. Returns the registry
     * keys that left. A heartbeat signed at or before {@code signedAt} that arrives later does not list them again.
     * {@code now} is when the withdrawal arrived, from which {@link #REJOIN_QUIET} is counted.
     */
    public synchronized List<String> withdraw(String signer, List<SignedManifest> entries, Instant signedAt,
                                              Instant now) {
        List<String> removed = new ArrayList<>();
        for (SignedManifest entry : entries.stream().limit(MAX_ENTRIES_PER_KEY).toList()) {
            if (entry == null || entry.manifest() == null) {
                continue;
            }
            String key = entry.manifest().registryKey();
            if (security.enabled() && (signer == null || !signer.equals(entry.key())
                    || !signer.equals(signers.get(key)))) {
                continue;
            }
            if (live.containsKey(key)) {
                remove(key, "withdrawn", now);
                withdrawn.put(key, signedAt);
                removed.add(key);
            }
        }
        return removed;
    }

    /** The end of an entry's lease: its {@code ttl} (between 1 and {@link #MAX_TTL_SECONDS} s) after it arrived. */
    static Instant leaseEnd(CapabilityManifest manifest, Instant receivedAt) {
        return receivedAt.plusSeconds(Math.clamp(manifest.ttlSeconds(), 1, MAX_TTL_SECONDS));
    }

    /** How many live entries {@code signer} signed. */
    private long entriesOf(String signer) {
        return signers.values().stream().filter(signer::equals).count();
    }

    /** {@link #remove(String, String, Instant, boolean)} of a leaving that starts the {@link #REJOIN_QUIET} time. */
    private void remove(String key, String reason, Instant now) {
        remove(key, reason, now, false);
    }

    /**
     * Removes an entry at {@code now}; once an instance has no live entry left, its keys are forgotten. The leaving is
     * recorded, unless the entry's joining was not recorded either, and starts the {@link #REJOIN_QUIET} time of the
     * entry's key unless it is an {@code outage} leaving (a lease extended when the connection to the broker came
     * back ran out). A leaving the ledger cannot record now is kept for the sweep ({@link #unrecordedLeaves}); the
     * entry is gone at once all the same.
     */
    private void remove(String key, String reason, Instant now, boolean outage) {
        CapabilityManifest gone = live.remove(key);
        String party = gone == null ? null : party(key, gone);
        signers.remove(key);
        leaseEnds.remove(key);
        received.remove(key);
        extended.remove(key);
        if (gone == null) {
            return;
        }
        boolean waited = quiet.remove(key);
        boolean unrecorded = unrecordedJoins.remove(key);
        if (waited || unrecorded) {
            log.debug("capability left again: {} from {} ({}): {}", gone.id(), gone.provider(), gone.instanceId(),
                    reason);
        } else {
            Leaving leaving = new Leaving(gone, reason, party, now, outage);
            if (!recordLeft(leaving)) {
                unrecordedLeaves.put(key, leaving);
            }
        }
        if (live.values().stream().noneMatch(m -> m.instanceId().equals(gone.instanceId()))) {
            instanceKeys.remove(gone.instanceId());
            sealKeys.remove(gone.instanceId());
            unreachable.remove(gone.instanceId());
        }
    }

    /** The owner revoked a key: the entries it signed leave now instead of when their leases end. */
    @EventListener
    public synchronized void revoked(KeyRevoked event) {
        if (event.publicKey() == null) {
            return;
        }
        Instant now = Instant.now();
        for (Map.Entry<String, String> e : List.copyOf(signers.entrySet())) {
            if (event.publicKey().equals(e.getValue())) {
                remove(e.getKey(), "access revoked", now);
            }
        }
    }

    /**
     * A call found no subscriber on the instance's subject (it stopped without withdrawing its entries). The
     * instance is not offered for calls until a heartbeat newer than {@code now} arrives; if none comes, its lease
     * ends as usual.
     */
    public void unreachable(String instanceId, Instant now) {
        if (instanceId != null && hasEntries(instanceId)) {
            unreachable.put(instanceId, now);
            log.info("instance {} has no responder; not offered for calls until it renews its lease", instanceId);
        }
    }

    private boolean hasEntries(String instanceId) {
        return live.values().stream().anyMatch(m -> m.instanceId().equals(instanceId));
    }

    /**
     * Whether the instance stopped renewing its lease: it has no live entry, or none of its entries was renewed in
     * the last half of its lease (a healthy provider renews about three times per lease). While the kernel is away
     * from the broker no instance counts as silent, and after the connection came back that moment counts as a
     * renewal of every entry listed before it.
     */
    public boolean silent(String instanceId, Instant now) {
        boolean away = away();
        return live.entrySet().stream().filter(e -> e.getValue().instanceId().equals(instanceId))
                .noneMatch(e -> renewedRecently(e.getKey(), e.getValue(), now, away));
    }

    /**
     * Whether this instance's entry for this capability stopped being renewed: it is not live (withdrawn, expired or
     * never listed), or was not renewed in the last half of its lease, judged as {@link #silent(String, Instant)}
     * does. Other capabilities of the instance do not count.
     */
    public boolean silent(String capabilityId, String instanceId, Instant now) {
        boolean away = away();
        return entry(capabilityId, instanceId).map(e -> !renewedRecently(e.getKey(), e.getValue(), now, away))
                .orElse(true);
    }

    private boolean renewedRecently(String key, CapabilityManifest manifest, Instant now, boolean away) {
        Instant at = received.get(key);
        if (at == null || !holds(key, now, away)) {
            return false;
        }
        if (away) {
            return true;
        }
        Instant back = reconnectedAt;
        Instant since = back != null && back.isAfter(at) ? back : at;
        long halfLease = Math.clamp(manifest.ttlSeconds(), 1, MAX_TTL_SECONDS) * 500L;
        return !since.plus(Duration.ofMillis(halfLease)).isBefore(now);
    }

    /**
     * Whether no entry of the instance has a lease that holds at {@code now} ({@link #holds}): it is gone, whether or
     * not the sweep has removed its entries yet. An instance the registry never listed has no lease either.
     */
    public boolean leaseEnded(String instanceId, Instant now) {
        boolean away = away();
        return live.entrySet().stream().filter(e -> e.getValue().instanceId().equals(instanceId))
                .noneMatch(e -> holds(e.getKey(), now, away));
    }

    /**
     * Whether the lease of this instance's entry for this capability does not hold at {@code now} ({@link #holds}):
     * withdrawn, expired or never listed, whatever the instance's other entries do.
     */
    public boolean leaseEnded(String capabilityId, String instanceId, Instant now) {
        boolean away = away();
        return entry(capabilityId, instanceId).map(e -> !holds(e.getKey(), now, away)).orElse(true);
    }

    /**
     * Whether the lease of a live entry holds at {@code now}: it has not run out, or the kernel is {@code away} from
     * the broker ({@link #away()}) and the lease still held when the kernel was last found connected.
     */
    private boolean holds(String key, Instant now, boolean away) {
        Instant end = leaseEnds.get(key);
        return end != null && (end.isAfter(now) || away && end.isAfter(lastConnected));
    }

    /**
     * Whether the kernel is away from the broker as far as leases go: not connected now, or the sweep has not yet
     * extended the leases since the connection came back (it found the kernel away, or the client counts a
     * reconnect it has not seen).
     */
    private boolean away() {
        return awaySince != null || !link.connected() || link.reconnects() != reconnectsSeen;
    }

    private Optional<Map.Entry<String, CapabilityManifest>> entry(String capabilityId, String instanceId) {
        return live.entrySet().stream().filter(e -> e.getValue().id().equals(capabilityId)
                && e.getValue().instanceId().equals(instanceId)).findFirst();
    }

    /** Why an entry leaves whose lease, extended when the connection to the broker came back, ran out. */
    static final String NOT_BACK = "lease expired: not renewed after the connection to the broker came back";

    /**
     * Leases are checked here as well, so a provider that dies without withdrawing still disappears, except while the
     * kernel is away from the broker ({@link #followLink}). Leavings and joinings the ledger could not record are
     * recorded now if it can, every leaving before a later joining of the same entry; the joining of entries listed
     * within {@link #REJOIN_QUIET} of a leaving of their key is recorded once that time has passed. A failure with
     * one entry does not stop the sweep for the others.
     */
    @Scheduled(fixedDelayString = "${fabric.kernel.registry-sweep:2s}")
    public void sweep() {
        sweep(Instant.now());
    }

    synchronized void sweep(Instant now) {
        if (!followLink(now)) {
            for (String key : List.copyOf(live.keySet())) {
                guarded(key, () -> {
                    if (!leaseEnds.getOrDefault(key, Instant.MIN).isAfter(now)) {
                        boolean outage = extended.contains(key);
                        remove(key, outage ? NOT_BACK : "lease expired", now, outage);
                    }
                });
            }
        }
        withdrawn.values().removeIf(at -> at.plus(WITHDRAWAL_MEMORY).isBefore(now));
        for (Iterator<Map.Entry<String, Leaving>> it = unrecordedLeaves.entrySet().iterator(); it.hasNext(); ) {
            if (recordLeft(it.next().getValue())) {
                it.remove();
            }
        }
        for (String key : List.copyOf(unrecordedJoins)) {
            guarded(key, () -> {
                CapabilityManifest m = live.get(key);
                if (m == null) {
                    unrecordedJoins.remove(key);
                } else if (!unrecordedLeaves.containsKey(key)) {
                    join(key, m);
                }
            });
        }
        for (String key : List.copyOf(quiet)) {
            guarded(key, () -> {
                CapabilityManifest m = live.get(key);
                if (m == null) {
                    quiet.remove(key);
                } else if (!recentlyLeft(party(key, m), now)) {
                    quiet.remove(key);
                    join(key, m);
                }
            });
        }
        lastLeft.values().removeIf(at -> !at.plus(REJOIN_QUIET).isAfter(now));
    }

    /** Runs one entry's part of the sweep; a failure is logged and the sweep goes on with the next entry. */
    private static void guarded(String key, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.warn("the registry sweep failed for {}: {}", key, e.getMessage(), e);
        }
    }

    /**
     * Follows the kernel's connection to the broker at a sweep; true while the kernel is away, when no lease runs
     * out. Once the connection is back, or the client counts a reconnect not seen yet (it was away between two
     * sweeps), every live entry whose lease held when the kernel was last found connected gets at least one lease
     * from {@code now} ({@link #leaseEnd}) and is marked {@link #extended} until its next heartbeat.
     */
    private boolean followLink(Instant now) {
        boolean connected = link.connected();
        long reconnects = link.reconnects();
        if (!connected) {
            if (awaySince == null) {
                awaySince = now;
                log.info("not connected to the broker: no lease runs out until the connection is back");
            }
            return true;
        }
        if (awaySince != null || reconnects != reconnectsSeen) {
            int count = 0;
            for (Map.Entry<String, CapabilityManifest> e : live.entrySet()) {
                Instant end = leaseEnds.get(e.getKey());
                Instant renewed = leaseEnd(e.getValue(), now);
                if (end != null && end.isAfter(lastConnected) && renewed.isAfter(end)) {
                    leaseEnds.put(e.getKey(), renewed);
                    extended.add(e.getKey());
                    count++;
                }
            }
            log.info("connected to the broker again: {} leases run one more lease from now", count);
            reconnectedAt = now;
            reconnectsSeen = reconnects;
            awaySince = null;
        }
        lastConnected = now;
        return false;
    }

    /**
     * Records a leaving; true when it is recorded. Unless it is an outage leaving, it starts the
     * {@link #REJOIN_QUIET} time of its key from when the entry left.
     */
    private boolean recordLeft(Leaving leaving) {
        CapabilityManifest m = leaving.manifest();
        try {
            ledger.recordSystem(Facts.CAPABILITY_LEFT, new Facts.CapabilityLeft(m.id(), m.version(), m.provider(),
                    m.instanceId(), leaving.reason()), "kernel");
        } catch (RuntimeException e) {
            if (!unrecordedLeaves.containsValue(leaving)) {
                log.warn("cannot record that {} from {} ({}) left, trying again at the next sweep: {}", m.id(),
                        m.provider(), m.instanceId(), e.getMessage());
            }
            return false;
        }
        log.info("capability left: {} from {} ({}): {}", m.id(), m.provider(), m.instanceId(), leaving.reason());
        if (!leaving.outage()) {
            lastLeft.merge(leaving.party(), leaving.at(), (a, b) -> a.isAfter(b) ? a : b);
        }
        return true;
    }

    /**
     * Every entry whose lease holds now ({@link #holds}), by capability id and instance, without the instances that
     * had no responder since their last heartbeat.
     */
    public List<CapabilityManifest> live() {
        Instant now = Instant.now();
        boolean away = away();
        return live.entrySet().stream()
                .filter(e -> holds(e.getKey(), now, away))
                .filter(e -> !hidden(e.getKey(), e.getValue()))
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(CapabilityManifest::id).thenComparing(CapabilityManifest::instanceId))
                .toList();
    }

    /** An entry of an unreachable instance that has not been renewed since that was noticed. */
    private boolean hidden(String key, CapabilityManifest manifest) {
        Instant since = unreachable.get(manifest.instanceId());
        Instant at = received.get(key);
        return since != null && (at == null || !at.isAfter(since));
    }

    /** One live manifest per capability id (the highest version), invoked providers only. */
    public Map<String, CapabilityManifest> invokedCapabilities() {
        Map<String, CapabilityManifest> result = new LinkedHashMap<>();
        for (CapabilityManifest m : live()) {
            if (m.mode() != CapabilityManifest.Mode.INVOKED) {
                continue;
            }
            result.merge(m.id(), m, (a, b) -> compareVersions(a.version(), b.version()) >= 0 ? a : b);
        }
        return result;
    }

    /** Live instances of one major version of an invoked capability. */
    public List<CapabilityManifest> instances(String capabilityId, int major) {
        return live().stream().filter(m -> m.id().equals(capabilityId) && m.major() == major
                && m.mode() == CapabilityManifest.Mode.INVOKED).toList();
    }

    /** A live entry of this capability from this instance. */
    public Optional<CapabilityManifest> instance(String capabilityId, String instanceId) {
        return live().stream().filter(m -> m.id().equals(capabilityId) && m.instanceId().equals(instanceId)).findFirst();
    }

    /** Whether any instance offers the capability now. */
    public boolean has(String capabilityId) {
        return live().stream().anyMatch(m -> m.id().equals(capabilityId));
    }

    /** The live capabilities for people: one view per capability and provider, with its instances. */
    public List<KernelApi.CapabilityView> views() {
        Map<String, List<CapabilityManifest>> byId = live().stream()
                .collect(Collectors.groupingBy(m -> m.id() + "@" + m.provider(), LinkedHashMap::new, Collectors.toList()));
        List<KernelApi.CapabilityView> views = new ArrayList<>();
        byId.values().forEach(group -> {
            CapabilityManifest m = group.getFirst();
            views.add(new KernelApi.CapabilityView(m.id(), m.version(), m.name(), m.provider(), m.kind().name(),
                    m.mode().name(), m.autonomy().name(), group.size(),
                    group.stream().map(CapabilityManifest::instanceId).toList(), m.consumes(), m.produces(),
                    m.description(), m.model()));
        });
        return views;
    }

    /**
     * Orders dotted version numbers part by part as whole numbers of any length; with equal leading parts the
     * version with more parts is the higher one.
     */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = new BigInteger(x[i]).compareTo(new BigInteger(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }
}
