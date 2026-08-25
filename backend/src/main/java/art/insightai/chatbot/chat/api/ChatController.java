package art.insightai.chatbot.chat.api;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * [Purpose] Streams LLM answers to the client over Server-Sent Events.
 * [Rationale] Chunks are emitted as JSON objects rather than raw strings for two reasons
 *   verified during D1: (1) SSE consumes the single space after "data:" as a delimiter,
 *   so a raw token " Friday" arrives as "Friday" and inter-word spacing is lost;
 *   (2) Spring MVC overwrites the SSE Content-Type and drops any charset parameter
 *   declared via @PostMapping(produces = ...), which corrupts non-ASCII text.
 *   JSON serialization sidesteps both. See docs/chatbot-plan-v0.4.md section 6.1.
 * [Caution] Response charset is forced by spring.servlet.encoding.force-response=true.
 *   Removing that property brings the mojibake back even with JSON chunks.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * [Purpose] D1 smoke endpoint proving the OpenRouter streaming path works end to end.
     * [Caution] Stateless on purpose. Conversation persistence, ChatMemory and usage
     *   accounting land in D2; do not build client features on this shape yet.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ChatChunk> stream(@RequestBody ChatRequest request) {
        return chatClient.prompt()
                .user(request.message())
                .stream()
                .content()
                .map(ChatChunk::new);
    }

    public record ChatRequest(String message) {}

    /** [Purpose] One streamed token. D2 adds conversationId, finishReason and usage. */
    public record ChatChunk(String delta) {}
}
