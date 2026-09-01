import fs from "node:fs";
import path from "node:path";

/**
 * [Purpose] chat-prototype-port:AC-27 -- verifies that the chat screen's own CSS modules
 *   (`ChatWorkspace`, `ConversationSidebar`, `ChatPanel`, `MessageList`, `ModelSelector`, `page`)
 *   contain zero unexplained padding/margin/gap/inset/width/height length literals, i.e. every
 *   spacing/sizing declaration is either `var(--app-*)`, the literal `0`, a named fixed-control-
 *   dimension exception, or a unit this token scale cannot represent (percentage/rem/em/vh/...).
 *   chat-prototype-port:AC-40 -- verifies the 3 ported keyframe animations (`ic-caret`,
 *   `ic-shimmer`, `ic-spin`) each have a matching stop rule inside a same-file
 *   `@media (prefers-reduced-motion: reduce)` block, for every selector that uses them.
 * [Rationale] Spec Tech Review AD-1 scopes this file deliberately narrower than
 *   `app/design-tokens.test.ts`'s AC-10/AC-11 scanners: color and border-radius/font-size
 *   literals are already checked there for every `*.module.css` under `src/**`, so re-asserting
 *   them here would create split-evidence (same fact asserted in two places, one silently going
 *   stale -- `dom-contract.test.tsx`'s own [Caution] names this risk). This file's scan property
 *   set is therefore exactly AD-1's list: padding/margin/gap/inset/width/height (and their
 *   longhands), nothing else -- border/outline/border-radius/font-size/color are out of scope by
 *   design, not by oversight.
 *
 *   The target file list is also deliberately the fixed 6 named in AD-1, not a `src/**` walk --
 *   AC-27 is chat-screen-specific ("채팅 화면 CSS 모듈"), a narrower claim than AC-10/AC-11's
 *   "every `*.module.css`". T11/T12 (the model popover cards) did not create a separate
 *   `ModelPopover.module.css`; the popover's styles live in `ModelSelector.module.css` itself
 *   (confirmed 2026-09-01 by listing `components/model-selector/`), so that file alone covers
 *   "신규 팝오버 관련 모듈" -- there is no 7th file to add.
 *
 *   Percentages, `rem`, `em`, `vh`/`vw` and friends are excluded from the spacing scan on
 *   purpose, not merely tolerated: this repo's spacing/sizing scale (`--app-gap-*`, fixed control
 *   px dimensions) is entirely px-based, so a percentage (a proportion, not a length -- CSS
 *   Values §6.1 distinguishes `<percentage>` from `<length>`) or a viewport/font-relative unit
 *   cannot be expressed as one of those tokens without changing its meaning. This mirrors
 *   reasoning already written inline where these values occur --
 *   `MessageList.module.css`'s `.userBubble` comment calls `max-width: 76%` "a proportion, not a
 *   length scale value" and its `.streamCaret` comment calls `line-height`'s unitless ratio
 *   "not a length, so out of scope for ... T13's future padding/margin/gap/inset/width/height
 *   scan" -- this file's scope decision is the literal fulfillment of that forward reference.
 *
 *   The remaining px literals (`SPACING_LITERAL_EXCEPTIONS` below) were measured by hand against
 *   every `*.module.css` file in the 6-file target list (2026-09-01, after T3-T12 landed --
 *   supersedes AD-1's pre-implementation 16-literal count, which only covered border/outline
 *   widths). Each is a fixed UI control dimension with no exact `--app-gap-*` match (the design
 *   token set is Done/closed, so no new step can be added to fit it) or the repo's existing
 *   visually-hidden idiom (`width:1px; height:1px; margin:-1px`, already treated as literal by
 *   convention -- `ChatPanel.module.css`/`MessageList.module.css`'s `.visuallyHidden`). None are
 *   color, so widening this list never overlaps AC-10's scope. Per `frontend.jsonl`'s T6 entry
 *   (follow_up addressed to "T13 담당자"), `.streamCaret`'s `width`/`margin-left: 2px` were
 *   flagged in advance as this file's responsibility to judge -- included here as a legitimate
 *   exception (same "too small for the 4px step to represent without visibly changing the
 *   design" reasoning the repo's own border/outline convention already uses for a different
 *   property), not tokenized, since no `--app-gap-*` step below 4px exists.
 * [Caution] `LITERAL_EXCEPTIONS.size`-style array assertions (`design-tokens.test.ts:220-223`
 *   precedent) guard both allow lists here from growing silently -- a future addition must edit
 *   this file's size assertion in the same diff, making the growth visible in review. The
 *   `SPACING_LITERAL_EXCEPTIONS` match is exact-string (`"prop: value"`), not a bare value set --
 *   `width: 31px` is not accidentally forgiven just because `width: 30px` is (see this file's own
 *   "not fuzzy-matched" regression guard test).
 * [AC] chat-prototype-port:AC-27, chat-prototype-port:AC-40
 */

