import DataTable from '../DataTable';
import SForm from '../SForm/Lazy';
import TableActions from '../Table/Actions';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App, Descriptions, Drawer, Modal, Segmented, Space, Spin, Tag} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {type ComponentProps, lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useData} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {type WorkflowInboxItem, workflowApi} from '@simplepoint/shared/workflow/api';
import {useRemoteList} from '../DataTable/useRemoteList';
import './style.css';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useMutationScope} from './useMutationScope';
import {useWorkflowRefresh} from './useWorkflowRefresh';
import {WorkflowStatus} from './Status';
import ProgressDrawer from './ProgressDrawer';
import type {WorkflowVariablesEditorHandle} from '../WorkflowForms/VariablesEditor';
import {useSearchParams} from 'react-router';
import WorkspaceBoundary from './WorkspaceBoundary';

type CompleteValues = {comment?: string};
type TaskView = 'pending' | 'completed';
type FormSchema = ComponentProps<typeof SForm>['schema'];

const VariablesEditor = lazy(() => import('../WorkflowForms/VariablesEditor'));

export default function WorkflowInbox() {
  const scope = useQueryScope();
  return <WorkspaceBoundary key={JSON.stringify(scope)} kind="todos"><TasksSession/></WorkspaceBoundary>;
}

function TasksSession() {
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp(), mutation = useMutationScope();
  const refreshWorkflow = useWorkflowRefresh();
  const [params, setParams] = useSearchParams();
  const [view, setView] = useState<TaskView>(() => params.get('view') === 'completed' ? 'completed' : 'pending');
  const handledLink = useRef<string | undefined>(undefined);
  const [submitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const [completeTarget, setCompleteTarget] = useState<WorkflowInboxItem>();
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectData, setRejectData] = useState<{reason?: string}>({});
  const [completeData, setCompleteData] = useState<CompleteValues>({});
  const [diagramId, setDiagramId] = useState<string>();
  const [variables, setVariables] = useState<Record<string, unknown>>({});
  const [variablesValid, setVariablesValid] = useState(false);
  const variablesEditorRef = useRef<WorkflowVariablesEditorHandle>(null);
  const [formSession, setFormSession] = useState(0);
  const [dirty, setDirty] = useState(false), [discardOpen, setDiscardOpen] = useState(false), [error, setError] = useState('');
  const targetRef = useRef<WorkflowInboxItem | undefined>(undefined);
  const locked = submitting || !!completeTarget || !!diagramId || discardOpen;
  usePageRetention(!locked, locked);

  useEffect(() => { void ensure(['workflow']); }, [ensure, locale]);
  // Preserve each tab's query/page state, but never across authorization scopes.
  const pending = useRemoteList<WorkflowInboxItem>('/workflow/inbox/pending', view === 'pending');
  const completed = useRemoteList<WorkflowInboxItem>('/workflow/inbox/completed', view === 'completed');
  const tasks = view === 'pending' ? pending : completed;
  const linkedTask = params.get('task'), linkedInstance = params.get('instance');
  useEffect(() => {if (!linkedTask && !linkedInstance) handledLink.current = undefined;}, [linkedTask, linkedInstance]);
  const linkedItem = useData(['workflow', 'inbox-entry', linkedTask], ({signal}) => workflowApi.inboxItem(linkedTask!, signal),
    {enabled: Boolean(linkedTask) && mutation.selected, retry: false});
  const clearLink = () => {const next = new URLSearchParams(params); next.delete('task'); next.delete('instance'); setParams(next, {replace: true});};
  useEffect(() => {
    if (!mutation.selected || targetRef.current || diagramId || submittingRef.current) return;
    const key = linkedTask ? `task:${linkedTask}` : linkedInstance ? `instance:${linkedInstance}` : undefined;
    if (!key || handledLink.current === key) return;
    if (linkedTask && linkedItem.data?.taskId === linkedTask) {
      handledLink.current = key;
      if (linkedItem.data.actionable) {
        targetRef.current = linkedItem.data; setCompleteTarget(linkedItem.data); setFormSession(value => value + 1);
        setCompleteData({}); setDirty(false); setError(''); setDiscardOpen(false); setRejectOpen(false); setRejectData({});
      } else setDiagramId(linkedItem.data.processInstanceId);
    } else if (linkedInstance && (!linkedTask || linkedItem.isError)) {
      handledLink.current = key; setDiagramId(linkedInstance);
    }
  }, [linkedTask, linkedInstance, linkedItem.data, linkedItem.isError, diagramId, mutation.selected]);
  const taskForm = useData(['workflow', 'task-form', completeTarget?.id, formSession],
    ({signal}) => workflowApi.taskForm(completeTarget!.taskId!, signal),
    {enabled: Boolean(completeTarget?.taskId), retry: false, staleTime: Infinity, gcTime: 120_000,
      refetchOnWindowFocus: false, refetchOnReconnect: false});
  useEffect(() => {setVariables(taskForm.data?.values ?? {});}, [taskForm.data, completeTarget?.id]);

  const claim = useCallback(async (task: WorkflowInboxItem) => {
    if (submittingRef.current || targetRef.current || !task.claimable || !task.taskId) return;
    const request = mutation.begin(); if (!request) return;
    submittingRef.current = true;
    setSubmitting(true);
    try {
      await workflowApi.claim(task.taskId, request.signal);
      if (!mutation.current(request.signal)) return;
      await refreshWorkflow();
      if (mutation.current(request.signal)) message.success(t('workflow.task.claimed', '任务已认领'));
    } catch {
      if (mutation.current(request.signal)) message.error(t('workflow.task.claimFailed', '任务认领失败'));
    } finally {
      submittingRef.current = false; if (mutation.current()) setSubmitting(false);
    }
  }, [refreshWorkflow, message, t, mutation.begin, mutation.current]);

  const complete = async (values: CompleteValues) => {
    const target = targetRef.current;
    if (!target?.taskId || !target.actionable || !taskForm.data || taskForm.isFetching || submittingRef.current) return;
    let currentVariables: Record<string, unknown>;
    try {
      if (!variablesEditorRef.current) return;
      currentVariables = variablesEditorRef.current.getValues(taskForm.data);
    } catch (reason) {setError((reason as Error).message); return;}
    const request = mutation.begin(); if (!request) return;
    submittingRef.current = true;
    setSubmitting(true);
    setError('');
    try {
      await workflowApi.complete(target.taskId, {
        comment: values.comment?.trim() || undefined,
        variables: currentVariables,
      }, request.signal);
      if (!mutation.current(request.signal)) return;
      targetRef.current = undefined;
      setCompleteTarget(undefined);
      setCompleteData({});
      setDirty(false); setDiscardOpen(false);
      clearLink();
      await refreshWorkflow();
      if (mutation.current(request.signal)) message.success(t('workflow.task.approved', '审批已通过'));
    } catch (reason) {
      if (mutation.current(request.signal)) setError(resolveApiErrorMessage(reason, t('workflow.task.completeFailed', '任务提交失败')));
    } finally {
      submittingRef.current = false; if (mutation.current()) setSubmitting(false);
    }
  };
  const reject = async (reason?: string) => {
    const target = targetRef.current;
    if (!target?.taskId || !target.actionable || submittingRef.current) return;
    const normalized = reason?.trim();
    if (!normalized) {setError(t('workflow.task.reasonRequired', '请填写驳回原因')); return;}
    const request = mutation.begin(); if (!request) return;
    submittingRef.current = true; setSubmitting(true); setError('');
    try {
      await workflowApi.reject(target.taskId, normalized, request.signal);
      if (!mutation.current(request.signal)) return;
      targetRef.current = undefined; setCompleteTarget(undefined); setRejectOpen(false); setRejectData({});
      setCompleteData({}); setDirty(false); setDiscardOpen(false);
      clearLink();
      await refreshWorkflow();
      if (mutation.current(request.signal)) message.success(t('workflow.task.rejected', '审批已驳回'));
    } catch (reason) {
      if (mutation.current(request.signal)) setError(resolveApiErrorMessage(reason, t('workflow.task.rejectFailed', '驳回失败，已保留填写内容')));
    } finally {submittingRef.current = false; if (mutation.current()) setSubmitting(false);}
  };
  const abandon = () => {if (submittingRef.current) return; targetRef.current = undefined; setCompleteTarget(undefined);
    setCompleteData({}); setDirty(false); setDiscardOpen(false); setError(''); setRejectOpen(false); setRejectData({}); clearLink();};
  const close = () => {if (submittingRef.current) return; if (dirty) setDiscardOpen(true); else abandon();};

  const statusTag = (value: string) => <WorkflowStatus value={value}/>;
  const columns: ColumnsType<WorkflowInboxItem> = [
    {title: t('workflow.instance.title', '事项标题'), dataIndex: 'title', key: 'title', width: 230,
      render: (value?: string) => value || '—'},
    {title: t('workflow.inbox.taskName', '当前 / 处理节点'), dataIndex: 'taskName', key: 'taskName', width: 160,
      render: (value?: string) => value || t('workflow.inbox.processAction', '流程操作')},
    {title: t('workflow.definition.name', '流程名称'), dataIndex: 'definitionName', key: 'definitionName',
      width: 170, render: (value?: string) => value || '—'},
    {title: t('workflow.inbox.starterName', '发起人'), dataIndex: 'starterName', key: 'starterName', width: 120,
      render: (value?: string) => value || t('workflow.identity.unavailable', '姓名不可用')},
    {title: view === 'pending' ? t('workflow.task.assignee', '办理人') : t('workflow.inbox.actorName', '实际操作人'),
      dataIndex: view === 'pending' ? 'assigneeName' : 'actorName', key: view === 'pending' ? 'assigneeName' : 'actorName', width: 150,
      render: (value: string | undefined, row) => value || (view === 'pending' && !row.assigned
        ? <Tag>{t('workflow.task.unclaimed', '待认领')}</Tag> : t('workflow.identity.unavailable', '姓名不可用'))},
    ...(view === 'completed' ? [{title: t('workflow.inbox.outcome', '处理结果'), dataIndex: 'outcome', key: 'outcome', width: 110,
      render: statusTag}] : []),
    {title: t('workflow.inbox.instanceStatus', '流程状态'), dataIndex: 'instanceStatus', key: 'instanceStatus', width: 110, render: statusTag},
    {title: view === 'pending' ? t('workflow.task.createdAt', '到达时间')
      : t('workflow.task.completedAt', '完成时间'),
    dataIndex: view === 'pending' ? 'createdAt' : 'handledAt', key: view === 'pending' ? 'createdAt' : 'handledAt', width: 190,
    render: (value?: string) => value ? new Date(value).toLocaleString(locale) : '—'},
    {title: t('workflow.task.priority', '优先级'), dataIndex: 'priority', key: 'priority', width: 90,
      render: (value: number) => value >= 75 ? <Tag color="red">{value}</Tag> : value},
    {title: t('workflow.actions', '操作'), key: 'actions', width: view === 'pending' ? 230 : 100,
      fixed: 'right', render: (_, row) => <TableActions selectedKeys={[row.id]} selectedRows={[row]}
        buttons={[
          {key: 'diagram', sort: 10, text: t('workflow.action.diagram', '进度'), type: 'link',
            argumentMinSize: 1, argumentMaxSize: 1},
          ...(view === 'pending' && row.claimable ? [{key: 'claim', sort: 20,
            text: t('workflow.action.claim', '认领'), type: 'link' as const,
            loading: submitting, argumentMinSize: 1, argumentMaxSize: 1}] : []),
          ...(view === 'pending' && row.actionable ? [{key: 'complete', sort: 30,
            text: t('workflow.action.complete', '办理'), type: 'link' as const,
            argumentMinSize: 1, argumentMaxSize: 1}] : []),
        ]} isButtonDisabled={() => locked || !mutation.selected} onButtonEvents={{
          diagram: () => setDiagramId(row.processInstanceId),
          claim: () => { void claim(row); },
          complete: () => {if (submittingRef.current || targetRef.current) return; targetRef.current = {...row};
            setFormSession(value => value + 1); setCompleteTarget(targetRef.current); setCompleteData({}); setDirty(false); setError(''); setDiscardOpen(false); setRejectOpen(false); setRejectData({});},
        }}/>} as const,
  ];

  const completeSchema = useMemo<FormSchema>(() => ({type: 'object', properties: {
    comment: {type: 'string', title: t('workflow.task.comment', '处理意见'), maxLength: 1000},
  }}), [t]);

  return <section className="workflow-personal-page">
      {linkedItem.error && <Alert type="warning" showIcon title={resolveApiErrorMessage(linkedItem.error,
        t('workflow.inbox.linkUnavailable', '该待办已由他人处理或当前不可访问'))}/>}
      {tasks.error && <Alert type="error" showIcon title={resolveApiErrorMessage(tasks.error, t('workflow.loadFailed', '数据加载失败'))}
        action={<TableActions selectedKeys={[]} selectedRows={[]}
          buttons={[{key: 'retry', sort: 10, text: t('workflow.retry', '重试'), argumentMinSize: 0, argumentMaxSize: 0}]}
          onButtonEvents={{retry: tasks.retry}}/>}/>}
      <DataTable {...tasks.tableProps} rowKey="id" storageKey={`personal.workflow.${view}`} columns={tasks.columns(columns)} scroll={{x: 1550}}
        toolbarExtra={<Space wrap>
          <Segmented value={view} disabled={locked} options={[
            {label: t('workflow.tasks.pending', '待办'), value: 'pending'},
            {label: t('workflow.tasks.completed', '已办'), value: 'completed'},
          ]} onChange={value => {setView(value as TaskView); const next = new URLSearchParams(params); next.set('view', String(value)); setParams(next, {replace: true});}}/>
        </Space>}/>

    <Drawer rootClassName="workflow-task-editor" title={completeTarget?.title} open={Boolean(completeTarget) && mutation.selected} width={640}
      destroyOnHidden={!completeTarget} closable={!submitting} maskClosable={!submitting} keyboard={!submitting} onClose={close}>
      {error && <Alert type="error" showIcon title={error}/>}
      {completeTarget && <Descriptions size="small" column={2} items={[
        {key: 'flow', label: t('workflow.definition.name', '流程名称'), children: completeTarget.definitionName},
        {key: 'starter', label: t('workflow.inbox.starterName', '发起人'), children: completeTarget.starterName || t('workflow.identity.unavailable', '姓名不可用')},
        {key: 'node', label: t('workflow.task.name', '任务'), children: completeTarget.taskName},
      ]}/>}
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'progress', sort: 1, text: t('workflow.action.diagram', '进度')},
        {key: 'reject', sort: 2, text: t('workflow.action.reject', '驳回'), danger: true},
      ]} isButtonDisabled={() => submitting} onButtonEvents={{
        progress: () => setDiagramId(completeTarget?.processInstanceId), reject: () => setRejectOpen(true),
      }}/>
      {taskForm.isFetching ? <Spin/> : taskForm.isError ? <Alert type="error" showIcon
        title={t('workflow.form.loadFailed', '流程表单加载失败，请关闭后重试')}/> : taskForm.data && <>
      <Suspense fallback={<Spin/>}><VariablesEditor ref={variablesEditorRef} key={completeTarget?.id} form={taskForm.data}
        stage="task" taskId={completeTarget?.taskId} value={variables} onChange={next => {setVariables(next); setDirty(true);}}
        onValidityChange={setVariablesValid} onDirtyChange={setDirty} disabled={submitting}/></Suspense>
      <SForm schema={completeSchema} uiSchema={{comment: {'ui:widget': 'textarea'},
        'ui:submitButtonOptions': {submitText: t('workflow.action.approve', '通过')}}}
        formData={completeData} submitLoading={submitting} disabled={submitting} submitDisabled={!variablesValid}
        onChange={(event: {formData?: CompleteValues}) => {setCompleteData(event.formData ?? {}); setDirty(true);}}
        onSubmit={(event: {formData?: CompleteValues}) => void complete(event.formData ?? {})}/></>}
    </Drawer>

    <Modal wrapClassName="workflow-task-rejection" open={rejectOpen && mutation.selected} title={t('workflow.action.reject', '驳回')} footer={null}
      closable={!submitting} maskClosable={!submitting} keyboard={!submitting} onCancel={() => {if (!submittingRef.current) setRejectOpen(false);}}>
      <Alert type="warning" showIcon title={t('workflow.task.rejectHint', '驳回将结束本轮审批，并保留全部处理记录。')}/>
      {error && <Alert type="error" showIcon title={error}/>}
      <SForm schema={{type: 'object', required: ['reason'], properties: {
        reason: {type: 'string', title: t('workflow.task.rejectReason', '驳回原因'), minLength: 1, maxLength: 500},
      }}} uiSchema={{reason: {'ui:widget': 'textarea'}, 'ui:submitButtonOptions': {submitText: t('workflow.action.confirmReject', '确认驳回')}}}
        formData={rejectData} disabled={submitting} submitLoading={submitting}
        onChange={(event: {formData?: {reason?: string}}) => {setRejectData(event.formData ?? {}); setDirty(true);}}
        onSubmit={(event: {formData?: {reason?: string}}) => void reject(event.formData?.reason)}/>
    </Modal>

    {diagramId && <ProgressDrawer id={diagramId} onClose={() => {setDiagramId(undefined); clearLink();}}/>}
    <Modal wrapClassName="workflow-task-discard" open={discardOpen && mutation.selected} title={t('workflow.unsaved.title', '放弃未保存的修改？')}
      destroyOnHidden={!discardOpen}
      onCancel={() => setDiscardOpen(false)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', sort: 1, text: t('workflow.unsaved.continue', '继续编辑')},
        {key: 'discard', sort: 2, text: t('workflow.unsaved.discard', '放弃修改'), danger: true, type: 'primary'},
      ]} onButtonEvents={{continue: () => setDiscardOpen(false), discard: abandon}}/>}/>
  </section>;
}
