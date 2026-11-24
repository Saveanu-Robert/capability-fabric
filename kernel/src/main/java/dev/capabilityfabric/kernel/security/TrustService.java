package dev.capabilityfabric.kernel.security;

import dev.capabilityfabric.kernel.person.ProfileService;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The person's profile used as a security policy. A change that is unusual for this person (a burst of changes, a
 * new device changing many existing goals or tasks at once, activity at an hour the person is never active) is not
 * refused: it asks for a yes, and that yes must come signed from the person's device, so a stolen session or a
 * prompt-injected sentence cannot quietly act.
 */
@Component
public class TrustService {

    static final Duration BURST_WINDOW = Duration.ofSeconds(60);
    static final Duration NEW_DEVICE = Duration.ofHours(1);
    static final int NEW_DEVICE_BULK = 3;
    static final int NIGHT_EVIDENCE = 10;
    /** The profile's "night" (see the person's part-of-day rhythm): from 22:00 until 05:00. */
    static final LocalTime NIGHT_STARTS = LocalTime.of(22, 0);
    static final LocalTime NIGHT_ENDS = LocalTime.of(5, 0);

    private final ProfileService profile;
    private final int burst;
    private final Clock clock;
    /** Recent changes per key; read and changed only through the map's atomic per-key operations. */
    private final ConcurrentHashMap<String, Deque<Instant>> recent = new ConcurrentHashMap<>();
    private volatile Instant lastSweep = Instant.EPOCH;

    @Autowired
    public TrustService(ProfileService profile, @Value("${fabric.security.burst:6}") int burst) {
        this(profile, burst, Clock.systemDefaultZone());
    }

    /** {@code clock} gives the local hour (its zone) used for the quiet-hours check. */
    TrustService(ProfileService profile, int burst, Clock clock) {
        this.profile = profile;
        this.burst = burst;
        this.clock = clock;
    }

    /** Why these changes need a confirmation from the device, or empty when they are usual for this person. */
    public Optional<String> assess(SecurityService.Caller caller, List<ActionType> changes, Instant now) {
        if (changes.isEmpty() || caller.keyId() == null) {
            return Optional.empty();
        }
        sweep(now);
        int[] inWindow = new int[1];
        // Counted atomically per key, so a sweep running at the same time never forgets these changes.
        recent.compute(caller.keyId(), (k, times) -> {
            Deque<Instant> kept = times == null ? new ArrayDeque<>() : times;
            trim(kept, now);
            changes.forEach(c -> kept.addLast(now));
            inWindow[0] = kept.size();
            return kept;
        });
        // Only changes to what exists count as bulk from a new device: new goals and tasks destroy nothing and can be
        // undone, so a person trying the system right after pairing ("add charcoal, napkins and a speaker") is not
        // asked. Every change counts towards the burst.
        int bulk = (int) changes.stream().filter(c -> c != ActionType.ADD_TASK && c != ActionType.CREATE_GOAL)
                .count();
        return reason(inWindow[0], bulk, caller.since(), now, LocalTime.ofInstant(now, clock.getZone()),
                nightActivity(), burst);
    }

    /**
     * Why changes are unusual, as a phrase the caller puts inside "This is unusual for you (...)", or empty when
     * they are not: at least {@code burst} changes within a minute, a bulk change ({@code changesNow} changes to
     * existing goals and tasks at once) from a device younger than an hour, or a change at night from a person with
     * enough finished tasks and none at night. {@code pairedAt} is
     * when the caller's device was paired or its grant was issued; unknown counts as new, so a delegate's first
     * requests get the same bulk check as a newly paired device. {@code night} is {finished at night, finished}.
     */
    static Optional<String> reason(int changesInLastMinute, int changesNow, Instant pairedAt, Instant now,
                                   LocalTime localTime, int[] night, int burst) {
        if (changesInLastMinute >= burst) {
            return Optional.of(changesInLastMinute + " changes within a minute");
        }
        boolean newDevice = pairedAt == null || pairedAt.plus(NEW_DEVICE).isAfter(now);
        if (newDevice && changesNow >= NEW_DEVICE_BULK) {
            return Optional.of((pairedAt == null ? "this device's access is new"
                    : "this device was paired less than an hour ago") + " and asks for " + changesNow
                    + " changes at once");
        }
        if (isNight(localTime) && night[1] >= NIGHT_EVIDENCE && night[0] == 0) {
            return Optional.of("you have never worked at this hour (" + night[1] + " finished tasks, none at night)");
        }
        return Optional.empty();
    }

    static boolean isNight(LocalTime time) {
        return !time.isBefore(NIGHT_STARTS) || time.isBefore(NIGHT_ENDS);
    }

    /**
     * {finished at night, finished in total} from the profile, which counts only the tasks the owner finished
     * (changes made as {@code human:}): a guest's or an agent's work at night never makes the hour look usual for
     * the owner.
     */
    private int[] nightActivity() {
        Map<String, Integer> parts = profile.profile().doneByPartOfDay();
        int total = parts.values().stream().mapToInt(Integer::intValue).sum();
        return new int[]{parts.getOrDefault("night", 0), total};
    }

    /** Drops what is known about a key (its access ended). */
    public void forget(String keyId) {
        if (keyId != null) {
            recent.remove(keyId);
        }
    }

    int trackedKeys() {
        return recent.size();
    }

    /** Once per window, keys with no change inside the window are forgotten. */
    private void sweep(Instant now) {
        if (now.isBefore(lastSweep.plus(BURST_WINDOW))) {
            return;
        }
        lastSweep = now;
        for (String key : recent.keySet()) {
            recent.computeIfPresent(key, (k, times) -> {
                trim(times, now);
                return times.isEmpty() ? null : times;
            });
        }
    }

    private static void trim(Deque<Instant> times, Instant now) {
        while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(BURST_WINDOW))) {
            times.removeFirst();
        }
    }
}
