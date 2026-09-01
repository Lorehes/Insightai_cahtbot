"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/api/client";
import {
  createConversation,
  deleteConversation,
  listConversations,
  updateRagEnabled,
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
 *
 *   Card T4 (chat-prototype-port) adds a `<header>` above `<ChatPanel>` holding the selected
 *   conversation's title (`h1`, chat-prototype-port:AC-8) and the relocated `<ModelSelector>`
 *   (moved out of the old `.toolbar`, which this header replaces). The title has no separate
 *   `useState` copy -- Spec §Tech Review AD-6 requires the same "single owner, derive on
 *   render" shape already used for the RAG toggle (card T5, documented further below):
 *   `headerTitle` below is
 *   derived straight from `list`/`selectedId` each render, so it can never drift from the list
 *   `ConversationSidebar` also renders from. `selectedConversation === undefined` (list not
 *   ready yet, or `selectedId` is `null` pending the mount effect's decision) falls back to the
 *   same `새 대화` label a `null` `title` does -- matching the prototype's own `activeTitle`
 *   default (`insight-chat 프로토타입.html` P:523: `scen === "empty" ? "새 대화" : active ?
 *   active.title : "새 대화"`). T4 also unifies the loading copy to `대화를 불러오는 중…` (was
 *   `대화 불러오는 중...`, chat-prototype-port:AC-7 partial -- T8 owns the final all-clear) and
 *   adds a 4-line shimmer skeleton alongside it while `isLoadingHistory` (chat-prototype-port:
 *   AC-41) -- rendered next to, not instead of, `<ChatPanel>`, since T4's scope is this file
 *   only and `<ChatPanel>` (owned by other cards) already tolerates rendering stale messages
 *   while a new selection's history loads.
 *
 *   Card T5 (chat-prototype-port) adds the RAG ON/OFF pill toggle to the same `<header>`,
 *   right after `<ModelSelector>`. Spec §Tech Review AD-6 makes this component the single owner
 *   of that state too, the same "no separate `useState` copy" shape `headerTitle` above already
 *   uses: the toggle's `aria-checked` and label text are derived straight from
 *   `selectedConversation?.ragEnabled` on every render, so they can never drift from what
 *   `ConversationSidebar` renders for the same list. `handleToggleRag` calls `updateRagEnabled`
 *   (card T1) and applies the *response's* `ragEnabled` -- not the locally-computed negation --
 *   to `setList` only after the PATCH resolves (chat-prototype-port:AC-10's "응답 성공 후"
 *   wording; no optimistic update, so a failed request leaves `aria-checked` exactly where it
 *   was, for free, since there is no local copy to roll back). A failure reuses the existing
 *   `actionError` banner instead of opening a second error surface.
 *
 *   Two fixes from the T5 review round (2026-09-01, report `docs/review/2026-09-01_chat-
 *   prototype-port-T5.md`). High-1 (in-flight guard) is unchanged and still true today. High-2's
 *   specific mechanism described below (`selectedConversation === undefined` / `?? true`) is
 *   superseded by T14's `ragById` design (see this file's "T14 rework" paragraph, further below,
 *   for what actually governs display/guard logic now) -- kept here only as the historical record
 *   of the bug High-2 fixed and why `?? true` was chosen at the time; the current code no longer
 *   contains a `?? true`/`?? false` fallback anywhere in this derivation:
 *
 *   (High-1) `ragToggleInFlightRef` is a `useRef` (not `useState`) in-flight guard, checked
 *   synchronously as the very first line of `handleToggleRag` and set/cleared around the
 *   `await updateRagEnabled(...)` call. A `useState` flag cannot do this job: a second click
 *   fired before React re-renders would still close over the pre-update value and slip through
 *   (see `isRagUpdating` below, which is *only* for the button's visual `disabled`, not the
 *   guard itself). Without this guard, two rapid clicks racing to PATCH `true`->`false` and
 *   back could have their responses land out of request order, leaving the UI showing the
 *   opposite of the server's actual `ragEnabled` -- and this toggle is the only control that
 *   tells the user whether their messages leave the company's OpenRouter boundary carrying
 *   retrieved document text, so a silently-wrong reading here is worse than most UI staleness.
 *   `isRagUpdating` (`useState`, mirrors the ref for render purposes only) additionally disables
 *   the button while a request is outstanding, so a user cannot even attempt the race visually.
 *
 *   (High-2) The button's `disabled` condition and every display fallback (`aria-checked`, the
 *   `.ragToggleOn` class, both label lines) now key off `selectedConversation === undefined`
 *   and fall back to `?? true`, not the previous `selectedId === null` / `?? false`. That
 *   previous pairing was wrong on two counts at once: `selectedId` can be non-null while
 *   `selectedConversation` is still `undefined` (the `refreshList()` round-trip right after
 *   `setSelectedId` on every new-conversation path, or any time `list.status === "error"`) --
 *   in that window the old code left the button *enabled* showing "OFF" while `handleToggleRag`
 *   itself silently no-opped (`if (!current) return`), a real yet invisible AC-9/AC-10
 *   violation. And separately, `?? false` was never the right fallback value to begin with:
 *   `ChatPanel`'s own `ragEnabled` prop below (card T6/T8) already falls back to `?? true`,
 *   because that is this project's actual backend default -- `ConversationService.java`'s
 *   `createConversation` resolves an unspecified `ragEnabled` to `true`
 *   (`resolvedRagEnabled = ragEnabled == null || ragEnabled`, line ~140) before a new
 *   conversation is even persisted. `?? false` and `?? true` next to each other for the same
 *   underlying value was an inter-component inconsistency that predates this fix; the T5 review
 *   round's High-2 only surfaced it through the toggle's half, and unifying both halves on
 *   `?? true` here removes the inconsistency rather than choosing it for convenience.
 *   [Caution: DB column default is the opposite] `conversations.rag_enabled`'s own column
 *   default is `false` (`information_schema.columns`, checked 2026-09-01) -- the *service*
 *   default (`true`, above) is what actually governs today only because every insert path goes
 *   through `ConversationService.createConversation`, which always passes an explicit resolved
 *   value and never relies on the column default. If a future insert path (raw SQL, a
 *   migration backfill, a different service method) ever omits `rag_enabled`, that row would
 *   silently get `false` -- the opposite of what this `?? true` fallback assumes. Not this
 *   card's scope to reconcile (would need a DB migration, out of bounds here); recorded so the
 *   next person doesn't have to re-derive it.
 *   The prototype's decorative sliding-knob graphic is approximated by a pure-CSS, `aria-hidden`
 *   `.ragKnob` (see `ChatWorkspace.module.css`); the on/off distinction is still carried by the
 *   label text and `aria-checked`, with the wash/ring/knob change alongside them, not color
 *   alone (accessibility default "색만으로 정보 전달 금지").
 *
 *   2nd review round fix (2026-09-01, same report's "2라운드" section -- T5's rework was Blocked
 *   again on a *new* bug the High-2 fix above introduced). `refreshList` and `handleDelete`'s
 *   post-delete re-fetch both used to replace the current `list` with `{ status: "error", ... }`
 *   on failure -- and `ConversationListState`'s `"error"` variant (defined in
 *   `ConversationSidebar.tsx`, T3's file, deliberately left untouched by this fix) carries no
 *   `conversations` array at all. For a conversation the user had already opened and explicitly
 *   turned RAG *off* for, losing `list` meant `findSelectedConversation` could no longer find it,
 *   `selectedConversation` became `undefined`, and the `?? true` fallback above -- whose entire
 *   justification is "this is the backend's default for a *brand-new* conversation" -- silently
 *   applied to an *already-known* one instead, flipping `aria-checked`/the label text to ON with
 *   no user action, and with no way back short of a page reload (the button is `disabled` in
 *   this state). The root cause was this state transition itself, not the `?? true` fallback
 *   value -- so the fix is in `refreshList` and `handleDelete` below, not here: a refresh failure
 *   now keeps whatever `list` already held if it was `"ready"` (every already-known
 *   conversation's real `ragEnabled` survives) and reports the failure through `actionError`
 *   instead of through `list` itself. `list` still turns to `"error"` only when there was
 *   nothing `"ready"` to fall back to -- the mount effect's own first-ever fetch (see its own
 *   inline comments) is intentionally unchanged, since at that point there is no prior list to
 *   lose.
 *
 *   3rd review round fix (2026-09-01, same report -- a pre-existing race condition present since
 *   T5's original delivery, first surfaced by this round's review even though it did not
 *   originate from either of the two fixes above). `refreshList` and `handleDelete`'s post-
 *   delete `listConversations()` re-fetch are both fire-and-await calls with no cancellation --
 *   if one is dispatched, then `handleToggleRag`'s PATCH is dispatched *and resolves* (applying
 *   its result to `list`), and only *then* does the older re-fetch's response finally arrive, the
 *   re-fetch's stale `conversations` snapshot (captured before the PATCH) used to unconditionally
 *   overwrite `list` in full via `setList({ status: "ready", conversations })` -- silently
 *   reverting the just-applied, already-visible PATCH result back to whatever it was before the
 *   user clicked the toggle. This round's fix was a generation counter (`ragToggleGenerationRef`,
 *   bumped on every PATCH success, compared against a dispatch-time snapshot by both re-fetch
 *   sites) -- **removed by T14, see below; this paragraph is kept only as history, the mechanism
 *   it describes no longer exists in this file.**
 *
 *   T14 rework (2026-09-01, `BL-032` -- full origin, including the 4th review round's probe that
 *   found the generation counter's own gap, is Spec §Tech Review "T14 신설 근거" and
 *   `docs/review/2026-09-01_chat-prototype-port-T5-round4.md`). The generation counter T5 3R
 *   added only protected the id that was *selected at the moment a re-fetch was dispatched*
 *   (`idAtDispatch`) -- not the id a user might switch to and toggle *while that re-fetch was
 *   still in flight*. Four rounds of patching individual races (in-flight guard, failed-refresh
 *   fallback, single-id generation counter, ...) kept finding a next one, because the actual
 *   design flaw was one level up: *any* list re-fetch had the power to overwrite `ragEnabled` at
 *   all.
 *
 *   T14's first attempt (`mergeConversationList`, no longer in this file -- see git history)
 *   removed that power for the *value* axis: a re-fetch response could no longer overwrite a
 *   known id's `ragEnabled` with a *different value*. It was itself Blocked in review, though,
 *   for the *existence* axis it left open: (a) `mergeConversationList` only ever ran on
 *   `refreshList`/`handleDelete`'s responses, never on the mount effect's own first-ever
 *   `listConversations()` call, so a conversation selected before that first call resolved had no
 *   protection at all yet; and (b) `mergeConversationList` rebuilt its output via `fresh.map(...)`
 *   -- an id genuinely *absent* from a particular `fresh` response produced no output entry for
 *   it either, silently losing the conversation (and whatever protected value it had) rather than
 *   merely failing to update it.
 *
 *   The current design (`ragById`, a `useState<Record<string, boolean>>` declared above,
 *   completely separate from `list`) removes both axes at once by changing *what* is being
 *   protected, not just *when*: `list` no longer carries `ragEnabled` display authority for
 *   anything at all -- `ragValue`/`ragEnabled` display below reads only `ragById`, never
 *   `list`/`selectedConversation`. Every site that learns about conversations from the server
 *   (the mount effect's own first fetch, `refreshList`, `handleDelete`'s post-delete refetch) now
 *   routes its response through `seedRagById` (module scope, below `ChatWorkspace`), which can
 *   only *add* an entry for an id `ragById` does not already have -- it can neither overwrite an
 *   existing entry's value (closes the value axis, same as `mergeConversationList` did) nor lose
 *   one for an id a particular response happens to omit (closes the existence axis
 *   `mergeConversationList` left open, since there is no `.map` over the response producing the
 *   next state -- an absent id simply has no effect, rather than needing to be defended against).
 *   `handleConversationCreated` additionally seeds `ragById` *synchronously*, in the same handler
 *   invocation that sets `selectedId`, from the create response's own `ragEnabled` field -- so a
 *   freshly created, freshly selected conversation is never in the "selected but unknown" window
 *   even for the instant before any refetch lands (this is what closes review round 4's own
 *   report's "path 7": the sidebar's "새 대화" button is reachable even before the mount effect's
 *   first fetch resolves, since only `<ChatPanel>` -- not the header/sidebar -- is gated on
 *   `initialSelectionResolved`). The *only* place `ragEnabled` may change for an *already-known*
 *   id is `handleToggleRag`'s own `setRagById` call after its PATCH resolves -- there is no
 *   dispatch-time snapshot, generation counter, or per-refetch comparison logic left anywhere in
 *   this file; a stale response cannot win a race it no longer has standing to enter.
 *   [Caution: cross-tab/device tradeoff] A `ragEnabled` change made outside this screen (a
 *   different browser tab, a different device, or a direct API call) is not picked up by a list
 *   re-fetch here -- `seedRagById` only ever adds an id it does not already know, never
 *   overwrites one it does, so this screen keeps showing its own already-known (now stale) value
 *   until this conversation is *freshly* learned about again (e.g. a first-ever mount fetch, or
 *   any other id not yet in `ragById`). This is an accepted, deliberate tradeoff for this
 *   project's session model (one anonymous session per user, chat Spec), not an oversight -- T5's
 *   four review rounds plus T14's own first attempt all tried to guard *both* "protect against
 *   stale re-fetches" and "stay live to genuine concurrent server-side changes" at once, and that
 *   combination is exactly what kept failing; removing the second goal entirely (this tradeoff)
 *   is what finally let the first one close completely instead of merely narrowing. See Spec
 *   §Tech Review "T14 신설 근거" and `docs/review/2026-09-01_chat-prototype-port-T5.md` /
 *   `-round4.md` for the full history if multi-session sync ever becomes a requirement.
 *
 *   T16 rework (2026-09-01/02, `chat-prototype-port:AC-44`, user-approved «3b» -- full origin in
 *   the Spec's own AC-44 row and its "T16 신설 근거" Tech Review section). A failed RAG-toggle
 *   PATCH used to make `handleToggleRag`'s `catch` block delete the id's `ragById` entry, folding
 *   the failure back into the same "not yet known" state AC-42 covers -- on the theory that the
 *   *next* list re-fetch would simply re-learn the server's real value. Getting that recovery to
 *   be race-free is exactly the "which re-fetch has standing to fill this id" problem T5's four
 *   rounds and T14's first two rounds kept re-discovering through different doors (see the long
 *   history above) -- five rounds in, the direction itself (not any one implementation of it) was
 *   the recurring failure. AC-44 (user-approved «3b») removes automatic recovery rather than
 *   attempting a sixth guard: a failed PATCH now adds the id to a new, separate state
 *   (`ragUnverified`, a `useState<Set<string>>`) instead of touching `ragById` at all. `ragById`
 *   keeps holding the last-confirmed value undisturbed -- not because `ChatPanel`'s own
 *   `ragEnabled` prop below still reads it while unverified (it does not; that prop is gated on
 *   `isRagUnverified` too, see its own [Caution]), but so a later successful re-fetch does not
 *   have to re-learn this id from scratch, which would reopen the delete-then-relearn race this
 *   rework retired. `ragUnverified` is a persistent flag no re-fetch site ever clears --
 *   AC-44's own text ("이후 목록 재조회가 그 대화의 RAG 값을 바꾸지 않으며, 페이지를 다시 불러와야
 *   해제된다") requires it to survive any number of in-page re-fetches and clear only on an actual
 *   page reload (a fresh mount starts with a fresh, empty `Set`). See `ragUnverified`'s own
 *   declaration comment, `handleToggleRag`'s own [Caution], and `isRagUnverified`'s own [Caution]
 *   (all further below in this file) for the mechanism, the guard, and the accessibility
 *   correction (no `aria-checked` boolean claim while unverified) respectively.
 * [AC] chat:AC-38, chat:AC-39, chat-prototype-port:AC-7, chat-prototype-port:AC-8,
 *   chat-prototype-port:AC-9, chat-prototype-port:AC-10, chat-prototype-port:AC-11,
 *   chat-prototype-port:AC-24, chat-prototype-port:AC-25, chat-prototype-port:AC-40,
 *   chat-prototype-port:AC-41, chat-prototype-port:AC-42, chat-prototype-port:AC-44
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
  // High-1 fix (T5 review round, `docs/review/2026-09-01_chat-prototype-port-T5.md`): a `useRef`
  // in-flight guard for `handleToggleRag`, not a `useState` one -- it must be readable
  // synchronously by a second click that fires before React has re-rendered from the first
  // click's `setIsRagUpdating(true)`, which a `useState`-only guard cannot do (see
  // `handleToggleRag`'s own [Caution]). `isRagUpdating` below is the `useState` twin used only
  // to drive the button's visible `disabled` state; it is not itself the guard.
  const ragToggleInFlightRef = useRef(false);
  const [isRagUpdating, setIsRagUpdating] = useState(false);
  // Card T14 rework (`BL-032`, see this component's top-of-file [Caution], "T14 rework"
  // paragraph) -- `ragEnabled` for every conversation this screen has ever learned about lives
  // here, keyed by conversation id, completely separate from `list` above. `list` is never read
  // for `ragEnabled` display purposes anymore (see `ragValue` below); it only supplies title/
  // existence/ordering. An id absent from this map means "not yet known" (a real third state,
  // not falsy-for-OFF) -- see `ragValue`'s own derivation for how that is surfaced. Since card
  // T16 (below), a failed RAG-toggle PATCH no longer deletes an id's entry here -- the last
  // confirmed value stays untouched; see `ragUnverified` just below for how the failure is
  // tracked instead.
  const [ragById, setRagById] = useState<Record<string, boolean>>({});
  // Card T16 (`chat-prototype-port:AC-44`, user-approved «3b», see this component's top-of-file
  // [Caution] "T16 rework" paragraph) -- ids whose last PATCH attempt failed and have not been
  // re-verified since. Deliberately *not* merged into `ragById` (e.g. as a third union member) --
  // `ragById[id]` keeps holding the last-confirmed server value the whole time an id is in this
  // set, but that retained value is not what `ChatPanel`'s own `ragEnabled` prop (below) reads
  // while unverified -- since T16's second rework round that prop is separately gated on this
  // same set and receives `undefined` instead (see its own [Caution] for why). Keeping the value
  // here (rather than deleting it) exists only so `handleToggleRag`'s success branch and
  // `handleDelete` have a normal known baseline to resolve back to. An id enters this set only in
  // `handleToggleRag`'s `catch`
  // block and leaves it only in that same function's success branch or `handleDelete` (when the
  // conversation itself is deleted) -- no re-fetch site (`refreshList`, `handleDelete`'s post-
  // delete refetch, the mount effect) ever touches it, on purpose: AC-44's own text ("이후 목록
  // 재조회가 그 대화의 RAG 값을 바꾸지 않으며, 페이지를 다시 불러와야 해제된다") requires this
  // state to survive any number of list re-fetches and clear only on an actual page reload (a
  // fresh mount, i.e. a fresh empty `Set`) -- not on any in-page event.
  const [ragUnverified, setRagUnverified] = useState<Set<string>>(new Set());

  const refreshList = useCallback(async () => {
    try {
      const conversations = await listConversations();
      // T14 rework (see this component's top-of-file [Caution]) -- `list` itself is a plain,
      // unconditional overwrite again (it no longer carries `ragEnabled` display authority, so
      // there is nothing left to merge/protect here). `ragById` is seeded separately, right
      // below, via `seedRagById`, which only *adds* entries for ids this screen has not seen
      // before -- an id already in `ragById` keeps its value no matter what this fresh response
      // says for it, regardless of timing or which id triggered the refetch.
      setList({ status: "ready", conversations });
      setRagById((prev) => seedRagById(prev, conversations));
    } catch (err: unknown) {
      // 2nd T5 rework-round fix (see this component's top-of-file [Caution], "2nd review round"
      // paragraph) -- do not clobber an already-`"ready"` list with the data-less `"error"`
      // variant on a refresh failure, so `findSelectedConversation`/`headerTitle` keep working
      // for a conversation this screen already knew about. Since T14, this branch has no bearing
      // on the RAG toggle at all -- `ragById` (this call's `try` above never reached `setRagById`
      // here, so nothing in it changes on a failure either) is what the toggle reads, and it is
      // untouched by this catch. The failure is still surfaced, just through `actionError`
      // instead of `list`.
      setList((prev) => (prev.status === "ready" ? prev : { status: "error", message: describeError(err) }));
      setActionError(describeActionError(err, "대화 목록을 새로고침하지 못했습니다"));
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
      // T14 rework (see this component's top-of-file [Caution]) -- the mount effect's own very
      // first fetch must seed `ragById` too, exactly like `refreshList`/`handleDelete` do below.
      // Before this line existed, this call site was the one path that bypassed `ragById`
      // entirely (it predates `ragById`'s introduction and only ever touched `list`), so the
      // very first render after mount had no seeded value for any conversation at all -- the RAG
      // toggle for `conversations[0]` would show "unknown" indefinitely, since nothing else was
      // going to seed it. `seedRagById` only adds ids not already known, so this is a no-op the
      // rare times something else (should not be possible this early, but kept safe regardless)
      // already seeded first.
      setRagById((prev) => seedRagById(prev, conversations));
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
      // T14 rework (see this component's top-of-file [Caution]) -- seeds `ragById` for `created`
      // synchronously, in the same event-handler invocation as `setSelectedId` above, from the
      // create response's own `ragEnabled` field (already part of `ConversationResponse`'s wire
      // shape -- `lib/api/conversations.ts`). This is what closes review round 4's "path 7":
      // there is no window where `selectedId` points at a conversation `ragById` does not yet
      // have a value for -- the toggle already knows the real value the instant this conversation
      // becomes selected, before `refreshList` below has even been dispatched, let alone
      // resolved. The `void refreshList()` call further down only refreshes `list` (title/
      // existence/ordering); it seeds `ragById` too (via `seedRagById`), but only ever as a
      // no-op for this id specifically, since `seedRagById` never overwrites an id already
      // present -- this line is what makes it already present.
      setRagById((prev) => ({ ...prev, [created.id]: created.ragEnabled }));
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
        // Card T16 -- drops `id` from `ragUnverified` too, if present. Not load-bearing for
        // correctness (a deleted conversation can never be `selectedId` again, so its
        // "unverified" flag would never be read either way) -- purely so a stale entry for an
        // id that no longer exists does not have to be reasoned about by the next reader of
        // this set.
        setRagUnverified((prev) => {
          if (!prev.has(id)) return prev;
          const next = new Set(prev);
          next.delete(id);
          return next;
        });
        // Fetches the post-delete list directly (rather than via the `refreshList`
        // callback) so the fresh array is available right here for the Medium-3
        // fallback-selection decision below, without a second round trip. Errors from this
        // call are handled the same way `refreshList` itself now does (2nd T5 rework-round fix,
        // see this component's top-of-file [Caution]) -- preserve `list` if it was already
        // `"ready"` instead of replacing it with the data-less `"error"` variant, and report the
        // failure through `actionError` -- rather than being allowed to propagate into the outer
        // `catch`, which is reserved for `deleteConversation` failures and must not be reported
        // to the user as "대화를 삭제하지 못했습니다" when the delete itself actually succeeded.
        // Reselection below (Medium-3 fix) reads only `.id` off this array -- since T14, its
        // `ragEnabled` fields are never read for display at all (`list` carries no `ragEnabled`
        // display authority anymore, see this component's top-of-file [Caution]), so there is no
        // "pre-merge" concern left here either way.
        let conversations: ConversationSummaryResponse[] = [];
        try {
          conversations = await listConversations();
          // T14 rework (see this component's top-of-file [Caution]) -- `list` is a plain
          // overwrite (existence/title/ordering only); `ragById` is seeded separately via
          // `seedRagById`, same as `refreshList` above, so this second, independent refetch site
          // cannot revert a RAG toggle PATCH either, no matter which conversation is selected
          // when this delete's own refetch was dispatched vs. when it resolves -- `seedRagById`
          // only ever adds ids `ragById` does not already have.
          setList({ status: "ready", conversations });
          setRagById((prev) => seedRagById(prev, conversations));
        } catch (err: unknown) {
          // `conversations` intentionally stays `[]` here -- the reselection logic just below
          // already treats an empty `conversations` as "nothing known to adopt" and falls back
          // to unselecting, which is the correct, unchanged behavior when the deleted
          // conversation was the open one and its replacement can't be determined right now.
          setList((prev) => (prev.status === "ready" ? prev : { status: "error", message: describeError(err) }));
          setActionError(describeActionError(err, "대화 목록을 새로고침하지 못했습니다"));
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

  // AD-6's "single owner, derive on render" shape (see this component's [Caution]) applied to
  // the header title -- no `useState` copy of `title` is kept. `undefined` covers both "list
  // not ready yet" and "selectedId is null" (mount decision still pending / nothing adopted),
  // and folds into the same `새 대화` fallback a `null` title already needs, matching the
  // prototype's `activeTitle` default (P:523). Lookup factored into `findSelectedConversation`
  // (module scope, below `ChatWorkspace`) -- `handleToggleRag` below needs the identical lookup
  // and used to repeat it inline; the T5 review round's maintainability follow-up asked for one
  // shared definition instead of two that could drift.
  const selectedConversation = findSelectedConversation(list, selectedId);
  const headerTitle = selectedConversation?.title ?? "새 대화";
  // T14 rework (see this component's top-of-file [Caution]) -- the single display value the
  // toggle button and its two label lines below read, sourced from `ragById` (module state
  // above), never from `list`/`selectedConversation`. Two states at this layer -- "not yet
  // known" (`undefined`) and "known" (the real value) -- with a third, orthogonal state
  // (`isRagUnverified` just below) layered on top of "known" by card T16:
  //   - `selectedId === null`: nothing selected, nothing to show.
  //   - `ragById[selectedId] === undefined`: selected, but this screen does not currently have a
  //     confirmed `ragEnabled` value for it (chat-prototype-port:AC-42's "미상"/unknown state).
  //     Reachable only by not having learned it yet -- brief, near-zero-render in practice, since
  //     every list-learning site and `handleConversationCreated` seed synchronously. Card T16
  //     removed the *other* way this used to be reachable: `handleToggleRag`'s `catch` block no
  //     longer deletes a previously known value on a failed PATCH (see `isRagUnverified` and that
  //     `catch` block's own comment) -- a failure now moves the id to card T16's separate
  //     "unverified" state instead, never back to "not yet known".
  //   - otherwise: the real, known value -- possibly stale-but-last-confirmed if `isRagUnverified`
  //     is also true for this id (T16).
  // No `?? true`/`?? false` fallback anywhere in this derivation -- collapsing "unknown" into
  // either boolean is exactly what let review round 4's cross-conversation race (and, before
  // that, 1R/2R's `selectedConversation === undefined` windows) silently claim a value this
  // screen did not actually know yet. `ragValue` stays `boolean | undefined` on purpose; every
  // reader below (`disabled`, the label text, `ChatPanel`'s `ragEnabled` prop) must decide for
  // itself what "unknown" means to it, not inherit one shared guess.
  const ragValue: boolean | undefined = selectedId !== null ? ragById[selectedId] : undefined;
  // Card T16 (chat-prototype-port:AC-44, user-approved «3b») -- true only for a *known* id
  // (`ragValue !== undefined`) whose last PATCH attempt failed and has not been re-verified
  // since (see `ragUnverified`'s own top-of-file declaration comment for the full lifecycle).
  // Never true for the "not yet known" state above -- `handleToggleRag` only ever adds an id to
  // `ragUnverified` after a PATCH it could only have attempted on an already-known value (its own
  // `current === undefined` guard), so the two states are mutually exclusive by construction, not
  // by a runtime check here.
  const isRagUnverified = selectedId !== null && ragUnverified.has(selectedId);
  // [Caution] (chat-prototype-port:AC-42, unchanged by T16) Why the toggle's `aria-checked="false"`
  //   in the "not yet known" window above is not a lie: `role="switch"` has no "mixed"
  //   `aria-checked`, so this component can never claim a boolean it does not actually have -- but
  //   it never claims one on its own either, because `aria-checked="false"` is paired everywhere
  //   it renders with a distinct "확인 중…" label (not "OFF") and `disabled`, so a screen reader
  //   user is told "unresolved", not told a false "off". This is only sound because every site
  //   that can move a conversation from "not yet known" to "known" (the mount effect's own first
  //   fetch, `refreshList`, `handleDelete`'s post-delete refetch, `handleConversationCreated`)
  //   calls `setRagById`/`seedRagById` synchronously in the same handler invocation that also
  //   calls `setList`/`setSelectedId`, so this window collapses to zero renders in practice.
  //   [Caution] (card T16, security review 2026-09-01 -- corrects this card's own first design
  //   draft) The `isRagUnverified` state just above does *not* reuse this same reasoning, on
  //   purpose: unlike the "not yet known" state (where no server value has ever been claimed, so
  //   there is nothing to contradict), `isRagUnverified` means this screen *did* learn a value once
  //   and a later PATCH may have committed a *different* one server-side without this screen ever
  //   finding out (the request succeeded but its response was lost). Reusing `ragById`'s
  //   last-confirmed boolean as `aria-checked` here -- the first draft of this fix did exactly
  //   that -- would let a screen reader user hear a definite "off" while the server, unknown to
  //   this screen, actually holds "on" (or vice versa): the same failure mode chat-prototype-
  //   port:AC-9/AC-10's whole guard chain exists to prevent, mirrored onto the one state that
  //   cannot rule it out. So the button below omits `role="switch"`/`aria-checked` entirely while
  //   `isRagUnverified` is true (falls back to a plain, disabled `<button>`) -- no boolean claim
  //   is made to assistive tech at all in that state, only the visible/accessible "확인 불가 ·
  //   새로고침" text. `ChatPanel`'s own `ragEnabled` prop (below) makes the same no-claim choice,
  //   not an exception to it: since T16's second rework round that prop is also gated on this
  //   same `isRagUnverified` and receives `undefined` (not the stale-but-last-confirmed
  //   `ragValue`) while this state is true -- see that prop's own [Caution] for the full history
  //   of why an earlier draft argued the opposite and was wrong (review Block, T16 2R High-1).
  //   `ChatPanel`'s own `ragEnabled?: boolean` default: since T14 second rework round's Medium-1,
  //   `ChatPanel` (and `MessageList` beneath it) no longer default `ragEnabled` to anything for an
  //   `undefined` value -- both now render neither the ON nor the OFF reading, matching this
  //   header's own "확인 중…" treatment instead of contradicting it.
  /**
   * [Purpose] Flips the selected conversation's conversation-scoped RAG toggle (card T5) by
   *   calling {@link updateRagEnabled} (card T1) with the negation of its current known value.
   * [Rationale] Reads the current value from `ragById`/`selectedId` at call time (not by closing
   *   over `ragValue` above) so this callback's dependency array can name `ragById` directly --
   *   it is only re-created when `ragById` or `selectedId` actually change. Applies the server's
   *   echoed `ragEnabled` (not the locally-computed negation) to `ragById`, and only after the
   *   PATCH resolves (chat-prototype-port:AC-10's "응답 성공 후" wording, no optimistic update
   *   per AD-6). T14 rework (see this component's top-of-file [Caution]): this function's own
   *   `setRagById` calls are the *only* place `ragEnabled` may change for an already-known
   *   conversation -- `list`-driven refetches (`refreshList`/`handleDelete`) never touch `ragById`
   *   for an id it already has, via `seedRagById`, so there is nothing left that can race this
   *   write. Card T16 rework (chat-prototype-port:AC-44, user-approved «3b», see this component's
   *   top-of-file [Caution] "T16 rework" paragraph): the failure path no longer deletes the
   *   `ragById` entry -- it stays exactly as last confirmed, and this id is instead added to
   *   `ragUnverified`, a persistent flag that survives any number of later list re-fetches (only a
   *   fresh page load clears it, per AC-44's own text) rather than being silently re-learned by
   *   the next one. This replaces four earlier rounds (T5 1R-4R, T14 1R-2R) of trying to make an
   *   automatic re-fetch-adopts-server-value recovery race-free -- see the Spec's own AC-44 row
   *   and its "T16 신설 근거" Tech Review section for why that direction was abandoned rather than
   *   attempted a fifth time.
   * [Caution] Three guards, in order, all load-bearing (1-2 from the T5 review round,
   *   High-1/High-2, carried forward unchanged by T14 and T16; 3 added by T16):
   *   1. `ragToggleInFlightRef.current` (High-1) -- must be the *first* line, checked before
   *      anything `async`, so a second click that fires before React re-renders from the first
   *      click's `setIsRagUpdating(true)` is still rejected. This is what actually prevents two
   *      overlapping PATCHes whose responses could resolve out of order and leave `aria-checked`
   *      showing the opposite of the server's real value.
   *   2. `selectedId === null` / `current === undefined` (High-2, now reading `ragById` instead
   *      of `list`) -- a defensive mirror of the button's own `disabled` condition below, not the
   *      primary guard against a disabled button firing `onClick` (it can't) -- kept so this
   *      function stays safe to call directly if that ever changes.
   *   3. `ragUnverified.has(selectedId)` (T16) -- the button is already `disabled` while this id
   *      is unverified, so this is not reachable through the UI either; kept for the same
   *      defensive reason as guard 2, and because a retry here would need a decision (retry the
   *      PATCH? require a page reload first?) AC-44 deliberately does not make -- refusing is the
   *      only choice that cannot be wrong.
   * [AC] chat-prototype-port:AC-9, chat-prototype-port:AC-10, chat-prototype-port:AC-44
   */
  const handleToggleRag = useCallback(async () => {
    if (ragToggleInFlightRef.current) return; // High-1 -- ignore a repeat click mid-request.
    if (selectedId === null) return; // Spec §8 D-1 ③ -- nothing to PATCH yet.
    if (ragUnverified.has(selectedId)) return; // T16 -- already unverified; only a reload clears it.
    const current = ragById[selectedId];
    if (current === undefined) return; // High-2 -- this conversation's value is not known yet.
    ragToggleInFlightRef.current = true;
    setIsRagUpdating(true);
    setActionError(null);
    try {
      const response = await updateRagEnabled(selectedId, !current);
      setRagById((prev) => ({ ...prev, [selectedId]: response.ragEnabled }));
      // T16 -- defensively clears any stale `ragUnverified` entry on success. Not reachable
      // through the UI today (a `ragUnverified` id's button is `disabled`, and guard 3 above
      // refuses a direct call too), but kept so this function's own invariant ("a successful PATCH
      // never leaves an id marked unverified") does not silently depend on the UI being the only
      // caller.
      setRagUnverified((prev) => {
        if (!prev.has(selectedId)) return prev;
        const next = new Set(prev);
        next.delete(selectedId);
        return next;
      });
    } catch (err: unknown) {
      setActionError(describeActionError(err, "문서 근거 설정을 변경하지 못했습니다"));
      // T16 rework (chat-prototype-port:AC-44, user-approved «3b») -- replaces the previous
      // "delete the `ragById` entry, fall back to the 미상/unknown state" recovery attempt (T14
      // second rework round's High-1). That approach still left an implicit race: *which* later
      // list re-fetch was allowed to re-seed this id first, and every attempt to make that
      // deterministic (an in-flight guard, a failed-refresh fallback, a generation counter, a
      // structural rewrite removing `list`'s write access entirely) kept finding a next window
      // where a *different* re-fetch could win (full history: Spec's own AC-9 row, `docs/review/
      // 2026-09-01_chat-prototype-port-T5*.md`). AC-44 (user-approved «3b») removes automatic
      // recovery instead of trying to race-proof it a fifth time: `ragById[selectedId]` is left
      // exactly as it was (the last confirmed server value -- not because `ChatPanel`'s own
      // `ragEnabled` prop below still needs it while unverified, it does not, see that prop's own
      // [Caution] -- but so a later re-fetch does not have to re-learn this id from scratch and
      // reopen the delete-then-relearn race this rework retired), and this id is added to
      // `ragUnverified` -- a flag no re-fetch site ever clears (see `ragUnverified`'s own
      // top-of-file declaration comment), so this
      // conversation's header toggle stays in the "확인 불가 · 새로고침" state (disabled, and
      // making no ON/OFF claim -- see `isRagUnverified`'s own [Caution] just above this function
      // for why even `aria-checked` is omitted here, not merely the label) until an actual page
      // reload.
      setRagUnverified((prev) => {
        if (prev.has(selectedId)) return prev; // already unverified -- avoid a redundant new Set.
        const next = new Set(prev);
        next.add(selectedId);
        return next;
      });
    } finally {
      ragToggleInFlightRef.current = false;
      setIsRagUpdating(false);
    }
  }, [ragById, ragUnverified, selectedId]);

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
        <header className={styles.header}>
          <h1 className={styles.title}>{headerTitle}</h1>
          <ModelSelector value={modelId} onChange={setModelId} />
          {/* chat-prototype-port:AC-9/10/11/42/44 -- RAG ON/OFF pill toggle (card T5, redesigned
              by T14, extended by T16). No `useState` copy: `role`/`aria-checked`, the
              `.ragToggleOn` class, and the label all read the derived values above (`ragValue`
              from `ragById[selectedId]`, `isRagUnverified` from `ragUnverified`, AD-6). Three
              mutually exclusive display states:
              - not yet known (`ragValue === undefined`, AC-42) -- `role="switch"`,
                `aria-checked="false"`, "확인 중…" label, `disabled`. `aria-checked="false"` is
                honest here because no server value has ever been claimed for this id yet -- see
                this component's top-of-file [Caution] for the full argument.
              - unverified after a failed PATCH (`isRagUnverified`, AC-44) -- `role`/`aria-checked`
                are omitted entirely (falls back to a plain disabled `<button>`, native role
                "button") rather than reusing the last-confirmed boolean, because that boolean may
                no longer match the server (the PATCH could have committed despite the response
                being lost) -- see `isRagUnverified`'s own [Caution] above for why this differs
                from the "not yet known" case. "확인 불가 · 새로고침" is the only text shown, and
                `disabled` (also enforced defensively by `handleToggleRag`'s own guard 3).
              - known and verified -- normal `role="switch"` + `aria-checked={ragValue}` +
                ON/OFF label, unchanged from T14.
              `disabled` overall covers "nothing to PATCH yet" (`selectedId === null`), "known but
              unresolved" (`ragValue === undefined`), "unverified" (`isRagUnverified`, T16), and "a
              PATCH from a previous click is still in flight" (`isRagUpdating` -- High-1, paired
              with `handleToggleRag`'s own `ragToggleInFlightRef` synchronous guard). */}
          <button
            type="button"
            role={isRagUnverified ? undefined : "switch"}
            aria-checked={isRagUnverified ? undefined : (ragValue ?? false)}
            disabled={selectedId === null || ragValue === undefined || isRagUpdating || isRagUnverified}
            onClick={() => void handleToggleRag()}
            className={`${styles.ragToggle} ${ragValue && !isRagUnverified ? styles.ragToggleOn : ""}`}
          >
            <span className={styles.ragToggleLabel}>
              {isRagUnverified ? (
                // AC-44 -- deliberately not the ON/OFF copy below, and deliberately not reusing
                // "확인 중…" either (that label is paired with `aria-checked="false"`, which is
                // honest for "never learned" but not for "learned, then a PATCH response was lost"
                // -- see this button's own comment above). "새로고침" tells the user the one way
                // out (AC-44's own text: only a page reload clears this state).
                <span className={styles.ragToggleTitle}>확인 불가 · 새로고침</span>
              ) : ragValue === undefined ? (
                // Deliberately not "ON"/"OFF" copy -- see this button's own comment above and
                // this component's top-of-file [Caution] for why claiming either would be wrong.
                <span className={styles.ragToggleTitle}>확인 중…</span>
              ) : (
                <>
                  <span className={styles.ragToggleTitle}>{ragValue ? "문서 근거 ON" : "문서 근거 OFF"}</span>
                  <span className={styles.ragToggleSubtitle}>
                    {ragValue ? "사내 문서에서 검색" : "모델 일반 지식만"}
                  </span>
                </>
              )}
            </span>
            {/* Decorative switch track/thumb (see `ChatWorkspace.module.css`'s `.ragKnob`) --
                the state is carried by `aria-checked` and the label text above, never by this
                graphic alone, so it is hidden from assistive tech. */}
            <span className={styles.ragKnob} aria-hidden="true" />
          </button>
        </header>
        {isLoadingHistory && (
          // chat-prototype-port:AC-41 -- 4-line shimmer skeleton (`ic-shimmer`, P:167-171) next
          // to the loading status text (unified copy, chat-prototype-port:AC-7 partial). Widths
          // are the prototype's own per-line values (38%/92%/84%/64%, P:168-171), not invented.
          <div className={styles.historyLoading}>
            <div className={`${styles.skeletonLine} ${styles.skeletonLine1}`} data-testid="history-skeleton-line" />
            <div className={`${styles.skeletonLine} ${styles.skeletonLine2}`} data-testid="history-skeleton-line" />
            <div className={`${styles.skeletonLine} ${styles.skeletonLine3}`} data-testid="history-skeleton-line" />
            <div className={`${styles.skeletonLine} ${styles.skeletonLine4}`} data-testid="history-skeleton-line" />
            <p role="status" className={styles.historyLoadingText}>
              대화를 불러오는 중…
            </p>
          </div>
        )}
        {initialSelectionResolved ? (
          <ChatPanel
            key={selectedId ?? NEW_CONVERSATION_KEY}
            conversationId={selectedId ?? undefined}
            initialMessages={initialMessages}
            model={modelId ?? undefined}
            // chat-prototype-port:AC-24/AC-25 (card T6) -- same "single owner, derive on
            // render" shape AD-6 already established for the RAG toggle button above: no
            // separate `useState` copy, so this can never drift from `ragById`. Passes `ragValue`
            // through (`boolean | undefined`) -- `ChatPanel` (and `MessageList` beneath it)
            // itself has no default for `undefined` either, since T14 second rework round's
            // Medium-1 (correcting an earlier, inaccurate version of this comment that still
            // described a `?? true` default `ChatPanel` no longer has).
            // [Caution] (card T16, 2R) Gated on `isRagUnverified`: while a conversation's toggle
            // is in the "확인 불가" state, this prop is forced to `undefined` rather than passing
            // the stale-but-last-confirmed `ragValue` through. An earlier version of this comment
            // argued this was a deliberate scope boundary (AC-44 only regulates the header
            // toggle) and left `ragEnabled={ragValue}` ungated -- that was wrong: it let
            // `ChatPanel`'s composer hint, `MessageList`'s TopBadge, and its RAG-OFF notice box
            // all keep asserting the last-confirmed ON/OFF value as fact in the same render where
            // the header honestly says "확인 불가" (review Block, T16 2R High-1). `ChatPanel`/
            // `MessageList` already treat `undefined` as "don't know" (T14 2R Medium-1), so no
            // other file needed to change.
            ragEnabled={isRagUnverified ? undefined : ragValue}
            onConversationCreated={handleConversationCreated}
          />
        ) : (
          // Mount-effect decision (adopt vs. auto-create) is still pending -- see this
          // component's [Caution] for why `<ChatPanel>` must not render yet. Suppressed while
          // `isLoadingHistory`'s own status text above is already announcing the same wait
          // (the adopt-branch case), so only one live region is ever visible at a time.
          // Text unified to the same `대화를 불러오는 중…` copy the skeleton block above and
          // `ConversationSidebar` already use (chat-prototype-port:AC-7's final all-clear,
          // card T8) -- this is the same "wait for a decision" state, just without the
          // skeleton's own history payload to shimmer for yet.
          !isLoadingHistory && (
            <p role="status" className={styles.pendingStatus}>
              대화를 불러오는 중…
            </p>
          )
        )}
      </div>
    </div>
  );
}

/**
 * [Purpose] Looks up the conversation `selectedId` refers to within `list`, if the list is
 *   `"ready"` and `selectedId` is actually in it.
 * [Rationale] Factored out of `ChatWorkspace` (T5 review round maintainability follow-up,
 *   `docs/review/2026-09-01_chat-prototype-port-T5.md`) for `headerTitle`'s render derivation.
 *   T14 rework: no longer used for anything `ragEnabled`-related (`ragById` replaced that
 *   entirely, see `ChatWorkspace`'s own top-of-file [Caution]) -- kept solely for `list`'s
 *   remaining display responsibilities (title, and whatever else derives from the selected
 *   conversation's non-`ragEnabled` fields).
 * [Caution] `undefined` is a real, expected return value (list not `"ready"` yet, or `selectedId`
 *   not present in it), not an error -- every call site must handle it, not throw on it.
 */
function findSelectedConversation(
  list: ConversationListState,
  selectedId: string | null,
): ConversationSummaryResponse | undefined {
  return list.status === "ready" ? list.conversations.find((c) => c.id === selectedId) : undefined;
}

/**
 * [Purpose] Adds a `ragEnabled` entry to `prev` (a `ragById`-shaped map) for every conversation
 *   in `fresh` that `prev` does not already have an entry for. Never overwrites an id `prev`
 *   already knows, and never removes an id `prev` knows that `fresh` happens to omit.
 * [Rationale] Card T14 (`BL-032`) -- see `ChatWorkspace`'s own top-of-file [Caution], "T14
 *   rework" paragraph, for the full origin. Every list-learning call site (the mount effect's own
 *   first fetch, `refreshList`, `handleDelete`'s post-delete refetch) routes its response through
 *   this function instead of writing to `ragById` directly, so `ragEnabled` has exactly one place
 *   it can *change* for an already-known conversation: `handleToggleRag`'s own `setRagById` call
 *   after its PATCH resolves (and `handleConversationCreated`'s synchronous seed, for a
 *   conversation's very first appearance). Because this function can only *add*, never overwrite
 *   or delete, it closes both axes review round 4 found still open in the previous
 *   (`mergeConversationList`) attempt: a stale response carrying a *different value* for a known
 *   id cannot win (the id is already present, so the incoming value is dropped, not merged-and-
 *   compared) and a stale or partial response that *omits a row entirely* for a known id cannot
 *   erase it either (there was never a write path from "row absent" to "entry removed" here in
 *   the first place -- unlike `mergeConversationList`, which rebuilt its output from `fresh.map`
 *   and so silently produced no entry at all for an id `fresh` did not list).
 * [Caution] `prev` must be the value read from `setRagById`'s own functional updater (always the
 *   true latest state), not a value closed over earlier -- passing a stale `prev` would risk
 *   treating an id as "not yet known" when it actually already was, and seed a possibly-stale
 *   `fresh` value over what should have been left alone. See `ChatWorkspace`'s top-of-file
 *   [Caution] "T14 rework" paragraph's own cross-tab/device caveat: this function is exactly why
 *   a `ragEnabled` change made outside this screen (a different tab/device) to a conversation this
 *   screen already knows about is not picked up by a refetch here -- only a conversation genuinely
 *   new to this screen gets `fresh`'s value at all.
 */
function seedRagById(
  prev: Record<string, boolean>,
  fresh: ConversationSummaryResponse[],
): Record<string, boolean> {
  const next = { ...prev };
  for (const c of fresh) {
    if (!(c.id in next)) next[c.id] = c.ragEnabled;
  }
  return next;
}

/** Maps `GET .../messages`'s wire shape to `MessageList`'s UI-level `ChatMessage` -- adds the
 *  client-only `id` React key `ChatMessage` requires (see its own [Caution]) and marks every
 *  assistant row `"done"` (history is, by definition, never mid-stream).
 *  `sources` (2026-09-01): an assistant row's persisted citations are threaded through as-is when
 *  the backend sent an array, so a reload shows the same 출처 chips the live stream did. A `null`/
 *  missing value becomes `undefined`, *not* `null` -- `SourceList`'s clause ③ reads a `null` on a
 *  finished message as "the stream ended without ever delivering sources" (BL-019) and renders
 *  "출처를 불러오지 못했습니다.", which would be a false failure for a history row that simply has
 *  no citations (a RAG-off turn, or one recorded before citations were persisted). */
function toChatMessages(responses: MessageResponse[]): ChatMessage[] {
  return responses.map((message, index) => ({
    id: `history-${index}`,
    role: message.role,
    content: message.content,
    ...(message.role === "assistant"
      ? {
          streamStatus: "done" as const,
          sources: Array.isArray(message.sources) ? message.sources : undefined,
        }
      : {}),
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
