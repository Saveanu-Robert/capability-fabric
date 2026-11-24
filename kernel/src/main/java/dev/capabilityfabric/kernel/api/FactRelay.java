package dev.capabilityfabric.kernel.api;

import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.protocol.Envelope;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.sdk.FabricSecurity;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.impl.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Transactional outbox: facts are committed to Postgres first, then published to the {@code FACTS} stream
 * with the fact id as message id, and marked as published once the batch is through. A crash between publishing
 * and marking sends the batch again on the next run: a fact is never lost, and the stream keeps one copy of a fact
 * sent again within its duplicate window (an hour, see {@code FabricSetup.facts()}), since it drops a message id it
 * has already stored in that window. Every fact is followed by its signal, a plain broker message on
 * {@code signal.<type>}: the fact's id, type, time and goal without its data, which is all agents may read (they ask
 * the kernel for the context they may see). A signal carries no reply subject, so nobody who hears it can answer in
 * the kernel's name. A signal that cannot be sent is dropped, never retried, and never holds back the facts; a batch
 * sent again sends its signals again, and the broker does not deduplicate plain messages, so a listener can hear the
 * same signal twice (with the same fact id).
 * <p>
 * A fact whose headers and body together exceed the broker's message size goes to the stream in its
 * {@link #reduced} form (same id, type, time and goal, data saying it was truncated), followed by its signal as
 * usual; the whole fact stays in the ledger, where the kernel reads it. A fact the client library refuses as
 * malformed is treated the same way, and one whose reduced form is refused too is skipped with an error in the log.
 * Either way the facts after it still go out.
 */
@Component
public class FactRelay {

    private static final Logger log = LoggerFactory.getLogger(FactRelay.class);

    /** The CloudEvents source of every fact and signal the kernel publishes. */
    static final String SOURCE = "capability-fabric/kernel";

    private final LedgerService ledger;
    private final Connection connection;
    private final JetStream jetStream;
    private final FabricSecurity security;

    public FactRelay(LedgerService ledger, Connection connection, FabricSecurity security) throws IOException {
        this.security = security;
        this.ledger = ledger;
        this.connection = connection;
        this.jetStream = connection.jetStream();
    }

    /**
     * Publishes up to 200 unpublished facts in order, each followed by its signal, both signed by the kernel. A fact
     * too large for the broker is published in its reduced form (see the class comment). At the first fact the stream
     * does not take for another reason (no connection, no acknowledgement in time) it stops; the facts published so
     * far are marked and the rest are tried again on the next run (a fact sent twice has the same message id, so the
     * stream keeps one). When the marking itself fails, or the kernel stops before it, the whole batch is sent again
     * on the next run, signals included. A signal that cannot be sent is logged once per run and skipped.
     */
    @Scheduled(fixedDelayString = "${fabric.kernel.relay-interval:200ms}")
    public void relay() {
        List<LedgerService.StoredEvent> batch = ledger.unpublished(200);
        if (batch.isEmpty()) {
            return;
        }
        long maxPayload = maxPayload();
        List<Long> published = new ArrayList<>();
        String signalFailure = null;
        try {
            for (LedgerService.StoredEvent e : batch) {
                publishFact(e, maxPayload);
                published.add(e.seq());
                try {
                    publishSignal(signalSubject(e.type()), signal(e).toBytes());
                } catch (RuntimeException ex) {
                    signalFailure = ex.getMessage();
                }
            }
        } catch (Exception ex) {
            log.warn("fact relay paused: {}", ex.getMessage());
        } finally {
            ledger.markPublished(published);
        }
        if (signalFailure != null) {
            log.warn("some signals were not sent: {}", signalFailure);
        }
    }

    /**
     * The broker's message size limit for headers and body together, as the connection last heard it from the
     * server; zero or less while it is not known (no server information yet), and then no fact is reduced ahead of
     * publishing.
     */
    private long maxPayload() {
        try {
            return connection.getMaxPayload();
        } catch (RuntimeException e) {
            log.debug("cannot read the broker's message size: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Into the fact stream, signed by the kernel for the subject it is published on, and kept once per message id: the
     * whole fact when it fits {@code maxPayload}, else its reduced form, and nothing when even that is refused. The
     * client library refuses a message it can never send (too large, malformed) with an
     * {@link IllegalArgumentException} before anything reaches the broker; that refusal is final for the message, so it
     * never stops the batch. Any other failure is passed on, and the fact is tried again on the next run.
     */
    private void publishFact(LedgerService.StoredEvent e, long maxPayload) throws Exception {
        String subject = Subjects.fact(e.type());
        byte[] whole = fact(e).toBytes();
        try {
            Headers headers = headers(subject, e.id(), whole);
            if (fits(headers, whole, maxPayload)) {
                publish(subject, e.id(), headers, whole);
                return;
            }
            log.warn("fact {} ({}) has {} bytes with its headers, more than the broker's {}: its reduced form is"
                    + " published", e.id(), e.type(), headers.serializedLength() + whole.length, maxPayload);
        } catch (IllegalArgumentException refused) {
            log.warn("fact {} ({}) was refused by the client library ({}): its reduced form is published", e.id(),
                    e.type(), refused.getMessage());
        }
        byte[] small = reduced(e, whole.length).toBytes();
        try {
            Headers headers = headers(subject, e.id(), small);
            if (!fits(headers, small, maxPayload)) {
                throw new IllegalArgumentException("its reduced form has " + (headers.serializedLength()
                        + small.length) + " bytes with its headers, more than the broker's " + maxPayload);
            }
            publish(subject, e.id(), headers, small);
        } catch (IllegalArgumentException refused) {
            log.error("fact {} ({}) is not in the fact stream, only in the ledger: {}", e.id(), e.type(),
                    refused.getMessage());
        }
    }

    private Headers headers(String subject, String id, byte[] body) {
        return security.headers(subject, body).put("Nats-Msg-Id", id);
    }

    private void publish(String subject, String id, Headers headers, byte[] body) throws Exception {
        jetStream.publish(subject, headers, body, PublishOptions.builder().messageId(id).build());
    }

    /**
     * Whether a message of these headers and body fits the broker's limit of {@code maxPayload} bytes, which counts
     * the serialized headers and the body together; an unknown limit (zero or less) lets every message through.
     */
    static boolean fits(Headers headers, byte[] body, long maxPayload) {
        return maxPayload <= 0 || (long) headers.serializedLength() + body.length <= maxPayload;
    }

    /**
     * A plain broker message without a reply subject, signed by the kernel for its subject: agents act only on what
     * the kernel published, and there is no inbox of the kernel's for them to answer.
     */
    private void publishSignal(String subject, byte[] body) {
        connection.publish(subject, security.headers(subject, body), body);
    }

    static Envelope fact(LedgerService.StoredEvent e) {
        return new Envelope(Envelope.SPEC_VERSION, e.id(), SOURCE, Envelope.TYPE_PREFIX + "fact." + e.type(),
                e.goalId(), e.at(), "application/json", e.goalId(), e.causationId(), e.principal(),
                Json.tree(e.payload()));
    }

    /**
     * The form of a fact too large for the broker: the fact's own id (also its message id), type, time and goal,
     * with data holding only the goal id, the fact id, the fact type, {@code "truncated": true} and the size of the
     * whole fact in bytes. Its size does not depend on what the fact said, since the causation id and the principal,
     * which a request can carry, are left out too. What the fact changed shows in the goal the kernel answers for
     * ({@code query.goal}, {@code query.context}).
     */
    static Envelope reduced(LedgerService.StoredEvent e, int wholeBytes) {
        ObjectNode data = Json.object();
        data.put("goalId", e.goalId());
        data.put("factId", e.id());
        data.put("type", e.type());
        data.put("truncated", true);
        data.put("bytes", wholeBytes);
        return new Envelope(Envelope.SPEC_VERSION, e.id(), SOURCE, Envelope.TYPE_PREFIX + "fact." + e.type(),
                e.goalId(), e.at(), "application/json", e.goalId(), null, null, data);
    }

    /** {@code signal.task.added} for fact type {@code task.added}. */
    static String signalSubject(String factType) {
        return Subjects.signal(factType);
    }

    /**
     * A fact's signal: its id, type, time and goal, with data holding only the goal id and the fact id. The principal
     * is kept only when an agent caused the fact (so that agent can ignore its own changes); a person's or the
     * kernel's is left out, like the causation id and the data.
     */
    static Envelope signal(LedgerService.StoredEvent e) {
        ObjectNode data = Json.object();
        data.put("goalId", e.goalId());
        data.put("factId", e.id());
        String principal = e.principal() != null && e.principal().startsWith("agent:") ? e.principal() : null;
        return new Envelope(Envelope.SPEC_VERSION, e.id(), SOURCE, Envelope.TYPE_PREFIX + signalSubject(e.type()),
                e.goalId(), e.at(), "application/json", e.goalId(), null, principal, data);
    }
}
