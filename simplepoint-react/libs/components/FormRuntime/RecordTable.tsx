import type {MappingIntent} from './referenceMapping/model';
import {localizeFormDocument} from './localization';
import type {ApplicationFormRuntimeView} from './contracts';
import {detailPayload} from '../FormComponents/Detail/payload';
import {
  editableValues,
  editableFieldIds,
  runtimeIdentity,
  recordPage,
  recordPageQuery,
  recordEditorSchema,
  type FormRecordMutation,
  type FormRecord,
  type FormRecordPage,
  type RecordRow,
  type RecordState,
} from './recordTableModel';
import SimpleTable from '../SimpleTable';
import type {SimpleTablePageRequest} from '../SimpleTable/types';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {appendQueryParams, isHttpError, request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useQueryClient} from '@tanstack/react-query';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {App, Modal} from 'antd';
import {lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type ReactNode} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {asyncComponent} from '../AsyncComponent';
import ResourceDrawer from '../ResourceDrawer';
import {LoadingPlaceholder} from '../LoadingFeedback';
import TableActions from '../Table/Actions';
import {recordCards} from './recordCards';
import {useRecordReferences} from './useRecordReferences';
import {referenceColumns} from './referenceColumns';
import {ReferenceRuntimeContext} from '../FormComponents/Reference/RuntimeContext';
import PageSection from '@simplepoint/shared/page/Section';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import ReferenceInspector from './ReferenceInspector';
import {canUnlinkRecord, type InverseUnlink} from './inverseUnlink';

const InverseRecords = lazy(() => import('./InverseRecords'));
const CollectionRecords = lazy(() => import('./CollectionRecords'));
const PublishedFormRuntime = asyncComponent(() => import('./Published'));
const SubmissionDialog = asyncComponent(() => import('../ApplicationWorkflow/SubmissionDialog'),
  (content, {onClose}) => <PendingSubmission onClose={onClose}>{content}</PendingSubmission>);
const SubmissionHistory = asyncComponent(() => import('../ApplicationWorkflow/SubmissionHistory'));
const RelatedUnlinkDialog = asyncComponent(() => import('./RelatedUnlinkDialog'),
  (content, {onClose}) => <PendingUnlink onClose={onClose}>{content}</PendingUnlink>);

function PendingSubmission({children, onClose}: {children: ReactNode; onClose: () => void}) {
  const {t} = useI18n();
  const selected = usePageSelected();
  return <Modal open={selected} title={t('app.submission.start', '提交审批')} footer={null}
    maskClosable={false} onCancel={onClose}>{children}</Modal>;
}

function PendingUnlink({children, onClose}: {children: ReactNode; onClose: () => void}) {
  const {t} = useI18n();
  return <ResourceDrawer open title={t('app.runtime.relation.unlink', '解除关联')}
    placement="right" defaultWidth={960} onClose={onClose}>{children}</ResourceDrawer>;
}

export type RecordTableEndpoint = {baseUrl: string; params?: Record<string, string>;
  /** Stable shared preference identity, independent of the current parent record. */
  preferenceKey: string; submission?: {baseUrl: string; params?: Record<string, string>}; unlink?: InverseUnlink;
  initialValues?: Record<string, unknown>; fixedReferenceField?: string};

type Props = {recordId?: string; showRelations?: boolean; endpoint?: RecordTableEndpoint; available?: boolean; descriptor: ApplicationFormRuntimeView; onInteractionLockChange?: (locked: boolean) => void;
  onReadAccessDenied?: (error: unknown) => void};

/** Standard SimpleTable record manager over the page-bound APP record facade. */
export default function RecordTable(props: Props) {
  const scope = useQueryScope();
  const {descriptor, endpoint} = props;
  const owner = JSON.stringify([scope, descriptor.applicationId, descriptor.pageId, descriptor.formId,
    descriptor.formVersion, endpoint?.baseUrl, endpoint?.params, props.recordId]);
  return <RecordTableSession key={owner} {...props}/>;
}

