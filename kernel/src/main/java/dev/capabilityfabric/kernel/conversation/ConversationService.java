package dev.capabilityfabric.kernel.conversation;

import dev.capabilityfabric.algorithms.DateExpressions;
import dev.capabilityfabric.algorithms.Durations;
import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.constitution.ConstitutionService;
import dev.capabilityfabric.kernel.decisions.DecisionLog;
import dev.capabilityfabric.kernel.ecosystem.Genesis;
import dev.capabilityfabric.kernel.execution.CapabilityInvoker;
import dev.capabilityfabric.kernel.inbox.InboxService;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerRules;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.person.ProfileService;
import dev.capabilityfabric.kernel.registry.CapabilityRegistry;
import dev.capabilityfabric.kernel.registry.Embeddings;
import dev.capabilityfabric.kernel.security.Contract;
import dev.capabilityfabric.kernel.security.GuestView;
import dev.capabilityfabric.kernel.security.SecurityService;
import dev.capabilityfabric.kernel.security.TrustService;
import dev.capabilityfabric.protocol.Constitution;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Conversation.Action;
import dev.capabilityfabric.protocol.Conversation.ActionType;
import dev.capabilityfabric.protocol.Conversation.Executed;
import dev.capabilityfabric.protocol.Ecosystem;
import dev.capabilityfabric.protocol.GoalContext.TaskView;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.KernelApi;
import dev.capabilityfabric.protocol.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single way people interact with the system. A sentence goes to whichever provider offers
 * {@code intent.interpret}; its proposed actions are resolved against the snapshot it saw and read actions run at once.
 * How a change runs depends on the turn's {@link Conversation.Mode}: in CONVERSE it runs at once only when the sentence
 * asks for it plainly (see below), and destructive ones always wait for the person's yes; in ASSIST and DECIDE every
 * change whose goal and task are not in doubt runs at once, with a note wherever the kernel's reading differs from the
 * model's, and only security changes and changes unusual for the device wait for the owner's yes (asked in ASSIST,
 * rejected with NEEDS_OWNER in DECIDE). A change in doubt is asked about in CONVERSE and rejected with the candidates
 * otherwise. Typed calls ({@link #execute}) skip the reading altogether. Each turn is remembered so follow-ups such as
 * "and mark the first task done" work.
 *
 * <p>The model's proposal is never trusted on its own. The goal, the task and the approval it names must be grounded in
 * what the person said; a goal the words leave weak with words of other goals, or tied with another, is never offered:
 * the reply names the goals that hold the words. A task change aimed at a goal none of whose tasks the words name moves
 * to the one goal whose task they do name ("the speaker isn't blocked anymore" aimed at "Learn Spanish"). A change is
 * applied without a question only when the whole sentence is plain: its own part is plain (every word is a plain change
 * verb for it, a word that names its target, the one date phrase of a move or the one amount of time a finished task
 * took, or a function word, see {@link PlainWords}), and every other word of the sentence lies in the plain part of
 * another of its changes or is a function or courtesy word; a condition, a correction, a retraction or a statement
 * about something else anywhere in the sentence makes every change of it asked ("if it rains, move the picnic to
 * December 6 and mark T3 done", "mark T3 done. Never mind."). A phrasing nobody listed ("I'll have T3 done tonight",
 * "we could move the picnic", "I did some of the invitations") is asked about, with the kernel's reading in the
 * question. The date and the hours of a change come only from its own part of the sentence. A date, an amount or a
 * status is applied without a question only when two independent readers agree: the kernel's own reading of the words
 * is simple and the model proposed the same value. When the two dates differ, the kernel's reading is asked about with
 * the model's named beside it ("move the picnic to 2026-12-18 (the assistant read 2026-12-24)?"); two amounts that
 * differ are named both and the person says the one they mean. A simple reading the model did not give is asked about
 * with that reading; words that are not simple (more than one date or amount, a correction, a negation, an exclusion, a
 * weekday that refers back to another date) are asked about with the model's reading, or with the kernel's own when the
 * model gave none; so is a move whose words name no date or a date the kernel cannot read itself. A move to a date on
 * the other side of the current one than the words say ("push it back") without a reading of the model's is refused,
 * and so is a deadline move with no date at all: no date is ever taken from the model's free text. New task titles and
 * goal texts must be the person's words; a new goal is created unasked only from one plain statement that the model's
 * text repeats word for word (see {@link PlainWords#plainStatement}), and a new task's title keeps every date phrase of
 * its words but a strict deadline. A new goal or task takes the date its words name as its deadline without asking only
 * in one strict form: a strong word ("by", "before", "due", "deadline", "no later than") right before the one date
 * phrase that ends its words, with no second clause before it (see {@link ActionDates#strictDeadline}), and the model
 * reading the same date; any other date is asked about when the model also gives it, and left out with a note saying
 * why when it does not. A task's hours come from the task's own part of the sentence: an amount of time shared with
 * other tasks, read from another task's part or not said to be what the work took is asked about or left out. A change
 * proposed for a question or a supposition ("should I mark T8 done?", "what if I push the picnic back?") is asked
 * about. Every profile correction a sentence asks for is asked about, stating the value that would last. Approving a
 * step, keeping a capability and pairing a device need the person's own words for it and a yes to the kernel's own
 * question; a pairing or invitation code never enters the conversation history the interpreter reads. Answers to inbox
 * messages follow the person's words, not the model's label: only the person's words choose which message a yes carries
 * out, and a message is answered without asking only by a sentence judged like every change: nothing in it makes the
 * answer wait, take it back or correct it, and it is nothing but an answer to the message (apart from its id) or a
 * plain change that carries out its suggestion, asking for no other change.
 */
@Component
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);
    public static final String INTERPRETER = "intent.interpret";

    /** Actions aimed at one task of a goal. */
    static final Set<ActionType> TASK_ACTIONS = Set.of(ActionType.SET_TASK_STATUS, ActionType.UPDATE_TASK,
            ActionType.REMOVE_TASK);

    private final Grounding grounding;
    private final ActionExecutor executor;
    private final CapabilityInvoker invoker;
    private final CapabilityRegistry registry;
    private final DecisionLog decisions;
    private final TargetCheck targets;
    private final Embeddings embeddings;
    private final LedgerService ledger;
    private final Genesis genesis;
    private final ConstitutionService constitution;
    private final InboxService inbox;
    private final KernelProperties properties;
    private final TrustService trust;
    private final SecurityService security;
    private final Clock clock = Clock.systemDefaultZone();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** Above this similarity a new task probably repeats an existing one; the person is asked first. */
    static final double DUPLICATE_COSINE = 0.8;

    public ConversationService(Grounding grounding, ActionExecutor executor, CapabilityInvoker invoker,
                               CapabilityRegistry registry, DecisionLog decisions, Embeddings embeddings,
                               LedgerService ledger, Genesis genesis, ConstitutionService constitution,
                               InboxService inbox, KernelProperties properties,
                               TrustService trust, SecurityService security) {
        this.security = security;
        this.trust = trust;
        this.genesis = genesis;
        this.constitution = constitution;
        this.inbox = inbox;
        this.grounding = grounding;
        this.executor = executor;
        this.invoker = invoker;
        this.registry = registry;
        this.decisions = decisions;
        this.targets = TargetCheck.using(embeddings);
        this.embeddings = embeddings;
        this.ledger = ledger;
        this.properties = properties;
    }

    /**
     * A sentence from a verified caller, at most {@link #MAX_SENTENCE} characters. The principal is the caller's
     * (never the payload's when security is on); a guest sees and changes only what the invitation covers, and
     * nothing of the sentence is read for a change the guest may not make; a change that is unusual for this person
     * needs a yes signed on the device. Curly apostrophes count as straight ones. A change is applied without a
     * question only when the whole sentence is plain (see {@link PlainWords#wholePlain}) and two readers agree on its
     * dates, hours and statuses; everything else is asked, except a change whose readings differ, which the reply
     * names both readings of so the person says the one meant. The model's reply text is shown only when every change
     * was carried out as the model read it and no read ran (the data shown answers then); after a change, only a
     * reply from a fixed list ("Done.", "Sure.", "Got it.", "Anything else?", "Is there anything else?", see {@link
     * #fixedReply}) is shown, in the list's own words, next to the kernel's own lines. A reply without actions that
     * states counts, dates or ids, quotes something or names a goal is replaced by the kernel's own reading, and so
     * is any reply without actions to a question about the person's goals and tasks (see {@link #dataQuestion}). A
     * question the assistant could not turn into actions is answered from the kernel's own reading too. A request for
     * a new goal the model proposed no change for is offered as that goal. Whenever nothing was changed and nothing
     * was asked, the reply starts with "Nothing was changed.", except a reply from a fixed list of courtesies to a
     * courtesy or greeting alone ("You're welcome!", see {@link #courtesyReply}). Task keys the words name that no
     * proposed change covers are said not to have changed (see {@link #uncoveredKeys}).
     */
    public Conversation.Reply handle(Conversation.Utterance utterance, SecurityService.Caller caller) {
        if (utterance.text() == null || utterance.text().isBlank()) {
            return Conversation.Reply.text("Say what you want to do, or ask a question.");
        }
        if (utterance.text().length() > MAX_SENTENCE) {
            // A sentence is read several times over (dates, names, negations); a longer text is refused unread.
            return Conversation.Reply.text(TOO_LONG);
        }
        if (!registry.has(INTERPRETER)) {
            // Only the owner can admit agents, so only the owner is told that the assistant waits for it.
            return Conversation.Reply.text(caller.owner() ? waitingInterpreter().orElse(NO_ASSISTANT) : NO_ASSISTANT);
        }
        Session session = session(sessionKey(caller, utterance.sessionId()));
        session.newTurn(); // a new sentence replaces questions left unanswered
        Conversation.Mode mode = utterance.mode() == null ? Conversation.Mode.CONVERSE : utterance.mode();
        String principal = caller.principal() != null ? caller.principal() : principal(utterance.principal());
        String said = plainIdioms(spokenDates(withTaskKeys(TargetCheck.straightQuotes(utterance.text().strip()))));
        Optional<String> kernelAnswer = fixedAnswer(said, () -> caller.owner()
                && inbox.unread().stream().anyMatch(m -> !m.suggestions().isEmpty())).or(() -> notEnglish(said))
                .or(() -> unsupported(said)).or(() -> dateAnswer(said, properties.today()));
        if (kernelAnswer.isPresent()) {
            // Greetings, thanks, help, a bare yes or no, today's date and requests for what the system does not do:
            // the kernel's own answer, without the model.
            session.remember(said, kernelAnswer.get());
            return Conversation.Reply.text(kernelAnswer.get());
        }
        Optional<Conversation.Reply> remembered = fromMemory(said, session, caller, mode, principal);
        if (remembered.isPresent()) {
            // "Undo that", "what did you change?", "no, I meant Saturday": answered from what this conversation did.
            return remembered.get();
        }
        Grounding.Snapshot snapshot = grounding.build(said, session.history(), session.focus(), caller::seesGoal,
                caller.owner());
        String turnId = UUID.randomUUID().toString();
        var invocation = invoker.invoke(null, turnId, -1, INTERPRETER, 1, null, snapshot.params(), Set.of());
        if (invocation.result() != null) {
            // Stored in the name of the instance that answered, under its quota, not under the records' own claims.
            decisions.saveAll(null, turnId, -1, invocation.result().decisions(),
                    invocation.manifest() == null ? null : DecisionLog.Source.of(invocation.manifest()));
        }
        Conversation.Interpretation interpretation = invocation.ok() ? interpretation(invocation.result().output())
                : null;
        // A guest's lists are cut to the goals shared with them before they are limited or counted.
        ActionExecutor.Shown shown = caller.owner() ? new ActionExecutor.Shown() : new ActionExecutor.Shown(caller::seesGoal);
        if (interpretation == null) {
            String reason = invocation.ok() ? "unreadable interpretation" : invocation.failureReason();
            log.warn("interpretation failed: {}", reason);
            String problem = interpretationProblem(reason, caller.owner() && reason != null
                    && reason.contains("no live provider") ? waitingInterpreter().orElse(null) : null);
            if (!question(said)) {
                // "Inviting friends isn't blocked anymore" the assistant gave no usable answer for: the kernel's own
                // reading of the news, asked first.
                List<Action> offered = new ArrayList<>();
                List<String> asked = new ArrayList<>();
                Optional<Executed> news = taskNews(said, snapshot, session.focus(), caller, offered, asked);
                if (!offered.isEmpty()) {
                    Conversation.PendingConfirmation confirmation = new Conversation.PendingConfirmation(
                            UUID.randomUUID().toString(), String.join("\n", asked), offered, null, null);
                    session.await(confirmation, caller.keyId());
                    session.remember(said, confirmation.question());
                    return reply(null, List.of(), confirmation, shown);
                }
                if (news.isPresent() && news.get().message().startsWith("tell which task")) {
                    session.remember(said, spoken(List.of(news.get()), List.of()));
                    return reply(null, List.of(news.get()), null, shown);
                }
            }
            Matcher retire = RETIRE_REQUEST.matcher(said);
            if (retire.matches() && caller.owner()) {
                // "Retire the packing list" the assistant could not tie to a capability: the kernel's own match.
                return retireReply(said, retire.group("name"), session, caller, mode, principal);
            }
            Optional<String> nothing = question(said) ? Optional.empty() : nothingNamed(said, snapshot.documents());
            if (nothing.isPresent()) {
                // "I don't need the unicorn anymore" the assistant could not read, when nothing is about a unicorn:
                // said so, with the closest goals.
                Executed refused = new Executed(ActionType.REMOVE_TASK, false, nothing.get(), null, null);
                session.remember(said, spoken(List.of(refused), List.of()));
                return reply(null, List.of(refused), null, shown);
            }
            if ((NO_GOAL.equals(problem) || UNCLEAR.equals(problem)) && !question(said)
                    && caller.may(ActionType.CREATE_GOAL, null)
                    && (asksForSomethingNew(said) || NEED_ANYWHERE.matcher(said).find())) {
                // "I want to organise a surprise party in two weeks, I need to invite friends and order a cake": the
                // goal in the person's words is offered, with the tasks they listed, never created unasked.
                return offerFromWords(said, snapshot, session, caller, true, mode, principal).orElseThrow();
            }
            if ((NO_GOAL.equals(problem) || UNCLEAR.equals(problem)) && question(said)) {
                // A question the assistant could not turn into actions is answered from the kernel's own reading.
                Executed read = kernelRead(said, snapshot, caller, principal, shown);
                if (!caller.owner()) {
                    visibleOnly(shown, caller);
                }
                Optional.ofNullable(read.goalId()).or(() -> read.ok() ? onlyGoalShown(shown) : Optional.empty())
                        .ifPresent(goal -> session.focusOn(Set.of(goal)));
                session.remember(said, spoken(List.of(read), List.of()));
                return reply(null, List.of(read), null, shown);
            }
            // "No goal matches your words" only for a sentence that names a goal.
            return Conversation.Reply.text(NO_GOAL.equals(problem) && TargetCheck.nameWords(said).isEmpty()
                    ? UNCLEAR : problem);
        }

        if (interpretation.actions().isEmpty() && !question(said) && caller.may(ActionType.CREATE_GOAL, null)
                && asksForSomethingNew(said)) {
            // "I will go on a business trip and I need a task for today, a checklist ...", answered by the model with
            // a question back: the goal in the person's words is offered instead, when it fits in a short title.
            Optional<Conversation.Reply> offer = offerFromWords(said, snapshot, session, caller, false, mode,
                    principal);
            if (offer.isPresent()) {
                return offer.get();
            }
        }
        List<Executed> executed = new ArrayList<>();
        List<Action> toConfirm = new ArrayList<>();
        List<String> questions = new ArrayList<>();
        // The changes this turn makes, each with the change that takes it back ("undo that", see Session#changed).
        List<Session.Change> made = new ArrayList<>();
        // Why a change would be asked about when the reason is doubt about its goal or task, or the owner's rule (in
        // ASSIST and DECIDE such a change is rejected with that reason), and the note on how its words were read
        // (in ASSIST and DECIDE it is done, with the note).
        Map<Action, String> doubts = new IdentityHashMap<>();
        Map<Action, String> notes = new IdentityHashMap<>();
        // Changes unusual for this device (see TrustService): asked in ASSIST, rejected in DECIDE, like the security
        // changes; a doubt about their goal or task rejects them first.
        Set<Action> unusualOnly = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> parts = instructions(said);
        if (parts.size() > 1) {
            // "Rename the gift task and move it to Sunday": what the system does not do is said for its own part.
            parts.forEach(part -> unsupported(part).ifPresent(note -> executed.add(new Executed(ActionType.UPDATE_TASK,
                    false, note, null, null))));
        }
        // Changes asked about only because they are unusual for this person: one prefix, then one change per line.
        List<String> stepUps = new ArrayList<>();
        Set<String> touched = new LinkedHashSet<>();
        // Set when the kernel read the sentence differently from the model; its reply text is then not shown.
        boolean overruled = false;
        // Reads that fall back to the list of goals list it once, however many of them the model proposed; a sentence
        // with several questions ("list my goals and what should I do today?") may show each list once.
        boolean listed = false;
        Set<ActionType> listedTypes = EnumSet.noneOf(ActionType.class);
        List<String> questionParts = questions(said);
        Set<String> usedParts = new HashSet<>();
        Set<String> recent = session.focus();
        Ecosystem.CapabilitySpec designed = null;
        Constitution.Rule drafted = null;
        List<Action> done = new ArrayList<>();
        // A new goal whose text the kernel took from elsewhere than the model's text field (see tolerant) is asked.
        Set<Action> retexted = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Action> proposals = new ArrayList<>(merged(interpretation.actions().stream()
                .map(a -> {
                    Action fixed = tolerant(a, snapshot, said, properties.today());
                    Action read = InboxService.normalise(fixed);
                    if (fixed != a) {
                        retexted.add(read);
                    }
                    return read;
                }).toList()));
        if (invocation.result() != null) {
            grounding.measured(invocation.result().decisions(), snapshot);
        }
        // Two habits read by the kernel itself, whatever the model made of them: a new goal "and add X to it", and
        // "add A, B and C to the barbecue".
        withAddClause(said, proposals);
        Matcher information = INFORMATION_FOR_GOAL.matcher(said);
        Optional<String> informedGoal = information.matches()
                && proposals.stream().noneMatch(a -> a.type() == ActionType.REQUEST_CAPABILITY)
                ? targets.clearMatch(information.group("goal"), snapshot.documents()) : Optional.empty();
        if (informedGoal.isPresent()) {
            // "I need a packing list for the picnic": new information for a goal the person has, not a second goal.
            proposals.clear();
            proposals.add(new Action(ActionType.REQUEST_CAPABILITY, informedGoal.get().substring(0, 8), null,
                    "a " + information.group("what") + " for " + goalText(informedGoal.get(), snapshot), null, null,
                    null, null, said, null));
        }
        if (informedGoal.isEmpty() && proposals.stream().anyMatch(a -> a.type() == ActionType.REQUEST_CAPABILITY)
                && proposals.stream().allMatch(a -> a.type().readOnly() || a.type() == ActionType.REQUEST_CAPABILITY)
                && needClause(goalWordsAsSaid(said)).isPresent() && caller.may(ActionType.CREATE_GOAL, null)) {
            // "I will go on a business trip tomorrow, create me a checklist with what I should take" read as a request
            // for a new capability: the person asks for a goal of their own (see #needClause), not for the system to
            // grow.
            proposals.clear();
            proposals.add(new Action(ActionType.CREATE_GOAL, null, null, goalWords(said, properties.today()), null,
                    null, null, null, said, null));
        }
        Matcher invite = INVITE_REQUEST.matcher(said);
        Optional<String> invitedTo = invite.matches() && proposals.stream().noneMatch(a -> !a.type().readOnly())
                && !PRONOUNS.contains(invite.group("who").toLowerCase(Locale.ROOT))
                ? targets.clearMatch(invite.group("what"), snapshot.documents()) : Optional.empty();
        if (invitedTo.isPresent()) {
            // "Let Ana help with the picnic until Saturday" the model proposed nothing for: the invitation the words
            // ask for, asked or refused like every security change.
            LocalDate until = invite.group("until") == null ? null
                    : DateExpressions.find(invite.group("until"), properties.today()).orElse(null);
            proposals.clear();
            proposals.add(new Action(ActionType.INVITE, invitedTo.get().substring(0, 8), null,
                    capitalFirst(invite.group("who")), until, null, null, null, said, said));
        }
        Matcher needs = GOAL_NEEDS.matcher(said);
        Optional<String> needingGoal = informedGoal.isEmpty() && needs.matches()
                && proposals.stream().allMatch(a -> a.type().readOnly() || a.type() == ActionType.ADD_TASK
                || a.type() == ActionType.REQUEST_CAPABILITY)
                && proposals.stream().filter(a -> a.type() == ActionType.ADD_TASK).count() <= 1
                ? targets.clearMatch(needs.group("goal"), snapshot.documents()).filter(this::open) : Optional.empty();
        if (needingGoal.isPresent()) {
            // "The passport thing also needs a copy of my birth certificate": a task for the goal the words name,
            // titled with the thing the words say, whatever the model titled it ("Task for the picnic blanket").
            proposals.clear();
            proposals.add(new Action(ActionType.ADD_TASK, needingGoal.get().substring(0, 8), null,
                    capitalFirst(needs.group("thing").strip().replaceFirst("(?i)^(?:a|an|some|the)\\s+", "")), null,
                    null, null, null, said, null));
        }
        if (NOT_DONE.matcher(said).find() && reopened(said, snapshot).isPresent()
                && proposals.stream().noneMatch(a -> a.type() == ActionType.CREATE_GOAL)) {
            // "I didn't finish the napkins" names one task marked done: the kernel's reading (back to to-do, see
            // #reopened below) replaces whatever the model read, which may be another task ("flowers in progress").
            proposals.clear();
        }
        if (CHANGE_REQUEST.matcher(said).lookingAt() && !ADD_WORDS.matcher(said).find()) {
            // "Move buying flowers to yesterday" asks to change what exists, and adds nothing: no new task.
            proposals.removeIf(a -> a.type() == ActionType.ADD_TASK);
        }
        if (VERIFY_REQUEST.matcher(said).lookingAt()) {
            // "Verify the picnic goal" read as showing it: the words ask for its check and fingerprint.
            proposals.replaceAll(a -> a.type() == ActionType.SHOW_GOAL && a.goal() != null
                    ? new Action(ActionType.VERIFY_GOAL, a.goal(), null, null, null, null, null, null, a.reason(),
                    a.evidence()) : a);
        }
        // "Plan the picnic again" read as reopening or showing an open goal asks for a new plan.
        proposals.replaceAll(a -> (a.type() == ActionType.REOPEN_GOAL || a.type() == ActionType.SHOW_GOAL
                && !question(said)) && snapshot.goal(a.goal())
                .filter(g -> open(g) && asksReplan(said, goalText(g, snapshot))).isPresent()
                ? new Action(ActionType.REFINE_GOAL, a.goal(), null, null, null, null, null, null, a.reason(),
                a.evidence()) : a);
        Optional<String> replanned = question(said) || proposals.stream().anyMatch(a -> !a.type().readOnly())
                ? Optional.empty() : targets.clearMatch(said, snapshot.documents())
                .filter(g -> open(g) && asksReplan(said, goalText(g, snapshot)));
        if (replanned.isPresent()) {
            // "Plan the picnic again" read as a list: the words ask for a new plan of the one goal they name.
            proposals.clear();
            proposals.add(new Action(ActionType.REFINE_GOAL, replanned.get().substring(0, 8), null, null, null, null,
                    null, null, said, null));
        }
        // Actions whose goal the kernel itself found named clearly by a part of the words: no other check of the goal.
        Set<Action> kernelNamed = Collections.newSetFromMap(new IdentityHashMap<>());
        kernelNamed.addAll(showEach(said, proposals, snapshot));
        kernelNamed.addAll(listAdd(said, proposals, snapshot));
        List<Action> drops = proposals.stream().filter(a -> a.type() == ActionType.REMOVE_TASK).toList();
        if (drops.size() >= 2 && drops.size() == proposals.size() && GIVING_UP.matcher(said).find()
                && drops.stream().map(Action::goal).distinct().count() == 1) {
            // "I'm not doing the stamps thing anymore" read as dropping each task gives up the goal: one question.
            Action first = drops.getFirst();
            proposals.clear();
            proposals.add(new Action(ActionType.CANCEL_GOAL, first.goal(), null, null, null, null, null, null,
                    first.reason(), first.evidence()));
        }
        if (STATUS_QUESTION.matcher(said).find() && proposals.stream().allMatch(a -> a.type().readOnly())) {
            // "Is everything running?" is answered from the system's state, never from the model's reply nor from
            // another list it chose.
            proposals.clear();
            proposals.add(new Action(ActionType.SYSTEM_STATUS, null, null, null, null, null, null, null, null, null));
        }
        if (proposals.size() == 1 && proposals.getFirst().type() == ActionType.ADD_TASK
                && proposals.getFirst().goal() != null && needClause(goalWordsAsSaid(said)).isPresent()
                && !addressesGoal(said, snapshot) && caller.may(ActionType.CREATE_GOAL, null)) {
            // "We're having friends over on Saturday, I need to plan dinner" or "next Tuesday is my wife's birthday,
            // create me a task to buy her a present" aimed at another goal: what is going on and the thing asked for
            // are a goal of their own (see #needClause) unless the words put it in a goal.
            proposals.set(0, new Action(ActionType.CREATE_GOAL, null, null, goalWords(said, properties.today()), null,
                    null, null, null, said, null));
        }
        if (REMIND_ME.matcher(said).lookingAt() && proposals.size() == 1
                && proposals.getFirst().type() == ActionType.ADD_TASK && proposals.getFirst().goal() != null
                && TargetCheck.namesAnyGoal(said, snapshot.documents()) && !addressesGoal(said, snapshot)
                && caller.may(ActionType.CREATE_GOAL, null)) {
            // "Remind me to call mom tomorrow" aimed at "Organise a party for my mom", whose words it shares: a
            // reminder is a goal of its own unless the words put it in a goal ("remind me to buy napkins for the
            // picnic"). Words that name no goal at all are offered as a goal below, as any such task is.
            proposals.set(0, offeredGoal(said));
        }
        if ((goalRequest(said) || taskRequest(said)) && !proposals.isEmpty()
                && proposals.stream().allMatch(a -> a.type().readOnly())
                || goalRequest(said) && proposals.stream().noneMatch(a -> a.type() == ActionType.CREATE_GOAL)) {
            // "Can you create a task to call the plumber tomorrow?" asks for something new, not for a list, and "new
            // goal: plan the picnic" for no change to another goal: the new goal in the person's words comes below
            // (see #offeredGoal), with a goal it resembles named.
            proposals.clear();
        }
        List<Action> created = proposals.stream().filter(a -> a.type() == ActionType.CREATE_GOAL).toList();
        if (created.size() == 1 && created.getFirst().text() != null) {
            // "Next Tuesday is my wife's birthday, create a task to buy her a present" read as a new goal and the same
            // words as a task of another goal or of the new goal ("add a reminder to pay the electricity bill" as the
            // goal and its one task): the task repeats the new goal, which is all the sentence asks for.
            // A new goal "and add X to it" keeps X (see #withAddClause).
            Set<String> goalStems = TargetCheck.stems(created.getFirst().text());
            boolean addsToIt = ADD_CLAUSE.matcher(goalWordsAsSaid(said)).matches();
            proposals.removeIf(a -> a.type() == ActionType.ADD_TASK && a.text() != null && (a.goal() != null
                    || !addsToIt) && !TargetCheck.stems(a.text()).isEmpty()
                    && goalStems.containsAll(TargetCheck.stems(a.text())));
            // "..., create me a task by this weekend to go and buy her a present" read as a goal the model named itself
            // with that task in it: the one thing the sentence asks for is the goal (see #needClause).
            Optional<String> need = needClause(goalWordsAsSaid(said));
            String createdText = created.getFirst().text();
            if (need.isPresent()) {
                Set<String> needStems = TargetCheck.stems(need.get());
                proposals.removeIf(a -> a.type() == ActionType.ADD_TASK && a.text() != null
                        && (a.goal() == null || a.goal().strip().equalsIgnoreCase(createdText.strip()))
                        && sharesMost(TargetCheck.stems(a.text()), needStems));
            }
        }
        if (proposals.isEmpty() && !question(said)) {
            // "The speaker will take about an hour" the model gave no change for: the kernel's own reading.
            estimateNews(said, snapshot).ifPresent(proposals::add);
        }
        // "Move the passport to Friday" read as moving a task about the passport: the words name the goal, so the
        // goal moves (see #goalMoveNotTask).
        proposals.replaceAll(a -> goalMoveNotTask(a, said, snapshot, caller));
        List<Action> distinct = List.copyOf(new LinkedHashSet<>(proposals));
        proposals.clear();
        proposals.addAll(distinct);
        List<String> instructionParts = instructions(said);
        if (instructionParts.size() > 1 && !proposals.isEmpty()
                && proposals.stream().noneMatch(a -> TASK_ACTIONS.contains(a.type()))) {
            // "Got the plates, and add sunscreen to the picnic" read as the new task alone: the news part is the
            // kernel's own reading, for the one open task it names.
            for (String part : instructionParts) {
                missedNews(part, snapshot).ifPresent(proposals::add);
            }
        }
        // With several changes aimed at goals, each is tied to its goal by its own part of the sentence.
        long goalChanges = proposals.stream().filter(a -> a.type().targetsGoal() && !a.type().readOnly()).count();
        // How many tasks the sentence marks done: one amount of time for several of them is asked about.
        long completions = proposals.stream().filter(a -> a.type() == ActionType.SET_TASK_STATUS
                && a.status() == TaskStatus.DONE).count();
        // A question or a supposition ("what if I push the picnic back?", "should I mark T8 done?") asks for no
        // change yet: every change proposed for it is asked about.
        boolean supposes = supposes(said);
        // A new goal is the person's own words unless the sentence asks for more than that one change.
        boolean alone = proposals.stream().filter(a -> !a.type().readOnly()).count() == 1;
        // A profile value the words state that the model left out is added, and asked about (see profileCompanions).
        List<Action> companions = profileCompanions(proposals, said);
        proposals.addAll(companions);
        Set<Action> kernelAdded = Collections.newSetFromMap(new IdentityHashMap<>());
        kernelAdded.addAll(companions);
        Optional<String> unusual = caller.keyId() == null ? Optional.empty() : trust.assess(caller,
                proposals.stream().map(Action::type).filter(t -> !t.readOnly()).toList(), clock.instant());
        // Each goal is shown at most once per turn, however many times the model asked to show it.
        Set<String> shownGoals = new HashSet<>();
        // The changes the sentence asks for: with several, each is judged on its own part of the sentence.
        List<Action> changeProposals = proposals.stream().filter(a -> !a.type().readOnly()).toList();
        // The tasks a change of one kind already landed on in this turn ("goal/task/type").
        Set<String> landed = new HashSet<>();
        // The tasks the turn's task changes are aimed at ("goal/task"): no change is moved onto one of them.
        Set<String> targeted = new HashSet<>();
        changeProposals.stream().filter(a -> TASK_ACTIONS.contains(a.type()) && a.task() != null)
                .forEach(a -> snapshot.goal(a.goal()).ifPresent(g -> targeted.add(g + "/"
                        + a.task().strip().toUpperCase(Locale.ROOT))));
        // A sentence with a change the kernel judges word by word (see PLAIN_JUDGED) applies its changes only when the
        // whole sentence is plain (see PlainWords#wholePlain): until every part is judged, those changes wait here.
        boolean judgedTurn = changeProposals.stream().anyMatch(a -> PLAIN_JUDGED.contains(a.type()));
        List<String> plainParts = new ArrayList<>();
        List<Waiting> ready = new ArrayList<>();
        // Tasks the person asked for on the goal this sentence creates: asked with it, added to it on yes.
        boolean creatingTurn = proposals.stream().anyMatch(a -> a.type() == ActionType.CREATE_GOAL);
        List<Action> newGoalTasks = new ArrayList<>(creatingTurn ? proposals.stream().filter(a -> a.type()
                == ActionType.ADD_TASK && a.goal() == null && a.text() != null && !a.text().isBlank()).toList()
                : List.of());
        for (Action proposed : proposals) {
            if (newGoalTasks.contains(proposed)) {
                continue;
            }
            Action reinterpreted = reinterpret(proposed, said);
            String goalId = snapshot.goal(reinterpreted.goal()).orElse(null);
            String approvalId = snapshot.approval(reinterpreted.approval()).orElse(null);
            if ((reinterpreted.type() == ActionType.APPROVE_STEP || reinterpreted.type() == ActionType.REJECT_STEP)
                    && approvalId == null && snapshot.approvalIds().isEmpty() && !inbox.unread().isEmpty()
                    && !said(STEP_WORDS, said)) {
                // Nothing waits for approval but messages do: "approve it" answers a message, and the person's words
                // must say which one. Words that speak of a step ("reject the calendar step") are told that nothing
                // waits for approval.
                reinterpreted = new Action(ActionType.ANSWER_MESSAGE, reinterpreted.goal(), null,
                        reinterpreted.type() == ActionType.APPROVE_STEP ? "accept" : DISMISS, null, null, null,
                        reinterpreted.approval(), reinterpreted.reason(), reinterpreted.evidence());
            }
            if (!caller.may(reinterpreted.type(), goalId)) {
                // A guest acts only within the invitation: these goals, these kinds of change. Nothing else of the
                // sentence is read for a change the caller may not make.
                executed.add(new Executed(reinterpreted.type(), false, "not part of what you were invited to do", goalId,
                        null));
                continue;
            }
            ActionType taskType = reinterpreted.type();
            Optional<TaskRef> elsewhere = goalId != null && TASK_ACTIONS.contains(taskType)
                    && taskType != ActionType.ADD_TASK ? taskNamedElsewhere(said, goalId, snapshot)
                    .filter(ref -> caller.may(taskType, ref.goalId())) : Optional.empty();
            if (elsewhere.isPresent()) {
                // "The speaker isn't blocked anymore" aimed at a goal with no such task: the one goal whose task the
                // words name is the goal meant, when the caller may change it.
                log.info("{} moved from {} to {} {}", reinterpreted.type(), goalId.substring(0, 8),
                        elsewhere.get().goalId().substring(0, 8), elsewhere.get().key());
                goalId = elsewhere.get().goalId();
                reinterpreted = withTask(withGoal(reinterpreted, goalId.substring(0, 8)), elsewhere.get().key());
            }
            // The task a task change lands on is chosen before its date and hours are read, so they are read for that
            // task: a relative move counts from its date, and its own part of the sentence gives its hours.
            TaskChoice choice = goalId != null && reinterpreted.task() != null
                    && TASK_ACTIONS.contains(reinterpreted.type())
                    ? chooseTask(reinterpreted, goalId, said, snapshot, changeProposals, targeted, recent) : null;
            if (choice != null && choice.refusal() != null) {
                executed.add(choice.refusal());
                continue;
            }
            if (choice != null && !choice.task().key().equalsIgnoreCase(reinterpreted.task().strip())) {
                reinterpreted = withTask(reinterpreted, choice.task().key());
            }
            if (choice != null && !landed.add(goalId + "/" + choice.task().key() + "/" + reinterpreted.type())) {
                // Two changes of one kind for one task would ask two questions about one thing: the first is asked,
                // and the reply says which change was not made.
                executed.add(new Executed(reinterpreted.type(), false, "change " + choice.task().key()
                        + " one way at a time: " + executor.describe(reinterpreted, goalId, null) + " was not made; "
                        + "answer the question about " + choice.task().key() + ", then say what else should change",
                        goalId, null));
                continue;
            }
            // This change's own part of the sentence: its date and its plainness are read from it alone.
            String own = ownWords(reinterpreted, goalId, choice, said, changeProposals, snapshot);
            if (choice != null && reinterpreted.type() == ActionType.SET_TASK_STATUS && own != null
                    && PlainWords.statesNoStatus(own) && !PlainWords.statesNoStatus(said)
                    && sharedStatus(changeProposals)) {
                // "Mark both the card and the cake done": one status said once for tasks joined by "and".
                own = said;
            }
            Optional<LocalDate> doOn = choice != null && reinterpreted.type() == ActionType.SET_TASK_STATUS
                    && PlainWords.statesNoStatus(own != null ? own : said)
                    ? DateExpressions.find(own != null ? own : said, properties.today())
                    .filter(d -> !d.isBefore(properties.today())) : Optional.empty();
            if (doOn.isPresent()) {
                // "Tomorrow I'll do the passport photos": no progress, but a day to do it on: the task's date, asked.
                reinterpreted = new Action(ActionType.UPDATE_TASK, reinterpreted.goal(), choice.task().key(), null,
                        doOn.get(), null, null, null, reinterpreted.reason(), reinterpreted.evidence());
            }
            if (choice != null && reinterpreted.type() == ActionType.SET_TASK_STATUS
                    && PlainWords.statesNoStatus(own != null ? own : said)) {
                // "great, the tiles look good now": the words say nothing about the task's progress, so no status is
                // offered for it.
                executed.add(new Executed(reinterpreted.type(), false, "change " + choice.task().key() + " \""
                        + choice.task().title() + "\": your words say nothing about its progress; say \""
                        + choice.task().key() + " is done\" to mark it done", goalId, null));
                continue;
            }
            String spentWords = own != null ? own : said;
            if (choice != null && reinterpreted.hours() != null && (reinterpreted.type() == ActionType.UPDATE_TASK
                    && reinterpreted.dueDate() == null && SPENT.matcher(spentWords).find()
                    || reinterpreted.type() == ActionType.SET_TASK_STATUS && reinterpreted.status() == TaskStatus.DONE
                    && SPENT_ONLY.matcher(spentWords).find() && !FINISHED.matcher(spentWords).find())) {
                // "I spent 2 hours on the report today": time already spent is no estimate of the whole task, nor a
                // sign that it is done, and it is recorded only when the task is done. The hint uses the hours said.
                List<Double> stated = statedDurations(spentWords);
                double spent = stated.size() == 1 ? stated.getFirst() : reinterpreted.hours();
                executed.add(new Executed(reinterpreted.type(), false, "record time spent on " + choice.task().key()
                        + " \"" + choice.task().title() + "\": time spent is recorded when the task is done; say \""
                        + choice.task().key() + " is done, it took " + hoursText(spent)
                        + (spent == 1.0 ? " hour" : " hours") + "\" when it is, or \""
                        + choice.task().key() + " will take 3 hours\" for an estimate",
                        goalId, null));
                continue;
            }
            Resolved resolved = resolve(reinterpreted, goalId, said, own, completions);
            Action action = resolved.action();
            // Outside CONVERSE the kernel's reading of hours the model read differently is the one done, both named.
            String hoursNote = resolved.assistantHours() != null && choice != null && mode != Conversation.Mode.CONVERSE
                    ? " (I read " + hoursText(action.hours()) + " h in your words; the assistant read "
                    + hoursText(resolved.assistantHours()) + " h)" : "";
            if (resolved.assistantHours() != null && choice != null && hoursNote.isEmpty()) {
                // The words and the model read different hours: the person says the hours they mean in a new
                // sentence; neither reading is offered as the one to apply.
                overruled = true;
                executed.add(new Executed(action.type(), false, "say the hours you mean for " + choice.task().key()
                        + " \"" + choice.task().title() + "\": I read " + hoursText(action.hours())
                        + " h, the assistant read " + hoursText(resolved.assistantHours()) + " h", goalId, null));
                continue;
            }
            if ((action.type() == ActionType.RESCHEDULE_GOAL || action.type() == ActionType.UPDATE_TASK
                    || action.type() == ActionType.ADD_TASK) && action.dueDate() != null
                    && action.dueDate().isBefore(properties.today())) {
                // "Move the picnic to last Monday": a day that has passed is refused at once, never asked about.
                overruled = true;
                executed.add(new Executed(action.type(), false, executor.describe(action, goalId, approvalId) + ": "
                        + action.dueDate() + " has already passed; say a day from today on", goalId, null));
                continue;
            }
            // The words the date of this action was read from: its own part of the sentence, else the sentence.
            String dateWords = resolved.words() != null ? resolved.words() : said;
            // Whether the words of this change's own part are plain for it (see PlainWords#plain); a plain part
            // counts towards the whole sentence being plain (see PlainWords#wholePlain).
            boolean ownPlain = plainFor(reinterpreted, goalId, choice, own, alone, snapshot);
            if (ownPlain) {
                plainParts.add(own);
            }
            boolean setsDeadline = action.type() == ActionType.CREATE_GOAL || action.type() == ActionType.ADD_TASK;
            if (dated(action) && resolved.source() == ActionDates.Source.WORDS && action.dueDate() != null
                    && ActionDates.anaphoricWeekday(dateWords, said)) {
                // "move T3 to December 1 and T4 to that Friday": the weekday may count from the other date, so the
                // kernel offers no reading of its own; the assistant's is asked about, and without one the person
                // says the date.
                if (proposed.dueDate() == null) {
                    overruled = true;
                    executed.add(new Executed(action.type(), false, "say the date you mean: "
                            + ActionDates.datePhrase(dateWords).map(p -> "\"" + p + "\"").orElse("your weekday")
                            + " may count from another date you named", goalId, null));
                    continue;
                }
                resolved = resolved.assistantsReading(proposed.dueDate());
                action = resolved.action();
            }
            // A deadline for a new goal or task whose words name no date at all is the model's invention, and so is
            // one for words that name no deadline ("a 5 km walk", "the pm7 shed door"): it is left out and the reply
            // says so. So is a date the words only name as a repeating day ("practise piano each Friday").
            LocalDate invented = setsDeadline && (resolved.source() == ActionDates.Source.MODEL
                    || resolved.source() == ActionDates.Source.MODEL_READING
                    && (resolved.recurrence() != null || ActionDates.deadlinePhrase(dateWords).isEmpty()))
                    ? action.dueDate() : null;
            // A date the words name for a new goal or task is its deadline at once only in the one strict form: a
            // strong marker right before the one date phrase that ends its words, with no second clause before it
            // ("plant tulips by Friday", "add a task to buy napkins by the 4th"; see ActionDates#strictDeadline).
            boolean strong = setsDeadline && resolved.source() == ActionDates.Source.WORDS && action.dueDate() != null
                    && ActionDates.strictDeadline(dateWords, properties.today());
            // Any other date the words name while the model gave none is no deadline ("renew my passport, the office
            // is closed on Friday", "learn the song I heard before Christmas", "hold off on the car until December
            // 4"): left out, and the reply says so. A date that ends the words and lacks only its "by" ("plan a picnic
            // next Sunday") is asked about as the deadline instead.
            LocalDate unmarked = setsDeadline && invented == null && resolved.source() == ActionDates.Source.WORDS
                    && action.dueDate() != null && !strong && proposed.dueDate() == null
                    && !ActionDates.onlyMarkerMissing(dateWords) ? action.dueDate() : null;
            // Such a date that the model also takes for the deadline, and a deadline the kernel cannot read itself
            // ("by next spring"), are asked.
            boolean unsureDeadline = setsDeadline && invented == null && unmarked == null && !strong
                    && action.dueDate() != null && (resolved.source() == ActionDates.Source.WORDS
                    || resolved.source() == ActionDates.Source.MODEL_READING);
            if (invented != null || unmarked != null) {
                action = withDue(action, null);
            }
            boolean move = action.type() == ActionType.RESCHEDULE_GOAL
                    || action.type() == ActionType.UPDATE_TASK && action.dueDate() != null;
            // A move whose words name no date: the model's date is its own guess. When the person refers to what an
            // unread message suggested for this goal, they answer that message; otherwise they are asked.
            boolean guessedDate = move && resolved.source() == ActionDates.Source.MODEL;
            // A deadline move for which neither the words nor the model give a date: no date is taken from anything
            // else (the model's free text included); it is refused, unless it answers a suggestion.
            boolean noDate = action.type() == ActionType.RESCHEDULE_GOAL && resolved.source() == ActionDates.Source.NONE;
            // A move to a date the kernel cannot read itself (a phrase it cannot work out, or a sentence whose part
            // about this action cannot be told apart) goes to the assistant's reading of the words: it is asked.
            boolean modelRead = move && resolved.source() == ActionDates.Source.MODEL_READING
                    && action.dueDate() != null;
            Inbox.Message suggested = guessedDate || noDate ? suggestionFor(action, goalId, said).orElse(null) : null;
            if (suggested != null) {
                action = new Action(ActionType.ANSWER_MESSAGE, action.goal(), null, "accept", null, null, null,
                        suggested.id(), action.reason(), action.evidence());
            }
            ActionType type = action.type();
            if (type != reinterpreted.type() && !caller.may(type, goalId)) {
                // A guest acts only within the invitation: these goals, these kinds of change.
                executed.add(new Executed(type, false, "not part of what you were invited to do", goalId, null));
                continue;
            }
            if (resolved.source() == ActionDates.Source.RULED_OUT && move) {
                overruled = true;
                executed.add(new Executed(type, false, "say the date you mean: " + resolved.proposed()
                        + " is a date your words rule out", goalId, null));
                continue;
            }
            if (noDate && suggested == null) {
                overruled = true;
                executed.add(new Executed(type, false, "say the date you mean: " + (ActionDates.statesDate(said, null,
                        properties.today()) ? "I could not tell which part of your words gives the new deadline"
                        : "your words name no new deadline"), goalId, null));
                continue;
            }
            // The words and the model name different dates: the kernel's reading of the words is asked about, with the
            // model's reading named beside it, for a move as for a new goal or task; it is never applied unasked.
            boolean disputed = dated(action) && unmarked == null && disputedDate(resolved, proposed.dueDate());
            // A date read from the words is used without asking only when two readers agree: the kernel's own reading
            // is simple (one date phrase, nothing corrected, negated or excluded, and for a move a date on the side
            // of the current date the words move it to: "push it back" is later) and the model proposed the same
            // date.
            boolean wordsDate = dated(action) && suggested == null && resolved.source() == ActionDates.Source.WORDS
                    && action.dueDate() != null;
            String direction = move && wordsDate
                    ? ActionDates.wrongDirection(dateWords, resolved.from(), action.dueDate()) : "";
            boolean unsureDate = wordsDate && (!ActionDates.simple(dateWords, properties.today())
                    || !direction.isEmpty());
            if (unsureDate && move && proposed.dueDate() == null && !direction.isEmpty()) {
                // A date on the wrong side of the current one ("push it back" to an earlier day), and no reading of the
                // model's to ask about: the person says the date. Any other unsure reading is asked about.
                overruled = true;
                executed.add(new Executed(type, false, "say the date you mean" + forTask(choice) + ": "
                        + (direction.isEmpty()
                        ? "your words do not name one date for sure" : direction.strip().replaceAll("^\\(|\\)$", "")),
                        goalId, null));
                continue;
            }
            // A simple reading of the words that the model did not give: asked about, with that reading.
            boolean unconfirmed = wordsDate && !unsureDate && proposed.dueDate() == null;
            String repeats = resolved.recurrence() == null || action.dueDate() != null ? ""
                    : "; no deadline set: \"" + resolved.recurrence() + "\" repeats, and a deadline is one date";
            String dateNote = suggested != null ? ""
                    : invented != null ? (repeats.isEmpty() ? "; no deadline set: " + noDateRead(dateWords) : repeats)
                    + " (the assistant suggested " + invented + ")"
                    : unmarked != null ? (!repeats.isEmpty() ? repeats : "; no deadline set: your words mention "
                    + unmarked + ActionDates.datePhrase(dateWords).map(p -> " (\"" + p + "\")").orElse("")
                    + ", but " + ActionDates.notStrictReason(dateWords, properties.today()))
                    : resolved.source() == ActionDates.Source.RULED_OUT
                    ? "; no deadline set: " + resolved.proposed() + " is a date your words rule out"
                    : modelRead ? modelMoveNote(resolved)
                    : !repeats.isEmpty() ? repeats
                    : direction + noDeadlineNote(action, resolved.words()) + modelReadingNote(action, resolved, dateWords)
                    + (guessedDate && suggested == null ? " (" + noDateRead(dateWords) + ")" : "")
                    + (unconfirmed && !unsureDeadline ? NO_MODEL_DATE : "");
            if (unsureDeadline && resolved.source() == ActionDates.Source.WORDS && !disputed) {
                dateNote += " (" + ActionDates.notStrictReason(dateWords, properties.today()) + ")";
            }
            if (disputed) {
                dateNote += otherDateNote(proposed.dueDate(), said, ActionDates.datePhrase(dateWords).orElse(""));
            } else if (unsureDate && move && proposed.dueDate() == null) {
                dateNote += NO_MODEL_DATE;
            }
            // A number the words and the model read differently (hours) is asked about too.
            dateNote += resolved.numberNote() + hoursNote;
            overruled |= !dateNote.isEmpty();
            // A date or hours the kernel read from the words where the model proposed none: the model's reply may
            // not know them, so it is not shown.
            overruled |= dated(action) && proposed.dueDate() == null && action.dueDate() != null
                    || proposed.hours() == null && action.hours() != null && TASK_ACTIONS.contains(type);
            // What the kernel read differently from the model is asked about, never applied silently, and so is
            // what it read from words too unsure to act on unasked.
            // A change proposed for a question or a supposition is asked about too.
            // Set when the goal or task of this change is in doubt: then it is never done unasked, in any mode.
            String targetDoubt = null;
            boolean grounded = reinterpreted == proposed && !guessedDate && !unconfirmed && !modelRead && !unsureDate
                    && resolved.numberNote().isEmpty() && !resolved.unsureNumber() && !kernelAdded.contains(proposed)
                    && !retexted.contains(proposed) && !(supposes && !type.readOnly()) && !unsureDeadline && !disputed;
            if (type == ActionType.ADD_RULE) {
                if (drafted != null) {
                    executed.add(new Executed(type, false, "one rule at a time: answer this one, then say the next",
                            null, null));
                    continue;
                }
                if (!RULE_WORDS.matcher(said).find()) {
                    // "I can't pick a venue until my sister tells me her budget" is no rule, whatever the model read.
                    executed.add(new Executed(type, false, NOT_A_RULE, null, null));
                    continue;
                }
                String wording = ruleWording(action.text(), said);
                ConstitutionService.Compiled compiled = constitution.compile(wording, principal);
                if (compiled.rule() == null) {
                    executed.add(new Executed(type, false, compiled.problem(), null, null));
                    continue;
                }
                drafted = compiled.rule();
                shown.ruleImpact = compiled.impact();
                toConfirm.add(new Action(type, null, null, wording, null, null, null, null, action.reason(),
                        action.evidence()));
                questions.add("I read that as a rule: " + compiled.description() + " " + impactSentence(compiled.impact())
                        + " Make it a rule?");
                continue;
            }
            if (type == ActionType.REMOVE_RULE) {
                String words = action.text();
                Optional<Constitution.Rule> rule = constitution.find(action.approval())
                        .or(() -> constitution.find(words));
                if (rule.isEmpty()) {
                    executed.add(new Executed(type, false, "tell which rule you mean; "
                            + "ask \"what are my rules?\" to see them", null, null));
                    continue;
                }
                toConfirm.add(new Action(type, null, null, rule.get().id(), null, null, null, null,
                        action.reason(), action.evidence()));
                questions.add("Repeal your rule \"" + rule.get().text() + "\" (" + rule.get().id() + ")?");
                continue;
            }
            if (type == ActionType.CORRECT_PROFILE) {
                // Every profile change a sentence asks for is asked about, stating the value that would last; a yes
                // stores exactly that value. Hours per day the words and the model read differently, or several the
                // model's reading is not among, are not offered: the person says the hours they mean.
                if (resolved.assistantHours() != null) {
                    overruled = true;
                    executed.add(new Executed(type, false, "set your daily hours: say the hours you mean: I read "
                            + hoursText(action.hours()) + " h a day, the assistant read "
                            + hoursText(resolved.assistantHours()) + " h", null, null));
                    continue;
                }
                if (action.hours() == null && "dailyHours".equals(canonicalKey(action.text()))
                        && !resolved.numberNote().isEmpty()) {
                    overruled = true;
                    executed.add(new Executed(type, false, "set your daily hours: say the one number of hours a day "
                            + "you mean" + resolved.numberNote(), null, null));
                    continue;
                }
                ProfileChange change = profileChange(action, reinterpreted.hours(), said, currentDays(snapshot));
                if (change.problem() != null) {
                    executed.add(new Executed(type, false, change.problem(), null, null));
                    continue;
                }
                ConstitutionService.Ruling ruling = constitution.checkPerson(type, null, null);
                if (ruling.verdict() == ConstitutionService.Verdict.FORBID) {
                    executed.add(new Executed(type, false, forbidden(ruling, executor.describe(change.action(), null,
                            null)), null, null));
                    continue;
                }
                boolean read = grounded && change.read() && profileGrounded(said, action)
                        && sameProfileKey(reinterpreted.text(), action.text());
                String question = profileQuestion(change, read, resolved.numberNote());
                toConfirm.add(change.action());
                questions.add(ruling.verdict() == ConstitutionService.Verdict.ASK_FIRST
                        ? capitalise(ruling.explanation()) + " says to ask you first. " + question : question);
                overruled = true;
                continue;
            }
            if (type == ActionType.ANSWER_MESSAGE) {
                List<Inbox.Message> unread = inbox.unread();
                Optional<Inbox.Message> named = action.approval() == null || action.approval().isBlank()
                        ? Optional.empty() : inbox.find(action.approval().strip());
                // The goal of a message is read from the words without the answer itself ("yes, accept the suggestion
                // for the bake sale" is about the bake sale), by the strict test that grounds changes. The kernel's
                // top-ranked goal by meaning alone (see TargetCheck#likely) may only make the person be asked about
                // a message, never choose the one a yes carries out.
                String about = MessageCheck.withoutAnswerWords(said);
                Predicate<Inbox.Message> aboutItsGoal = m -> m.goalId() != null
                        && targets.check(about, null, m.goalId(), snapshot.documents(), Set.of()).grounded();
                Optional<String> likelyGoal = targets.likely(about, snapshot.documents());
                Predicate<Inbox.Message> likelyItsGoal = m -> aboutItsGoal.test(m)
                        || m.goalId() != null && likelyGoal.filter(m.goalId()::equals).isPresent();
                Optional<Inbox.Message> found = suggested != null ? Optional.of(suggested)
                        : MessageCheck.answered(named, said, unread, aboutItsGoal);
                Optional<Inbox.Message> onlyLikely = found.isPresent() ? Optional.empty()
                        : MessageCheck.answered(named, said, unread, likelyItsGoal);
                if (found.isEmpty() && onlyLikely.isEmpty()) {
                    executed.add(new Executed(type, false, MessageCheck.which(
                            MessageCheck.tied(said, unread, likelyItsGoal), unread.size()), null, null));
                    continue;
                }
                Inbox.Message m = found.orElseGet(onlyLikely::get);
                Boolean intent = intent(said);
                boolean destructive = Inbox.destructive(m.suggestions());
                // A message is answered without asking only by a sentence judged like every change: it asks for no
                // other change, is no question, nothing in it makes the answer wait on something, take it back or
                // correct it (see PlainWords#settled), and every word of it belongs to the answer. That is a sentence
                // that is nothing but an answer to the message, apart from its id ("yes", "no, not now", "accept
                // feedbeef", "yes, accept the suggestion for the bake sale"), or a change that carries out its
                // suggestion and whose own words are plain for that change once the reference to the suggestion is
                // set aside ("push T3 of the picnic like you suggested"). "ok, mark T3 as done", "accept feedbeef if it
                // rains" and "push T3 like you suggested, I mean T4" are asked about.
                boolean alongside = proposals.stream().anyMatch(p -> p != proposed && !p.type().readOnly());
                boolean plain = !alongside && !supposes && PlainWords.settled(said) && (suggested != null
                        ? own != null && plainFor(reinterpreted, goalId, choice,
                        PlainWords.withoutSuggestionReference(own), alone, snapshot)
                        && PlainWords.wholePlain(said, List.of(own))
                        : MessageCheck.onlyAnswers(MessageCheck.withoutMessageId(said, m), m));
                if (onlyLikely.isPresent() && m.status() == Inbox.Status.UNREAD) {
                    // Only the meaning points at this message: the person confirms which message they answer.
                    boolean dismiss = Boolean.FALSE.equals(intent) || !accepts(action.text());
                    toConfirm.add(new Action(type, m.goalId(), null, dismiss ? DISMISS : "accept", null, null, null,
                            m.id(), null, null));
                    questions.add(dismiss ? "Dismiss the message \"" + m.text() + "\"?" : acceptQuestion(m));
                } else if (m.status() != Inbox.Status.UNREAD) {
                    executed.addAll(answer(m, false, false, principal, shown).executed());
                } else if (Boolean.FALSE.equals(intent)) {
                    if (plain) {
                        executed.addAll(answer(m, false, false, principal, shown).executed());
                    } else {
                        // A no among other words ("not now, move the picnic to Friday") may not be about the message.
                        toConfirm.add(new Action(type, m.goalId(), null, DISMISS, null, null, null, m.id(), null,
                                null));
                        questions.add("Dismiss the message \"" + m.text() + "\"?");
                    }
                } else if (!accepts(action.text())) {
                    // The model reads the sentence as a no while the words do not clearly say no ("it's fine", "ok,
                    // stick with Friday"): ask before dismissing anything, and never accept on the kernel's yes.
                    toConfirm.add(new Action(type, m.goalId(), null, DISMISS, null, null, null, m.id(), null, null));
                    questions.add("Dismiss the message \"" + m.text() + "\"?");
                } else if (Boolean.TRUE.equals(intent) && !destructive && plain) {
                    Answered answered = answer(m, true, false, principal, shown);
                    executed.addAll(answered.executed());
                    if (answered.question() != null) {
                        toConfirm.add(answered.retry());
                        questions.add(answered.question());
                    }
                } else {
                    // The person did not clearly say yes, said more than a yes, or the suggestion is destructive: ask
                    // before acting.
                    toConfirm.add(new Action(type, m.goalId(), null, "accept", null, null, null, m.id(), null, null));
                    questions.add(acceptQuestion(m));
                }
                if (m.goalId() != null) {
                    touched.add(m.goalId());
                }
                continue;
            }
            if (type == ActionType.REVOKE_ACCESS && caller.owner()) {
                // Resolved before asking, so the question names exactly whose access ends.
                String whose = action.text() != null ? action.text() : said;
                List<SecurityService.Holder> holders = security.match(whose);
                if (holders.size() != 1) {
                    executed.add(new Executed(type, false, holders.isEmpty()
                            ? "nobody with access matches \"" + whose + "\"; ask \"who has access?\""
                            : "that matches more than one: " + holders.stream().map(SecurityService.Holder::label).toList(), null, null));
                    continue;
                }
                // The question carries the holder's exact reference (its kind and whole key id, see
                // SecurityService.Holder#ref): a yes ends exactly the access shown, whatever names others chose.
                toConfirm.add(new Action(type, null, null, holders.getFirst().ref(), null, null, null, null,
                        action.reason(), action.evidence()));
                questions.add("End the access of " + holders.getFirst().label() + "?");
                continue;
            }
            if (type == ActionType.ADMIT_PROVIDER && caller.owner()) {
                // Resolved before asking, so the question shows exactly the contract that will be admitted.
                List<SecurityService.PendingAdmission> waiting = waitingAgents(action, said);
                if (waiting.size() != 1) {
                    executed.add(new Executed(type, false, waiting.isEmpty()
                            ? "no agent is waiting for admission under that name; run fabric without a sentence to "
                            + "review waiting agents"
                            // Two keys may ask under one name: each is shown with its key, which tells them apart.
                            : "that matches more than one waiting agent: " + String.join("; ", waiting.stream()
                            .map(p -> "\"" + Grounding.oneLine(p.provider()) + "\" (key " + fingerprint(p.keyId())
                                    + ")").toList()), null, null));
                    continue;
                }
                SecurityService.PendingAdmission agent = waiting.getFirst();
                // The question carries the exact key id of the agent shown and the hash of the contract shown; a yes
                // admits that key by its id alone, never by a provider name, and is refused when the contract it asks
                // for changed since (see ActionExecutor's ADMIT_PROVIDER).
                toConfirm.add(new Action(type, null, null, agent.provider(), null, null, null, agent.keyId(),
                        action.reason(), agent.contractHash()));
                questions.add(admissionQuestion(agent));
                continue;
            }
            if (type == ActionType.REQUEST_CAPABILITY) {
                if (designed != null) {
                    executed.add(new Executed(type, false, "one new capability at a time: answer this one first",
                            null, null));
                    continue;
                }
                if (TASK_NEWS.matcher(said).find()) {
                    // "I'm stuck on the venue, waiting for my sister" is news about a task, whatever the model read.
                    executed.add(new Executed(type, false, NOT_A_REQUEST, null, null));
                    continue;
                }
                if (goalId != null && !targets.check(said, action.evidence(), goalId, snapshot.documents(),
                        recent).grounded()) {
                    goalId = null; // grow the capability, but do not guess which goal to use it on
                }
                if (goalId == null) {
                    goalId = targets.clearMatch(said, snapshot.documents()).orElse(null);
                }
                ConstitutionService.Ruling ruling = constitution.checkPerson(type, goalId);
                if (ruling.verdict() == ConstitutionService.Verdict.FORBID) {
                    executed.add(new Executed(type, false, forbidden(ruling, null), goalId, null));
                    continue;
                }
                Optional<String> full = executor.growthFull();
                if (full.isPresent()) {
                    // No room for one more grown capability: nothing is designed that could not be grown.
                    executed.add(new Executed(type, false, "grow a capability for that: " + full.get(), goalId, null));
                    continue;
                }
                Genesis.Design design = genesis.design(action.text(), goalId, principal);
                if (design.spec() == null) {
                    executed.add(new Executed(type, false, "design a capability for that: " + design.problem(),
                            goalId, null));
                    continue;
                }
                designed = design.spec();
                toConfirm.add(new Action(type, goalId, null, action.text(), null, null, null, null,
                        action.reason(), action.evidence()));
                questions.add(growQuestion(designed, goalId));
                if (goalId != null) {
                    touched.add(goalId);
                }
                continue;
            }
            if (type.targetsGoal() && type.readOnly()) {
                // A read may show the model's goal when the kernel ranks it first by a clear margin, even when the
                // strict test that changes need is not met: showing a goal changes nothing.
                TargetCheck.Result check = goalId == null ? null : kernelNamed.contains(proposed)
                        ? new TargetCheck.Result(TargetCheck.Verdict.GROUNDED, "named in its own part")
                        : targets.readable(said, action.evidence(), goalId, snapshot.documents(), recent)
                        ? new TargetCheck.Result(TargetCheck.Verdict.GROUNDED, "ranked first")
                        : targets.check(said, action.evidence(), goalId, snapshot.documents(), recent);
                if (check == null || !check.grounded()) {
                    // A read the words do not tie to one goal is answered from the kernel's own reading: when the
                    // sentence names no goal ("how many goals are overdue?"), the list of goals or the agenda; else
                    // a goal it names that nobody has, the kernel's own match (clear, by a word only one goal uses,
                    // or ranked first by a clear margin), or that no goal clearly answers to the name.
                    overruled = true;
                    boolean namesGoal = !TargetCheck.nameWords(said).isEmpty();
                    Optional<String> unknown = namesGoal ? TargetCheck.unknownName(said, snapshot.documents())
                            : Optional.empty();
                    Optional<String> match = namesGoal ? kernelMatch(said, snapshot.documents()) : Optional.empty();
                    if (unknown.isPresent()) {
                        executed.add(new Executed(type, false, noSuchGoal(unknown.get(), snapshot.documents()), null,
                                null));
                        continue;
                    }
                    if (match.isPresent() && caller.may(type, match.get())) {
                        goalId = match.get();
                    } else if (type == ActionType.SHOW_GOAL && namesGoal) {
                        // A name no goal clearly answers to is said so, with the closest goals, not a whole list.
                        executed.add(new Executed(type, false, unclearGoal(said, snapshot.documents()), null, null));
                        continue;
                    } else if (type == ActionType.SHOW_GOAL) {
                        if (!listed) {
                            listed = true;
                            executed.add(fallbackRead(said, principal, shown));
                        }
                        continue;
                    } else {
                        executed.add(new Executed(type, false, "tell which goal you mean"
                                + (check == null ? "" : " (" + check.reason() + ")") + "; say its name", null, null));
                        continue;
                    }
                }
                if (type == ActionType.SHOW_GOAL && !shownGoals.add(goalId)) {
                    continue;
                }
            } else if (type.targetsGoal() && goalId != null) {
                // With several goal changes, or a goal created in the same sentence ("cancel the electricity bill goal
                // and create a goal to pay the gas bill"), each change is judged on its own part.
                String words = goalChanges > 1 || creatingTurn && changeProposals.size() > 1
                        ? ownPart(said, action, snapshot.documents().get(goalId),
                        choice == null ? null : choice.task().title()) : said;
                if (type == ActionType.REFINE_GOAL) {
                    // "replan", "plan ... again" ask for a plan; they name no goal.
                    words = withoutReplanWords(words, goalText(goalId, snapshot));
                }
                Optional<String> newTrip = type == ActionType.ADD_TASK && alone && !kernelNamed.contains(proposed)
                        ? tripNotIn(said, snapshot.documents().get(goalId)) : Optional.empty();
                if (newTrip.isPresent() && caller.may(ActionType.CREATE_GOAL, null)) {
                    // "I will go on a business trip and I need a checklist ..." aimed at the holiday checklist: the
                    // trip is new, so what the person needs for it is offered as a goal of its own.
                    // A deadline that ends what is needed ("... by the end of the day") is the goal's, not its title.
                    LocalDate due = neededBy(newTrip.get());
                    Action asGoal = new Action(ActionType.CREATE_GOAL, null, null,
                            goalTitle(newTrip.get(), due != null), due != null ? due : action.dueDate(), null, null,
                            null, action.reason(), action.evidence());
                    toConfirm.add(asGoal);
                    questions.add("Did you mean: " + executor.describe(asGoal, null, null) + "?");
                    overruled = true;
                    continue;
                }
                TargetCheck.Result check = kernelNamed.contains(proposed)
                        ? new TargetCheck.Result(TargetCheck.Verdict.GROUNDED, "named after \"to\"")
                        : targets.check(words, action.evidence(), goalId, snapshot.documents(), recent,
                        type == ActionType.ADD_TASK ? withoutGoalName(action.text(), goalText(goalId, snapshot))
                        : null);
                if (!check.grounded() && !TASK_ACTIONS.contains(type) && type != ActionType.ADD_TASK
                        && type != ActionType.CANCEL_GOAL && namedByName(words, goalId, snapshot.documents())) {
                    // "Push the passport back a week" while the trip checklist has a task about the passport: only the
                    // passport goal's own name holds the words, so a change of the goal means it. Cancelling a whole
                    // goal still needs words that ground it.
                    check = new TargetCheck.Result(TargetCheck.Verdict.GROUNDED, "named by the goal's own name");
                }
                if (!check.grounded() && !words.equals(said) && TASK_ACTIONS.contains(type)
                        && groundedByAnotherPart(proposed, goalId, words, said, changeProposals, snapshot, recent)) {
                    // "T1 of the thesis is done, T2 is in progress": T2's part names its task by key and no goal,
                    // and another part of the sentence grounds that same goal.
                    check = new TargetCheck.Result(TargetCheck.Verdict.GROUNDED, "another part names the goal");
                }
                if (!check.grounded() && targets.namesOnly(words, goalId, snapshot.documents())) {
                    // The words name this goal and no other; words no goal uses keep them from grounding it: asked.
                    // Only words no goal uses weaken it ("stuck on the speaker, my friend hasn't replied"): the goal is
                    // not in doubt, so outside CONVERSE the change is done; it is asked about in CONVERSE.
                    log.info("weak {} on {}: {}", type, goalId.substring(0, 8), check.reason());
                    grounded = false;
                } else if (!check.grounded() && TASK_ACTIONS.contains(type) && action.task() != null
                        && namedTask(words, goalId, action.task(), snapshot)) {
                    // The words name this task's title and no other goal has such a task ("move the speaker to
                    // Friday" while another goal's text says "Friday"): the goal is not in doubt, also when it was
                    // just discussed; asked in CONVERSE.
                    log.info("task-named {} on {}: {}", type, goalId.substring(0, 8), check.reason());
                    grounded = false;
                } else if (!check.grounded() && (recent.size() == 1 && recent.contains(goalId)
                        || otherChangeGoals(proposed, changeProposals, snapshot).contains(goalId))
                        && TargetCheck.distinctiveOverlap(TargetCheck.withoutExcluded(words), snapshot.documents())
                        .getOrDefault(goalId, 0.0) > 0) {
                    // The words fit several goals and one of them is the goal just discussed ("the guest list" after
                    // a turn about the birthday party): asked about that one, never applied unasked.
                    log.info("weak {} on {} (just discussed): {}", type, goalId.substring(0, 8), check.reason());
                    grounded = false;
                    targetDoubt = whichGoal(words, check.reason(), snapshot.documents());
                } else if (!check.grounded()) {
                    // Only a goal the words name is ever offered: a match the words leave weak with words of other
                    // goals, or tied, is refused, naming the goals that hold the words, so a yes can never land on a
                    // goal the person did not name.
                    log.info("ungrounded {} on {}: {}", type, goalId.substring(0, 8), check.reason());
                    overruled = true;
                    if (type == ActionType.ADD_TASK && action.text() != null
                            && caller.may(ActionType.CREATE_GOAL, null)
                            && (!TargetCheck.namesAnyGoal(alone ? said : words, snapshot.documents())
                            || alone && taskRequest(said))) {
                        // "Remind me to call mom tomorrow" names no goal at all, and neither does the part of a long
                        // sentence that says "I need to pick up my suit": it is offered as a goal of its own.
                        Action asGoal = new Action(ActionType.CREATE_GOAL, null, null,
                                goalTitle(action.text(), action.dueDate() != null),
                                action.dueDate(), null, null, null, action.reason(), action.evidence());
                        toConfirm.add(asGoal);
                        questions.add("\"" + asGoal.text() + "\" fits none of your goals. Did you mean: "
                                + executor.describe(asGoal, null, null) + "?");
                        continue;
                    }
                    // "I don't need the unicorn anymore" when nothing is about a unicorn: said so, no goal is asked
                    // for.
                    Optional<String> nothing = nothingNamed(said, snapshot.documents());
                    executed.add(new Executed(type, false, nothing.isPresent() ? nothing.get()
                            : whichGoal(words, check.reason(), snapshot.documents()), null, null));
                    continue;
                }
            }
            if (type == ActionType.ADD_TASK && action.text() != null && !TargetCheck.unsaid(action.text(), said).isEmpty()) {
                grounded = false; // a task title with words the person did not say is the model's idea: asked first
            }
            String modelTitle = type == ActionType.ADD_TASK ? action.text() : null;
            Optional<String> need = modelTitle != null && alone ? needClause(goalWordsAsSaid(said))
                    .filter(n -> sharesMost(TargetCheck.stems(modelTitle), TargetCheck.stems(n))) : Optional.empty();
            if (need.isPresent()) {
                // "Next Tuesday is my wife's birthday, create me a task by this weekend to go and buy her a present"
                // aimed at a goal: the task is what the sentence needs, due when that ends with "by" and its date;
                // the date of what is going on is no part of it.
                LocalDate due = neededBy(need.get());
                action = withText(action, goalTitle(withPerson(need.get(), said), due != null));
                if (due != null && !due.isBefore(properties.today())) {
                    action = withDue(action, due);
                    dateNote = "";
                }
            } else if (modelTitle != null) {
                String thing = withoutGoAnd(withoutTaskWord(addedThing(modelTitle, goalText(goalId, snapshot))));
                if (!thing.equals(modelTitle)) {
                    // "Add sunscreen to the picnic" as a title is "Sunscreen", "Task for the picnic blanket" is
                    // "Picnic blanket".
                    action = withText(action, thing);
                }
                String kept = titleWithDates(action.text(), dateWords, goalText(goalId, snapshot), properties.today());
                if (!kept.equals(action.text())) {
                    // "hold off on buying chairs until December 4" titled "hold off on buying chairs": the date the
                    // title left out is put back, and the title is asked about.
                    action = withText(action, kept);
                    grounded = false;
                }
            }
            if (type == ActionType.ADD_TASK && action.text() != null) {
                if (!capitalFirst(action.text()).equals(action.text())) {
                    action = withText(action, capitalFirst(action.text()));
                }
            }
            if (GOAL_PLAIN.contains(type) && !ownPlain) {
                // A goal change is applied unasked only when every word of its own part of the sentence is plain for
                // it ("move the picnic to Friday"); "we could move the picnic to Friday" and "the picnic may move to
                // Friday" are asked (see PlainWords). A new task's title is the person's own words, whatever they are.
                grounded = false;
            }
            if (type == ActionType.CREATE_GOAL) {
                // A new goal is created unasked only when its words are judged plain like every change (see
                // #plainFor): one plain statement whose words the model's text repeats word for word. When the model's
                // text is not taken from the sentence, a sentence that asks only for this goal gives the question the
                // goal's own words.
                String stripped = goalWords(said, properties.today());
                boolean longWords = wordCount(stripped) > LONG_TITLE;
                Optional<GoalSplit> split = alone && newGoalTasks.isEmpty() ? goalThenTasks(said) : Optional.empty();
                if (split.isPresent()) {
                    // "I want to organise a surprise party in two weeks, I need to invite friends and order a cake":
                    // the goal is the words before what it takes, and each thing it takes comes with it as a task.
                    String goalPart = goalWords(split.get().goal(), properties.today());
                    action = withText(action, goalPart);
                    OptionalInt dateStart = ActionDates.endingDateStart(goalPart);
                    LocalDate due = action.dueDate() == null && dateStart.isPresent() && dateStart.getAsInt() > 0
                            ? ActionDates.read(goalPart, null, properties.today(), null, false).date() : null;
                    if (due != null && !due.isBefore(properties.today())) {
                        // The goal's own words end with its date ("... in two weeks"), what it takes follows it.
                        action = withDue(action, due);
                        dateNote = ActionDates.strictDeadline(goalPart, properties.today()) ? ""
                                : " (" + ActionDates.NO_MARKER + ")";
                    }
                    split.get().tasks().forEach(task -> newGoalTasks.add(new Action(ActionType.ADD_TASK, null, null,
                            task, null, null, null, null, said, null)));
                    grounded = false;
                } else if (alone && longWords && needClause(goalWordsAsSaid(said)).isEmpty()
                        && shortSaidTitle(action.text(), said)) {
                    // A long sentence titled by the model in a few of the person's own words keeps that title, never
                    // unasked in CONVERSE, unless the kernel reads what the sentence needs itself (see #needClause).
                    action = withText(action, withoutTrailingDate(action.text()));
                    grounded = false;
                } else {
                    if (alone && (!takenFrom(action.text(), said, properties.today())
                            || !sameWords(stripped, goalWordsAsSaid(said))
                            && sameWords(action.text(), goalWordsAsSaid(said))
                            || sameWords(action.text(), said) && !sameWords(stripped, said))) {
                        // A model text that repeats the sentence with its strict deadline becomes the goal's words
                        // without it, as when the model left the deadline out itself; a text that is the whole
                        // sentence loses the request to create it too ("new goal: finish the report by Friday").
                        action = withText(action, stripped);
                    }
                    if (alone && taskRequest(action.text())) {
                        // "Add a task to buy sunscreen" as the goal's text: the request for a task is no part of it.
                        action = withText(action, withoutTaskCommand(action.text()));
                    }
                    // Taken from the sentence, what is needed is read with its deadline kept, so the deadline stays
                    // the goal's ("..., create a task to buy her a present by this weekend").
                    String goalText = action.text();
                    Optional<String> needed = Optional.empty();
                    if (alone && (wordCount(goalText) > LONG_TITLE || needsTo(said))) {
                        needed = (sameWords(goalText, stripped) ? needClause(goalWordsAsSaid(said)) : needed)
                                .or(() -> needClause(goalText));
                    }
                    if (needed.isPresent()) {
                        // The whole sentence would be the title: what the person says they need is, never unasked
                        // in CONVERSE.
                        action = withText(action, needed.get());
                        grounded = false;
                        LocalDate due = neededBy(needed.get());
                        Matcher wrapperDay = WRAPPER_DAY.matcher(said);
                        if (due == null && action.dueDate() == null && wrapperDay.find()) {
                            // "I need a task for today, a checklist ...": the day the wrapper names.
                            due = DateExpressions.find(wrapperDay.group(1), properties.today()).orElse(null);
                        }
                        boolean fromContext = false;
                        if (due == null && action.dueDate() == null && needsTo(said)) {
                            // "We're having friends over on Saturday, I need to plan dinner": the one date of what is
                            // going on is when the thing to do is due.
                            due = contextDate(said, properties.today()).orElse(null);
                            fromContext = due != null;
                        }
                        if (due != null && !due.isBefore(properties.today())) {
                            // "... I need a checklist with what I need to pack by the end of the day": what is
                            // needed ends with "by" and its date, the deadline, whatever date another part names
                            // ("next Tuesday is my wife's birthday, ... buy her a present by this weekend").
                            action = withDue(action, due);
                            dateNote = fromContext ? " (" + ActionDates.NO_MARKER + ")" : "";
                        }
                    }
                }
                String titled = goalTitle(withoutTaskClause(withPerson(action.text(), said), newGoalTasks),
                        action.dueDate() != null);
                if (titled != null && !titled.equals(action.text())) {
                    action = withText(action, titled);
                }
                if (!ownPlain) {
                    grounded = false;
                }
            }
            if (type == ActionType.REOPEN_GOAL && goalId != null && ledger.load(goalId).filter(
                    s -> s.status() != GoalStatus.CANCELLED && s.status() != GoalStatus.COMPLETED).isPresent()) {
                // Nothing to bring back: said at once, not asked first.
                executed.add(new Executed(type, false, "\"" + goalText(goalId, snapshot) + "\" is still open", goalId,
                        null));
                continue;
            }
            if (type == ActionType.RETIRE_CAPABILITY || type == ActionType.KEEP_CAPABILITY) {
                // The capability is resolved before anything is asked or done, so the question and its receipt name
                // exactly the one that changes.
                String capability = lifecycleTarget(type, action.text());
                if (capability == null) {
                    executed.add(new Executed(type, false, type == ActionType.RETIRE_CAPABILITY
                            ? "retire \"" + action.text() + "\": no capability the system grew, nor one from an older "
                            + "version that waits to be kept, matches it; a built-in capability cannot be retired this "
                            + "way, stop its provider instead"
                            : "keep \"" + action.text() + "\": no grown or quarantined capability matches it",
                            null, null));
                    continue;
                }
                if (type == ActionType.RETIRE_CAPABILITY && !namesCapability(said, capability,
                        executor.capabilityName(capability))) {
                    // Retiring takes a capability out of every plan: only words that name it ask for that, never the
                    // model's label alone ("I don't need the unicorn anymore" read as retiring the packing list).
                    Optional<String> nothing = nothingNamed(said, snapshot.documents());
                    executed.add(new Executed(type, false, nothing.isPresent() ? nothing.get() : "retire " + capability
                            + ": your words do not name it; say \"retire " + capability + "\"", null, null));
                    continue;
                }
                if (type == ActionType.KEEP_CAPABILITY && !keepSaid(said, capability,
                        executor.capabilityName(capability))) {
                    // Keeping hands a capability its grant back or signs it again: only the person's own words ask
                    // for that, never the model's label alone ("how is the shopping list doing?").
                    executed.add(new Executed(type, false, "keep " + capability + ": your words do not ask to keep it; "
                            + "say \"keep " + capability + "\"", null, null));
                    continue;
                }
                action = withText(action, capability);
            }
            if ((type == ActionType.APPROVE_STEP || type == ActionType.REJECT_STEP) && approvalId == null) {
                executed.add(new Executed(type, false, snapshot.approvalIds().isEmpty() ? NOTHING_TO_APPROVE
                        : "tell which step you mean; say its id or its capability (\"what is waiting for approval?\" "
                        + "lists them)", null, null));
                continue;
            }
            if (type == ActionType.APPROVE_STEP && !said(APPROVAL_WORDS, said)) {
                // A step that waits for approval runs only when the person's words approve it.
                executed.add(new Executed(type, false, "approve a step: your words do not approve one; say \"approve\" "
                        + "with its id or its capability (\"what is waiting for approval?\" lists them)", null, null));
                continue;
            }
            if (type == ActionType.PAIR_DEVICE && !said(PAIRING_WORDS, said)) {
                // A pairing code lets a device act as the owner: only the person's own words ask for one.
                executed.add(new Executed(type, false, "pair a device: your words do not ask for that; say \"pair a "
                        + "new device\"", null, null));
                continue;
            }
            if (type == ActionType.RESUME && !RESUME_WORDS.matcher(said.toLowerCase(Locale.ROOT)).find()) {
                grounded = false; // only the person's own words restart automatic work, never the model's label alone
            }
            if (type == ActionType.REFINE_GOAL && !asksReplan(said, goalText(goalId, snapshot))) {
                grounded = false; // a goal is planned again only when the words ask for a new plan
            }
            if (type == ActionType.RESCHEDULE_GOAL && createLike(said)) {
                // "QA bake sale for the school, due next Friday" describes a goal with its deadline; it is not a
                // request to move the deadline of a goal it resembles.
                grounded = false;
            }
            String similarGoal = type == ActionType.CREATE_GOAL ? similarOpenGoal(action.text(), snapshot) : null;
            if (choice != null && TASK_ACTIONS.contains(type)) {
                TaskView task = choice.task();
                GoalState state = choice.state();
                if (choice.unsure()) {
                    grounded = false; // the words name another task than the model's, or point at none
                    targetDoubt = "tell which task you mean: your words do not name " + task.key() + " \""
                            + task.title() + "\" of \"" + state.text() + "\" for sure; say its key, for example \""
                            + task.key() + " of " + state.text() + "\"";
                }
                String problem = transitionProblem(action, task);
                if (problem != null) {
                    executed.add(new Executed(type, false, problem, goalId, null));
                    continue;
                }
                if (type == ActionType.UPDATE_TASK && action.dueDate() == null && action.hours() == null) {
                    // Nothing is left to change once the words give neither a date nor hours for this task: said at
                    // once, never asked ("Did you mean: set T6?" could only fail after a yes).
                    String why = resolved.numberNote().strip().replaceAll("^\\(|\\)$", "");
                    executed.add(new Executed(type, false, "change " + task.key() + " \"" + task.title() + "\": "
                            + (why.isEmpty() ? "your words give no new date and no hours for it" : why), goalId, null));
                    continue;
                }
                if (type != ActionType.REMOVE_TASK) {
                    // Applied unasked only when every word of the task's own part of the sentence is plain for this
                    // change ("T3 is done, it took 2 hours"); "I'll have T3 done tonight", "I did some of T3" and
                    // "T3 could get stuck" are asked (see PlainWords).
                    PlainWords.Target target = new PlainWords.Target(state.text(), goalId, task.title(), null);
                    if (type == ActionType.SET_TASK_STATUS && action.status() != TaskStatus.TODO
                            && task.status() == TaskStatus.BLOCKED && UNBLOCKED.matcher(own).find()) {
                        // "The venue isn't blocked anymore" takes the task back to to-do, whatever the model read.
                        action = new Action(type, action.goal(), action.task(), action.text(), action.dueDate(),
                                TaskStatus.TODO, null, action.approval(), action.reason(), action.evidence());
                        grounded = false;
                    }
                    if (!ownPlain) {
                        grounded = false;
                        Action asked = action;
                        Optional<TaskStatus> stated = type == ActionType.SET_TASK_STATUS
                                ? PlainWords.plainStatus(own, target, properties.today())
                                .filter(st -> st != asked.status()) : Optional.empty();
                        if (stated.isPresent() && mode != Conversation.Mode.CONVERSE) {
                            // Outside CONVERSE the status the words plainly state is the one done, with both named.
                            action = new Action(type, action.goal(), action.task(), action.text(), action.dueDate(),
                                    stated.get(), action.hours(), action.approval(), action.reason(),
                                    action.evidence());
                            dateNote += " (your words say " + LedgerRules.readable(stated.get())
                                    + "; the assistant read " + LedgerRules.readable(asked.status()) + ")";
                        } else if (stated.isPresent()) {
                            // The words plainly state another status than the model's ("T3 is done" read as in
                            // progress): neither is offered; the reply names both and the person says the one meant.
                            overruled = true;
                            executed.add(new Executed(type, false, "say the status you mean for " + task.key()
                                    + " \"" + task.title() + "\": I read " + LedgerRules.readable(stated.get())
                                    + ", the assistant read " + LedgerRules.readable(asked.status()), goalId, null));
                            continue;
                        }
                    }
                }
            }
            if ((type == ActionType.APPROVE_STEP || type == ActionType.REJECT_STEP) && approvalId != null
                    && !approvalGrounded(said, approvalId, snapshot, recent)) {
                grounded = false;
            }
            if (goalId != null) {
                touched.add(goalId);
            }
            if (similarGoal != null) {
                // "I need a packing list for the picnic" next to "Plan the picnic": the person may mean that goal.
                Action similarAsked = withIds(action, goalId, approvalId);
                toConfirm.add(similarAsked);
                notes.put(similarAsked, dateNote + "; it looks like your goal \"" + similarGoal + "\"");
                questions.add("You already have the goal \"" + similarGoal + "\", which looks similar. Create \""
                        + action.text() + "\"" + (action.dueDate() == null ? "" : " due " + action.dueDate())
                        + " anyway" + dateNote + "?");
                continue;
            }
            LocalDate newDeadline = action.dueDate();
            String moveNote = type == ActionType.RESCHEDULE_GOAL && goalId != null && newDeadline != null
                    ? ledger.load(goalId).map(g -> drasticMove(g, newDeadline, properties.today())).orElse(null)
                    : null;
            if (moveNote != null) {
                grounded = false; // a deadline moved a long way, or tasks landing on today, is asked about first
                dateNote += moveNote;
            }
            Optional<TaskView> duplicate = type == ActionType.ADD_TASK && grounded && goalId != null
                    ? similarTask(goalId, action.text()) : Optional.empty();
            if (duplicate.isPresent()) {
                Action duplicateAsked = withIds(action, goalId, approvalId);
                toConfirm.add(duplicateAsked);
                doubts.put(duplicateAsked, "add \"" + action.text() + "\": the goal already has "
                        + duplicate.get().key() + " \"" + duplicate.get().title() + "\", which looks the same");
                questions.add("This goal already has " + duplicate.get().key() + " \"" + duplicate.get().title()
                        + "\", which looks similar. Add \"" + action.text() + "\" anyway" + dateNote + "?");
                continue;
            }
            ConstitutionService.Ruling ruling = governed(type)
                    ? constitution.checkPerson(type, goalId, type == ActionType.CREATE_GOAL ? action.text() : null)
                    : ConstitutionService.Ruling.ALLOW;
            if (ruling.verdict() == ConstitutionService.Verdict.FORBID) {
                executed.add(new Executed(type, false, forbidden(ruling, executor.describe(action, goalId, approvalId)),
                        goalId, null));
                continue;
            }
            boolean stepUp = unusual.isPresent() && !type.readOnly();
            if (type.needsConfirmation() || !grounded || ruling.verdict() == ConstitutionService.Verdict.ASK_FIRST
                    || stepUp) {
                Action askedAction = withIds(action, goalId, approvalId);
                toConfirm.add(askedAction);
                notes.put(askedAction, dateNote);
                if (stepUp) {
                    // Unusual for this device, whatever else holds: never done without the owner's yes on a device.
                    unusualOnly.add(askedAction);
                }
                if (targetDoubt != null) {
                    doubts.put(askedAction, targetDoubt);
                } else if (ruling.verdict() == ConstitutionService.Verdict.ASK_FIRST) {
                    doubts.put(askedAction, "ask the owner first: " + ruling.explanation() + " says to ask before "
                            + executor.describe(action, goalId, approvalId));
                }
                String described = executor.describe(action, goalId, approvalId) + dateNote;
                if (stepUp && grounded && ruling.verdict() != ConstitutionService.Verdict.ASK_FIRST
                        && !type.needsConfirmation()) {
                    stepUps.add(capitalise(described) + "?");
                } else {
                    questions.add(!grounded ? "Did you mean: " + described + "?"
                            : ruling.verdict() == ConstitutionService.Verdict.ASK_FIRST
                            ? capitalise(ruling.explanation()) + " says to ask you first: " + described + "?"
                            : questionWithNote(executor.question(action, goalId, approvalId), dateNote));
                }
            } else if (judgedTurn && !type.readOnly()) {
                // Ready to apply once the whole sentence is known to be plain; its place among the outcomes is kept.
                ready.add(new Waiting(action, goalId, approvalId, dateNote, executed.size()));
                executed.add(null);
            } else {
                // A list read is decided by the words, whatever list the model picked: goals for a question about
                // goals, the agenda for one about tasks and days, the inbox only when the words speak of messages.
                boolean listRead = type == ActionType.LIST_GOALS || type == ActionType.SHOW_AGENDA
                        || type == ActionType.SHOW_INBOX && !INBOX_WORDS.matcher(said.toLowerCase(Locale.ROOT)).find();
                ActionType listAsked = type == ActionType.SHOW_INBOX ? ActionType.LIST_GOALS : type;
                String readWords = listRead ? questionFor(listAsked, questionParts, usedParts, said) : said;
                ActionType read = listRead ? listType(readWords, listAsked) : type;
                if (listRead) {
                    if (!listedTypes.add(read)) {
                        continue; // that list is already shown in this turn
                    }
                    listed = true;
                    if (read != type && !caller.may(read, null)) {
                        executed.add(new Executed(read, false, "not part of what you were invited to do", null,
                                null));
                        continue;
                    }
                }
                // "What's pending for the holiday?": a list question whose words name one goal shows that goal.
                Optional<String> namedGoal = listRead ? goalNamedInRead(readWords, snapshot)
                        .filter(g -> caller.may(ActionType.SHOW_GOAL, g) && shownGoals.add(g)) : Optional.empty();
                GoalState before = type.readOnly() || goalId == null ? null : ledger.load(goalId).orElse(null);
                Executed result = namedGoal.isPresent() ? executor.execute(new Action(ActionType.SHOW_GOAL, null, null,
                        null, null, null, null, null, null, null), namedGoal.get(), null, principal, shown)
                        : listRead ? fallbackRead(read, agendaWords(read, readWords, questionParts), principal,
                        shown) : executor.execute(action, goalId, approvalId, principal, shown);
                if (result.ok() && !dateNote.isEmpty()) {
                    result = new Executed(result.type(), true, result.message() + dateNote, result.goalId(),
                            result.runId());
                }
                executed.add(result);
                if (result.ok()) {
                    done.add(withIds(action, result.goalId() != null ? result.goalId() : goalId, approvalId));
                    if (!type.readOnly()) {
                        made.add(change(action, goalId, before, result));
                    }
                }
                if (result.goalId() != null) {
                    touched.add(result.goalId());
                } else if (listRead && result.ok()) {
                    onlyGoalShown(shown).ifPresent(touched::add);
                }
            }
        }
        // The changes that wait are applied only when every word of the sentence lies in a part judged plain for one
        // of its changes, or is a function or courtesy word, and nothing in it makes them wait on something, take them
        // back or correct them; otherwise each is asked, with the kernel's reading.
        boolean wholePlain = PlainWords.settled(said) && PlainWords.wholePlain(said, plainParts);
        for (Waiting w : ready) {
            if (wholePlain) {
                GoalState before = w.goalId() == null ? null : ledger.load(w.goalId()).orElse(null);
                Executed result = executor.execute(w.action(), w.goalId(), w.approvalId(), principal, shown);
                if (result.ok() && !w.dateNote().isEmpty()) {
                    result = new Executed(result.type(), true, result.message() + w.dateNote(), result.goalId(),
                            result.runId());
                }
                executed.set(w.at(), result);
                if (result.ok()) {
                    done.add(withIds(w.action(), result.goalId() != null ? result.goalId() : w.goalId(),
                            w.approvalId()));
                    made.add(change(w.action(), w.goalId(), before, result));
                }
                if (result.goalId() != null) {
                    touched.add(result.goalId());
                }
            } else {
                toConfirm.add(withIds(w.action(), w.goalId(), w.approvalId()));
                questions.add("Did you mean: " + executor.describe(w.action(), w.goalId(), w.approvalId())
                        + w.dateNote() + "?");
            }
        }
        executed.removeIf(Objects::isNull);
        Optional<String> uncovered = uncoveredKeys(said, proposals, executed);
        if (uncovered.isPresent()) {
            // The words name tasks no proposed change covers ("T4 ... T10 are all done" read as five changes): the
            // reply says which were not changed, so nothing is taken for done that was not.
            overruled = true;
            executed.add(new Executed(changeProposals.stream().filter(a -> TASK_ACTIONS.contains(a.type()))
                    .map(Action::type).findFirst().orElse(ActionType.SET_TASK_STATUS), false, uncovered.get(), null,
                    null));
        }
        settleFulfilled(done, principal);
        boolean creates = proposals.stream().anyMatch(a -> a.type() == ActionType.CREATE_GOAL);
        boolean changes = proposals.stream().anyMatch(a -> !a.type().readOnly());
        if (!changes && questionParts.size() > 1) {
            // "List my goals and what should I do today?" read as one question: each list asked for is shown.
            for (String part : questionParts) {
                ActionType asked = listType(part, null);
                if ((asked == ActionType.LIST_GOALS || asked == ActionType.SHOW_AGENDA) && caller.may(asked, null)
                        && listedTypes.add(asked)) {
                    executed.add(fallbackRead(asked, agendaWords(asked, part, questionParts), principal, shown));
                }
            }
        }
        if (interpretation.actions().isEmpty() && (YES_OR_NO.matcher(said).matches() || courtesy(said)
                && Arrays.stream(TargetCheck.normalise(said).split(" ")).anyMatch(AGREEING::contains))) {
            // A bare yes, no or "ok" while several messages wait, which the assistant could not tie to one of them.
            overruled = true;
            executed.add(new Executed(ActionType.ANSWER_MESSAGE, false, "answer a message with a bare yes or no: say "
                    + "which message you mean, or run the CLI without a sentence to go through them", null, null));
        }
        Optional<String> repeated = creates || !changes || question(said) ? Optional.empty()
                : repeatedGoal(said, snapshot.documents()).filter(this::open);
        if (repeated.isPresent()) {
            // The sentence is an open goal's own words, as when creating it again; the model proposed another change,
            // so the reply says that no second goal was made. Showing that goal is what its name asks for.
            overruled = true;
            executed.add(0, new Executed(ActionType.CREATE_GOAL, false, "create \""
                    + snapshot.documents().get(repeated.get()).getFirst() + "\" again: it is already one of your goals, "
                    + "so nothing new was created", repeated.get(), null));
        }
        if (!changes && (goalRequest(said) || taskRequest(said)) && caller.may(ActionType.CREATE_GOAL, null)) {
            // The words ask for a new goal and the model proposed no change: the goal in the person's words is
            // offered (in CONVERSE; the modes that act create it below).
            Action offer = offeredGoal(said);
            String similar = similarOpenGoal(offer.text(), snapshot);
            toConfirm.add(offer);
            questions.add(similar != null ? "You already have the goal \"" + similar + "\", which looks similar. "
                    + "Create \"" + offer.text() + "\"" + (offer.dueDate() == null ? "" : " due " + offer.dueDate())
                    + " anyway?" : "Did you mean: " + executor.describe(offer, null, null)
                    + offeredDateNote(said, offer) + "?");
            overruled = true;
        }
        if (!changes && toConfirm.isEmpty() && CHANGE_REQUEST.matcher(said).lookingAt()
                && YESTERDAY.matcher(said).find()) {
            // "Move buying flowers to yesterday", which no date reading takes: the day has passed.
            overruled = true;
            executed.add(new Executed(ActionType.UPDATE_TASK, false, "move it to yesterday: that day has already "
                    + "passed; say a day from today on", null, null));
        }
        Optional<String> unknown = changes || !toConfirm.isEmpty() || question(said) ? Optional.empty()
                : nothingNamed(said, snapshot.documents());
        if (unknown.isPresent()) {
            // "Drop the unicorn task" when nothing is about a unicorn: said so, with the closest goals.
            overruled = true;
            executed.add(new Executed(ActionType.REMOVE_TASK, false, unknown.get(), null, null));
        }
        boolean taskTried = proposals.stream().anyMatch(a -> TASK_ACTIONS.contains(a.type())
                || a.type() == ActionType.ADD_TASK);
        if (!taskTried && toConfirm.isEmpty()
                && executed.stream().noneMatch(e -> e != null && e.ok() && !e.type().readOnly()) && !question(said)) {
            Optional<Executed> news = taskNews(said, snapshot, recent, caller, toConfirm, questions);
            boolean refused = executed.stream().anyMatch(e -> e != null && !e.ok());
            if (!toConfirm.isEmpty() || news.isPresent() && (!refused
                    || news.get().message().startsWith("tell which task"))) {
                // "I'm working on inviting friends", read by the model as something else: the task its words name is
                // offered the status they state, or the tasks they may name are listed.
                executed.removeIf(e -> e != null && !e.ok() && (e.type() == ActionType.ANSWER_MESSAGE
                        || e.type() == ActionType.ADD_RULE || e.type() == ActionType.REQUEST_CAPABILITY));
                news.ifPresent(executed::add);
                overruled = true;
            }
        }
        Optional<Action> notDone = !toConfirm.isEmpty()
                || executed.stream().anyMatch(e -> e != null && e.ok() && !e.type().readOnly())
                ? Optional.empty() : reopened(said, snapshot).filter(a -> caller.may(a.type(), a.goal()));
        if (notDone.isPresent()) {
            // "I didn't finish the napkins" about a task marked done: taking it back to to-do is offered, whatever
            // else the model read into it.
            executed.removeIf(e -> e != null && !e.ok() && (e.type() == ActionType.ANSWER_MESSAGE
                    || e.type() == ActionType.ADD_RULE || e.type() == ActionType.REQUEST_CAPABILITY));
            toConfirm.add(notDone.get());
            questions.add("Did you mean: " + executor.describe(notDone.get(), notDone.get().goal(), null) + "?");
            overruled = true;
        }
        // A turn without actions whose reply states counts, dates, ids, quotes or names a goal states facts nobody
        // checked: a question is answered from the ledger instead, anything else gets a line that says where to look.
        boolean unchecked = interpretation.actions().isEmpty()
                && unverifiable(interpretation.reply(), snapshot.documents());
        // A question about the person's data the model answered without actions ("is my thesis due on Friday?" with
        // "Yes, it is.") is answered from the ledger too, whatever the reply says.
        boolean dataAsked = interpretation.actions().isEmpty() && dataQuestion(said, snapshot.documents());
        if ((unchecked || dataAsked) && question(said)) {
            executed.add(kernelRead(said, snapshot, caller, principal, shown));
        }
        if (!newGoalTasks.isEmpty()) {
            askNewGoalTasks(newGoalTasks, toConfirm, questions, executed);
        }
        if (mode != Conversation.Mode.CONVERSE && !toConfirm.isEmpty()) {
            // ASSIST and DECIDE act instead of asking: a change whose goal or task is in doubt, or that the owner's
            // rule holds for the owner's yes, is rejected with the reason (the message names the candidates); a
            // security change, and one unusual for this device, is asked about in ASSIST and rejected in DECIDE;
            // every other change is done as a yes would do it, with the note on how its words were read.
            overruled = true;
            List<Action> apply = new ArrayList<>();
            List<Action> stillAsked = new ArrayList<>();
            for (Action a : toConfirm) {
                String doubt = doubts.get(a);
                if (doubt != null) {
                    executed.add(new Executed(a.type(), false, doubt, a.goal(), null));
                } else if (!caller.owner() && !caller.may(a.type(), a.goal())) {
                    executed.add(new Executed(a.type(), false, "not part of what you were invited to do", a.goal(),
                            null));
                } else if (Conversation.SECURITY_ACTIONS.contains(a.type()) || unusualOnly.contains(a)) {
                    if (mode == Conversation.Mode.ASSIST) {
                        stillAsked.add(a);
                    } else {
                        executed.add(new Executed(a.type(), false, "this needs the owner's yes on a device: "
                                + (unusualOnly.contains(a) ? unusual.orElse("unusual for this device") + "; " : "")
                                + executor.describe(a, a.goal(), a.approval()), a.goal(), null));
                    }
                } else {
                    apply.add(a);
                }
            }
            List<Action> applied = new ArrayList<>();
            Conversation.PendingConfirmation again = applyConfirmed(new Conversation.PendingConfirmation("decided", "",
                    apply, designed, drafted), principal, shown, session, executed, applied, made, notes);
            settleFulfilled(applied, principal);
            executed.stream().filter(e -> e.ok() && e.goalId() != null).forEach(e -> touched.add(e.goalId()));
            toConfirm.clear();
            questions.clear();
            stepUps.clear();
            List<String> unusualChanges = new ArrayList<>();
            for (Action a : stillAsked) {
                toConfirm.add(a);
                if (unusualOnly.contains(a) && unusual.isPresent()) {
                    unusualChanges.add(capitalise(executor.describe(a, a.goal(), a.approval())) + "?");
                } else {
                    questions.add(capitalise(executor.question(a, a.goal(), a.approval())));
                }
            }
            if (!unusualChanges.isEmpty()) {
                // The reason once, then each change on a line (see #stepUpQuestion).
                questions.add(0, stepUpQuestion(unusual.get(), unusualChanges));
            }
            if (again != null) {
                executed.add(new Executed(ActionType.ANSWER_MESSAGE, false, again.question(), null, null));
            }
        }
        Conversation.PendingConfirmation confirmation = null;
        if (unusual.isPresent() && !stepUps.isEmpty()) {
            questions.add(0, stepUpQuestion(unusual.get(), stepUps));
        }
        if (!toConfirm.isEmpty()) {
            confirmation = new Conversation.PendingConfirmation(UUID.randomUUID().toString(),
                    String.join("\n", questions), toConfirm, designed, drafted);
            session.await(confirmation, caller.keyId());
        }
        if (!caller.owner()) {
            visibleOnly(shown, caller);
        }
        // When an action failed, was asked about, or was read differently, the model's reply may describe what did
        // not happen; the kernel's own lines speak instead. After a read the data itself answers, so the model's
        // description of it (which may count or date things wrongly) is never shown. After a change the kernel's
        // lines ("done: ...") name what changed, and the only reply shown with them is one from a fixed list (see
        // #fixedReply): "Done, and I also moved the next task up." is not shown.
        boolean readRan = executed.stream().anyMatch(e -> e.type().readOnly());
        boolean quiet = !overruled && !unchecked && !readRan && toConfirm.isEmpty()
                && executed.stream().allMatch(Executed::ok);
        String text;
        if (quiet && !executed.isEmpty()) {
            text = fixedReply(interpretation.reply()).orElse(null);
        } else if (quiet) {
            // Nothing was changed, whatever the reply says ("Done! I've marked everything as complete.", "Yes, I
            // did."): the person reads that first. Only a reply from a fixed list of courtesies to a courtesy or a
            // greeting alone stands without it ("You're welcome!", see #courtesyReply).
            String reply = interpretation.reply();
            Optional<String> courteous = interpretation.actions().isEmpty() && courtesy(said) ? courtesyReply(reply)
                    : Optional.empty();
            text = courteous.orElseGet(() -> reply == null || reply.isBlank() ? NOTHING_CHANGED
                    : NOTHING_CHANGED + " " + reply);
        } else {
            text = unchecked && executed.isEmpty() && toConfirm.isEmpty() ? UNCHECKED : null;
        }
        if (!touched.isEmpty() || readRan) {
            // A turn that changed and showed nothing (a refusal, a question) keeps the goals in focus.
            session.focusOn(touched);
        }
        session.changed(made);
        made.stream().map(Session.Change::made).filter(ConversationService::datedChange).reduce((a, b) -> b)
                .ifPresent(session::dated);
        toConfirm.stream().filter(ConversationService::datedChange).reduce((a, b) -> b).ifPresent(session::dated);
        // The kernel's line that replaces an unchecked reply is not the assistant's turn: kept in the history, the
        // model would repeat it.
        session.remember(said, UNCHECKED.equals(text) ? "" : text != null ? text : spoken(executed, questions));
        log.info("\"{}\" -> {}", said, interpretation.actions().stream().map(a -> a.type().name()).toList());
        return reply(text, withoutRepeatedRefusals(executed), confirmation, shown);
    }

    /** "Remind me tomorrow to pay the rent": a reminder whose day is said before what to do. */
    private static final Pattern REMIND_FIRST = Pattern.compile("(?i)^(?<lead>.*?\\bremind\\s+me\\s+)(?<date>(?:on\\s+"
            + "|by\\s+|next\\s+|this\\s+|in\\s+)?(?:today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday"
            + "|saturday|sunday|(?:a|one|two|three|four|five|six|\\d+)\\s+(?:days?|weeks?|months?)|week|month"
            + "|the\\s+\\d{1,2}(?:st|nd|rd|th)?|(?:the\\s+)?end\\s+of\\s+(?:the\\s+)?(?:day|week|month))"
            + "(?:\\s+(?:morning|afternoon|evening|night))?)\\s+(?<rest>to\\s+.+?)\\s*(?<end>[.!]*)$");
    /** "Until the end of the day", "till tomorrow": the day by which something is done. */
    private static final Pattern UNTIL_DEADLINE = Pattern.compile("(?i)\\b(?:until|till|til)\\s+(?:at\\s+)?"
            + "(?=(?:the\\s+)?end\\s+of\\b|eod\\b|today\\b|tonight\\b|tomorrow\\b|close\\s+of\\s+business\\b|cob\\b)");
    /** Words of waiting, where "until tomorrow" says how long and is no deadline: "I'm blocked until tomorrow". */
    private static final Pattern HOLDING = Pattern.compile("(?i)\\b(?:blocked|stuck|waiting|wait|busy|away|off|hold"
            + "|paused?|postpone\\w*|delay\\w*|can'?t|cannot|won'?t|not)\\b");
    private static final String DAY = "(?:today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday"
            + "|sunday|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun|\\d{1,2}(?:st|nd|rd|th)|\\d{1,2}[./-]\\d{1,2}"
            + "(?:[./-]\\d{2,4})?|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?\\s+\\d{1,2}"
            + "(?:st|nd|rd|th)?)";
    private static final String CLOCK_TIME = "(?:\\d{1,2}(?::\\d{2})?\\s*(?:am|pm|a\\.m\\.|p\\.m\\.)|\\d{1,2}:\\d{2}"
            + "|noon|midday|midnight)(?![\\w:])";
    /** A time of day after a day: "Friday 5pm", "tomorrow at 9:30", "tomorrow morning". The day is kept. */
    private static final Pattern CLOCK = Pattern.compile("(?i)\\b(?<day>" + DAY + ")(?:,?\\s+(?:at\\s+|around\\s+"
            + "|by\\s+|before\\s+)?" + CLOCK_TIME + "|\\s+(?:in\\s+the\\s+)?(?:morning|afternoon|evening|night)\\b"
            + "(?=\\s*(?:$|[,.;:!?]|(?:and|so|then|to|i|we|please)\\b)))");
    /** A time of day alone after "by" or "before": "finish the report by 5pm" is due today. */
    private static final Pattern CLOCK_ALONE = Pattern.compile("(?i)\\b(?<marker>by|before|until|till)\\s+"
            + CLOCK_TIME);
    /** A time of day alone after "at": "call the plumber at 5pm". */
    private static final Pattern CLOCK_AT = Pattern.compile("(?i),?\\s+(?:at|around)\\s+" + CLOCK_TIME);

    /**
     * The sentence with its dates said the way the kernel reads them: a reminder's day moves to the end ("remind me
     * tomorrow to pay the rent" is "remind me to pay the rent tomorrow"), "until the end of the day" and "till
     * tomorrow" are deadlines ("by"), unless the sentence speaks of waiting ("blocked until tomorrow"), and times of
     * day are left out, since only days are kept ("by Friday 5pm" is "by Friday", "by 5pm" is "by today", "tomorrow
     * morning" is "tomorrow"). A day's name typed with a slip or a shorthand is read as the day (see
     * {@link #withDayNames}), and "EOD" or "COB" as the end of the day. A deadline said as a clause of its own after
     * the goal ("..., deadline to be end of the day", "Organize garage. Deadline end of month.") is said with "by",
     * "the upcoming weekend" is "this weekend", and "pls" or "plz" is "please".
     */
    static String spokenDates(String said) {
        if (said == null) {
            return null;
        }
        String typed = END_OF_DAY_SHORT.matcher(withDayNames(said)).replaceAll("the end of the day");
        typed = DEADLINE_CLAUSE.matcher(SHORT_PLEASE.matcher(typed).replaceAll("please")).replaceAll(" by ");
        typed = UPCOMING_WEEKEND.matcher(typed).replaceAll("this weekend");
        Matcher remind = REMIND_FIRST.matcher(typed);
        String t = remind.matches() ? remind.group("lead") + remind.group("rest") + " " + remind.group("date")
                + remind.group("end") : typed;
        if (!HOLDING.matcher(t).find()) {
            t = UNTIL_DEADLINE.matcher(t).replaceAll("by ");
        }
        t = CLOCK.matcher(t).replaceAll("${day}");
        t = CLOCK_ALONE.matcher(t).replaceAll("${marker} today");
        return CLOCK_AT.matcher(t).replaceAll("");
    }

    /** "(The) upcoming weekend", "the coming weekend": this weekend. */
    private static final Pattern UPCOMING_WEEKEND = Pattern.compile("(?i)\\b(?:the\\s+)?(?:up)?coming\\s+weekend\\b");
    /** "pls", "plz": "please". */
    private static final Pattern SHORT_PLEASE = Pattern.compile("(?i)\\b(?:pls|plz)\\b");
    /**
     * A deadline said as the last clause, after punctuation, or "with a deadline of", before a date: "..., deadline to
     * be end of the day", ". Deadline: Friday", "with a deadline of December 3". "The report deadline moved to Monday"
     * and "..., the deadline is Friday, so move it" say no such clause.
     */
    private static final Pattern DEADLINE_CLAUSE = Pattern.compile("(?i)(?:\\s*[,;.]\\s*(?:and\\s+)?(?:the\\s+|a\\s+)?"
            + "|\\s+with\\s+(?:a|the)\\s+)deadline\\s*"
            + "(?:(?:(?:is|will\\s+be|should\\s+be|to\\s+be|being|of|on|at)\\s+)+|[:-]\\s*)?"
            + "(?=(?:the\\s+)?(?:end|today|tonight|tomorrow|this|next|coming|mon|tue|wed|thu|fri|sat|sun|jan"
            + "|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|\\d))(?=[^,;!?]*[.!?]?\\s*$)");
    /** "EOD", "COB": the end of the working day. */
    private static final Pattern END_OF_DAY_SHORT = Pattern.compile("(?i)\\b(?:eod|cob|e\\.o\\.d\\.?)(?![\\w.])");
    /** Days' names a person may mistype; "may", "march" and the short months are words too, so they never count. */
    private static final List<String> DAY_NAMES = List.of("today", "tonight", "tomorrow", "monday", "tuesday",
            "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "april", "august",
            "september", "october", "november", "december", "weekend");
    /** Shorthands for days: "tmrw", "2moro", "tonite". */
    private static final Map<String, String> DAY_SHORTHANDS = Map.ofEntries(Map.entry("tmrw", "tomorrow"),
            Map.entry("tmr", "tomorrow"), Map.entry("tmw", "tomorrow"), Map.entry("2moro", "tomorrow"),
            Map.entry("2morrow", "tomorrow"), Map.entry("tomoz", "tomorrow"), Map.entry("tonite", "tonight"),
            Map.entry("2nite", "tonight"), Map.entry("2day", "today"));
    /** Words one slip away from a day's name that mean something else. */
    private static final Set<String> NEAR_DAY_WORDS = Set.of("sundae", "sundaes", "sundry", "toddy", "mondo",
            "fridge", "augusts");
    private static final Pattern WORD_TOKEN = Pattern.compile("\\b[A-Za-z2]{3,}\\b");

    /**
     * The words with each mistyped day's name read as that day ("tomorow", "wensday", "saterday", "febuary"): a word
     * of five letters or more one slip away from a day's name (two for names of eight letters or more), unless it is
     * that name with an "s" ("Tuesdays") or a word of its own ("sundae"); and shorthands ("tmrw", "tonite") as the
     * day they stand for.
     */
    static String withDayNames(String said) {
        Matcher m = WORD_TOKEN.matcher(said);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String word = m.group();
            String lower = word.toLowerCase(Locale.ROOT);
            String day = DAY_SHORTHANDS.get(lower);
            if (day == null && lower.length() >= 5 && lower.chars().allMatch(Character::isLetter)
                    && !DAY_NAMES.contains(lower) && !NEAR_DAY_WORDS.contains(lower)) {
                day = DAY_NAMES.stream().filter(name -> !lower.equals(name + "s")
                        && slips(lower, name) <= (name.length() >= 8 ? 2 : 1)).findFirst().orElse(null);
            }
            String kept = day == null ? word : Character.isUpperCase(word.charAt(0))
                    ? Character.toUpperCase(day.charAt(0)) + day.substring(1) : day;
            m.appendReplacement(out, Matcher.quoteReplacement(kept));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Typing slips between two words: letters left out, added, changed or swapped with the next one. */
    static int slips(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 2) {
            return 3;
        }
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** Said to a sentence in another language. */
    static final String ENGLISH_ONLY = "I only understand English for now. Please say it in English.";
    private static final Pattern NON_ASCII_LETTER = Pattern.compile("[\\p{L}&&[^\\p{ASCII}]]");

    /**
     * The kernel's answer to a sentence in another language: of three words or more, at least half hold letters
     * outside English ("planifică o cină vineri"), or every word does ("привет"); a name with an accent in an
     * English sentence ("book Café Rouge", "Zoë's party") is no such sentence. Empty otherwise.
     */
    static Optional<String> notEnglish(String said) {
        if (said == null || said.isBlank()) {
            return Optional.empty();
        }
        String[] words = said.strip().split("\\s+");
        long foreign = Arrays.stream(words).filter(w -> NON_ASCII_LETTER.matcher(w).find()).count();
        boolean other = foreign > 0 && (words.length >= 3 && foreign * 2 >= words.length || foreign == words.length);
        return other ? Optional.of(ENGLISH_ONLY) : Optional.empty();
    }

    /** A question about today's date or the time: "what time is it?", "what day is it today?". */
    private static final Pattern DATE_QUESTION = Pattern.compile("(?i)^\\s*(?:what(?:'s|\\s+is)\\s+(?:the\\s+)?(?:time"
            + "|date|day)(?:\\s+(?:is\\s+it|today|now|right\\s+now))?|what\\s+(?:time|day|date)\\s+is\\s+(?:it|today)"
            + "(?:\\s+today)?|which\\s+day\\s+is\\s+(?:it|today)|what(?:'s|\\s+is)\\s+today(?:'s\\s+date)?"
            + "|what\\s+day\\s+of\\s+the\\s+week\\s+is\\s+it)\\s*\\??\\s*$");

    /** The kernel's answer to a question about today's date or the time; empty for any other sentence. */
    static Optional<String> dateAnswer(String said, LocalDate today) {
        if (said == null || today == null || !DATE_QUESTION.matcher(said).matches()) {
            return Optional.empty();
        }
        return Optional.of("Today is " + today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + ", "
                + today + ". I keep dates, not times of day.");
    }

    /** "Undo that", "undo the last change", "revert it", "take that back", "put it back". */
    private static final Pattern UNDO = Pattern.compile("(?i)^\\s*(?:(?:please|ok|okay|oops|sorry|no)[,!.]?\\s+)?"
            + "(?:undo|revert|reverse|roll\\s*back|take\\s+(?:that|it)\\s+back|put\\s+it\\s+back)(?:\\s+(?:that|it|this"
            + "|everything|all\\s+that|the\\s+last(?:\\s+(?:change|changes|thing|one|step|action))?"
            + "|my\\s+last\\s+(?:change|action)|what\\s+you\\s+(?:just\\s+)?did|the\\s+changes?))?"
            + "(?:\\s+(?:please|the\\s+way\\s+it\\s+was|as\\s+it\\s+was))?\\s*[.!]*\\s*$");
    /** "What did you change?", "what did you just do?", "what changed?", "what have you done?". */
    private static final Pattern WHAT_CHANGED = Pattern.compile("(?i)^\\s*(?:so\\s+)?what\\s+(?:did\\s+you\\s+"
            + "(?:just\\s+)?(?:change|do|update|modify)|have\\s+you\\s+(?:just\\s+)?(?:changed|done|updated)"
            + "|(?:was|has\\s+been|got)\\s+changed|changed|did\\s+that\\s+(?:change|do))"
            + "(?:\\s+(?:just\\s+now|now|exactly|then))?\\s*\\??\\s*$");
    /** A correction of the last date: "no, I meant Saturday", "sorry, make it the 28th", "actually Sunday". */
    private static final Pattern CORRECTION = Pattern.compile("(?i)^\\s*(?:(?:no|nope|sorry|oops|actually|wait|oh)\\b"
            + "[,!.]?\\s*)+(?:i\\s+(?:meant|mean|said)\\s+|make\\s+it\\s+|it\\s+should\\s+be\\s+"
            + "|change\\s+it\\s+to\\s+)?(?<rest>.+?)\\s*[.!]*$"
            + "|^\\s*(?:i\\s+meant|i\\s+mean|make\\s+it|it\\s+should\\s+be|change\\s+it\\s+to)\\s+"
            + "(?<rest2>.+?)\\s*[.!]*$");
    static final String NOTHING_TO_UNDO = "There is nothing to undo: I made no change in this conversation in the last "
            + "two hours.";
    static final String NOTHING_CHANGED_YET = "I made no change in this conversation in the last two hours.";

    /**
     * The kernel's answer from what this conversation did: "undo that" takes back the last changes (see
     * {@link #undo}); "what did you change?" lists them; a correction of a date ("no, I meant Saturday") makes the last
     * change with a date, made or asked about in the last two turns, with the new date instead. In
     * {@link Conversation.Mode#CONVERSE} both are asked about first. Empty for every other sentence, and for a
     * correction when no such change is remembered (the model reads it then).
     */
    private Optional<Conversation.Reply> fromMemory(String said, Session session, SecurityService.Caller caller,
                                                    Conversation.Mode mode, String principal) {
        if (UNDO.matcher(said).matches()) {
            return Optional.of(undo(said, session, caller, mode, principal));
        }
        if (WHAT_CHANGED.matcher(said).matches()) {
            List<Session.Change> last = session.lastChanges();
            String text = last.isEmpty() ? NOTHING_CHANGED_YET : "The last change in this conversation: "
                    + String.join("; ", last.stream().map(Session.Change::done).toList()) + ".";
            session.remember(said, text);
            return Optional.of(Conversation.Reply.text(text));
        }
        Optional<LocalDate> corrected = correctedDate(said, properties.today());
        Action last = session.lastDated();
        if (corrected.isEmpty() || last == null || !caller.may(last.type(), last.goal())) {
            return Optional.empty();
        }
        if (corrected.get().isBefore(properties.today())) {
            String text = "Nothing was changed: " + corrected.get() + " has already passed.";
            session.remember(said, text);
            return Optional.of(Conversation.Reply.text(text));
        }
        Action fixed = withDue(last, corrected.get());
        if (mode != Conversation.Mode.CONVERSE) {
            session.dated(fixed);
            return Optional.of(applied(said, List.of(fixed), List.of(), session, principal));
        }
        String question = "Did you mean: " + executor.describe(fixed, fixed.goal(), null) + "?";
        Conversation.PendingConfirmation confirmation = new Conversation.PendingConfirmation(
                UUID.randomUUID().toString(), question, List.of(fixed), null, null);
        session.await(confirmation, caller.keyId());
        session.dated(fixed);
        session.remember(said, question);
        return Optional.of(reply(null, List.of(), confirmation, new ActionExecutor.Shown()));
    }

    /**
     * The date a correction names, when it names nothing else: "no, I meant Saturday", "sorry, the 28th, not the
     * 27th". Empty for any other sentence ("no, cancel the picnic").
     */
    static Optional<LocalDate> correctedDate(String said, LocalDate today) {
        Matcher m = CORRECTION.matcher(said == null ? "" : said);
        if (!m.matches()) {
            return Optional.empty();
        }
        String rest = (m.group("rest") != null ? m.group("rest") : m.group("rest2"))
                .replaceAll("(?i)\\s*,?\\s*\\b(?:not|instead\\s+of|rather\\s+than)\\b.*$", "").strip();
        List<DateExpressions.Span> spans = DateExpressions.datePhraseSpans(rest);
        if (spans.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder left = new StringBuilder(rest);
        for (DateExpressions.Span span : spans.reversed()) {
            left.delete(span.start(), span.end());
        }
        String other = left.toString().toLowerCase(Locale.ROOT)
                .replaceAll("\\b(?:on|to|by|for|the|it|instead|please|then|that|this|one|day|date)\\b|[^a-z0-9]", "");
        return other.isEmpty() ? DateExpressions.find(rest, today) : Optional.empty();
    }

    /**
     * "Undo that": the changes the last turn or answer made, taken back in reverse order (asked about first in
     * {@link Conversation.Mode#CONVERSE}); a change that cannot be taken back is said with the reason.
     */
    private Conversation.Reply undo(String said, Session session, SecurityService.Caller caller,
                                    Conversation.Mode mode, String principal) {
        List<Session.Change> last = session.lastChanges();
        if (last.isEmpty()) {
            session.remember(said, NOTHING_TO_UNDO);
            return Conversation.Reply.text(NOTHING_TO_UNDO);
        }
        List<Action> undo = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<Executed> refused = new ArrayList<>();
        for (Session.Change c : last.reversed()) {
            String note = c.undo() == null ? c.done() + " cannot be undone: " + c.why()
                    : !caller.may(c.undo().type(), c.undo().goal())
                    ? c.done() + " cannot be undone: not part of what you were invited to do" : null;
            if (note != null) {
                notes.add(note);
                refused.add(new Executed(c.made().type(), false, note, c.made().goal(), null));
            } else {
                undo.add(c.undo());
                lines.add(executor.describe(c.undo(), c.undo().goal(), null));
            }
        }
        if (mode != Conversation.Mode.CONVERSE && !undo.isEmpty()) {
            return applied(said, undo, refused, session, principal);
        }
        if (undo.isEmpty()) {
            String text = "Nothing was changed: " + String.join("; ", notes) + ".";
            session.remember(said, text);
            return Conversation.Reply.text(text);
        }
        String question = "To undo the last change: " + String.join("; ", lines) + "?"
                + (notes.isEmpty() ? "" : " (" + String.join("; ", notes) + ")");
        Conversation.PendingConfirmation confirmation = new Conversation.PendingConfirmation(
                UUID.randomUUID().toString(), question, undo, null, null);
        session.await(confirmation, caller.keyId());
        session.remember(said, question);
        return reply(null, List.of(), confirmation, new ActionExecutor.Shown());
    }

    /**
     * Does {@code actions} at once, for a sentence answered from the conversation's memory outside
     * {@link Conversation.Mode#CONVERSE} ("undo that", "no, I meant Saturday"), and says what was done and what was
     * not ({@code refused}).
     */
    private Conversation.Reply applied(String said, List<Action> actions, List<Executed> refused, Session session,
                                       String principal) {
        ActionExecutor.Shown shown = new ActionExecutor.Shown();
        List<Executed> executed = new ArrayList<>(refused);
        List<Action> done = new ArrayList<>();
        List<Session.Change> made = new ArrayList<>();
        applyConfirmed(new Conversation.PendingConfirmation("memory", "", actions, null, null), principal, shown,
                session, executed, done, made, Map.of());
        settleFulfilled(done, principal);
        session.changed(made);
        session.remember(said, spoken(executed, List.of()));
        return reply(null, executed, null, shown);
    }

    /**
     * A change just made, as the session remembers it: the kernel's line, the change with its goal id, and the change
     * that takes it back, read from the goal as it was before ({@code before}) and after. A new goal is cancelled, a
     * cancelled one reopened and a reopened one cancelled; a deadline, a task's date and its estimate go back to what
     * they were; a task's status goes back to what it was, and a task the change closed (done or dropped) goes back to
     * to-do, the only status a closed task can take; a new task is dropped. Other changes say why they are not taken
     * back here.
     */
    private Session.Change change(Action action, String goalId, GoalState before, Executed result) {
        String goal = result.goalId() != null ? result.goalId() : goalId;
        Action made = withIds(action, goal, action.approval());
        Optional<TaskView> was = before == null || action.task() == null ? Optional.empty()
                : before.task(action.task());
        Action undo = null;
        String why = null;
        switch (action.type()) {
            case CREATE_GOAL -> undo = undoAction(ActionType.CANCEL_GOAL, goal, null, null, null, null);
            case CANCEL_GOAL -> undo = undoAction(ActionType.REOPEN_GOAL, goal, null, null, null, null);
            case REOPEN_GOAL -> undo = undoAction(ActionType.CANCEL_GOAL, goal, null, null, null, null);
            case RESCHEDULE_GOAL -> {
                if (before != null && before.dueDate() != null) {
                    undo = undoAction(ActionType.RESCHEDULE_GOAL, goal, null, before.dueDate(), null, null);
                } else {
                    why = "the goal had no deadline before, and a deadline cannot be taken away";
                }
            }
            case SET_TASK_STATUS, REMOVE_TASK -> {
                TaskStatus now = action.type() == ActionType.REMOVE_TASK ? TaskStatus.DROPPED : action.status();
                TaskStatus back = was.map(t -> now == TaskStatus.DONE || now == TaskStatus.DROPPED ? TaskStatus.TODO
                        : t.status()).orElse(null);
                if (back != null && back != now) {
                    undo = undoAction(ActionType.SET_TASK_STATUS, goal, was.get().key(), null, back, null);
                } else if (back != null) {
                    why = "its status did not change";
                }
            }
            case UPDATE_TASK -> {
                LocalDate due = action.dueDate() == null ? null : was.map(TaskView::dueDate).orElse(null);
                Double hours = action.hours() == null ? null : was.map(TaskView::effortHours).orElse(null);
                if (due != null || hours != null) {
                    undo = undoAction(ActionType.UPDATE_TASK, goal, was.get().key(), due, null, hours);
                } else if (was.isPresent()) {
                    why = "the task had no date or estimate before, and neither can be taken away";
                }
            }
            case ADD_TASK -> {
                // The new task: the one with the title added, else the newest; planning may add others meanwhile.
                List<String> old = before == null ? List.of() : before.tasks().stream().map(TaskView::id).toList();
                List<TaskView> added = (goal == null ? Optional.<GoalState>empty() : ledger.load(goal))
                        .map(s -> s.tasks().stream().filter(t -> !old.contains(t.id())).toList()).orElse(List.of());
                undo = added.stream().filter(t -> t.title().equalsIgnoreCase(String.valueOf(action.text()).strip()))
                        .reduce((a, b) -> b).or(() -> added.stream().reduce((a, b) -> b))
                        .map(t -> undoAction(ActionType.REMOVE_TASK, goal, t.key(), null, null, null)).orElse(null);
            }
            default -> why = "I cannot take back that kind of change myself";
        }
        return new Session.Change(result.message(), made, undo,
                undo != null || why != null ? why : "I cannot tell what it was before");
    }

    private static Action undoAction(ActionType type, String goal, String task, LocalDate due, TaskStatus status,
                                     Double hours) {
        return new Action(type, goal, task, null, due, status, hours, null, "undo", null);
    }

    /** True for a change that sets a date a correction ("no, I meant Saturday") may change. */
    static boolean datedChange(Action a) {
        return a != null && a.dueDate() != null && (a.type() == ActionType.CREATE_GOAL
                || a.type() == ActionType.RESCHEDULE_GOAL || a.type() == ActionType.UPDATE_TASK);
    }

    /** Time already spent on a task: "I spent 2 hours on the report", "it took me an hour so far". */
    private static final Pattern SPENT = Pattern.compile("(?i)\\b(?:spent|took|worked|been\\s+working|was\\s+working"
            + "|put\\s+in)\\b");
    /** Time spent that says nothing of the end: "I spent an hour on the flowers", "I worked on the report". */
    private static final Pattern SPENT_ONLY = Pattern.compile("(?i)\\b(?:spent|worked\\s+on|been\\s+working"
            + "|was\\s+working|put\\s+in)\\b");
    /** Words that say a task is over: "done", "finished", "completed", "wrapped up". */
    private static final Pattern FINISHED = Pattern.compile("(?i)\\b(?:done|finish\\w*|complet\\w*|did|wrapped"
            + "|sorted|handled|over|through)\\b");

    /**
     * A request for a new task or reminder: "add a task to buy sunscreen", "can you create a task to call the
     * plumber", "new task: book the car service", "todo: buy milk, eggs, bread".
     */
    private static final Pattern TASK_COMMAND = Pattern.compile("(?i)^\\s*(?:(?:please|ok|okay|hey|so)[,\\s]+)?"
            + "(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)?(?:please\\s+)?(?:(?:add|create|make|set\\s+up"
            + "|put\\s+in|write\\s+down|note\\s+down|log|schedule)\\s+(?:me\\s+)?(?:a\\s+|an\\s+|one\\s+|the\\s+)?"
            + "(?:new\\s+)?(?:task|todo|to-do|to\\s+do|reminder|item|entry)(?:\\s+(?:to|for|called|named|about"
            + "|that\\s+says|saying))?|new\\s+(?:task|todo|to-do|reminder|item)(?:\\s+(?:to|for|called|named|about))?"
            + "|(?:todo|to-do|to\\s+do|task|reminder)(?=\\s*[:-]))\\s*[:,-]?\\s*");
    /** "Put renew insurance on my list for next Monday": a thing put on the person's own list. */
    private static final Pattern ON_MY_LIST = Pattern.compile("(?i)^\\s*(?:(?:please|ok|okay|hey|so)[,\\s]+)?"
            + "(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)?(?:please\\s+)?(?:put|add|write|stick)\\s+"
            + "(?<thing>.+?)\\s+(?:on|to|in|onto|into)\\s+(?:my|the)\\s+(?:(?:to-?do|todo)\\s+)?(?:list|agenda"
            + "|calendar|plate|tasks)\\b(?<rest>.*)$");

    /** True when the sentence asks for a new task or reminder in so many words and says what it is. */
    static boolean taskRequest(String said) {
        if (said == null) {
            return false;
        }
        String t = said.strip();
        Matcher command = TASK_COMMAND.matcher(t);
        return command.lookingAt() && !t.substring(command.end()).isBlank() || ON_MY_LIST.matcher(t).matches();
    }

    /** The words without a request for a new task ("add a task to", "new task:", "put ... on my list"). */
    static String withoutTaskCommand(String text) {
        Matcher list = ON_MY_LIST.matcher(text);
        if (list.matches()) {
            return (list.group("thing") + list.group("rest")).strip();
        }
        Matcher command = TASK_COMMAND.matcher(text);
        return command.lookingAt() && !text.substring(command.end()).isBlank() ? text.substring(command.end()) : text;
    }

    /** What a person needs to know rather than to do: "I need to know", "I want to see", "I have to check". */
    private static final Pattern INFORMATION_NEED = Pattern.compile("(?i)^\\s*(?:know|see|check|find\\s+out"
            + "|understand|look\\s+at|remember\\s+what|ask)\\b");

    /**
     * True when a sentence that is no question asks for something new: a goal, a task, a reminder, "I need to ...",
     * "I will ...", unless what follows asks to know something ("I need to know what is due").
     */
    static boolean asksForSomethingNew(String said) {
        if (said == null) {
            return false;
        }
        Matcher filler = GOAL_FILLER.matcher(said);
        return goalRequest(said) || taskRequest(said)
                || filler.lookingAt() && !INFORMATION_NEED.matcher(said.substring(filler.end())).find();
    }

    /** "... so I need to pack and check in online": something to do, said anywhere in a sentence. */
    private static final Pattern NEED_ANYWHERE = Pattern.compile("(?i)\\b(?:i|we)\\s+(?:really\\s+|also\\s+)?"
            + "(?:need|have|want|must|should|got)\\s+to\\b");

    /** The day a request names for its wrapper: "a task for today", "a reminder for tomorrow". */
    private static final Pattern WRAPPER_DAY = Pattern.compile("(?i)\\b(?:task|todo|to-do|reminder|checklist|list)\\s+"
            + "for\\s+(today|tonight|tomorrow)\\b");

    /** Said when a long sentence could not be read and no goal in its words could be offered. */
    static final String TOO_MUCH_AT_ONCE = "That was a lot at once, and I could not read it. Say one goal per "
            + "sentence, for example \"Bring a gift to my sister's wedding by Saturday\".";

    /**
     * The kernel's own offer when the model gave no usable answer, or proposed nothing, for a sentence that asks for
     * something new: the goal in the person's words, with the tasks the sentence lists (see {@link #goalThenTasks}),
     * all asked about; the day a wrapper names ("a task for today") is the deadline when the words give none. A
     * sentence too long to give a short title is asked to be said in parts when {@code orTooMuch}; otherwise there is
     * no offer.
     */
    private Optional<Conversation.Reply> offerFromWords(String said, Grounding.Snapshot snapshot, Session session,
                                                        SecurityService.Caller caller, boolean orTooMuch,
                                                        Conversation.Mode mode, String principal) {
        Optional<GoalSplit> split = goalThenTasks(said);
        String words = split.map(GoalSplit::goal).orElse(said);
        Action offer = offeredGoal(words);
        if (split.isEmpty() && wordCount(offer.text()) > LONG_TITLE) {
            Optional<String> needed = needClause(offer.text());
            if (needed.isPresent()) {
                offer = withText(offer, needed.get());
            }
        }
        if (wordCount(offer.text()) > LONG_TITLE) {
            if (!orTooMuch) {
                return Optional.empty();
            }
            session.remember(said, TOO_MUCH_AT_ONCE);
            return Optional.of(Conversation.Reply.text(TOO_MUCH_AT_ONCE));
        }
        Matcher wrapperDay = WRAPPER_DAY.matcher(said);
        if (offer.dueDate() == null && wrapperDay.find()) {
            offer = withDue(offer, DateExpressions.find(wrapperDay.group(1), properties.today()).orElse(null));
        }
        List<Action> toConfirm = new ArrayList<>(List.of(offer));
        String similar = similarOpenGoal(offer.text(), snapshot);
        List<String> questions = new ArrayList<>(List.of(similar != null ? "You already have the goal \"" + similar
                + "\", which looks similar. Create \"" + offer.text() + "\"" + (offer.dueDate() == null ? ""
                : " due " + offer.dueDate()) + " anyway?"
                : "Did you mean: " + executor.describe(offer, null, null) + offeredDateNote(words, offer) + "?"));
        List<Executed> executed = new ArrayList<>();
        split.ifPresent(s -> askNewGoalTasks(s.tasks().stream().map(t -> new Action(ActionType.ADD_TASK, null, null,
                t, null, null, null, null, said, null)).toList(), toConfirm, questions, executed));
        if (mode != Conversation.Mode.CONVERSE) {
            // Acting instead of asking: the goal in the person's words, with its tasks, done and reported.
            session.dated(datedChange(offer) ? offer : null);
            return Optional.of(applied(said, toConfirm, executed, session, principal));
        }
        Conversation.PendingConfirmation confirmation = new Conversation.PendingConfirmation(
                UUID.randomUUID().toString(), String.join("\n", questions), toConfirm, null, null);
        session.await(confirmation, caller.keyId());
        session.dated(datedChange(offer) ? offer : null);
        session.remember(said, String.join("\n", questions));
        return Optional.of(reply(null, executed, confirmation, new ActionExecutor.Shown()));
    }

    /** Above this many words a goal's title is the whole sentence rather than a name for the goal. */
    static final int LONG_TITLE = 10;

    static int wordCount(String text) {
        return text == null || text.isBlank() ? 0 : text.strip().split("\\s+").length;
    }

    /** A new goal and what it takes, as one sentence gives them (see {@link #goalThenTasks}). */
    record GoalSplit(String goal, List<String> tasks) {
    }

    /**
     * Words that open a statement of what is going on rather than a goal: "I'm moving flats", "we're having friends
     * over", "my mom turns 60", "next Tuesday is my wife's birthday", "it's my anniversary".
     */
    private static final Pattern CIRCUMSTANCE = Pattern.compile("(?i)^\\s*(?:i'?m|i\\s+am|we'?re|we\\s+are|it'?s"
            + "|it\\s+is|there'?s|there\\s+is|my|our|his|her|their|next|this|on|tomorrow|today|tonight)\\b");
    /** A new goal followed by what it takes: "..., I need to invite friends, order a cake and decorate the house". */
    private static final Pattern GOAL_THEN_TASKS = Pattern.compile("(?i)^(?<goal>.+?)(?:\\s*[,;:.]\\s*(?:and\\s+"
            + "|so\\s+)?|\\s+(?:and|so)\\s+)(?:i|we)(?:'ll|\\s+will)?\\s+(?:(?:really\\s+)?(?:need|have|want|got)\\s+to"
            + "|must|should|gotta)\\s+(?<tasks>.+?)\\s*[.!]*$");

    /**
     * The goal and its tasks of a sentence that names a goal and then what it takes ("I want to organise a surprise
     * party for my husband in two weeks, I need to invite friends, order a cake and decorate the house"): the words
     * before as the goal, and each thing listed after as a task (at most ten, of at most eight words each). Empty for
     * any other sentence.
     */
    static Optional<GoalSplit> goalThenTasks(String said) {
        Matcher m = GOAL_THEN_TASKS.matcher(said == null ? "" : said.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        List<String> tasks = Arrays.stream(LIST_SEPARATOR.split(m.group("tasks"))).map(String::strip)
                .map(t -> t.replaceFirst("(?i)^(?:to|also|then)\\s+", "").replaceAll("[\\s,;:.!-]+$", ""))
                .filter(t -> !t.isBlank()).map(ConversationService::capitalFirst).toList();
        String goal = m.group("goal").strip();
        if (wordCount(GOAL_FILLER.matcher(goal).replaceFirst("")) < 2 || tasks.isEmpty() || tasks.size() > 10
                || tasks.stream().anyMatch(t -> wordCount(t) > 8)
                || tasks.size() == 1 && CIRCUMSTANCE.matcher(goal).lookingAt()
                && !GOAL_FILLER.matcher(goal).lookingAt()) {
            // "We're having friends over on Saturday, I need to plan dinner": what is going on, then the one thing to
            // do, which is the goal (see #needClause).
            return Optional.empty();
        }
        return Optional.of(new GoalSplit(goal, tasks));
    }

    /**
     * True when a model's title for a long sentence is a short name in the person's own words: two to eight words
     * besides a date at its end, every one of them said.
     */
    static boolean shortSaidTitle(String text, String said) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String title = withoutTrailingDate(text);
        int words = wordCount(title);
        return words >= 2 && words <= 8 && TargetCheck.stems(title).size() >= 2
                && TargetCheck.unsaid(title, said).isEmpty();
    }

    /** A title without the date phrase at its end, "until" read as "by" ("checklist until the end of today"). */
    static String withoutTrailingDate(String text) {
        String asDeadline = text.replaceAll("(?i)\\b(?:until|till)\\b", "by");
        String titled = goalTitle(asDeadline, true);
        return titled.equals(capitalFirst(asDeadline.strip())) ? capitalFirst(text.strip()) : titled;
    }

    /**
     * "I will go on a business trip and I need a checklist ...", "next Tuesday is my wife's birthday, create a task to
     * buy her a present", "I go on a trip tomorrow create me a checklist ...": the context, then what the person needs
     * or asks to be made for them, or a task or list asked for. "..., and write the invitations" asks for neither. A
     * request (group "ask") counts only after what is going on or a trip (see {@link #needClause}).
     */
    private static final Pattern NEED_CLAUSE = Pattern.compile("(?i)^(?<context>.+?)(?:\\s*[,;.]\\s*(?:and\\s+"
            + "|so\\s+)?|\\s+(?:and|so)\\s+|\\s+(?=(?:please\\s+)?(?:create|make)\\s+(?:me|us)\\b))(?:(?:i|we)\\s+"
            + "(?:will\\s+|would\\s+|also\\s+|really\\s+)?(?:need|want|would\\s+like|'d\\s+like)|(?<ask>(?:please\\s+)?"
            + "(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)?(?:(?:create|make|prepare|write|set\\s+up)\\s+"
            + "(?:me|us)|(?:create|make|set\\s+up|add)(?=\\s+(?:a|an|the|my)\\s+(?:new\\s+)?(?:task|todo|to-do|reminder"
            + "|(?:packing\\s+|shopping\\s+|check)?list)\\b))))\\s+(?<need>(?:a|an|some|the|my)\\s+.+)$");
    /**
     * "We're having friends over on Saturday, I need to plan dinner", "my mom turns 60 on December 12, help me organise
     * a party for her": what is going on, then the one thing the person needs to do.
     */
    private static final Pattern NEED_TO = Pattern.compile("(?i)^(?<context>.+?)(?:\\s*[,;.]\\s*(?:and\\s+|so\\s+)?"
            + "|\\s+(?:and|so)\\s+)(?:(?:i|we)\\s+(?:will\\s+|also\\s+|really\\s+)?(?:need|want|have|got)\\s+to"
            + "|(?:please\\s+)?(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)?help\\s+(?:me|us)(?:\\s+to)?)"
            + "\\s+(?<need>.+)$");
    /**
     * True when a sentence says what is going on and then the one thing the person needs to do ({@link #NEED_TO}): that
     * thing is the goal, however short the sentence.
     */
    static boolean needsTo(String said) {
        String words = goalWordsAsSaid(said);
        Matcher m = NEED_TO.matcher(words);
        return m.matches() && CIRCUMSTANCE.matcher(m.group("context")).lookingAt()
                && !LIST_SEPARATOR.matcher(m.group("need")).find();
    }

    /**
     * The deadline that ends what a sentence needs ({@link #needClause}): "by", "before", "due", "until" or "no later
     * than" and a date at its end ("Checklist ... by the end of the day"); null without one.
     */
    private LocalDate neededBy(String need) {
        OptionalInt dateStart = ActionDates.endingDateStart(need);
        return dateStart.isPresent() && need.substring(dateStart.getAsInt())
                .matches("(?is)^(?:by|before|due|until|till|no later than)\\b.*") ? endingDate(need) : null;
    }

    /**
     * The one date the circumstance of a {@link #needsTo} sentence names ("we're having friends over on Saturday" of
     * "..., I need to plan dinner"); empty when it names none or several.
     */
    static Optional<LocalDate> contextDate(String said, LocalDate today) {
        Matcher m = NEED_TO.matcher(goalWordsAsSaid(said));
        if (!m.matches() || DateExpressions.datePhraseSpans(m.group("context")).size() != 1) {
            return Optional.empty();
        }
        return DateExpressions.find(m.group("context"), today);
    }

    /**
     * A task asked for with its date before what it is: "a task by this weekend to go and buy her a present", "a task
     * for the coming weekend to do the shopping", "a reminder to call mom".
     */
    private static final Pattern DATED_WRAPPER = Pattern.compile("(?i)^(?:a|an|the|my)\\s+(?:new\\s+)?"
            + "(?:task|todo|to-do|reminder)\\s+(?:(?:by|for|on|before|due)\\s+(?<date>.+?)\\s+)?to\\s+(?<todo>.+)$");
    /** The trip or event a context names: "a business trip", "on holidays", "my sister's wedding". */
    private static final Pattern TRIP = Pattern.compile("(?i)\\b(?:a|an|my|the|our)\\s+(?<trip>(?:[\\p{L}'-]+\\s+)?"
            + "(?:trip|holidays?|vacation|journey|travel|wedding|move|conference))\\b|\\bon\\s+(?<plain>holidays?"
            + "|vacation)\\b");
    /** Words that point back at the trip: "on this travel", "for the trip". */
    private static final Pattern BACK_REFERENCE = Pattern.compile("(?i)\\b(?:this|that|the|my)\\s+(?:travel|trip"
            + "|journey|holidays?|vacation)\\b");

    /**
     * What a long sentence says the person needs, as a goal's title: "I will go on a business trip and I need a task
     * for today a checklist with what I need to take with me on this travel" gives "Checklist with what I need to take
     * with me on the business trip"; "next Tuesday is my wife's birthday, create a task to buy her a present" gives
     * "Buy her a present"; "we're having friends over on Saturday, I need to plan dinner" gives "Plan dinner" (see
     * {@link #NEED_TO}). A wrapper ("a task for today", "a task by Sunday to", "a to-do") goes, its date moving to the
     * end, and so does "go and"; the trip the first part names replaces the words that point back at it or is added
     * ("for the holidays"), and a date at the end stays, to be read as the deadline. A request to make something, and
     * a thing to do, count only after what is going on or a trip ("new goal: plan the party, and add a task to buy a
     * cake" names its goal first). Empty when the sentence says no such thing.
     */
    static Optional<String> needClause(String words) {
        Matcher m = NEED_CLAUSE.matcher(words == null ? "" : words.strip());
        boolean asked = m.matches() && m.group("ask") != null;
        if (!m.matches()) {
            m = NEED_TO.matcher(words == null ? "" : words.strip());
            if (!m.matches()) {
                return Optional.empty();
            }
            asked = true;
        }
        String context = m.group("context");
        if (asked && !CIRCUMSTANCE.matcher(context).lookingAt() && !TRIP.matcher(context).find()) {
            // "New goal: plan the party, and add a task to buy a cake" names the goal first: the task is no goal.
            return Optional.empty();
        }
        Matcher dated = DATED_WRAPPER.matcher(m.group("need").strip());
        String need = (dated.matches() ? dated.group("todo") + (dated.group("date") == null ? ""
                : " by " + dated.group("date")) : m.group("need").strip())
                .replaceFirst("(?i)^go\\s+(?:and|an|&|to)\\s+", "")
                .replaceFirst("(?i)^(?:a|an|some|the|my)\\s+(?:new\\s+)?(?:task|todo|to-do|reminder)(?:\\s+(?:for\\s+)?"
                        + "(?:today|tonight|tomorrow))?\\s+(?:(?:with|for|of|as)\\s+)?(?=(?:a|an|the|my|some)\\s+)", "")
                .replaceFirst("(?i)^((?:a|an|some|the|my)\\s+)(?:todo|to-do)\\s+(?=(?:check)?list\\b)", "$1")
                .replaceFirst("(?i)^(?:a|an|some|the|my)\\s+", "");
        Matcher trip = TRIP.matcher(m.group("context"));
        if (trip.find()) {
            String name = trip.group("trip") != null ? trip.group("trip") : trip.group("plain");
            Matcher back = BACK_REFERENCE.matcher(need);
            if (back.find()) {
                need = need.substring(0, back.start()) + "the " + name + need.substring(back.end());
            } else {
                OptionalInt date = ActionDates.endingDateStart(need);
                int at = date.isPresent() && date.getAsInt() > 0 ? date.getAsInt() : need.length();
                need = need.substring(0, at).strip() + " for the " + name
                        + (at < need.length() ? " " + need.substring(at).strip() : "");
            }
        }
        need = need.replaceAll("[\\s,;:.!-]+$", "");
        return wordCount(need) >= 2 ? Optional.of(capitalFirst(need)) : Optional.empty();
    }

    /** News that a task is done: "I finished inviting friends", "the cake is done", "I booked the venue". */
    private static final Pattern DONE_NEWS = Pattern.compile("(?i)\\b(?:finished|completed|done|did|booked|bought"
            + "|called|packed|paid|sent|ordered|picked\\s+up|got|wrote|cleaned|fixed|sorted|handled)\\b");
    /** News that work on a task is under way: "I'm working on the guest list", "I started the slides". */
    private static final Pattern PROGRESS_NEWS = Pattern.compile("(?i)\\b(?:working\\s+on|started|starting"
            + "|i'?m\\s+on|i\\s+am\\s+on|busy\\s+with|in\\s+the\\s+middle\\s+of)\\b");
    /** News that a task is held up: "I'm stuck on the cake", "the venue is blocked". */
    private static final Pattern BLOCKED_NEWS = Pattern.compile("(?i)\\b(?:stuck|blocked|waiting\\s+(?:for|on))\\b");
    /** "I can't invite friends until my sister sends the list": held up by something else. */
    private static final Pattern CANT_UNTIL = Pattern.compile("(?i)\\b(?:can'?t|cannot|unable\\s+to)\\b.*\\b(?:until"
            + "|till|before|unless)\\b|\\bcan'?t\\s+(?:continue|go\\s+on|finish|start)\\b");
    /** Who news is about: the person ("I", "we") or a thing that "is done", "is blocked". */
    private static final Pattern NEWS_SUBJECT = Pattern.compile("(?i)\\b(?:i|i'm|im|i've|ive|we|we're|we've)\\b"
            + "|\\b(?:is|are|was|were)\\s+(?:now\\s+)?(?:done|finished|complete|blocked|stuck|in\\s+progress)\\b");
    /** The reason news gives, from its first word on: "waiting for my friend", "until I get the list". */
    private static final Pattern REASON = Pattern.compile("(?i)[,;]?\\s*\\b(?:waiting\\s+(?:for|on)|until|till"
            + "|because|since|unless|as\\s+soon\\s+as)\\b.*$");
    /** Words of news that name no task: the status words themselves and the words around them. */
    private static final Set<String> NEWS_WORDS = Set.of("finish", "finished", "complet", "done", "did", "work",
            "working", "start", "started", "stuck", "block", "blocked", "wait", "waiting", "busi", "middl", "just",
            "today", "now", "already", "task", "thing", "unblock", "unblocked", "anymor", "longer");

    /**
     * The kernel's reading of news about a task the model read as something else ("I'm working on inviting friends"
     * read as an answer to a message): the status the words state (to-do for "isn't blocked anymore", then blocked,
     * in progress and done, in that order) and the open task whose title holds their other words, a task of the goal
     * in focus first. One such task is offered the status through {@code toConfirm} and {@code questions}; several
     * are listed (at most three) and the person says which; none is said so. Empty for a request ("mark everything
     * done"), for words with no one the news is about, and for words that state no status or hold no word besides
     * it.
     */
    private Optional<Executed> taskNews(String said, Grounding.Snapshot snapshot, Set<String> recent,
                                        SecurityService.Caller caller, List<Action> toConfirm, List<String> questions) {
        if (CHANGE_REQUEST.matcher(said).lookingAt() || !NEWS_SUBJECT.matcher(said).find()) {
            return Optional.empty(); // "mark everything done" asks for a change; news says what happened
        }
        String t = TargetCheck.withoutNegated(said);
        TaskStatus status = UNBLOCKED.matcher(said).find() ? TaskStatus.TODO
                : BLOCKED_NEWS.matcher(t).find() || CANT_UNTIL.matcher(said).find() ? TaskStatus.BLOCKED
                : PROGRESS_NEWS.matcher(t).find() ? TaskStatus.IN_PROGRESS
                : DONE_NEWS.matcher(t).find() ? TaskStatus.DONE : null;
        if (status == null) {
            return Optional.empty();
        }
        // "I'm stuck on the ice, waiting for my friend": what follows "waiting for", "until" or "because" is the
        // reason, and the task is named before it.
        String taskPart = REASON.matcher(said).replaceFirst("");
        Set<String> words = new HashSet<>(TargetCheck.stems(taskPart));
        words.removeIf(w -> NEWS_WORDS.contains(w) || DONE_NEWS.matcher(w).matches());
        if (words.isEmpty()) {
            words.addAll(TargetCheck.stems(said));
            words.removeIf(w -> NEWS_WORDS.contains(w) || DONE_NEWS.matcher(w).matches());
        }
        if (words.isEmpty()) {
            return Optional.empty();
        }
        record Candidate(String goalId, String goalText, TaskView task, double share) {
        }
        List<Candidate> found = new ArrayList<>();
        for (String goalId : snapshot.documents().keySet()) {
            ledger.load(goalId).ifPresent(goal -> goal.tasks().stream().filter(task -> task.status().isOpen())
                    .forEach(task -> {
                        Set<String> title = TargetCheck.stems(task.title());
                        long hit = title.stream().filter(words::contains).count();
                        if (hit > 0 && hit * 2 >= title.size()) {
                            found.add(new Candidate(goalId, goal.text(), task, (double) hit / title.size()));
                        }
                    }));
        }
        double best = found.stream().mapToDouble(Candidate::share).max().orElse(0);
        List<Candidate> top = found.stream().filter(c -> c.share() == best).toList();
        List<Candidate> focused = top.stream().filter(c -> recent.contains(c.goalId())).toList();
        List<Candidate> chosen = focused.size() == 1 ? focused : top;
        if (chosen.isEmpty()) {
            return Optional.of(new Executed(ActionType.SET_TASK_STATUS, false, "find the task you mean: no open task "
                    + "matches your words; say its key and goal, for example \"T2 of the picnic is "
                    + (status == TaskStatus.DONE ? "done" : status == TaskStatus.BLOCKED ? "blocked" : "in progress")
                    + "\" (\"show me the picnic\" lists the keys)", null, null));
        }
        if (chosen.size() == 1 && caller.may(ActionType.SET_TASK_STATUS, chosen.getFirst().goalId())) {
            Candidate c = chosen.getFirst();
            Action offer = new Action(ActionType.SET_TASK_STATUS, c.goalId(), c.task().key(), null, null, status, null,
                    null, said, null);
            toConfirm.add(offer);
            questions.add("Did you mean: " + executor.describe(offer, c.goalId(), null) + "?");
            return Optional.empty();
        }
        return Optional.of(new Executed(ActionType.SET_TASK_STATUS, false, "tell which task you mean: "
                + String.join(" or ", chosen.stream().limit(3).map(c -> c.task().key() + " \"" + c.task().title()
                + "\" in \"" + c.goalText() + "\"").toList()) + "; say its key and goal, for example \""
                + chosen.getFirst().task().key() + " of " + chosen.getFirst().goalText() + "\"", null, null));
    }

    /** A part of a sentence that opens with news of a finished task: "got the plates", "I just bought the napkins". */
    private static final Pattern DONE_PART = Pattern.compile("(?i)^\\s*(?:(?:i|we)\\s+)?(?:just\\s+|already\\s+)?"
            + "(?:got|bought|finished|completed|booked|packed|paid|sent|ordered|picked\\s+up|sorted)\\s+(?<what>.+)$");

    /**
     * The status change a part of a sentence reports when it opens with news of a finished task ({@link #DONE_PART})
     * and its other words name one open task best of all (most of its title's words said); empty otherwise.
     */
    private Optional<Action> missedNews(String part, Grounding.Snapshot snapshot) {
        Matcher m = DONE_PART.matcher(part);
        if (!m.matches()) {
            return Optional.empty();
        }
        Set<String> words = TargetCheck.stems(m.group("what"));
        words.removeIf(NEWS_WORDS::contains);
        return openTaskNamed(words, snapshot).map(t -> new Action(ActionType.SET_TASK_STATUS,
                t.goalId().substring(0, 8), t.key(), null, null, TaskStatus.DONE, null, null, part, part));
    }

    /**
     * The one open task of an open goal whose title {@code words} name best of all (most of its title's words said),
     * empty when none or several do.
     */
    private Optional<TaskRef> openTaskNamed(Set<String> words, Grounding.Snapshot snapshot) {
        double best = 0;
        List<TaskRef> found = new ArrayList<>();
        for (String goalId : snapshot.documents().keySet()) {
            Optional<GoalState> goal = open(goalId) ? ledger.load(goalId) : Optional.empty();
            for (TaskView task : goal.map(GoalState::tasks).orElse(List.of())) {
                Set<String> title = TargetCheck.stems(task.title());
                long hit = title.stream().filter(words::contains).count();
                if (!task.status().isOpen() || hit == 0 || hit * 2 < title.size()) {
                    continue;
                }
                double share = (double) hit / title.size();
                if (share > best) {
                    best = share;
                    found.clear();
                }
                if (share == best) {
                    found.add(new TaskRef(goalId, task.key()));
                }
            }
        }
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /** "The speaker will take about an hour", "the charts will need 3 hours": how long a task will take. */
    private static final Pattern ESTIMATE_NEWS = Pattern.compile("(?i)^\\s*(?:i\\s+think\\s+)?(?<what>.+?)\\s+(?:will"
            + "|would|should|might|is\\s+going\\s+to|'ll)\\s+(?:take|need)\\s+(?<amount>.+?)\\s*[.!]?$");

    /**
     * The estimate a sentence states for the one open task it names ({@link #ESTIMATE_NEWS}) with one plain amount
     * of time ("about an hour" is 1 h); empty otherwise.
     */
    private Optional<Action> estimateNews(String said, Grounding.Snapshot snapshot) {
        Matcher m = ESTIMATE_NEWS.matcher(said);
        Optional<Double> hours = m.matches() ? Durations.only(m.group("amount")) : Optional.empty();
        if (hours.isEmpty()) {
            return Optional.empty();
        }
        return openTaskNamed(TargetCheck.stems(m.group("what")), snapshot).map(t -> new Action(
                ActionType.UPDATE_TASK, t.goalId().substring(0, 8), t.key(), null, null, null, hours.get(), null,
                said, said));
    }

    /**
     * "I need a packing list for the picnic", "make me a budget for the move": new information for a goal. "A plan for
     * the picnic" is no such thing: that goal's plan is made again (REFINE_GOAL), by the model's reading.
     */
    private static final Pattern INFORMATION_FOR_GOAL = Pattern.compile("(?i)^\\s*(?:(?:i|we)\\s+(?:really\\s+"
            + "|also\\s+)?(?:need|want|would\\s+like|'d\\s+like)|(?:can|could)\\s+you\\s+(?:get|make|give)(?:\\s+me)?"
            + "|give\\s+me|make\\s+me)\\s+(?:a|an|some)\\s+(?<what>(?:[\\p{L}-]+\\s+){0,3}?(?:list|checklist"
            + "|schedule|budget|itinerary|menu|playlist|timeline|summary|breakdown|estimate|forecast|route|map))\\s+for"
            + "\\s+(?:the|my|our)\\s+(?<goal>.+?)\\s*[.!?]?$");

    /** Words that name nobody to invite: "let me help with ...". */
    private static final Set<String> PRONOUNS = Set.of("me", "us", "it", "them", "him", "her");
    /** "Let Ana help with the picnic until Saturday", "invite Tom to the move": someone asked into a goal. */
    private static final Pattern INVITE_REQUEST = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:let|allow|invite)\\s+"
            + "(?<who>\\p{L}[\\p{L}'-]*)\\s+(?:(?:to\\s+)?help\\s+(?:me\\s+)?(?:with|on)|work\\s+on|join|into|to)\\s+"
            + "(?<what>.+?)(?:\\s+(?:until|till|through)\\s+(?<until>.+?))?\\s*[.!]?$");

    /**
     * "The passport thing also needs a copy of my birth certificate", "the picnic still needs a blanket": a thing a
     * goal needs, said as a noun ("needs to be done by Friday" is no thing).
     */
    private static final Pattern GOAL_NEEDS = Pattern.compile("(?i)^\\s*(?:oh\\s+and\\s+|and\\s+)?(?<goal>.+?)\\s+"
            + "(?:also\\s+|still\\s+)?needs\\s+(?:also\\s+)?(?<thing>(?!to\\b|it\\b|more\\s+time\\b)[\\p{L}\\d].*?)"
            + "(?:\\s+too|\\s+as\\s+well)?\\s*[.!]?$");

    /** "Retire the packing list", "remove the packing list capability". */
    private static final Pattern RETIRE_REQUEST = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:retire|get\\s+rid\\s+of)"
            + "\\s+(?:the\\s+)?(?<name>.+?)(?:\\s+capability)?\\s*[.!]*$");

    /**
     * The kernel's answer to a retire request the assistant gave no usable answer for: the grown capability that
     * matches the name, retired (asked about first in {@link Conversation.Mode#CONVERSE}), or that none does.
     */
    private Conversation.Reply retireReply(String said, String name, Session session, SecurityService.Caller caller,
                                           Conversation.Mode mode, String principal) {
        String capability = lifecycleTarget(ActionType.RETIRE_CAPABILITY, name);
        if (capability == null) {
            String text = "Nothing was changed: no capability the system grew matches \"" + name + "\"; a built-in "
                    + "capability cannot be retired this way, stop its provider instead (\"which capabilities are "
                    + "running?\" lists them).";
            session.remember(said, text);
            return Conversation.Reply.text(text);
        }
        Action retire = new Action(ActionType.RETIRE_CAPABILITY, null, null, capability, null, null, null, null, said,
                null);
        if (mode != Conversation.Mode.CONVERSE) {
            return applied(said, List.of(retire), List.of(), session, principal);
        }
        Conversation.PendingConfirmation confirmation = new Conversation.PendingConfirmation(
                UUID.randomUUID().toString(), executor.question(retire, null, null), List.of(retire), null, null);
        session.await(confirmation, caller.keyId());
        session.remember(said, confirmation.question());
        return reply(null, List.of(), confirmation, new ActionExecutor.Shown());
    }

    /** Words that add something: "add", "create", "new", "include", "put ... on". */
    private static final Pattern ADD_WORDS = Pattern.compile("(?i)\\b(?:add|create|new|include|put|plus)\\b");
    /**
     * The note on a date the model read differently: the other date phrase of the words it comes from, when one
     * names it ("not 2026-11-24, which is \"tomorrow\" in your words", when the trip is tomorrow and the deadline
     * the end of the day); the phrase the kernel read its own date from ({@code kernelPhrase}) is never that one.
     * Else, when the phrase the kernel read gives the model's date read alone, that the words could also mean it
     * ("this weekend" read as Sunday and as Saturday), and otherwise the assistant's reading.
     */
    String otherDateNote(LocalDate modelDate, String said, String kernelPhrase) {
        String kernel = kernelPhrase.toLowerCase(Locale.ROOT);
        for (DateExpressions.Span span : DateExpressions.datePhraseSpans(said)) {
            String phrase = span.in(said).strip();
            String lower = phrase.toLowerCase(Locale.ROOT);
            if (!kernel.isBlank() && (kernel.contains(lower) || lower.contains(kernel))) {
                continue;
            }
            if (DateExpressions.find(phrase, properties.today()).filter(modelDate::equals).isPresent()) {
                return " (not " + modelDate + ", which is \"" + phrase + "\" in your words)";
            }
        }
        // The same words read two ways ("this weekend": Saturday or Sunday): the phrase alone gives the model's date.
        boolean sameWords = !kernel.isBlank() && DateExpressions.find(kernelPhrase, properties.today())
                .filter(modelDate::equals).isPresent();
        return sameWords ? " (your words could also mean " + modelDate + ")"
                : " (the assistant read " + modelDate + ")";
    }

    /** "Yesterday": a day that has passed, which no deadline or date move takes. */
    private static final Pattern YESTERDAY = Pattern.compile("(?i)\\byesterday\\b");

    /** "Verify the picnic goal", "check the integrity of the picnic". */
    private static final Pattern VERIFY_REQUEST = Pattern.compile("(?i)^\\s*(?:please\\s+|can\\s+you\\s+)?(?:verify"
            + "|check\\s+the\\s+integrity\\s+of|fingerprint)\\b");

    /**
     * A request to change something: "drop the unicorn task", "move the gala to Friday", "mark T3 done", "I don't need
     * napkins anymore", "no need for the unicorn", "forget the unicorn".
     */
    private static final Pattern CHANGE_REQUEST = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:drop|remove|delete"
            + "|cancel|mark|move|finish|complete|reschedule|push|postpone|change|update|reopen|bring\\s+back"
            + "|forget(?:\\s+about)?|no\\s+(?:more\\s+)?need\\s+(?:for|of)"
            + "|(?:i|we)\\s+(?:don'?t|do\\s+not|no\\s+longer)\\s+need)\\b");

    /** Words of a change request that name nothing: its verbs, the words for a task or goal and for its date. */
    private static final Pattern CHANGE_FILLER = Pattern.compile("(?i)\\b(?:the|my|a|an|this|that|task|tasks|goal"
            + "|goals|item|thing|one|as|done|to|do|todo|from|of|for|please|it|in|on|progress|blocked|everything|all"
            + "|anything|something|them|those|these|both|each|every|last|first|next|other|rest|again|now|today"
            + "|anymore|any\\s+more|any|longer|deadlines?|dates?|due)\\b");

    /**
     * Why a change request cannot be made when it names something no goal or task of the person mentions ("drop the
     * unicorn task": "change anything about "unicorn": no goal or task of yours mentions it; the closest are ..."),
     * empty for a sentence that is no change request or names something known.
     */
    private Optional<String> nothingNamed(String said, Map<String, List<String>> documents) {
        if (!CHANGE_REQUEST.matcher(said).lookingAt()) {
            return Optional.empty();
        }
        return TargetCheck.unknownName(said, documents).or(() -> unknownThing(said, documents))
                .map(thing -> "change anything about \"" + thing + "\": no goal or task of yours mentions it; "
                        + noSuchGoal(thing, documents).replaceFirst("^find a goal matching \"[^\"]*\"; ", ""));
    }

    /**
     * What a change request names when no goal or task uses any of its words ("drop the unicorn task" gives
     * "unicorn"): the words after its verb without the words for a task or goal and without dates. Empty when some
     * word is known or none is left.
     */
    static Optional<String> unknownThing(String said, Map<String, List<String>> documents) {
        String rest = CHANGE_REQUEST.matcher(said).replaceFirst("");
        for (DateExpressions.Span span : DateExpressions.datePhraseSpans(rest).reversed()) {
            rest = rest.substring(0, span.start()) + rest.substring(span.end());
        }
        String thing = CHANGE_FILLER.matcher(rest).replaceAll(" ").replaceAll("[^\\p{L}\\p{N}' -]", " ")
                .replaceAll("\\s+", " ").strip();
        Set<String> words = TargetCheck.stems(thing);
        if (words.isEmpty()) {
            return Optional.empty();
        }
        Set<String> known = new HashSet<>();
        documents.values().forEach(texts -> texts.forEach(text -> known.addAll(TargetCheck.stems(text))));
        return words.stream().noneMatch(known::contains) ? Optional.of(thing) : Optional.empty();
    }

    /**
     * True when the words name the title of task {@code taskKey} of goal {@code goalId} best of all tasks shown (most
     * of the title's words said), and no task of another goal as well; then the words name the goal through its task,
     * whatever other words they share with other goals ("Friday").
     */
    private boolean namedTask(String words, String goalId, String taskKey, Grounding.Snapshot snapshot) {
        Set<String> said = TargetCheck.stems(words);
        double best = 0;
        Set<String> bestGoals = new HashSet<>();
        boolean ours = false;
        for (String g : snapshot.documents().keySet()) {
            Optional<GoalState> state = ledger.load(g);
            if (state.isEmpty()) {
                continue;
            }
            for (TaskView t : state.get().tasks()) {
                Set<String> title = TargetCheck.stems(t.title());
                long hit = title.stream().filter(said::contains).count();
                if (title.isEmpty() || hit == 0 || hit * 2 < title.size()) {
                    continue;
                }
                double share = (double) hit / title.size();
                if (share > best) {
                    best = share;
                    bestGoals.clear();
                    ours = false;
                }
                if (share == best) {
                    bestGoals.add(g);
                    ours |= g.equals(goalId) && t.key().equalsIgnoreCase(taskKey.strip());
                }
            }
        }
        return ours && bestGoals.size() == 1;
    }

    /** True when at least half of {@code words} (and at least one) are in {@code other}. */
    static boolean sharesMost(Set<String> words, Set<String> other) {
        long shared = words.stream().filter(other::contains).count();
        return shared > 0 && shared * 2 >= words.size();
    }

    /**
     * True when the goal {@code goalId}'s own text shares a word with {@code words} and no other goal's text does,
     * whatever their tasks say ("push the passport back a week" names "Renew my passport", not the trip checklist
     * with a task about the passport).
     */
    private static boolean namedByName(String words, String goalId, Map<String, List<String>> documents) {
        Set<String> said = TargetCheck.stems(TargetCheck.withoutNegated(words));
        List<String> own = documents.getOrDefault(goalId, List.of());
        if (own.isEmpty() || TargetCheck.stems(own.getFirst()).stream().noneMatch(said::contains)) {
            return false;
        }
        return documents.entrySet().stream().filter(e -> !e.getKey().equals(goalId) && !e.getValue().isEmpty())
                .noneMatch(e -> TargetCheck.stems(e.getValue().getFirst()).stream().anyMatch(said::contains));
    }

    /**
     * A task's date move read as its goal's when the words share only the goal's own name with the task and type no
     * task key ("move the passport to Friday" read as T3 "Schedule appointment at passport office" of "Renew my
     * passport"): the goal's deadline moves, when the caller may move it. Any other action as it is.
     */
    private Action goalMoveNotTask(Action a, String said, Grounding.Snapshot snapshot, SecurityService.Caller caller) {
        Optional<String> goalId = snapshot.goal(a.goal());
        if (a.type() != ActionType.UPDATE_TASK || a.dueDate() == null || a.hours() != null || a.task() == null
                || goalId.isEmpty() || !caller.may(ActionType.UPDATE_TASK, goalId.get())
                || !caller.may(ActionType.RESCHEDULE_GOAL, goalId.get())) {
            return a; // a guest's change is refused before anything is read for it
        }
        Optional<GoalState> goal = ledger.load(goalId.get());
        Optional<TaskView> task = goal.flatMap(g -> g.task(a.task()));
        if (task.isEmpty()) {
            return a;
        }
        Set<String> words = TargetCheck.stems(TargetCheck.withoutNegated(said));
        Set<String> goalWords = TargetCheck.stems(goal.get().text());
        Set<String> shared = new HashSet<>(TargetCheck.stems(task.get().title()));
        shared.retainAll(words);
        boolean namesGoal = goalWords.stream().anyMatch(words::contains);
        // Several such moves of one goal ("move the passport to Friday" read as a move of each of its tasks) become
        // one: the converted actions are equal. A typed key ("push T4 of the picnic back") names the task.
        return namesGoal && !shared.isEmpty() && goalWords.containsAll(shared) && namedKeys(said).isEmpty()
                ? new Action(ActionType.RESCHEDULE_GOAL, a.goal(), null, null, a.dueDate(), null, null, null, null,
                null)
                : a;
    }

    /** A task of a goal: the goal's id and the task's key. */
    record TaskRef(String goalId, String key) {
    }

    /**
     * The one task the words name, when it belongs to another goal than {@code goalId} and neither {@code goalId}'s
     * text nor any of its tasks shares a word with them: "the speaker isn't blocked anymore" aimed at "Learn Spanish"
     * names T13 "Speaker" of the picnic. A dropped task or a task of a closed goal never counts, and only one task
     * named better than every other (most of its title's words said) does; empty otherwise.
     */
    private Optional<TaskRef> taskNamedElsewhere(String words, String goalId, Grounding.Snapshot snapshot) {
        Set<String> said = TargetCheck.stems(words);
        String aimedAt = goalText(goalId, snapshot);
        if (aimedAt == null || TargetCheck.stems(aimedAt).stream().anyMatch(said::contains)) {
            return Optional.empty(); // the words name the goal the change is aimed at
        }
        double best = 0;
        List<TaskRef> found = new ArrayList<>();
        for (String g : snapshot.documents().keySet()) {
            Optional<GoalState> state = ledger.load(g);
            if (state.isEmpty() || state.get().status() == GoalStatus.CANCELLED
                    || state.get().status() == GoalStatus.COMPLETED) {
                continue;
            }
            for (TaskView t : state.get().tasks()) {
                Set<String> title = TargetCheck.stems(t.title());
                long hit = title.stream().filter(said::contains).count();
                if (hit > 0 && g.equals(goalId)) {
                    return Optional.empty(); // the words may mean a task of the goal the change is aimed at
                }
                if (title.isEmpty() || hit == 0 || hit * 2 < title.size() || t.status() == TaskStatus.DROPPED) {
                    continue;
                }
                double share = (double) hit / title.size();
                if (share > best) {
                    best = share;
                    found.clear();
                }
                if (share == best) {
                    found.add(new TaskRef(g, t.key()));
                }
            }
        }
        return found.size() == 1 && !found.getFirst().goalId().equals(goalId) ? Optional.of(found.getFirst())
                : Optional.empty();
    }

    /** "I didn't finish the napkins", "I haven't done the flowers yet": a task taken for done that is not. */
    private static final Pattern NOT_DONE = Pattern.compile("(?i)\\b(?:i|we)\\s+(?:didn'?t|did\\s+not|haven'?t"
            + "|have\\s+not|never)\\s+(?:actually\\s+|really\\s+)?(?:finish(?:ed)?|do(?:ne)?|complete(?:d)?"
            + "|get\\s+to)\\s+(?<what>.+?)(?:\\s+(?:yet|after\\s+all|at\\s+all))?\\s*[.!]*$");

    /**
     * The task a negated completion names ("I didn't finish the napkins"), back to to-do, when exactly one done task
     * of the goals shown holds the words (with its goal's words allowed beside them); empty otherwise.
     */
    private Optional<Action> reopened(String said, Grounding.Snapshot snapshot) {
        Matcher m = NOT_DONE.matcher(said);
        if (!m.find()) {
            return Optional.empty();
        }
        Set<String> wanted = TargetCheck.stems(m.group("what"));
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<Action> found = new ArrayList<>();
        for (String goalId : snapshot.documents().keySet()) {
            ledger.load(goalId).ifPresent(goal -> goal.tasks().stream().filter(t -> t.status() == TaskStatus.DONE)
                    .filter(t -> {
                        Set<String> title = TargetCheck.stems(t.title());
                        Set<String> known = new HashSet<>(title);
                        known.addAll(TargetCheck.stems(goal.text()));
                        return title.stream().anyMatch(wanted::contains) && known.containsAll(wanted);
                    })
                    .forEach(t -> found.add(new Action(ActionType.SET_TASK_STATUS, goalId, t.key(), null, null,
                            TaskStatus.TODO, null, null, said, null))));
        }
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /**
     * The title for what a sentence needs for a trip or event it names first, when the goal a task was aimed at never
     * mentions that trip ("I will go on a business trip and I need a checklist ..." aimed at the holiday checklist
     * gives "Checklist with what I need to take with me on the business trip"); empty otherwise.
     */
    static Optional<String> tripNotIn(String said, List<String> goalTexts) {
        Matcher need = NEED_CLAUSE.matcher(goalWords(said));
        if (!need.matches() || goalTexts == null) {
            return Optional.empty();
        }
        Matcher trip = TRIP.matcher(need.group("context"));
        if (!trip.find()) {
            return Optional.empty();
        }
        Set<String> name = TargetCheck.stems(trip.group("trip") != null ? trip.group("trip") : trip.group("plain"));
        Set<String> known = new HashSet<>();
        goalTexts.forEach(text -> known.addAll(TargetCheck.stems(text)));
        return known.containsAll(name) ? Optional.empty() : needClause(goalWords(said));
    }

    /** The one person a sentence names as "my wife", "my brother's", "my boss": who "her", "him" or "his" means. */
    private static final Pattern MY_PERSON = Pattern.compile("(?i)\\bmy\\s+(wife|husband|partner|girlfriend|boyfriend"
            + "|mom|mum|mother|dad|father|sister|brother|son|daughter|grandma|grandmother|grandpa|grandfather"
            + "|aunt|uncle|cousin|friend|boss|colleague|neighbou?r|niece|nephew)(?:'s)?\\b");
    private static final Set<String> SHE = Set.of("wife", "girlfriend", "mom", "mum", "mother", "sister", "daughter",
            "grandma", "grandmother", "aunt", "niece");
    private static final Set<String> HE = Set.of("husband", "boyfriend", "dad", "father", "brother", "son", "grandpa",
            "grandfather", "uncle", "nephew");
    /** "Her" as an object: before an article or at the end ("buy her a present", "call her"). */
    private static final Pattern HER_OBJECT = Pattern.compile("(?i)\\bher\\b(?=\\s+(?:a|an|the|some|something)\\b"
            + "|\\s*[,.!?]|\\s*$)");

    /**
     * A new goal's title with "her", "him" or "his" as the one person the sentence names ("next Tuesday is my wife's
     * birthday ... buy her a present" gives "Buy my wife a present"; "for her birthday" gives "for my wife's
     * birthday"), when the title does not name that person itself and the pronoun fits them. Otherwise the title as
     * it is.
     */
    static String withPerson(String title, String said) {
        if (title == null || said == null) {
            return title;
        }
        Matcher m = MY_PERSON.matcher(said);
        Set<String> people = new LinkedHashSet<>();
        while (m.find()) {
            people.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        if (people.size() != 1 || MY_PERSON.matcher(title).find()) {
            return title;
        }
        String who = people.iterator().next();
        String result = title;
        if (!HE.contains(who)) {
            result = HER_OBJECT.matcher(result).replaceAll("my " + who);
            result = result.replaceAll("(?i)\\bher\\b", "my " + who + "'s");
        }
        if (!SHE.contains(who)) {
            result = result.replaceAll("(?i)\\bhim\\b", "my " + who).replaceAll("(?i)\\bhis\\b", "my " + who + "'s");
        }
        return result;
    }

    /** Words a list question uses for the list, its cut or the question itself: none of them names a goal. */
    private static final Pattern LIST_VOCABULARY = Pattern.compile("(?i)\\b(?:what'?s?|which|how|many|much|is|are|do"
            + "|does|did|have|has|i|my|me|the|a|an|for|on|in|of|to|left|pending|remaining|open|due|overdue|late"
            + "|urgent|next|today|tonight|tomorrow|this|week|month|tasks?|to-?dos?|todo|list|agenda|goals?|projects?"
            + "|things?|anything|everything|still|should|can|need|work|show|tell|about|status|there|up|going|with"
            + "|so|far|now|please|any|all|and|or)\\b");

    /**
     * The one goal a list question's own words name ("what's pending for the holiday?"), leaving out the words of the
     * list and the question; empty when they name none or several.
     */
    private Optional<String> goalNamedInRead(String words, Grounding.Snapshot snapshot) {
        String rest = LIST_VOCABULARY.matcher(words == null ? "" : words.toLowerCase(Locale.ROOT)).replaceAll(" ")
                .replaceAll("[^\\p{L}\\p{N}' ]", " ").strip();
        if (rest.isBlank()) {
            return Optional.empty();
        }
        List<String> named = snapshot.documents().keySet().stream()
                .filter(id -> targets.namesOnly(rest, id, snapshot.documents())).toList();
        return named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    /** A new goal with a task added in the same breath: "plan a barbecue on Sunday and add buying charcoal to it". */
    private static final Pattern ADD_CLAUSE = Pattern.compile("(?i)^(?<goal>.+?),?\\s+and\\s+(?:also\\s+|then\\s+)?"
            + "(?:add|include|put)\\s+(?<task>.+?)(?:\\s+(?:to|on|in|onto)\\s+(?:it|that|this|the\\s+list|my\\s+list"
            + "|the\\s+goal))?\\s*[.!]?$");

    /**
     * The one new goal of a sentence whose words go on to add a task to it ({@link #ADD_CLAUSE}), when no task for the
     * new goal was proposed: the task joins the proposals, aimed at the new goal, and the goal keeps the words before
     * "and add" when the model's text holds the task's words too.
     */
    static void withAddClause(String said, List<Action> proposals) {
        List<Action> goals = proposals.stream().filter(a -> a.type() == ActionType.CREATE_GOAL).toList();
        if (goals.size() != 1 || goals.getFirst().text() == null || proposals.stream()
                .anyMatch(a -> a.type() == ActionType.ADD_TASK && a.goal() == null)) {
            return;
        }
        Matcher m = ADD_CLAUSE.matcher(goalWordsAsSaid(said));
        if (!m.matches()) {
            return;
        }
        Action goal = goals.getFirst();
        String task = m.group("task").strip();
        int at = proposals.indexOf(goal);
        if (goal.text().toLowerCase(Locale.ROOT).contains(task.toLowerCase(Locale.ROOT))) {
            proposals.set(at, withText(goal, m.group("goal").strip()));
        }
        proposals.add(at + 1, new Action(ActionType.ADD_TASK, null, null, capitalFirst(task), null, null, null, null,
                goal.reason(), task));
    }

    /** A request to see several goals: "show me the passport goal and the dentist goal", "how are X and Y going?". */
    private static final Pattern SHOW_SEVERAL = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:show(?:\\s+me)?|open"
            + "|how\\s+(?:are|is))\\s+(?<items>.+?)(?:\\s+(?:going|doing|coming\\s+along))?\\s*[?.!]?$");

    /**
     * A request to see several goals ({@link #SHOW_SEVERAL}) shows each goal one of its parts names clearly, when the
     * model proposed fewer: "show me the passport goal and the dentist goal" shows both. Returns the views it added.
     */
    private List<Action> showEach(String said, List<Action> proposals, Grounding.Snapshot snapshot) {
        Matcher m = SHOW_SEVERAL.matcher(said);
        if (!m.matches()) {
            return List.of();
        }
        List<String> goals = Arrays.stream(LIST_SEPARATOR.split(m.group("items")))
                .map(part -> targets.clearMatch(part.strip(), snapshot.documents()).orElse(null))
                .filter(Objects::nonNull).distinct().toList();
        long shown = proposals.stream().filter(a -> a.type() == ActionType.SHOW_GOAL).count();
        if (goals.size() < 2 || shown >= goals.size()) {
            return List.of();
        }
        proposals.removeIf(a -> a.type() == ActionType.SHOW_GOAL);
        List<Action> views = new ArrayList<>();
        for (String goal : goals) {
            views.add(new Action(ActionType.SHOW_GOAL, goal.substring(0, 8), null, null, null, null, null, null, null,
                    null));
        }
        proposals.addAll(views);
        return views;
    }

    /** Several things added to one goal: "add plates, napkins and a speaker to the barbecue". */
    private static final Pattern LIST_ADD = Pattern.compile("(?i)^\\s*(?:please\\s+|(?:can|could|would)\\s+you\\s+)?"
            + "add\\s+(?<items>.+?)\\s+(?:to|on|onto|in)\\s+(?:the\\s+|my\\s+)?(?<goal>[^,]+?)\\s*[.!?]?$");
    /** What separates the things of a list: commas and "and". */
    private static final Pattern LIST_SEPARATOR = Pattern.compile("(?i)\\s*,\\s*(?:and\\s+|or\\s+)?|\\s+and\\s+");

    /**
     * A sentence that adds several things to one goal ({@link #LIST_ADD}) gives one task per thing, aimed at the goal
     * its words name (else the one the model chose), unless the model proposed as many tasks itself. One thing added
     * to a goal the words name clearly is that goal's task (see {@link #oneAdded}). Each thing keeps the person's
     * words without a leading article ("a speaker" is "Speaker"). Returns the tasks, its own or the model's, whose
     * goal the words after "to" name clearly: their goal needs no other check.
     */
    private List<Action> listAdd(String said, List<Action> proposals, Grounding.Snapshot snapshot) {
        Matcher m = LIST_ADD.matcher(said);
        if (!m.matches()) {
            return List.of();
        }
        List<String> items = Arrays.stream(LIST_SEPARATOR.split(m.group("items"))).map(String::strip)
                .map(i -> i.replaceFirst("(?i)^(?:a|an|the|some)\\s+", "")).filter(i -> !i.isBlank()).toList();
        String phrase = m.group("goal").strip();
        Optional<String> named = items.isEmpty() ? Optional.empty() : targets.clearMatch(phrase, snapshot.documents());
        if (items.size() == 1 && named.isPresent()) {
            // Null when the model proposed several tasks or other changes: then nothing is added here.
            Action one = oneAdded(items.getFirst(), named.get(), phrase, proposals);
            return one == null ? List.of() : List.of(one);
        }
        if (items.size() < 2) {
            return List.of();
        }
        List<Action> proposedTasks = proposals.stream().filter(a -> a.type() == ActionType.ADD_TASK).toList();
        if (proposedTasks.size() >= items.size()) {
            // The model listed the things itself: as many tasks as things keep the person's words as titles, and its
            // tasks for the goal the words name need no other check.
            if (proposedTasks.size() == items.size()) {
                for (int i = 0; i < items.size(); i++) {
                    proposals.set(proposals.indexOf(proposedTasks.get(i)),
                            withText(proposedTasks.get(i), capitalFirst(items.get(i))));
                }
            }
            List<Action> tasks = proposals.stream().filter(a -> a.type() == ActionType.ADD_TASK).toList();
            return named.map(id -> tasks.stream().filter(a -> snapshot.goal(a.goal()).map(id::equals)
                    .orElse(false)).toList()).orElse(List.of());
        }
        Optional<String> goal = named.or(() -> proposals.stream().map(Action::goal).filter(Objects::nonNull)
                .map(g -> snapshot.goal(g).orElse(null)).filter(Objects::nonNull).findFirst());
        if (goal.isEmpty()) {
            return List.of();
        }
        proposals.clear();
        for (String item : items) {
            proposals.add(new Action(ActionType.ADD_TASK, goal.get().substring(0, 8), null, capitalFirst(item), null,
                    null, null, null, null, phrase));
        }
        return named.isPresent() ? List.copyOf(proposals) : List.of();
    }

    /**
     * "Add booking the park to the picnic": one thing added to the goal the words after "to" name clearly. The task
     * the model proposed keeps its date and takes the person's words as its title and that goal; with no task
     * proposed and no other change, it is added. Null when the model proposed several tasks or other changes.
     */
    private static Action oneAdded(String item, String goalId, String phrase, List<Action> proposals) {
        List<Action> adds = proposals.stream().filter(a -> a.type() == ActionType.ADD_TASK).toList();
        if (adds.size() > 1 || adds.isEmpty() && proposals.stream().anyMatch(a -> !a.type().readOnly())) {
            return null;
        }
        Action base = adds.isEmpty() ? null : adds.getFirst();
        Action task = new Action(ActionType.ADD_TASK, goalId.substring(0, 8), null, capitalFirst(item),
                base == null ? null : base.dueDate(), null, null, null, base == null ? null : base.reason(), phrase);
        if (base == null) {
            proposals.removeIf(a -> a.type().readOnly());
            proposals.add(task);
        } else {
            proposals.set(proposals.indexOf(base), task);
        }
        return task;
    }

    /** A request to add something to a goal used as a task's title: "Add sunscreen to the picnic". */
    private static final Pattern ADDED_THING = Pattern.compile("(?i)^\\s*(?:add|put|include)\\s+(?<thing>.+?)\\s+"
            + "(?:to|on|onto|in|into)\\s+(?<where>.+?)\\s*[.!]?$");

    /**
     * The thing a task title asks to add when the title is a request to add it to its own goal ("Add sunscreen to the
     * picnic" or "to picnic supplies" for "Plan the picnic" is "Sunscreen", "Put a blanket on the list" is "Blanket");
     * otherwise the title.
     */
    static String addedThing(String title, String goalText) {
        Matcher m = ADDED_THING.matcher(title == null ? "" : title);
        if (!m.matches() || goalText == null) {
            return title;
        }
        Set<String> where = TargetCheck.stems(m.group("where").replaceAll("(?i)\\b(?:the|my|our|goal|list|to-?do"
                + "|todo|it|this|that|supplies|items|things|stuff|shopping)\\b", " "));
        Set<String> goal = TargetCheck.stems(goalText);
        String thing = m.group("thing").strip().replaceFirst("(?i)^(?:a|an|the|some)\\s+", "");
        return goal.containsAll(where) && !thing.isBlank() ? capitalFirst(thing) : title;
    }

    /**
     * A new task's title without the words of its goal's name ("Book the park for the picnic" for "Plan the picnic"
     * is "Book the park for the"): words that name the goal say where the task goes, not what it is.
     */
    static String withoutGoalName(String title, String goalText) {
        if (title == null || goalText == null) {
            return title;
        }
        Set<String> goalStems = TargetCheck.stems(goalText);
        return Arrays.stream(title.split("\\s+"))
                .filter(w -> TargetCheck.stems(w).stream().noneMatch(goalStems::contains))
                .reduce((a, b) -> a + " " + b).orElse("");
    }

    /** Where one question ends and the next begins: "what should I do today and what's overdue?". */
    private static final Pattern NEXT_QUESTION = Pattern.compile("(?i)(?:\\s*[,;]\\s*(?:and\\s+)?"
            + "|\\s+and\\s+(?:also\\s+)?)"
            + "(?=(?:what|which|when|how|who|where|is|are|do|does|any|anything|show|list)\\b)");

    /** The questions of a sentence, cut before each question word that follows "and", a comma or ";". */
    static List<String> questions(String said) {
        return said == null ? List.of() : Arrays.stream(NEXT_QUESTION.split(said)).map(String::strip)
                .filter(s -> !s.isBlank()).toList();
    }

    /**
     * The question of a sentence with several that a list read of {@code type} answers: the first one not used yet
     * whose words ask for that list ({@link #listType}); the whole sentence when it holds one question or none fits.
     */
    private static String questionFor(ActionType type, List<String> parts, Set<String> used, String said) {
        if (parts.size() < 2) {
            return said;
        }
        for (String part : parts) {
            if (!used.contains(part) && listType(part, type) == type) {
                used.add(part);
                return part;
            }
        }
        return said;
    }

    /**
     * The words the agenda is cut by: with several questions that ask for different cuts of the agenda ("what should I
     * do today and what's overdue?"), the question that asks for none, so the whole agenda answers both (overdue
     * tasks show as overdue on it); otherwise {@code words}.
     */
    private static String agendaWords(ActionType read, String words, List<String> parts) {
        if (read != ActionType.SHOW_AGENDA || parts.size() < 2
                || parts.stream().map(TaskFilter::of).distinct().count() < 2) {
            return words;
        }
        return parts.stream().filter(p -> TaskFilter.of(p).isEmpty()).findFirst().orElse(words);
    }

    /** The goals the other changes of the turn are aimed at: "push the passport back and drop the appointment task". */
    private static Set<String> otherChangeGoals(Action change, List<Action> changes, Grounding.Snapshot snapshot) {
        Set<String> goals = new HashSet<>();
        for (Action other : changes) {
            if (other != change && other.goal() != null) {
                snapshot.goal(other.goal()).ifPresent(goals::add);
            }
        }
        return goals;
    }

    /** Where one instruction ends and the next begins: "and", "then", a comma or a semicolon before an action verb. */
    private static final Pattern NEXT_INSTRUCTION = Pattern.compile("(?i)(?:\\s*,\\s*(?:and\\s+|then\\s+)?|\\s*;\\s*"
            + "|\\s+and\\s+(?:then\\s+|also\\s+)?|\\s+then\\s+)(?=(?:please\\s+)?(?:create|add|move|push|pull|bring"
            + "|cancel|drop|remove|delete|mark|set|change|make|remind|start|finish|book|schedule|reschedule|postpone"
            + "|plan|put|show|tell|list|keep|retire|pause|resume|invite|pair|approve|reject|forget)\\b)");

    /** The instructions of a sentence, cut before each action verb that follows "and", "then", a comma or ";". */
    static List<String> instructions(String said) {
        return said == null ? List.of() : Arrays.stream(NEXT_INSTRUCTION.split(said)).map(String::strip)
                .filter(s -> !s.isBlank()).toList();
    }

    /**
     * The instruction of a sentence with several that an action belongs to: the one that holds the action's quote,
     * else the one sharing strictly the most words with {@code title} (its task's or its goal's), a tie going to the
     * one that starts with a verb of the action ("cancel the water bill goal and create a goal to pay the phone bill"
     * gives the cancellation its first part). Null when the sentence holds one instruction or none can be told apart.
     */
    static String instructionOf(String said, Action action, String title) {
        List<String> parts = instructions(said);
        if (parts.size() < 2) {
            return null;
        }
        String quote = action.evidence() == null ? "" : TargetCheck.normalise(action.evidence());
        if (!quote.isBlank()) {
            List<String> holding = parts.stream()
                    .filter(p -> (" " + TargetCheck.normalise(p) + " ").contains(" " + quote + " ")).toList();
            if (holding.size() == 1) {
                return holding.getFirst();
            }
        }
        if (title == null) {
            return null;
        }
        Set<String> titleWords = TargetCheck.stems(title);
        long most = parts.stream().mapToLong(p -> TargetCheck.stems(p).stream().filter(titleWords::contains).count())
                .max().orElse(0);
        if (most == 0) {
            return null;
        }
        List<String> best = parts.stream()
                .filter(p -> TargetCheck.stems(p).stream().filter(titleWords::contains).count() == most).toList();
        if (best.size() == 1) {
            return best.getFirst();
        }
        Pattern verbs = ACTION_VERBS.get(action.type());
        List<String> byVerb = verbs == null ? List.of()
                : best.stream().filter(p -> verbs.matcher(p).lookingAt()).toList();
        return byVerb.size() == 1 ? byVerb.getFirst() : null;
    }

    /** The verbs an instruction of each kind starts with. */
    private static final Map<ActionType, Pattern> ACTION_VERBS = Map.of(
            ActionType.CANCEL_GOAL, Pattern.compile("(?i)(?:please\\s+)?(?:cancel|drop|forget|delete|remove)\\b"),
            ActionType.CREATE_GOAL, Pattern.compile("(?i)(?:please\\s+)?(?:create|make|plan|start|set\\s+up)\\b"),
            ActionType.RESCHEDULE_GOAL, Pattern.compile("(?i)(?:please\\s+)?(?:move|push|pull|postpone|reschedule"
                    + "|bring)\\b"),
            ActionType.ADD_TASK, Pattern.compile("(?i)(?:please\\s+)?(?:add|include|put|remind)\\b"),
            ActionType.REMOVE_TASK, Pattern.compile("(?i)(?:please\\s+)?(?:drop|remove|delete)\\b"),
            ActionType.SET_TASK_STATUS, Pattern.compile("(?i)(?:please\\s+)?(?:mark|finish|start|set)\\b"),
            ActionType.UPDATE_TASK, Pattern.compile("(?i)(?:please\\s+)?(?:move|push|set|postpone|bring)\\b"));

    /** True when the turn sets two or more tasks to one and the same status. */
    static boolean sharedStatus(List<Action> changes) {
        List<TaskStatus> statuses = changes.stream().filter(a -> a.type() == ActionType.SET_TASK_STATUS)
                .map(Action::status).toList();
        return statuses.size() >= 2 && statuses.stream().distinct().count() == 1 && statuses.getFirst() != null;
    }

    /**
     * A new goal's text without the clause that asks for one of its tasks: "plan a picnic on Saturday and add buying a
     * cake to it" with the task "Buy a cake" is "plan a picnic on Saturday". The text is cut at the last " and " after
     * which a word of a task's title follows, so "plan the wedding and honeymoon and add booking flights" keeps "and
     * honeymoon"; it stays whole when there is none.
     */
    static String withoutTaskClause(String text, List<Action> tasks) {
        if (text == null || tasks.isEmpty()) {
            return text;
        }
        Set<String> taskWords = new HashSet<>();
        tasks.forEach(t -> taskWords.addAll(TargetCheck.stems(t.text())));
        Matcher and = Pattern.compile("(?i),?\\s+and\\s+").matcher(text);
        int cut = -1;
        while (and.find()) {
            boolean taskFollows = TargetCheck.stems(text.substring(and.end())).stream().anyMatch(taskWords::contains);
            if (and.start() > 0 && taskFollows) {
                cut = and.start();
            }
        }
        return cut < 0 ? text : text.substring(0, cut).strip();
    }

    /**
     * Asks about the tasks the person asked for on the goal the sentence creates ("I'm going on a business trip and
     * need a task for today: a checklist of what to take"): with the goal's own question when it is asked, so one yes
     * creates the goal and adds them; on their own question, aimed at the new goal, when it was created unasked; and
     * not at all, said so, when no goal was created.
     */
    private void askNewGoalTasks(List<Action> tasks, List<Action> toConfirm, List<String> questions,
                                 List<Executed> executed) {
        boolean asked = toConfirm.stream().anyMatch(a -> a.type() == ActionType.CREATE_GOAL);
        Optional<String> created = executed.stream().filter(e -> e != null && e.ok()
                && e.type() == ActionType.CREATE_GOAL && e.goalId() != null).map(Executed::goalId).findFirst();
        for (Action proposed : tasks) {
            Action task = withText(proposed, capitalFirst(proposed.text()));
            String what = "the task \"" + task.text() + "\"" + (task.dueDate() == null ? "" : " due " + task.dueDate());
            if (asked) {
                toConfirm.add(task);
                questions.add("And add " + what + " to it?");
            } else if (created.isPresent()) {
                toConfirm.add(new Action(ActionType.ADD_TASK, created.get(), null, task.text(), task.dueDate(), null,
                        null, null, task.reason(), task.evidence()));
                questions.add("Did you mean: add " + what + " to the new goal?");
            } else {
                executed.add(new Executed(ActionType.ADD_TASK, false, what + " was not added: no new goal was created",
                        null, null));
            }
        }
    }

    /**
     * The one goal a list showed, when every goal and task on it belongs to that goal: after "what should I do today?"
     * lists only tasks of one goal, "I did it" may refer to that goal (see {@link TargetCheck}). Empty when the list
     * showed nothing or more than one goal.
     */
    static Optional<String> onlyGoalShown(ActionExecutor.Shown shown) {
        Set<String> goals = new HashSet<>();
        shown.goals.forEach(g -> goals.add(g.id()));
        shown.agenda.forEach(a -> goals.add(a.goalId()));
        return goals.size() == 1 ? Optional.ofNullable(goals.iterator().next()) : Optional.empty();
    }

    /** The model's output read as an interpretation; null when it has the wrong shape. */
    private static Conversation.Interpretation interpretation(JsonNode output) {
        if (output == null || output.isNull()) {
            return null;
        }
        try {
            return Json.MAPPER.treeToValue(output, Conversation.Interpretation.class);
        } catch (RuntimeException e) {
            log.warn("unreadable interpretation: {}", e.getMessage());
            return null;
        }
    }

    /** The longest sentence the kernel reads, in characters. */
    public static final int MAX_SENTENCE = 2000;
    static final String TOO_LONG = "That is too long for one sentence: say it in at most " + MAX_SENTENCE
            + " characters.";
    static final String NO_ASSISTANT = "No assistant is connected right now, so I cannot understand sentences. If you "
            + "have just admitted it, try again in a few seconds; otherwise start it with: docker compose up -d "
            + "agent-assistant";
    /**
     * Said when an agent offering the interpreter waits for the owner's admission. It names no key: whoever holds the
     * agents' credentials can ask to join under any name, so the owner compares the key shown when admitting with the
     * one in the assistant's own container log.
     */
    static final String WAITING_FOR_ADMISSION = "An assistant is waiting for your yes, and until then no sentence is "
            + "understood. Run the CLI without a sentence (fabric, or docker compose run --rm cli), compare the key it "
            + "shows with the one in the assistant's container log, and admit it.";
    static final String TIMED_OUT = "The assistant did not answer in time; its language model may be busy or still "
            + "loading. Try again in a moment.";
    static final String UNREACHABLE = "I could not reach the assistant or its language model. Check that "
            + "agent-assistant and the model server are running (docker compose ps), then try again.";
    static final String NO_GOAL = "I could not find a goal matching your words. Say its name as it appears in your "
            + "list (\"list my goals\"), or describe a new goal to create it.";
    static final String UNCLEAR = "Sorry, I could not work out what you meant this time. Try saying it another way, "
            + "in English.";

    /** The outcomes of a turn with every refusal said once: seven goals refused for the same words give one line. */
    static List<Executed> withoutRepeatedRefusals(List<Executed> executed) {
        Set<String> seen = new HashSet<>();
        return executed.stream().filter(e -> e == null || e.ok() || seen.add(e.type() + "\n" + e.message())).toList();
    }
    /** Said when the call to the assistant failed on the kernel's side (its broker connection dropped meanwhile). */
    static final String INTERRUPTED = "My connection to the assistant was interrupted while it read your sentence, so "
            + "nothing was changed. Try again in a moment.";

    /**
     * Why a sentence was not understood, in words the person can act on: no assistant (or one waiting for
     * admission, {@code waiting} saying so; null when none waits), the kernel's own connection to it interrupted
     * (the call "failed on the kernel's side", see {@link CapabilityInvoker.Invocation#kernelSide}), the assistant or
     * its model not answering, or a sentence that was genuinely unclear.
     */
    static String interpretationProblem(String reason, String waiting) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (r.startsWith("the call failed on the kernel's side")) {
            return INTERRUPTED;
        }
        if (r.contains("no live provider")) {
            return waiting != null ? waiting : NO_ASSISTANT;
        }
        if (r.contains("timed out") || r.contains("timeout")) {
            return TIMED_OUT;
        }
        if (r.contains("no responder") || r.contains("connection refused") || r.contains("unreachable")
                || r.contains("i/o error") || r.contains("unknown host") || r.contains("unknownhost")
                || r.contains("failed to connect") || r.contains("connectexception")) {
            return UNREACHABLE;
        }
        return r.contains("needs a goal id") ? NO_GOAL : UNCLEAR;
    }

    /**
     * What to tell the owner when an agent offering the interpreter waits for their yes (see
     * {@link SecurityService#waitingFor}): it asked to join for the first time, or it is admitted but asks for terms
     * its admission does not cover (after an upgrade changed what the kernel gives it); empty when none waits, so an
     * admitted assistant that is simply stopped is told as not running. The words name no key and no agent (see
     * {@link #WAITING_FOR_ADMISSION}), however many wait.
     */
    private Optional<String> waitingInterpreter() {
        return security.waitingFor(INTERPRETER).isEmpty() ? Optional.empty() : Optional.of(WAITING_FOR_ADMISSION);
    }

    /**
     * The agents waiting for admission that the words name: by the key id the model gave, by the provider name, or
     * by the words of the sentence ("admit the estimator").
     */
    private List<SecurityService.PendingAdmission> waitingAgents(Action action, String said) {
        Map<String, SecurityService.PendingAdmission> found = new LinkedHashMap<>();
        for (String ref : Arrays.asList(action.approval(), action.text())) {
            if (ref != null && !ref.isBlank()) {
                security.pendingAdmission(ref.strip()).ifPresent(p -> found.put(p.keyId(), p));
            }
        }
        if (found.isEmpty()) {
            for (SecurityService.Holder h : security.match(action.text() != null ? action.text() : said)) {
                if (h.label().startsWith("agent ")) {
                    security.pendingAdmission(h.keyId()).ifPresent(p -> found.put(p.keyId(), p));
                }
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * What admitting an agent means: who it is, what it offers (each capability with the name it gives itself, which
     * the interpreter is shown too, see {@code SecurityService.PendingAdmission.offers}), what the kernel gives the
     * capabilities it feeds (the person's words and data in the call itself, in the words of
     * {@link Contract#KERNEL_SUPPLIED}), what it reads, what it may write, what every admitted agent sees beyond that
     * ({@link Contract#STRUCTURE_NOTE}), as the inbox's admission text says too, and the side effects it has outside
     * the person's goals. The names come from the agent, so each is shown on one line.
     */
    static String admissionQuestion(SecurityService.PendingAdmission agent) {
        List<String> produces = names(agent.produces());
        List<String> reads = names(agent.reads());
        List<String> sideEffects = names(agent.sideEffects());
        List<String> supplied = names(agent.kernelSupplied());
        return "Admit the agent \"" + Grounding.oneLine(agent.provider()) + "\" (key " + fingerprint(agent.keyId())
                + ")? It offers " + String.join(", ", names(agent.offers()))
                + (supplied.isEmpty() ? ", " : "; the kernel gives it " + String.join("; ", supplied) + "; it ")
                + (reads.isEmpty() ? "reads nothing" : "reads " + String.join(", ", reads))
                + (produces.isEmpty() ? " and writes nothing" : " and may write " + String.join(", ", produces))
                + "; " + Contract.STRUCTURE_NOTE + "."
                + (sideEffects.isEmpty() ? "" : " Side effects outside your goals: " + String.join(", ", sideEffects)
                + ".");
    }

    private static List<String> names(List<String> names) {
        return names == null ? List.of() : names.stream().map(Grounding::oneLine).toList();
    }

    /** A key id in the dashed form devices show: 3f9a11c2... becomes 3F9A-11C2-.... */
    static String fingerprint(String keyId) {
        String id = keyId == null ? "" : keyId.toUpperCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < id.length(); i += 4) {
            if (i > 0) {
                sb.append('-');
            }
            sb.append(id, i, Math.min(id.length(), i + 4));
        }
        return sb.toString();
    }

    /**
     * A guest's replies show only the goals shared with them, and of those neither the owner's pace (the calibration
     * annotations estimates carry, see {@link GuestView#CALIBRATION}) nor the plan runs with their steps and providers.
     */
    static void visibleOnly(ActionExecutor.Shown shown, SecurityService.Caller caller) {
        shown.goals.removeIf(g -> !caller.seesGoal(g.id()));
        shown.details.removeIf(d -> !caller.seesGoal(d.goal().id()));
        shown.details.replaceAll(ConversationService::forGuest);
        shown.agenda.removeIf(a -> !caller.seesGoal(a.goalId()));
        shown.decisions.clear();
        shown.approvals.clear();
        shown.capabilities.clear();
        shown.messages.clear();
        shown.rules.clear();
        shown.profile = null;
    }

    /**
     * A goal's details as a guest sees them (see {@link GuestView#details}): no calibration annotations on the goal or
     * its tasks, and no runs. The goal query applies the same filter.
     */
    static KernelApi.GoalDetails forGuest(KernelApi.GoalDetails d) {
        return GuestView.details(d);
    }

    /** Sessions belong to keys: nobody can continue, or read back, another device's conversation. */
    private static String sessionKey(SecurityService.Caller caller, String sessionId) {
        String id = sessionId == null || sessionId.isBlank() ? "default" : sessionId;
        return caller.keyId() == null ? id : caller.keyId() + ":" + id;
    }

    private static Action withIds(Action a, String goalId, String approvalId) {
        return new Action(a.type(), goalId, a.task(), a.text(), a.dueDate(), a.status(), a.hours(), approvalId,
                a.reason(), a.evidence());
    }

    private static Action withGoal(Action a, String goal) {
        return new Action(a.type(), goal, a.task(), a.text(), a.dueDate(), a.status(), a.hours(), a.approval(),
                a.reason(), a.evidence());
    }

    private static Action withTask(Action a, String task) {
        return new Action(a.type(), a.goal(), task, a.text(), a.dueDate(), a.status(), a.hours(), a.approval(),
                a.reason(), a.evidence());
    }

    private static Action withDue(Action a, LocalDate due) {
        return new Action(a.type(), a.goal(), a.task(), a.text(), due, a.status(), a.hours(), a.approval(),
                a.reason(), a.evidence());
    }

    private static Action withText(Action a, String text) {
        return new Action(a.type(), a.goal(), a.task(), text, a.dueDate(), a.status(), a.hours(), a.approval(),
                a.reason(), a.evidence());
    }

    /** Goal changes whose own words are checked word by word before they are applied unasked (see PlainWords). */
    static final Set<ActionType> GOAL_PLAIN = Set.of(ActionType.RESCHEDULE_GOAL, ActionType.REFINE_GOAL,
            ActionType.REOPEN_GOAL, ActionType.ADD_TASK);

    /**
     * The changes a sentence may apply without a question, each judged on the words of its own part (see
     * {@link PlainWords#plain}; a new goal on its words as a whole). A sentence with one of them applies its changes
     * only when the whole sentence is plain (see {@link PlainWords#wholePlain}).
     */
    static final Set<ActionType> PLAIN_JUDGED = Set.of(ActionType.SET_TASK_STATUS, ActionType.UPDATE_TASK,
            ActionType.RESCHEDULE_GOAL, ActionType.REFINE_GOAL, ActionType.REOPEN_GOAL, ActionType.ADD_TASK,
            ActionType.CREATE_GOAL);

    /** Words that speak of a step that waits for approval: "the calendar step", "the approval", "the run". */
    static final Pattern STEP_WORDS = Pattern.compile("\\b(?:steps?|approvals?|runs?)\\b");

    /**
     * A change ready to apply that waits until the whole sentence is judged: the action, its goal and approval, the
     * note its outcome carries, and where its outcome stands among the turn's outcomes.
     */
    record Waiting(Action action, String goalId, String approvalId, String dateNote, int at) {
    }

    /**
     * The note of a date the kernel read simply from the words while the model gave none, for the question that asks
     * about it: two readers must agree for a date to be applied without a question.
     */
    static final String NO_MODEL_DATE = " (the assistant gave no date)";

    /**
     * The task a task change lands on, chosen from the person's words before anything else is read for it: the goal's
     * state, the task, whether the words leave it unsure (they name another task than the model's, or do not point at
     * the model's), and the refusal when no such task exists (then the rest is null).
     */
    record TaskChoice(GoalState state, TaskView task, boolean unsure, Executed refusal) {
    }

    /**
     * The task a change of {@code action}'s task on {@code goalId} lands on: the one task key the person typed when
     * it is not the model's ("mark T4 done" when the model chose T3), else the model's task; when the person typed no
     * key and the words do not point at the model's task, the one other task they clearly point at, if any, unless
     * another change of the turn is aimed at that task ({@code targeted}, "goal/task"). Unsure in both cases, so the
     * change is asked about, unless the words say that other task's whole title and less than half of the model's
     * ("the speaker will take about an hour" read as T14 "Get ice"). The model's task is sure when the words say its
     * whole title and no other task's ("I'm blocked on the speaker, the shop is closed"). "No need for the napkins"
     * names the napkins for a removal. With several changes in the sentence, the words that point at a task are the
     * change's own part (see {@link #taskWords}, else the one instruction that names no other change's task):
     * "I collected the images and organized the image files" grounds the first change on "I collected the images".
     * Refused when no task matches.
     */
    private TaskChoice chooseTask(Action action, String goalId, String said, Grounding.Snapshot snapshot,
                                  List<Action> changes, Set<String> targeted, Set<String> recent) {
        ActionType type = action.type();
        GoalState state = ledger.load(goalId).orElse(null);
        Optional<TaskView> task = state == null ? Optional.empty() : state.task(action.task());
        boolean unsure = false;
        Set<String> named = namedKeys(TargetCheck.withoutNegated(said));
        if (!named.isEmpty() && (task.isEmpty() || !named.contains(task.get().key()))) {
            // The person typed another key than the model chose: ask about the task the person named.
            List<TaskView> typed = named.stream()
                    .map(k -> state == null ? Optional.<TaskView>empty() : state.task(k))
                    .flatMap(Optional::stream).toList();
            if (typed.isEmpty()) {
                return new TaskChoice(state, null, true, noTask(action, String.join(", ", named), state, goalId, said,
                        snapshot, recent));
            }
            if (typed.size() == 1) {
                task = Optional.of(typed.getFirst());
            }
            unsure = true;
        }
        if (task.isEmpty()) {
            return new TaskChoice(state, null, true, noTask(action, action.task(), state, goalId, said, snapshot,
                    recent));
        }
        String words = said;
        String key = task.get().key();
        boolean sharedKey = changes.stream().anyMatch(c -> c.type() != type && TASK_ACTIONS.contains(c.type())
                && c.task() != null && c.task().strip().equalsIgnoreCase(key)
                && goalId.equals(snapshot.goal(c.goal()).orElse(null)));
        String kindPart = named.isEmpty() && sharedKey ? partOfKind(said, type) : null;
        if (kindPart != null) {
            // "Mark the napkins done and move the speaker to Friday" read as two changes of T12 "Speaker": each change
            // reads the instruction of its own kind.
            words = kindPart;
        } else if (named.isEmpty() && changes.size() > 1) {
            String own = taskWords(said, task.get(), state, changes, snapshot);
            words = own != null ? own : untargetedPart(said, state, goalId, targeted, key);
        }
        if (type == ActionType.REMOVE_TASK) {
            words = NO_NEED.matcher(words).replaceAll("drop"); // "no need for napkins" names the napkins
        }
        String taskSaid = words;
        TaskView chosen = task.get();
        // The words say the model's task's whole title and no other task's: it is the task meant.
        boolean onlyWhole = saysWhole(taskSaid, chosen.title(), state.text()) && state.tasks().stream()
                .filter(t -> !t.key().equals(chosen.key()))
                .noneMatch(t -> saysWhole(taskSaid, t.title(), state.text()));
        if (named.isEmpty() && !onlyWhole && !targets.taskGrounded(words, action.evidence(), state.text(),
                state.tasks(), task.get())) {
            log.info("ungrounded {}: {} \"{}\" for \"{}\"", type, task.get().key(), task.get().title(), said);
            unsure = true;
            // When the words clearly point at one other task that no other change of the turn is aimed at, the
            // question offers that task.
            Optional<TaskView> likelier = targets.likelierTask(words, action.evidence(), state.text(), state.tasks(),
                    task.get());
            if (likelier.isPresent() && !targeted.contains(goalId + "/" + likelier.get().key()
                    .toUpperCase(Locale.ROOT))) {
                // The words say that task's whole title and little of the model's: the person named it, and the
                // model's key was a slip ("the speaker will take about an hour" read as T14 "Get ice").
                Set<String> stated = TargetCheck.stems(words);
                Set<String> modelTitle = TargetCheck.stems(task.get().title());
                unsure = !(saysWhole(words, likelier.get().title(), state.text())
                        && modelTitle.stream().filter(stated::contains).count() * 2 < modelTitle.size());
                task = likelier;
            }
        }
        return new TaskChoice(state, task.get(), unsure, null);
    }

    /**
     * The one instruction of the sentence that names no task another change of the turn is aimed at ("mark the napkins
     * done" of "mark the napkins done and move the speaker to Friday" for a change the model aimed at a wrong key):
     * the words for {@code key}'s change; the whole sentence when there is not exactly one.
     */
    private static String untargetedPart(String said, GoalState state, String goalId, Set<String> targeted,
                                         String key) {
        List<Set<String>> others = state.tasks().stream()
                .filter(t -> !t.key().equalsIgnoreCase(key) && targeted.contains(goalId + "/"
                        + t.key().toUpperCase(Locale.ROOT)))
                .map(t -> TargetCheck.stems(t.title())).toList();
        List<String> free = instructions(said).stream().filter(part -> {
            Set<String> words = TargetCheck.stems(part);
            return others.stream().noneMatch(title -> !title.isEmpty() && words.containsAll(title));
        }).toList();
        return instructions(said).size() > 1 && free.size() == 1 ? free.getFirst() : said;
    }

    /** Words that state a task's status: "done", "finished", "blocked", "in progress", "started". */
    private static final Pattern STATUS_PART = Pattern.compile("(?i)\\b(?:done|finished|completed?|blocked|stuck"
            + "|in\\s+progress|started|to-?do|sorted)\\b");

    /**
     * The one instruction of a sentence that asks for a change of {@code type}: the one stating a status for a status
     * change, a date or an amount of time for a task update, a drop for a removal; null when not exactly one does.
     */
    private static String partOfKind(String said, ActionType type) {
        List<String> parts = instructions(said);
        List<String> fit = parts.stream().filter(part -> switch (type) {
            case SET_TASK_STATUS -> STATUS_PART.matcher(part).find();
            case UPDATE_TASK -> !DateExpressions.datePhraseSpans(part).isEmpty() || Durations.states(part);
            case REMOVE_TASK -> NO_NEED.matcher(part).find() || CHANGE_REQUEST.matcher(part).lookingAt()
                    && part.toLowerCase(Locale.ROOT).matches("\\s*(?:please\\s+)?(?:drop|remove|delete|forget)\\b.*");
            default -> false;
        }).toList();
        return parts.size() > 1 && fit.size() == 1 ? fit.getFirst() : null;
    }

    /** Words that drop something by saying it is not needed: "no need for", "we don't need", "no longer need". */
    private static final Pattern NO_NEED = Pattern.compile("(?i)\\b(?:no\\s+(?:more\\s+)?need\\s+(?:for|of)"
            + "|(?:i|we)?\\s*(?:don'?t|do\\s+not|no\\s+longer)\\s+need(?:\\s+(?:the|any))?)\\b");

    /** True when the words say every word of a task's title that its goal's text does not hold ("the speaker"). */
    private static boolean saysWhole(String words, String title, String goalText) {
        Set<String> said = TargetCheck.stems(words);
        Set<String> own = TargetCheck.stems(title);
        own.removeAll(TargetCheck.stems(goalText));
        return !own.isEmpty() && said.containsAll(own);
    }

    /**
     * The refusal for a task change whose task {@code keys} the goal does not have: it names the goal only when the
     * words ground it ("there is no task T99 in "Plan a picnic in the park""); otherwise the goal the model chose may
     * not be the one meant, and the person is asked which goal they mean (see {@link #whichGoal}).
     */
    private Executed noTask(Action action, String keys, GoalState state, String goalId, String said,
                            Grounding.Snapshot snapshot, Set<String> recent) {
        TargetCheck.Result check = targets.check(said, action.evidence(), goalId, snapshot.documents(), recent);
        if (state != null && check.grounded()) {
            return new Executed(action.type(), false, "there is no task " + keys + " in \"" + state.text() + "\"",
                    goalId, null);
        }
        return new Executed(action.type(), false, whichGoal(said, check.reason(), snapshot.documents())
                + "; the goal the assistant chose has no task " + keys, null, null);
    }

    /**
     * The part of the sentence that asks for a change of {@code task}: the task's own part (see
     * {@link ActionDates#taskPart}); with several changes in the sentence, the parts of its goal changes are left out
     * first ("move the picnic to Friday and mark T3 done" gives T3 "mark T3 done"). The part is the sentence with the
     * rest blanked, every character in its place. Null when no part can be told apart.
     */
    private String taskWords(String said, TaskView task, GoalState state, List<Action> changes,
                             Grounding.Snapshot snapshot) {
        String words = changes.size() > 1 ? withoutGoalParts(said, changes, snapshot) : said;
        ActionDates.TaskPart part = ActionDates.taskPart(words, new ActionDates.TaskName(task.key(), task.title()),
                state.text(), otherTasks(state, task.key()));
        return part == null ? null : part.text();
    }

    /**
     * The part of the sentence that asks for a goal change, a new task or a new goal: the whole sentence when it asks
     * for one change; with several, the clause that shares the most words with the goal's text (with a new task's
     * title for a new task, the new goal's text for a new goal), leaving out first every clause that names the task of
     * one of the sentence's task changes (see {@link ActionDates#clause}); null when no clause can be told apart. The
     * part is the sentence with the rest blanked, every character in its place.
     */
    static String goalActionWords(Action action, String goalText, String said, List<Action> changes) {
        if (changes.size() <= 1 || said == null) {
            return said;
        }
        Set<String> keys = new HashSet<>();
        changes.stream().filter(c -> TASK_ACTIONS.contains(c.type()) && c.task() != null)
                .forEach(c -> keys.add(c.task().strip().toUpperCase(Locale.ROOT)));
        String rest = keys.isEmpty() ? said
                : ActionDates.blankClauses(said, clause -> namedKeys(clause).stream().anyMatch(keys::contains));
        String title = action.type() == ActionType.ADD_TASK || action.type() == ActionType.CREATE_GOAL
                ? action.text() : goalText;
        int[] span = ActionDates.clauseSpan(rest, null, title);
        if (span == null || span[0] >= span[1] || rest.isBlank()) {
            return null;
        }
        return ActionDates.mask(rest, span[0], span[1]);
    }

    /**
     * The words a change is read from and judged on: for a task change its task's own part (see {@link #taskWords}),
     * for a goal change, a new task or a new goal its own part (see {@link #goalActionWords}), else the sentence;
     * null when its part cannot be told apart. The date of the change comes from these words alone, never from
     * another part of the sentence.
     */
    private String ownWords(Action action, String goalId, TaskChoice choice, String said, List<Action> changes,
                            Grounding.Snapshot snapshot) {
        if (choice != null && TASK_ACTIONS.contains(action.type())) {
            return taskWords(said, choice.task(), choice.state(), changes, snapshot);
        }
        if (action.type() == ActionType.CREATE_GOAL || action.type().targetsGoal() && !action.type().readOnly()) {
            return goalActionWords(action, goalText(goalId, snapshot), said, changes);
        }
        return said;
    }

    /**
     * True when the words of a change's own part ({@code own}) are plain for it (see {@link PlainWords#plain}): a task
     * change judged against its task, a goal change or a new task against its goal; a new task's title must be one
     * plain statement too (see {@link PlainWords#plainStatement}: "call Anna, cancel that" is not). A new goal is
     * plain only when it is the sentence's only change, its words are one plain statement (one sentence, no negation,
     * retraction, correction, condition or hedge anywhere, only Latin script) and the model's text repeats the goal's
     * words word for word (see {@link #goalWords(String, LocalDate)}); its deadline is judged apart (see
     * {@link ActionDates#strictDeadline}). Every other change is not judged plain, and a part that cannot be told
     * apart ({@code own} null) is not plain.
     */
    private boolean plainFor(Action action, String goalId, TaskChoice choice, String own, boolean alone,
                             Grounding.Snapshot snapshot) {
        ActionType type = action.type();
        if (choice != null && TASK_ACTIONS.contains(type)) {
            if (type == ActionType.REMOVE_TASK) {
                return false;
            }
            PlainWords.Target target = new PlainWords.Target(choice.state().text(), goalId, choice.task().title(),
                    null);
            return PlainWords.partsStandAlone(own)
                    && PlainWords.plain(type, action.status(), own, target, properties.today());
        }
        if (GOAL_PLAIN.contains(type)) {
            PlainWords.Target target = new PlainWords.Target(goalText(goalId, snapshot), goalId, null,
                    type == ActionType.ADD_TASK ? action.text() : null);
            return PlainWords.partsStandAlone(own) && PlainWords.plain(type, null, own, target, properties.today())
                    && (type != ActionType.ADD_TASK || PlainWords.plainStatement(action.text()));
        }
        return type == ActionType.CREATE_GOAL && alone && own != null && PlainWords.plainStatement(own)
                && (sameWords(action.text(), goalWords(own, properties.today()))
                || sameWords(action.text(), goalWordsAsSaid(own)));
    }

    /** The word right before a date phrase that puts it in place: "until December 4", "on Friday", "by the 4th". */
    private static final Pattern DATE_MARKER_BEFORE = Pattern.compile("(?i)\\b(?:until|till|by|on|for|before|after"
            + "|in|from|at|due|this|next|around|every|each)\\s+$");

    /**
     * A new task's title that keeps every date phrase of its words ({@code words}, its own part of the sentence)
     * besides a strict deadline on {@code today} (see {@link ActionDates#strictDeadline}) and besides a date phrase of
     * its goal's text ({@code goalText}): each phrase the title leaves out is added at its end with the word that puts
     * it in place, so "hold off on buying chairs until December 4" titled "hold off on buying chairs" keeps "until
     * December 4" and "call Anna on Friday" keeps "on Friday". The title itself when it leaves out none.
     */
    static String titleWithDates(String title, String words, String goalText, LocalDate today) {
        if (title == null || words == null) {
            return title;
        }
        String text = TargetCheck.straightQuotes(words);
        int[] strict = ActionDates.strictDeadline(text, today) ? ActionDates.trailingDeadline(text).orElse(null) : null;
        String inTitle = " " + TargetCheck.normalise(title) + " ";
        String inGoal = " " + TargetCheck.normalise(goalText) + " ";
        StringBuilder kept = new StringBuilder(title.strip());
        for (DateExpressions.Span span : DateExpressions.datePhraseSpans(text)) {
            if (strict != null && span.start() >= strict[0] && span.end() <= strict[1]) {
                continue;
            }
            String phrase = TargetCheck.normalise(span.in(text));
            if (phrase.isEmpty() || inTitle.contains(" " + phrase + " ") || inGoal.contains(" " + phrase + " ")) {
                continue;
            }
            Matcher marker = DATE_MARKER_BEFORE.matcher(text.substring(Math.max(0, span.start() - 12), span.start()));
            int from = marker.find() ? Math.max(0, span.start() - 12) + marker.start() : span.start();
            kept.append(' ').append(text.substring(from, span.end()).strip());
        }
        return kept.toString();
    }

    /** " for T3 "Reserve the spot"" when a change lands on a task, so the reply names it; empty otherwise. */
    private static String forTask(TaskChoice choice) {
        return choice == null || choice.task() == null ? ""
                : " for " + choice.task().key() + " \"" + choice.task().title() + "\"";
    }

    /** True when two texts hold the same words in the same order, case, accents and punctuation aside. */
    static boolean sameWords(String a, String b) {
        String x = TargetCheck.normalise(a);
        return !x.isEmpty() && x.equals(TargetCheck.normalise(b));
    }

    /** The text of the goal {@code goalId}, from the ledger or else the snapshot; null without a goal. */
    private String goalText(String goalId, Grounding.Snapshot snapshot) {
        if (goalId == null) {
            return null;
        }
        return ledger.load(goalId).map(GoalState::text).orElseGet(() -> snapshot.documents()
                .getOrDefault(goalId, List.of()).stream().findFirst().orElse(null));
    }

    /** The sentence with the own part of each of its goal changes (see {@link #goalActionWords}) blanked. */
    private static String withoutGoalParts(String said, List<Action> changes, Grounding.Snapshot snapshot) {
        String words = said;
        for (Action change : changes) {
            if (TASK_ACTIONS.contains(change.type())) {
                continue;
            }
            String goalId = snapshot.goal(change.goal()).orElse(null);
            String goalText = goalId == null ? null
                    : snapshot.documents().getOrDefault(goalId, List.of()).stream().findFirst().orElse(null);
            String part = goalActionWords(change, goalText, said, changes);
            if (part != null && !part.equals(said) && part.length() == words.length()) {
                StringBuilder blanked = new StringBuilder(words);
                for (int k = 0; k < Math.min(part.length(), blanked.length()); k++) {
                    if (part.charAt(k) != ' ') {
                        blanked.setCharAt(k, ' ');
                    }
                }
                words = blanked.toString();
            }
        }
        return words;
    }

    /**
     * Words with which a person asks for a goal to be planned again: "replan", "refine", "a new plan", "update the
     * plan", "rethink", "reorganise", "break it down", "more tasks", "more detail", "smaller steps", and "plan ...
     * again" (see {@link #replanSpans}).
     */
    static final Pattern REPLAN_WORDS = Pattern.compile("\\b(?:re-?plan\\w*|refine\\w*|plan\\s+again"
            + "|new plan|(?:update|redo|rework|change|improve|rethink|revise)\\s+(?:the\\s+|its\\s+|my\\s+"
            + "|this\\s+|that\\s+)?plan|re-?think\\w*|re-?organi[sz]\\w*|break\\s+(?:it|this|that)\\s+down|more tasks"
            + "|more detail\\w*|smaller (?:tasks|steps))\\b");
    /** "Plan ... again" with the words between: "plan the QA r10 regrout goal again". */
    private static final Pattern PLAN_AGAIN = Pattern.compile("\\bplan\\s+((?:[\\w'-]+\\s+){1,12}?)again\\b");

    /**
     * Where the words that ask for a new plan stand in {@code lower} (lower case), as {start, end} pairs: each match
     * of {@link #REPLAN_WORDS}, and the "plan" and the "again" of "plan ... again" when every word between them names
     * the goal ({@code goalText}, its id, "goal") or is a function word ("plan the picnic again", "plan the QA r10
     * regrout goal again"); the goal's words between them are no request and are left out. Empty when the words ask
     * for no new plan.
     */
    static List<int[]> replanSpans(String lower, String goalText) {
        List<int[]> spans = new ArrayList<>();
        if (lower == null) {
            return spans;
        }
        Matcher m = REPLAN_WORDS.matcher(lower);
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
        }
        Set<String> goalStems = TargetCheck.stems(goalText == null ? "" : goalText);
        Set<String> goalWordSet = new HashSet<>(Arrays.asList(
                TargetCheck.normalise(goalText == null ? "" : goalText).split(" ")));
        Matcher again = PLAN_AGAIN.matcher(lower);
        while (again.find()) {
            boolean goalWords = Arrays.stream(again.group(1).strip().split("\\s+")).allMatch(w ->
                    PlainWords.FUNCTION.contains(w) || w.equals("goal") || goalWordSet.contains(w)
                            || goalStems.contains(TargetCheck.stem(w)));
            if (goalWords) {
                spans.add(new int[]{again.start(), again.start() + 4});
                spans.add(new int[]{again.end() - 5, again.end()});
            }
        }
        return spans;
    }

    /** True when the words ask for a new plan of the goal whose text is {@code goalText} (see {@link #replanSpans}). */
    static boolean asksReplan(String said, String goalText) {
        return said != null && !replanSpans(TargetCheck.lowerSameLength(said), goalText).isEmpty();
    }

    /**
     * The words with those that ask for a new plan blanked (see {@link #replanSpans}), every character in its place.
     */
    static String withoutReplanWords(String said, String goalText) {
        if (said == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(said);
        for (int[] span : replanSpans(TargetCheck.lowerSameLength(said), goalText)) {
            for (int k = span[0]; k < Math.min(span[1], out.length()); k++) {
                out.setCharAt(k, ' ');
            }
        }
        return out.toString();
    }

    /** Words that ask to change a date that is already set. */
    private static final Pattern CHANGE_WORDS = Pattern.compile("\\b(?:move\\w*|push\\w*|postpon\\w*|delay\\w*"
            + "|reschedul\\w*|shift\\w*|bring\\w*|pull\\w*|extend\\w*|chang\\w*|set|make it|later|earlier"
            + "|sooner|deadline|due date|instead|back|forward|new date|date to|moved)\\b");

    /**
     * True when a sentence reads like a new goal rather than a change of an existing one: it starts with a request
     * to create a goal ("new goal: ..."), or it gives a date with no word asking to change one ("QA bake sale for
     * the school, due next Friday").
     */
    static boolean createLike(String said) {
        String t = said == null ? "" : said.strip();
        if (GOAL_COMMAND.matcher(t).lookingAt() && !GOAL_COMMAND.matcher(t).replaceFirst("").isBlank()) {
            return true;
        }
        return !CHANGE_WORDS.matcher(t.toLowerCase(Locale.ROOT)).find();
    }

    /**
     * The text of an open goal that a new goal's text clearly matches (see {@link TargetCheck#clearMatch}), so the
     * person is asked before a second goal for the same thing is created; null when none does.
     */
    private String similarOpenGoal(String text, Grounding.Snapshot snapshot) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return targets.clearMatch(text, snapshot.documents()).flatMap(ledger::load)
                .filter(g -> g.status() != GoalStatus.COMPLETED && g.status() != GoalStatus.CANCELLED)
                .map(GoalState::text).orElse(null);
    }

    /**
     * The goal whose text the sentence repeats, as a person creating it again would say it: the same words as the
     * goal's text, compared without punctuation, case and deadline phrase ("QA renew my passport by the end of the
     * year" repeats a goal created from that sentence). Empty when no goal's text is the sentence.
     */
    static Optional<String> repeatedGoal(String said, Map<String, List<String>> documents) {
        String words = TargetCheck.normalise(goalWords(said));
        if (words.isEmpty()) {
            return Optional.empty();
        }
        return documents.entrySet().stream().filter(e -> !e.getValue().isEmpty()
                        && TargetCheck.normalise(goalWords(e.getValue().getFirst())).equals(words))
                .map(Map.Entry::getKey).findFirst();
    }

    /** A deadline pulled in by more than this many days is asked about first. */
    static final int EARLIER_DAYS_ASKED = 3;

    /** A deadline pushed later by more than this many days (about half a year) is asked about first. */
    static final int LATER_DAYS_ASKED = 183;

    /**
     * Why moving a goal's deadline to {@code to} deserves a note (asked about first in CONVERSE, said with the outcome
     * otherwise): it moves the deadline more than {@link #EARLIER_DAYS_ASKED} days earlier, or open tasks fall due
     * today that are not due now (task dates move with the deadline and never before today), or it moves the deadline
     * (or, for a goal without one, today) more than {@link #LATER_DAYS_ASKED} days later ("move the picnic to November
     * 5" read as next November). Null when none of these.
     */
    static String drasticMove(GoalState goal, LocalDate to, LocalDate today) {
        LocalDate from = goal.dueDate();
        if (to == null) {
            return null;
        }
        LocalDate base = from != null ? from : today;
        if (base != null && to.isAfter(base)) {
            long later = ChronoUnit.DAYS.between(base, to);
            return later > LATER_DAYS_ASKED ? " (" + later + " days later than " + (from != null ? from
                    : "today, " + today) + ")" : null;
        }
        if (from == null || !to.isBefore(from)) {
            return null;
        }
        long earlier = ChronoUnit.DAYS.between(to, from);
        long ontoToday = goal.tasks().stream().filter(t -> t.status().isOpen() && t.dueDate() != null
                && t.dueDate().isAfter(today) && !t.dueDate().minusDays(earlier).isAfter(today)).count();
        List<String> why = new ArrayList<>();
        if (earlier > EARLIER_DAYS_ASKED) {
            why.add(earlier + " days earlier than " + from);
        }
        if (ontoToday > 0) {
            why.add(ontoToday + (ontoToday == 1 ? " task falls" : " tasks fall") + " due today");
        }
        return why.isEmpty() ? null : " (" + String.join("; ", why) + ")";
    }

    /** The question for changes asked about only because they are unusual: the reason once, each change on a line. */
    static String stepUpQuestion(String unusual, List<String> changes) {
        String prefix = "This is unusual for you (" + unusual + "), so confirm it on your device:";
        return changes.size() == 1 ? prefix + " " + changes.getFirst() : prefix + "\n" + String.join("\n", changes);
    }

    /** The answer to "approve it" when no step waits for approval. */
    static final String NOTHING_TO_APPROVE = "nothing is waiting for your approval";

    /** Words with which a person asks automatic work to start again. */
    static final Pattern RESUME_WORDS = Pattern.compile("\\b(?:resume|resumes|resuming|unpause|un-pause|continue"
            + "|restart|start again|carry on|switch back on|turn back on)\\b");

    /** Why a change was refused by a rule, naming the change when it is known: "your rule "..." forbids it (...)". */
    static String forbidden(ConstitutionService.Ruling ruling, String change) {
        return ruling.explanation() + " forbids it" + (change == null ? "" : " (" + change + ")");
    }

    /**
     * Says so when a new goal or task was meant to get a deadline that no date could be read from ("by the end of
     * March" when the calendar does not reach it); empty otherwise. {@code said} is the action's own part of the
     * sentence, null when it names no date while another part does.
     */
    static String noDeadlineNote(Action action, String said) {
        boolean setsDeadline = action.type() == ActionType.CREATE_GOAL || action.type() == ActionType.ADD_TASK;
        if (!setsDeadline || action.dueDate() != null || said == null) {
            return "";
        }
        return ActionDates.deadlinePhrase(said).map(p -> "; no deadline set: no date could be read from \"" + p + "\"")
                .orElse("");
    }

    /**
     * True when the date read from the person's words differs from the date the model proposed (both known): the
     * change is asked about with the kernel's date, for a move as for a new goal or task, and the question names the
     * model's date too.
     */
    static boolean disputedDate(Resolved resolved, LocalDate modelDate) {
        return resolved.source() == ActionDates.Source.WORDS && modelDate != null && resolved.action().dueDate() != null
                && !modelDate.equals(resolved.action().dueDate());
    }

    /**
     * Says so when a new goal or task keeps the model's date because the words name a date the kernel cannot read
     * whole ("by the first Monday after Easter"): the deadline phrase and that the date is the assistant's reading of
     * it. Empty for other actions and when the date was read from the words.
     */
    static String modelReadingNote(Action action, Resolved resolved, String said) {
        boolean setsDeadline = action.type() == ActionType.CREATE_GOAL || action.type() == ActionType.ADD_TASK;
        if (!setsDeadline || resolved.source() != ActionDates.Source.MODEL_READING || action.dueDate() == null) {
            return "";
        }
        if (resolved.words() == null) {
            return " (the assistant's date; I could not tell which part of your words gives it)";
        }
        return ActionDates.deadlinePhrase(said)
                .map(p -> " (the assistant's reading of \"" + p + "\"; I could not read that date myself)")
                .orElse(" (the assistant's date; I could not read one from your words myself)");
    }

    /**
     * Why no date was read from the words: "your words name no date", or, when they hold a word that reads as a date
     * the kernel cannot work out ("Sat", "6.12.", "Boxing Day", "midnight", see {@link ActionDates#dateLike}), "I
     * could not read a date in your words".
     */
    static String noDateRead(String words) {
        return ActionDates.dateLike(words) ? "I could not read a date in your words" : "your words name no date";
    }

    /** A confirmation question with a date note placed before its question mark. */
    static String questionWithNote(String question, String note) {
        if (note.isEmpty()) {
            return question;
        }
        int mark = question.indexOf('?');
        return mark < 0 ? question + note : question.substring(0, mark) + note + question.substring(mark);
    }

    /**
     * The words for a sentence naming a goal nobody has: which name was not found, and the goals that come closest
     * (or how to see them all).
     */
    private String noSuchGoal(String name, Map<String, List<String>> documents) {
        List<String> close = targets.closest(name, documents, 3).stream()
                .map(id -> "\"" + documents.get(id).getFirst() + "\"").toList();
        return "find a goal matching \"" + name + "\"" + (close.isEmpty()
                ? "; say \"list my goals\" to see them all"
                : "; the closest " + (close.size() == 1 ? "is " : "are ") + String.join(", ", close));
    }

    /**
     * The words for a sentence that names a goal by words some goals use, when no goal clearly answers to them
     * ("how is my pm2 sailing boat goal going?" when "pm2" names several goals and none is about a boat): which name
     * matched no goal clearly, and the goals that come closest (or how to see them all).
     */
    private String unclearGoal(String said, Map<String, List<String>> documents) {
        String name = String.join(" ", TargetCheck.nameWords(said));
        List<String> close = targets.closest(name, documents, 3).stream()
                .map(id -> "\"" + documents.get(id).getFirst() + "\"").toList();
        return "tell which goal you mean: no goal clearly matches \"" + name + "\"" + (close.isEmpty()
                ? "; say \"list my goals\" to see them all"
                : "; the closest " + (close.size() == 1 ? "is " : "are ") + String.join(", ", close));
    }

    /**
     * The grown or quarantined capability a lifecycle change is about, by id: a grown capability, else a quarantined
     * grown capability that waits to be kept (see {@link Genesis#findQuarantined}), for both; for
     * {@link ActionType#KEEP_CAPABILITY} also a live capability whose quarantined providers it reinstates. Null when
     * the words match none.
     */
    private String lifecycleTarget(ActionType type, String ref) {
        Optional<Ecosystem.CapabilitySpec> spec = executor.grownSpec(ref);
        if (spec.isPresent()) {
            return spec.get().id();
        }
        Optional<String> older = genesis.findQuarantined(ref).map(Ecosystem.CapabilitySpec::id);
        if (type != ActionType.KEEP_CAPABILITY) {
            return older.orElse(null);
        }
        return older.orElseGet(() -> executor.resolveCapability(ref));
    }

    /** True when there was something to do and all of it succeeded. */
    static boolean allDone(List<Executed> executed) {
        return !executed.isEmpty() && executed.stream().allMatch(Executed::ok);
    }

    /**
     * What the kernel said in a turn, for the conversation history the interpreter is given: each outcome ("done:
     * ..." or "could not: ...", see {@link #remembered}) and each question, at most 600 characters.
     */
    static String spoken(List<Executed> executed, List<String> questions) {
        List<String> lines = new ArrayList<>();
        executed.forEach(e -> lines.add((e.ok() ? "done: " : "could not: ") + remembered(e)));
        lines.addAll(questions);
        String text = String.join(" ", lines);
        return text.length() <= 600 ? text : text.substring(0, 600) + "...";
    }

    /**
     * An outcome as the conversation history keeps it: its message, except that a pairing code or an invitation
     * code never enters the history, which the interpreter reads: a pairing made is kept as "a pairing code was
     * shown", an invitation as "an invitation was made".
     */
    static String remembered(Executed e) {
        if (e.type() == ActionType.PAIR_DEVICE) {
            return e.ok() ? "a pairing code was shown" : "no pairing code was made";
        }
        if (e.type() == ActionType.INVITE) {
            return e.ok() ? "an invitation was made" : "no invitation was made";
        }
        return e.message();
    }

    /** Actions whose date the kernel reads from the person's words. */
    static boolean dated(Action a) {
        return a.type() == ActionType.RESCHEDULE_GOAL || a.type() == ActionType.CREATE_GOAL
                || a.type() == ActionType.ADD_TASK || a.type() == ActionType.UPDATE_TASK && a.dueDate() != null;
    }

    /**
     * Fills in what the kernel computes from the person's own words instead of trusting the model: the date of a
     * dated action (see {@link ActionDates}), the hours of a status change or estimate (see {@link #spokenHours}),
     * and the words a profile correction is read from.
     *
     * @param own         the change's own part of the sentence (see {@link #ownWords}), which its date is read from
     * @param completions how many tasks the sentence marks done
     */
    private Resolved resolve(Action a, String goalId, String said, String own, long completions) {
        if (a.type() == ActionType.INVITE) {
            // The guest's rights are read from the person's words (view only?), the end date from the sentence.
            LocalDate until = DateExpressions.findTarget(said, properties.today()).orElse(a.dueDate());
            return Resolved.of(new Action(a.type(), a.goal(), a.task(), guestName(a.text(), said), until, a.status(),
                    a.hours(), a.approval(), said, a.evidence()));
        }
        if (a.type() == ActionType.CORRECT_PROFILE) {
            // The model's key is kept, whatever value the words seem to name: the question asks about that key with
            // its number ("Did you mean ...?" when the words do not state it), never about another key. Only a
            // correction that names no key takes the one the words name.
            String key = isProfileKey(a.text()) ? canonicalKey(a.text()) : profileKey(said);
            Hours hours = dailyHoursReading(key, a.hours(), said);
            return Resolved.of(new Action(a.type(), a.goal(), a.task(), key, a.dueDate(), a.status(), hours.hours(),
                    a.approval(), said, a.evidence())).noting(hours);
        }
        Action action = a;
        Hours hours = new Hours(null, "");
        boolean completion = a.type() == ActionType.SET_TASK_STATUS && a.status() == TaskStatus.DONE;
        if ((a.type() == ActionType.SET_TASK_STATUS || a.type() == ActionType.UPDATE_TASK)
                && (a.hours() != null || completion)) {
            // Hours nobody said are the model's guess: the status change stands, the hours do not. Hours the words
            // state otherwise than the model are asked about, and so are hours of another task's part of the words.
            GoalState state = goalId == null ? null : ledger.load(goalId).orElse(null);
            String title = state == null || a.task() == null ? null
                    : state.task(a.task()).map(TaskView::title).orElse(null);
            hours = taskHours(a.hours(), said, completion, new ActionDates.TaskName(a.task(), title),
                    state == null ? null : state.text(), otherTasks(state, a.task()), completions);
            action = new Action(a.type(), a.goal(), a.task(), a.text(), a.dueDate(), a.status(), hours.hours(),
                    a.approval(), a.reason(), a.evidence());
        }
        // A task change with neither a date nor hours from the model may still be a move the words date ("move T3
        // to November 27"): its date is read from its words like any move's.
        boolean undated = a.type() == ActionType.UPDATE_TASK && a.dueDate() == null && action.hours() == null;
        return (dated(action) || undated ? withDate(action, goalId, said, own) : Resolved.of(action)).noting(hours);
    }

    /** The goal's tasks other than {@code key}, as the words may name them; empty without a goal. */
    static List<ActionDates.TaskName> otherTasks(GoalState state, String key) {
        if (state == null) {
            return List.of();
        }
        return state.tasks().stream().filter(t -> key == null || !t.key().equalsIgnoreCase(key.strip()))
                .map(t -> new ActionDates.TaskName(t.key(), t.title())).toList();
    }

    /**
     * An action with the date the kernel read for it, where that date came from and the model's own date; the words
     * the date was read from (null when no part of the sentence could be told apart for the action, or the kernel
     * reads no date for it); when the words and the model give different hours, the note for the question that asks
     * about them (empty when they agree), and whether hours read from the words are too unsure to record unasked
     * (see {@link #taskHours}); the date a move starts from (null for none); and the words that make a new deadline
     * repeat ("every Friday", null for none), which are no deadline; and the model's hours when they differ from the
     * hours the kernel read from the words (null when they do not), so the person is asked to say which they mean.
     */
    record Resolved(Action action, ActionDates.Source source, LocalDate proposed, String words, String numberNote,
                    boolean unsureNumber, LocalDate from, String recurrence, Double assistantHours) {

        Resolved(Action action, ActionDates.Source source, LocalDate proposed, String words) {
            this(action, source, proposed, words, "", false, null, null, null);
        }

        /** An action whose date the kernel does not read. */
        static Resolved of(Action action) {
            return new Resolved(action, ActionDates.Source.WORDS, action.dueDate(), null);
        }

        /**
         * The same reading with the hours as the kernel read them: the note for the question about them, whether
         * hours read from the words are too unsure to record unasked, and the model's hours when the two readings
         * differ.
         */
        Resolved noting(Hours hours) {
            return new Resolved(action, source, proposed, words, hours.note() == null ? "" : hours.note(),
                    hours.unsure(), from, recurrence, hours.assistant());
        }

        /** The same reading with the date a move starts from and the words that make a new deadline repeat. */
        Resolved placed(LocalDate from, String recurrence) {
            return new Resolved(action, source, proposed, words, numberNote, unsureNumber, from, recurrence,
                    assistantHours);
        }

        /**
         * The same change with the model's date as the only reading ({@link ActionDates.Source#MODEL_READING}): the
         * words name a date the kernel does not offer a reading of its own for, so the model's is asked about.
         */
        Resolved assistantsReading(LocalDate date) {
            return new Resolved(withDue(action, date), ActionDates.Source.MODEL_READING, date, words, numberNote,
                    unsureNumber, from, recurrence, assistantHours);
        }
    }

    /**
     * The date of a dated change, read from its own part of the sentence ({@code own}) alone. When that part cannot be
     * told apart (null), or names no date while another part does ("mark T3 done and move the picnic, my sister
     * leaves on December 11"), no date of another part is taken: the model's date is then its reading of the
     * sentence, and with none there is no date.
     */
    private Resolved withDate(Action a, String goalId, String said, String own) {
        boolean move = a.type() == ActionType.RESCHEDULE_GOAL || a.type() == ActionType.UPDATE_TASK;
        LocalDate from = null;
        if (move) {
            GoalState state = goalId == null ? null : ledger.load(goalId).orElse(null);
            if (state == null) {
                return Resolved.of(a); // no goal to move: refused or asked about later
            }
            Optional<TaskView> task = state.task(a.task());
            from = a.type() == ActionType.RESCHEDULE_GOAL ? state.dueDate() : task.map(TaskView::dueDate).orElse(null);
        }
        String words = own;
        if (words != null && !words.equals(said) && !ActionDates.statesDate(words, from, properties.today())
                && ActionDates.statesDate(said, from, properties.today())) {
            words = null;
        }
        if (words == null) {
            return new Resolved(a, a.dueDate() == null ? ActionDates.Source.NONE
                    : ActionDates.Source.MODEL_READING, a.dueDate(), null).placed(from, null);
        }
        // A new goal or task "every Friday" repeats: the repeating words give no deadline, other words may.
        String recurring = move ? null : ActionDates.recurrence(words).orElse(null);
        String read = recurring == null ? words : ActionDates.withoutRecurrence(words);
        ActionDates.Reading reading = ActionDates.read(read, from, properties.today(), a.dueDate(), move);
        return new Resolved(new Action(a.type(), a.goal(), a.task(), a.text(), reading.date(), a.status(), a.hours(),
                a.approval(), a.reason(), a.evidence()), reading.source(), reading.proposed(), words)
                .placed(from, recurring);
    }

    /**
     * The note for a move to the assistant's reading of a date the kernel cannot read itself: the phrase it read
     * (see {@link ActionDates#datePhrase}), or that no part of the words could be told apart for this action.
     */
    static String modelMoveNote(Resolved resolved) {
        if (resolved.words() == null) {
            return " (the assistant's date; I could not tell which part of your words gives it)";
        }
        return ActionDates.datePhrase(resolved.words())
                .map(p -> " (the assistant's reading of \"" + p + "\"; I could not read that date myself)")
                .orElse(" (the assistant's reading of your words; I could not read that date myself)");
    }

    /**
     * The part of a sentence with several changes that is about this action's goal (see {@link ActionDates#clause}),
     * so each change is tied to its goal by its own words ("move the picnic to Friday and mark the bake sale done");
     * the whole sentence when no part can be told apart. A task key's part that runs to the end of the sentence
     * ("mark T1 of call mom done and push the passport to December 5", the only key) gives way to the clause that
     * holds most of the goal's words.
     */
    static String ownPart(String said, Action action, List<String> goalTexts) {
        return ownPart(said, action, goalTexts, null);
    }

    /**
     * {@link #ownPart(String, Action, List)} for a task change whose task is titled {@code taskTitle}: its part is the
     * clause that shares most words with the task's title ("I booked the table" of "I booked the table and ordered the
     * flowers" for "Booking a table"), else the part the goal's words give.
     */
    static String ownPart(String said, Action action, List<String> goalTexts, String taskTitle) {
        String instruction = instructionOf(said, action,
                taskTitle != null ? taskTitle : goalTexts == null || goalTexts.isEmpty() ? null : goalTexts.getFirst());
        if (instruction != null) {
            return instruction;
        }
        if (taskTitle != null) {
            String byTask = ActionDates.clause(said, action.task(), taskTitle);
            if (byTask != null && !byTask.isBlank() && !byTask.equals(said)) {
                return byTask;
            }
        }
        String title = goalTexts == null || goalTexts.isEmpty() ? null : goalTexts.getFirst();
        String part = ActionDates.clause(said, action.task(), title);
        if (said != null && said.equals(part) && action.task() != null && title != null) {
            String byGoal = ActionDates.clause(said, null, title);
            if (byGoal != null && !byGoal.isBlank()) {
                part = byGoal;
            }
        }
        return part == null || part.isBlank() ? said : part;
    }

    /**
     * True when a task change whose own part ({@code part}) names its task by key ("T2 is in progress") and no goal
     * is tied to {@code goalId} by another part of the same sentence: another change of the turn on that goal whose
     * own part grounds it ("T1 of the thesis is done, T2 is in progress"). A part that names words of a goal is judged
     * on its own words.
     */
    private boolean groundedByAnotherPart(Action change, String goalId, String part, String said, List<Action> changes,
                                          Grounding.Snapshot snapshot, Set<String> recent) {
        if (change.task() == null || !namedKeys(part).contains(upper(change.task().strip()))
                || TargetCheck.sharesWord(part, snapshot.documents().getOrDefault(goalId, List.of()))) {
            return false;
        }
        String goalText = snapshot.documents().getOrDefault(goalId, List.of()).stream().findFirst().orElse(null);
        for (Action other : changes) {
            if (other == change || !other.type().targetsGoal()
                    || !goalId.equals(snapshot.goal(other.goal()).orElse(null))) {
                continue;
            }
            // A task change's own part ends at the next task key ("picnic: T3 is done but T4 is not" gives T3 "picnic:
            // T3 is done"); a goal change's is its clause.
            String words = TASK_ACTIONS.contains(other.type()) && other.task() != null
                    ? Objects.requireNonNullElse(ActionDates.ownPart(said, other.task(), null), said)
                    : ownPart(said, other, goalText == null ? List.of() : List.of(goalText));
            if (!words.equals(said) && targets.check(words, other.evidence(), goalId, snapshot.documents(), recent)
                    .grounded()) {
                return true;
            }
        }
        return false;
    }

    /** The most goals a refusal names when the words do not tie a change to one goal. */
    static final int GOALS_NAMED = 3;

    /**
     * The refusal for a change whose words do not ground the goal it is aimed at: "tell which goal you mean" with the
     * reason, naming the goals that hold the words (by their share of the words' distinctive weight, at most
     * {@link #GOALS_NAMED}), "say its name" when none does. The goal the model chose is never offered on its own.
     */
    static String whichGoal(String words, String reason, Map<String, List<String>> documents) {
        Map<String, Double> shares = TargetCheck.distinctiveOverlap(words, documents);
        List<String> holding = shares.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> documents.getOrDefault(e.getKey(), List.of()).stream().findFirst().orElse(null))
                .filter(Objects::nonNull).limit(GOALS_NAMED).map(t -> "\"" + Grounding.oneLine(t) + "\"").toList();
        String why = reason == null || reason.isBlank() ? "" : " (" + reason + ")";
        return holding.isEmpty() ? "tell which goal you mean" + why + "; say its name"
                : "tell which goal you mean" + why + ": " + String.join(" or ", holding);
    }

    /** True when the goal is neither completed nor cancelled. */
    private boolean open(String goalId) {
        return ledger.load(goalId).map(g -> g.status() != GoalStatus.COMPLETED && g.status() != GoalStatus.CANCELLED)
                .orElse(false);
    }

    /**
     * Explicit words asking for a new goal, with what it is: "new goal: ...", "create a goal to ...", "add a goal
     * called ...", "goal: ...". A sentence that only mentions a goal ("goal reached") is none.
     */
    private static final Pattern GOAL_REQUEST = Pattern.compile("(?i)^\\s*(?:(?:please|ok|okay|hey)[,\\s]+)?"
            + "(?:(?:create|add|make|start|set up|open)\\s+(?:a\\s+|an\\s+|the\\s+|my\\s+)?(?:new\\s+)?goal\\b"
            + "|new\\s+goal\\b|goal\\s*[:-])");

    /** True when the sentence asks in so many words for a new goal and says what it is. */
    static boolean goalRequest(String said) {
        String t = said == null ? "" : said.strip();
        return GOAL_REQUEST.matcher(t).lookingAt() && !GOAL_COMMAND.matcher(t).replaceFirst("").isBlank();
    }

    /**
     * The new goal a request for one asks for: its own words, and the deadline its words name in the strict form (see
     * {@link ActionDates#strictDeadline}; none otherwise, and the date stays in the goal's words).
     */
    private Action offeredGoal(String said) {
        LocalDate today = properties.today();
        LocalDate strict = ActionDates.strictDeadline(said, today)
                ? ActionDates.read(said, null, today, null, false).date() : null;
        if (strict != null && !strict.isBefore(today)) {
            return new Action(ActionType.CREATE_GOAL, null, null, capitalFirst(goalWords(said, today)), strict, null,
                    null, null, said, null);
        }
        String words = goalWords(said, today);
        LocalDate due = endingDate(words);
        // A date that has passed is no deadline; the title leaves it out and the question says so.
        return due != null ? new Action(ActionType.CREATE_GOAL, null, null, goalTitle(words, true),
                due.isBefore(today) ? null : due, null, null, null, said, null)
                : new Action(ActionType.CREATE_GOAL, null, null, capitalFirst(words), null, null, null, null, said,
                null);
    }

    /** The date the date phrase that ends the words names ("yesterday" too, which no deadline reads), or null. */
    private LocalDate endingDate(String words) {
        OptionalInt start = ActionDates.endingDateStart(words);
        if (start.isEmpty() || start.getAsInt() == 0) {
            return null;
        }
        return words.substring(start.getAsInt()).matches("(?i).*\\byesterday\\W*$")
                ? properties.today().minusDays(1)
                : ActionDates.read(words, null, properties.today(), null, false).date();
    }

    /**
     * The note on an offered goal: its deadline was read from a date said without "by" or "before", or the date that
     * ends its words has passed and is no deadline.
     */
    private String offeredDateNote(String said, Action offer) {
        LocalDate ending = endingDate(goalWords(said, properties.today()));
        return offer.dueDate() == null && ending != null && ending.isBefore(properties.today())
                ? "; no deadline set: " + ending + " has already passed"
                : offer.dueDate() != null && !ActionDates.strictDeadline(said, properties.today())
                ? " (" + ActionDates.NO_MARKER + ")" : "";
    }

    /**
     * A profile change as the question shows it and a yes stores it: the action with the exact value ({@code
     * dailyHours} and {@code estimateBias} as its number, {@code workingDays} as the stored days, see
     * {@link ProfileService#stored}), the value in lasting words ("you work Monday to Thursday", see
     * {@link ActionExecutor#lastingValue}), the notes the question carries, and whether the value was read from the
     * words without doubt; or, when no value can be stated, why nothing is asked.
     */
    record ProfileChange(Action action, String value, String note, boolean read, String problem) {

        static ProfileChange refused(String problem) {
            return new ProfileChange(null, null, "", false, problem);
        }
    }

    /** The working days the snapshot shows the person has now; weekdays when it shows none. */
    static List<String> currentDays(Grounding.Snapshot snapshot) {
        String stored = snapshot.params().path("profile").path("workingDays").asString("");
        return stored.isBlank() ? List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY")
                : List.of(stored.split(","));
    }

    /**
     * The profile change a sentence asks for (see {@link ProfileChange}). Working days are those the words give against
     * {@code current} (see {@link ProfileService#workingDays}); daily hours are the action's hours as
     * {@link #dailyHoursReading} read them; the pace is the factor the words give (see {@link ProfileService#factor}:
     * the one they end on after a self-correction or a change from what it "used to" be), read without doubt when the
     * words give one pace the model does not read otherwise; a pace the model reads otherwise is not asked about (the
     * person says the one meant), and words that give several paces are asked about only with the model's reading.
     * Without a pace in the words, the model's number is asked with a note saying why the words did not give it: they
     * give a number without saying which way it goes, or no number at all (then only a model number that goes the way
     * the words do). Words that limit the value in time or make it a one-off ("in December", "while the kids are off",
     * "I'm sick", see {@link ProfileService#limitWording}) are named in a note, as are the days a number of hours is
     * stated for ("on weekends"): a profile value is one lasting value.
     */
    static ProfileChange profileChange(Action action, Double modelValue, String said, List<String> current) {
        String key = canonicalKey(action.text());
        String words = said == null ? "" : said;
        String limit = key == null ? "" : ProfileService.limitWording(words, key)
                .map(p -> " (you said \"" + p + "\": I can only store a lasting value)").orElse("");
        if ("workingDays".equals(key)) {
            List<String> days = ProfileService.workingDays(words, current);
            if (days.isEmpty()) {
                return ProfileChange.refused("set your working days: say which days you work");
            }
            Action exact = new Action(ActionType.CORRECT_PROFILE, null, null, key, null, null, null, null,
                    ProfileService.stored(days), null);
            return new ProfileChange(exact, ActionExecutor.lastingValue(exact, current), limit, true, null);
        }
        if ("dailyHours".equals(key)) {
            Double hours = action.hours();
            if (hours == null || hours <= 0 || hours > 16) {
                return ProfileChange.refused("set your daily hours: say a number of hours between 0 and 16");
            }
            Action exact = new Action(ActionType.CORRECT_PROFILE, null, null, key, null, null, hours, null, words,
                    null);
            String scope = ProfileService.hoursScope(words).map(p -> " (you said \"" + p
                    + "\": I can only store one number of hours for every working day)").orElse("");
            return new ProfileChange(exact, ActionExecutor.lastingValue(exact, current), limit + scope,
                    Durations.simplePerDay(words), null);
        }
        if ("estimateBias".equals(key)) {
            Double fromWords = ProfileService.factor(words).orElse(null);
            Double factor;
            String note;
            boolean read;
            if (fromWords != null) {
                // A model that read another pace: neither is offered, the person says the one meant. Words that give
                // more than one pace ("30% longer, no, 50% longer", "it used to be 30%, now 50%") are asked about
                // with the model's reading, which is the one they end on; without one, the person says it.
                boolean differs = modelValue != null && Math.abs(modelValue - fromWords) > 0.005;
                boolean several = ProfileService.paceAmounts(words) > 1;
                if (differs) {
                    return ProfileChange.refused("set your pace: say the pace you mean: I read " + hoursText(fromWords)
                            + " times the estimate, the assistant read " + hoursText(modelValue) + " times");
                }
                if (several && modelValue == null) {
                    return ProfileChange.refused("set your pace: your words give more than one pace; say the one you "
                            + "mean");
                }
                factor = fromWords;
                read = !several;
                note = several ? " (your words give more than one pace; this is the one they end on)" : "";
            } else if (modelValue == null) {
                return ProfileChange.refused(ProfileService.paceAmount(words) ? UNREAD_PACE : NO_PACE);
            } else if (ProfileService.paceAmount(words)) {
                factor = modelValue;
                read = false;
                note = " (the assistant's reading: I could not tell from your words whether you are faster or slower)";
            } else {
                Optional<Boolean> faster = ProfileService.faster(words);
                if (faster.isPresent() && faster.get() != modelValue < 1) {
                    return ProfileChange.refused(NO_PACE);
                }
                factor = modelValue;
                read = false;
                note = " (the assistant's number: your words give none)";
            }
            if (factor < 0.25 || factor > 4) {
                return ProfileChange.refused(NO_PACE);
            }
            Action exact = new Action(ActionType.CORRECT_PROFILE, null, null, key, null, null, factor, null, words,
                    null);
            return new ProfileChange(exact, ActionExecutor.lastingValue(exact, current), limit + note, read, null);
        }
        return ProfileChange.refused("learn that: only your daily hours, your working days and how far estimates are "
                + "off can be learned");
    }

    /** Why no pace is asked about when the words give none the kernel can read. */
    static final String NO_PACE = "set your pace: say how far estimates are off, for example \"50% longer\" or "
            + "\"20% faster\"";
    /** Why no pace is asked about when the words give a number without saying which way it goes. */
    static final String UNREAD_PACE = "set your pace: say whether tasks take you longer or less time than "
            + "estimated, for example \"50% longer\" or \"20% faster\"";

    /**
     * The question for a profile change: "From now on: you work Monday to Thursday?", with its notes before the
     * question mark; "Did you mean, from now on: ...?" when the kernel read the value differently from the model or
     * not from the words alone.
     */
    static String profileQuestion(ProfileChange change, boolean read, String numberNote) {
        return (read ? "From now on: " : "Did you mean, from now on: ") + change.value() + change.note()
                + (numberNote == null ? "" : numberNote) + "?";
    }

    /** Words with which a person refers to what the system suggested ("like you suggested", "as you said"). */
    private static final Pattern REFERS_TO_SUGGESTION = Pattern.compile("\\b(?:suggest\\w*|recommend\\w*|propos\\w*"
            + "|advi[cs]e\\w*|you said|you mentioned|your idea)\\b");

    /**
     * The unread message whose suggestion a move carries out when the person refers to it ("yes, move the deadline
     * like you suggested"): the one message about this goal that suggests this kind of change (for a task, this
     * task). Empty when the words do not refer to a suggestion, or no single message fits.
     */
    private Optional<Inbox.Message> suggestionFor(Action action, String goalId, String said) {
        if (goalId == null || !REFERS_TO_SUGGESTION.matcher(said.toLowerCase(Locale.ROOT)).find()) {
            return Optional.empty();
        }
        List<Inbox.Message> fitting = inbox.unread().stream().filter(m -> m.suggestions().stream().anyMatch(s ->
                        s.type() == action.type() && goalId.equals(s.goal() != null ? s.goal() : m.goalId())
                                && (action.type() != ActionType.UPDATE_TASK
                                || Objects.equals(upper(s.task()), upper(action.task())))))
                .toList();
        return fitting.size() == 1 ? Optional.of(fitting.getFirst()) : Optional.empty();
    }

    /**
     * Hours the model split off a completion ("T3 is done, it took 1h30") as a separate re-estimate of the same task
     * are recorded with the completion: a done task is not re-estimated, but its real hours count.
     */
    static List<Action> merged(List<Action> proposals) {
        List<Action> out = new ArrayList<>(proposals);
        for (int u = 0; u < out.size(); u++) {
            Action update = out.get(u);
            if (update.type() != ActionType.UPDATE_TASK || update.hours() == null || update.dueDate() != null
                    || update.task() == null) {
                continue;
            }
            for (int d = 0; d < out.size(); d++) {
                Action done = out.get(d);
                if (done.type() == ActionType.SET_TASK_STATUS && done.status() == TaskStatus.DONE
                        && done.hours() == null && update.task().equalsIgnoreCase(String.valueOf(done.task()))
                        && Objects.equals(done.goal(), update.goal())) {
                    out.set(d, new Action(done.type(), done.goal(), done.task(), done.text(), done.dueDate(),
                            done.status(), update.hours(), done.approval(), done.reason(), done.evidence()));
                    out.remove(u);
                    u--;
                    break;
                }
            }
        }
        return out;
    }

    /** What starts a request to create a goal: "new goal:", "create a goal to", "add a new goal called". */
    private static final Pattern GOAL_COMMAND = Pattern.compile("(?i)^\\s*(?:(?:please|ok|okay|hey)[,\\s]+)?"
            + "(?:(?:create|add|make|start|set up|open)\\s+(?:a\\s+|an\\s+|the\\s+|my\\s+)?(?:new\\s+)?goal"
            + "(?:\\s+(?:to|called|named|for|about|of))?|new\\s+goal(?:\\s+(?:to|called|named|for|about))?|goal)"
            + "\\b\\s*[:,-]?\\s*");

    /**
     * What a person says before the goal itself: "I need to", "help me", "remind me to", "I want to", "let's". It is
     * no part of the goal's words.
     */
    private static final Pattern GOAL_FILLER = Pattern.compile("(?i)^\\s*(?:(?:please|so|ok|okay|well)[,\\s]+)?"
            + "(?:(?:can|could|would|will)\\s+you\\s+(?:please\\s+)?)?(?:please\\s+)?"
            + "(?:help\\s+me(?:\\s+to)?|remind\\s+me\\s+to|i\\s+(?:really\\s+)?(?:need|have|want|ought)\\s+to"
            + "|i'?d\\s+like\\s+to|i\\s+would\\s+like\\s+to|i\\s+must|i\\s+should|i'?m\\s+going\\s+to"
            + "|i\\s+am\\s+going\\s+to|i'?m\\s+gonna|i\\s+gotta|i'?ve\\s+got\\s+to|i\\s+plan\\s+to"
            + "|i'?m\\s+planning\\s+to|i\\s+will|i'll|we\\s+(?:really\\s+)?(?:need|have|want)\\s+to|we\\s+will|we'll"
            + "|we\\s+must|we\\s+should|let'?s"
            + "|(?:i|we)\\s+(?:really\\s+|also\\s+)?(?:need|want)\\s+(?:a|an|some|my)(?=\\s))\\b\\s*");

    /**
     * A new goal's title: {@code text} without "go and" before what is to be done (see {@link #withoutGoAnd}) and
     * without the date phrase at its end ("plan my sister's birthday party next Saturday" due that Saturday is "Plan
     * my sister's birthday party"), which is the goal's due date instead, unless a word before it makes it part of what
     * is said ("until December 4", see {@link ActionDates#endingDateStart}), and with a capital first letter unless
     * the first word mixes cases ("iPhone app"). Without a {@code deadline} every date phrase stays.
     */
    static String goalTitle(String text, boolean deadline) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String title = withoutGoAnd(text.strip());
        OptionalInt dateStart = deadline ? ActionDates.endingDateStart(title) : OptionalInt.empty();
        if (dateStart.isPresent() && dateStart.getAsInt() > 0) {
            // No connecting word is left hanging at the end: "plan the party by the" is "plan the party".
            title = title.substring(0, dateStart.getAsInt()).replaceAll("[\\s,;:-]+$", "")
                    .replaceAll("(?i)(?:[\\s,]+(?:by|before|on|at|for|due|in|until|till|the|a|an))+$", "").strip();
        }
        return capitalFirst(title);
    }

    /** "Task for the picnic blanket" is "picnic blanket": the word for a task is no part of what it is. */
    static String withoutTaskWord(String title) {
        String rest = title == null ? null : title.replaceFirst("(?i)^(?:a\\s+|the\\s+)?(?:new\\s+)?(?:task|todo"
                + "|to-do|item)\\s*(?:for|to|about|of|:)\\s+(?:the\\s+|a\\s+|an\\s+)?", "");
        return rest == null || rest.isBlank() ? title : capitalFirst(rest);
    }

    /** "Go and buy her a present" is "buy her a present": the going is no part of what is to be done. */
    static String withoutGoAnd(String text) {
        String rest = text == null ? null : text.replaceFirst("(?i)^(?:go|come)\\s+(?:and|an|&)\\s+", "");
        return rest == null || wordCount(rest) < 2 ? text : rest;
    }

    /** {@code text} with a capital first letter, unless its first word mixes cases ("iPhone app"). */
    static String capitalFirst(String text) {
        boolean lowerStart = text != null && text.length() > 1 && Character.isLowerCase(text.charAt(0))
                && !Character.isUpperCase(text.charAt(1));
        return lowerStart ? Character.toUpperCase(text.charAt(0)) + text.substring(1) : text;
    }

    /**
     * A goal's words as a person creating it again would say them, for telling whether a sentence repeats a goal (see
     * {@link #repeatedGoal}): the sentence without the request to create it ("new goal:") and without a trailing
     * deadline in the strict form (see {@link ActionDates#trailingDeadline}: "by the end of March" at the end),
     * whether or not its date can be read today.
     */
    static String goalWords(String said) {
        return goalWords(said, null);
    }

    /**
     * A new goal in the person's own words: the sentence without the request to create it ("new goal:"), without what
     * is said before the goal itself ("I need to", "help me", see GOAL_FILLER) and, only when it is the goal's strict
     * deadline on {@code today} (see {@link ActionDates#strictDeadline}: "by the end of March" at the end, a date read
     * whole), without its deadline phrase, which becomes the goal's due date instead.
     * Any other date phrase stays in the text ("learn the song I heard before Christmas", "QA mow the lawn, rain lasts
     * until Friday", "QA plant tulips by 27/11", whose date can be read two ways). With {@code today} null, a trailing
     * deadline in the strict form is left out whatever its date.
     */
    static String goalWords(String said, LocalDate today) {
        String sentence = said == null ? "" : TargetCheck.straightQuotes(said).strip();
        String text = withoutTaskCommand(GOAL_COMMAND.matcher(sentence).replaceFirst(""));
        String withoutFiller = GOAL_FILLER.matcher(text).replaceFirst("");
        if (!withoutFiller.isBlank()) {
            text = withoutFiller;
        }
        Optional<int[]> deadline = ActionDates.trailingDeadline(text);
        if (deadline.isPresent() && (today == null || ActionDates.strictDeadline(text, today))) {
            text = text.substring(0, deadline.get()[0]) + text.substring(deadline.get()[1]);
        }
        text = text.replaceAll("\\s+", " ").replaceAll("\\s+([,.;:!?])", "$1").replaceAll("[\\s,;:.!-]+$", "")
                .strip();
        return text.isEmpty() ? sentence : text;
    }

    /**
     * A new goal in the person's own words with any deadline kept: the sentence without the request to create it
     * ("new goal: plant tulips by Friday" gives "plant tulips by Friday").
     */
    static String goalWordsAsSaid(String said) {
        String sentence = said == null ? "" : TargetCheck.straightQuotes(said).strip();
        String text = withoutTaskCommand(GOAL_COMMAND.matcher(sentence).replaceFirst("")).replaceAll("\\s+", " ")
                .replaceAll("[\\s,;:.!-]+$", "").strip();
        return text.isEmpty() ? sentence : text;
    }

    /**
     * True when a new goal's text is the person's: every word of it was said, it leaves out none of the content words
     * the sentence gives the goal ("QA pm: renew my passport" is not "Renew my passport"), and it keeps every date
     * phrase the goal's words hold besides a strict deadline on {@code today} (see {@link #goalWords(String,
     * LocalDate)}: "hold off on buying a car until December 4" is not "Hold off on buying a car").
     */
    static boolean takenFrom(String text, String said, LocalDate today) {
        if (text == null || text.isBlank() || !TargetCheck.unsaid(text, said).isEmpty()) {
            return false;
        }
        Set<String> kept = TargetCheck.words(text);
        String words = goalWords(said, today);
        String normalisedText = " " + TargetCheck.normalise(text) + " ";
        boolean datesKept = DateExpressions.datePhraseSpans(words).stream()
                .allMatch(span -> normalisedText.contains(" " + TargetCheck.normalise(span.in(words)) + " "));
        return datesKept && TargetCheck.words(words).stream()
                .allMatch(w -> kept.stream().anyMatch(k -> TargetCheck.similarWords(w, k)));
    }

    /** Words that make a question one about tasks and dates, answered by the agenda rather than the goal list. */
    private static final Pattern AGENDA_QUESTION = Pattern.compile("\\b(?:tasks?|today|tonight|tomorrow|agenda"
            + "|to ?dos?|should i|can i|next|this week|work on|start with|due)\\b");
    private static final Pattern GOAL_WORDS = Pattern.compile("\\b(?:goals?|projects?)\\b");
    /** Words that ask what waits for the person's approval: "what is waiting for my approval?", "pending approvals". */
    private static final Pattern APPROVAL_LIST_WORDS = Pattern.compile("\\b(?:approv\\w*|sign[- ]?off)\\b");
    /** Words that ask about the inbox: only these make a list read show the unread messages. */
    static final Pattern INBOX_WORDS = Pattern.compile("\\b(?:inbox|messages?|notifications?|suggestions?"
            + "|warnings?|alerts?|reminders?|unread|what'?s new|anything new)\\b");

    /**
     * Which list answers a question, from its words: the steps waiting for approval when it speaks of approval ("what
     * is waiting for my approval?"), the list of goals when it speaks of goals or projects, the agenda when it speaks
     * of tasks or days without them ("what should I do today?"), else {@code proposed}.
     */
    static ActionType listType(String said, ActionType proposed) {
        String t = said == null ? "" : TargetCheck.withoutNegated(said).toLowerCase(Locale.ROOT);
        return APPROVAL_LIST_WORDS.matcher(t).find() ? ActionType.LIST_APPROVALS
                : GOAL_WORDS.matcher(t).find() ? ActionType.LIST_GOALS
                : AGENDA_QUESTION.matcher(t).find() ? ActionType.SHOW_AGENDA : proposed;
    }

    /**
     * The fixed read that answers a question the kernel answers itself: the agenda for a question about tasks or
     * dates ("what should I do today?"), otherwise the list of goals.
     */
    private Executed fallbackRead(String said, String principal, ActionExecutor.Shown shown) {
        return fallbackRead(listType(said, ActionType.LIST_GOALS), said, principal, shown);
    }

    /**
     * Runs a list of goals or the agenda. A list is cut to what the words ask about before it is limited: for goals,
     * overdue goals ("which goals are late?"), goals due today, tomorrow, this week or next week, open, completed or
     * cancelled goals; for tasks, overdue tasks, tasks due today, tomorrow, this week or next week ("how many tasks
     * are due tomorrow?"), on a day, a weekend or in a month, or by a day (see {@link DatedCut}), whether or not they
     * can start now. The line then says how many there are in all ("25
     * tasks due tomorrow, showing 10"). A date to cut by that no cut answers ("goals due in March") is said on the
     * line of the whole list.
     */
    private Executed fallbackRead(ActionType type, String said, String principal, ActionExecutor.Shown shown) {
        Optional<GoalFilter> filter = type == ActionType.LIST_GOALS ? GoalFilter.of(said) : Optional.empty();
        if (filter.isPresent()) {
            return executor.listGoals(filter.get().cut, filter.get().label, shown);
        }
        Optional<TaskFilter> tasks = type == ActionType.SHOW_AGENDA ? TaskFilter.of(said) : Optional.empty();
        if (tasks.isPresent()) {
            return executor.listTasks(tasks.get().cut, tasks.get().label, shown);
        }
        Optional<DatedCut> dated = type == ActionType.SHOW_AGENDA ? DatedCut.of(said, properties.today())
                : Optional.empty();
        if (dated.isPresent()) {
            return executor.listTasks(dated.get().from(), dated.get().to(), dated.get().label(), shown);
        }
        Executed whole = executor.execute(new Action(type, null, null, null, null, null, null, null, null, null), null,
                null, principal, shown);
        // A cut the words ask for that no cut makes is said, so the whole list is not taken for the cut asked for.
        Optional<String> uncut = unsupportedCut(said);
        return uncut.isPresent() && whole.ok() ? new Executed(whole.type(), true, whole.message()
                + uncutNote(uncut.get()), whole.goalId(), whole.runId()) : whole;
    }

    /**
     * The kernel's own answer to a question it reads itself: a goal the words name (one nobody has, or one no goal
     * clearly answers to, is said so; otherwise the kernel's own match, see {@link #kernelMatch}), else, for a
     * sentence that names no goal, the list of goals or the agenda (see {@link #fallbackRead(String, String,
     * ActionExecutor.Shown)}), within what the caller may do.
     */
    private Executed kernelRead(String said, Grounding.Snapshot snapshot, SecurityService.Caller caller,
                                String principal, ActionExecutor.Shown shown) {
        if (!TargetCheck.nameWords(said).isEmpty()) {
            Optional<String> unknown = TargetCheck.unknownName(said, snapshot.documents());
            if (unknown.isPresent()) {
                return new Executed(ActionType.SHOW_GOAL, false, noSuchGoal(unknown.get(), snapshot.documents()),
                        null, null);
            }
            Optional<String> match = kernelMatch(said, snapshot.documents());
            if (match.isPresent() && caller.may(ActionType.SHOW_GOAL, match.get())) {
                return executor.execute(new Action(ActionType.SHOW_GOAL, null, null, null, null, null, null, null,
                        null, null), match.get(), null, principal, shown);
            }
            return new Executed(ActionType.SHOW_GOAL, false, unclearGoal(said, snapshot.documents()), null, null);
        }
        ActionType type = listType(said, ActionType.LIST_GOALS);
        if (!caller.may(type, null)) {
            return new Executed(type, false, "not part of what you were invited to do", null, null);
        }
        return fallbackRead(type, said, principal, shown);
    }

    /**
     * The goal a sentence that names one points at in the kernel's own reading: the clear match, else the one goal
     * a name word belongs to ("attic"), else the goal ranked first by a clear margin. Only for showing a goal.
     */
    private Optional<String> kernelMatch(String said, Map<String, List<String>> documents) {
        return targets.clearMatch(said, documents)
                .or(() -> TargetCheck.distinctiveMatch(said, documents))
                .or(() -> targets.likely(said, documents));
    }

    /** A cut of the goal list that a question asks for, with the list's label ("overdue goals"). */
    enum GoalFilter {
        OVERDUE("overdue goals", LedgerService.GoalCut.OVERDUE),
        DUE_TODAY("goals due today", LedgerService.GoalCut.DUE_TODAY),
        DUE_TOMORROW("goals due tomorrow", LedgerService.GoalCut.DUE_TOMORROW),
        DUE_THIS_WEEK("goals due this week", LedgerService.GoalCut.DUE_THIS_WEEK),
        DUE_NEXT_WEEK("goals due next week", LedgerService.GoalCut.DUE_NEXT_WEEK),
        COMPLETED("completed goals", LedgerService.GoalCut.COMPLETED),
        CANCELLED("cancelled goals", LedgerService.GoalCut.CANCELLED),
        OPEN("open goals", LedgerService.GoalCut.OPEN);

        final String label;
        final LedgerService.GoalCut cut;

        GoalFilter(String label, LedgerService.GoalCut cut) {
            this.label = label;
            this.cut = cut;
        }

        private static final Pattern OVERDUE_WORDS = Pattern.compile("\\b(?:overdue|late|past due|behind|missed)\\b");
        private static final Pattern TODAY_WORDS = Pattern.compile("\\bdue\\s+(?:by\\s+|on\\s+)?today\\b");
        private static final Pattern TOMORROW_WORDS = Pattern.compile("\\bdue\\s+(?:by\\s+|on\\s+)?tomorrow\\b");
        private static final Pattern WEEK_WORDS = Pattern.compile("\\b(?:this week|due soon|coming up)\\b");
        private static final Pattern NEXT_WEEK_WORDS = Pattern.compile("\\bnext week\\b");
        private static final Pattern COMPLETED_WORDS = Pattern.compile("\\b(?:completed|finished|done|achieved"
                + "|accomplished|reached)\\b");
        private static final Pattern CANCELLED_WORDS = Pattern.compile("\\b(?:cancell?ed|abandoned|dropped)\\b");
        private static final Pattern OPEN_WORDS = Pattern.compile("\\b(?:open|active|current|unfinished)\\b");

        /** The cut the words ask for, or empty for the whole list. */
        static Optional<GoalFilter> of(String said) {
            String t = said == null ? "" : TargetCheck.withoutNegated(said).toLowerCase(Locale.ROOT);
            return OVERDUE_WORDS.matcher(t).find() ? Optional.of(OVERDUE)
                    : TODAY_WORDS.matcher(t).find() ? Optional.of(DUE_TODAY)
                    : TOMORROW_WORDS.matcher(t).find() ? Optional.of(DUE_TOMORROW)
                    : WEEK_WORDS.matcher(t).find() ? Optional.of(DUE_THIS_WEEK)
                    : NEXT_WEEK_WORDS.matcher(t).find() ? Optional.of(DUE_NEXT_WEEK)
                    : OPEN_WORDS.matcher(t).find() ? Optional.of(OPEN)
                    : COMPLETED_WORDS.matcher(t).find() ? Optional.of(COMPLETED)
                    : CANCELLED_WORDS.matcher(t).find() ? Optional.of(CANCELLED) : Optional.empty();
        }
    }

    /**
     * A date a list question cuts by that no cut answers ("goals due in March", "tasks due on Friday"): the words from
     * "due" or "deadline" on, so the list says it is not cut that way. Empty when the words ask for no such cut.
     */
    static Optional<String> unsupportedCut(String said) {
        String t = said == null ? "" : TargetCheck.withoutNegated(said).toLowerCase(Locale.ROOT);
        Matcher due = DUE_DATE_WORDS.matcher(t);
        if (!due.find()) {
            return Optional.empty();
        }
        String rest = t.substring(due.start(), DateExpressions.clauseEnd(t, due.end())).replaceAll("[?.!]+$", "")
                .strip();
        return ActionDates.datePhrase(rest).isPresent() || ActionDates.statesDate(rest, null, LocalDate.of(2000, 1, 3))
                ? Optional.of(rest) : Optional.empty();
    }

    /** "Due" or "deadline" with what follows: where a list question names the dates it wants. */
    private static final Pattern DUE_DATE_WORDS = Pattern.compile("\\b(?:due|deadlines?)\\b");

    /** What a list says when the words asked for a cut it cannot make. */
    static String uncutNote(String asked) {
        return "; not cut to \"" + asked + "\": goals can be cut to what is overdue or due today, tomorrow, this week "
                + "or next week, and to open, completed or cancelled ones; tasks also to a day, a weekend or a month";
    }

    /** A cut of the tasks that a question about due dates asks for, with the list's label ("tasks due tomorrow"). */
    enum TaskFilter {
        OVERDUE("overdue tasks", ActionExecutor.TaskCut.OVERDUE),
        DUE_TODAY("tasks due today", ActionExecutor.TaskCut.DUE_TODAY),
        DUE_TOMORROW("tasks due tomorrow", ActionExecutor.TaskCut.DUE_TOMORROW),
        DUE_THIS_WEEK("tasks due this week", ActionExecutor.TaskCut.DUE_THIS_WEEK),
        DUE_NEXT_WEEK("tasks due next week", ActionExecutor.TaskCut.DUE_NEXT_WEEK);

        final String label;
        final ActionExecutor.TaskCut cut;

        TaskFilter(String label, ActionExecutor.TaskCut cut) {
            this.label = label;
            this.cut = cut;
        }

        private static final Pattern OVERDUE_WORDS = Pattern.compile("\\b(?:overdue|late|past due|behind|missed)\\b");
        private static final Pattern TODAY_WORDS = Pattern.compile("\\bdue\\s+(?:by\\s+|on\\s+)?today\\b"
                + "|\\btoday'?s\\s+(?:tasks|deadlines)\\b");
        /**
         * A question about what is due tomorrow: "due tomorrow", "tomorrow's tasks", "what's on my list for tomorrow?",
         * "what do I have tomorrow?". "What can I do tomorrow?" is the agenda of what can start.
         */
        private static final Pattern TOMORROW_WORDS = Pattern.compile("\\bdue\\s+(?:by\\s+|on\\s+)?tomorrow\\b"
                + "|\\btomorrow'?s\\s+(?:tasks|deadlines|list|agenda|plan)\\b|\\b(?:for|on)\\s+tomorrow\\b"
                + "|\\bhave\\s+(?:on\\s+|planned\\s+)?tomorrow\\b|\\b(?:list|agenda|plan|schedule)\\b.*\\btomorrow\\b");
        /** "This week", "the end of the week", "the rest of the week", "due soon", "coming up". */
        private static final Pattern WEEK_WORDS = Pattern.compile("\\b(?:this week|(?:end|rest) of (?:the|this) week"
                + "|due soon|coming up)\\b");
        private static final Pattern NEXT_WEEK_WORDS = Pattern.compile("\\bnext week\\b");

        /**
         * The cut the words ask for, or empty for the agenda of what can start now ("what should I do today?" is
         * that agenda; "which tasks are due today?" is the cut).
         */
        static Optional<TaskFilter> of(String said) {
            String t = said == null ? "" : TargetCheck.withoutNegated(said).toLowerCase(Locale.ROOT);
            return OVERDUE_WORDS.matcher(t).find() ? Optional.of(OVERDUE)
                    : TODAY_WORDS.matcher(t).find() ? Optional.of(DUE_TODAY)
                    : TOMORROW_WORDS.matcher(t).find() ? Optional.of(DUE_TOMORROW)
                    : WEEK_WORDS.matcher(t).find() ? Optional.of(DUE_THIS_WEEK)
                    : NEXT_WEEK_WORDS.matcher(t).find() ? Optional.of(DUE_NEXT_WEEK) : Optional.empty();
        }
    }

    /**
     * A cut of the tasks by a day the question names that no {@link TaskFilter} makes: "this weekend" (its Saturday and
     * Sunday), "next weekend", "this month", "next month", "in March" (the whole month), "by Friday", "before the
     * weekend" (its Friday) and "the end of the month" (everything due until then, late tasks included), "on Friday",
     * "for December 3" (that day). Today and tomorrow are left to the agenda and to {@link TaskFilter}.
     */
    record DatedCut(String label, LocalDate from, LocalDate to) {
        private static final Pattern WEEKEND = Pattern.compile("\\b(?<next>next\\s+)?weekend\\b");
        private static final Pattern UNTIL = Pattern.compile("^(?:by|until|till|before)\\b");
        private static final Pattern BEFORE_WEEKEND = Pattern.compile("(?s).*\\b(?:by|until|till|before)\\s+"
                + "(?:the\\s+|this\\s+)?");
        /** "This month", "next month", "in March", "during December": the whole month. */
        private static final Pattern MONTH = Pattern.compile("\\b(?:(?<relative>this|next)\\s+month|(?:in|during)\\s+"
                + "(?<name>january|february|march|april|may|june|july|august|september|october|november|december))\\b"
                + "(?!\\s+\\d)");
        private static final Pattern NEAR_DAY = Pattern.compile("(?:(?:by|until|till|for|on)\\s+)?(?:today|tonight"
                + "|tomorrow|now)");

        static Optional<DatedCut> of(String said, LocalDate today) {
            String t = said == null ? "" : TargetCheck.withoutNegated(said).toLowerCase(Locale.ROOT);
            Matcher weekend = WEEKEND.matcher(t);
            if (weekend.find()) {
                LocalDate saturday = today.getDayOfWeek() == DayOfWeek.SUNDAY ? today.minusDays(1)
                        : today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
                saturday = weekend.group("next") != null ? saturday.plusWeeks(1) : saturday;
                if (BEFORE_WEEKEND.matcher(t.substring(0, weekend.start())).matches()) {
                    // "What's due before the weekend?": everything due by its Friday.
                    LocalDate friday = saturday.minusDays(1);
                    return Optional.of(new DatedCut("tasks due by " + friday, LocalDate.MIN, friday));
                }
                return Optional.of(new DatedCut("tasks due " + (weekend.group("next") != null ? "next" : "this")
                        + " weekend", saturday, saturday.plusDays(1)));
            }
            Matcher month = MONTH.matcher(t);
            if (month.find()) {
                LocalDate first = today.withDayOfMonth(1);
                if ("next".equals(month.group("relative"))) {
                    first = first.plusMonths(1);
                } else if (month.group("name") != null) {
                    Month named = Month.valueOf(month.group("name").toUpperCase(Locale.ROOT));
                    first = first.withMonth(named.getValue());
                    first = first.isBefore(today.withDayOfMonth(1)) ? first.plusYears(1) : first;
                }
                String name = first.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + first.getYear();
                return Optional.of(new DatedCut("tasks due in " + name, first, first.plusMonths(1).minusDays(1)));
            }
            for (DateExpressions.Span span : DateExpressions.datePhraseSpans(t)) {
                String phrase = span.in(t).strip();
                Optional<LocalDate> day = DateExpressions.find(phrase, today);
                if (day.isEmpty() || NEAR_DAY.matcher(phrase).matches()) {
                    continue; // "what should I do today?" is the agenda; "due today" is a TaskFilter
                }
                String before = t.substring(0, span.start()).strip();
                boolean until = UNTIL.matcher(phrase).find() || phrase.matches("(?:the\\s+)?end\\b.*")
                        || before.matches("(?s).*\\b(?:by|until|till|before)$");
                return Optional.of(until
                        ? new DatedCut("tasks due by " + day.get(), LocalDate.MIN, day.get())
                        : new DatedCut("tasks due on " + day.get(), day.get(), day.get()));
            }
            return Optional.empty();
        }
    }

    /**
     * Said instead of a reply that states facts nobody checked, when the sentence was not a question and nothing was
     * proposed: what happened (nothing) and where to look.
     */
    static final String UNCHECKED = "Nothing was changed. To see where things stand, ask \"list my goals\" or "
            + "\"what should I do today?\"";
    /**
     * Said before the model's reply whenever nothing was changed and nothing was asked: the person reads that first,
     * whatever the sentence was ("did you do it?" answered "Yes, I did."). Only a reply from a fixed list of courtesies
     * (see {@link #courtesyReply}) to a courtesy or a greeting alone (see {@link #courtesy}) stands without it; an
     * empty reply is this line alone.
     */
    static final String NOTHING_CHANGED = "Nothing was changed.";

    /**
     * The only replies shown next to the kernel's own lines after a change: they claim nothing those lines do not
     * say, whatever was changed.
     */
    static final List<String> FIXED_REPLIES = List.of("Done.", "Sure.", "Got it.", "Anything else?",
            "Is there anything else?");
    /** The only replies shown alone to a courtesy or a greeting: they claim nothing at all. */
    static final List<String> COURTESY_REPLIES = List.of("You're welcome!", "Hello!", "Hi!", "Glad to help.");

    /**
     * The reply of {@link #FIXED_REPLIES} the model's reply is, compared without case, extra spaces and the full stop
     * or exclamation mark at its end ("done!" is "Done."; a question keeps its question mark), in the list's own
     * words; empty for any other reply, which is then not shown.
     */
    static Optional<String> fixedReply(String reply) {
        return fromList(reply, FIXED_REPLIES);
    }

    /** The reply of {@link #COURTESY_REPLIES} the model's reply is, compared as {@link #fixedReply} compares. */
    static Optional<String> courtesyReply(String reply) {
        return fromList(reply, COURTESY_REPLIES);
    }

    private static Optional<String> fromList(String reply, List<String> list) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        String key = replyKey(reply);
        return list.stream().filter(entry -> replyKey(entry).equals(key)).findFirst();
    }

    /**
     * A reply as {@link #fixedReply} compares it: straight quotes, lower case, single spaces, no closing "." or "!".
     */
    private static String replyKey(String reply) {
        return TargetCheck.straightQuotes(reply).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                .replaceAll("[.!]+$", "");
    }

    /**
     * Words without which a sentence states no rule: what must never or always happen, what waits for the person's
     * yes, a limit. A rule is drafted only from a sentence that holds one of them.
     */
    static final Pattern RULE_WORDS = Pattern.compile("(?i)\\b(?:never|always|don'?t\\s+(?:let|allow)"
            + "|do\\s+not\\s+(?:let|allow)|stop\\s+letting|not\\s+allowed|forbid\\w*|ask\\s+me|without\\s+(?:asking"
            + "|my\\s+(?:ok|okay|yes|approval|permission))|check\\s+with\\s+me|no\\s+more\\s+than|not\\s+more\\s+than"
            + "|at\\s+most|maximum|limit\\w*|every\\s+time|whenever|each\\s+time|rules?|must\\s+not|mustn'?t)\\b");
    /** Said when the model read a rule into a sentence without rule words. */
    static final String NOT_A_RULE = "that does not read as a rule: a rule says what must never or always happen, "
            + "what waits for your yes, or a limit (\"never let agents change my interview goal without asking\")";

    /** A request for a note on a task: "add a note to the menu task", "note on T3: ...". */
    private static final Pattern NOTE_REQUEST = Pattern.compile("(?i)\\b(?:add|put|write|attach|leave|make)\\s+"
            + "(?:a\\s+)?(?:note|comment)\\b"
            + "|\\b(?:note|comment)\\s+(?:on|to|for)\\s+(?:the\\s+|my\\s+)?(?:t\\d+|task)\\b");
    /** A request to rename a task or a goal: "rename the balloons task", "change the title of T3". */
    private static final Pattern RENAME_REQUEST = Pattern.compile("(?i)\\b(?:rename|retitle)\\b"
            + "|\\bchange\\s+(?:the\\s+)?(?:name|title)\\b");
    /** A request to order tasks by hand: "the cake has to wait until the venue is booked", "T5 depends on T3". */
    private static final Pattern ORDER_REQUEST = Pattern.compile("(?i)\\bdepends?\\s+on\\b|\\b(?:has|have|needs?)\\s+to"
            + "\\s+wait\\s+(?:until|for)\\s+(?:the\\s+|my\\s+)?(?:t\\d+"
            + "|\\w+\\s+(?:is|are)\\s+(?:done|booked|finished|ready))"
            + "|\\b(?:must|should)\\s+come\\s+(?:after|before)\\b");
    /** A request to set a priority by hand: "make the invitations my top priority", "prioritise the cake". */
    private static final Pattern PRIORITY_REQUEST = Pattern.compile("(?i)\\b(?:make|set|put)\\b.*\\b(?:top|high|highest"
            + "|first|low|lowest)\\s+priority\\b|\\bprioriti[sz]e\\b|\\bmore\\s+important\\s+than\\b");
    /**
     * A question rather than a request: it ends with a question mark or starts with a question word. "Can you",
     * "could you", "would you" and "will you" start requests ("can you rename the balloons task?").
     */
    private static final Pattern QUESTION_FORM = Pattern.compile("(?i)^(?!\\s*(?:can|could|would|will)\\s+you\\b)"
            + "(?:.*\\?\\s*$|\\s*(?:what|which|why|how|when|where|who|is|are|do|does|can|could)\\b)");
    /** A request to cancel or delete everything at once: "delete everything", "cancel all my goals". */
    private static final Pattern EVERYTHING = Pattern.compile("(?i)\\b(?:cancel|delete|remove|drop|clear|wipe|erase)"
            + "\\s+(?:all|every|everything)\\b");
    /** A question about the world outside the person's goals: the weather, the news, a joke. */
    private static final Pattern OFF_TOPIC = Pattern.compile("(?i)\\b(?:weather|forecast|joke|stock\\s+market"
            + "|exchange\\s+rate|recipe|translate|capital\\s+of)\\b");
    static final String ONE_AT_A_TIME = "Nothing was changed: I cancel or drop one goal or task at a time, and their "
            + "history is always kept. Say which one, for example \"cancel the passport goal\".";
    static final String ONLY_GOALS = "I only know your goals and tasks, so I cannot answer that.";
    /** Someone else taking a task: "my sister will take care of the flowers", "Tom will handle the music". */
    private static final Pattern DELEGATION = Pattern.compile("(?i)\\b(?!(?:i|we|you)\\b)\\w+\\s+"
            + "(?:will|is\\s+going\\s+to|'ll)\\s+(?:take\\s+care\\s+of|handle|look\\s+after|sort\\s+out"
            + "|deal\\s+with)\\b");
    static final String NO_OWNERS = "Tasks have no owners yet. To let someone help, invite them to the goal (\"let Ana "
            + "help with the birthday dinner until Saturday\"), or drop the task if it is no longer yours.";
    /** A question about what the person finished: "what did I get done today?", "what have I completed?". */
    private static final Pattern DONE_QUESTION = Pattern.compile("(?i)\\bwhat\\s+(?:did|have)\\s+i\\s+(?:get\\s+done"
            + "|got\\s+done|finish(?:ed)?|complete[d]?|done|do|achieve[d]?)\\b");
    static final String NO_DONE_LIST = "I cannot list what you finished across your goals yet. Each goal shows its "
            + "finished tasks (\"show me the birthday party\"), and \"what do you know about how I work?\" counts "
            + "them.";
    static final String NO_NOTES = "Tasks have no notes yet. To keep it, add it as a task of its own, for example "
            + "\"add asking about vegetarian options to the birthday party\".";
    static final String NO_RENAME = "Tasks and goals cannot be renamed yet. Add the task under its new name and drop "
            + "the old one.";
    static final String NO_ORDER = "The order of the tasks comes from the plan, and I cannot change it by hand yet. "
            + "You can mark a task blocked (\"the cake is blocked until the venue is booked\") or move its date.";
    static final String NO_PRIORITY = "Priorities come from the deadlines and the plan, and I cannot set them by hand "
            + "yet. Moving a task's date earlier raises it.";

    /**
     * The kernel's answer to a request for something it does not do: cancelling everything at once, or what tasks do
     * not have yet (a note, a new name, an order or a priority set by hand); a question asks for none of these ("what
     * is my top priority?"), but one about the world outside the person's goals ("what's the weather tomorrow?") or
     * about everything the person finished is told so. Empty for every other sentence, and for a sentence with
     * several instructions ({@link #instructions}).
     */
    static Optional<String> unsupported(String said) {
        if (said == null || said.isBlank() || instructions(said).size() > 1) {
            // With several instructions ("rename the gift task and move it to Sunday") the others are read as usual.
            return Optional.empty();
        }
        if (QUESTION_FORM.matcher(said).find()) {
            return OFF_TOPIC.matcher(said).find() ? Optional.of(ONLY_GOALS)
                    : DONE_QUESTION.matcher(said).find() ? Optional.of(NO_DONE_LIST) : Optional.empty();
        }
        return EVERYTHING.matcher(said).find() ? Optional.of(ONE_AT_A_TIME)
                : DELEGATION.matcher(said).find() ? Optional.of(NO_OWNERS)
                : NOTE_REQUEST.matcher(said).find() ? Optional.of(NO_NOTES)
                : RENAME_REQUEST.matcher(said).find() ? Optional.of(NO_RENAME)
                : ORDER_REQUEST.matcher(said).find() ? Optional.of(NO_ORDER)
                : PRIORITY_REQUEST.matcher(said).find() ? Optional.of(NO_PRIORITY)
                : Optional.empty();
    }

    /** Words that take a blocked task back to work: "isn't blocked anymore", "no longer stuck", "unblocked". */
    private static final Pattern UNBLOCKED = Pattern.compile("(?i)\\b(?:(?:is|are)\\s*n'?o?t|not|no\\s+longer)\\s+"
            + "(?:blocked|stuck|waiting)\\b|\\bunblocked\\b");

    /** Two readings of hours this close are the same: a minute, so a rounded "0.17" for "10 minutes" agrees. */
    static final double SAME_HOURS = 1.0 / 60 + 1e-9;

    /** The answer to a question about what the system does ("what can you do?", "help"). */
    static final String HELP = """
            I keep your goals and tasks, and the agents running here plan them. Talk to me in plain sentences, \
            for example:
              "Plan my sister's birthday party by Saturday"
              "what should I do today?"
              "I booked the venue, it took an hour"
              "move the party to Sunday"
              "what is in my inbox?\"""";
    /** "Remind me to ...", "please remind me to ...", "can you remind me to ...". */
    private static final Pattern REMIND_ME = Pattern.compile("(?i)^\\s*(?:please\\s+)?(?:(?:can|could|would|will)"
            + "\\s+you\\s+(?:please\\s+)?)?remind\\s+me\\s+(?:to|that|about)\\b");
    /** Words that put something in a goal: "for the picnic", "to my passport list", "in the move". */
    private static final Pattern INTO_GOAL = Pattern.compile("(?i)\\b(?:for|to|in|on|into|onto)\\s+(?:the|my|our)\\s+"
            + "(?<goal>[\\p{L}\\d'-]+(?:\\s+[\\p{L}\\d'-]+){0,3})");

    /** True when the words put what they say in a goal they name clearly ("... for the picnic"). */
    private boolean addressesGoal(String said, Grounding.Snapshot snapshot) {
        Matcher m = INTO_GOAL.matcher(said);
        while (m.find()) {
            String goal = m.group("goal").replaceAll("(?i)\\s+(?:goal|list|project)\\b.*$", "");
            if (targets.clearMatch(goal, snapshot.documents()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** "What's on my plate (today)?", "what do I have on my plate": the agenda. */
    private static final Pattern ON_MY_PLATE = Pattern.compile("(?i)\\bon\\s+my\\s+plate\\b");

    /**
     * The sentence with idioms said plainly, so no reader takes their words for a goal's or task's: "on my plate" is
     * "on my agenda" (not the plates of the picnic), and "I can't do the speaker until Saturday" is "I'm blocked on the
     * speaker until Saturday".
     */
    static String plainIdioms(String said) {
        return said == null ? null : CANT_DO_UNTIL.matcher(ON_MY_PLATE.matcher(said).replaceAll("on my agenda"))
                .replaceAll(m -> Matcher.quoteReplacement((m.group(1).equalsIgnoreCase("we") ? "we're" : "I'm")
                        + " blocked on " + m.group(2) + " until"));
    }

    /**
     * "I can't do the speaker until Saturday": the person is blocked on the task until then. Said that way, no reader
     * takes the task for a negated word.
     */
    private static final Pattern CANT_DO_UNTIL = Pattern.compile("(?i)\\b(i|we)\\s+(?:can'?t|cannot|am\\s+unable\\s+to"
            + "|are\\s+unable\\s+to)\\s+(?:do|start|finish|work\\s+on|get\\s+to)\\s+(.+?)\\s+(?:until|till)\\b");
    /** A question about whether the system runs: "is everything running?", "are the agents up?". */
    private static final Pattern STATUS_QUESTION = Pattern.compile("(?i)\\b(?:is|are)\\s+(?:everything|it|the\\s+system"
            + "|things|all|automatic\\s+work|the\\s+agents?)\\s+(?:running|working|up|ok|okay|paused|alive)\\b"
            + "|\\bsystem\\s+status\\b");
    /** Words that report on a task rather than ask for something new: "stuck", "blocked", "finished", "working on". */
    private static final Pattern TASK_NEWS = Pattern.compile("(?i)\\b(?:stuck|blocked|waiting\\s+(?:for|on)|finished"
            + "|done\\s+with|working\\s+on|started|in\\s+progress|took\\s+(?:me\\s+)?(?:about\\s+)?\\d"
            + "|can'?t\\s+(?:start|continue|finish))\\b");
    /** Said when the model read a request for a new capability into news about a task. */
    static final String NOT_A_REQUEST = "that reads as news about a task, not as a request for something new: say what "
            + "happened to which task, for example \"the venue task is blocked\" or \"I finished the invitations\"";
    /** Words that give up a whole goal: "not doing it anymore", "give up on", "forget about", "no longer want". */
    private static final Pattern GIVING_UP = Pattern.compile("(?i)\\bnot\\s+(?:doing|going\\s+to\\s+do)\\b.*"
            + "\\b(?:anymore|any\\s+more)\\b|\\bgive\\s+up\\s+on\\b|\\bforget\\s+about\\b"
            + "|\\bno\\s+longer\\s+(?:want|need|doing)\\b");
    /** A bare yes or no. */
    private static final Pattern YES_OR_NO = Pattern.compile(
            "(?i)^\\s*(?:y|yes|yeah|yep|yup|n|no|nope|nah)\\s*[.!]*\\s*$");
    /** The answer to a bare yes or no when nothing waits for one. */
    static final String NOTHING_ASKED = "Nothing is waiting for a yes or no right now.";
    /** The answer to a greeting alone. */
    static final String GREETING = "Hello! Tell me something you want to get done, or ask \"what should I do today?\"";
    /** A question about what the system does, and nothing else. */
    private static final Pattern HELP_QUESTION = Pattern.compile("(?i)^\\s*(?:help|help me|what can you do"
            + "|what do you do|what can i (?:do|say|ask)(?: here)?|how does (?:this|it) work"
            + "|how do i use (?:this|you|it)|who are you|what are you)\\s*[?!.]*\\s*$");
    /** A newcomer's question: "Hi! I'm new here, how do I start?", "where do I begin?". */
    private static final Pattern GETTING_STARTED = Pattern.compile("(?i)\\b(?:how\\s+do\\s+i\\s+(?:start|begin"
            + "|get\\s+started)|where\\s+do\\s+i\\s+(?:start|begin)|i'?m\\s+new\\s+here|i\\s+am\\s+new\\s+here"
            + "|getting\\s+started|how\\s+does\\s+this\\s+(?:app|thing|system|work))\\b");
    private static final Set<String> THANKS = Set.of("thanks", "thank", "thx", "ty", "cheers");
    private static final Set<String> FAREWELL = Set.of("bye", "goodbye", "later", "night");
    private static final Set<String> HELLO = Set.of("hello", "hi", "hey", "morning", "afternoon", "evening");
    /** Courtesy words that may also answer a suggestion waiting in the inbox: "ok", "sounds fine", "great". */
    private static final Set<String> AGREEING = Set.of("ok", "okay", "sure", "fine", "sounds", "alright", "right",
            "perfect", "great", "cool", "awesome", "excellent", "brilliant", "wonderful", "lovely", "nice");

    /**
     * The kernel's own answer to a sentence that asks for nothing the model is needed for: a question about what the
     * system does gets {@link #HELP}; a courtesy or greeting alone ({@link #courtesy}) gets a reply from a fixed list,
     * by its words: thanks before a farewell before a greeting, anything else ("ok, great") {@code "Glad to help."}.
     * A courtesy with a word that may agree to something ("ok thanks", "sounds good") is left to the interpreter while
     * {@code suggestionsWait} says an inbox message waits for an answer, and so is a bare yes or no, which otherwise
     * gets {@link #NOTHING_ASKED}. Empty for every other sentence.
     */
    static Optional<String> fixedAnswer(String said, BooleanSupplier suggestionsWait) {
        if (said == null || said.isBlank()) {
            return Optional.empty();
        }
        if (HELP_QUESTION.matcher(said).matches() || said.strip().matches("[?!.]+")
                || GETTING_STARTED.matcher(said).find() && said.strip().split("\\s+").length <= 10) {
            return Optional.of(HELP);
        }
        if (YES_OR_NO.matcher(said).matches()) {
            // A yes or no answers a suggestion waiting in the inbox; with none waiting it answers nothing.
            return suggestionsWait.getAsBoolean() ? Optional.empty() : Optional.of(NOTHING_ASKED);
        }
        if (!courtesy(said)) {
            return Optional.empty();
        }
        Set<String> words = Set.copyOf(Arrays.asList(TargetCheck.normalise(TargetCheck.straightQuotes(said))
                .split(" "))); // "ok ok": words repeat, so no Set.of
        if (words.stream().anyMatch(AGREEING::contains) && suggestionsWait.getAsBoolean()) {
            return Optional.empty();
        }
        return Optional.of(words.stream().anyMatch(THANKS::contains) ? "You're welcome!"
                : words.stream().anyMatch(FAREWELL::contains) ? "Bye!"
                : words.stream().anyMatch(HELLO::contains) ? GREETING
                : "Glad to help.");
    }

    /** Words of courtesy and greeting that ask for nothing: "thanks", "hello", "ok", "great", "good morning". */
    private static final Set<String> COURTESY = Set.of("thanks", "thank", "you", "thx", "ty", "cheers", "so", "much",
            "very", "a", "lot", "hello", "hi", "hey", "there", "good", "morning", "afternoon", "evening", "night", "ok",
            "okay", "great", "cool", "nice", "perfect", "awesome", "wonderful", "excellent", "brilliant", "got", "it",
            "bye", "goodbye", "see", "ya", "later", "again", "all", "right", "alright", "sounds", "fine", "lovely");
    /**
     * True when a sentence is a courtesy or a greeting and nothing else ("thanks!", "hello", "ok, great", "good
     * morning"): it asks for nothing, so a reply to it from the fixed list of courtesies (see {@link #courtesyReply})
     * needs no "Nothing was changed.".
     */
    static boolean courtesy(String said) {
        if (said == null || said.isBlank()) {
            return true;
        }
        String words = TargetCheck.normalise(TargetCheck.straightQuotes(said));
        return !words.isEmpty() && Arrays.stream(words.split(" ")).allMatch(COURTESY::contains);
    }

    /**
     * The words for the task keys a sentence names (outside its negated parts) that no proposed change covers ("T4,
     * T5, T6, T7, T8, T9 and T10 are done" read as changes of T4 to T8): "T9, T10 were not changed: say what should
     * change for them in a sentence of their own". A key an outcome of the turn already names ("there is no task T9")
     * is not repeated. Empty when every key named is covered, or no proposed change is aimed at a task (a key then
     * only places something, as in "move the picnic to the day after T4").
     */
    static Optional<String> uncoveredKeys(String said, List<Action> proposals, List<Executed> outcomes) {
        Set<String> covered = new HashSet<>();
        boolean taskChanges = false;
        for (Action a : proposals) {
            if (TASK_ACTIONS.contains(a.type()) && a.task() != null) {
                taskChanges = true;
                covered.addAll(namedKeys(a.task()));
            }
        }
        if (!taskChanges) {
            return Optional.empty();
        }
        outcomes.forEach(e -> covered.addAll(namedKeys(e.message())));
        List<String> left = namedKeys(TargetCheck.withoutNegated(said)).stream().filter(k -> !covered.contains(k))
                .toList();
        if (left.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join(", ", left) + (left.size() == 1 ? " was" : " were") + " not changed: say what "
                + "should change for " + (left.size() == 1 ? "it in a sentence of its own" : "them in a sentence of their own"));
    }

    /** Words of a question about the state of goals and tasks: deadlines, progress, what is left. */
    private static final Pattern DATA_WORDS = Pattern.compile("\\b(?:due|deadlines?|done|finished|complete|completed"
            + "|late|overdue|behind|on track|left|remaining|how many|how much|status|progress|scheduled|planned)\\b");

    /**
     * True when a question asks about the person's own data: it speaks of deadlines, progress or what is left ("is my
     * thesis due on Friday?", "how many tasks are left?"), names a date, or shares a content word with a goal or task
     * ("is the picnic on track?"). Questions about the assistant itself ("how do I add a goal?") are none.
     */
    static boolean dataQuestion(String said, Map<String, List<String>> documents) {
        if (!question(said)) {
            return false;
        }
        String t = TargetCheck.straightQuotes(said).toLowerCase(Locale.ROOT);
        return DATA_WORDS.matcher(t).find() || ActionDates.statesDate(t, null, LocalDate.of(2000, 1, 3))
                || documents.values().stream().anyMatch(texts -> TargetCheck.sharesWord(said, texts));
    }

    private static final Pattern FACTS = Pattern.compile("\\d|\\b(?:monday|tuesday|wednesday|thursday|friday"
            + "|saturday|sunday|january|february|march|april|june|july|august|september|october|november|december"
            + "|today|tomorrow|yesterday|tonight)\\b|\\b(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven"
            + "|twelve|several|no)\\s+(?:\\w+\\s+)?(?:goals?|tasks?|messages?|rules?|steps?|approvals?)\\b",
            Pattern.CASE_INSENSITIVE);

    /** True when a reply states counts, dates or ids ("You have 3 overdue goals", "due Friday", "goal 3f9a11c2"). */
    static boolean statesFacts(String reply) {
        return reply != null && FACTS.matcher(reply).find();
    }

    /** A quotation in a reply: double quotes, straight or curly. */
    private static final Pattern QUOTED = Pattern.compile("[\"\u201c\u201d]");

    /**
     * True when a reply says something the kernel cannot check: it states counts, dates or ids (see
     * {@link #statesFacts}, task keys such as T3 included), quotes something, or names a goal or task by a content
     * word of its texts ("your passport renewal").
     */
    static boolean unverifiable(String reply, Map<String, List<String>> documents) {
        if (reply == null || reply.isBlank()) {
            return false;
        }
        if (statesFacts(reply) || QUOTED.matcher(reply).find()) {
            return true;
        }
        Set<String> words = TargetCheck.words(reply);
        return !words.isEmpty() && documents.values().stream()
                .anyMatch(texts -> texts.stream().anyMatch(text -> TargetCheck.words(text).stream()
                        .anyMatch(words::contains)));
    }

    private static final Pattern QUESTION_START = Pattern.compile("^\\s*(?:what|whats|what's|which|when|where|who"
            + "|why|how|hows|how's|is|are|am|do|does|did|can|could|should|will|would|have|has|any)\\b"
            + "|^\\s*(?:please\\s+)?(?:list|show|display|give me)\\b|\\b(?:tell me|show me|let me know)\\b");

    /**
     * True when the sentence asks something: it ends with a question mark, starts like a question, or asks to be
     * shown something ("list my overdue goals").
     */
    static boolean question(String said) {
        return said != null && (said.strip().endsWith("?")
                || QUESTION_START.matcher(said.toLowerCase(Locale.ROOT)).find());
    }

    /**
     * The sentence states how long something took or takes ("2 hours", "half an hour", "90 min", "1.5h"), even only
     * vaguely ("a couple of hours"); read by {@link Durations}, the one reader of amounts of time.
     */
    static boolean statesDuration(String said) {
        return said != null && Durations.states(said);
    }

    /**
     * Every duration a sentence states with a number, in hours, in the order said (see {@link Durations#hours}):
     * "2 hours" is 2, "90 min" 1.5, "half an hour" 0.5, "an hour and a half" 1.5; "1 hour 30 minutes", "1h30",
     * "1h30min" and "1 h 30" are one duration of 1.5.
     */
    static List<Double> statedDurations(String said) {
        return Durations.hours(said);
    }

    /**
     * Hours as the kernel reads them for a change, the note for the question that asks about them (empty when nothing
     * needs saying), whether the hours are too unsure to record without asking (see {@link #simpleAmount}), and the
     * model's hours when the words state other hours than the model read ({@code assistant}, null otherwise): then
     * neither is offered, and the person says the hours they mean.
     */
    record Hours(Double hours, String note, boolean unsure, Double assistant) {

        Hours(Double hours, String note) {
            this(hours, note, false, null);
        }

        Hours(Double hours, String note, boolean unsure) {
            this(hours, note, unsure, null);
        }

        /** The words state {@code read} and the model read {@code model}: two readings, of which none is applied. */
        static Hours disputed(double read, double model) {
            return new Hours(read, "", true, model);
        }
    }

    /**
     * The hours of a status change or estimate as the person said them (see {@link #taskHours}); a single other
     * duration is the one offered in the question.
     */
    static Double spokenHours(Double proposed, String said) {
        return taskHours(proposed, said, false).hours();
    }

    /**
     * Words that put a duration on finished work, at most two words before the amount: "it took 2 hours", "it took me
     * about 2 hours", "spent 90 minutes", "done in an hour", "finished it in an hour", "finished T3 of the picnic in
     * an hour" (at most four words between), "done after 2 hours". "In" counts only after a word of completion, so
     * "in 2 hours I'll start T4" puts nothing on finished work, and not after a present form of "be": "T3 is done in
     * 2 hours" and "I'm finished with T3 in 2 hours" say when the work will be done (see {@link
     * PlainWords#NOT_AFTER_PRESENT_BE}). The words between hold no number and no punctuation, so "done in 2 hours, 3
     * hours less than planned" puts only the 2 hours on the work.
     */
    private static final Pattern TOOK = Pattern.compile("(?i)(?:\\b(?:took|taken|spent|lasted|needed|required"
            + "|after)\\b|" + PlainWords.NOT_AFTER_PRESENT_BE
            + "\\b(?:done|finished|completed?|did|wrapped\\s+up)\\b(?:\\s+(?:[^\\s\\d,;.:!?]+|t\\d{1,3}))"
            + "{0,4}?\\s+in\\b)"
            + "(?:\\s+[^\\s\\d,;.:!?]+){0,2}\\s*$");
    /**
     * Words that make an amount relative, a rate, a repeat, a total of several things or no time the work took ("2
     * hours longer", "3 hours less than planned", "an extra hour", "2 hours too many", "2 hours past the estimate", "4
     * hours ahead of schedule", "2 hours a day for a week", "2 hours twice", "3 hours in total", "they took 2 hours
     * together", "2 hours left", "2 hours before the deadline"): the hours of one piece of work cannot be read from
     * them.
     */
    private static final Pattern RATE_OR_TOTAL = Pattern.compile("(?i)\\b(?:together|in total|total|totals|combined"
            + "|altogether|both|each|per|every|twice|again|times|between them|more|extra|additional|another|longer"
            + "|shorter|less|quicker|faster|slower|early|earlier|late|later|ago|short of|saved|too long|too many"
            + "|too much|than|instead of|left|remaining|before|past|over|under|ahead|behind|on top|plus|beyond"
            + "|schedule)\\b");

    /** {@link #taskHours(Double, String, boolean, String, String)} for a sentence about one task. */
    static Hours taskHours(Double proposed, String said, boolean completion) {
        return taskHours(proposed, said, completion, null, null);
    }

    /**
     * {@link #taskHours(Double, String, boolean, ActionDates.TaskName, String, List, long)} for the task {@code key}
     * titled {@code title}, with no other task known and one completion in the sentence.
     */
    static Hours taskHours(Double proposed, String said, boolean completion, String key, String title) {
        return taskHours(proposed, said, completion, new ActionDates.TaskName(key, title), null, List.of(), 1);
    }

    /**
     * The hours of a status change or estimate of the task {@code me}, read from the amounts the words hold true (see
     * {@link Durations#affirmed}: "3 hours, not 5" states 3, "2 hours, sorry, 3" states 3), and only from the task's
     * own part of the sentence (see {@link ActionDates#taskPart}): the clauses that name it by key or by title words,
     * where the goal's {@code others} tasks count by key and by title words too ("T3 took 2 hours and T4 took 3
     * hours" gives T3 2 hours; "I reserved the spot, the invitations took 2 hours" gives "Reserve the spot" none), and
     * from the first clause that names the task on (a clause before it speaks of something else: "the picnic took 2
     * hours of planning and T3 is done" gives T3 none). Task keys and names with digits ("T10", "r11") are never read
     * as amounts. A completion with several amounts in its part takes the one that follows "took", "spent", "done in"
     * or "after" ("the estimate was 5 hours but it took 2" is 2).
     *
     * <p>Hours are recorded without a question only when two readers agree: the kernel reads one simple amount (see
     * {@link #simpleAmount}) and the model read the same. One amount said for each task ("T5 and T7 are done, they
     * took 2 hours each", see {@link Durations#each}) is every such task's own amount. Words that state other hours
     * than the model read give no hours to offer: the reply names both and the person says the hours they mean (see
     * {@link Hours#disputed}). The model gave none: a completion's one simple amount of work is asked about with the
     * kernel's reading ("T3 is done, it took 2 hours"); one that is not simple, and several amounts that may add up
     * ("an hour this morning and an hour this afternoon"), leave the hours out and ask the person to say them. The
     * kernel reads no number of its own, so the model's is asked about: from several amounts, from a duration said
     * only vaguely ("a couple of hours"), from words that are not simple, and from a relative amount, a rate, a repeat
     * or a total ("3 hours less than planned", "2 hours a day for a week", "3 hours in total"). Asked about, with no
     * hours recorded: a part that cannot be told apart; an amount that the part shares with other tasks ("T3 and T4
     * are done, it took 2 hours"); with several completions in the sentence and fewer amounts than completions, an
     * amount the clauses naming this task do not hold themselves ("I reserved the spot and sent the invitations, it
     * took 2 hours"); hours of the model's where the part states none, or where no word says the work took them.
     *
     * @param goalText    the goal's text, whose words name none of its tasks
     * @param completions how many tasks the sentence marks done
     */
    static Hours taskHours(Double proposed, String said, boolean completion, ActionDates.TaskName me, String goalText,
                           List<ActionDates.TaskName> others, long completions) {
        String words = said == null ? "" : said;
        String label = me == null || me.key() == null || me.key().isBlank() ? "this task" : me.key();
        // Amounts are read without task keys, names with digits and the numbers of the task's or goal's own words
        // ("T10", "QA r11", "buy 2 tickets"), every character in its place.
        UnaryOperator<String> clean = w -> withoutNumbersOf(PlainWords.withoutLabels(w),
                me == null ? null : me.title(), goalText);
        Function<String, List<Durations.Amount>> amountsOf = w -> Durations.affirmed(clean.apply(w));
        List<Durations.Amount> all = amountsOf.apply(words);
        ActionDates.TaskPart part;
        if (me == null || (me.key() == null || me.key().isBlank()) && (me.title() == null || me.title().isBlank())) {
            // Without a key or a title, the task's part can be told only when the sentence is about one task.
            boolean several = namedKeys(words).size() > 1 || all.size() > 1;
            String own = several ? ActionDates.ownPart(words, null, null) : words;
            part = own == null ? null : new ActionDates.TaskPart(own, own, own, false, false);
        } else {
            part = ActionDates.taskPart(words, me, goalText, others);
        }
        if (part == null) {
            return proposed == null && (!completion || all.isEmpty()) ? new Hours(null, "")
                    : new Hours(null, " (no hours recorded: I could not tell which part of your words gives the hours "
                    + "of " + label + ")");
        }
        part = fromNamingClause(part);
        String text = part.text();
        // "T5 and T7 are done, they took 2 hours each": one amount said for each task (see Durations#each) is this
        // task's own amount, whatever other tasks share the part.
        Optional<Double> each = Durations.each(clean.apply(text));
        if (each.isPresent()) {
            double h = rounded(each.get());
            if (proposed == null) {
                return completion && WORK_TOOK.matcher(text).find() ? new Hours(h, NO_MODEL_HOURS, true)
                        : new Hours(null, "");
            }
            return Math.abs(h - proposed) < 1e-6 ? new Hours(proposed, "") : Hours.disputed(h, proposed);
        }
        List<Durations.Amount> amounts = amountsOf.apply(text);
        if (part.shared() && !amounts.isEmpty() && !amountsOf.apply(part.alone()).isEmpty()) {
            // "T3 and T4 are done, T3 took 1 hour": the clause about this task alone holds its amount.
            text = part.alone();
            amounts = amountsOf.apply(text);
        } else if (part.shared() && !amounts.isEmpty()) {
            amounts = List.of(); // only a clause shared with other tasks holds an amount: see below
        }
        String own = text;
        List<Durations.Amount> held = amounts;
        boolean shared = part.shared() && held.isEmpty() && !amountsOf.apply(part.text()).isEmpty()
                || !held.isEmpty() && completion && completions > 1 && all.size() < completions
                && amountsOf.apply(part.named()).isEmpty();
        if (shared) {
            return proposed == null && !completion ? new Hours(null, "") : new Hours(null, " (no hours recorded: "
                    + "your words give one amount for " + label + " and other tasks together)");
        }
        if (completion && held.size() > 1) {
            List<Durations.Amount> took = held.stream().filter(a -> TOOK.matcher(own.substring(0,
                    Math.min(a.start(), own.length()))).find()).toList();
            if (!took.isEmpty()) {
                held = took; // "the estimate was 5 hours but it took 2": the work took 2
            }
        }
        List<Durations.Amount> exact = held.stream().filter(a -> !a.vague()).toList();
        List<Double> stated = exact.stream().map(Durations.Amount::hours).distinct().toList();
        // Relative, rate or total words count in the clause of an amount ("2 hours a day"), not elsewhere ("both
        // done").
        boolean rate = held.stream().map(a -> amountClause(own, a)).anyMatch(clause ->
                RATE_OR_TOTAL.matcher(clause).find() || Durations.perDay(clause));
        boolean simple = simpleAmount(clean.apply(own)) && !rate;
        boolean took = held.stream().anyMatch(a -> TOOK.matcher(own.substring(0, Math.min(a.start(), own.length())))
                .find());
        String several = exact.size() > 1 ? String.join(" h and ", exact.stream().map(a -> hoursText(a.hours()))
                .toList()) + " h" : "";
        if (proposed == null) {
            if (!completion || exact.isEmpty() || !took) {
                return new Hours(null, "");
            }
            if (exact.size() > 1) {
                // "it took an hour this morning and an hour this afternoon": amounts that may add up give no number
                return new Hours(null, " (no hours recorded: your words give " + several + "; say how long " + label
                        + " took)");
            }
            if (rate) {
                return new Hours(null, " (no hours recorded: your words give a relative amount, a rate or a total, "
                        + "not the hours of " + label + ")");
            }
            // One amount the work took and no reading of the model's: asked about with the kernel's reading when the
            // words are simple; otherwise the person says the hours.
            return simple ? new Hours(rounded(exact.getFirst().hours()), NO_MODEL_HOURS, true)
                    : new Hours(null, " (no hours recorded: your words do not give one amount for sure; say how long "
                    + label + " took)");
        }
        if (held.isEmpty()) {
            // Hours stated only for another task are not this task's: asked about, without hours.
            return all.isEmpty() ? new Hours(null, "") : new Hours(null, " (no hours recorded: your words give no "
                    + "hours for " + label + ", the assistant read " + hoursText(proposed) + " h)");
        }
        if (completion && !took) {
            // "T3 is done, I have 2 hours left today": no word says the work took that time, whatever the model read.
            return new Hours(null, " (no hours recorded: your words do not say how long " + label + " took, the "
                    + "assistant read " + hoursText(proposed) + " h)");
        }
        if (exact.size() > 1) {
            // Several amounts that may add up: the kernel reads no number of its own, so the model's is asked about.
            return new Hours(proposed, " (your words give " + several + "; " + hoursText(proposed)
                    + " h is the assistant's reading)", true);
        }
        if (stated.isEmpty() || rate) {
            return new Hours(proposed, "", !simple);
        }
        if (Math.abs(stated.getFirst() - proposed) <= SAME_HOURS) {
            // "10 minutes" is 0.1666 h, which the model writes 0.17: the same reading, kept as the words give it.
            return new Hours(rounded(stated.getFirst()), "", !simple);
        }
        return Hours.disputed(rounded(stated.getFirst()), proposed);
    }

    /**
     * The words with every number that the given names hold blanked where the words repeat it with its neighbour
     * ("2 tickets" of "Buy 2 tickets", "buy 2"), every character in its place: such a number is part of a name, not an
     * amount of time.
     */
    static String withoutNumbersOf(String words, String... names) {
        if (words == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(words);
        for (String name : names) {
            String[] t = TargetCheck.normalise(name).split(" ");
            for (int i = 0; i < t.length; i++) {
                if (t[i].isEmpty() || !Character.isDigit(t[i].charAt(0))) {
                    continue;
                }
                List<String> phrases = new ArrayList<>();
                if (i + 1 < t.length) {
                    phrases.add(Pattern.quote(t[i]) + "\\s+" + Pattern.quote(t[i + 1]));
                }
                if (i > 0) {
                    phrases.add(Pattern.quote(t[i - 1]) + "\\s+" + Pattern.quote(t[i]));
                }
                for (String phrase : phrases) {
                    Matcher m = Pattern.compile("(?i)\\b" + phrase + "\\b").matcher(words);
                    while (m.find()) {
                        for (int k = m.start(); k < m.end(); k++) {
                            out.setCharAt(k, ' ');
                        }
                    }
                }
            }
        }
        return out.toString();
    }

    /**
     * The task's part from its first clause that names it on (see {@code ActionDates.TaskPart.named}): a clause
     * before that one names no task and speaks of something else ("the picnic took 2 hours of planning and T3 is
     * done"), so its hours are not the task's. The part as it is when no clause names the task.
     */
    static ActionDates.TaskPart fromNamingClause(ActionDates.TaskPart part) {
        if (!part.own()) {
            return part;
        }
        String named = part.named();
        int first = 0;
        while (first < named.length() && named.charAt(first) == ' ') {
            first++;
        }
        if (first == 0 || first >= named.length()) {
            return part;
        }
        return new ActionDates.TaskPart(blankBefore(part.text(), first), named, blankBefore(part.alone(), first),
                part.shared(), true);
    }

    private static String blankBefore(String text, int at) {
        StringBuilder out = new StringBuilder(text);
        for (int k = 0; k < Math.min(at, out.length()); k++) {
            out.setCharAt(k, ' ');
        }
        return out.toString();
    }

    /**
     * The note of hours the kernel read from the words while the model gave none, for the question that asks about
     * them: two readers must agree for hours to be recorded without a question.
     */
    static final String NO_MODEL_HOURS = " (the assistant gave no hours)";

    /** A word that puts an amount on finished work, for an amount said for each task: "they took 2 hours each". */
    private static final Pattern WORK_TOOK = Pattern.compile("(?i)\\b(?:took|taken|spent|lasted|needed|done\\s+in)\\b");

    /** The clause of {@code words} that holds the amount, as the date parser cuts clauses. */
    private static String amountClause(String words, Durations.Amount amount) {
        int at = Math.max(0, Math.min(amount.start(), words.length()));
        int from = DateExpressions.clauseStart(words, at);
        int to = Math.max(at, DateExpressions.clauseEnd(words, at));
        return words.substring(Math.min(from, at), Math.min(words.length(), to));
    }

    /**
     * True when an amount of time read from the words (or that the model agrees with) can be recorded without asking:
     * the words state one amount with a number and nothing that corrects, negates, contrasts or bounds it (see
     * {@link Durations#simple}: not "it took 2 hours, make that 3", "2 hours, not 3" or "a couple of hours").
     */
    static boolean simpleAmount(String words) {
        return Durations.simple(words);
    }

    private static double rounded(double hours) {
        return Math.round(hours * 100) / 100.0;
    }

    /** 2.0 as "2", 1.5 as "1.5". */
    static String hoursText(double hours) {
        return hours == Math.rint(hours) ? String.valueOf((long) hours) : String.valueOf(rounded(hours));
    }

    /**
     * The hours per day a sentence states for the person's capacity ("I have an hour and a half a day" is 1.5, "I work
     * 4 hours on weekdays" 4, "90 minutes a day" 1.5, "I don't have 3 hours a day, only 2" 2, "from 4 hours a day to 2"
     * 2; see {@link Durations#perDayHours}); null when it states none, when several amounts per day compete, or when
     * it speaks of pace ("50% longer"), never of capacity.
     */
    static Double statedDailyHours(String said) {
        if (said == null || ProfileService.pace(said)) {
            return null;
        }
        return Durations.perDayHours(said).orElse(null);
    }

    private static final Pattern FIRST_PERSON = Pattern.compile("\\b(?:i|i'm|im|i've|i'll|me|my)\\b");
    private static final Pattern NOT_ABOUT_ME = Pattern.compile(
            "\\b(?:tasks?|agents?|goals?|capabilit\\w*|assistant|estimator|coach|rules?)\\b");
    private static final Pattern WORKING = Pattern.compile("\\b(?:work|working|available|free|busy|off)\\b");

    /**
     * A first-person statement of capacity or working days ("I can do no more than 2 hours a day", "I only work on
     * weekdays"): it describes the person, it is not a rule about changes.
     */
    static boolean capacityStatement(String said) {
        String t = said == null ? "" : said.toLowerCase(Locale.ROOT);
        if (!FIRST_PERSON.matcher(t).find() || NOT_ABOUT_ME.matcher(t).find()) {
            return false;
        }
        boolean hours = statedDailyHours(t) != null;
        boolean days = WORKING.matcher(t).find() && !ProfileService.days(t).isEmpty();
        return hours || days;
    }

    /**
     * The profile corrections the words state that the model's corrections leave out: a sentence that gives both
     * hours per day and working days ("I work 4 hours on weekdays") corrects both, so when the model proposed a
     * correction of one of them, the other is added (and asked about, as the kernel's own reading). Empty when the
     * model proposed no profile correction or the words state nothing more.
     */
    static List<Action> profileCompanions(List<Action> proposals, String said) {
        Set<String> covered = new HashSet<>();
        boolean correcting = false;
        for (Action a : proposals) {
            Action r = reinterpret(a, said);
            if (r.type() != ActionType.CORRECT_PROFILE) {
                continue;
            }
            correcting = true;
            String key = isProfileKey(r.text()) ? canonicalKey(r.text()) : profileKey(said);
            if (key != null) {
                covered.add(key);
            }
        }
        if (!correcting || said == null) {
            return List.of();
        }
        List<Action> added = new ArrayList<>();
        Double hours = statedDailyHours(said);
        if (hours != null && !covered.contains("dailyHours")) {
            added.add(new Action(ActionType.CORRECT_PROFILE, null, null, "dailyHours", null, null, hours, null, said,
                    null));
        }
        String t = said.toLowerCase(Locale.ROOT);
        // "Every day" and "daily" next to hours give a rate, not a change of working days.
        int days = ProfileService.days(t).size();
        if (WORKING.matcher(t).find() && days > 0 && days < 7 && !covered.contains("workingDays")) {
            added.add(new Action(ActionType.CORRECT_PROFILE, null, null, "workingDays", null, null, null, null, said,
                    null));
        }
        return added;
    }

    /** Words that ask to verify a goal's ledger: "verify", "check its integrity", "was it tampered with", "audit". */
    static final Pattern VERIFY_WORDS = Pattern.compile("\\b(?:verif\\w*|integrity|tamper\\w*|untampered|intact"
            + "|fingerprint\\w*|audit\\w*|prove|proof|hash\\w*|ledger|authentic\\w*|genuine|unaltered|altered)\\b");

    /**
     * A rule the model drafted from a capacity statement becomes the profile correction the person meant; it is
     * asked about before it is stored. A verification of a goal's ledger the words do not ask for (see
     * {@link #VERIFY_WORDS}) becomes showing the goal.
     */
    static Action reinterpret(Action a, String said) {
        if (a.type() == ActionType.VERIFY_GOAL && !said(VERIFY_WORDS, said)) {
            // "did I finish T3 of the picnic?" asks about the goal, not about its ledger: the goal is shown.
            return new Action(ActionType.SHOW_GOAL, a.goal(), a.task(), a.text(), a.dueDate(), a.status(), a.hours(),
                    a.approval(), a.reason(), a.evidence());
        }
        if (a.type() != ActionType.ADD_RULE || !capacityStatement(said)) {
            return a;
        }
        String key = profileKey(said);
        if (key == null || "estimateBias".equals(key)) {
            return a;
        }
        return new Action(ActionType.CORRECT_PROFILE, null, null, key, null, null,
                "dailyHours".equals(key) ? statedDailyHours(said) : null, null, said, a.evidence());
    }

    /** Words of the invitation that a guest's name never holds: "let", "help", "invite", "share", "until", "with". */
    private static final Pattern NOT_IN_A_NAME = Pattern.compile("\\b(?:let|help|invite|share|until|with)\\b");
    private static final Set<String> NOT_A_NAME = Set.of("I", "Let", "Share", "Invite", "Give", "Allow", "Please",
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "January", "February", "March",
            "April", "May", "June", "July", "August", "September", "October", "November", "December");

    /**
     * A guest's name: the model's when it is a name (one to three words), otherwise the first capitalised word of
     * the sentence that is not a command or a date ("let Ana help with the picnic" gives Ana).
     */
    static String guestName(String proposed, String said) {
        if (proposed != null && !proposed.isBlank() && proposed.strip().split("\\s+").length <= 3
                && !NOT_IN_A_NAME.matcher(proposed.toLowerCase(Locale.ROOT)).find()) {
            return proposed.strip();
        }
        for (String word : (said == null ? "" : said).split("[^\\p{L}'-]+")) {
            String w = word.replaceAll("'s$", "");
            if (!w.isEmpty() && Character.isUpperCase(w.charAt(0)) && !NOT_A_NAME.contains(w)) {
                return w;
            }
        }
        return "guest";
    }

    /**
     * Which profile value a sentence corrects: pace phrasing ("50% longer", "slower", "twice as long") is the estimate
     * bias, an amount of time with a per-day phrase ("2 hours a day", "90 minutes a day") is the daily hours, day
     * names are the working days.
     */
    static String profileKey(String said) {
        String t = said == null ? "" : said.toLowerCase(Locale.ROOT);
        if (ProfileService.pace(t)) {
            return "estimateBias";
        }
        if (Durations.states(t) && ProfileService.perDay(t)) {
            return "dailyHours";
        }
        return ProfileService.days(t).isEmpty() ? null : "workingDays";
    }

    /**
     * Two profile keys name the same value ("dailyHours", "daily hours", "work hours"); a blank proposal, or one that
     * is no profile key, names none.
     */
    static boolean sameProfileKey(String proposed, String used) {
        if (!isProfileKey(proposed)) {
            return true;
        }
        return used != null && canonicalKey(proposed).equals(canonicalKey(used));
    }

    /** The stored name of the profile value a text names ("work hours" is dailyHours), or null for none. */
    static String canonicalKey(String text) {
        if (!isProfileKey(text)) {
            return null;
        }
        String label = ActionExecutor.profileLabel(text);
        return label.equals("daily hours") ? "dailyHours" : label.equals("working days") ? "workingDays"
                : "estimateBias";
    }

    /** True for a text that names one of the profile values: daily hours, working days or pace. */
    static boolean isProfileKey(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String label = ActionExecutor.profileLabel(text);
        return label.equals("daily hours") || label.equals("working days") || label.startsWith("pace");
    }

    /** The daily hours of a correction as {@link #dailyHoursReading} offers them. */
    static Double dailyHours(String key, Double proposed, String said) {
        return dailyHoursReading(key, proposed, said).hours();
    }

    /**
     * The daily hours of a correction as the person said them, from the amounts per day the words hold true (see
     * {@link Durations#perDayAmounts}): "I can work 2 hours a day" is 2, "I don't have 3 hours a day, only 2" 2, "drop
     * my capacity from 4 hours a day to 2" 2. A per-day amount the model also gave, or gave none, is used; one the
     * model read otherwise is not offered: both readings are kept (see {@link Hours#disputed}) and the person says
     * the hours they mean. When several amounts per day compete ("8 hours a day but only 2 on tasks"), the model's
     * number when it is among them is asked about, and with none among them no hours are offered. Without a per-day
     * amount the model's number stands and is checked against the words (see {@link #profileGrounded}). Other values
     * carry no hours of their own beyond the model's.
     */
    static Hours dailyHoursReading(String key, Double proposed, String said) {
        if (!"daily hours".equals(ActionExecutor.profileLabel(key))) {
            return new Hours(proposed, "");
        }
        List<Double> perDay = said == null || ProfileService.pace(said) ? List.of() : Durations.perDayAmounts(said);
        if (perDay.size() > 1) {
            boolean modelsAmong = proposed != null && perDay.stream().anyMatch(n -> Math.abs(n - proposed) < 1e-6);
            return new Hours(modelsAmong ? proposed : null, " (your words give " + String.join(" and ",
                    perDay.stream().map(ConversationService::hoursText).toList()) + ")", true);
        }
        if (perDay.isEmpty()) {
            return new Hours(proposed, "");
        }
        double stated = perDay.getFirst();
        if (proposed == null || Math.abs(stated - proposed) < 1e-6) {
            return new Hours(stated, "");
        }
        return Hours.disputed(stated, proposed);
    }

    /** A status change the task cannot make, said before asking anything. */
    static String transitionProblem(Action action, TaskView task) {
        String name = task.key() + " \"" + task.title() + "\"";
        return switch (action.type()) {
            case REMOVE_TASK -> task.status() == TaskStatus.DROPPED ? name + " is already dropped"
                    : task.status() == TaskStatus.DONE ? name + " is done; only open tasks can be dropped" : null;
            case SET_TASK_STATUS -> action.status() == null || action.status() == task.status() && action.hours() == null
                    ? (action.status() == null ? null : name + " is already " + LedgerRules.readable(task.status()))
                    : !task.status().canMoveTo(action.status()) && action.status() != task.status()
                    ? name + " cannot go from " + LedgerRules.readable(task.status()) + " to "
                    + LedgerRules.readable(action.status()) : null;
            case UPDATE_TASK -> task.status().isOpen() ? null
                    : name + " is " + LedgerRules.readable(task.status()) + "; only open tasks can be moved or re-estimated";
            default -> null;
        };
    }

    private static final Pattern TASK_KEY = Pattern.compile("(?i)\\bT(\\d{1,3})\\b");
    /** A task named by its number the way people write it: "task 2", "task #2", "task number 2", "#2". */
    private static final Pattern NUMBERED_TASK = Pattern.compile("(?i)\\btask\\s*(?:number\\s*|no\\.?\\s*|#\\s*)?"
            + "(\\d{1,3})\\b|(?<![\\w#])#(\\d{1,3})\\b");

    /** The sentence with every task named by its number written as its key: "mark task 2 done" is "mark T2 done". */
    static String withTaskKeys(String said) {
        Matcher m = NUMBERED_TASK.matcher(said);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, "T" + (m.group(1) != null ? m.group(1) : m.group(2)));
        }
        return m.appendTail(out).toString();
    }

    /** Task keys the person typed, such as T3. */
    static Set<String> namedKeys(String said) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = TASK_KEY.matcher(said == null ? "" : said);
        while (m.find()) {
            keys.add("T" + Integer.parseInt(m.group(1)));
        }
        return keys;
    }

    /** Past forms that do not end in "-ed", with the verb they come from: "sent" is "send". */
    static final Map<String, String> IRREGULAR_PAST = Map.ofEntries(Map.entry("sent", "send"),
            Map.entry("bought", "buy"), Map.entry("made", "make"), Map.entry("wrote", "write"), Map.entry("paid", "pay"),
            Map.entry("built", "build"), Map.entry("got", "get"), Map.entry("gave", "give"), Map.entry("found", "find"),
            Map.entry("told", "tell"), Map.entry("sold", "sell"), Map.entry("ran", "run"), Map.entry("met", "meet"),
            Map.entry("brought", "bring"), Map.entry("caught", "catch"), Map.entry("taught", "teach"),
            Map.entry("chose", "choose"), Map.entry("drove", "drive"), Map.entry("spoke", "speak"),
            Map.entry("ate", "eat"), Map.entry("began", "begin"), Map.entry("swept", "sweep"), Map.entry("kept", "keep"),
            Map.entry("hung", "hang"), Map.entry("won", "win"), Map.entry("took", "take"), Map.entry("went", "go"),
            Map.entry("drew", "draw"), Map.entry("threw", "throw"), Map.entry("grew", "grow"), Map.entry("held", "hold"),
            Map.entry("spent", "spend"), Map.entry("rang", "ring"), Map.entry("sang", "sing"), Map.entry("sat", "sit"),
            Map.entry("stood", "stand"), Map.entry("rode", "ride"), Map.entry("led", "lead"), Map.entry("fed", "feed"),
            Map.entry("lent", "lend"), Map.entry("wore", "wear"), Map.entry("did", "do"), Map.entry("read", "read"),
            Map.entry("cut", "cut"), Map.entry("put", "put"), Map.entry("set", "set"), Map.entry("shut", "shut"));

    /**
     * Words of a question or a supposition anywhere in a sentence: "what if", "if I", "should I", "could I", "can I",
     * "might", "would it", "is it ok", "suppose", "I wonder", "thinking of".
     */
    private static final Pattern SUPPOSITION = Pattern.compile("\\b(?:what\\s+if|if\\s+(?:i|we)|should\\s+(?:i|we)"
            + "|could\\s+(?:i|we)|can\\s+(?:i|we)|might|would\\s+it|is\\s+it\\s+(?:ok|okay|alright|all\\s+right|fine"
            + "|possible|a\\s+good\\s+idea|wise)|suppose|supposing|hypothetically|wondering|i\\s+wonder"
            + "|thinking\\s+(?:of|about)|considering)\\b");
    /**
     * A question at the start of a sentence or of a clause: "is reserving the picnic spot done", "did I finish ...",
     * "have the invitations been sent", "had T3 been done", "can the picnic be pushed back", "ok, should I ...". "May"
     * before a number is a month.
     */
    private static final Pattern QUESTION_AT_START = Pattern.compile("(?:^|[,;:]\\s*)(?:(?:ok|okay|so|and|but|hey"
            + "|hmm|well)[,\\s]+)?(?:is|are|am|was|were|did|does|do\\s+(?:i|you|we)|has|have|had|can|could"
            + "|should|would|will|shall|may(?!\\s+\\d)|might|what|which|when|where|who|why|how)\\b");

    /**
     * True when the sentence is a question or a supposition, so it asks for no change yet: it holds a question mark
     * ("T3 of the picnic done? yes"), starts (or has a clause that starts) like a question ("is reserving the picnic
     * spot done", "can the picnic be pushed back a week"), or supposes ("what if I push the picnic back a week", "I
     * might push it", "is it ok to move the picnic to the 11th"). A polite request phrased as a question ("can you
     * move the picnic to Friday?") counts too: one yes is cheap.
     */
    static boolean supposes(String said) {
        if (said == null) {
            return false;
        }
        String t = TargetCheck.straightQuotes(said).strip().toLowerCase(Locale.ROOT);
        return t.indexOf('?') >= 0 || QUESTION_AT_START.matcher(t).find() || SUPPOSITION.matcher(t).find();
    }

    /** True when {@code words} finds something in the sentence outside its negated parts. */
    private static boolean said(Pattern words, String said) {
        return said != null && words.matcher(TargetCheck.withoutNegated(said).toLowerCase(Locale.ROOT)).find();
    }

    /** Words that approve a step waiting for approval: "approve", "allow", "go ahead", "let it run", ... */
    static final Pattern APPROVAL_WORDS = Pattern.compile("\\b(?:approv\\w*|allow\\w*|go\\s+ahead|green\\s*light"
            + "|let\\s+(?:it|them|that|this|the\\s+step)\\s+(?:run|go|proceed|happen|through)|proceed|permit\\w*"
            + "|authori[sz]\\w*|sign\\s+off|accept\\w*)\\b");
    /** Words that ask to pair a device: "pair", "device", "laptop", "phone", "computer", "tablet", ... */
    static final Pattern PAIRING_WORDS = Pattern.compile("\\b(?:pair\\w*|devices?|laptops?|phones?|computers?"
            + "|tablets?|ipads?|desktops?|mobile|(?:another|other|new|second)\\s+machine)\\b");
    /** Words that ask to keep a capability: "keep", "reinstate", "bring back", "restore", "retain", ... */
    private static final Pattern KEEP_WORDS = Pattern.compile("\\b(?:keep\\w*|kept|reinstat\\w*|restor\\w*|retain\\w*"
            + "|un-?retir\\w*|re-?enabl\\w*|re-?activat\\w*|hold\\s+on\\s+to|(?:bring|brought)\\s+(?:\\S+\\s+){0,4}?back)"
            + "\\b");

    /**
     * True when the sentence, outside its negated parts, asks to keep the capability {@code id} (named
     * {@code name}): keep words and the capability's id or the content words of its name, all of them or, for a name
     * of three or more, all but one ("keep shopping.list", "bring the shopping list back", "keep the packing list" for
     * "Barbecue Packing List"; not "keep going", "how is the shopping list doing?" or "don't keep it").
     */
    static boolean keepSaid(String said, String id, String name) {
        if (said == null || id == null) {
            return false;
        }
        String kept = TargetCheck.withoutNegated(said);
        return KEEP_WORDS.matcher(kept.toLowerCase(Locale.ROOT)).find() && namesCapability(kept, id, name);
    }

    /**
     * True when the words name the capability {@code id} (named {@code name}): its id, or the content words of its
     * name, all of them or, for a name of three or more, all but one ("the packing list" names "Barbecue Packing
     * List"; "the unicorn" names nothing).
     */
    static boolean namesCapability(String words, String id, String name) {
        if (words == null || id == null) {
            return false;
        }
        if (TargetCheck.containsPhrase(words, id)) {
            return true;
        }
        Set<String> spoken = TargetCheck.stems(words);
        Set<String> named = name == null ? Set.of() : TargetCheck.stems(name);
        long hits = named.stream().filter(spoken::contains).count();
        return !named.isEmpty() && (hits == named.size() || named.size() >= 3 && hits >= 2 && hits >= named.size() - 1);
    }

    /**
     * With several steps waiting, "approve it" does not say which one: the sentence must name the approval's id,
     * its capability, or its goal.
     */
    private boolean approvalGrounded(String said, String approvalId, Grounding.Snapshot snapshot, Set<String> recent) {
        JsonNode pending = snapshot.params().path("approvals");
        if (pending.size() <= 1) {
            return true;
        }
        String t = said.toLowerCase(Locale.ROOT);
        for (JsonNode a : pending) {
            if (!approvalId.startsWith(a.path("id").asString())) {
                continue;
            }
            if (t.contains(a.path("id").asString())) {
                return true;
            }
            for (String word : a.path("capability").asString("").split("[.\\-_]")) {
                if (word.length() >= 5 && t.contains(word.substring(0, Math.min(word.length(), 7)))) {
                    return true;
                }
            }
            String goalId = snapshot.goal(a.path("goal").asString()).orElse(null);
            if (goalId == null) {
                return false;
            }
            TargetCheck.Result check = targets.check(said, null, goalId, snapshot.documents(), recent);
            return check.grounded() && !"refers to the goal just discussed".equals(check.reason());
        }
        return false;
    }

    /**
     * The rule is what the person said now. The model's paraphrase is used only when it shares most of its words
     * with the sentence; a text copied from an earlier turn or an existing rule is ignored.
     */
    public static String ruleWording(String proposed, String said) {
        String sentence = said == null ? "" : said.strip();
        if (proposed == null || proposed.isBlank()) {
            return sentence;
        }
        Set<String> spoken = new HashSet<>(List.of(TargetCheck.normalise(sentence).split(" ")));
        String[] words = TargetCheck.normalise(proposed).split(" ");
        long shared = Arrays.stream(words).filter(spoken::contains).count();
        return shared * 2 >= words.length ? proposed.strip() : sentence;
    }

    /**
     * A turn's reply: its text (null when the kernel's own lines speak), the outcomes, the question and the data shown.
     */
    static Conversation.Reply reply(String text, List<Executed> executed, Conversation.PendingConfirmation confirmation,
                                    ActionExecutor.Shown shown) {
        return new Conversation.Reply(text, executed, confirmation, shown.goals, shown.details, shown.capabilities,
                shown.decisions, shown.approvals, shown.status, shown.hashes, shown.agenda, shown.impacts,
                shown.ecosystem, shown.grown, shown.rules, shown.profile, shown.messages, shown.ruleImpact);
    }

    /**
     * The person's own changes are governed by their rules; the kill switch, the rules themselves and answers to
     * messages (whose suggestions are checked one by one) are not.
     */
    private static boolean governed(ActionType type) {
        return !type.readOnly() && type != ActionType.PAUSE && type != ActionType.RESUME
                && type != ActionType.ADD_RULE && type != ActionType.REMOVE_RULE
                && type != ActionType.ANSWER_MESSAGE;
    }

    private static String capitalise(String s) {
        return ActionExecutor.capitalise(s);
    }

    /**
     * A rule's dry-run impact in one or two sentences, for the question that asks to make it a rule; empty without an
     * impact.
     */
    public static String impactSentence(Constitution.Impact impact) {
        if (impact == null) {
            return "";
        }
        if (impact.agentStepsAffected() == 0 && impact.personActionsAffected() == 0 && impact.dueDayBreaches() == 0) {
            return "Over the last " + impact.days() + " days it would not have changed anything.";
        }
        List<String> parts = new ArrayList<>();
        if (impact.agentStepsAffected() > 0) {
            parts.add(impact.agentStepsAffected() + " capability step(s)");
        }
        if (impact.personActionsAffected() > 0) {
            parts.add(impact.personActionsAffected() + " of your own change(s)");
        }
        String sentence = parts.isEmpty() ? "" : "Over the last " + impact.days() + " days it would have affected "
                + String.join(" and ", parts) + ".";
        if (impact.dueDayBreaches() > 0) {
            sentence += (sentence.isEmpty() ? "" : " ") + impact.dueDayBreaches() + " upcoming day(s) already break it.";
        }
        return sentence;
    }

    /**
     * A correction of the person's profile must come from what they said in this sentence, not from the model's
     * imagination: daily hours need an amount of time with exactly the correction's hours ("2 hours", "90 minutes",
     * "an hour and a half") that the words hold true ("not 3 hours a day" states no 3; "from 4 to 2 hours" states 2;
     * see {@link Durations#affirmedHours}) and a per-day phrase ("a day", "daily", "on weekdays"), and never pace
     * words; working days need day names; a pace needs a factor the kernel can read from the words ("50% longer",
     * "twice as long").
     */
    public static boolean profileGrounded(String utterance, Action action) {
        String u = utterance == null ? "" : utterance.toLowerCase(Locale.ROOT);
        String key = action.text() == null ? "" : action.text().toLowerCase(Locale.ROOT);
        if (key.contains("hour") || key.contains("capacity")) {
            if (action.hours() == null || !ProfileService.perDay(u) || ProfileService.pace(u)) {
                return false;
            }
            double h = action.hours();
            return Durations.affirmedHours(u).stream().anyMatch(stated -> Math.abs(stated - h) < 1e-6);
        }
        if (key.contains("day")) {
            return !ProfileService.days(u).isEmpty();
        }
        if (key.contains("bias") || key.contains("estimate") || key.contains("pace")) {
            return ProfileService.factor(u).isPresent();
        }
        return false;
    }

    // ---- inbox -----------------------------------------------------------------------------------------------

    /** What answering a message did, plus a question when its basis changed and the person should look again. */
    record Answered(List<Executed> executed, String question, Action retry) {
    }

    /**
     * When the person does directly exactly what an unread message suggested ("yes, move the deadline like you
     * said"), the message is answered: it was accepted, just not by id. Only changes that succeeded count, each as it
     * was applied, and every suggestion of the message must be one of them (see {@link #fulfils}).
     */
    private void settleFulfilled(List<Action> done, String principal) {
        if (done.isEmpty()) {
            return;
        }
        for (Inbox.Message m : inbox.unread()) {
            boolean fulfilled = !m.suggestions().isEmpty() && m.suggestions().stream()
                    .allMatch(s -> done.stream().anyMatch(d -> fulfils(d, s)));
            if (fulfilled) {
                inbox.settle(m, Inbox.Status.ACCEPTED, principal);
            }
        }
    }

    /**
     * True when the change {@code done} is the change {@code suggestion} asks for: the same kind of change on the same
     * goal and task, with every value the suggestion sets set the same way: its date, its status, its hours, and for a
     * new task or goal its words ("mark T3 done" does not carry out a suggestion to mark T3 blocked, "buy napkins" not
     * one to add "book the band", and a move of T3 not a suggestion to re-estimate it).
     */
    static boolean fulfils(Action done, Action suggestion) {
        boolean titled = suggestion.type() == ActionType.ADD_TASK || suggestion.type() == ActionType.CREATE_GOAL;
        return done.type() == suggestion.type() && Objects.equals(done.goal(), suggestion.goal())
                && Objects.equals(upper(done.task()), upper(suggestion.task()))
                && (suggestion.dueDate() == null || suggestion.dueDate().equals(done.dueDate()))
                && (suggestion.status() == null || suggestion.status() == done.status())
                && (suggestion.hours() == null || done.hours() != null
                && Math.abs(suggestion.hours() - done.hours()) < 1e-6)
                && (!titled || suggestion.text() == null || sameWords(suggestion.text(), done.text()));
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    /** The model's label for a dismissal, carried by the confirmation question that asks before dismissing. */
    static final String DISMISS = "dismiss";

    private static final Pattern YES = Pattern.compile("\\b(yes|yeah|yep|ok|okay|sure|accept|agreed|go ahead|do it|"
            + "please do|sounds good|fine|like you (?:said|suggested)|as (?:you )?suggested)\\b");
    /** Words that refuse on their own. */
    private static final Pattern NO = Pattern.compile(
            "\\b(no|nope|nah|never|leave|ignore|dismiss|reject|decline|skip|forget|refuse)\\b");
    /** Negated phrases that agree: "no problem", "not a problem", "I don't mind"; "of course not" is a no. */
    private static final Pattern YES_IDIOM = Pattern.compile("\\b(?:no problem|not a problem|no worries|don'?t mind"
            + "|do not mind|why not|of course(?!\\s+not\\b))\\b");
    /** Words that put the decision off or doubt it: neither a yes nor a no. */
    private static final Pattern UNDECIDED = Pattern.compile("\\b(?:for now|later|not sure|unsure|don'?t know|no idea"
            + "|maybe|perhaps|let me think|think about it|decide)\\b");
    /** A condition attached to a yes ("ok but don't touch T3"): the person is asked. */
    private static final Pattern CAVEAT = Pattern.compile("\\b(?:but|except|unless|only if|as long as)\\b");
    private static final Pattern NEGATION = Pattern.compile(TargetCheck.NEGATION);
    /** A negation up to two words before a yes-word ("I can't accept", "please don't do it"). */
    private static final Pattern NEGATED_BEFORE = Pattern.compile("(?:" + TargetCheck.NEGATION + ")\\s+(?:\\S+\\s+){0,2}$");
    private static final Pattern LABEL_NO = Pattern.compile("\\b(?:no|not|never|dismiss|reject|decline|ignore|skip)\\b|n't\\b");

    /**
     * What the person's own words say about a suggestion: yes, no, or nothing clear (null). A yes-word that is
     * negated ("I can't accept that"), a refusing word that is negated ("don't dismiss it", "don't reject it, I want
     * that agent"), a yes with a condition or with a refusing word, and words that put the decision off ("leave it
     * for now", "not sure") are not clear; a negation on its own ("not now", "I don't think so", "of course not")
     * is a no; agreeing phrases with a negation ("no problem", "not a problem", "I don't mind") are a yes.
     */
    public static Boolean intent(String said) {
        String t = TargetCheck.straightQuotes(said == null ? "" : said).toLowerCase(Locale.ROOT);
        t = YES_IDIOM.matcher(t).replaceAll(" yes ");
        if (UNDECIDED.matcher(t).find()) {
            return null;
        }
        boolean yes = false;
        boolean negatedYes = false;
        Matcher m = YES.matcher(t);
        while (m.find()) {
            if (NEGATED_BEFORE.matcher(t.substring(clauseStart(t, m.start()), m.start())).find()) {
                negatedYes = true;
            } else {
                yes = true;
            }
        }
        boolean refuses = false;
        boolean negatedNo = false;
        Matcher n = NO.matcher(t);
        while (n.find()) {
            if (NEGATED_BEFORE.matcher(t.substring(clauseStart(t, n.start()), n.start())).find()) {
                negatedNo = true;
            } else {
                refuses = true;
            }
        }
        if (negatedYes || negatedNo || yes && (refuses || CAVEAT.matcher(t).find())) {
            return null;
        }
        if (yes) {
            return true;
        }
        return refuses || NEGATION.matcher(t).find() ? false : null;
    }

    /** A negation counts only within its word's clause, which starts after the last comma, semicolon or stop. */
    private static int clauseStart(String t, int index) {
        return Math.max(0, Math.max(t.lastIndexOf(',', index), Math.max(t.lastIndexOf(';', index),
                t.lastIndexOf('.', index))));
    }

    /** The model's label ("accept" or "dismiss") read as an answer; anything negative is a no. */
    public static boolean accepts(String text) {
        return text == null || !LABEL_NO.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    private Answered answer(Inbox.Message message, boolean accept, boolean force, String principal,
                            ActionExecutor.Shown shown) {
        if (message.status() != Inbox.Status.UNREAD) {
            return answered(message, false, "that message was already "
                    + message.status().name().toLowerCase(Locale.ROOT));
        }
        if (!accept) {
            if (!inbox.settle(message, Inbox.Status.DISMISSED, principal)) {
                return answered(message, false, "that message was already answered");
            }
            return answered(message, true, "dismissed the message" + (message.goalText() == null ? ""
                    : " about \"" + message.goalText() + "\"") + refuseAdmission(message, principal));
        }
        if (message.goalId() != null && !inbox.goalIsOpen(message.goalId())) {
            inbox.settle(message, Inbox.Status.EXPIRED, principal);
            return answered(message, false, "that goal is closed, so the suggestion no longer applies");
        }
        if (!force && !message.suggestions().isEmpty() && inbox.outdated(message)) {
            String what = message.goalText() == null ? "Your tasks" : "The plan of \"" + message.goalText() + "\"";
            return new Answered(List.of(), what + " changed since I suggested to " + message.suggestionText()
                    + ". Do it anyway?", new Action(ActionType.ANSWER_MESSAGE, message.goalId(), null, "accept anyway",
                    null, null, null, message.id(), null, null));
        }
        if (!inbox.settle(message, Inbox.Status.ACCEPTED, principal)) {
            return answered(message, false, "that message was already answered");
        }
        List<Executed> results = new ArrayList<>();
        // A new deadline moves the goal's open tasks with it (never before today), so a task move suggested for the
        // same goal is applied after the deadline move, and only when it puts the task later than the deadline move
        // did; a suggested date the shift already reached or passed would pull the task back out of order.
        Set<String> rescheduled = new HashSet<>();
        message.suggestions().stream().filter(s -> s.type() == ActionType.RESCHEDULE_GOAL && s.goal() != null)
                .forEach(s -> rescheduled.add(s.goal()));
        List<Action> afterDeadline = new ArrayList<>();
        Set<String> deadlineMoved = new HashSet<>();
        for (Action suggestion : message.suggestions()) {
            if (suggestion.type() == ActionType.UPDATE_TASK && rescheduled.contains(suggestion.goal())) {
                afterDeadline.add(suggestion);
                continue;
            }
            Executed result = applySuggestion(suggestion, principal, shown);
            results.add(result);
            if (result.ok() && suggestion.type() == ActionType.RESCHEDULE_GOAL) {
                deadlineMoved.add(suggestion.goal());
            }
        }
        Map<String, List<String>> notMoved = new LinkedHashMap<>();
        for (Action suggestion : afterDeadline) {
            LocalDate shifted = ledger.load(suggestion.goal()).flatMap(g -> g.task(suggestion.task()))
                    .map(TaskView::dueDate).orElse(null);
            if (!deadlineMoved.contains(suggestion.goal())) {
                results.add(new Executed(ActionType.UPDATE_TASK, false, suggestion.task() + " was not moved: the new "
                        + "deadline it goes with was not set", suggestion.goal(), null));
            } else if (suggestion.dueDate() != null && (shifted == null || suggestion.dueDate().isAfter(shifted))) {
                results.add(applySuggestion(suggestion, principal, shown));
            } else {
                notMoved.computeIfAbsent(suggestion.goal(), g -> new ArrayList<>()).add(suggestion.task());
            }
        }
        notMoved.forEach((goal, tasks) -> results.add(new Executed(ActionType.UPDATE_TASK, true,
                "the task dates moved with the new deadline, so " + String.join(", ", tasks) + " "
                        + (tasks.size() == 1 ? "was" : "were") + " not moved separately", goal, null)));
        if (results.isEmpty()) {
            results.add(new Executed(ActionType.ANSWER_MESSAGE, true, "noted: " + message.text(), message.goalId(), null));
        } else if (results.stream().noneMatch(Executed::ok)) {
            inbox.reopen(message, principal); // nothing happened; the person can try again after fixing what stood in the way
        }
        return new Answered(results, null, null);
    }

    /**
     * Carries out one suggestion of an accepted message under the person's rules: a forbidding rule refuses it;
     * accepting the message is the person's explicit yes, so an ask-first rule is satisfied.
     */
    private Executed applySuggestion(Action suggestion, String principal, ActionExecutor.Shown shown) {
        ConstitutionService.Ruling ruling = constitution.checkPerson(suggestion.type(), suggestion.goal());
        if (ruling.verdict() == ConstitutionService.Verdict.FORBID) {
            return new Executed(suggestion.type(), false, forbidden(ruling, null), suggestion.goal(), null);
        }
        return executor.execute(suggestion, suggestion.goal(), null, principal, shown);
    }

    /**
     * A "no" to the kernel's admission message refuses the waiting agent (it can still be admitted later); the agent's
     * key id is the suggestion's {@code approval}. Returns a note for the reply, empty for any other message.
     */
    private String refuseAdmission(Inbox.Message message, String principal) {
        if (message.kind() != Inbox.Kind.ADMISSION || !"kernel".equals(message.provider())) {
            return "";
        }
        boolean refused = false;
        for (Action s : message.suggestions()) {
            if (s.type() == ActionType.ADMIT_PROVIDER && s.approval() != null) {
                refused |= security.reject(s.approval(), principal).isEmpty();
            }
        }
        return refused ? "; the agent was refused (you can still admit it later)" : "";
    }

    /**
     * The question before a message is accepted: its suggestion in the message's words and, when it makes several
     * changes, every one of them on a line of its own (each task and date), so a yes never covers a change the
     * person was not shown. A message without suggestions is marked as read.
     */
    String acceptQuestion(Inbox.Message m) {
        if (m.suggestions().isEmpty()) {
            return "Mark the message \"" + m.text() + "\" as read?";
        }
        String question = "Accept the suggestion to " + m.suggestionText() + "?";
        if (m.suggestions().size() < 2) {
            return question;
        }
        StringBuilder sb = new StringBuilder(question).append(" It would:");
        for (Action s : m.suggestions()) {
            sb.append("\n  - ").append(executor.describe(s, s.goal() != null ? s.goal() : m.goalId(), null));
        }
        return sb.toString();
    }

    private static Answered answered(Inbox.Message message, boolean ok, String text) {
        return new Answered(List.of(new Executed(ActionType.ANSWER_MESSAGE, ok, text, message.goalId(), null)), null, null);
    }

    /**
     * Answers a message directly (the CLI shows unread messages and asks yes or no for each). A yes must carry the
     * receipt of the message as shown: always for a destructive suggestion, and for every message when the caller
     * is a verified device.
     */
    public Conversation.Reply answer(Inbox.Answer answer, SecurityService.Caller caller) {
        Optional<Inbox.Message> message = inbox.find(answer.messageId());
        if (message.isEmpty()) {
            return Conversation.Reply.text("I do not know that message.");
        }
        if (!receiptAccepted(answer, message.get(), caller.keyId() != null)) {
            // The yes must be for exactly the message and suggestions the device showed.
            return Conversation.Reply.text("Nothing was done: your answer does not match the message as it stands now.");
        }
        ActionExecutor.Shown shown = new ActionExecutor.Shown();
        String principal = caller.principal() != null ? caller.principal() : principal(answer.principal());
        Answered answered = answer(message.get(), answer.accept(), answer.force(), principal, shown);
        Conversation.PendingConfirmation confirmation = null;
        if (answered.question() != null) {
            confirmation = new Conversation.PendingConfirmation(UUID.randomUUID().toString(), answered.question(),
                    List.of(answered.retry()), null, null);
            session(sessionKey(caller, answer.sessionId())).await(confirmation, caller.keyId());
        }
        // A dismissal says so in its one line; "Done." only when everything accepted was carried out.
        String text = confirmation == null && answer.accept() && allDone(answered.executed()) ? "Done." : null;
        return reply(text, answered.executed(), confirmation, shown);
    }

    /**
     * Whether a yes to a question may stand: it carries the question's receipt, or needs none (a no, or a question
     * that neither changes something destructive nor answers an inbox message, asked without a verified device).
     */
    static boolean receiptAccepted(Conversation.Confirm confirm, Conversation.PendingConfirmation pending,
                                   boolean verifiedDevice) {
        if (!confirm.accepted() || Conversation.receipt(pending).equals(confirm.receipt())) {
            return true;
        }
        return !verifiedDevice && pending.actions().stream()
                .noneMatch(a -> a.type().needsConfirmation() || a.type() == ActionType.ANSWER_MESSAGE);
    }

    /** Whether a yes may stand: it carries the message's receipt, or needs none (a no, or a harmless suggestion). */
    static boolean receiptAccepted(Inbox.Answer answer, Inbox.Message message, boolean verifiedDevice) {
        if (!answer.accept() || Inbox.receipt(message).equals(answer.receipt())) {
            return true;
        }
        return !verifiedDevice && !Inbox.destructive(message.suggestions());
    }

    private String growQuestion(Ecosystem.CapabilitySpec spec, String goalId) {
        String goal = goalId == null ? null : genesis.goalText(goalId);
        return "I can grow a new capability \"" + spec.name() + "\" (" + spec.id() + "): " + spec.description()
                + " It would add " + spec.id() + " to " + (spec.scope() == Ecosystem.Scope.GOAL ? "the goal" : "each open task")
                + ". Grow it on probation" + (goal == null ? "" : " and use it on \"" + goal + "\"") + "?";
    }

    private Optional<TaskView> similarTask(String goalId, String title) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        Optional<float[]> wanted = embeddings.embed(title);
        TaskView best = null;
        double bestScore = 0;
        for (TaskView t : ledger.load(goalId).map(GoalState::tasks).orElse(List.of())) {
            if (!t.status().isOpen()) {
                continue;
            }
            double score = TargetCheck.normalise(t.title()).equals(TargetCheck.normalise(title)) ? 1.0
                    : wanted.flatMap(w -> embeddings.embed(t.title()).map(v -> Embeddings.cosine(w, v)))
                    .orElseGet(() -> Embeddings.lexicalSimilarity(title, t.title()) >= 0.5 ? 1.0 : 0.0);
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return bestScore >= DUPLICATE_COSINE ? Optional.of(best) : Optional.empty();
    }

    /**
     * Small models sometimes put a new goal's description in the goal field: alone, it is read as the description;
     * next to a text of its own, the model split the person's sentence, and the goal takes the sentence's own words
     * (see {@link #goalWords}, read on {@code today}). Either way the goal field may have named a goal the person
     * meant, so the new goal is asked about, never created unasked.
     */
    static Action tolerant(Action a, Grounding.Snapshot snapshot, String said, LocalDate today) {
        if (a.type() != ActionType.CREATE_GOAL || a.goal() == null || a.goal().isBlank()
                || snapshot.goal(a.goal()).isPresent()) {
            return a;
        }
        String text = a.text() == null || a.text().isBlank() ? a.goal() : goalWords(said, today);
        return new Action(a.type(), null, a.task(), text, a.dueDate(), a.status(), a.hours(), a.approval(),
                a.reason(), a.evidence());
    }

    /**
     * A yes or no. With security on, the yes must be signed by the device the question was asked on and carry the
     * receipt of exactly that question: what you see is what you sign. A yes to a destructive change or to an inbox
     * suggestion carries the receipt in any case.
     */
    public Conversation.Reply confirm(Conversation.Confirm confirm, SecurityService.Caller caller) {
        // An answer never starts a conversation: a session that does not exist has nothing waiting.
        Session session = existingSession(sessionKey(caller, confirm.sessionId()));
        Conversation.PendingConfirmation pending = session == null ? null
                : session.take(confirm.confirmationId(), caller.keyId());
        if (pending == null) {
            return Conversation.Reply.text("There is nothing waiting for confirmation.");
        }
        if (!receiptAccepted(confirm, pending, caller.keyId() != null)) {
            session.remember("yes", "Refused: the answer did not match the question.");
            return Conversation.Reply.text("Nothing was done: your answer does not match the question that was asked.");
        }
        if (!caller.owner()) {
            for (Action a : pending.actions()) {
                if (!caller.may(a.type(), a.goal())) {
                    return Conversation.Reply.text("Nothing was done: that is not part of what you were invited to do.");
                }
            }
        }
        if (!confirm.accepted()) {
            session.remember("no", "Nothing was changed.");
            return Conversation.Reply.text("OK, nothing was changed.");
        }
        ActionExecutor.Shown shown = new ActionExecutor.Shown();
        List<Executed> executed = new ArrayList<>();
        List<Action> done = new ArrayList<>();
        List<Session.Change> made = new ArrayList<>();
        String principal = caller.principal() != null ? caller.principal() : principal(confirm.principal());
        Conversation.PendingConfirmation followUp = applyConfirmed(pending, principal, shown, session, executed, done,
                made, Map.of());
        settleFulfilled(done, principal);
        session.changed(made);
        if (followUp != null) {
            session.await(followUp, caller.keyId());
        }
        String text = followUp == null && allDone(executed) ? "Done." : null;
        session.remember("yes", text != null ? text : spoken(executed, followUp == null ? List.of()
                : List.of(followUp.question())));
        return reply(text, executed, followUp, shown);
    }

    /** What a typed call may do: goals and tasks, and reads of them; the rest needs a sentence. */
    static final Set<ActionType> TYPED_ACTIONS = EnumSet.of(ActionType.CREATE_GOAL, ActionType.ADD_TASK,
            ActionType.SET_TASK_STATUS, ActionType.UPDATE_TASK, ActionType.REMOVE_TASK, ActionType.RESCHEDULE_GOAL,
            ActionType.CANCEL_GOAL, ActionType.REOPEN_GOAL, ActionType.REFINE_GOAL, ActionType.LIST_GOALS,
            ActionType.SHOW_GOAL, ActionType.SHOW_AGENDA);

    /**
     * Typed actions from a caller that knows what it wants (see {@link Conversation.Command}): no language model and
     * no question. Each action's goal is its id, short id or exact text; one the caller may not touch, a security
     * change, an action a sentence must carry (growing a capability, rules, messages) and one the owner's rule holds
     * for the owner's yes are rejected with the reason; every other action is done as a yes would do it, and the
     * conversation remembers the changes ("undo that").
     */
    public Conversation.Reply execute(Conversation.Command command, SecurityService.Caller caller) {
        Session session = session(sessionKey(caller, command.sessionId()));
        session.newTurn();
        String principal = caller.principal() != null ? caller.principal() : principal(command.principal());
        ActionExecutor.Shown shown = caller.owner() ? new ActionExecutor.Shown()
                : new ActionExecutor.Shown(caller::seesGoal);
        List<Executed> executed = new ArrayList<>();
        List<Action> apply = new ArrayList<>();
        for (Action a : command.actions()) {
            if (a == null || a.type() == null) {
                executed.add(new Executed(ActionType.SHOW_AGENDA, false, "an action needs a type", null, null));
                continue;
            }
            ActionType type = a.type();
            String goalId = a.goal() == null || a.goal().isBlank() ? null : typedGoal(a.goal());
            if (!TYPED_ACTIONS.contains(type)) {
                executed.add(new Executed(type, false, Conversation.SECURITY_ACTIONS.contains(type)
                        ? "this needs the owner's yes on a device: send it as a sentence from a paired device"
                        : "send this as a sentence (intent.submit with mode DECIDE): it is not a typed action", null,
                        null));
                continue;
            }
            if (type.targetsGoal() && type != ActionType.LIST_GOALS && type != ActionType.SHOW_AGENDA
                    && goalId == null) {
                executed.add(new Executed(type, false, "find a goal matching \"" + (a.goal() == null ? "" : a.goal())
                        + "\": give its id, short id or exact text", null, null));
                continue;
            }
            if (!caller.may(type, goalId)) {
                executed.add(new Executed(type, false, "not part of what you were invited to do", goalId, null));
                continue;
            }
            if (type.readOnly()) {
                executed.add(executor.execute(a, goalId, null, principal, shown));
                continue;
            }
            ConstitutionService.Ruling ruling = constitution.checkPerson(type, goalId,
                    type == ActionType.CREATE_GOAL ? a.text() : null);
            if (ruling.verdict() == ConstitutionService.Verdict.ASK_FIRST) {
                executed.add(new Executed(type, false, "ask the owner first: " + ruling.explanation()
                        + " says to ask before " + executor.describe(a, goalId, null), goalId, null));
                continue;
            }
            apply.add(withIds(a, goalId, a.approval()));
        }
        List<Action> done = new ArrayList<>();
        List<Session.Change> made = new ArrayList<>();
        applyConfirmed(new Conversation.PendingConfirmation("typed", "", apply, null, null), principal, shown,
                session, executed, done, made, Map.of());
        settleFulfilled(done, principal);
        session.changed(made);
        Set<String> touched = new LinkedHashSet<>();
        executed.stream().filter(e -> e.ok() && e.goalId() != null).forEach(e -> touched.add(e.goalId()));
        if (!touched.isEmpty()) {
            session.focusOn(touched);
        }
        session.remember("(typed) " + command.actions().size() + " action(s)", spoken(executed, List.of()));
        if (!caller.owner()) {
            visibleOnly(shown, caller);
        }
        return reply(null, executed, null, shown);
    }

    /** The goal a typed action names: its id, the one goal whose id starts with a short id, or the one exact text. */
    private String typedGoal(String ref) {
        String r = ref.strip().replaceAll("^\\[|\\]$", "");
        List<GoalState> goals = new ArrayList<>(ledger.openGoals());
        goals.addAll(ledger.recentlyCancelled(50));
        goals.addAll(ledger.recentlyCompleted(50));
        List<String> byId = goals.stream().map(GoalState::goalId).filter(id -> id.equalsIgnoreCase(r)
                || r.length() >= 8 && id.startsWith(r.toLowerCase(Locale.ROOT))).distinct().toList();
        if (byId.size() == 1) {
            return byId.getFirst();
        }
        List<String> byText = goals.stream().filter(g -> g.text().equalsIgnoreCase(r)).map(GoalState::goalId)
                .distinct().toList();
        return byText.size() == 1 ? byText.getFirst() : null;
    }

    /**
     * Carries out the actions of a question answered yes, or of a turn that acts instead of asking (see
     * {@link Conversation.Mode}): a capability is grown from the designed spec, a drafted rule is ratified, a message
     * is answered, and every other change is done, a task that came with a new goal going to the goal created here.
     * A rule ratified in between still binds. Outcomes go to {@code executed} ({@code notes} adds how the words were
     * read to a change that was done), changes to {@code done} and {@code made}; returns the question a message
     * answer needs next, or null.
     */
    private Conversation.PendingConfirmation applyConfirmed(Conversation.PendingConfirmation pending, String principal,
                                                            ActionExecutor.Shown shown, Session session,
                                                            List<Executed> executed, List<Action> done,
                                                            List<Session.Change> made, Map<Action, String> notes) {
        Conversation.PendingConfirmation followUp = null;
        // A task asked about together with a new goal has no goal of its own: it goes to the goal created here.
        String created = null;
        for (Action action : pending.actions()) {
            // Goal and approval ids were resolved when the question was asked.
            switch (action.type()) {
                case REQUEST_CAPABILITY -> {
                    ConstitutionService.Ruling ruling = constitution.checkPerson(action.type(), action.goal());
                    if (ruling.verdict() == ConstitutionService.Verdict.FORBID) {
                        executed.add(new Executed(action.type(), false, forbidden(ruling, null), action.goal(), null));
                    } else if (pending.spec() != null) {
                        executed.add(executor.grow(pending.spec(), action.goal(), principal));
                    }
                }
                case ADD_RULE -> {
                    if (pending.rule() != null) {
                        Constitution.Rule rule = constitution.ratify(pending.rule(), principal);
                        shown.rules.add(rule);
                        executed.add(new Executed(action.type(), true, "rule " + rule.id() + " is in force: "
                                + Constitution.describe(rule), null, null));
                    }
                }
                case REMOVE_RULE -> {
                    Optional<Constitution.Rule> rule = constitution.find(action.text());
                    if (rule.isPresent()) {
                        constitution.repeal(rule.get(), principal);
                        executed.add(new Executed(action.type(), true, "rule " + rule.get().id() + " repealed", null, null));
                    } else {
                        executed.add(new Executed(action.type(), false, "that rule is no longer active", null, null));
                    }
                }
                case ANSWER_MESSAGE -> {
                    Optional<Inbox.Message> m = inbox.find(action.approval());
                    if (m.isEmpty()) {
                        executed.add(new Executed(action.type(), false, "that message is gone", null, null));
                        break;
                    }
                    Answered answered = answer(m.get(), !DISMISS.equals(action.text()),
                            "accept anyway".equals(action.text()), principal, shown);
                    executed.addAll(answered.executed());
                    if (answered.question() != null) {
                        followUp = new Conversation.PendingConfirmation(UUID.randomUUID().toString(),
                                answered.question(), List.of(answered.retry()), null, null);
                    }
                }
                default -> {
                    // A rule ratified between question and answer still binds.
                    ConstitutionService.Ruling ruling = governed(action.type())
                            ? constitution.checkPerson(action.type(), action.goal(),
                            action.type() == ActionType.CREATE_GOAL ? action.text() : null)
                            : ConstitutionService.Ruling.ALLOW;
                    if (ruling.verdict() == ConstitutionService.Verdict.FORBID) {
                        executed.add(new Executed(action.type(), false, forbidden(ruling, null), action.goal(), null));
                    } else {
                        String goal = action.type() == ActionType.ADD_TASK && action.goal() == null ? created
                                : action.goal();
                        GoalState before = goal == null ? null : ledger.load(goal).orElse(null);
                        Executed result = executor.execute(action, goal, action.approval(), principal, shown);
                        String note = notes.getOrDefault(action, "");
                        if (result.ok() && !note.isBlank()) {
                            result = new Executed(result.type(), true, result.message() + note, result.goalId(),
                                    result.runId());
                        }
                        executed.add(result);
                        if (result.ok()) {
                            done.add(action);
                            Session.Change change = change(action, goal, before, result);
                            made.add(change);
                            if (action.type() == ActionType.CREATE_GOAL) {
                                created = result.goalId();
                                // "No, I meant Saturday" after a new goal moves its deadline.
                                session.dated(action.dueDate() == null ? null : new Action(
                                        ActionType.RESCHEDULE_GOAL, created, null, null, action.dueDate(), null, null,
                                        null, action.reason(), null));
                            } else if (datedChange(change.made())) {
                                session.dated(change.made());
                            }
                        }
                    }
                }
            }
        }
        return followUp;
    }

    /** The session under {@code id}, created when there is none; sessions idle for two hours are forgotten first. */
    private Session session(String id) {
        forgetIdle();
        return sessions.computeIfAbsent(id == null || id.isBlank() ? "default" : id, k -> new Session(clock));
    }

    /** The session under {@code id}, or null when there is none; never creates one. */
    private Session existingSession(String id) {
        forgetIdle();
        return sessions.get(id == null || id.isBlank() ? "default" : id);
    }

    private void forgetIdle() {
        Instant cutoff = clock.instant().minusSeconds(2 * 3600);
        sessions.entrySet().removeIf(e -> e.getValue().idleSince(cutoff));
    }

    /** How many conversations are kept in memory. */
    int sessionCount() {
        return sessions.size();
    }

    private static String principal(String principal) {
        return principal == null || principal.isBlank() ? "human:anonymous" : principal;
    }
}
