import assert from 'node:assert/strict';
import test from 'node:test';
import {
  canDisplayExecutionOutput,
  isSafeExecutionErrorCode,
  resolveExecutionDiagnostic,
  shouldDisplayExecutionDiagnostic,
} from './executionDiagnostics.ts';

const agentCodes = [
  'AGENT_BUDGET_COST_EXCEEDED',
  'AGENT_BUDGET_INPUT_TOKENS_EXCEEDED',
  'AGENT_BUDGET_LOOP_DEPTH_EXCEEDED',
  'AGENT_BUDGET_OUTPUT_TOKENS_EXCEEDED',
  'AGENT_BUDGET_STEPS_EXCEEDED',
  'AGENT_EXECUTION_CANCELLED',
  'AGENT_EXECUTION_FAILED',
  'AGENT_EXECUTION_RETRY_EXHAUSTED',
  'AGENT_HUMAN_INTERVENTION_TIMEOUT',
  'AGENT_MODEL_FALLBACK_EXHAUSTED',
  'AGENT_RUNTIME_FAILED',
  'AGENT_RUNTIME_LEASE_EXPIRED',
  'AGENT_RUNTIME_TRANSITION_LIMIT',
  'AGENT_SKILL_ARGUMENTS_INVALID',
  'AGENT_SKILL_EXECUTION_FAILED',
  'MODEL_INVOCATION_FAILED',
];

const workflowCodes = [
  'WORKFLOW_BUDGET_NODE_EXECUTIONS_EXCEEDED',
  'WORKFLOW_BUDGET_TIME_EXCEEDED',
  'WORKFLOW_COMPENSATION_FAILED',
  'WORKFLOW_EXECUTION_FAILED',
  'WORKFLOW_HUMAN_TASK_TIMEOUT',
  'WORKFLOW_NODE_CANCELLED',
  'WORKFLOW_NODE_EXECUTION_FAILED',
  'WORKFLOW_NODE_FAILED',
  'WORKFLOW_RETRY_EXHAUSTED',
  'WORKFLOW_WAIT_STATE_INVALID',
];

test('all known Agent execution and trace codes resolve locally', () => {
  for (const surface of ['agentExecution', 'agentTrace']) {
    for (const errorCode of agentCodes) {
      const diagnostic = resolveExecutionDiagnostic(surface, {errorCode});
      assert.equal(diagnostic.code, errorCode);
      assert.equal(diagnostic.category.endsWith('Unknown'), false);
    }
  }
  assert.deepEqual(
    resolveExecutionDiagnostic('agentExecution', {
      errorCode: 'AGENT_BUDGET_STEPS_EXCEEDED',
    }),
    {category: 'agentBudget', code: 'AGENT_BUDGET_STEPS_EXCEEDED'},
  );
  assert.deepEqual(
    resolveExecutionDiagnostic('agentTrace', {
      errorCode: 'MODEL_INVOCATION_FAILED',
    }),
    {category: 'agentModel', code: 'MODEL_INVOCATION_FAILED'},
  );
  assert.deepEqual(
    resolveExecutionDiagnostic('agentExecution', {
      errorCode: 'AGENT_EXECUTION_FAILED',
    }),
    {category: 'agentExecution', code: 'AGENT_EXECUTION_FAILED'},
  );
});

test('all known Workflow execution and node codes resolve locally', () => {
  for (const surface of ['workflowExecution', 'workflowNode']) {
    for (const errorCode of workflowCodes) {
      const diagnostic = resolveExecutionDiagnostic(surface, {errorCode});
      assert.equal(diagnostic.code, errorCode);
      assert.equal(diagnostic.category.endsWith('Unknown'), false);
    }
  }
  assert.deepEqual(
    resolveExecutionDiagnostic('workflowExecution', {
      errorCode: 'WORKFLOW_BUDGET_TIME_EXCEEDED',
    }),
    {category: 'workflowBudget', code: 'WORKFLOW_BUDGET_TIME_EXCEEDED'},
  );
  assert.deepEqual(
    resolveExecutionDiagnostic('workflowNode', {
      errorCode: 'WORKFLOW_HUMAN_TASK_TIMEOUT',
    }),
    {category: 'workflowHumanTimeout', code: 'WORKFLOW_HUMAN_TASK_TIMEOUT'},
  );
});

