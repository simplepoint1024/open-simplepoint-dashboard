import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import {get} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Row,
  Space,
  Spin,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
  message,
} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import dayjs, {type Dayjs} from 'dayjs';
import {useCallback, useEffect, useMemo, useState} from 'react';

type CurrencySummary = {
  currency: string;
  invocationCount: number;
  inputTokens: number;
  cachedInputTokens: number;
  outputTokens: number;
  inputCost: string;
  cachedInputCost: string;
  outputCost: string;
  requestCost: string;
  totalCost: string;
};

type ModelSummary = {
  modelDefinitionId: string;
  modelId: string;
  currency: string;
  invocationCount: number;
  inputTokens: number;
  outputTokens: number;
  totalCost: string;
};

type BillingSummary = {
  from: string;
  to: string;
  invocationCount: number;
  pricedInvocationCount: number;
  unpricedInvocationCount: number;
  currencies: CurrencySummary[];
  models: ModelSummary[];
};

const resolveErrorMessage = (error: unknown, fallback: string) => {
  if (error instanceof Error && error.message) return error.message;
  if (typeof error === 'string' && error) return error;
  return fallback;
};

const formatAmount = (value: string, currency: string, locale: string) => {
  const amount = Number(value);
  if (!Number.isFinite(amount)) return `${currency} ${value}`;
  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 8,
    }).format(amount);
  } catch {
    return `${currency} ${amount.toFixed(8)}`;
  }
};

