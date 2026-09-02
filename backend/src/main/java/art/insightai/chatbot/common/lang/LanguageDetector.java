package art.insightai.chatbot.common.lang;

/**
 * [Purpose] Detects which of ko/en/zh/ja a piece of text is written in, or UNKNOWN when it
 *   cannot decide.
 * [Rationale] Spec multilingual-support §5.3 fixes the entire rule table (character ranges,
 *   thresholds, evaluation order, denominator definition), and Tech Review AD-1 chose a
 *   self-contained implementation over a third-party detector precisely so that table stays the
 *   single source of truth: a probabilistic library would not reproduce it, making AC-1
 *   ("returns per the §5.3 rule table") unverifiable. §5.3 also requires the same implementation
 *   to serve both question-language detection (prompt rendering) and answer-language matching
 *   (AC-11) — do not fork this logic.
 * [Caution] The evaluation order below is part of the rule, not an optimization — Japanese text
 *   mixes kana and han, so kana must be checked first (§5.3 note). Thresholds are initial values
 *   (Spec §8 R-2) kept as the four constants below; adjust only there. Threshold comparisons use
 *   integer cross-multiplication, not floating-point ratios — {@code EvalReportRow#passed()}'s
 *   {@code 0.87 * 15 = 13.050000000000001} caution is the precedent for why floating point is
 *   forbidden here (Tech Review AD-1).
 * [AC] multilingual-support:AC-1
 */
public final class LanguageDetector {

    private static final int KANA_THRESHOLD_PERCENT = 5;
    private static final int HANGUL_THRESHOLD_PERCENT = 20;
    private static final int HAN_THRESHOLD_PERCENT = 20;
    private static final int LATIN_THRESHOLD_PERCENT = 50;

    private LanguageDetector() {
    }

    /**
     * [Purpose] Classifies {@code text} into ko/en/zh/ja/UNKNOWN following the Spec §5.3 rule
     *   table.
     * [Side effects] None; a pure function of the input.
     * [AC] multilingual-support:AC-1
     */
    public static Language detect(String text) {
        if (text == null) {
            return Language.UNKNOWN;
        }

        int kana = 0;
        int hangul = 0;
        int han = 0;
        int latin = 0;

        for (int codePoint : text.codePoints().toArray()) {
            if (isKana(codePoint)) {
                kana++;
            } else if (isHangul(codePoint)) {
                hangul++;
            } else if (isHan(codePoint)) {
                han++;
            } else if (isLatin(codePoint)) {
                latin++;
            }
        }

        int denominator = kana + hangul + han + latin;
        if (denominator == 0) {
            return Language.UNKNOWN;
        }
        if (kana * 100 >= denominator * KANA_THRESHOLD_PERCENT) {
            return Language.JA;
        }
        if (hangul * 100 >= denominator * HANGUL_THRESHOLD_PERCENT) {
            return Language.KO;
        }
        if (han * 100 >= denominator * HAN_THRESHOLD_PERCENT) {
            return Language.ZH;
        }
        if (latin * 100 >= denominator * LATIN_THRESHOLD_PERCENT) {
            return Language.EN;
        }
        return Language.UNKNOWN;
    }

    private static boolean isKana(int codePoint) {
        return (codePoint >= 0x3040 && codePoint <= 0x309F)
                || (codePoint >= 0x30A0 && codePoint <= 0x30FF);
    }

    private static boolean isHangul(int codePoint) {
        return (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0x1100 && codePoint <= 0x11FF)
                || (codePoint >= 0x3130 && codePoint <= 0x318F);
    }

    private static boolean isHan(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF);
    }

    private static boolean isLatin(int codePoint) {
        return (codePoint >= 'A' && codePoint <= 'Z')
                || (codePoint >= 'a' && codePoint <= 'z');
    }
}
