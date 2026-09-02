package art.insightai.chatbot.rag.retrieval;

import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

import reactor.core.publisher.Flux;

/**
 * [Purpose] hybrid-rag AC-5's "모른다" short-circuit: when {@link RetrievalAugmentationAdvisor}
 *   (which must run immediately before this advisor in the chain -- see {@link
 *   RagAdvisorConfig}) retrieved zero documents, this advisor returns a fixed "찾을 수
 *   없습니다" answer and never invokes the rest of the advisor chain -- i.e. the actual chat
 *   model is never called for that request.
 * [Rationale] See {@link RagAdvisorConfig}'s class Javadoc for the full (a)/(b) design-decision
 *   writeup mandated by the T9 card brief. Summary: {@code RetrievalAugmentationAdvisor}'s own
 *   {@code adviseCall}/{@code adviseStream} (inherited {@code BaseAdvisor} defaults) always
 *   proceed to {@code chain.next*()} after {@code before()} -- there is no library hook to make
 *   it skip the model call itself, and the class is {@code final} so it cannot be subclassed to
 *   add one. The skip therefore has to live in a sibling advisor positioned immediately after it,
 *   which is what this class is.
 * [Caution] Deliberately does *not* implement {@code BaseAdvisor}: that interface's
 *   {@code adviseCall}/{@code adviseStream} defaults unconditionally call {@code chain.next*()}
 *   after {@code before()}, which is exactly the behavior this class must avoid. Implementing
 *   {@link CallAdvisor}/{@link StreamAdvisor} directly is what makes the early return (skipping
 *   {@code chain.nextCall}/{@code nextStream} entirely) possible. This advisor's {@link
 *   #getOrder()} MUST stay numerically greater than {@link RagAdvisorConfig#RAG_ADVISOR_ORDER}
 *   (lower order runs first in this library's convention -- confirmed via {@code Advisor extends
 *   Ordered} and {@code Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER}'s own doc comment, which
 *   places chat-memory advisors at a very low order specifically so they run before RAG-style
 *   advisors). If this advisor ran *before* {@link RetrievalAugmentationAdvisor#before}, {@code
 *   context.get(DOCUMENT_CONTEXT)} would not exist yet and {@link #isEmptyDocumentContext} would
 *   always see a missing key -- silently disabling AC-5 rather than throwing. Symmetrically, if
 *   no {@code DOCUMENT_CONTEXT} key is present at all (e.g. this advisor is attached without
 *   {@link RetrievalAugmentationAdvisor}, which should never happen once hybrid-rag T10 wires
 *   them as a pair), this advisor treats that as "not applicable" and passes the request through
 *   unchanged -- only an explicit, empty {@link List} short-circuits. This class closes AC-5's
 *   clauses (1) ("no document context is injected into the model") and (2) ("the answer contains
 *   the fixed not-found phrase") only. Clause (3) ("{@code sources} is an empty array") is
 *   hybrid-rag:T11's to close: what this class actually observes is {@code
 *   ChatResponse.getMetadata()}'s {@code DOCUMENT_CONTEXT} being an empty list, which is a proxy
 *   for the eventual {@code sources} response field, not that field itself -- the {@code
 *   Document}-to-{@code SourceRef} mapping path ({@link SourceRefMapper}) does not exist for this
 *   advisor's output today (see Tech Review "AC-5 소유권 분할"). Separately, this short-circuit has
 *   an upper-bound side effect for any advisor chained after this one: because {@link #adviseCall}/
 *   {@link #adviseStream} return early instead of calling {@code chain.next*()}, every advisor
 *   whose {@code getOrder()} is numerically greater than {@link #ORDER} has its {@code before()}/
 *   {@code after()} skipped entirely on a zero-document turn -- a future usage-aggregation or
 *   logging advisor positioned after this one would silently miss every "not found" turn unless it
 *   is placed before this advisor in the chain instead.
 * [AC] hybrid-rag:AC-5 (partial — final evidence is hybrid-rag:T11, see Tech Review "AC-5 소유권 분할")
 */
public final class RagEmptyContextGuardAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * [Rationale] Must run strictly after {@link RagAdvisorConfig#RAG_ADVISOR_ORDER} -- see this
     *   class's [Caution]. Derived from that constant (rather than duplicated as a literal) so
     *   the two orders can never independently drift apart.
     */
    public static final int ORDER = RagAdvisorConfig.RAG_ADVISOR_ORDER + 1;

