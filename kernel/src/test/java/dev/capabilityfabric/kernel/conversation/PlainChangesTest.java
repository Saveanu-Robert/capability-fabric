package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.constitution.ConstitutionService;
import dev.capabilityfabric.kernel.decisions.DecisionLog;
import dev.capabilityfabric.kernel.ecosystem.Genesis;
import dev.capabilityfabric.kernel.execution.CapabilityInvoker;
import dev.capabilityfabric.kernel.inbox.InboxService;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.kernel.registry.Embeddings;
import dev.capabilityfabric.kernel.security.SecurityService;
import dev.capabilityfabric.kernel.security.TrustService;
import dev.capabilityfabric.protocol.CapabilityResult;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Conversation.Action;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.Conversation.Executed;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Grant;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Only plain sentences change anything without a question: every word of a change's own part of the sentence must be
 * one the kernel knows for that change (see {@link PlainWords}). Today is Wednesday 18 November 2026; the picnic is
 * due on Thursday 19 November, so "push it back to Friday" moves it later.
 */
class PlainChangesTest {

    private static final String GOAL = "aaaa1111-0000-0000-0000-000000000001";
    private static final String GOAL_TEXT = "Plan a picnic in the park";

    private final Grounding grounding = mock(Grounding.class);
    private final ActionExecutor executor = mock(ActionExecutor.class);
    private final CapabilityInvoker invoker = mock(CapabilityInvoker.class);
    private final CapabilityRegistry registry = mock(CapabilityRegistry.class);
    private final LedgerService ledger = mock(LedgerService.class);
    private final ConstitutionService constitution = mock(ConstitutionService.class);
    private final InboxService inbox = mock(InboxService.class);
    private final KernelProperties properties = mock(KernelProperties.class);
    private final List<Action> executed = new ArrayList<>();
    private ConversationService conversation;

    @BeforeEach
    void setUp() {
        when(properties.today()).thenReturn(LocalDate.of(2026, 11, 18));
        when(registry.has(ConversationService.INTERPRETER)).thenReturn(true);
        when(constitution.checkPerson(any(), any())).thenReturn(ConstitutionService.Ruling.ALLOW);
        when(constitution.checkPerson(any(), any(), any())).thenReturn(ConstitutionService.Ruling.ALLOW);
        when(executor.describe(any(), any(), any())).thenAnswer(i -> {
            Action a = i.getArgument(0);
            return "do " + a.type() + (a.task() == null ? "" : " " + a.task()) + (a.status() == null ? ""
                    : " " + a.status()) + (a.dueDate() == null ? "" : " due " + a.dueDate())
                    + (a.hours() == null ? "" : " " + a.hours() + " h");
        });
        when(executor.question(any(), any(), any())).thenAnswer(i -> "Q " + ((Action) i.getArgument(0)).type() + "?");
        when(executor.execute(any(), any(), any(), any(), any())).thenAnswer(i -> {
            executed.add(i.getArgument(0));
            return new Executed(((Action) i.getArgument(0)).type(), true, "done", i.getArgument(1), null);
        });
        Map<String, List<String>> documents = Map.of(GOAL, List.of(GOAL_TEXT, "Book the band (" + GOAL_TEXT + ")",
                "Reserve the spot (" + GOAL_TEXT + ")", "Send the invitations (" + GOAL_TEXT + ")"));
        when(grounding.build(anyString(), any(), any(), any(), anyBoolean())).thenReturn(new Grounding.Snapshot(
                Json.object(), Map.of(GOAL.substring(0, 8), GOAL), Map.of(), documents));
        when(ledger.load(GOAL)).thenReturn(Optional.of(new GoalState(GOAL, GOAL_TEXT, LocalDate.of(2026, 11, 19),
                GoalStatus.ACTIVE, Map.of(), List.of(task("T2", "Book the band", LocalDate.of(2026, 11, 19)),
                task("T3", "Reserve the spot", LocalDate.of(2026, 11, 19)),
                task("T4", "Send the invitations", LocalDate.of(2026, 11, 30))), 5, 4, Instant.now(), "human:me")));
        conversation = new ConversationService(grounding, executor, invoker, registry, mock(DecisionLog.class),
                mock(Embeddings.class), ledger, mock(Genesis.class), constitution, inbox, properties,
                mock(TrustService.class), mock(SecurityService.class));
    }

