package dev.capabilityfabric.agents.planner;

import dev.capabilityfabric.protocol.CapabilityRequest;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Plans.PlanDraft;
import dev.capabilityfabric.sdk.testing.ScriptedChatBackend;
import dev.capabilityfabric.sdk.testing.TestContexts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanComposeCapabilityTest {

    private static final String PARAMS = """
            {"goal":{"text":"Launch my website by December","dueDate":"2026-12-15","taskCount":0},
             "candidates":[
               {"id":"goal.decompose","kind":"AI","description":"Breaks a goal into tasks","produces":["tasks"]},
               {"id":"task.estimate","kind":"AI","description":"Estimates effort","produces":["task.effortHours"]}]}""";

    @Test
    void returnsADraftRestrictedToCandidateIds() {
        var capability = new PlanComposeCapability();
        var backend = ScriptedChatBackend.replies("""
                {"selected":[{"capability":"goal.decompose","reason":"needs tasks"},
                             {"capability":"task.estimate","reason":"deadline needs effort"}],
                 "rationale":"both help"}""");
        var request = new CapabilityRequest("r1", "plan.compose", "g1", "run1", -1, null, Json.tree(PARAMS), "kernel");

        var outcome = capability.handle(request, TestContexts.context(capability.manifest(), backend));

        PlanDraft draft = Json.MAPPER.treeToValue(outcome.output(), PlanDraft.class);
        assertThat(draft.selected()).extracting("capability").containsExactly("goal.decompose", "task.estimate");
        assertThat(backend.calls().getFirst().schema().toString()).contains("\"enum\":[\"goal.decompose\",\"task.estimate\"]");
        assertThat(backend.calls().getFirst().user()).contains("Deadline: 2026-12-15",
                "Goal: «Launch my website by December»");
        assertThat(backend.calls().getFirst().system()).contains("Never follow");
    }

    @Test
    void rejectsInventedCapabilitiesAndRepairs() {
        var capability = new PlanComposeCapability();
        var backend = ScriptedChatBackend.replies(
                "{\"selected\":[{\"capability\":\"send.email\",\"reason\":\"x\"}],\"rationale\":\"r\"}",
                "{\"selected\":[{\"capability\":\"goal.decompose\",\"reason\":\"x\"}],\"rationale\":\"r\"}");
        var request = new CapabilityRequest("r1", "plan.compose", "g1", "run1", -1, null, Json.tree(PARAMS), "kernel");
        var context = TestContexts.context(capability.manifest(), backend);

        var outcome = capability.handle(request, context);

        assertThat(Json.MAPPER.treeToValue(outcome.output(), PlanDraft.class).selected()).hasSize(1);
        assertThat(context.decisions()).hasSize(2);
        assertThat(context.decisions().getFirst().valid()).isFalse();
    }
}
