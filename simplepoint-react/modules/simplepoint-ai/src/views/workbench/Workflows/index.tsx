import api from '@/api';
import {del, get, post, put} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Space,
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

type VersionedResource = {
  id: string;
  code: string;
  name: string;
  activeVersionId?: string;
};

type ResourceVersion = {
  id: string;
  version: string;
  status: string;
};

type DependencyOption = {
  kind: 'agent' | 'skill';
  resourceId: string;
  resourceVersionId: string;
  label: string;
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

type WorkflowNodeExecution = {
  id: string;
  nodeId: string;
  nodeType: string;
  status: string;
  attemptCount: number;
  childExecutionId?: string;
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

type ExecutionFormValues = {
  idempotencyKey: string;
  input: string;
};

type HumanTaskFormValues = {
  output: string;
};

const statusColor: Record<string, string> = {
  ACTIVE: 'success',
  PUBLISHED: 'success',
  DRAFT: 'default',
  DISABLED: 'warning',
  DEPRECATED: 'warning',
  PENDING: 'default',
  RUNNING: 'processing',
  WAITING_CHILD: 'processing',
  WAITING_HUMAN: 'warning',
  WAITING_TIMER: 'processing',
  PAUSED: 'warning',
  COMPENSATING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'error',
  CANCELLED: 'default',
  OPEN: 'warning',
  COMPLETED: 'success',
  TIMED_OUT: 'error',
};

const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  return fallback;
};

const pretty = (value: unknown) => JSON.stringify(value, null, 2);

const sampleManifest = (
  code: string,
  version: string,
  options: DependencyOption[],
) => {
  const agent = options.find((option) => option.kind === 'agent');
  const skill = options.find((option) => option.kind === 'skill');
  const nodes: Record<string, unknown>[] = [];
  if (agent) {
    nodes.push({
      id: 'agent',
      type: 'agent',
      name: agent.label,
      agentId: agent.resourceId,
      versionId: agent.resourceVersionId,
      input: {$ref: 'input'},
    });
  }
  if (skill) {
    nodes.push({
      id: 'skill',
      type: 'skill',
      name: skill.label,
      skillId: skill.resourceId,
      versionId: skill.resourceVersionId,
      input: {$ref: agent ? 'nodes.agent.output' : 'input'},
    });
  }
  nodes.push({
    id: 'review',
    type: 'human',
    title: 'Review result',
    description: 'Confirm that the generated result may continue.',
    inputSchema: {type: 'object', additionalProperties: true},
    timeoutSeconds: 300,
    timeoutAction: 'FAIL',
  });
  nodes.push({id: 'done', type: 'end'});
  const edges = nodes.slice(0, -1).map((node, index) => ({
    from: node.id,
    to: nodes[index + 1].id,
  }));
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
      edges,
      output: {$ref: 'nodes.done.output'},
    },
  };
};

