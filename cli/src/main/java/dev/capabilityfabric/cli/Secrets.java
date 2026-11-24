package dev.capabilityfabric.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The folder setup wrote on this machine: FABRIC_SECRETS, else ./secrets. Files live in one folder per role
 * ({@code person/person.nk}, {@code public/ca.crt}, {@code public/kernel.pub}); folders written by older setups keep
 * them side by side ({@code person.nk}, {@code ca.crt}), which is read as well. A file or folder that exists but
 * cannot be read by the person running the CLI is an error, never a reason to go on without it.
 */
record Secrets(Path dir) {

    static final String PLAIN_URL = "nats://localhost:4222";
    static final String TLS_URL = "tls://localhost:4222";

    static Optional<Secrets> find() {
        return find(System.getenv("FABRIC_SECRETS"));
    }

    static Optional<Secrets> find(String configured) {
        Path p = configured != null && !configured.isBlank() ? Path.of(configured.strip()) : Path.of("secrets");
        return Files.isDirectory(p) ? Optional.of(new Secrets(p)) : Optional.empty();
    }

    /** The person role's broker login. */
    Optional<Path> personSeed() {
        return find("person", "person.nk", true);
    }

    /** The certificate authority that signed the broker's and the gateway's certificates. */
    Optional<Path> authority() {
        return find("public", "ca.crt", true);
    }

    /** The kernel's Ed25519 public key text, one line. */
    Optional<Path> kernelKey() {
        return find("public", "kernel.pub", false);
    }

    /** TLS on the local broker when setup made a certificate authority, plain NATS otherwise. */
    String brokerUrl() {
        return authority().isPresent() ? TLS_URL : PLAIN_URL;
    }

    static String brokerUrl(Optional<Secrets> secrets) {
        return secrets.map(Secrets::brokerUrl).orElse(PLAIN_URL);
    }

    /**
     * {@code <dir>/<role>/<name>}, else (when {@code flat}) {@code <dir>/<name>}. A folder that cannot be entered
     * hides whether the file is there, so it fails like a file that cannot be read.
     */
    private Optional<Path> find(String role, String name, boolean flat) {
        if (!Files.isExecutable(dir)) {
            throw unreadable(dir);
        }
        Path folder = dir.resolve(role);
        Path file = folder.resolve(name);
        if (Files.isRegularFile(file)) {
            return Optional.of(readable(file));
        }
        if (Files.isDirectory(folder) && !Files.isExecutable(folder)) {
            throw unreadable(folder);
        }
        Path old = dir.resolve(name);
        return flat && Files.isRegularFile(old) ? Optional.of(readable(old)) : Optional.empty();
    }

    private static Path readable(Path file) {
        if (!Files.isReadable(file)) {
            throw unreadable(file);
        }
        return file;
    }

    static Fabric.CliException unreadable(Path path) {
        return new Fabric.CliException("cannot read " + path.toAbsolutePath() + ": permission denied for the user "
                + System.getProperty("user.name") + ". Give this user read access to it (on Linux or macOS, for "
                + "example: sudo chown -R \"$(id -u)\" " + path.toAbsolutePath() + ")");
    }
}
