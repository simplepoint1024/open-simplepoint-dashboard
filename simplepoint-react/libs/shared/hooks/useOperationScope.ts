import {useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {getQueryScopeSnapshot} from '@simplepoint/shared/api/queryScope';
import {operationIsCurrent, acquireOperation, releaseOperation, resetOperation} from './operationState';

/** Atomic write lock; started writes may finish in a retained page, never in another auth scope. */
export function useOperationScope(ownerKey?: string) {
  const selected = usePageSelected(), scope = JSON.stringify(useQueryScope());
  const selection = useRef(selected), alive = useRef(true), request = useRef<AbortController | undefined>(undefined);
  const owner = useRef(ownerKey); owner.current = ownerKey;
  selection.current = selected;
  const [busy, setBusy] = useState(false);
  useLayoutEffect(() => {
    resetOperation(request); setBusy(false);
  }, [scope, ownerKey]);
  useEffect(() => {alive.current = true; return () => {alive.current = false; request.current?.abort();};}, []);
  const current = useCallback((signal?: AbortSignal) => operationIsCurrent(scope, getQueryScopeSnapshot(), ownerKey,
    owner.current, alive.current, signal), [scope, ownerKey]);
  const begin = useCallback(() => {
    if (!current() || !selection.current || request.current) return undefined;
    const next = acquireOperation(request); if (!next) return undefined;
    setBusy(true); return next;
  }, [current]);
  const finish = useCallback((next: AbortController) => {
    if (releaseOperation(request, next) && current()) setBusy(false);
  }, [current]);
  const isSelected = useCallback(() => selection.current, []);
  const isBusy = useCallback(() => Boolean(request.current), []);
  return {selected, isSelected, isBusy, busy, begin, finish, current};
}
