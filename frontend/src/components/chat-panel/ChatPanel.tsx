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
 * [Caution] `ragEnabled` (card T6, retyped to `boolean | undefined` by card T14 finishing work
 *   Medium-1) is no longer passed through to {@link MessageList} (2026-09-02): this component
 *   reads it in exactly two places -- `composerHint` below, and `handleSend`, which snapshots
 *   the value onto each new assistant message as `ragEnabledAtSend` so an answer keeps the RAG
 *   reading it was actually generated under (a later toggle flip must not relabel past answers
 *   -- see {@link ./MessageList}'s `ChatMessage` [Caution] for the user-reported defect this
 *   fixes). It is still never sent on the wire (`streamAssistantReply`'s request has no
 *   per-message RAG field, Spec §8 D-1: the setting is conversation-scoped, changed only via
 *   `PATCH /api/conversations/{id}` from {@link ../chat-workspace/ChatWorkspace}). No default
 *   value anymore -- this component
 *   used to default a missing value to `true` (this project's backend default for a brand-new
 *   conversation), but {@link ../chat-workspace/ChatWorkspace} can now legitimately pass
 *   `undefined` for an *already-known* conversation too. A failed RAG-toggle PATCH no longer
 *   deletes that conversation's `ragById` entry (card T16 replaced that "auto-recover" design
 *   after it was Blocked in review five times); instead the id is added to a separate
 *   `ragUnverified` set, and only while an id is in that set does {@link
 *   ../chat-workspace/ChatWorkspace} gate this prop down to `undefined` (T16 2R High-1 --
 *   an earlier T16 version left this prop ungated, so this component kept rendering the
 *   stale-but-last-confirmed boolean as fact even while the header toggle honestly showed
 *   "확인 불가"). Silently reading `undefined` as ON would misreport a real, possibly-OFF
 *   conversation, in the document-leak direction. See `composerHint`'s own derivation below and
 *   {@link MessageList}'s own [Caution] for how the three states (`true`/`false`/`undefined`) are
 *   now told apart at every render site instead of collapsed by a default.
 * [Caution] `handleSend`'s optional `overrideText` parameter (card T7, chat-prototype-port
 *   AC-14) exists solely so {@link MessageList}'s empty-state example chips can send a fixed
 *   string without this component reading it back out of `draft` first (which would require
 *   an extra `setDraft`/re-render round trip before the value was available to send). A normal
 *   typed submission (`handleSubmit`/`handleKeyDown` below) always calls `handleSend()` with no
 *   argument, so `draft` remains authoritative for that path -- see Spec §Tech Review AD-2 for
 *   why the chips themselves render inside {@link MessageList}, not here.
 * [Caution] Card T8 fixes the `textarea` `placeholder` to one literal string (chat-prototype-
 *   port:AC-21) -- the old `conversation.status === "creating"` branch that swapped in a second
 *   "준비 중" placeholder is gone entirely, not just its text, since that branch was also the
 *   last surviving source of the retired `"대화 준비 중..."` loading-copy variant (chat-
 *   prototype-port:AC-7's final all-clear, owned by this card). The hint line below the form
 *   (`composerHint`, AC-22) and the fixed disclaimer under it (AC-23) both derive from state
 *   this component already owns/receives (`isSending`, `ragEnabled`) -- neither adds new state.
 * [Caution] Card T9 (chat-prototype-port:AC-17/AC-18): the composer's submit control swaps, at
 *   the same position, between the `전송` button (`isSending === false`) and a `생성 중단`
 *   button (`isSending === true`) -- see the JSX below, not a second piece of state. Clicking
 *   `생성 중단` calls {@link handleStop}, which aborts `activeStreamController.current` -- the
 *   exact same `AbortController` this component's own unmount-cleanup effect (above) already
 *   aborts, so `handleSend`'s catch block cannot (and does not need to) distinguish "user
 *   clicked stop" from "component unmounted mid-stream": either way it transitions the message
 *   to a terminal `streamStatus` with whatever text already arrived left intact -- see that
 *   catch block's own [Caution] for the `sources` value chosen (Spec §Tech Review AD-4,
 *   user-approved 2026-09-01).
 * [Caution] Card T10 (chat-prototype-port:AC-19/AC-20, Spec §Tech Review AD-9): a genuine
 *   stream failure (the non-abort `catch` branch below) no longer rewrites `content` -- no
 *   inline "메시지를 가져오지 못했습니다." sentence, no "(연결이 끊어졌습니다)" suffix. Whatever
 *   streamed in before the throw is left exactly as accumulated (possibly `""`); {@link
 *   MessageList}'s `MessageRow` renders the failure notice itself, as a `role="alert"` error
 *   card, from `streamStatus === "error"` alone -- entirely separate from `content` now, so the
 *   two no longer collide in one string. `onRetry` below is `handleSend` itself, reused the
 *   same way {@link MessageList}'s example chips reuse it (via `overrideText`) -- the error
 *   card's "다시 시도" button hands back the *failed* turn's own user-message text (Spec D-9:
 *   same `conversationId`, a new turn, the failed one left in place -- this component never
 *   deletes a message from `messages` on retry, and there is no delete-turn API to call even if
 *   it wanted to, Spec §5 "API").
 * [AC] chat:AC-36, chat:AC-38, chat:AC-39, chat-prototype-port:AC-7, chat-prototype-port:AC-14,
 *   chat-prototype-port:AC-17, chat-prototype-port:AC-18, chat-prototype-port:AC-19,
 *   chat-prototype-port:AC-20, chat-prototype-port:AC-21, chat-prototype-port:AC-22,
 *   chat-prototype-port:AC-23, chat-prototype-port:AC-24, chat-prototype-port:AC-25
 */
