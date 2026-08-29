import { SessionStatus } from "@/components/session-status/SessionStatus";
import { ChatWorkspace } from "@/components/chat-workspace/ChatWorkspace";
import styles from "./page.module.css";

/**
 * [Purpose] Card F1's session-wiring proof (chat:AC-40) plus the full chat screen Spec §5
 *   "화면 (D3)" describes: sidebar (create/select/delete, chat:AC-38), model picker
 *   (chat:AC-39), and card F2's stream/markdown transcript (chat:AC-36, chat:AC-37) -- all
 *   composed by {@link ChatWorkspace} (card F3).
 * [Caution] The F1 session-proof section above the chat screen is kept deliberately -- it is
 *   still the only place `chatbot_sid` cookie persistence is demonstrated in the UI, and no
 *   card has asked for its removal. `page.tsx` itself stays a thin composition root with no
 *   test of its own (matching F1/F2's existing pattern) -- all real logic/state lives in
 *   {@link ChatWorkspace} and what it renders, each independently tested.
 * [AC] chat:AC-36, chat:AC-37, chat:AC-38, chat:AC-39, chat:AC-40
 */
export default function Home() {
  return (
    <main className={styles.page}>
      <h1>insight-chat</h1>
      <section>
        <h2>세션 부트스트랩 확인 (F1)</h2>
        <SessionStatus />
      </section>
      <section>
        <h2>채팅</h2>
        <ChatWorkspace />
      </section>
    </main>
  );
}
