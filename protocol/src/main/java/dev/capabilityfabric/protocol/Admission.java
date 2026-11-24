package dev.capabilityfabric.protocol;

import java.util.List;

/** An agent asking to be admitted, and the kernel's answer. */
public final class Admission {

    private Admission() {
    }

    /**
     * {@code PENDING}: the owner has not decided yet; {@code ADMITTED}: the grant is in the answer;
     * {@code REJECTED}: the owner said no; {@code REVOKED}: admitted once, no longer.
     */
    public enum Status { PENDING, ADMITTED, REJECTED, REVOKED }

    /** What the agent asks for: the capabilities it offers and the vocabulary they write. */
    public record Request(String provider, String instanceId, List<CapabilityManifest> manifests) {
        public Request {
            manifests = manifests == null ? List.of() : List.copyOf(manifests);
        }
    }

    /** {@code grant} is the encoded {@link Grant} once admitted; {@code fingerprint} is what the owner compares. */
    public record Answer(Status status, String grant, String fingerprint, String message) {
    }
}
