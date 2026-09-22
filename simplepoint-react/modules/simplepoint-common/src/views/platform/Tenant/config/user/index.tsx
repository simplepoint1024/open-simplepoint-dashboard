import {useEffect, useMemo} from 'react';
import AssignmentTable, {type AssignmentTableProps} from '@simplepoint/components/AssignmentTable';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import api from '@/api';
import {fetchAuthorizeUsers, fetchUnauthorizedUsers, type UserRelevanceVo} from '@/api/platform/tenant';

export interface TenantUserConfigProps {tenantId: string; ownerId?: string}

export default function TenantUserConfig({tenantId, ownerId}: TenantUserConfigProps) {
  const {t, messages, ensure, locale} = useI18n();
  useEffect(() => {void ensure(['users', 'table', 'common']);}, [ensure, locale]);
  const columns = useMemo<AssignmentTableProps<UserRelevanceVo>['columns']>(() => [
    {key: 'name', dataIndex: 'name', title: t('users.title.name', '姓名')},
    {key: 'email', dataIndex: 'email', title: t('users.title.email', '邮箱')},
    {key: 'phoneNumber', dataIndex: 'phoneNumber', title: t('users.title.phoneNumber', '手机号')},
    {key: 'id', dataIndex: 'id', title: t('users.title.id', '用户标识')},
  ], [messages, t]);
  if (!tenantId) return <div/>;
  return <AssignmentTable<UserRelevanceVo> endpoint={`${api['platform.tenants'].baseUrl}/users/items`}
    parent={{tenantId}} itemKey="id" columns={columns} storageKey="platform.tenants.users.assignment"
    disabledKeys={ownerId ? [ownerId] : undefined}
    onMove={(selected, keys) => selected
      ? fetchUnauthorizedUsers({tenantId, userIds: keys})
      : fetchAuthorizeUsers({tenantId, userIds: keys})}/>;
}
