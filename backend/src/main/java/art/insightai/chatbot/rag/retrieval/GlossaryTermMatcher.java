package art.insightai.chatbot.rag.retrieval;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import art.insightai.chatbot.common.config.AppProperties;

/**
 * [Purpose] Finds which configured glossary terms ({@code app.glossary.terms}) appear verbatim
 *   in a search query, so {@link ParadeDbBm25SearchAdapter} can require them as phrases.
 * [Rationale] This is the "term list + phrase AND" workaround for the missing user dictionary in
 *   pg_search 0.25.4 (see {@link AppProperties.Glossary}'s [Rationale] and
 *   {@code DOC_ROOT/docs/eval/paradedb-user-dictionary-probe-2026-09-03.md} §3/§5 option A).
 *   Matching is deliberately dumb -- case-insensitive substring after NFC normalization -- because
 *   the engine's own tokenizer, not this class, decides what a phrase means: the adapter passes the
 *   configured term text to ParadeDB, whose {@code ###} operator re-tokenizes it with the same
 *   {@code pdb.lindera(korean)} the index used, so {@code 인사이트에이아이} becomes the token
 *   sequence {@code 인사이트 / 에 / 이아이} on both sides and matches inside
 *   {@code 인사이트에이아이는} (the particle {@code 는} is simply a following token). A substring
 *   check on the raw query therefore agrees with what the phrase operator will match. NFC is
 *   applied because a query pasted from macOS (NFD-decomposed Hangul) would otherwise miss an
 *   NFC term while still reading identically on screen (playbook §1-2). Lowercasing uses
 *   {@link Locale#ROOT} so a Turkish-locale JVM cannot fold {@code I} differently from the
 *   tokenizer, which also lowercases non-CJK text.
 *   <p>
 *   The matcher lives in {@code rag/retrieval} rather than {@code common/config} because it is a
 *   retrieval concern that happens to read configuration, matching how {@link
 *   NonIndexedDocumentLookup} sits next to the adapter that consumes it. It is a separate class
 *   rather than a private method on the adapter so the ParadeDB-specific SQL and the
 *   engine-agnostic "which terms are in this query" question stay independently readable and
 *   independently testable.
 * [Caution] Returns terms in configured order, deduplicated by {@link AppProperties.Glossary}'s
 *   own compact constructor. A term that is a substring of another configured term (e.g.
 *   {@code AI} and {@code InsightAI}) is matched independently -- both are returned and both
 *   become phrase conditions; because the adapter ORs the phrases, the shorter term only widens
 *   the match, never narrows it. Keep very short or very generic terms out of the glossary for
 *   that reason: they make the phrase filter a no-op for every query that mentions them.
 * [AC] hybrid-rag:AC-13
 */
@Component
public class GlossaryTermMatcher {

    private final List<String> terms;
    private final List<String> foldedTerms;

    /**
     * [Caution] {@code @Autowired} is required here, not decorative: with two constructors and no
     *   marker Spring falls back to a no-arg constructor, which this class does not have, and the
     *   whole context fails to start ("No default constructor found" -- observed 2026-09-03).
     */
    @Autowired
    public GlossaryTermMatcher(AppProperties appProperties) {
        this(appProperties.glossary().terms());
    }

    /**
     * [Purpose] Direct-list constructor for tests that build the adapter by hand (e.g. {@code
     *   ParadeDbBm25SearchAdapterIntegrationTest}) without a full {@link AppProperties}.
     */
    GlossaryTermMatcher(List<String> terms) {
        this.terms = List.copyOf(terms);
        this.foldedTerms = this.terms.stream().map(GlossaryTermMatcher::fold).toList();
    }

    /**
     * [Purpose] Returns the configured terms (original spelling, configured order) that occur in
     *   {@code query}; empty when nothing matches or no terms are configured.
     */
    public List<String> termsIn(String query) {
        if (terms.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }
        String foldedQuery = fold(query);
        return java.util.stream.IntStream.range(0, terms.size())
                .filter(i -> foldedQuery.contains(foldedTerms.get(i)))
                .mapToObj(terms::get)
                .toList();
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
