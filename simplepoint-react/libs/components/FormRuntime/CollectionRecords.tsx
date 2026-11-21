import {Alert, App, Modal, Skeleton, Tabs} from 'antd';
import {useEffect, useState} from 'react';
import {useQueryClient} from '@tanstack/react-query';
import {get, post} from '@simplepoint/shared/api/methods';
import {appendQueryParams, isHttpError, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {resolveLocalizedText, type LocalizedText} from '@simplepoint/shared/i18n/localizedText';
import {createIcon} from '@simplepoint/shared/types/icon';
import SimpleTable from '../SimpleTable';
import TableActions from '../Table/Actions';
import {recordPage, recordPageQuery, type FormRecordPage, type RecordRow} from './recordTableModel';
import type {ApplicationFormRuntimeView} from './contracts';

type CollectionView = {id: string; inverse: boolean; label: string; labelLocalization?: LocalizedText;
  canLink: boolean; canUnlink: boolean};
type Observation = {anchorVersion: number; edges: {memberId: string; memberVersion: number; edgeVersion: number; linked: boolean}[]};
type Intent = Observation & {idempotencyKey: string; link: boolean};

type Props = {parent: ApplicationFormRuntimeView; recordId: string; onInteractionLockChange: (locked: boolean) => void};

export default function CollectionRecords(props: Props) {
  const queryScope = useQueryScope();
  return <CollectionSession key={JSON.stringify([queryScope, props.parent.applicationId, props.parent.pageId,
    props.parent.formId, props.parent.formVersion, props.recordId])} {...props}/>;
}

function CollectionSession({parent, recordId, onInteractionLockChange}: Props) {
  const {t, locale} = useI18n(), pageSelected = usePageSelected();
  const records = `/app/runtime/applications/${encodeURIComponent(parent.applicationId)}/pages/${encodeURIComponent(parent.pageId)}/records`;
  const catalog = `${records}/${encodeURIComponent(recordId)}/collections`;
  const params = {formId: parent.formId, formVersion: String(parent.formVersion)};
  const [selected, setSelected] = useState<string>();
  const [locked, setLocked] = useState(false);
  useEffect(() => {
    onInteractionLockChange(locked);
    return () => onInteractionLockChange(false);
  }, [locked, onInteractionLockChange]);
  const query = useOwnedData([catalog, params], ({signal}) =>
    get<CollectionView[]>(catalog, params, {signal, notifyOnError: false}),
  {retry: false, staleTime: 0, gcTime: 0, enabled: !locked, refetchOnWindowFocus: false});
  if (query.isError) return <Alert type="error"
    title={resolveApiErrorMessage(query.error, t('app.runtime.relatedLoadFailed', '关联数据加载失败'))}
    action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('table.retry', '重试'), disabled: !pageSelected},
    ]} onButtonEvents={{retry: () => {if (pageSelected) void query.refetch();}}}/>}/>;
  if (!query.data) return pageSelected ? <Skeleton active paragraph={{rows: 1}}/> : null;
  const key = (view: CollectionView) => `${view.inverse}:${view.id}`;
  const current = query.data.find(view => key(view) === selected) ?? query.data[0];
  if (!current) return null;
  return <section style={{minWidth: 0, marginTop: 16}}>
    <Tabs size="small" activeKey={key(current)} onChange={setSelected} items={query.data.map(view => ({
      key: key(view), icon: createIcon('LinkOutlined'), disabled: locked && key(view) !== key(current),
      label: resolveLocalizedText(view.labelLocalization, locale, undefined, 'TENANT', view.label).text,
    }))}/>
    <CollectionTable key={key(current)} view={current} records={records}
      baseUrl={`${catalog}/${encodeURIComponent(current.id)}`}
      params={{...params, inverse: String(current.inverse)}} available={!query.isFetching || locked}
      lock={setLocked}/>
  </section>;
}

