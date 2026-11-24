package dev.capabilityfabric.cli;

import dev.capabilityfabric.protocol.Security;

import java.util.Locale;
import java.util.Optional;

/**
 * The kernel's key id as people pass it with {@code --kernel}: 16 hex characters ({@code 3f9a11c277b0d41e}) or the
 * dashed fingerprint form ({@code 3F9A-11C2-77B0-D41E}), in any case.
 */
final class KernelId {

    private KernelId() {
    }

    /** The 16 lower-case hex characters, or a plain error when {@code text} is not a key id. */
    static String normalize(String text) {
        String id = text == null ? "" : text.strip().replace("-", "").toLowerCase(Locale.ROOT);
        if (!id.matches("[0-9a-f]{16}")) {
            throw new Fabric.CliException("--kernel needs the kernel's key id: 16 hex characters such as "
                    + "3f9a11c277b0d41e, or the fingerprint form 3F9A-11C2-77B0-D41E");
        }
        return id;
    }

    /** The id of a public key, or empty when the text is not a key. */
    static Optional<String> of(String publicKeyText) {
        try {
            return Optional.of(Security.keyId(publicKeyText));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** True when {@code publicKeyText} is a key whose id is {@code id} (in any accepted form). */
    static boolean matches(String id, String publicKeyText) {
        return publicKeyText != null && of(publicKeyText).map(normalize(id)::equals).orElse(false);
    }

    /** Two ids that must agree (from {@code --kernel} and from an invitation): either one, or an error. */
    static Optional<String> agree(String given, String fromInvitation) {
        Optional<String> a = Optional.ofNullable(given).filter(s -> !s.isBlank()).map(KernelId::normalize);
        Optional<String> b = Optional.ofNullable(fromInvitation).filter(s -> !s.isBlank()).map(KernelId::normalize);
        if (a.isPresent() && b.isPresent() && !a.get().equals(b.get())) {
            throw new Fabric.CliException("--kernel " + a.get() + " is not the kernel named in the invitation ("
                    + b.get() + "); ask for a new invitation");
        }
        return a.or(() -> b);
    }
}
