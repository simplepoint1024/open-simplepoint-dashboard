import {useEffect, useMemo} from 'react';
import type {TableColumnsType} from 'antd';
import AssignmentTable from '@simplepoint/components/AssignmentTable';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import api from '@/api';
import {fetchAuthorize, fetchUnauthorized} from '@/api/platform/tenant';
import type {PackageRelevantVo} from '@/api/platform/package';

export interface TenantPackageConfigProps {
  tenantId: string;
  onInteractionLockChange?: (locked: boolean) => void;
}

const App = ({tenantId, onInteractionLockChange}: TenantPackageConfigProps) => {
  const {t, messages, ensure, locale} = useI18n();
  useEffect(() => {void ensure(['packages', 'table', 'common']);}, [ensure, locale]);
  const columns = useMemo<TableColumnsType<PackageRelevantVo>>(() => [
    {key: 'name', dataIndex: 'name', title: t('packages.title.name')},
    {key: 'code', dataIndex: 'code', title: t('packages.title.code')},
    {key: 'description', dataIndex: 'description', title: t('packages.title.description')},
  ], [messages, t]);
  if (!tenantId) return <div/>;
  return <AssignmentTable<PackageRelevantVo> endpoint={`${api['platform.packages'].baseUrl}/items`}
    parent={{tenantId}} itemKey="code" columns={columns} storageKey="platform.packages.assignment"
    onInteractionLockChange={onInteractionLockChange}
    onMove={(selected, keys, options) => selected
      ? fetchUnauthorized({tenantId, packageCodes: keys}, options)
      : fetchAuthorize({tenantId, packageCodes: keys}, options)}/>;
};

export default App;
