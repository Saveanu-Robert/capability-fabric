package dev.capabilityfabric.protocol;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The evolving capability ecosystem: capabilities the system grows itself (genesis), what each capability really
 * changes (counterfactual impact), and how well each provider has performed (earned standing).
 */
public final class Ecosystem {

    private Ecosystem() {
    }

    /** What a grown capability annotates. */
    public enum Scope {
        /** Adds one annotation value to every open task. */
        TASK,
        /** Adds one annotation value to the goal. */
        GOAL
    }

    /**
     * {@code PROBATION}: newly grown, kept or retired by the person once it has shown its value; {@code ACTIVE}:
     * kept; {@code RETIRED}: no longer hosted.
     */
    public enum SpecStatus { PROBATION, ACTIVE, RETIRED }

    /**
     * A capability defined entirely as data, designed by a model and hosted by a generic provider. It can only add
     * annotations under its own id; it can never create, delete or reorder anything.
     */
    public record CapabilitySpec(String id, String version, String name, String description, Scope scope,
                                 JsonNode valueSchema, String instructions, List<String> consumes, List<String> tags,
                                 SpecStatus status, String need, String createdBy, Instant createdAt) {
        public CapabilitySpec {
            consumes = consumes == null ? List.of() : List.copyOf(consumes);
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        /** The annotation key a grown capability writes: its own id. */
        public String annotationKey() {
            return id;
        }

        /** The vocabulary token it writes: its annotation on the goal or on each task. */
        public String token() {
            return scope == Scope.GOAL ? Vocabulary.goalAnnotation(id) : Vocabulary.taskAnnotation(id);
        }

        public CapabilitySpec withStatus(SpecStatus next) {
            return new CapabilitySpec(id, version, name, description, scope, valueSchema, instructions, consumes, tags,
                    next, need, createdBy, createdAt);
        }
    }

    /** What a grown capability may read. */
    public static final Set<String> CONSUMABLE = Set.of(Vocabulary.GOAL_TEXT, Vocabulary.GOAL_DUE_DATE,
            Vocabulary.GOAL_TODAY, Vocabulary.TASKS, Vocabulary.TASK_TITLE, Vocabulary.TASK_DESCRIPTION,
            Vocabulary.TASK_DUE_DATE, Vocabulary.TASK_STATUS, Vocabulary.TASK_EFFORT_HOURS);

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9]*\\.[a-z][a-zA-Z0-9]*");
    private static final Set<String> PRIMITIVES = Set.of("string", "number", "integer", "boolean");
    /**
     * Namespaces a grown capability may not use: those of the built-in capabilities and their annotations, and the
     * names of the system's own parts, so a grown capability can never pass for one of them. Built-in providers
     * declare their manifests in code and are not validated against this list.
     */
    static final Set<String> RESERVED_NAMESPACES = Set.of("goal", "task", "tasks", "plan", "deps", "priority",
            "calendar", "risk", "estimate", "memory", "intent", "capability", "deadline", "person", "kernel",
            "policy", "coach", "security", "profile", "inbox", "rule", "rules", "constitution", "conversation",
            "ledger", "genesis", "forge", "sentinel", "assistant", "agent", "admission", "grant", "approval",
            "decision", "proposal", "fact", "query", "control", "system");

    /**
     * The rules every grown capability must satisfy. Shared by the designer (to repair its own drafts) and the
     * kernel (which decides).
     */
    public static List<String> validate(CapabilitySpec spec, Set<String> takenIds) {
        List<String> problems = new ArrayList<>();
        if (spec.id() == null || !ID.matcher(spec.id()).matches()) {
            problems.add("id must look like namespace.name in lower camel case, for example packing.list");
        } else {
            if (takenIds.contains(spec.id())) {
                problems.add("id " + spec.id() + " is already taken");
            }
            if (RESERVED_NAMESPACES.contains(spec.id().substring(0, spec.id().indexOf('.')))) {
                problems.add("namespace " + spec.id().substring(0, spec.id().indexOf('.')) + " is reserved");
            }
        }
        if (spec.name() == null || spec.name().isBlank() || spec.name().length() > 60) {
            problems.add("name must be 1 to 60 characters");
        }
        if (spec.description() == null || spec.description().length() < 20 || spec.description().length() > 400) {
            problems.add("description must be 20 to 400 characters");
        }
        if (spec.instructions() == null || spec.instructions().length() < 20 || spec.instructions().length() > 1500) {
            problems.add("instructions must be 20 to 1500 characters");
        }
        if (spec.scope() == null) {
            problems.add("scope must be TASK or GOAL");
        }
        for (String token : spec.consumes()) {
            if (!CONSUMABLE.contains(token)) {
                problems.add("cannot consume " + token + "; allowed: " + CONSUMABLE);
            }
        }
        problems.addAll(valueSchemaProblems(spec.valueSchema()));
        return problems;
    }

