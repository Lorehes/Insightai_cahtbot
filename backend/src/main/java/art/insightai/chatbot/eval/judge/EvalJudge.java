package art.insightai.chatbot.eval.judge;

import java.util.List;

import art.insightai.chatbot.eval.evalset.EvalCase;

/**
 * [Purpose] Pure judgement logic for one {@link EvalCase} against one {@link EvalResponse} —
 *   no LLM call, no database, no chatbot pipeline dependency. Card E5 (sweep runner) will call
 *   {@link #judge(EvalCase, EvalResponse)} once the real hybrid-rag pipeline exists to produce
 *   {@link EvalResponse} instances; until then this class is exercised only with hand-built
 *   {@link EvalResponse} fixtures in tests.
 * [Rationale] Tech Review D-3 fixes every rule this class implements:
 *   {@code answer_correct} = every {@code expectContains} string is a substring of the answer
 *   after whitespace is removed from both (D-3 revised 2026-09-01, BL-043 (a) -- the original
 *   "no normalization" rule mis-scored "17 명" against "17명"; see {@link #stripWhitespace});
 *   {@code source_correct} = {@code expectSource} is among
 *   {@code sourceFilenames}; {@code not_found_correct} = any refusal phrase is present AND
 *   {@code sourceFilenames} is empty; {@code hallucination} = no refusal phrase is present.
 *   The refusal-phrase match itself is deterministic, not an LLM-as-judge call: hybrid-rag
 *   Spec D-T8 already commits the application (not the LLM) to emitting one of these exact
 *   fixed strings whenever the zero-result "not found" path is taken (hybrid-rag:AC-5), and
 *   multilingual-support AD-4 extends that fixed set to ko/en/zh/ja, so a plain substring check
 *   against {@link #REJECTION_PHRASES} is reliable — there is no free-form LLM wording to guess
 *   at. This resolves Spec OQ-4 (AND of phrase match + empty sources) without inventing a
 *   keyword list.
 * [Caution] {@link #REJECTION_PHRASES} is an independent copy of the refusal strings
 *   rag/retrieval's {@code RefusalPhrases} table actually emits, not a compile-time dependency
 *   (multilingual-support AD-3 keeps eval isolated from the rag/retrieval module on purpose —
 *   no import of {@code RefusalPhrases} here). Each entry MUST be re-verified by hand against
 *   {@code RefusalPhrases#all()} whenever either table changes — a single character mismatch
 *   makes every NOT_FOUND case in that language silently misjudge {@code hallucination=true}
 *   (the refusal-phrase check in {@link #judgeNotFound(EvalCase.NotFound, EvalResponse)} would
 *   never match). No automated drift-detection test exists for this pair yet — adding one is
 *   left to a future decision, not enforced by this class.
 * [AC] eval-usage-dashboard:AC-3
 * [AC] eval-usage-dashboard:AC-4
 * [AC] eval-usage-dashboard:AC-5
 * [AC] eval-usage-dashboard:AC-6a
 * [AC] multilingual-support:AC-9
 */
public final class EvalJudge {

    /**
     * [Rationale] Tech Review D-3: hybrid-rag Spec AC-5's fixed refusal string, trailing period
     *   excluded from the match per D-3's explicit note. Kept as a standalone constant (rather
     *   than folded away) because it is the ko entry of {@link #REJECTION_PHRASES} and existing
     *   call sites/comments outside this file refer to it by name.
     * [Caution] This is an independent copy, not sourced from hybrid-rag/rag-retrieval code
     *   (multilingual-support AD-3 forbids a compile-time dependency on that module). It must be
     *   re-verified against the actual string that module emits whenever it changes — a
     *   one-character drift makes every NOT_FOUND case silently misjudge
     *   {@code hallucination=true}.
     */
    static final String REJECTION_PHRASE = "제공된 문서에서 찾을 수 없습니다";

