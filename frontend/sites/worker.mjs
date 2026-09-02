/**
 * Public Sites edge: anonymous browser sessions with signed cookies and
 * server-only gateway tokens. Administration is public by explicit owner choice;
 * this is not an employee-only service and must contain no confidential data.
 */
const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const COOKIE = '__Host-insight-session';
const RULES = [
  [/^\/api\/(session|models)$/, ['GET']],
  [/^\/api\/conversations$/, ['GET', 'POST']],
  [new RegExp(`^/api/conversations/${UUID}$`), ['GET', 'PATCH', 'DELETE']],
  [new RegExp(`^/api/conversations/${UUID}/messages$`), ['GET', 'POST']],
  [/^\/api\/admin\/documents$/, ['GET', 'POST']],
  [new RegExp(`^/api/admin/documents/${UUID}$`), ['DELETE']],
  [/^\/api\/admin\/usage$/, ['GET']],
];
const SECURITY = {
  'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; style-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
  'X-Frame-Options': 'DENY', 'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'strict-origin-when-cross-origin',
};

/** Keep errors bounded and never echo gateway addresses, secrets or upstream errors. */
function failure(status, message) {
  return Response.json({ title: message }, { status, headers: { ...SECURITY, 'Cache-Control': 'no-store' } });
}

/** Authenticate anonymous session cookies without requiring a platform account. */
async function sessionSignature(secret, sid) {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const bytes = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(JSON.stringify(['sites-anonymous-session-v1', sid])));
  return Array.from(new Uint8Array(bytes), (byte) => byte.toString(16).padStart(2, '0')).join('');
}

/** Serve static pages or forward explicitly permitted API operations as a stream. */
export async function handle(request, env, fetchUpstream = fetch) {
  const url = new URL(request.url);
  const adminPath = url.pathname === '/admin' || url.pathname.startsWith('/admin/') || url.pathname.startsWith('/api/admin/');
  if (!url.pathname.startsWith('/api/')) {
    if (!env.ASSETS) return failure(503, 'Site assets unavailable');
    const response = await env.ASSETS.fetch(request);
    const headers = new Headers(response.headers);
    for (const [name, value] of Object.entries(SECURITY)) headers.set(name, value);
    return new Response(response.body, { status: response.status, headers });
  }
  if (!RULES.some(([pattern, methods]) => pattern.test(url.pathname) && methods.includes(request.method))) return failure(404, 'Not found');
  if (!['GET', 'HEAD'].includes(request.method) && request.headers.get('origin') !== url.origin) return failure(403, 'Cross-origin request rejected');
  if (!/^[a-f0-9]{64}$/.test(env.SITES_GATEWAY_TOKEN ?? '') || !/^[a-f0-9]{64}$/.test(env.SITES_GATEWAY_ADMIN_TOKEN ?? '')) return failure(503, 'Gateway not configured');
  let origin;
  try {
    origin = new URL(env.SITES_GATEWAY_ORIGIN);
    if (origin.protocol !== 'https:' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw new Error();
  } catch { return failure(503, 'Gateway not configured'); }
  const headers = new Headers();
  for (const name of ['content-type', 'accept']) {
    if (request.headers.has(name)) headers.set(name, request.headers.get(name));
  }
  headers.set('x-insight-gateway-token', adminPath ? env.SITES_GATEWAY_ADMIN_TOKEN : env.SITES_GATEWAY_TOKEN);
  const cookie = (request.headers.get('cookie') ?? '').split(';').map((v) => v.trim()).find((v) => v.startsWith(COOKIE + '='));
  if (cookie) {
    const [sid, signature] = cookie.slice(COOKIE.length + 1).split('.');
    if (new RegExp(`^${UUID}$`).test(sid) && signature === await sessionSignature(env.SITES_GATEWAY_TOKEN, sid)) {
      headers.set('cookie', `chatbot_sid=${sid}`);
    }
  }
  try {
    const response = await fetchUpstream(new URL(url.pathname + url.search, origin), {
      method: request.method, headers, redirect: 'manual', signal: request.signal,
      body: ['GET', 'HEAD'].includes(request.method) ? undefined : request.body,
      duplex: 'half',
    });
    if (response.status >= 300 && response.status < 400) return failure(502, 'Unexpected gateway redirect');
    const out = new Headers(SECURITY);
    out.set('Cache-Control', 'no-store, no-transform');
    for (const name of ['content-type', 'content-disposition']) {
      if (response.headers.has(name)) out.set(name, response.headers.get(name));
    }
    const sid = response.headers.get('set-cookie')?.match(new RegExp(`(?:^|[, ])chatbot_sid=(${UUID})(?:;|$)`))?.[1];
    if (sid) out.set('Set-Cookie', `${COOKIE}=${sid}.${await sessionSignature(env.SITES_GATEWAY_TOKEN, sid)}; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=7776000`);
    return new Response(response.body, { status: response.status, headers: out });
  } catch { return failure(502, 'Local backend is unavailable'); }
}

export default { fetch: (request, env) => handle(request, env) };
