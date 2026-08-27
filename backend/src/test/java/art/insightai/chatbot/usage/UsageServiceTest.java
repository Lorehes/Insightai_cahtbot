package art.insightai.chatbot.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * [Purpose] Verifies {@link UsageService#summarize} combines {@link UsageRepository}'s two
 *   queries into one {@link UsageSummary} without a database ({@link UsageRepository} mocked).
 */
@ExtendWith(MockitoExtension.class)
class UsageServiceTest {

    @Mock
    private UsageRepository usageRepository;

    private UsageService service;

    /**
     * [Purpose] Constructs {@link UsageService} after Mockito has injected {@link
     *   #usageRepository} — a field initializer would run before that injection and capture a
     *   {@code null} repository.
     */
    @BeforeEach
    void setUp() {
        service = new UsageService(usageRepository);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-16] summarize는 totals와 byModel을 하나의 UsageSummary로 합친다")
    void summarizeCombinesTotalsAndByModel() {
        // given
        OffsetDateTime from = OffsetDateTime.parse("2026-08-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-08-31T00:00:00+09:00");
        UsageTotals totals = new UsageTotals(7L, 1000L, 400L, new BigDecimal("1.2300"), 2L);
        List<ModelUsage> byModel = List.of(
                new ModelUsage("upstage/solar-pro4", 5L, 700L, 300L, new BigDecimal("1.0000")),
                new ModelUsage("baai/bge-m3", 2L, 300L, 100L, new BigDecimal("0.2300")));
        when(usageRepository.fetchTotals(from, to)).thenReturn(totals);
        when(usageRepository.fetchByModel(from, to)).thenReturn(byModel);

        // when
        UsageSummary summary = service.summarize(from, to);

        // then
        assertThat(summary.totalRequests()).isEqualTo(7L);
        assertThat(summary.promptTokens()).isEqualTo(1000L);
        assertThat(summary.completionTokens()).isEqualTo(400L);
        assertThat(summary.totalCostUsd()).isEqualByComparingTo("1.2300");
        assertThat(summary.byModel()).isEqualTo(byModel);
    }

    @Test
    @DisplayName("[eval-usage-dashboard:AC-17] summarize는 missingCostCount를 totals에서 그대로 전달한다")
    void summarizePropagatesMissingCostCount() {
        // given: every assistant row in the period is missing cost_usd
        OffsetDateTime from = null;
        OffsetDateTime to = null;
        UsageTotals totals = new UsageTotals(3L, 300L, 90L, BigDecimal.ZERO, 3L);
        when(usageRepository.fetchTotals(from, to)).thenReturn(totals);
        when(usageRepository.fetchByModel(from, to)).thenReturn(List.of());

        // when
        UsageSummary summary = service.summarize(from, to);

        // then: total cost is 0 AND missingCostCount says why -- neither field alone would tell
        //   "genuinely free" apart from "cost unknown" (R12)
        assertThat(summary.totalCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(summary.missingCostCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("summarize는 동일한 from/to를 fetchTotals와 fetchByModel 양쪽에 그대로 전달한다")
    void summarizePassesSamePeriodToBothQueries() {
        // given
        OffsetDateTime from = OffsetDateTime.parse("2026-08-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-08-31T00:00:00+09:00");
        when(usageRepository.fetchTotals(from, to))
                .thenReturn(new UsageTotals(0L, 0L, 0L, BigDecimal.ZERO, 0L));
        when(usageRepository.fetchByModel(from, to)).thenReturn(List.of());

        // when
        service.summarize(from, to);

        // then: same from/to instances reach both queries, so their results describe the same
        //   period and byModel sums stay reconcilable with the top-level totals
        verify(usageRepository).fetchTotals(from, to);
        verify(usageRepository).fetchByModel(from, to);
    }
}
