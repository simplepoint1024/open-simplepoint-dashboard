import {useMemo, useSyncExternalStore} from 'react';
import {EMPTY_QUERY_SCOPE, getQueryScopeSnapshot, subscribeQueryScope} from '../api/queryScope.ts';
import type {QueryScope} from '../api/queryScope.ts';

export function useQueryScope(): QueryScope {
  const snapshot = useSyncExternalStore(subscribeQueryScope, getQueryScopeSnapshot, () => EMPTY_QUERY_SCOPE);
  return useMemo(() => JSON.parse(snapshot) as QueryScope, [snapshot]);
}
