package art.insightai.chatbot.common.session;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * [Purpose] Restricts {@link AnonymousSessionFilter} to {@code /api/*} (Tech Review A-9), since
 *   Spring Boot otherwise auto-registers any {@code Filter} bean under {@code /*}.
 * [Rationale] Wrapping the filter bean in an explicit {@link FilterRegistrationBean} suppresses
 *   Spring Boot's automatic {@code /*} registration for that same bean instance — Boot's
 *   {@code ServletContextInitializerBeans} skips a {@code Filter} it finds already wrapped by a
 *   registration bean — so only this narrowed mapping takes effect. The order is pinned to
 *   {@link Ordered#HIGHEST_PRECEDENCE} (T4 review round 2, Low, carried over twice): this is
 *   currently the only application-defined {@code Filter} bean, so today's behavior would be
 *   identical without it, but {@code AnonymousSessionFilter} must run before any future filter
 *   that reads {@link SessionContext} or authenticates a request — leaving the order implicit
 *   would let a later-added filter bean silently race ahead of it depending on bean-registration
 *   order alone. Two caveats on the {@code HIGHEST_PRECEDENCE} (= {@code Integer.MIN_VALUE})
 *   value itself: (a) Spring Boot's own built-in {@code OrderedCharacterEncodingFilter} also
 *   registers at this same precedence, so relative order between the two is not actually
 *   guaranteed by this value alone — harmless today because that filter only reads the request
 *   encoding and writes response headers, never {@link SessionContext}; (b) this value sits ahead
 *   of where a future Spring Security filter chain would install ({@code
 *   SecurityProperties.DEFAULT_FILTER_ORDER = -100}), which needs to be re-examined once Phase 2
 *   authentication is introduced.
 */
@Configuration
public class SessionFilterConfig {

    @Bean
    public FilterRegistrationBean<AnonymousSessionFilter> anonymousSessionFilterRegistration(
            AnonymousSessionFilter anonymousSessionFilter) {
        FilterRegistrationBean<AnonymousSessionFilter> registration =
                new FilterRegistrationBean<>(anonymousSessionFilter);
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
