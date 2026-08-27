package art.insightai.chatbot.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Self-verification for {@link SampleDocumentFixtures} -- no Spec AC maps to card T4
 * (Tech Review: "AC 없는 카드는 T1·T3·T4"), so these carry no {@code [document-ingestion:AC-N]}
 * prefix and do not affect the evidence ledger. Confirms every fixture file this class exposes
 * is actually on the classpath, contains its documented keyword, and -- for the PDF -- is a
 * structurally valid file Tika can really parse (not just bytes that happen to exist), so T6
 * can build its own Tika-parsing tests on top of these without re-verifying the fixtures first.
 */
class SampleDocumentFixturesTest {

    @Test
    void textFixtureContainsItsDocumentedKeywordAndSentence() {
        // given / when
        String content = new String(SampleDocumentFixtures.readBytes(SampleDocumentFixtures.TEXT_FILENAME),
                StandardCharsets.UTF_8);

        // then
        assertThat(content).contains(SampleDocumentFixtures.KEYWORD_TEXT);
        assertThat(content).contains(SampleDocumentFixtures.KNOWN_SENTENCE_TEXT);
    }

    @Test
    void markdownFixtureContainsItsDocumentedKeyword() {
        // given / when
        String content = new String(SampleDocumentFixtures.readBytes(SampleDocumentFixtures.MARKDOWN_FILENAME),
                StandardCharsets.UTF_8);

        // then
        assertThat(content).contains(SampleDocumentFixtures.KEYWORD_TEXT);
    }

    @Test
    void pdfFixtureIsParseableByTikaAndReportsTwoPages() throws Exception {
        // given
        byte[] pdfBytes = SampleDocumentFixtures.readBytes(SampleDocumentFixtures.PDF_FILENAME);

        // when: full-text extraction (this is the code path T6 will actually use)
        String extractedText = new Tika().parseToString(new java.io.ByteArrayInputStream(pdfBytes));

        // then
        assertThat(extractedText).contains(SampleDocumentFixtures.KEYWORD_PDF);
        assertThat(extractedText).contains(SampleDocumentFixtures.KNOWN_SENTENCE_PDF_PAGE_2);

        // when: page-count metadata (backs AD-9's "Tika provides a page boundary" branch)
        Metadata metadata = new Metadata();
        new AutoDetectParser().parse(new java.io.ByteArrayInputStream(pdfBytes),
                new BodyContentHandler(-1), metadata, new ParseContext());

        // then
        assertThat(metadata.get("xmpTPg:NPages")).isEqualTo(String.valueOf(SampleDocumentFixtures.PDF_PAGE_COUNT));
    }
}
