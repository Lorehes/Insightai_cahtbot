package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;
import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.support.SampleDocumentFixtures;

/**
 * [Purpose] Exercises the real {@link DocumentParser} (Apache Tika) -&gt; {@link DocumentChunker}
 *   (Spring AI {@code TokenTextSplitter}) -&gt; {@link ChunkMetadataBuilder} pipeline against T4's
 *   genuine fixture files (txt/md/pdf), one test per allowed MIME-type shape. This is the
 *   integration counterpart {@link ChunkMetadataBuilderTest}'s class Javadoc points to
 *   ("Parsing/chunking themselves need real fixture files and are exercised by dev-qa-tester's
 *   integration tests") -- that class only feeds {@link ChunkMetadataBuilder} synthetic,
 *   already-split chunks.
 * [Rationale] No database or Spring context is needed here: none of the three collaborators
 *   under test touches a data store (that is T7/T8's job), so this instantiates all three by
 *   hand with real dependencies -- a real {@link org.springframework.ai.reader.tika.TikaDocumentReader}
 *   reading real file bytes and a real {@code TokenTextSplitter} -- rather than mocking any of
 *   them (project CLAUDE.md §"통합": 외부 IO는 실제 인스턴스로 검증). {@code AppProperties.Ingest} is
 *   built with AD-10's confirmed values (chunkSize=2000/minChunkSizeChars=500) instead of relying
 *   on Spring property binding, since no {@code @SpringBootTest} context is started.
 * [Caution] The PDF case doubles as a regression guard for AD-9: {@code
 *   sample-document.pdf} is a genuine 2-page file (Tika really does report {@code
 *   xmpTPg:NPages=2} for it -- see {@code SampleDocumentFixturesTest}), yet {@link
 *   DocumentParser}/{@link ChunkMetadataBuilder}'s own Javadoc documents that {@code
 *   TikaDocumentReader} never surfaces that page count to Spring AI at all. This test asserts
 *   {@code page} is still {@code null} on real PDF chunks specifically so a future change that
 *   swaps in a page-aware reader (e.g. {@code PagePdfDocumentReader}) is forced to update this
 *   test intentionally rather than silently regressing AC-8's "page is null when Tika can't
 *   supply it" branch back to a stale assumption.
 */
class DocumentIngestionPipelineIntegrationTest {

    private final DocumentParser parser = new DocumentParser();
    private final DocumentChunker chunker = new DocumentChunker(buildAppProperties());
    private final ChunkMetadataBuilder metadataBuilder = new ChunkMetadataBuilder();

    @Test
    @DisplayName("[document-ingestion:AC-8] txt 파일을 실제로 파싱·청킹하면 각 청크에 AC-8 메타데이터 6개 키가 채워지고 page는 null이다")
    void parsesAndChunksTextFixtureWithAc8Metadata() {
        assertPipelineProducesAc8CompliantChunks(
                SampleDocumentFixtures.TEXT_FILENAME,
                SampleDocumentFixtures.TEXT_MIME_TYPE,
                SampleDocumentFixtures.KEYWORD_TEXT);
    }

    @Test
    @DisplayName("[document-ingestion:AC-8] md 파일을 실제로 파싱·청킹하면 각 청크에 AC-8 메타데이터 6개 키가 채워지고 page는 null이다")
    void parsesAndChunksMarkdownFixtureWithAc8Metadata() {
        assertPipelineProducesAc8CompliantChunks(
                SampleDocumentFixtures.MARKDOWN_FILENAME,
                SampleDocumentFixtures.MARKDOWN_MIME_TYPE,
                SampleDocumentFixtures.KEYWORD_TEXT);
    }

