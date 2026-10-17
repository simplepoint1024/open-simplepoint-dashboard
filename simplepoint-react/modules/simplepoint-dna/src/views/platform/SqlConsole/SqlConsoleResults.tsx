import {DownloadOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import {Alert, Descriptions, Empty, Space, Tabs, Tag, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {useMemo} from 'react';
import {sqlQueryFields} from './sqlQueryFields';
import type {
  ResultRow,
  ResultTabKey,
  SqlExecuteResult,
  SqlExplainResult,
  SqlQueryResult,
  SqlUpdateResult,
  StatementExecutionRecord,
  StatementExecutionStatus,
} from './types';

const {Paragraph} = Typography;

const formatPushedSqls = (pushedSqls: string[]) => pushedSqls
  .map((sql, index) => `-- Pushdown SQL ${index + 1}\n${sql}`)
  .join('\n\n');

const formatPushedSql = (sql: string, type: string) => `-- ${type} Pushdown SQL\n${sql}`;

const renderTextCellValue = (value: string) => (
  <span className="dna-sql-console-result-cell" title={value}>
    {value}
  </span>
);

const renderCellValue = (value: unknown) => {
  if (value === null || value === undefined) {
    return <Tag>NULL</Tag>;
  }
  if (typeof value === 'boolean') {
    return <Tag color={value ? 'success' : 'default'}>{value ? 'true' : 'false'}</Tag>;
  }
  if (typeof value === 'object') {
    try {
      return renderTextCellValue(JSON.stringify(value));
    } catch {
      return renderTextCellValue(String(value));
    }
  }
  return renderTextCellValue(String(value));
};

type SqlConsoleResultsProps = {
  activeTabKey: ResultTabKey;
  busy: boolean;
  executeMessage: string | null;
  executeType: SqlExecuteResult['type'] | null;
  explainResult: SqlExplainResult | null;
  gridHeight: number;
  onExport: (format: 'CSV' | 'JSON') => void;
  onSelectStatement: (record: StatementExecutionRecord) => void;
  onTabChange: (key: ResultTabKey) => void;
  queryResult: SqlQueryResult | null;
  resultRevision: number;
  selectedStatementKey: number | null;
  statementExecutions: StatementExecutionRecord[];
  updateResult: SqlUpdateResult | null;
};

export const SqlConsoleResults = ({
  activeTabKey,
  busy,
  executeMessage,
  executeType,
  explainResult,
  gridHeight,
  onExport,
  onSelectStatement,
  onTabChange,
  queryResult,
  resultRevision,
  selectedStatementKey,
  statementExecutions,
  updateResult,
}: SqlConsoleResultsProps) => {
  const {t} = useI18n();
  const analysisResult = queryResult ?? explainResult;

  const resultColumns = useMemo<ColumnsType<ResultRow>>(() => (queryResult?.columns ?? []).map((column, index) => {
    const title = column.typeName ? `${column.name} (${column.typeName})` : column.name;
    return {
      title: (
        <span className="dna-sql-console-result-header" title={title}>
          {title}
        </span>
      ),
      width: 180,
      dataIndex: ['values', index],
      key: `${column.name}-${index}`,
      render: (value: unknown) => renderCellValue(value),
    };
  }), [queryResult]);

  const resultData = useMemo<ResultRow[]>(() => (queryResult?.rows ?? []).map((row, index) => ({
    key: index,
    values: row ?? [],
  })), [queryResult]);

  const statementExecutionColumns = useMemo<ColumnsType<StatementExecutionRecord>>(() => [
    {title: '#', dataIndex: 'sequence', key: 'sequence', width: 56},
    {
      title: t('dna.federation.sqlConsole.page.batch.status', '状态'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: StatementExecutionStatus) => {
        const label = status === 'PENDING'
          ? t('dna.federation.sqlConsole.page.batch.pending', '等待')
          : status === 'RUNNING'
            ? t('dna.federation.sqlConsole.page.batch.running', '执行中')
            : status === 'SUCCESS'
              ? t('dna.federation.sqlConsole.page.batch.success', '成功')
              : status === 'FAILED'
                ? t('dna.federation.sqlConsole.page.batch.failed', '失败')
                : t('dna.federation.sqlConsole.page.batch.skipped', '已跳过');
        const color = status === 'SUCCESS' ? 'success'
          : status === 'FAILED' ? 'error'
            : status === 'RUNNING' ? 'processing'
              : 'default';
        return <Tag color={color}>{label}</Tag>;
      },
    },
    {
      title: t('dna.federation.sqlConsole.page.field.executionType', '执行类型'),
      dataIndex: 'type',
      key: 'type',
      width: 90,
      render: (value?: string) => value || '-',
    },
    {
      title: 'SQL',
      dataIndex: 'sql',
      key: 'sql',
      width: 360,
      render: (value: string) => renderTextCellValue(value.replace(/\s+/g, ' ')),
    },
    {
      title: t('dna.federation.sqlConsole.page.batch.summary', '结果摘要'),
      dataIndex: 'summary',
      key: 'summary',
      width: 240,
      render: (value?: string) => renderTextCellValue(value || '-'),
    },
    {
      title: t('dna.federation.sqlConsole.page.field.executionTimeMs', '执行耗时(ms)'),
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 120,
      render: (value?: number) => value ?? '-',
    },
  ], [t]);

  const scrollPane = (children: React.ReactNode) => (
    <div className="dna-sql-console-scroll-pane">{children}</div>
  );

  const resultTab = {
    key: 'result',
    label: t('dna.federation.sqlConsole.page.tab.result', '执行结果'),
    children: scrollPane(
      <Space className="dna-sql-console-result-content" orientation="vertical" size={8} style={{display: 'flex'}}>
        {statementExecutions.length > 1 ? (
          <DataTable<StatementExecutionRecord>
            storageKey="dna.sql-console.batch-results"
            settings={false}
            query={{mode: 'local', scope: 'complete', fields: {
              sequence: {type: 'number'},
              status: {type: 'string', enum: ['PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED']},
              type: {type: 'string'},
              sql: {type: 'string'},
              summary: {type: 'string'},
              durationMs: {type: 'number'},
            }}}
            className="dna-sql-console-statement-table"
            size="small"
            rowKey="key"
            scroll={{x: 960, y: 150}}
            pagination={false}
            columns={statementExecutionColumns}
            dataSource={statementExecutions}
            rowClassName={record => record.key === selectedStatementKey ? 'dna-sql-console-statement-selected' : ''}
            onRow={record => ({onClick: () => onSelectStatement(record)})}
          />
        ) : null}
        {queryResult ? (<>
          {queryResult.truncated ? (
            <Alert
              type="warning"
              showIcon
              message={t('dna.federation.sqlConsole.page.alert.resultTruncated.title', '结果已按策略上限截断')}
              description={t(
                'dna.federation.sqlConsole.page.alert.resultTruncated.description',
                `当前仅返回前 ${queryResult.returnedRows} 行，请调整查询条件或放宽查询策略后再试。`,
              ).replace('{rows}', String(queryResult.returnedRows))}
            />
          ) : null}
          <DataTable<ResultRow>
            storageKey="dna.sql-console.result"
            query={{mode: 'local', scope: 'result', fields: sqlQueryFields(queryResult.columns)}}
            includeToolbarInScroll
            key={resultRevision}
            className="dna-sql-console-result-table"
            size="small"
            rowKey="key"
            scroll={{x: 'max-content', y: Math.max(80, gridHeight
              - (statementExecutions.length > 1 ? 210 : 0)
              - (queryResult.truncated ? 100 : 0))}}
            pagination={{defaultPageSize: 50, showSizeChanger: true, size: 'small', pageSizeOptions: [20, 50, 100, 200]}}
            columns={resultColumns}
            dataSource={resultData}
          />
        </>) : updateResult ? (
          <Space orientation="vertical" size={16} style={{display: 'flex'}}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.executionType', '执行类型')}>
                <Tag color={executeType === 'DDL' ? 'purple' : 'processing'}>{executeType}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.catalog', '数据目录')}>{updateResult.catalogCode}</Descriptions.Item>
              <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.dataSource', '执行数据源')}>{updateResult.dataSourceCode}</Descriptions.Item>
              <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.affectedRows', '影响行数')}>{updateResult.affectedRows}</Descriptions.Item>
              <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.executionTimeMs', '执行耗时(ms)')} span={2}>
                {updateResult.executionTimeMs}
              </Descriptions.Item>
            </Descriptions>
            {updateResult.pushedSql ? (
              <Paragraph style={{whiteSpace: 'pre-wrap', marginBottom: 0}}>
                {formatPushedSql(updateResult.pushedSql, executeType || 'SQL')}
              </Paragraph>
            ) : null}
          </Space>
        ) : executeType === 'FLUSH_CACHE' ? (
          <Alert
            type="success"
            showIcon
            message={t('dna.federation.sqlConsole.page.success.flushCache', '缓存已刷新')}
            description={executeMessage || '-'}
          />
        ) : (
          <Empty description={t('dna.federation.sqlConsole.page.empty.resultPending', '执行 SQL 后展示结果集')} />
        )}
      </Space>,
    ),
  };

  const tabs = [
    resultTab,
    {
      key: 'analysis',
      label: t('dna.federation.sqlConsole.page.tab.analysis', '执行分析'),
      children: scrollPane(analysisResult ? (
        <Space orientation="vertical" size={16} style={{display: 'flex'}}>
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.catalog', '数据目录')}>{analysisResult.catalogCode}</Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.policy', '查询策略')}>{analysisResult.policyCode}</Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.maxRows', '最大返回行数')}>{analysisResult.maxRows}</Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.timeoutMs', '超时(ms)')}>{analysisResult.timeoutMs}</Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.allowCrossSourceJoin', '允许跨源 Join')}>
              <Tag color={analysisResult.allowCrossSourceJoin ? 'success' : 'default'}>
                {analysisResult.allowCrossSourceJoin
                  ? t('dna.federation.sqlConsole.page.state.allow', '允许')
                  : t('dna.federation.sqlConsole.page.state.forbid', '禁止')}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.crossSourceJoin', '实际跨源 Join')}>
              <Tag color={analysisResult.crossSourceJoin ? 'processing' : 'default'}>
                {analysisResult.crossSourceJoin
                  ? t('dna.federation.sqlConsole.page.state.yes', '是')
                  : t('dna.federation.sqlConsole.page.state.no', '否')}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.platformJoin', '平台侧 Join')}>
              <Tag color={analysisResult.platformJoin ? 'warning' : 'success'}>
                {analysisResult.platformJoin
                  ? t('dna.federation.sqlConsole.page.state.yes', '是')
                  : t('dna.federation.sqlConsole.page.state.no', '否')}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.dataSources', '命中数据源')} span={2}>
              {(analysisResult.dataSources ?? []).length > 0
                ? (analysisResult.dataSources ?? []).map((code) => <Tag key={code}>{code}</Tag>)
                : '-'}
            </Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.pushedDownOperators', '下推算子')} span={2}>
              {(analysisResult.pushedDownOperators ?? []).length > 0
                ? (analysisResult.pushedDownOperators ?? []).map((operator, index) => (
                  <Tag key={`${operator}-${index}`}>{operator}</Tag>
                ))
                : '-'}
            </Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.schemaCache', 'Schema 缓存')}>
              <Tag color={analysisResult.schemaCacheHit ? 'success' : 'default'}>
                {analysisResult.schemaCacheHit
                  ? t('dna.federation.sqlConsole.page.state.hit', '命中')
                  : t('dna.federation.sqlConsole.page.state.miss', '未命中')}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.mountedDataSourceCount', '挂载数据源数')}>
              {analysisResult.mountedDataSourceCount}
            </Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.schemaAssemblyTimeMs', 'Schema 组装耗时(ms)')} span={2}>
              {analysisResult.schemaAssemblyTimeMs}
            </Descriptions.Item>
            {queryResult ? (
              <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.executionTimeMs', '执行耗时(ms)')} span={2}>
                {queryResult.executionTimeMs}
              </Descriptions.Item>
            ) : null}
          </Descriptions>
          <div>
            <Paragraph style={{marginBottom: 8}}>
              <strong>{t('dna.federation.sqlConsole.page.label.pushdownSummary', '下推摘要')}</strong>
            </Paragraph>
            <Paragraph style={{whiteSpace: 'pre-wrap', marginBottom: 0}}>
              {analysisResult.pushdownSummary || t('dna.federation.sqlConsole.page.state.noPushdownSummary', '暂无下推摘要')}
            </Paragraph>
          </div>
        </Space>
      ) : (
        <Empty description={t('dna.federation.sqlConsole.page.empty.analysis', '查看执行计划或执行 SQL 后展示分析结果')} />
      )),
    },
    {
      key: 'pushedSql',
      label: t('dna.federation.sqlConsole.page.tab.pushedSql', 'JDBC 下推 SQL'),
      children: scrollPane(analysisResult ? (
        (analysisResult.pushedSqls ?? []).length > 0 ? (
          <Paragraph style={{whiteSpace: 'pre-wrap', marginBottom: 0}}>
            {formatPushedSqls(analysisResult.pushedSqls ?? [])}
          </Paragraph>
        ) : (
          <Empty description={t('dna.federation.sqlConsole.page.empty.noPushedSqlCollected', '本次执行未采集到 JDBC 下推 SQL')} />
        )
      ) : updateResult?.pushedSql ? (
        <Paragraph style={{whiteSpace: 'pre-wrap', marginBottom: 0}}>
          {formatPushedSql(updateResult.pushedSql, executeType || 'SQL')}
        </Paragraph>
      ) : (
        <Empty description={t('dna.federation.sqlConsole.page.empty.pushedSqlPending', '执行 SQL 后展示 JDBC 下推 SQL')} />
      )),
    },
    {
      key: 'plan',
      label: t('dna.federation.sqlConsole.page.tab.plan', 'Calcite 计划'),
      children: scrollPane(analysisResult ? (
        <Paragraph style={{whiteSpace: 'pre-wrap', marginBottom: 0}}>
          {analysisResult.planText || t('dna.federation.sqlConsole.page.empty.noPlan', '暂无执行计划')}
        </Paragraph>
      ) : (
        <Empty description={t('dna.federation.sqlConsole.page.empty.planPending', '查看执行计划或执行 SQL 后展示 Calcite 执行计划')} />
      )),
    },
  ];

  return (
    <Tabs
      className="dna-sql-console-tabs"
      size="small"
      activeKey={activeTabKey}
      onChange={key => onTabChange(key as ResultTabKey)}
      items={tabs}
      tabBarExtraContent={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'exportCsv', sort: 0, type: 'text', size: 'small', icon: <DownloadOutlined/>,
          text: 'CSV', disabled: busy || !queryResult, onClick: () => onExport('CSV')},
        {key: 'exportJson', sort: 1, type: 'text', size: 'small', text: 'JSON',
          disabled: busy || !queryResult, onClick: () => onExport('JSON')},
      ]}/>} />
  );
};
