package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.DecisionRecord;
import dev.capabilityfabric.protocol.Envelope;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Proposal;
import dev.capabilityfabric.protocol.Reply;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.SignedSpec;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.sdk.model.ReplaySource;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.Subscription;
import io.nats.client.impl.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Client side of the fabric for providers: requests to the kernel (goal context, goals, the memory of finished tasks,
 * recorded decisions, grown capabilities' specs, proposals, inbox posts, registration and the admission check) and
 * the kernel's signals. Providers talk to the kernel only: with security on, only answers the kernel signed count,
 * and a provider never calls another provider's capability (providers accept calls from the kernel only).
 *
 * <p>Every request has its own reply subscription. Other processes may publish to the shared reply prefix, so the
 * client reads that subscription until an answer that verifies arrives or the timeout ends: an answer that is not
 * signed by the kernel for this very request is ignored, never returned, and a faster forged answer cannot hide
 * the real one. When this process has a seal key, every request names it and the kernel seals its answer for it,
 * so other connections that can see the reply subject read only ciphertext.
 *
 * <p>Nothing here throws for an unavailable or misbehaving fabric: requests become error replies, and the typed
 * queries become empty results when the kernel does not answer, refuses or answers with data of the wrong shape, so
 * callers can degrade.
 */
public class FabricClient implements ReplaySource {

    private static final Logger log = LoggerFactory.getLogger(FabricClient.class);
    /** NATS status code the broker sends to the reply subject when nobody listens on the request subject. */
    private static final int NO_RESPONDERS = 503;

    private final Connection connection;
    private final Duration defaultTimeout;
    private final FabricSecurity security;

    public FabricClient(Connection connection, Duration defaultTimeout) {
        this(connection, defaultTimeout, new FabricSecurity(false, null));
    }

    /** @param defaultTimeout how long {@link #request(String, Object)} waits for an answer */
    public FabricClient(Connection connection, Duration defaultTimeout, FabricSecurity security) {
        this.connection = connection;
        this.defaultTimeout = defaultTimeout;
        this.security = security;
    }

    public FabricSecurity security() {
        return security;
    }

    public Connection connection() {
        return connection;
    }

    /**
     * Request/reply against a kernel subject. Unavailability or an unreadable answer becomes an error reply, never
     * an exception. Once the kernel's key is known (pinned or learnt at admission), only an answer it signed for
     * this request counts: the signature's scope is {@link Security#replyScope} of the subject and the nonce of
     * this request's own stamp, and its timestamp must be within {@link Security#CLOCK_SKEW} of now. With a seal key
     * the request names it ({@link Security#HEADER_REPLY_SEAL}, inside the signed scope) and the kernel's answer
     * arrives sealed for it.
     */
    public Reply request(String subject, Object payload, Duration timeout) {
        byte[] body = Json.bytes(payload);
        Headers headers = requestHeaders(subject, body);
        String scope = Security.replyScope(subject, headers.getFirst(Security.HEADER_NONCE));
        boolean checked = security.kernelKey().isPresent();
        Exchange exchange = exchange(subject, headers, body, timeout,
                m -> checked ? security.refuseUnlessKernel(m, scope) : Optional.empty(), scope);
        if (exchange.answer().isEmpty()) {
            String waited = " on " + subject + " within " + timeout.toMillis() + " ms";
            return Reply.error(exchange.ignored() == 0 ? "no answer" + waited
                    : "no answer the kernel signed" + waited + " (" + exchange.ignored() + " ignored, last: "
                    + exchange.lastRefusal() + ")");
        }
        try {
            return Reply.fromBytes(exchange.answer().get());
        } catch (RuntimeException e) {
            return Reply.error("unreadable answer on " + subject + ": " + e.getMessage());
        }
    }

    /**
     * The headers of one request: the stamp, signed for the subject plus this process's seal key when it has one,
     * and that key in {@link Security#HEADER_REPLY_SEAL}.
     */
    private Headers requestHeaders(String subject, byte[] body) {
        String seal = security.sealKeyText();
        Headers headers = security.headers(Security.requestScope(subject, seal), body);
        if (seal != null) {
            headers.put(Security.HEADER_REPLY_SEAL, seal);
        }
        return headers;
    }

    public Reply request(String subject, Object payload) {
        return request(subject, payload, defaultTimeout);
    }

    /**
     * The goal's context as the kernel minimises it for this provider; empty when the kernel does not answer in
     * time, refuses, or answers with something that is not a goal context.
     */
    public Optional<GoalContext> goalContext(String goalId) {
        return typed(request(Subjects.QUERY_CONTEXT, new KernelApi.Ref(goalId)), GoalContext.class);
    }

    /**
     * The data of a successful reply read as {@code type}; empty for a refusal, a reply without data or data that is
     * not a JSON object of that shape.
     */
    static <T> Optional<T> typed(Reply reply, Class<T> type) {
        if (!reply.ok() || reply.data() == null || !reply.data().isObject()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(reply.dataAs(type));
        } catch (RuntimeException e) {
            log.debug("ignoring an answer that is not a {}: {}", type.getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    /** One goal of the kernel's goal list. Agents are given the id, status and dates only, never the text. */
    public record ListedGoal(String id, GoalStatus status, LocalDate dueDate, Instant updatedAt) {

        /** Neither completed nor cancelled. */
        public boolean open() {
            return status != GoalStatus.CANCELLED && status != GoalStatus.COMPLETED;
        }
    }

    /** The goals the kernel lists for this process; empty when it does not answer or refuses. */
    public Optional<List<ListedGoal>> goals() {
        Reply reply = request(Subjects.QUERY_GOALS, Json.object());
        return reply.ok() ? Optional.of(listedGoals(reply.data())) : Optional.empty();
    }

    /**
     * The goals in a goal query's answer. Reads only id, status, dueDate and updatedAt, so the short form the kernel
     * gives agents and full summaries both work; entries without an id or a known status are skipped, and a date
     * that cannot be read is left out.
     */
    public static List<ListedGoal> listedGoals(JsonNode data) {
        List<ListedGoal> goals = new ArrayList<>();
        if (data == null || !data.isArray()) {
            return goals;
        }
        for (JsonNode g : data) {
            String id = g.path("id").asString(null);
            GoalStatus status;
            try {
                status = GoalStatus.valueOf(g.path("status").asString(""));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (id == null || id.isBlank()) {
                continue;
            }
            goals.add(new ListedGoal(id, status, parsed(g.path("dueDate"), LocalDate::parse),
                    parsed(g.path("updatedAt"), Instant::parse)));
        }
        return goals;
    }

    private static <T> T parsed(JsonNode value, Function<String, T> parser) {
        try {
            return value.isString() ? parser.apply(value.asString()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Finished tasks similar to {@code text} (at most {@code limit}, 5 when it is not positive, never more than 20)
     * with their estimated and real hours, as the kernel's memory keeps them ({@code query.memory}). The kernel shows
     * only what this provider was admitted to read: the goals' text only to a provider that reads
     * {@code goal.text}, the hours only to one that reads {@code task.actualHours} or {@code task.effortHours}.
     * Empty when the kernel does not answer in time, refuses or answers with something unreadable.
     */
    public Optional<KernelApi.RecallResult> recall(String text, int limit) {
        return recall(text, limit, defaultTimeout);
    }

    /** {@link #recall(String, int)} waiting at most {@code timeout} for the answer. */
    public Optional<KernelApi.RecallResult> recall(String text, int limit, Duration timeout) {
        return typed(request(Subjects.QUERY_MEMORY, new KernelApi.MemoryQuery(text, limit), timeout),
                KernelApi.RecallResult.class);
    }

    /**
     * The grown capabilities' specs ({@code query.genesis}) as the kernel signed each of them, retired ones included.
     * The kernel answers only the agent that hosts grown capabilities (admitted for {@code capability.design}).
     * Entries that are not well-formed signed specs are left out; checking each signature against the kernel's key
     * ({@link SignedSpec#verifies}) is the caller's part. Empty when the kernel does not answer in time, refuses or
     * answers with something that is not a list.
     */
    public Optional<List<SignedSpec>> genesis() {
        Reply reply = request(Subjects.QUERY_GENESIS, Json.object());
        if (!reply.ok() || reply.data() == null || !reply.data().isArray()) {
            return Optional.empty();
        }
        List<SignedSpec> specs = new ArrayList<>();
        for (JsonNode entry : reply.data()) {
            SignedSpec.parse(Json.bytes(entry)).ifPresent(specs::add);
        }
        return Optional.of(specs);
    }

    /**
     * Follows the kernel's signals for these fact types ({@code signal.<type>}) with a plain broker subscription,
     * from now on, whatever goal (or none) they concern. {@code listener} gets every signal the kernel signed for the
     * subject it arrived on (once the kernel's key is known; every readable signal while security is off), on the
     * returned dispatcher's thread. A signal sent while this process was disconnected is not delivered again. Close
     * the dispatcher with {@link Connection#closeDispatcher} to stop.
     */
    public Dispatcher signals(Collection<String> factTypes, Consumer<Envelope> listener) {
        Dispatcher dispatcher = connection.createDispatcher(message -> {
            if (security.refuseUnlessKernel(message, message.getSubject()).isPresent()) {
                return;
            }
            try {
                listener.accept(Envelope.fromBytes(message.getData()));
            } catch (RuntimeException e) {
                log.warn("ignoring signal on {}: {}", message.getSubject(), e.getMessage());
            }
        });
        factTypes.forEach(type -> dispatcher.subscribe(Subjects.signal(type)));
        return dispatcher;
    }

    public Reply propose(Proposal proposal) {
        return request(Subjects.PROPOSAL_SUBMIT, proposal);
    }

    /**
     * The model's answer the kernel recorded for this prompt hash, for replaying a run without the model. Empty when
     * the kernel does not answer in time, has no such decision (or refuses), or answers with something that is not a
     * recorded decision.
     */
    @Override
    public Optional<String> recordedResponse(String promptHash) {
        return typed(request(Subjects.QUERY_DECISION_LOOKUP, new KernelApi.Ref(promptHash)), DecisionRecord.class)
                .map(DecisionRecord::responseText);
    }

    /**
     * What one request brought back: the body of the first answer that passed the check (opened when it was sealed
     * for this process), if any, and how many answers were ignored because they did not.
     */
    record Exchange(Optional<byte[]> answer, int ignored, String lastRefusal) {
    }

    /**
     * Publishes one request with a reply subject of its own and reads the answers until {@code refuse} accepts
     * one (returns empty) and its body opens for {@code scope}, or {@code timeout} ends. Any failure (no
     * responders, a closed connection, an interrupt) ends the exchange without an answer.
     */
    private Exchange exchange(String subject, Headers headers, byte[] body, Duration timeout,
                              Function<Message, Optional<String>> refuse, String scope) {
        Subscription replies = null;
        int ignored = 0;
        String lastRefusal = null;
        try {
            String inbox = connection.createInbox();
            replies = connection.subscribe(inbox);
            connection.publish(subject, inbox, headers, body);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                long left = deadline - System.nanoTime();
                Message message = left <= 0 ? null : replies.nextMessage(Duration.ofNanos(left));
                if (message == null) {
                    return new Exchange(Optional.empty(), ignored, lastRefusal);
                }
                if (message.isStatusMessage()) {
                    if (message.getStatus() != null && message.getStatus().getCode() == NO_RESPONDERS) {
                        return new Exchange(Optional.empty(), ignored, lastRefusal);
                    }
                    continue;
                }
                Optional<String> refused = refuse.apply(message);
                if (refused.isEmpty()) {
                    try {
                        return new Exchange(Optional.of(security.openReply(message, scope)), ignored, lastRefusal);
                    } catch (IllegalArgumentException e) {
                        refused = Optional.of("cannot open the sealed answer: " + e.getMessage());
                    }
                }
                ignored++;
                lastRefusal = refused.get();
                log.debug("ignored an answer on {}: {}", subject, lastRefusal);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Exchange(Optional.empty(), ignored, lastRefusal);
        } catch (RuntimeException e) {
            log.debug("request on {} failed: {}", subject, e.getMessage());
            return new Exchange(Optional.empty(), ignored, lastRefusal);
        } finally {
            if (replies != null) {
                try {
                    replies.unsubscribe();
                } catch (RuntimeException ignoredFailure) {
                    // the connection is closing; the subscription goes with it
                }
            }
        }
    }
}
