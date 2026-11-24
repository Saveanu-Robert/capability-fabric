package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a capability provider advertises in the registry. The fields are a superset of an A2A Agent Card skill (id,
 * name, description, tags) plus the typed contract the kernel needs: vocabulary tokens consumed, required and produced,
 * value schemas for new annotations, side effects and triggers for reactive providers.
 */
public record CapabilityManifest(
        String id,
        String version,
        String name,
        String provider,
        String instanceId,
        String description,
        Kind kind,
        Mode mode,
        List<String> consumes,
        List<String> requires,
        List<String> produces,
        Map<String, JsonNode> annotationSchemas,
        SideEffect sideEffect,
        List<String> triggers,
        List<String> tags,
        Cost cost,
        String model,
        Instant heartbeatAt,
        int ttlSeconds) {

    public enum Kind { AI, DETERMINISTIC }

    /** INVOKED providers answer capability calls; REACTIVE providers follow the kernel's signals and send proposals. */
    public enum Mode { INVOKED, REACTIVE }

    /** Declared effects outside the ledger. */
    public enum SideEffect { NONE, EXTERNAL, DESTRUCTIVE }

    public record Cost(long expectedLatencyMs, int llmCalls) {
    }

    /** A major version number as the manifest schema allows it: at most nine digits. */
    private static final Pattern MAJOR = Pattern.compile("[0-9]{1,9}");

    public CapabilityManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        consumes = consumes == null ? List.of() : List.copyOf(consumes);
        requires = requires == null ? List.of() : List.copyOf(requires);
        produces = produces == null ? List.of() : List.copyOf(produces);
        annotationSchemas = annotationSchemas == null ? Map.of() : Map.copyOf(annotationSchemas);
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        tags = tags == null ? List.of() : List.copyOf(tags);
        sideEffect = sideEffect == null ? SideEffect.NONE : sideEffect;
        mode = mode == null ? Mode.INVOKED : mode;
        kind = kind == null ? Kind.DETERMINISTIC : kind;
        cost = cost == null ? new Cost(1000, kind == Kind.AI ? 1 : 0) : cost;
    }

    /**
     * The major version, the part before the first dot: callers bind to it, so a new major is a new contract on
     * its own subject. The manifest schema allows at most nine digits per part, so a valid version always fits.
     *
     * @throws IllegalArgumentException when the major part is not a number of at most nine digits
     */
    public int major() {
        String head = version.split("\\.")[0];
        if (!MAJOR.matcher(head).matches()) {
            throw new IllegalArgumentException("version " + version + " has no major number of at most nine digits");
        }
        return Integer.parseInt(head);
    }

    /** The subject every instance of this capability's major version listens on (queue group). */
    public String subject() {
        return Subjects.capability(id, major());
    }

    /** The subject only this instance listens on. */
    public String instanceSubject() {
        return Subjects.capabilityInstance(id, major(), instanceId);
    }

    /**
     * The prefix every instance id of {@code provider} starts with: the provider's name with every character other
     * than a letter, a digit or '-' replaced by '-', then '-'. The agent runtime names its instances so, and the
     * kernel lists only entries whose instance id starts with their provider's prefix.
     */
    public static String instancePrefix(String provider) {
        return provider.replaceAll("[^A-Za-z0-9-]", "-") + "-";
    }

    /** Registry key: one entry per capability per running instance. */
    public String registryKey() {
        return id + "." + instanceId;
    }

    public AutonomyLevel autonomy() {
        if (sideEffect != SideEffect.NONE) {
            return AutonomyLevel.L3;
        }
        if (produces.stream().anyMatch(Vocabulary.STRUCTURAL::contains)) {
            return AutonomyLevel.L2;
        }
        return produces.isEmpty() ? AutonomyLevel.L0 : AutonomyLevel.L1;
    }

    public CapabilityManifest withInstance(String newProvider, String newInstanceId, Instant heartbeat, int ttl) {
        return new CapabilityManifest(id, version, name, newProvider, newInstanceId, description, kind, mode,
                consumes, requires, produces, annotationSchemas, sideEffect, triggers, tags, cost, model,
                heartbeat, ttl);
    }

    public CapabilityManifest withHeartbeat(Instant heartbeat) {
        return withInstance(provider, instanceId, heartbeat, ttlSeconds);
    }

    public CapabilityManifest withModel(String newModel) {
        return new CapabilityManifest(id, version, name, provider, instanceId, description, kind, mode,
                consumes, requires, produces, annotationSchemas, sideEffect, triggers, tags, cost, newModel,
                heartbeatAt, ttlSeconds);
    }

    /** Text used for semantic matching against goals. */
    public String searchText() {
        return (name == null ? id : name) + ". " + (description == null ? "" : description)
                + (tags.isEmpty() ? "" : " Tags: " + String.join(", ", tags) + ".");
    }

    public static Builder builder(String id, String version) {
        return new Builder(id, version);
    }

    /** Fluent builder used by providers to declare their contract. */
    public static final class Builder {
        private final String id;
        private final String version;
        private String name;
        private String description;
        private Kind kind = Kind.DETERMINISTIC;
        private Mode mode = Mode.INVOKED;
        private List<String> consumes = List.of();
        private List<String> requires = List.of();
        private List<String> produces = List.of();
        private Map<String, JsonNode> annotationSchemas = Map.of();
        private SideEffect sideEffect = SideEffect.NONE;
        private List<String> triggers = List.of();
        private List<String> tags = List.of();
        private Cost cost;

        private Builder(String id, String version) {
            this.id = id;
            this.version = version;
        }

        public Builder name(String value) { this.name = value; return this; }
        public Builder description(String value) { this.description = value; return this; }
        public Builder kind(Kind value) { this.kind = value; return this; }
        public Builder mode(Mode value) { this.mode = value; return this; }
        public Builder consumes(String... tokens) { this.consumes = List.of(tokens); return this; }
        public Builder requires(String... tokens) { this.requires = List.of(tokens); return this; }
        public Builder produces(String... tokens) { this.produces = List.of(tokens); return this; }
        public Builder annotationSchemas(Map<String, JsonNode> value) { this.annotationSchemas = value; return this; }
        public Builder sideEffect(SideEffect value) { this.sideEffect = value; return this; }
        public Builder triggers(String... factTypes) { this.triggers = List.of(factTypes); return this; }
        public Builder tags(String... value) { this.tags = List.of(value); return this; }
        public Builder cost(long expectedLatencyMs, int llmCalls) { this.cost = new Cost(expectedLatencyMs, llmCalls); return this; }

        public CapabilityManifest build() {
            return new CapabilityManifest(id, version, name, null, null, description, kind, mode, consumes,
                    requires, produces, annotationSchemas, sideEffect, triggers, tags, cost, null, null, 0);
        }
    }
}
