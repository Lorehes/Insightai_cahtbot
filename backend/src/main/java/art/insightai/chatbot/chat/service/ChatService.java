package art.insightai.chatbot.chat.service;

import art.insightai.chatbot.chat.domain.ChatChunk;
import art.insightai.chatbot.chat.domain.Conversation;
import art.insightai.chatbot.chat.domain.Message;
import art.insightai.chatbot.chat.repository.MessageChatMemoryRepository;
import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.rag.domain.SourceRef;
import art.insightai.chatbot.rag.retrieval.RagAdvisorPair;
import art.insightai.chatbot.rag.retrieval.RagEmptyContextGuardAdvisor;
import art.insightai.chatbot.rag.retrieval.SourceRefMapper;

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
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 *   OQ-6; T9 rework 2026-08-27, correctness High-1) — via {@link #streamReply(Conversation, String, String, Integer)}'s three
 *   termination hooks, which all funnel into the same guarded {@link #onStreamTerminated}
 *   helper (see that method's own [Rationale] for why three hooks, not one {@code doFinally}).
 *   Neither {@link #recordUserMessage} nor the assistant-side persistence calls
 *   a repository directly (T9 rework, maintainability Medium-1): both delegate to {@link
 *   ConversationService#persistUserTurn}/{@link ConversationService#persistAssistantTurn}, two
 *   {@code public @Transactional} methods on a *different* bean. This class's own methods run
 *   both from the synchronous prelude and from inside a {@code Flux} operator that may run on a
 *   different thread after {@link #streamReply(Conversation, String, String, Integer)} has already returned (Tech Review A-8) — a
 *   {@code @Transactional} method declared on this class itself would silently lose its
 *   transactional behavior via Spring AOP self-invocation (a proxy only applies to calls arriving
 *   from another bean's reference, never a same-class {@code this} call); routing through the
 *   injected {@code conversationService} field sidesteps that entirely, on top of already
 *   avoiding the detached-entity risk {@link Conversation}'s own [Caution] warns this exact card
 *   about (this class never calls {@code conversationRepository.save}). {@link #streamReply(Conversation, String, String, Integer)} also
 *   enforces {@code app.session.max-messages-per-conversation} (T9 rework, security Medium-3;
 *   user decision D-3①) via {@link ConversationService#enforceMessageQuota}, before {@link
 *   #recordUserMessage} runs and before any LLM call, so an exceeded conversation is rejected at
 *   zero OpenRouter cost.
 * [Caution] The per-message {@code model} override accepted by {@code
 *   ConversationController.SendMessageRequest} is honored as of T9 rework 2026-08-27 (correctness
 *   D-4 — previously validated by {@code ConversationService#prepareMessageSend} but then
 *   silently discarded): {@link #streamReply(Conversation, String, String, Integer)} takes the caller's {@code requestedModel} as an
 *   explicit parameter and uses it for both the actual OpenRouter call and the persisted
 *   'assistant' row's {@code model} column, falling back to {@code conversation.getModel()} only
 *   when {@code null}/blank — exactly what {@code prepareMessageSend}'s own [Rationale] already
 *   documented as the intended contract (that method only validates the override against the
 *   catalog, chat:AC-22; it does not resolve or return the fallback value itself, so this class
 *   still owns that resolution). {@code conversations.model} itself is never written by this
 *   class — a per-message override changes only which model this one call uses and which value
 *   this one {@code messages} row records, not the conversation's own stored setting (there is no
 *   "update a conversation's model" endpoint; adding one is out of this card's scope). RAG
 *   augmentation was out of scope for chat Spec's own T10 (chat Spec §2 Out of scope #1/#2 —
 *   separate {@code hybrid-rag} Spec) as of that card's landing — at that point {@link
 *   #streamReply} never branched on {@code conversation.isRagEnabled()} and never built a {@code
 *   RetrievalAugmentationAdvisor} into the prompt. <b>That gap is closed by hybrid-rag Spec's own
 *   T10 card ("{@code ChatService} integration — conditional advisor attachment gated on {@code
     *   rag_enabled}") — see {@link
 *   #streamReply}'s own [Rationale] for the current attachment mechanics (hybrid-rag:AC-9 clause①,
 *   AC-10, AC-14 final).</b> It does include prior conversation turns as of chat Spec's T10: {@link
 *   #chatMemory}, a {@link MessageWindowChatMemory} built from {@link MessageChatMemoryRepository}
 *   in the constructor below, is read (never written — see {@link MessageChatMemoryRepository}'s
 *   own class-level [Rationale] for why {@code add()}/{@code saveAll()} are never called) inside
 *   {@link #streamReply(Conversation, String, String, Integer)}'s synchronous prelude; that method's own [Rationale] explains exactly why
 *   a single {@code .messages(history)} call replaces the previous {@code .user(userMessage)} call
 *   rather than sitting alongside it.
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
    private final RagAdvisorPair ragAdvisorPair;
    private final SourceRefMapper sourceRefMapper;
    private final ObjectMapper objectMapper;

    /**
     * [Purpose] Wires the six collaborators {@link #streamReply(Conversation, String, String, Integer)} needs: the fluent LLM client,
     *   the transactional persistence delegate, (T10) the multi-turn history reader, (hybrid-rag
     *   T10) the RAG advisor pair conditionally attached per request, and (hybrid-rag T11) the
     *   {@code RetrievedChunk}-&gt;{@code SourceRef} mapper plus the JSON codec {@link
     *   #recordAssistantMessage} uses to persist {@code messages.sources}.
     * [Rationale] {@code chatMemory} is built here, as a plain field, the same way {@code
     *   chatClient} is built from its own {@code Builder} on the line above it — neither is
     *   exposed as a separate {@code @Bean} — so this constructor is the one place that pairs
     *   {@code chatMemoryRepository} with the {@code app.chat.memory.max-messages} window size
     *   (Tech Review A-5). See {@link MessageChatMemoryRepository}'s own class-level [Caution] for
     *   the one side effect of this choice: Spring AI's own autoconfiguration still creates a
     *   second, unrelated, default-sized {@code ChatMemory} bean alongside this field, confirmed
     *   harmless there. {@code ragAdvisorPair} is {@code RagAdvisorConfig#ragAdvisors} (hybrid-rag
     *   T9/T12/T10 rework rounds 1-2) — a {@link RagAdvisorPair} record bundling the RAG advisor and
     *   its empty-context guard together. Stored here rather than attached via {@code
     *   chatClientBuilder.defaultAdvisors(...)} in this constructor, because attaching it
     *   unconditionally would run RAG for every conversation regardless of {@code rag_enabled}
     *   (hybrid-rag Tech Review D-T9: RAG ON/OFF is this class's decision, not the retriever's —
     *   see {@link #streamReply(Conversation, String, String, Integer)}'s own [Rationale] for the per-request conditional attachment this
     *   field exists to support). T10 rework round 1 (review Block security Medium-2) first closed "RAG
     *   attached without the guard" as a single {@code List<Advisor>} (this class previously held
     *   the pair as two separate constructor parameters, which let a future edit attach one without
     *   the other); T10 rework round 2 (review round-2 Block security Medium-2) replaced that {@code
     *   List<Advisor>} with this dedicated record type — a bare {@code List<Advisor>} still
     *   compiled for {@code .advisors(ragAdvisors.get(0))} and was matched into this constructor by
     *   Spring only via the parameter's own *name* ({@code ragAdvisors}), not its type (see {@link
     *   RagAdvisorPair}'s own Javadoc for both mechanics, confirmed against {@code
     *   DefaultListableBeanFactory} source). {@link RagAdvisorPair#toList()} is now the only way to
     *   obtain the {@code List<Advisor>} {@link ChatClient.ChatClientRequestSpec#advisors} requires,
     *   and Spring resolves this constructor parameter by this record's own distinct type.
     *   {@code sourceRefMapper} (hybrid-rag T11) is the pre-existing {@code @Component} T8 already
     *   built and unit-tested ({@code SourceRefMapperTest}) — this class is simply its first
     *   caller, never reimplementing its {@code document_id}/{@code filename}/{@code page}
     *   extraction. {@code objectMapper} is Spring Boot 4's autoconfigured {@code
     *   tools.jackson.databind.ObjectMapper} bean (Jackson 3, not the classic {@code
     *   com.fasterxml.jackson.databind.ObjectMapper} — see {@code
     *   ConversationControllerIntegrationTest}'s own class Javadoc for the same distinction on the
     *   read side); {@link #recordAssistantMessage} uses it only to serialize {@code
     *   List<ChatChunk.Source>} to the JSON text {@code messages.sources} stores, never to parse
     *   anything.
     */
    public ChatService(ChatClient.Builder chatClientBuilder, ConversationService conversationService,
            MessageChatMemoryRepository chatMemoryRepository, AppProperties appProperties,
            RagAdvisorPair ragAdvisorPair, SourceRefMapper sourceRefMapper, ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.conversationService = conversationService;
        this.chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(appProperties.chat().memory().maxMessages())
                .build();
        this.ragAdvisorPair = ragAdvisorPair;
        this.sourceRefMapper = sourceRefMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * [Purpose] Convenience overload of {@link #streamReply(Conversation, String, String, Integer)}
     *   with no {@code maxTokens} override -- the path every production caller uses. See that
     *   method for this class's full behavior (RAG attachment, persistence, termination hooks).
     */
    public Flux<ChatChunk> streamReply(Conversation conversation, String requestedModel, String userMessage) {
        return streamReply(conversation, requestedModel, userMessage, null);
    }

    /**
     * [Purpose] Streams {@code conversation}'s reply to {@code userMessage} as SSE chunks, and
     *   persists both the 'user' and 'assistant' {@code messages} rows. When {@code maxTokensOverride}
     *   is non-null, it is also passed through to {@code OpenAiChatOptions.maxTokens(...)} for
     *   this call only -- see the "Overload rationale (BL-024)" paragraph below.
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
     *   sent this turn's question to the LLM twice. {@code requestSpec} (hybrid-rag T10) is built
     *   from the {@code .messages(...).options(...)} chain above and only conditionally gains
     *   {@link #ragAdvisorPair}'s two advisors via {@code .advisors(ragAdvisorPair.toList())} when
     *   {@code conversation.isRagEnabled()} is {@code true} — never as {@code ChatClient.Builder}-
     *   level default advisors, per hybrid-rag Tech Review D-T9 ("RAG ON/OFF is decided by whether
     *   {@code ChatService} attaches the advisor, not by the retriever"). When {@code false}, {@code .advisors(...)} is
     *   never called at all — not called with an empty list — so {@link HybridDocumentRetriever}/
     *   its two search ports are never invoked for that conversation (hybrid-rag:AC-10). {@link
     *   ChatClient.ChatClientRequestSpec#advisors(java.util.List)} is the overload used here (T10
     *   rework round 1, review Block security Medium-2 — switched from the {@code Advisor...} varargs
     *   overload this method used through T9/T12, confirmed against {@code
     *   spring-ai-client-chat-2.0.1-sources.jar}'s {@code ChatClient.java}, which declares three
     *   {@code advisors} overloads on this interface: {@code Consumer<AdvisorSpec>}, {@code
     *   Advisor...}, and {@code List<Advisor>}) — {@link RagAdvisorPair#toList()} (T10 rework
     *   round 2) is now what produces that fixed pair as one {@code List}, so this overload remains
     *   the direct fit with no need for {@code AdvisorSpec}'s builder-style mutation API or an
     *   array-conversion step. List order inside {@link RagAdvisorPair#toList()} is RAG advisor
     *   first, guard second, matching {@code RagAdvisorAssemblyTest}'s own production-mirroring
     *   order — but per {@code RagEmptyContextGuardAdvisor}'s own [Caution], {@code
     *   DefaultAroundAdvisorChain.Builder.reOrder()} re-sorts by {@code getOrder()} regardless of
     *   registration order, so {@code RagAdvisorConfig#RAG_ADVISOR_ORDER}/{@code
     *   RagEmptyContextGuardAdvisor#ORDER} are what actually fixes execution order, not the list
     *   order.
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
     *   in sync when either changes. <b>Update (hybrid-rag T10, landed 2026-08-30):</b> when
     *   {@code conversation.isRagEnabled()} is {@code true}, {@link RetrievalAugmentationAdvisor}
     *   injects retrieved document text into the same prompt this note already describes as
     *   unbounded on the output side — every retrieved chunk's content adds further to
     *   prompt-side cost on top of the 211x projection above, which was derived from a
     *   RAG-disabled baseline. No new defense is added by this card; the existing {@code
     *   app.search.top-k} ceiling on chunk count is the only bound already in place, and whether
     *   that is sufficient is not evaluated here. <b>Two further RAG-specific notes added T10
     *   rework round 1 (2026-08-30, review Block performance Medium-1/Medium-2), documentation only, no
     *   code change:</b> (1) <i>RAG context size is a separate amplification axis from the 211x
     *   history projection above.</i> The context text injected per request is bounded by {@code
     *   app.search.top-k} (default 5) multiplied by {@code TokenTextSplitter}'s chunk size (default
     *   2000 characters) — roughly 10,000 characters of context on top of every prompt this note's
     *   211x figure already covers, once RAG is enabled. This is a setting-derived estimate, not a
     *   measurement, and it compounds with (does not replace) the history-based projection: a full
     *   40-row memory window plus a full 5-chunk RAG context are two independent additions to the
     *   same prompt. (2) <i>RAG adds a first-token latency axis this codebase has not had before.</i>
     *   A RAG-enabled request runs query compression, embedding, and (when {@code rerank.enabled})
     *   reranking sequentially, all before the streaming call to the answer model even starts — so
     *   time-to-first-token is no longer bounded only by the answer model's own latency. {@code
     *   rerank.read-timeout} alone (10s, {@code application.yaml}) can add up to 10 seconds of
     *   pre-stream delay by itself if the rerank call is slow, on top of whatever compression and
     *   embedding already took. This card is the first to expose that latency chain to a real user
     *   — {@code RagAdvisorAssemblyTest}/{@code ChatServiceRagAttachmentTest} exercise the pipeline
     *   with {@code StubChatModel} (zero real latency), so this risk is not evidenced by either
     *   test suite; no AC or defense for it exists yet.
     * [Caution] <b>Overload rationale (BL-024, 2026-09-01):</b> {@code eval-usage-dashboard:AC-20b}'s positive control needs a real (non-stub)
     *   OpenRouter call that actually returns {@code finish_reason=length}, through this exact
     *   production path (persistence, {@link ChatChunk#normalizeFinishReason}) rather than a
     *   hand-rolled call elsewhere — BL-024, 2026-09-01 user approval. <b>Correction (review round
     *   1, correctness Low-1):</b> this paragraph previously also named "RAG advisor attachment" as
     *   part of that exact path, but {@code EvalSweepIntegrationTest.PositiveControls} runs with
     *   {@code ragEnabled=false} (the control targets output-length truncation, not retrieval) —
     *   RAG attachment is not exercised by that control and was never required for it to be valid.
     *   This is an overload, not a default parameter (Java has none), so
     *   the pre-existing 3-arg method above (~11 call sites: {@code ConversationController} plus
     *   test fixtures) recompiles unchanged and keeps passing {@code null} here, which sets no
     *   {@code maxTokens} at all — byte-for-byte the same {@link OpenAiChatOptions} this method
     *   built before this overload existed.
     *   <b>Scope of the override:</b> This parameter is for the eval sweep's one-off positive-control call only. Do not
     *   wire it into {@code ConversationController} or any other production caller — introducing a
     *   real {@code maxTokens} default on the normal chat path is exactly the {@code BL-004}
     *   decision this class's [Rationale] javadoc (above) says is still open (truncating output
     *   produces {@code finish_reason=length} with no truncation indicator shown to the user).
     *   Making that call here, implicitly, by wiring this override into a production caller would
     *   pre-empt that decision without it actually being made.
     * [AC] chat:AC-30, chat:AC-34, hybrid-rag:AC-5, hybrid-rag:AC-9, hybrid-rag:AC-10,
     *   hybrid-rag:AC-11, hybrid-rag:AC-12, hybrid-rag:AC-14,
     *   eval-usage-dashboard:AC-20b
     */
    public Flux<ChatChunk> streamReply(Conversation conversation, String requestedModel, String userMessage,
            Integer maxTokensOverride) {
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
        AtomicReference<List<ChatChunk.Source>> sourcesHolder = new AtomicReference<>();
        AtomicBoolean assistantMessageRecorded = new AtomicBoolean(false);
        // hybrid-rag T11: read once here, not per-response inside toChunk -- Conversation's own
        // ragEnabled flag cannot change mid-stream, and this is the exact same call already made
        // just below to decide whether to attach the advisor pair at all.
        boolean ragEnabled = conversation.isRagEnabled();

        // eval-usage-dashboard:AC-20b (BL-024): applied only when the eval sweep's positive-control
        // call passes a non-null override -- every production caller passes null via the 3-arg
        // overload above, so .maxTokens() is never called on this builder outside that one case.
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder().model(model)
                // extraBody is OpenRouter's own extension, not a standard OpenAI request field --
                // S1 spike (state.md, 2026-08-25) confirmed the provider only attaches its `cost`
                // field to the response's usage payload when this flag is set per request; the
                // standard `include_usage` option (already defaulted on for streaming by
                // OpenAiChatModel.createRequest()) gets prompt/completion token counts without it,
                // but never `cost` (chat:AC-32, chat:AC-33).
                .extraBody(Map.of("usage", Map.of("include", true)));
        if (maxTokensOverride != null) {
            optionsBuilder = optionsBuilder.maxTokens(maxTokensOverride);
        }
        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt()
                .messages(history)
                .options(optionsBuilder);

        // hybrid-rag T10 (AC-9 clause①, AC-10, AC-14 final): attach the RAG advisor pair only for a
        // conversation that opted in -- .advisors(...) is skipped entirely (not called with an
        // empty list) when ragEnabled is false, so HybridDocumentRetriever's two search ports are
        // never invoked (AC-10). See this method's own [Rationale] for why this is per-request
        // rather than a ChatClient.Builder-level default advisor, and why ragAdvisorPair is a
        // dedicated record rather than two separate fields or a bare List<Advisor> (T10 rework
        // rounds 1-2, security Medium-2).
        if (ragEnabled) {
            requestSpec = requestSpec.advisors(ragAdvisorPair.toList());
        }

        Flux<ChatChunk> chunks = requestSpec
                .stream()
                .chatResponse()
                .map(response -> toChunk(response, conversationIdText, contentBuilder, finishReasonHolder,
                        usageHolder, sourcesHolder, ragEnabled));

        return chunks
                .doOnComplete(() -> onStreamTerminated(SignalType.ON_COMPLETE, assistantMessageRecorded,
                        conversationId, model, contentBuilder, finishReasonHolder, usageHolder, sourcesHolder))
                .doOnCancel(() -> onStreamTerminated(SignalType.CANCEL, assistantMessageRecorded,
                        conversationId, model, contentBuilder, finishReasonHolder, usageHolder, sourcesHolder))
                .onErrorResume(error -> {
                    log.error("스트리밍 중 오류 발생 (conversationId={})", conversationId, error);
                    onStreamTerminated(SignalType.ON_ERROR, assistantMessageRecorded, conversationId,
                            model, contentBuilder, finishReasonHolder, usageHolder, sourcesHolder);
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
     *   carries a non-null finish reason, normalizes it via {@link
     *   ChatChunk#normalizeFinishReason} (BL-003, 2026-08-30 user decision) and stores the
     *   normalized value in {@code finishReasonHolder} — {@link #streamReply(Conversation, String, String, Integer)}'s termination hooks
     *   (via {@link #onStreamTerminated}) read both once the stream ends. Likewise, when this
     *   response's {@code usage} is non-zero, extracts it via {@link #extractUsage} and stores it
     *   in {@code usageHolder} (T11) — per the S1 spike (state.md, 2026-08-25), every non-terminal
     *   delta chunk carries a zeroed {@code usage} (prompt=0/completion=0/total=0), and the real
     *   token/cost summary lands only on the stream's last {@link ChatResponse}, so {@code
     *   usageHolder} ends up holding exactly that one response's extraction once the stream
     *   completes.
     * [Caution] A {@code null} finish reason from {@code result.getMetadata()} is left {@code
     *   null} here, never folded to {@link ChatChunk#FINISH_REASON_UNKNOWN} — every non-terminal
     *   {@link ChatResponse} in a stream legitimately has no finish reason, and folding it here
     *   would put a finishReason on every mid-stream chunk, breaking chat:AC-28. Only {@link
     *   #onStreamTerminated} may turn a still-{@code null} {@code finishReasonHolder} into {@link
     *   ChatChunk#FINISH_REASON_UNKNOWN}, once the whole stream is known to have ended. <b>hybrid-rag
     *   T11 addition:</b> {@code sources} is resolved (via {@link #resolveSources}) inside the same
     *   {@code finishReason != null} branch that already identifies the terminal response — reusing
     *   that branch, rather than {@code usage}'s own separate {@code totalTokens > 0} guard, is
     *   deliberate: {@link RetrievalAugmentationAdvisor#after} (spring-ai-rag 2.0.1 source, read
     *   directly) only ever writes {@code DOCUMENT_CONTEXT} onto the one {@link ChatResponse} for
     *   which {@code org.springframework.ai.chat.client.advisor.AdvisorUtils#onFinishReason()}
     *   returns {@code true} inside {@code BaseAdvisor#adviseStream} (spring-ai-client-chat 2.0.1
     *   source, read directly) — i.e. the same response that carries a non-{@code null} finish
     *   reason. A response's own {@code usage} can independently be zero on that exact response in
     *   principle (a real, if unobserved, edge case), so piggy-backing on {@code usage}'s guard
     *   instead would risk silently skipping {@code sources} resolution on the one response that
     *   actually carries it. <b>BL-019 investigation (dev-backend-engineer, 2026-08-30, T11 card):</b>
     *   an attempt was made to decouple {@code sourcesHolder}'s update from this same {@code
     *   finishReason != null} gate — i.e. update it whenever {@code DOCUMENT_CONTEXT} metadata is
     *   present on {@code response}, regardless of {@code finishReason} — specifically to give
     *   {@link #onStreamTerminated}'s persisted {@code messages.sources} a value even on the BL-009
     *   shape (terminal {@code finishReason} unresolved). Confirmed, by both reading {@code
     *   BaseAdvisor#adviseStream} (spring-ai-client-chat-2.0.1-sources.jar) and by an empirical
     *   debug run of the reproducing fixture, that this is a no-op with this project's pinned
     *   {@code spring-ai-rag}/{@code spring-ai-client-chat} 2.0.1: {@code
     *   RetrievalAugmentationAdvisor#after} — the only code that ever writes {@code
     *   DOCUMENT_CONTEXT} onto a {@link ChatResponse}'s own metadata — is itself gated by {@code
     *   AdvisorUtils.onFinishReason()}, which reads the exact same raw {@code
     *   result.getMetadata().getFinishReason()} this method reads. So "{@code DOCUMENT_CONTEXT}
     *   present" is logically a strict subset of "{@code finishReason != null}" for every {@link
     *   ChatResponse} this project's advisor chain ever produces — on the BL-009 shape, {@code
     *   DOCUMENT_CONTEXT} is not merely unread, it is never attached to any response in the stream
     *   at all, confirmed by instrumenting this exact branch and observing {@code
     *   response.getMetadata().get(DOCUMENT_CONTEXT) == null} on every chunk of {@code
     *   ChatServiceRagAttachmentTest#losesSourcesOnBothAxesWhenTerminalResponseFinishReasonIsUnrecognizedByTheSdk}'s
     *   fixture, terminal chunk included. Closing {@code messages.sources} for that exact
     *   interaction (tracked separately as {@code BL-019}, per coordinator instruction not to
     *   conflate it with {@code BL-009}'s own, already-approved scope) would require reading {@code
     *   RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT} off {@code ChatClientResponse.context()}
     *   instead (populated unconditionally by {@code RetrievalAugmentationAdvisor#before}, not
     *   gated by {@code onFinishReason()}) — which means subscribing to {@code
     *   .stream().chatClientResponse()} instead of today's {@code .stream().chatResponse()} in
     *   {@link #streamReply(Conversation, String, String, Integer)}, a wider API-surface change this bugfix card's scope did not cover and
     *   whose context-propagation-through-the-full-chain behavior is unverified in this codebase.
     *   {@code BL-019} therefore remains open on both the wire and storage axes as of this
     *   investigation; not resolved by this method.
     * [AC] chat:AC-25, chat:AC-26, chat:AC-27, chat:AC-28, chat:AC-32, chat:AC-33, hybrid-rag:AC-11,
     *   hybrid-rag:AC-12
     */
    private ChatChunk toChunk(ChatResponse response, String conversationId, StringBuilder contentBuilder,
            AtomicReference<String> finishReasonHolder, AtomicReference<ChatChunk.Usage> usageHolder,
            AtomicReference<List<ChatChunk.Source>> sourcesHolder, boolean ragEnabled) {
        Generation result = response.getResult();
        String text = result.getOutput().getText();
        if (text == null) {
            text = "";
        }
        contentBuilder.append(text);

        String finishReason = ChatChunk.normalizeFinishReason(result.getMetadata().getFinishReason());
        List<ChatChunk.Source> sources = null;
        if (finishReason != null) {
            finishReasonHolder.set(finishReason);
            sources = resolveSources(response, ragEnabled, conversationId);
            // D7/T1 (2026-09-01): when the model answered with exactly the fixed "not found"
            // sentence (the prompt's instruction for context that does not contain the answer),
            // the retrieved chunks are not evidence for anything -- drop them, so this answer
            // carries the same `sources: []` shape as the zero-document guard path (hybrid-rag
            // AC-5 clause ③, D5 "근거가 있는 척 하지 않는다"). contentBuilder holds the complete
            // answer here: every delta chunk precedes the finish-reason-bearing terminal one.
            // Done here, not in resolveSources, so wire and messages.sources stay identical.
            if (!sources.isEmpty()
                    && RagEmptyContextGuardAdvisor.isNotFoundAnswer(contentBuilder.toString())) {
                log.debug("모델이 고정 거절 문구로 답해 sources {}건을 비웁니다 (conversationId={})",
                        sources.size(), conversationId);
                sources = List.of();
            }
            sourcesHolder.set(sources);
        }

        ChatChunk.Usage usage = null;
        Usage responseUsage = response.getMetadata().getUsage();
        if (responseUsage != null && responseUsage.getTotalTokens() > 0) {
            usage = extractUsage(responseUsage);
            usageHolder.set(usage);
        }

        return new ChatChunk(conversationId, text.isEmpty() ? null : text, finishReason, usage, sources);
    }

    /**
     * [Purpose] Resolves the {@code sources} list for the one terminal {@link ChatResponse} of a
     *   stream (hybrid-rag Spec AC-11) — always a non-{@code null} list, never {@code null} itself
     *   (hybrid-rag:AC-5 final, AC-10 clause②, AC-14 clause② all require an explicit empty array,
     *   not an absent field, in their respective zero-citation cases).
     * [Rationale] {@code !ragEnabled} short-circuits to {@code List.of()} before ever touching
     *   {@code response.getMetadata()} — {@code RetrievalAugmentationAdvisor} is never attached to
     *   this request at all when RAG is disabled (see {@link #streamReply(Conversation, String, String, Integer)}'s own [Rationale]), so
     *   no {@code DOCUMENT_CONTEXT} key can exist on this response either way (hybrid-rag:AC-10
     *   clause②). When RAG is enabled, {@link RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT} is read
     *   directly off {@code response.getMetadata()} — confirmed by reading {@code
     *   RetrievalAugmentationAdvisor.after()} (spring-ai-rag 2.0.1 source): it copies {@code
     *   context.get(DOCUMENT_CONTEXT)} (a {@code List<Document>}, populated by {@code before()}
     *   even when empty — hybrid-rag:AC-5's zero-document case included) onto the returned {@code
     *   ChatResponse}'s own metadata via {@code ChatResponse.Builder.metadata(String, Object)},
     *   which is exactly the map {@link org.springframework.ai.model.AbstractResponseMetadata#get}
     *   reads from — not assumed, read directly against this project's pinned {@code
     *   spring-ai-rag-2.0.1-sources.jar}/{@code spring-ai-model-2.0.1-sources.jar}. This holds
     *   whether or not {@link art.insightai.chatbot.rag.retrieval.RagEmptyContextGuardAdvisor}
     *   short-circuited the model call: that class's own Javadoc documents forwarding {@code
     *   requestContext} unchanged into its synthesized response specifically so {@code
     *   RetrievalAugmentationAdvisor#after} still runs its own {@code DOCUMENT_CONTEXT} copy
     *   regardless. Each {@link Document} this method receives is converted to this project's own
     *   {@link RetrievedChunk} shape via {@link #toRetrievedChunk} — the same intermediate type
     *   {@link SourceRefMapper} already accepts — rather than reimplementing {@code
     *   document_id}/{@code filename}/{@code page} extraction here; {@link
     *   art.insightai.chatbot.rag.retrieval.SpringAiDocumentRetrieverAdapter}'s own Javadoc
     *   confirms that adapter already copies {@link RetrievedChunk#score()} onto {@link
     *   Document#getScore()} verbatim (the RRF fusion score, untouched by reranking — Tech Review
     *   D-T6), so no rerank-vs-fusion ambiguity is introduced by this round trip.
     * [Caution] Never lets one bad element fail the whole turn (this project's established
     *   fallback philosophy, D4/D5; {@code HybridDocumentRetriever#retrieve}'s own axis-failure
     *   fallback is the precedent) — the "metadata missing/wrong shape" branch (no {@code
     *   DOCUMENT_CONTEXT} at all) still falls back to {@code List.of()} for the whole call, since
     *   there is nothing to salvage. But per-{@link Document} failures are tolerated individually,
     *   not list-wide (correctness+security review, 2026-08-30, T11 rework round 2 — a round-1
     *   regression: an earlier version of this method wrapped {@link SourceRefMapper#toSourceRefs}
     *   in one {@code try/catch} around the whole stream, so a single malformed chunk's {@code
     *   document_id}/{@code filename}/{@code page} — {@link SourceRefMapper} throws {@code
     *   IllegalStateException} per chunk for those — silently discarded every other,
     *   successfully-mapped source in the same response; under hybrid-rag:AC-20's pending FE
     *   contract that would render "no source documents" even when most sources were valid). The
     *   loop below therefore calls {@link SourceRefMapper#toSourceRef} (singular) once per {@link
     *   Document}, inside its own {@code try/catch}, so one bad element is dropped without
     *   discarding the rest — mirroring the {@code Document.class::isInstance} filter's own
     *   per-element tolerance immediately above it, rather than introducing a second, inconsistent
     *   failure-handling shape in the same method. Both the type-filter drop count and the mapping
     *   failure count log a {@code warn} whenever either is greater than zero, so this
     *   otherwise-invisible partial data loss is at least observable in logs; only when literally
     *   every element in {@code documents} fails to map does this method's return value collapse to
     *   an empty list, same as the true "nothing to report" cases (hybrid-rag:AC-5/AC-10 clause②/
     *   AC-14 clause②) — that empty result is not itself distinguished from those cases, since
     *   {@code sources: []} already carries no signal about *why* it is empty on the wire.
     * [AC] hybrid-rag:AC-5, hybrid-rag:AC-10, hybrid-rag:AC-11, hybrid-rag:AC-12, hybrid-rag:AC-14
     */
    private List<ChatChunk.Source> resolveSources(ChatResponse response, boolean ragEnabled, String conversationId) {
        if (!ragEnabled) {
            return List.of();
        }
        Object rawDocumentContext = response.getMetadata().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(rawDocumentContext instanceof List<?> rawDocuments)) {
            log.warn("RAG 활성 대화의 마지막 응답에 DOCUMENT_CONTEXT 메타데이터가 없어 sources 를 빈 배열로 "
                    + "대체합니다 (conversationId={})", conversationId);
            return List.of();
        }
        List<Document> documents = rawDocuments.stream()
                .filter(Document.class::isInstance)
                .map(Document.class::cast)
                .toList();
        if (documents.size() < rawDocuments.size()) {
            log.warn("DOCUMENT_CONTEXT 내 {}개 원소가 Document 타입이 아니어서 sources 매핑에서 제외합니다 "
                    + "(conversationId={})", rawDocuments.size() - documents.size(), conversationId);
        }
        // Per-element tolerance (correctness+security review, 2026-08-30, T11 rework round 2): map
        // one Document at a time so a single malformed chunk's IllegalStateException (missing
        // document_id/filename, bad page shape -- see SourceRefMapper's own [Caution]) only drops
        // that one candidate, never the whole response's sources. Do NOT revert to
        // sourceRefMapper.toSourceRefs(chunks) (list-in/list-out) wrapped in one try/catch -- that
        // is exactly the regression this loop fixes (see this method's own [Caution]).
        List<ChatChunk.Source> sources = new ArrayList<>(documents.size());
        // One citation per (documentId, page), not per chunk (2026-09-01, user decision "A + C"):
        // top-k retrieval routinely returns several chunks of the same page of the same file, and
        // each used to become its own, visually identical Source ("출처 5건" for one document).
        // Retrieval order is rank order, so keeping the *first* occurrence keeps the best-ranked
        // chunk's score/snippet for that citation. A null page is a real key of its own (non-PDF
        // formats, ChunkMetadataBuilder) -- every chunk of such a document collapses into one
        // citation, which is exactly what the user sees anyway since there is no page to tell them
        // apart by. This is the only place the wire/persisted `sources` list is assembled (hybrid-
        // rag:AC-11/AC-12), so the SSE payload and messages.sources stay identical.
        Set<SourceCitationKey> seen = new HashSet<>();
        int mappingFailures = 0;
        for (Document document : documents) {
            try {
                ChatChunk.Source source = toChunkSource(sourceRefMapper.toSourceRef(toRetrievedChunk(document)));
                if (seen.add(new SourceCitationKey(source.documentId(), source.page()))) {
                    sources.add(source);
                }
            } catch (RuntimeException e) {
                mappingFailures++;
                log.debug("단일 청크의 sources 매핑 실패, 해당 원소만 제외합니다 (conversationId={})", conversationId, e);
            }
        }
        if (mappingFailures > 0) {
            log.warn("DOCUMENT_CONTEXT 내 {}개 청크가 sources 매핑에 실패하여 해당 원소만 제외합니다 "
                    + "(conversationId={})", mappingFailures, conversationId);
        }
        return List.copyOf(sources);
    }

    /**
     * [Purpose] Identity of one citation for {@link #resolveSources}'s de-duplication: the backing
     *   document plus the page within it ({@code null} page = "the whole file", for formats whose
     *   parser yields no page boundary).
     * [Rationale] A record so equality/hashing is by value; kept private to this class because the
     *   rule "one citation per document page" is a presentation decision of the wire/persisted
     *   {@code sources} list (hybrid-rag:AC-12), not a property of {@link SourceRef} (which stays
     *   one-per-chunk for the rag package's own consumers, e.g. the eval judge). Declared directly
     *   below its only consumer (batch review 2026-09-01 Medium-4 -- an earlier position between
     *   {@code toRetrievedChunk}'s Javadoc and that method left that Javadoc orphaned).
     */
    private record SourceCitationKey(String documentId, Integer page) {
    }

    /**
     * [Purpose] Converts one Spring AI {@link Document} (as left in {@code DOCUMENT_CONTEXT} by
     *   {@link RetrievalAugmentationAdvisor}) into this project's axis-agnostic {@link
     *   RetrievedChunk} shape, so {@link SourceRefMapper} — already unit-tested against that type
     *   — can map it onward.
     * [Caution] {@link Document#getScore()} is {@code @Nullable}; this project's own {@link
     *   art.insightai.chatbot.rag.retrieval.SpringAiDocumentRetrieverAdapter} always sets it (never
     *   leaves it unset) when it builds the {@link Document} in the first place, so a {@code null}
     *   here is not expected in practice — the {@code 0.0} fallback below only guards against a
     *   theoretical future {@code DocumentPostProcessor}/{@code QueryAugmenter} substituting a
     *   {@link Document} that never went through that adapter.
     */
    private static RetrievedChunk toRetrievedChunk(Document document) {
        Double score = document.getScore();
        return new RetrievedChunk(
                document.getId(), document.getText(), document.getMetadata(), score != null ? score : 0.0);
    }

    /**
     * [Purpose] Copies one {@link SourceRef} field-by-field into this package's own {@link
     *   ChatChunk.Source} — the one place that crosses from the {@code rag} domain type to the
     *   {@code chat} wire/persistence type (T11 card brief's agreed design constraint: {@code
     *   ChatChunk} must never reference {@link SourceRef} directly).
     * [Caution] {@code documentId} is converted from {@link SourceRef}'s {@link java.util.UUID} to
     *   a plain {@link String} here — {@link ChatChunk.Source#documentId()}'s own Javadoc explains
     *   why the wire/persistence shape is a string, not a UUID.
     */
    private static ChatChunk.Source toChunkSource(SourceRef ref) {
        return new ChatChunk.Source(ref.documentId().toString(), ref.filename(), ref.score(), ref.snippet(),
                ref.page());
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
     *   end up holding (they read the same {@code usageHolder} entry — chat:AC-33's "response
     *   value = DB row value" invariant holds by construction, not by coincidence).
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
     * [Rationale] Runs before the {@link Flux} in {@link #streamReply(Conversation, String, String, Integer)} is even built, so this row
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
     * [Purpose] Shared endpoint for {@link #streamReply(Conversation, String, String, Integer)}'s three termination hooks ({@code
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
     *   finishReasonHolder} — the real, already-lower-cased {@code stop}/{@code length}/etc. value
     *   the LLM reported (BL-003, 2026-08-30 user decision) — or, if the SDK never supplied a
     *   usable finish reason on any response in the stream (still {@code null} here), {@link
     *   ChatChunk#FINISH_REASON_UNKNOWN}. This is the one place that fallback is allowed to
     *   happen: unlike {@link #toChunk}, this method only runs once the whole stream has reached a
     *   terminal signal, so there is no mid-stream chunk left to accidentally give a finishReason
     *   to.
     * [Side effects] Calls {@link #recordAssistantMessage} at most once per invocation of this
     *   method's owning {@link Flux} subscription — {@code recorded.compareAndSet(false, true)}
     *   returning {@code false} means some other terminal path already recorded this turn, so this
     *   call returns without doing anything further. This guard is what makes "exactly once" hold
     *   across three independent call sites rather than operator choice alone: e.g. if the {@code
     *   doOnComplete} hook's callback itself threw, {@code FluxPeek} (which backs {@code
     *   doOnComplete}) routes that exception into a new {@code onError} signal reaching the
     *   downstream {@code onErrorResume}, which would then also call this method — without the
     *   guard, chat:AC-29's guarantee of exactly one persisted assistant row would break in that
     *   edge case.
     * [Caution] The {@link ChatChunk#FINISH_REASON_UNKNOWN} fallback above writes only the
     *   persisted {@code messages.finish_reason} column — it does not touch the wire. By the time
     *   this method runs (called from {@code doOnComplete}/{@code doOnCancel}/{@code
     *   onErrorResume}, all reached only after the {@link Flux} has already finished emitting
     *   elements downstream), {@link #toChunk} has already sent the stream's last {@link
     *   ChatChunk} to the client; this method is side-effect only and has no way to revise an
     *   element already emitted. So on the path where the SDK never supplies a usable finish
     *   reason at all — confirmed by reading {@code spring-ai-openai-2.0.1-sources.jar}: {@code
     *   OpenAiChatModel.java:393-396} maps an unmappable/absent value to {@code null} rather than a
     *   name (not the {@code _UNKNOWN} enum name), and {@code OpenAiChatModel.java:1278-1281}'s
     *   streaming default {@code FinishReason.of("")} resolves through that same {@code null} path
     *   — the client's actual last wire chunk still carries {@code finishReason: null}; this
     *   method's fallback never reaches it. That wire-axis gap is open, out of this card's scope,
     *   and tracked as {@code BL-009} (2026-08-30 user decision), not resolved by this method.
     *   Compare {@link ChatChunk#FINISH_REASON_CANCELLED}'s own [Purpose], which documents the same
     *   storage-only scope for that value ("Only the persisted {@code messages} row uses it"). The
     *   {@code [AC] chat:AC-28} tag below is therefore satisfied on the storage axis only.
     *   <b>hybrid-rag T11 (BL-019, added 2026-08-30, coordinator correction — insight-chat-7f):</b>
     *   {@code sources} shares the same structural gap as {@code finishReason} above, but this is
     *   tracked as a <i>separate</i> item, {@code BL-019} — the 2026-08-30 user approval that
     *   accepted {@code BL-009} covered {@code finishReason} only and does not extend to {@code
     *   sources} by itself (global CLAUDE.md §6.1: an approval does not extend to outcomes it did
     *   not address — "승인이 다루지 않은 결과에 확장 적용하지 않는다"(quoted verbatim)).
     *   {@code BL-019} is filed and awaiting separate user approval; do not describe the {@code
     *   sources} gap as already accepted "per BL-009 precedent". <b>Unlike {@code finishReason},
     *   this method has no fallback to offer {@code sources} either</b> — an investigation into
     *   decoupling {@code sourcesHolder}'s update from {@code finishReason} (see {@link
     *   #toChunk}'s own {@code [Caution]}, "BL-019 investigation") found that, with this project's
     *   pinned {@code spring-ai-rag}/{@code spring-ai-client-chat} 2.0.1, {@code DOCUMENT_CONTEXT}
     *   is only ever attached to a {@link ChatResponse}'s metadata by {@code
     *   RetrievalAugmentationAdvisor#after}, which is itself gated by the exact same {@code
     *   finishReason}-recognition signal ({@code AdvisorUtils.onFinishReason()}) — so on the BL-009
     *   shape, {@code sourcesHolder} never receives an update at all, on any response in the stream,
     *   and stays at its initial {@code null}. {@code messages.sources} is therefore lost on this
     *   path exactly like {@code messages.finish_reason} would be without this method's own
     *   fallback above — except {@code sources} has no equivalent fallback value to substitute (an
     *   explicit empty array here would misrepresent "documents were found but not attributable" as
     *   "zero documents found", which is a distinct, already-covered case — hybrid-rag:AC-5/AC-10
     *   clause②/AC-14 clause②). {@code BL-019} therefore remains open on <i>both</i> the wire and storage
     *   axes; not resolved by this method or by {@link #toChunk}. <b>Additional fact for {@code
     *   T15}/frontend to account for regardless of how {@code BL-019} is eventually closed:</b>
     *   {@code finishReason} and {@code sources} are populated on the exact same terminal {@link
     *   ChatResponse} by the underlying library ({@link
     *   art.insightai.chatbot.rag.retrieval.RagEmptyContextGuardAdvisor}'s {@code FINISH_REASON}
     *   field Javadoc documents this coupling from the advisor side) — so when this gap fires, the
     *   wire's last chunk loses both fields <i>together</i>, on the same chunk. A client cannot
     *   treat "this chunk carries no {@code finishReason}" as a reliable proxy for "more chunks are
     *   still coming", because that exact case is indistinguishable, per chunk, from "this is the
     *   last chunk and both fields were lost to this gap". Chunk content alone cannot determine
     *   stream end in that case; only the SSE transport's own end-of-stream signal (connection
     *   close) can.
     * [AC] chat:AC-28
     */
    private void onStreamTerminated(SignalType signalType, AtomicBoolean recorded, UUID conversationId,
            String model, StringBuilder contentBuilder, AtomicReference<String> finishReasonHolder,
            AtomicReference<ChatChunk.Usage> usageHolder, AtomicReference<List<ChatChunk.Source>> sourcesHolder) {
        if (!recorded.compareAndSet(false, true)) {
            return;
        }
        String finishReason = switch (signalType) {
            case CANCEL -> ChatChunk.FINISH_REASON_CANCELLED;
            case ON_ERROR -> ChatChunk.FINISH_REASON_ERROR;
            default -> {
                String observed = finishReasonHolder.get();
                yield observed != null ? observed : ChatChunk.FINISH_REASON_UNKNOWN;
            }
        };
        recordAssistantMessage(conversationId, model, contentBuilder.toString(), finishReason, usageHolder.get(),
                sourcesHolder.get());
    }

    /**
     * [Purpose] Inserts the 'assistant' {@code messages} row once the stream reaches a terminal
     *   signal — normal completion, error, or cancellation alike (chat Spec §7 OQ-6 requires it to
     *   be persisted — "저장한다"(quoted verbatim) —
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
     *   keep {@code null} token/cost columns exactly as before this card. <b>hybrid-rag T11
     *   addition:</b> {@code sources} (hybrid-rag:AC-12 write side) is whatever {@link #toChunk} last
     *   stored in {@code sourcesHolder} — the same {@link ChatChunk.Source} list the client's own
     *   last-chunk {@code sources} field carries, serialized to JSON text by {@link
     *   #serializeSources} — mirroring {@code usage}'s "response == DB" construction exactly.
     *   {@code sources} stays {@code null} (not {@code "[]"}) on the error/cancel paths whenever no
     *   chunk ever reached the terminal, {@code DOCUMENT_CONTEXT}-bearing response before the
     *   stream terminated — the same "nothing to report yet" reasoning {@code usage} already
     *   documents above, not a regression. Delegates the actual persistence to {@link
     *   ConversationService#persistAssistantTurn} — see class [Rationale] for why this class never
     *   calls a repository directly for this.
     * [AC] chat:AC-29, chat:AC-32, chat:AC-33, hybrid-rag:AC-12
     */
    private void recordAssistantMessage(UUID conversationId, String model, String content, String finishReason,
            ChatChunk.Usage usage, List<ChatChunk.Source> sources) {
        Integer promptTokens = usage != null ? usage.promptTokens() : null;
        Integer completionTokens = usage != null ? usage.completionTokens() : null;
        BigDecimal costUsd = usage != null ? usage.cost() : null;
        Message message = new Message(conversationId, Message.ROLE_ASSISTANT, content, model,
                promptTokens, completionTokens, costUsd, serializeSources(sources), finishReason);
        conversationService.persistAssistantTurn(message);
    }

    /**
     * [Purpose] Serializes {@code sources} to the JSON text {@code messages.sources} stores
     *   (hybrid-rag:AC-12 write side), or returns {@code null} unchanged when {@code sources} itself is
     *   {@code null} (see {@link #recordAssistantMessage}'s own [Rationale] for when that happens).
     * [Caution] Never lets a serialization failure fail the whole turn (this project's established
     *   fallback philosophy, D4/D5) — {@link ChatChunk.Source} carries only {@link String}/{@code
     *   double}/{@link Integer} fields, so a real failure here is not expected in practice, but the
     *   {@code catch} exists for the same reason {@link #resolveSources}'s own {@code catch} does:
     *   a JSON-encoding defect must not turn an otherwise-successful, already-billed response into
     *   a lost {@code messages} row.
     * [AC] hybrid-rag:AC-12
     */
    private String serializeSources(List<ChatChunk.Source> sources) {
        if (sources == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(sources);
        } catch (RuntimeException e) {
            log.error("sources 직렬화 실패, messages.sources 를 null 로 저장합니다", e);
            return null;
        }
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
