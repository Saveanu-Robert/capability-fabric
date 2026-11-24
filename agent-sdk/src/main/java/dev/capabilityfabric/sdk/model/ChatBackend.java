package dev.capabilityfabric.sdk.model;

import tools.jackson.databind.JsonNode;

/** One model server. Implementations must be thread-safe. */
public interface ChatBackend {

    /** One completion; failures are thrown as runtime exceptions. */
    Reply chat(Call call);

    /** A single chat completion request. {@code schema} constrains decoding when the server supports it. */
    record Call(String model, String system, String user, JsonNode schema, double temperature, int seed,
                int numCtx, String keepAlive, boolean thinking) {
    }

    /** The model's text and, when the server reports them, the token counts. */
    record Reply(String text, Integer promptTokens, Integer completionTokens) {
    }
}
