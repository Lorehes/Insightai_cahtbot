package art.insightai.chatbot.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import com.zaxxer.hikari.HikariDataSource;

/**
 * [Purpose] Permanent regression probe proving {@code classpath:/config/application.yaml}
 * actually loads and takes effect in a {@code @SpringBootTest} context that does NOT extend
 * {@link IntegrationTestBase} -- i.e. does not activate {@code @ActiveProfiles("test")}. Every
 * other test in this module either extends {@link IntegrationTestBase} (so it only ever proved
 * the profile-scoped {@code application-test.yaml} layer works) or is a plain unit test with no
 * Spring context at all -- neither exercises this file (review
 * 2026-08-27_document-ingestion-T4-round2.md High-1: the prior gap left this config-data layer
 * completely unverified, and deleting/renaming it did not fail any of the then-149 tests).
 * [Rationale] Two independent things are asserted, both required to actually close High-1: (1)
 * the six {@code spring.ai.openai.*}/{@code OPENROUTER_API_KEY} poison values this file sets
 * resolve through {@link Environment} exactly as documented -- the literal assertion the review
 * asked for -- and (2) {@code qa.config-application-yaml-loaded}, a key that exists ONLY in this
 * file (deliberately absent from {@code pom.xml}'s surefire {@code systemPropertyVariables}).
 * The second assertion is not redundant: Spring Boot's property-source precedence ranks Java
 * system properties ABOVE any config-data file, and {@code pom.xml}'s surefire configuration
 * forces the exact same six/seven poison values as JVM system properties for every test in this
 * module's default {@code ./mvnw test} run -- so under Maven, assertion (1) alone would keep
 * passing even if this file were deleted (the system-property layer would silently backfill the
 * same values), which is exactly the false-positive the review's "지워도 149/149 통과" finding
 * described. Assertion (2) has no such backstop anywhere else, so it is the one that actually
 * fails when this file is missing -- confirmed by hand (dev-log document-ingestion-T4 round-2
 * rework entry records this run): the compiled copy of this file under
 * {@code target/test-classes/config/application.yaml} was moved aside, this class was run alone
 * (bypassing Maven Surefire entirely -- see [Caution] for why that specific bypass matters here),
 * and BOTH assertions failed cleanly: {@code poisonValuesApplyWithoutActivatingTheTestProfile}
 * failed with the real {@code spring.ai.openai.base-url} resolving to the actual
 * {@code https://openrouter.ai/api/v1} from {@code infra/.env} instead of the poisoned
 * {@code http://127.0.0.1:1} -- i.e. this file's absence does not merely fail a test, it is the
 * exact "real app gets poisoned by a real key" scenario the review's High-1 finding warned about
 * -- and {@code canaryPropertyProvesThisFileIsTheSource} failed with a {@code null} property. The
 * file was then restored and this class was re-run to confirm both assertions pass again.
 * [Caution] Do not add {@code @ActiveProfiles("test")} or {@code extends IntegrationTestBase} to
 * this class -- that would activate {@code application-test.yaml} and defeat the entire point
 * (this class exists specifically to represent the "profile not active" case). This mirrors
 * {@code art.insightai.chatbot.migration.V1InitSchemaIntegrationTest}'s already-established safe
 * pattern of a plain (non-{@link IntegrationTestBase}) {@code @SpringBootTest}: the real
 * {@code OpenAiChatAutoConfiguration}/{@code OpenAiEmbeddingAutoConfiguration} beans DO get
 * created in this context (no autoconfigure exclusion applies outside the {@code test} profile),
 * but they are wired with this file's unroutable {@code base-url}/dummy {@code api-key}, so
 * nothing here can reach the real OpenRouter endpoint. {@link DatabaseGuardExtension} is applied
 * directly (not via {@link IntegrationTestBase}) so this context still refuses to let Flyway's
 * default auto-migration touch an unexpected database, same guarantee, without also pulling in
 * the {@code test} profile. When this file was actually removed for the manual verification
 * described above, {@code ApplicationContext} startup itself still succeeded -- Spring AI's
 * {@code OpenAiApi} client construction does not eagerly validate {@code api-key}/{@code
 * base-url} or make a network call at bean-creation time, it just wires whatever
 * {@code infra/.env} resolves ({@code https://openrouter.ai/api/v1}, a real key) -- so the
 * failure surfaced exactly where it should: as a clean {@link org.opentest4j.AssertionFailedError}
 * inside a passing context, not a context-startup crash. A reader re-running the same manual
 * check (move {@code target/test-classes/config/application.yaml} aside, run this class alone,
 * put it back) should expect the same clean two-assertion failure, not a boot failure.
 * Running this class in isolation (bypassing Maven Surefire, e.g. via a raw JUnit Platform
 * launcher against the compiled classes) matters specifically because {@code pom.xml}'s surefire
 * {@code systemPropertyVariables} forces the SAME six/seven poison values as JVM system
 * properties for every {@code ./mvnw test} run, and Spring Boot ranks system properties above any
 * config-data file -- so under a normal {@code ./mvnw test}, {@code
 * poisonValuesApplyWithoutActivatingTheTestProfile} alone would keep passing even with this file
 * deleted (the system-property layer backfills the same values); only the canary assertion is
 * immune to that masking under Maven, but bypassing Surefire for the manual check makes the
 * first assertion regress too, which is the stronger and more legible proof.
 * <p>
 * [2026-08-28, review 2026-08-28_X1-test-pool-size-round2.md correctness Low-3] This class was
 * also the cheapest place to close a second, unrelated gap in the same file: the X1 card's
 * {@code spring.datasource.hikari.maximum-pool-size}/{@code connection-timeout} keys (added below
 * the poison block) had no regression probe at all -- unlike the seven keys above, nothing failed
 * if they were deleted or their indentation slipped into the wrong YAML mapping (this file has a
 * prior {@code duplicate key spring} incident from X1 round 1). {@link
 * #hikariPoolIsExplicitlyBoundToTheDocumentedTestValues()} closes that gap by asserting against
 * the live {@link HikariDataSource} bean rather than {@link Environment#getProperty}: this context
 * (unlike {@code ParadeDbBm25SearchAdapterIntegrationTest}, the only place in this module that
 * wraps a {@code DataSource} in {@code support.CountingDataSource}) never replaces the
 * Spring-Boot-managed {@code DataSource} bean, so the injected instance is the same object Hikari
 * itself pools connections through -- proving "the property resolves" AND "the pool actually
 * applied it", not just the former.
 * <p>
 * [2026-08-28, review 2026-08-28_X1-test-pool-size-round3.md Medium-2] {@code minimum-idle} was
 * initially left unasserted above because a parallel card was still deciding its value; that
 * card confirmed {@code 1}, so this class now asserts it too. It was the one key among the three
 * where an omission was not a neutral gap: without a probe, deleting the YAML line does not fail
 * any test -- {@code HikariConfig.java:1137-1139} silently re-derives {@code minIdle =
 * maxPoolSize} and the pool reverts exactly to the state round2 High-1 flagged, with nothing here
 * to catch it.
 */
