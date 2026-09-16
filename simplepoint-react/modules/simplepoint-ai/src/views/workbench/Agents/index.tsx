import api from '@/api';
import DataTable from '@simplepoint/components/DataTable';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {del, get, post, put} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import SchemaExecutionForm, {
  inputSchemaFromManifest,
  type ExecutionInput,
} from '../components/SchemaExecutionForm';
import JsonSchemaObjectEditor from '../components/JsonSchemaObjectEditor';
import DependencyOptionSelect, {
  useDependencyOptionDirectory,
} from '../components/DependencyOptionSelect';
import type {
  DependencySelectionIssue,
} from '../components/dependencyOptions';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Switch,
  Tag,
  Tabs,
  Timeline,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useSearchParams} from 'react-router';
import {
  canDisplayExecutionOutput,
  resolveExecutionDiagnostic,
  shouldDisplayExecutionDiagnostic,
  type ExecutionDiagnosticCategory,
  type ExecutionDiagnosticSource,
  type ExecutionDiagnosticSurface,
} from '../executionDiagnostics';
import {
  canPerformAgentWorkbenchAction,
  denyAllAgentWorkbenchPermissions,
  normalizeAgentWorkbenchPermissions,
  type AgentWorkbenchAction,
  type AgentWorkbenchPermissions,
} from './permissions';

const {Paragraph, Text} = Typography;
const {TextArea} = Input;

type Translate = (key: string, fallback?: string) => string;

const agentDiagnosticLabel = (
  t: Translate,
  category: ExecutionDiagnosticCategory,
) => {
  switch (category) {
    case 'agentBudget':
      return t(
        'ai.agents.execution.diagnostic.budget',
        '执行已达到配置的资源预算，请检查预算和输入规模后重试',
      );
    case 'agentCancelled':
      return t(
        'ai.agents.execution.diagnostic.cancelled',
        '执行已被取消，请确认取消来源后重新发起',
      );
    case 'agentExecution':
      return t(
        'ai.agents.execution.diagnostic.executionFailed',
        'Agent 执行未能完成，请检查配置和运行状态后重试',
      );
    case 'agentRetryExhausted':
      return t(
        'ai.agents.execution.diagnostic.retryExhausted',
        '执行重试次数已用尽，请检查运行配置后重试',
      );
    case 'agentHumanTimeout':
      return t(
        'ai.agents.execution.diagnostic.humanTimeout',
        '人工介入等待已超时，请重新发起执行并及时处理',
      );
    case 'agentModel':
      return t(
        'ai.agents.execution.diagnostic.model',
        '模型调用未完成，请检查模型可用性和回退配置后重试',
      );
    case 'agentSkillArguments':
      return t(
        'ai.agents.execution.diagnostic.skillArguments',
        'Skill 输入不符合参数约束，请检查 Agent 输入和 Skill 绑定',
      );
    case 'agentSkill':
      return t(
        'ai.agents.execution.diagnostic.skill',
        'Skill 执行未完成，请检查已绑定版本和运行状态后重试',
      );
    case 'agentRuntime':
      return t(
        'ai.agents.execution.diagnostic.runtime',
        'Agent 运行时未能完成执行，请稍后重试；持续失败时请联系管理员',
      );
    case 'agentTraceUnknown':
      return t(
        'ai.agents.execution.diagnostic.traceUnknown',
        '本次调用链路未能完成，请依据诊断代码联系管理员',
      );
    default:
      return t(
        'ai.agents.execution.diagnostic.unknown',
        'Agent 执行未能完成，请依据诊断代码联系管理员',
      );
  }
};

type AgentDefinition = {
  id: string;
  scopeType: 'SYSTEM' | 'TENANT';
  tenantId?: string;
  code: string;
  name: string;
  description?: string;
  status: 'DRAFT' | 'ACTIVE' | 'DISABLED';
  activeVersionId?: string;
  enabled: boolean;
};

type AgentSkillBinding = {
  id: string;
  skillId: string;
  skillVersionId: string;
  skillAlias: string;
  skillCode: string;
  skillVersionName: string;
  skillContentHash: string;
};

type AgentVersion = {
  id: string;
  version: string;
  manifestSchemaVersion: string;
  contentHash: string;
  primaryModelId: string;
  publicAccess: boolean;
  workflowReference?: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  publishedAt?: string;
  deprecatedAt?: string;
  manifest?: Record<string, unknown>;
  modelSelector?: {
    primaryModelId?: string;
    fallbackModelIds?: string[];
  };
  memoryPolicy?: Record<string, unknown>;
  budget?: Record<string, unknown>;
  approvalPolicy?: Record<string, unknown>;
  humanInterventionPolicy?: Record<string, unknown>;
  skillBindings?: AgentSkillBinding[];
};

type AgentExecutionStatus =
  | 'WAITING_APPROVAL'
  | 'PENDING'
  | 'RUNNING'
  | 'WAITING_SKILL'
  | 'WAITING_HUMAN'
  | 'PAUSED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'REJECTED'
  | 'CANCELLED';

type AgentExecutionTrace = {
  id: string;
  sequence: number;
  type: 'MODEL' | 'SKILL';
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  modelDefinitionId?: string;
  modelInvocationId?: string;
  skillExecutionId?: string;
  capabilityAlias?: string;
  inputTokens?: number;
  outputTokens?: number;
  cost?: number;
  input?: unknown;
  output?: unknown;
  errorCode?: string;
  errorMessage?: string;
  startedAt: string;
  completedAt?: string;
};

type AgentExecutionEvent = {
  id: string;
  sequence: number;
  type: string;
  executionStatus: AgentExecutionStatus;
  traceId?: string;
  interventionId?: string;
  actorId?: string;
  occurredAt: string;
  payload?: Record<string, unknown>;
};

type AgentExecutionEventFeed = {
  afterSequence: number;
  nextSequence: number;
  hasMore: boolean;
  executionStatus: AgentExecutionStatus;
  events: AgentExecutionEvent[];
};

type AgentExecutionStatusMetric = {
  status: AgentExecutionStatus;
  executionCount: number;
  inputTokens?: number;
  outputTokens?: number;
  cost?: number;
  steps?: number;
  humanInterventions?: number;
};

type AgentTraceMetric = {
  type: AgentExecutionTrace['type'];
  status: AgentExecutionTrace['status'];
  traceCount: number;
  inputTokens?: number;
  outputTokens?: number;
  cost?: number;
};

type AgentExecutionMetrics = {
  windowFrom: string;
  windowTo: string;
  totalExecutions: number;
  activeExecutions: number;
  succeededExecutions: number;
  failedExecutions: number;
  cancelledExecutions: number;
  rejectedExecutions: number;
  inputTokens: number;
  outputTokens: number;
  cost: number;
  steps: number;
  humanInterventions: number;
  statuses: AgentExecutionStatusMetric[];
  traces: AgentTraceMetric[];
};

type AgentExecution = {
  id: string;
  agentVersionId: string;
  agentVersionContentHash: string;
  status: AgentExecutionStatus;
  primaryModelId: string;
  currentModelId?: string;
  currentSkillExecutionId?: string;
  stepCount: number;
  loopDepth: number;
  maximumSteps: number;
  maximumInputTokens: number;
  maximumOutputTokens: number;
  maximumCost: number;
  consumedInputTokens: number;
  consumedOutputTokens: number;
  consumedCost: number;
  shortTermMemoryEnabled: boolean;
  longTermMemoryEnabled: boolean;
  maximumMemoryMessages: number;
  maximumMemorySummaryCharacters: number;
  compactedMessageCount: number;
  memoryRevision: number;
  memorySummaryHash?: string;
  lastMemoryCompactedAt?: string;
  longTermMemoryScope: 'SUBJECT';
  maximumLongTermMemoryEntries: number;
  longTermMemoryRetrievalTopK: number;
  longTermMemoryScoreThreshold: number;
  maximumLongTermMemoryInjectionCharacters: number;
  maximumLongTermMemoryRecordCharacters: number;
  longTermMemoryRetentionDays: number;
  longTermMemoryRetrievedCount: number;
  longTermMemoryInjectedCharacters: number;
  longTermMemorySnapshotHash?: string;
  longTermMemoryRetrievedAt?: string;
  longTermMemoryWrittenId?: string;
  longTermMemoryWrittenAt?: string;
  humanInterventionEnabled: boolean;
  maximumHumanInterventions: number;
  humanInterventionTimeoutSeconds: number;
  humanInterventionTimeoutAction: 'FAIL' | 'CANCEL';
  humanInterventionCount: number;
  currentHumanInterventionId?: string;
  humanInterventions?: AgentHumanIntervention[];
  approvalRequired: boolean;
  approvalInstructions?: string;
  requestedBy?: string;
  pauseRequested: boolean;
  pauseRequestedAt?: string;
  pauseRequestedBy?: string;
  pauseReason?: string;
  pausedAt?: string;
  resumedAt?: string;
  resumedBy?: string;
  input?: Record<string, unknown>;
  output?: unknown;
  traces?: AgentExecutionTrace[];
  createdAt: string;
  startedAt?: string;
  completedAt?: string;
  errorCode?: string;
  errorMessage?: string;
};

type AgentHumanIntervention = {
  id: string;
  status: 'REQUESTED' | 'WAITING' | 'COMPLETED' | 'EXPIRED' | 'CANCELLED';
  prompt: string;
  requestedBy: string;
  requestedAt: string;
  waitingAt?: string;
  expiresAt: string;
  outcome?: 'CONTINUE' | 'CANCEL';
  response?: Record<string, unknown>;
  responseComment?: string;
  respondedBy?: string;
  respondedAt?: string;
  completionReason?: string;
};

type AgentMemory = {
  id: string;
  agentVersionId: string;
  sourceExecutionId: string;
  memoryScope: 'SUBJECT';
  content: string;
  contentHash: string;
  createdAt: string;
  expiresAt: string;
};

type AgentFormValues = {
  code?: string;
  name: string;
  description?: string;
  enabled?: boolean;
};

type SkillBindingFormValue = {
  alias: string;
  versionId: string;
};

type VersionFormValues = {
  version: string;
  systemPrompt: string;
  primaryModelId: string;
  fallbackModelIds?: string[];
  skillBindings?: SkillBindingFormValue[];
  shortTermEnabled: boolean;
  longTermEnabled: boolean;
  maximumMessages: number;
  maximumSummaryCharacters: number;
  longTermScope: 'SUBJECT';
  maximumLongTermEntries: number;
  longTermRetrievalTopK: number;
  longTermScoreThreshold: number;
  maximumLongTermInjectionCharacters: number;
  maximumLongTermRecordCharacters: number;
  longTermRetentionDays: number;
  maximumSteps: number;
  maximumLoopDepth: number;
  maximumConcurrency: number;
  maximumInputTokens: number;
  maximumOutputTokens: number;
  maximumCost: number;
  approvalRequired: boolean;
  allowSelfApproval: boolean;
  approvalInstructions?: string;
  humanInterventionEnabled: boolean;
  maximumHumanInterventions: number;
  humanInterventionTimeoutSeconds: number;
  humanInterventionTimeoutAction: 'FAIL' | 'CANCEL';
  publicAccess: boolean;
  workflowRef?: string;
  inputSchema: string;
  outputSchema: string;
};

type HumanInterventionFormValues = {
  prompt: string;
};

type HumanInterventionResponseFormValues = {
  outcome: 'CONTINUE' | 'CANCEL';
  input: string;
  comment?: string;
};

type VersionStep = 'basics' | 'capabilities' | 'governance' | 'schema' | 'review';

const versionStepOrder: VersionStep[] = [
  'basics',
  'capabilities',
  'governance',
  'schema',
  'review',
];

const definitionStatusColor = (status: AgentDefinition['status']) => ({
  ACTIVE: 'green',
  DRAFT: 'gold',
  DISABLED: 'default',
}[status]);

const versionStatusColor = (status: AgentVersion['status']) => ({
  PUBLISHED: 'green',
  DRAFT: 'gold',
  DEPRECATED: 'default',
}[status]);

const executionStatusColor = (status: AgentExecutionStatus) => ({
  SUCCEEDED: 'green',
  RUNNING: 'processing',
  PENDING: 'blue',
  WAITING_SKILL: 'cyan',
  WAITING_HUMAN: 'magenta',
  WAITING_APPROVAL: 'gold',
  PAUSED: 'orange',
  FAILED: 'red',
  REJECTED: 'volcano',
  CANCELLED: 'default',
}[status]);

const terminalExecutionStatuses = new Set<AgentExecutionStatus>([
  'SUCCEEDED',
  'FAILED',
  'REJECTED',
  'CANCELLED',
]);

const pollingExecutionStatuses = new Set<AgentExecutionStatus>([
  'PENDING',
  'RUNNING',
  'WAITING_SKILL',
  'WAITING_HUMAN',
]);

const eventColor = (event: AgentExecutionEvent) => {
  if (event.type.endsWith('_FAILED') || event.type.endsWith('_REJECTED')) {
    return 'red';
  }
  if (event.type.endsWith('_SUCCEEDED') || event.type.endsWith('_COMPLETED')) {
    return 'green';
  }
  if (event.type.endsWith('_CANCELLED') || event.type.endsWith('_EXPIRED')) {
    return 'gray';
  }
  if (event.type.includes('WAITING') || event.type.includes('PAUSE')) {
    return 'orange';
  }
  return 'blue';
};

const parseObject = (value: string, label: string) => {
  const decoded: unknown = JSON.parse(value);
  if (!decoded || Array.isArray(decoded) || typeof decoded !== 'object') {
    throw new Error(`${label} must be a JSON object`);
  }
  return decoded as Record<string, unknown>;
};

