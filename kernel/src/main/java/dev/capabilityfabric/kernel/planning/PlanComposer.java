package dev.capabilityfabric.kernel.planning;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.execution.CapabilityInvoker;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.registry.CapabilityIndex;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.DecisionRecord;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Plans;
import dev.capabilityfabric.protocol.Plans.Plan;
import dev.capabilityfabric.protocol.Plans.PlanDraft;
import dev.capabilityfabric.protocol.TaskStatus;
import dev.capabilityfabric.protocol.Vocabulary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Composes the system plan for a goal from the capabilities that are live right now.
 * <ul>
 *   <li>create/refine: semantic discovery picks candidates, the {@code plan.compose} capability (an LLM)
 *       chooses the relevant ones, the resolver completes and orders them. Without a planner that reads the
 *       goal's text, or if it fails, a deterministic template uses every candidate.</li>
 *   <li>refresh (after a change): only deterministic capabilities that consume a changed token, and the
 *       capabilities downstream of them, run again. No model is involved.</li>
 * </ul>
 */
@Component
public class PlanComposer {

    public static final String PLANNER = "plan.compose";

    private final CapabilityRegistry registry;
    private final CapabilityIndex index;
    private final CapabilityInvoker invoker;
    private final KernelProperties properties;
    private final PlanResolver resolver = new PlanResolver();

    public PlanComposer(CapabilityRegistry registry, CapabilityIndex index, CapabilityInvoker invoker,
                        KernelProperties properties) {
        this.registry = registry;
        this.index = index;
        this.invoker = invoker;
        this.properties = properties;
    }

    /**
     * A composed plan, the planner's model calls ({@code plannerDecisions}, empty without a planner call) and the
     * planner instance that made them ({@code null} when no planner answered).
     */
    public record Composed(Plan plan, List<DecisionRecord> plannerDecisions, CapabilityManifest planner) {
    }

    /**
     * The plan for one run. {@code changedTokens} select the capabilities of a refresh; {@code pinned} capabilities
     * are added whatever the planner chose (when they are live). The resolver then completes, orders and caps it.
     * Each note of the plan is one line of at most {@link CapabilityInvoker#MAX_REASON} characters.
     */
    public Composed compose(GoalState state, String runId, String trigger, Set<String> changedTokens,
                            Set<String> pinned) {
        Map<String, CapabilityManifest> available = new LinkedHashMap<>(registry.invokedCapabilities());
        available.remove(PLANNER);
        Set<String> present = PlanResolver.presentTokens(state.toContext(properties.today()));
        List<String> notes = new ArrayList<>();
        List<DecisionRecord> decisions = List.of();
        CapabilityManifest planner = null;
        Plans.Source source;
        List<String> selected;

        if ("refresh".equals(trigger)) {
            source = Plans.Source.REFRESH;
            selected = refreshSelection(available, changedTokens);
        } else {
            List<CapabilityManifest> candidates = index.rank(state.text(), available.values().stream()
                    .filter(m -> !m.produces().isEmpty()).toList(), properties.plannerCandidates())
                    .stream().map(CapabilityIndex.Ranked::manifest).toList();
            selected = List.of();
            source = Plans.Source.TEMPLATE;
            if (properties.useLlmPlanner() && registry.has(PLANNER) && !candidates.isEmpty()) {
                var invocation = invoker.invoke(state.goalId(), runId, -1, PLANNER, 1, null,
                        plannerParams(state, candidates), Set.of(), PlanComposer::readsPlannerInput);
                Optional<PlanDraft> draft = invocation.ok() ? draft(invocation.result().output()) : Optional.empty();
                if (draft.isPresent()) {
                    Set<String> allowed = new LinkedHashSet<>();
                    candidates.forEach(c -> allowed.add(c.id()));
                    selected = draft.get().selected().stream().map(Plans.Selection::capability)
                            .filter(allowed::contains).distinct().toList();
                    source = Plans.Source.LLM;
                    if (draft.get().rationale() != null) {
                        notes.add("planner: " + draft.get().rationale());
                    }
                } else {
                    notes.add("planner unavailable: " + (invocation.ok() ? "its answer is not a plan draft"
                            : invocation.failureReason()));
                }
                if (invocation.result() != null) {
                    decisions = invocation.result().decisions();
                    planner = invocation.manifest();
                }
            }
            if (selected.isEmpty()) {
                source = Plans.Source.TEMPLATE;
                selected = candidates.stream().map(CapabilityManifest::id).toList();
                if (!candidates.isEmpty()) {
                    notes.add("template plan: every matching capability");
                }
            }
        }

        if (!pinned.isEmpty()) {
            List<String> withPinned = new ArrayList<>(selected);
            pinned.stream().filter(available::containsKey).filter(p -> !withPinned.contains(p)).forEach(p -> {
                withPinned.add(p);
                notes.add("included " + p + " because it was asked for");
            });
            selected = withPinned;
        }
        PlanResolver.Resolution resolution = resolver.resolve(selected, available, present,
                properties.maxStepsPerPlan());
        notes.addAll(resolution.notes());
        // The planner's rationale and a failure quote what an agent sent; every note is one capped line.
        Plan plan = new Plan(UUID.randomUUID().toString(), state.goalId(), runId, source, resolution.steps(),
                notes.stream().map(CapabilityInvoker::capped).toList());
        return new Composed(plan, decisions, planner);
    }

