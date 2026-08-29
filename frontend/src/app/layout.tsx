import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

// F1: 생성기 기본 placeholder 제목/설명을 프로젝트 정보로 교체했다 (생성기 산출물의
// 유일한 수정 지점 -- 나머지 이 파일은 create-next-app 원본 그대로다).
export const metadata: Metadata = {
  title: "insight-chat",
  description: "사내 업무용 챗봇 -- Spring Boot + OpenRouter + 하이브리드 RAG",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" className={`${geistSans.variable} ${geistMono.variable}`}>
      <body>{children}</body>
    </html>
  );
}
