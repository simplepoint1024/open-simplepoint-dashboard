import {useCallback} from 'react';
import {RoleTransferSelect, type EntityTransferQuery} from '@simplepoint/components/EntityTransfer';
import type {Page} from '@simplepoint/shared/types/request';
import {RoleRelevantVo} from '@/api/system/role';
import {fetchAuthorize, fetchAuthorized, fetchRoleCandidates, fetchUnauthorized} from '@/api/system/user';

export interface RoleSelectProps {
  userId: string | null;
}

function buildPageParams(query: EntityTransferQuery): Record<string, string> {
  const params: Record<string, string> = {
    page: String(query.page),
    size: String(query.pageSize),
  };
  const search = query.search.trim();
  if (search) {
    params.keyword = search;
  }
  return params;
}

function pageRoleCandidates(items: RoleRelevantVo[], query: EntityTransferQuery): Page<RoleRelevantVo> {
  const keyword = query.search.trim().toLowerCase();
  const filtered = keyword
    ? items.filter((item) =>
      item.id.toLowerCase().includes(keyword) ||
      item.name.toLowerCase().includes(keyword) ||
      (item.description ?? '').toLowerCase().includes(keyword)
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

const App = ({userId}: RoleSelectProps) => {
  const canQuery = !!userId;

  const fetchItems = useCallback(
    async (query: EntityTransferQuery) => {
      const page = await fetchRoleCandidates(buildPageParams({...query, page: 0, pageSize: 100000}));
      return pageRoleCandidates(page.content ?? [], query);
    },
    [],
  );

  const fetchAuthorizedKeys = useCallback(
    () => fetchAuthorized({userId}),
    [userId],
  );

  const handleAuthorize = useCallback(
    (roleIds: string[]) => fetchAuthorize({userId, roleIds}),
    [userId],
  );

  const handleUnauthorize = useCallback(
    (roleIds: string[]) => fetchUnauthorized({userId, roleIds}),
    [userId],
  );

  if (!canQuery) {
    return <div style={{flex: 1, minHeight: 0}} />;
  }

  return (
    <div style={{display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0}}>
      <div style={{flex: 1, minHeight: 0}}>
        <RoleTransferSelect<RoleRelevantVo>
          fetchItems={fetchItems}
          fetchAuthorizedKeys={fetchAuthorizedKeys}
          onAuthorize={handleAuthorize}
          onUnauthorize={handleUnauthorize}
          enabled={canQuery}
          queryDeps={[userId]}
          adaptiveHeight
          titles={['可选角色', '已分配角色']}
        />
      </div>
    </div>
  );
};

export default App;
