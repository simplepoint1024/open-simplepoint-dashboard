import api from '@/api';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Badge, Button, Card, Col, Row, Spin, Statistic, Typography, message} from 'antd';
import {useCallback, useEffect, useState} from 'react';
import {resolveErrorMessage} from '../shared';

const dataSourceConfig = api['platform.dna-data-sources'];

type HealthItem = {
  dataSourceId: string;
  dataSourceCode: string;
  dataSourceName: string;
  driverName: string;
  status: 'UP' | 'DOWN';
  responseTimeMs: number;
  errorMessage?: string;
};

const App = () => {
  const {ensure, locale} = useI18n();
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<HealthItem[]>([]);

  useEffect(() => {
    void ensure(dataSourceConfig.i18nNamespaces);
  }, [ensure, locale]);

  const loadHealth = useCallback(async () => {
    setLoading(true);
    try {
      const result = await get<HealthItem[]>(`${dataSourceConfig.baseUrl}/health`);
      setItems(result ?? []);
    } catch (error) {
      message.error(resolveErrorMessage(error, '健康检查失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadHealth();
  }, [loadHealth]);

  const online = items.filter((i) => i.status === 'UP').length;
  const offline = items.filter((i) => i.status === 'DOWN').length;

  return (
    <Spin spinning={loading}>
      <Row gutter={[16, 16]} style={{marginBottom: 24}}>
        <Col span={8}>
          <Card>
            <Statistic title="数据源总数" value={items.length} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic title="在线" value={online} valueStyle={{color: '#3f8600'}} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic title="离线" value={offline} valueStyle={{color: offline > 0 ? '#cf1322' : undefined}} />
          </Card>
        </Col>
      </Row>

      <div style={{marginBottom: 16, display: 'flex', justifyContent: 'flex-end'}}>
        <Button type="primary" onClick={loadHealth} loading={loading}>刷新</Button>
      </div>

      <Row gutter={[16, 16]}>
        {items.map((item) => (
          <Col key={item.dataSourceId} xs={24} sm={12} md={8} lg={6}>
            <Badge.Ribbon
              text={item.status === 'UP' ? '在线' : '离线'}
              color={item.status === 'UP' ? 'green' : 'red'}
            >
              <Card
                hoverable
                style={{borderColor: item.status === 'DOWN' ? '#ff4d4f' : undefined}}
              >
                <Typography.Title level={5} style={{marginTop: 0}}>
                  {item.dataSourceName || item.dataSourceCode}
                </Typography.Title>
                <Typography.Text type="secondary" style={{display: 'block', marginBottom: 8}}>
                  {item.driverName || '-'}
                </Typography.Text>
                <Typography.Text>
                  响应时间: {item.responseTimeMs}ms
                </Typography.Text>
                {item.errorMessage ? (
                  <Typography.Text type="danger" style={{display: 'block', marginTop: 4}} ellipsis>
                    {item.errorMessage}
                  </Typography.Text>
                ) : null}
              </Card>
            </Badge.Ribbon>
          </Col>
        ))}
        {items.length === 0 && !loading ? (
          <Col span={24}>
            <Card>
              <Typography.Text type="secondary">暂无数据源</Typography.Text>
            </Card>
          </Col>
        ) : null}
      </Row>
    </Spin>
  );
};

export default App;
