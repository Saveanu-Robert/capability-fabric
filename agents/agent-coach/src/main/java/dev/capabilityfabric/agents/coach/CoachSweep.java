package dev.capabilityfabric.agents.coach;

import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.Subjects;
import dev.capabilityfabric.sdk.AgentRuntime;
import dev.capabilityfabric.sdk.FabricClient;
import dev.capabilityfabric.sdk.InvocationContext;
import dev.capabilityfabric.sdk.model.StructuredModel;
import io.nats.client.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Looks at every open goal on a timer, for what no fact announces: work due today, overdue work and goals nobody
 * has touched for a while. It also looks at every goal again when the person corrects their profile.
 */
@Component
public class CoachSweep {

    private static final Logger log = LoggerFactory.getLogger(CoachSweep.class);

    private final Coach coach;
    private final FabricClient fabric;
    private final StructuredModel model;
    private final ObjectProvider<AgentRuntime> runtime;
    private final int staleDays;

    public CoachSweep(Coach coach, FabricClient fabric, StructuredModel model, ObjectProvider<AgentRuntime> runtime,
                      @Value("${fabric.coach.stale-days:7}") int staleDays) {
        this.coach = coach;
        this.fabric = fabric;
        this.model = model;
        this.runtime = runtime;
        this.staleDays = staleDays;
    }

    /**
     * A corrected profile changes what fits before a deadline, so every goal is looked at again. The kernel's signal
     * for the correction is enough: it is followed only when the kernel signed it, and signals that arrive while a
     * sweep is already waiting add no second one.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void listen() {
        fabric.connection().createDispatcher(this::onProfileCorrected)
                .subscribe(Subjects.signal(Facts.PROFILE_CORRECTED));
    }

    private final AtomicBoolean sweepWaiting = new AtomicBoolean();

    void onProfileCorrected(Message message) {
        var security = fabric.security();
        if (security.enabled() && security.refuseUnlessKernel(message, message.getSubject()).isPresent()) {
            return;
        }
        if (sweepWaiting.compareAndSet(false, true)) {
            Thread.ofVirtual().start(this::sweepAfterSignal);
        }
    }

    /** Takes the sweep's lock first, so signals that arrive while it waits for a running sweep start no other. */
    private synchronized void sweepAfterSignal() {
        sweepWaiting.set(false);
        sweep();
    }

    /**
     * Runs the sweep checks on every open goal; does nothing until the runtime has joined the fabric. A sweep ends a
     * hold on phrasing (see {@link Coach#sweepStarting}), so its first nudge is phrased again.
     */
    @Scheduled(initialDelayString = "${fabric.coach.first-sweep:PT40S}", fixedDelayString = "${fabric.coach.sweep:PT10M}")
    public synchronized void sweep() {
        AgentRuntime agent = runtime.getIfAvailable();
        if (agent == null) {
            return;
        }
        var manifest = agent.manifests().stream().filter(m -> m.id().equals(coach.manifest().id())).findFirst();
        var goals = fabric.goals();
        if (manifest.isEmpty() || goals.isEmpty()) {
            return;
        }
        coach.sweepStarting();
        int looked = 0;
        for (FabricClient.ListedGoal summary : goals.get()) {
            if (!summary.open()) {
                continue;
            }
            looked++;
            fabric.goalContext(summary.id()).ifPresent(goal -> {
                InvocationContext context = new InvocationContext(manifest.get(), model, fabric);
                Nudges.onSweep(goal, summary.updatedAt(), staleDays).forEach(n -> coach.post(n, goal, context));
            });
        }
        log.debug("swept {} open goals", looked);
    }
}
