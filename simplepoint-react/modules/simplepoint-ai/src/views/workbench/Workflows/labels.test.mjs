import assert from 'node:assert/strict';
import test from 'node:test';
import {workflowDependencyTypeLabel} from './labels.ts';

const keyOf = (key) => key;

test('workflow dependency types resolve through local translation keys', () => {
  assert.deepEqual(
    ['AGENT', 'SKILL', 'COMPENSATION_SKILL'].map(
      (type) => workflowDependencyTypeLabel(keyOf, type),
    ),
    [
      'ai.workflows.dependency.agent',
      'ai.workflows.dependency.skill',
      'ai.workflows.dependency.compensationSkill',
    ],
  );
});

test('unknown workflow dependency types never become user-facing text', () => {
  assert.equal(
    workflowDependencyTypeLabel(keyOf, 'PRIVATE_TYPE'),
    'ai.workflows.dependency.unknown',
  );
});
