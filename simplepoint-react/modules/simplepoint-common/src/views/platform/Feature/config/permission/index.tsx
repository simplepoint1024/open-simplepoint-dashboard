import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect, useMemo, useState} from 'react';
import {GetProp, TableColumnsType, TableProps, TransferProps} from 'antd';
import STableTransfer from '@simplepoint/components/STableTransfer';
import {useData, usePage} from '@simplepoint/shared/api/methods';
import {PermissionRelevantVo, fetchItems, fetchSelectedItems} from '@/api/system/permission';
import {fetchAuthorized, fetchAuthorize, fetchUnauthorized} from '@/api/platform/feature';

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];

export interface FeaturePermissionConfigProps {
  featureCode: string;
}

interface TableTransferProps extends TransferProps<TransferItem> {
  dataSource: PermissionRelevantVo[];
  leftColumns: TableColumnsType<PermissionRelevantVo>;
  rightColumns: TableColumnsType<PermissionRelevantVo>;
}

const App = ({featureCode}: FeaturePermissionConfigProps) => {
  const {t, messages, ensure, locale} = useI18n();
  const [leftPage, setLeftPage] = useState({current: 1, pageSize: 10});
  const [rightPage, setRightPage] = useState({current: 1, pageSize: 10});

  useEffect(() => {
    void ensure(['permissions', 'table', 'common']);
  }, [ensure, locale]);

  const {data: page} = usePage<PermissionRelevantVo>(
    ['platform-permission-items', leftPage.current, leftPage.pageSize],
    () => fetchItems({page: String(leftPage.current - 1), size: String(leftPage.pageSize)})
  );
  const content = page?.content ?? [];

  const columns: TableColumnsType<PermissionRelevantVo> = useMemo(
    () => [
      {key: 'name', dataIndex: 'name', title: t('permissions.title.name')},
      {key: 'authority', dataIndex: 'authority', title: t('permissions.title.authority')},
      {key: 'description', dataIndex: 'description', title: t('permissions.title.description')},
    ],
    [messages],
  );

  const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);
  const [selectedItems, setSelectedItems] = useState<PermissionRelevantVo[]>([]);
  const selectedAuthorities = useMemo(
    () => (targetKeys ?? []).map((key) => String(key)),
    [targetKeys],
  );

  const {data: authorized} = useData<string[]>(
    featureCode ? ['fetchAuthorizedFeaturePermissions', featureCode] : '',
    () => fetchAuthorized({featureCode}),
    {enabled: !!featureCode},
  );

  const {data: selectedDetails} = useData<PermissionRelevantVo[]>(
    featureCode && selectedAuthorities.length > 0
      ? ['fetchSelectedFeaturePermissions', ...selectedAuthorities]
      : ['fetchSelectedFeaturePermissions', featureCode || 'empty'],
    () => fetchSelectedItems(selectedAuthorities),
    {enabled: !!featureCode && selectedAuthorities.length > 0},
  );

  useEffect(() => {
    setTargetKeys([]);
    setSelectedItems([]);
    setLeftPage((prev) => ({...prev, current: 1}));
    setRightPage((prev) => ({...prev, current: 1}));
  }, [featureCode]);

  useEffect(() => {
    if (authorized) {
      setTargetKeys(authorized);
    }
  }, [authorized]);

  useEffect(() => {
    if (!selectedAuthorities.length) {
      setSelectedItems([]);
      return;
    }
    if (selectedDetails) {
      const detailMap = new Map(selectedDetails.map((item) => [item.authority, item]));
      setSelectedItems(
        selectedAuthorities
          .map((authority) => detailMap.get(authority))
          .filter((item): item is PermissionRelevantVo => !!item),
      );
    }
  }, [selectedAuthorities, selectedDetails]);

  const dataSource = useMemo(() => {
    const map = new Map<string, PermissionRelevantVo>();
    content.forEach((item) => map.set(item.authority, item));
    selectedItems.forEach((item) => map.set(item.authority, item));
    return Array.from(map.values());
  }, [content, selectedItems]);

  const leftPagination: TableProps<PermissionRelevantVo>['pagination'] = {
    current: leftPage.current,
    pageSize: leftPage.pageSize,
    total: page?.page.totalElements ?? 0,
    showSizeChanger: true,
    showQuickJumper: true,
    onChange: (current, pageSize) => setLeftPage({current, pageSize}),
  };

  const rightPagination: TableProps<PermissionRelevantVo>['pagination'] = {
    current: rightPage.current,
    pageSize: rightPage.pageSize,
    total: selectedItems.length,
    showSizeChanger: true,
    showQuickJumper: true,
    onChange: (current, pageSize) => setRightPage({current, pageSize}),
  };

  const onChange: TableTransferProps['onChange'] = (nextTargetKeys, direction, moveKeys) => {
    setTargetKeys(nextTargetKeys);
    setRightPage((prev) => ({...prev, current: 1}));

    const mergedItemMap = new Map<string, PermissionRelevantVo>();
    dataSource.forEach((item) => mergedItemMap.set(item.authority, item));
    selectedItems.forEach((item) => mergedItemMap.set(item.authority, item));
    setSelectedItems(
      (nextTargetKeys ?? [])
        .map((key) => mergedItemMap.get(String(key)))
        .filter((item): item is PermissionRelevantVo => !!item),
    );
    if (!featureCode) return;

    if (direction === 'right') {
      void fetchAuthorize({featureCode, permissionAuthority: moveKeys as string[]});
    } else {
      void fetchUnauthorized({featureCode, permissionAuthority: moveKeys as string[]});
    }
  };

  if (!featureCode) {
    return <div style={{flex: 1, minHeight: 0}}/>;
  }

  return (
    <div style={{display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0}}>
      <div style={{flex: 1, minHeight: 0}}>
        <STableTransfer
          dataSource={dataSource}
          targetKeys={targetKeys}
          showSelectAll={false}
          onChange={onChange}
          leftColumns={columns}
          rightColumns={columns}
          itemKey="authority"
          adaptiveHeight
          searchable
          leftPagination={leftPagination}
          rightPagination={rightPagination}
        />
      </div>
    </div>
  );
};

export default App;
