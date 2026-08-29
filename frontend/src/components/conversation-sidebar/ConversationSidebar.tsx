"use client";

import type { ConversationSummaryResponse } from "@/lib/api/conversations";
import styles from "./ConversationSidebar.module.css";

/**
 * [Purpose] Shape {@link ../chat-workspace/ChatWorkspace} passes {@link ConversationSidebar}
 *   for `GET /api/conversations`'s three possible outcomes -- mirrors the `Status`/
 *   `ConversationState` discriminated-union pattern already used by
 *   `components/session-status/SessionStatus.tsx` and `components/chat-panel/ChatPanel.tsx`.
 */
export type ConversationListState =
  | { status: "loading" }
  | { status: "ready"; conversations: ConversationSummaryResponse[] }
  | { status: "error"; message: string };

export interface ConversationSidebarProps {
  list: ConversationListState;
  selectedId: string | null;
  onSelect: (id: string) => void;
  onCreate: () => void;
  onDelete: (id: string) => void;
  /** Disables create/select/delete while a prior action is still in flight (card F3: no
   *  queueing -- one action at a time keeps this component's own state simple). */
  isBusy?: boolean;
  /** Latest create/select/delete failure from the parent, surfaced as an inline banner. */
  actionError?: string | null;
}

/**
 * [Purpose] Card F3's conversation list: new/select/delete (chat:AC-38), Spec §5 "화면 (D3)"
 *   element ①. Purely presentational -- every action is delegated to the caller via a
 *   callback prop; this component never imports `lib/api/conversations.ts` itself.
 * [Rationale] Kept presentational (not self-fetching) so {@link ../chat-workspace/ChatWorkspace}
 *   remains the single source of truth for "which conversations exist" and "which one is
 *   selected" -- both of those also have to reach {@link ../chat-panel/ChatPanel} (the
 *   selected id, to load its history), so one owner avoids the sidebar's own list and the
 *   workspace's selection ever disagreeing.
 * [Caution] Each list item renders two sibling `<button>`s (select, delete), never one button
 *   nested inside another -- nested interactive elements are invalid HTML and would make the
 *   delete action unreachable by keyboard/assistive tech in some browsers.
 * [AC] chat:AC-38
 */
export function ConversationSidebar({
  list,
  selectedId,
  onSelect,
  onCreate,
  onDelete,
  isBusy = false,
  actionError = null,
}: ConversationSidebarProps) {
  const handleDeleteClick = (id: string, label: string) => {
    // Native confirm(), not a custom modal -- introduces no new visual design surface
    // (colors/spacing/a dialog component) for something the card brief left undecided, while
    // still guarding an otherwise-irreversible-from-this-UI action (soft delete has no
    // undo/trash screen in this Spec).
    if (window.confirm(`"${label}" 대화를 삭제할까요?`)) {
      onDelete(id);
    }
  };

  return (
    <aside className={styles.sidebar} aria-label="대화 목록">
      <button type="button" className={styles.newButton} onClick={onCreate} disabled={isBusy}>
        + 새 대화
      </button>
      {actionError !== null && (
        <p role="alert" className={styles.error}>
          {actionError}
        </p>
      )}
      {list.status === "loading" && <p role="status">대화 목록을 불러오는 중...</p>}
      {list.status === "error" && (
        <p role="alert" className={styles.error}>
          대화 목록을 불러오지 못했습니다: {list.message}
        </p>
      )}
      {list.status === "ready" && list.conversations.length === 0 && (
        <p className={styles.empty}>대화가 없습니다. 새 대화를 시작해보세요.</p>
      )}
      {list.status === "ready" && list.conversations.length > 0 && (
        <ul className={styles.list}>
          {list.conversations.map((conversation) => {
            const isSelected = conversation.id === selectedId;
            // title is null until a conversation's first message is sent
            // (ConversationController#create [Caution]) -- a fresh conversation therefore
            // always needs this fallback label.
            const label = conversation.title ?? "새 대화";
            return (
              <li key={conversation.id} className={styles.item} data-testid={`conversation-${conversation.id}`}>
                <button
                  type="button"
                  className={isSelected ? styles.selectButtonActive : styles.selectButton}
                  onClick={() => onSelect(conversation.id)}
                  disabled={isBusy}
                  aria-current={isSelected ? "true" : undefined}
                >
                  {label}
                </button>
                <button
                  type="button"
                  className={styles.deleteButton}
                  onClick={() => handleDeleteClick(conversation.id, label)}
                  disabled={isBusy}
                  aria-label={`${label} 삭제`}
                >
                  삭제
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </aside>
  );
}
