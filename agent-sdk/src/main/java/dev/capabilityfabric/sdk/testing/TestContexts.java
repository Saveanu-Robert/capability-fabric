package dev.capabilityfabric.sdk.testing;

import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.sdk.InvocationContext;
import dev.capabilityfabric.sdk.model.ChatBackend;
import dev.capabilityfabric.sdk.model.StructuredModel;

import java.time.Instant;

/** Builds handler contexts for unit tests without a broker or a model server. */
public final class TestContexts {

    private TestContexts() {
    }

    /**
     * A context with instance "test-instance" of provider "test-provider", no fabric, and a model answering from
     * {@code backend} with two repairs ({@code null} for no model).
     */
    public static InvocationContext context(CapabilityManifest declared, ChatBackend backend) {
        CapabilityManifest manifest = declared.withInstance("test-provider", "test-instance", Instant.now(), 15);
        StructuredModel model = backend == null ? null
                : new StructuredModel(backend, null, "test-model", 0.0, 42, 8192, "1m", false, 2, false);
        return new InvocationContext(manifest, model, null);
    }

    /** A context without a model, for a deterministic capability (see {@link #context}). */
    public static InvocationContext deterministic(CapabilityManifest declared) {
        return context(declared, null);
    }
}
