import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canPerformAgentWorkbenchAction,
  denyAllAgentWorkbenchPermissions,
  normalizeAgentWorkbenchPermissions,
} from './permissions.ts';

const fields = [
  'view',
  'create',
  'edit',
  'delete',
  'manageVersions',
  'publish',
  'execute',
  'approve',
  'control',
  'manageMemory',
  'intervene',
];

test('deny-all covers every Agent workbench permission field', () => {
  assert.deepEqual(Object.keys(denyAllAgentWorkbenchPermissions), fields);
  assert.ok(fields.every((field) => denyAllAgentWorkbenchPermissions[field] === false));
});

test('normalization only grants an exact boolean true', () => {
  const normalized = normalizeAgentWorkbenchPermissions({
    view: true,
    create: 1,
    edit: 'true',
    delete: new Boolean(true),
    manageVersions: false,
    publish: null,
    execute: undefined,
    approve: {},
    control: [],
    manageMemory: '1',
    intervene: true,
    ignoredFuturePermission: true,
  });

  assert.deepEqual(normalized, {
    ...denyAllAgentWorkbenchPermissions,
    view: true,
    intervene: true,
  });
  assert.deepEqual(
    normalizeAgentWorkbenchPermissions(undefined),
    denyAllAgentWorkbenchPermissions,
  );
  assert.deepEqual(
    normalizeAgentWorkbenchPermissions(null),
    denyAllAgentWorkbenchPermissions,
  );
  assert.deepEqual(
    normalizeAgentWorkbenchPermissions([true]),
    denyAllAgentWorkbenchPermissions,
  );
  assert.deepEqual(normalizeAgentWorkbenchPermissions({create: true}), {
    ...denyAllAgentWorkbenchPermissions,
    create: true,
  });
});

test('every raw permission maps to its direct workbench action', () => {
  for (const field of fields) {
    const permissions = {...denyAllAgentWorkbenchPermissions, [field]: true};
    assert.equal(canPerformAgentWorkbenchAction(permissions, field), true, field);
  }
});

test('related Agent actions share the intended resource permission', () => {
  const groups = {
    manageVersions: ['createVersion'],
    publish: ['publish', 'deprecate'],
    execute: ['execute', 'cancel'],
    approve: ['approve', 'reject'],
    control: ['control', 'pause', 'resume'],
    manageMemory: ['manageMemory', 'deleteMemory'],
    intervene: ['intervene', 'requestIntervention', 'respondIntervention'],
  };

  for (const [permission, actions] of Object.entries(groups)) {
    const permissions = {
      ...denyAllAgentWorkbenchPermissions,
      [permission]: true,
    };
    for (const action of actions) {
      assert.equal(
        canPerformAgentWorkbenchAction(permissions, action),
        true,
        `${permission} should grant ${action}`,
      );
    }
  }
});

test('unknown, missing, and malformed permission paths remain denied', () => {
  const allowAll = Object.fromEntries(fields.map((field) => [field, true]));
  assert.equal(canPerformAgentWorkbenchAction(allowAll, 'futureMutation'), false);
  assert.equal(canPerformAgentWorkbenchAction(undefined, 'create'), false);
  assert.equal(
    canPerformAgentWorkbenchAction(
      normalizeAgentWorkbenchPermissions({create: 'true'}),
      'create',
    ),
    false,
  );
});
