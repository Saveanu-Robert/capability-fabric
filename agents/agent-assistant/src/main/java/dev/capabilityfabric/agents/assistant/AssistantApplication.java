package dev.capabilityfabric.agents.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Provides {@code intent.interpret}, the system's natural-language front door: every sentence a person says reaches
 * the system through this capability, and goals and tasks have no command endpoints of their own besides the yes or no
 * to an inbox suggestion. Also provides {@code policy.compile}, which turns a rule sentence into a rule draft.
 */
@SpringBootApplication
public class AssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssistantApplication.class, args);
    }
}
