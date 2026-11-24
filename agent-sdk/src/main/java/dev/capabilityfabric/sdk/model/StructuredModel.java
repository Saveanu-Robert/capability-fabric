package dev.capabilityfabric.sdk.model;

import dev.capabilityfabric.protocol.DecisionRecord;
import dev.capabilityfabric.protocol.Hashing;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.SchemaValidator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * LLM-Modulo wrapper: the model proposes, Java verifies. Each attempt is schema-validated and semantically checked;
 * failures are fed back for at most {@code maxRepairs} repairs, each with a seed of its own. Every attempt becomes a
 * {@link DecisionRecord}. In replay mode answers come from recorded decisions with the same prompt hash, so a run can
 * be reproduced without any model.
 */
public final class StructuredModel {

    static final double REPAIR_TEMPERATURE = 0.4;

    private final ChatBackend backend;
    private final ReplaySource replaySource;
    private final String chatModel;
    private final double temperature;
    private final int seed;
    private final int numCtx;
    private final String keepAlive;
    private final boolean thinking;
    private final int maxRepairs;
    private final boolean replay;

    public StructuredModel(ChatBackend backend, ReplaySource replaySource, String chatModel, double temperature,
                           int seed, int numCtx, String keepAlive, boolean thinking, int maxRepairs, boolean replay) {
        this.backend = backend;
        this.replaySource = replaySource;
        this.chatModel = chatModel;
        this.temperature = temperature;
        this.seed = seed;
        this.numCtx = numCtx;
        this.keepAlive = keepAlive;
        this.thinking = thinking;
        this.maxRepairs = maxRepairs;
        this.replay = replay;
    }

    public String chatModel() {
        return chatModel;
    }

    /**
     * Asks until an answer passes the schema and the semantic checks, at most {@code 1 + maxRepairs} times; each
     * repair shows the model its previous problems. Every attempt is handed to {@code sink}.
     *
     * @throws ModelOutputException when no attempt is acceptable, or in replay mode when a decision is missing
     */
    public <T> T ask(ModelRequest request, Class<T> type, String capability, String instanceId,
                     Consumer<DecisionRecord> sink) {
        String user = request.user();
        List<String> errors = List.of();
        for (int attempt = 1; attempt <= 1 + maxRepairs; attempt++) {
            // A repair at temperature 0 with the same seed tends to repeat the rejected answer; allow some variation.
            double attemptTemperature = attempt == 1 ? temperature : Math.max(temperature, REPAIR_TEMPERATURE);
            int attemptSeed = seedFor(attempt);
            ObjectNode canonical = Json.object();
            canonical.put("model", chatModel);
            canonical.put("system", request.system());
            canonical.put("user", user);
            canonical.set("schema", request.schema());
            canonical.put("temperature", attemptTemperature);
            canonical.put("seed", attemptSeed);
            String hash = Hashing.canonicalHash(canonical);

            long start = System.nanoTime();
            ChatBackend.Reply reply;
            boolean replayed = false;
            if (replay) {
                Optional<String> recorded = replaySource == null ? Optional.empty() : replaySource.recordedResponse(hash);
                if (recorded.isEmpty()) {
                    throw new ModelOutputException("replay mode: no recorded decision for " + request.purpose(),
                            List.of("prompt hash " + hash));
                }
                reply = new ChatBackend.Reply(recorded.get(), null, null);
                replayed = true;
            } else {
                reply = backend.chat(new ChatBackend.Call(chatModel, request.system(), user, request.schema(),
                        attemptTemperature, attemptSeed, numCtx, keepAlive, thinking));
            }
            long latencyMs = (System.nanoTime() - start) / 1_000_000;

            JsonNode parsed = parseJson(reply.text());
            if (parsed != null) {
                parsed = request.tolerant().apply(parsed);
            }
            List<String> problems = new ArrayList<>();
            if (parsed == null) {
                problems.add("answer is not a JSON object");
            } else {
                if (request.schema() != null) {
                    problems.addAll(SchemaValidator.validate(request.schema(), parsed));
                }
                if (problems.isEmpty()) {
                    problems.addAll(request.semantic().apply(parsed));
                }
            }
            sink.accept(new DecisionRecord(UUID.randomUUID().toString(), capability, instanceId, request.purpose(),
                    chatModel, hash, canonical, reply.text(), parsed, problems.isEmpty(), problems, attempt,
                    latencyMs, reply.promptTokens(), reply.completionTokens(), replayed, Instant.now()));
            if (problems.isEmpty()) {
                return Json.MAPPER.treeToValue(parsed, type);
            }
            errors = problems;
            user = request.user() + "\n\nYour previous answer was rejected for these reasons:\n- "
                    + String.join("\n- ", problems)
                    + "\nPrevious answer:\n" + truncate(reply.text(), 3000)
                    + "\nReturn only corrected JSON that satisfies the schema.";
        }
        throw new ModelOutputException("no valid answer for " + request.purpose() + " after "
                + (1 + maxRepairs) + " attempts", errors);
    }

    /**
     * The seed of one attempt: the configured seed first, then one more for every repair. Two repairs that show the
     * model the same rejected answer then still differ in their seed (and their prompt hash), so the model is not
     * asked the very same question twice and a replay finds each attempt's own recorded answer.
     */
    int seedFor(int attempt) {
        return seed + attempt - 1;
    }

    /** At most this many '{' are tried as the start of the answer's object. */
    static final int MAX_STARTS = 20;

    /**
     * The first complete JSON object in the answer: bare JSON, JSON inside code fences, or JSON surrounded by prose,
     * whatever follows it (a stray '}' or more text). Each '{' is tried in turn (at most {@link #MAX_STARTS}) as the
     * start of one JSON value, read with the streaming parser, which stops where that value ends. A short id written
     * in brackets without quotes ({@code "goal": [fd53a5a0]}) is read as the string {@code "[fd53a5a0]"} first.
     */
    static JsonNode parseJson(String text) {
        if (text == null) {
            return null;
        }
        // An id written in brackets without quotes ("goal": [fd53a5a0]) is no JSON at all: it is read as the string.
        text = BARE_ID.matcher(text).replaceAll(": \"[$1]\"");
        char[] chars = text.toCharArray();
        int start = text.indexOf('{');
        for (int tries = 0; start >= 0 && tries < MAX_STARTS; tries++) {
            JsonNode node = firstValue(chars, start);
            if (node != null && node.isObject()) {
                return node;
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    /** A short hex id in brackets without quotes, as a value: {@code : [fd53a5a0]}. */
    private static final Pattern BARE_ID = Pattern.compile(
            ":\\s*\\[\\s*([0-9a-f]{8})\\s*]");

    /** Reads one JSON value and stops there, whatever text follows it. */
    private static final ObjectReader FIRST_VALUE = Json.MAPPER.readerFor(JsonNode.class)
            .without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** The one JSON value that starts at {@code start}, or null when none does. */
    private static JsonNode firstValue(char[] chars, int start) {
        try (JsonParser parser = FIRST_VALUE.createParser(chars, start, chars.length - start)) {
            return FIRST_VALUE.readValue(parser);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
