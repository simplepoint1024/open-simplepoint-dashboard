import {useCallback, useMemo, useState} from 'react';
import {App, Button, Tooltip} from 'antd';
import type {ColumnType, ColumnsType, TableProps} from 'antd/es/table';
import {ClearOutlined, FilterFilled, FilterOutlined} from '@simplepoint/shared/types/icon';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import ColumnFilter from '../Table/ColumnFilter';
import QueryTools from '../Table/QueryTools';
import {encodeDocument, filterNode, nullOps, validateView} from '../Table/query';
import {compileLocalQuery, localQuerySchema, type LocalFields, type QuerySchema, type TableQueryState} from './localQuery';

export type DataTableQuery = {
  mode: 'local';
  /** Complete supplied data or an explicitly bounded result snapshot, never a server page. */
  scope: 'complete' | 'result';
  fields: LocalFields;
  contextKey?: string;
} | {
  mode: 'remote';
  schema: QuerySchema;
  state: TableQueryState;
  onChange: (state: TableQueryState) => void;
  contextKey?: string;
};

const empty: TableQueryState = {filters: {}};

/** Shared query UI, with explicit local/remote ownership to prevent filtering only a server page. */
export function useTableQuery<T extends object>(query: DataTableQuery | undefined,
  sourceColumns: ColumnsType<T>, data: readonly T[], storageKey: string, allColumns = sourceColumns) {
  const scope = useQueryScope();
  const {t} = useI18n();
  const {message} = App.useApp();
  const [local, setLocal] = useState<{key: string; state: TableQueryState; page: number; size?: number}>({key: '', state: empty, page: 1});
  const schema = useMemo(() => {
    if (query?.mode !== 'local') return query?.schema;
    const localSchema = localQuerySchema(query.fields);
    for (const [name, field] of Object.entries(query.fields)) {
      const column = allColumns.find(col => !('children' in col)
        && String(col.key ?? (Array.isArray(col.dataIndex) ? col.dataIndex.join('.') : col.dataIndex)) === (field.columnKey ?? name));
      if (!field.label && typeof column?.title === 'string') localSchema.properties[name].title = column.title;
    }
    return localSchema;
  }, [query, allColumns]);
  const definitionKey = JSON.stringify(query?.mode === 'local' ? Object.fromEntries(Object.entries(query.fields)
    .sort(([a], [b]) => a.localeCompare(b)).map(([name, {label: _label, ...definition}]) => [name, definition])) : schema?.['x-query']);
  const stateKey = JSON.stringify([scope, storageKey, query?.contextKey, definitionKey]);
  const current = query?.mode === 'remote' ? query.state : local.key === stateKey ? local.state : empty;
  const page = local.key === stateKey ? local.page : 1;
  const fields = query?.mode === 'local' ? query.fields : undefined;
  const contract = schema?.['x-query'];
  const apply = useCallback((filters: Record<string, string>, sort?: string | string[]) => {
    if (!contract || !query) return;
    const state = {filters, sort};
    try {
      validateView({name: 'current', ...state}, contract);
      // Typed values (dates, bounds, decimals) are checked before accepting local state.
      if (query.mode === 'local') compileLocalQuery(query.fields, state);
      if (query.mode === 'remote') query.onChange(state);
      else setLocal(previous => ({key: stateKey, state, page: 1, size: previous.key === stateKey ? previous.size : undefined}));
    } catch (error) {void message.error((error as Error).message);}
  }, [contract, query, stateKey, message]);
  const result = useMemo(() => {
    if (!fields) return {rows: data, error: undefined};
    try {
      const compiled = compileLocalQuery(fields, current);
      const hasFilters = Object.keys(current.filters).length > 0;
      const hasSort = Array.isArray(current.sort) ? current.sort.length > 0 : Boolean(current.sort);
      const rows = hasFilters ? data.filter(compiled.matches) : data;
      return {rows: hasSort ? [...rows].sort(compiled.compare) : rows, error: undefined};
    } catch (error) {
      // Do not silently show unfiltered data when an old view becomes incompatible.
      return {rows: [], error: (error as Error).message};
    }
  }, [data, fields, current]);
  const sorts = useMemo(() => current.sort ? Array.isArray(current.sort) ? current.sort : [current.sort] : [], [current.sort]);
  const columns = useMemo<ColumnsType<T>>(() => {
    if (!contract) return sourceColumns;
    const decorate = (definitions: ColumnsType<T>): ColumnsType<T> => definitions.map(column => {
      if ('children' in column) return {...column, children: decorate(column.children)};
      const key = String(column.key ?? (Array.isArray(column.dataIndex) ? column.dataIndex.join('.') : column.dataIndex));
      const alias = fields ? Object.keys(fields).find(field => (fields[field].columnKey ?? field) === key) : key;
      const capability = alias ? contract.fields[alias] : undefined;
      if (!alias || !capability) return {...column, sorter: undefined, filterDropdown: undefined, filters: undefined, onFilter: undefined};
      const property = schema!.properties[alias];
      const node = current.filters[alias] ? filterNode(alias, current.filters[alias]) : undefined;
      const sortIndex = sorts.findIndex(s => s.split(',')[0] === alias);
      const direction = sorts[sortIndex]?.split(',')[1];
      const scalarType = Array.isArray(property?.type) ? property.type.find((value: string) => value !== 'null') : property?.type;
      const type = scalarType === 'number' || scalarType === 'integer' || scalarType === 'boolean'
        ? scalarType : property?.format?.startsWith('date') ? 'date' : 'string';
      return {...column, key: alias, onFilter: undefined, filters: undefined,
        sorter: capability.sortable ? {multiple: sortIndex < 0 ? 1 : 100 - sortIndex} : undefined,
        sortOrder: direction === 'asc' ? 'ascend' : direction === 'desc' ? 'descend' : null,
        filterIcon: () => node ? <FilterFilled style={{color: '#1677ff'}} /> : <FilterOutlined />,
        filterDropdown: ({close}) => <ColumnFilter columnLabel={property?.title ?? alias} columnType={type}
          javaType={capability.javaType} format={property?.format} operators={capability.operators}
          options={property?.oneOf?.filter((option: {const?: unknown}) => option.const != null)
            .map((option: {const: unknown; title?: string}) => ({value: String(option.const), label: option.title ?? String(option.const)}))
            ?? property?.enum?.map((value: unknown) => ({value: String(value), label: String(value)}))}
          initialOp={node?.op} initialValue={node?.value}
          onChange={(op, text, value) => {
            const next = {...current.filters};
            if (nullOps.includes(op) || text) next[alias] = encodeDocument({field: alias, op, ...(nullOps.includes(op) ? {} : {value})});
            else delete next[alias];
            apply(next, current.sort); close();
          }} />,
      } as ColumnType<T>;
    });
    return decorate(sourceColumns);
  }, [sourceColumns, contract, fields, schema, current, sorts, apply]);
  const handleChange: NonNullable<TableProps<T>['onChange']> = (pagination, _filters, sorter, extra) => {
    if (extra.action === 'sort') {
      const nextSort = (Array.isArray(sorter) ? sorter : [sorter]).filter(s => s.order)
        .map(s => `${String(s.columnKey)},${s.order === 'ascend' ? 'asc' : 'desc'}`);
      apply(current.filters, nextSort.length ? nextSort : undefined);
    } else if (query?.mode === 'local' && extra.action === 'paginate') {
      setLocal({key: stateKey, state: current, page: pagination.current ?? 1, size: pagination.pageSize});
    }
  };
  const scopeHint = query?.mode === 'local' ? query.scope === 'result'
    ? t('table.query.resultScope', '仅筛选当前已返回的结果集，不会重新查询数据源；被截断的结果不包含未返回数据。')
    : t('table.query.localScope', '对已完整加载的数据先筛选和排序，再进行分页。') : undefined;
  // Persist by resource, not serialized schema: JSON property order can change on server restart.
  // Existing views are validated against the current contract when the user applies them.
  const tools = schema && contract && Object.keys(contract.fields).length > 0
    ? <><QueryTools contract={contract} properties={schema.properties} filters={current.filters} sort={current.sort}
      storageKey={`${storageKey}.query`} scopeHint={scopeHint} onApply={apply} />
      {Object.keys(current.filters).length > 0 && <Tooltip title={t('table.clearFilters', '清除全部筛选')}>
        <Button aria-label={t('table.clearFilters', '清除全部筛选')} className="sp-table-icon-button" type="text"
          icon={<ClearOutlined/>} onClick={() => apply({}, current.sort)} />
      </Tooltip>}</> : null;
  return {columns, data: result.rows, error: result.error, tools, scopeHint, page,
    size: local.key === stateKey ? local.size : undefined, onChange: handleChange,
    reset: () => apply({}, undefined)};
}
