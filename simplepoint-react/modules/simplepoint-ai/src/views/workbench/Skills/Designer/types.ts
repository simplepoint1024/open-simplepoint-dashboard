export type DesignerNodeType =
  | 'INPUT'
  | 'OUTPUT'
  | 'TOOL'
  | 'PROMPT'
  | 'RESOURCE'
  | 'CONDITION'
  | 'PARALLEL';

export type DesignerPosition = {
  x: number;
  y: number;
};

export type DesignerNode = {
  id: string;
  type: DesignerNodeType;
  parentNodeId?: string | null;
  branchId?: string | null;
  order: number;
  configuration: Record<string, unknown>;
  position: DesignerPosition;
};

export type DesignerPort = {
  nodeId: string;
  id: string;
  direction: 'INPUT' | 'OUTPUT';
  kind: 'CONTROL' | 'DATA';
  label?: string | null;
  required: boolean;
  multiple: boolean;
};

export type DesignerEdge = {
  id: string;
  sourceNodeId: string;
  sourceHandle: string;
  targetNodeId: string;
  targetHandle: string;
};

export type DesignerViewport = {
  x: number;
  y: number;
  zoom: number;
};

export type SkillDesignerNodeMock = {
  nodeId: string;
  mode: 'SUCCESS' | 'ERROR';
  output?: unknown;
  errorCode?: string | null;
  errorMessage?: string | null;
};

export type SkillDesignerTestAssertion = {
  id: string;
  sourceNodeId: string;
  fieldPath: string;
  operator: 'EQUALS' | 'NOT_EQUALS' | 'EXISTS' | 'NOT_EXISTS' | 'CONTAINS' | 'MATCHES';
  expected?: unknown;
};

export type SkillDesignerTestCase = {
  id: string;
  name: string;
  enabled: boolean;
  input: Record<string, unknown>;
  mocks: SkillDesignerNodeMock[];
  assertions: SkillDesignerTestAssertion[];
};

export type SkillDesignerDocument = {
  schemaVersion: 'simplepoint.io/designer/v1alpha1';
  metadata: Record<string, unknown>;
  inputSchema: Record<string, unknown>;
  outputSchema: Record<string, unknown>;
  tools: Array<Record<string, unknown>>;
  prompts: Array<Record<string, unknown>>;
  resources: Array<Record<string, unknown>>;
  nodes: DesignerNode[];
  ports: DesignerPort[];
  edges: DesignerEdge[];
  workflowOutput: unknown;
  budgets: Record<string, unknown>;
  approvals: Record<string, unknown>;
  tests: SkillDesignerTestCase[];
  viewport: DesignerViewport;
};

export type SkillDesignerDiagnostic = {
  severity: 'ERROR' | 'WARNING' | 'INFO';
  code: string;
  message: string;
  nodeId?: string | null;
  fieldPath?: string | null;
  jsonPointer?: string | null;
  details?: Record<string, unknown> | null;
};

export type SkillDesignerCompilation = {
  valid: boolean;
  manifest?: Record<string, unknown> | null;
  contentHash?: string | null;
  diagnostics: SkillDesignerDiagnostic[];
};

export type SkillDraftView = {
  id: string;
  skillId: string;
  scopeType: 'SYSTEM' | 'TENANT';
  tenantId?: string | null;
  revision: number;
  document: SkillDesignerDocument;
  compilation: SkillDesignerCompilation;
  createdAt: string;
  updatedAt: string;
};

export type SkillDraftRevision = {
  id: string;
  draftId: string;
  revision: number;
  source?: 'SAVE' | 'RESTORE' | 'VERSION_COPY';
  validationStatus: 'VALID' | 'INVALID';
  contentHash?: string | null;
  createdBy?: string | null;
  createdAt: string;
};

