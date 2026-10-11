import {useCallback, useEffect, useRef} from 'react';
import {getQueryScopeSnapshot} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageSelected} from '@simplepoint/shared/page/hooks';

/** A retained page owns its writes; leaving its authorization scope aborts transport and drops late UI results. */
export function useMutationScope() {
  const key = JSON.stringify(useQueryScope()), selected = usePageSelected(), selection = useRef(selected);
  selection.current = selected;
  const mounted = useRef(true), request = useRef<AbortController | undefined>(undefined);
  useEffect(() => {mounted.current = true; return () => {mounted.current = false; request.current?.abort();};}, []);
  const current = useCallback((signal?: AbortSignal) => mounted.current && key === getQueryScopeSnapshot() && !signal?.aborted, [key]);
  const begin = useCallback(() => {
    if (!selection.current || !current()) return undefined;
    request.current?.abort(); const next = new AbortController(); request.current = next; return next;
  }, [current]);
  return {selected, current, begin};
}
