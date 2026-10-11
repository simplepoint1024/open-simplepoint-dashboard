import DataTable from '@simplepoint/components/DataTable';
import {useRemoteList} from '@simplepoint/components/DataTable/useRemoteList';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import TableActions from '@simplepoint/components/Table/Actions';
import type {
  WorkflowDesignerHandle,
  WorkflowDesignerValidation,
} from '@simplepoint/components/WorkflowDesigner';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {Alert, App, Drawer, Modal, Space, Spin, Tag, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {type ComponentProps, lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useData} from '@simplepoint/shared/api/methods';
import {useQueryClient} from '@tanstack/react-query';
import {scopedQueryOptions} from '@simplepoint/shared/api/queryScope';
import type {WorkflowVariablesEditorHandle} from '@simplepoint/components/WorkflowForms/VariablesEditor';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {emptyFormContract, expressionVariables, type WorkflowFormContract} from '@simplepoint/components/WorkflowDesigner/contracts';
import {
  type DefinitionDetail,
  type DefinitionSummary,
  type VersionSummary,
  type VersionDetail,
  workflowApi,
} from '../../api';
import '../styles.css';
import {useMutationScope} from '../useMutationScope';

type Draft = {name: string; category?: string; description?: string; bpmnXml: string; formContract: WorkflowFormContract};
type CreateValues = {key?: string; name?: string; category?: string; description?: string};
type StartValues = {title?: string; businessKey?: string; ccUserIds?: string[]};
type CopyTarget = {definitionId: string; version: number; expectedRevision: number;
  historySession?: AbortController; editorSession?: AbortController};
type DiscardKind = 'editor' | 'create' | 'start';

type FormSchema = ComponentProps<typeof SForm>['schema'];

const WorkflowDesigner = lazy(() => import('@simplepoint/components/WorkflowDesigner'));
const ContractEditor = lazy(() => import('@simplepoint/components/WorkflowForms/ContractEditor'));
const VariablesEditor = lazy(() => import('@simplepoint/components/WorkflowForms/VariablesEditor'));

export default function Definitions() {
  const scope = useQueryScope();
  return <DefinitionsSession key={JSON.stringify(scope)}/>;
}

function DefinitionsSession() {
  const {t, ensure, locale} = useI18n();
  const queryClient = useQueryClient(), queryScope = useQueryScope();
  const resolveNumericLiteral = useCallback((expression: string) => queryClient.fetchQuery({
    ...scopedQueryOptions(queryScope, ['workflow', 'numeric-literal-preview', expression],
      ({signal}) => workflowApi.numericLiteralPreview(expression, signal)),
    staleTime: Infinity, gcTime: 120_000, retry: false,
  }), [queryClient, queryScope]);
  const resolvePriorityLiteral = useCallback(async (value: string) => {
    const preview = await queryClient.fetchQuery({
      ...scopedQueryOptions(queryScope, ['workflow', 'priority-literal-preview', value],
        ({signal}) => workflowApi.priorityLiteralPreview(value, signal)),
      staleTime: Infinity, gcTime: 120_000, retry: false,
    });
    return {value: preview.value ?? undefined, issue: preview.issue
      ? preview.issue === 'PRIORITY_TYPE' ? t('workflow.editor.priorityType', '优先级常量必须是数值或整数文本，不能使用布尔值')
        : t('workflow.editor.priorityInteger', '优先级文本必须能被引擎解析为 32 位整数，不能使用小数或指数文本') : undefined};
  }, [queryClient, queryScope, t]);
  const {message} = App.useApp(), mutation = useMutationScope();
  const [createOpen, setCreateOpen] = useState(false);
  const [createData, setCreateData] = useState<CreateValues>({});
  const [createDirty, setCreateDirty] = useState(false), [startDirty, setStartDirty] = useState(false);
  const [discardKind, setDiscardKind] = useState<DiscardKind>('editor');
  const [editor, setEditor] = useState<DefinitionDetail>();
  const [editorMode, setEditorMode] = useState<'metadata' | 'design'>('metadata');
  const designerRef = useRef<WorkflowDesignerHandle>(null);
  const metadataFormRef = useRef<SFormHandle>(null);
  const publishFormRef = useRef<SFormHandle>(null);
  const editorRequest = useRef<AbortController | undefined>(undefined);
  const historyRequest = useRef<AbortController | undefined>(undefined);
  const versionRequest = useRef<AbortController | undefined>(undefined);
  const mountedRef = useRef(true);
  const copyPendingRef = useRef(false);
  const [copyPending, setCopyPending] = useState(false);
  const [copyTarget, setCopyTarget] = useState<CopyTarget>(), copyTargetRef = useRef<CopyTarget | undefined>(undefined);
  const [discardOpen, setDiscardOpen] = useState(false), [writeError, setWriteError] = useState('');
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      editorRequest.current?.abort(); historyRequest.current?.abort(); versionRequest.current?.abort();
    };
  }, []);
  const [history, setHistory] = useState<DefinitionDetail>();
  const [versionPreview, setVersionPreview] = useState<VersionDetail>();
  const [versionLoading, setVersionLoading] = useState(false);
  const [startVersion, setStartVersion] = useState<number>();
  const [draft, setDraft] = useState<Draft>();
  const [validation, setValidation] = useState<WorkflowDesignerValidation>();
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishData, setPublishData] = useState<{comment?: string}>({});
  const [startTarget, setStartTarget] = useState<DefinitionSummary>();
  const [startData, setStartData] = useState<StartValues>({});
  const [variablesOpen, setVariablesOpen] = useState(false);
  const [variablesBlocked, setVariablesBlocked] = useState(false);
  const [startVariables, setStartVariables] = useState<Record<string, unknown>>({});
  const [variablesValid, setVariablesValid] = useState(false);
  const variablesEditorRef = useRef<WorkflowVariablesEditorHandle>(null);

  const overlay = !!editor || !!history || createOpen || publishOpen || !!startTarget || variablesOpen || !!copyTarget || discardOpen;
  const locked = overlay || saving || copyPending;
  usePageRetention(!locked && !dirty, locked || dirty);
  useEffect(() => { void ensure(['workflow']); }, [ensure, locale]);

  const definitions = useRemoteList<DefinitionSummary>('/workflow/definitions', mutation.selected);
  const permissionsQuery = useData(['workflow', 'definition-permissions'],
    ({signal}) => workflowApi.permissions(signal));
  const permissions = permissionsQuery.data;
  const load = definitions.refresh;
  const startForm = useData(['workflow', 'start-form', startTarget?.id, startVersion],
    ({signal}) => workflowApi.startForm(startTarget!.id, startVersion, signal),
    {enabled: Boolean(startTarget), retry: false, staleTime: Infinity, gcTime: 120_000,
      refetchOnWindowFocus: false, refetchOnReconnect: false});
  useEffect(() => {
    setStartVariables(startForm.data?.values ?? {});
  }, [startForm.data, startTarget?.id, startVersion]);
  const declarations = useMemo(() => expressionVariables(draft?.formContract ?? emptyFormContract()), [draft?.formContract]);
  const metadataData = useMemo(() => ({name: draft?.name, category: draft?.category, description: draft?.description}),
    [draft?.name, draft?.category, draft?.description]);

  const openEditor = useCallback(async (id: string, mode: 'metadata' | 'design' = 'metadata') => {
    if (!mutation.selected || savingRef.current || copyTargetRef.current) return;
    editorRequest.current?.abort();
    const request = new AbortController(); editorRequest.current = request;
    try {
      const detail = await workflowApi.definition(id, request.signal);
      if (request.signal.aborted || request !== editorRequest.current) return;
      setEditor(detail);
      setEditorMode(mode);
      setDraft({
        name: detail.definition.name,
        category: detail.definition.category,
        description: detail.definition.description,
        bpmnXml: detail.bpmnXml,
        formContract: detail.formContract ?? emptyFormContract(),
      });
      setValidation(undefined);
      setDirty(false);
      setWriteError(''); setDiscardOpen(false);
      setPublishData({});
    } catch {
      if (!request.signal.aborted) message.error(t('workflow.definition.loadFailed', '流程定义加载失败'));
    }
  }, [message, t, mutation.selected]);

  const openHistory = useCallback(async (id: string) => {
    if (!mutation.selected || savingRef.current || copyTargetRef.current) return;
    historyRequest.current?.abort(); versionRequest.current?.abort();
    const request = new AbortController(); historyRequest.current = request;
    try {
      const result = await workflowApi.definition(id, request.signal);
      if (request.signal.aborted || request !== historyRequest.current) return;
      setHistory(result);
      setVersionPreview(undefined);
    } catch {if (!request.signal.aborted) message.error(t('workflow.definition.loadFailed', '流程定义加载失败'));}
  }, [message, t, mutation.selected]);

  const abandonEditor = () => {
      if (savingRef.current || copyPendingRef.current || variablesBlocked) return;
      editorRequest.current?.abort();
      setEditor(undefined);
      setDraft(undefined);
      setDirty(false);
      setValidation(undefined);
      setDiscardOpen(false); setVariablesOpen(false); setPublishOpen(false); setPublishData({}); setWriteError('');
  };
  const closeEditor = () => {if (savingRef.current || copyPendingRef.current || variablesBlocked) return;
    if (dirty || publishData.comment) {setDiscardKind('editor'); setDiscardOpen(true);} else abandonEditor();};
  const abandonDraft = (kind: DiscardKind) => {
    if (savingRef.current || copyPendingRef.current) return;
    if (kind === 'editor') return abandonEditor();
    if (kind === 'create') {setCreateOpen(false); setCreateData({}); setCreateDirty(false);}
    else {setStartTarget(undefined); setStartData({}); setStartVariables({}); setStartDirty(false); setVariablesValid(false);}
    setDiscardOpen(false); setWriteError('');
  };
  const closeDraft = (kind: 'create' | 'start') => {
    if (savingRef.current) return;
    if (kind === 'create' ? createDirty : startDirty) {setDiscardKind(kind); setDiscardOpen(true);}
    else abandonDraft(kind);
  };

  const saveDraft = async (metadata?: CreateValues) => {
    if (!editor || !draft || !editor.permissions.canEdit || savingRef.current || copyPendingRef.current) return;
    if (validation && !validation.valid) {
      message.error(validation.errors[0] ?? t('workflow.definition.invalid', '流程结构不完整'));
      return;
    }
    const request = mutation.begin(); if (!request) return;
    const captured = metadata ? {...draft, name: metadata.name ?? '', category: metadata.category, description: metadata.description} : draft;
    savingRef.current = true; setSaving(true); setWriteError('');
    try {
      const bpmnXml = editorMode === 'design' && editor.permissions.canEdit
        ? await designerRef.current?.getXml() : draft.bpmnXml;
      if (!bpmnXml) throw new Error('designer-not-ready');
      if (!mutation.current(request.signal)) return;
      const detail = await workflowApi.saveDraft(editor.definition.id, {
        expectedRevision: editor.definition.draftRevision,
        ...captured,
        bpmnXml,
      }, request.signal);
      if (!mutation.current(request.signal)) return;
      setEditor(detail);
      setDraft({...captured, bpmnXml: detail.bpmnXml, formContract: detail.formContract});
      setDirty(false);
      await load();
      if (mutation.current(request.signal)) message.success(t('workflow.definition.saved', '流程草稿已保存'));
    } catch (error) {
      if (mutation.current(request.signal)) setWriteError(resolveApiErrorMessage(error, t('workflow.definition.saveFailed', '流程草稿保存失败，请刷新后重试')));
    } finally {
      savingRef.current = false; if (mutation.current()) setSaving(false);
    }
  };

  const publish = async (values: {comment?: string}) => {
    if (!editor?.permissions.canPublish || savingRef.current || copyPendingRef.current || dirty) return;
    const request = mutation.begin(); if (!request) return;
    savingRef.current = true; setSaving(true); setWriteError('');
    try {
      await workflowApi.publish(editor.definition.id, editor.definition.draftRevision,
        values.comment?.trim() || undefined, request.signal);
      const detail = await workflowApi.definition(editor.definition.id, request.signal);
      if (!mutation.current(request.signal)) return;
      setEditor(detail);
      setDraft(current => current ? {...current, bpmnXml: detail.bpmnXml} : current);
      setDirty(false);
      setPublishOpen(false);
      setPublishData({});
      await load();
      if (mutation.current(request.signal)) message.success(t('workflow.definition.published', '流程版本已发布'));
    } catch {
      if (mutation.current(request.signal)) setWriteError(t('workflow.definition.publishFailed', '流程发布失败，请检查模型和权限'));
    } finally {
      savingRef.current = false; if (mutation.current()) setSaving(false);
    }
  };

  const createDefinition = async (data: CreateValues) => {
    if (permissions?.canCreate !== true || savingRef.current || copyPendingRef.current) return;
    const request = mutation.begin(); if (!request) return;
    savingRef.current = true; setSaving(true); setWriteError('');
    try {
      const detail = await workflowApi.createDefinition({
        key: data.key?.trim() ?? '',
        name: data.name?.trim() ?? '',
        category: data.category?.trim() || undefined,
        description: data.description?.trim() || undefined,
      }, request.signal);
      if (!mutation.current(request.signal)) return;
      setCreateOpen(false);
      setCreateData({});
      setCreateDirty(false);
      await load();
      if (!mutation.current(request.signal)) return;
      setEditor(detail);
      setEditorMode('design');
      setDraft({name: detail.definition.name, category: detail.definition.category,
        description: detail.definition.description, bpmnXml: detail.bpmnXml,
        formContract: detail.formContract ?? emptyFormContract()});
      setDirty(false);
      message.success(t('workflow.definition.created', '流程定义已创建'));
    } catch {
      if (mutation.current(request.signal)) setWriteError(t('workflow.definition.createFailed', '流程定义创建失败'));
    } finally {
      savingRef.current = false; if (mutation.current()) setSaving(false);
    }
  };

  const startProcess = async (data: StartValues) => {
    if (!startTarget || permissions?.canStart !== true || !startForm.data || startForm.isFetching || savingRef.current || copyPendingRef.current) return;
    let currentVariables: Record<string, unknown>;
    try {
      if (!variablesEditorRef.current) return;
      currentVariables = variablesEditorRef.current.getValues(startForm.data);
    } catch (reason) {setWriteError((reason as Error).message); return;}
    const request = mutation.begin(); if (!request) return;
    savingRef.current = true; setSaving(true); setWriteError('');
    try {
      await workflowApi.start(startTarget.id, {
        version: startForm.data.definitionVersion,
        title: data.title?.trim() || startTarget.name,
        businessKey: data.businessKey?.trim() || undefined,
        variables: currentVariables,
        ccUserIds: data.ccUserIds,
      }, request.signal);
      if (!mutation.current(request.signal)) return;
      setStartTarget(undefined);
      setStartData({});
      setStartDirty(false);
      message.success(t('workflow.instance.started', '流程已发起'));
    } catch {
      if (mutation.current(request.signal)) setWriteError(t('workflow.instance.startFailed', '流程发起失败'));
    } finally {
      savingRef.current = false; if (mutation.current()) setSaving(false);
    }
  };

  const cancelCopy = () => {if (copyPendingRef.current) return; copyTargetRef.current = undefined;
    setCopyTarget(undefined); setWriteError('');};
  const submitCopy = async () => {
    const target = copyTargetRef.current;
    if (!target || copyPendingRef.current || savingRef.current || !history?.permissions.canCopyVersion
      || historyRequest.current !== target.historySession || editorRequest.current !== target.editorSession) return;
    const request = mutation.begin(); if (!request) return;
    copyPendingRef.current = true; setCopyPending(true); setWriteError('');
    try {
      const detail = await workflowApi.copyVersion(target.definitionId, target.version, target.expectedRevision, request.signal);
      if (!mutation.current(request.signal) || historyRequest.current !== target.historySession
        || editorRequest.current !== target.editorSession) return;
      copyTargetRef.current = undefined; setCopyTarget(undefined);
      setEditor(detail); setEditorMode('design'); setHistory(undefined); setVersionPreview(undefined);
      setPublishData({});
      setDraft({name: detail.definition.name, category: detail.definition.category, description: detail.definition.description,
        bpmnXml: detail.bpmnXml, formContract: detail.formContract ?? emptyFormContract()});
      setDirty(false); await load();
      if (mutation.current(request.signal)) message.success(t('workflow.version.copied', '历史版本已复制为草稿'));
    } catch (error) {if (mutation.current(request.signal)) setWriteError(resolveApiErrorMessage(error, t('workflow.version.copyFailed', '复制历史版本失败')));}
    finally {copyPendingRef.current = false; if (mutation.current()) setCopyPending(false);}
  };

  const status = (record: DefinitionSummary) => <Space size={4}>
    <Tag color={record.status === 'PUBLISHED' ? 'green' : 'default'}>
      {record.status === 'PUBLISHED' ? t('workflow.status.published', '已发布')
        : t('workflow.status.draft', '草稿')}
    </Tag>
    {record.unpublishedChanges && <Tag color="gold">
      {t('workflow.status.changed', '有未发布修改')}
    </Tag>}
  </Space>;

  const columns: ColumnsType<DefinitionSummary> = useMemo(() => [
    {title: t('workflow.definition.name', '流程名称'), dataIndex: 'name', key: 'name'},
    {title: t('workflow.definition.key', '流程标识'), dataIndex: 'key', key: 'key', width: 180},
    {title: t('workflow.definition.category', '分类'), dataIndex: 'category', key: 'category', width: 140,
      render: (value?: string) => value || '—'},
    {title: t('workflow.definition.version', '最新版本'), dataIndex: 'latestVersion',
      key: 'latestVersion', width: 110, render: (value: number, row) => value
        ? <Typography.Link disabled={locked || !mutation.selected} onClick={() => {void openHistory(row.id);}}>{`v${value}`}</Typography.Link> : '—'},
    {title: t('workflow.definition.status', '状态'), key: 'status', width: 210, render: (_, row) => status(row)},
    {title: t('workflow.updatedAt', '更新时间'), dataIndex: 'updatedAt', key: 'updatedAt', width: 190,
      render: (value: string) => new Date(value).toLocaleString(locale)},
    {title: t('workflow.actions', '操作'), key: 'actions', width: 260, fixed: 'right', render: (_, row) =>
      <TableActions selectedKeys={[row.id]} selectedRows={[row]} buttons={[
        {key: 'edit', sort: 10, text: t('workflow.action.editMetadata', '基础信息'), type: 'link',
          argumentMinSize: 1, argumentMaxSize: 1},
        {key: 'design', sort: 15, text: t('workflow.action.design', '流程设计'), type: 'link',
          argumentMinSize: 1, argumentMaxSize: 1},
        ...(permissions?.canStart && row.latestVersion > 0 ? [{key: 'start', sort: 20,
          text: t('workflow.action.start', '发起'), type: 'link' as const,
          argumentMinSize: 1, argumentMaxSize: 1}] : []),
      ]} isButtonDisabled={() => locked || !mutation.selected} onButtonEvents={{
        edit: () => { void openEditor(row.id); },
        design: () => {void openEditor(row.id, 'design');},
        start: () => {if (locked || savingRef.current) return; setWriteError(''); setStartDirty(false); setStartTarget(row); setStartVersion(row.latestVersion); setStartData({title: row.name}); },
      }}/>} as const,
  ], [locale, openEditor, openHistory, permissions?.canStart, t, locked, mutation.selected]);

  const versionColumns: ColumnsType<VersionSummary> = useMemo(() => [
    {title: t('workflow.definition.version', '版本'), dataIndex: 'version', key: 'version', width: 90,
      render: (value: number) => `v${value}`},
    {title: t('workflow.publish.comment', '发布说明'), dataIndex: 'comment', key: 'comment',
      render: (value?: string) => value || '—'},
    {title: t('workflow.publish.user', '发布人'), dataIndex: 'publishedBy', key: 'publishedBy', width: 150},
    {title: t('workflow.publish.time', '发布时间'), dataIndex: 'publishedAt', key: 'publishedAt', width: 190,
      render: (value: string) => new Date(value).toLocaleString(locale)},
    {title: t('workflow.actions', '操作'), key: 'actions', width: 260, render: (_, row) =>
      <TableActions selectedKeys={[row.id]} selectedRows={[row]}
        buttons={[
          {key: 'preview', sort: 5, text: t('workflow.action.preview', '查看版本'), type: 'link'},
          ...(history?.permissions.canCopyVersion ? [{key: 'copy', sort: 10,
            text: t('workflow.action.copyVersion', '复制为草稿'), type: 'link' as const,
            disabled: copyPending, loading: copyPending}] : []),
          ...(history?.permissions.canStart ? [{key: 'start', sort: 20,
            text: t('workflow.action.start', '发起'), type: 'link' as const}] : []),
        ]} isButtonDisabled={() => saving || copyPending || !!copyTarget || !mutation.selected}
        onButtonEvents={{preview: async () => {
          if (!history || savingRef.current || copyPendingRef.current || copyTargetRef.current) return;
          versionRequest.current?.abort();
          const request = new AbortController(); versionRequest.current = request;
          setVersionPreview(undefined);
          setVersionLoading(true);
          try {
            const result = await workflowApi.version(history.definition.id, row.version, request.signal);
            if (!request.signal.aborted && request === versionRequest.current) setVersionPreview(result);
          } catch {if (!request.signal.aborted) message.error(t('workflow.definition.loadFailed', '流程定义加载失败'));}
          finally {if (request === versionRequest.current) setVersionLoading(false);}
        }, start: () => {
          if (!history || savingRef.current || copyPendingRef.current || copyTargetRef.current) return;
          setWriteError('');
          setStartDirty(false);
          setStartTarget(history.definition); setStartVersion(row.version);
          setStartData({title: history.definition.name});
        }, copy: () => {
          if (!history?.permissions.canCopyVersion || copyPendingRef.current || savingRef.current || copyTargetRef.current) return;
          const target = {definitionId: history.definition.id, version: row.version, expectedRevision: history.definition.draftRevision,
            historySession: historyRequest.current, editorSession: editorRequest.current};
          copyTargetRef.current = target; setCopyTarget(target); setWriteError('');
        }}}/>},
  ], [history, locale, message, t, copyPending, copyTarget, saving, mutation.selected]);

  const createSchema = useMemo<FormSchema>(() => ({
    type: 'object', required: ['key', 'name'], properties: {
      key: {type: 'string', title: t('workflow.definition.key', '流程标识'), minLength: 3, maxLength: 80,
        pattern: '^[A-Za-z][A-Za-z0-9_-]{2,79}$'},
      name: {type: 'string', title: t('workflow.definition.name', '流程名称'), minLength: 1, maxLength: 160, pattern: '\\S'},
      category: {type: 'string', title: t('workflow.definition.category', '分类'), maxLength: 80},
      description: {type: 'string', title: t('workflow.definition.description', '说明'), maxLength: 1000},
    },
  }), [t]);

  const startSchema = useMemo<FormSchema>(() => ({type: 'object', properties: {
    title: {type: 'string', title: t('workflow.instance.title', '事项标题'), maxLength: 240},
    businessKey: {type: 'string', title: t('workflow.instance.businessKey', '业务标识'), maxLength: 160},
    ccUserIds: {type: 'array', title: t('workflow.instance.ccUsers', '抄送人'), maxItems: 50,
      uniqueItems: true, items: {type: 'string'}},
  }}), [t]);

  const metadataSchema = useMemo<FormSchema>(() => ({type: 'object', required: ['name'], properties: {
    name: {type: 'string', title: t('workflow.definition.name', '流程名称'), minLength: 1, maxLength: 160, pattern: '\\S'},
    category: {type: 'string', title: t('workflow.definition.category', '分类'), maxLength: 80},
    description: {type: 'string', title: t('workflow.definition.description', '说明'), maxLength: 1000},
  }}), [t]);

  const publishSchema = useMemo<FormSchema>(() => ({type: 'object', additionalProperties: false, properties: {
    comment: {type: 'string', title: t('workflow.publish.comment', '发布说明'), maxLength: 500},
  }}), [t]);

  return <section className="workflow-page">
      {definitions.error && <Alert type="error" showIcon message={t('workflow.loadFailed', '数据加载失败')}
        action={<TableActions selectedKeys={[]} selectedRows={[]}
          buttons={[{key: 'retry', sort: 10, text: t('workflow.retry', '重试'), argumentMinSize: 0, argumentMaxSize: 0}]}
          onButtonEvents={{retry: definitions.retry}}/>}/>}
      <DataTable {...definitions.tableProps} storageKey="workflow.definitions" columns={definitions.columns(columns)}
        toolbarExtra={<Space wrap><TableActions selectedKeys={[]} selectedRows={[]}
          buttons={permissions?.canCreate ? [{key: 'add', sort: 10, text: t('workflow.action.create', '新建流程'), icon: 'PlusOutlined',
            type: 'primary', disabled: locked, argumentMinSize: 0, argumentMaxSize: 0}] : []}
          onButtonEvents={{add: () => {if (!locked) {setWriteError(''); setCreateOpen(true);}}}}/>
        </Space>}
        scroll={{x: 1100}} pagination={definitions.tableProps.pagination && {...definitions.tableProps.pagination, disabled: locked}}/>

    <Modal title={t('workflow.action.create', '新建流程')} open={createOpen && mutation.selected} footer={null}
      destroyOnHidden={!createOpen} closable={!saving} maskClosable={!saving} keyboard={!saving}
      onCancel={() => closeDraft('create')}>
      <SForm schema={createSchema} uiSchema={{description: {'ui:widget': 'textarea'}}}
        formData={createData} submitLoading={saving} disabled={saving}
        onChange={(event: {formData?: CreateValues}) => {setCreateData(event.formData ?? {}); setCreateDirty(true);}}
        onSubmit={(event: {formData?: CreateValues}) => void createDefinition(event.formData ?? {})}/>
      {writeError && <Alert type="error" showIcon title={writeError}/>}
    </Modal>

    <Drawer rootClassName="workflow-definition-editor" width="min(720px, 92vw)"
      placement={editorMode === 'design' ? 'bottom' : 'right'} height="min(90vh, 1000px)"
      open={Boolean(editor && draft) && mutation.selected} maskClosable={false} closable={!saving && !copyPending && !variablesBlocked} keyboard={!saving && !copyPending && !variablesBlocked}
      destroyOnHidden={!editor} title={editor ? `${editor.definition.name} · ${editor.definition.key}` : ''}
      onClose={closeEditor}
      extra={editor && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        ...(editorMode === 'design' ? [{key: 'variables', sort: 5,
          text: t('workflow.variable.configure', '配置变量'), disabled: saving || copyPending,
          argumentMinSize: 0, argumentMaxSize: 0}] : []),
        ...(editor.permissions.canEdit ? [{key: 'save', sort: 10, text: t('workflow.action.save', '保存草稿'),
          type: 'primary' as const, loading: saving, disabled: saving || copyPending, argumentMinSize: 0, argumentMaxSize: 0}] : []),
        ...(editorMode === 'design' && editor.permissions.canPublish ? [{key: 'publish', sort: 20, text: t('workflow.action.publish', '发布'),
          disabled: dirty || saving || copyPending, loading: saving, argumentMinSize: 0, argumentMaxSize: 0}] : []),
      ]} onButtonEvents={{variables: () => setVariablesOpen(true),
        save: () => {if (editorMode === 'metadata') metadataFormRef.current?.submit(); else void saveDraft();},
        publish: () => setPublishOpen(true)}}/>}>
      {editor && draft && <>
        {writeError && !publishOpen && <Alert type="error" showIcon title={writeError}/>}
        {validation?.warnings.length ? <Alert type="warning" showIcon closable
          message={validation.warnings.join('；')} style={{marginBottom: 12}}/> : null}
        {editorMode === 'metadata' ? <div className="workflow-definition-editor__meta">
          <SForm ref={metadataFormRef} hideSubmit schema={metadataSchema} uiSchema={{description: {'ui:widget': 'textarea'}}}
            formData={metadataData}
            disabled={saving || copyPending || !editor.permissions.canEdit}
            onChange={(event: {formData?: CreateValues}) => {
              const value = event.formData;
              if (!value) return;
              setDraft(current => current ? {...current, name: value.name ?? '',
                category: value.category, description: value.description} : current);
              setDirty(true);
            }} onSubmit={event => void saveDraft(event.formData)}/>
        </div> : <Suspense fallback={<Spin size="large"/>}>
        {dirty && <Alert type="info" showIcon title={t('workflow.publish.saveFirst', '请先保存当前设计，再发布新版本')}/>}
        <WorkflowDesigner ref={designerRef} xml={draft.bpmnXml} readOnly={!editor.permissions.canEdit} disabled={saving || copyPending}
          resolveNumericLiteral={resolveNumericLiteral}
          resolvePriorityLiteral={resolvePriorityLiteral}
          height="100%" variables={declarations} formContract={draft.formContract}
          onFormContractChange={formContract => {
            setDraft(current => current ? {...current, formContract} : current); setDirty(true);
          }}
          onDirtyChange={value => { if (value) setDirty(true); }}
          onChange={(bpmnXml, result) => {setDraft(current => current ? {...current, bpmnXml} : current);
            setValidation(result);}}/>
        </Suspense>}
      </>}
    </Drawer>

    <Drawer title={t('workflow.variable.configure', '配置变量')} open={variablesOpen && Boolean(draft) && mutation.selected}
      rootClassName="workflow-variable-contract" width="min(960px, 94vw)" destroyOnHidden={!variablesOpen || !draft}
      closable={!variablesBlocked} maskClosable={!variablesBlocked} keyboard={!variablesBlocked}
      onClose={() => {if (!variablesBlocked) setVariablesOpen(false);}}>
      {draft && <Suspense fallback={<Spin/>}><ContractEditor value={draft.formContract}
        onBlockedChange={setVariablesBlocked}
        disabled={!editor?.permissions.canEdit || saving}
        onChange={formContract => {setDraft(current => current ? {...current, formContract} : current); setDirty(true);}}/></Suspense>}
    </Drawer>

    <Drawer rootClassName="workflow-definition-history" title={history ? `${history.definition.name} · ${t('workflow.versions.title', '发布历史')}` : ''}
      open={Boolean(history) && mutation.selected} placement="bottom" height="min(88vh, 1000px)"
      destroyOnHidden={!history}
      closable={!copyPending && !copyTarget} maskClosable={!copyPending && !copyTarget} keyboard={!copyPending && !copyTarget}
      onClose={() => {if (copyPendingRef.current || copyTargetRef.current) return; historyRequest.current?.abort(); versionRequest.current?.abort();
        setHistory(undefined); setVersionPreview(undefined); setVersionLoading(false);}}>
      {history && <>
        <DataTable rowKey="id" storageKey="workflow.definition.versions" columns={versionColumns}
          dataSource={history.versions} pagination={{pageSize: 10, showSizeChanger: true}}
          query={{mode: 'local', scope: 'complete', contextKey: history.definition.id, fields: {
            version: {type: 'number'}, comment: {type: 'string'}, publishedAt: {type: 'date-time'},
          }}}/>
        {versionLoading && <Spin/>}
        {versionPreview && <div className="workflow-version-preview">
          <Typography.Text strong>{`${versionPreview.name ?? history.definition.key} · v${versionPreview.version.version}`}</Typography.Text>
          {versionPreview.category && <Typography.Paragraph type="secondary">{versionPreview.category}</Typography.Paragraph>}
          {versionPreview.description && <Typography.Paragraph>{versionPreview.description}</Typography.Paragraph>}
          <Suspense fallback={<Spin/>}><WorkflowDesigner key={versionPreview.version.id}
            resolveNumericLiteral={resolveNumericLiteral}
            resolvePriorityLiteral={resolvePriorityLiteral}
            xml={versionPreview.bpmnXml} variables={expressionVariables(versionPreview.formContract)}
            formContract={versionPreview.formContract} readOnly height={520}/></Suspense>
        </div>}
      </>}
    </Drawer>

    <Modal title={t('workflow.action.publish', '发布流程')} open={publishOpen && mutation.selected}
      destroyOnHidden={!publishOpen} closable={!saving} maskClosable={!saving} keyboard={!saving}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 1, text: t('workflow.dialog.cancel', '取消'), disabled: saving},
        {key: 'publish', sort: 2, text: t('workflow.action.publish', '发布'), type: 'primary', loading: saving, disabled: saving},
      ]} onButtonEvents={{cancel: () => {if (!savingRef.current) setPublishOpen(false);}, publish: () => publishFormRef.current?.submit()}}/>}
      onCancel={() => {if (!savingRef.current) setPublishOpen(false);}}>
      <Typography.Paragraph type="secondary">
        {t('workflow.publish.hint', '发布后将创建不可变版本；运行中的实例不会切换版本。')}
      </Typography.Paragraph>
      <SForm ref={publishFormRef} hideSubmit schema={publishSchema} uiSchema={{comment: {'ui:widget': 'textarea'}}}
        formData={publishData} disabled={saving} onChange={event => setPublishData(event.formData ?? {})}
        onSubmit={event => void publish(event.formData ?? {})}/>
      {writeError && <Alert type="error" showIcon title={writeError}/>}
    </Modal>

    <Modal title={t('workflow.action.start', '发起流程')} open={Boolean(startTarget) && mutation.selected} footer={null}
      destroyOnHidden={!startTarget} closable={!saving} maskClosable={!saving} keyboard={!saving}
      onCancel={() => closeDraft('start')}>
      {writeError && <Alert type="error" showIcon title={writeError}/>}
      {startVersion && <Typography.Paragraph>{`${startTarget?.name} · v${startVersion}`}</Typography.Paragraph>}
      {startForm.isFetching ? <Spin/> : startForm.isError ? <Alert type="error" showIcon
        title={t('workflow.form.loadFailed', '流程表单加载失败，请关闭后重试')}/> : startForm.data && <>
      <Suspense fallback={<Spin/>}><VariablesEditor ref={variablesEditorRef} key={`${startTarget?.id}:${startForm.data.definitionVersion}`}
        form={startForm.data} stage="start" value={startVariables} onChange={setStartVariables}
        onValidityChange={setVariablesValid} onDirtyChange={setStartDirty} disabled={saving}/></Suspense>
      <SForm schema={startSchema} uiSchema={{ccUserIds: {'ui:widget': 'TenantDirectory',
        'ui:options': {kind: 'users', multiple: true}}}}
        formData={startData} submitLoading={saving} disabled={saving} submitDisabled={!variablesValid}
        onChange={(event: {formData?: StartValues}) => {setStartData(event.formData ?? {}); setStartDirty(true);}}
        onSubmit={(event: {formData?: StartValues}) => void startProcess(event.formData ?? {})}/></>}
    </Modal>
    <Modal wrapClassName="workflow-copy-confirmation" open={!!copyTarget && mutation.selected}
      destroyOnHidden={!copyTarget}
      title={t('workflow.version.copyConfirm', '将此历史版本复制为当前草稿？')} closable={!copyPending} maskClosable={!copyPending} keyboard={!copyPending}
      onCancel={cancelCopy} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 1, text: t('workflow.dialog.cancel', '取消'), disabled: copyPending},
        {key: 'copy', sort: 2, text: t('workflow.action.copyVersion', '复制为草稿'), type: 'primary', loading: copyPending, disabled: copyPending},
      ]} onButtonEvents={{cancel: cancelCopy, copy: () => void submitCopy()}}/>}>
      <Typography.Paragraph>{t('workflow.version.copyBoundary', '当前草稿将被覆盖，已发布版本及运行中的实例不变。')}</Typography.Paragraph>
      {copyTarget && <Typography.Text>{`v${copyTarget.version}`}</Typography.Text>}
      {writeError && <Alert type="error" showIcon title={writeError}/>}
    </Modal>
    <Modal wrapClassName="workflow-discard-confirmation" open={discardOpen && mutation.selected}
      destroyOnHidden={!discardOpen}
      title={t('workflow.unsaved.title', '放弃未保存的修改？')} onCancel={() => setDiscardOpen(false)}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', sort: 1, text: t('workflow.unsaved.continue', '继续编辑')},
        {key: 'discard', sort: 2, text: t('workflow.unsaved.discard', '放弃修改'), danger: true, type: 'primary'},
      ]} onButtonEvents={{continue: () => setDiscardOpen(false), discard: () => abandonDraft(discardKind)}}/>}>
      {t('workflow.unsaved.content', '关闭后，本次流程草稿修改不会保留。')}
    </Modal>
  </section>;
}
