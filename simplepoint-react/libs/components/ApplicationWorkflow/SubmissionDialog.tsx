import {Alert, App, Modal, Spin} from 'antd';
import {useEffect, useLayoutEffect, useRef, useState} from 'react';
import {get, post} from '@simplepoint/shared/api/methods';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {recordMutation, type FormRecord} from '../FormRuntime';
import PublishedFormRuntime from '../FormRuntime/Published';
import SForm, {type SFormHandle} from '../SForm';
import EntitySelect, {type EntitySelectSource} from '../EntitySelect';
import TableActions from '../Table/Actions';
import {runtimeSchema} from '../WorkflowForms/model';
import {missingSubmissionFields} from './submissionValidation';
import {submissionUrl, type Preparation, type Submission} from './types';

type Definition = {id: string; version: number; name?: string};
type Props = {applicationId: string; pageId: string; definition?: Definition; previousSubmissionId?: string;
  expectedForm?: {formId: string; formVersion: number};
  initialRecord?: FormRecord; initialValues?: Record<string, unknown>;
  onClose: () => void; onAccepted?: (submission: Submission) => void};
const definitions: EntitySelectSource = {
  key: 'app.bound-processes', searchParams: (keyword, page, size) => ({keyword, page, size}),
  option: row => typeof row.id === 'string' && typeof row.name === 'string' && typeof row.version === 'number'
    ? {value: `${row.id}:${row.version}`, label: `${row.name} · v${row.version}`} : undefined,
};

/** APP accepts one immutable intent before DNA or Workflow is called. Uncertain requests retain their exact body. */
export default function SubmissionDialog(props: Props) {
  const {t} = useI18n(), selected = usePageSelected(), {modal} = App.useApp();
  const [definition, setDefinition] = useState(props.definition);
  const url = submissionUrl(props.applicationId, props.pageId);
  const choices = useOwnedData(['app', 'submission-definitions', url], ({signal}) =>
    get<{content: Definition[]; totalElements: number}>(url + '/definitions', {page: 0, size: 2}, {signal, notifyOnError: false}),
    {enabled: !definition, retry: false, staleTime: 0});
  useEffect(() => {
    if (!definition && !choices.isFetching && !choices.isError && choices.data?.totalElements === 1) setDefinition(choices.data.content[0]);
  }, [choices.data, choices.isFetching, choices.isError, definition]);
  const preparation = useOwnedData(['app', 'submission-preparation', url, definition?.id, definition?.version, props.previousSubmissionId, props.initialRecord?.id, props.initialRecord?.version],
    ({signal}) => get<Preparation>(url + '/preparation', {definitionId: definition!.id, definitionVersion: definition!.version,
      ...(props.previousSubmissionId ? {previousSubmissionId: props.previousSubmissionId} : {})}, {signal, notifyOnError: false}),
    {enabled: !!definition, retry: false, staleTime: Infinity, refetchOnWindowFocus: false, refetchOnReconnect: false});
  const [locked, setLocked] = useState(false);
  const [dirty, setDirty] = useState(false);
  const close = () => {
    if (locked) return;
    if (!dirty) props.onClose();
    else modal.confirm({title: t('app.draft.discardTitle', '放弃未保存的修改？'), onOk: props.onClose});
  };
  const changed = !!props.expectedForm && !!preparation.data && (props.expectedForm.formId !== preparation.data.form.formId
    || props.expectedForm.formVersion !== preparation.data.form.formVersion);
  return <Modal wrapClassName="app-workflow-submission" title={t('app.submission.start', '提交审批')} width="min(1050px, 94vw)"
    open={selected} footer={null} maskClosable={false} closable={!locked} keyboard={!locked} onCancel={close} destroyOnHidden>
    {!definition && choices.isFetching && <Spin/>}
    {!definition && choices.isError && <Alert type="error" showIcon title={resolveApiErrorMessage(choices.error, t('app.submission.prepareFailed', '提交准备失败'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('app.retry', '重试')}]}
        onButtonEvents={{retry: () => void choices.refetch()}}/>}/>}
    {!definition && !choices.isFetching && !choices.isError && choices.data?.totalElements === 0
      && <Alert type="warning" showIcon title={t('app.submission.noProcess', '当前表单没有可用的已发布审批流程，请联系管理员配置。')}/>}
    {!definition && !choices.isFetching && !choices.isError && (choices.data?.totalElements ?? 0) > 1 && <EntitySelect endpoint={url + '/definitions'} source={definitions}
      ariaLabel={t('app.submission.chooseProcess', '选择审批流程')} placeholder={t('app.submission.chooseProcess', '选择审批流程')}
      onChange={id => {if (id) {const [key, version] = id.split(':'); setDefinition({id: key, version: Number(version)});}}}/>}
    {definition && preparation.isPending && <Spin/>}
    {preparation.error && <Alert type="error" showIcon title={resolveApiErrorMessage(preparation.error, t('app.submission.prepareFailed', '提交准备失败'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('app.retry', '重试')}]}
        onButtonEvents={{retry: () => void preparation.refetch()}}/>}/>}
    {changed && <Alert showIcon type="warning" title={t('app.runtime.versionChanged', '表单已更新，请加载新版本后继续')}/>}
    {preparation.data && !preparation.isFetching && !preparation.error && !changed && <SubmissionEditor key={JSON.stringify([url, definition, props.previousSubmissionId, props.initialRecord?.id, props.initialRecord?.version])}
      {...props} preparation={preparation.data} url={url} onLock={setLocked} onDirty={setDirty}/>}
  </Modal>;
}

