package art.insightai.chatbot.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import art.insightai.chatbot.support.IntegrationTestBase;
import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * [Purpose] Confirms {@code server.address} actually resolves to a loopback address in a fully
 *   booted {@code ApplicationContext}, not merely that {@code application.yaml:116} contains the
 *   literal {@code 127.0.0.1}.
 * [Rationale] T4 review round 2 (Medium-3) flagged that no test exercised this property at all --
 *   {@code application.yaml} imports {@code infra/.env} as a property source ({@code
 *   spring.config.import}), so a future {@code server.address} (or a relaxed-binding match such
 *   as {@code SERVER_ADDRESS}) added there could silently widen the bind address back to
 *   {@code 0.0.0.0} without any file in this repository visibly changing. Extending {@link
 *   IntegrationTestBase} boots the same full context (including that {@code infra/.env} import)
 *   that production actually uses, rather than a narrower {@code ApplicationContextRunner} slice
 *   that would not see an override coming from that file. The loopback check accepts any
 *   loopback-resolving value ({@code 127.0.0.1}, {@code localhost}, ...) rather than a literal
 *   string match, since {@link InetAddress#isLoopbackAddress()} is what actually determines
 *   whether Tomcat binds to a single local interface (the property's real effect) or something
 *   broader.
 * [Caution] This only proves the *resolved* property value is loopback; it does not open a real
 *   socket to prove Tomcat bound where this property says it would (that would require an
 *   embedded-web-server test slice, which this project's {@code @SpringBootTest} usage does not
 *   otherwise exercise).
 */
class ServerAddressConfigurationTest extends IntegrationTestBase {

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("server.address 프로퍼티가 실제로 루프백 주소로 해석된다")
    void serverAddressResolvesToLoopbackAddress() throws Exception {
        // given: the fully booted context, including infra/.env as a property source
        String resolved = environment.getProperty("server.address");

        // when / then: the value is present and resolves to a loopback interface -- not merely
        // "equal to the literal string 127.0.0.1" in application.yaml
        assertThat(resolved).isNotBlank();
        assertThat(InetAddress.getByName(resolved).isLoopbackAddress()).isTrue();
    }
}