const Agents = () => {
  const {message, modal} = App.useApp();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedSkillVersionId = searchParams.get('skillVersionId')?.trim() ?? '';
  const config = api['ai-workbench.agents'];
  const {t, ensure, locale} = useI18n();
  const [form] = Form.useForm<AgentFormValues>();
  const [versionForm] = Form.useForm<VersionFormValues>();
  const [interventionForm] = Form.useForm<HumanInterventionFormValues>();
  const [interventionResponseForm] =
    Form.useForm<HumanInterventionResponseFormValues>();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [versions, setVersions] = useState<AgentVersion[]>([]);
  const [executions, setExecutions] = useState<AgentExecution[]>([]);
  const [agentPage, setAgentPage] = useState(1);
  const [agentPageSize, setAgentPageSize] = useState(10);
  const [agentTotal, setAgentTotal] = useState(0);
  const [versionPage, setVersionPage] = useState(1);
  const [versionPageSize, setVersionPageSize] = useState(10);
  const [versionTotal, setVersionTotal] = useState(0);
  const [executionPage, setExecutionPage] = useState(1);
  const [executionPageSize, setExecutionPageSize] = useState(10);
  const [executionTotal, setExecutionTotal] = useState(0);
  const [workspaceSection, setWorkspaceSection] = useState('overview');
  const [memories, setMemories] = useState<AgentMemory[]>([]);
  const [loading, setLoading] = useState(false);
  const [versionLoading, setVersionLoading] = useState(false);
  const [executionLoading, setExecutionLoading] = useState(false);
  const [memoryLoading, setMemoryLoading] = useState(false);
  const [observabilityLoading, setObservabilityLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [dependencyValidating, setDependencyValidating] = useState(false);
  const [executionActionId, setExecutionActionId] = useState<string>();
  const [editing, setEditing] = useState<AgentDefinition>();
  const [agentDialogOpen, setAgentDialogOpen] = useState(false);
  const [selectedAgent, setSelectedAgent] = useState<AgentDefinition>();
  const [versionDialogOpen, setVersionDialogOpen] = useState(false);
  const [versionStep, setVersionStep] = useState<VersionStep>('basics');
  const [inspectingVersion, setInspectingVersion] = useState<AgentVersion>();
  const [executionDialogOpen, setExecutionDialogOpen] = useState(false);
  const [executionInputSchema, setExecutionInputSchema] =
    useState<Record<string, unknown>>();
  const [executionSchemaLoading, setExecutionSchemaLoading] = useState(false);
  const [inspectingExecution, setInspectingExecution] = useState<AgentExecution>();
  const [executionEvents, setExecutionEvents] = useState<AgentExecutionEvent[]>([]);
  const [eventCursor, setEventCursor] = useState(-1);
  const [eventHasMore, setEventHasMore] = useState(false);
  const [executionTraces, setExecutionTraces] = useState<AgentExecutionTrace[]>([]);
  const [tracePage, setTracePage] = useState(0);
  const [traceTotal, setTraceTotal] = useState(0);
  const [traceType, setTraceType] =
    useState<AgentExecutionTrace['type']>();
  const [traceStatus, setTraceStatus] =
    useState<AgentExecutionTrace['status']>();
  const [metrics, setMetrics] = useState<AgentExecutionMetrics>();
  const [metricsDays, setMetricsDays] = useState(1);
  const [memoryDrawerOpen, setMemoryDrawerOpen] = useState(false);
  const [interventionExecution, setInterventionExecution] =
    useState<AgentExecution>();
  const [respondingIntervention, setRespondingIntervention] = useState<{
    execution: AgentExecution;
    intervention: AgentHumanIntervention;
  }>();
  const [permissions, setPermissions] = useState<AgentWorkbenchPermissions>({
    ...denyAllAgentWorkbenchPermissions,
  });
  const [permissionsLoading, setPermissionsLoading] = useState(true);
  const [permissionsError, setPermissionsError] = useState(false);
  const [authorizationContextRevision, setAuthorizationContextRevision] =
    useState(0);
  const agentRequestSequence = useRef(0);
  const versionRequestSequence = useRef(0);
  const executionRequestSequence = useRef(0);
  const metricsRequestSequence = useRef(0);
  const traceRequestSequence = useRef(0);
  const eventRequestSequence = useRef(0);
  const executionDetailRequestSequence = useRef(0);
  const executionSchemaRequestSequence = useRef(0);
  const memoryRequestSequence = useRef(0);
  const dependencyValidationRequestSequence = useRef(0);
  const permissionRequestSequence = useRef(0);
  const permissionReloadScheduled = useRef(false);
  const authorizationContextSequence = useRef(0);
  const permissionsRef = useRef<AgentWorkbenchPermissions>({
    ...denyAllAgentWorkbenchPermissions,
  });
  const selectedAgentIdRef = useRef<string | undefined>(undefined);
  const selectedPrimaryModelId = Form.useWatch('primaryModelId', versionForm);
  const versionDraft = Form.useWatch([], versionForm) as VersionFormValues | undefined;
  const dependencyDirectoryEnabled = Boolean(
    selectedAgent
    && permissions.manageVersions
    && !permissionsLoading
    && !permissionsError
    && (versionDialogOpen || requestedSkillVersionId),
  );
  const dependencyDirectory = useDependencyOptionDirectory({
    baseUrl: config.baseUrl,
    consumerId: selectedAgent?.id,
    contextKey: authorizationContextRevision,
    enabled: dependencyDirectoryEnabled,
  });

  const renderDiagnostic = (
    surface: Extract<ExecutionDiagnosticSurface, 'agentExecution' | 'agentTrace'>,
    source: ExecutionDiagnosticSource,
  ) => {
    const diagnostic = resolveExecutionDiagnostic(surface, source);
    return (
      <Space direction="vertical" size={2}>
        {diagnostic.code && (
          <Text copyable code>{diagnostic.code}</Text>
        )}
        <Text type="danger">
          {agentDiagnosticLabel(t, diagnostic.category)}
        </Text>
      </Space>
    );
  };

  const canAgentAction = useCallback((action: AgentWorkbenchAction) => (
    canPerformAgentWorkbenchAction(permissions, action)
  ), [permissions]);

  const requireAgentAction = useCallback((action: AgentWorkbenchAction) => {
    if (canPerformAgentWorkbenchAction(permissionsRef.current, action)) return true;
    message.warning(t(
      'ai.agents.permissions.denied',
      '当前授权上下文不允许此操作，请刷新权限后重试',
    ));
    return false;
  }, [message, t]);

  const closeMutationSurfaces = useCallback(() => {
    // Preserve the form instances and their drafts while removing every
    // actionable surface from the previous authorization context.
    setAgentDialogOpen(false);
    setVersionDialogOpen(false);
    setExecutionDialogOpen(false);
    setInterventionExecution(undefined);
    setRespondingIntervention(undefined);
    Modal.destroyAll();
  }, []);

  const loadPermissions = useCallback(async () => {
    const requestSequence = ++permissionRequestSequence.current;
    const denied = {...denyAllAgentWorkbenchPermissions};
    permissionsRef.current = denied;
    setPermissions(denied);
    setPermissionsLoading(true);
    setPermissionsError(false);
    try {
      const response = await get<unknown>(
        `${config.baseUrl}/workbench-permissions`,
      );
      if (requestSequence !== permissionRequestSequence.current) return;
      const normalized = normalizeAgentWorkbenchPermissions(response);
      permissionsRef.current = normalized;
      setPermissions(normalized);
    } catch {
      if (requestSequence !== permissionRequestSequence.current) return;
      const deniedAfterFailure = {...denyAllAgentWorkbenchPermissions};
      permissionsRef.current = deniedAfterFailure;
      setPermissions(deniedAfterFailure);
      setPermissionsError(true);
    } finally {
      if (requestSequence === permissionRequestSequence.current) {
        setPermissionsLoading(false);
      }
    }
  }, [config.baseUrl]);

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  useEffect(() => {
    void loadPermissions();
    return () => {
      permissionRequestSequence.current += 1;
    };
  }, [loadPermissions]);

  useEffect(() => {
    let active = true;
    const handleAuthorizationContextChange = () => {
      // Invalidate the old request and snapshot synchronously. Related tenant,
      // role, and context events emitted in one turn share one reload request.
      permissionRequestSequence.current += 1;
      const denied = {...denyAllAgentWorkbenchPermissions};
      permissionsRef.current = denied;
      setPermissions(denied);
      setPermissionsLoading(true);
      setPermissionsError(false);
      authorizationContextSequence.current += 1;
      agentRequestSequence.current += 1;
      versionRequestSequence.current += 1;
      executionRequestSequence.current += 1;
      metricsRequestSequence.current += 1;
      traceRequestSequence.current += 1;
      eventRequestSequence.current += 1;
      executionDetailRequestSequence.current += 1;
      executionSchemaRequestSequence.current += 1;
      memoryRequestSequence.current += 1;
      dependencyValidationRequestSequence.current += 1;
      closeMutationSurfaces();
      setAgents([]);
      setAgentTotal(0);
      selectedAgentIdRef.current = undefined;
      setSelectedAgent(undefined);
      setVersions([]);
      setVersionTotal(0);
      setExecutions([]);
      setExecutionTotal(0);
      setMemories([]);
      setMetrics(undefined);
      setInspectingVersion(undefined);
      setInspectingExecution(undefined);
      setExecutionEvents([]);
      setExecutionTraces([]);
      setMemoryDrawerOpen(false);
      setSaving(false);
      setDependencyValidating(false);
      setExecutionActionId(undefined);
      setExecutionSchemaLoading(false);
      setObservabilityLoading(false);
      setMemoryLoading(false);
      setAgentPage(1);
      setVersionPage(1);
      setExecutionPage(1);
      setAuthorizationContextRevision((current) => current + 1);
      if (permissionReloadScheduled.current) return;
      permissionReloadScheduled.current = true;
      queueMicrotask(() => {
        if (!active) return;
        permissionReloadScheduled.current = false;
        void loadPermissions();
      });
    };
    const eventNames = [
      'sp-set-tenant',
      'sp-set-role',
      'sp-set-context-id',
    ] as const;
    eventNames.forEach((eventName) => window.addEventListener(
      eventName,
      handleAuthorizationContextChange,
    ));
    return () => {
      active = false;
      permissionReloadScheduled.current = false;
      eventNames.forEach((eventName) => window.removeEventListener(
        eventName,
        handleAuthorizationContextChange,
      ));
    };
  }, [closeMutationSurfaces, loadPermissions]);

  const loadAgents = useCallback(async () => {
    const requestSequence = ++agentRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    setLoading(true);
    try {
      const page = await get<Page<AgentDefinition>>(
        config.baseUrl,
        {page: agentPage - 1, size: agentPageSize, sort: 'createdAt,desc'},
      );
      if (requestSequence !== agentRequestSequence.current
        || contextSequence !== authorizationContextSequence.current) return;
      setAgents(page.content ?? []);
      setAgentTotal(page.page.totalElements ?? 0);
      setSelectedAgent((current) => current
        ? (page.content ?? []).find((agent) => agent.id === current.id) ?? current
        : undefined);
    } catch (error) {
      if (requestSequence !== agentRequestSequence.current
        || contextSequence !== authorizationContextSequence.current) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.load', 'Agent 列表加载失败'),
      ));
    } finally {
      if (requestSequence === agentRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setLoading(false);
      }
    }
  }, [agentPage, agentPageSize, config.baseUrl, message, t]);

  const loadVersions = useCallback(async (agent?: AgentDefinition) => {
    const requestSequence = ++versionRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    if (!agent) {
      setVersions([]);
      setVersionTotal(0);
      setVersionLoading(false);
      return;
    }
    setVersionLoading(true);
    try {
      const page = await get<Page<AgentVersion>>(
        `${config.baseUrl}/${agent.id}/versions`,
        {page: versionPage - 1, size: versionPageSize, sort: 'createdAt,desc'},
      );
      if (requestSequence === versionRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setVersions(page.content ?? []);
        setVersionTotal(page.page.totalElements ?? 0);
      }
    } catch (error) {
      if (requestSequence === versionRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setVersions([]);
        setVersionTotal(0);
        message.error(resolveApiErrorMessage(
          error,
          t('ai.agents.error.loadVersions', 'Agent 版本加载失败'),
        ));
      }
    } finally {
      if (requestSequence === versionRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setVersionLoading(false);
      }
    }
  }, [config.baseUrl, message, t, versionPage, versionPageSize]);

  const loadExecutions = useCallback(async (
    agent?: AgentDefinition,
    silent = false,
  ) => {
    const requestSequence = ++executionRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    if (!agent) {
      setExecutions([]);
      setExecutionTotal(0);
      setExecutionLoading(false);
      return;
    }
    setExecutionLoading(true);
    try {
      const page = await get<Page<AgentExecution>>(
        `${config.baseUrl}/${agent.id}/executions`,
        {
          page: executionPage - 1,
          size: executionPageSize,
          sort: 'createdAt,desc',
        },
      );
      if (requestSequence !== executionRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setExecutions(page.content ?? []);
      setExecutionTotal(page.page.totalElements ?? 0);
      setInspectingExecution((current) => {
        if (!current) return undefined;
        const summary = (page.content ?? []).find((execution) =>
          execution.id === current.id);
        return summary ? {
          ...current,
          ...summary,
          input: current.input,
          output: current.output,
          humanInterventions: current.humanInterventions,
          traces: current.traces,
        } : current;
      });
    } catch (error) {
      if (requestSequence === executionRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setExecutions([]);
        setExecutionTotal(0);
      }
      if (!silent && requestSequence === executionRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        message.error(resolveApiErrorMessage(
          error,
          t('ai.agents.error.loadExecutions', 'Agent 执行记录加载失败'),
        ));
      }
    } finally {
      if (requestSequence === executionRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setExecutionLoading(false);
      }
    }
  }, [config.baseUrl, executionPage, executionPageSize, message, t]);

  const loadMetrics = useCallback(async (
    agent?: AgentDefinition,
    days = metricsDays,
  ) => {
    const requestSequence = ++metricsRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    if (!agent) {
      setMetrics(undefined);
      return;
    }
    try {
      const windowTo = new Date();
      const windowFrom = new Date(
        windowTo.getTime() - days * 24 * 60 * 60 * 1000,
      );
      const value = await get<AgentExecutionMetrics>(
        `${config.baseUrl}/${agent.id}/metrics`,
        {
          from: windowFrom.toISOString(),
          to: windowTo.toISOString(),
        },
      );
      if (requestSequence !== metricsRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setMetrics(value);
    } catch (error) {
      if (requestSequence !== metricsRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setMetrics(undefined);
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.loadMetrics', 'Agent 运行指标加载失败'),
      ));
    }
  }, [config.baseUrl, message, metricsDays, t]);

  const loadTraces = useCallback(async (
    agent: AgentDefinition,
    executionId: string,
    page = tracePage,
    type = traceType,
    status = traceStatus,
    silent = false,
  ) => {
    const requestSequence = ++traceRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    try {
      const result = await get<Page<AgentExecutionTrace>>(
        `${config.baseUrl}/${agent.id}/executions/${executionId}/traces`,
        {
          page,
          size: 10,
          sort: 'sequence,asc',
          ...(type ? {type} : {}),
          ...(status ? {status} : {}),
        },
      );
      if (requestSequence !== traceRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setExecutionTraces(result.content ?? []);
      setTracePage(result.page.number ?? page);
      setTraceTotal(result.page.totalElements ?? 0);
    } catch (error) {
      if (requestSequence !== traceRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setExecutionTraces([]);
      setTraceTotal(0);
      if (!silent) {
        message.error(resolveApiErrorMessage(
          error,
          t('ai.agents.error.loadTraces', 'Agent 调用链路加载失败'),
        ));
      }
    }
  }, [config.baseUrl, message, t, tracePage, traceStatus, traceType]);

  const loadEvents = useCallback(async (
    agent: AgentDefinition,
    executionId: string,
    after = eventCursor,
    append = true,
    silent = false,
  ) => {
    const requestSequence = ++eventRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    try {
      const feed = await get<AgentExecutionEventFeed>(
        `${config.baseUrl}/${agent.id}/executions/${executionId}/events`,
        {after, limit: 100},
      );
      if (requestSequence !== eventRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setExecutionEvents((current) => {
        const source = append ? [...current, ...(feed.events ?? [])] : feed.events ?? [];
        return Array.from(
          new Map(source.map((event) => [event.sequence, event])).values(),
        ).sort((left, right) => left.sequence - right.sequence);
      });
      setEventCursor(feed.nextSequence);
      setEventHasMore(feed.hasMore);
    } catch (error) {
      if (requestSequence !== eventRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      if (!silent) {
        message.error(resolveApiErrorMessage(
          error,
          t('ai.agents.error.loadEvents', 'Agent 执行事件加载失败'),
        ));
      }
    }
  }, [config.baseUrl, eventCursor, message, t]);

  const openExecutionDetails = useCallback(async (
    execution: AgentExecution,
  ) => {
    const requestSequence = ++executionDetailRequestSequence.current;
    traceRequestSequence.current += 1;
    eventRequestSequence.current += 1;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id) return;
    const agent = selectedAgent;
    setInspectingExecution(execution);
    setExecutionEvents([]);
    setEventCursor(-1);
    setEventHasMore(false);
    setExecutionTraces([]);
    setTracePage(0);
    setTraceTotal(0);
    setTraceType(undefined);
    setTraceStatus(undefined);
    setObservabilityLoading(true);
    try {
      const [details, tracePageResult, eventFeed] = await Promise.all([
        get<AgentExecution>(
          `${config.baseUrl}/${agent.id}/executions/${execution.id}`,
        ),
        get<Page<AgentExecutionTrace>>(
          `${config.baseUrl}/${agent.id}/executions/${execution.id}/traces`,
          {page: 0, size: 10, sort: 'sequence,asc'},
        ),
        get<AgentExecutionEventFeed>(
          `${config.baseUrl}/${agent.id}/executions/${execution.id}/events`,
          {after: -1, limit: 100},
        ),
      ]);
      if (requestSequence !== executionDetailRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setInspectingExecution(details);
      setExecutionTraces(tracePageResult.content ?? []);
      setTracePage(tracePageResult.page.number ?? 0);
      setTraceTotal(tracePageResult.page.totalElements ?? 0);
      setExecutionEvents(eventFeed.events ?? []);
      setEventCursor(eventFeed.nextSequence);
      setEventHasMore(eventFeed.hasMore);
    } catch (error) {
      if (requestSequence !== executionDetailRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.loadExecutionDetails', 'Agent 执行详情加载失败'),
      ));
    } finally {
      if (requestSequence === executionDetailRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setObservabilityLoading(false);
      }
    }
  }, [config.baseUrl, message, selectedAgent, t]);

  const refreshExecutionDetails = useCallback(async (silent = false) => {
    const requestSequence = ++executionDetailRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id
      || !inspectingExecution) return;
    const agent = selectedAgent;
    const executionId = inspectingExecution.id;
    setObservabilityLoading(true);
    try {
      const details = await get<AgentExecution>(
        `${config.baseUrl}/${agent.id}/executions/${executionId}`,
      );
      if (requestSequence !== executionDetailRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setInspectingExecution(details);
      await Promise.all([
        loadEvents(agent, executionId, eventCursor, true, silent),
        loadTraces(
          agent,
          executionId,
          tracePage,
          traceType,
          traceStatus,
          silent,
        ),
      ]);
    } catch (error) {
      if (requestSequence !== executionDetailRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      if (!silent) {
        message.error(resolveApiErrorMessage(
          error,
          t('ai.agents.error.loadExecutionDetails', 'Agent 执行详情加载失败'),
        ));
      }
    } finally {
      if (requestSequence === executionDetailRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setObservabilityLoading(false);
      }
    }
  }, [
    config.baseUrl,
    eventCursor,
    inspectingExecution,
    loadEvents,
    loadTraces,
    message,
    selectedAgent,
    t,
    tracePage,
    traceStatus,
    traceType,
  ]);

  const loadMemories = useCallback(async (agent?: AgentDefinition) => {
    const requestSequence = ++memoryRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    if (!agent) {
      setMemories([]);
      return;
    }
    setMemoryLoading(true);
    try {
      const items = await get<AgentMemory[]>(
        `${config.baseUrl}/${agent.id}/memories`,
      );
      if (requestSequence !== memoryRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setMemories(items ?? []);
    } catch (error) {
      if (requestSequence !== memoryRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setMemories([]);
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.loadMemories', 'Agent 长期记忆加载失败'),
      ));
    } finally {
      if (requestSequence === memoryRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setMemoryLoading(false);
      }
    }
  }, [config.baseUrl, message, t]);

  useEffect(() => {
    void loadAgents();
  }, [authorizationContextRevision, loadAgents]);

  useEffect(() => {
    if (!versionDialogOpen || !dependencyDirectoryEnabled) return;
    void dependencyDirectory.prefetch(['MODEL', 'SKILL']);
  }, [
    dependencyDirectory.prefetch,
    dependencyDirectoryEnabled,
    versionDialogOpen,
  ]);

  useEffect(() => {
    if (!requestedSkillVersionId || !selectedAgent
      || !dependencyDirectoryEnabled) return;
    void dependencyDirectory.resolve('SKILL', [requestedSkillVersionId]);
  }, [
    dependencyDirectory.resolve,
    dependencyDirectoryEnabled,
    requestedSkillVersionId,
    selectedAgent,
  ]);

  useEffect(() => {
    void loadVersions(selectedAgent);
    void loadExecutions(selectedAgent);
  }, [loadExecutions, loadVersions, selectedAgent]);

  useEffect(() => {
    void loadMetrics(selectedAgent, metricsDays);
  }, [loadMetrics, metricsDays, selectedAgent]);

  useEffect(() => {
    setInspectingExecution(undefined);
    setExecutionEvents([]);
    setEventCursor(-1);
    setEventHasMore(false);
    setExecutionTraces([]);
    setTracePage(0);
    setTraceTotal(0);
  }, [selectedAgent?.id]);

  useEffect(() => {
    if (!selectedAgent || !executions.some((execution) =>
      pollingExecutionStatuses.has(execution.status))) return undefined;
    const timer = window.setTimeout(() => {
      void loadExecutions(selectedAgent, true);
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [executions, loadExecutions, selectedAgent]);

  useEffect(() => {
    if (!inspectingExecution
      || terminalExecutionStatuses.has(inspectingExecution.status)) {
      return undefined;
    }
    const timer = window.setTimeout(() => {
      void refreshExecutionDetails(true);
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [inspectingExecution, refreshExecutionDetails]);

  const openCreate = () => {
    if (!requireAgentAction('create')) return;
    setEditing(undefined);
    form.setFieldsValue({
      code: '',
      name: '',
      description: '',
      enabled: true,
    });
    setAgentDialogOpen(true);
  };

  const selectAgent = (agent?: AgentDefinition) => {
    const selectionChanged = selectedAgentIdRef.current !== agent?.id;
    if (selectionChanged) {
      versionRequestSequence.current += 1;
      executionRequestSequence.current += 1;
      metricsRequestSequence.current += 1;
      traceRequestSequence.current += 1;
      eventRequestSequence.current += 1;
      executionDetailRequestSequence.current += 1;
      executionSchemaRequestSequence.current += 1;
      memoryRequestSequence.current += 1;
      dependencyValidationRequestSequence.current += 1;
      setVersions([]);
      setVersionTotal(0);
      setVersionLoading(Boolean(agent));
      setExecutions([]);
      setExecutionTotal(0);
      setExecutionLoading(Boolean(agent));
      setMetrics(undefined);
      setMemories([]);
      setInspectingVersion(undefined);
      setInspectingExecution(undefined);
      setExecutionEvents([]);
      setEventCursor(-1);
      setEventHasMore(false);
      setExecutionTraces([]);
      setTracePage(0);
      setTraceTotal(0);
      setExecutionInputSchema(undefined);
      setExecutionSchemaLoading(false);
      setMemoryDrawerOpen(false);
      setInterventionExecution(undefined);
      setRespondingIntervention(undefined);
      setExecutionActionId(undefined);
      setDependencyValidating(false);
      setObservabilityLoading(false);
      setVersionDialogOpen(false);
      setExecutionDialogOpen(false);
      Modal.destroyAll();
    }
    setVersionPage(1);
    setExecutionPage(1);
    setWorkspaceSection('overview');
    selectedAgentIdRef.current = agent?.id;
    setSelectedAgent(agent);
  };

  const openEdit = (agent: AgentDefinition) => {
    if (!requireAgentAction('edit')) return;
    setEditing(agent);
    form.setFieldsValue({
      code: agent.code,
      name: agent.name,
      description: agent.description,
      enabled: agent.enabled,
    });
    setAgentDialogOpen(true);
  };

  const saveAgent = async () => {
    if (!requireAgentAction(editing ? 'edit' : 'create')) return;
    const contextSequence = authorizationContextSequence.current;
    let values: AgentFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (contextSequence !== authorizationContextSequence.current
      || !requireAgentAction(editing ? 'edit' : 'create')) return;
    setSaving(true);
    try {
      if (editing) {
        await put(`${config.baseUrl}/${editing.id}`, {
          ...values,
          code: editing.code,
        });
      } else {
        await post(config.baseUrl, values);
      }
      if (contextSequence !== authorizationContextSequence.current) return;
      message.success(t('ai.agents.message.saved', 'Agent 已保存'));
      setAgentDialogOpen(false);
      await loadAgents();
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.save', 'Agent 保存失败'),
      ));
    } finally {
      if (contextSequence === authorizationContextSequence.current) {
        setSaving(false);
      }
    }
  };

  const removeAgent = (agent: AgentDefinition) => {
    if (!requireAgentAction('delete')) return;
    const contextSequence = authorizationContextSequence.current;
    modal.confirm({
      title: t('ai.agents.delete.title', '删除 Agent？'),
      content: t(
        'ai.agents.delete.description',
        '存在不可变版本的 Agent 不允许删除。',
      ),
      okButtonProps: {danger: true},
      onOk: async () => {
        if (contextSequence !== authorizationContextSequence.current
          || !requireAgentAction('delete')) return;
        try {
          await del(`${config.baseUrl}/${agent.id}`, []);
          if (contextSequence !== authorizationContextSequence.current) return;
          message.success(t('ai.agents.message.deleted', 'Agent 已删除'));
          if (selectedAgent?.id === agent.id) selectAgent(undefined);
          await loadAgents();
        } catch (error) {
          if (contextSequence !== authorizationContextSequence.current) return;
          message.error(resolveApiErrorMessage(
            error,
            t('ai.agents.error.delete', 'Agent 删除失败'),
          ));
        }
      },
    });
  };

  const openVersionCreate = () => {
    if (!requireAgentAction('createVersion')) return;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id) return;
    const requestedBinding = requestedSkillVersionId
      ? dependencyDirectory.getOption('SKILL', requestedSkillVersionId)
      : undefined;
    const requestedResolution = requestedSkillVersionId
      ? dependencyDirectory.getResolution('SKILL', requestedSkillVersionId)
      : undefined;
    if (requestedSkillVersionId && (
      requestedResolution?.status !== 'resolved'
      || !requestedBinding?.selectable
    )) {
      message.warning(t(
        'ai.agents.bind.unavailable',
        '待绑定的 Skill 版本不存在、未发布或当前作用域不可见',
      ));
      return;
    }
    const requestedAlias = (requestedBinding?.resourceCode ?? 'skill')
      .toLowerCase()
      .replace(/[^a-z0-9_.-]+/g, '-')
      .replace(/^[^a-z0-9]+/, '')
      .slice(0, 64) || 'skill';
    versionForm.resetFields();
    versionForm.setFieldsValue({
      version: '1.0.0',
      systemPrompt: '',
      primaryModelId: undefined,
      fallbackModelIds: [],
      skillBindings: requestedBinding ? [{
        alias: requestedAlias,
        versionId: requestedSkillVersionId,
      }] : [],
      shortTermEnabled: true,
      longTermEnabled: false,
      maximumMessages: 20,
      maximumSummaryCharacters: 8192,
      longTermScope: 'SUBJECT',
      maximumLongTermEntries: 500,
      longTermRetrievalTopK: 5,
      longTermScoreThreshold: 0.05,
      maximumLongTermInjectionCharacters: 6000,
      maximumLongTermRecordCharacters: 8192,
      longTermRetentionDays: 365,
      maximumSteps: 16,
      maximumLoopDepth: 4,
      maximumConcurrency: 4,
      maximumInputTokens: 32000,
      maximumOutputTokens: 4096,
      maximumCost: 10,
      approvalRequired: false,
      allowSelfApproval: false,
      approvalInstructions: '',
      humanInterventionEnabled: false,
      maximumHumanInterventions: 4,
      humanInterventionTimeoutSeconds: 3600,
      humanInterventionTimeoutAction: 'FAIL',
      publicAccess: false,
      workflowRef: '',
      inputSchema: JSON.stringify({
        type: 'object',
        properties: {
          request: {type: 'string'},
        },
        required: ['request'],
        additionalProperties: false,
      }, null, 2),
      outputSchema: JSON.stringify({
        type: 'object',
        additionalProperties: true,
      }, null, 2),
    });
    setVersionStep('basics');
    setVersionDialogOpen(true);
  };

  const closeVersionCreate = (force = false) => {
    if (force || !versionForm.isFieldsTouched()) {
      setVersionDialogOpen(false);
      return;
    }
    modal.confirm({
      title: t('ai.agents.version.discard.title', '放弃未创建的版本？'),
      content: t(
        'ai.agents.version.discard.description',
        '当前向导中的修改尚未创建，关闭后将丢失。',
      ),
      okButtonProps: {danger: true},
      okText: t('ai.agents.version.discard.confirm', '放弃修改'),
      cancelText: t('ai.agents.action.cancelDialog', '取消'),
      onOk: () => setVersionDialogOpen(false),
    });
  };

  const dependencyValidationMessage = (
    issues: DependencySelectionIssue[],
    kind: 'MODEL' | 'SKILL',
  ) => {
    const failed = issues.some((issue) =>
      issue.reason === 'RESOLVE_FAILED' || issue.reason === 'RESOLVING');
    if (failed) {
      return t(
        'ai.dependencies.validation.resolveFailed',
        '依赖状态校验失败，表单内容已保留，请重试',
      );
    }
    return kind === 'MODEL'
      ? t(
        'ai.dependencies.validation.modelUnavailable',
        '已选模型不可用，请重新选择后再保存',
      )
      : t(
        'ai.dependencies.validation.versionUnavailable',
        '已选依赖版本不可用，请重新选择后再保存',
      );
  };

  const dependencyFingerprint = (values: Pick<
    VersionFormValues,
    'primaryModelId' | 'fallbackModelIds' | 'skillBindings'
  >) => JSON.stringify({
    primaryModelId: values.primaryModelId ?? null,
    fallbackModelIds: values.fallbackModelIds ?? [],
    skillBindings: (values.skillBindings ?? []).map((binding) => ({
      alias: binding.alias ?? '',
      versionId: binding.versionId ?? null,
    })),
  });

  const validateVersionDependencies = async (
    values: Pick<
      VersionFormValues,
      'primaryModelId' | 'fallbackModelIds' | 'skillBindings'
    >,
    refresh = true,
  ) => {
    const validationSequence = ++dependencyValidationRequestSequence.current;
    const fingerprint = dependencyFingerprint(values);
    const modelIds = [
      values.primaryModelId,
      ...(values.fallbackModelIds ?? []),
    ];
    const skillIds = (values.skillBindings ?? [])
      .map((binding) => binding.versionId);
    const [modelIssues, skillIssues] = await Promise.all([
      dependencyDirectory.validate('MODEL', modelIds, refresh),
      dependencyDirectory.validate('SKILL', skillIds, refresh),
    ]);
    const currentValues = versionForm.getFieldsValue([
      'primaryModelId',
      'fallbackModelIds',
      'skillBindings',
    ]) as Pick<VersionFormValues,
      'primaryModelId' | 'fallbackModelIds' | 'skillBindings'>;
    if (validationSequence !== dependencyValidationRequestSequence.current
      || dependencyFingerprint(currentValues) !== fingerprint) {
      return false;
    }
    const primaryIssue = modelIssues.some((issue) =>
      issue.id === values.primaryModelId);
    const fallbackIssue = modelIssues.some((issue) =>
      (values.fallbackModelIds ?? []).includes(issue.id));
    versionForm.setFields([
      {
        name: 'primaryModelId',
        errors: primaryIssue
          ? [dependencyValidationMessage(modelIssues, 'MODEL')]
          : [],
      },
      {
        name: 'fallbackModelIds',
        errors: fallbackIssue
          ? [dependencyValidationMessage(modelIssues, 'MODEL')]
          : [],
      },
      {
        name: 'skillBindings',
        errors: skillIssues.length > 0
          ? [dependencyValidationMessage(skillIssues, 'SKILL')]
          : [],
      },
    ]);
    if (modelIssues.length === 0 && skillIssues.length === 0) return true;
    setVersionStep('capabilities');
    return false;
  };

  const validateVersionStep = async (step: VersionStep) => {
    const fields: Record<VersionStep, string[]> = {
      basics: ['version', 'systemPrompt'],
      capabilities: ['primaryModelId', 'fallbackModelIds', 'skillBindings'],
      governance: [
        'shortTermEnabled',
        'longTermEnabled',
        'maximumMessages',
        'maximumSummaryCharacters',
        'longTermScope',
        'maximumLongTermEntries',
        'longTermRetrievalTopK',
        'longTermScoreThreshold',
        'maximumLongTermInjectionCharacters',
        'maximumLongTermRecordCharacters',
        'longTermRetentionDays',
        'maximumSteps',
        'maximumLoopDepth',
        'maximumConcurrency',
        'maximumInputTokens',
        'maximumOutputTokens',
        'maximumCost',
        'approvalRequired',
        'allowSelfApproval',
        'approvalInstructions',
        'humanInterventionEnabled',
        'maximumHumanInterventions',
        'humanInterventionTimeoutSeconds',
        'humanInterventionTimeoutAction',
        'publicAccess',
      ],
      schema: ['workflowRef', 'inputSchema', 'outputSchema'],
      review: [],
    };
    try {
      await versionForm.validateFields(fields[step]);
      if (step === 'capabilities') {
        setDependencyValidating(true);
        try {
          const values = versionForm.getFieldsValue([
            'primaryModelId',
            'fallbackModelIds',
            'skillBindings',
          ]);
          if (!await validateVersionDependencies(values)) return false;
        } finally {
          setDependencyValidating(false);
        }
      }
      if (step === 'schema') {
        parseObject(versionForm.getFieldValue('inputSchema'), 'Input Schema');
        parseObject(versionForm.getFieldValue('outputSchema'), 'Output Schema');
      }
      return true;
    } catch {
      if (step === 'schema') {
        message.error(t(
          'ai.agents.error.schema',
          '输入和输出 Schema 必须是有效的 JSON 对象',
        ));
      }
      return false;
    }
  };

  const navigateVersionStep = async (next: VersionStep) => {
    if (dependencyValidating) return;
    const currentIndex = versionStepOrder.indexOf(versionStep);
    const nextIndex = versionStepOrder.indexOf(next);
    if (nextIndex > currentIndex && !await validateVersionStep(versionStep)) return;
    setVersionStep(nextIndex > currentIndex + 1
      ? versionStepOrder[currentIndex + 1]
      : next);
  };

  const saveVersion = async () => {
    if (!requireAgentAction('createVersion')) return;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id) return;
    const agent = selectedAgent;
    let values: VersionFormValues;
    try {
      values = await versionForm.validateFields();
    } catch {
      return;
    }
    if (contextSequence !== authorizationContextSequence.current
      || selectedAgentIdRef.current !== agent.id
      || !requireAgentAction('createVersion')) return;
    let inputSchema: Record<string, unknown>;
    let outputSchema: Record<string, unknown>;
    try {
      inputSchema = parseObject(values.inputSchema, 'Input Schema');
      outputSchema = parseObject(values.outputSchema, 'Output Schema');
    } catch {
      message.error(t(
        'ai.agents.error.schema',
        '输入和输出 Schema 必须是有效的 JSON 对象',
      ));
      return;
    }
    if (contextSequence !== authorizationContextSequence.current
      || selectedAgentIdRef.current !== agent.id
      || !requireAgentAction('createVersion')) return;
    setDependencyValidating(true);
    let dependenciesValid = false;
    try {
      dependenciesValid = await validateVersionDependencies(values);
    } finally {
      if (contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setDependencyValidating(false);
      }
    }
    if (!dependenciesValid
      || contextSequence !== authorizationContextSequence.current
      || selectedAgentIdRef.current !== agent.id
      || !requireAgentAction('createVersion')) return;
    let bindings: Array<{
      alias: string;
      skillId: string;
      versionId: string;
    }>;
    try {
      bindings = (values.skillBindings ?? []).map((binding) => {
        const option = dependencyDirectory.getOption(
          'SKILL',
          binding.versionId,
        );
        if (!option?.selectable || !option.resourceVersionId) {
          throw new Error('Skill version is unavailable');
        }
        return {
          alias: binding.alias,
          skillId: option.resourceId,
          versionId: option.resourceVersionId,
        };
      });
    } catch {
      message.error(t(
        'ai.agents.error.skillVersion',
        '绑定的 Skill 版本已不可用，请重新选择',
      ));
      return;
    }
    const manifest = {
      apiVersion: 'simplepoint.io/v1alpha1',
      kind: 'Agent',
      metadata: {
        name: agent.code,
        version: values.version,
      },
      spec: {
        systemPrompt: values.systemPrompt,
        model: {
          primaryModelId: values.primaryModelId,
          fallbackModelIds: values.fallbackModelIds ?? [],
        },
        skills: bindings,
        behavior: {
          instructions: [],
          responseStyle: 'concise',
        },
        memory: {
          shortTermEnabled: values.shortTermEnabled,
          longTermEnabled: values.longTermEnabled,
          maximumMessages: values.maximumMessages,
          maximumSummaryCharacters: values.maximumSummaryCharacters,
          longTermScope: values.longTermScope,
          maximumLongTermEntries: values.maximumLongTermEntries,
          longTermRetrievalTopK: values.longTermRetrievalTopK,
          longTermScoreThreshold: values.longTermScoreThreshold,
          maximumLongTermInjectionCharacters:
            values.maximumLongTermInjectionCharacters,
          maximumLongTermRecordCharacters:
            values.maximumLongTermRecordCharacters,
          longTermRetentionDays: values.longTermRetentionDays,
        },
        budgets: {
          maximumSteps: values.maximumSteps,
          maximumLoopDepth: values.maximumLoopDepth,
          maximumConcurrency: values.maximumConcurrency,
          maximumInputTokens: values.maximumInputTokens,
          maximumOutputTokens: values.maximumOutputTokens,
          maximumCost: values.maximumCost,
        },
        approvals: {
          execution: {
            required: values.approvalRequired,
            allowSelfApproval: values.allowSelfApproval,
            ...(values.approvalInstructions
              ? {instructions: values.approvalInstructions} : {}),
          },
        },
        humanIntervention: {
          enabled: values.humanInterventionEnabled,
          maximumRequests: values.maximumHumanInterventions,
          timeoutSeconds: values.humanInterventionTimeoutSeconds,
          timeoutAction: values.humanInterventionTimeoutAction,
        },
        inputSchema,
        outputSchema,
        publicAccess: values.publicAccess,
        ...(values.workflowRef ? {workflowRef: values.workflowRef} : {}),
      },
    };
    setSaving(true);
    try {
      await post(`${config.baseUrl}/${agent.id}/versions`, {
        version: values.version,
        manifest,
      });
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.success(t(
        'ai.agents.message.versionCreated',
        '不可变 Agent 版本已创建',
      ));
      closeVersionCreate(true);
      if (requestedSkillVersionId) setSearchParams({}, {replace: true});
      await loadVersions(agent);
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.createVersion', 'Agent 版本创建失败'),
      ));
    } finally {
      if (contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setSaving(false);
      }
    }
  };

  const changeVersionStatus = async (
    agent: AgentDefinition,
    version: AgentVersion,
    action: 'publish' | 'deprecate',
  ) => {
    if (!requireAgentAction(action)) return;
    const contextSequence = authorizationContextSequence.current;
    if (selectedAgentIdRef.current !== agent.id) return;
    try {
      await post(
        `${config.baseUrl}/${agent.id}/versions/${version.id}/${action}`,
        {},
      );
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.success(action === 'publish'
        ? t('ai.agents.message.published', 'Agent 版本已发布并激活')
        : t('ai.agents.message.deprecated', 'Agent 版本已废弃'));
      await Promise.all([loadVersions(agent), loadAgents()]);
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.changeStatus', '版本状态变更失败'),
      ));
    }
  };

  const confirmVersionStatus = (
    version: AgentVersion,
    action: 'publish' | 'deprecate',
  ) => {
    if (!requireAgentAction(action)) return;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id) return;
    const agent = selectedAgent;
    modal.confirm({
      title: action === 'publish'
        ? t('ai.agents.version.publish.title', '发布这个 Agent 版本？')
        : t('ai.agents.version.deprecate.title', '废弃这个 Agent 版本？'),
      content: action === 'publish'
        ? t(
          'ai.agents.version.publish.description',
          '发布后该不可变版本将成为 Agent 的活动版本，新执行会固定使用它。',
        )
        : t(
          'ai.agents.version.deprecate.description',
          '废弃后不能再作为活动版本使用，已经开始的执行不受影响。',
        ),
      okButtonProps: action === 'deprecate' ? {danger: true} : undefined,
      okText: action === 'publish'
        ? t('ai.agents.action.publish', '发布')
        : t('ai.agents.action.deprecate', '废弃'),
      cancelText: t('ai.agents.action.cancelDialog', '取消'),
      onOk: () => changeVersionStatus(agent, version, action),
    });
  };

  const openExecution = async () => {
    if (!requireAgentAction('execute')) return;
    const requestSequence = ++executionSchemaRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent?.activeVersionId
      || selectedAgentIdRef.current !== selectedAgent.id) {
      message.warning(t(
        'ai.agents.execution.noActiveVersion',
        '请先发布并激活一个 Agent 版本',
      ));
      return;
    }
    const agent = selectedAgent;
    setExecutionDialogOpen(true);
    setExecutionInputSchema(undefined);
    setExecutionSchemaLoading(true);
    try {
      const version = await get<AgentVersion>(
        `${config.baseUrl}/${agent.id}/versions/${agent.activeVersionId}`,
      );
      if (requestSequence !== executionSchemaRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setExecutionInputSchema(inputSchemaFromManifest(version.manifest));
    } catch (error) {
      if (requestSequence !== executionSchemaRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      setExecutionDialogOpen(false);
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.loadExecutionSchema', 'Agent 输入 Schema 加载失败'),
      ));
    } finally {
      if (requestSequence === executionSchemaRequestSequence.current
        && contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setExecutionSchemaLoading(false);
      }
    }
  };

  const startExecution = async (input: ExecutionInput) => {
    if (!requireAgentAction('execute')) return;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id) return;
    const agent = selectedAgent;
    setSaving(true);
    try {
      const execution = await post<AgentExecution>(
        `${config.baseUrl}/${agent.id}/executions`,
        {
          idempotencyKey: globalThis.crypto?.randomUUID?.()
            ?? `${Date.now()}-${Math.random()}`,
          input,
        },
      );
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.success(t(
        'ai.agents.message.executionStarted',
        'Agent 执行已提交',
      ));
      setExecutionDialogOpen(false);
      await Promise.all([
        loadExecutions(agent),
        loadMetrics(agent),
        openExecutionDetails(execution),
      ]);
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.startExecution', 'Agent 执行提交失败'),
      ));
    } finally {
      if (contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setSaving(false);
      }
    }
  };

  const executionAction = async (
    execution: AgentExecution,
    action: 'approve' | 'reject' | 'pause' | 'resume' | 'cancel',
    note = '',
  ) => {
    if (!requireAgentAction(action)) return;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id) return;
    const agent = selectedAgent;
    setExecutionActionId(execution.id);
    try {
      await post(
        `${config.baseUrl}/${agent.id}/executions/${execution.id}/${action}`,
        action === 'approve' || action === 'reject'
          ? {comment: note}
          : action === 'pause'
            ? {reason: note}
            : {},
      );
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.success(t(
        `ai.agents.message.${action}`,
        action === 'approve'
          ? 'Agent 执行已审批'
          : action === 'reject'
            ? 'Agent 执行已驳回'
            : action === 'pause'
              ? 'Agent 执行已请求暂停'
              : action === 'resume'
                ? 'Agent 执行已恢复'
                : 'Agent 执行已取消',
      ));
      await Promise.all([
        loadExecutions(agent),
        loadMetrics(agent),
        inspectingExecution?.id === execution.id
          ? refreshExecutionDetails()
          : Promise.resolve(),
      ]);
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.executionAction', 'Agent 执行状态变更失败'),
      ));
    } finally {
      if (contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setExecutionActionId(undefined);
      }
    }
  };

  const confirmExecutionAction = (
    execution: AgentExecution,
    action: 'approve' | 'reject' | 'pause' | 'resume' | 'cancel',
  ) => {
    if (!requireAgentAction(action)) return;
    let note = '';
    const actionLabels = {
      approve: t('ai.agents.action.approve', '审批'),
      reject: t('ai.agents.action.reject', '驳回'),
      pause: t('ai.agents.action.pause', '暂停'),
      resume: t('ai.agents.action.resume', '恢复'),
      cancel: t('ai.agents.action.cancel', '取消'),
    };
    const requiresNote = action === 'approve' || action === 'reject'
      || action === 'pause';
    modal.confirm({
      title: t(
        'ai.agents.execution.actionConfirm',
        '确认{action}这次 Agent 执行？',
        {action: actionLabels[action]},
      ),
      content: requiresNote ? (
        <Input.TextArea
          autoFocus
          maxLength={1024}
          rows={3}
          placeholder={action === 'pause'
            ? t('ai.agents.execution.pauseReasonPlaceholder', '请输入暂停原因（可选）')
            : t('ai.agents.execution.actionCommentPlaceholder', '请输入处理说明（可选）')}
          onChange={(event) => {
            note = event.target.value.trim();
          }}
        />
      ) : t(
        'ai.agents.execution.actionEffect',
        '操作会立即提交；如果执行状态已变化，系统会保留当前状态并提示刷新。',
      ),
      okText: actionLabels[action],
      okButtonProps: action === 'reject' || action === 'cancel'
        ? {danger: true} : undefined,
      cancelText: t('ai.agents.action.cancelDialog', '取消'),
      onOk: () => executionAction(execution, action, note),
    });
  };

  const openHumanIntervention = (execution: AgentExecution) => {
    if (!requireAgentAction('requestIntervention')) return;
    interventionForm.setFieldsValue({prompt: ''});
    setInterventionExecution(execution);
  };

  const requestHumanIntervention = async () => {
    if (!requireAgentAction('requestIntervention')) return;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id
      || !interventionExecution) return;
    const agent = selectedAgent;
    const execution = interventionExecution;
    let values: HumanInterventionFormValues;
    try {
      values = await interventionForm.validateFields();
    } catch {
      return;
    }
    if (contextSequence !== authorizationContextSequence.current
      || selectedAgentIdRef.current !== agent.id
      || !requireAgentAction('requestIntervention')) return;
    setSaving(true);
    try {
      await post(
        `${config.baseUrl}/${agent.id}/executions/${
          execution.id
        }/interventions`,
        {prompt: values.prompt},
      );
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.success(t(
        'ai.agents.message.interventionRequested',
        '已请求在下一个安全检查点进行人工介入',
      ));
      setInterventionExecution(undefined);
      await Promise.all([
        loadExecutions(agent),
        inspectingExecution?.id === execution.id
          ? refreshExecutionDetails()
          : Promise.resolve(),
      ]);
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.interventionRequest', '人工介入请求失败'),
      ));
    } finally {
      if (contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setSaving(false);
      }
    }
  };

  const openHumanInterventionResponse = async (execution: AgentExecution) => {
    if (!requireAgentAction('respondIntervention')) return;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id) return;
    const agent = selectedAgent;
    try {
      const details = await get<AgentExecution>(
        `${config.baseUrl}/${agent.id}/executions/${execution.id}`,
      );
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      const intervention = (details.humanInterventions ?? []).find(
        (item) => item.id === details.currentHumanInterventionId,
      );
      if (!intervention) {
        message.error(t(
          'ai.agents.error.interventionMissing',
          '当前人工介入任务不存在，请刷新后重试',
        ));
        return;
      }
      interventionResponseForm.setFieldsValue({
        outcome: 'CONTINUE',
        input: '{}',
        comment: '',
      });
      setRespondingIntervention({execution: details, intervention});
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.loadExecutionDetails', 'Agent 执行详情加载失败'),
      ));
    }
  };

  const respondHumanIntervention = async () => {
    if (!requireAgentAction('respondIntervention')) return;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id
      || !respondingIntervention) return;
    const agent = selectedAgent;
    const responseTarget = respondingIntervention;
    let values: HumanInterventionResponseFormValues;
    try {
      values = await interventionResponseForm.validateFields();
    } catch {
      return;
    }
    if (contextSequence !== authorizationContextSequence.current
      || selectedAgentIdRef.current !== agent.id
      || !requireAgentAction('respondIntervention')) return;
    let input: Record<string, unknown> = {};
    if (values.outcome === 'CONTINUE') {
      try {
        input = parseObject(values.input, 'Human intervention input');
      } catch {
        message.error(t(
          'ai.agents.error.interventionInput',
          '人工输入必须是有效的 JSON 对象',
        ));
        return;
      }
    }
    setSaving(true);
    try {
      const {execution, intervention} = responseTarget;
      await post(
        `${config.baseUrl}/${agent.id}/executions/${
          execution.id
        }/interventions/${intervention.id}/respond`,
        {
          outcome: values.outcome,
          input,
          comment: values.comment,
        },
      );
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.success(values.outcome === 'CONTINUE'
        ? t(
          'ai.agents.message.interventionContinued',
          '人工输入已提交，Agent 将继续执行',
        )
        : t(
          'ai.agents.message.interventionCancelled',
          'Agent 执行已由人工取消',
        ));
      setRespondingIntervention(undefined);
      await Promise.all([
        loadExecutions(agent),
        loadMetrics(agent),
        inspectingExecution?.id === execution.id
          ? refreshExecutionDetails()
          : Promise.resolve(),
      ]);
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current
        || selectedAgentIdRef.current !== agent.id) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.agents.error.interventionResponse', '人工介入提交失败'),
      ));
    } finally {
      if (contextSequence === authorizationContextSequence.current
        && selectedAgentIdRef.current === agent.id) {
        setSaving(false);
      }
    }
  };

  const openMemories = async () => {
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id) return;
    setMemoryDrawerOpen(true);
    await loadMemories(selectedAgent);
  };

  const removeMemory = (memory: AgentMemory) => {
    if (!requireAgentAction('deleteMemory')) return;
    const contextSequence = authorizationContextSequence.current;
    if (!selectedAgent || selectedAgentIdRef.current !== selectedAgent.id) return;
    const agent = selectedAgent;
    modal.confirm({
      title: t('ai.agents.memory.delete.title', '删除这条长期记忆？'),
      content: t(
        'ai.agents.memory.delete.description',
        '删除后无法恢复，后续执行不会再检索到这条记忆。',
      ),
      okButtonProps: {danger: true},
      onOk: async () => {
        if (contextSequence !== authorizationContextSequence.current
          || selectedAgentIdRef.current !== agent.id
          || !requireAgentAction('deleteMemory')) return;
        try {
          await del(
            `${config.baseUrl}/${agent.id}/memories/${memory.id}`,
            [],
          );
          if (contextSequence !== authorizationContextSequence.current
            || selectedAgentIdRef.current !== agent.id) return;
          message.success(t(
            'ai.agents.message.memoryDeleted',
            '长期记忆已删除',
          ));
          await loadMemories(agent);
        } catch (error) {
          if (contextSequence !== authorizationContextSequence.current
            || selectedAgentIdRef.current !== agent.id) return;
          message.error(resolveApiErrorMessage(
            error,
            t('ai.agents.error.deleteMemory', '长期记忆删除失败'),
          ));
        }
      },
    });
  };

  const requestedBindingOption = requestedSkillVersionId
    ? dependencyDirectory.getOption('SKILL', requestedSkillVersionId)
    : undefined;
  const requestedBindingResolution = requestedSkillVersionId
    ? dependencyDirectory.getResolution('SKILL', requestedSkillVersionId)
    : undefined;
  const requestedBindingReady = Boolean(
    selectedAgent
    && requestedBindingResolution?.status === 'resolved'
    && requestedBindingOption?.selectable,
  );
  const requestedBindingResolving = Boolean(
    selectedAgent
    && permissions.manageVersions
    && (!requestedBindingResolution
      || requestedBindingResolution.status === 'loading'),
  );
  const requestedBindingFailed = requestedBindingResolution?.status === 'error';

  const metricTraceCount = useMemo(() =>
    (metrics?.traces ?? []).reduce(
      (total, item) => total + item.traceCount,
      0,
    ), [metrics]);

  const definitionStatusLabels: Record<AgentDefinition['status'], string> = {
    ACTIVE: t('ai.agents.status.active', '已激活'),
    DRAFT: t('ai.agents.status.draft', '草稿'),
    DISABLED: t('ai.agents.status.disabled', '已停用'),
  };
  const versionStatusLabels: Record<AgentVersion['status'], string> = {
    PUBLISHED: t('ai.agents.status.published', '已发布'),
    DRAFT: t('ai.agents.status.draft', '草稿'),
    DEPRECATED: t('ai.agents.status.deprecated', '已废弃'),
  };
  const executionStatusLabels: Record<AgentExecutionStatus, string> = {
    WAITING_APPROVAL: t('ai.agents.status.waitingApproval', '等待审批'),
    PENDING: t('ai.agents.status.pending', '等待执行'),
    RUNNING: t('ai.agents.status.running', '执行中'),
    WAITING_SKILL: t('ai.agents.status.waitingSkill', '等待 Skill'),
    WAITING_HUMAN: t('ai.agents.status.waitingHuman', '等待人工输入'),
    PAUSED: t('ai.agents.status.paused', '已暂停'),
    SUCCEEDED: t('ai.agents.status.succeeded', '已成功'),
    FAILED: t('ai.agents.status.failed', '已失败'),
    REJECTED: t('ai.agents.status.rejected', '已驳回'),
    CANCELLED: t('ai.agents.status.cancelled', '已取消'),
  };
  const traceTypeLabels: Record<AgentExecutionTrace['type'], string> = {
    MODEL: t('ai.agents.trace.model', '模型'),
    SKILL: t('ai.agents.trace.skill', 'Skill'),
  };
  const traceStatusLabels: Record<AgentExecutionTrace['status'], string> = {
    RUNNING: t('ai.agents.status.running', '执行中'),
    SUCCEEDED: t('ai.agents.status.succeeded', '已成功'),
    FAILED: t('ai.agents.status.failed', '已失败'),
    CANCELLED: t('ai.agents.status.cancelled', '已取消'),
  };
  const interventionStatusLabels: Record<AgentHumanIntervention['status'], string> = {
    REQUESTED: t('ai.agents.intervention.status.requested', '已请求'),
    WAITING: t('ai.agents.intervention.status.waiting', '等待输入'),
    COMPLETED: t('ai.agents.intervention.status.completed', '已完成'),
    EXPIRED: t('ai.agents.intervention.status.expired', '已超时'),
    CANCELLED: t('ai.agents.intervention.status.cancelled', '已取消'),
  };
  const eventTypeLabels: Record<string, string> = {
    EXECUTION_CREATED: t('ai.agents.event.executionCreated', '执行已创建'),
    EXECUTION_STARTED: t('ai.agents.event.executionStarted', '执行已开始'),
    EXECUTION_SUCCEEDED: t('ai.agents.event.executionSucceeded', '执行成功'),
    EXECUTION_FAILED: t('ai.agents.event.executionFailed', '执行失败'),
    EXECUTION_CANCELLED: t('ai.agents.event.executionCancelled', '执行已取消'),
    APPROVAL_GRANTED: t('ai.agents.event.approvalGranted', '审批通过'),
    APPROVAL_REJECTED: t('ai.agents.event.approvalRejected', '审批驳回'),
    PAUSE_REQUESTED: t('ai.agents.event.pauseRequested', '已请求暂停'),
    PAUSED: t('ai.agents.event.paused', '执行已暂停'),
    RESUMED: t('ai.agents.event.resumed', '执行已恢复'),
    MODEL_STARTED: t('ai.agents.event.modelStarted', '模型调用开始'),
    MODEL_SUCCEEDED: t('ai.agents.event.modelSucceeded', '模型调用成功'),
    MODEL_FAILED: t('ai.agents.event.modelFailed', '模型调用失败'),
    SKILL_STARTED: t('ai.agents.event.skillStarted', 'Skill 调用开始'),
    SKILL_SUCCEEDED: t('ai.agents.event.skillSucceeded', 'Skill 调用成功'),
    SKILL_FAILED: t('ai.agents.event.skillFailed', 'Skill 调用失败'),
    MEMORY_RETRIEVED: t('ai.agents.event.memoryRetrieved', '已检索长期记忆'),
    MEMORY_WRITTEN: t('ai.agents.event.memoryWritten', '已写入长期记忆'),
    HUMAN_INTERVENTION_REQUESTED: t('ai.agents.event.interventionRequested', '已请求人工介入'),
    HUMAN_INTERVENTION_WAITING: t('ai.agents.event.interventionWaiting', '等待人工输入'),
    HUMAN_INTERVENTION_COMPLETED: t('ai.agents.event.interventionCompleted', '人工介入已完成'),
    HUMAN_INTERVENTION_CANCELLED: t('ai.agents.event.interventionCancelled', '人工介入已取消'),
    HUMAN_INTERVENTION_EXPIRED: t('ai.agents.event.interventionExpired', '人工介入已超时'),
  };

  return (
    <div style={{
      display: 'flex',
      flexDirection: 'column',
      gap: 16,
      height: '100%',
      minHeight: 0,
      overflow: 'auto',
    }}>
      <Alert
        showIcon
        type="info"
        closable
        message={t('ai.agents.notice.title', '声明式 Agent Registry')}
        description={t(
          'ai.agents.notice.description',
          'Agent 版本固定模型选择器和已发布 Skill 版本；运行时只能经过 Agent → Skill → MCP 能力链路。',
        )}
      />
      {permissionsLoading && (
        <Alert
          showIcon
          closable
          type="info"
          message={t(
            'ai.agents.permissions.loading.title',
            '正在加载 Agent 操作权限',
          )}
          description={t(
            'ai.agents.permissions.loading.description',
            '读取功能保持可用；权限确认前，新增、修改和执行等操作暂时隐藏。',
          )}
        />
      )}
      {permissionsError && (
        <Alert
          showIcon
          type="error"
          message={t(
            'ai.agents.permissions.error.title',
            'Agent 操作权限加载失败',
          )}
          description={t(
            'ai.agents.permissions.error.description',
            '你仍可查看当前页面；为避免误操作，所有变更操作已隐藏。请重试加载权限。',
          )}
          action={(
            <Button size="small" onClick={() => void loadPermissions()}>
              {t('ai.agents.permissions.retry', '重试')}
            </Button>
          )}
        />
      )}
      {requestedSkillVersionId && (
        <Alert
          showIcon
          type={requestedBindingReady
            ? 'success'
            : requestedBindingFailed
              ? 'error'
              : requestedBindingResolving || !selectedAgent
                ? 'info'
                : 'warning'}
          message={t('ai.agents.bind.title', '将刚发布的 Skill 版本绑定到 Agent')}
          description={!selectedAgent
            ? t(
              'ai.agents.bind.selectAgent',
              '请先从下方选择一个 Agent，再创建不可变 Agent 版本。',
            )
            : requestedBindingResolving
              ? t(
                'ai.agents.bind.resolving',
                '正在确认该 Skill 版本是否可用于当前 Agent…',
              )
              : requestedBindingFailed
                ? t(
                  'ai.agents.bind.resolveFailed',
                  'Skill 版本状态加载失败，未清除绑定请求，请重试。',
                )
                : requestedBindingReady
                  ? t(
              'ai.agents.bind.ready',
              '已选择 Agent“{name}”。创建新版本时会自动带入该固定 Skill 版本。',
              {name: selectedAgent.name},
            )
                  : t(
                    'ai.agents.bind.unavailable',
                    '待绑定的 Skill 版本不存在、未发布或当前作用域不可见',
                  )}
          action={(
            <Space>
              <Button onClick={() => setSearchParams({}, {replace: true})}>
                {t('ai.agents.bind.cancel', '取消绑定')}
              </Button>
              {requestedBindingFailed && selectedAgent
                && permissions.manageVersions && (
                <Button onClick={() => void dependencyDirectory.resolve(
                  'SKILL',
                  [requestedSkillVersionId],
                  {force: true},
                )}>
                  {t('action.retry', '重试')}
                </Button>
              )}
              {canAgentAction('createVersion') && (
                <Button
                  type="primary"
                  disabled={!requestedBindingReady}
                  onClick={openVersionCreate}
                >
                  {t('ai.agents.bind.createVersion', '创建 Agent 版本')}
                </Button>
              )}
            </Space>
          )}
        />
      )}
      <Card
        title={t('ai.agents.title', 'Agent')}
        extra={(
          <Space>
            <Button onClick={() => void loadAgents()}>
              {t('ai.agents.action.refresh', '刷新')}
            </Button>
            {canAgentAction('create') && (
              <Button type="primary" onClick={openCreate}>
                {t('ai.agents.action.create', '新增 Agent')}
              </Button>
            )}
          </Space>
        )}
      >
        <DataTable<AgentDefinition>
          rowKey="id"
          loading={loading}
          dataSource={agents}
          pagination={{
            current: agentPage,
            pageSize: agentPageSize,
            total: agentTotal,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: ['10', '20', '50'],
            showTotal: (total) => t(
              'ai.agents.pagination.total',
              '共 {total} 个 Agent',
              {total},
            ),
          }}
          scroll={{x: 960}}
          onChange={(pagination) => {
            setAgentPage(pagination.current ?? 1);
            setAgentPageSize(pagination.pageSize ?? 10);
          }}
          rowSelection={{
            type: 'radio',
            selectedRowKeys: selectedAgent ? [selectedAgent.id] : [],
            onChange: (_, rows) => selectAgent(rows[0]),
          }}
          onRow={(record) => ({
            tabIndex: 0,
            'aria-selected': selectedAgent?.id === record.id,
            onClick: () => selectAgent(record),
            onKeyDown: (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                selectAgent(record);
              }
            },
          })}
          columns={[
            {
              title: t('ai.agents.column.code', '代码'),
              dataIndex: 'code',
              width: 190,
            },
            {
              title: t('ai.agents.column.name', '名称'),
              dataIndex: 'name',
              width: 220,
            },
            {
              title: t('ai.agents.column.scope', '作用域'),
              dataIndex: 'scopeType',
              width: 110,
              render: (value) => (
                <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>
                  {value === 'TENANT'
                    ? t('ai.agents.scope.tenant', '租户')
                    : t('ai.agents.scope.system', '平台')}
                </Tag>
              ),
            },
            {
              title: t('ai.agents.column.status', '状态'),
              dataIndex: 'status',
              width: 110,
              render: (value) => (
                <Tag color={definitionStatusColor(value)}>
                  {definitionStatusLabels[value as AgentDefinition['status']]}
                </Tag>
              ),
            },
            {
              title: t('ai.agents.column.description', '说明'),
              dataIndex: 'description',
              ellipsis: true,
            },
            ...(canAgentAction('edit') || canAgentAction('delete') ? [{
              title: t('ai.agents.column.action', '操作'),
              width: 160,
              render: (_: unknown, record: AgentDefinition) => (
                <Space>
                  {canAgentAction('edit') && (
                    <Button type="link" onClick={(event) => {
                      event.stopPropagation();
                      openEdit(record);
                    }}>
                      {t('ai.agents.action.edit', '编辑')}
                    </Button>
                  )}
                  {canAgentAction('delete') && (
                    <Button danger type="link" onClick={(event) => {
                      event.stopPropagation();
                      removeAgent(record);
                    }}>
                      {t('ai.agents.action.delete', '删除')}
                    </Button>
                  )}
                </Space>
              ),
            }] : []),
          ]}
        />
      </Card>

      {!selectedAgent ? (
        <Card>
          <Empty
            description={t(
              'ai.agents.workspace.empty',
              '选择一个 Agent 后管理版本、查看指标和执行记录',
            )}
          >
            {canAgentAction('create') && (
              <Button type="primary" onClick={openCreate}>
                {t('ai.agents.action.create', '新增 Agent')}
              </Button>
            )}
          </Empty>
        </Card>
      ) : (
        <>
          <Card size="small">
            <Descriptions
              size="small"
              column={{xs: 1, sm: 2, lg: 4}}
              items={[
                {
                  key: 'name',
                  label: t('ai.agents.field.name', '名称'),
                  children: selectedAgent.name,
                },
                {
                  key: 'code',
                  label: t('ai.agents.field.code', '代码'),
                  children: <Text code copyable>{selectedAgent.code}</Text>,
                },
                {
                  key: 'status',
                  label: t('ai.agents.column.status', '状态'),
                  children: (
                    <Tag color={definitionStatusColor(selectedAgent.status)}>
                      {definitionStatusLabels[selectedAgent.status]}
                    </Tag>
                  ),
                },
                {
                  key: 'activeVersion',
                  label: t('ai.agents.workspace.activeVersion', '活动版本'),
                  children: selectedAgent.activeVersionId
                    ? <Text code copyable>{selectedAgent.activeVersionId}</Text>
                    : t('ai.agents.workspace.noActiveVersion', '尚未发布版本'),
                },
              ]}
            />
            <Tabs
              activeKey={workspaceSection}
              onChange={setWorkspaceSection}
              style={{marginBottom: -16}}
              items={[
                {
                  key: 'overview',
                  label: t('ai.agents.workspace.overview', '运行概览'),
                },
                {
                  key: 'versions',
                  label: (
                    <Space size={4}>
                      {t('ai.agents.versions.title', '不可变版本')}
                      <Tag>{versionTotal}</Tag>
                    </Space>
                  ),
                },
                {
                  key: 'executions',
                  label: (
                    <Space size={4}>
                      {t('ai.agents.executions.title', '执行记录')}
                      <Tag>{executionTotal}</Tag>
                    </Space>
                  ),
                },
              ]}
            />
          </Card>

      {workspaceSection === 'overview' && (
      <Card
        title={selectedAgent
          ? `${selectedAgent.name} · ${t('ai.agents.metrics.title', '运行指标')}`
          : t('ai.agents.metrics.empty', '选择 Agent 后查看运行指标')}
        extra={selectedAgent ? (
          <Space>
            <Select
              value={metricsDays}
              style={{width: 140}}
              onChange={setMetricsDays}
              options={[
                {
                  value: 1,
                  label: t('ai.agents.metrics.range1d', '最近 24 小时'),
                },
                {
                  value: 7,
                  label: t('ai.agents.metrics.range7d', '最近 7 天'),
                },
                {
                  value: 30,
                  label: t('ai.agents.metrics.range30d', '最近 30 天'),
                },
              ]}
            />
            <Button onClick={() => void loadMetrics(selectedAgent)}>
              {t('ai.agents.action.refresh', '刷新')}
            </Button>
          </Space>
        ) : undefined}
      >
        <Row gutter={[16, 16]}>
          <Col xs={12} md={6} xl={3}>
            <Statistic
              title={t('ai.agents.metrics.total', '执行总数')}
              value={metrics?.totalExecutions ?? 0}
            />
          </Col>
          <Col xs={12} md={6} xl={3}>
            <Statistic
              title={t('ai.agents.metrics.active', '活跃执行')}
              value={metrics?.activeExecutions ?? 0}
            />
          </Col>
          <Col xs={12} md={6} xl={3}>
            <Statistic
              title={t('ai.agents.metrics.succeeded', '成功')}
              value={metrics?.succeededExecutions ?? 0}
              valueStyle={{color: 'var(--ant-color-success)'}}
            />
          </Col>
          <Col xs={12} md={6} xl={3}>
            <Statistic
              title={t('ai.agents.metrics.failed', '失败/取消')}
              value={(metrics?.failedExecutions ?? 0)
                + (metrics?.cancelledExecutions ?? 0)}
              valueStyle={{color: 'var(--ant-color-error)'}}
            />
          </Col>
          <Col xs={12} md={6} xl={3}>
            <Statistic
              title={t('ai.agents.metrics.inputTokens', '输入 Token')}
              value={metrics?.inputTokens ?? 0}
            />
          </Col>
          <Col xs={12} md={6} xl={3}>
            <Statistic
              title={t('ai.agents.metrics.outputTokens', '输出 Token')}
              value={metrics?.outputTokens ?? 0}
            />
          </Col>
          <Col xs={12} md={6} xl={3}>
            <Statistic
              title={t('ai.agents.metrics.cost', '累计费用')}
              value={metrics?.cost ?? 0}
              precision={4}
            />
          </Col>
          <Col xs={12} md={6} xl={3}>
            <Statistic
              title={t('ai.agents.metrics.traces', '调用链路')}
              value={metricTraceCount}
            />
          </Col>
        </Row>
      </Card>
      )}

      {workspaceSection === 'versions' && (
      <Card
        title={selectedAgent
          ? `${selectedAgent.name} · ${t('ai.agents.versions.title', '不可变版本')}`
          : t('ai.agents.versions.empty', '选择 Agent 后管理版本')}
        extra={selectedAgent && canAgentAction('createVersion') ? (
          <Button type="primary" onClick={openVersionCreate}>
            {t('ai.agents.action.createVersion', '创建版本')}
          </Button>
        ) : undefined}
      >
        <DataTable<AgentVersion>
          rowKey="id"
          loading={versionLoading}
          dataSource={versions}
          pagination={{
            current: versionPage,
            pageSize: versionPageSize,
            total: versionTotal,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: ['10', '20', '50'],
            showTotal: (total) => t(
              'ai.agents.pagination.versionTotal',
              '共 {total} 个版本',
              {total},
            ),
          }}
          onChange={(pagination) => {
            setVersionPage(pagination.current ?? 1);
            setVersionPageSize(pagination.pageSize ?? 10);
          }}
          scroll={{x: 920}}
          locale={{emptyText: t('ai.agents.versions.noData', '暂无版本')}}
          columns={[
            {
              title: t('ai.agents.column.version', '版本'),
              dataIndex: 'version',
              width: 120,
            },
            {
              title: t('ai.agents.column.status', '状态'),
              dataIndex: 'status',
              width: 110,
              render: (value) => (
                <Tag color={versionStatusColor(value)}>
                  {versionStatusLabels[value as AgentVersion['status']]}
                </Tag>
              ),
            },
            {
              title: t('ai.agents.column.model', '主模型'),
              dataIndex: 'primaryModelId',
              width: 240,
              ellipsis: true,
              render: (value) => <Text code>{value}</Text>,
            },
            {
              title: t('ai.agents.column.skills', 'Skill 绑定'),
              width: 140,
              render: (_: unknown, record: AgentVersion) => (
                <Tag color="blue">{record.skillBindings?.length ?? 0}</Tag>
              ),
            },
            {
              title: t('ai.agents.column.contentHash', '内容 Hash'),
              dataIndex: 'contentHash',
              ellipsis: true,
            },
            {
              title: t('ai.agents.column.action', '操作'),
              width: 230,
              render: (_: unknown, record: AgentVersion) => (
                <Space>
                  <Button type="link" onClick={() => setInspectingVersion(record)}>
                    {t('ai.agents.action.inspect', '查看')}
                  </Button>
                  {canAgentAction('publish') && record.status === 'DRAFT' && (
                    <Button
                      type="link"
                      onClick={() => confirmVersionStatus(record, 'publish')}
                    >
                      {t('ai.agents.action.publish', '发布')}
                    </Button>
                  )}
                  {canAgentAction('deprecate') && record.status === 'PUBLISHED' && (
                    <Button
                      danger
                      type="link"
                      onClick={() => confirmVersionStatus(record, 'deprecate')}
                    >
                      {t('ai.agents.action.deprecate', '废弃')}
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>
      )}

      {workspaceSection === 'executions' && (
      <Card
        title={selectedAgent
          ? `${selectedAgent.name} · ${t('ai.agents.executions.title', '执行记录')}`
          : t('ai.agents.executions.empty', '选择 Agent 后查看执行记录')}
        extra={selectedAgent ? (
          <Space>
            <Button onClick={() => void openMemories()}>
              {t('ai.agents.action.memory', '长期记忆')}
            </Button>
            <Button onClick={() => void loadExecutions(selectedAgent)}>
              {t('ai.agents.action.refreshExecutions', '刷新执行')}
            </Button>
            {canAgentAction('execute') && (
              <Button
                type="primary"
                disabled={selectedAgent.status !== 'ACTIVE'}
                onClick={() => void openExecution()}
              >
                {t('ai.agents.action.execute', '执行 Agent')}
              </Button>
            )}
          </Space>
        ) : undefined}
      >
        <DataTable<AgentExecution>
          rowKey="id"
          loading={executionLoading}
          dataSource={executions}
          pagination={{
            current: executionPage,
            pageSize: executionPageSize,
            total: executionTotal,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: ['10', '20', '50'],
            showTotal: (total) => t(
              'ai.agents.pagination.executionTotal',
              '共 {total} 条执行',
              {total},
            ),
          }}
          onChange={(pagination) => {
            setExecutionPage(pagination.current ?? 1);
            setExecutionPageSize(pagination.pageSize ?? 10);
          }}
          scroll={{x: 1260}}
          locale={{emptyText: t('ai.agents.executions.noData', '暂无执行记录')}}
          columns={[
            {
              title: t('ai.agents.column.status', '状态'),
              dataIndex: 'status',
              width: 150,
              render: (value: AgentExecutionStatus) => (
                <Tag color={executionStatusColor(value)}>
                  {executionStatusLabels[value]}
                </Tag>
              ),
            },
            {
              title: t('ai.agents.execution.steps', '步骤'),
              width: 100,
              render: (_, record) => `${record.stepCount}/${record.maximumSteps}`,
            },
            {
              title: t('ai.agents.execution.tokens', 'Token'),
              width: 180,
              render: (_, record) => (
                <Text>
                  {record.consumedInputTokens}/{record.maximumInputTokens}
                  {' → '}
                  {record.consumedOutputTokens}/{record.maximumOutputTokens}
                </Text>
              ),
            },
            {
              title: t('ai.agents.execution.skill', '当前 Skill 执行'),
              dataIndex: 'currentSkillExecutionId',
              ellipsis: true,
              render: (value) => value
                ? <Text copyable code>{value}</Text>
                : '-',
            },
            {
              title: t('ai.agents.execution.createdAt', '提交时间'),
              dataIndex: 'createdAt',
              width: 190,
              render: (value) => value
                ? new Date(value).toLocaleString(locale)
                : '-',
            },
            {
              title: t('ai.agents.column.action', '操作'),
              width: 360,
              render: (_, record) => (
                <Space>
                  <Button
                    type="link"
                    onClick={() => void openExecutionDetails(record)}
                  >
                    {t('ai.agents.action.inspect', '查看')}
                  </Button>
                  {canAgentAction('approve')
                    && record.status === 'WAITING_APPROVAL' && (
                    <>
                      <Button
                        type="link"
                        loading={executionActionId === record.id}
                        onClick={() => confirmExecutionAction(record, 'approve')}
                      >
                        {t('ai.agents.action.approve', '审批')}
                      </Button>
                      <Button
                        danger
                        type="link"
                        disabled={executionActionId === record.id}
                        onClick={() => confirmExecutionAction(record, 'reject')}
                      >
                        {t('ai.agents.action.reject', '驳回')}
                      </Button>
                    </>
                  )}
                  {canAgentAction('pause')
                    && !terminalExecutionStatuses.has(record.status)
                    && record.status !== 'PAUSED' && (
                    <Button
                      type="link"
                      loading={executionActionId === record.id}
                      onClick={() => confirmExecutionAction(record, 'pause')}
                    >
                      {t('ai.agents.action.pause', '暂停')}
                    </Button>
                  )}
                  {canAgentAction('resume') && record.status === 'PAUSED' && (
                    <Button
                      type="link"
                      loading={executionActionId === record.id}
                      onClick={() => confirmExecutionAction(record, 'resume')}
                    >
                      {t('ai.agents.action.resume', '恢复')}
                    </Button>
                  )}
                  {canAgentAction('requestIntervention')
                    && record.humanInterventionEnabled
                    && !terminalExecutionStatuses.has(record.status)
                    && record.status !== 'WAITING_APPROVAL'
                    && record.status !== 'WAITING_HUMAN'
                    && !record.currentHumanInterventionId
                    && record.humanInterventionCount
                      < record.maximumHumanInterventions && (
                    <Button
                      type="link"
                      onClick={() => openHumanIntervention(record)}
                    >
                      {t('ai.agents.action.intervene', '人工介入')}
                    </Button>
                  )}
                  {canAgentAction('respondIntervention')
                    && record.status === 'WAITING_HUMAN' && (
                    <Button
                      type="link"
                      onClick={() => void openHumanInterventionResponse(record)}
                    >
                      {t('ai.agents.action.respondIntervention', '提交人工输入')}
                    </Button>
                  )}
                  {canAgentAction('cancel')
                    && !terminalExecutionStatuses.has(record.status) && (
                    <Button
                      danger
                      type="link"
                      disabled={executionActionId === record.id}
                      onClick={() => confirmExecutionAction(record, 'cancel')}
                    >
                      {t('ai.agents.action.cancel', '取消')}
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>
      )}
        </>
      )}

      <Modal
        open={agentDialogOpen && canAgentAction(editing ? 'edit' : 'create')}
        title={editing
          ? t('ai.agents.dialog.edit', '编辑 Agent')
          : t('ai.agents.dialog.create', '新增 Agent')}
        confirmLoading={saving}
        onOk={() => void saveAgent()}
        onCancel={() => setAgentDialogOpen(false)}
        destroyOnHidden={false}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="code"
            label={t('ai.agents.field.code', '代码')}
            rules={[
              {required: true},
              {pattern: /^[a-z0-9][a-z0-9_.-]{0,63}$/},
            ]}
          >
            <Input disabled={Boolean(editing)} />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('ai.agents.field.name', '名称')}
            rules={[{required: true, max: 128}]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('ai.agents.field.description', '说明')}
            rules={[{max: 512}]}
          >
            <TextArea rows={3} />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t('ai.agents.field.enabled', '启用')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={executionDialogOpen && canAgentAction('execute')}
        title={t('ai.agents.execution.start', '执行 Agent')}
        width={680}
        footer={null}
        onCancel={() => setExecutionDialogOpen(false)}
        destroyOnHidden={false}
      >
        <Alert
          showIcon
          type="info"
          closable
          message={t(
            'ai.agents.execution.notice',
            '本次执行固定当前活动 Agent 版本、模型和 Skill 版本。',
          )}
          style={{marginBottom: 16}}
        />
        <SchemaExecutionForm
          schema={executionInputSchema}
          loading={executionSchemaLoading}
          submitting={saving}
          submitText={t('ai.agents.action.execute', '执行 Agent')}
          onSubmit={startExecution}
        />
      </Modal>

      <Modal
        open={Boolean(interventionExecution)
          && canAgentAction('requestIntervention')}
        title={t('ai.agents.intervention.requestTitle', '请求人工介入')}
        confirmLoading={saving}
        onOk={() => void requestHumanIntervention()}
        onCancel={() => setInterventionExecution(undefined)}
        destroyOnHidden={false}
      >
        <Alert
          showIcon
          type="warning"
          message={t(
            'ai.agents.intervention.requestNotice',
            '执行将在下一个安全检查点等待，不会强制中断正在进行的模型或 Skill 调用。',
          )}
          style={{marginBottom: 16}}
        />
        <Form form={interventionForm} layout="vertical">
          <Form.Item
            name="prompt"
            label={t('ai.agents.intervention.prompt', '需要人工确认的问题')}
            rules={[{required: true, max: 1024}]}
          >
            <TextArea rows={5} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={Boolean(respondingIntervention)
          && canAgentAction('respondIntervention')}
        title={t('ai.agents.intervention.responseTitle', '提交人工介入结果')}
        width={680}
        confirmLoading={saving}
        onOk={() => void respondHumanIntervention()}
        onCancel={() => setRespondingIntervention(undefined)}
        destroyOnHidden={false}
      >
        {respondingIntervention && (
          <Alert
            showIcon
            type="info"
            closable
            message={respondingIntervention.intervention.prompt}
            description={`${t(
              'ai.agents.intervention.expiresAt',
              '截止时间',
            )}: ${new Date(
              respondingIntervention.intervention.expiresAt,
            ).toLocaleString(locale)}`}
            style={{marginBottom: 16}}
          />
        )}
        <Form form={interventionResponseForm} layout="vertical">
          <Form.Item
            name="outcome"
            label={t('ai.agents.intervention.outcome', '处理结果')}
            rules={[{required: true}]}
          >
            <Select
              options={[
                {
                  value: 'CONTINUE',
                  label: t('ai.agents.intervention.continue', '提交输入并继续'),
                },
                {
                  value: 'CANCEL',
                  label: t('ai.agents.intervention.cancel', '取消执行'),
                },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="input"
            label={t('ai.agents.intervention.input', '结构化人工输入')}
            rules={[{required: true}]}
          >
            <TextArea rows={8} style={{fontFamily: 'monospace'}} />
          </Form.Item>
          <Form.Item
            name="comment"
            label={t('ai.agents.intervention.comment', '处理说明')}
            rules={[{max: 1024}]}
          >
            <TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        open={versionDialogOpen && canAgentAction('createVersion')}
        title={t('ai.agents.version.create', '创建不可变 Agent 版本')}
        width="min(920px, 96vw)"
        onClose={() => closeVersionCreate()}
        extra={(
          <Button onClick={() => closeVersionCreate()}>
            {t('ai.agents.action.close', '关闭')}
          </Button>
        )}
      >
        <Alert
          showIcon
          type="warning"
          message={t(
            'ai.agents.version.warning',
            '版本创建后内容不可修改；发布前会重新校验模型和 Skill。',
          )}
          style={{marginBottom: 16}}
        />
        <Form
          form={versionForm}
          layout="vertical"
          disabled={dependencyValidating}
          onValuesChange={(changedValues) => {
            if (Object.keys(changedValues).some((key) => (
              key === 'primaryModelId'
              || key === 'fallbackModelIds'
              || key === 'skillBindings'
            ))) {
              dependencyValidationRequestSequence.current += 1;
            }
          }}
        >
          <Tabs
            activeKey={versionStep}
            onChange={(key) => void navigateVersionStep(key as VersionStep)}
            items={[
              {key: 'basics', label: t('ai.agents.version.step.basics', '基本信息')},
              {key: 'capabilities', label: t('ai.agents.version.step.capabilities', '模型与 Skill')},
              {key: 'governance', label: t('ai.agents.version.step.governance', '记忆与治理')},
              {key: 'schema', label: t('ai.agents.version.step.schema', '输入与输出')},
              {key: 'review', label: t('ai.agents.version.step.review', '确认创建')},
            ]}
          />
          {versionStep === 'basics' && (
            <>
          <Form.Item
            name="version"
            label={t('ai.agents.field.version', '语义版本')}
            rules={[{
              required: true,
              pattern: /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/,
            }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="systemPrompt"
            label={t('ai.agents.field.systemPrompt', 'System Prompt')}
            rules={[{required: true, max: 32768}]}
          >
            <TextArea autoSize={{minRows: 5, maxRows: 12}} />
          </Form.Item>
            </>
          )}
          {versionStep === 'capabilities' && (
            <>
          <Form.Item
            name="primaryModelId"
            label={t('ai.agents.field.primaryModel', '主模型')}
            rules={[{required: true}]}
          >
            <DependencyOptionSelect
              directory={dependencyDirectory}
              kind="MODEL"
              allowClear
              style={{width: '100%'}}
              placeholder={t(
                'ai.dependencies.search.modelPlaceholder',
                '搜索并选择可用模型',
              )}
            />
          </Form.Item>
          <Form.Item
            name="fallbackModelIds"
            label={t('ai.agents.field.fallbackModels', '回退模型')}
            dependencies={['primaryModelId']}
            rules={[({getFieldValue}) => ({
              validator(_, value?: string[]) {
                return value?.includes(getFieldValue('primaryModelId'))
                  ? Promise.reject(new Error(t(
                    'ai.agents.validation.fallbackPrimary',
                    '回退模型不能包含主模型',
                  )))
                  : Promise.resolve();
              },
            })]}
          >
            <DependencyOptionSelect
              directory={dependencyDirectory}
              kind="MODEL"
              mode="multiple"
              maxCount={8}
              excludeValues={selectedPrimaryModelId
                ? [selectedPrimaryModelId]
                : []}
              style={{width: '100%'}}
              placeholder={t(
                'ai.dependencies.search.fallbackModelPlaceholder',
                '搜索并选择回退模型（最多 8 个）',
              )}
            />
          </Form.Item>
          <Text strong>{t('ai.agents.field.skills', '固定 Skill 版本')}</Text>
          <Form.List
            name="skillBindings"
            rules={[{
              validator: async (_, bindings?: SkillBindingFormValue[]) => {
                const aliases = (bindings ?? []).map((binding) => binding?.alias).filter(Boolean);
                const versions = (bindings ?? []).map((binding) => binding?.versionId).filter(Boolean);
                if (new Set(aliases).size !== aliases.length) {
                  throw new Error(t(
                    'ai.agents.validation.duplicateSkillAlias',
                    'Skill 别名不能重复',
                  ));
                }
                if (new Set(versions).size !== versions.length) {
                  throw new Error(t(
                    'ai.agents.validation.duplicateSkillVersion',
                    '同一个 Skill 版本不能重复绑定',
                  ));
                }
              },
            }]}
          >
            {(fields, {add, remove}, {errors}) => (
              <Space direction="vertical" style={{width: '100%', marginTop: 12}}>
                {fields.map((field) => (
                  <Space key={field.key} align="baseline" style={{width: '100%'}}>
                    <Form.Item
                      {...field}
                      name={[field.name, 'alias']}
                      rules={[{
                        required: true,
                        pattern: /^[a-z0-9][a-z0-9_.-]{0,63}$/,
                      }]}
                    >
                      <Input
                        placeholder={t('ai.agents.field.skillAlias', 'Skill 别名')}
                      />
                    </Form.Item>
                    <Form.Item
                      {...field}
                      name={[field.name, 'versionId']}
                      rules={[{required: true}]}
                      style={{minWidth: 360}}
                    >
                      <DependencyOptionSelect
                        directory={dependencyDirectory}
                        kind="SKILL"
                        allowClear
                        style={{width: '100%'}}
                        placeholder={t(
                          'ai.agents.field.skillVersion',
                          '选择已发布 Skill 版本',
                        )}
                      />
                    </Form.Item>
                    <Button danger onClick={() => remove(field.name)}>
                      {t('ai.agents.action.removeBinding', '移除')}
                    </Button>
                  </Space>
                ))}
                <Button
                  type="dashed"
                  disabled={fields.length >= 32}
                  onClick={() => add({alias: '', versionId: undefined})}
                >
                  {t('ai.agents.action.addBinding', '添加 Skill 绑定')}
                </Button>
                <Form.ErrorList errors={errors} />
              </Space>
            )}
          </Form.List>
            </>
          )}
          {versionStep === 'governance' && (
            <>
          <Descriptions
            bordered
            size="small"
            column={2}
            style={{marginTop: 20, marginBottom: 20}}
          >
            <Descriptions.Item label={t('ai.agents.field.shortMemory', '短期记忆')}>
              <Form.Item name="shortTermEnabled" valuePropName="checked" noStyle>
                <Switch />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.agents.field.longMemory', '长期记忆')}>
              <Form.Item name="longTermEnabled" valuePropName="checked" noStyle>
                <Switch />
              </Form.Item>
              <Text type="secondary" style={{marginLeft: 8}}>
                {t(
                  'ai.agents.field.longMemorySubjectHint',
                  '仅在同一 Agent、作用域和当前主体之间复用',
                )}
              </Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.agents.field.maximumMessages', '最大消息数')}>
              <Form.Item
                name="maximumMessages"
                noStyle
                dependencies={['maximumConcurrency']}
                rules={[
                  {required: true},
                  ({getFieldValue}) => ({
                    validator(_, value?: number) {
                      const maximumConcurrency =
                        Number(getFieldValue('maximumConcurrency')) || 1;
                      if (value === undefined || value >= maximumConcurrency + 2) {
                        return Promise.resolve();
                      }
                      return Promise.reject(new Error(t(
                        'ai.agents.validation.maximumMessages',
                        '最大消息数至少应为最大并发数 + 2',
                      )));
                    },
                  }),
                ]}
              >
                <InputNumber min={1} max={1000} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t(
              'ai.agents.field.maximumSummaryCharacters',
              '摘要字符上限',
            )}>
              <Form.Item
                name="maximumSummaryCharacters"
                noStyle
                rules={[{required: true}]}
              >
                <InputNumber min={1024} max={32768} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t(
              'ai.agents.field.longMemoryScope',
              '长期记忆边界',
            )}>
              <Form.Item name="longTermScope" noStyle rules={[{required: true}]}>
                <Select
                  style={{width: 160}}
                  options={[{
                    value: 'SUBJECT',
                    label: t(
                      'ai.agents.field.longMemoryScopeSubject',
                      '当前主体',
                    ),
                  }]}
                />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t(
              'ai.agents.field.maximumLongTermEntries',
              '保留记忆数',
            )}>
              <Form.Item
                name="maximumLongTermEntries"
                noStyle
                rules={[{required: true}]}
              >
                <InputNumber min={1} max={10000} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t(
              'ai.agents.field.longTermRetrievalTopK',
              '检索 Top K',
            )}>
              <Form.Item
                name="longTermRetrievalTopK"
                noStyle
                rules={[{required: true}]}
              >
                <InputNumber min={1} max={20} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t(
              'ai.agents.field.longTermScoreThreshold',
              '最低相关度',
            )}>
              <Form.Item
                name="longTermScoreThreshold"
                noStyle
                rules={[{required: true}]}
              >
                <InputNumber min={0} max={1} step={0.01} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t(
              'ai.agents.field.maximumLongTermInjectionCharacters',
              '注入字符上限',
            )}>
              <Form.Item
                name="maximumLongTermInjectionCharacters"
                noStyle
                rules={[{required: true}]}
              >
                <InputNumber min={512} max={32768} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t(
              'ai.agents.field.maximumLongTermRecordCharacters',
              '单条记忆字符上限',
            )}>
              <Form.Item
                name="maximumLongTermRecordCharacters"
                noStyle
                rules={[{required: true}]}
              >
                <InputNumber min={512} max={32768} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t(
              'ai.agents.field.longTermRetentionDays',
              '保留天数',
            )}>
              <Form.Item
                name="longTermRetentionDays"
                noStyle
                rules={[{required: true}]}
              >
                <InputNumber min={1} max={3650} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.agents.field.maximumSteps', '最大步骤')}>
              <Form.Item name="maximumSteps" noStyle rules={[{required: true}]}>
                <InputNumber min={1} max={256} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.agents.field.maximumLoopDepth', '最大循环深度')}>
              <Form.Item name="maximumLoopDepth" noStyle rules={[{required: true}]}>
                <InputNumber min={0} max={32} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.agents.field.maximumConcurrency', '最大并发')}>
              <Form.Item name="maximumConcurrency" noStyle rules={[{required: true}]}>
                <InputNumber min={1} max={32} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.agents.field.maximumInputTokens', '输入 Token')}>
              <Form.Item name="maximumInputTokens" noStyle rules={[{required: true}]}>
                <InputNumber min={1} max={10000000} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.agents.field.maximumOutputTokens', '输出 Token')}>
              <Form.Item name="maximumOutputTokens" noStyle rules={[{required: true}]}>
                <InputNumber min={1} max={1000000} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.agents.field.maximumCost', '最大费用')}>
              <Form.Item name="maximumCost" noStyle rules={[{required: true}]}>
                <InputNumber min={0} max={1000000} precision={4} />
              </Form.Item>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.agents.field.publicAccess', '允许对外发布')}>
              <Form.Item name="publicAccess" valuePropName="checked" noStyle>
                <Switch />
              </Form.Item>
            </Descriptions.Item>
          </Descriptions>
          <Space size="large" style={{marginBottom: 16}}>
            <Form.Item
              name="approvalRequired"
              label={t('ai.agents.field.approvalRequired', '执行需要审批')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              name="allowSelfApproval"
              label={t('ai.agents.field.allowSelfApproval', '允许自审')}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
          </Space>
          <Form.Item
            name="approvalInstructions"
            label={t('ai.agents.field.approvalInstructions', '审批说明')}
            rules={[{max: 512}]}
          >
            <Input />
          </Form.Item>
          <Space size="large" wrap style={{marginBottom: 16}}>
            <Form.Item
              name="humanInterventionEnabled"
              label={t(
                'ai.agents.field.humanInterventionEnabled',
                '允许人工介入',
              )}
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              name="maximumHumanInterventions"
              label={t(
                'ai.agents.field.maximumHumanInterventions',
                '最大介入次数',
              )}
              rules={[{required: true}]}
            >
              <InputNumber min={1} max={32} />
            </Form.Item>
            <Form.Item
              name="humanInterventionTimeoutSeconds"
              label={t(
                'ai.agents.field.humanInterventionTimeoutSeconds',
                '人工等待超时（秒）',
              )}
              rules={[{required: true}]}
            >
              <InputNumber min={60} max={604800} />
            </Form.Item>
            <Form.Item
              name="humanInterventionTimeoutAction"
              label={t(
                'ai.agents.field.humanInterventionTimeoutAction',
                '超时动作',
              )}
              rules={[{required: true}]}
            >
              <Select
                style={{width: 140}}
                options={[
                  {
                    value: 'FAIL',
                    label: t(
                      'ai.agents.field.timeoutActionFail',
                      '执行失败',
                    ),
                  },
                  {
                    value: 'CANCEL',
                    label: t(
                      'ai.agents.field.timeoutActionCancel',
                      '取消执行',
                    ),
                  },
                ]}
              />
            </Form.Item>
          </Space>
            </>
          )}
          {versionStep === 'schema' && (
            <>
          <Form.Item
            name="workflowRef"
            label={t('ai.agents.field.workflowRef', 'Workflow 引用')}
            rules={[{pattern: /^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$/}]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="inputSchema"
            label={t('ai.agents.field.inputSchema', '输入 Schema')}
            rules={[{required: true}]}
          >
            <JsonSchemaObjectEditor />
          </Form.Item>
          <Form.Item
            name="outputSchema"
            label={t('ai.agents.field.outputSchema', '输出 Schema')}
            rules={[{required: true}]}
          >
            <JsonSchemaObjectEditor />
          </Form.Item>
            </>
          )}
          {versionStep === 'review' && versionDraft && (
            <Space direction="vertical" size={16} style={{width: '100%'}}>
              <Alert
                showIcon
                type="info"
                message={t(
                  'ai.agents.version.review.notice',
                  '请确认依赖和治理边界。创建后内容不可修改，仍需单独发布才会生效。',
                )}
              />
              <Descriptions bordered size="small" column={{xs: 1, md: 2}}>
                <Descriptions.Item label={t('ai.agents.field.version', '语义版本')}>
                  {versionDraft.version}
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.field.primaryModel', '主模型')}>
                  {dependencyDirectory.getOption(
                    'MODEL',
                    versionDraft.primaryModelId,
                  )?.resourceName
                    ?? dependencyDirectory.getOption(
                      'MODEL',
                      versionDraft.primaryModelId,
                    )?.resourceCode
                    ?? versionDraft.primaryModelId}
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.field.fallbackModels', '回退模型')}>
                  {versionDraft.fallbackModelIds?.length ?? 0}
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.field.skills', '固定 Skill 版本')}>
                  {versionDraft.skillBindings?.length ?? 0}
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.field.maximumSteps', '最大步骤')}>
                  {versionDraft.maximumSteps}
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.field.maximumCost', '最大费用')}>
                  {versionDraft.maximumCost}
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.field.approvalRequired', '执行需要审批')}>
                  {versionDraft.approvalRequired
                    ? t('ai.common.yes', '是') : t('ai.common.no', '否')}
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.field.humanInterventionEnabled', '允许人工介入')}>
                  {versionDraft.humanInterventionEnabled
                    ? t('ai.common.yes', '是') : t('ai.common.no', '否')}
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.field.shortMemory', '短期记忆')}>
                  {versionDraft.shortTermEnabled
                    ? t('ai.common.enabled', '已启用') : t('ai.common.disabled', '未启用')}
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.field.longMemory', '长期记忆')}>
                  {versionDraft.longTermEnabled
                    ? t('ai.common.enabled', '已启用') : t('ai.common.disabled', '未启用')}
                </Descriptions.Item>
              </Descriptions>
              <div>
                <Text strong>{t('ai.agents.field.systemPrompt', 'System Prompt')}</Text>
                <Paragraph
                  style={{marginTop: 8, whiteSpace: 'pre-wrap'}}
                  ellipsis={{rows: 6, expandable: true}}
                >
                  {versionDraft.systemPrompt}
                </Paragraph>
              </div>
            </Space>
          )}
          <Space style={{width: '100%', justifyContent: 'space-between', marginTop: 24}}>
            <Button
              disabled={versionStep === versionStepOrder[0]}
              onClick={() => {
                const index = versionStepOrder.indexOf(versionStep);
                void navigateVersionStep(versionStepOrder[index - 1]);
              }}
            >
              {t('ai.agents.version.previous', '上一步')}
            </Button>
            {versionStep === 'review' ? (
              <Button
                type="primary"
                loading={saving || dependencyValidating}
                onClick={() => void saveVersion()}
              >
                {t('ai.agents.action.createVersion', '创建版本')}
              </Button>
            ) : (
              <Button
                type="primary"
                loading={dependencyValidating}
                onClick={() => {
                  const index = versionStepOrder.indexOf(versionStep);
                  void navigateVersionStep(versionStepOrder[index + 1]);
                }}
              >
                {t('ai.agents.version.next', '下一步')}
              </Button>
            )}
          </Space>
        </Form>
      </Drawer>

      <Drawer
        open={Boolean(inspectingVersion)}
        title={t('ai.agents.version.details', 'Agent 版本详情')}
        width={760}
        onClose={() => setInspectingVersion(undefined)}
      >
        {inspectingVersion && (
          <Space direction="vertical" size="large" style={{width: '100%'}}>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label={t('ai.agents.field.version', '语义版本')}>
                {inspectingVersion.version}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.column.status', '状态')}>
                <Tag color={versionStatusColor(inspectingVersion.status)}>
                  {versionStatusLabels[inspectingVersion.status]}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.column.model', '主模型')}>
                {inspectingVersion.primaryModelId}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.column.contentHash', '内容 Hash')}>
                <Text copyable code>{inspectingVersion.contentHash}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.field.publicAccess', '允许对外发布')}>
                {String(inspectingVersion.publicAccess)}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.field.workflowRef', 'Workflow 引用')}>
                {inspectingVersion.workflowReference || '-'}
              </Descriptions.Item>
            </Descriptions>
            <div>
              <Text strong>{t('ai.agents.details.skillBindings', '固定 Skill 版本')}</Text>
              <div style={{marginTop: 8}}>
                {(inspectingVersion.skillBindings ?? []).length === 0
                  ? <Text type="secondary">-</Text>
                  : inspectingVersion.skillBindings?.map((binding) => (
                    <Tag key={binding.id} color="blue">
                      {binding.skillAlias} → {binding.skillCode}:{binding.skillVersionName}
                    </Tag>
                  ))}
              </div>
            </div>
            <div>
              <Text strong>{t('ai.agents.version.manifest', '版本清单')}</Text>
              <Paragraph>
                <pre style={{
                  maxHeight: 520,
                  overflow: 'auto',
                  padding: 12,
                  background: 'var(--ant-color-fill-quaternary)',
                  borderRadius: 8,
                }}>
                  {JSON.stringify(inspectingVersion.manifest, null, 2)}
                </pre>
              </Paragraph>
            </div>
          </Space>
        )}
      </Drawer>

      <Drawer
        open={Boolean(inspectingExecution)}
        title={t('ai.agents.execution.details', 'Agent 执行详情')}
        width={1100}
        onClose={() => {
          setInspectingExecution(undefined);
          setExecutionEvents([]);
          setEventCursor(-1);
          setEventHasMore(false);
          setExecutionTraces([]);
          setTracePage(0);
          setTraceTotal(0);
        }}
        extra={selectedAgent && inspectingExecution ? (
            <Button
              loading={observabilityLoading}
              onClick={() => void refreshExecutionDetails()}
            >
              {t('ai.agents.action.refreshExecutions', '刷新执行')}
            </Button>
          ) : undefined}
      >
        {inspectingExecution && (
          <Space direction="vertical" size="large" style={{width: '100%'}}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('ai.agents.column.status', '状态')}>
                <Tag color={executionStatusColor(inspectingExecution.status)}>
                  {executionStatusLabels[inspectingExecution.status]}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.execution.id', '执行 ID')}>
                <Text copyable code>{inspectingExecution.id}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.execution.steps', '步骤')}>
                {inspectingExecution.stepCount}/{inspectingExecution.maximumSteps}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.execution.loopDepth', '循环深度')}>
                {inspectingExecution.loopDepth}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.execution.inputTokens', '输入 Token')}>
                {inspectingExecution.consumedInputTokens}
                /{inspectingExecution.maximumInputTokens}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.execution.outputTokens', '输出 Token')}>
                {inspectingExecution.consumedOutputTokens}
                /{inspectingExecution.maximumOutputTokens}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.execution.cost', '费用')}>
                {inspectingExecution.consumedCost}
                /{inspectingExecution.maximumCost}
              </Descriptions.Item>
              <Descriptions.Item label={t(
                'ai.agents.execution.memoryMessages',
                '短期记忆',
              )}>
                {inspectingExecution.shortTermMemoryEnabled
                  ? `${inspectingExecution.compactedMessageCount} / ${inspectingExecution.maximumMemoryMessages}`
                  : t('ai.agents.execution.memoryDisabled', '未启用')}
              </Descriptions.Item>
              <Descriptions.Item label={t(
                'ai.agents.execution.memoryRevision',
                '记忆修订',
              )}>
                {inspectingExecution.memoryRevision ?? 0}
              </Descriptions.Item>
              <Descriptions.Item label={t(
                'ai.agents.execution.longMemoryRetrieved',
                '长期记忆检索',
              )}>
                {inspectingExecution.longTermMemoryEnabled
                  ? `${inspectingExecution.longTermMemoryRetrievedCount ?? 0} / ${
                    inspectingExecution.longTermMemoryInjectedCharacters ?? 0
                  } ${t('ai.agents.execution.characters', '字符')}`
                  : t('ai.agents.execution.memoryDisabled', '未启用')}
              </Descriptions.Item>
              <Descriptions.Item label={t(
                'ai.agents.execution.longMemoryWritten',
                '写入长期记忆',
              )}>
                {inspectingExecution.longTermMemoryWrittenId
                  ? (
                      <Text copyable code>
                        {inspectingExecution.longTermMemoryWrittenId}
                      </Text>
                    )
                  : '-'}
              </Descriptions.Item>
              {inspectingExecution.longTermMemorySnapshotHash && (
                <Descriptions.Item
                  label={t(
                    'ai.agents.execution.longMemorySnapshot',
                    '长期记忆快照 Hash',
                  )}
                  span={2}
                >
                  <Text copyable code>
                    {inspectingExecution.longTermMemorySnapshotHash}
                  </Text>
                </Descriptions.Item>
              )}
              <Descriptions.Item label={t(
                'ai.agents.execution.humanIntervention',
                '人工介入',
              )}>
                {inspectingExecution.humanInterventionEnabled
                  ? `${inspectingExecution.humanInterventionCount}/${
                    inspectingExecution.maximumHumanInterventions
                  } · ${inspectingExecution.humanInterventionTimeoutSeconds}s`
                  : t('ai.agents.execution.interventionDisabled', '未启用')}
              </Descriptions.Item>
              <Descriptions.Item label={t(
                'ai.agents.execution.interventionTimeoutAction',
                '人工等待超时动作',
              )}>
                {inspectingExecution.humanInterventionEnabled
                  ? inspectingExecution.humanInterventionTimeoutAction === 'FAIL'
                    ? t('ai.agents.field.timeoutActionFail', '执行失败')
                    : t('ai.agents.field.timeoutActionCancel', '取消执行')
                  : '-'}
              </Descriptions.Item>
              {inspectingExecution.currentHumanInterventionId && (
                <Descriptions.Item
                  label={t(
                    'ai.agents.execution.currentIntervention',
                    '当前人工介入任务',
                  )}
                  span={2}
                >
                  <Text copyable code>
                    {inspectingExecution.currentHumanInterventionId}
                  </Text>
                </Descriptions.Item>
              )}
              <Descriptions.Item label={t('ai.agents.field.primaryModel', '主模型')}>
                {inspectingExecution.currentModelId
                  || inspectingExecution.primaryModelId}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.agents.execution.skill', '当前 Skill 执行')} span={2}>
                {inspectingExecution.currentSkillExecutionId
                  ? (
                      <Text copyable code>
                        {inspectingExecution.currentSkillExecutionId}
                      </Text>
                    )
                  : '-'}
              </Descriptions.Item>
              {inspectingExecution.pauseReason && (
                <Descriptions.Item
                  label={t('ai.agents.execution.pauseReason', '暂停原因')}
                  span={2}
                >
                  {inspectingExecution.pauseReason}
                </Descriptions.Item>
              )}
              {shouldDisplayExecutionDiagnostic(
                'agentExecution',
                inspectingExecution.status,
                inspectingExecution,
              ) && (
                <Descriptions.Item label={t('ai.agents.execution.error', '错误')} span={2}>
                  {renderDiagnostic('agentExecution', inspectingExecution)}
                </Descriptions.Item>
              )}
            </Descriptions>

            <div>
              <Text strong>{t('ai.agents.execution.input', '执行输入')}</Text>
              <pre style={{
                maxHeight: 260,
                overflow: 'auto',
                padding: 12,
                background: 'var(--ant-color-fill-quaternary)',
                borderRadius: 8,
              }}>
                {JSON.stringify(inspectingExecution.input, null, 2)}
              </pre>
            </div>

            {inspectingExecution.output !== undefined
              && canDisplayExecutionOutput(inspectingExecution.status) && (
              <div>
                <Text strong>{t('ai.agents.execution.output', '执行输出')}</Text>
                <pre style={{
                  maxHeight: 320,
                  overflow: 'auto',
                  padding: 12,
                  background: 'var(--ant-color-fill-quaternary)',
                  borderRadius: 8,
                }}>
                  {JSON.stringify(inspectingExecution.output, null, 2)}
                </pre>
              </div>
            )}

            <div>
              <Text strong>
                {t('ai.agents.execution.events', '执行事件')}
              </Text>
              {executionEvents.length === 0 ? (
                <div style={{marginTop: 12}}>
                  <Text type="secondary">
                    {t('ai.agents.execution.eventsEmpty', '暂无执行事件')}
                  </Text>
                </div>
              ) : (
                <Timeline
                  style={{marginTop: 16}}
                  items={executionEvents.map((event) => ({
                    color: eventColor(event),
                    children: (
                      <Space direction="vertical" size={2}>
                        <Space wrap>
                          <Text strong>
                            #{event.sequence}{' '}
                            {eventTypeLabels[event.type]
                              ?? t('ai.agents.event.unknown', '未知事件')}
                          </Text>
                          <Tag color={executionStatusColor(event.executionStatus)}>
                            {executionStatusLabels[event.executionStatus]}
                          </Tag>
                          <Text type="secondary">
                            {new Date(event.occurredAt).toLocaleString(locale)}
                          </Text>
                        </Space>
                        {event.actorId && (
                          <Text type="secondary">
                            {t('ai.agents.execution.eventActor', '操作者')}: {' '}
                            {event.actorId}
                          </Text>
                        )}
                        {event.payload && Object.keys(event.payload).length > 0 && (
                          <details>
                            <summary>
                              {t('ai.agents.execution.eventDetails', '查看事件数据')}
                            </summary>
                            <pre style={{
                              maxWidth: 760,
                              overflow: 'auto',
                              margin: '4px 0 0',
                              padding: 8,
                              background: 'var(--ant-color-fill-quaternary)',
                              borderRadius: 6,
                            }}>
                              {JSON.stringify(event.payload, null, 2)}
                            </pre>
                          </details>
                        )}
                      </Space>
                    ),
                  }))}
                />
              )}
              {eventHasMore && selectedAgent && (
                <Button
                  size="small"
                  loading={observabilityLoading}
                  onClick={() => void loadEvents(
                    selectedAgent,
                    inspectingExecution.id,
                    eventCursor,
                    true,
                  )}
                >
                  {t('ai.agents.execution.loadMoreEvents', '加载更多事件')}
                </Button>
              )}
            </div>

            {(inspectingExecution.humanInterventions ?? []).length > 0 && (
              <div>
                <Text strong>
                  {t('ai.agents.intervention.history', '人工介入记录')}
                </Text>
                <DataTable<AgentHumanIntervention>
                  rowKey="id"
                  size="small"
                  style={{marginTop: 8}}
                  dataSource={inspectingExecution.humanInterventions ?? []}
                  pagination={false}
                  columns={[
                    {
                      title: t('ai.agents.column.status', '状态'),
                      dataIndex: 'status',
                      width: 110,
                      render: (value: AgentHumanIntervention['status']) => (
                        <Tag>{interventionStatusLabels[value]}</Tag>
                      ),
                    },
                    {
                      title: t('ai.agents.intervention.prompt', '需要人工确认的问题'),
                      dataIndex: 'prompt',
                      ellipsis: true,
                    },
                    {
                      title: t('ai.agents.intervention.requestedBy', '请求人'),
                      dataIndex: 'requestedBy',
                      width: 180,
                      ellipsis: true,
                    },
                    {
                      title: t('ai.agents.intervention.outcome', '处理结果'),
                      dataIndex: 'outcome',
                      width: 100,
                      render: (value) => value || '-',
                    },
                    {
                      title: t('ai.agents.intervention.expiresAt', '截止时间'),
                      dataIndex: 'expiresAt',
                      width: 190,
                      render: (value) =>
                        new Date(value).toLocaleString(locale),
                    },
                  ]}
                  expandable={{
                    expandedRowRender: (intervention) => (
                      <Space direction="vertical" style={{width: '100%'}}>
                        <Text type="secondary">
                          {t('ai.agents.intervention.input', '结构化人工输入')}
                        </Text>
                        <pre>
                          {JSON.stringify(intervention.response, null, 2)}
                        </pre>
                        {intervention.responseComment && (
                          <Text>{intervention.responseComment}</Text>
                        )}
                        {intervention.completionReason && (
                          <Text type="secondary">
                            {intervention.completionReason}
                          </Text>
                        )}
                      </Space>
                    ),
                  }}
                />
              </div>
            )}

            <div>
              <Space wrap>
                <Text strong>{t('ai.agents.execution.traces', '调用链路')}</Text>
                <Select
                  allowClear
                  value={traceType}
                  style={{width: 130}}
                  placeholder={t('ai.agents.execution.traceType', '类型')}
                  options={[
                    {value: 'MODEL', label: traceTypeLabels.MODEL},
                    {value: 'SKILL', label: traceTypeLabels.SKILL},
                  ]}
                  onChange={(value?: AgentExecutionTrace['type']) => {
                    setTraceType(value);
                    setTracePage(0);
                    if (selectedAgent) {
                      void loadTraces(
                        selectedAgent,
                        inspectingExecution.id,
                        0,
                        value,
                        traceStatus,
                      );
                    }
                  }}
                />
                <Select
                  allowClear
                  value={traceStatus}
                  style={{width: 140}}
                  placeholder={t('ai.agents.column.status', '状态')}
                  options={[
                    {value: 'RUNNING', label: traceStatusLabels.RUNNING},
                    {value: 'SUCCEEDED', label: traceStatusLabels.SUCCEEDED},
                    {value: 'FAILED', label: traceStatusLabels.FAILED},
                    {value: 'CANCELLED', label: traceStatusLabels.CANCELLED},
                  ]}
                  onChange={(value?: AgentExecutionTrace['status']) => {
                    setTraceStatus(value);
                    setTracePage(0);
                    if (selectedAgent) {
                      void loadTraces(
                        selectedAgent,
                        inspectingExecution.id,
                        0,
                        traceType,
                        value,
                      );
                    }
                  }}
                />
              </Space>
              <DataTable<AgentExecutionTrace>
                rowKey="id"
                size="small"
                style={{marginTop: 8}}
                loading={observabilityLoading}
                dataSource={executionTraces}
                pagination={{
                  current: tracePage + 1,
                  pageSize: 10,
                  total: traceTotal,
                  hideOnSinglePage: true,
                  showSizeChanger: false,
                  onChange: (page) => {
                    if (selectedAgent) {
                      void loadTraces(
                        selectedAgent,
                        inspectingExecution.id,
                        page - 1,
                        traceType,
                        traceStatus,
                      );
                    }
                  },
                }}
                columns={[
                  {
                    title: '#',
                    dataIndex: 'sequence',
                    width: 60,
                  },
                  {
                    title: t('ai.agents.execution.traceType', '类型'),
                    dataIndex: 'type',
                    width: 90,
                    render: (value) => (
                      <Tag color={value === 'MODEL' ? 'purple' : 'blue'}>
                        {traceTypeLabels[value as AgentExecutionTrace['type']]}
                      </Tag>
                    ),
                  },
                  {
                    title: t('ai.agents.column.status', '状态'),
                    dataIndex: 'status',
                    width: 110,
                    render: (value: AgentExecutionTrace['status']) => (
                      <Tag color={executionStatusColor(value)}>
                        {traceStatusLabels[value]}
                      </Tag>
                    ),
                  },
                  {
                    title: t('ai.agents.execution.target', '目标'),
                    ellipsis: true,
                    render: (_, trace) => trace.type === 'MODEL'
                      ? trace.modelDefinitionId ?? '-'
                      : [trace.capabilityAlias, trace.skillExecutionId]
                          .filter(Boolean)
                          .join(' · ') || '-',
                  },
                  {
                    title: t('ai.agents.execution.tokens', 'Token'),
                    width: 130,
                    render: (_, trace) => trace.type === 'MODEL'
                      ? `${trace.inputTokens ?? 0}/${trace.outputTokens ?? 0}`
                      : '-',
                  },
                  {
                    title: t('ai.agents.execution.cost', '费用'),
                    dataIndex: 'cost',
                    width: 100,
                    render: (value) => value ?? '-',
                  },
                ]}
                expandable={{
                  expandedRowRender: (trace) => (
                    <Space direction="vertical" style={{width: '100%'}}>
                      <Space size={4} wrap>
                        <Text type="secondary">
                          {t('ai.agents.execution.traceId', '调用链路 ID')}:
                        </Text>
                        <Text copyable code>{trace.id}</Text>
                      </Space>
                      <Text type="secondary">
                        {t('ai.agents.execution.input', '执行输入')}
                      </Text>
                      <pre>{JSON.stringify(trace.input, null, 2)}</pre>
                      {canDisplayExecutionOutput(trace.status) && (
                        <>
                          <Text type="secondary">
                            {t('ai.agents.execution.output', '执行输出')}
                          </Text>
                          <pre>{JSON.stringify(trace.output, null, 2)}</pre>
                        </>
                      )}
                      {shouldDisplayExecutionDiagnostic(
                        'agentTrace',
                        trace.status,
                        trace,
                      ) && (
                        renderDiagnostic('agentTrace', trace)
                      )}
                    </Space>
                  ),
                }}
              />
            </div>
          </Space>
        )}
      </Drawer>

      <Drawer
        open={memoryDrawerOpen}
        title={selectedAgent
          ? `${selectedAgent.name} · ${t(
            'ai.agents.memory.title',
            '当前主体长期记忆',
          )}`
          : t('ai.agents.memory.title', '当前主体长期记忆')}
        width={860}
        onClose={() => setMemoryDrawerOpen(false)}
        extra={selectedAgent ? (
          <Button onClick={() => void loadMemories(selectedAgent)}>
            {t('ai.agents.action.refresh', '刷新')}
          </Button>
        ) : undefined}
      >
        <Alert
          showIcon
          type="info"
          closable
          message={t(
            'ai.agents.memory.notice',
            '这里只显示当前登录主体在当前平台或租户作用域内产生的记忆。',
          )}
          description={t(
            'ai.agents.memory.untrusted',
            '记忆会作为不可信历史数据注入，不能覆盖 System Prompt 或当前请求。',
          )}
          style={{marginBottom: 16}}
        />
        <DataTable<AgentMemory>
          rowKey="id"
          loading={memoryLoading}
          dataSource={memories}
          pagination={{pageSize: 10, hideOnSinglePage: true}}
          locale={{emptyText: t('ai.agents.memory.empty', '暂无长期记忆')}}
          columns={[
            {
              title: t('ai.agents.memory.content', '记忆内容'),
              dataIndex: 'content',
              render: (value: string) => (
                <Paragraph
                  ellipsis={{
                    rows: 4,
                    expandable: true,
                    symbol: t('ai.common.more', '展开'),
                  }}
                  style={{whiteSpace: 'pre-wrap', marginBottom: 0}}
                >
                  {value}
                </Paragraph>
              ),
            },
            {
              title: t('ai.agents.memory.createdAt', '写入时间'),
              dataIndex: 'createdAt',
              width: 180,
              render: (value) => new Date(value).toLocaleString(locale),
            },
            {
              title: t('ai.agents.memory.expiresAt', '到期时间'),
              dataIndex: 'expiresAt',
              width: 180,
              render: (value) => new Date(value).toLocaleString(locale),
            },
            ...(canAgentAction('deleteMemory') ? [{
              title: t('ai.agents.column.action', '操作'),
              width: 90,
              render: (_: unknown, record: AgentMemory) => (
                <Button
                  danger
                  type="link"
                  onClick={() => removeMemory(record)}
                >
                  {t('ai.agents.action.delete', '删除')}
                </Button>
              ),
            }] : []),
          ]}
          expandable={{
            expandedRowRender: (memory) => (
              <Descriptions bordered size="small" column={1}>
                <Descriptions.Item label={t('ai.agents.memory.id', '记忆 ID')}>
                  <Text copyable code>{memory.id}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.memory.sourceExecution', '来源执行')}>
                  <Text copyable code>{memory.sourceExecutionId}</Text>
                </Descriptions.Item>
                <Descriptions.Item label={t('ai.agents.memory.contentHash', '内容 Hash')}>
                  <Text copyable code>{memory.contentHash}</Text>
                </Descriptions.Item>
              </Descriptions>
            ),
          }}
        />
      </Drawer>
    </div>
  );
};

export default Agents;
