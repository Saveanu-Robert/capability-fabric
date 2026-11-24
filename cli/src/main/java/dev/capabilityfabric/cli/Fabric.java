package dev.capabilityfabric.cli;

import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Reply;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.Subjects;
import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;

/**
 * The CLI's only dependency on the system: signed request/reply, either over NATS (on the machine or the local
 * network) or over HTTPS through the gateway (from anywhere). Every request is signed with this device's key. The
 * kernel signs each reply for the request it answers (its subject and nonce); once the device trusts a kernel key,
 * only replies signed by it for this request, and dated within the allowed clock skew, are accepted.
 *
 * <p>Every request also carries a fresh X25519 key ({@link Security#HEADER_REPLY_SEAL}) that is part of what the
 * device signs ({@link Security#requestScope}). The kernel seals its reply for that key, so other connections of the
 * person role on the broker, and the gateway in between, see only ciphertext; the private half lives only for the one
 * request.
 *
 * <p>A pairing or joining code travels sealed for the kernel: the device first asks the kernel for its keys
 * ({@link #hello}), checks who signed them like any other reply, and then seals the code for the kernel's sealing key
 * ({@link #exchangeSealed}), bound to the subject and to this device's own signing key ({@link Security#claimContext}).
 * The broker and the gateway see only ciphertext, and the kernel opens it with the key that signed the request, so
 * sealed bytes that someone else re-signs with their own key do not open and cannot pair or join that key.
 */
final class Fabric implements AutoCloseable {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    /** How often a wait for a reply over NATS checks that the broker connection is still the one it sent on. */
    static final Duration POLL = Duration.ofMillis(250);
    /** Subjects that only read; a timeout there cannot have changed anything. */
    static final Set<String> READS = Set.of(Subjects.INTENT_INBOX, Subjects.QUERY_GOAL, Subjects.QUERY_GOALS,
            Subjects.SECURITY_HELLO);
    static final String LOST = "the connection to the broker was lost while waiting for the kernel's answer; what "
            + "you asked may or may not have been done, so look (for example: fabric \"list my goals\") before asking again";

    private static final System.Logger LOG = System.getLogger(Fabric.class.getName());

    /** Sends one signed request and returns a reply with the reply's own signature, if it has one. */
    interface Transport {
        /**
         * A transport that can receive several replies (NATS) returns the first one {@code screen} accepts and ignores
         * the others until the timeout; one that receives a single reply (HTTPS) returns it as it came. The fabric
         * checks the returned reply again either way.
         *
         * @param sealed    true when {@code body} is sealed for the kernel ({@link Security#seal}); the transport
         *                  marks it with {@link Security#HEADER_SEALED} so the kernel opens it before reading it
         * @param replySeal the public key the reply is to be sealed for; the stamp was made for
         *                  {@link Security#requestScope} of the subject and this key
         */
        Raw send(String subject, byte[] body, boolean sealed, Security.Stamp stamp, String replySeal,
                 Optional<String> grant, Duration timeout, Screen screen);
    }

    /** Decides whether a reply can be accepted for the request in flight. */
    interface Screen {
        /**
         * Why the reply cannot be accepted (it may be someone else's), or empty when it can.
         *
         * @throws CliException when the reply is the kernel's own but cannot be used, which ends the request
         */
        Optional<String> refusal(Raw reply);
    }

    /** The replies arriving on one request's own inbox. */
    interface Replies {
        /**
         * The next reply, or {@code null} when none arrived within {@code wait}.
         *
         * @throws CliException when the broker reports that nobody listens on the subject
         */
        Raw next(Duration wait) throws InterruptedException;
    }

    /**
     * A reply as it arrived: its bytes, the signature it carried (if any, made over these bytes as they are), and
     * whether the bytes are sealed for the request's reply key ({@link Security#HEADER_SEALED}).
     */
    record Raw(byte[] data, Optional<Security.Stamp> stamp, boolean sealed) {
        /** A reply whose bytes are not sealed. */
        Raw(byte[] data, Optional<Security.Stamp> stamp) {
            this(data, stamp, false);
        }
    }

    /** A checked reply and the kernel key that signed it ({@code null} when it was not signed). */
    record Answer(Reply reply, String signer) {
    }

    /**
     * A reply's data and the date "due today", "days left", "overdue" and "stops at today" are counted from: the
     * kernel's own date when the reply carries it ({@link #today}).
     */
    record Dated<T>(T data, LocalDate today) {
    }

