package art.insightai.chatbot.rag.api;

import art.insightai.chatbot.rag.domain.Document;
import art.insightai.chatbot.rag.ingest.DocumentUploadService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * [Purpose] {@code POST /api/admin/documents} -- accepts one multipart file upload and returns
 *   immediately with the created {@code documents} row's identity (document-ingestion Spec §5
 *   Affected Surfaces, Tech Review T5).
 * [Rationale] This class only translates HTTP in/out: it reads the {@code file} multipart part
 *   and delegates every validation/persistence decision to {@link DocumentUploadService}, mirroring
 *   the split {@code chat.api.ConversationController} already uses against {@code
 *   ConversationService}. The multipart part name {@code file} has no prior convention in this
 *   project to follow (document-ingestion Spec Tech Review card T5), so it was chosen as the
 *   plainest available name.
 * [Caution] Returns {@code 202 Accepted}, not {@code 201 Created} -- unlike {@code
 *   ConversationController#create}'s {@code 201}, this endpoint's response does not represent a
 *   fully-formed resource yet: parsing/embedding have not run (document-ingestion:AC-1), so 202
 *   ("accepted for processing") is the accurate status. Do not add a {@code Location} header
 *   pointing at a not-yet-implemented {@code GET /api/admin/documents/{id}} (T9 is a list
 *   endpoint only; no single-document GET exists in this Spec's Tech Review).
 * [AC] document-ingestion:AC-1
 */
@RestController
@RequestMapping("/api/admin/documents")
public class DocumentUploadController {

    private final DocumentUploadService documentUploadService;

    public DocumentUploadController(DocumentUploadService documentUploadService) {
        this.documentUploadService = documentUploadService;
    }

    /**
     * [Purpose] Entry point for {@code POST /api/admin/documents}. Delegates every guard (MIME
     *   whitelist, size, cumulative quota) and the actual persistence/file-write to {@link
     *   DocumentUploadService#upload} -- see that method for the 400/413/403 exception cases.
     * [AC] document-ingestion:AC-1, document-ingestion:AC-2, document-ingestion:AC-3,
     *   document-ingestion:AC-4
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentUploadResponse upload(@RequestParam("file") MultipartFile file) {
        Document document = documentUploadService.upload(file);
        return DocumentUploadResponse.from(document);
    }

    /** [Purpose] Response body for {@code POST /api/admin/documents} (document-ingestion:AC-1). */
    public record DocumentUploadResponse(UUID id, String filename, String status) {
        static DocumentUploadResponse from(Document document) {
            return new DocumentUploadResponse(
                    document.getId(), document.getFilename(), document.getStatus().name());
        }
    }
}
