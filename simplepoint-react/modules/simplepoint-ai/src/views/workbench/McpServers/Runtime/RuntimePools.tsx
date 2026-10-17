import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import {useRuntimeServerNames} from './runtimeQueries';
import {canEditPool, canScalePool} from './rowPolicy';
import {useRemoteList} from '@simplepoint/components/DataTable/useRemoteList';
import {del, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  App,
  Modal,
  Space,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useMemo, useRef, useState} from 'react';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useOperationScope} from './useOperationScope';
import RuntimePoolEditorDialog from './RuntimePoolEditorDialog';
import {
  runtimeErrorLabel,
  runtimeNetworkModeLabel,
  runtimeStatusLabel,
} from './runtimeLabels';
import type {RuntimePool} from './types';
import {
  formatBytes,
  formatCpu,
  formatDateTime,
  resolveErrorMessage,
  statusColor,
} from './utils';

const {Text} = Typography;

type RuntimePoolsProps = {
  active?: boolean;
  poolsUrl: string;
  secretsUrl: string;
  serversUrl: string;
  canManage: boolean;
};

const RuntimePools = ({active = true, poolsUrl, secretsUrl, serversUrl, canManage}: RuntimePoolsProps) => {
  const {t} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope();
  const scaleForm = useRef<SFormHandle>(null);
  const list = useRemoteList<RuntimePool>(poolsUrl, active);
  const load = list.refresh;
  const loadError = list.error ? resolveErrorMessage(list.error, t('table.loadFail', '加载失败')) : undefined;
  const [editorOpen, setEditorOpen] = useState(false);
  const [editingPool, setEditingPool] = useState<RuntimePool>();
  const [scalingPool, setScalingPool] = useState<RuntimePool>();
  const [scaleDraft, setScaleDraft] = useState<{desiredReplicas?: number}>({});
  const [confirmation, setConfirmation] = useState<{pool: RuntimePool; action: 'redeploy' | 'disable' | 'delete'}>();
  const [writeError, setWriteError] = useState('');
  const locked = operation.busy || editorOpen || !!scalingPool || !!confirmation;
  usePageRetention(false, locked);


  const serverNames = useRuntimeServerNames(serversUrl, list.page.content, active);

  const operate = useCallback(async (pool: RuntimePool, action: 'activate' | 'disable' | 'redeploy') => {
    if (!canManage || !pool.id || loadError) return;
    const request = operation.begin(); if (!request) return; setWriteError('');
    try {
      await post(poolsUrl + '/' + encodeURIComponent(pool.id) + '/' + action, {}, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setConfirmation(undefined);
      message.success(action === 'activate' ? t('ai.runtime.success.pool.activated', 'Runtime Pool 已激活')
        : action === 'redeploy' ? t('ai.runtime.success.pool.redeployed', 'Runtime Pool 正在重新部署')
        : t('ai.runtime.success.pool.disabled', 'Runtime Pool 已禁用'));
      await load();
    } catch (error) {if (operation.current(request.signal)) setWriteError(resolveErrorMessage(error,
      t('ai.runtime.error.pool.operation', 'Runtime Pool 操作失败')));}
    finally {operation.finish(request);}
  }, [canManage, loadError, poolsUrl, load, message, t, operation.begin, operation.current, operation.finish]);
  const remove = useCallback(async (pool: RuntimePool) => {
    if (!canManage || !pool.id || loadError || pool.status !== 'DISABLED' || pool.currentReplicas !== 0 || pool.readyReplicas !== 0) return;
    const request = operation.begin(); if (!request) return; setWriteError('');
    try {
      await del(poolsUrl + '/' + encodeURIComponent(pool.id), [], {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setConfirmation(undefined);
      message.success(t('ai.runtime.success.pool.deleted', 'Runtime Pool 及其已结束 Workload 已删除')); await load();
    } catch (error) {if (operation.current(request.signal)) setWriteError(resolveErrorMessage(error,
      t('ai.runtime.error.pool.delete', '请先禁用 Pool，并等待全部副本回收后再删除')));}
    finally {operation.finish(request);}
  }, [canManage, loadError, poolsUrl, load, message, t, operation.begin, operation.current, operation.finish]);
  const submitScale = async (values: {desiredReplicas?: number}) => {
    const captured = scalingPool, desired = values.desiredReplicas;
    if (!canManage || !captured?.id || !canScalePool(captured) || !Number.isSafeInteger(desired)
      || desired! < captured.minReplicas || desired! > captured.maxReplicas || loadError) return;
    const request = operation.begin(); if (!request) return; setWriteError('');
    try {
      await post(poolsUrl + '/' + encodeURIComponent(captured.id) + '/scale', {desiredReplicas: desired}, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setScalingPool(undefined); setScaleDraft({});
      message.success(t('ai.runtime.success.pool.scaled', '目标副本数已更新')); await load();
    } catch (error) {if (operation.current(request.signal)) setWriteError(resolveErrorMessage(error,
      t('ai.runtime.error.pool.scale', 'Runtime Pool 扩缩容失败')));}
    finally {operation.finish(request);}
  };
  const confirmOperation = (pool: RuntimePool, action: 'redeploy' | 'disable' | 'delete') => {
    if (locked || !operation.isSelected() || !canManage || loadError || !pool.id) return;
    setWriteError(''); setConfirmation({pool: structuredClone(pool), action});
  };

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
            {pool.imageDigest?.slice(0, 22) ?? '-'}…
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
      render: (value?: string) => formatDateTime(value),
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
        <TableActions selectedKeys={[pool.id]} selectedRows={[pool]} buttons={[
          {key: 'edit', sort: 1, text: t('ai.runtime.action.edit', '编辑'), type: 'link', size: 'small', disabled: !canEditPool(pool)},
          {key: 'scale', sort: 2, text: t('ai.runtime.action.scale', '扩缩容'), type: 'link', size: 'small', disabled: !canScalePool(pool)},
          {key: 'activate', sort: 3, text: t('ai.runtime.action.activate', '激活'), type: 'link', size: 'small'},
          {key: 'redeploy', sort: 4, text: t('ai.runtime.action.redeploy', '重新部署'), type: 'link', size: 'small', disabled: pool.status === 'DISABLED'},
          {key: 'disable', sort: 5, text: t('ai.runtime.action.disable', '禁用'), type: 'link', size: 'small', danger: true, disabled: pool.status === 'DISABLED'},
          {key: 'delete', sort: 6, text: t('ai.runtime.action.delete', '删除'), type: 'link', size: 'small', danger: true,
            disabled: pool.status !== 'DISABLED' || pool.currentReplicas !== 0 || pool.readyReplicas !== 0},
        ]} isButtonDisabled={() => locked || !operation.selected || !!loadError}
          onButtonEvents={{edit: () => {
            if (locked || !operation.isSelected() || !canEditPool(pool)) return;
            setWriteError(''); setEditingPool(structuredClone(pool)); setEditorOpen(true);
          }, scale: () => {
            if (locked || !operation.isSelected() || !canScalePool(pool)) return;
            setWriteError(''); setScaleDraft({desiredReplicas: pool.desiredReplicas}); setScalingPool(structuredClone(pool));
          }, activate: () => {if (!locked) void operate(pool, 'activate');},
          redeploy: () => confirmOperation(pool, 'redeploy'), disable: () => confirmOperation(pool, 'disable'),
          delete: () => confirmOperation(pool, 'delete')}}/>
      ),
    }] : []),
  ], [canManage, operate, serverNames, t, locked, operation.selected, loadError]);

  return (
    <>
      <div className="runtime-list-pools" style={{display: 'flex', flexDirection: 'column', minHeight: 0, gap: 12}}>
        {writeError && !scalingPool && !confirmation && <Alert type='error' showIcon title={writeError}/>}
        {loadError && (
          <Alert
            showIcon
            type="error"
            style={{marginBottom: 16}}
            message={t('ai.runtime.error.pools.load', 'Runtime Pool 加载失败')}
            description={loadError}
            action={(
              <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
                {key: 'retry', sort: 1, text: t('table.retry', '重试'), size: 'small'},
                {key: 'reset', sort: 2, text: t('table.query.reset', '重置筛选与排序'), size: 'small'},
              ]} onButtonEvents={{retry: list.retry, reset: list.reset}}/>
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
        <DataTable<RuntimePool>
          {...list.tableProps}
          refresh={() => {if (!locked) void load();}}
          pagination={list.tableProps.pagination && typeof list.tableProps.pagination === 'object' ? {...list.tableProps.pagination, disabled: locked} : list.tableProps.pagination}
          toolbarExtra={<Space>
            {canManage && (
              <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
                {key: 'create', sort: 1, text: t('ai.runtime.action.pool.create', '新建 Pool'), type: 'primary', disabled: locked || !!loadError || !operation.selected},
              ]} onButtonEvents={{create: () => {
                if (locked || !operation.isSelected() || loadError) return;
                setWriteError(''); setEditingPool(undefined);
                setEditorOpen(true);
              }}}/>
            )}
          </Space>}
          query={list.tableProps.query}
          columns={list.columns(columns)}
          scroll={{x: 1800}}
        />
      </div>
      <RuntimePoolEditorDialog
        open={editorOpen}
        pool={editingPool}
        serversUrl={serversUrl}
        poolsUrl={poolsUrl}
        secretsUrl={secretsUrl}
        onCancel={() => setEditorOpen(false)}
        onSaved={() => {
          setEditorOpen(false);
          void load();
        }}
      />
      <Modal wrapClassName="runtime-pool-scale" open={!!scalingPool && operation.selected} destroyOnHidden={!scalingPool}
        title={t('ai.runtime.pool.scale', '调整目标副本')} closable={!operation.busy} maskClosable={!operation.busy} keyboard={!operation.busy}
        onCancel={() => {if (!operation.busy) {setScalingPool(undefined); setScaleDraft({});}}}
        footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'cancel', sort: 1, text: t('ai.mcp.action.cancel', '取消'), disabled: operation.busy},
          {key: 'confirm', sort: 2, text: t('ai.runtime.action.confirm', '确认'), type: 'primary', loading: operation.busy, disabled: operation.busy},
        ]} onButtonEvents={{cancel: () => {if (!operation.busy) {setScalingPool(undefined); setScaleDraft({});}},
          confirm: () => scaleForm.current?.submit()}}/>}>
        {writeError && <Alert type="error" showIcon title={writeError}/>}
        <SForm ref={scaleForm} hideSubmit disabled={operation.busy} schema={{type: 'object', required: ['desiredReplicas'], properties: {
          desiredReplicas: {type: 'integer', minimum: scalingPool?.minReplicas, maximum: scalingPool?.maxReplicas,
            title: t('ai.runtime.field.desiredReplicas', '目标副本数')},
        }}} formData={scaleDraft} onChange={event => {if (!operation.busy) setScaleDraft(event.formData ?? {});}}
          onSubmit={event => {void submitScale(event.formData ?? {});}}/>
      </Modal>
      <Modal wrapClassName="runtime-pool-confirmation" open={!!confirmation && operation.selected} destroyOnHidden={!confirmation}
        title={confirmation?.action === 'redeploy' ? t('ai.runtime.confirm.pool.redeploy', '确认使用当前配置替换全部 Runtime 副本？')
          : confirmation?.action === 'disable' ? t('ai.runtime.confirm.pool.disable', '确认禁用并回收该 Pool 的全部副本？')
          : t('ai.runtime.confirm.pool.delete', '确认删除该 Pool 和已结束的 Workload 记录？')}
        closable={!operation.busy} maskClosable={!operation.busy} keyboard={!operation.busy}
        onCancel={() => {if (!operation.busy) setConfirmation(undefined);}}
        footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'cancel', sort: 1, text: t('ai.mcp.action.cancel', '取消'), disabled: operation.busy},
          {key: 'confirm', sort: 2, text: t('ai.runtime.action.confirm', '确认'), type: 'primary', danger: confirmation?.action !== 'redeploy',
            loading: operation.busy, disabled: operation.busy},
        ]} onButtonEvents={{cancel: () => {if (!operation.busy) setConfirmation(undefined);}, confirm: () => {
          if (confirmation?.action === 'delete') void remove(confirmation.pool);
          else if (confirmation) void operate(confirmation.pool, confirmation.action);
        }}}/>}>
        <Text strong>{confirmation?.pool.name || confirmation?.pool.code || '—'}</Text>
        {writeError && <Alert type="error" showIcon title={writeError}/>}
      </Modal>
    </>
  );
};

export default RuntimePools;
