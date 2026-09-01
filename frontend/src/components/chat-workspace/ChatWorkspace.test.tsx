import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ChatWorkspace } from "./ChatWorkspace";
import { ApiError } from "@/lib/api/client";
import {
  createConversation,
  deleteConversation,
  listConversations,
  updateRagEnabled,
  type ConversationRagEnabledResponse,
  type ConversationSummaryResponse,
} from "@/lib/api/conversations";
import { listModels } from "@/lib/api/models";
import { getMessages, streamAssistantReply, type ChatChunk, type MessageResponse } from "@/lib/api/messages";

/**
 * Integration-level tests: only the network boundary (`lib/api/*`) is mocked, exactly like
 * `chat-panel/ChatPanel.test.tsx` mocks `lib/api/conversations`/`lib/api/messages` while
 * rendering the real component tree -- `ConversationSidebar`, `ModelSelector`, and `ChatPanel`
 * itself are all real here, so these tests exercise the actual wiring between them, not a
 * re-mock of it.
 */
jest.mock("@/lib/api/conversations");
jest.mock("@/lib/api/models");
jest.mock("@/lib/api/messages", () => ({
  ...jest.requireActual("@/lib/api/messages"),
  streamAssistantReply: jest.fn(),
  getMessages: jest.fn(),
}));

const mockedCreateConversation = createConversation as jest.MockedFunction<typeof createConversation>;
const mockedListConversations = listConversations as jest.MockedFunction<typeof listConversations>;
const mockedDeleteConversation = deleteConversation as jest.MockedFunction<typeof deleteConversation>;
const mockedUpdateRagEnabled = updateRagEnabled as jest.MockedFunction<typeof updateRagEnabled>;
const mockedListModels = listModels as jest.MockedFunction<typeof listModels>;
const mockedGetMessages = getMessages as jest.MockedFunction<typeof getMessages>;
const mockedStreamAssistantReply = streamAssistantReply as jest.MockedFunction<typeof streamAssistantReply>;

const NEW_CONV = {
  id: "new-conv",
  title: null,
  model: "upstage/solar-pro4",
  ragEnabled: false,
  createdAt: "2026-08-28T00:00:00Z",
  updatedAt: "2026-08-28T00:00:00Z",
};
const NEW_CONV_SUMMARY = {
  id: NEW_CONV.id,
  title: null,
  model: NEW_CONV.model,
  ragEnabled: false,
  updatedAt: NEW_CONV.updatedAt,
};
const CONV_B = { id: "conv-b", title: "old chat", model: "upstage/solar-pro4", ragEnabled: false, updatedAt: "2026-08-20T00:00:00Z" };
// review 2026-08-29 round-2 High-2: a second, distinct conversation so a mount-time adoption
// (`conversations[0]`) and a user's click land on different ids. With only CONV_B present,
// mount already adopted it, so a click on "old chat" was a no-op `handleSelect` short-circuited
// on its own `if (id === selectedId) return` -- every assertion in the old test was already
// satisfied before the click ever happened.
const CONV_A = { id: "conv-a", title: "recent chat", model: "upstage/solar-pro4", ragEnabled: false, updatedAt: "2026-08-27T00:00:00Z" };
// chat-prototype-port:AC-8 -- a conversation whose first message has never been sent
// (`title: null`, `ConversationResponse`'s own [Caution]) so the header's `새 대화` fallback has
// a real (not auto-created) conversation to render it for.
const UNTITLED_CONV = {
  id: "conv-untitled",
  title: null,
  model: "upstage/solar-pro4",
  ragEnabled: false,
  updatedAt: "2026-08-19T00:00:00Z",
};
const CATALOG = [
  { id: "upstage/solar-pro4", displayName: "Solar Pro 4", default: true },
  { id: "other/model", displayName: "Other Model", default: false },
];

/** A single-chunk generator, matching ChatPanel.test.tsx's own helper -- not shared across
 *  files (each test file is self-contained, project convention). */
async function* singleChunkReply(chunks: ChatChunk[]): AsyncGenerator<ChatChunk, void, void> {
  for (const chunk of chunks) yield chunk;
}

/** An externally-resolvable promise -- lets a test hold `getMessages` "in flight" for as long
 *  as it needs to assert on the loading state (chat-prototype-port:AC-41's skeleton) before
 *  letting it resolve, instead of racing a fixed mock resolution against `waitFor`. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/**
 * [Caution] Re-queries `getByRole` on every poll instead of capturing one node up front --
 * `ChatWorkspace` intentionally remounts `<ChatPanel>` under a new `key` once its auto-create
 * resolves (see `ChatWorkspace`'s own [Rationale]), which detaches the very node a one-time
 * `findByRole` would have captured before it ever reflects "ready" (a detached jsdom node's
 * attributes are frozen at whatever they were at the moment of removal). Re-querying makes
 * this helper follow whichever instance is actually in the document at each poll.
 */
async function waitForComposerReady() {
  await waitFor(() => expect(screen.getByRole("textbox")).toBeEnabled());
  return screen.getByRole("textbox");
}

