import {lazy, Suspense, useEffect, useRef, useState} from 'react';
import {Alert, App, Modal, Segmented, Space, Spin} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useData} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {workflowApi, type WorkflowMatter} from '@simplepoint/shared/workflow/api';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import DataTable from '../DataTable';
import {useRemoteList} from '../DataTable/useRemoteList';
import TableActions from '../Table/Actions';
import SForm from '../SForm/Lazy';
import {useMutationScope} from './useMutationScope';
import {useWorkflowRefresh} from './useWorkflowRefresh';
import {WorkflowStatus} from './Status';
import ProgressDrawer from './ProgressDrawer';
import './style.css';
import {useSearchParams} from 'react-router';
import WorkspaceBoundary from './WorkspaceBoundary';

const StartProcess = lazy(() => import('./StartProcess'));
const ResubmitProcess = lazy(() => import('./ResubmitProcess'));
type View = 'started' | 'copied' | 'all';
type Operation = {item: WorkflowMatter; kind: 'withdraw' | 'cancel'};

/** Personal process actions live alongside their matters; management adds an authorized all view. */
export default function WorkflowMatters({management = false}: {management?: boolean}) {
  const scope = useQueryScope();
  return <WorkspaceBoundary key={JSON.stringify(scope)} kind="matters"><MattersSession management={management}/></WorkspaceBoundary>;
}

