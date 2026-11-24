package dev.capabilityfabric.kernel.policy;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.protocol.AutonomyLevel;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Effects;
import dev.capabilityfabric.protocol.Vocabulary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded autonomy. Every planned step and every reactive proposal passes here: denied capabilities never run, steps
 * above the automatic autonomy level wait for a human, and the kill switch stops all dispatching. The switch survives
 * restarts.
 */
@Component
public class PolicyGuard {

    /** What happens to a planned step: it runs, it waits for the person's approval, or it never runs. */
    public enum Verdict { ALLOW, APPROVAL, DENY }

    private final KernelProperties properties;
    private final JdbcClient jdbc;
    private final AtomicBoolean paused = new AtomicBoolean();

    /** Reads the kill switch as it was left before the last stop. */
    public PolicyGuard(KernelProperties properties, JdbcClient jdbc) {
        this.properties = properties;
        this.jdbc = jdbc;
        jdbc.sql("SELECT value FROM flags WHERE name = 'paused'").query(String.class).optional()
                .ifPresent(v -> paused.set(Boolean.parseBoolean(v)));
    }

    /**
     * A denied capability never runs; one listed as needing approval, or whose autonomy is above the automatic
     * limit for planned steps, waits for the person.
     */
    public Verdict checkStep(CapabilityManifest manifest) {
        if (properties.denied().contains(manifest.id())) {
            return Verdict.DENY;
        }
        if (requiresApproval(manifest) || manifest.autonomy().exceeds(properties.maxAutoInvoked())) {
            return Verdict.APPROVAL;
        }
        return Verdict.ALLOW;
    }

    /** The capability is listed as always needing the person's approval. */
    public boolean requiresApproval(CapabilityManifest manifest) {
        return properties.approvalRequired().contains(manifest.id());
    }

    /**
     * Returns the reason a reactive proposal cannot be applied automatically, if any. Proposals are not queued for
     * approval, so a capability whose steps always need the person's approval has its proposals held back too.
     */
    public Optional<String> checkProposal(CapabilityManifest manifest, Effects effects) {
        if (properties.denied().contains(manifest.id())) {
            return Optional.of(manifest.id() + " is denied by policy");
        }
        if (requiresApproval(manifest)) {
            return Optional.of(manifest.id() + " needs the person's approval, so its proposals are not applied");
        }
        AutonomyLevel level = effects.producedTokens().stream().anyMatch(Vocabulary.STRUCTURAL::contains)
                ? AutonomyLevel.L2 : AutonomyLevel.L1;
        if (manifest.sideEffect() != CapabilityManifest.SideEffect.NONE) {
            level = AutonomyLevel.L3;
        }
        if (level.exceeds(properties.maxAutoReactive())) {
            return Optional.of("proposal needs autonomy " + level + " but reactive changes are limited to "
                    + properties.maxAutoReactive());
        }
        return Optional.empty();
    }

    /** Whether the kill switch is on: no plan is composed and no capability is called while it is. */
    public boolean paused() {
        return paused.get();
    }

    /** Turns the kill switch on or off and stores it, so it survives a restart. */
    public void setPaused(boolean value) {
        paused.set(value);
        jdbc.sql("""
                INSERT INTO flags (name, value) VALUES ('paused', :v)
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value""").param("v", Boolean.toString(value)).update();
    }
}
