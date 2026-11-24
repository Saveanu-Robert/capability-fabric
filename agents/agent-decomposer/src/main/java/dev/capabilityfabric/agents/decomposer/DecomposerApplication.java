package dev.capabilityfabric.agents.decomposer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Provides {@code goal.decompose}: turns a natural-language objective into tasks with dependencies. */
@SpringBootApplication
public class DecomposerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DecomposerApplication.class, args);
    }
}
