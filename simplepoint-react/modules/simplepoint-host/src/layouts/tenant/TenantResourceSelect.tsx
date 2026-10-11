import {useMemo} from 'react';
import type {WidgetProps} from '@rjsf/utils';
import EntitySelect, {type EntitySelectSource} from '@simplepoint/components/EntitySelect';
import {get} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import type {ResourceType, TenantResource} from './resourceForms';
const endpoint = '/common/tenants/current-resources/picker';
/** Uses the bounded platform selection behavior and the server's current workspace eligibility. */
export default function TenantResourceSelect({id, value, disabled, readonly, required, onChange, options}: WidgetProps) {
  const tenantId = String(options.tenantId), kind = String(options.kind), childType = options.childType as ResourceType;
  const excludedId = typeof options.excludedId === 'string' ? options.excludedId : undefined;
  const source = useMemo<EntitySelectSource>(() => {
    const params = {kind, childType, ...(excludedId ? {excludedId} : {})};
    const option = (row: Record<string, unknown>) => typeof row.id === 'string' && row.id.trim() && row.id !== '***'
      && typeof row.name === 'string' && row.name.trim() && row.name !== '***' && typeof row.code === 'string' && row.code !== '***'
      ? {value: row.id, label: `${row.name ?? ''} · ${row.code ?? ''}`} : undefined;
    return {key: JSON.stringify(['tenant-resource-picker.v1', tenantId, kind, childType, excludedId]),
      searchParams: (keyword, page, size) => ({...params, keyword, page, size}), option,
      resolve: async (ids, signal) => {
        const resolved = [];
        for (const selectedId of ids) {
          const result = normalizePage(await get<RawPage<TenantResource>>(endpoint, {...params, selectedId, page: 0, size: 1},
            {signal, headers: {'X-Tenant-Id': tenantId}}), 1);
          for (const row of result.content) {const item = option(row); if (item) resolved.push(item);}
        }
        return resolved;
      },
    };
  }, [tenantId, kind, childType, excludedId]);
  return <EntitySelect id={id} endpoint={endpoint} source={source} multiple={false} value={typeof value === 'string' ? value : undefined}
    disabled={disabled || readonly} allowClear={!required} onChange={next => onChange(next)}/>;
}
