package art.insightai.chatbot.usage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Purpose] {@code GET /api/admin/usage} — returns request/token/cost totals plus the per-model
 *   breakdown for an optional {@code [from, to)} period (eval-usage-dashboard Spec, card U2).
 * [Rationale] Maps {@link UsageSummary}/{@link ModelUsage} — service-layer types assembled by
 *   {@link UsageService#summarize} — to the dedicated {@link UsageResponse}/{@link
 *   ModelUsageResponse} records below before returning them, the same shape every other
 *   controller in this codebase uses ({@code ConversationController}'s four response records,
 *   {@code ModelsController.ModelResponse}, {@code SessionController.SessionResponse}: none of
 *   them return a domain/service type directly). This keeps the HTTP contract decoupled from
 *   {@link UsageSummary}'s field set — a future field {@link UsageService#summarize} adds for
 *   internal diagnostics would otherwise be auto-exposed by this unauthenticated endpoint with no
 *   controller-code change to review (U2 review round 1, maintainability Medium-3). Field names
 *   are unchanged from {@link UsageSummary}/{@link ModelUsage} — {@code totalRequests}, {@code
 *   promptTokens}, {@code completionTokens}, {@code totalCostUsd}, {@code missingCostCount},
 *   {@code byModel[]} with {@code model}/{@code requests}/{@code promptTokens}/{@code
 *   completionTokens}/{@code costUsd} — because eval-usage-dashboard:AC-13/AC-16/AC-17 fix that
 *   exact shape as the API contract. {@code from}/{@code to} are bound as {@link OffsetDateTime}
 *   with {@code @DateTimeFormat(iso = ISO.DATE_TIME)} (Tech Review D-8: "타입: OffsetDateTime.
 *   오프셋 없는 입력은 거부") — that annotation selects {@code DateTimeFormatter.ISO_DATE_TIME}, which
 *   parses a date-time with or without an offset, but {@link OffsetDateTime#parse} then requires
 *   {@code OFFSET_SECONDS} to resolve the result; an offset-less value (e.g. {@code
 *   2026-08-28T00:00:00}, no {@code +09:00}/{@code Z}) fails that resolution and Spring surfaces
 *   it as a {@code MethodArgumentTypeMismatchException}, which Spring MVC's built-in {@code
 *   DefaultHandlerExceptionResolver} already answers with 400 — independent of this project's
 *   {@code GlobalExceptionHandler} and independent of Spring Boot's {@code
 *   spring.mvc.problemdetails.enabled} switch (unset in {@code application.yaml}, so that switch
 *   plays no part here). This project's own uniform {@code ProblemDetail} body shape is therefore
 *   not guaranteed for a malformed {@code from}/{@code to} value the way it is for {@link
 *   InvalidUsagePeriodException} below — only the reject-with-400 requirement itself is
 *   guaranteed. The {@code from > to} check, by contrast, cannot be expressed as a binding
 *   failure (both values parse successfully on their own), so Tech Review D-8 assigns it to this
 *   controller explicitly ("검증 위치: from > to → 400은 Controller"): see {@link
 *   InvalidUsagePeriodException}, mapped to 400 by {@code GlobalExceptionHandler}.
 * [Caution] No authentication guards this endpoint despite the {@code /api/admin/**} path — Spec
 *   §2 "Out of scope" defers auth/per-user quota to Phase 2. Any caller holding network access to
 *   this service can read aggregate request/token/cost figures across every user. Currently kept
 *   at Medium, not High, on two grounds (U2 review round 1, security Medium-2): (a) {@code
 *   server.address: 127.0.0.1} (chat T4 review) keeps this service off any network but the host
 *   machine's own loopback, and (b) a cross-origin browser context cannot read this response even
 *   if it can trigger the request. Ground (a) is tied to the roadmap: revisit at internal LAN
 *   release (chat Spec §8 R-6 roadmap stage ①, {@code state.md} "2단계(사내망 공개 전환 시)") — not
 *   "beyond" that stage, as an earlier version of this note said — because that stage is exactly
 *   when {@code server.address} leaves loopback. That stage's required-item list ({@code
 *   state.md} "사용자 확정 결정 2건 — eval-usage-dashboard U2 리뷰에서 (2026-08-28)") now names
 *   {@code /api/admin/**} authentication (item 4) and a batched rate-limit review covering this
 *   endpoint alongside {@code sendMessage}/{@code delete}/{@code updateRagEnabled} (item 5) as
 *   required additions — registered, not yet implemented; closing either gap is a separate future
 *   card, not this one.
 *   Ground (b), unlike (a), is not tied to that roadmap milestone — it depends on the current CORS
 *   configuration, not on a future date. It holds today: a project-wide grep for {@code
 *   @CrossOrigin}/{@code addCorsMappings}/{@code allowedOrigins}/{@code CorsConfiguration} finds
 *   zero actual CORS configuration anywhere in this project (only Javadoc mentions of those terms
 *   exist, this paragraph among them). It also still holds after the frontend integration that
 *   was originally the open question here (chat F1, confirmed 2026-08-28 by {@code
 *   insight-chat-c0}): the frontend's {@code next.config.ts} proxies {@code /api/:path*} to this
 *   backend through a Next.js {@code rewrites} server-side rewrite rather than a direct
 *   cross-origin browser call (chat Spec §7 OQ-11, chat:AC-40), and its {@code client.ts} fetch helper
 *   sends {@code credentials: "same-origin"}, not {@code "include"} — so the browser only ever
 *   talks to its own origin ({@code localhost:3000}) and no cross-origin request to this endpoint
 *   occurs at all. F2/F3 are expected to reuse this same {@code apiFetch} helper, but that is
 *   unconfirmed — F2/F3 have no code yet, and {@code apiFetch} returns parsed JSON ({@code
 *   return (await response.json()) as T}), a shape an SSE streaming screen may not be able to
 *   reuse; if a streaming screen bypasses it, re-check ground (b) then. Re-judge this endpoint's
 *   severity — not on a schedule, but whenever either of these two facts stops being true: someone
 *   adds real CORS configuration ({@code
 *   @CrossOrigin}/{@code addCorsMappings}/{@code allowedOrigins}) anywhere in this project, or the
 *   frontend switches from the {@code rewrites} proxy to a direct cross-origin call with {@code
 *   credentials: "include"}.
 * [AC] eval-usage-dashboard:AC-13, eval-usage-dashboard:AC-15
 */
@RestController
@RequestMapping("/api/admin/usage")
public class UsageController {

    private final UsageService usageService;

    public UsageController(UsageService usageService) {
        this.usageService = usageService;
    }

    /**
     * [Purpose] Handles {@code GET /api/admin/usage?from=&to=}. Either bound may be omitted to
     *   leave that side of the period open (Tech Review D-8 default: both omitted means the
     *   entire history).
     * [AC] eval-usage-dashboard:AC-13, eval-usage-dashboard:AC-15
     */
    @GetMapping
    public UsageResponse usage(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new InvalidUsagePeriodException(from, to);
        }
        return UsageResponse.from(usageService.summarize(from, to));
    }

    /**
     * [Purpose] Response body for {@code GET /api/admin/usage} (eval-usage-dashboard Spec §4/§5,
     *   AC-13, AC-16, AC-17).
     * [Rationale] Field names/types are copied verbatim from {@link UsageSummary} — see this
     *   class's own [Rationale] for why the wrapping exists despite the identical shape.
     */
    public record UsageResponse(
            long totalRequests,
            long promptTokens,
            long completionTokens,
            BigDecimal totalCostUsd,
            long missingCostCount,
            List<ModelUsageResponse> byModel) {
        static UsageResponse from(UsageSummary summary) {
            return new UsageResponse(
                    summary.totalRequests(),
                    summary.promptTokens(),
                    summary.completionTokens(),
                    summary.totalCostUsd(),
                    summary.missingCostCount(),
                    summary.byModel().stream().map(ModelUsageResponse::from).toList());
        }
    }

    /**
     * [Purpose] One entry of {@code UsageResponse.byModel} (eval-usage-dashboard:AC-16).
     * [Rationale] Field names/types are copied verbatim from {@link ModelUsage} — see {@link
     *   UsageResponse}'s own [Rationale].
     */
    public record ModelUsageResponse(
            String model,
            long requests,
            long promptTokens,
            long completionTokens,
            BigDecimal costUsd) {
        static ModelUsageResponse from(ModelUsage usage) {
            return new ModelUsageResponse(
                    usage.model(),
                    usage.requests(),
                    usage.promptTokens(),
                    usage.completionTokens(),
                    usage.costUsd());
        }
    }
}
