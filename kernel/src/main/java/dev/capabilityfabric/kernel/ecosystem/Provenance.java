package dev.capabilityfabric.kernel.ecosystem;

import dev.capabilityfabric.kernel.ledger.LedgerEvent;
import dev.capabilityfabric.protocol.Facts;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which capability (and provider) caused each fact of a goal. Facts carry the id of the capability call or
 * proposal that caused them; step and proposal facts map those ids back to capabilities. A step's request id is
 * chosen by the kernel, so its mapping always stands; a proposal only maps an id that nothing mapped before, so a
 * later proposal reusing an earlier call's or proposal's id cannot claim that work for its own capability.
 */
public final class Provenance {

    /** The capability and provider behind a fact; {@code provider} is {@code null} when it is not known. */
    public record Source(String capability, String provider) {
    }

    /** Fact types that change goal content (as opposed to history such as plan or step facts). */
    public static final Set<String> CONTENT = Set.of(Facts.TASK_ADDED, Facts.TASK_UPDATED, Facts.TASK_ANNOTATED,
            Facts.DEPENDENCY_ADDED, Facts.GOAL_ANNOTATED);

    private final Map<String, Source> byCause = new HashMap<>();

    /** Reads a goal's complete fact history, in order. */
    public Provenance(List<LedgerEvent> events) {
        for (LedgerEvent e : events) {
            if (Facts.STEP_FINISHED.equals(e.type())) {
                Facts.StepFinished step = e.payload(Facts.StepFinished.class);
                if (step.requestId() != null) {
                    byCause.put(step.requestId(), new Source(step.capability(), step.provider()));
                }
            } else if (Facts.PROPOSAL_DECIDED.equals(e.type())) {
                Facts.ProposalDecided decided = e.payload(Facts.ProposalDecided.class);
                if (decided.proposalId() != null) {
                    byCause.putIfAbsent(decided.proposalId(), new Source(decided.capability(), provider(e)));
                }
            }
        }
        // Proposal facts are recorded by the kernel; take the provider from the content facts they caused.
        for (LedgerEvent e : events) {
            Source s = e.causationId() == null ? null : byCause.get(e.causationId());
            if (s != null && s.provider() == null && e.principal() != null && e.principal().startsWith("agent:")) {
                byCause.put(e.causationId(), new Source(s.capability(), provider(e)));
            }
        }
    }

    /** Who caused this fact, or empty when a person or the kernel did. */
    public Optional<Source> of(LedgerEvent event) {
        return event.causationId() == null ? Optional.empty() : Optional.ofNullable(byCause.get(event.causationId()));
    }

    /** Whether any fact of the goal came from a call or proposal of this capability. */
    public boolean contributed(String capability) {
        return byCause.values().stream().anyMatch(s -> s.capability().equals(capability));
    }

    /** Every capability that caused a fact of the goal. */
    public Set<String> capabilities() {
        return byCause.values().stream().map(Source::capability).collect(Collectors.toSet());
    }

    private static String provider(LedgerEvent e) {
        String p = e.principal();
        if (p == null || !p.startsWith("agent:")) {
            return null;
        }
        int slash = p.indexOf('/');
        return slash < 0 ? p.substring(6) : p.substring(6, slash);
    }
}
