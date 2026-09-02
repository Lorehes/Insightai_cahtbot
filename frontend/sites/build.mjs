/** Build an isolated Next static export and a Worker without changing local start/dev. */
import { spawnSync } from 'node:child_process';
import { mkdir, cp, copyFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const result = spawnSync(process.execPath, ['node_modules/next/dist/bin/next', 'build'], {
  cwd: root, stdio: 'inherit', env: { ...process.env, INSIGHT_SITES_BUILD: '1' },
});
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
await mkdir(new URL('../dist/server/', import.meta.url), { recursive: true });
await cp(new URL('../.next-sites/', import.meta.url), new URL('../dist/client/', import.meta.url), { recursive: true });
await copyFile(new URL('./worker.mjs', import.meta.url), new URL('../dist/server/index.js', import.meta.url));
console.log('Sites Worker and static assets built in frontend/dist');