const COMPONENTS_DIR = __dirname;
const APP_DIR = path.join(__dirname, "..", "app");

const TARGET_FILES: Array<{ name: string; filePath: string }> = [
  {
    name: "ChatWorkspace.module.css",
    filePath: path.join(COMPONENTS_DIR, "chat-workspace", "ChatWorkspace.module.css"),
  },
  {
    name: "ConversationSidebar.module.css",
    filePath: path.join(COMPONENTS_DIR, "conversation-sidebar", "ConversationSidebar.module.css"),
  },
  {
    name: "ChatPanel.module.css",
    filePath: path.join(COMPONENTS_DIR, "chat-panel", "ChatPanel.module.css"),
  },
  {
    name: "MessageList.module.css",
    filePath: path.join(COMPONENTS_DIR, "chat-panel", "MessageList.module.css"),
  },
  {
    name: "ModelSelector.module.css",
    filePath: path.join(COMPONENTS_DIR, "model-selector", "ModelSelector.module.css"),
  },
  { name: "page.module.css", filePath: path.join(APP_DIR, "page.module.css") },
];

/** Strips `/* ... *\/` comments -- this file's CSS modules document their rounding decisions
 *  extensively inline, and several of those comments contain literal `prop: value`-shaped text
 *  (e.g. "height: 38px` and the 9px- scale..."), which would otherwise false-positive against
 *  the declaration regexes below. */
function stripCssComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, "");
}

// ---------------------------------------------------------------------------------------------
// AC-27 -- spacing/sizing literal scanner
// ---------------------------------------------------------------------------------------------

/** AD-1's scan scope, spelled out as exact property names (longhands included) -- deliberately
 *  excludes border/outline/border-radius/font-size/color (already covered elsewhere, or out of
 *  this AC's scope entirely). */
const SPACING_PROPERTIES = [
  "padding-top",
  "padding-right",
  "padding-bottom",
  "padding-left",
  "padding",
  "margin-top",
  "margin-right",
  "margin-bottom",
  "margin-left",
  "margin",
  "row-gap",
  "column-gap",
  "gap",
  "inset-block",
  "inset-inline",
  "inset",
  "top",
  "right",
  "bottom",
  "left",
  "min-width",
  "max-width",
  "width",
  "min-height",
  "max-height",
  "height",
];

// Lookbehind excludes any match preceded by a word char or hyphen, so `border-top`/`inset-block`
// etc. never get misread as a shorter alternative (`top`/`inset`) starting mid-word.
const SPACING_DECLARATION_RE = new RegExp(
  `(?<![\\w-])(${SPACING_PROPERTIES.join("|")})\\s*:\\s*([^;}]+?)\\s*(?:;|(?=}))`,
  "g",
);

/** Fixed UI control dimensions and the visually-hidden idiom -- see file header [Rationale] for
 *  the measurement and justification behind every entry. Matched as exact `"prop: value"`
 *  strings, not bare values, so a coincidentally-equal literal in an unrelated declaration is
 *  not silently forgiven. */
const SPACING_LITERAL_EXCEPTIONS = new Set<string>([
  // Standard "sr-only" visually-hidden idiom (`ChatPanel.module.css`/`MessageList.module.css`'s
  // `.visuallyHidden`) -- already the repo's convention before this scanner existed.
  "width: 1px",
  "height: 1px",
  "margin: -1px",
  // ChatWorkspace.module.css `.skeletonLine` -- must match the `ic-shimmer` keyframe's
  // -420px/420px `background-size` amplitude to animate correctly; not a spacing-scale value.
  "height: 14px",
  // ChatWorkspace.module.css `.ragToggle` -- fixed pill-control height, no exact --app-gap-*
  // step; same "plain literal" precedent as ConversationSidebar's `.brandDot`/`.newButton` below.
  "height: 38px",
  // ConversationSidebar.module.css `.brandDot` -- fixed circular dot size.
  "width: 9px",
  "height: 9px",
  // ConversationSidebar.module.css `.newButton` -- fixed control height.
  "height: 44px",
  // MessageList.module.css `.avatar` -- fixed circular avatar size, same precedent as above.
  "width: 30px",
  "height: 30px",
  // MessageList.module.css `.topBadge` -- fixed pill-badge height, same precedent as `.avatar`.
  "height: 24px",
  // MessageList.module.css `.streamCaret` -- caret thickness, too small for the 4px spacing step
  // to represent without visibly changing the design; same reasoning as the repo's existing
  // 1-2px border/outline exception, applied here to a different property (flagged in advance by
  // T6's dev-log follow_up for this file to judge).
  "width: 2px",
  "margin-left: 2px",
]);

