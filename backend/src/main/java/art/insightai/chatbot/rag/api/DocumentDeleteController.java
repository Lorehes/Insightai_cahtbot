package art.insightai.chatbot.rag.api;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import art.insightai.chatbot.rag.ingest.DocumentDeleteService;

/**
 * [Purpose] {@code DELETE /api/admin/documents/{id}} -- removes one document's row, chunks, and
 *   original file (document-ingestion Spec §5 Affected Surfaces, Tech Review T10).
 * [Rationale] Kept as its own controller class rather than a third method on {@link
 *   DocumentUploadController}/{@link DocumentListController}, mirroring how the Spec's Tech Review
 *   splits T5/T9/T10 into independent cards under the same {@code /api/admin/documents} path --
 *   {@link DocumentListController}'s own class-level [Rationale] already states that split
 *   convention explicitly. This class only translates HTTP in/out and delegates every guard and
 *   the actual delete to {@link DocumentDeleteService#delete}.
 * [Caution] Per §7 OQ-7 ("무보호 유지"), this endpoint -- like {@link DocumentUploadController}/
 *   {@link DocumentListController} -- is intentionally unauthenticated; do not add an auth check
 *   here without a Spec decision.
 * [AC] document-ingestion:AC-12, document-ingestion:AC-13
 */
@RestController
@RequestMapping("/api/admin/documents")
public class DocumentDeleteController {

    private final DocumentDeleteService documentDeleteService;

    public DocumentDeleteController(DocumentDeleteService documentDeleteService) {
        this.documentDeleteService = documentDeleteService;
    }

    /**
     * [Purpose] Entry point for {@code DELETE /api/admin/documents/{id}}. Delegates the
     *   existence check and the actual delete to {@link DocumentDeleteService#delete} -- see that
     *   method for the 404 case ({@link art.insightai.chatbot.rag.domain.DocumentNotFoundException},
     *   mapped by {@code GlobalExceptionHandler}).
     * [Rationale] Returns {@code 204 No Content} with an empty body, mirroring {@code
     *   ConversationController#delete}'s same shape one Spec over -- a delete leaves nothing new to
     *   read back.
     * [AC] document-ingestion:AC-12, document-ingestion:AC-13
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        documentDeleteService.delete(id);
    }
}
