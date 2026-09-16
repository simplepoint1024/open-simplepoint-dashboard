import {ReloadOutlined} from '@ant-design/icons';
import {get} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Button, Card, Descriptions, Skeleton, Space, Tag, Typography} from 'antd';
import {useCallback, useEffect, useState} from 'react';

const {Text} = Typography;

type GatewayStatus = {
  service: string;
  status: string;
  protocolBaseline: string;
  sdkVersion: string;
  instanceId: string;
  checkedAt: string;
};

type McpGatewayStatusCardProps = {
  statusUrl: string;
};

const formatCheckedAt = (value?: string) => {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

const McpGatewayStatusCard = ({statusUrl}: McpGatewayStatusCardProps) => {
  const {t} = useI18n();
  const [status, setStatus] = useState<GatewayStatus>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  const load = useCallback(async () => {
    setLoading(true);
    setError(undefined);
    try {
      setStatus(await get<GatewayStatus>(statusUrl, undefined, {timeoutMs: 10_000}));
    } catch (loadError) {
      setStatus(undefined);
      setError(resolveApiErrorMessage(
        loadError,
        t('ai.mcp.gateway.error.load', 'MCP Gateway 状态加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [statusUrl, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const available = status?.status === 'UP';

  return (
    <Card
      size="small"
      title={(
        <Space size={8}>
          <span>{t('ai.mcp.gateway.title', 'MCP Gateway')}</span>
          {!loading && !error && (
            <Tag color={available ? 'success' : 'error'}>
              {available
                ? t('ai.mcp.gateway.status.up', '运行正常')
                : t('ai.mcp.gateway.status.down', '不可用')}
            </Tag>
          )}
        </Space>
      )}
      extra={(
        <Button
          type="text"
          size="small"
          icon={<ReloadOutlined spin={loading}/>}
          disabled={loading}
          onClick={() => void load()}
        >
          {t('ai.mcp.action.refresh', '刷新')}
        </Button>
      )}
    >
      {loading && !status ? (
        <Skeleton active title={false} paragraph={{rows: 2}}/>
      ) : error ? (
        <Alert
          showIcon
          type="warning"
          message={t('ai.mcp.gateway.error.summary', 'Gateway 状态暂时不可用')}
          description={error}
          action={(
            <Button size="small" onClick={() => void load()}>
              {t('ai.mcp.action.retry', '重试')}
            </Button>
          )}
        />
      ) : (
        <Descriptions size="small" column={{xs: 1, sm: 2, lg: 4}}>
          <Descriptions.Item label={t('ai.mcp.gateway.service', '服务')}>
            {status?.service || '-'}
          </Descriptions.Item>
          <Descriptions.Item label={t('ai.mcp.gateway.protocol', 'MCP 协议基线')}>
            <Text code>{status?.protocolBaseline || '-'}</Text>
          </Descriptions.Item>
          <Descriptions.Item label={t('ai.mcp.gateway.instance', '实例')}>
            <Text ellipsis={{tooltip: status?.instanceId}} style={{maxWidth: 220}}>
              {status?.instanceId || '-'}
            </Text>
          </Descriptions.Item>
          <Descriptions.Item label={t('ai.mcp.gateway.checkedAt', '检查时间')}>
            {formatCheckedAt(status?.checkedAt)}
          </Descriptions.Item>
        </Descriptions>
      )}
    </Card>
  );
};

export default McpGatewayStatusCard;