    private static TaskView task(String key, String title, LocalDate due) {
        return new TaskView("id-" + key, key, title, null, TaskStatus.TODO, due, List.of(), null, null, null, null,
                Map.of());
    }

    private void modelSays(String reply, Action... actions) {
        CapabilityResult result = new CapabilityResult("r", ConversationService.INTERPRETER, "agent-assistant", "a1",
                CapabilityResult.Status.OK, null, Json.toTree(new Conversation.Interpretation(reply, List.of(actions))),
                List.of(), null, 1);
        when(invoker.invoke(isNull(), anyString(), anyInt(), eq(ConversationService.INTERPRETER), anyInt(), isNull(),
                any(), any())).thenReturn(new CapabilityInvoker.Invocation(result, null, List.of(), null));
    }

    private Conversation.Reply say(String text) {
        return conversation.handle(new Conversation.Utterance(text, "s1", null), SecurityService.Caller.anonymous());
    }

    private static Action status(String task, TaskStatus status, Double hours) {
        return new Action(ActionType.SET_TASK_STATUS, GOAL.substring(0, 8), task, null, null, status, hours, null,
                null, null);
    }

    private static Action move(LocalDate due) {
        return new Action(ActionType.RESCHEDULE_GOAL, GOAL.substring(0, 8), null, null, due, null, null, null, null,
                null);
    }

    private static Action moveTask(String task, LocalDate due) {
        return new Action(ActionType.UPDATE_TASK, GOAL.substring(0, 8), task, null, due, null, null, null, null, null);
    }

    private static Action newGoal(String text, LocalDate due) {
        return new Action(ActionType.CREATE_GOAL, null, null, text, due, null, null, null, null, null);
    }

    private static final LocalDate FRIDAY = LocalDate.of(2026, 11, 20);

    // ---- plain sentences apply at once ----------------------------------------------------------------------------

    @Test
    void thePlainSentencesApplyWithoutAQuestion() {
        Object[][] cases = {
                {"move T3 of the picnic to November 27", moveTask("T3", LocalDate.of(2026, 11, 27))},
                {"push the picnic back to Friday", move(FRIDAY)},
                {"T3 of the picnic is done, it took 2 hours", status("T3", TaskStatus.DONE, 2.0)},
                {"I reserved the spot", status("T3", TaskStatus.DONE, null)},
                {"I started T4 of the picnic", status("T4", TaskStatus.IN_PROGRESS, null)},
                {"T4 of the picnic is blocked", status("T4", TaskStatus.BLOCKED, null)},
                {"new goal: QA plant tulips by Friday", newGoal("QA plant tulips", FRIDAY)},
                {"mark T2 of the picnic as done", status("T2", TaskStatus.DONE, null)},
                {"I sent the invitations", status("T4", TaskStatus.DONE, null)},
                {"I'm working on T4 of the picnic", status("T4", TaskStatus.IN_PROGRESS, null)},
                {"finished T3 of the picnic in 2 hours", status("T3", TaskStatus.DONE, 2.0)}};
        for (Object[] c : cases) {
            modelSays("Got it.", (Action) c[1]);
            Conversation.Reply reply = say((String) c[0]);
            assertThat(reply.confirmation()).as((String) c[0]).isNull();
        }
        assertThat(executed).hasSize(cases.length);
        assertThat(executed.get(2).hours()).isEqualTo(2.0);
        assertThat(executed.get(6).dueDate()).isEqualTo(FRIDAY);
    }

