import {CancelledError} from '@tanstack/react-query';
import type {QueryClient, QueryFunctionContext, QueryKey} from '@tanstack/react-query';
import {getStoredContextId, getStoredRoleId, getStoredTenantId} from './contextId.ts';

export type QueryScope = readonly [tenantId: string | null, roleId: string | null, contextId: string | null];

const SCOPE_KEY = 'simplepoint.query-scope';
const SCOPE_EVENTS = ['sp-set-tenant', 'sp-set-role', 'sp-set-context-id', 'storage'] as const;
export const EMPTY_QUERY_SCOPE = '[null,null,null]';

// A primitive snapshot remains referentially stable for useSyncExternalStore.
export function getQueryScopeSnapshot(): string {
  const tenantId = getStoredTenantId()?.trim() || null;
  const roleId = tenantId ? getStoredRoleId(tenantId)?.trim() || null : null;
  const contextId = tenantId ? getStoredContextId(tenantId, roleId ?? undefined)?.trim() || null : null;
  return JSON.stringify([tenantId, roleId, contextId]);
}

export function subscribeQueryScope(onChange: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const target = window;
  let previous = getQueryScopeSnapshot();
  const listener = () => {
    // Read the active scope, not the event payload: events can describe another
    // tenant being prepared, or a late context response for a previous role.
    const next = getQueryScopeSnapshot();
    if (next === previous) return;
    previous = next;
    onChange();
  };
  SCOPE_EVENTS.forEach((event) => target.addEventListener(event, listener));
  return () => SCOPE_EVENTS.forEach((event) => target.removeEventListener(event, listener));
}

export function scopedQueryKey(scope: QueryScope, key: QueryKey): QueryKey {
  return [SCOPE_KEY, ...scope, ...key];
}

export function scopedQueryOptions<T>(
  scope: QueryScope,
  key: QueryKey,
  fetchFn: (context: QueryFunctionContext) => Promise<T>,
) {
  return {
    queryKey: scopedQueryKey(scope, key),
    queryFn: async (context: QueryFunctionContext): Promise<T> => {
      // A delayed refetch must not send today's authorization headers and put
      // its response into yesterday's cache key.
      if (JSON.stringify(scope) !== getQueryScopeSnapshot() || context.signal.aborted) {
        throw new CancelledError({revert: true});
      }
      const result = await fetchFn(context);
      // Also protect callers whose transport does not yet support AbortSignal.
      if (context.signal.aborted) throw new CancelledError({revert: true});
      return result;
    },
  };
}

/** Install once per host QueryClient; global/session queries are left intact. */
export function bindQueryScope(client: QueryClient): () => void {
  const discardPreviousScopes = () => {
    const scope = JSON.parse(getQueryScopeSnapshot()) as QueryScope;
    const filters = {
      predicate: (query: {queryKey: QueryKey}) => query.queryKey[0] === SCOPE_KEY
        && scope.some((value, index) => query.queryKey[index + 1] !== value),
    };
    // Cancellation starts synchronously. Do not await before removing: a rapid
    // A -> B -> A switch must not let delayed cleanup delete the new A query.
    void client.cancelQueries(filters);
    client.removeQueries(filters);
  };
  const unsubscribe = subscribeQueryScope(discardPreviousScopes);
  discardPreviousScopes();
  return unsubscribe;
}
