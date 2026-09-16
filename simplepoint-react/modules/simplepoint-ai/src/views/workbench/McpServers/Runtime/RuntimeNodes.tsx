import DataTable from '@simplepoint/components/DataTable';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Modal,
  Progress,
  Space,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import type {RuntimeNode} from './types';
import {runtimeErrorLabel, runtimeStatusLabel} from './runtimeLabels';
import {
  formatBytes,
  formatCpu,
  formatDateTime,
  resolveErrorMessage,
  statusColor,
} from './utils';

const {Paragraph, Text} = Typography;

type RuntimeNodesProps = {
  nodesUrl: string;
};

const RuntimeNodes = ({nodesUrl}: RuntimeNodesProps) => {
  const {t} = useI18n();
  const [rows, setRows] = useState<RuntimeNode[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const [detail, setDetail] = useState<RuntimeNode>();

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(undefined);
    try {
      const page = await get<Page<RuntimeNode>>(nodesUrl, {page: 0, size: 500});
      setRows(page.content ?? []);
    } catch (error) {
      const errorMessage = resolveErrorMessage(
        error,
        t(
          'ai.runtime.error.nodes.load',
          'Runtime Node 加载失败；节点信息仅平台管理员可查看。',
        ),
      );
      setRows([]);
      setLoadError(errorMessage);
    } finally {
      setLoading(false);
    }
  }, [nodesUrl, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const columns = useMemo(() => [
    {
      title: t('ai.runtime.field.node', '节点'),
      dataIndex: 'displayName',
      width: 210,
      render: (value: string, node: RuntimeNode) => (
        <Space direction="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text code type="secondary">{node.nodeId}</Text>
        </Space>
      ),
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
      title: t('ai.runtime.node.capacity', 'Workload 容量'),
      key: 'capacity',
      width: 210,
      render: (_: unknown, node: RuntimeNode) => {
        const percent = node.maxWorkloads > 0
          ? Math.round((node.runningWorkloads / node.maxWorkloads) * 100)
          : 0;
        return (
          <Progress
            percent={percent}
            size="small"
            format={() => `${node.runningWorkloads}/${node.maxWorkloads}`}
          />
        );
      },
    },
    {
      title: t('ai.runtime.node.resources', '节点资源'),
      key: 'resources',
      width: 210,
      render: (_: unknown, node: RuntimeNode) => (
        `${node.cpuCores} CPU · ${formatBytes(node.memoryBytes)}`
      ),
    },
    {
      title: t('ai.runtime.node.runtime', 'Runtime'),
      key: 'runtime',
      width: 185,
      render: (_: unknown, node: RuntimeNode) => (
        <Space direction="vertical" size={0}>
          <Text>{node.runtimeVersion}</Text>
          <Text type="secondary">{t(
            'ai.runtime.node.dockerApiVersion',
            'Docker API {version}',
            {version: node.engineApiVersion},
          )}</Text>
        </Space>
      ),
    },
    {
      title: t('ai.runtime.node.security', '安全策略'),
      key: 'security',
      width: 260,
      render: (_: unknown, node: RuntimeNode) => (
        <Space wrap size={4}>
          <Tag color={node.seccompEnforced ? 'green' : 'red'}>{t('ai.runtime.node.security.seccomp', 'seccomp')}</Tag>
          <Tag color={node.appArmorEnforced ? 'green' : 'default'}>{t('ai.runtime.node.security.appArmor', 'AppArmor')}</Tag>
          <Tag color={node.requireSupplyChainAdmission ? 'green' : 'gold'}>{t('ai.runtime.node.security.supplyChainAdmission', '供应链准入')}</Tag>
          <Tag color={node.requireImageDigest ? 'green' : 'red'}>{t('ai.runtime.node.security.imageDigestRequired', '必须使用镜像 Digest')}</Tag>
        </Space>
      ),
    },
    {
      title: t('ai.runtime.node.cachedImages', '缓存镜像'),
      dataIndex: 'cachedImageDigests',
      width: 105,
      render: (value?: string[]) => value?.length ?? 0,
    },
    {
      title: t('ai.runtime.field.lastHeartbeat', '最后心跳'),
      dataIndex: 'lastHeartbeatAt',
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
      width: 90,
      render: (_: unknown, node: RuntimeNode) => (
        <Button type="link" size="small" onClick={() => setDetail(node)}>
          {t('ai.runtime.action.details', '详情')}
        </Button>
      ),
    },
  ], [t]);

  return (
    <>
      <Card
        title={t('ai.runtime.node.title', 'Runtime Nodes')}
        extra={<Button onClick={() => void load()}>{t('ai.runtime.action.refresh', '刷新')}</Button>}
      >
        <Alert
          showIcon
          type="info"
          closable
          style={{marginBottom: 16}}
          message={t(
            'ai.runtime.node.notice',
            '节点由独立 tool-runtime 进程主动注册；本页只读，且仅平台管理员可访问。',
          )}
        />
        {loadError && (
          <Alert
            showIcon
            type="error"
            style={{marginBottom: 16}}
            message={t(
              'ai.runtime.error.nodes.load',
              'Runtime Node 加载失败；节点信息仅平台管理员可查看。',
            )}
            description={loadError}
            action={(
              <Button size="small" onClick={() => void load()}>
                {t('ai.mcp.action.retry', '重试')}
              </Button>
            )}
          />
        )}
        <DataTable
          rowKey="nodeId"
          loading={loading}
          columns={columns}
          dataSource={rows}
          scroll={{x: 1800}}
          pagination={{pageSize: 20, hideOnSinglePage: true}}
        />
      </Card>
      <Modal
        open={Boolean(detail)}
        title={`${t('ai.runtime.node.details', 'Runtime Node 详情')} · ${detail?.displayName ?? ''}`}
        width={920}
        footer={null}
        onCancel={() => setDetail(undefined)}
      >
        {detail && (
          <>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('ai.runtime.node.id', '节点 ID')}>{detail.nodeId}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.instanceId', '实例 ID')}>{detail.instanceId}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.generation', '代次')}>{detail.generation}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.field.status', '状态')}>
                <Tag color={statusColor(detail.status)}>
                  {runtimeStatusLabel(t, detail.status)}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.advertiseUrl', '服务地址')} span={2}>
                {detail.advertiseUrl}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.maxMemory', '单任务内存')}>
                {formatBytes(detail.maxWorkloadMemoryBytes)}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.maxCpu', '单任务 CPU')}>
                {formatCpu(detail.maxWorkloadNanoCpus)}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.security.seccomp', 'seccomp')}>{detail.seccompProfileHash || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.node.security.appArmor', 'AppArmor')}>{detail.appArmorProfile || '-'}</Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.field.registeredAt', '注册时间')}>
                {formatDateTime(detail.registeredAt)}
              </Descriptions.Item>
              <Descriptions.Item label={t('ai.runtime.field.heartbeatExpiresAt', '心跳到期')}>
                {formatDateTime(detail.heartbeatExpiresAt)}
              </Descriptions.Item>
            </Descriptions>
            <Paragraph style={{marginTop: 16, marginBottom: 6}}>
              <Text strong>{t('ai.runtime.node.labels', '节点标签')}</Text>
            </Paragraph>
            <Space wrap>
              {Object.entries(detail.labels ?? {}).map(([key, value]) => (
                <Tag key={key}>{`${key}=${value}`}</Tag>
              ))}
              {Object.keys(detail.labels ?? {}).length === 0 && '-'}
            </Space>
            <Paragraph style={{marginTop: 16, marginBottom: 6}}>
              <Text strong>{t('ai.runtime.node.cachedImages', '缓存镜像')}</Text>
            </Paragraph>
            <Paragraph
              code
              copyable={{text: (detail.cachedImageDigests ?? []).join('\n')}}
              style={{maxHeight: 240, overflow: 'auto'}}
            >
              {(detail.cachedImageDigests ?? []).join('\n') || '-'}
            </Paragraph>
          </>
        )}
      </Modal>
    </>
  );
};

export default RuntimeNodes;
