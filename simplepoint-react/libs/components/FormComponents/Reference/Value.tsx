import {useContext, useMemo} from 'react';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import ReferenceLabel from '../../ReferenceLabel';
import TableActions from '../../Table/Actions';
import {ReferenceRuntimeContext, type ReferenceSources} from './RuntimeContext';
import {referenceIdentity, referenceProjection} from './display';
import {ReferenceNavigationContext} from './NavigationContext';
import {ReferenceSnapshotContext} from './SnapshotContext';
import {snapshotLabelKey} from './snapshots';

/** Live reads require an owning runtime adapter. Snapshots and designer previews stay entirely static. */
type Presentation = 'both' | 'label' | 'action';

export default function FormReferenceValue({value, relationId, presentation = 'both'}: {value: unknown; relationId: string; presentation?: Presentation}) {
  const sources = useContext(ReferenceRuntimeContext);
  const snapshots = useContext(ReferenceSnapshotContext);
  const binding = useMemo(() => snapshots === undefined ? sources?.(relationId) : undefined, [sources, relationId, snapshots]);
  const id = referenceIdentity(value);
  if (snapshots !== undefined) return presentation === 'action' ? null : id
    ? <ReferenceLabel value={id} projection={snapshots.get(snapshotLabelKey(relationId, id)) ?? {id, label: null}}/>
    : <>{value == null || value === '' ? '—' : String(value)}</>;
  if (!binding?.source.resolve || !id) return presentation === 'action' ? null : <>{value == null || value === '' ? '—' : String(value)}</>;
  return <LiveReferenceValue key={JSON.stringify([binding.source.key, binding.endpoint, id])} id={id} binding={binding} presentation={presentation}/>;
}

function LiveReferenceValue({id, binding, presentation}: {id: string; binding: ReturnType<ReferenceSources>; presentation: Presentation}) {
  const {t} = useI18n(), active = usePageActive();
  const actionOnly = presentation === 'action';
  const navigation = useContext(ReferenceNavigationContext);
  const query = useOwnedData(['form-reference-label', binding.endpoint, binding.source.key, id],
    ({signal}) => binding.source.resolve!([id], signal),
    {retry: false, staleTime: 0, gcTime: 0, refetchOnMount: 'always'});
  // Revoked/error/stale-in-flight responses must never retain a previous sensitive label.
  if (!active || query.isFetching || query.isPending) return actionOnly ? null : <span aria-busy={active}>{active ? t('loading', '加载中...') : '—'}</span>;
  if (query.isError) return actionOnly ? null : <span role="status">{t('table.loadFail', '加载失败')}
    <TableActions appearance="row" selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'retry', sort: 0, icon: 'ReloadOutlined', iconOnly: true, text: t('table.retry', '重试')},
    ]} onButtonEvents={{retry: () => {if (active) void query.refetch();}}}/>
  </span>;
  const projection = referenceProjection(id, query.data ?? []);
  const option = query.data?.find(item => item.value.toLowerCase() === id);
  const canOpen = presentation !== 'label' && projection.label !== null && option?.metadata?.canDrillThrough === true && navigation?.canOpen(binding, id);
  return <>{!actionOnly && <ReferenceLabel value={id} projection={projection}/>} {canOpen && <TableActions appearance="row" selectedKeys={[]} selectedRows={[]} buttons={[
    {key: 'view', sort: 0, icon: 'EyeOutlined', text: t('app.runtime.record.detail', '详情')},
  ]} onButtonEvents={{view: () => {if (active && navigation?.canOpen(binding, id)) navigation.open(binding, id);}}}/>}</>;
}
