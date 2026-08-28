package art.insightai.chatbot.chat.api;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.service.ChatService;
import art.insightai.chatbot.chat.service.ConversationService;
import art.insightai.chatbot.common.session.SessionContext;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 * [Purpose] Full conversation CRUD plus the {@code POST .../messages} entry point: create, list,
 *   read a conversation's message history, send a message, soft-delete, and toggle {@code
 *   ragEnabled} (chat Spec §5 A-11, T6/T7/T8/T9).
 * [Rationale] Every method reads {@code userId} from {@link SessionContext} synchronously in the
 *   method body, before any downstream call, per that class's own [Rationale] (request-bound
 *   thread state does not survive into a reactive operator). This matters for {@link
 *   #sendMessage} specifically (Tech Review A-8): {@code userId} is captured into a local
 *   variable and {@link ConversationService#prepareMessageSend} runs to completion — ownership
 *   guard (chat:AC-17) and model whitelist check (chat:AC-22) both included — before the method
 *   ever constructs a {@code Flux}, so no reactive operator here ever touches
 *   {@code SessionContext}/{@code RequestContextHolder}. {@link #delete} and {@link
 *   #updateRagEnabled} (T8) build no {@code Flux} at all, so that specific hazard does not apply
 *   to them, but they keep the same "read {@code userId} first, then delegate" shape for
 *   consistency. Ownership/model-whitelist guarding, default model resolution, ordering, soft
 *   delete and field update all live in {@link ConversationService}; the actual SSE
 *   streaming/persistence behind {@link #sendMessage} lives in {@link ChatService} (T9). This
 *   class only translates HTTP in/out.
 * [Caution] {@code title} is {@code null} in {@link #create}'s response for every conversation —
 *   no message has been sent yet at creation time. {@link ChatService} (T9) sets it once the
 *   conversation's first message is sent (Tech Review A-12/OQ-4: first 30 characters of that
 *   message), so {@link #list}'s response shows a non-{@code null} {@code title} for any
 *   conversation that has received at least one message. {@link #delete} and {@link
 *   #updateRagEnabled} both 404 (via {@code ConversationNotFoundException}) for a missing id,
 *   another session's conversation, or an already-soft-deleted conversation alike — see that
 *   exception's own [Rationale] for why the three causes are not distinguished.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatService chatService;
    private final SessionContext sessionContext;

    public ConversationController(ConversationService conversationService, ChatService chatService,
            SessionContext sessionContext) {
        this.conversationService = conversationService;
        this.chatService = chatService;
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
     * [Purpose] Entry point for {@code POST /api/conversations/{id}/messages}: validates
     *   ownership (404 via {@code ConversationNotFoundException}) and, when a model is supplied,
     *   that it is a catalog id (400 via {@code InvalidModelException}) — both synchronously,
     *   before any {@code Flux} exists — then delegates to {@link ChatService#streamReply} for
     *   the actual SSE body (T9), passing {@code request.model()} through unchanged so it is
     *   {@link ChatService}, not this controller, that decides the null/blank fallback
     *   (chat T9 rework 2026-08-27, correctness D-4).
     * [Rationale] {@code produces = MediaType.TEXT_EVENT_STREAM_VALUE} together with a {@code
     *   Flux<ChatChunk>} return type (a POJO, never a raw {@code String}) is what makes
     *   chat:AC-24 true: Spring MVC serializes each emitted {@link ChatChunk} as its own JSON
     *   {@code data:} line instead of writing raw text (CLAUDE.md fact #6).
     * [Caution] {@code userId} is read from {@link SessionContext} here, in the synchronous
     *   method body, and never inside a {@code Flux} operator (Tech Review A-8) — do not move
     *   this read into a {@code .map()}/{@code .flatMap()}. T7 review round 1 Low-1 (security):
     *   this endpoint had no per-user send-frequency or daily cap once it started making a real
     *   OpenRouter call. T9 rework 2026-08-27 (security Medium-3, user decision D-3①) closes part
     *   of that gap: {@link ChatService#streamReply} now rejects once a conversation holds {@code
     *   app.session.max-messages-per-conversation} 'user' rows (chat:AC-45) — a per-conversation
     *   ceiling, not a time-windowed rate limit, so a caller can still create a new conversation
     *   (up to {@code max-conversations-per-user}) and resume sending at full speed immediately.
     *   That combination is not even bounded at {@code max-conversations-per-user ×
     *   max-messages-per-conversation} (50 × 200 = 10,000): deleting a conversation frees its slot
     *   in {@code max-conversations-per-user} right away, because {@code
     *   ConversationService#createConversation}'s count excludes soft-deleted rows while
     *   chat:AC-16 still requires the deleted conversation and its messages to survive on disk —
     *   the same fact {@link ConversationQuotaExceededException}'s own [Caution] already documents
     *   for conversation creation. A caller can therefore delete-and-recreate conversations
     *   indefinitely, so this endpoint's true ceiling on message volume from one session is
     *   unbounded, not the finite 10,000 the two static caps alone would suggest (chat T9 review
     *   round 2, security Low-1; user decision 2026-08-27: leave the caps as they are for now).
     *   {@link #delete} and {@link #updateRagEnabled} still have no cap of any kind. Revisit all of
     *   the above — the uncapped {@code delete}/{@code updateRagEnabled} endpoints and this
     *   quota-reset gap alike — together at Spec §8 R-6's roadmap stage ① (internal LAN release) —
     *   that stage's own text already calls for a rate-limit re-review at that point; not
     *   re-litigated by this card.
     * [AC] chat:AC-17, chat:AC-18, chat:AC-22, chat:AC-24
     */
    @PostMapping(value = "/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatChunk> sendMessage(@PathVariable UUID id, @Valid @RequestBody SendMessageRequest request) {
        UUID userId = sessionContext.currentUserId();
        Conversation conversation = conversationService.prepareMessageSend(id, userId, request.model());
        return chatService.streamReply(conversation, request.model(), request.message());
    }

    /**
     * [Purpose] Soft-deletes {@code id}: marks {@code conversations.deleted_at}, leaving that
     *   row and its {@code messages} rows in place (chat Spec §5 A-3, A-11). 404s (via {@code
     *   GlobalExceptionHandler}) when {@code id} does not exist, belongs to another session, or
     *   is already soft-deleted.
     * [Rationale] Returns {@code 204 No Content} with an empty body per §5 A-11's fixed API
     *   contract row ({@code DELETE /api/conversations/{id}} -> {@code 204}) — unlike {@link
     *   #create}'s {@code 201} (which must hand the caller the id/fields of a brand-new
     *   resource), a delete leaves nothing new to read back.
     * [Caution] No per-user send-frequency or daily cap guards this endpoint (T8 review round 1,
     *   Low-1) — same kind of gap {@link #sendMessage}'s own [Caution] flags (T7 review round 1,
     *   Low-1), but fully open here: unlike {@link #sendMessage} (T9 rework 2026-08-27, security
     *   Medium-3 — that endpoint now rejects past a per-conversation message ceiling), this
     *   endpoint has no cap of any kind yet. It is a write reachable by anyone holding the
     *   anonymous session cookie, with no authentication (chat Spec §8 R-6). Revisit together
     *   with {@link #sendMessage} at Spec §8 R-6's roadmap stage ① (internal LAN release) — that
     *   stage's own text already calls for a rate-limit re-review at that point; do not carry
     *   this gap past it unexamined.
     * [AC] chat:AC-16, chat:AC-17
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        UUID userId = sessionContext.currentUserId();
        conversationService.deleteConversation(id, userId);
    }

    /**
     * [Purpose] Updates {@code ragEnabled} on conversation {@code id} (chat Spec §5 A-11, D-6,
     *   §7 OQ-14). 404s under the same three conditions as {@link #delete}.
     * [Rationale] Returns only {@code {id, ragEnabled}} per §5 A-11's fixed contract, not the
     *   full {@link ConversationResponse} shape {@link #create} returns — this endpoint exists
     *   solely to flip one conversation-scoped field (D-6/OQ-14: the RAG toggle stays
     *   conversation-scoped, not a per-message override), so echoing the whole resource back
     *   would imply a broader "update conversation" contract this endpoint does not implement.
     * [Caution] Same rate-limiting gap as {@link #delete} — fully open, no per-user
     *   send-frequency or daily cap (T8 review round 1, Low-1; T7 review round 1, Low-1 on
     *   {@link #sendMessage}) — unlike {@link #sendMessage}, which T9 rework 2026-08-27 (security
     *   Medium-3) gave a per-conversation message ceiling; see that method's own [Caution]. Revisit
     *   together with {@link #sendMessage} and {@link #delete} at Spec §8 R-6's roadmap stage ①
     *   (internal LAN release); do not carry this gap past that stage unexamined.
     * [AC] chat:AC-41, chat:AC-44
     */
    @PatchMapping("/{id}")
    public ConversationRagEnabledResponse updateRagEnabled(
            @PathVariable UUID id, @Valid @RequestBody UpdateRagEnabledRequest request) {
        UUID userId = sessionContext.currentUserId();
        Conversation conversation = conversationService.updateRagEnabled(id, userId, request.ragEnabled());
        return ConversationRagEnabledResponse.from(conversation);
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

    /**
     * [Purpose] Request body for {@code PATCH /api/conversations/{id}} (chat Spec §5 A-11).
     * [Rationale] {@code ragEnabled} carries no {@code ?} in A-11's contract row (unlike {@code
     *   CreateConversationRequest}'s optional fields), so it is required: {@code @NotNull}
     *   rejects a missing/null value with 400 (via {@code GlobalExceptionHandler}'s existing
     *   {@code MethodArgumentNotValidException} handler) before {@code
     *   ConversationService#updateRagEnabled} ever runs, instead of unboxing a null {@code
     *   Boolean} into a {@code NullPointerException}.
     */
    public record UpdateRagEnabledRequest(@NotNull Boolean ragEnabled) {}

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

    /** [Purpose] Response body for {@code PATCH /api/conversations/{id}} (chat Spec §5 A-11). */
    public record ConversationRagEnabledResponse(UUID id, boolean ragEnabled) {
        static ConversationRagEnabledResponse from(Conversation conversation) {
            return new ConversationRagEnabledResponse(conversation.getId(), conversation.isRagEnabled());
        }
    }

    /** [Purpose] One entry of {@code GET /api/conversations/{id}/messages} (chat Spec §5 A-11). */
    public record MessageResponse(String role, String content, OffsetDateTime createdAt) {
        static MessageResponse from(Message message) {
            return new MessageResponse(message.getRole(), message.getContent(), message.getCreatedAt());
        }
    }
}
