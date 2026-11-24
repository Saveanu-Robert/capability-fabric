package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.Subjects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/** Settings shared by every fabric component (prefix {@code fabric}). */
@ConfigurationProperties("fabric")
public record FabricProperties(@DefaultValue Nats nats, @DefaultValue Agent agent, @DefaultValue Model model,
                               @DefaultValue SecuritySettings security) {

    /**
     * Broker connection. {@code caFile} (PEM) turns on TLS for {@code tls://} URLs; {@code seedFile} holds the NKey
     * seed this component authenticates with (challenge-response, no password), so the broker can enforce the
     * subject permissions of its role. {@code inboxPrefix} is where replies to this connection's requests arrive;
     * the broker lets each role subscribe only to its own prefix ({@link Subjects#INBOX_AGENT} for agents,
     * {@link Subjects#INBOX_KERNEL} for the kernel).
     */
    public record Nats(@DefaultValue("nats://localhost:4222") String url, String user, String password, String caFile,
                       String seedFile, @DefaultValue(Subjects.INBOX_AGENT) String inboxPrefix) {

        @ConstructorBinding
        public Nats {
            inboxPrefix = inboxPrefix == null || inboxPrefix.isBlank() ? Subjects.INBOX_AGENT : inboxPrefix.strip();
        }

        /** An agent's connection settings: replies arrive under {@link Subjects#INBOX_AGENT}. */
        public Nats(String url, String user, String password, String caFile, String seedFile) {
            this(url, user, password, caFile, seedFile, Subjects.INBOX_AGENT);
        }
    }

    /**
     * Message-level security. {@code keyFile} is this process's Ed25519 identity; when empty it is
     * {@code keysDir/<provider>.key}, created on first start. The X25519 key that calls to this process are sealed
     * for lives next to it ({@link #sealPath}). {@code kernelKeyFile}, when set, holds the kernel's public key
     * (one line, base64url): the process trusts exactly that key from the start instead of the first kernel that
     * admits it.
     */
    public record SecuritySettings(@DefaultValue("true") boolean enabled, String keyFile, String keysDir,
                                   String kernelKeyFile) {

        @ConstructorBinding
        public SecuritySettings {
        }

        public SecuritySettings(boolean enabled, String keyFile, String keysDir) {
            this(enabled, keyFile, keysDir, null);
        }

        /** {@code keyFile} when configured, otherwise {@code keysDir/<provider>.key}. */
        public Path keyPath(String provider) {
            if (keyFile != null && !keyFile.isBlank()) {
                return Path.of(keyFile);
            }
            return keysDirectory().resolve(fileName(provider) + ".key");
        }

        /** {@code <keyFile>.seal} when a key file is configured, otherwise {@code keysDir/<provider>.seal}. */
        public Path sealPath(String provider) {
            if (keyFile != null && !keyFile.isBlank()) {
                return Path.of(keyFile + ".seal");
            }
            return keysDirectory().resolve(fileName(provider) + ".seal");
        }

        /** Whether a kernel key file is configured, so the kernel's key is trusted from the start. */
        public boolean pinsKernelKey() {
            return kernelKeyFile != null && !kernelKeyFile.isBlank();
        }

        private Path keysDirectory() {
            return keysDir != null && !keysDir.isBlank() ? Path.of(keysDir)
                    : Path.of(System.getProperty("user.home"), ".capability-fabric", "agents");
        }

        private static String fileName(String provider) {
            return provider.replaceAll("[^A-Za-z0-9._-]", "_");
        }
    }

    /**
     * Provider identity and registry lease. The lease ({@code ttl}) expires unless renewed every
     * {@code heartbeat}; an expired lease removes the provider from discovery.
     */
    public record Agent(String provider,
                        @DefaultValue("true") boolean enabled,
                        @DefaultValue("5s") Duration heartbeat,
                        @DefaultValue("15s") Duration ttl,
                        @DefaultValue("1500ms") Duration reactionDebounce) {
    }

    /**
     * Model settings. {@code mode=live} calls the model; {@code mode=replay} answers from recorded
     * decisions (looked up by prompt hash in the kernel) and fails if a decision is missing.
     */
    public record Model(@DefaultValue("live") String mode,
                        @DefaultValue("qwen3.5:4b") String chat,
                        @DefaultValue("0.0") double temperature,
                        @DefaultValue("42") int seed,
                        @DefaultValue("8192") int numCtx,
                        @DefaultValue("30m") String keepAlive,
                        @DefaultValue("2") int maxRepairs,
                        @DefaultValue("false") boolean thinking) {

        public boolean replay() {
            return "replay".equalsIgnoreCase(mode);
        }
    }
}