    /**
     * [AC] hybrid-rag:AC-5 (partial — final evidence is hybrid-rag:T11, see Tech Review "AC-5
     *   소유권 분할") -- the exact substring the AC requires the assistant answer to contain when
     *   zero documents pass the score threshold.
     */
    public static final String NOT_FOUND_MESSAGE = "제공된 문서에서 찾을 수 없습니다.";

    /**
     * [Purpose] {@code ChatResponse} metadata key this advisor stamps ({@code Boolean.TRUE}) on
     *   every response it synthesizes, so downstream code can tell "the model was never called"
     *   apart from "the model was called but its usage summary never arrived".
     * [Rationale] Before 2026-09-02 the synthesized response carried no usage at all, so {@code
     *   ChatService} persisted the 'assistant' row with {@code null} token/cost columns -- the
     *   exact shape the admin usage dashboard counts as "비용 측정 실패" ({@code
     *   UsageRepository}'s {@code missing_cost_count}). Six of the seven such rows on the demo DB
     *   turned out to be this path: a genuinely free turn (no OpenRouter request was made) that
     *   the dashboard nevertheless reported as "실제 청구액이 표시 금액보다 클 수 있음". The user chose
     *   (2026-09-02, "A안") to record such turns as 0 tokens / 0 cost instead, leaving {@code
     *   null} to mean only "a paid call whose cost is unknown" (stream cancelled/errored before
     *   the terminal usage chunk, or the provider omitted {@code cost}). A metadata marker, rather
     *   than a zero-valued {@code Usage} on the synthesized response, is used because {@code
     *   ChatService#toChunk} must keep ignoring zero-valued usage: every non-terminal streaming
     *   chunk from a real model carries prompt=0/completion=0 (S1 spike), so a zero {@code Usage}
     *   alone cannot distinguish this guard from a mid-stream delta.
     * [Caution] The marker survives {@link RetrievalAugmentationAdvisor#after} because that method
     *   rebuilds the response via {@code ChatResponse.builder().from(chatResponse)}, which copies
     *   every existing metadata entry (read against spring-ai-model 2.0.1's {@code
     *   ChatResponse.Builder#metadata(ChatResponseMetadata)}). Any future advisor placed between
     *   this one and {@code ChatService} that rebuilds the response without {@code from(...)} would
     *   silently drop it -- and every guard turn would fall back to being counted as "비용 측정 실패".
     */
    public static final String SYNTHESIZED_RESPONSE_METADATA_KEY = "rag_empty_context_guard_synthesized";

    /**
     * [Purpose] True when {@code response} is one this advisor synthesized (see {@link
     *   #SYNTHESIZED_RESPONSE_METADATA_KEY}), i.e. no chat-model call was made for it.
     */
    public static boolean isSynthesizedResponse(ChatResponse response) {
        return Boolean.TRUE.equals(response.getMetadata().get(SYNTHESIZED_RESPONSE_METADATA_KEY));
    }

