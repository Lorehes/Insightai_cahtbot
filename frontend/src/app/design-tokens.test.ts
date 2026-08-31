import fs from "node:fs";
import path from "node:path";
import RootLayout from "./layout";

/**
 * [Purpose] design-system:AC-1 through AC-11, AC-18, AC-19 -- verifies `globals.css`'s token
 *   layer against the prototype's measured values (byte-for-byte where the AC requires it),
 *   the structural invariants (§8 D1 dark mode removal, §Tech Review AD-4 var()-only app
 *   layer, D4's full migration off literals in every `*.module.css`), `layout.tsx`'s `lang`
 *   attribute and font-variable cleanup (D3, §Tech Review AD-6), and that no test name from
 *   D1's pre-migration baseline has silently disappeared (AC-18).
 * [Rationale] `globals.css` is parsed with a small regex-based extractor rather than a CSS
 *   parser dependency (Spec §Tech Review AD-13 -- no new libraries). AC-1/2/3 assert inclusion
 *   ("this name has this value"), never set-equality ("only these names exist") -- historically
 *   this mattered while AD-2's legacy aliases (`--background`, `--foreground`,
 *   `--color-border`) still coexisted with the ported base layer (D2 through D3); D4 removes
 *   those aliases, but the assertion style is left as inclusion rather than tightened to
 *   equality, since a future base-layer addition shouldn't need this file's unrelated AC-1/2/3
 *   cases to change. `RootLayout` is a server component but a pure function, so AC-7 calls it
 *   directly rather than rendering it (Spec §4 D3 test table).
 * [Caution] Jest never loads CSS in this project's config (`next/jest` stubs `*.css` --
 *   Spec §6 판별력 주의 1), so this file reads `globals.css` (and every `*.module.css` for
 *   AC-10/AC-11) as text with `node:fs`, not via any import/`getComputedStyle` path.
 * [AC] design-system:AC-1, design-system:AC-2, design-system:AC-3, design-system:AC-4,
 *   design-system:AC-5, design-system:AC-6, design-system:AC-7, design-system:AC-8,
 *   design-system:AC-9, design-system:AC-10, design-system:AC-11, design-system:AC-18,
 *   design-system:AC-19
 */

const APP_DIR = __dirname;
const SRC_DIR = path.join(__dirname, "..");
const FRONTEND_ROOT = path.join(SRC_DIR, "..");
const GLOBALS_CSS_PATH = path.join(APP_DIR, "globals.css");
const globalsCss = fs.readFileSync(GLOBALS_CSS_PATH, "utf8");

/** Extracts every `--name: value;` custom-property declaration from a CSS text blob into a
 *  Map, keyed by the full `--name`. Values are trimmed but otherwise verbatim. The terminator
 *  is `;` or a following `}` (review 2026-08-30 correctness Low-2) -- a declaration missing
 *  its trailing semicolon (legal CSS for the last declaration in a block) would otherwise be
 *  silently dropped from the map, which is a safe failure for the value-equality ACs
 *  (`tokens.get(...)` becomes `undefined`, so `toBe(...)` fails) but an unsafe one for AC-5's
 *  scanner, which only flags names it actually sees. */
function parseCustomProperties(css: string): Map<string, string> {
  const map = new Map<string, string>();
  const re = /--([a-zA-Z0-9-]+)\s*:\s*([^;}]+?)\s*(?:;|(?=}))/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(css)) !== null) {
    map.set(`--${m[1]}`, m[2].trim());
  }
  return map;
}

/** Counts `prefers-color-scheme` occurrences in a CSS text blob (design-system:AC-6). */
function countDarkModeQueries(css: string): number {
  return (css.match(/prefers-color-scheme/g) ?? []).length;
}

/** WCAG 2.1 relative luminance of a `#rrggbb` hex color (design-system:AC-9). */
function relativeLuminance(hex: string): number {
  const rgb = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255);
  const [r, g, b] = rgb.map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2.1 contrast ratio between two `#rrggbb` hex colors (design-system:AC-9). */
function contrastRatio(hexA: string, hexB: string): number {
  const lA = relativeLuminance(hexA);
  const lB = relativeLuminance(hexB);
  const [lighter, darker] = lA > lB ? [lA, lB] : [lB, lA];
  return (lighter + 0.05) / (darker + 0.05);
}

const tokens = parseCustomProperties(globalsCss);

describe("base color tokens match the prototype byte for byte", () => {
  const expected: Record<string, string> = {
    "--color-primary": "#ff385c",
    "--color-primary-active": "#e00b41",
    "--color-ink": "#222222",
    "--color-body": "#3f3f3f",
    "--color-muted": "#6a6a6a",
    "--color-muted-soft": "#929292",
    "--color-canvas": "#ffffff",
    "--color-surface-soft": "#f7f7f7",
    "--color-surface-strong": "#f2f2f2",
    "--color-surface-card": "#ffffff",
    "--color-hairline": "#dddddd",
    "--color-hairline-soft": "#ebebeb",
    "--color-border-strong": "#c1c1c1",
    "--color-error": "#c13515",
    "--color-on-primary": "#ffffff",
  };

  it("[design-system:AC-1] all 15 base color tokens are present with the prototype's exact values", () => {
    // Guards against `expected` itself shrinking silently (review 2026-08-30 correctness
    // Low-3) -- the loop below would still pass with fewer entries, but the test's own name
    // claims "15".
    expect(Object.keys(expected)).toHaveLength(15);
    for (const [name, value] of Object.entries(expected)) {
      expect(tokens.get(name)).toBe(value);
    }
  });
});

