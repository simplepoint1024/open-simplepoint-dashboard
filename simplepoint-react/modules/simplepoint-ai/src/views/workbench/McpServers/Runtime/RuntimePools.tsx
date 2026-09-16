import DataTable from '@simplepoint/components/DataTable';
import {del, get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  App,
  Button,
  Card,
  InputNumber,
  Modal,
  Space,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import RuntimePoolEditorDialog from './RuntimePoolEditorDialog';
import {
  runtimeErrorLabel,
  runtimeNetworkModeLabel,
  runtimeStatusLabel,
} from './runtimeLabels';
import type {RuntimePool, RuntimeServer} from './types';
import {
  formatBytes,
  formatCpu,
  formatDateTime,
  resolveErrorMessage,
  statusColor,
} from './utils';

const {Text} = Typography;

type RuntimePoolsProps = {
  poolsUrl: string;
  secretsUrl: string;
  serversUrl: string;
  canManage: boolean;
};

const RuntimePools = ({poolsUrl, secretsUrl, serversUrl, canManage}: RuntimePoolsProps) => {
  const {t} = useI18n();
  const {message, modal} = App.useApp();
  const [rows, setRows] = useState<RuntimePool[]>([]);
  const [servers, setServers] = useState<RuntimeServer[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string>();
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingPool, setEditingPool] = useState<RuntimePool>();
  const [scalingPool, setScalingPool] = useState<RuntimePool>();
  const [scaleValue, setScaleValue] = useState(0);
  const [scaling, setScaling] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(undefined);
    try {
      const [poolPage, serverPage] = await Promise.all([
        get<Page<RuntimePool>>(poolsUrl, {page: 0, size: 500}),
        get<Page<RuntimeServer>>(serversUrl, {page: 0, size: 500}),
      ]);
      setRows(poolPage.content ?? []);
      setServers(serverPage.content ?? []);
    } catch (error) {
      setRows([]);
      setServers([]);
      setLoadError(resolveErrorMessage(
        error,
        t('ai.runtime.error.pools.load', 'Runtime Pool 加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [poolsUrl, serversUrl, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const serverNames = useMemo(() => new Map(
    servers.map((server) => [
      server.id,
      server.name || server.code || server.id,
    ]),
  ), [servers]);

  const operate = useCallback(async (
    pool: RuntimePool,
    action: 'activate' | 'disable' | 'redeploy',
  ) => {
    try {
      await post(`${poolsUrl}/${pool.id}/${action}`, {});
      message.success(action === 'activate'
        ? t('ai.runtime.success.pool.activated', 'Runtime Pool 已激活')
        : action === 'redeploy'
          ? t('ai.runtime.success.pool.redeployed', 'Runtime Pool 正在重新部署')
          : t('ai.runtime.success.pool.disabled', 'Runtime Pool 已禁用'));
      await load();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.pool.operation', 'Runtime Pool 操作失败'),
      ));
    }
  }, [load, message, poolsUrl, t]);

  const remove = useCallback(async (pool: RuntimePool) => {
    try {
      await del(`${poolsUrl}/${pool.id}`, []);
      message.success(t(
        'ai.runtime.success.pool.deleted',
        'Runtime Pool 及其已结束 Workload 已删除',
      ));
      await load();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t(
          'ai.runtime.error.pool.delete',
          '请先禁用 Pool，并等待全部副本回收后再删除',
        ),
      ));
    }
  }, [load, message, poolsUrl, t]);

  const submitScale = useCallback(async () => {
    if (!scalingPool) return;
    setScaling(true);
    try {
      await post(`${poolsUrl}/${scalingPool.id}/scale`, {
        desiredReplicas: scaleValue,
      });
      message.success(t('ai.runtime.success.pool.scaled', '目标副本数已更新'));
      setScalingPool(undefined);
      await load();
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.runtime.error.pool.scale', 'Runtime Pool 扩缩容失败'),
      ));
    } finally {
      setScaling(false);
    }
  }, [load, message, poolsUrl, scaleValue, scalingPool, t]);

  const columns = useMemo(() => [
    {
      title: t('ai.runtime.field.name', '名称'),
      dataIndex: 'name',
      width: 190,
      render: (value: string, pool: RuntimePool) => (
        <Space direction="vertical" size={0}>
          <Text strong>{value}</Text>
          <Text type="secondary" code>{pool.code}</Text>
        </Space>
      ),
    },
    {
      title: t('ai.runtime.field.server', 'MCP Server'),
      dataIndex: 'serverId',
      width: 190,
      render: (value: string) => serverNames.get(value) || value,
    },
    {
      title: t('ai.runtime.field.imageReference', 'OCI 镜像'),
      dataIndex: 'imageReference',
      width: 280,
      ellipsis: true,
      render: (value: string, pool: RuntimePool) => (
        <Space direction="vertical" size={0}>
          <Text copyable={{text: value}} ellipsis>{value}</Text>
          <Text type="secondary" copyable={{text: pool.imageDigest}}>
            {pool.imageDigest.slice(0, 22)}…
          </Text>
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
      title: t('ai.runtime.field.replicas', '副本'),
      key: 'replicas',
      width: 145,
      render: (_: unknown, pool: RuntimePool) => (
        <Text>{`${pool.readyReplicas}/${pool.currentReplicas} → ${pool.desiredReplicas}`}</Text>
      ),
    },
    {
      title: t('ai.runtime.field.resources', '资源限制'),
      key: 'resources',
      width: 180,
      render: (_: unknown, pool: RuntimePool) => (
        <Text>{`${formatCpu(pool.requestedNanoCpus)} · ${formatBytes(pool.requestedMemoryBytes)}`}</Text>
      ),
    },
    {
      title: t('ai.runtime.field.networkMode', '网络'),
      dataIndex: 'networkMode',
      width: 100,
      render: (value: string) => <Tag>{runtimeNetworkModeLabel(t, value)}</Tag>,
    },
    {
      title: t('ai.runtime.field.lastActivity', '最后活动'),
      dataIndex: 'lastActivityAt',
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
    ...(canManage ? [{
      title: t('ai.runtime.field.action', '操作'),
      key: 'action',
      fixed: 'right' as const,
      width: 390,
      render: (_: unknown, pool: RuntimePool) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => {
            setEditingPool(pool);
            setEditorOpen(true);
          }}>
            {t('ai.runtime.action.edit', '编辑')}
          </Button>
          <Button type="link" size="small" onClick={() => {
            setScaleValue(pool.desiredReplicas);
            setScalingPool(pool);
          }}>
            {t('ai.runtime.action.scale', '扩缩容')}
          </Button>
          <Button type="link" size="small" onClick={() => void operate(pool, 'activate')}>
            {t('ai.runtime.action.activate', '激活')}
          </Button>
          <Button
            type="link"
            size="small"
            disabled={pool.status === 'DISABLED'}
            onClick={() => modal.confirm({
              title: t(
                'ai.runtime.confirm.pool.redeploy',
                '确认使用当前配置替换全部 Runtime 副本？',
              ),
              onOk: () => operate(pool, 'redeploy'),
            })}
          >
            {t('ai.runtime.action.redeploy', '重新部署')}
          </Button>
          <Button
            type="link"
            danger
            size="small"
            disabled={pool.status === 'DISABLED'}
            onClick={() => modal.confirm({
              title: t('ai.runtime.confirm.pool.disable', '确认禁用并回收该 Pool 的全部副本？'),
              okButtonProps: {danger: true},
              onOk: () => operate(pool, 'disable'),
            })}
          >
            {t('ai.runtime.action.disable', '禁用')}
          </Button>
          <Button
            type="link"
            danger
            size="small"
            disabled={pool.status !== 'DISABLED'
              || pool.currentReplicas !== 0
              || pool.readyReplicas !== 0}
            onClick={() => modal.confirm({
              title: t(
                'ai.runtime.confirm.pool.delete',
                '确认删除该 Pool 和已结束的 Workload 记录？',
              ),
              okButtonProps: {danger: true},
              onOk: () => remove(pool),
            })}
          >
            {t('ai.runtime.action.delete', '删除')}
          </Button>
        </Space>
      ),
    }] : []),
  ], [canManage, modal, operate, remove, serverNames, t]);

  return (
    <>
      <Card
        title={t('ai.runtime.pool.title', 'Runtime Pools')}
        extra={(
          <Space>
            <Button onClick={() => void load()}>
              {t('ai.runtime.action.refresh', '刷新')}
            </Button>
            {canManage && (
              <Button type="primary" onClick={() => {
                setEditingPool(undefined);
                setEditorOpen(true);
              }}>
                {t('ai.runtime.action.pool.create', '新建 Pool')}
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
            message={t('ai.runtime.error.pools.load', 'Runtime Pool 加载失败')}
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
            'ai.runtime.pool.notice',
            'Pool 是托管 OCI MCP Server 的声明式副本配置，支持预热、弹性扩缩容和缩容到零。',
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
      <RuntimePoolEditorDialog
        open={editorOpen}
        pool={editingPool}
        servers={servers}
        poolsUrl={poolsUrl}
        secretsUrl={secretsUrl}
        onCancel={() => setEditorOpen(false)}
        onSaved={() => {
          setEditorOpen(false);
          void load();
        }}
      />
      <Modal
        open={Boolean(scalingPool)}
        title={t('ai.runtime.pool.scale', '调整目标副本')}
        okText={t('ai.runtime.action.confirm', '确认')}
        confirmLoading={scaling}
        onCancel={() => setScalingPool(undefined)}
        onOk={() => void submitScale()}
      >
        <InputNumber
          min={0}
          max={scalingPool?.maxReplicas ?? 0}
          precision={0}
          value={scaleValue}
          onChange={(value) => setScaleValue(value ?? 0)}
          style={{width: '100%'}}
        />
      </Modal>
    </>
  );
};

export default RuntimePools;
