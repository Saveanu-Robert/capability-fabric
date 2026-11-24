package dev.capabilityfabric.kernel;

import dev.capabilityfabric.algorithms.PriorityScorer;
import dev.capabilityfabric.kernel.constitution.ConstitutionService;
import dev.capabilityfabric.kernel.ecosystem.Counterfactual;
import dev.capabilityfabric.kernel.ecosystem.Reputation;
import dev.capabilityfabric.kernel.execution.CapabilityInvoker;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.kernel.security.SecurityService;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.CapabilityRequest;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Envelope;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Reply;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.SignedManifest;
import dev.capabilityfabric.protocol.SignedSpec;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.protocol.Vocabulary;
import dev.capabilityfabric.sdk.AgentRuntime;
import dev.capabilityfabric.sdk.CapabilityHandler;
import dev.capabilityfabric.sdk.CapabilityOutcome;
import dev.capabilityfabric.sdk.FabricClient;
import dev.capabilityfabric.sdk.FabricSecurity;
import dev.capabilityfabric.sdk.InvocationContext;
import dev.capabilityfabric.sdk.NatsSupport;
import dev.capabilityfabric.sdk.FabricProperties;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.JetStreamApiException;
import io.nats.client.Message;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The core claim, automated: a capability that joins a running system changes outcomes without any change
 * to existing components, and leaving degrades gracefully. Real NATS and Postgres (Testcontainers), the real
 * kernel, and deterministic fake providers (no model). The broker runs exactly as deployed: the configuration,
 * certificates and NKeys come from the setup tool, every client connects over TLS with its role's NKey (kernel,
 * agent, person, gateway), and the broker's per-role permissions apply. The kernel's key folder is read-only, as it
 * is mounted in the deployment. Run with {@code ./mvnw -Pit verify}; needs Docker.
 */
class CapabilityJoinIT {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18").asCompatibleSubstituteFor("postgres"));
    static GenericContainer<?> nats;

    static ConfigurableApplicationContext kernel;
    /** The person role's connection (CLI and owner devices). */
    static Connection connection;
    /** An agent role's connection, for what agents may do directly on the broker. */
    static Connection agentConnection;
    /** The gateway role's connection: it may publish only on the {@code gw.} subjects of its allowlist. */
    static Connection gatewayConnection;
    static FabricClient client;
    static String natsUrl;
    static Path keys;
    /** The security material setup generated: one folder per role. */
    static Path secrets;
    static final Security.Identity OWNER = Security.generate();
    /** The kernel's "today" is pinned to the real date, so every date below is relative to it. */
    static final LocalDate TODAY = LocalDate.now();
    static final Map<AgentRuntime, FabricClient> FABRICS = new ConcurrentHashMap<>();

