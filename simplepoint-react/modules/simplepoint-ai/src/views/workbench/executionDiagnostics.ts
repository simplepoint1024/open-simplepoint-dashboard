export type ExecutionDiagnosticSurface =
  | 'agentExecution'
  | 'agentTrace'
  | 'workflowExecution'
  | 'workflowNode';

export type ExecutionDiagnosticCategory =
  | 'agentBudget'
  | 'agentCancelled'
  | 'agentExecution'
  | 'agentRetryExhausted'
  | 'agentHumanTimeout'
  | 'agentModel'
  | 'agentRuntime'
  | 'agentSkillArguments'
  | 'agentSkill'
  | 'workflowBudget'
  | 'workflowExecution'
  | 'workflowHumanTimeout'
  | 'workflowNodeCancelled'
  | 'workflowNode'
  | 'workflowRetryExhausted'
  | 'workflowWaitState'
  | 'workflowAgentChild'
  | 'workflowSkillChild'
  | 'workflowCompensationChild'
  | 'agentExecutionUnknown'
  | 'agentTraceUnknown'
  | 'workflowExecutionUnknown'
  | 'workflowNodeUnknown';

export type ExecutionDiagnosticSource = {
  errorCode?: unknown;
  errorMessage?: unknown;
};

export type ExecutionDiagnostic = {
  category: ExecutionDiagnosticCategory;
  code?: string;
};

// Keep this aligned with the runtime persistence boundary. Only a bounded,
// machine-readable code may be copied to the UI; server prose is never read.
const SAFE_ERROR_CODE = /^[A-Z][A-Z0-9_]{2,63}$/;
const SAFE_CHILD_CODES = new Set(['FAILED', 'REJECTED', 'CANCELLED']);
const DISPLAYABLE_OUTPUT_STATUSES = new Set([
  'PENDING',
  'RUNNING',
  'WAITING',
  'SUCCEEDED',
  'SKIPPED',
  'COMPENSATING',
  'COMPENSATED',
  // A compensation failure keeps the safe, successful forward-node output so
  // operators can repair the failed compensation without rerunning the node.
  'COMPENSATION_FAILED',
]);

const agentCategories: Readonly<Record<string, ExecutionDiagnosticCategory>> = {
  AGENT_BUDGET_COST_EXCEEDED: 'agentBudget',
  AGENT_BUDGET_INPUT_TOKENS_EXCEEDED: 'agentBudget',
  AGENT_BUDGET_LOOP_DEPTH_EXCEEDED: 'agentBudget',
  AGENT_BUDGET_OUTPUT_TOKENS_EXCEEDED: 'agentBudget',
  AGENT_BUDGET_STEPS_EXCEEDED: 'agentBudget',
  AGENT_EXECUTION_CANCELLED: 'agentCancelled',
  AGENT_EXECUTION_FAILED: 'agentExecution',
  AGENT_EXECUTION_RETRY_EXHAUSTED: 'agentRetryExhausted',
  AGENT_HUMAN_INTERVENTION_TIMEOUT: 'agentHumanTimeout',
  AGENT_MODEL_FALLBACK_EXHAUSTED: 'agentModel',
  AGENT_RUNTIME_FAILED: 'agentRuntime',
  AGENT_RUNTIME_LEASE_EXPIRED: 'agentRuntime',
  AGENT_RUNTIME_TRANSITION_LIMIT: 'agentRuntime',
  AGENT_SKILL_ARGUMENTS_INVALID: 'agentSkillArguments',
  AGENT_SKILL_EXECUTION_FAILED: 'agentSkill',
  MODEL_INVOCATION_FAILED: 'agentModel',
};

