package art.insightai.chatbot.chat.api;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import org.springframework.http.codec.ServerSentEvent;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    /**
     * 토큰을 raw String 이 아니라 JSON 으로 감싸 흘린다.
     *  - String 으로 보내면 SSE 응답에서 charset 이 떨어져 한글이 깨진다
     *  - SSE 는 "data:" 뒤 첫 공백을 구분자로 소비하므로, raw String 은 단어 사이 공백을 잃는다
     * 두 문제 모두 JSON 직렬화로 해결된다.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatChunk>> stream(@RequestBody ChatRequest request) {
        return chatClient.prompt()
                .user(request.message())
                .stream()
                .content()
                .map(token -> ServerSentEvent.builder(new ChatChunk(token)).build());
    }

    public record ChatRequest(String message) {}

    /** D2 에 conversationId, finishReason, usage 를 여기에 추가한다. */
    public record ChatChunk(String delta) {}
}