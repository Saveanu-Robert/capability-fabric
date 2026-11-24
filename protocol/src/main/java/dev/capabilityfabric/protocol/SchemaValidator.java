package dev.capabilityfabric.protocol;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON Schema 2020-12 validation. Every boundary validates: capability inputs and outputs, manifests, model outputs and
 * annotation values. Model servers may silently ignore schema keywords during constrained decoding, so this check is
 * the one that counts.
 */
public final class SchemaValidator {

    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    /** Compiled schemas kept; the least recently used one is dropped beyond this many. */
    static final int CACHE_SIZE = 256;
    /**
     * Compiled schemas by canonical text, in access order. Request schemas embed per-call values (task keys,
     * capability ids), so the cache is bounded instead of growing with every distinct call.
     */
    private static final Map<String, Schema> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Schema> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private SchemaValidator() {
    }

    /** Every way {@code instance} breaks {@code schema}, each as "location: message"; empty when it is valid. */
    public static List<String> validate(JsonNode schema, JsonNode instance) {
        List<Error> errors = compiled(schema).validate(instance);
        // The location tells a model which field to repair; the message alone often does not.
        return errors.stream().map(e -> e.getInstanceLocation() + ": " + e.getMessage()).toList();
    }

    /** Compiles outside the lock, so a slow compilation never holds up validations of other schemas. */
    private static Schema compiled(JsonNode schema) {
        String key = Json.canonical(schema);
        synchronized (CACHE) {
            Schema cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }
        Schema fresh = REGISTRY.getSchema(schema);
        synchronized (CACHE) {
            Schema raced = CACHE.putIfAbsent(key, fresh);
            return raced != null ? raced : fresh;
        }
    }

    static int cachedSchemas() {
        synchronized (CACHE) {
            return CACHE.size();
        }
    }

    /** Validates against a schema bundled under {@code /schemas/<name>.schema.json}. */
    public static List<String> validateBundled(String name, JsonNode instance) {
        return validate(bundled(name), instance);
    }

    /**
     * The schema bundled under {@code /schemas/<name>.schema.json}.
     *
     * @throws IllegalArgumentException when no such schema is bundled
     */
    public static JsonNode bundled(String name) {
        String path = "/schemas/" + name + ".schema.json";
        try (InputStream in = SchemaValidator.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("no bundled schema " + path);
            }
            return Json.MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True when the node is itself a usable schema (compiles without error). */
    public static boolean isSchema(JsonNode schema) {
        try {
            REGISTRY.getSchema(schema);
            return schema.isObject();
        } catch (RuntimeException e) {
            return false;
        }
    }
}
