import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConversationSidebar, type ConversationListState } from "./ConversationSidebar";

/**
 * `ConversationSidebar` is purely presentational -- it takes a `ConversationListState` and
 * callback props, no network calls of its own -- so there is nothing to mock. Callbacks
 * are `jest.fn()` where a test asserts on the call, or the shared `noop()` stub below
 * where a test only needs the prop to satisfy the component's type.
 *
 * Card T3 (chat-prototype-port) note on query style: after AC-3, the new-conversation
 * button's accessible name is the bare text "새 대화" -- which an untitled conversation's own
 * list-item label can also carry (pre-existing `chat:AC-38` fallback, unrelated to this card).
 * Tests below reach the button via its `data-testid` (`new-conversation-button`) instead of
 * `getByRole(..., { name: "새 대화" })` wherever a fixture might also contain an untitled
 * conversation, and scope role/name list-item lookups to `within(screen.getByRole("list"))` so
 * they cannot accidentally match the button instead.
 */
const READY_TWO: ConversationListState = {
  status: "ready",
  conversations: [
    { id: "c1", title: "first conversation", model: "m1", ragEnabled: false, updatedAt: "2026-08-28T00:00:01Z" },
    { id: "c2", title: null, model: "m1", ragEnabled: false, updatedAt: "2026-08-27T00:00:00Z" },
  ],
};

function noop() {
  /* not exercised by tests that don't assert on this callback */
}

