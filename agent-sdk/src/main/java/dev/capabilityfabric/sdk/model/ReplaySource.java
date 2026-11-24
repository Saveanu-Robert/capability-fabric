package dev.capabilityfabric.sdk.model;

import java.util.Optional;

/** Where replay mode finds recorded model answers, keyed by prompt hash. */
public interface ReplaySource {

    /** The model's recorded answer text for this prompt hash; empty when none was recorded. */
    Optional<String> recordedResponse(String promptHash);
}
