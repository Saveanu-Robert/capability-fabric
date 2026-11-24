package dev.capabilityfabric.sdk.model;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * A structured question for the model.
 *
 * @param purpose   short label recorded with the decision (for example {@code decompose})
 * @param system    instructions
 * @param user      the task, built only from data the capability consumes
 * @param schema    JSON Schema the answer must satisfy (flat: no $ref, no conditionals)
 * @param semantic  extra checks beyond the schema (for example "dependsOn names unknown keys");
 *                  returns error messages, empty when the answer is acceptable
 * @param tolerant  tolerant reader applied to the parsed answer before any check: maps what small models
 *                  predictably get wrong (a field under another name) onto the schema; the recorded decision keeps
 *                  the model's original text
 */
public record ModelRequest(String purpose, String system, String user, JsonNode schema,
                           Function<JsonNode, List<String>> semantic, UnaryOperator<JsonNode> tolerant) {

    public ModelRequest(String purpose, String system, String user, JsonNode schema,
                        Function<JsonNode, List<String>> semantic) {
        this(purpose, system, user, schema, semantic, UnaryOperator.identity());
    }

    public ModelRequest(String purpose, String system, String user, JsonNode schema) {
        this(purpose, system, user, schema, node -> List.of());
    }

    /** Line breaks and other control characters, which {@link #quote} turns into spaces. */
    private static final Pattern BREAKS = Pattern.compile("[\\p{Cntrl}\\u0085\\u2028\\u2029]+");

    /**
     * Text that people or records wrote, marked for a prompt so the model can tell data from instructions: between
     * « and », without the guillemets it contains (so it cannot close the mark early) and with line breaks and other
     * control characters as spaces (so it cannot start a line of its own). {@code null} is the empty text. A prompt
     * that embeds such text tells the model that what stands inside « » is data.
     */
    public static String quote(String text) {
        String plain = text == null ? "" : text.replace("«", "").replace("»", "");
        return "«" + BREAKS.matcher(plain).replaceAll(" ") + "»";
    }
}