const Billing = () => {
  const config = api['ai-workbench.billing'];
  const {t, ensure, locale} = useI18n();
  const [activeTab, setActiveTab] = useState('summary');
  const [range, setRange] = useState<[Dayjs, Dayjs]>([
    dayjs().startOf('month'),
    dayjs(),
  ]);
  const [summary, setSummary] = useState<BillingSummary | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    void ensure(config.i18nNamespaces);
  }, [ensure, locale]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await get<BillingSummary>(config.summaryUrl, {
        from: range[0].startOf('day').toISOString(),
        to: range[1].endOf('day').toISOString(),
      });
      setSummary(data);
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('ai.billing.error.load', '模型计费数据加载失败'),
      ));
    } finally {
      setLoading(false);
    }
  }, [config.summaryUrl, range, t]);

  useEffect(() => {
    void load();
  }, [load]);

  const columns = useMemo<ColumnsType<ModelSummary>>(() => [
    {
      title: t('ai.billing.table.model', '模型'),
      dataIndex: 'modelId',
      ellipsis: true,
    },
    {
      title: t('ai.billing.table.currency', '币种'),
      dataIndex: 'currency',
      width: 90,
    },
    {
      title: t('ai.billing.table.invocations', '调用数'),
      dataIndex: 'invocationCount',
      width: 110,
    },
    {
      title: t('ai.billing.table.inputTokens', '输入 Token'),
      dataIndex: 'inputTokens',
      width: 140,
    },
    {
      title: t('ai.billing.table.outputTokens', '输出 Token'),
      dataIndex: 'outputTokens',
      width: 140,
    },
    {
      title: t('ai.billing.table.totalCost', '总费用'),
      dataIndex: 'totalCost',
      width: 180,
      render: (value: string, record) => formatAmount(value, record.currency, locale),
    },
  ], [locale, t]);

  const invocationColumns = useMemo(() => ({
    status: {
      width: 120,
      render: (value: string) => (
        <Tag color={value === 'SUCCEEDED' ? 'green' : value === 'FAILED' ? 'red' : 'blue'}>
          {t(`ai.invocations.status.${value}`, value || '-')}
        </Tag>
      ),
    },
    operation: {
      width: 130,
      render: (value: string) => t(`ai.invocations.operation.${value}`, value || '-'),
    },
    modelId: {width: 260, ellipsis: true},
    durationMillis: {width: 130},
    inputTokens: {width: 120},
    outputTokens: {width: 120},
    totalTokens: {width: 120},
    billingStatus: {
      width: 130,
      render: (value: string) => {
        const color = value === 'CALCULATED' ? 'gold'
          : value === 'UNPRICED' ? 'orange'
            : value === 'NOT_CHARGED' ? 'default'
              : 'blue';
        return (
          <Tag color={color}>
            {t(`ai.invocations.billingStatus.${value}`, value || '-')}
          </Tag>
        );
      },
    },
    billingCurrency: {width: 100},
    totalCost: {
      width: 150,
      render: (value: number | string | undefined, record: {billingCurrency?: string}) => (
        value == null ? '-' : `${record.billingCurrency || ''} ${Number(value).toFixed(8)}`
      ),
    },
    startedAt: {width: 190},
  }), [t]);

  const summaryContent = (
    <Spin spinning={loading}>
      <Space direction="vertical" size={16} style={{display: 'flex'}}>
        <Alert
          showIcon
          type="info"
          message={t('ai.billing.notice.title', '费用按调用时价格快照计算')}
          description={t(
            'ai.billing.notice.description',
            'Token 用量以供应商返回值为准；价格调整只影响后续调用，历史费用不会重算。不同币种始终分开汇总。',
          )}
        />
        <Card
          title={t('ai.billing.title', '模型计费')}
          extra={(
            <Space wrap>
              <DatePicker.RangePicker
                allowClear={false}
                value={range}
                onChange={(values) => {
                  if (values?.[0] && values[1]) {
                    setRange([values[0], values[1]]);
                  }
                }}
              />
              <Button onClick={() => void load()}>
                {t('ai.billing.action.refresh', '刷新')}
              </Button>
              <Button type="primary" onClick={() => setActiveTab('invocations')}>
                {t('ai.billing.action.invocations', '查看调用明细')}
              </Button>
            </Space>
          )}
        >
          <Row gutter={[16, 16]}>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Statistic
                  title={t('ai.billing.stat.invocations', '调用总数')}
                  value={summary?.invocationCount ?? 0}
                />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Statistic
                  title={t('ai.billing.stat.priced', '已计费调用')}
                  value={summary?.pricedInvocationCount ?? 0}
                />
              </Card>
            </Col>
            <Col xs={24} sm={12} lg={6}>
              <Card size="small">
                <Statistic
                  title={t('ai.billing.stat.unpriced', '成功但未配置价格')}
                  value={summary?.unpricedInvocationCount ?? 0}
                  valueStyle={{
                    color: summary?.unpricedInvocationCount ? '#d46b08' : undefined,
                  }}
                />
              </Card>
            </Col>
            {(summary?.currencies ?? []).map((item) => (
              <Col xs={24} sm={12} lg={6} key={item.currency}>
                <Card size="small">
                  <Statistic
                    title={t('ai.billing.stat.cost', '{currency} 总费用', {
                      currency: item.currency,
                    })}
                    value={Number(item.totalCost)}
                    precision={Math.abs(Number(item.totalCost)) < 0.01 ? 6 : 2}
                    suffix={item.currency}
                  />
                </Card>
              </Col>
            ))}
          </Row>
        </Card>
        <Card title={t('ai.billing.table.title', '模型费用排行')}>
          <Table<ModelSummary>
            columns={columns}
            dataSource={summary?.models ?? []}
            pagination={false}
            rowKey={(record) => (
              `${record.modelDefinitionId}:${record.currency}`
            )}
            locale={{
              emptyText: (
                <Typography.Text type="secondary">
                  {t('ai.billing.empty', '所选时间范围暂无已计费调用')}
                </Typography.Text>
              ),
            }}
            scroll={{x: 900}}
          />
        </Card>
      </Space>
    </Spin>
  );

  return (
    <Tabs
      activeKey={activeTab}
      onChange={setActiveTab}
      items={[
        {
          key: 'summary',
          label: t('ai.billing.tab.summary', '账单概览'),
          children: summaryContent,
        },
        {
          key: 'invocations',
          label: t('ai.billing.tab.invocations', '调用明细'),
          children: (
            <SimpleTable
              baseUrl={config.invocationsUrl}
              i18nNamespaces={config.i18nNamespaces}
              name="ai-workbench-billing-invocations"
              columnOverrides={invocationColumns}
            />
          ),
        },
      ]}
    />
  );
};

export default Billing;
