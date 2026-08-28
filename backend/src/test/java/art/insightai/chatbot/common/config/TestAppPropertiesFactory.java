package art.insightai.chatbot.common.config;

import java.time.Duration;
import java.util.List;

import org.springframework.util.unit.DataSize;

/**
 * [Purpose] Shared fixture factory for a valid {@link AppProperties} (or the {@code app.models.*}
 * property-string slice of one), for any test outside {@code common/config} that needs the
 * six-record aggregate to bind/construct successfully but is not itself testing
 * Search/Rerank/Ingest/Session/Models.
 * [Rationale] chat Spec T5 added {@code AppProperties.Models} as a sixth record component with no
 * {@code @DefaultValue} (chat Spec §5 A-10 requires the catalog itself, not just a shape, to come
 * from application.yaml). That makes every test that resolves {@code AppProperties} -- whether
 * through {@code ApplicationContextRunner} property strings or a direct {@code new
 * AppProperties(...)} call -- responsible for supplying a valid models catalog, even when the
 * class under test never reads {@code AppProperties.models()}. Six test files each hand-copied the
 * identical 3-model catalog to satisfy this (T5 review 2026-08-27, Medium-4) -- this is the second
 * time the same shape of duplication has happened (document-ingestion T1's {@code uploadDir}
 * required the same kind of baseline first). Centralizing here means the next {@code @DefaultValue}-less
 * record added to {@link AppProperties} only needs a new {@link Builder} setter, not N hand edits
 * across unrelated test files.
 * [Caution] {@link #VALID_MODELS_CATALOG_PROPERTIES} (property-key strings, for {@code
 * ApplicationContextRunner}) and {@link #validModels()} (typed record, for a direct {@code new
 * AppProperties(...)} call) describe the same three models and must be kept in sync by hand -- one
 * is Spring property keys, the other is a constructed record, so there is no single value both
 * forms could share. {@link Builder}'s defaults intentionally mirror what {@code
 * AnonymousUserServiceTest}/{@code ReciprocalRankFusionTest}/{@code OpenRouterRerankAdapterTest}
 * already used before this factory existed (HYBRID search mode, rerank disabled, {@code
 * java.io.tmpdir} upload dir, 1000 anonymous-user ceiling) so that {@link #valid()} is a drop-in
 * replacement for those files' old hand-built baseline, not a behavior change.
 */
public final class TestAppPropertiesFactory {

    private TestAppPropertiesFactory() {
    }

    /**
     * [Purpose] The {@code app.models.*} property-key form of a minimal, entirely valid
     * three-entry catalog, for {@code ApplicationContextRunner}-based tests (e.g. {@code
     * AppPropertiesSearchBindingTest}, {@code AppPropertiesRerankBindingTest}, {@code
     * AppPropertiesModelsBindingTest}'s own baseline) that bind {@link AppProperties} through
     * real Spring property resolution rather than constructing the record directly.
     */
    public static final String[] VALID_MODELS_CATALOG_PROPERTIES = {
            "app.models.default-id=upstage/solar-pro4",
            "app.models.items[0].id=upstage/solar-pro4",
            "app.models.items[0].display-name=Solar Pro 4",
            "app.models.items[1].id=openai/gpt-5-mini",
            "app.models.items[1].display-name=GPT-5 mini",
            "app.models.items[2].id=anthropic/claude-haiku-4.5",
            "app.models.items[2].display-name=Claude Haiku 4.5",
    };

    /**
     * [Purpose] The typed-record form of the same three-entry catalog as {@link
     * #VALID_MODELS_CATALOG_PROPERTIES}, for tests that build {@link AppProperties} directly
     * (e.g. {@code ReciprocalRankFusionTest}, {@code OpenRouterRerankAdapterTest}, {@code
     * AnonymousUserServiceTest}) instead of going through property binding.
     */
    public static AppProperties.Models validModels() {
        return new AppProperties.Models("upstage/solar-pro4", List.of(
                new AppProperties.Models.ModelEntry("upstage/solar-pro4", "Solar Pro 4"),
                new AppProperties.Models.ModelEntry("openai/gpt-5-mini", "GPT-5 mini"),
                new AppProperties.Models.ModelEntry("anthropic/claude-haiku-4.5", "Claude Haiku 4.5")));
    }

    /**
     * [Purpose] A ready-to-use valid {@link AppProperties}, equivalent to {@code builder().build()}.
     */
    public static AppProperties valid() {
        return builder().build();
    }

    /**
     * [Purpose] Starts a {@link Builder} pre-populated with valid defaults for every component,
     * so a caller only overrides the one or two components its test actually exercises.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * [Purpose] Builds a full six-component {@link AppProperties} from valid defaults, letting a
     * caller override individual components (e.g. {@code .search(...)} for {@code
     * ReciprocalRankFusionTest}'s per-call RRF parameters, {@code .rerank(...)} for {@code
     * OpenRouterRerankAdapterTest}'s enabled/disabled cases, {@code .session(...)} for {@code
     * AnonymousUserServiceTest}'s low-ceiling quota case) without hand-repeating the other five.
     */
    public static final class Builder {

        private AppProperties.Auth auth = new AppProperties.Auth(AppProperties.Auth.Mode.NONE);
        private AppProperties.Search search =
                new AppProperties.Search(AppProperties.Search.Mode.HYBRID, 5, 60, 0.5, 0.5, 20, 20, 0.0);
        private AppProperties.Rerank rerank =
                new AppProperties.Rerank(false, "cohere/rerank-v3.5", Duration.ofSeconds(5), Duration.ofSeconds(10));
        private AppProperties.Ingest ingest = new AppProperties.Ingest(
                System.getProperty("java.io.tmpdir"), DataSize.ofMegabytes(20),
                List.of("text/plain"), 2000, 500, 100);
        private AppProperties.Session session = new AppProperties.Session(1000, 50, 200);
        private AppProperties.Models models = validModels();

        private Builder() {
        }

        public Builder auth(AppProperties.Auth auth) {
            this.auth = auth;
            return this;
        }

        public Builder search(AppProperties.Search search) {
            this.search = search;
            return this;
        }

        public Builder rerank(AppProperties.Rerank rerank) {
            this.rerank = rerank;
            return this;
        }

        public Builder ingest(AppProperties.Ingest ingest) {
            this.ingest = ingest;
            return this;
        }

        public Builder session(AppProperties.Session session) {
            this.session = session;
            return this;
        }

        public Builder models(AppProperties.Models models) {
            this.models = models;
            return this;
        }

        public AppProperties build() {
            return new AppProperties(auth, search, rerank, ingest, session, models);
        }
    }
}
