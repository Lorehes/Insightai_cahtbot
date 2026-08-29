import type { NextConfig } from "next";

// 백엔드 오리진. server.address 가 127.0.0.1 로 고정돼 있어(backend/src/main/resources/
// application.yaml, 로컬 데모 한정 조치) 'localhost' 대신 리터럴 IP를 쓴다 -- Node의 DNS
// 해석 순서에 따라 'localhost'가 ::1(IPv6)로 먼저 시도되면 IPv4 전용으로 바인딩된 백엔드에
// 연결이 실패할 수 있다. 백엔드 application.yaml의 다른 값들(예: DB_URL)과 같은 패턴으로
// 기본값을 두고 필요하면 환경변수로 덮어쓸 수 있게 했다 (1단계 로컬 데모에서는 기본값 그대로).
const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN ?? "http://127.0.0.1:8080";

// react-markdown (card F2, chat:AC-37) and its own dependency tree (remark/rehype/mdast/
// hast/micromark/vfile) ship ESM-only, with no CommonJS build. `next/jest`'s default Jest
// config unconditionally skips all of node_modules from transformation unless a package is
// listed in `transpilePackages` -- without this, any test importing react-markdown fails
// with "SyntaxError: Unexpected token 'export'" (jest-runtime tries to plain `require()`
// raw ESM source). This is the exact transitive closure of react-markdown's dependencies
// whose package.json declares `"type": "module"` (walked from package-lock.json,
// cross-checked against each node_modules/<pkg>/package.json -- 67 packages as of
// react-markdown@10.1.0). Listing it here also makes Next's own bundler run these through
// the same SWC transform as first-party code, which is a safe no-op for already-valid
// modern JS. If a future dependency bump reintroduces the same Jest failure, the error
// names the exact missing package -- add it here (or re-walk the whole closure the same
// way).
const REACT_MARKDOWN_ESM_DEPENDENCIES = [
  "@ungap/structured-clone",
  "bail",
  "ccount",
  "character-entities",
  "character-entities-html4",
  "character-entities-legacy",
  "character-reference-invalid",
  "comma-separated-tokens",
  "decode-named-character-reference",
  "devlop",
  "estree-util-is-identifier-name",
  "hast-util-to-jsx-runtime",
  "hast-util-whitespace",
  "html-url-attributes",
  "is-alphabetical",
  "is-alphanumerical",
  "is-decimal",
  "is-hexadecimal",
  "is-plain-obj",
  "longest-streak",
  "mdast-util-from-markdown",
  "mdast-util-mdx-expression",
  "mdast-util-mdx-jsx",
  "mdast-util-mdxjs-esm",
  "mdast-util-phrasing",
  "mdast-util-to-hast",
  "mdast-util-to-markdown",
  "mdast-util-to-string",
  "micromark",
  "micromark-core-commonmark",
  "micromark-factory-destination",
  "micromark-factory-label",
  "micromark-factory-space",
  "micromark-factory-title",
  "micromark-factory-whitespace",
  "micromark-util-character",
  "micromark-util-chunked",
  "micromark-util-classify-character",
  "micromark-util-combine-extensions",
  "micromark-util-decode-numeric-character-reference",
  "micromark-util-decode-string",
  "micromark-util-encode",
  "micromark-util-html-tag-name",
  "micromark-util-normalize-identifier",
  "micromark-util-resolve-all",
  "micromark-util-sanitize-uri",
  "micromark-util-subtokenize",
  "micromark-util-symbol",
  "micromark-util-types",
  "parse-entities",
  "property-information",
  "react-markdown",
  "remark-parse",
  "remark-rehype",
  "space-separated-tokens",
  "stringify-entities",
  "trim-lines",
  "trough",
  "unified",
  "unist-util-is",
  "unist-util-position",
  "unist-util-stringify-position",
  "unist-util-visit",
  "unist-util-visit-parents",
  "vfile",
  "vfile-message",
  "zwitch",
];

