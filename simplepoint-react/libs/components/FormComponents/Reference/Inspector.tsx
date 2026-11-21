import {useCallback, useLayoutEffect, useMemo, useRef, useState, type ContextType, type ReactNode} from 'react';
import {Alert, Skeleton} from 'antd';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import PageSection from '@simplepoint/shared/page/Section';
import ResourceDrawer from '../../ResourceDrawer';
import TableActions from '../../Table/Actions';
import {ReferenceNavigationContext} from './NavigationContext';
import type {ReferenceBinding, ReferenceDetail} from './RuntimeContext';
import {referenceNavigation} from './navigation';

type Frame = {key: string; binding: ReferenceBinding; recordId: string};
type Props = {children: ReactNode; enabled?: boolean; renderDetail: (detail: ReferenceDetail,
  context: {onInteractionLockChange: (locked: boolean) => void}) => ReactNode;
  onOpenPage?: (binding: ReferenceBinding, recordId: string) => void};

/** The owning domain supplies authorized readers, rendering and the shared action lifecycle. */
export default function ReferenceInspector({children, renderDetail, onOpenPage, enabled = true}: Props) {
  const selected = usePageSelected();
  const [frames, setFrames] = useState<Frame[]>([]);
  const current = useRef(frames), foreground = useRef(selected);
  foreground.current = selected && enabled;
  useLayoutEffect(() => {
    if (!enabled) {current.current = []; setFrames([]);}
  }, [enabled]);
  const open = useCallback((depth: number, binding: ReferenceBinding, recordId: string) => {
    if (!foreground.current || current.current.length !== depth) return;
    const action = referenceNavigation(depth, recordId, !!binding.detail, !!onOpenPage && !!binding.pageLocation);
    if (action === 'page') {onOpenPage!(binding, recordId); return;}
    if (action !== 'drawer') return;
    const next = [...current.current, {key: crypto.randomUUID(), binding, recordId}];
    current.current = next; setFrames(next);
  }, [onOpenPage]);
  const navigation = (depth: number) => ({
    canOpen: (binding: ReferenceBinding, id: string) => selected && enabled && current.current.length === depth
      && !!referenceNavigation(depth, id, !!binding.detail, !!onOpenPage && !!binding.pageLocation),
    open: (binding: ReferenceBinding, id: string) => open(depth, binding, id),
  });
  const close = () => {
    if (!foreground.current) return;
    const next = current.current.slice(0, -1); current.current = next; setFrames(next);
  };
  return <ReferenceNavigationContext.Provider value={navigation(0)}>
    <PageSection selected={!enabled || frames.length === 0}>{children}</PageSection>
    {enabled && frames.map((frame, index) => {
      const top = index === frames.length - 1;
      return <ReferenceFrame key={frame.key} frame={frame} top={top} onClose={close}
        navigation={navigation(index + 1)} renderDetail={renderDetail}/>;
    })}
  </ReferenceNavigationContext.Provider>;
}

function ReferenceFrame({frame, top, onClose, navigation, renderDetail}: {frame: Frame; top: boolean;
  onClose: () => void; navigation: ContextType<typeof ReferenceNavigationContext>; renderDetail: Props['renderDetail']}) {
  const {t} = useI18n();
  const [locked, setLocked] = useState(false);
  return <ResourceDrawer rootClassName="sp-reference-inspector" placement="right"
    title={t('app.runtime.record.detail', '详情')} defaultWidth={1000} open={top} preserveContents locked={locked}
    onClose={() => {if (!locked) onClose();}}>
    <PageSection selected={top}>
      <ReferenceNavigationContext.Provider value={navigation}>
        <ReferenceDetailContent frame={frame} locked={locked} renderDetail={renderDetail} onInteractionLockChange={setLocked}/>
      </ReferenceNavigationContext.Provider>
    </PageSection>
  </ResourceDrawer>;
}

function ReferenceDetailContent({frame, locked, renderDetail, onInteractionLockChange}: {frame: Frame; locked: boolean;
  renderDetail: Props['renderDetail']; onInteractionLockChange: (locked: boolean) => void}) {
  const {t} = useI18n(), selected = usePageSelected();
  const key = useMemo(() => ['form-reference-detail', frame.key, frame.binding.endpoint, frame.binding.source.key, frame.recordId], [frame]);
  const query = useOwnedData(key, ({signal}) => frame.binding.detail!(frame.recordId, signal),
    {retry: false, staleTime: 0, gcTime: 0, enabled: !locked, refetchOnWindowFocus: false, refetchOnMount: 'always'});
  return <>
    {selected && (query.isFetching || query.isPending) && <Skeleton active paragraph={{rows: 5}}/>}
    {selected && !query.isFetching && query.isError && <Alert type="error" showIcon title={t('app.runtime.loadFailed', '应用页面加载失败')}
    action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
      {key: 'retry', sort: 0, icon: 'ReloadOutlined', text: t('table.retry', '重试')},
    ]} onButtonEvents={{retry: () => {if (selected) void query.refetch();}}}/>}/>}
    {query.data && !query.isError && <div hidden={!selected || query.isFetching} style={{minHeight: 0}}>
      <PageSection selected={selected && !query.isFetching}>{renderDetail(query.data, {onInteractionLockChange})}</PageSection>
    </div>}
  </>;
}
