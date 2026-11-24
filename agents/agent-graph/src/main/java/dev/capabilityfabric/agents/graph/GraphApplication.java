package dev.capabilityfabric.agents.graph;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Deterministic analysis: {@code deps.analyze} and {@code priority.score}. One process may provide several
 * capabilities; no model is involved.
 */
@SpringBootApplication
public class GraphApplication {

    public static void main(String[] args) {
        SpringApplication.run(GraphApplication.class, args);
    }
}