    @BeforeAll
    static void start() throws Exception {
        keys = Files.createTempDirectory("capability-fabric-keys");
        secrets = generateSecrets();
        readOnly(secrets.resolve("kernel"));
        POSTGRES.start();
        nats = broker(secrets);
        nats.start();
        // The broker's certificate names localhost and 127.0.0.1, so the client checks the name it connects to.
        natsUrl = "tls://localhost:" + nats.getMappedPort(4222);
        kernel = new SpringApplication(KernelApplication.class).run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--fabric.nats.url=" + natsUrl,
                "--fabric.nats.ca-file=" + secrets.resolve("public/ca.crt"),
                "--fabric.nats.seed-file=" + secrets.resolve("kernel/kernel.nk"),
                "--fabric.kernel.embeddings=false",
                "--fabric.kernel.use-llm-planner=false",
                "--fabric.kernel.fixed-today=" + TODAY,
                "--fabric.kernel.registry-sweep=500ms",
                "--fabric.agent.heartbeat=1s",
                "--fabric.security.enabled=true",
                "--fabric.security.burst=1000",
                "--fabric.security.key-file=" + secrets.resolve("kernel/kernel.key"),
                "--spring.ai.ollama.base-url=http://localhost:9");
        connection = NatsSupport.connect(role("person", Subjects.INBOX_PERSON), "it-client");
        agentConnection = NatsSupport.connect(role("agent", Subjects.INBOX_AGENT), "it-agent");
        gatewayConnection = NatsSupport.connect(role("gateway", Subjects.INBOX_GATEWAY), "it-gateway");
        // The test is the owner: its device signs every request and pairs with the code the kernel offers. Through the
        // gateway a code must be sealed for the kernel, and even the right code cannot pair the first device; on the
        // machine itself it can, in plain text.
        client = new FabricClient(connection, Duration.ofSeconds(10), new FabricSecurity(true, OWNER));
        String code = security().newPairingCode();
        Reply remote = sealedThroughGateway(Security.generate(), Subjects.SECURITY_PAIR,
                new KernelApi.Claim(code, "it-remote"));
        assertThat(remote.error()).as("first pairing through the gateway").contains("the machine that runs the system");
        Reply unsealed = throughGateway(Security.generate(), Subjects.SECURITY_PAIR,
                new KernelApi.Claim(code, "it-remote"));
        assertThat(unsealed.error()).as("a plain code through the gateway").contains("must be sealed for the kernel");
        // A Fabric-Via header on the machine's own broker changes nothing: only the gateway's subjects count.
        Reply paired = withViaHeader(OWNER, Subjects.SECURITY_PAIR, new KernelApi.Claim(code, "it-owner"));
        assertThat(paired.ok()).as(paired.error()).isTrue();
        assertThat(Files.exists(secrets.resolve("kernel/kernel.key.seal"))).isTrue();
    }

    /**
     * Makes a folder and its files read-only, as the kernel's secrets are mounted in the deployment, so a kernel
     * that writes into its key folder fails here too. Not available on file systems without POSIX modes, and root
     * writes anyway.
     */
    static void readOnly(Path folder) throws IOException {
        if (!folder.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try (var files = Files.list(folder)) {
            for (Path file : files.toList()) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
            }
        }
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-x------"));
        if (!"root".equals(System.getProperty("user.name"))) {
            assertThatThrownBy(() -> Files.createFile(folder.resolve("probe")))
                    .as("nothing can be written into " + folder).isInstanceOf(IOException.class);
        }
    }

    /** Connection settings of one broker role: TLS trusting setup's authority, the role's NKey and reply prefix. */
    static FabricProperties.Nats role(String role, String inboxPrefix) {
        return new FabricProperties.Nats(natsUrl, null, null, secrets.resolve("public/ca.crt").toString(),
                secrets.resolve(role + "/" + role + ".nk").toString(), inboxPrefix);
    }

    /**
     * Runs the setup tool into a fresh folder. In a reactor build that has not packaged the setup module its classes
     * are on the test class path and it runs in this JVM; otherwise the class path holds its executable jar, which
     * runs in a JVM of its own.
     */
    static Path generateSecrets() throws Exception {
        Path dir = Files.createTempDirectory("capability-fabric-secrets");
        ClassLoader loader = CapabilityJoinIT.class.getClassLoader();
        if (loader.getResource("dev/capabilityfabric/setup/Setup.class") != null) {
            Class.forName("dev.capabilityfabric.setup.Setup").getMethod("main", String[].class)
                    .invoke(null, (Object) new String[]{dir.toString()});
            return dir;
        }
        URL packaged = loader.getResource("BOOT-INF/classes/dev/capabilityfabric/setup/Setup.class");
        assertThat(packaged).as("the setup module is a test dependency of the kernel").isNotNull();
        String location = packaged.getPath();
        Path jar = Path.of(URI.create(location.substring(0, location.indexOf("!/"))));
        String javaBinary = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(javaBinary, "-jar", jar.toString(), dir.toString()).redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(2, TimeUnit.MINUTES)).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        return dir;
    }

    /** The broker with setup's configuration: TLS, one NKey per role and each role's permissions. */
    static GenericContainer<?> broker(Path secrets) {
        GenericContainer<?> broker = new GenericContainer<>("nats:2.15.3")
                .withCommand("-c", "/security/nats/nats.conf")
                .withExposedPorts(4222).waitingFor(Wait.forLogMessage(".*Server is ready.*", 1));
        for (String file : List.of("nats.conf", "nats.crt", "nats.key")) {
            broker = broker.withCopyFileToContainer(MountableFile.forHostPath(
                    secrets.resolve("nats/" + file), 0644), "/security/nats/" + file);
        }
        return broker;
    }

    static SecurityService security() {
        return kernel.getBean(SecurityService.class);
    }

    /**
     * The owner admits a provider once it has asked (the admission ceremony, minus the inbox): its request is found
     * by its name, as the owner's question shows it, and the yes admits exactly the key and contract shown.
     */
    static void admit(String provider) throws InterruptedException {
        await(provider + " admitted", () -> security().pendingAdmission(provider)
                .map(waiting -> security().admit(waiting.keyId(), waiting.contractHash(), "human:it-owner"))
                .orElse(Optional.of(provider + " has not asked yet")), Optional::isEmpty);
    }

    @AfterAll
    static void stop() throws Exception {
        if (kernel != null) {
            kernel.close();
        }
        if (connection != null) {
            connection.close();
        }
        if (agentConnection != null) {
            agentConnection.close();
        }
        if (gatewayConnection != null) {
            gatewayConnection.close();
        }
        if (secrets != null && secrets.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(secrets.resolve("kernel"), PosixFilePermissions.fromString("rwx------"));
        }
        if (nats != null) {
            nats.stop();
        }
        POSTGRES.stop();
    }

    // ---- deterministic fake providers --------------------------------------------------------------------

    static final class Decomposer implements CapabilityHandler {
        @Override
        public CapabilityManifest manifest() {
            return CapabilityManifest.builder("goal.decompose", "1.0.0").description("Breaks a goal into tasks")
                    .kind(CapabilityManifest.Kind.AI).consumes(Vocabulary.GOAL_TEXT).requires(Vocabulary.GOAL_TEXT)
                    .produces(Vocabulary.TASKS).build();
        }

        @Override
        public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
            return CapabilityOutcome.of(new Effects(List.of(
                    new Effects.NewTask("a", "Write the long report", null, TODAY.plusDays(11), List.of(), null),
                    new Effects.NewTask("b", "Send a short email", null, TODAY.plusDays(11), List.of(), null),
                    new Effects.NewTask("c", "Publish", null, TODAY.plusDays(14), List.of("a", "b"), null)),
                    List.of(), Map.of(), null));
        }
    }

    static final class Priority implements CapabilityHandler {
        @Override
        public CapabilityManifest manifest() {
            return CapabilityManifest.builder("priority.score", "1.0.0").description("Scores task priority")
                    .consumes(Vocabulary.TASKS, Vocabulary.TASK_STATUS, Vocabulary.TASK_DUE_DATE,
                            Vocabulary.TASK_EFFORT_HOURS, Vocabulary.GOAL_TODAY)
                    .requires(Vocabulary.TASKS).produces(Vocabulary.TASK_PRIORITY, Vocabulary.TASK_RANK).build();
        }

        @Override
        public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
            var scores = new PriorityScorer().score(request.context().tasks().stream()
                    .map(t -> new PriorityScorer.Input(t, null, null)).toList(), request.context().today(), null);
            return CapabilityOutcome.of(Effects.ofUpdates(scores.stream()
                    .map(s -> new Effects.TaskUpdate(s.taskId(), null, s.priority(), s.rank(), null, null, null)).toList()));
        }
    }

    /** The newcomer. Nothing above knows it exists. */
    static final class Estimator implements CapabilityHandler {
        @Override
        public CapabilityManifest manifest() {
            return CapabilityManifest.builder("task.estimate", "1.0.0").description("Estimates effort in hours")
                    .kind(CapabilityManifest.Kind.AI).consumes(Vocabulary.TASKS, Vocabulary.TASK_TITLE)
                    .requires(Vocabulary.TASKS).produces(Vocabulary.TASK_EFFORT_HOURS).build();
        }

        @Override
        public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
            List<Effects.TaskUpdate> updates = new ArrayList<>();
            for (TaskView t : request.context().tasks()) {
                double hours = t.title().contains("long") ? 30 : t.title().contains("short") ? 0.5 : 2;
                updates.add(new Effects.TaskUpdate(t.key(), hours, null, null, null, null, null));
            }
            return CapabilityOutcome.of(Effects.ofUpdates(updates));
        }
    }

    /** A provider that always fails: the ecosystem should stop giving it work. */
    static final class FlakyEstimator implements CapabilityHandler {
        @Override
        public CapabilityManifest manifest() {
            return new Estimator().manifest();
        }

        @Override
        public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
            throw new IllegalStateException("flaky");
        }
    }

    /** Stands in for the language model: maps a few fixed phrasings to actions, deterministically. */
    static final class Interpreter implements CapabilityHandler {
        @Override
        public CapabilityManifest manifest() {
            return CapabilityManifest.builder("intent.interpret", "1.0.0").description("Understands what a person says")
                    .kind(CapabilityManifest.Kind.AI).build();
        }

        @Override
        public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
            String said = request.params().path("utterance").asString();
            Conversation.Action action = null;
            if (said.startsWith("new goal: ")) {
                action = new Conversation.Action(Conversation.ActionType.CREATE_GOAL, null, null,
                        said.substring(10), null, null, null, null, null, null);
            } else if (said.startsWith("replan ")) {
                action = new Conversation.Action(Conversation.ActionType.REFINE_GOAL, said.substring(7), null,
                        null, null, null, null, null, null, said.substring(7));
            } else if (said.startsWith("rule: ")) {
                action = new Conversation.Action(Conversation.ActionType.ADD_RULE, null, null, said.substring(6), null,
                        null, null, null, null, null);
            } else if (said.startsWith("repeal ")) {
                action = new Conversation.Action(Conversation.ActionType.REMOVE_RULE, null, null, said.substring(7), null,
                        null, null, null, null, null);
            } else if (said.equals("list goals")) {
                action = new Conversation.Action(Conversation.ActionType.LIST_GOALS, null, null, null, null, null,
                        null, null, null, null);
            } else if (said.equals("pair device")) {
                action = new Conversation.Action(Conversation.ActionType.PAIR_DEVICE, null, null, null, null, null,
                        null, null, null, null);
            } else if (said.startsWith("cancel ")) {
                action = new Conversation.Action(Conversation.ActionType.CANCEL_GOAL, said.substring(7), null,
                        null, null, null, null, null, null, said.substring(7));
            }
            return CapabilityOutcome.output(new Conversation.Interpretation("ok",
                    action == null ? List.of() : List.of(action)));
        }
    }

    /** Stands in for the rule compiler: any rule mentioning reports forbids agents to touch report goals. */
    static final class Compiler implements CapabilityHandler {
        @Override
        public CapabilityManifest manifest() {
            return CapabilityManifest.builder("policy.compile", "1.0.0").description("Compiles rules")
                    .kind(CapabilityManifest.Kind.AI).build();
        }

        @Override
        public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
            var rule = Json.object();
            rule.put("text", request.params().path("text").asString());
            rule.put("effect", "FORBID");
            rule.put("actor", "AGENTS");
            rule.putArray("topicKeywords").add("report");
            return CapabilityOutcome.output(rule);
        }
    }

    static Conversation.Reply confirm(Conversation.Reply asked) {
        Reply reply = client.request(Subjects.INTENT_CONFIRM, new Conversation.Confirm(asked.confirmation().id(), true,
                "it", "human:it", Conversation.receipt(asked.confirmation())), Duration.ofSeconds(10));
        assertThat(reply.ok()).as(reply.error()).isTrue();
        return reply.dataAs(Conversation.Reply.class);
    }

    static Reply post(AgentRuntime from, String goalId, String key, Inbox.Kind kind, Conversation.Action... suggestions) {
        return FABRICS.get(from).request(Subjects.INBOX_POST, new Inbox.Post(kind, goalId,
                "Heads up about " + key, List.of(suggestions), key, from.instanceId().replaceAll("-[^-]+$", ""),
                from.instanceId(), List.of(), null));
    }

    /** The unread inbox messages the kernel sends (the newest ones; the answer also says how many there are). */
    static List<Inbox.Message> unread() {
        return List.of(Json.MAPPER.treeToValue(client.request(Subjects.INTENT_INBOX, Json.object()).data()
                .path("messages"), Inbox.Message[].class));
    }

    static Conversation.Reply answer(Inbox.Message message, boolean accept) {
        Reply reply = client.request(Subjects.INTENT_INBOX_ANSWER, new Inbox.Answer(message.id(), accept, false, "it",
                "human:it", Inbox.receipt(message)), Duration.ofSeconds(10));
        assertThat(reply.ok()).as(reply.error()).isTrue();
        return reply.dataAs(Conversation.Reply.class);
    }

    static Conversation.Reply say(String text) {
        Reply reply = client.request(Subjects.INTENT_SUBMIT, new Conversation.Utterance(text, "it", "human:it"),
                Duration.ofSeconds(30));
        assertThat(reply.ok()).as(reply.error()).isTrue();
        return reply.dataAs(Conversation.Reply.class);
    }

    static AgentRuntime provider(String name, CapabilityHandler... handlers) throws InterruptedException {
        AgentRuntime runtime = unadmitted(name, handlers);
        admit(name);
        return runtime;
    }

    /** A provider with its own key that has asked to join but is not admitted. */
    static AgentRuntime unadmitted(String name, CapabilityHandler... handlers) {
        var properties = new FabricProperties(role("agent", Subjects.INBOX_AGENT),
                new FabricProperties.Agent(name, true, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofMillis(200)),
                new FabricProperties.Model("live", "none", 0, 42, 4096, "1m", 0, false),
                new FabricProperties.SecuritySettings(true, null, keys.toString()));
        Connection nc = NatsSupport.connect(properties.nats(), name);
        // With a seal key: the kernel seals every call to this provider (the whole experiment runs sealed). Like the
        // deployed agents, the provider trusts the kernel key setup wrote from the start, so it can verify the
        // kernel's first call even when that call arrives before the heartbeat answer that tells it it is admitted.
        var security = new FabricSecurity(true,
                Security.loadOrCreate(properties.security().keyPath(name)),
                Security.SealKey.loadOrCreate(properties.security().sealPath(name)),
                FabricSecurity.readKernelKey(secrets.resolve("public/kernel.pub")));
        FabricClient fabric = new FabricClient(nc, Duration.ofSeconds(5), security);
        AgentRuntime runtime = new AgentRuntime(nc, properties, List.of(handlers), List.of(), fabric, null, name);
        FABRICS.put(runtime, fabric);
        runtime.start();
        return runtime;
    }

    /** A request signed by a key of the test's choosing (a guest, a stranger, a delegate). */
    static Reply signed(Security.Identity who, String subject, Object payload, Grant grant) {
        return new FabricClient(connection, Duration.ofSeconds(10), new FabricSecurity(true, who) {
            @Override
            public Headers headers(String scope, byte[] body) {
                Headers h = new Headers();
                who.stamp(scope, body).headers().forEach(h::put);
                if (grant != null) {
                    h.put(Security.HEADER_GRANT, grant.encode());
                }
                return h;
            }
        }).request(subject, payload);
    }

    /**
     * A request signed by {@code who} on the machine's own broker that carries a {@code Fabric-Via} header, as an
     * older gateway set it: the kernel ignores the header.
     */
    static Reply withViaHeader(Security.Identity who, String subject, Object payload) {
        return new FabricClient(connection, Duration.ofSeconds(10), new FabricSecurity(true, who) {
            @Override
            public Headers headers(String scope, byte[] body) {
                Headers h = super.headers(scope, body);
                h.put(Security.HEADER_VIA, "gateway");
                return h;
            }
        }).request(subject, payload);
    }

    /** A request signed by {@code who} as the gateway forwards it from the network: on {@code gw.<subject>}. */
    static Reply throughGateway(Security.Identity who, String subject, Object payload) throws Exception {
        return forwarded(who, subject, Json.bytes(payload), new Headers());
    }

    /**
     * {@code body} signed by {@code signer} for the plain {@code subject}, with {@code extra} headers, published by the
     * gateway role on {@code gw.<subject>}; the answer must be signed by the kernel for the plain subject and the
     * request's nonce, as devices check it.
     */
    static Reply forwarded(Security.Identity signer, String subject, byte[] body, Headers extra) throws Exception {
        String kernelKey = security().identity().publicKeyText();
        Headers headers = new Headers();
        var stamp = signer.stamp(subject, body);
        stamp.headers().forEach(headers::put);
        extra.entrySet().forEach(e -> e.getValue().forEach(v -> headers.add(e.getKey(), v)));
        Message answer = gatewayConnection.request(Subjects.viaGateway(subject), headers, body, Duration.ofSeconds(10));
        var signed = FabricSecurity.stampOf(answer).orElseThrow();
        assertThat(signed.key()).as("signed by the kernel").isEqualTo(kernelKey);
        assertThat(signed.verifies(Security.replyScope(subject, stamp.nonce()), answer.getData()))
                .as("for the plain subject").isTrue();
        return Reply.fromBytes(answer.getData());
    }

    /** The kernel's seal key, as a device learns it through the gateway ({@code security.hello}). */
    static String kernelSealKey(Security.Identity who) throws Exception {
        KernelApi.Hello keys = throughGateway(who, Subjects.SECURITY_HELLO, Json.object()).dataAs(KernelApi.Hello.class);
        assertThat(keys.key()).as("the kernel's keys").isEqualTo(security().identity().publicKeyText());
        return keys.sealKey();
    }

    /** A claim as {@code device} seals it for the kernel: for the plain subject and the device's own signing key. */
    static byte[] sealedClaim(Security.Identity device, String subject, KernelApi.Claim claim) throws Exception {
        return Security.seal(kernelSealKey(device), Security.claimContext(subject, device.publicKeyText()),
                Json.bytes(claim));
    }

    static Headers sealedHeader() {
        Headers headers = new Headers();
        headers.put(Security.HEADER_SEALED, Security.SEAL_SCHEME);
        return headers;
    }

    /**
     * A pairing or invitation code as a current device sends it through the gateway: it asks the kernel for its keys
     * ({@code security.hello}), checks that the answer is signed by the kernel it expects, and seals the claim for the
     * kernel's seal key, so the gateway forwards only ciphertext.
     */
    static Reply sealedThroughGateway(Security.Identity who, String subject, KernelApi.Claim claim) throws Exception {
        return forwarded(who, subject, sealedClaim(who, subject, claim), sealedHeader());
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    static <T> T await(String what, Supplier<T> probe, Predicate<T> done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        T value = probe.get();
        while (!done.test(value)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out waiting for " + what + "; last value " + value);
            }
            Thread.sleep(200);
            value = probe.get();
        }
        return value;
    }

    static List<String> capabilities() {
        return kernel.getBean(CapabilityRegistry.class).views().stream()
                .map(KernelApi.CapabilityView::id).toList();
    }

    static KernelApi.GoalDetails goal(String id) {
        return client.request(Subjects.QUERY_GOAL, new KernelApi.Ref(id)).dataAs(KernelApi.GoalDetails.class);
    }

    static KernelApi.GoalDetails awaitRun(String goalId, String runId) throws InterruptedException {
        return await("run " + runId, () -> goal(goalId), d -> d.runs().stream()
                .anyMatch(r -> r.runId().equals(runId) && r.outcome() != null));
    }

    static KernelApi.RunView run(KernelApi.GoalDetails details, String runId) {
        return details.runs().stream().filter(r -> r.runId().equals(runId)).findFirst().orElseThrow();
    }

    static TaskView task(KernelApi.GoalDetails details, String key) {
        return details.tasks().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
    }

    // ---- the experiment --------------------------------------------------------------------------------

    @Test
    void newcomerChangesOutcomesWithoutChangingExistingComponents() throws Exception {
        AgentRuntime core = provider("core", new Interpreter(), new Decomposer(), new Priority(), new Compiler());
        await("core capabilities", CapabilityJoinIT::capabilities,
                c -> c.containsAll(List.of("intent.interpret", "goal.decompose", "priority.score")));
        var registry = kernel.getBean(CapabilityRegistry.class);
        assertThat(registry.sealKeyOf(core.instanceId())).as("calls to core are sealed").isPresent();
        // Agents register through the kernel only: the broker holds no registry bucket, and an agent's connection may
        // not write one anyway.
        assertThatThrownBy(() -> agentConnection.keyValue(Subjects.KV_CAPABILITIES).put("task.estimate.core-x",
                new byte[]{1})).as("agent role and a registry bucket").isInstanceOf(Exception.class);
        // Agents may listen to capability subjects and reply to the kernel. One that answers every call first with
        // something of its own changes nothing: the kernel waits for the answer signed by the called instance.
        Dispatcher racer = agentConnection.createDispatcher(m -> {
            if (m.getReplyTo() != null) {
                agentConnection.publish(m.getReplyTo(), Json.bytes(Map.of("status", "ERROR", "error", "raced")));
            }
        });
        racer.subscribe("cap.>");

        Conversation.Executed created = say("new goal: Publish the quarterly report").executed().getFirst();
        assertThat(created.ok()).isTrue();
        String goalId = created.goalId();
        var before = awaitRun(goalId, created.runId());
        assertThat(run(before, created.runId()).outcome()).isEqualTo("COMPLETED");
        assertThat(before.tasks()).hasSize(3).allSatisfy(t -> assertThat(t.effortHours()).isNull());
        assertThat(task(before, "T1").priority()).isEqualTo(task(before, "T2").priority());

        // A new provider joins the running system. Nothing else is restarted or changed.
        AgentRuntime newcomer = provider("newcomer", new Estimator());
        await("estimator discovered", CapabilityJoinIT::capabilities, c -> c.contains("task.estimate"));

        Conversation.Executed refined = say("replan " + goalId.substring(0, 8)).executed().getFirst();
        var after = awaitRun(goalId, refined.runId());
        List<String> steps = run(after, refined.runId()).steps().stream().map(KernelApi.StepView::capability).toList();
        assertThat(steps).containsExactly("task.estimate", "priority.score");
        assertThat(task(after, "T1").effortHours()).isEqualTo(30.0);
        assertThat(task(after, "T2").effortHours()).isEqualTo(0.5);
        // The existing priority provider now uses the newcomer's output: the short task outranks the long one.
        assertThat(task(after, "T2").priority()).isGreaterThan(task(after, "T1").priority());
        assertThat(task(after, "T2").rank()).isLessThan(task(after, "T1").rank());

        // Counterfactual twin: without the estimator, the short task would not outrank the long one.
        var impact = kernel.getBean(Counterfactual.class).explain(goalId, "task.estimate");
        assertThat(impact.tasksLosingFields()).isEqualTo(3);
        assertThat(impact.tasksReordered()).isGreaterThan(0);
        assertThat(impact.summary()).contains("would be ranked differently");

        // Earned reputation: a provider that keeps failing is quarantined while a healthy one exists.
        AgentRuntime flaky = provider("flaky", new FlakyEstimator());
        await("flaky estimator discovered", () -> kernel.getBean(CapabilityRegistry.class)
                .instances("task.estimate", 1).size(), n -> n == 2);
        var invoker = kernel.getBean(CapabilityInvoker.class);
        var healthy = Set.of(newcomer.instanceId());
        for (int i = 0; i < 6; i++) {
            invoker.invoke(goalId, "it", i, "task.estimate", 1, null, null, healthy);
        }
        var reputation = kernel.getBean(Reputation.class);
        assertThat(reputation.quarantined("task.estimate", "flaky")).isTrue();
        assertThat(reputation.quarantined("task.estimate", "newcomer")).isFalse();
        var afterQuarantine = invoker.invoke(goalId, "it", 9, "task.estimate", 1,
                kernel.getBean(LedgerService.class).load(goalId).orElseThrow().toContext(TODAY), null, Set.of());
        assertThat(afterQuarantine.manifest().provider()).isEqualTo("newcomer");
        flaky.stop();

        // Constitution: a rule in plain words is compiled, shown back with what it would have changed, and only
        // enforced after the person says yes. Then capability steps on matching goals are refused.
        Conversation.Reply ruleAsked = say("rule: agents must not touch my report goals");
        assertThat(ruleAsked.confirmation().question()).contains("Changes by agents on goals mentioning report are refused");
        assertThat(ruleAsked.ruleImpact().agentStepsAffected()).as("the dry run finds earlier steps").isPositive();
        assertThat(kernel.getBean(ConstitutionService.class).active()).isEmpty();
        assertThat(confirm(ruleAsked).executed().getFirst().message()).contains("is in force");
        Conversation.Executed governed = say("replan " + goalId.substring(0, 8)).executed().getFirst();
        var governedRun = run(awaitRun(goalId, governed.runId()), governed.runId());
        assertThat(governedRun.steps()).isNotEmpty().allSatisfy(st -> {
            assertThat(st.outcome()).isEqualTo("REJECTED");
            assertThat(st.detail()).contains("agents must not touch my report goals");
        });
        Conversation.Reply repeal = say("repeal report");
        assertThat(confirm(repeal).executed().getFirst().message()).contains("repealed");
        assertThat(kernel.getBean(ConstitutionService.class).active()).isEmpty();

        // Inbox: a provider speaks first with typed suggestions; the kernel keeps one copy per observation and
        // checks, when the person answers, that the facts the suggestion rests on have not changed.
        var reschedule = new Conversation.Action(Conversation.ActionType.RESCHEDULE_GOAL, goalId, null, null,
                TODAY.plusDays(45), null, null, null, "it", null);
        var moveTask = new Conversation.Action(Conversation.ActionType.UPDATE_TASK, goalId, "T2", null,
                TODAY.plusDays(34), null, null, null, "it", null);
        assertThat(post(core, goalId, "it:1", Inbox.Kind.DEADLINE_RISK, reschedule).ok()).isTrue();
        assertThat(post(core, goalId, "it:1", Inbox.Kind.DEADLINE_RISK, reschedule).data().path("duplicate").asBoolean()).isTrue();
        assertThat(post(core, goalId, "it:2", Inbox.Kind.BLOCKED_CHAIN, moveTask).ok()).isTrue();
        assertThat(post(core, goalId, "it:3", Inbox.Kind.STALE_GOAL, new Conversation.Action(Conversation.ActionType.PAUSE, goalId, null,
                null, null, null, null, null, null, null)).error()).contains("cannot be suggested");
        // A move suggested without its new date is refused before it reaches the inbox.
        assertThat(post(core, goalId, "it:6", Inbox.Kind.DEADLINE_RISK, new Conversation.Action(
                Conversation.ActionType.RESCHEDULE_GOAL, goalId, null, "back a week", null, null, null, null, "it",
                null)).error()).contains("must give the new due date");
        // Agents cannot speak as the kernel: no admission messages, no kernel dedupe keys.
        assertThat(post(core, goalId, "it:5", Inbox.Kind.ADMISSION).error()).contains("only the kernel posts");
        assertThat(post(core, goalId, "admission:" + core.instanceId(), Inbox.Kind.STALE_GOAL).error())
                .contains("reserved for the kernel");
        List<Inbox.Message> messages = unread();
        assertThat(messages).hasSize(2);
        assertThat(client.request(Subjects.INTENT_INBOX, Json.object()).today()).as("every reply carries the kernel's "
                + "date").isEqualTo(TODAY);
        Conversation.Reply accepted = answer(messages.getFirst(), true);
        assertThat(accepted.executed()).singleElement().satisfies(e -> assertThat(e.ok()).as(e.message()).isTrue());
        assertThat(goal(goalId).goal().dueDate()).isEqualTo(TODAY.plusDays(45));
        Conversation.Reply stale = answer(messages.get(1), true);
        assertThat(stale.executed()).isEmpty();
        assertThat(stale.confirmation().question()).contains("changed since");
        confirm(stale);
        assertThat(task(goal(goalId), "T2").dueDate()).isEqualTo(TODAY.plusDays(34));
        assertThat(unread()).isEmpty();

        // Two answers to the same message at the same moment: exactly one carries it out.
        var addTask = new Conversation.Action(Conversation.ActionType.ADD_TASK, goalId, null, "Print the report", null,
                null, null, null, "it", null);
        assertThat(post(core, goalId, "it:4", Inbox.Kind.STALE_GOAL, addTask).ok()).isTrue();
        Inbox.Message once = unread().getFirst();
        var first = CompletableFuture.supplyAsync(() -> answer(once, true));
        var second = CompletableFuture.supplyAsync(() -> answer(once, true));
        long carriedOut = Stream.of(first.join(), second.join())
                .filter(r -> r.executed().stream().anyMatch(e -> e.type() == Conversation.ActionType.ADD_TASK && e.ok()))
                .count();
        assertThat(carriedOut).isEqualTo(1);
        assertThat(goal(goalId).tasks()).filteredOn(t -> t.title().equals("Print the report")).hasSize(1);

        // Leaving: a provider that stops withdraws its capabilities, discovery forgets it at once, and new plans no
        // longer include it. A call made now finds no provider, which is not held against the one that left.
        racer.unsubscribe("cap.>"); // it would be a responder for the stopped instance's subject
        // Runs the answers above started may still be calling the estimator; their results count as usual.
        await("runs settled", () -> goal(goalId), d -> d.runs().stream().allMatch(r -> r.outcome() != null));
        double standing = reputation.score("task.estimate", "newcomer");
        newcomer.stop();
        assertThat(capabilities()).as("withdrawn at once").doesNotContain("task.estimate");
        var afterStop = invoker.invoke(goalId, "it", 10, "task.estimate", 1, null, null, Set.of());
        assertThat(afterStop.ok()).isFalse();
        assertThat(reputation.score("task.estimate", "newcomer")).isEqualTo(standing);
        Conversation.Executed lunch = say("new goal: Plan the team lunch").executed().getFirst();
        var lunchDetails = awaitRun(lunch.goalId(), lunch.runId());
        assertThat(run(lunchDetails, lunch.runId()).steps()).extracting(KernelApi.StepView::capability)
                .containsExactly("goal.decompose", "priority.score");

        // Destructive actions wait for the person's confirmation.
        Conversation.Reply cancel = say("cancel " + lunch.goalId().substring(0, 8));
        assertThat(cancel.executed()).isEmpty();
        assertThat(cancel.confirmation().question()).contains("Plan the team lunch");
        assertThat(confirm(cancel).executed().getFirst().ok()).isTrue();
        assertThat(goal(lunch.goalId()).goal().status()).isEqualTo(GoalStatus.CANCELLED);

        // The emergency stop works without any model and cancels new runs until resumed.
        assertThat(client.request(Subjects.CONTROL_STOP, Json.object().put("principal", "human:it")).ok()).isTrue();
        Conversation.Executed paused = say("new goal: Water the plants").executed().getFirst();
        var pausedDetails = awaitRun(paused.goalId(), paused.runId());
        assertThat(run(pausedDetails, paused.runId()).outcome()).isEqualTo("CANCELLED");
        assertThat(client.request(Subjects.CONTROL_RESUME, Json.object().put("principal", "human:it")).ok()).isTrue();

        // The stored snapshot always equals the state folded from the fact history.
        assertThat(kernel.getBean(LedgerService.class).rebuild(goalId).contentHash())
                .isEqualTo(kernel.getBean(LedgerService.class).load(goalId).orElseThrow().contentHash());
        // ---- security: every message signed, every key known, authority is what a key holds ----------------
        // Unsigned, replayed and unknown requests never reach the conversation.
        Reply unsigned = new FabricClient(connection, Duration.ofSeconds(10)).request(Subjects.INTENT_SUBMIT,
                new Conversation.Utterance("list goals", "x", "human:mallory"));
        assertThat(unsigned.error()).contains("unsigned");
        byte[] body = Json.bytes(new Conversation.Utterance("list goals", "x", null));
        var stamp = OWNER.stamp(Subjects.INTENT_SUBMIT, body);
        Headers headers = new Headers();
        stamp.headers().forEach(headers::put);
        assertThat(Reply.fromBytes(connection.request(Subjects.INTENT_SUBMIT, headers, body, Duration.ofSeconds(10))
                .getData()).ok()).isTrue();
        assertThat(Reply.fromBytes(connection.request(Subjects.INTENT_SUBMIT, headers, body, Duration.ofSeconds(10))
                .getData()).error()).as("the same signed message again").contains("replay");
        var stranger = Security.generate();
        assertThat(signed(stranger, Subjects.INTENT_SUBMIT, new Conversation.Utterance("list goals", "x", null), null)
                .error()).contains("not paired");
        assertThat(signed(stranger, Subjects.CONTROL_STOP, Json.object(), null).error()).contains("not paired");

        // An admitted agent cannot take over another instance's id with its own key, even for a capability its
        // grant covers, nor withdraw another instance's entries.
        String coreInstance = core.instanceId();
        String coreKey = registry.keyOf(coreInstance).orElseThrow();
        AgentRuntime rival = provider("rival", new Estimator());
        await("rival listed", () -> registry.instances("task.estimate", 1).size(), n -> n >= 1);
        var rivalIdentity = Security.loadOrCreate(keys.resolve("rival.key"));
        FabricClient rivalFabric = FABRICS.get(rival);
        String rivalGrant = rivalFabric.security().grant().map(Grant::encode).orElse(null);
        // A valid seal key, so the entry is refused for the instance id it takes and not for a missing seal key.
        String rivalSealKey = Security.SealKey.generate().publicKeyText();
        var takeover = new Estimator().manifest().withInstance("rival", coreInstance, Instant.now(), 15);
        Reply takeoverReply = rivalFabric.request(Subjects.REGISTRY_HEARTBEAT, new KernelApi.Registration(List.of(
                SignedManifest.sign(takeover, rivalIdentity, rivalSealKey, rivalGrant))));
        assertThat(takeoverReply.ok()).as(takeoverReply.error()).isTrue();
        assertThat(takeoverReply.data().path("listed").asInt()).isZero();
        assertThat(takeoverReply.data().path("refused").toString()).contains("must start with");
        assertThat(registry.keyOf(coreInstance)).hasValue(coreKey);
        assertThat(registry.instance("task.estimate", coreInstance)).isEmpty();
        var coreEntry = new Priority().manifest().withInstance("core", coreInstance, Instant.now(), 3);
        Reply notItsOwn = rivalFabric.request(Subjects.REGISTRY_WITHDRAW, new KernelApi.Registration(List.of(
                SignedManifest.sign(coreEntry, rivalIdentity, rivalSealKey, rivalGrant))));
        assertThat(notItsOwn.data().path("withdrawn").isEmpty()).as(notItsOwn.toString()).isTrue();
        assertThat(registry.instance("priority.score", coreInstance)).hasValueSatisfying(m ->
                assertThat(m.provider()).isEqualTo("core"));
        // A capability withdrawn while its provider keeps running leaves at once.
        assertThat(rival.unregister("task.estimate")).isTrue();
        assertThat(registry.instances("task.estimate", 1)).noneMatch(m -> m.provider().equals("rival"));
        rival.stop();

        // The kernel's memory answers admitted agents only, as a kernel query: every admitted agent gets the titles
        // of finished tasks (as every goal context carries titles), the hours only when it reads them.
        AgentRuntime reader = provider("reader", new Estimator());
        assertThat(FABRICS.get(reader).recall("report", 3)).as("an agent that reads task titles").isPresent();
        reader.stop();
        assertThat(FABRICS.get(core).recall("report", 3)).as("core reads titles like every admitted agent")
                .hasValueSatisfying(r -> assertThat(r.items()).allSatisfy(item -> {
                    assertThat(item.estimatedHours()).isNull();
                    assertThat(item.actualHours()).isNull();
                }));
        Reply strangerRecall = new FabricClient(agentConnection, Duration.ofSeconds(5),
                new FabricSecurity(true, stranger)).request(Subjects.QUERY_MEMORY,
                new KernelApi.MemoryQuery("report", 3));
        assertThat(strangerRecall.error()).as("an agent's connection with a key never admitted").contains("not paired");

        // A rogue agent offering an existing capability with its own key is not listed until the owner admits it.
        int scorers = kernel.getBean(CapabilityRegistry.class).instances("priority.score", 1).size();
        AgentRuntime rogue = unadmitted("rogue", new Priority());
        await("rogue asks to join", () -> unread().stream().anyMatch(m -> m.kind() == Inbox.Kind.ADMISSION
                && m.text().contains("rogue")), b -> b);
        Thread.sleep(1500);
        assertThat(kernel.getBean(CapabilityRegistry.class).instances("priority.score", 1)).hasSize(scorers);
        rogue.stop();

        // A further device pairs through the gateway with a sealed code, and the owner is told which device it was. A
        // relay that signs the same sealed bytes with its own key cannot open them, so it cannot pair in the phone's
        // place, and the code stays good for the phone.
        var phone = Security.generate();
        byte[] phoneClaim = sealedClaim(phone, Subjects.SECURITY_PAIR, new KernelApi.Claim(security().newPairingCode(),
                "it-phone"));
        var relay = Security.generate();
        Reply relayed = forwarded(relay, Subjects.SECURITY_PAIR, phoneClaim, sealedHeader());
        assertThat(relayed.error()).as("re-signed by a relay").contains("not sealed for this kernel");
        assertThat(security().identify(relay.publicKeyText(), null).role()).isEqualTo(SecurityService.Role.UNKNOWN);
        Reply phonePaired = forwarded(phone, Subjects.SECURITY_PAIR, phoneClaim, sealedHeader());
        assertThat(phonePaired.ok()).as(phonePaired.error()).isTrue();
        assertThat(phonePaired.data().path("kernel").asString()).isEqualTo(security().identity().publicKeyText());
        assertThat(phonePaired.data().path("name").asString()).as("the name the kernel keeps").isEqualTo("it-phone");
        assertThat(unread()).anySatisfy(m -> {
            assertThat(m.kind()).isEqualTo(Inbox.Kind.SECURITY);
            assertThat(m.text()).contains("\"it-phone\"")
                    .contains(Security.fingerprint(phone.publicKeyText()))
                    .contains("If this was not you, say: revoke it-phone");
        });

        // A guest invited to one goal sees and changes only that goal; a wrong receipt is refused.
        String invitation = security().invite("Ana", goalId, false, null, "human:it-owner");
        var ana = Security.generate();
        Reply joined = signed(ana, Subjects.SECURITY_JOIN, new KernelApi.Claim(invitation, "ana-phone"), null);
        assertThat(joined.ok()).as(joined.error()).isTrue();
        assertThat(joined.data().path("name").asString()).as("the guest the device acts as").isEqualTo("Ana");
        assertThat(signed(ana, Subjects.SECURITY_JOIN, new KernelApi.Claim(invitation, "again"), null).error())
                .as("an invitation works once").contains("already used");
        var bo = Security.generate();
        Reply boJoined = sealedThroughGateway(bo, Subjects.SECURITY_JOIN, new KernelApi.Claim(security().invite("Bo",
                goalId, true, null, "human:it-owner"), "bo-tablet"));
        assertThat(boJoined.ok()).as(boJoined.error()).isTrue();
        assertThat(boJoined.data().path("grant").asString()).isNotEmpty();
        assertThat(unread()).anySatisfy(m -> assertThat(m.text()).contains("\"bo-tablet\"")
                .contains("say: revoke Bo"));
        var anaGoals = signed(ana, Subjects.INTENT_SUBMIT, new Conversation.Utterance("list goals", "a", null), null)
                .dataAs(Conversation.Reply.class);
        assertThat(anaGoals.goals()).extracting(KernelApi.GoalSummary::id).containsExactly(goalId);
        var anaCancel = signed(ana, Subjects.INTENT_SUBMIT, new Conversation.Utterance("cancel " + goalId.substring(0, 8),
                "a", null), null).dataAs(Conversation.Reply.class);
        assertThat(anaCancel.executed()).singleElement().satisfies(e -> assertThat(e.message()).contains("not part of"));
        assertThat(signed(ana, Subjects.INTENT_INBOX, Json.object(), null).error()).contains("guests may not do this");
        var anaPairs = signed(ana, Subjects.INTENT_SUBMIT, new Conversation.Utterance("pair device", "a", null), null)
                .dataAs(Conversation.Reply.class);
        assertThat(anaPairs.executed()).as("a guest cannot pair devices").singleElement().satisfies(e -> {
            assertThat(e.ok()).isFalse();
            assertThat(e.message()).doesNotContainPattern("\\d{4}-\\d{4}");
        });

        Conversation.Reply ownerCancel = say("cancel " + goalId.substring(0, 8));
        Reply forged = client.request(Subjects.INTENT_CONFIRM, new Conversation.Confirm(ownerCancel.confirmation().id(),
                true, "it", null, "not-the-receipt"), Duration.ofSeconds(10));
        assertThat(forged.dataAs(Conversation.Reply.class).reply()).contains("does not match");
        assertThat(goal(goalId).goal().status()).isNotEqualTo(GoalStatus.CANCELLED);

        // Ana hands a narrower, view-only grant to a friend without asking the kernel; revoking Ana voids it.
        var friend = Security.generate();
        var anaGrant = security().grantOf(ana.publicKeyText()).orElseThrow();
        var viewOnly = anaGrant.narrow(ana, friend.publicKeyText(), Grant.Rights.person("delegate",
                "Ana's friend", List.of(goalId), List.of(Conversation.ActionType.LIST_GOALS)), null);
        var friendGoals = signed(friend, Subjects.INTENT_SUBMIT, new Conversation.Utterance("list goals", "f", null),
                viewOnly).dataAs(Conversation.Reply.class);
        assertThat(friendGoals.goals()).extracting(KernelApi.GoalSummary::id).containsExactly(goalId);
        // Signed by hand: Grant.narrow itself refuses to widen.
        var wider = Grant.issue(ana, friend.publicKeyText(), Grant.Rights.person(
                "delegate", "x", null, List.of(Conversation.ActionType.CANCEL_GOAL)), anaGrant.until(), anaGrant);
        assertThat(signed(friend, Subjects.INTENT_SUBMIT, new Conversation.Utterance("list goals", "f", null), wider)
                .error()).as("a grant wider than its parent").contains("claims more than the grant it came from");
        assertThat(security().revoke("Ana", "human:it-owner")).isEmpty();
        assertThat(signed(friend, Subjects.INTENT_SUBMIT, new Conversation.Utterance("list goals", "f", null), viewOnly)
                .error()).as("revoking Ana voids what she handed on").contains("your access was revoked");
        assertThat(signed(ana, Subjects.INTENT_SUBMIT, new Conversation.Utterance("list goals", "a", null), null)
                .error()).contains("revoked");

        // The broker enforces the roles: a person's connection cannot touch the registry, an agent's cannot speak
        // for a person, and an agent cannot read the replies the kernel receives.
        assertThatThrownBy(() -> connection.keyValue(Subjects.KV_CAPABILITIES).put("x.y", new byte[]{1}))
                .as("person role and the registry").isInstanceOf(Exception.class);
        Reply asAgent = new FabricClient(agentConnection, Duration.ofSeconds(2), new FabricSecurity(true, OWNER))
                .request(Subjects.INTENT_SUBMIT, new Conversation.Utterance("list goals", "x", null));
        assertThat(asAgent.ok()).as("agent role and the person's subjects").isFalse();
        Subscription eavesdrop = agentConnection.subscribe(Subjects.INBOX_KERNEL + ".>");
        agentConnection.flush(Duration.ofSeconds(2));
        say("replan " + goalId.substring(0, 8));
        assertThat(eavesdrop.nextMessage(Duration.ofSeconds(1))).as("the kernel's replies").isNull();
        agentsReadSignalsAndSealedRepliesOnly(core, goalId);
        theForgeReadsGrownSpecsFromTheKernel(core);
        core.stop();
    }

    /** Stands in for the forge's designer: admitting it makes its agent the host of grown capabilities. */
    static final class Designer implements CapabilityHandler {
        @Override
        public CapabilityManifest manifest() {
            return CapabilityManifest.builder("capability.design", "1.0.0").description("Designs capabilities")
                    .kind(CapabilityManifest.Kind.AI).build();
        }

        @Override
        public CapabilityOutcome handle(CapabilityRequest request, InvocationContext context) {
            return CapabilityOutcome.output(Json.object());
        }
    }

    /**
     * Agents have no JetStream rights, so the grown capabilities' specs reach their host through the kernel: a signed
     * request on {@code query.genesis}, answered only to the agent admitted for {@code capability.design}, with
     * every spec signed by the kernel and without the sentence that asked for it.
     */
    static void theForgeReadsGrownSpecsFromTheKernel(AgentRuntime core) throws Exception {
        var kernelIdentity = kernel.getBean(FabricSecurity.class).identity();
        var spec = new Ecosystem.CapabilitySpec("weather.risk", "1.0.0", "Weather risk",
                "Flags outdoor tasks", Ecosystem.Scope.TASK, Json.toTree(Map.of("type", "string")),
                "Say whether rain matters", List.of(Vocabulary.TASK_TITLE), List.of(),
                Ecosystem.SpecStatus.PROBATION, "my garden tasks keep getting rained out",
                "human:it-owner", Instant.now());
        kernel.getBean(Connection.class).keyValue(Subjects.KV_GENESIS).put(spec.id(),
                SignedSpec.sign(spec, kernelIdentity).bytes());
        AgentRuntime forge = provider("forge", new Designer());

        var specs = FABRICS.get(forge).genesis();

        assertThat(specs).hasValueSatisfying(list -> assertThat(list).anySatisfy(s -> {
            assertThat(s.spec().id()).isEqualTo("weather.risk");
            assertThat(s.verifies(kernelIdentity.publicKeyText())).isTrue();
            assertThat(s.spec().need()).isNull();
            assertThat(s.spec().createdBy()).isNull();
        }));
        assertThat(FABRICS.get(core).genesis()).as("an agent that hosts no grown capabilities").isEmpty();
        assertThat(FABRICS.get(core).request(Subjects.QUERY_GENESIS, Json.object()).error())
                .contains("only the agent that hosts grown capabilities");
        assertThatThrownBy(() -> agentConnection.keyValue(Subjects.KV_GENESIS).get(spec.id()))
                .as("the genesis bucket is not for agents").isInstanceOf(Exception.class);
        forge.stop();
    }

    /**
     * What an agent's broker connection can and cannot see or do: no facts but the kernel's signals (thin and
     * signed), no reply meant for another agent in clear, no reply from the kernel written where a requester names,
     * and no message into the kernel's reply inbox that answers nothing.
     */
    static void agentsReadSignalsAndSealedRepliesOnly(AgentRuntime core, String goalId) throws Exception {
        Connection kernelConnection = kernel.getBean(Connection.class);
        String kernelKey = security().identity().publicKeyText();

        // Facts are not for agents; signals are, through a plain subscription as reactive agents follow them. Agents
        // have no JetStream rights at all: no stream, consumer or bucket API.
        Connection other = NatsSupport.connect(role("agent", Subjects.INBOX_AGENT), "it-other-agent");
        try {
            Subscription facts = other.subscribe(Subjects.ALL_FACTS);
            Subscription signals = other.subscribe(Subjects.ALL_SIGNALS);
            assertThatThrownBy(() -> kernelConnection.jetStreamManagement().getStreamInfo(Subjects.STREAM_SIGNALS))
                    .as("no stream keeps signals").isInstanceOf(JetStreamApiException.class);
            assertThatThrownBy(() -> other.getStreamContext(Subjects.STREAM_FACTS))
                    .as("the fact stream's API").isInstanceOf(Exception.class);
            assertThatThrownBy(() -> other.jetStreamManagement().getStreamNames())
                    .as("the JetStream API").isInstanceOf(Exception.class);
            other.flush(Duration.ofSeconds(2));
            say("replan " + goalId.substring(0, 8));
            Message signal = signals.nextMessage(Duration.ofSeconds(10));
            assertThat(signal).as("a signal").isNotNull();
            assertThat(signal.getSubject()).startsWith(Subjects.SIGNAL_PREFIX);
            assertThat(signal.getReplyTo()).as("nothing to answer in the kernel's name").isNull();
            assertThat(FabricSecurity.stampOf(signal).orElseThrow().verifies(signal.getSubject(), signal.getData()))
                    .as("signed by the kernel for its subject").isTrue();
            assertThat(FabricSecurity.stampOf(signal).orElseThrow().key()).isEqualTo(kernelKey);
            var envelope = Envelope.fromBytes(signal.getData());
            assertThat(envelope.data().size()).as("only ids").isEqualTo(2);
            assertThat(envelope.data().has("factId")).isTrue();
            assertThat(facts.nextMessage(Duration.ofSeconds(1))).as("no fact reaches an agent").isNull();
            signals.unsubscribe();

            // Another agent sees the reply to a request of core's, but only sealed for core.
            Subscription replies = other.subscribe(Subjects.INBOX_AGENT + ".>");
            other.flush(Duration.ofSeconds(2));
            assertThat(FABRICS.get(core).goalContext(goalId)).as("core reads its context").isPresent();
            List<Message> seen = new ArrayList<>();
            for (Message m = replies.nextMessage(Duration.ofSeconds(2)); m != null;
                 m = replies.nextMessage(Duration.ofMillis(500))) {
                seen.add(m);
            }
            // Every answer of the kernel on the shared prefix (heartbeats, admission answers) is sealed for its agent.
            assertThat(seen).as("the reply passed by").anySatisfy(m -> assertThat(m.getHeaders() == null ? null
                    : m.getHeaders().getFirst(Security.HEADER_SEALED)).isEqualTo(Security.SEAL_SCHEME));
            assertThat(seen).noneSatisfy(m -> assertThat(new String(m.getData(), StandardCharsets.UTF_8))
                    .contains("quarterly"));

            // A request naming a bucket as its reply subject is dropped: nothing is written there.
            byte[] body = Json.bytes(Json.object());
            Headers headers = FABRICS.get(core).security().headers(Subjects.QUERY_GOALS, body);
            other.publish(Subjects.QUERY_GOALS, "$KV." + Subjects.KV_GENESIS + ".it-probe", headers, body);
            other.flush(Duration.ofSeconds(2));
            Thread.sleep(1500);
            assertThat(kernelConnection.keyValue(Subjects.KV_GENESIS).get("it-probe")).as("the genesis bucket").isNull();

            // A request naming another role's reply inbox is dropped: the kernel answers an agent on agents' inboxes
            // only, so no kernel-signed answer lands where the kernel or a person waits for one.
            Subscription foreign = kernelConnection.subscribe(Subjects.INBOX_KERNEL + ".it-foreign");
            kernelConnection.flush(Duration.ofSeconds(2));
            Headers goalsHeaders = FABRICS.get(core).security().headers(Subjects.QUERY_GOALS, body);
            other.publish(Subjects.QUERY_GOALS, Subjects.INBOX_KERNEL + ".it-foreign", goalsHeaders, body);
            other.flush(Duration.ofSeconds(2));
            assertThat(foreign.nextMessage(Duration.ofSeconds(2))).as("a reply in the kernel's inbox").isNull();
            foreign.unsubscribe();

            // Agents answer only what they were asked: nothing reaches the kernel's reply inbox unprompted.
            Subscription kernelInbox = kernelConnection.subscribe(Subjects.INBOX_KERNEL + ".it-probe");
            kernelConnection.flush(Duration.ofSeconds(2));
            other.publish(Subjects.INBOX_KERNEL + ".it-probe", body);
            other.flush(Duration.ofSeconds(2));
            assertThat(kernelInbox.nextMessage(Duration.ofSeconds(1))).as("an unprompted reply").isNull();
            kernelInbox.unsubscribe();
        } finally {
            other.close();
        }
    }
}
