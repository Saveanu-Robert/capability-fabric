package dev.capabilityfabric.kernel.inbox;

import dev.capabilityfabric.kernel.KernelProperties;
import dev.capabilityfabric.kernel.ledger.GoalState;
import dev.capabilityfabric.kernel.ledger.LedgerRules;
import dev.capabilityfabric.kernel.ledger.LedgerService;
import dev.capabilityfabric.kernel.person.ProfileService;
import dev.capabilityfabric.protocol.Conversation;
import dev.capabilityfabric.protocol.Facts;
import dev.capabilityfabric.protocol.GoalStatus;
import dev.capabilityfabric.protocol.Hashing;
import dev.capabilityfabric.protocol.Inbox;
import dev.capabilityfabric.protocol.Json;
import dev.capabilityfabric.protocol.TaskStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Messages the system starts. A provider (or the kernel) posts an observation with typed suggestions; the kernel
 * stores it once per observation (dedupe key) together with a fingerprint of the goal facts it rests on. Every change
 * of a message's status, including expiry, is recorded as an {@code inbox.answered} fact.
 */
@Component
public class InboxService {

    /** Suggestions may only use these actions: changes to one goal the person can take back. */
    public static final Set<Conversation.ActionType> SUGGESTIBLE = Set.of(Conversation.ActionType.RESCHEDULE_GOAL,
            Conversation.ActionType.UPDATE_TASK, Conversation.ActionType.SET_TASK_STATUS,
            Conversation.ActionType.REFINE_GOAL, Conversation.ActionType.ADD_TASK, Conversation.ActionType.REMOVE_TASK,
            Conversation.ActionType.CANCEL_GOAL);

    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{16}");
    /** What a message id or a prefix of one may be: at least 4 hex digits and hyphens. */
    private static final Pattern ID_PREFIX = Pattern.compile("[0-9a-fA-F-]{4,36}");

    /**
     * At most this many unread messages from one provider: a further message is not stored unless it replaces one (see
     * {@link #superseded}). The kernel's own messages (admissions, rule limits, security notices) are not counted.
     */
    public static final int MAX_UNREAD_PER_PROVIDER = 50;
    /** At most this many unread messages of one kind about one goal from one provider; older ones are retired. */
    public static final int MAX_UNREAD_PER_GOAL_AND_KIND = 5;

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final KernelProperties properties;
    private final ProfileService profile;

    public InboxService(JdbcClient jdbc, LedgerService ledger, KernelProperties properties, ProfileService profile) {
        this.profile = profile;
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.properties = properties;
    }

    /** A drop of a task is the REMOVE_TASK action, whichever way it was written, so its rules and checks apply. */
    public static Conversation.Action normalise(Conversation.Action a) {
        if (a.type() == Conversation.ActionType.SET_TASK_STATUS && a.status() == TaskStatus.DROPPED) {
            return new Conversation.Action(Conversation.ActionType.REMOVE_TASK, a.goal(), a.task(), a.text(), null, null,
                    null, a.approval(), a.reason(), a.evidence());
        }
        return a;
    }

    /** A post as it is stored: drops written as drops (see {@link #normalise}), texts without control characters. */
    static Inbox.Post cleaned(Inbox.Post given) {
        return new Inbox.Post(given.kind(), given.goalId(), LedgerRules.printable(given.text()),
                given.suggestions().stream().map(InboxService::normalise).map(InboxService::printable).toList(),
                given.dedupeKey(), given.provider(), given.instanceId(), given.decisions(), given.basis());
    }

    /** A suggestion whose free texts (task title, reason) carry no control characters. */
    private static Conversation.Action printable(Conversation.Action a) {
        return new Conversation.Action(a.type(), a.goal(), a.task(), LedgerRules.printable(a.text()), a.dueDate(),
                a.status(), a.hours(), a.approval(), LedgerRules.printable(a.reason()),
                LedgerRules.printable(a.evidence()));
    }