/** True if every whitespace-separated token remaining after stripping `var(...)`/`calc(...)`
 *  calls is either the literal `0` or a unit this px-based token scale cannot represent
 *  (percentage, `rem`, `em`, `vh`/`vw`/`vmin`/`vmax`/`ch`) -- see file header [Rationale]. */
function isZeroOrOutOfScope(tokens: string[]): boolean {
  return tokens.every(
    (t) => t === "0" || /^-?\d*\.?\d+(%|rem|em|vh|vw|vmin|vmax|ch)$/.test(t),
  );
}

/** Every padding/margin/gap/inset/width/height declaration in `css` whose value is not fully
 *  `var(...)`/`calc(...)`-composed, not the literal `0`, not an out-of-scope unit, and not a
 *  named exception (chat-prototype-port:AC-27). Returns `"prop: value"` strings, matching
 *  `design-tokens.test.ts`'s `findScaleLiterals` output convention. */
function findSpacingLiterals(css: string): string[] {
  const clean = stripCssComments(css);
  const hits: string[] = [];
  const re = new RegExp(SPACING_DECLARATION_RE);
  let m: RegExpExecArray | null;
  while ((m = re.exec(clean)) !== null) {
    const prop = m[1];
    const value = m[2].trim();
    const stripped = value
      .replace(/var\(--[\w-]+\)/g, "")
      .replace(/calc\([^)]*\)/g, "")
      .trim();
    if (stripped === "") continue; // fully var()/calc()-composed
    const tokens = stripped.split(/\s+/).filter(Boolean);
    if (isZeroOrOutOfScope(tokens)) continue;
    const declaration = `${prop}: ${value}`;
    if (SPACING_LITERAL_EXCEPTIONS.has(declaration)) continue;
    hits.push(declaration);
  }
  return hits;
}

describe("no unexplained spacing/sizing literals remain in the chat screen's CSS modules", () => {
  it("[chat-prototype-port:AC-27] every target file exists and every padding/margin/gap/inset/width/height declaration is var(...), 0, an out-of-scope unit, or a named exception", () => {
    // A missing/renamed file would make the loop below scan nothing and pass vacuously --
    // assert the corpus itself is real first (same pattern design-tokens.test.ts's AC-10/AC-11
    // use for their own file-count check).
    for (const { filePath } of TARGET_FILES) {
      expect(fs.existsSync(filePath)).toBe(true);
    }
    const violations = TARGET_FILES.map(({ name, filePath }) => ({
      file: name,
      hits: findSpacingLiterals(fs.readFileSync(filePath, "utf8")),
    })).filter((r) => r.hits.length > 0);
    expect(violations).toEqual([]);
  });

  it("[chat-prototype-port:AC-27] the exception list has exactly the 13 entries this file's header justifies (positive control -- list size guard)", () => {
    expect(SPACING_LITERAL_EXCEPTIONS.size).toBe(13);
  });

  it("[chat-prototype-port:AC-27] the scanner actually flags an unexplained spacing literal (positive control, INC-013)", () => {
    expect(findSpacingLiterals(".x { padding: 40px; }")).toEqual(["padding: 40px"]);
    expect(findSpacingLiterals(".y { margin-top: 6px; }")).toEqual(["margin-top: 6px"]);
    expect(findSpacingLiterals(".z { gap: 10px; }")).toEqual(["gap: 10px"]);
  });

  it("[chat-prototype-port:AC-27] the exception match is exact-string, not fuzzy on the bare value (regression guard)", () => {
    // width: 30px is exempted (.avatar); a different, unexplained px value close to it must
    // still be flagged -- proves SPACING_LITERAL_EXCEPTIONS isn't silently forgiving any 30px.
    expect(findSpacingLiterals(".avatar { width: 30px; }")).toEqual([]);
    expect(findSpacingLiterals(".other { width: 31px; }")).toEqual(["width: 31px"]);
    // Same value, different property -- also not exempted by the width:30px entry.
    expect(findSpacingLiterals(".other { height: 31px; }")).toEqual(["height: 31px"]);
  });

  it("[chat-prototype-port:AC-27] value 0 and non-px units (percentage/rem/em/vh) are never flagged (regression guard)", () => {
    expect(findSpacingLiterals(".x { margin: 0; padding: 0 0; }")).toEqual([]);
    expect(findSpacingLiterals(".y { width: 76%; max-width: 48rem; }")).toEqual([]);
    expect(findSpacingLiterals(".z { max-height: 60vh; height: 1.05em; }")).toEqual([]);
  });

  it("[chat-prototype-port:AC-27] a declaration mixing var() with a literal 0 is not flagged, but mixing var() with an unexplained literal is (regression guard)", () => {
    expect(findSpacingLiterals(".x { padding: 0 var(--app-gap-md); }")).toEqual([]);
    expect(findSpacingLiterals(".y { padding: 6px var(--app-gap-md); }")).toEqual([
      "padding: 6px var(--app-gap-md)",
    ]);
  });
});

