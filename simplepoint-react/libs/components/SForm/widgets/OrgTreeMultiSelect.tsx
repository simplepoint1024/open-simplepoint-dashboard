import type {WidgetProps} from '@rjsf/utils';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  OrganizationTransferSelect,
  type EntityTransferQuery,
  type OrganizationTransferItem,
} from '../../EntityTransfer';

function pageOrganizations(
  items: OrganizationTransferItem[],
  query: EntityTransferQuery,
): Page<OrganizationTransferItem> {
  const keyword = query.search.trim().toLowerCase();
  const filtered = keyword
    ? items.filter((item) =>
      item.id.toLowerCase().includes(keyword) ||
      item.name.toLowerCase().includes(keyword) ||
      (item.code ?? '').toLowerCase().includes(keyword) ||
      (item.type ?? '').toLowerCase().includes(keyword) ||
      (item.description ?? '').toLowerCase().includes(keyword)
    )
    : items;
  const start = query.page * query.pageSize;
  return {
    content: filtered.slice(start, start + query.pageSize),
    page: {
      size: query.pageSize,
      number: query.page,
      totalElements: filtered.length,
      totalPages: Math.ceil(filtered.length / query.pageSize),
    },
  };
}

async function fetchOrganizationPage(query: EntityTransferQuery) {
  const page = await get<Page<OrganizationTransferItem>>('/common/platform/organizations', {
    page: '0',
    size: '1000',
  });
  return pageOrganizations(page.content ?? [], query);
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
        value={selectedValues}
        onValueChange={(nextKeys) => onChange(nextKeys.length > 0 ? nextKeys : undefined)}
        disabled={disabled || readonly}
        listHeight={280}
        defaultPageSize={5}
        selectedLookupPageSize={1000}
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
