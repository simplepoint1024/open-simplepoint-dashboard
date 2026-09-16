import DataTable from '@simplepoint/components/DataTable';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {
  runtimeErrorLabel,
  runtimeNetworkModeLabel,
  runtimeStatusLabel,
} from './runtimeLabels';
import type {RuntimeSecret, RuntimeServer, RuntimeWorkload} from './types';
import {
  formatBytes,
  formatCpu,
  formatDateTime,
  resolveErrorMessage,
  splitHosts,
  statusColor,
} from './utils';

const {Text} = Typography;
const MIB = 1024 * 1024;
const NANO_CPU = 1_000_000_000;
const TERMINAL_STATUSES = new Set(['SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED']);

type RuntimeWorkloadsProps = {
  workloadsUrl: string;
  secretsUrl: string;
  serversUrl: string;
  canManage: boolean;
};

type WorkloadFormValues = {
  executionId?: string;
  serverId: string;
  imageReference: string;
  imageDigest?: string;
  memoryMiB: number;
  cpuCores: number;
  pidsLimit: number;
  timeoutSeconds: number;
  networkMode: string;
  egressAllowlist?: string;
  secretIds?: string[];
};

const RuntimeWorkloads = ({
  workloadsUrl,
  secretsUrl,
  serversUrl,
  canManage,
}: RuntimeWorkloadsProps) => {
  const {t} = useI18n();
  const {message, modal} = App.useApp();
  const [form] = Form.useForm<WorkloadFormValues>();
  const networkMode = Form.useWatch('networkMode', form);
  const [rows, setRows] = useState<RuntimeWorkload[]>([]);
  const [servers, setServers] = useState<RuntimeServer[]>([]);
  const [secrets, setSecrets] = useState<RuntimeSecret[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const [submitOpen, setSubmitOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [detail, setDetail] = useState<RuntimeWorkload>();

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(undefined);
    try {
      const [workloadPage, serverPage, secretPage] = await Promise.all([
        get<Page<RuntimeWorkload>>(workloadsUrl, {page: 0, size: 500}),
        get<Page<RuntimeServer>>(serversUrl, {page: 0, size: 500}),
        get<Page<RuntimeSecret>>(secretsUrl, {page: 0, size: 500}),
      ]);
      setRows(workloadPage.content ?? []);
      setServers(serverPage.content ?? []);
      setSecrets((secretPage.content ?? []).filter((secret) => secret.enabled !== false));
    } catch (error) {
      setRows([]);
      setServers([]);
      setSecrets([]);
      setLoadError(resolveErrorMessage(
        error,
        t('ai.runtime.error.workloads.load', 'Runtime Workload 加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [secretsUrl, serversUrl, t, workloadsUrl]);

  useEffect(() => {
    void load();
  }, [load]);

  const serverNames = useMemo(() => new Map(
    servers.map((server) => [
      server.id,
      server.name || server.code || server.id,
    ]),
  ), [servers]);

  const submit = useCallback(async () => {
    let values: WorkloadFormValues;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }
    setSubmitting(true);
    try {
      await post(workloadsUrl, {
        executionId: values.executionId || null,
        serverId: values.serverId,
        imageReference: values.imageReference,
        imageDigest: values.imageDigest?.trim().toLowerCase() || null,
        memoryBytes: Math.round(values.memoryMiB * MIB),
        nanoCpus: Math.round(values.cpuCores * NANO_CPU),
        pidsLimit: values.pidsLimit,
        timeoutSeconds: values.timeoutSeconds,
        networkMode: values.networkMode,
        egressAllowlist: values.networkMode === 'egress'
          ? splitHosts(values.egressAllowlist)
          : [],
        secretIds: values.secretIds ?? [],
      });
      setSubmitOpen(false);
      form.resetFields();
      message.success(t('ai.runtime.success.workload.submitted', 'Runtime Workload 已提交'));
      await load();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.workload.submit', 'Runtime Workload 提交失败'),
      ));
    } finally {
      setSubmitting(false);
    }
  }, [form, load, message, t, workloadsUrl]);

  const stop = useCallback(async (workload: RuntimeWorkload) => {
    try {
      await post(`${workloadsUrl}/${workload.id}/stop`, {});
      message.success(t('ai.runtime.success.workload.stopped', '已请求停止 Workload'));
      await load();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.workload.stop', 'Runtime Workload 停止失败'),
      ));
    }
  }, [load, message, t, workloadsUrl]);

  const columns = useMemo(() => [
    {
      title: t('ai.runtime.field.executionId', 'Execution ID'),
      dataIndex: 'executionId',
      width: 210,
      ellipsis: true,
      render: (value: string) => <Text code copyable={{text: value}}>{value}</Text>,
    },
    {
      title: t('ai.runtime.field.status', '状态'),
      dataIndex: 'status',
      width: 105,
      render: (value: string) => (
        <Tag color={statusColor(value)}>{runtimeStatusLabel(t, value)}</Tag>
      ),
    },
    {
      title: t('ai.runtime.field.server', 'MCP Server'),
      dataIndex: 'serverId',
      width: 180,
      render: (value: string) => serverNames.get(value) || value,
    },
    {
      title: t('ai.runtime.field.pool', 'Pool'),
      dataIndex: 'poolId',
      width: 150,
      ellipsis: true,
      render: (value?: string) => value || t('ai.runtime.workload.manual', '手动任务'),
    },
    {
      title: t('ai.runtime.field.node', '运行节点'),
      dataIndex: 'assignedNodeId',
      width: 160,
      render: (value?: string) => value || '-',
    },
    {
      title: t('ai.runtime.field.imageReference', 'OCI 镜像'),
      dataIndex: 'imageReference',
      width: 260,
      ellipsis: true,
    },
    {
      title: t('ai.runtime.field.resources', '资源限制'),
      key: 'resources',
      width: 190,
      render: (_: unknown, workload: RuntimeWorkload) => (
        `${formatCpu(workload.requestedNanoCpus)} · ${formatBytes(workload.requestedMemoryBytes)}`
      ),
    },
    {
      title: t('ai.runtime.field.startedAt', '启动时间'),
      dataIndex: 'startedAt',
      width: 175,
      render: formatDateTime,
    },
    {
      title: t('ai.runtime.field.lastError', '最后错误'),
      dataIndex: 'lastError',
      width: 220,
      ellipsis: true,
      render: (value?: string) => value
        ? <Text type="danger">{runtimeErrorLabel(t, value)}</Text>
        : '-',
    },
    {
      title: t('ai.runtime.field.action', '操作'),
      key: 'action',
      fixed: 'right' as const,
      width: 150,
      render: (_: unknown, workload: RuntimeWorkload) => (
        <Space>
          <Button type="link" size="small" onClick={() => setDetail(workload)}>
            {t('ai.runtime.action.details', '详情')}
          </Button>
          <Button
            type="link"
            danger
            size="small"
            disabled={!canManage || TERMINAL_STATUSES.has(workload.status)}
            onClick={() => modal.confirm({
              title: t('ai.runtime.confirm.workload.stop', '确认停止该 Runtime Workload？'),
              okButtonProps: {danger: true},
              onOk: () => stop(workload),
            })}
          >
            {t('ai.runtime.action.stop', '停止')}
          </Button>
        </Space>
      ),
    },
  ], [canManage, modal, serverNames, stop, t]);

  return (
    <>
      <Card
        title={t('ai.runtime.workload.title', 'Runtime Workloads')}
        extra={(
          <Space>
            <Button onClick={() => void load()}>
              {t('ai.runtime.action.refresh', '刷新')}
            </Button>
            {canManage && (
              <Button type="primary" onClick={() => {
                form.resetFields();
                form.setFieldsValue({
                  memoryMiB: 256,
                  cpuCores: 0.5,
                  pidsLimit: 128,
                  timeoutSeconds: 3600,
                  networkMode: 'none',
                  secretIds: [],
                });
                setSubmitOpen(true);
              }}>
                {t('ai.runtime.action.workload.submit', '提交测试 Workload')}
              </Button>
            )}
          </Space>
        )}
      >
        {loadError && (
          <Alert
            showIcon
            type="error"
            style={{marginBottom: 16}}
            message={t('ai.runtime.error.workloads.load', 'Runtime Workload 加载失败')}
            description={loadError}
            action={(
              <Button size="small" onClick={() => void load()}>
                {t('ai.mcp.action.retry', '重试')}
              </Button>
            )}
          />
        )}
        <Alert
          showIcon
          type="info"
          closable
          style={{marginBottom: 16}}
          message={t(
            'ai.runtime.workload.notice',
            '这里同时显示 Pool 自动创建的副本和手动提交的 OCI MCP 测试任务。',
          )}
        />
        <DataTable
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={rows}
          scroll={{x: 1800}}
          pagination={{pageSize: 20, hideOnSinglePage: true}}
        />
      </Card>
      <Modal
        open={submitOpen}
        title={t('ai.runtime.workload.submit', '提交 Runtime Workload')}
        width={860}
        okText={t('ai.runtime.action.submit', '提交')}
        confirmLoading={submitting}
        destroyOnHidden
        onCancel={() => {
          form.resetFields();
          setSubmitOpen(false);
        }}
        onOk={() => void submit()}
      >
        <Form form={form} layout="vertical">
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="executionId"
                label={t('ai.runtime.field.executionId', 'Execution ID')}
                extra={t('ai.runtime.executionId.help', '留空时由平台生成。')}
              >
                <Input maxLength={64}/>
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="serverId"
                label={t('ai.runtime.field.server', 'MCP Server')}
                rules={[{required: true}]}
              >
                <Select options={servers
                  .filter((server) => server.deploymentType === 'MANAGED_OCI')
                  .map((server) => ({
                    value: server.id,
                    label: server.name || server.code || server.id,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item
                name="imageReference"
                label={t('ai.runtime.field.imageReference', 'OCI 镜像')}
                rules={[{required: true, max: 440}]}
              >
                <Input placeholder="docker.io/example/mcp-server:1.0.0"/>
              </Form.Item>
            </Col>
            <Col span={24}>
              <Form.Item
                name="imageDigest"
                label={t('ai.runtime.field.imageDigest', '镜像 Digest')}
                rules={[{pattern: /^sha256:[a-fA-F0-9]{64}$/}]}
                extra={t(
                  'ai.runtime.imageDigest.help',
                  '可选；留空时服务端会拉取镜像 Tag，并自动保存解析出的 Digest。',
                )}
              >
                <Input placeholder={t(
                  'ai.runtime.imageDigest.placeholder',
                  '可选，留空自动解析',
                )}/>
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item name="memoryMiB" label={t('ai.runtime.field.memory', '内存（MiB）')}>
                <InputNumber min={32} max={16384} style={{width: '100%'}}/>
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item name="cpuCores" label={t('ai.runtime.field.cpu', 'CPU（核）')}>
                <InputNumber min={0.01} max={16} step={0.1} style={{width: '100%'}}/>
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item name="pidsLimit" label={t('ai.runtime.field.pids', 'PID 上限')}>
                <InputNumber min={1} max={4096} style={{width: '100%'}}/>
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item name="timeoutSeconds" label={t('ai.runtime.field.timeout', '超时（秒）')}>
                <InputNumber min={1} max={86400} style={{width: '100%'}}/>
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="networkMode" label={t('ai.runtime.field.networkMode', '网络模式')}>
                <Select options={[
                  {value: 'none', label: t('ai.runtime.network.none', '禁止网络')},
                  {value: 'bridge', label: t('ai.runtime.network.bridge', 'Bridge 网络')},
                  {value: 'egress', label: t('ai.runtime.network.egress', '受控出口')},
                ]}/>
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item name="secretIds" label={t('ai.runtime.field.secrets', 'Runtime Secrets')}>
                <Select
                  mode="multiple"
                  options={secrets.map((secret) => ({
                    value: secret.id,
                    label: `${secret.name} (${secret.code})`,
                  }))}
                />
              </Form.Item>
            </Col>
            {networkMode === 'egress' && (
              <Col span={24}>
                <Form.Item
                  name="egressAllowlist"
                  label={t('ai.runtime.field.egressAllowlist', '出口域名白名单')}
                  rules={[{required: true}]}
                >
                  <Input.TextArea autoSize={{minRows: 3, maxRows: 8}}/>
                </Form.Item>
              </Col>
            )}
          </Row>
        </Form>
      </Modal>
      <Modal
        open={Boolean(detail)}
        title={t('ai.runtime.workload.details', 'Workload 详情')}
        width={900}
        footer={null}
        onCancel={() => setDetail(undefined)}
      >
        {detail && (
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label={t('ai.runtime.workload.id', 'Workload ID')} span={2}>{detail.id}</Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.executionId', '执行 ID')} span={2}>{detail.executionId}</Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.status', '状态')}>
              <Tag color={statusColor(detail.status)}>
                {runtimeStatusLabel(t, detail.status)}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.node', '运行节点')}>
              {detail.assignedNodeId || '-'}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.workload.leaseId', '租约 ID')}>{detail.leaseId || '-'}</Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.workload.fencingToken', '隔离令牌')}>{detail.fencingToken}</Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.workload.containerId', '容器 ID')} span={2}>
              <Text copyable>{detail.containerId || '-'}</Text>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.imageReference', 'OCI 镜像')} span={2}>
              {detail.imageReference}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.imageDigest', '镜像 Digest')} span={2}>{detail.imageDigest}</Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.networkMode', '网络')}>
              {runtimeNetworkModeLabel(t, detail.networkMode)}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.secrets', 'Secrets')}>
              {(detail.secretIds ?? []).length}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.deadlineAt', '截止时间')}>
              {formatDateTime(detail.deadlineAt)}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.finishedAt', '结束时间')}>
              {formatDateTime(detail.finishedAt)}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.runtime.field.lastError', '最后错误')} span={2}>
              {detail.lastError ? runtimeErrorLabel(t, detail.lastError) : '-'}
            </Descriptions.Item>
          </Descriptions>
        )}
      </Modal>
    </>
  );
};

export default RuntimeWorkloads;
