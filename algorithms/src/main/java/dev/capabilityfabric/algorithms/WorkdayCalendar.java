package dev.capabilityfabric.algorithms;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Working days (Monday to Friday unless the person works other days), minus configured holidays. */
public final class WorkdayCalendar {

    /** Monday to Friday: the working days when the person has not said otherwise. */
    public static final Set<DayOfWeek> WEEKDAYS = Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    private final Set<DayOfWeek> workingDays;
    private final Set<LocalDate> holidays;

    /** Monday to Friday minus {@code holidays}. */
    public WorkdayCalendar(Set<LocalDate> holidays) {
        this(WEEKDAYS, holidays);
    }

    /** {@code workingDays} minus {@code holidays}; no working days at all means Monday to Friday. */
    public WorkdayCalendar(Set<DayOfWeek> workingDays, Set<LocalDate> holidays) {
        this.workingDays = workingDays == null || workingDays.isEmpty() ? WEEKDAYS : Set.copyOf(workingDays);
        this.holidays = Set.copyOf(holidays);
    }

    /** The person's own working days, by name ({@code MONDAY}); unknown names are ignored. */
    public static WorkdayCalendar of(Collection<String> dayNames) {
        return of(dayNames, List.of());
    }

    /** Working days by name plus holidays as ISO dates; unknown names and unreadable dates are ignored. */
    public static WorkdayCalendar of(Collection<String> dayNames, Collection<String> holidayDates) {
        Set<LocalDate> holidays = new HashSet<>();
        for (String date : holidayDates) {
            try {
                holidays.add(LocalDate.parse(date.strip()));
            } catch (RuntimeException e) {
                // ignored: not a date
            }
        }
        if (dayNames.isEmpty()) {
            return new WorkdayCalendar(WEEKDAYS, holidays);
        }
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String name : dayNames) {
            try {
                days.add(DayOfWeek.valueOf(name.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                // ignored: not a day name
            }
        }
        return new WorkdayCalendar(days, holidays);
    }

    /** True on one of the person's working days that is not a holiday. */
    public boolean isWorkday(LocalDate day) {
        return workingDays.contains(day.getDayOfWeek()) && !holidays.contains(day);
    }

    /**
     * Working days after {@code today} up to and including {@code due}: what is shown as "workdays left".
     * Negative when the due date has passed: minus the working days elapsed since it.
     */
    public int workdaysLeft(LocalDate today, LocalDate due) {
        if (!due.isBefore(today)) {
            int count = 0;
            for (LocalDate d = today.plusDays(1); !d.isAfter(due); d = d.plusDays(1)) {
                if (isWorkday(d)) {
                    count++;
                }
            }
            return count;
        }
        int count = 0;
        for (LocalDate d = due.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
            if (isWorkday(d)) {
                count++;
            }
        }
        return -Math.max(count, 1);
    }

    /**
     * Working days available for work due on {@code due}: those counted by {@link #workdaysLeft}, plus today when
     * today is a working day, since work due today can still be done today. Negative, as in {@link #workdaysLeft},
     * when the due date has passed.
     */
    public int capacityDays(LocalDate today, LocalDate due) {
        int left = workdaysLeft(today, due);
        return left >= 0 && isWorkday(today) ? left + 1 : left;
    }
}
