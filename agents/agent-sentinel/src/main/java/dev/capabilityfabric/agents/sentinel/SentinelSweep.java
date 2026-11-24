package dev.capabilityfabric.agents.sentinel;

import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.Proposal;
import dev.capabilityfabric.protocol.Reply;
import dev.capabilityfabric.sdk.AgentRuntime;
import dev.capabilityfabric.sdk.FabricClient;
import dev.capabilityfabric.sdk.ReactiveHandler.ProposedChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Looks at every open goal on a timer (hourly by default, so at least once on every new day), for what no fact
 * announces: the working days before a due date run out as days pass, and a value proposed yesterday is out of
 * date today. Proposes only values that changed, like the reaction to a fact does.
 */
@Component
public class SentinelSweep {

    private static final Logger log = LoggerFactory.getLogger(SentinelSweep.class);

    private final DeadlineWatch watch;
    private final FabricClient fabric;
    private final ObjectProvider<AgentRuntime> runtime;

    public SentinelSweep(DeadlineWatch watch, FabricClient fabric, ObjectProvider<AgentRuntime> runtime) {
        this.watch = watch;
        this.fabric = fabric;
        this.runtime = runtime;
    }

    /** Assesses every open goal and proposes the changed values; does nothing before the runtime has joined. */
    @Scheduled(initialDelayString = "${fabric.sentinel.first-sweep:PT45S}", fixedDelayString = "${fabric.sentinel.sweep:PT1H}")
    public synchronized void sweep() {
        AgentRuntime agent = runtime.getIfAvailable();
        if (agent == null) {
            return;
        }
        Optional<CapabilityManifest> manifest = agent.manifests().stream()
                .filter(m -> m.id().equals(watch.manifest().id())).findFirst();
        Optional<List<FabricClient.ListedGoal>> goals = fabric.goals();
        if (manifest.isEmpty() || goals.isEmpty()) {
            return;
        }
        int proposed = 0;
        for (FabricClient.ListedGoal goal : goals.get()) {
            if (!goal.open()) {
                continue;
            }
            Optional<ProposedChange> change = fabric.goalContext(goal.id()).flatMap(watch::assess);
            if (change.isPresent()) {
                Reply reply = fabric.propose(proposal(change.get(), manifest.get()));
                proposed++;
                if (!reply.ok()) {
                    log.info("deadline risk for goal {} not accepted: {}", goal.id(), reply.error());
                }
            }
        }
        log.debug("swept {} goals, {} proposals", goals.get().size(), proposed);
    }

    /** The proposal for one change, in the name of the advertised instance; no fact caused it. */
    static Proposal proposal(ProposedChange change, CapabilityManifest manifest) {
        return new Proposal(UUID.randomUUID().toString(), manifest.id(), manifest.provider(), manifest.instanceId(),
                change.goalId(), change.effects().taskUpdates(), change.effects().goalAnnotations(), change.reason(),
                List.of(), null);
    }
}
