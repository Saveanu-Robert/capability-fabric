package dev.capabilityfabric.cli;

import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.GoalContext;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.Reply;
import dev.capabilityfabric.protocol.Subjects;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The guided demo ({@code fabric --demo}): every feature of the system, step by step, on the system this device is
 * paired with. Each step says what it shows, sends a sentence (or a typed call) in a stated mode, prints the answer as
 * a person sees it, and compares the outcome with the one the step expects. A question the system asks is answered by
 * the step, out loud; key steps also check what the answer says (a title, a status, an amount). The demo keeps its
 * changes below the security policy's pace (six changes within a minute are unusual), creates its own goals and
 * cancels them at the end. It is meant for a fresh system: the demo scripts start one, run it and remove everything
 * afterwards (see README.md).
 */
final class Demo {

    /** What a step does: say a sentence, say it for a script (JSON), send typed actions, or stop and resume. */
    enum Kind { SAY, JSON, TYPED, STOP, RESUME }

    /**
     * One step: the chapter it belongs to, what it shows, how it is sent, the sentence or typed actions (placeholders
     * in angle brackets are filled in when the step runs, see {@link #fill}), the answers to the questions it may
     * get ("y", "n", one per question), the outcomes it accepts, the reason code a rejection should carry, whether
     * it waits for the planning it starts, and text its answer must contain (null for none).
     */
    record Step(String chapter, String shows, Kind kind, Conversation.Mode mode, String text, String answers,
                Set<String> accepts, String code, boolean waits, String says) {

        /** This step, whose answer must also contain {@code text} (a title, a status, an amount). */
        Step says(String text) {
            return new Step(chapter, shows, kind, mode, this.text, answers, accepts, code, waits, text);
        }
    }

    private static final Conversation.Mode ASSIST = Conversation.Mode.ASSIST;
    private static final Conversation.Mode DECIDE = Conversation.Mode.DECIDE;
    private static final Conversation.Mode CONVERSE = Conversation.Mode.CONVERSE;
    private static final Set<String> DONE = Set.of("COMPLETED");
    private static final Set<String> SHOWN = Set.of("ANSWERED");
    private static final Set<String> REFUSED = Set.of("REJECTED");

    /** A change that is done and reported (the CLI's default mode). */
    private static Step does(String chapter, String shows, String text) {
        return new Step(chapter, shows, Kind.SAY, ASSIST, text, "", DONE, null, false, null);
    }

    /** A question answered with data. */
    private static Step asks(String chapter, String shows, String text) {
        return new Step(chapter, shows, Kind.SAY, ASSIST, text, "", SHOWN, null, false, null);
    }

    /** A sentence sent the way a service sends it: decisive, the outcome printed as JSON. */
    private static Step service(String chapter, String shows, String text, Set<String> accepts, String code) {
        return new Step(chapter, shows, Kind.JSON, DECIDE, text, "", accepts, code, false, null);
    }

    /** Typed actions (no model involved). */
    private static Step typed(String chapter, String shows, String json, Set<String> accepts, String code) {
        return new Step(chapter, shows, Kind.TYPED, DECIDE, json, "", accepts, code, false, null);
    }

    static final String SYSTEM = "🩺 The system";
    static final String GOALS = "🎯 Goals and the agents' plans";
    static final String TASKS = "✅ Tasks: news, estimates and time spent";
    static final String DATES = "📅 Dates and the agenda";
    static final String MANY = "🔀 Several things at once, memory and undo";
    static final String PERSON = "👤 How you work, rules and automatic work";
    static final String GROWTH = "🌱 Capabilities the system grows, inbox and ecosystem";
    static final String SECURITY = "🔐 Security, sharing and the three modes";
    static final String SERVICES = "🤖 Calls from another service (JSON and typed actions)";
    static final String EDGES = "🧪 Edge cases and things the system will not do";
    static final String CLEANUP = "🧹 Cleaning up";

