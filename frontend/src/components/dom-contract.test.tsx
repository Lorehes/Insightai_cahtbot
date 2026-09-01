import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ChatWorkspace } from "./chat-workspace/ChatWorkspace";
import { createConversation, deleteConversation, listConversations } from "@/lib/api/conversations";
import { listModels } from "@/lib/api/models";
import { getMessages, streamAssistantReply, type ChatChunk } from "@/lib/api/messages";

/**
 * [Purpose] Fixes the DOM anchors chat:AC-36/37/38/39/40's 🔶 browser evidence depends on, so
 *   a later card that touches markup gets an immediate red instead of silently invalidating
 *   that evidence (Spec §8 R1).
 * [Rationale] Only the network boundary (`lib/api/*`) is mocked, exactly like
 *   `chat-workspace/ChatWorkspace.test.tsx` -- `ConversationSidebar`, `ModelSelector`,
 *   `ChatPanel`, `MessageList` are all real, so the id-generating code (`ChatPanel.tsx`'s
 *   `nextMessageId`, `ChatWorkspace.tsx`'s `toChatMessages`) actually runs. Anchors are always
 *   queried by prefix selector (`^=`), never by a fixture's own id value (Spec §8 R7) --
 *   `MessageList.test.tsx`'s existing `getByTestId("message-a1")` cases stay green even if the
 *   `assistant-`/`user-` prefix scheme changes, so they cannot stand in for this file. The
 *   `message-history-` prefix is the one exception: `ChatWorkspace.test.tsx:159-160,186-187`
 *   already guards it against a real `toChatMessages()` call (2026-08-29 rework), so this
 *   file's history case is a regression re-check, not new coverage -- only `message-assistant-`
 *   /`message-user-` (live streaming) and `conversation-` were unguarded before this file
 *   (review 2026-08-30, correctness axis: confirmed against `ChatPanel.test.tsx` having zero
 *   testid assertions). The `#model-selector` catalog fixture below intentionally uses an `id`
 *   that differs from its `displayName`, so a future swap to a display-label-keyed
 *   `<option value>` would fail here (Spec §4 AC-17 rationale).
 * [Caution] Do not restate the semantic layer already covered by
 *   `ConversationSidebar.test.tsx`/`ModelSelector.test.tsx` (`aria-current`, `role="status"`,
 *   accessible names) -- Spec §4 "AC-16·17은 data-testid 층만 다룬다". Re-asserting it here
 *   would create the exact split-evidence problem `insight-chat-c0` flagged (duplicate
 *   assertions of the same fact, only one of which gets updated when the code changes). The
 *   delete button's `aria-label` query below must use its full accessible name (`"old chat
 *   삭제"`), not a `/삭제/` substring match -- the button's own text content is also literally
 *   "삭제" (`ConversationSidebar.tsx:108`), so a substring match would still pass even if the
 *   `aria-label` attribute were deleted entirely (review 2026-08-30 correctness High-1).
 * [AC] design-system:AC-16, design-system:AC-17
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
  createdAt: "2026-08-30T00:00:00Z",
  updatedAt: "2026-08-30T00:00:00Z",
};
// id and displayName are deliberately different strings (Spec §4 AC-17 rationale, §6
// "판별력 요건") -- an <option value> that silently became the display label instead of the
// wire id would still look plausible to a human but fail this file's equality check.
const CATALOG = [
  { id: "upstage/solar-pro4", displayName: "Solar Pro 4", default: true },
  { id: "some/other-model", displayName: "Other Model", default: false },
];
const CONV_A = { id: "conv-a", title: "recent chat", model: "upstage/solar-pro4", ragEnabled: false, updatedAt: "2026-08-27T00:00:00Z" };
const CONV_B = { id: "conv-b", title: "old chat", model: "upstage/solar-pro4", ragEnabled: false, updatedAt: "2026-08-20T00:00:00Z" };

async function* singleChunkReply(chunks: ChatChunk[]): AsyncGenerator<ChatChunk, void, void> {
  for (const chunk of chunks) yield chunk;
}

/** Mirrors `ChatWorkspace.test.tsx`'s own helper -- see that file's [Caution] for why this
 *  re-queries on every poll instead of capturing one node up front. */
async function waitForComposerReady() {
  await waitFor(() => expect(screen.getByRole("textbox")).toBeEnabled());
  return screen.getByRole("textbox");
}

