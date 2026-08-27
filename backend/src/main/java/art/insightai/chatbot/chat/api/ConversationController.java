package art.insightai.chatbot.chat.api;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.service.ConversationService;
import art.insightai.chatbot.common.session.SessionContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * [Purpose] Conversation CRUD plus the {@code POST .../messages} entry point: create, list, read
 *   a conversation's message history, and send a message (chat Spec §5 A-11, T6/T7).
 *   {@code DELETE}/{@code PATCH} remain out of scope (T8).
 * [Rationale] Every method reads {@code userId} from {@link SessionContext} synchronously in the
 *   method body, before any downstream call, per that class's own [Rationale] (request-bound
 *   thread state does not survive into a reactive operator). This matters for {@link
 *   #sendMessage} specifically (Tech Review A-8): {@code userId} is captured into a local
 *   variable and {@link ConversationService#prepareMessageSend} runs to completion — ownership
 *   guard (chat:AC-17) and model whitelist check (chat:AC-22) both included — before the method
 *   ever constructs a {@code Flux}, so no reactive operator here ever touches
 *   {@code SessionContext}/{@code RequestContextHolder}. All business logic (default model
 *   resolution, ownership guard, ordering) lives in {@link ConversationService}; this class only
 *   translates HTTP in/out.
 * [Caution] {@code title} is always {@code null} in every response here — the title-generation
 *   rule (A-12) is T9's scope, not this card's. {@link #sendMessage} returns an empty
 *   {@code Flux} on success (chat:AC-18, chat:AC-22 — zero LLM calls, no {@code messages} row
 *   written) — T9 replaces that empty {@code Flux} with the real streaming body; do not build
 *   client features against this endpoint's current (empty) response shape.
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
     * [Purpose] T7's entry guard + SSE skeleton for {@code POST /api/conversations/{id}/messages}.
     *   Validates ownership (404 via {@code ConversationNotFoundException}) and, when a model is
     *   supplied, that it is a catalog id (400 via {@code InvalidModelException}) — both
     *   synchronously, before any {@code Flux} exists. On success, returns an empty
     *   {@code Flux<ChatChunk>}: this card makes no LLM call and writes no {@code messages} row
     *   (chat:AC-18, chat:AC-22). T9 replaces the empty {@code Flux} with the real streaming body.
     * [Caution] {@code userId} is read from {@link SessionContext} here, in the synchronous
     *   method body, and never inside a {@code Flux} operator (Tech Review A-8) — do not move
     *   this read into a {@code .map()}/{@code .flatMap()} once T9 adds real streaming logic.
     *   T7 review round 1 Low-1 (security): this endpoint has no per-user send-frequency or
     *   daily cap. Harmless today since T7 makes zero LLM calls and does one read-only DB query,
     *   but before T9 replaces the empty {@code Flux} with a real OpenRouter call, decide whether
     *   a rate limit is needed here — do not carry this gap into T9 unexamined.
     * [AC] chat:AC-17, chat:AC-18, chat:AC-22
     */
    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatChunk> sendMessage(@PathVariable UUID id, @Valid @RequestBody SendMessageRequest request) {
        UUID userId = sessionContext.currentUserId();
        conversationService.prepareMessageSend(id, userId, request.model());
        return Flux.empty();
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

    /**
     * [Purpose] Request body for {@code POST /api/conversations/{id}/messages} (chat Spec §5
     *   A-11).
     * [Rationale] {@code model} is optional — {@code null}/blank falls back to the
     *   conversation's already-persisted model ({@link ConversationService#prepareMessageSend}).
     *   {@code @Size(max = 64)} mirrors {@code CreateConversationRequest.model} for the same
     *   reason (T6 review 2026-08-27, Medium-1): an over-length id is rejected by {@code @Valid}
     *   (400) before it reaches the catalog check, which would also 400 but via a slower path.
     *   {@code message}'s {@code @Size(max = MAX_MESSAGE_LENGTH)} closes T7 review round 1
     *   Medium-1: {@code Message.content} is a {@code text} column with no DB-level length cap,
     *   {@code application.yaml}'s {@code multipart.max-*-size} only bounds multipart/form
     *   uploads (not this JSON body), and Tomcat's form-size limit is likewise form-content-type
     *   only — none of them touch this field, so without an explicit cap it is unbounded and a
     *   caller could submit an arbitrarily large string per request (resource exhaustion). The
     *   cap is a plain {@code static final int} rather than an {@code AppProperties} field
     *   because a {@code @Size} annotation attribute must be a compile-time constant — it cannot
     *   read a {@code @ConfigurationProperties} value at runtime the way {@code
     *   ConversationService}'s whitelist check reads {@code AppProperties.Models} — so this is not
     *   the same kind of "should this be configurable" choice as {@code
     *   app.ingest.max-file-size}/{@code app.session.max-conversations-per-user}.
     *   {@value #MAX_MESSAGE_LENGTH} is a demo-scale placeholder sized to a generous single chat
     *   input (on the order of a long paragraph, well above normal typed usage) — not a number
     *   backed by observed traffic. Revisit alongside T9's real LLM call, where the OpenRouter
     *   model's actual context window becomes the more meaningful ceiling.
     */
    public record SendMessageRequest(
            @NotBlank @Size(max = MAX_MESSAGE_LENGTH) String message,
            @Size(max = 64) String model
    ) {
        /** [Purpose] See {@link SendMessageRequest}'s [Rationale] for why this is a constant, not an {@code AppProperties} field. */
        public static final int MAX_MESSAGE_LENGTH = 4000;
    }

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