test('dynamic Workflow child codes retain only bounded machine-readable codes', () => {
  assert.equal(
    resolveExecutionDiagnostic('workflowNode', {
      errorCode: 'WORKFLOW_AGENT_CHILD_FAILED',
    }).category,
    'workflowAgentChild',
  );
  assert.equal(
    resolveExecutionDiagnostic('workflowNode', {
      errorCode: 'WORKFLOW_SKILL_CHILD_CANCELLED',
    }).category,
    'workflowSkillChild',
  );
  assert.equal(
    resolveExecutionDiagnostic('workflowExecution', {
      errorCode: 'WORKFLOW_COMPENSATION_CHILD_FAILED',
    }).category,
    'workflowCompensationChild',
  );
});

test('unknown but valid codes use a fallback without exposing the code', () => {
  assert.deepEqual(
    resolveExecutionDiagnostic('agentTrace', {
      errorCode: 'FUTURE_AGENT_RUNTIME_CODE',
    }),
    {category: 'agentTraceUnknown'},
  );
  assert.deepEqual(
    resolveExecutionDiagnostic('workflowNode', {
      errorCode: 'FUTURE_WORKFLOW_NODE_CODE',
    }),
    {category: 'workflowNodeUnknown'},
  );
});

test('missing and malicious codes never become copyable diagnostics', () => {
  for (const errorCode of [
    undefined,
    '',
    ' code_with_spaces ',
    '<script>alert(1)</script>',
    'WORKFLOW_AGENT_CHILD_',
    'WORKFLOW_AGENT_CHILD__FAILED',
    'WORKFLOW_AGENT_CHILD_PROVIDER_SECRET',
    'A'.repeat(66),
  ]) {
    const diagnostic = resolveExecutionDiagnostic('workflowNode', {errorCode});
    assert.equal(diagnostic.code, undefined);
    assert.equal(diagnostic.category, 'workflowNodeUnknown');
  }
  assert.equal(isSafeExecutionErrorCode('AGENT_RUNTIME_FAILED'), true);
});

test('persisted outputs are hidden for failure, cancellation, and unknown states', () => {
  assert.equal(canDisplayExecutionOutput('RUNNING'), true);
  assert.equal(canDisplayExecutionOutput('SUCCEEDED'), true);
  assert.equal(canDisplayExecutionOutput('COMPENSATED'), true);
  assert.equal(canDisplayExecutionOutput('COMPENSATION_FAILED'), true);
  assert.equal(canDisplayExecutionOutput('FAILED'), false);
  assert.equal(canDisplayExecutionOutput('CANCELLED'), false);
  assert.equal(canDisplayExecutionOutput('PROVIDER_FAILURE'), false);
  assert.equal(canDisplayExecutionOutput(undefined), false);
});

test('ordinary cancellation and approval rejection do not masquerade as failures', () => {
  assert.equal(
    shouldDisplayExecutionDiagnostic('agentExecution', 'CANCELLED', {}),
    false,
  );
  assert.equal(
    shouldDisplayExecutionDiagnostic('agentExecution', 'REJECTED', {}),
    false,
  );
  assert.equal(
    shouldDisplayExecutionDiagnostic('workflowExecution', 'CANCELLED', {}),
    false,
  );
  assert.equal(
    shouldDisplayExecutionDiagnostic('agentExecution', 'FAILED', {}),
    true,
  );
  assert.equal(
    shouldDisplayExecutionDiagnostic('workflowExecution', 'CANCELLED', {
      errorCode: 'WORKFLOW_EXECUTION_FAILED',
    }),
    true,
  );
  assert.equal(
    shouldDisplayExecutionDiagnostic('agentTrace', 'CANCELLED', {}),
    true,
  );
  assert.equal(
    shouldDisplayExecutionDiagnostic('workflowNode', 'COMPENSATION_FAILED', {}),
    true,
  );
});

test('raw persisted error messages are ignored and never leak', () => {
  const privateMessage = 'upstream response with token=super-secret';
  const diagnostic = resolveExecutionDiagnostic('agentExecution', {
    errorCode: 'AGENT_RUNTIME_FAILED',
    errorMessage: privateMessage,
  });

  assert.equal(diagnostic.category, 'agentRuntime');
  assert.equal(Object.values(diagnostic).includes(privateMessage), false);
  assert.equal('errorMessage' in diagnostic, false);
});
