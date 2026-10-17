import {useMemo} from 'react';
import EntitySelect, {type EntitySelectProps, type EntitySelectSource} from '../EntitySelect';
import {entityLookupBatches, entitySearchParams, type EntityOption} from '../EntitySelect/query';
import {get} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {tenantDirectoryLookupBatches} from '../TenantDirectorySelect/query';
import {tenantEndpoint, tenantOption, type TenantDirectory} from './model';
import {TenantIdentity} from './Identity';

type Selection<P> = P extends EntitySelectProps
  ? Omit<P, 'endpoint' | 'source' | 'filters' | 'lookupFilters' | 'renderOption' | 'renderSelected'> : never;
export type TenantSelectProps = Selection<EntitySelectProps> & {directory?: TenantDirectory};

/** Explicitly separates platform administration from the caller's switchable workspaces. */
export default function TenantSelect({directory = 'current', ...props}: TenantSelectProps) {
  const {t, locale} = useI18n();
  const endpoint = tenantEndpoint(directory);
  const source = useMemo<EntitySelectSource>(() => {
    const option = (row: Record<string, unknown>) => tenantOption(row,
      t('tenant.unnamed', '未命名工作空间'), t('tenant.platformSpace', '平台工作台'),
      directory === 'current' ? t('tenant.mySpace', '我的空间') : undefined);
    return {key: JSON.stringify(['tenant-select', directory, locale]), option,
      searchParams: directory === 'platform' ? entitySearchParams : (keyword, page, size) => ({keyword: keyword.trim(), page, size}),
      resolve: async (ids, signal) => {
        const result: EntityOption[] = [];
        const expected = new Set(ids);
        const batches = directory === 'platform' ? entityLookupBatches(ids)
          : tenantDirectoryLookupBatches(ids).map(values => ({values: values.join(',')}));
        for (const params of batches) {
          const rows = directory === 'platform'
            ? normalizePage(await get<RawPage<Record<string, unknown>>>(endpoint, params, {signal, notifyOnError: false})).content
            : await get<Record<string, unknown>[]>(endpoint + '/selected', params, {signal, notifyOnError: false});
          result.push(...rows.flatMap(row => {const item = option(row); return item && expected.has(item.value) ? [item] : [];}));
        }
        return result;
      }};
  }, [directory, endpoint, locale, t]);
  return <div className="sp-domain-select" data-domain-select="tenant" data-directory={directory}>
    <EntitySelect {...props} endpoint={endpoint} source={source}
      renderOption={option => <TenantIdentity option={option}/>}
      renderSelected={option => <TenantIdentity option={option}/>}
      placeholder={props.placeholder ?? t('form.tenantSelect.placeholder', '搜索名称选择工作空间')}/>
  </div>;
}
