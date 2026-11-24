package dev.capabilityfabric.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.crypto.Cipher;
import javax.crypto.KDF;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;

/**
 * Identity without accounts. Every participant (a person's device, an agent, the kernel) holds an Ed25519 key pair
 * and signs what it sends; there are no passwords, sessions or bearer API keys. A signature covers the subject (or
 * the purpose of a reply), a timestamp, a single-use nonce and the SHA-256 of the payload, so a message cannot be
 * altered, attributed to someone else, or replayed. Uses only the JDK.
 */
public final class Security {

    /** Headers that carry a {@link Stamp}: the signer's public key, the time in ms, the nonce, the signature. */
    public static final String HEADER_KEY = "Fabric-Key";
    public static final String HEADER_TS = "Fabric-Ts";
    public static final String HEADER_NONCE = "Fabric-Nonce";
    public static final String HEADER_SIG = "Fabric-Sig";
    /** An encoded {@link Grant} chain, when the signer acts on delegated rights. */
    public static final String HEADER_GRANT = "Fabric-Grant";
    /** Present when the payload is sealed for its recipient with {@link #seal}; the value names the scheme. */
    public static final String HEADER_SEALED = "Fabric-Sealed";
    /** The only sealing scheme: {@link #seal}. */
    public static final String SEAL_SCHEME = "x25519-hkdf-aesgcm-v1";
    /**
     * An X25519 public key the requester wants its reply sealed for. It is part of the signed scope
     * ({@link #requestScope}), so nobody on the way can swap it; the responder seals the reply body for it with the
     * reply's scope as context and sets {@link #HEADER_SEALED} on the reply.
     */
    public static final String HEADER_REPLY_SEAL = "Fabric-Reply-Seal";
    /**
     * A header a publisher may set to say it relays for the internet. Nothing signs it and anyone can set or leave it
     * out, so no component trusts it: a request came through the gateway exactly when it arrived on a gateway subject
     * ({@link Subjects#viaGateway}), which only the gateway's connection may publish.
     */
    public static final String HEADER_VIA = "Fabric-Via";

    /**
     * How far a sender's clock may differ from the receiver's; a {@link ReplayGuard} remembers a nonce for twice
     * this time after it arrived.
     */
    public static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

    /**
     * The context a device seals a claim with ({@link #seal}): the plain subject it sends the claim on
     * ({@link Subjects#plain}) and, on a second line, the device's own signing key text ({@link #encode}). The kernel
     * opens a claim with the key that verifiably signed the request, so sealed bytes re-signed by any other key, a
     * relay's included, do not open.
     */
    public static String claimContext(String subject, String deviceKey) {
        if (subject == null || deviceKey == null) {
            throw new IllegalArgumentException("a claim context needs a subject and a signing key");
        }
        return subject + "\n" + deviceKey;
    }

    /**
     * The scope a reply is signed for: the request's subject and the nonce of the request's own stamp ({@code ""}
     * for an unsigned request), so a reply verifies only for the one request it answers.
     */
    public static String replyScope(String requestSubject, String requestNonce) {
        return "reply:" + requestSubject + ":" + (requestNonce == null ? "" : requestNonce);
    }

    private static final String ALGORITHM = "Ed25519";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private Security() {
    }

    /** A key pair. The public key, base64url-encoded, is the participant's name on the wire. */
    public record Identity(PublicKey publicKey, PrivateKey privateKey) {

        public String publicKeyText() {
            return encode(publicKey);
        }

        public String keyId() {
            return Security.keyId(publicKey);
        }

        /** The raw Ed25519 signature of {@code data}. */
        public byte[] sign(byte[] data) {
            try {
                Signature s = Signature.getInstance(ALGORITHM);
                s.initSign(privateKey);
                s.update(data);
                return s.sign();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("cannot sign", e);
            }
        }

        /** Signs a message: the four values travel as headers (or JSON fields over HTTPS). */
        public Stamp stamp(String scope, byte[] payload) {
            long ts = System.currentTimeMillis();
            byte[] nonceBytes = new byte[16];
            RANDOM.nextBytes(nonceBytes);
            String nonce = B64.encodeToString(nonceBytes);
            return new Stamp(publicKeyText(), ts, nonce, B64.encodeToString(sign(signingInput(scope, ts, nonce, payload))));
        }
    }

    /** The signature of one message: who signed it ({@code key}), when ({@code ts}, epoch ms), and a nonce. */
    public record Stamp(String key, long ts, String nonce, String signature) {

