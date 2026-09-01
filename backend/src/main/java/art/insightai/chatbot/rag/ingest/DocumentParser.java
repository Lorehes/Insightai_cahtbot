package art.insightai.chatbot.rag.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * [Purpose] Parses an uploaded file's bytes into Spring AI {@link Document} instances -- one per
 *   page for PDFs (via {@link PagePdfDocumentReader}), one per file for everything else in the
 *   ingestion whitelist (DOCX/PPTX/XLSX/TXT/MD, via Apache Tika's auto-detecting reader).
 * [Rationale] document-ingestion Spec Tech Review card T6's "Tika 파싱" step, extended
 *   (2026-09-01, user decision "A + C") so citations can carry a real page number. {@link
 *   TikaDocumentReader#get()} always returns exactly one {@link Document} per resource whose
 *   metadata carries only a {@code source} key -- it never exposes Apache Tika's own {@code
 *   Metadata} (e.g. {@code xmpTPg:NPages}) or any page boundary, for any format (verified against
 *   {@code spring-ai-tika-document-reader:2.0.1} sources and empirically against {@code
 *   fixtures/sample-document.pdf}; raw output in the {@code document-ingestion-T6} dev-log entry).
 *   So with Tika alone {@code metadata.page} was {@code null} on every chunk, and a RAG answer
 *   backed by five chunks of one PDF rendered five identical, indistinguishable citations. {@link
 *   PagePdfDocumentReader} with {@code pagesPerDocument = 1} yields one {@link Document} per page
 *   stamped with {@link PagePdfDocumentReader#METADATA_START_PAGE_NUMBER}; {@code
 *   TokenTextSplitter} copies each source document's metadata onto every chunk it produces, so the
 *   page number survives chunking and {@link ChunkMetadataBuilder#resolvePage} can promote it to
 *   AC-8's {@code page} key. Non-PDF formats keep the Tika path and a {@code null} page (AC-8's
 *   "Tika가 페이지 경계를 제공하지 않는 문서 형식이면 값은 null이다" branch, Spec §7 OQ-5 best-effort).
 * [Caution] PDF is detected by <b>content</b> ({@code %PDF-} magic bytes), not by the client's
 *   declared {@code Content-Type}: the upload path accepts that header at face value (Spec AD-5,
 *   NR-5) and the on-disk original has no extension, so the declared type is the one input here
 *   that cannot be trusted to pick a parser. A non-PDF file mislabeled as PDF therefore still goes
 *   to Tika instead of failing inside PDFBox, and a real PDF mislabeled as something else still
 *   gets page numbers. A PDF that PDFBox cannot open (encrypted, corrupt) throws from {@link
 *   PagePdfDocumentReader#get()} like Tika would, landing on the pipeline's existing
 *   {@code PARSE_FAILURE_MESSAGE} path -- no new failure shape.
 *   A page-split source also means chunks never straddle a page boundary, which changes chunk
 *   counts for PDFs versus the old single-document split; {@code chunk_index} stays a plain list
 *   position across the whole file (see {@link ChunkMetadataBuilder}).
 */
@Component
public class DocumentParser {

    /** [Purpose] The 5-byte header every PDF starts with (PDF 32000-1:2008 §7.5.2). */
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    /**
     * [Purpose] One {@link Document} per page, with PDFBox's default text formatting (no margin
     *   trimming, no paragraph reordering -- AD-10's "no extra tuning" rule applied to the reader).
     * [Rationale] {@code PdfDocumentReaderConfig} is immutable, so one instance is shared.
     */
    private static final PdfDocumentReaderConfig PDF_PAGE_CONFIG = PdfDocumentReaderConfig.builder()
            .withPagesPerDocument(1)
            .build();

    /**
     * [Purpose] Extracts the text content of the given resource as Spring AI {@link Document}s --
     *   one per page for a PDF, one for the whole file otherwise.
     * [Rationale] See class Javadoc for why the branch is decided by magic bytes.
     */
    public List<Document> parse(Resource resource) {
        if (isPdf(resource)) {
            // try-with-resources (batch review 2026-09-01 Medium-1): PagePdfDocumentReader parses
            // the whole file into an in-heap PDDocument in its constructor and never closes it --
            // the reader has no close() of its own, so the subclass below adds one. The blank-page
            // filter runs BEFORE normalization (same review, Low-8): a page whose raw text is all
            // whitespace is blank under String#isBlank either way (normalization only removes
            // whitespace), and filtering first means no Document is ever constructed for an empty
            // page -- e.g. a scanned, image-only page -- regardless of how the Document
            // constructor treats empty text.
            try (ClosablePagePdfDocumentReader reader = new ClosablePagePdfDocumentReader(resource)) {
                return reader.get().stream()
                        .filter(page -> page.getText() != null && !page.getText().isBlank())
                        .map(DocumentParser::normalizeWhitespace)
                        .toList();
            }
        }
        return new TikaDocumentReader(resource).get();
    }

    /**
     * [Purpose] {@link PagePdfDocumentReader} that can actually be closed -- the library class
     *   opens a {@code PDDocument} (the whole file, buffered in heap) in its constructor, keeps it
     *   in a {@code protected} field, and neither implements {@link AutoCloseable} nor exposes a
     *   close path (batch review 2026-09-01 Medium-1, verified against
     *   {@code spring-ai-pdf-document-reader-2.0.1} sources).
     * [Rationale] Subclassing is the only route that reaches the {@code protected final document}
     *   field without touching the library. The stream cache is memory-only (pdfbox 3.0.7
     *   {@code COSDocument}), so this closes heap retention, not a file-descriptor leak.
     * [Caution] {@link IOException} from {@code close()} is wrapped unchecked so callers can use
     *   plain try-with-resources; the pipeline's existing {@code RuntimeException} catch turns it
     *   into the usual {@code PARSE_FAILURE_MESSAGE} path.
     */
    private static final class ClosablePagePdfDocumentReader extends PagePdfDocumentReader
            implements AutoCloseable {

        private ClosablePagePdfDocumentReader(Resource resource) {
            super(resource, PDF_PAGE_CONFIG);
        }

        @Override
        public void close() {
            try {
                this.document.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to close PDDocument", e);
            }
        }
    }

    /**
     * [Purpose] Collapses the run-length spacing PDFBox's layout-preserving stripper emits (it pads
     *   words with spaces to keep column alignment, and pads every line to the page width) into
     *   single spaces, trims each line, and drops blank-line runs beyond one.
     * [Rationale] Left as-is, that padding reaches three places that all read the raw text: the
     *   BM25 index (harmless), the embedding input (wasted tokens), and {@code SourceRefMapper}'s
     *   200-character snippet, which would be mostly whitespace. Tika's plain-text output never
     *   had this shape, so the rest of the pipeline was written against normally spaced text.
     *   Newlines are kept so paragraph boundaries still guide {@code TokenTextSplitter}.
     * [Caution] Rebuilds the {@link Document} with the same id and metadata map -- the page-number
     *   keys {@link ChunkMetadataBuilder#resolvePage} depends on must survive this step.
     */
    private static Document normalizeWhitespace(Document page) {
        String text = page.getText() == null ? "" : page.getText();
        String normalized = text.lines()
                .map(line -> line.replaceAll("[ \\t\\x0B\\f]+", " ").strip())
                .collect(java.util.stream.Collectors.joining("\n"))
                .replaceAll("\n{3,}", "\n\n")
                .strip();
        return new Document(page.getId(), normalized, page.getMetadata());
    }

    /**
     * [Purpose] True when the resource's first bytes are the {@code %PDF-} signature.
     * [Caution] An unreadable resource answers {@code false} rather than throwing, so the failure
     *   surfaces from the Tika path with its usual exception instead of from this sniff.
     *   This opens (and closes) the resource's stream once before {@link #parse} reads it again,
     *   so callers must pass a <b>re-readable</b> {@link Resource} (batch review 2026-09-01
     *   Low-8) -- true for both real call sites today ({@code FileSystemResource} from the
     *   pipeline, {@code ClassPathResource} in tests), but a one-shot
     *   {@code InputStreamResource} would hand the PDF reader an exhausted stream.
     */
    private static boolean isPdf(Resource resource) {
        byte[] head = new byte[PDF_MAGIC.length];
        try (InputStream in = resource.getInputStream()) {
            int read = in.readNBytes(head, 0, head.length);
            return read == head.length && Arrays.equals(head, PDF_MAGIC);
        } catch (IOException e) {
            return false;
        }
    }
}
