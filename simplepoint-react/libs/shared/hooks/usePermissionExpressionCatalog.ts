import {useQueryClient} from '@tanstack/react-query';
import {get} from '../api/methods';
import {permissionCatalogKey, permissionCatalogSeed, type PermissionCatalog} from '../api/permissionCatalog';
import {useOwnedData} from './useOwnedData';
import {useQueryScope} from './useQueryScope';

/** Standalone form fields only read their target; a real page directory can seed the identical scoped target. */
export function usePermissionExpressionCatalog(resourceCode?: string) {
  const client = useQueryClient(), scope = useQueryScope();
  return useOwnedData<PermissionCatalog>([...permissionCatalogKey, resourceCode ?? ''],
    ({signal}) => get('/common/data-scopes/expression/catalog', {resourceCode}, {signal, notifyOnError: false}),
    {enabled: !!resourceCode, staleTime: 30_000, retry: false, ...permissionCatalogSeed(client, scope, resourceCode)});
}
