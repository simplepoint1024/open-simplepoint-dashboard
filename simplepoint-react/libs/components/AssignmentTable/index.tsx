import {useEffect, useLayoutEffect, useMemo, useState} from 'react';
import type {Key} from 'react';
import {Alert, Typography} from 'antd';
import DataTable, {type ColumnsType, type QuerySchema, type TableQueryState} from '../DataTable';
import TableActions from '../Table/Actions';
import {transportFilters} from '../Table/query';
import {get, usePage} from '@simplepoint/shared/api/methods';
import {normalizePage, toPagination, type RawPage} from '@simplepoint/shared/types/request';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage, type RequestOptions} from '@simplepoint/shared/api/client';
import {reachableServerPage} from '../SimpleTable/pagination';
import {assignmentKey, assignmentPresentationKeys} from './identity';

export type AssignmentColumns<T extends object> = ColumnsType<T>
  | ((state: {disabled: boolean}) => ColumnsType<T>);

export interface AssignmentTableProps<T extends object> {
  endpoint: string;
  /** Composite assignments may have a distinct persisted read model on the assigned side. */
  assignedEndpoint?: string;
  parent: Record<string, string>;
  storageKey: string;
  /** Inline editors receive the same synchronous read/write availability as row actions. */
  columns: AssignmentColumns<T>;
  assignedColumns?: AssignmentColumns<T>;
  itemKey: keyof T & string;
  assignedItemKey?: keyof T & string;
  canAssign?: boolean;
  canUnassign?: boolean;
  /** Protected assignments such as the tenant owner cannot be selected or moved. */
  disabledKeys?: readonly string[];
  /** Read-only forms can still inspect query results without modifying their draft. */
  disabled?: boolean;
  /** A draft may display ineligible rows while still allowing an existing selection to be removed. */
  isRowDisabled?: (selected: boolean, row: T) => boolean;
  /** Additional read-only query inputs, such as the form's selected draft identifiers. */
  queryKey?: readonly unknown[];
  /** Optional read-only POST query; returns a real server page, never a sliced catalog. */
  loadPage?: (request: {selected: boolean; filters: Record<string, string>; sort?: string | string[];
    page: number; size: number; signal: AbortSignal}) => Promise<RawPage<T>>;
  /** Draft values invalidate both sides themselves; persisted assignments refresh after a successful move. */
  refreshAfterMove?: boolean;
  labels?: {available: string; assigned: string; assign: string; unassign: string};
  /** selected=true removes the current selected-page rows; false assigns available rows. */
  onMove: (selected: boolean, keys: string[], options: RequestOptions) => Promise<unknown>;
  /** A containing configuration drawer must not close while this captured write is pending. */
  onInteractionLockChange?: (locked: boolean) => void;
}

/** Both sides are independently server-paged; never materializes an entire assignment catalog. */
export default function AssignmentTable<T extends object>(props: AssignmentTableProps<T>) {
  const scope = useQueryScope();
  const contextKey = JSON.stringify([scope, props.endpoint, props.assignedEndpoint, props.parent]);
  return <AssignmentSession key={contextKey} {...props} contextKey={contextKey}/>;
}

function AssignmentSession<T extends object>(props: AssignmentTableProps<T> & {contextKey: string}) {
  const operation = useOperationScope(props.contextKey);
  const {t} = useI18n();
  const [revision, setRevision] = useState(0);
  const [writeError, setWriteError] = useState<string>();
  usePageRetention(!operation.busy, operation.busy);
  useLayoutEffect(() => {
    props.onInteractionLockChange?.(operation.busy);
    return () => props.onInteractionLockChange?.(false);
  }, [props.onInteractionLockChange, operation.busy]);
  const move = async (selected: boolean, keys: string[]) => {
    if (props.disabled || (selected ? props.canUnassign === false : props.canAssign === false)
      || keys.length === 0 || keys.length > 100
      || keys.some(key => assignmentKey(key) === undefined || props.disabledKeys?.includes(key))) return;
    const request = operation.begin(); if (!request) return;
    const captured = [...keys];
    props.onInteractionLockChange?.(true);
    setWriteError(undefined);
    try {
      await props.onMove(selected, captured, {signal: request.signal, notifyOnError: false});
      if (operation.current(request.signal) && props.refreshAfterMove !== false) setRevision(value => value + 1);
    } catch (error) {
      if (operation.current(request.signal)) setWriteError(resolveApiErrorMessage(error, t('error.operationFailed', '操作失败，请重试')));
    } finally {
      if (operation.current(request.signal)) props.onInteractionLockChange?.(false);
      operation.finish(request);
    }
  };
  return <div className="sp-assignment-table" style={{height: '100%', minHeight: 0, overflow: 'auto'}}>
    {writeError && <Alert className="sp-assignment-write-error" type="error" showIcon title={writeError} style={{marginBottom: 12}}/>}
    <div style={{display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 340px), 1fr))', gap: 12}}>
      {[false, true].map(selected => <AssignmentPanel key={String(selected)} {...props} selected={selected}
        moving={operation.busy} inactive={!operation.selected} revision={revision} move={move}/>) }
    </div>
  </div>;
}