describe("ConversationSidebar", () => {
  it("shows a loading status while the list is being fetched", () => {
    render(
      <ConversationSidebar list={{ status: "loading" }} selectedId={null} onSelect={noop} onCreate={noop} onDelete={noop} />,
    );

    expect(screen.getByRole("status")).toHaveTextContent("불러오는 중");
  });

  it("[chat-prototype-port:AC-7] shows the unified loading copy exactly, ellipsis and all", () => {
    render(
      <ConversationSidebar list={{ status: "loading" }} selectedId={null} onSelect={noop} onCreate={noop} onDelete={noop} />,
    );

    // Character-exact, not a substring match -- a variant like "대화 목록을 불러오는 중..."
    // (three literal dots instead of the ellipsis character) must fail this assertion
    // (Spec §6 판별력 요건 1).
    expect(screen.getByRole("status").textContent).toBe("대화를 불러오는 중…");
  });

  it("shows an accessible error when the list fails to load", () => {
    render(
      <ConversationSidebar
        list={{ status: "error", message: "network down" }}
        selectedId={null}
        onSelect={noop}
        onCreate={noop}
        onDelete={noop}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("network down");
  });

  it("shows an empty-state message when the list has no conversations", () => {
    render(
      <ConversationSidebar
        list={{ status: "ready", conversations: [] }}
        selectedId={null}
        onSelect={noop}
        onCreate={noop}
        onDelete={noop}
      />,
    );

    // Character-exact (Spec §6 판별력 요건 1) -- the pre-port copy differed from the correct
    // one by exactly one space ("대화가 없습니다. 새 대화를 시작해보세요." vs the prototype's
    // "아직 대화가 없습니다. 새 대화를 시작해 보세요."), which a `/대화가 없습니다/` regex would
    // not have caught.
    expect(screen.getByText("아직 대화가 없습니다. 새 대화를 시작해 보세요.")).toBeInTheDocument();
  });

  // dev-qa-tester (2026-09-01, card T3 evidence pass): the test right above this one is one of
  // the 6 pre-existing names design-tokens.test.ts's D1_BASELINE_NAMES pins (Spec §8 R-2's
  // table, row "shows an empty-state message..." -- AC-5 대문 문구가 바뀌었으니 이름은 낡은 채로
  // 두고 본문만 바꾼다). Renaming it to carry an `[chat-prototype-port:AC-5]` tag would make
  // `design-tokens.test.ts`'s baseline-subset check fail (AC-29 requires that file to stay
  // byte-for-byte unmodified), so the tag cannot live on that test. This separate test exists
  // solely to give AC-5 a passing `[chat-prototype-port:AC-5]`-tagged assertion for the evidence
  // ledger, without touching the protected name above.
  it("[chat-prototype-port:AC-5] empty-state copy is character-exact, not merely a substring match", () => {
    render(
      <ConversationSidebar
        list={{ status: "ready", conversations: [] }}
        selectedId={null}
        onSelect={noop}
        onCreate={noop}
        onDelete={noop}
      />,
    );

    const empty = screen.getByText("아직 대화가 없습니다. 새 대화를 시작해 보세요.");
    expect(empty.textContent).toBe("아직 대화가 없습니다. 새 대화를 시작해 보세요.");
    // Negative control: the pre-port copy (missing the space before "보세요") must not appear.
    expect(screen.queryByText("아직 대화가 없습니다. 새 대화를 시작해보세요.")).not.toBeInTheDocument();
  });

  it("[chat-prototype-port:AC-2] renders the brand block ('insight' then '사내 문서 검색', in that order) at the top of the sidebar", () => {
    render(
      <ConversationSidebar list={{ status: "ready", conversations: [] }} selectedId={null} onSelect={noop} onCreate={noop} onDelete={noop} />,
    );

    expect(screen.getByText("insight")).toBeInTheDocument();
    expect(screen.getByText("사내 문서 검색")).toBeInTheDocument();
    // Position check (Spec §4 AC-2 "최상단"), scoped to the sidebar's own root (the <aside>,
    // exposed via its "complementary" landmark role) rather than the test's render container --
    // proves "insight" is the sidebar's very first text node, so moving the brand block below
    // the header (e.g. next to the footer) would fail this assertion where the pre-fix version
    // (relative order only) would not have caught it.
    const text = screen.getByRole("complementary").textContent ?? "";
    expect(text.indexOf("insight")).toBe(0);
    expect(text.indexOf("insight")).toBeLessThan(text.indexOf("사내 문서 검색"));
  });

  it("[chat-prototype-port:AC-3] the new-conversation button's accessible name is exactly '새 대화', with no literal '+'", () => {
    render(
      <ConversationSidebar list={{ status: "ready", conversations: [] }} selectedId={null} onSelect={noop} onCreate={noop} onDelete={noop} />,
    );

    // Exact name match (not a substring) -- proves the "+" glyph is not part of the accessible
    // name, only present (if at all) as a decorative, aria-hidden icon.
    expect(screen.getByRole("button", { name: "새 대화" })).toBe(screen.getByTestId("new-conversation-button"));
    expect(screen.queryByRole("button", { name: "+ 새 대화" })).not.toBeInTheDocument();
  });

  it("[chat-prototype-port:AC-4] renders the '최근 대화' label above the conversation list", () => {
    const { container } = render(
      <ConversationSidebar list={READY_TWO} selectedId={null} onSelect={noop} onCreate={noop} onDelete={noop} />,
    );

    expect(screen.getByText("최근 대화")).toBeInTheDocument();
    const text = container.textContent ?? "";
    expect(text.indexOf("최근 대화")).toBeLessThan(text.indexOf("first conversation"));
  });

  it("[chat-prototype-port:AC-6] renders the two-line session footer at the bottom of the sidebar", () => {
    render(<ConversationSidebar list={READY_TWO} selectedId={null} onSelect={noop} onCreate={noop} onDelete={noop} />);

    expect(screen.getByText("익명 세션 · 로그인 없이 사용 중")).toBeInTheDocument();
    expect(screen.getByText("세션 쿠키 기준으로 대화가 보관됩니다.")).toBeInTheDocument();
    // Position check (Spec §4 AC-6 "하단"): both footer lines must come after the "최근 대화"
    // list-section label, and the second line must be the very last text in the sidebar --
    // proves the footer cannot be moved above the header/list (or a later section appended
    // after it) without failing this test, where the pre-fix version (existence only) would not
    // have caught it.
    const text = screen.getByRole("complementary").textContent ?? "";
    expect(text.indexOf("익명 세션 · 로그인 없이 사용 중")).toBeGreaterThan(text.indexOf("최근 대화"));
    expect(text.indexOf("세션 쿠키 기준으로 대화가 보관됩니다.")).toBeGreaterThan(text.indexOf("최근 대화"));
    expect(text.endsWith("세션 쿠키 기준으로 대화가 보관됩니다.")).toBe(true);
  });

  it("[chat:AC-38] renders every conversation's title, falling back to a placeholder for a null title", () => {
    render(<ConversationSidebar list={READY_TWO} selectedId={null} onSelect={noop} onCreate={noop} onDelete={noop} />);

    // Scoped to the list -- the new-conversation button also carries the "새 대화" accessible
    // name (see file-level note above), so an unscoped getByRole would be ambiguous.
    const list = within(screen.getByRole("list"));
    expect(list.getByRole("button", { name: "first conversation" })).toBeInTheDocument();
    expect(list.getByRole("button", { name: "새 대화" })).toBeInTheDocument();
  });

  it("marks the selected conversation with aria-current, and no other item", () => {
    render(<ConversationSidebar list={READY_TWO} selectedId="c2" onSelect={noop} onCreate={noop} onDelete={noop} />);

    const list = within(screen.getByRole("list"));
    expect(list.getByRole("button", { name: "first conversation" })).not.toHaveAttribute("aria-current");
    expect(list.getByRole("button", { name: "새 대화" })).toHaveAttribute("aria-current", "true");
  });

  it("[chat:AC-38] clicking a conversation calls onSelect with its id", async () => {
    const onSelect = jest.fn();
    const user = userEvent.setup();
    render(<ConversationSidebar list={READY_TWO} selectedId={null} onSelect={onSelect} onCreate={noop} onDelete={noop} />);

    await user.click(screen.getByRole("button", { name: "first conversation" }));

    expect(onSelect).toHaveBeenCalledWith("c1");
  });

  it("selecting a conversation via the keyboard (Tab + Enter) also calls onSelect", async () => {
    const onSelect = jest.fn();
    const user = userEvent.setup();
    render(<ConversationSidebar list={READY_TWO} selectedId={null} onSelect={onSelect} onCreate={noop} onDelete={noop} />);

    screen.getByRole("button", { name: "first conversation" }).focus();
    await user.keyboard("{Enter}");

    expect(onSelect).toHaveBeenCalledWith("c1");
  });

  it("[chat:AC-38] clicking '+ 새 대화' calls onCreate", async () => {
    const onCreate = jest.fn();
    const user = userEvent.setup();
    render(
      <ConversationSidebar list={READY_TWO} selectedId={null} onSelect={noop} onCreate={onCreate} onDelete={noop} />,
    );

    await user.click(screen.getByTestId("new-conversation-button"));

    expect(onCreate).toHaveBeenCalledTimes(1);
  });

  it("[chat:AC-38] deleting a conversation calls onDelete only after the user confirms", async () => {
    const onDelete = jest.fn();
    const user = userEvent.setup();
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    render(
      <ConversationSidebar list={READY_TWO} selectedId={null} onSelect={noop} onCreate={noop} onDelete={onDelete} />,
    );

    await user.click(screen.getByRole("button", { name: "first conversation 삭제" }));

    expect(confirmSpy).toHaveBeenCalledTimes(1);
    expect(onDelete).toHaveBeenCalledWith("c1");
    confirmSpy.mockRestore();
  });

  it("does not call onDelete when the user cancels the confirmation", async () => {
    const onDelete = jest.fn();
    const user = userEvent.setup();
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(false);
    render(
      <ConversationSidebar list={READY_TWO} selectedId={null} onSelect={noop} onCreate={noop} onDelete={onDelete} />,
    );

    await user.click(screen.getByRole("button", { name: "first conversation 삭제" }));

    expect(onDelete).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it("disables new/select/delete controls while isBusy is true", () => {
    render(
      <ConversationSidebar list={READY_TWO} selectedId={null} onSelect={noop} onCreate={noop} onDelete={noop} isBusy />,
    );

    expect(screen.getByTestId("new-conversation-button")).toBeDisabled();
    expect(screen.getByRole("button", { name: "first conversation" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "first conversation 삭제" })).toBeDisabled();
  });

  it("shows the latest action error as an accessible alert", () => {
    render(
      <ConversationSidebar
        list={READY_TWO}
        selectedId={null}
        onSelect={noop}
        onCreate={noop}
        onDelete={noop}
        actionError="대화를 만들지 못했습니다: 개수 상한에 도달했습니다."
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("개수 상한에 도달했습니다");
  });
});