        /** The stamp as the four {@code Fabric-*} headers. */
        public Map<String, String> headers() {
            return Map.of(HEADER_KEY, key, HEADER_TS, Long.toString(ts), HEADER_NONCE, nonce, HEADER_SIG, signature);
        }

        /**
         * The stamp in {@code headers}; empty when one of the four is missing, the time is not a number, or the nonce
         * is not in the form {@link Identity#stamp} writes ({@link #isValidNonce}).
         */
        public static Optional<Stamp> of(Map<String, String> headers) {
            String key = headers.get(HEADER_KEY);
            String ts = headers.get(HEADER_TS);
            String nonce = headers.get(HEADER_NONCE);
            String sig = headers.get(HEADER_SIG);
            if (key == null || ts == null || !isValidNonce(nonce) || sig == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(new Stamp(key, Long.parseLong(ts), nonce, sig));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }

        /** The signature is valid for this scope and payload (freshness and replay are the {@link ReplayGuard}'s). */
        public boolean verifies(String scope, byte[] payload) {
            try {
                return isCanonicalKey(key) && Security.verify(decodePublicKey(key), signingInput(scope, ts, nonce, payload),
                        B64D.decode(signature));
            } catch (RuntimeException e) {
                return false;
            }
        }

        public String keyId() {
            return Security.keyId(decodePublicKey(key));
        }
    }

    /** A new Ed25519 key pair. */
    public static Identity generate() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            return new Identity(pair.getPublic(), pair.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this JDK", e);
        }
    }

