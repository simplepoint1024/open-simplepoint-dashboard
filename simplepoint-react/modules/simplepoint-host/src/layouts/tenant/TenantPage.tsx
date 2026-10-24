import type {ReactNode} from 'react';
import {Alert, Empty, Skeleton} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {fetchCurrentTenantProfile, useCurrentTenants, type CurrentTenantProfile} from '@/fetches/tenants';
import './index.css';

type Capability = 'profileEditable' | 'resourceManageable' | 'packageManageable';

/** The page capability comes from the active tenant; a retained page cannot reuse another scope's state. */
export default function TenantPage({capability, children}: {
  capability?: Capability; children: (profile: CurrentTenantProfile) => ReactNode;
}) {
  const scope = useQueryScope();
  return <TenantPageSession key={JSON.stringify(scope)} capability={capability}>{children}</TenantPageSession>;
}

function TenantPageSession({capability, children}: {capability?: Capability; children: (profile: CurrentTenantProfile) => ReactNode}) {
  const {t} = useI18n(), scope = useQueryScope(), tenantId = scope[0];
  const tenants = useCurrentTenants();
  const tenant = tenants.data?.find(item => item.tenantId === tenantId);
  const profile = useOwnedData(['common', 'tenants', 'current-profile', tenantId],
    ({signal}) => fetchCurrentTenantProfile(tenantId!, signal),
    {enabled: !!tenantId && !!tenant && tenant.tenantType !== 'PLATFORM', retry: false, staleTime: 30_000});
  if (tenant?.tenantType === 'PLATFORM') return <div className="tenant-page-state"><Empty
    description={t('tenant.profile.platformUnavailable', '平台工作空间不提供租户资料，请切换到具体租户')}/></div>;
  if (tenants.isError || profile.isError || !tenants.isPending && !tenant) return <div className="tenant-page-state">
    <Alert type="error" showIcon title={t('tenant.profile.unavailable', '当前工作空间没有可访问的租户资料')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0, icon: 'ReloadOutlined',
        text: t('action.retry', '重试'), onClick: () => {void tenants.refetch(); if (tenant) void profile.refetch();}}]}/>}/>
  </div>;
  if (!profile.data) return <div className="tenant-page-state"><Skeleton active paragraph={{rows: 8}}/></div>;
  if (capability && !profile.data[capability]) return <div className="tenant-page-state"><Empty
    description={t('tenant.settings.ownerRequired', '仅当前工作空间负责人可使用此功能')}/></div>;
  return children(profile.data);
}
