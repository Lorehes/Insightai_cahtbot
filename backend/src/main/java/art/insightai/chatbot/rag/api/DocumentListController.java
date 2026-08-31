package art.insightai.chatbot.rag.api;

import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.ingest.DocumentListService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * [Purpose] {@code GET /api/admin/documents} -- lists every uploaded document with its current
 *   ingestion status, most recently created first (document-ingestion Spec §5 Affected Surfaces,
 *   Tech Review T9).
 * [Rationale] Kept as its own controller class rather than a second method on {@link
 *   DocumentUploadController}, mirroring how the Spec's Tech Review splits T5/T9/T10 into
 *   independent cards under the same {@code /api/admin/documents} path -- {@code
 *   DocumentUploadController}'s own class-level [Caution] already anticipates this endpoint as
 *   separate work. This class only translates HTTP out: every field comes straight from the
 *   {@code documents} row via {@link DocumentListService#listDocuments}, with no business logic
 *   here.
 * [Caution] Per §7 OQ-7 ("무보호 유지"), this endpoint -- like {@link DocumentUploadController} --
 *   is intentionally unauthenticated; do not add an auth check here without a Spec decision.
 *   {@code chunkCount}/{@code errorMessage}/{@code indexedAt} are {@code null} for any document
 *   that has not reached {@code INDEXED} (or {@code FAILED}, for {@code errorMessage}) because
 *   {@link Document} only ever sets them from {@link Document#markIndexed}/{@link
 *   Document#markFailed}, which run on the corresponding state transition -- not because of
 *   AD-8. AD-8 ({@link Document}'s own [Caution]) governs only the unrelated {@code title}
 *   field; no Spec/Tech Review item directly addresses these three fields' null policy, it
 *   follows from the entity's own state-conditional design. This DTO simply mirrors whatever
 *   the entity already holds without fabricating a placeholder value.
 * [AC] document-ingestion:AC-11
 */
@RestController
@RequestMapping("/api/admin/documents")
public class DocumentListController {

    private final DocumentListService documentListService;

    public DocumentListController(DocumentListService documentListService) {
        this.documentListService = documentListService;
    }

    /**
     * [Purpose] Entry point for {@code GET /api/admin/documents}. Delegates the query (including
     *   the {@code created_at} descending order) to {@link DocumentListService#listDocuments}.
     * [AC] document-ingestion:AC-11
     */
    @GetMapping
    public List<DocumentListItemResponse> list() {
        return documentListService.listDocuments().stream()
                .map(DocumentListItemResponse::from)
                .toList();
    }

    /**
     * [Purpose] One entry of {@code GET /api/admin/documents} -- the exact field set AC-11
     *   requires, in the order the AC lists them.
     */
    public record DocumentListItemResponse(
            UUID id,
            String filename,
            String mimeType,
            Long sizeBytes,
            String status,
            Integer chunkCount,
            String errorMessage,
            OffsetDateTime createdAt,
            OffsetDateTime indexedAt
    ) {
        static DocumentListItemResponse from(Document document) {
            return new DocumentListItemResponse(
                    document.getId(),
                    document.getFilename(),
                    document.getMimeType(),
                    document.getSizeBytes(),
                    document.getStatus().name(),
                    document.getChunkCount(),
                    document.getErrorMessage(),
                    document.getCreatedAt(),
                    document.getIndexedAt());
        }
    }
}
