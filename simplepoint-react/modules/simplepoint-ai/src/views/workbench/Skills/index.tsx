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
  Typography,
  message,
} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';

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
  toolBindings?: SkillToolBinding[];
};

type SkillExecutionStep = {
  id: string;
  stepId: string;
  stepType: string;
  stepOrder: number;
  toolAlias?: string;
  toolName?: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED';
  attemptCount: number;
  input?: Record<string, unknown>;
  output?: unknown;
  errorMessage?: string;
};

type SkillExecution = {
  id: string;
  skillId: string;
  skillVersionId: string;
  status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
  currentStepId?: string;
  attemptCount: number;
  requestedBy?: string;
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

const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return fallback;
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
    workflow: {
      steps: [],
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
  FAILED: 'red',
  CANCELLED: 'default',
}[status]);

const Skills = () => {
  const config = api['ai-workbench.skills'];
  const {t, ensure, locale} = useI18n();
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
  const [inspectingVersion, setInspectingVersion] = useState<SkillVersion>();
  const [executionSkill, setExecutionSkill] = useState<SkillDefinition>();
  const [executionInput, setExecutionInput] = useState('{}');
  const [executionSubmitting, setExecutionSubmitting] = useState(false);
  const [executionHistorySkill, setExecutionHistorySkill] = useState<SkillDefinition>();
  const [executionHistory, setExecutionHistory] = useState<SkillExecution[]>([]);
  const [executionHistoryLoading, setExecutionHistoryLoading] = useState(false);
  const [executionDetails, setExecutionDetails] = useState<SkillExecution>();

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
      message.error(resolveErrorMessage(
        error,
        t('ai.skills.error.load', 'Skill 列表加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [config.baseUrl, t]);

  const loadVersions = useCallback(async (skill?: SkillDefinition) => {
    if (!skill) {
      setVersions([]);
      return;
    }
    setVersionLoading(true);
    try {
      const page = await get<Page<SkillVersion>>(
        `${config.baseUrl}/${skill.id}/versions`,
        {page: 0, size: 500, sort: 'createdAt,desc'},
      );
      setVersions(page.content ?? []);
    } catch (error) {
      setVersions([]);
      message.error(resolveErrorMessage(
        error,
        t('ai.skills.error.loadVersions', 'Skill 版本加载失败'),
      ));
    } finally {
      setVersionLoading(false);
    }
  }, [config.baseUrl, t]);

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
      message.error(resolveErrorMessage(
        error,
        t('ai.skills.error.save', 'Skill 保存失败'),
      ));
    } finally {
      setSaving(false);
    }
  };

  const removeSkill = (skill: SkillDefinition) => {
    Modal.confirm({
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
          message.error(resolveErrorMessage(
            error,
            t('ai.skills.error.delete', 'Skill 删除失败'),
          ));
        }
      },
    });
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
      message.error(resolveErrorMessage(
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
      message.error(resolveErrorMessage(
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
      message.error(resolveErrorMessage(
        error,
        t('ai.skills.error.loadExecutions', 'Skill 执行记录加载失败'),
      ));
    } finally {
      setExecutionHistoryLoading(false);
    }
  }, [config.baseUrl, t]);

  const openExecution = (skill: SkillDefinition) => {
    setExecutionSkill(skill);
    setExecutionInput('{}');
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

  const runSkill = async () => {
    if (!executionSkill) return;
    let input: Record<string, unknown>;
    try {
      const decoded: unknown = JSON.parse(executionInput);
      if (!decoded || Array.isArray(decoded) || typeof decoded !== 'object') {
        throw new Error('not object');
      }
      input = decoded as Record<string, unknown>;
    } catch {
      message.error(t('ai.skills.execution.inputInvalid', '执行输入必须是 JSON 对象'));
      return;
    }
    setExecutionSubmitting(true);
    try {
      let execution = await post<SkillExecution>(
        `${config.baseUrl}/${executionSkill.id}/executions`,
        {idempotencyKey: crypto.randomUUID(), input},
      );
      setExecutionDetails(execution);
      setExecutionSkill(undefined);
      for (let index = 0; index < 60
        && (execution.status === 'PENDING' || execution.status === 'RUNNING');
        index += 1) {
        await new Promise((resolve) => window.setTimeout(resolve, 500));
        execution = await refreshExecution(executionSkill, execution.id);
        setExecutionDetails(execution);
      }
      message.success(execution.status === 'SUCCEEDED'
        ? t('ai.skills.execution.succeeded', 'Skill 执行成功')
        : execution.status === 'FAILED'
          ? t('ai.skills.execution.failed', 'Skill 执行失败')
          : t('ai.skills.execution.submitted', 'Skill 已提交执行'));
    } catch (error) {
      message.error(resolveErrorMessage(
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
        <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>{value}</Tag>
      ),
    },
    {
      title: t('ai.skills.column.status', '状态'),
      dataIndex: 'status',
      width: 100,
      render: (value: SkillDefinition['status']) => (
        <Tag color={skillStatusColor(value)}>{value}</Tag>
      ),
    },
    {
      title: t('ai.skills.column.action', '操作'),
      key: 'action',
      width: 360,
      fixed: 'right' as const,
      render: (_: unknown, skill: SkillDefinition) => (
        <Space size={4}>
          {skill.status === 'ACTIVE' && (
            <Button type="link" onClick={() => openExecution(skill)}>
              {t('ai.skills.action.execute', '执行')}
            </Button>
          )}
          <Button type="link" onClick={() => openExecutionHistory(skill)}>
            {t('ai.skills.action.executions', '执行记录')}
          </Button>
          <Button type="link" onClick={() => setSelectedSkill(skill)}>
            {t('ai.skills.action.versions', '版本')}
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
  ], [t]);

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
        <Tag color={executionStatusColor(value)}>{value}</Tag>
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
      title: t('ai.skills.execution.startedAt', '开始时间'),
      dataIndex: 'startedAt',
      width: 190,
      render: (value?: string) => value ?? '-',
    },
    {
      title: t('ai.skills.column.action', '操作'),
      key: 'action',
      width: 90,
      render: (_: unknown, execution: SkillExecution) => (
        <Button type="link" onClick={() => setExecutionDetails(execution)}>
          {t('ai.skills.action.inspect', '查看')}
        </Button>
      ),
    },
  ], [t]);

  const executionStepColumns = useMemo(() => [
    {
      title: t('ai.skills.execution.step', '步骤'),
      dataIndex: 'stepId',
      width: 140,
    },
    {
      title: t('ai.skills.execution.tool', 'MCP Tool'),
      dataIndex: 'toolName',
      width: 180,
      render: (value?: string, step?: SkillExecutionStep) => (
        <Text code>{step?.toolAlias ? `${step.toolAlias} → ` : ''}{value ?? '-'}</Text>
      ),
    },
    {
      title: t('ai.skills.column.status', '状态'),
      dataIndex: 'status',
      width: 110,
      render: (value: SkillExecutionStep['status']) => (
        <Tag color={value === 'SUCCEEDED' ? 'green' : value === 'FAILED' ? 'red' : 'blue'}>
          {value}
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
      title: t('ai.skills.column.bindings', '工具绑定'),
      dataIndex: 'toolBindings',
      width: 100,
      render: (value?: SkillToolBinding[]) => value?.length ?? 0,
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
        <Tag color={versionStatusColor(value)}>{value}</Tag>
      ),
    },
    {
      title: t('ai.skills.column.action', '操作'),
      key: 'action',
      width: 210,
      render: (_: unknown, version: SkillVersion) => (
        <Space size={2}>
          <Button type="link" onClick={() => setInspectingVersion(version)}>
            {t('ai.skills.action.inspect', '查看')}
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
  ], [selectedSkill, t]);

  return (
    <div style={{height: '100%', display: 'flex', flexDirection: 'column', gap: 16}}>
      <Alert
        showIcon
        type="info"
        message={t('ai.skills.notice.title', '声明式 Skill Registry')}
        description={t(
          'ai.skills.notice.description',
          'Skill 不包含可执行代码；版本固定 OCI Digest、MCP 能力快照和 Tool Schema。新增 Skill 无需修改平台代码。',
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
        <Table
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
        <Table
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
              '此 JSON 必须与 OCI Artifact 中的 Skill Manifest 层完全一致；Tool 工作流步骤只能引用已绑定 alias。',
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
            <Descriptions bordered size="small" column={1}>
              <Descriptions.Item label="Artifact">
                <Text copyable>{inspectingVersion.artifactReference}</Text>
              </Descriptions.Item>
              <Descriptions.Item label="Digest">
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
              <Descriptions.Item label="Content Hash">
                <Text copyable>{inspectingVersion.contentHash}</Text>
              </Descriptions.Item>
            </Descriptions>
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
        confirmLoading={executionSubmitting}
        onOk={() => void runSkill()}
        onCancel={() => setExecutionSkill(undefined)}
      >
        <Alert
          showIcon
          type="info"
          style={{marginBottom: 16}}
          message={t(
            'ai.skills.execution.notice',
            '输入会按已发布版本的 Schema 校验；Workflow 只调用版本固定的 MCP Snapshot 和 Tool。',
          )}
        />
        <TextArea
          rows={12}
          spellCheck={false}
          value={executionInput}
          onChange={(event) => setExecutionInput(event.target.value)}
          style={{fontFamily: 'monospace'}}
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
        <Table
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
        footer={null}
        title={`${t('ai.skills.execution.details', '执行详情')} · ${executionDetails?.id ?? ''}`}
        onCancel={() => setExecutionDetails(undefined)}
      >
        {executionDetails && (
          <>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('ai.skills.column.status', '状态')}>
                <Tag color={executionStatusColor(executionDetails.status)}>
                  {executionDetails.status}
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
              <Descriptions.Item
                span={2}
                label={t('ai.skills.execution.error', '错误')}
              >
                {executionDetails.errorMessage ?? '-'}
              </Descriptions.Item>
            </Descriptions>
            <Table
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
    </div>
  );
};

export default Skills;
