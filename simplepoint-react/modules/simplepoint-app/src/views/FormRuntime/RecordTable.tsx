import RuntimeForm, {
  editableValues,
  detailPayload,
  editableFieldIds,
  runtimeIdentity,
  recordPage,
  recordPageQuery,
  recordEditorSchema,
  type ApplicationFormRuntimeView,
  type FormRecordMutation,
  type FormRecord,
  type FormRecordPage,
  type RecordRow,
  type RecordState,
} from '@simplepoint/components/FormRuntime';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {isHttpError, request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useQueryClient} from '@tanstack/react-query';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {App, Drawer} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import SubmissionDialog from '@simplepoint/components/ApplicationWorkflow/SubmissionDialog';
import SubmissionHistory from '@simplepoint/components/ApplicationWorkflow/SubmissionHistory';
import ResourceDrawer from '@simplepoint/components/ResourceDrawer';
import TableActions from '@simplepoint/components/Table/Actions';

type Props = {available?: boolean; descriptor: ApplicationFormRuntimeView; onInteractionLockChange?: (locked: boolean) => void};

/** Standard SimpleTable record manager over the page-bound APP record facade. */
export default function RecordTable({descriptor, available = true, onInteractionLockChange}: Props) {
  const {t} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope(descriptor.pageId);
  const queries = useQueryClient(), scope = useQueryScope();
  const [submission, setSubmission] = useState<FormRecord>();
  const [detail, setDetail] = useState<FormRecord>();
  const [history, setHistory] = useState(false), [historyLocked, setHistoryLocked] = useState(false);
  const [tableLocked, setTableLocked] = useState(false);
  const interaction = useCallback((locked: boolean) => setTableLocked(locked), []);
  const writeState = useRef<RecordState>('DRAFT');
  useEffect(() => {onInteractionLockChange?.(tableLocked || operation.busy || !!submission || !!detail || history);},
    [tableLocked, operation.busy, submission, detail, history, onInteractionLockChange]);
  const [pending, setPending] = useState<{method: 'POST' | 'PUT'; url: string; body: {
    runtime: ReturnType<typeof runtimeIdentity>; idempotencyKey: string; expectedVersion?: number;
    state: RecordState; values: Record<string, unknown>;
  }}>();
  const deletes = useRef(new Map<string, string>());
  const baseUrl = useMemo(() =>
    `/app/runtime/applications/${encodeURIComponent(descriptor.applicationId)}`
      + `/pages/${encodeURIComponent(descriptor.pageId)}/records`,
  [descriptor.applicationId, descriptor.pageId]);
  const loadPage = async (page: SimpleTablePageRequest) => recordPage(
    await post<FormRecordPage>(
      baseUrl + '/query',
      recordPageQuery(page),
      {signal: page.signal, notifyOnError: false},
    ),
  );

  const openRecord = async (row: RecordRow | undefined, kind: 'view' | 'submit') => {
    if (!available || !row || tableLocked || submission || detail || history) return;
    const request = operation.begin(); if (!request) return;
    try {
      const record = await get<FormRecord>(`${baseUrl}/${encodeURIComponent(row.id)}`, undefined, {signal: request.signal, notifyOnError: false});
      if (operation.current(request.signal)) {if (kind === 'view') setDetail(record); else setSubmission(record);}
    } catch (cause) {
      if (operation.current(request.signal)) message.error(resolveApiErrorMessage(cause, t('app.runtime.loadFailed', '应用页面加载失败')));
    } finally {operation.finish(request);}
  };
  return <><SimpleTable<RecordRow>
    name={`app.runtime.records.${descriptor.pageId}`}
    storageKey={`app.runtime.records.${descriptor.pageId}`}
    baseUrl={baseUrl}
    i18nNamespaces={['app-workbench']}
    confirmDiscard
    drawerWidth="min(1100px, 94vw)"
    isButtonDisabled={(button, _keys, rows) => !available || operation.busy || !!submission || !!detail || history
      || button.key === 'startProcess' && rows[0]?.recordState !== 'DRAFT'}
    customButtonEvents={{startProcess: (_keys, rows) => void openRecord(rows[0], 'submit'),
      view: (_keys, rows) => void openRecord(rows[0], 'view'), history: () => setHistory(true)}}
    formReadOnly={Boolean(pending)}
    formCloseDisabled={Boolean(pending)}
    onInteractionLockChange={interaction}
    loadPage={loadPage}
    loadEditingRecord={async (row, signal) => {
      const record = await get<FormRecord>(`${baseUrl}/${encodeURIComponent(row.id)}`, undefined, {signal, notifyOnError: false});
      return {...record.values, id: record.id, version: record.version, recordState: record.state};
    }}
    renderForm={editor => <><RuntimeForm document={descriptor.document} sceneId={descriptor.sceneId}
      value={editor.formData} recordState={editor.editingRecord?.recordState}
      disabled={editor.disabled || editor.readOnly || !available} submitting={editor.submitting}
      onChange={editor.onChange} onAction={async (action, values) => {
        writeState.current = action === 'SUBMIT' ? 'SUBMITTED' : editor.editingRecord?.recordState ?? 'DRAFT';
        await editor.onSubmit(values);
      }}/>
      {editor.readOnly && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('app.retry', '重试'), disabled: editor.disabled},
      ]} onButtonEvents={{retry: () => void editor.onSubmit(editor.formData)}}/>}
    </>}
    formSchemaTransform={(schema, current) => recordEditorSchema(schema, descriptor, current)}
    onSubmit={async (action, formData, current, context) => {
      if (!available && !pending) throw new Error(t('app.runtime.versionChanged', '表单已更新，请加载新版本后继续'));
      const record = current as RecordRow | null;
      const intent = pending ?? {method: action === 'add' ? 'POST' as const : 'PUT' as const,
        url: action === 'add' ? baseUrl : `${baseUrl}/${encodeURIComponent(record!.id)}`,
        body: {runtime: runtimeIdentity(descriptor), idempotencyKey: crypto.randomUUID(),
          expectedVersion: action === 'edit' ? record!.version : undefined,
          state: writeState.current,
          values: structuredClone(detailPayload(descriptor.document, editableValues(formData ?? {},
            editableFieldIds(descriptor, undefined, record?.recordState), record), record?.recordState))}};
      try {
        const options = {signal: context.signal, notifyOnError: false};
        const result = intent.method === 'POST'
          ? await post<FormRecordMutation>(intent.url, intent.body, options)
          : await put<FormRecordMutation>(intent.url, intent.body, options);
        setPending(undefined);
        return result;
      } catch (error) {
        const uncertain = !isHttpError(error) || error.status === 0 || error.status >= 500;
        setPending(uncertain ? intent : undefined);
        throw error;
      }
    }}
    afterSubmit={({action}) => {
      message.success(action === 'add'
        ? t('app.runtime.record.created', '记录已新增')
        : t('app.runtime.record.updated', '记录已更新'));
    }}
    deleteRows={async (rows, signal) => {
      const record = rows[0];
      if (!record) throw new Error('A selected record is required');
      const identity = `${record.id}:${record.version}`;
      const key = deletes.current.get(identity) ?? crypto.randomUUID();
      deletes.current.set(identity, key);
      await request<void>(
        `${baseUrl}/${encodeURIComponent(record.id)}`
          + `?expectedVersion=${encodeURIComponent(record.version)}`
          + `&formId=${encodeURIComponent(descriptor.formId)}&formVersion=${descriptor.formVersion}`
          + `&idempotencyKey=${encodeURIComponent(key)}`,
        {method: 'DELETE', signal, notifyOnError: false},
      );
      deletes.current.delete(identity);
    }}
  />
    <Drawer rootClassName="app-record-detail" title={t('app.runtime.record.detail', '详情')}
      width="min(1100px, 94vw)" open={!!detail && operation.selected} destroyOnHidden={!detail} onClose={() => setDetail(undefined)}>
      {detail && <RuntimeForm document={descriptor.document} sceneId={descriptor.sceneId} value={detail.values}
        systemValues={detail.systemValues} recordState={detail.state} readOnly/>}
    </Drawer>
    <ResourceDrawer rootClassName="app-record-history" title={t('app.runtime.approvalHistory', '审批记录')}
      open={history} defaultHeight={520} locked={historyLocked} onClose={() => setHistory(false)}>
      {history && <SubmissionHistory applicationId={descriptor.applicationId} pageId={descriptor.pageId} onInteractionLockChange={setHistoryLocked}/>}
    </ResourceDrawer>
    {submission && <SubmissionDialog applicationId={descriptor.applicationId} pageId={descriptor.pageId}
      expectedForm={descriptor} initialRecord={submission} initialValues={submission.values}
      onAccepted={() => void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl])})}
      onClose={() => setSubmission(undefined)}/>}
  </>;
}
