import api from '@/api';
import type {TableButtonProps} from '@simplepoint/components/Table';
import SimpleTable from '@simplepoint/components/SimpleTable';
import DataTable from '@simplepoint/components/DataTable';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {App, Tag, Typography} from 'antd';
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {modelTypeLabel, resourceScopeLabel} from '../modelLabels';
import {
  resolveProviderMessage,
  resolveProviderOperationError,
  resolveProviderStatus,
} from './messageCodes';

const {Text} = Typography;
type ProviderRow = {
  id?: string;
  name?: string;
  code?: string;
  vendor?: string;
  lastStatus?: string;
  lastMessage?: string;
  hasApiKey?: boolean;
  scopeType?: string;
  tenantId?: string;
};

type DiscoveredModel = {
  modelId: string;
  displayName?: string;
  modelType?: string;
  ownedBy?: string;
  pricing?: {
    currency?: string;
    inputTokenPrice?: number;
    cachedInputTokenPrice?: number;
    outputTokenPrice?: number;
    requestPrice?: number;
  };
};

type ConnectionTestResult = {
  discoveredModelCount?: number;
  testedAt?: string;
  messageCode?: string;
};

type ModelSyncResult = {
  discovered?: number;
  created?: number;
  updated?: number;
  unavailable?: number;
};

const providerVendorLabels: Record<string, string> = {
  OPENAI: 'OpenAI',
  ANTHROPIC: 'Anthropic',
  GOOGLE_GEMINI: 'Google Gemini',
  AZURE_OPENAI: 'Azure OpenAI',
  MISTRAL: 'Mistral AI',
  GROQ: 'Groq',
  DEEPSEEK: 'DeepSeek',
  XAI: 'xAI',
  OPENROUTER: 'OpenRouter',
  TOGETHER_AI: 'Together AI',
  FIREWORKS_AI: 'Fireworks AI',
  ALIBABA_QWEN: 'Alibaba Qwen / DashScope',
  MOONSHOT: 'Moonshot / Kimi',
  MINIMAX: 'MiniMax',
  STEPFUN: 'StepFun',
  ZHIPU_AI: 'Zhipu AI / GLM',
  BAIDU_QIANFAN: 'Baidu Qianfan',
  TENCENT_HUNYUAN: 'Tencent Hunyuan',
  VOLCENGINE_DOUBAO: 'Volcengine Doubao',
  SILICONFLOW: 'SiliconFlow',
  NVIDIA_NIM: 'NVIDIA NIM',
  HUGGING_FACE: 'Hugging Face',
  COHERE: 'Cohere',
  OLLAMA: 'Ollama',
  LM_STUDIO: 'LM Studio',
  VLLM: 'vLLM',
  CUSTOM: 'Custom',
};

