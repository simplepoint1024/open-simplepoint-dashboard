import type {WidgetProps} from '@rjsf/utils';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  OrganizationTransferSelect,
  type EntityTransferQuery,
  type OrganizationTransferItem,
} from '../../EntityTransfer';

type OrganizationOptionPage = {
  content: OrganizationTransferItem[];
  number: number;
  size: number;
  hasNext: boolean;
};

const endpoint = '/common/platform/organizations/options';

function toTransferPage(result: OrganizationOptionPage, query: EntityTransferQuery): Page<OrganizationTransferItem> {
  const number = result.number ?? query.page;
  const size = result.size ?? query.pageSize;
  const totalPages = number + 1 + (result.hasNext ? 1 : 0);
  return {
    content: result.content ?? [],
    page: {
      size,
      number,
      totalPages,
      totalElements: result.hasNext ? totalPages * size : number * size + (result.content?.length ?? 0),
    },
  };
}

async function fetchOrganizationPage(query: EntityTransferQuery) {
  const result = await get<OrganizationOptionPage>(endpoint, {
    page: String(query.page),
    size: String(query.pageSize),
    flat: 'true',
    ...(query.search.trim() ? {keyword: query.search.trim()} : {}),
  });
  return toTransferPage(result, query);
}

async function fetchSelectedOrganizations(keys: string[]) {
  if (keys.length === 0) return [];
  const batches: string[][] = [];
  for (let index = 0; index < keys.length; index += 100) {
    batches.push(keys.slice(index, index + 100));
  }
  const pages = await Promise.all(batches.map(batch => get<OrganizationOptionPage>(endpoint, {
    ids: batch.join(','),
    size: String(batch.length),
  })));
  return pages.flatMap(page => page.content ?? []);
}

const OrgTreeMultiSelect = ({value, disabled, readonly, onChange, rawErrors}: WidgetProps) => {
  const {t} = useI18n();
  const selectedValues = Array.isArray(value) ? value.map(String) : [];

  return (
    <div
      style={{
        border: rawErrors && rawErrors.length > 0 ? '1px solid #ff4d4f' : undefined,
        borderRadius: rawErrors && rawErrors.length > 0 ? 6 : undefined,
        padding: rawErrors && rawErrors.length > 0 ? 4 : undefined,
      }}
    >
      <OrganizationTransferSelect
        fetchItems={fetchOrganizationPage}
        fetchSelectedItems={fetchSelectedOrganizations}
        value={selectedValues}
        onValueChange={(nextKeys) => onChange(nextKeys.length > 0 ? nextKeys : undefined)}
        disabled={disabled || readonly}
        listHeight={280}
        defaultPageSize={5}
        selectedLookupPageSize={0}
        titles={[
          t('organizations.selector.available', '可选组织'),
          t('organizations.selector.selected', '已选组织'),
        ]}
        locale={{
          itemUnit: t('organizations.selector.itemUnit', '项'),
          itemsUnit: t('organizations.selector.itemsUnit', '项'),
          searchPlaceholder: t('organizations.selector.searchPlaceholder', '搜索组织'),
          notFoundContent: t('table.emptyText', '暂无数据'),
        }}
      />
    </div>
  );
};

export default OrgTreeMultiSelect;
