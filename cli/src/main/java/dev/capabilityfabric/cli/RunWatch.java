package dev.capabilityfabric.cli;

import dev.capabilityfabric.protocol.Envelope;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.protocol.Subjects;
import io.nats.client.Connection;
import io.nats.client.Dispatcher;
import io.nats.client.impl.Headers;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Follows plan runs from before a request is sent, so a run that finishes, or stops to wait for the person's
 * approval, while the reply is still on its way is not missed. Only facts the kernel signed count
 * ({@link #fromKernel}); anyone else on the broker who publishes a look-alike fact changes nothing here. Without a
 * broker connection (the gateway) it sees nothing and only what the caller reports counts.
 */
final class RunWatch implements AutoCloseable {

    private final Connection connection;
    private final Fabric.Device device;
    private final Dispatcher dispatcher;
    private final Set<String> finished = new HashSet<>();
    private final Map<String, String> waiting = new LinkedHashMap<>();

    private RunWatch(Connection connection, Fabric.Device device) {
        this.connection = connection;
        this.device = device;
        this.dispatcher = connection == null ? null : connection.createDispatcher(message ->
                receive(message.getSubject(), message.getHeaders(), message.getData()));
        if (dispatcher != null) {
            dispatcher.subscribe(Subjects.fact(Facts.RUN_FINISHED));
            dispatcher.subscribe(Subjects.fact(Facts.APPROVAL_REQUESTED));
        }
    }

    /**
     * Starts listening now when there is a broker connection; {@code device} says which kernel's signature a fact
     * must carry.
     */
    static RunWatch start(Optional<Connection> connection, Fabric.Device device) {
        return new RunWatch(connection.orElse(null), device);
    }

    /** True when run facts are followed, which needs a broker connection. */
    boolean live() {
        return dispatcher != null;
    }

    /**
     * One fact as it arrived on {@code subject}: a run that finished, or one that waits for the person's approval.
     * A fact the kernel did not sign for that subject, or one this version cannot read, is ignored.
     */
    void receive(String subject, Headers headers, byte[] data) {
        if (subject == null || !fromKernel(device, subject, headers, data)) {
            return;
        }
        try {
            Envelope fact = Envelope.fromBytes(data);
            if (subject.equals(Subjects.fact(Facts.APPROVAL_REQUESTED))) {
                Facts.ApprovalRequested asked = fact.dataAs(Facts.ApprovalRequested.class);
                waiting(asked.runId(), asked.capability() + ": " + asked.reason());
            } else if (subject.equals(Subjects.fact(Facts.RUN_FINISHED))) {
                finished(fact.dataAs(Facts.RunFinished.class).runId());
            }
        } catch (RuntimeException ignored) {
            // A fact this version cannot read does not concern the runs it waits for.
        }
    }

    /**
     * Whether a fact that arrived on {@code subject} was published by the kernel this device trusts: the kernel signs
     * every fact for the subject it publishes it on. With a pinned kernel key, only that key's valid signature
     * counts; before any key is pinned, a kept expectation (from {@code --grant --kernel}) must match the signer.
     * A device that trusts and expects no kernel yet checks replies no further than their signature fitting the
     * request ({@link Fabric#screen}), and facts the same way: an unsigned fact passes, a signature that does not fit
     * the fact does not.
     */
    static boolean fromKernel(Fabric.Device device, String subject, Headers headers, byte[] data) {
        Optional<Security.Stamp> stamp = stamp(headers);
        String signer = stamp.filter(s -> s.verifies(subject, data == null ? new byte[0] : data))
                .map(Security.Stamp::key).orElse(null);
        Optional<String> pinned = device.kernelKey();
        if (pinned.isPresent()) {
            return pinned.get().equals(signer);
        }
        Optional<String> expected = device.expectedKernel();
        if (expected.isPresent()) {
            return KernelId.matches(expected.get(), signer);
        }
        return stamp.isEmpty() || signer != null;
    }

    /** The signature a message's headers carry; empty when they carry none or an incomplete one. */
    private static Optional<Security.Stamp> stamp(Headers headers) {
        if (headers == null) {
            return Optional.empty();
        }
        Map<String, String> values = new HashMap<>();
        for (String name : List.of(Security.HEADER_KEY, Security.HEADER_TS, Security.HEADER_NONCE,
                Security.HEADER_SIG)) {
            String v = headers.getFirst(name);
            if (v != null) {
                values.put(name, v);
            }
        }
        return Security.Stamp.of(values);
    }

    /** Records that a run finished, from its fact or from what the goal already shows. */
    synchronized void finished(String runId) {
        if (runId != null) {
            finished.add(runId);
            notifyAll();
        }
    }

    /** Records that a run waits for the person's approval of {@code what}; the first description is kept. */
    synchronized void waiting(String runId, String what) {
        if (runId != null) {
            waiting.putIfAbsent(runId, what);
            notifyAll();
        }
    }

    /** True when every run has finished or waits for the person. */
    synchronized boolean settled(Collection<String> runIds) {
        return runIds.stream().allMatch(r -> finished.contains(r) || waiting.containsKey(r));
    }

    /** Waits until {@link #settled}, or the time is up; true when settled. */
    synchronized boolean await(Collection<String> runIds, Duration max) throws InterruptedException {
        long end = System.nanoTime() + max.toNanos();
        while (!settled(runIds)) {
            long left = end - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }

    /** What waits for the person among these runs. */
    synchronized List<String> waitingForYou(Collection<String> runIds) {
        return runIds.stream().filter(waiting::containsKey).map(waiting::get).distinct().toList();
    }

    @Override
    public void close() {
        if (dispatcher != null) {
            connection.closeDispatcher(dispatcher);
        }
    }
}
