package art.insightai.chatbot.rag.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import art.insightai.chatbot.rag.ingest.OrphanChunkCleanupService;

/**
 * [Purpose] {@code POST /api/admin/documents/orphan-chunks/cleanup} -- deletes every {@code
 *   vector_store_bgem3} chunk whose {@code documents} row no longer exists and reports the
 *   number removed (document-ingestion Spec §4 AC-18, Tech Review T10 High-1, user-approved
 *   fix 2).
 * [Rationale] Kept as its own controller class under a sub-path of {@code
 *   /api/admin/documents} rather than a fourth method on {@link DocumentDeleteController},
 *   mirroring how the Spec's Tech Review already splits T5/T9/T10 into independent controller
 *   classes under that same base path -- {@link DocumentListController}'s own class-level
 *   [Rationale] states that split convention explicitly. This class only translates HTTP out;
 *   the delete itself is {@link OrphanChunkCleanupService#cleanup}.
 * [Caution] Per §7 OQ-7 ("무보호 유지"), this endpoint -- like every other controller under
 *   {@code /api/admin/documents} -- is intentionally unauthenticated; do not add an auth check
 *   here without a Spec decision. This is a deliberate re-confirmation, not an oversight: this
 *   endpoint exists <i>because</i> a security-axis review finding (High-1) found orphaned chunks
 *   reachable through the unauthenticated search path with no way to remove them, but the finding
 *   was about that reachability and the missing removal path -- not about who may call the
 *   removal path itself. Access control for this whole admin surface is Spec §7 OQ-7's standing,
 *   already-accepted decision (127.0.0.1-bound in this project's current deployment, project
 *   CLAUDE.md), and this endpoint does not widen what an unauthenticated caller could already do
 *   through {@link DocumentDeleteController#delete}: it deletes rows that no longer correspond to
 *   any listed, retrievable document, so it cannot be used to remove a document a caller does not
 *   already have the (equally unauthenticated) means to remove via that endpoint. If §7 OQ-7 is
 *   ever revisited for a two-stage exposure transition (review 2026-08-30 T10 security Low-1),
 *   this endpoint moves with the rest of {@code /api/admin/documents}, not ahead of it.
 * [AC] document-ingestion:AC-18
 */
@RestController
@RequestMapping("/api/admin/documents/orphan-chunks")
public class OrphanChunkCleanupController {

    private final OrphanChunkCleanupService orphanChunkCleanupService;

    public OrphanChunkCleanupController(OrphanChunkCleanupService orphanChunkCleanupService) {
        this.orphanChunkCleanupService = orphanChunkCleanupService;
    }

    /**
     * [Purpose] Entry point for {@code POST /api/admin/documents/orphan-chunks/cleanup}.
     *   Delegates the delete to {@link OrphanChunkCleanupService#cleanup} and reports the count.
     * [AC] document-ingestion:AC-18
     */
    @PostMapping("/cleanup")
    public OrphanChunkCleanupResponse cleanup() {
        return new OrphanChunkCleanupResponse(orphanChunkCleanupService.cleanup());
    }

    /**
     * [Purpose] Response body of {@code POST /api/admin/documents/orphan-chunks/cleanup} -- the
     *   exact shape AC-18 requires: the number of chunk rows removed.
     */
    public record OrphanChunkCleanupResponse(int deletedCount) {
    }
}
