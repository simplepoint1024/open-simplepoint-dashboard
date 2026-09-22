import {useCallback, useEffect, useState} from 'react';
import type {ColumnsType, DataTableProps, QuerySchema, TableQueryState} from './index';
import {transportFilters} from '../Table/query';
import {get, usePage} from '@simplepoint/shared/api/methods';
import {normalizePage, toPagination, type RawPage} from '@simplepoint/shared/types/request';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';

/** Each table owns a server query, never filters the currently loaded page locally. */
export function useRemoteList<T extends object, Wire extends object = T>(endpoint: string, active: boolean, transform?: (row: Wire) => T) {
  const schemaResult = useSchema(endpoint, {enabled: active, retry: false});
  const schema = schemaResult.data?.schema?.['x-query'] ? schemaResult.data.schema as unknown as QuerySchema : undefined;
  const [state, setState] = useState<TableQueryState>({filters: {}});
  const [pagination, setPagination] = useState({page: 0, size: 20});
  const result = usePage<T>([endpoint, state, pagination], async ({signal}) => {
    const response = normalizePage(await get<RawPage<Wire>>(endpoint, {
    ...transportFilters(state.filters), ...pagination, ...(state.sort ? {sort: state.sort} : {}),
    }, {signal}));
    return {...response, content: response.content.map(row => transform ? transform(row) : row as unknown as T)};
  }, {enabled: active && !!schema, retry: false});
  useEffect(() => {
    if (result.data && pagination.page > 0 && result.data.page.totalElements <= pagination.page * pagination.size) {
      setPagination(previous => ({...previous, page: Math.max(0, result.data!.page.totalPages - 1)}));
    }
  }, [result.data, pagination]);
  const page = result.data ?? normalizePage<T>({content: [], totalElements: 0, number: pagination.page, size: pagination.size});
  const refresh = useCallback(async () => {if (active) await result.refetch();}, [active, result.refetch]);
  const invalidSchema = !!schemaResult.data && !schema;
  const error = schemaResult.error ?? result.error ?? (invalidSchema ? new Error('查询配置不完整') : undefined);
  const reset = () => {setState({filters: {}}); setPagination(previous => ({...previous, page: 0}));};
  const retry = () => {void (schemaResult.error || invalidSchema ? schemaResult.refetch() : result.refetch());};
  const tableProps: Partial<DataTableProps<T>> = {
    rowKey: 'id', storageKey: endpoint, refresh,
    dataSource: error ? [] : page.content,
    loading: result.isFetching || schemaResult.isFetching,
    query: schema ? {mode: 'remote', schema, state, onChange: next => {
      setState(next); setPagination(previous => ({...previous, page: 0}));
    }} : undefined,
    pagination: {...toPagination(page), current: pagination.page + 1, pageSize: pagination.size,
      showSizeChanger: true, onChange: (number, size) => setPagination({page: number - 1, size})},
  };
  const columns = useCallback((definitions: ColumnsType<T>): ColumnsType<T> => !schema ? [] : definitions.filter(column =>
    !('dataIndex' in column) || !column.dataIndex || !!schema.properties[String(column.dataIndex)]), [schema]);
  return {page, schema, tableProps, error, retry, reset, refresh, columns};
}
