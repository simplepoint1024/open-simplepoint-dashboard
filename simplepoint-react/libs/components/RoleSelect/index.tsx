import {useMemo} from 'react';
import EntitySelect, {type EntitySelectProps, type EntitySelectSource} from '../EntitySelect';
import TenantDirectorySelect from '../TenantDirectorySelect';
import {tenantDirectoryLookupBatches} from '../TenantDirectorySelect/query';
import {get} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {EntityOption} from '../EntitySelect/query';

type Selection<P> = P extends EntitySelectProps ? Omit<P, 'endpoint' | 'source' | 'filters' | 'lookupFilters'> : never;
export type RoleSelectProps = Selection<EntitySelectProps> & {
  /** Workflow candidates persist authorities; context switching persists role IDs. Never convert implicitly. */
  directory?: 'tenant-authorities' | 'current';
};
const endpoint = '/common/current-directory/roles';
export default function RoleSelect({directory = 'tenant-authorities', ...props}: RoleSelectProps) {
  const {t, locale} = useI18n(), [tenantId] = useQueryScope();
  const source = useMemo<EntitySelectSource>(() => {
    const option = (row: Record<string, unknown>): EntityOption | undefined => typeof row.id === 'string' && !!row.id && !/^\*+$/.test(row.id)
      ? {value: row.id, label: typeof row.name === 'string' && row.name.trim() && !/^\*+$/.test(row.name.trim())
        ? row.name.trim() : t('role.unnamed', '未命名角色')} : undefined;
    return {key: JSON.stringify(['role-select.current', tenantId, locale]), option,
      searchParams: (keyword, page, size) => ({tenantId: tenantId ?? '', keyword: keyword.trim(), page, size}),
      resolve: async (ids, signal) => {
        const result: EntityOption[] = [], expected = new Set(ids);
        for (const values of tenantDirectoryLookupBatches(ids)) {
          const rows = await get<Record<string, unknown>[]>(endpoint + '/selected',
            {tenantId: tenantId ?? '', values: values.join(',')}, {signal, notifyOnError: false});
          result.push(...rows.flatMap(row => {const item = option(row); return item && expected.has(item.value) ? [item] : [];}));
        }
        return result;
      }};
  }, [tenantId, locale, t]);
  const placeholder = props.placeholder ?? t('form.roleSelect.placeholder', '搜索名称或权限标识选择角色');
  return <div className="sp-domain-select" data-domain-select="role" data-directory={directory}>
    {directory === 'tenant-authorities' ? <TenantDirectorySelect {...props} kind="roles" placeholder={placeholder}/>
      : <EntitySelect {...props} disabled={props.disabled || !tenantId} endpoint={endpoint} source={source} placeholder={placeholder}/>}
  </div>;
}