function RecordTableSession({descriptor: sourceDescriptor, recordId, available = true, onInteractionLockChange, onReadAccessDenied, endpoint, showRelations = true}: Props) {
  const {t, locale} = useI18n();
  const descriptor = useMemo(() => ({...sourceDescriptor, document: localizeFormDocument(sourceDescriptor.document, locale)}), [sourceDescriptor, locale]);
  const references = useRecordReferences(descriptor);
  const {message} = App.useApp();
  const endpointIdentity = JSON.stringify([descriptor.pageId, endpoint?.baseUrl, endpoint?.params, recordId]);
  const operation = useOperationScope(endpointIdentity);
  const queries = useQueryClient(), scope = useQueryScope();
  const [submission, setSubmission] = useState<FormRecord>();
  const [unlink, setUnlink] = useState<FormRecord>();
  const [detail, setDetail] = useState<FormRecord>();
  const [recordContent, setRecordContent] = useState<FormRecord>();
  const [inverseLocked, setInverseLocked] = useState(false);
  const [collectionLocked, setCollectionLocked] = useState(false);
  const detailLocked = inverseLocked || collectionLocked;
  const [history, setHistory] = useState<string>(), [historyLocked, setHistoryLocked] = useState(false);
  const [tableLocked, setTableLocked] = useState(false);
  const listActionsDisabled = !available || operation.busy || tableLocked || detailLocked || !!submission || !!detail || !!history || !!unlink;
  const columns = useMemo(() => referenceColumns(descriptor.document, listActionsDisabled), [descriptor.document, listActionsDisabled]);
  const interaction = useCallback((locked: boolean) => setTableLocked(locked), []);
  const writeMappings = useRef<MappingIntent | undefined>(undefined);
  const writeState = useRef<RecordState>('DRAFT');
  useEffect(() => {onInteractionLockChange?.(tableLocked || detailLocked || operation.busy || !!submission || !!detail || !!history || !!unlink);},
    [tableLocked, detailLocked, operation.busy, submission, detail, history, unlink, onInteractionLockChange]);
  const [pending, setPending] = useState<{method: 'POST' | 'PUT'; url: string; body: {
    runtime: ReturnType<typeof runtimeIdentity>; idempotencyKey: string; expectedVersion?: number;
    state: RecordState; referenceMappings?: MappingIntent; values: Record<string, unknown>;
  }}>();
  const deletes = useRef(new Map<string, string>());
  const baseUrl = useMemo(() => endpoint?.baseUrl ??
    `/app/runtime/applications/${encodeURIComponent(descriptor.applicationId)}`
      + `/pages/${encodeURIComponent(descriptor.pageId)}/records`,
  [descriptor.applicationId, descriptor.pageId, endpoint?.baseUrl]);
  const recordUrl = (suffix = '') => appendQueryParams(baseUrl + suffix, endpoint?.params);
  const loadPage = async (page: SimpleTablePageRequest) => {
    if (recordId) {
      const record = await get<FormRecord>(recordUrl(`/${encodeURIComponent(recordId)}`), undefined,
        {signal: page.signal, notifyOnError: false});
      if (!page.signal?.aborted) setRecordContent(record);
      return recordPage({page: 0, size: page.size, total: 1, records: [record]});
    }
    return recordPage(await post<FormRecordPage>(
      recordUrl('/query'),
      recordPageQuery(page),
      {signal: page.signal, notifyOnError: false},
    ));
  };

  const openRecord = async (row: RecordRow | undefined, kind: 'view' | 'submit' | 'unlink') => {
    if (!available || !row || tableLocked || detailLocked || submission || detail || !!history || unlink) return;
    const request = operation.beginRead(); if (!request) return;
    try {
      const record = await get<FormRecord>(recordUrl(`/${encodeURIComponent(row.id)}`), undefined, {signal: request.signal, notifyOnError: false});
      if (operation.current(request.signal)) {if (kind === 'view') setDetail(record); else if (kind === 'unlink') setUnlink(record); else setSubmission(record);}
    } catch (cause) {
      if (operation.current(request.signal)) message.error(resolveApiErrorMessage(cause, t('app.runtime.loadFailed', '应用页面加载失败')));
    } finally {operation.finish(request);}
  };
  return <ReferenceInspector ownerKey={JSON.stringify([descriptor.applicationId, descriptor.pageId, descriptor.formId, descriptor.formVersion, endpointIdentity])} enabled={available}>
    <ReferenceRuntimeContext.Provider value={available ? references : undefined}><SimpleTable<RecordRow>
    name={endpoint?.preferenceKey ?? `app.runtime.records.${descriptor.pageId}`}
    storageKey={endpoint?.preferenceKey ?? `app.runtime.records.${descriptor.pageId}`}
    baseUrl={baseUrl}
    schemaParams={endpoint?.params}
    queryContext={endpointIdentity}
    initialValues={endpoint?.initialValues}
    i18nNamespaces={['app-workbench']}
    cards={recordCards(descriptor.document)}
    columnOverrides={columns}
    confirmDiscard
    drawerWidth="min(1100px, 94vw)"
    isButtonDisabled={(button, _keys, rows) => !available || detailLocked || operation.busy || !!submission || !!detail || !!history || !!unlink
      || button.key === 'unlink' && !canUnlinkRecord(endpoint?.unlink, rows[0]?.recordState)
      || button.key === 'startProcess' && rows[0]?.recordState !== 'DRAFT'}
    customButtonEvents={{unlink: (_keys, rows) => {if (canUnlinkRecord(endpoint?.unlink, rows[0]?.recordState)) void openRecord(rows[0], 'unlink');}, startProcess: (_keys, rows) => void openRecord(rows[0], 'submit'),
      view: (_keys, rows) => void openRecord(rows[0], 'view'), history: (_keys, rows) => {if (rows[0]) setHistory(rows[0].id);}}}
    formReadOnly={Boolean(pending)}
    formCloseDisabled={Boolean(pending)}
    onInteractionLockChange={interaction}
    onDrawerOpenChange={open => {if (!open) {writeState.current = 'DRAFT'; writeMappings.current = undefined;}}}
    onReadAccessDenied={onReadAccessDenied}
    loadPage={loadPage}
    renderContent={recordId ? content => <div style={{flex: 1, minHeight: 0, overflow: 'auto'}}>
      {content.loading && <LoadingPlaceholder/>}
      <PageSection selected={available && !content.loading && !content.failed && !tableLocked && !submission && !history && !unlink}>
        <div hidden={content.loading || content.failed}>
          <TableActions {...content} selectedKeys={content.data?.content.map(row => row.id) ?? []}
            selectedRows={content.data?.content ?? []}
            buttons={content.buttons?.filter(button => button.key !== 'add' && button.key !== 'view')}/>
          {recordContent && <>
            <PublishedFormRuntime descriptor={descriptor} record={recordContent} value={recordContent.values}
              systemValues={recordContent.systemValues} systemLabels={recordContent.systemLabels}
              disabled={listActionsDisabled} readOnly/>
            {showRelations && <Suspense fallback={null}><CollectionRecords parent={descriptor} recordId={recordContent.id} onInteractionLockChange={setCollectionLocked}/>
              <InverseRecords parent={descriptor} recordId={recordContent.id} onInteractionLockChange={setInverseLocked}/></Suspense>}
          </>}
        </div>
      </PageSection>
    </div> : undefined}
    loadEditingRecord={async (row, signal) => {
      const record = await get<FormRecord>(recordUrl(`/${encodeURIComponent(row.id)}`), undefined, {signal, notifyOnError: false});
      return {...record.values, id: record.id, version: record.version, recordState: record.state};
    }}
    renderForm={editor => <><PublishedFormRuntime descriptor={descriptor}
      record={editor.editingRecord ? {values: editor.editingRecord, id: editor.editingRecord.id, version: editor.editingRecord.version, state: editor.editingRecord.recordState} : undefined}
      fixedReferenceField={editor.editingRecord ? undefined : endpoint?.fixedReferenceField}
      value={editor.formData}
      disabled={editor.disabled || editor.readOnly || !available} submitting={editor.submitting}
      onChange={editor.onChange} onAction={async (action, values, mappings) => {
        writeMappings.current = mappings;
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
        url: recordUrl(action === 'add' ? '' : `/${encodeURIComponent(record!.id)}`),
        body: {runtime: runtimeIdentity(descriptor), idempotencyKey: crypto.randomUUID(),
          expectedVersion: action === 'edit' ? record!.version : undefined,
          state: writeState.current,
          ...(writeMappings.current ? {referenceMappings: structuredClone(writeMappings.current)} : {}),
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
      const records = rows.map(row => ({id: row.id, expectedVersion: row.version}))
        .sort((a, b) => a.id.localeCompare(b.id));
      const runtime = runtimeIdentity(descriptor);
      const identity = JSON.stringify([scope, recordUrl(), runtime, records]);
      const idempotencyKey = deletes.current.get(identity) ?? crypto.randomUUID();
      deletes.current.set(identity, idempotencyKey);
      const batch = records.length > 1;
      const url = batch ? recordUrl() : appendQueryParams(recordUrl(`/${encodeURIComponent(record.id)}`), {
          expectedVersion: String(record.version), formId: descriptor.formId,
          formVersion: String(descriptor.formVersion), idempotencyKey,
      });
      try {
        await request<void>(url, {method: 'DELETE', signal, notifyOnError: false,
          ...(batch ? {json: {records, runtime, idempotencyKey}} : {})});
        deletes.current.delete(identity);
      } catch (error) {
        if (isHttpError(error) && error.status > 0 && error.status < 500) deletes.current.delete(identity);
        throw error;
      }
    }}
  />
    <ResourceDrawer rootClassName="app-record-detail" title={t('app.runtime.record.detail', '详情')}
      placement="right" defaultWidth={1100} open={!!detail && operation.selected} locked={detailLocked}
      onClose={() => {if (!detailLocked) setDetail(undefined);}}>
      <PageSection selected={!!detail && operation.selected && available}>
        {detail && <><PublishedFormRuntime descriptor={descriptor} record={detail} value={detail.values}
          systemValues={detail.systemValues} systemLabels={detail.systemLabels} readOnly/>
          {showRelations && <Suspense fallback={null}><CollectionRecords parent={descriptor} recordId={detail.id} onInteractionLockChange={setCollectionLocked}/>
            <InverseRecords parent={descriptor} recordId={detail.id} onInteractionLockChange={setInverseLocked}/></Suspense>}
        </>}
      </PageSection>
    </ResourceDrawer>
    <ResourceDrawer rootClassName="app-record-history" title={t('app.runtime.approvalHistory', '审批记录')}
      open={!!history} defaultHeight={520} locked={historyLocked} onClose={() => setHistory(undefined)}>
      {history && <SubmissionHistory key={history} recordId={history} applicationId={descriptor.applicationId} pageId={descriptor.pageId} onInteractionLockChange={setHistoryLocked}/>}
    </ResourceDrawer>
    {unlink && endpoint?.unlink && <RelatedUnlinkDialog descriptor={descriptor} record={unlink} relation={endpoint.unlink}
      url={recordUrl(`/${encodeURIComponent(unlink.id)}/unlink`)} onClose={() => setUnlink(undefined)}
      onCompleted={() => {setUnlink(undefined); message.success(t('app.runtime.relation.unlinked', '已解除关联'));
        void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl])});}}/>}
    {submission && <SubmissionDialog endpoint={endpoint?.submission} applicationId={descriptor.applicationId} pageId={descriptor.pageId}
      expectedForm={descriptor} initialRecord={submission} initialValues={submission.values}
      onAccepted={() => void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl])})}
      onClose={() => {setSubmission(undefined); void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl])});}}/>}
  </ReferenceRuntimeContext.Provider></ReferenceInspector>;
}