    private final Connection connection;
    private final Transport transport;
    private final Device device;
    private volatile String expectedKernel;

    private Fabric(Connection connection, Transport transport, Device device) {
        this.connection = connection;
        this.transport = transport;
        this.device = device;
    }

    /** A fabric over any transport, without a broker connection (tests, and the gateway path). */
    static Fabric over(Transport transport, Device device) {
        return new Fabric(null, transport, device);
    }

    /**
     * NATS, with the person role's NKey and TLS trusting setup's certificate authority when the secrets folder has
     * them. Replies arrive under the person role's own inbox prefix, the only one the broker lets people read. The
     * client library's own error and reconnect reports are kept off the terminal; a broker error is remembered and
     * named when a request then fails.
     */
    static Fabric connect(String url, Device device, Optional<Secrets> secrets) {
        BrokerErrors errors = new BrokerErrors();
        Optional<Path> seed = Optional.empty();
        try {
            Options.Builder options = Options.builder().server(url).connectionName("fabric-cli")
                    .connectionTimeout(Duration.ofSeconds(5)).maxReconnects(3)
                    .inboxPrefix(Subjects.INBOX_PERSON).useTimeoutException().errorListener(errors);
            seed = secrets.flatMap(Secrets::personSeed);
            seed.ifPresent(file -> options.authHandler(BrokerAuth.nkey(file.toString())));
            if (url.startsWith("tls://")) {
                Path ca = secrets.flatMap(Secrets::authority).orElseThrow(() -> new CliException("TLS needs the "
                        + "certificate authority: set FABRIC_SECRETS to the folder setup created (usually ./secrets in "
                        + "the project)"));
                options.sslContext(BrokerAuth.trusting(ca.toString()));
            }
            Connection connection = Nats.connect(options.build());
            return new Fabric(connection, natsTransport(connection, errors), device);
        } catch (CliException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("interrupted");
        } catch (Exception e) {
            throw new CliException(connectFailure(url, e, secrets, seed));
        }
    }

