package dev.capabilityfabric.kernel.security;

import dev.capabilityfabric.protocol.AutonomyLevel;
import dev.capabilityfabric.protocol.CapabilityManifest;
import dev.capabilityfabric.protocol.CapabilityManifest.SideEffect;
import dev.capabilityfabric.protocol.Hashing;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.Vocabulary;
import tools.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What an agent is admitted for, capability by capability: its name, what each one reads ({@code consumes}), needs
 * ({@code requires}) and writes ({@code produces}), the value schemas of the annotations it writes, its side effects
 * outside the ledger, the autonomy level that follows from them and, for the capabilities the kernel itself hands
 * the person's words and data to ({@link #KERNEL_SUPPLIED}), what the kernel gives them. The owner admits exactly
 * this; a registry entry is listed only while its manifest stays within it, so an agent cannot later read more,
 * write more, rename a capability (names are shown to the interpreter), change what its annotations may hold, or
 * declare fewer side effects (and with them a lower autonomy, which would skip the approvals the kernel asks for).
 */
public record Contract(Map<String, Terms> capabilities) {

    /**
     * One capability's terms; the token lists are sorted and without duplicates. {@code name} and
     * {@code annotationSchemas} are {@code null} in terms recorded before they were part of a contract: such terms
     * allow any name and schemas until {@link Contract#completedWith} records them. {@code kernelSupplied} is what
     * the kernel hands the capability in its call beyond the goal context ({@link #KERNEL_SUPPLIED}, in words), and
     * {@code null} for every other capability.
     */
    public record Terms(List<String> consumes, List<String> requires, List<String> produces, SideEffect sideEffect,
                        AutonomyLevel autonomy, String name, Map<String, JsonNode> annotationSchemas,
                        String kernelSupplied) {

        public Terms {
            consumes = sorted(consumes);
            requires = sorted(requires);
            produces = sorted(produces);
            sideEffect = sideEffect == null ? SideEffect.NONE : sideEffect;
            autonomy = autonomy == null ? AutonomyLevel.L0 : autonomy;
            annotationSchemas = annotationSchemas == null ? null
                    : Collections.unmodifiableMap(new TreeMap<>(annotationSchemas));
        }

        /** Terms of a capability the kernel supplies nothing to beyond the goal context. */
        public Terms(List<String> consumes, List<String> requires, List<String> produces, SideEffect sideEffect,
                     AutonomyLevel autonomy, String name, Map<String, JsonNode> annotationSchemas) {
            this(consumes, requires, produces, sideEffect, autonomy, name, annotationSchemas, null);
        }

        /** Terms without a recorded name or annotation schemas (see the record comment). */
        public Terms(List<String> consumes, List<String> requires, List<String> produces, SideEffect sideEffect,
                     AutonomyLevel autonomy) {
            this(consumes, requires, produces, sideEffect, autonomy, null, null, null);
        }

        /**
         * The terms a manifest declares; its autonomy is the one the kernel derives from it, a manifest without a
         * name has the empty name, and a capability the kernel feeds gets what the kernel supplies to it
         * ({@link #KERNEL_SUPPLIED}).
         */
        public static Terms of(CapabilityManifest manifest) {
            return new Terms(manifest.consumes(), manifest.requires(), manifest.produces(), manifest.sideEffect(),
                    manifest.autonomy(), manifest.name() == null ? "" : manifest.name(), manifest.annotationSchemas(),
                    KERNEL_SUPPLIED.get(manifest.id()));
        }

        /**
         * Whether {@code asked} stays within these terms: it reads, needs and writes nothing more, declares the same
         * side effects and no lower autonomy, has the same name and annotation schemas (when these terms record
         * them), and is supplied exactly what these terms say the kernel supplies (so a capability the kernel feeds
         * is called only when the owner admitted it with those words).
         */
        public boolean allows(Terms asked) {
            return Set.copyOf(consumes).containsAll(asked.consumes) && Set.copyOf(requires).containsAll(asked.requires)
                    && Set.copyOf(produces).containsAll(asked.produces) && sideEffect == asked.sideEffect
                    && !autonomy.exceeds(asked.autonomy)
                    && (name == null || name.equals(asked.name))
                    && (annotationSchemas == null || annotationSchemas.equals(asked.annotationSchemas))
                    && Objects.equals(kernelSupplied, asked.kernelSupplied);
        }

        /** Whether these terms record no name or no annotation schemas yet. */
        boolean incomplete() {
            return name == null || annotationSchemas == null;
        }

        /**
         * These terms with the name and annotation schemas of {@code asked} where they record none. What the kernel
         * supplies is never filled in: the owner is asked for that.
         */
        Terms completedWith(Terms asked) {
            return new Terms(consumes, requires, produces, sideEffect, autonomy, name == null ? asked.name : name,
                    annotationSchemas == null ? asked.annotationSchemas : annotationSchemas, kernelSupplied);
        }

        /**
         * Both together: every token of either, the stronger side effect, the higher autonomy, the first name, every
         * annotation schema of either and the first account of what the kernel supplies.
         */
        Terms merge(Terms other) {
            Map<String, JsonNode> schemas = annotationSchemas == null ? other.annotationSchemas
                    : other.annotationSchemas == null ? annotationSchemas : union(other.annotationSchemas, annotationSchemas);
            return new Terms(union(consumes, other.consumes), union(requires, other.requires),
                    union(produces, other.produces),
                    sideEffect.ordinal() >= other.sideEffect.ordinal() ? sideEffect : other.sideEffect,
                    autonomy.exceeds(other.autonomy) ? autonomy : other.autonomy,
                    name != null ? name : other.name, schemas,
                    kernelSupplied != null ? kernelSupplied : other.kernelSupplied);
        }

        /**
         * Everything the capability may read: what it consumes and what it requires (a required token is in the
         * context it gets as well).
         */
        public List<String> reads() {
            return sorted(union(consumes, requires));
        }

        /**
         * "reads tasks, task.title, may write task.effortHours, with external side effects"; for a capability the
         * kernel feeds, what the kernel gives it comes first: "the kernel gives it your rule sentences and the names
         * of the capabilities in use, reads nothing, writes nothing".
         */
        String describe() {
            List<String> reads = reads();
            String read = (kernelSupplied == null ? "" : "the kernel gives it " + kernelSupplied + ", ")
                    + (reads.isEmpty() ? "reads nothing" : "reads " + String.join(", ", reads));
            String writes = produces.isEmpty() ? "writes nothing" : "may write " + String.join(", ", produces);
            String effects = switch (sideEffect) {
                case NONE -> "";
                case EXTERNAL -> ", with external side effects";
                case DESTRUCTIVE -> ", with destructive side effects";
            };
            return read + ", " + writes + effects;
        }

        private static List<String> union(List<String> a, List<String> b) {
            return Stream.concat(a.stream(), b.stream()).toList();
        }

        private static Map<String, JsonNode> union(Map<String, JsonNode> a, Map<String, JsonNode> b) {
            Map<String, JsonNode> all = new TreeMap<>(a);
            all.putAll(b);
            return all;
        }

        private static List<String> sorted(Collection<String> tokens) {
            return tokens == null ? List.of() : List.copyOf(new TreeSet<>(tokens));
        }
    }

