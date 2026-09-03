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
        @DefaultValue Models models,
        @DefaultValue Chat chat,
        @DefaultValue Glossary glossary,
        @DefaultValue Ngram ngram
) {

    /**
     * [Purpose] The third retrieval axis: character n-gram BM25 over the {@code content_ngram}
     *   view that {@code V4__add_ngram_view_to_bm25_index.sql} adds to the ParadeDB index.
     * [Rationale] Option B of {@code DOC_ROOT/docs/eval/paradedb-user-dictionary-probe-2026-09-03.md}
     *   (user-approved 2026-09-03 together with the chunk context header). The lindera axis cannot
     *   see a proper noun, product code, or typo that its embedded dictionary does not know; a
     *   2-3-gram view matches such strings by character overlap regardless (probe §4: the exact
     *   answer chunk only, for both {@code 인사이트에이아이} and {@code ES-2024-A}, and the answer
     *   still first for the typo {@code 인사이트에이아리}). It is fused as its own axis through
     *   {@link art.insightai.chatbot.rag.retrieval.ReciprocalRankFusion} rather than merged into the
     *   BM25 axis because its {@code pdb.score} scale is roughly three times lindera's (probe §4),
     *   so the two cannot share {@link Search#bm25RawScoreThreshold()} -- hence its own
     *   {@code rawScoreThreshold}, and its own {@code weight} because an n-gram match-any query is
     *   noisier than a morphological one and must not outvote the vector axis (playbook §4-3:
     *   "낮은 가중치"). Top-level component for the same reason as {@link Glossary}: {@link Search}'s
     *   positional constructor has nine test call sites.
     * [Caution] {@code enabled=false} makes {@code HybridDocumentRetriever} skip the axis entirely
     *   (no query, no fusion contribution) so the two-axis fused-score arithmetic every existing
     *   test derives stays exact. Requires the V4 index; if V4 is rolled back, set this to false
     *   or every hybrid search fails on the missing alias. {@code rawScoreThreshold} is on the
     *   n-gram {@code pdb.score} scale, not lindera's -- never copy the 1.3 across.
     */
    public record Ngram(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("0.2") double weight,
            @DefaultValue("20") int topK,
            @DefaultValue("0.0") double rawScoreThreshold
    ) {
        public Ngram {
            if (Double.isNaN(rawScoreThreshold) || rawScoreThreshold < 0.0) {
                throw new IllegalArgumentException(
                        "app.ngram.raw-score-threshold must be a non-negative number, but was " + rawScoreThreshold);
            }
            if (Double.isNaN(weight) || weight < 0.0) {
                throw new IllegalArgumentException(
                        "app.ngram.weight must be a non-negative number, but was " + weight);
            }
            if (topK < 1) {
                throw new IllegalArgumentException("app.ngram.top-k must be at least 1, but was " + topK);
            }
        }
    }

    /**
     * [Purpose] The project's own proper-noun list (company names, product names, aliases,
     *   product codes) -- the single source every consumer of "words the tokenizer must not
     *   split" reads from.
     * [Rationale] pg_search 0.25.4's {@code pdb.lindera(korean)} has no user dictionary and no
     *   synonym filter (verified 2026-09-03 against the live container and the paradedb/paradedb
     *   source: the Lindera segmenter's user-dictionary slot is hard-coded to {@code None};
     *   {@code DOC_ROOT/docs/eval/paradedb-user-dictionary-probe-2026-09-03.md}). The engine
     *   therefore splits {@code 인사이트에이아이} into {@code 인사이트 / 에 / 이아이}, and the
     *   lone particle {@code 에} is what drags unrelated chunks into the BM25 axis (D1 spike 5-c's
     *   1.18-point noise row). The measured workaround (same probe report, §3) is to keep the
     *   whole query on the {@code |||} match operator and additionally require, with the
     *   {@code ###} phrase operator, any glossary term that appears verbatim in the query --
     *   which removes the noise rows and doubles the answer chunk's score. This record is that
     *   term list. It is a top-level component rather than a field on {@link Search} because
     *   a glossary is not a search tuning knob: the same list is the natural source for
     *   query-time synonym expansion, translation glossaries, and prompt-side original-form
     *   hints later (multilingual-RAG playbook §4-4/§4-5/§1-8), and because {@link Search}'s
     *   positional constructor is called from nine test sites that a new field would break.
     * [Caution] Every term is matched as a case-insensitive substring of the query and then
     *   sent to ParadeDB as a phrase; a term that is spelled one way in queries and another
     *   way in the corpus (e.g. {@code InsightAI} vs {@code 인사이트에이아이}) filters the BM25
     *   axis down to zero rows for that query, leaving only the vector axis. List every spelling
     *   the corpus actually uses. Changing this list needs no reindex -- it only changes the
     *   query -- but it does shift BM25 scores upward for matched queries, so the axis-native
     *   threshold ({@code app.search.bm25-raw-score-threshold}) stays valid only in the "no
     *   term matched" direction; re-sweep if the list grows beyond a handful of terms.
     */
    public record Glossary(
            List<String> terms
    ) {
        public Glossary {
            terms = terms == null
                    ? List.of()
                    : terms.stream().map(String::strip).filter(t -> !t.isEmpty()).distinct().toList();
        }
    }

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
     * [Caution] {@code scoreThreshold} filters the RRF *fused* score, which is a function of
     *   rank, not of relevance: with the default {@code rrfK}/weights/topK its whole value
     *   range is {@code 0.00625..0.01639} (worst case {@code 0.5/(60+20)}, best case
     *   {@code 0.5/61 + 0.5/61}), and a chunk ranked first on both axes always scores exactly
     *   {@code 0.0163934...} whatever the query was. Measured on the D7 sweep's stored
     *   {@code messages.sources}, 9 of 10 questions with no answer in the corpus produced the
     *   same top-1 fused score as questions that had one, so no value of {@code scoreThreshold}
     *   separates them: it behaves as a top-k control, never as a relevance filter (BL-025).
     *   {@code bm25RawScoreThreshold}/{@code vectorRawScoreThreshold} are the filter the plan
     *   doc's R6 actually intended. They drop a chunk by its axis-native score before fusion,
     *   which is where R6's observed numbers live (a 1.18-point BM25 row being irrelevant while
     *   3.99 was the answer, D1 spike 5-c). Two properties rather than one because the axes are
     *   not on a common scale: BM25 is unbounded and corpus-dependent, cosine similarity is
     *   bounded. Both default to {@code 0.0} (disabled), so the pipeline is unchanged until D7
     *   supplies measured numbers, and {@code scoreThreshold} is kept as-is because AC-4 and
     *   Test Case U2 pin its behavior.
     */
    public record Search(
            @DefaultValue("vector") Mode mode,
            @DefaultValue("5") int topK,
            @DefaultValue("60") int rrfK,
            @DefaultValue("0.5") double weightBm25,
            @DefaultValue("0.5") double weightVector,
            @DefaultValue("20") int bm25TopK,
            @DefaultValue("20") int vectorTopK,
            @DefaultValue("0.0") double scoreThreshold,
            @DefaultValue("0.0") double bm25RawScoreThreshold,
            @DefaultValue("0.0") double vectorRawScoreThreshold
    ) {
        public Search {
            requireUsableThreshold(scoreThreshold, "app.search.score-threshold");
            requireUsableThreshold(bm25RawScoreThreshold, "app.search.bm25-raw-score-threshold");
            requireUsableThreshold(vectorRawScoreThreshold, "app.search.vector-raw-score-threshold");
        }

        /**
         * [Purpose] Fails startup on a threshold value that would disable retrieval silently.
         * [Rationale] {@code NaN} loses every {@code >=} comparison, so a mistyped value would
         *   drop an entire axis's results with no error anywhere; a negative value silently
         *   means "disabled" while reading as if it were active. Both are configuration
         *   mistakes that only surface as missing answers, which is the hardest failure to
         *   trace back. {@code Models}, {@code Memory} and {@code Ingest} already validate in
         *   their compact constructors; {@code Search} was the gap (BL-025 review, Low-3).
         *   These numbers get hand-set during the D7 sweep, which is exactly when a typo is
         *   most likely, so the check earns its keep before that sweep rather than after.
         * [Caution] {@code 0.0} is legal and means the filter is off -- do not tighten this to
         *   a positive-only check.
         */
        private static void requireUsableThreshold(double value, String propertyName) {
            if (Double.isNaN(value) || value < 0.0) {
                throw new IllegalArgumentException(
                        propertyName + " must be a non-negative number, but was " + value);
            }
        }

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
     *   {@code deleteLockTimeout} bounds how long {@code DocumentDeleteService#delete} waits on
     *   {@code DocumentIngestLockRegistry#tryLock} before giving up (document-ingestion T10
     *   review 2026-08-30 round 3, Medium-1) — a plain {@code ReentrantLock#lock()} would park the
     *   request thread handling {@code DELETE /api/admin/documents/{id}} indefinitely if a
     *   same-stripe ingestion's embedding round-trip stalls, which is itself an unauthenticated
     *   attack surface one resource over from AC-17's own concern. 30 seconds is a demo-scale
     *   placeholder, not a measured number: it is meant to comfortably exceed one embedding
     *   round-trip for a single chunk batch under normal OpenRouter latency, the same
     *   "generous, not tuned" spirit as {@code Rerank.connectTimeout}/{@code readTimeout} above.
     *   On expiry {@code DocumentDeleteService#delete} throws {@code
     *   DocumentDeleteLockTimeoutException}, mapped to 503 — deliberately distinct from AC-18's
     *   orphan-cleanup endpoint, which means "no row to clean up", not "busy right now".
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
            @DefaultValue("100") int maxDocumentCount,
            @DefaultValue("30s") Duration deleteLockTimeout
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
     *   (chat T4 review 2026-08-26, High-2), the number of {@code conversations} rows any single
     *   user may hold at once (chat T6 review 2026-08-27, Medium-2), and the number of 'user'
     *   {@code messages} rows any single conversation may hold at once (chat T9 review
     *   2026-08-27, Medium-3; user decision D-3①).
     * [Rationale] {@code maxAnonymousUsers}: mirrors the {@code Ingest.maxDocumentCount} precedent
     *   above — every unauthenticated request with no, malformed, or unknown {@code chatbot_sid}
     *   cookie makes {@code AnonymousUserService#resolveOrCreate} insert a fresh row, so a crawler
     *   or scanner hitting {@code /api/*} repeatedly can grow the table without bound. {@code
     *   maxAnonymousUsers} only defines the ceiling; {@code AnonymousUserService} is responsible
     *   for counting {@code auth_provider='anonymous'} rows and enforcing it, and only on the
     *   "issue a brand-new row" path — cookie reuse never counts against it. 1000 is a demo-scale
     *   ceiling (Spec R-6 §8, stage 1 local demo), not a capacity estimate for the stage 2/3
     *   rollout — those stages must revisit this value alongside R-6.
     *   {@code maxConversationsPerUser}: same shape of problem one level down — {@code
     *   POST /api/conversations} is unauthenticated and unconditionally inserts a row per call, so
     *   a single {@code chatbot_sid} cookie replayed in a loop can grow the table without bound
     *   even while staying under {@code maxAnonymousUsers} (only one user row is ever created).
     *   {@code ConversationService} is responsible for counting a user's non-soft-deleted
     *   conversations and enforcing it. 50 is a demo-scale placeholder, not a number backed by
     *   observed usage — like {@code Search.scoreThreshold} above, the eval-usage-dashboard Spec's
     *   D7 pass is expected to revisit it.
     *   {@code maxMessagesPerConversation}: same shape of problem another level down, and the
     *   first of the three tied to a real per-call cost — T9 connects {@code
     *   POST /api/conversations/{id}/messages} to an actual paid OpenRouter call, so a single
     *   {@code chatbot_sid} cookie replayed against the same conversation id triggers one billed
     *   request per call, unbounded by either ceiling above (a single conversation can absorb
     *   unlimited messages while its owner stays under both quotas). {@code ConversationService}
     *   is responsible for counting a conversation's {@code role='user'} rows and enforcing it,
     *   before {@code ChatService} makes the LLM call. 200 is a demo-scale placeholder — ten times
     *   the fixed 20-turn {@code ChatMemory} window §7 OQ-7 sets for T10, generous headroom above
     *   what the feature itself needs for one conversation — not a number backed by observed
     *   usage; like the two ceilings above, the eval-usage-dashboard Spec's D7 pass is expected to
     *   revisit it.
     */
    public record Session(
            @DefaultValue("1000") int maxAnonymousUsers,
            @DefaultValue("50") int maxConversationsPerUser,
            @DefaultValue("200") int maxMessagesPerConversation
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

    /**
     * [Purpose] Multi-turn {@code ChatMemory} configuration (chat Spec §7 OQ-7, Tech Review A-5,
     *   T10).
     * [Rationale] Nested under {@code chat.memory.*} — not a flat {@code app.chat-memory-max-
     *   messages} key — because {@code app.chat.memory.max-messages} is the exact property path
     *   Tech Review A-5 specifies. Only one setting exists today ({@link Memory#maxMessages}), but
     *   the extra {@code Chat} level leaves room for future {@code app.chat.*} settings (e.g. a
     *   system prompt) without a breaking rename, mirroring how {@link Models} already leaves
     *   {@link Models.ModelEntry} nested rather than flattening its two fields into {@code
     *   AppProperties} directly.
     * [Rationale] {@code streamTimeout} (2026-09-02, user-reported defect) bounds one answer-
     *   generation call to OpenRouter end to end -- connect, and reading the whole streamed body
     *   until the terminal chunk. Spring AI 2.0.1's {@code OpenAiChatOptions} fills a {@code null}
     *   timeout with {@code AbstractOpenAiOptions.DEFAULT_TIMEOUT} (60s) at construction time, and
     *   {@code SpringAiOpenAiHttpClient} applies that value as OkHttp's {@code callTimeout} (whole
     *   call, streaming body included) as well as {@code readTimeout}. Because {@code
     *   ChatService#streamReply} builds a fresh {@code OpenAiChatOptions} per request, that 60s
     *   default was always sent regardless of {@code spring.ai.openai.timeout}, and it was never
     *   visible in this project's own configuration. It went unnoticed while the catalog held only
     *   fast models; the 2026-09-02 catalog swap added {@code qwen/qwen3.8-flash}, a reasoning
     *   model whose hidden thinking pushes some turns past 60s (measured: 4 of 5 failures ended at
     *   61-66s with {@code finish_reason=error} and empty content, successes up to 41s / 3,925
     *   completion tokens). 5 minutes is a generous ceiling chosen to stay above any observed turn,
     *   not a tuned value; the stream can still be cancelled by the user at any time.
     * [Caution] The compact constructor rejects a zero or negative {@code streamTimeout} at
     *   startup -- OkHttp treats {@code 0} as "no timeout", which would silently reintroduce the
     *   unbounded-request-thread problem {@code Rerank.readTimeout} exists to prevent.
     */
    public record Chat(
            @DefaultValue Memory memory,
            @DefaultValue("5m") Duration streamTimeout
    ) {
        public Chat {
            if (streamTimeout == null || streamTimeout.isZero() || streamTimeout.isNegative()) {
                throw new IllegalStateException(
                        "app.chat.stream-timeout must be a positive duration, got: " + streamTimeout);
            }
        }

        /**
         * [Purpose] How many of the most-recent {@code messages} rows (user+assistant combined)
         *   {@code MessageChatMemoryRepository} feeds into the LLM prompt for multi-turn context.
         * [Rationale] {@code maxMessages} defaults to 40 = 20 turns x 2 rows/turn (one 'user' +
         *   one 'assistant' row per turn) — the user-approved OQ-7 answer ("최근 20턴 고정"), not a
         *   value this record derives on its own. The x2 pairing is structural (every request
         *   writes exactly one {@code role='user'} row and one {@code role='assistant'} row per
         *   chat:AC-29), so a future change to the turn count only ever edits this one number.
         * [Caution] The compact constructor rejects {@code maxMessages < 1} at application
         *   startup — fail-fast, following the same {@link Models}/{@link Ingest} precedent, so a
         *   bad {@code app.chat.memory.max-messages} value in application.yaml never reaches
         *   {@code MessageChatMemoryRepository#findByConversationId} at request time. Without this
         *   guard, {@code MessageChatMemoryRepository#findByConversationId}'s {@code
         *   Math.max(0, rows.size() - maxMessages)} silently misbehaves at both edges T10 review
         *   2026-08-28 (correctness Medium-2) found: {@code 0} makes that expression equal {@code
         *   rows.size()}, so {@code rows.subList(rows.size(), rows.size())} returns an empty list —
         *   and because {@link art.insightai.chatbot.chat.service.ChatService#streamReply} no
         *   longer sends a separate {@code .user(userMessage)} call (T10), the current turn's own
         *   question is then never sent to the LLM at all; any negative value makes the expression
         *   exceed {@code rows.size()}, and {@code List#subList} throws {@code
         *   IndexOutOfBoundsException} on every {@code POST .../messages} call.
         */
        public record Memory(
                @DefaultValue("40") int maxMessages
        ) {
            public Memory {
                if (maxMessages < 1) {
                    throw new IllegalStateException(
                            "app.chat.memory.max-messages must be at least 1, got: " + maxMessages);
                }
            }
        }
    }
}
