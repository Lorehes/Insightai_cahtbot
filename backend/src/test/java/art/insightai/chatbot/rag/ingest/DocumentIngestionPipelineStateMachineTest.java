package art.insightai.chatbot.rag.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.Resource;

import art.insightai.chatbot.common.config.AppProperties;
import art.insightai.chatbot.common.config.TestAppPropertiesFactory;
import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentRepository;
import art.insightai.chatbot.rag.domain.DocumentStatus;

/**
 * [Purpose] Unit tests for {@link DocumentIngestionPipeline}'s orchestration: the order in which
 *   it drives the status state machine (document-ingestion:AC-5), what it does when a step throws
 *   (document-ingestion:AC-6), and where the recorded {@code chunk_count} comes from
 *   (document-ingestion:AC-7).
 * [Rationale] Tech Review AD-4 settles how transition ordering is verified: not with a history
 *   table (that would be a schema change owned by the chat Spec, G2) but by observing calls to
 *   {@link DocumentStatusTransitioner}. With every collaborator mocked, an {@link InOrder} check
 *   here proves the pipeline <i>requests</i> PENDING -&gt; PARSING -&gt; EMBEDDING -&gt; INDEXED in
 *   that sequence and nothing else, which is precisely the half of AC-5 that does not depend on a
 *   database. Mocks also make the failure paths reachable at all: a parse/embed exception, a
 *   zero-chunk document and a failing compensation delete are all awkward to provoke against a real
 *   ParadeDB instance but are the cases AC-6 is written about.
 *   <p>
 *   The class is named for the state machine rather than for the class under test because
 *   {@code DocumentIngestionPipelineIntegrationTest} already exists for card T6's
 *   parse-&gt;chunk-&gt;metadata chain, and two files whose names differ only by suffix would be a
 *   standing invitation to run the wrong one.
 * [Caution] These tests do NOT establish AC-5's "상태가 순서대로 <b>기록</b>된다". Spec §4's AC-5 cell
 *   explicitly warns against promoting that AC on unit evidence: recording means committed rows
 *   observable from outside a running pipeline, which only a live-database integration test (owned
 *   by dev-qa-tester) can show. What is proven here is the call order and the failure semantics.
 *   Likewise AC-7's "실제 행 수와 일치" is only shown here in the weaker sense that the pipeline
 *   sources the number from {@link ChunkRepository#countByDocumentId} rather than from
 *   {@code chunks.size()} -- whether that query counts what AC-7 says it counts is T3's own
 *   integration test.
 *   <p>
 *   <b>Every case must stage a real file at {@code <temp upload-dir>/<id>}.</b> Since review T8
 *   L-1 the pipeline rejects an original path that is not a regular file <i>before</i> calling the
 *   parser, so a mocked {@link DocumentParser} alone no longer gets the happy path off the ground;
 *   {@link #givenPendingDocument()} writes that file. A test that forgets it does not fail
 *   confusingly -- it lands on the FAILED path and its status assertions break there.
 *   <p>
 *   <b>Coverage limit of the symbolic-link guard.</b>
 *   {@link #failsWithoutFollowingASymbolicLinkAtTheOriginalPath()} is the only case that can tell
 *   {@code Files.isRegularFile(path, NOFOLLOW_LINKS)} apart from the option-less call, and it can
 *   only run where the OS lets an unprivileged process create a symbolic link (on Windows that
 *   needs Developer Mode or {@code SeCreateSymbolicLinkPrivilege}). Where it cannot, it aborts
 *   loudly instead of passing. The directory case beside it runs everywhere but does <i>not</i>
 *   discriminate the option -- a regression that drops {@code NOFOLLOW_LINKS} keeps it green.
 *   Read a green run of this class as "the guard exists", not as "links are covered", and look
 *   for a skip mark before claiming the latter.
 */
