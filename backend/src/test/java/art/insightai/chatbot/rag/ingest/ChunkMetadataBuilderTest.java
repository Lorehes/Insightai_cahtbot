package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import art.insightai.chatbot.rag.domain.Document;

/**
 * [Purpose] Unit tests for {@link ChunkMetadataBuilder}'s per-chunk metadata stamping (AC-8).
 * [Rationale] Parsing/chunking themselves need real fixture files and are exercised by
 *   dev-qa-tester's integration tests; this class only needs synthetic {@code
 *   org.springframework.ai.document.Document} chunks with empty metadata, so a plain unit test
 *   (no Spring context) is sufficient.
 */
class ChunkMetadataBuilderTest {

    private final ChunkMetadataBuilder builder = new ChunkMetadataBuilder();

    @Test
    @DisplayName("[document-ingestion:AC-8] 청크 metadata에 document_id/filename/chunk_index/dept/acl이 채워진다")
    void stampsAllRequiredKeysWithExpectedValues() {
        // given: a document entity and two already-split chunk Documents with empty metadata
        Document document = new Document("report.pdf", "application/pdf", 1024L);
        List<org.springframework.ai.document.Document> chunks = List.of(
                new org.springframework.ai.document.Document("first chunk text"),
                new org.springframework.ai.document.Document("second chunk text"));

        // when
        List<org.springframework.ai.document.Document> result = builder.build(chunks, document);

        // then: every required key is present with the expected value, per chunk
        for (int i = 0; i < result.size(); i++) {
            Map<String, Object> metadata = result.get(i).getMetadata();
            assertThat(metadata)
                    .containsEntry("document_id", document.getId().toString())
                    .containsEntry("filename", "report.pdf")
                    .containsEntry("chunk_index", i)
                    .containsEntry("dept", "ALL")
                    .containsEntry("acl", "ALL");
        }
    }

    @Test
    @DisplayName("[document-ingestion:AC-8] page 키는 존재하되 값은 null이다 (Tika가 페이지 경계를 제공하지 않음)")
    void pageKeyIsPresentButNull() {
        // given
        Document document = new Document("notes.txt", "text/plain", 10L);
        List<org.springframework.ai.document.Document> chunks =
                List.of(new org.springframework.ai.document.Document("chunk text"));

        // when
        List<org.springframework.ai.document.Document> result = builder.build(chunks, document);

        // then: the key exists (containsKey) even though its value is null -- distinct from
        // "key absent", which is exactly what AC-8 requires
        Map<String, Object> metadata = result.get(0).getMetadata();
        assertThat(metadata).containsKey("page");
        assertThat(metadata.get("page")).isNull();
    }

    @Test
    @DisplayName("[document-ingestion:AC-8] chunk_index는 입력 리스트 순서대로 0부터 매겨진다")
    void chunkIndexFollowsListOrder() {
        // given: three chunks in a specific order
        Document document = new Document("multi.md", "text/markdown", 500L);
        List<org.springframework.ai.document.Document> chunks = List.of(
                new org.springframework.ai.document.Document("a"),
                new org.springframework.ai.document.Document("b"),
                new org.springframework.ai.document.Document("c"));

        // when
        List<org.springframework.ai.document.Document> result = builder.build(chunks, document);

        // then
        assertThat(result.get(0).getMetadata()).containsEntry("chunk_index", 0);
        assertThat(result.get(1).getMetadata()).containsEntry("chunk_index", 1);
        assertThat(result.get(2).getMetadata()).containsEntry("chunk_index", 2);
    }
}
