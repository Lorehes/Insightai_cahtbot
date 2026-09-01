package art.insightai.chatbot.rag.retrieval;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.rag.domain.RetrievedChunk;

/**
 * [Purpose] Orchestrates the hybrid-rag retrieval pipeline end to end: branches on {@code
 *   app.search.mode} (AC-1, AC-2), fans out to the axis ports, delegates fusion and score-
 *   threshold filtering to {@link ReciprocalRankFusion} exactly once, delegates reranking to
 *   {@link RerankPort}, and owns the two failure fallbacks the ports themselves are not
 *   responsible for: a search-axis exception collapses to an empty result (AC-14), and a rerank
 *   exception collapses to the fused order (AC-8).
 * [Rationale] Pipeline order is Tech Review D-T3 plus one stage added by BL-025: mode branch
 *   -&gt; axis search(es) -&gt; per-axis raw-score filter ({@link #dropBelowRawThreshold}) -&gt;
 *   {@link ReciprocalRankFusion#fuse} (fusion + fused-score threshold, one pass) -&gt; {@link
 *   RerankPort#rerank} (which itself returns at most {@code app.search.top-k} entries, already
 *   in final order per AC-6/AC-7) -&gt; return. This class therefore never truncates a second
 *   time; asking {@link RerankPort#rerank} for {@code topK} directly is what makes "topK cutting
 *   happens after reranking" true without a duplicate {@code .limit(...)} here. In {@code VECTOR}
 *   mode the BM25 axis is never invoked (AC-1) and {@link ReciprocalRankFusion#fuse} is still
 *   called with {@code List.of()} for the BM25 side (its own Javadoc requires this over {@code
 *   null}) so the single threshold-filter code path (AC-4) runs the same way regardless of mode.
 *   D-T3's "threshold applies exactly once, right after RRF fusion" still holds literally:
 *   the fused-score filter is still one pass inside {@link ReciprocalRankFusion#fuse}. What
 *   BL-025 adds is a different filter, on a different quantity (axis-native scores), at a
 *   different stage -- because the fused score cannot express relevance at all. That deviation
 *   is recorded in the hybrid-rag Spec's design-deviation section.
 *   {@link RetrievedChunk#score} on every element this class returns is always the RRF fusion
 *   score (Tech Review D-T6) — this class never rewrites it, and neither collaborator it calls
 *   does either.
 * [Caution] This class does **not** implement Spring AI's {@code
 *   org.springframework.ai.rag.retrieval.search.DocumentRetriever} ({@code Query} -&gt; {@code
 *   List<org.springframework.ai.document.Document>}). That adapter/binding is T9's scope
 *   (advisor assembly), not T7's — the internal pipeline here speaks {@link RetrievedChunk}
 *   throughout ({@link ReciprocalRankFusion}, {@link RerankPort} are all typed against it), and
 *   adding a {@code Document}-mapping method to this class would be scope creep past the T7 card
 *   (global CLAUDE.md G2). AC-14's exact behavior when only ONE axis of a {@code HYBRID} search
 *   fails (the other having already succeeded) is deliberately the same as when both fail: this
 *   class returns {@code List.of()} for either case, per the AC-14 text itself ("검색 단계에서
 *   예외가 발생하면 sources 빈 배열") rather than a partial-axis-survives design, because Spec §5
 *   AC principle 1 requires an AC to be judgeable as true/false and "fail one axis, keep serving
 *   the other" is not written anywhere in the Spec. A partial-axis-survival mode may be a real
 *   quality improvement (the surviving axis's results are strictly more informative than none),
 *   but that is a **follow_up candidate for a future Spec revision**, not something this card
 *   implements — flagging it here per the card brief rather than silently deciding it. The
 *   {@code AC-13} tag below is intentionally marked partial: this class's fuse-then-rerank
 *   pipeline is the mechanism AC-13's exact-match ranking outcome depends on (a weight/rrfK
 *   change here can flip which chunk ranks first), so it is not unrelated to AC-13 — but T7 does
 *   not own AC-13's final evidence. Tech Review's "AC-13 소유권 재정정 (2026-08-28)" section
 *   closes T7 on AC-1/AC-2/AC-8 only, after {@code
 *   HybridDocumentRetrieverIntegrationTest.rankExactMatchFirstForProductCodeQuery} — written and
 *   run against the live ParadeDB/pgvector indexes — failed under the current default RRF
 *   weights (a tie the BM25-inserted-first accumulation order breaks the wrong way), and hands
 *   AC-13's final confirmation to {@code eval-usage-dashboard:T1} once D7 retunes those weights.
 *   That integration test stays in the repo {@code @Disabled}, not deleted, so re-enabling it is
 *   AC-13's actual close-out step. The {@code AC-14} tag below is likewise partial, but for a
 *   different reason than AC-13's: AC-14's own text is a three-clause sentence ("대화 응답 자체는
 *   실패하지 않고" / "sources 빈 배열로 진행" / "ERROR 로그"), and this class — being below the
 *   {@code ChatService} / advisor layer T9 and T10 own — can only produce the proxy evidence for
 *   two of the three (returning {@code List.of()} on axis failure, and the {@code Level.ERROR}
 *   log line in {@link #retrieve}). It has no way to prove the first clause ("대화 응답 자체는
 *   실패하지 않고"), which requires the end-to-end {@code ChatService} -&gt; advisor -&gt; this
 *   class path that does not exist yet. Tech Review's "AC-14 소유권 분할 (2026-08-28)" section
 *   (사용자 결정, following the 2026-08-28 T7 review round-1 High-1) therefore splits AC-14 into
 *   **T7 = partial (retriever-layer fallback)** and **T10 = final (end-to-end non-failure of the
 *   chat response)**; Spec §4's AC-14 row stays ⬜ until T10 adds the end-to-end check on top of
 *   this class's two unit tests.
 * [AC] hybrid-rag:AC-1
 * [AC] hybrid-rag:AC-2
 * [AC] hybrid-rag:AC-8
 * [AC] hybrid-rag:AC-13 (partial — final evidence is eval-usage-dashboard:T1, see [Caution] above)
 * [AC] hybrid-rag:AC-14 (partial — final evidence is hybrid-rag:T10, see Tech Review "AC-14 소유권 분할", see [Caution] above)
 */
