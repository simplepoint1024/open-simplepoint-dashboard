import {useMemo} from 'react';
import EntitySelect, {type EntitySelectProps, type EntitySelectSource} from '../EntitySelect';
import TenantDirectorySelect from '../TenantDirectorySelect';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {userPickerConfiguration, userPickerLookupBatches, userPickerOption} from './model';

type Selection<P> = P extends EntitySelectProps ? Omit<P, 'endpoint' | 'source' | 'filters' | 'lookupFilters'> : never;
export type UserSelectProps = Selection<EntitySelectProps> & {
  /** Tenant members and global account discovery intentionally have different authorization boundaries. */
  directory?: 'tenant' | 'accounts';
  accountOptions?: Record<string, unknown>;
  /** A create-only domain endpoint may explicitly forbid hydration by arbitrary ID. */
  resolveAccounts?: boolean;
};

export default function UserSelect({directory = 'tenant', accountOptions, resolveAccounts = true, ...props}: UserSelectProps) {
  const {t, locale} = useI18n();
  const config = userPickerConfiguration(accountOptions);
  const source = useMemo<EntitySelectSource>(() => ({
    key: JSON.stringify([config.key, resolveAccounts, locale]), pageSize: config.pageSize, debounceMs: config.debounceMs,
    minSearchLength: config.minSearchLength,
    searchParams: (keyword, page, size) => ({...config.staticParams, [config.pageParam]: page,
      [config.sizeParam]: size, [config.searchParam]: keyword.trim()}),
    option: row => userPickerOption(row, t('form.userPicker.unnamed', '未设置姓名或邮箱')),
    resolve: async (requested, signal) => {
      if (!resolveAccounts) return [];
      const selected = [], expected = new Set(requested);
      for (const batch of userPickerLookupBatches(requested, config.staticParams, config.resolveParam)) {
        const result = await get<Record<string, unknown>[]>(config.resolveEndpoint,
          {...config.staticParams, [config.resolveParam]: batch.join(',')}, {signal, notifyOnError: false});
        selected.push(...(Array.isArray(result) ? result : []).flatMap(row => {
          const option = userPickerOption(row, t('form.userPicker.unnamed', '未设置姓名或邮箱'));
          return option && expected.has(option.value) ? [option] : [];
        }));
      }
      return selected;
    },
  }), [config.key, resolveAccounts, locale, t]);
  return <div className="sp-domain-select" data-domain-select="user" data-directory={directory}>
    {directory === 'tenant' ? <TenantDirectorySelect {...props} kind="users"
      placeholder={props.placeholder ?? t('form.userSelect.placeholder', '搜索姓名或邮箱选择用户')}/>
      : <EntitySelect {...props} endpoint={config.searchEndpoint} source={source}
        placeholder={props.placeholder ?? t('form.userPicker.placeholder', '输入邮箱或手机号搜索用户')}
        searchHint={t('form.userPicker.minSearch', '请输入至少 {count} 位邮箱或手机号', {count: config.minSearchLength})}/>}
  </div>;
}
