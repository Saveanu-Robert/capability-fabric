package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.Subjects;
import io.nats.client.AuthHandler;
import io.nats.client.AuthenticationException;
import io.nats.client.Connection;
import io.nats.client.ErrorListener;
import io.nats.client.NKey;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Opens broker connections that survive broker restarts. */
public final class NatsSupport {

    private static final Logger log = LoggerFactory.getLogger(NatsSupport.class);

    private NatsSupport() {
    }

    /**
     * Reports the broker's errors through the application's log. A permissions violation (the broker refused a
     * publish or subscription this process's role may not make) is logged at ERROR: it is a configuration
     * problem that retrying does not fix. Other errors and exceptions are logged at WARN.
     */
    static final ErrorListener ERRORS = new ErrorListener() {
        @Override
        public void errorOccurred(Connection conn, String error) {
            if (isPermissionViolation(error)) {
                log.error("the broker refused this process's request: {} (the role's NATS permissions do not allow it)",
                        error);
            } else {
                log.warn("broker error: {}", error);
            }
        }

        @Override
        public void exceptionOccurred(Connection conn, Exception exp) {
            log.warn("broker connection problem: {}", exp.getMessage());
        }
    };

    static boolean isPermissionViolation(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains("permissions violation");
    }

    /** The reply inboxes of the broker roles, each with the dot that starts the token after the prefix. */
    static final List<String> REPLY_PREFIXES = List.of(Subjects.INBOX_AGENT + ".", Subjects.INBOX_PERSON + ".",
            Subjects.INBOX_GATEWAY + ".", Subjects.INBOX_KERNEL + ".");

    /**
     * Whether a request's reply subject is one a reply may be published to: a subject under the reply inbox of one
     * of the broker roles. Any other subject (a stream, a key-value bucket, a request subject) would let whoever
     * sent the request make this process write there, with this process's broker permissions.
     */
    public static boolean isReplySubject(String subject) {
        return subject != null && REPLY_PREFIXES.stream()
                .anyMatch(p -> subject.length() > p.length() && subject.startsWith(p));
    }

    /**
     * Whether a reply subject is under one role's reply inbox ({@code inboxPrefix}, such as
     * {@link Subjects#INBOX_KERNEL}): the prefix, a dot and at least one more character.
     */
    public static boolean isReplySubjectOf(String subject, String inboxPrefix) {
        String prefix = inboxPrefix + ".";
        return subject != null && subject.length() > prefix.length() && subject.startsWith(prefix);
    }