describe("scale tokens (spacing 9-step, radius 7-step) match the prototype byte for byte", () => {
  it("[design-system:AC-2] all 9 spacing tokens match", () => {
    expect(tokens.get("--space-xxs")).toBe("2px");
    expect(tokens.get("--space-xs")).toBe("4px");
    expect(tokens.get("--space-sm")).toBe("8px");
    expect(tokens.get("--space-md")).toBe("12px");
    expect(tokens.get("--space-base")).toBe("16px");
    expect(tokens.get("--space-lg")).toBe("24px");
    expect(tokens.get("--space-xl")).toBe("32px");
    expect(tokens.get("--space-xxl")).toBe("48px");
    expect(tokens.get("--space-section")).toBe("64px");
  });

  it("[design-system:AC-2] all 7 radius tokens match", () => {
    expect(tokens.get("--radius-none")).toBe("0px");
    expect(tokens.get("--radius-xs")).toBe("4px");
    expect(tokens.get("--radius-sm")).toBe("8px");
    expect(tokens.get("--radius-md")).toBe("14px");
    expect(tokens.get("--radius-lg")).toBe("20px");
    expect(tokens.get("--radius-xl")).toBe("32px");
    expect(tokens.get("--radius-full")).toBe("9999px");
  });
});

describe("typography tokens (9 kinds) match the prototype byte for byte", () => {
  it("[design-system:AC-3] page/section/card/body/answer/meta/label/code sizes and weights, plus the mono stack, all match", () => {
    expect(tokens.get("--app-type-page-size")).toBe("28px");
    expect(tokens.get("--app-type-page-weight")).toBe("700");
    expect(tokens.get("--app-type-section-size")).toBe("20px");
    expect(tokens.get("--app-type-section-weight")).toBe("600");
    expect(tokens.get("--app-type-card-size")).toBe("16px");
    expect(tokens.get("--app-type-card-weight")).toBe("600");
    expect(tokens.get("--app-type-body-size")).toBe("15px");
    expect(tokens.get("--app-type-body-weight")).toBe("400");
    expect(tokens.get("--app-type-answer-size")).toBe("16px");
    expect(tokens.get("--app-type-answer-lh")).toBe("1.75");
    expect(tokens.get("--app-type-meta-size")).toBe("13px");
    expect(tokens.get("--app-type-meta-weight")).toBe("400");
    expect(tokens.get("--app-type-label-size")).toBe("12px");
    expect(tokens.get("--app-type-label-weight")).toBe("600");
    expect(tokens.get("--app-type-code-size")).toBe("13.5px");
    expect(tokens.get("--app-type-mono")).toBe(
      'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace',
    );
  });
});

describe("--font-sans is exactly the system stack, without the prototype's licensed faces", () => {
  it("[design-system:AC-4] --font-sans matches exactly and excludes Airbnb Cereal VF / Circular", () => {
    const fontSans = tokens.get("--font-sans");
    expect(fontSans).toBe(
      "'Pretendard', -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif",
    );
    expect(fontSans).not.toMatch(/Cereal/);
    expect(fontSans).not.toMatch(/Circular/);
  });
});