@SpringBootTest
@ExtendWith(DatabaseGuardExtension.class)
class ConfigApplicationYamlAppliesOutsideIntegrationTestBaseTest {

    @Autowired
    private Environment environment;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("IntegrationTestBase를 상속하지 않아도 config/application.yaml의 포이즌 값이 적용된다")
    void poisonValuesApplyWithoutActivatingTheTestProfile() {
        assertThat(environment.getProperty("spring.ai.openai.base-url"))
                .isEqualTo("http://127.0.0.1:1");
        // then: compared as a boolean, not via isEqualTo, so a failure message never renders
        // the actual resolved value -- if the defense line collapses and infra/.env's real
        // OPENROUTER_API_KEY backfills this property, AssertJ's isEqualTo failure message would
        // otherwise print that live credential verbatim into console/surefire output (review
        // 2026-08-27_document-ingestion-T4-round3.md security Medium-1).
        assertThat("dummy-test-key-do-not-call-openrouter"
                        .equals(environment.getProperty("spring.ai.openai.api-key")))
                .as("spring.ai.openai.api-key must be the poison value (actual value withheld: may be a live credential)")
                .isTrue();
        assertThat(environment.getProperty("spring.ai.openai.chat.base-url"))
                .isEqualTo("http://127.0.0.1:1");
        assertThat("dummy-test-key-do-not-call-openrouter"
                        .equals(environment.getProperty("spring.ai.openai.chat.api-key")))
                .as("spring.ai.openai.chat.api-key must be the poison value (actual value withheld: may be a live credential)")
                .isTrue();
        assertThat(environment.getProperty("spring.ai.openai.embedding.base-url"))
                .isEqualTo("http://127.0.0.1:1");
        assertThat("dummy-test-key-do-not-call-openrouter"
                        .equals(environment.getProperty("spring.ai.openai.embedding.api-key")))
                .as("spring.ai.openai.embedding.api-key must be the poison value (actual value withheld: may be a live credential)")
                .isTrue();
        assertThat("dummy-test-key-do-not-call-openrouter"
                        .equals(environment.getProperty("OPENROUTER_API_KEY")))
                .as("OPENROUTER_API_KEY must be the poison value (actual value withheld: may be a live credential)")
                .isTrue();
    }

