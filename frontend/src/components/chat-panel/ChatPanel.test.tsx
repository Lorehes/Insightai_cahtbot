import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ChatPanel } from "./ChatPanel";
import { createConversation } from "@/lib/api/conversations";
import { streamAssistantReply, type ChatChunk } from "@/lib/api/messages";

jest.mock("@/lib/api/conversations");
// Preserves the real `isErrorFinish` (ChatPanel calls it directly) while replacing only
// the network-touching `streamAssistantReply`.
jest.mock("@/lib/api/messages", () => ({
  ...jest.requireActual("@/lib/api/messages"),
  streamAssistantReply: jest.fn(),
}));

const mockedCreateConversation = createConversation as jest.MockedFunction<typeof createConversation>;
const mockedStreamAssistantReply = streamAssistantReply as jest.MockedFunction<typeof streamAssistantReply>;

const READY_CONVERSATION = {
  id: "conv-1",
  title: null,
  model: "some-model",
  ragEnabled: false,
  createdAt: "2026-08-28T00:00:00Z",
  updatedAt: "2026-08-28T00:00:00Z",
};

/** A single-chunk generator, for tests that don't care about incremental timing. */
async function* singleChunkReply(chunks: ChatChunk[]): AsyncGenerator<ChatChunk, void, void> {
  for (const chunk of chunks) yield chunk;
}

/** Deferred gate so a test can control exactly when a mocked stream advances past it. */
function createGate() {
  let release!: () => void;
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}

async function waitForReady() {
  const textbox = await screen.findByRole("textbox");
  await waitFor(() => expect(textbox).toBeEnabled());
  return textbox;
}

