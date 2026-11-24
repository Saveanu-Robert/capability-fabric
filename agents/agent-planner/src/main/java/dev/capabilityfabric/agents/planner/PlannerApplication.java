package dev.capabilityfabric.agents.planner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Provides {@code plan.compose}: decides which discovered capabilities are relevant for a goal. */
@SpringBootApplication
public class PlannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PlannerApplication.class, args);
    }
}