    @Test
    @DisplayName("이 파일에만 있는 표시자 프로퍼티로 파일 삭제를 실제로 감지할 수 있다")
    void canaryPropertyProvesThisFileIsTheSource() {
        // then: unlike the seven properties above (also forced via pom.xml surefire
        // systemPropertyVariables, so they would keep passing even if this file vanished -- see
        // class Javadoc [Rationale]), this key exists nowhere else. It is the assertion that
        // actually regresses when config/application.yaml is deleted or renamed.
        assertThat(environment.getProperty("qa.config-application-yaml-loaded"))
                .isEqualTo("true");
    }

    @Test
    @DisplayName("HikariCP 풀 상한과 커넥션 타임아웃이 config/application.yaml에 명시된 값으로 실제 바인딩된다")
    void hikariPoolIsExplicitlyBoundToTheDocumentedTestValues() {
        // given: this context's DataSource bean is never wrapped (see class Javadoc) -- casting
        // straight to HikariDataSource, rather than reading the raw property via Environment,
        // proves the value actually reached the pool Hikari hands connections out from, not just
        // that a property source resolves it.
        assertThat(dataSource)
                .as("DataSource bean must be the Spring-Boot-managed HikariDataSource for this "
                        + "assertion to prove the pool -- not just a property source -- picked up "
                        + "config/application.yaml's values")
                .isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;

        // then: hardcoded, intentionally -- config/application.yaml:67-97 documents an extensive,
        // measured rationale for exactly 3, exactly 1, and exactly 5000ms (review
        // 2026-08-28_X1-test-pool-size.md Medium-2/3, round2 High-1, round3 Medium-1/2). The
        // arithmetic itself is not re-quoted here -- see that YAML comment block, not this one,
        // for the current numbers; duplicating it in two places is exactly how round 3 found a
        // stale copy (review 2026-08-28_X1-test-pool-size-round3.md Low-3). A future change to
        // any of the three numbers is a deliberate act that must revisit that YAML rationale, not
        // a silent drift -- so this test is meant to break and force a conscious update alongside
        // the YAML comment, rather than loosely accepting "anything but the framework default".
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(3);
        assertThat(hikari.getMinimumIdle()).isEqualTo(1);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(5000L);
    }
}
