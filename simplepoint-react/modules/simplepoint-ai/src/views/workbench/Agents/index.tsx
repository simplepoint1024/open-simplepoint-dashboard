import api from '@/api';
import {del, get, post, put} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Switch,
  Table,
  Tag,
  Timeline,
  Typography,
  message,
} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';

const {Paragraph, Text} = Typography;
const {TextArea} = Input;

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
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
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

type ModelOption = {
  id: string;
  modelId?: string;
  displayName?: string;
  modelType?: string;
  enabled?: boolean;
  available?: boolean;
};

type SkillOption = {
  id: string;
  code: string;
  name: string;
  enabled: boolean;
};

type SkillVersionOption = {
  id: string;
  version: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
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

type ExecutionFormValues = {
  idempotencyKey: string;
  input: string;
};

type HumanInterventionFormValues = {
  prompt: string;
};

type HumanInterventionResponseFormValues = {
  outcome: 'CONTINUE' | 'CANCEL';
  input: string;
  comment?: string;
};

const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return fallback;
};

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
  const config = api['ai-workbench.agents'];
  const {t, ensure, locale} = useI18n();
  const [form] = Form.useForm<AgentFormValues>();
  const [versionForm] = Form.useForm<VersionFormValues>();
  const [executionForm] = Form.useForm<ExecutionFormValues>();
  const [interventionForm] = Form.useForm<HumanInterventionFormValues>();
  const [interventionResponseForm] =
    Form.useForm<HumanInterventionResponseFormValues>();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [versions, setVersions] = useState<AgentVersion[]>([]);
  const [executions, setExecutions] = useState<AgentExecution[]>([]);
  const [memories, setMemories] = useState<AgentMemory[]>([]);
  const [models, setModels] = useState<ModelOption[]>([]);
  const [skillVersions, setSkillVersions] = useState<Map<string, {
    skill: SkillOption;
    version: SkillVersionOption;
  }>>(new Map());
  const [loading, setLoading] = useState(false);
  const [versionLoading, setVersionLoading] = useState(false);
  const [executionLoading, setExecutionLoading] = useState(false);
  const [memoryLoading, setMemoryLoading] = useState(false);
  const [observabilityLoading, setObservabilityLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<AgentDefinition>();
  const [agentDialogOpen, setAgentDialogOpen] = useState(false);
  const [selectedAgent, setSelectedAgent] = useState<AgentDefinition>();
  const [versionDialogOpen, setVersionDialogOpen] = useState(false);
  const [inspectingVersion, setInspectingVersion] = useState<AgentVersion>();
  const [executionDialogOpen, setExecutionDialogOpen] = useState(false);
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

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  const loadAgents = useCallback(async () => {
    setLoading(true);
    try {
      const page = await get<Page<AgentDefinition>>(
        config.baseUrl,
        {page: 0, size: 500, sort: 'createdAt,desc'},
      );
      setAgents(page.content ?? []);
      setSelectedAgent((current) => current
        ? (page.content ?? []).find((agent) => agent.id === current.id)
        : undefined);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.load', 'Agent 列表加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [config.baseUrl, t]);

  const loadVersions = useCallback(async (agent?: AgentDefinition) => {
    if (!agent) {
      setVersions([]);
      return;
    }
    setVersionLoading(true);
    try {
      const page = await get<Page<AgentVersion>>(
        `${config.baseUrl}/${agent.id}/versions`,
        {page: 0, size: 500, sort: 'createdAt,desc'},
      );
      setVersions(page.content ?? []);
    } catch (error) {
      setVersions([]);
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadVersions', 'Agent 版本加载失败'),
      ));
    } finally {
      setVersionLoading(false);
    }
  }, [config.baseUrl, t]);

  const loadExecutions = useCallback(async (agent?: AgentDefinition) => {
    if (!agent) {
      setExecutions([]);
      return;
    }
    setExecutionLoading(true);
    try {
      const page = await get<Page<AgentExecution>>(
        `${config.baseUrl}/${agent.id}/executions`,
        {page: 0, size: 100, sort: 'createdAt,desc'},
      );
      setExecutions(page.content ?? []);
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
      setExecutions([]);
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadExecutions', 'Agent 执行记录加载失败'),
      ));
    } finally {
      setExecutionLoading(false);
    }
  }, [config.baseUrl, t]);

  const loadMetrics = useCallback(async (
    agent?: AgentDefinition,
    days = metricsDays,
  ) => {
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
      setMetrics(value);
    } catch (error) {
      setMetrics(undefined);
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadMetrics', 'Agent 运行指标加载失败'),
      ));
    }
  }, [config.baseUrl, metricsDays, t]);

  const loadTraces = useCallback(async (
    agent: AgentDefinition,
    executionId: string,
    page = tracePage,
    type = traceType,
    status = traceStatus,
  ) => {
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
      setExecutionTraces(result.content ?? []);
      setTracePage(result.page.number ?? page);
      setTraceTotal(result.page.totalElements ?? 0);
    } catch (error) {
      setExecutionTraces([]);
      setTraceTotal(0);
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadTraces', 'Agent 调用链路加载失败'),
      ));
    }
  }, [config.baseUrl, t, tracePage, traceStatus, traceType]);

  const loadEvents = useCallback(async (
    agent: AgentDefinition,
    executionId: string,
    after = eventCursor,
    append = true,
  ) => {
    try {
      const feed = await get<AgentExecutionEventFeed>(
        `${config.baseUrl}/${agent.id}/executions/${executionId}/events`,
        {after, limit: 100},
      );
      setExecutionEvents((current) => {
        const source = append ? [...current, ...(feed.events ?? [])] : feed.events ?? [];
        return Array.from(
          new Map(source.map((event) => [event.sequence, event])).values(),
        ).sort((left, right) => left.sequence - right.sequence);
      });
      setEventCursor(feed.nextSequence);
      setEventHasMore(feed.hasMore);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadEvents', 'Agent 执行事件加载失败'),
      ));
    }
  }, [config.baseUrl, eventCursor, t]);

  const openExecutionDetails = useCallback(async (
    execution: AgentExecution,
  ) => {
    if (!selectedAgent) return;
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
      setInspectingExecution(details);
      setExecutionTraces(tracePageResult.content ?? []);
      setTracePage(tracePageResult.page.number ?? 0);
      setTraceTotal(tracePageResult.page.totalElements ?? 0);
      setExecutionEvents(eventFeed.events ?? []);
      setEventCursor(eventFeed.nextSequence);
      setEventHasMore(eventFeed.hasMore);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadExecutionDetails', 'Agent 执行详情加载失败'),
      ));
    } finally {
      setObservabilityLoading(false);
    }
  }, [config.baseUrl, selectedAgent, t]);

  const refreshExecutionDetails = useCallback(async () => {
    if (!selectedAgent || !inspectingExecution) return;
    const agent = selectedAgent;
    const executionId = inspectingExecution.id;
    setObservabilityLoading(true);
    try {
      const details = await get<AgentExecution>(
        `${config.baseUrl}/${agent.id}/executions/${executionId}`,
      );
      setInspectingExecution(details);
      await Promise.all([
        loadEvents(agent, executionId, eventCursor, true),
        loadTraces(agent, executionId, tracePage, traceType, traceStatus),
      ]);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadExecutionDetails', 'Agent 执行详情加载失败'),
      ));
    } finally {
      setObservabilityLoading(false);
    }
  }, [
    config.baseUrl,
    eventCursor,
    inspectingExecution,
    loadEvents,
    loadTraces,
    selectedAgent,
    t,
    tracePage,
    traceStatus,
    traceType,
  ]);

  const loadDependencies = useCallback(async () => {
    try {
      const [modelPage, skillPage] = await Promise.all([
        get<Page<ModelOption>>(
          config.modelsUrl,
          {page: 0, size: 500, sort: 'displayName,asc'},
        ),
        get<Page<SkillOption>>(
          config.skillsUrl,
          {page: 0, size: 500, sort: 'name,asc'},
        ),
      ]);
      const usableModels = (modelPage.content ?? []).filter((model) =>
        model.enabled !== false
        && model.available !== false
        && (model.modelType === 'LLM' || model.modelType === 'MULTIMODAL'));
      const usableSkills = (skillPage.content ?? []).filter((skill) =>
        skill.enabled !== false);
      setModels(usableModels);
      const versionPages = await Promise.all(usableSkills.map(async (skill) => ({
        skill,
        page: await get<Page<SkillVersionOption>>(
          `${config.skillsUrl}/${skill.id}/versions`,
          {page: 0, size: 500, sort: 'createdAt,desc'},
        ),
      })));
      const next = new Map<string, {
        skill: SkillOption;
        version: SkillVersionOption;
      }>();
      versionPages.forEach(({skill, page}) => {
        (page.content ?? [])
          .filter((version) => version.status === 'PUBLISHED')
          .forEach((version) => next.set(version.id, {skill, version}));
      });
      setSkillVersions(next);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadDependencies', '模型或 Skill 依赖加载失败'),
      ));
    }
  }, [config.modelsUrl, config.skillsUrl, t]);

  const loadMemories = useCallback(async (agent?: AgentDefinition) => {
    if (!agent) {
      setMemories([]);
      return;
    }
    setMemoryLoading(true);
    try {
      const items = await get<AgentMemory[]>(
        `${config.baseUrl}/${agent.id}/memories`,
      );
      setMemories(items ?? []);
    } catch (error) {
      setMemories([]);
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadMemories', 'Agent 长期记忆加载失败'),
      ));
    } finally {
      setMemoryLoading(false);
    }
  }, [config.baseUrl, t]);

  useEffect(() => {
    void loadAgents();
    void loadDependencies();
  }, [loadAgents, loadDependencies]);

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
      void loadExecutions(selectedAgent);
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [executions, loadExecutions, selectedAgent]);

  useEffect(() => {
    if (!inspectingExecution
      || terminalExecutionStatuses.has(inspectingExecution.status)) {
      return undefined;
    }
    const timer = window.setTimeout(() => {
      void refreshExecutionDetails();
    }, 2000);
    return () => window.clearTimeout(timer);
  }, [inspectingExecution, refreshExecutionDetails]);

  const openCreate = () => {
    setEditing(undefined);
    form.setFieldsValue({
      code: '',
      name: '',
      description: '',
      enabled: true,
    });
    setAgentDialogOpen(true);
  };

  const openEdit = (agent: AgentDefinition) => {
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
    let values: AgentFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
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
      message.success(t('ai.agents.message.saved', 'Agent 已保存'));
      setAgentDialogOpen(false);
      await loadAgents();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.save', 'Agent 保存失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const removeAgent = (agent: AgentDefinition) => {
    Modal.confirm({
      title: t('ai.agents.delete.title', '删除 Agent？'),
      content: t(
        'ai.agents.delete.description',
        '存在不可变版本的 Agent 不允许删除。',
      ),
      okButtonProps: {danger: true},
      onOk: async () => {
        try {
          await del(`${config.baseUrl}/${agent.id}`, []);
          message.success(t('ai.agents.message.deleted', 'Agent 已删除'));
          if (selectedAgent?.id === agent.id) setSelectedAgent(undefined);
          await loadAgents();
        } catch (error) {
          message.error(resolveErrorMessage(
            error,
            t('ai.agents.error.delete', 'Agent 删除失败'),
          ));
        }
      },
    });
  };

  const openVersionCreate = () => {
    if (!selectedAgent) return;
    versionForm.setFieldsValue({
      version: '1.0.0',
      systemPrompt: '',
      primaryModelId: models[0]?.id,
      fallbackModelIds: [],
      skillBindings: [],
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
    setVersionDialogOpen(true);
  };

  const saveVersion = async () => {
    if (!selectedAgent) return;
    let values: VersionFormValues;
    try {
      values = await versionForm.validateFields();
    } catch {
      return;
    }
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
    let bindings: Array<{
      alias: string;
      skillId: string;
      versionId: string;
    }>;
    try {
      bindings = (values.skillBindings ?? []).map((binding) => {
        const option = skillVersions.get(binding.versionId);
        if (!option) throw new Error('Skill version is unavailable');
        return {
          alias: binding.alias,
          skillId: option.skill.id,
          versionId: option.version.id,
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
        name: selectedAgent.code,
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
      await post(`${config.baseUrl}/${selectedAgent.id}/versions`, {
        version: values.version,
        manifest,
      });
      message.success(t(
        'ai.agents.message.versionCreated',
        '不可变 Agent 版本已创建',
      ));
      setVersionDialogOpen(false);
      await loadVersions(selectedAgent);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.createVersion', 'Agent 版本创建失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const changeVersionStatus = async (
    version: AgentVersion,
    action: 'publish' | 'deprecate',
  ) => {
    if (!selectedAgent) return;
    try {
      await post(
        `${config.baseUrl}/${selectedAgent.id}/versions/${version.id}/${action}`,
        {},
      );
      message.success(action === 'publish'
        ? t('ai.agents.message.published', 'Agent 版本已发布并激活')
        : t('ai.agents.message.deprecated', 'Agent 版本已废弃'));
      await Promise.all([loadVersions(selectedAgent), loadAgents()]);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.changeStatus', '版本状态变更失败'),
      ));
    }
  };

  const openExecution = () => {
    if (!selectedAgent) return;
    executionForm.setFieldsValue({
      idempotencyKey: globalThis.crypto?.randomUUID?.()
        ?? `${Date.now()}-${Math.random()}`,
      input: JSON.stringify({
        request: '',
      }, null, 2),
    });
    setExecutionDialogOpen(true);
  };

  const startExecution = async () => {
    if (!selectedAgent) return;
    let values: ExecutionFormValues;
    try {
      values = await executionForm.validateFields();
    } catch {
      return;
    }
    let input: Record<string, unknown>;
    try {
      input = parseObject(values.input, 'Agent input');
    } catch {
      message.error(t(
        'ai.agents.error.executionInput',
        'Agent 输入必须是有效的 JSON 对象',
      ));
      return;
    }
    setSaving(true);
    try {
      const execution = await post<AgentExecution>(
        `${config.baseUrl}/${selectedAgent.id}/executions`,
        {
          idempotencyKey: values.idempotencyKey,
          input,
        },
      );
      message.success(t(
        'ai.agents.message.executionStarted',
        'Agent 执行已提交',
      ));
      setExecutionDialogOpen(false);
      await Promise.all([
        loadExecutions(selectedAgent),
        loadMetrics(selectedAgent),
        openExecutionDetails(execution),
      ]);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.startExecution', 'Agent 执行提交失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const executionAction = async (
    execution: AgentExecution,
    action: 'approve' | 'reject' | 'pause' | 'resume' | 'cancel',
  ) => {
    if (!selectedAgent) return;
    try {
      await post(
        `${config.baseUrl}/${selectedAgent.id}/executions/${execution.id}/${action}`,
        action === 'approve' || action === 'reject'
          ? {comment: ''}
          : action === 'pause'
            ? {reason: ''}
            : {},
      );
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
        loadExecutions(selectedAgent),
        loadMetrics(selectedAgent),
        inspectingExecution?.id === execution.id
          ? refreshExecutionDetails()
          : Promise.resolve(),
      ]);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.executionAction', 'Agent 执行状态变更失败'),
      ));
    }
  };

  const openHumanIntervention = (execution: AgentExecution) => {
    interventionForm.setFieldsValue({prompt: ''});
    setInterventionExecution(execution);
  };

  const requestHumanIntervention = async () => {
    if (!selectedAgent || !interventionExecution) return;
    let values: HumanInterventionFormValues;
    try {
      values = await interventionForm.validateFields();
    } catch {
      return;
    }
    setSaving(true);
    try {
      await post(
        `${config.baseUrl}/${selectedAgent.id}/executions/${
          interventionExecution.id
        }/interventions`,
        {prompt: values.prompt},
      );
      message.success(t(
        'ai.agents.message.interventionRequested',
        '已请求在下一个安全检查点进行人工介入',
      ));
      setInterventionExecution(undefined);
      await Promise.all([
        loadExecutions(selectedAgent),
        inspectingExecution?.id === interventionExecution.id
          ? refreshExecutionDetails()
          : Promise.resolve(),
      ]);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.interventionRequest', '人工介入请求失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const openHumanInterventionResponse = async (execution: AgentExecution) => {
    if (!selectedAgent) return;
    try {
      const details = await get<AgentExecution>(
        `${config.baseUrl}/${selectedAgent.id}/executions/${execution.id}`,
      );
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
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.loadExecutionDetails', 'Agent 执行详情加载失败'),
      ));
    }
  };

  const respondHumanIntervention = async () => {
    if (!selectedAgent || !respondingIntervention) return;
    let values: HumanInterventionResponseFormValues;
    try {
      values = await interventionResponseForm.validateFields();
    } catch {
      return;
    }
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
      const {execution, intervention} = respondingIntervention;
      await post(
        `${config.baseUrl}/${selectedAgent.id}/executions/${
          execution.id
        }/interventions/${intervention.id}/respond`,
        {
          outcome: values.outcome,
          input,
          comment: values.comment,
        },
      );
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
        loadExecutions(selectedAgent),
        loadMetrics(selectedAgent),
        inspectingExecution?.id === execution.id
          ? refreshExecutionDetails()
          : Promise.resolve(),
      ]);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.agents.error.interventionResponse', '人工介入提交失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const openMemories = async () => {
    if (!selectedAgent) return;
    setMemoryDrawerOpen(true);
    await loadMemories(selectedAgent);
  };

  const removeMemory = (memory: AgentMemory) => {
    if (!selectedAgent) return;
    Modal.confirm({
      title: t('ai.agents.memory.delete.title', '删除这条长期记忆？'),
      content: t(
        'ai.agents.memory.delete.description',
        '删除后无法恢复，后续执行不会再检索到这条记忆。',
      ),
      okButtonProps: {danger: true},
      onOk: async () => {
        try {
          await del(
            `${config.baseUrl}/${selectedAgent.id}/memories/${memory.id}`,
            [],
          );
          message.success(t(
            'ai.agents.message.memoryDeleted',
            '长期记忆已删除',
          ));
          await loadMemories(selectedAgent);
        } catch (error) {
          message.error(resolveErrorMessage(
            error,
            t('ai.agents.error.deleteMemory', '长期记忆删除失败'),
          ));
        }
      },
    });
  };

  const modelOptions = useMemo(() => models.map((model) => ({
    value: model.id,
    label: `${model.displayName || model.modelId || model.id} · ${model.modelType}`,
  })), [models]);

  const skillVersionOptions = useMemo(() =>
    Array.from(skillVersions.values()).map(({skill, version}) => ({
      value: version.id,
      label: `${skill.name || skill.code} · ${version.version}`,
    })), [skillVersions]);

  const metricTraceCount = useMemo(() =>
    (metrics?.traces ?? []).reduce(
      (total, item) => total + item.traceCount,
      0,
    ), [metrics]);

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
        message={t('ai.agents.notice.title', '声明式 Agent Registry')}
        description={t(
          'ai.agents.notice.description',
          'Agent 版本固定模型选择器和已发布 Skill 版本；运行时只能经过 Agent → Skill → MCP 能力链路。',
        )}
      />
      <Card
        title={t('ai.agents.title', 'Agent')}
        extra={(
          <Space>
            <Button onClick={() => void Promise.all([
              loadAgents(),
              loadDependencies(),
            ])}>
              {t('ai.agents.action.refresh', '刷新')}
            </Button>
            <Button type="primary" onClick={openCreate}>
              {t('ai.agents.action.create', '新增 Agent')}
            </Button>
          </Space>
        )}
      >
        <Table<AgentDefinition>
          rowKey="id"
          loading={loading}
          dataSource={agents}
          pagination={false}
          rowSelection={{
            type: 'radio',
            selectedRowKeys: selectedAgent ? [selectedAgent.id] : [],
            onChange: (_, rows) => setSelectedAgent(rows[0]),
          }}
          onRow={(record) => ({
            onClick: () => setSelectedAgent(record),
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
                  {value}
                </Tag>
              ),
            },
            {
              title: t('ai.agents.column.status', '状态'),
              dataIndex: 'status',
              width: 110,
              render: (value) => (
                <Tag color={definitionStatusColor(value)}>{value}</Tag>
              ),
            },
            {
              title: t('ai.agents.column.description', '说明'),
              dataIndex: 'description',
              ellipsis: true,
            },
            {
              title: t('ai.agents.column.action', '操作'),
              width: 160,
              render: (_, record) => (
                <Space>
                  <Button type="link" onClick={(event) => {
                    event.stopPropagation();
                    openEdit(record);
                  }}>
                    {t('ai.agents.action.edit', '编辑')}
                  </Button>
                  <Button danger type="link" onClick={(event) => {
                    event.stopPropagation();
                    removeAgent(record);
                  }}>
                    {t('ai.agents.action.delete', '删除')}
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

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

      <Card
        title={selectedAgent
          ? `${selectedAgent.name} · ${t('ai.agents.versions.title', '不可变版本')}`
          : t('ai.agents.versions.empty', '选择 Agent 后管理版本')}
        extra={selectedAgent ? (
          <Button type="primary" onClick={openVersionCreate}>
            {t('ai.agents.action.createVersion', '创建版本')}
          </Button>
        ) : undefined}
      >
        <Table<AgentVersion>
          rowKey="id"
          loading={versionLoading}
          dataSource={versions}
          pagination={false}
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
                <Tag color={versionStatusColor(value)}>{value}</Tag>
              ),
            },
            {
              title: t('ai.agents.column.model', '主模型'),
              dataIndex: 'primaryModelId',
              width: 240,
              ellipsis: true,
              render: (value) =>
                models.find((model) => model.id === value)?.displayName
                || models.find((model) => model.id === value)?.modelId
                || value,
            },
            {
              title: t('ai.agents.column.skills', 'Skill 绑定'),
              width: 140,
              render: (_, record) => (
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
              render: (_, record) => (
                <Space>
                  <Button type="link" onClick={() => setInspectingVersion(record)}>
                    {t('ai.agents.action.inspect', '查看')}
                  </Button>
                  {record.status !== 'DEPRECATED' && (
                    <Button
                      type="link"
                      onClick={() => void changeVersionStatus(record, 'publish')}
                    >
                      {t('ai.agents.action.publish', '发布')}
                    </Button>
                  )}
                  {record.status === 'PUBLISHED' && (
                    <Button
                      danger
                      type="link"
                      onClick={() => void changeVersionStatus(record, 'deprecate')}
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
            <Button
              type="primary"
              disabled={selectedAgent.status !== 'ACTIVE'}
              onClick={openExecution}
            >
              {t('ai.agents.action.execute', '执行 Agent')}
            </Button>
          </Space>
        ) : undefined}
      >
        <Table<AgentExecution>
          rowKey="id"
          loading={executionLoading}
          dataSource={executions}
          pagination={{pageSize: 10, hideOnSinglePage: true}}
          locale={{emptyText: t('ai.agents.executions.noData', '暂无执行记录')}}
          columns={[
            {
              title: t('ai.agents.column.status', '状态'),
              dataIndex: 'status',
              width: 150,
              render: (value: AgentExecutionStatus) => (
                <Tag color={executionStatusColor(value)}>{value}</Tag>
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
                  {record.status === 'WAITING_APPROVAL' && (
                    <>
                      <Button
                        type="link"
                        onClick={() => void executionAction(record, 'approve')}
                      >
                        {t('ai.agents.action.approve', '审批')}
                      </Button>
                      <Button
                        danger
                        type="link"
                        onClick={() => void executionAction(record, 'reject')}
                      >
                        {t('ai.agents.action.reject', '驳回')}
                      </Button>
                    </>
                  )}
                  {!terminalExecutionStatuses.has(record.status)
                    && record.status !== 'PAUSED' && (
                    <Button
                      type="link"
                      onClick={() => void executionAction(record, 'pause')}
                    >
                      {t('ai.agents.action.pause', '暂停')}
                    </Button>
                  )}
                  {record.status === 'PAUSED' && (
                    <Button
                      type="link"
                      onClick={() => void executionAction(record, 'resume')}
                    >
                      {t('ai.agents.action.resume', '恢复')}
                    </Button>
                  )}
                  {record.humanInterventionEnabled
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
                  {record.status === 'WAITING_HUMAN' && (
                    <Button
                      type="link"
                      onClick={() => void openHumanInterventionResponse(record)}
                    >
                      {t('ai.agents.action.respondIntervention', '提交人工输入')}
                    </Button>
                  )}
                  {!terminalExecutionStatuses.has(record.status) && (
                    <Button
                      danger
                      type="link"
                      onClick={() => void executionAction(record, 'cancel')}
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

      <Modal
        open={agentDialogOpen}
        title={editing
          ? t('ai.agents.dialog.edit', '编辑 Agent')
          : t('ai.agents.dialog.create', '新增 Agent')}
        confirmLoading={saving}
        onOk={() => void saveAgent()}
        onCancel={() => setAgentDialogOpen(false)}
        destroyOnHidden
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
        open={executionDialogOpen}
        title={t('ai.agents.execution.start', '执行 Agent')}
        width={680}
        confirmLoading={saving}
        onOk={() => void startExecution()}
        onCancel={() => setExecutionDialogOpen(false)}
        destroyOnHidden
      >
        <Alert
          showIcon
          type="info"
          message={t(
            'ai.agents.execution.notice',
            '本次执行固定当前活动 Agent 版本、模型和 Skill 版本。',
          )}
          style={{marginBottom: 16}}
        />
        <Form form={executionForm} layout="vertical">
          <Form.Item
            name="idempotencyKey"
            label={t('ai.agents.execution.idempotencyKey', '幂等键')}
            rules={[{required: true, max: 128}]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="input"
            label={t('ai.agents.execution.input', '执行输入')}
            rules={[{required: true}]}
          >
            <TextArea rows={12} style={{fontFamily: 'monospace'}} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={Boolean(interventionExecution)}
        title={t('ai.agents.intervention.requestTitle', '请求人工介入')}
        confirmLoading={saving}
        onOk={() => void requestHumanIntervention()}
        onCancel={() => setInterventionExecution(undefined)}
        destroyOnHidden
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
        open={Boolean(respondingIntervention)}
        title={t('ai.agents.intervention.responseTitle', '提交人工介入结果')}
        width={680}
        confirmLoading={saving}
        onOk={() => void respondHumanIntervention()}
        onCancel={() => setRespondingIntervention(undefined)}
        destroyOnHidden
      >
        {respondingIntervention && (
          <Alert
            showIcon
            type="info"
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
        open={versionDialogOpen}
        title={t('ai.agents.version.create', '创建不可变 Agent 版本')}
        width={760}
        onClose={() => setVersionDialogOpen(false)}
        extra={(
          <Space>
            <Button onClick={() => setVersionDialogOpen(false)}>
              {t('ai.agents.action.close', '关闭')}
            </Button>
            <Button
              type="primary"
              loading={saving}
              onClick={() => void saveVersion()}
            >
              {t('ai.agents.action.createVersion', '创建版本')}
            </Button>
          </Space>
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
        <Form form={versionForm} layout="vertical">
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
          <Form.Item
            name="primaryModelId"
            label={t('ai.agents.field.primaryModel', '主模型')}
            rules={[{required: true}]}
          >
            <Select showSearch optionFilterProp="label" options={modelOptions} />
          </Form.Item>
          <Form.Item
            name="fallbackModelIds"
            label={t('ai.agents.field.fallbackModels', '回退模型')}
          >
            <Select
              mode="multiple"
              maxCount={8}
              showSearch
              optionFilterProp="label"
              options={modelOptions}
            />
          </Form.Item>
          <Text strong>{t('ai.agents.field.skills', '固定 Skill 版本')}</Text>
          <Form.List name="skillBindings">
            {(fields, {add, remove}) => (
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
                      <Select
                        showSearch
                        optionFilterProp="label"
                        options={skillVersionOptions}
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
              </Space>
            )}
          </Form.List>
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
            <TextArea rows={8} style={{fontFamily: 'monospace'}} />
          </Form.Item>
          <Form.Item
            name="outputSchema"
            label={t('ai.agents.field.outputSchema', '输出 Schema')}
            rules={[{required: true}]}
          >
            <TextArea rows={8} style={{fontFamily: 'monospace'}} />
          </Form.Item>
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
                  {inspectingVersion.status}
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
              <Text strong>Manifest</Text>
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
                  {inspectingExecution.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Execution ID">
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
                  } chars`
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
                  ? inspectingExecution.humanInterventionTimeoutAction
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
              {inspectingExecution.errorMessage && (
                <Descriptions.Item label={t('ai.agents.execution.error', '错误')} span={2}>
                  <Text type="danger">
                    {inspectingExecution.errorCode
                      ? `${inspectingExecution.errorCode}: ` : ''}
                    {inspectingExecution.errorMessage}
                  </Text>
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

            {inspectingExecution.output !== undefined && (
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
                          <Text strong>#{event.sequence} {event.type}</Text>
                          <Tag color={executionStatusColor(event.executionStatus)}>
                            {event.executionStatus}
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
                <Table<AgentHumanIntervention>
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
                      render: (value) => <Tag>{value}</Tag>,
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
                    {value: 'MODEL', label: 'MODEL'},
                    {value: 'SKILL', label: 'SKILL'},
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
                    {value: 'RUNNING', label: 'RUNNING'},
                    {value: 'SUCCEEDED', label: 'SUCCEEDED'},
                    {value: 'FAILED', label: 'FAILED'},
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
              <Table<AgentExecutionTrace>
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
                        {value}
                      </Tag>
                    ),
                  },
                  {
                    title: t('ai.agents.column.status', '状态'),
                    dataIndex: 'status',
                    width: 110,
                  },
                  {
                    title: t('ai.agents.execution.target', '目标'),
                    ellipsis: true,
                    render: (_, trace) => trace.type === 'MODEL'
                      ? trace.modelDefinitionId
                      : `${trace.capabilityAlias} · ${trace.skillExecutionId}`,
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
                      <Text type="secondary">Input</Text>
                      <pre>{JSON.stringify(trace.input, null, 2)}</pre>
                      <Text type="secondary">Output</Text>
                      <pre>{JSON.stringify(trace.output, null, 2)}</pre>
                      {trace.errorMessage && (
                        <Text type="danger">{trace.errorMessage}</Text>
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
        <Table<AgentMemory>
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
                  ellipsis={{rows: 4, expandable: true, symbol: 'more'}}
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
            {
              title: t('ai.agents.column.action', '操作'),
              width: 90,
              render: (_, record) => (
                <Button
                  danger
                  type="link"
                  onClick={() => removeMemory(record)}
                >
                  {t('ai.agents.action.delete', '删除')}
                </Button>
              ),
            },
          ]}
          expandable={{
            expandedRowRender: (memory) => (
              <Descriptions bordered size="small" column={1}>
                <Descriptions.Item label="Memory ID">
                  <Text copyable code>{memory.id}</Text>
                </Descriptions.Item>
                <Descriptions.Item label="Source Execution">
                  <Text copyable code>{memory.sourceExecutionId}</Text>
                </Descriptions.Item>
                <Descriptions.Item label="Content Hash">
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
