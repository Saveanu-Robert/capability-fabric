package dev.capabilityfabric.kernel.registry;

import dev.capabilityfabric.protocol.Subjects;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.KeyValueManagement;
import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.KeyValueStatus;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * Creates the broker resources the kernel owns: the fact stream and the genesis bucket. Each has a size limit, so
 * nobody who may publish into one can fill the broker's disk, and the genesis bucket a limit on consumers. Agents have
 * no JetStream rights: they hear the kernel's signals through a plain subscription as they are published (no stream
 * keeps them) and get the grown capabilities' specs from the kernel ({@code query.genesis}). Existing resources are
 * given these limits. A capability registry bucket or a signal stream found on the broker is deleted: providers
 * register through the kernel ({@link CapabilityRegistry}) and signals are plain broker messages, so neither is used.
 */
@Component
public class FabricSetup {

    private static final Logger log = LoggerFactory.getLogger(FabricSetup.class);

    static final long MIB = 1024L * 1024L;
    /** Facts are kept 7 days and 1 GiB at most; the oldest go first (Postgres keeps every fact). */
    static final Duration FACTS_AGE = Duration.ofDays(7);
    static final long FACTS_BYTES = 1024 * MIB;
    /** One genesis spec is far smaller than this. */
    static final long MAX_VALUE_BYTES = 64 * 1024;
    static final long GENESIS_BYTES = 16 * MIB;
    /**
     * Consumers the genesis bucket may have at once. Only the kernel's connection may create any; the limit bounds
     * what a connection with JetStream rights can leave behind.
     */
    static final int MAX_CONSUMERS = 64;
    /** The stream behind the genesis bucket. */
    static final String GENESIS_STREAM = "KV_" + Subjects.KV_GENESIS;

    /**
     * Creates what is missing and applies the limits to what exists: the fact stream (on disk, duplicates by message
     * id dropped within an hour) and the genesis bucket (on disk, 5 revisions per spec).
     */
    public FabricSetup(Connection connection) throws IOException, JetStreamApiException {
        JetStreamManagement jsm = connection.jetStreamManagement();
        ensureStream(jsm, facts());
        dropSignalStream(jsm);

        KeyValueManagement kvm = connection.keyValueManagement();
        dropRegistryBucket(kvm);
        try {
            ensureLimits(kvm, kvm.getStatus(Subjects.KV_GENESIS), genesis());
        } catch (JetStreamApiException notFound) {
            kvm.create(genesis());
            log.info("created genesis bucket {}", Subjects.KV_GENESIS);
        }
        // After the bucket's own settings: updating a bucket resets its stream's consumer limit.
        limitConsumers(jsm, GENESIS_STREAM);
    }

    /** Deletes the registry bucket earlier versions kept, if it is there. */
    private static void dropRegistryBucket(KeyValueManagement kvm) throws IOException {
        try {
            kvm.getStatus(Subjects.KV_CAPABILITIES);
        } catch (JetStreamApiException notFound) {
            return;
        }
        try {
            kvm.delete(Subjects.KV_CAPABILITIES);
            log.info("deleted the registry bucket {}: providers register through the kernel", Subjects.KV_CAPABILITIES);
        } catch (JetStreamApiException e) {
            log.warn("cannot delete the registry bucket {}: {}", Subjects.KV_CAPABILITIES, e.getMessage());
        }
    }

    /** Deletes the signal stream earlier versions kept, if it is there. */
    private static void dropSignalStream(JetStreamManagement jsm) throws IOException {
        try {
            jsm.getStreamInfo(Subjects.STREAM_SIGNALS);
        } catch (JetStreamApiException notFound) {
            return;
        }
        try {
            jsm.deleteStream(Subjects.STREAM_SIGNALS);
            log.info("deleted the signal stream {}: signals are plain broker messages", Subjects.STREAM_SIGNALS);
        } catch (JetStreamApiException e) {
            log.warn("cannot delete the signal stream {}: {}", Subjects.STREAM_SIGNALS, e.getMessage());
        }
    }

    /**
     * Sets {@link #MAX_CONSUMERS} on a stream that has another limit. A broker that refuses (for example while the
     * stream has more consumers than that) leaves the stream as it is and the kernel starts anyway.
     */
    private static void limitConsumers(JetStreamManagement jsm, String stream) throws IOException, JetStreamApiException {
        StreamConfiguration current = jsm.getStreamInfo(stream).getConfiguration();
        if (current.getMaxConsumers() == MAX_CONSUMERS) {
            return;
        }
        try {
            jsm.updateStream(StreamConfiguration.builder(current).maxConsumers(MAX_CONSUMERS).build());
            log.info("stream {} now allows at most {} consumers", stream, MAX_CONSUMERS);
        } catch (JetStreamApiException e) {
            log.warn("cannot limit the consumers of stream {}: {}", stream, e.getMessage());
        }
    }

    static StreamConfiguration facts() {
        return StreamConfiguration.builder()
                .name(Subjects.STREAM_FACTS)
                .subjects(Subjects.ALL_FACTS)
                .storageType(StorageType.File)
                .maxAge(FACTS_AGE)
                .maxBytes(FACTS_BYTES)
                .duplicateWindow(Duration.ofHours(1))
                .build();
    }

    static KeyValueConfiguration genesis() {
        return KeyValueConfiguration.builder()
                .name(Subjects.KV_GENESIS)
                .description("Specs of capabilities the system grew; written only by the kernel")
                .maxHistoryPerKey(5)
                .maxValueSize(MAX_VALUE_BYTES)
                .maxBucketSize(GENESIS_BYTES)
                .storageType(StorageType.File)
                .build();
    }

    /** Creates the stream, or updates it when its age or size limit differs from {@code wanted}. */
    private static void ensureStream(JetStreamManagement jsm, StreamConfiguration wanted)
            throws IOException, JetStreamApiException {
        StreamConfiguration current;
        try {
            current = jsm.getStreamInfo(wanted.getName()).getConfiguration();
        } catch (JetStreamApiException notFound) {
            jsm.addStream(wanted);
            log.info("created stream {}", wanted.getName());
            return;
        }
        if (!sameLimits(current, wanted)) {
            jsm.updateStream(wanted);
            log.info("stream {} now keeps at most {} for {}", wanted.getName(), bytes(wanted.getMaxBytes()),
                    wanted.getMaxAge());
        }
    }

    static boolean sameLimits(StreamConfiguration current, StreamConfiguration wanted) {
        return current.getMaxBytes() == wanted.getMaxBytes() && current.getMaxAge().equals(wanted.getMaxAge());
    }

    /** Updates a bucket whose value or bucket size limit differs from {@code wanted}. */
    private static void ensureLimits(KeyValueManagement kvm, KeyValueStatus current, KeyValueConfiguration wanted)
            throws IOException, JetStreamApiException {
        if (current.getMaxValueSize() != wanted.getMaxValueSize()
                || current.getMaxBucketSize() != wanted.getMaxBucketSize()) {
            kvm.update(wanted);
            log.info("bucket {} now holds at most {} ({} per value)", wanted.getBucketName(),
                    bytes(wanted.getMaxBucketSize()), bytes(wanted.getMaxValueSize()));
        }
    }

    private static String bytes(long n) {
        return n >= MIB ? n / MIB + " MiB" : n / 1024 + " KiB";
    }
}
