package art.insightai.chatbot.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;

/**
 * [Purpose] Shared, small, deterministic sample files (and the values a test can expect after
 * parsing them) for every document-ingestion Spec test that needs a real file to upload/parse
 * -- one canonical fixture per allowed text-shaped MIME type this Spec's AD-5 whitelist covers
 * without requiring binary-format guesswork (DOCX/PPTX/XLSX are intentionally not included
 * here; no card in this Spec's Tech Review requires a fixture for them yet -- add one there if
 * a future card needs it instead of guessing a format here).
 * [Rationale] document-ingestion Spec §6 "공통 전제": "테스트 픽스처 문서는 회사 문서(R4 미확보)가
 * 아니라 저장소에 넣는 소형 샘플 파일을 쓴다." Card T4 centralizes these so T5-T10 don't each invent
 * their own ad hoc fixture (Tech Review §8 rationale for splitting T4 out as its own card).
 * All three files live under {@code src/test/resources/fixtures/} and share one repeated,
 * distinctive keyword ({@link #KEYWORD_TEXT}/{@link #KEYWORD_PDF}) so a test can run a BM25
 * (AC-10) or vector-similarity (AC-9) search against the word/sentence and expect a hit back.
 * [Caution] {@code sample-document.pdf} was generated once with Apache PDFBox (already a
 * transitive dependency via {@code spring-ai-tika-document-reader} -- no new dependency added)
 * using the Standard 14 Helvetica font, which only supports Latin-1 text -- that is why its
 * content is English while the {@code .txt}/{@code .md} fixtures are Korean (this Spec's AC-10
 * requires a Korean BM25 hit, which those two already cover). The PDF is deliberately 2 pages
 * so a test can assert AD-9's "Tika provides a page boundary" branch; empirically confirmed via
 * a disposable verification test (see {@code docs/dev-log/backend.jsonl} document-ingestion-T4
 * entry) that Tika's {@code AutoDetectParser} reports {@code xmpTPg:NPages=2} for it and that
 * both pages' text round-trips through {@code Tika#parseToString}. Do not hand-edit the PDF
 * bytes; regenerate with PDFBox if the expected text below ever needs to change.
 */
public final class SampleDocumentFixtures {

    private static final String FIXTURE_DIR = "fixtures/";

    /** [Purpose] Plain-text fixture -- {@code text/plain} branch of AD-5's MIME whitelist. */
    public static final String TEXT_FILENAME = "sample-document.txt";
    public static final String TEXT_MIME_TYPE = "text/plain";

    /** [Purpose] Markdown fixture -- {@code text/markdown} branch of AD-5's MIME whitelist. */
    public static final String MARKDOWN_FILENAME = "sample-document.md";
    public static final String MARKDOWN_MIME_TYPE = "text/markdown";

    /**
     * [Purpose] PDF fixture -- {@code application/pdf} branch of AD-5's MIME whitelist, and the
     * only one of the three with a genuine multi-page structure (see class [Caution]).
     */
    public static final String PDF_FILENAME = "sample-document.pdf";
    public static final String PDF_MIME_TYPE = "application/pdf";
    public static final int PDF_PAGE_COUNT = 2;

    /**
     * [Purpose] The distinctive Korean word repeated throughout the {@code .txt}/{@code .md}
     * fixtures -- feed this to a BM25 query for AC-10, or to a Korean MIME-whitelist unit test
     * that needs a realistic (non-Latin) sample string.
     * [Caution] Measured live against the {@code chatbot-db} ParadeDB container (review
     * 2026-08-26_document-ingestion-T4.md Medium item -- "이미 한 번 Block까지 갔던 함정의
     * 반복", project rule P3): {@code content ||| '인사이트에이아이'} matches this bare form AND
     * every particle-suffixed form the fixture text actually uses -- {@code 인사이트에이아이는}
     * (topic marker 는) and {@code 인사이트에이아이의} (genitive marker 의) both matched with
     * scores 0.333 and 0.374 respectively (bare form scored 0.553, all three rows inserted
     * side-by-side under one throwaway {@code document_id} then deleted). lindera does NOT
     * fragment this specific word here -- it is not guaranteed for every Korean noun+particle
     * combination in general (see {@code ParadeDbBm25SearchAdapterIntegrationTest}'s class
     * Javadoc, finding 2, for a case where an unregistered compound word CAN fragment and
     * accidentally match unrelated rows). If a future edit to this fixture's text introduces a
     * particle form not listed above, re-measure before trusting an AC-10 test built on it.
     */
    public static final String KEYWORD_TEXT = "인사이트에이아이";

    /** [Purpose] An exact sentence copied from {@code sample-document.txt} -- feed this as a
     * vector-similarity query for AC-9; the chunk it was copied from should score highest.
     * [Caution] This claim depends on {@link StubEmbeddingModel}'s token-hashing vectors, which
     * make a chunk containing this exact sentence verbatim score strictly higher against this
     * query than an unrelated chunk (review 2026-08-26_document-ingestion-T4.md High-1) -- see
     * {@link StubEmbeddingModelTest#queryScoresSourceChunkHigherThanUnrelatedChunk()} for the
     * self-verification of that property using this exact constant. It does not hold for an
     * arbitrary/random vector stub. */
    public static final String KNOWN_SENTENCE_TEXT =
            "인사이트에이아이는 사내 업무용 챗봇과 문서 검색 플랫폼을 만드는 가상의 기술 회사다.";

    /** [Purpose] The distinctive English word repeated throughout {@code sample-document.pdf}
     * (Latin-only, see class [Caution]) -- use for a PDF-specific keyword/vector query. */
    public static final String KEYWORD_PDF = "InsightAI";

    /** [Purpose] An exact sentence from {@code sample-document.pdf}'s second page -- pairs with
     * {@link #PDF_PAGE_COUNT} to assert a chunk sourced from page 2 carries {@code page: 2}. */
    public static final String KNOWN_SENTENCE_PDF_PAGE_2 = "Page two of the InsightAI PDF fixture.";

    /**
     * [Purpose] Chunk metadata keys AC-8 requires on every indexed chunk, verbatim from that
     * AC's text -- reuse this instead of a test hand-typing the set (and risking a typo/omission
     * silently weakening an AC-8 test). Values are not asserted here: {@code dept}/{@code acl}
     * are fixed to the literal string {@code "ALL"} by AC-8, but {@code document_id}/
     * {@code filename}/{@code chunk_index}/{@code page} are per-chunk and only T6/T7's own
     * tests know what to expect for a given upload.
     */
    public static final Set<String> AC8_REQUIRED_METADATA_KEYS =
            Set.of("document_id", "filename", "chunk_index", "dept", "acl", "page");

    private SampleDocumentFixtures() {
    }

    /**
     * [Purpose] Reads one of this class's fixture files from the test classpath.
     * [Side effects] None beyond the read itself -- callers get a fresh copy of the bytes every
     * call, so mutating the returned array is safe.
     */
    public static byte[] readBytes(String filename) {
        try (InputStream in = SampleDocumentFixtures.class.getClassLoader()
                .getResourceAsStream(FIXTURE_DIR + filename)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Fixture not found on classpath: " + FIXTURE_DIR + filename
                                + " -- expected under backend/src/test/resources/" + FIXTURE_DIR);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read fixture " + filename, e);
        }
    }
}
