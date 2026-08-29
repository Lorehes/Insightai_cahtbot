"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { createConversation, type ConversationResponse } from "@/lib/api/conversations";
import { isErrorFinish, streamAssistantReply } from "@/lib/api/messages";
import { MessageList, type ChatMessage } from "./MessageList";
import styles from "./ChatPanel.module.css";

type ConversationState =
  | { status: "creating" }
  | { status: "ready"; conversationId: string }
  | { status: "error"; message: string };

let messageIdCounter = 0;
/** Client-only React list key -- never sent to the backend, never compared across reloads. */
function nextMessageId(prefix: "user" | "assistant"): string {
  messageIdCounter += 1;
  return `${prefix}-${messageIdCounter}`;
}

/**
 * [Purpose] The chat stream screen: an input box that sends a question and a transcript that
 *   renders the assistant's reply as it streams in (chat Spec §5 "화면 (D3)" ②③,
 *   chat:AC-36), with markdown/code-block rendering handled by {@link MessageList}
 *   (chat:AC-37). Also seeds the transcript with a conversation's prior turns when given
 *   (chat:AC-38) and lets a caller pin which model a turn is sent with (chat:AC-39).
 * [Rationale] Auto-creates its own conversation on mount via `POST /api/conversations`
 *   (see `lib/api/conversations.ts` [Rationale]) only when no `conversationId` is supplied --
 *   a conversation id is a hard prerequisite for `POST .../messages`. When a `conversationId`
 *   is supplied, that bootstrap step is skipped and the given id is used as-is -- read once,
 *   on mount, not re-read on every prop change (a conversation, once open, does not silently
 *   swap under the user while they are mid-turn; see [Caution]). `initialMessages` is likewise
 *   only read once, via `useState`'s lazy-initial-value form. This component itself still
 *   never calls `GET .../messages` -- that fetch and the wire-to-UI mapping both live in
 *   {@link ../chat-workspace/ChatWorkspace} (card F3), which also decides when a fresh
 *   `<ChatPanel>` mount is warranted via its `key` prop (see that component's own
 *   [Rationale] for why the split landed there instead of inside this component).
 *   `onConversationCreated` is the one hook this component gives a caller into its own
 *   auto-create path, so a parent can learn the freshly-minted id without this component
 *   knowing anything about a conversation list.
 * [Caution] Only one turn streams at a time -- the input is disabled while a reply is in
 *   flight (this backend does not document behavior for two concurrent streams on the same
 *   conversation, so this component never attempts it). A stream in progress is aborted on
 *   unmount (see the second effect below) so no state update ever lands on an unmounted
 *   component -- this doubles as the cleanup for a mid-turn conversation switch, since a
 *   caller that changes conversations is expected to do so via a `key` change (a fresh
 *   mount), not a prop update on the same instance.
 * [AC] chat:AC-36, chat:AC-38, chat:AC-39
 */