    /**
     * [Purpose] Reports whether a complete assistant answer is the fixed "not found" sentence and
     *   nothing else -- the shape {@code NonceBoundaryContextAugmenter}'s prompt instructs the model
     *   to produce when the retrieved context does not contain the answer (D7/T1, 2026-09-01).
     * [Rationale] The D7 sweep measured that no axis-native score threshold separates the eval
     *   set's "no answer in the corpus" questions from the answerable ones on a single-document
     *   corpus (both BM25 and cosine distributions overlap -- {@code docs/eval/raw-score-
     *   distribution-2026-09-01.md}), so the zero-document guard above cannot be the only "모른다"
     *   path: the model itself has to say so when it is handed chunks that do not answer the
     *   question. hybrid-rag Spec D5 ("근거가 있는 척 하지 않는다") and AC-5 clause ③ then require the
     *   {@code sources} list to be empty for such an answer -- a refusal accompanied by citations
     *   would present the retrieved chunks as evidence for an answer that was never given. {@code
     *   ChatService} consults this method for the terminal chunk so the wire payload and {@code
     *   messages.sources} both drop the citations in exactly that case.
     * [Caution] Deliberately an <b>equality</b> check (surrounding whitespace and the trailing
     *   period ignored), not {@code contains}: an answer such as "X 는 제공된 문서에서 찾을 수 없습니다.
     *   다만 Y 는 …" does answer something and must keep its citations. The prompt asks for the
     *   sentence "and nothing else", so a compliant model produces exactly this shape; a model that
     *   embeds the sentence in a longer answer simply keeps its sources, which is the safe direction.
     */
    public static boolean isNotFoundAnswer(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.strip();
        if (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1).strip();
        }
        return normalized.equals(NOT_FOUND_MESSAGE.substring(0, NOT_FOUND_MESSAGE.length() - 1));
    }

    /**
     * [Rationale] This response is synthesized by this class, never by a real model call, so
     *   the finish reason is never subject to R-G (the risk that the OpenAI SDK maps an unknown
     *   upstream {@code finish_reason} to {@code null}). A fixed non-blank value here guarantees
     *   {@code AdvisorUtils.onFinishReason()} still triggers {@link
     *   RetrievalAugmentationAdvisor#after} on the streaming path, exactly as it would for a real
     *   model response with a recognized finish reason.
     */
    private static final String FINISH_REASON = "stop";

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * [Purpose] Short-circuits to the fixed AC-5 answer when {@link
     *   RetrievalAugmentationAdvisor#before} populated an empty document list; otherwise defers
     *   unchanged to the rest of the chain (the real model call).
     * [AC] hybrid-rag:AC-5 (partial — final evidence is hybrid-rag:T11, see Tech Review "AC-5 소유권 분할")
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        if (isEmptyDocumentContext(chatClientRequest.context())) {
            return notFoundResponse(chatClientRequest.context());
        }
        return callAdvisorChain.nextCall(chatClientRequest);
    }

    /**
     * [Purpose] Streaming counterpart of {@link #adviseCall}: emits a single synthesized chunk
     *   carrying the fixed AC-5 answer and a non-blank finish reason instead of subscribing to
     *   the rest of the chain at all.
     * [AC] hybrid-rag:AC-5 (partial — final evidence is hybrid-rag:T11, see Tech Review "AC-5 소유권 분할")
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        if (isEmptyDocumentContext(chatClientRequest.context())) {
            return Flux.just(notFoundResponse(chatClientRequest.context()));
        }
        return streamAdvisorChain.nextStream(chatClientRequest);
    }

    /**
     * [Purpose] True only when {@link RetrievalAugmentationAdvisor#DOCUMENT_CONTEXT} is present
     *   in the request context AND is an empty {@link List} -- a missing or non-{@code List}
     *   value is treated as "not applicable", never as zero documents (see class [Caution]).
     */
    private boolean isEmptyDocumentContext(Map<String, Object> context) {
        Object raw = context.get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        return raw instanceof List<?> documents && documents.isEmpty();
    }

    /**
     * [Purpose] Builds the AC-5 canned response: assistant text is exactly {@link
     *   #NOT_FOUND_MESSAGE}, {@code finishReason} is {@link #FINISH_REASON} (non-blank, see that
     *   field's [Rationale]). {@code requestContext} is forwarded unchanged so {@link
     *   RetrievalAugmentationAdvisor#after} still reads {@code DOCUMENT_CONTEXT} (the empty list)
     *   out of it and copies it onto {@code ChatResponse} metadata -- keeping "sources come from
     *   response metadata" the single contract T11 needs to implement, whether or not this guard
     *   fired. The response metadata additionally carries {@link
     *   #SYNTHESIZED_RESPONSE_METADATA_KEY} so {@code ChatService} can record the turn as a
     *   zero-cost one rather than an unknown-cost one.
     */
    private ChatClientResponse notFoundResponse(Map<String, Object> requestContext) {
        ChatGenerationMetadata generationMetadata =
                ChatGenerationMetadata.builder().finishReason(FINISH_REASON).build();
        Generation generation = new Generation(new AssistantMessage(NOT_FOUND_MESSAGE), generationMetadata);
        ChatResponse chatResponse = ChatResponse.builder()
                .generations(List.of(generation))
                .metadata(SYNTHESIZED_RESPONSE_METADATA_KEY, Boolean.TRUE)
                .build();
        return ChatClientResponse.builder()
                .chatResponse(chatResponse)
                .context(requestContext)
                .build();
    }
}
