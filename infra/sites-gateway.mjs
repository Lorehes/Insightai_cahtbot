/**
 * Authenticated loopback gateway for Sites. The HTTPS tunnel must target this
 * gateway, never the anonymous Spring backend. Secrets remain server-only.
 */
import http from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

const UUID = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const ROUTES = [
  [/^\/api\/(session|models)$/, ['GET'], false],
  [/^\/api\/conversations$/, ['GET', 'POST'], false],
  [new RegExp(`^/api/conversations/${UUID}$`), ['GET', 'PATCH', 'DELETE'], false],
  [new RegExp(`^/api/conversations/${UUID}/messages$`), ['GET', 'POST'], false],
  [/^\/api\/admin\/documents$/, ['GET', 'POST'], true],
  [new RegExp(`^/api/admin/documents/${UUID}$`), ['DELETE'], true],
  [/^\/api\/admin\/usage$/, ['GET'], true],
];

/** Match exact reviewed routes so the tunnel never exposes arbitrary endpoints. */
export function routeAccess(path, method) {
  const route = ROUTES.find(([pattern, methods]) => pattern.test(path) && methods.includes(method));
  return route ? (route[2] ? 'admin' : 'chat') : null;
}

/** Compare fixed-format server secrets in constant time. */
function matches(actual, expected) {
  return typeof actual === 'string' && /^[a-f0-9]{64}$/.test(actual)
    && timingSafeEqual(Buffer.from(actual), Buffer.from(expected));
}

/** Create without listening so tests can supply an isolated fake backend. */
export function createGateway({ token, adminToken, backendPort = 8080 }) {
  if (!/^[a-f0-9]{64}$/.test(token ?? '') || !/^[a-f0-9]{64}$/.test(adminToken ?? '') || token === adminToken) {
    throw new Error('Two distinct 32-byte lowercase hex gateway secrets are required.');
  }
  if (!Number.isInteger(backendPort) || backendPort < 1 || backendPort > 65535) throw new Error('Invalid backend port');
  const server = http.createServer((req, res) => {
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    const reject = (status) => { res.writeHead(status); res.end(); };
    if (!req.url?.startsWith('/api/') || /[%\\]|\/\//.test(req.url.split('?')[0])) return reject(404);
    const access = routeAccess(req.url.split('?')[0], req.method);
    if (!access) return reject(404);
    if (!matches(req.headers['x-insight-gateway-token'], access === 'admin' ? adminToken : token)) return reject(403);
    const limit = 32 * 1024 * 1024;
    const length = Number(req.headers['content-length'] ?? 0);
    if (!Number.isFinite(length) || length > limit) return reject(413);
    const headers = {};
    for (const name of ['accept', 'content-type', 'cookie']) {
      if (req.headers[name]) headers[name] = req.headers[name];
    }
    // Fixed loopback target: no caller-controlled hosts or forwarded secrets.
    const upstream = http.request({ hostname: '127.0.0.1', port: backendPort,
      path: req.url, method: req.method, headers }, (reply) => {
      res.statusCode = reply.statusCode ?? 502;
      for (const name of ['content-type', 'set-cookie', 'content-disposition']) {
        if (reply.headers[name]) res.setHeader(name, reply.headers[name]);
      }
      res.setHeader('Cache-Control', 'no-store, no-transform');
      reply.on('error', () => res.destroy());
      reply.pipe(res);
    });
    upstream.setTimeout(120_000, () => upstream.destroy(new Error('Upstream timeout')));
    upstream.on('error', () => {
      if (!res.headersSent) reject(502);
      else if (!res.writableEnded) res.destroy();
    });
    let received = 0;
    req.on('data', (chunk) => {
      received += chunk.length;
      if (received > limit) {
        req.unpipe(upstream);
        if (!res.headersSent) reject(413);
        upstream.destroy();
      }
    });
    req.on('aborted', () => upstream.destroy());
    res.on('close', () => upstream.destroy());
    req.pipe(upstream);
  });
  server.headersTimeout = 15_000;
  server.requestTimeout = 120_000;
  return server;
}

/** Read environment configuration only at the executable boundary. */
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  createGateway({ token: process.env.SITES_GATEWAY_TOKEN,
    adminToken: process.env.SITES_GATEWAY_ADMIN_TOKEN })
    .listen(8788, '127.0.0.1', () => console.log('Sites gateway listening on 127.0.0.1:8788'));
}