    /**
     * The capabilities the kernel hands the person's words and data to in the call itself (beyond the goal context,
     * which is cut to what a capability reads), with what it hands them in words. These words are part of the terms
     * the owner admits ({@link Terms#kernelSupplied}); a provider admitted without them is not listed for that
     * capability, so the kernel never calls it.
     *
     * <p>The words follow what the kernel sends at most. {@code intent.interpret} gets the conversation snapshot:
     * the sentence, today's date, whether automatic work is paused, the goals with their text, status, due date and
     * task counts, their next tasks, the focus goals' tasks with their titles, status, due dates, hours, rank, risk,
     * blockers and priority reason, the unread inbox, the rules, the profile, the recent turns, the steps waiting for
     * approval, the live capabilities' ids and names, and the grown capabilities that are retired or wait for the
     * owner's yes to be kept, with their ids, names and status (a guest's snapshot has no inbox, rules, profile, steps
     * or capabilities). {@code policy.compile} gets the rule sentence, today's date, the kinds of action and the live
     * capabilities' ids and names. {@code capability.design} gets the request, the goal's text and its first task
     * titles, and the capability ids a new capability may not use. Changing these words changes the terms: a provider
     * admitted with the old words is not listed for the capability until the owner admits the new ones.
     */
    public static final Map<String, String> KERNEL_SUPPLIED = Map.of(
            "intent.interpret", "your sentences, goal and task texts, goal and task dates, hours, ranks, risks and "
                    + "blockers, inbox, rules, profile, recent conversation, steps waiting for your approval, the "
                    + "capabilities in use and grown capabilities that are retired or wait for your yes",
            "policy.compile", "your rule sentences and the names of the capabilities in use",
            "capability.design", "your request, the goal's text and task titles, and the ids of capabilities already "
                    + "in use");