@ExtendWith(MockitoExtension.class)
class DocumentIngestionPipelineStateMachineTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentStatusTransitioner statusTransitioner;

    @Mock
    private DocumentParser documentParser;

    @Mock
    private DocumentChunker documentChunker;

    @Mock
    private ChunkMetadataBuilder chunkMetadataBuilder;

    @Mock
    private ChunkIndexer chunkIndexer;

    @Mock
    private ChunkRepository chunkRepository;

    @TempDir
    private Path uploadDir;

    private DocumentIngestionPipeline pipeline() {
        return new DocumentIngestionPipeline(documentRepository, statusTransitioner, documentParser,
                documentChunker, chunkMetadataBuilder, chunkIndexer, chunkRepository,
                appProperties());
    }

    private AppProperties appProperties() {
        AppProperties.Ingest defaults = TestAppPropertiesFactory.valid().ingest();
        return TestAppPropertiesFactory.builder()
                .ingest(new AppProperties.Ingest(uploadDir.toString(), defaults.maxFileSize(),
                        defaults.allowedMimeTypes(), defaults.chunkSize(),
                        defaults.minChunkSizeChars(), defaults.maxDocumentCount()))
                .build();
    }

    private Document givenPendingDocument() {
        Document document = registeredPendingDocument();
        writeOriginalFile(document.getId(), "원본 문서 본문");
        return document;
    }

    private Document registeredPendingDocument() {
        Document document = new Document("sample-document.txt", "text/plain", 42L);
        when(documentRepository.findById(document.getId())).thenReturn(Optional.of(document));
        return document;
    }

    private void writeOriginalFile(UUID documentId, String content) {
        try {
            Files.writeString(uploadDir.resolve(documentId.toString()), content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stage the original file of " + documentId, e);
        }
    }

    private List<org.springframework.ai.document.Document> chunks(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> org.springframework.ai.document.Document.builder()
                        .text("청크 본문 " + i)
                        .build())
                .toList();
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] 성공 경로는 PENDING 문서를 PARSING → EMBEDDING → INDEXED 순서로 전이시킨다")
    void drivesStatusTransitionsInOrderOnSuccess() {
        // given
        Document document = givenPendingDocument();
        UUID documentId = document.getId();
        List<org.springframework.ai.document.Document> parsed = chunks(1);
        List<org.springframework.ai.document.Document> split = chunks(3);
        when(documentParser.parse(any(Resource.class))).thenReturn(parsed);
        when(documentChunker.chunk(parsed)).thenReturn(split);
        when(chunkRepository.countByDocumentId(documentId)).thenReturn(3);

        // when
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PENDING);
        pipeline().ingest(documentId);

        // then
        InOrder inOrder = inOrder(statusTransitioner, chunkIndexer);
        inOrder.verify(statusTransitioner).transitionTo(documentId, DocumentStatus.PARSING);
        inOrder.verify(statusTransitioner).transitionTo(documentId, DocumentStatus.EMBEDDING);
        inOrder.verify(chunkIndexer).index(split);
        inOrder.verify(statusTransitioner).markIndexed(documentId, 3);
        verify(statusTransitioner, never()).markFailed(any(), any());
        verify(chunkRepository, never()).deleteByDocumentId(any());
    }

    @Test
    @DisplayName("[document-ingestion:AC-5] EMBEDDING 전이는 청크 색인 이전에 일어난다 (색인 중 상태가 외부에서 관찰 가능해야 한다)")
    void movesToEmbeddingBeforeIndexingStarts() {
        // given
        Document document = givenPendingDocument();
        UUID documentId = document.getId();
        List<org.springframework.ai.document.Document> parsed = chunks(1);
        List<org.springframework.ai.document.Document> split = chunks(2);
        when(documentParser.parse(any(Resource.class))).thenReturn(parsed);
        when(documentChunker.chunk(parsed)).thenReturn(split);
        when(chunkRepository.countByDocumentId(documentId)).thenReturn(2);

        // when
        pipeline().ingest(documentId);

        // then: metadata is stamped before indexing too, otherwise AC-8's keys would be missing
        InOrder inOrder = inOrder(chunkMetadataBuilder, statusTransitioner, chunkIndexer);
        inOrder.verify(chunkMetadataBuilder).build(split, document);
        inOrder.verify(statusTransitioner).transitionTo(documentId, DocumentStatus.EMBEDDING);
        inOrder.verify(chunkIndexer).index(split);
    }

    @Test
    @DisplayName("[document-ingestion:AC-7] chunk_count 는 chunks.size() 가 아니라 ChunkRepository 가 센 실제 행 수로 기록된다")
    void recordsChunkCountFromTheRepositoryCountNotFromTheChunkListSize() {
        // given: the in-memory chunk list and the row count deliberately disagree, so a
        // chunks.size() implementation cannot pass this test
        Document document = givenPendingDocument();
        UUID documentId = document.getId();
        List<org.springframework.ai.document.Document> parsed = chunks(1);
        List<org.springframework.ai.document.Document> split = chunks(4);
        when(documentParser.parse(any(Resource.class))).thenReturn(parsed);
        when(documentChunker.chunk(parsed)).thenReturn(split);
        when(chunkRepository.countByDocumentId(documentId)).thenReturn(7);

        // when
        pipeline().ingest(documentId);

        // then
        verify(statusTransitioner).markIndexed(documentId, 7);
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] 파싱 단계 예외는 FAILED + 비어 있지 않은 error_message 로 이어지고 청크를 보상 삭제한다")
    void marksFailedAndDeletesChunksWhenParsingThrows() {
        // given
        Document document = givenPendingDocument();
        UUID documentId = document.getId();
        when(documentParser.parse(any(Resource.class)))
                .thenThrow(new RuntimeException("tika blew up"));

        // when
        pipeline().ingest(documentId);

        // then
        InOrder inOrder = inOrder(chunkRepository, statusTransitioner);
        inOrder.verify(chunkRepository).deleteByDocumentId(documentId);
        inOrder.verify(statusTransitioner).markFailed(
                documentId, DocumentIngestionPipeline.PARSE_FAILURE_MESSAGE);
        assertThat(DocumentIngestionPipeline.PARSE_FAILURE_MESSAGE).isNotBlank();
        verify(statusTransitioner, never()).markIndexed(any(), anyInt());
        verifyNoInteractions(chunkIndexer);
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] 임베딩·색인 단계 예외도 FAILED + 보상 삭제로 이어진다 (부분 색인 금지)")
    void marksFailedAndDeletesChunksWhenIndexingThrows() {
        // given
        Document document = givenPendingDocument();
        UUID documentId = document.getId();
        List<org.springframework.ai.document.Document> parsed = chunks(1);
        List<org.springframework.ai.document.Document> split = chunks(3);
        when(documentParser.parse(any(Resource.class))).thenReturn(parsed);
        when(documentChunker.chunk(parsed)).thenReturn(split);
        doThrow(new RuntimeException("embedding endpoint refused"))
                .when(chunkIndexer).index(split);

        // when
        pipeline().ingest(documentId);

        // then: the compensating delete must precede FAILED so no observer ever sees FAILED
        // while orphaned chunks are still readable
        InOrder inOrder = inOrder(chunkRepository, statusTransitioner);
        inOrder.verify(chunkRepository).deleteByDocumentId(documentId);
        inOrder.verify(statusTransitioner).markFailed(
                documentId, DocumentIngestionPipeline.INDEX_FAILURE_MESSAGE);
        verify(statusTransitioner, never()).markIndexed(any(), anyInt());
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] 청크가 0건이면 임베딩을 시도하지 않고 FAILED 로 종결한다")
    void failsWithoutEmbeddingWhenChunkingProducesNothing() {
        // given: an empty or whitespace-only file parses fine but yields no chunk
        Document document = givenPendingDocument();
        UUID documentId = document.getId();
        List<org.springframework.ai.document.Document> parsed = chunks(1);
        when(documentParser.parse(any(Resource.class))).thenReturn(parsed);
        when(documentChunker.chunk(parsed)).thenReturn(List.of());

        // when
        pipeline().ingest(documentId);

        // then: it can never become INDEXED (AC-7 requires chunk_count >= 1), and it must not
        // pay for an embedding call for a document with nothing to embed
        verify(statusTransitioner, never()).transitionTo(documentId, DocumentStatus.EMBEDDING);
        verifyNoInteractions(chunkIndexer);
        verify(statusTransitioner).markFailed(
                documentId, DocumentIngestionPipeline.EMPTY_CONTENT_FAILURE_MESSAGE);
        assertThat(DocumentIngestionPipeline.EMPTY_CONTENT_FAILURE_MESSAGE).isNotBlank();
    }

    @Test
    @DisplayName("[document-ingestion:AC-7] 색인 후 실제 행 수가 0 이면 INDEXED 로 가지 않고 FAILED 로 종결한다")
    void failsWhenIndexingProducedNoRow() {
        // given
        Document document = givenPendingDocument();
        UUID documentId = document.getId();
        List<org.springframework.ai.document.Document> parsed = chunks(1);
        List<org.springframework.ai.document.Document> split = chunks(2);
        when(documentParser.parse(any(Resource.class))).thenReturn(parsed);
        when(documentChunker.chunk(parsed)).thenReturn(split);
        when(chunkRepository.countByDocumentId(documentId)).thenReturn(0);

        // when
        pipeline().ingest(documentId);

        // then
        verify(statusTransitioner, never()).markIndexed(any(), anyInt());
        verify(statusTransitioner).markFailed(
                documentId, DocumentIngestionPipeline.EMPTY_INDEX_FAILURE_MESSAGE);
    }

    @Test
    @DisplayName("[document-ingestion:AC-6] 보상 삭제가 실패해도 FAILED 는 기록되고 error_message 가 그 사실을 알린다")
    void stillMarksFailedWhenCompensatingDeleteItselfFails() {
        // given
        Document document = givenPendingDocument();
        UUID documentId = document.getId();
        when(documentParser.parse(any(Resource.class)))
                .thenThrow(new RuntimeException("tika blew up"));
        when(chunkRepository.deleteByDocumentId(documentId))
                .thenThrow(new RuntimeException("database unreachable"));

        // when
        pipeline().ingest(documentId);

        // then: leaving the document stuck in PARSING would be unrecoverable (no retry path,
        // Spec OQ-3/OQ-8), so FAILED still gets recorded -- but it must not silently claim the
        // chunks are gone
        ArgumentCaptor<String> errorMessage = ArgumentCaptor.forClass(String.class);
        verify(statusTransitioner).markFailed(eq(documentId), errorMessage.capture());
        assertThat(errorMessage.getValue())
                .startsWith(DocumentIngestionPipeline.PARSE_FAILURE_MESSAGE)
                .endsWith(DocumentIngestionPipeline.CLEANUP_FAILED_SUFFIX);
    }

    @Test
    @DisplayName("[document-ingestion §5] 처리 직전에 삭제된 문서는 아무 것도 하지 않고 건너뛴다")
    void skipsSilentlyWhenTheDocumentNoLongerExists() {
        // given: the admin deleted the document (T10) between commit and this task being scheduled
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());

        // when
        pipeline().ingest(documentId);

        // then
        verifyNoInteractions(statusTransitioner, documentParser, documentChunker,
                chunkMetadataBuilder, chunkIndexer);
        verify(chunkRepository, never()).deleteByDocumentId(any());
        verify(chunkRepository, never()).countByDocumentId(any());
    }

    @Test
    @DisplayName("[document-ingestion §5] 파싱 대상 파일은 upload-dir 아래 문서 id 이름의 파일이다 (호출자 파일명 미사용)")
    void readsTheOriginalFileFromTheIdDerivedPath() {
        // given
        Document document = givenPendingDocument();
        UUID documentId = document.getId();
        when(documentParser.parse(any(Resource.class))).thenReturn(chunks(1));
        when(documentChunker.chunk(anyList())).thenReturn(chunks(1));
        when(chunkRepository.countByDocumentId(documentId)).thenReturn(1);

        // when
        pipeline().ingest(documentId);

        // then
        ArgumentCaptor<Resource> resource = ArgumentCaptor.forClass(Resource.class);
        verify(documentParser).parse(resource.capture());
        assertThat(resource.getValue().getFilename()).isEqualTo(documentId.toString());
        assertThat(resource.getValue().getFilename()).isNotEqualTo(document.getFilename());
    }

    @Test
    @DisplayName("[document-ingestion §5] 원본 경로가 일반 파일이 아니면 파서를 부르지 않고 파싱 실패로 종결한다")
    void failsWithoutParsingWhenTheOriginalPathIsNotARegularFile() throws IOException {
        // given: the id-derived path exists but is a directory rather than the uploaded file
        Document document = registeredPendingDocument();
        Files.createDirectory(uploadDir.resolve(document.getId().toString()));

        // when
        pipeline().ingest(document.getId());

        // then: no new status and no new client-visible string -- it joins the parse-failure path
        verifyNoInteractions(documentParser, documentChunker, chunkIndexer);
        verify(statusTransitioner).markFailed(
                document.getId(), DocumentIngestionPipeline.PARSE_FAILURE_MESSAGE);
        verify(statusTransitioner, never()).markIndexed(any(), anyInt());
    }

    @Test
    @DisplayName("[document-ingestion §5] 원본 자리에 심볼릭 링크가 놓이면 링크 대상을 파싱하지 않는다")
    void failsWithoutFollowingASymbolicLinkAtTheOriginalPath() throws IOException {
        // given: someone with local write access swaps the original for a link to a file the
        // upload MIME allow-list never saw; following it would index that file's contents and
        // expose them through the unauthenticated RAG endpoint
        Document document = registeredPendingDocument();
        Path linkTarget = uploadDir.resolve("link-target.txt");
        Files.writeString(linkTarget, "링크 대상 파일의 본문");
        Path originalPath = uploadDir.resolve(document.getId().toString());
        try {
            Files.createSymbolicLink(originalPath, linkTarget);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // aborts (a visible skip) rather than passing: a silent pass would claim coverage
            // this run did not have -- see the class [Caution]
            abort("심볼릭 링크를 만들 수 없어 NOFOLLOW_LINKS 를 실행으로 검증할 수 없다: " + e);
        }

        // when
        pipeline().ingest(document.getId());

        // then: only NOFOLLOW_LINKS makes this fail -- without it the link resolves to a regular
        // file and the parser is handed the target's bytes
        verifyNoInteractions(documentParser, documentChunker, chunkIndexer);
        verify(statusTransitioner).markFailed(
                document.getId(), DocumentIngestionPipeline.PARSE_FAILURE_MESSAGE);
    }
}
