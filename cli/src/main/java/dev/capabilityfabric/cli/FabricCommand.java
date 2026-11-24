package dev.capabilityfabric.cli;

import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.Envelope;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Reply;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.Subjects;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;
import java.util.function.Supplier;

/** {@code fabric}: talk to Capability Fabric in plain language. */
@Command(name = "fabric", mixinStandardHelpOptions = true, versionProvider = FabricCommand.Version.class,
        description = {"Talk to your to-do system in plain language.", "",
                "  fabric \"Plan my move to Berlin by the end of March\"",
                "  fabric \"what should I do today?\"",
                "  fabric \"I finished choosing the domain, it took 2 hours\"",
                "  fabric                    start a conversation (type 'exit' to leave)", ""})
public class FabricCommand implements Callable<Integer> {

    /** The version {@code --version} prints: the one in the jar's manifest, or a development build without one. */
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            String version = FabricCommand.class.getPackage().getImplementationVersion();
            return new String[]{"fabric " + (version == null ? "(development build)" : version)};
        }
    }

    /** Follow-up questions answered in one go before the person is handed back the prompt. */
    static final int MAX_CONFIRMATIONS = 5;
    /** The longest name this device gives itself when pairing or joining, or a delegate, in characters. */
    static final int MAX_NAME = 40;
    /**
     * The longest sentence the system takes at a time, in characters; the kernel and the gateway refuse longer ones.
     */
    static final int MAX_SENTENCE = 2000;

    @Parameters(arity = "0..*", paramLabel = "SENTENCE", description = "What you want, in your own words")
    List<String> words = new ArrayList<>();

    @Option(names = "--stop", description = "Emergency stop of all automatic work; works without any model")
    boolean stop;

    @Option(names = "--resume", description = "Resume after an emergency stop")
    boolean resume;

    @Option(names = "--watch", description = "Show the facts the system records, as they happen (Ctrl+C to stop)")
    boolean watch;

    @Option(names = "--no-wait", description = "Do not wait for planning to finish")
    boolean noWait;

    @Option(names = "--decide", description = "Never ask back: every change is done or rejected with a reason "
            + "(for scripts and other services)")
    boolean decide;

    @Option(names = "--ask", description = "Ask before every consequential change and whenever a reading is unsure")
    boolean ask;

    @Option(names = "--do", paramLabel = "JSON", description = "Typed actions as a JSON list, e.g. "
            + "'[{\"type\":\"SET_TASK_STATUS\",\"goal\":\"4cb429a9\",\"task\":\"T7\",\"status\":\"DONE\"}]': "
            + "each is done or rejected with a reason, without a language model")
    String typed;

    @Option(names = "--json", description = "With a sentence: print the outcome as JSON (status, what was done, what "
            + "was rejected and why); asks nothing")
    boolean json;

    @Option(names = "--nats", description = "NATS URL (env NATS_URL; default tls://localhost:4222 when the secrets "
            + "folder has a certificate authority, else nats://localhost:4222)", defaultValue = "${env:NATS_URL:-}")
    String nats;

    @Option(names = "--as", description = "Who you are when security is off (otherwise your device key decides)",
            defaultValue = "${env:FABRIC_USER:-human:me}")
    String principal;

    @Option(names = "--gateway", description = "Talk to a remote system over HTTPS (env FABRIC_GATEWAY), e.g. https://host:8443",
            defaultValue = "${env:FABRIC_GATEWAY:-}")
    String gateway;

    @Option(names = "--trust-gateway", paramLabel = "FINGERPRINT", description = "With --gateway: the fingerprint of "
            + "the gateway's certificate authority (the gateway prints it when it starts); needed on first contact "
            + "without a terminal")
    String trustGateway;

    @Option(names = "--pair", paramLabel = "CODE", description = "Pair this device with the system (the first code is "
            + "in the kernel's log; a paired device gives more: say \"I want to use my laptop too\")")
    String pairCode;

    @Option(names = "--join", paramLabel = "INVITATION", description = "Join with an invitation someone gave you")
    String invitation;

    @Option(names = "--kernel", paramLabel = "KEY_ID", description = "With --pair, --join or --grant: the kernel's key "
            + "id; its answers are refused unless that kernel signed them, and a code is sealed for that kernel only")
    String kernelId;

    @Option(names = "--name", description = "This device's name when pairing or joining (default: user and host)")
    String deviceName;

    @Option(names = "--whoami", description = "Show this device's key and what it is allowed to do")
    boolean whoami;

    @Option(names = "--delegate", paramLabel = "KEY", description = "Hand a narrower grant to another device's key "
            + "(their 'fabric --whoami' shows it); prints a grant they keep with --grant")
    String delegateTo;

    @Option(names = "--view-only", description = "With --delegate: they may only look")
    boolean viewOnly;

    @Option(names = "--until", paramLabel = "DATE", description = "With --delegate: the grant ends after this day (YYYY-MM-DD)")
    String until;

    @Option(names = "--goal", paramLabel = "GOAL", description = "With --delegate: limit to these goals (an id, an id "
            + "prefix or a title)", split = ",")
    List<String> goals;

    @Option(names = "--grant", paramLabel = "GRANT", description = "Keep a grant someone handed to this device")
    String grantToKeep;

    @Option(names = "--demo", description = "Run the guided demo: every feature, step by step, printing what is said, "
            + "what comes back and whether it is the expected outcome (meant for a fresh system: see demo.sh)")
    boolean demo;

    private final BufferedReader in;
    private final PrintStream out;

    public FabricCommand() {
        this(new BufferedReader(new InputStreamReader(System.in, inputCharset())), System.out);
    }

    FabricCommand(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    /** The console's own encoding, so typed accents and symbols arrive intact. */
    static Charset inputCharset() {
        if (System.console() != null) {
            return System.console().charset();
        }
        String stdin = System.getProperty("stdin.encoding");
        try {
            return stdin == null ? StandardCharsets.UTF_8 : Charset.forName(stdin);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * How long one sentence may take: the kernel's model calls (understanding it, then compiling a rule or designing
     * a capability) have no fixed limit and take minutes on a slow machine. When the wait ends the sentence may still
     * be carried out, and the person is told to look before saying it again. The gateway waits as long.
     */
    static final Duration TURN_TIMEOUT = Duration.ofMinutes(7);
    /**
     * How long a yes or no (to a question or an inbox message) may take: the kernel's longest path there is growing
     * a capability, which waits up to 40 s for it to join. The gateway waits as long.
     */
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(60);

    public static void main(String[] args) {
        CommandLine cli = new CommandLine(new FabricCommand());
        cli.setExecutionExceptionHandler((e, cmd, parsed) -> {
            cmd.getErr().println("error: " + message(e));
            return 1;
        });
        System.exit(cli.execute(args));
    }

    /**
     * The first pair of options that cannot be used together, or an option given without the one it belongs to;
     * empty when the command line makes sense.
     */
    Optional<String> conflict() {
        List<String> modes = new ArrayList<>();
        if (stop) {
            modes.add("--stop");
        }
        if (resume) {
            modes.add("--resume");
        }
        if (watch) {
            modes.add("--watch");
        }
        if (whoami) {
            modes.add("--whoami");
        }
        if (pairCode != null) {
            modes.add("--pair");
        }
        if (invitation != null) {
            modes.add("--join");
        }
        if (delegateTo != null) {
            modes.add("--delegate");
        }
        if (grantToKeep != null) {
            modes.add("--grant");
        }
        if (!words.isEmpty()) {
            modes.add("a sentence");
        }
        if (typed != null) {
            modes.add("--do");
        }
        if (demo) {
            modes.add("--demo");
        }
        if (modes.size() > 1) {
            return Optional.of("use either " + modes.get(0) + " or " + modes.get(1) + ", not both");
        }
        if (decide && ask) {
            return Optional.of("use either --decide or --ask, not both");
        }
        if ((decide || ask) && words.isEmpty()) {
            return Optional.of((decide ? "--decide" : "--ask") + " goes with a sentence");
        }
        if (json && words.isEmpty() && typed == null) {
            return Optional.of("--json goes with a sentence or --do");
        }
        boolean delegating = delegateTo != null;
        if (!delegating && (viewOnly || until != null || goals != null)) {
            return Optional.of((viewOnly ? "--view-only" : until != null ? "--until" : "--goal") + " goes with --delegate");
        }
        if (kernelId != null && pairCode == null && invitation == null && grantToKeep == null) {
            return Optional.of("--kernel goes with --pair, --join or --grant");
        }
        if (deviceName != null && pairCode == null && invitation == null && !delegating) {
            return Optional.of("--name goes with --pair, --join or --delegate");
        }
        if (trustGateway != null && (gateway == null || gateway.isBlank())) {
            return Optional.of("--trust-gateway goes with --gateway");
        }
        return Optional.empty();
    }

    /**
     * Runs the one thing the command line asks for: show this device, keep a grant, hand one on, pair or join, stop
     * or resume, watch facts, say one sentence, send typed actions, run the guided demo ({@link Demo}), or (with no
     * sentence) hold a conversation that starts with what waits in the inbox. A device that holds a grant and is not
     * told how to reach the system talks through the gateway it remembers ({@link #rememberedGateway}).
     */
    @Override
    public Integer call() throws Exception {
        Optional<String> conflict = conflict();
        if (conflict.isPresent()) {
            throw new Fabric.CliException(conflict.get());
        }
        Fabric.Device device = Fabric.Device.local();
        Optional<Secrets> secrets = Secrets.find();
        Optional<String> remembered = useRememberedGateway(device, secrets);
        if (whoami) {
            try {
                device.adoptKernelKey(secrets);
            } catch (Fabric.CliException e) {
                out.println("Warning: " + e.getMessage());
            }
            return whoami(device, remembered);
        }
        device.adoptKernelKey(secrets);
        if (grantToKeep != null) {
            return keepGrant(device);
        }
        if (delegateTo != null) {
            return delegate(device, secrets);
        }
        Invitation joining = invitation == null ? null : Invitation.read(invitation);
        try (Fabric fabric = open(device, secrets, joining)) {
            if (pairCode != null || joining != null) {
                int claimed = claim(fabric, joining);
                if (claimed == 0 && viaGateway()) {
                    out.println(fromNowOn(device, secrets));
                }
                return claimed;
            }
            if (stop || resume) {
                Reply reply = fabric.request(stop ? Subjects.CONTROL_STOP : Subjects.CONTROL_RESUME,
                        new KernelApi.ControlCommand(principal, stop ? "emergency stop" : null));
                out.println(!reply.ok() ? "error: " + Format.line(reply.error())
                        : stop ? "Stopped: no capability is called until you resume (fabric --resume)."
                        : "Resumed.");
                return reply.ok() ? 0 : 1;
            }
            if (watch) {
                watch(fabric.connection().orElseThrow(() -> new Fabric.CliException(
                        "--watch needs a direct connection to the broker, not the gateway")), device);
                return 0;
            }
            if (!words.isEmpty()) {
                Conversation.Reply reply = turn(fabric, String.join(" ", words));
                if (json) {
                    // A script reads one JSON object and the exit status: nothing else is printed.
                    return finished(reply);
                }
                inboxHint(fabric, reply);
                return decide ? finished(reply) : 0;
            }
            if (typed != null) {
                return typedCall(fabric, typed);
            }
            if (demo) {
                return new Demo(this, fabric, out).run();
            }
            out.println("Talk to your to-do system. Type 'exit' to leave.");
            admitWaiting(fabric);
            answerInbox(fabric);
            while (true) {
                out.print("\n> ");
                out.flush();
                String line = in.readLine();
                if (line == null || Set.of("exit", "quit", "bye").contains(line.strip().toLowerCase(Locale.ROOT))) {
                    return 0;
                }
                if (!line.isBlank()) {
                    try {
                        turn(fabric, line);
                    } catch (RuntimeException e) {
                        // One failed turn (kernel busy or restarting) does not end the conversation.
                        out.println("\nerror: " + message(e));
                    }
                }
            }
        }
    }

    /**
     * Makes this run talk through the gateway the device remembers ({@link #rememberedGateway}) when neither the
     * command line nor the environment names the way to the system, and returns it. {@code --watch} needs the
     * broker, so it keeps using it.
     */
    Optional<String> useRememberedGateway(Fabric.Device device, Optional<Secrets> secrets) {
        Optional<String> remembered = routeChosen() || watch ? Optional.empty() : rememberedGateway(device, secrets);
        remembered.ifPresent(url -> gateway = url);
        return remembered;
    }

    /** True when the command line or the environment names the way to the system: a gateway or a broker. */
    boolean routeChosen() {
        return viaGateway() || nats != null && !nats.isBlank();
    }

    /** True when this run talks to the system through a gateway. */
    private boolean viaGateway() {
        return gateway != null && !gateway.isBlank();
    }

    /**
     * The gateway a device that holds a grant (a guest's or a delegate's) talks through when nothing names the way
     * to the system: the one gateway it remembers ({@link GatewayTrust#KNOWN}), as {@code https://host:port}. Empty
     * when the device holds no grant, remembers no gateway or several, or this machine has a broker login of its own
     * (a secrets folder with the person key, which only the machine running the system has).
     */
    static Optional<String> rememberedGateway(Fabric.Device device, Optional<Secrets> secrets) {
        try {
            if (device.grant().isEmpty() || secrets.flatMap(Secrets::personSeed).isPresent()) {
                return Optional.empty();
            }
        } catch (Fabric.CliException e) {
            // A secrets folder that cannot be read is reported by the broker connection that uses it.
            return Optional.empty();
        }
        List<String> known = GatewayTrust.endpoints(device);
        return known.size() == 1 ? Optional.of("https://" + known.getFirst()) : Optional.empty();
    }

    /**
     * After pairing or joining through a gateway: how this device talks to the system from now on. A device that
     * holds a grant and remembers only this gateway uses it by itself ({@link #rememberedGateway}); any other names
     * it on every command.
     */
    String fromNowOn(Fabric.Device device, Optional<Secrets> secrets) {
        String used = GatewayTrust.endpoint(Fabric.gatewayUri(gateway));
        Optional<String> remembered = rememberedGateway(device, secrets);
        if (remembered.map(url -> GatewayTrust.endpoint(Fabric.gatewayUri(url))).filter(used::equals).isPresent()) {
            return "From now on this device talks through the gateway " + remembered.get() + " by itself, for "
                    + "example: fabric \"what is on my agenda?\"";
        }
        String url = Fabric.gatewayUri(gateway).toString();
        return "From now on, name the gateway on every command:  fabric --gateway " + url
                + " \"what is on my agenda?\"  (or set FABRIC_GATEWAY=" + url + ")";
    }

    /**
     * A broker connection that failed, said with the gateways this device used before: when the system runs on
     * another machine the broker on this one is not the way to it. Unchanged when the device remembers no gateway,
     * or when the broker was named on purpose ({@code --nats} or NATS_URL).
     */
    Fabric.CliException withGatewayHint(Fabric.CliException failure, Fabric.Device device) {
        List<String> known = GatewayTrust.endpoints(device);
        if (known.isEmpty() || nats != null && !nats.isBlank()) {
            return failure;
        }
        String urls = String.join(", ", known.stream().map(e -> "https://" + e).toList());
        return new Fabric.CliException(failure.getMessage() + ". If the system runs on another machine, this device "
                + "reached it through " + (known.size() == 1 ? "the gateway " + urls + ": fabric --gateway " + urls
                : "a gateway (" + urls + "): fabric --gateway <one of them>") + " \"...\"");
    }

    /**
     * NATS on this machine, or HTTPS through the gateway. A first contact with a gateway is pinned to the
     * invitation's certificate authority, to {@code --trust-gateway}, to the local one, or to what the person
     * confirms; see {@link GatewayTrust}. A broker that cannot be reached names the gateways this device used
     * before ({@link #withGatewayHint}).
     */
    private Fabric open(Fabric.Device device, Optional<Secrets> secrets, Invitation joining) {
        if (!viaGateway()) {
            try {
                return Fabric.connect(nats == null || nats.isBlank() ? Secrets.brokerUrl(secrets) : nats.strip(),
                        device, secrets);
            } catch (Fabric.CliException e) {
                throw withGatewayHint(e, device);
            }
        }
        List<String> pins = new ArrayList<>();
        if (joining != null && joining.caPin() != null) {
            pins.add(joining.caPin());
        }
        if (trustGateway != null && !trustGateway.isBlank()) {
            String pin = GatewayTrust.normalize(trustGateway);
            if (pin.length() < 32 || !pin.matches("[0-9a-f]+")) {
                throw new Fabric.CliException("--trust-gateway needs the fingerprint the gateway printed "
                        + "(at least 32 hex digits, colons allowed)");
            }
            pins.add(trustGateway);
        }
        URI uri = Fabric.gatewayUri(gateway);
        List<String> required = GatewayTrust.pinsForFirstContact(uri, device, pins, secrets,
                interactive() ? this::ask : null);
        return Fabric.gateway(gateway.strip(), device, required);
    }

    /** True when a person can answer questions at a terminal. */
    private static boolean interactive() {
        return System.console() != null && System.console().isTerminal();
    }

    private String ask(String question) {
        out.print(question);
        out.flush();
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * What one read of the inbox brought: the unread messages it carries, how many are unread in all (more than it
     * carries when the kernel sends only the newest), or why the inbox could not be read.
     *
     * @param failure why the read failed, or {@code null} when it worked
     * @param today   the date the questions under the messages count days from: the kernel's date the read came with
     *                ({@link Fabric#today}); {@code null} when nothing was read
     */
    record Unread(List<Inbox.Message> messages, int total, String failure, LocalDate today) {

        static Unread failed(String why) {
            return new Unread(List.of(), 0, why == null || why.isBlank() ? "no reason given" : why, null);
        }

        boolean failed() {
            return failure != null;
        }

        /**
         * The kernel's inbox data, read as of {@code today}: a list of messages, or an object with the newest
         * {@code messages} and the {@code total} unread. No data means an empty inbox.
         *
         * @throws IllegalArgumentException when the data is neither
         */
        static Unread of(JsonNode data, LocalDate today) {
            if (data == null || data.isNull()) {
                return new Unread(List.of(), 0, null, today);
            }
            JsonNode list = data.isArray() ? data : data.path("messages");
            if (!list.isArray()) {
                throw new IllegalArgumentException("the kernel's inbox is not a list of messages");
            }
            List<Inbox.Message> messages = List.of(Json.MAPPER.treeToValue(list, Inbox.Message[].class));
            JsonNode total = data.path("total");
            return new Unread(messages, total.isIntegralNumber() ? Math.max(messages.size(), total.asInt())
                    : messages.size(), null, today);
        }
    }

    /**
     * Reads the unread inbox. A failure (the kernel refused, did not answer in time, or sent something unreadable) is
     * returned, never thrown: the inbox is a convenience and must not fail the command, but it is not shown as empty
     * either. A device that holds a grant (a guest's or a delegate's) has no inbox: the kernel keeps the inbox for
     * the owner's devices, so nothing is asked and nothing is shown.
     */
    static Unread unread(Fabric fabric) {
        if (fabric.device().grant().isPresent()) {
            return new Unread(List.of(), 0, null, null);
        }
        try {
            Reply reply = fabric.request(Subjects.INTENT_INBOX, new KernelApi.Ref(null));
            return reply.ok() ? Unread.of(reply.data(), Fabric.today(reply)) : Unread.failed(reply.error());
        } catch (RuntimeException e) {
            return Unread.failed(message(e));
        }
    }

    /**
     * After one sentence that did not show the inbox: how many messages wait there, or that it could not be checked.
     */
    void inboxHint(Fabric fabric, Conversation.Reply reply) {
        if (reply.executed().stream().anyMatch(e -> e.type() == ActionType.SHOW_INBOX)) {
            return;
        }
        Unread unread = unread(fabric);
        if (unread.failed()) {
            out.println("\n(your inbox could not be checked: " + Format.line(unread.failure()) + ")");
        } else if (unread.total() > 0) {
            out.println("\n(" + unread.total() + " unread message" + (unread.total() == 1 ? "" : "s")
                    + " in your inbox: run the CLI without a sentence to go through them)");
        }
    }

    /** An admission request the kernel posted: its only suggestion is to admit the provider. */
    static boolean kernelAdmission(Inbox.Message m) {
        return m.kind() == Inbox.Kind.ADMISSION && "kernel".equals(m.provider()) && !m.suggestions().isEmpty()
                && m.suggestions().stream().allMatch(s -> s.type() == ActionType.ADMIT_PROVIDER);
    }

    /** The suggestions that move one task to a new date. */
    static List<Conversation.Action> dateMoves(List<Conversation.Action> suggestions) {
        return suggestions.stream().filter(s -> s != null && s.type() == ActionType.UPDATE_TASK && s.dueDate() != null)
                .toList();
    }

    /**
     * What one suggestion that needs a yes on its own would do, in words. A task status needs one only when it drops
     * the task, so that is what it says.
     */
    private static String drastic(Conversation.Action a) {
        return a.type() == ActionType.SET_TASK_STATUS ? "drop a task" : Format.action(a.type());
    }

    /**
     * The question under one inbox message. When a yes drops or moves work ({@link Inbox#destructive}, the test the
     * kernel applies too) it names what would change ("this would move 13 task dates and cancel a goal"). A new
     * deadline says how many of the goal's task dates move with it and by how much ({@link Format#deadlineMove}),
     * and when several task dates would move on their own every move is listed first ({@link Format#move}), so
     * nothing a yes carries out is left unseen. {@code goals} gives a goal's details by id (empty when it cannot be
     * shown), and {@code today} (the kernel's date the inbox read came with, see {@link Fabric#today}) is the earliest
     * date a moved task lands on. With {@code more} other messages follow, and "s" skips them all.
     */
    static String prompt(Inbox.Message m, boolean more, Function<String, Optional<KernelApi.GoalDetails>> goals,
                         LocalDate today) {
        String skip = more ? " / s = skip the rest" : "";
        if (kernelAdmission(m)) {
            return "    admit it? [y = admit / n = reject, you can still admit it later / Enter = later" + skip + "] ";
        }
        if (m.suggestions().isEmpty()) {
            return "    ok? [y = noted / n = dismiss / Enter = later" + skip + "] ";
        }
        String choices = "[y = yes / n = dismiss / Enter = later" + skip + "] ";
        if (!Inbox.destructive(m.suggestions())) {
            return "    do it? " + choices;
        }
        List<Conversation.Action> moves = dateMoves(m.suggestions());
        List<String> changes = new ArrayList<>();
        m.suggestions().stream().filter(s -> s != null && s.type() == ActionType.RESCHEDULE_GOAL)
                .map(s -> Format.deadlineMove(s, goal(goals, s.goal()), today)).distinct().forEach(changes::add);
        if (moves.size() > 1) {
            changes.add("move " + moves.size() + " task dates");
        }
        m.suggestions().stream().filter(s -> s != null && s.type() != null && s.type() != ActionType.RESCHEDULE_GOAL
                && Inbox.destructive(List.of(s))).map(FabricCommand::drastic).distinct().forEach(changes::add);
        StringBuilder sb = new StringBuilder("    this would ").append(String.join(" and ", changes));
        if (moves.size() > 1) {
            sb.append(":\n");
            moves.forEach(a -> sb.append("      ").append(Format.move(a, goal(goals, a.goal()))).append('\n'));
            sb.append("    Do it? ");
        } else {
            sb.append(". Do it? ");
        }
        return sb.append(choices).toString();
    }

    /** The details of the goal with this id, or empty when there is no id or the goal cannot be shown. */
    private static Optional<KernelApi.GoalDetails> goal(Function<String, Optional<KernelApi.GoalDetails>> goals,
                                                        String id) {
        return id == null || id.isBlank() ? Optional.empty() : goals.apply(id);
    }

    /** How long looking up a goal to name what a message would move may take. */
    static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    /**
     * Goal details looked up on the system once per goal; a goal that cannot be shown (not visible to this device,
     * or the kernel is slow) is remembered as empty, and what it would move is then named by task key and goal id.
     */
    static Function<String, Optional<KernelApi.GoalDetails>> lookedUpOnce(
            Function<String, Optional<KernelApi.GoalDetails>> lookup) {
        Map<String, Optional<KernelApi.GoalDetails>> seen = new HashMap<>();
        return id -> seen.computeIfAbsent(id, g -> {
            try {
                return lookup.apply(g);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    private static Optional<KernelApi.GoalDetails> goalDetails(Fabric fabric, String goal) {
        return Optional.ofNullable(fabric.data(Subjects.QUERY_GOAL, new KernelApi.Ref(goal), KernelApi.GoalDetails.class,
                LOOKUP_TIMEOUT));
    }

    /** Answers that end the walk through the inbox; the messages not reached stay unread. */
    static final Set<String> SKIP = Set.of("s", "skip");

    /**
     * The system speaks first: each unread message, with its suggestion, waits for a yes, a no, later, or "s" to
     * skip the rest. One message that cannot be answered does not stop the others. An inbox that cannot be read is
     * said to be so, and when the kernel sends only the newest messages the total is named.
     */
    void answerInbox(Fabric fabric) throws IOException, InterruptedException {
        Unread read = unread(fabric);
        if (read.failed()) {
            out.println("\n(your inbox could not be read: " + Format.line(read.failure()) + ")");
            return;
        }
        List<Inbox.Message> unread = read.messages();
        if (unread.isEmpty()) {
            return;
        }
        out.println("\nWhile you were away I noticed " + read.total() + " thing" + (read.total() == 1 ? "" : "s")
                + (read.total() > unread.size() ? "; here are the newest " + unread.size() : "") + ":");
        Function<String, Optional<KernelApi.GoalDetails>> goals = lookedUpOnce(g -> goalDetails(fabric, g));
        try (RunWatch runs = watch(fabric)) {
            List<Conversation.Executed> executed = new ArrayList<>();
            for (int i = 0; i < unread.size(); i++) {
                Inbox.Message m = unread.get(i);
                out.print("\n" + Format.message(m));
                out.print(prompt(m, i < unread.size() - 1, goals, read.today()));
                out.flush();
                String answer = in.readLine();
                String a = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
                if (SKIP.contains(a)) {
                    out.println("    The rest stay in your inbox; ask \"what is in my inbox?\" to see them again.");
                    break;
                }
                boolean yes = Set.of("y", "yes").contains(a);
                boolean no = Set.of("n", "no").contains(a);
                if (!yes && !no) {
                    continue;
                }
                try {
                    Fabric.Dated<Conversation.Reply> answered = fabric.dated(Subjects.INTENT_INBOX_ANSWER,
                            new Inbox.Answer(m.id(), yes, false, session(), principal, Inbox.receipt(m)),
                            Conversation.Reply.class, ANSWER_TIMEOUT);
                    Conversation.Reply reply = answered.data();
                    out.print(Format.reply(reply, answered.today()));
                    executed.addAll(reply.executed());
                    executed.addAll(confirmIfAsked(fabric, reply));
                    nextSentence = null; // only a sentence's own questions go on to a sentence
                } catch (RuntimeException e) {
                    out.println("    could not answer [" + Format.id(m.id()) + "]: " + message(e));
                }
            }
            if (!noWait) {
                waitForRuns(fabric, runs, executed);
            }
        }
    }

    /**
     * Sends typed actions ({@code --do}) and prints the outcome, as JSON with {@code --json}; returns the exit status
     * (see {@link #finished}).
     */
    int typedCall(Fabric fabric, String actionsJson) {
        Conversation.Action[] actions;
        try {
            actions = Json.read(actionsJson, Conversation.Action[].class);
        } catch (RuntimeException e) {
            throw new Fabric.CliException("--do needs a JSON list of actions: " + message(e));
        }
        Fabric.Dated<Conversation.Reply> answered = fabric.dated(Subjects.INTENT_EXECUTE,
                new Conversation.Command(List.of(actions), session(), principal), Conversation.Reply.class,
                TURN_TIMEOUT);
        Conversation.Reply reply = answered.data();
        out.print(json ? OUTCOME_WRITER.writeValueAsString(outcome(reply)) + "\n"
                : Format.reply(reply, answered.today()));
        return finished(reply);
    }

    /** The exit status for a script: 0 when every change was done or only data came back, 1 otherwise. */
    static int finished(Conversation.Reply reply) {
        Conversation.Outcome outcome = Conversation.outcome(reply);
        return outcome == Conversation.Outcome.COMPLETED || outcome == Conversation.Outcome.ANSWERED ? 0 : 1;
    }

    /** Writes a turn's outcome with its fields in their own order ({@code status} first), not sorted. */
    static final ObjectWriter OUTCOME_WRITER = Json.MAPPER.writer()
            .without(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /** How sentences are answered: {@code --decide}, {@code --ask}, or acting and reporting by default. */
    Conversation.Mode mode() {
        return decide || json ? Conversation.Mode.DECIDE : ask ? Conversation.Mode.CONVERSE : Conversation.Mode.ASSIST;
    }

    /**
     * A turn as one object for a script: its outcome ({@link Conversation#outcome}), the reply, what was done, what
     * was rejected with its reason code ({@link Conversation#reasonCode}), a question when one waits, and the goals,
     * goal details and agenda a read showed.
     */
    static Map<String, Object> outcome(Conversation.Reply reply) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", Conversation.outcome(reply).name());
        result.put("reply", reply.reply());
        result.put("done", reply.executed().stream().filter(Conversation.Executed::ok).map(e -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", e.type().name());
            d.put("message", e.message());
            d.put("goalId", e.goalId());
            return d;
        }).toList());
        List<Map<String, Object>> rejected = new ArrayList<>(reply.executed().stream().filter(e -> !e.ok()).map(e -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", e.type().name());
            r.put("code", Conversation.reasonCode(e));
            r.put("message", e.message());
            return r;
        }).toList());
        if (Conversation.notUnderstood(reply)) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("type", null);
            r.put("code", "NOT_UNDERSTOOD");
            r.put("message", reply.reply());
            rejected.add(r);
        }
        result.put("rejected", rejected);
        result.put("question", reply.confirmation() == null ? null : reply.confirmation().question());
        // What a read showed, for the caller to use: goals (ids, texts, dates), a goal's details and the agenda.
        result.put("goals", reply.goals());
        result.put("details", reply.details());
        result.put("agenda", reply.agenda());
        return result;
    }

    /** How a person answers a question: yes, no, or with something else, which is a sentence of its own. */
    enum Answer { YES, NO, OTHER }

    private static final Pattern YES_ANSWER = Pattern.compile("(?:y|yes|yeah|yea|yep|yup|ya|sure|ok|okay|k|go ahead"
            + "|do it|please|please do|confirm|confirmed|correct|right|that'?s right|absolutely|of course|definitely"
            + "|sounds good|go|proceed)(?: (?:please|thanks|thank you|sure|yes|do it|go ahead))*");
    private static final Pattern NO_ANSWER = Pattern.compile("(?:n|no|nope|nah|no thanks|no thank you|cancel|stop"
            + "|don'?t|do not|never ?mind|wrong|not now|skip)(?: (?:thanks|thank you|please))*");
    /** An answer to a question that was a sentence of its own; it is said once the question is answered no. */
    private String nextSentence;

    /**
     * How a person answered a question: yes ("y", "yes please", "sure", "ok", "go ahead"), no ("n", "no thanks",
     * "nope", nothing at all), or something else ("no, I meant Saturday").
     */
    static Answer answer(String line) {
        String t = line == null ? "" : line.strip().toLowerCase(Locale.ROOT).replace('’', '\'')
                .replaceAll("[.!,]+", " ").replaceAll("\\s+", " ").strip();
        return YES_ANSWER.matcher(t).matches() ? Answer.YES
                : t.isEmpty() || NO_ANSWER.matcher(t).matches() ? Answer.NO : Answer.OTHER;
    }

    /**
     * Asks every follow-up question the kernel sends, up to {@link #MAX_CONFIRMATIONS}; each yes or no is bound to
     * the question as shown by its receipt. An answer that is neither ("no, I meant Saturday") is a no, and is then
     * said as the next sentence (see {@link #turn}).
     */
    List<Conversation.Executed> confirmIfAsked(Fabric fabric, Conversation.Reply reply) throws IOException {
        List<Conversation.Executed> executed = new ArrayList<>();
        Conversation.Reply current = reply;
        for (int round = 0; current.confirmation() != null; round++) {
            Conversation.PendingConfirmation asked = current.confirmation();
            // A question about several actions comes one action per line; they stay under its first line.
            String shown = Format.indented(asked.question(), "  ");
            String question = shown.isEmpty() ? "Go ahead?" : shown;
            if (round == MAX_CONFIRMATIONS) {
                out.println("(not answered here: " + question + ")");
                break;
            }
            out.print(question + " [y/N] ");
            out.flush();
            String answer = in.readLine();
            Answer read = answer(answer);
            boolean yes = read == Answer.YES;
            if (read == Answer.OTHER) {
                // "No, I meant Saturday": a no to this question, and then a sentence of its own.
                nextSentence = answer.strip();
            }
            Fabric.Dated<Conversation.Reply> answered = fabric.dated(Subjects.INTENT_CONFIRM,
                    new Conversation.Confirm(asked.id(), yes, session(), principal, Conversation.receipt(asked)),
                    Conversation.Reply.class, ANSWER_TIMEOUT);
            current = answered.data();
            out.print(Format.reply(current, answered.today()));
            executed.addAll(current.executed());
        }
        return executed;
    }

    /**
     * Pairs this device (owner) or claims an invitation (guest); pins the kernel's key once its answer checks out. The
     * kernel's keys come first ({@link Fabric#hello}, checked against the expected or pinned kernel), and the code is
     * sealed for the kernel's sealing key and bound to this device's key ({@link Fabric#exchangeSealed}), so only that
     * kernel can read it, and only for this device. Through a gateway a code is never sent unsealed; a kernel on this
     * machine that gives no sealing key gets it as it is.
     */
    int claim(Fabric fabric, Invitation joining) {
        boolean pairing = pairCode != null;
        String not = pairing ? "Not paired" : "Not joined";
        String name = cleanName(deviceName != null ? deviceName : System.getProperty("user.name", "me") + " on " + hostName());
        Optional<String> expected = KernelId.agree(kernelId, joining == null ? null : joining.kernelKeyId());
        expected.ifPresent(fabric::expectKernel);
        boolean trustedBefore = fabric.device().kernelKey().isPresent();
        boolean viaGateway = viaGateway();
        if (viaGateway && expected.isEmpty() && !trustedBefore) {
            // Over the internet the code is sealed for the kernel whose keys come back first: only a key id given
            // here (the pairing instruction and every invitation name it) tells the real kernel from whoever answers.
            out.println(not + ": through a gateway, add the kernel's key id (--kernel <key id>); "
                    + (pairing ? "the pairing instruction names it." : "ask whoever invited you for it, or for a new "
                    + "invitation."));
            return 1;
        }
        Optional<String> sealedFor = Optional.empty();
        Fabric.Answer answer;
        KernelApi.Claim claim = new KernelApi.Claim(pairing ? pairCode.strip() : joining.claimCode(), name);
        String subject = pairing ? Subjects.SECURITY_PAIR : Subjects.SECURITY_JOIN;
        Optional<KernelApi.Hello> hello = fabric.hello();
        if (hello.isPresent()) {
            sealedFor = Optional.of(hello.get().key());
            answer = fabric.exchangeSealed(subject, claim, hello.get().sealKey());
        } else if (viaGateway) {
            out.println(not + ": the kernel did not give a key to seal the code for, so the code was not sent through "
                    + "the gateway.");
            return 1;
        } else {
            // A kernel on this machine that does not seal claims still takes them as they are.
            answer = fabric.exchange(subject, claim);
        }
        Reply reply = answer.reply();
        if (!reply.ok()) {
            out.println(not + ": " + Format.line(reply.error()));
            return 1;
        }
        String kernel = reply.data() == null ? "" : reply.data().path("kernel").asString("");
        if (answer.signer() == null || !answer.signer().equals(kernel)) {
            throw new Fabric.CliException("the answer is not signed by the kernel key it names; nothing was kept");
        }
        if (sealedFor.isPresent() && !sealedFor.get().equals(kernel)) {
            throw new Fabric.CliException("the answer comes from another kernel than the one the code was sealed for; "
                    + "nothing was kept");
        }
        fabric.device().pinKernel(kernel);
        if (pairing) {
            // The kernel knows a paired device as one of the owner's; a grant kept from an earlier invitation or
            // delegation no longer says what this device may do.
            try {
                fabric.device().forgetGrant();
            } catch (Fabric.CliException e) {
                out.println("Note: " + e.getMessage() + "; delete it by hand, this device no longer needs it.");
            }
        } else {
            String grant = reply.data().path("grant").asString("");
            if (!grant.isBlank()) {
                fabric.device().keepGrant(grant);
            }
        }
        // The kernel's answer names the device as it keeps it (a guest's device acts as the invitation's guest).
        String kept = reply.data() == null ? "" : Format.line(reply.data().path("name").asString(""));
        out.println((pairing ? "Paired" : "Joined") + " as \"" + (kept.isBlank() ? name : kept) + "\". This device's key: "
                + Security.fingerprint(fabric.device().identity().publicKeyText()) + "; the kernel's key: "
                + Security.fingerprint(kernel));
        if (!trustedBefore && expected.isEmpty()) {
            out.println("Compare the kernel's key with the one in its log (docker compose logs kernel).");
        }
        return 0;
    }

    /**
     * A name as this device sends it: one line, no '@' or slashes, at most {@link #MAX_NAME} characters. The kernel
     * keeps a device name in its own form (letters, digits, spaces, '.', '_' and '-'), which its answer to pairing
     * names.
     */
    static String cleanName(String name) {
        StringBuilder sb = new StringBuilder();
        (name == null ? "" : name).codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                sb.append(' ');
            } else if (cp == '@' || cp == '/' || cp == '\\') {
                sb.append('-');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        String clean = sb.toString().replaceAll("\\s+", " ").strip();
        if (clean.codePointCount(0, clean.length()) > MAX_NAME) {
            clean = clean.substring(0, clean.offsetByCodePoints(0, MAX_NAME)).strip();
        }
        return clean.isEmpty() ? "device" : clean;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "device";
        }
    }

    /**
     * Prints this device's key, the kernel it trusts or expects, the grant it holds, and the gateway it talks
     * through by itself ({@code remembered}, see {@link #rememberedGateway}).
     */
    int whoami(Fabric.Device device, Optional<String> remembered) {
        String key = device.identity().publicKeyText();
        out.println("This device:        " + Security.fingerprint(key));
        out.println("Its full key:       " + key);
        out.println("                    (to hand it a grant, someone runs: fabric --delegate <this key>)");
        out.println("Kept in:            " + device.home());
        device.kernelKey().ifPresentOrElse(k -> out.println("Trusts the kernel:  " + fingerprintOf(k)),
                () -> device.expectedKernel().ifPresentOrElse(
                        id -> out.println("Expects the kernel: " + id + " (checked on its first answer)"),
                        () -> out.println("Not paired yet:     fabric --pair <code>  or  fabric --join <invitation>")));
        device.grant().ifPresent(g -> out.println("Holds a grant:      " + Format.grant(g) + " (from "
                + fingerprintOf(g.issuer()) + ")"));
        remembered.ifPresent(url -> out.println("Talks through:      " + url + " (the gateway it remembers; --gateway "
                + "or --nats chooses another way)"));
        return 0;
    }

    private static String fingerprintOf(String key) {
        try {
            return Security.fingerprint(key);
        } catch (RuntimeException e) {
            return "an unreadable key";
        }
    }

    /**
     * Keeps a grant handed to this device. The kernel's key is not taken from the grant (its root may be the
     * owner's device): with {@code --kernel} the first answer must come from that kernel, which then becomes the pin.
     */
    private int keepGrant(Fabric.Device device) {
        Grant grant = Grant.decode(grantToKeep).orElseThrow(() -> new Fabric.CliException("that is not a grant"));
        if (!device.identity().publicKeyText().equals(grant.holder())) {
            throw new Fabric.CliException("that grant is for another device's key");
        }
        if (grant.until() != null && grant.until().isBefore(Instant.now())) {
            throw new Fabric.CliException("that grant has already ended");
        }
        Optional<String> expected = Optional.ofNullable(kernelId).filter(s -> !s.isBlank()).map(KernelId::normalize);
        Optional<String> pinned = device.kernelKey();
        if (expected.isPresent() && pinned.isPresent() && !KernelId.matches(expected.get(), pinned.get())) {
            throw new Fabric.CliException("this device already trusts the kernel " + fingerprintOf(pinned.get())
                    + ", not " + expected.get() + "; the grant was not kept");
        }
        device.keepGrant(grant.encode());
        out.println("Kept: " + Format.grant(grant));
        if (pinned.isEmpty() && expected.isPresent()) {
            device.expectKernel(expected.get());
            out.println("The kernel's first answer must be signed by kernel " + expected.get() + "; it is trusted from then on.");
        } else if (pinned.isEmpty()) {
            out.println("Note: the kernel's answers are not checked yet; ask for its key id and run this again with "
                    + "--kernel <id>.");
        }
        return 0;
    }

    /** Hands a narrower grant to another key, signed here; goal titles are looked up on the system. */
    private int delegate(Fabric.Device device, Optional<Secrets> secrets) {
        String holder = delegateTo.strip();
        if (!Security.isCanonicalKey(holder)) {
            throw new Fabric.CliException(
                    "that is not a device key; ask them for the full key 'fabric --whoami' shows");
        }
        if (holder.equals(device.identity().publicKeyText())) {
            throw new Fabric.CliException("that is this device's own key");
        }
        Optional<Grant> parent = device.grant();
        List<String> scope = goals == null || goals.isEmpty() ? parent.map(g -> g.rights().goals()).orElse(null)
                : resolveGoals(device, secrets, goals);
        Grant.Rights rights = delegatedRights(parent, viewOnly, scope, cleanName(deviceName == null ? "delegate" : deviceName));
        Grant grant = handOn(device.identity(), parent, holder, rights, endOf(until, LocalDate.now()));
        String kernel = device.kernelKey().flatMap(KernelId::of).map(id -> " --kernel " + id).orElse("");
        out.println("Give them this; they run:  fabric --grant " + grant.encode() + kernel);
        reachVia(device, secrets).ifPresent(out::println);
        out.println(Format.grant(grant));
        return 0;
    }

    /**
     * How the holder of a grant reaches this system from their machine: through the gateway this device uses (named
     * on the command line, or the one a device holding a grant remembers, see {@link #rememberedGateway}; on the
     * machine running the system, its gateway), with the certificate authority's fingerprint to pin on first
     * contact when it is known here (remembered for that gateway, or setup's own authority for a gateway on this
     * machine). Empty when this device knows no gateway at all.
     */
    Optional<String> reachVia(Fabric.Device device, Optional<Secrets> secrets) {
        Optional<URI> uri = gateway == null || gateway.isBlank() ? Optional.empty()
                : Optional.of(Fabric.gatewayUri(gateway));
        boolean local = uri.map(u -> GatewayTrust.loopback(u.getHost())).orElse(true);
        Optional<String> pin = uri.flatMap(u -> GatewayTrust.known(device, GatewayTrust.endpoint(u)))
                .or(() -> local ? secrets.flatMap(Secrets::authority)
                        .map(ca -> GatewayTrust.fingerprint(GatewayTrust.readCertificate(ca))) : Optional.empty());
        if (uri.isEmpty() && pin.isEmpty()) {
            return Optional.empty();
        }
        // A loopback address means nothing on their machine; the port is kept.
        String address = uri.filter(u -> !local).map(URI::toString).orElse("https://<this machine's address>:"
                + uri.map(u -> u.getPort() < 0 ? 443 : u.getPort()).orElse(8443));
        String trust = pin.map(p -> " --trust-gateway " + GatewayTrust.shortPin(p)).orElse("");
        return Optional.of("Then they talk to the system with:  fabric --gateway " + address + trust
                + " \"what is on my agenda?\"" + (pin.isEmpty() ? "\n(on first contact they compare the gateway's "
                + "fingerprint with the one it printed when it started)" : ""));
    }

    /**
     * The rights a delegate gets: the guest actions (or only looking with {@code --view-only}), never more than the
     * grant this device holds itself.
     */
    static Grant.Rights delegatedRights(Optional<Grant> parent, boolean viewOnly, List<String> goals, String name) {
        List<ActionType> wanted = viewOnly ? Grant.Rights.VIEW_ACTIONS : Grant.Rights.GUEST_ACTIONS;
        List<ActionType> allowed = parent.map(g -> g.rights().actions()).orElse(null);
        List<ActionType> actions = allowed == null ? wanted : wanted.stream().filter(allowed::contains).toList();
        if (actions.isEmpty()) {
            throw new Fabric.CliException("your own grant leaves no action to hand on");
        }
        return Grant.Rights.person("delegate", name, goals, actions);
    }

    /** Signs the grant: a root grant from an owner device, or a narrowing of the grant this device holds. */
    static Grant handOn(Security.Identity identity, Optional<Grant> parent, String holder, Grant.Rights rights,
                        Instant end) {
        if (parent.isEmpty()) {
            return Grant.issue(identity, holder, rights, end, null);
        }
        if (!rights.within(parent.get().rights())) {
            throw new Fabric.CliException("that would hand on more than your own grant allows ("
                    + Format.grant(parent.get()) + ")");
        }
        try {
            return parent.get().narrow(identity, holder, rights, end);
        } catch (IllegalArgumentException e) {
            throw new Fabric.CliException("cannot hand on that grant: " + e.getMessage());
        }
    }

    /** The end of the grant: the start of the day after {@code until}; empty input means no end. */
    static Instant endOf(String until, LocalDate today) {
        if (until == null || until.isBlank()) {
            return null;
        }
        LocalDate last;
        try {
            last = LocalDate.parse(until.strip());
        } catch (DateTimeParseException e) {
            throw new Fabric.CliException("--until needs a date like " + today.plusDays(7));
        }
        if (last.isBefore(today)) {
            throw new Fabric.CliException("--until " + last + " is in the past");
        }
        return last.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Goal ids for {@code --goal}: full ids are taken as they are; prefixes and titles are looked up on the system
     * over one connection, opened only when needed.
     */
    private List<String> resolveGoals(Fabric.Device device, Optional<Secrets> secrets, List<String> refs) {
        List<String> ids = new ArrayList<>();
        Fabric fabric = null;
        try {
            for (String ref : refs) {
                String r = ref.strip();
                if (r.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                    ids.add(r.toLowerCase(Locale.ROOT));
                    continue;
                }
                if (fabric == null) {
                    fabric = open(device, secrets, null);
                }
                Fabric f = fabric;
                ids.add(resolveGoal(r, prefix -> goalById(f, prefix), () -> allGoals(f)));
            }
        } finally {
            if (fabric != null) {
                fabric.close();
            }
        }
        return ids.stream().distinct().toList();
    }

    private static Optional<String> goalById(Fabric fabric, String prefix) {
        Reply reply = fabric.request(Subjects.QUERY_GOAL, new KernelApi.Ref(prefix));
        return reply.ok() && reply.data() != null
                ? Optional.ofNullable(reply.dataAs(KernelApi.GoalDetails.class).goal().id()) : Optional.empty();
    }

    static List<KernelApi.GoalSummary> allGoals(Fabric fabric) {
        Reply reply = fabric.request(Subjects.QUERY_GOALS, new KernelApi.Ref(null), Duration.ofSeconds(5));
        if (!reply.ok() || reply.data() == null) {
            throw new Fabric.CliException(reply.error() == null ? "no goal list" : reply.error());
        }
        return List.of(reply.dataAs(KernelApi.GoalSummary[].class));
    }

    /**
     * A goal named by an id prefix or by its title: an exact title wins over a partial one, and an open goal over a
     * completed or cancelled one; anything still ambiguous is refused with the candidates.
     */
    static String resolveGoal(String ref, Function<String, Optional<String>> byId,
                              Supplier<List<KernelApi.GoalSummary>> all) {
        if (ref.matches("[0-9a-fA-F-]{4,}")) {
            Optional<String> id = byId.apply(ref);
            if (id.isPresent()) {
                return id.get();
            }
        }
        List<KernelApi.GoalSummary> goals;
        try {
            goals = all.get();
        } catch (Fabric.CliException e) {
            throw new Fabric.CliException("no goal has the id \"" + ref + "\", and goal titles cannot be looked up "
                    + "here (" + e.getMessage() + "). Use the goal's id: fabric \"list my goals\" shows it");
        }
        String wanted = ref.toLowerCase(Locale.ROOT);
        List<KernelApi.GoalSummary> exact = goals.stream()
                .filter(g -> g.text() != null && g.text().strip().equalsIgnoreCase(ref)).toList();
        List<KernelApi.GoalSummary> matches = !exact.isEmpty() ? exact : goals.stream()
                .filter(g -> g.text() != null && g.text().toLowerCase(Locale.ROOT).contains(wanted)).toList();
        if (matches.size() > 1) {
            List<KernelApi.GoalSummary> open = matches.stream()
                    .filter(g -> g.status() != GoalStatus.COMPLETED && g.status() != GoalStatus.CANCELLED).toList();
            matches = open.isEmpty() ? matches : open;
        }
        if (matches.size() == 1) {
            return matches.getFirst().id();
        }
        if (matches.isEmpty()) {
            throw new Fabric.CliException("no goal matches \"" + ref + "\"");
        }
        throw new Fabric.CliException("several goals match \"" + ref + "\": " + String.join(", ", matches.stream()
                .map(g -> Format.id(g.id()) + " \"" + Format.cut(g.text(), 40) + "\"").toList()) + ". Use the goal's id");
    }

    /**
     * On first start the agents wait for the owner; offer to admit them all after showing who they are. Only the
     * kernel's own admission requests are offered together.
     */
    void admitWaiting(Fabric fabric) throws IOException {
        // An inbox that cannot be read here is reported by answerInbox, which reads it next.
        List<Inbox.Message> admissions = unread(fabric).messages().stream()
                .filter(FabricCommand::kernelAdmission).toList();
        if (admissions.size() < 2) {
            return;
        }
        out.println("\n" + admissions.size() + " agents ask to join:");
        admissions.forEach(m -> out.print(Format.message(m)));
        out.print("Admit all " + admissions.size() + "? [y = all / Enter = one by one] ");
        out.flush();
        String answer = in.readLine();
        if (answer == null || !Set.of("y", "yes").contains(answer.strip().toLowerCase(Locale.ROOT))) {
            return;
        }
        for (Inbox.Message m : admissions) {
            try {
                Fabric.Dated<Conversation.Reply> answered = fabric.dated(Subjects.INTENT_INBOX_ANSWER,
                        new Inbox.Answer(m.id(), true, false, session(), principal, Inbox.receipt(m)),
                        Conversation.Reply.class, ANSWER_TIMEOUT);
                out.print(Format.reply(answered.data(), answered.today()));
                confirmIfAsked(fabric, answered.data());
                nextSentence = null;
            } catch (RuntimeException e) {
                out.println("  could not admit [" + Format.id(m.id()) + "]: " + message(e));
            }
        }
    }

    /** The conversation this terminal speaks in, one per principal. */
    private String session() {
        return "cli:" + principal;
    }

    /**
     * One sentence: sends it, prints the reply, asks the follow-up questions, then waits for the plan runs it started
     * unless {@code --no-wait}. An answer to a question that was a sentence of its own ("no, I meant Saturday") is
     * said next, and its reply is the one returned.
     */
    Conversation.Reply turn(Fabric fabric, String text) throws IOException, InterruptedException {
        if (text.length() > MAX_SENTENCE) {
            throw new Fabric.CliException("that is " + text.length() + " characters; the system takes at most "
                    + MAX_SENTENCE + " at a time, so say it in shorter parts. Nothing was sent");
        }
        Conversation.Reply reply;
        try (RunWatch runs = watch(fabric)) {
            Fabric.Dated<Conversation.Reply> answered = withDots(() -> fabric.dated(Subjects.INTENT_SUBMIT,
                    new Conversation.Utterance(text, session(), principal, mode()), Conversation.Reply.class,
                    TURN_TIMEOUT));
            reply = answered.data();
            if (json) {
                // For scripts: one JSON object, nothing asked, nothing waited for.
                out.println(OUTCOME_WRITER.writeValueAsString(outcome(reply)));
                return reply;
            }
            out.print(Format.reply(reply, answered.today()));
            List<Conversation.Executed> executed = new ArrayList<>(reply.executed());
            executed.addAll(confirmIfAsked(fabric, reply));
            if (!noWait) {
                waitForRuns(fabric, runs, executed);
            }
        }
        String next = nextSentence;
        nextSentence = null;
        if (next != null) {
            out.println();
            return turn(fabric, next);
        }
        return reply;
    }

    /** Listens for run facts before the request goes out, unless nothing will be waited for. */
    RunWatch watch(Fabric fabric) {
        return RunWatch.start(noWait ? Optional.empty() : fabric.connection(), fabric.device());
    }

    /**
     * Waits until the plan runs started by this turn finish, then shows the goals they changed. A run that waits
     * for the person's approval cannot finish while this terminal waits, so it ends the wait with a hint.
     */
    void waitForRuns(Fabric fabric, RunWatch runs, List<Conversation.Executed> executed)
            throws InterruptedException {
        List<Conversation.Executed> started = executed.stream().filter(e -> e.ok() && e.runId() != null).toList();
        if (started.isEmpty()) {
            return;
        }
        if (!runs.live()) {
            out.println("\n(planning runs on the server; ask \"show me ...\" in a moment to see the result)");
            return;
        }
        List<String> runIds = started.stream().map(Conversation.Executed::runId).distinct().toList();
        started.forEach(e -> catchUp(fabric, runs, e));
        boolean settled = runs.settled(runIds) || withDots(() -> runs.await(runIds, Duration.ofMinutes(15)));
        runs.waitingForYou(runIds).forEach(w -> out.println("\n  waiting for your approval: " + Format.line(w)
                + "\n  say \"approve it\" or \"what is waiting for my approval?\""));
        if (!settled) {
            out.println("\n(planning is still running; ask \"show me ...\" later to see the result)");
        }
        started.stream().map(Conversation.Executed::goalId).filter(Objects::nonNull).distinct().forEach(goalId -> {
            try {
                Fabric.Dated<KernelApi.GoalDetails> goal = fabric.dated(Subjects.QUERY_GOAL, new KernelApi.Ref(goalId),
                        KernelApi.GoalDetails.class, Fabric.DEFAULT_TIMEOUT);
                out.print("\n" + Format.goal(goal.data(), goal.today()));
            } catch (RuntimeException e) {
                out.println("\n(cannot show goal " + Format.id(goalId) + " now: " + message(e) + ")");
            }
        });
    }

    /** What the goal already says about a run: finished, or stopped at a step that waits for approval. */
    private static void catchUp(Fabric fabric, RunWatch runs, Conversation.Executed e) {
        if (e.goalId() == null) {
            return;
        }
        try {
            KernelApi.GoalDetails details = fabric.data(Subjects.QUERY_GOAL, new KernelApi.Ref(e.goalId()),
                    KernelApi.GoalDetails.class);
            if (details.runs().stream().anyMatch(r -> e.runId().equals(r.runId()) && r.outcome() != null)) {
                runs.finished(e.runId());
            } else if (details.goal().status() == GoalStatus.AWAITING_APPROVAL) {
                runs.waiting(e.runId(), "a step of \"" + Format.cut(details.goal().text(), 60) + "\"");
            }
        } catch (RuntimeException ignored) {
            // The run's facts still arrive; the goal is only a shortcut.
        }
    }

    interface Waiting<T> {
        T call() throws InterruptedException;
    }

    /** How often a long wait prints a dot. */
    static final Duration DOT_EVERY = Duration.ofMillis(1500);
    /** The most dots on the line at once; then they are wiped and start again, so they never wrap. */
    static final int MAX_DOTS = 30;

    /** {@link #withDots(Duration, Waiting)} with a dot every {@link #DOT_EVERY}. */
    private <T> T withDots(Waiting<T> work) throws InterruptedException {
        return withDots(DOT_EVERY, work);
    }

    /**
     * Runs {@code work} while a dot is printed every {@code every}, so a long wait shows that something is
     * happening. The dots stay on one line (at most {@link #MAX_DOTS}) and are wiped with spaces when the work ends,
     * so what is printed next starts on a clean line however short it is; a wait that printed no dot prints nothing.
     * With {@code --json} nothing is printed.
     */
    <T> T withDots(Duration every, Waiting<T> work) throws InterruptedException {
        if (json) {
            return work.call(); // a script reads JSON only: no dots
        }
        int[] shown = {0};
        Thread dots = Thread.ofVirtual().start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(every);
                    if (shown[0] == MAX_DOTS) {
                        out.print(wipe(shown[0]));
                        shown[0] = 0;
                    }
                    out.print(".");
                    out.flush();
                    shown[0]++;
                }
            } catch (InterruptedException ignored) {
                // finished
            }
        });
        try {
            return work.call();
        } finally {
            dots.interrupt();
            dots.join();
            if (shown[0] > 0) {
                out.print(wipe(shown[0]));
                out.flush();
            }
        }
    }

    /** Back to the start of the line, {@code width} spaces over what was there, and back again. */
    static String wipe(int width) {
        return "\r" + " ".repeat(width) + "\r";
    }

    /**
     * Prints every fact as it is recorded (time, correlation id, fact type, who, data) until the process is stopped.
     * Only facts the kernel this device trusts signed are shown ({@link RunWatch#fromKernel}); a fact that cannot be
     * read is skipped.
     */
    private void watch(Connection connection, Fabric.Device device) throws InterruptedException {
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm:ss");
        Dispatcher dispatcher = connection.createDispatcher(message -> {
            if (!RunWatch.fromKernel(device, message.getSubject(), message.getHeaders(), message.getData())) {
                return;
            }
            Envelope fact;
            try {
                fact = Envelope.fromBytes(message.getData());
            } catch (RuntimeException e) {
                return;
            }
            out.printf("%s %-9s %-26s %-34s %s%n", LocalTime.now().format(time), Format.id(fact.correlationid()),
                    Format.line(message.getSubject().substring(Subjects.FACT_PREFIX.length())),
                    Format.cut(fact.principal(), 34), Format.cut(String.valueOf(fact.data()), 120));
        });
        dispatcher.subscribe(Subjects.ALL_FACTS);
        out.println("Watching facts; Ctrl+C to stop.");
        new CountDownLatch(1).await();
    }

    /** An exception as one plain line; its text may come from the system, so it is cleaned like any other. */
    static String message(Throwable e) {
        String text = Format.line(e.getMessage()).strip();
        return text.isEmpty() ? e.getClass().getSimpleName() : text;
    }
}
