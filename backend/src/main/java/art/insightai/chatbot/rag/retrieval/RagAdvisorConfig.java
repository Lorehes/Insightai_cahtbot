package art.insightai.chatbot.rag.retrieval;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
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
 *   <p>
 *   <b>T12 addendum (hybrid-rag AC-9, multi-turn query compression).</b> {@link
 *   #retrievalAugmentationAdvisor} now also wires {@link #compressionQueryTransformer} in as its
 *   sole {@code queryTransformers} entry, per Tech Review D-T3's fixed pipeline order
 *   (질의압축 always runs first, ahead of both search axes). {@link #compressionQueryTransformer}
 *   itself only builds the library-provided {@link CompressionQueryTransformer} delegate and
 *   hands it to {@link HistoryAwareCompressionQueryTransformer} -- see that class's own Javadoc
 *   for why the wrapper guards against calling the model when {@link Query#history()} carries no
 *   prior assistant turn (2026-08-30 재작업 정정 -- an empty {@code history()} never actually
 *   occurs; see that class's own Javadoc for the runtime-confirmed shape) -- doing so
 *   unconditionally (the library default) would silently regress this class's own AC-5 evidence
 *   above. As with T9's own attachment note, this class still does not attach {@code
 *   RetrievalAugmentationAdvisor} to any {@code ChatClient} (still T10's scope) -- so AC-9's
 *   end-to-end evidence (a real request through the attached chain) is not obtainable from this
 *   class alone; see {@link HistoryAwareCompressionQueryTransformerTest}'s own Javadoc for the
 *   unit-level evidence this card closes instead ({@code RagAdvisorConfigCompressionTest} was
 *   renamed to this in the 3라운드 class extraction below).
 *   <p>
 *   <b>T12 재작업 3라운드 (2026-08-30, 리뷰 2R maintainability Medium-1).</b> The guard, fallback, and
 *   sanitize logic that used to live inline in {@link #compressionQueryTransformer} moved to
 *   {@link HistoryAwareCompressionQueryTransformer}, a separate top-level class, matching how
 *   {@link RagEmptyContextGuardAdvisor} was already extracted the moment its own logic stopped
 *   being trivial. {@link #compressionQueryTransformer} now does assembly only, consistent with
 *   this class's own stated purpose.
 *   <p>
 *   <b>T12 재작업 2라운드 (2026-08-30, 리뷰 Block High-2/Medium-4).</b> Read {@code
 *   RetrievalAugmentationAdvisor.before()} directly (spring-ai-rag 2.0.1 source, lines 111-151):
 *   step 1 runs {@link #compressionQueryTransformer} over {@code originalQuery} to produce {@code
 *   transformedQuery}, which step 3 hands to {@link SpringAiDocumentRetrieverAdapter} (i.e. the
 *   compressed text is what actually reaches search) -- but step 6's {@code
 *   this.queryAugmenter.augment(originalQuery, documents)} passes the *original*, uncompressed
 *   {@code Query}, not {@code transformedQuery}. So the final answer-generation prompt's user text
 *   (built from {@code augmentedQuery.text()} at step 7) is always grounded in the user's verbatim
 *   wording, never silently replaced by the compressed standalone query -- compression only ever
 *   affects which documents get retrieved, never what the model is asked to answer. This resolves
 *   this card's own prior open question about a possible T10 hand-off item: there is none for this
 *   specific concern.
 *   <p>
 *   <b>T10 재작업 1라운드 (2026-08-30, 리뷰 Block security High-1/Medium-2).</b> Two changes land
 *   together here, both required before {@code ChatService} (T10's own file) may attach this
 *   class's beans to a real {@link ChatClient} for the first time (Spec §8 R-H's own words: "T9 의
 *   두 빈은 어떤 {@code ChatClient} 에도 부착되지 않으며... T9 병합만으로는 런타임 인젝션 경로가 생기지
 *   않는다" -- T10 is exactly the card that closes that gap). <b>(1) R-H.</b> {@link
 *   #retrievalAugmentationAdvisor} now passes {@link #DOCUMENT_FORMATTER} and {@link
 *   #PROMPT_TEMPLATE} to {@link ContextualQueryAugmenter.Builder} instead of leaving both at the
 *   library default -- see those fields' own Javadoc for the two-part defense (chunk-body
 *   neutralization plus an explicit context/instruction boundary and treat-as-data rule). This is a
 *   lightweight mitigation only (Spec §8 R-H's own characterization, matching the precedent already
 *   set at {@link HistoryAwareCompressionQueryTransformer#sanitizeCompressedQuery} for the sibling
 *   risk R-J) -- it narrows the specific "fake separator line" vector R-H's empirical read of the
 *   library default found, not every conceivable prompt-injection shape. It does not interact with
 *   this class's own AC-5 short-circuit: {@code ContextualQueryAugmenter.augment()} (spring-ai-rag
 *   2.0.1 source) only ever invokes {@code documentFormatter}/{@code promptTemplate} on its
 *   non-empty-{@code documents} branch -- the zero-document branch ({@code
 *   augmentQueryWhenEmptyContext}) neither reads nor calls either, and {@link
 *   RagEmptyContextGuardAdvisor} short-circuits before the model call independently of which
 *   {@code QueryAugmenter} configuration produced the (unused, on that path) augmented query text.
 *   Confirmed by re-running every {@code RagAdvisorAssemblyTest} AC-5 test after this change with no
 *   modification to those tests. <b>(2) Medium-2.</b> {@link #ragAdvisors} bundles {@link
 *   #retrievalAugmentationAdvisor} and {@link #ragEmptyContextGuardAdvisor} into the single
 *   {@code List<Advisor>} {@code ChatService} now injects and attaches as one call-site argument --
 *   see that bean's own Javadoc for why "attach RAG without the guard" being a type error (rather
 *   than a two-argument call site a future edit could silently shrink to one) is the actual fix.
 *   <p>
 *   <b>T10 재작업 2라운드 (2026-08-30, 리뷰 2R Block security Medium-1/Medium-2) -- both of round
 *   1's fixes above were themselves found incomplete, not merely refinable.</b> <b>(1) Medium-1.</b>
 *   Round 1's {@code DOCUMENT_FORMATTER}/{@code PROMPT_TEMPLATE} fields (both names above -- both
 *   removed from this class by this round, so neither resolves to a member here anymore)
 *   neutralized one attack shape (dash-only separator lines) but used *fixed* literal boundary
 *   markers ({@code [CONTEXT START]}/{@code [CONTEXT END]}, {@code [DOCUMENT n]}/{@code
 *   [/DOCUMENT n]}). A retrieved chunk could simply reproduce those exact literals itself, ahead
 *   of its own injected instruction text, and a model would read the forged marker as the real
 *   boundary -- no amount of neutralizing chunk content closes this, because the fixed literal is
 *   the only thing distinguishing "real boundary" from "forged boundary", and an attacker who does
 *   not know today's exact neutralization rule can simply pick a different fixed string next time.
 *   {@link #retrievalAugmentationAdvisor} now uses {@link NonceBoundaryContextAugmenter} instead
 *   of {@link ContextualQueryAugmenter} -- a fresh, unpredictable nonce generated inside that
 *   class's own {@code augment} call (i.e. after every document is already fixed content) makes
 *   the real boundary markers impossible for any chunk to have pre-empted; see that class's own
 *   Javadoc for the full rationale and its Javadoc [Caution] for a test-construction pitfall that
 *   would silently defeat this property if repeated. <b>(2) Medium-2.</b> Round 1's fix bundled
 *   both advisors into a single {@code List<Advisor>}, but a bare {@code List<Advisor>} still
 *   compiles for {@code .advisors(ragAdvisors.get(0))} (RAG attached without the guard), and
 *   Spring resolves a {@code List<Advisor>} injection point by the parameter's *name*, not its
 *   type ({@code DefaultListableBeanFactory} source) -- a future rename or an unrelated second
 *   {@code Advisor} bean could silently change what a {@code List<Advisor>}-typed constructor
 *   parameter actually receives. {@link #ragAdvisors} now returns {@link RagAdvisorPair}, a
 *   dedicated record with no accessor that returns one advisor without the other -- see that
 *   record's own Javadoc for both mechanics this closes.
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

    /**
     * [Purpose] Assembles the RAG advisor with T12's {@link #compressionQueryTransformer} as its
     *   only query transformer (Tech Review D-T3: 질의압축 is always the pipeline's first stage),
     *   and (T10 재작업 2라운드) {@link NonceBoundaryContextAugmenter} as its {@code queryAugmenter}
     *   (Spec §8 R-H) -- replacing T10 재작업 1라운드's {@link ContextualQueryAugmenter} plus fixed
     *   {@code documentFormatter}/{@code promptTemplate} configuration (see this class's own
     *   Javadoc addendum, and {@link NonceBoundaryContextAugmenter}'s own Javadoc, for why the
     *   fixed-marker approach was replaced rather than merely patched again).
     * [Rationale] {@code queryTransformers(QueryTransformer...)} takes a chain; a chain of one is
     *   sufficient here because this Spec has exactly one query-rewriting concern (AC-9). Adding a
     *   second transformer later must preserve compression running first, since a later stage
     *   would otherwise search on an un-compressed pronoun-only query. {@code
     *   ContextualQueryAugmenter}'s {@code allowEmptyContext(true)} configuration is no longer set
     *   here because {@link NonceBoundaryContextAugmenter} is not that class -- its own
     *   empty-{@code documents} branch always returns the original query unchanged, the same
     *   behavior {@code allowEmptyContext(true)} used to select (see that class's own Javadoc
     *   [Caution] for why this branch is unreachable in production regardless). A fresh {@link
     *   NonceBoundaryContextAugmenter} instance is constructed once here, at bean-creation time --
     *   it is that instance's own {@code augment} method, invoked fresh on every request, that
     *   generates a new random nonce each time, not this bean method.
     * [AC] hybrid-rag:AC-9
     */
    @Bean
    public RetrievalAugmentationAdvisor retrievalAugmentationAdvisor(
            SpringAiDocumentRetrieverAdapter documentRetriever, QueryTransformer compressionQueryTransformer) {
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .queryTransformers(compressionQueryTransformer)
                .queryAugmenter(new NonceBoundaryContextAugmenter())
                .order(RAG_ADVISOR_ORDER)
                .build();
    }

    /**
     * [Purpose] Builds the library-provided {@link CompressionQueryTransformer} delegate and
     *   wraps it in {@link HistoryAwareCompressionQueryTransformer}, which owns the guard,
     *   fallback, and sanitize behavior AC-9 needs (see that class's own Javadoc for the full
     *   rationale and the runtime-confirmed shape of {@link Query#history()} this guard relies on).
     * [Rationale] 2026-08-30 재작업 3라운드 (리뷰 2R maintainability Medium-1): this bean previously
     *   carried that logic inline, which conflicted with this class's own "assembly only" Javadoc
     *   and was the only place in this file departing from the pattern {@link
     *   #ragEmptyContextGuardAdvisor} already follows (custom logic in its own top-level class, one
     *   line here). Extracting {@link HistoryAwareCompressionQueryTransformer} restores that
     *   pattern; this method now only constructs the delegate and hands it over. The {@link
     *   ChatClient.Builder} parameter is Spring AI's own autoconfigured, {@code prototype}-scoped
     *   bean (confirmed via {@code ChatClientAutoConfiguration} bytecode: {@code
     *   @Scope("prototype")} on its {@code chatClientBuilder} bean method) -- each injection point,
     *   including {@code ChatService}'s own constructor, receives an independent builder instance,
     *   so this bean building its own internal {@link ChatClient} does not share or interfere with
     *   {@code ChatService}'s.
     * [AC] hybrid-rag:AC-9
     */
    @Bean
    public QueryTransformer compressionQueryTransformer(ChatClient.Builder chatClientBuilder) {
        // 2026-09-03: the prompt is no longer the library default -- see
        // HistoryAwareCompressionQueryTransformer.COMPRESSION_PROMPT_TEMPLATE's [Rationale] for
        // why the output language and identifiers are pinned there (still assembly-only here).
        CompressionQueryTransformer delegate = CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .promptTemplate(HistoryAwareCompressionQueryTransformer.COMPRESSION_PROMPT_TEMPLATE)
                .build();
        return new HistoryAwareCompressionQueryTransformer(delegate);
    }

    @Bean
    public RagEmptyContextGuardAdvisor ragEmptyContextGuardAdvisor() {
        return new RagEmptyContextGuardAdvisor();
    }

    /**
     * [Purpose] Bundles {@link #retrievalAugmentationAdvisor} and {@link
     *   #ragEmptyContextGuardAdvisor} into the one {@code List<Advisor>} {@code ChatService}
     *   injects and attaches with a single {@code .advisors(List<Advisor>)} call (T10 재작업
     *   1라운드, 리뷰 Block security Medium-2).
     * [Rationale] Before this bean existed, {@code ChatService} held the two advisors as two
     *   separate constructor parameters and attached them with a two-argument {@code
     *   .advisors(Advisor...)} call -- nothing in the type system stopped a future edit from
     *   dropping one argument (most plausibly the guard, since the RAG advisor alone still
     *   "does something" and a missing guard fails silently rather than throwing: Spec §8's fixed
     *   phrase would simply stop appearing on a zero-document turn, replaced by the library's own
     *   English "outside your knowledge base" template -- see {@link RagAdvisorConfig}'s own
     *   Javadoc for why that specific silent failure mode is the one this whole class's design
     *   already goes out of its way to avoid). Returning a single {@code List<Advisor>} instead
     *   removes that failure mode structurally: {@code ChatService} has no reference to either
     *   advisor individually to attach alone, only this bean, whose only content is both of them
     *   together. List order (RAG first, guard second) mirrors the previous call-site argument
     *   order for readability only -- {@code DefaultAroundAdvisorChain.Builder.reOrder()}
     *   re-sorts by {@code getOrder()} regardless of list/argument order either way (see {@code
     *   RagAdvisorAssemblyTest#shortCircuitsEvenWhenRegisteredInReverseOrder}, which already
     *   proves this for the two-argument form and remains valid for this list form since the
     *   underlying chain-building mechanics are unchanged).
     *   <p>
     *   <b>T10 재작업 2라운드 (2026-08-30, 리뷰 2R Block security Medium-2).</b> The {@code
     *   List<Advisor>} return type above turned out not to be the structural fix its own
     *   [Rationale] paragraph claimed: {@code .advisors(ragAdvisors.get(0))} (RAG attached without
     *   the guard) still compiles against a {@code List<Advisor>}, and Spring resolves a
     *   {@code List<Advisor>}-typed constructor parameter by the parameter's own *name*
     *   ({@code ragAdvisors}), not its type ({@code DefaultListableBeanFactory} source, read
     *   directly) -- a future rename of either this bean or {@code ChatService}'s own parameter, or
     *   an unrelated second {@code Advisor}-typed bean added anywhere in the context, could
     *   silently change what {@code ChatService} actually receives, with no compiler error either
     *   way. This method now returns {@link RagAdvisorPair} instead -- a dedicated record with no
     *   accessor that yields one advisor without the other, resolved by Spring through its own
     *   distinct type rather than a generic {@code List<Advisor>} plus a name match. See that
     *   record's own Javadoc for the full mechanics.
     * [AC] hybrid-rag:AC-10, hybrid-rag:AC-14
     */
    @Bean
    public RagAdvisorPair ragAdvisors(RetrievalAugmentationAdvisor retrievalAugmentationAdvisor,
            RagEmptyContextGuardAdvisor ragEmptyContextGuardAdvisor) {
        return new RagAdvisorPair(retrievalAugmentationAdvisor, ragEmptyContextGuardAdvisor);
    }
}
