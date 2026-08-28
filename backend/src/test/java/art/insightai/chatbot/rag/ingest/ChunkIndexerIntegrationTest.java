package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.rag.retrieval.ParadeDbBm25SearchAdapter;
import art.insightai.chatbot.support.IntegrationTestBase;
import art.insightai.chatbot.support.SampleDocumentFixtures;
import art.insightai.chatbot.support.StubEmbeddingModel;

/**
 * [Purpose] Verifies that a chunk indexed through {@link ChunkIndexer#index(List)} -- the
 *   production insert path (AD-1: unmodified delegation to {@code VectorStore.add()}) -- is
 *   actually retrievable by both search axes this Spec's evidence ledger checks:
 *   document-ingestion:AC-9 (pgvector cosine similarity via the live {@code <=>} operator) and
 *   document-ingestion:AC-10 (ParadeDB BM25 via the live {@code content ||| ?} operator). {@link
 *   ChunkIndexerTest} (unit, mocked {@code VectorStore}) already fully covers the delegation
 *   itself and deliberately carries no AC tag -- see that class's Javadoc for why a mock cannot
 *   prove either search axis. This is Integration-only per Spec §6's Test Cases table, which
 *   places both AC-9 and AC-10 under "Integration (ParadeDB 필요)": {@code vector_store_bgem3
 *   .embedding} is a pgvector column and {@code content |||} a ParadeDB operator, neither of
 *   which H2 can host.
 * [Rationale] Every test indexes through the real {@link ChunkIndexer} bean (wired to the real
 *   {@code VectorStore}/{@code JdbcTemplate} beans against the live {@code chatbot-db} container)
 *   with {@link StubEmbeddingModel} standing in for the network call (Spec §6 "공통 전제"). Before
 *   writing this file, {@link StubEmbeddingModel}'s source and its own self-verification test
 *   ({@code StubEmbeddingModelTest#queryScoresSourceChunkHigherThanUnrelatedChunk}) were read to
 *   confirm the stub is NOT a fixed constant vector -- it derives a deterministic, token-overlap
 *   -discriminative vector per input text (bag-of-words hashing, L2-normalized), so a real
 *   "ranks near the top" assertion has actual discriminative power here, not a coincidence that
 *   would hold for any implementation. AC-9's "결과 상위에 포함된다" test below therefore seeds two
 *   clearly unrelated distractor chunks FIRST and the true match LAST (mirrors {@code
 *   ParadeDbBm25SearchAdapterIntegrationTest#returnsExactMatchChunkFirstForProductCodeQuery}'s
 *   ordering discipline, which caught a real "ORDER BY" regression by insertion-order coincidence
 *   in that file's own history) and issues a raw {@code embedding <=> ?::vector} query -- AC-9's
 *   own literal operator -- against the whole live table, then separately re-confirms strict
 *   ranking against only this test's own rows (container-noise-proof, since {@code chatbot-db} is
 *   shared with other concurrently running cards/sessions per this project's harness notes). AC-10
 *   reuses the real, already-verified {@link ParadeDbBm25SearchAdapter} bean (hybrid-rag Spec,
 *   read-only here per this card's scope) rather than hand-writing a second copy of its {@code
 *   content ||| ?} SQL, so this test proves the actual production query path finds a T7-indexed
 *   chunk, not just that the SQL text is theoretically correct. {@link SampleDocumentFixtures}
 *   (T4)'s {@code KNOWN_SENTENCE_TEXT}/{@code KEYWORD_TEXT} constants were built and live-measured
 *   for exactly these two queries -- see that class's Javadoc for the measured scores.
 * [Caution] Cleanup uses {@link ChunkRepository#deleteByDocumentId} (T3, autowired) rather than a
 *   direct {@code JdbcTemplate} delete: unlike the sibling suites in this package/{@code
 *   rag/retrieval}, {@link ChunkRepository} is not the class under test here (only {@link
 *   ChunkIndexer} is), so reusing it does not risk masking a bug in the code being verified.
 */
class ChunkIndexerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private ChunkIndexer chunkIndexer;

    @Autowired
    private ChunkRepository chunkRepository;

    @Autowired
    private ParadeDbBm25SearchAdapter bm25SearchAdapter;

    @Autowired
    private StubEmbeddingModel stubEmbeddingModel;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> trackedDocumentIds = new ArrayList<>();

    @AfterEach
    void cleanUpIndexedChunks() {
        for (UUID documentId : trackedDocumentIds) {
            chunkRepository.deleteByDocumentId(documentId);
        }
        trackedDocumentIds.clear();
    }

    @Test
    @DisplayName("[document-ingestion:AC-9] ChunkIndexer로 색인한 청크는 embedding 이 null 이 아니고 차원이 1024다")
    void indexedChunkHasNonNullEmbeddingWith1024Dimensions() {
        // given
        UUID documentId = UUID.randomUUID();
        index(documentId, SampleDocumentFixtures.KNOWN_SENTENCE_TEXT);

        // when
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT (embedding IS NOT NULL) AS not_null, vector_dims(embedding) AS dims "
                        + "FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?",
                documentId.toString());

        // then
        assertThat(row.get("not_null")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("dims")).isEqualTo(1024);
    }

    @Test
    @DisplayName("[document-ingestion:AC-9] ChunkIndexer로 색인한 청크는 본문 문장으로 벡터 유사도 검색(<=>)했을 때 결과 상위에 포함된다")
    void indexedChunkAppearsAmongTopResultsForCosineSimilaritySearchOnItsOwnSentence() {
        // given: two clearly unrelated distractor chunks are indexed first, then the true-match
        // chunk (containing the exact query sentence) last -- seeding order mirrors
        // ParadeDbBm25SearchAdapterIntegrationTest's discipline of putting the real match last so
        // a pass cannot be explained by insertion-order coincidence instead of a real
        // "ORDER BY embedding <=> ?" clause.
        UUID distractor1Id = UUID.randomUUID();
        UUID distractor2Id = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        index(distractor1Id, "연차휴가는 입사일을 기준으로 매년 갱신되며 최대 15일까지 사용할 수 있습니다.");
        index(distractor2Id, "휴게실 커피머신 원두는 매주 월요일에 보충됩니다.");
        index(targetId, "회사 소개 챕터의 일부. " + SampleDocumentFixtures.KNOWN_SENTENCE_TEXT + " 이상 소개를 마친다.");

        // when: a raw `<=>` cosine-distance query -- AC-9's own literal operator -- against the
        // query sentence's stub embedding, top 5 across the whole live (shared) table
        String queryVector = vectorLiteral(stubEmbeddingModel.embed(SampleDocumentFixtures.KNOWN_SENTENCE_TEXT));
        List<Map<String, Object>> topResults = jdbcTemplate.queryForList(
                "SELECT metadata->>'document_id' AS document_id "
                        + "FROM vector_store_bgem3 ORDER BY embedding <=> ?::vector LIMIT 5",
                queryVector);

        // then: the target chunk is among the top 5 nearest by cosine distance
        assertThat(topResults)
                .extracting(resultRow -> resultRow.get("document_id"))
                .as("target chunk appears in the top-5 nearest by cosine distance")
                .contains(targetId.toString());

        // then: and it strictly outranks both of this test's own distractors -- a
        // container-noise-proof check, independent of whatever a concurrently running
        // session/card may have separately seeded into this shared infra container
        double targetDistance = distanceOf(targetId, queryVector);
        assertThat(targetDistance)
                .as("target chunk's cosine distance to the query")
                .isLessThan(distanceOf(distractor1Id, queryVector))
                .isLessThan(distanceOf(distractor2Id, queryVector));
    }

    @Test
    @DisplayName("[document-ingestion:AC-10] ChunkIndexer로 색인한 청크는 본문에 등장하는 한국어 단어로 BM25 검색(content ||| :query)했을 때 결과에 포함된다")
    void indexedChunkIsFoundByKoreanBm25SearchOnAWordItContains() {
        // given
        UUID documentId = UUID.randomUUID();
        index(documentId, "회사 소개 챕터의 일부. " + SampleDocumentFixtures.KNOWN_SENTENCE_TEXT + " 이상 소개를 마친다.");

        // when: the same production adapter (hybrid-rag Spec) that issues `content ||| ?`
        // against vector_store_bgem3 -- proves the real BM25 query path finds a T7-indexed chunk
        List<RetrievedChunk> result = bm25SearchAdapter.search(SampleDocumentFixtures.KEYWORD_TEXT, 20);

        // then
        assertThat(result)
                .extracting(chunk -> chunk.metadata().get("document_id"))
                .contains(documentId.toString());
    }

    /**
     * [Purpose] Indexes one single-chunk document through the real {@link ChunkIndexer} bean and
     *   tracks its {@code document_id} for {@link #cleanUpIndexedChunks()}.
     */
    private void index(UUID documentId, String content) {
        trackedDocumentIds.add(documentId);
        chunkIndexer.index(List.of(
                Document.builder()
                        .text(content)
                        .metadata("document_id", documentId.toString())
                        .metadata("chunk_index", 0)
                        .build()));
    }

    /**
     * [Purpose] The single chunk row's cosine distance (pgvector {@code <=>}) to {@code
     *   queryVector}, scoped to one {@code document_id} -- each test in this file indexes exactly
     *   one chunk per tracked id, so this always resolves to exactly one row.
     */
    private double distanceOf(UUID documentId, String queryVector) {
        return jdbcTemplate.queryForObject(
                "SELECT embedding <=> ?::vector FROM vector_store_bgem3 WHERE metadata->>'document_id' = ?",
                Double.class, queryVector, documentId.toString());
    }

    /**
     * [Purpose] Renders a {@code float[]} as a {@code vector(n)} SQL literal (e.g.
     *   {@code "[0.0,1.0,...]"}) for a raw {@code <=>} query bind parameter.
     * [Caution] Duplicated rather than reused from {@code ChunkSeeder#vectorLiteral} because that
     *   method is {@code private} -- mirrors {@code ChunkRepositoryIntegrationTest}/{@code
     *   ParadeDbBm25SearchAdapterIntegrationTest}'s own each-file-has-its-own-copy convention for
     *   the same reason.
     */
    private static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
