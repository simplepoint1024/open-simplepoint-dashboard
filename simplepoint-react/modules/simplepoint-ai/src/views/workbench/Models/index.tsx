import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {Tag, message} from 'antd';
import {useCallback, useEffect, useMemo, useState} from 'react';
import ModelDebugDialog, {type DebugModel} from './ModelDebugDialog';

type ProviderOption = {
  id: string;
  name?: string;
  code?: string;
  enabled?: boolean;
};

type ModelRow = {
  id: string;
  modelId?: string;
  displayName?: string;
  modelType?: string;
  enabled?: boolean;
  available?: boolean;
  providerName?: string;
  scopeType?: string;
  tenantId?: string;
};

const modelTypes = ['LLM', 'EMBEDDING', 'RERANK', 'IMAGE', 'AUDIO', 'MODERATION', 'MULTIMODAL', 'OTHER'];

const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return fallback;
};

const Models = () => {
  const baseConfig = api['ai-workbench.models'];
  const providerBaseConfig = api['ai-workbench.providers'];
  const {t, ensure, locale} = useI18n();
  const [providers, setProviders] = useState<ProviderOption[]>([]);
  const [debugModel, setDebugModel] = useState<DebugModel>();

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  useEffect(() => {
    void get<Page<ProviderOption>>(providerBaseConfig.baseUrl, {page: 0, size: 500})
      .then((page) => setProviders(page.content ?? []))
      .catch((error) => message.error(resolveErrorMessage(
        error,
        t('ai.models.page.error.loadProviders', '供应商列表加载失败'),
      )));
  }, [t]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema?.properties ?? {};
    if (properties.providerId) {
      properties.providerId.oneOf = providers.map((provider) => ({
        const: provider.id,
        title: `${provider.name || provider.code || provider.id}${provider.enabled === false ? ` (${t('ai.common.disabled', '已禁用')})` : ''}`,
      }));
    }
    if (properties.modelType) {
      properties.modelType.oneOf = modelTypes.map((value) => ({
        const: value,
        title: t(`ai.models.type.${value}`, value),
      }));
    }
    if (properties.billingEnabled) {
      properties.billingEnabled.default = false;
    }
    if (properties.billingCurrency) {
      properties.billingCurrency.default = 'USD';
    }
    delete properties.providerName;
    delete properties.available;
    delete properties.discovered;
    delete properties.ownedBy;
    delete properties.releasedAt;
    delete properties.scopeType;
    delete properties.tenantId;
    return nextSchema;
  }, [providers, t]);

  const columnOverrides = useMemo(() => ({
    scopeType: {
      width: 110,
      render: (value: string) => (
        <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>
          {t(`ai.scope.${value}`, value || '-')}
        </Tag>
      ),
    },
    providerId: {
      width: 200,
      render: (value: string, record: ModelRow) => record.providerName || value || '-',
    },
    modelId: {width: 280, ellipsis: true},
    modelType: {
      width: 140,
      render: (value: string) => <Tag color="blue">{t(`ai.models.type.${value}`, value || '-')}</Tag>,
    },
    enabled: {
      width: 100,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'default'}>
          {value ? t('ai.common.enabled', '已启用') : t('ai.common.disabled', '已禁用')}
        </Tag>
      ),
    },
    available: {
      width: 100,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'red'}>
          {value ? t('ai.models.page.available', '可用') : t('ai.models.page.unavailable', '不可用')}
        </Tag>
      ),
    },
    billingEnabled: {
      width: 110,
      render: (value: boolean) => (
        <Tag color={value ? 'gold' : 'default'}>
          {value ? t('ai.common.enabled', '已启用') : t('ai.common.disabled', '已禁用')}
        </Tag>
      ),
    },
    billingCurrency: {width: 100},
    inputTokenPrice: {width: 170},
    outputTokenPrice: {width: 170},
  }), [t]);

  const customButtonEvents = useMemo<Record<string, (
    selectedRowKeys: React.Key[],
    selectedRows: ModelRow[],
    props: TableButtonProps,
  ) => void>>(() => ({
    debug: (_keys, rows) => {
      const model = rows[0];
      if (!model?.id) return;
      if (!['LLM', 'MULTIMODAL'].includes(model.modelType || '')
          || model.enabled === false
          || model.available === false) {
        message.warning(t(
          'ai.model-debug.unavailable',
          '仅可调试已启用且可用的语言或多模态模型',
        ));
        return;
      }
      setDebugModel(model);
    },
  }), [t]);

  return (
    <>
      <SimpleTable
        {...baseConfig}
        formSchemaTransform={formSchemaTransform}
        columnOverrides={columnOverrides}
        customButtonEvents={customButtonEvents}
      />
      <ModelDebugDialog
        model={debugModel}
        open={Boolean(debugModel)}
        onClose={() => setDebugModel(undefined)}
      />
    </>
  );
};

export default Models;
