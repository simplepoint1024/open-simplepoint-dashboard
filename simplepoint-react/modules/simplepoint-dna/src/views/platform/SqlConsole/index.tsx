import api from '@/api';
import {DatabaseOutlined, DownloadOutlined, PlayCircleOutlined, ReloadOutlined, SettingOutlined} from '@simplepoint/shared/types/icon';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {assertConsoleResponse, filterLoadedTree, quoteSqlPath, resultCsv} from './sqlConsoleUtils';
import {sqlQueryFields} from './sqlQueryFields';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {normalizePage, type Page} from '@simplepoint/shared/types/request';
import {Alert, App as AntApp, Button, Popover, Splitter, Descriptions, Empty, Input, InputNumber, Select, Space, Spin, Tabs, Tag, Tree, Typography, theme} from 'antd';
import Table from '@simplepoint/components/DataTable';
import type {TabsProps} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {Key, ReactNode} from 'react';
import {SqlEditor, type SqlEditorRef} from './SqlEditor';
import './index.css';
import {
  findMetadataTreeNodeByKey,
  normalizeMetadataTreeNodes,
  renderMetadataTreeTitle,
  replaceMetadataTreeChildren,
  type MetadataNodeType,
  type MetadataPathSegment,
  type MetadataTreeNode,
} from '../metadataTree';
import {resolveErrorMessage} from '../shared';

const {Paragraph} = Typography;

const dataSourceConfig = api['platform.dna-data-sources'];
const metadataConfig = api['platform.dna-metadata'];
const sqlConsoleConfig = api['platform.dna-federation-sql-console'];

type JdbcDataSourceOption = {
  id: string;
  code?: string;
  name?: string;
  enabled?: boolean;
  driverName?: string;
  databaseProductName?: string;
};

type SqlExplainResult = {
  catalogCode: string;
  policyCode: string;
  maxRows: number;
  timeoutMs: number;
  allowCrossSourceJoin: boolean;
  crossSourceJoin: boolean;
  dataSources: string[];
  planText: string;
  pushedSqls: string[];
  pushdownSummary?: string;
  schemaCacheHit: boolean;
  schemaAssemblyTimeMs: number;
  mountedDataSourceCount: number;
  pushedDownOperators?: string[];
  platformJoin?: boolean;
};

type SqlColumn = {
  name: string;
  typeName?: string | null;
  jdbcType?: number | null;
};

type SqlQueryResult = SqlExplainResult & {
  columns: SqlColumn[];
  rows: unknown[][];
  truncated: boolean;
  returnedRows: number;
  executionTimeMs: number;
};

type SqlUpdateResult = {
  catalogCode: string;
  dataSourceCode: string;
  affectedRows: number;
  executionTimeMs: number;
  pushedSql?: string | null;
};

type SqlExecuteResult = {
  type: 'QUERY' | 'DML' | 'DDL' | 'FLUSH_CACHE' | string;
  queryResult?: SqlQueryResult | null;
  updateResult?: SqlUpdateResult | null;
  message?: string | null;
};

type SqlConsolePayload = {
  catalogCode?: string;
  sql: string;
  defaultSchema?: string;
  maxRows?: number;
  parameters?: unknown[];
};

type BuildSqlConsolePayloadOptions = {
  allowGlobalFlush?: boolean;
  executionSql?: string;
};

type ResultRow = {
  key: number;
  values: unknown[];
};

type ResultTabKey = 'analysis' | 'pushedSql' | 'plan' | 'result';

const defaultSql = 'SELECT 1;';

const resolveDataSourceLabel = (dataSource: JdbcDataSourceOption) => {
  const primary = dataSource.name || dataSource.code || dataSource.id;
  const secondary = dataSource.code && dataSource.code !== primary ? ` (${dataSource.code})` : '';
  const product = dataSource.databaseProductName ? ` - ${dataSource.databaseProductName}` : '';
  return `${primary}${secondary}${product}`;
};

