package dev.capabilityfabric.protocol;

import java.util.List;

/** Plan messages. A plan is data: persisted, inspectable and replayable. */
public final class Plans {

    private Plans() {
    }

    /** What the planner capability returns: which discovered capabilities are relevant, and why. */
    public record PlanDraft(List<Selection> selected, String rationale) {
        public PlanDraft {
            selected = selected == null ? List.of() : List.copyOf(selected);
        }
    }

    /** One capability the planner chose for the goal, with its reason in plain words. */
    public record Selection(String capability, String reason) {
    }

    /** Where a plan's steps came from. */
    public enum Source {
        /** Capabilities selected by the planner model, completed and ordered by the resolver. */
        LLM,
        /**
         * Deterministic fallback: every candidate semantic discovery found for the goal, completed and ordered by the
         * resolver.
         */
        TEMPLATE,
        /** Deterministic re-run of capabilities affected by a change (status, due date, new facts). */
        REFRESH
    }

    /** A validated, ordered plan the kernel executes step by step. */
    public record Plan(String planId, String goalId, String runId, Source source, List<PlanStep> steps,
                       List<String> notes) {
        public Plan {
            steps = steps == null ? List.of() : List.copyOf(steps);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * One step. {@code optional} steps may be skipped when their provider fails, because nothing
     * later in the plan requires what they produce. {@code after} lists the steps this one waits for.
     */
    public record PlanStep(int index, String capability, int major, AutonomyLevel autonomy, boolean optional,
                           List<String> after, String reason) {
        public PlanStep {
            after = after == null ? List.of() : List.copyOf(after);
        }
    }
}
