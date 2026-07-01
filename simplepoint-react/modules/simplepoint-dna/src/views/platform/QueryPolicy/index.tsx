import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {Alert, Tag, message} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {resolveErrorMessage} from '../shared';

const baseConfig = api['platform.dna-federation-query-policies'];
const dataSourceConfig = api['platform.dna-data-sources'];

type DataSourceOption = {
  id: string;
  code?: string;
  name?: string;
  enabled?: boolean;
};

const resolveDataSourceLabel = (dataSource: DataSourceOption, disabledSuffix: string) => {
  const primary = dataSource.name || dataSource.code || dataSource.id;
  const secondary = dataSource.code && dataSource.code !== primary ? ` (${dataSource.code})` : '';
  const disabled = dataSource.enabled === false ? disabledSuffix : '';
  return `${primary}${secondary}${disabled}`;
};

const renderBooleanTag = (value: boolean | undefined, yes: string, no: string) => {
  if (value == null) {
    return '-';
  }
  return <Tag color={value ? 'green' : 'default'}>{value ? yes : no}</Tag>;
};

const App = () => {
  const {t, ensure, locale} = useI18n();
  const [dataSources, setDataSources] = useState<DataSourceOption[]>([]);
  const [dataSourcesLoaded, setDataSourcesLoaded] = useState(false);

  useEffect(() => {
    void ensure([...baseConfig.i18nNamespaces, ...dataSourceConfig.i18nNamespaces]);
  }, [ensure, locale]);

  const loadDataSources = useCallback(async () => {
    const page = await get<Page<DataSourceOption>>(dataSourceConfig.baseUrl, {page: 0, size: 200});
    setDataSources((page.content ?? []).filter((ds) => ds.enabled !== false));
    setDataSourcesLoaded(true);
  }, []);

  useEffect(() => {
    void loadDataSources().catch((error) => {
      setDataSourcesLoaded(true);
      message.error(resolveErrorMessage(error, t('dna.federation.queryPolicies.page.error.loadDataSources', '数据源列表加载失败')));
    });
  }, [loadDataSources, t]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.catalogId) {
      properties.catalogId.title = t('dna.federation.queryPolicies.page.field.dataSource', '数据源');
      properties.catalogId.oneOf = dataSources.map((dataSource) => ({
        const: dataSource.id,
        title: resolveDataSourceLabel(dataSource, t('dna.federation.queryPolicies.page.state.disabledSuffix', ' - 已禁用')),
      }));
      properties.catalogId.description = dataSources.length > 0
        ? t('dna.federation.queryPolicies.page.form.dataSource.description.available', '请选择已配置的数据源')
        : t('dna.federation.queryPolicies.page.form.dataSource.description.empty', '请先在数据源页面新增数据源');
    }
    delete properties.catalogCode;
    delete properties.catalogName;
    return nextSchema;
  }, [dataSources, t]);

  const columnOverrides = useMemo(() => ({
    catalogId: {
      title: t('dna.federation.queryPolicies.page.field.dataSource', '数据源'),
      width: 220,
      render: (value: string, record: {catalogName?: string; catalogCode?: string}) =>
        record.catalogName || record.catalogCode || value || '-',
    },
    allowSqlConsole: {
      title: t('dna.federation.queryPolicies.title.allowSqlConsole', '允许 SQL 控制台'),
      width: 160,
      render: (value?: boolean) => renderBooleanTag(value, t('dna.federation.queryPolicies.page.state.yes', '是'), t('dna.federation.queryPolicies.page.state.no', '否')),
    },
    allowCrossSourceJoin: {
      title: t('dna.federation.queryPolicies.title.allowCrossSourceJoin', '允许跨源 Join'),
      width: 160,
      render: (value?: boolean) => renderBooleanTag(value, t('dna.federation.queryPolicies.page.state.yes', '是'), t('dna.federation.queryPolicies.page.state.no', '否')),
    },
  }), [t]);

  return (
    <div>
      {dataSourcesLoaded && dataSources.length === 0 ? (
        <Alert
          type="warning"
          showIcon
          style={{marginBottom: 16}}
          message={t('dna.federation.queryPolicies.page.alert.noDataSources.title', '当前还没有数据源')}
          description={t('dna.federation.queryPolicies.page.alert.noDataSources.description', '请先到数据源页面新增数据源，再回来配置查询策略。')}
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
