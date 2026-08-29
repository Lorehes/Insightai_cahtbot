"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@/lib/api/client";
import {
  createConversation,
  deleteConversation,
  listConversations,
  type ConversationResponse,
  type ConversationSummaryResponse,
} from "@/lib/api/conversations";
import { getMessages, type MessageResponse } from "@/lib/api/messages";
import { ChatPanel } from "@/components/chat-panel/ChatPanel";
import type { ChatMessage } from "@/components/chat-panel/MessageList";
import { ConversationSidebar, type ConversationListState } from "@/components/conversation-sidebar/ConversationSidebar";
import { ModelSelector } from "@/components/model-selector/ModelSelector";
import styles from "./ChatWorkspace.module.css";

/** `ChatPanel`'s `key` when nothing is selected yet -- distinct from any real conversation id
 *  (a UUID), so it can never collide with one. */
const NEW_CONVERSATION_KEY = "__new__";

/**
 * [Purpose] Card F3's screen composition root: wires the conversation sidebar
 *   (create/select/delete, chat:AC-38) and the model picker (chat:AC-39) around card F2's
 *   {@link ChatPanel}, so the four pieces Spec §5 "화면 (D3)" lists (sidebar, message area,
 *   input, model picker) render as one screen.
 * [Rationale] Owns the state {@link ChatPanel} explicitly does not (per its own [Rationale]):
 *   which conversation is selected, the conversation list itself, a selected conversation's
 *   loaded history, and the picked model id. Renders `<ChatPanel key={selectedId ?? "__new__"}>`
 *   -- switching keys forces React to unmount the previous instance and mount a fresh one,
 *   which is what actually resets `ChatPanel`'s internal state (messages/draft/in-flight
 *   stream) on a conversation switch; `ChatPanel` itself deliberately never re-reads a changed
 *   `conversationId` prop on an existing instance (see its own [Caution]), so a `key` change
 *   is the only correct way to swap it. This also means an in-flight stream on the
 *   conversation being switched away from is aborted for free by `ChatPanel`'s own unmount
 *   cleanup -- no extra guard needed here. `getMessages` (chat:AC-38's history load) and the
 *   `MessageResponse` -> `ChatMessage` mapping (`toChatMessages` below) both live here, not in
 *   `ChatPanel` or `ConversationSidebar`, because this is the one place that already needs to
 *   know both "which conversation was just selected" and "what `ChatPanel` expects as input".
 * [Caution] Current design (consolidated 2026-08-29 rework round 2, Medium-3 -- full history
 *   of how this design was reached, including the two intermediate shapes it replaced, is in
 *   `frontend.jsonl` entries 5 and 7, not duplicated here):
 *
 *   If the conversation list is non-empty, the mount effect below adopts the most-recently-
 *   updated conversation (`conversations[0]`, per `listConversations`'s own `updatedAt DESC`
 *   ordering guarantee) instead of leaving the screen unselected. `<ChatPanel>` is not mounted
 *   at all until that adoption decision is *fully* resolved (`initialSelectionResolved`) --
 *   mounting it earlier with `conversationId={undefined}` would make it auto-create a new
 *   conversation via `POST /api/conversations` before the adopted one is known, orphaning that
 *   auto-created conversation the instant the adopted one takes over. A brand-new conversation
 *   is still created, correctly, only when there is truly nothing to adopt (empty list on
 *   first-ever visit, deleting the last remaining conversation, or the adopted candidate's
 *   history failing to load, e.g. deleted from another tab meanwhile) -- in all three cases
 *   `selectedId` is deliberately left `null` so `ChatPanel`'s own auto-create (its original,
 *   unchanged behavior; this component only learns the new id back via
 *   `onConversationCreated`) is the *only* create call, never a second one racing it.
 *   `handleDelete` mirrors the same "adopt most-recent remaining, else leave unselected" shape
 *   when the deleted conversation was the open one. The brief gap while the mount decision is
 *   pending renders a small status placeholder instead of an empty pane, suppressed when
 *   `isLoadingHistory`'s own status text is already visible so only one live region announces
 *   the wait at a time.
 *
 *   Why not "always start a new conversation on load" (card F2's original, simpler behavior):
 *   it has no support one way or the other in chat:AC-38/39, but interacts badly with the
 *   backend's `max-conversations-per-user: 50` cap and the sidebar list -- every page load and
 *   every delete-of-the-open-conversation would add one more empty, identically `새 대화`-
 *   labeled row, exhausting the cap with zero real conversations and, well before that, filling
 *   the sidebar with indistinguishable rows that defeat chat:AC-38's "select a different
 *   conversation from the list" affordance.
 * [AC] chat:AC-38, chat:AC-39
 */
