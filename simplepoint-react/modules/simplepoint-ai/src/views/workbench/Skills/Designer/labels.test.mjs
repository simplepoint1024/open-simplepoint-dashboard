import assert from 'node:assert/strict';
import test from 'node:test';
import {
  skillDebugExecutionStatusLabel,
  skillDebugNodeStatusLabel,
  skillDraftRevisionSourceLabel,
  skillDesignerDiagnosticLabel,
  skillDesignerDiagnosticLocationLabel,
  skillLifecycleStatusLabel,
  skillNodeTypeLabel,
  skillPublishStageLabel,
  skillPublishTaskStatusLabel,
  skillStepTypeLabel,
} from './labels.ts';

const translate = (key) => key;

test('every designer node and debug status resolves through a translation key', () => {
  const nodeTypes = [
    'INPUT', 'OUTPUT', 'TOOL', 'PROMPT', 'RESOURCE', 'CONDITION', 'PARALLEL',
  ];
  const nodeStatuses = [
    'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED',
    'WAITING_APPROVAL', 'PAUSED',
  ];
  const executionStatuses = [
    'WAITING_APPROVAL', 'PENDING', 'RUNNING', 'PAUSED',
    'SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED',
  ];

  assert.equal(new Set(nodeTypes.map((value) => skillNodeTypeLabel(
    translate,
    value,
  ))).size, nodeTypes.length);
  assert.equal(new Set(nodeStatuses.map((value) => skillDebugNodeStatusLabel(
    translate,
    value,
  ))).size, nodeStatuses.length);
  assert.equal(new Set(executionStatuses.map((value) => (
    skillDebugExecutionStatusLabel(translate, value)
  ))).size, executionStatuses.length);
});

test('unknown step types use a safe local label', () => {
  const rawValue = 'UPSTREAM_INTERNAL_NODE_TYPE';
  const label = skillStepTypeLabel(translate, rawValue);
  assert.equal(label, 'ai.skills.designer.node.unknown');
  assert.equal(label.includes(rawValue), false);
  assert.equal(
    skillStepTypeLabel(translate, 'tool'),
    'ai.skills.designer.node.tool',
  );
});

test('skill lifecycle values and unknown states are localized safely', () => {
  const statuses = ['DRAFT', 'ACTIVE', 'DISABLED', 'PUBLISHED', 'DEPRECATED'];
  assert.equal(new Set(statuses.map((value) => skillLifecycleStatusLabel(
    translate,
    value,
  ))).size, statuses.length);
  assert.equal(
    skillLifecycleStatusLabel(translate, 'PRIVATE_SERVER_STATE'),
    'ai.skills.lifecycle.unknown',
  );
});

test('publish statuses and stages are exhaustively localized', () => {
  const statuses = ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'];
  const stages = [
    'QUEUED', 'GENERATING', 'PUSHING', 'VERIFYING',
    'CREATING_VERSION', 'ACTIVATING', 'COMPLETED',
  ];
  assert.equal(new Set(statuses.map((value) => skillPublishTaskStatusLabel(
    translate,
    value,
  ))).size, statuses.length);
  assert.equal(new Set(stages.map((value) => skillPublishStageLabel(
    translate,
    value,
  ))).size, stages.length);
});

test('draft revision sources use finite local labels', () => {
  assert.deepEqual(
    ['SAVE', 'RESTORE', 'VERSION_COPY'].map(
      (source) => skillDraftRevisionSourceLabel(translate, source),
    ),
    [
      'ai.skills.designer.history.source.SAVE',
      'ai.skills.designer.history.source.RESTORE',
      'ai.skills.designer.history.source.VERSION_COPY',
    ],
  );
  assert.equal(
    skillDraftRevisionSourceLabel(translate, 'PRIVATE_SOURCE'),
    'ai.skills.designer.history.source.UNKNOWN',
  );
});

test('designer diagnostics use safe local messages and locations', () => {
  assert.equal(
    skillDesignerDiagnosticLabel(translate, 'SKILL_DESIGNER_WORKFLOW_EMPTY'),
    'ai.skills.designer.diagnostics.workflowEmpty',
  );
  assert.equal(
    skillDesignerDiagnosticLabel(translate, 'PRIVATE_SERVER_DIAGNOSTIC'),
    'ai.skills.designer.diagnostics.configurationInvalid',
  );
  assert.equal(
    skillDesignerDiagnosticLocationLabel(translate, 'nodes'),
    'ai.skills.designer.diagnostics.location.nodes',
  );
  assert.equal(
    skillDesignerDiagnosticLocationLabel(translate, 'private.internal.path'),
    'ai.skills.designer.diagnostics.location.configuration',
  );
});