describe("ChatWorkspace", () => {
  beforeEach(() => {
    mockedCreateConversation.mockReset().mockResolvedValue(NEW_CONV);
    mockedListConversations.mockReset().mockResolvedValue([]);
    mockedDeleteConversation.mockReset().mockResolvedValue(undefined);
    mockedUpdateRagEnabled.mockReset();
    mockedListModels.mockReset().mockResolvedValue(CATALOG);
    mockedGetMessages.mockReset();
    mockedStreamAssistantReply.mockReset();
  });

  // Spec §8 AD-3: AC-1's h1/h2-absence clauses are proven from `page.test.tsx` (the only file
  // that actually renders `page.tsx`, which is where those elements could exist) -- this test
  // owns only the third clause, the `aside`/main sibling structure, which is this component's
  // own root, not `page.tsx`'s.
  it("[chat-prototype-port:AC-1] renders the sidebar aside and the main content area as siblings", async () => {
    render(<ChatWorkspace />);
    await waitForComposerReady();

    // review 2026-09-01 Low-1: named via its accessible name (ConversationSidebar.tsx's
    // `aria-label="대화 목록"`) rather than a bare `document.querySelector("aside")`, so this
    // also specifically identifies *the* conversation sidebar, not just any `<aside>`.
    const aside = screen.getByRole("complementary", { name: "대화 목록" });
    expect(aside).not.toBeNull();
    // "siblings" means the same parent, not merely "both present somewhere in the tree" -- a
    // main region nested inside the aside (or vice versa) would still pass a weaker
    // `document.querySelector` check for each element individually.
    const main = aside.nextElementSibling;
    expect(main).not.toBeNull();
    expect(main?.parentElement).toBe(aside.parentElement);
    // Guards against a false positive where `main` above is actually another `<aside>` (e.g. a
    // second sidebar) rather than the real content area. review 2026-09-01 Medium-2: anchored on
    // the Composer's `textarea` (`ChatPanel.tsx`) instead of the model selector's
    // `[role="combobox"], select` -- T6 (Spec §4 AC-30) replaces the native `<select>` with a
    // `role="listbox"` popover, which would silently make the old selector match nothing and
    // this guard would stop guarding without failing. The Composer textarea is not scheduled to
    // change shape, so it survives T6 as an anchor unique to the content area.
    expect(main?.querySelector("textarea")).not.toBeNull();
  });

  // Scoped with `getByRole("heading", { level: 1 })` rather than a text match -- "새 대화" is
  // also the new-conversation button's accessible name and an untitled conversation's own
  // sidebar list-item label (`ConversationSidebar`'s own [Caution]), so a bare text query would
  // be ambiguous the moment any untitled conversation exists in the list. The `h1` role is
  // unique to this header (grep confirms no other `<h1>`/`<h2>`/`<h3>` renders anywhere in
  // `ChatPanel`/`ConversationSidebar`/`ModelSelector`).
  it("[chat-prototype-port:AC-8] renders the selected conversation's title as an h1, and swaps it on selection", async () => {
    mockedListConversations.mockResolvedValue([CONV_A, CONV_B]);
    mockedGetMessages.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();

    // Mount adopts `conversations[0]` (CONV_A) -- its non-null title renders verbatim.
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("recent chat");

    await user.click(screen.getByRole("button", { name: "old chat" }));

    await waitFor(() => expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("old chat"));
  });

  it("[chat-prototype-port:AC-8] falls back to '새 대화' for a conversation whose title is null", async () => {
    mockedListConversations.mockResolvedValue([UNTITLED_CONV]);
    mockedGetMessages.mockResolvedValue([]);
    render(<ChatWorkspace />);
    await waitForComposerReady();

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("새 대화");
  });

  it("[chat-prototype-port:AC-41] renders 4 history skeleton lines with the unified loading text while loading, and 0 once it finishes", async () => {
    mockedListConversations.mockResolvedValue([CONV_A, CONV_B]);
    const pending = deferred<MessageResponse[]>();
    mockedGetMessages
      .mockResolvedValueOnce([]) // mount's adoption fetch for CONV_A resolves immediately
      .mockImplementationOnce(() => pending.promise); // the click below stays "loading" until resolved
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    expect(screen.queryAllByTestId("history-skeleton-line")).toHaveLength(0);

    await user.click(screen.getByRole("button", { name: "old chat" }));

    expect(await screen.findAllByTestId("history-skeleton-line")).toHaveLength(4);
    // Char-for-char the unified string (chat-prototype-port:AC-7 partial) -- an ellipsis
    // character `…`, not three periods, and the "를" particle.
    expect(screen.getByText("대화를 불러오는 중…")).toBeInTheDocument();

    pending.resolve([]);

    await waitFor(() => expect(screen.queryAllByTestId("history-skeleton-line")).toHaveLength(0));
  });

  // Card T8 -- the brief pre-adoption-decision window (`initialSelectionResolved === false`,
  // `isLoadingHistory === false`) is the one loading-copy site AC-41's own skeleton test above
  // does not cover: it renders *before* the mount effect's `listConversations()` call has even
  // resolved, so there is no `[]`/non-empty branch decision yet to gate a skeleton on. This was
  // the component's own literal `"대화 준비 중..."` string prior to T8; asserting the unified
  // copy here is this card's own share of chat-prototype-port:AC-7's final all-clear (the other
  // two loading sites -- `ConversationSidebar` and this file's own AC-41 skeleton text -- were
  // already unified by T3/T4).
  it("[chat-prototype-port:AC-7] shows the unified loading copy during the pre-adoption-decision window, before it resolves into either the skeleton or the composer", async () => {
    const pendingList = deferred<ConversationSummaryResponse[]>();
    mockedListConversations.mockReturnValue(pendingList.promise);
    render(<ChatWorkspace />);

    // Two elements at this exact moment, not one -- `ConversationSidebar`'s own `list.status
    // === "loading"` copy (unified by T3, before `listConversations` resolves) renders
    // simultaneously with this file's own pre-adoption-decision placeholder. Both must already
    // be the unified string; `getAllByText` (not `getByRole("status")`) is deliberate here so a
    // regression in either site fails this assertion, not just one of the two.
    expect(screen.getAllByText("대화를 불러오는 중…")).toHaveLength(2);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();

    pendingList.resolve([]);

    await waitForComposerReady();
    expect(screen.queryByText("대화를 불러오는 중…")).not.toBeInTheDocument();
  });

  /** All three retired loading-copy variants the unified `대화를 불러오는 중…` string replaced --
   *  see this file's own AC-7 test [Caution] for why the whole-screen check below must query
   *  for these literally instead of trusting that "the new string is present" implies "the old
   *  ones are gone". */
  const RETIRED_LOADING_VARIANTS = ["대화 목록을 불러오는 중...", "대화 불러오는 중...", "대화 준비 중..."];

  // Spec §8 AD-7 -- a component-scoped test cannot prove a screen-wide "0 occurrences" claim by
  // construction (it never renders the sibling components where a stray copy of the string could
  // still live). This test renders the actual composition root (`ChatWorkspace`, the same tree
  // `page.tsx` mounts) and checks across the *whole* rendered screen, at every loading
  // checkpoint T8 touches (pre-adoption-decision window, history-skeleton window, and the
  // settled/composer-ready state), that none of the three retired variants appears anywhere --
  // not just that the unified string is present, which the tests above already established but
  // which alone would not catch a leftover, unrelated dead string sitting next to it.
  it("[chat-prototype-port:AC-7] the three retired loading-copy variants are 0 occurrences anywhere on screen, at every loading checkpoint", async () => {
    mockedListConversations.mockResolvedValueOnce([CONV_A, CONV_B]); // mount: adopts CONV_A
    const pendingHistory = deferred<MessageResponse[]>();
    mockedGetMessages.mockReturnValueOnce(pendingHistory.promise);
    render(<ChatWorkspace />);

    // Checkpoint 1: pre-adoption-decision window (`listConversations` has not resolved... but
    // it already has here since it's not deferred -- this render call is still synchronous with
    // React's first commit, so the very first paint is still the pending-placeholder branch).
    for (const variant of RETIRED_LOADING_VARIANTS) {
      expect(screen.queryByText(variant)).not.toBeInTheDocument();
    }

    // Checkpoint 2: history-skeleton window (adoption decided, `getMessages` still pending).
    await screen.findAllByTestId("history-skeleton-line");
    for (const variant of RETIRED_LOADING_VARIANTS) {
      expect(screen.queryByText(variant)).not.toBeInTheDocument();
    }

    pendingHistory.resolve([]);

    // Checkpoint 3: settled, composer ready.
    await waitForComposerReady();
    for (const variant of RETIRED_LOADING_VARIANTS) {
      expect(screen.queryByText(variant)).not.toBeInTheDocument();
    }
  });

  it("[chat-prototype-port:AC-9] the RAG toggle is role=switch and aria-checked matches the selected conversation's ragEnabled, flipping on selection", async () => {
    const convOn = { ...CONV_A, ragEnabled: true };
    const convOff = { ...CONV_B, ragEnabled: false };
    mockedListConversations.mockResolvedValue([convOn, convOff]);
    mockedGetMessages.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();

    // Mount adopts conversations[0] (convOn).
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "true");

    await user.click(screen.getByRole("button", { name: "old chat" }));

    await waitFor(() => expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "false"));
  });

  it("[chat-prototype-port:AC-11] RAG toggle labels are the exact ON/OFF copy, char for char, and flip together with aria-checked", async () => {
    const convOn = { ...CONV_A, ragEnabled: true };
    const convOff = { ...CONV_B, ragEnabled: false };
    mockedListConversations.mockResolvedValue([convOn, convOff]);
    mockedGetMessages.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();

    // review 2026-09-01 Low-2: scoped to the toggle itself (`within(getByRole("switch"))`)
    // rather than the whole document -- an unscoped `screen.getByText` would also match an
    // unrelated element elsewhere on screen carrying the same string by coincidence, and
    // wouldn't catch a regression where these labels leaked outside the switch or a second
    // stray copy appeared next to it.
    const toggle = screen.getByRole("switch");
    expect(within(toggle).getByText("문서 근거 ON")).toBeInTheDocument();
    expect(within(toggle).getByText("사내 문서에서 검색")).toBeInTheDocument();
    expect(within(toggle).queryByText("문서 근거 OFF")).not.toBeInTheDocument();
    expect(within(toggle).queryByText("모델 일반 지식만")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "old chat" }));

    await waitFor(() => expect(within(toggle).getByText("문서 근거 OFF")).toBeInTheDocument());
    expect(within(toggle).getByText("모델 일반 지식만")).toBeInTheDocument();
    expect(within(toggle).queryByText("문서 근거 ON")).not.toBeInTheDocument();
    expect(within(toggle).queryByText("사내 문서에서 검색")).not.toBeInTheDocument();
  });

  it("[chat-prototype-port:AC-10] clicking the RAG toggle PATCHes exactly once with the negated value, and aria-checked flips only after the response resolves", async () => {
    const conv = { ...CONV_A, ragEnabled: true };
    mockedListConversations.mockResolvedValue([conv]);
    mockedGetMessages.mockResolvedValue([]);
    const pending = deferred<ConversationRagEnabledResponse>();
    mockedUpdateRagEnabled.mockReturnValue(pending.promise);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    const toggle = screen.getByRole("switch");
    expect(toggle).toHaveAttribute("aria-checked", "true");

    await user.click(toggle);

    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);
    expect(mockedUpdateRagEnabled).toHaveBeenCalledWith("conv-a", false);
    // Not flipped yet -- AD-6 forbids an optimistic update; the PATCH is still pending.
    expect(toggle).toHaveAttribute("aria-checked", "true");

    pending.resolve({ id: "conv-a", ragEnabled: false });

    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "false"));
    // Still exactly once after settling -- resolving the promise must not trigger a second call.
    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);
  });

  // T5 review round High-3: the test above only ever drove ON->OFF, so a hardcoded
  // `updateRagEnabled(selectedId, false)` would have passed it too. This is the symmetric
  // OFF->ON case, placed right next to it so the pair together proves the call argument is the
  // actual negation of the current value, not a constant.
  it("[chat-prototype-port:AC-10] clicking the RAG toggle when it starts OFF PATCHes with true, and aria-checked flips to true only after the response resolves", async () => {
    const conv = { ...CONV_A, ragEnabled: false };
    mockedListConversations.mockResolvedValue([conv]);
    mockedGetMessages.mockResolvedValue([]);
    const pending = deferred<ConversationRagEnabledResponse>();
    mockedUpdateRagEnabled.mockReturnValue(pending.promise);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    const toggle = screen.getByRole("switch");
    expect(toggle).toHaveAttribute("aria-checked", "false");

    await user.click(toggle);

    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);
    expect(mockedUpdateRagEnabled).toHaveBeenCalledWith("conv-a", true);
    // Not flipped yet -- AD-6 forbids an optimistic update; the PATCH is still pending.
    expect(toggle).toHaveAttribute("aria-checked", "false");

    pending.resolve({ id: "conv-a", ragEnabled: true });

    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));
    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);
  });

  // T5 review round High-1: `handleToggleRag`'s `ragToggleInFlightRef` guard exists specifically
  // for a second click that lands before React has committed the first click's
  // `setIsRagUpdating(true)` (and the `disabled` attribute it drives) -- a plain sequential
  // `await user.click(); await user.click();` would let that commit happen in between and never
  // actually exercise the race, since a real `disabled` button blocks a second native click on
  // its own. Firing both clicks inside one `act()` keeps React's commit deferred across both, so
  // the second click still lands on a not-yet-disabled DOM node -- the ref (checked synchronously
  // as `handleToggleRag`'s very first line, before either `setState` call) is what has to stop it.
  it("[chat-prototype-port:AC-10] a rapid second click while the first PATCH is still in flight does not fire a second updateRagEnabled call", async () => {
    const conv = { ...CONV_A, ragEnabled: true };
    mockedListConversations.mockResolvedValue([conv]);
    mockedGetMessages.mockResolvedValue([]);
    const pending = deferred<ConversationRagEnabledResponse>();
    mockedUpdateRagEnabled.mockReturnValue(pending.promise);
    render(<ChatWorkspace />);
    await waitForComposerReady();
    const toggle = screen.getByRole("switch");

    act(() => {
      fireEvent.click(toggle);
      fireEvent.click(toggle);
    });

    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);
    expect(mockedUpdateRagEnabled).toHaveBeenCalledWith("conv-a", false);

    await act(async () => {
      pending.resolve({ id: "conv-a", ragEnabled: false });
      await pending.promise;
    });

    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "false"));
    // Still exactly once after settling -- resolving the promise must not trigger a second call.
    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);

    // 2R (2nd review round) Medium-1: the pair of assertions above only prove the *guard*
    // engages while a request is in flight -- deleting `ragToggleInFlightRef.current = false;`
    // from `handleToggleRag`'s `finally` block (a permanent lock, never released) would still
    // pass everything up to this point, since there has been no second click *after* the first
    // one settled. Firing a genuine second click here, once `pending` has resolved and React has
    // committed the settled state, is what actually exercises the release path.
    const pending2 = deferred<ConversationRagEnabledResponse>();
    mockedUpdateRagEnabled.mockReturnValueOnce(pending2.promise);

    fireEvent.click(toggle);

    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(2);
    // Negation of the now-settled value (false -> true), not a repeat of the first call's args --
    // proves this is a real second PATCH, not e.g. a stale closure re-sending the first request.
    expect(mockedUpdateRagEnabled).toHaveBeenNthCalledWith(2, "conv-a", true);

    await act(async () => {
      pending2.resolve({ id: "conv-a", ragEnabled: true });
      await pending2.promise;
    });

    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));
    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(2);
  });

  // Body replaced by card T16 (chat-prototype-port:AC-44, user-approved «3b») -- the previous
  // version of this test asserted a revert to AC-42's "unknown"/확인 중… state (T14's second
  // rework round, High-1). AC-44's approved revision replaces that automatic-recovery design
  // entirely (see `handleToggleRag`'s own `catch` block [Caution] for the full rationale) with a
  // separate, persistent "unverifiable" state: no `role="switch"`/`aria-checked` at all (a plain
  // disabled `<button>`), so no ON/OFF boolean is asserted to assistive tech -- not even the
  // stale-but-last-confirmed one. Test name and body both updated to match.
  it("shows the existing actionError banner (no new error surface) and moves the toggle to the unverifiable disabled state -- with no switch role or aria-checked exposed at all -- rather than the stale pre-click boolean, when the RAG toggle PATCH fails", async () => {
    const conv = { ...CONV_A, ragEnabled: true };
    mockedListConversations.mockResolvedValue([conv]);
    mockedGetMessages.mockResolvedValue([]);
    mockedUpdateRagEnabled.mockRejectedValue(new ApiError(500, "boom"));
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    const toggle = screen.getByRole("switch");
    expect(toggle).toHaveAttribute("aria-checked", "true");

    await user.click(toggle);

    expect(await screen.findByRole("alert")).toHaveTextContent("문서 근거 설정을 변경하지 못했습니다");
    // T16 (security correction to this card's own first design draft, 2026-09-01/02): a failed
    // PATCH no longer deletes `ragById`'s entry or falls back to AC-42's known-boolean-free but
    // `aria-checked="false"`-bearing "확인 중…" state -- that would still assert a definite "off"
    // to a screen reader while the server's real value may actually be "on" (the request could
    // have committed even though its response was lost). Instead `role="switch"`/`aria-checked`
    // are dropped from the DOM entirely, so `getByRole("switch")` no longer finds this element at
    // all; it is queried as an ordinary disabled `<button>` by its visible/accessible name below.
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    const unverifiedToggle = screen.getByRole("button", { name: "확인 불가 · 새로고침" });
    expect(unverifiedToggle).toBeDisabled();
    expect(unverifiedToggle).not.toHaveAttribute("aria-checked");
    expect(screen.queryByText("확인 중…")).not.toBeInTheDocument();
    expect(screen.queryByText("문서 근거 ON")).not.toBeInTheDocument();
    expect(screen.queryByText("문서 근거 OFF")).not.toBeInTheDocument();
  });

  // chat-prototype-port:AC-44 (revised 2026-09-01, user-approved «3b», card T16) -- this AC used
  // to require the *opposite* of what it asserts now: an early draft had "the next list re-fetch
  // adopts the server's real value" as its own body. That direction was abandoned after five
  // rounds (T5 1R-4R, T14 1R-2R) of finding a new race in whichever re-fetch was allowed to win
  // that adoption (full history: Spec's own AC-9 row). The approved revision instead requires the
  // *absence* of any automatic recovery -- "이후 목록 재조회가 그 대화의 RAG 값을 바꾸지 않으며,
  // 페이지를 다시 불러와야 해제된다" -- so this test's whole point is proving a negative across
  // *multiple* re-fetches, not just checking the state once right after the failure. A test that
  // only checked immediately after the failed PATCH could pass even if the very next re-fetch
  // silently cleared the state again; this test keeps probing after two separate re-fetches
  // (deleting two different other conversations, `CONV_B` then `convC`, without ever touching the
  // selected `convA`), the second one deliberately held pending and resolved only after its own
  // "still unverifiable" assertion, so a delayed response landing later cannot clear it either.
  it("[chat-prototype-port:AC-44] a failed RAG toggle PATCH leaves the toggle unverifiable, and it does not clear across two later list re-fetches (one of them delivered late) -- only a fresh page load would", async () => {
    const convA = { ...CONV_A, ragEnabled: true }; // selected, starts ON
    const convC = { id: "conv-c", title: "third chat", model: "upstage/solar-pro4", ragEnabled: false, updatedAt: "2026-08-15T00:00:00Z" };
    const pendingSecondRefetch = deferred<ConversationSummaryResponse[]>();
    mockedListConversations
      .mockResolvedValueOnce([convA, CONV_B, convC]) // mount: adopts convA (conversations[0])
      // handleDelete #1 (deletes conv-b) post-delete refetch -- deliberately reports a
      // *different* ragEnabled for convA than its pre-click `true`, so "does this response get
      // silently adopted" is an observable, not a coincidental pass.
      .mockResolvedValueOnce([{ ...convA, ragEnabled: false }, convC])
      // handleDelete #2 (deletes conv-c) post-delete refetch -- held pending on purpose; resolved
      // later, after this test has already asserted "still unverifiable" while it is in flight.
      .mockReturnValueOnce(pendingSecondRefetch.promise);
    mockedGetMessages.mockResolvedValue([]);
    mockedDeleteConversation.mockResolvedValue(undefined);
    mockedUpdateRagEnabled.mockRejectedValue(new ApiError(500, "boom"));
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    const toggle = screen.getByRole("switch");
    expect(toggle).toHaveAttribute("aria-checked", "true");

    await user.click(toggle);
    expect(await screen.findByRole("alert")).toHaveTextContent("문서 근거 설정을 변경하지 못했습니다");

    // Right after the failed PATCH: unverifiable -- no switch role/aria-checked exposed at all.
    // Whole-document substring scan (T16 2R, promoted from an exact-text `queryByText` scoped to
    // the header toggle only -- see this file's AC-42 test above for the same promotion pattern
    // and why an exact-match query has a blind spot). Before T16 2R High-1's fix, `ChatWorkspace`
    // kept passing the header's own last-confirmed `ragValue` straight through to `ChatPanel` even
    // while `isRagUnverified` was true, so `ChatPanel`'s composer hint, `MessageList`'s TopBadge,
    // and its RAG-OFF notice box all kept asserting "ON" as fact in the very same render where the
    // header honestly said "확인 불가" -- an exact-text query scoped to the header toggle alone
    // could not see that (confirmed empirically: reverting the `ChatWorkspace.tsx` gating fix while
    // keeping only the exact-text version of these two lines still passed). `getByRole("button",
    // { name: "확인 불가 · 새로고침" })`'s exact accessible-name match still proves the header
    // toggle's own text is neither ON/OFF copy nor anything else; the two scan lines below now
    // additionally cover every other surface fed by the same `ragEnabled` prop.
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "확인 불가 · 새로고침" })).toBeDisabled();
    expect(document.body.textContent).not.toMatch(/문서 근거 ON/);
    expect(document.body.textContent).not.toMatch(/문서 근거 OFF/);

    // Re-fetch #1: deletes the *other* conversation (conv-b) so `handleDelete`'s post-delete
    // `listConversations()` refetch fires without moving `selectedId` off convA.
    await user.click(screen.getByRole("button", { name: "old chat 삭제" }));
    await waitFor(() => expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-b"));

    // This is the AC's own body: still unverifiable after re-fetch #1 has fully resolved, even
    // though that response reported a real (different) ragEnabled for convA -- a version of the
    // code that let any re-fetch re-seed an unverified id would fail this specific assertion. The
    // whole-document scan (Low-2, T16 2R) observes this directly *after* the re-fetch, not merely
    // before-and-after the original PATCH failure -- a regression that only cleared `ragUnverified`
    // (and therefore un-gated the `ragEnabled` prop again) on a re-fetch specifically, rather than
    // never clearing it at all, would be invisible to a check that only ran right after the PATCH.
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "확인 불가 · 새로고침" })).toBeDisabled();
    expect(document.body.textContent).not.toMatch(/문서 근거 ON/);
    expect(document.body.textContent).not.toMatch(/문서 근거 OFF/);

    // Re-fetch #2: deletes the last other conversation (conv-c) -- its own list refetch is
    // dispatched but held pending below, so "still unverifiable while a second refetch is
    // in-flight" and "still unverifiable after it resolves" are both asserted separately.
    await user.click(screen.getByRole("button", { name: "third chat 삭제" }));
    await waitFor(() => expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-c"));
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "확인 불가 · 새로고침" })).toBeDisabled();

    await act(async () => {
      pendingSecondRefetch.resolve([{ ...convA, ragEnabled: false }]);
      await pendingSecondRefetch.promise;
    });

    // Still unverifiable after a second, delayed re-fetch resolves too -- proving this state has
    // no expiry of its own short of an actual page reload (a fresh mount, not exercised further
    // in a unit test), not merely "the very next re-fetch is ignored." Whole-document scan again
    // (T16 2R, promoted alongside the first assertion block above -- same rationale: an exact-text
    // query scoped to the header toggle cannot see `ChatPanel`/`MessageList`'s surfaces, which this
    // AC's re-fetch clause also governs now that the `ragEnabled` prop is gated).
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "확인 불가 · 새로고침" })).toBeDisabled();
    expect(document.body.textContent).not.toMatch(/문서 근거 ON/);
    expect(document.body.textContent).not.toMatch(/문서 근거 OFF/);

    confirmSpy.mockRestore();
  });

  // Not an AC-44 clause itself (AC-44's own text never mentions deletion) -- this covers
  // `handleDelete`'s own `ragUnverified` cleanup (`ChatWorkspace.tsx`'s own comment on that call:
  // "not load-bearing for correctness... purely so a stale entry... does not have to be reasoned
  // about"). Real conversation ids are never recycled by the backend, so there is no way to
  // observe this cleanup through the UI without an artificial id reuse -- this test's mocked
  // `createConversation` deliberately hands back the same id the just-deleted conversation had,
  // isolating whether a leftover `ragUnverified` entry would wrongly mark a brand-new conversation
  // (one that never had a failed PATCH of its own) as unverified the instant it is created.
  it("handleDelete removes the deleted conversation's id from ragUnverified, so a same-id conversation created afterward is not wrongly treated as unverified", async () => {
    const convA = { ...CONV_A, ragEnabled: true }; // selected, starts ON
    mockedListConversations
      .mockResolvedValueOnce([convA]) // mount: adopts convA (the only conversation)
      .mockResolvedValueOnce([]) // handleDelete's post-delete refetch: nothing left
      .mockResolvedValue([]); // refreshList after ChatPanel's own auto-create
    mockedGetMessages.mockResolvedValue([]);
    mockedDeleteConversation.mockResolvedValue(undefined);
    mockedUpdateRagEnabled.mockRejectedValue(new ApiError(500, "boom"));
    // Reuses "conv-a" -- artificial, see this test's own comment above.
    mockedCreateConversation.mockResolvedValueOnce({
      id: "conv-a",
      title: null,
      model: "upstage/solar-pro4",
      ragEnabled: true,
      createdAt: "2026-09-02T00:00:00Z",
      updatedAt: "2026-09-02T00:00:00Z",
    });
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    const toggle = screen.getByRole("switch");
    expect(toggle).toHaveAttribute("aria-checked", "true");

    await user.click(toggle);
    expect(await screen.findByRole("alert")).toHaveTextContent("문서 근거 설정을 변경하지 못했습니다");
    expect(screen.queryByRole("switch")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "확인 불가 · 새로고침" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "recent chat 삭제" }));
    await waitFor(() => expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-a"));

    // Nothing left to adopt -- ChatPanel's own auto-create fires and this test's mocked
    // `createConversation` hands back a brand-new conversation that happens to reuse "conv-a"'s id.
    await waitFor(() => expect(mockedCreateConversation).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("새 대화"));

    // If `handleDelete` had not cleared "conv-a" out of `ragUnverified`, this freshly-created
    // conversation -- which never had a failed PATCH of its own -- would still render the
    // "확인 불가 · 새로고침" disabled button instead of a normal, enabled switch.
    const newToggle = await screen.findByRole("switch");
    expect(newToggle).toBeEnabled();
    expect(newToggle).toHaveAttribute("aria-checked", "true");
    expect(screen.queryByRole("button", { name: "확인 불가 · 새로고침" })).not.toBeInTheDocument();

    confirmSpy.mockRestore();
  });

  // chat-prototype-port:AC-42 (new, from T14's wrap-up round) -- the "미상"(unknown RAG state) window
  // itself, isolated as its own dedicated AC evidence rather than folded into the T14 test right
  // below (which exercises the same window but is tagged AC-9/round-4-probe history, not AC-42).
  // Keeps `listConversations` at its `beforeEach` default (`[]`) so the mount effect resolves to
  // "nothing to adopt" quickly, and holds `createConversation` pending so `ChatPanel`'s own
  // auto-create (triggered once `initialSelectionResolved` flips) never resolves during this
  // test -- `selectedId` stays `null` throughout, which is this component's actual "RAG state
  // unknown" condition per `ragValue`'s own derivation (`ChatWorkspace.tsx`'s top-of-file
  // [Caution]). Asserts both the positive claims (disabled, "확인 중…" present) and the negative
  // ones AC-42 explicitly requires (Spec's "판별력" -- discriminating power) -- "문서 근거 ON"/"문서
  // 근거 OFF" must each be *absent*, not merely unchecked, since a regression that rendered "OFF"
  // instead of "확인 중…" would otherwise still pass a test that only checked `disabled`.
  // [Correction, T14's second rework round, Medium-1] Two independent gaps in the previous
  // version, found while writing this fix (dev-frontend-engineer, self-verification):
  //   1. The negative assertions used to be `screen.queryAllByText("문서 근거 ON"/"문서 근거
  //      OFF")` -- an *exact* string match, which only matches a node whose own full text
  //      equals that string precisely. It missed `ChatPanel`'s composer hint
  //      (`ChatPanel.tsx`'s `composerHint`), whose ON/OFF strings are longer ("문서 근거 ON ·
  //      사내 문서에서 검색한 뒤 답변합니다") and therefore never matched -- before this card's
  //      fix, that hint fell back to `ChatPanel`'s old `ragEnabled = true` default for this
  //      exact `ragValue === undefined` scenario and silently rendered the ON copy.
  //   2. Even fixing (1) alone (switching to a document-wide substring scan) was not enough to
  //      make this test red against the pre-fix code: `screen.findByRole("switch")` resolves as
  //      soon as the header's toggle button exists, which happens on this component's very
  //      first synchronous render -- *before* the mount effect's own `await listConversations()`
  //      has had a chance to resolve and flip `initialSelectionResolved`, so `<ChatPanel>` was
  //      not mounted yet at assertion time and its composer hint was not in the DOM to catch at
  //      all (confirmed empirically: a temporary `console.log(document.body.textContent)` at
  //      that point showed the "대화를 불러오는 중…" placeholder, not `<ChatPanel>`'s own
  //      content). The `waitFor` below -- polling for the empty-state title `<ChatPanel>` only
  //      renders once mounted -- forces this test to actually wait for `<ChatPanel>` before
  //      scanning the document, so the composer-hint gap this AC's own text calls out is a real
  //      part of what gets exercised, not a vacuous pass.
  it("[chat-prototype-port:AC-42] the RAG toggle is disabled with '확인 중…' and zero ON/OFF copy while its state is unknown (no conversation selected yet)", async () => {
    const pendingCreate = deferred<typeof NEW_CONV>();
    mockedCreateConversation.mockReturnValue(pendingCreate.promise);
    render(<ChatWorkspace />);

    const toggle = await screen.findByRole("switch");
    expect(toggle).toBeDisabled();
    expect(screen.getByText("확인 중…")).toBeInTheDocument();
    // Waits for `<ChatPanel>` to actually mount (see this test's own [Correction] point 2
    // above) before scanning for the composer hint's ON/OFF copy below.
    await waitFor(() => expect(screen.getByText("무엇을 찾아드릴까요?")).toBeInTheDocument());
    // Substring scan across the entire rendered document (header toggle label *and* ChatPanel's
    // composer hint below it), not an exact-match query scoped to a single node -- see this
    // test's own [Correction] point 1 above for why the exact-match version had a blind spot.
    expect(document.body.textContent).not.toMatch(/문서 근거 ON/);
    expect(document.body.textContent).not.toMatch(/문서 근거 OFF/);

    // Drains the still-pending `createConversation` call so it does not resolve into a later
    // test and trip an "update on an unmounted component" warning -- this test's own assertions
    // are already complete above.
    await act(async () => {
      pendingCreate.resolve(NEW_CONV);
      await pendingCreate.promise;
    });
  });

  it("disables the RAG toggle while no conversation is selected yet, and enables it immediately with the real value once one is created (Spec §8 D-1 ③, T14)", async () => {
    const pending = deferred<typeof NEW_CONV>();
    mockedCreateConversation.mockReturnValue(pending.promise);
    render(<ChatWorkspace />);

    expect(await screen.findByRole("switch")).toBeDisabled();
    // dev-qa-tester (T14 rework QA, 2026-09-01) -- the "미상"(unknown) third state must not
    // claim either boolean reading while disabled: neither ON/OFF label text renders, and the
    // dedicated "확인 중…" copy does. Strengthens this pre-existing test rather than adding a
    // separate one, since it already captures the exact window (`ragValue === undefined`) where
    // this distinction matters. [Correction, T14's second rework round, Medium-1] Widened from an
    // exact-match `queryByText` (which missed `ChatPanel`'s longer composer-hint strings) to a
    // substring scan of the whole document, and added the `waitFor` below so `<ChatPanel>` has
    // actually mounted by the time that scan runs -- both gaps, and why they mattered together,
    // are explained in full on the dedicated `[chat-prototype-port:AC-42]` test above.
    expect(screen.getByText("확인 중…")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("무엇을 찾아드릴까요?")).toBeInTheDocument());
    expect(document.body.textContent).not.toMatch(/문서 근거 ON/);
    expect(document.body.textContent).not.toMatch(/문서 근거 OFF/);

    pending.resolve(NEW_CONV);
    await waitForComposerReady();

    // T14 rework: `listConversations` in this fixture always resolves to `[]` (`beforeEach`'s
    // default, never overridden in this test), so `list.conversations` never actually reflects
    // the newly created conversation for the rest of this test -- yet the toggle is enabled and
    // correct anyway, because `handleConversationCreated` seeds `ragById[created.id]` from the
    // create response's own `ragEnabled` field (`NEW_CONV.ragEnabled`, `false`) synchronously, in
    // the same handler invocation as `setSelectedId`. This is the fix for review round 4's "path
    // 7" (`docs/review/2026-09-01_chat-prototype-port-T5-round4.md`): there is no longer a window
    // where `selectedId` points at a conversation `ragById` does not yet have a value for.
    expect(screen.getByRole("switch")).toBeEnabled();
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "false");
  });

  // 2R (2nd review round) High-1 fix verification, originally written against the *old, buggy*
  // behavior (list-error -> `selectedConversation` becomes `undefined` -> `?? true` fallback
  // silently flips `aria-checked`) that predates T14. T14 rework: `ragById` is a separate state
  // from `list` entirely, so a `list`-fetch failure has no path to affecting the toggle at all
  // anymore regardless of the `ragEnabled` fixture value used -- this test still uses
  // `ragEnabled: false` (rather than `true`) so it would still catch a regression that somehow
  // reintroduced a shared fallback between the two states. Constructed via `handleDelete`'s own
  // post-delete `listConversations()` failing while the *deleted* conversation is a different one
  // from the currently selected/open one -- so `selectedId` stays pointed at the still-open
  // conversation the whole time, isolating "the post-delete refresh failed" as the only thing
  // that changed.
  it("[chat-prototype-port:AC-9] a list refresh failure after delete does not flip aria-checked away from the still-known conversation's actual ragEnabled value", async () => {
    const convOff = { ...CONV_A, ragEnabled: false };
    mockedListConversations
      .mockResolvedValueOnce([convOff, CONV_B]) // mount: adopts convOff (conversations[0])
      .mockRejectedValueOnce(new Error("network down")); // handleDelete's post-delete refetch
    mockedGetMessages.mockResolvedValue([]);
    mockedDeleteConversation.mockResolvedValue(undefined);
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    const toggle = screen.getByRole("switch");
    // Baseline: convOff is selected and actually present in a "ready" list -- enabled, real value.
    expect(toggle).toBeEnabled();
    expect(toggle).toHaveAttribute("aria-checked", "false");

    // Deletes CONV_B (not the selected convOff) -- selectedId is untouched, only the post-delete
    // `listConversations()` refetch fails.
    await user.click(screen.getByRole("button", { name: "old chat 삭제" }));

    // The failure is still surfaced -- just through the actionError banner, not by wiping `list`.
    expect(await screen.findByRole("alert")).toHaveTextContent("대화 목록을 새로고침하지 못했습니다");
    // `ragById` is untouched by this failure entirely (it is a separate state from `list`, T14
    // rework) -- the toggle keeps showing the same real, already-known value it always had.
    expect(toggle).toBeEnabled();
    expect(toggle).toHaveAttribute("aria-checked", "false");
    confirmSpy.mockRestore();
  });

  // Originated as a 3R (3rd review round) race-condition test, `docs/review/2026-09-01_chat-
  // prototype-port-T5.md` "3rd review round fix" -- `handleDelete`'s post-delete-*success*
  // `listConversations()` refetch is fire-and-await with no cancellation. If a RAG toggle PATCH
  // on the still-selected conversation is dispatched *and resolves* (applying its result to
  // `list`) while that refetch is still in flight, the refetch's own stale `conversations`
  // snapshot (captured before the PATCH) would otherwise unconditionally overwrite `list` in
  // full, silently reverting the already-applied, already-visible PATCH result the instant the
  // stale response landed. Originally guarded by a generation counter (`ragToggleGenerationRef`,
  // removed by card T14 -- see `ChatWorkspace`'s own top-of-file [Caution], "T14 rework"
  // paragraph); this test still exercises the exact same scenario, now protected by `ragById`
  // being a state entirely separate from `list` -- a refetch response can seed an id `ragById`
  // does not have yet, but can never overwrite one it already does -- via deferred promises for
  // both the refetch and the PATCH.
  it("[chat-prototype-port:AC-9] a stale post-delete list refetch arriving after a RAG toggle PATCH already resolved does not revert aria-checked (handleDelete path)", async () => {
    const convA = { ...CONV_A, ragEnabled: false }; // selected, starts OFF
    mockedListConversations.mockResolvedValueOnce([convA, CONV_B]); // mount: adopts convA
    mockedGetMessages.mockResolvedValue([]);
    mockedDeleteConversation.mockResolvedValue(undefined);
    // handleDelete's post-delete `listConversations()` refetch stays pending until this test
    // explicitly resolves it, below -- letting the RAG toggle PATCH land first.
    const pendingRefetch = deferred<ConversationSummaryResponse[]>();
    mockedListConversations.mockReturnValueOnce(pendingRefetch.promise);
    const pendingPatch = deferred<ConversationRagEnabledResponse>();
    mockedUpdateRagEnabled.mockReturnValue(pendingPatch.promise);
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    const toggle = screen.getByRole("switch");
    expect(toggle).toHaveAttribute("aria-checked", "false");

    // Step 1: delete "old chat" (conv-b, not the selected conv-a) -- dispatches the post-delete
    // refetch above, which stays pending.
    await user.click(screen.getByRole("button", { name: "old chat 삭제" }));
    expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-b");

    // Step 2: while that refetch is still in flight, toggle RAG on the still-selected conv-a --
    // its PATCH resolves immediately and is applied to `list` first.
    await user.click(toggle);
    pendingPatch.resolve({ id: "conv-a", ragEnabled: true });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));

    // Step 3: now let the stale refetch resolve -- an old snapshot where conv-a is still
    // `ragEnabled: false` (captured before the toggle click). `ragById["conv-a"]` is already
    // `true` (from the PATCH just above) by the time this lands, so `seedRagById` -- which only
    // ever *adds* an entry for an id it does not already have -- drops this stale value on the
    // floor instead of overwriting it.
    pendingRefetch.resolve([{ ...convA, ragEnabled: false }]); // conv-b genuinely gone (real delete outcome)
    await waitFor(() => expect(screen.queryByRole("button", { name: "old chat" })).not.toBeInTheDocument());
    expect(toggle).toHaveAttribute("aria-checked", "true");
    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);
    confirmSpy.mockRestore();
  });

  // Symmetric case for `refreshList` (the other call site sharing the same `seedRagById` seeding,
  // per this component's [Caution] and review round 3R's correctness Low-1 follow-up) --
  // `refreshList` itself is only reachable here via `handleDelete`'s *outer* catch (fired when
  // `deleteConversation` itself rejects, leaving `selectedId` untouched), since the success path
  // uses its own inline `listConversations()` call, already covered by the test above.
  it("[chat-prototype-port:AC-9] a stale refreshList refetch triggered by a failed delete does not revert aria-checked after a RAG toggle PATCH already resolved (refreshList path)", async () => {
    const convA = { ...CONV_A, ragEnabled: false }; // selected, starts OFF
    // A third conversation, absent from the mount-time list, included only in the *stale*
    // refetch response below -- its appearance is the test's proof that the stale refetch's
    // `setList` call actually landed (this delete fails, so unlike the sibling test above,
    // "old chat" never disappears either way and can't serve as that signal here).
    const convC = { id: "conv-c", title: "third chat", model: "upstage/solar-pro4", ragEnabled: true, updatedAt: "2026-08-15T00:00:00Z" };
    mockedListConversations.mockResolvedValueOnce([convA, CONV_B]); // mount: adopts convA
    mockedGetMessages.mockResolvedValue([]);
    mockedDeleteConversation.mockRejectedValueOnce(new Error("delete failed")); // outer catch -> refreshList()
    // refreshList's own `listConversations()` call stays pending until this test explicitly
    // resolves it, below.
    const pendingRefetch = deferred<ConversationSummaryResponse[]>();
    mockedListConversations.mockReturnValueOnce(pendingRefetch.promise);
    const pendingPatch = deferred<ConversationRagEnabledResponse>();
    mockedUpdateRagEnabled.mockReturnValue(pendingPatch.promise);
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    const toggle = screen.getByRole("switch");
    expect(toggle).toHaveAttribute("aria-checked", "false");

    // Deleting "old chat" fails outright (network error, before the server is ever reached) --
    // `handleDelete`'s outer catch fires `refreshList()` to reconcile, which stays pending
    // (deferred above). `selectedId` is untouched (still conv-a) the whole time.
    await user.click(screen.getByRole("button", { name: "old chat 삭제" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("대화를 삭제하지 못했습니다");

    // While that refreshList refetch is still in flight, toggle RAG on the still-selected
    // conv-a -- its PATCH resolves immediately and is applied to `list` first.
    await user.click(toggle);
    pendingPatch.resolve({ id: "conv-a", ragEnabled: true });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));

    // Now let the stale refreshList refetch resolve -- an old snapshot where conv-a is still
    // `ragEnabled: false` (the delete never actually happened, so "old chat" alone can't prove
    // this landed -- `convC`, unique to this response, is what `waitFor` below actually anchors
    // on). `ragById["conv-a"]` is already `true` (from the PATCH just above), so `seedRagById`
    // drops this stale value instead of reverting `aria-checked` to "false".
    pendingRefetch.resolve([{ ...convA, ragEnabled: false }, CONV_B, convC]);
    await waitFor(() => expect(screen.getByRole("button", { name: "third chat" })).toBeInTheDocument());
    expect(toggle).toHaveAttribute("aria-checked", "true");
    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);
    confirmSpy.mockRestore();
  });

  // Card T14 (`BL-032`) -- the probe scenario security executed manually in the 4R review
  // (`docs/review/2026-09-01_chat-prototype-port-T5-round4.md`), now fixed as a permanent test.
  // The previous (3R) `ragToggleGenerationRef` guard only protected the id selected at
  // *refetch-dispatch* time (conv-a here, never touched again after this test's step 1) -- not
  // the id actually switched to and toggled while that refetch was still in flight (conv-b).
  // T14 removes that guard entirely and instead keeps `ragEnabled` in `ragById`, a state entirely
  // separate from `list`/refetches (see `seedRagById`'s own [Rationale]) -- so which id triggered
  // the refetch, or which generation was current when it was dispatched, no longer matters at
  // all.
  it("[chat-prototype-port:AC-9] switching to a different conversation and toggling RAG there survives a stale post-delete refetch dispatched before the switch (T14 cross-conversation guard)", async () => {
    const convA = { ...CONV_A, ragEnabled: false }; // selected at mount
    const convB = { ...CONV_B, ragEnabled: false }; // switched to mid-refetch, then toggled
    const convC = {
      id: "conv-c",
      title: "third chat",
      model: "upstage/solar-pro4",
      ragEnabled: false,
      updatedAt: "2026-08-10T00:00:00Z",
    }; // deleted while conv-a is selected
    mockedListConversations.mockResolvedValueOnce([convA, convB, convC]); // mount: adopts convA
    mockedGetMessages.mockResolvedValue([]);
    mockedDeleteConversation.mockResolvedValue(undefined);
    // handleDelete's post-delete `listConversations()` refetch stays pending until this test
    // explicitly resolves it, below -- letting a switch-and-toggle on a *different* conversation
    // (conv-b) land first.
    const pendingRefetch = deferred<ConversationSummaryResponse[]>();
    mockedListConversations.mockReturnValueOnce(pendingRefetch.promise);
    const pendingPatch = deferred<ConversationRagEnabledResponse>();
    mockedUpdateRagEnabled.mockReturnValue(pendingPatch.promise);
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "false");

    // Step 1: delete "third chat" (conv-c) while conv-a is selected -- dispatches the post-delete
    // refetch above, which stays pending. `isBusy` (`isLoadingHistory`) is not raised by this
    // path since the deleted conversation is not the selected one, so the sidebar stays usable.
    await user.click(screen.getByRole("button", { name: "third chat 삭제" }));
    expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-c");

    // Step 2: while that refetch is still in flight, switch to conv-b.
    await user.click(screen.getByRole("button", { name: "old chat" }));
    await waitFor(() => expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("old chat"));

    // Step 3: toggle RAG on the now-selected conv-b -- its PATCH resolves immediately and is
    // applied to `list` first.
    await user.click(screen.getByRole("switch"));
    pendingPatch.resolve({ id: "conv-b", ragEnabled: true });
    await waitFor(() => expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "true"));

    // Step 4: now let the stale post-delete refetch resolve -- a snapshot where conv-c is
    // genuinely gone (the real delete outcome) but conv-b's `ragEnabled` is still the pre-toggle
    // value (`false`, captured before the toggle click). Pre-T14, this landed with no protection
    // for conv-b (only conv-a was protected by the old generation counter) and silently reverted
    // `aria-checked` back to "false" with no user action -- the exact bug round 4 found.
    pendingRefetch.resolve([convA, { ...convB, ragEnabled: false }]);
    await waitFor(() => expect(screen.queryByRole("button", { name: "third chat 삭제" })).not.toBeInTheDocument());
    expect(screen.getByRole("switch")).toHaveAttribute("aria-checked", "true");
    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);
    confirmSpy.mockRestore();
  });

  // Card T14 rework, round 2 (`BL-032` review round 4's own probe scenarios, reproduced here as
  // permanent tests -- `docs/review/2026-09-01_chat-prototype-port-T5-round4.md`). T14's *first*
  // attempt (`mergeConversationList`, the test directly above and its siblings) closed the
  // "value" axis (a refetch response can no longer overwrite a known id's `ragEnabled` with a
  // *different* value) but was itself Blocked in review for the "존재" (existence) axis it left
  // open: (path 7) the mount effect's own very first `listConversations()` call never routed
  // through `mergeConversationList` at all, so a conversation selected before that call resolved
  // had zero protection; and (path 6) `mergeConversationList` rebuilt its output via
  // `fresh.map(...)`, so an id genuinely *absent* from a particular refetch response produced no
  // output entry for it either, silently losing whatever value had been protected. This test
  // reproduces "path 7" -- see the next test for "path 6".
  it("[chat-prototype-port:AC-9] toggling RAG on a conversation created while the mount effect's own first fetch is still pending survives that fetch's stale response (round 4 probe, path 7)", async () => {
    const pendingMount = deferred<ConversationSummaryResponse[]>();
    // `handleConversationCreated`'s own `refreshList()` call is *also* deliberately kept pending
    // here (not resolved immediately like most other tests in this file do) -- if it were left
    // to resolve right away, its own `seedRagById` call would seed `ragById[created.id]` a beat
    // later regardless of whether `handleConversationCreated`'s *synchronous* seed line exists,
    // and the assertion right after creation below would pass either way -- exactly the kind of
    // judgment-power gap this card's own Spec §Tech Review notes call out (a coincidental second
    // path masking a missing first one). Deferring both refetches means the assertion right after
    // creation, below, can only be satisfied by the synchronous seed.
    const pendingRefreshList = deferred<ConversationSummaryResponse[]>();
    mockedListConversations
      .mockReturnValueOnce(pendingMount.promise) // mount's own first-ever fetch, stays pending
      .mockReturnValueOnce(pendingRefreshList.promise); // handleConversationCreated's own refreshList()
    mockedCreateConversation.mockResolvedValueOnce(NEW_CONV); // ragEnabled: false
    const pendingPatch = deferred<ConversationRagEnabledResponse>();
    mockedUpdateRagEnabled.mockReturnValue(pendingPatch.promise);
    const user = userEvent.setup();
    render(<ChatWorkspace />);

    // The mount effect's own first fetch is still pending here -- `<ChatPanel>` is not mounted
    // yet (gated on `initialSelectionResolved`), but the sidebar's "새 대화" button and the
    // header's RAG toggle are both rendered unconditionally regardless of that gate.
    await user.click(screen.getByTestId("new-conversation-button"));

    // `handleConversationCreated` seeds `ragById[created.id]` synchronously, in the same handler
    // invocation as `setSelectedId` -- the toggle is enabled and correct immediately, with
    // *neither* the mount fetch *nor* `refreshList`'s own fetch having resolved yet (both are
    // still pending at this exact point).
    const toggle = await screen.findByRole("switch");
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveAttribute("aria-checked", "false");

    await user.click(toggle);
    pendingPatch.resolve({ id: NEW_CONV.id, ragEnabled: true });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "true"));

    // Now let `refreshList`'s own fetch resolve too -- a snapshot that also predates the PATCH.
    pendingRefreshList.resolve([NEW_CONV_SUMMARY]); // ragEnabled: false, pre-PATCH
    await waitFor(() => expect(mockedListConversations).toHaveBeenCalledTimes(2));
    expect(toggle).toHaveAttribute("aria-checked", "true");

    // Finally, let the mount effect's own original fetch resolve -- a snapshot from *before* the
    // conversation existed at all (an empty list, captured before any of the above happened).
    pendingMount.resolve([]);

    await waitForComposerReady();
    expect(toggle).toHaveAttribute("aria-checked", "true");
    expect(toggle).toBeEnabled();
  });

  // "Path 6" of the same round 4 probe (see the previous test's own comment for the shared
  // background) -- a stale refetch response that omits a known conversation's row *entirely*
  // (rather than merely carrying a stale value for it) must not be able to erase its protected
  // `ragEnabled`, and a *second* stale response landing afterward, this time carrying an outright
  // stale value, must not reintroduce it either.
  it("[chat-prototype-port:AC-9] creating and toggling a brand-new conversation survives a stale post-delete refetch that omits it entirely, and a later stale refetch does not reintroduce the pre-PATCH value either (round 4 probe, path 6, parts A+B)", async () => {
    const convA = { ...CONV_A, ragEnabled: false };
    mockedListConversations.mockResolvedValueOnce([convA, CONV_B]); // mount: adopts convA
    mockedGetMessages.mockResolvedValue([]);
    mockedDeleteConversation.mockResolvedValue(undefined);

    // Step 1's post-delete refetch (`handleDelete`) stays pending until explicitly resolved below.
    const pendingDeleteRefetch = deferred<ConversationSummaryResponse[]>();
    mockedListConversations.mockReturnValueOnce(pendingDeleteRefetch.promise);

    const newConv = { ...NEW_CONV, id: "conv-new", ragEnabled: true }; // starts ON
    mockedCreateConversation.mockResolvedValueOnce(newConv);
    const newConvSummary = {
      id: newConv.id,
      title: null,
      model: newConv.model,
      ragEnabled: true,
      updatedAt: newConv.updatedAt,
    };
    // handleConversationCreated's own refreshList() call, right after creating conv-new.
    mockedListConversations.mockResolvedValueOnce([convA, CONV_B, newConvSummary]);

    const pendingPatch = deferred<ConversationRagEnabledResponse>();
    mockedUpdateRagEnabled.mockReturnValue(pendingPatch.promise);
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();

    // Step 1: delete "old chat" (conv-b) -- dispatches the post-delete refetch above, pending.
    await user.click(screen.getByRole("button", { name: "old chat 삭제" }));
    expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-b");

    // Step 2: while that refetch is still in flight, create a brand-new conversation and select it.
    await user.click(screen.getByTestId("new-conversation-button"));
    await waitFor(() => expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("새 대화"));
    const toggle = screen.getByRole("switch");
    await waitFor(() => expect(toggle).toBeEnabled());
    expect(toggle).toHaveAttribute("aria-checked", "true"); // seeded synchronously from `created`

    // Step 3: toggle RAG OFF on the new conversation -- its PATCH resolves immediately.
    await user.click(toggle);
    pendingPatch.resolve({ id: newConv.id, ragEnabled: false });
    await waitFor(() => expect(toggle).toHaveAttribute("aria-checked", "false"));

    // Part A: let the stale post-delete refetch resolve -- captured *before* conv-new existed,
    // so it omits that id entirely (the existence axis: the row itself is missing, not merely
    // carrying a stale value for it).
    pendingDeleteRefetch.resolve([convA]); // conv-b genuinely gone; conv-new not present at all
    await waitFor(() => expect(screen.queryByRole("button", { name: "old chat" })).not.toBeInTheDocument());
    expect(toggle).toHaveAttribute("aria-checked", "false");
    expect(mockedUpdateRagEnabled).toHaveBeenCalledTimes(1);

    // Part B: a second stale response, this time carrying an outright stale *value* for conv-new
    // (its pre-PATCH `true`) rather than omitting it, must not reintroduce that value either.
    mockedListConversations.mockResolvedValueOnce([{ ...newConvSummary, ragEnabled: true }]);
    await user.click(screen.getByRole("button", { name: "recent chat 삭제" })); // deletes conv-a
    await waitFor(() => expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-a"));
    expect(toggle).toHaveAttribute("aria-checked", "false");

    confirmSpy.mockRestore();
  });

  it("[chat:AC-38] starts a new conversation automatically on first load", async () => {
    render(<ChatWorkspace />);

    await waitForComposerReady();
    expect(mockedCreateConversation).toHaveBeenCalledTimes(1);
  });

  it("[chat:AC-38] clicking '+ 새 대화' starts another new conversation", async () => {
    mockedCreateConversation.mockResolvedValueOnce(NEW_CONV).mockResolvedValueOnce({ ...NEW_CONV, id: "second-conv" });
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    expect(mockedCreateConversation).toHaveBeenCalledTimes(1);

    // chat-prototype-port:AC-3 (card T3) changed the button's accessible name to bare "새 대화"
    // (no literal "+"); queried by testid rather than role/name because an untitled
    // conversation's own list-item label can carry the same accessible name (see
    // ConversationSidebar's own [Caution]).
    await user.click(screen.getByTestId("new-conversation-button"));

    await waitFor(() => expect(mockedCreateConversation).toHaveBeenCalledTimes(2));
  });

  it("shows a human-readable error when creating a new conversation hits the quota", async () => {
    mockedCreateConversation
      .mockResolvedValueOnce(NEW_CONV)
      .mockRejectedValueOnce(new ApiError(403, "conversation limit reached"));
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();

    // chat-prototype-port:AC-3 (card T3) -- see the note on the identical change above.
    await user.click(screen.getByTestId("new-conversation-button"));

    expect(await screen.findByRole("alert")).toHaveTextContent("개수 상한에 도달했습니다");
  });

  it("[chat:AC-38] selecting a different conversation from the sidebar displays its previous messages", async () => {
    // Two conversations so the mount effect's own adoption (`conversations[0]`, CONV_A) and
    // the click below resolve to different ids -- see review 2026-08-29 High-2 for why a
    // single-conversation list made this test's click a no-op.
    mockedListConversations.mockResolvedValue([CONV_A, CONV_B]);
    mockedGetMessages.mockImplementation(async (id: string) =>
      id === "conv-a"
        ? [
            { role: "user", content: "mount question", createdAt: "2026-08-27T00:00:00Z" },
            { role: "assistant", content: "mount answer", createdAt: "2026-08-27T00:00:01Z" },
          ]
        : [
            { role: "user", content: "what is insight-chat", createdAt: "2026-08-20T00:00:00Z" },
            { role: "assistant", content: "an internal chatbot", createdAt: "2026-08-20T00:00:01Z" },
          ],
    );
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    // Confirms the mount adopted CONV_A (not CONV_B) before clicking -- otherwise a passing
    // assertion below could be coincidence rather than proof the click actually did anything.
    // `<ChatPanel>` itself is not rendered at all until that adoption decision is settled (the
    // rework's `initialSelectionResolved` gate -- see ChatWorkspace's own [Caution]).
    await waitForComposerReady();
    expect(await screen.findByText("mount answer")).toBeInTheDocument();
    expect(mockedGetMessages).toHaveBeenCalledWith("conv-a");

    await user.click(await screen.findByRole("button", { name: "old chat" }));

    expect(await screen.findByText("what is insight-chat")).toBeInTheDocument();
    expect(screen.getByText("an internal chatbot")).toBeInTheDocument();
    // The mount-adopted transcript must actually be gone -- if `handleSelect`'s body were a
    // no-op (review 2026-08-29 High-2's exact reproduction), "mount answer" would still be on
    // screen instead of being replaced by CONV_B's messages.
    expect(screen.queryByText("mount answer")).not.toBeInTheDocument();
    expect(mockedGetMessages).toHaveBeenCalledWith("conv-b");
    // Guards the `history-${index}` id scheme `toChatMessages` assigns (ChatWorkspace.tsx) --
    // Spec chat:AC-38's evidence cell quotes `message-history-0`/`message-history-1` by name
    // (2026-08-28 browser verification), so a silent rename of that scheme would otherwise
    // leave the evidence citation pointing at a testid that no longer exists, with no test
    // catching it (review 2026-08-29 L6-①).
    expect(screen.getByTestId("message-history-0")).toHaveTextContent("what is insight-chat");
    expect(screen.getByTestId("message-history-1")).toHaveTextContent("an internal chatbot");
  });

  // review 2026-08-31 round-2 correctness Low-1: guards the seam between this component's
  // `toChatMessages` and MessageList's `SourceList` -- since 2026-09-01 `toChatMessages` threads
  // a history row's `sources` *array* through, but a `null`/missing value must still become
  // `undefined` (render nothing), never `null` (SourceList's clause ③ "delivery failure"
  // message). Untagged because AC-21 only regulates the immediately-post-stream screen, not
  // history reload, so this is a regression guard against a false failure message, not AC
  // evidence. See MessageList.tsx's own SourceList [Caution] for the mechanism this locks in.
  it("never shows the citation delivery-failure message for a message loaded from conversation history", async () => {
    mockedListConversations.mockResolvedValue([CONV_A]);
    mockedGetMessages.mockResolvedValue([
      { role: "user", content: "old question", createdAt: "2026-08-27T00:00:00Z" },
      { role: "assistant", content: "old answer", createdAt: "2026-08-27T00:00:01Z", sources: null },
    ]);
    render(<ChatWorkspace />);

    await waitForComposerReady();
    expect(await screen.findByText("old answer")).toBeInTheDocument();
    expect(screen.queryByText("출처를 불러오지 못했습니다.")).not.toBeInTheDocument();
    expect(screen.queryByText("출처")).not.toBeInTheDocument();
  });

  // 2026-09-01: a reload used to drop every citation, because `toChatMessages` discarded the
  // `sources` the backend already returns on `GET .../messages` (hybrid-rag AC-12's read side).
  // The persisted array must render exactly like the live stream's terminal chunk did.
  it("renders the persisted citations of an assistant message loaded from conversation history", async () => {
    mockedListConversations.mockResolvedValue([CONV_A]);
    mockedGetMessages.mockResolvedValue([
      { role: "user", content: "old question", createdAt: "2026-08-27T00:00:00Z", sources: null },
      {
        role: "assistant",
        content: "old answer",
        createdAt: "2026-08-27T00:00:01Z",
        sources: [
          { documentId: "doc-1", filename: "규정.pdf", score: 0.9, snippet: "…", page: 3 },
          { documentId: "doc-1", filename: "규정.pdf", score: 0.6, snippet: "…", page: 7 },
        ],
      },
    ]);
    render(<ChatWorkspace />);

    await waitForComposerReady();
    expect(await screen.findByText("old answer")).toBeInTheDocument();
    expect(screen.getByText("2건")).toBeInTheDocument();
    expect(screen.getByText("규정.pdf p.3")).toBeInTheDocument();
    expect(screen.getByText("규정.pdf p.7")).toBeInTheDocument();
    expect(screen.queryByText("출처를 불러오지 못했습니다.")).not.toBeInTheDocument();
  });

  it("adopting an existing conversation on first load never auto-creates one (review 2026-08-29 Medium-3, re-opened)", async () => {
    // This is the exact assertion QA reported removing after confirming it failed against the
    // pre-rework code (`ChatPanel` mounted with `conversationId={undefined}` while this
    // component's adoption fetch was still in flight, auto-creating an orphaned conversation
    // before the real one was adopted). It is intentionally its own test, separate from the
    // "displays its previous messages" case above, so this specific regression has one
    // dedicated assertion that cannot be satisfied by coincidence of other unrelated changes to
    // that test. Also carries the mount-adoption content check moved out of the AC-38 test
    // above (review 2026-08-29 High-2) -- that test now needs a click that switches to a
    // *non*-adopted conversation, so "the mount itself loads and displays the adopted
    // conversation's history" needs its own home instead of disappearing.
    mockedListConversations.mockResolvedValueOnce([CONV_B]).mockResolvedValue([NEW_CONV_SUMMARY, CONV_B]);
    mockedGetMessages.mockResolvedValue([
      { role: "user", content: "what is insight-chat", createdAt: "2026-08-20T00:00:00Z" },
      { role: "assistant", content: "an internal chatbot", createdAt: "2026-08-20T00:00:01Z" },
    ]);
    render(<ChatWorkspace />);

    await waitForComposerReady();

    expect(await screen.findByText("what is insight-chat")).toBeInTheDocument();
    expect(screen.getByText("an internal chatbot")).toBeInTheDocument();
    expect(mockedGetMessages).toHaveBeenCalledWith("conv-b");
    expect(screen.getByTestId("message-history-0")).toHaveTextContent("what is insight-chat");
    expect(screen.getByTestId("message-history-1")).toHaveTextContent("an internal chatbot");
    expect(mockedCreateConversation).not.toHaveBeenCalled();
  });

  it("shows an inline error and keeps the current conversation when loading a selection's history fails", async () => {
    mockedListConversations.mockResolvedValueOnce([CONV_B]).mockResolvedValue([NEW_CONV_SUMMARY, CONV_B]);
    mockedGetMessages.mockRejectedValue(new ApiError(404, "not found"));
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();

    await user.click(await screen.findByRole("button", { name: "old chat" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("대화를 찾을 수 없습니다");
    // The composer is still usable -- the failed selection did not tear down the current panel.
    expect(screen.getByRole("textbox")).toBeEnabled();
  });

  it("[chat:AC-38] deleting a conversation removes it from the sidebar list", async () => {
    mockedListConversations
      .mockResolvedValueOnce([CONV_B]) // initial fetch, before auto-create resolves
      .mockResolvedValueOnce([NEW_CONV_SUMMARY, CONV_B]) // after auto-create's refreshList
      .mockResolvedValue([NEW_CONV_SUMMARY]); // after delete's refreshList -- conv-b gone
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await screen.findByRole("button", { name: "old chat" });

    await user.click(screen.getByRole("button", { name: "old chat 삭제" }));

    expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-b");
    await waitFor(() => expect(screen.queryByRole("button", { name: "old chat" })).not.toBeInTheDocument());
    confirmSpy.mockRestore();
  });

  it("deleting the currently open conversation adopts a remaining one instead of creating a fresh one (review 2026-08-29 Medium-2)", async () => {
    // The prior version of this suite's only delete test never actually shrank its
    // `listConversations` mock on delete (the just-deleted conversation stayed in the
    // returned array) and let `getMessages` resolve to `undefined` (no implementation),
    // which threw inside `toChatMessages` and fell through to the *failure* fallback --
    // so the "adopt a remaining conversation" branch (`ChatWorkspace.tsx`'s
    // `if (conversations.length > 0)`) never actually ran even though the test's name
    // claimed to cover deletion. Here the mock genuinely drops CONV_A after delete, leaving
    // CONV_B to be adopted.
    mockedListConversations
      .mockResolvedValueOnce([CONV_A, CONV_B]) // mount: two conversations, adopts CONV_A
      .mockResolvedValueOnce([CONV_B]); // post-delete refetch inside handleDelete: CONV_A gone
    mockedGetMessages.mockImplementation(async (id: string) =>
      id === "conv-a"
        ? [{ role: "user", content: "mount question", createdAt: "2026-08-27T00:00:00Z" }]
        : [{ role: "user", content: "what is insight-chat", createdAt: "2026-08-20T00:00:00Z" }],
    );
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();
    expect(await screen.findByText("mount question")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "recent chat 삭제" }));

    expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-a");
    expect(await screen.findByText("what is insight-chat")).toBeInTheDocument();
    expect(screen.queryByText("mount question")).not.toBeInTheDocument();
    expect(mockedCreateConversation).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it("deleting the last remaining conversation starts a fresh one automatically", async () => {
    // Unlike the case above, nothing is left to adopt after the delete -- this is the one
    // branch where falling back to `ChatPanel`'s own auto-create is the correct outcome, not
    // the Medium-3 bug (see `ChatWorkspace`'s own [Caution]).
    mockedListConversations
      .mockResolvedValueOnce([CONV_B]) // mount: one conversation, adopts it
      .mockResolvedValueOnce([]) // post-delete refetch inside handleDelete: none left
      .mockResolvedValue([NEW_CONV_SUMMARY]); // refreshList after ChatPanel's own auto-create
    mockedGetMessages.mockResolvedValue([
      { role: "user", content: "what is insight-chat", createdAt: "2026-08-20T00:00:00Z" },
    ]);
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await screen.findByRole("button", { name: "old chat 삭제" });

    await user.click(screen.getByRole("button", { name: "old chat 삭제" }));

    expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-b");
    await waitFor(() => expect(mockedCreateConversation).toHaveBeenCalledTimes(1));
    confirmSpy.mockRestore();
  });

  // Card T12 rewrote this test's body onto the trigger-button + role="listbox" popover shape
  // (ModelSelector.tsx no longer renders a native <select>) -- the name itself is left
  // byte-for-byte unchanged (`design-tokens.test.ts`'s D1_BASELINE_NAMES,
  // chat-prototype-port:AC-29 -- confirmed present at design-tokens.test.ts:534, so the card's
  // "이름 유지" branch applies). The mouse-selection half of chat-prototype-port:AC-39's
  // evidence lives here under the existing name; the keyboard half gets its own new test right
  // below instead of being folded into this one's name, which would otherwise break the
  // baseline match. Picks the CATALOG's only *non-default* model ("Other Model") -- picking the
  // default would still pass a component that silently ignores the user's selection (the exact
  // lesson chat:AC-39's own evidence cell already recorded).
  it("[chat:AC-39] sends the selected model id in the actual message-send request", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "new-conv", delta: "ok", finishReason: "STOP", usage: null, sources: null }]),
    );
    const user = userEvent.setup();
    render(<ChatWorkspace />);

    // Selected by id, not role/name -- ConversationSidebar's own buttons share the "button"
    // role, and `#model-selector` is this trigger's own stable identity either way
    // (ModelSelector.tsx's [AC] chat-prototype-port:AC-30).
    const trigger = document.getElementById("model-selector") as HTMLButtonElement;
    await waitFor(() => expect(trigger).toBeEnabled());
    await user.click(trigger);
    await user.click(screen.getByRole("option", { name: "Other Model" }));

    const textbox = await waitForComposerReady();
    await user.type(textbox, "hello");
    await user.click(screen.getByRole("button", { name: "전송" }));

    await waitFor(() =>
      expect(mockedStreamAssistantReply).toHaveBeenCalledWith(
        "new-conv",
        "hello",
        expect.objectContaining({ model: "other/model" }),
      ),
    );
  });

  // §4:182행 증거 칸 지시("키보드로도 한 번 시험한다") -- the click-based test above and this one
  // together are chat-prototype-port:AC-39's mouse and keyboard coverage.
  it("[chat-prototype-port:AC-39] also sends a keyboard-picked non-default model id in the actual send request", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "new-conv", delta: "ok", finishReason: "STOP", usage: null, sources: null }]),
    );
    const user = userEvent.setup();
    render(<ChatWorkspace />);

    const trigger = document.getElementById("model-selector") as HTMLButtonElement;
    await waitFor(() => expect(trigger).toBeEnabled());
    trigger.focus();
    await user.keyboard("{ArrowDown}"); // opens the popover, focused on the current selection (default)
    await user.keyboard("{ArrowDown}"); // moves to "other/model", CATALOG's only non-default entry
    await user.keyboard("{Enter}"); // selects it, closes the popover, returns focus to the trigger

    const textbox = await waitForComposerReady();
    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    await waitFor(() =>
      expect(mockedStreamAssistantReply).toHaveBeenCalledWith(
        "new-conv",
        "hi",
        expect.objectContaining({ model: "other/model" }),
      ),
    );
  });
});
