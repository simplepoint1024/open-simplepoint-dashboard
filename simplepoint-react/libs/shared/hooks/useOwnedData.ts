import {useEffect} from 'react';
import {useQueryClient} from '@tanstack/react-query';
import type {QueryFunctionContext, UseQueryOptions} from '@tanstack/react-query';
import {useData} from '@simplepoint/shared/api/methods';
import {cancelInactiveScopedQuery} from '@simplepoint/shared/api/queryScope';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {useQueryScope} from './useQueryScope';

/** Normal scoped query plus exact unused-request cancellation when its retained owner stops reading. */
export function useOwnedData<T>(key: readonly unknown[], fetch: (context: QueryFunctionContext) => Promise<T>,
  options?: Omit<UseQueryOptions<T, Error, T, readonly unknown[]>, 'queryKey' | 'queryFn'>) {
  const client = useQueryClient(), scope = useQueryScope(), active = usePageActive();
  const result = useData(key, fetch, options);
  const identity = JSON.stringify(key), scopeIdentity = JSON.stringify(scope);
  useEffect(() => {
    if (active && options?.enabled !== false) return;
    // Never interrupt another enabled observer sharing the same authorized query.
    void cancelInactiveScopedQuery(client, scope, key);
  }, [active, options?.enabled, identity, scopeIdentity, client]);
  return result;
}
