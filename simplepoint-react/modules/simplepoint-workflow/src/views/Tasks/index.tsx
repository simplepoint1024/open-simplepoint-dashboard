import DataTable from '@simplepoint/components/DataTable';
import SForm from '@simplepoint/components/SForm/Lazy';
import TableActions from '@simplepoint/components/Table/Actions';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App, Drawer, Input, Modal, Segmented, Space, Spin, Tag} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {type ComponentProps, lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useData} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {type TaskSummary, workflowApi} from '../../api';
import '../styles.css';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useMutationScope} from '../useMutationScope';
import type {WorkflowVariablesEditorHandle} from '@simplepoint/components/WorkflowForms/VariablesEditor';

type CompleteValues = {comment?: string};
type TaskView = 'pending' | 'completed';
type FormSchema = ComponentProps<typeof SForm>['schema'];

const ProcessStatusDiagram = lazy(() => import('@simplepoint/components/ProcessStatusDiagram'));
const VariablesEditor = lazy(() => import('@simplepoint/components/WorkflowForms/VariablesEditor'));

export default function Tasks() {
  const scope = useQueryScope();
  return <TasksSession key={JSON.stringify(scope)}/>;
}

function TasksSession() {
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp(), mutation = useMutationScope();
  const [view, setView] = useState<TaskView>('pending');
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [submitting, setSubmitting] = useState(false);
  const submittingRef = useRef(false);
  const [completeTarget, setCompleteTarget] = useState<TaskSummary>();
  const [completeData, setCompleteData] = useState<CompleteValues>({});
  const [diagramId, setDiagramId] = useState<string>();
  const [variables, setVariables] = useState<Record<string, unknown>>({});
  const [variablesValid, setVariablesValid] = useState(false);
  const variablesEditorRef = useRef<WorkflowVariablesEditorHandle>(null);
  const [formSession, setFormSession] = useState(0);
  const [dirty, setDirty] = useState(false), [discardOpen, setDiscardOpen] = useState(false), [error, setError] = useState('');
  const targetRef = useRef<TaskSummary | undefined>(undefined);
  const locked = submitting || !!completeTarget || !!diagramId || discardOpen;
  usePageRetention(!locked, locked);

  useEffect(() => { void ensure(['workflow']); }, [ensure, locale]);
  const permissionsQuery = useData(['workflow', 'runtime-permissions'], ({signal}) => workflowApi.runtimePermissions(signal));
  const permissions = permissionsQuery.data;
  const tasks = useData(['workflow', 'tasks', view, search, page, size], ({signal}) => view === 'pending'
    ? workflowApi.pendingTasks(search, page, size, signal) : workflowApi.completedTasks(search, page, size, signal), {retry: false});
  const load = useCallback(async () => {await tasks.refetch();}, [tasks.refetch]);
  const diagramQuery = useData(['workflow', 'diagram', diagramId], ({signal}) => workflowApi.diagram(diagramId!, signal),
    {enabled: Boolean(diagramId), retry: false});
  const diagram = diagramQuery.data;
  const taskForm = useData(['workflow', 'task-form', completeTarget?.id, formSession],
    ({signal}) => workflowApi.taskForm(completeTarget!.id, signal),
    {enabled: Boolean(completeTarget), retry: false, staleTime: Infinity, gcTime: 120_000,
      refetchOnWindowFocus: false, refetchOnReconnect: false});
  useEffect(() => {setVariables(taskForm.data?.values ?? {});}, [taskForm.data, completeTarget?.id]);

  const claim = useCallback(async (task: TaskSummary) => {
    if (submittingRef.current || targetRef.current || !task.claimable || permissions?.canClaim !== true) return;
    const request = mutation.begin(); if (!request) return;
    submittingRef.current = true;
    setSubmitting(true);
    try {
      await workflowApi.claim(task.id, request.signal);
      if (!mutation.current(request.signal)) return;
      await load();
      if (mutation.current(request.signal)) message.success(t('workflow.task.claimed', '任务已认领'));
    } catch {
      if (mutation.current(request.signal)) message.error(t('workflow.task.claimFailed', '任务认领失败'));
    } finally {
      submittingRef.current = false; if (mutation.current()) setSubmitting(false);
    }
  }, [load, message, t, permissions?.canClaim, mutation.begin, mutation.current]);

  const complete = async (values: CompleteValues) => {
    const target = targetRef.current;
    if (!target || !target.actionable || permissions?.canComplete !== true || !taskForm.data || taskForm.isFetching || submittingRef.current) return;
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
      await workflowApi.complete(target.id, {
        comment: values.comment?.trim() || undefined,
        variables: currentVariables,
      }, request.signal);
      if (!mutation.current(request.signal)) return;
      targetRef.current = undefined;
      setCompleteTarget(undefined);
      setCompleteData({});
      setDirty(false); setDiscardOpen(false);
      await load();
      if (mutation.current(request.signal)) message.success(t('workflow.task.completed', '任务已提交'));
    } catch (reason) {
      if (mutation.current(request.signal)) setError(resolveApiErrorMessage(reason, t('workflow.task.completeFailed', '任务提交失败')));
    } finally {
      submittingRef.current = false; if (mutation.current()) setSubmitting(false);
    }
  };
  const abandon = () => {if (submittingRef.current) return; targetRef.current = undefined; setCompleteTarget(undefined);
    setCompleteData({}); setDirty(false); setDiscardOpen(false); setError('');};
  const close = () => {if (submittingRef.current) return; if (dirty) setDiscardOpen(true); else abandon();};

  const columns: ColumnsType<TaskSummary> = useMemo(() => [
    {title: t('workflow.task.name', '任务'), dataIndex: 'name', key: 'name', width: 180},
    {title: t('workflow.instance.title', '事项标题'), dataIndex: 'instanceTitle', key: 'instanceTitle',
      render: (value?: string) => value || '—'},
    {title: t('workflow.definition.name', '流程名称'), dataIndex: 'definitionName', key: 'definitionName',
      width: 170, render: (value?: string) => value || '—'},
    {title: t('workflow.task.assignee', '办理人'), dataIndex: 'assignee', key: 'assignee', width: 150,
      render: (value?: string) => value || <Tag>{t('workflow.task.unclaimed', '待认领')}</Tag>},
    {title: view === 'pending' ? t('workflow.task.createdAt', '到达时间')
      : t('workflow.task.completedAt', '完成时间'),
    dataIndex: view === 'pending' ? 'createdAt' : 'completedAt', key: 'time', width: 190,
    render: (value?: string) => value ? new Date(value).toLocaleString(locale) : '—'},
    {title: t('workflow.task.priority', '优先级'), dataIndex: 'priority', key: 'priority', width: 90,
      render: (value: number) => value >= 75 ? <Tag color="red">{value}</Tag> : value},
    {title: t('workflow.actions', '操作'), key: 'actions', width: view === 'pending' ? 230 : 100,
      fixed: 'right', render: (_, row) => <TableActions selectedKeys={[row.id]} selectedRows={[row]}
        buttons={[
          {key: 'diagram', sort: 10, text: t('workflow.action.diagram', '进度'), type: 'link',
            argumentMinSize: 1, argumentMaxSize: 1},
          ...(view === 'pending' && row.claimable && permissions?.canClaim ? [{key: 'claim', sort: 20,
            text: t('workflow.action.claim', '认领'), type: 'link' as const,
            loading: submitting, argumentMinSize: 1, argumentMaxSize: 1}] : []),
          ...(view === 'pending' && row.actionable && permissions?.canComplete ? [{key: 'complete', sort: 30,
            text: t('workflow.action.complete', '办理'), type: 'link' as const,
            argumentMinSize: 1, argumentMaxSize: 1}] : []),
        ]} isButtonDisabled={() => locked || !mutation.selected} onButtonEvents={{
          diagram: () => setDiagramId(row.processInstanceId),
          claim: () => { void claim(row); },
          complete: () => {if (submittingRef.current || targetRef.current) return; targetRef.current = {...row};
            setFormSession(value => value + 1); setCompleteTarget(targetRef.current); setCompleteData({}); setDirty(false); setError(''); setDiscardOpen(false);},
        }}/>} as const,
  ], [claim, locale, permissions, submitting, t, view, locked, mutation.selected]);

  const completeSchema = useMemo<FormSchema>(() => ({type: 'object', properties: {
    comment: {type: 'string', title: t('workflow.task.comment', '处理意见'), maxLength: 1000},
  }}), [t]);

  return <section className="workflow-page">
      {tasks.isError && <Alert type="error" showIcon message={t('workflow.loadFailed', '数据加载失败')}
        action={<TableActions selectedKeys={[]} selectedRows={[]}
          buttons={[{key: 'retry', sort: 10, text: t('workflow.retry', '重试'), argumentMinSize: 0, argumentMaxSize: 0}]}
          onButtonEvents={{retry: () => { void load(); }}}/>}/>}
      <DataTable rowKey="id" storageKey={`workflow.tasks.${view}`} columns={columns}
        dataSource={tasks.data?.content ?? []} loading={tasks.isFetching} refresh={() => void load()} scroll={{x: 1150}}
        toolbarExtra={<Space wrap>
          <Segmented value={view} disabled={locked} options={[
            {label: t('workflow.tasks.pending', '待办'), value: 'pending'},
            {label: t('workflow.tasks.completed', '已办'), value: 'completed'},
          ]} onChange={value => {setView(value as TaskView); setPage(0);}}/>
          <Input.Search value={keyword} allowClear style={{width: 260}} disabled={locked}
            placeholder={t('workflow.search', '搜索流程或事项')}
            onChange={event => setKeyword(event.target.value)}
            onSearch={value => {setPage(0); setSearch(value.trim());}}/>
        </Space>}
        pagination={{current: page + 1, pageSize: size, total: tasks.data?.totalElements ?? 0, showSizeChanger: true, disabled: locked,
          onChange: (next, nextSize) => {setPage(next - 1); setSize(nextSize);}}}/>

    <Drawer rootClassName="workflow-task-editor" title={completeTarget?.name} open={Boolean(completeTarget) && mutation.selected} width={560}
      destroyOnHidden={!completeTarget} closable={!submitting} maskClosable={!submitting} keyboard={!submitting} onClose={close}>
      {error && <Alert type="error" showIcon title={error}/>}
      {taskForm.isFetching ? <Spin/> : taskForm.isError ? <Alert type="error" showIcon
        title={t('workflow.form.loadFailed', '流程表单加载失败，请关闭后重试')}/> : taskForm.data && <>
      <Suspense fallback={<Spin/>}><VariablesEditor ref={variablesEditorRef} key={completeTarget?.id} form={taskForm.data}
        stage="task" taskId={completeTarget?.id} value={variables} onChange={next => {setVariables(next); setDirty(true);}}
        onValidityChange={setVariablesValid} onDirtyChange={setDirty} disabled={submitting}/></Suspense>
      <SForm schema={completeSchema} uiSchema={{comment: {'ui:widget': 'textarea'}}}
        formData={completeData} submitLoading={submitting} disabled={submitting} submitDisabled={!variablesValid}
        onChange={(event: {formData?: CompleteValues}) => {setCompleteData(event.formData ?? {}); setDirty(true);}}
        onSubmit={(event: {formData?: CompleteValues}) => void complete(event.formData ?? {})}/></>}
    </Drawer>

    <Drawer title={diagram?.instance.title ?? t('workflow.diagram.title', '流程进度')}
      open={Boolean(diagramId) && mutation.selected} width="min(1100px, 92vw)" loading={diagramQuery.isFetching}
      destroyOnHidden onClose={() => setDiagramId(undefined)}>
      {diagramQuery.isError && <Alert type="error" showIcon title={t('workflow.diagram.loadFailed', '流程状态图加载失败')}/>}
      {diagram && <Suspense fallback={<Spin/>}><ProcessStatusDiagram xml={diagram.bpmnXml} activities={diagram.activities}
        activeActivityIds={diagram.activeActivityIds} fitActive/></Suspense>}
    </Drawer>
    <Modal wrapClassName="workflow-task-discard" open={discardOpen && mutation.selected} title={t('workflow.unsaved.title', '放弃未保存的修改？')}
      destroyOnHidden={!discardOpen}
      onCancel={() => setDiscardOpen(false)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', sort: 1, text: t('workflow.unsaved.continue', '继续编辑')},
        {key: 'discard', sort: 2, text: t('workflow.unsaved.discard', '放弃修改'), danger: true, type: 'primary'},
      ]} onButtonEvents={{continue: () => setDiscardOpen(false), discard: abandon}}/>}/>
  </section>;
}