    /**
     * Why connecting failed. A broker that takes only TLS, reached without it, is said with what is missing here:
     * no secrets folder at all (as when fabric runs outside the project folder, where it finds no ./secrets and so
     * uses a plain URL), a secrets folder without the certificate authority, or a plain URL named on purpose; a
     * broker without TLS reached with it is said too. A refused login names the key that was used, or that none was
     * found. Anything else means the broker could not be reached.
     *
     * @param secrets the secrets folder found on this machine ({@code Secrets.find}), if any
     * @param seed    the person key the login used, if any
     */
    static String connectFailure(String url, Exception e, Optional<Secrets> secrets, Optional<Path> seed) {
        String reason = rootMessage(e);
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("ssl required by server")) {
            String head = "the broker at " + url + " takes only TLS connections";
            if (secrets.isEmpty()) {
                return head + ", and no secrets folder was found here (FABRIC_SECRETS, else ./secrets in the folder "
                        + "fabric runs from). Run fabric from the project folder, or set FABRIC_SECRETS to the secrets "
                        + "folder setup created; for a broker on another machine also set NATS_URL=tls://<host>:4222";
            }
            if (!hasAuthority(secrets.get())) {
                return head + ", and the secrets folder " + secrets.get().dir().toAbsolutePath() + " has no "
                        + "certificate authority (public/ca.crt). Run setup again, or set FABRIC_SECRETS to the "
                        + "secrets folder setup created";
            }
            return head + ": use a tls:// address, for example NATS_URL=" + Secrets.TLS_URL;
        }
        if (lower.contains("ssl connection wanted by client")) {
            return "the broker at " + url + " does not offer TLS: use a nats:// address, for example NATS_URL="
                    + Secrets.PLAIN_URL;
        }
        if (lower.contains("authorization")) {
            return "the broker at " + url + " refused this device's login (" + reason + "): " + seed
                    .map(p -> "the person key in " + p + " is not one it knows; run setup again or use the secrets "
                            + "folder of this system")
                    .orElse("no person key was found; set FABRIC_SECRETS to the folder setup created (usually "
                            + "./secrets in the project)");
        }
        return "cannot reach NATS at " + url + " (" + reason + "). Is the system running? Try: docker compose up -d";
    }

    /** True when {@code secrets} holds the certificate authority. */
    private static boolean hasAuthority(Secrets secrets) {
        try {
            return secrets.authority().isPresent();
        } catch (CliException e) {
            // A folder that cannot be read fails where it is first read, before any connection is tried.
            return false;
        }
    }

    /**
     * HTTPS through the gateway. The certificate authority behind the gateway must match every pin in
     * {@code pins} and the one this device remembers for the gateway; see {@link GatewayTrust}.
     */
    static Fabric gateway(String url, Device device, List<String> pins) {
        URI uri = gatewayUri(url);
        try {
            var context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{
                    new GatewayTrust.PinningTrust(GatewayTrust.endpoint(uri), device, pins)}, new SecureRandom());
            HttpClient client = HttpClient.newBuilder().sslContext(context).connectTimeout(Duration.ofSeconds(10)).build();
            return over(httpsTransport(client, uri), device);
        } catch (GeneralSecurityException e) {
            throw new CliException("cannot set up HTTPS for " + url + ": " + e.getMessage());
        }
    }

    /** The gateway's address without a trailing slash; only https with a host is accepted. */
    static URI gatewayUri(String url) {
        String u = url.strip();
        URI uri;
        try {
            uri = URI.create(u.endsWith("/") ? u.substring(0, u.length() - 1) : u);
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new CliException("--gateway needs an https:// address, for example https://localhost:8443");
        }
        return uri;
    }

    /** The broker connection; absent over the gateway. */
    Optional<Connection> connection() {
        return Optional.ofNullable(connection);
    }

    Device device() {
        return device;
    }

    /**
     * Until this device trusts a kernel key, replies must be signed by the kernel with this key id (from
     * {@code --kernel} or an invitation). The caller pins the key once the answer it was waiting for arrived.
     */
    void expectKernel(String keyId) {
        this.expectedKernel = keyId;
    }

    /** {@link #exchange} with the {@link #DEFAULT_TIMEOUT}, keeping only the reply. */
    Reply request(String subject, Object payload) {
        return request(subject, payload, DEFAULT_TIMEOUT);
    }

    Reply request(String subject, Object payload, Duration timeout) {
        return exchange(subject, payload, timeout).reply();
    }

    Answer exchange(String subject, Object payload) {
        return exchange(subject, payload, DEFAULT_TIMEOUT);
    }

    /**
     * Signs {@code payload} for {@code subject} together with a reply key made for this request only, sends it and
     * returns the reply accepted for this very request (see {@link #screen}), opened with that key when it is
     * sealed, with the key that signed it. Nothing is sent when an expected kernel contradicts the pin.
     */
    Answer exchange(String subject, Object payload, Duration timeout) {
        return send(subject, Json.bytes(payload), false, timeout);
    }

    /**
     * {@link #exchange} with {@code payload} sealed for the kernel's sealing key {@code sealKey} (from {@link #hello})
     * with {@link Security#claimContext} of the subject and this device's signing key as the context, so only that
     * kernel can read it, and only for a request this device signed. The device signs the sealed bytes, as they travel.
     */
    Answer exchangeSealed(String subject, Object payload, String sealKey) {
        byte[] sealed;
        try {
            sealed = Security.seal(sealKey, Security.claimContext(subject, device.identity().publicKeyText()),
                    Json.bytes(payload));
        } catch (IllegalArgumentException e) {
            throw new CliException("the kernel's sealing key is not a valid key; nothing was sent");
        }
        return send(subject, sealed, true, DEFAULT_TIMEOUT);
    }

    private Answer send(String subject, byte[] body, boolean sealed, Duration timeout) {
        Optional<String> expected = Optional.ofNullable(expectedKernel);
        refuseContradiction(device, expected);
        Security.SealKey seal = Security.SealKey.generate();
        String replySeal = seal.publicKeyText();
        Security.Stamp stamp = device.identity().stamp(Security.requestScope(subject, replySeal), body);
        Screen screen = raw -> screen(device, expected, subject, stamp.nonce(), seal, raw, System.currentTimeMillis());
        Raw raw = transport.send(subject, body, sealed, stamp, replySeal, device.grant().map(Grant::encode), timeout,
                screen);
        return verify(device, expected, subject, stamp.nonce(), seal, raw, System.currentTimeMillis());
    }

    /**
     * The kernel's keys ({@link Subjects#SECURITY_HELLO}), for sealing a pairing or joining code. The reply passes the
     * same checks as any other: a pinned or expected kernel must have signed it, and a reply that claims another
     * kernel's key than the one that signed it is refused. Empty when the kernel gives no usable sealing key: nobody
     * answers on the subject, the kernel refuses, or the reply is unsigned or names no sealing key. The caller decides
     * whether a code may then travel unsealed.
     */
    Optional<KernelApi.Hello> hello() {
        Answer answer;
        try {
            answer = exchange(Subjects.SECURITY_HELLO, Map.of());
        } catch (NoResponders e) {
            return Optional.empty();
        }
        KernelApi.Hello hello;
        try {
            hello = answer.reply().ok() ? answer.reply().dataAs(KernelApi.Hello.class) : null;
        } catch (RuntimeException e) {
            throw new CliException("the kernel's keys cannot be read; nothing was sent");
        }
        if (hello == null || answer.signer() == null || hello.sealKey() == null || hello.sealKey().isBlank()) {
            return Optional.empty();
        }
        if (!answer.signer().equals(hello.key())) {
            throw new CliException("the kernel's keys are signed by another key than the one they name; nothing was "
                    + "sent");
        }
        return Optional.of(hello);
    }

    /** The reply's bytes opened with the request's reply key, or empty when they cannot be opened with it. */
    private static Optional<byte[]> opened(Security.SealKey seal, String subject, String requestNonce, Raw raw) {
        if (seal == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Security.open(seal, Security.replyScope(subject, requestNonce), raw.data()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** An expected kernel id (from {@code --kernel} or an invitation) that contradicts the pinned key. */
    private static void refuseContradiction(Device device, Optional<String> expected) {
        Optional<String> pinned = device.kernelKey();
        if (pinned.isPresent() && expected.isPresent() && !KernelId.matches(expected.get(), pinned.get())) {
            throw new CliException("this device trusts the kernel " + fingerprint(pinned.get())
                    + ", not the expected kernel " + expected.get() + "; nothing is sent to or accepted from it");
        }
    }

    /** The key that signed this reply for this request, or {@code null} when it carries no valid signature for it. */
    private static String signer(String subject, String requestNonce, Raw raw) {
        return raw.stamp().filter(s -> s.verifies(Security.replyScope(subject, requestNonce), raw.data()))
                .map(Security.Stamp::key).orElse(null);
    }

    /** {@link #screen} for a request sent without a reply key: a sealed reply cannot be opened. */
    static Optional<String> screen(Device device, Optional<String> expected, String subject, String requestNonce,
                                   Raw raw, long now) {
        return screen(device, expected, subject, requestNonce, null, raw, now);
    }

    /**
     * Whether a reply can be accepted for the request with this nonce. A pinned key must have signed it; before any
     * key is pinned, an expected key id (given for this run, or kept by {@code --grant}) must match the signer;
     * without either, an unsigned reply is accepted but a signature that does not fit this request is not. A reply
     * signed for this request but dated further from this device's clock than {@link Security#CLOCK_SKEW} is the
     * kernel's own answer to a device with a wrong clock: that ends the request with an error. A sealed reply must
     * open with the request's reply key {@code seal}; one that does not is someone else's, unless an accepted key
     * signed it for this request, which ends the request with an error. An unsealed reply is read as it is: only
     * the signature decides whose it is, and the kernel seals what it sends to a request that carried a reply key.
     *
     * @return why the reply is refused, or empty when it is accepted
     */
    static Optional<String> screen(Device device, Optional<String> expected, String subject, String requestNonce,
                                   Security.SealKey seal, Raw raw, long now) {
        String signer = signer(subject, requestNonce, raw);
        Optional<String> pinned = device.kernelKey();
        Optional<String> wanted = pinned.isPresent() ? Optional.empty() : expected.or(device::expectedKernel);
        if (pinned.isPresent() && !pinned.get().equals(signer)) {
            return Optional.of("the answer is not signed by the kernel this device trusts (" + fingerprint(pinned.get())
                    + ")");
        }
        if (wanted.isPresent() && !KernelId.matches(wanted.get(), signer)) {
            return Optional.of("the answer is signed by " + (signer == null ? "no kernel key"
                    : "the kernel key " + KernelId.of(signer).orElse("?")) + ", not by the expected kernel "
                    + wanted.get());
        }
        if (signer == null && raw.stamp().isPresent()) {
            return Optional.of("the answer carries a signature that is not valid for this request");
        }
        if (signer != null && Math.abs(now - raw.stamp().get().ts()) > Security.CLOCK_SKEW.toMillis()) {
            throw new CliException("the kernel's answer is dated " + Instant.ofEpochMilli(raw.stamp().get().ts())
                    + ", more than " + Security.CLOCK_SKEW.toMinutes() + " minutes away from this device's clock ("
                    + Instant.ofEpochMilli(now) + "); set this device's clock right and try again");
        }
        if (raw.sealed() && opened(seal, subject, requestNonce, raw).isEmpty()) {
            if (signer != null) {
                throw new CliException("the kernel's answer is sealed but does not open with this request's key; "
                        + "refusing it");
            }
            return Optional.of("the answer is sealed for another key");
        }
        return Optional.empty();
    }

    /** {@link #verify} for a request sent without a reply key: a sealed reply cannot be opened. */
    static Answer verify(Device device, Optional<String> expected, String subject, String requestNonce, Raw raw,
                         long now) {
        return verify(device, expected, subject, requestNonce, null, raw, now);
    }

    /**
     * A reply counts only if {@link #screen} accepts it; a sealed one is then read after opening it with the
     * request's reply key {@code seal}. A kept expectation is turned into the pin by the first reply that matches
     * it; an expectation given for this run is pinned by the caller once the answer it waited for arrived.
     */
    static Answer verify(Device device, Optional<String> expected, String subject, String requestNonce,
                         Security.SealKey seal, Raw raw, long now) {
        refuseContradiction(device, expected);
        Optional<String> refusal = screen(device, expected, subject, requestNonce, seal, raw, now);
        if (refusal.isPresent()) {
            throw new CliException(refusal.get() + "; refusing it");
        }
        String signer = signer(subject, requestNonce, raw);
        byte[] data = raw.sealed() ? opened(seal, subject, requestNonce, raw).orElseThrow() : raw.data();
        if (device.kernelKey().isEmpty() && expected.isEmpty() && device.expectedKernel().isPresent()) {
            device.pinKernel(signer);
            device.forgetExpectedKernel();
        }
        return new Answer(Reply.fromBytes(data), signer);
    }

    private static String fingerprint(String key) {
        try {
            return Security.fingerprint(key);
        } catch (RuntimeException e) {
            return "a damaged key file";
        }
    }

    <T> T data(String subject, Object payload, Class<T> type) {
        return data(subject, payload, type, DEFAULT_TIMEOUT);
    }

    /** The reply's data, or the kernel's refusal text as it is. */
    <T> T data(String subject, Object payload, Class<T> type, Duration timeout) {
        return dated(subject, payload, type, timeout).data();
    }

    /**
     * The reply's data with the date to count days from ({@link #today}); the kernel's refusal text as it is when it
     * refuses.
     */
    <T> Dated<T> dated(String subject, Object payload, Class<T> type, Duration timeout) {
        Reply reply = request(subject, payload, timeout);
        if (!reply.ok()) {
            throw new CliException(reply.error() == null || reply.error().isBlank()
                    ? "the kernel refused without saying why" : reply.error());
        }
        return new Dated<>(reply.dataAs(type), today(reply));
    }

    /**
     * The date to count "due today", "days left", "overdue" and "stops at today" from: the kernel's date in the
     * person's time zone that the reply carries, so this device counts as the kernel does whatever its own clock and
     * zone say; this machine's date when the reply carries none (a kernel that does not send it).
     */
    static LocalDate today(Reply reply) {
        return reply.today() != null ? reply.today() : LocalDate.now();
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Requests over NATS, each on its own reply subscription so that only replies to this request are read. The reply
     * key travels in {@link Security#HEADER_REPLY_SEAL}, and a body sealed for the kernel is marked with
     * {@link Security#HEADER_SEALED}. The wait ends with the first reply the screen accepts, when the broker says
     * nobody listens, when the connection drops or reconnects (a reply sent meanwhile is lost), or at the timeout.
     */
    private static Transport natsTransport(Connection connection, BrokerErrors errors) {
        return (subject, body, sealed, stamp, replySeal, grant, timeout, screen) -> {
            if (connection.getStatus() != Connection.Status.CONNECTED) {
                throw new CliException("the connection to the broker is down; nothing was sent. Try again in a moment");
            }
            Headers headers = new Headers();
            requestHeaders(stamp, replySeal, sealed, grant).forEach(headers::put);
            long reconnects = connection.getStatistics().getReconnects();
            errors.clear();
            Subscription replies;
            try {
                replies = connection.subscribe(connection.createInbox());
            } catch (IllegalStateException e) {
                throw new CliException("the connection to the broker is closed; nothing was sent");
            }
            try {
                connection.publish(subject, replies.getSubject(), headers, body);
                return firstAccepted(subject, timeout, screen, wait -> next(replies, wait, subject),
                        () -> connection.getStatus() != Connection.Status.CONNECTED
                                || connection.getStatistics().getReconnects() != reconnects, errors::last);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CliException("interrupted");
            } catch (IllegalStateException e) {
                throw new CliException(LOST);
            } finally {
                try {
                    replies.unsubscribe();
                } catch (IllegalStateException ignored) {
                    // the connection closed; the subscription went with it
                }
            }
        };
    }

    /**
     * The headers of a request over NATS: the device's signature, the reply key, the sealing mark when the body is
     * sealed for the kernel, and the grant the device holds.
     */
    static Map<String, String> requestHeaders(Security.Stamp stamp, String replySeal, boolean sealed,
                                              Optional<String> grant) {
        Map<String, String> headers = new LinkedHashMap<>(stamp.headers());
        headers.put(Security.HEADER_REPLY_SEAL, replySeal);
        if (sealed) {
            headers.put(Security.HEADER_SEALED, Security.SEAL_SCHEME);
        }
        grant.ifPresent(g -> headers.put(Security.HEADER_GRANT, g));
        return headers;
    }

    /**
     * One message from the request's subscription as a reply, with its signature and whether it is sealed; the
     * broker's "no responders" status ends the wait.
     */
    private static Raw next(Subscription replies, Duration wait, String subject) throws InterruptedException {
        Message message = replies.nextMessage(wait);
        if (message == null) {
            return null;
        }
        if (message.isStatusMessage()) {
            if (message.getStatus().getCode() == 503) {
                throw new NoResponders(subject);
            }
            return null;
        }
        Map<String, String> stamp = new HashMap<>();
        boolean sealed = false;
        if (message.getHeaders() != null) {
            for (String h : new String[]{Security.HEADER_KEY, Security.HEADER_TS, Security.HEADER_NONCE,
                    Security.HEADER_SIG}) {
                String v = message.getHeaders().getFirst(h);
                if (v != null) {
                    stamp.put(h, v);
                }
            }
            sealed = message.getHeaders().getFirst(Security.HEADER_SEALED) != null;
        }
        return new Raw(message.getData(), Security.Stamp.of(stamp), sealed);
    }

    /**
     * Reads replies until the screen accepts one. Refused replies are skipped (they may come from someone else) and
     * only named if nothing acceptable arrives in time. The connection is checked between polls of at most
     * {@link #POLL}, so a lost connection ends the wait at once instead of at the timeout.
     *
     * @param lost        true once the connection the request was sent on dropped or reconnected
     * @param brokerError the last error the broker reported since the request was sent, or {@code null}
     */
    static Raw firstAccepted(String subject, Duration timeout, Screen screen, Replies replies, BooleanSupplier lost,
                             Supplier<String> brokerError) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String refused = null;
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            Raw raw = replies.next(Duration.ofNanos(Math.min(left, POLL.toNanos())));
            if (raw != null) {
                Optional<String> refusal = screen.refusal(raw);
                if (refusal.isEmpty()) {
                    return raw;
                }
                refused = refusal.get();
                LOG.log(System.Logger.Level.DEBUG, "ignored a reply on {0}: {1}", subject, refused);
            } else if (lost.getAsBoolean()) {
                throw new CliException(LOST);
            }
        }
        if (refused != null) {
            throw new CliException(refused + ", and no answer from the trusted kernel arrived within "
                    + timeout.toSeconds() + " s; refusing it");
        }
        String error = brokerError.get();
        throw new CliException(error != null ? "the broker refused the request on " + subject + " (" + error + ")"
                : timedOut(subject, timeout));
    }

    /**
     * Why no reply came in time. For a request that may change something the kernel can still be carrying it out,
     * so the person is told to look before asking again.
     */
    static String timedOut(String subject, Duration timeout) {
        String waited = "the kernel did not answer within " + timeout.toSeconds() + " s";
        return READS.contains(subject) ? waited + "; it may be busy, try again in a moment"
                : waited + "; it may still carry out the request, so look (for example: fabric \"list my goals\") "
                + "before asking again";
    }

    /** The broker found nobody listening on the subject. */
    static String notRunning(String subject) {
        return "the kernel is not running (nobody answers on " + subject + "). Start it with: docker compose up -d";
    }

    /**
     * Requests through the gateway's one endpoint: the signed request travels as a JSON envelope with the reply key
     * ({@code replySeal}) and, for a body sealed for the kernel, {@code sealed} naming the scheme; the kernel's reply
     * comes back as the kernel made it: its bytes (sealed when {@code sealed} names the scheme) and its signature
     * fields. A refusal by the gateway itself is shown in its own words.
     */
    private static Transport httpsTransport(HttpClient http, URI gateway) {
        return (subject, body, sealed, stamp, replySeal, grant, timeout, screen) -> {
            HttpRequest request = HttpRequest.newBuilder(gateway.resolve("/intent")).timeout(timeout.plusSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(envelope(subject, body, sealed, stamp,
                            replySeal, grant)))).build();
            try {
                HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
                return gatewayReply(response.statusCode(), response.body());
            } catch (SSLHandshakeException e) {
                throw new CliException("refusing the gateway at " + gateway + ": " + rootMessage(e));
            } catch (IOException e) {
                throw new CliException("cannot reach the gateway at " + gateway + ": " + rootMessage(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CliException("interrupted");
            } catch (RuntimeException e) {
                if (e instanceof CliException) {
                    throw e;
                }
                throw new CliException("the gateway sent an answer that cannot be read: " + e.getMessage());
            }
        };
    }

    /** The JSON envelope one request travels in through the gateway. */
    static Map<String, Object> envelope(String subject, byte[] body, boolean sealed, Security.Stamp stamp,
                                        String replySeal, Optional<String> grant) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("subject", subject);
        envelope.put("payload", Base64.getEncoder().encodeToString(body));
        envelope.put("key", stamp.key());
        envelope.put("ts", stamp.ts());
        envelope.put("nonce", stamp.nonce());
        envelope.put("sig", stamp.signature());
        envelope.put("replySeal", replySeal);
        if (sealed) {
            envelope.put("sealed", Security.SEAL_SCHEME);
        }
        grant.ifPresent(g -> envelope.put("grant", g));
        return envelope;
    }

    /**
     * What the gateway answered: the kernel's reply as it came, or the gateway's own refusal as an error. A body
     * that is not JSON (a proxy's error page, for example) is named by its HTTP status.
     */
    static Raw gatewayReply(int status, byte[] body) {
        JsonNode node;
        try {
            node = Json.tree(body);
        } catch (RuntimeException e) {
            throw new CliException("the gateway sent an answer that cannot be read (HTTP " + status + ")");
        }
        if (node == null || !node.has("data")) {
            String error = node == null ? "" : node.path("error").asString("");
            if (status == 429) {
                throw new CliException("the gateway says: " + (error.isBlank() ? "too many requests; wait a minute"
                        : error));
            }
            throw new CliException("the gateway refused the request: " + (error.isBlank() ? "HTTP " + status : error));
        }
        byte[] data = Base64.getDecoder().decode(node.path("data").asString());
        Optional<Security.Stamp> replyStamp = node.has("sig") ? Optional.of(new Security.Stamp(
                node.path("key").asString(), node.path("ts").asLong(), node.path("nonce").asString(),
                node.path("sig").asString())) : Optional.empty();
        return new Raw(data, replyStamp, node.path("sealed").isString());
    }

    /** The message of the innermost cause. */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? e.toString() : t.getMessage();
    }

    /** Keeps the client library quiet on the terminal and remembers the last error the broker sent. */
    static final class BrokerErrors implements ErrorListener {
        private final AtomicReference<String> last = new AtomicReference<>();

        @Override
        public void errorOccurred(Connection conn, String error) {
            last.set(error);
        }

        String last() {
            return last.get();
        }

        void clear() {
            last.set(null);
        }
    }

    /** A failure shown to the person as one plain line after "error: ". */
    static class CliException extends RuntimeException {
        CliException(String message) {
            super(message);
        }
    }

    /** The broker found nobody listening on the subject: the kernel is not running, or does not serve it. */
    static final class NoResponders extends CliException {
        NoResponders(String subject) {
            super(notRunning(subject));
        }
    }

    /**
     * This device's identity and what it knows: its key (created on first use), the kernel's key (pinned when it
     * paired, or when the first answer matched the key id it was told to expect), a grant it holds (a guest or a
     * delegate), and the gateways it trusts.
     */
    static final class Device {

        static final String KERNEL = "kernel.pub";
        static final String EXPECTED_KERNEL = "kernel.expected";

        private final Path home;
        private final Security.Identity identity;

        Device(Path home) {
            this.home = home;
            this.identity = Security.loadOrCreate(home.resolve("device.key"));
        }

        /** This machine's device: FABRIC_HOME, else {@code ~/.capability-fabric}. */
        static Device local() {
            String override = System.getenv("FABRIC_HOME");
            return new Device(override != null && !override.isBlank() ? Path.of(override)
                    : Path.of(System.getProperty("user.home"), ".capability-fabric"));
        }

        Security.Identity identity() {
            return identity;
        }

        /** The kernel key this device trusts, if it trusts one. */
        Optional<String> kernelKey() {
            return read(KERNEL).filter(s -> !s.isBlank());
        }

        /**
         * Trusts {@code key} as the kernel's from now on. An existing pin is never replaced: pinning another key is
         * refused with what to do if the system was really set up again.
         */
        void pinKernel(String key) {
            if (!Security.isCanonicalKey(key)) {
                throw new CliException("the kernel's key is not a valid key; nothing was pinned");
            }
            Optional<String> pinned = kernelKey();
            if (pinned.isPresent()) {
                if (pinned.get().equals(key)) {
                    return;
                }
                throw new CliException("this device already trusts the kernel key " + fingerprint(pinned.get())
                        + ", not " + Security.fingerprint(key) + ". If the system was set up again, delete "
                        + home.resolve(KERNEL) + " and pair again.");
            }
            write(KERNEL, key);
        }

        /** Pins the kernel key setup wrote into the secrets folder on this machine, if there is one. */
        void adoptKernelKey(Optional<Secrets> secrets) {
            Optional<Path> file = secrets.flatMap(Secrets::kernelKey);
            if (file.isEmpty()) {
                return;
            }
            String key;
            try {
                key = Files.readString(file.get(), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new CliException("cannot read " + file.get() + ": " + e.getMessage());
            }
            if (!Security.isCanonicalKey(key)) {
                throw new CliException(file.get() + " does not hold a kernel key; run setup again");
            }
            Optional<String> pinned = kernelKey();
            if (pinned.isPresent() && !pinned.get().equals(key)) {
                throw new CliException("this device trusts the kernel key " + fingerprint(pinned.get()) + " but "
                        + file.get() + " names " + Security.fingerprint(key) + ". If the system was set up again, "
                        + "delete " + home.resolve(KERNEL) + " and pair again.");
            }
            pinKernel(key);
        }

        /** The kernel key id a kept grant came with; the first reply signed by that key pins it. */
        Optional<String> expectedKernel() {
            return read(EXPECTED_KERNEL).filter(s -> !s.isBlank());
        }

        /** Remembers the kernel key id a kept grant came with, until the first reply signed by that kernel. */
        void expectKernel(String keyId) {
            write(EXPECTED_KERNEL, KernelId.normalize(keyId));
        }

        void forgetExpectedKernel() {
            try {
                Files.deleteIfExists(home.resolve(EXPECTED_KERNEL));
            } catch (IOException e) {
                throw new CliException("cannot remove " + home.resolve(EXPECTED_KERNEL) + ": " + e.getMessage());
            }
        }

        /** The grant this device holds (as a guest or a delegate), if any. */
        Optional<Grant> grant() {
            return read("grant").flatMap(Grant::decode);
        }

        void keepGrant(String encoded) {
            write("grant", encoded);
        }

        /** Drops the grant this device kept, if any. */
        void forgetGrant() {
            try {
                Files.deleteIfExists(home.resolve("grant"));
            } catch (IOException e) {
                throw new CliException("cannot remove " + home.resolve("grant") + ": " + e.getMessage());
            }
        }

        Path home() {
            return home;
        }

        /** A file of this device's folder, stripped; empty when it is missing or cannot be read. */
        Optional<String> read(String name) {
            try {
                Path p = home.resolve(name);
                return Files.exists(p) ? Optional.of(Files.readString(p, StandardCharsets.UTF_8).strip()) : Optional.empty();
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        void write(String name, String value) {
            try {
                Files.createDirectories(home);
                Files.writeString(home.resolve(name), value + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new CliException("cannot write " + home.resolve(name) + ": " + e.getMessage());
            }
        }
    }
}
