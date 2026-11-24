package dev.capabilityfabric.setup;

import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.Subjects;
import io.nats.client.NKey;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Creates the security material once, one folder per role, using only the JDK and its {@code keytool}, so it needs
 * no other tool:
 * <pre>
 *   ca/       ca.p12, ca.pass               the local certificate authority (only setup reads it)
 *   public/   ca.crt, kernel.pub            what everyone may know
 *   nats/     nats.crt, nats.key, nats.conf the broker's certificate and configuration
 *   kernel/   kernel.nk, kernel.key, kernel.key.seal   the kernel's broker login, Ed25519 identity and X25519 key
 *   agent/    agent.nk                      the agent role's broker login
 *   person/   person.nk                     the person role's broker login (the CLI)
 *   gateway/  gateway.p12, gateway.pass, gateway.hosts, gateway.nk   the gateway's certificate and broker login
 *   db/       fabric.db.password            the database password (postgres and the kernel)
 * </pre>
 * Each service mounts only the folders it needs. Running setup again keeps every key; it re-issues a certificate
 * when it is missing, expires within {@link #RENEW_BEFORE}, was signed by another authority, or (for the gateway)
 * no longer lists the host names in {@code FABRIC_PUBLIC_HOSTS}. The broker configuration is rewritten from the keys.
 * Files written before the per-role layout existed are moved into their folders.
 */
public final class Setup {

    /** The fixed user and group id the service containers run as (see the Dockerfile). */
    static final int SERVICE_ID = 10001;
    /** The user id postgres runs as in the official image. */
    static final int POSTGRES_ID = 999;
    static final Duration RENEW_BEFORE = Duration.ofDays(90);
    private static final int LEAF_DAYS = 825;
    private static final int CA_DAYS = 3650;

    /** Where each file of the flat layout moves to. */
    private static final Map<String, String> LEGACY = Map.ofEntries(
            Map.entry("ca.p12", "ca"), Map.entry("ca.pass", "ca"), Map.entry("ca.crt", "public"),
            Map.entry("nats.crt", "nats"), Map.entry("nats.key", "nats"), Map.entry("nats.conf", "nats"),
            Map.entry("kernel.nk", "kernel"), Map.entry("kernel.key", "kernel"), Map.entry("agent.nk", "agent"),
            Map.entry("person.nk", "person"), Map.entry("gateway.p12", "gateway"), Map.entry("gateway.pass", "gateway"));

    private final Path dir;
    private final List<String> publicHosts;
    private final Optional<Integer> projectOwner;
    private final List<String> notes = new ArrayList<>();

    Setup(Path dir, List<String> publicHosts) {
        this(dir, publicHosts, Optional.empty());
    }

    /**
     * @param projectOwner the user id that owns the project files on the host (compose mounts compose.yaml into the
     *                     setup container for this); used when the secrets folder was created by the container runtime
     */
    Setup(Path dir, List<String> publicHosts, Optional<Integer> projectOwner) {
        this.dir = dir;
        this.publicHosts = publicHosts;
        this.projectOwner = projectOwner;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args.length > 0 ? args[0] : "secrets");
        List<String> hosts = new ArrayList<>();
        String env = System.getenv("FABRIC_PUBLIC_HOSTS");
        if (env != null) {
            for (String h : env.split(",")) {
                if (!h.isBlank()) {
                    try {
                        hosts.add(publicHost(h));
                    } catch (IllegalArgumentException e) {
                        // A bad name must not stop the broker and the gateway from starting: it is left out.
                        System.out.println("  skipped: " + e.getMessage());
                    }
                }
            }
        }
        Path project = Path.of(System.getenv().getOrDefault("FABRIC_PROJECT_FILE", "/project/compose.yaml"));
        new Setup(dir, hosts, Files.exists(project) ? uid(project) : Optional.empty()).run();
        // Only the compose setup service names the folder its service volumes are mounted under; run elsewhere (on a
        // host, in a test) setup touches no volumes.
        String volumes = System.getenv("FABRIC_SERVICE_VOLUMES");
        if (volumes != null && !volumes.isBlank()) {
            adoptVolumes(Path.of(volumes), SERVICE_ID).forEach(n -> System.out.println("  " + n));
        }
    }

    /**
     * Gives every file and folder in the service volumes mounted under {@code root} (one folder per volume, such as
     * the CLI's home and each agent's key volume) to the service user {@code id}, when another user owns it (an image
     * that ran under another user id left it there), since the services cannot read keys another user owns. Returns one
     * line per volume that changed, and one per volume this file system does not let setup change.
     */
    static List<String> adoptVolumes(Path root, int id) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return lines;
        }
        List<Path> volumes;
        try (var listed = Files.list(root)) {
            volumes = listed.filter(Files::isDirectory).sorted().toList();
        }
        for (Path volume : volumes) {
            List<Path> foreign;
            try (var walk = Files.walk(volume)) {
                foreign = walk.filter(p -> !Files.isSymbolicLink(p))
                        .filter(p -> uid(p).map(u -> u != id).orElse(false) || gid(p).map(g -> g != id).orElse(false))
                        .toList();
            } catch (IOException | UncheckedIOException e) {
                // One unreadable volume must not stop setup, and with it the broker, the database and the gateway.
                lines.add("could not look through the " + volume.getFileName() + " volume: " + e.getMessage());
                continue;
            }
            if (foreign.isEmpty()) {
                continue;
            }
            int given = 0;
            for (Path p : foreign) {
                try {
                    Files.setAttribute(p, "unix:uid", id);
                    Files.setAttribute(p, "unix:gid", id);
                    given++;
                } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
                    // Left as it is; the line below says how many could not be given.
                }
            }
            String name = volume.getFileName().toString();
            lines.add(given == foreign.size()
                    ? "gave " + given + " file(s) in the " + name + " volume to the service user " + id
                    : "could not give " + (foreign.size() - given) + " file(s) in the " + name + " volume to the "
                            + "service user " + id + "; the service using it may not read its keys");
        }
        return lines;
    }

    void run() throws Exception {
        Files.createDirectories(dir);
        // Private from the first file on: the authority's key and the seeds are written before protect() runs.
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // File systems without POSIX modes keep their own access rules.
        }
        migrateFlatLayout();
        for (String folder : List.of("ca", "public", "nats", "kernel", "agent", "person", "gateway", "db")) {
            Files.createDirectories(dir.resolve(folder));
        }
        String caPassword = secret(dir.resolve("ca/ca.pass"));
        Path caStore = dir.resolve("ca/ca.p12");
        boolean replacing = Files.exists(caStore);
        boolean upgrade = replacing;
        boolean reissued = false;
        if (!replacing || expiresSoon(caCertificate(caStore, caPassword))) {
            Files.deleteIfExists(caStore);
            keytool("-genkeypair", "-alias", "ca", "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                    "-dname", "CN=Capability Fabric local certificate authority", "-ext", "bc:c", "-validity", Integer.toString(CA_DAYS),
                    "-keystore", caStore.toString(), "-storetype", "PKCS12", "-storepass", caPassword, "-keypass", caPassword);
            if (replacing) {
                notes.add("replaced the certificate authority; every service trusts it only after it starts again: "
                        + "docker compose up -d --force-recreate; devices that trusted the old one must trust it again");
            }
        }
        X509Certificate ca = caCertificate(caStore, caPassword);
        writeIfChanged(dir.resolve("public/ca.crt"), pem(ca));

        List<String> natsHosts = List.of("nats", "localhost");
        if (needsCertificate(readPemCertificate(dir.resolve("nats/nats.crt")), ca, natsHosts)
                || !Files.exists(dir.resolve("nats/nats.key"))) {
            Issued nats = issue("nats", natsHosts, caStore, caPassword);
            writeIfChanged(dir.resolve("nats/nats.crt"), pem(nats.certificate()) + pem(ca));
            writeIfChanged(dir.resolve("nats/nats.key"), pem(nats.key()));
            notes.add("issued the broker's certificate");
            reissued = upgrade;
        }

        List<String> gatewayHosts = hostNames(List.of("gateway", "localhost"));
        Path gatewayStore = dir.resolve("gateway/gateway.p12");
        String gatewayPassword = secret(dir.resolve("gateway/gateway.pass"));
        if (needsCertificate(gatewayCertificate(gatewayStore, gatewayPassword), ca, gatewayHosts)) {
            Issued gateway = issue("gateway", gatewayHosts, caStore, caPassword);
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("gateway", gateway.key(), gatewayPassword.toCharArray(),
                    new Certificate[]{gateway.certificate(), ca});
            Path tmp = gatewayStore.resolveSibling("gateway.p12.tmp");
            try (var out = Files.newOutputStream(tmp)) {
                store.store(out, gatewayPassword.toCharArray());
            }
            Files.move(tmp, gatewayStore, StandardCopyOption.REPLACE_EXISTING);
            notes.add("issued the gateway's certificate for " + String.join(", ", gatewayHosts));
            reissued = reissued || upgrade;
        }
        writeIfChanged(dir.resolve("gateway/gateway.hosts"), String.join("\n", gatewayHosts) + "\n");

        String kernel = nkey(dir.resolve("kernel/kernel.nk"));
        String agent = nkey(dir.resolve("agent/agent.nk"));
        String person = nkey(dir.resolve("person/person.nk"));
        String gateway = nkey(dir.resolve("gateway/gateway.nk"));
        writeIfChanged(dir.resolve("nats/nats.conf"), natsConfig(kernel, agent, person, gateway));
        if (!Files.exists(dir.resolve("db/fabric.db.password"))) {
            secret(dir.resolve("db/fabric.db.password"));
            if (upgrade) {
                notes.add("created a database password; postgres takes it over at its next start");
            }
        }
        Security.Identity identity = Security.loadOrCreate(dir.resolve("kernel/kernel.key"));
        // The kernel's X25519 key for sealed requests (pairing and invitation codes); the kernel mounts its folder
        // read-only, so setup creates it.
        Security.SealKey.loadOrCreate(dir.resolve("kernel/kernel.key.seal"));
        writeIfChanged(dir.resolve("public/kernel.pub"), identity.publicKeyText() + "\n");

        protect();
        if (reissued) {
            notes.add("a running broker and gateway keep the old certificate until they restart: "
                    + "docker compose restart nats gateway");
        }

        System.out.println("Capability Fabric security material is in " + dir.toAbsolutePath());
        System.out.println("  kernel key            " + Security.fingerprint(identity.publicKeyText()));
        System.out.println("  certificate authority " + fingerprint(ca));
        System.out.println("  gateway names         " + String.join(", ", gatewayHosts) + ", 127.0.0.1");
        notes.forEach(n -> System.out.println("  " + n));
    }

    /** Moves files of the flat layout (everything in one folder) into the per-role folders, keeping their content. */
    private void migrateFlatLayout() throws IOException {
        for (var entry : LEGACY.entrySet()) {
            Path old = dir.resolve(entry.getKey());
            if (Files.isRegularFile(old)) {
                Path target = dir.resolve(entry.getValue()).resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) {
                    Files.delete(old);
                } else {
                    Files.move(old, target);
                }
            }
        }
    }

    record Issued(PrivateKey key, X509Certificate certificate) {
    }

    /** A new key pair for {@code name} and a certificate for it signed by the local authority. */
    Issued issue(String name, List<String> dnsNames, Path caStore, String caPassword) throws Exception {
        String password = password();
        Path work = Files.createTempDirectory(dir.resolve("ca"), name);
        Path store = work.resolve(name + ".p12");
        Path csr = work.resolve(name + ".csr");
        Path signed = work.resolve(name + ".signed");
        try {
            keytool("-genkeypair", "-alias", name, "-keyalg", "EC", "-groupname", "secp256r1", "-sigalg", "SHA256withECDSA",
                    "-dname", "CN=" + name, "-validity", Integer.toString(LEAF_DAYS), "-keystore", store.toString(),
                    "-storetype", "PKCS12", "-storepass", password, "-keypass", password);
            keytool("-certreq", "-alias", name, "-keystore", store.toString(), "-storepass", password, "-file", csr.toString());
            keytool("-gencert", "-alias", "ca", "-keystore", caStore.toString(), "-storepass", caPassword,
                    "-infile", csr.toString(), "-outfile", signed.toString(), "-rfc", "-validity", Integer.toString(LEAF_DAYS),
                    "-ext", "SAN=" + san(dnsNames), "-ext", "KU=digitalSignature", "-ext", "EKU=serverAuth");
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                ks.load(in, password.toCharArray());
            }
            PrivateKey key = (PrivateKey) ks.getKey(name, password.toCharArray());
            return new Issued(key, (X509Certificate) certificate(signed));
        } finally {
            for (Path p : List.of(store, csr, signed, work)) {
                Files.deleteIfExists(p);
            }
        }
    }

    /** True when there is no certificate, it ends soon, another authority signed it, or it misses a host name. */
    static boolean needsCertificate(Optional<X509Certificate> current, X509Certificate ca, List<String> hosts) {
        if (current.isEmpty() || expiresSoon(current.get())) {
            return true;
        }
        try {
            current.get().verify(ca.getPublicKey());
        } catch (Exception e) {
            return true;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            var alternatives = current.get().getSubjectAlternativeNames();
            if (alternatives != null) {
                alternatives.forEach(l -> names.add(l.get(1).toString()));
            }
        } catch (Exception e) {
            return true;
        }
        return !names.stream().map(Setup::canonicalHost).toList()
                .containsAll(hosts.stream().map(Setup::canonicalHost).toList());
    }

    /**
     * A name from {@code FABRIC_PUBLIC_HOSTS} as a certificate can carry it: a scheme, a port and the brackets of an
     * IPv6 address are removed ({@code https://[fd00::5]:8443} becomes {@code fd00::5}); anything that is still not a
     * host name or an IP address is refused with the reason.
     */
    static String publicHost(String given) {
        String h = given.strip().replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) {
            h = h.substring(0, slash);
        }
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end > 0 ? h.substring(1, end) : h.substring(1);
        } else if (h.chars().filter(c -> c == ':').count() == 1) {
            h = h.substring(0, h.indexOf(':'));
        }
        String refused = "FABRIC_PUBLIC_HOSTS: \"" + given.strip()
                + "\" is not a host name or an IP address (write names like fabric.example.org, comma-separated)";
        if (h.matches("[0-9.]+")) {
            // Only a full dotted quad with each part 0-255 is an IPv4 address; "1234" or "10.0.0.256" are refused.
            if (!h.matches("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}")) {
                throw new IllegalArgumentException(refused);
            }
            return h;
        }
        if (h.contains(":")) {
            if (!h.matches("[0-9a-fA-F:]+")) {
                throw new IllegalArgumentException(refused);
            }
            try {
                InetAddress.ofLiteral(h); // a valid IPv6 literal; kept as written (canonicalHost compares)
                return h;
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(refused);
            }
        }
        // A DNS name has labels of at most 63 characters and at most 253 characters in all.
        boolean name = h.length() <= 253
                && h.matches("(?i)[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*");
        if (h.isEmpty() || !name) {
            throw new IllegalArgumentException(refused);
        }
        return h;
    }

    /** What reads as an IP address rather than a host name: digits and dots, or hex digits with a colon. */
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9.]+|[0-9a-fA-F:]+:[0-9a-fA-F:]*");

    /** An IP address in one spelling ({@code ::1} and {@code 0:0:0:0:0:0:0:1} are the same); host names lower-cased. */
    static String canonicalHost(String host) {
        if (IP_LITERAL.matcher(host).matches()) {
            try {
                return InetAddress.ofLiteral(host).getHostAddress();
            } catch (IllegalArgumentException e) {
                return host;
            }
        }
        return host.toLowerCase(Locale.ROOT);
    }

    static boolean expiresSoon(X509Certificate certificate) {
        return certificate.getNotAfter().toInstant().isBefore(Instant.now().plus(RENEW_BEFORE));
    }

    String san(List<String> dnsNames) {
        List<String> parts = new ArrayList<>();
        for (String host : dnsNames) {
            parts.add(IP_LITERAL.matcher(host).matches() ? "ip:" + host : "dns:" + host);
        }
        parts.add("ip:127.0.0.1");
        return String.join(",", parts);
    }

    List<String> hostNames(List<String> base) {
        List<String> all = new ArrayList<>(base);
        publicHosts.stream().filter(h -> !all.contains(h)).forEach(all::add);
        return all;
    }

    /** An NKey for a broker role: the seed stays in its folder, the public key goes into the broker configuration. */
    static String nkey(Path seed) throws Exception {
        NKey key;
        if (Files.exists(seed)) {
            key = NKey.fromSeed(Files.readString(seed).strip().toCharArray());
        } else {
            key = NKey.createUser(new SecureRandom());
            Files.writeString(seed, new String(key.getSeed()) + "\n");
        }
        return new String(key.getPublicKey());
    }

    /**
     * The broker floor, one user per role. The kernel may do anything. Agents may publish only the kernel's request
     * subjects meant for agents (registration, admission, context, the goal list, recorded decisions, memory,
     * grown-capability specs, proposals, inbox posts) and have no JetStream rights at all: they follow the
     * kernel-signed signals with a plain subscription; they answer the kernel's calls and calls for proposals only
     * through the reply subject of a message they received ({@code allow_responses}). They may not read facts (only the
     * thin signals), the kernel's request subjects, the reply subjects of the kernel, people or the gateway, nor the
     * JetStream API and system subjects. People (the CLI) may only send sentences, answers, inbox reads, the kill
     * switch and resume, pairing and joining (and the request for the kernel's keys that comes before them), read
     * goals, and listen to their own replies and to facts. The gateway may only forward what its allowlist accepts, on
     * its own {@code gw.} subjects (so the kernel knows a request came from the internet), and listen to its own
     * replies. Each role's replies arrive under its own prefix ({@code _INBOX_kernel}, {@code _INBOX_agent},
     * {@code _INBOX_person}, {@code _INBOX_gateway}).
     */
    static String natsConfig(String kernel, String agent, String person, String gateway) {
        return """
                # Written by Capability Fabric setup from the keys in its folders; run setup again instead of editing it.
                port: 4222
                # No message (sentence, call, reply, fact) needs more; a bound keeps any single sender from filling
                # the receivers' memory with a few huge messages.
                max_payload: 262144
                jetstream {
                  store_dir: "/data"
                }
                tls {
                  cert_file: "/security/nats/nats.crt"
                  key_file: "/security/nats/nats.key"
                  timeout: 5
                }
                authorization {
                  users: [
                    { nkey: "%s" }
                    { nkey: "%s", permissions: {
                        publish: { allow: ["proposal.submit", "inbox.post", "query.context", "query.goals",
                          "query.decision.lookup", "query.memory", "query.genesis", "security.admission",
                          "registry.heartbeat", "registry.withdraw"] }
                        subscribe: { allow: [">"], deny: ["intent.>", "control.>", "security.>", "query.>",
                          "proposal.>", "inbox.post", "registry.>", "gw.>", "fact.>", "_INBOX.>", "_INBOX_kernel.>",
                          "_INBOX_person.>", "_INBOX_gateway.>", "$JS.>", "$SYS.>", "$KV.>"] }
                        allow_responses: { max: 1, expires: "15m" }
                    } }
                    { nkey: "%s", permissions: {
                        publish: { allow: ["intent.>", "control.>", "security.pair", "security.join", "security.hello",
                          "query.goal", "query.goals"] }
                        subscribe: { allow: ["_INBOX_person.>", "fact.>"] }
                    } }
                    { nkey: "%s", permissions: {
                        publish: { allow: [%s] }
                        subscribe: { allow: ["_INBOX_gateway.>"] }
                    } }
                  ]
                }
                """.formatted(kernel, agent, person, gateway, gatewayPublish());
    }

    /** What the gateway's user may publish: the gateway subject of every request it forwards, sorted. */
    static String gatewayPublish() {
        return String.join(", ", Subjects.GATEWAY_SUBJECTS.stream().sorted()
                .map(s -> "\"" + Subjects.viaGateway(s) + "\"").toList());
    }

    /**
     * File ownership and modes per folder. The service containers run as {@link #SERVICE_ID}: they own kernel/ and
     * gateway/ (owner-only). person/ and agent/ stay owned by whoever owns the secrets folder (the person running
     * the CLI or an agent on this machine) with read access for the service group. public/ is readable by all;
     * ca/ and nats/ by their owner only (setup and the broker run as root). Where ownership cannot be changed (a
     * file system that maps every file to the host user, or no POSIX support), the folders a service must read
     * fall back to world-readable so the services still start.
     */
    private void protect() throws IOException {
        int folderOwner = uid(dir).orElse(-1);
        // The secrets folder itself is private to its owner; the containers reach their folders through bind mounts,
        // which do not need access to the parent folder.
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // File systems without POSIX modes keep their own access rules.
        }
        boolean root = "root".equals(System.getProperty("user.name"));
        if (folderOwner == 0 && projectOwner.isPresent() && projectOwner.get() > 0) {
            // The container runtime created the folder as root: give it to whoever owns the project files.
            try {
                Files.setAttribute(dir, "unix:uid", projectOwner.get());
                folderOwner = uid(dir).orElse(folderOwner);
            } catch (UnsupportedOperationException | IllegalArgumentException | IOException ignored) {
                // Ownership cannot be changed here; the note below explains what to do.
            }
        }
        applyFolder("ca", -1, -1, "rw-------", "rwx------");
        applyFolder("nats", -1, -1, "rw-------", "rwx------");
        applyFolder("public", -1, -1, "rw-r--r--", "rwxr-xr-x");
        boolean owned = root
                && applyFolder("kernel", SERVICE_ID, SERVICE_ID, "rw-------", "rwx------")
                & applyFolder("gateway", SERVICE_ID, SERVICE_ID, "rw-------", "rwx------")
                & applyFolder("person", folderOwner, SERVICE_ID, "rw-r-----", "rwxr-x---")
                & applyFolder("agent", folderOwner, SERVICE_ID, "rw-r-----", "rwxr-x---")
                // The database password: postgres reads it as its own user, the kernel through the service group.
                & applyFolder("db", POSTGRES_ID, SERVICE_ID, "rw-r-----", "rwxr-x---");
        if (!owned) {
            for (String folder : List.of("kernel", "gateway", "person", "agent", "db")) {
                applyFolder(folder, -1, -1, "rw-r--r--", "rwxr-xr-x");
            }
            if (root) {
                notes.add("this file system ignores file owners, so the service folders are readable by every "
                        + "local user; keep the secrets folder in a private directory");
            }
        }
        // With a rootless runtime the host user appears as root here, so a root-owned folder is no problem then.
        if (owned && folderOwner == 0 && projectOwner.map(o -> o > 0).orElse(true)) {
            notes.add("the secrets folder belongs to root, so the CLI on this machine cannot read person/; create "
                    + "the folder yourself before the first start (mkdir secrets) or change its owner");
        }
    }

    /** Sets owner (when {@code uid} is not -1), group and modes on a folder and its files; false when ignored. */
    private boolean applyFolder(String folder, int uid, int gid, String fileMode, String dirMode) throws IOException {
        Path path = dir.resolve(folder);
        List<Path> targets = new ArrayList<>();
        targets.add(path);
        try (var files = Files.list(path)) {
            files.forEach(targets::add);
        }
        boolean applied = true;
        for (Path p : targets) {
            try {
                if (uid >= 0) {
                    Files.setAttribute(p, "unix:uid", uid);
                }
                if (gid >= 0) {
                    Files.setAttribute(p, "unix:gid", gid);
                }
                Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? dirMode : fileMode));
                if (uid >= 0 && uid(p).orElse(-1) != uid || gid >= 0 && gid(p).orElse(-1) != gid) {
                    applied = false;
                }
            } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
                applied = false;
            }
        }
        return applied;
    }

    private static Optional<Integer> uid(Path p) {
        try {
            return Optional.of((Integer) Files.getAttribute(p, "unix:uid"));
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> gid(Path p) {
        try {
            return Optional.of((Integer) Files.getAttribute(p, "unix:gid"));
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Replaces a file only when its content changes, through a temporary file, so readers never see it half written.
     */
    static void writeIfChanged(Path file, String content) throws IOException {
        if (Files.exists(file) && Files.readString(file).equals(content)) {
            return;
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String secret(Path file) throws IOException {
        if (Files.exists(file)) {
            return Files.readString(file).strip();
        }
        String value = password();
        Files.writeString(file, value + "\n");
        return value;
    }

    /**
     * A random password for a keystore. It never starts with "-", which keytool would read as an option when the
     * password is typed on its command line.
     */
    static String password() {
        String value;
        do {
            value = Security.randomToken(18);
        } while (value.startsWith("-"));
        return value;
    }

    private static X509Certificate caCertificate(Path store, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, password.toCharArray());
        }
        return (X509Certificate) ks.getCertificate("ca");
    }

    private static Optional<X509Certificate> gatewayCertificate(Path store, String password) {
        if (!Files.exists(store)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(store)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password.toCharArray());
            return Optional.ofNullable((X509Certificate) ks.getCertificate("gateway"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<X509Certificate> readPemCertificate(Path pem) {
        if (!Files.exists(pem)) {
            return Optional.empty();
        }
        try {
            return Optional.of((X509Certificate) certificate(pem));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** The SHA-256 of a certificate, as people compare it: colon-separated upper-case hex. */
    static String fingerprint(Certificate certificate) throws Exception {
        return HexFormat.ofDelimiter(":").withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
    }

    static Certificate certificate(Path pem) throws Exception {
        try (InputStream in = Files.newInputStream(pem)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    static String pem(Certificate certificate) throws Exception {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    static String pem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    /** keytool options whose value is a password. */
    private static final Set<String> PASSWORD_OPTIONS = Set.of("-storepass", "-keypass");

    /**
     * Runs the JDK's keytool (from java.home, so no PATH or shell is involved). Passwords given with
     * {@link #PASSWORD_OPTIONS} reach keytool through its environment ({@code -storepass:env <variable>}), so they do
     * not appear in its command line, which other users of the machine can list.
     */
    static void keytool(String... args) throws Exception {
        String exe = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        List<String> command = new ArrayList<>();
        command.add(exe);
        Map<String, String> passwords = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (PASSWORD_OPTIONS.contains(args[i]) && i + 1 < args.length) {
                String variable = "FABRIC_KEYTOOL_PASSWORD_" + passwords.size();
                command.add(args[i] + ":env");
                command.add(variable);
                passwords.put(variable, args[++i]);
            } else {
                command.add(args[i]);
            }
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(passwords);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException("keytool " + args[0] + " failed: " + output);
        }
    }
}
