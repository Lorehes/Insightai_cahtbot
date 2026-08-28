package art.insightai.chatbot.eval.evalset;

import java.util.List;

/**
 * [Purpose] The full set of items loaded from an eval-set YAML file, with the found/not-found
 *   split that AC-1 checks (25 total = 15 found + 10 not-found in the real production file,
 *   per the 2026-08-26 expansion from the original 20 = 15 + 5).
 */
public record EvalSet(List<EvalCase> cases) {

    public List<EvalCase.Found> foundCases() {
        return cases.stream().filter(EvalCase.Found.class::isInstance).map(EvalCase.Found.class::cast).toList();
    }

    public List<EvalCase.NotFound> notFoundCases() {
        return cases.stream().filter(EvalCase.NotFound.class::isInstance).map(EvalCase.NotFound.class::cast).toList();
    }
}
