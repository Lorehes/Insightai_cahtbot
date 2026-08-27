package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.support.CountingDataSource;
import art.insightai.chatbot.support.IntegrationTestBase;

/**
 * [Purpose] Verifies {@link ParadeDbBm25SearchAdapter} against the live ParadeDB container --
 *   {@code content |||} is a ParadeDB operator H2 cannot host, so this is Integration-only,
 *   mirroring {@code ChunkRepositoryIntegrationTest}'s justification for the same constraint.
 * [Rationale] Reuses {@code ChunkRepositoryIntegrationTest}'s seeding pattern: each test inserts
 *   its own rows under a fresh random {@code document_id} and cleans them up in
 *   {@link #cleanUpInsertedRows()} via a direct {@link JdbcTemplate} delete (not the adapter
 *   under test), so a bug in {@link ParadeDbBm25SearchAdapter} cannot also break cleanup and
 *   leak rows into other cards' sessions sharing this {@code infra} container. The seed
 *   sentences reproduce the D1 spike's {@code ES-2024-A} case (chatbot-plan-v0.4.md §7.1) that
 *   motivated the hybrid pipeline in the first place.
 *   Rework (review 2026-08-26_hybrid-rag-T4.md Block): every seed/query combination below was
 *   verified live via {@code docker exec chatbot-db psql} before being written here (project
 *   rule P3 -- do not guess ParadeDB/lindera behavior from a web example). Two findings that
 *   shaped this file: (1) a short competing chunk that shares the *entire* product code (e.g.
 *   {@code "ES-2024-B는 별도 코드입니다"}) actually OUTSCORES the longer exact-match sentence --
 *   ParadeDB's BM25 rewards short documents -- so the review's own suggested competitor could
 *   not be used verbatim; the competitors here were chosen by measuring real scores instead.
 *   (2) {@code content |||} treats its operand as a disjunction of tokens, so an arbitrary
 *   Korean "gibberish" string is not guaranteed to miss every row (lindera fragments unknown
 *   compounds) -- {@code "QWERTYUIOPASDFGH"} was chosen for the empty-result case only after
 *   confirming live that it matches none of this file's seeded rows.
 * [Caution] AC-13's own text requires {@code mode=hybrid}, which only exists once T7's
 *   {@code HybridDocumentRetriever} lands -- this file's {@code [hybrid-rag:AC-13]}-tagged test
 *   is BM25-axis-only partial evidence (Tech Review "AC-13 소유권 정정", 2026-08-26). Do not let
 *   passing tests here flip Spec §4 AC-13 to ✅; that happens only when T7's own AC-13(최종) test
 *   passes.
 */
class ParadeDbBm25SearchAdapterIntegrationTest extends IntegrationTestBase {

    private static final String DUMMY_EMBEDDING_LITERAL = buildDummyEmbeddingLiteral();

    @Autowired
    private ParadeDbBm25SearchAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    private final List<UUID> insertedDocumentIds = new ArrayList<>();

