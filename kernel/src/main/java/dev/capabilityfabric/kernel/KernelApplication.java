package dev.capabilityfabric.kernel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The deterministic kernel: owns the goal ledger, discovers capabilities, composes and runs plans,
 * and enforces policy. It never calls a chat model itself; it only uses embeddings for matching.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class KernelApplication {

    public static void main(String[] args) {
        SpringApplication.run(KernelApplication.class, args);
    }
}