// ---------------------------------------------------------------------------------------------
// AC-40 -- prefers-reduced-motion coverage scanner
// ---------------------------------------------------------------------------------------------

const ANIMATION_NAMES = ["ic-caret", "ic-shimmer", "ic-spin"] as const;
type AnimationName = (typeof ANIMATION_NAMES)[number];

/** Splits `css` into its depth-1 blocks (`{ header, body }`), where `body` is the raw text
 *  between the block's own matching braces (nested blocks, e.g. a rule inside `@media { ... }`,
 *  are left as unparsed text in the parent's `body` -- callers recurse into it when needed).
 *  Hand-rolled instead of a CSS parser dependency (no new libraries -- Spec §6, AD-8). */
function splitTopLevelBlocks(css: string): Array<{ header: string; body: string }> {
  const blocks: Array<{ header: string; body: string; bodyStart: number }> = [];
  let depth = 0;
  let segmentStart = 0;
  let headerStart = 0;
  for (let i = 0; i < css.length; i++) {
    const c = css[i];
    if (c === "{") {
      if (depth === 0) headerStart = segmentStart;
      depth++;
      if (depth === 1) {
        blocks.push({ header: css.slice(headerStart, i).trim(), body: "", bodyStart: i + 1 });
      }
    } else if (c === "}") {
      depth--;
      if (depth === 0) {
        const last = blocks[blocks.length - 1];
        last.body = css.slice(last.bodyStart, i);
        segmentStart = i + 1;
      }
    }
  }
  return blocks.map(({ header, body }) => ({ header, body }));
}

function normSelector(header: string): string {
  return header.replace(/\s+/g, " ").trim();
}

/** True if `body` (a single selector block's declarations) stops the animation -- either
 *  `animation: none` or `animation-duration` at or below `0.01ms` (chat-prototype-port:AC-40's
 *  own two accepted forms). */
function stopsAnimation(body: string): boolean {
  if (/\banimation\s*:\s*none\b/.test(body)) return true;
  const durationMatch = /animation-duration\s*:\s*([\d.]+)\s*ms/.exec(body);
  return durationMatch !== null && parseFloat(durationMatch[1]) <= 0.01;
}

interface ReducedMotionAnalysis {
  usages: Array<{ selector: string; animation: AnimationName }>;
  violations: Array<{ selector: string; animation: AnimationName }>;
}

/** For a single CSS module's text: finds every selector outside any `@media` block whose
 *  `animation`/`animation-name` declaration names one of `ic-caret`/`ic-shimmer`/`ic-spin`, then
 *  checks -- within that *same file's* `@media (prefers-reduced-motion: reduce)` block only
 *  (Spec §8 AD-8: the block lives beside the selector, not in a shared `globals.css`) -- whether
 *  a same-selector rule stops it. A usage with no matching stop rule is a violation
 *  (chat-prototype-port:AC-40). */
function analyzeReducedMotion(css: string): ReducedMotionAnalysis {
  const clean = stripCssComments(css);
  const topBlocks = splitTopLevelBlocks(clean);
  const baseBlocks = topBlocks.filter((b) => !b.header.startsWith("@media"));
  const reduceMediaBlocks = topBlocks.filter((b) =>
    /@media\s*\(\s*prefers-reduced-motion\s*:\s*reduce\s*\)/.test(b.header),
  );
  const reduceInnerBlocks = reduceMediaBlocks.flatMap((b) => splitTopLevelBlocks(b.body));

  const usages: Array<{ selector: string; animation: AnimationName }> = [];
  const animationDeclRe = /animation(?:-name)?\s*:\s*([^;]+);?/g;
  for (const block of baseBlocks) {
    let dm: RegExpExecArray | null;
    animationDeclRe.lastIndex = 0;
    while ((dm = animationDeclRe.exec(block.body)) !== null) {
      const value = dm[1];
      for (const anim of ANIMATION_NAMES) {
        if (new RegExp(`\\b${anim}\\b`).test(value)) {
          usages.push({ selector: normSelector(block.header), animation: anim });
        }
      }
    }
  }

  const violations = usages.filter(({ selector }) => {
    const target = reduceInnerBlocks.find((b) => normSelector(b.header) === selector);
    return !target || !stopsAnimation(target.body);
  });

  return { usages, violations };
}