const workflowCategories: Readonly<Record<string, ExecutionDiagnosticCategory>> = {
  WORKFLOW_BUDGET_NODE_EXECUTIONS_EXCEEDED: 'workflowBudget',
  WORKFLOW_BUDGET_TIME_EXCEEDED: 'workflowBudget',
  WORKFLOW_COMPENSATION_FAILED: 'workflowCompensationChild',
  WORKFLOW_EXECUTION_FAILED: 'workflowExecution',
  WORKFLOW_HUMAN_TASK_TIMEOUT: 'workflowHumanTimeout',
  WORKFLOW_NODE_CANCELLED: 'workflowNodeCancelled',
  WORKFLOW_NODE_EXECUTION_FAILED: 'workflowNode',
  WORKFLOW_NODE_FAILED: 'workflowNode',
  WORKFLOW_RETRY_EXHAUSTED: 'workflowRetryExhausted',
  WORKFLOW_WAIT_STATE_INVALID: 'workflowWaitState',
};

const workflowChildCategories = [
  ['WORKFLOW_AGENT_CHILD_', 'workflowAgentChild'],
  ['WORKFLOW_SKILL_CHILD_', 'workflowSkillChild'],
  ['WORKFLOW_COMPENSATION_CHILD_', 'workflowCompensationChild'],
] as const satisfies ReadonlyArray<readonly [string, ExecutionDiagnosticCategory]>;

const unknownCategory = (
  surface: ExecutionDiagnosticSurface,
): ExecutionDiagnosticCategory => {
  switch (surface) {
    case 'agentExecution':
      return 'agentExecutionUnknown';
    case 'agentTrace':
      return 'agentTraceUnknown';
    case 'workflowExecution':
      return 'workflowExecutionUnknown';
    case 'workflowNode':
      return 'workflowNodeUnknown';
  }
};

export const isSafeExecutionErrorCode = (code: unknown): code is string => (
  typeof code === 'string' && SAFE_ERROR_CODE.test(code)
);

export const canDisplayExecutionOutput = (status: unknown): boolean => (
  typeof status === 'string' && DISPLAYABLE_OUTPUT_STATUSES.has(status)
);

const hasPersistedDiagnostic = (
  source?: ExecutionDiagnosticSource,
): boolean => [source?.errorCode, source?.errorMessage].some((value) => (
  typeof value === 'string' && value.trim().length > 0
));

export const shouldDisplayExecutionDiagnostic = (
  surface: ExecutionDiagnosticSurface,
  status: unknown,
  source?: ExecutionDiagnosticSource,
): boolean => {
  if (hasPersistedDiagnostic(source)) return true;
  switch (surface) {
    case 'agentExecution':
    case 'workflowExecution':
      return status === 'FAILED';
    case 'agentTrace':
      return status === 'FAILED' || status === 'CANCELLED';
    case 'workflowNode':
      return status === 'FAILED'
        || status === 'CANCELLED'
        || status === 'COMPENSATION_FAILED';
  }
};

const workflowChildCategory = (
  code: string,
): ExecutionDiagnosticCategory | undefined => {
  for (const [prefix, category] of workflowChildCategories) {
    if (!code.startsWith(prefix)) continue;
    const childCode = code.slice(prefix.length);
    return SAFE_CHILD_CODES.has(childCode) ? category : undefined;
  }
  return undefined;
};

export const resolveExecutionDiagnostic = (
  surface: ExecutionDiagnosticSurface,
  source?: ExecutionDiagnosticSource,
): ExecutionDiagnostic => {
  const candidate = source?.errorCode;
  if (!isSafeExecutionErrorCode(candidate)) {
    return {category: unknownCategory(surface)};
  }

  if (surface === 'workflowExecution' || surface === 'workflowNode') {
    const childCategory = workflowChildCategory(candidate);
    const fixedCategory = workflowCategories[candidate];
    if (!fixedCategory && !childCategory) {
      return {category: unknownCategory(surface)};
    }
    return {
      category: fixedCategory ?? childCategory!,
      code: candidate,
    };
  }

  const category = agentCategories[candidate];
  if (!category) {
    return {category: unknownCategory(surface)};
  }
  return {
    category,
    code: candidate,
  };
};
