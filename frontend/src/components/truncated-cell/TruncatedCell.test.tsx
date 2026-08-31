import fs from "node:fs";
import path from "node:path";
import { render } from "@testing-library/react";
import { TruncatedCell, ErrorMessageCell } from "./TruncatedCell";

/**
 * [Purpose] design-system:AC-14/AC-15 -- verifies a long, whitespace-free `filename` is
 *   rendered with the truncation class and its full original text preserved losslessly in
 *   `title`, and that the truncation CSS actually contains the declarations required to clip
 *   it (Spec §Tech Review AD-8), with `error_message`'s cell kept on a distinctly-named class.
 * [Rationale] Jest never loads CSS (`next/jest` stubs `*.css` -- Spec §6 판별력 주의 1;
 *   `offsetWidth` is also unusable -- jsdom has no layout engine, Spec §8 D4), so AC-14 (DOM)
 *   and AC-15 (CSS source) are kept as a deliberate pair, same as `StatusBadge.test.tsx`.
 * [AC] design-system:AC-14, design-system:AC-15
 */

const CSS_PATH = path.join(__dirname, "TruncatedCell.module.css");
const css = fs.readFileSync(CSS_PATH, "utf8");

/** Every `.className { ... }` rule block in a CSS text blob, as [selectorText, body] pairs.
 *  Comments are stripped first (design-system:AC-15). */
function parseCssBlocks(text: string): Array<[string, string]> {
  const withoutComments = text.replace(/\/\*[\s\S]*?\*\//g, "");
  const blocks: Array<[string, string]> = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(withoutComments)) !== null) blocks.push([m[1], m[2]]);
  return blocks;
}

/** The combined declaration body of every rule block whose selector list includes
 *  `.className` (design-system:AC-15). */
function bodyForClass(blocks: Array<[string, string]>, className: string): string {
  const classRe = new RegExp(`\\.${className}(?![\\w-])`);
  return blocks
    .filter(([selector]) => classRe.test(selector))
    .map(([, body]) => body)
    .join(" ");
}

// A no-space 255-character Korean string -- the exact shape Spec §2/AC-14 names (documents
// .filename, up to 255 chars, no whitespace guaranteed).
const LONG_FILENAME = "가".repeat(255);

describe("TruncatedCell renders the truncation class with the full original text preserved", () => {
  it("[design-system:AC-14] a 255-char whitespace-free Korean filename gets the truncated class, and title carries the original losslessly", () => {
    const { container } = render(<TruncatedCell text={LONG_FILENAME} />);
    const el = container.firstElementChild as HTMLElement;

    // Binds the class itself, not just "some class is present" -- next/jest's CSS module
    // proxy returns the accessed key verbatim (same technique as StatusBadge.test.tsx, review
    // 2026-08-30 correctness Low-1 on that file, applied here proactively).
    expect(el.className).toBe("truncated");
    expect(el.getAttribute("title")).toBe(LONG_FILENAME);
    expect(el.getAttribute("title")).toHaveLength(255);
    expect(el.textContent).toBe(LONG_FILENAME);
  });

  it("[design-system:AC-14] a short filename is preserved exactly too (not a 255-specific special case)", () => {
    const { container } = render(<TruncatedCell text="회사소개.pdf" />);
    const el = container.firstElementChild as HTMLElement;
    expect(el.getAttribute("title")).toBe("회사소개.pdf");
    expect(el.className).toBe("truncated");
  });
});

describe("ErrorMessageCell renders on its own, differently-named class", () => {
  it("[design-system:AC-15] ErrorMessageCell binds the errorMessage class, not truncated", () => {
    const { container } = render(<ErrorMessageCell text="Unsupported file type" />);
    const el = container.firstElementChild as HTMLElement;
    expect(el.className).toBe("errorMessage");
    expect(el.textContent).toBe("Unsupported file type");
  });

  // Untagged, not [design-system:AC-15] -- neither AC-15 nor AD-7 requires or forbids a
  // `title` on ErrorMessageCell (AD-7's "title carries the original losslessly" requirement is
  // TruncatedCell-only). A future T11 tooltip addition here would be a Spec-permitted change,
  // and an AC-15-tagged test on this fact would misreport it as a design-system regression --
  // the same misattribution shape D5's review Low-4 found on `data-status` (review 2026-08-30
  // correctness Low-3 on this file). This test records current behavior, not an AC.
  it("ErrorMessageCell has no title attribute (current behavior, not an AC requirement)", () => {
    const { container } = render(<ErrorMessageCell text="Unsupported file type" />);
    expect(container.firstElementChild).not.toHaveAttribute("title");
  });
});

describe("TruncatedCell's CSS actually contains the containment declarations AD-8 requires", () => {
  const blocks = parseCssBlocks(css);
  const truncatedBody = bodyForClass(blocks, "truncated");
  const errorMessageBody = bodyForClass(blocks, "errorMessage");

  it("[design-system:AC-15] .truncated has all 5 declarations AD-8 fixes (span needs display:block for overflow:hidden to clip at all)", () => {
    expect(truncatedBody).toMatch(/display:\s*block/);
    // max-width: 100% is the declaration AD-8(c) names as this Spec's own boundary --
    // everything past it (column width) is document-ingestion T11's job. Omitted from an
    // earlier version of this test (review 2026-08-30 correctness Low-1); this is the one
    // declaration whose absence wouldn't just look wrong, it would blur exactly the line AD-8
    // draws between this card and T11's.
    expect(truncatedBody).toMatch(/max-width:\s*100%/);
    expect(truncatedBody).toMatch(/overflow:\s*hidden/);
    expect(truncatedBody).toMatch(/white-space:\s*nowrap/);
    expect(truncatedBody).toMatch(/text-overflow:\s*ellipsis/);
  });

  it("[design-system:AC-15] .errorMessage is a genuinely different class, not an alias for .truncated", () => {
    // Same test-hygiene lesson as StatusBadge's AC-12 Low-2 (review 2026-08-30): check the
    // negative, not just that .errorMessage has its own declarations. If .errorMessage ever
    // inherited .truncated's clipping (e.g. a future shared base-class refactor), an error
    // message -- meant to be read in full -- would silently start clipping.
    expect(truncatedBody).not.toBe(errorMessageBody);
    expect(errorMessageBody).not.toMatch(/overflow:\s*hidden/);
    expect(errorMessageBody).not.toMatch(/text-overflow:\s*ellipsis/);
    // A CSS Modules `composes: truncated;` would add none of the text this function inspects
    // (the composed rules live in .truncated's own block, not copied into .errorMessage's
    // body), so the two `not.toMatch` checks above would pass right through it -- this pins
    // the specific escape hatch the checks above can't see (review 2026-08-30 correctness
    // Low-2).
    expect(errorMessageBody).not.toMatch(/composes\s*:/);
    expect(errorMessageBody).toMatch(/white-space:\s*normal/);
    expect(errorMessageBody).toMatch(/overflow-wrap:\s*anywhere/);
    expect(errorMessageBody).toMatch(/var\(--app-status-danger\)/);
  });

  it("[design-system:AC-15] the class-body lookup actually attributes declarations to the right class (positive control)", () => {
    const synthetic = parseCssBlocks(".truncated { overflow: hidden; } .errorMessage { color: red; }");
    expect(bodyForClass(synthetic, "truncated")).toMatch(/overflow:\s*hidden/);
    expect(bodyForClass(synthetic, "truncated")).not.toMatch(/color:\s*red/);
    expect(bodyForClass(synthetic, "errorMessage")).toMatch(/color:\s*red/);
  });
});