export type SkillDebugExecutionStep = {
  id: string;
  stepId: string;
  stepType: string;
  stepOrder: number;
  capabilityAlias?: string;
  capabilityName?: string;
  bindingId?: string;
  mcpServerId?: string;
  capabilitySnapshotId?: string;
  capabilitySchemaHash?: string;
  capabilityTemplate?: boolean;
  capabilityTokenIdHash?: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
  attemptCount: number;
  inputTemplate?: unknown;
  input?: Record<string, unknown>;
  output?: unknown;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
};

export type SkillTestAssertionResult = {
  id: string;
  sourceNodeId: string;
  fieldPath: string;
  operator: SkillDesignerTestAssertion['operator'];
  passed: boolean;
  expected?: unknown;
  actual?: unknown;
  message: string;
};

export type SkillDebugExecution = {
  id: string;
  skillId: string;
  sourceType: 'DRAFT';
  draftId: string;
  draftRevision: number;
  draftContentHash: string;
  debugMode: 'LIVE' | 'MOCK';
  testCaseId?: string | null;
  mockConfigHash?: string | null;
  assertionsPassed?: boolean | null;
  assertionResults?: SkillTestAssertionResult[] | null;
  testRunId?: string | null;
  testRunOrder?: number | null;
  status:
    | 'WAITING_APPROVAL'
    | 'PENDING'
    | 'RUNNING'
    | 'PAUSED'
    | 'SUCCEEDED'
    | 'FAILED'
    | 'REJECTED'
    | 'CANCELLED';
  currentStepId?: string;
  attemptCount: number;
  input?: Record<string, unknown>;
  output?: unknown;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
  createdAt?: string;
  approvalRequired?: boolean;
  approvalInstructions?: string;
  pauseRequested?: boolean;
  cancelRequested?: boolean;
  cancelRequestedAt?: string | null;
  cancelRequestedBy?: string | null;
  cancelReason?: string | null;
  breakpointStepIds?: string[];
  steps?: SkillDebugExecutionStep[];
};

export type SkillMockTestRunCase = {
  executionId: string;
  testCaseId: string;
  testCaseName: string;
  order: number;
  status: SkillDebugExecution['status'];
  assertionsPassed?: boolean | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
};

export type SkillMockTestRun = {
  id: string;
  skillId: string;
  draftId: string;
  draftRevision: number;
  draftContentHash: string;
  status: 'RUNNING' | 'PASSED' | 'FAILED';
  totalCount: number;
  completedCount: number;
  passedCount: number;
  failedCount: number;
  createdAt?: string | null;
  completedAt?: string | null;
  cases: SkillMockTestRunCase[];
};

export type SkillDebugExecutionEvent = {
  id: string;
  sequence: number;
  type:
    | 'EXECUTION_CREATED'
    | 'APPROVAL_REQUIRED'
    | 'APPROVAL_GRANTED'
    | 'APPROVAL_REJECTED'
    | 'EXECUTION_STARTED'
    | 'PAUSE_REQUESTED'
    | 'EXECUTION_PAUSED'
    | 'EXECUTION_RESUMED'
    | 'CANCEL_REQUESTED'
    | 'BREAKPOINTS_UPDATED'
    | 'STEP_STARTED'
    | 'STEP_SUCCEEDED'
    | 'STEP_FAILED'
    | 'STEP_SKIPPED'
    | 'EXECUTION_SUCCEEDED'
    | 'EXECUTION_FAILED'
    | 'EXECUTION_CANCELLED';
  executionStatus: SkillDebugExecution['status'];
  stepId?: string | null;
  actorId?: string | null;
  occurredAt: string;
  payload?: Record<string, unknown>;
};

export type SkillDebugExecutionEventFeed = {
  afterSequence: number;
  nextSequence: number;
  hasMore: boolean;
  executionStatus: SkillDebugExecution['status'];
  events: SkillDebugExecutionEvent[];
};

export type SkillDebugNodeStatus = SkillDebugExecutionStep['status'] | 'WAITING_APPROVAL' | 'PAUSED';

