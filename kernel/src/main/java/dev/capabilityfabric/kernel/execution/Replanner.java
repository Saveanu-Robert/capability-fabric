package dev.capabilityfabric.kernel.execution;

import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Vocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * A goal whose plan was empty (no capability could break it into tasks) is not left failed for ever: when a
 * capability that creates tasks joins, every such goal is planned again, however many goals there are. A goal whose
 * tasks were all dropped counts as one without tasks, as it does when a run settles its status.
 */
@Component
public class Replanner {

    private static final Logger log = LoggerFactory.getLogger(Replanner.class);

    private final LedgerService ledger;
    private final RunCoordinator coordinator;

    public Replanner(LedgerService ledger, RunCoordinator coordinator) {
        this.ledger = ledger;
        this.coordinator = coordinator;
    }

    /** A capability joined: if it creates tasks, every failed goal with no live (not dropped) task is planned again. */
    @EventListener
    public void joined(CapabilityRegistry.Joined event) {
        if (!event.manifest().produces().contains(Vocabulary.TASKS)) {
            return;
        }
        for (GoalState g : ledger.openGoals()) {
            if (g.status() == GoalStatus.FAILED && GoalRunner.liveTasks(g).isEmpty()) {
                log.info("{} joined; planning \"{}\" again", event.manifest().id(), g.text());
                coordinator.request(g.goalId(), "refine", Set.of(Vocabulary.TASKS));
            }
        }
    }
}
