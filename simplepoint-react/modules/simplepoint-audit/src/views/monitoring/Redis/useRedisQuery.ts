import {useCallback, useEffect, useId, useState} from 'react';
import type {QuerySchema, TableQueryState} from '@simplepoint/components/DataTable';
import {transportFilters} from '@simplepoint/components/Table/query';
import {get, useData} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {usePageActive} from '@simplepoint/shared/page/hooks';
import {pollingInterval, SnapshotSession, validateScanResponse, type ScanStatus} from './snapshot';

export type RedisRow = {
  key: string; type?: string; ttlSeconds?: number | string | null; persistent?: boolean | string | null;
  size?: number | string | null; editable?: boolean | string | null; observedAt?: string;
};
export type ScanResult = {
  status: ScanStatus; snapshotId?: string | null; observedKeys: number; pattern: string;
  startedAt?: string | null; completedAt?: string | null; expiresAt?: string | null;
  message?: string | null; page?: RawPage<RedisRow> | null;
};

/** Mounted once per auth context and scan pattern; table states never contain partial SCAN pages. */
export function useRedisQuery(baseUrl: string, pattern: string) {
  const endpoint = baseUrl + '/query';
  const active = usePageActive();
  const instance = useId();
  const [session] = useState(() => new SnapshotSession());
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<TableQueryState>({filters: {}});
  const [pagination, setPagination] = useState({page: 0, size: 20});
  const schemas = useSchema(endpoint, {retry: false});
  const schema = schemas.data?.schema?.['x-query'] ? schemas.data.schema as unknown as QuerySchema : undefined;
  const result = useData<ScanResult>([endpoint, instance, pattern, revision, state, pagination], async ({signal}) => {
    const response = await get<ScanResult>(endpoint, {
      ...transportFilters(state.filters), ...pagination, ...(state.sort ? {sort: state.sort} : {}),
      scanPattern: pattern, ...session.parameters(revision),
    }, {signal});
    validateScanResponse(response);
    session.accept(revision, response, signal.aborted);
    return response;
  }, {enabled: !!schema, retry: false, gcTime: 0, refetchOnWindowFocus: false,
    refetchInterval: query => pollingInterval(query.state.data?.status, active)});
  const page = result.data?.status === 'READY' && result.data.page
    ? normalizePage(result.data.page) : undefined;
  useEffect(() => {
    if (page && pagination.page > 0 && pagination.page >= page.page.totalPages) {
      setPagination(previous => ({...previous, page: Math.max(0, page.page.totalPages - 1)}));
    }
  }, [page?.page.totalPages, pagination.page]);
  const change = useCallback((next: TableQueryState) => {
    setState(next); setPagination(previous => ({...previous, page: 0}));
  }, []);
  const refresh = useCallback(() => {
    setRevision(session.refresh());
    setPagination(previous => ({...previous, page: 0}));
  }, [session]);
  const reset = useCallback(() => change({filters: {}}), [change]);
  const error = schemas.error ?? result.error ?? (schemas.data && !schema ? new Error('查询配置不完整') : undefined);
  return {state, change, pagination, setPagination, page, scan: result.data, schema,
    buttons: schemas.data?.buttons ?? [], error, refresh, reset,
    retry: () => {void (schemas.error || !schema ? schemas.refetch() : result.refetch());},
    loading: schemas.isFetching || result.isFetching || result.data?.status === 'BUILDING'};
}
