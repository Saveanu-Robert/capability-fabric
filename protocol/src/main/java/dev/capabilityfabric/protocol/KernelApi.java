package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Request and response payloads of the kernel's query and command subjects. */
public final class KernelApi {

    private KernelApi() {
    }

    /** A pairing code or an invitation, presented by a device signing with its own key. */
    public record Claim(String code, String deviceName) {
    }

    /** A request for finished tasks similar to {@code text}, at most {@code limit} of them. */
    public record MemoryQuery(String text, int limit) {
    }

    /**
     * The kernel's public keys: {@code key} (Ed25519) signs its replies, {@code sealKey} (X25519) receives sealed
     * requests such as pairing and invitation codes.
     */
    public record Hello(String key, String sealKey) {
    }

    /** Registry entries an agent renews (its signed manifests), or withdraws. */
    public record Registration(List<SignedManifest> entries) {
        public Registration {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** Reference to a goal or task by full id or unique id prefix. */
    public record Ref(String ref) {
    }

    /** One goal in a list. The short form agents get carries only id, status, dueDate and updatedAt. */
    public record GoalSummary(String id, String text, GoalStatus status, LocalDate dueDate, int taskCount,
                              int doneCount, Instant createdAt, Instant updatedAt) {
    }

    /** One plan step as it ran: which provider answered, how long it took, its outcome and any detail. */
    public record StepView(int index, String capability, String outcome, String provider, long durationMs,
                           int llmCalls, String detail) {
    }

    /** One plan run of a goal: what triggered it, where its plan came from and how each step went. */
    public record RunView(String runId, String trigger, String source, String outcome, Instant startedAt,
                          Long durationMs, int llmCalls, List<StepView> steps) {
    }

    public record GoalDetails(GoalSummary goal, Map<String, JsonNode> annotations, List<GoalContext.TaskView> tasks,
                              List<RunView> runs) {
    }

    public record CapabilityView(String id, String version, String name, String provider, String kind, String mode,
                                 String autonomy, int instances, List<String> instanceIds, List<String> consumes,
                                 List<String> produces, String description, String model) {
    }

    public record ApprovalView(String id, String goalId, String capability, String autonomy, String reason,
                               String status, Instant createdAt) {
    }

    public record DecisionView(String id, String capability, String purpose, String model, boolean valid,
                               int attempt, long latencyMs, Integer promptTokens, Integer completionTokens,
                               boolean replayed, Instant at) {
    }

    public record ControlCommand(String principal, String reason) {
    }

    /** A hash of a goal's ledger, to verify that its history is intact. */
    public record LedgerHash(String goalId, String hash, int taskCount) {
    }

    public record SystemStatus(boolean paused, int liveCapabilities, int activeRuns, String embeddingModel) {
    }

    /** A finished task similar to the text asked about, with its estimated and real hours (either may be unknown). */
    public record RecallItem(String goalText, String taskTitle, Double estimatedHours, Double actualHours,
                             double similarity) {
    }

    public record RecallResult(List<RecallItem> items) {
    }
}
