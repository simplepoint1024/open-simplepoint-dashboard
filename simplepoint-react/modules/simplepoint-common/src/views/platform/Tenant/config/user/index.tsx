import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect, useMemo, useState} from 'react';
import {GetProp, TableColumnsType, TransferProps} from 'antd';
import STableTransfer from '@simplepoint/components/STableTransfer';
import {useData, usePage} from '@simplepoint/shared/api/methods';
import {
  fetchAuthorizedUsers,
  fetchAuthorizeUsers,
  fetchUnauthorizedUsers,
  fetchUserItems,
  UserRelevanceVo,
} from '@/api/platform/tenant';

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];

export interface TenantUserConfigProps {
  tenantId: string;
  ownerId?: string;
}

interface TableTransferProps extends TransferProps<TransferItem> {
  dataSource: UserRelevanceVo[];
  leftColumns: TableColumnsType<UserRelevanceVo>;
  rightColumns: TableColumnsType<UserRelevanceVo>;
}

const App = ({tenantId, ownerId}: TenantUserConfigProps) => {
  const {t, messages, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(['users', 'table', 'common']);
  }, [ensure, locale]);

  const {data: page} = usePage<UserRelevanceVo>(
    tenantId ? ['platform-tenant-user-items', tenantId] : '',
    () => fetchUserItems({tenantId, page: '0', size: '10000000'}),
    {enabled: !!tenantId}
  );
  const content = useMemo(
    () => (page?.content ?? []).map((item) => ({...item, disabled: item.id === ownerId})),
    [page?.content, ownerId]
  );

  const columns: TableColumnsType<UserRelevanceVo> = useMemo(
    () => [
      {key: 'name', dataIndex: 'name', title: t('users.title.nickname', '用户')},
      {key: 'email', dataIndex: 'email', title: t('users.title.email', '邮箱')},
      {key: 'phoneNumber', dataIndex: 'phoneNumber', title: t('users.title.phoneNumber', '手机号')},
    ],
    [messages],
  );

  const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);

  const {data: authorized} = useData<string[]>(
    tenantId ? ['fetchAuthorizedTenantUsers', tenantId] : '',
    () => fetchAuthorizedUsers({tenantId}),
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
      void fetchAuthorizeUsers({tenantId, userIds: moveKeys as string[]});
    } else {
      void fetchUnauthorizedUsers({tenantId, userIds: moveKeys as string[]});
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
          itemKey="id"
          adaptiveHeight
          searchable
        />
      </div>
    </div>
  );
};

export default App;
