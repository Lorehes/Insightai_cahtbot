package art.insightai.chatbot.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * [Purpose] Binds the {@code app.*} namespace of application.yaml.
 * [Rationale] Records give constructor binding and immutability for free, and
 *   spring-boot-configuration-processor turns them into IDE metadata, which removes the
 *   "cannot resolve configuration property" warnings and adds completion. Modes are enums
 *   rather than strings so a typo such as {@code hybird} fails at startup instead of
 *   silently falling back to vector search halfway through D6.
 * [Caution] The processor only runs because it is listed in the maven-compiler-plugin
 *   annotationProcessorPaths block. Adding the dependency alone is not enough once that
 *   block exists, because it replaces processor discovery from the classpath.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue Auth auth,
        @DefaultValue Search search,
        @DefaultValue Rerank rerank,
        @DefaultValue Ingest ingest,
        @DefaultValue Session session,
        @DefaultValue Models models
) {

    /** [Purpose] Authentication strategy. NONE for the demo, WORKS (NAVER WORKS SSO) in Phase 2. */
    public record Auth(
            @DefaultValue("none") Mode mode
    ) {
        public enum Mode { NONE, WORKS }
    }

    /**
     * [Purpose] Retrieval strategy and hybrid fusion parameters.
     * [Rationale] VECTOR uses pgvector alone; HYBRID fuses BM25 and vector ranks with
     *   Reciprocal Rank Fusion. Weights and rrfK are tuned against the D7 evaluation set,
     *   so they are configuration rather than constants. {@code bm25TopK}/{@code vectorTopK}
     *   turn the plan doc's fixed "top 20" (§7) into a property so D7 tuning does not need a
     *   rebuild (hybrid-rag Spec §8-D3). {@code scoreThreshold} defaults to {@code 0.0}
     *   (effectively disabled) on purpose: the plan doc's R6 confirms a threshold is needed,
     *   but D1 has no measured number to back one — hardcoding a guessed value would silently
     *   drop valid chunks before D7 tunes it. The filter code path exists; only the number is
     *   deferred (hybrid-rag Spec §8-D2). The eval-usage-dashboard Spec's D7 sweep is expected
     *   to determine the real value.
     */
    public record Search(
            @DefaultValue("vector") Mode mode,
            @DefaultValue("5") int topK,
            @DefaultValue("60") int rrfK,
            @DefaultValue("0.5") double weightBm25,
            @DefaultValue("0.5") double weightVector,
            @DefaultValue("20") int bm25TopK,
            @DefaultValue("20") int vectorTopK,
            @DefaultValue("0.0") double scoreThreshold
    ) {
        public enum Mode { VECTOR, HYBRID }
    }

    /**
     * [Purpose] Reranking stage. Enabled only after the D7 evaluation says it helps.
     * [Rationale] {@code connectTimeout}/{@code readTimeout} bound the {@link
     *   art.insightai.chatbot.rag.retrieval.OpenRouterRerankAdapter}'s {@code RestClient} so a
     *   stalled OpenRouter response cannot block a request thread indefinitely (hybrid-rag Spec
     *   T6 review 2026-08-27, High-1). Like {@code Search.scoreThreshold} above, the numbers
     *   themselves have no measured basis yet — 5s/10s are deliberately generous placeholders,
     *   not tuned values. The eval-usage-dashboard Spec's D7 pass is expected to replace them
     *   with numbers backed by observed OpenRouter latency.
     */
    public record Rerank(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("cohere/rerank-v3.5") String model,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout
    ) {}

    /**
     * [Purpose] Document ingestion limits and chunking parameters (document-ingestion Spec T1).
     * [Rationale] {@code chunkSize}/{@code minChunkSizeChars} feed Spring AI's
     *   {@code TokenTextSplitter}, which has no overlap parameter (CLAUDE.md fact #11) — the
     *   Spec's OQ-1 answer widens the chunk instead. {@code maxFileSize} is the application-level
     *   413 threshold (20MB); the container-level multipart limit in application.yaml is kept at
     *   25MB on purpose (Tech Review AD-6) so this check, not the container, is what trips first.
     * [Caution] {@code uploadDir} has no default on purpose — it must come from the
     *   {@code UPLOAD_DIR} environment variable (infra/.env). application.yaml binds it as
     *   {@code ${UPLOAD_DIR}} with no fallback, so a missing value fails placeholder resolution
     *   at startup instead of silently binding null. Do not add a {@code :default} suffix there.
     *   The compact constructor additionally requires {@code uploadDir} to be non-blank and an
     *   absolute path ({@code Path.isAbsolute()}); this runs at binding time, so a bad value
     *   fails fast at startup instead of surfacing as an NPE deep in T5's file-write code. It
     *   does NOT check for or reject a drive root such as {@code C:/uploads} — that is an
     *   operator convention, not an enforced invariant. Avoid drive roots anyway: their inherited
     *   ACL grants every locally authenticated account read/write (review 2026-08-25, High-1).
     *   Use a path under the user's profile instead, e.g.
     *   {@code C:/Users/<name>/AppData/Local/insight-chat/uploads} (the literal
     *   {@code %LOCALAPPDATA%} env-var syntax is NOT expanded here and would fail the
     *   absolute-path check).
     *   {@code maxDocumentCount} caps the total number of {@code documents} rows the demo
     *   deployment may hold. {@code POST /api/admin/documents} is unauthenticated (Spec OQ-7,
     *   already approved) and only checks per-request size (20MB), not cumulative volume — an
     *   unbounded number of uploads can exhaust disk and drive unbounded bge-m3 embedding cost
     *   (review 2026-08-25, Medium-2, user-approved fix). 100 is a demo-scale ceiling: generous
     *   for the current corpus, not unlimited. This field only defines the limit; T5's upload
     *   endpoint is responsible for counting {@code documents} rows and enforcing it.
     */
    public record Ingest(
            String uploadDir,
            @DefaultValue("20MB") DataSize maxFileSize,
            @DefaultValue({
                    "application/pdf",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "text/plain",
                    "text/markdown"
            }) List<String> allowedMimeTypes,
            @DefaultValue("2000") int chunkSize,
            @DefaultValue("500") int minChunkSizeChars,
            @DefaultValue("100") int maxDocumentCount
    ) {
        public Ingest {
            if (uploadDir == null || uploadDir.isBlank()) {
                throw new IllegalStateException(
                        "app.ingest.upload-dir (UPLOAD_DIR) must not be blank");
            }
            if (!Path.of(uploadDir).isAbsolute()) {
                throw new IllegalStateException(
                        "app.ingest.upload-dir (UPLOAD_DIR) must be an absolute path, got: " + uploadDir);
            }
        }
    }

    /**
     * [Purpose] Caps the number of anonymous {@code users} rows the demo deployment may hold
     *   (chat T4 review 2026-08-26, High-2), and the number of {@code conversations} rows any
     *   single user may hold at once (chat T6 review 2026-08-27, Medium-2).
     * [Rationale] {@code maxAnonymousUsers}: mirrors the {@code Ingest.maxDocumentCount} precedent
     *   above — every unauthenticated request with no, malformed, or unknown {@code chatbot_sid}
     *   cookie makes {@code AnonymousUserService#resolveOrCreate} insert a fresh row, so a crawler
     *   or scanner hitting {@code /api/*} repeatedly can grow the table without bound. {@code
     *   maxAnonymousUsers} only defines the ceiling; {@code AnonymousUserService} is responsible
     *   for counting {@code auth_provider='anonymous'} rows and enforcing it, and only on the
     *   "issue a brand-new row" path — cookie reuse never counts against it. 1000 is a demo-scale
     *   ceiling (Spec R-6 §8, 1단계 로컬 데모), not a capacity estimate for the 2단계/3단계
     *   rollout — those stages must revisit this value alongside R-6.
     *   {@code maxConversationsPerUser}: same shape of problem one level down — {@code
     *   POST /api/conversations} is unauthenticated and unconditionally inserts a row per call, so
     *   a single {@code chatbot_sid} cookie replayed in a loop can grow the table without bound
     *   even while staying under {@code maxAnonymousUsers} (only one user row is ever created).
     *   {@code ConversationService} is responsible for counting a user's non-soft-deleted
     *   conversations and enforcing it. 50 is a demo-scale placeholder, not a number backed by
     *   observed usage — like {@code Search.scoreThreshold} above, the eval-usage-dashboard Spec's
     *   D7 pass is expected to revisit it.
     */
    public record Session(
            @DefaultValue("1000") int maxAnonymousUsers,
            @DefaultValue("50") int maxConversationsPerUser
    ) {}

    /**
     * [Purpose] Fixed catalog of selectable chat models for {@code GET /api/models} (chat Spec
     *   §5 A-11, T5).
     * [Rationale] The catalog is hardcoded here instead of fetched from OpenRouter's {@code
     *   GET /v1/models} at runtime, per chat Spec §5 A-10 — a runtime call would leave a live
     *   OpenRouter dependency inside every integration test run, breaking chat:AC-35 (zero live
     *   OpenRouter HTTP calls during the full test run). The five candidate ids were each
     *   confirmed to exist in the real OpenRouter catalog by a one-time manual {@code
     *   GET https://openrouter.ai/api/v1/models} check during T5 implementation (CLAUDE.md P1);
     *   see docs/dev-log/backend.jsonl's note field for this card for the result. {@code
     *   defaultId} is bound from {@code CHAT_MODEL_DEFAULT} independently of {@code
     *   spring.ai.openai.chat.model} (which is bound from the same env var in application.yaml)
     *   rather than reading that Spring AI property directly, so this record has no dependency on
     *   Spring AI's property shape.
     * [Caution] The compact constructor enforces chat:AC-19 (3-5 entries), chat:AC-20 (no
     *   {@code :free} suffix — CLAUDE.md P2), and chat:AC-21 ({@code defaultId} must match one
     *   catalog entry) at application startup — fail-fast, so a bad {@code app.models} block in
     *   application.yaml or a stale {@code CHAT_MODEL_DEFAULT} never reaches {@code
     *   GET /api/models} at request time. Do not re-validate in the controller.
     * [AC] chat:AC-19, chat:AC-20, chat:AC-21
     */
    public record Models(
            String defaultId,
            List<ModelEntry> items
    ) {
        public Models {
            if (defaultId == null || defaultId.isBlank()) {
                throw new IllegalStateException(
                        "app.models.default-id (CHAT_MODEL_DEFAULT) must not be blank");
            }
            if (items == null || items.size() < 3 || items.size() > 5) {
                throw new IllegalStateException(
                        "app.models.items must contain between 3 and 5 entries, got: "
                                + (items == null ? 0 : items.size()));
            }
            for (ModelEntry item : items) {
                if (item.id() == null || item.id().isBlank()) {
                    throw new IllegalStateException("app.models.items entries must have a non-blank id");
                }
                if (item.id().endsWith(":free")) {
                    throw new IllegalStateException(
                            "app.models.items must not contain a ':free' model id, got: " + item.id());
                }
            }
            boolean defaultPresent = items.stream().anyMatch(item -> defaultId.equals(item.id()));
            if (!defaultPresent) {
                throw new IllegalStateException(
                        "app.models.default-id (" + defaultId
                                + ") does not match any app.models.items id");
            }
        }

        /**
         * [Purpose] Whether {@code id} matches one of {@link #items}' catalog ids.
         * [Rationale] Backs chat:AC-22's model whitelist check wherever a caller-supplied model
         *   string needs validating — {@code ConversationService#createConversation} and {@code
         *   ConversationService#prepareMessageSend} (both chat T7) call this instead of each
         *   re-deriving the same {@code anyMatch} the compact constructor above already uses for
         *   {@code defaultId}.
         * [AC] chat:AC-22
         */
        public boolean contains(String id) {
            return items.stream().anyMatch(item -> item.id().equals(id));
        }

        /** [Purpose] One selectable model: OpenRouter id plus a human-readable label. */
        public record ModelEntry(String id, String displayName) {}
    }
}
