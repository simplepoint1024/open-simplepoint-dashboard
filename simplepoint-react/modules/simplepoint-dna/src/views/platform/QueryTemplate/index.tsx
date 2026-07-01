import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {Alert, Tag, message} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {resolveErrorMessage} from '../shared';

const baseConfig = api['platform.dna-federation-query-templates'];
const dataSourceConfig = api['platform.dna-data-sources'];

type JdbcDataSourceOption = {
  id: string;
  code?: string;
  name?: string;
  enabled?: boolean;
};

const App = () => {
  const {t, ensure, locale} = useI18n();
  const [dataSources, setDataSources] = useState<JdbcDataSourceOption[]>([]);
  const [dataSourcesLoaded, setDataSourcesLoaded] = useState(false);

  useEffect(() => {
    void ensure([...baseConfig.i18nNamespaces, ...dataSourceConfig.i18nNamespaces]);
  }, [ensure, locale]);

  const loadDataSources = useCallback(async () => {
    const page = await get<Page<JdbcDataSourceOption>>(dataSourceConfig.baseUrl, {page: 0, size: 200});
    setDataSources((page.content ?? []).filter((ds) => ds.enabled !== false));
    setDataSourcesLoaded(true);
  }, []);

  useEffect(() => {
    void loadDataSources().catch((error) => {
      setDataSourcesLoaded(true);
      message.error(resolveErrorMessage(error, t('dna.federation.queryTemplates.error.loadDataSources', '数据源列表加载失败')));
    });
  }, [loadDataSources, t]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.catalogId) {
      properties.catalogId.title = t('dna.federation.queryTemplates.title.catalogId', '数据源');
      properties.catalogId.oneOf = dataSources.map((ds) => ({
        const: ds.id,
        title: ds.name || ds.code || ds.id,
      }));
    }
    if (properties.sql) {
      properties.sql['x-ui'] = {...(properties.sql['x-ui'] ?? {}), widget: 'textarea'};
    }
    delete properties.catalogCode;
    delete properties.catalogName;
    return nextSchema;
  }, [dataSources, t]);

  const columnOverrides = useMemo(() => ({
    catalogId: {
      title: t('dna.federation.queryTemplates.title.catalogId', '数据源'),
      width: 180,
      render: (_: string, record: {catalogName?: string; catalogCode?: string}) =>
        record.catalogName || record.catalogCode || '-',
    },
    isPublic: {
      title: t('dna.federation.queryTemplates.title.isPublic', '公开'),
      width: 80,
      render: (value?: boolean) => (
        <Tag color={value ? 'green' : 'default'}>
          {value
            ? t('dna.federation.queryTemplates.value.public', '是')
            : t('dna.federation.queryTemplates.value.private', '否')}
        </Tag>
      ),
    },
    enabled: {
      title: t('dna.federation.queryTemplates.title.enabled', '状态'),
      width: 80,
      render: (value?: boolean) => (
        <Tag color={value ? 'green' : 'red'}>
          {value
            ? t('dna.federation.queryTemplates.value.enabled', '启用')
            : t('dna.federation.queryTemplates.value.disabled', '禁用')}
        </Tag>
      ),
    },
  }), [t]);

  return (
    <div>
      {dataSourcesLoaded && dataSources.length === 0 ? (
        <Alert
          type="info"
          showIcon
          style={{marginBottom: 16}}
          message={t(
            'dna.federation.queryTemplates.message.noDataSources',
            '暂无可用数据源，查询模板可不绑定数据源。'
          )}
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