    @Test
    void eachChangeOfASentenceIsJudgedOnItsOwnPart() {
        modelSays("Ok.", move(FRIDAY), status("T3", TaskStatus.DONE, null));

        Conversation.Reply reply = say("move the picnic to Friday and mark T3 done");

        assertThat(reply.confirmation()).isNull();
        assertThat(executed).extracting(Action::type).containsExactly(ActionType.RESCHEDULE_GOAL,
                ActionType.SET_TASK_STATUS);

        executed.clear();
        modelSays("Ok.", move(FRIDAY), status("T3", TaskStatus.DONE, null));
        Conversation.Reply mixed = say("move the picnic to Friday and T3 is almost done");
        assertThat(executed).as("the completion's part is not plain, so the whole sentence is not").isEmpty();
        assertThat(mixed.confirmation().actions()).extracting(Action::type)
                .containsExactlyInAnyOrder(ActionType.RESCHEDULE_GOAL, ActionType.SET_TASK_STATUS);
    }

    // ---- everything else is asked ---------------------------------------------------------------------------------

    @Test
    void futureIntendedConditionalAndPartialStatusesAreAsked() {
        Object[][] cases = {
                {"I'll have T3 of the picnic done tonight", TaskStatus.DONE},
                {"T3 will be done tomorrow", TaskStatus.DONE},
                {"I'll finish T3 tonight", TaskStatus.DONE},
                {"I want T3 done by Friday", TaskStatus.DONE},
                {"remind me to finish T3", TaskStatus.DONE},
                {"T3 needs to be done", TaskStatus.DONE},
                {"T3 has to be done", TaskStatus.DONE},
                {"get T3 done by Friday", TaskStatus.DONE},
                {"I'll be done with T3", TaskStatus.DONE},
                {"T3 took 2 hours today, I'll finish it tomorrow", TaskStatus.DONE},
                {"T4 can start as soon as T3 is done", TaskStatus.DONE},
                {"after T3 is done", TaskStatus.DONE},
                {"T3 is just about done", TaskStatus.DONE},
                {"T3 is all but done", TaskStatus.DONE},
                {"T3 is done apart from the deposit", TaskStatus.DONE},
                {"T3 is 3/4 done", TaskStatus.DONE},
                {"I did some of the invitations", TaskStatus.DONE},
                {"I sent a few of the invitations", TaskStatus.DONE},
                {"I finished the first step of T3", TaskStatus.DONE},
                {"I'm done with T3 for today", TaskStatus.DONE},
                {"I did what I could on T3", TaskStatus.DONE},
                {"T3 or T4 is done", TaskStatus.DONE},
                {"I'm going to start T4 tomorrow", TaskStatus.IN_PROGRESS},
                {"I'll start T4 tomorrow", TaskStatus.IN_PROGRESS},
                {"I'm about to start T4", TaskStatus.IN_PROGRESS},
                {"I'd like to start T4 soon", TaskStatus.IN_PROGRESS},
                {"I need to start T4", TaskStatus.IN_PROGRESS},
                {"I plan to work on T4", TaskStatus.IN_PROGRESS},
                {"start T4 on Friday", TaskStatus.IN_PROGRESS},
                {"T4 could get stuck", TaskStatus.BLOCKED},
                {"T4 will be blocked if the park is closed", TaskStatus.BLOCKED},
                {"T4 was blocked last week but it's moving now", TaskStatus.BLOCKED},
                {"I got stuck on T4 yesterday but sorted it out", TaskStatus.BLOCKED},
                {"T4 may be blocked", TaskStatus.BLOCKED}};
        for (Object[] c : cases) {
            String said = (String) c[0];
            String key = said.contains("T4") || said.contains("invitations") ? "T4" : "T3";
            modelSays("Done!", status(key, (TaskStatus) c[1], null));
            Conversation.Reply reply = say(said);
            if (reply.confirmation() == null) {
                // words that name no goal: the model's goal is not offered at all
                assertThat(reply.executed()).as(said).singleElement().satisfies(e -> assertThat(e.message())
                        .startsWith("tell which goal you mean"));
            } else {
                assertThat(reply.confirmation().question()).as(said).startsWith("Did you mean:");
            }
        }
        verify(executor, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void hypotheticalAndRequestedMovesAreAsked() {
        for (String said : List.of("the picnic may move to Friday", "we could move the picnic to Friday",
                "I would push the picnic to Friday", "let's say the picnic moves to Friday", "I could move the picnic "
                        + "to Friday", "I'd move the picnic to Friday", "the picnic could be moved to Friday",
                "we may have to move the picnic to Friday", "I may move the picnic to Friday",
                "remind me to move the picnic to Friday", "I was going to move the picnic to Friday",
                "move the picnic to Friday because it will rain")) {
            modelSays("Moved.", move(FRIDAY));
            assertThat(say(said).confirmation()).as(said).isNotNull();
        }
        verify(executor, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void theQuestionOffersTheStatusTheWordsStatePlainly() {
        modelSays("Ok.", status("T3", TaskStatus.IN_PROGRESS, null));

        Conversation.Reply reply = say("T3 of the picnic is done");

        assertThat(reply.confirmation()).as("no yes or no between two readings of the status").isNull();
        assertThat(reply.executed()).singleElement().satisfies(e -> assertThat(e.message())
                .isEqualTo("say the status you mean for T3 \"Reserve the spot\": I read done, the assistant read in "
                        + "progress"));
        verify(executor, never()).execute(any(), any(), any(), any(), any());
    }

    // ---- amounts of time ------------------------------------------------------------------------------------------

    @Test
    void anAmountThatIsNotTheTimeTheWorkTookIsNeverRecordedUnasked() {
        for (String said : List.of("I finished T3 of the picnic, it took 2 hours too many",
                "T3 of the picnic is done, 2 hours past the estimate", "T3 of the picnic is done, 4 hours ahead of schedule",
                "T3 of the picnic is done, 2 hours on top of the estimate")) {
            modelSays("Nice.", status("T3", TaskStatus.DONE, 2.0));
            assertThat(say(said).confirmation()).as(said).isNotNull();
        }
        modelSays("Nice.", status("T3", TaskStatus.DONE, 2.0));
        Conversation.Reply left = say("T3 of the picnic is done, I have 2 hours left today");
        assertThat(left.confirmation().actions().getFirst().hours()).as("no word says the work took them").isNull();
        assertThat(left.confirmation().question()).contains("your words do not say how long T3 took");
        verify(executor, never()).execute(any(), any(), any(), any(), any());
    }

    // ---- new goals and their deadlines ----------------------------------------------------------------------------

    @Test
    void aDateTheWordsOnlyMentionIsNoDeadline() {
        for (String said : List.of("new goal: QA renew my library card, the library is closed next Monday",
                "new goal: QA visit grandma, she was in hospital until Monday",
                "new goal: QA return the jacket I bought on Monday")) {
            executed.clear();
            modelSays("Created.", newGoal(null, null));
            Conversation.Reply reply = say(said);
            if (reply.confirmation() == null) {
                assertThat(executed).as(said).singleElement().satisfies(a -> assertThat(a.dueDate()).isNull());
            } else {
                assertThat(reply.confirmation().actions().getFirst().dueDate()).as(said).isNull();
            }
        }
        modelSays("Created.", newGoal("QA renew my library card", LocalDate.of(2026, 11, 23)));
        executed.clear();
        Conversation.Reply reply = say("new goal: QA renew my library card, the library is closed next Monday");
        assertThat(reply.confirmation()).as("a mention the model takes for the deadline is asked").isNotNull();
        assertThat(reply.confirmation().question())
                .contains("you did not say \"by\" or \"before\" in front of the date");
        assertThat(executed).isEmpty();
    }

    @Test
    void aDateOnlyAWeakWordMarksIsAskedWhenTheModelTakesItForTheDeadline() {
        for (String said : List.of("new goal: QA plant tulips on Friday", "new goal: QA plant tulips for Friday",
                "new goal: QA visit grandma, she was in hospital until Friday")) {
            modelSays("Created.", newGoal("QA plant tulips", FRIDAY));
            Conversation.Reply reply = say(said);
            assertThat(reply.confirmation()).as(said).isNotNull();
            assertThat(reply.confirmation().actions().getFirst().dueDate()).as(said).isEqualTo(FRIDAY);
        }
        verify(executor, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void aStrongMarkerInTheGoalsOwnClauseSetsTheDeadline() {
        for (String said : List.of("new goal: QA plant tulips by Friday",
                "new goal: QA plant tulips no later than Friday", "new goal: QA buy milk and bread by Friday")) {
            executed.clear();
            modelSays("Created.", newGoal(ConversationService.goalWords(said), FRIDAY));
            assertThat(say(said).confirmation()).as(said).isNull();
            assertThat(executed).as(said).singleElement().satisfies(a -> assertThat(a.dueDate()).isEqualTo(FRIDAY));
        }
        executed.clear();
        modelSays("Created.", newGoal("QA plant tulips", FRIDAY));
        assertThat(say("new goal: QA plant tulips, due Friday").confirmation())
                .as("a second clause before the deadline: asked").isNotNull();
        assertThat(executed).isEmpty();
    }

    // ---- the task a change lands on -------------------------------------------------------------------------------

    @Test
    void aChangeMovedToAnotherTaskIsReadForThatTask() {
        // The person typed T4 (due 30 November); the model chose T3 (due 19 November).
        modelSays("Moved.", moveTask("T3", LocalDate.of(2026, 11, 22)));

        Conversation.Reply reply = say("push T4 of the picnic back 3 days");

        assertThat(reply.confirmation().actions()).as("three days after T4's date, not T3's").singleElement()
                .satisfies(a -> assertThat(a.dueDate()).isEqualTo(LocalDate.of(2026, 12, 3)));
        assertThat(reply.confirmation().question()).as("the model's date differs from the words' date for T4")
                .contains("(the assistant read 2026-11-22)");

        modelSays("Moved.", moveTask("T3", LocalDate.of(2026, 12, 3)));
        Conversation.Reply agreed = say("push T4 of the picnic back 3 days");
        assertThat(agreed.confirmation().actions()).as("another task than the model's: asked").singleElement()
                .satisfies(a -> {
                    assertThat(a.task()).isEqualTo("T4");
                    assertThat(a.dueDate()).isEqualTo(LocalDate.of(2026, 12, 3));
                });
        verify(executor, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void twoChangesLandingOnOneTaskAskOnce() {
        modelSays("Moved.", moveTask("T3", LocalDate.of(2026, 12, 3)), moveTask("T4", LocalDate.of(2026, 12, 3)));

        Conversation.Reply reply = say("push T4 of the picnic back 3 days");

        assertThat(reply.confirmation().actions()).hasSize(1);
        assertThat(reply.executed()).anySatisfy(e -> assertThat(e.message()).contains("change T4 one way at a time"));
    }

    @Test
    void anUpdateLeftWithNothingToChangeIsRefusedWithoutAQuestion() {
        modelSays("Ok.", new Action(ActionType.UPDATE_TASK, GOAL.substring(0, 8), "T3", null, null, null, 3.0, null,
                null, null));

        Conversation.Reply reply = say("T3 and T4 of the picnic will take 3 hours");

        assertThat(reply.confirmation()).isNull();
        assertThat(reply.executed()).anySatisfy(e -> {
            assertThat(e.ok()).isFalse();
            assertThat(e.message()).contains("change T3").contains("no hours recorded");
        });
        verify(executor, never()).execute(any(), any(), any(), any(), any());
    }

    @Test
    void taskKeysNoChangeCoversAreSaid() {
        modelSays("Done.", status("T3", TaskStatus.DONE, null));

        Conversation.Reply reply = say("T3 and T4 of the picnic are done");

        assertThat(reply.executed()).anySatisfy(e -> {
            assertThat(e.ok()).isFalse();
            assertThat(e.message()).isEqualTo("T4 was not changed: say what should change for it in a sentence of "
                    + "its own");
        });
        assertThat(ConversationService.uncoveredKeys("move the picnic to the day after T4",
                List.of(move(FRIDAY)), List.of())).as("no task change: a key only places something").isEmpty();
        assertThat(ConversationService.uncoveredKeys("T3 is done, not T4", List.of(status("T3", TaskStatus.DONE,
                null)), List.of())).as("a negated key").isEmpty();
    }

    // ---- what a guest may not do is never read ----------------------------------------------------------------------

    @Test
    void aChangeAGuestMayNotMakeIsRefusedBeforeItsWordsAreRead() {
        Grant viewOnly = new Grant("g", "kernel", "ana", Grant.Rights.person("guest", "Ana", List.of(GOAL),
                SecurityService.VIEW_ACTIONS), Instant.now(), null, null, "sig");
        SecurityService.Caller guest = new SecurityService.Caller(SecurityService.Role.GUEST, "k1", null, "Ana",
                viewOnly, null, null);
        modelSays("Moved.", moveTask("T3", FRIDAY));

        Conversation.Reply reply = conversation.handle(new Conversation.Utterance("move T3 of the picnic to Friday "
                + "by by by by by by", "s1", null), guest);

        assertThat(reply.executed()).singleElement().satisfies(e -> assertThat(e.message())
                .isEqualTo("not part of what you were invited to do"));
        verify(ledger, never()).load(any());
    }

    // ---- replies after a change -----------------------------------------------------------------------------------

    @Test
    void afterAChangeOnlyAReplyFromTheFixedListIsShown() {
        modelSays("Done, and I also moved the next task up.", status("T3", TaskStatus.DONE, null));
        assertThat(say("T3 of the picnic is done").reply()).isNull();
        modelSays("Everything is finished now!", status("T3", TaskStatus.DONE, null));
        assertThat(say("T3 of the picnic is done").reply()).isNull();
        modelSays("I let your guests know.", status("T3", TaskStatus.DONE, null));
        assertThat(say("T3 of the picnic is done").reply()).isNull();
        modelSays("Got it.", status("T3", TaskStatus.DONE, null));
        assertThat(say("T3 of the picnic is done").reply()).isEqualTo("Got it.");
        modelSays("Anything else?", status("T3", TaskStatus.DONE, null));
        assertThat(say("T3 of the picnic is done").reply()).isEqualTo("Anything else?");
        modelSays("Done! Anything else?", status("T3", TaskStatus.DONE, null));
        assertThat(say("T3 of the picnic is done").reply()).as("a claim with the question").isNull();
        assertThat(ConversationService.fixedReply("Sure.")).contains("Sure.");
        assertThat(ConversationService.fixedReply("  sure! ")).as("compared without case and closing mark")
                .contains("Sure.");
        assertThat(ConversationService.fixedReply("Well done!")).isEmpty();
        assertThat(ConversationService.fixedReply(null)).isEmpty();
        assertThat(ConversationService.fixedReply("Shall I move T4 too?")).isEmpty();
        assertThat(ConversationService.fixedReply("Is there anything else?")).contains("Is there anything else?");
        assertThat(ConversationService.fixedReply("Is there anything else I can help with?")).isEmpty();
    }

    @Test
    void aReplyWithNoActionsAlwaysSaysNothingWasChangedExceptToACourtesy() {
        modelSays("Yes, I did.");
        assertThat(say("did you do it?").reply()).isEqualTo("Nothing was changed. Yes, I did.");
        modelSays("Yes, it went through.");
        assertThat(say("did it work?").reply()).isEqualTo("Nothing was changed. Yes, it went through.");
        modelSays("Sure, it won't happen now.");
        assertThat(say("is it possible to cancel it?").reply()).isEqualTo("Nothing was changed. Sure, it won't happen "
                + "now.");
        modelSays("");
        assertThat(say("cancel it").reply()).as("an empty reply").isEqualTo("Nothing was changed.");
        modelSays("Hello!");
        assertThat(say("hello").reply()).isEqualTo(ConversationService.GREETING);
        modelSays("You're welcome.");
        assertThat(say("ok, great, thanks").reply()).as("in the fixed list's own words").isEqualTo("You're welcome!");
    }
}
