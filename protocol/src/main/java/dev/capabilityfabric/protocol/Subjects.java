package dev.capabilityfabric.protocol;

import java.util.Set;

/**
 * NATS subject taxonomy. Components address work by capability, never by service name.
 *
 * <ul>
 *   <li>{@code intent.submit} - a person says something in natural language (request/reply, kernel answers).</li>
 *   <li>{@code intent.confirm} - the person's yes or no to an action that needs confirmation.</li>
 *   <li>{@code intent.execute} - typed actions with goal ids and task keys, done or rejected without a model.</li>
 *   <li>{@code intent.inbox}, {@code intent.inbox.answer} - the person reads the inbox and answers one message;
 *       providers post to it on {@code inbox.post}.</li>
 *   <li>{@code fact.<aggregate>.<event>} - immutable facts; only the kernel publishes them (JetStream FACTS).</li>
 *   <li>{@code signal.<aggregate>.<event>} - the kernel's thin, signed notice of each fact, for agents.</li>
 *   <li>{@code registry.heartbeat}, {@code registry.withdraw} - agents renew and end their capability leases.</li>
 *   <li>{@code cap.<capability>.v<major>} - capability call to any provider (queue group, request/reply).</li>
 *   <li>{@code cap.<capability>.v<major>.i.<instance>} - capability call to one chosen provider instance.</li>
 *   <li>{@code cfp.<capability>} - call for proposals; every provider instance may answer with a bid.</li>
 *   <li>{@code proposal.submit} - reactive agents propose ledger changes; the kernel validates them.</li>
 *   <li>{@code query.*} - read model for providers and for rendering results.</li>
 *   <li>{@code security.*} - pairing, joining, admission and the kernel's public keys.</li>
 *   <li>{@code control.stop}, {@code control.resume} - emergency stop that works without any model.</li>
 *   <li>{@code gw.<subject>} - the same requests forwarded from the internet by the gateway.</li>
 * </ul>
 */
public final class Subjects {

    public static final String INTENT_SUBMIT = "intent.submit";
    public static final String INTENT_CONFIRM = "intent.confirm";
    /** Typed actions with ids, done or rejected without a language model (request/reply, kernel answers). */
    public static final String INTENT_EXECUTE = "intent.execute";
    /** The person's unread inbox (read) and their answer to one message. */
    public static final String INTENT_INBOX = "intent.inbox";
    public static final String INTENT_INBOX_ANSWER = "intent.inbox.answer";
    /** Providers post suggestions for the person's inbox here. */
    public static final String INBOX_POST = "inbox.post";

    public static final String FACT_PREFIX = "fact.";
    public static final String ALL_FACTS = "fact.>";

    public static final String PROPOSAL_SUBMIT = "proposal.submit";

    public static final String QUERY_GOAL = "query.goal";
    /** The goals: owners get summaries; agents get every open goal's id, status, due date and last change only. */
    public static final String QUERY_GOALS = "query.goals";
    public static final String QUERY_CONTEXT = "query.context";
    public static final String QUERY_DECISION_LOOKUP = "query.decision.lookup";
    /** Finished tasks similar to a text, with their hours (agents; minimised to the caller's admitted contract). */
    public static final String QUERY_MEMORY = "query.memory";
    /** The grown capabilities' specs, signed by the kernel (for the agent that hosts them). */
    public static final String QUERY_GENESIS = "query.genesis";
    /**
     * The gateway forwards every request on {@code gw.<subject>}; the broker lets only the gateway publish there and
     * keeps it off the plain subjects, so the kernel knows from the subject alone that a request came from the
     * internet. Signatures and reply scopes keep using the plain subject.
     */
    public static final String GATEWAY_PREFIX = "gw.";
    /**
     * Agents register their capabilities with the kernel: a signed list of registry entries renews their leases,
     * a signed withdrawal ends them at once. The kernel keeps the registry; agents never write it themselves.
     */
    public static final String REGISTRY_HEARTBEAT = "registry.heartbeat";
    public static final String REGISTRY_WITHDRAW = "registry.withdraw";

