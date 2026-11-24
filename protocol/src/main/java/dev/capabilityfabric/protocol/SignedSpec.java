package dev.capabilityfabric.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A grown capability's spec as the kernel signs it, after the owner confirmed it: the spec and the kernel's signature
 * over its canonical JSON. The kernel stores signed specs and lists them to the forge ({@link Subjects#QUERY_GENESIS}),
 * and a reader accepts a spec only when the signature verifies against the kernel key it trusts. Anything else (an
 * unsigned spec, a spec signed by another key, a value that is not a signed spec at all) is skipped.
 *
 * @param signature base64url Ed25519 signature by the kernel over {@link #signingBytes(Ecosystem.CapabilitySpec)}
 */
public record SignedSpec(Ecosystem.CapabilitySpec spec, String signature) {

    /** Signs {@code spec} with the kernel's {@code identity}. */
    public static SignedSpec sign(Ecosystem.CapabilitySpec spec, Security.Identity identity) {
        return new SignedSpec(spec, Security.sign(identity, signingBytes(spec)));
    }

    /**
     * True when the spec is well formed (it has an id, a scope and a status) and the signature is valid for
     * {@code kernelKey}; false for any missing or malformed part.
     */
    public boolean verifies(String kernelKey) {
        return wellFormed() && kernelKey != null && signature != null
                && Security.verify(kernelKey, signingBytes(spec), signature);
    }

    /** True when the spec carries the fields every reader relies on: an id, a scope and a status. */
    public boolean wellFormed() {
        return spec != null && spec.id() != null && !spec.id().isBlank() && spec.scope() != null
                && spec.status() != null;
    }

    /** The JSON bytes of the signed spec, as the kernel stores it. */
    public byte[] bytes() {
        return Json.bytes(this);
    }

    /**
     * The spec stored under {@code key} (its id) when {@code value} is a signed spec whose id equals
     * the key and whose signature verifies against {@code kernelKey}; empty otherwise. Never throws.
     */
    public static Optional<Ecosystem.CapabilitySpec> read(String key, byte[] value, String kernelKey) {
        return parse(value).filter(s -> s.verifies(kernelKey) && s.spec().id().equals(key)).map(SignedSpec::spec);
    }

    /**
     * {@code value} read as a signed spec without checking the signature; empty when it is not one or the spec is
     * not well formed. Never throws. For readers that run with security switched off.
     */
    public static Optional<SignedSpec> parse(byte[] value) {
        if (value == null || value.length == 0) {
            return Optional.empty();
        }
        try {
            SignedSpec signed = Json.read(value, SignedSpec.class);
            return signed != null && signed.wellFormed() ? Optional.of(signed) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static byte[] signingBytes(Ecosystem.CapabilitySpec spec) {
        return ("capability-fabric-genesis-spec-v1\n" + Json.canonical(spec)).getBytes(StandardCharsets.UTF_8);
    }
}
