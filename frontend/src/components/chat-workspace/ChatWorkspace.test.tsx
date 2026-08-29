import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ChatWorkspace } from "./ChatWorkspace";
import { ApiError } from "@/lib/api/client";
import { createConversation, deleteConversation, listConversations } from "@/lib/api/conversations";
import { listModels } from "@/lib/api/models";
import { getMessages, streamAssistantReply, type ChatChunk } from "@/lib/api/messages";

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
const CATALOG = [
  { id: "upstage/solar-pro4", displayName: "Solar Pro 4", default: true },
  { id: "other/model", displayName: "Other Model", default: false },
];

/** A single-chunk generator, matching ChatPanel.test.tsx's own helper -- not shared across
 *  files (each test file is self-contained, project convention). */
async function* singleChunkReply(chunks: ChatChunk[]): AsyncGenerator<ChatChunk, void, void> {
  for (const chunk of chunks) yield chunk;
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
    mockedListModels.mockReset().mockResolvedValue(CATALOG);
    mockedGetMessages.mockReset();
    mockedStreamAssistantReply.mockReset();
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

    await user.click(screen.getByRole("button", { name: "+ 새 대화" }));

    await waitFor(() => expect(mockedCreateConversation).toHaveBeenCalledTimes(2));
  });

  it("shows a human-readable error when creating a new conversation hits the quota", async () => {
    mockedCreateConversation
      .mockResolvedValueOnce(NEW_CONV)
      .mockRejectedValueOnce(new ApiError(403, "conversation limit reached"));
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();

    await user.click(screen.getByRole("button", { name: "+ 새 대화" }));

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

  it("[chat:AC-39] sends the selected model id in the actual message-send request", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "new-conv", delta: "ok", finishReason: "STOP", usage: null }]),
    );
    const user = userEvent.setup();
    render(<ChatWorkspace />);

    const select = await screen.findByRole("combobox");
    await waitFor(() => expect(select).toHaveValue("upstage/solar-pro4"));
    await user.selectOptions(select, "other/model");

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
});
