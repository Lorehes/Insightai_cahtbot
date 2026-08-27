package art.insightai.chatbot.chat.api;

import art.insightai.chatbot.common.config.AppProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * [Purpose] Exposes the fixed chat model catalog via {@code GET /api/models} (chat Spec §5 A-11).
 * [Rationale] The catalog itself lives in {@link AppProperties.Models}, sourced from the
 *   hardcoded {@code app.models} block in application.yaml (chat Spec §5 A-10) rather than a
 *   runtime call to OpenRouter's {@code GET /v1/models} — see that record's Javadoc for why.
 *   This controller only maps the already-validated catalog to the response shape fixed by the
 *   Spec; {@link AppProperties.Models}'s compact constructor is what enforces chat:AC-19
 *   (3-5 entries), chat:AC-20 (no {@code :free} ids) and chat:AC-21 (default id resolves) at
 *   startup, so there is nothing left to validate here.
 * [Caution] {@code isDefault} is serialized as {@code "default"} via {@code @JsonProperty}
 *   because {@code default} is a reserved word and cannot be a Java record component name.
 * [AC] chat:AC-19, chat:AC-20, chat:AC-21
 */
@RestController
@RequestMapping("/api/models")
public class ModelsController {

    private final AppProperties.Models models;

    public ModelsController(AppProperties appProperties) {
        this.models = appProperties.models();
    }

    @GetMapping
    public List<ModelResponse> listModels() {
        return models.items().stream()
                .map(item -> new ModelResponse(
                        item.id(),
                        item.displayName(),
                        item.id().equals(models.defaultId())))
                .toList();
    }

    /** [Purpose] Response body for one entry of {@code GET /api/models} (chat Spec §5 A-11). */
    public record ModelResponse(
            String id,
            String displayName,
            @JsonProperty("default") boolean isDefault
    ) {}
}