const Providers = () => {
  const baseConfig = api['ai-workbench.providers'];
  const {t, ensure, locale} = useI18n();
  const {message, modal} = App.useApp();
  const [tableKey, setTableKey] = useState(0);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const refresh = useCallback(() => setTableKey((value) => value + 1), []);

  const requireProvider = useCallback((rows: ProviderRow[]) => {
    const provider = rows?.[0];
    if (!provider?.id) {
      message.warning(t('ai.providers.page.warning.select', '请选择一个模型接入'));
      return null;
    }
    return provider;
  }, [message, t]);

  const handleTest = useCallback(async (rows: ProviderRow[]) => {
    const provider = requireProvider(rows);
    if (!provider) return;
    const hide = message.loading(t('ai.providers.page.progress.testing', '正在测试连接...'), 0);
    try {
      const result = await post<ConnectionTestResult>(`${baseConfig.baseUrl}/${provider.id}/test`, {});
      hide();
      const descriptor = resolveProviderMessage(result.messageCode) ?? {
        key: 'ai.providers.page.success.test',
        fallback: '连接测试成功',
      };
      message.success(t(descriptor.key, descriptor.fallback));
      modal.success({
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
      const descriptor = resolveProviderOperationError(error, {
        key: 'ai.providers.page.error.test',
        fallback: '连接测试失败',
      });
      message.error(t(descriptor.key, descriptor.fallback));
      refresh();
    }
  }, [baseConfig.baseUrl, message, modal, refresh, requireProvider, t]);

  const handleDiscover = useCallback(async (rows: ProviderRow[]) => {
    const provider = requireProvider(rows);
    if (!provider) return;
    const hide = message.loading(t('ai.providers.page.progress.discovering', '正在获取可用模型...'), 0);
    try {
      const models = await get<DiscoveredModel[]>(`${baseConfig.baseUrl}/${provider.id}/models/discover`);
      hide();
      modal.info({
        width: 1280,
        title: t('ai.providers.page.modal.discover.title', '接入可用模型'),
        content: (
          <DataTable<DiscoveredModel>
            style={{marginTop: 16}}
            size="small"
            rowKey="modelId"
            pagination={{pageSize: 10, hideOnSinglePage: true}}
            scroll={{y: 420}}
            dataSource={models}
            columns={[
              {title: t('ai.models.title.modelId', '模型 ID'), dataIndex: 'modelId'},
              {
                title: t('ai.models.title.modelType', '模型类型'),
                dataIndex: 'modelType',
                width: 140,
                render: (value: string) => modelTypeLabel(t, value),
              },
              {title: t('ai.models.title.ownedBy', '所有者'), dataIndex: 'ownedBy', width: 160},
              {
                title: t('ai.models.title.billingCurrency', '币种'),
                dataIndex: ['pricing', 'currency'],
                width: 80,
              },
              {
                title: t('ai.models.title.inputTokenPrice', '输入 / 百万 Token'),
                dataIndex: ['pricing', 'inputTokenPrice'],
                width: 150,
              },
              {
                title: t('ai.models.title.outputTokenPrice', '输出 / 百万 Token'),
                dataIndex: ['pricing', 'outputTokenPrice'],
                width: 150,
              },
            ]}
          />
        ),
      });
    } catch (error) {
      hide();
      const descriptor = resolveProviderOperationError(error, {
        key: 'ai.providers.page.error.discover',
        fallback: '获取模型列表失败',
      });
      message.error(t(descriptor.key, descriptor.fallback));
    }
  }, [baseConfig.baseUrl, message, modal, requireProvider, t]);

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
      const descriptor = resolveProviderOperationError(error, {
        key: 'ai.providers.page.error.sync',
        fallback: '同步模型列表失败',
      });
      message.error(t(descriptor.key, descriptor.fallback));
      refresh();
    }
  }, [baseConfig.baseUrl, message, refresh, requireProvider, t]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.vendor) {
      properties.vendor.oneOf = Object.entries(providerVendorLabels).map(([value, title]) => ({
        const: value,
        title,
      }));
      delete properties.vendor.enum;
      delete properties.vendor.enumNames;
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
    return nextSchema;
  }, [t]);

  const columnOverrides = useMemo(() => ({
    scopeType: {
      width: 110,
      render: (value: string) => (
        <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>
          {resourceScopeLabel(t, value)}
        </Tag>
      ),
    },
    vendor: {
      width: 190,
      render: (value: string) => providerVendorLabels[value]
        || t('ai.providers.vendor.unknown', '未知模型厂商'),
    },
    baseUrl: {width: 300, ellipsis: true},
    modelDiscoveryUrl: {width: 320, ellipsis: true},
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
        const descriptor = resolveProviderStatus(value);
        const color = descriptor.tone === 'success'
          ? 'green'
          : descriptor.tone === 'error' ? 'red' : 'default';
        return (
          <Tag color={color}>
            {t(descriptor.key, descriptor.fallback)}
          </Tag>
        );
      },
    },
    lastMessage: {
      width: 180,
      render: (value: string) => {
        const descriptor = resolveProviderMessage(value);
        return descriptor
          ? t(descriptor.key, descriptor.fallback)
          : '-';
      },
    },
  }), [t]);

  const errorMessageResolver = useCallback((error: unknown) => {
    const descriptor = resolveProviderOperationError(error);
    return t(descriptor.key, descriptor.fallback);
  }, [t]);

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
      errorMessageResolver={errorMessageResolver}
    />
  );
};

export default Providers;
