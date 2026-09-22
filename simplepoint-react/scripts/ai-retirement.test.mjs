import assert from 'node:assert/strict';
import {existsSync, readFileSync} from 'node:fs';
import test from 'node:test';

const root = new URL('../../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');
const retired = /(?:skill|agent|workflow)/i;

test('retired AI pages, backend modules and schema initialization stay removed', () => {
  const resources = JSON.parse(read('simplepoint-services/simplepoint-service-ai/src/main/resources/META-INF/simplepoint/resources/ai.json'));
  const walk = value => {
    if (!value || typeof value !== 'object') return;
    if (value.code) assert.equal(retired.test(value.code), false, value.code);
    if (value.component) assert.equal(retired.test(value.component), false, value.component);
    Object.values(value).forEach(walk);
  };
  walk(resources);
  const settings = read('settings.gradle.kts');
  assert.doesNotMatch(settings, /simplepoint-plugin-ai-(skill|agent|workflow)-|simplepoint-service-(agent|workflow)-runtime/);
  assert.doesNotMatch(read('simplepoint-services/simplepoint-service-ai/src/main/resources/schema.sql'), /simpoint_ai_(skill|agent|workflow)/);
  assert.doesNotMatch(read('simplepoint-react/modules/simplepoint-ai/src/api/index.ts'), /ai-workbench\.(skills|agents|workflows)/);
  for (const feature of ['skill', 'agent', 'workflow']) {
    for (const layer of ['api', 'implementation']) {
      assert.equal(existsSync(new URL(`simplepoint-plugins/simplepoint-plugin-ai/simplepoint-plugin-ai-${feature}-${layer}/build.gradle.kts`, root)), false);
    }
  }
});

test('retirement migration targets exactly 26 dedicated tables without cascading', () => {
  const sql = read('scripts/sql/cleanup-ai-orchestration.sql');
  const tables = [...sql.matchAll(/^DROP TABLE IF EXISTS public\.(\w+);$/gm)].map(match => match[1]);
  assert.equal(tables.length, 26);
  assert.equal(new Set(tables).size, 26);
  assert.ok(tables.every(table => /^simpoint_ai_(skill|agent|workflow)/.test(table)));
  assert.doesNotMatch(sql, /^DROP .*CASCADE/im);
  assert.match(sql, /^BEGIN;$/m);
  assert.match(sql, /^COMMIT;$/m);
  assert.match(sql, /DELETE FROM public\.simpoint_ac_role_resource_grants/);
  assert.match(sql, /DELETE FROM public\.simpoint_i18n_namespaces/);
});

test('MCP direct tooling remains available without retired runtime services', () => {
  const compose = read('docker-compose.yml');
  assert.doesNotMatch(compose, /^  (agent|workflow)-runtime:/m);
  assert.match(compose, /^  mcp-gateway:/m);
  assert.match(compose, /^  tool-runtime:/m);
  const runner = read('scripts/node/verify-community-mcp-e2e.mjs');
  assert.match(runner, /async function discoverAndCall/);
  assert.match(runner, /async function verifyLedger/);
  assert.doesNotMatch(runner, /\/workbench\/(skills|agents|workflows)/);
  assert.ok(existsSync(new URL('templates/ai-extensions/mcp-tool-typescript/package.json', root)));
});
