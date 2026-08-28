package art.insightai.chatbot.chat.service;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.ConversationNotFoundException;
import art.insightai.chatbot.chat.domain.ConversationQuotaExceededException;
import art.insightai.chatbot.chat.domain.InvalidModelException;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.domain.MessageQuotaExceededException;
import art.insightai.chatbot.chat.repository.ConversationRepository;
import art.insightai.chatbot.chat.repository.MessageRepository;
import art.insightai.chatbot.common.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * [Purpose] Business logic for conversation CRUD: creation, ownership-scoped listing, ownership
 *   guard, message history retrieval, and (T9) the two transactional persistence steps behind a
 *   sent message (chat Spec §5 A-11, T6, T9).
 * [Rationale] The ownership guard ({@link #requireOwnedConversation}) is concentrated here, not
 *   in {@code ConversationController}, so every endpoint that needs "does this conversation
 *   belong to the caller" (T6's messages list, T7's message send, T8's delete/ragEnabled update)
 *   calls the same check rather than re-deriving it from the repository query. Default
 *   model resolution (chat:AC-23) reads {@link AppProperties.Models#defaultId()} — the same
 *   fail-fast-validated value {@code ModelsController} exposes via {@code GET /api/models} — so a
 *   caller that omits {@code model} always gets a value already known to be a real catalog entry.
 *   A caller-supplied {@code model} string is validated against {@link
 *   AppProperties.Models#contains} in both {@link #createConversation} and {@link
 *   #prepareMessageSend} (chat:AC-22, chat T7) — {@code null}/blank always skips that check and
 *   falls back to a value already known valid (the catalog default, or the conversation's own
 *   already-validated {@code model}), so the whitelist is only ever consulted for a value the
 *   caller actually chose. {@link #persistUserTurn}/{@link #persistAssistantTurn} exist purely so
 *   {@code ChatService} has a *different* Spring bean to call into for the two bulk {@code
 *   @Modifying} queries on {@link ConversationRepository} (T9 rework 2026-08-27, maintainability
 *   Medium-1) — Spring's proxy-based AOP only intercepts a call arriving through another bean's
 *   injected reference, never a same-class {@code this} call (self-invocation), so a {@code
 *   @Transactional} method on {@code ChatService} itself calling its own private helper would
 *   silently run without a transaction. {@link #enforceMessageQuota} follows the exact same
 *   "count, then reject before the costly step" shape as {@link #createConversation}'s {@code
 *   max-conversations-per-user} check, one level down (T4/T6 precedent; T9 review, security
 *   Medium-3).
 * [Caution] {@link #createConversation}, {@link #listConversations} and {@link #getMessages} all
 *   take {@code userId} explicitly from the caller (the controller reads it from {@link
 *   art.insightai.chatbot.common.session.SessionContext} before calling in) rather than reaching
 *   into {@code SessionContext} itself — keeps this class free of any HTTP/servlet dependency, so
 *   it stays trivially unit-testable without a request context bound. {@link #persistUserTurn}/
 *   {@link #persistAssistantTurn} take an already-built {@link Message} rather than raw fields —
 *   this class only persists it; it never decides a message's role, content or timing (that
 *   stays {@code ChatService}'s sole concern, per Tech Review A-4 and {@link Message}'s own
 *   class-level [Caution]).
 */
@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AppProperties appProperties;

    public ConversationService(ConversationRepository conversationRepository,
            MessageRepository messageRepository, AppProperties appProperties) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.appProperties = appProperties;
    }

    /**
     * [Purpose] Creates a new conversation owned by {@code userId}. {@code model} falls back to
     *   {@link AppProperties.Models#defaultId()} when {@code null} or blank; {@code ragEnabled}
     *   falls back to {@code true} when {@code null}.
     * [Rationale] The {@code ragEnabled} default follows chat Spec §7 OQ-14 (resolved
     *   2026-08-25): {@code POST /api/conversations} accepts {@code ragEnabled} with a default of
     *   {@code true}, so a caller that omits the field starts the conversation with RAG on. An
     *   earlier implementation defaulted {@code null} to {@code false}, contradicting the Approved
     *   Spec (D-1, 2026-08-28 fix). {@code null} and an explicit {@code false} are deliberately
     *   handled by different branches of the same expression rather than collapsed into one:
     *   {@code null} means the caller expressed no preference and falls through to the
     *   Spec-mandated default, while an explicit {@code false} is the caller's own choice to start
     *   this conversation with RAG off and must be honored as given, never silently promoted to
     *   {@code true}.
     * [Side effects] Throws {@link ConversationQuotaExceededException} instead of inserting when
     *   {@code userId} already holds {@code app.session.max-conversations-per-user} non-deleted
     *   conversations (T6 review 2026-08-27, Medium-2) — mirrors {@code
     *   AnonymousUserService#resolveOrCreate}'s "count, then reject before save" shape. The
     *   {@code count} and the eventual {@code save} are not wrapped in a single atomic check
     *   (no {@code SELECT ... FOR UPDATE}, no unique constraint on the count) — two concurrent
     *   requests for the same {@code userId} can both pass the count check and both insert,
     *   letting the row count exceed {@code maxConversationsPerUser} by a small margin (T6 review
     *   round 2, Low-1/Low-6). Left as-is: harmless at this project's demo scale (single-digit
     *   concurrent requests per user), revisit if/when this moves to a multi-instance deployment
     *   (revisit at stage 2 transition).
     * [Caution] The WARN log below has no rate limiting and no upper bound on volume (T6 review
     *   round 2, Low-2) — mirrors {@code AnonymousUserService#resolveOrCreate}'s equivalent
     *   [Caution]: while {@code userId} stays at the ceiling, every further create attempt logs
     *   again. If a file log sink ({@code logging.file.*}) is ever introduced, re-evaluate the
     *   log volume this can produce. Also throws {@link InvalidModelException} instead of
     *   inserting when a non-blank {@code model} does not match any {@code app.models[]}
     *   catalog entry (chat:AC-22, chat T7).
     * [AC] chat:AC-13, chat:AC-22, chat:AC-23, chat:AC-43
     */
    @Transactional
    public Conversation createConversation(UUID userId, String model, Boolean ragEnabled) {
        int maxConversationsPerUser = appProperties.session().maxConversationsPerUser();
        if (conversationRepository.countByUserIdAndDeletedAtIsNull(userId) >= maxConversationsPerUser) {
            log.warn("사용자별 대화 상한 도달, 신규 생성 거부 (userId={}, app.session.max-conversations-per-user={})",
                    userId, maxConversationsPerUser);
            throw new ConversationQuotaExceededException(userId, maxConversationsPerUser);
        }
        validateModelIfProvided(model);
        String resolvedModel = (model == null || model.isBlank())
                ? appProperties.models().defaultId()
                : model;
        boolean resolvedRagEnabled = ragEnabled == null || ragEnabled;
        Conversation conversation = new Conversation(userId, resolvedModel, resolvedRagEnabled);
        return conversationRepository.save(conversation);
    }

    /**
     * [Purpose] Lists {@code userId}'s non-deleted conversations, most recently updated first.
     * [AC] chat:AC-14
     */
    @Transactional(readOnly = true)
    public List<Conversation> listConversations(UUID userId) {
        return conversationRepository.findByUserIdAndDeletedAtIsNullOrderByUpdatedAtDesc(userId);
    }

    /**
     * [Purpose] Returns {@code conversationId}'s messages in {@code created_at} ascending order,
     *   after confirming the conversation belongs to {@code userId} and is not soft-deleted.
     * [AC] chat:AC-15
     */
    @Transactional(readOnly = true)
    public List<Message> getMessages(UUID conversationId, UUID userId) {
        requireOwnedConversation(conversationId, userId);
        return messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
    }

    /**
     * [Purpose] T7's entry guard for {@code POST /api/conversations/{id}/messages}: confirms
     *   ownership/non-deletion (chat:AC-17) and, when the caller supplies {@code model}, that it
     *   is a catalog id (chat:AC-22) — both run synchronously and return before the controller
     *   builds any {@code Flux} (Tech Review A-8: a reactive operator may run on a different
     *   thread with no request context bound, so this check cannot live there).
     * [Rationale] Reuses {@link #requireOwnedConversation}, the same guard {@link #getMessages}
     *   already relies on, so GET and POST agree on what "not found" means for a given id
     *   (chat:AC-17 covers both endpoints). {@code model == null || model.isBlank()} means the
     *   caller wants the conversation's already-persisted (and already-validated-at-creation)
     *   model, so no catalog check runs in that branch — mirrors {@link #createConversation}'s
     *   own blank-model handling.
     * [Side effects] None beyond the read: no {@code messages} row is written and no LLM is
     *   called here (chat:AC-18, chat:AC-22) — T9 adds the actual streaming body behind this
     *   guard.
     * [Caution] Returns the resolved {@link Conversation}, not just the resolved model string,
     *   because T9 (which replaces this method's caller with the real streaming body) needs the
     *   entity's other fields (e.g. {@code ragEnabled}) too, not only the model id.
     * [AC] chat:AC-17, chat:AC-18, chat:AC-22
     */
    @Transactional(readOnly = true)
    public Conversation prepareMessageSend(UUID conversationId, UUID userId, String model) {
        Conversation conversation = requireOwnedConversation(conversationId, userId);
        validateModelIfProvided(model);
        return conversation;
    }

    /**
     * [Purpose] Throws {@link MessageQuotaExceededException} when {@code conversationId} already
     *   holds {@code app.session.max-messages-per-conversation} 'user' {@link Message} rows (chat
     *   T9 review 2026-08-27, security Medium-3; user decision D-3①).
     * [Rationale] {@code ChatService#streamReply} calls this before {@link #persistUserTurn} and
     *   before building any {@code Flux}/calling the LLM — same "count, then reject before the
     *   costly step" shape as {@link #createConversation}'s {@code max-conversations-per-user}
     *   check, one level down (T4/T6 precedent). Counting only {@code role='user'} rows ties the
     *   ceiling directly to the number of paid OpenRouter calls this conversation has triggered —
     *   the one 'assistant' row each call also inserts does not count a second time against the
     *   same limit.
     * [Caution] The count and the eventual insert are not wrapped in one atomic check-and-insert
     *   (no {@code SELECT ... FOR UPDATE}, no unique constraint on the count) — same concurrency
     *   caveat, and same demo-scale justification for leaving it as-is, as {@link
     *   #createConversation}'s own [Side effects]. The WARN log below has the same no-rate-limit
     *   caveat as {@link #createConversation}'s own [Caution] and {@code
     *   AnonymousUserService#resolveOrCreate}'s equivalent.
     * [AC] chat:AC-45
     */
    @Transactional(readOnly = true)
    public void enforceMessageQuota(UUID conversationId) {
        int maxMessagesPerConversation = appProperties.session().maxMessagesPerConversation();
        if (messageRepository.countByConversationIdAndRole(conversationId, Message.ROLE_USER)
                >= maxMessagesPerConversation) {
            log.warn("대화별 메시지 상한 도달, 전송 거부 (conversationId={}, app.session.max-messages-per-conversation={})",
                    conversationId, maxMessagesPerConversation);
            throw new MessageQuotaExceededException(conversationId);
        }
    }

    /**
     * [Purpose] Atomically persists chat T9's 'user' turn: sets {@code conversations.title} when
     *   {@code titleIfUnset} is non-{@code null}, bumps {@code conversations.updated_at}, and
     *   inserts {@code userMessage} — the exact call sequence chat Spec Tech Review A-4/A-12
     *   requires before any LLM interaction begins.
     * [Rationale] Exists on this bean, not {@code ChatService}, solely so the three calls run
     *   inside one {@code @Transactional} method reached through a *different* bean's proxy — see
     *   this class's own [Rationale] for why. Grouping all three in one transaction also closes
     *   the atomicity gap chat T9 review 2026-08-27 correctness L7 flagged for the previous
     *   three-independent-transaction shape. {@code titleIfUnset} being {@code null} skips the
     *   {@code assignTitleIfAbsent} call entirely (chat T9 review, performance L12) — {@code
     *   ChatService} passes {@code null} once it already knows, from the {@link Conversation} it
     *   already holds, that {@code title} is non-null; it still passes a computed title whenever
     *   that field is {@code null}, so two concurrent first messages for the same brand-new
     *   conversation still both reach {@link ConversationRepository#assignTitleIfAbsent}'s atomic
     *   {@code WHERE title IS NULL} race guard unaffected.
     * [Side effects] Inserts one {@code messages} row; may update {@code conversations.title}/
     *   {@code updated_at}.
     * [Caution] Callers must pass only a {@code conversationId} (via {@code
     *   userMessage.getConversationId()}) that has already passed {@link
     *   #requireOwnedConversation} — unlike this class's other public methods, this one performs
     *   no ownership, existence, or soft-delete check of its own (chat T9 review round 2, security
     *   Low-2). The only current caller is {@code ChatService}, which only reaches this method
     *   after {@link #prepareMessageSend}'s guard has already run for the same id.
     * [AC] chat:AC-29
     */
    @Transactional
    public void persistUserTurn(String titleIfUnset, Message userMessage) {
        UUID conversationId = userMessage.getConversationId();
        if (titleIfUnset != null) {
            conversationRepository.assignTitleIfAbsent(conversationId, titleIfUnset);
        }
        conversationRepository.touchUpdatedAt(conversationId, OffsetDateTime.now());
        messageRepository.save(userMessage);
    }

    /**
     * [Purpose] Atomically persists chat T9's 'assistant' turn: bumps {@code
     *   conversations.updated_at} and inserts {@code assistantMessage} — called once the stream
     *   reaches a terminal signal, completion/error/cancellation alike (chat Spec §7 OQ-6, Tech
     *   Review A-6; T9 rework 2026-08-27, correctness High-1).
     * [Rationale] Same "different bean, one transaction" reasoning as {@link #persistUserTurn} —
     *   this is {@code ChatService}'s asynchronous completion-path counterpart, called from
     *   inside a {@code Flux} operator that may run on a thread other than the one that invoked
     *   {@code streamReply} (Tech Review A-8). Reaching this method through the injected {@code
     *   conversationService} field (never {@code this}) is what lets Spring's AOP proxy intercept
     *   the call and open a transaction regardless of which thread performs it.
     * [Side effects] Inserts one {@code messages} row; may update {@code conversations.updated_at}.
     * [Caution] Same precondition as {@link #persistUserTurn}: callers must pass only a {@code
     *   conversationId} (via {@code assistantMessage.getConversationId()}) that has already passed
     *   {@link #requireOwnedConversation} — this method performs no ownership, existence, or
     *   soft-delete check of its own (chat T9 review round 2, security Low-2).
     * [AC] chat:AC-29
     */
    @Transactional
    public void persistAssistantTurn(Message assistantMessage) {
        conversationRepository.touchUpdatedAt(assistantMessage.getConversationId(), OffsetDateTime.now());
        messageRepository.save(assistantMessage);
    }

    /**
     * [Purpose] Soft-deletes {@code conversationId}: stamps {@code deleted_at}, leaves the
     *   {@code conversations} row and its {@code messages} rows in place (chat Spec §5 A-3, T8).
     * [Rationale] Reuses {@link #requireOwnedConversation}, the same guard {@link #getMessages}
     *   and {@link #prepareMessageSend} already rely on, so a missing id, another session's
     *   conversation, and an already-soft-deleted conversation all resolve to the same {@link
     *   ConversationNotFoundException} -> 404 (chat:AC-17's {@code DELETE} clause — the last of
     *   that AC's three endpoints, which T8 closes). The field mutation itself is delegated to
     *   {@link Conversation#softDelete()} (A-3: physical delete stays out of scope); {@code save}
     *   is called explicitly afterward, mirroring {@link #createConversation}'s style, rather
     *   than relying on dirty-checking to flush the change at transaction commit.
     * [Side effects] Never calls {@link ConversationRepository#delete} / {@code deleteById}, and
     *   never touches {@code messages} — chat:AC-16 requires both tables' rows to survive.
     * [Caution] {@link ConversationQuotaExceededException}'s own [Caution] already notes that a
     *   soft delete here "frees up quota", because {@link #createConversation}'s check
     *   ({@code countByUserIdAndDeletedAtIsNull}) never counts a soft-deleted row. The flip side:
     *   {@code conversations} itself has no ceiling on cumulative row count. A caller can create
     *   up to {@code app.session.max-conversations-per-user}, delete all of them, and create up
     *   to the cap again, indefinitely — every one of those rows (and their {@code messages})
     *   stays on disk forever per chat:AC-16. This is intentional, not a defect: {@code
     *   application.yaml}'s comment on that property already says the cap excludes soft-deleted
     *   rows, and AC-16 requires a soft-deleted row to survive regardless of how many times a
     *   user repeats this create/delete cycle. Do not change this method to enforce a cumulative
     *   or physical-delete cap (T8 review round 1, Low-2).
     * [AC] chat:AC-16, chat:AC-17
     */
    @Transactional
    public void deleteConversation(UUID conversationId, UUID userId) {
        Conversation conversation = requireOwnedConversation(conversationId, userId);
        conversation.softDelete();
        conversationRepository.save(conversation);
    }

    /**
     * [Purpose] Updates {@code ragEnabled} on {@code conversationId} for {@code userId} (chat
     *   Spec §5 A-11, D-6, §7 OQ-14, T8).
     * [Rationale] Reuses {@link #requireOwnedConversation} exactly as {@link #deleteConversation}
     *   and {@link #prepareMessageSend} do, so a missing id, another session's conversation, and
     *   an already-soft-deleted conversation all 404 the same way (chat:AC-41's {@code PATCH}
     *   clause). Returns the saved {@link Conversation} so the controller can read {@code
     *   id}/{@code ragEnabled} back for the response body without a second query.
     * [Caution] This {@code save} used to be able to race with a concurrent {@link
     *   #deleteConversation} on the same row and silently undo the soft delete — see {@link
     *   Conversation}'s class-level [Caution] on {@code @DynamicUpdate} (T8 review round 1,
     *   High-1) for the full mechanism and why annotating the entity, not this method, closes it.
     * [AC] chat:AC-41, chat:AC-44
     */
    @Transactional
    public Conversation updateRagEnabled(UUID conversationId, UUID userId, boolean ragEnabled) {
        Conversation conversation = requireOwnedConversation(conversationId, userId);
        conversation.updateRagEnabled(ragEnabled);
        return conversationRepository.save(conversation);
    }

    /**
     * [Purpose] Resolves {@code conversationId} for {@code userId}, throwing {@link
     *   ConversationNotFoundException} when the id is missing, owned by someone else, or
     *   soft-deleted — the three cases {@code findByIdAndUserIdAndDeletedAtIsNull} deliberately
     *   does not distinguish (see that exception's [Rationale]).
     */
    private Conversation requireOwnedConversation(UUID conversationId, UUID userId) {
        return conversationRepository.findByIdAndUserIdAndDeletedAtIsNull(conversationId, userId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    /**
     * [Purpose] Throws {@link InvalidModelException} when {@code model} is non-blank and not a
     *   catalog id; does nothing when {@code model} is {@code null}/blank.
     * [Rationale] Extracted from {@link #createConversation} and {@link #prepareMessageSend},
     *   which previously each ran the same {@code appProperties.models().contains(model)} check
     *   inline (T7 review round 1, maintainability Low-1) — both callers treat "no model
     *   supplied" identically (skip the check, resolve a fallback elsewhere), so the blank guard
     *   belongs in one place. Mirrors this class's existing {@link #requireOwnedConversation}
     *   extraction (T6).
     * [AC] chat:AC-22
     */
    private void validateModelIfProvided(String model) {
        if (model != null && !model.isBlank() && !appProperties.models().contains(model)) {
            throw new InvalidModelException(model);
        }
    }
}
