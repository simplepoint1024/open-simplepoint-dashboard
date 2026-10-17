import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {useRuntimeServerNames} from './runtimeQueries';
import {useDeploymentSchema} from './useDeploymentSchema';
import {useRemoteList} from '@simplepoint/components/DataTable/useRemoteList';
import {post} from '@simplepoint/shared/api/methods';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {useOperationScope} from './useOperationScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  App,
  Descriptions,
  Modal,
  Space,
  Tag,
  Typography,
} from 'antd';
import {useCallback, useMemo, useRef, useState} from 'react';
import {
  runtimeErrorLabel,
  runtimeNetworkModeLabel,
  runtimeStatusLabel,
} from './runtimeLabels';
import type {RuntimeWorkload} from './types';
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
  active?: boolean;
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
  active = true,
  workloadsUrl,
  secretsUrl,
  serversUrl,
  canManage,
}: RuntimeWorkloadsProps) => {
  const {t} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope();
  const form = useRef<SFormHandle>(null);
  const [formData, setFormData] = useState<Partial<WorkloadFormValues>>({});
  const submittingRef = useRef(false);
  const schema = useDeploymentSchema({serversUrl, secretsUrl, pool: false, networkMode: formData.networkMode});
  const list = useRemoteList<RuntimeWorkload>(workloadsUrl, active);
  const load = list.refresh;
  const loadError = list.error ? resolveErrorMessage(list.error, t('table.loadFail', '加载失败')) : undefined;
  const [submitOpen, setSubmitOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [detail, setDetail] = useState<RuntimeWorkload>();
  const [stopping, setStopping] = useState<RuntimeWorkload>();
  const [writeError, setWriteError] = useState('');
  const attemptedExecution = useRef<string | undefined>(undefined);
  const locked = operation.busy || submitOpen || !!detail || !!stopping;
  usePageRetention(false, operation.busy || submitOpen || !!stopping);


  const serverNames = useRuntimeServerNames(serversUrl, list.page.content, active);

  const submit = useCallback(async (values: WorkloadFormValues) => {
    if (!canManage || !submitOpen || loadError || submittingRef.current) return;
    const request = operation.begin(); if (!request) return;
    attemptedExecution.current ??= values.executionId?.trim() || crypto.randomUUID();
    if (!values.executionId?.trim()) setFormData(previous => ({...previous, executionId: attemptedExecution.current}));
    submittingRef.current = true; setSubmitting(true); setWriteError('');
    try {
      await post(workloadsUrl, {
        executionId: attemptedExecution.current,
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
      }, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setSubmitOpen(false);
      setFormData({});
      message.success(t('ai.runtime.success.workload.submitted', 'Runtime Workload 已提交'));
      await load();
    } catch (error) {
      if (!operation.current(request.signal)) return;
      setWriteError(resolveErrorMessage(
        error,
        t('ai.runtime.error.workload.submit', 'Runtime Workload 提交失败'),
      ));
    } finally {
      submittingRef.current = false; operation.finish(request); if (operation.current()) setSubmitting(false);
    }
  }, [canManage, load, loadError, submitOpen, message, t, workloadsUrl, operation.begin, operation.current, operation.finish]);

  const stop = async (workload: RuntimeWorkload) => {
    if (!canManage || !workload.id || TERMINAL_STATUSES.has(workload.status) || loadError) return;
    const request = operation.begin(); if (!request) return; setWriteError('');
    try {
      await post(workloadsUrl + '/' + encodeURIComponent(workload.id) + '/stop', {}, {signal: request.signal});
      if (!operation.current(request.signal)) return;
      setStopping(undefined); message.success(t('ai.runtime.success.workload.stopped', '已请求停止 Workload')); await load();
    } catch (error) {if (operation.current(request.signal)) setWriteError(resolveErrorMessage(error,
      t('ai.runtime.error.workload.stop', 'Runtime Workload 停止失败')));}
    finally {operation.finish(request);}
  };

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
    {
      title: t('ai.runtime.field.action', '操作'),
      key: 'action',
      fixed: 'right' as const,
      width: 150,
      render: (_: unknown, workload: RuntimeWorkload) => (
        <TableActions selectedKeys={[workload.id]} selectedRows={[workload]} buttons={[
          {key: 'detail', sort: 1, text: t('ai.runtime.action.details', '详情'), type: 'link', size: 'small'},
          {key: 'stop', sort: 2, text: t('ai.runtime.action.stop', '停止'), type: 'link', size: 'small', danger: true,
            disabled: !canManage || TERMINAL_STATUSES.has(workload.status)},
        ]} isButtonDisabled={() => locked || !operation.selected || !!loadError}
          onButtonEvents={{detail: () => {if (!locked && operation.isSelected()) setDetail(structuredClone(workload));},
          stop: () => {
            if (locked || !canManage || !operation.isSelected() || !workload.id || TERMINAL_STATUSES.has(workload.status)) return;
            setWriteError(''); setStopping(structuredClone(workload));
          }}}/>
      ),
    },
  ], [canManage, serverNames, t, locked, operation.selected, loadError]);

  return (
    <>
      <div className="runtime-list-workloads" style={{display: 'flex', flexDirection: 'column', minHeight: 0, gap: 12}}>
        {writeError && !submitOpen && !stopping && <Alert type='error' showIcon title={writeError}/>}
        {loadError && (
          <Alert
            showIcon
            type="error"
            style={{marginBottom: 16}}
            message={t('ai.runtime.error.workloads.load', 'Runtime Workload 加载失败')}
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
            'ai.runtime.workload.notice',
            '这里同时显示 Pool 自动创建的副本和手动提交的 OCI MCP 测试任务。',
          )}
        />
        <DataTable<RuntimeWorkload>
          {...list.tableProps}
          refresh={() => {if (!locked) void load();}}
          pagination={list.tableProps.pagination && typeof list.tableProps.pagination === 'object' ? {...list.tableProps.pagination, disabled: locked} : list.tableProps.pagination}
          toolbarExtra={<Space>
            {canManage && (
              <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
                {key: 'submit', sort: 1, text: t('ai.runtime.action.workload.submit', '提交测试 Workload'), type: 'primary', disabled: locked || !!loadError || !operation.selected},
              ]} onButtonEvents={{submit: () => {
                if (locked || !operation.isSelected() || loadError) return;
                attemptedExecution.current = undefined; setWriteError('');
                setFormData({
                  executionId: crypto.randomUUID(),
                  memoryMiB: 256,
                  cpuCores: 0.5,
                  pidsLimit: 128,
                  timeoutSeconds: 3600,
                  networkMode: 'none',
                  secretIds: [],
                });
                setSubmitOpen(true);
              }}}/>
            )}
          </Space>}
          query={list.tableProps.query}
          columns={list.columns(columns)}
          scroll={{x: 1800}}
        />
      </div>
      <Modal
        wrapClassName='runtime-workload-submit'
        open={submitOpen && operation.selected}
        title={t('ai.runtime.workload.submit', '提交 Runtime Workload')}
        width={860}
        okText={t('ai.runtime.action.submit', '提交')}
        confirmLoading={submitting}
        closable={!submitting} maskClosable={!submitting} keyboard={!submitting}
        destroyOnHidden={!submitOpen}
        onCancel={() => {
          if (submittingRef.current) return;
          setFormData({});
          setSubmitOpen(false);
        }}
        onOk={() => form.current?.submit()}
      >
        {writeError && <Alert type='error' showIcon title={writeError}/>}