    /**
     * What every admitted agent reads whatever its contract says: the blackboard's structure. Each goal context
     * keeps every task's title, status and dependencies, and the goal list gives every open goal's id, status and
     * due date. An agent also reads back the annotations it writes ({@link #STRUCTURE_NOTE}).
     */
    public static final Set<String> STRUCTURE = Set.of(Vocabulary.TASKS, Vocabulary.TASK_TITLE, Vocabulary.TASK_STATUS,
            Vocabulary.TASK_DEPENDS_ON, Vocabulary.GOAL_DUE_DATE);

    /**
     * What every admitted agent reads beyond its terms, in words, as the owner is shown it with every contract:
     * {@link #STRUCTURE}, and the annotations it writes itself, which it always reads back.
     */
    public static final String STRUCTURE_NOTE = "like every admitted agent it also sees every task's title, status "
            + "and dependencies, every open goal's status and due date, and the annotations it writes";

    public Contract {
        capabilities = capabilities == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(capabilities));
    }

    /** The contract these manifests ask for; manifests of the same capability are merged. */
    public static Contract of(Collection<CapabilityManifest> manifests) {
        Map<String, Terms> terms = new TreeMap<>();
        manifests.forEach(m -> terms.merge(m.id(), Terms.of(m), Terms::merge));
        return new Contract(terms);
    }

    /** The capability ids, sorted. */
    public Set<String> ids() {
        return new TreeSet<>(capabilities.keySet());
    }

    /** Every token any capability may write, sorted. */
    public Set<String> produces() {
        return capabilities.values().stream().flatMap(t -> t.produces().stream())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Every token any capability may read (consumes or requires), sorted. */
    public Set<String> reads() {
        return capabilities.values().stream().flatMap(t -> t.reads().stream())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** The capabilities with side effects outside the ledger: "notify.send (external)", sorted by id. */
    public List<String> sideEffects() {
        return capabilities.entrySet().stream().filter(e -> e.getValue().sideEffect() != SideEffect.NONE)
                .map(e -> e.getKey() + " (" + e.getValue().sideEffect().name().toLowerCase(Locale.ROOT) + ")")
                .toList();
    }

    /**
     * What the kernel gives the capabilities it feeds, one line per capability: "intent.interpret: your sentences,
     * ...", sorted by id; empty when it feeds none of them.
     */
    public List<String> kernelSupplied() {
        return capabilities.entrySet().stream().filter(e -> e.getValue().kernelSupplied() != null)
                .map(e -> e.getKey() + ": " + e.getValue().kernelSupplied()).toList();
    }

    /** Whether this contract allows the manifest: it names one of the capabilities, within its terms. */
    public boolean allows(CapabilityManifest manifest) {
        Terms terms = capabilities.get(manifest.id());
        return terms != null && terms.allows(Terms.of(manifest));
    }

    /** Whether every capability {@code asked} offers is in this contract, within its terms. */
    public boolean covers(Contract asked) {
        return asked.capabilities.entrySet().stream().allMatch(e -> {
            Terms terms = capabilities.get(e.getKey());
            return terms != null && terms.allows(e.getValue());
        });
    }

    /** Whether some capability's terms record no name or annotation schemas yet. */
    public boolean incomplete() {
        return capabilities.values().stream().anyMatch(Terms::incomplete);
    }

    /**
     * This contract with the names and annotation schemas {@code asked} declares filled in where its terms record
     * none; everything else stays as it is.
     */
    public Contract completedWith(Contract asked) {
        Map<String, Terms> next = new TreeMap<>(capabilities);
        next.replaceAll((id, terms) -> asked.capabilities.containsKey(id)
                ? terms.completedWith(asked.capabilities.get(id)) : terms);
        return new Contract(next);
    }

    /** This contract with {@code id} on {@code terms} (added or replaced). */
    public Contract with(String id, Terms terms) {
        Map<String, Terms> next = new TreeMap<>(capabilities);
        next.put(id, terms);
        return new Contract(next);
    }

    /** This contract without {@code id}. */
    public Contract without(String id) {
        Map<String, Terms> next = new TreeMap<>(capabilities);
        next.remove(id);
        return new Contract(next);
    }

    /**
     * The same contract always has the same hash, whatever the order it was asked in. Names, annotation schemas and
     * what the kernel supplies are part of it when the terms record them; a field a term does not record (null) is
     * left out of the hashed JSON.
     */
    public String hash() {
        return Hashing.canonicalHash(this);
    }

    /**
     * "task.estimate "Effort estimator" (reads task.title, tasks, may write task.effortHours); risk.watch (reads
     * tasks, may write task.ann.risk.deadline); like every admitted agent it also sees ..." ({@link #STRUCTURE_NOTE}):
     * every capability as {@link #offers()} shows it, with its terms. Empty for a contract without capabilities.
     */
    public String describe() {
        if (capabilities.isEmpty()) {
            return "";
        }
        return capabilities.entrySet().stream().map(e -> offered(e.getKey(), e.getValue()) + " ("
                + e.getValue().describe() + ")").collect(Collectors.joining("; ")) + "; " + STRUCTURE_NOTE;
    }

    /**
     * The capabilities as the owner is shown them, sorted by id: each id followed by the name its terms record, in
     * double quotes ({@code task.estimate "Effort estimator"}), or the id alone when they record no name, the empty
     * name or the id itself. The name is part of the contract and is what the interpreter and the rule compiler are
     * shown for the capability, so the owner reads it before admitting it.
     */
    public List<String> offers() {
        return capabilities.entrySet().stream().map(e -> offered(e.getKey(), e.getValue())).toList();
    }

    /** One capability as {@link #offers()} shows it. */
    static String offered(String id, Terms terms) {
        String name = terms.name() == null ? "" : shownName(terms.name());
        return name.isEmpty() || name.equals(id) ? id : id + " \"" + name + "\"";
    }

    /**
     * A capability name as it is shown inside double quotes: control and format characters (line breaks, terminal
     * escape sequences, direction overrides) become spaces, runs of spaces become one, and double quotes become
     * single ones, so the name stays on one line and ends where its closing quote is.
     */
    static String shownName(String name) {
        return name.replaceAll("[\\p{Cc}\\p{Cf}]+", " ").replaceAll("\\s+", " ").strip().replace('"', '\'');
    }

    /**
     * Whether an admission recorded before contracts were kept, whose grant lets it write {@code granted}, may take
     * these terms as its contract without asking the owner again: every capability reads nothing beyond
     * {@link #STRUCTURE} and what the grant lets it write, has no side effects outside the ledger and is supplied
     * nothing by the kernel ({@link #KERNEL_SUPPLIED}). The grant itself must cover the capabilities and what they
     * write; that is checked apart.
     */
    public boolean withinLegacyGrant(Collection<String> granted) {
        Set<String> implied = new TreeSet<>(STRUCTURE);
        implied.addAll(granted);
        return capabilities.values().stream().allMatch(t -> implied.containsAll(t.reads())
                && t.sideEffect() == SideEffect.NONE && t.kernelSupplied() == null);
    }

    /** The contract as JSON, the form an admission stores it in. */
    public String toJson() {
        return Json.write(this);
    }

    /** The contract stored as {@link #toJson()} wrote it; empty for no text or text that is not one. */
    public static Optional<Contract> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Json.read(json, Contract.class));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
