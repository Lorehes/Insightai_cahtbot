package art.insightai.chatbot.common.lang;

import java.util.Optional;

/**
 * [Purpose] The four supported answer/question languages plus UNKNOWN, the result
 *   {@link LanguageDetector#detect(String)} returns when it cannot decide.
 * [Rationale] Spec multilingual-support §5.3 fixes the language set at ko/en/zh/ja. AD-9 requires
 *   this exact type (not a separate eval-set enum) so AC-11's language-match comparison never
 *   needs a conversion step.
 * [Caution] {@link #orDefault()} is the single fallback point (AD-7, OQ-3 C1) — do not add another
 *   UNKNOWN -> KO fallback elsewhere; route callers through this method instead.
 * [AC] multilingual-support:AC-1
 */
public enum Language {
    KO,
    EN,
    ZH,
    JA,
    UNKNOWN;

    /**
     * [Purpose] Resolves UNKNOWN to the project's default answer language (KO); leaves any other
     *   value unchanged.
     * [Rationale] AD-7 / OQ-3 C1 designate this as the only place the UNKNOWN -> KO fallback may
     *   happen, so prompt rendering, the empty-context guard, and the eval report all agree.
     * [AC] multilingual-support:AC-1
     */
    public Language orDefault() {
        return this == UNKNOWN ? KO : this;
    }

    /**
     * [Purpose] Maps the eval-set YAML's lowercase {@code lang} code (exactly {@code ko}/{@code
     *   en}/{@code zh}/{@code ja}) to its enum constant; any other input yields empty.
     * [Rationale] AD-9 shares this enum between the eval-set schema and {@link LanguageDetector}
     *   so AC-11's question-language vs. answer-language comparison never needs a conversion step.
     *   {@code UNKNOWN} has no code on purpose — it is a detection outcome, not a value an eval-set
     *   item may declare.
     * [Caution] Only the exact lowercase codes match. {@link art.insightai.chatbot.eval.evalset.EvalSetLoader}
     *   relies on this to reject uppercase (e.g. {@code KO}) and {@code unknown} as validation
     *   failures rather than silently accepting them.
     * [AC] multilingual-support:AC-7
     */
    public static Optional<Language> fromCode(String code) {
        return switch (code) {
            case "ko" -> Optional.of(KO);
            case "en" -> Optional.of(EN);
            case "zh" -> Optional.of(ZH);
            case "ja" -> Optional.of(JA);
            default -> Optional.empty();
        };
    }

}
