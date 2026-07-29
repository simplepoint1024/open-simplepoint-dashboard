import api from '@/api';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Button, Card, Descriptions, Space, Spin, Tag, message} from 'antd';
import {useCallback, useEffect, useState} from 'react';

type GatewayStatus = {
  service: string;
  status: string;
  protocolBaseline: string;
  sdkVersion: string;
  instanceId: string;
  checkedAt: string;
};

const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return fallback;
};

const McpGateway = () => {
  const config = api['ai-workbench.mcp-gateway'];
  const {t, ensure, locale} = useI18n();
  const [status, setStatus] = useState<GatewayStatus>();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [config.i18nNamespaces, ensure, locale]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setStatus(await get<GatewayStatus>(config.statusUrl));
    } catch (error) {
      setStatus(undefined);
      message.error(resolveErrorMessage(
        error,
        t('ai.mcp.gateway.error.load', 'MCP Gateway 状态加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [config.statusUrl, t]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <Space direction="vertical" size={16} style={{display: 'flex'}}>
      <Alert
        showIcon
        type="info"
        message={t('ai.mcp.gateway.notice.title', '独立 MCP 协议网关')}
        description={t(
          'ai.mcp.gateway.notice.description',
          'Gateway 与 AI 主服务进程隔离，负责 MCP 协议终止、远程连接和工具调用。',
        )}
      />
      <Card
        title={t('ai.mcp.gateway.title', 'MCP Gateway')}
        extra={<Button onClick={() => void load()}>{t('ai.mcp.action.refresh', '刷新')}</Button>}
      >
        <Spin spinning={loading}>
          <Descriptions bordered column={{xs: 1, sm: 1, md: 2}}>
            <Descriptions.Item label={t('ai.mcp.gateway.status', '状态')}>
              <Tag color={status?.status === 'UP' ? 'green' : 'red'}>
                {status?.status || 'DOWN'}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.mcp.gateway.service', '服务')}>
              {status?.service || '-'}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.mcp.gateway.protocol', 'MCP 协议基线')}>
              {status?.protocolBaseline || '-'}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.mcp.gateway.sdk', '官方 Java SDK')}>
              {status?.sdkVersion || '-'}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.mcp.gateway.instance', '实例')}>
              {status?.instanceId || '-'}
            </Descriptions.Item>
            <Descriptions.Item label={t('ai.mcp.gateway.checkedAt', '检查时间')}>
              {status?.checkedAt || '-'}
            </Descriptions.Item>
          </Descriptions>
        </Spin>
      </Card>
    </Space>
  );
};

export default McpGateway;
