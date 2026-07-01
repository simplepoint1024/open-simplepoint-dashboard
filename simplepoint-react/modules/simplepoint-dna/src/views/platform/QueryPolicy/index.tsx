import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get} from '@simplepoint/shared/api/methods';
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

const resolveDataSourceLabel = (dataSource: DataSourceOption) => {
  const primary = dataSource.name || dataSource.code || dataSource.id;
  const secondary = dataSource.code && dataSource.code !== primary ? ` (${dataSource.code})` : '';
  const disabled = dataSource.enabled === false ? ' - 已禁用' : '';
  return `${primary}${secondary}${disabled}`;
};

const renderBooleanTag = (value?: boolean) => {
  if (value == null) {
    return '-';
  }
  return <Tag color={value ? 'green' : 'default'}>{value ? '是' : '否'}</Tag>;
};

const App = () => {
  const [dataSources, setDataSources] = useState<DataSourceOption[]>([]);
  const [dataSourcesLoaded, setDataSourcesLoaded] = useState(false);

  const loadDataSources = useCallback(async () => {
    const page = await get<Page<DataSourceOption>>(dataSourceConfig.baseUrl, {page: 0, size: 200});
    setDataSources((page.content ?? []).filter((ds) => ds.enabled !== false));
    setDataSourcesLoaded(true);
  }, []);

  useEffect(() => {
    void loadDataSources().catch((error) => {
      setDataSourcesLoaded(true);
      message.error(resolveErrorMessage(error, '数据源列表加载失败'));
    });
  }, [loadDataSources]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.catalogId) {
      properties.catalogId.title = '数据源';
      properties.catalogId.oneOf = dataSources.map((dataSource) => ({
        const: dataSource.id,
        title: resolveDataSourceLabel(dataSource),
      }));
      properties.catalogId.description = dataSources.length > 0 ? '请选择已配置的数据源' : '请先在数据源页面新增数据源';
    }
    delete properties.catalogCode;
    delete properties.catalogName;
    return nextSchema;
  }, [dataSources]);

  const columnOverrides = useMemo(() => ({
    catalogId: {
      title: '数据源',
      width: 220,
      render: (value: string, record: {catalogName?: string; catalogCode?: string}) =>
        record.catalogName || record.catalogCode || value || '-',
    },
    allowSqlConsole: {
      title: '允许 SQL 控制台',
      width: 160,
      render: renderBooleanTag,
    },
    allowCrossSourceJoin: {
      title: '允许跨源 Join',
      width: 160,
      render: renderBooleanTag,
    },
  }), []);

  return (
    <div>
      {dataSourcesLoaded && dataSources.length === 0 ? (
        <Alert
          type="warning"
          showIcon
          style={{marginBottom: 16}}
          message="当前还没有数据源"
          description="请先到数据源页面新增数据源，再回来配置查询策略。"
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
