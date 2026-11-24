package dev.capabilityfabric.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/**
 * The single JSON configuration used on every wire and in every hash. Keys are sorted so that the same value always
 * serialises to the same bytes.
 */
public final class Json {

    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    private Json() {
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    public static byte[] bytes(Object value) {
        return write(value).getBytes(StandardCharsets.UTF_8);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    public static <T> T read(byte[] json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    public static JsonNode tree(String json) {
        return MAPPER.readTree(json);
    }

    public static JsonNode tree(byte[] json) {
        return MAPPER.readTree(json);
    }

    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static <T> T convert(Object value, Class<T> type) {
        return MAPPER.convertValue(value, type);
    }

    public static ObjectNode object() {
        return JsonNodeFactory.instance.objectNode();
    }

    public static ArrayNode array() {
        return JsonNodeFactory.instance.arrayNode();
    }

    /** Canonical form: sorted keys, no insignificant whitespace. Used for hashing. */
    public static String canonical(Object value) {
        JsonNode node = value instanceof JsonNode n ? n : toTree(value);
        return MAPPER.writeValueAsString(MAPPER.treeToValue(node, Object.class));
    }
}