    /**
     * Stores a message once per observation (dedupe key, 7 days) and retires older unread messages it replaces: the
     * same kind about the same goal, or the same limit rule. A provider with {@link #MAX_UNREAD_PER_PROVIDER} unread
     * messages stores only replacements, and of one kind about one goal at most {@link #MAX_UNREAD_PER_GOAL_AND_KIND}
     * stay unread (the oldest are retired), so no provider can bury the inbox. Returns the stored message, or empty for
     * a duplicate or a message over the limit (see {@link #offer}, which tells the two apart). The message text, the
     * suggestion text and the texts of its suggestions are stored without control characters (see
     * {@link LedgerRules#printable}).
     */
    public Optional<Inbox.Message> post(Inbox.Post given, String suggestionText) {
        return offer(given, suggestionText).message();
    }

    /**
     * What became of a post: the stored message, or empty; {@code held} is true only when the post was refused
     * because its provider already has {@link #MAX_UNREAD_PER_PROVIDER} unread messages, so the provider can offer it
     * again once the person has answered (yes or no) or dismissed some; reading a message leaves it unread. Empty and
     * not held is a duplicate of an observation already stored.
     */
    public record Posted(Optional<Inbox.Message> message, boolean held) {
    }

    /** The dedupe key of the kernel's note that a provider's messages are held back. */
    static String heldKey(String provider) {
        return "held:" + provider;
    }

    /**
     * {@link #post}, telling a duplicate from a message held back (see {@link Posted}). The first time a provider's
     * message is held back, the kernel posts one message of its own saying that this provider's new messages wait
     * until the person answers (yes or no) or dismisses some of its unread ones; that note is stored once per
     * observation like any other (its dedupe key names the provider), so a provider held again and again does not
     * fill the inbox with it. Serialised, so two providers or two runs cannot store the same observation twice.
     */
    public synchronized Posted offer(Inbox.Post given, String suggestionText) {
        Inbox.Post post = cleaned(given);
        suggestionText = LedgerRules.printable(suggestionText);
        if (post.kind() == Inbox.Kind.DEADLINE_RISK) {
            // A warning whose plan changed is withdrawn first, so it cannot block its own fresh version below.
            expireChangedRisks();
        }
        if (post.dedupeKey() != null) {
            // An unread message, or one the person answered, blocks the same observation for a week; one that was
            // replaced or expired does not, so the current version can come back. Keys belong to their provider:
            // another provider's message never blocks this one.
            int seen = jdbc.sql("""
                    SELECT count(*) FROM inbox WHERE dedupe_key = :k AND coalesce(provider, '') = :provider
                      AND created_at > :since AND status <> 'EXPIRED'""")
                    .param("k", post.dedupeKey()).param("provider", providerOf(post))
                    .param("since", OffsetDateTime.now(ZoneOffset.UTC).minusDays(7)).query(Integer.class).single();
            if (seen > 0) {
                return new Posted(Optional.empty(), false);
            }
        }
        List<Inbox.Message> replaced = supersededBy(post);
        if (!"kernel".equals(post.provider()) && replaced.isEmpty() && unreadFrom(post) >= MAX_UNREAD_PER_PROVIDER) {
            noteHeld(providerOf(post));
            return new Posted(Optional.empty(), true);
        }
        String basis = post.basis() != null && FINGERPRINT.matcher(post.basis()).matches() && post.goalId() != null
                ? post.basis() : basis(post.goalId(), post.suggestions());
        expire(replaced);
        if (post.goalId() != null) {
            makeRoom(post);
        }
        String id = UUID.randomUUID().toString();
        jdbc.sql("""
                INSERT INTO inbox (id, goal_id, kind, text, suggestions, suggestion_text, status, basis, provider,
                    dedupe_key, created_at)
                VALUES (:id, :goal, :kind, :text, CAST(:suggestions AS jsonb), :st, 'UNREAD', :basis, :provider, :key, :at)""")
                .param("id", id).param("goal", post.goalId()).param("kind", post.kind().name())
                .param("text", post.text()).param("suggestions", Json.write(post.suggestions()))
                .param("st", suggestionText).param("basis", basis).param("provider", post.provider())
                .param("key", post.dedupeKey()).param("at", OffsetDateTime.now(ZoneOffset.UTC)).update();
        Facts.InboxChanged fact = new Facts.InboxChanged(id, post.goalId(), post.kind().name(), "UNREAD", post.provider());
        if (post.goalId() != null) {
            ledger.record(post.goalId(), Facts.INBOX_POSTED, fact, "agent:" + post.provider(), id);
        } else {
            ledger.recordSystem(Facts.INBOX_POSTED, fact, "agent:" + post.provider());
        }
        return new Posted(find(id), false);
    }

