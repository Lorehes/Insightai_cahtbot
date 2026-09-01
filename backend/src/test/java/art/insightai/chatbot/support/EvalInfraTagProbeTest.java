package art.insightai.chatbot.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Permanent regression probe for the {@code eval-infra} half of the tag-exclusion
 * expression in {@code backend/pom.xml} (default build: {@code excludedGroups=eval | eval-infra};
 * {@code -Peval-infra} profile: {@code groups=eval-infra}) -- BL-024, 2026-09-01 (security review:
 * {@code EvalTagProbeTest} only covers {@code @Tag("eval")} and cannot detect the expression
 * regressing from {@code eval | eval-infra} back to {@code eval} alone). Sibling of {@link
 * EvalTagProbeTest}; see that class's Javadoc for why a one-off {@code -Dtest}-scoped check is not
 * an acceptable substitute for a permanent probe (eval-usage-dashboard E0 review, Medium-1/-2).
 * [Rationale] If this exclusion silently regresses, {@code
 * DocumentRealEmbeddingIndexingIntegrationTest} -- the only test class in this suite that injects
 * real {@code OPENROUTER_API_KEY} credentials via {@code @DynamicPropertySource} -- would run on
 * every default {@code ./mvnw test}, making a real (billed) OpenRouter embedding call and a real
 * DB write/delete on every ordinary build (chat:AC-35's "zero real OpenRouter calls" guarantee).
 * [Caution] Do not add real assertions, mocks, or side effects here -- its only job is to exist
 * and be discoverable so the Surefire summary's test count changes depending on whether it ran.
 * Re-verify with (run from {@code backend/}, no {@code -Dtest} scoping):
 *   ./mvnw test              (expect: this class does NOT appear in the executed test list)
 *   ./mvnw test -Peval-infra (expect: this class DOES appear, 1 test, PASS)
 *   ./mvnw test -Peval       (expect: this class does NOT appear -- only {@link EvalTagProbeTest}
 *     should)
 */
@Tag("eval-infra")
class EvalInfraTagProbeTest {

    @Test
    @DisplayName("eval-infra 태그 프로브 — 실행 여부 자체가 신호이며 단언 내용은 무의미하다")
    void probe() {
        // given / when / then: presence in the Surefire summary is the entire test.
        assertTrue(true);
    }
}
