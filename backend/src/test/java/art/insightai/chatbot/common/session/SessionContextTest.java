package art.insightai.chatbot.common.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [Purpose] Verifies {@link SessionContext#currentUserId()} reads exactly the attribute
 *   {@link AnonymousSessionFilter} writes, and fails fast when the filter never ran.
 *   {@link RequestContextHolder} is exercised directly with a {@link MockHttpServletRequest} —
 *   no servlet container needed.
 */
class SessionContextTest {

    private final SessionContext sessionContext = new SessionContext();

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("[chat:AC-9] 필터가 설정한 request attribute에서 userId를 읽는다")
    void readsUserIdSetByFilter() {
        // given
        UUID userId = UUID.randomUUID();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SessionContext.USER_ID_ATTRIBUTE, userId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        // when
        UUID result = sessionContext.currentUserId();

        // then
        assertThat(result).isEqualTo(userId);
    }

    @Test
    @DisplayName("요청이 바인딩되지 않았으면 IllegalStateException을 던진다")
    void throwsWhenNoRequestBound() {
        // given: no RequestContextHolder attributes bound

        // when / then
        assertThatThrownBy(sessionContext::currentUserId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("필터가 아직 실행되지 않아 attribute가 없으면 IllegalStateException을 던진다")
    void throwsWhenAttributeMissing() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        // when / then
        assertThatThrownBy(sessionContext::currentUserId).isInstanceOf(IllegalStateException.class);
    }
}