    /**
     * Posts the kernel's note that {@code provider}'s new messages are held back until the person answers (yes or no)
     * or dismisses some of its unread ones; stored once per observation (see {@link #heldKey}).
     */
    private void noteHeld(String provider) {
        offer(new Inbox.Post(Inbox.Kind.SECURITY, null, heldNote(provider), List.of(), heldKey(provider), "kernel",
                "kernel", List.of(), null), null);
    }

    /**
     * The words of the note that {@code provider}'s new messages are held back. Only an answer or a dismissal frees a
     * place: a message that was read stays unread.
     */
    static String heldNote(String provider) {
        String name = provider == null || provider.isBlank() ? "an assistant" : "\"" + provider + "\"";
        return "New messages from " + name + " are held back: it already has " + MAX_UNREAD_PER_PROVIDER
                + " unread messages here. Answer (yes/no) or dismiss some of them and its new warnings come through "
                + "again.";
    }

    /** How many unread messages the post's provider has. */
    private int unreadFrom(Inbox.Post post) {
        Integer count = jdbc.sql("SELECT count(*) FROM inbox WHERE status = 'UNREAD' AND coalesce(provider, '') = :p")
                .param("p", providerOf(post)).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    /**
     * Retires the oldest unread messages of the post's kind about its goal from its provider, so that with the post
     * at most {@link #MAX_UNREAD_PER_GOAL_AND_KIND} stay unread ("blocked" messages about many different tasks).
     */
    private void makeRoom(Inbox.Post post) {
        List<Inbox.Message> same = jdbc.sql("""
                SELECT * FROM inbox WHERE status = 'UNREAD' AND kind = :kind AND goal_id = :goal
                  AND coalesce(provider, '') = :provider ORDER BY created_at""")
                .param("kind", post.kind().name()).param("goal", post.goalId()).param("provider", providerOf(post))
                .query((rs, i) -> message(rs)).list();
        int excess = same.size() - (MAX_UNREAD_PER_GOAL_AND_KIND - 1);
        if (excess > 0) {
            expire(same.subList(0, excess));
        }
    }

    /**
     * The older unread messages this one replaces: only messages of the same kind from the same provider, so one
     * provider can never withdraw another's (or the kernel's) messages.
     */
    private List<Inbox.Message> supersededBy(Inbox.Post post) {
        Optional<Superseded> scope = superseded(post);
        if (scope.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT * FROM inbox WHERE status = 'UNREAD' AND kind = :kind AND coalesce(provider, '') = :provider"
                + (scope.get().goalId() == null ? " AND goal_id IS NULL" : " AND goal_id = :goal")
                + (scope.get().keyPrefix() == null ? "" : " AND dedupe_key LIKE :prefix");
        var statement = jdbc.sql(sql).param("kind", post.kind().name()).param("provider", providerOf(post));
        if (scope.get().goalId() != null) {
            statement = statement.param("goal", scope.get().goalId());
        }
        if (scope.get().keyPrefix() != null) {
            statement = statement.param("prefix", keysStartingWith(scope.get().keyPrefix()));
        }
        List<Inbox.Message> found = statement.query((rs, i) -> message(rs)).list();
        return found == null ? List.of() : found;
    }

    /** Retires unread messages that no longer apply; each expiry is recorded as a fact by the kernel. */
    private void expire(List<Inbox.Message> messages) {
        messages.forEach(m -> settle(m, Inbox.Status.EXPIRED, "kernel"));
    }

    /** Which older messages a post replaces: those about this goal, and with this dedupe key prefix when set. */
    record Superseded(String goalId, String keyPrefix) {
    }

    /**
     * A goal message replaces older ones of its kind about the same goal; a blocked chain that names its blocked
     * task ({@code blocked:<task>:}) replaces only the older message about that task, and one that names none
     * replaces those about the goal. Goal-less messages are replaced per subject: a limit rule ({@code limit:<rule>:})
     * or an agent ({@code admission:<key>:}). Empty when nothing is replaced.
     */
    static Optional<Superseded> superseded(Inbox.Post post) {
        String key = post.dedupeKey();
        String[] parts = key == null ? new String[0] : key.split(":");
        if (post.goalId() == null) {
            boolean subject = parts.length >= 2 && (key.startsWith("limit:") || key.startsWith("admission:"));
            return subject ? Optional.of(new Superseded(null, parts[0] + ":" + parts[1] + ":")) : Optional.empty();
        }
        if (post.kind() == Inbox.Kind.BLOCKED_CHAIN && parts.length >= 2 && key.startsWith("blocked:")) {
            return Optional.of(new Superseded(post.goalId(), "blocked:" + parts[1] + ":"));
        }
        return Optional.of(new Superseded(post.goalId(), null));
    }

    private static String providerOf(Inbox.Post post) {
        return post.provider() == null ? "" : post.provider();
    }

    /**
     * The unread messages, oldest first, after withdrawing those that no longer apply: messages about closed goals,
     * "due today" and "overdue" from earlier days, and deadline warnings whose plan changed.
     */
    public List<Inbox.Message> unread() {
        expireSettledGoals();
        expireBygoneDays();
        expireChangedRisks();
        return jdbc.sql("SELECT * FROM inbox WHERE status = 'UNREAD' ORDER BY created_at")
                .query((rs, i) -> message(rs)).list();
    }

    /**
     * A message by id or unique id prefix of at least 4 characters, whatever its status. A reference that holds
     * anything but hex digits and hyphens (the characters of an id) finds nothing.
     */
    public Optional<Inbox.Message> find(String ref) {
        if (ref == null || !ID_PREFIX.matcher(ref).matches()) {
            return Optional.empty();
        }
        List<Inbox.Message> found = jdbc.sql("SELECT * FROM inbox WHERE id LIKE :p LIMIT 2")
                .param("p", ref.toLowerCase(Locale.ROOT) + "%").query((rs, i) -> message(rs)).list();
        return found.size() == 1 ? Optional.of(found.getFirst()) : Optional.empty();
    }

    /**
     * Answers an unread message. Atomic: of two answers given at once only one wins (returns true); the other
     * finds the message already answered.
     */
    public boolean settle(Inbox.Message message, Inbox.Status status, String principal) {
        int changed = jdbc.sql("UPDATE inbox SET status = :s, answered_at = :at WHERE id = :id AND status = 'UNREAD'")
                .param("s", status.name()).param("at", OffsetDateTime.now(ZoneOffset.UTC)).param("id", message.id()).update();
        if (changed == 0) {
            return false;
        }
        Facts.InboxChanged fact = new Facts.InboxChanged(message.id(), message.goalId(), message.kind().name(),
                status.name(), principal);
        if (message.goalId() != null) {
            ledger.record(message.goalId(), Facts.INBOX_ANSWERED, fact, principal, message.id());
        } else {
            ledger.recordSystem(Facts.INBOX_ANSWERED, fact, principal);
        }
        return true;
    }

    /** Settles the unread messages whose dedupe key starts with {@code prefix} (decided some other way). */
    public void settleByKey(String prefix, Inbox.Status status, String principal) {
        List<Inbox.Message> open = jdbc.sql("SELECT * FROM inbox WHERE status = 'UNREAD' AND dedupe_key LIKE :p")
                .param("p", keysStartingWith(prefix)).query((rs, i) -> message(rs)).list();
        open.forEach(m -> settle(m, status, principal));
    }

    /**
     * The LIKE pattern of the dedupe keys that start with {@code prefix}: its "%", "_" and backslashes escaped (the
     * backslash is the default LIKE escape), so a key a provider chose matches only as written.
     */
    static String keysStartingWith(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /**
     * Puts an accepted message back when none of its suggestions could be carried out, so it can be tried again. The
     * change back to unread is recorded as an {@code inbox.answered} fact with the status {@code UNREAD}, like every
     * other change of a message's status; nothing is recorded when the message was not accepted.
     */
    public void reopen(Inbox.Message message, String principal) {
        int changed = jdbc.sql("UPDATE inbox SET status = 'UNREAD', answered_at = NULL WHERE id = :id AND status = 'ACCEPTED'")
                .param("id", message.id()).update();
        if (changed == 0) {
            return;
        }
        Facts.InboxChanged fact = new Facts.InboxChanged(message.id(), message.goalId(), message.kind().name(),
                Inbox.Status.UNREAD.name(), principal);
        if (message.goalId() != null) {
            ledger.record(message.goalId(), Facts.INBOX_ANSWERED, fact, principal, message.id());
        } else {
            ledger.recordSystem(Facts.INBOX_ANSWERED, fact, principal);
        }
    }

    /** True when the goal facts the message rests on changed since it was posted. */
    public boolean outdated(Inbox.Message message) {
        if (message.basis() == null) {
            return false;
        }
        return !message.basis().equals(basis(message.goalId(), message.suggestions()));
    }

    /**
     * Fingerprint of the facts a message rests on: its goal's plan, or, for a message about several goals, the
     * plans of every goal its suggestions touch.
     */
    String basis(String goalId, List<Conversation.Action> suggestions) {
        if (goalId != null) {
            // The same view a provider gets (goal facts plus the person's capacity), so its basis compares.
            return ledger.load(goalId).map(s -> profile.context(s).planFingerprint()).orElse("gone");
        }
        TreeSet<String> goals = new TreeSet<>();
        suggestions.stream().map(Conversation.Action::goal).filter(Objects::nonNull).forEach(goals::add);
        if (goals.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        goals.forEach(g -> sb.append(g).append(':').append(ledger.load(g).map(s -> profile.context(s).planFingerprint())
                .orElse("gone")).append('|'));
        return Hashing.sha256(sb.toString()).substring(0, 16);
    }

    /**
     * A deadline warning is a calculation: once the plan or the person's capacity it was computed from changed, it
     * is withdrawn. Runs before every read of the inbox and before a new warning is stored, so the coach's fresh
     * warning for the changed plan is not taken for a duplicate of the withdrawn one.
     */
    private void expireChangedRisks() {
        List<Inbox.Message> risks = jdbc.sql("SELECT * FROM inbox WHERE status = 'UNREAD' AND kind = 'DEADLINE_RISK'")
                .query((rs, i) -> message(rs)).list();
        expire(risks.stream().filter(this::outdated).toList());
    }

    /** "Due today" and "overdue" describe one day; the coach writes a fresh one the next day if it still holds. */
    private void expireBygoneDays() {
        OffsetDateTime startOfToday = properties.today().atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        expire(jdbc.sql("SELECT * FROM inbox WHERE status = 'UNREAD' AND kind IN ('DUE_TODAY', 'OVERDUE')"
                        + " AND created_at < :start")
                .param("start", startOfToday).query((rs, i) -> message(rs)).list());
    }

    /** Messages about a cancelled or completed goal no longer apply. */
    private void expireSettledGoals() {
        expire(jdbc.sql("""
                SELECT i.* FROM inbox i JOIN goals g ON g.id = i.goal_id
                WHERE i.status = 'UNREAD' AND g.status IN ('CANCELLED', 'COMPLETED')""")
                .query((rs, i) -> message(rs)).list());
    }

    private Inbox.Message message(ResultSet rs) throws SQLException {
        String goalId = rs.getString("goal_id");
        String goalText = goalId == null ? null : ledger.load(goalId).map(GoalState::text).orElse(null);
        List<Conversation.Action> suggestions = List.of(Json.read(rs.getString("suggestions"), Conversation.Action[].class));
        return new Inbox.Message(rs.getString("id"), Inbox.Kind.valueOf(rs.getString("kind")), goalId, goalText,
                rs.getString("text"), suggestions, rs.getString("suggestion_text"),
                Inbox.Status.valueOf(rs.getString("status")), rs.getString("basis"), rs.getString("provider"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    /** True when the goal exists and is neither cancelled nor completed. */
    public boolean goalIsOpen(String goalId) {
        return ledger.load(goalId).map(g -> g.status() != GoalStatus.CANCELLED && g.status() != GoalStatus.COMPLETED)
                .orElse(false);
    }
}
