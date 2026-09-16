import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DASHBOARD_PATH,
  migrateLegacyAiWorkspacePath,
  migrateLegacyAiWorkspaceTabs,
  resolveLegacyAiWorkspaceTarget,
} from './legacyAiWorkspace.ts';

test('legacy AI workspace resolves to the first authorized internal page', () => {
  const routes = [{
    path: '/ai/workbench',
    children: [
      {path: '/ai/workbench/workspace', component: 'ai/workbench/Workspace'},
      {path: '/ai/workbench/models', component: 'ai/workbench/Models'},
      {path: '/ai/workbench/agents', component: 'ai/workbench/Agents'},
    ],
  }];
  assert.equal(resolveLegacyAiWorkspaceTarget(routes), '/ai/workbench/models');
});

test('external and iframe routes are not selected as migration targets', () => {
  const routes = [
    {path: '/ai/workbench/external', component: 'external:https://example.com'},
    {path: '/ai/workbench/report', component: 'iframe:https://example.com'},
  ];
  assert.equal(resolveLegacyAiWorkspaceTarget(routes), DASHBOARD_PATH);
});

test('hidden and disabled pages are never selected as migration targets', () => {
  const routes = [{
    path: '/ai/workbench',
    children: [
      {path: '/ai/workbench/designer', component: 'ai/workbench/Designer', routeKind: 'hidden'},
      {path: '/ai/workbench/models', component: 'ai/workbench/Models', disabled: true},
      {path: '/ai/workbench/agents', component: 'ai/workbench/Agents'},
    ],
  }];
  assert.equal(resolveLegacyAiWorkspaceTarget(routes), '/ai/workbench/agents');
});

test('users without an authorized AI child fall back to the dashboard', () => {
  assert.equal(resolveLegacyAiWorkspaceTarget([]), DASHBOARD_PATH);
  assert.equal(resolveLegacyAiWorkspaceTarget([{path: '/ai/workbench'}]), DASHBOARD_PATH);
});

test('only the retired landing paths are migrated', () => {
  assert.equal(
    migrateLegacyAiWorkspacePath('/ai/workbench/workspace', '/ai/workbench/models'),
    '/ai/workbench/models',
  );
  assert.equal(
    migrateLegacyAiWorkspacePath('/ai/workbench', '/ai/workbench/models'),
    '/ai/workbench/models',
  );
  assert.equal(
    migrateLegacyAiWorkspacePath('/ai/workbench/agents', '/ai/workbench/models'),
    '/ai/workbench/agents',
  );
});

test('legacy paths are preserved until authorized routes finish loading', () => {
  assert.equal(
    migrateLegacyAiWorkspacePath(
      '/ai/workbench/workspace',
      '/dashboard',
      false,
    ),
    '/ai/workbench/workspace',
  );
  assert.equal(
    migrateLegacyAiWorkspacePath(
      '/ai/workbench/workspace',
      '/ai/workbench/models',
      true,
    ),
    '/ai/workbench/models',
  );
});

test('persisted legacy tab keys and locations migrate atomically and deduplicate', () => {
  assert.deepEqual(
    migrateLegacyAiWorkspaceTabs([
      {
        key: '/ai/workbench/workspace',
        location: '/ai/workbench/workspace?from=bookmark',
        label: 'Legacy',
      },
      {
        key: '/ai/workbench/models',
        location: '/ai/workbench/models?model=one',
        label: 'Models',
      },
    ], '/ai/workbench/models', true),
    [{
      key: '/ai/workbench/models',
      location: '/ai/workbench/models',
      label: 'Legacy',
    }],
  );
});

test('persisted legacy tabs remain untouched while route authorization is loading', () => {
  const tabs = [{
    key: '/ai/workbench/workspace',
    location: '/ai/workbench/workspace?from=bookmark',
  }];
  assert.deepEqual(
    migrateLegacyAiWorkspaceTabs(tabs, '/dashboard', false),
    tabs,
  );
});
