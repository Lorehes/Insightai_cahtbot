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
      singleChunkReply([{ conversationId: "conv-1", delta: "hi back", finishReason: "STOP", usage: null, sources: null }]),
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
      yield { conversationId: "conv-1", delta: "안", finishReason: null, usage: null, sources: null };
      await gate.promise;
      yield { conversationId: "conv-1", delta: "녕하세요", finishReason: null, usage: null, sources: null };
      yield { conversationId: "conv-1", delta: "!", finishReason: "STOP", usage: null, sources: null };
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
        { conversationId: "conv-1", delta: "## heading\n\n- item", finishReason: null, usage: null, sources: null },
        { conversationId: "conv-1", delta: "\n\ndone", finishReason: "STOP", usage: null, sources: null },
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
      yield { conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null, sources: null };
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
      singleChunkReply([{ conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null, sources: null }]),
    );
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "shift line{Shift>}{Enter}{/Shift}");
    expect(mockedStreamAssistantReply).not.toHaveBeenCalled();

    await user.type(textbox, "{Enter}");
    await waitFor(() => expect(mockedStreamAssistantReply).toHaveBeenCalledTimes(1));
  });

  // Body replaced by card T10 (chat-prototype-port:AC-19, Spec §Tech Review AD-9/R-2) --
  // the old "(연결이 끊어졌습니다)" content-suffix assertion is gone (the suffix itself was
  // removed from ChatPanel.tsx), but the test's own name is kept per Spec §8 R-2/§Tech
  // Review "그 밖의 리스크" 2.
  it("marks the reply as an error when the stream throws mid-turn (not an intentional abort)", async () => {
    mockedStreamAssistantReply.mockImplementation(async function* () {
      yield { conversationId: "conv-1", delta: "partial", finishReason: null, usage: null, sources: null };
      throw new Error("network dropped");
    });
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    // The already-arrived text stays on screen, unmodified (Spec §8 R-5) -- no suffix, no
    // rewrite.
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("partial"));
    expect(screen.getByRole("status")).toHaveAttribute("aria-busy", "false");
    // No sources were ever received before the throw -- AC-21 clause ③'s failure text is
    // accurate here too, alongside the error card (see ChatPanel.tsx's own [Caution] on this
    // catch branch).
    expect(screen.getByText("출처를 불러오지 못했습니다.")).toBeInTheDocument();
  });

  // review 2026-08-31 round-2 correctness Low-2: the catch branch must not discard a sources
  // array that a chunk already delivered before a later chunk throws.
  // Body replaced by card T10 (same reason as the test above) -- name kept, the
  // "(연결이 끊어졌습니다)" assertion is gone.
  it("keeps sources already received before a mid-stream throw, instead of discarding them to the clause ③ failure screen", async () => {
    mockedStreamAssistantReply.mockImplementation(async function* () {
      yield {
        conversationId: "conv-1",
        delta: "partial",
        finishReason: null,
        usage: null,
        sources: [{ documentId: "d1", filename: "a.pdf", score: 0.01, snippet: "s", page: null }],
      };
      throw new Error("network dropped");
    });
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("partial"));
    expect(screen.getByText("1건")).toBeInTheDocument();
    expect(screen.queryByText("출처를 불러오지 못했습니다.")).not.toBeInTheDocument();
  });

  it("marks the reply as an error when the server's own last chunk has finishReason \"error\", while still showing its safe message text", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([
        {
          conversationId: "conv-1",
          delta: "an error occurred while generating the response",
          finishReason: "error",
          usage: null, sources: null,
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

  it("[chat-prototype-port:AC-17] shows exactly one 생성 중단 button while streaming, and swaps back to 전송 once the stream ends", async () => {
    const gate = createGate();
    mockedStreamAssistantReply.mockImplementation(async function* () {
      await gate.promise;
      yield { conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null, sources: null };
    });
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    // Idle: one 전송 button, zero 생성 중단 buttons.
    expect(screen.getByRole("button", { name: "전송" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "생성 중단" })).not.toBeInTheDocument();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));

    // Streaming: the reverse -- zero 전송 buttons, one 생성 중단 button.
    await waitFor(() => expect(screen.getByRole("button", { name: "생성 중단" })).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "전송" })).not.toBeInTheDocument();

    gate.release();

    // Stream ends: back to the idle state -- the reverse condition, asserted explicitly (Spec
    // §6 "테스트 함정": both directions of the swap must be checked, not just one).
    await waitFor(() => expect(screen.getByRole("button", { name: "전송" })).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "생성 중단" })).not.toBeInTheDocument();
  });

  it("[chat-prototype-port:AC-18] clicking 생성 중단 aborts the AbortSignal, keeps the text already received, re-enables the input, and does not mis-render the citation delivery-failure box", async () => {
    let capturedSignal: AbortSignal | undefined;
    mockedStreamAssistantReply.mockImplementation(async function* (_id, _message, options) {
      capturedSignal = options?.signal;
      yield { conversationId: "conv-1", delta: "partial answer", finishReason: null, usage: null, sources: null };
      // Mirrors `lib/api/messages.ts`'s real `fetch(..., { signal })` contract: aborting the
      // signal rejects the in-flight read with a `DOMException("AbortError")`, which is what
      // propagates out of the `for await` loop in `ChatPanel.handleSend` as a thrown error.
      await new Promise<never>((_resolve, reject) => {
        options?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
      });
    });
    const user = userEvent.setup();
    render(<ChatPanel />);
    const textbox = await waitForReady();

    await user.type(textbox, "hi");
    await user.click(screen.getByRole("button", { name: "전송" }));
    await screen.findByText("partial answer");
    expect(capturedSignal?.aborted).toBe(false);

    await user.click(screen.getByRole("button", { name: "생성 중단" }));

    // (a) the signal was actually aborted.
    await waitFor(() => expect(capturedSignal?.aborted).toBe(true));
    // (b) the text that already arrived stays on screen, unmodified -- no suffix/rewrite.
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("partial answer"));
    expect(screen.getByRole("status")).toHaveAttribute("aria-busy", "false");
    expect(screen.queryByTestId("stream-caret")).not.toBeInTheDocument();
    // (c) the input is re-enabled and back to its idle 전송 state.
    await waitFor(() => expect(textbox).toBeEnabled());
    expect(screen.getByRole("button", { name: "전송" })).toBeInTheDocument();
    // (d) the core bug AD-4 exists to prevent: a user-initiated stop must not be mis-rendered
    // as clause ③'s delivery-failure box (it would be if `sources` stayed `null` instead of
    // becoming `undefined`).
    expect(screen.queryByText("출처를 불러오지 못했습니다.")).not.toBeInTheDocument();
  });

  it("aborts an in-flight stream's AbortSignal when the panel unmounts", async () => {
    let capturedSignal: AbortSignal | undefined;
    const neverSettles = new Promise<never>(() => {});
    mockedStreamAssistantReply.mockImplementation(async function* (_id, _message, options) {
      capturedSignal = options?.signal;
      yield { conversationId: "conv-1", delta: "partial", finishReason: null, usage: null, sources: null };
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
    // Body updated by T7 (chat-prototype-port AC-12 replaces the old placeholder sentence,
    // Spec §Tech Review AD-2) -- this test's own name is unchanged, it still checks "starts
    // empty, nothing seeded".
    expect(screen.getByText("무엇을 찾아드릴까요?")).toBeInTheDocument();
  });

  it("[chat-prototype-port:AC-14] clicking an example chip sends that chip's exact label, not whatever is in the draft textarea", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null, sources: null }]),
    );
    const user = userEvent.setup();
    render(<ChatPanel conversationId="existing-conv" />);
    await waitForReady();

    // Spec §6 discriminating-power requirement 4 -- literal re-typed here, not read off a
    // component-exported constant.
    await user.click(screen.getByRole("button", { name: "회사 주소가 어디인가요?" }));

    await waitFor(() => expect(mockedStreamAssistantReply).toHaveBeenCalledTimes(1));
    // Character-for-character equality against the raw second positional argument, not a
    // substring/regex match (Spec §6 판별력 요건: "정규화 후 비교 금지").
    expect(mockedStreamAssistantReply.mock.calls[0][1]).toBe("회사 주소가 어디인가요?");
  });

  it("[chat:AC-39] forwards the given model prop to streamAssistantReply when sending", async () => {
    mockedStreamAssistantReply.mockImplementation(() =>
      singleChunkReply([{ conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null, sources: null }]),
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
      singleChunkReply([{ conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null, sources: null }]),
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

  it("[chat-prototype-port:AC-21] the composer textarea has the fixed placeholder text, even while the conversation is still being created", async () => {
    let resolveCreate!: (value: typeof READY_CONVERSATION) => void;
    mockedCreateConversation.mockReset();
    mockedCreateConversation.mockReturnValue(new Promise((resolve) => (resolveCreate = resolve)));

    render(<ChatPanel />);

    // Char-for-char match required (Spec §4 header note) -- literal `+`, and the space before
    // "로" that the old conditional "준비 중" placeholder never had to get right.
    expect(screen.getByRole("textbox")).toHaveAttribute(
      "placeholder",
      "사내 문서에 대해 질문하세요. Shift + Enter 로 줄바꿈.",
    );

    resolveCreate(READY_CONVERSATION);
    await waitForReady();

    // Same literal string once "creating" resolves to "ready" -- there is no longer a second,
    // conditional placeholder variant (AC-7's retired "대화 준비 중..." branch).
    expect(screen.getByRole("textbox")).toHaveAttribute(
      "placeholder",
      "사내 문서에 대해 질문하세요. Shift + Enter 로 줄바꿈.",
    );
  });

  it("[chat-prototype-port:AC-23] renders the fixed disclaimer below the composer regardless of RAG/streaming state", async () => {
    render(<ChatPanel ragEnabled={false} />);
    await waitForReady();

    expect(
      screen.getByText("답변은 사내 문서를 근거로 생성됩니다. 중요한 결정 전에는 원문을 확인하세요."),
    ).toBeInTheDocument();
  });

  describe("[chat-prototype-port:AC-22] composer hint text", () => {
    it("shows the RAG ON hint when idle and ragEnabled is true", async () => {
      render(<ChatPanel ragEnabled={true} />);
      await waitForReady();

      expect(screen.getByText("문서 근거 ON · 사내 문서에서 검색한 뒤 답변합니다")).toBeInTheDocument();
    });

    it("shows the RAG OFF hint when idle and ragEnabled is false", async () => {
      render(<ChatPanel ragEnabled={false} />);
      await waitForReady();

      expect(screen.getByText("문서 근거 OFF · 모델의 일반 지식만으로 답변합니다")).toBeInTheDocument();
    });

    it("prioritizes the streaming hint over the RAG hint while a reply is in flight, then reverts once it finishes", async () => {
      const gate = createGate();
      mockedStreamAssistantReply.mockImplementation(async function* () {
        await gate.promise;
        yield { conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null, sources: null };
      });
      const user = userEvent.setup();
      render(<ChatPanel ragEnabled={true} />);
      const textbox = await waitForReady();

      expect(screen.getByText("문서 근거 ON · 사내 문서에서 검색한 뒤 답변합니다")).toBeInTheDocument();

      await user.type(textbox, "hi");
      await user.click(screen.getByRole("button", { name: "전송" }));

      await waitFor(() =>
        expect(
          screen.getByText("응답을 스트리밍 중입니다 · 입력이 잠시 비활성화됩니다"),
        ).toBeInTheDocument(),
      );
      expect(screen.queryByText("문서 근거 ON · 사내 문서에서 검색한 뒤 답변합니다")).not.toBeInTheDocument();

      gate.release();

      await waitFor(() =>
        expect(screen.getByText("문서 근거 ON · 사내 문서에서 검색한 뒤 답변합니다")).toBeInTheDocument(),
      );
      expect(
        screen.queryByText("응답을 스트리밍 중입니다 · 입력이 잠시 비활성화됩니다"),
      ).not.toBeInTheDocument();
    });
  });

  describe("citation area wiring (hybrid-rag Spec T15, AC-21)", () => {
    it("[hybrid-rag:AC-21] clause ① shows no citation area while chunks with sources: null are still arriving", async () => {
      const gate = createGate();
      mockedStreamAssistantReply.mockImplementation(async function* () {
        yield { conversationId: "conv-1", delta: "partial", finishReason: null, usage: null, sources: null };
        await gate.promise;
        yield {
          conversationId: "conv-1",
          delta: "!",
          finishReason: "STOP",
          usage: null,
          sources: [{ documentId: "d1", filename: "a.pdf", score: 0.01, snippet: "s", page: null }],
        };
      });
      const user = userEvent.setup();
      render(<ChatPanel />);
      const textbox = await waitForReady();

      await user.type(textbox, "hi");
      await user.click(screen.getByRole("button", { name: "전송" }));

      await screen.findByText("partial");
      expect(screen.queryByText("출처")).not.toBeInTheDocument();

      gate.release();

      expect(await screen.findByText("출처")).toBeInTheDocument();
      expect(screen.getByText("1건")).toBeInTheDocument();
    });

    it("[hybrid-rag:AC-21] clause ② renders the AC-20 empty-sources screen when the terminal chunk confirms an empty array", async () => {
      mockedStreamAssistantReply.mockImplementation(() =>
        singleChunkReply([
          { conversationId: "conv-1", delta: "no evidence found", finishReason: "STOP", usage: null, sources: [] },
        ]),
      );
      const user = userEvent.setup();
      render(<ChatPanel />);
      const textbox = await waitForReady();

      await user.type(textbox, "hi");
      await user.click(screen.getByRole("button", { name: "전송" }));

      expect(await screen.findByText("0건")).toBeInTheDocument();
      expect(screen.getByText("이 답변에는 근거 문서가 없습니다.")).toBeInTheDocument();
    });

    it("[hybrid-rag:AC-21] clause ③ shows the delivery-failure message when every chunk (including the terminal one) carries sources: null (BL-019)", async () => {
      mockedStreamAssistantReply.mockImplementation(() =>
        singleChunkReply([
          { conversationId: "conv-1", delta: "answer text", finishReason: "STOP", usage: null, sources: null },
        ]),
      );
      const user = userEvent.setup();
      render(<ChatPanel />);
      const textbox = await waitForReady();

      await user.type(textbox, "hi");
      await user.click(screen.getByRole("button", { name: "전송" }));

      expect(await screen.findByText("출처를 불러오지 못했습니다.")).toBeInTheDocument();
      expect(screen.queryByText("이 답변에는 근거 문서가 없습니다.")).not.toBeInTheDocument();
    });

    it("uses the last non-null sources value seen across the stream, not just the terminal chunk's own value", async () => {
      mockedStreamAssistantReply.mockImplementation(async function* () {
        yield {
          conversationId: "conv-1",
          delta: "partial",
          finishReason: null,
          usage: null,
          sources: [{ documentId: "d1", filename: "a.pdf", score: 0.01, snippet: "s", page: null }],
        };
        // Mirrors the real backend contract: every chunk after the one that carried sources
        // (including the terminal one) carries `sources: null` again -- the assistant message
        // must keep the earlier non-null value, not overwrite it back to null.
        yield { conversationId: "conv-1", delta: "!", finishReason: "STOP", usage: null, sources: null };
      });
      const user = userEvent.setup();
      render(<ChatPanel />);
      const textbox = await waitForReady();

      await user.type(textbox, "hi");
      await user.click(screen.getByRole("button", { name: "전송" }));

      expect(await screen.findByText("1건")).toBeInTheDocument();
      expect(screen.queryByText("출처를 불러오지 못했습니다.")).not.toBeInTheDocument();
    });

    // review 2026-08-31 High-2: the earlier static-render version of this assertion (in
    // MessageList.test.tsx) never actually sent a second turn or exercised ChatPanel's own
    // state update -- it could not have caught a bug where completing turn 2 clobbers turn 1's
    // sources (e.g. a `setMessages` call that touches every message instead of only the one
    // being updated). This test drives a real second `handleSend` call through the same
    // `ChatPanel` instance, per Spec §6 F5 ("1턴 완료 후 2턴 스트리밍").
    it("[hybrid-rag:AC-22] keeps turn 1's sources visible and unchanged while turn 2 is actually streaming", async () => {
      const turn2Gate = createGate();
      mockedStreamAssistantReply
        .mockImplementationOnce(() =>
          singleChunkReply([
            {
              conversationId: "conv-1",
              delta: "first answer",
              finishReason: "STOP",
              usage: null,
              sources: [{ documentId: "d1", filename: "turn1.pdf", score: 0.01, snippet: "s", page: null }],
            },
          ]),
        )
        .mockImplementationOnce(async function* () {
          yield { conversationId: "conv-1", delta: "second partial", finishReason: null, usage: null, sources: null };
          await turn2Gate.promise;
          yield { conversationId: "conv-1", delta: "!", finishReason: "STOP", usage: null, sources: [] };
        });
      const user = userEvent.setup();
      render(<ChatPanel />);
      const textbox = await waitForReady();

      await user.type(textbox, "first question");
      await user.click(screen.getByRole("button", { name: "전송" }));
      await screen.findByText("turn1.pdf");
      const turn1Row = screen.getByText("first answer").closest("li")!;

      await waitFor(() => expect(textbox).toBeEnabled());
      await user.type(textbox, "second question");
      await user.click(screen.getByRole("button", { name: "전송" }));
      await screen.findByText("second partial");

      // Turn 2 is still streaming -- turn 1's own row must be untouched.
      expect(within(turn1Row).getByText("1건")).toBeInTheDocument();
      expect(within(turn1Row).getByText("turn1.pdf")).toBeInTheDocument();
      const turn2Row = screen.getByText(/second partial/).closest("li")!;
      expect(within(turn2Row).queryByText("출처")).not.toBeInTheDocument();

      turn2Gate.release();

      await waitFor(() => expect(within(turn2Row).getByText("0건")).toBeInTheDocument());
      // Turn 1 still unaffected after turn 2 resolves.
      expect(within(turn1Row).getByText("1건")).toBeInTheDocument();
      expect(within(turn1Row).getByText("turn1.pdf")).toBeInTheDocument();
    });
  });

  describe("error card + retry (card T10)", () => {
    it("[chat-prototype-port:AC-19] renders a role=alert error card with the fixed title and a 다시 시도 button when the stream fails, and 0 of the old inline error sentence", async () => {
      mockedStreamAssistantReply.mockImplementation(async function* () {
        yield { conversationId: "conv-1", delta: "partial", finishReason: null, usage: null, sources: null };
        throw new Error("network dropped");
      });
      const user = userEvent.setup();
      render(<ChatPanel />);
      const textbox = await waitForReady();

      await user.type(textbox, "hi");
      await user.click(screen.getByRole("button", { name: "전송" }));

      const errorCard = await screen.findByRole("alert");
      expect(within(errorCard).getByText("응답을 받지 못했습니다")).toBeInTheDocument();
      expect(within(errorCard).getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
      // INC-013 absence proof -- the old inline sentence must be gone entirely, not merely
      // coexisting alongside the new card.
      expect(screen.queryByText("메시지를 가져오지 못했습니다.")).not.toBeInTheDocument();
      // Already-arrived text stays on screen (Spec §8 R-5), not replaced by the card.
      expect(screen.getByText("partial")).toBeInTheDocument();
    });

    it("[chat-prototype-port:AC-19] still renders the error card when nothing streamed before the failure (empty content)", async () => {
      mockedStreamAssistantReply.mockImplementation(async function* () {
        throw new Error("network dropped");
      });
      const user = userEvent.setup();
      render(<ChatPanel />);
      const textbox = await waitForReady();

      await user.type(textbox, "hi");
      await user.click(screen.getByRole("button", { name: "전송" }));

      const errorCard = await screen.findByRole("alert");
      expect(within(errorCard).getByText("응답을 받지 못했습니다")).toBeInTheDocument();
      expect(screen.queryByText("메시지를 가져오지 못했습니다.")).not.toBeInTheDocument();
    });

    it("[chat-prototype-port:AC-20] clicking 다시 시도 resends the exact same question on the same conversationId as a new turn, without removing the failed turn", async () => {
      mockedStreamAssistantReply
        .mockImplementationOnce(async function* () {
          yield { conversationId: "conv-1", delta: "", finishReason: null, usage: null, sources: null };
          throw new Error("network dropped");
        })
        .mockImplementationOnce(() =>
          singleChunkReply([
            { conversationId: "conv-1", delta: "second try ok", finishReason: "STOP", usage: null, sources: null },
          ]),
        );
      const user = userEvent.setup();
      render(<ChatPanel />);
      const textbox = await waitForReady();

      await user.type(textbox, "hi");
      await user.click(screen.getByRole("button", { name: "전송" }));

      const errorCard = await screen.findByRole("alert");
      await user.click(within(errorCard).getByRole("button", { name: "다시 시도" }));

      await waitFor(() => expect(mockedStreamAssistantReply).toHaveBeenCalledTimes(2));
      // Same `conversationId`/`message` arguments on both calls (AC-20's own wording).
      expect(mockedStreamAssistantReply.mock.calls[1][0]).toBe(mockedStreamAssistantReply.mock.calls[0][0]);
      expect(mockedStreamAssistantReply.mock.calls[1][1]).toBe(mockedStreamAssistantReply.mock.calls[0][1]);
      expect(mockedStreamAssistantReply.mock.calls[1][1]).toBe("hi");

      await screen.findByText("second try ok");
      // The failed turn is not deleted from the transcript -- "hi" now appears twice (the
      // original failed turn's own user message, plus the retried turn's).
      expect(screen.getAllByText("hi")).toHaveLength(2);
    });

    it("[chat-prototype-port:AC-20] disables the 다시 시도 button while a retried turn is streaming", async () => {
      const gate = createGate();
      mockedStreamAssistantReply
        .mockImplementationOnce(async function* () {
          yield { conversationId: "conv-1", delta: "partial", finishReason: null, usage: null, sources: null };
          throw new Error("network dropped");
        })
        .mockImplementationOnce(async function* () {
          await gate.promise;
          yield { conversationId: "conv-1", delta: "ok", finishReason: "STOP", usage: null, sources: null };
        });
      const user = userEvent.setup();
      render(<ChatPanel />);
      const textbox = await waitForReady();

      await user.type(textbox, "hi");
      await user.click(screen.getByRole("button", { name: "전송" }));

      const errorCard = await screen.findByRole("alert");
      const retryButton = within(errorCard).getByRole("button", { name: "다시 시도" });
      expect(retryButton).toBeEnabled();

      await user.click(retryButton);

      await waitFor(() => expect(retryButton).toBeDisabled());

      gate.release();

      await waitFor(() => expect(retryButton).toBeEnabled());
    });
  });
});
