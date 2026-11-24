package dev.capabilityfabric.agents.coach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The coach: a reactive provider that speaks first. Nobody calls it; it follows the kernel's signals (each names a
 * fact) and the clock ({@link CoachSweep}), and leaves messages with suggestions in the person's inbox.
 */
@SpringBootApplication
@EnableScheduling
public class CoachApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoachApplication.class, args);
    }
}
