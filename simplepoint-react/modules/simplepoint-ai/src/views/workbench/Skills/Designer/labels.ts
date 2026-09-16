import type {
  DesignerNodeType,
  SkillDebugExecution,
  SkillDebugNodeStatus,
  SkillMockTestRun,
  SkillPublishTaskStage,
  SkillPublishTaskStatus,
} from './types';

type Translate = (key: string, fallback?: string) => string;

export const skillNodeTypeLabel = (
  t: Translate,
  type: DesignerNodeType,
) => {
  switch (type) {
    case 'INPUT':
      return t('ai.skills.designer.node.input', 'Skill 输入');
    case 'OUTPUT':
      return t('ai.skills.designer.node.output', 'Skill 输出');
    case 'TOOL':
      return t('ai.skills.designer.node.tool', 'MCP Tool');
    case 'PROMPT':
      return t('ai.skills.designer.node.prompt', 'MCP Prompt');
    case 'RESOURCE':
      return t('ai.skills.designer.node.resource', 'MCP Resource');
    case 'CONDITION':
      return t('ai.skills.designer.node.condition', '条件');
    case 'PARALLEL':
      return t('ai.skills.designer.node.parallel', '并行');
  }
};

export const skillDebugNodeStatusLabel = (
  t: Translate,
  status: SkillDebugNodeStatus,
) => {
  switch (status) {
    case 'PENDING':
      return t('ai.skills.designer.debug.status.pending', '等待执行');
    case 'RUNNING':
      return t('ai.skills.designer.debug.status.running', '执行中');
    case 'SUCCEEDED':
      return t('ai.skills.designer.debug.status.succeeded', '已成功');
    case 'FAILED':
      return t('ai.skills.designer.debug.status.failed', '已失败');
    case 'SKIPPED':
      return t('ai.skills.designer.debug.status.skipped', '已跳过');
    case 'WAITING_APPROVAL':
      return t('ai.skills.designer.debug.status.waitingApproval', '等待审批');
    case 'PAUSED':
      return t('ai.skills.designer.debug.status.paused', '已暂停');
  }
};

export const skillDebugExecutionStatusLabel = (
  t: Translate,
  status: SkillDebugExecution['status'],
) => {
  switch (status) {
    case 'WAITING_APPROVAL':
      return t('ai.skills.designer.debug.status.waitingApproval', '等待审批');
    case 'PENDING':
      return t('ai.skills.designer.debug.status.pending', '等待执行');
    case 'RUNNING':
      return t('ai.skills.designer.debug.status.running', '执行中');
    case 'PAUSED':
      return t('ai.skills.designer.debug.status.paused', '已暂停');
    case 'SUCCEEDED':
      return t('ai.skills.designer.debug.status.succeeded', '已成功');
    case 'FAILED':
      return t('ai.skills.designer.debug.status.failed', '已失败');
    case 'REJECTED':
      return t('ai.skills.designer.debug.status.rejected', '已驳回');
    case 'CANCELLED':
      return t('ai.skills.designer.debug.status.cancelled', '已取消');
  }
};

export const skillDebugModeLabel = (
  t: Translate,
  mode: SkillDebugExecution['debugMode'],
) => mode === 'MOCK'
  ? t('ai.skills.designer.debug.mode.mock', '模拟测试')
  : t('ai.skills.designer.debug.mode.live', '真实调用');

export const skillValidationStatusLabel = (
  t: Translate,
  status: 'VALID' | 'INVALID',
) => status === 'VALID'
  ? t('ai.skills.designer.validation.valid', '有效')
  : t('ai.skills.designer.validation.invalid', '无效');

export const skillDraftRevisionSourceLabel = (
  t: Translate,
  source?: string,
) => {
  switch (source) {
    case 'SAVE':
      return t('ai.skills.designer.history.source.SAVE', '保存');
    case 'RESTORE':
      return t('ai.skills.designer.history.source.RESTORE', '历史恢复');
    case 'VERSION_COPY':
      return t('ai.skills.designer.history.source.VERSION_COPY', '版本复制');
    default:
      return t('ai.skills.designer.history.source.UNKNOWN', '来源未知');
  }
};

export const skillDesignerDiagnosticLabel = (
  t: Translate,
  code?: string,
) => {
  if (code === 'SKILL_DESIGNER_WORKFLOW_EMPTY') {
    return t(
      'ai.skills.designer.diagnostics.workflowEmpty',
      '请从左侧添加并连接至少一个 MCP Tool、Prompt 或 Resource 节点',
    );
  }
  if (code?.includes('_SCHEMA_')) {
    return t(
      'ai.skills.designer.diagnostics.schemaInvalid',
      '输入或输出数据结构配置不完整，请检查 Schema',
    );
  }
  if (code?.includes('_EDGE_') || code?.includes('_PORT_')) {
    return t(
      'ai.skills.designer.diagnostics.connectionInvalid',
      '节点连线配置不完整或无效，请检查连接方向和端口',
    );
  }
  if (code?.includes('_CAPABILITY_')
    || code?.includes('_BINDING_')
    || code?.includes('_REFERENCE_')) {
    return t(
      'ai.skills.designer.diagnostics.capabilityInvalid',
      'MCP 能力绑定不完整或已不可用，请重新选择能力',
    );
  }
  if (code?.includes('_CONDITION_')
    || code?.includes('_BRANCH_')
    || code?.includes('_PARALLEL_')
    || code?.includes('_CONTROL_')) {
    return t(
      'ai.skills.designer.diagnostics.flowInvalid',
      '流程分支或控制节点配置无效，请检查节点属性',
    );
  }
  if (code?.includes('_TEST_')) {
    return t(
      'ai.skills.designer.diagnostics.testInvalid',
      '测试用例配置不完整或无效，请检查测试数据',
    );
  }
  if (code?.includes('_NODE_')) {
    return t(
      'ai.skills.designer.diagnostics.nodeInvalid',
      '节点配置不完整或无效，请定位后完善必填项',
    );
  }
  return t(
    'ai.skills.designer.diagnostics.configurationInvalid',
    '当前设计存在无效配置，请按提示检查画布',
  );
};