    @Test
    @DisplayName("[document-ingestion:AC-8] 실제 2페이지 PDF를 파싱·청킹해도 page는 null이다 (Tika가 페이지 경계를 제공하지 않음, AD-9)")
    void parsesAndChunksPdfFixtureWithNullPageDespiteRealPageBoundary() {
        // given: a genuinely 2-page PDF -- SampleDocumentFixturesTest already confirms Tika
        // itself can see xmpTPg:NPages=2 for these exact bytes, so a non-null page here would
        // only be possible if TikaDocumentReader propagated that count, which it does not.
        Resource resource = new ClassPathResource("fixtures/" + SampleDocumentFixtures.PDF_FILENAME);
        Document documentEntity = new Document(
                SampleDocumentFixtures.PDF_FILENAME, SampleDocumentFixtures.PDF_MIME_TYPE, 0L);

        // when
        List<org.springframework.ai.document.Document> parsed = parser.parse(resource);
        List<org.springframework.ai.document.Document> chunks = chunker.chunk(parsed);
        List<org.springframework.ai.document.Document> stamped = metadataBuilder.build(chunks, documentEntity);

        // then: Tika still collapses the 2 pages into a single parsed Document (DocumentParser
        // [Caution]), and both pages' text survives into the chunk stream
        assertThat(parsed).hasSize(1);
        String combinedText = chunks.stream()
                .map(org.springframework.ai.document.Document::getText)
                .collect(Collectors.joining());
        assertThat(combinedText).contains(SampleDocumentFixtures.KEYWORD_PDF);
        assertThat(combinedText).contains(SampleDocumentFixtures.KNOWN_SENTENCE_PDF_PAGE_2);

        // then: every real chunk still gets all 6 AC-8 keys, page null despite the real 2 pages
        assertThat(stamped).isNotEmpty();
        for (org.springframework.ai.document.Document chunk : stamped) {
            Map<String, Object> metadata = chunk.getMetadata();
            assertThat(metadata).containsKey("page");
            assertThat(metadata.get("page")).isNull();
        }
    }

    /**
     * [Purpose] Shared assertion body for the txt/md cases: parses+chunks the given fixture
     *   through the real pipeline and checks every AC-8 key lands on every resulting chunk.
     */
    private void assertPipelineProducesAc8CompliantChunks(String filename, String mimeType, String expectedKeyword) {
        // given
        Resource resource = new ClassPathResource("fixtures/" + filename);
        Document documentEntity = new Document(filename, mimeType, 0L);

        // when
        List<org.springframework.ai.document.Document> parsed = parser.parse(resource);
        List<org.springframework.ai.document.Document> chunks = chunker.chunk(parsed);
        List<org.springframework.ai.document.Document> stamped = metadataBuilder.build(chunks, documentEntity);

        // then: real Tika extraction actually preserved the fixture's content through chunking
        assertThat(chunks).isNotEmpty();
        String combinedText = chunks.stream()
                .map(org.springframework.ai.document.Document::getText)
                .collect(Collectors.joining());
        assertThat(combinedText).contains(expectedKeyword);

        // then: every chunk carries all 6 AC-8 keys with the expected fixed/derived values
        for (int i = 0; i < stamped.size(); i++) {
            Map<String, Object> metadata = stamped.get(i).getMetadata();
            assertThat(metadata)
                    .containsEntry("document_id", documentEntity.getId().toString())
                    .containsEntry("filename", filename)
                    .containsEntry("chunk_index", i)
                    .containsEntry("dept", "ALL")
                    .containsEntry("acl", "ALL")
                    .containsKey("page");
            assertThat(metadata.get("page")).isNull();
        }
    }

    /**
     * [Purpose] Builds a real (non-mocked) {@link AppProperties} carrying AD-10's confirmed
     *   {@code TokenTextSplitter} sizing, for direct construction of {@link DocumentChunker}
     *   outside a Spring context.
     * [Rationale] Delegates to {@link TestAppPropertiesFactory}, whose default {@code Ingest}
     *   component already matches AD-10's confirmed values ({@code chunkSize=2000},
     *   {@code minChunkSizeChars=500}), instead of hand-building an {@link AppProperties.Ingest}
     *   here. {@link DocumentChunker} only reads {@code ingest.chunkSize()}/{@code
     *   ingest.minChunkSizeChars()} (see {@link DocumentChunker}'s own Javadoc), so this test
     *   never depends on the other five components' values -- routing through the shared factory
     *   means a future {@link AppProperties} shape change (INC-008) needs to update the factory
     *   only, not this file too.
     */
    private static AppProperties buildAppProperties() {
        return TestAppPropertiesFactory.valid();
    }
}
