package art.insightai.chatbot.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * [Purpose] Verifies the {@code app.session.max-anonymous-users} ceiling that {@code
 *   AnonymousUserService}/{@code AnonymousSessionFilter} gained in T4's rework (review High-2):
 *   a brand-new session issuance is rejected once the ceiling is reached, while replaying an
 *   already-existing cookie keeps working regardless of it (review qa-tester assignment).
 * [Rationale] The ceiling is pinned to {@code 0} via a class-level {@link TestPropertySource}
 *   (its own Spring context, separate from {@code SessionControllerIntegrationTest}'s own pin at
 *   {@code 100000} -- neither runs at the production default of 1000; {@code IntegrationTestBase}
 *   itself now pins 100000 too, BL-027, 2026-09-01, but this class's own {@code
 *   @TestPropertySource} still wins) so "any brand-new issuance is over the limit" holds deterministically without first
 *   padding the {@code users} table up to a real threshold. Under that ceiling, {@code
 *   GET /api/session} without a cookie can never itself produce a pre-existing anonymous row to
 *   reuse -- that request IS the rejected path -- so the row used by the reuse test is seeded
 *   directly via {@link JdbcTemplate}, bypassing {@code AnonymousUserService} entirely.
 * [Caution] This ceiling started as a T4 review addition (High-2) with no Spec §4 item behind it,
 *   so these test names originally carried no {@code [chat:AC-N]} prefix. The round-2 review
 *   (2026-08-26, correctness High-1) added {@code chat:AC-42} to Spec §4 to cover exactly this
 *   behavior, so every test below now carries that prefix and their pass/fail moves the evidence
 *   ledger. The reject-path test is a {@code @ParameterizedTest} (round-3 review, correctness
 *   Low-1) covering all three shapes AC-42's own wording lists for a new-issue request, not just
 *   the "no cookie" case.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.session.max-anonymous-users=0")
class SessionQuotaIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID seededUserId;

    @AfterEach
    void cleanUpSeededRow() {
        if (seededUserId != null) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", seededUserId);
            seededUserId = null;
        }
    }

    @ParameterizedTest(name = "[chat:AC-42] {0}")
    @MethodSource("newIssueRequestVariants")
    @DisplayName("[chat:AC-42] 상한을 초과하면 신규 발급이 필요한 모든 케이스(쿠키없음/형식오류/미상UUID)가 503으로 거부되고 Set-Cookie 없이 users 행도 늘지 않는다")
    void rejectsNewSessionIssuanceOnceCeilingReached(String description, String cookieValue) throws Exception {
        // given: every variant below reaches the "issue a brand-new row" branch of
        //   resolveOrCreate -- none of them resolve to an existing anonymous row
        int usersBefore = countAnonymousUsers();
        MockHttpServletRequestBuilder request = get("/api/session");
        if (cookieValue != null) {
            request = request.cookie(new Cookie("chatbot_sid", cookieValue));
        }

        // when
        MvcResult result = mockMvc.perform(request).andReturn();

        // then
        assertThat(result.getResponse().getStatus())
                .isEqualTo(jakarta.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).isEmpty();
        assertThat(countAnonymousUsers()).isEqualTo(usersBefore);
    }

    /**
     * [Purpose] The three shapes AC-42's own wording lists for "a new-issue request" -- no
     *   cookie at all, a syntactically malformed value, and a well-formed UUID that is simply
     *   absent from {@code users} -- all of which {@code AnonymousUserService#resolveOrCreate}
     *   folds into the same "issue a brand-new row" branch (AC-12's fallback logic).
     */
    private static Stream<Arguments> newIssueRequestVariants() {
        return Stream.of(
                Arguments.of("쿠키 없음", null),
                Arguments.of("형식 오류", "not-a-uuid"),
                Arguments.of("형식은 맞지만 DB에 없는 미상 UUID", UUID.randomUUID().toString()));
    }

    @Test
    @DisplayName("[chat:AC-42] 상한 도달 상태에서도 기존 쿠키 재사용 경로는 정상 동작한다")
    void reusesExistingCookieRegardlessOfCeiling() throws Exception {
        // given: a row seeded directly, bypassing AnonymousUserService/resolveOrCreate entirely
        seededUserId = jdbcTemplate.queryForObject(
                "INSERT INTO users (auth_provider) VALUES ('anonymous') RETURNING id", UUID.class);
        int usersBefore = countAnonymousUsers();

        // when
        MvcResult result = mockMvc.perform(get("/api/session")
                        .cookie(new Cookie("chatbot_sid", seededUserId.toString())))
                .andExpect(status().isOk())
                .andReturn();

        // then: same id echoed back, a fresh Set-Cookie issued, no new row despite the ceiling
        assertThat(readUserId(result)).isEqualTo(seededUserId);
        assertThat(result.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        assertThat(countAnonymousUsers()).isEqualTo(usersBefore);
    }

    private UUID readUserId(MvcResult result) throws Exception {
        String userId = JsonPath.read(result.getResponse().getContentAsString(), "$.userId");
        return UUID.fromString(userId);
    }

    private int countAnonymousUsers() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE auth_provider = 'anonymous'", Integer.class);
        return count == null ? 0 : count;
    }
}
