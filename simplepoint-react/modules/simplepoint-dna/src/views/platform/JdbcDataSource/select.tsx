import {useMemo} from 'react';
import EntitySelect, {type EntitySelectSource} from '@simplepoint/components/EntitySelect';
import type {EntityOption} from '@simplepoint/components/EntitySelect/query';
import {get} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import api from '@/api';

export type JdbcSourceOption = {id: string; code?: string; name?: string; enabled?: boolean;
  driverName?: string; databaseProductName?: string};
export const enabledSources = {enabled: JSON.stringify({version: 1, where: {field: 'enabled', op: 'equals', value: true}})};
export const sourceLabel = (row: JdbcSourceOption) => [row.name || row.code,
  row.code && row.code !== row.name ? `(${row.code})` : '', row.databaseProductName].filter(Boolean).join(' ');
const endpoint = api['platform.dna-data-sources'].baseUrl;
export const sourceCodeFilter = (code: string) => ({code: JSON.stringify({version: 1,
  where: {field: 'code', op: 'equals', value: code}})});

/** Source codes are queryable; IDs are not part of this resource's public query contract. */
export default function JdbcDataSourceSelect({value, disabled, placeholder, className, onChange}:
  {value?: string; disabled?: boolean; placeholder?: string; className?: string;
    onChange: (code: string | undefined, row?: JdbcSourceOption) => void}) {
  const scope = useQueryScope();
  const scopeKey = JSON.stringify(scope);
  const {source, known} = useMemo(() => {
    const known = new Map<string, JdbcSourceOption>();
    const option = (row: Record<string, unknown>): EntityOption | undefined => {
      if (typeof row.id !== 'string' || row.id === '***' || typeof row.code !== 'string' || !row.code || row.code === '***') return;
      const item = row as JdbcSourceOption;
      known.set(row.code, item);
      // The shared picker keeps ten pages; this adapter must not retain an unbounded second catalog.
      while (known.size > 220) known.delete(known.keys().next().value!);
      return {value: row.code, label: sourceLabel(item)};
    };
    const source: EntitySelectSource = {key: 'jdbc-source-code.v1',
      searchParams: (keyword, page, size) => ({page, size, sort: 'name,asc', ...(keyword.trim() ? {
        $query: JSON.stringify({version: 1, where: {op: 'or', children: ['name', 'code'].map(field =>
          ({field, op: 'like', value: keyword.trim()}))}})} : {})}),
      option,
      resolve: async (codes, signal) => {
        const options: EntityOption[] = [];
        for (const code of codes) {
          const page = normalizePage(await get<RawPage<JdbcSourceOption>>(endpoint,
            {page: 0, size: 1, ...sourceCodeFilter(code), ...enabledSources}, {signal}));
          for (const row of page.content) {const item = option(row); if (item) options.push(item);}
        }
        return options;
      },
    };
    return {source, known};
  }, [scopeKey]);
  return <EntitySelect endpoint={endpoint} source={source} filters={enabledSources} value={value}
    disabled={disabled} allowClear={false} placeholder={placeholder} className={className}
    onChange={code => onChange(code, known.get(code ?? ''))}/>;
}
