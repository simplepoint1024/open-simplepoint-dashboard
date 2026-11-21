import {useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {getQueryScopeSnapshot} from '@simplepoint/shared/api/queryScope';
import {operationIsCurrent, acquireOperation, releaseOperation, resetOperation} from './operationState';

/** Atomic write lock; started writes may finish in a retained page, never in another auth scope. */
export function useOperationScope(ownerKey?: string) {
  const selected = usePageSelected(), scope = JSON.stringify(useQueryScope());
  const selection = useRef(selected), alive = useRef(true), request = useRef<AbortController | undefined>(undefined);
  const cancelOnDeselect = useRef(false);
  const owner = useRef(ownerKey); owner.current = ownerKey;
  selection.current = selected;
  const [busy, setBusy] = useState(false);
  useLayoutEffect(() => {
    resetOperation(request); cancelOnDeselect.current = false; setBusy(false);
  }, [scope, ownerKey]);
  useLayoutEffect(() => {
    if (!selected && cancelOnDeselect.current) {
      resetOperation(request); cancelOnDeselect.current = false; setBusy(false);
    }
  }, [selected]);
  useEffect(() => {alive.current = true; return () => {alive.current = false; request.current?.abort();};}, []);
  const current = useCallback((signal?: AbortSignal) => operationIsCurrent(scope, getQueryScopeSnapshot(), ownerKey,
    owner.current, alive.current, signal), [scope, ownerKey]);
  const acquire = useCallback((readOnly: boolean) => {
    if (!current() || !selection.current || request.current) return undefined;
    const next = acquireOperation(request); if (!next) return undefined;
    cancelOnDeselect.current = readOnly;
    setBusy(true); return next;
  }, [current]);
  const begin = useCallback(() => acquire(false), [acquire]);
  /** Read preparation shares the operation lock but may be cancelled when its page is hidden. */
  const beginRead = useCallback(() => acquire(true), [acquire]);
  const finish = useCallback((next: AbortController) => {
    if (releaseOperation(request, next) && current()) {cancelOnDeselect.current = false; setBusy(false);}
  }, [current]);
  const isSelected = useCallback(() => selection.current, []);
  const isBusy = useCallback(() => Boolean(request.current), []);
  return {selected, isSelected, isBusy, busy, begin, beginRead, finish, current};
}