    /**
     * The broker's permissions violation behind a failure, if that is what caused it: from the exception or its
     * causes, or else from the last error the broker reported on {@code connection}.
     */
    public static Optional<String> permissionViolation(Connection connection, Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (isPermissionViolation(t.getMessage())) {
                return Optional.of(t.getMessage());
            }
        }
        String last = connection == null ? null : connection.getLastError();
        return isPermissionViolation(last) ? Optional.of(last) : Optional.empty();
    }

    /** NKey authentication: the broker sends a nonce, the seed signs it; no password crosses the network. */
    public static AuthHandler nkey(String seedFile) {
        try {
            char[] seed = Files.readString(Path.of(seedFile)).strip().toCharArray();
            NKey key = NKey.fromSeed(seed);
            return new AuthHandler() {
                @Override
                public byte[] sign(byte[] nonce) {
                    try {
                        return key.sign(nonce);
                    } catch (Exception e) {
                        throw new IllegalStateException("cannot sign the broker's challenge", e);
                    }
                }

                @Override
                public char[] getID() {
                    try {
                        return key.getPublicKey();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public char[] getJWT() {
                    return null;
                }
            };
        } catch (Exception e) {
            throw new IllegalStateException("cannot read the NKey seed " + seedFile, e);
        }
    }

    /** TLS that trusts exactly the certificate authority in {@code caFile} (PEM), such as the one setup created. */
    public static SSLContext trusting(String caFile) {
        try (var in = Files.newInputStream(Path.of(caFile))) {
            var store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                store.setCertificateEntry("ca" + i++, cert);
            }
            var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(store);
            var context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("cannot load the certificate authority " + caFile, e);
        }
    }

    /** How long to wait between attempts while the broker cannot be reached at startup. */
    static final Duration STARTUP_RETRY = Duration.ofSeconds(1);

    /** Opens one connection attempt with these options; {@link Nats#connect(Options)} outside tests. */
    @FunctionalInterface
    interface Connector {
        Connection connect(Options options) throws IOException, InterruptedException;
    }

    /**
     * The process's broker connection, opened at startup. While the broker cannot be reached (not up yet, or
     * restarting) it tries again every {@link #STARTUP_RETRY}; a failure that trying again cannot fix ends the
     * start at once with what to set ({@link #startupProblem}): a TLS certificate this process does not trust, a
     * broker that wants TLS (or does not offer it), a login the broker refuses. Stopping the process (SIGTERM,
     * Ctrl-C) while it waits ends the start as well. Once connected, the connection reconnects forever
     * ({@link #options}).
     *
     * @throws IllegalStateException when the start ends without a connection
     */
    public static Connection connect(FabricProperties.Nats nats, String connectionName) {
        try (StartupStop stop = StartupStop.forCurrentThread()) {
            return firstConnection(() -> options(nats, connectionName), nats, Nats::connect, stop::requested,
                    STARTUP_RETRY);
        }
    }

    /**
     * Tries {@code connector} until it connects, a failure is one {@link #startupProblem} names, or
     * {@code stopping} says the process stops (checked before each attempt and after each wait).
     */
    static Connection firstConnection(Supplier<Options> options, FabricProperties.Nats nats,
                                      Connector connector, BooleanSupplier stopping, Duration wait) {
        for (int attempt = 1; ; attempt++) {
            if (stopping.getAsBoolean()) {
                throw new IllegalStateException("stopped while connecting to NATS at " + nats.url());
            }
            try {
                return connector.connect(options.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("stopped while connecting to NATS at " + nats.url(), e);
            } catch (IOException e) {
                Optional<String> problem = startupProblem(e, nats);
                if (problem.isPresent()) {
                    log.error("{}", problem.get());
                    throw new IllegalStateException(problem.get(), e);
                }
                if (attempt == 1 || attempt % 30 == 0) {
                    log.warn("NATS at {} cannot be reached yet ({}); trying again every {} ms", nats.url(),
                            rootMessage(e), wait.toMillis());
                }
            }
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("stopped while connecting to NATS at " + nats.url(), e);
            }
        }
    }

    /**
     * Why the first connection failed when trying again cannot help, with the settings that fix it; empty for a
     * broker that cannot be reached yet. Recognised, in the failure or its causes: a login the broker refuses (the
     * NKey seed), a TLS certificate this process does not trust or that does not fit the address (the certificate
     * authority), and TLS wanted on one side only (the URL's scheme).
     */
    static Optional<String> startupProblem(Throwable failure, FabricProperties.Nats nats) {
        String head = null;
        for (Throwable t = failure; t != null && head == null; t = t.getCause()) {
            String text = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (t instanceof AuthenticationException || text.contains("authorization violation")) {
                head = "the broker at " + nats.url() + " refused this process's login (" + t.getMessage()
                        + "): FABRIC_NATS_SEEDFILE must name the NKey seed of its role" + now(nats.seedFile());
            } else if (t instanceof CertificateException || t instanceof CertPathBuilderException
                    || t instanceof CertPathValidatorException || text.contains("pkix")
                    || text.contains("certificate")) {
                head = "the broker's TLS certificate at " + nats.url() + " is not trusted (" + t.getMessage()
                        + "): FABRIC_NATS_CAFILE must name the certificate authority setup created"
                        + now(nats.caFile());
            } else if (text.contains("ssl required by server")) {
                head = "the broker at " + nats.url() + " takes only TLS connections: NATS_URL must be a tls:// "
                        + "address and FABRIC_NATS_CAFILE name the certificate authority setup created"
                        + now(nats.caFile());
            } else if (text.contains("ssl connection wanted by client")) {
                head = "the broker at " + nats.url() + " does not offer TLS: use a nats:// address in NATS_URL, or "
                        + "the address of the broker setup secured";
            }
        }
        return Optional.ofNullable(head).map(h -> h + ". " + settingsHint(nats));
    }

    /** " (now /path)" for a configured file, " (none is set)" otherwise. */
    private static String now(String file) {
        return file == null || file.isBlank() ? " (none is set)" : " (now " + file + ")";
    }

    /**
     * The settings a process started outside the compose stack needs, with the files setup created in the project's
     * {@code secrets} folder: an agent's own seed and the kernel's key, or the kernel's seed for the kernel.
     */
    static String settingsHint(FabricProperties.Nats nats) {
        boolean agent = Subjects.INBOX_AGENT.equals(nats.inboxPrefix());
        return "Outside the compose stack set NATS_URL=tls://localhost:4222, FABRIC_NATS_CAFILE=secrets/public/ca.crt, "
                + (agent ? "FABRIC_NATS_SEEDFILE=secrets/agent/agent.nk and "
                + "FABRIC_SECURITY_KERNELKEYFILE=secrets/public/kernel.pub"
                : "FABRIC_NATS_SEEDFILE=secrets/kernel/kernel.nk");
    }

    /** The message of the innermost cause, which names what went wrong (refused, timed out, unknown host). */
    static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    /**
     * While the first connection is being opened: a JVM shutdown (SIGTERM, Ctrl-C) marks the start as stopping and
     * interrupts the thread that connects, so the start ends instead of holding the application context, which a
     * shutdown waits for. Closing it removes the hook.
     */
    static final class StartupStop implements AutoCloseable {

        private final AtomicBoolean requested = new AtomicBoolean();
        private final Thread hook;

        private StartupStop(Thread connecting) {
            hook = new Thread(() -> {
                requested.set(true);
                connecting.interrupt();
            }, "nats-startup-stop");
            try {
                Runtime.getRuntime().addShutdownHook(hook);
            } catch (IllegalStateException alreadyStopping) {
                requested.set(true);
            }
        }

        static StartupStop forCurrentThread() {
            return new StartupStop(Thread.currentThread());
        }

        boolean requested() {
            return requested.get();
        }

        /** Runs what the shutdown hook runs, without a shutdown. */
        void stop() {
            hook.run();
        }

        @Override
        public void close() {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // the hook runs or has run as part of the shutdown
            }
        }
    }

    /**
     * The connection options: reconnect forever, replies under the role's own inbox prefix (also used for
     * JetStream and key-value replies), timeouts reported as exceptions rather than as cancelled futures, and the
     * broker's errors reported through {@link #ERRORS}.
     */
    static Options options(FabricProperties.Nats nats, String connectionName) {
        Options.Builder builder = Options.builder()
                .server(nats.url())
                .connectionName(connectionName)
                .inboxPrefix(nats.inboxPrefix())
                .useTimeoutException()
                .errorListener(ERRORS)
                .maxReconnects(-1)
                // Reconnecting continues after login refusals too (by default the client stops after two).
                .ignoreAuthErrorAbort()
                .reconnectWait(Duration.ofSeconds(1))
                .connectionTimeout(Duration.ofSeconds(5))
                .pingInterval(Duration.ofSeconds(10));
        if (nats.user() != null && !nats.user().isBlank()) {
            builder.userInfo(nats.user(), nats.password() == null ? "" : nats.password());
        }
        if (nats.seedFile() != null && !nats.seedFile().isBlank()) {
            builder.authHandler(nkey(nats.seedFile()));
        }
        if (nats.caFile() != null && !nats.caFile().isBlank()) {
            builder.sslContext(trusting(nats.caFile()));
        }
        return builder.build();
    }
}
