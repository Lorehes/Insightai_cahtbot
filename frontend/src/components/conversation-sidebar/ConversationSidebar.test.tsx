import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ConversationSidebar, type ConversationListState } from "./ConversationSidebar";

/**
 * `ConversationSidebar` is purely presentational -- it takes a `ConversationListState` and
 * callback props, no network calls of its own -- so there is nothing to mock. Callbacks
 * are `jest.fn()` where a test asserts on the call, or the shared `noop()` stub below
 * where a test only needs the prop to satisfy the component's type.
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

    expect(screen.getByText(/대화가 없습니다/)).toBeInTheDocument();
  });

  it("[chat:AC-38] renders every conversation's title, falling back to a placeholder for a null title", () => {
    render(<ConversationSidebar list={READY_TWO} selectedId={null} onSelect={noop} onCreate={noop} onDelete={noop} />);

    expect(screen.getByRole("button", { name: "first conversation" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "새 대화" })).toBeInTheDocument();
  });

  it("marks the selected conversation with aria-current, and no other item", () => {
    render(<ConversationSidebar list={READY_TWO} selectedId="c2" onSelect={noop} onCreate={noop} onDelete={noop} />);

    expect(screen.getByRole("button", { name: "first conversation" })).not.toHaveAttribute("aria-current");
    expect(screen.getByRole("button", { name: "새 대화" })).toHaveAttribute("aria-current", "true");
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

    await user.click(screen.getByRole("button", { name: "+ 새 대화" }));

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

    expect(screen.getByRole("button", { name: "+ 새 대화" })).toBeDisabled();
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