export function ChatPanel({
  conversationId: providedConversationId,
  initialMessages,
  model,
  onConversationCreated,
}: {
  conversationId?: string;
  /** Seeds the transcript once, on mount, instead of starting empty (chat:AC-38). Loading
   *  this from `GET .../messages` is the caller's job -- see this component's [Rationale]. */
  initialMessages?: ChatMessage[];
  /** Forwarded as `streamAssistantReply`'s `model` option on every send (chat:AC-39). */
  model?: string;
  /** Fired once this component's own auto-create (no `conversationId` supplied) resolves. */
  onConversationCreated?: (conversation: ConversationResponse) => void;
}) {
  const [conversation, setConversation] = useState<ConversationState>(
    providedConversationId ? { status: "ready", conversationId: providedConversationId } : { status: "creating" },
  );
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages ?? []);
  const [draft, setDraft] = useState("");
  const [isSending, setIsSending] = useState(false);
  const activeStreamController = useRef<AbortController | null>(null);

  useEffect(() => {
    if (providedConversationId) return;
    let cancelled = false;
    createConversation()
      .then((created) => {
        if (!cancelled) {
          setConversation({ status: "ready", conversationId: created.id });
          onConversationCreated?.(created);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) setConversation({ status: "error", message: describeError(err) });
      });
    return () => {
      cancelled = true;
    };
    // Deliberately empty deps -- see this component's own [Rationale]: the conversation
    // bootstrap runs exactly once per mount, not on every `providedConversationId` change.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Aborts any in-flight stream when the panel unmounts, so no `setMessages` call from a
  // stale `streamAssistantReply` loop ever fires after React has stopped rendering this
  // component.
  useEffect(() => {
    return () => activeStreamController.current?.abort();
  }, []);

  const updateAssistantMessage = useCallback((id: string, updater: (message: ChatMessage) => ChatMessage) => {
    setMessages((prev) => prev.map((m) => (m.id === id ? updater(m) : m)));
  }, []);

  const handleSend = useCallback(async () => {
    const text = draft.trim();
    if (text === "" || isSending || conversation.status !== "ready") return;

    const conversationId = conversation.conversationId;
    const userMessage: ChatMessage = { id: nextMessageId("user"), role: "user", content: text };
    const assistantId = nextMessageId("assistant");
    const assistantMessage: ChatMessage = {
      id: assistantId,
      role: "assistant",
      content: "",
      streamStatus: "streaming",
    };

    setMessages((prev) => [...prev, userMessage, assistantMessage]);
    setDraft("");
    setIsSending(true);

    const controller = new AbortController();
    activeStreamController.current = controller;

    let lastFinishReason: string | null = null;
    try {
      for await (const chunk of streamAssistantReply(conversationId, text, { signal: controller.signal, model })) {
        if (chunk.finishReason !== null) lastFinishReason = chunk.finishReason;
        if (chunk.delta) {
          const delta = chunk.delta;
          updateAssistantMessage(assistantId, (m) => ({ ...m, content: m.content + delta }));
        }
      }
      updateAssistantMessage(assistantId, (m) => ({
        ...m,
        streamStatus: isErrorFinish(lastFinishReason) ? "error" : "done",
      }));
    } catch (err: unknown) {
      if (isAbortError(err)) return; // intentional cancel (unmount) -- not a failure to report
      updateAssistantMessage(assistantId, (m) => ({
        ...m,
        content: m.content === "" ? "메시지를 가져오지 못했습니다." : `${m.content}\n\n(연결이 끊어졌습니다)`,
        streamStatus: "error",
      }));
    } finally {
      if (activeStreamController.current === controller) activeStreamController.current = null;
      setIsSending(false);
    }
  }, [conversation, draft, isSending, model, updateAssistantMessage]);

  const handleSubmit = useCallback(
    (e: React.FormEvent) => {
      e.preventDefault();
      void handleSend();
    },
    [handleSend],
  );

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      // Enter sends; Shift+Enter inserts a newline (standard chat-input convention).
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        void handleSend();
      }
    },
    [handleSend],
  );

  if (conversation.status === "error") {
    return (
      <p role="alert" className={styles.error}>
        대화를 시작하지 못했습니다: {conversation.message}
      </p>
    );
  }

  const inputDisabled = conversation.status !== "ready" || isSending;

  return (
    <div className={styles.panel}>
      <MessageList messages={messages} />
      <form onSubmit={handleSubmit} className={styles.form}>
        <label htmlFor="chat-message-input" className={styles.visuallyHidden}>
          메시지
        </label>
        <textarea
          id="chat-message-input"
          className={styles.textarea}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder={
            conversation.status === "creating" ? "대화 준비 중..." : "메시지를 입력하세요 (Enter로 전송, Shift+Enter로 줄바꿈)"
          }
          disabled={inputDisabled}
          rows={3}
        />
        <button type="submit" className={styles.button} disabled={inputDisabled || draft.trim() === ""}>
          전송
        </button>
      </form>
    </div>
  );
}

function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
