package dev.capabilityfabric.cli;

import java.util.Locale;
import java.util.Optional;

/**
 * An invitation code: {@code fabric-invite:<id>.<secret>.<caPin>.<kernelKeyId>}, or the older
 * {@code fabric-invite:<id>.<secret>}. {@code caPin} is the first 32 hex characters of the SHA-256 of the certificate
 * authority behind the gateway ({@code -} when the kernel had none); {@code kernelKeyId} is the kernel's
 * {@link dev.capabilityfabric.protocol.Security#keyId key id}. Both let the guest's device check whom it talks to
 * before it sends the secret.
 *
 * @param caPin       lower-case hex, or {@code null} when the code carries none
 * @param kernelKeyId lower-case hex, or {@code null} when the code carries none
 */
record Invitation(String id, String secret, String caPin, String kernelKeyId) {

    static final String PREFIX = "fabric-invite:";

    /** The invitation in {@code code} (either form), or empty when it is not one or a part is malformed. */
    static Optional<Invitation> parse(String code) {
        if (code == null || !code.strip().startsWith(PREFIX)) {
            return Optional.empty();
        }
        String[] parts = code.strip().substring(PREFIX.length()).split("\\.", -1);
        if (parts.length != 2 && parts.length != 4) {
            return Optional.empty();
        }
        String id = parts[0];
        String secret = parts[1];
        if (!token(id) || !token(secret)) {
            return Optional.empty();
        }
        if (parts.length == 2) {
            return Optional.of(new Invitation(id, secret, null, null));
        }
        String pin = parts[2].toLowerCase(Locale.ROOT);
        String kernel = parts[3].toLowerCase(Locale.ROOT);
        if (!pin.equals("-") && !pin.matches("[0-9a-f]{32,64}") || !kernel.matches("[0-9a-f]{16}")) {
            return Optional.empty();
        }
        return Optional.of(new Invitation(id, secret, pin.equals("-") ? null : pin, kernel));
    }

    /**
     * The invitation in {@code code}, or a failure that says what is wrong with it: text that does not start with
     * {@link #PREFIX} is not an invitation; text that does but cannot be read is an invitation that was cut short or
     * changed on the way.
     *
     * @throws Fabric.CliException when {@code code} is not a whole, readable invitation
     */
    static Invitation read(String code) {
        Optional<Invitation> invitation = parse(code);
        if (invitation.isPresent()) {
            return invitation.get();
        }
        if (code == null || !code.strip().startsWith(PREFIX)) {
            throw new Fabric.CliException("that is not an invitation; an invitation starts with " + PREFIX);
        }
        throw new Fabric.CliException("that invitation is incomplete or damaged; copy the whole code again, or ask "
                + "whoever invited you for a new one");
    }

    /** What the kernel needs to find and check the invitation; the pins stay on this device. */
    String claimCode() {
        return PREFIX + id + "." + secret;
    }

    private static boolean token(String s) {
        return !s.isEmpty() && s.matches("[A-Za-z0-9_-]+");
    }
}
