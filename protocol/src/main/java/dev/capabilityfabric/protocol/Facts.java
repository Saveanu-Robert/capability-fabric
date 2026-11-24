package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Fact types and payloads. Facts are past-tense and immutable; the kernel is their only publisher.
 * The fact type doubles as the subject suffix: type {@code task.added} is published on {@code fact.task.added}.
 */
public final class Facts {

    public static final String GOAL_CREATED = "goal.created";
    public static final String GOAL_STATUS_CHANGED = "goal.status-changed";
    public static final String GOAL_ANNOTATED = "goal.annotated";
    public static final String GOAL_RESCHEDULED = "goal.rescheduled";
    public static final String RULE_RATIFIED = "rule.ratified";
    public static final String RULE_REPEALED = "rule.repealed";
    public static final String INBOX_POSTED = "inbox.posted";
    public static final String INBOX_ANSWERED = "inbox.answered";
    public static final String PROFILE_CORRECTED = "profile.corrected";
    public static final String TASK_ADDED = "task.added";
    public static final String TASK_UPDATED = "task.updated";
    public static final String TASK_ANNOTATED = "task.annotated";
    public static final String TASK_STATUS_CHANGED = "task.status-changed";
    public static final String DEPENDENCY_ADDED = "task.dependency-added";
    public static final String PLAN_COMPOSED = "plan.composed";
    public static final String STEP_FINISHED = "plan.step-finished";
    public static final String RUN_FINISHED = "plan.run-finished";
    public static final String APPROVAL_REQUESTED = "approval.requested";
    public static final String APPROVAL_DECIDED = "approval.decided";
    public static final String PROPOSAL_DECIDED = "proposal.decided";
    public static final String CAPABILITY_JOINED = "capability.joined";
    public static final String CAPABILITY_LEFT = "capability.left";
    public static final String CAPABILITY_GROWN = "capability.grown";
    public static final String CAPABILITY_KEPT = "capability.kept";
    public static final String CAPABILITY_RETIRED = "capability.retired";
    public static final String CAPABILITY_QUARANTINED = "capability.quarantined";
    public static final String DEVICE_PAIRED = "security.device-paired";
    public static final String PROVIDER_ADMISSION_REQUESTED = "security.admission-requested";
    public static final String PROVIDER_ADMITTED = "security.provider-admitted";
    public static final String INVITATION_CREATED = "security.invitation-created";
    public static final String INVITATION_CLAIMED = "security.invitation-claimed";
    public static final String ACCESS_REVOKED = "security.access-revoked";
    public static final String REQUEST_REFUSED = "security.request-refused";
    public static final String SYSTEM_PAUSED = "system.paused";
    public static final String SYSTEM_RESUMED = "system.resumed";

    private Facts() {
    }

    public record GoalCreated(String goalId, String text, LocalDate dueDate, String principal) {
    }

    public record GoalStatusChanged(String goalId, GoalStatus from, GoalStatus to, String reason) {
    }

    public record GoalAnnotated(String goalId, Map<String, JsonNode> annotations, String by) {
    }

    public record GoalRescheduled(String goalId, LocalDate from, LocalDate to, String reason, String by) {
    }

    public record RuleChanged(String ruleId, String text, String by) {
    }

    public record InboxChanged(String messageId, String goalId, String kind, String status, String by) {
    }

    public record ProfileCorrected(String key, JsonNode value, String by) {
    }

    public record TaskAdded(String goalId, String taskId, String key, String title, String description,
                            LocalDate dueDate, List<String> dependsOn, Double effortHours, String by) {
    }

    /** Core field changes; only non-null fields changed. */
    public record TaskUpdated(String goalId, String taskId, Double effortHours, Integer priority, Integer rank,
                              LocalDate dueDate, String by) {
    }

    public record TaskAnnotated(String goalId, String taskId, Map<String, JsonNode> annotations, String by) {
    }

    public record TaskStatusChanged(String goalId, String taskId, TaskStatus from, TaskStatus to, String reason,
                                    Double actualHours, String by) {
    }

    public record DependencyAdded(String goalId, String taskId, String dependsOnTaskId, String by) {
    }

    public record PlanComposed(String goalId, String runId, String trigger, Plans.Plan plan) {
    }

    public enum StepOutcome { COMPLETED, SKIPPED, FAILED, REJECTED }

    /** {@code requestId} links the step to the facts its effects caused (their causation id). */
    public record StepFinished(String goalId, String runId, int index, String capability, StepOutcome outcome,
                               String provider, String instanceId, long durationMs, int llmCalls, String detail,
                               String requestId) {
    }

    public enum RunOutcome { COMPLETED, DEGRADED, FAILED, CANCELLED }

    public record RunFinished(String goalId, String runId, String trigger, RunOutcome outcome, long durationMs,
                              int llmCalls, int stepsCompleted, int stepsSkipped, int stepsFailed) {
    }

    public record ApprovalRequested(String approvalId, String goalId, String runId, int step, String capability,
                                    AutonomyLevel autonomy, String reason) {
    }

    public record ApprovalDecided(String approvalId, String goalId, boolean approved, String by) {
    }

    public record ProposalDecided(String proposalId, String goalId, String capability, String instanceId,
                                  boolean accepted, String reason) {
    }

    public record CapabilityJoined(String capability, String version, String provider, String instanceId,
                                   CapabilityManifest.Kind kind, CapabilityManifest.Mode mode,
                                   List<String> produces) {
    }

    public record CapabilityLeft(String capability, String version, String provider, String instanceId,
                                 String reason) {
    }

    public record SystemPaused(String by, String reason) {
    }

    /** A capability grown, kept, retired or quarantined; {@code reason} explains why. */
    public record CapabilityLifecycle(String capability, String provider, String status, String reason, String by) {
    }

    /** A security event: who (key id and name), what, and why. */
    public record SecurityEvent(String keyId, String name, String what, String detail) {
    }
}
