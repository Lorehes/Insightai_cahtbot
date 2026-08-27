package art.insightai.chatbot.rag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import art.insightai.chatbot.rag.domain.RetrievedChunk;
import art.insightai.chatbot.rag.domain.SourceRef;

/**
 * [Purpose] Verifies {@link SourceRefMapper}'s field-by-field mapping from {@link RetrievedChunk}
 *   metadata to {@link SourceRef} (hybrid-rag Spec AC-11), across the metadata combinations D-T7
 *   calls out: {@code page} present/absent, and filenames containing characters that could break
 *   a naive mapping.
 */
class SourceRefMapperTest {

    private static final String DOCUMENT_ID = "11111111-1111-1111-1111-111111111111";

    private final SourceRefMapper mapper = new SourceRefMapper();

    @Test
    @DisplayName("[hybrid-rag:AC-11] page가 있으면 documentId·filename·score·snippet·page를 모두 채운다")
    void mapsAllFieldsWhenPagePresent() {
        // given: a chunk with a complete metadata set, including a numeric page
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-1",
                "본문 내용",
                Map.of(
                        "document_id", DOCUMENT_ID,
                        "filename", "회사소개.pdf",
                        "page", 3),
                0.042);

        // when
        SourceRef result = mapper.toSourceRef(chunk);

        // then
        assertThat(result.documentId()).isEqualTo(UUID.fromString(DOCUMENT_ID));
        assertThat(result.filename()).isEqualTo("회사소개.pdf");
        assertThat(result.score()).isEqualTo(0.042);
        assertThat(result.snippet()).isEqualTo("본문 내용");
        assertThat(result.page()).isEqualTo(3);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] page 키가 없으면 page는 null로 매핑된다")
    void mapsNullPageWhenKeyAbsent() {
        // given: Tika could not recover a page boundary (document-ingestion Spec OQ-5) — no
        //   "page" key at all
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-2",
                "본문",
                Map.of("document_id", DOCUMENT_ID, "filename", "메모.txt"),
                0.5);

        // when
        SourceRef result = mapper.toSourceRef(chunk);

        // then
        assertThat(result.page()).isNull();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] page 값이 명시적으로 null이어도 page는 null로 매핑된다")
    void mapsNullPageWhenValueExplicitlyNull() {
        // given: the key is present with an explicit null value (Map.of() can't hold null, so a
        //   mutable map is used here)
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("document_id", DOCUMENT_ID);
        metadata.put("filename", "메모.txt");
        metadata.put("page", null);
        RetrievedChunk chunk = new RetrievedChunk("chunk-3", "본문", metadata, 0.5);

        // when
        SourceRef result = mapper.toSourceRef(chunk);

        // then
        assertThat(result.page()).isNull();
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] 특수문자·공백이 섞인 filename도 그대로 보존한다")
    void preservesFilenameWithSpecialCharacters() {
        // given
        String filename = "2026 (최종) 보고서_v2 [draft].pdf";
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-4",
                "본문",
                Map.of("document_id", DOCUMENT_ID, "filename", filename),
                0.1);

        // when
        SourceRef result = mapper.toSourceRef(chunk);

        // then
        assertThat(result.filename()).isEqualTo(filename);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] content가 200자를 넘으면 snippet은 앞 200자로 잘린다")
    void truncatesSnippetToTwoHundredChars() {
        // given
        String longContent = "가".repeat(250);
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-5",
                longContent,
                Map.of("document_id", DOCUMENT_ID, "filename", "긴문서.pdf"),
                0.1);

        // when
        SourceRef result = mapper.toSourceRef(chunk);

        // then
        assertThat(result.snippet()).hasSize(200);
        assertThat(result.snippet()).isEqualTo(longContent.substring(0, 200));
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] content가 200자 이하면 snippet은 전체를 그대로 담는다")
    void keepsSnippetWholeWhenContentUnderLimit() {
        // given
        String shortContent = "짧은 본문";
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-6",
                shortContent,
                Map.of("document_id", DOCUMENT_ID, "filename", "짧은문서.pdf"),
                0.1);

        // when
        SourceRef result = mapper.toSourceRef(chunk);

        // then
        assertThat(result.snippet()).isEqualTo(shortContent);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] score는 RRF 융합 점수를 그대로 옮긴다(리랭크 점수로 덮어쓰지 않는다)")
    void copiesScoreVerbatim() {
        // given: score field represents the RRF fusion score by contract (Tech Review D-T6),
        //   whatever value the pipeline has already computed by the time this chunk arrives here
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-7",
                "본문",
                Map.of("document_id", DOCUMENT_ID, "filename", "문서.pdf"),
                0.01639344);

        // when
        SourceRef result = mapper.toSourceRef(chunk);

        // then
        assertThat(result.score()).isEqualTo(0.01639344);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] document_id가 없으면 IllegalStateException을 던진다")
    void throwsWhenDocumentIdMissing() {
        // given
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-8", "본문", Map.of("filename", "문서.pdf"), 0.1);

        // when / then
        assertThatThrownBy(() -> mapper.toSourceRef(chunk))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("document_id");
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] filename이 없으면 IllegalStateException을 던진다")
    void throwsWhenFilenameMissing() {
        // given
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-9", "본문", Map.of("document_id", DOCUMENT_ID), 0.1);

        // when / then
        assertThatThrownBy(() -> mapper.toSourceRef(chunk))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("filename");
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] document_id가 유효한 UUID 형식이 아니면 IllegalStateException을 던진다")
    void throwsWhenDocumentIdNotValidUuid() {
        // given
        RetrievedChunk chunk = new RetrievedChunk(
                "chunk-10",
                "본문",
                Map.of("document_id", "not-a-uuid", "filename", "문서.pdf"),
                0.1);

        // when / then
        assertThatThrownBy(() -> mapper.toSourceRef(chunk))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("[hybrid-rag:AC-11] toSourceRefs는 여러 청크를 순서를 보존해 매핑한다")
    void mapsListPreservingOrder() {
        // given
        RetrievedChunk first = new RetrievedChunk(
                "chunk-11", "첫 번째", Map.of("document_id", DOCUMENT_ID, "filename", "a.pdf"), 0.9);
        RetrievedChunk second = new RetrievedChunk(
                "chunk-12", "두 번째", Map.of("document_id", DOCUMENT_ID, "filename", "b.pdf"), 0.5);

        // when
        List<SourceRef> result = mapper.toSourceRefs(List.of(first, second));

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).filename()).isEqualTo("a.pdf");
        assertThat(result.get(1).filename()).isEqualTo("b.pdf");
    }
}