    /** A device pairs with the kernel using the one-time code the kernel shows; signed by the device's key. */
    public static final String SECURITY_PAIR = "security.pair";
    /** A guest's device claims an invitation; signed by the guest's key. */
    public static final String SECURITY_JOIN = "security.join";
    /** An agent asks whether it has been admitted, and receives its grant; signed by the agent's key. */
    public static final String SECURITY_ADMISSION = "security.admission";
    /**
     * The kernel's public keys, signed by the kernel: a device that pairs or joins through the gateway checks the
     * signing key against the one it was told, then seals its code for the sealing key, so the gateway forwards only
     * ciphertext.
     */
    public static final String SECURITY_HELLO = "security.hello";
    public static final String CONTROL_STOP = "control.stop";
    public static final String CONTROL_RESUME = "control.resume";

    /**
     * Reply-subject prefixes, one per broker role. The broker lets each role subscribe only to its own prefix, so
     * replies meant for the kernel or for people (pairing codes, invitations, answers) cannot be read by agents.
     */
    public static final String INBOX_KERNEL = "_INBOX_kernel";
    public static final String INBOX_AGENT = "_INBOX_agent";
    public static final String INBOX_PERSON = "_INBOX_person";
    public static final String INBOX_GATEWAY = "_INBOX_gateway";
    /** The registry bucket of older versions; the kernel deletes it at start (the registry lives in the kernel). */
    public static final String KV_CAPABILITIES = "capabilities";
    /**
     * The kernel's bucket of specs of capabilities the system grew. Agents have no access to it: the forge gets the
     * specs from the kernel with a signed {@link #QUERY_GENESIS} request and hosts every non-retired one.
     */
    public static final String KV_GENESIS = "genesis";
    public static final String STREAM_FACTS = "FACTS";
    /**
     * Signals: for every fact the kernel also publishes a thin, kernel-signed envelope under {@code signal.<type>}
     * that carries only the goal id and the fact id. Agents follow signals with a plain subscription (they have no
     * JetStream rights) and ask the kernel for the context they are allowed to see; only the kernel and people's
     * devices may read the facts themselves.
     */
    public static final String SIGNAL_PREFIX = "signal.";
    public static final String ALL_SIGNALS = "signal.>";
    /** The signal stream of older versions; the kernel deletes it at start (signals are plain broker messages). */
    public static final String STREAM_SIGNALS = "SIGNALS";

    /**
     * The requests the gateway forwards from the internet, each on {@link #viaGateway} of its subject: sentences,
     * answers, typed actions, inbox reads, goal reads, the kernel's keys, pairing and joining, and the emergency stop and resume.
     * The kernel serves them on both subjects, the gateway accepts no others, and setup lets the gateway's broker
     * user publish only on their gateway subjects.
     */
    public static final Set<String> GATEWAY_SUBJECTS = Set.of(INTENT_SUBMIT, INTENT_CONFIRM, INTENT_EXECUTE,
            INTENT_INBOX, INTENT_INBOX_ANSWER, QUERY_GOAL, QUERY_GOALS, SECURITY_HELLO, SECURITY_PAIR, SECURITY_JOIN, CONTROL_STOP,
            CONTROL_RESUME);

    private Subjects() {
    }

    /** {@code fact.task.added} for fact type {@code task.added}. */
    public static String fact(String factType) {
        return FACT_PREFIX + factType;
    }

    /** {@code signal.task.added}: the kernel's signal for facts of type {@code task.added}. */
    public static String signal(String factType) {
        return SIGNAL_PREFIX + factType;
    }

    /** {@code cap.<id>.v<major>}: every instance of the capability's major version listens here. */
    public static String capability(String capabilityId, int major) {
        return "cap." + capabilityId + ".v" + major;
    }

    /** {@code cap.<id>.v<major>.i.<instance>}: only that instance listens here. */
    public static String capabilityInstance(String capabilityId, int major, String instanceId) {
        return capability(capabilityId, major) + ".i." + instanceId;
    }

    /** {@code cfp.<id>}: calls for proposals for the capability. */
    public static String cfp(String capabilityId) {
        return "cfp." + capabilityId;
    }

    /** Queue group name shared by every instance of the same capability major version (no dots allowed). */
    public static String queueGroup(String capabilityId, int major) {
        return "q_" + capabilityId.replace('.', '_') + "_v" + major;
    }

    /** The subject the gateway forwards {@code subject} on. */
    public static String viaGateway(String subject) {
        return GATEWAY_PREFIX + subject;
    }

    /** The plain subject of a gateway subject ({@code gw.intent.submit} is {@code intent.submit}); others unchanged. */
    public static String plain(String subject) {
        return subject != null && subject.startsWith(GATEWAY_PREFIX) ? subject.substring(GATEWAY_PREFIX.length()) : subject;
    }
}
