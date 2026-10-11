import api from '@/api';
import DataTable from '@simplepoint/components/DataTable';
import SimpleTable from '@simplepoint/components/SimpleTable';
import type {SimpleTablePageRequest} from '@simplepoint/components/SimpleTable/types';
import TableActions from '@simplepoint/components/Table/Actions';
import {get, useData} from '@simplepoint/shared/api/methods';
import type {Page} from '@simplepoint/shared/types/request';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import PageSection from '@simplepoint/shared/page/Section';
import {Alert, Col, DatePicker, Row, Space, Spin, Statistic, Tabs, Tag, Typography, theme} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import dayjs, {type Dayjs} from 'dayjs';
import {useCallback, useEffect, useMemo, useState} from 'react';
import {localizeWorkbenchError, resolveWorkbenchOperationError} from '../workbenchErrorCodes';
import {invocationBillingStatusTag, invocationOperationLabel, invocationStatusTag} from './labels';
import {billingPeriod, billingInvocationQuery, formatBillingAmount} from './model';

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

const config = api['ai-workbench.billing'];
const billingQueryFallback = {key: 'ai.billing.error.queryFailed', fallback: '计费数据查询失败，请稍后重试'};

export default function Billing() {
  const scope = useQueryScope();
  return <BillingSession key={JSON.stringify(scope)}/>;
}
function BillingSession() {
  const {t, ensure, locale} = useI18n(), selected = usePageSelected(), {token} = theme.useToken();
  const [activeTab, setActiveTab] = useState('summary');
  const [range, setRange] = useState<[Dayjs, Dayjs]>([dayjs().startOf('month'), dayjs()]);
  const period = useMemo(() => billingPeriod(range[0].startOf('day').toISOString(),
    range[1].startOf('day').add(1, 'day').toISOString()), [range]);
  const query = useData([config.summaryUrl, period], ({signal}) => get<BillingSummary>(config.summaryUrl, period, {signal}),
    {enabled: !!period && activeTab === 'summary', retry: false});
  const summary = period && !query.error ? query.data : undefined;
  useEffect(() => {void ensure(config.i18nNamespaces);}, [ensure, locale]);
  const loadInvocations = useCallback(({page, size, filters, sort, signal}: SimpleTablePageRequest) => {
    if (!period) return Promise.reject(Error(t('ai.billing.error.rangeInvalid', '计费时间范围无效，请重新选择')));
    return get<Page<object>>(config.invocationsUrl, {...filters, $query: billingInvocationQuery(period, filters.$query),
      page, size, ...(sort ? {sort} : {})}, {signal});
  }, [period, t]);

  const columns = useMemo<ColumnsType<ModelSummary>>(() => [
    {title: t('ai.billing.table.model', '模型'), dataIndex: 'modelId', ellipsis: true},
    {title: t('ai.billing.table.currency', '币种'), dataIndex: 'currency', width: 90},
    {title: t('ai.billing.table.invocations', '调用数'), dataIndex: 'invocationCount', width: 110},
    {title: t('ai.billing.table.inputTokens', '输入 Token'), dataIndex: 'inputTokens', width: 140},
    {title: t('ai.billing.table.outputTokens', '输出 Token'), dataIndex: 'outputTokens', width: 140},
    {title: t('ai.billing.table.totalCost', '总费用'), dataIndex: 'totalCost', width: 210,
      render: (value: string, record) => formatBillingAmount(value, record.currency, locale)},
  ], [locale, t]);
  const invocationColumns = useMemo(() => ({
    status: {width: 120, render: (value: string) => {const status = invocationStatusTag(t, value); return <Tag color={status.color}>{status.label}</Tag>;}},
    operation: {width: 130, render: (value: string) => invocationOperationLabel(t, value)},
    modelId: {width: 260, ellipsis: true}, durationMillis: {width: 130}, inputTokens: {width: 120}, outputTokens: {width: 120}, totalTokens: {width: 120},
    billingStatus: {width: 130, render: (value: string) => {const status = invocationBillingStatusTag(t, value); return <Tag color={status.color}>{status.label}</Tag>;}},
    billingCurrency: {width: 100},
    totalCost: {width: 210, render: (value: unknown, record: {billingCurrency?: string}) => formatBillingAmount(value, record.billingCurrency || '', locale)},
    startedAt: {width: 190},
  }), [locale, t]);
  const summaryContent = <div className="billing-summary" style={{display: 'flex', flexDirection: 'column', gap: 12}}>
    <Alert showIcon type="info" title={t('ai.billing.notice.title', '费用按调用时价格快照计算')}
      description={t('ai.billing.notice.description', 'Token 用量以供应商返回值为准；价格调整只影响后续调用，历史费用不会重算。不同币种始终分开汇总。')}/>
    {period && query.error && <Alert type="error" showIcon title={localizeWorkbenchError(t, resolveWorkbenchOperationError(query.error, billingQueryFallback))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1, text: t('table.retry', '重试'), loading: query.isFetching, disabled: !selected}]}
        onButtonEvents={{retry: () => {if (selected && !query.isFetching) void query.refetch();}}}/>}/>}
    {summary ? <Row gutter={[24, 16]} className="billing-statistics">
      <Col xs={24} sm={12} lg={6}><Statistic title={t('ai.billing.stat.invocations', '调用总数')} value={summary.invocationCount}/></Col>
      <Col xs={24} sm={12} lg={6}><Statistic title={t('ai.billing.stat.priced', '已计费调用')} value={summary.pricedInvocationCount}/></Col>
      <Col xs={24} sm={12} lg={6}><Statistic title={t('ai.billing.stat.unpriced', '成功但未配置价格')} value={summary.unpricedInvocationCount}
        styles={{content: {color: summary.unpricedInvocationCount ? token.colorWarning : undefined}}}/></Col>
      {summary.currencies.map(item => <Col xs={24} sm={12} lg={6} key={item.currency}>
        <Statistic title={t('ai.billing.stat.cost', '{currency} 总费用', {currency: item.currency})}
          value={item.totalCost} formatter={() => formatBillingAmount(item.totalCost, item.currency, locale)}/>
      </Col>)}
    </Row> : period && !query.error && <Spin size="small"/>}
    <DataTable<ModelSummary> storageKey="ai.billing.model-summary" columns={columns} dataSource={summary?.models ?? []}
      query={{mode: 'local', scope: 'result', contextKey: JSON.stringify(period), fields: {
        modelId: {type: 'string'}, currency: {type: 'string'}, invocationCount: {type: 'integer'},
        inputTokens: {type: 'integer'}, outputTokens: {type: 'integer'}, totalCost: {type: 'number'},
      }}}
      loading={!!period && query.isFetching} pagination={false} rowKey={record => record.modelDefinitionId + ':' + record.currency}
      toolbarExtra={<Space orientation="vertical" size={2}>
        <Typography.Text strong>{t('ai.billing.table.title', '模型费用排行')}</Typography.Text>
        <Typography.Text type="secondary">{t('ai.billing.table.top20', '仅展示所选时间范围费用最高的前 20 个模型与币种分组；完整调用记录请查看调用明细。')}</Typography.Text>
      </Space>}
      locale={{emptyText: query.error || !period ? '—' : query.isPending ? <Spin size="small"/>
        : <Typography.Text type="secondary">{t('ai.billing.empty', '所选时间范围暂无已计费调用')}</Typography.Text>}} scroll={{x: 1000}}/>
  </div>;
  return <div className="ai-billing" style={{height: '100%', minHeight: 0, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 12, overflow: 'auto'}}>
    <Space wrap>
      <DatePicker.RangePicker allowClear={false} value={range} disabled={!selected} onChange={values => {
        if (values?.[0] && values[1]) setRange([values[0], values[1]]);
      }}/>
      {activeTab === 'summary' && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'refresh', sort: 1, text: t('ai.billing.action.refresh', '刷新'), loading: query.isFetching, disabled: !period || !selected},
        {key: 'invocations', sort: 2, text: t('ai.billing.action.invocations', '查看调用明细'), type: 'primary', disabled: !period || !selected},
      ]} onButtonEvents={{refresh: () => {if (period && selected && !query.isFetching) void query.refetch();}, invocations: () => setActiveTab('invocations')}}/>}
    </Space>
    {!period && <Alert type="error" showIcon title={t('ai.billing.error.rangeLimit', '请选择有效日期范围，最长 366 天（含所选结束日期）。')}/>}
    <Tabs activeKey={activeTab} onChange={setActiveTab} items={[
      {key: 'summary', label: t('ai.billing.tab.summary', '账单概览'), children: <PageSection selected={activeTab === 'summary'}>{summaryContent}</PageSection>},
      {key: 'invocations', label: t('ai.billing.tab.invocations', '调用明细'), children: <PageSection selected={activeTab === 'invocations'}>{period && <SimpleTable<object>
        key={JSON.stringify(period)} baseUrl={config.invocationsUrl} i18nNamespaces={config.i18nNamespaces}
        name="ai-workbench-billing-invocations" columnOverrides={invocationColumns} loadPage={loadInvocations}/>}</PageSection>},
    ]}/>
  </div>;
}
