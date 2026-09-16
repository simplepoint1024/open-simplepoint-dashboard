import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canPerformWorkflowWorkbenchAction,
  denyAllWorkflowWorkbenchPermissions,
  normalizeWorkflowWorkbenchPermissions,
} from './permissions.ts';

const fields = [
  'view',
  'create',
  'edit',
  'delete',
  'manageVersions',
  'publish',
  'execute',
  'intervene',
];

test('deny-all covers every Workflow workbench permission field', () => {
  assert.deepEqual(Object.keys(denyAllWorkflowWorkbenchPermissions), fields);
  assert.ok(fields.every((field) =>
    denyAllWorkflowWorkbenchPermissions[field] === false));
});

test('normalization only grants an exact boolean true', () => {
  const normalized = normalizeWorkflowWorkbenchPermissions({
    view: true,
    create: 1,
    edit: 'true',
    delete: new Boolean(true),
    manageVersions: false,
    publish: null,
    execute: undefined,
    intervene: true,
    ignoredFuturePermission: true,
  });

  assert.deepEqual(normalized, {
    ...denyAllWorkflowWorkbenchPermissions,
    view: true,
    intervene: true,
  });
  assert.deepEqual(
    normalizeWorkflowWorkbenchPermissions(undefined),
    denyAllWorkflowWorkbenchPermissions,
  );
  assert.deepEqual(
    normalizeWorkflowWorkbenchPermissions(null),
    denyAllWorkflowWorkbenchPermissions,
  );
  assert.deepEqual(
    normalizeWorkflowWorkbenchPermissions([true]),
    denyAllWorkflowWorkbenchPermissions,
  );
  assert.deepEqual(normalizeWorkflowWorkbenchPermissions({create: true}), {
    ...denyAllWorkflowWorkbenchPermissions,
    create: true,
  });
});

test('every raw permission maps to its direct workbench action', () => {
  for (const field of fields) {
    const permissions = {
      ...denyAllWorkflowWorkbenchPermissions,
      [field]: true,
    };
    assert.equal(
      canPerformWorkflowWorkbenchAction(permissions, field),
      true,
      field,
    );
  }
});

test('related Workflow actions share the intended resource permission', () => {
  const groups = {
    manageVersions: ['createVersion'],
    publish: ['publish', 'deprecate'],
    execute: ['execute'],
    intervene: [
      'intervene',
      'pause',
      'resume',
      'cancel',
      'respondHumanTask',
    ],
  };

  for (const [permission, actions] of Object.entries(groups)) {
    const permissions = {
      ...denyAllWorkflowWorkbenchPermissions,
      [permission]: true,
    };
    for (const action of actions) {
      assert.equal(
        canPerformWorkflowWorkbenchAction(permissions, action),
        true,
        `${permission} should grant ${action}`,
      );
    }
  }
});

test('cancel and human task response require intervene, not execute', () => {
  const executeOnly = {
    ...denyAllWorkflowWorkbenchPermissions,
    execute: true,
  };
  assert.equal(canPerformWorkflowWorkbenchAction(executeOnly, 'execute'), true);
  assert.equal(canPerformWorkflowWorkbenchAction(executeOnly, 'cancel'), false);
  assert.equal(
    canPerformWorkflowWorkbenchAction(executeOnly, 'respondHumanTask'),
    false,
  );
});

test('unknown, missing, and malformed permission paths remain denied', () => {
  const allowAll = Object.fromEntries(fields.map((field) => [field, true]));
  assert.equal(
    canPerformWorkflowWorkbenchAction(allowAll, 'futureMutation'),
    false,
  );
  assert.equal(
    canPerformWorkflowWorkbenchAction(undefined, 'create'),
    false,
  );
  assert.equal(
    canPerformWorkflowWorkbenchAction(
      normalizeWorkflowWorkbenchPermissions({create: 'true'}),
      'create',
    ),
    false,
  );
});