export function ChatPanel({
  conversationId: providedConversationId,
  initialMessages,
  model,
  // [Caution] (T14's second rework round, chat-prototype-port:AC-9/AC-42, Medium-1) No default value
  // here anymore -- the previous `= true` default (paired with `ConversationService`'s backend
  // default for a *brand-new* conversation) silently misrepresented an *already-known*
  // conversation whose real value this screen may not currently be able to vouch for (card T16's
  // `ragUnverified` gating on a failed RAG-toggle PATCH -- see this component's own top-of-file
  // [Caution] for the full rationale, corrected in T16's second rework round).
  ragEnabled,
  onConversationCreated,
}: {
  conversationId?: string;
  /** Seeds the transcript once, on mount, instead of starting empty (chat:AC-38). Loading
   *  this from `GET .../messages` is the caller's job -- see this component's [Rationale]. */
  initialMessages?: ChatMessage[];
  /** Forwarded as `streamAssistantReply`'s `model` option on every send (chat:AC-39). */
  model?: string;
  /** Read for the composer hint (chat-prototype-port:AC-22) and snapshotted onto each new
   *  assistant message as `ragEnabledAtSend` (badge/notice/citation gating, chat-prototype-
   *  port:AC-24/AC-25/AC-43/AC-46) -- see this component's own [Caution]. `undefined` is a
   *  real "not yet known" third state (card T14 finishing work, Medium-1), not defaulted here. */
  ragEnabled?: boolean;
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

  // `overrideText` (chat-prototype-port:AC-14) lets a caller -- currently only
  // `MessageList`'s example-chip `onExampleSelect` below -- send a fixed string instead of
  // whatever is currently in the `draft` textarea, without duplicating this function's
  // stream-lifecycle logic. `handleSubmit`/`handleKeyDown` below keep calling `handleSend()`
  // with no argument, so `draft` remains the source of truth for a normal typed submission.
  const handleSend = useCallback(
    async (overrideText?: string) => {
      const text = (overrideText ?? draft).trim();
      if (text === "" || isSending || conversation.status !== "ready") return;

      const conversationId = conversation.conversationId;
      const userMessage: ChatMessage = { id: nextMessageId("user"), role: "user", content: text };
      const assistantId = nextMessageId("assistant");
      const assistantMessage: ChatMessage = {
        id: assistantId,
        role: "assistant",
        content: "",
        streamStatus: "streaming",
        sources: null,
        // Send-time snapshot (2026-09-02): the RAG setting this turn is actually answered
        // under. Set once here, never updated -- a later toggle flip must not relabel this
        // answer's badge/notice/citations (see MessageList's `ChatMessage` [Caution]).
        ragEnabledAtSend: ragEnabled,
      };

      setMessages((prev) => [...prev, userMessage, assistantMessage]);
      setDraft("");
      setIsSending(true);

      const controller = new AbortController();
      activeStreamController.current = controller;

      let lastFinishReason: string | null = null;
      // hybrid-rag Spec AC-21: tracks the last non-null `sources` seen across the whole
      // stream, mirroring `lastFinishReason` above -- but unlike `finishReason`, a still-null
      // value here after the loop ends is a real, distinct state (BL-019 delivery failure,
      // clause ③), not "no chunk happened to carry one yet". Never read mid-loop; only
      // meaningful once the stream has closed (the `for await` below exits), matching {@link
      // streamAssistantReply}'s own "completion is `done`, not `finishReason`" rule.
      let lastSources: ChatMessage["sources"] = null;
      try {
        for await (const chunk of streamAssistantReply(conversationId, text, {
          signal: controller.signal,
          model,
        })) {
          if (chunk.finishReason !== null) lastFinishReason = chunk.finishReason;
          // `Array.isArray`, not `!== null` (review 2026-08-31 Low-1) -- a chunk missing the
          // `sources` key entirely parses to `undefined`, which `!== null` would treat as a
          // real update and overwrite an already-received array back to `undefined`, silently
          // discarding real citations mid-stream.
          if (Array.isArray(chunk.sources)) lastSources = chunk.sources;
          if (chunk.delta) {
            const delta = chunk.delta;
            updateAssistantMessage(assistantId, (m) => ({ ...m, content: m.content + delta }));
          }
        }
        updateAssistantMessage(assistantId, (m) => ({
          ...m,
          streamStatus: isErrorFinish(lastFinishReason) ? "error" : "done",
          sources: lastSources,
        }));
      } catch (err: unknown) {
        if (isAbortError(err)) {
          // Card T9 (chat-prototype-port:AC-18), Spec §Tech Review AD-4 option (b),
          // user-approved 2026-09-01: an abort here comes from either a user clicking `생성
          // 중단` ({@link handleStop}) or this component's own unmount-cleanup effect --
          // this branch cannot tell which, and does not need to. Either way it is an
          // intentional cancel, not a failure: `streamStatus` moves to `"done"`, never
          // `"error"`, and `content` is left exactly as already accumulated (no suffix, no
          // rewrite -- AC-18's "이미 도착한 텍스트는 화면에 남는다"). `sources` uses
          // `lastSources ?? undefined`, not `lastSources` alone -- a still-`null` `lastSources`
          // (no chunk ever carried one) must not reach {@link MessageList}'s `SourceList`,
          // whose clause ③ would then render "출처를 불러오지 못했습니다." and misreport a
          // user-initiated stop as a delivery failure (AD-4 rejected exactly this, option (a)).
          // `undefined` instead routes through `SourceList`'s "no stream was ever tracked for
          // this message" branch -- the same one a history-loaded message hits -- which is
          // accurate here: this message's stream tracking did end, deliberately, without ever
          // resolving a sources value. Input re-activation (AC-18's third clause) needs no
          // separate handling -- the `finally` block below unconditionally sets `isSending`
          // back to `false` on every exit path, including this one.
          updateAssistantMessage(assistantId, (m) => ({
            ...m,
            streamStatus: "done",
            sources: lastSources ?? undefined,
          }));
          return;
        }
        // Card T10 (chat-prototype-port:AC-19, Spec §Tech Review AD-9): `content` is left
        // untouched here -- no inline "메시지를 가져오지 못했습니다." sentence, no
        // "(연결이 끊어졌습니다)" suffix appended to whatever text already arrived (possibly
        // `""`). `streamStatus: "error"` alone is what {@link MessageList}'s `MessageRow`
        // reads to render the `role="alert"` error card below the (unmodified) content.
        // `sources: lastSources` here too (review 2026-08-31 round-2 correctness Low-2) -- a
        // chunk carrying real citations can arrive before a later chunk throws, so this must
        // not unconditionally discard back to `null`: whatever was actually received survives
        // (clause ②), and only a genuinely empty-handed stream falls through to clause ③'s
        // "출처를 불러오지 못했습니다." (deliberate either way, not redundant with the error
        // card above it -- one says the reply itself failed, the other reports the citation
        // data).
        updateAssistantMessage(assistantId, (m) => ({
          ...m,
          streamStatus: "error",
          sources: lastSources,
        }));
      } finally {
        if (activeStreamController.current === controller) activeStreamController.current = null;
        setIsSending(false);
      }
    },
    [conversation, draft, isSending, model, ragEnabled, updateAssistantMessage],
  );

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

  /**
   * [Purpose] Handler for the `생성 중단` button (card T9, chat-prototype-port:AC-18) --
   *   aborts whatever stream `handleSend` is currently tracking.
   * [Caution] Only reachable while `isSending` is true, since the button that calls this only
   *   renders then (see the JSX below) -- the optional-chaining call is defense in depth, not a
   *   condition this function itself needs to branch on. The actual state transition (terminal
   *   `streamStatus`, `sources`, text left intact) happens in `handleSend`'s own catch block,
   *   not here -- this function only signals the abort.
   */
  const handleStop = useCallback(() => {
    activeStreamController.current?.abort();
  }, []);

  if (conversation.status === "error") {
    return (
      <p role="alert" className={styles.error}>
        대화를 시작하지 못했습니다: {conversation.message}
      </p>
    );
  }

  const inputDisabled = conversation.status !== "ready" || isSending;
  // chat-prototype-port:AC-22 -- derived straight from `isSending`/`ragEnabled` (both already
  // tracked above/passed in), not a new piece of state: a streaming reply always wins over the
  // RAG-scoped hint, matching this component's own [Caution] that `ragEnabled` is a per-
  // conversation setting, not a per-turn one, so it has nothing meaningful to say while a turn
  // is actively in flight. `ragEnabled === true`/`=== false` (card T14 finishing work, Medium-1),
  // not a truthy/falsy check on the raw value -- `undefined` ("not yet known") must not silently
  // read as either ON or OFF copy (chat-prototype-port:AC-42's "0 of each" requirement extends
  // to this hint too, not only the header toggle).
  const composerHint = isSending
    ? "응답을 스트리밍 중입니다 · 입력이 잠시 비활성화됩니다"
    : ragEnabled === true
      ? "문서 근거 ON · 사내 문서에서 검색한 뒤 답변합니다"
      : ragEnabled === false
        ? "문서 근거 OFF · 모델의 일반 지식만으로 답변합니다"
        : "문서 근거 확인 중…";

  return (
    <div className={styles.panel}>
      <MessageList
        messages={messages}
        isSending={isSending}
        onExampleSelect={(text) => void handleSend(text)}
        onRetry={(text) => void handleSend(text)}
      />
      {/* Prototype P:399-423 -- one card (the form) holding the textarea and a bottom row with
          the state hint (AC-22) and the submit control; the fixed disclaimer (AC-23) sits
          centered under the card. Purely a layout regrouping: every element, id, and text is
          the same as before. */}
      <div className={styles.composer}>
        <div className={styles.composerInner}>
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
              placeholder="사내 문서에 대해 질문하세요. Shift + Enter 로 줄바꿈."
              disabled={inputDisabled}
              rows={2}
            />
            <div className={styles.composerRow}>
              <p className={styles.composerHint}>{composerHint}</p>
              {/* chat-prototype-port:AC-17 -- same-position swap keyed on `isSending`: exactly
                  one of `전송`/`생성 중단` renders at a time, never both/neither (Spec
                  `P:406-419`). The glyphs are decorative (`aria-hidden`), so each button's
                  accessible name stays exactly "전송" / "생성 중단". */}
              {isSending ? (
                <button type="button" className={styles.stopButton} onClick={handleStop}>
                  <span className={styles.stopIcon} aria-hidden="true" />
                  생성 중단
                </button>
              ) : (
                <button type="submit" className={styles.button} disabled={inputDisabled || draft.trim() === ""}>
                  전송
                  <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" className={styles.buttonIcon}>
                    <path
                      d="M2.5 11.5 L11.5 2.5 M5.5 2.5 H11.5 V8.5"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.7"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                </button>
              )}
            </div>
          </form>
          <p className={styles.composerNotice}>
            답변은 사내 문서를 근거로 생성됩니다. 중요한 결정 전에는 원문을 확인하세요.
          </p>
        </div>
      </div>
    </div>
  );
}

function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === "AbortError";
}

function describeError(err: unknown): string {
  return err instanceof Error ? err.message : String(err);
}