describe("ChatPanel", () => {
  beforeEach(() => {
    mockedCreateConversation.mockReset();
    mockedStreamAssistantReply.mockReset();
    mockedCreateConversation.mockResolvedValue(READY_CONVERSATION);
  });

  it("disables the input while the conversation is being created, then enables it", async () => {
    let resolveCreate!: (value: typeof READY_CONVERSATION) => void;
    mockedCreateConversation.mockReset();
    mockedCreateConversation.mockReturnValue(new Promise((resolve) => (resolveCreate = resolve)));

    render(<ChatPanel />);
    expect(screen.getByRole("textbox")).toBeDisabled();

    resolveCreate(READY_CONVERSATION);
    await waitFor(() => expect(screen.getByRole("textbox")).toBeEnabled());
  });

  it("shows an accessible error and no input when conversation creation fails", async () => {
    mockedCreateConversation.mockReset();
    mockedCreateConversation.mockRejectedValue(new Error("quota exceeded"));

    render(<ChatPanel />);

    expect(await screen.findByRole("alert")).toHaveTextContent("quota exceeded");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("skips creating a conversation when one is already provided", async () => {
    render(<ChatPanel conversationId="existing-conv" />);

    await waitForReady();
    expect(mockedCreateConversation).not.toHaveBeenCalled();
  });

  it("shows the user's own message immediately, rendered as plain text", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "conv-1", delta: "hi back", finishReason: "STOP", usage: null }]),
    );
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hello there");
    await user.click(screen.getByRole("button", { name: "전송" }));

    expect(await screen.findByText("hello there")).toBeInTheDocument();
    // The textarea clears once the turn is sent.
    expect(textbox).toHaveValue("");
  });

  it("[chat:AC-36] grows the assistant reply's rendered text across separate chunks instead of showing it all at once", async () => {
    const gate = createGate();
    mockedStreamAssistantReply.mockImplementation(async function* () {
      yield { conversationId: "conv-1", delta: "안", finishReason: null, usage: null };
      await gate.promise;
      yield { conversationId: "conv-1", delta: "녕하세요", finishReason: null, usage: null };
      yield { conversationId: "conv-1", delta: "!", finishReason: "STOP", usage: null };
    });
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    const reply = await screen.findByRole("status");
    await waitFor(() => expect(reply).toHaveTextContent("안"));
    expect(reply).toHaveAttribute("aria-busy", "true");
    const lengthBeforeGrowth = reply.textContent?.length ?? 0;

    gate.release();

    await waitFor(() => expect(reply.textContent).toBe("안녕하세요!"));
    expect(reply.textContent!.length).toBeGreaterThan(lengthBeforeGrowth);
    await waitFor(() => expect(reply).toHaveAttribute("aria-busy", "false"));
  });

  it("[chat:AC-37] renders the completed assistant reply's markdown as real elements once streaming finishes", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([
        { conversationId: "conv-1", delta: "## heading\n\n- item", finishReason: null, usage: null },
        { conversationId: "conv-1", delta: "\n\ndone", finishReason: "STOP", usage: null },
      ]),
    );
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "give me markdown");
    await user.click(screen.getByRole("button", { name: "전송" }));

    expect(await screen.findByRole("heading", { level: 2, name: "heading" })).toBeInTheDocument();
    // Scoped to the assistant's own status region: the transcript itself is also a `<ul>`
    // (role "list"/its rows aren't "listitem" here, but scoping keeps this robust either way).
    expect(within(screen.getByRole("status")).getByRole("listitem")).toHaveTextContent("item");
  });

  it("disables the input while a reply is streaming and re-enables it once done", async () => {
    const gate = createGate();
    mockedStreamAssistantReply.mockImplementation(async function* () {
      await gate.promise;
      yield { conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null };
    });
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    await waitFor(() => expect(textbox).toBeDisabled());

    gate.release();

    await waitFor(() => expect(textbox).toBeEnabled());
  });

  it("cannot submit an empty or whitespace-only draft", async () => {
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "   ");

    expect(screen.getByRole("button", { name: "전송" })).toBeDisabled();
    expect(mockedStreamAssistantReply).not.toHaveBeenCalled();
  });

  it("sends on Enter, and inserts a newline instead of sending on Shift+Enter", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null }]),
    );
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "shift line{Shift>}{Enter}{/Shift}");
    expect(mockedStreamAssistantReply).not.toHaveBeenCalled();

    await user.type(textbox, "{Enter}");
    await waitFor(() => expect(mockedStreamAssistantReply).toHaveBeenCalledTimes(1));
  });

  it("marks the reply as an error when the stream throws mid-turn (not an intentional abort)", async () => {
    mockedStreamAssistantReply.mockImplementation(async function* () {
      yield { conversationId: "conv-1", delta: "partial", finishReason: null, usage: null };
      throw new Error("network dropped");
    });
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(/연결이 끊어졌습니다/));
    expect(screen.getByRole("status")).toHaveAttribute("aria-busy", "false");
  });

  it("marks the reply as an error when the server's own last chunk has finishReason \"error\", while still showing its safe message text", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([
        {
          conversationId: "conv-1",
          delta: "an error occurred while generating the response",
          finishReason: "error",
          usage: null,
        },
      ]),
    );
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    const reply = await screen.findByRole("status");
    await waitFor(() => expect(reply).toHaveTextContent("an error occurred while generating the response"));
    await waitFor(() => expect(reply).toHaveAttribute("aria-busy", "false"));
  });

  it("aborts an in-flight stream's AbortSignal when the panel unmounts", async () => {
    let capturedSignal: AbortSignal | undefined;
    const neverSettles = new Promise<never>(() => {});
    mockedStreamAssistantReply.mockImplementation(async function* (_id, _message, options) {
      capturedSignal = options?.signal;
      yield { conversationId: "conv-1", delta: "partial", finishReason: null, usage: null };
      await neverSettles;
    });
    const user = userEvent.setup();
    const { unmount } = render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));
    await screen.findByRole("status");

    expect(capturedSignal?.aborted).toBe(false);
    unmount();
    expect(capturedSignal?.aborted).toBe(true);
  });

  it("[chat:AC-38] seeds the transcript with the given initialMessages instead of starting empty", async () => {
    render(
      <ChatPanel
        conversationId="existing-conv"
        initialMessages={[
          { id: "h0", role: "user", content: "earlier question" },
          { id: "h1", role: "assistant", content: "earlier answer", streamStatus: "done" },
        ]}
      />,
    );

    await waitForReady();
    expect(screen.getByText("earlier question")).toBeInTheDocument();
    expect(screen.getByText("earlier answer")).toBeInTheDocument();
    // No network call was made to reconstruct this history -- it came in as a prop.
    expect(mockedStreamAssistantReply).not.toHaveBeenCalled();
  });

  it("does not seed the transcript when initialMessages is omitted (existing no-arg behavior unchanged)", async () => {
    render(<ChatPanel conversationId="existing-conv" />);

    await waitForReady();
    expect(screen.getByText("대화를 시작해보세요.")).toBeInTheDocument();
  });

  it("[chat:AC-39] forwards the given model prop to streamAssistantReply when sending", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null }]),
    );
    const user = userEvent.setup();
    render(<ChatPanel model="some/other-model" />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    await waitFor(() => expect(mockedStreamAssistantReply).toHaveBeenCalledTimes(1));
    expect(mockedStreamAssistantReply).toHaveBeenCalledWith(
      "conv-1",
      "hi",
      expect.objectContaining({ model: "some/other-model" }),
    );
  });

  it("sends with an undefined model when no model prop is given (existing no-arg behavior unchanged)", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null }]),
    );
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    await waitFor(() => expect(mockedStreamAssistantReply).toHaveBeenCalledTimes(1));
    expect(mockedStreamAssistantReply).toHaveBeenCalledWith("conv-1", "hi", expect.objectContaining({ model: undefined }));
  });

  it("[chat:AC-38] invokes onConversationCreated once a new conversation is auto-created", async () => {
    const onConversationCreated = jest.fn();
    render(<ChatPanel onConversationCreated={onConversationCreated} />);

    await waitForReady();

    expect(onConversationCreated).toHaveBeenCalledTimes(1);
    expect(onConversationCreated).toHaveBeenCalledWith(READY_CONVERSATION);
  });

  it("does not invoke onConversationCreated when a conversationId is already provided", async () => {
    const onConversationCreated = jest.fn();
    render(<ChatPanel conversationId="existing-conv" onConversationCreated={onConversationCreated} />);

    await waitForReady();

    expect(onConversationCreated).not.toHaveBeenCalled();
  });
});
