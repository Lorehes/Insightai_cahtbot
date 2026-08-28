package art.insightai.chatbot.support;

import org.hibernate.cfg.AvailableSettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * [Purpose] Wires a shared {@link CapturingStatementInspector} into Hibernate as the session
 * factory's {@code StatementInspector} (review 2026-08-27_chat-T8-round2.md correctness Low-1).
 * [Rationale] {@link HibernatePropertiesCustomizer} is Spring Boot's own extension point for
 * contributing entries to the Hibernate properties map before the {@code SessionFactory} boots --
 * confirmed against this project's pinned {@code spring-boot-hibernate:4.1.1} and {@code
 * hibernate-core:7.4.5.Final} sources: {@code HibernateJpaConfiguration} collects every {@code
 * HibernatePropertiesCustomizer} bean via {@code ObjectProvider} and applies each one to the
 * properties map handed to the entity manager factory builder, and Hibernate's own {@code
 * StrategySelectorImpl#resolveStrategy} special-cases a property value that is already an
 * instance of the target strategy type ({@code strategy.isInstance(strategyReference)}) by
 * returning that exact instance instead of instantiating a new one from a class name. Putting the
 * {@link CapturingStatementInspector} bean instance itself under {@link
 * AvailableSettings#STATEMENT_INSPECTOR} (rather than a class-name string) is what lets a test
 * {@code @Autowired} that same instance afterwards and read back what Hibernate actually sent to
 * the driver -- a class-name-only registration would hand Hibernate a *different*, unreachable
 * instance instead.
 * [Caution] Not part of {@link IntegrationTestBase} -- {@code @Import} it explicitly on the one
 * test class that needs SQL-shape assertions (mirrors this package's existing per-concern
 * {@code @Import} convention, e.g. {@link StubChatModelConfig}/{@link StubEmbeddingModelConfig}),
 * rather than paying the (small but nonzero) per-statement recording cost in every
 * {@code @SpringBootTest} in this module.
 */
@TestConfiguration
public class CapturingStatementInspectorConfig {

    @Bean
    public CapturingStatementInspector capturingStatementInspector() {
        return new CapturingStatementInspector();
    }

    @Bean
    public HibernatePropertiesCustomizer capturingStatementInspectorCustomizer(
            CapturingStatementInspector capturingStatementInspector) {
        return hibernateProperties ->
                hibernateProperties.put(AvailableSettings.STATEMENT_INSPECTOR, capturingStatementInspector);
    }
}
