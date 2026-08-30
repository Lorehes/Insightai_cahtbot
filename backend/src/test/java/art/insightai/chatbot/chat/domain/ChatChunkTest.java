package art.insightai.chatbot.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [Purpose] Verifies {@link ChatChunk#normalizeFinishReason} as a pure function, in isolation
 *   from {@code ChatService}'s two call sites (BL-003, 2026-08-30 user decision — "소문자로
 *   통일한다"). No Spring context, no reactive stream involved.
 * [Rationale] BL-003 did not open a new AC on the closed {@code chat} Spec (routing decision,
 *   2026-08-30: "새 AC 가 아니라 기존 chat:AC-28 의 증거 보강") — every test in this class is
 *   therefore deliberately untagged; the {@code chat:AC-28} tag is reserved for tests that
 *   exercise the real SDK/wire/DB path through {@code ChatService}
 *   ({@code ChatServiceTest}/{@code ConversationControllerIntegrationTest}), not this method's
 *   standalone case coverage.
 */
class ChatChunkTest {

    @Test
    @DisplayName("SDK 가 대문자로 보내는 종료 사유(STOP·LENGTH)를 소문자로 통일한다")
    void normalizesUppercaseSdkValuesToLowercase() {
        assertThat(ChatChunk.normalizeFinishReason("STOP")).isEqualTo("stop");
        assertThat(ChatChunk.normalizeFinishReason("LENGTH")).isEqualTo("length");
        // mixed case defensively -- the SDK is only observed to send all-caps enum names, but
        // toLowerCase(Locale.ROOT) makes no assumption about that being the only shape
        assertThat(ChatChunk.normalizeFinishReason("Stop")).isEqualTo("stop");
    }

    @Test
    @DisplayName("_UNKNOWN 센티널(문자열)을 unknown 상수로 접는다 -- 이 SDK 버전에서는 도달 불가능한 "
            + "방어적 분기다 (2026-08-30 review High-2: 실물 _UNKNOWN 은 SDK 가 null 로 반환한다, "
            + "OpenAiChatModel.java:393-396)")
    void foldsUnknownSentinelToUnknownConstant() {
        // given/when/then: the OpenAI Java SDK's own unmapped-value sentinel, literally "_UNKNOWN"
        assertThat(ChatChunk.normalizeFinishReason("_UNKNOWN")).isEqualTo(ChatChunk.FINISH_REASON_UNKNOWN);
        // lower-casing "_UNKNOWN" alone would leave "_unknown" (leading underscore) -- this is the
        // exact defect the method's own [Caution] describes guarding against
        assertThat(ChatChunk.normalizeFinishReason("_UNKNOWN")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("빈 문자열/공백은 unknown 으로 접지 않고 null 을 반환한다 (2026-08-30 review Low-4: "
            + "blank 는 absence 와 같은 신호이므로, mid-stream 청크가 빈 문자열을 받아도 finishReason 이 "
            + "계속 null 이어야 chat:AC-28 의 '마지막 청크만 finishReason 을 가진다'가 실물에서도 지켜진다 "
            + "-- unknown 으로 접는 판단은 스트림 종료 여부를 아는 onStreamTerminated 에만 맡긴다)")
    void returnsNullForBlankInput() {
        // given/when/then: blank input is treated the same as null -- not a distinct "unknown"
        // signal, only a differently-shaped absence of a value
        assertThat(ChatChunk.normalizeFinishReason("")).isNull();
        assertThat(ChatChunk.normalizeFinishReason("   ")).isNull();
    }

    @Test
    @DisplayName("null 입력은 unknown 으로 접지 않고 null 그대로 반환한다 (mid-stream 청크가 null 을 "
            + "유지해야 chat:AC-28 이 실물에서 깨지지 않는다 -- 이 메서드 자신은 mid-stream 여부를 구분할 "
            + "방법이 없으므로, 그 판단은 onStreamTerminated 에만 맡긴다는 문서화된 계약을 고정한다)")
    void returnsNullUnchangedForNullInput() {
        assertThat(ChatChunk.normalizeFinishReason(null)).isNull();
    }

    @Test
    @DisplayName("이 프로젝트 자체 상수(error/cancelled)는 정규화를 거쳐도 값이 그대로다 (프론트 "
            + "isErrorFinish() 회귀 가드 -- ChatPanel.tsx:137)")
    void passesBackendOwnConstantsThroughNormalizationUnchanged() {
        assertThat(ChatChunk.normalizeFinishReason(ChatChunk.FINISH_REASON_ERROR)).isEqualTo("error");
        assertThat(ChatChunk.normalizeFinishReason(ChatChunk.FINISH_REASON_CANCELLED)).isEqualTo("cancelled");
    }
}