const buildDataSourceTreeNodes = (
  dataSources: JdbcDataSourceOption[],
  resolveLabel: (type: MetadataNodeType, fallback?: string | null) => string,
): MetadataTreeNode[] => dataSources.map((dataSource) => {
  const title = resolveDataSourceLabel(dataSource);
  return {
    key: `data-source-${dataSource.id}`,
    title: renderMetadataTreeTitle('DATA_SOURCE', title, resolveLabel),
    rawTitle: title,
    type: 'DATA_SOURCE',
    path: [],
    leaf: false,
    isLeaf: false,
    loaded: false,
    dataSourceId: dataSource.id,
  };
});

const toExplainSnapshot = (result: SqlExplainResult | SqlQueryResult): SqlExplainResult => ({
  catalogCode: result.catalogCode,
  policyCode: result.policyCode,
  maxRows: result.maxRows,
  timeoutMs: result.timeoutMs,
  allowCrossSourceJoin: result.allowCrossSourceJoin,
  crossSourceJoin: result.crossSourceJoin,
  dataSources: result.dataSources ?? [],
  planText: result.planText,
  pushedSqls: result.pushedSqls ?? [],
  pushdownSummary: result.pushdownSummary,
  schemaCacheHit: result.schemaCacheHit ?? false,
  schemaAssemblyTimeMs: result.schemaAssemblyTimeMs ?? 0,
  mountedDataSourceCount: result.mountedDataSourceCount ?? (result.dataSources ?? []).length,
  pushedDownOperators: result.pushedDownOperators ?? [],
  platformJoin: result.platformJoin ?? false,
});

const formatPushedSqls = (pushedSqls: string[]) => pushedSqls
  .map((sql, index) => `-- Pushdown SQL ${index + 1}\n${sql}`)
  .join('\n\n');

const formatPushedSql = (sql: string, type: string) => `-- ${type} Pushdown SQL\n${sql}`;

const isFlushCacheSql = (value: string) => /^\s*FLUSH\s+CACHE\s*;?\s*$/i.test(value);

const saveBlob = (blob: Blob, fileName: string) => {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
};

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