describe("--app-* declarations are aliases only, except the named literal exceptions", () => {
  // Spec §Tech Review AD-4's exception list, transcribed literally so a future addition is
  // visible in a diff here (Spec §4 D2 test table: "예외 목록 자체를 테스트에 리터럴로 박아
  // 목록이 늘어나면 눈에 띄게 한다"). 3 literal colors + 16 --app-type-* tokens + 4 fixed
  // widths = 23 -- Spec §Tech Review AD-4 narrates this as "24"; the discrepancy traces to
  // --app-font, which AD-4's own text excludes ("참조 쪽이다, 예외가 아니다") and this file's
  // count agrees with that text over the summary number (review 2026-08-30 correctness
  // confirmed this file's 23 is the internally-consistent reading; Spec doc fix tracked as a
  // follow_up, not applied here -- Spec editing is outside this card's authority).
  const LITERAL_EXCEPTIONS = new Set([
    "--app-accent-wash",
    "--app-accent-line",
    "--app-status-danger-wash",
    "--app-type-page-size",
    "--app-type-page-weight",
    "--app-type-section-size",
    "--app-type-section-weight",
    "--app-type-card-size",
    "--app-type-card-weight",
    "--app-type-body-size",
    "--app-type-body-weight",
    "--app-type-answer-size",
    "--app-type-answer-lh",
    "--app-type-meta-size",
    "--app-type-meta-weight",
    "--app-type-label-size",
    "--app-type-label-weight",
    "--app-type-mono",
    "--app-type-code-size",
    "--app-rail-width",
    "--app-admin-nav-width",
    "--app-answer-max",
    "--app-topbar-height",
  ]);

  /** Names of every --app-* token whose value is neither `var(...)` nor a named exception. A
   *  `var(--x)` whose referenced `--x` is not itself declared anywhere in `t` also counts as a
   *  violation (review 2026-08-30 correctness Low-1) -- `startsWith("var(")` alone would let a
   *  typo'd reference (e.g. `var(--color-inkk)`) through, which is exactly the "broken
   *  reference" AD-2 cites as the reason `--shadow-card` gets ported as a literal instead. */
  function findAppLayerViolations(t: Map<string, string>): string[] {
    const violations: string[] = [];
    for (const [name, value] of t) {
      if (!name.startsWith("--app-")) continue;
      if (LITERAL_EXCEPTIONS.has(name)) continue;
      const varMatch = /^var\((--[a-zA-Z0-9-]+)\)$/.exec(value);
      if (varMatch && t.has(varMatch[1])) continue;
      violations.push(name);
    }
    return violations;
  }

  it("[design-system:AC-5] every --app-* declaration in globals.css is var(...) of a real token, or a named exception", () => {
    expect(findAppLayerViolations(tokens)).toEqual([]);
  });

  it("[design-system:AC-5] the exception list itself has exactly the 23 names this Spec's AD-4 text supports", () => {
    // Guards LITERAL_EXCEPTIONS from silently shrinking (review 2026-08-30 correctness Low-3)
    // -- a shrunk list would make findAppLayerViolations over-flag, which fails loudly, but a
    // grown list fails silently and this assertion is the only thing that would catch that.
    expect(LITERAL_EXCEPTIONS.size).toBe(23);
  });

  it("[design-system:AC-5] the scanner actually flags an unexplained literal (positive control)", () => {
    const synthetic = parseCustomProperties(":root { --app-fake: #123456; }");
    expect(findAppLayerViolations(synthetic)).toEqual(["--app-fake"]);
  });

  it("[design-system:AC-5] the scanner also flags a var() reference to a token that doesn't exist (positive control)", () => {
    const synthetic = parseCustomProperties(":root { --app-fake: var(--does-not-exist); }");
    expect(findAppLayerViolations(synthetic)).toEqual(["--app-fake"]);
  });
});

describe("no dark-mode block remains in globals.css", () => {
  it("[design-system:AC-6] globals.css itself has zero prefers-color-scheme occurrences", () => {
    expect(countDarkModeQueries(globalsCss)).toBe(0);
  });

  it("[design-system:AC-6] the counter actually detects a dark-mode block (positive control)", () => {
    expect(countDarkModeQueries("@media (prefers-color-scheme: dark) { :root { --x: 1; } }")).toBe(1);
  });
});

describe("body text colors meet WCAG AA 4.5:1 against the canvas", () => {
  it("[design-system:AC-9] --color-ink, --color-body, --color-muted all reach 4.5:1 against #ffffff", () => {
    const canvas = "#ffffff";
    expect(contrastRatio(tokens.get("--color-ink")!, canvas)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(tokens.get("--color-body")!, canvas)).toBeGreaterThanOrEqual(4.5);
    expect(contrastRatio(tokens.get("--color-muted")!, canvas)).toBeGreaterThanOrEqual(4.5);
  });

  // --color-muted-soft (#929292, ~3.1:1) is deliberately excluded -- Spec §8 D2, user decision
  // (a): kept at the prototype's value, restricted to large-text/UI-border use where 3:1 is
  // the applicable AA threshold, not asserted against 4.5:1 here.
});

describe("--shadow-card matches the prototype byte for byte", () => {
  it("[design-system:AC-19] --shadow-card is the exact prototype string, including spacing and commas", () => {
    expect(tokens.get("--shadow-card")).toBe(
      "rgba(0,0,0,0.02) 0 0 0 1px, rgba(0,0,0,0.04) 0 2px 6px 0, rgba(0,0,0,0.1) 0 4px 8px 0",
    );
  });
});

describe("root layout renders lang=\"ko\"", () => {
  it("[design-system:AC-7] RootLayout renders <html lang=\"ko\">", () => {
    // RootLayout is a server component but a pure function -- calling it directly returns the
    // React element tree without needing a render pass (Spec §4 D3 test table). The prop type
    // comes from Next's generated `LayoutProps<"/">`; casting a minimal children value past it
    // here is the "casting may be needed" case the Spec's own test table calls out.
    const element = RootLayout({ children: null } as Parameters<typeof RootLayout>[0]);
    expect(element.props.lang).toBe("ko");
  });
});

