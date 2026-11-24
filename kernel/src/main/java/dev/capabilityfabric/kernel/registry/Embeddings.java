package dev.capabilityfabric.kernel.registry;

import dev.capabilityfabric.kernel.KernelProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Text embeddings for matching goals to capabilities and recalling similar past work. Vectors are cached per text,
 * keeping the {@link #CACHE_SIZE} most recently used ones. When no embedding model is reachable, callers fall back
 * to {@link #lexicalSimilarity}, so the system keeps working.
 */
@Component
public class Embeddings {

    private static final Logger log = LoggerFactory.getLogger(Embeddings.class);
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "into",
            "each", "task", "tasks", "goal", "goals", "are", "will", "when", "what", "your", "have");

    static final int CACHE_SIZE = 10_000;

    private final EmbeddingModel model;
    private final String modelName;
    private final Map<String, float[]> cache;
    private volatile long disabledUntil;

    @Autowired
    public Embeddings(ObjectProvider<EmbeddingModel> model, KernelProperties properties, Environment environment) {
        this(properties.embeddings() ? model.getIfAvailable() : null,
                environment.getProperty("spring.ai.ollama.embedding.options.model", "none"), CACHE_SIZE);
    }

    /** {@code model} may be {@code null} (lexical matching only); at most {@code cacheSize} vectors are kept. */
    Embeddings(EmbeddingModel model, String modelName, int cacheSize) {
        this.model = model;
        this.modelName = modelName;
        this.cache = lru(cacheSize);
    }

    /** A synchronised map in access order that drops its least recently used entry beyond {@code capacity}. */
    static <V> Map<String, V> lru(int capacity) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > capacity;
            }
        });
    }

    /** The embedding model's name stored with each vector ("none" without a model). */
    public String modelName() {
        return model == null ? "none" : modelName;
    }

    /**
     * The text's vector, from the cache or the model. Empty without a model, and for 60 s after the model failed,
     * so a missing model costs one failed call a minute rather than one per text.
     */
    public Optional<float[]> embed(String text) {
        if (model == null || text == null || System.currentTimeMillis() < disabledUntil) {
            return Optional.empty();
        }
        float[] cached = cache.get(text);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            float[] vector = model.embed(text);
            cache.put(text, vector);
            return Optional.of(vector);
        } catch (RuntimeException e) {
            log.warn("embedding model unavailable ({}); using lexical matching for 60 s", e.getMessage());
            disabledUntil = System.currentTimeMillis() + 60_000;
            return Optional.empty();
        }
    }

    /** Cosine similarity of two vectors; 0 when their lengths differ or either is all zeros. */
    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /** Jaccard overlap of content words; the fallback when embeddings are unavailable. */
    public static double lexicalSimilarity(String a, String b) {
        Set<String> x = words(a);
        Set<String> y = words(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0;
        }
        long common = x.stream().filter(y::contains).count();
        return common / (double) (x.size() + y.size() - common);
    }

    /** Lower-case words longer than two letters, without stop words and with a plural "s" removed. */
    private static Set<String> words(String text) {
        if (text == null) {
            return Set.of();
        }
        return Stream.of(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(w -> w.length() > 2 && !STOP.contains(w))
                .map(w -> w.endsWith("s") && w.length() > 4 ? w.substring(0, w.length() - 1) : w)
                .collect(Collectors.toSet());
    }

    /** Vector literal for pgvector, for example {@code [0.1,0.2]}. */
    public static String literal(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
