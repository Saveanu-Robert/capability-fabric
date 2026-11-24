package dev.capabilityfabric.gateway;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.Subjects;
import io.nats.client.Connection;
import io.nats.client.Message;
import io.nats.client.Nats;
import io.nats.client.Options;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The only door to the system from the internet, and it has no resource endpoints: one HTTPS endpoint,
 * {@code POST /intent}, carries a signed sentence, a signed yes or no, a signed read of the inbox or of goals, the
 * signed emergency stop or resume, or a signed pairing or joining request (and the request for the kernel's keys that
 * comes before it) ({@link #SUBJECTS}), and returns the kernel's signed reply. The gateway cannot act for anyone: the
 * signature is checked again by the kernel, end to end, and the device checks that the kernel signed the reply for its
 * own request. It drops what is plainly bad before it reaches the system (unknown subjects, oversized, unsigned or
 * replayed bodies, nonces not in the form devices write, stale or invalid signatures, sentences longer than the kernel
 * takes), limits how fast any key and any address may knock, how long a request may take to arrive, how many requests
 * it handles at once, and how many connections one address may have that are still arriving (counted before the TLS
 * handshake). Every connection carries one request, so each one is counted from its start. Pairing and joining have a
 * tighter limit per address, because a fresh key costs nothing. With the kernel's key it passes on only the reply the
 * kernel signed for the request.
 *
 * <p>It forwards every request on the gateway subject of what the device signed ({@link Subjects#viaGateway}, for
 * example {@code gw.intent.submit}), with the device's headers and body as they came. The broker lets only the
 * gateway publish there and keeps it off the plain subjects, so the kernel knows from the subject alone that a
 * request came from the internet; signatures and reply scopes keep using the plain subject. A device's reply key
 * ({@code replySeal}, covered by the device's signature) is passed on as {@link Security#HEADER_REPLY_SEAL}; the
 * kernel then seals its reply for that key and the gateway returns the sealed bytes without being able to read them.
 *
 * <p>Pairing and joining codes travel sealed the other way too: a device asks the kernel for its keys
 * ({@code security.hello}), checks who signed them, and seals its code for the kernel's sealing key, bound to its own
 * signing key. Such an envelope says {@code "sealed": <scheme>}; the gateway passes the sealed payload on untouched,
 * marked with {@link Security#HEADER_SEALED}, so only the kernel can open it, and only for the device that signed it.
 */
public final class Gateway {

    /** The largest envelope accepted, in bytes. */
    static final int MAX_BODY = 64 * 1024;
    /**
     * Subjects people may use from outside. {@code query.goals} lets an owner's device look up a goal by its title;
     * the kernel answers it for owners only. {@code security.hello} gives the kernel's keys to any signed device, so
     * it can seal a pairing or joining code for the kernel.
     */
    static final Set<String> SUBJECTS = Subjects.GATEWAY_SUBJECTS;
    /** The longest sentence carried, in characters; the kernel refuses longer ones too. */
    static final int MAX_SENTENCE = 2000;
    /** Requests one signing key may send per minute. */
    static final int PER_KEY_PER_MINUTE = 30;
    /** Requests one client address may send per minute, whatever keys it signs with. */
    static final int PER_ADDRESS_PER_MINUTE = 120;
    /** Subjects that claim access with a code; each try is a guess when the code is wrong. */
    static final Set<String> PAIRING = Set.of(Subjects.SECURITY_PAIR, Subjects.SECURITY_JOIN);
    /** Pairing and joining requests one client address may send per {@link #PAIRING_WINDOW}. */
    static final int PAIRING_PER_ADDRESS = 10;
    static final Duration PAIRING_WINDOW = Duration.ofMinutes(15);
    /** Requests forwarded to the kernel at once; beyond that the gateway answers 503 at once. */
    static final int MAX_IN_FLIGHT = 64;
    /** Exchanges handled at once, each on its own virtual thread; beyond that new connections are closed. */
    static final int MAX_EXCHANGES = 128;
    /**
     * Connections one client address may have whose request is still arriving: from before the TLS handshake until
     * the request body has been read. A device sends one request at a time and its request arrives in moments, so
     * more than a few at once from one address are connections held open on purpose; a new one beyond the limit is
     * closed before its handshake. An IPv6 address is counted by its network ({@link #addressKey}).
     */
    static final int ARRIVING_PER_ADDRESS = 8;
    /** Open connections the HTTP server keeps at once. */
    static final int MAX_CONNECTIONS = 256;
    /** How long the kernel may take with a sentence; the CLI waits as long (its TURN_TIMEOUT). */
    static final Duration SUBMIT_TIMEOUT = Duration.ofMinutes(7);
    /**
     * How long the kernel may take with a yes or no; its longest path there is growing a capability, which waits up
     * to 40 s for it to join. The CLI waits as long (its ANSWER_TIMEOUT).
     */
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(60);
    /** How long the kernel may take with anything else (inbox, goal lookups, pairing, the kill switch). */
    static final Duration OTHER_TIMEOUT = Duration.ofSeconds(30);
    /** How often a wait for the kernel's reply checks that the broker connection is still the one it sent on. */
    static final Duration POLL = Duration.ofMillis(250);
    /**
     * How long a client may take from its first byte to the end of its request: the TLS handshake, the headers and a
     * body of at most {@link #MAX_BODY} bytes. A connection that sends nothing at all is closed after as long.
     */
    static final Duration READ_DEADLINE = Duration.ofSeconds(10);
    /** How often the server looks for connections that sent nothing within {@link #READ_DEADLINE}. */
    static final Duration IDLE_CHECK = Duration.ofSeconds(1);
    /** How long one exchange may take from the end of the request to the end of the response. */
    static final Duration RESPONSE_DEADLINE = SUBMIT_TIMEOUT.plusMinutes(1);

    /** The kernel, as the gateway reaches it: one request, one reply with its signature headers. */
    interface Upstream {
        /**
         * @param subject the gateway subject the request is published on ({@code gw.<subject>}); the kernel signs
         *                its reply for the plain subject ({@link Subjects#plain})
         * @throws TimeoutException when the kernel does not answer in time
         * @throws Lost             when the broker connection dropped while waiting
         * @throws IOException      when nobody answers or the broker cannot be reached
         */
        Answer ask(String subject, Map<String, String> headers, byte[] payload, Duration timeout)
                throws TimeoutException, IOException;
    }

    /**
     * A reply's bytes and its headers: the signature ({@code Fabric-Key}, {@code Fabric-Ts}, {@code Fabric-Nonce},
     * {@code Fabric-Sig}) and {@code Fabric-Sealed} when the bytes are sealed for the device.
     */
    record Answer(byte[] data, Map<String, String> headers) {
    }

    /** The replies arriving on one request's own inbox. */
    interface Replies {
        /**
         * The next reply, or {@code null} when none arrived within {@code wait}.
         *
         * @throws IOException when the broker reports that nobody listens on the subject
         */
        Answer next(Duration wait) throws IOException, InterruptedException;
    }

    /** The broker connection dropped after the request was sent: it may or may not have been handled. */
    static final class Lost extends IOException {
        Lost() {
            super("the broker connection was lost while waiting for the kernel");
        }
    }

    /**
     * The broker refused the gateway one of the request's subjects: the publish (nobody got the request) or the
     * reply subscription (the request went out, but its reply cannot arrive).
     */
    static final class Refused extends IOException {
        private final Broker.Refusal refusal;

        Refused(Broker.Refusal refusal) {
            super(refusedText(refusal));
            this.refusal = refusal;
        }

        Broker.Refusal refusal() {
            return refusal;
        }
    }

    /** What ends a wait for the kernel's reply before it times out. */
    interface Broken {
        /**
         * Why the wait cannot succeed any more ({@link Lost} or {@link Refused}), or {@code null} while it still can.
         */
        IOException why();
    }

    private static final System.Logger LOG = System.getLogger(Gateway.class.getName());

    /** An HTTP status and the JSON body sent with it. */
    record Response(int status, Map<String, Object> body) {
        static Response error(int status, String error) {
            return new Response(status, Map.of("error", error));
        }
    }

    private final Upstream upstream;
    private final Clock clock;
    private final Limiter perKey;
    private final Limiter perAddress;
    private final Limiter pairing;
    private final Security.ReplayGuard replays = new Security.ReplayGuard();
    private final Semaphore inFlight;

    Gateway(Upstream upstream, Clock clock, int maxInFlight) {
        this.upstream = upstream;
        this.clock = clock;
        this.perKey = new Limiter(PER_KEY_PER_MINUTE, clock);
        this.perAddress = new Limiter(PER_ADDRESS_PER_MINUTE, clock);
        this.pairing = new Limiter(PAIRING_PER_ADDRESS, PAIRING_WINDOW, clock);
        this.inFlight = new Semaphore(maxInFlight);
    }

    /**
     * Starts the HTTPS server on GATEWAY_PORT (default 8443) with the keystore GATEWAY_KEYSTORE, connects to the
     * broker as the gateway role ({@link #connect}) and reads the kernel's key ({@link #kernelKey}). Prints the
     * certificate authority's fingerprint, which devices compare on first contact.
     */
    public static void main(String[] args) throws Exception {
        limitServer();
        int port = Integer.parseInt(env("GATEWAY_PORT", "8443"));
        Path keystore = Path.of(env("GATEWAY_KEYSTORE", "/security/gateway/gateway.p12"));
        char[] password = keystorePassword(keystore).toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            store.load(in, password);
        }
        var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, password);
        var tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);

        Optional<String> kernelKey = kernelKey(Path.of(env("FABRIC_KERNEL_KEYFILE", "/security/public/kernel.pub")));
        if (kernelKey.isEmpty()) {
            System.out.println("gateway: no kernel key found (FABRIC_KERNEL_KEYFILE); replies are passed on unchecked "
                    + "and only devices check them");
        }
        Broker.Refusals refusals = new Broker.Refusals();
        Connection connection = connect(refusals);
        Gateway gateway = new Gateway(nats(connection, kernelKey, refusals), Clock.systemUTC(), MAX_IN_FLIGHT);
        HttpsServer server = server(new InetSocketAddress(port), tls, gateway, connection::getStatus, MAX_EXCHANGES,
                new Arrivals(ARRIVING_PER_ADDRESS));
        server.start();
        System.out.println("gateway listening on https://0.0.0.0:" + port + "/intent; certificate authority "
                + "fingerprint " + authorityFingerprint(store) + " (devices check this one on first contact)");
    }

    /**
     * Deadlines and limits of the JDK's HTTP server, which reads them once when it is first used, so this runs
     * before the server is created. Values set on the command line are kept.
     */
    static void limitServer() {
        setIfAbsent("sun.net.httpserver.maxReqTime", Long.toString(READ_DEADLINE.toSeconds()));
        setIfAbsent("sun.net.httpserver.maxRspTime", Long.toString(RESPONSE_DEADLINE.toSeconds()));
        setIfAbsent("sun.net.httpserver.clockTick", Long.toString(IDLE_CHECK.toMillis()));
        setIfAbsent("jdk.httpserver.maxConnections", Integer.toString(MAX_CONNECTIONS));
    }

    private static void setIfAbsent(String property, String value) {
        if (System.getProperty(property) == null) {
            System.setProperty(property, value);
        }
    }

    /**
     * The HTTPS server, not yet started: {@code POST /intent} goes to {@code gateway}, {@code GET /health} answers from
     * the broker connection's {@code status}, at most {@code maxExchanges} exchanges run at once ({@link #bounded}),
     * and one client address may have only as many connections whose request is still arriving as {@code arrivals}
     * allows ({@link #admitting}).
     */
    static HttpsServer server(InetSocketAddress address, SSLContext tls, Gateway gateway,
                              Supplier<Connection.Status> status, int maxExchanges, Arrivals arrivals)
            throws IOException {
        HttpsServer server = HttpsServer.create(address, 128);
        server.setHttpsConfigurator(admitting(tls, arrivals));
        server.createContext("/intent", gateway::intent);
        // Healthy only while the broker connection is up, so a health check sees a gateway that cannot forward.
        server.createContext("/health", exchange -> {
            arrived();
            Connection.Status now = status.get();
            boolean up = now == Connection.Status.CONNECTED;
            respond(exchange, up ? 200 : 503, Map.of("ok", up, "broker", now.name().toLowerCase(Locale.ROOT)));
        });
        server.setExecutor(bounded(maxExchanges, arrivals));
        return server;
    }

    /**
     * Runs each exchange on a virtual thread, at most {@code max} at once; the server closes a refused connection.
     * Each exchange gets its own {@link Arrival} in {@code arrivals} ({@link #ARRIVAL}), which the TLS setup of a new
     * connection takes ({@link #admitting}) and which is given back once the request has arrived ({@link #arrived})
     * or, at the latest, when the exchange ends.
     */
    static Executor bounded(int max, Arrivals arrivals) {
        Semaphore permits = new Semaphore(max);
        return task -> {
            if (!permits.tryAcquire()) {
                throw new RejectedExecutionException("more than " + max + " exchanges at once");
            }
            try {
                Thread.ofVirtual().name("gateway-exchange").start(() -> {
                    Arrival arrival = new Arrival(arrivals);
                    try {
                        ScopedValue.where(ARRIVAL, arrival).run(task);
                    } finally {
                        arrival.done();
                        permits.release();
                    }
                });
            } catch (RuntimeException | Error e) {
                permits.release();
                throw e;
            }
        };
    }

    /**
     * The current exchange's place among the connections still arriving; bound by {@link #bounded} for the whole
     * exchange, so the TLS setup and the handlers, which run on the exchange's thread, find it.
     */
    static final ScopedValue<Arrival> ARRIVAL = ScopedValue.newInstance();

    /**
     * TLS for the server's connections with {@code tls}, and a count per client address: the server sets up TLS for a
     * new connection on its exchange's thread before the handshake, and that is where the connection takes its place
     * in {@code arrivals} ({@link Arrival#take}). An address that already has as many connections arriving as
     * {@code arrivals} allows gets this one closed at once, before any handshake work. Outside an exchange run by
     * {@link #bounded} nothing is counted.
     */
    static HttpsConfigurator admitting(SSLContext tls, Arrivals arrivals) {
        return new HttpsConfigurator(tls) {
            @Override
            public void configure(HttpsParameters params) {
                if (ARRIVAL.isBound()) {
                    InetSocketAddress client = params.getClientAddress();
                    String address = client.getAddress() == null ? client.getHostString()
                            : client.getAddress().getHostAddress();
                    ARRIVAL.get().take(addressKey(address));
                }
                super.configure(params);
            }
        };
    }

    /** The current exchange's request has arrived: it no longer counts against its address ({@link #ARRIVAL}). */
    static void arrived() {
        if (ARRIVAL.isBound()) {
            ARRIVAL.get().done();
        }
    }

    /**
     * How many connections each client address has whose request is still arriving, and the most it may have. An
     * address is forgotten when its count drops to zero.
     */
    static final class Arrivals {
        private final int limit;
        private final Map<String, Integer> counts = new ConcurrentHashMap<>();

        Arrivals(int limit) {
            this.limit = limit;
        }

        /** Counts one more for {@code address}; false, and nothing counted, when it already has the limit. */
        boolean enter(String address) {
            boolean[] entered = new boolean[1];
            counts.compute(address, (k, n) -> {
                int now = n == null ? 0 : n;
                if (now >= limit) {
                    return n;
                }
                entered[0] = true;
                return now + 1;
            });
            return entered[0];
        }

        /** Counts one fewer for {@code address}. */
        void leave(String address) {
            counts.computeIfPresent(address, (k, n) -> n <= 1 ? null : n - 1);
        }

        /** How many connections {@code address} has arriving now. */
        int arriving(String address) {
            return counts.getOrDefault(address, 0);
        }

        /** How many addresses have a connection arriving now. */
        int tracked() {
            return counts.size();
        }

        int limit() {
            return limit;
        }
    }

    /**
     * One exchange's place in {@link Arrivals}: taken when its connection's TLS is set up, given back once. Only the
     * exchange's own thread uses it.
     */
    static final class Arrival {
        private final Arrivals arrivals;
        private String address;

        Arrival(Arrivals arrivals) {
            this.arrivals = arrivals;
        }

        /**
         * Counts this exchange against {@code address}; a second call while it counts changes nothing.
         *
         * @throws IllegalStateException when the address already has as many connections arriving as allowed; the
         *                               server then closes the connection
         */
        void take(String address) {
            if (this.address != null) {
                return;
            }
            if (!arrivals.enter(address)) {
                LOG.log(System.Logger.Level.DEBUG, "closed a connection from {0}: {1} connections from it are still "
                        + "arriving", address, arrivals.limit());
                throw new IllegalStateException("more than " + arrivals.limit() + " connections from " + address
                        + " are still arriving");
            }
            this.address = address;
        }

        /** Stops counting this exchange; a second call changes nothing. */
        void done() {
            if (address != null) {
                arrivals.leave(address);
                address = null;
            }
        }

        /** True while this exchange counts against an address. */
        boolean counted() {
            return address != null;
        }
    }

    /**
     * The gateway role's broker connection: its NKey and the certificate authority come from the environment
     * (compose mounts them under /security), and its replies arrive under the gateway's own inbox prefix. The
     * broker's errors go to {@code refusals}, which remembers the subjects it refuses.
     */
    static Connection connect(Broker.Refusals refusals) throws Exception {
        String url = env("NATS_URL", "nats://localhost:4222");
        Options.Builder options = Options.builder().server(url).connectionName("fabric-gateway").maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(1)).inboxPrefix(Subjects.INBOX_GATEWAY).useTimeoutException()
                .errorListener(refusals)
                // Keep reconnecting after the broker refuses the login (for example while setup rewrites its
                // configuration); by default the client gives up for good after two refusals.
                .ignoreAuthErrorAbort();
        String seed = env("FABRIC_NATS_SEEDFILE", existing("/security/gateway/gateway.nk"));
        if (!seed.isBlank()) {
            options.authHandler(Broker.nkey(seed));
        }
        String ca = env("FABRIC_NATS_CAFILE", existing("/security/public/ca.crt"));
        if (!ca.isBlank()) {
            options.sslContext(Broker.trusting(ca));
        } else if (url.startsWith("tls://")) {
            throw new IllegalStateException("NATS_URL uses TLS but no certificate authority is configured: set "
                    + "FABRIC_NATS_CAFILE");
        }
        return Nats.connectReconnectOnConnect(options.build());
    }

    /** The kernel's public key text from {@code file} (one line, as setup writes it), or empty when there is none. */
    static Optional<String> kernelKey(Path file) {
        String key = readOr(file, "");
        if (key.isBlank()) {
            return Optional.empty();
        }
        if (!Security.isCanonicalKey(key)) {
            throw new IllegalStateException(file + " does not hold a kernel key; run setup again");
        }
        return Optional.of(key);
    }

    /** {@code file} when it is a regular file, else the empty string. */
    private static String existing(String file) {
        return Files.isRegularFile(Path.of(file)) ? file : "";
    }

    /** The keystore password: GATEWAY_KEYSTORE_PASSWORD, else {@code gateway.pass} in the keystore's folder. */
    static String keystorePassword(Path keystore) {
        String password = env("GATEWAY_KEYSTORE_PASSWORD", readOr(keystore.resolveSibling("gateway.pass"), ""));
        if (password.isBlank()) {
            throw new IllegalStateException("no password for " + keystore + ": put gateway.pass next to it or set "
                    + "GATEWAY_KEYSTORE_PASSWORD");
        }
        return password;
    }

    /**
     * Requests to the kernel over NATS on the gateway subject they are given, each on its own reply subscription. With
     * the kernel's key, only a reply the kernel signed for this request (scope
     * {@code reply:<plain subject>:<request nonce>}) ends the wait; anything else on the inbox is ignored. Without the
     * key the first reply is passed on and the device checks it. Nobody listening on the subject, a broker that cannot
     * be reached, a connection that drops while waiting, and a broker that refuses the gateway the subject or the reply
     * subscription ({@code refusals}) are told apart; the last two end the wait at once.
     */
    static Upstream nats(Connection connection, Optional<String> kernelKey, Broker.Refusals refusals) {
        return (subject, headers, payload, timeout) -> {
            if (connection.getStatus() != Connection.Status.CONNECTED) {
                throw new IOException("the broker is not connected");
            }
            Headers h = new Headers();
            headers.forEach(h::put);
            String nonce = headers.getOrDefault(Security.HEADER_NONCE, "");
            long reconnects = connection.getStatistics().getReconnects();
            long sentAt = refusals.now();
            Subscription replies;
            try {
                replies = connection.subscribe(connection.createInbox());
            } catch (IllegalStateException e) {
                throw new IOException("the broker connection is closed", e);
            }
            List<String> own = List.of(subject, replies.getSubject());
            try {
                connection.publish(subject, replies.getSubject(), h, payload);
                return firstGenuine(subject, timeout, genuine(kernelKey, subject, nonce),
                        wait -> next(replies, wait, subject), () -> {
                            if (connection.getStatus() != Connection.Status.CONNECTED
                                    || connection.getStatistics().getReconnects() != reconnects) {
                                return new Lost();
                            }
                            return refusals.since(sentAt, own).<IOException>map(Refused::new).orElse(null);
                        });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            } catch (IllegalStateException e) {
                throw new Lost();
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
     * Which replies end the wait for a request published on {@code gatewaySubject} with this nonce: with the
     * kernel's key, only one it signed for the request's plain subject ({@link Subjects#plain}); without it, any.
     */
    static Predicate<Answer> genuine(Optional<String> kernelKey, String gatewaySubject, String requestNonce) {
        String subject = Subjects.plain(gatewaySubject);
        return answer -> kernelKey.isEmpty() || signedFor(kernelKey.get(), subject, requestNonce, answer);
    }

    /**
     * True when {@code kernelKey} signed this reply for the request with this plain subject and nonce. A sealed reply
     * is signed as it travels, so this holds without opening it.
     */
    static boolean signedFor(String kernelKey, String subject, String requestNonce, Answer answer) {
        return Security.Stamp.of(answer.headers())
                .filter(stamp -> kernelKey.equals(stamp.key()))
                .map(stamp -> stamp.verifies(Security.replyScope(subject, requestNonce), answer.data()))
                .orElse(false);
    }

    /** The reply headers the gateway keeps: the kernel's signature and the sealing mark. */
    private static final String[] REPLY_HEADERS = {Security.HEADER_KEY, Security.HEADER_TS, Security.HEADER_NONCE,
            Security.HEADER_SIG, Security.HEADER_SEALED};

    /** One message from the request's subscription; the broker's "no responders" status means nobody listens. */
    private static Answer next(Subscription replies, Duration wait, String subject)
            throws IOException, InterruptedException {
        Message message;
        try {
            message = replies.nextMessage(wait);
        } catch (IllegalStateException e) {
            throw new Lost();
        }
        if (message == null) {
            return null;
        }
        if (message.isStatusMessage()) {
            if (message.getStatus().getCode() == 503) {
                throw new IOException("nobody answers on " + subject);
            }
            return null;
        }
        Map<String, String> out = new HashMap<>();
        if (message.getHeaders() != null) {
            for (String name : REPLY_HEADERS) {
                String v = message.getHeaders().getFirst(name);
                if (v != null) {
                    out.put(name, v);
                }
            }
        }
        return new Answer(message.getData(), out);
    }

    /**
     * Reads replies until one is genuine; others are ignored. {@code broken} is asked between polls of at most
     * {@link #POLL}, so a dropped connection or a refused subject ends the wait at once with what it says.
     */
    static Answer firstGenuine(String subject, Duration timeout, Predicate<Answer> genuine, Replies replies,
                               Broken broken) throws TimeoutException, IOException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new TimeoutException("no reply from the kernel on " + subject + " within " + timeout);
            }
            Answer answer = replies.next(Duration.ofNanos(Math.min(left, POLL.toNanos())));
            if (answer != null) {
                if (genuine.test(answer)) {
                    return answer;
                }
                LOG.log(System.Logger.Level.DEBUG, "ignored a reply on {0} that the kernel did not sign for this request",
                        subject);
            } else {
                IOException why = broken.why();
                if (why != null) {
                    throw why;
                }
            }
        }
    }

    /** The HTTP handler of {@code POST /intent}: a body that cannot be read is 400, an unexpected failure 500. */
    void intent(HttpExchange exchange) throws IOException {
        Response response;
        try {
            response = handle(exchange.getRequestMethod(), exchange.getRemoteAddress().getAddress().getHostAddress(),
                    exchange.getRequestBody());
        } catch (IOException e) {
            response = Response.error(400, "the request could not be read");
        } catch (RuntimeException e) {
            response = Response.error(500, "the gateway failed to handle the request");
        }
        respond(exchange, response.status(), response.body());
    }

    /** Everything {@code POST /intent} does, apart from the HTTP plumbing. */
    Response handle(String method, String address, InputStream requestBody) throws IOException {
        if (!"POST".equals(method)) {
            return Response.error(405, "POST a signed envelope");
        }
        String knocker = addressKey(address);
        if (!perAddress.allow(knocker)) {
            return Response.error(429, "too many requests from this address");
        }
        byte[] body = readLimited(requestBody);
        arrived();
        if (body == null) {
            return Response.error(413, "body larger than " + MAX_BODY + " bytes");
        }
        Check check = check(body, clock.millis());
        if (check.error() != null) {
            return Response.error(check.status(), check.error());
        }
        Optional<String> replay = replays.check(check.stamp());
        if (replay.isPresent()) {
            return Response.error(replay.get().contains("replay") ? 409 : 401, replay.get());
        }
        if (PAIRING.contains(check.subject()) && !pairing.allow(knocker)) {
            return Response.error(429, "too many pairing or joining attempts from this address (at most "
                    + PAIRING_PER_ADDRESS + " in " + PAIRING_WINDOW.toMinutes() + " minutes); try again later");
        }
        if (!perKey.allow(check.stamp().key())) {
            return Response.error(429, "too many requests from this key");
        }
        if (!inFlight.tryAcquire()) {
            return Response.error(503, "the gateway is busy; try again in a moment");
        }
        try {
            return forward(check);
        } finally {
            inFlight.release();
        }
    }

    /**
     * Passes a checked request to the kernel on its gateway subject ({@link Subjects#viaGateway}) with the device's
     * signature headers (and grant, reply key and sealing mark) and the payload as the device signed it, and returns
     * the kernel's reply as the kernel made it: its bytes, sealed or not ({@code sealed} names the scheme when they
     * are), and its signature fields. Only transport failures become HTTP errors: a slow kernel is 504; one that
     * cannot be reached, a connection lost while waiting, or a broker that refuses the gateway one of the request's
     * subjects (named in the error) is 502.
     */
    private Response forward(Check check) {
        Map<String, String> headers = new HashMap<>(check.stamp().headers());
        if (check.grant() != null) {
            headers.put(Security.HEADER_GRANT, check.grant());
        }
        if (check.replySeal() != null) {
            headers.put(Security.HEADER_REPLY_SEAL, check.replySeal());
        }
        if (check.sealed()) {
            headers.put(Security.HEADER_SEALED, Security.SEAL_SCHEME);
        }
        Answer reply;
        try {
            reply = upstream.ask(Subjects.viaGateway(check.subject()), headers, check.payload(),
                    timeout(check.subject()));
        } catch (TimeoutException e) {
            return Response.error(504, "the system did not answer in time");
        } catch (Lost e) {
            return Response.error(502, "the connection to the system was lost while waiting; the request may or may "
                    + "not have been handled");
        } catch (Refused e) {
            return Response.error(502, refusedText(e.refusal()));
        } catch (IOException | RuntimeException e) {
            return Response.error(502, "the system is not reachable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", Base64.getEncoder().encodeToString(reply.data() == null ? new byte[0] : reply.data()));
        Map<String, String> h = reply.headers();
        if (h.get(Security.HEADER_SIG) != null && h.get(Security.HEADER_TS) != null) {
            try {
                long ts = Long.parseLong(h.get(Security.HEADER_TS));
                out.put("key", h.get(Security.HEADER_KEY));
                out.put("ts", ts);
                out.put("nonce", h.get(Security.HEADER_NONCE));
                out.put("sig", h.get(Security.HEADER_SIG));
            } catch (NumberFormatException e) {
                return Response.error(502, "the system sent a malformed signature");
            }
        }
        if (h.get(Security.HEADER_SEALED) != null) {
            out.put("sealed", h.get(Security.HEADER_SEALED));
        }
        return new Response(200, out);
    }

    /**
     * What a device is told when the broker refused the gateway one of its request's subjects: the subject, and
     * whether the request can have reached the kernel.
     */
    static String refusedText(Broker.Refusal refusal) {
        return switch (refusal.how()) {
            case PUBLISH -> "the broker does not let the gateway publish on " + refusal.subject()
                    + "; the request was not handled";
            case REPLY_TO -> "the broker does not let the gateway ask for replies on " + refusal.subject()
                    + "; the request was not handled";
            case SUBSCRIPTION -> "the broker does not let the gateway receive replies on " + refusal.subject()
                    + "; the request may or may not have been handled";
        };
    }

    /** How long the kernel may take on a subject: a sentence, a yes or no, or anything else. */
    static Duration timeout(String subject) {
        return switch (subject) {
            case Subjects.INTENT_SUBMIT -> SUBMIT_TIMEOUT;
            case Subjects.INTENT_CONFIRM, Subjects.INTENT_INBOX_ANSWER -> ANSWER_TIMEOUT;
            default -> OTHER_TIMEOUT;
        };
    }

    /**
     * What the gateway checks before anything reaches the system; {@code replySeal} is null when none was sent, and
     * {@code sealed} says that the payload is sealed for the kernel.
     */
    record Check(int status, String error, String subject, byte[] payload, Security.Stamp stamp, String grant,
                 String replySeal, boolean sealed) {
        static Check refuse(int status, String error) {
            return new Check(status, error, null, null, null, null, null, false);
        }
    }

    /** The length of an X25519 public key in the X.509 form {@link Security.SealKey#publicKeyText} encodes. */
    static final int REPLY_SEAL_BYTES = 44;

    /**
     * Checks an envelope before anything reaches the system: a subject people may use, a base64 payload, a timestamp
     * within the allowed clock skew of {@code now}, a nonce in the form devices write ({@link Security#isValidNonce},
     * so the replay guard keeps only short ones), a reply key that is one (when there is one), a sealing mark that
     * names {@link Security#SEAL_SCHEME} on a pairing or joining code (when there is one), and a signature over all
     * of it ({@link Security#requestScope} of the subject and the reply key; for a sealed code, over the sealed
     * bytes). A sentence longer than {@link #MAX_SENTENCE} is refused. Replays are refused afterwards by the replay
     * guard.
     */
    static Check check(byte[] body, long now) {
        JsonNode envelope;
        try {
            envelope = Json.tree(body);
        } catch (RuntimeException e) {
            return Check.refuse(400, "not JSON");
        }
        String subject = envelope.path("subject").asString("");
        if (!SUBJECTS.contains(subject)) {
            return Check.refuse(403, "the gateway only carries sentences, answers, inbox and goal reads, the emergency "
                    + "stop and resume, pairing and joining, and the request for the kernel's keys that comes before "
                    + "them");
        }
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(envelope.path("payload").asString(""));
        } catch (IllegalArgumentException e) {
            return Check.refuse(400, "payload is not base64");
        }
        JsonNode seal = envelope.path("replySeal");
        String replySeal = seal.isMissingNode() || seal.isNull() ? null : seal.isString() ? seal.asString() : "";
        if (replySeal != null && !replySealKey(replySeal)) {
            return Check.refuse(400, "replySeal is not a reply key");
        }
        JsonNode mark = envelope.path("sealed");
        boolean sealed = !mark.isMissingNode() && !mark.isNull();
        if (sealed && !(mark.isString() && Security.SEAL_SCHEME.equals(mark.asString()))) {
            return Check.refuse(400, "sealed must name " + Security.SEAL_SCHEME);
        }
        if (sealed && !PAIRING.contains(subject)) {
            return Check.refuse(400, "only pairing and joining codes travel sealed");
        }
        Security.Stamp stamp = new Security.Stamp(envelope.path("key").asString(""), envelope.path("ts").asLong(0),
                envelope.path("nonce").asString(""), envelope.path("sig").asString(""));
        if (Math.abs(now - stamp.ts()) > Security.CLOCK_SKEW.toMillis()) {
            return Check.refuse(401, "the request is too old or the device's clock is wrong");
        }
        if (!Security.isValidNonce(stamp.nonce())) {
            return Check.refuse(400, "the nonce must be " + Security.NONCE_MIN_LENGTH + " to "
                    + Security.NONCE_MAX_LENGTH + " base64url characters");
        }
        if (!stamp.verifies(Security.requestScope(subject, replySeal), payload)) {
            return Check.refuse(401, "invalid signature");
        }
        if (Subjects.INTENT_SUBMIT.equals(subject) && sentenceLength(payload) > MAX_SENTENCE) {
            return Check.refuse(400, "the sentence is longer than " + MAX_SENTENCE + " characters; say it in shorter "
                    + "parts");
        }
        String grant = envelope.path("grant").isString() ? envelope.path("grant").asString() : null;
        return new Check(200, null, subject, payload, stamp, grant, replySeal, sealed);
    }

    /**
     * The length in characters of the sentence ({@code text}) a sentence payload carries; 0 when it carries none or
     * is not JSON, which the kernel refuses itself.
     */
    static int sentenceLength(byte[] payload) {
        try {
            JsonNode sentence = Json.tree(payload);
            JsonNode text = sentence == null ? null : sentence.path("text");
            return text != null && text.isString() ? text.asString().length() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** True when {@code text} is unpadded base64url of {@link #REPLY_SEAL_BYTES} bytes, as a reply key is written. */
    static boolean replySealKey(String text) {
        if (!text.matches("[A-Za-z0-9_-]+")) {
            return false;
        }
        try {
            return Base64.getUrlDecoder().decode(text).length == REPLY_SEAL_BYTES;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The address a knock is counted against: an IPv4 address as it is, an IPv6 address by its first 64 bits (one
     * network, which hands its hosts as many addresses as they like). Anything else is used as it is.
     */
    static String addressKey(String address) {
        if (address == null || address.indexOf(':') < 0) {
            return address;
        }
        try {
            InetAddress parsed = InetAddress.ofLiteral(address);
            if (parsed instanceof Inet6Address) {
                byte[] bytes = parsed.getAddress();
                return HexFormat.of().formatHex(bytes, 0, 8) + "::/64";
            }
            return parsed.getHostAddress();
        } catch (IllegalArgumentException e) {
            return address;
        }
    }

    /** At most {@link #MAX_BODY} bytes of the body; {@code null} when it is larger. */
    private static byte[] readLimited(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_BODY + 1);
        return data.length > MAX_BODY ? null : data;
    }

    private static void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.bytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Strict-Transport-Security", "max-age=31536000");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        // One request per connection: the server closes it after the response, so every request is counted from
        // before its TLS handshake (see admitting) and no connection waits idle for another.
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * SHA-256 of the certificate authority at the end of the gateway's chain, the value devices pin, as
     * colon-separated upper-case hex.
     */
    static String authorityFingerprint(KeyStore store) throws Exception {
        for (String alias : Collections.list(store.aliases())) {
            if (store.isKeyEntry(alias)) {
                Certificate[] chain = store.getCertificateChain(alias);
                Certificate last = chain == null || chain.length == 0 ? store.getCertificate(alias) : chain[chain.length - 1];
                return HexFormat.ofDelimiter(":").withUpperCase()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(last.getEncoded()));
            }
        }
        return "?";
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** The stripped content of {@code file}, or {@code fallback} when it is missing or cannot be read. */
    private static String readOr(Path file, String fallback) {
        try {
            return Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).strip() : fallback;
        } catch (IOException e) {
            return fallback;
        }
    }

    /**
     * Sliding window per key or address (one minute unless given). Keys that stayed quiet for a whole window are
     * forgotten, at most once per window, so the map holds only recent knockers.
     */
    static final class Limiter {
        private final int limit;
        private final long windowMs;
        private final Clock clock;
        private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
        private volatile long lastSweep;

        Limiter(int perMinute, Clock clock) {
            this(perMinute, Duration.ofMinutes(1), clock);
        }

        Limiter(int limit, Duration window, Clock clock) {
            this.limit = limit;
            this.windowMs = window.toMillis();
            this.clock = clock;
            this.lastSweep = clock.millis();
        }

        /** True when {@code who} knocked fewer than the limit within the window; only allowed knocks count. */
        boolean allow(String who) {
            long now = clock.millis();
            sweep(now);
            boolean[] allowed = new boolean[1];
            hits.compute(who, (k, times) -> {
                Deque<Long> t = times == null ? new ArrayDeque<>() : times;
                prune(t, now);
                if (t.size() < limit) {
                    t.addLast(now);
                    allowed[0] = true;
                }
                return t;
            });
            return allowed[0];
        }

        /** How many keys or addresses are remembered. */
        int tracked() {
            return hits.size();
        }

        private void sweep(long now) {
            if (now - lastSweep < windowMs) {
                return;
            }
            lastSweep = now;
            for (String key : hits.keySet()) {
                hits.computeIfPresent(key, (k, t) -> {
                    prune(t, now);
                    return t.isEmpty() ? null : t;
                });
            }
        }

        private void prune(Deque<Long> times, long now) {
            while (!times.isEmpty() && now - times.peekFirst() >= windowMs) {
                times.removeFirst();
            }
        }
    }
}