describe("layout.tsx has no dead font CSS variables", () => {
  const LAYOUT_TSX_PATH = path.join(APP_DIR, "layout.tsx");
  const layoutSource = fs.readFileSync(LAYOUT_TSX_PATH, "utf8");

  /** Names of every `variable: "--x"` a `next/font` call in this source declares.
   *  (design-system:AC-8) */
  function extractDeclaredFontVariables(source: string): string[] {
    const re = /variable:\s*["'](--[a-zA-Z0-9-]+)["']/g;
    const names: string[] = [];
    let m: RegExpExecArray | null;
    while ((m = re.exec(source)) !== null) names.push(m[1]);
    return names;
  }

  /** Every `--name` referenced inside a `var(--name...)` call, across every `*.css` file
   *  under `dir` (recursive). Walks the tree by hand rather than `readdirSync`'s `recursive`
   *  option (Spec §Tech Review AD-13 -- that option's behavior is Node-version-dependent).
   *  (design-system:AC-8) */
  function collectCssVarReferences(dir: string): Set<string> {
    const refs = new Set<string>();
    const re = /var\(\s*(--[a-zA-Z0-9-]+)/g;
    const walk = (d: string) => {
      for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
        const full = path.join(d, entry.name);
        if (entry.isDirectory()) {
          walk(full);
          continue;
        }
        if (!entry.name.endsWith(".css")) continue;
        const text = fs.readFileSync(full, "utf8");
        let m: RegExpExecArray | null;
        while ((m = re.exec(text)) !== null) refs.add(m[1]);
      }
    };
    walk(dir);
    return refs;
  }

  /** Declared font-variable names that `referenced` does not contain (design-system:AC-8).
   *  The one function both the real assertion below and its positive control call, so the
   *  control actually exercises the same violation logic instead of a reimplementation that
   *  could drift from it (review 2026-08-30 correctness Medium-2). */
  function findUnreferencedFontVariables(declared: string[], referenced: Set<string>): string[] {
    return declared.filter((name) => !referenced.has(name));
  }

  it("[design-system:AC-8] every font variable layout.tsx declares is referenced somewhere in src/**", () => {
    const declared = extractDeclaredFontVariables(layoutSource);
    const referenced = collectCssVarReferences(SRC_DIR);
    expect(findUnreferencedFontVariables(declared, referenced)).toEqual([]);
    // The walker itself must actually find something real, not just fail to find nothing --
    // otherwise an empty `declared` here would make the assertion above vacuously true even
    // if `collectCssVarReferences` were broken (review 2026-08-30 correctness Medium-2).
    // --app-type-mono is the token D3 itself wired MessageList.module.css onto.
    expect(referenced.has("--app-type-mono")).toBe(true);
  });

  it("[design-system:AC-8] the declared-variable set is empty post-D3 (next/font fully removed, AD-6)", () => {
    // Named separately from the loop above so an empty set reads as an explicit, asserted
    // fact rather than a for-loop that happens to iterate zero times (INC-013 pattern --
    // "0건" ACs need their own positive assertion, not just an absence of failure).
    expect(extractDeclaredFontVariables(layoutSource)).toEqual([]);
  });

  it("[design-system:AC-8] layout.tsx source contains no next/font import (structural invariant, review 2026-08-30 correctness Low-1)", () => {
    // extractDeclaredFontVariables only recognizes the `variable: "--x"` string-literal shape
    // -- a `variable: SOME_CONST` form would slip past it silently. This assertion pins the
    // stronger, syntax-independent invariant AD-6 actually requires ("next/font 완전 제거").
    expect(layoutSource).not.toMatch(/from\s+["']next\/font/);
  });

  it("[design-system:AC-8] the checker actually flags an unreferenced font variable (positive control)", () => {
    const declared = extractDeclaredFontVariables('variable: "--font-fake-unused",');
    const referenced = new Set<string>(); // empty corpus -- nothing references it
    expect(findUnreferencedFontVariables(declared, referenced)).toEqual(["--font-fake-unused"]);
  });
});

/** Every `*.module.css` file under `dir` (recursive). Walks the tree by hand for the same
 *  reason `collectCssVarReferences` above does (Spec §Tech Review AD-13).
 *  (design-system:AC-10, design-system:AC-11) */
function findModuleCssFiles(dir: string): string[] {
  const files: string[] = [];
  const walk = (d: string) => {
    for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
      const full = path.join(d, entry.name);
      if (entry.isDirectory()) {
        walk(full);
        continue;
      }
      if (entry.name.endsWith(".module.css")) files.push(full);
    }
  };
  walk(dir);
  return files;
}

describe("no color literals remain in *.module.css files", () => {
  // Practical, non-exhaustive named-color list -- Spec §4 D4's test table only requires
  // catching the AD-10 positive-control sample ("명명색 합성 샘플 -> 1건"), not exhaustive
  // CSS Color Module Level 4 coverage.
  const NAMED_COLORS = [
    "black", "white", "red", "green", "blue", "yellow", "orange", "purple", "pink", "brown",
    "gray", "grey", "cyan", "magenta", "lime", "navy", "teal", "maroon", "olive", "silver",
    "gold", "indigo", "violet", "coral", "salmon", "khaki", "plum", "orchid", "tan", "beige",
    "ivory", "lavender", "crimson", "transparent",
  ];
  // Lookaround (not `\b`) treats `-` as a word-continuation character, not a boundary --
  // `\b` alone would let "white" inside the real CSS property `white-space` false-positive
  // (review 2026-08-30 D4 self-caught: found via this exact false positive in
  // ConversationSidebar.module.css/ChatPanel.module.css/MessageList.module.css's
  // `white-space: nowrap` declarations).
  const NAMED_COLOR_RE = new RegExp(`(?<![\\w-])(${NAMED_COLORS.join("|")})(?![\\w-])`, "i");

  /** Kinds of color literal found in a CSS text blob: hex, an rgb()/rgba()/hsl()/hsla()
   *  function, or a named color from the list above (design-system:AC-10). */
  function findColorLiterals(css: string): string[] {
    const hits: string[] = [];
    if (/#[0-9a-fA-F]{3,8}\b/.test(css)) hits.push("hex");
    if (/\b(rgb|rgba|hsl|hsla)\(/i.test(css)) hits.push("rgb/hsl function");
    if (NAMED_COLOR_RE.test(css)) hits.push("named color");
    return hits;
  }

  it("[design-system:AC-10] no *.module.css file under src/** contains a color literal", () => {
    const files = findModuleCssFiles(SRC_DIR);
    // A scanner that silently found nothing to scan would make the assertion below
    // vacuously true -- assert the corpus itself is real (review 2026-08-30 pattern,
    // carried over from the D3 AC-8 fix).
    expect(files.length).toBeGreaterThan(0);
    const violations = files
      .map((f) => ({ file: path.relative(SRC_DIR, f), hits: findColorLiterals(fs.readFileSync(f, "utf8")) }))
      .filter((r) => r.hits.length > 0);
    expect(violations).toEqual([]);
  });

  it("[design-system:AC-10] the scanner actually flags hex/rgb/named-color literals (positive control)", () => {
    expect(findColorLiterals("color: #fff;")).toContain("hex");
    expect(findColorLiterals("color: rgb(0,0,0);")).toContain("rgb/hsl function");
    expect(findColorLiterals("color: red;")).toContain("named color");
  });

  it("[design-system:AC-10] the scanner does not flag the CSS property white-space as a color literal (regression guard)", () => {
    // white-space is real CSS in this repo -- ConversationSidebar/ChatPanel use `nowrap`
    // (truncation and sr-only rules), MessageList uses `pre-wrap` (review 2026-08-30
    // correctness Low-3: an earlier version of this comment said "nowrap" for all three,
    // which was wrong for MessageList). A naive \b-bounded regex flags "white" inside either
    // value, since \b treats the hyphen as a boundary too. Caught by this file's own AC-10
    // assertion during D4 authoring; fixed by requiring non-word/non-hyphen on both sides.
    expect(findColorLiterals("white-space: nowrap;")).toEqual([]);
    expect(findColorLiterals("white-space: pre-wrap;")).toEqual([]);
  });
});

describe("no literal border-radius/font-size declarations remain in *.module.css files", () => {
  /** `border-radius`/`font-size` declarations in a CSS text blob whose value is not
   *  `var(...)`, matched by exact property name so `font-family`, `border-width`, and the
   *  `font:` shorthand are excluded (design-system:AC-11, Spec §4 D4's scanner definition). */
  function findScaleLiterals(css: string): string[] {
    const hits: string[] = [];
    // Lookbehind on the property name (not a required preceding `;`/`{`) so a comment-preceded
    // declaration (`{ /* note */ border-radius: 6px; }`) is still seen; terminator accepts a
    // following `}` as well as `;` so a semicolon-less last declaration (legal CSS) is too --
    // the same class of gap `parseCustomProperties` above was fixed for (review 2026-08-30
    // correctness Low-2 on that function; this scanner had the same gap, found in D4's own
    // review and fixed here to match).
    const re = /(?<![\w-])(border-radius|font-size)\s*:\s*([^;}]+?)\s*(?:;|(?=}))/g;
    let m: RegExpExecArray | null;
    while ((m = re.exec(css)) !== null) {
      const value = m[2].trim();
      if (!value.startsWith("var(")) hits.push(`${m[1]}: ${value}`);
    }
    return hits;
  }

  it("[design-system:AC-11] no *.module.css file has a literal border-radius or font-size declaration", () => {
    const files = findModuleCssFiles(SRC_DIR);
    // Same self-check as AC-10's real assertion (review 2026-08-30 correctness Low-1) -- an
    // empty corpus would make the assertion below vacuously true.
    expect(files.length).toBeGreaterThan(0);
    const violations = files
      .map((f) => ({ file: path.relative(SRC_DIR, f), hits: findScaleLiterals(fs.readFileSync(f, "utf8")) }))
      .filter((r) => r.hits.length > 0);
    expect(violations).toEqual([]);
  });

  it("[design-system:AC-11] the scanner actually flags a literal border-radius/font-size (positive control)", () => {
    expect(findScaleLiterals(".x { border-radius: 6px; }")).toEqual(["border-radius: 6px"]);
    expect(findScaleLiterals(".y { font-size: 12px; }")).toEqual(["font-size: 12px"]);
  });

  it("[design-system:AC-11] the scanner still catches a comment-preceded or semicolon-less declaration (regression guard)", () => {
    // The scanner's first version required a literal `;`/`{` immediately before the property
    // name and a trailing `;` -- both miss legal CSS (review 2026-08-30 correctness Medium-1).
    expect(findScaleLiterals(".x { /* note */ border-radius: 6px; }")).toEqual(["border-radius: 6px"]);
    expect(findScaleLiterals(".x { border-radius: 6px }")).toEqual(["border-radius: 6px"]);
  });

  it("[design-system:AC-11] the scanner ignores font-family and the font shorthand (regression guard)", () => {
    expect(findScaleLiterals(".z { font-family: monospace; font: inherit; border-width: 1px; }")).toEqual([]);
  });
});

describe("no test name from the D1 baseline has disappeared or been renamed", () => {
  // Captured at D1 kickoff (npx jest --listTests + npx jest --json, before any design-system
  // file existed), per Spec §Tech Review AD-11. These are the bare `it(...)` argument strings
  // this file's own extractTestNames() below produces -- NOT Jest's `fullName` (which prefixes
  // each with its ancestor describe() titles). An earlier version of this list used fullName,
  // which made every single baseline entry register as "missing" against extractTestNames()'s
  // output, since the two never actually share a common string shape (caught while authoring
  // this card, 2026-08-30). 119 names, all plain string literals (no `it.each`/dynamic names
  // existed in this repo at D1 -- AD-11's own [Caution] about that limitation is therefore
  // currently inert, not exercised). This bare-name list also contains 4 groups of genuine
  // cross-file duplicates (e.g. "URL-encodes the conversation id" x3) -- see
  // findMissingBaselineNames below for why that does not weaken the guard.
  //
  // Cross-checked against D1's actual record (review 2026-08-30 correctness Low-2): this list
  // was regenerated from the 12 original files' *current* content rather than copied from D1's
  // dev-log `ac18_baseline.names`, so a silent rename between D1 and D4 could in principle have
  // been re-baselined as if it were always the name. Verified this did not happen -- an
  // unordered (multiset) suffix match of all 119 entries here against D1's 119 recorded
  // `fullName` strings (`docs/dev-log/frontend.jsonl`'s D1 rework entry) resolves every single
  // one; the only entries that don't match by simple substring are the 4 containing `\n`/`\"`
  // escapes, and those fail purely because D1's record is JSON-parsed (escapes resolved) while
  // this list is raw source text (escapes literal) -- the same representation difference
  // extractTestNames() itself has relative to Jest's runtime fullName, so it is consistent
  // with, not a sign of drift from, how this file already works.
  const D1_BASELINE_NAMES = [
    "disables the input while the conversation is being created, then enables it",
    "shows an accessible error and no input when conversation creation fails",
    "skips creating a conversation when one is already provided",
    "shows the user's own message immediately, rendered as plain text",
    "[chat:AC-36] grows the assistant reply's rendered text across separate chunks instead of showing it all at once",
    "[chat:AC-37] renders the completed assistant reply's markdown as real elements once streaming finishes",
    "disables the input while a reply is streaming and re-enables it once done",
    "cannot submit an empty or whitespace-only draft",
    "sends on Enter, and inserts a newline instead of sending on Shift+Enter",
    "marks the reply as an error when the stream throws mid-turn (not an intentional abort)",
    "marks the reply as an error when the server's own last chunk has finishReason \\\"error\\\", while still showing its safe message text",
    "aborts an in-flight stream's AbortSignal when the panel unmounts",
    "[chat:AC-38] seeds the transcript with the given initialMessages instead of starting empty",
    "does not seed the transcript when initialMessages is omitted (existing no-arg behavior unchanged)",
    "[chat:AC-39] forwards the given model prop to streamAssistantReply when sending",
    "sends with an undefined model when no model prop is given (existing no-arg behavior unchanged)",
    "[chat:AC-38] invokes onConversationCreated once a new conversation is auto-created",
    "does not invoke onConversationCreated when a conversationId is already provided",
    "[chat:AC-38] starts a new conversation automatically on first load",
    "[chat:AC-38] clicking '+ 새 대화' starts another new conversation",
    "shows a human-readable error when creating a new conversation hits the quota",
    "[chat:AC-38] selecting a different conversation from the sidebar displays its previous messages",
    "adopting an existing conversation on first load never auto-creates one (review 2026-08-29 Medium-3, re-opened)",
    "shows an inline error and keeps the current conversation when loading a selection's history fails",
    "[chat:AC-38] deleting a conversation removes it from the sidebar list",
    "deleting the currently open conversation adopts a remaining one instead of creating a fresh one (review 2026-08-29 Medium-2)",
    "deleting the last remaining conversation starts a fresh one automatically",
    "[chat:AC-39] sends the selected model id in the actual message-send request",
    "shows a loading status while the list is being fetched",
    "shows an accessible error when the list fails to load",
    "shows an empty-state message when the list has no conversations",
    "[chat:AC-38] renders every conversation's title, falling back to a placeholder for a null title",
    "marks the selected conversation with aria-current, and no other item",
    "[chat:AC-38] clicking a conversation calls onSelect with its id",
    "selecting a conversation via the keyboard (Tab + Enter) also calls onSelect",
    "[chat:AC-38] clicking '+ 새 대화' calls onCreate",
    "[chat:AC-38] deleting a conversation calls onDelete only after the user confirms",
    "does not call onDelete when the user cancels the confirmation",
    "disables new/select/delete controls while isBusy is true",
    "shows the latest action error as an accessible alert",
    "renders the session id returned by the initial bootstrap call",
    "confirms the cookie rode along when a recheck returns the same session id",
    "warns when a recheck returns a different session id (cookie not carried)",
    "shows an accessible error message when the bootstrap call fails",
    "shows a placeholder when there are no messages yet",
    "renders a user message as plain text, not interpreted as markdown",
    "[chat:AC-37] renders a markdown heading as a real heading element, not literal '#' text",
    "[chat:AC-37] renders a markdown bullet list as <ul><li>, not literal '-' text",
    "[chat:AC-37] renders a markdown ordered list as <ol><li>",
    "[chat:AC-37] renders a fenced code block as <pre><code>, not literal backtick text",
    "renders an https link with target=_blank and rel=noopener noreferrer",
    "never renders a javascript: URL as a clickable link (XSS guard)",
    "never renders raw HTML in the response as live DOM (no dangerouslySetInnerHTML passthrough)",
    "never renders an http(s) markdown image as a live <img>, falling back to its alt text",
    "renders a same-origin relative markdown image as a live <img>",
    "falls back to alt text for a data: URI markdown image (react-markdown's own urlTransform strips it before this component sees it)",
    "never renders a protocol-relative markdown image as a live <img> (host is not actually same-origin)",
    "marks a streaming assistant message as busy for assistive tech, and a finished one as not busy",
    "shows a disabled loading placeholder before the catalog resolves",
    "[chat:AC-39] renders exactly the models GET /api/models returned, matching id and count",
    "calls onChange once with the catalog's default model id once loaded",
    "[chat:AC-39] picking a different model calls onChange with the newly picked id",
    "marks the default model's option label so it is distinguishable from the others",
    "shows an accessible error when the catalog fails to load",
    "returns nothing until a full \\\\n\\\\n-terminated block has arrived",
    "[chat:AC-36] completes a block once the terminator arrives in a later push (event split across reads)",
    "[chat:AC-36] splits an event down to a single character at a time and still reassembles it",
    "returns multiple blocks when one push contains several complete events",
    "keeps a trailing partial event buffered across a push that ends mid-event",
    "normalizes CRLF and lone CR line endings to LF before looking for the separator",
    "returns null when nothing (or only whitespace) is buffered",
    "returns a leftover un-terminated event once the stream has closed",
    "clears the buffer so a second call returns null",
    "[chat:AC-36] extracts the data payload of a simple event",
    "returns null for a block with no data: line (blank keep-alive block)",
    "ignores a `:`-prefixed comment line",
    "returns null for a block containing only a comment",
    "ignores event:/id:/retry: fields alongside a data: line",
    "joins multiple data: lines in one block with \\\\n, per the SSE spec",
    "[chat:AC-36] strips exactly one leading space after `data:`, not more (project CLAUDE.md fact #6)",
    "preserves a Korean payload untouched (project CLAUDE.md fact #6)",
    "[chat:AC-39] GETs /api/models with same-origin credentials",
    "[chat:AC-39] returns the parsed catalog unmodified, count and ids intact",
    "preserves the wire field under the key 'default', not 'isDefault' (JsonProperty pitfall)",
    "throws ApiError on a non-2xx response",
    "[chat:AC-40] requests same-origin credentials so the session cookie rides along",
    "[chat:AC-40] a caller cannot override credentials away from same-origin",
    "resolves with the parsed JSON body on a 2xx response",
    "throws ApiError carrying the response status on a non-2xx response",
    "requests same-origin credentials, same as apiFetch",
    "resolves with undefined on a 2xx response, never touching response.json()",
    "throws ApiError carrying the response status on a non-2xx response",
    "[chat:AC-38] GETs /api/conversations and returns the parsed array unmodified",
    "does not re-sort or re-filter the response -- trusts the backend's own order as-is",
    "throws ApiError on a non-2xx response",
    "[chat:AC-38] DELETEs the encoded conversation path and resolves without a body",
    "URL-encodes the conversation id",
    "throws ApiError carrying 404 for an unknown/foreign/already-deleted conversation",
    "POSTs to the conversation's messages endpoint with same-origin credentials and a JSON body",
    "URL-encodes the conversation id",
    "forwards an optional model and an AbortSignal through to fetch",
    "[chat:AC-36] yields a chunk as soon as its event is complete, without waiting for the stream to close",
    "[chat:AC-36] reassembles one SSE event whose bytes are split across two separate reads",
    "[chat:AC-36] reassembles a Korean delta whose multi-byte UTF-8 encoding is split mid-character across reads",
    "[chat:AC-36] yields several events delivered in a single read, in order",
    "does not stop early just because a non-final chunk happens to carry a non-null finishReason",
    "flushes a final un-terminated event once the stream closes without a trailing blank line",
    "releases the reader lock once the stream ends",
    "throws ApiError carrying the response status when the request is rejected before streaming (e.g. 404/400/quota)",
    "falls back to the status line when a non-2xx response body is not JSON",
    "is true only for a case-insensitive match against \\\"error\\\"",
    "is false for null, and for other known finishReason values regardless of casing",
    "[chat:AC-38] GETs the conversation's messages path with same-origin credentials",
    "URL-encodes the conversation id",
    "[chat:AC-38] returns prior messages unmodified, in the order the backend sent them",
    "throws ApiError carrying 404 for an unknown/foreign/already-deleted conversation",
    "does not proxy a bare /api/:path* wildcard (regression guard for review 2026-08-29 High-1)",
    "proxies exactly the four path families src/lib/api/** calls",
    "[chat:AC-46] sets the security response headers with their measured exact values on every route, and disables X-Powered-By",
  ];

  /** Every literal `it("...")`/`it('...')` test-name string across every `*.test.ts(x)` file
   *  under `dir` (recursive, skips `node_modules`/`.next`/`.git`). Only sees string literals --
   *  `it.each`/template-literal/computed names are invisible to this extractor (Spec §Tech
   *  Review AD-11's documented limitation). (design-system:AC-18) */
  function extractTestNames(dir: string): string[] {
    const names: string[] = [];
    const SKIP_DIRS = new Set(["node_modules", ".next", ".git"]);
    const re = /\bit(?:\.\w+)?\(\s*(["'])((?:\\.|(?!\1).)*)\1/g;
    const walk = (d: string) => {
      for (const entry of fs.readdirSync(d, { withFileTypes: true })) {
        if (entry.isDirectory()) {
          if (SKIP_DIRS.has(entry.name)) continue;
          walk(path.join(d, entry.name));
          continue;
        }
        if (!/\.test\.tsx?$/.test(entry.name)) continue;
        const text = fs.readFileSync(path.join(d, entry.name), "utf8");
        re.lastIndex = 0;
        let m: RegExpExecArray | null;
        while ((m = re.exec(text)) !== null) names.push(m[2]);
      }
    };
    walk(dir);
    return names;
  }

  /** Occurrence counts of each name in `names` (design-system:AC-18) -- the multiset this
   *  repo's real duplicate bare-text test names require (see D1_BASELINE_NAMES's own
   *  comment). */
  function countOccurrences(names: string[]): Map<string, number> {
    const counts = new Map<string, number>();
    for (const name of names) counts.set(name, (counts.get(name) ?? 0) + 1);
    return counts;
  }

  /** Baseline names `currentCounts` cannot fully account for (design-system:AC-18) -- a
   *  multiset difference, not a Set-membership check. If the baseline has "X" twice and
   *  `currentCounts` only has one "X" left, one "X" is reported missing even though the name
   *  itself is still technically present. The one function both the real assertion and its
   *  positive control call (review 2026-08-30 D3 pattern, applied here proactively). */
  function findMissingBaselineNames(baseline: string[], currentCounts: Map<string, number>): string[] {
    const remaining = new Map(currentCounts);
    const missing: string[] = [];
    for (const name of baseline) {
      const left = remaining.get(name) ?? 0;
      if (left > 0) {
        remaining.set(name, left - 1);
      } else {
        missing.push(name);
      }
    }
    return missing;
  }

  it("[design-system:AC-18] every D1-baseline test name is still present in the current suite", () => {
    // FRONTEND_ROOT, not SRC_DIR -- frontend/next.config.test.ts lives outside src/ and its 3
    // tests are part of the D1 baseline (INC-017: a scan scoped to src/** alone would miss it
    // and manufacture a false "3 tests disappeared" failure here).
    const current = countOccurrences(extractTestNames(FRONTEND_ROOT));
    expect(findMissingBaselineNames(D1_BASELINE_NAMES, current)).toEqual([]);
  });

  it("[design-system:AC-18] the baseline itself has the 119 names D1 captured", () => {
    expect(D1_BASELINE_NAMES).toHaveLength(119);
  });

  it("[design-system:AC-18] the checker is multiset-aware -- losing one of several same-named tests is still caught (regression guard)", () => {
    // This repo's own baseline has 4 groups of cross-file bare-text duplicates (e.g.
    // "URL-encodes the conversation id" x3, found while authoring this card) -- a plain
    // Set-membership check would let one of those silently disappear as long as at least one
    // namesake elsewhere survived.
    const baseline = ["shared name", "shared name", "shared name"];
    const currentTwoOfThree = countOccurrences(["shared name", "shared name"]); // one is gone
    expect(findMissingBaselineNames(baseline, currentTwoOfThree)).toEqual(["shared name"]);
  });

  it("[design-system:AC-18] the checker actually flags a name missing from a synthetic baseline (positive control)", () => {
    const syntheticBaseline = ["alpha test", "beta test", "gamma test"];
    const syntheticCurrent = countOccurrences(["alpha test", "gamma test"]); // "beta test" missing
    expect(findMissingBaselineNames(syntheticBaseline, syntheticCurrent)).toEqual(["beta test"]);
  });
});
