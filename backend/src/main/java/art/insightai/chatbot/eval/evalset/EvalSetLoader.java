package art.insightai.chatbot.eval.evalset;

import art.insightai.chatbot.common.lang.Language;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * [Purpose] Loads an eval-set YAML file (Spec appendix schema) into typed {@link EvalCase} records
 *   and validates the schema so malformed items fail loudly (AC-2) instead of being skipped.
 * [Rationale] Tech Review D-2 keeps eval types local — this loader never imports hybrid-rag/chat
 *   DTOs, so E2/E3/E4 can be built before those Specs finish. SnakeYAML is already on the classpath
 *   transitively via spring-boot-starter (it backs application.yaml parsing — confirmed via
 *   `mvnw dependency:tree`), so no new dependency is introduced (CLAUDE.md rule #7).
 * [Caution] The production file lives outside the repo at {@code {DOC_ROOT}/docs/eval/eval-set.yaml}
 *   (Spec §7 OQ-6) and is not committed; only test fixtures under
 *   {@code backend/src/test/resources/eval/} are versioned. This class does not enforce the
 *   "exactly 20 items" part of AC-1 — that is a property of the real production file, not a
 *   universal loader invariant, so it works the same for any item count.
 */
public final class EvalSetLoader {

    private static final String KEY_QUESTION = "question";
    private static final String KEY_EXPECT_SOURCE = "expect_source";
    private static final String KEY_EXPECT_CONTAINS = "expect_contains";
    private static final String KEY_EXPECT = "expect";
    private static final String KEY_LANG = "lang";
    private static final String NOT_FOUND_MARKER = "NOT_FOUND";

    private EvalSetLoader() {
    }

    /**
     * [Purpose] Load and validate an eval-set YAML file from the filesystem.
     * [Side effects] Reads the file at {@code yamlFile}.
     */
    public static EvalSet load(Path yamlFile) {
        try (InputStream in = Files.newInputStream(yamlFile)) {
            return load(in);
        } catch (IOException e) {
            throw new EvalSetValidationException("Failed to read eval-set file: " + yamlFile, e);
        }
    }

    /**
     * [Purpose] Load and validate an eval-set YAML document from an arbitrary stream (used by
     *   tests against classpath fixtures, and internally by {@link #load(Path)}).
     */
    public static EvalSet load(InputStream yaml) {
        Object root = new Yaml().load(yaml);
        if (!(root instanceof List<?> rawItems)) {
            throw new EvalSetValidationException(
                    "eval-set YAML root must be a list, got: " + (root == null ? "null" : root.getClass()));
        }

        List<EvalCase> cases = new ArrayList<>(rawItems.size());
        for (int i = 0; i < rawItems.size(); i++) {
            cases.add(toEvalCase(i, rawItems.get(i)));
        }
        return new EvalSet(List.copyOf(cases));
    }

    /**
     * [Rationale] AC-2's two judgement modes (D-3) are mutually exclusive on purpose: an item
     *   declares either {@code expect: NOT_FOUND} or the {@code expect_source}+{@code expect_contains}
     *   pair. Any other shape — both modes present at once, a partial found item, or none of the
     *   three keys — is rejected rather than guessed at. Presence of {@code expect} alone does not
     *   prove the other mode is absent, so both branches are checked explicitly instead of an
     *   {@code if (expect != null) ... else ...} that would silently treat a mixed item as
     *   {@code NotFound} (review follow_up: this previously misclassified a mixed item instead of
     *   rejecting it).
     */
    private static EvalCase toEvalCase(int index, Object rawItem) {
        if (!(rawItem instanceof Map<?, ?> rawMap)) {
            throw new EvalSetValidationException("eval-set item #" + index + " must be a mapping, got: " + rawItem);
        }

        String question = asString(rawMap.get(KEY_QUESTION));
        if (question == null || question.isBlank()) {
            throw new EvalSetValidationException("eval-set item #" + index + " is missing required field 'question'");
        }

        Object expect = rawMap.get(KEY_EXPECT);
        Object expectSource = rawMap.get(KEY_EXPECT_SOURCE);
        Object expectContains = rawMap.get(KEY_EXPECT_CONTAINS);
        Language lang = resolveLang(index, question, rawMap.get(KEY_LANG));

        if (expect != null) {
            if (!NOT_FOUND_MARKER.equals(String.valueOf(expect))) {
                throw new EvalSetValidationException(
                        "eval-set item #" + index + " ('" + question + "') has unsupported 'expect' value: "
                                + expect + " (only '" + NOT_FOUND_MARKER + "' is supported)");
            }
            if (expectSource != null || expectContains != null) {
                throw new EvalSetValidationException(
                        "eval-set item #" + index + " ('" + question + "') declares both 'expect' and "
                                + "'expect_source'/'expect_contains' — an item must use exactly one of the two "
                                + "judgement modes");
            }
            return new EvalCase.NotFound(question, lang);
        }

        if (expectSource != null || expectContains != null) {
            String source = asString(expectSource);
            List<String> contains = asStringList(index, question, expectContains);
            if (source == null || source.isBlank() || contains.isEmpty()) {
                throw new EvalSetValidationException(
                        "eval-set item #" + index + " ('" + question + "') requires both a non-blank "
                                + "'expect_source' and a non-empty 'expect_contains' list");
            }
            return new EvalCase.Found(question, source, contains, lang);
        }

        throw new EvalSetValidationException(
                "eval-set item #" + index + " ('" + question + "') has none of 'expect_source'/'expect_contains'/"
                        + "'expect' — every item must declare one of the two judgement modes");
    }

    /**
     * [Purpose] Resolves the optional {@code lang} key to a {@link Language}: absent means
     *   {@link Language#KO} (AC-7); present must be exactly one of the lowercase codes
     *   {@link Language#fromCode(String)} accepts.
     * [Rationale] AD-9 excludes {@code UNKNOWN} from the valid declared values — it is a
     *   detection outcome, not something an eval-set author may write — so any non-matching
     *   value (wrong case, {@code unknown}, blank, non-string) fails the same way an unsupported
     *   'expect' value does: a loud {@link EvalSetValidationException} naming the item index.
     * [AC] multilingual-support:AC-7
     */
    private static Language resolveLang(int index, String question, Object rawLang) {
        if (rawLang == null) {
            return Language.KO;
        }
        String code = asString(rawLang);
        return Language.fromCode(code).orElseThrow(() -> new EvalSetValidationException(
                "eval-set item #" + index + " ('" + question + "') has unsupported 'lang' value: "
                        + rawLang + " (expected one of 'ko', 'en', 'zh', 'ja')"));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> asStringList(int index, String question, Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> rawList)) {
            throw new EvalSetValidationException(
                    "eval-set item #" + index + " ('" + question + "') 'expect_contains' must be a list, got: " + value);
        }
        return rawList.stream().map(String::valueOf).toList();
    }
}