export function ChatWorkspace() {
  const [list, setList] = useState<ConversationListState>({ status: "loading" });
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [initialMessages, setInitialMessages] = useState<ChatMessage[]>([]);
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [modelId, setModelId] = useState<string | null>(null);
  // Gates `<ChatPanel>`'s very first mount on the mount effect below having fully decided
  // whether to adopt an existing conversation or deliberately leave `selectedId` `null` --
  // see this component's [Caution] for why rendering `<ChatPanel>` before that decision is
  // made is the Medium-3 bug, not a fix for it. Set exactly once, in the mount effect, and
  // never reset -- it has nothing to do with `isLoadingHistory`, which continues to toggle
  // on every later select/delete.
  const [initialSelectionResolved, setInitialSelectionResolved] = useState(false);

  const refreshList = useCallback(async () => {
    try {
      const conversations = await listConversations();
      setList({ status: "ready", conversations });
    } catch (err: unknown) {
      setList({ status: "error", message: describeError(err) });
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    // Mirrors ChatPanel/SessionStatus's own "read once on mount" pattern (an inline async
    // function, invoked immediately) instead of calling the `refreshList` callback above --
    // calling `refreshList` itself here trips `react-hooks/set-state-in-effect` (it statically
    // resolves to a function that calls `setList`), even though the actual update happens
    // after an `await`, not synchronously during the effect. `refreshList` stays a separate
    // `useCallback` because it is also reused from event handlers below
    // (handleCreate/handleConversationCreated/handleDelete), where this lint rule does not
    // apply -- only the mount-time fetch needed inlining. `handleDelete` below needs the same
    // "adopt the most-recent remaining conversation" shape as the second half of this function
    // and duplicates it inline rather than sharing a `useCallback` with this effect, for the
    // same lint reason. No `exhaustive-deps` suppression needed here (unlike ChatPanel/
    // ModelSelector's mount effects) -- every identifier this effect body references
    // (`listConversations`, `getMessages`, `toChatMessages`, `describeActionError`,
    // `describeError`, and the setState setters) is module-scoped or a setState setter,
    // neither of which that rule requires in `[]`.
    //
    // [Caution] Uses `await`/`try`/`catch` throughout rather than raw `.then()`/`.catch()`
    // chaining -- `getMessages` is mocked with a plain `jest.fn()` (no default implementation)
    // in several `ChatWorkspace.test.tsx` cases that don't care about its result, which
    // resolves to `undefined`, not a rejected/resolved Promise; calling `.then()` directly on
    // that `undefined` throws synchronously ("Cannot read properties of undefined (reading
    // 'then')") *before* a chained `.catch()` ever gets a chance to attach, whereas `await
    // undefined` is simply `undefined` and lets the `try`/`catch` below handle whatever
    // downstream code (here, `toChatMessages`) does with that.
    (async () => {
      let conversations: ConversationSummaryResponse[];
      try {
        conversations = await listConversations();
      } catch (err: unknown) {
        if (!cancelled) {
          setList({ status: "error", message: describeError(err) });
          // Unknown whether any conversation exists -- falling through to ChatPanel's own
          // auto-create is the same safe default the empty-list branch below takes.
          setInitialSelectionResolved(true);
        }
        return;
      }
      if (cancelled) return;
      setList({ status: "ready", conversations });
      if (conversations.length === 0) {
        setInitialSelectionResolved(true); // nothing to adopt -- ChatPanel auto-creates
        return;
      }
      // Medium-3 fix (see this component's [Caution]): adopt the most-recently-updated
      // existing conversation instead of always leaving `selectedId` null, which is what made
      // `ChatPanel` auto-create a brand-new, empty conversation on every page load.
      // `conversations[0]` is safe to treat as "most recent" without a client-side sort --
      // `listConversations`'s own [Rationale] documents the backend's `updatedAt DESC`
      // ordering guarantee.
      setIsLoadingHistory(true);
      try {
        const history = await getMessages(conversations[0].id);
        if (cancelled) return;
        setInitialMessages(toChatMessages(history));
        setSelectedId(conversations[0].id);
      } catch (err: unknown) {
        // Falls back to the pre-fix behavior (leave selectedId null -> ChatPanel
        // auto-creates) rather than leaving the screen stuck unselected -- e.g. the
        // conversation could have been deleted from another tab between the two calls.
        if (!cancelled) setActionError(describeActionError(err, "대화를 불러오지 못했습니다"));
      } finally {
        if (!cancelled) {
          setIsLoadingHistory(false);
          // Set unconditionally here (success or failure) -- either way the mount effect has
          // now fully decided what `<ChatPanel>` should render with (see this component's
          // [Caution]).
          setInitialSelectionResolved(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Shared by both creation paths: ChatPanel's own auto-create-on-mount (no conversationId
  // supplied) and ConversationSidebar's explicit "새 대화" button (handleCreate below) --
  // either way, a brand-new conversation has no history to fetch.
  const handleConversationCreated = useCallback(
    (created: ConversationResponse) => {
      setSelectedId(created.id);
      setInitialMessages([]);
      setActionError(null);
      void refreshList();
    },
    [refreshList],
  );

  const handleCreate = useCallback(async () => {
    setActionError(null);
    try {
      const created = await createConversation();
      handleConversationCreated(created);
    } catch (err: unknown) {
      setActionError(describeActionError(err, "대화를 만들지 못했습니다"));
    }
  }, [handleConversationCreated]);

  const handleSelect = useCallback(
    async (id: string) => {
      if (id === selectedId) return;
      setActionError(null);
      setIsLoadingHistory(true);
      try {
        const history = await getMessages(id);
        setInitialMessages(toChatMessages(history));
        setSelectedId(id);
      } catch (err: unknown) {
        // Deliberately does not change `selectedId` -- a failed selection attempt leaves the
        // current conversation on screen instead of blanking it.
        setActionError(describeActionError(err, "대화를 불러오지 못했습니다"));
      } finally {
        setIsLoadingHistory(false);
      }
    },
    [selectedId],
  );

  const handleDelete = useCallback(
    async (id: string) => {
      setActionError(null);
      try {
        await deleteConversation(id);
        // Fetches the post-delete list directly (rather than via the `refreshList`
        // callback) so the fresh array is available right here for the Medium-3
        // fallback-selection decision below, without a second round trip. Errors from this
        // call are handled the same way `refreshList` itself would (set `list` to an error
        // state) rather than being allowed to propagate into the outer `catch`, which is
        // reserved for `deleteConversation` failures -- a list-refresh failure here must not
        // be reported to the user as "대화를 삭제하지 못했습니다" when the delete itself
        // actually succeeded.
        let conversations: ConversationSummaryResponse[] = [];
        try {
          conversations = await listConversations();
          setList({ status: "ready", conversations });
        } catch (err: unknown) {
          setList({ status: "error", message: describeError(err) });
        }
        if (id === selectedId) {
          // Medium-3 fix (see this component's [Caution]): adopt the most-recently-updated
          // *remaining* conversation instead of unconditionally falling back to "no
          // selection" -- which used to make ChatPanel auto-create yet another empty
          // conversation on every delete of the currently-open one. `conversations` here has
          // already been filtered server-side by `deleteConversation`'s effect (soft-deleted
          // rows are excluded by `listConversations`'s own query, see its [Rationale]), so no
          // client-side re-filtering of `id` out of it is needed.
          if (conversations.length > 0) {
            setIsLoadingHistory(true);
            try {
              const history = await getMessages(conversations[0].id);
              setInitialMessages(toChatMessages(history));
              setSelectedId(conversations[0].id);
            } catch (err: unknown) {
              // Same fallback as the mount effect: don't get stuck unselected if the
              // adopted candidate's history fails to load for some other reason.
              setActionError(describeActionError(err, "대화를 불러오지 못했습니다"));
              setSelectedId(null);
              setInitialMessages([]);
            } finally {
              setIsLoadingHistory(false);
            }
          } else {
            // Nothing left to adopt -- this is the one case where falling back to "no
            // selection yet" (ChatPanel auto-creates, see this component's [Caution]) is the
            // correct outcome, not the bug Medium-3 flagged.
            setSelectedId(null);
            setInitialMessages([]);
          }
        }
      } catch (err: unknown) {
        setActionError(describeActionError(err, "대화를 삭제하지 못했습니다"));
        void refreshList(); // reconciles the list with server truth either way
      }
    },
    [refreshList, selectedId],
  );

  return (
    <div className={styles.workspace}>
      <ConversationSidebar
        list={list}
        selectedId={selectedId}
        onSelect={(id) => void handleSelect(id)}
        onCreate={() => void handleCreate()}
        onDelete={(id) => void handleDelete(id)}
        isBusy={isLoadingHistory}
        actionError={actionError}
      />
      <div className={styles.main}>
        <div className={styles.toolbar}>
          <ModelSelector value={modelId} onChange={setModelId} />
          {isLoadingHistory && <p role="status">대화 불러오는 중...</p>}
        </div>
        {initialSelectionResolved ? (
          <ChatPanel
            key={selectedId ?? NEW_CONVERSATION_KEY}
            conversationId={selectedId ?? undefined}
            initialMessages={initialMessages}
            model={modelId ?? undefined}
            onConversationCreated={handleConversationCreated}
          />
        ) : (
          // Mount-effect decision (adopt vs. auto-create) is still pending -- see this
          // component's [Caution] for why `<ChatPanel>` must not render yet. Suppressed while
          // `isLoadingHistory`'s own status text above is already announcing the same wait
          // (the adopt-branch case), so only one live region is ever visible at a time.
          !isLoadingHistory && <p role="status">대화 준비 중...</p>
        )}
      </div>
    </div>
  );
}

/** Maps `GET .../messages`'s wire shape to `MessageList`'s UI-level `ChatMessage` -- adds the
 *  client-only `id` React key `ChatMessage` requires (see its own [Caution]) and marks every
 *  assistant row `"done"` (history is, by definition, never mid-stream). */
function toChatMessages(responses: MessageResponse[]): ChatMessage[] {
  return responses.map((message, index) => ({
    id: `history-${index}`,
    role: message.role,
    content: message.content,
    ...(message.role === "assistant" ? { streamStatus: "done" as const } : {}),
  }));
}

/**
 * [Purpose] Turns a create/select/delete failure into a message a user can act on, branching
 *   on {@link ApiError.status} rather than assuming the `ProblemDetail` body's shape (chat
 *   Spec Tech Review, `GlobalExceptionHandler` -- a 403 body's `detail` is a fixed safe
 *   constant, never the raw exception message, but this function does not depend on that
 *   either way).
 * [Caution] The 404 branch intentionally covers three indistinguishable backend causes
 *   (unknown id / another session's conversation / already deleted) without guessing which
 *   one applies -- see `lib/api/conversations.ts`'s `deleteConversation` [Caution].
 */
function describeActionError(err: unknown, action: string): string {
  if (err instanceof ApiError) {
    if (err.status === 404) return `${action}: 대화를 찾을 수 없습니다 (삭제되었거나 접근할 수 없음).`;
    if (err.status === 403) return `${action}: 개수 상한에 도달했습니다.`;
    return `${action}: ${err.message}`;
  }
  return err instanceof Error ? `${action}: ${err.message}` : `${action}: ${String(err)}`;
}

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
