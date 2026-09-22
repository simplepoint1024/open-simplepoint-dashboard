import {useEffect, useRef, useState} from 'react';
import type {Key} from 'react';
import {Alert, Button, Typography} from 'antd';
import DataTable, {type ColumnsType, type QuerySchema, type TableQueryState} from '../DataTable';
import TableActions from '../Table/Actions';
import {transportFilters} from '../Table/query';
import {get, usePage} from '@simplepoint/shared/api/methods';
import {normalizePage, toPagination, type RawPage} from '@simplepoint/shared/types/request';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useActionFeedback} from '@simplepoint/shared/hooks/useActionFeedback';

export interface AssignmentTableProps<T extends object> {
  endpoint: string;
  parent: Record<string, string>;
  storageKey: string;
  columns: ColumnsType<T>;
  itemKey: keyof T & string;
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
  onMove: (selected: boolean, keys: string[]) => Promise<unknown>;
}

/** Both sides are independently server-paged; never materializes an entire assignment catalog. */
export default function AssignmentTable<T extends object>(props: AssignmentTableProps<T>) {
  const scope = useQueryScope();
  const contextKey = JSON.stringify([scope, props.endpoint, props.parent]);
  return <AssignmentSession key={contextKey} {...props} contextKey={contextKey}/>;
}

function AssignmentSession<T extends object>(props: AssignmentTableProps<T> & {contextKey: string}) {
  const {t} = useI18n();
  const run = useActionFeedback();
  const schemaQuery = useSchema(props.endpoint, {retry: false}, props.parent);
  const raw = schemaQuery.data?.schema;
  const schema = raw?.['x-query'] ? raw as unknown as QuerySchema : undefined;
  const [revision, setRevision] = useState(0);
  const [moving, setMoving] = useState(false);
  const lock = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {mounted.current = true; return () => {mounted.current = false;};}, []);
  const move = async (selected: boolean, keys: string[]) => {
    if (props.disabled || lock.current || keys.length === 0 || keys.length > 100
      || keys.some(key => props.disabledKeys?.includes(key))) return;
    lock.current = true;
    setMoving(true);
    try {
      const result = await run('assignment.move', () => props.onMove(selected, keys));
      if (mounted.current && result.status === 'success' && props.refreshAfterMove !== false) setRevision(value => value + 1);
    } finally {
      lock.current = false;
      if (mounted.current) setMoving(false);
    }
  };
  const invalidSchema = !!schemaQuery.data && !schema;
  return <div className="sp-assignment-table" style={{height: '100%', minHeight: 0, overflow: 'auto'}}>
    {(schemaQuery.error || invalidSchema) && <Alert type="error" showIcon
      title={t('table.transfer.schemaError', '查询配置加载失败，请重试')}
      action={<Button onClick={() => void schemaQuery.refetch()}>{t('table.retry', '重试')}</Button>}/>}
    <div style={{display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 340px), 1fr))', gap: 12}}>
      {[false, true].map(selected => <AssignmentPanel key={String(selected)} {...props} selected={selected}
        schema={schema} schemaLoading={schemaQuery.isFetching && !schema} moving={moving} revision={revision} move={move}/>) }
    </div>
  </div>;
}

function AssignmentPanel<T extends object>({endpoint, parent, storageKey, columns, itemKey, contextKey, disabledKeys,
  disabled, queryKey, loadPage, labels, isRowDisabled,
  schema, schemaLoading, selected, moving, revision, move}: AssignmentTableProps<T> & {
  contextKey: string; schema?: QuerySchema; schemaLoading: boolean; selected: boolean; moving: boolean; revision: number;
  move: (selected: boolean, keys: string[]) => Promise<void>;
}) {
  const {t} = useI18n();
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
  const rowDisabled = (row: T) => !!disabledKeys?.includes(String(row[itemKey])) || !!isRowDisabled?.(selected, row);
  const selectedRows = rows.filter(row => keys.includes(String(row[itemKey])) && !rowDisabled(row));
  const activeKeys = selectedRows.map(row => String(row[itemKey]));
  const busy = disabled || moving || result.isFetching || !schema || !!result.error;
  useEffect(() => {setKeys([]);}, [revision, inputKey]);
  useEffect(() => {
    if (result.data && pagination.page > 0 && result.data.page.totalElements <= pagination.page * pagination.size) {
      setPagination(previous => ({...previous, page: Math.max(0, result.data!.page.totalPages - 1)}));
      setKeys([]);
    }
  }, [result.data, pagination]);
  return <section className="sp-assignment-panel" data-selected={selected} style={{minWidth: 0}}>
    <Typography.Title level={5}>{selected ? labels?.assigned ?? t('table.transfer.assigned', '已分配') : labels?.available ?? t('table.transfer.available', '未分配')}</Typography.Title>
    {result.error && <Alert type="error" showIcon title={t('table.loadFail', '加载失败')}
      action={<Button onClick={() => void result.refetch()}>{t('table.retry', '重试')}</Button>}/>}
    <DataTable<T> rowKey={itemKey} columns={schema ? columns.filter(column =>
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
        buttons={[{key: selected ? 'unassign' : 'assign', sort: 0, argumentMinSize: 1, argumentMaxSize: 100,
          text: selected ? labels?.unassign ?? t('table.transfer.unassign', '移除分配') : labels?.assign ?? t('table.transfer.assign', '分配'),
          icon: selected ? 'LeftOutlined' : 'RightOutlined', danger: selected, disabled: busy}]}
        onButtonEvents={{[selected ? 'unassign' : 'assign']: () => {void move(selected, activeKeys);}}}/>} />
  </section>;
}
