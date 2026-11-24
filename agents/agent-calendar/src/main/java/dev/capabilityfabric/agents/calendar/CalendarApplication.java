package dev.capabilityfabric.agents.calendar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Provides {@code calendar.workdays}, a deterministic capability: the working days left until each open task's due
 * date, as an annotation any capability may read. Starting it while the system runs shows that a provider without a
 * model joins the same way an AI one does. None of the built-in capabilities reads the annotation (priorities count
 * working days themselves) and the CLI does not show it, since the count goes stale a day after it was made.
 */
@SpringBootApplication
public class CalendarApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalendarApplication.class, args);
    }
}
