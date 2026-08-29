package art.insightai.chatbot.chat.service;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.repository.MessageChatMemoryRepository;
import art.insightai.chatbot.common.config.AppProperties;

import com.openai.core.JsonValue;
import com.openai.models.completions.CompletionUsage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
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
 *   and never builds a {@code RetrievalAugmentationAdvisor} into the prompt. It does include prior
 *   conversation turns as of this card (T10): {@link #chatMemory}, a {@link MessageWindowChatMemory}
 *   built from {@link MessageChatMemoryRepository} in the constructor below, is read (never
 *   written — see {@link MessageChatMemoryRepository}'s own class-level [Rationale] for why {@code
 *   add()}/{@code saveAll()} are never called) inside {@link #streamReply}'s synchronous prelude;
 *   that method's own [Rationale] explains exactly why a single {@code .messages(history)} call
 *   replaces the previous {@code .user(userMessage)} call rather than sitting alongside it.
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** [Purpose] chat Spec Tech Review A-12/OQ-4: title = first user message, truncated. */
    private static final int TITLE_MAX_LENGTH = 30;
    private static final String TITLE_ELLIPSIS = "…";

    private final ChatClient chatClient;
    private final ConversationService conversationService;
    private final ChatMemory chatMemory;

    /**
     * [Purpose] Wires the three collaborators {@link #streamReply} needs: the fluent LLM client,
     *   the transactional persistence delegate, and (T10) the multi-turn history reader.
     * [Rationale] {@code chatMemory} is built here, as a plain field, the same way {@code
     *   chatClient} is built from its own {@code Builder} on the line above it — neither is
     *   exposed as a separate {@code @Bean} — so this constructor is the one place that pairs
     *   {@code chatMemoryRepository} with the {@code app.chat.memory.max-messages} window size
     *   (Tech Review A-5). See {@link MessageChatMemoryRepository}'s own class-level [Caution] for
     *   the one side effect of this choice: Spring AI's own autoconfiguration still creates a
     *   second, unrelated, default-sized {@code ChatMemory} bean alongside this field, confirmed
     *   harmless there.
     */
    public ChatService(ChatClient.Builder chatClientBuilder, ConversationService conversationService,
            MessageChatMemoryRepository chatMemoryRepository, AppProperties appProperties) {
        this.chatClient = chatClientBuilder.build();
        this.conversationService = conversationService;
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(appProperties.chat().memory().maxMessages())
                .build();
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
     *   delta}, so it is complete independent of that decision. {@code history} (T10) is this
     *   method's second synchronous step, reusing {@code conversationIdText} — because it runs
     *   after {@link #recordUserMessage} (Tech Review A-4's call order: ① 'user' row insert, ②
     *   history read), the list {@link #chatMemory}{@code .get(...)} returns already ends with
     *   this turn's own question, so {@code .messages(history)} below supplies both prior turns
     *   (chat:AC-30) and this turn's question in one call. A separate {@code .user(userMessage)}
     *   call was deliberately removed rather than kept alongside it: reading {@code
     *   DefaultChatClientUtils.toChatClientRequest} (spring-ai-client-chat-2.0.1-sources.jar)
     *   confirms the real {@link ChatClient} appends any {@code .messages(...)} contents and then
     *   any {@code .user(...)} text as two independent, additive steps — keeping both would have
     *   sent this turn's question to the LLM twice.
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
     *   doFinally(Consumer<SignalType>)} — a first attempt at this fix did exactly that and was
     *   reverted after real test runs (not source reading alone) caught {@code
     *   ConversationControllerIntegrationTest} losing the 'assistant' row under a {@code
     *   hasSize(2)} assertion on its fetched {@code messages} rows: {@code
     *   reactor.core.publisher.FluxDoFinally}'s {@code onComplete()}/{@code onError()} call
     *   {@code actual.onComplete()}/{@code actual.onError(t)} — i.e. forward the signal to the
     *   downstream subscriber, which for this endpoint is the servlet container writing the SSE
     *   response the test client reads — *before* running the {@code doFinally} consumer, so a
     *   fast reader can observe the stream's end and query {@code messages} before this class's
     *   INSERT has even executed. {@code doOnComplete}/{@code doOnError} (backed by {@code
     *   FluxPeek}), by contrast, run their callback *before* forwarding the signal downstream —
     *   confirmed by reading {@code reactor-core-3.8.7-sources.jar}, not assumed — which is why
     *   the error path below embeds its {@link #onStreamTerminated} call inside {@code
     *   onErrorResume}'s function (invoked to *produce* the fallback publisher, therefore
     *   strictly before that publisher is subscribed to and its one chunk reaches the client)
     *   rather than in a separate {@code doOnError}. The three hooks must all stay upstream of
     *   {@code onErrorResume} in the chain below: only from that position do {@code
     *   doOnComplete}/{@code doOnCancel} observe {@code chunks}' own signals rather than the
     *   fallback publisher {@code onErrorResume} substitutes on error. Multi-turn history (T10)
     *   also changed the order of magnitude of a paid call's prompt cost, and nothing added by
     *   this card bounds it — the T10 review (2026-08-28, security Medium-1) found the three
     *   existing defenses are all row-count axes, not a size/cost axis: {@code
     *   ConversationController.SendMessageRequest#MAX_MESSAGE_LENGTH} caps one message at 4000
     *   characters, {@code app.session.max-messages-per-conversation} caps a conversation at 200
     *   'user' rows, and {@code app.chat.memory.max-messages} caps the window at 40 rows. <b>None
     *   caps output length</b>: no {@code maxTokens} (or equivalent) is set anywhere in this
     *   codebase — the {@code OpenAiChatOptions.builder().model(model).extraBody(...)} call this
     *   method makes sets only the model id and (as of T11) OpenRouter's usage-include {@code
     *   extraBody} flag, neither of which bounds output length, and {@code grep -rn
     *   "maxTokens|max-tokens" backend/src/main/} returns zero matches. A
     *   table-wide aggregate of {@code messages} rows captured during 2026-08-28 browser
     *   verification (14 'user' rows / 14 'assistant' rows total, summed across multiple
     *   conversations — not one 28-row conversation; see limit (2) below) measured 'user' rows at
     *   18-111 characters (avg 54) and 'assistant' rows at 11-1750 characters (avg 517 — roughly
     *   10x a 'user' row). Projecting that average across a full 40-row window (20 user + 20
     *   assistant, instead of today's single ~54-character user message) gives roughly 20x54 +
     *   20x517 = 11,420 characters, a character-based amplification of roughly <b>211x</b> — well
     *   past the T10 review's message-*count*-based "up to ~40x" figure (the review's original 41
     *   was off-by-one), which undercounts because it treats every row as the same size when
     *   'assistant' rows run an order of magnitude longer than 'user' rows. Three limits apply to
     *   the 211x figure: (1) characters are not tokens — the five catalog models tokenize
     *   differently and mix Korean/English differently, so a character-based ratio is not
     *   guaranteed to hold token-for-token; (2) it is a projection, not an observation — the
     *   longest conversation actually seen has 6 messages, so the 40-row window has never once
     *   been full, and the 517-character average was extrapolated to 20 turns, not measured at
     *   that length; (3) the 'assistant' side's true worst case is unknown because nothing bounds
     *   it — the 'user' side's worst case is computable (20 x 4000 = 80,000 characters) but there
     *   is no equivalent ceiling on the other side. This gap was deliberately left uncoded pending
     *   real data. <b>Update (T11, landed 2026-08-29):</b> {@code prompt_tokens}/{@code
     *   completion_tokens}/{@code cost_usd} are now recorded per 'assistant' row (see {@link
     *   #extractUsage}/{@link #extractCost}) — but whether to introduce {@code maxTokens} is still
     *   not decided here, because the 211x figure above remains a projection, not an observation
     *   (limit (2)): no conversation has yet filled the 40-row window this card's own recording
     *   would need to confirm or refute that projection. Revisit once real operational traffic
     *   accumulates enough {@code cost_usd}/token history to replace the projection with a
     *   measurement, not from a guessed token budget. {@code maxTokens} is preferable to a
     *   bespoke token-budget mechanism because it is a first-class model option that bounds both
     *   the output and everything re-fed into the next turn's prompt at once — but setting it
     *   produces {@code finish_reason = LENGTH}, which has been observed to flip eval judging to
     *   a hallucination verdict while showing the user no truncation indicator ({@code
     *   insight-chat-72}), so introducing {@code maxTokens} and adding an AC for a "response was
     *   truncated" indicator must be decided together, not separately. Present risk from this gap
     *   is small — {@code server.address: 127.0.0.1} restricts this to a single local presenter
     *   demo, and no observed conversation has come close to filling the window — but that
     *   premise does not survive Spec R-6's stage-① (LAN-wide exposure); revisit this note when
     *   that stage is reached. The call-*count* side of this cap is tracked separately in {@code
     *   ConversationController.sendMessage}'s {@code [Caution]} note (T9 review round 2, security
     *   Low-1), whose acceptance of an unbounded call ceiling was made on 2026-08-27 — before
     *   this per-call amplification was known; the two factors (call count and per-call cost)
     *   multiply. The amplification arithmetic above is duplicated in Korean in {@code
     *   application.yaml}'s {@code app.session.max-messages-per-conversation} comment; keep both
     *   in sync when either changes.
     * [AC] chat:AC-30, chat:AC-34
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

        // ② per Tech Review A-4's call order: this read happens strictly after the 'user' row
        // above is inserted, so the returned history already ends with this turn's own question
        // -- see this method's own [Rationale] for why that removes the need for a separate
        // .user(userMessage) call below.
        var history = chatMemory.get(conversationIdText);

        StringBuilder contentBuilder = new StringBuilder();
        AtomicReference<String> finishReasonHolder = new AtomicReference<>();
        AtomicReference<ChatChunk.Usage> usageHolder = new AtomicReference<>();
        AtomicBoolean assistantMessageRecorded = new AtomicBoolean(false);

        Flux<ChatChunk> chunks = chatClient.prompt()
                .messages(history)
                // extraBody is OpenRouter's own extension, not a standard OpenAI request field --
                // S1 spike (state.md, 2026-08-25) confirmed the provider only attaches its `cost`
                // field to the response's usage payload when this flag is set per request; the
                // standard `include_usage` option (already defaulted on for streaming by
                // OpenAiChatModel.createRequest()) gets prompt/completion token counts without it,
                // but never `cost` (chat:AC-32, chat:AC-33).
                .options(OpenAiChatOptions.builder().model(model)
                        .extraBody(Map.of("usage", Map.of("include", true))))
                .stream()
                .chatResponse()
                .map(response -> toChunk(response, conversationIdText, contentBuilder, finishReasonHolder,
                        usageHolder));

        return chunks
                .doOnComplete(() -> onStreamTerminated(SignalType.ON_COMPLETE, assistantMessageRecorded,
                        conversationId, model, contentBuilder, finishReasonHolder, usageHolder))
                .doOnCancel(() -> onStreamTerminated(SignalType.CANCEL, assistantMessageRecorded,
                        conversationId, model, contentBuilder, finishReasonHolder, usageHolder))
                .onErrorResume(error -> {
                    log.error("스트리밍 중 오류 발생 (conversationId={})", conversationId, error);
                    onStreamTerminated(SignalType.ON_ERROR, assistantMessageRecorded, conversationId,
                            model, contentBuilder, finishReasonHolder, usageHolder);
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
     *   stream ends. Likewise, when this response's {@code usage} is non-zero, extracts it via
     *   {@link #extractUsage} and stores it in {@code usageHolder} (T11) — per the S1 spike
     *   (state.md, 2026-08-25), every non-terminal delta chunk carries a zeroed {@code usage}
     *   (prompt=0/completion=0/total=0), and the real token/cost summary lands only on the
     *   stream's last {@link ChatResponse}, so {@code usageHolder} ends up holding exactly that
     *   one response's extraction once the stream completes.
     * [AC] chat:AC-25, chat:AC-26, chat:AC-27, chat:AC-28, chat:AC-32, chat:AC-33
     */
    private ChatChunk toChunk(ChatResponse response, String conversationId, StringBuilder contentBuilder,
            AtomicReference<String> finishReasonHolder, AtomicReference<ChatChunk.Usage> usageHolder) {
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

        ChatChunk.Usage usage = null;
        Usage responseUsage = response.getMetadata().getUsage();
        if (responseUsage != null && responseUsage.getTotalTokens() > 0) {
            usage = extractUsage(responseUsage);
            usageHolder.set(usage);
        }

        return new ChatChunk(conversationId, text.isEmpty() ? null : text, finishReason, usage);
    }

    /**
     * [Purpose] Converts one Spring AI {@link Usage} into this card's wire/persistence shape:
     *   prompt/completion token counts plus (if present) OpenRouter's {@code cost} extension
     *   (S1 spike, chat:AC-32, chat:AC-33).
     * [Rationale] {@link Usage#getPromptTokens()}/{@link Usage#getCompletionTokens()} are
     *   type-safe first-class fields Spring AI already parses; {@code cost} is not — the S1
     *   spike (state.md, 2026-08-25) found it survives only inside {@link Usage#getNativeUsage()},
     *   which the real OpenAI Java SDK populates with a {@link CompletionUsage} whose unmapped
     *   JSON properties (including {@code cost}) are preserved via {@code @JsonAnySetter}/
     *   {@code _additionalProperties()} rather than dropped, so {@link #extractCost} is what
     *   actually reads it.
     * [AC] chat:AC-32, chat:AC-33
     */
    private static ChatChunk.Usage extractUsage(Usage usage) {
        return new ChatChunk.Usage(usage.getPromptTokens(), usage.getCompletionTokens(),
                extractCost(usage.getNativeUsage()));
    }

    /**
     * [Purpose] Reads OpenRouter's {@code cost} extension out of the OpenAI SDK's native usage
     *   object, as a {@link BigDecimal} (chat:AC-33).
     * [Rationale] {@code nativeUsage} must be cast to the concrete SDK type
     *   {@link CompletionUsage} — Spring AI's {@link Usage} interface never promotes {@code cost}
     *   to a first-class field (S1 spike). {@code numberOpt.get().toString()} deliberately reads
     *   the {@link JsonValue}'s number through its {@code String} form rather than {@code
     *   doubleValue()} followed by {@link BigDecimal#valueOf(double)} — the monetary-type rule
     *   (global CLAUDE.md §7.2-4) forbids this code from adding its own floating-point rounding
     *   step on top of whatever the wire value already was. Whatever precision the OpenAI SDK's
     *   own Jackson tree already collapsed the literal into before this method ever sees it (a
     *   {@code DoubleNode}, since neither this project nor the SDK enables {@code
     *   USE_BIG_DECIMAL_FOR_FLOATS} — confirmed by reading {@code
     *   openai-java-core-4.49.0-sources.jar}'s {@code ObjectMappers.kt}) is a boundary this method
     *   cannot undo; it only guarantees it does not lose further precision itself, and that the
     *   one {@link BigDecimal} produced here is the exact value both {@link #toChunk}'s wire
     *   {@code usage.cost} and {@link #recordAssistantMessage}'s persisted {@code cost_usd} column
     *   end up holding (they read the same {@code usageHolder} entry — chat:AC-33's "응답의 값 =
     *   DB 행의 값" holds by construction, not by coincidence).
     * [Caution] Returns {@code null} whenever {@code nativeUsage} is not a {@link CompletionUsage},
     *   the {@code "cost"} additional property is absent (e.g. {@code extraBody}'s usage-
     *   include flag was not honored by the provider), or the property's numeric literal cannot be
     *   parsed into a {@link BigDecimal} (2026-08-29 review L5) — e.g. {@code "NaN"}/{@code
     *   "Infinity"}, which {@link BigDecimal#BigDecimal(String)} rejects with a {@code
     *   NumberFormatException}. Reachability of that last case is not confirmed either way:
     *   standard JSON has no non-numeric number literal, but whether the OpenAI SDK's Jackson
     *   configuration enables {@code ALLOW_NON_NUMERIC_NUMBERS}-style parsing of such tokens if
     *   OpenRouter ever sent one was not checked. The guard costs nothing if unreachable and, if it
     *   ever is reached, keeps this one bad value from turning an otherwise-successful, already
     *   fully-billed response into {@link ChatChunk#error} for the caller (the exception would
     *   otherwise propagate out of {@link #toChunk}'s {@code map} and be caught by {@link
     *   #streamReply}'s {@code onErrorResume}) — every caller must still treat a {@code null} cost
     *   as "unknown", never coerce it to zero.
     * [AC] chat:AC-33
     */
    private static BigDecimal extractCost(Object nativeUsage) {
        if (!(nativeUsage instanceof CompletionUsage completionUsage)) {
            return null;
        }
        JsonValue costValue = completionUsage._additionalProperties().get("cost");
        if (costValue == null) {
            return null;
        }
        Optional<Number> costNumber = costValue.asNumber();
        if (costNumber.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(costNumber.get().toString());
        } catch (NumberFormatException e) {
            return null;
        }
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
            String model, StringBuilder contentBuilder, AtomicReference<String> finishReasonHolder,
            AtomicReference<ChatChunk.Usage> usageHolder) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        String finishReason = switch (signalType) {
            case CANCEL -> ChatChunk.FINISH_REASON_CANCELLED;
            case ON_ERROR -> ChatChunk.FINISH_REASON_ERROR;
            default -> finishReasonHolder.get();
        };
        recordAssistantMessage(conversationId, model, contentBuilder.toString(), finishReason, usageHolder.get());
    }

    /**
     * [Purpose] Inserts the 'assistant' {@code messages} row once the stream reaches a terminal
     *   signal — normal completion, error, or cancellation alike (chat Spec §7 OQ-6: "저장한다" —
     *   Tech Review A-6's {@code finishReason} value set includes {@code error}/{@code cancelled}
     *   precisely so a non-{@code stop}/{@code length} outcome is still recorded, not discarded).
     * [Rationale] {@code content} is whatever {@link #toChunk} accumulated before the terminal
     *   signal — the full answer on success (chat:AC-29), or only the tokens received before
     *   failure/cancellation on the other two paths (possibly {@code ""}, which {@code
     *   messages.content}'s {@code NOT NULL} constraint accepts). {@code usage} (T11) is whatever
     *   {@link #toChunk} last stored in {@code usageHolder} — the same {@link ChatChunk.Usage}
     *   instance the client's own last-chunk {@code usage} field carries, so {@code
     *   promptTokens}/{@code completionTokens}/{@code costUsd} persisted below are identical to
     *   the response the caller already received (chat:AC-32, chat:AC-33), not a second,
     *   independently-derived read. {@code usage} stays {@code null} on the error/cancel paths
     *   whenever no chunk ever reached a non-zero usage before the stream terminated — those rows
     *   keep {@code null} token/cost columns exactly as before this card. {@code sources} is still
     *   {@code null} here on purpose: source citations belong to the separate {@code hybrid-rag}
     *   Spec (chat Spec §2 Out of scope #1), untouched by this card. Delegates the actual
     *   persistence to {@link ConversationService#persistAssistantTurn} — see class [Rationale]
     *   for why this class never calls a repository directly for this.
     * [AC] chat:AC-29, chat:AC-32, chat:AC-33
     */
    private void recordAssistantMessage(UUID conversationId, String model, String content, String finishReason,
            ChatChunk.Usage usage) {
        Integer promptTokens = usage != null ? usage.promptTokens() : null;
        Integer completionTokens = usage != null ? usage.completionTokens() : null;
        BigDecimal costUsd = usage != null ? usage.cost() : null;
        Message message = new Message(conversationId, Message.ROLE_ASSISTANT, content, model,
                promptTokens, completionTokens, costUsd, null, finishReason);
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
