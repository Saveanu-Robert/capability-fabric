package dev.capabilityfabric.agents.estimator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Provides {@code task.estimate}. Start it while the system is running: the kernel discovers it, the
 * planner starts selecting it, and priorities and critical paths start using its estimates, with no
 * change to any other component.
 */
@SpringBootApplication
public class EstimatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EstimatorApplication.class, args);
    }
}
