package dev.capabilityfabric.agents.sentinel;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A reactive provider (blackboard knowledge source): nobody calls it. It follows the kernel's signals (each names a
 * fact) and the clock ({@link SentinelSweep}), notices deadline risk and proposes annotations, which the kernel
 * accepts only within the reactive autonomy limit.
 */
@SpringBootApplication
@EnableScheduling
public class SentinelApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelApplication.class, args);
    }
}
