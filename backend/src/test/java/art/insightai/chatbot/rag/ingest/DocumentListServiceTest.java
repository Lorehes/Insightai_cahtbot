package art.insightai.chatbot.rag.ingest;

import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.domain.DocumentRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * [Purpose] Verifies {@link DocumentListService#listDocuments} delegates to {@link
 *   DocumentRepository#findAllByOrderByCreatedAtDesc()} and returns its result unchanged, with
 *   {@link DocumentRepository} mocked. Field-level DTO mapping and real {@code created_at}
 *   ordering against a database are dev-qa-tester's integration scope (Spec §6 "GET 목록 필드·정렬").
 * [Rationale] Mirrors {@code DocumentUploadServiceTest}'s mock-only style one class over — this
 *   service has no branch to cover beyond "call the sorted finder, return what it gives back", so
 *   a single delegation test is the whole unit-level surface.
 */
@ExtendWith(MockitoExtension.class)
class DocumentListServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    /**
     * [Purpose] Confirms the service calls the created_at-descending finder exactly once and
     *   returns its list as-is, without re-sorting or filtering.
     * [Rationale] Tagged {@code [document-ingestion §5]} rather than {@code
     *   [document-ingestion:AC-11]} -- with {@link DocumentRepository} mocked, this test only
     *   observes that the finder is called and its return value passed through; it proves
     *   delegation, not AC-11's 9-field serialization or real {@code created_at} ordering against a
     *   database (review 2026-08-30 T9 round1 Medium-2). Those are covered by dev-qa-tester's
     *   {@code DocumentListControllerIntegrationTest}, which Spec §4's AC-11 evidence cell already
     *   cites exclusively -- this class-level Javadoc's own [Rationale] said as much before the tag
     *   was corrected to match.
     */
    @Test
    @DisplayName("[document-ingestion §5] createdAt 내림차순 리포지토리 조회 결과를 그대로 반환한다")
    void listDocumentsDelegatesToCreatedAtDescendingFinder() {
        // given
        Document newer = new Document("newer.txt", "text/plain", 10L);
        Document older = new Document("older.txt", "text/plain", 20L);
        when(documentRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(newer, older));
        DocumentListService service = new DocumentListService(documentRepository);

        // when
        List<Document> result = service.listDocuments();

        // then
        assertThat(result).containsExactly(newer, older);
        verify(documentRepository).findAllByOrderByCreatedAtDesc();
        verifyNoMoreInteractions(documentRepository);
    }
}
