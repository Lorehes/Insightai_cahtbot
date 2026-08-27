package art.insightai.chatbot.chat.service;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.ConversationNotFoundException;
import art.insightai.chatbot.chat.domain.ConversationQuotaExceededException;
import art.insightai.chatbot.chat.domain.InvalidModelException;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.repository.ConversationRepository;
import art.insightai.chatbot.chat.repository.MessageRepository;
import art.insightai.chatbot.common.config.AppProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * [Purpose] Business logic for conversation CRUD: creation, ownership-scoped listing, ownership
 *   guard and message history retrieval (chat Spec §5 A-11, T6).
 * [Rationale] The ownership guard ({@link #requireOwnedConversation}) is concentrated here, not
 *   in {@code ConversationController}, so every current and future endpoint that needs "does
 *   this conversation belong to the caller" (this card's messages list, later cards' send/rename/
 *   delete) calls the same check rather than re-deriving it from the repository query. Default
 *   model resolution (chat:AC-23) reads {@link AppProperties.Models#defaultId()} — the same
 *   fail-fast-validated value {@code ModelsController} exposes via {@code GET /api/models} — so a
 *   caller that omits {@code model} always gets a value already known to be a real catalog entry.
 *   A caller-supplied {@code model} string is validated against {@link
 *   AppProperties.Models#contains} in both {@link #createConversation} and {@link
 *   #prepareMessageSend} (chat:AC-22, chat T7) — {@code null}/blank always skips that check and
 *   falls back to a value already known valid (the catalog default, or the conversation's own
 *   already-validated {@code model}), so the whitelist is only ever consulted for a value the
 *   caller actually chose.
 * [Caution] {@link #createConversation}, {@link #listConversations} and {@link #getMessages} all
 *   take {@code userId} explicitly from the caller (the controller reads it from {@link
 *   art.insightai.chatbot.common.session.SessionContext} before calling in) rather than reaching
 *   into {@code SessionContext} itself — keeps this class free of any HTTP/servlet dependency, so
 *   it stays trivially unit-testable without a request context bound.
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
     *   falls back to {@code false} when {@code null}.
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
     *   (2단계 전환 시 재검토).
     * [Caution] The WARN log below has no rate limiting and no upper bound on volume (T6 review
     *   round 2, Low-2) — mirrors {@code AnonymousUserService#resolveOrCreate}'s equivalent
     *   [Caution]: while {@code userId} stays at the ceiling, every further create attempt logs
     *   again. If a file log sink ({@code logging.file.*}) is ever introduced, re-evaluate the
     *   log volume this can produce. Also throws {@link InvalidModelException} instead of
     *   inserting when a non-blank {@code model} does not match any {@code app.models[]}
     *   catalog entry (chat:AC-22, chat T7).
     * [AC] chat:AC-13, chat:AC-22, chat:AC-23
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
        boolean resolvedRagEnabled = ragEnabled != null && ragEnabled;
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