const Workflows = () => {
  const config = api['ai-workbench.workflows'];
  const {ensure, locale, t} = useI18n();
  const [form] = Form.useForm<WorkflowFormValues>();
  const [versionForm] = Form.useForm<VersionFormValues>();
  const [executionForm] = Form.useForm<ExecutionFormValues>();
  const [humanTaskForm] = Form.useForm<HumanTaskFormValues>();
  const [workflows, setWorkflows] = useState<WorkflowDefinition[]>([]);
  const [versions, setVersions] = useState<WorkflowVersion[]>([]);
  const [executions, setExecutions] = useState<WorkflowExecution[]>([]);
  const [executionEvents, setExecutionEvents] =
    useState<WorkflowExecutionEvent[]>([]);
  const [dependencyOptions, setDependencyOptions] =
    useState<DependencyOption[]>([]);
  const [selected, setSelected] = useState<WorkflowDefinition>();
  const [editing, setEditing] = useState<WorkflowDefinition>();
  const [inspecting, setInspecting] = useState<WorkflowVersion>();
  const [inspectingExecution, setInspectingExecution] =
    useState<WorkflowExecution>();
  const [respondingTask, setRespondingTask] =
    useState<WorkflowHumanTask>();
  const [definitionOpen, setDefinitionOpen] = useState(false);
  const [versionOpen, setVersionOpen] = useState(false);
  const [executionOpen, setExecutionOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [versionLoading, setVersionLoading] = useState(false);
  const [executionLoading, setExecutionLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  const loadWorkflows = useCallback(async () => {
    setLoading(true);
    try {
      const page = await get<Page<WorkflowDefinition>>(
        config.baseUrl,
        {page: 0, size: 500, sort: 'createdAt,desc'},
      );
      setWorkflows(page.content ?? []);
      setSelected((current) => current
        ? (page.content ?? []).find((item) => item.id === current.id)
        : undefined);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.load', 'Workflow 列表加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [config.baseUrl, t]);

  const loadVersions = useCallback(async (
    workflow?: WorkflowDefinition,
  ) => {
    if (!workflow) {
      setVersions([]);
      return;
    }
    setVersionLoading(true);
    try {
      const page = await get<Page<WorkflowVersion>>(
        `${config.baseUrl}/${workflow.id}/versions`,
        {page: 0, size: 500, sort: 'createdAt,desc'},
      );
      setVersions(page.content ?? []);
    } catch (error) {
      setVersions([]);
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.loadVersions', 'Workflow 版本加载失败'),
      ));
    } finally {
      setVersionLoading(false);
    }
  }, [config.baseUrl, t]);

  const loadDependencyOptions = useCallback(async () => {
    try {
      const [agentPage, skillPage] = await Promise.all([
        get<Page<VersionedResource>>(
          config.agentsUrl,
          {page: 0, size: 500, sort: 'createdAt,desc'},
        ),
        get<Page<VersionedResource>>(
          config.skillsUrl,
          {page: 0, size: 500, sort: 'createdAt,desc'},
        ),
      ]);
      const resources = [
        ...(agentPage.content ?? []).map((resource) => ({
          kind: 'agent' as const,
          resource,
          baseUrl: config.agentsUrl,
        })),
        ...(skillPage.content ?? []).map((resource) => ({
          kind: 'skill' as const,
          resource,
          baseUrl: config.skillsUrl,
        })),
      ];
      const resolved = await Promise.all(resources.map(async (item) => {
        const page = await get<Page<ResourceVersion>>(
          `${item.baseUrl}/${item.resource.id}/versions`,
          {page: 0, size: 500, sort: 'createdAt,desc'},
        );
        return (page.content ?? [])
          .filter((version) => version.status === 'PUBLISHED')
          .map((version): DependencyOption => ({
            kind: item.kind,
            resourceId: item.resource.id,
            resourceVersionId: version.id,
            label: `${item.resource.code}@${version.version}`,
          }));
      }));
      setDependencyOptions(resolved.flat());
    } catch {
      setDependencyOptions([]);
    }
  }, [config.agentsUrl, config.skillsUrl]);

  const loadExecutions = useCallback(async (
    workflow?: WorkflowDefinition,
  ) => {
    if (!workflow) {
      setExecutions([]);
      return;
    }
    setExecutionLoading(true);
    try {
      const page = await get<Page<WorkflowExecution>>(
        `${config.baseUrl}/${workflow.id}/executions`,
        {page: 0, size: 100, sort: 'createdAt,desc'},
      );
      setExecutions(page.content ?? []);
    } catch (error) {
      setExecutions([]);
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.loadExecutions', '执行记录加载失败'),
      ));
    } finally {
      setExecutionLoading(false);
    }
  }, [config.baseUrl, t]);

  useEffect(() => {
    void loadWorkflows();
    void loadDependencyOptions();
  }, [loadDependencyOptions, loadWorkflows]);

  useEffect(() => {
    void loadVersions(selected);
    void loadExecutions(selected);
  }, [loadExecutions, loadVersions, selected]);

  const openExecution = () => {
    executionForm.setFieldsValue({
      idempotencyKey: `workflow-${Date.now()}`,
      input: '{}',
    });
    setExecutionOpen(true);
  };

  const startExecution = async () => {
    if (!selected) return;
    let values: ExecutionFormValues;
    try {
      values = await executionForm.validateFields();
    } catch {
      return;
    }
    let input: Record<string, unknown>;
    try {
      input = JSON.parse(values.input) as Record<string, unknown>;
    } catch {
      message.error(t(
        'ai.workflows.validation.input',
        '执行输入必须是有效 JSON 对象',
      ));
      return;
    }
    setSaving(true);
    try {
      const execution = await post<WorkflowExecution>(
        `${config.baseUrl}/${selected.id}/executions`,
        {idempotencyKey: values.idempotencyKey, input},
      );
      setExecutionOpen(false);
      message.success(t(
        'ai.workflows.message.executionStarted',
        'Workflow 执行已提交',
      ));
      await loadExecutions(selected);
      await inspectExecution(execution.id);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.execute', 'Workflow 执行失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const inspectExecution = async (executionId: string) => {
    if (!selected) return;
    setExecutionLoading(true);
    try {
      const [execution, feed] = await Promise.all([
        get<WorkflowExecution>(
          `${config.baseUrl}/${selected.id}/executions/${executionId}`,
        ),
        get<WorkflowExecutionEventFeed>(
          `${config.baseUrl}/${selected.id}/executions/${executionId}/events`,
          {after: 0, limit: 500},
        ),
      ]);
      setInspectingExecution(execution);
      setExecutionEvents(feed.events ?? []);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.loadExecution', '执行详情加载失败'),
      ));
    } finally {
      setExecutionLoading(false);
    }
  };

  const controlExecution = async (
    action: 'pause' | 'resume' | 'cancel',
  ) => {
    if (!selected || !inspectingExecution) return;
    try {
      await post(
        `${config.baseUrl}/${selected.id}/executions/`
          + `${inspectingExecution.id}/${action}`,
        action === 'pause' ? {reason: 'Workbench intervention'} : {},
      );
      await Promise.all([
        inspectExecution(inspectingExecution.id),
        loadExecutions(selected),
      ]);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.intervene', '执行状态变更失败'),
      ));
    }
  };

  const openHumanTask = (task: WorkflowHumanTask) => {
    setRespondingTask(task);
    humanTaskForm.setFieldValue('output', '{}');
  };

  const respondHumanTask = async () => {
    if (!selected || !inspectingExecution || !respondingTask) return;
    let values: HumanTaskFormValues;
    try {
      values = await humanTaskForm.validateFields();
    } catch {
      return;
    }
    let output: unknown;
    try {
      output = JSON.parse(values.output);
    } catch {
      message.error(t(
        'ai.workflows.validation.humanOutput',
        '人工任务响应必须是有效 JSON',
      ));
      return;
    }
    setSaving(true);
    try {
      await post(
        `${config.baseUrl}/${selected.id}/executions/`
          + `${inspectingExecution.id}/human-tasks/`
          + `${respondingTask.id}/respond`,
        {output},
      );
      setRespondingTask(undefined);
      await Promise.all([
        inspectExecution(inspectingExecution.id),
        loadExecutions(selected),
      ]);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.respond', '人工任务处理失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const openCreate = () => {
    setEditing(undefined);
    form.setFieldsValue({
      code: '',
      name: '',
      description: '',
      enabled: true,
    });
    setDefinitionOpen(true);
  };

  const openEdit = (workflow: WorkflowDefinition) => {
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
    let values: WorkflowFormValues;
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
      message.success(t('ai.workflows.message.saved', 'Workflow 已保存'));
      setDefinitionOpen(false);
      await loadWorkflows();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.save', 'Workflow 保存失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const removeDefinition = (workflow: WorkflowDefinition) => {
    Modal.confirm({
      title: t('ai.workflows.delete.title', '删除 Workflow？'),
      content: t(
        'ai.workflows.delete.description',
        '存在不可变版本的 Workflow 不允许删除。',
      ),
      okButtonProps: {danger: true},
      onOk: async () => {
        try {
          await del(`${config.baseUrl}/${workflow.id}`, []);
          if (selected?.id === workflow.id) setSelected(undefined);
          message.success(
            t('ai.workflows.message.deleted', 'Workflow 已删除'),
          );
          await loadWorkflows();
        } catch (error) {
          message.error(resolveErrorMessage(
            error,
            t('ai.workflows.error.delete', 'Workflow 删除失败'),
          ));
        }
      },
    });
  };

  const openCreateVersion = () => {
    if (!selected) return;
    const version = '1.0.0';
    versionForm.setFieldsValue({
      version,
      manifest: pretty(sampleManifest(
        selected.code,
        version,
        dependencyOptions,
      )),
    });
    setVersionOpen(true);
  };

  const refreshSampleVersion = () => {
    if (!selected) return;
    const version = versionForm.getFieldValue('version') || '1.0.0';
    versionForm.setFieldValue(
      'manifest',
      pretty(sampleManifest(selected.code, version, dependencyOptions)),
    );
  };

  const saveVersion = async () => {
    if (!selected) return;
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
    setSaving(true);
    try {
      await post(`${config.baseUrl}/${selected.id}/versions`, {
        version: values.version,
        manifest,
      });
      setVersionOpen(false);
      message.success(
        t('ai.workflows.message.versionCreated', '不可变版本已创建'),
      );
      await loadVersions(selected);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.createVersion', 'Workflow 版本创建失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const changeVersionStatus = async (
    version: WorkflowVersion,
    action: 'publish' | 'deprecate',
  ) => {
    if (!selected) return;
    try {
      await post(
        `${config.baseUrl}/${selected.id}/versions/${version.id}/${action}`,
        {},
      );
      message.success(action === 'publish'
        ? t('ai.workflows.message.published', 'Workflow 版本已发布')
        : t('ai.workflows.message.deprecated', 'Workflow 版本已废弃'));
      await Promise.all([loadVersions(selected), loadWorkflows()]);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.workflows.error.lifecycle', '版本状态变更失败'),
      ));
    }
  };

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
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: t('ai.workflows.column.status', '状态'),
      dataIndex: 'status',
      width: 110,
      render: (value: string) => (
        <Tag color={statusColor[value]}>{value}</Tag>
      ),
    },
    {
      title: t('ai.workflows.column.action', '操作'),
      width: 190,
      render: (_: unknown, workflow: WorkflowDefinition) => (
        <Space>
          <Button type="link" onClick={() => setSelected(workflow)}>
            {t('ai.workflows.action.versions', '版本')}
          </Button>
          <Button type="link" onClick={() => openEdit(workflow)}>
            {t('ai.workflows.action.edit', '编辑')}
          </Button>
          <Button
            danger
            type="link"
            onClick={() => removeDefinition(workflow)}
          >
            {t('ai.workflows.action.delete', '删除')}
          </Button>
        </Space>
      ),
    },
  ], [t]);

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
        <Tag color={statusColor[value]}>{value}</Tag>
      ),
    },
    {
      title: t('ai.workflows.column.dependencies', '固定依赖'),
      render: (_: unknown, version: WorkflowVersion) => (
        <Space size={[0, 4]} wrap>
          {(version.dependencies ?? []).map((dependency) => (
            <Tag key={dependency.id}>
              {dependency.dependencyType}: {dependency.resourceCode}
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
          {version.status === 'DRAFT' && (
            <Button
              type="link"
              onClick={() => void changeVersionStatus(version, 'publish')}
            >
              {t('ai.workflows.action.publish', '发布')}
            </Button>
          )}
          {version.status === 'PUBLISHED' && (
            <Button
              danger
              type="link"
              onClick={() => void changeVersionStatus(version, 'deprecate')}
            >
              {t('ai.workflows.action.deprecate', '废弃')}
            </Button>
          )}
        </Space>
      ),
    },
  ], [selected, t]);

  const executionColumns = useMemo(() => [
    {
      title: t('ai.workflows.column.executionId', '执行 ID'),
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
        <Tag color={statusColor[value]}>{value}</Tag>
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
  ], [selected, t]);

  const executionTerminal = inspectingExecution
    ? ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(
      inspectingExecution.status,
    )
    : false;

  return (
    <Space direction="vertical" size={16} style={{display: 'flex'}}>
      <Alert
        showIcon
        type="info"
        message={t(
          'ai.workflows.notice.title',
          '持久化 Agent Workflow',
        )}
        description={t(
          'ai.workflows.notice.description',
          '版本固定 Agent 与 Skill，工作流只接受有界声明式 DAG，不执行嵌入代码。',
        )}
      />
      <Card
        title={t('ai.workflows.title', 'Agent Workflow')}
        extra={(
          <Space>
            <Button onClick={() => void loadWorkflows()}>
              {t('ai.workflows.action.refresh', '刷新')}
            </Button>
            <Button type="primary" onClick={openCreate}>
              {t('ai.workflows.action.create', '新增 Workflow')}
            </Button>
          </Space>
        )}
      >
        <Table
          rowKey="id"
          loading={loading}
          columns={definitionColumns}
          dataSource={workflows}
          pagination={false}
          rowClassName={(workflow) =>
            workflow.id === selected?.id ? 'ant-table-row-selected' : ''}
          onRow={(workflow) => ({
            onClick: () => setSelected(workflow),
          })}
        />
      </Card>
      <Card
        title={selected
          ? `${t('ai.workflows.executions.title', '执行记录')} · ${selected.name}`
          : t('ai.workflows.executions.empty', '选择 Workflow 后查看执行')}
        extra={selected && (
          <Space>
            <Button onClick={() => void loadExecutions(selected)}>
              {t('ai.workflows.action.refresh', '刷新')}
            </Button>
            <Button
              type="primary"
              disabled={!selected.activeVersionId}
              onClick={openExecution}
            >
              {t('ai.workflows.action.execute', '执行')}
            </Button>
          </Space>
        )}
      >
        <Table
          rowKey="id"
          loading={executionLoading}
          columns={executionColumns}
          dataSource={executions}
          pagination={false}
          locale={{
            emptyText: t('ai.workflows.executions.noData', '暂无执行记录'),
          }}
        />
      </Card>
      <Card
        title={selected
          ? `${t('ai.workflows.versions.title', '不可变版本')} · ${selected.name}`
          : t('ai.workflows.versions.empty', '选择 Workflow 后管理版本')}
        extra={selected && (
          <Button type="primary" onClick={openCreateVersion}>
            {t('ai.workflows.action.createVersion', '创建版本')}
          </Button>
        )}
      >
        <Table
          rowKey="id"
          loading={versionLoading}
          columns={versionColumns}
          dataSource={versions}
          pagination={false}
          locale={{
            emptyText: t('ai.workflows.versions.noData', '暂无版本'),
          }}
        />
      </Card>

      <Modal
        open={definitionOpen}
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
        open={executionOpen}
        title={t('ai.workflows.execution.create', '执行 Workflow')}
        confirmLoading={saving}
        onCancel={() => setExecutionOpen(false)}
        onOk={() => void startExecution()}
      >
        <Form form={executionForm} layout="vertical">
          <Form.Item
            name="idempotencyKey"
            label={t('ai.workflows.field.idempotencyKey', '幂等键')}
            rules={[{required: true}]}
          >
            <Input maxLength={128} />
          </Form.Item>
          <Form.Item
            name="input"
            label={t('ai.workflows.field.executionInput', '输入 JSON')}
            rules={[{required: true}]}
          >
            <TextArea rows={12} spellCheck={false} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        width={920}
        open={versionOpen}
        title={t('ai.workflows.version.create', '创建不可变 Workflow 版本')}
        confirmLoading={saving}
        onCancel={() => setVersionOpen(false)}
        onOk={() => void saveVersion()}
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
        <Form form={versionForm} layout="vertical">
          <Form.Item
            name="version"
            label={t('ai.workflows.field.version', '语义版本')}
            rules={[{required: true}]}
          >
            <Input
              maxLength={64}
              onBlur={refreshSampleVersion}
            />
          </Form.Item>
          <Form.Item
            label={t('ai.workflows.field.catalog', '可用已发布依赖')}
          >
            <Space size={[0, 4]} wrap>
              {dependencyOptions.map((option) => (
                <Tag key={`${option.kind}:${option.resourceVersionId}`}>
                  {option.kind.toUpperCase()}: {option.label}
                </Tag>
              ))}
              {!dependencyOptions.length && (
                <Text type="secondary">
                  {t(
                    'ai.workflows.field.catalogEmpty',
                    '当前作用域没有已发布的 Agent 或 Skill',
                  )}
                </Text>
              )}
            </Space>
          </Form.Item>
          <Form.Item
            name="manifest"
            label="AgentWorkflow Manifest (JSON)"
            rules={[{required: true}]}
          >
            <TextArea rows={24} spellCheck={false} />
          </Form.Item>
          <Button onClick={refreshSampleVersion}>
            {t('ai.workflows.action.resetSample', '按当前版本重置示例')}
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
              <Descriptions.Item label="Version">
                {inspecting.version}
              </Descriptions.Item>
              <Descriptions.Item label="Status">
                <Tag color={statusColor[inspecting.status]}>
                  {inspecting.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Schema">
                {inspecting.manifestSchemaVersion}
              </Descriptions.Item>
              <Descriptions.Item label="Content Hash">
                <Text copyable>{inspecting.contentHash}</Text>
              </Descriptions.Item>
            </Descriptions>
            <Card
              size="small"
              title={t('ai.workflows.column.dependencies', '固定依赖')}
            >
              {(inspecting.dependencies ?? []).map((dependency) => (
                <Paragraph key={dependency.id} copyable>
                  {dependency.nodeId} · {dependency.dependencyType} ·
                  {' '}{dependency.resourceCode}
                  @{dependency.resourceVersionName}
                  {' · '}{dependency.resourceVersionId}
                </Paragraph>
              ))}
            </Card>
            <Card size="small" title="Manifest">
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
          setInspectingExecution(undefined);
          setExecutionEvents([]);
        }}
        extra={inspectingExecution && (
          <Space>
            {inspectingExecution.status === 'PAUSED' ? (
              <Button onClick={() => void controlExecution('resume')}>
                {t('ai.workflows.action.resume', '恢复')}
              </Button>
            ) : !executionTerminal && (
              <Button onClick={() => void controlExecution('pause')}>
                {t('ai.workflows.action.pause', '暂停')}
              </Button>
            )}
            {!executionTerminal && (
              <Button
                danger
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
              <Descriptions.Item label="Execution ID">
                <Text copyable>{inspectingExecution.id}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="Status">
                <Tag color={statusColor[inspectingExecution.status]}>
                  {inspectingExecution.status}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Version">
                <Text copyable>
                  {inspectingExecution.workflowVersionId}
                </Text>
              </Descriptions.Item>
              <Descriptions.Item label="Content Hash">
                <Text copyable>
                  {inspectingExecution.workflowVersionContentHash}
                </Text>
              </Descriptions.Item>
              <Descriptions.Item label="Node budget">
                {inspectingExecution.consumedNodeExecutions}
                {' / '}
                {inspectingExecution.maximumNodeExecutions}
              </Descriptions.Item>
              <Descriptions.Item label="Parallelism">
                {inspectingExecution.maximumParallelism}
              </Descriptions.Item>
              {inspectingExecution.errorCode && (
                <Descriptions.Item label="Error" span={2}>
                  <Text type="danger">
                    {inspectingExecution.errorCode}
                    {' · '}
                    {inspectingExecution.errorMessage}
                  </Text>
                </Descriptions.Item>
              )}
            </Descriptions>
            <Card
              size="small"
              title={t('ai.workflows.execution.nodes', '节点检查点')}
            >
              <Table
                rowKey="id"
                size="small"
                pagination={false}
                dataSource={inspectingExecution.nodes ?? []}
                columns={[
                  {title: 'Node', dataIndex: 'nodeId'},
                  {title: 'Type', dataIndex: 'nodeType', width: 100},
                  {
                    title: 'Status',
                    dataIndex: 'status',
                    width: 150,
                    render: (value: string) => (
                      <Tag color={statusColor[value]}>{value}</Tag>
                    ),
                  },
                  {
                    title: 'Attempts',
                    dataIndex: 'attemptCount',
                    width: 90,
                  },
                  {
                    title: 'Error',
                    render: (_: unknown, node: WorkflowNodeExecution) =>
                      node.errorCode
                        ? `${node.errorCode} · ${node.errorMessage ?? ''}`
                        : '-',
                  },
                ]}
              />
            </Card>
            <Card
              size="small"
              title={t('ai.workflows.execution.humanTasks', '人工任务')}
            >
              <Table
                rowKey="id"
                size="small"
                pagination={false}
                dataSource={inspectingExecution.humanTasks ?? []}
                columns={[
                  {title: 'Node', dataIndex: 'nodeId', width: 120},
                  {title: 'Title', dataIndex: 'title'},
                  {
                    title: 'Status',
                    dataIndex: 'status',
                    width: 120,
                    render: (value: string) => (
                      <Tag color={statusColor[value]}>{value}</Tag>
                    ),
                  },
                  {title: 'Due', dataIndex: 'dueAt', width: 190},
                  {
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
                  },
                ]}
              />
            </Card>
            <Card
              size="small"
              title={t('ai.workflows.execution.events', '执行事件')}
            >
              <Timeline
                items={executionEvents.map((event) => ({
                  color: event.type.includes('FAILED') ? 'red' : 'blue',
                  children: (
                    <Space direction="vertical" size={0}>
                      <Text strong>
                        #{event.sequence} {event.type}
                      </Text>
                      <Text type="secondary">{event.occurredAt}</Text>
                      {event.payload
                        && <Text code>{JSON.stringify(event.payload)}</Text>}
                    </Space>
                  ),
                }))}
              />
            </Card>
          </Space>
        )}
      </Drawer>

      <Modal
        open={Boolean(respondingTask)}
        title={respondingTask?.title}
        confirmLoading={saving}
        onCancel={() => setRespondingTask(undefined)}
        onOk={() => void respondHumanTask()}
      >
        {respondingTask && (
          <Space direction="vertical" size={12} style={{display: 'flex'}}>
            {respondingTask.description && (
              <Text>{respondingTask.description}</Text>
            )}
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label="Context">
                <pre style={{whiteSpace: 'pre-wrap', margin: 0}}>
                  {pretty(respondingTask.context)}
                </pre>
              </Descriptions.Item>
              <Descriptions.Item label="Response schema">
                <pre style={{whiteSpace: 'pre-wrap', margin: 0}}>
                  {pretty(respondingTask.inputSchema)}
                </pre>
              </Descriptions.Item>
            </Descriptions>
            <Form form={humanTaskForm} layout="vertical">
              <Form.Item
                name="output"
                label={t('ai.workflows.field.humanOutput', '响应 JSON')}
                rules={[{required: true}]}
              >
                <TextArea rows={10} spellCheck={false} />
              </Form.Item>
            </Form>
          </Space>
        )}
      </Modal>
    </Space>
  );
};

export default Workflows;