describe("prefers-reduced-motion stops all 3 ported animations, wherever they're used", () => {
  it("[chat-prototype-port:AC-40] ic-caret/ic-shimmer/ic-spin are all used somewhere in the 6 target files, and every usage has a same-file reduce stop rule", () => {
    const combined: ReducedMotionAnalysis = { usages: [], violations: [] };
    for (const { filePath } of TARGET_FILES) {
      const css = fs.readFileSync(filePath, "utf8");
      const result = analyzeReducedMotion(css);
      combined.usages.push(...result.usages);
      combined.violations.push(...result.violations);
    }
    // A corpus with zero usages would make the violations-empty assertion below vacuously true
    // -- assert all 3 animation species were actually found (Spec §4 AC-40's "3개 전부").
    const foundAnimations = new Set(combined.usages.map((u) => u.animation));
    expect([...foundAnimations].sort()).toEqual([...ANIMATION_NAMES].sort());
    expect(combined.violations).toEqual([]);
  });

  it("[chat-prototype-port:AC-40] the scanner actually flags a missing stop rule -- 1 of 3 animations left out of the reduce block (positive control, §6 판별력 10)", () => {
    const synthetic = `
      .caretEl { animation: ic-caret 1s step-end infinite; }
      .shimmerEl { animation: ic-shimmer 1.3s linear infinite; }
      .spinEl { animation: ic-spin 0.9s linear infinite; }
      @media (prefers-reduced-motion: reduce) {
        .caretEl { animation: none; }
        .shimmerEl { animation: none; }
        /* .spinEl deliberately omitted */
      }
    `;
    const { violations } = analyzeReducedMotion(synthetic);
    expect(violations).toEqual([{ selector: ".spinEl", animation: "ic-spin" }]);
  });

  it("[chat-prototype-port:AC-40] a reduce block with all 3 covered has zero violations (regression guard, same fixture minus the omission)", () => {
    const synthetic = `
      .caretEl { animation: ic-caret 1s step-end infinite; }
      .shimmerEl { animation: ic-shimmer 1.3s linear infinite; }
      .spinEl { animation: ic-spin 0.9s linear infinite; }
      @media (prefers-reduced-motion: reduce) {
        .caretEl { animation: none; }
        .shimmerEl { animation: none; }
        .spinEl { animation: none; }
      }
    `;
    expect(analyzeReducedMotion(synthetic).violations).toEqual([]);
  });

  it("[chat-prototype-port:AC-40] animation-duration <= 0.01ms counts as stopped even without animation: none (regression guard -- AC-40's second accepted form)", () => {
    const synthetic = `
      .caretEl { animation: ic-caret 1s step-end infinite; }
      @media (prefers-reduced-motion: reduce) {
        .caretEl { animation-duration: 0.01ms; }
      }
    `;
    expect(analyzeReducedMotion(synthetic).violations).toEqual([]);
  });

  it("[chat-prototype-port:AC-40] animation-duration above 0.01ms does not count as stopped (regression guard -- boundary)", () => {
    const synthetic = `
      .caretEl { animation: ic-caret 1s step-end infinite; }
      @media (prefers-reduced-motion: reduce) {
        .caretEl { animation-duration: 0.02ms; }
      }
    `;
    expect(analyzeReducedMotion(synthetic).violations).toEqual([
      { selector: ".caretEl", animation: "ic-caret" },
    ]);
  });

  it("[chat-prototype-port:AC-40] a reduce block in a different selector does not accidentally cover an unrelated one (regression guard -- same-selector matching)", () => {
    const synthetic = `
      .caretEl { animation: ic-caret 1s step-end infinite; }
      @media (prefers-reduced-motion: reduce) {
        .unrelatedEl { animation: none; }
      }
    `;
    expect(analyzeReducedMotion(synthetic).violations).toEqual([
      { selector: ".caretEl", animation: "ic-caret" },
    ]);
  });
});
