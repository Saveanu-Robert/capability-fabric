package dev.capabilityfabric.kernel.security;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The latest admission request of each key that asked recently, whether the kernel stored it or not: the key, the
 * provider name, the hash of the contract it asks for, that contract as compact JSON text, when it asked and, when it
 * was not stored, why. The owner can admit a key by its fingerprint from this record even while the waiting list or
 * the inbox is full.
 *
 * <p>What is kept is bounded twice: at most {@code maxKeys} keys, and at most {@code maxChars} characters of
 * contract text and other fields for all of them together ({@link Request#weight()}). When either bound is passed, the
 * key that asked longest ago is forgotten first, so an agent that keeps asking (agents ask on every heartbeat) stays
 * however many other keys ask. A contract whose text is longer than {@link #MAX_CONTRACT_CHARS} is not kept at all:
 * such a key is remembered with its hash only and cannot be admitted by its fingerprint until a request of it is
 * stored.
 */
final class RecentAdmissions {

    /** The longest contract text kept for one request (far more than any real agent asks for). */
    static final int MAX_CONTRACT_CHARS = 64 * 1024;
    /** What one entry counts besides its contract text: the key, the names, the hash and the reason. */
    static final int ENTRY_OVERHEAD = 512;

    /**
     * One key's latest request. {@code contractText} is the contract as {@link Contract#toJson()} wrote it, or
     * {@code null} when it was longer than {@link #MAX_CONTRACT_CHARS}; {@code contractHash} is always the hash of
     * the contract asked for; {@code problem} is why the request was not stored ({@code null} when it was).
     */
    record Request(String keyId, String publicKey, String provider, String contractHash, String contractText,
                   Instant at, String problem) {

        /** The request for {@code contract}, its text left out when it is too long to keep. */
        static Request of(String keyId, String publicKey, String provider, Contract contract, Instant at,
                          String problem) {
            String text = contract.toJson();
            return new Request(keyId, publicKey, provider, contract.hash(),
                    text.length() > MAX_CONTRACT_CHARS ? null : text, at, problem);
        }

        /** The contract asked for; empty when its text was not kept. */
        Optional<Contract> contract() {
            return Contract.fromJson(contractText);
        }

        /** How many characters this entry counts against the bound of all entries together. */
        long weight() {
            return ENTRY_OVERHEAD + (contractText == null ? 0 : contractText.length())
                    + (problem == null ? 0 : problem.length()) + (provider == null ? 0 : provider.length());
        }
    }

    private final int maxKeys;
    private final long maxChars;
    private final Map<String, Request> requests = new LinkedHashMap<>();
    private long chars;

    RecentAdmissions(int maxKeys, long maxChars) {
        this.maxKeys = maxKeys;
        this.maxChars = maxChars;
    }

    /** Records a key's latest request; it becomes the newest entry, and the oldest go while a bound is passed. */
    synchronized void put(Request request) {
        remove(request.keyId());
        requests.put(request.keyId(), request);
        chars += request.weight();
        Iterator<Request> oldest = requests.values().iterator();
        while ((requests.size() > maxKeys || chars > maxChars) && oldest.hasNext()) {
            Request gone = oldest.next();
            if (gone == request) {
                break; // the newest entry stays, whatever it weighs
            }
            oldest.remove();
            chars -= gone.weight();
        }
    }

    /** The latest request of the key with this key id, if it asked at or after {@code since}. */
    synchronized Optional<Request> get(String keyId, Instant since) {
        return Optional.ofNullable(requests.get(keyId)).filter(r -> !r.at().isBefore(since));
    }

    /** Forgets a key's request: the owner decided on it. */
    synchronized void forget(String keyId) {
        remove(keyId);
    }

    synchronized int size() {
        return requests.size();
    }

    /** How many characters all entries count together ({@link Request#weight()}). */
    synchronized long chars() {
        return chars;
    }

    private void remove(String keyId) {
        Request gone = requests.remove(keyId);
        if (gone != null) {
            chars -= gone.weight();
        }
    }
}