function MattersSession({management}: {management: boolean}) {
  const {t, ensure, locale} = useI18n(), {message} = App.useApp();
  const mutation = useMutationScope(), refreshWorkflow = useWorkflowRefresh();
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState<View>(management ? 'all' : params.get('view') === 'copied' ? 'copied' : 'started');
  const [operation, setOperation] = useState<Operation>();
  const [reason, setReason] = useState<{reason?: string}>({});
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), flight = useRef(false);
  const [diagramId, setDiagramId] = useState<string>();
  const [starting, setStarting] = useState(false);
  const [resubmitting, setResubmitting] = useState<string>();
  const locked = Boolean(operation || diagramId || busy || starting || resubmitting);
  const linkedInstance = params.get('instance'), handledLink = useRef<string | undefined>(undefined);
  usePageRetention(!locked, locked);
  useEffect(() => {void ensure(['workflow']);}, [ensure, locale]);
  useEffect(() => {
    if (!linkedInstance) {handledLink.current = undefined; return;}
    if (mutation.selected && linkedInstance && !locked && handledLink.current !== linkedInstance) {
      handledLink.current = linkedInstance; setDiagramId(linkedInstance);
    }
  }, [linkedInstance, locked, mutation.selected]);
  const permissions = useData(['workflow', 'runtime-permissions'], ({signal}) => workflowApi.runtimePermissions(signal), {retry: false});
  const started = useRemoteList<WorkflowMatter>('/workflow/matters/started', view === 'started');
  const copied = useRemoteList<WorkflowMatter>('/workflow/matters/copied', view === 'copied');
  const all = useRemoteList<WorkflowMatter>('/workflow/matters/all', management && view === 'all' && permissions.data?.canViewAllInstances === true);
  const table = view === 'started' ? started : view === 'copied' ? copied : all;
  const close = () => {if (!flight.current) {setOperation(undefined); setReason({}); setError('');}};
  const perform = async (value?: string) => {
    if (!operation || flight.current) return;
    const text = value?.trim();
    if (!text) {setError(t('workflow.matter.reasonRequired', '请填写操作原因')); return;}
    const request = mutation.begin(); if (!request) return;
    flight.current = true; setBusy(true); setError('');
    try {
      await workflowApi[operation.kind](operation.item.id, text, request.signal);
      if (!mutation.current(request.signal)) return;
      setOperation(undefined); setReason({});
      await refreshWorkflow();
      if (mutation.current(request.signal)) message.success(t('workflow.matter.actionSucceeded', '操作成功，处理记录已保留'));
    } catch (cause) {
      if (mutation.current(request.signal)) setError(resolveApiErrorMessage(cause, t('workflow.matter.actionFailed', '操作失败，已保留填写内容')));
    } finally {flight.current = false; if (mutation.current()) setBusy(false);}
  };
  const time = (value?: string) => value ? new Date(value).toLocaleString(locale) : '—';
  const columns: ColumnsType<WorkflowMatter> = [
    {key: 'title', dataIndex: 'title', title: t('workflow.instance.title', '事项标题'), width: 240},
    {key: 'definitionName', dataIndex: 'definitionName', title: t('workflow.definition.name', '流程名称'), width: 180},
    {key: 'definitionVersion', dataIndex: 'definitionVersion', title: t('workflow.matter.definitionVersion', '流程版本'), width: 100},
    {key: 'starterName', dataIndex: 'starterName', title: t('workflow.inbox.starterName', '发起人'), width: 120,
      render: value => value || t('workflow.identity.unavailable', '用户信息不可用')},
    {key: 'status', dataIndex: 'status', title: t('workflow.inbox.instanceStatus', '流程状态'), width: 120,
      render: value => <WorkflowStatus value={value}/>},
    {key: 'startedAt', dataIndex: 'startedAt', title: t('workflow.matter.startedAt', '发起时间'), width: 185, render: time},
    {key: 'endedAt', dataIndex: 'endedAt', title: t('workflow.matter.endedAt', '结束时间'), width: 185, render: time},
    {key: 'actions', title: t('workflow.actions', '操作'), width: 230, fixed: 'right', render: (_, item) =>
      <TableActions selectedKeys={[item.id]} selectedRows={[item]} isButtonDisabled={() => locked || !mutation.selected}
        buttons={[
          {key: 'progress', sort: 1, text: t('workflow.action.diagram', '进度'), type: 'link'},
          ...(item.canWithdraw ? [{key: 'withdraw', sort: 2, text: t('workflow.action.withdraw', '撤回'), type: 'link' as const}] : []),
          ...(item.canCancel ? [{key: 'cancel', sort: 3, text: t('workflow.action.cancel', '取消'), danger: true, type: 'link' as const}] : []),
          ...(item.canResubmit ? [{key: 'resubmit', sort: 4, text: t('workflow.action.resubmit', '修改后重新发起'), type: 'link' as const}] : []),
        ]} onButtonEvents={{progress: () => setDiagramId(item.id),
          withdraw: () => {setOperation({item, kind: 'withdraw'}); setReason({}); setError('');},
          cancel: () => {setOperation({item, kind: 'cancel'}); setReason({}); setError('');},
          resubmit: () => setResubmitting(item.id),
        }}/>},
  ];
  return <section className="workflow-personal-page">
    {table.error && <Alert showIcon type="error" title={resolveApiErrorMessage(table.error, t('workflow.loadFailed', '数据加载失败'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1, text: t('workflow.retry', '重试')}]}
        onButtonEvents={{retry: table.retry}}/>}/>}
    <DataTable {...table.tableProps} storageKey={`personal.workflow.matters.${view}`} columns={table.columns(columns)} scroll={{x: 1380}}
      toolbarExtra={<Space wrap><Segmented value={view} disabled={locked} onChange={value => setView(value as View)} options={management ? [
        {value: 'all', label: t('workflow.instances.all', '全部事项')},
      ] : [
        {value: 'started', label: t('workflow.instances.started', '我发起的')},
        {value: 'copied', label: t('workflow.instances.copied', '抄送我的')},
      ]}/>{!management && permissions.data?.canStartInstances && <TableActions selectedKeys={[]} selectedRows={[]} isButtonDisabled={() => locked}
        buttons={[{key: 'start', sort: 1, text: t('workflow.action.start', '发起流程'), type: 'primary'}]}
        onButtonEvents={{start: () => setStarting(true)}}/>}</Space>}/>
    {starting && <Suspense fallback={<Spin/>}><StartProcess onClose={() => setStarting(false)}/></Suspense>}
    {resubmitting && <Suspense fallback={<Spin/>}><ResubmitProcess id={resubmitting} onClose={() => setResubmitting(undefined)}/></Suspense>}
    <Modal wrapClassName="workflow-matter-operation" title={operation?.kind === 'withdraw' ? t('workflow.action.withdraw', '撤回') : t('workflow.action.cancel', '取消')}
      open={Boolean(operation) && mutation.selected} footer={null} closable={!busy} maskClosable={!busy} keyboard={!busy} onCancel={close}>
      <p>{operation?.item.title}</p>
      <Alert showIcon type="warning" title={t('workflow.matter.terminateHint', '操作将结束当前流程。历史审批记录会保留，本次操作也会进入已办。')}/>
      {error && <Alert showIcon type="error" title={error}/>}
      <SForm schema={{type: 'object', required: ['reason'], properties: {reason: {
        type: 'string', title: t('workflow.matter.reason', '操作原因'), minLength: 1, maxLength: 500,
      }}}} uiSchema={{reason: {'ui:widget': 'textarea'}, 'ui:submitButtonOptions': {submitText: t('workflow.matter.confirm', '确认操作')}}}
        formData={reason} disabled={busy} submitLoading={busy}
        onChange={(event: {formData?: {reason?: string}}) => setReason(event.formData ?? {})}
        onSubmit={(event: {formData?: {reason?: string}}) => void perform(event.formData?.reason)}/>
    </Modal>
    {diagramId && <ProgressDrawer id={diagramId} onClose={() => {setDiagramId(undefined);
      const next = new URLSearchParams(params); next.delete('instance'); setParams(next, {replace: true});}}/>}
  </section>;
}
