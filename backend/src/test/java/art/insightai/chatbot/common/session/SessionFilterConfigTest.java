package art.insightai.chatbot.common.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * [Purpose] Verifies Tech Review A-9's {@code /api/*} mapping constraint holds on the actual
 *   {@link FilterRegistrationBean} produced by {@link SessionFilterConfig}, not merely on the
 *   filter's own request-handling behavior (review correctness Medium-1, card T4 rework).
 * [Rationale] A registration accidentally left at the Boot default ({@code /*}) would still make
 *   every {@link AnonymousSessionFilterTest} and
 *   {@code art.insightai.chatbot.chat.api.SessionControllerIntegrationTest} case pass, since none
 *   of them exercise a non-{@code /api} path -- so the url-pattern itself needs its own
 *   assertion, independent of the filter's mocked unit tests.
 */
class SessionFilterConfigTest {

    @Test
    @DisplayName("FilterRegistrationBean의 urlPatterns가 정확히 /api/* 하나다")
    void registersFilterOnlyUnderApiPath() {
        // given
        SessionFilterConfig config = new SessionFilterConfig();
        AnonymousSessionFilter filter = mock(AnonymousSessionFilter.class);

        // when
        FilterRegistrationBean<AnonymousSessionFilter> registration =
                config.anonymousSessionFilterRegistration(filter);

        // then
        assertThat(registration.getUrlPatterns()).containsExactly("/api/*");
    }

    @Test
    @DisplayName("FilterRegistrationBean의 order가 Ordered.HIGHEST_PRECEDENCE다")
    void registersFilterAtHighestPrecedence() {
        // given: T4 review round 2, Low (carried over twice) -- pinning the order had no
        //   regression test until now
        SessionFilterConfig config = new SessionFilterConfig();
        AnonymousSessionFilter filter = mock(AnonymousSessionFilter.class);

        // when
        FilterRegistrationBean<AnonymousSessionFilter> registration =
                config.anonymousSessionFilterRegistration(filter);

        // then
        assertThat(registration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