export type SkillVersionDesignerView = {
  skillId: string;
  versionId: string;
  version: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  compatible: boolean;
  compatibilityMessage?: string | null;
  document?: SkillDesignerDocument | null;
  manifest: Record<string, unknown>;
};

export type SkillManagedRegistryStatus = {
  configured: boolean;
  registry?: string | null;
  repositoryPrefix?: string | null;
  secureTransport: boolean;
  authenticationConfigured: boolean;
  signatureRequired: boolean;
  connected?: boolean | null;
  checkedAt?: string | null;
  message: string;
};

export type SkillPublishTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';

export type SkillPublishTaskStage =
  | 'QUEUED'
  | 'GENERATING'
  | 'PUSHING'
  | 'VERIFYING'
  | 'CREATING_VERSION'
  | 'ACTIVATING'
  | 'COMPLETED';

export type SkillPublishTask = {
  id: string;
  skillId: string;
  draftId: string;
  draftRevision: number;
  draftContentHash: string;
  scopeType: 'SYSTEM' | 'TENANT';
  tenantId?: string | null;
  version: string;
  activate: boolean;
  status: SkillPublishTaskStatus;
  stage: SkillPublishTaskStage;
  attemptCount: number;
  nextAttemptAt: string;
  artifactReference?: string | null;
  artifactDigest?: string | null;
  artifactConfigDigest?: string | null;
  artifactContentDigest?: string | null;
  contentHash?: string | null;
  skillVersionId?: string | null;
  errorCode?: string | null;
  errorMessage?: string | null;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type SkillVersionSummary = {
  id: string;
  version: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  contentHash: string;
  artifactReference?: string | null;
  artifactDigest?: string | null;
  createdAt?: string | null;
};

export type SkillSummary = {
  id: string;
  code: string;
  name: string;
  description?: string;
};

export type ToolBinding = {
  alias: string;
  serverId: string;
  snapshotId: string;
  name: string;
};

export type PromptBinding = ToolBinding;

export type ResourceBinding = {
  alias: string;
  serverId: string;
  snapshotId: string;
  uri: string;
  uriTemplate: string;
};

export type McpServerSummary = {
  id: string;
  code?: string;
  name?: string;
  status?: string;
  enabled?: boolean;
};

export type McpToolDescriptor = {
  name: string;
  title?: string;
  description?: string;
  inputSchema?: Record<string, unknown>;
  outputSchema?: Record<string, unknown>;
};

export type McpPromptDescriptor = {
  name: string;
  title?: string;
  description?: string;
  arguments?: Array<{
    name: string;
    title?: string;
    description?: string;
    required?: boolean;
  }>;
};

export type McpResourceDescriptor = {
  uri: string;
  name: string;
  title?: string;
  description?: string;
  mimeType?: string;
};

export type McpResourceTemplateDescriptor = {
  uriTemplate: string;
  name: string;
  title?: string;
  description?: string;
  mimeType?: string;
};

export type McpCapabilitySnapshotSummary = {
  id: string;
  schemaHash: string;
  discoveredAt: string;
  active: boolean;
  toolCount: number;
};

export type McpCapabilitySnapshotDetails = {
  id: string;
  serverId: string;
  protocolVersion?: string;
  remoteServerName?: string;
  remoteServerVersion?: string;
  schemaHash: string;
  discoveredAt: string;
  active: boolean;
  capabilities?: Record<string, unknown>;
  tools: McpToolDescriptor[];
  prompts: McpPromptDescriptor[];
  resources: McpResourceDescriptor[];
  resourceTemplates: McpResourceTemplateDescriptor[];
};

export type McpServerCapabilities = {
  snapshot?: McpCapabilitySnapshotSummary;
  tools: McpToolDescriptor[];
  prompts: McpPromptDescriptor[];
  resources: McpResourceDescriptor[];
  resourceTemplates: McpResourceTemplateDescriptor[];
};

export const INPUT_NODE_ID = '__input';
export const OUTPUT_NODE_ID = '__output';
export const DESIGNER_DRAG_TYPE = 'application/simplepoint-skill-node';
