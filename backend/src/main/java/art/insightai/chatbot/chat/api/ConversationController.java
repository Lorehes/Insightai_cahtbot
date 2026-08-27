package art.insightai.chatbot.chat.api;

import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.service.ConversationService;
import art.insightai.chatbot.common.session.SessionContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * [Purpose] Conversation CRUD: create, list, and read a conversation's message history (chat
 *   Spec §5 A-11, T6). {@code POST .../messages} (SSE send), {@code DELETE}/{@code PATCH} are
 *   out of this card's scope (T7~T9).
 * [Rationale] Every method reads {@code userId} from {@link SessionContext} synchronously in the
 *   method body, before any downstream call, per that class's own [Rationale] (request-bound
 *   thread state does not survive into a reactive operator) — none of this card's endpoints are
 *   reactive, but the same call-site convention is kept for consistency with {@code
 *   SessionController}. All business logic (default model resolution, ownership guard,
 *   ordering) lives in {@link ConversationService}; this class only translates HTTP in/out.
 * [Caution] {@code title} is always {@code null} in every response here — the title-generation
 *   rule (A-12) is T9's scope, not this card's.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final SessionContext sessionContext;

    public ConversationController(ConversationService conversationService, SessionContext sessionContext) {
        this.conversationService = conversationService;
        this.sessionContext = sessionContext;
    }

    /**
     * [Purpose] Creates a conversation owned by the current session's user.
     * [AC] chat:AC-13, chat:AC-23
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationResponse create(@Valid @RequestBody(required = false) CreateConversationRequest request) {
        UUID userId = sessionContext.currentUserId();
        String model = request != null ? request.model() : null;
        Boolean ragEnabled = request != null ? request.ragEnabled() : null;
        Conversation conversation = conversationService.createConversation(userId, model, ragEnabled);
        return ConversationResponse.from(conversation);
    }

    /**
     * [Purpose] Lists the current session's non-deleted conversations, most recently updated
     *   first.
     * [AC] chat:AC-14
     */
    @GetMapping
    public List<ConversationSummaryResponse> list() {
        UUID userId = sessionContext.currentUserId();
        return conversationService.listConversations(userId).stream()
                .map(ConversationSummaryResponse::from)
                .toList();
    }

    /**
     * [Purpose] Returns {@code id}'s messages in {@code created_at} ascending order. 404s (via
     *   {@code GlobalExceptionHandler}) when {@code id} does not exist, belongs to another
     *   session, or is soft-deleted.
     * [AC] chat:AC-15
     */
    @GetMapping("/{id}/messages")
    public List<MessageResponse> messages(@PathVariable UUID id) {
        UUID userId = sessionContext.currentUserId();
        return conversationService.getMessages(id, userId).stream()
                .map(MessageResponse::from)
                .toList();
    }

    /**
     * [Purpose] Request body for {@code POST /api/conversations} (chat Spec §5 A-11).
     * [Rationale] {@code @Size(max = 64)} mirrors {@code Conversation.model}'s {@code varchar(64)}
     *   column so an over-length value is rejected by {@code @Valid} on the controller parameter
     *   (400) instead of reaching {@code ConversationRepository.save} and surfacing as an
     *   unhandled {@code DataIntegrityViolationException} (500) (T6 review 2026-08-27, Medium-1).
     */
    public record CreateConversationRequest(
            @Size(max = 64) String model,
            Boolean ragEnabled
    ) {}

    /** [Purpose] Response body for {@code POST /api/conversations} (chat Spec §5 A-11). */
    public record ConversationResponse(
            UUID id,
            String title,
            String model,
            boolean ragEnabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        static ConversationResponse from(Conversation conversation) {
            return new ConversationResponse(
                    conversation.getId(),
                    conversation.getTitle(),
                    conversation.getModel(),
                    conversation.isRagEnabled(),
                    conversation.getCreatedAt(),
                    conversation.getUpdatedAt());
        }
    }

    /** [Purpose] One entry of {@code GET /api/conversations} (chat Spec §5 A-11). */
    public record ConversationSummaryResponse(
            UUID id,
            String title,
            String model,
            boolean ragEnabled,
            OffsetDateTime updatedAt
    ) {
        static ConversationSummaryResponse from(Conversation conversation) {
            return new ConversationSummaryResponse(
                    conversation.getId(),
                    conversation.getTitle(),
                    conversation.getModel(),
                    conversation.isRagEnabled(),
                    conversation.getUpdatedAt());
        }
    }

    /** [Purpose] One entry of {@code GET /api/conversations/{id}/messages} (chat Spec §5 A-11). */
    public record MessageResponse(String role, String content, OffsetDateTime createdAt) {
        static MessageResponse from(Message message) {
            return new MessageResponse(message.getRole(), message.getContent(), message.getCreatedAt());
        }
    }
}
