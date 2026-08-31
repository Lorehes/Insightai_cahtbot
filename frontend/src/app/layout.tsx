import type { Metadata } from "next";
import "./globals.css";

// F1: 생성기 기본 placeholder 제목/설명을 프로젝트 정보로 교체했다 (생성기 산출물의
// 유일한 수정 지점 -- 나머지 이 파일은 create-next-app 원본 그대로다).
export const metadata: Metadata = {
  title: "insight-chat",
  description: "사내 업무용 챗봇 -- Spring Boot + OpenRouter + 하이브리드 RAG",
};

/**
 * [Purpose] App root layout. Renders `lang="ko"` for the Korean UI and loads the global token
 *   layer (`globals.css`).
 * [Rationale] `next/font` (Geist/Geist_Mono) is fully removed -- the prototype loads no web
 *   font (`--font-sans` is the Pretendard-first system stack), and leaving them in would let
 *   the actual rendered font drift from the stack AC-4 pins (Spec §Tech Review AD-6). The
 *   `className` attribute disappearing along with them is exactly the "font variable wiring"
 *   §2 names as In scope.
 * [AC] design-system:AC-7, design-system:AC-8
 */
export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}
