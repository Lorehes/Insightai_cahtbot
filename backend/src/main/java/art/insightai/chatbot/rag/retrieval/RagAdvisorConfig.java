package art.insightai.chatbot.rag.retrieval;

import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * [Purpose] Assembles the {@link RetrievalAugmentationAdvisor} bean (hybrid-rag Tech Review T9)
 *   around T7's {@link HybridDocumentRetriever} via {@link SpringAiDocumentRetrieverAdapter},
 *   plus its companion {@link RagEmptyContextGuardAdvisor}, which owns AC-5's "모른다"
 *   short-circuit. Neither bean is attached to any {@code ChatClient} here -- attachment is
 *   hybrid-rag T10's scope (Tech Review "어드바이저 등록 순서 계약"); {@code ChatService} is chat
 *   Spec T10's file and out of this card's bounds per this card's own brief.
 * [Rationale] AC-5's text ("임계값을 통과한 문서가 0건이면 LLM 에 문서 컨텍스트를 주입하지 않고, 답변에
 *   «제공된 문서에서 찾을 수 없습니다» 가 포함되며 sources 는 빈 배열이다") is judged against this
 *   Spec's stub-only test environment (§8-D7). Tech Review D-T8 requires the application, not the
 *   LLM, to produce the fixed phrase directly. But the T1 spike's empirical read of {@code
 *   ContextualQueryAugmenter} (this project's chosen {@code QueryAugmenter}, and {@code
 *   RetrievalAugmentationAdvisor}'s own default when none is supplied) found the opposite default
 *   shape: on zero documents it *replaces the query text* with a fixed English template and lets
 *   the model generate a refusal from it -- i.e. it delegates the "모른다" wording to the LLM,
 *   which is exactly what D-T8 rules out.
 *   <p>
 *   This card resolves that conflict as option <b>(b)</b> from its brief -- detect zero documents
 *   outside the advisor's own model call and return the fixed phrase without invoking the model
 *   at all -- rather than option (a) (configuring {@code allowEmptyContext(false)} plus a custom
 *   template and trusting the LLM to reproduce it). (a) was rejected on a testability ground, not
 *   a style preference: {@code StubChatModel} (chat Spec's test double, read-only here) replays
 *   whatever token sequence a test enqueues in advance and never reads the prompt text it
 *   receives (confirmed by reading its source -- {@code call()}/{@code stream()} both call {@code
 *   capturedPrompts.add(prompt)} purely for later inspection, then serve the next queued script
 *   unconditionally). So under (a), a test could only "prove" AC-5 by enqueueing the exact
 *   expected phrase itself, which would pass regardless of whether the augmenter actually
 *   rewrote the query -- a tautology, not a verification (violates AC principle 1, "참·거짓으로
 *   판정 가능한 문장" -- there is no way for the assertion to fail on a real defect). D-T8's own
 *   stated reason ("테스트 LLM 은 스텁이므로 LLM 프롬프트에 맡기면 참·거짓 판정이 불가능해진다") predicted
 *   precisely this, and reading the stub's source confirms it empirically rather than by
 *   assumption.
 *   <p>
 *   Because {@code RetrievalAugmentationAdvisor.adviseCall}/{@code adviseStream} (both inherited
 *   {@code BaseAdvisor} defaults: {@code before() -> chain.next*() -> after()}) unconditionally
 *   proceed to {@code chain.next*()}, and the class is {@code final} (cannot be subclassed to
 *   override that), the only place to implement (b) is a *separate* advisor ordered immediately
 *   after this one -- {@link RagEmptyContextGuardAdvisor} (see its own Javadoc for the mechanics).
 *   <p>
 *   This bean still sets {@code allowEmptyContext(true)} explicitly (rather than leaving the
 *   library default {@code false}) -- not because that value is ever exercised when the guard
 *   advisor is correctly wired (the guard short-circuits before the model call either way), but
 *   to make it unambiguous, for a future reader, that {@link RagEmptyContextGuardAdvisor} is the
 *   single source of truth for the zero-document case. Leaving the default {@code false} would
 *   mean that if the guard were ever accidentally omitted from a chain, the library would quietly
 *   substitute its own English "outside your knowledge base" template instead -- turning an AC-5
 *   regression into a *different*, more confusing symptom (a stray English answer) rather than an
 *   absent Korean phrase, making the defect harder to attribute to this class pairing.
 */
@Configuration
public class RagAdvisorConfig {

    /**
     * [Rationale] {@link RetrievalAugmentationAdvisor}'s own unset default is {@code 0} (see its
     *   source: {@code this.order = order != null ? order : 0;}). Set explicitly here, rather
     *   than relying on that default silently, so this value and {@link
     *   RagEmptyContextGuardAdvisor#ORDER} are both visible from one place and cannot drift apart
     *   independently.
     */
    public static final int RAG_ADVISOR_ORDER = 0;

    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            SpringAiDocumentRetrieverAdapter documentRetriever) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(true).build())
                .order(RAG_ADVISOR_ORDER)
                .build();
    }

    @Bean
    public RagEmptyContextGuardAdvisor ragEmptyContextGuardAdvisor() {
        return new RagEmptyContextGuardAdvisor();
    }
}