describe("DOM anchor contract (design-system:AC-16, AC-17)", () => {
  beforeEach(() => {
    mockedCreateConversation.mockReset().mockResolvedValue(NEW_CONV);
    mockedListConversations.mockReset().mockResolvedValue([]);
    mockedDeleteConversation.mockReset().mockResolvedValue(undefined);
    mockedListModels.mockReset().mockResolvedValue(CATALOG);
    mockedGetMessages.mockReset();
    mockedStreamAssistantReply.mockReset();
  });

  it("[design-system:AC-16] a live-streamed turn exposes message-assistant-*/message-user-* testid prefixes", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "new-conv", delta: "hi there", finishReason: "STOP", usage: null, sources: null }]),
    );
    const user = userEvent.setup();
    render(<ChatWorkspace />);

    const textbox = await waitForComposerReady();
    await user.type(textbox, "hello");
    await user.click(screen.getByRole("button", { name: "전송" }));

    await waitFor(() => expect(document.querySelector('[data-testid^="message-assistant-"]')).not.toBeNull());
    expect(document.querySelector('[data-testid^="message-user-"]')).not.toBeNull();
    // Not a fixture id: this is `ChatPanel.tsx`'s real `nextMessageId()` counter, unguarded
    // before this test (neither `ChatPanel.test.tsx` nor `MessageList.test.tsx`'s fixed
    // `"a1"` id ever exercised it).
  });

  it("[design-system:AC-16] a reselected conversation's prior turns expose message-history-* testids, one per message", async () => {
    mockedListConversations.mockResolvedValue([CONV_A, CONV_B]);
    mockedGetMessages.mockImplementation(async (id: string) =>
      id === "conv-a"
        ? [{ role: "user", content: "mount question", createdAt: "2026-08-27T00:00:00Z" }]
        : [
            { role: "user", content: "what is insight-chat", createdAt: "2026-08-20T00:00:00Z" },
            { role: "assistant", content: "an internal chatbot", createdAt: "2026-08-20T00:00:01Z" },
          ],
    );
    const user = userEvent.setup();
    render(<ChatWorkspace />);
    await waitForComposerReady();

    await user.click(await screen.findByRole("button", { name: "old chat" }));

    await waitFor(() =>
      expect(document.querySelectorAll('[data-testid^="message-history-"]')).toHaveLength(2),
    );
  });

  it("[design-system:AC-16] each sidebar entry is a li[data-testid^=\"conversation-\"] whose first button selects and whose aria-label=\"...삭제\" button deletes", async () => {
    mockedListConversations.mockResolvedValue([CONV_A, CONV_B]);
    mockedGetMessages.mockResolvedValue([]);
    const confirmSpy = jest.spyOn(window, "confirm").mockReturnValue(true);
    const user = userEvent.setup();
    try {
      render(<ChatWorkspace />);
      await waitForComposerReady();

      const items = document.querySelectorAll('li[data-testid^="conversation-"]');
      expect(items.length).toBe(2);
      const oldChatItem = Array.from(items).find((el) => el.textContent?.includes("old chat"));
      expect(oldChatItem).toBeDefined();
      const scoped = within(oldChatItem as HTMLElement);

      const firstButton = scoped.getAllByRole("button")[0];
      await user.click(firstButton);
      await waitFor(() => expect(mockedGetMessages).toHaveBeenCalledWith("conv-b"));

      // Full accessible name, not a `/삭제/` substring: the button's own text content is also
      // literally "삭제" (ConversationSidebar.tsx:108), so a substring match would still pass
      // even with the `aria-label` attribute deleted entirely -- this exact name only resolves
      // if `aria-label="old chat 삭제"` is actually present (review 2026-08-30 correctness High-1).
      const deleteButton = scoped.getByRole("button", { name: "old chat 삭제" });
      await waitFor(() => expect(deleteButton).toBeEnabled());
      await user.click(deleteButton);
      expect(mockedDeleteConversation).toHaveBeenCalledWith("conv-b");
    } finally {
      confirmSpy.mockRestore();
    }
  });

  it("[design-system:AC-17] #model-selector is a popover trigger whose option ids exactly match the catalog's id set", async () => {
    const user = userEvent.setup();
    render(<ChatWorkspace />);

    const trigger = await waitFor(() => {
      const el = document.querySelector("#model-selector");
      expect(el).not.toBeNull();
      expect(el?.tagName).toBe("BUTTON");
      expect((el as HTMLButtonElement).disabled).toBe(false);
      return el as HTMLButtonElement;
    });

    await user.click(trigger);

    const listbox = await screen.findByRole("listbox");
    const optionIds = within(listbox)
      .getAllByRole("option")
      .map((o) => o.getAttribute("data-model-id"));
    expect(optionIds).toEqual(CATALOG.map((m) => m.id));
    // If this ever became the display label instead of the wire id, it would still read
    // "Solar Pro 4"/"Other Model" to a human -- the id/displayName mismatch in CATALOG above
    // is what makes that swap fail here instead of passing by coincidence.
  });
});
