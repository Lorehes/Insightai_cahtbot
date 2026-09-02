/** Node-native checks isolate the Sites dispatcher and gateway from live data. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { handle } from './worker.mjs';

const env = { SITES_GATEWAY_ORIGIN: 'https://gateway.example', SITES_GATEWAY_TOKEN: 'a'.repeat(64),
  SITES_GATEWAY_ADMIN_TOKEN: 'b'.repeat(64),
  ASSETS: { fetch: async () => new Response('<html>chat</html>') } };
const request = (path, extras = {}) => new Request('https://site.example' + path, extras);
const sid = '12345678-1234-1234-1234-123456789abc';

test('anonymous visitors can access pages, sessions and administrator operations', async () => {
  const upstream = async () => new Response('{}', { headers: { 'content-type': 'application/json' } });
  assert.equal((await handle(request('/'), env)).status, 200);
  assert.equal((await handle(request('/api/session'), env, upstream)).status, 200);
  assert.equal((await handle(request('/admin/documents'), env)).status, 200);
  assert.equal((await handle(request('/api/admin/documents'), env, upstream)).status, 200);
  assert.equal((await handle(request('/admin/usage'), env)).status, 200);
  assert.equal((await handle(request('/api/admin/usage'), env, upstream)).status, 200);
  assert.equal((await handle(request('/api/admin/documents', { method: 'POST', headers: { origin: 'https://site.example' } }), env, upstream)).status, 200);
  assert.equal((await handle(request('/api/admin/documents/' + sid, { method: 'DELETE', headers: { origin: 'https://site.example' } }), env, upstream)).status, 200);
  assert.equal((await worker.fetch(request('/'), env, {})).status, 200);
});

test('unreviewed routes, cross-origin writes and absent config fail closed', async () => {
  assert.equal((await handle(request('/api/admin/documents/orphan-chunks/cleanup', { method: 'POST' }), env)).status, 404);
  assert.equal((await handle(request('/api/conversations', { method: 'POST', headers: { origin: 'https://attacker.example' } }), env)).status, 403);
  assert.equal((await handle(request('/api/session'), { ...env, SITES_GATEWAY_TOKEN: '' })).status, 503);
  assert.equal((await handle(request('/api/session'), { ...env, SITES_GATEWAY_ORIGIN: 'http://gateway.example' })).status, 503);
});

test('anonymous sessions use signed cookies; forged cookies and caller secrets are discarded', async () => {
  const seen = [];
  const upstream = async (url, options) => {
    seen.push(options);
    return new Response('data: reply\n\n', { headers: { 'content-type': 'text/event-stream',
      'set-cookie': `chatbot_sid=${sid}; Path=/; HttpOnly; SameSite=Lax` } });
  };
  const first = await handle(request('/api/session', { headers: {
    cookie: `chatbot_sid=${sid}`, 'x-insight-gateway-token': 'attacker' } }), env, upstream);
  assert.equal(seen[0].headers.get('cookie'), null);
  assert.equal(seen[0].headers.get('x-insight-gateway-token'), env.SITES_GATEWAY_TOKEN);
  assert.equal(await first.text(), 'data: reply\n\n');
  const cookie = first.headers.get('set-cookie');
  assert.match(cookie, /Secure; HttpOnly; SameSite=Lax/);
  const pair = cookie.split(';')[0];
  await handle(request('/api/session', { headers: { cookie: pair } }), env, upstream);
  assert.equal(seen[1].headers.get('cookie'), `chatbot_sid=${sid}`);
  await handle(request('/api/session', { headers: { cookie: pair.slice(0, -1) + (pair.endsWith('0') ? '1' : '0') } }), env, upstream);
  assert.equal(seen[2].headers.get('cookie'), null);
  await handle(request('/api/admin/documents'), env, upstream);
  assert.equal(seen[3].headers.get('x-insight-gateway-token'), env.SITES_GATEWAY_ADMIN_TOKEN);
  assert.equal(seen[3].headers.has('oai-authenticated-user-id'), false);
});

test('redirects and backend failures never leak credentials or internal error details', async () => {
  const redirect = await handle(request('/api/session'), env,
    async () => new Response(null, { status: 302, headers: { location: 'https://attacker.example' } }));
  assert.equal(redirect.status, 502);
  const error = await handle(request('/api/session'), env, async () => { throw new Error('private server detail'); });
  assert.equal(error.status, 502);
  assert.doesNotMatch(await error.text(), /private server detail/);
});