// review 2026-08-29 Medium-1: `next.config.ts` shipped zero security response headers
// (no CSP, no X-Frame-Options/frame-ancestors, no Referrer-Policy, no X-Content-Type-
// Options) and `X-Powered-By: Next.js` was left on, advertising the framework for free.
// Beyond the general hardening value, the CSP's `img-src` is the second layer (the
// `img` override in `MessageList.tsx`'s `markdownComponents` is the first) closing the
// High-2 markdown-image exfiltration channel -- even a future render path that bypasses
// the component-level guard still can't load a cross-origin image, because the browser
// itself refuses the request.
//
// `style-src` has no `'unsafe-inline'` -- measured, not assumed. The starting hypothesis
// (per review 2026-08-29 Medium-1) was that `next/font` (Geist/Geist Mono, used in
// `app/layout.tsx`) injects an inline `<style>` carrying `@font-face`/`size-adjust` rules,
// which would need `'unsafe-inline'`. An actual `npm run build && npm start` +
// `curl http://127.0.0.1:3000/` check showed otherwise for this exact toolchain (Next
// 16.3.3 with Turbopack): the served HTML has zero `<style>` elements and zero `style=`
// attributes, and the `@font-face` rules for both fonts are present in the *external*
// `.css` chunk files linked via `<link rel="stylesheet">` instead (confirmed by fetching
// those chunk URLs directly and grepping for `@font-face`). So this Next/Turbopack
// combination bundles font CSS into the same external stylesheet as everything else
// (this app's CSS Modules), and `style-src 'self'` alone covers it -- no `'unsafe-inline'`
// carve-out needed. If a future Next/font upgrade reintroduces inline font styles, the
// symptom will be the custom font silently not applying (falls back to the browser
// default) with a CSP violation in the browser console naming `style-src`; that is the
// trigger to revisit this, not something to pre-emptively loosen now.
//
// review 2026-08-29 round-3 L1: `next dev` (not `next build`/`next start`) does trip
// `style-src 'self'` today -- measured at 32 browser-console violations plus one blocked
// `eval()` call, all traced to the `next-devtools` dev overlay and to `next/font`'s
// *dev-only* runtime `<style>` injection for `@font-face`. This is a known, accepted dev-
// only exception, not the re-check trigger described above: the production build measured
// above still ships zero inline `<style>`/`style=` at both the served-HTML and the post-JS
// DOM boundary. Do not add `'unsafe-eval'` or an `'unsafe-inline'` carve-out to `style-src`
// to silence this -- that would weaken the policy that actually ships to users for a dev-
// only cosmetic console warning.
//
// review 2026-08-29 round-2 High-1: the paragraph that used to stand here claimed
// `script-src` also needed no `'unsafe-inline'` because "this app ships no inline
// `<script>`" -- that was never measured, only assumed by analogy with the (correctly
// measured) `style-src` case, and it was false. A `npm run build && npm start` response
// body inspection (see this card's dev-log `note` for the exact command and byte counts)
// shows Next's own App Router hydration payload (`self.__next_f.push(...)`, the RSC
// stream Next hands to the client runtime) ships as two inline `<script>` elements with no
// `nonce` attribute, on every route, in both `next build` and `next dev` output -- that is
// Next 16's own mechanism, not something this app's code adds or can remove without
// switching frameworks. Without `script-src` in this policy, it fell back to
// `default-src 'self'`, and `'self'` does not permit inline scripts -- so the CSP blocked
// Next's own hydration script and the app never became interactive (confirmed in headless
// Chrome: two CSP violation console messages naming `default-src 'self'` as the blocked
// directive, and the DOM frozen on its pre-hydration loading text).
//
// Fix: declare `script-src` explicitly with `'unsafe-inline'` rather than leaving it to
// the `default-src` fallback. The Next-recommended alternative -- a per-request `nonce`
// threaded through `middleware.ts` -- was considered and rejected for this card: it forces
// every route from static-prerendered to dynamically-rendered on every request (a
// deployment/perf trade-off outside this card's scope; a candidate for a follow-up card,
// not this rework). `'unsafe-inline'` is an accepted, scoped trade-off here because:
// (1) this tree has no raw-HTML-injection path for an attacker to place a *new* inline
// `<script>` into the page -- `react-markdown` never uses `dangerouslySetInnerHTML` and
// `rehype-raw` (the only plugin that re-enables raw HTML passthrough) is never added
// (see `MessageList.tsx`'s top-level [Rationale]), and no other component in this app
// renders unescaped HTML; (2) the concrete threat this CSP defends against -- the
// markdown-image exfiltration channel, review 2026-08-29 High-2 -- is stopped by
// `img-src`, a directive `'unsafe-inline'` on `script-src` does not touch at all. The two
// directives are independent controls for independent threats. `img-src 'self' data:`,
// `connect-src 'self'`, and `frame-ancestors 'none'` below are therefore kept byte-for-byte
// as they were -- review round 2 named this the one thing that must not regress while
// fixing `script-src`. Loosening `script-src` is a stopgap, not the end state: the
// middleware-`nonce` route above remains the correct long-term fix and should be revisited
// once the dynamic-rendering trade-off has its own card.
//
// review 2026-08-29 round-2 Low-1: `base-uri`/`form-action` were also missing.
// `default-src` does not act as a fallback for either of these two directives (per the CSP
// spec, they are "fetch directive" for neither `base-uri` nor `form-action`'s own
// resource-load kind), so their absence was a silent gap regardless of `default-src`'s
// value. This app has no `<base>`-injection path and no `<form>` element at all today, so
// this is pre-emptive hardening rather than a fix for an observed hole, and costs nothing
// to add alongside the `script-src` fix above.
const SECURITY_HEADERS = [
  {
    key: "Content-Security-Policy",
    value: [
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline'",
      "img-src 'self' data:",
      "connect-src 'self'",
      "style-src 'self'",
      "frame-ancestors 'none'",
      "base-uri 'none'",
      "form-action 'self'",
    ].join("; "),
  },
  // Redundant with the CSP's `frame-ancestors 'none'` above for modern browsers, but kept
  // for the handful of older/embedded user agents that only honor this legacy header.
  { key: "X-Frame-Options", value: "DENY" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  { key: "X-Content-Type-Options", value: "nosniff" },
];

const nextConfig: NextConfig = {
  transpilePackages: REACT_MARKDOWN_ESM_DEPENDENCIES,
  // Stops `X-Powered-By: Next.js` from being sent on every response (review 2026-08-29
  // Medium-1) -- it costs nothing functionally and only ever helped an attacker fingerprint
  // the stack.
  poweredByHeader: false,
  async headers() {
    return [
      {
        // Applies to every route this app serves (there is only one: the chat page and
        // its Next-internal assets) -- there is no separate public/marketing route that
        // would need a different, looser policy.
        source: "/:path*",
        headers: SECURITY_HEADERS,
      },
    ];
  },
  // `next dev` 가 기본으로 frontend/AGENTS.md · frontend/CLAUDE.md 를 자동 생성한다(Next.js
  // 16 신규 기능, 실행 로그로 실측 확인). 이 저장소는 루트 CLAUDE.md 하나를 단일 진실
  // 공급원으로 삼는 엄격한 문서 거버넌스를 쓰고 있어(전역 규약, 프로젝트 CLAUDE.md), 하위
  // 디렉터리에 도구가 임의로 추가하는 두 번째 CLAUDE.md 를 허용하지 않는다.
  agentRules: false,
  async rewrites() {
    // review 2026-08-29 High-1: the previous `/api/:path*` catch-all also proxied
    // `/api/admin/**` (unauthenticated usage read + unauthenticated multipart document
    // upload, see backend UsageController / DocumentUploadController) to any LAN client
    // that could reach this dev/demo server, defeating the backend's `server.address:
    // 127.0.0.1` loopback restriction. `src/lib/api/**` only ever calls three path
    // families, so the proxy is narrowed to exactly those instead of a wildcard.
    return [
      {
        // 브라우저가 '/api/session' 를 이 프론트와 같은 오리진(예: localhost:3000)으로
        // 호출하면 Next 서버가 백엔드로 프록시한다 -- 브라우저 입장에서는 여전히 같은
        // 오리진 요청이라 HttpOnly + SameSite=Lax 세션 쿠키(chatbot_sid)가 자동으로 실린다.
        // (chat Spec §7 OQ-11 확정, AC-40, Tech Review §4 데이터 흐름)
        source: "/api/session",
        destination: `${BACKEND_ORIGIN}/api/session`,
      },
      {
        // `lib/api/models.ts` -- GET /api/models only, no sub-paths.
        source: "/api/models",
        destination: `${BACKEND_ORIGIN}/api/models`,
      },
      {
        // `lib/api/conversations.ts` -- POST (create) / GET (list) on the collection
        // itself, no `:path` segment.
        source: "/api/conversations",
        destination: `${BACKEND_ORIGIN}/api/conversations`,
      },
      {
        // `lib/api/conversations.ts` (GET one, DELETE) and `lib/api/messages.ts`
        // (GET/POST .../messages) -- both need the `{id}` and `{id}/messages` sub-paths,
        // so this one keeps a wildcard but scoped under `/api/conversations/` only.
        source: "/api/conversations/:path*",
        destination: `${BACKEND_ORIGIN}/api/conversations/:path*`,
      },
    ];
  },
};

export default nextConfig;
