package dev.capabilityfabric.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 over canonical JSON. */
public final class Hashing {

    private Hashing() {
    }

    /** SHA-256 of the UTF-8 bytes of {@code text}, as 64 lowercase hex characters. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #sha256} of {@code value}'s canonical JSON: the same value always has the same hash. */
    public static String canonicalHash(Object value) {
        return sha256(Json.canonical(value));
    }
}
