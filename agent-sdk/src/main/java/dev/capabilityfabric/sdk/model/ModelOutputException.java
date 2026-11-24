package dev.capabilityfabric.sdk.model;

import java.util.List;

/** The model did not produce an acceptable answer within the repair budget. */
public class ModelOutputException extends RuntimeException {

    private final List<String> errors;

    public ModelOutputException(String message, List<String> errors) {
        super(message + ": " + String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    /** The problems found in the last answer. */
    public List<String> errors() {
        return errors;
    }
}
