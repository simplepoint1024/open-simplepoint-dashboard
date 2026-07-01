import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {Alert, Tag, message} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {resolveErrorMessage} from '../shared';

const baseConfig = api['platform.dna-data-quality'];
const dataSourceConfig = api['platform.dna-data-sources'];

type DataSourceOption = {
  id: string;
  code?: string;
  name?: string;
  enabled?: boolean;
};

const RULE_TYPES = [
  {const: 'NOT_NULL', title: '非空检查 (NOT_NULL)'},
  {const: 'UNIQUE', title: '唯一性检查 (UNIQUE)'},
  {const: 'RANGE', title: '范围检查 (RANGE)'},
  {const: 'REGEX', title: '正则匹配 (REGEX)'},
  {const: 'ROW_COUNT', title: '行数检查 (ROW_COUNT)'},
  {const: 'CUSTOM_SQL', title: '自定义SQL (CUSTOM_SQL)'},
];

const SEVERITIES = [
  {const: 'INFO', title: '信息 (INFO)'},
  {const: 'WARNING', title: '警告 (WARNING)'},
  {const: 'ERROR', title: '错误 (ERROR)'},
  {const: 'CRITICAL', title: '严重 (CRITICAL)'},
];

const SEVERITY_COLORS: Record<string, string> = {
  INFO: 'blue',
  WARNING: 'orange',
  ERROR: 'red',
  CRITICAL: 'magenta',
};

const STATUS_COLORS: Record<string, string> = {
  PASSED: 'green',
  FAILED: 'red',
  ERROR: 'volcano',
};

const App = () => {
  const {t, ensure, locale} = useI18n();
  const [dataSources, setDataSources] = useState<DataSourceOption[]>([]);
  const [loaded, setLoaded] = useState(false);

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
      message.error(resolveErrorMessage(error, t('dna.dataQuality.error.loadDataSources', 'Failed to load data source list')));
    });
  }, [loadDataSources, t]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.catalogId) {
      properties.catalogId.title = t('dna.dataQuality.title.catalogId', 'Data Source');
      properties.catalogId.oneOf = dataSources.map((ds) => ({
        const: ds.id,
        title: ds.name || ds.code || ds.id,
      }));
    }
    if (properties.ruleType) {
      properties.ruleType.oneOf = RULE_TYPES;
    }
    if (properties.severity) {
      properties.severity.oneOf = SEVERITIES;
    }
    delete properties.catalogName;
    delete properties.lastRunStatus;
    delete properties.lastRunMessage;
    delete properties.lastRunAt;
    return nextSchema;
  }, [dataSources, t]);

  const columnOverrides = useMemo(() => ({
    catalogId: {
      title: t('dna.dataQuality.title.catalogId', 'Data Source'),
      width: 150,
      render: (_: string, record: {catalogName?: string}) =>
        record.catalogName || '-',
    },
    ruleType: {
      title: t('dna.dataQuality.title.ruleType', 'Rule Type'),
      width: 130,
      render: (value?: string) => {
        const label = RULE_TYPES.find((rt) => rt.const === value)?.title ?? value;
        return <Tag color="blue">{label}</Tag>;
      },
    },
    severity: {
      title: t('dna.dataQuality.title.severity', 'Severity'),
      width: 90,
      render: (value?: string) => (
        <Tag color={SEVERITY_COLORS[value ?? ''] ?? 'default'}>{value || '-'}</Tag>
      ),
    },
    lastRunStatus: {
      title: t('dna.dataQuality.title.lastRunStatus', 'Last Status'),
      width: 100,
      render: (value?: string) =>
        value ? <Tag color={STATUS_COLORS[value] ?? 'default'}>{value}</Tag> : '-',
    },
    lastRunAt: {
      title: t('dna.dataQuality.title.lastRunAt', 'Last Run'),
      width: 170,
      render: (value?: string) =>
        value ? new Date(value).toLocaleString() : '-',
    },
    enabled: {
      title: t('dna.dataQuality.title.enabled', 'Status'),
      width: 80,
      render: (value?: boolean) => (
        <Tag color={value ? 'green' : 'red'}>{value ? t('dna.dataQuality.state.enabled', 'Enabled') : t('dna.dataQuality.state.disabled', 'Disabled')}</Tag>
      ),
    },
  }), [t]);

  return (
    <div>
      {loaded && dataSources.length === 0 ? (
        <Alert type="warning" showIcon style={{marginBottom: 16}}
          message={t('dna.dataQuality.alert.noDataSources', 'No data sources available. Please add and enable a data source first.')}
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
