package dev.capabilityfabric.protocol;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A capability token: authority is what you hold, not who you are. A grant names its holder's public key, the rights
 * it carries and when it ends, and is signed by its issuer. The kernel issues root grants (to an admitted agent, to
 * a guest who claimed an invitation); the owner's devices can issue them too. Whoever holds a grant can hand on a
 * narrower one by signing a child grant for another key, without asking any server: the chain proves every step,
 * each child may only shrink the rights and the time of its parent, and the last holder must sign the request.
 *
 * <p>For agents the rights are the contract they were admitted with (capability ids and the vocabulary they may
 * write); for people they are goals and kinds of action. A {@code null} list means "any", an empty list "none".
 *
 * @param issuer    the signer's public key (base64url)
 * @param holder    the public key allowed to use the grant
 * @param parent    the grant this one narrows, or {@code null} for a root grant
 * @param signature the issuer's signature over everything else
 */
public record Grant(String id, String issuer, String holder, Rights rights, Instant issuedAt, Instant until,
                    Grant parent, String signature) {

    /**
     * What a grant allows. {@code role} is "provider" or a person's role, {@code name} the holder's display name;
     * agents carry {@code capabilities} and {@code produces}, people {@code goals} and {@code actions}.
     */
    public record Rights(String role, String name, List<String> capabilities, List<String> produces,
                         List<String> goals, List<Conversation.ActionType> actions) {

        public Rights {
            capabilities = capabilities == null ? null : List.copyOf(capabilities);
            produces = produces == null ? null : List.copyOf(produces);
            goals = goals == null ? null : List.copyOf(goals);
            actions = actions == null ? null : List.copyOf(actions);
        }

        /**
         * What a guest may do unless the invitation says "view only", and what a delegate may do unless it is handed
         * less; also what a person's grant without an action list allows.
         */
        public static final List<Conversation.ActionType> GUEST_ACTIONS = List.of(Conversation.ActionType.LIST_GOALS,
                Conversation.ActionType.SHOW_GOAL, Conversation.ActionType.SHOW_AGENDA,
                Conversation.ActionType.ADD_TASK, Conversation.ActionType.SET_TASK_STATUS,
                Conversation.ActionType.UPDATE_TASK);
        /** What a guest invited "view only", or a delegate handed a view-only grant, may do: look only. */
        public static final List<Conversation.ActionType> VIEW_ACTIONS = List.of(Conversation.ActionType.LIST_GOALS,
                Conversation.ActionType.SHOW_GOAL, Conversation.ActionType.SHOW_AGENDA);

        /** An admitted agent's contract: the capabilities it may offer and the vocabulary they may write. */
        public static Rights provider(String name, List<String> capabilities, List<String> produces) {
            return new Rights("provider", name, capabilities, produces, List.of(), List.of());
        }

        /** A person's rights: the goals they may see and the kinds of action they may take. */
        public static Rights person(String role, String name, List<String> goals, List<Conversation.ActionType> actions) {
            return new Rights(role, name, List.of(), List.of(), goals, actions);
        }

        /** Every dimension is the same or narrower than {@code outer}'s. */
        public boolean within(Rights outer) {
            return within(capabilities, outer.capabilities) && within(produces, outer.produces)
                    && within(goals, outer.goals) && within(actions, outer.actions);
        }

        private static <T> boolean within(List<T> inner, List<T> outer) {
            return outer == null || inner != null && outer.containsAll(inner);
        }

        public boolean allowsAction(Conversation.ActionType action) {
            return actions == null || actions.contains(action);
        }

        public boolean allowsGoal(String goalId) {
            return goals == null || goalId != null && goals.contains(goalId);
        }
    }

    /** A new grant signed by {@code issuer} for {@code holder}; {@code parent} is {@code null} for a root grant. */
    public static Grant issue(Security.Identity issuer, String holder, Rights rights, Instant until, Grant parent) {
        Grant unsigned = new Grant("g" + Security.randomToken(9), issuer.publicKeyText(), holder, rights, Instant.now(),
                until, parent, null);
        return unsigned.withSignature(Security.sign(issuer, unsigned.signingBytes()));
    }

    private Grant withSignature(String sig) {
        return new Grant(id, issuer, holder, rights, issuedAt, until, parent, sig);
    }

    /**
     * Hands on a narrower grant: signed by this grant's holder, for another key. The end date is capped at this
     * grant's own.
     *
     * @throws IllegalArgumentException when {@code holderIdentity} is not this grant's holder, when
     *                                  {@code newHolder} is missing, or when {@code narrower} claims more than this
     *                                  grant carries (such a chain would never verify)
     */
    public Grant narrow(Security.Identity holderIdentity, String newHolder, Rights narrower, Instant newUntil) {
        if (holderIdentity == null || !holderIdentity.publicKeyText().equals(holder)) {
            throw new IllegalArgumentException("only the holder of grant " + id + " can hand it on");
        }
        if (newHolder == null || newHolder.isBlank()) {
            throw new IllegalArgumentException("a narrower grant needs a holder");
        }
        if (narrower == null || rights == null || !narrower.within(rights)) {
            throw new IllegalArgumentException("the narrower grant claims more than grant " + id + " allows");
        }
        Instant end = until == null ? newUntil : newUntil == null || newUntil.isAfter(until) ? until : newUntil;
        return issue(holderIdentity, newHolder, narrower, end, this);
    }

    byte[] signingBytes() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("issuer", issuer);
        body.put("holder", holder);
        body.put("rights", rights);
        body.put("issuedAt", issuedAt == null ? null : issuedAt.toString());
        body.put("until", until == null ? null : until.toString());
        body.put("parent", parent);
        return Json.canonical(body).getBytes(StandardCharsets.UTF_8);
    }

    /** The first grant of the chain, the one a trusted key issued. */
    public Grant root() {
        return parent == null ? this : parent.root();
    }

    /**
     * Checks the whole chain: every signature, every link (a child is signed by its parent's holder), every
     * narrowing, every end date, and that the root was issued by a trusted key. A grant without an issuer or a
     * holder is a problem too. Empty when the grant is good. It never throws: a grant read from a request header can
     * be anything, so whatever cannot be checked is reported as a problem.
     */
    public List<String> verify(Set<String> trustedRoots, Instant now) {
        List<String> problems = new ArrayList<>();
        Instant at = now == null ? Instant.now() : now;
        Grant g = this;
        int depth = 0;
        while (g != null) {
            if (++depth > 8) {
                problems.add("the chain is too long");
                break;
            }
            try {
                check(g, trustedRoots, at, problems);
            } catch (RuntimeException e) {
                problems.add("grant " + g.id + " could not be checked");
            }
            g = g.parent;
        }
        return problems;
    }

    private static void check(Grant g, Set<String> trustedRoots, Instant now, List<String> problems) {
        boolean noIssuer = blank(g.issuer);
        if (noIssuer) {
            problems.add("grant " + g.id + " names no issuer");
        }
        if (blank(g.holder)) {
            problems.add("grant " + g.id + " names no holder");
        }
        if (noIssuer || g.signature == null || !Security.verify(g.issuer, g.signingBytes(), g.signature)) {
            problems.add("grant " + g.id + " has an invalid signature");
        }
        if (g.until != null && now.isAfter(g.until)) {
            problems.add("grant " + g.id + " ended at " + g.until);
        }
        if (g.parent != null) {
            if (noIssuer || !g.issuer.equals(g.parent.holder)) {
                problems.add("grant " + g.id + " was not issued by the holder of its parent");
            }
            if (g.rights == null || g.parent.rights == null || !g.rights.within(g.parent.rights)) {
                problems.add("grant " + g.id + " claims more than its parent allows");
            }
            if (g.parent.until != null && (g.until == null || g.until.isAfter(g.parent.until))) {
                problems.add("grant " + g.id + " lasts longer than its parent");
            }
        } else if (noIssuer || trustedRoots == null || trustedRoots.stream().noneMatch(g.issuer::equals)) {
            problems.add("grant " + g.id + " was issued by an unknown key");
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    /** Ids of this grant and all its ancestors (a revoked id anywhere in the chain voids it). */
    public List<String> chainIds() {
        List<String> ids = new ArrayList<>();
        for (Grant g = this; g != null; g = g.parent) {
            ids.add(g.id);
        }
        return ids;
    }

    /** The whole chain as one unpadded base64url string, as carried in the {@code Fabric-Grant} header. */
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.bytes(this));
    }

    /** The grant {@link #encode} wrote; empty for blank or unreadable text. Nothing is verified here. */
    public static Optional<Grant> decode(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Json.read(Base64.getUrlDecoder().decode(text.strip()), Grant.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