<SForm ref={form} hideSubmit schema={schema} formData={formData} disabled={submitting}
          uiSchema={{executionId: {'ui:readonly': !!attemptedExecution.current}}}
          onChange={({formData}) => {if (!submittingRef.current) setFormData(formData ?? {});}}
          onSubmit={({formData}) => void submit(formData as WorkloadFormValues)}/>
      </Modal>
      <Modal
        open={Boolean(detail) && operation.selected}
        destroyOnHidden={!detail}
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
      <Modal wrapClassName="runtime-workload-stop" open={!!stopping && operation.selected} destroyOnHidden={!stopping}
        title={t('ai.runtime.confirm.workload.stop', '确认停止该 Runtime Workload？')}
        closable={!operation.busy} maskClosable={!operation.busy} keyboard={!operation.busy}
        onCancel={() => {if (!operation.busy) setStopping(undefined);}}
        footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'cancel', sort: 1, text: t('ai.mcp.action.cancel', '取消'), disabled: operation.busy},
          {key: 'confirm', sort: 2, text: t('ai.runtime.action.confirm', '确认'), type: 'primary', danger: true, loading: operation.busy, disabled: operation.busy},
        ]} onButtonEvents={{cancel: () => {if (!operation.busy) setStopping(undefined);}, confirm: () => {if (stopping) void stop(stopping);}}}/>}>
        <Text code>{stopping?.executionId || stopping?.id || '—'}</Text>
        {writeError && <Alert type="error" showIcon title={writeError}/>}
      </Modal>
    </>
  );
};

export default RuntimeWorkloads;