function AssignmentPanel<T extends object>({endpoint: availableEndpoint, assignedEndpoint, parent, storageKey,
  columns: availableColumns, assignedColumns, itemKey: availableItemKey, assignedItemKey, contextKey, disabledKeys,
  disabled, canAssign, canUnassign, queryKey, loadPage, labels, isRowDisabled,
  selected, moving, inactive, revision, move}: AssignmentTableProps<T> & {
  contextKey: string; selected: boolean; moving: boolean; inactive: boolean; revision: number;
  move: (selected: boolean, keys: string[]) => Promise<void>;
}) {
  const {t} = useI18n();
  const endpoint = selected ? assignedEndpoint ?? availableEndpoint : availableEndpoint;
  const columnDefinition = selected ? assignedColumns ?? availableColumns : availableColumns;
  const itemKey = selected ? assignedItemKey ?? availableItemKey : availableItemKey;
  const schemaQuery = useSchema(endpoint, {retry: false}, parent);
  const raw = schemaQuery.data?.schema;
  const schema = raw?.['x-query'] ? raw as unknown as QuerySchema : undefined;
  const schemaLoading = schemaQuery.isFetching && !schema;
  const invalidSchema = !!schemaQuery.data && !schema;
  const [query, setQuery] = useState<TableQueryState>({filters: {}});
  const [pagination, setPagination] = useState({page: 0, size: 20});
  const [keys, setKeys] = useState<Key[]>([]);
  const inputKey = JSON.stringify(queryKey ?? []);
  const result = usePage<T>([endpoint, parent, selected, query, pagination, revision, inputKey], async ({signal}) =>
    normalizePage(await (loadPage ? loadPage({selected, filters: transportFilters(query.filters), sort: query.sort,
      ...pagination, signal}) : get<RawPage<T>>(endpoint, {
      ...transportFilters(query.filters), ...parent, selected,
      page: pagination.page, size: pagination.size, ...(query.sort ? {sort: query.sort} : {}),
    }, {signal}))), {enabled: !!schema, retry: false});
  const rows = result.data?.content ?? [];
  const presentationKeys = useMemo(() => assignmentPresentationKeys(rows, itemKey), [rows, itemKey]);
  const rowDisabled = (row: T) => assignmentKey(row[itemKey]) === undefined || !!disabledKeys?.includes(String(row[itemKey])) || !!isRowDisabled?.(selected, row);
  const selectedRows = rows.filter(row => keys.includes(String(row[itemKey])) && !rowDisabled(row));
  const activeKeys = [...new Set(selectedRows.map(row => assignmentKey(row[itemKey])!))];
  const mayMove = selected ? canUnassign !== false : canAssign !== false;
  const controlsDisabled = !!(disabled || moving || inactive || result.isFetching || !schema || result.error);
  const busy = !mayMove || controlsDisabled;
  const columns = typeof columnDefinition === 'function'
    ? columnDefinition({disabled: controlsDisabled}) : columnDefinition;
  useEffect(() => {setKeys([]);}, [revision, inputKey]);
  useEffect(() => {
    if (result.isFetching || result.error) return;
    const reachable = reachableServerPage(pagination.page + 1, result.data?.page);
    if (reachable !== pagination.page + 1) {
      setPagination(previous => ({...previous, page: reachable - 1}));
      setKeys([]);
    }
  }, [result.data, result.isFetching, result.error, pagination.page]);
  return <section className="sp-assignment-panel" data-selected={selected} style={{minWidth: 0}}>
    <Typography.Title level={5}>{selected ? labels?.assigned ?? t('table.transfer.assigned', '已分配') : labels?.available ?? t('table.transfer.available', '未分配')}</Typography.Title>
    {(schemaQuery.error || invalidSchema) && <Alert type="error" showIcon
      title={t('table.transfer.schemaError', '查询配置加载失败，请重试')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retrySchema', sort: 0, argumentMinSize: 0,
        argumentMaxSize: 0, text: t('table.retry', '重试')}]}
        onButtonEvents={{retrySchema: () => {void schemaQuery.refetch();}}}/>}/>}
    {result.error && <Alert type="error" showIcon title={t('table.loadFail', '加载失败')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, argumentMinSize: 0,
        argumentMaxSize: 0, text: t('table.retry', '重试')}]}
        onButtonEvents={{retry: () => {void result.refetch();}}}/>}/>}
    <DataTable<T> rowKey={row => presentationKeys.get(row)!} columns={schema ? columns.filter(column =>
      !column.key || !!schema.properties[String(column.key)]) : columns} dataSource={result.error ? [] : rows}
      storageKey={`${storageKey}.${selected ? 'assigned' : 'available'}`} refresh={() => void result.refetch()}
      query={schema ? {mode: 'remote', schema, state: query, contextKey, onChange: next => {
        setQuery(next); setPagination(previous => ({...previous, page: 0})); setKeys([]);
      }} : undefined}
      loading={moving || result.isFetching || schemaLoading} scroll={{x: 'max-content', y: 300}} size="small"
      rowSelection={{selectedRowKeys: activeKeys, preserveSelectedRowKeys: false,
        onChange: next => {if (!busy) setKeys(next.filter(key => !disabledKeys?.includes(String(key))));},
        getCheckboxProps: row => ({disabled: busy || rowDisabled(row)})}}
      pagination={{...toPagination(result.data), current: pagination.page + 1, pageSize: pagination.size,
        onChange: (page, size) => {setPagination({page: page - 1, size}); setKeys([]);}}}
      toolbarExtra={<TableActions selectedKeys={activeKeys} selectedRows={selectedRows}
        buttons={mayMove ? [{key: selected ? 'unassign' : 'assign', sort: 0, argumentMinSize: 1, argumentMaxSize: 100,
          text: selected ? labels?.unassign ?? t('table.transfer.unassign', '移除分配') : labels?.assign ?? t('table.transfer.assign', '分配'),
          icon: selected ? 'LeftOutlined' : 'RightOutlined', danger: selected, disabled: busy}] : []}
        onButtonEvents={{[selected ? 'unassign' : 'assign']: () => {void move(selected, activeKeys);}}}/>} />
  </section>;
}
