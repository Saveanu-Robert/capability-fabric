package dev.capabilityfabric.agents.forge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The forge: designs new capabilities as data ({@code capability.design}) and hosts every grown capability the
 * kernel has accepted, without any new code. The system extends itself through this process.
 */
@SpringBootApplication
public class ForgeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ForgeApplication.class, args);
    }
}
