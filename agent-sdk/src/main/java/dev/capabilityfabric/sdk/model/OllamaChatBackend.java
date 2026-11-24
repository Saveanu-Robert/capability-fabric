package dev.capabilityfabric.sdk.model;

import dev.capabilityfabric.protocol.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ollama through Spring AI. The JSON Schema goes into Ollama's {@code format} field, which constrains
 * decoding. Some Ollama engines (for example the MLX engine on Apple silicon) do not support constrained
 * decoding; for those models the schema is put into the instructions instead, and the Java-side validation
 * and repair loop in {@link StructuredModel} remains the authority either way. A model found to refuse
 * constrained decoding is remembered and asked with the schema in the instructions from then on. Thinking is off
 * unless the call asks for it ({@code fabric.model.thinking}).
 */
public final class OllamaChatBackend implements ChatBackend {

    private static final Logger log = LoggerFactory.getLogger(OllamaChatBackend.class);

    private final ChatModel chatModel;
    private final Set<String> unconstrained = ConcurrentHashMap.newKeySet();

    public OllamaChatBackend(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public Reply chat(Call call) {
        boolean constrained = call.schema() != null && !unconstrained.contains(call.model());
        try {
            return send(call, constrained);
        } catch (RuntimeException e) {
            if (constrained && String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT).contains("structured output")) {
                unconstrained.add(call.model());
                log.info("{} does not support constrained decoding on this server; putting the schema in the prompt",
                        call.model());
                return send(call, false);
            }
            throw e;
        }
    }

    private Reply send(Call call, boolean constrained) {
        OllamaChatOptions.Builder options = OllamaChatOptions.builder();
        options.model(call.model());
        options.temperature(call.temperature());
        options.seed(call.seed());
        options.numCtx(call.numCtx());
        options.keepAlive(call.keepAlive());
        String system = call.system();
        if (call.schema() != null) {
            if (constrained) {
                options.format(Json.convert(call.schema(), Map.class));
            } else {
                system = system + "\n\nThe answer must be one JSON object that satisfies this JSON Schema:\n"
                        + Json.write(call.schema());
            }
        }
        if (call.thinking()) {
            options.enableThinking();
        } else {
            options.disableThinking();
        }
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(call.user())), options.build());
        ChatResponse response = chatModel.call(prompt);
        String text = response.getResult() == null ? "" : response.getResult().getOutput().getText();
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return new Reply(text == null ? "" : text,
                usage == null ? null : usage.getPromptTokens(),
                usage == null ? null : usage.getCompletionTokens());
    }
}