function CollectionTable({view, records, baseUrl, params, available, lock}: {
  view: CollectionView; records: string; baseUrl: string; params: Record<string, string>;
  available: boolean; lock: (locked: boolean) => void;
}) {
  const {t} = useI18n(), {message} = App.useApp();
  const [candidates, setCandidates] = useState(false);
  const [intent, setIntent] = useState<Intent>();
  const [uncertain, setUncertain] = useState(false);
  const operation = useOperationScope(baseUrl), queries = useQueryClient(), scope = useQueryScope();
  const allowed = operation.selected && available && !operation.busy && !intent;
  const refresh = () => void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [records])});
  const reloadSection = () => void queries.invalidateQueries({queryKey: scopedQueryKey(scope, [baseUrl])});
  useEffect(() => {
    lock(operation.busy || !!intent);
    return () => lock(false);
  }, [operation.busy, intent, lock]);

  const prepare = async (rows: RecordRow[], link: boolean) => {
    if (!allowed || !rows.length) return;
    const request = operation.beginRead(); if (!request) return;
    try {
      const ids = rows.map(row => row.id);
      const observation = await post<Observation>(appendQueryParams(`${baseUrl}/observe`, params),
        view.inverse ? {memberId: ids[0], link} : {members: ids, link}, {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return;
      if (rows.some(row => !observation.edges.some(edge => row.id === edge.memberId
        && row.version === edge.memberVersion && edge.linked !== link))) {
        reloadSection();
        message.warning(t('app.relation.collection.changed', '关联记录已变化，请刷新后重新选择。'));
        return;
      }
      setIntent({...observation, link, idempotencyKey: crypto.randomUUID()});
    } catch (failure) {
      if (operation.current(request.signal)) message.error(resolveApiErrorMessage(failure,
        t('app.relation.collection.failed', '关联操作失败')));
    } finally {operation.finish(request);}
  };

  const save = async () => {
    if (!intent) return;
    const request = operation.begin(); if (!request) return;
    try {
      await post<void>(appendQueryParams(`${baseUrl}/mutations`, params), intent,
        {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal)) return;
      setIntent(undefined);
      setUncertain(false);
      setCandidates(false);
      reloadSection();
      refresh();
      message.success(intent.link ? t('app.relation.collection.linked', '已添加关联')
        : t('app.runtime.relation.unlinked', '已解除关联'));
    } catch (failure) {
      if (!operation.current(request.signal)) return;
      const retry = !isHttpError(failure) || failure.status === 0 || failure.status >= 500;
      setUncertain(retry);
      if (!retry) {setIntent(undefined); reloadSection(); refresh();}
      message.error(resolveApiErrorMessage(failure, t('app.relation.collection.failed', '关联操作失败')));
    } finally {operation.finish(request);}
  };

  const back = () => {if (allowed) setCandidates(false);};
  const close = () => {if (!uncertain && !operation.isBusy()) setIntent(undefined);};
  return <>
    {candidates && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'back', sort: 0, icon: 'ArrowLeftOutlined', text: t('app.relation.collection.back', '返回关联列表'), disabled: !allowed},
    ]} onButtonEvents={{back}}/>}
    <div style={{height: 'clamp(320px, 52vh, 600px)', minWidth: 0, minHeight: 0}}>
      <SimpleTable<RecordRow> key={String(candidates)} name={`app.collection.${view.inverse}.${view.id}.${candidates}`}
        baseUrl={baseUrl} schemaParams={{...params, candidates: String(candidates)}}
        i18nNamespaces={['app-workbench']}
        isButtonDisabled={() => !allowed}
        rowSelection={{isRowSelectable: row => !view.inverse || candidates || row.recordState === 'DRAFT'}}
        customButtonEvents={{
          chooseMembers: () => {if (allowed && view.canLink) setCandidates(true);},
          linkSelected: (_keys, rows) => {if (view.canLink) void prepare(rows, true);},
          unlinkMembers: (_keys, rows) => {if (view.canUnlink) void prepare(rows, false);},
        }}
        loadPage={async page => recordPage(await post<FormRecordPage>(
          appendQueryParams(`${baseUrl}/query`, {...params, candidates: String(candidates)}),
          recordPageQuery(page), {signal: page.signal, notifyOnError: false}))}/>
    </div>
    <Modal open={operation.selected && !!intent} maskClosable={false} closable={!uncertain && !operation.busy}
      title={intent?.link ? t('app.relation.collection.confirmLink', '添加所选关联？')
        : t('app.relation.collection.confirmUnlink', '解除所选关联？')}
      onCancel={close} footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, icon: 'CloseOutlined', text: t('app.designer.cancelData', '取消'), disabled: uncertain || operation.busy},
        {key: 'confirm', sort: 1, icon: 'CheckOutlined', type: 'primary',
          text: uncertain ? t('table.retry', '重试') : t('app.relation.collection.confirm', '确认'),
          disabled: operation.busy},
      ]} onButtonEvents={{cancel: close, confirm: () => void save()}}/>}>
      {uncertain && <Alert type="warning" title={t('app.relation.collection.uncertain', '尚未收到操作结果，请重试以确认。')}/>}
      {!uncertain && intent && !intent.link && t('app.relation.collection.unlinkHint', '解除后，两端的业务记录都会保留。')}
    </Modal>
  </>;
}
