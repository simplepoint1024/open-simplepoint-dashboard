import {useMemo} from 'react';
import EntitySelect, {type EntitySelectProps, type EntitySelectSource} from '../EntitySelect';
import type {EntityOption} from '../EntitySelect/query';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {tenantDirectoryLookupBatches, tenantRoleAuthoritySelectable} from './query';

export type TenantDirectoryKind = 'users' | 'roles';
type DirectoryProps<P> = P extends EntitySelectProps ? Omit<P, 'endpoint' | 'source' | 'filters'> : never;
export type TenantDirectorySelectProps = DirectoryProps<EntitySelectProps> & {
  kind: TenantDirectoryKind;
};

type DirectoryItem = {id: string; name: string; value: string};
const option = (row: Record<string, unknown>): EntityOption | undefined =>
  typeof row.value === 'string' && row.value
    ? {value: row.value, label: typeof row.name === 'string' && row.name ? row.name : row.value}
    : undefined;

/** User IDs and role authorities use the same authorized, paged, scope-keyed selector. */
export default function TenantDirectorySelect({kind, ...props}: TenantDirectorySelectProps) {
  const {t} = useI18n();
  const endpoint = `/common/tenant-directory/${kind}`;
  const source = useMemo<EntitySelectSource>(() => {
    const toOption = (row: Record<string, unknown>): EntityOption | undefined => {
      const item = option(row);
      if (item && kind === 'roles' && !tenantRoleAuthoritySelectable(item.value)) return {...item, disabled: true,
        title: t('form.remoteSelect.roleUnsupported', '此角色权限标识无法安全用于流程：含逗号、首尾空白、控制字符或长度超限')};
      return item;
    };
    return {
      key: `tenant-directory.${kind}`,
      searchParams: (keyword, page, size) => ({keyword: keyword.trim(), page, size}),
      option: toOption,
      resolve: async (ids, signal) => {
        const options: EntityOption[] = [];
        for (const values of tenantDirectoryLookupBatches(ids)) {
          const items = await get<DirectoryItem[]>(`${endpoint}/selected`,
            {values: values.join(',')}, {signal});
          options.push(...items.flatMap(item => {
            const resolved = toOption(item);
            return resolved ? [resolved] : [];
          }));
        }
        return options;
      },
    };
  }, [endpoint, kind, t]);
  return <EntitySelect {...props} endpoint={endpoint} source={source}/>;
}