const App = () => {
  const {t, ensure, locale} = useI18n();
  const {message} = AntApp.useApp();
  const [catalogCode, setCatalogCode] = useState<string>();
  const [dataSources, setDataSources] = useState<JdbcDataSourceOption[]>([]);
  const [treeData, setTreeData] = useState<MetadataTreeNode[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<Key[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<Key[]>([]);
  const [selectedTreeNode, setSelectedTreeNode] = useState<MetadataTreeNode | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [sql, setSql] = useState(defaultSql);
  const [defaultSchema, setDefaultSchema] = useState('');
  const [maxRows, setMaxRows] = useState<number | null>(200);
  const [parametersText, setParametersText] = useState('');
  const [loadingMode, setLoadingMode] = useState<'explain' | 'query' | null>(null);
  const [exportFormat, setExportFormat] = useState<'CSV' | 'JSON' | null>(null);
  const [activeTabKey, setActiveTabKey] = useState<ResultTabKey>('result');
  const [explainResult, setExplainResult] = useState<SqlExplainResult | null>(null);
  const [queryResult, setQueryResult] = useState<SqlQueryResult | null>(null);
  const [updateResult, setUpdateResult] = useState<SqlUpdateResult | null>(null);
  const [executeType, setExecuteType] = useState<SqlExecuteResult['type'] | null>(null);
  const [executeMessage, setExecuteMessage] = useState<string | null>(null);
  const editorRef = useRef<SqlEditorRef>(null);
  const busyRef = useRef(false);
  const aliveRef = useRef(true);
  const treeGeneration = useRef(0);
  const [executionError, setExecutionError] = useState<string | null>(null);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [failedTreeNode, setFailedTreeNode] = useState<MetadataTreeNode | null>(null);
  const [retryingTree, setRetryingTree] = useState(false);
  const pendingNodes = useRef(new Set<Key>());
  const [treeRevision, setTreeRevision] = useState(0);
  const [resultRevision, setResultRevision] = useState(0);
  const [search, setSearch] = useState('');
  const {token} = theme.useToken();
  const selected = usePageSelected();
  const [optionsOpen, setOptionsOpen] = useState(false);
  const outputRef = useRef<HTMLDivElement>(null);
  const [gridHeight, setGridHeight] = useState(160);
  const translateRef = useRef(t);
  translateRef.current = t;
  useEffect(() => {
    const element = outputRef.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setGridHeight(Math.max(80, Math.floor(entry.contentRect.height - 132))));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  usePageRetention(false, sql !== defaultSql || Boolean(parametersText) || loadingMode !== null);
  useEffect(() => {
    aliveRef.current = true;
    return () => { aliveRef.current = false; treeGeneration.current++; };
  }, []);

  useEffect(() => {
    void ensure([...sqlConsoleConfig.i18nNamespaces, ...dataSourceConfig.i18nNamespaces]);
  }, [ensure, locale]);

  const resolveNodeTypeLabel = useCallback((type: MetadataNodeType, fallback?: string | null) => {
    const t = translateRef.current;
    return t(`dna.federation.sqlConsole.page.nodeType.${type}`, fallback || type);
  }, []);

  const loadDataSources = useCallback(async () => {
    const t = translateRef.current;
    const generation = ++treeGeneration.current;
    setTreeLoading(true);
    setTreeError(null);
    setFailedTreeNode(null);
    try {
      const items: JdbcDataSourceOption[] = [];
      for (let pageNumber = 0; ; pageNumber++) {
        const page = normalizePage(await get<Page<JdbcDataSourceOption>>(dataSourceConfig.baseUrl, {page: pageNumber, size: 200}), 200);
        if (!aliveRef.current || generation !== treeGeneration.current) return;
        items.push(...(page.content ?? []));
        if (!page.content.length || pageNumber + 1 >= page.page.totalPages) break;
      }
      const enabled = items.filter(source => source.enabled !== false && Boolean(source.code));
      setDataSources(enabled);
      setCatalogCode(current => enabled.some(source => source.code === current) ? current : enabled[0]?.code);
      setTreeData(buildDataSourceTreeNodes(enabled, resolveNodeTypeLabel));
      setTreeRevision(value => value + 1);
      setSelectedKeys([]);
      setExpandedKeys([]);
      setSelectedTreeNode(null);
    } catch (error) {
      if (aliveRef.current && generation === treeGeneration.current) {
        setTreeError(resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.loadDataSources', '数据源树加载失败')));
      }
    } finally {
      if (aliveRef.current && generation === treeGeneration.current) setTreeLoading(false);
    }
  }, [resolveNodeTypeLabel]);

  useEffect(() => { void loadDataSources(); }, [loadDataSources]);

  const loadChildren = useCallback(async (dataSourceId: string, path: MetadataPathSegment[]) => {
    const data = await post<MetadataTreeNode[]>(`${metadataConfig.baseUrl}/${dataSourceId}/children`, {path});
    assertConsoleResponse(data, translateRef.current('dna.federation.sqlConsole.page.error.loadChildren', '树节点加载失败'));
    if (!Array.isArray(data)) throw new Error(translateRef.current('dna.federation.sqlConsole.page.error.loadChildren', '树节点加载失败'));
    return normalizeMetadataTreeNodes(data, resolveNodeTypeLabel, {dataSourceId});
  }, [resolveNodeTypeLabel]);

  const handleLoadData = useCallback(async (treeNode: object) => {
    const node = treeNode as MetadataTreeNode;
    if (node.isLeaf || node.loaded || !node.dataSourceId || pendingNodes.current.has(node.key)) {
      return;
    }
    const generation = treeGeneration.current;
    pendingNodes.current.add(node.key);
    try {
      const children = await loadChildren(node.dataSourceId, node.type === 'DATA_SOURCE' ? [] : node.path);
      if (aliveRef.current && generation === treeGeneration.current) {
        setTreeData((current) => replaceMetadataTreeChildren(current, node.key, children));
        setTreeError(null);
        setFailedTreeNode(null);
        setExpandedKeys(keys => keys.includes(node.key) ? keys : [...keys, node.key]);
      }
    } catch (error) {
      if (aliveRef.current && generation === treeGeneration.current) {
        setTreeError(resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.loadChildren', '树节点加载失败')));
        setFailedTreeNode(node);
        setExpandedKeys(keys => keys.filter(key => key !== node.key));
      }
      throw error; // Ant Tree must not mark a failed node loaded; expanding it again retries.
    } finally {
      pendingNodes.current.delete(node.key);
    }
  }, [loadChildren, message, t]);

  const handleSelectTreeNode = useCallback((keys: Key[]) => {
    setSelectedKeys(keys);
    setSelectedTreeNode(keys.length > 0 ? findMetadataTreeNodeByKey(treeData, keys[0]) : null);
  }, [treeData]);

  const selectedTreePath = useMemo(() => {
    if (!selectedTreeNode?.dataSourceId) {
      return null;
    }
    const dataSource = dataSources.find((item) => item.id === selectedTreeNode.dataSourceId);
    const rootCode = dataSource?.code || dataSource?.name || selectedTreeNode.dataSourceId;
    if (selectedTreeNode.type === 'DATA_SOURCE') {
      return quoteSqlPath([rootCode]);
    }
    return quoteSqlPath([rootCode, ...selectedTreeNode.path.map((segment) => segment.name)]);
  }, [dataSources, selectedTreeNode]);

  const buildSqlConsolePayload = useCallback((options?: BuildSqlConsolePayloadOptions): SqlConsolePayload | null => {
    const normalizedSql = (options?.executionSql ?? sql).trim();
    if (!normalizedSql) {
      message.warning(t('dna.federation.sqlConsole.page.warning.enterSql', '请输入要执行的 SQL'));
      return null;
    }
    const globalFlush = options?.allowGlobalFlush === true && isFlushCacheSql(normalizedSql);
    if (!catalogCode && !globalFlush) {
      message.warning(t('dna.federation.sqlConsole.page.warning.selectCatalog', '请选择数据源'));
      return null;
    }

    const payload: SqlConsolePayload = {sql: normalizedSql};
    if (catalogCode) {
      payload.catalogCode = catalogCode;
    }
    if (globalFlush) {
      return payload;
    }

    let parameters: unknown[] | undefined;
    const normalizedParameters = parametersText.trim();
    if (normalizedParameters) {
      let parsed: unknown;
      try {
        parsed = JSON.parse(normalizedParameters);
      } catch {
        message.warning(t('dna.federation.sqlConsole.page.warning.invalidParametersJson', '参数必须是合法的 JSON 数组'));
        return null;
      }
      if (!Array.isArray(parsed)) {
        message.warning(t('dna.federation.sqlConsole.page.warning.parametersMustBeArray', '参数必须是 JSON 数组'));
        return null;
      }
      parameters = parsed;
    }

    const normalizedDefaultSchema = defaultSchema.trim();
    if (normalizedDefaultSchema) {
      payload.defaultSchema = normalizedDefaultSchema;
    }
    if (maxRows && maxRows > 0) {
      payload.maxRows = maxRows;
    }
    if (parameters) {
      payload.parameters = parameters;
    }
    return payload;
  }, [catalogCode, defaultSchema, maxRows, message, parametersText, sql, t]);

  const submit = useCallback(async (mode: 'explain' | 'query') => {
    if (busyRef.current) return;
    const payload = buildSqlConsolePayload({
      allowGlobalFlush: mode === 'query',
      executionSql: editorRef.current?.getExecutionSql(),
    });
    if (!payload) {
      return;
    }
    busyRef.current = true;
    setLoadingMode(mode);
    setExecutionError(null);
    setExplainResult(null);
    setQueryResult(null);
    setUpdateResult(null);
    setExecuteType(null);
    setExecuteMessage(null);
    setResultRevision(value => value + 1);
    try {
      if (mode === 'explain') {
        const result = await post<SqlExplainResult>(`${sqlConsoleConfig.baseUrl}/explain`, payload);
        if (!aliveRef.current) return;
        assertConsoleResponse(result, t('dna.federation.sqlConsole.page.error.explain', '执行计划生成失败'));
        setExplainResult(result);
        setQueryResult(null);
        setUpdateResult(null);
        setExecuteType(null);
        setExecuteMessage(null);
        setActiveTabKey('analysis');
      } else {
        const result = await post<SqlExecuteResult>(`${sqlConsoleConfig.baseUrl}/execute`, payload);
        if (!aliveRef.current) return;
        assertConsoleResponse(result, t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'));
        setExecuteType(result.type);
        setExecuteMessage(result.message ?? null);
        if (result.type === 'QUERY' && result.queryResult) {
          setExplainResult(toExplainSnapshot(result.queryResult));
          setQueryResult(result.queryResult);
          setUpdateResult(null);
          setActiveTabKey('result');
        } else if ((result.type === 'DML' || result.type === 'DDL') && result.updateResult) {
          setExplainResult(null);
          setQueryResult(null);
          setUpdateResult(result.updateResult);
          setActiveTabKey('result');
        } else if (result.type === 'FLUSH_CACHE') {
          setExplainResult(null);
          setQueryResult(null);
          setUpdateResult(null);
          setActiveTabKey('result');
        } else {
          throw new Error(t('dna.federation.sqlConsole.page.error.unsupportedExecuteResult', '不支持的执行结果类型'));
        }
      }
    } catch (error) {
      if (!aliveRef.current) return;
      setExecutionError(resolveErrorMessage(
        error,
        mode === 'explain'
          ? t('dna.federation.sqlConsole.page.error.explain', '执行计划生成失败')
          : t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'),
      ));
    } finally {
      busyRef.current = false;
      if (aliveRef.current) setLoadingMode(null);
    }
  }, [buildSqlConsolePayload, message, t]);

  const exportResults = useCallback((format: 'CSV' | 'JSON') => {
    if (busyRef.current || !queryResult) return;
    busyRef.current = true;
    setExportFormat(format);
    try {
      const content = format === 'CSV'
        ? resultCsv(queryResult.columns, queryResult.rows)
        : JSON.stringify({columns: queryResult.columns, rows: queryResult.rows, truncated: queryResult.truncated}, null, 2);
      saveBlob(new Blob([content], {type: format === 'CSV' ? 'text/csv;charset=utf-8' : 'application/json'}), `result.${format.toLowerCase()}`);
      message.success(t('dna.federation.sqlConsole.page.success.export', '查询结果已导出'));
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.export', '查询结果导出失败')));
    } finally {
      busyRef.current = false;
      setExportFormat(null);
    }
  }, [queryResult, message, t]);

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

  const renderScrollPane = useCallback((children: ReactNode) => (
    <div className="dna-sql-console-scroll-pane">
      {children}
    </div>
  ), []);

  const resultTabs = useMemo<TabsProps['items']>(() => [
    {
      key: 'analysis',
      label: t('dna.federation.sqlConsole.page.tab.analysis', '执行分析'),
      children: renderScrollPane(analysisResult ? (
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
      children: renderScrollPane(analysisResult ? (
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
      children: renderScrollPane(analysisResult ? (
        <Paragraph style={{whiteSpace: 'pre-wrap', marginBottom: 0}}>
          {analysisResult.planText || t('dna.federation.sqlConsole.page.empty.noPlan', '暂无执行计划')}
        </Paragraph>
      ) : (
        <Empty description={t('dna.federation.sqlConsole.page.empty.planPending', '查看执行计划或执行 SQL 后展示 Calcite 执行计划')} />
      )),
    },
    {
      key: 'result',
      label: t('dna.federation.sqlConsole.page.tab.result', '执行结果'),
      children: renderScrollPane(queryResult ? (
        <Space className="dna-sql-console-result-content" orientation="vertical" size={4} style={{display: 'flex'}}>
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
          <Table<ResultRow>
            storageKey="dna.sql-console.result"
            query={{mode: 'local', scope: 'result', fields: sqlQueryFields(queryResult.columns)}}
            includeToolbarInScroll
            key={resultRevision}
            className="dna-sql-console-result-table"
            size="small"
            rowKey="key"
            scroll={{x: 'max-content', y: Math.max(80, gridHeight - (queryResult.truncated ? 100 : 0))}}
            pagination={{defaultPageSize: 50, showSizeChanger: true, size: 'small', pageSizeOptions: [20, 50, 100, 200]}}
            columns={resultColumns}
            dataSource={resultData}
          />
        </Space>
      ) : updateResult ? (
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
      )),
    },
  ], [analysisResult, executeMessage, executeType, queryResult, renderScrollPane, resultColumns, resultData, resultRevision, gridHeight, t, updateResult]);

  const visibleTree = useMemo(() => filterLoadedTree(treeData, search), [treeData, search]);
  const visibleExpandedKeys = useMemo(() => {
    if (!search.trim()) return expandedKeys;
    const keys: Key[] = [];
    const visit = (nodes: MetadataTreeNode[]) => nodes.forEach(node => {
      if (node.children?.length) { keys.push(node.key); visit(node.children); }
    });
    visit(visibleTree);
    return keys;
  }, [expandedKeys, search, visibleTree]);
  const busy = loadingMode !== null || exportFormat !== null;
  const controls = (
    <div className="dna-sql-options">
      <label>{t('dna.federation.sqlConsole.page.placeholder.defaultSchema', '默认 Schema')}
        <Input value={defaultSchema} onChange={event => setDefaultSchema(event.target.value)} disabled={busy} />
      </label>
      <label>{t('dna.federation.sqlConsole.page.placeholder.parameters', '参数 JSON 数组，例如 [1, "Alice", null]')}
        <Input.TextArea value={parametersText} onChange={event => setParametersText(event.target.value)}
          autoSize={{minRows: 3, maxRows: 8}} disabled={busy} />
      </label>
    </div>
  );

  return (
    <div className="dna-sql-workbench" style={{
      '--sql-bg': token.colorBgContainer, '--sql-panel': token.colorFillAlter,
      '--sql-border': token.colorBorderSecondary, '--sql-text': token.colorText,
      '--sql-muted': token.colorTextSecondary,
    } as React.CSSProperties}>
      <Splitter>
        <Splitter.Panel defaultSize={260} min={180} max="45%" collapsible>
          <aside className="dna-sql-explorer">
            <div className="dna-sql-toolwindow-title">
              <span><DatabaseOutlined /> {t('dna.federation.sqlConsole.page.card.tree', '数据库')}</span>
              <Button type="text" size="small" icon={<ReloadOutlined />} loading={treeLoading}
                aria-label={t('dna.federation.sqlConsole.page.button.refreshTree', '刷新树')}
                onClick={() => void loadDataSources()} />
            </div>
            <Input.Search allowClear value={search} onChange={event => setSearch(event.target.value)}
              placeholder={t('dna.federation.sqlConsole.page.search', '搜索已加载对象')} />
            {treeError && <Alert type="error" showIcon closable onClose={() => setTreeError(null)} title={treeError}
              action={<Button size="small" loading={retryingTree} onClick={() => {
                setRetryingTree(true);
                void (failedTreeNode ? handleLoadData(failedTreeNode) : loadDataSources())
                  .catch(() => undefined).finally(() => setRetryingTree(false));
              }}>{t('dna.federation.sqlConsole.page.retry', '重试')}</Button>} />}
            <div className="dna-sql-tree">
              {treeLoading ? <Spin /> : treeData.length ? (
                <Tree key={treeRevision} blockNode showLine={{showLeafIcon: false}}
                  treeData={visibleTree} loadData={search.trim() ? undefined : handleLoadData} selectedKeys={selectedKeys}
                  expandedKeys={visibleExpandedKeys} onExpand={setExpandedKeys}
                  filterTreeNode={node => Boolean(search.trim()) && (node.rawTitle ?? '').toLowerCase().includes(search.trim().toLowerCase())}
                  onSelect={handleSelectTreeNode}
                  onDoubleClick={(_, node) => {
                    const source = dataSources.find(item => item.id === node.dataSourceId);
                    if (source?.code && node.path?.length) {
                      editorRef.current?.insertText(quoteSqlPath([source.code, ...node.path.map(item => item.name)]));
                    }
                  }}
                />
              ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={t('dna.federation.sqlConsole.page.empty.noDataSources', '当前没有可用的数据源')} />}
            </div>
            {selectedTreePath && <div className="dna-sql-object-path">
              <Typography.Text ellipsis={{tooltip: selectedTreePath}}>{selectedTreePath}</Typography.Text>
              <Button size="small" type="text" onClick={() => editorRef.current?.insertText(selectedTreePath)}>
                {t('dna.federation.sqlConsole.page.button.insertPath', '插入路径')}
              </Button>
            </div>}
          </aside>
        </Splitter.Panel>
        <Splitter.Panel min={360}>
          <div className="dna-sql-main">
            <div className="dna-sql-file-tab"><span>console.sql</span><Typography.Text type="secondary">SQL</Typography.Text></div>
            <div className="dna-sql-toolbar">
              <Button type="text" className="dna-sql-run" icon={<PlayCircleOutlined />}
                loading={loadingMode === 'query'} disabled={busy || !catalogCode}
                title={t('dna.federation.sqlConsole.page.runHint', '执行选中内容或光标所在语句（Ctrl/⌘ + Enter）')}
                onClick={() => void submit('query')}>
                {t('dna.federation.sqlConsole.page.button.query', '执行 SQL')}
              </Button>
              <Button type="text" loading={loadingMode === 'explain'} disabled={busy || !catalogCode}
                onClick={() => void submit('explain')}>
                {t('dna.federation.sqlConsole.page.button.explain', '查看执行计划')}
              </Button>
              <Select className="dna-sql-source-select" size="small" showSearch={{optionFilterProp: 'label'}}
                aria-label={t('dna.federation.sqlConsole.page.field.dataSource', '执行数据源')}
                placeholder={t('dna.federation.sqlConsole.page.warning.selectCatalog', '请选择数据源')}
                value={catalogCode} loading={treeLoading} disabled={busy}
                onChange={value => {setCatalogCode(value); setDefaultSchema('');}}
                options={dataSources.map(source => ({label: resolveDataSourceLabel(source), value: source.code}))} />
              <InputNumber size="small" min={1} max={1000000} precision={0} value={maxRows}
                aria-label={t('dna.federation.sqlConsole.page.field.maxRows', '最大返回行数')}
                title={t('dna.federation.sqlConsole.page.field.maxRows', '最大返回行数')}
                onChange={value => setMaxRows(value)} disabled={busy} />
              <Popover trigger="click" placement="bottomRight" content={controls} open={selected && optionsOpen} onOpenChange={setOptionsOpen}>
                <Button type="text" icon={<SettingOutlined />}
                  aria-label={t('dna.federation.sqlConsole.page.options', '查询参数')} />
              </Popover>
            </div>
            <Splitter orientation="vertical" className="dna-sql-editor-split">
              <Splitter.Panel defaultSize="45%" min={100}>
                <div className="dna-sql-editor">
                  <SqlEditor ref={editorRef} value={sql} onChange={setSql}
                    dark={token.colorBgContainer.toLowerCase() !== '#ffffff' && token.colorBgContainer !== '#fff'}
                    onExecute={() => void submit('query')} />
                </div>
              </Splitter.Panel>
              <Splitter.Panel min={120}>
                <div className="dna-sql-output" ref={outputRef}>
                  {executionError && <Alert type="error" showIcon
                    title={t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败')}
                    description={<pre>{executionError}</pre>} />}
                  {loadingMode && <div role="status" className="dna-sql-progress"><Spin size="small" />
                    {loadingMode === 'explain'
                      ? t('dna.federation.sqlConsole.page.progress.explain', '正在生成执行计划...')
                      : t('dna.federation.sqlConsole.page.progress.query', '正在执行 SQL...')}
                  </div>}
                  <Tabs className="dna-sql-console-tabs" size="small" activeKey={activeTabKey}
                    onChange={key => setActiveTabKey(key as ResultTabKey)}
                    items={[...resultTabs!.filter(item => item.key === 'result'), ...resultTabs!.filter(item => item.key !== 'result')]}
                    tabBarExtraContent={<Space size={0}>
                      <Button type="text" size="small" icon={<DownloadOutlined />} disabled={busy || !queryResult}
                        onClick={() => exportResults('CSV')}>CSV</Button>
                      <Button type="text" size="small" disabled={busy || !queryResult}
                        onClick={() => exportResults('JSON')}>JSON</Button>
                    </Space>} />
                </div>
              </Splitter.Panel>
            </Splitter>
            <div className="dna-sql-statusbar" role="status">
              <span>{catalogCode || '—'}</span>
              <span>{queryResult ? `${queryResult.returnedRows} rows · ${queryResult.executionTimeMs} ms`
                : updateResult ? `${updateResult.affectedRows} rows · ${updateResult.executionTimeMs} ms` : 'Ctrl/⌘ + Enter'}</span>
            </div>
          </div>
        </Splitter.Panel>
      </Splitter>
    </div>
  );
};

export default App;
