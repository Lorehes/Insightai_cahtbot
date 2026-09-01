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
 *   Card T3 (chat-prototype-port) ports the prototype's branding block, "최근 대화" label,
 *   empty-state copy, and session footer (`insight-chat 프로토타입.html` P:30-79) on top of
 *   this existing shape (chat-prototype-port:AC-2, AC-3, AC-4, AC-5, AC-6, AC-7 partial).
 * [Rationale] Kept presentational (not self-fetching) so {@link ../chat-workspace/ChatWorkspace}
 *   remains the single source of truth for "which conversations exist" and "which one is
 *   selected" -- both of those also have to reach {@link ../chat-panel/ChatPanel} (the
 *   selected id, to load its history), so one owner avoids the sidebar's own list and the
 *   workspace's selection ever disagreeing.
 * [Caution] Each list item renders two sibling `<button>`s (select, delete), never one button
 *   nested inside another -- nested interactive elements are invalid HTML and would make the
 *   delete action unreachable by keyboard/assistive tech in some browsers. This structure is
 *   load-bearing for chat-prototype-port:AC-28 (`li[data-testid^="conversation-"]` + its first
 *   `button` = select; the `button` whose `aria-label` contains "삭제" = delete) -- do not
 *   change it without checking that AC.
 *   The new-conversation button's accessible name is exactly "새 대화" (chat-prototype-port:
 *   AC-3, no literal "+" in the name) -- but an untitled conversation's own list-item label
 *   also falls back to the same text ("새 대화", pre-existing `chat:AC-38` behavior, unrelated
 *   to this card and left unchanged). Two controls can therefore legitimately share an
 *   accessible name at once; tests that need the *button*, not a *list item*, must scope their
 *   query (a `data-testid` is provided on the button for exactly this reason -- see the test
 *   file).
 * [AC] chat-prototype-port:AC-2, chat-prototype-port:AC-3, chat-prototype-port:AC-4,
 *   chat-prototype-port:AC-5, chat-prototype-port:AC-6, chat-prototype-port:AC-7
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
      <div className={styles.header}>
        <div className={styles.brand}>
          <span className={styles.brandDot} aria-hidden="true" />
          <span className={styles.brandName}>insight</span>
          <span className={styles.brandTagline}>사내 문서 검색</span>
        </div>
        <button
          type="button"
          className={styles.newButton}
          onClick={onCreate}
          disabled={isBusy}
          data-testid="new-conversation-button"
        >
          <svg
            width="15"
            height="15"
            viewBox="0 0 15 15"
            aria-hidden="true"
            className={styles.newButtonIcon}
          >
            <path d="M7.5 2 V13 M2 7.5 H13" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
          <span>새 대화</span>
        </button>
      </div>

      {actionError !== null && (
        <p role="alert" className={styles.error}>
          {actionError}
        </p>
      )}

      <div className={styles.listSection}>
        <div className={styles.listLabel}>최근 대화</div>
        {list.status === "loading" && (
          <p role="status" className={styles.status}>
            대화를 불러오는 중…
          </p>
        )}
        {list.status === "error" && (
          <p role="alert" className={styles.error}>
            대화 목록을 불러오지 못했습니다: {list.message}
          </p>
        )}
        {list.status === "ready" && list.conversations.length === 0 && (
          <p className={styles.empty}>아직 대화가 없습니다. 새 대화를 시작해 보세요.</p>
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
                    {/* Prototype P:55-57 -- the accessible name is the `aria-label` above; the
                        glyph itself is decorative. */}
                    <svg width="13" height="13" viewBox="0 0 13 13" aria-hidden="true">
                      <path
                        d="M2.5 2.5 L10.5 10.5 M10.5 2.5 L2.5 10.5"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="1.5"
                        strokeLinecap="round"
                      />
                    </svg>
                  </button>
                </li>
              );
            })}
          </ul>
        )}
      </div>

      <div className={styles.footer}>
        <div className={styles.footerLine}>
          <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" className={styles.footerIcon}>
            <circle cx="7" cy="5" r="2.6" fill="none" stroke="currentColor" strokeWidth="1.2" />
            <path
              d="M2.4 12.4 c0.6 -2.7 2.4 -4 4.6 -4 s4 1.3 4.6 4"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.2"
              strokeLinecap="round"
            />
          </svg>
          <span>익명 세션 · 로그인 없이 사용 중</span>
        </div>
        <div className={styles.footerSub}>세션 쿠키 기준으로 대화가 보관됩니다.</div>
      </div>
    </aside>
  );
}
