package dev.capabilityfabric.cli;

import io.nats.client.AuthHandler;
import io.nats.client.NKey;

import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** The broker floor: TLS that trusts setup's certificate authority, and NKey challenge-response login. */
final class BrokerAuth {

    private BrokerAuth() {
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
        } catch (AccessDeniedException e) {
            throw Secrets.unreadable(Path.of(seedFile));
        } catch (Exception e) {
            throw new Fabric.CliException("cannot read the NKey seed " + seedFile + ": " + e.getMessage());
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
            throw new Fabric.CliException("cannot load the certificate authority " + caFile + ": " + e.getMessage());
        }
    }
}
