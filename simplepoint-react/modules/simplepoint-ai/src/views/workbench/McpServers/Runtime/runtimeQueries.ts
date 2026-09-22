import {useMemo} from 'react';
import {get, useData} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {entityLookupBatches} from '@simplepoint/components/EntitySelect/query';
import type {RuntimeServer} from './types';

const enabled = JSON.stringify({version: 1, where: {field: 'enabled', op: 'equals', value: true}});
export const managedServerFilters = {
  deploymentType: JSON.stringify({version: 1, where: {field: 'deploymentType', op: 'equals', value: 'MANAGED_OCI'}}),
  enabled,
};
export const enabledSecretFilters = {enabled};

/** Decorate only the current page; no all-server catalog and no per-row request waterfall. */
export function useRuntimeServerNames(endpoint: string, rows: readonly {serverId: string}[], active = true) {
  const ids = [...new Set(rows.map(row => row.serverId).filter(id => typeof id === 'string' && id !== '' && id !== '***'))].sort();
  const result = useData(['runtime-server-names', endpoint, ids], async ({signal}) => {
    const servers: RuntimeServer[] = [];
    for (const parameters of entityLookupBatches(ids)) {
      servers.push(...normalizePage(await get<RawPage<RuntimeServer>>(endpoint, parameters, {signal})).content);
    }
    return servers;
  }, {enabled: active && ids.length > 0, retry: false});
  return useMemo(() => new Map((result.data ?? []).map(server => [server.id, server.name || server.code || server.id])), [result.data]);
}
