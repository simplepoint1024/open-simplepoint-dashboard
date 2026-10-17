import {formatDateTime} from '@simplepoint/shared/format/temporal';
import {ReloadOutlined} from '@simplepoint/shared/types/icon';
import {get, useData} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, Card, Descriptions, Skeleton, Space, Tag, Typography} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';

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
  return Number.isNaN(date.getTime()) ? value : formatDateTime(date);
};

const McpGatewayStatusCard = ({statusUrl}: McpGatewayStatusCardProps) => {
  const {t} = useI18n();
  const query = useData(['mcp', 'gateway-status', statusUrl], ({signal}) =>
    get<GatewayStatus>(statusUrl, undefined, {timeoutMs: 10_000, signal, notifyOnError: false}), {retry: false});
  const status = query.data;
  const loading = query.isPending || query.isFetching;
  const error = query.error ? resolveApiErrorMessage(query.error,
    t('ai.mcp.gateway.error.load', 'MCP Gateway 状态加载失败')) : undefined;

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
        <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'refresh', sort: 1, text: t('ai.mcp.action.refresh', '刷新'), type: 'text', size: 'small',
            icon: <ReloadOutlined spin={loading}/>, disabled: loading},
        ]} onButtonEvents={{refresh: () => void query.refetch()}}/>
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
            <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
              {key: 'retry', sort: 1, text: t('ai.mcp.action.retry', '重试'), size: 'small'},
            ]} onButtonEvents={{retry: () => void query.refetch()}}/>
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