    /** The planner's answer as a draft, or empty when it is missing, JSON null or not shaped like a draft. */
    static Optional<PlanDraft> draft(JsonNode output) {
        if (output == null || output.isNull() || !output.isObject()) {
            return Optional.empty();
        }
        try {
            PlanDraft draft = Json.MAPPER.treeToValue(output, PlanDraft.class);
            return draft == null || draft.selected() == null ? Optional.empty() : Optional.of(draft);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Deterministic capabilities that consume a changed token, plus everything downstream of them. */
    static List<String> refreshSelection(Map<String, CapabilityManifest> available, Set<String> changed) {
        Set<String> tokens = new LinkedHashSet<>(changed);
        Set<String> selected = new LinkedHashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (CapabilityManifest m : available.values()) {
                if (selected.contains(m.id()) || m.kind() != CapabilityManifest.Kind.DETERMINISTIC
                        || m.produces().isEmpty()) {
                    continue;
                }
                if (m.consumes().stream().anyMatch(tokens::contains)) {
                    selected.add(m.id());
                    tokens.addAll(m.produces());
                    grew = true;
                }
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Whether a planner instance may be given what {@link #plannerParams} sends. The goal's text is sent, so only an
     * instance whose manifest reads it ({@link Vocabulary#GOAL_TEXT}) gets the call: the registry lists a manifest
     * only while it stays within the contract the owner admitted, so the owner was shown that it reads the goal's
     * text. The due date and the count of tasks are part of the structure every admitted agent sees, and the
     * candidates describe capabilities, not the person's work.
     */
    static boolean readsPlannerInput(CapabilityManifest planner) {
        return CapabilityInvoker.reads(planner).contains(Vocabulary.GOAL_TEXT);
    }

    /** The planner's input: the goal's text, due date and count of tasks still wanted, and the candidates. */
    private static ObjectNode plannerParams(GoalState state, List<CapabilityManifest> candidates) {
        ObjectNode params = Json.object();
        ObjectNode goal = params.putObject("goal");
        goal.put("text", state.text());
        goal.put("dueDate", state.dueDate() == null ? null : state.dueDate().toString());
        // Dropped tasks are no longer part of the goal's work, so a goal whose tasks were all dropped has none.
        goal.put("taskCount", state.tasks().stream().filter(t -> t.status() != TaskStatus.DROPPED).count());
        ArrayNode list = params.putArray("candidates");
        for (CapabilityManifest m : candidates) {
            ObjectNode c = list.addObject();
            c.put("id", m.id());
            c.put("name", m.name());
            c.put("description", m.description());
            c.put("kind", m.kind().name());
            c.set("consumes", Json.toTree(m.consumes()));
            c.set("produces", Json.toTree(m.produces()));
        }
        return params;
    }
}
