package art.insightai.chatbot.support;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Permanent regression probe for the eval-tag Maven Surefire split configured in
 * {@code backend/pom.xml} (default build: {@code excludedGroups=eval | eval-infra}; {@code -Peval}
 * profile: {@code groups=eval}). See {@link EvalInfraTagProbeTest} for the sibling probe covering
 * the {@code eval-infra} half of that expression (BL-024, 2026-09-01) -- this class only proves
 * {@code eval} stays excluded, not {@code eval-infra}; the two tags regress independently (e.g. an
 * edit that narrows {@code excludedGroups} back to {@code eval} alone). This class asserts nothing
 * about production code -- its only signal is
 * whether it shows up at all in the JUnit Platform "Tests run" summary. If the exclusion
 * silently regresses (e.g. a future pom.xml edit drops {@code excludedGroups} or the
 * {@code combine.self="override"} on the profile side), a plain {@code ./mvnw test} would start
 * executing eval-tagged tests -- and per this mechanism's rationale (chat:AC-35, protecting
 * against real OpenRouter calls during ordinary builds), a future real eval test would then make
 * a live call on every default build.
 * [Rationale] eval-usage-dashboard Spec card E0 review rework (Medium-1/Medium-2, correctness):
 * the original verification used a temporary probe of the same kind (named
 * {@code TempEvalTagProbeTest}, see backend.jsonl E0 entry) that was deleted immediately after
 * a narrow {@code -Dtest}-scoped check, so the claim "default build excludes eval" could not be
 * reproduced later. A separate, since-superseded dev-log entry (chat T1 전체 회귀,
 * backend.jsonl line ~15) recorded that same class name appearing in an *unscoped*
 * "8/8 전부 통과" run in a way that read as if the default build had executed it. This class is
 * kept in the repository (not deleted after this verification) specifically so the exclusion
 * claim stays re-checkable by anyone, at any time, without recreating a probe first.
 * [Caution] Do not add real assertions, mocks, or side effects here -- its only job is to exist
 * and be discoverable so the Surefire summary's test count changes depending on whether it ran.
 * Re-verify with (run from {@code backend/}, no {@code -Dtest} scoping):
 *   ./mvnw test          (expect: this class does NOT appear in the executed test list)
 *   ./mvnw test -Peval    (expect: this class DOES appear, 1 test, PASS)
 *   ./mvnw test -Peval-infra (expect: this class does NOT appear -- only {@link
 *     EvalInfraTagProbeTest} should)
 */
@Tag("eval")
class EvalTagProbeTest {

    @Test
    @DisplayName("eval 태그 프로브 — 실행 여부 자체가 신호이며 단언 내용은 무의미하다")
    void probe() {
        // given / when / then: presence in the Surefire summary is the entire test.
        assertTrue(true);
    }
}
