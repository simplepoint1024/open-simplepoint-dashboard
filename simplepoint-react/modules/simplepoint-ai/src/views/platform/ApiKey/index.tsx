import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {post} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Modal, Space, Tag, Typography, message} from 'antd';
import {useCallback, useMemo, useState} from 'react';

const {Paragraph, Text} = Typography;

type ApiKeyRow = {
  id: string;
  scopeType?: string;
  name?: string;
  keyPrefix?: string;
  enabled?: boolean;
  expiresAt?: string;
  lastUsedAt?: string;
  usageCount?: number;
  issuedKey?: string;
};

type ApiKeyViewProps = {
  configKey?: 'platform.ai-api-keys' | 'tenant.ai-api-keys';
};

const displayDate = (value?: string) => value ? new Date(value).toLocaleString() : '-';

export const ApiKeyView = ({configKey = 'platform.ai-api-keys'}: ApiKeyViewProps) => {
  const baseConfig = api[configKey];
  const {t} = useI18n();
  const [tableKey, setTableKey] = useState(0);

  const revealIssuedKey = useCallback((result: unknown) => {
    const issuedKey = (result as ApiKeyRow | null)?.issuedKey;
    if (!issuedKey) return;
    Modal.success({
      title: t('ai.api-keys.secret.title', '请立即保存 API Key'),
      width: 640,
      content: (
        <Space direction="vertical" size={12} style={{width: '100%'}}>
          <Text type="warning">
            {t('ai.api-keys.secret.once', '完整 Key 只显示这一次，关闭后无法再次查看。')}
          </Text>
          <Paragraph copyable={{text: issuedKey}} code style={{wordBreak: 'break-all', marginBottom: 0}}>
            {issuedKey}
          </Paragraph>
        </Space>
      ),
    });
  }, [t]);

  const rotate = useCallback((rows: ApiKeyRow[]) => {
    const row = rows[0];
    if (!row?.id) return;
    Modal.confirm({
      title: t('ai.api-keys.rotate.title', '轮换 API Key'),
      content: t('ai.api-keys.rotate.confirm', '轮换后旧 Key 会立即失效，确定继续吗？'),
      okText: t('ai.api-keys.rotate.ok', '确认轮换'),
      cancelText: t('ai.api-keys.rotate.cancel', '取消'),
      onOk: async () => {
        try {
          const result = await post<ApiKeyRow>(`${baseConfig.baseUrl}/${row.id}/rotate`, {});
          revealIssuedKey(result);
          setTableKey((current) => current + 1);
          message.success(t('ai.api-keys.rotate.success', 'API Key 已轮换'));
        } catch (error) {
          message.error(resolveApiErrorMessage(
            error,
            t('ai.api-keys.rotate.failed', 'API Key 轮换失败'),
          ));
          throw error;
        }
      },
    });
  }, [baseConfig.baseUrl, revealIssuedKey, t]);

  const customButtonEvents = useMemo<Record<string, (
    selectedRowKeys: React.Key[],
    selectedRows: ApiKeyRow[],
    props: TableButtonProps,
  ) => void>>(() => ({
    rotate: (_keys, rows) => rotate(rows),
  }), [rotate]);

  const formSchemaTransform = useCallback((schema: any) => {
    const nextSchema = structuredClone(schema ?? {});
    const properties = nextSchema.properties ?? {};
    const readOnlyFields = [
      'scopeType', 'tenantId', 'keyPrefix', 'secretHash', 'lastUsedAt', 'usageCount',
      'revokedAt', 'issuedKey', 'createdAt', 'updatedAt', 'createdBy', 'updatedBy', 'deletedAt',
    ];
    readOnlyFields.forEach((field) => delete properties[field]);
    if (Array.isArray(nextSchema.required)) {
      nextSchema.required = nextSchema.required.filter((field: string) => !readOnlyFields.includes(field));
    }
    return nextSchema;
  }, []);

  const columnOverrides = useMemo(() => ({
    scopeType: {
      width: 105,
      render: (value: string) => (
        <Tag color={value === 'TENANT' ? 'blue' : 'purple'}>{value || '-'}</Tag>
      ),
    },
    keyPrefix: {
      width: 175,
      render: (value: string) => value ? <Text code>{value}…</Text> : '-',
    },
    enabled: {
      width: 90,
      render: (value: boolean) => (
        <Tag color={value ? 'green' : 'default'}>
          {value
            ? t('ai.api-keys.status.enabled', '已启用')
            : t('ai.api-keys.status.disabled', '已禁用')}
        </Tag>
      ),
    },
    expiresAt: {width: 175, render: displayDate},
    lastUsedAt: {width: 175, render: displayDate},
    usageCount: {width: 110},
  }), [t]);

  return (
    <div style={{height: '100%', display: 'flex', flexDirection: 'column', gap: 12}}>
      <Alert
        type="info"
        showIcon
        message={t('ai.api-keys.guide.title', 'OpenAI / Anthropic 兼容模型 API')}
        description={(
          <Space direction="vertical" size={2}>
            <Text>
              {t('ai.api-keys.guide.openai', 'OpenAI：POST /ai/v1/chat/completions，使用 Authorization: Bearer <API_KEY>')}
            </Text>
            <Text>
              {t('ai.api-keys.guide.anthropic', 'Anthropic：POST /ai/v1/messages，使用 x-api-key: <API_KEY>')}
            </Text>
            <Text type="secondary">
              {t('ai.api-keys.guide.models', '可先调用 GET /ai/v1/models 获取当前 Key 可访问的模型 ID。')}
            </Text>
          </Space>
        )}
      />
      <div style={{flex: 1, minHeight: 0}}>
        <SimpleTable
          key={tableKey}
          {...baseConfig}
          customButtonEvents={customButtonEvents}
          formSchemaTransform={formSchemaTransform}
          columnOverrides={columnOverrides}
          afterSubmit={({result}) => revealIssuedKey(result)}
        />
      </div>
    </div>
  );
};

export default ApiKeyView;
