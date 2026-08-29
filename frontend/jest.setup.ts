// RTL 커스텀 matcher(toBeInTheDocument 등)를 전역 expect 에 등록한다.
import "@testing-library/jest-dom";

// jest-environment-jsdom 은 TextEncoder/TextDecoder 를 구현하지 않는다(실제 브라우저·순수
// Node 와 다름, 직접 실측 확인 -- typeof TextDecoder === "undefined"). 카드 F2 의 SSE
// 파서(src/lib/api/messages.ts)가 ReadableStream 청크 경계에서 잘린 다국어(한국어 등)
// 멀티바이트 문자를 올바르게 복원하려면 진짜 TextDecoder 가 필요하다(프로젝트 CLAUDE.md
// 사실 #6). Node `util` 구현은 다른 모든 실행 환경이 실제로 쓰는 것과 동일해 대역이 아니다.
import { TextDecoder, TextEncoder } from "node:util";

if (typeof globalThis.TextDecoder === "undefined") {
  globalThis.TextDecoder = TextDecoder as unknown as typeof globalThis.TextDecoder;
}
if (typeof globalThis.TextEncoder === "undefined") {
  globalThis.TextEncoder = TextEncoder as unknown as typeof globalThis.TextEncoder;
}
