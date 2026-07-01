import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect, useMemo, useState} from 'react';
import {GetProp, TableColumnsType, TransferProps} from 'antd';
import STableTransfer from '@simplepoint/components/STableTransfer';
import {useData, usePage} from '@simplepoint/shared/api/methods';
import {fetchAuthorized, fetchAuthorize, fetchUnauthorized} from '@/api/platform/application';
import {FeatureRelevantVo, fetchItems} from '@/api/platform/feature';

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];

export interface ApplicationFeatureConfigProps {
  applicationCode: string;
}

interface TableTransferProps extends TransferProps<TransferItem> {
  dataSource: FeatureRelevantVo[];
  leftColumns: TableColumnsType<FeatureRelevantVo>;
  rightColumns: TableColumnsType<FeatureRelevantVo>;
}

const App = ({applicationCode}: ApplicationFeatureConfigProps) => {
  const {t, messages, ensure, locale} = useI18n();

  useEffect(() => {
    void ensure(['features']);
  }, [ensure, locale]);

  const {data: page} = usePage<FeatureRelevantVo>(
    ['platform-feature-items'],
    () => fetchItems({page: '0', size: '10000000'})
  );
  const content = page?.content ?? [];

  const columns: TableColumnsType<FeatureRelevantVo> = useMemo(
    () => [
      {key: 'name', dataIndex: 'name', title: t('features.title.name')},
      {key: 'code', dataIndex: 'code', title: t('features.title.code')},
      {key: 'description', dataIndex: 'description', title: t('features.title.description')},
    ],
    [messages],
  );

  const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);

  const {data: authorized} = useData<string[]>(
    applicationCode ? ['fetchAuthorizedApplicationFeatures', applicationCode] : '',
    () => fetchAuthorized({applicationCode}),
    {enabled: !!applicationCode},
  );

  useEffect(() => {
    setTargetKeys([]);
  }, [applicationCode]);

  useEffect(() => {
    if (authorized) {
      setTargetKeys(authorized);
    }
  }, [authorized]);

  const onChange: TableTransferProps['onChange'] = (nextTargetKeys, direction, moveKeys) => {
    setTargetKeys(nextTargetKeys);
    if (!applicationCode) return;

    if (direction === 'right') {
      void fetchAuthorize({applicationCode, featureCodes: moveKeys as string[]});
    } else {
      void fetchUnauthorized({applicationCode, featureCodes: moveKeys as string[]});
    }
  };

  if (!applicationCode) {
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
