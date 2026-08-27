package art.insightai.chatbot.common.session;

import art.insightai.chatbot.user.AnonymousUserQuotaExceededException;
import art.insightai.chatbot.user.AnonymousUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * [Purpose] Verifies {@link AnonymousSessionFilter}'s cookie parsing, delegation to
 *   {@link AnonymousUserService}, request-attribute propagation and {@code Set-Cookie} shape.
 *   No servlet container involved — request/response/chain are mocked (Tech Review A-9).
 */
@ExtendWith(MockitoExtension.class)
class AnonymousSessionFilterTest {

    @Mock
    private AnonymousUserService anonymousUserService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain filterChain;

    @Test
    @DisplayName("GET /api/models에 쿠키가 없으면 AnonymousUserService를 호출하지 않고 체인만 진행한다"
            + " (T5 review round 1, Medium-1)")
    void skipsSessionResolutionForCookielessModelsCatalogRequest() throws Exception {
        // given
        when(request.getCookies()).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/models");
        AnonymousSessionFilter filter = new AnonymousSessionFilter(anonymousUserService);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: no users row is minted, no session attribute or Set-Cookie header is written,
        //   but Cache-Control and the chain still proceed as usual
        verify(anonymousUserService, never()).resolveOrCreate(any());
        verify(request, never()).setAttribute(eq(SessionContext.USER_ID_ATTRIBUTE), any());
        verify(response, never()).addHeader(eq("Set-Cookie"), anyString());
        verify(filterChain).doFilter(request, response);
        verify(response).addHeader(eq("Cache-Control"), eq("no-store"));
    }

    @Test
    @DisplayName("GET /api/models이라도 기존 쿠키가 있으면 평소처럼 AnonymousUserService로 해석한다"
            + " (T5 review round 1, Medium-1)")
    void stillResolvesModelsCatalogRequestWhenCookieIsPresent() throws Exception {
        // given
        UUID existingUserId = UUID.randomUUID();
        Cookie sidCookie = new Cookie("chatbot_sid", existingUserId.toString());
        when(request.getCookies()).thenReturn(new Cookie[]{sidCookie});
        when(anonymousUserService.resolveOrCreate(existingUserId.toString())).thenReturn(existingUserId);
        AnonymousSessionFilter filter = new AnonymousSessionFilter(anonymousUserService);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: the sliding 90-day expiry (AC-11) still applies to a caller that already has a
        //   session, even on this path
        verify(anonymousUserService).resolveOrCreate(existingUserId.toString());
        verify(response).addHeader(eq("Set-Cookie"), contains(existingUserId.toString()));
    }

    @Test
    @DisplayName("[chat:AC-9] 쿠키가 없으면 null 값으로 AnonymousUserService를 호출해 신규 세션을 발급한다")
    void issuesNewSessionWhenNoCookiePresent() throws Exception {
        // given
        UUID newUserId = UUID.randomUUID();
        when(request.getCookies()).thenReturn(null);
        when(anonymousUserService.resolveOrCreate(null)).thenReturn(newUserId);
        AnonymousSessionFilter filter = new AnonymousSessionFilter(anonymousUserService);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: resolved id is exposed to SessionContext, the chain continues, and a fresh
        //   cookie is issued
        verify(request).setAttribute(eq(SessionContext.USER_ID_ATTRIBUTE), eq(newUserId));
        verify(filterChain).doFilter(request, response);
        verify(response).addHeader(eq("Set-Cookie"), contains(newUserId.toString()));
        // then: a normal (200-bound) response also carries Cache-Control: no-store (T4 review
        //   round 2, Low -- carried over twice with no regression test until now)
        verify(response).addHeader(eq("Cache-Control"), eq("no-store"));
    }

    @Test
    @DisplayName("[chat:AC-42] 상한 초과 예외가 발생하면 503을 직접 쓰고 체인을 진행하지 않으며 Cache-Control은 그대로 유지된다")
    void writes503AndStopsChainWhenQuotaExceeded() throws Exception {
        // given: the ceiling is reached on the "issue a brand-new row" branch (no cookie at all)
        when(request.getCookies()).thenReturn(null);
        when(anonymousUserService.resolveOrCreate(null))
                .thenThrow(new AnonymousUserQuotaExceededException(1000));
        AnonymousSessionFilter filter = new AnonymousSessionFilter(anonymousUserService);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then: the filter writes the 503 itself and never reaches DispatcherServlet or issues a
        //   cookie, since no controller/@ExceptionHandler runs ahead of this filter
        verify(response).sendError(eq(HttpServletResponse.SC_SERVICE_UNAVAILABLE), anyString());
        verify(filterChain, never()).doFilter(any(), any());
        verify(response, never()).addHeader(eq("Set-Cookie"), anyString());
        // then: Cache-Control is set before the exception path is even reached, so the 503
        //   response is also never cached
        verify(response).addHeader(eq("Cache-Control"), eq("no-store"));
    }

    @Test
    @DisplayName("[chat:AC-10] 기존 쿠키 값을 그대로 AnonymousUserService에 전달해 재사용한다")
    void passesExistingCookieValueThrough() throws Exception {
        // given
        UUID existingUserId = UUID.randomUUID();
        Cookie sidCookie = new Cookie("chatbot_sid", existingUserId.toString());
        when(request.getCookies()).thenReturn(new Cookie[]{sidCookie});
        when(anonymousUserService.resolveOrCreate(existingUserId.toString())).thenReturn(existingUserId);
        AnonymousSessionFilter filter = new AnonymousSessionFilter(anonymousUserService);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then
        verify(anonymousUserService).resolveOrCreate(existingUserId.toString());
        verify(request).setAttribute(eq(SessionContext.USER_ID_ATTRIBUTE), eq(existingUserId));
    }

    @Test
    @DisplayName("[chat:AC-12] DB에 없는 임의 값도 그대로 전달하고, 새로 발급된 id를 Set-Cookie에 싣는다")
    void forwardsUnknownCookieValueAndAdoptsFreshId() throws Exception {
        // given: the filter never judges validity itself — AnonymousUserService owns the AC-12
        //   fallback, the filter only forwards whatever value the cookie carried
        Cookie unknownCookie = new Cookie("chatbot_sid", "not-a-uuid");
        UUID freshUserId = UUID.randomUUID();
        when(request.getCookies()).thenReturn(new Cookie[]{unknownCookie});
        when(anonymousUserService.resolveOrCreate("not-a-uuid")).thenReturn(freshUserId);
        AnonymousSessionFilter filter = new AnonymousSessionFilter(anonymousUserService);

        // when
        filter.doFilterInternal(request, response, filterChain);

        // then
        verify(response).addHeader(eq("Set-Cookie"), contains(freshUserId.toString()));
    }

    @Test
    @DisplayName("[chat:AC-11] Set-Cookie 헤더가 chatbot_sid/HttpOnly/Path=//SameSite=Lax/Max-Age=7776000를 갖고 Secure는 없다")
    void buildsSetCookieHeaderWithExactAttributes() {
        // given / when
        UUID userId = UUID.randomUUID();
        String header = AnonymousSessionFilter.buildSetCookieHeader(userId);

        // then: split on "; " and compare the attribute set exactly -- a plain `contains("Path=/")`
        //   would also pass for the wrong value "Path=/api" (review correctness Medium-2)
        String[] attributes = header.split("; ");
        assertThat(attributes[0]).isEqualTo("chatbot_sid=" + userId);
        assertThat(Arrays.copyOfRange(attributes, 1, attributes.length))
                .containsExactlyInAnyOrder("Path=/", "Max-Age=7776000", "HttpOnly", "SameSite=Lax");
    }
}
