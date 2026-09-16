import {
  CheckCircleOutlined,
  CloudUploadOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  App,
  Alert,
  Button,
  Descriptions,
  Divider,
  Drawer,
  Empty,
  Form,
  Input,
  Space,
  Spin,
  Steps,
  Switch,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router';
import {
  checkManagedRegistry,
  loadManagedRegistry,
  loadPublishTask,
  loadPublishTasks,
  loadSkillVersions,
  retryPublishTask,
  startPublishTask,
} from './api';
import {skillPublishStageLabel, skillPublishTaskStatusLabel} from './labels';
import type {
  SkillDesignerCompilation,
  SkillDesignerDocument,
  SkillDraftView,
  SkillManagedRegistryStatus,
  SkillPublishTask,
  SkillPublishTaskStage,
  SkillVersionSummary,
} from './types';

const {Text} = Typography;

const SEMANTIC_VERSION = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$/;

const stages: SkillPublishTaskStage[] = [
  'QUEUED',
  'GENERATING',
  'PUSHING',
  'VERIFYING',
  'CREATING_VERSION',
  'ACTIVATING',
  'COMPLETED',
];

const randomKey = () => globalThis.crypto?.randomUUID?.()
  ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;

const registryStatusColor = (registry?: SkillManagedRegistryStatus) => {
  if (!registry?.configured) return 'default';
  if (registry.connected === true) return 'success';
  if (registry.connected === false) return 'error';
  return 'warning';
};

type Props = {
  open: boolean;
  skillId: string;
  draft: SkillDraftView;
  document: SkillDesignerDocument;
  compilation: SkillDesignerCompilation;
  onClose: () => void;
};

const SkillPublishDrawer = ({open, skillId, draft, document, compilation, onClose}: Props) => {
  const {t} = useI18n();
  const {message} = App.useApp();
  const navigate = useNavigate();
  const [registry, setRegistry] = useState<SkillManagedRegistryStatus>();
  const [versions, setVersions] = useState<SkillVersionSummary[]>([]);
  const [tasks, setTasks] = useState<SkillPublishTask[]>([]);
  const [task, setTask] = useState<SkillPublishTask>();
  const [loading, setLoading] = useState(false);
  const [checking, setChecking] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [version, setVersion] = useState('');
  const [activate, setActivate] = useState(true);
  const [idempotencyKey, setIdempotencyKey] = useState(randomKey);
  const pollToken = useRef(0);
  const sourceKey = `${draft.id}:${draft.revision}`;

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      const [nextRegistry, versionPage, taskPage] = await Promise.all([
        loadManagedRegistry(),
        loadSkillVersions(skillId),
        loadPublishTasks(skillId),
      ]);
      const nextTasks = taskPage.content ?? [];
      setRegistry(nextRegistry);
      setVersions(versionPage.content ?? []);
      setTasks(nextTasks);
      setTask((current) => current
        ? nextTasks.find((item) => item.id === current.id) ?? current
        : nextTasks.find((item) => item.status === 'PENDING' || item.status === 'RUNNING'));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.publish.loadFailed', '发布信息加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [message, skillId, t]);

  useEffect(() => {
    if (!open) return;
    setTask(undefined);
    setVersion('');
    setActivate(true);
    setIdempotencyKey(randomKey());
    void refresh();
  }, [open, refresh, sourceKey]);

  useEffect(() => {
    if (!open || !task || !['PENDING', 'RUNNING'].includes(task.status)) return;
    const token = ++pollToken.current;
    let timer: number | undefined;
    const poll = async () => {
      try {
        const next = await loadPublishTask(skillId, task.id);
        if (token !== pollToken.current) return;
        setTask(next);
        setTasks((items) => [next, ...items.filter((item) => item.id !== next.id)]);
        if (next.status === 'PENDING' || next.status === 'RUNNING') {
          timer = window.setTimeout(poll, 1000);
        } else if (next.status === 'SUCCEEDED') {
          message.success(t(
            'ai.skills.designer.publish.succeeded',
            'Skill {version} 发布完成',
            {version: next.version},
          ));
          void loadSkillVersions(skillId).then((page) => setVersions(page.content ?? []));
        }
      } catch (error) {
        if (token !== pollToken.current) return;
        message.error(resolveApiErrorMessage(
          error,
          t('ai.skills.designer.publish.refreshFailed', '发布进度刷新失败，可稍后手动重试'),
        ));
      }
    };
    timer = window.setTimeout(poll, 800);
    return () => {
      pollToken.current += 1;
      if (timer) window.clearTimeout(timer);
    };
  }, [message, open, skillId, t, task?.id, task?.status]);

  const latestVersion = versions[0];
  const unchanged = Boolean(latestVersion?.contentHash && latestVersion.contentHash === compilation.contentHash);
  const counts = useMemo(() => ({
    nodes: document.nodes.length,
    tools: document.tools.length,
    prompts: document.prompts.length,
    resources: document.resources.length,
    tests: document.tests.length,
  }), [document]);

  const checkRegistry = async () => {
    setChecking(true);
    try {
      const checked = await checkManagedRegistry();
      setRegistry(checked);
      message.success(t('ai.skills.designer.publish.registryConnected', 'Registry 连接正常'));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.publish.registryFailed', 'Registry 连接检查失败'),
      ));
    } finally {
      setChecking(false);
    }
  };

  const submit = async () => {
    const normalizedVersion = version.trim();
    if (!SEMANTIC_VERSION.test(normalizedVersion)) {
      message.warning(t(
        'ai.skills.designer.publish.versionInvalid',
        '请输入有效的语义版本，例如 1.2.0 或 1.2.0-rc.1',
      ));
      return;
    }
    if (versions.some((item) => item.version === normalizedVersion)) {
      message.warning(t('ai.skills.designer.publish.versionExists', '该版本已经存在，请使用新的版本号'));
      return;
    }
    if (registry?.connected !== true) {
      message.warning(t('ai.skills.designer.publish.registryCheckRequired', '发布前请先确认 Registry 连接正常'));
      return;
    }
    setSubmitting(true);
    try {
      const created = await startPublishTask(
        skillId,
        draft.revision,
        normalizedVersion,
        activate,
        idempotencyKey,
      );
      setTask(created);
      setTasks((items) => [created, ...items.filter((item) => item.id !== created.id)]);
      message.success(t('ai.skills.designer.publish.started', '发布任务已提交，可安全关闭此页面'));
      setIdempotencyKey(randomKey());
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.publish.submitFailed', '发布任务提交失败；表单已保留，可直接重试'),
      ));
    } finally {
      setSubmitting(false);
    }
  };

  const retry = async () => {
    if (!task) return;
    setRetrying(true);
    try {
      const next = await retryPublishTask(skillId, task.id);
      setTask(next);
      setTasks((items) => [next, ...items.filter((item) => item.id !== next.id)]);
      message.success(t('ai.skills.designer.publish.retryStarted', '原发布任务已重新排队'));
    } catch (error) {
      message.error(resolveApiErrorMessage(
        error,
        t('ai.skills.designer.publish.retryFailed', '发布任务重试失败'),
      ));
    } finally {
      setRetrying(false);
    }
  };

  const currentStage = task ? Math.max(0, stages.indexOf(task.stage)) : 0;
  const stageItems = stages
    .filter((stage) => task?.activate !== false || stage !== 'ACTIVATING')
    .map((stage) => ({
      title: skillPublishStageLabel(t, stage),
    }));
  const displayedStage = task?.activate === false && currentStage >= stages.indexOf('ACTIVATING')
    ? currentStage - 1 : currentStage;

  return (
    <Drawer
      open={open}
      width={760}
      title={t('ai.skills.designer.publish.title', '发布 Skill 不可变版本')}
      onClose={onClose}
      extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>{t('ai.skills.designer.publish.refresh', '刷新')}</Button>}
    >
      <Spin spinning={loading && !registry}>
        <Alert
          type={registry?.connected === true ? 'success' : registry?.configured ? 'warning' : 'error'}
          showIcon
          message={registry?.configured
            ? t('ai.skills.designer.publish.registry', '托管 Registry：{registry}', {registry: registry.registry ?? '-'})
            : t('ai.skills.designer.publish.registryMissing', '尚未配置托管 OCI Registry')}
          description={registry?.configured
            ? `${registry.repositoryPrefix ?? '-'} · ${registry.secureTransport ? 'TLS' : 'HTTP'} · ${registry.signatureRequired ? t('ai.skills.designer.publish.signatureRequired', '必须验签') : t('ai.skills.designer.publish.signatureOptional', '不强制验签')}`
            : t('ai.skills.designer.publish.registryMissingDescription', '请先由管理员配置 Registry、允许列表和服务端凭据。')}
          action={registry?.configured ? (
            <Button size="small" loading={checking} onClick={() => void checkRegistry()}>
              {t('ai.skills.designer.publish.checkRegistry', '检查连接')}
            </Button>
          ) : undefined}
          style={{marginBottom: 16}}
        />
        <Descriptions bordered size="small" column={2}>
          <Descriptions.Item label={t('ai.skills.designer.publish.draftRevision', '草稿修订')}>{draft.revision}</Descriptions.Item>
          <Descriptions.Item label={t('ai.skills.designer.publish.validation', '校验结果')}>
            <Tag color="success" icon={<CheckCircleOutlined />}>{t('ai.skills.designer.publish.valid', '已通过')}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label={t('ai.skills.designer.common.contentHash', '内容 Hash')} span={2}>
            <Text copyable ellipsis>{compilation.contentHash}</Text>
          </Descriptions.Item>
          <Descriptions.Item label={t('ai.skills.designer.publish.changeSummary', '变更摘要')} span={2}>
            <Space wrap>
              <Tag>{t('ai.skills.designer.publish.nodeCount', '{count} 节点', {count: counts.nodes})}</Tag>
              <Tag>{t('ai.skills.designer.publish.toolCount', '{count} 工具', {count: counts.tools})}</Tag>
              <Tag>{t('ai.skills.designer.publish.promptCount', '{count} Prompt', {count: counts.prompts})}</Tag>
              <Tag>{t('ai.skills.designer.publish.resourceCount', '{count} 资源', {count: counts.resources})}</Tag>
              <Tag>{t('ai.skills.designer.publish.testCount', '{count} 测试', {count: counts.tests})}</Tag>
              {latestVersion && <Tag color={unchanged ? 'warning' : 'blue'}>
                {unchanged
                  ? t('ai.skills.designer.publish.unchanged', '与最新版本 {version} 内容相同', {version: latestVersion.version})
                  : t('ai.skills.designer.publish.changed', '相对最新版本 {version} 有变更', {version: latestVersion.version})}
              </Tag>}
            </Space>
          </Descriptions.Item>
        </Descriptions>

        {!task && (
          <Form layout="vertical" style={{marginTop: 18}} onFinish={() => void submit()}>
            <Form.Item
              label={t('ai.skills.designer.publish.version', '语义版本')}
              required
              validateStatus={version && !SEMANTIC_VERSION.test(version.trim()) ? 'error' : undefined}
              help={version && !SEMANTIC_VERSION.test(version.trim())
                ? t('ai.skills.designer.publish.versionInvalid', '请输入有效的语义版本，例如 1.2.0 或 1.2.0-rc.1')
                : t('ai.skills.designer.publish.versionHelp', '版本发布后不可覆盖或删除。')}
            >
              <Input
                autoFocus
                maxLength={64}
                placeholder="1.0.0"
                value={version}
                onChange={(event) => setVersion(event.target.value)}
              />
            </Form.Item>
            <Form.Item label={t('ai.skills.designer.publish.activate', '发布后立即激活')}>
              <Switch checked={activate} onChange={setActivate} />
            </Form.Item>
            {activate && (
              <Alert
                type="warning"
                showIcon
                message={t('ai.skills.designer.publish.activateWarning', '完成验签和版本创建后，将切换该 Skill 的活动版本')}
                description={t('ai.skills.designer.publish.activateDescription', '新的 Agent 执行会使用此版本；正在运行的执行仍保持原有固定版本。')}
                style={{marginBottom: 16}}
              />
            )}
            <Button
              type="primary"
              htmlType="submit"
              icon={<CloudUploadOutlined />}
              loading={submitting}
              disabled={!registry?.configured || registry.connected !== true || !compilation.contentHash}
            >
              {activate
                ? t('ai.skills.designer.publish.publishAndActivate', '发布并激活')
                : t('ai.skills.designer.publish.publishOnly', '仅发布版本')}
            </Button>
          </Form>
        )}

        {task && (
          <>
            <Divider>{t('ai.skills.designer.publish.progress', '发布进度')}</Divider>
            <Steps
              size="small"
              responsive
              current={displayedStage}
              status={task.status === 'FAILED' ? 'error' : task.status === 'SUCCEEDED' ? 'finish' : 'process'}
              items={stageItems}
            />
            {task.status === 'FAILED' && (
              <Alert
                type="error"
                showIcon
                message={task.errorCode ?? t('ai.skills.designer.publish.failed', '发布失败')}
                description={task.errorMessage}
                action={<Button danger loading={retrying} onClick={() => void retry()}>{t('ai.skills.designer.publish.retry', '重试原任务')}</Button>}
                style={{marginTop: 18}}
              />
            )}
            {task.status === 'SUCCEEDED' && (
              <Alert
                type="success"
                showIcon
                message={t('ai.skills.designer.publish.completed', '版本 {version} 已发布{activated}', {
                  version: task.version,
                  activated: task.activate ? t('ai.skills.designer.publish.activatedSuffix', '并激活') : '',
                })}
                action={task.skillVersionId ? (
                  <Space>
                    <Button onClick={() => navigate(`/ai/workbench/skill-designer?skillId=${encodeURIComponent(skillId)}&versionId=${encodeURIComponent(task.skillVersionId ?? '')}`)}>
                      {t('ai.skills.designer.publish.openVersion', '查看不可变版本')}
                    </Button>
                    <Button type="primary" onClick={() => navigate(`/ai/workbench/agents?skillVersionId=${encodeURIComponent(task.skillVersionId ?? '')}`)}>
                      {t('ai.skills.designer.publish.bindAgent', '绑定到 Agent')}
                    </Button>
                  </Space>
                ) : undefined}
                style={{marginTop: 18}}
              />
            )}
            <Descriptions bordered size="small" column={2} style={{marginTop: 18}}>
              <Descriptions.Item label={t('ai.skills.designer.publish.taskStatus', '任务状态')}>
                <Tag color={task.status === 'SUCCEEDED' ? 'success' : task.status === 'FAILED' ? 'error' : 'processing'}>
                  {skillPublishTaskStatusLabel(t, task.status)}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.designer.publish.attempts', '执行次数')}>{task.attemptCount}</Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.designer.publish.targetVersion', '目标版本')}>{task.version}</Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.designer.publish.activation', '自动激活')}>{task.activate ? t('common.yes', '是') : t('common.no', '否')}</Descriptions.Item>
              <Descriptions.Item label={t('ai.skills.designer.publish.taskId', '任务 ID')} span={2}><Text copyable>{task.id}</Text></Descriptions.Item>
              {task.artifactReference && <Descriptions.Item label={t('ai.skills.field.artifactReference', 'OCI 制品引用')} span={2}><Text copyable>{task.artifactReference}</Text></Descriptions.Item>}
              {task.artifactDigest && <Descriptions.Item label={t('ai.skills.field.artifactDigest', 'OCI Manifest Digest')} span={2}><Text copyable>{task.artifactDigest}</Text></Descriptions.Item>}
              {task.artifactConfigDigest && <Descriptions.Item label={t('ai.skills.details.configDigest', 'Config Digest')} span={2}><Text copyable>{task.artifactConfigDigest}</Text></Descriptions.Item>}
              {task.artifactContentDigest && <Descriptions.Item label={t('ai.skills.details.contentDigest', 'Manifest 层 Digest')} span={2}><Text copyable>{task.artifactContentDigest}</Text></Descriptions.Item>}
              {task.completedAt && <Descriptions.Item label={t('ai.skills.designer.publish.completedAt', '完成时间')} span={2}>{new Date(task.completedAt).toLocaleString()}</Descriptions.Item>}
            </Descriptions>
            {(task.status === 'FAILED' || task.status === 'SUCCEEDED') && (
              <Button style={{marginTop: 16}} onClick={() => {
                setTask(undefined);
                setVersion('');
                setActivate(true);
                setIdempotencyKey(randomKey());
              }}>{t('ai.skills.designer.publish.newTask', '发布另一个版本')}</Button>
            )}
          </>
        )}

        <Divider>{t('ai.skills.designer.publish.recent', '最近发布任务')}</Divider>
        {tasks.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('ai.skills.designer.publish.noTasks', '暂无发布任务')} /> : (
          <Space direction="vertical" style={{width: '100%'}}>
            {tasks.slice(0, 5).map((item) => (
              <Button key={item.id} block style={{height: 'auto', padding: '8px 12px', textAlign: 'left'}} onClick={() => setTask(item)}>
                <Space wrap>
                  <Tag color={item.status === 'SUCCEEDED' ? 'success' : item.status === 'FAILED' ? 'error' : 'processing'}>
                    {skillPublishTaskStatusLabel(t, item.status)}
                  </Tag>
                  <Text strong>{item.version}</Text>
                  <Text type="secondary">{t(
                    'ai.skills.designer.common.revisionValue',
                    '修订 {revision}',
                    {revision: item.draftRevision},
                  )}</Text>
                  <Tag color={registryStatusColor(registry)} icon={item.status === 'SUCCEEDED' ? <SafetyCertificateOutlined /> : undefined}>
                    {skillPublishStageLabel(t, item.stage)}
                  </Tag>
                </Space>
              </Button>
            ))}
          </Space>
        )}
      </Spin>
    </Drawer>
  );
};

export default SkillPublishDrawer;
