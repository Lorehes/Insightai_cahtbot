package art.insightai.chatbot.chat.service;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * [Purpose] Streams an LLM reply for {@code POST /api/conversations/{id}/messages} and persists
 *   both turns of the exchange to {@code messages} (chat Spec §5 A-4, A-6, A-7, A-8, A-12, T9).
 * [Rationale] Per Tech Review A-4, this class is the sole *originator* of writes to {@code
 *   messages} — {@code MessageChatMemoryRepository} (T10) is read-only, and no other class ever
 *   decides to insert a row or picks its content. The call order matches A-4 exactly: ①
 *   {@link #recordUserMessage} runs synchronously, before the {@link Flux} is built, so the
 *   'user' row exists even if the caller disconnects before a single token streams; T10's history
 *   read (②) and this card's stream (③) come after; ④ the 'assistant' row is written once the
 *   stream reaches a terminal signal — completion, error, or cancellation alike (chat Spec §7
 *   OQ-6; T9 rework 2026-08-27, correctness High-1) — via {@link #streamReply}'s three
 *   termination hooks, which all funnel into the same guarded {@link #onStreamTerminated}
 *   helper (see that method's own [Rationale] for why three hooks, not one {@code doFinally}).
 *   Neither {@link #recordUserMessage} nor the assistant-side persistence calls
 *   a repository directly (T9 rework, maintainability Medium-1): both delegate to {@link
 *   ConversationService#persistUserTurn}/{@link ConversationService#persistAssistantTurn}, two
 *   {@code public @Transactional} methods on a *different* bean. This class's own methods run
 *   both from the synchronous prelude and from inside a {@code Flux} operator that may run on a
 *   different thread after {@link #streamReply} has already returned (Tech Review A-8) — a
 *   {@code @Transactional} method declared on this class itself would silently lose its
 *   transactional behavior via Spring AOP self-invocation (a proxy only applies to calls arriving
 *   from another bean's reference, never a same-class {@code this} call); routing through the
 *   injected {@code conversationService} field sidesteps that entirely, on top of already
 *   avoiding the detached-entity risk {@link Conversation}'s own [Caution] warns this exact card
 *   about (this class never calls {@code conversationRepository.save}). {@link #streamReply} also
 *   enforces {@code app.session.max-messages-per-conversation} (T9 rework, security Medium-3;
 *   user decision D-3①) via {@link ConversationService#enforceMessageQuota}, before {@link
 *   #recordUserMessage} runs and before any LLM call, so an exceeded conversation is rejected at
 *   zero OpenRouter cost.
 * [Caution] The per-message {@code model} override accepted by {@code
 *   ConversationController.SendMessageRequest} is honored as of T9 rework 2026-08-27 (correctness
 *   D-4 — previously validated by {@code ConversationService#prepareMessageSend} but then
 *   silently discarded): {@link #streamReply} takes the caller's {@code requestedModel} as an
 *   explicit parameter and uses it for both the actual OpenRouter call and the persisted
 *   'assistant' row's {@code model} column, falling back to {@code conversation.getModel()} only
 *   when {@code null}/blank — exactly what {@code prepareMessageSend}'s own [Rationale] already
 *   documented as the intended contract (that method only validates the override against the
 *   catalog, chat:AC-22; it does not resolve or return the fallback value itself, so this class
 *   still owns that resolution). {@code conversations.model} itself is never written by this
 *   class — a per-message override changes only which model this one call uses and which value
 *   this one {@code messages} row records, not the conversation's own stored setting (there is no
 *   "update a conversation's model" endpoint; adding one is out of this card's scope). RAG
 *   augmentation is out of scope for this Spec entirely (§2 Out of scope #1/#2 — separate {@code
 *   hybrid-rag} Spec) — {@link #streamReply} never branches on {@code conversation.isRagEnabled()}
 *   and builds a plain, single-turn prompt with no {@code RetrievalAugmentationAdvisor} and no
 *   conversation history (T10 adds history via {@code MessageWindowChatMemory}).
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** [Purpose] chat Spec Tech Review A-12/OQ-4: title = first user message, truncated. */
    private static final int TITLE_MAX_LENGTH = 30;
    private static final String TITLE_ELLIPSIS = "…";

    private final ChatClient chatClient;
    private final ConversationService conversationService;

    public ChatService(ChatClient.Builder chatClientBuilder, ConversationService conversationService) {
        this.chatClient = chatClientBuilder.build();
        this.conversationService = conversationService;
    }

    /**
     * [Purpose] Streams {@code conversation}'s reply to {@code userMessage} as SSE chunks, and
     *   persists both the 'user' and 'assistant' {@code messages} rows.
     * [Rationale] {@code conversation} is the (possibly detached — see class [Caution] and
     *   {@code ConversationService#prepareMessageSend}'s own [Caution]) entity {@code
     *   ConversationController} already resolved and validated synchronously (chat:AC-17,
     *   chat:AC-22) before calling in; only its already-loaded scalar fields ({@code id}, {@code
     *   title}, {@code model}) are read here, and the entity itself is never saved back. {@code
     *   requestedModel} is {@code SendMessageRequest.model} — already validated against the
     *   catalog by {@code prepareMessageSend} when non-blank (chat:AC-22); this method does not
     *   re-validate it, only resolves the fallback (class [Caution]). {@code contentBuilder}
     *   accumulates every streamed token in arrival order so the persisted 'assistant' row's
     *   content is exactly what a client reconstructs by concatenating every {@code delta} it
     *   received (chat:AC-25, chat:AC-29) — the accumulation happens inside {@link #toChunk}
     *   before that method decides what (if anything) goes into the outgoing chunk's own {@code
     *   delta}, so it is complete independent of that decision.
     * [Side effects] Throws {@code MessageQuotaExceededException} (via {@link
     *   ConversationService#enforceMessageQuota}) instead of doing anything else when {@code
     *   conversation}'s id already holds {@code app.session.max-messages-per-conversation} 'user'
     *   rows — no LLM call, no {@code messages} insert. Otherwise calls {@link #recordUserMessage}
     *   synchronously before returning. The returned {@link Flux} calls {@link
     *   #recordAssistantMessage} exactly once, guarded by an {@link AtomicBoolean}, no matter
     *   which terminal signal (normal completion, upstream error, or downstream cancellation)
     *   triggers it — via three separate hooks ({@code doOnComplete}, {@code doOnCancel}, and an
     *   {@code onErrorResume}-embedded call) that each funnel into the same guarded {@link
     *   #onStreamTerminated} helper (T9 rework 2026-08-27, correctness High-1/L5).
     * [Caution] Never read {@code SessionContext}/{@code RequestContextHolder} inside this
     *   method's {@link Flux} chain (Tech Review A-8) — the caller has already performed every
     *   check that needs it, and subscription may happen on a different thread than the one that
     *   invoked this method. Do not consolidate the three termination hooks into a single {@code
     *   doFinally(Consumer<SignalType>)} — a first attempt at this fix did exactly that and
     *   was reverted after real test runs (not source reading alone) caught {@code
     *   ConversationControllerIntegrationTest} losing the 'assistant' row under a {@code
     *   hasSize(2)} assertion on its fetched {@code messages} rows: {@code
     *   reactor.core.publisher.FluxDoFinally}'s {@code onComplete()}/{@code onError()} call
     *   {@code actual.onComplete()}/{@code actual.onError(t)} — i.e. forward the signal to the
     *   downstream subscriber, which for
     *   this endpoint is the servlet container writing the SSE response the test client reads —
     *   *before* running the {@code doFinally} consumer, so a fast reader can observe the stream's
     *   end and query {@code messages} before this class's INSERT has even executed. {@code
     *   doOnComplete}/{@code doOnError} (backed by {@code FluxPeek}), by contrast, run their
     *   callback *before* forwarding the signal downstream — confirmed by reading {@code
     *   reactor-core-3.8.7-sources.jar}, not assumed — which is why the error path below embeds
     *   its {@link #onStreamTerminated} call inside {@code onErrorResume}'s function (invoked to
     *   *produce* the fallback publisher, therefore strictly before that publisher is subscribed
     *   to and its one chunk reaches the client) rather than in a separate {@code doOnError}. The
     *   three hooks must all stay upstream of {@code onErrorResume} in the chain below: only from
     *   that position do {@code doOnComplete}/{@code doOnCancel} observe {@code chunks}' own
     *   signals rather than the fallback publisher {@code onErrorResume} substitutes on error.
     * [AC] chat:AC-34
     */
    public Flux<ChatChunk> streamReply(Conversation conversation, String requestedModel, String userMessage) {
        UUID conversationId = conversation.getId();
        String conversationIdText = conversationId.toString();

        conversationService.enforceMessageQuota(conversationId);

        String model = (requestedModel != null && !requestedModel.isBlank())
                ? requestedModel
                : conversation.getModel();
        String titleIfUnset = conversation.getTitle() == null ? generateTitle(userMessage) : null;
        recordUserMessage(conversationId, titleIfUnset, userMessage);

        StringBuilder contentBuilder = new StringBuilder();
        AtomicReference<String> finishReasonHolder = new AtomicReference<>();
        AtomicBoolean assistantMessageRecorded = new AtomicBoolean(false);

        Flux<ChatChunk> chunks = chatClient.prompt()
                .user(userMessage)
                .options(ChatOptions.builder().model(model))
                .stream()
                .chatResponse()
                .map(response -> toChunk(response, conversationIdText, contentBuilder, finishReasonHolder));

        return chunks
                .doOnComplete(() -> onStreamTerminated(SignalType.ON_COMPLETE, assistantMessageRecorded,
                        conversationId, model, contentBuilder, finishReasonHolder))
                .doOnCancel(() -> onStreamTerminated(SignalType.CANCEL, assistantMessageRecorded,
                        conversationId, model, contentBuilder, finishReasonHolder))
                .onErrorResume(error -> {
                    log.error("스트리밍 중 오류 발생 (conversationId={})", conversationId, error);
                    onStreamTerminated(SignalType.ON_ERROR, assistantMessageRecorded, conversationId,
                            model, contentBuilder, finishReasonHolder);
                    return Flux.just(ChatChunk.error(conversationIdText));
                });
    }

    /**
     * [Purpose] Maps one streamed {@link ChatResponse} to the {@link ChatChunk} sent over the
     *   wire, and accumulates its text into {@code contentBuilder}.
     * [Rationale] Sending {@code text} as a JSON string field — never concatenated into a raw SSE
     *   line — is what actually preserves a token's leading space: CLAUDE.md fact #6 documents
     *   that a raw {@code data: Friday} line loses the space before "Friday" to the SSE
     *   delimiter, while a JSON field such as {@code {"delta":" Friday"}} carries it untouched,
     *   and the same JSON encoding is what lets Korean text survive transport byte-for-byte (fact
     *   #7) once {@code ChatController} is gone and {@code spring.servlet.encoding.*} (fact #5) is
     *   the only thing left forcing the response charset. An empty {@code text} — only the
     *   terminal response, which both the real SDK and {@code StubChatModel} emit with an empty
     *   {@link org.springframework.ai.chat.messages.AssistantMessage} — maps to a {@code null}
     *   delta rather than {@code ""}, matching {@link ChatChunk#delta}'s documented "may be null"
     *   contract on the last chunk.
     * [Side effects] Appends {@code text} to {@code contentBuilder} and, when this response
     *   carries a non-null finish reason, stores it in {@code finishReasonHolder} — {@link
     *   #streamReply}'s termination hooks (via {@link #onStreamTerminated}) read both once the
     *   stream ends.
     * [AC] chat:AC-25, chat:AC-26, chat:AC-27, chat:AC-28
     */
    private ChatChunk toChunk(ChatResponse response, String conversationId, StringBuilder contentBuilder,
            AtomicReference<String> finishReasonHolder) {
        Generation result = response.getResult();
        String text = result.getOutput().getText();
        if (text == null) {
            text = "";
        }
        contentBuilder.append(text);

        String finishReason = result.getMetadata().getFinishReason();
        if (finishReason != null) {
            finishReasonHolder.set(finishReason);
        }

        return new ChatChunk(conversationId, text.isEmpty() ? null : text, finishReason, null);
    }

    /**
     * [Purpose] Inserts the 'user' {@code messages} row for {@code content}, and — per Tech
     *   Review A-12 — sets {@code conversations.title} when {@code titleIfUnset} is non-{@code
     *   null} and bumps {@code conversations.updated_at}.
     * [Rationale] Runs before the {@link Flux} in {@link #streamReply} is even built, so this row
     *   exists regardless of whether the client stays connected long enough for a single token to
     *   stream. {@code model} is intentionally {@code null} on this row — see {@link Message}'s
     *   own class-level Javadoc ("the 'user' row has no model/token/cost data"). Delegates the
     *   actual persistence to {@link ConversationService#persistUserTurn} — see class [Rationale]
     *   for why this class never calls a repository directly for this.
     * [AC] chat:AC-29
     */
    private void recordUserMessage(UUID conversationId, String titleIfUnset, String content) {
        Message message = new Message(
                conversationId, Message.ROLE_USER, content, null, null, null, null, null, null);
        conversationService.persistUserTurn(titleIfUnset, message);
    }

    /**
     * [Purpose] Shared endpoint for {@link #streamReply}'s three termination hooks ({@code
     *   doOnComplete}, {@code doOnCancel}, {@code onErrorResume}): decides {@code finishReason}
     *   from {@code signalType} and calls {@link #recordAssistantMessage} exactly once, guarded
     *   by {@code recorded} (T9 rework 2026-08-27, correctness High-1).
     * [Rationale] {@code CANCEL} (client disconnect/abort mid-stream, e.g. a closed browser tab)
     *   maps to {@link ChatChunk#FINISH_REASON_CANCELLED} per chat Spec §7 OQ-6 — before this
     *   fix, cancellation reached neither {@code doOnComplete} nor {@code onErrorResume}, so the
     *   'assistant' row was never written and a user who disconnected mid-answer lost it entirely
     *   on reconnect. {@code ON_ERROR} maps to {@link ChatChunk#FINISH_REASON_ERROR}; every other
     *   signal (in practice only {@code ON_COMPLETE}, the only other value any of the three call
     *   sites ever passes) falls back to whatever {@link #toChunk} last stored in {@code
     *   finishReasonHolder} (the real {@code stop}/{@code length} value the LLM reported).
     * [Side effects] Calls {@link #recordAssistantMessage} at most once per invocation of this
     *   method's owning {@link Flux} subscription — {@code recorded.compareAndSet(false, true)}
     *   returning {@code false} means some other terminal path already recorded this turn, so this
     *   call returns without doing anything further. This guard is what makes "exactly once" hold
     *   across three independent call sites rather than operator choice alone: e.g. if the {@code
     *   doOnComplete} hook's callback itself threw, {@code FluxPeek} (which backs {@code
     *   doOnComplete}) routes that exception into a new {@code onError} signal reaching the
     *   downstream {@code onErrorResume}, which would then also call this method — without the
     *   guard, chat:AC-29's "assistant 1행" would break in that edge case.
     */
    private void onStreamTerminated(SignalType signalType, AtomicBoolean recorded, UUID conversationId,
            String model, StringBuilder contentBuilder, AtomicReference<String> finishReasonHolder) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        String finishReason = switch (signalType) {
            case CANCEL -> ChatChunk.FINISH_REASON_CANCELLED;
            case ON_ERROR -> ChatChunk.FINISH_REASON_ERROR;
            default -> finishReasonHolder.get();
        };
        recordAssistantMessage(conversationId, model, contentBuilder.toString(), finishReason);
    }

    /**
     * [Purpose] Inserts the 'assistant' {@code messages} row once the stream reaches a terminal
     *   signal — normal completion, error, or cancellation alike (chat Spec §7 OQ-6: "저장한다" —
     *   Tech Review A-6's {@code finishReason} value set includes {@code error}/{@code cancelled}
     *   precisely so a non-{@code stop}/{@code length} outcome is still recorded, not discarded).
     * [Rationale] {@code content} is whatever {@link #toChunk} accumulated before the terminal
     *   signal — the full answer on success (chat:AC-29), or only the tokens received before
     *   failure/cancellation on the other two paths (possibly {@code ""}, which {@code
     *   messages.content}'s {@code NOT NULL} constraint accepts). {@code promptTokens}/{@code
     *   completionTokens}/{@code costUsd}/{@code sources} are {@code null} here on purpose:
     *   token/cost accounting is T11's scope (project card boundary, not implemented by this
     *   card) and source citations belong to the separate {@code hybrid-rag} Spec (chat Spec §2
     *   Out of scope #1) — this card writes neither. Delegates the actual persistence to {@link
     *   ConversationService#persistAssistantTurn} — see class [Rationale] for why this class never
     *   calls a repository directly for this.
     * [AC] chat:AC-29
     */
    private void recordAssistantMessage(UUID conversationId, String model, String content, String finishReason) {
        Message message = new Message(
                conversationId, Message.ROLE_ASSISTANT, content, model, null, null, null, null, finishReason);
        conversationService.persistAssistantTurn(message);
    }

    /**
     * [Purpose] Truncates {@code message} to {@value #TITLE_MAX_LENGTH} characters, appending
     *   {@value #TITLE_ELLIPSIS} when longer (chat Spec Tech Review A-12/OQ-4). No corresponding
     *   AC exists for this rule (Spec §8 N-2), so correctness here is unit-tested but not part of
     *   the AC evidence ledger.
     */
    private static String generateTitle(String message) {
        if (message.length() <= TITLE_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, TITLE_MAX_LENGTH) + TITLE_ELLIPSIS;
    }
}