    /** Every step of the demo, in order: later steps rely on what earlier ones created. */
    static List<Step> steps() {
        List<Step> s = new ArrayList<>();
        s.add(asks(SYSTEM, "Is everything up? Answered from the system's own state, never by the model.",
                "is everything running?"));
        s.add(asks(SYSTEM, "What the system can do: answered by the kernel itself, without a model.",
                "what can you do?"));
        s.add(asks(SYSTEM, "The capabilities live right now, offered by the admitted agents.",
                "what capabilities do you have?"));

        s.add(new Step(GOALS, "A goal with a deadline: the agents decompose it into tasks, order them and give each a "
                + "date. The demo waits for that plan.", Kind.SAY, ASSIST, "new goal: Plan the picnic by Saturday",
                "", DONE, null, true, null));
        s.add(does(GOALS, "Several tasks in one sentence: one task each, titled in your words.",
                "add charcoal, napkins and a speaker to the picnic").says("\"Speaker\" to \"Plan the picnic\""));
        s.add(does(GOALS, "A second goal; its deadline comes from \"by December 15\".",
                "new goal: Renew my passport by December 15").says("created goal \"Renew my passport\" due "));
        s.add(does(GOALS, "Two goals about a report (used later to show an ambiguous request).",
                "new goal: Finish the quarterly report by Friday")
                .says("created goal \"Finish the quarterly report\" due "));
        s.add(does(GOALS, "", "new goal: Write the annual report by December 20")
                .says("created goal \"Write the annual report\" due "));
        s.add(does(GOALS, "A long sentence gets a short title in your words, and \"deadline end of the day\" is the "
                + "deadline (today).", "I will go on a business trip tomorrow, create me a checklist with what I "
                + "should take, deadline end of the day")
                .says("Checklist with what I should take for the business trip\" due "));
        s.add(does(GOALS, "What is going on, then what to do: \"her\" becomes \"my wife\", and \"by this weekend\" "
                + "is Sunday.", "next Tuesday is my wife's birthday, create me a task by this weekend to go and buy "
                + "her a present").says("created goal \"Buy my wife a present\" due "));
        s.add(does(GOALS, "A reminder is a goal of its own, due on the day it names.",
                "pls remind me to call the dentist on thursday").says("created goal \"Call the dentist\" due "));
        s.add(asks(GOALS, "Every goal, with its status and deadline.", "show me all my goals"));
        s.add(asks(GOALS, "One goal with its tasks, dependencies and priorities.", "show me the picnic"));

        s.add(does(TASKS, "News about a task: done.", "got the charcoal").says("\"Charcoal\" is now done"));
        s.add(does(TASKS, "Taking it back: the task is open again.", "I didn't finish the charcoal")
                .says("\"Charcoal\" is now todo"));
        s.add(does(TASKS, "Blocked, with the reason ignored.", "stuck on the speaker, my friend hasn't replied")
                .says("\"Speaker\" is now blocked"));
        s.add(does(TASKS, "Unblocked: back to to-do.", "the speaker isn't blocked anymore")
                .says("\"Speaker\" is now todo"));
        s.add(does(TASKS, "An estimate.", "the speaker will take about an hour")
                .says("\"Speaker\" is now estimated at 1.0 h"));
        s.add(does(TASKS, "A new date for a task.", "the napkins can wait until Monday")
                .says("\"Napkins\" is now due "));
        s.add(does(TASKS, "Done, and the time it took is recorded (the agents learn your pace from it).",
                "finished the charcoal, it took 40 minutes").says("0.67 h recorded"));
        s.add(does(TASKS, "A thing a goal needs is a task titled with that thing.",
                "the picnic also needs a blanket").says("\"Blanket\""));

        s.add(does(DATES, "Moving a goal moves every open task date with it.", "move the picnic to Sunday")
                .says("\"Plan the picnic\" is now due "));
        s.add(does(DATES, "A relative move.", "push the passport back a week")
                .says("\"Renew my passport\" is now due "));
        s.add(new Step(DATES, "A new plan for a goal, with the capabilities live now.", Kind.SAY, ASSIST,
                "plan the picnic again", "", DONE, null, true, null).says("re-planning \"Plan the picnic\""));
        s.add(asks(DATES, "The agenda: what can start now, most urgent first.", "what should I do today?"));
        s.add(asks(DATES, "Tasks due this week, whether or not they can start yet.", "anything due this week?"));
        s.add(asks(DATES, "Tasks due on the coming Saturday or Sunday.", "what do I have this weekend?"));
        s.add(asks(DATES, "Everything due by a day, late tasks included.", "what's due by Friday?"));
        s.add(asks(DATES, "Overdue tasks.", "do I have anything overdue?"));

        s.add(does(MANY, "Two changes in one sentence, each read from its own part.",
                "mark the napkins done and move the speaker to Friday").says("\"Napkins\" is now done"));
        s.add(does(MANY, "News and a new task together.", "got the blanket, and add sunscreen to the picnic")
                .says("\"Sunscreen\""));
        s.add(asks(MANY, "The conversation remembers what it changed.", "what did you just change?"));
        s.add(does(MANY, "Undo takes back the last sentence's changes.", "undo that")
                .says("dropped from \"Plan the picnic\""));
        s.add(does(MANY, "A move...", "move the passport to Friday").says("\"Renew my passport\" is now due "));
        s.add(does(MANY, "...and a correction of it.", "no, I meant Thursday")
                .says("\"Renew my passport\" is now due "));

        s.add(does(PERSON, "What you say about how you work becomes part of your profile.",
                "I can only do about 3 hours a day").says("Daily hours: 3"));
        s.add(does(PERSON, "", "I don't work on weekends").says("Working days: Monday to Friday"));
        s.add(asks(PERSON, "The profile the agents plan with.", "show me my profile"));
        s.add(does(PERSON, "A standing rule in your words: agents must ask before changing the passport goal.",
                "never let agents change my passport goal without asking me first").says("is in force"));
        s.add(asks(PERSON, "The rules in force.", "what are my rules?"));
        s.add(does(PERSON, "Automatic work can be paused...", "pause everything").says("paused"));
        s.add(does(PERSON, "...and resumed.", "resume").says("resumed"));
        s.add(new Step(PERSON, "The emergency stop works without any model (fabric --stop).", Kind.STOP, ASSIST,
                "fabric --stop", "", DONE, null, false, null));
        s.add(new Step(PERSON, "And back (fabric --resume).", Kind.RESUME, ASSIST, "fabric --resume", "", DONE, null,
                false, null));

        s.add(new Step(GROWTH, "Information the system has no capability for: it designs one, grows it on probation "
                + "and re-plans the goal with it.", Kind.SAY, ASSIST, "I need a packing list for the picnic", "", DONE,
                null, true, null).says("grew "));
        s.add(asks(GROWTH, "The ecosystem: providers, their standing, grown capabilities.", "show me the ecosystem"));
        s.add(asks(GROWTH, "The inbox: what the coach noticed (deadlines at risk, work due today).",
                "what's in my inbox?"));
        s.add(asks(GROWTH, "The decisions the models made for a goal, recorded for replay.",
                "which AI decisions were made for the picnic?"));

        s.add(service(SECURITY, "Sharing is a security change: a service (decisive mode) cannot do it; the owner's "
                + "device must say yes.", "let Ana help with the picnic until Saturday", REFUSED, "NEEDS_OWNER"));
        s.add(new Step(SECURITY, "On the owner's device it is asked, the demo answers yes, and an invitation code is "
                + "printed for Ana.", Kind.SAY, ASSIST, "let Ana help with the picnic until Saturday", "y", DONE, null,
                false, null).says("fabric-invite:"));
        s.add(new Step(SECURITY, "Taking the access back is asked too.", Kind.SAY, ASSIST, "revoke Ana's access", "y",
                DONE, null, false, null).says("revoked"));
        s.add(service(SECURITY, "Three changes to existing goals at once from a device paired less than an hour ago "
                + "are unusual: refused for a service (done when the device is older).",
                "move the passport, the dentist and the picnic to next Friday", Set.of("REJECTED", "COMPLETED"),
                "NEEDS_OWNER"));
        s.add(new Step(SECURITY, "The careful mode (--ask) asks before a change; the demo answers yes.", Kind.SAY,
                CONVERSE, "move the picnic to Saturday", "y", DONE, null, false, null)
                .says("\"Plan the picnic\" is now due "));
        s.add(new Step(SECURITY, "In the careful mode a no changes nothing.", Kind.SAY, CONVERSE,
                "cancel the annual report goal", "n", Set.of("ASKED", "ANSWERED"), null, false, null));

        s.add(service(SERVICES, "Two goals mention a report: rejected, naming both, so the service can retry with one.",
                "move the report to Monday", REFUSED, "AMBIGUOUS_TARGET"));
        s.add(service(SERVICES, "Nothing is about a unicorn.", "drop the unicorn task", REFUSED, "UNKNOWN_TARGET"));
        s.add(service(SERVICES, "A date that has passed.", "move the picnic to last Monday", REFUSED, "DATE_PASSED"));
        s.add(service(SERVICES, "English only, for now.", "planifică o cină vineri", REFUSED, "NOT_UNDERSTOOD"));
        s.add(service(SERVICES, "A change the system does not make is answered with what to do instead.",
                "rename the speaker task", SHOWN, null).says("cannot be renamed"));
        s.add(typed(SERVICES, "A typed call: no model reads it, the kernel checks and runs it.",
                "[{\"type\":\"ADD_TASK\",\"goal\":\"<picnic>\",\"text\":\"Pack the cooler\"}]", DONE, null)
                .says("Pack the cooler"));
        s.add(typed(SERVICES, "A typed estimate for a task named by its key.",
                "[{\"type\":\"UPDATE_TASK\",\"goal\":\"<picnic>\",\"task\":\"<speaker>\",\"hours\":2}]", DONE, null)
                .says("estimated at 2.0 h"));
        s.add(typed(SERVICES, "A typed call naming a goal nobody has.",
                "[{\"type\":\"CANCEL_GOAL\",\"goal\":\"no such goal\"}]", REFUSED, "UNKNOWN_TARGET"));
        s.add(typed(SERVICES, "A typed read.", "[{\"type\":\"SHOW_GOAL\",\"goal\":\"<picnic>\"}]", SHOWN, null));

        s.add(asks(EDGES, "A lone question mark is a request for help.", "?"));
        s.add(asks(EDGES, "Courtesy needs no model.", "thanks!"));
        s.add(asks(EDGES, "\"On my plate\" is the agenda, not the picnic's plates.", "what's on my plate today"));
        s.add(new Step(EDGES, "Something nobody has: said so, with the closest goals.", Kind.SAY, ASSIST,
                "I don't need the unicorn anymore", "", REFUSED, "UNKNOWN_TARGET", false, null));
        s.add(new Step(EDGES, "News that names no open task.", Kind.SAY, ASSIST, "I finished the gazebo", "",
                Set.of("REJECTED", "ANSWERED"), null, false, null));
        s.add(asks(EDGES, "What the system does not do (the weather) is said so.", "what's the weather tomorrow?"));

        s.add(does(CLEANUP, "Cancelling a goal keeps it in the history...", "cancel the annual report goal")
                .says("cancelled \"Write the annual report\""));
        s.add(does(CLEANUP, "...so it can be brought back.", "bring back the annual report goal")
                .says("\"Write the annual report\" is open again"));
        s.add(typed(CLEANUP, "Every goal the demo created is cancelled with typed calls.", "<cancel-all>", DONE,
                null));
        return s;
    }

