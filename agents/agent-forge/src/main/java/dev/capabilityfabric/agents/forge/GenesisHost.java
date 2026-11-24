package dev.capabilityfabric.agents.forge;

import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.SignedSpec;
import dev.capabilityfabric.sdk.AgentRuntime;
import dev.capabilityfabric.sdk.CapabilityHandler;
import dev.capabilityfabric.sdk.FabricClient;
import dev.capabilityfabric.sdk.FabricSecurity;
import io.nats.client.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Hosts the capabilities the system has grown. It asks the kernel for the grown capabilities' specs with a signed
 * request ({@link FabricClient#genesis}) when it starts, whenever the kernel signals that a capability was grown, kept
 * or retired, and every {@link #RESYNC_MS} ms to make up for signals sent while it was disconnected; it serves every
 * spec that is not retired as a {@link PromptCapability}. With security on, a spec is hosted only when the kernel key
 * this process trusts signed it and it passes the growth rules. An entry that cannot be trusted (unsigned, signed by
 * another key, malformed) is ignored and leaves what is hosted for its id unchanged. A retired spec, or one the kernel
 * no longer lists, is withdrawn from the fabric at once. Until the kernel answers (it answers only once this agent is
 * admitted) and its key is known, the host asks again every {@link #RETRY_MS} ms.
 */
@Component
public class GenesisHost implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GenesisHost.class);
    /** How often the host asks again while the kernel does not answer or its key is not known yet. */
    static final long RETRY_MS = 5_000;
    /** How often the host asks even when no signal came. */
    static final long RESYNC_MS = 10 * 60_000;
    /** The facts after which the specs are asked for again. */
    static final Set<String> SPEC_FACTS = Set.of(Facts.CAPABILITY_GROWN, Facts.CAPABILITY_KEPT,
            Facts.CAPABILITY_RETIRED);

    /** Where hosted capabilities are served: the agent runtime in production. */
    interface Hosting {
        void register(CapabilityHandler handler);

        void unregister(String capabilityId);
    }

    /** The kernel as the host sees it: its answer to {@code query.genesis} and its signals. */
    interface Kernel {
        /** The signed specs, retired ones included; empty when the kernel does not answer. */
        Optional<List<SignedSpec>> genesis();

        /** Calls {@code listener} for every kernel signal of these fact types until the returned handle is closed. */
        AutoCloseable onSignals(Collection<String> factTypes, Runnable listener);
    }

    private final Kernel kernel;
    private final Hosting hosting;
    private final boolean verify;
    private final Supplier<Optional<String>> kernelKey;
    /** The spec each hosted capability is served from, by id. */
    private final Map<String, Ecosystem.CapabilitySpec> hosted = new ConcurrentHashMap<>();
    private final Object wake = new Object();
    private volatile boolean running;
    private volatile boolean asked;
    private volatile Thread worker;
    private volatile AutoCloseable signals;

    @Autowired
    public GenesisHost(FabricClient fabric, AgentRuntime runtime, FabricSecurity security) {
        this(new Kernel() {
            @Override
            public Optional<List<SignedSpec>> genesis() {
                return fabric.genesis();
            }

            @Override
            public AutoCloseable onSignals(Collection<String> factTypes, Runnable listener) {
                Dispatcher dispatcher = fabric.signals(factTypes, signal -> listener.run());
                return () -> fabric.connection().closeDispatcher(dispatcher);
            }
        }, new Hosting() {
            @Override
            public void register(CapabilityHandler handler) {
                runtime.register(handler);
            }

            @Override
            public void unregister(String capabilityId) {
                runtime.unregister(capabilityId);
            }
        }, security.enabled(), security::kernelKey);
    }

    GenesisHost(Kernel kernel, Hosting hosting, boolean verify, Supplier<Optional<String>> kernelKey) {
        this.kernel = kernel;
        this.hosting = hosting;
        this.verify = verify;
        this.kernelKey = kernelKey;
    }

    @Override
    public void start() {
        running = true;
        worker = Thread.ofVirtual().name("genesis-host").start(this::follow);
    }

    /** Follows the kernel's signals and asks for the specs at start, after each signal and on the timers. */
    private void follow() {
        try {
            signals = kernel.onSignals(SPEC_FACTS, this::askSoon);
        } catch (RuntimeException e) {
            log.warn("cannot follow the kernel's capability signals ({}); asking every {} s instead", e.getMessage(),
                    RETRY_MS / 1000);
        }
        while (running) {
            asked = false;
            boolean current = refresh();
            long wait = current && signals != null ? RESYNC_MS : RETRY_MS;
            synchronized (wake) {
                if (!asked && running) {
                    try {
                        wake.wait(wait);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    /** Asks for the specs again on the host's own thread, so a signal never waits for the kernel's answer. */
    private void askSoon() {
        synchronized (wake) {
            asked = true;
            wake.notifyAll();
        }
    }

    /**
     * Asks the kernel for the specs and makes what is hosted match them: each trusted spec that is not retired is
     * hosted (again, when it changed); a hosted capability whose spec is retired, or that the kernel no longer
     * lists, is withdrawn; an id whose entries cannot be trusted keeps what it has. False when the specs could not
     * be read (no answer, or the kernel key is not known yet with security on), so the host asks again soon.
     */
    synchronized boolean refresh() {
        Optional<String> trusted = kernelKey.get();
        if (verify && trusted.isEmpty()) {
            log.info("grown capabilities wait until the kernel key is known (set fabric.security.kernel-key-file to "
                    + "pin it)");
            return false;
        }
        Optional<List<SignedSpec>> answer;
        try {
            answer = kernel.genesis();
        } catch (RuntimeException e) {
            answer = Optional.empty();
        }
        if (answer.isEmpty()) {
            log.info("the kernel did not list the grown capabilities yet; asking again in {} s", RETRY_MS / 1000);
            return false;
        }
        Map<String, Ecosystem.CapabilitySpec> listed = new LinkedHashMap<>();
        Set<String> untrusted = new HashSet<>();
        for (SignedSpec entry : answer.get()) {
            Optional<Ecosystem.CapabilitySpec> spec = trusted(entry, trusted.orElse(null));
            if (spec.isPresent()) {
                listed.put(spec.get().id(), spec.get());
            } else if (entry != null && entry.spec() != null && entry.spec().id() != null) {
                untrusted.add(entry.spec().id());
            }
        }
        for (String id : Set.copyOf(hosted.keySet())) {
            Ecosystem.CapabilitySpec spec = listed.get(id);
            if (spec == null && !untrusted.contains(id) || spec != null && spec.status() == Ecosystem.SpecStatus.RETIRED) {
                withdraw(id);
            }
        }
        listed.values().stream().filter(s -> s.status() != Ecosystem.SpecStatus.RETIRED).forEach(this::host);
        log.debug("hosting {} grown capabilities", hosted.size());
        return true;
    }

    /**
     * The spec of an entry the host may serve: signed by {@code kernelKey} (with security on; well formed with it
     * off) and within the growth rules; empty otherwise.
     */
    private Optional<Ecosystem.CapabilitySpec> trusted(SignedSpec entry, String kernelKey) {
        if (entry == null || !(verify ? entry.verifies(kernelKey) : entry.wellFormed())) {
            log.warn("ignoring a grown capability that is not a spec signed by the trusted kernel: {}",
                    entry == null || entry.spec() == null ? null : entry.spec().id());
            return Optional.empty();
        }
        List<String> problems = Ecosystem.validate(entry.spec(), Set.of());
        if (!problems.isEmpty()) {
            log.warn("ignoring spec {}: {}", entry.spec().id(), String.join("; ", problems));
            return Optional.empty();
        }
        return Optional.of(entry.spec());
    }

    /** Serves {@code spec}, replacing the capability served under its id when the spec changed. */
    private void host(Ecosystem.CapabilitySpec spec) {
        if (spec.equals(hosted.get(spec.id()))) {
            return;
        }
        withdraw(spec.id());
        try {
            hosting.register(new PromptCapability(spec));
        } catch (RuntimeException e) {
            log.warn("could not host spec {}: {}", spec.id(), e.getMessage());
            return;
        }
        hosted.put(spec.id(), spec);
        log.info("hosting grown capability {} ({})", spec.id(), spec.status());
    }

    /** Ids of the capabilities hosted now, each with its spec status. */
    Map<String, String> hosted() {
        Map<String, String> statuses = new LinkedHashMap<>();
        hosted.forEach((id, spec) -> statuses.put(id, spec.status().name()));
        return Map.copyOf(statuses);
    }

    private void withdraw(String id) {
        if (hosted.remove(id) != null) {
            hosting.unregister(id);
        }
    }

    @Override
    public void stop() {
        running = false;
        askSoon();
        AutoCloseable handle = signals;
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception e) {
                log.debug("closing the signal subscription: {}", e.getMessage());
            }
        }
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // After the agent runtime has started.
        return Integer.MAX_VALUE - 50;
    }
}