    @AfterEach
    void cleanUpInsertedRows() {
        for (UUID documentId : insertedDocumentIds) {
            jdbcTemplate.update(
                    "DELETE FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?",
                    documentId.toString());
        }
        insertedDocumentIds.clear();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-13] ES-2024-A 를 포함한 청크가 있을 때 \"ES-2024-A\" 질의 시 "
            + "경쟁 청크들보다 앞선 1위로 반환된다")
    void returnsExactMatchChunkFirstForProductCodeQuery() {
        // given: two lower-scoring competitors are seeded FIRST and the exact match LAST. An
        // earlier version of this test seeded the exact match first, which let a broken/removed
        // "ORDER BY score DESC" pass by sheer insertion-order coincidence (Postgres tends to
        // return an unordered scan in physical/insertion order) -- confirmed live (review
        // 2026-08-26 correctness Medium-1, round-2 rework): deleting "ORDER BY score DESC" from
        // the adapter's SQL left this test green when the exact match was seeded first, and only
        // failed once the exact match was moved to be seeded last, as below.
        UUID documentId = UUID.randomUUID();
        insertChunk(documentId, "2024년 매출 자료");
        insertChunk(documentId, "ES는 유럽연합의 약자로도 쓰입니다");
        insertChunk(documentId, "제품코드 ES-2024-A 는 물류 자동화 솔루션의 내부 관리 코드입니다.");

        // when
        List<RetrievedChunk> result = adapter.search("ES-2024-A", 20);

        // then: at least one competitor also matched, the exact match ranks first, and its score
        // is a direct, explicit maximum over every other returned chunk. This replaces the prior
        // isSortedAccordingTo(reverseOrder()) check, which only requires a non-increasing
        // sequence and would silently tolerate ties between the leader and a competitor -- a
        // strict "isLessThan" comparison against the leader's own score does not.
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        RetrievedChunk leader = result.get(0);
        assertThat(leader.content()).contains("ES-2024-A");
        assertThat(result.subList(1, result.size()))
                .as("every competitor scores strictly lower than the exact-match leader")
                .allSatisfy(chunk -> assertThat(chunk.score()).isLessThan(leader.score()));
    }

    @Test
    @DisplayName("한 쿼리 결과만으로 content·metadata·score 가 모두 채워지고, "
            + "search() 1회당 SQL 실행이 정확히 1회다 (N+1 없음)")
    void populatesContentMetadataAndScoreFromASingleQueryWithoutNPlusOne() {
        // given: a counting JdbcTemplate built on top of the real, live DataSource -- the
        // adapter under test is a fresh instance wired to it so the count reflects exactly this
        // one search() call (review 2026-08-26 High-3: the previous version of this test never
        // counted anything, so a second lookup added inside mapRow would still have passed)
        UUID documentId = UUID.randomUUID();
        insertChunk(documentId, "제품코드 ES-2024-A 는 물류 자동화 솔루션의 내부 관리 코드입니다.");
        AtomicInteger statementCount = new AtomicInteger();
        ParadeDbBm25SearchAdapter countingAdapter =
                new ParadeDbBm25SearchAdapter(
                        new JdbcTemplate(new CountingDataSource(dataSource, statementCount)));

        // when
        List<RetrievedChunk> result = countingAdapter.search("ES-2024-A", 20);

        // then: exactly one JDBC statement was prepared for this call. The row-count/content
        // assertions below are narrowed to this test's own document_id (Low item, review
        // 2026-08-26 round-2 rework) -- this is a live, shared infra container, so "ES-2024-A"
        // could in principle also match a row seeded by another concurrently-running test.
        assertThat(statementCount.get()).isEqualTo(1);
        RetrievedChunk chunk = onlyChunkFor(result, documentId);
        assertThat(chunk.content()).contains("ES-2024-A");
        assertThat(chunk.metadata()).containsEntry("document_id", documentId.toString());
        assertThat(chunk.score()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("topK 로 넘긴 값이 LIMIT 으로 바인딩되어, 그보다 많은 행이 매치돼도 결과가 그 건수로 제한된다")
    void limitsResultCountToRequestedTopK() {
        // given: three chunks that all match "ES-2024-A" via shared BM25 tokens -- confirmed
        // live (docker exec psql) that all three score above zero for this query, so at least 3
        // rows are eligible before any LIMIT is applied (Low item, review 2026-08-26 round-2
        // rework: topK -> LIMIT binding had no dedicated test)
        UUID documentId = UUID.randomUUID();
        insertChunk(documentId, "제품코드 ES-2024-A 는 물류 자동화 솔루션의 내부 관리 코드입니다.");
        insertChunk(documentId, "2024년 매출 자료");
        insertChunk(documentId, "ES는 유럽연합의 약자로도 쓰입니다");

        // when
        List<RetrievedChunk> result = adapter.search("ES-2024-A", 2);

        // then: exactly 2 rows come back even though at least 3 eligible rows exist -- proves
        // topK reaches the SQL as the bound LIMIT parameter rather than being ignored
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("매치되는 청크가 없으면, 매치되지 않는 다른 청크가 존재해도 빈 목록을 반환한다")
    void returnsEmptyListWhenNoChunkMatches() {
        // given: unrelated chunks ARE seeded here (review 2026-08-26 correctness Medium-3 --
        // asserting emptiness against a table with zero rows at all proves nothing about
        // filtering)
        UUID documentId = UUID.randomUUID();
        insertChunk(documentId, "제품코드 ES-2024-A 는 물류 자동화 솔루션의 내부 관리 코드입니다.");
        insertChunk(documentId, "연차휴가는 입사일을 기준으로 매년 갱신되며 최대 15일까지 사용할 수 있습니다.");

        // when: confirmed live that this query shares no BM25 token with either seeded chunk --
        // an arbitrary Korean string is not a safe choice here, since lindera fragments unknown
        // compounds and can accidentally match unrelated rows (see class Javadoc, finding 2)
        List<RetrievedChunk> result = adapter.search("QWERTYUIOPASDFGH", 20);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("어미가 붙은 한국어 문장도 lindera 형태소 분석을 거쳐 어간 질의와 매치된다")
    void matchesKoreanQueryAgainstLinderaTokenizedChunk() {
        // given: the seed sentence carries the query word plus a topic-marker suffix
        // ("연차휴가는") -- every other test in this file queries by a Latin product code, which
        // exercises the "||| " operator but never actually proves the index's Korean lindera
        // tokenizer path runs at all (review 2026-08-26 correctness Medium-2)
        UUID documentId = UUID.randomUUID();
        insertChunk(documentId, "연차휴가는 입사일을 기준으로 매년 갱신되며 최대 15일까지 사용할 수 있습니다.");

        // when: query omits the "는" topic-marker particle the seed sentence carries, so a
        // match here can only come from lindera splitting the noun off before indexing
        List<RetrievedChunk> result = adapter.search("연차휴가", 20);

        // then: narrowed to this test's own document_id (Low item, review 2026-08-26 round-2
        // rework) -- a shared infra container could in principle hold another concurrently
        // seeded row that also matches "연차휴가"
        assertThat(onlyChunkFor(result, documentId).content()).contains("연차휴가");
    }

    @Test
    @DisplayName("쉼표·따옴표·중괄호가 섞인 filename 이 metadata 에서 원래 값 그대로 파싱된다")
    void parsesMetadataWithCommaQuoteAndBraceCharactersInFilename() {
        // given: filename deliberately contains a comma, escaped quotes, and brace characters
        // that would look like a nested JSON object if handled by naive string concatenation
        // (review 2026-08-26 correctness Medium-4 / security Low-3)
        UUID documentId = UUID.randomUUID();
        String filename = "report, \"final\" v2 {draft}, 100% done.pdf";
        insertChunk(documentId, "제품코드 ES-2024-A 는 물류 자동화 솔루션의 내부 관리 코드입니다.", filename);

        // when
        List<RetrievedChunk> result = adapter.search("ES-2024-A", 20);

        // then: narrowed to this test's own document_id (Low item, review 2026-08-26 round-2
        // rework) -- other tests in this file also seed "ES-2024-A" content under their own
        // document_id, and this test asserts a filename value only this row carries
        assertThat(onlyChunkFor(result, documentId).metadata()).containsEntry("filename", filename);
    }

    @Test
    @DisplayName("SQL 인젝션처럼 보이는 질의를 바인딩 파라미터로 넘겨도 시드된 청크가 결과에 노출되지 않는다")
    void rejectsSqlInjectionLikeQueryWithoutExposingSeededChunk() {
        // given: review 2026-08-26 correctness+security Medium-2 (round-2 rework) -- the
        // previous version of this test only asserted "no exception thrown", which an
        // injection that WIDENS the result set (e.g. string-concatenated "OR 1=1" matching
        // every row) would also satisfy. If query/topK ever stopped being bound JDBC
        // parameters (this class's own [Caution] -- see class Javadoc) and were concatenated
        // into the SQL text instead, "' OR 1=1 --" would turn the WHERE clause into a tautology
        // and return every row in the shared table, including this seeded one.
        UUID documentId = UUID.randomUUID();
        insertChunk(documentId, "제품코드 ES-2024-A 는 물류 자동화 솔루션의 내부 관리 코드입니다.");

        // when
        List<RetrievedChunk> result = adapter.search("' OR 1=1 --", 20);

        // then: this test's own seeded chunk is absent from the result set. isEmpty() would be
        // unsafe here -- this is a live, shared `infra` container other cards' tests may be
        // seeding rows into concurrently -- so absence of THIS test's document_id is the actual,
        // container-safe proof that the payload did not widen the query.
        assertThat(result)
                .extracting(chunk -> chunk.metadata().get("document_id"))
                .doesNotContain(documentId.toString());
    }

    @Test
    @DisplayName("'|||' 매치 연산자는 괄호로만 이루어진 질의에도 DSL 파싱 오류 없이 안전하게 동작한다")
    void keepsMatchDisjunctionOperatorSafeForParenthesesLikeQuery() {
        // given: this is NOT an injection guard -- content ||| ? cannot execute SQL through its
        // matched text regardless of content, so "(((" poses no injection risk by itself. This
        // guards a different, real regression: verified live (docker exec psql PREPARE/EXECUTE,
        // 2026-08-26) that content ||| ? treats "(((" as a literal token-disjunction operand,
        // while the "@@@" operator DOES throw a ParadeDB query-DSL parse error for the exact same
        // bound-parameter input -- so if a future edit ever switched BM25_SEARCH_SQL from |||
        // to @@@ (this class's own [Caution] warns against exactly that), this test would catch
        // the regression by failing with a parse exception here (review 2026-08-26
        // correctness+security Medium-2, round-2 rework: renamed from "인젝션 가드" -- the
        // previous name and comment mischaracterized what this case actually protects).
        UUID documentId = UUID.randomUUID();
        insertChunk(documentId, "제품코드 ES-2024-A 는 물류 자동화 솔루션의 내부 관리 코드입니다.");

        // when / then
        assertThatCode(() -> adapter.search("(((", 20)).doesNotThrowAnyException();
    }

    /**
     * [Purpose] Returns the single element of {@code result} whose {@code document_id} metadata
     *   matches {@code documentId}, failing the test if there is not exactly one.
     * [Rationale] This suite runs against a live, shared {@code infra} container (Low item,
     *   review 2026-08-26 round-2 rework) -- a bare {@code result.get(0)}/{@code hasSize(1)}
     *   assumes this test's own seeded row is the only match, which does not hold if another
     *   concurrently-running test happens to seed a row matching the same query text.
     */
    private RetrievedChunk onlyChunkFor(List<RetrievedChunk> result, UUID documentId) {
        List<RetrievedChunk> matches =
                result.stream()
                        .filter(chunk -> documentId.toString().equals(chunk.metadata().get("document_id")))
                        .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private void insertChunk(UUID documentId, String content) {
        insertedDocumentIds.add(documentId);
        jdbcTemplate.update(
                "INSERT INTO vector_store_bgem3 (content, metadata, embedding) "
                        + "VALUES (?, json_build_object('document_id', ?::text)::json, ?::vector)",
                content, documentId.toString(), DUMMY_EMBEDDING_LITERAL);
    }

    /**
     * [Purpose] Same as {@link #insertChunk(UUID, String)} but also sets a {@code filename}
     *   metadata key.
     * [Rationale] Uses the fixed SQL template {@code json_build_object(...)} with each value as
     *   its own bind parameter -- not Java-side string concatenation -- so Postgres itself
     *   escapes commas/quotes/braces in {@code filename} (review 2026-08-26 correctness
     *   Medium-4 / security Low-3).
     */
    private void insertChunk(UUID documentId, String content, String filename) {
        insertedDocumentIds.add(documentId);
        jdbcTemplate.update(
                "INSERT INTO vector_store_bgem3 (content, metadata, embedding) "
                        + "VALUES (?, json_build_object("
                        + "'document_id', ?::text, 'filename', ?::text)::json, ?::vector)",
                content, documentId.toString(), filename, DUMMY_EMBEDDING_LITERAL);
    }

    private static String buildDummyEmbeddingLiteral() {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append('0');
        }
        return literal.append(']').toString();
    }
}
