package art.insightai.chatbot.eval.judge;

import java.math.BigDecimal;
import java.util.List;

/**
 * [Purpose] The eval-local view of a chatbot response that {@link EvalJudge} scores against an
 *   {@code EvalCase} — the answer body, the source filenames cited, and the latency/cost figures
 *   the future report card (E4) aggregates alongside pass/fail counts.
 * [Rationale] Tech Review D-2 fixes this exact shape so E3/E4 never compile against hybrid-rag's
 *   {@code SourceDto} or chat's response types. Card E5 (sweep runner), once the real chatbot
 *   pipeline (hybrid-rag T7/T9~T11) exists, is the single place that maps a real response into
 *   this record; {@link EvalJudge} stays a pure function of this record and never calls the
 *   pipeline itself.
 */
public record EvalResponse(String answer, List<String> sourceFilenames, long latencyMs, BigDecimal costUsd) {
}
