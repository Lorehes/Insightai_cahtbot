package art.insightai.chatbot.eval.report;

import art.insightai.chatbot.eval.judge.EvalResponse;
import art.insightai.chatbot.eval.judge.JudgeResult;

/**
 * [Purpose] One judged eval case — the {@link EvalResponse} that was scored, paired with the
 *   {@link JudgeResult} it produced. {@link EvalReportAggregator} consumes a list of these because
 *   AC-8's avg_latency_ms/total_cost_usd metrics live on {@code EvalResponse} while the
 *   correctness/hallucination metrics live on {@code JudgeResult} — no single existing type
 *   carries both.
 * [Rationale] {@code EvalJudge} (E3) intentionally returns only a {@code JudgeResult} without
 *   echoing back latency or cost, keeping it a pure judgement function (D-2). E4 needs both to
 *   build a report row, so this record is the pairing E5 (sweep runner) will build one instance of
 *   per case, per combination.
 */
public record JudgedCase(EvalResponse response, JudgeResult result) {

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
                + "], result=" + result + "]";
    }
}
