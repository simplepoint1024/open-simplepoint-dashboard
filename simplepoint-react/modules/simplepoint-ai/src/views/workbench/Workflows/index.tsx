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
import {useDependencyOptionDirectory} from '../components/DependencyOptionSelect';
import type {DependencySelectionIssue} from '../components/dependencyOptions';
import JsonSchemaObjectEditor from '../components/JsonSchemaObjectEditor';
import WorkflowDesigner from './Designer/WorkflowDesigner';
import {
  dependencyReferencesFromManifest,
  documentFromManifest,
  parseWorkflowManifest,
  validateWorkflowDocument,
} from './Designer/model';
import {workflowDependencyTypeLabel} from './labels';
import {
  canPerformWorkflowWorkbenchAction,
  denyAllWorkflowWorkbenchPermissions,
  normalizeWorkflowWorkbenchPermissions,
  type WorkflowWorkbenchAction,
  type WorkflowWorkbenchPermissions,
} from './permissions';
import {
  canDisplayExecutionOutput,
  resolveExecutionDiagnostic,
  shouldDisplayExecutionDiagnostic,
  type ExecutionDiagnosticCategory,
  type ExecutionDiagnosticSource,
  type ExecutionDiagnosticSurface,
} from '../executionDiagnostics';
import {
  Alert,
  App,
  Button,
  Card,
  Collapse,
  Descriptions,
  Drawer,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Tag,
  Tabs,
  Timeline,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';

const {Paragraph, Text} = Typography;
const {TextArea} = Input;

type Translate = (key: string, fallback?: string) => string;

const workflowDiagnosticLabel = (
  t: Translate,
  category: ExecutionDiagnosticCategory,
) => {
  switch (category) {
    case 'workflowBudget':
      return t(
        'ai.workflows.execution.diagnostic.budget',
        '执行已达到 Workflow 资源预算，请检查预算和流程规模后重试',
      );
    case 'workflowHumanTimeout':
      return t(
        'ai.workflows.execution.diagnostic.humanTimeout',
        '人工任务等待已超时，请重新发起流程并及时处理',
      );
    case 'workflowNodeCancelled':
      return t(
        'ai.workflows.execution.diagnostic.nodeCancelled',
        '节点执行已被取消，请确认流程状态和取消来源',
      );
    case 'workflowNode':
      return t(
        'ai.workflows.execution.diagnostic.node',
        '节点未能完成，请检查节点配置和固定依赖后重试',
      );
    case 'workflowRetryExhausted':
      return t(
        'ai.workflows.execution.diagnostic.retryExhausted',
        '节点重试次数已用尽，请检查重试策略和依赖状态',
      );
    case 'workflowWaitState':
      return t(
        'ai.workflows.execution.diagnostic.waitState',
        '节点等待状态无效，请刷新执行状态后重试',
      );
    case 'workflowAgentChild':
      return t(
        'ai.workflows.execution.diagnostic.agentChild',
        '关联的 Agent 子执行未能完成，请检查 Agent 执行记录',
      );
    case 'workflowSkillChild':
      return t(
        'ai.workflows.execution.diagnostic.skillChild',
        '关联的 Skill 子执行未能完成，请检查 Skill 执行记录',
      );
    case 'workflowCompensationChild':
      return t(
        'ai.workflows.execution.diagnostic.compensationChild',
        '补偿 Skill 子执行未能完成，请检查补偿配置和执行记录',
      );
    case 'workflowNodeUnknown':
      return t(
        'ai.workflows.execution.diagnostic.nodeUnknown',
        '节点未能完成，请依据诊断代码联系管理员',
      );
    default:
      return t(
        'ai.workflows.execution.diagnostic.unknown',
        'Workflow 执行未能完成，请依据诊断代码联系管理员',
      );
  }
};

type WorkflowDefinition = {
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

type WorkflowDependency = {
  id: string;
  nodeId: string;
  dependencyType: 'AGENT' | 'SKILL' | 'COMPENSATION_SKILL';
  resourceId: string;
  resourceVersionId: string;
  resourceCode: string;
  resourceVersionName: string;
  resourceContentHash: string;
};

type WorkflowVersion = {
  id: string;
  version: string;
  manifestSchemaVersion: string;
  contentHash: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  publishedAt?: string;
  deprecatedAt?: string;
  manifest?: Record<string, unknown>;
  dependencies?: WorkflowDependency[];
};

type WorkflowFormValues = {
  code?: string;
  name: string;
  description?: string;
  enabled?: boolean;
};

type VersionFormValues = {
  version: string;
  manifest: string;
};

type WorkflowNodeExecutionStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'WAITING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'SKIPPED'
  | 'COMPENSATING'
  | 'COMPENSATED'
  | 'COMPENSATION_FAILED';

type WorkflowNodeExecution = {
  id: string;
  nodeId: string;
  nodeType: string;
  status: WorkflowNodeExecutionStatus;
  attemptCount: number;
  childExecutionId?: string;
  compensationExecutionId?: string;
  errorCode?: string;
  errorMessage?: string;
  input?: unknown;
  output?: unknown;
};

type WorkflowHumanTask = {
  id: string;
  nodeId: string;
  title: string;
  description?: string;
  status: 'OPEN' | 'COMPLETED' | 'TIMED_OUT' | 'CANCELLED';
  dueAt: string;
  inputSchema?: Record<string, unknown>;
  context?: unknown;
  output?: unknown;
};

type WorkflowExecutionEvent = {
  id: string;
  sequence: number;
  type: string;
  executionStatus: string;
  occurredAt: string;
  payload?: Record<string, unknown>;
};

type WorkflowExecution = {
  id: string;
  workflowVersionId: string;
  workflowVersionContentHash: string;
  status: string;
  requestedBy?: string;
  inputHash: string;
  input?: Record<string, unknown>;
  output?: unknown;
  errorCode?: string;
  errorMessage?: string;
  pauseRequested: boolean;
  consumedNodeExecutions: number;
  maximumNodeExecutions: number;
  maximumParallelism: number;
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
  nodes?: WorkflowNodeExecution[];
  humanTasks?: WorkflowHumanTask[];
};

type WorkflowExecutionEventFeed = {
  events: WorkflowExecutionEvent[];
  nextSequence: number;
  hasMore: boolean;
};

const statusColor: Record<string, string> = {
  ACTIVE: 'success',
  PUBLISHED: 'success',
  DRAFT: 'default',
  DISABLED: 'warning',
  DEPRECATED: 'warning',
  PENDING: 'default',
  RUNNING: 'processing',
  WAITING: 'warning',
  WAITING_CHILD: 'processing',
  WAITING_HUMAN: 'warning',
  WAITING_TIMER: 'processing',
  PAUSED: 'warning',
  COMPENSATING: 'warning',
  COMPENSATED: 'success',
  COMPENSATION_FAILED: 'error',
  SUCCEEDED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
  SKIPPED: 'default',
  OPEN: 'warning',
  COMPLETED: 'success',
  TIMED_OUT: 'error',
};

const workflowEventColor = (type: string) => {
  if (type.endsWith('_CANCELLED')) return 'gray';
  return type.includes('FAILED') ? 'red' : 'blue';
};

const pretty = (value: unknown) => JSON.stringify(value, null, 2);

const objectValue = (value: unknown): Record<string, unknown> => value
  && typeof value === 'object' && !Array.isArray(value)
  ? value as Record<string, unknown> : {};

const sampleManifest = (
  code: string,
  version: string,
  defaults: {reviewTitle: string; reviewDescription: string},
) => {
  const nodes: Record<string, unknown>[] = [{
    id: 'review',
    type: 'human',
    title: defaults.reviewTitle,
    description: defaults.reviewDescription,
    inputSchema: {type: 'object', additionalProperties: true},
    timeoutSeconds: 300,
    timeoutAction: 'FAIL',
  }, {id: 'done', type: 'end'}];
  return {
    apiVersion: 'simplepoint.io/v1alpha1',
    kind: 'AgentWorkflow',
    metadata: {name: code, version},
    spec: {
      inputSchema: {type: 'object', additionalProperties: true},
      outputSchema: {type: 'object', additionalProperties: true},
      budgets: {
        maximumDurationSeconds: 3600,
        maximumNodeExecutions: 64,
        maximumParallelism: 4,
      },
      failurePolicy: {
        mode: 'FAIL_FAST',
        compensation: 'REVERSE_SUCCEEDED',
      },
      nodes,
      edges: [{from: 'review', to: 'done'}],
      output: {$ref: 'nodes.done.output'},
    },
  };
};

const Workflows = () => {
  const {message, modal} = App.useApp();
  const config = api['ai-workbench.workflows'];
  const {ensure, locale, t} = useI18n();
  const [form] = Form.useForm<WorkflowFormValues>();
  const [versionForm] = Form.useForm<VersionFormValues>();
  const [workflows, setWorkflows] = useState<WorkflowDefinition[]>([]);
  const [versions, setVersions] = useState<WorkflowVersion[]>([]);
  const [executions, setExecutions] = useState<WorkflowExecution[]>([]);
  const [workflowPage, setWorkflowPage] = useState(1);
  const [workflowPageSize, setWorkflowPageSize] = useState(10);
  const [workflowTotal, setWorkflowTotal] = useState(0);
  const [versionPage, setVersionPage] = useState(1);
  const [versionPageSize, setVersionPageSize] = useState(10);
  const [versionTotal, setVersionTotal] = useState(0);
  const [executionPage, setExecutionPage] = useState(1);
  const [executionPageSize, setExecutionPageSize] = useState(10);
  const [executionTotal, setExecutionTotal] = useState(0);
  const [workspaceSection, setWorkspaceSection] = useState('versions');
  const [executionEvents, setExecutionEvents] =
    useState<WorkflowExecutionEvent[]>([]);
  const [eventNextSequence, setEventNextSequence] = useState(0);
  const [eventHasMore, setEventHasMore] = useState(false);
  const [selected, setSelected] = useState<WorkflowDefinition>();
  const [editing, setEditing] = useState<WorkflowDefinition>();
  const [inspecting, setInspecting] = useState<WorkflowVersion>();
  const [inspectingExecution, setInspectingExecution] =
    useState<WorkflowExecution>();
  const [respondingTask, setRespondingTask] =
    useState<WorkflowHumanTask>();
  const [definitionOpen, setDefinitionOpen] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const [versionEditorMode, setVersionEditorMode] = useState('visual');
  const [designerManifest, setDesignerManifest] =
    useState<Record<string, unknown>>();
  const [designerDraftRestored, setDesignerDraftRestored] = useState(false);
  const [dependencyValidationError, setDependencyValidationError] =
    useState<string>();
  const [dependencyValidating, setDependencyValidating] = useState(false);
  const [workflowInputSchemaValid, setWorkflowInputSchemaValid] = useState(true);
  const [workflowOutputSchemaValid, setWorkflowOutputSchemaValid] = useState(true);
  const [executionOpen, setExecutionOpen] = useState(false);
  const [executionInputSchema, setExecutionInputSchema] =
    useState<Record<string, unknown>>();
  const [executionSchemaLoading, setExecutionSchemaLoading] = useState(false);
  const [loading, setLoading] = useState(false);
  const [versionLoading, setVersionLoading] = useState(false);
  const [executionLoading, setExecutionLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [intervening, setIntervening] = useState<string>();
  const [permissions, setPermissions] = useState<WorkflowWorkbenchPermissions>({
    ...denyAllWorkflowWorkbenchPermissions,
  });
  const [permissionsLoading, setPermissionsLoading] = useState(true);
  const [permissionsError, setPermissionsError] = useState(false);
  const [authorizationContextRevision, setAuthorizationContextRevision] =
    useState(0);
  const executionPollBusy = useRef(false);
  const workflowRequestSequence = useRef(0);
  const versionRequestSequence = useRef(0);
  const executionRequestSequence = useRef(0);
  const executionDetailRequestSequence = useRef(0);
  const executionSchemaRequestSequence = useRef(0);
  const eventRequestSequence = useRef(0);
  const dependencyValidationRequestSequence = useRef(0);
  const permissionRequestSequence = useRef(0);
  const permissionReloadScheduled = useRef(false);
  const authorizationContextSequence = useRef(0);
  const selectedWorkflowIdRef = useRef<string | undefined>(undefined);
  const permissionsRef = useRef<WorkflowWorkbenchPermissions>({
    ...denyAllWorkflowWorkbenchPermissions,
  });
  const dependencyDirectoryEnabled = Boolean(
    selected
    && versionOpen
    && permissions.manageVersions
    && !permissionsLoading
    && !permissionsError,
  );
  const dependencyDirectory = useDependencyOptionDirectory({
    baseUrl: config.baseUrl,
    consumerId: selected?.id,
    contextKey: authorizationContextRevision,
    enabled: dependencyDirectoryEnabled,
  });
  const designerDependencyReferences = useMemo(() => designerManifest
    ? dependencyReferencesFromManifest(designerManifest)
    : {AGENT: [], SKILL: []}, [designerManifest]);

  const renderDiagnostic = (
    surface: Extract<ExecutionDiagnosticSurface, 'workflowExecution' | 'workflowNode'>,
    source: ExecutionDiagnosticSource,
  ) => {
    const diagnostic = resolveExecutionDiagnostic(surface, source);
    return (
      <Space direction="vertical" size={2}>
        {diagnostic.code && (
          <Text copyable code>{diagnostic.code}</Text>
        )}
        <Text type="danger">
          {workflowDiagnosticLabel(t, diagnostic.category)}
        </Text>
      </Space>
    );
  };

  const canWorkflowAction = useCallback((action: WorkflowWorkbenchAction) => (
    canPerformWorkflowWorkbenchAction(permissions, action)
  ), [permissions]);

  const requireWorkflowAction = useCallback((
    action: WorkflowWorkbenchAction,
  ) => {
    if (canPerformWorkflowWorkbenchAction(permissionsRef.current, action)) {
      return true;
    }
    message.warning(t(
      'ai.workflows.permissions.denied',
      '当前授权上下文不允许此操作，请刷新权限后重试',
    ));
    return false;
  }, [message, t]);

  const closeMutationSurfaces = useCallback(() => {
    // Keep the form instances and browser-backed designer draft intact while
    // removing every actionable surface from the previous context.
    setDefinitionOpen(false);
    setVersionOpen(false);
    dependencyValidationRequestSequence.current += 1;
    setDependencyValidating(false);
    setDependencyValidationError(undefined);
    setExecutionOpen(false);
    executionSchemaRequestSequence.current += 1;
    setRespondingTask(undefined);
    Modal.destroyAll();
  }, []);

  const loadPermissions = useCallback(async () => {
    const requestSequence = ++permissionRequestSequence.current;
    const denied = {...denyAllWorkflowWorkbenchPermissions};
    permissionsRef.current = denied;
    setPermissions(denied);
    setPermissionsLoading(true);
    setPermissionsError(false);
    try {
      const response = await get<unknown>(
        `${config.baseUrl}/workbench-permissions`,
      );
      if (requestSequence !== permissionRequestSequence.current) return;
      const normalized = normalizeWorkflowWorkbenchPermissions(response);
      permissionsRef.current = normalized;
      setPermissions(normalized);
    } catch {
      if (requestSequence !== permissionRequestSequence.current) return;
      const deniedAfterFailure = {...denyAllWorkflowWorkbenchPermissions};
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
      // Revoke the previous snapshot synchronously. Tenant, role, and context
      // events emitted in the same turn share one reload request.
      permissionRequestSequence.current += 1;
      const denied = {...denyAllWorkflowWorkbenchPermissions};
      permissionsRef.current = denied;
      setPermissions(denied);
      setPermissionsLoading(true);
      setPermissionsError(false);
      authorizationContextSequence.current += 1;
      workflowRequestSequence.current += 1;
      versionRequestSequence.current += 1;
      executionRequestSequence.current += 1;
      executionDetailRequestSequence.current += 1;
      eventRequestSequence.current += 1;
      closeMutationSurfaces();
      setWorkflows([]);
      setWorkflowTotal(0);
      selectedWorkflowIdRef.current = undefined;
      setSelected(undefined);
      setEditing(undefined);
      setVersions([]);
      setVersionTotal(0);
      setExecutions([]);
      setExecutionTotal(0);
      setInspecting(undefined);
      setInspectingExecution(undefined);
      setExecutionEvents([]);
      setEventNextSequence(0);
      setEventHasMore(false);
      setExecutionInputSchema(undefined);
      setExecutionSchemaLoading(false);
      setLoading(false);
      setVersionLoading(false);
      setExecutionLoading(false);
      setSaving(false);
      setDependencyValidating(false);
      setDependencyValidationError(undefined);
      setIntervening(undefined);
      executionPollBusy.current = false;
      setWorkflowPage(1);
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

  const loadWorkflows = useCallback(async () => {
    const requestSequence = ++workflowRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    setLoading(true);
    try {
      const page = await get<Page<WorkflowDefinition>>(
        config.baseUrl,
        {
          page: workflowPage - 1,
          size: workflowPageSize,
          sort: 'createdAt,desc',
        },
      );
      if (requestSequence !== workflowRequestSequence.current
        || contextSequence !== authorizationContextSequence.current) return;
      setWorkflows(page.content ?? []);
      setWorkflowTotal(page.page.totalElements ?? 0);
      setSelected((current) => current
        ? (page.content ?? []).find((item) => item.id === current.id) ?? current
        : undefined);
    } catch (error) {
      if (requestSequence !== workflowRequestSequence.current
        || contextSequence !== authorizationContextSequence.current) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.workflows.error.load', 'Workflow 列表加载失败'),
      ));
    } finally {
      if (requestSequence === workflowRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setLoading(false);
      }
    }
  }, [config.baseUrl, message, t, workflowPage, workflowPageSize]);

  const loadVersions = useCallback(async (
    workflow?: WorkflowDefinition,
  ) => {
    const requestSequence = ++versionRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    if (!workflow) {
      setVersions([]);
      setVersionTotal(0);
      setVersionLoading(false);
      return;
    }
    setVersionLoading(true);
    try {
      const page = await get<Page<WorkflowVersion>>(
        `${config.baseUrl}/${workflow.id}/versions`,
        {page: versionPage - 1, size: versionPageSize, sort: 'createdAt,desc'},
      );
      if (requestSequence === versionRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setVersions(page.content ?? []);
        setVersionTotal(page.page.totalElements ?? 0);
      }
    } catch (error) {
      if (requestSequence === versionRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setVersions([]);
        setVersionTotal(0);
        message.error(resolveApiErrorMessage(
          error,
          t('ai.workflows.error.loadVersions', 'Workflow 版本加载失败'),
        ));
      }
    } finally {
      if (requestSequence === versionRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setVersionLoading(false);
      }
    }
  }, [config.baseUrl, message, t, versionPage, versionPageSize]);

  const loadExecutions = useCallback(async (
    workflow?: WorkflowDefinition,
  ) => {
    const requestSequence = ++executionRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    if (!workflow) {
      setExecutions([]);
      setExecutionTotal(0);
      setExecutionLoading(false);
      return;
    }
    setExecutionLoading(true);
    try {
      const page = await get<Page<WorkflowExecution>>(
        `${config.baseUrl}/${workflow.id}/executions`,
        {
          page: executionPage - 1,
          size: executionPageSize,
          sort: 'createdAt,desc',
        },
      );
      if (requestSequence === executionRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setExecutions(page.content ?? []);
        setExecutionTotal(page.page.totalElements ?? 0);
      }
    } catch (error) {
      if (requestSequence === executionRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setExecutions([]);
        setExecutionTotal(0);
        message.error(resolveApiErrorMessage(
          error,
          t('ai.workflows.error.loadExecutions', '执行记录加载失败'),
        ));
      }
    } finally {
      if (requestSequence === executionRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setExecutionLoading(false);
      }
    }
  }, [config.baseUrl, executionPage, executionPageSize, message, t]);

  useEffect(() => {
    void loadWorkflows();
  }, [authorizationContextRevision, loadWorkflows]);

  useEffect(() => {
    if (!versionOpen || !dependencyDirectoryEnabled) return;
    void dependencyDirectory.prefetch(['AGENT', 'SKILL']);
  }, [
    dependencyDirectory.prefetch,
    dependencyDirectoryEnabled,
    versionOpen,
  ]);

  useEffect(() => {
    if (!versionOpen || !dependencyDirectoryEnabled) return;
    void Promise.all([
      dependencyDirectory.resolve('AGENT', designerDependencyReferences.AGENT),
      dependencyDirectory.resolve('SKILL', designerDependencyReferences.SKILL),
    ]);
  }, [
    dependencyDirectory.resolve,
    dependencyDirectoryEnabled,
    designerDependencyReferences.AGENT.join('|'),
    designerDependencyReferences.SKILL.join('|'),
    versionOpen,
  ]);

  useEffect(() => {
    void loadVersions(selected);
    void loadExecutions(selected);
  }, [loadExecutions, loadVersions, selected]);

  const openExecution = async () => {
    if (!requireWorkflowAction('execute')) return;
    if (!selected?.activeVersionId
      || selectedWorkflowIdRef.current !== selected.id) {
      message.warning(t(
        'ai.workflows.execution.noActiveVersion',
        '请先发布并激活一个 Workflow 版本',
      ));
      return;
    }
    const contextSequence = authorizationContextSequence.current;
    const requestSequence = ++executionSchemaRequestSequence.current;
    const workflow = selected;
    setExecutionOpen(true);
    setExecutionInputSchema(undefined);
    setExecutionSchemaLoading(true);
    try {
      if (requestSequence !== executionSchemaRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedWorkflowIdRef.current !== workflow.id
        || !requireWorkflowAction('execute')) return;
      const version = await get<WorkflowVersion>(
        `${config.baseUrl}/${workflow.id}/versions/${workflow.activeVersionId}`,
      );
      if (requestSequence !== executionSchemaRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedWorkflowIdRef.current !== workflow.id) return;
      setExecutionInputSchema(inputSchemaFromManifest(version.manifest));
    } catch (error) {
      if (requestSequence !== executionSchemaRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedWorkflowIdRef.current !== workflow.id) return;
      setExecutionOpen(false);
      message.error(resolveApiErrorMessage(
        error,
        t('ai.workflows.error.loadExecutionSchema', 'Workflow 输入 Schema 加载失败'),
      ));
    } finally {
      if (requestSequence === executionSchemaRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setExecutionSchemaLoading(false);
      }
    }
  };

  const startExecution = async (input: ExecutionInput) => {
    if (!selected || selectedWorkflowIdRef.current !== selected.id
      || !requireWorkflowAction('execute')) return;
    const contextSequence = authorizationContextSequence.current;
    const workflow = selected;
    if (contextSequence !== authorizationContextSequence.current
      || !requireWorkflowAction('execute')) return;
    setSaving(true);
    try {
      const execution = await post<WorkflowExecution>(
        `${config.baseUrl}/${workflow.id}/executions`,
        {
          idempotencyKey: `workflow-${globalThis.crypto?.randomUUID?.()
            ?? `${Date.now()}-${Math.random()}`}`,
          input,
        },
      );
      if (contextSequence !== authorizationContextSequence.current
        || selectedWorkflowIdRef.current !== workflow.id) return;
      setExecutionOpen(false);
      message.success(t(
        'ai.workflows.message.executionStarted',
        'Workflow 执行已提交',
      ));
      await loadExecutions(workflow);
      await inspectExecution(execution.id);
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.workflows.error.execute', 'Workflow 执行失败'),
      ));
    } finally {
      if (contextSequence === authorizationContextSequence.current) {
        setSaving(false);
      }
    }
  };

  const inspectExecution = async (executionId: string, silent = false) => {
    if (!selected || selectedWorkflowIdRef.current !== selected.id) return;
    const requestSequence = ++executionDetailRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    const workflow = selected;
    if (!silent) setExecutionLoading(true);
    try {
      const [execution, feed] = await Promise.all([
        get<WorkflowExecution>(
          `${config.baseUrl}/${workflow.id}/executions/${executionId}`,
        ),
        get<WorkflowExecutionEventFeed>(
          `${config.baseUrl}/${workflow.id}/executions/${executionId}/events`,
          {after: 0, limit: 100},
        ),
      ]);
      if (requestSequence !== executionDetailRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedWorkflowIdRef.current !== workflow.id) return;
      setInspectingExecution(execution);
      setExecutionEvents(feed.events ?? []);
      setEventNextSequence(feed.nextSequence ?? 0);
      setEventHasMore(Boolean(feed.hasMore));
    } catch (error) {
      if (!silent
        && requestSequence === executionDetailRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        message.error(resolveApiErrorMessage(
          error,
          t('ai.workflows.error.loadExecution', '执行详情加载失败'),
        ));
      }
    } finally {
      if (!silent
        && requestSequence === executionDetailRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        setExecutionLoading(false);
      }
    }
  };

  const loadLaterEvents = async (silent = false) => {
    if (!selected || !inspectingExecution
      || selectedWorkflowIdRef.current !== selected.id) return;
    const requestSequence = ++eventRequestSequence.current;
    const contextSequence = authorizationContextSequence.current;
    const workflow = selected;
    const executionId = inspectingExecution.id;
    const after = eventNextSequence;
    try {
      const feed = await get<WorkflowExecutionEventFeed>(
        `${config.baseUrl}/${workflow.id}/executions/${executionId}/events`,
        {after, limit: 100},
      );
      if (requestSequence !== eventRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedWorkflowIdRef.current !== workflow.id) return;
      if (feed.events?.length) {
        setExecutionEvents((current) => {
          const known = new Set(current.map((event) => event.id));
          return [...current, ...feed.events.filter((event) => !known.has(event.id))];
        });
      }
      setEventNextSequence(feed.nextSequence ?? after);
      setEventHasMore(Boolean(feed.hasMore));
    } catch (error) {
      if (!silent
        && requestSequence === eventRequestSequence.current
        && contextSequence === authorizationContextSequence.current) {
        message.error(resolveApiErrorMessage(
          error,
          t('ai.workflows.error.loadEvents', '执行事件加载失败'),
        ));
      }
    }
  };

  const controlExecution = async (
    action: 'pause' | 'resume' | 'cancel',
  ) => {
    if (!selected || !inspectingExecution
      || selectedWorkflowIdRef.current !== selected.id
      || !requireWorkflowAction(action)) return;
    const contextSequence = authorizationContextSequence.current;
    const workflow = selected;
    const executionId = inspectingExecution.id;
    let reason = '';
    modal.confirm({
      title: t(`ai.workflows.execution.${action}.title`,
        action === 'pause' ? '暂停这次执行？'
          : action === 'resume' ? '恢复这次执行？' : '取消这次执行？'),
      content: action === 'pause' ? (
        <Input.TextArea
          maxLength={512}
          rows={3}
          placeholder={t('ai.workflows.execution.pause.reason', '说明暂停原因（可选）')}
          onChange={(event) => { reason = event.target.value; }}
        />
      ) : t(`ai.workflows.execution.${action}.description`,
        action === 'resume' ? '执行将从持久化节点检查点继续。'
          : '取消不可撤销，尚未完成的人工任务也会一并取消。'),
      okButtonProps: action === 'cancel' ? {danger: true} : undefined,
      onOk: async () => {
        if (contextSequence !== authorizationContextSequence.current
          || selectedWorkflowIdRef.current !== workflow.id
          || !requireWorkflowAction(action)) return;
        setIntervening(action);
        try {
          await post(
            `${config.baseUrl}/${workflow.id}/executions/`
              + `${executionId}/${action}`,
            action === 'pause' ? {reason: reason.trim() || undefined} : {},
          );
          if (contextSequence !== authorizationContextSequence.current
            || selectedWorkflowIdRef.current !== workflow.id) return;
          message.success(t(
            `ai.workflows.message.${action}`,
            action === 'pause' ? '暂停请求已提交'
              : action === 'resume' ? '执行已恢复' : '执行已取消',
          ));
          await Promise.all([
            inspectExecution(executionId),
            loadExecutions(workflow),
          ]);
        } catch (error) {
          if (contextSequence !== authorizationContextSequence.current) return;
          message.error(resolveApiErrorMessage(
            error,
            t('ai.workflows.error.intervene', '执行状态变更失败'),
          ));
          throw error;
        } finally {
          if (contextSequence === authorizationContextSequence.current) {
            setIntervening(undefined);
          }
        }
      },
    });
  };

  const openHumanTask = (task: WorkflowHumanTask) => {
    if (!requireWorkflowAction('respondHumanTask')) return;
    setRespondingTask(task);
  };

  const respondHumanTask = async (output: ExecutionInput) => {
    if (!selected || !inspectingExecution || !respondingTask
      || selectedWorkflowIdRef.current !== selected.id
      || !requireWorkflowAction('respondHumanTask')) return;
    const contextSequence = authorizationContextSequence.current;
    const workflow = selected;
    const executionId = inspectingExecution.id;
    const taskId = respondingTask.id;
    modal.confirm({
      title: t('ai.workflows.execution.human.confirm.title', '提交人工任务响应？'),
      content: t(
        'ai.workflows.execution.human.confirm.description',
        '提交后响应会写入持久化检查点且不能修改，工作流随后继续运行。',
      ),
      onOk: async () => {
        if (contextSequence !== authorizationContextSequence.current
          || selectedWorkflowIdRef.current !== workflow.id
          || !requireWorkflowAction('respondHumanTask')) return;
        setSaving(true);
        try {
          await post(
            `${config.baseUrl}/${workflow.id}/executions/`
              + `${executionId}/human-tasks/${taskId}/respond`,
            {output},
          );
          if (contextSequence !== authorizationContextSequence.current
            || selectedWorkflowIdRef.current !== workflow.id) return;
          setRespondingTask(undefined);
          message.success(t('ai.workflows.message.humanResponded', '人工任务已提交'));
          await Promise.all([
            inspectExecution(executionId),
            loadExecutions(workflow),
          ]);
        } catch (error) {
          if (contextSequence !== authorizationContextSequence.current) return;
          message.error(resolveApiErrorMessage(
            error,
            t('ai.workflows.error.respond', '人工任务处理失败'),
          ));
          throw error;
        } finally {
          if (contextSequence === authorizationContextSequence.current) {
            setSaving(false);
          }
        }
      },
    });
  };

  useEffect(() => {
    if (!selected || !inspectingExecution
      || ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(inspectingExecution.status)) return;
    const timer = globalThis.setTimeout(async () => {
      if (executionPollBusy.current) return;
      executionPollBusy.current = true;
      const contextSequence = authorizationContextSequence.current;
      const detailRequestSequence = executionDetailRequestSequence.current;
      const workflowId = selected.id;
      const executionId = inspectingExecution.id;
      try {
        const execution = await get<WorkflowExecution>(
          `${config.baseUrl}/${workflowId}/executions/${executionId}`,
        );
        if (contextSequence !== authorizationContextSequence.current
          || detailRequestSequence
            !== executionDetailRequestSequence.current
          || selectedWorkflowIdRef.current !== workflowId) return;
        setInspectingExecution(execution);
        await loadLaterEvents(true);
        if (contextSequence !== authorizationContextSequence.current
          || detailRequestSequence
            !== executionDetailRequestSequence.current
          || selectedWorkflowIdRef.current !== workflowId) return;
        if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(execution.status)) {
          await loadExecutions(selected);
        }
      } catch {
        // Background polling is intentionally silent; manual refresh reports errors.
      } finally {
        executionPollBusy.current = false;
      }
    }, 2500);
    return () => globalThis.clearTimeout(timer);
  }, [config.baseUrl, eventNextSequence, inspectingExecution, selected]);

  const openCreate = () => {
    if (!requireWorkflowAction('create')) return;
    setEditing(undefined);
    form.setFieldsValue({
      code: '',
      name: '',
      description: '',
      enabled: true,
    });
    setDefinitionOpen(true);
  };

  const selectWorkflow = (workflow?: WorkflowDefinition) => {
    const selectionChanged = selectedWorkflowIdRef.current !== workflow?.id;
    if (selectionChanged) {
      versionRequestSequence.current += 1;
      executionRequestSequence.current += 1;
      setVersions([]);
      setVersionTotal(0);
      setVersionLoading(Boolean(workflow));
      setExecutions([]);
      setExecutionTotal(0);
      setExecutionLoading(Boolean(workflow));
    }
    executionDetailRequestSequence.current += 1;
    executionSchemaRequestSequence.current += 1;
    eventRequestSequence.current += 1;
    dependencyValidationRequestSequence.current += 1;
    setDependencyValidating(false);
    setDependencyValidationError(undefined);
    setVersionPage(1);
    setExecutionPage(1);
    setWorkspaceSection('versions');
    setInspecting(undefined);
    setInspectingExecution(undefined);
    setExecutionEvents([]);
    setEventNextSequence(0);
    setEventHasMore(false);
    setRespondingTask(undefined);
    selectedWorkflowIdRef.current = workflow?.id;
    setSelected(workflow);
  };

  const openEdit = (workflow: WorkflowDefinition) => {
    if (!requireWorkflowAction('edit')) return;
    setEditing(workflow);
    form.setFieldsValue({
      code: workflow.code,
      name: workflow.name,
      description: workflow.description,
      enabled: workflow.enabled,
    });
    setDefinitionOpen(true);
  };

  const saveDefinition = async () => {
    const action: WorkflowWorkbenchAction = editing ? 'edit' : 'create';
    if (!requireWorkflowAction(action)) return;
    const contextSequence = authorizationContextSequence.current;
    const editingWorkflow = editing;
    let values: WorkflowFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    if (contextSequence !== authorizationContextSequence.current
      || !requireWorkflowAction(action)) return;
    setSaving(true);
    try {
      if (editingWorkflow) {
        await put(`${config.baseUrl}/${editingWorkflow.id}`, {
          ...values,
          code: editingWorkflow.code,
        });
      } else {
        await post(config.baseUrl, values);
      }
      if (contextSequence !== authorizationContextSequence.current) return;
      message.success(t('ai.workflows.message.saved', 'Workflow 已保存'));
      setDefinitionOpen(false);
      await loadWorkflows();
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.workflows.error.save', 'Workflow 保存失败'),
      ));
    } finally {
      if (contextSequence === authorizationContextSequence.current) {
        setSaving(false);
      }
    }
  };

  const removeDefinition = (workflow: WorkflowDefinition) => {
    if (!requireWorkflowAction('delete')) return;
    const contextSequence = authorizationContextSequence.current;
    modal.confirm({
      title: t('ai.workflows.delete.title', '删除 Workflow？'),
      content: t(
        'ai.workflows.delete.description',
        '存在不可变版本的 Workflow 不允许删除。',
      ),
      okButtonProps: {danger: true},
      onOk: async () => {
        if (contextSequence !== authorizationContextSequence.current
          || !requireWorkflowAction('delete')) return;
        try {
          await del(`${config.baseUrl}/${workflow.id}`, []);
          if (contextSequence !== authorizationContextSequence.current) return;
          if (selected?.id === workflow.id) selectWorkflow(undefined);
          message.success(
            t('ai.workflows.message.deleted', 'Workflow 已删除'),
          );
          await loadWorkflows();
        } catch (error) {
          if (contextSequence !== authorizationContextSequence.current) return;
          message.error(resolveApiErrorMessage(
            error,
            t('ai.workflows.error.delete', 'Workflow 删除失败'),
          ));
        }
      },
    });
  };

  const openCreateVersion = () => {
    if (!selected || selectedWorkflowIdRef.current !== selected.id
      || !requireWorkflowAction('createVersion')) return;
    const version = '1.0.0';
    const sample = sampleManifest(selected.code, version, {
      reviewTitle: t('ai.workflows.designer.defaults.reviewTitle', '审核结果'),
      reviewDescription: t(
        'ai.workflows.designer.defaults.reviewDescription',
        '确认生成的结果可以继续执行。',
      ),
    });
    const storageKey = `simplepoint.workflow.designer.${selected.id}`;
    let restored: Record<string, unknown> | undefined;
    try {
      const raw = globalThis.localStorage?.getItem(storageKey);
      restored = raw ? parseWorkflowManifest(raw) : undefined;
    } catch {
      restored = undefined;
    }
    const manifest = restored ?? sample;
    const restoredMetadata = objectValue(manifest.metadata);
    const restoredVersion = typeof restoredMetadata.version === 'string'
      ? restoredMetadata.version : version;
    versionForm.resetFields();
    versionForm.setFieldsValue({
      version: restoredVersion,
      manifest: pretty(manifest),
    });
    setDesignerManifest(manifest);
    setDesignerDraftRestored(Boolean(restored));
    setDependencyValidationError(undefined);
    setWorkflowInputSchemaValid(true);
    setWorkflowOutputSchemaValid(true);
    setVersionEditorMode('visual');
    setVersionOpen(true);
  };

  const refreshSampleVersion = () => {
    if (!selected || selectedWorkflowIdRef.current !== selected.id
      || !requireWorkflowAction('createVersion')) return;
    const version = versionForm.getFieldValue('version') || '1.0.0';
    const manifest = sampleManifest(selected.code, version, {
      reviewTitle: t('ai.workflows.designer.defaults.reviewTitle', '审核结果'),
      reviewDescription: t(
        'ai.workflows.designer.defaults.reviewDescription',
        '确认生成的结果可以继续执行。',
      ),
    });
    versionForm.setFieldValue(
      'manifest',
      pretty(manifest),
    );
    setDesignerManifest(manifest);
    setDesignerDraftRestored(false);
    setDependencyValidationError(undefined);
  };

  const updateDesignerManifest = (manifest: Record<string, unknown>) => {
    if (!selected || !canPerformWorkflowWorkbenchAction(
      permissionsRef.current,
      'createVersion',
    )) return;
    setDesignerManifest(manifest);
    setDependencyValidationError(undefined);
    versionForm.setFieldValue('manifest', pretty(manifest));
    try {
      globalThis.localStorage?.setItem(
        `simplepoint.workflow.designer.${selected.id}`,
        pretty(manifest),
      );
    } catch {
      // Browser draft storage is best effort; the open form remains intact.
    }
  };

  const updateDesignerSpec = (patch: Record<string, unknown>) => {
    if (!designerManifest || !canPerformWorkflowWorkbenchAction(
      permissionsRef.current,
      'createVersion',
    )) return;
    const currentSpec = designerManifest.spec
      && typeof designerManifest.spec === 'object'
      && !Array.isArray(designerManifest.spec)
      ? designerManifest.spec as Record<string, unknown> : {};
    updateDesignerManifest({
      ...designerManifest,
      spec: {...currentSpec, ...patch},
    });
  };

  const closeVersionEditor = (force = false) => {
    if (force || !versionForm.isFieldsTouched()) {
      dependencyValidationRequestSequence.current += 1;
      setDependencyValidating(false);
      setDependencyValidationError(undefined);
      setVersionOpen(false);
      return;
    }
    modal.confirm({
      title: t('ai.workflows.version.discard.title', '关闭 Workflow 设计器？'),
      content: t(
        'ai.workflows.version.discard.description',
        '画布已自动保存在当前浏览器，下次打开可以继续；尚未创建不可变版本。',
      ),
      okText: t('ai.workflows.version.discard.confirm', '关闭设计器'),
      cancelText: t('ai.workflows.action.cancelDialog', '继续编辑'),
      onOk: () => {
        dependencyValidationRequestSequence.current += 1;
        setDependencyValidating(false);
        setDependencyValidationError(undefined);
        setVersionOpen(false);
      },
    });
  };

  const syncManifestVersion = () => {
    if (!selected || !designerManifest) return;
    const version = versionForm.getFieldValue('version') || '1.0.0';
    updateDesignerManifest({
      ...designerManifest,
      metadata: {
        ...objectValue(designerManifest.metadata),
        name: selected.code,
        version,
      },
    });
  };

  const dependencyValidationMessage = (
    issues: DependencySelectionIssue[],
  ) => issues.some((issue) =>
    issue.reason === 'RESOLVE_FAILED' || issue.reason === 'RESOLVING')
    ? t(
      'ai.dependencies.validation.resolveFailed',
      '依赖状态校验失败，表单内容已保留，请重试',
    )
    : t(
      'ai.dependencies.validation.versionUnavailable',
      '已选依赖版本不可用，请重新选择后再保存',
    );

  const saveVersion = async () => {
    if (!selected || selectedWorkflowIdRef.current !== selected.id
      || saving || dependencyValidating
      || !requireWorkflowAction('createVersion')) return;
    const contextSequence = authorizationContextSequence.current;
    const workflow = selected;
    setDependencyValidationError(undefined);
    if (!workflowInputSchemaValid || !workflowOutputSchemaValid) {
      setVersionEditorMode('settings');
      message.error(t(
        'ai.workflows.validation.schema',
        '输入和输出 Schema 必须是有效的对象 Schema',
      ));
      return;
    }
    let values: VersionFormValues;
    try {
      values = await versionForm.validateFields();
    } catch {
      return;
    }
    let manifest: Record<string, unknown>;
    try {
      manifest = JSON.parse(values.manifest) as Record<string, unknown>;
    } catch {
      message.error(t(
        'ai.workflows.validation.manifest',
        'Manifest 必须是有效 JSON',
      ));
      return;
    }
    const diagnostics = validateWorkflowDocument(documentFromManifest(manifest));
    if (diagnostics.length > 0) {
      setVersionEditorMode('visual');
      message.error(t(
        'ai.workflows.validation.designer',
        '画布仍有未解决的问题，请修复后再创建版本',
      ));
      return;
    }
    const references = dependencyReferencesFromManifest(manifest);
    if (references.AGENT.length > 0 || references.SKILL.length > 0) {
      const validationSequence = ++dependencyValidationRequestSequence.current;
      setDependencyValidating(true);
      let dependencyIssues: DependencySelectionIssue[];
      try {
        const [agentIssues, skillIssues] = await Promise.all([
          dependencyDirectory.validate('AGENT', references.AGENT),
          dependencyDirectory.validate('SKILL', references.SKILL),
        ]);
        dependencyIssues = [...agentIssues, ...skillIssues];
      } catch {
        dependencyIssues = [{
          id: 'dependency-directory',
          reason: 'RESOLVE_FAILED',
        }];
      } finally {
        if (validationSequence === dependencyValidationRequestSequence.current
          && contextSequence === authorizationContextSequence.current) {
          setDependencyValidating(false);
        }
      }
      if (validationSequence !== dependencyValidationRequestSequence.current
        || contextSequence !== authorizationContextSequence.current
        || selectedWorkflowIdRef.current !== workflow.id) return;
      if (dependencyIssues.length > 0) {
        setDependencyValidationError(
          dependencyValidationMessage(dependencyIssues),
        );
        setVersionEditorMode('visual');
        return;
      }
    }
    if (contextSequence !== authorizationContextSequence.current
      || selectedWorkflowIdRef.current !== workflow.id
      || !requireWorkflowAction('createVersion')) return;
    setSaving(true);
    try {
      await post(`${config.baseUrl}/${workflow.id}/versions`, {
        version: values.version,
        manifest,
      });
      if (contextSequence !== authorizationContextSequence.current
        || selectedWorkflowIdRef.current !== workflow.id) return;
      closeVersionEditor(true);
      try {
        globalThis.localStorage?.removeItem(
          `simplepoint.workflow.designer.${workflow.id}`,
        );
      } catch {
        // The immutable version is already persisted; stale local storage is harmless.
      }
      message.success(
        t('ai.workflows.message.versionCreated', '不可变版本已创建'),
      );
      await loadVersions(workflow);
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.workflows.error.createVersion', 'Workflow 版本创建失败'),
      ));
    } finally {
      if (contextSequence === authorizationContextSequence.current) {
        setSaving(false);
      }
    }
  };

  const changeVersionStatus = async (
    version: WorkflowVersion,
    action: 'publish' | 'deprecate',
  ) => {
    if (!selected || selectedWorkflowIdRef.current !== selected.id
      || !requireWorkflowAction(action)) return;
    const contextSequence = authorizationContextSequence.current;
    const workflow = selected;
    if (contextSequence !== authorizationContextSequence.current
      || selectedWorkflowIdRef.current !== workflow.id
      || !requireWorkflowAction(action)) return;
    try {
      await post(
        `${config.baseUrl}/${workflow.id}/versions/${version.id}/${action}`,
        {},
      );
      if (contextSequence !== authorizationContextSequence.current
        || selectedWorkflowIdRef.current !== workflow.id) return;
      message.success(action === 'publish'
        ? t('ai.workflows.message.published', 'Workflow 版本已发布')
        : t('ai.workflows.message.deprecated', 'Workflow 版本已废弃'));
      await Promise.all([loadVersions(workflow), loadWorkflows()]);
    } catch (error) {
      if (contextSequence !== authorizationContextSequence.current) return;
      message.error(resolveApiErrorMessage(
        error,
        t('ai.workflows.error.lifecycle', '版本状态变更失败'),
      ));
    }
  };

  const confirmVersionStatus = (
    version: WorkflowVersion,
    action: 'publish' | 'deprecate',
  ) => {
    if (!requireWorkflowAction(action)) return;
    modal.confirm({
      title: action === 'publish'
        ? t('ai.workflows.version.publish.title', '发布这个 Workflow 版本？')
        : t('ai.workflows.version.deprecate.title', '废弃这个 Workflow 版本？'),
      content: action === 'publish'
        ? t(
          'ai.workflows.version.publish.description',
          '服务端会重新校验 DAG、固定依赖和内容 Hash；通过后它将成为活动版本。',
        )
        : t(
          'ai.workflows.version.deprecate.description',
          '废弃后不能再作为活动版本，新执行不会使用它；已经开始的执行不受影响。',
        ),
      okText: action === 'publish'
        ? t('ai.workflows.action.publish', '发布')
        : t('ai.workflows.action.deprecate', '废弃'),
      okButtonProps: action === 'deprecate' ? {danger: true} : undefined,
      cancelText: t('ai.workflows.action.cancelDialog', '继续编辑'),
      onOk: () => changeVersionStatus(version, action),
    });
  };

  const statusLabel = useCallback((value: string) => ({
    ACTIVE: t('ai.workflows.status.active', '已激活'),
    PUBLISHED: t('ai.workflows.status.published', '已发布'),
    DRAFT: t('ai.workflows.status.draft', '草稿'),
    DISABLED: t('ai.workflows.status.disabled', '已停用'),
    DEPRECATED: t('ai.workflows.status.deprecated', '已废弃'),
    PENDING: t('ai.workflows.status.pending', '等待执行'),
    RUNNING: t('ai.workflows.status.running', '执行中'),
    WAITING: t('ai.workflows.status.waiting', '等待中'),
    WAITING_CHILD: t('ai.workflows.status.waitingChild', '等待子执行'),
    WAITING_HUMAN: t('ai.workflows.status.waitingHuman', '等待人工任务'),
    WAITING_TIMER: t('ai.workflows.status.waitingTimer', '等待定时器'),
    PAUSED: t('ai.workflows.status.paused', '已暂停'),
    COMPENSATING: t('ai.workflows.status.compensating', '补偿中'),
    COMPENSATED: t('ai.workflows.status.compensated', '已补偿'),
    COMPENSATION_FAILED: t('ai.workflows.status.compensationFailed', '补偿失败'),
    SUCCEEDED: t('ai.workflows.status.succeeded', '已成功'),
    FAILED: t('ai.workflows.status.failed', '已失败'),
    CANCELLED: t('ai.workflows.status.cancelled', '已取消'),
    SKIPPED: t('ai.workflows.status.skipped', '已跳过'),
    OPEN: t('ai.workflows.status.open', '待处理'),
    COMPLETED: t('ai.workflows.status.completed', '已完成'),
    TIMED_OUT: t('ai.workflows.status.timedOut', '已超时'),
  }[value] ?? t('ai.workflows.status.unknown', '状态未知')), [t]);

  const definitionColumns = useMemo(() => [
    {
      title: t('ai.workflows.column.code', '代码'),
      dataIndex: 'code',
      width: 180,
    },
    {
      title: t('ai.workflows.column.name', '名称'),
      dataIndex: 'name',
    },
    {
      title: t('ai.workflows.column.scope', '作用域'),
      dataIndex: 'scopeType',
      width: 100,
      render: (value: string) => (
        <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>
          {value === 'TENANT'
            ? t('ai.workflows.scope.tenant', '租户')
            : t('ai.workflows.scope.system', '平台')}
        </Tag>
      ),
    },
    {
      title: t('ai.workflows.column.status', '状态'),
      dataIndex: 'status',
      width: 110,
      render: (value: string) => (
        <Tag color={statusColor[value]}>{statusLabel(value)}</Tag>
      ),
    },
    {
      title: t('ai.workflows.column.action', '操作'),
      width: 190,
      render: (_: unknown, workflow: WorkflowDefinition) => (
        <Space>
          <Button type="link" onClick={(event) => {
            event.stopPropagation();
            selectWorkflow(workflow);
          }}>
            {t('ai.workflows.action.versions', '版本')}
          </Button>
          {canWorkflowAction('edit') && (
            <Button type="link" onClick={(event) => {
              event.stopPropagation();
              openEdit(workflow);
            }}>
              {t('ai.workflows.action.edit', '编辑')}
            </Button>
          )}
          {canWorkflowAction('delete') && (
            <Button
              danger
              type="link"
              onClick={(event) => {
                event.stopPropagation();
                removeDefinition(workflow);
              }}
            >
              {t('ai.workflows.action.delete', '删除')}
            </Button>
          )}
        </Space>
      ),
    },
  ], [
    canWorkflowAction,
    openEdit,
    removeDefinition,
    selectWorkflow,
    statusLabel,
    t,
  ]);

  const versionColumns = useMemo(() => [
    {
      title: t('ai.workflows.column.version', '版本'),
      dataIndex: 'version',
      width: 120,
    },
    {
      title: t('ai.workflows.column.status', '状态'),
      dataIndex: 'status',
      width: 120,
      render: (value: string) => (
        <Tag color={statusColor[value]}>{statusLabel(value)}</Tag>
      ),
    },
    {
      title: t('ai.workflows.column.dependencies', '固定依赖'),
      render: (_: unknown, version: WorkflowVersion) => (
        <Space size={[0, 4]} wrap>
          {(version.dependencies ?? []).map((dependency) => (
            <Tag key={dependency.id}>
              {workflowDependencyTypeLabel(t, dependency.dependencyType)}:{' '}
              {dependency.resourceCode}
              @{dependency.resourceVersionName}
            </Tag>
          ))}
        </Space>
      ),
    },
    {
      title: t('ai.workflows.column.contentHash', '内容 Hash'),
      dataIndex: 'contentHash',
      ellipsis: true,
      width: 180,
    },
    {
      title: t('ai.workflows.column.action', '操作'),
      width: 230,
      render: (_: unknown, version: WorkflowVersion) => (
        <Space>
          <Button type="link" onClick={() => setInspecting(version)}>
            {t('ai.workflows.action.inspect', '查看')}
          </Button>
          {canWorkflowAction('publish') && version.status === 'DRAFT' && (
            <Button
              type="link"
              onClick={() => confirmVersionStatus(version, 'publish')}
            >
              {t('ai.workflows.action.publish', '发布')}
            </Button>
          )}
          {canWorkflowAction('deprecate')
            && version.status === 'PUBLISHED' && (
            <Button
              danger
              type="link"
              onClick={() => confirmVersionStatus(version, 'deprecate')}
            >
              {t('ai.workflows.action.deprecate', '废弃')}
            </Button>
          )}
        </Space>
      ),
    },
  ], [canWorkflowAction, confirmVersionStatus, statusLabel, t]);

  const executionColumns = useMemo(() => [
    {
      title: t('ai.workflows.execution.id', '执行 ID'),
      dataIndex: 'id',
      ellipsis: true,
      width: 220,
      render: (value: string) => <Text copyable>{value}</Text>,
    },
    {
      title: t('ai.workflows.column.status', '状态'),
      dataIndex: 'status',
      width: 150,
      render: (value: string) => (
        <Tag color={statusColor[value]}>{statusLabel(value)}</Tag>
      ),
    },
    {
      title: t('ai.workflows.column.progress', '节点进度'),
      width: 130,
      render: (_: unknown, execution: WorkflowExecution) => (
        <Text>
          {execution.consumedNodeExecutions}
          {' / '}
          {execution.maximumNodeExecutions}
        </Text>
      ),
    },
    {
      title: t('ai.workflows.column.createdAt', '创建时间'),
      dataIndex: 'createdAt',
      width: 190,
    },
    {
      title: t('ai.workflows.column.action', '操作'),
      width: 100,
      render: (_: unknown, execution: WorkflowExecution) => (
        <Button
          type="link"
          onClick={() => void inspectExecution(execution.id)}
        >
          {t('ai.workflows.action.inspect', '查看')}
        </Button>
      ),
    },
  ], [inspectExecution, statusLabel, t]);

  const executionTerminal = inspectingExecution
    ? ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(
      inspectingExecution.status,
    )
    : false;
  const designerSpec = objectValue(designerManifest?.spec);
  const designerBudgets = objectValue(designerSpec.budgets);
  const designerFailurePolicy = objectValue(designerSpec.failurePolicy);
  const designerNodes = Array.isArray(designerSpec.nodes) ? designerSpec.nodes : [];
  const designerEdges = Array.isArray(designerSpec.edges) ? designerSpec.edges : [];
  const previousSpec = objectValue(versions[0]?.manifest?.spec);
  const previousNodes = Array.isArray(previousSpec.nodes) ? previousSpec.nodes : [];
  const previousEdges = Array.isArray(previousSpec.edges) ? previousSpec.edges : [];
  const designerDependencyCount = designerNodes.filter((node) => {
    const type = objectValue(node).type;
    return type === 'agent' || type === 'skill';
  }).length;

  return (
    <div style={{display: 'flex', flexDirection: 'column', gap: 16}}>
      <Alert
        showIcon
        type="info"
        closable
        message={t(
          'ai.workflows.notice.title',
          '持久化 Agent Workflow',
        )}
        description={t(
          'ai.workflows.notice.description',
          '版本固定 Agent 与 Skill，工作流只接受有界声明式 DAG，不执行嵌入代码。',
        )}
      />
      {permissionsLoading && (
        <Alert
          showIcon
          closable
          type="info"
          message={t(
            'ai.workflows.permissions.loading.title',
            '正在加载 Workflow 操作权限',
          )}
          description={t(
            'ai.workflows.permissions.loading.description',
            '读取功能保持可用；权限确认前，新增、修改和执行等操作暂时隐藏。',
          )}
        />
      )}
      {permissionsError && (
        <Alert
          showIcon
          type="error"
          message={t(
            'ai.workflows.permissions.error.title',
            'Workflow 操作权限加载失败',
          )}
          description={t(
            'ai.workflows.permissions.error.description',
            '你仍可查看当前页面；为避免误操作，所有变更操作已隐藏。请重试加载权限。',
          )}
          action={(
            <Button size="small" onClick={() => void loadPermissions()}>
              {t('ai.workflows.permissions.retry', '重试')}
            </Button>
          )}
        />
      )}
      <Card
        title={t('ai.workflows.title', 'Agent Workflow')}
        extra={(
          <Space>
            <Button onClick={() => void loadWorkflows()}>
              {t('ai.workflows.action.refresh', '刷新')}
            </Button>
            {canWorkflowAction('create') && (
              <Button type="primary" onClick={openCreate}>
                {t('ai.workflows.action.create', '新增 Workflow')}
              </Button>
            )}
          </Space>
        )}
      >
        <DataTable
          rowKey="id"
          loading={loading}
          columns={definitionColumns}
          dataSource={workflows}
          pagination={{
            current: workflowPage,
            pageSize: workflowPageSize,
            total: workflowTotal,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: ['10', '20', '50'],
            showTotal: (total) => t(
              'ai.workflows.pagination.total',
              '共 {total} 个 Workflow',
              {total},
            ),
          }}
          scroll={{x: 900}}
          onChange={(pagination) => {
            setWorkflowPage(pagination.current ?? 1);
            setWorkflowPageSize(pagination.pageSize ?? 10);
          }}
          rowClassName={(workflow) =>
            workflow.id === selected?.id ? 'ant-table-row-selected' : ''}
          onRow={(workflow) => ({
            tabIndex: 0,
            'aria-selected': workflow.id === selected?.id,
            onClick: () => selectWorkflow(workflow),
            onKeyDown: (event) => {
              if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                selectWorkflow(workflow);
              }
            },
          })}
        />
      </Card>
      {!selected ? (
        <Card>
          <Empty
            description={t(
              'ai.workflows.workspace.empty',
              '选择一个 Workflow 后设计版本并查看执行记录',
            )}
          >
            {canWorkflowAction('create') && (
              <Button type="primary" onClick={openCreate}>
                {t('ai.workflows.action.create', '新增 Workflow')}
              </Button>
            )}
          </Empty>
        </Card>
      ) : (
        <Card size="small">
          <Descriptions
            size="small"
            column={{xs: 1, sm: 2, lg: 4}}
            items={[
              {
                key: 'name',
                label: t('ai.workflows.field.name', '名称'),
                children: selected.name,
              },
              {
                key: 'code',
                label: t('ai.workflows.field.code', '代码'),
                children: <Text code copyable>{selected.code}</Text>,
              },
              {
                key: 'status',
                label: t('ai.workflows.column.status', '状态'),
                children: (
                  <Tag color={statusColor[selected.status]}>
                    {statusLabel(selected.status)}
                  </Tag>
                ),
              },
              {
                key: 'activeVersion',
                label: t('ai.workflows.workspace.activeVersion', '活动版本'),
                children: selected.activeVersionId
                  ? <Text code copyable>{selected.activeVersionId}</Text>
                  : t('ai.workflows.workspace.noActiveVersion', '尚未发布版本'),
              },
            ]}
          />
          <Tabs
            activeKey={workspaceSection}
            onChange={setWorkspaceSection}
            style={{marginBottom: -16}}
            items={[
              {
                key: 'versions',
                label: (
                  <Space size={4}>
                    {t('ai.workflows.versions.title', '不可变版本')}
                    <Tag>{versionTotal}</Tag>
                  </Space>
                ),
              },
              {
                key: 'executions',
                label: (
                  <Space size={4}>
                    {t('ai.workflows.executions.title', '执行记录')}
                    <Tag>{executionTotal}</Tag>
                  </Space>
                ),
              },
            ]}
          />
        </Card>
      )}
      {selected && workspaceSection === 'executions' && (
      <Card
        title={selected
          ? `${t('ai.workflows.executions.title', '执行记录')} · ${selected.name}`
          : t('ai.workflows.executions.empty', '选择 Workflow 后查看执行')}
        extra={selected && (
          <Space>
            <Button onClick={() => void loadExecutions(selected)}>
              {t('ai.workflows.action.refresh', '刷新')}
            </Button>
            {canWorkflowAction('execute') && (
              <Button
                type="primary"
                disabled={!selected.activeVersionId}
                onClick={() => void openExecution()}
              >
                {t('ai.workflows.action.execute', '执行')}
              </Button>
            )}
          </Space>
        )}
      >
        <DataTable
          rowKey="id"
          loading={executionLoading}
          columns={executionColumns}
          dataSource={executions}
          pagination={{
            current: executionPage,
            pageSize: executionPageSize,
            total: executionTotal,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: ['10', '20', '50'],
            showTotal: (total) => t(
              'ai.workflows.pagination.executionTotal',
              '共 {total} 条执行',
              {total},
            ),
          }}
          scroll={{x: 900}}
          onChange={(pagination) => {
            setExecutionPage(pagination.current ?? 1);
            setExecutionPageSize(pagination.pageSize ?? 10);
          }}
          locale={{
            emptyText: t('ai.workflows.executions.noData', '暂无执行记录'),
          }}
        />
      </Card>
      )}
      {selected && workspaceSection === 'versions' && (
      <Card
        title={selected
          ? `${t('ai.workflows.versions.title', '不可变版本')} · ${selected.name}`
          : t('ai.workflows.versions.empty', '选择 Workflow 后管理版本')}
        extra={selected && canWorkflowAction('createVersion') && (
          <Button type="primary" onClick={openCreateVersion}>
            {t('ai.workflows.action.createVersion', '创建版本')}
          </Button>
        )}
      >
        <DataTable
          rowKey="id"
          loading={versionLoading}
          columns={versionColumns}
          dataSource={versions}
          pagination={{
            current: versionPage,
            pageSize: versionPageSize,
            total: versionTotal,
            showSizeChanger: true,
            showQuickJumper: true,
            pageSizeOptions: ['10', '20', '50'],
            showTotal: (total) => t(
              'ai.workflows.pagination.versionTotal',
              '共 {total} 个版本',
              {total},
            ),
          }}
          scroll={{x: 980}}
          onChange={(pagination) => {
            setVersionPage(pagination.current ?? 1);
            setVersionPageSize(pagination.pageSize ?? 10);
          }}
          locale={{
            emptyText: t('ai.workflows.versions.noData', '暂无版本'),
          }}
        />
      </Card>
      )}

      <Modal
        open={definitionOpen && canWorkflowAction(editing ? 'edit' : 'create')}
        title={editing
          ? t('ai.workflows.dialog.edit', '编辑 Workflow')
          : t('ai.workflows.dialog.create', '新增 Workflow')}
        confirmLoading={saving}
        onCancel={() => setDefinitionOpen(false)}
        onOk={() => void saveDefinition()}
      >
        <Form form={form} layout="vertical">
          <Form.Item
            name="code"
            label={t('ai.workflows.field.code', '代码')}
            rules={[{required: true}]}
          >
            <Input disabled={Boolean(editing)} maxLength={64} />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('ai.workflows.field.name', '名称')}
            rules={[{required: true}]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('ai.workflows.field.description', '说明')}
          >
            <TextArea rows={3} maxLength={512} showCount />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t('ai.workflows.field.enabled', '启用')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        width={680}
        open={executionOpen && canWorkflowAction('execute')}
        title={t('ai.workflows.action.execute', '执行 Workflow')}
        footer={null}
        onCancel={() => {
          executionSchemaRequestSequence.current += 1;
          setExecutionOpen(false);
        }}
        destroyOnHidden
      >
        <SchemaExecutionForm
          schema={executionInputSchema}
          loading={executionSchemaLoading}
          submitting={saving}
          submitText={t('ai.workflows.action.execute', '执行 Workflow')}
          onSubmit={startExecution}
        />
      </Modal>

      <Modal
        width="96vw"
        open={versionOpen && canWorkflowAction('createVersion')}
        title={t('ai.workflows.version.create', '创建不可变 Workflow 版本')}
        confirmLoading={saving}
        onCancel={() => closeVersionEditor()}
        onOk={() => void saveVersion()}
        okText={t('ai.workflows.action.createVersion', '创建版本')}
        cancelText={t('ai.workflows.action.close', '关闭')}
        destroyOnHidden
      >
        <Alert
          showIcon
          type="warning"
          message={t(
            'ai.workflows.version.warning',
            '创建后 Manifest 不可修改，发布时会再次校验固定依赖。',
          )}
          style={{marginBottom: 16}}
        />
        {designerDraftRestored && (
          <Alert
            showIcon
            closable
            type="success"
            message={t(
              'ai.workflows.version.draftRestored',
              '已恢复当前浏览器中自动保存的画布',
            )}
            style={{marginBottom: 16}}
          />
        )}
        {dependencyValidationError && (
          <Alert
            showIcon
            closable
            type="error"
            message={dependencyValidationError}
            onClose={() => setDependencyValidationError(undefined)}
            style={{marginBottom: 16}}
          />
        )}
        <Form form={versionForm} layout="vertical">
          <Space wrap align="start" style={{width: '100%'}}>
            <Form.Item
              name="version"
              label={t('ai.workflows.field.version', '语义版本')}
              rules={[{
                required: true,
                pattern: /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/,
              }]}
            >
              <Input maxLength={64} style={{width: 220}} onBlur={syncManifestVersion} />
            </Form.Item>
            <Form.Item label={t('ai.workflows.field.catalog', '可用已发布依赖')}>
              <Text type="secondary">
                {t(
                  'ai.workflows.field.catalogCount',
                  '当前可用：{agents} 个 Agent 版本，{skills} 个 Skill 版本',
                  {
                    agents: dependencyDirectory.getQuery('AGENT').items.length,
                    skills: dependencyDirectory.getQuery('SKILL').items.length,
                  },
                )}
              </Text>
            </Form.Item>
          </Space>
          <Tabs
            activeKey={versionEditorMode}
            onChange={(key) => {
              if (key === 'visual') {
                const parsed = parseWorkflowManifest(
                  versionForm.getFieldValue('manifest'),
                );
                if (!parsed) {
                  message.error(t(
                    'ai.workflows.validation.manifest',
                    'Manifest 必须是有效 JSON',
                  ));
                  return;
                }
                setDesignerManifest(parsed);
              }
              setVersionEditorMode(key);
            }}
            items={[
              {
                key: 'visual',
                label: t('ai.workflows.version.visual', '可视化编排'),
                children: selected && designerManifest ? (
                  <WorkflowDesigner
                    manifest={designerManifest}
                    directory={dependencyDirectory}
                    storageKey={`simplepoint.workflow.designer.${selected.id}`}
                    onChange={updateDesignerManifest}
                  />
                ) : null,
              },
              {
                key: 'settings',
                label: t('ai.workflows.version.settings', '契约与治理'),
                children: designerManifest ? (
                  <div style={{maxWidth: 980}}>
                    <Alert
                      showIcon
                      type="info"
                      message={t(
                        'ai.workflows.version.settingsNotice',
                        'Schema、预算和失败策略属于不可变版本，执行开始后不会随页面配置变化。',
                      )}
                      style={{marginBottom: 16}}
                    />
                    <Descriptions
                      bordered
                      size="small"
                      column={{xs: 1, md: 2}}
                      items={[
                        {
                          key: 'maximumDurationSeconds',
                          label: t('ai.workflows.field.maximumDurationSeconds', '最长执行时间（秒）'),
                          children: (
                            <InputNumber
                              min={1}
                              max={604800}
                              value={Number(designerBudgets.maximumDurationSeconds ?? 3600)}
                              onChange={(value) => updateDesignerSpec({
                                budgets: {...designerBudgets, maximumDurationSeconds: value ?? 3600},
                              })}
                            />
                          ),
                        },
                        {
                          key: 'maximumNodeExecutions',
                          label: t('ai.workflows.field.maximumNodeExecutions', '最大节点执行数'),
                          children: (
                            <InputNumber
                              min={1}
                              max={4096}
                              value={Number(designerBudgets.maximumNodeExecutions ?? 64)}
                              onChange={(value) => updateDesignerSpec({
                                budgets: {...designerBudgets, maximumNodeExecutions: value ?? 64},
                              })}
                            />
                          ),
                        },
                        {
                          key: 'maximumParallelism',
                          label: t('ai.workflows.field.maximumParallelism', '最大并行度'),
                          children: (
                            <InputNumber
                              min={1}
                              max={64}
                              value={Number(designerBudgets.maximumParallelism ?? 4)}
                              onChange={(value) => updateDesignerSpec({
                                budgets: {...designerBudgets, maximumParallelism: value ?? 4},
                              })}
                            />
                          ),
                        },
                        {
                          key: 'failureMode',
                          label: t('ai.workflows.field.failureMode', '节点失败策略'),
                          children: (
                            <Select
                              style={{width: 180}}
                              value={String(designerFailurePolicy.mode ?? 'FAIL_FAST')}
                              options={[
                                {
                                  value: 'FAIL_FAST',
                                  label: t('ai.workflows.field.failFast', '立即失败'),
                                },
                                {
                                  value: 'CONTINUE',
                                  label: t('ai.workflows.field.continueOnFailure', '继续可运行节点'),
                                },
                              ]}
                              onChange={(value) => updateDesignerSpec({
                                failurePolicy: {...designerFailurePolicy, mode: value},
                              })}
                            />
                          ),
                        },
                        {
                          key: 'compensationMode',
                          label: t('ai.workflows.field.compensationMode', '补偿策略'),
                          children: (
                            <Select
                              style={{width: 220}}
                              value={String(designerFailurePolicy.compensation ?? 'NONE')}
                              options={[
                                {
                                  value: 'NONE',
                                  label: t('ai.workflows.field.noCompensation', '不自动补偿'),
                                },
                                {
                                  value: 'REVERSE_SUCCEEDED',
                                  label: t('ai.workflows.field.reverseCompensation', '逆序补偿已成功节点'),
                                },
                              ]}
                              onChange={(value) => updateDesignerSpec({
                                failurePolicy: {...designerFailurePolicy, compensation: value},
                              })}
                            />
                          ),
                        },
                        {
                          key: 'output',
                          label: t('ai.workflows.field.outputReference', '工作流输出引用'),
                          children: (
                            <Input
                              style={{width: 280}}
                              value={String(objectValue(designerSpec.output).$ref ?? '')}
                              placeholder="nodes.done.output"
                              onChange={(event) => updateDesignerSpec(event.target.value.trim()
                                ? {output: {$ref: event.target.value.trim()}}
                                : {output: undefined})}
                            />
                          ),
                        },
                      ]}
                    />
                    <div style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(auto-fit, minmax(320px, 1fr))',
                      gap: 16,
                      marginTop: 16,
                    }}>
                      <Card size="small" title={t('ai.workflows.field.inputSchema', '输入 Schema')}>
                        <JsonSchemaObjectEditor
                          value={pretty(designerSpec.inputSchema ?? {
                            type: 'object', additionalProperties: true,
                          })}
                          onChange={(value) => {
                            const schema = parseWorkflowManifest(value);
                            if (schema) updateDesignerSpec({inputSchema: schema});
                          }}
                          onValidityChange={setWorkflowInputSchemaValid}
                        />
                      </Card>
                      <Card size="small" title={t('ai.workflows.field.outputSchema', '输出 Schema')}>
                        <JsonSchemaObjectEditor
                          value={pretty(designerSpec.outputSchema ?? {
                            type: 'object', additionalProperties: true,
                          })}
                          onChange={(value) => {
                            const schema = parseWorkflowManifest(value);
                            if (schema) updateDesignerSpec({outputSchema: schema});
                          }}
                          onValidityChange={setWorkflowOutputSchemaValid}
                        />
                      </Card>
                    </div>
                  </div>
                ) : null,
              },
              {
                key: 'review',
                label: t('ai.workflows.version.review', '确认创建'),
                children: designerManifest ? (
                  <Space direction="vertical" size={16} style={{width: '100%'}}>
                    <Alert
                      showIcon
                      type="warning"
                      message={t(
                        'ai.workflows.version.reviewNotice',
                        '创建后 Manifest 和依赖不可修改；版本仍是草稿，需要单独发布才会成为活动版本。',
                      )}
                    />
                    <Descriptions bordered size="small" column={{xs: 1, md: 3}}>
                      <Descriptions.Item label={t('ai.workflows.field.version', '语义版本')}>
                        {versionForm.getFieldValue('version')}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.workflows.version.nodeCount', '节点数')}>
                        {designerNodes.length}
                        {previousNodes.length > 0 && ` (${designerNodes.length - previousNodes.length >= 0 ? '+' : ''}${designerNodes.length - previousNodes.length})`}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.workflows.version.edgeCount', '连线数')}>
                        {designerEdges.length}
                        {previousEdges.length > 0 && ` (${designerEdges.length - previousEdges.length >= 0 ? '+' : ''}${designerEdges.length - previousEdges.length})`}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.workflows.column.dependencies', '固定依赖')}>
                        {designerDependencyCount}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.workflows.field.maximumNodeExecutions', '最大节点执行数')}>
                        {String(designerBudgets.maximumNodeExecutions ?? 64)}
                      </Descriptions.Item>
                      <Descriptions.Item label={t('ai.workflows.field.maximumParallelism', '最大并行度')}>
                        {String(designerBudgets.maximumParallelism ?? 4)}
                      </Descriptions.Item>
                    </Descriptions>
                  </Space>
                ) : null,
              },
              {
                key: 'json',
                label: t('ai.workflows.version.advancedJson', '高级 JSON'),
                children: (
                  <>
                    <Alert
                      showIcon
                      type="warning"
                      message={t(
                        'ai.workflows.version.jsonWarning',
                        '高级模式适合维护可视化面板暂未覆盖的声明；返回画布前必须是有效 Manifest JSON。',
                      )}
                      style={{marginBottom: 12}}
                    />
                    <Form.Item
                      name="manifest"
                      label={t('ai.workflows.version.manifest', 'AgentWorkflow Manifest')}
                      rules={[{required: true}]}
                    >
                      <TextArea rows={28} spellCheck={false} style={{fontFamily: 'monospace'}} />
                    </Form.Item>
                  </>
                ),
              },
            ]}
          />
          <Button danger onClick={() => {
            if (!requireWorkflowAction('createVersion')) return;
            modal.confirm({
              title: t('ai.workflows.version.reset.title', '重置为示例工作流？'),
              content: t(
                'ai.workflows.version.reset.description',
                '当前画布会被替换，但仍可在画布中使用撤销恢复本次会话内的修改。',
              ),
              okButtonProps: {danger: true},
              onOk: refreshSampleVersion,
            });
          }}>
            {t('ai.workflows.action.resetSample', '重置为示例')}
          </Button>
        </Form>
      </Modal>

      <Drawer
        width={760}
        open={Boolean(inspecting)}
        title={t('ai.workflows.version.details', 'Workflow 版本详情')}
        onClose={() => setInspecting(undefined)}
      >
        {inspecting && (
          <Space direction="vertical" size={16} style={{display: 'flex'}}>
            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label={t('ai.workflows.column.version', '版本')}>
                {inspecting.version}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.workflows.column.status', '状态')}>
                <Tag color={statusColor[inspecting.status]}>
                  {statusLabel(inspecting.status)}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.workflows.version.schemaVersion', 'Schema 版本')}>
                {inspecting.manifestSchemaVersion}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.workflows.column.contentHash', '内容 Hash')}>
                <Text copyable>{inspecting.contentHash}</Text>
              </Descriptions.Item>
            </Descriptions>
            <Card
              size="small"
              title={t('ai.workflows.column.dependencies', '固定依赖')}
            >
              {(inspecting.dependencies ?? []).map((dependency) => (
                <Paragraph key={dependency.id} copyable>
                  {dependency.nodeId} ·{' '}
                  {workflowDependencyTypeLabel(t, dependency.dependencyType)} ·
                  {' '}{dependency.resourceCode}
                  @{dependency.resourceVersionName}
                  {' · '}{dependency.resourceVersionId}
                </Paragraph>
              ))}
            </Card>
            <Card size="small" title={t('ai.workflows.version.manifest', 'AgentWorkflow Manifest')}>
              <Paragraph>
                <pre style={{whiteSpace: 'pre-wrap', margin: 0}}>
                  {pretty(inspecting.manifest)}
                </pre>
              </Paragraph>
            </Card>
          </Space>
        )}
      </Drawer>

      <Drawer
        width={980}
        open={Boolean(inspectingExecution)}
        title={t('ai.workflows.execution.details', 'Workflow 执行详情')}
        onClose={() => {
          executionDetailRequestSequence.current += 1;
          eventRequestSequence.current += 1;
          setInspectingExecution(undefined);
          setExecutionEvents([]);
          setEventNextSequence(0);
          setEventHasMore(false);
        }}
        extra={inspectingExecution && (
          <Space>
            {canWorkflowAction('resume')
              && inspectingExecution.status === 'PAUSED' ? (
              <Button
                loading={intervening === 'resume'}
                disabled={Boolean(intervening)}
                onClick={() => void controlExecution('resume')}
              >
                {t('ai.workflows.action.resume', '恢复')}
              </Button>
            ) : canWorkflowAction('pause') && !executionTerminal && (
              <Button
                loading={intervening === 'pause'}
                disabled={Boolean(intervening)}
                onClick={() => void controlExecution('pause')}
              >
                {t('ai.workflows.action.pause', '暂停')}
              </Button>
            )}
            {canWorkflowAction('cancel') && !executionTerminal && (
              <Button
                danger
                loading={intervening === 'cancel'}
                disabled={Boolean(intervening)}
                onClick={() => void controlExecution('cancel')}
              >
                {t('ai.workflows.action.cancel', '取消')}
              </Button>
            )}
            <Button
              loading={executionLoading}
              onClick={() => void inspectExecution(inspectingExecution.id)}
            >
              {t('ai.workflows.action.refresh', '刷新')}
            </Button>
          </Space>
        )}
      >
        {inspectingExecution && (
          <Space direction="vertical" size={16} style={{display: 'flex'}}>
            <Descriptions bordered column={2} size="small">
              <Descriptions.Item label={t('ai.workflows.execution.id', '执行 ID')}>
                <Text copyable>{inspectingExecution.id}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.workflows.column.status', '状态')}>
                <Tag color={statusColor[inspectingExecution.status]}>
                  {statusLabel(inspectingExecution.status)}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.workflows.column.version', '版本')}>
                <Text copyable>
                  {inspectingExecution.workflowVersionId}
                </Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.workflows.column.contentHash', '内容 Hash')}>
                <Text copyable>
                  {inspectingExecution.workflowVersionContentHash}
                </Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.workflows.execution.nodeBudget', '节点预算')}>
                {inspectingExecution.consumedNodeExecutions}
                {' / '}
                {inspectingExecution.maximumNodeExecutions}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.workflows.field.maximumParallelism', '最大并行度')}>
                {inspectingExecution.maximumParallelism}
              </Descriptions.Item>
              {shouldDisplayExecutionDiagnostic(
                'workflowExecution',
                inspectingExecution.status,
                inspectingExecution,
              ) && (
                <Descriptions.Item label={t('ai.workflows.execution.failure', '失败原因')} span={2}>
                  {renderDiagnostic('workflowExecution', inspectingExecution)}
                </Descriptions.Item>
              )}
            </Descriptions>
            <Card
              size="small"
              title={t('ai.workflows.execution.nodes', '节点检查点')}
            >
              <DataTable
                rowKey="id"
                size="small"
                pagination={false}
                dataSource={inspectingExecution.nodes ?? []}
                columns={[
                  {title: t('ai.workflows.execution.node', '节点'), dataIndex: 'nodeId'},
                  {
                    title: t('ai.workflows.execution.type', '类型'),
                    dataIndex: 'nodeType',
                    width: 120,
                    render: (value: string) => t(
                      `ai.workflows.designer.node.${value.toLowerCase()}`,
                      value,
                    ),
                  },
                  {
                    title: t('ai.workflows.column.status', '状态'),
                    dataIndex: 'status',
                    width: 150,
                    render: (value: string) => (
                      <Tag color={statusColor[value]}>{statusLabel(value)}</Tag>
                    ),
                  },
                  {
                    title: t('ai.workflows.execution.attempts', '尝试次数'),
                    dataIndex: 'attemptCount',
                    width: 90,
                  },
                  {
                    title: t('ai.workflows.execution.failure', '失败原因'),
                    render: (_: unknown, node: WorkflowNodeExecution) =>
                      shouldDisplayExecutionDiagnostic(
                        'workflowNode',
                        node.status,
                        node,
                      )
                      ? (
                          <Space direction="vertical" size={4}>
                            {renderDiagnostic('workflowNode', node)}
                            <Space size={4} wrap>
                              <Text type="secondary">
                                {t(
                                  'ai.workflows.execution.checkpointId',
                                  '检查点 ID',
                                )}:
                              </Text>
                              <Text copyable code>{node.id}</Text>
                            </Space>
                            {node.childExecutionId && (
                              <Space size={4} wrap>
                                <Text type="secondary">
                                  {t(
                                    'ai.workflows.execution.childExecutionId',
                                    '子执行 ID',
                                  )}:
                                </Text>
                                <Text copyable code>
                                  {node.childExecutionId}
                                </Text>
                              </Space>
                            )}
                            {node.compensationExecutionId && (
                              <Space size={4} wrap>
                                <Text type="secondary">
                                  {t(
                                    'ai.workflows.execution.compensationExecutionId',
                                    '补偿执行 ID',
                                  )}:
                                </Text>
                                <Text copyable code>
                                  {node.compensationExecutionId}
                                </Text>
                              </Space>
                            )}
                          </Space>
                        )
                      : '-',
                  },
                ]}
                expandable={{
                  expandedRowRender: (node) => (
                    <Collapse
                      size="small"
                      items={canDisplayExecutionOutput(node.status)
                        ? [
                            {
                              key: 'input',
                              label: t('ai.workflows.execution.input', '输入'),
                              children: <pre style={{whiteSpace: 'pre-wrap'}}>{pretty(node.input)}</pre>,
                            },
                            {
                              key: 'output',
                              label: t('ai.workflows.execution.output', '输出'),
                              children: <pre style={{whiteSpace: 'pre-wrap'}}>{pretty(node.output)}</pre>,
                            },
                          ]
                        : [
                            {
                              key: 'input',
                              label: t('ai.workflows.execution.input', '输入'),
                              children: <pre style={{whiteSpace: 'pre-wrap'}}>{pretty(node.input)}</pre>,
                            },
                          ]}
                    />
                  ),
                  rowExpandable: (node) => node.input !== undefined
                    || (canDisplayExecutionOutput(node.status)
                      && node.output !== undefined),
                }}
              />
            </Card>
            <Card
              size="small"
              title={t('ai.workflows.execution.humanTasks', '人工任务')}
            >
              <DataTable
                rowKey="id"
                size="small"
                pagination={false}
                dataSource={inspectingExecution.humanTasks ?? []}
                columns={[
                  {title: t('ai.workflows.execution.node', '节点'), dataIndex: 'nodeId', width: 120},
                  {title: t('ai.workflows.execution.taskTitle', '任务'), dataIndex: 'title'},
                  {
                    title: t('ai.workflows.column.status', '状态'),
                    dataIndex: 'status',
                    width: 120,
                    render: (value: string) => (
                      <Tag color={statusColor[value]}>{statusLabel(value)}</Tag>
                    ),
                  },
                  {title: t('ai.workflows.execution.dueAt', '截止时间'), dataIndex: 'dueAt', width: 190},
                  ...(canWorkflowAction('respondHumanTask') ? [{
                    title: t('ai.workflows.column.action', '操作'),
                    width: 100,
                    render: (_: unknown, task: WorkflowHumanTask) =>
                      task.status === 'OPEN' ? (
                        <Button
                          type="link"
                          onClick={() => openHumanTask(task)}
                        >
                          {t('ai.workflows.action.respond', '处理')}
                        </Button>
                      ) : null,
                  }] : []),
                ]}
              />
            </Card>
            <Card
              size="small"
              title={t('ai.workflows.execution.events', '执行事件')}
            >
              <Timeline
                items={executionEvents.map((event) => ({
                  color: workflowEventColor(event.type),
                  children: (
                    <Space direction="vertical" size={0}>
                      <Text strong>
                        #{event.sequence}{' '}
                        {t(
                          `ai.workflows.event.${event.type}`,
                          t('ai.workflows.event.unknown', '未知事件'),
                        )}
                      </Text>
                      <Text type="secondary">{event.occurredAt}</Text>
                      {event.payload && Object.keys(event.payload).length > 0 && (
                        <Collapse
                          ghost
                          size="small"
                          items={[{
                            key: 'payload',
                            label: t('ai.workflows.execution.eventPayload', '事件数据'),
                            children: <pre style={{whiteSpace: 'pre-wrap'}}>{pretty(event.payload)}</pre>,
                          }]}
                        />
                      )}
                    </Space>
                  ),
                }))}
              />
              {eventHasMore && (
                <Button onClick={() => void loadLaterEvents()}>
                  {t('ai.workflows.execution.loadMoreEvents', '加载更多事件')}
                </Button>
              )}
            </Card>
          </Space>
        )}
      </Drawer>

      <Modal
        open={Boolean(respondingTask)
          && canWorkflowAction('respondHumanTask')}
        title={respondingTask?.title}
        footer={null}
        onCancel={() => setRespondingTask(undefined)}
        destroyOnHidden
      >
        {respondingTask && canWorkflowAction('respondHumanTask') && (
          <Space direction="vertical" size={12} style={{display: 'flex'}}>
            {respondingTask.description && (
              <Text>{respondingTask.description}</Text>
            )}
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label={t('ai.workflows.execution.context', '任务上下文')}>
                <Collapse
                  ghost
                  size="small"
                  items={[{
                    key: 'context',
                    label: t('ai.workflows.execution.viewContext', '查看上下文'),
                    children: <pre style={{whiteSpace: 'pre-wrap', margin: 0}}>
                      {pretty(respondingTask.context)}
                    </pre>,
                  }]}
                />
              </Descriptions.Item>
            </Descriptions>
            <SchemaExecutionForm
              schema={respondingTask.inputSchema}
              submitting={saving}
              submitText={t('ai.workflows.action.respondHumanTask', '提交响应')}
              onSubmit={respondHumanTask}
            />
          </Space>
        )}
      </Modal>
    </div>
  );
};

export default Workflows;
