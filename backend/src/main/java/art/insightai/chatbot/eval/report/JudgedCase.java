package art.insightai.chatbot.eval.report;

import art.insightai.chatbot.eval.judge.EvalJudge;
import art.insightai.chatbot.eval.judge.EvalResponse;
import art.insightai.chatbot.eval.judge.JudgeResult;

/**
 * [Purpose] One judged eval case — the {@link EvalResponse} that was scored, paired with the
 *   {@link JudgeResult} it produced. {@link EvalReportAggregator} consumes a list of these because
 *   AC-8's avg_latency_ms/total_cost_usd metrics live on {@code EvalResponse} while the
 *   correctness/hallucination metrics live on {@code JudgeResult} — no single existing type
 *   carries both. Two more per-case contamination signals live here rather than on
 *   {@code EvalResponse}: {@code retrievedNonIndexedChunkCount} (AC-19a field 2) and
 *   {@code finishReason} (AC-20a). Both are report-only bookkeeping that {@link EvalJudge} never
 *   reads, and placing them on {@code EvalResponse} would force every one of its ~11 existing
 *   construction sites in {@code EvalJudgeTest} (AC-3/4/5/6a, already {@code ✅}) to supply values
 *   they do not care about; {@code JudgedCase} is already the E4/E6-owned pairing type, so it is
 *   the lower-blast-radius home for card E6's additions.
 * [Rationale] {@code EvalJudge} (E3) intentionally returns only a {@code JudgeResult} without
 *   echoing back latency or cost, keeping it a pure judgement function (D-2). E4 needs both to
 *   build a report row, so this record is the pairing E5 (sweep runner) will build one instance of
 *   per case, per combination. E5 is also the one place expected to populate
 *   {@code retrievedNonIndexedChunkCount}/{@code finishReason} from the real retrieval/chat
 *   pipeline (E6 does not know or care where those values come from — Tech Review card E6 note).
 * [AC] eval-usage-dashboard:AC-19a
 * [AC] eval-usage-dashboard:AC-20a
 */
public record JudgedCase(
        EvalResponse response,
        JudgeResult result,
        int retrievedNonIndexedChunkCount,
        String finishReason) {

    /**
     * [Purpose] Legacy 2-arg shape kept for every pre-existing call site ({@code JudgedCaseTest},
     *   {@code EvalReportAggregatorTest}'s {@code found()}/{@code notFound()} helpers) that has no
     *   opinion on the two new contamination fields.
     * [Rationale] Defaults to {@code retrievedNonIndexedChunkCount=0}/{@code finishReason=null} —
     *   "no contamination observed" — so those tests keep verifying AC-6b/AC-8/AC-9a exactly as
     *   before without being touched by this card (E6 card note: "순수 추가만").
     */
    public JudgedCase(EvalResponse response, JudgeResult result) {
        this(response, result, 0, null);
    }

    /**
     * [Purpose] Rejects a negative chunk count at construction time instead of letting
     *   {@link EvalReportAggregator} silently sum a nonsensical value into the report row.
     */
    public JudgedCase {
        if (retrievedNonIndexedChunkCount < 0) {
            throw new IllegalArgumentException("retrievedNonIndexedChunkCount must not be negative");
        }
    }

    /**
     * [Purpose] Overrides the record-default {@code toString()} so it never embeds the full
     *   {@code EvalResponse.answer()} body — only its length is shown.
     * [Caution] The default record {@code toString()} would call {@code response.toString()},
     *   which embeds {@code answer} verbatim (a full LLM answer built from internal documents).
     *   E4's scope has zero loggers or exception constructions, so no leak path exists today, but
     *   E5 (sweep runner) is expected to log {@code JudgedCase} instances once it exists — this
     *   override closes that path before it opens (L2, 2026-08-28 review).
     */
    @Override
    public String toString() {
        String answer = response.answer();
        return "JudgedCase[response=EvalResponse[answer.length="
                + (answer == null ? "null" : answer.length())
                + ", sourceFilenames=" + response.sourceFilenames()
                + ", latencyMs=" + response.latencyMs()
                + ", costUsd=" + response.costUsd()
                + "], result=" + result
                + ", retrievedNonIndexedChunkCount=" + retrievedNonIndexedChunkCount
                + ", finishReason=" + finishReason + "]";
    }
}