    /**
     * [Purpose] The four ko/en/zh/ja refusal sentences this judge treats as "the model refused
     *   to answer", each with its terminal punctuation (Korean/English {@code '.'}, Chinese/
     *   Japanese {@code '。'}) already stripped so the {@code contains} check in
     *   {@link #judgeNotFound(EvalCase.NotFound, EvalResponse)} matches regardless of whether the
     *   model actually terminates the sentence.
     * [Rationale] multilingual-support AD-4 (user-confirmed 2026-09-02, gate 2) fixes these four
     *   literal sentences. multilingual-support AD-3 requires this to be an independent copy
     *   inside {@code eval} rather than an import of rag/retrieval's {@code RefusalPhrases} table,
     *   so the eval module stays decoupled from the production retrieval module it is scoring.
     * [Caution] Must match {@code RefusalPhrases#all()} character-for-character except for the
     *   terminal punctuation (deliberately stripped here, present there). A drift-detection test
     *   that asserts this equality does not exist yet in this codebase; keeping the two tables in
     *   sync by hand is the caller's responsibility until one is added.
     * [AC] multilingual-support:AC-9
     */
    static final List<String> REJECTION_PHRASES = List.of(
            REJECTION_PHRASE,
            "The answer is not in the provided documents",
            "在提供的文档中找不到答案",
            "提供された文書には見つかりませんでした");

    private EvalJudge() {
    }

    /**
     * [Purpose] Judges either kind of {@link EvalCase}, dispatching to the matching rule set.
     */
    public static JudgeResult judge(EvalCase evalCase, EvalResponse response) {
        return switch (evalCase) {
            case EvalCase.Found found -> judgeFound(found, response);
            case EvalCase.NotFound notFound -> judgeNotFound(notFound, response);
        };
    }

    /**
     * [Purpose] AC-3/AC-4: scores a "found" question. {@code answerCorrect} and
     *   {@code sourceCorrect} are computed independently and never collapsed, so a caller can
     *   tell a wrong-answer FAIL apart from a right-answer-wrong-citation FAIL (AC-4).
     */
    public static JudgeResult.Found judgeFound(EvalCase.Found evalCase, EvalResponse response) {
        String answer = stripWhitespace(response.answer());
        boolean answerCorrect = evalCase.expectContains().stream()
                .map(EvalJudge::stripWhitespace)
                .allMatch(answer::contains);
        boolean sourceCorrect = response.sourceFilenames().contains(evalCase.expectSource());
        return new JudgeResult.Found(answerCorrect, sourceCorrect);
    }

    /**
     * [Purpose] Removes every whitespace character (ASCII and Unicode separators) so the {@code
     *   answer_correct} substring check ignores spacing only -- D-3 as revised by BL-043 (a),
     *   2026-09-01, user decision.
     * [Rationale] The D7 sweep judged "17 명 (정규직 기준)" and "12 년째" as wrong against {@code
     *   expect_contains} "17명"/"12년": the product was right and the judge was not (the same
     *   INC-024 family as the free-form refusal misclassification). Whitespace is the only
     *   normalization applied -- no case folding, no punctuation stripping, no semantic matching
     *   -- because anything looser would start counting wrong answers as right, which is the
     *   opposite misclassification (insight-chat-7f's caution on BL-043).
     * [Caution] Applied to both sides, so an {@code expect_contains} string that itself contains
     *   a space ("서초구 반포대로" style) still matches an answer that spaces it differently.
     */
    static String stripWhitespace(String text) {
        return text.replaceAll("[\\s\\p{Z}]+", "");
    }

    /**
     * [Purpose] AC-5/AC-6: scores a "not-found" question per the D-3 truth table, including the
     *   uncounted third state (a refusal phrase present but {@code sourceFilenames} non-empty),
     *   where neither {@code notFoundCorrect} nor {@code hallucination} is set.
     * [AC] multilingual-support:AC-9
     */
    public static JudgeResult.NotFound judgeNotFound(EvalCase.NotFound evalCase, EvalResponse response) {
        boolean refused = REJECTION_PHRASES.stream().anyMatch(response.answer()::contains);
        boolean sourcesEmpty = response.sourceFilenames().isEmpty();
        boolean notFoundCorrect = refused && sourcesEmpty;
        boolean hallucination = !refused;
        return new JudgeResult.NotFound(notFoundCorrect, hallucination);
    }
}
