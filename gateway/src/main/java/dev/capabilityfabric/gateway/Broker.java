package dev.capabilityfabric.gateway;

import io.nats.client.AuthHandler;
import io.nats.client.Connection;
import io.nats.client.NKey;
import io.nats.client.impl.ErrorListenerLoggerImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** The broker floor: TLS that trusts setup's certificate authority, and NKey challenge-response login. */
final class Broker {

    private Broker() {
    }

    /**
     * NKey challenge-response login with the seed in {@code seedFile}: the broker sends a nonce and only its
     * signature leaves this process.
     */
    static AuthHandler nkey(String seedFile) {
        try {
            NKey key = NKey.fromSeed(Files.readString(Path.of(seedFile)).strip().toCharArray());
            return new AuthHandler() {
                @Override
                public byte[] sign(byte[] nonce) {
                    try {
                        return key.sign(nonce);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
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
            throw new IllegalStateException("cannot read the NKey seed " + seedFile + ": " + e.getMessage());
        }
    }

    /** TLS that trusts only the certificates in {@code caFile} (PEM), not the system's certificate authorities. */
    static SSLContext trusting(String caFile) {
        try (var in = Files.newInputStream(Path.of(caFile))) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
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
            throw new IllegalStateException("cannot load the certificate authority " + caFile + ": " + e.getMessage());
        }
    }

    /** What the broker refused the gateway on a subject. */
    enum Refused {
        /** Publishing on it: the request reached nobody. */
        PUBLISH,
        /** Publishing with it as the reply subject: the request reached nobody. */
        REPLY_TO,
        /** Subscribing to it: the request went out, but no reply can come back on it. */
        SUBSCRIPTION
    }

    /** A subject the broker refused the gateway, and how. */
    record Refusal(String subject, Refused how) {
    }

    /**
     * The broker's permission errors, remembered per subject with the time they arrived. The broker answers a
     * publish or subscription the gateway may not make with an asynchronous error ({@code Permissions Violation for
     * Publish to "<subject>"}, {@code ... Publish with Reply of "<subject>"} or {@code ... Subscription to
     * "<subject>"}) and otherwise drops it silently; a request waiting for its reply asks {@link #since} whether one
     * of its own subjects was refused after it was sent. Every error is also logged as the client library does by
     * default. At most {@link #MAX} subjects are remembered; the oldest go first.
     */
    static final class Refusals extends ErrorListenerLoggerImpl {

        static final int MAX = 256;
        private static final Pattern VIOLATION = Pattern.compile(
                "Permissions Violation for (Publish to|Publish with Reply of|Subscription to) \"([^\"]+)\"");
        private static final System.Logger LOG = System.getLogger(Refusals.class.getName());

        private final Map<String, Seen> seen = new ConcurrentHashMap<>();
        private final LongSupplier nanos;

        private record Seen(long at, Refused how) {
        }

        Refusals() {
            this(System::nanoTime);
        }

        Refusals(LongSupplier nanos) {
            this.nanos = nanos;
        }

        @Override
        public void errorOccurred(Connection conn, String error) {
            super.errorOccurred(conn, error);
            refused(error);
        }

        /** Remembers the subject a permission error names; other errors are ignored. */
        void refused(String error) {
            Matcher m = error == null ? null : VIOLATION.matcher(error);
            if (m == null || !m.find()) {
                return;
            }
            Refused how = switch (m.group(1)) {
                case "Publish to" -> Refused.PUBLISH;
                case "Publish with Reply of" -> Refused.REPLY_TO;
                default -> Refused.SUBSCRIPTION;
            };
            if (seen.size() >= MAX) {
                seen.entrySet().stream().min(Map.Entry.comparingByValue(Comparator.comparingLong(Seen::at)))
                        .ifPresent(oldest -> seen.remove(oldest.getKey()));
            }
            seen.put(m.group(2), new Seen(nanos.getAsLong(), how));
            LOG.log(System.Logger.Level.WARNING, "the broker refused the gateway {0} {1}; check the gateway role''s "
                    + "permissions in the broker configuration (run setup again)",
                    m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }

        /** The first of {@code subjects} the broker refused at or after {@code sentAt} ({@link System#nanoTime}). */
        Optional<Refusal> since(long sentAt, Collection<String> subjects) {
            for (String subject : subjects) {
                Seen s = seen.get(subject);
                if (s != null && s.at() - sentAt >= 0) {
                    return Optional.of(new Refusal(subject, s.how()));
                }
            }
            return Optional.empty();
        }

        /** {@link System#nanoTime} as this listener reads it, for the {@code sentAt} of {@link #since}. */
        long now() {
            return nanos.getAsLong();
        }
    }
}
