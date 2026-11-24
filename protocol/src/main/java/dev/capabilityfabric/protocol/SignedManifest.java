package dev.capabilityfabric.protocol;

import java.nio.charset.StandardCharsets;

/**
 * A registry entry as an agent writes it: its manifest (with the time the agent sent it), its public signing key, its
 * public sealing key (calls to this instance are encrypted for it), the grant it was admitted with (absent until the
 * owner admits it) and its signature over all of that. The kernel lists only entries signed by an admitted key whose
 * manifest stays within the admitted contract, and only inside a heartbeat signed by that same key, which it refuses
 * when it is stale or a replay: an entry copied off the wire lists nothing for anyone else. The lease runs from when
 * the kernel received the heartbeat, whatever time the manifest names.
 *
 * @param sealKey the instance's X25519 public key (base64url); with security on, the kernel lists no entry without a
 *                valid one, so every call to the instance is sealed
 */
public record SignedManifest(CapabilityManifest manifest, String key, String sealKey, String grant, String signature) {

    /** Signs the manifest, the signer's key, the seal key and the grant together with {@code identity}. */
    public static SignedManifest sign(CapabilityManifest manifest, Security.Identity identity, String sealKey,
                                      String grant) {
        return new SignedManifest(manifest, identity.publicKeyText(), sealKey, grant,
                Security.sign(identity, signingBytes(manifest, identity.publicKeyText(), sealKey, grant)));
    }

    /** The signature is valid for {@link #key} over everything in the entry; who the key belongs to is not checked. */
    public boolean verifies() {
        return key != null && signature != null
                && Security.verify(key, signingBytes(manifest, key, sealKey, grant), signature);
    }

    static byte[] signingBytes(CapabilityManifest manifest, String key, String sealKey, String grant) {
        return ("capability-fabric-manifest-v2\n" + Json.canonical(manifest) + "\n" + key + "\n" + (sealKey == null ? "" : sealKey)
                + "\n" + (grant == null ? "" : grant)).getBytes(StandardCharsets.UTF_8);
    }
}
