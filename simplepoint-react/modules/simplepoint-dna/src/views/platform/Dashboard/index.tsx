import api from '@/api';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Badge,
  Button,
  Card,
  Col,
  Progress,
  Row,
  Space,
  Spin,
  Statistic,
  Table,
  Tooltip,
  Typography,
  message,
} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {useNavigate} from 'react-router';
import {formatDateTime, resolveErrorMessage} from '../shared';

const dashboardConfig = api['platform.dna-dashboard'];

type HealthItem = {
  dataSourceId: string;
  dataSourceCode: string;
  dataSourceName: string;
  status: 'UP' | 'DOWN';
  responseTimeMs: number;
};

type AuditItem = {
  id: string;
  catalogCode?: string;
  sql?: string;
  executionTimeMs?: number;
  createdAt?: string;
};

type QueryTrendPoint = {
  date: string;
  count: number;
};

type QualityStats = {
  total: number;
  passed: number;
  failed: number;
  error: number;
  notRun: number;
};

type DashboardSummary = {
  dataSourceCount: number;
  templateCount: number;
  assetCount: number;
  totalQueries: number;
  recentAudits: AuditItem[];
  healthOverview: HealthItem[];
  queryTrend: QueryTrendPoint[];
  qualityStats: QualityStats;
};

const BAR_MAX_HEIGHT = 60;

