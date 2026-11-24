package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.DecisionRecord;
import dev.capabilityfabric.sdk.model.ModelRequest;
import dev.capabilityfabric.sdk.model.StructuredModel;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-call context handed to handlers: the model (with validation, repair and recording), the fabric client for
 * requests to the kernel (a goal's context, the memory of finished tasks, see {@link FabricClient}), and the decision
 * log of this call.
 */
public final class InvocationContext {

    private final CapabilityManifest manifest;
    private final StructuredModel model;
    private final FabricClient fabric;
    private final List<DecisionRecord> decisions = new CopyOnWriteArrayList<>();

    public InvocationContext(CapabilityManifest manifest, StructuredModel model, FabricClient fabric) {
        this.manifest = manifest;
        this.model = model;
        this.fabric = fabric;
    }

    public CapabilityManifest manifest() {
        return manifest;
    }

    /** Ask the model for JSON matching the request schema; every attempt is recorded. */
    public <T> T ask(ModelRequest request, Class<T> type) {
        if (model == null) {
            throw new IllegalStateException("no model configured for " + manifest.id());
        }
        return model.ask(request, type, manifest.id(), manifest.instanceId(), decisions::add);
    }

    /** The fabric client; empty in tests and wherever the handler runs without a broker. */
    public Optional<FabricClient> fabric() {
        return Optional.ofNullable(fabric);
    }

    /** The model calls made so far in this call, in order. */
    public List<DecisionRecord> decisions() {
        return List.copyOf(decisions);
    }

    /** The chat model's name, or {@code null} without a model. */
    public String modelName() {
        return model == null ? null : model.chatModel();
    }
}