    private final FabricCommand command;
    private final Fabric fabric;
    private final PrintStream out;
    private final String session;
    /** When the last changes were made, for the pace the security policy expects. */
    private final Deque<Instant> changes = new ArrayDeque<>();
    /** The goals the demo created, cancelled at the end. */
    private final Set<String> created = new LinkedHashSet<>();
    private final List<String> unexpected = new ArrayList<>();

    Demo(FabricCommand command, Fabric fabric, PrintStream out) {
        this.command = command;
        this.fabric = fabric;
        this.out = out;
        this.session = "demo:" + command.principal;
    }

    /** Runs every step; 0 when each outcome was one its step accepts, 1 otherwise. */
    int run() throws Exception {
        List<Step> steps = steps();
        Instant start = Instant.now();
        out.println("""

                ╔══════════════════════════════════════════════════════════════════════╗
                ║  🧵 Capability Fabric: guided demo                                   ║
                ║  Every feature, step by step: what is sent, what comes back, and     ║
                ║  whether the outcome is the expected one.                            ║
                ╚══════════════════════════════════════════════════════════════════════╝""");
        out.println("\n" + steps.size() + " steps. 👤 is what is said, 🤖 what the system answers, ❓ a question it "
                + "asks and 🧑 the demo's answer. Each step ends with its outcome: ✅ as expected, ⚠️ not.");
        if (!existingGoals().isEmpty()) {
            out.println("\n⚠️  This system already has goals; the demo adds its own and cancels them at the end. "
                    + "Some answers may differ from the expected ones (for example a new goal that resembles one of "
                    + "yours). The demo scripts run it on a fresh system.");
        }
        String chapter = null;
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            if (!step.chapter().equals(chapter)) {
                chapter = step.chapter();
                out.println("\n\n━━━ " + chapter + " " + "━".repeat(Math.max(3, 66 - chapter.length())));
            }
            out.println("\n[" + (i + 1) + "/" + steps.size() + "]"
                    + (step.shows().isEmpty() ? "" : " " + step.shows()));
            try {
                Optional<Conversation.Reply> reply = run(step);
                reply.ifPresent(r -> judge(step, r));
            } catch (RuntimeException e) {
                out.println("   💥 " + FabricCommand.message(e));
                unexpected.add(step.text() + ": " + FabricCommand.message(e));
            }
        }
        Duration took = Duration.between(start, Instant.now());
        int total = steps.size();
        out.println("\n\n━━━ 📊 Summary " + "━".repeat(56));
        out.println((total - unexpected.size()) + " of " + total + " steps ended as expected, in "
                + took.toMinutes() + " min " + took.toSecondsPart() + " s.");
        if (!unexpected.isEmpty()) {
            out.println("Not as expected (a small model reads everyday sentences well, not perfectly; see Known "
                    + "limitations in README.md):");
            unexpected.forEach(u -> out.println("  ⚠️  " + u));
        }
        return unexpected.isEmpty() ? 0 : 1;
    }

    /** Sends one step and prints what comes back; empty for the steps that have no reply to judge. */
    private Optional<Conversation.Reply> run(Step step) throws Exception {
        return switch (step.kind()) {
            case STOP, RESUME -> {
                out.println("👤 " + step.text());
                Reply reply = fabric.request(step.kind() == Kind.STOP ? Subjects.CONTROL_STOP
                        : Subjects.CONTROL_RESUME, new KernelApi.ControlCommand(command.principal,
                        step.kind() == Kind.STOP ? "demo" : null));
                out.println("🤖 " + (!reply.ok() ? "error: " + Format.line(reply.error())
                        : step.kind() == Kind.STOP ? "Stopped: no capability is called until you resume."
                        : "Resumed."));
                if (!reply.ok()) {
                    unexpected.add(step.text() + ": " + reply.error());
                }
                out.println("   📋 " + (reply.ok() ? "done" : "failed") + (reply.ok() ? "  ✅" : "  ⚠️"));
                yield Optional.empty();
            }
            case TYPED -> {
                if (step.text().equals("<cancel-all>")) {
                    cancelAll();
                    yield Optional.empty();
                }
                String json = fill(step.text());
                pace("");
                out.println("👤 typed call (fabric --json --do): " + json);
                Fabric.Dated<Conversation.Reply> answered = fabric.dated(Subjects.INTENT_EXECUTE,
                        new Conversation.Command(List.of(Json.read(json,
                                Conversation.Action[].class)), session, command.principal),
                        Conversation.Reply.class, FabricCommand.TURN_TIMEOUT);
                out.println("🤖 " + FabricCommand.OUTCOME_WRITER.writeValueAsString(
                        FabricCommand.outcome(answered.data())));
                noted(answered.data());
                yield Optional.of(answered.data());
            }
            case SAY, JSON -> Optional.of(say(step));
        };
    }

    /** One sentence in the step's mode; the questions it gets are answered with the step's answers. */
    private Conversation.Reply say(Step step) throws Exception {
        pace(step.text());
        String mode = switch (step.mode()) {
            case DECIDE -> "fabric --json";
            case CONVERSE -> "fabric --ask";
            case ASSIST -> "fabric";
        };
        out.println("👤 " + mode + " \"" + step.text() + "\"");
        Conversation.Reply reply;
        try (RunWatch runs = command.watch(fabric)) {
            Fabric.Dated<Conversation.Reply> answered = fabric.dated(Subjects.INTENT_SUBMIT,
                    new Conversation.Utterance(step.text(), session, command.principal, step.mode()),
                    Conversation.Reply.class, FabricCommand.TURN_TIMEOUT);
            reply = answered.data();
            if (step.kind() == Kind.JSON) {
                out.println("🤖 " + FabricCommand.OUTCOME_WRITER.writeValueAsString(FabricCommand.outcome(reply)));
            } else {
                printAnswer(reply, answered.today());
            }
            noted(reply);
            List<Conversation.Executed> executed = new ArrayList<>(reply.executed());
            Deque<String> answers = new ArrayDeque<>(Arrays.stream(step.answers().split(","))
                    .map(String::strip).filter(a -> !a.isEmpty()).toList());
            Conversation.Reply first = reply;
            while (reply.confirmation() != null && !answers.isEmpty()) {
                Conversation.PendingConfirmation asked = reply.confirmation();
                String answer = answers.poll();
                out.println("❓ " + Format.indented(asked.question(), "   ").strip());
                out.println("🧑 " + answer);
                Fabric.Dated<Conversation.Reply> confirmed = fabric.dated(Subjects.INTENT_CONFIRM,
                        new Conversation.Confirm(asked.id(), answer.toLowerCase(Locale.ROOT).startsWith("y"), session,
                                command.principal, Conversation.receipt(asked)),
                        Conversation.Reply.class, FabricCommand.ANSWER_TIMEOUT);
                reply = confirmed.data();
                printAnswer(reply, confirmed.today());
                noted(reply);
                executed.addAll(reply.executed());
            }
            if (step.waits() && !command.noWait) {
                command.waitForRuns(fabric, runs, executed);
            }
            // A "no" leaves the question's own outcome (ASKED) as the step's: nothing was changed.
            if (reply.executed().isEmpty() && reply.confirmation() == null && first.confirmation() != null
                    && step.answers().strip().toLowerCase(Locale.ROOT).startsWith("n")) {
                reply = first;
            }
        }
        return reply;
    }

    /** Prints an answer as the CLI shows it, unless it holds nothing but a question (printed with ❓ next). */
    private void printAnswer(Conversation.Reply reply, LocalDate today) {
        String text = Format.reply(reply, today).strip();
        if (!text.isEmpty()) {
            out.print(Format.indented("🤖 " + text, "   ") + "\n");
        }
    }

    /**
     * Prints the outcome line and records a step that ended otherwise than it expects: another outcome, a rejection
     * without its reason code, or an answer without the text it must contain.
     */
    private void judge(Step step, Conversation.Reply reply) {
        String status = Conversation.outcome(reply).name();
        List<String> codes = new ArrayList<>(reply.executed().stream().filter(e -> !e.ok())
                .map(Conversation::reasonCode).toList());
        if (Conversation.notUnderstood(reply)) {
            codes.add("NOT_UNDERSTOOD");
        }
        boolean codeOk = step.code() == null || status.equals("COMPLETED") || status.equals("ANSWERED")
                || codes.contains(step.code());
        String answer = (reply.reply() == null ? "" : reply.reply()) + "\n" + String.join("\n",
                reply.executed().stream().map(Conversation.Executed::message).toList());
        boolean saysOk = step.says() == null || answer.contains(step.says());
        boolean ok = step.accepts().contains(status) && codeOk && saysOk;
        String expected = String.join(" or ", step.accepts().stream().sorted().toList())
                + (step.code() == null ? "" : " " + step.code())
                + (step.says() == null ? "" : ", saying \"" + step.says() + "\"");
        out.println("   📋 outcome: " + status + (codes.isEmpty() ? "" : " " + String.join(", ", codes))
                + (ok ? "  ✅" : "  ⚠️  expected " + expected));
        if (!ok) {
            unexpected.add("\"" + step.text() + "\": " + status + (codes.isEmpty() ? "" : " " + codes)
                    + ", expected " + expected);
        }
    }

    /** Remembers the goals a reply created and when its changes were made. */
    private void noted(Conversation.Reply reply) {
        Instant now = Instant.now();
        for (Conversation.Executed e : reply.executed()) {
            if (!e.type().readOnly()) {
                changes.addLast(now);
            }
            if (e.ok() && e.type() == Conversation.ActionType.CREATE_GOAL && e.goalId() != null) {
                created.add(e.goalId());
            }
        }
    }

    /** Changes within a minute that the security policy treats as unusual (its default). */
    static final int BURST = 6;

    /**
     * Waits until the changes made in the last minute and the ones {@code text} may ask for stay below
     * {@link #BURST}, saying so when it waits. A sentence may ask for one change per item it lists ("add charcoal,
     * napkins and a speaker"), at most three counted.
     */
    private void pace(String text) throws InterruptedException {
        int asked = Math.min(3, 1 + text.split(",| and ", -1).length - 1);
        Instant cutoff = Instant.now().minusSeconds(60);
        while (!changes.isEmpty() && changes.peekFirst().isBefore(cutoff)) {
            changes.pollFirst();
        }
        int over = changes.size() + asked - (BURST - 1);
        if (over > 0) {
            Instant until = changes.toArray(new Instant[0])[over - 1].plusSeconds(61);
            long seconds = Math.max(1, Duration.between(Instant.now(), until).toSeconds());
            out.println("   ⏳ waiting " + seconds + " s: the security policy treats " + BURST + " changes within a "
                    + "minute as unusual");
            Thread.sleep(seconds * 1000);
            for (int i = 0; i < over; i++) {
                changes.pollFirst();
            }
        }
    }

    /** Cancels every open goal the demo created, one typed call each. */
    private void cancelAll() throws Exception {
        List<KernelApi.GoalSummary> open = existingGoals().stream().filter(g -> created.contains(g.id())).toList();
        if (open.isEmpty()) {
            out.println("   (no open goal of the demo is left)");
            return;
        }
        for (KernelApi.GoalSummary goal : open) {
            pace("");
            String json = "[{\"type\":\"CANCEL_GOAL\",\"goal\":\"" + goal.id() + "\"}]";
            out.println("👤 typed call: " + json);
            Fabric.Dated<Conversation.Reply> answered = fabric.dated(Subjects.INTENT_EXECUTE,
                    new Conversation.Command(List.of(Json.read(json,
                            Conversation.Action[].class)), session, command.principal),
                    Conversation.Reply.class, FabricCommand.TURN_TIMEOUT);
            printAnswer(answered.data(), answered.today());
            noted(answered.data());
            if (Conversation.outcome(answered.data()) != Conversation.Outcome.COMPLETED) {
                unexpected.add("cancelling \"" + goal.text() + "\": " + Conversation.outcome(answered.data()));
            }
        }
    }

    /** The open goals on the system. */
    private List<KernelApi.GoalSummary> existingGoals() {
        return FabricCommand.allGoals(fabric).stream()
                .filter(g -> g.status() != GoalStatus.CANCELLED && g.status() != GoalStatus.COMPLETED).toList();
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("<(picnic|speaker)>");

    /** The typed actions with {@code <picnic>} as the picnic goal's id and {@code <speaker>} as its speaker's key. */
    private String fill(String json) {
        Matcher m = PLACEHOLDER.matcher(json);
        StringBuilder filled = new StringBuilder();
        while (m.find()) {
            String goalId = existingGoals().stream().filter(g -> g.text().toLowerCase(Locale.ROOT).contains("picnic"))
                    .map(KernelApi.GoalSummary::id).findFirst().orElse("picnic");
            String value = m.group(1).equals("picnic") ? goalId : speakerKey(goalId);
            m.appendReplacement(filled, Matcher.quoteReplacement(value));
        }
        m.appendTail(filled);
        return filled.toString();
    }

    /** The key of the picnic's speaker task. */
    private String speakerKey(String goalId) {
        KernelApi.GoalDetails details = fabric.data(Subjects.QUERY_GOAL, new KernelApi.Ref(goalId),
                KernelApi.GoalDetails.class);
        return details == null ? "T1" : details.tasks().stream()
                .filter(t -> t.title().toLowerCase(Locale.ROOT).contains("speaker"))
                .map(GoalContext.TaskView::key).findFirst().orElse("T1");
    }
}
