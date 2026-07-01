import api from '@/api';
import type {TableButtonProps} from '@simplepoint/components/Table';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {Alert, Modal, Tag, Typography, message} from 'antd';
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {formatDateTime, resolveErrorMessage} from '../shared';

const {Paragraph, Text} = Typography;
const baseConfig = api['platform.dna-data-sources'];
const driverBaseConfig = api['platform.dna-drivers'];

type JdbcDriverOption = {
  id: string;
  code?: string;
  name?: string;
  enabled?: boolean;
};

type JdbcDataSourceRow = {
  id?: string;
  driverId?: string;
  driverCode?: string;
  driverName?: string;
  jdbcUrl?: string;
  lastConnectStatus?: string;
  databaseProductName?: string;
};

type JdbcDataSourceConnectionResult = {
  driverCode?: string;
  jdbcUrl?: string;
  testedAt?: string;
  message?: string;
  databaseProductName?: string;
  databaseProductVersion?: string;
};

const connectStatusColorMap: Record<string, string> = {
  SUCCESS: 'green',
  FAILED: 'red',
};

const resolveDriverLabel = (driver: JdbcDriverOption, disabledSuffix: string) => {
  const primary = driver.name || driver.code || driver.id;
  const secondary = driver.code && driver.code !== primary ? ` (${driver.code})` : '';
  const disabled = driver.enabled === false ? disabledSuffix : '';
  return `${primary}${secondary}${disabled}`;
};

const App = () => {
  const {t, ensure, locale} = useI18n();
  const [tableKey, setTableKey] = useState(0);
  const [driverOptions, setDriverOptions] = useState<JdbcDriverOption[]>([]);
  const [driversLoaded, setDriversLoaded] = useState(false);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const refreshTable = useCallback(() => {
    setTableKey((value) => value + 1);
  }, []);

  const loadDrivers = useCallback(async () => {
    const page = await get<Page<JdbcDriverOption>>(driverBaseConfig.baseUrl, {page: 0, size: 200});
    setDriverOptions(page.content ?? []);
    setDriversLoaded(true);
  }, []);

  useEffect(() => {
    void loadDrivers().catch((error) => {
      setDriversLoaded(true);
      message.error(resolveErrorMessage(error, t('dna.dataSources.page.error.loadDrivers', '驱动列表加载失败')));
    });
  }, [loadDrivers, t]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.driverId) {
      properties.driverId.title = t('dna.dataSources.title.driverId', '驱动');
      properties.driverId.oneOf = driverOptions.map((driver) => ({
        const: driver.id,
        title: resolveDriverLabel(driver, t('dna.dataSources.page.driver.disabledSuffix', ' - 已禁用')),
      }));
      properties.driverId.description = driverOptions.length > 0
        ? t('dna.dataSources.page.form.driver.description.available', '请选择已配置的 JDBC 驱动')
        : t('dna.dataSources.page.form.driver.description.empty', '请先在驱动管理页面新增驱动');
    }
    delete properties.driverCode;
    delete properties.driverName;
    return nextSchema;
  }, [driverOptions, t]);

  const columnOverrides = useMemo(() => ({
    driverId: {
      title: t('dna.dataSources.page.column.driver', '驱动'),
      width: 220,
      render: (value: string, record: JdbcDataSourceRow) => record.driverName || record.driverCode || value || '-',
    },
    jdbcUrl: {
      width: 320,
      ellipsis: true,
    },
    lastConnectStatus: {
      width: 120,
      render: (value: string) => {
        const text = value === 'SUCCESS'
          ? t('dna.dataSources.page.status.success', '成功')
          : value === 'FAILED'
            ? t('dna.dataSources.page.status.failed', '失败')
            : t('dna.dataSources.page.state.notTested', '未测试');
        return <Tag color={connectStatusColorMap[value] || 'default'}>{text}</Tag>;
      },
    },
    databaseProductName: {
      width: 180,
      render: (value: string) => value || '-',
    },
  }), [t]);

  const handleConnect = useCallback(async (_keys: React.Key[], rows: JdbcDataSourceRow[]) => {
    const dataSource = rows?.[0];
    if (!dataSource?.id) {
      message.warning(t('dna.dataSources.page.warning.selectDataSource', '请选择要测试的数据源'));
      return;
    }

    const hide = message.loading(t('dna.dataSources.page.progress.connecting', '正在测试连接...'), 0);
    try {
      const result = await post<JdbcDataSourceConnectionResult>(`${baseConfig.baseUrl}/${dataSource.id}/connect`, {});
      hide();
      message.success(t('dna.dataSources.page.success.connect', '数据源连接成功'));
      Modal.success({
        title: t('dna.dataSources.page.modal.connectSuccess.title', '连接测试成功'),
        content: (
          <div style={{marginTop: 16}}>
            <Paragraph>
              <Text strong>{t('dna.dataSources.page.field.driver', '驱动')}：</Text>
              {dataSource.driverName || result.driverCode || dataSource.driverCode || '-'}
            </Paragraph>
            <Paragraph>
              <Text strong>{t('dna.dataSources.page.field.jdbcUrl', 'JDBC URL')}：</Text>
              <Text code>{result.jdbcUrl || dataSource.jdbcUrl || '-'}</Text>
            </Paragraph>
            <Paragraph>
              <Text strong>{t('dna.dataSources.page.field.databaseProductName', '数据库产品')}：</Text>
              {result.databaseProductName || '-'}
            </Paragraph>
            <Paragraph>
              <Text strong>{t('dna.dataSources.page.field.databaseProductVersion', '数据库版本')}：</Text>
              {result.databaseProductVersion || '-'}
            </Paragraph>
            <Paragraph>
              <Text strong>{t('dna.dataSources.page.field.lastTestedAt', '测试时间')}：</Text>
              {formatDateTime(result.testedAt)}
            </Paragraph>
            <Paragraph style={{marginBottom: 0}}>
              <Text strong>{t('dna.dataSources.page.field.result', '结果')}：</Text>
              {result.message || t('dna.dataSources.page.state.connectSuccess', '连接成功')}
            </Paragraph>
          </div>
        ),
      });
      refreshTable();
    } catch (error) {
      hide();
      message.error(resolveErrorMessage(error, t('dna.dataSources.page.error.connect', '数据源连接失败')));
    }
  }, [refreshTable, t]);

  const customButtonEvents: Record<string, (selectedRowKeys: React.Key[], selectedRows: JdbcDataSourceRow[],
    props: TableButtonProps) => void> = {
    connect: (selectedRowKeys, selectedRows) => {
      void handleConnect(selectedRowKeys, selectedRows);
    },
  };

  return (
    <div>
      {driversLoaded && driverOptions.length === 0 ? (
        <Alert
          type="warning"
          showIcon
          style={{marginBottom: 16}}
          message={t('dna.dataSources.page.alert.noDrivers.title', '当前还没有可用驱动')}
          description={t(
            'dna.dataSources.page.alert.noDrivers.description',
            '请先到驱动管理页面新增并下载 JDBC 驱动，再回来配置数据源。',
          )}
        />
      ) : null}
      <SimpleTable
        key={tableKey}
        {...baseConfig}
        customButtonEvents={customButtonEvents}
        formSchemaTransform={formSchemaTransform}
        columnOverrides={columnOverrides}
      />
    </div>
  );
};

export default App;
