import DataTable from '@simplepoint/components/DataTable';
import {del, get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Modal,
  Space,
  Steps,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {
  RuntimePool,
  RuntimeWorkload,
} from './Runtime/types';
import {runtimeErrorLabel, runtimeStatusLabel} from './Runtime/runtimeLabels';
import {
  formatBytes,
  formatCpu,
  formatDateTime,
  resolveErrorMessage,
  statusColor,
} from './Runtime/utils';
import {mcpServerErrorLabel} from './labels';

const {Paragraph, Text} = Typography;

type ManagedMcpServer = {
  id: string;
  name?: string;
  code?: string;
  status?: string;
  protocolVersion?: string;
  remoteServerName?: string;
  remoteServerVersion?: string;
  activeSnapshotId?: string;
  lastError?: string;
};

type McpTool = {
  name: string;
  title?: string;
  description?: string;
};

type DiscoveryResult = {
  protocolVersion?: string;
  serverName?: string;
  serverVersion?: string;
  tools?: McpTool[];
};

type McpDeploymentStatusDialogProps = {
  open: boolean;
  server: ManagedMcpServer;
  pool: RuntimePool;
  poolsUrl: string;
  workloadsUrl: string;
  serversUrl: string;
  autoDiscover?: boolean;
  onClose: () => void;
  onEdit: (pool: RuntimePool) => void;
  onChanged: () => void;
  onOpenCapabilities?: (serverId: string) => void;
  onDeleted: () => void;
};

const terminalStatuses = new Set(['SUCCEEDED', 'FAILED', 'LOST', 'CANCELLED']);

const McpDeploymentStatusDialog = ({
  open,
  server,
  pool,
  poolsUrl,
  workloadsUrl,
  serversUrl,
  autoDiscover = false,
  onClose,
  onEdit,
  onChanged,
  onOpenCapabilities,
  onDeleted,
}: McpDeploymentStatusDialogProps) => {
  const {t} = useI18n();
  const {message, modal} = App.useApp();
  const [currentServer, setCurrentServer] = useState(server);
  const [currentPool, setCurrentPool] = useState(pool);
  const [workloads, setWorkloads] = useState<RuntimeWorkload[]>([]);
  const [discovery, setDiscovery] = useState<DiscoveryResult>();
  const [refreshing, setRefreshing] = useState(false);
  const [operating, setOperating] = useState(false);
  const [discovering, setDiscovering] = useState(false);
  const [discoverAfterReady, setDiscoverAfterReady] = useState(autoDiscover);
  const discoveryAttempted = useRef(false);

  const refresh = useCallback(async (quiet = false) => {
    if (!open) return;
    if (!quiet) setRefreshing(true);
    try {
      const [nextPool, nextWorkloads, nextServer] = await Promise.all([
        get<RuntimePool>(`${poolsUrl}/${pool.id}`),
        get<RuntimeWorkload[]>(`${workloadsUrl}/by-pool/${pool.id}`),
        get<ManagedMcpServer>(`${serversUrl}/${server.id}`),
      ]);
      setCurrentPool(nextPool);
      setWorkloads(nextWorkloads);
      setCurrentServer(nextServer);
    } catch (error) {
      if (!quiet) {
        message.error(resolveErrorMessage(
          error,
          t('ai.mcp.deployment.error.load', '托管 MCP 部署状态加载失败'),
        ));
      }
    } finally {
      if (!quiet) setRefreshing(false);
    }
  }, [message, open, pool.id, poolsUrl, server.id, serversUrl, t, workloadsUrl]);

  useEffect(() => {
    if (!open) return;
    setCurrentServer(server);
    setCurrentPool(pool);
    setWorkloads([]);
    setDiscovery(undefined);
    setDiscoverAfterReady(autoDiscover);
    discoveryAttempted.current = false;
    void refresh();
    const timer = window.setInterval(() => void refresh(true), 2500);
    return () => window.clearInterval(timer);
  }, [autoDiscover, open, pool, refresh, server]);

  const discover = useCallback(async () => {
    if (discovering) return;
    discoveryAttempted.current = true;
    setDiscovering(true);
    try {
      const result = await post<DiscoveryResult>(
        `${serversUrl}/${server.id}/discover`,
        {},
      );
      setDiscovery(result);
      setDiscoverAfterReady(false);
      message.success(t(
        'ai.mcp.deployment.success.discover',
        'MCP 初始化完成，工具能力已经同步',
      ));
      await refresh(true);
      onChanged();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.mcp.servers.error.discover', 'MCP 能力发现失败'),
      ));
      await refresh(true);
    } finally {
      setDiscovering(false);
    }
  }, [discovering, message, onChanged, refresh, server.id, serversUrl, t]);

  useEffect(() => {
    if (!open
        || currentPool.readyReplicas < 1
        || discoveryAttempted.current
        || (!discoverAfterReady && currentServer.status === 'READY')) {
      return;
    }
    void discover();
  }, [
    currentPool.readyReplicas,
    currentServer.status,
    discover,
    discoverAfterReady,
    open,
  ]);

  const operate = useCallback(async (
    action: 'activate' | 'redeploy' | 'disable',
  ) => {
    setOperating(true);
    try {
      const result = await post<RuntimePool>(
        `${poolsUrl}/${currentPool.id}/${action}`,
        {},
      );
      setCurrentPool(result);
      if (action === 'redeploy' || action === 'activate') {
        setDiscovery(undefined);
        setDiscoverAfterReady(true);
        discoveryAttempted.current = false;
      }
      message.success(action === 'disable'
        ? t('ai.runtime.success.pool.disabled', 'Runtime Pool 已禁用')
        : action === 'redeploy'
          ? t('ai.runtime.success.pool.redeployed', 'Runtime Pool 正在重新部署')
          : t('ai.runtime.success.pool.activated', 'Runtime Pool 已激活'));
      onChanged();
      await refresh(true);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.pool.operation', 'Runtime Pool 操作失败'),
      ));
    } finally {
      setOperating(false);
    }
  }, [currentPool.id, message, onChanged, poolsUrl, refresh, t]);

  const remove = useCallback(async () => {
    setOperating(true);
    try {
      await del(`${poolsUrl}/${currentPool.id}`, []);
      message.success(t(
        'ai.runtime.success.pool.deleted',
        'Runtime Pool 及其已结束 Workload 已删除',
      ));
      onDeleted();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t(
          'ai.runtime.error.pool.delete',
          '请先禁用 Pool，并等待全部副本回收后再删除',
        ),
      ));
      await refresh(true);
    } finally {
      setOperating(false);
    }
  }, [currentPool.id, message, onDeleted, poolsUrl, refresh, t]);

  const activeWorkloads = useMemo(
    () => workloads.filter((item) => !terminalStatuses.has(item.status)),
    [workloads],
  );
  const scheduled = activeWorkloads.some((item) => Boolean(item.assignedNodeId));
  const running = currentPool.readyReplicas > 0;
  const discovered = currentServer.status === 'READY'
    && Boolean(currentServer.activeSnapshotId);
  const failed = currentPool.status === 'ERROR' || currentServer.status === 'ERROR';
  const canDelete = currentPool.status === 'DISABLED'
    && currentPool.currentReplicas === 0
    && activeWorkloads.length === 0;
  const workloadError = activeWorkloads.find((item) => item.lastError)?.lastError;
  const errorMessage = currentPool.lastError
    ? runtimeErrorLabel(t, currentPool.lastError)
    : currentServer.lastError
      ? mcpServerErrorLabel(t, currentServer.lastError)
      : workloadError
        ? runtimeErrorLabel(t, workloadError)
        : undefined;

  const stepStatus = (
    finished: boolean,
    active: boolean,
  ): 'finish' | 'process' | 'wait' | 'error' => {
    if (finished) return 'finish';
    if (failed) return 'error';
    return active ? 'process' : 'wait';
  };

  const toolRows = discovery?.tools ?? [];

  return (
    <Modal
      open={open}
      width={980}
      title={t('ai.mcp.deployment.title', '托管 MCP 部署中心')}
      onCancel={onClose}
      footer={(
        <Space wrap>
          <Button onClick={onClose}>
            {t('ai.runtime.action.close', '关闭')}
          </Button>
          <Button loading={refreshing} onClick={() => void refresh()}>
            {t('ai.runtime.action.refresh', '刷新')}
          </Button>
          <Button onClick={() => onEdit(currentPool)}>
            {t('ai.mcp.deployment.action.edit', '更新部署配置')}
          </Button>
          {currentPool.status === 'DISABLED' ? (
            <Button
              type="primary"
              loading={operating}
              onClick={() => void operate('activate')}
            >
              {t('ai.runtime.action.activate', '激活')}
            </Button>
          ) : (
            <>
              <Button
                loading={operating}
                onClick={() => modal.confirm({
                  title: t(
                    'ai.runtime.confirm.pool.redeploy',
                    '确认使用当前配置替换全部 Runtime 副本？',
                  ),
                  onOk: () => operate('redeploy'),
                })}
              >
                {t('ai.runtime.action.redeploy', '重新部署')}
              </Button>
              <Button
                danger
                loading={operating}
                onClick={() => modal.confirm({
                  title: t(
                    'ai.runtime.confirm.pool.disable',
                    '确认禁用并回收该 Pool 的全部副本？',
                  ),
                  okButtonProps: {danger: true},
                  onOk: () => operate('disable'),
                })}
              >
                {t('ai.runtime.action.disable', '禁用')}
              </Button>
            </>
          )}
          <Button
            danger
            disabled={!canDelete}
            loading={operating}
            onClick={() => modal.confirm({
              title: t(
                'ai.runtime.confirm.pool.delete',
                '确认删除该 Pool 和已结束的 Workload 记录？',
              ),
              okButtonProps: {danger: true},
              onOk: remove,
            })}
          >
            {t('ai.runtime.action.delete', '删除部署')}
          </Button>
        </Space>
      )}
    >
      <Alert
        showIcon
        type={errorMessage ? 'error' : running ? 'success' : 'info'}
        style={{marginBottom: 20}}
        message={errorMessage
          || (running
            ? t('ai.mcp.deployment.ready', 'OCI Runtime 副本已经就绪')
            : t('ai.mcp.deployment.progress', '平台正在校验镜像并协调 Runtime 副本'))}
        description={errorMessage
          ? t(
            'ai.mcp.deployment.error.hint',
            '请修正镜像、Digest、网络或节点容量配置后重新部署。',
          )
          : undefined}
      />
      <Steps
        size="small"
        style={{marginBottom: 24}}
        items={[
          {
            title: t('ai.mcp.deployment.step.image', '镜像校验'),
            status: stepStatus(
              currentPool.prewarmedNodes > 0 || activeWorkloads.length > 0,
              true,
            ),
          },
          {
            title: t('ai.mcp.deployment.step.schedule', '节点调度'),
            status: stepStatus(scheduled, activeWorkloads.length > 0),
          },
          {
            title: t('ai.mcp.deployment.step.runtime', 'Runtime 就绪'),
            status: stepStatus(running, scheduled),
          },
          {
            title: t('ai.mcp.deployment.step.discover', '能力发现'),
            status: stepStatus(discovered, running || discovering),
          },
        ]}
      />
      <Descriptions
        bordered
        size="small"
        column={2}
        style={{marginBottom: 20}}
        items={[
          {
            key: 'server',
            label: t('ai.mcp-servers.entity.title', 'MCP Server'),
            children: currentServer.name || currentServer.code || currentServer.id,
          },
          {
            key: 'poolStatus',
            label: t('ai.runtime.field.status', '状态'),
            children: (
              <Space>
                <Tag color={statusColor(currentPool.status)}>
                  {runtimeStatusLabel(t, currentPool.status)}
                </Tag>
                <Text>{`${currentPool.readyReplicas}/${currentPool.currentReplicas} → ${currentPool.desiredReplicas}`}</Text>
              </Space>
            ),
          },
          {
            key: 'image',
            label: t('ai.runtime.field.imageReference', 'OCI 镜像'),
            span: 2,
            children: (
              <Space direction="vertical" size={0}>
                <Text copyable>{currentPool.imageReference}</Text>
                <Text type="secondary" copyable>{currentPool.imageDigest}</Text>
              </Space>
            ),
          },
          {
            key: 'resources',
            label: t('ai.runtime.field.resources', '资源限制'),
            children: `${formatCpu(currentPool.requestedNanoCpus)} · ${formatBytes(currentPool.requestedMemoryBytes)} · PID ${currentPool.requestedPidsLimit}`,
          },
          {
            key: 'protocol',
            label: t('ai.mcp.gateway.protocol', '协议版本'),
            children: currentServer.protocolVersion || discovery?.protocolVersion || '-',
          },
        ]}
      />
      <DataTable
        rowKey="id"
        size="small"
        dataSource={workloads.slice().reverse().slice(0, 5)}
        pagination={false}
        style={{marginBottom: 20}}
        columns={[
          {
            title: t('ai.runtime.workload.entity', 'Workload'),
            dataIndex: 'runtimeWorkloadId',
            ellipsis: true,
            render: (value: string, item: RuntimeWorkload) => value || item.id,
          },
          {
            title: t('ai.runtime.field.node', '节点'),
            dataIndex: 'assignedNodeId',
            width: 170,
            render: (value?: string) => value || '-',
          },
          {
            title: t('ai.runtime.field.status', '状态'),
            dataIndex: 'status',
            width: 110,
            render: (value: string) => (
              <Tag color={statusColor(value)}>{runtimeStatusLabel(t, value)}</Tag>
            ),
          },
          {
            title: t('ai.runtime.field.startedAt', '启动时间'),
            dataIndex: 'startedAt',
            width: 180,
            render: formatDateTime,
          },
        ]}
      />
      {running && (
        <Space direction="vertical" style={{width: '100%'}} size="middle">
          <Space>
            <Button
              type={discovered ? 'default' : 'primary'}
              loading={discovering}
              onClick={() => void discover()}
            >
              {discovered
                ? t('ai.mcp.deployment.action.rediscover', '重新发现能力')
                : t('ai.mcp.deployment.action.discover', '发现工具')}
            </Button>
            {discovered && onOpenCapabilities && (
              <Button
                type="primary"
                onClick={() => {
                  onOpenCapabilities(server.id);
                }}
              >
                {t('ai.mcp.deployment.action.test', '打开工具测试')}
              </Button>
            )}
          </Space>
          {toolRows.length > 0 && (
            <>
              <Text strong>
                {t('ai.mcp.deployment.discoveredTools', '本次发现的工具')}
              </Text>
              <DataTable
                rowKey="name"
                size="small"
                dataSource={toolRows}
                pagination={{pageSize: 5, hideOnSinglePage: true}}
                columns={[
                  {title: t('ai.mcp.tools.name', '工具名称'), dataIndex: 'name'},
                  {
                    title: t('ai.mcp.tools.title', '标题'),
                    dataIndex: 'title',
                    width: 180,
                  },
                  {
                    title: t('ai.mcp.tools.description', '说明'),
                    dataIndex: 'description',
                  },
                ]}
              />
            </>
          )}
        </Space>
      )}
      {!canDelete && currentPool.status === 'DISABLED' && (
        <Paragraph type="secondary" style={{marginTop: 16, marginBottom: 0}}>
          {t(
            'ai.runtime.pool.delete.wait',
            '正在回收 Runtime 副本；副本归零后即可删除部署。',
          )}
        </Paragraph>
      )}
    </Modal>
  );
};

export default McpDeploymentStatusDialog;
