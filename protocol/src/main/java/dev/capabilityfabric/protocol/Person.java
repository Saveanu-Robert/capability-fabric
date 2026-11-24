package dev.capabilityfabric.protocol;

import java.util.List;
import java.util.Map;

/**
 * The system's model of the person, built from evidence (finished tasks, reported hours, completion times) and
 * corrected by the person in words. Its values reach capabilities as {@code person.*} vocabulary.
 */
public final class Person {

    private Person() {
    }

    /** How far estimates were off for one kind of task: factor = actual / estimated (geometric mean). */
    public record KindBias(String kind, double factor, int samples, String source) {
    }

    /**
     * @param sources where each value comes from: "evidence", "person" (said so) or "default"
     * @param insights short observations derived from the numbers
     */
    public record Profile(double dailyHours, List<String> workingDays, double overallBias, int outcomes,
                          List<KindBias> byKind, Map<String, Integer> doneByPartOfDay, Map<String, Integer> doneByWeekday,
                          int tasksDone, int tasksDropped, double tasksPerWeek, Map<String, String> sources,
                          List<String> insights) {
        public Profile {
            workingDays = workingDays == null ? List.of() : List.copyOf(workingDays);
            byKind = byKind == null ? List.of() : List.copyOf(byKind);
            doneByPartOfDay = doneByPartOfDay == null ? Map.of() : Map.copyOf(doneByPartOfDay);
            doneByWeekday = doneByWeekday == null ? Map.of() : Map.copyOf(doneByWeekday);
            sources = sources == null ? Map.of() : Map.copyOf(sources);
            insights = insights == null ? List.of() : List.copyOf(insights);
        }
    }

    /** The kinds of task the profile distinguishes; tasks are assigned to the nearest kind by meaning. */
    public static final List<String> KINDS = List.of("planning and research", "writing and content",
            "design and creative work", "shopping and errands", "communication and coordination",
            "technical setup", "physical and manual work", "learning and practice");
}
