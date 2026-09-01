import { ChatWorkspace } from "@/components/chat-workspace/ChatWorkspace";
import styles from "./page.module.css";

/**
 * [Purpose] Card T2 (`chat-prototype-port`): screen root that renders only the real chat screen
 *   ({@link ChatWorkspace}, card F3, Spec §5 "화면 (D3)") -- the earlier dev-only top section
 *   (an `insight-chat` `h1` plus the F1 session-bootstrap proof under a `채팅` `h2`) is gone,
 *   and `SessionStatus` is no longer rendered here at all (`chat-prototype-port:AC-1`).
 * [Rationale] `SessionStatus` is intentionally **not deleted** -- its component/CSS/test files
 *   are untouched, only this file's render call is removed. Deleting the file would drop
 *   `SessionStatus.test.tsx`'s test names and flip the already-closed `design-system:AC-18`
 *   (✅) to red; Spec §8 R-1a reserves that file's deletion for a separate, explicit user
 *   decision this card does not make. This file's own tests live in `page.test.tsx` (Spec §8
 *   AD-3 split -- it owns the two h1/h2-absence clauses, while `ChatWorkspace.test.tsx` owns
 *   the third, the `aside`/main sibling-structure clause); all real logic/state lives in
 *   {@link ChatWorkspace} and what it renders, each independently tested.
 * [Caution] `ChatWorkspace`'s own root already renders its `<aside>` (sidebar) and its content
 *   `<div>` as siblings -- this file must not insert anything between them.
 *   `page.module.css`'s `.page` only supplies the outer full-height frame those two sit inside;
 *   making the sidebar/content actually reach the viewport bottom is `ChatWorkspace`'s own CSS,
 *   out of this card's scope (T3+).
 * [AC] chat-prototype-port:AC-1
 */
export default function Home() {
  return (
    <main className={styles.page}>
      <ChatWorkspace />
    </main>
  );
}
