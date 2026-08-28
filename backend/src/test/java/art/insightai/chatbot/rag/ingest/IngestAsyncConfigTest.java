package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * [Purpose] Pins the {@code ingestExecutor} pool shape that {@link IngestAsyncConfig}'s [Caution]
 *   derives from a hard external constraint, so that raising it silently is not possible.
 * [Rationale] The chosen values are not a style preference. {@code
 *   backend/src/test/resources/config/application.yaml} fixes HikariCP at {@code
 *   maximum-pool-size: 3} (card X1, asserted by {@code
 *   ConfigApplicationYamlAppliesOutsideIntegrationTestBaseTest} and not changeable), and every
 *   ingest worker borrows a pooled connection for its {@code REQUIRES_NEW} status transitions and
 *   its chunk SQL. A pool larger than 2 workers would let background ingestion alone exhaust those
 *   3 connections and make any concurrent borrower fail deterministically after {@code
 *   connection-timeout: 5000} -- a symptom that looks like flakiness but is accumulation. This
 *   test fails at exactly the moment someone changes a value that the connection arithmetic
 *   depends on, which is the only point at which the arithmetic can still be re-checked cheaply.
 *   These assertions are also Tech Review AD-11's literal values (core2/max2/queue50, AbortPolicy,
 *   never {@code CallerRunsPolicy} -- which would run ingestion on the request thread and break
 *   document-ingestion:AC-1).
 * [Caution] No AC tag: none of the Spec's acceptance criteria talks about pool sizing. This is a
 *   regression guard for a cross-cutting environmental constraint, not AC evidence.
 */
class IngestAsyncConfigTest {

    @Test
    @DisplayName("[document-ingestion §5] ingestExecutor 는 테스트 커넥션 풀(3) 안에 들어가도록 core2/max2/queue50 으로 고정된다")
    void ingestExecutorPoolIsBoundedWithinTheJdbcConnectionBudget() {
        // given
        IngestAsyncConfig config = new IngestAsyncConfig();

        // when
        ThreadPoolTaskExecutor executor = config.ingestExecutor();

        // then: at most 2 workers, so 2 of the 3 available JDBC connections at worst, leaving one
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        assertThat(executor.getQueueCapacity()).isEqualTo(50);
    }

    @Test
    @DisplayName("[document-ingestion §5] ingestExecutor 는 큐가 가득 차면 AbortPolicy 로 거부한다 (CallerRunsPolicy 금지)")
    void ingestExecutorRejectsWithAbortPolicyRatherThanRunningOnTheCallerThread() {
        // given
        IngestAsyncConfig config = new IngestAsyncConfig();
        ThreadPoolTaskExecutor executor = config.ingestExecutor();

        // when
        executor.initialize();

        // then
        try {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("[document-ingestion §5] IngestAsyncConfig 는 @EnableAsync 를 켜고 ingestExecutor 라는 이름으로 빈을 등록한다")
    void enablesAsyncAndRegistersTheExecutorUnderTheNameTheListenerReferences() throws Exception {
        // given / when
        EnableAsync enableAsync = IngestAsyncConfig.class.getAnnotation(EnableAsync.class);
        Bean bean = IngestAsyncConfig.class.getMethod("ingestExecutor").getAnnotation(Bean.class);

        // then: the bean name is what DocumentUploadedEventListener's @Qualifier resolves, and
        // applicationTaskExecutor is an AsyncTaskExecutor too, so the name is load-bearing.
        // @EnableAsync is asserted as a kept decision, not as a live dependency: since the T8
        // rework (review Medium-1) the listener submits to the executor explicitly and no @Async
        // annotation remains in this codebase. It stays so that the next @Async added anywhere
        // does not silently run inline -- see IngestAsyncConfig's [Rationale]
        assertThat(enableAsync).isNotNull();
        assertThat(bean).isNotNull();
        assertThat(bean.value()).containsExactly(IngestAsyncConfig.INGEST_EXECUTOR_BEAN_NAME);
        assertThat(IngestAsyncConfig.INGEST_EXECUTOR_BEAN_NAME).isEqualTo("ingestExecutor");
    }
}
