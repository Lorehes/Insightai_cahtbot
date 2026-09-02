package art.insightai.chatbot.rag.retrieval;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import art.insightai.chatbot.common.lang.Language;

/**
 * [Purpose] The fixed ko/en/zh/ja "not found in the provided documents" refusal sentences, and the
 *   equality-based check that tells whether a complete assistant answer is exactly one of them.
 * [Rationale] Spec multilingual-support AD-3 places this table in {@code rag/retrieval} rather than
 *   {@code common/lang}: its only production consumers are {@link NonceBoundaryContextAugmenter} and
 *   {@link RagEmptyContextGuardAdvisor} (both in this package) plus {@code ChatService}, which already
 *   depends on {@link RagEmptyContextGuardAdvisor}. Promoting it to {@code common} would weaken the
 *   CLAUDE.md package split rationale ("changing the retrieval strategy stays inside rag/retrieval").
 *   AD-4 fixes the four literal sentences (user-confirmed 2026-09-02, gate 2) so this class does not
 *   invent wording. AD-5 keeps the pre-existing equality-check semantics (whitespace- and
 *   terminal-punctuation-insensitive) rather than switching to {@code contains}: Spec AC-4 requires an
 *   answer that merely *contains* a refusal sentence alongside other content to keep its sources, so
 *   equality is the contract itself, not an implementation detail.
 * [Caution] {@code KO} deliberately references {@link RagEmptyContextGuardAdvisor#NOT_FOUND_MESSAGE}
 *   instead of duplicating its value — that constant must not change by a single character (AC-5), and
 *   two independent literals would let the two drift apart silently. {@code NOT_FOUND_MESSAGE} is a
 *   compile-time constant expression, so this reference is inlined at compile time and creates no
 *   runtime class-init cycle with {@code RagEmptyContextGuardAdvisor} (which in turn calls
 *   {@link #isRefusal(String)}). {@link #isRefusal(String)} is an equality check, not {@code contains}
 *   — the equality comparison is AC-4's contract itself, not an implementation detail. The set of
 *   terminal punctuation stripped before comparison is exactly {@code {'.', '。'}} (en/ko end in
 *   {@code '.'}, zh/ja end in {@code '。'}). No case folding is applied (AD-5) — a mismatch falls
 *   through to "keep sources", the safe direction per {@link RagEmptyContextGuardAdvisor}'s existing
 *   Javadoc.
 * [AC] multilingual-support:AC-3, multilingual-support:AC-4, multilingual-support:AC-5 (부분 — 최종 판정
 *   경로 전환은 T4의 {@link RagEmptyContextGuardAdvisor} 소관, Tech Review "AC-5 소유권 분할")
 */
public final class RefusalPhrases {

    private static final Map<Language, String> PHRASES_BY_LANGUAGE = buildTable();

    private static final Set<String> NORMALIZED_REFUSALS = buildNormalizedSet();

    private RefusalPhrases() {
    }

    private static Map<Language, String> buildTable() {
        Map<Language, String> table = new EnumMap<>(Language.class);
        table.put(Language.KO, RagEmptyContextGuardAdvisor.NOT_FOUND_MESSAGE);
        table.put(Language.EN, "The answer is not in the provided documents.");
        table.put(Language.ZH, "在提供的文档中找不到答案。");
        table.put(Language.JA, "提供された文書には見つかりませんでした。");
        return Map.copyOf(table);
    }

    private static Set<String> buildNormalizedSet() {
        Set<String> normalized = new LinkedHashSet<>();
        for (String phrase : PHRASES_BY_LANGUAGE.values()) {
            normalized.add(normalize(phrase));
        }
        return Set.copyOf(normalized);
    }

    /**
     * [Purpose] Returns the fixed refusal sentence for {@code language}.
     * [Rationale] {@code UNKNOWN} has no sentence of its own — callers must resolve it through
     *   {@link Language#orDefault()} first (AD-7's single fallback point); this method does not
     *   silently substitute KO for it.
     * [AC] multilingual-support:AC-3, multilingual-support:AC-4
     */
    public static String of(Language language) {
        if (language == Language.UNKNOWN) {
            throw new IllegalArgumentException(
                    "No refusal phrase for UNKNOWN; resolve via Language#orDefault() first");
        }
        return PHRASES_BY_LANGUAGE.get(language);
    }

    /**
     * [Purpose] True when {@code answer}, after normalization, equals exactly one of the four
     *   refusal sentences — regardless of which language the question was asked in.
     * [Rationale] AD-5: equality (not {@code contains}) is AC-4's contract — an answer that embeds a
     *   refusal sentence alongside other content must keep its sources.
     * [Caution] Normalization: {@code null} -> false; {@link String#strip()}; if the result ends
     *   with {@code '.'} or {@code '。'}, remove exactly one such trailing character and strip again.
     *   No case folding.
     * [AC] multilingual-support:AC-3, multilingual-support:AC-4, multilingual-support:AC-5 (부분)
     */
    public static boolean isRefusal(String answer) {
        if (answer == null) {
            return false;
        }
        return NORMALIZED_REFUSALS.contains(normalize(answer));
    }

    /**
     * [Purpose] Exposes all four refusal sentences verbatim so drift-detection tests (T6's
     *   EvalJudge comparison) can assert the eval-side copy still matches production.
     */
    public static List<String> all() {
        return List.copyOf(PHRASES_BY_LANGUAGE.values());
    }

    private static String normalize(String text) {
        String normalized = text.strip();
        if (normalized.endsWith(".") || normalized.endsWith("。")) {
            normalized = normalized.substring(0, normalized.length() - 1).strip();
        }
        return normalized;
    }
}
