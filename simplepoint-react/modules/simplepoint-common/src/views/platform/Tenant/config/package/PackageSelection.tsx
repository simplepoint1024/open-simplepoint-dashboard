import {useMemo} from 'react';
import type {WidgetProps} from '@rjsf/utils';
import EntitySelect, {type EntitySelectSource} from '@simplepoint/components/EntitySelect';
import {entitySearchParams} from '@simplepoint/components/EntitySelect/query';
import {get} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';

const endpoint = '/common/platform/packages/items';
const predicate = (field: string, value: string | boolean) => JSON.stringify({version: 1, where: {field, op: 'equals', value}});
const eligible = {enabled: predicate('enabled', true)};

/** Package codes, not entity IDs, identify the immutable allocation snapshot. */
export default function PackageSelection({id, value, disabled, readonly, onChange}: WidgetProps) {
  const source = useMemo<EntitySelectSource>(() => {
    const option = (row: Record<string, unknown>) => typeof row.code === 'string' && row.code.trim() && !/^\*+$/.test(row.code)
      && typeof row.name === 'string' && row.name.trim() && !/^\*+$/.test(row.name)
      ? {value: row.code, label: `${row.name} · ${row.code}`} : undefined;
    return {key: 'tenant-package-allocation.v1', option,
      searchParams: (keyword, page, size) => ({...entitySearchParams(keyword, page, size), ...eligible}),
      resolve: async (codes, signal) => {
        const options = [];
        for (const code of codes) {
          const result = normalizePage(await get<RawPage<Record<string, unknown>>>(endpoint,
            {...eligible, code: predicate('code', code), page: 0, size: 1}, {signal}), 1);
          for (const row of result.content) {const candidate = option(row); if (candidate) options.push(candidate);}
        }
        return options;
      },
    };
  }, []);
  return <EntitySelect id={id} endpoint={endpoint} source={source} multiple={false} allowClear={false}
    value={typeof value === 'string' ? value : undefined} disabled={disabled || readonly} onChange={next => onChange(next)}/>;
}