const MiniBarChart = ({data, t}: {data: QueryTrendPoint[]; t: (key: string, fallback: string) => string}) => {
  const max = Math.max(...data.map((d) => d.count), 1);
  return (
    <div style={{display: 'flex', alignItems: 'flex-end', gap: 4, height: BAR_MAX_HEIGHT + 24}}>
      {data.map((item) => {
        const barH = item.count > 0
          ? Math.max(4, Math.round((item.count / max) * BAR_MAX_HEIGHT))
          : 2;
        return (
          <Tooltip key={item.date} title={`${item.date}: ${item.count} ${t('dna.dashboard.label.times', '次')}`}>
            <div style={{flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', cursor: 'default'}}>
              <div style={{
                width: '100%',
                background: item.count > 0 ? '#1677ff' : '#f0f0f0',
                borderRadius: '2px 2px 0 0',
                height: barH,
              }} />
              <div style={{fontSize: 10, color: '#8c8c8c', marginTop: 3, lineHeight: '14px'}}>
                {item.date.slice(5)}
              </div>
            </div>
          </Tooltip>
        );
      })}
    </div>
  );
};

const App = () => {
  const {t, ensure, locale} = useI18n();
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [summary, setSummary] = useState<DashboardSummary | null>(null);

  useEffect(() => {
    void ensure(dashboardConfig.i18nNamespaces);
  }, [ensure, locale]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await get<DashboardSummary>(`${dashboardConfig.baseUrl}/summary`);
      setSummary(data);
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.dashboard.error.load', '加载仪表盘数据失败')));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const healthItems = summary?.healthOverview ?? [];
  const onlineCount = healthItems.filter((i) => i.status === 'UP').length;
  const healthRate = healthItems.length > 0
    ? Math.round((onlineCount / healthItems.length) * 100)
    : 0;
  const healthStrokeColor = healthRate === 100 ? '#52c41a'
    : healthRate >= 80 ? '#faad14'
      : '#ff4d4f';

  const qualityStats = summary?.qualityStats;
  const qualityTotal = qualityStats?.total ?? 0;
  const qualityPassed = qualityStats?.passed ?? 0;
  const qualityRate = qualityTotal > 0
    ? Math.round((qualityPassed / qualityTotal) * 100)
    : 0;
  const qualityStrokeColor = qualityRate >= 90 ? '#52c41a'
    : qualityRate >= 70 ? '#faad14'
      : '#ff4d4f';

  const auditColumns: ColumnsType<AuditItem> = useMemo(() => [
    {title: t('dna.dashboard.column.catalogCode', '数据源'), dataIndex: 'catalogCode', width: 120, ellipsis: true},
    {
      title: 'SQL',
      dataIndex: 'sql',
      ellipsis: true,
      render: (value?: string) => (
        <Typography.Text code ellipsis style={{maxWidth: 400}}>
          {value || '-'}
        </Typography.Text>
      ),
    },
    {
      title: t('dna.dashboard.column.executionTime', '耗时'),
      dataIndex: 'executionTimeMs',
      width: 100,
      render: (value?: number) => (value != null ? `${value}ms` : '-'),
    },
    {
      title: t('dna.dashboard.column.createdAt', '时间'),
      dataIndex: 'createdAt',
      width: 180,
      render: formatDateTime,
    },
  ], [t]);

  return (
    <Spin spinning={loading}>
      {/* 统计卡片 */}
      <Row gutter={[16, 16]} style={{marginBottom: 16}}>
        <Col xs={12} sm={6}>
          <Card hoverable onClick={() => navigate('/dna/data-sources')}>
            <Statistic title={t('dna.dashboard.stat.dataSources', '数据源')} value={summary?.dataSourceCount ?? 0} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card hoverable onClick={() => navigate('/dna/federation/query-templates')}>
            <Statistic title={t('dna.dashboard.stat.queryTemplates', '查询模板')} value={summary?.templateCount ?? 0} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card hoverable onClick={() => navigate('/dna/data-assets')}>
            <Statistic title={t('dna.dashboard.stat.dataAssets', '数据资产')} value={summary?.assetCount ?? 0} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card hoverable onClick={() => navigate('/dna/federation/query-audits')}>
            <Statistic title={t('dna.dashboard.stat.totalQueries', '查询总数')} value={summary?.totalQueries ?? 0} />
          </Card>
        </Col>
      </Row>

      {/* 可视化分析行 */}
      <Row gutter={[16, 16]} style={{marginBottom: 16}}>
        <Col xs={24} sm={12} lg={12}>
          <Card
            title={t('dna.dashboard.card.queryTrend', '查询趋势（近 7 天）')}
            extra={
              <Button size="small" onClick={() => navigate('/dna/federation/query-audits')}>
                {t('dna.dashboard.button.viewAll', '查看全部')}
              </Button>
            }
          >
            {(summary?.queryTrend ?? []).length > 0 ? (
              <MiniBarChart data={summary?.queryTrend ?? []} t={t} />
            ) : (
              <Typography.Text type="secondary">{t('dna.dashboard.empty.noData', '暂无数据')}</Typography.Text>
            )}
          </Card>
        </Col>
        <Col xs={24} sm={6} lg={6}>
          <Card
            title={t('dna.dashboard.card.dataSourceHealth', '数据源健康')}
            extra={
              <Button size="small" onClick={() => navigate('/dna/health')}>
                {t('dna.dashboard.button.detail', '详情')}
              </Button>
            }
          >
            <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8}}>
              <Progress
                type="circle"
                percent={healthRate}
                size={80}
                strokeColor={healthStrokeColor}
              />
              <Space size={16}>
                <Badge status="success" text={`${t('dna.dashboard.badge.online', '在线')} ${onlineCount}`} />
                <Badge status="error" text={`${t('dna.dashboard.badge.offline', '离线')} ${healthItems.length - onlineCount}`} />
              </Space>
            </div>
          </Card>
        </Col>
        <Col xs={24} sm={6} lg={6}>
          <Card
            title={t('dna.dashboard.card.dataQualityRate', '数据质量通过率')}
            extra={
              <Button size="small" onClick={() => navigate('/dna/data-quality')}>
                {t('dna.dashboard.button.detail', '详情')}
              </Button>
            }
          >
            <div style={{display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8}}>
              <Progress
                type="circle"
                percent={qualityRate}
                size={80}
                strokeColor={qualityStrokeColor}
                format={(pct) => qualityTotal > 0 ? `${pct ?? 0}%` : '-'}
              />
              <Space size={12}>
                <Badge status="success" text={`${t('dna.dashboard.badge.passed', '通过')} ${qualityPassed}`} />
                <Badge status="error" text={`${t('dna.dashboard.badge.failed', '失败')} ${(qualityStats?.failed ?? 0) + (qualityStats?.error ?? 0)}`} />
              </Space>
            </div>
          </Card>
        </Col>
      </Row>

      {/* 数据源健康状态详情 */}
      <Card
        title={t('dna.dashboard.card.dataSourceStatus', '数据源状态')}
        style={{marginBottom: 16}}
        extra={<Button size="small" onClick={() => navigate('/dna/health')}>{t('dna.dashboard.button.fullMonitor', '完整监控')}</Button>}
      >
        {healthItems.length > 0 ? (
          <Space size={[16, 8]} wrap>
            {healthItems.map((item) => (
              <Badge
                key={item.dataSourceId}
                status={item.status === 'UP' ? 'success' : 'error'}
                text={`${item.dataSourceName || item.dataSourceCode} (${item.responseTimeMs}ms)`}
              />
            ))}
          </Space>
        ) : (
          <Typography.Text type="secondary">{t('dna.dashboard.empty.noDataSources', '暂无数据源')}</Typography.Text>
        )}
      </Card>

      {/* 最近查询 */}
      <Card
        title={t('dna.dashboard.card.recentQueries', '最近查询')}
        extra={
          <Button size="small" onClick={() => navigate('/dna/federation/sql-console')}>
            {t('dna.dashboard.button.openSqlConsole', '打开 SQL 控制台')}
          </Button>
        }
      >
        <Table
          columns={auditColumns}
          dataSource={summary?.recentAudits ?? []}
          rowKey="id"
          pagination={false}
          size="small"
        />
      </Card>
    </Spin>
  );
};

export default App;
