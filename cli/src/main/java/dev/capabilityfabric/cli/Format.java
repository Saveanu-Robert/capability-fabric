package dev.capabilityfabric.cli;

import dev.capabilityfabric.protocol.Constitution;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Person;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Plain-text rendering for terminals on any OS: tables and marks use ASCII only; people's text is shown as written,
 * except that every text that comes from the system (goal and task texts, agents' notes, the kernel's and the model's
 * words) goes through {@link #line} or {@link #text} first, so it cannot carry terminal control sequences.
 */
final class Format {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private Format() {
    }

    /**
     * Text from the system as one terminal line. Control characters (C0, DEL and C1, which include the escape
     * character that starts colour and cursor sequences) and the bidirectional embedding, override and isolate marks
     * (which reorder what is shown) are dropped; line breaks and tabs become spaces, so the text cannot start a line
     * of its own that looks like the CLI's.
     */
    static String line(String s) {
        return clean(s, false);
    }

    /** Like {@link #line}, for text of several lines: line breaks (any kind, carriage returns too) become "\n". */
    static String text(String s) {
        return clean(s, true);
    }

    private static String clean(String s, boolean lines) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == '\r' && i < s.length() && s.charAt(i) == '\n') {
                continue; // a CRLF pair is one line break
            }
            if (lineBreak(cp)) {
                sb.append(lines ? '\n' : ' ');
            } else if (cp == '\t') {
                sb.append(lines ? '\t' : ' ');
            } else if (!control(cp)) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    /**
     * Text of several lines ({@link #text}) as a block that starts after a prefix: blank space around it is cut, each
     * line keeps its own indentation, and every line after the first starts with {@code indent}, so the block stays
     * under its first line and no line of it can start at the left edge like the CLI's own. Blank lines stay empty.
     */
    static String indented(String s, String indent) {
        String[] lines = text(s).strip().split("\n", -1);
        StringBuilder sb = new StringBuilder(lines[0].stripTrailing());
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i].stripTrailing();
            sb.append('\n');
            if (!l.isEmpty()) {
                sb.append(indent).append(l);
            }
        }
        return sb.toString();
    }

    private static boolean lineBreak(int cp) {
        return cp == '\n' || cp == '\r' || cp == 0x0B || cp == 0x0C || cp == 0x85 || cp == 0x2028 || cp == 0x2029;
    }

    /** C0, DEL, C1, and the bidirectional embedding, override and isolate marks. */
    static boolean control(int cp) {
        return cp < 0x20 || (cp >= 0x7F && cp <= 0x9F) || (cp >= 0x202A && cp <= 0x202E)
                || (cp >= 0x2066 && cp <= 0x2069);
    }

    /** The first 8 characters of an id, as ids are shown in tables. */
    static String id(String id) {
        return id == null ? "-" : prefix(id, 8);
    }

    /** The first {@code n} characters of {@code s} as one line ({@link #line}). */
    static String prefix(String s, int n) {
        String clean = line(s);
        return clean.substring(0, Math.min(n, clean.length()));
    }

    /**
     * {@code s} as one line ({@link #line}) shortened to {@code width} characters, ending in "..." when it was
     * longer; a character outside the Basic Multilingual Plane is never split.
     */
    static String cut(String s, int width) {
        if (s == null) {
            return "";
        }
        String clean = line(s);
        if (clean.length() <= width) {
            return clean;
        }
        int end = Math.max(0, width - 3);
        if (end > 0 && Character.isHighSurrogate(clean.charAt(end - 1))) {
            end--;
        }
        return clean.substring(0, end) + "...";
    }

    /** An enum constant or code as people read it: {@code AWAITING_APPROVAL} becomes "awaiting approval". */
    static String words(Object value) {
        return value == null ? "-" : line(value.toString()).toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** What a grant lets its holder do: "Ana: may list goals, show a goal on goal 1a2b3c4d until 2026-12-31". */
    static String grant(Grant g) {
        var r = g.rights();
        String who = r.name() == null || r.name().isBlank() ? "a grant" : line(r.name());
        String actions = r.actions() == null ? "do anything" : r.actions().isEmpty() ? "do nothing"
                : r.actions().stream().map(Format::action).collect(Collectors.joining(", "));
        String goals = r.goals() == null ? " on any goal" : r.goals().isEmpty() ? " on no goal"
                : (r.goals().size() == 1 ? " on goal " : " on goals ")
                + r.goals().stream().map(Format::id).collect(Collectors.joining(", "));
        return who + ": may " + actions + goals + (g.until() == null ? "" : " until " + lastDay(g.until()));
    }

    /** The last whole day before an end instant, in this machine's zone (a grant ends at the start of the next day). */
    static LocalDate lastDay(Instant until) {
        return LocalDate.ofInstant(until.minusNanos(1), ZoneId.systemDefault());
    }

    /** One suggested move of a task's date, without the goal's details: "T3 of goal 1a2b3c4d to 2026-12-01". */
    static String move(Conversation.Action a) {
        return move(a, Optional.empty());
    }

    /**
     * One suggested move of a task's date, with the task's title and the goal's text when {@code goal} (the goal the
     * move names) has them: "T3 "Write copy" of "Launch site" to 2026-12-01". What is not known is named by the task
     * key and the goal's short id: "T3 of goal 1a2b3c4d to 2026-12-01".
     */
    static String move(Conversation.Action a, Optional<KernelApi.GoalDetails> goal) {
        String task = a.task() == null || line(a.task()).isBlank() ? "a task" : line(a.task()).strip();
        Optional<String> title = goal.flatMap(d -> d.tasks().stream()
                        .filter(t -> t.key() != null && t.key().equalsIgnoreCase(task)).findFirst())
                .map(TaskView::title).filter(t -> !line(t).isBlank());
        Optional<String> goalText = goal.map(d -> d.goal().text()).filter(t -> t != null && !line(t).isBlank());
        return task + title.map(t -> " \"" + cut(t, 40) + "\"").orElse("")
                + goalText.map(t -> " of \"" + cut(t, 40) + "\"").orElse(a.goal() == null ? "" : " of goal " + id(a.goal()))
                + " to " + a.dueDate();
    }

    /**
     * A suggested new deadline and what it does to the goal's task dates, as the kernel carries it out: every open
     * task with a date moves by as many days as the deadline, and one that would land before {@code today} lands on
     * today; a goal without a deadline gets one and no task date moves. {@code goal} is the goal the suggestion
     * names; without its details, or without a new date, the shift is said in general terms: "move the deadline of
     * goal 1a2b3c4d to 2026-12-15 (its open task dates move by as many days)".
     */
    static String deadlineMove(Conversation.Action a, Optional<KernelApi.GoalDetails> goal, LocalDate today) {
        Optional<KernelApi.GoalSummary> summary = goal.map(KernelApi.GoalDetails::goal);
        String name = summary.map(KernelApi.GoalSummary::text).filter(t -> !line(t).isBlank())
                .map(t -> "\"" + cut(t, 40) + "\"").orElse(a.goal() == null ? "a goal" : "goal " + id(a.goal()));
        if (summary.isEmpty() || a.dueDate() == null) {
            return "move the deadline of " + name + (a.dueDate() == null ? "" : " to " + a.dueDate())
                    + " (its open task dates move by as many days)";
        }
        LocalDate from = summary.get().dueDate();
        if (from == null) {
            return "set the deadline of " + name + " to " + a.dueDate() + " (no task date moves with it)";
        }
        long days = ChronoUnit.DAYS.between(from, a.dueDate());
        List<LocalDate> dated = goal.get().tasks().stream()
                .filter(t -> t != null && t.status() != null && t.status().isOpen() && t.dueDate() != null)
                .map(TaskView::dueDate).toList();
        String head = "move the deadline of " + name + " from " + from + " to " + a.dueDate();
        if (days == 0 || dated.isEmpty()) {
            return head + " (no task date moves with it)";
        }
        long toToday = dated.stream().filter(d -> d.plusDays(days).isBefore(today)).count();
        String stops = toToday == 0 ? "" : dated.size() == 1 ? " but stops at today"
                : toToday == dated.size() ? "; all stop at today" : "; " + toToday + " of them stop at today";
        return head + " (" + dated.size() + (dated.size() == 1 ? " task date moves " : " task dates move ")
                + Math.abs(days) + (Math.abs(days) == 1 ? " day " : " days ") + (days > 0 ? "later" : "earlier")
                + " with it" + stops + ")";
    }

    /** What an action lets someone do, in words; every action has one. */
    static String action(Conversation.ActionType type) {
        return switch (type) {
            case LIST_GOALS -> "list goals";
            case SHOW_GOAL -> "show a goal";
            case SHOW_AGENDA -> "show the agenda";
            case LIST_CAPABILITIES -> "list capabilities";
            case SHOW_DECISIONS -> "show model decisions";
            case LIST_APPROVALS -> "list approvals";
            case SYSTEM_STATUS -> "show the system status";
            case VERIFY_GOAL -> "verify a goal's history";
            case EXPLAIN_IMPACT -> "explain what a capability changed";
            case SHOW_ECOSYSTEM -> "show how capabilities perform";
            case LIST_RULES -> "list rules";
            case SHOW_PROFILE -> "show how you work";
            case SHOW_INBOX -> "show the inbox";
            case CREATE_GOAL -> "create goals";
            case REFINE_GOAL -> "refine a goal";
            case ADD_TASK -> "add tasks";
            case SET_TASK_STATUS -> "change a task's status";
            case UPDATE_TASK -> "update tasks";
            case RESCHEDULE_GOAL -> "reschedule a goal";
            case REOPEN_GOAL -> "reopen a goal";
            case APPROVE_STEP -> "approve a waiting step";
            case RESUME -> "resume automatic work";
            case KEEP_CAPABILITY -> "keep a capability";
            case CORRECT_PROFILE -> "correct how you work";
            case ANSWER_MESSAGE -> "answer inbox messages";
            case LIST_ACCESS -> "list who has access";
            case PAIR_DEVICE -> "pair a device";
            case CANCEL_GOAL -> "cancel a goal";
            case REMOVE_TASK -> "remove tasks";
            case REJECT_STEP -> "reject a waiting step";
            case PAUSE -> "pause automatic work";
            case REQUEST_CAPABILITY -> "request a new capability";
            case RETIRE_CAPABILITY -> "retire a capability";
            case ADD_RULE -> "add rules";
            case REMOVE_RULE -> "remove rules";
            case ADMIT_PROVIDER -> "admit a provider";
            case INVITE -> "invite someone";
            case REVOKE_ACCESS -> "revoke access";
        };
    }

    /** The goal list as a table: id, status, due date, done/total tasks, text. */
    static String goals(List<KernelApi.GoalSummary> goals) {
        StringBuilder sb = new StringBuilder(String.format("%-9s %-17s %-10s %-6s %s%n", "GOAL", "STATUS", "DUE", "DONE", "TEXT"));
        for (KernelApi.GoalSummary g : goals) {
            sb.append(String.format("%-9s %-17s %-10s %-6s %s%n", id(g.id()), words(g.status()),
                    g.dueDate() == null ? "-" : g.dueDate(), g.doneCount() + "/" + g.taskCount(), cut(g.text(), 70)));
        }
        return sb.toString();
    }

    /**
     * One goal in full: its tasks by rank with short notes (time left counted from {@code today}, the kernel's date
     * the details came with, see {@link Fabric#today}), what other capabilities added, the estimates a pace factor
     * changed, and its runs with their steps.
     */
    static String goal(KernelApi.GoalDetails d, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        KernelApi.GoalSummary g = d.goal();
        sb.append("Goal ").append(line(g.id())).append("  ").append(words(g.status()))
                .append(g.dueDate() == null ? "" : "  due " + g.dueDate()).append('\n');
        sb.append("  ").append(line(g.text())).append('\n');
        JsonNode cp = d.annotations().get("deps.criticalPathHours");
        if (cp != null && !cp.isNull()) {
            sb.append("  critical path: ").append(hours(cp.asDouble())).append(" h\n");
        }
        if (!d.tasks().isEmpty()) {
            sb.append('\n').append(String.format("  %-4s %-11s %-9s %4s %4s %7s %-10s %-34s %s%n",
                    "KEY", "STATUS", "ID", "PRIO", "RANK", "EFFORT", "DUE", "TITLE", "NOTES"));
            for (TaskView t : tasks(d)) {
                sb.append(String.format("  %-4s %-11s %-9s %4s %4s %7s %-10s %-34s %s%n", line(t.key()), words(t.status()),
                        id(t.id()), t.priority() == null ? "-" : t.priority(), t.rank() == null ? "-" : t.rank(),
                        t.effortHours() == null ? "-" : hours(t.effortHours()) + "h", t.dueDate() == null ? "-" : t.dueDate(),
                        cut(t.title(), 34), notes(t, today)));
            }
        }
        StringBuilder extra = new StringBuilder();
        d.annotations().forEach((k, v) -> {
            if (!KNOWN_GOAL.contains(k)) {
                extra.append("  ").append(line(k)).append(": ").append(cut(plain(v), 110)).append('\n');
            }
        });
        for (TaskView t : d.tasks()) {
            t.annotations().forEach((k, v) -> {
                if (!KNOWN_TASK.contains(k)) {
                    extra.append("  ").append(line(t.key())).append(' ').append(line(k)).append(": ")
                            .append(cut(plain(v), 100)).append('\n');
                }
            });
        }
        if (!extra.isEmpty()) {
            sb.append("\n  Added by other capabilities:\n").append(extra);
        }
        List<String> paced = new ArrayList<>();
        for (TaskView t : tasks(d)) {
            String c = calibration(t);
            if (c != null) {
                paced.add("  " + line(t.key()) + "   " + c + "\n");
            }
        }
        if (!paced.isEmpty()) {
            sb.append("\n  Estimates adjusted to your pace:\n").append(String.join("", paced));
        }
        if (!d.runs().isEmpty()) {
            sb.append("\n  Runs (newest first):\n");
            for (KernelApi.RunView r : d.runs()) {
                sb.append(String.format(Locale.ROOT, "  %s  %-8s %-12s %-10s %7s  %d model calls%n",
                        TIME.format(r.startedAt()), words(r.trigger()), plan(r.source()),
                        r.outcome() == null ? "running" : words(r.outcome()),
                        r.durationMs() == null ? "-" : String.format(Locale.ROOT, "%.1fs", r.durationMs() / 1000.0),
                        r.llmCalls()));
                for (KernelApi.StepView s : r.steps()) {
                    sb.append(String.format(Locale.ROOT, "      %d %-18s %-10s %-18s %6.1fs %s%n", s.index(),
                            line(s.capability()), words(s.outcome()), s.provider() == null ? "-" : line(s.provider()),
                            s.durationMs() / 1000.0, s.detail() == null ? "" : cut(s.detail(), 60)));
                }
            }
        }
        return sb.toString();
    }

    /**
     * Task annotations of the built-in capabilities that stay out of the list of what other capabilities added: the
     * ones the task table or a section of their own shows (what a task waits for, its deadline risk, the critical
     * path, the pace adjustment) and the ones not shown at all (the depth, the priority's reason, the estimate's
     * confidence, and the calendar's count of working days left, which goes stale a day after it was made).
     */
    private static final Set<String> KNOWN_TASK = Set.of("deps.blockedBy", "deps.depth",
            "deps.critical", "risk.deadline", "calendar.workdaysLeft", "priority.reason", "estimate.confidence",
            "estimate.calibration");
    private static final Set<String> KNOWN_GOAL = Set.of("deps.order", "deps.criticalPathHours");

    private static List<TaskView> tasks(KernelApi.GoalDetails d) {
        List<TaskView> tasks = new ArrayList<>(d.tasks());
        tasks.sort((a, b) -> Integer.compare(a.rank() == null ? 999 : a.rank(), b.rank() == null ? 999 : b.rank()));
        return tasks;
    }

    /** How a run's plan was made, in words: by the planner model, the default plan, or a refresh of changed parts. */
    static String plan(String source) {
        if (source == null) {
            return "-";
        }
        return switch (source) {
            case "LLM" -> "model plan";
            case "TEMPLATE" -> "default plan";
            case "REFRESH" -> "refresh";
            default -> words(source);
        };
    }

    /**
     * How far an effort may lie from the model's hours times the pace factor and still be that product: the
     * estimator rounds efforts to the quarter hour (and to at least a quarter hour), so half a quarter and a little.
     */
    static final double CALIBRATION_TOLERANCE = 0.13;

    /**
     * Hours as every table and line shows them: at most two decimals (the estimator works in quarter hours), at
     * least one, so 2 is "2.0", 0.75 is "0.75" and 1.333 is "1.33".
     */
    static String hours(double hours) {
        BigDecimal h = BigDecimal.valueOf(hours).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return (h.scale() < 1 ? h.setScale(1) : h).toPlainString();
    }

    /**
     * The estimator's pace adjustment of a task as a short phrase ("4.0 h from the model x1.25 = 5.0 h: ..."), or
     * {@code null} when the estimate was not changed by a pace factor. The phrase is shown only while the task's
     * effort still is the model's hours times the factor: once the effort was set otherwise (the person re-estimated
     * it, or a later estimate replaced it), the annotation describes an effort the task no longer has. The reason
     * comes from the estimator and is shortened.
     */
    static String calibration(TaskView t) {
        JsonNode c = t.annotations().get("estimate.calibration");
        if (c == null || !c.isObject() || !c.path("factor").isNumber() || !c.path("modelHours").isNumber()
                || t.effortHours() == null) {
            return null;
        }
        double factor = c.path("factor").asDouble();
        double modelHours = c.path("modelHours").asDouble();
        if (Math.abs(factor - 1.0) < 0.005
                || Math.abs(t.effortHours() - Math.max(0.25, modelHours * factor)) > CALIBRATION_TOLERANCE) {
            return null;
        }
        String basis = c.path("basis").isString() ? ": " + cut(c.path("basis").asString(), 80) : "";
        return hours(modelHours) + " h from the model " + String.format(Locale.ROOT, "x%.2f", factor) + " = "
                + hours(t.effortHours()) + " h" + basis;
    }

    private static String plain(JsonNode v) {
        if (v.isString()) {
            return v.asString();
        }
        if (v.isArray()) {
            List<String> items = new ArrayList<>();
            v.forEach(i -> items.add(i.isString() ? i.asString() : i.toString()));
            return String.join(", ", items);
        }
        return v.toString();
    }

    /**
     * The short notes of a task: what it waits for, its deadline risk, the time left until its due date counted from
     * {@code today}, whether it is on the critical path, and the hours it took. The calendar's stored count of
     * working days left is not shown: it was counted when the plan was made and goes stale a day later.
     */
    private static String notes(TaskView t, LocalDate today) {
        List<String> notes = new ArrayList<>();
        JsonNode blocked = t.annotations().get("deps.blockedBy");
        if (blocked != null && blocked.isArray() && !blocked.isEmpty()) {
            List<String> keys = new ArrayList<>();
            blocked.forEach(k -> keys.add(line(k.asString())));
            notes.add("after " + String.join(",", keys));
        }
        // Deadline risk and time left describe open work; a done or dropped task may still carry old values.
        boolean open = t.status() == null || t.status().isOpen();
        JsonNode risk = t.annotations().get("risk.deadline");
        if (open && risk != null && !"NONE".equals(risk.path("level").asString(""))
                && !"UNKNOWN".equals(risk.path("level").asString(""))) {
            notes.add("risk " + words(risk.path("level").asString()));
        }
        if (open && t.dueDate() != null) {
            notes.add(timeLeft(t.dueDate(), today));
        }
        JsonNode critical = t.annotations().get("deps.critical");
        if (critical != null && critical.asBoolean()) {
            notes.add("critical");
        }
        if (t.actualHours() != null) {
            notes.add("took " + hours(t.actualHours()) + "h");
        }
        return String.join("; ", notes);
    }

    /** Calendar days from {@code today} to {@code due}, in words: "3 days left", "due today", "2 days overdue". */
    static String timeLeft(LocalDate due, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, due);
        if (days == 0) {
            return "due today";
        }
        long n = Math.abs(days);
        return n + (n == 1 ? " day" : " days") + (days > 0 ? " left" : " overdue");
    }

    static String impact(Ecosystem.ImpactReport r) {
        StringBuilder sb = new StringBuilder("  What " + line(r.capability()) + " changed in \"" + cut(r.goalText(), 50)
                + "\"\n");
        sb.append("  ").append(line(r.summary())).append('\n');
        if (!r.deltas().isEmpty()) {
            sb.append(String.format("  %-4s %-34s %9s %9s  %s%n", "KEY", "TITLE", "RANK NOW", "WITHOUT", "WOULD LOSE"));
            for (var d : r.deltas()) {
                sb.append(String.format("  %-4s %-34s %9s %9s  %s%n", line(d.key()), cut(d.title(), 34),
                        d.rankActual() == null ? "-" : d.rankActual(), d.rankWithout() == null ? "-" : d.rankWithout(),
                        line(String.join(", ", d.lostFields()))));
            }
        }
        r.notes().forEach(n -> sb.append("  note: ").append(line(n)).append('\n'));
        return sb.toString();
    }

    /**
     * How capabilities perform. AVG ERROR is the typical ratio between a capability's predictions and what happened
     * (x1.00 is exact), from the mean of the logarithms of those ratios.
     */
    static String ecosystem(List<Ecosystem.Standing> standings) {
        StringBuilder sb = new StringBuilder(String.format("  %-18s %-20s %-10s %5s %5s %8s %9s %6s  %s%n",
                "CAPABILITY", "PROVIDER", "LIFECYCLE", "CALLS", "FAIL", "OUTCOMES", "AVG ERROR", "SCORE", ""));
        for (var s : standings) {
            sb.append(String.format(Locale.ROOT, "  %-18s %-20s %-10s %5d %5d %8d %9s %6.2f  %s%n",
                    cut(s.capability(), 18), cut(s.provider(), 20),
                    s.lifecycle() == null ? "" : line(s.lifecycle()).toLowerCase(Locale.ROOT), s.calls(), s.failures(),
                    s.outcomes(),
                    s.meanLogError() == null ? "-" : String.format(Locale.ROOT, "x%.2f", Math.exp(s.meanLogError())),
                    s.score(), s.quarantined() ? "quarantined" : s.grown() ? "grown" : ""));
        }
        return sb.toString();
    }

    /** What can be done now, as a table; an overdue due date ends in "!". */
    static String agenda(List<Conversation.AgendaItem> items) {
        StringBuilder sb = new StringBuilder(String.format("  %-4s %-36s %-10s %7s %-8s %s%n", "TASK", "TITLE", "DUE",
                "EFFORT", "RISK", "GOAL"));
        for (Conversation.AgendaItem i : items) {
            sb.append(String.format("  %-4s %-36s %-10s %7s %-8s %s%n", line(i.task()), cut(i.title(), 36),
                    i.dueDate() == null ? "-" : i.overdue() ? i.dueDate() + "!" : i.dueDate().toString(),
                    i.effortHours() == null ? "-" : hours(i.effortHours()) + "h",
                    i.risk() == null || "NONE".equals(i.risk()) || "UNKNOWN".equals(i.risk()) ? "" : words(i.risk()),
                    id(i.goalId()) + " " + cut(i.goalText(), 40)));
        }
        return sb.toString();
    }

    static String capabilities(List<KernelApi.CapabilityView> caps) {
        StringBuilder sb = new StringBuilder(String.format("%-18s %-7s %-18s %-13s %-8s %-3s %-4s %s%n",
                "CAPABILITY", "VERSION", "PROVIDER", "KIND", "MODE", "AUT", "INST", "PRODUCES"));
        for (KernelApi.CapabilityView c : caps) {
            sb.append(String.format(Locale.ROOT, "%-18s %-7s %-18s %-13s %-8s %-3s %-4d %s%n", line(c.id()),
                    line(c.version()), line(c.provider()), "AI".equals(c.kind()) ? "AI" : words(c.kind()),
                    words(c.mode()), line(c.autonomy()), c.instances(), line(String.join(", ", c.produces()))));
        }
        return sb.toString();
    }

    static String decisions(List<KernelApi.DecisionView> decisions) {
        StringBuilder sb = new StringBuilder(String.format("%-16s %-14s %-14s %-6s %-3s %8s %7s %7s%n",
                "CAPABILITY", "PURPOSE", "MODEL", "VALID", "TRY", "LATENCY", "IN", "OUT"));
        for (KernelApi.DecisionView d : decisions) {
            sb.append(String.format(Locale.ROOT, "%-16s %-14s %-14s %-6s %-3d %7.1fs %7s %7s%s%n", line(d.capability()),
                    line(d.purpose()), line(d.model()),
                    d.valid(), d.attempt(), d.latencyMs() / 1000.0, d.promptTokens() == null ? "-" : d.promptTokens(),
                    d.completionTokens() == null ? "-" : d.completionTokens(), d.replayed() ? "  (replayed)" : ""));
        }
        return sb.toString();
    }

    static String approvals(List<KernelApi.ApprovalView> approvals) {
        if (approvals.isEmpty()) {
            return "No pending approvals.\n";
        }
        StringBuilder sb = new StringBuilder(String.format("%-9s %-9s %-18s %-4s %s%n", "ID", "GOAL", "CAPABILITY", "AUT", "REASON"));
        for (KernelApi.ApprovalView a : approvals) {
            sb.append(String.format("%-9s %-9s %-18s %-4s %s%n", id(a.id()), id(a.goalId()), line(a.capability()),
                    line(a.autonomy()), line(a.reason())));
        }
        return sb.toString();
    }

    /** A list read's count line that says no more than the rows under it ("12 goals"), or no count at all. */
    private static final Pattern PLAIN_COUNT = Pattern.compile("\\d*|\\d+ (?:goals|tasks you can do now|unread messages)");
    private static final Pattern NONE = Pattern.compile("0 (?!of\\b)(\\D.*?)[.!]*");

    /**
     * The kernel's line about a list it showed, when it says more than the rows do: which goals a filtered list
     * keeps ("3 overdue goals"), or that a long list was cut. Empty for a plain count ("12 goals") and when the
     * reply has no such list.
     */
    static Optional<String> countLine(Conversation.Reply reply, Conversation.ActionType type) {
        return reply.executed().stream().filter(e -> e.type() == type && e.ok() && e.message() != null)
                .map(e -> line(e.message()).strip()).filter(m -> !PLAIN_COUNT.matcher(m).matches()).findFirst();
    }

    /**
     * What an empty list says: a filtered one in the kernel's words ("0 overdue goals" becomes "No overdue goals."),
     * any other in {@code otherwise}.
     */
    static String empty(Optional<String> countLine, String otherwise) {
        if (countLine.isEmpty()) {
            return otherwise;
        }
        Matcher none = NONE.matcher(countLine.get());
        return none.matches() ? "No " + none.group(1) + "." : countLine.get();
    }

    /**
     * Everything the person sees after one turn: the reply text when there is one, a line for every action that
     * changed something or failed (the kernel's own words, so a turn without reply text still says what happened),
     * and any data to show, under the kernel's count line when that says more than the rows do ({@link #countLine}).
     * A reply with nothing to show and no question says so. The time left of a goal's tasks is counted from
     * {@code today}, the kernel's date the reply came with ({@link Fabric#today}).
     */
    static String reply(Conversation.Reply reply, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        if (reply.reply() != null && !text(reply.reply()).isBlank() && !asksAlready(reply)) {
            sb.append(text(reply.reply()).strip()).append('\n');
        }
        for (Conversation.Executed e : reply.executed()) {
            if ((!e.type().readOnly() || !e.ok() || e.type() == Conversation.ActionType.LIST_ACCESS)
                    && !(e.ok() && saidAlready(reply.reply(), e))) {
                sb.append(outcome(e)).append('\n');
            }
        }
        Optional<String> agendaCount = countLine(reply, Conversation.ActionType.SHOW_AGENDA);
        if (!reply.agenda().isEmpty()) {
            sb.append('\n');
            agendaCount.ifPresent(c -> sb.append("  ").append(c).append('\n'));
            sb.append(agenda(reply.agenda()));
        } else if (listed(reply, Conversation.ActionType.SHOW_AGENDA)) {
            sb.append('\n').append("  ").append(empty(agendaCount,
                    "Nothing to do right now: no open task can be started.")).append('\n');
        }
        Optional<String> goalCount = countLine(reply, Conversation.ActionType.LIST_GOALS);
        if (!reply.goals().isEmpty()) {
            sb.append('\n');
            goalCount.ifPresent(c -> sb.append("  ").append(c).append('\n'));
            sb.append(goals(reply.goals()));
        } else if (listed(reply, Conversation.ActionType.LIST_GOALS)) {
            sb.append('\n').append("  ").append(empty(goalCount,
                    "No goals yet. Say what you want to achieve, for example: \"plan my move by March\".")).append('\n');
        }
        reply.details().forEach(d -> sb.append('\n').append(goal(d, today)));
        if (!reply.capabilities().isEmpty()) {
            sb.append('\n').append(capabilities(reply.capabilities()));
        } else if (listed(reply, Conversation.ActionType.LIST_CAPABILITIES)) {
            sb.append('\n').append("  No capability is live right now.\n");
        }
        if (!reply.decisions().isEmpty()) {
            sb.append('\n').append(decisions(reply.decisions()));
        } else if (listed(reply, Conversation.ActionType.SHOW_DECISIONS)) {
            sb.append('\n').append("  No model decisions recorded yet.\n");
        }
        if (!reply.approvals().isEmpty() || listed(reply, Conversation.ActionType.LIST_APPROVALS)) {
            sb.append('\n').append(approvals(reply.approvals()));
        }
        if (reply.status() != null) {
            sb.append('\n').append(String.format(Locale.ROOT,
                    "automatic work: %s, live providers: %d, active runs: %d, embeddings: %s%n",
                    reply.status().paused() ? "paused" : "running", reply.status().liveCapabilities(),
                    reply.status().activeRuns(), line(reply.status().embeddingModel())));
        }
        reply.hashes().forEach(h -> sb.append(String.format(Locale.ROOT, "goal %s: %d tasks, fingerprint %s%n",
                id(h.goalId()), h.taskCount(), prefix(h.hash(), 16))));
        reply.impacts().forEach(r -> sb.append('\n').append(impact(r)));
        if (!reply.ecosystem().isEmpty()) {
            sb.append('\n').append(ecosystem(reply.ecosystem()));
        } else if (listed(reply, Conversation.ActionType.SHOW_ECOSYSTEM)) {
            sb.append('\n').append("  No capability has a track record yet.\n");
        }
        reply.grown().forEach(g -> sb.append(String.format("  grown: %-20s %-10s for \"%s\"%n", line(g.id()), g.status(),
                cut(g.need(), 60))));
        if (reply.ruleImpact() != null) {
            sb.append('\n').append(ruleImpact(reply.ruleImpact()));
        }
        if (listed(reply, Conversation.ActionType.LIST_RULES)) {
            sb.append('\n').append(rules(reply.rules()));
        }
        if (reply.profile() != null) {
            sb.append('\n').append(profile(reply.profile()));
        }
        if (listed(reply, Conversation.ActionType.SHOW_INBOX)) {
            Optional<String> inboxCount = countLine(reply, Conversation.ActionType.SHOW_INBOX);
            sb.append('\n');
            if (reply.inbox().isEmpty()) {
                sb.append("  ").append(empty(inboxCount, "Your inbox is empty.")).append('\n');
            } else {
                inboxCount.ifPresent(c -> sb.append("  ").append(c).append('\n'));
                sb.append(inbox(reply.inbox()));
            }
        }
        if (sb.isEmpty() && reply.confirmation() == null) {
            sb.append("Nothing changed.\n");
        }
        return sb.toString();
    }

    /**
     * True when the reply text is the pending question itself, which the confirmation prompt prints on its own line.
     */
    static boolean asksAlready(Conversation.Reply reply) {
        return reply.confirmation() != null && reply.confirmation().question() != null && reply.reply() != null
                && text(reply.reply()).strip().equals(text(reply.confirmation().question()).strip());
    }

    /**
     * True when the reply text already is the action's line: "Dismissed." over "dismissed: <message>" says the same
     * thing twice. Compares the text without its closing punctuation with the message up to its first colon.
     */
    static boolean saidAlready(String replyText, Conversation.Executed e) {
        if (replyText == null || e.message() == null) {
            return false;
        }
        String said = replyText.strip().replaceAll("[.!]+$", "").toLowerCase(Locale.ROOT);
        int colon = e.message().indexOf(':');
        String head = (colon < 0 ? e.message() : e.message().substring(0, colon)).strip().toLowerCase(Locale.ROOT);
        return !said.isEmpty() && said.equals(head);
    }

    /** True when the reply carries a successful action of this type, so its section is shown even when empty. */
    private static boolean listed(Conversation.Reply reply, Conversation.ActionType type) {
        return reply.executed().stream().anyMatch(e -> e.type() == type && e.ok());
    }

    /**
     * One executed action: "done: ..." or "could not: ...". "could not" is said once: a failure text that starts
     * with "could not:" or "not done:" loses that prefix, and one that is already a sentence about not being able
     * to ("could not ...", "I could not ...") is shown as it is. The kernel's text keeps its line breaks (a pairing
     * instruction holds a command to copy on a line of its own); its later lines are indented under the first
     * ({@link #indented}), deeper than the CLI's own lines, so none of them reads as another outcome.
     */
    static String outcome(Conversation.Executed e) {
        String message = e.message() == null || line(e.message()).isBlank() ? action(e.type())
                : indented(e.message(), "    ");
        if (e.ok()) {
            return "  done: " + message;
        }
        String bare = message.replaceFirst("(?i)^((could not|not done)\\s*:\\s*)+", "");
        if (bare.isBlank()) {
            bare = action(e.type());
        }
        return bare.toLowerCase(Locale.ROOT).matches("(?s)(i )?could not\\b.*") ? "  " + bare
                : "  could not: " + bare;
    }

    static String rules(List<Constitution.Rule> rules) {
        if (rules.isEmpty()) {
            return "  No rules yet. Say one, for example: \"never let agents change my interview goal without asking\".\n";
        }
        StringBuilder sb = new StringBuilder();
        for (var r : rules) {
            sb.append(String.format("  %-9s \"%s\"%n            enforced as: %s%n", line(r.id()), cut(r.text(), 90),
                    line(Constitution.describe(r))));
        }
        return sb.toString();
    }

    static String ruleImpact(Constitution.Impact impact) {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  Dry run over the last %d days: %d "
                + "capability step(s) and %d of your change(s) affected, %d upcoming day(s) over a limit%n",
                impact.days(), impact.agentStepsAffected(), impact.personActionsAffected(), impact.dueDayBreaches()));
        impact.examples().forEach(e -> sb.append("    - ").append(line(e)).append('\n'));
        return sb.toString();
    }

    /**
     * How the person works: capacity, working days, pace overall and per kind of work, with where each value came from.
     */
    static String profile(Person.Profile p) {
        StringBuilder sb = new StringBuilder("  How you work\n");
        sb.append(String.format("  %-16s %s h a day%s%n", "capacity", p.dailyHours(), source(p, "dailyHours")));
        sb.append(String.format("  %-16s %s%s%n", "working days", String.join(", ", p.workingDays().stream()
                .map(Format::day).toList()), source(p, "workingDays")));
        sb.append(String.format(Locale.ROOT, "  %-16s x%.2f of the estimate over %d finished task(s)%s%n",
                "your pace", p.overallBias(), p.outcomes(), source(p, "estimateBias")));
        for (var k : p.byKind()) {
            sb.append(String.format(Locale.ROOT, "  %-16s x%.2f on %s (%d task(s))%n", "", k.factor(),
                    line(k.kind()), k.samples()));
        }
        // A rate of 0 means the kernel has under a week of history to count from: no rate is shown then.
        String rate = p.tasksPerWeek() > 0 ? String.format(Locale.ROOT, ", %.1f a week", p.tasksPerWeek()) : "";
        sb.append(String.format(Locale.ROOT, "  %-16s %d done, %d dropped%s%n", "tasks",
                p.tasksDone(), p.tasksDropped(), rate));
        p.insights().forEach(i -> sb.append("  - ").append(line(i)).append('\n'));
        return sb.toString();
    }

    /** A day name as "Mon": its first three letters, the first upper case. */
    private static String day(String name) {
        String d = line(name);
        return d.isEmpty() ? d : d.substring(0, 1).toUpperCase(Locale.ROOT)
                + d.substring(1, Math.min(3, d.length())).toLowerCase(Locale.ROOT);
    }

    private static String source(Person.Profile p, String key) {
        String s = p.sources().get(key);
        return s == null ? "" : "  (" + line(s) + ")";
    }

    static String inbox(List<Inbox.Message> messages) {
        if (messages.isEmpty()) {
            return "  Your inbox is empty.\n";
        }
        StringBuilder sb = new StringBuilder();
        for (var m : messages) {
            sb.append(message(m));
        }
        return sb.toString();
    }

    /** One inbox message: short id, kind and goal, the text, and the suggestion in words when it has one. */
    static String message(Inbox.Message m) {
        StringBuilder sb = new StringBuilder(String.format("  [%s] %s%s%n", id(m.id()), words(m.kind()),
                m.goalText() == null ? "" : " - " + cut(m.goalText(), 60)));
        sb.append("    ").append(line(m.text())).append('\n');
        if (m.suggestionText() != null) {
            sb.append("    suggestion: ").append(line(m.suggestionText())).append('\n');
        }
        return sb.toString();
    }
}
