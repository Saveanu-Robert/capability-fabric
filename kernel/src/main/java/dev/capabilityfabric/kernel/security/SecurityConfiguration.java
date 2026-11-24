package dev.capabilityfabric.kernel.security;

import dev.capabilityfabric.protocol.Security;
import dev.capabilityfabric.sdk.FabricSecurity;
import dev.capabilityfabric.sdk.FabricProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The kernel's identity on the fabric: it trusts its own key and needs no admission. */
@Configuration
public class SecurityConfiguration {

    /**
     * The kernel's message security: its Ed25519 identity (the configured key file, or {@code kernel.key} in the
     * keys folder, created when missing), trusting its own key as the kernel's.
     */
    @Bean
    public FabricSecurity fabricSecurity(FabricProperties properties) {
        var settings = properties.security();
        return FabricSecurity.selfTrusted(settings.enabled(), Security.loadOrCreate(settings.keyPath("kernel")));
    }
}
