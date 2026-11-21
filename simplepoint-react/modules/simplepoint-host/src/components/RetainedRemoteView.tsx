import type {ComponentType} from 'react';
import {usePageLocation} from '@simplepoint/shared/page/hooks';
import {pageTabIdentity} from '@simplepoint/shared/page/viewIdentity';
import {BoundaryAndSuspense} from './withBoundaryAndSuspense';

/** Refresh belongs to this retained view, not every instance of its resource route. */
export function RetainedRemoteView({component, t, path, refreshKeys}: {
  component: ComponentType;
  t: (key: string, fallback?: string) => string;
  path: string;
  refreshKeys: Record<string, number>;
}) {
  const identity = pageTabIdentity(usePageLocation());
  return <BoundaryAndSuspense component={component} t={t} path={path} refreshKey={refreshKeys[identity] || 0}/>;
}