function SubmissionEditor({preparation, url, onLock, onDirty, initialRecord, initialValues, onAccepted, onClose}: Props & {
  preparation: Preparation; url: string; onLock: (locked: boolean) => void; onDirty: (dirty: boolean) => void;
}) {
  const {t} = useI18n(), operation = useOperationScope(url), selected = usePageSelected();
  const record = preparation.copyOnResubmission ? undefined : preparation.record ?? initialRecord;
  const confirmRecord = !!initialRecord && !preparation.previousSubmissionId;
  const [values, setValues] = useState(() => {
    const seed = structuredClone(initialValues ?? preparation.record?.values ?? record?.values ?? {});
    if (preparation.copyOnResubmission) for (const detail of preparation.form.document.details ?? []) {
      const rows = seed[detail.detailId];
      if (Array.isArray(rows)) seed[detail.detailId] = rows.map(row => ({values: row.values}));
    }
    return seed;
  });
  const [inputs, setInputs] = useState({title: preparation.title, ccUserIds: preparation.ccUserIds, variables: preparation.values});
  const [error, setError] = useState(''), [pending, setPending] = useState<Record<string, unknown>>();
  const [accepted, setAccepted] = useState<Submission>();
  const feedback = useRef<HTMLDivElement>(null);
  const [feedbackAttempt, setFeedbackAttempt] = useState(0);
  const status = useOwnedData(['app', 'submission-status', url, accepted?.id], ({signal}) =>
    get<Submission>(`${url}/${encodeURIComponent(accepted!.id)}`, undefined, {signal, notifyOnError: false}),
    {enabled: !!accepted && !['SUCCEEDED', 'FAILED'].includes(accepted.status), retry: false,
      refetchInterval: query => query.state.error || ['SUCCEEDED', 'FAILED'].includes(query.state.data?.status ?? '') ? false : 1500});
  useEffect(() => {
    if (status.data && status.data.id === accepted?.id && (status.data.updatedAt !== accepted.updatedAt
      || status.data.status !== accepted.status || status.data.processInstanceId !== accepted.processInstanceId)) {
      setAccepted(status.data); onAccepted?.(status.data);
    }
  }, [status.data, accepted, onAccepted]);
  useLayoutEffect(() => {
    if (selected && (error || accepted || status.isError)) {
      feedback.current?.focus({preventScroll: true});
      feedback.current?.scrollIntoView({block: 'nearest'});
    }
  }, [selected, error, accepted, status.isError, feedbackAttempt]);
  const [dirty, setDirty] = useState(false);
  const draft = useRef(values);
  const form = useRef<SFormHandle>(null), meta = useRef<SFormHandle>(null);
  usePageRetention(!dirty && !pending && !operation.busy, dirty || !!pending || operation.busy);
  useEffect(() => {onLock(operation.busy || !!pending);}, [operation.busy, pending, onLock]);
  useEffect(() => {onDirty(dirty);}, [dirty, onDirty]);
  const send = async (body: Record<string, unknown>) => {
    const request = operation.begin(); if (!request) return;
    onLock(true); setError('');
    try {
      const result = await post<Submission>(url, body, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return;
      setAccepted(result); setPending(undefined); setDirty(false); onAccepted?.(result);
    } catch (cause) {
      if (!operation.current(request.signal)) return;
      const uncertain = !isHttpError(cause) || cause.status === 0 || cause.status >= 500;
      setPending(uncertain ? body : undefined);
      setError(resolveApiErrorMessage(cause, t('app.submission.failed', '提交未完成')));
    } finally {operation.finish(request);}
  };
  const frozen = operation.busy || !!pending || !!accepted;
  const submit = (data: typeof inputs) => {
    if (frozen || !operation.isSelected()) return;
    const mutation = recordMutation(preparation.form, record, draft.current, 'SUBMITTED', crypto.randomUUID());
    const body = structuredClone({runtime: mutation.body.runtime, idempotencyKey: mutation.body.idempotencyKey,
      expectedVersion: record?.version, values: confirmRecord ? {} : mutation.body.values, recordId: mutation.recordId,
      definitionId: preparation.definitionId, definitionVersion: preparation.definitionVersion,
      previousSubmissionId: preparation.previousSubmissionId, title: data.title, ccUserIds: data.ccUserIds, variables: data.variables});
    void send(body);
  };
  const dispatch = (handle: SFormHandle | null) => {
    if (!handle?.submit()) setError(t('app.submission.formNotReady', '表单尚未就绪，请等待附件上传或当前操作完成后重试。'));
  };
  return <div style={{display: 'grid', gap: 16}}>
    {preparation.copyOnResubmission && <Alert type="info" showIcon title="重新申请将生成修订记录，原审批数据将保留"/>}
    {confirmRecord && <Alert type="info" showIcon title={t('app.submission.readOnlyConfirmation', '请核对已保存的业务数据。需要修改时，请返回列表编辑后再提交审批。')}/>}
    <PublishedFormRuntime descriptor={preparation.form} record={record} previewState="SUBMITTED" formRef={form} externalSubmit
      value={values} readOnly={confirmRecord} disabled={frozen} submitting={operation.busy}
      onChange={next => {if (!frozen) {setValues(next); setDirty(true);}}}
      onSubmit={next => {draft.current = next; dispatch(meta.current);}}/>
    <SForm ref={meta} hideSubmit disabled={frozen} schema={{type: 'object', required: ['title'], properties: {
      title: {type: 'string', title: t('workflow.instance.title', '事项标题'), minLength: 1, maxLength: 240},
      ccUserIds: {type: 'array', title: t('workflow.instance.ccUsers', '抄送人'), items: {type: 'string'}, uniqueItems: true, maxItems: 50},
      variables: {...runtimeSchema(preparation.variables, 'start'), title: t('app.submission.processInputs', '审批信息')},
    }}} uiSchema={{ccUserIds: {'ui:widget': 'UserSelect', 'ui:options': {multiple: true}}}} formData={inputs}
      onChange={event => {if (!frozen) {setInputs(event.formData as typeof inputs); setDirty(true);}}}
      onSubmit={event => submit(event.formData as typeof inputs)}/>
    {(status.isError || error || accepted) && <div ref={feedback} tabIndex={-1} style={{display: 'grid', gap: 16}}>
    {status.isError && <Alert type="warning" showIcon title={t('app.submission.statusUnavailable', '暂时无法刷新审批状态，请重试查询；无需重复提交。')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'refresh', sort: 0, icon: 'ReloadOutlined', text: t('app.retry', '重试')}]}
        onButtonEvents={{refresh: () => void status.refetch()}}/>}/>}
    {error && <Alert type="error" showIcon title={error} description={pending ? t('app.submission.uncertain', '提交结果正在确认，请重试原请求。') : undefined}/>}
    {accepted && <Alert type={accepted.status === 'FAILED' ? 'error' : accepted.processInstanceId ? 'success' : 'info'} showIcon
      title={accepted.status === 'FAILED' ? t('app.submission.status.FAILED', '提交失败')
        : accepted.processInstanceId ? t('app.submission.started', '审批已发起') : t('app.submission.accepted', '提交已受理，可在提交记录中查看进度')}
      description={accepted.status === 'FAILED'
        ? t('app.error.RECORD_OPERATION_REJECTED', '数据不符合约束，本次提交已终止。请检查重复编号或必填内容后重新提交。') : accepted.id}/>}
    </div>}
    <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      ...(accepted ? [{key: 'close', sort: 0, icon: 'CheckOutlined', text: t('app.submission.done', '完成')}] : [
        {key: 'submit', sort: 0, icon: pending ? 'ReloadOutlined' : 'SendOutlined', type: 'primary' as const,
          disabled: operation.busy, text: pending ? t('app.retry', '重试') : t('app.submission.start', '提交审批')},
      ]),
    ]} onButtonEvents={{close: onClose, submit: () => {
      if (operation.isBusy()) return;
      setFeedbackAttempt(value => value + 1);
      if (pending) void send(pending);
      else {
        setError('');
        if (confirmRecord && record) {
          try {
            const missing = missingSubmissionFields(preparation.form, record);
            if (missing.length) {setError(t('app.submission.missingFields', '请返回编辑并补充必填内容：{fields}', {fields: missing.join('、')})); return;}
          } catch {
            setError(t('app.runtime.unsupported', '表单包含当前版本不支持的组件，请联系设计者检查。')); return;
          }
        }
        dispatch(confirmRecord ? meta.current : form.current);
      }
    }}}/>
  </div>;
}
