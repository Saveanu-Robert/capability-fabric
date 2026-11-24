package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.Subjects;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The secrets folder setup creates, as a process started on the host finds it: {@code FABRIC_SECRETS}, else
 * {@code ./secrets} (where the CLI looks too). An agent whose settings name neither a certificate authority nor a
 * seed uses the folder's files when they are there, whatever broker address it is given: TLS trusting
 * {@code public/ca.crt}, the agent role's login {@code agent/agent.nk}, and, unless a kernel key file is set, the
 * kernel's key {@code public/kernel.pub}. The default address {@code nats://localhost:4222} becomes
 * {@code tls://localhost:4222}; any other address is kept, and with the certificate authority set the connection
 * uses TLS whatever its scheme. A certificate authority or a seed given explicitly leaves the broker settings as
 * they are, and the kernel (whose replies arrive under another inbox prefix) is never changed.
 */
final class LocalSecrets {

    static final String DEFAULT_URL = "nats://localhost:4222";
    static final String TLS_URL = "tls://localhost:4222";

    private LocalSecrets() {
    }

    /**
     * {@code configured} (the value of {@code FABRIC_SECRETS}) when set, else {@code ./secrets}; empty when there is
     * no such folder.
     */
    static Optional<Path> folder(String configured) {
        Path folder = configured != null && !configured.isBlank() ? Path.of(configured.strip()) : Path.of("secrets");
        return Files.isDirectory(folder) ? Optional.of(folder) : Optional.empty();
    }

    /** The folder {@link #folder(String)} finds for this process's environment. */
    static Optional<Path> folder() {
        return folder(System.getenv("FABRIC_SECRETS"));
    }

    /**
     * {@code properties} with the broker settings and the kernel key taken from {@code folder} where the class
     * comment says so; {@code properties} itself otherwise.
     */
    static FabricProperties effective(FabricProperties properties, Optional<Path> folder) {
        FabricProperties.Nats nats = properties.nats();
        if (folder.isEmpty() || !Subjects.INBOX_AGENT.equals(nats.inboxPrefix()) || !blank(nats.caFile())
                || !blank(nats.seedFile())) {
            return properties;
        }
        Path ca = folder.get().resolve("public").resolve("ca.crt");
        Path seed = folder.get().resolve("agent").resolve("agent.nk");
        if (!Files.isRegularFile(ca) || !Files.isRegularFile(seed)) {
            return properties;
        }
        String url = blank(nats.url()) || DEFAULT_URL.equals(nats.url().strip()) ? TLS_URL : nats.url();
        FabricProperties.Nats local = new FabricProperties.Nats(url, nats.user(), nats.password(), ca.toString(),
                seed.toString(), nats.inboxPrefix());
        FabricProperties.SecuritySettings security = properties.security();
        Path kernelKey = folder.get().resolve("public").resolve("kernel.pub");
        if (security.enabled() && !security.pinsKernelKey() && Files.isRegularFile(kernelKey)) {
            security = new FabricProperties.SecuritySettings(true, security.keyFile(), security.keysDir(),
                    kernelKey.toString());
        }
        return new FabricProperties(local, properties.agent(), properties.model(), security);
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
