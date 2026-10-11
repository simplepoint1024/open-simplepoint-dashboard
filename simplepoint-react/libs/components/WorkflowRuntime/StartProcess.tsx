import {lazy, Suspense, useEffect, useRef, useState} from 'react';
import {Alert, App, Modal, Spin, Typography} from 'antd';
import {get, useData} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {workflowApi, type WorkflowResubmission} from '@simplepoint/shared/workflow/api';
import EntitySelect, {type EntitySelectSource} from '../EntitySelect';
import SForm from '../SForm/Lazy';
import TableActions from '../Table/Actions';
import type {WorkflowVariablesEditorHandle} from '../WorkflowForms/VariablesEditor';
import {useMutationScope} from './useMutationScope';
import {useWorkflowRefresh} from './useWorkflowRefresh';

const VariablesEditor = lazy(() => import('../WorkflowForms/VariablesEditor'));
const startableSource: EntitySelectSource = {
  key: 'workflow.published-startable', pageSize: 20,
  searchParams: (keyword, page, size) => ({page, size, sort: 'name,asc', ...(keyword.trim() ? {name: `like:${keyword.trim()}`} : {})}),
  option: row => typeof row.id === 'string' && typeof row.name === 'string' ? {value: row.id, label: row.name} : undefined,
  resolve: async (ids, signal) => (await get<Array<{id: string; name: string}>>('/workflow/matters/startable/options', {ids}, {signal}))
    .map(row => ({value: row.id, label: row.name})),
};
type Values = {title?: string; businessKey?: string; ccUserIds?: string[]};

