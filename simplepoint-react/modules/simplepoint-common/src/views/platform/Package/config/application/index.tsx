import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect, useMemo, useState} from 'react';
import {GetProp, TableColumnsType, TransferProps} from 'antd';
import STableTransfer from '@simplepoint/components/STableTransfer';
import {useData, usePage} from '@simplepoint/shared/api/methods';
import {fetchAuthorized, fetchAuthorize, fetchUnauthorized} from '@/api/platform/package';
import {ApplicationRelevantVo, fetchItems} from '@/api/platform/application';

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];

export interface PackageApplicationConfigProps {
  packageCode: string;
}

interface TableTransferProps extends TransferProps<TransferItem> {
  dataSource: ApplicationRelevantVo[];
  leftColumns: TableColumnsType<ApplicationRelevantVo>;
  rightColumns: TableColumnsType<ApplicationRelevantVo>;
}

const App = ({packageCode}: PackageApplicationConfigProps) => {
  const {t, messages, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(['applications']);
  }, [ensure, locale]);

  const {data: page} = usePage<ApplicationRelevantVo>(
    ['platform-application-items'],
    () => fetchItems({page: '0', size: '10000000'})
  );
  const content = page?.content ?? [];

  const columns: TableColumnsType<ApplicationRelevantVo> = useMemo(
    () => [
      {key: 'name', dataIndex: 'name', title: t('applications.title.name')},
      {key: 'code', dataIndex: 'code', title: t('applications.title.code')},
      {key: 'description', dataIndex: 'description', title: t('applications.title.description')},
    ],
    [messages],
  );

  const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);

  const {data: authorized} = useData<string[]>(
    packageCode ? ['fetchAuthorizedPackageApplications', packageCode] : '',
    () => fetchAuthorized({packageCode}),
    {enabled: !!packageCode},
  );

  useEffect(() => {
    setTargetKeys([]);
  }, [packageCode]);

  useEffect(() => {
    if (authorized) {
      setTargetKeys(authorized);
    }
  }, [authorized]);

  const onChange: TableTransferProps['onChange'] = (nextTargetKeys, direction, moveKeys) => {
    setTargetKeys(nextTargetKeys);
    if (!packageCode) return;

    if (direction === 'right') {
      void fetchAuthorize({packageCode, applicationCodes: moveKeys as string[]});
    } else {
      void fetchUnauthorized({packageCode, applicationCodes: moveKeys as string[]});
    }
  };

  if (!packageCode) {
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