export const skillDesignerDiagnosticLocationLabel = (
  t: Translate,
  fieldPath?: string | null,
) => {
  const root = fieldPath?.split(/[.[\]]/, 1)[0];
  switch (root) {
    case 'nodes':
      return t('ai.skills.designer.diagnostics.location.nodes', '画布节点');
    case 'edges':
      return t('ai.skills.designer.diagnostics.location.edges', '节点连线');
    case 'inputSchema':
      return t('ai.skills.designer.diagnostics.location.inputSchema', '输入 Schema');
    case 'outputSchema':
      return t('ai.skills.designer.diagnostics.location.outputSchema', '输出 Schema');
    case 'tests':
      return t('ai.skills.designer.diagnostics.location.tests', '测试用例');
    case 'metadata':
      return t('ai.skills.designer.diagnostics.location.metadata', '基础信息');
    case 'bindings':
      return t('ai.skills.designer.diagnostics.location.bindings', 'MCP 能力绑定');
    default:
      return fieldPath
        ? t('ai.skills.designer.diagnostics.location.configuration', '设计配置')
        : undefined;
  }
};

export const skillStepTypeLabel = (
  t: Translate,
  type?: string,
) => {
  const normalized = type?.toUpperCase();
  switch (normalized) {
    case 'INPUT':
    case 'OUTPUT':
    case 'TOOL':
    case 'PROMPT':
    case 'RESOURCE':
    case 'CONDITION':
    case 'PARALLEL':
      return skillNodeTypeLabel(t, normalized);
    default:
      return t('ai.skills.designer.node.unknown', '未知节点');
  }
};

export const skillLifecycleStatusLabel = (
  t: Translate,
  status?: string,
) => {
  switch (status) {
    case 'DRAFT':
      return t('ai.skills.lifecycle.draft', '草稿');
    case 'ACTIVE':
      return t('ai.skills.lifecycle.active', '已激活');
    case 'DISABLED':
      return t('ai.skills.lifecycle.disabled', '已停用');
    case 'PUBLISHED':
      return t('ai.skills.lifecycle.published', '已发布');
    case 'DEPRECATED':
      return t('ai.skills.lifecycle.deprecated', '已废弃');
    default:
      return t('ai.skills.lifecycle.unknown', '状态未知');
  }
};

export const skillMockTestRunStatusLabel = (
  t: Translate,
  status: SkillMockTestRun['status'],
) => {
  switch (status) {
    case 'RUNNING':
      return t('ai.skills.designer.tests.run.status.running', '执行中');
    case 'PASSED':
      return t('ai.skills.designer.tests.run.status.passed', '全部通过');
    case 'FAILED':
      return t('ai.skills.designer.tests.run.status.failed', '存在失败');
  }
};

export const skillPublishTaskStatusLabel = (
  t: Translate,
  status: SkillPublishTaskStatus,
) => {
  switch (status) {
    case 'PENDING':
      return t('ai.skills.designer.publish.status.pending', '等待发布');
    case 'RUNNING':
      return t('ai.skills.designer.publish.status.running', '发布中');
    case 'SUCCEEDED':
      return t('ai.skills.designer.publish.status.succeeded', '发布成功');
    case 'FAILED':
      return t('ai.skills.designer.publish.status.failed', '发布失败');
  }
};

export const skillPublishStageLabel = (
  t: Translate,
  stage: SkillPublishTaskStage,
) => {
  switch (stage) {
    case 'QUEUED':
      return t('ai.skills.designer.publish.stage.QUEUED', '等待处理');
    case 'GENERATING':
      return t('ai.skills.designer.publish.stage.GENERATING', '生成制品');
    case 'PUSHING':
      return t('ai.skills.designer.publish.stage.PUSHING', '推送制品');
    case 'VERIFYING':
      return t('ai.skills.designer.publish.stage.VERIFYING', '校验制品');
    case 'CREATING_VERSION':
      return t('ai.skills.designer.publish.stage.CREATING_VERSION', '创建版本');
    case 'ACTIVATING':
      return t('ai.skills.designer.publish.stage.ACTIVATING', '激活版本');
    case 'COMPLETED':
      return t('ai.skills.designer.publish.stage.COMPLETED', '已完成');
  }
};
