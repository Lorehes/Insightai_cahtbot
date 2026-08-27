package art.insightai.chatbot.chat.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import art.insightai.chatbot.support.IntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * [Purpose] Verifies {@code GET /api/session} end-to-end through the real servlet filter chain
 *   (chat Spec Tech Review A-9/A-11) -- unlike {@code AnonymousSessionFilterTest} and
 *   {@code AnonymousUserServiceTest} (T4 unit tests, mocked request/response/repository), this
 *   drives the actual {@link art.insightai.chatbot.common.session.AnonymousSessionFilter} bean
 *   registered by {@link art.insightai.chatbot.common.session.SessionFilterConfig} against the
 *   live ParadeDB container via {@link MockMvc}, so a wiring mistake in either the
 *   {@code FilterRegistrationBean} url-pattern or {@code open-in-view: false} would surface here
 *   even though every individual unit stayed green.
 * [Rationale] Each test tracks the {@code users.id} value(s) it caused to be created and deletes
 *   them in {@link #cleanUpInsertedRows()} via a direct {@link JdbcTemplate} statement, mirroring
 *   {@code ChunkRepositoryIntegrationTest}/{@code MessageRepositoryIntegrationTest} -- this suite
 *   does not rely on Spring's test-transaction rollback (none of this module's integration tests
 *   do), because {@link art.insightai.chatbot.user.AnonymousUserService#resolveOrCreate} commits
 *   its own {@code @Transactional} write independently of the MockMvc request thread.
 *   {@link #countAnonymousUsers()} snapshots the {@code users} row count immediately before each
 *   assertion rather than assuming the table starts empty, since the same ParadeDB container is
 *   shared across this module's other integration test classes (Surefire runs them sequentially,
 *   not in parallel, so a snapshot taken right before a MockMvc call is not racing another test).
 *   {@code app.session.max-anonymous-users} is pinned to a large value via {@link
 *   TestPropertySource} (T4 review round 2, correctness Medium-4): AC-9/10/12 all exercise the
 *   "issue a brand-new row" branch, which throws {@code AnonymousUserQuotaExceededException} once
 *   the shared ParadeDB container's {@code users} table accumulates past the 1000 default -- these
 *   three ACs would then fail for a reason unrelated to what they test. {@code
 *   SessionQuotaIntegrationTest} owns the ceiling-reached behavior itself with its own, separately
 *   pinned value.
 * [Caution] {@code @AutoConfigureMockMvc} is required (not just {@code IntegrationTestBase}'s bare
 *   {@code @SpringBootTest}) so the {@code FilterRegistrationBean<AnonymousSessionFilter>} bean is
 *   actually wired into the {@link MockMvc} filter chain -- without it, requests reach
 *   {@link SessionController} directly and every AC in this class would silently pass for the
 *   wrong reason (no filter run at all rather than a correctly-behaving one). Response bodies are
 *   read with {@link JsonPath} rather than an autowired {@code ObjectMapper} bean: Spring Boot 4's
 *   default Jackson auto-configuration registers {@code tools.jackson.databind.ObjectMapper}
 *   (Jackson 3), not the classic {@code com.fasterxml.jackson.databind.ObjectMapper} this test
 *   would otherwise have autowired -- confirmed via {@code mvnw dependency:tree} during this
 *   card's test authoring, so a future test in this module should check which Jackson major
 *   version is actually on the classpath before assuming the Jackson 2 API is wired.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.session.max-anonymous-users=100000")
class SessionControllerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> insertedUserIds = new ArrayList<>();

    @AfterEach
    void cleanUpInsertedRows() {
        for (UUID userId : insertedUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
        insertedUserIds.clear();
    }

    @Test
    @DisplayName("[chat:AC-9] 쿠키 없이 GET /api/session 을 호출하면 Set-Cookie가 1건 발급되고 "
            + "auth_provider='anonymous' 행이 1행 늘며 본문에 그 식별자가 실린다")
    void issuesNewSessionAndCreatesUserRowWhenNoCookiePresent() throws Exception {
        // given
        int usersBefore = countAnonymousUsers();

        // when
        MvcResult result = mockMvc.perform(get("/api/session"))
                .andExpect(status().isOk())
                .andReturn();

        // then: exactly one Set-Cookie header, whose value matches the body's userId
        List<String> setCookieHeaders = result.getResponse().getHeaders("Set-Cookie");
        assertThat(setCookieHeaders).hasSize(1);

        UUID userId = readUserId(result);
        insertedUserIds.add(userId);
        assertThat(setCookieHeaders.get(0)).contains(userId.toString());

        // then: the users table grew by exactly one anonymous row, which is the returned id
        assertThat(countAnonymousUsers()).isEqualTo(usersBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT auth_provider FROM users WHERE id = ?", String.class, userId))
                .isEqualTo("anonymous");
    }

    @Test
    @DisplayName("[chat:AC-10] AC-9이 발급한 쿠키로 재요청하면 동일한 식별자를 돌려주고 users 행이 늘지 않는다")
    void reusesExistingSessionWhenValidCookiePresent() throws Exception {
        // given: a first call establishes a session and its cookie
        MvcResult first = mockMvc.perform(get("/api/session")).andReturn();
        UUID firstUserId = readUserId(first);
        insertedUserIds.add(firstUserId);
        String cookieValue = extractCookieValue(first.getResponse().getHeader("Set-Cookie"));
        int usersBeforeSecondCall = countAnonymousUsers();

        // when: a second call replays that cookie
        MvcResult second = mockMvc.perform(get("/api/session")
                        .cookie(new Cookie("chatbot_sid", cookieValue)))
                .andExpect(status().isOk())
                .andReturn();

        // then
        UUID secondUserId = readUserId(second);
        assertThat(secondUserId).isEqualTo(firstUserId);
        assertThat(countAnonymousUsers()).isEqualTo(usersBeforeSecondCall);
    }

    @Test
    @DisplayName("[chat:AC-11] Set-Cookie 헤더가 chatbot_sid/HttpOnly/Path=//SameSite=Lax/"
            + "Max-Age=7776000를 갖고 Secure는 없다 (실제 서블릿 응답)")
    void setCookieHeaderCarriesExactAttributesOverRealServletResponse() throws Exception {
        // when
        MvcResult result = mockMvc.perform(get("/api/session")).andReturn();

        // then
        UUID userId = readUserId(result);
        insertedUserIds.add(userId);
        String header = result.getResponse().getHeader("Set-Cookie");

        // split on "; " and compare the attribute set exactly -- a plain `contains("Path=/")`
        //   would also pass for the wrong value "Path=/api" (review correctness Medium-2). One
        //   attribute is dropped before the exact-set comparison: MockHttpServletResponse
        //   re-parses the raw "Set-Cookie" string we wrote into a MockCookie and re-serializes
        //   it, appending an "Expires=" attribute computed from Max-Age (mirrors real servlet
        //   container behavior for HTTP/1.0 compatibility) -- this is not a value
        //   AnonymousSessionFilter controls or AC-11 constrains, and it is wall-clock-derived so
        //   it cannot be pinned to a literal without flaking.
        List<String> attributes = new ArrayList<>(Arrays.asList(header.split("; ")));
        String cookieNameAndValue = attributes.remove(0);
        attributes.removeIf(attribute -> attribute.startsWith("Expires="));
        assertThat(cookieNameAndValue).isEqualTo("chatbot_sid=" + userId);
        assertThat(attributes)
                .containsExactlyInAnyOrder("Path=/", "Max-Age=7776000", "HttpOnly", "SameSite=Lax");
    }

    @Test
    @DisplayName("[chat:AC-12] DB에 없거나 형식이 잘못된 쿠키 값을 보내도 500이 아니라 "
            + "200과 새 Set-Cookie를 반환하고 새 users 행이 생긴다")
    void issuesFreshSessionWhenCookieIsMalformedOrUnknown() throws Exception {
        // given
        int usersBefore = countAnonymousUsers();

        // when: not a well-formed UUID at all
        MvcResult malformedResult = mockMvc.perform(get("/api/session")
                        .cookie(new Cookie("chatbot_sid", "not-a-uuid")))
                .andExpect(status().isOk())
                .andReturn();
        UUID malformedUserId = readUserId(malformedResult);
        insertedUserIds.add(malformedUserId);

        // then: no 5xx, exactly one fresh Set-Cookie, one new row
        assertThat(malformedResult.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        assertThat(countAnonymousUsers()).isEqualTo(usersBefore + 1);

        // when: syntactically a UUID, but never persisted
        UUID unknownId = UUID.randomUUID();
        MvcResult unknownResult = mockMvc.perform(get("/api/session")
                        .cookie(new Cookie("chatbot_sid", unknownId.toString())))
                .andExpect(status().isOk())
                .andReturn();
        UUID unknownResultUserId = readUserId(unknownResult);
        insertedUserIds.add(unknownResultUserId);

        // then: a different, freshly created id -- not the unknown value echoed back --
        //   a fresh Set-Cookie for that id, and a second new row (review Low, round-2 follow-up:
        //   the malformed case above already asserted Set-Cookie; this branch had not)
        assertThat(unknownResultUserId).isNotEqualTo(unknownId);
        assertThat(unknownResult.getResponse().getHeaders("Set-Cookie")).hasSize(1);
        assertThat(unknownResult.getResponse().getHeader("Set-Cookie"))
                .contains(unknownResultUserId.toString());
        assertThat(countAnonymousUsers()).isEqualTo(usersBefore + 2);
    }

    /**
     * [Purpose] Extracts {@code $.userId} from a {@code GET /api/session} response body via
     *   {@link JsonPath} (see class-level [Caution] for why this avoids an autowired
     *   {@code ObjectMapper} bean).
     */
    private UUID readUserId(MvcResult result) throws Exception {
        String userId = JsonPath.read(result.getResponse().getContentAsString(), "$.userId");
        return UUID.fromString(userId);
    }

    private int countAnonymousUsers() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE auth_provider = 'anonymous'", Integer.class);
        return count == null ? 0 : count;
    }

    private static String extractCookieValue(String setCookieHeader) {
        String firstAttribute = setCookieHeader.split(";", 2)[0];
        return firstAttribute.substring(firstAttribute.indexOf('=') + 1);
    }
}
