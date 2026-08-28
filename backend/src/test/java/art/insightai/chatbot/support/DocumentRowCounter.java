package art.insightai.chatbot.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * [Purpose] Shared {@code documents} row-count helper for document-ingestion integration tests
 *   that assert "the table's row count did not change" around a rejected/limited upload.
 * [Rationale] {@code DocumentUploadContainerSizeLimitIntegrationTest} and {@code
 *   DocumentUploadControllerIntegrationTest} each wrote an identical private {@code
 *   countDocuments()} method (review 2026-08-28 maintainability Low-3). This class centralizes
 *   that query so a third caller (e.g. a {@code max-document-count} quota test) does not need to
 *   copy it again, mirroring this package's existing precedent of centralizing a query duplicated
 *   across sibling test classes ({@link ChunkSeeder}) per the T4-established {@code support/}
 *   convention.
 * [Caution] Not a general-purpose repository -- reads {@code documents} directly via {@link
 *   JdbcTemplate} on purpose (project convention: 통합 테스트는 실제 인스턴스, 모킹 금지), independent of
 *   whatever repository/service is under test, so a bug in the class under test cannot also break
 *   this count.
 */
public final class DocumentRowCounter {

    private DocumentRowCounter() {
    }

    /**
     * [Purpose] Returns the current number of rows in {@code documents}.
     */
    public static long countDocuments(JdbcTemplate jdbcTemplate) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM documents", Long.class);
        return count != null ? count : 0L;
    }
}
