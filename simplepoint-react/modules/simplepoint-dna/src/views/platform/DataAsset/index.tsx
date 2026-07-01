import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {Alert, Tag, message} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {resolveErrorMessage} from '../shared';

const baseConfig = api['platform.dna-data-assets'];
const dataSourceConfig = api['platform.dna-data-sources'];

type DataSourceOption = {
  id: string;
  code?: string;
  name?: string;
  enabled?: boolean;
};

const App = () => {
  const {t, ensure, locale} = useI18n();
  const [dataSources, setDataSources] = useState<DataSourceOption[]>([]);
  const [loaded, setLoaded] = useState(false);

  const assetTypes = useMemo(() => [
    {const: 'TABLE', title: t('dna.dataAssets.assetType.TABLE', '表 (TABLE)')},
    {const: 'VIEW', title: t('dna.dataAssets.assetType.VIEW', '视图 (VIEW)')},
    {const: 'ALL', title: t('dna.dataAssets.assetType.ALL', '全部 (ALL)')},
  ], [t]);

  useEffect(() => {
    void ensure([...baseConfig.i18nNamespaces, ...dataSourceConfig.i18nNamespaces]);
  }, [ensure, locale]);

  const loadDataSources = useCallback(async () => {
    const page = await get<Page<DataSourceOption>>(dataSourceConfig.baseUrl, {page: 0, size: 200});
    setDataSources((page.content ?? []).filter((ds) => ds.enabled !== false));
    setLoaded(true);
  }, []);

  useEffect(() => {
    void loadDataSources().catch((error) => {
      setLoaded(true);
      message.error(resolveErrorMessage(error, t('dna.dataAssets.error.loadDataSources', 'Failed to load data source list')));
    });
  }, [loadDataSources]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.catalogId) {
      properties.catalogId.title = t('dna.dataAssets.title.catalogId', '数据源');
      properties.catalogId.oneOf = dataSources.map((ds) => ({
        const: ds.id,
        title: ds.name || ds.code || ds.id,
      }));
    }
    if (properties.assetType) {
      properties.assetType.oneOf = assetTypes;
    }
    delete properties.catalogCode;
    delete properties.catalogName;
    return nextSchema;
  }, [assetTypes, dataSources, t]);

  const columnOverrides = useMemo(() => ({
    catalogId: {
      title: t('dna.dataAssets.title.catalogId', '数据源'),
      width: 180,
      render: (_: string, record: {catalogName?: string; catalogCode?: string}) =>
        record.catalogName || record.catalogCode || '-',
    },
    assetType: {
      title: t('dna.dataAssets.title.assetType', '资产类型'),
      width: 100,
      render: (value?: string) => {
        const colors: Record<string, string> = {TABLE: 'blue', VIEW: 'purple', ALL: 'default'};
        return <Tag color={colors[value ?? ''] ?? 'default'}>{value || '-'}</Tag>;
      },
    },
    enabled: {
      title: t('dna.dataAssets.title.enabled', '状态'),
      width: 80,
      render: (value?: boolean) => (
        <Tag color={value ? 'green' : 'red'}>{value ? t('dna.dataAssets.state.enabled', 'Enabled') : t('dna.dataAssets.state.disabled', 'Disabled')}</Tag>
      ),
    },
  }), [t]);

  return (
    <div>
      {loaded && dataSources.length === 0 ? (
        <Alert type="warning" showIcon style={{marginBottom: 16}}
          message={t('dna.dataAssets.alert.noDataSources', 'No data sources available. Please add and enable a data source first.')}
        />
      ) : null}
      <SimpleTable
        {...baseConfig}
        formSchemaTransform={formSchemaTransform}
        columnOverrides={columnOverrides}
      />
    </div>
  );
};

export default App;
