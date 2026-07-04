import {useCallback, useEffect} from 'react';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {UserTransferSelect, type EntityTransferQuery} from '@simplepoint/components/EntityTransfer';
import type {Page} from '@simplepoint/shared/types/request';
import {
  fetchAuthorizedUsers,
  fetchAuthorizeUsers,
  fetchUnauthorizedUsers,
  fetchUserItems,
  UserRelevanceVo,
} from '@/api/platform/tenant';

export interface TenantUserConfigProps {
  tenantId: string;
  ownerId?: string;
}

function buildPageParams(tenantId: string, query: EntityTransferQuery) {
  const params: {tenantId: string; page: string; size: string; keyword?: string} = {
    tenantId,
    page: String(query.page),
    size: String(query.pageSize),
  };
  const search = query.search.trim();
  if (search) {
    params.keyword = search;
  }
  return params;
}

function pageUserCandidates(items: UserRelevanceVo[], query: EntityTransferQuery): Page<UserRelevanceVo> {
  const keyword = query.search.trim().toLowerCase();
  const filtered = keyword
    ? items.filter((item) =>
      item.id.toLowerCase().includes(keyword) ||
      item.name.toLowerCase().includes(keyword) ||
      (item.email ?? '').toLowerCase().includes(keyword) ||
      (item.phoneNumber ?? '').includes(keyword)
    )
    : items;
  const start = query.page * query.pageSize;
  const content = filtered.slice(start, start + query.pageSize);
  return {
    content,
    page: {
      size: query.pageSize,
      number: query.page,
      totalElements: filtered.length,
      totalPages: Math.ceil(filtered.length / query.pageSize),
    },
  };
}

const App = ({tenantId, ownerId}: TenantUserConfigProps) => {
  const {t, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(['users', 'table', 'common']);
  }, [ensure, locale]);

  const fetchItems = useCallback(
    async (query: EntityTransferQuery) => {
      const page = await fetchUserItems(buildPageParams(tenantId, {...query, page: 0, pageSize: 100000}));
      return pageUserCandidates(page.content ?? [], query);
    },
    [tenantId],
  );

  const fetchAuthorizedKeys = useCallback(
    () => fetchAuthorizedUsers({tenantId}),
    [tenantId],
  );

  const handleAuthorize = useCallback(
    (userIds: string[]) => fetchAuthorizeUsers({tenantId, userIds}),
    [tenantId],
  );

  const handleUnauthorize = useCallback(
    (userIds: string[]) => fetchUnauthorizedUsers({tenantId, userIds}),
    [tenantId],
  );

  if (!tenantId) {
    return <div style={{flex: 1, minHeight: 0}} />;
  }

  return (
    <div style={{display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0}}>
      <div style={{flex: 1, minHeight: 0}}>
        <UserTransferSelect<UserRelevanceVo>
          fetchItems={fetchItems}
          fetchAuthorizedKeys={fetchAuthorizedKeys}
          onAuthorize={handleAuthorize}
          onUnauthorize={handleUnauthorize}
          disabledKeys={ownerId ? [ownerId] : undefined}
          enabled={!!tenantId}
          queryDeps={[tenantId]}
          adaptiveHeight
          selectedLookupPageSize={100000}
          titles={[
            t('tenants.userConfig.availableUsers', '可选用户'),
            t('tenants.userConfig.authorizedUsers', '租户用户'),
          ]}
        />
      </div>
    </div>
  );
};

export default App;
