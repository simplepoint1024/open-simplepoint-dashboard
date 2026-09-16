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
import {
  App,
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
  Tag,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router';
import {
  skillDebugExecutionStatusLabel,
  skillDebugNodeStatusLabel,
  skillLifecycleStatusLabel,
  skillStepTypeLabel,
} from './Designer/labels';

const {Paragraph, Text} = Typography;
const {TextArea} = Input;

type SkillDefinition = {
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

type SkillToolBinding = {
  id: string;
  mcpServerId: string;
  capabilitySnapshotId: string;
  toolName: string;
  toolAlias: string;
  inputSchemaHash: string;
  outputSchemaHash?: string;
};

type SkillPromptBinding = {
  id: string;
  mcpServerId: string;
  capabilitySnapshotId: string;
  promptName: string;
  promptAlias: string;
  descriptorHash: string;
};

type SkillResourceBinding = {
  id: string;
  mcpServerId: string;
  capabilitySnapshotId: string;
  resourceSelector: string;
  resourceAlias: string;
  resourceTemplate: boolean;
  descriptorHash: string;
};

type SkillVersion = {
  id: string;
  version: string;
  artifactReference: string;
  artifactDigest: string;
  artifactMediaType: string;
  artifactConfigDigest: string;
  artifactContentDigest: string;
  artifactSignatureRequired: boolean;
  artifactSignatureVerified: boolean;
  artifactVerificationPolicyHash: string;
  artifactVerifiedAt: string;
  manifestSchemaVersion: string;
  contentHash: string;
  status: 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
  publishedAt?: string;
  deprecatedAt?: string;
  manifest?: Record<string, unknown>;
  budget?: {
    maximumToolCalls: number;
    maximumDurationSeconds: number;
    maximumPayloadBytes: number;
  };
  approvalPolicy?: {
    required: boolean;
    allowSelfApproval: boolean;
    instructions?: string;
  };
  toolBindings?: SkillToolBinding[];
  promptBindings?: SkillPromptBinding[];
  resourceBindings?: SkillResourceBinding[];
};

type SkillExecutionStep = {
  id: string;
  stepId: string;
  stepType: string;
  stepOrder: number;
  capabilityAlias?: string;
  capabilityName?: string;
  capabilityTemplate?: boolean;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
  attemptCount: number;
  capabilityTokenIdHash?: string;
  input?: Record<string, unknown>;
  output?: unknown;
  errorMessage?: string;
};

type SkillExecution = {
  id: string;
  skillId: string;
  skillVersionId: string;
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
  maximumToolCalls?: number;
  maximumDurationSeconds?: number;
  maximumPayloadBytes?: number;
  consumedToolCalls?: number;
  consumedPayloadBytes?: number;
  deadlineAt?: string;
  requestedBy?: string;
  approvalRequired?: boolean;
  selfApprovalAllowed?: boolean;
  approvalInstructions?: string;
  approvalRequestedAt?: string;
  approvedAt?: string;
  approvedBy?: string;
  approvalComment?: string;
  rejectedAt?: string;
  rejectedBy?: string;
  rejectionReason?: string;
  pauseRequested?: boolean;
  pauseRequestedAt?: string;
  pauseRequestedBy?: string;
  pauseReason?: string;
  pausedAt?: string;
  resumedAt?: string;
  resumedBy?: string;
  input?: Record<string, unknown>;
  output?: unknown;
  errorCode?: string;
  errorMessage?: string;
  startedAt?: string;
  completedAt?: string;
  createdAt?: string;
  steps?: SkillExecutionStep[];
};

type SkillFormValues = {
  code?: string;
  name: string;
  description?: string;
  enabled?: boolean;
};

type VersionFormValues = {
  version: string;
  artifactReference: string;
  artifactDigest: string;
  manifest: string;
};

type SkillManagedRegistryStatus = {
  configured: boolean;
  registry: string;
  repositoryPrefix: string;
  secureTransport: boolean;
  authenticationConfigured: boolean;
  signatureRequired: boolean;
  connected?: boolean | null;
  checkedAt?: string | null;
  message: string;
};

type ExecutionAction = 'approve' | 'reject' | 'pause';

type ExecutionActionState = {
  action: ExecutionAction;
  skill: SkillDefinition;
  execution: SkillExecution;
};

const defaultManifest = (code: string, version: string) => JSON.stringify({
  apiVersion: 'simplepoint.io/v1alpha1',
  kind: 'Skill',
  metadata: {
    name: code,
    version,
  },
  spec: {
    inputSchema: {
      type: 'object',
      properties: {},
      additionalProperties: false,
    },
    outputSchema: {
      type: 'object',
      properties: {},
      additionalProperties: false,
    },
    tools: [],
    prompts: [],
    resources: [],
    workflow: {
      steps: [],
    },
    approvals: {
      execution: {
        required: false,
        allowSelfApproval: false,
      },
    },
  },
}, null, 2);

const skillStatusColor = (status: SkillDefinition['status']) => ({
  ACTIVE: 'green',
  DRAFT: 'gold',
  DISABLED: 'default',
}[status]);

const versionStatusColor = (status: SkillVersion['status']) => ({
  PUBLISHED: 'green',
  DRAFT: 'gold',
  DEPRECATED: 'default',
}[status]);

const executionStatusColor = (status: SkillExecution['status']) => ({
  SUCCEEDED: 'green',
  RUNNING: 'blue',
  PENDING: 'gold',
  WAITING_APPROVAL: 'orange',
  PAUSED: 'purple',
  FAILED: 'red',
  REJECTED: 'red',
  CANCELLED: 'default',
}[status]);

const formatBytes = (value?: number) => {
  if (value === undefined) return '-';
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / 1024 / 1024).toFixed(2)} MiB`;
};

const summarizeWorkflow = (manifest?: Record<string, unknown>) => {
  const spec = manifest?.spec;
  if (!spec || typeof spec !== 'object' || Array.isArray(spec)) return undefined;
  const workflow = (spec as Record<string, unknown>).workflow;
  if (!workflow || typeof workflow !== 'object' || Array.isArray(workflow)) return undefined;
  const steps = (workflow as Record<string, unknown>).steps;
  if (!Array.isArray(steps)) return undefined;
  let tools = 0;
  let prompts = 0;
  let resources = 0;
  let conditions = 0;
  let parallels = 0;
  let branches = 0;
  const countCapabilities = (values: unknown[]) => {
    values.forEach((value) => {
      if (!value || typeof value !== 'object' || Array.isArray(value)) return;
      const step = value as Record<string, unknown>;
      if (step.type === 'tool') tools += 1;
      if (step.type === 'prompt') prompts += 1;
      if (step.type === 'resource') resources += 1;
    });
  };
  steps.forEach((value) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return;
    const step = value as Record<string, unknown>;
    if (step.type === 'tool') tools += 1;
    if (step.type === 'prompt') prompts += 1;
    if (step.type === 'resource') resources += 1;
    if (step.type === 'condition') {
      conditions += 1;
      countCapabilities(Array.isArray(step.then) ? step.then : []);
      countCapabilities(Array.isArray(step.else) ? step.else : []);
    }
    if (step.type === 'parallel') {
      parallels += 1;
      if (!Array.isArray(step.branches)) return;
      branches += step.branches.length;
      step.branches.forEach((branchValue) => {
        if (!branchValue || typeof branchValue !== 'object' || Array.isArray(branchValue)) return;
        const branchSteps = (branchValue as Record<string, unknown>).steps;
        countCapabilities(Array.isArray(branchSteps) ? branchSteps : []);
      });
    }
  });
  return {tools, prompts, resources, conditions, parallels, branches};
};

const Skills = () => {
  const config = api['ai-workbench.skills'];
  const {t, ensure, locale} = useI18n();
  const {message, modal} = App.useApp();
  const navigate = useNavigate();
  const [form] = Form.useForm<SkillFormValues>();
  const [versionForm] = Form.useForm<VersionFormValues>();
  const [skills, setSkills] = useState<SkillDefinition[]>([]);
  const [versions, setVersions] = useState<SkillVersion[]>([]);
  const [loading, setLoading] = useState(false);
  const [versionLoading, setVersionLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<SkillDefinition>();
  const [skillDialogOpen, setSkillDialogOpen] = useState(false);
  const [selectedSkill, setSelectedSkill] = useState<SkillDefinition>();
  const [versionDialogOpen, setVersionDialogOpen] = useState(false);
  const [managedRegistry, setManagedRegistry] =
    useState<SkillManagedRegistryStatus>();
  const [managedRegistryLoading, setManagedRegistryLoading] = useState(false);
  const [managedRegistryChecking, setManagedRegistryChecking] = useState(false);
  const [inspectingVersion, setInspectingVersion] = useState<SkillVersion>();
  const [executionSkill, setExecutionSkill] = useState<SkillDefinition>();
  const [executionInputSchema, setExecutionInputSchema] =
    useState<Record<string, unknown>>();
  const [executionSchemaLoading, setExecutionSchemaLoading] = useState(false);
  const [executionSubmitting, setExecutionSubmitting] = useState(false);
  const [executionHistorySkill, setExecutionHistorySkill] = useState<SkillDefinition>();
  const [executionHistory, setExecutionHistory] = useState<SkillExecution[]>([]);
  const [executionHistoryLoading, setExecutionHistoryLoading] = useState(false);
  const [executionDetails, setExecutionDetails] = useState<SkillExecution>();
  const [executionAction, setExecutionAction] = useState<ExecutionActionState>();
  const [executionActionComment, setExecutionActionComment] = useState('');
  const [executionActionSubmitting, setExecutionActionSubmitting] = useState(false);
  const versionRequestSequence = useRef(0);

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  const loadSkills = useCallback(async () => {
    setLoading(true);
    try {
      const page = await get<Page<SkillDefinition>>(
        config.baseUrl,
        {page: 0, size: 500, sort: 'createdAt,desc'},
      );
      setSkills(page.content ?? []);
      setSelectedSkill((current) => current
        ? (page.content ?? []).find((skill) => skill.id === current.id)
        : undefined);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.error.load', 'Skill 列表加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [config.baseUrl, message, t]);

  const loadVersions = useCallback(async (skill?: SkillDefinition) => {
    const requestSequence = ++versionRequestSequence.current;
    if (!skill) {
      setVersions([]);
      setVersionLoading(false);
      return;
    }
    setVersionLoading(true);
    try {
      const page = await get<Page<SkillVersion>>(
        `${config.baseUrl}/${skill.id}/versions`,
        {page: 0, size: 500, sort: 'createdAt,desc'},
      );
      if (requestSequence === versionRequestSequence.current) {
        setVersions(page.content ?? []);
      }
    } catch (error) {
      if (requestSequence === versionRequestSequence.current) {
        setVersions([]);
        message.error(resolveApiErrorMessage(
          error,
          t('ai.skills.error.loadVersions', 'Skill 版本加载失败'),
        ));
      }
    } finally {
      if (requestSequence === versionRequestSequence.current) {
        setVersionLoading(false);
      }
    }
  }, [config.baseUrl, message, t]);

  useEffect(() => {
    void loadSkills();
  }, [loadSkills]);

  useEffect(() => {
    void loadVersions(selectedSkill);
  }, [loadVersions, selectedSkill]);

  const openCreate = () => {
    setEditing(undefined);
    form.setFieldsValue({
      code: '',
      name: '',
      description: '',
      enabled: true,
    });
    setSkillDialogOpen(true);
  };

  const openEdit = (skill: SkillDefinition) => {
    setEditing(skill);
    form.setFieldsValue({
      code: skill.code,
      name: skill.name,
      description: skill.description,
      enabled: skill.enabled,
    });
    setSkillDialogOpen(true);
  };

  const saveSkill = async () => {
    let values: SkillFormValues;
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
      message.success(t('ai.skills.message.saved', 'Skill 已保存'));
      setSkillDialogOpen(false);
      await loadSkills();
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.error.save', 'Skill 保存失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const removeSkill = (skill: SkillDefinition) => {
    modal.confirm({
      title: t('ai.skills.delete.title', '删除 Skill？'),
      content: t(
        'ai.skills.delete.description',
        '存在不可变版本的 Skill 不允许删除。',
      ),
      okButtonProps: {danger: true},
      onOk: async () => {
        try {
          await del(`${config.baseUrl}/${skill.id}`, []);
          message.success(t('ai.skills.message.deleted', 'Skill 已删除'));
          if (selectedSkill?.id === skill.id) setSelectedSkill(undefined);
          await loadSkills();
        } catch (error) {
          message.error(resolveApiErrorMessage(
            error,
            t('ai.skills.error.delete', 'Skill 删除失败'),
          ));
        }
      },
    });
  };

  const loadManagedRegistry = async () => {
    setManagedRegistryLoading(true);
    try {
      setManagedRegistry(await get<SkillManagedRegistryStatus>(
        config.registryUrl,
        {},
      ));
    } catch (error) {
      setManagedRegistry(undefined);
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.registry.error.load', '托管 Registry 配置加载失败'),
      ));
    } finally {
      setManagedRegistryLoading(false);
    }
  };

  const checkManagedRegistry = async () => {
    setManagedRegistryChecking(true);
    try {
      const status = await post<SkillManagedRegistryStatus>(
        `${config.registryUrl}/connectivity-check`,
        {},
      );
      setManagedRegistry(status);
      message.success(t(
        'ai.skills.registry.message.connected',
        '托管 Registry 连接成功',
      ));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.registry.error.connectivity', '托管 Registry 连接失败'),
      ));
    } finally {
      setManagedRegistryChecking(false);
    }
  };

  const openVersionCreate = () => {
    if (!selectedSkill) return;
    const version = '1.0.0';
    versionForm.setFieldsValue({
      version,
      artifactReference: `registry.example.com/skills/${selectedSkill.code}:${version}`,
      artifactDigest: '',
      manifest: defaultManifest(selectedSkill.code, version),
    });
    setVersionDialogOpen(true);
    void loadManagedRegistry();
  };

  const saveVersion = async () => {
    if (!selectedSkill) return;
    let values: VersionFormValues;
    try {
      values = await versionForm.validateFields();
    } catch {
      return;
    }
    let manifest: Record<string, unknown>;
    try {
      const decoded: unknown = JSON.parse(values.manifest);
      if (!decoded || Array.isArray(decoded) || typeof decoded !== 'object') {
        throw new Error('not object');
      }
      manifest = decoded as Record<string, unknown>;
    } catch {
      message.error(t('ai.skills.error.manifest', 'Manifest 必须是有效的 JSON 对象'));
      return;
    }
    setSaving(true);
    try {
      await post(`${config.baseUrl}/${selectedSkill.id}/versions`, {
        version: values.version,
        artifactReference: values.artifactReference,
        artifactDigest: values.artifactDigest,
        manifest,
      });
      message.success(t('ai.skills.message.versionCreated', '不可变版本已创建'));
      setVersionDialogOpen(false);
      await loadVersions(selectedSkill);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.error.createVersion', 'Skill 版本创建失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const changeVersionStatus = async (
    version: SkillVersion,
    action: 'publish' | 'deprecate',
  ) => {
    if (!selectedSkill) return;
    try {
      await post(
        `${config.baseUrl}/${selectedSkill.id}/versions/${version.id}/${action}`,
        {},
      );
      message.success(action === 'publish'
        ? t('ai.skills.message.published', 'Skill 版本已发布并激活')
        : t('ai.skills.message.deprecated', 'Skill 版本已废弃'));
      await Promise.all([loadVersions(selectedSkill), loadSkills()]);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.error.changeStatus', '版本状态变更失败'),
      ));
    }
  };

  const loadExecutions = useCallback(async (skill: SkillDefinition) => {
    setExecutionHistoryLoading(true);
    try {
      const page = await get<Page<SkillExecution>>(
        `${config.baseUrl}/${skill.id}/executions`,
        {page: 0, size: 100, sort: 'createdAt,desc'},
      );
      setExecutionHistory(page.content ?? []);
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.error.loadExecutions', 'Skill 执行记录加载失败'),
      ));
    } finally {
      setExecutionHistoryLoading(false);
    }
  }, [config.baseUrl, message, t]);

  const openExecution = async (skill: SkillDefinition) => {
    if (!skill.activeVersionId) {
      message.warning(t(
        'ai.skills.execution.noActiveVersion',
        '请先发布并激活一个 Skill 版本',
      ));
      return;
    }
    setExecutionSkill(skill);
    setExecutionInputSchema(undefined);
    setExecutionSchemaLoading(true);
    try {
      const version = await get<SkillVersion>(
        `${config.baseUrl}/${skill.id}/versions/${skill.activeVersionId}`,
      );
      setExecutionInputSchema(inputSchemaFromManifest(version.manifest));
    } catch (error) {
      setExecutionSkill(undefined);
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.error.loadExecutionSchema', 'Skill 输入 Schema 加载失败'),
      ));
    } finally {
      setExecutionSchemaLoading(false);
    }
  };

  const openExecutionHistory = (skill: SkillDefinition) => {
    setExecutionHistorySkill(skill);
    void loadExecutions(skill);
  };

  const refreshExecution = useCallback(async (
    skill: SkillDefinition,
    executionId: string,
  ) => get<SkillExecution>(
    `${config.baseUrl}/${skill.id}/executions/${executionId}`,
  ), [config.baseUrl]);

  const applyExecutionResult = useCallback((execution: SkillExecution) => {
    setExecutionHistory((current) => current.map((candidate) => (
      candidate.id === execution.id ? execution : candidate
    )));
    setExecutionDetails((current) => (
      current?.id === execution.id ? execution : current
    ));
  }, []);

  const watchExecution = useCallback(async (
    skill: SkillDefinition,
    initial: SkillExecution,
    watchedStatuses: SkillExecution['status'][],
  ) => {
    let execution = initial;
    for (let index = 0; index < 60 && watchedStatuses.includes(execution.status); index += 1) {
      await new Promise((resolve) => window.setTimeout(resolve, 500));
      execution = await refreshExecution(skill, execution.id);
      applyExecutionResult(execution);
    }
    return execution;
  }, [applyExecutionResult, refreshExecution]);

  const openExecutionAction = (
    action: ExecutionAction,
    skill: SkillDefinition,
    execution: SkillExecution,
  ) => {
    setExecutionActionComment('');
    setExecutionAction({action, skill, execution});
  };

  const submitExecutionAction = async () => {
    if (!executionAction) return;
    const {action, skill, execution} = executionAction;
    setExecutionActionSubmitting(true);
    try {
      const updated = await post<SkillExecution>(
        `${config.baseUrl}/${skill.id}/executions/${execution.id}/${action}`,
        action === 'pause'
          ? {reason: executionActionComment || undefined}
          : {comment: executionActionComment || undefined},
      );
      applyExecutionResult(updated);
      setExecutionAction(undefined);
      message.success({
        approve: t('ai.skills.execution.approved', '执行已审批通过'),
        reject: t('ai.skills.execution.rejected', '执行已驳回'),
        pause: updated.status === 'RUNNING'
          ? t('ai.skills.execution.pauseRequested', '暂停请求已提交')
          : t('ai.skills.execution.paused', '执行已暂停'),
      }[action]);
      if (action === 'approve' && updated.status === 'PENDING') {
        void watchExecution(skill, updated, ['PENDING', 'RUNNING']);
      } else if (action === 'pause' && updated.status === 'RUNNING') {
        void watchExecution(skill, updated, ['RUNNING']);
      }
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.error.controlExecution', '执行状态变更失败'),
      ));
    } finally {
      setExecutionActionSubmitting(false);
    }
  };

  const resumeExecution = async (
    skill: SkillDefinition,
    execution: SkillExecution,
  ) => {
    try {
      const updated = await post<SkillExecution>(
        `${config.baseUrl}/${skill.id}/executions/${execution.id}/resume`,
        {},
      );
      applyExecutionResult(updated);
      message.success(t('ai.skills.execution.resumed', '执行已恢复'));
      if (updated.status === 'PENDING') {
        void watchExecution(skill, updated, ['PENDING', 'RUNNING']);
      }
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.error.controlExecution', '执行状态变更失败'),
      ));
    }
  };

  const runSkill = async (input: ExecutionInput) => {
    if (!executionSkill) return;
    setExecutionSubmitting(true);
    try {
      let execution = await post<SkillExecution>(
        `${config.baseUrl}/${executionSkill.id}/executions`,
        {
          idempotencyKey: globalThis.crypto?.randomUUID?.()
            ?? `${Date.now()}-${Math.random()}`,
          input,
        },
      );
      setExecutionDetails(execution);
      setExecutionSkill(undefined);
      execution = await watchExecution(
        executionSkill,
        execution,
        ['PENDING', 'RUNNING'],
      );
      message.success(execution.status === 'SUCCEEDED'
        ? t('ai.skills.execution.succeeded', 'Skill 执行成功')
        : execution.status === 'FAILED'
          ? t('ai.skills.execution.failed', 'Skill 执行失败')
          : t('ai.skills.execution.submitted', 'Skill 已提交执行'));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.error.execute', 'Skill 执行提交失败'),
      ));
    } finally {
      setExecutionSubmitting(false);
    }
  };

  const columns = useMemo(() => [
    {
      title: t('ai.skills.column.code', '代码'),
      dataIndex: 'code',
      width: 180,
      render: (value: string) => <Text code>{value}</Text>,
    },
    {
      title: t('ai.skills.column.name', '名称'),
      dataIndex: 'name',
      width: 220,
    },
    {
      title: t('ai.skills.column.description', '说明'),
      dataIndex: 'description',
      ellipsis: true,
    },
    {
      title: t('ai.skills.column.scope', '作用域'),
      dataIndex: 'scopeType',
      width: 100,
      render: (value: SkillDefinition['scopeType']) => (
        <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>
          {value === 'TENANT'
            ? t('ai.skills.scope.tenant', '租户私有')
            : t('ai.skills.scope.system', '系统共享')}
        </Tag>
      ),
    },
    {
      title: t('ai.skills.column.status', '状态'),
      dataIndex: 'status',
      width: 100,
      render: (value: SkillDefinition['status']) => (
        <Tag color={skillStatusColor(value)}>
          {skillLifecycleStatusLabel(t, value)}
        </Tag>
      ),
    },
    {
      title: t('ai.skills.column.action', '操作'),
      key: 'action',
      width: 440,
      fixed: 'right' as const,
      render: (_: unknown, skill: SkillDefinition) => (
        <Space size={4}>
          {skill.status === 'ACTIVE' && (
            <Button type="link" onClick={() => void openExecution(skill)}>
              {t('ai.skills.action.execute', '执行')}
            </Button>
          )}
          <Button type="link" onClick={() => openExecutionHistory(skill)}>
            {t('ai.skills.action.executions', '执行记录')}
          </Button>
          <Button type="link" onClick={() => setSelectedSkill(skill)}>
            {t('ai.skills.action.versions', '版本')}
          </Button>
          <Button
            type="link"
            onClick={() => navigate(`/ai/workbench/skill-designer?skillId=${encodeURIComponent(skill.id)}`)}
          >
            {t('ai.skills.action.designer', '可视化设计')}
          </Button>
          <Button type="link" onClick={() => openEdit(skill)}>
            {t('ai.skills.action.edit', '编辑')}
          </Button>
          <Button danger type="link" onClick={() => removeSkill(skill)}>
            {t('ai.skills.action.delete', '删除')}
          </Button>
        </Space>
      ),
    },
  ], [navigate, t]);

  const executionColumns = useMemo(() => [
    {
      title: t('ai.skills.execution.id', '执行 ID'),
      dataIndex: 'id',
      width: 270,
      render: (value: string) => <Text code copyable>{value}</Text>,
    },
    {
      title: t('ai.skills.column.status', '状态'),
      dataIndex: 'status',
      width: 110,
      render: (value: SkillExecution['status']) => (
        <Tag color={executionStatusColor(value)}>
          {skillDebugExecutionStatusLabel(t, value)}
        </Tag>
      ),
    },
    {
      title: t('ai.skills.execution.currentStep', '当前步骤'),
      dataIndex: 'currentStepId',
      width: 130,
      render: (value?: string) => value ?? '-',
    },
    {
      title: t('ai.skills.execution.attempts', '执行次数'),
      dataIndex: 'attemptCount',
      width: 100,
    },
    {
      title: t('ai.skills.execution.capabilityToken', '能力令牌'),
      dataIndex: 'steps',
      width: 170,
      render: (steps?: SkillExecutionStep[]) => {
        const value = steps?.find((step) => step.capabilityTokenIdHash)
          ?.capabilityTokenIdHash;
        return value
          ? <Text code copyable={{text: value}}>{`${value.slice(0, 12)}…`}</Text>
          : '-';
      },
    },
    {
      title: t('ai.skills.execution.startedAt', '开始时间'),
      dataIndex: 'startedAt',
      width: 190,
      render: (value?: string) => value ?? '-',
    },
    {
      title: t('ai.skills.column.action', '操作'),
      key: 'action',
      width: 300,
      render: (_: unknown, execution: SkillExecution) => {
        const skill = executionHistorySkill;
        const approvalPending = execution.approvalRequired
          && !execution.approvedAt
          && !execution.rejectedAt
          && (execution.status === 'WAITING_APPROVAL' || execution.status === 'PAUSED');
        return (
          <Space size={2}>
            <Button type="link" onClick={() => setExecutionDetails(execution)}>
              {t('ai.skills.action.inspect', '查看')}
            </Button>
            {skill && approvalPending && (
              <>
                <Button
                  type="link"
                  onClick={() => openExecutionAction('approve', skill, execution)}
                >
                  {t('ai.skills.action.approve', '通过')}
                </Button>
                <Button
                  danger
                  type="link"
                  onClick={() => openExecutionAction('reject', skill, execution)}
                >
                  {t('ai.skills.action.reject', '驳回')}
                </Button>
              </>
            )}
            {skill && ['WAITING_APPROVAL', 'PENDING', 'RUNNING'].includes(execution.status) && (
              <Button
                type="link"
                onClick={() => openExecutionAction('pause', skill, execution)}
              >
                {t('ai.skills.action.pause', '暂停')}
              </Button>
            )}
            {skill && execution.status === 'PAUSED' && (
              <Button type="link" onClick={() => void resumeExecution(skill, execution)}>
                {t('ai.skills.action.resume', '恢复')}
              </Button>
            )}
          </Space>
        );
      },
    },
  ], [executionHistorySkill, t]);

  const executionStepColumns = useMemo(() => [
    {
      title: t('ai.skills.execution.step', '步骤'),
      dataIndex: 'stepId',
      width: 140,
    },
    {
      title: t('ai.skills.execution.capabilityType', '能力类型'),
      dataIndex: 'stepType',
      width: 110,
      render: (value: string) => (
        <Tag color={{
          tool: 'blue',
          prompt: 'purple',
          resource: 'cyan',
        }[value]}>
          {skillStepTypeLabel(t, value)}
        </Tag>
      ),
    },
    {
      title: t('ai.skills.execution.capability', 'MCP 能力'),
      dataIndex: 'capabilityName',
      width: 260,
      render: (value?: string, step?: SkillExecutionStep) => (
        <Text code>
          {step?.capabilityAlias ? `${step.capabilityAlias} → ` : ''}
          {value ?? '-'}
          {step?.capabilityTemplate ? t('ai.skills.capability.templateSuffix', '（模板）') : ''}
        </Text>
      ),
    },
    {
      title: t('ai.skills.column.status', '状态'),
      dataIndex: 'status',
      width: 110,
      render: (value: SkillExecutionStep['status']) => (
        <Tag color={value === 'SUCCEEDED'
          ? 'green'
          : value === 'FAILED'
            ? 'red'
            : value === 'SKIPPED'
              ? 'default'
              : 'blue'}>
          {skillDebugNodeStatusLabel(t, value)}
        </Tag>
      ),
    },
    {
      title: t('ai.skills.execution.attempts', '执行次数'),
      dataIndex: 'attemptCount',
      width: 100,
    },
    {
      title: t('ai.skills.execution.error', '错误'),
      dataIndex: 'errorMessage',
      ellipsis: true,
      render: (value?: string) => value ?? '-',
    },
  ], [t]);

  const versionColumns = useMemo(() => [
    {
      title: t('ai.skills.column.version', '版本'),
      dataIndex: 'version',
      width: 110,
      render: (value: string) => <Text code>{value}</Text>,
    },
    {
      title: t('ai.skills.column.artifact', 'OCI Artifact'),
      dataIndex: 'artifactReference',
      ellipsis: true,
      render: (value: string) => <Text copyable={{text: value}}>{value}</Text>,
    },
    {
      title: t('ai.skills.column.bindings', '能力绑定'),
      key: 'bindings',
      width: 110,
      render: (_: unknown, version: SkillVersion) => (
        (version.toolBindings?.length ?? 0)
        + (version.promptBindings?.length ?? 0)
        + (version.resourceBindings?.length ?? 0)
      ),
    },
    {
      title: t('ai.skills.column.supplyChain', '供应链'),
      dataIndex: 'artifactSignatureVerified',
      width: 130,
      render: (verified: boolean, version: SkillVersion) => (
        <Tag color={verified ? 'green' : 'blue'}>
          {verified
            ? t('ai.skills.supplyChain.signed', '签名已验证')
            : version.artifactSignatureRequired
              ? t('ai.skills.supplyChain.rejected', '签名未通过')
              : t('ai.skills.supplyChain.contentOnly', '内容已验证')}
        </Tag>
      ),
    },
    {
      title: t('ai.skills.column.status', '状态'),
      dataIndex: 'status',
      width: 110,
      render: (value: SkillVersion['status']) => (
        <Tag color={versionStatusColor(value)}>
          {skillLifecycleStatusLabel(t, value)}
        </Tag>
      ),
    },
    {
      title: t('ai.skills.column.action', '操作'),
      key: 'action',
      width: 270,
      render: (_: unknown, version: SkillVersion) => (
        <Space size={2}>
          <Button type="link" onClick={() => setInspectingVersion(version)}>
            {t('ai.skills.action.inspect', '查看')}
          </Button>
          <Button
            type="link"
            disabled={!selectedSkill}
            onClick={() => selectedSkill && navigate(
              `/ai/workbench/skill-designer?skillId=${encodeURIComponent(selectedSkill.id)}&versionId=${encodeURIComponent(version.id)}`,
            )}
          >
            {t('ai.skills.action.visualize', '设计图')}
          </Button>
          {version.status === 'DRAFT' && (
            <Button type="link" onClick={() => void changeVersionStatus(version, 'publish')}>
              {t('ai.skills.action.publish', '发布')}
            </Button>
          )}
          {version.status === 'PUBLISHED' && (
            <Button danger type="link" onClick={() => void changeVersionStatus(version, 'deprecate')}>
              {t('ai.skills.action.deprecate', '废弃')}
            </Button>
          )}
        </Space>
      ),
    },
  ], [navigate, selectedSkill, t]);

  const executionDetailsSkill = executionDetails
    ? skills.find((skill) => skill.id === executionDetails.skillId)
    : undefined;
  const executionDetailsApprovalPending = Boolean(
    executionDetails?.approvalRequired
      && !executionDetails.approvedAt
      && !executionDetails.rejectedAt
      && (
        executionDetails.status === 'WAITING_APPROVAL'
        || executionDetails.status === 'PAUSED'
      ),
  );
  const inspectingWorkflow = summarizeWorkflow(inspectingVersion?.manifest);

  return (
    <div style={{height: '100%', display: 'flex', flexDirection: 'column', gap: 16}}>
      <Alert
        showIcon
        type="info"
        closable
        message={t('ai.skills.notice.title', '声明式 Skill Registry')}
        description={t(
          'ai.skills.notice.description',
          'Skill 不包含可执行代码；版本固定 OCI Digest、MCP 能力快照以及 Tool、Prompt、Resource 描述。新增 Skill 无需修改平台代码。',
        )}
      />
      <Card
        style={{flex: 1, minHeight: 0}}
        styles={{body: {height: '100%', padding: 0}}}
        title={t('ai.skills.title', '技能')}
        extra={(
          <Space>
            <Button onClick={() => void loadSkills()}>
              {t('ai.skills.action.refresh', '刷新')}
            </Button>
            <Button type="primary" onClick={openCreate}>
              {t('ai.skills.action.create', '新增 Skill')}
            </Button>
          </Space>
        )}
      >
        <DataTable
          rowKey="id"
          loading={loading}
          dataSource={skills}
          columns={columns}
          pagination={{pageSize: 20, showSizeChanger: true}}
          scroll={{x: 1000, y: 'calc(100vh - 330px)'}}
        />
      </Card>

      <Modal
        open={skillDialogOpen}
        title={editing
          ? t('ai.skills.dialog.edit', '编辑 Skill')
          : t('ai.skills.dialog.create', '新增 Skill')}
        confirmLoading={saving}
        onOk={() => void saveSkill()}
        onCancel={() => setSkillDialogOpen(false)}
      >
        <Form form={form} layout="vertical" requiredMark={false}>
          <Form.Item
            name="code"
            label={t('ai.skills.field.code', '代码')}
            rules={[
              {required: true},
              {pattern: /^[a-z0-9][a-z0-9_.-]{0,63}$/},
            ]}
          >
            <Input disabled={Boolean(editing)} placeholder="document-summary" />
          </Form.Item>
          <Form.Item
            name="name"
            label={t('ai.skills.field.name', '名称')}
            rules={[{required: true, max: 128}]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="description"
            label={t('ai.skills.field.description', '说明')}
            rules={[{max: 512}]}
          >
            <TextArea rows={3} />
          </Form.Item>
          <Form.Item
            name="enabled"
            label={t('ai.skills.field.enabled', '启用')}
            valuePropName="checked"
          >
            <Switch />
          </Form.Item>
        </Form>
      </Modal>

      <Drawer
        open={Boolean(selectedSkill)}
        title={`${t('ai.skills.versions.title', '不可变版本')} · ${selectedSkill?.name ?? ''}`}
        width={1100}
        extra={(
          <Button type="primary" onClick={openVersionCreate}>
            {t('ai.skills.action.createVersion', '创建版本')}
          </Button>
        )}
        onClose={() => setSelectedSkill(undefined)}
      >
        <DataTable
          rowKey="id"
          size="small"
          loading={versionLoading}
          dataSource={versions}
          columns={versionColumns}
          pagination={{pageSize: 20}}
          scroll={{x: 1000}}
        />
      </Drawer>

      <Modal
        open={versionDialogOpen}
        width={900}
        title={t('ai.skills.version.create', '创建不可变 Skill 版本')}
        confirmLoading={saving}
        onOk={() => void saveVersion()}
        onCancel={() => setVersionDialogOpen(false)}
      >
        <Alert
          showIcon
          type={managedRegistry?.configured ? 'info' : 'warning'}
          style={{marginBottom: 16}}
          message={managedRegistry?.configured
            ? t('ai.skills.registry.configured', '托管 Registry 已配置')
            : t('ai.skills.registry.notConfigured', '托管 Registry 尚未配置')}
          description={managedRegistryLoading
            ? t('ai.skills.registry.loading', '正在读取服务端发布配置…')
            : managedRegistry?.configured
              ? t(
                'ai.skills.registry.summary',
                '{registry}/{prefix} · {transport} · {authentication} · {signature}',
                {
                  registry: managedRegistry.registry,
                  prefix: managedRegistry.repositoryPrefix,
                  transport: managedRegistry.secureTransport ? 'TLS' : 'HTTP',
                  authentication: managedRegistry.authenticationConfigured
                    ? t('ai.skills.registry.auth.configured', '服务端凭据已配置')
                    : t('ai.skills.registry.auth.anonymous', '匿名访问'),
                  signature: managedRegistry.signatureRequired
                    ? t('ai.skills.registry.signature.required', '要求签名')
                    : t('ai.skills.registry.signature.optional', '签名可选'),
                },
              )
              : t(
                'ai.skills.registry.notConfiguredDescription',
                '可继续使用下方外部 OCI 导入；设计器一键发布需由平台管理员配置托管 Registry。',
              )}
          action={managedRegistry?.configured ? (
            <Button
              size="small"
              loading={managedRegistryChecking}
              onClick={() => void checkManagedRegistry()}
            >
              {managedRegistry?.connected
                ? t('ai.skills.registry.recheck', '重新检查')
                : t('ai.skills.registry.check', '检查连接')}
            </Button>
          ) : undefined}
        />
        <Alert
          showIcon
          type="warning"
          style={{marginBottom: 16}}
          message={t(
            'ai.skills.version.warning',
            '系统会从 Registry 拉取完整 Artifact，校验 Digest、媒体类型、内容和 Cosign 签名；版本创建后不可修改。',
          )}
        />
        <Form form={versionForm} layout="vertical" requiredMark={false}>
          <Form.Item
            name="version"
            label={t('ai.skills.field.version', '语义版本')}
            rules={[{required: true}]}
          >
            <Input placeholder="1.0.0" />
          </Form.Item>
          <Form.Item
            name="artifactReference"
            label={t('ai.skills.field.artifactReference', 'OCI Artifact 引用')}
            rules={[{required: true, max: 512}]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="artifactDigest"
            label={t('ai.skills.field.artifactDigest', 'OCI Manifest Digest')}
            rules={[
              {required: true},
              {pattern: /^sha256:[a-f0-9]{64}$/},
            ]}
          >
            <Input placeholder="sha256:..." />
          </Form.Item>
          <Form.Item
            name="manifest"
            label={t('ai.skills.field.manifest', 'Skill Manifest JSON')}
            rules={[{required: true}]}
            extra={t(
              'ai.skills.field.manifestHint',
              '此 JSON 必须与 OCI Artifact 中的 Skill Manifest 层完全一致；Tool、Prompt、Resource 步骤只能引用各自已绑定的 alias。',
            )}
          >
            <TextArea rows={20} spellCheck={false} style={{fontFamily: 'monospace'}} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        open={Boolean(inspectingVersion)}
        width={940}
        footer={null}
        title={`${t('ai.skills.version.details', '版本详情')} · ${inspectingVersion?.version ?? ''}`}
        onCancel={() => setInspectingVersion(undefined)}
      >
        {inspectingVersion && (
          <>
            <Space style={{marginBottom: 16}}>
              <Button
                type="primary"
                disabled={!selectedSkill}
                onClick={() => {
                  if (!selectedSkill) return;
                  setInspectingVersion(undefined);
                  navigate(
                    `/ai/workbench/skill-designer?skillId=${encodeURIComponent(selectedSkill.id)}&versionId=${encodeURIComponent(inspectingVersion.id)}`,
                  );
                }}
              >
                {t('ai.skills.action.visualize', '查看只读设计图')}
              </Button>
            </Space>
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label={t('ai.skills.column.artifact', 'OCI 制品')}>
                <Text copyable>{inspectingVersion.artifactReference}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.field.artifactDigest', 'OCI Manifest Digest')}>
                <Text copyable>{inspectingVersion.artifactDigest}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.details.configDigest', 'Config Digest')}>
                <Text copyable>{inspectingVersion.artifactConfigDigest}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.details.contentDigest', 'Manifest 层 Digest')}>
                <Text copyable>{inspectingVersion.artifactContentDigest}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.details.signature', '签名校验')}>
                <Tag color={inspectingVersion.artifactSignatureVerified ? 'green' : 'blue'}>
                  {inspectingVersion.artifactSignatureVerified
                    ? t('ai.skills.supplyChain.signed', '签名已验证')
                    : t('ai.skills.supplyChain.contentOnly', '内容已验证')}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.details.policyHash', '校验策略 Hash')}>
                <Text copyable>{inspectingVersion.artifactVerificationPolicyHash}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.details.verifiedAt', '校验时间')}>
                {inspectingVersion.artifactVerifiedAt}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.details.contentHash', '内容 Hash')}>
                <Text copyable>{inspectingVersion.contentHash}</Text>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.details.executionBudget', '执行预算')}>
                {inspectingVersion.budget
                  ? t(
                    'ai.skills.details.executionBudgetValue',
                    '{calls} 次 Tool 调用 · {duration} 秒 · {payload}',
                    {
                      calls: inspectingVersion.budget.maximumToolCalls,
                      duration: inspectingVersion.budget.maximumDurationSeconds,
                      payload: formatBytes(inspectingVersion.budget.maximumPayloadBytes),
                    },
                  )
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.details.workflow', '工作流结构')}>
                {inspectingWorkflow
                  ? t(
                    'ai.skills.details.workflowValue',
                    '{tools} 个 Tool · {prompts} 个 Prompt · {resources} 个 Resource · {conditions} 个条件 · {parallels} 个并行节点/{branches} 个分支',
                    inspectingWorkflow,
                  )
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.details.approvalPolicy', '执行审批')}>
                {inspectingVersion.approvalPolicy?.required
                  ? (
                    <Space wrap>
                      <Tag color="orange">
                        {t('ai.skills.approval.required', '需要审批')}
                      </Tag>
                      <Tag color={inspectingVersion.approvalPolicy.allowSelfApproval ? 'gold' : 'green'}>
                        {inspectingVersion.approvalPolicy.allowSelfApproval
                          ? t('ai.skills.approval.selfAllowed', '允许自审')
                          : t('ai.skills.approval.separation', '申请与审批分离')}
                      </Tag>
                      {inspectingVersion.approvalPolicy.instructions}
                    </Space>
                  )
                  : t('ai.skills.approval.notRequired', '无需审批')}
              </Descriptions.Item>
            </Descriptions>
            <Card
              size="small"
              style={{marginTop: 16}}
              title={t('ai.skills.details.capabilityBindings', '固定 MCP 能力')}
            >
              <Space direction="vertical" size={12} style={{width: '100%'}}>
                <div>
                  <Text strong>{t('ai.skills.details.toolBindings', 'Tools')}</Text>
                  <div style={{marginTop: 8}}>
                    <Space wrap>
                      {inspectingVersion.toolBindings?.length
                        ? inspectingVersion.toolBindings.map((binding) => (
                          <Tag key={binding.id} color="blue">
                            {`${binding.toolAlias} → ${binding.toolName} · ${binding.mcpServerId}@${binding.capabilitySnapshotId}`}
                          </Tag>
                        ))
                        : <Text type="secondary">-</Text>}
                    </Space>
                  </div>
                </div>
                <div>
                  <Text strong>{t('ai.skills.details.promptBindings', 'Prompts')}</Text>
                  <div style={{marginTop: 8}}>
                    <Space wrap>
                      {inspectingVersion.promptBindings?.length
                        ? inspectingVersion.promptBindings.map((binding) => (
                          <Tag key={binding.id} color="purple">
                            {`${binding.promptAlias} → ${binding.promptName} · ${binding.mcpServerId}@${binding.capabilitySnapshotId}`}
                          </Tag>
                        ))
                        : <Text type="secondary">-</Text>}
                    </Space>
                  </div>
                </div>
                <div>
                  <Text strong>{t('ai.skills.details.resourceBindings', 'Resources')}</Text>
                  <div style={{marginTop: 8}}>
                    <Space wrap>
                      {inspectingVersion.resourceBindings?.length
                        ? inspectingVersion.resourceBindings.map((binding) => (
                          <Tag key={binding.id} color="cyan">
                            {`${binding.resourceAlias} → ${binding.resourceSelector}${binding.resourceTemplate ? t('ai.skills.capability.templateSuffix', '（模板）') : ''} · ${binding.mcpServerId}@${binding.capabilitySnapshotId}`}
                          </Tag>
                        ))
                        : <Text type="secondary">-</Text>}
                    </Space>
                  </div>
                </div>
              </Space>
            </Card>
            <Paragraph
              copyable={{text: JSON.stringify(inspectingVersion.manifest ?? {}, null, 2)}}
              style={{
                marginTop: 16,
                padding: 16,
                maxHeight: 420,
                overflow: 'auto',
                whiteSpace: 'pre-wrap',
                fontFamily: 'monospace',
                background: 'rgba(127, 127, 127, 0.08)',
              }}
            >
              {JSON.stringify(inspectingVersion.manifest ?? {}, null, 2)}
            </Paragraph>
          </>
        )}
      </Modal>

      <Modal
        open={Boolean(executionSkill)}
        title={`${t('ai.skills.execution.run', '执行 Skill')} · ${executionSkill?.name ?? ''}`}
        width={720}
        footer={null}
        onCancel={() => setExecutionSkill(undefined)}
        destroyOnHidden
      >
        <Alert
          showIcon
          type="info"
          closable
          style={{marginBottom: 16}}
          message={t(
            'ai.skills.execution.notice',
            '输入会按已发布版本的 Schema 校验；所有节点只调用版本固定的 MCP Snapshot 及 Tool、Prompt、Resource。',
          )}
        />
        <SchemaExecutionForm
          schema={executionInputSchema}
          loading={executionSchemaLoading}
          submitting={executionSubmitting}
          submitText={t('ai.skills.action.execute', '执行 Skill')}
          onSubmit={runSkill}
        />
      </Modal>

      <Drawer
        open={Boolean(executionHistorySkill)}
        width={1120}
        title={`${t('ai.skills.execution.history', 'Skill 执行记录')} · ${executionHistorySkill?.name ?? ''}`}
        extra={executionHistorySkill && (
          <Button onClick={() => void loadExecutions(executionHistorySkill)}>
            {t('ai.skills.action.refresh', '刷新')}
          </Button>
        )}
        onClose={() => setExecutionHistorySkill(undefined)}
      >
        <DataTable
          rowKey="id"
          size="small"
          loading={executionHistoryLoading}
          dataSource={executionHistory}
          columns={executionColumns}
          pagination={{pageSize: 20}}
          scroll={{x: 1000}}
        />
      </Drawer>

      <Modal
        open={Boolean(executionDetails)}
        width={980}
        footer={executionDetails && executionDetailsSkill ? (
          <Space>
            {executionDetailsApprovalPending && (
              <>
                <Button
                  type="primary"
                  onClick={() => openExecutionAction(
                    'approve',
                    executionDetailsSkill,
                    executionDetails,
                  )}
                >
                  {t('ai.skills.action.approve', '通过')}
                </Button>
                <Button
                  danger
                  onClick={() => openExecutionAction(
                    'reject',
                    executionDetailsSkill,
                    executionDetails,
                  )}
                >
                  {t('ai.skills.action.reject', '驳回')}
                </Button>
              </>
            )}
            {['WAITING_APPROVAL', 'PENDING', 'RUNNING'].includes(executionDetails.status) && (
              <Button onClick={() => openExecutionAction(
                'pause',
                executionDetailsSkill,
                executionDetails,
              )}>
                {t('ai.skills.action.pause', '暂停')}
              </Button>
            )}
            {executionDetails.status === 'PAUSED' && (
              <Button
                type="primary"
                onClick={() => void resumeExecution(
                  executionDetailsSkill,
                  executionDetails,
                )}
              >
                {t('ai.skills.action.resume', '恢复')}
              </Button>
            )}
            <Button onClick={() => setExecutionDetails(undefined)}>
              {t('ai.skills.action.close', '关闭')}
            </Button>
          </Space>
        ) : null}
        title={`${t('ai.skills.execution.details', '执行详情')} · ${executionDetails?.id ?? ''}`}
        onCancel={() => setExecutionDetails(undefined)}
      >
        {executionDetails && (
          <>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('ai.skills.column.status', '状态')}>
                <Tag color={executionStatusColor(executionDetails.status)}>
                  {skillDebugExecutionStatusLabel(t, executionDetails.status)}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.attempts', '执行次数')}>
                {executionDetails.attemptCount}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.currentStep', '当前步骤')}>
                {executionDetails.currentStepId ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.startedAt', '开始时间')}>
                {executionDetails.startedAt ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.toolCallBudget', 'Tool 调用预算')}>
                {`${executionDetails.consumedToolCalls ?? 0} / ${executionDetails.maximumToolCalls ?? '-'}`}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.payloadBudget', '载荷预算')}>
                {`${formatBytes(executionDetails.consumedPayloadBytes)} / ${formatBytes(executionDetails.maximumPayloadBytes)}`}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.durationBudget', '时长预算')}>
                {executionDetails.maximumDurationSeconds === undefined
                  ? '-'
                  : `${executionDetails.maximumDurationSeconds} s`}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.deadlineAt', '截止时间')}>
                {executionDetails.deadlineAt ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.requestedBy', '申请人')}>
                {executionDetails.requestedBy ?? '-'}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.execution.approvalState', '审批状态')}>
                {executionDetails.rejectedAt
                  ? `${t('ai.skills.execution.rejected', '已驳回')} · ${executionDetails.rejectedBy ?? '-'}`
                  : executionDetails.approvedAt
                    ? `${t('ai.skills.execution.approved', '已通过')} · ${executionDetails.approvedBy ?? '-'}`
                    : executionDetails.approvalRequired
                      ? t('ai.skills.execution.awaitingApproval', '等待审批')
                      : t('ai.skills.approval.notRequired', '无需审批')}
              </Descriptions.Item>
              {executionDetails.approvalInstructions && (
                <Descriptions.Item
                  span={2}
                  label={t('ai.skills.execution.approvalInstructions', '审批说明')}
                >
                  {executionDetails.approvalInstructions}
                </Descriptions.Item>
              )}
              {(executionDetails.pauseRequestedAt || executionDetails.pausedAt) && (
                <Descriptions.Item
                  span={2}
                  label={t('ai.skills.execution.pauseState', '暂停状态')}
                >
                  {executionDetails.status === 'RUNNING' && executionDetails.pauseRequested
                    ? t('ai.skills.execution.pausePending', '将在当前工具调用结束后暂停')
                    : `${executionDetails.pausedAt ?? executionDetails.pauseRequestedAt ?? '-'} · ${executionDetails.pauseReason ?? '-'}`}
                </Descriptions.Item>
              )}
              <Descriptions.Item
                span={2}
                label={t('ai.skills.execution.error', '错误')}
              >
                {executionDetails.errorMessage ?? '-'}
              </Descriptions.Item>
            </Descriptions>
            <DataTable
              rowKey="id"
              size="small"
              style={{marginTop: 16}}
              dataSource={executionDetails.steps ?? []}
              columns={executionStepColumns}
              pagination={false}
              scroll={{x: 800}}
            />
            <Paragraph
              copyable={{text: JSON.stringify(executionDetails.output ?? {}, null, 2)}}
              style={{
                marginTop: 16,
                padding: 16,
                maxHeight: 320,
                overflow: 'auto',
                whiteSpace: 'pre-wrap',
                fontFamily: 'monospace',
                background: 'rgba(127, 127, 127, 0.08)',
              }}
            >
              {JSON.stringify(executionDetails.output ?? {}, null, 2)}
            </Paragraph>
          </>
        )}
      </Modal>

      <Modal
        open={Boolean(executionAction)}
        title={{
          approve: t('ai.skills.execution.approveTitle', '审批通过执行'),
          reject: t('ai.skills.execution.rejectTitle', '驳回执行'),
          pause: t('ai.skills.execution.pauseTitle', '暂停执行'),
        }[executionAction?.action ?? 'pause']}
        confirmLoading={executionActionSubmitting}
        okButtonProps={{danger: executionAction?.action === 'reject'}}
        okText={executionAction?.action === 'approve'
          ? t('ai.skills.action.approve', '通过')
          : executionAction?.action === 'reject'
            ? t('ai.skills.action.reject', '驳回')
            : t('ai.skills.action.pause', '暂停')}
        onOk={() => void submitExecutionAction()}
        onCancel={() => setExecutionAction(undefined)}
      >
        <TextArea
          rows={4}
          maxLength={1024}
          showCount
          value={executionActionComment}
          placeholder={executionAction?.action === 'pause'
            ? t('ai.skills.execution.pauseReason', '可填写暂停原因')
            : t('ai.skills.execution.decisionComment', '可填写审批意见')}
          onChange={(event) => setExecutionActionComment(event.target.value)}
        />
      </Modal>
    </div>
  );
};

export default Skills;
