import fs from "node:fs";
import path from "node:path";
import { render } from "@testing-library/react";
import { StatusBadge, type DocumentStatus } from "./StatusBadge";

/**
 * [Purpose] design-system:AC-12/AC-13 -- verifies the badge's CSS references the correct
 *   `--app-status-*` token per state (Spec §2 mapping table) and that all 5 states render with
 *   distinct classes and distinct, non-empty Korean labels.
 * [Rationale] Jest never loads CSS in this project's config (`next/jest` stubs `*.css` -- Spec
 *   §6 판별력 주의 1, `className` values are opaque strings that would still equal a nonexistent
 *   class), so AC-12 (CSS source) and AC-13 (DOM) are kept as a deliberate pair -- a DOM-only
 *   assertion would pass even if the CSS referenced the wrong token or no token at all (Spec §8
 *   R6).
 * [AC] design-system:AC-12, design-system:AC-13
 */

const CSS_PATH = path.join(__dirname, "StatusBadge.module.css");
const css = fs.readFileSync(CSS_PATH, "utf8");

/** Every `.className { ... }` rule block in a CSS text blob, as [selectorText, body] pairs.
 *  Comments are stripped first so a future comment mentioning a class name (e.g. `.indexed`)
 *  can't be mistaken for a selector (review 2026-08-30 correctness, noted risk). Selector lists
 *  (`.a, .b { ... }`) stay together as one block, so a class shared across a combined selector
 *  is still found. (design-system:AC-12) */
function parseCssBlocks(text: string): Array<[string, string]> {
  const withoutComments = text.replace(/\/\*[\s\S]*?\*\//g, "");
  const blocks: Array<[string, string]> = [];
  const re = /([^{}]+)\{([^{}]*)\}/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(withoutComments)) !== null) blocks.push([m[1], m[2]]);
  return blocks;
}

/** True if some rule block whose selector list includes `.className` has `token` in its body
 *  (design-system:AC-12). */
function classReferencesToken(blocks: Array<[string, string]>, className: string, token: string): boolean {
  const classRe = new RegExp(`\\.${className}(?![\\w-])`);
  return blocks.some(([selector, body]) => classRe.test(selector) && body.includes(token));
}

describe("StatusBadge CSS references the Spec §2 mapping table's tokens", () => {
  const blocks = parseCssBlocks(css);

  it("[design-system:AC-12] pending/parsing/embedding/indexed/failed each reference their mapped --app-status-* token", () => {
    expect(classReferencesToken(blocks, "pending", "var(--app-status-neutral)")).toBe(true);
    expect(classReferencesToken(blocks, "parsing", "var(--app-status-active)")).toBe(true);
    expect(classReferencesToken(blocks, "embedding", "var(--app-status-active)")).toBe(true);
    expect(classReferencesToken(blocks, "indexed", "var(--app-status-ok)")).toBe(true);
    expect(classReferencesToken(blocks, "failed", "var(--app-status-danger)")).toBe(true);
    expect(classReferencesToken(blocks, "failed", "var(--app-status-danger-wash)")).toBe(true);
  });

  it("[design-system:AC-12] only FAILED references --app-status-danger -- the other 4 states don't inherit it from a shared block", () => {
    // AD-7's CSS spec gives FAILED the only background/danger declaration; the other 4 share a
    // base block (radius/padding/font) with FAILED's own selector, so a danger-wash regression
    // added to that shared block would go undetected by the positive-mapping test above alone
    // (review 2026-08-30 correctness Low-2).
    for (const name of ["pending", "parsing", "embedding", "indexed"]) {
      expect(classReferencesToken(blocks, name, "--app-status-danger")).toBe(false);
    }
  });

  it("[design-system:AC-12] the parser attributes a token to the class that actually declares it, not just \"exists somewhere in the file\" (positive control)", () => {
    // A synthetic file with two different classes/tokens -- if classReferencesToken ignored the
    // className argument (matched any block containing the token, regardless of selector), this
    // would still report `parsing`/`indexed` both true and `indexed`/`active` cross-matched
    // false. Also exercises the combined-selector shape (`.parsing, .embedding`) the real CSS
    // uses (review 2026-08-30 correctness Low-3).
    const synthetic = parseCssBlocks(
      ".parsing, .embedding { color: var(--app-status-active); } .indexed { color: var(--app-status-ok); }",
    );
    expect(classReferencesToken(synthetic, "parsing", "var(--app-status-active)")).toBe(true);
    expect(classReferencesToken(synthetic, "embedding", "var(--app-status-active)")).toBe(true);
    expect(classReferencesToken(synthetic, "indexed", "var(--app-status-ok)")).toBe(true);
    expect(classReferencesToken(synthetic, "indexed", "var(--app-status-active)")).toBe(false);
    expect(classReferencesToken(synthetic, "parsing", "var(--app-status-ok)")).toBe(false);
  });
});

describe("StatusBadge renders 5 distinct states", () => {
  it("[design-system:AC-13] each of the 5 statuses renders its own className and a different, non-empty text label", () => {
    const statuses: DocumentStatus[] = ["PENDING", "PARSING", "EMBEDDING", "INDEXED", "FAILED"];
    const rendered = statuses.map((status) => {
      const { container, unmount } = render(<StatusBadge status={status} />);
      const el = container.firstElementChild as HTMLElement;
      const result = { status, className: el.className, text: el.textContent };
      unmount();
      return result;
    });

    for (const r of rendered) {
      expect(r.text).not.toBe("");
      // Binds each status to its own CSS module key, not just "some className distinct from the
      // other four" -- `next/jest`'s CSS module proxy returns the accessed key verbatim, so this
      // pins the status->class selection itself (a swapped CLASS_NAMES entry, e.g. PENDING
      // rendering with the "indexed" class, would still leave all 5 classNames mutually
      // distinct and slip past a Set-size-5 check alone; review 2026-08-30 correctness Low-1).
      expect(r.className).toBe(r.status.toLowerCase());
    }
    const classNames = rendered.map((r) => r.className);
    expect(new Set(classNames).size).toBe(5);
    const texts = rendered.map((r) => r.text);
    expect(new Set(texts).size).toBe(5);
  });

  // Untagged, not [design-system:AC-13] -- AD-7a is explicit that no AC judges `data-status`
  // ("어떤 AC 도 이 속성을 판정하지 않는다") and that the label text AD-9 chose is free to change.
  // A test tagged AC-13 that queries by the Korean label would turn either an AD-7a-sanctioned
  // removal of data-status or an AD-9-sanctioned label wording change into a false AC-13
  // regression -- the exact "misattributed regression" AD-7a exists to prevent, reproduced
  // inside this same file (review 2026-08-30 correctness Low-4).
  it("exposes data-status on every state as a wording-independent anchor for consumers (Spec AD-7a)", () => {
    const statuses: DocumentStatus[] = ["PENDING", "PARSING", "EMBEDDING", "INDEXED", "FAILED"];
    for (const status of statuses) {
      const { container, unmount } = render(<StatusBadge status={status} />);
      expect(container.firstElementChild).toHaveAttribute("data-status", status);
      unmount();
    }
  });
});
