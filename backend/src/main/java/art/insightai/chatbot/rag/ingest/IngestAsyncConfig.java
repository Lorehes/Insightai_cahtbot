package art.insightai.chatbot.rag.ingest;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * [Purpose] Defines the single dedicated {@code ingestExecutor} thread pool that
 *   {@link DocumentUploadedEventListener} runs the ingestion pipeline on, and keeps Spring's
 *   {@code @Async} support switched on for the application.
 * [Rationale] Placement: document-ingestion Spec §5 "인프라·설정" parenthetically suggested
 *   {@code common/config}, but this class lives in {@code rag/ingest} instead, because
 *   (a) the project CLAUDE.md packages by domain rather than by layer ("저장소나 검색 방식을 바꿀 때
 *   변경 범위가 하나에 갇힌다"), and (b) every existing {@code @Configuration} class in this
 *   codebase already follows that rule -- {@code rag/retrieval/RagAdvisorConfig},
 *   {@code rag/retrieval/RerankRestClientConfig}, {@code common/session/SessionFilterConfig} --
 *   while {@code common/config} holds only {@code AppProperties}. This executor is used by
 *   exactly one collaborator in exactly one package, so co-locating it keeps that established
 *   pattern intact and avoids a shared-file edit in {@code common/config} (see [Caution]).
 *   <p>
 *   Pool shape is Tech Review AD-11's confirmed value: {@code core=2 / max=2 / queue=50} with
 *   {@link ThreadPoolExecutor.AbortPolicy}. {@code CallerRunsPolicy} is explicitly forbidden by
 *   AD-11 because it would run ingestion on the caller thread and thereby break
 *   document-ingestion:AC-1 ("응답 시점에 파싱·임베딩은 수행되지 않는다"). The bounded queue exists
 *   for the same reason the pool is bounded -- an unbounded queue would put no ceiling at all on
 *   how much work can pile up behind the OpenRouter embedding rate limit (기획서 R11, CLAUDE.md
 *   금지 P2). What {@code AbortPolicy} rejects is <b>not</b> discarded:
 *   {@link DocumentUploadedEventListener} catches the rejection and closes the document out as
 *   FAILED (document-ingestion:AC-16); read that class's [Rationale] before changing anything
 *   here, because the reason it submits explicitly instead of using {@code @Async} is that
 *   {@code @Async} made this policy's rejections uncatchable.
 *   <p>
 *   {@code @EnableAsync} is retained even though the T8 rework left <b>no {@code @Async}
 *   annotation anywhere in this codebase</b>. It is kept rather than dropped because dropping it
 *   trades a piece of inert configuration for a silent failure: the next {@code @Async} added in
 *   any package would then run inline on the caller's thread with no error and no log, which is
 *   the exact class of defect this Spec has been chasing (NR-3, Medium-1). Removing it is a
 *   deliberate decision for a later card, not an incidental cleanup.
 * [Caution] <b>Thread count vs. the test JDBC connection pool.</b> Do not raise
 *   {@link #CORE_POOL_SIZE}/{@link #MAX_POOL_SIZE} without re-deriving this arithmetic first.
 *   {@code backend/src/test/resources/config/application.yaml} pins HikariCP to
 *   {@code maximum-pool-size: 3} / {@code minimum-idle: 1} / {@code connection-timeout: 5000}
 *   (card X1; {@code ConfigApplicationYamlAppliesOutsideIntegrationTestBaseTest} asserts all
 *   three, and those values may not be changed). Every pipeline step this executor runs borrows
 *   a pooled connection: each {@link DocumentStatusTransitioner} call opens its own
 *   {@code REQUIRES_NEW} transaction, and {@code VectorStore.add()} /
 *   {@link ChunkRepository} run their own statements. {@link DocumentIngestionPipeline} is
 *   deliberately written so a worker holds at most <i>one</i> connection at any instant (it is
 *   not wrapped in an outer transaction -- AD-3), so {@code max=2} workers can consume at most
 *   2 of the 3 connections, leaving exactly 1 for the test thread itself. Raising the pool to 3
 *   or more would let the workers alone consume the whole pool and make any concurrent borrower
 *   fail deterministically after {@code connection-timeout: 5000} -- that is accumulation, not
 *   flakiness, and it must not be retried away. Note the headroom is zero at {@code max=2}: a
 *   test that holds a connection open (e.g. a {@code @Transactional} test method) <i>while</i>
 *   two ingest tasks run concurrently would already be at the ceiling.
 *   <p>
 *   Defining any {@code Executor} bean would normally switch off Spring Boot's own
 *   {@code applicationTaskExecutor}: {@code TaskExecutorConfigurations.TaskExecutorConfiguration}
 *   is guarded by {@code OnExecutorCondition}, an {@code AnyNestedCondition} whose first branch
 *   is {@code @ConditionalOnMissingBean(Executor.class)} (read from
 *   {@code spring-boot-autoconfigure:4.1.1} sources). Losing that bean would silently degrade
 *   Spring MVC async support -- {@code WebMvcAutoConfiguration} only installs an
 *   {@code AsyncTaskExecutor} when a bean named {@code applicationTaskExecutor} exists, otherwise
 *   MVC falls back to a thread-per-request {@code SimpleAsyncTaskExecutor}, which would affect the
 *   chat Spec's SSE endpoint. That is why {@code application.yaml} sets
 *   {@code spring.task.execution.mode: force} (the condition's second branch) -- it keeps
 *   {@code applicationTaskExecutor} alive alongside this bean. Do not remove that property while
 *   this bean exists.
 *   <p>
 *   Shutdown semantics are left at Spring's defaults ({@code waitForTasksToCompleteOnShutdown =
 *   false}), so an in-flight ingestion is interrupted on application shutdown and its document
 *   stays in a non-terminal state. That is the outcome Spec §7 OQ-8 already decided to accept
 *   ("데모 범위에서 방치" — delete and re-upload); adding drain-on-shutdown handling here would be
 *   a scope change, not a fix.
 */
@Configuration
@EnableAsync
public class IngestAsyncConfig {

    /**
     * [Purpose] Bean name of the ingestion executor, referenced by
     * {@code @Qualifier(IngestAsyncConfig.INGEST_EXECUTOR_BEAN_NAME)} at
     * {@link DocumentUploadedEventListener}'s injection point.
     * [Caution] The qualifier resolves at context startup, so a rename that is not mirrored at the
     *   injection site fails fast with an unsatisfied-dependency error. That is a deliberate
     *   improvement over the previous {@code @Async("...")} form, whose bean name was resolved
     *   lazily and failed only when the first task was submitted -- i.e. in production, on the
     *   first upload. Always reference this constant rather than typing the literal; and note
     *   {@code applicationTaskExecutor} is also an {@code AsyncTaskExecutor}, so the qualifier is
     *   required, not decorative.
     */
    public static final String INGEST_EXECUTOR_BEAN_NAME = "ingestExecutor";

    /** [Purpose] AD-11 core pool size — see class [Caution] before changing. */
    static final int CORE_POOL_SIZE = 2;

    /** [Purpose] AD-11 max pool size — see class [Caution] before changing. */
    static final int MAX_POOL_SIZE = 2;

    /** [Purpose] AD-11 bounded queue capacity; beyond this, submissions are rejected outright. */
    static final int QUEUE_CAPACITY = 50;

    /** [Purpose] Log-readable prefix so an ingestion stack trace is identifiable as background work. */
    static final String THREAD_NAME_PREFIX = "ingest-";

    /**
     * [Purpose] Builds the dedicated ingestion thread pool (AD-11).
     * [Rationale] Every value is set explicitly rather than inherited from
     *   {@code spring.task.execution.pool.*} defaults (core 8, unbounded queue) or from
     *   {@code SimpleAsyncTaskExecutor} (a new thread per task): both would exceed the JDBC
     *   connection budget described in the class [Caution].
     * [Side effects] Registers a bean of type {@code Executor}; see class [Caution] for the
     *   interaction with Spring Boot's {@code applicationTaskExecutor} auto-configuration.
     */
    @Bean(INGEST_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_POOL_SIZE);
        executor.setMaxPoolSize(MAX_POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix(THREAD_NAME_PREFIX);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
