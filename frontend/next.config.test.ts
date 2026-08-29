import nextConfig from "./next.config";

/**
 * Guards `next.config.ts`'s `rewrites()` against silently widening back to a bare
 * `/api/:path*` catch-all. Review 2026-08-29 High-1 found that shape proxying the
 * backend's unauthenticated `/api/admin/**` endpoints (usage read + unauthenticated
 * multipart document upload) to any LAN client, defeating the backend's `server.address:
 * 127.0.0.1` loopback restriction; the fix narrowed `source` to the exact path families
 * `src/lib/api/**` actually calls. This test calls the real `rewrites()` function (no
 * mocking -- there is nothing to mock, it is a pure config value) so a future edit that
 * reintroduces a directly-under-`/api` wildcard for convenience fails a test instead of
 * only a manual review.
 */
describe("next.config.ts rewrites", () => {
  it("does not proxy a bare /api/:path* wildcard (regression guard for review 2026-08-29 High-1)", async () => {
    // given
    const rewritesFn = nextConfig.rewrites;
    if (!rewritesFn) throw new Error("next.config.ts must define rewrites()");

    // when
    const result = await rewritesFn();
    const sources = (Array.isArray(result) ? result : result.beforeFiles ?? []).map(
      (rule) => rule.source,
    );

    // then
    expect(sources).not.toContain("/api/:path*");
    // No rule's source may be a single path segment directly under /api followed by a
    // wildcard (e.g. "/api/:anything*") -- that shape is exactly as broad as the
    // catch-all High-1 flagged, regardless of the parameter's name.
    expect(sources.some((source) => /^\/api\/:[^/]+\*$/.test(source))).toBe(false);
  });

  it("proxies exactly the four path families src/lib/api/** calls", async () => {
    // given
    const rewritesFn = nextConfig.rewrites;
    if (!rewritesFn) throw new Error("next.config.ts must define rewrites()");

    // when
    const result = await rewritesFn();
    const sources = (Array.isArray(result) ? result : result.beforeFiles ?? []).map(
      (rule) => rule.source,
    );

    // then
    expect(sources.sort()).toEqual(
      ["/api/session", "/api/models", "/api/conversations", "/api/conversations/:path*"].sort(),
    );
  });
});

/**
 * chat:AC-46 (신설 2026-08-29, review Medium-1 -- see Spec §4 for the sourcing/approval
 * record; CSP value updated 2026-08-29 rework round 2, review High-1 -- see Spec §4 for
 * the pending AC-46 wording update this rework hands off to dev-qa-tester). Calls the real
 * `headers()` function directly (pure config value, nothing to mock) and asserts the exact
 * header values dev-frontend-engineer measured with a live `npm run build && npm start`
 * round trip (2026-08-29 frontend dev-log `note` carries the full CSP string and the
 * hydration check method) -- this test does not repeat that live browser check itself,
 * only that the config value producing it has not silently drifted, the same relationship
 * AC-40's `client.test.ts` tests have to that AC's own live-cookie verification. Review
 * round 2 High-1 is the reason this distinction matters here specifically: this test alone
 * passed even while the CSP it asserts made the app fail to hydrate in every browser,
 * because a header-value assertion cannot see what the browser does with that header.
 */
describe("next.config.ts headers", () => {
  it("[chat:AC-46] sets the security response headers with their measured exact values on every route, and disables X-Powered-By", async () => {
    // given
    const headersFn = nextConfig.headers;
    if (!headersFn) throw new Error("next.config.ts must define headers()");

    // when
    const rules = await headersFn();

    // then
    expect(rules).toHaveLength(1);
    expect(rules[0].source).toBe("/:path*");
    const headerMap = Object.fromEntries(rules[0].headers.map((h) => [h.key, h.value]));
    expect(headerMap["Content-Security-Policy"]).toBe(
      "default-src 'self'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
        "connect-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; " +
        "form-action 'self'",
    );
    expect(headerMap["X-Frame-Options"]).toBe("DENY");
    expect(headerMap["Referrer-Policy"]).toBe("strict-origin-when-cross-origin");
    expect(headerMap["X-Content-Type-Options"]).toBe("nosniff");
    expect(nextConfig.poweredByHeader).toBe(false);
  });
});
