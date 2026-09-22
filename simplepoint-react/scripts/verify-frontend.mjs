// One reproducible, fail-fast quality gate. Requires installed dependencies and Chrome.
import {spawn} from 'node:child_process';
import {readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const packageManager = process.env.npm_execpath
  ? [process.execPath, process.env.npm_execpath]
  : ['corepack', 'pnpm'];

async function run(command, args) {
  console.log(`\n[quality] ${command} ${args.join(' ')}`);
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, {cwd: root, env: process.env, stdio: 'inherit'});
    child.once('error', reject);
    child.once('exit', (code, signal) => code === 0 ? resolve() : reject(new Error(`Check failed (${signal ?? code})`)));
  });
}
const pnpm = (...args) => run(packageManager[0], [...packageManager.slice(1), ...args]);
const node = (...args) => run(process.execPath, args);

async function testsIn(directory) {
  const tests = [];
  for (const entry of await readdir(path.join(root, directory), {withFileTypes: true})) {
    const relative = path.join(directory, entry.name);
    if (entry.isDirectory() && !['node_modules', 'dist', '.cache', '@mf-types'].includes(entry.name)) {
      tests.push(...await testsIn(relative));
    } else if (entry.isFile() && entry.name.endsWith('.test.mjs')) tests.push(relative);
  }
  return tests;
}

for (const module of ['common', 'audit', 'dna', 'ai', 'host', 'mocks']) {
  const directory = module === 'mocks' ? module : `simplepoint-${module}`;
  await pnpm('exec', 'tsc', '-p', `modules/${directory}/tsconfig.json`, '--noEmit');
}
const tests = (await Promise.all(['libs', 'modules', 'scripts'].map(testsIn))).flat().sort();
if (!tests.length) throw new Error('No regression tests discovered');
await node('--experimental-strip-types', '--test', ...tests);
await node('scripts/sync-i18n-bundles.mjs', '--check');
await pnpm('--filter', './modules/*', '--if-present', 'run', 'build');
await node('scripts/check-mf-entry-sizes.mjs');
await node('scripts/smoke-mf.mjs');
console.log('\n[quality] All frontend checks passed. Browser APIs used isolated fixtures, not real accounts.');
