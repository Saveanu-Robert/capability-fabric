package dev.capabilityfabric.sdk;

import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Security;
import io.nats.client.Message;
import io.nats.client.impl.Headers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * This process's identity on the fabric and whom it trusts. Every outgoing request, reply, bid and registry entry
 * is signed with the process's key; incoming work is accepted only when signed by the kernel. The kernel's key is
 * either pinned from the start (read from a file the operator provides) or learnt from the grant the kernel issues
 * when the owner admits this process. The kernel itself is "self-trusted". Calls the kernel seals for this process
 * are opened with its X25519 seal key, whose public half the process advertises in its signed registry entry; the
 * same key is named in every request this process sends ({@link Security#HEADER_REPLY_SEAL}), so the answer comes
 * back sealed for it and other connections on the broker see only ciphertext.
 */
public class FabricSecurity {

    private final boolean enabled;
    private final Security.Identity identity;
    private final Security.SealKey sealKey;
    private final boolean pinned;
    private final Security.ReplayGuard replays = new Security.ReplayGuard();
    private volatile String kernelKey;
    private volatile Grant grant;

    public FabricSecurity(boolean enabled, Security.Identity identity) {
        this(enabled, identity, null, null);
    }

    /**
     * @param sealKey          the key sealed calls are opened with, or {@code null} to accept plain calls only
     * @param pinnedKernelKey  the only kernel key trusted, or {@code null} to trust the key that admits this process
     * @throws IllegalArgumentException when {@code pinnedKernelKey} is not an Ed25519 key in canonical form
     */
    public FabricSecurity(boolean enabled, Security.Identity identity, Security.SealKey sealKey,
                          String pinnedKernelKey) {
        if (pinnedKernelKey != null && !Security.isCanonicalKey(pinnedKernelKey)) {
            throw new IllegalArgumentException("the pinned kernel key is not an Ed25519 public key");
        }
        this.enabled = enabled;
        this.identity = identity;
        this.sealKey = sealKey;
        this.pinned = pinnedKernelKey != null;
        this.kernelKey = pinnedKernelKey;
    }

    /** For the kernel: it trusts its own key, needs no admission and opens no sealed calls. */
    public static FabricSecurity selfTrusted(boolean enabled, Security.Identity identity) {
        return new FabricSecurity(enabled, identity, null, identity.publicKeyText());
    }

    /**
     * Reads the kernel's public key from {@code file} (one line). Fails when the file is missing or holds anything
     * else, so a misconfigured process never falls back to trusting whoever answers first.
     */
    public static String readKernelKey(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the kernel key file " + file, e);
        }
        if (!Security.isCanonicalKey(text)) {
            throw new IllegalStateException("the kernel key file " + file + " does not hold an Ed25519 public key");
        }
        return text;
    }

    public boolean enabled() {
        return enabled;
    }

    public Security.Identity identity() {
        return identity;
    }

    /** The trusted kernel key: pinned, or learnt from the grant that admitted this process; empty before that. */
    public Optional<String> kernelKey() {
        return Optional.ofNullable(kernelKey);
    }

    /** True when the kernel key was configured, not learnt. */
    public boolean kernelKeyPinned() {
        return pinned;
    }

    public Optional<Security.SealKey> sealKey() {
        return Optional.ofNullable(sealKey);
    }

    /** The public half of the seal key, as advertised in the registry entry; {@code null} without one. */
    public String sealKeyText() {
        return enabled && sealKey != null ? sealKey.publicKeyText() : null;
    }

    public Optional<Grant> grant() {
        return Optional.ofNullable(grant);
    }

    /**
     * The owner admitted this process: remember the grant and, unless a kernel key is pinned, pin the key that
     * issued it. Empty when accepted, otherwise why the grant is ignored: it must name this process's key as
     * holder, verify against its issuer and, with a pinned key, be issued by that key.
     */
    public Optional<String> admittedWith(Grant granted) {
        if (granted == null) {
            return Optional.of("no grant");
        }
        String issuer = granted.root().issuer();
        if (identity != null && !identity.publicKeyText().equals(granted.holder())) {
            return Optional.of("the grant is for another key");
        }
        String trusted = pinned ? kernelKey : issuer;
        if (trusted == null || !trusted.equals(issuer)) {
            return Optional.of("the grant was not issued by the trusted kernel");
        }
        List<String> problems = granted.verify(Set.of(trusted), Instant.now());
        if (!problems.isEmpty()) {
            return Optional.of(String.join("; ", problems));
        }
        this.grant = granted;
        this.kernelKey = trusted;
        return Optional.empty();
    }

    /** Forgets the grant: the owner revoked or has not yet renewed this process's admission. */
    public void revoked() {
        this.grant = null;
    }

    /**
     * The headers for one outgoing message: a stamp over {@code payload} for {@code scope} and the grant, when
     * admitted. Empty headers when security is off.
     */
    public Headers headers(String scope, byte[] payload) {
        Headers headers = new Headers();
        if (enabled) {
            identity.stamp(scope, payload).headers().forEach(headers::put);
            if (grant != null) {
                headers.put(Security.HEADER_GRANT, grant.encode());
            }
        }
        return headers;
    }

    /** The stamp in a message's headers; empty when it has none or an incomplete one. */
    public static Optional<Security.Stamp> stampOf(Message message) {
        Headers h = message.getHeaders();
        if (h == null) {
            return Optional.empty();
        }
        Map<String, String> values = new HashMap<>();
        for (String name : new String[]{Security.HEADER_KEY, Security.HEADER_TS, Security.HEADER_NONCE,
                Security.HEADER_SIG}) {
            String v = h.getFirst(name);
            if (v != null) {
                values.put(name, v);
            }
        }
        return Security.Stamp.of(values);
    }

    /**
     * Empty when the message was signed by the kernel for this scope, is fresh and has not been seen before;
     * otherwise why it is refused. Always empty when security is off.
     */
    public Optional<String> refuseUnlessKernel(Message message, String scope) {
        if (!enabled) {
            return Optional.empty();
        }
        String trusted = kernelKey;
        if (trusted == null) {
            return Optional.of("not admitted yet, so no kernel is trusted");
        }
        Optional<Security.Stamp> stamp = stampOf(message);
        if (stamp.isEmpty()) {
            return Optional.of("unsigned");
        }
        if (!trusted.equals(stamp.get().key())) {
            return Optional.of("not signed by the kernel");
        }
        if (!stamp.get().verifies(scope, message.getData())) {
            return Optional.of("invalid signature");
        }
        return replays.check(stamp.get());
    }

    /**
     * {@link #refuseUnlessKernel} for a request that arrived on {@code subject}: the scope it must be signed for
     * includes the reply seal key it carries, if any ({@link Security#requestScope}).
     */
    public Optional<String> refuseUnlessKernelRequest(Message message, String subject) {
        return refuseUnlessKernel(message, requestScope(message, subject));
    }

    /** The X25519 key a request asks its reply to be sealed for ({@link Security#HEADER_REPLY_SEAL}), or null. */
    public static String replySealOf(Message message) {
        Headers h = message.getHeaders();
        String key = h == null ? null : h.getFirst(Security.HEADER_REPLY_SEAL);
        return key == null || key.isBlank() ? null : key;
    }

    /** The scope a request that arrived on {@code subject} is signed for, with the reply seal key it carries. */
    public static String requestScope(Message message, String subject) {
        return Security.requestScope(subject, replySealOf(message));
    }

    /**
     * The longest seal key text looked at: an X25519 public key in the form {@link Security.SealKey#publicKeyText()}
     * writes has 59 characters.
     */
    public static final int MAX_SEAL_KEY_LENGTH = 64;

    /** Whether {@code text} is an X25519 public key in the one form {@link Security.SealKey#publicKeyText()} writes. */
    public static boolean isSealKey(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_SEAL_KEY_LENGTH) {
            return false;
        }
        try {
            var key = KeyFactory.getInstance("X25519").generatePublic(
                    new X509EncodedKeySpec(Base64.getUrlDecoder().decode(text)));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(key.getEncoded()).equals(text);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** A reply ready for the wire: the body as sent (sealed when the requester asked) and the headers signing it. */
    public record SignedReply(Headers headers, byte[] body) {
    }

    /**
     * A reply signed for {@code scope}. With a {@code replySealKey} the body is sealed for that key with
     * {@code scope} as context and marked with {@link Security#HEADER_SEALED}; the stamp signs the sealed bytes, so
     * the requester checks the signature before it opens anything.
     *
     * @throws IllegalArgumentException when {@code replySealKey} is not an X25519 public key
     */
    public SignedReply reply(String scope, byte[] body, String replySealKey) {
        if (replySealKey == null) {
            return new SignedReply(headers(scope, body), body);
        }
        if (!isSealKey(replySealKey)) {
            throw new IllegalArgumentException("the reply seal key is not an X25519 public key");
        }
        byte[] sealed = Security.seal(replySealKey, scope, body);
        Headers headers = headers(scope, sealed);
        headers.put(Security.HEADER_SEALED, Security.SEAL_SCHEME);
        return new SignedReply(headers, sealed);
    }

    /**
     * The body of an answer as the responder wrote it: an answer sealed for this process is opened with its seal
     * key, bound to {@code scope} (the scope the answer is signed for); a plain one is returned as it is. Call it
     * after the stamp was checked.
     *
     * @throws IllegalArgumentException when the answer is sealed with an unknown scheme, for a key this process does
     *                                  not have, for another scope, or was altered
     */
    public byte[] openReply(Message message, String scope) {
        return opened(message, scope);
    }

    /**
     * The body of a request as the sender wrote it. A body sealed for this process (header
     * {@link Security#HEADER_SEALED}) is opened with the seal key, bound to the subject it arrived on; a plain body is
     * returned as it is. Call it after the stamp was checked: the stamp covers the bytes on the wire.
     *
     * @throws IllegalArgumentException when the body is sealed with an unknown scheme, for a key this process does
     *                                  not have, for another subject, or was altered
     */
    public byte[] plaintext(Message message) {
        return opened(message, message.getSubject());
    }

    /**
     * A message's body opened with the seal key for {@code context} when it carries {@link Security#HEADER_SEALED};
     * as it came otherwise.
     */
    private byte[] opened(Message message, String context) {
        Headers h = message.getHeaders();
        String scheme = h == null ? null : h.getFirst(Security.HEADER_SEALED);
        if (scheme == null) {
            return message.getData();
        }
        if (!Security.SEAL_SCHEME.equals(scheme)) {
            throw new IllegalArgumentException("unknown sealing scheme " + scheme);
        }
        if (sealKey == null) {
            throw new IllegalArgumentException("sealed for a key this process does not have");
        }
        return Security.open(sealKey, context, message.getData());
    }
}
