import {lazy, Suspense, useContext, type ReactNode} from 'react';
import {Skeleton} from 'antd';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import Inspector from '../FormComponents/Reference/Inspector';
import {ReferenceNavigationContext} from '../FormComponents/Reference/NavigationContext';
import {useInRouterContext, useNavigate} from 'react-router';
import type {ReferenceBinding} from '../FormComponents/Reference/RuntimeContext';

const RecordTable = lazy(() => import('./RecordTable'));

/** One bounded inspector stack per owning page/editor; nested forms reuse that stack. */
type Props = {ownerKey: string; children: ReactNode; enabled?: boolean};

export default function PublishedReferenceInspector(props: Props) {
  const existing = useContext(ReferenceNavigationContext), routed = useInRouterContext();
  if (existing) return <>{props.children}</>;
  return routed ? <RoutedInspector {...props}/> : <InspectorSession {...props}/>;
}

function RoutedInspector(props: Props) {
  const navigate = useNavigate();
  return <InspectorSession {...props} onOpenPage={(binding, id) => navigate(binding.pageLocation!(id))}/>;
}

function InspectorSession({ownerKey, children, enabled = true, onOpenPage}: Props & {onOpenPage?: (binding: ReferenceBinding, id: string) => void}) {
  const scope = useQueryScope();
  return <Inspector key={JSON.stringify([scope, ownerKey])} enabled={enabled} onOpenPage={onOpenPage} renderDetail={({form, record}, context) =>
    <Suspense fallback={<Skeleton active/>}><RecordTable descriptor={form} recordId={record.id}
      onInteractionLockChange={context.onInteractionLockChange}/></Suspense>}>
    {children}
  </Inspector>;
}
