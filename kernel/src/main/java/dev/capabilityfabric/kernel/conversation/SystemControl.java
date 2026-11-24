package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.kernel.execution.GoalRunner;
import dev.capabilityfabric.kernel.execution.RunCoordinator;
import dev.capabilityfabric.kernel.execution.RunStore;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.policy.PolicyGuard;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The kill switch. Reachable two ways: through conversation ("pause everything", confirmed by the person) and
 * through the model-free emergency subjects, so it works even when no model is available.
 */
@Component
public class SystemControl {

    private static final Logger log = LoggerFactory.getLogger(SystemControl.class);

    private final PolicyGuard policy;
    private final LedgerService ledger;
    private final RunStore runs;
    private final RunCoordinator coordinator;

    public SystemControl(PolicyGuard policy, LedgerService ledger, RunStore runs, RunCoordinator coordinator) {
        this.policy = policy;
        this.ledger = ledger;
        this.runs = runs;
        this.coordinator = coordinator;
    }

    /** Stops automatic work: no capability is called until {@link #resume}; the pause is recorded as a fact. */
    public void pause(String principal, String reason) {
        policy.setPaused(true);
        ledger.recordSystem(Facts.SYSTEM_PAUSED, new Facts.SystemPaused(principal, reason), principal);
        log.warn("system PAUSED by {}", principal);
    }

    /** Resumes and restarts the goals whose latest run the pause cancelled. Returns how many restarted. */
    public int resume(String principal) {
        policy.setPaused(false);
        ledger.recordSystem(Facts.SYSTEM_RESUMED, new Facts.SystemPaused(principal, null), principal);
        int restarted = 0;
        for (RunStore.Unfinished run : runs.cancelledLatest()) {
            if (ledger.load(run.goalId()).filter(g -> g.status() != GoalStatus.CANCELLED).isPresent()) {
                if (coordinator.request(run.goalId(), "refresh".equals(run.trigger()) ? "refresh" : "refine",
                        GoalRunner.statusTokens()) != null) {
                    restarted++;
                }
            }
        }
        log.warn("system resumed by {}; {} runs restarted", principal, restarted);
        return restarted;
    }

    public boolean paused() {
        return policy.paused();
    }
}
