import api from '@/api';
import type {TableButtonProps} from '@simplepoint/components/Table';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Modal, Table, Tag, Typography, message} from 'antd';
import React, {useCallback, useEffect, useMemo, useState} from 'react';

const {Text} = Typography;
type ProviderRow = {
  id?: string;
  name?: string;
  code?: string;
  providerType?: string;
  lastStatus?: string;
  hasApiKey?: boolean;
  scopeType?: string;
  tenantId?: string;
};

type DiscoveredModel = {
  modelId: string;
  displayName?: string;
  modelType?: string;
  ownedBy?: string;
};

type ConnectionTestResult = {
  discoveredModelCount?: number;
  testedAt?: string;
  message?: string;
};

type ModelSyncResult = {
  discovered?: number;
  created?: number;
  updated?: number;
  unavailable?: number;
};

const providerTypeLabels: Record<string, string> = {
  OPENAI: 'OpenAI',
  ANTHROPIC: 'Anthropic',
  OPENAI_COMPATIBLE: 'OpenAI Compatible',
};

const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return fallback;
};

type ModelProviderViewProps = {
  configKey?: 'platform.ai-providers' | 'tenant.ai-providers';
};

export const ModelProviderView = ({configKey = 'platform.ai-providers'}: ModelProviderViewProps) => {
  const baseConfig = api[configKey];
  const {t, ensure, locale} = useI18n();
  const [tableKey, setTableKey] = useState(0);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const refresh = useCallback(() => setTableKey((value) => value + 1), []);

  const requireProvider = useCallback((rows: ProviderRow[]) => {
    const provider = rows?.[0];
    if (!provider?.id) {
      message.warning(t('ai.providers.page.warning.select', '请选择一个模型供应商'));
      return null;
    }
    return provider;
  }, [t]);

  const handleTest = useCallback(async (rows: ProviderRow[]) => {
    const provider = requireProvider(rows);
    if (!provider) return;
    const hide = message.loading(t('ai.providers.page.progress.testing', '正在测试连接...'), 0);
    try {
      const result = await post<ConnectionTestResult>(`${baseConfig.baseUrl}/${provider.id}/test`, {});
      hide();
      message.success(result.message || t('ai.providers.page.success.test', '连接测试成功'));
      Modal.success({
        title: t('ai.providers.page.modal.test.title', '连接测试成功'),
        content: t(
          'ai.providers.page.modal.test.content',
          `已获取 ${result.discoveredModelCount ?? 0} 个可用模型`,
          {count: result.discoveredModelCount ?? 0},
        ),
      });
      refresh();
    } catch (error) {
      hide();
      message.error(resolveErrorMessage(error, t('ai.providers.page.error.test', '连接测试失败')));
      refresh();
    }
  }, [refresh, requireProvider, t]);

  const handleDiscover = useCallback(async (rows: ProviderRow[]) => {
    const provider = requireProvider(rows);
    if (!provider) return;
    const hide = message.loading(t('ai.providers.page.progress.discovering', '正在获取可用模型...'), 0);
    try {
      const models = await get<DiscoveredModel[]>(`${baseConfig.baseUrl}/${provider.id}/models/discover`);
      hide();
      Modal.info({
        width: 860,
        title: t('ai.providers.page.modal.discover.title', '供应商可用模型'),
        content: (
          <Table
            style={{marginTop: 16}}
            size="small"
            rowKey="modelId"
            pagination={{pageSize: 10, hideOnSinglePage: true}}
            scroll={{y: 420}}
            dataSource={models}
            columns={[
              {title: t('ai.models.title.modelId', '模型 ID'), dataIndex: 'modelId'},
              {title: t('ai.models.title.modelType', '模型类型'), dataIndex: 'modelType', width: 140},
              {title: t('ai.models.title.ownedBy', '所有者'), dataIndex: 'ownedBy', width: 160},
            ]}
          />
        ),
      });
    } catch (error) {
      hide();
      message.error(resolveErrorMessage(error, t('ai.providers.page.error.discover', '获取模型列表失败')));
    }
  }, [requireProvider, t]);

  const handleSync = useCallback(async (rows: ProviderRow[]) => {
    const provider = requireProvider(rows);
    if (!provider) return;
    const hide = message.loading(t('ai.providers.page.progress.syncing', '正在同步模型列表...'), 0);
    try {
      const result = await post<ModelSyncResult>(`${baseConfig.baseUrl}/${provider.id}/models/sync`, {});
      hide();
      message.success(t(
        'ai.providers.page.success.sync',
        `同步完成：新增 ${result.created ?? 0}，更新 ${result.updated ?? 0}`,
        {created: result.created ?? 0, updated: result.updated ?? 0},
      ));
      refresh();
    } catch (error) {
      hide();
      message.error(resolveErrorMessage(error, t('ai.providers.page.error.sync', '同步模型列表失败')));
      refresh();
    }
  }, [refresh, requireProvider, t]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.providerType) {
      properties.providerType.oneOf = Object.entries(providerTypeLabels).map(([value, title]) => ({
        const: value,
        title,
      }));
    }
    if (properties.apiKey) {
      properties.apiKey.description = t(
        'ai.providers.page.form.apiKey.description',
        '编辑时留空将保留已有凭证。凭证只写入，不会从服务端回传。',
      );
    }
    delete properties.hasApiKey;
    delete properties.lastStatus;
    delete properties.lastMessage;
    delete properties.lastTestedAt;
    delete properties.lastSyncedAt;
    delete properties.scopeType;
    delete properties.tenantId;
    if (configKey === 'tenant.ai-providers') {
      delete properties.allowPrivateNetwork;
    }
    return nextSchema;
  }, [configKey, t]);

  const columnOverrides = useMemo(() => ({
    scopeType: {
      width: 110,
      render: (value: string) => (
        <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>
          {t(`ai.scope.${value}`, value || '-')}
        </Tag>
      ),
    },
    providerType: {
      width: 180,
      render: (value: string) => providerTypeLabels[value] || value || '-',
    },
    baseUrl: {width: 300, ellipsis: true},
    enabled: {
      width: 100,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'default'}>
          {value ? t('ai.common.enabled', '已启用') : t('ai.common.disabled', '已禁用')}
        </Tag>
      ),
    },
    autoSyncEnabled: {
      width: 110,
      render: (value: boolean) => value
        ? <Tag color="blue">{t('ai.providers.page.autoSync.on', '自动同步')}</Tag>
        : <Text type="secondary">{t('ai.providers.page.autoSync.off', '手动同步')}</Text>,
    },
    lastStatus: {
      width: 110,
      render: (value: string) => {
        const color = value === 'SUCCESS' || value === 'SYNCED' ? 'green' : value === 'FAILED' ? 'red' : 'default';
        return <Tag color={color}>{value || t('ai.common.notTested', '未测试')}</Tag>;
      },
    },
  }), [t]);

  const customButtonEvents: Record<string, (
    selectedRowKeys: React.Key[],
    selectedRows: ProviderRow[],
    props: TableButtonProps,
  ) => void> = {
    test: (_keys, rows) => void handleTest(rows),
    discover: (_keys, rows) => void handleDiscover(rows),
    sync: (_keys, rows) => void handleSync(rows),
  };

  return (
    <SimpleTable
      key={tableKey}
      {...baseConfig}
      customButtonEvents={customButtonEvents}
      formSchemaTransform={formSchemaTransform}
      columnOverrides={columnOverrides}
    />
  );
};

export default ModelProviderView;
