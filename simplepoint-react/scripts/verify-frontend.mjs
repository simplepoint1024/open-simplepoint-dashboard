// One reproducible, fail-fast quality gate. Requires installed dependencies and Chrome.
import {spawn} from 'node:child_process';
import {readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const packageManager = process.env.npm_execpath
  ? [process.execPath, process.env.npm_execpath]
  : ['corepack', 'pnpm'];

async function run(command, args, environment = {}) {
  console.log(`\n[quality] ${command} ${args.join(' ')}`);
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, {cwd: root, env: {...process.env, ...environment}, stdio: 'inherit'});
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

for (const module of ['common', 'audit', 'dna', 'ai', 'app', 'workflow', 'host', 'mocks']) {
  const directory = module === 'mocks' ? module : `simplepoint-${module}`;
  await pnpm('exec', 'tsc', '-p', `modules/${directory}/tsconfig.json`, '--noEmit');
}
const tests = (await Promise.all(['libs', 'modules', 'scripts'].map(testsIn))).flat().sort();
if (!tests.length) throw new Error('No regression tests discovered');
await node('--experimental-strip-types', '--test', ...tests);
await node('scripts/ui-standards/audit.mjs', '--strict');
await node('scripts/sync-i18n-bundles.mjs', '--check');
await pnpm('--filter', './modules/*', '--if-present', 'run', 'build');
await node('scripts/check-mf-entry-sizes.mjs');
await node('scripts/smoke-mf.mjs');
for (const policy of ['root', 'child']) await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_FIELD_POLICY_ONLY: policy});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_FIELD_LIFECYCLE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_FIELD_PROTECTED_ONLY: '1'});
for (const mode of ['writes', 'readonly', 'permissions']) await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_CATALOG_ONLY: mode});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_WORKFLOW_LIFECYCLE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_WORKFLOW_PERSONAL_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_BILLING_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_MCP_LIFECYCLE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_RUNTIME_MUTATIONS_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_JDBC_ARTIFACTS_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_APP_WORKBENCH_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_REDIS_MUTATIONS_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_KNOWLEDGE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_API_KEYS_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_DICTIONARY_LIFECYCLE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_USER_PICKER_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_CONFIGURATION_LIFECYCLE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_APPLICATION_RESOURCES_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_RESOURCE_MANAGEMENT_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_ROLE_LIFECYCLE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_MEMBER_LIFECYCLE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_NOTIFICATION_LIFECYCLE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_ORGANIZATION_PICKER_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_FORM_SUBMISSION_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_DATA_SCOPE_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_IDENTITY_PROVIDER_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_AI_CONFIGURATION_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_EXACT_DECIMAL_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_PROFILE_ACCESS_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_EXPRESSION_MODES_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_CONDITION_MODES_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_EXPRESSION_STRINGS_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_EXPRESSION_NUMERIC_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_WORKFLOW_FORM_COMMIT_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_PRIORITY_LITERAL_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_DUE_DATE_LITERAL_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_DUE_DATE_CALENDAR_ONLY: '1'});
await run(process.execPath, ['scripts/smoke-mf.mjs'], {MF_SMOKE_ROLE_AUTHORITY_ONLY: '1'});
console.log('\n[quality] All frontend checks passed. Browser APIs used isolated fixtures, not real accounts.');
