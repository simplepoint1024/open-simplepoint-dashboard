import {Alert, Skeleton, Tabs} from 'antd';
import {useCallback, useEffect, useState} from 'react';
import {get} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import PageSection from '@simplepoint/shared/page/Section';
import {TableOutlined} from '@simplepoint/shared/types/icon';
import {resolveLocalizedText} from '@simplepoint/shared/i18n/localizedText';
import TableActions from '../Table/Actions';
import RecordTable from './RecordTable';
import type {ApplicationFormRuntimeView} from './contracts';
import {inverseCatalog, inverseEndpoint, inverseEditor, type InverseRuntimeView, type InverseViewSummary} from './inverseModel';

type Props = {parent: ApplicationFormRuntimeView; recordId: string; onInteractionLockChange?: (locked: boolean) => void};

/** One bounded related list beneath a saved record; it uses the same CRUD controller and editor. */
export default function InverseRecords({parent, recordId, onInteractionLockChange}: Props) {
  const {locale} = useI18n(), active = usePageActive();
  const [selected, setSelected] = useState<string>(), [locked, setLocked] = useState(false);
  const catalog = inverseCatalog(parent, recordId);
  const query = useOwnedData([catalog.baseUrl, catalog.params], ({signal}) =>
    get<InverseViewSummary[]>(catalog.baseUrl, catalog.params, {signal, notifyOnError: false}),
  {retry: false, staleTime: 0, gcTime: 0, enabled: !locked, refetchOnWindowFocus: false});
  const lock = useCallback((value: boolean) => {setLocked(value); onInteractionLockChange?.(value);}, [onInteractionLockChange]);
  useEffect(() => () => onInteractionLockChange?.(false), [onInteractionLockChange]);
  const views = query.data ?? [];
  const current = views.some(view => view.viewId === selected) ? selected : views[0]?.viewId;
  const usable = active && !query.isError && !query.isFetching;
  if (query.isError) return <ReadFailure error={query.error} retry={() => void query.refetch()}/>;
  if (query.isPending) return active ? <Skeleton active paragraph={{rows: 2}}/> : null;
  if (!views.length) return null;
  return <section style={{minWidth: 0, marginTop: 16}}>
    <Tabs size="small" activeKey={current} onChange={key => {if (!locked) setSelected(key);}}
      items={views.map(view => ({key: view.viewId, icon: <TableOutlined/>, disabled: locked && view.viewId !== current,
        label: resolveLocalizedText(view.labelLocalization, locale, undefined, 'TENANT', view.label).text}))}/>
    <PageSection selected={usable}>
      {current && <InverseList key={current} parent={parent} recordId={recordId} viewId={current}
        available={usable} locked={locked} onInteractionLockChange={lock}/>}
    </PageSection>
  </section>;
}

function InverseList({parent, recordId, viewId, available, locked, onInteractionLockChange}: Props & {
  viewId: string; available: boolean; locked: boolean;
}) {
  const catalog = inverseCatalog(parent, recordId);
  const baseUrl = `${catalog.baseUrl}/${encodeURIComponent(viewId)}/records`;
  const query = useOwnedData([baseUrl, 'runtime', catalog.params], ({signal}) =>
    get<InverseRuntimeView>(`${baseUrl}/runtime`, catalog.params, {signal, notifyOnError: false}),
  {retry: false, staleTime: 0, gcTime: 0, enabled: !locked, refetchOnWindowFocus: false});
  if (query.isError) return <ReadFailure error={query.error} retry={() => void query.refetch()}/>;
  if (!query.data) return <Skeleton active paragraph={{rows: 4}}/>;
  return <div style={{height: 'clamp(320px, 55vh, 640px)', minHeight: 0, minWidth: 0}}>
    <RecordTable descriptor={inverseEditor(query.data)} available={available && !query.isFetching}
      endpoint={inverseEndpoint(parent, recordId, viewId, query.data)} showRelations={false}
      onInteractionLockChange={onInteractionLockChange}/>
  </div>;
}

function ReadFailure({error, retry}: {error: unknown; retry: () => void}) {
  const {t} = useI18n(), active = usePageActive();
  return <Alert type="error" showIcon title={t('app.runtime.relatedLoadFailed', '关联数据加载失败')}
    description={resolveApiErrorMessage(error, t('app.runtime.relatedLoadFailed', '关联数据加载失败'))}
    action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('table.retry', '重试'), disabled: !active},
    ]} onButtonEvents={{retry: () => {if (active) retry();}}}/>}/>;
}