    /**
     * The JSON Schema dialects a value schema may name in {@code $schema}, written with {@code https} and without a
     * trailing {@code #}. The validator knows these without loading anything; for any other URI it would try to load
     * the dialect from where the URI points.
     */
    private static final Set<String> KNOWN_DIALECTS = Set.of("https://json-schema.org/draft/2020-12/schema",
            "https://json-schema.org/draft/2019-09/schema", "https://json-schema.org/draft-07/schema",
            "https://json-schema.org/draft-06/schema", "https://json-schema.org/draft-04/schema");

    /**
     * Only small, flat value shapes: a primitive, a list of strings, or an object of primitives. The only
     * {@code $} keyword allowed, at any depth, is {@code $schema} naming a known dialect ({@link #KNOWN_DIALECTS}):
     * references and the other core keywords ({@code $ref}, {@code $dynamicRef}, {@code $id}, {@code $defs},
     * {@code $anchor}, ...) point the validator at schemas outside this one, which it may fail to resolve when it
     * later checks a value.
     */
    static List<String> valueSchemaProblems(JsonNode schema) {
        List<String> problems = new ArrayList<>();
        if (schema == null || !schema.isObject()) {
            return List.of("valueSchema must be a JSON Schema object");
        }
        if (schema.toString().length() > 2000) {
            return List.of("valueSchema must be small (at most 2000 characters)");
        }
        String keyword = dollarKeyword(schema);
        if (keyword != null) {
            return List.of("valueSchema must not use " + keyword + " (only $schema naming a JSON Schema draft may start"
                    + " with $)");
        }
        String type = schema.path("type").asString("");
        switch (type) {
            case "string", "number", "integer", "boolean" -> {
            }
            case "array" -> {
                if (!"string".equals(schema.path("items").path("type").asString(""))) {
                    problems.add("an array value must be a list of strings");
                }
            }
            case "object" -> {
                JsonNode props = schema.path("properties");
                if (!props.isObject() || props.isEmpty() || props.size() > 6) {
                    problems.add("an object value needs 1 to 6 properties");
                } else {
                    Iterator<Map.Entry<String, JsonNode>> it = props.properties().iterator();
                    while (it.hasNext()) {
                        Map.Entry<String, JsonNode> p = it.next();
                        if (!PRIMITIVES.contains(p.getValue().path("type").asString(""))) {
                            problems.add("property " + p.getKey() + " must be a string, number, integer or boolean");
                        }
                    }
                }
            }
            default -> problems.add("valueSchema type must be string, number, integer, boolean, array or object");
        }
        if (problems.isEmpty() && !SchemaValidator.isSchema(schema)) {
            problems.add("valueSchema is not a valid JSON Schema");
        }
        return problems;
    }

    /**
     * The first key starting with {@code $} at any depth of {@code node} other than a {@code $schema} that names a
     * known dialect ({@code "$schema"} itself when it names another); else null.
     */
    private static String dollarKeyword(JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String key = field.getKey();
                boolean knownDialect = key.equals("$schema") && field.getValue().isString()
                        && KNOWN_DIALECTS.contains(field.getValue().asString().replaceFirst("^http://", "https://")
                        .replaceFirst("#$", ""));
                if (key.startsWith("$") && !knownDialect) {
                    return key;
                }
                String inner = dollarKeyword(field.getValue());
                if (inner != null) {
                    return inner;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                String inner = dollarKeyword(element);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    /** One task's position with and without a capability. */
    public record TaskDelta(String key, String title, Integer rankActual, Integer rankWithout, Integer priorityActual,
                            Integer priorityWithout, List<String> lostFields) {
        public TaskDelta {
            lostFields = lostFields == null ? List.of() : List.copyOf(lostFields);
        }
    }

    /** What a goal would look like had one capability never contributed. */
    public record ImpactReport(String goalId, String goalText, String capability, int tasks, int tasksReordered,
                               int tasksLosingFields, Double criticalPathActual, Double criticalPathWithout,
                               List<TaskDelta> deltas, List<String> notes, String summary) {
        public ImpactReport {
            deltas = deltas == null ? List.of() : List.copyOf(deltas);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /** A provider's earned standing for one capability. */
    public record Standing(String capability, String provider, String kind, boolean grown, String lifecycle,
                           int calls, int failures, int outcomes, Double meanLogError, int accepted, int rejected,
                           double score, boolean quarantined) {
    }
}
