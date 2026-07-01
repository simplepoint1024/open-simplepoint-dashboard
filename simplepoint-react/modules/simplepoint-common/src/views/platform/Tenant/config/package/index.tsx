import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect, useMemo, useState} from 'react';
import {GetProp, TableColumnsType, TransferProps} from 'antd';
import STableTransfer from '@simplepoint/components/STableTransfer';
import {useData, usePage} from '@simplepoint/shared/api/methods';
import {fetchAuthorized, fetchAuthorize, fetchUnauthorized} from '@/api/platform/tenant';
import {fetchItems, PackageRelevantVo} from '@/api/platform/package';

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];

export interface TenantPackageConfigProps {
  tenantId: string;
}

interface TableTransferProps extends TransferProps<TransferItem> {
  dataSource: PackageRelevantVo[];
  leftColumns: TableColumnsType<PackageRelevantVo>;
  rightColumns: TableColumnsType<PackageRelevantVo>;
}

const App = ({tenantId}: TenantPackageConfigProps) => {
  const {t, messages, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(['packages']);
  }, [ensure, locale]);

  const {data: page} = usePage<PackageRelevantVo>(
    ['platform-package-items'],
    () => fetchItems({page: '0', size: '10000000'})
  );
  const content = page?.content ?? [];

  const columns: TableColumnsType<PackageRelevantVo> = useMemo(
    () => [
      {key: 'name', dataIndex: 'name', title: t('packages.title.name')},
      {key: 'code', dataIndex: 'code', title: t('packages.title.code')},
      {key: 'description', dataIndex: 'description', title: t('packages.title.description')},
    ],
    [messages],
  );

  const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);

  const {data: authorized} = useData<string[]>(
    tenantId ? ['fetchAuthorizedTenantPackages', tenantId] : '',
    () => fetchAuthorized({tenantId}),
    {enabled: !!tenantId},
  );

  useEffect(() => {
    setTargetKeys([]);
  }, [tenantId]);

  useEffect(() => {
    if (authorized) {
      setTargetKeys(authorized);
    }
  }, [authorized]);

  const onChange: TableTransferProps['onChange'] = (nextTargetKeys, direction, moveKeys) => {
    setTargetKeys(nextTargetKeys);
    if (!tenantId) return;

    if (direction === 'right') {
      void fetchAuthorize({tenantId, packageCodes: moveKeys as string[]});
    } else {
      void fetchUnauthorized({tenantId, packageCodes: moveKeys as string[]});
    }
  };

  if (!tenantId) {
    return <div style={{flex: 1, minHeight: 0}}/>;
  }

  return (
    <div style={{display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0}}>
      <div style={{flex: 1, minHeight: 0}}>
        <STableTransfer
          dataSource={content}
          targetKeys={targetKeys}
          showSelectAll={false}
          onChange={onChange}
          leftColumns={columns}
          rightColumns={columns}
          itemKey="code"
          adaptiveHeight
          searchable
        />
      </div>
    </div>
  );
};

export default App;
