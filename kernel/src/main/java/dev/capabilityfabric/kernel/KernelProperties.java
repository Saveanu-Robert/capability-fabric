package dev.capabilityfabric.kernel;

import dev.capabilityfabric.protocol.AutonomyLevel;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

/**
 * Kernel limits and policy (prefix {@code fabric.kernel}).
 *
 * @param fixedToday        pin "today" (replays and tests); empty means the system clock
 * @param maxStepsPerPlan   a composed plan is cut to this many steps
 * @param maxTasksPerGoal   the ledger refuses effects that would give a goal more tasks
 * @param maxLlmCallsPerRun model calls one run may make; model steps after that are skipped
 * @param maxRunsPerGoalPerHour runs (refreshes not counted) one goal may start per hour
 * @param callTimeout       how long a capability call waits, plus twice the latency the provider expects
 * @param cfpWindow         how long a call for proposals collects bids
 * @param approvalTimeout   how long a step waits for the person's approval before it counts as rejected
 * @param maxAutoInvoked    highest autonomy a planned step may have without human approval
 * @param maxAutoReactive   highest autonomy a reactive proposal may have to be applied automatically
 * @param approvalRequired  capability ids that always need human approval
 * @param denied            capability ids that are never invoked
 * @param plannerCandidates capabilities semantic discovery offers the planner per goal
 * @param useLlmPlanner     whether {@code plan.compose} chooses among the candidates (otherwise all are used)
 * @param embeddings        whether an embedding model is used for matching and recall
 * @param gatewayUrl        the address remote devices use to reach the gateway (for example
 *                          {@code https://fabric.example.org:8443}); shown in invitations and pairing instructions
 */
@ConfigurationProperties("fabric.kernel")
public record KernelProperties(
        LocalDate fixedToday,
        @DefaultValue("12") int maxStepsPerPlan,
        @DefaultValue("40") int maxTasksPerGoal,
        @DefaultValue("30") int maxLlmCallsPerRun,
        @DefaultValue("30") int maxRunsPerGoalPerHour,
        @DefaultValue("300s") Duration callTimeout,
        @DefaultValue("800ms") Duration cfpWindow,
        @DefaultValue("30m") Duration approvalTimeout,
        @DefaultValue("L2") AutonomyLevel maxAutoInvoked,
        @DefaultValue("L1") AutonomyLevel maxAutoReactive,
        @DefaultValue("8") int plannerCandidates,
        @DefaultValue("true") boolean useLlmPlanner,
        @DefaultValue("true") boolean embeddings,
        List<String> approvalRequired,
        List<String> denied,
        @DefaultValue("https://localhost:8443") String gatewayUrl) {

    public KernelProperties {
        approvalRequired = approvalRequired == null ? List.of() : List.copyOf(approvalRequired);
        denied = denied == null ? List.of() : List.copyOf(denied);
    }

    public LocalDate today() {
        return fixedToday != null ? fixedToday : LocalDate.now();
    }
}
