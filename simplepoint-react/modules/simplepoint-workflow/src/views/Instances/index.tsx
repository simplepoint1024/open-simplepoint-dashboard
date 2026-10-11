import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App, Drawer, Input, Modal, Segmented, Select, Space, Spin, Tag, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useData} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {
  type ProcessInstance,
  workflowApi,
} from '../../api';
import '../styles.css';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useMutationScope} from '../useMutationScope';

type Scope = 'started' | 'copied' | 'all';

const statusColor: Record<string, string> = {
  RUNNING: 'processing', COMPLETED: 'success', CANCELED: 'default', FAILED: 'error',
};

export default function Instances() {
  const scope = useQueryScope();
  return <InstancesSession key={JSON.stringify(scope)}/>;
}

const ProcessStatusDiagram = lazy(() => import('@simplepoint/components/ProcessStatusDiagram'));

function InstancesSession() {
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp(), mutation = useMutationScope();
  const [scope, setScope] = useState<Scope>('started');
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [diagramId, setDiagramId] = useState<string>();
  const [cancelTarget, setCancelTarget] = useState<ProcessInstance>(), targetRef = useRef<ProcessInstance | undefined>(undefined);
  const [cancelData, setCancelData] = useState({reason: ''}), [canceling, setCanceling] = useState(false), flight = useRef(false);
  const [error, setError] = useState(''), form = useRef<SFormHandle>(null);
  const locked = !!cancelTarget || canceling || !!diagramId;
  usePageRetention(!locked, locked);

  useEffect(() => { void ensure(['workflow']); }, [ensure, locale]);
  const permissionsQuery = useData(['workflow', 'runtime-permissions'], ({signal}) => workflowApi.runtimePermissions(signal));
  const permissions = permissionsQuery.data;
  const instances = useData(['workflow', 'instances', scope, search, status, page, size], ({signal}) =>
    scope === 'started' ? workflowApi.startedInstances(search, page, size, signal)
      : scope === 'copied' ? workflowApi.copiedInstances(search, page, size, signal)
        : workflowApi.instances(search, status, page, size, signal), {retry: false});
  const load = useCallback(async () => {await instances.refetch();}, [instances.refetch]);
  const diagramQuery = useData(['workflow', 'diagram', diagramId], ({signal}) => workflowApi.diagram(diagramId!, signal),
    {enabled: Boolean(diagramId), retry: false});
  const diagram = diagramQuery.data;

  const cancel = (instance: ProcessInstance) => {
    if (flight.current || targetRef.current || permissions?.canCancelInstances !== true || instance.status !== 'RUNNING') return;
    targetRef.current = {...instance}; setCancelTarget(targetRef.current); setCancelData({reason: ''}); setError('');
  };
  const closeCancel = () => {if (flight.current) return; targetRef.current = undefined; setCancelTarget(undefined); setCancelData({reason: ''}); setError('');};
  const submitCancel = async (values: {reason?: string}) => {
    const target = targetRef.current, reason = values.reason?.trim();
    if (!target || flight.current || !reason || reason.length > 500 || permissions?.canCancelInstances !== true) return;
    const request = mutation.begin(); if (!request) return;
    flight.current = true; setCanceling(true); setError('');
    try {
      await workflowApi.cancel(target.id, reason, request.signal);
      if (!mutation.current(request.signal)) return;
      targetRef.current = undefined; setCancelTarget(undefined); setCancelData({reason: ''});
      await load();
      if (mutation.current(request.signal)) message.success(t('workflow.instance.canceled', '流程已终止'));
    } catch (reason) {if (mutation.current(request.signal)) setError(resolveApiErrorMessage(reason, t('workflow.instance.cancelFailed', '流程终止失败或当前账号无权限')));}
    finally {flight.current = false; if (mutation.current()) setCanceling(false);}
  };
  const cancelSchema = useMemo(() => ({type: 'object' as const, required: ['reason'], additionalProperties: false, properties: {
    reason: {type: 'string' as const, title: t('workflow.instance.cancelReason', '请输入终止原因'), minLength: 1, maxLength: 500, pattern: '\\S'},
  }}), [t]);

  const columns: ColumnsType<ProcessInstance> = useMemo(() => [
    {title: t('workflow.instance.title', '事项标题'), dataIndex: 'title', key: 'title'},
    {title: t('workflow.definition.name', '流程名称'), dataIndex: 'definitionName', key: 'definitionName',
      width: 170},
    {title: t('workflow.definition.version', '版本'), dataIndex: 'definitionVersion',
      key: 'definitionVersion', width: 80, render: (value: number) => `v${value}`},
    {title: t('workflow.instance.businessKey', '业务标识'), dataIndex: 'businessKey', key: 'businessKey',
      width: 150, render: (value?: string) => value || '—'},
    {title: t('workflow.instance.starter', '发起人'), dataIndex: 'starterId', key: 'starterId', width: 140},
    {title: t('workflow.instance.status', '状态'), dataIndex: 'status', key: 'status', width: 110,
      render: (value: string) => <Tag color={statusColor[value] ?? 'default'}>
        {t(`workflow.instance.status.${value.toLowerCase()}`, value)}</Tag>},
    {title: t('workflow.instance.startedAt', '发起时间'), dataIndex: 'startedAt', key: 'startedAt',
      width: 190, render: (value: string) => new Date(value).toLocaleString(locale)},
    {title: t('workflow.actions', '操作'), key: 'actions', width: 150, fixed: 'right', render: (_, row) =>
      <TableActions selectedKeys={[row.id]} selectedRows={[row]} buttons={[
        {key: 'diagram', sort: 10, text: t('workflow.action.diagram', '进度'), type: 'link',
          argumentMinSize: 1, argumentMaxSize: 1},
        ...(row.status === 'RUNNING' && permissions?.canCancelInstances ? [{key: 'cancel', sort: 20,
          text: t('workflow.action.cancel', '终止'),
          type: 'link' as const, danger: true, argumentMinSize: 1, argumentMaxSize: 1}] : []),
      ]} isButtonDisabled={() => locked || !mutation.selected} onButtonEvents={{diagram: () => setDiagramId(row.id), cancel: () => cancel(row)}}/>} as const,
  ], [locale, permissions?.canCancelInstances, t, locked, mutation.selected]);

  return <section className="workflow-page">
      {instances.isError && <Alert type="error" showIcon
        message={scope === 'all' ? t('workflow.instances.noAccess', '加载失败，当前账号可能无全部事项权限')
          : t('workflow.loadFailed', '数据加载失败')}
        action={<TableActions selectedKeys={[]} selectedRows={[]}
          buttons={[{key: 'retry', sort: 10, text: t('workflow.retry', '重试'), argumentMinSize: 0, argumentMaxSize: 0}]}
          onButtonEvents={{retry: () => { void load(); }}}/>}/>}
      <DataTable rowKey="id" storageKey={`workflow.instances.${scope}`} columns={columns}
        dataSource={instances.data?.content ?? []} loading={instances.isFetching} refresh={() => void load()} scroll={{x: 1250}}
        toolbarExtra={<Space wrap>
          <Segmented value={scope} disabled={locked} options={[
            {label: t('workflow.instances.started', '我发起的'), value: 'started'},
            {label: t('workflow.instances.copied', '抄送我的'), value: 'copied'},
            ...(permissions?.canViewAllInstances
              ? [{label: t('workflow.instances.all', '全部事项'), value: 'all'}] : []),
          ]} onChange={value => {setScope(value as Scope); setPage(0);}}/>
          {scope === 'all' && <Select value={status} disabled={locked} style={{width: 130}} options={[
            {label: t('workflow.instances.anyStatus', '全部状态'), value: ''},
            {label: t('workflow.instance.status.running', '运行中'), value: 'RUNNING'},
            {label: t('workflow.instance.status.completed', '已完成'), value: 'COMPLETED'},
            {label: t('workflow.instance.status.canceled', '已终止'), value: 'CANCELED'},
          ]} onChange={value => {setStatus(value); setPage(0);}}/>}
          <Input.Search value={keyword} allowClear style={{width: 250}} disabled={locked}
            placeholder={t('workflow.search', '搜索流程或事项')}
            onChange={event => setKeyword(event.target.value)}
            onSearch={value => {setPage(0); setSearch(value.trim());}}/>
        </Space>}
        pagination={{current: page + 1, pageSize: size, total: instances.data?.totalElements ?? 0, showSizeChanger: true, disabled: locked,
          onChange: (next, nextSize) => {setPage(next - 1); setSize(nextSize);}}}/>

    <Drawer title={diagram?.instance.title ?? t('workflow.diagram.title', '流程进度')}
      open={Boolean(diagramId) && mutation.selected} width="min(1100px, 92vw)" loading={diagramQuery.isFetching}
      destroyOnHidden onClose={() => setDiagramId(undefined)}>
      {diagramQuery.isError && <Alert type="error" showIcon title={t('workflow.diagram.loadFailed', '流程状态图加载失败')}/>}
      {diagram && <>
        <Typography.Paragraph type="secondary">
          {diagram.instance.definitionName} · v{diagram.instance.definitionVersion}
        </Typography.Paragraph>
        <Suspense fallback={<Spin/>}><ProcessStatusDiagram xml={diagram.bpmnXml} activities={diagram.activities}
          activeActivityIds={diagram.activeActivityIds} fitActive/></Suspense>
      </>}
    </Drawer>
    <Modal wrapClassName="workflow-instance-cancellation" title={t('workflow.instance.cancelTitle', '终止流程？')}
      destroyOnHidden={!cancelTarget}
      open={!!cancelTarget && mutation.selected} closable={!canceling} maskClosable={!canceling} keyboard={!canceling} onCancel={closeCancel}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'close', sort: 1, text: t('workflow.dialog.cancel', '取消'), disabled: canceling},
        {key: 'cancelInstance', sort: 2, text: t('workflow.action.cancelInstance', '终止流程'), danger: true, type: 'primary', loading: canceling, disabled: canceling},
      ]} onButtonEvents={{close: closeCancel, cancelInstance: () => {if (!flight.current) form.current?.submit();}}}/> }>
      <Typography.Paragraph>{cancelTarget?.title}</Typography.Paragraph>
      {error && <Alert type="error" showIcon title={error}/>}
      <SForm ref={form} hideSubmit schema={cancelSchema} uiSchema={{reason: {'ui:widget': 'textarea'}}} formData={cancelData}
        disabled={canceling} onChange={event => setCancelData(event.formData ?? {reason: ''})}
        onSubmit={event => void submitCancel(event.formData ?? {})}/>
    </Modal>
  </section>;
}
