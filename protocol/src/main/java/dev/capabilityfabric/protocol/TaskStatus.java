package dev.capabilityfabric.protocol;

import java.util.EnumSet;
import java.util.Set;

/** Task lifecycle. Legal transitions are owned here so every component agrees on them. */
public enum TaskStatus {
    TODO,
    IN_PROGRESS,
    BLOCKED,
    DONE,
    /** No longer wanted; kept in the history instead of being deleted. */
    DROPPED;

    /**
     * The statuses a task in this status may move to: an open task may move to any other status (a blocked task may
     * be reported done once whatever held it is over), and a closed task can only be reopened (TODO).
     */
    public Set<TaskStatus> allowedNext() {
        return switch (this) {
            case TODO -> EnumSet.of(IN_PROGRESS, BLOCKED, DONE, DROPPED);
            case IN_PROGRESS -> EnumSet.of(TODO, BLOCKED, DONE, DROPPED);
            case BLOCKED -> EnumSet.of(TODO, IN_PROGRESS, DONE, DROPPED);
            case DONE, DROPPED -> EnumSet.of(TODO);
        };
    }

    public boolean canMoveTo(TaskStatus next) {
        return allowedNext().contains(next);
    }

    /** Neither done nor dropped. */
    public boolean isOpen() {
        return this != DONE && this != DROPPED;
    }
}
