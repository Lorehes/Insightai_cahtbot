/** Isolated fake-backend tests; no production chatbot or database requests. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { createGateway, routeAccess } from './sites-gateway.mjs';

test('missing or equal secrets fail closed', () => {
  assert.throws(() => createGateway({}));
  assert.throws(() => createGateway({ token: 'a'.repeat(64), adminToken: 'a'.repeat(64) }));
});

test('only reviewed exact method/path pairs are allowed', () => {
  assert.equal(routeAccess('/api/session', 'GET'), 'chat');
  assert.equal(routeAccess('/api/admin/documents', 'POST'), 'admin');
  assert.equal(routeAccess('/api/admin/documents/orphan-chunks/cleanup', 'POST'), null);
  assert.equal(routeAccess('/api/session', 'DELETE'), null);
  assert.equal(routeAccess('/actuator/env', 'GET'), null);
});

test('authentication, admin separation, cookie and streaming forwarding', async (t) => {
  let calls = 0;
  let seenSecret;
  const backend = http.createServer((req, res) => {
    calls++;
    seenSecret = req.headers['x-insight-gateway-token'];
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Set-Cookie', 'chatbot_sid=test; HttpOnly; SameSite=Lax');
    res.write('data: first\n\n');
    setTimeout(() => res.end('data: last\n\n'), 10);
  }).listen(0, '127.0.0.1');
  await once(backend, 'listening');
  const token = 'a'.repeat(64);
  const adminToken = 'b'.repeat(64);
  const gateway = createGateway({ token, adminToken, backendPort: backend.address().port }).listen(0, '127.0.0.1');
  await once(gateway, 'listening');
  t.after(() => { gateway.closeAllConnections(); gateway.close(); backend.closeAllConnections(); backend.close(); });
  const url = `http://127.0.0.1:${gateway.address().port}`;
  assert.equal((await fetch(url + '/api/session')).status, 403);
  assert.equal((await fetch(url + '/api/admin/documents', { headers: { 'x-insight-gateway-token': token } })).status, 403);
  assert.equal(calls, 0);
  const response = await fetch(url + '/api/session', { headers: { 'x-insight-gateway-token': token } });
  assert.equal(response.status, 200);
  assert.equal(await response.text(), 'data: first\n\ndata: last\n\n');
  assert.match(response.headers.get('set-cookie'), /HttpOnly/);
  assert.equal(seenSecret, undefined);
  const adminResponse = await fetch(url + '/api/admin/documents', { headers: { 'x-insight-gateway-token': adminToken } });
  assert.equal(adminResponse.status, 200);
  await adminResponse.text();
});