    /**
     * Loads the identity stored in {@code file}, creating it on first use. The file holds both keys as base64; it is
     * created readable by its owner only where the file system supports it (see {@link #writeOwnerOnly}).
     */
    public static Identity loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                return read(Files.readString(file, StandardCharsets.UTF_8));
            }
            Identity created = generate();
            writeOwnerOnly(file, write(created));
            return created;
        } catch (IOException e) {
            throw new IllegalStateException("cannot read or create the key file " + file, e);
        }
    }

    /**
     * Writes a secret to {@code file} through a sibling temporary file that is created readable and writable by its
     * owner only (where the file system has POSIX permissions), so the secret is never readable by others, not even
     * for a moment; the temporary file then replaces {@code file} in one step. A temporary file left by an earlier
     * attempt is removed first, never reused.
     */
    static void writeOwnerOnly(Path file, String content) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        if (Files.getFileStore(dir != null ? dir : file.toAbsolutePath()).supportsFileAttributeView("posix")) {
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(tmp); // some mounted file systems have no POSIX permissions
        }
        Files.writeString(tmp, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static String write(Identity identity) {
        return "{\"algorithm\":\"Ed25519\",\"public\":\"" + encode(identity.publicKey()) + "\",\"private\":\""
                + B64.encodeToString(identity.privateKey().getEncoded()) + "\"}\n";
    }

    static Identity read(String text) {
        var node = Json.tree(text);
        try {
            KeyFactory factory = KeyFactory.getInstance(ALGORITHM);
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(B64D.decode(node.path("private").asString())));
            return new Identity(decodePublicKey(node.path("public").asString()), privateKey);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("the key file is damaged", e);
        }
    }

    /** A public key as unpadded base64url of its X.509 encoding: its name on the wire. */
    public static String encode(PublicKey key) {
        return B64.encodeToString(key.getEncoded());
    }

    /**
     * True when {@code text} is an Ed25519 public key in its one canonical spelling (unpadded base64url with zero
     * trailing bits). The JDK decoder also accepts padded and non-zero-tail variants of the same key; comparing keys
     * as text is only sound once those are refused.
     */
    public static boolean isCanonicalKey(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        try {
            return encode(decodePublicKey(text)).equals(text);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The shortest and longest nonce a stamp may carry, in base64url characters (12 and 32 random bytes). */
    public static final int NONCE_MIN_LENGTH = 16;
    public static final int NONCE_MAX_LENGTH = 43;

    /**
     * True when {@code text} has the form of a stamp nonce: 16 to 43 characters of the unpadded base64url alphabet
     * ({@code A-Z a-z 0-9 - _}). {@link Identity#stamp} writes 22 (16 random bytes). Every receiver that remembers
     * nonces checks this first, so what it keeps per message stays small whatever a sender puts in the header.
     */
    public static boolean isValidNonce(String text) {
        if (text == null || text.length() < NONCE_MIN_LENGTH || text.length() > NONCE_MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean base64url = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!base64url) {
                return false;
            }
        }
        return true;
    }

    /**
     * The Ed25519 key {@link #encode} wrote.
     *
     * @throws IllegalArgumentException when {@code text} is not one
     */
    public static PublicKey decodePublicKey(String text) {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(B64D.decode(text)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("not an Ed25519 public key", e);
        }
    }

    /** A short stable name for a key: 16 hex characters of its SHA-256. */
    public static String keyId(PublicKey key) {
        return HexFormat.of().formatHex(sha256(key.getEncoded())).substring(0, 16);
    }

    public static String keyId(String publicKeyText) {
        return keyId(decodePublicKey(publicKeyText));
    }

    /** What a person compares by eye, like a phone pairing code: "3F9A-11C2-77B0-D41E". */
    public static String fingerprint(String publicKeyText) {
        String id = keyId(publicKeyText).toUpperCase(Locale.ROOT);
        return id.substring(0, 4) + "-" + id.substring(4, 8) + "-" + id.substring(8, 12) + "-" + id.substring(12, 16);
    }

    public static boolean verify(PublicKey key, byte[] data, byte[] signature) {
        try {
            Signature s = Signature.getInstance(ALGORITHM);
            s.initVerify(key);
            s.update(data);
            return s.verify(signature);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * The scope a request is signed for: its subject, plus the reply seal key when the requester asks for a sealed
     * reply. Requester and responder compute it the same way.
     */
    public static String requestScope(String subject, String replySealKey) {
        return replySealKey == null || replySealKey.isBlank() ? subject : subject + "\nreply-seal:" + replySealKey;
    }

    /** The bytes a stamp signs: a version line, the scope, the time, the nonce and the payload's SHA-256. */
    public static byte[] signingInput(String scope, long ts, String nonce, byte[] payload) {
        String text = "capability-fabric-v1\n" + scope + "\n" + ts + "\n" + nonce + "\n"
                + HexFormat.of().formatHex(sha256(payload == null ? new byte[0] : payload));
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code identity}'s signature of {@code data}, as unpadded base64url. */
    public static String sign(Identity identity, byte[] data) {
        return B64.encodeToString(identity.sign(data));
    }

    /** The base64url {@code signature} by {@code publicKeyText} is valid for {@code data}; false for any bad input. */
    public static boolean verify(String publicKeyText, byte[] data, String signature) {
        try {
            return verify(decodePublicKey(publicKeyText), data, B64D.decode(signature));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A single-use code people can read out and type: 8 ASCII digits from a secure random source. */
    public static String code() {
        int n = RANDOM.nextInt(100_000_000);
        String digits = String.format(Locale.ROOT, "%08d", n);
        return digits.substring(0, 4) + "-" + digits.substring(4);
    }

    /** {@code bytes} random bytes from a secure source, as unpadded base64url. */
    public static String randomToken(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return B64.encodeToString(b);
    }

    /** Compares secrets in constant time. */
    public static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * An X25519 key pair for receiving sealed payloads. An agent publishes the public half in its signed registry
     * entry; the kernel seals each call for the instance it addresses, so other processes on the broker see only
     * ciphertext.
     */
    public record SealKey(PublicKey publicKey, PrivateKey privateKey) {

        public String publicKeyText() {
            return B64.encodeToString(publicKey.getEncoded());
        }

        /** A new X25519 key pair. */
        public static SealKey generate() {
            try {
                KeyPair pair = KeyPairGenerator.getInstance(SEAL_ALGORITHM).generateKeyPair();
                return new SealKey(pair.getPublic(), pair.getPrivate());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("X25519 is not available in this JDK", e);
            }
        }

        /** Loads the key pair stored in {@code file}, creating it on first use (owner-readable only where possible). */
        public static SealKey loadOrCreate(Path file) {
            try {
                if (Files.exists(file)) {
                    var node = Json.tree(Files.readString(file, StandardCharsets.UTF_8));
                    KeyFactory factory = KeyFactory.getInstance(SEAL_ALGORITHM);
                    return new SealKey(
                            factory.generatePublic(new X509EncodedKeySpec(B64D.decode(node.path("public").asString()))),
                            factory.generatePrivate(new PKCS8EncodedKeySpec(B64D.decode(node.path("private").asString()))));
                }
                SealKey created = generate();
                writeOwnerOnly(file, "{\"algorithm\":\"X25519\",\"public\":\"" + created.publicKeyText()
                        + "\",\"private\":\"" + B64.encodeToString(created.privateKey().getEncoded()) + "\"}\n");
                return created;
            } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
                throw new IllegalStateException("cannot read or create the seal key file " + file, e);
            }
        }
    }

    private static final String SEAL_ALGORITHM = "X25519";
    private static final int SEAL_PUBLIC_LENGTH = 44;
    private static final int SEAL_NONCE_LENGTH = 12;

    /**
     * Encrypts {@code plaintext} so that only the holder of {@code recipientSealKey} can read it. A fresh ephemeral
     * X25519 key agrees a secret with the recipient, HKDF-SHA256 turns it into an AES-256 key bound to {@code context}
     * (a call's subject, a reply's {@link #replyScope} or a claim's {@link #claimContext}), and AES-GCM encrypts and
     * authenticates. Output: ephemeral public key (44 bytes, X.509), nonce (12 bytes), ciphertext with tag.
     */
    public static byte[] seal(String recipientSealKey, String context, byte[] plaintext) {
        try {
            KeyFactory factory = KeyFactory.getInstance(SEAL_ALGORITHM);
            PublicKey recipient = factory.generatePublic(new X509EncodedKeySpec(B64D.decode(recipientSealKey)));
            KeyPair ephemeral = KeyPairGenerator.getInstance(SEAL_ALGORITHM).generateKeyPair();
            byte[] ephemeralPublic = ephemeral.getPublic().getEncoded();
            byte[] nonce = new byte[SEAL_NONCE_LENGTH];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, sealingKey(ephemeral.getPrivate(), recipient, ephemeralPublic, context),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] body = cipher.doFinal(plaintext);
            byte[] out = new byte[ephemeralPublic.length + nonce.length + body.length];
            System.arraycopy(ephemeralPublic, 0, out, 0, ephemeralPublic.length);
            System.arraycopy(nonce, 0, out, ephemeralPublic.length, nonce.length);
            System.arraycopy(body, 0, out, ephemeralPublic.length + nonce.length, body.length);
            return out;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("cannot seal for this key", e);
        }
    }

    /** Decrypts what {@link #seal} produced for this key and context; throws when it was altered or not ours. */
    public static byte[] open(SealKey key, String context, byte[] sealed) {
        if (sealed == null || sealed.length < SEAL_PUBLIC_LENGTH + SEAL_NONCE_LENGTH + 16) {
            throw new IllegalArgumentException("the sealed payload is too short");
        }
        try {
            byte[] ephemeralPublic = Arrays.copyOfRange(sealed, 0, SEAL_PUBLIC_LENGTH);
            byte[] nonce = Arrays.copyOfRange(sealed, SEAL_PUBLIC_LENGTH, SEAL_PUBLIC_LENGTH + SEAL_NONCE_LENGTH);
            PublicKey ephemeral = KeyFactory.getInstance(SEAL_ALGORITHM).generatePublic(new X509EncodedKeySpec(ephemeralPublic));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, sealingKey(key.privateKey(), ephemeral, ephemeralPublic, context),
                    new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(sealed, SEAL_PUBLIC_LENGTH + SEAL_NONCE_LENGTH,
                    sealed.length - SEAL_PUBLIC_LENGTH - SEAL_NONCE_LENGTH);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("the sealed payload cannot be opened with this key", e);
        }
    }

    private static SecretKey sealingKey(PrivateKey own, PublicKey other, byte[] ephemeralPublic, String context)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance(SEAL_ALGORITHM);
        agreement.init(own);
        agreement.doPhase(other, true);
        byte[] shared = agreement.generateSecret();
        byte[] info = ("capability-fabric-seal-v1\n" + context).getBytes(StandardCharsets.UTF_8);
        return KDF.getInstance("HKDF-SHA256").deriveKey("AES",
                HKDFParameterSpec.ofExtract().addIKM(shared).addSalt(ephemeralPublic).thenExpand(info, 32));
    }

    /**
     * Refuses stale and replayed messages: a signing key not in canonical form, a nonce not in stamp form
     * ({@link #isValidNonce}), a timestamp outside the allowed clock skew, or a nonce already seen from the same key,
     * is rejected. For each accepted message it keeps only the SHA-256 of key and nonce and the time the message
     * arrived, so an entry has a fixed size. An entry is forgotten once more than twice the window has passed since it
     * arrived: the stamp's timestamp was at most one window ahead of that arrival, so from then on the same stamp is
     * refused as too old.
     *
     * <p>The guard holds at most {@code perKeyCap} entries for one signing key and {@code totalCap} entries in all. A
     * fresh, new message that would go over either cap is refused and not remembered, so a sender that sends new
     * nonces as fast as it can is held to a fixed amount of memory. A key over its cap is refused until its oldest
     * entries are forgotten; when the whole guard is full, every new message is refused until entries are forgotten,
     * so callers that can tell unknown senders apart (a budget for strangers) should apply that budget before this
     * check.
     */
    public static final class ReplayGuard {

        /** Entries one key may hold by default: about 40 messages a second, kept up for the whole retention time. */
        public static final int DEFAULT_PER_KEY_CAP = 10_000;
        /** Entries the guard holds by default for all keys together: under 20 MB of heap. */
        public static final int DEFAULT_TOTAL_CAP = 100_000;

        private final long windowMs = CLOCK_SKEW.toMillis();
        private final int perKeyCap;
        private final int totalCap;
        private final LongSupplier clock;
        /** The guarded state below is read and changed only while holding this guard's monitor. */
        private final Set<String> seen = new HashSet<>();
        private final Map<String, Sender> senders = new HashMap<>();
        private final ArrayDeque<Entry> arrivals = new ArrayDeque<>();

        /** A guard with {@link #DEFAULT_PER_KEY_CAP} and {@link #DEFAULT_TOTAL_CAP}. */
        public ReplayGuard() {
            this(DEFAULT_PER_KEY_CAP, DEFAULT_TOTAL_CAP);
        }

        /** A guard that keeps at most {@code perKeyCap} entries per key and {@code totalCap} entries in all. */
        public ReplayGuard(int perKeyCap, int totalCap) {
            this(perKeyCap, totalCap, System::currentTimeMillis);
        }

        ReplayGuard(int perKeyCap, int totalCap, LongSupplier clock) {
            if (perKeyCap < 1 || totalCap < perKeyCap) {
                throw new IllegalArgumentException("the caps must be at least 1 and the total at least the per-key cap");
            }
            this.perKeyCap = perKeyCap;
            this.totalCap = totalCap;
            this.clock = clock;
        }

        /** Empty when the stamp is fresh and new and the guard has room for it; otherwise the reason it is refused. */
        public Optional<String> check(Stamp stamp) {
            long now = clock.getAsLong();
            if (!isCanonicalKey(stamp.key())) {
                return Optional.of("the signing key is not in its canonical form");
            }
            if (!isValidNonce(stamp.nonce())) {
                return Optional.of("the nonce is not 16 to 43 base64url characters");
            }
            if (Math.abs(now - stamp.ts()) > windowMs) {
                return Optional.of("the message is too old or its clock is wrong");
            }
            String id = seenKey(stamp);
            synchronized (this) {
                forgetArrivedBefore(now - 2 * windowMs);
                if (seen.contains(id)) {
                    return Optional.of("the message was already received once (replay)");
                }
                Sender sender = senders.get(stamp.key());
                if (sender != null && sender.entries >= perKeyCap) {
                    return Optional.of("too many messages from this key within the replay window");
                }
                if (seen.size() >= totalCap) {
                    return Optional.of("too many messages from all senders within the replay window");
                }
                if (sender == null) {
                    sender = new Sender(stamp.key());
                    senders.put(stamp.key(), sender);
                }
                seen.add(id);
                sender.entries++;
                arrivals.addLast(new Entry(id, sender, now));
            }
            return Optional.empty();
        }

        /**
         * Forgets entries from the oldest arrival on, as long as they arrived before {@code limit}. Entries are added
         * in arrival order, so each check does work only for the entries it forgets.
         */
        private void forgetArrivedBefore(long limit) {
            for (Entry oldest = arrivals.peekFirst(); oldest != null && oldest.arrived() < limit;
                    oldest = arrivals.peekFirst()) {
                arrivals.removeFirst();
                seen.remove(oldest.id());
                if (--oldest.sender().entries == 0) {
                    senders.remove(oldest.sender().key);
                }
            }
        }

        /** What the guard keeps for the messages it accepted, for checks in this package. */
        synchronized Set<String> rememberedKeys() {
            return Set.copyOf(seen);
        }

        /** How many entries the guard keeps for {@code key}, for checks in this package. */
        synchronized int entriesOf(String key) {
            Sender sender = senders.get(key);
            return sender == null ? 0 : sender.entries;
        }

        /** The SHA-256 of {@code key|nonce}, base64url: one fixed-size entry per accepted message. */
        private static String seenKey(Stamp stamp) {
            return B64.encodeToString(sha256((stamp.key() + "|" + stamp.nonce()).getBytes(StandardCharsets.UTF_8)));
        }

        /** One signing key the guard keeps entries for, and how many. */
        private static final class Sender {
            private final String key;
            private int entries;

            private Sender(String key) {
                this.key = key;
            }
        }

        /** One accepted message: its {@link #seenKey}, its sender, and the time it arrived. */
        private record Entry(String id, Sender sender, long arrived) {
        }
    }
}