/** Version-pinned starter form, shared by personal matters and future linked resubmissions. */
export default function StartProcess({onClose, resubmission}: {onClose: () => void; resubmission?: WorkflowResubmission}) {
  const {t} = useI18n(), {message} = App.useApp();
  const mutation = useMutationScope(), refresh = useWorkflowRefresh();
  const [selection, setSelection] = useState<{id: string; name: string} | undefined>(() => resubmission
    ? {id: resubmission.source.definitionId, name: resubmission.source.definitionName} : undefined);
  const [values, setValues] = useState<Values>(() => resubmission ? {
    title: resubmission.source.title, businessKey: resubmission.source.businessKey, ccUserIds: resubmission.ccUserIds,
  } : {}), [variables, setVariables] = useState<Record<string, unknown>>({});
  const [valid, setValid] = useState(false), [dirty, setDirty] = useState(false), [busy, setBusy] = useState(false);
  const [discard, setDiscard] = useState<'close' | 'selection'>();
  const [error, setError] = useState(''), flight = useRef(false), editor = useRef<WorkflowVariablesEditorHandle>(null);
  const form = useData(['workflow', 'start-form', 'personal', selection?.id],
    ({signal}) => workflowApi.startForm(selection!.id, undefined, signal),
    {enabled: Boolean(selection) && !resubmission, retry: false, staleTime: 0, gcTime: 0, refetchOnWindowFocus: false, refetchOnReconnect: false});
  const runtimeForm = resubmission?.form ?? form.data;
  const fetching = !resubmission && form.isFetching, formError = !resubmission && form.error;
  const submitLabel = resubmission ? t('workflow.action.resubmit', '修改后重新发起') : t('workflow.action.start', '发起流程');
  useEffect(() => {setVariables(runtimeForm?.values ?? {});}, [runtimeForm]);
  const clearSelection = () => {setSelection(undefined); setValues({}); setVariables({}); setDirty(false); setValid(false); setError('');};
  const close = () => {if (!flight.current) {if (dirty) setDiscard('close'); else onClose();}};
  const submit = async (data: Values) => {
    if (!selection || !runtimeForm || fetching || !editor.current || flight.current) return;
    let submitted: Record<string, unknown>;
    try {submitted = editor.current.getValues(runtimeForm);} catch (cause) {setError((cause as Error).message); return;}
    const request = mutation.begin(); if (!request) return;
    flight.current = true; setBusy(true); setError('');
    try {
      const command = {version: runtimeForm.definitionVersion,
        title: data.title?.trim() || selection.name, businessKey: data.businessKey?.trim() || undefined,
        ccUserIds: data.ccUserIds, variables: submitted,
      };
      if (resubmission) await workflowApi.resubmit(resubmission.source.id, command, request.signal);
      else await workflowApi.start(selection.id, command, request.signal);
      if (!mutation.current(request.signal)) return;
      await refresh();
      if (mutation.current(request.signal)) {message.success(t('workflow.instance.started', '流程已发起')); onClose();}
    } catch (cause) {
      if (mutation.current(request.signal)) setError(resolveApiErrorMessage(cause, t('workflow.instance.startFailed', '流程发起失败')));
    } finally {flight.current = false; if (mutation.current()) setBusy(false);}
  };
  return <>
    <Modal wrapClassName="workflow-process-start" open={mutation.selected} title={submitLabel} width={700} footer={null}
      closable={!busy} maskClosable={!busy} keyboard={!busy} onCancel={close}>
      {error && <Alert showIcon type="error" title={error}/>}
      {resubmission && <Alert showIcon type="info" title={t('workflow.resubmit.hint', '将按原流程版本创建关联的新事项。原审批结果和处理历史保持不变。')}/>}
      {!selection ? <EntitySelect endpoint="/workflow/matters/startable" source={startableSource}
        ariaLabel={t('workflow.personal.chooseProcess', '选择流程')} placeholder={t('workflow.personal.chooseProcess', '选择流程')}
        onChange={(id, option) => {if (id) setSelection({id, name: String(option?.label ?? '')});}}/> : <>
        <Typography.Paragraph>{selection.name}{runtimeForm ? ` · v${runtimeForm.definitionVersion}` : ''}</Typography.Paragraph>
        {!resubmission && <TableActions selectedKeys={[]} selectedRows={[]} isButtonDisabled={() => busy} buttons={[
          {key: 'change', sort: 1, text: t('workflow.personal.changeProcess', '重新选择流程')},
        ]} onButtonEvents={{change: () => {if (dirty) setDiscard('selection'); else clearSelection();}}}/>}
        {fetching ? <Spin/> : formError ? <Alert showIcon type="error" title={t('workflow.form.loadFailed', '流程表单加载失败，请关闭后重试')}
          action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1, text: t('workflow.retry', '重试')}]}
            onButtonEvents={{retry: () => void form.refetch()}}/>}/> : runtimeForm && <>
          <Suspense fallback={<Spin/>}><VariablesEditor ref={editor} key={`${selection.id}:${runtimeForm.definitionVersion}`}
            form={runtimeForm} stage="start" value={variables} onChange={next => {setVariables(next); setDirty(true);}}
            onDirtyChange={setDirty} onValidityChange={setValid} disabled={busy}/></Suspense>
          <SForm schema={{type: 'object', properties: {
            title: {type: 'string', title: t('workflow.instance.title', '事项标题'), maxLength: 240},
            businessKey: {type: 'string', title: t('workflow.instance.businessKey', '业务标识'), maxLength: 160},
            ccUserIds: {type: 'array', title: t('workflow.instance.ccUsers', '抄送人'), items: {type: 'string'}, uniqueItems: true, maxItems: 50},
          }}} uiSchema={{ccUserIds: {'ui:widget': 'TenantDirectory', 'ui:options': {kind: 'users', multiple: true}},
            'ui:submitButtonOptions': {submitText: submitLabel}}}
            formData={values} disabled={busy} submitLoading={busy} submitDisabled={!valid}
            onChange={(event: {formData?: Values}) => {setValues(event.formData ?? {}); setDirty(true);}}
            onSubmit={(event: {formData?: Values}) => void submit(event.formData ?? {})}/>
        </>}
      </>}
    </Modal>
    <Modal wrapClassName="workflow-start-discard" open={Boolean(discard) && mutation.selected} title={t('workflow.unsaved.title', '放弃未保存的修改？')}
      onCancel={() => setDiscard(undefined)} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', sort: 1, text: t('workflow.unsaved.continue', '继续编辑')},
        {key: 'discard', sort: 2, text: t('workflow.unsaved.discard', '放弃修改'), danger: true},
      ]} onButtonEvents={{continue: () => setDiscard(undefined), discard: () => {
        if (discard === 'close') onClose(); else clearSelection(); setDiscard(undefined);
      }}}/>}/>
  </>;
}
