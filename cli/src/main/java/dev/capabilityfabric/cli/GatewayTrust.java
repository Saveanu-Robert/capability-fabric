package dev.capabilityfabric.cli;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Which gateway this device talks to. The device pins the certificate authority behind the gateway (the last
 * certificate of the chain it presents, self-signed), not the gateway's own certificate, so a renewed gateway
 * certificate from the same authority keeps working. A pin comes from an invitation, from
 * {@code --trust-gateway <fingerprint>}, from this machine's secrets folder (for a gateway on this machine), or from
 * the person's yes after seeing the fingerprint; it is then remembered per host and port in {@code known_gateways}.
 */
final class GatewayTrust {

    static final String KNOWN = "known_gateways";
    private static final int MIN_PIN_HEX = 32;

    private GatewayTrust() {
    }

    /** SHA-256 of the certificate, as colon-separated upper-case hex (the form the gateway prints). */
    static String fingerprint(X509Certificate certificate) {
        try {
            return HexFormat.ofDelimiter(":").withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The authority that issued the chain: its last certificate, which must sign itself, while every certificate is
     * signed by the one after it. A chain without its authority is refused, since there would be nothing to pin.
     */
    static X509Certificate authority(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("the gateway presented no certificate");
        }
        try {
            for (int i = 0; i < chain.length - 1; i++) {
                chain[i].verify(chain[i + 1].getPublicKey());
            }
            X509Certificate last = chain[chain.length - 1];
            last.verify(last.getPublicKey());
            chain[0].checkValidity();
            return last;
        } catch (CertificateException e) {
            throw e;
        } catch (GeneralSecurityException e) {
            throw new CertificateException("the gateway's certificate chain does not end at the authority that "
                    + "issued it", e);
        }
    }

    /** Hex digits only, lower case; colons and spaces dropped. */
    static String normalize(String pin) {
        return pin == null ? "" : pin.replace(":", "").replace(" ", "").strip().toLowerCase(Locale.ROOT);
    }

    /** True when {@code pin} (at least 32 hex digits, colon-separated or not, any case) names this fingerprint. */
    static boolean matches(String pin, String fingerprint) {
        String p = normalize(pin);
        return p.length() >= MIN_PIN_HEX && p.matches("[0-9a-f]+") && normalize(fingerprint).startsWith(p);
    }

    /** The first 32 hex digits of a fingerprint: the form invitations carry and {@code --trust-gateway} accepts. */
    static String shortPin(String fingerprint) {
        String hex = normalize(fingerprint);
        return hex.substring(0, Math.min(MIN_PIN_HEX, hex.length()));
    }

    /** {@code host:port} as remembered in {@code known_gateways}. */
    static String endpoint(URI uri) {
        return uri.getHost() + ":" + (uri.getPort() < 0 ? 443 : uri.getPort());
    }

    /** The fingerprint this device remembers for {@code endpoint}, if any. */
    static Optional<String> known(Fabric.Device device, String endpoint) {
        for (String line : device.read(KNOWN).orElse("").split("\\R")) {
            if (line.startsWith(endpoint + " ")) {
                return Optional.of(line.substring(endpoint.length() + 1).strip());
            }
        }
        return Optional.empty();
    }

    /** Every {@code host:port} this device remembers a gateway for. */
    static List<String> endpoints(Fabric.Device device) {
        List<String> endpoints = new ArrayList<>();
        for (String line : device.read(KNOWN).orElse("").split("\\R")) {
            String endpoint = line.strip().split("\\s+", 2)[0];
            if (!endpoint.isEmpty() && !endpoints.contains(endpoint)) {
                endpoints.add(endpoint);
            }
        }
        return endpoints;
    }

    /** Remembers {@code fingerprint} for {@code endpoint}, replacing what was remembered for it. */
    static void remember(Fabric.Device device, String endpoint, String fingerprint) {
        StringBuilder lines = new StringBuilder();
        for (String line : device.read(KNOWN).orElse("").split("\\R")) {
            if (!line.isBlank() && !line.startsWith(endpoint + " ")) {
                lines.append(line).append(System.lineSeparator());
            }
        }
        device.write(KNOWN, lines + endpoint + " " + fingerprint);
    }

    /**
     * The pins a first contact must satisfy. With a remembered pin or a given one nothing more is needed; a gateway
     * on this machine is checked against the local certificate authority; otherwise the authority's fingerprint is
     * fetched (nothing is sent) and shown, and the person is asked, or, without a terminal, told to pass
     * {@code --trust-gateway}.
     *
     * @param ask the question to the person and their answer, or {@code null} without a terminal
     */
    static List<String> pinsForFirstContact(URI uri, Fabric.Device device, List<String> given,
                                            Optional<Secrets> secrets, Function<String, String> ask) {
        String endpoint = endpoint(uri);
        if (known(device, endpoint).isPresent() || !given.isEmpty()) {
            return given;
        }
        Optional<Path> localAuthority = loopback(uri.getHost()) ? secrets.flatMap(Secrets::authority) : Optional.empty();
        if (localAuthority.isPresent()) {
            return List.of(fingerprint(readCertificate(localAuthority.get())));
        }
        String fingerprint;
        try {
            fingerprint = fingerprint(authority(probe(uri, Duration.ofSeconds(10))));
        } catch (CertificateException e) {
            throw new Fabric.CliException("cannot trust the gateway at " + endpoint + ": " + e.getMessage());
        }
        String shown = "The gateway " + endpoint + " is new to this device. Its certificate authority's fingerprint is\n  "
                + fingerprint + "\nCompare it with the one the gateway printed when it started.";
        if (ask == null) {
            throw new Fabric.CliException(shown + "\nIf they match, run again with:  --trust-gateway " + fingerprint);
        }
        String answer = ask.apply(shown + "\nTrust this gateway? [y/N] ");
        if (answer == null || !List.of("y", "yes").contains(answer.strip().toLowerCase(Locale.ROOT))) {
            throw new Fabric.CliException("the gateway is not trusted; nothing was sent");
        }
        return List.of(fingerprint);
    }

    /** True for {@code localhost} or a loopback IP literal; other host names are not looked up. */
    static boolean loopback(String host) {
        if (host == null) {
            return false;
        }
        if (host.equalsIgnoreCase("localhost")) {
            return true;
        }
        try {
            String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            boolean ipLiteral = literal.matches("[0-9.]+") || literal.matches("[0-9a-fA-F:]*:[0-9a-fA-F:]*");
            return ipLiteral && InetAddress.getByName(literal).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    static X509Certificate readCertificate(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException | GeneralSecurityException e) {
            throw new Fabric.CliException("cannot read the certificate authority " + file + ": " + e.getMessage());
        }
    }

    /** The chain the gateway presents, fetched with a TLS handshake that sends no request. */
    static X509Certificate[] probe(URI uri, Duration timeout) {
        X509Certificate[][] seen = new X509Certificate[1][];
        X509TrustManager capture = new X509TrustManager() {
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                seen[0] = chain.clone();
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a server");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        int port = uri.getPort() < 0 ? 443 : uri.getPort();
        try (Socket raw = new Socket()) {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{capture}, null);
            raw.connect(new InetSocketAddress(uri.getHost(), port), (int) timeout.toMillis());
            raw.setSoTimeout((int) timeout.toMillis());
            try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(raw, uri.getHost(), port, true)) {
                tls.startHandshake();
            }
        } catch (IOException | GeneralSecurityException e) {
            throw new Fabric.CliException("cannot reach the gateway at " + endpoint(uri) + ": " + e.getMessage());
        }
        if (seen[0] == null || seen[0].length == 0) {
            throw new Fabric.CliException("the gateway at " + endpoint(uri) + " presented no certificate");
        }
        return seen[0];
    }

    /**
     * Checks the gateway during every TLS handshake, before any request is sent: the chain must end at an authority
     * that matches every given pin and the one remembered for this host and port. The first trusted contact is
     * remembered. A remembered pin of the gateway's own certificate, rather than of its authority, is replaced by
     * its authority's.
     */
    static final class PinningTrust implements X509TrustManager {

        private final String endpoint;
        private final Fabric.Device device;
        private final List<String> pins;

        PinningTrust(String endpoint, Fabric.Device device, List<String> pins) {
            this.endpoint = endpoint;
            this.device = device;
            this.pins = List.copyOf(pins);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            X509Certificate authority = authority(chain);
            String fingerprint = fingerprint(authority);
            for (String pin : pins) {
                if (!matches(pin, fingerprint)) {
                    throw new CertificateException("the gateway's certificate authority " + fingerprint
                            + " is not the expected one (" + pin + ")");
                }
            }
            Optional<String> known = known(device, endpoint);
            if (known.isPresent()) {
                if (matches(known.get(), fingerprint)) {
                    return;
                }
                if (matches(known.get(), fingerprint(chain[0]))) {
                    remember(device, endpoint, fingerprint);
                    return;
                }
                throw new CertificateException("the gateway's certificate authority changed since this device first "
                        + "trusted it; if that is expected, remove its line from " + device.home().resolve(KNOWN));
            }
            if (pins.isEmpty()) {
                throw new CertificateException("this device does not trust the gateway " + endpoint + " yet");
            }
            remember(device, endpoint, fingerprint);
            System.err.println("Trusting the gateway " + endpoint + " from now on; its certificate authority is\n  "
                    + fingerprint);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("not a server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
