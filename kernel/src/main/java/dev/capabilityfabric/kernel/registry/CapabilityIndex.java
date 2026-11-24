package dev.capabilityfabric.kernel.registry;

import dev.capabilityfabric.protocol.CapabilityManifest;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Semantic discovery: ranks capabilities by how well their description matches a goal. Only the best
 * matches go into the planner prompt, which keeps prompts short (prompt processing dominates latency
 * on local models) and lets the capability set grow without growing every prompt.
 */
@Component
public class CapabilityIndex {

    private final Embeddings embeddings;

    public CapabilityIndex(Embeddings embeddings) {
        this.embeddings = embeddings;
    }

    /** A candidate and how well it matches the goal (cosine similarity, or word overlap without embeddings). */
    public record Ranked(CapabilityManifest manifest, double score) {
    }

    /**
     * The {@code limit} candidates that match the goal text best, best first and by id on ties: by embedding when
     * both texts have a vector, otherwise by word overlap.
     */
    public List<Ranked> rank(String goalText, Collection<CapabilityManifest> candidates, int limit) {
        Optional<float[]> goal = embeddings.embed(goalText);
        return candidates.stream()
                .map(m -> new Ranked(m, score(goal, goalText, m)))
                .sorted(Comparator.comparingDouble(Ranked::score).reversed()
                        .thenComparing(r -> r.manifest().id()))
                .limit(limit)
                .toList();
    }

    private double score(Optional<float[]> goal, String goalText, CapabilityManifest m) {
        if (goal.isPresent()) {
            Optional<float[]> cap = embeddings.embed(m.searchText());
            if (cap.isPresent()) {
                return Embeddings.cosine(goal.get(), cap.get());
            }
        }
        return Embeddings.lexicalSimilarity(goalText, m.searchText());
    }
}