@Component
public class HybridDocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HybridDocumentRetriever.class);

    private final VectorSearchPort vectorSearchPort;
    private final Bm25SearchPort bm25SearchPort;
    private final ReciprocalRankFusion reciprocalRankFusion;
    private final RerankPort rerankPort;

    /**
     * [Rationale] Kept as the whole {@link AppProperties.Search} record rather than unpacked
     *   into individual {@code final} fields (unlike the sibling {@link ReciprocalRankFusion} and
     *   {@link OpenRouterRerankAdapter}, which each unpack 3-4 scalars used inside a single
     *   method). This class reads four different sub-fields ({@code mode}, {@code vectorTopK},
     *   {@code bm25TopK}, {@code topK}) across two separate methods ({@link #retrieve}, {@link
     *   #searchAndFuse}), so the record stays a single cohesive "current search config" value
     *   threaded through the whole request rather than four loosely-related scalars — no project
     *   rule mandates either style (2026-08-28 review Low-4).
     */
    private final AppProperties.Search searchProperties;

    public HybridDocumentRetriever(
            VectorSearchPort vectorSearchPort,
            Bm25SearchPort bm25SearchPort,
            ReciprocalRankFusion reciprocalRankFusion,
            RerankPort rerankPort,
            AppProperties appProperties
    ) {
        this.vectorSearchPort = vectorSearchPort;
        this.bm25SearchPort = bm25SearchPort;
        this.reciprocalRankFusion = reciprocalRankFusion;
        this.rerankPort = rerankPort;
        this.searchProperties = appProperties.search();
    }

    /**
     * [Purpose] Runs the full pipeline for one query and returns the final, reranked-or-fused
     *   chunk list — never throws for a search-axis or rerank failure (AC-8, AC-14). This
     *   guarantee covers failures produced *while searching*; it does not cover a caller passing
     *   an invalid {@code query} in the first place — see [Caution] below.
     * [Caution] {@code query} must not be {@code null}: this method calls {@link
     *   Objects#requireNonNull} on it immediately, so a null argument throws {@link
     *   NullPointerException} before either try block runs, rather than being silently treated as
     *   a search-axis failure. This project has no package-wide null-contract convention ({@code
     *   rag/domain/Document.java} is the only file under {@code src/main} using {@code
     *   @NonNull}/{@code @Nullable}/{@code requireNonNull}), so this explicit check plus this line
     *   is what establishes the contract for this class. A prior revision computed {@code
     *   query.length()} inside the two catch blocks below without this guard: a null query then
     *   reached {@link #searchAndFuse}, the resulting {@link RuntimeException} was caught as if it
     *   were an ordinary search-axis failure, and the log call's own {@code query.length()}
     *   dereference threw a fresh, uncaught {@link NullPointerException} — silently breaking the
     *   "never throws" guarantee this method exists to provide (2026-08-28 review round 2,
     *   Medium-1). T9/T10's advisor assembly must never invoke this method with a null query;
     *   confirm that as part of AC-14's end-to-end verification (Tech Review "AC-14 소유권 분할").
     *   Separately: never log the raw {@code query} string itself. It is verbatim user chat input
     *   for an internal company bot (HR/payroll/contract topics are in scope), and this project's
     *   logging convention is to log identifiers, not user-authored body text (see {@code
     *   ChatService#streamReply}'s {@code conversationId=} pattern and {@code
     *   ConversationService}'s {@code userId=}/{@code conversationId=} pattern — neither ever logs
     *   message content). {@code query} also has no newline restriction (the caller-side {@code
     *   ConversationController.MAX_MESSAGE_LENGTH} only bounds length), so logging it verbatim is
     *   a log-forging vector (CWE-117) on top of the content-exposure problem. Below, only {@code
     *   query.length()} and the failing exception's class name are logged; the exception's own
     *   stack trace carries most of the remaining diagnostic value. This was Medium in the
     *   2026-08-28 T7 review round 1 because the exposure stays inside this single-host demo
     *   deployment's own server log
     *   (no new trust boundary crossed) — re-evaluate as High once phase-2 (사내망) or later moves
     *   this log off the host.
     */
    public List<RetrievedChunk> retrieve(String query) {
        Objects.requireNonNull(query, "query must not be null");

        List<RetrievedChunk> fused;
        try {
            fused = searchAndFuse(query);
        } catch (RuntimeException e) {
            log.error("검색/융합 단계에서 예외 발생, 빈 sources 로 진행 (queryLength={}, mode={}, cause={})",
                    query.length(), searchProperties.mode(), e.getClass().getSimpleName(), e);
            return List.of();
        }

        try {
            return rerankPort.rerank(query, fused, searchProperties.topK());
        } catch (RuntimeException e) {
            log.warn("리랭크 실패, 융합 결과 상위 topK 로 폴백 (queryLength={}, topK={}, cause={})",
                    query.length(), searchProperties.topK(), e.getClass().getSimpleName(), e);
            return fused.stream().limit(searchProperties.topK()).toList();
        }
    }

    /**
     * [Purpose] Mode branch (AC-1, AC-2): {@code VECTOR} queries only {@link #vectorSearchPort};
     *   {@code HYBRID} queries both axes. Either branch hands its raw axis result(s) to {@link
     *   ReciprocalRankFusion#fuse} for fusion and the one-time threshold filter.
     * [Caution] Any {@link RuntimeException} thrown by either port propagates unchanged to
     *   {@link #retrieve}, which is the only place that catches it (AC-14) — this method does not
     *   swallow anything itself.
     */
    private List<RetrievedChunk> searchAndFuse(String query) {
        if (searchProperties.mode() == AppProperties.Search.Mode.VECTOR) {
            List<RetrievedChunk> vectorResults = dropBelowRawThreshold(
                    vectorSearchPort.search(query, searchProperties.vectorTopK()),
                    searchProperties.vectorRawScoreThreshold());
            return reciprocalRankFusion.fuse(List.of(), vectorResults);
        }
        List<RetrievedChunk> bm25Results = dropBelowRawThreshold(
                bm25SearchPort.search(query, searchProperties.bm25TopK()),
                searchProperties.bm25RawScoreThreshold());
        List<RetrievedChunk> vectorResults = dropBelowRawThreshold(
                vectorSearchPort.search(query, searchProperties.vectorTopK()),
                searchProperties.vectorRawScoreThreshold());
        return reciprocalRankFusion.fuse(bm25Results, vectorResults);
    }

    /**
     * [Purpose] Drops chunks whose axis-native score is below that axis's raw threshold, before
     *   {@link ReciprocalRankFusion} turns scores into rank contributions (BL-025).
     * [Rationale] {@code app.search.score-threshold} filters the fused RRF score, and that score
     *   is a function of rank alone: a chunk ranked first on both axes scores exactly
     *   {@code 0.5/61 + 0.5/61} no matter how relevant it is, so that filter can only decide how
     *   many ranks pass, never how relevant a chunk is. Measured on the D7 sweep's stored
     *   {@code messages.sources}, 9 of 10 questions with no answer in the corpus produced the
     *   same top-1 fused score as questions that had one. Relevance information survives only in
     *   the axis-native scores (BM25 term score, cosine similarity), which fusion discards by
     *   design (Tech Review D-T6), so the only place a relevance filter can work is here, ahead
     *   of it. This is what the plan doc's R6 meant by a score threshold: its evidence is a
     *   1.18-point BM25 row that was irrelevant while 3.99 was the answer (D1 spike 5-c).
     * [Caution] Each axis needs its own threshold because the two are not on a common scale:
     *   BM25 scores are unbounded and corpus-dependent, cosine similarity is bounded. A
     *   threshold of {@code 0.0} disables the filter for that axis and returns the list
     *   untouched, which is the default until D7 measures real values; that identity return also
     *   keeps the empty-axis contract ({@code List.of()} for an axis that did not run) intact.
     *   Filtering happens before fusion on purpose: {@link ReciprocalRankFusion} derives rank
     *   from list position, so a dropped chunk must not occupy a rank slot and push a surviving
     *   chunk down. The comparison is {@code >=}, matching the existing threshold's boundary
     *   behavior (Spec Test Case U2). This does not replace {@code app.search.score-threshold}:
     *   that filter still runs exactly once inside {@link ReciprocalRankFusion#fuse} (AC-4), and
     *   both default to disabled.
     */
    private static List<RetrievedChunk> dropBelowRawThreshold(
            List<RetrievedChunk> axisResults,
            double rawScoreThreshold
    ) {
        if (rawScoreThreshold <= 0.0) {
            return axisResults;
        }
        return axisResults.stream()
                .filter(chunk -> chunk.score() >= rawScoreThreshold)
                .toList();
    }
}
