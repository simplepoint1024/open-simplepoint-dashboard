import api from '@/api';
import {CodeOutlined, DatabaseOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, EyeOutlined, FontSizeOutlined, PlusOutlined, PlayCircleOutlined, ReloadOutlined, SettingOutlined} from '@simplepoint/shared/types/icon';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {assertConsoleResponse, buildTableSqlTemplate, filterLoadedTree, quoteSqlPath, resultCsv, sqlStatements, type SqlTemplateKind, type SqlTemplateStructure} from './sqlConsoleUtils';
import {sqlQueryFields} from './sqlQueryFields';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {normalizePage, type Page} from '@simplepoint/shared/types/request';
import {Alert, App as AntApp, Button, Dropdown, Popover, Splitter, Descriptions, Empty, Input, InputNumber, Space, Spin, Switch, Tabs, Tag, Tree, Typography, theme} from 'antd';
import Table from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import JdbcDataSourceSelect, {enabledSources, sourceCodeFilter, type JdbcSourceOption} from '../JdbcDataSource/select';
import type {MenuProps, TabsProps} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {Key, ReactNode} from 'react';
import {SqlEditor, type SqlEditorRef} from './SqlEditor';
import type {
  SqlCompletionCatalog,
  SqlCompletionDataSource,
  SqlCompletionMetadataProvider,
  SqlCompletionPathSegment,
  SqlMetadataTarget,
} from './sqlCompletion';
import './index.css';
import {
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
  catalogCode?: string;
  executionSql?: string;
  ignoreParameters?: boolean;
  parameters?: unknown[];
};

type MetadataTableStructure = SqlTemplateStructure & {
  tablePath?: MetadataPathSegment[];
  dialectCode?: string;
  dialectName?: string;
};

type MetadataTableTarget = {
  catalogCode: string;
  path: string[];
};

type ResultRow = {
  key: number;
  values: unknown[];
};

type StatementExecutionStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED';

type StatementExecutionRecord = {
  key: number;
  sequence: number;
  sql: string;
  status: StatementExecutionStatus;
  type?: SqlExecuteResult['type'];
  durationMs?: number;
  summary?: string;
  error?: string;
  result?: SqlExecuteResult;
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
  const permissions = useData(['dna-sql-permissions', sqlConsoleConfig.baseUrl], ({signal}) =>
    get<{canExecute: boolean; canExplain: boolean}>(`${sqlConsoleConfig.baseUrl}/workbench-permissions`, undefined, {signal}), {retry: false});
  const canExecute = permissions.data?.canExecute === true;
  const canExplain = permissions.data?.canExplain === true;
  const [catalogCode, setCatalogCode] = useState<string>();
  const catalogCodeRef = useRef(catalogCode);
  catalogCodeRef.current = catalogCode;
  const [dataSources, setDataSources] = useState<JdbcDataSourceOption[]>([]);
  const [treeData, setTreeData] = useState<MetadataTreeNode[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<Key[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<Key[]>([]);
  const expandedKeysRef = useRef<Key[]>([]);
  const [selectedTreeNode, setSelectedTreeNode] = useState<MetadataTreeNode | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [sql, setSql] = useState(defaultSql);
  const [defaultSchema, setDefaultSchema] = useState('');
  const [maxRows, setMaxRows] = useState<number | null>(200);
  const [parametersText, setParametersText] = useState('');
  const [loadingMode, setLoadingMode] = useState<'explain' | 'query' | null>(null);
  const [exportFormat, setExportFormat] = useState<'CSV' | 'JSON' | null>(null);
  const [formattingSql, setFormattingSql] = useState(false);
  const [activeTabKey, setActiveTabKey] = useState<ResultTabKey>('result');
  const [explainResult, setExplainResult] = useState<SqlExplainResult | null>(null);
  const [queryResult, setQueryResult] = useState<SqlQueryResult | null>(null);
  const [updateResult, setUpdateResult] = useState<SqlUpdateResult | null>(null);
  const [executeType, setExecuteType] = useState<SqlExecuteResult['type'] | null>(null);
  const [executeMessage, setExecuteMessage] = useState<string | null>(null);
  const [statementExecutions, setStatementExecutions] = useState<StatementExecutionRecord[]>([]);
  const [selectedStatementKey, setSelectedStatementKey] = useState<number | null>(null);
  const [continueOnError, setContinueOnError] = useState(false);
  const [statementProgress, setStatementProgress] = useState<{current: number; total: number} | null>(null);
  const editorRef = useRef<SqlEditorRef>(null);
  const busyRef = useRef(false);
  const aliveRef = useRef(true);
  const treeGeneration = useRef(0);
  const sourceRequest = useRef<AbortController | undefined>(undefined);
  const [executionError, setExecutionError] = useState<string | null>(null);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [failedTreeNode, setFailedTreeNode] = useState<MetadataTreeNode | null>(null);
  const [retryingTree, setRetryingTree] = useState(false);
  const pendingNodes = useRef(new Set<Key>());
  const tableStructureCache = useRef(new Map<string, Promise<MetadataTableStructure>>());
  const completionCatalogCache = useRef(new Map<string, Promise<SqlCompletionCatalog>>());
  const [treeRevision, setTreeRevision] = useState(0);
  const [resultRevision, setResultRevision] = useState(0);
  const [search, setSearch] = useState('');
  const {token} = theme.useToken();
  const selected = usePageSelected();
  const [optionsOpen, setOptionsOpen] = useState(false);
  const outputRef = useRef<HTMLDivElement>(null);
  const treeContainerRef = useRef<HTMLDivElement>(null);
  const treeClickTimer = useRef<number | null>(null);
  const [gridHeight, setGridHeight] = useState(160);
  const formattingSqlRef = useRef(false);
  const translateRef = useRef(t);
  translateRef.current = t;
  expandedKeysRef.current = expandedKeys;
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
    return () => { aliveRef.current = false; treeGeneration.current++; sourceRequest.current?.abort(); };
  }, []);
  useEffect(() => () => {
    if (treeClickTimer.current !== null) window.clearTimeout(treeClickTimer.current);
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
    sourceRequest.current?.abort();
    const abort = new AbortController(); sourceRequest.current = abort;
    setTreeLoading(true);
    tableStructureCache.current.clear();
    completionCatalogCache.current.clear();
    setTreeError(null);
    setFailedTreeNode(null);
    try {
      const page = normalizePage(await get<Page<JdbcDataSourceOption>>(dataSourceConfig.baseUrl,
        {page: 0, size: 20, sort: 'name,asc', ...enabledSources}, {signal: abort.signal}), 20);
      if (!aliveRef.current || generation !== treeGeneration.current) return;
      const items = [...page.content];
      // Preserve an off-page selection on refresh using a single exact, permission-scoped lookup.
      const currentCode = catalogCodeRef.current;
      if (currentCode && !items.some(source => source.code === currentCode)) {
        const selectedPage = normalizePage(await get<Page<JdbcDataSourceOption>>(dataSourceConfig.baseUrl,
          {page: 0, size: 1, ...sourceCodeFilter(currentCode), ...enabledSources}, {signal: abort.signal}));
        if (!aliveRef.current || generation !== treeGeneration.current) return;
        items.push(...selectedPage.content);
      }
      const enabled = items.filter(source => source.enabled !== false && Boolean(source.code)
        && source.code !== '***' && source.id !== '***');
      setDataSources(enabled);
      setCatalogCode(current => enabled.some(source => source.code === current) ? current : enabled[0]?.code);
      setTreeData(buildDataSourceTreeNodes(enabled, resolveNodeTypeLabel));
      setTreeRevision(value => value + 1);
      setSelectedKeys([]);
      setExpandedKeys([]);
      setSelectedTreeNode(null);
    } catch (error) {
      if (aliveRef.current && generation === treeGeneration.current && !abort.signal.aborted) {
        setTreeError(resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.loadDataSources', '数据源树加载失败')));
      }
    } finally {
      if (aliveRef.current && generation === treeGeneration.current) setTreeLoading(false);
    }
  }, [resolveNodeTypeLabel]);

  useEffect(() => { void loadDataSources(); }, [loadDataSources]);

  const selectDataSource = useCallback((code?: string, row?: JdbcSourceOption) => {
    if (busyRef.current || !code || !row?.id) return;
    setCatalogCode(code); setDefaultSchema(''); setSelectedKeys([]); setSelectedTreeNode(null);
    // First directory page plus a bounded set of explicitly visited sources supports completion and the tree.
    setDataSources(current => [...current.filter(source => source.id !== row.id).slice(-39), row]);
    setTreeData(current => current.some(node => node.dataSourceId === row.id) ? current
      : [...current.slice(-39), ...buildDataSourceTreeNodes([row], resolveNodeTypeLabel)]);
  }, [resolveNodeTypeLabel]);

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

  const handleSelectTreeNode = useCallback((_keys: Key[], info: {node: object}) => {
    const node = info.node as MetadataTreeNode;
    setSelectedKeys([node.key]);
    setSelectedTreeNode(node);
    if (treeClickTimer.current !== null) window.clearTimeout(treeClickTimer.current);
    if (node.isLeaf) return;
    treeClickTimer.current = window.setTimeout(() => {
      treeClickTimer.current = null;
      if (expandedKeysRef.current.includes(node.key)) {
        setExpandedKeys(keys => keys.filter(key => key !== node.key));
      } else if (node.loaded) {
        setExpandedKeys(keys => keys.includes(node.key) ? keys : [...keys, node.key]);
      } else {
        void handleLoadData(node).catch(() => undefined);
      }
    }, 220);
  }, [handleLoadData]);

  const resolveMetadataTableTarget = useCallback((node: MetadataTreeNode): MetadataTableTarget | null => {
    if (!node.dataSourceId || !node.path.length) {
      return null;
    }
    const source = dataSources.find(item => item.id === node.dataSourceId);
    if (!source?.code) {
      return null;
    }
    return {
      catalogCode: source.code,
      path: [source.code, ...node.path.map(segment => segment.name)],
    };
  }, [dataSources]);

  const selectedTreePath = useMemo(() => {
    if (!selectedTreeNode) return null;
    const target = resolveMetadataTableTarget(selectedTreeNode);
    if (target) return quoteSqlPath(target.path);
    if (!selectedTreeNode.dataSourceId) return null;
    const dataSource = dataSources.find(item => item.id === selectedTreeNode.dataSourceId);
    const rootCode = dataSource?.code || dataSource?.name || selectedTreeNode.dataSourceId;
    if (selectedTreeNode.type === 'DATA_SOURCE') {
      return quoteSqlPath([rootCode]);
    }
    return quoteSqlPath([rootCode, ...selectedTreeNode.path.map((segment) => segment.name)]);
  }, [dataSources, resolveMetadataTableTarget, selectedTreeNode]);

  const buildSqlConsolePayload = useCallback((options?: BuildSqlConsolePayloadOptions): SqlConsolePayload | null => {
    const normalizedSql = (options?.executionSql ?? sql).trim();
    if (!normalizedSql) {
      message.warning(t('dna.federation.sqlConsole.page.warning.enterSql', '请输入要执行的 SQL'));
      return null;
    }
    const effectiveCatalogCode = options?.catalogCode ?? catalogCode;
    const globalFlush = options?.allowGlobalFlush === true && isFlushCacheSql(normalizedSql);
    if (!effectiveCatalogCode && !globalFlush) {
      message.warning(t('dna.federation.sqlConsole.page.warning.selectCatalog', '请选择数据源'));
      return null;
    }

    const payload: SqlConsolePayload = {sql: normalizedSql};
    if (effectiveCatalogCode) {
      payload.catalogCode = effectiveCatalogCode;
    }
    if (globalFlush) {
      return payload;
    }

    let parameters = options?.parameters;
    const normalizedParameters = options?.ignoreParameters || parameters !== undefined ? '' : parametersText.trim();
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

  const clearExecutionDetail = useCallback(() => {
    setExecutionError(null);
    setExplainResult(null);
    setQueryResult(null);
    setUpdateResult(null);
    setExecuteType(null);
    setExecuteMessage(null);
    setResultRevision(value => value + 1);
  }, []);

  const applyExecuteResult = useCallback((result: SqlExecuteResult) => {
    setExecutionError(null);
    setExecuteType(result.type);
    setExecuteMessage(result.message ?? null);
    setResultRevision(value => value + 1);
    if (result.type === 'QUERY' && result.queryResult) {
      setExplainResult(toExplainSnapshot(result.queryResult));
      setQueryResult(result.queryResult);
      setUpdateResult(null);
    } else if ((result.type === 'DML' || result.type === 'DDL') && result.updateResult) {
      if (result.type === 'DDL') {
        tableStructureCache.current.clear();
        completionCatalogCache.current.clear();
      }
      setExplainResult(null);
      setQueryResult(null);
      setUpdateResult(result.updateResult);
    } else if (result.type === 'FLUSH_CACHE') {
      tableStructureCache.current.clear();
      completionCatalogCache.current.clear();
      setExplainResult(null);
      setQueryResult(null);
      setUpdateResult(null);
    } else {
      throw new Error(t('dna.federation.sqlConsole.page.error.unsupportedExecuteResult', '不支持的执行结果类型'));
    }
    setActiveTabKey('result');
  }, [t]);

  const selectStatementExecution = useCallback((record: StatementExecutionRecord) => {
    setSelectedStatementKey(record.key);
    if (record.result) {
      applyExecuteResult(record.result);
      return;
    }
    clearExecutionDetail();
    setExecutionError(record.error ?? null);
    setActiveTabKey('result');
  }, [applyExecuteResult, clearExecutionDetail]);

  const resolveStatementParameters = useCallback((statementCount: number): Array<unknown[] | undefined> | null => {
    const normalized = parametersText.trim();
    if (!normalized) return Array.from({length: statementCount}, () => undefined);
    let parsed: unknown;
    try {
      parsed = JSON.parse(normalized);
    } catch {
      message.warning(t('dna.federation.sqlConsole.page.warning.invalidParametersJson', '参数必须是合法的 JSON 数组'));
      return null;
    }
    if (!Array.isArray(parsed) || parsed.length !== statementCount || !parsed.every(Array.isArray)) {
      message.warning(t(
        'dna.federation.sqlConsole.page.warning.batchParameters',
        '执行多条 SQL 时，参数必须是与语句数量一致的二维 JSON 数组',
      ));
      return null;
    }
    return parsed as unknown[][];
  }, [message, parametersText, t]);

  const summarizeExecution = useCallback((result: SqlExecuteResult) => {
    if (result.type === 'QUERY' && result.queryResult) {
      return t('dna.federation.sqlConsole.page.batch.returnedRows', '返回 {rows} 行')
        .replace('{rows}', String(result.queryResult.returnedRows));
    }
    if ((result.type === 'DML' || result.type === 'DDL') && result.updateResult) {
      return t('dna.federation.sqlConsole.page.batch.affectedRows', '影响 {rows} 行')
        .replace('{rows}', String(result.updateResult.affectedRows));
    }
    return result.message ?? '-';
  }, [t]);

  const submit = useCallback(async (mode: 'explain' | 'query', options?: BuildSqlConsolePayloadOptions) => {
    if (busyRef.current || !(mode === 'explain' ? canExplain : canExecute)) return;
    const requestedSql = options?.executionSql ?? editorRef.current?.getExecutionSql() ?? '';
    const statements = sqlStatements(requestedSql);
    if (statements.length === 0) {
      message.warning(t('dna.federation.sqlConsole.page.warning.enterSql', '请输入要执行的 SQL'));
      return;
    }
    if (mode === 'explain' && statements.length > 1) {
      message.warning(t('dna.federation.sqlConsole.page.warning.explainSingle', '执行计划一次只能分析一条 SQL'));
      return;
    }

    const isBatch = mode === 'query' && statements.length > 1;
    const parameterSets = isBatch ? resolveStatementParameters(statements.length) : null;
    if (isBatch && !parameterSets) return;
    const firstPayload = buildSqlConsolePayload({
      ...options,
      allowGlobalFlush: mode === 'query',
      executionSql: statements[0],
      ...(isBatch ? {ignoreParameters: true, parameters: parameterSets?.[0]} : {}),
    });
    if (!firstPayload) return;

    busyRef.current = true;
    setLoadingMode(mode);
    clearExecutionDetail();
    if (!isBatch) {
      setStatementExecutions([]);
      setSelectedStatementKey(null);
    }
    try {
      if (mode === 'explain') {
        const result = await post<SqlExplainResult>(`${sqlConsoleConfig.baseUrl}/explain`, firstPayload);
        if (!aliveRef.current) return;
        assertConsoleResponse(result, t('dna.federation.sqlConsole.page.error.explain', '执行计划生成失败'));
        setExplainResult(result);
        setActiveTabKey('analysis');
        return;
      }

      if (!isBatch) {
        const result = await post<SqlExecuteResult>(`${sqlConsoleConfig.baseUrl}/execute`, firstPayload);
        if (!aliveRef.current) return;
        assertConsoleResponse(result, t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'));
        applyExecuteResult(result);
        return;
      }

      let records: StatementExecutionRecord[] = statements.map((statement, index) => ({
        key: index + 1,
        sequence: index + 1,
        sql: statement,
        status: 'PENDING',
      }));
      setStatementExecutions(records);
      setSelectedStatementKey(records[0]?.key ?? null);
      setActiveTabKey('result');

      for (let index = 0; index < records.length; index += 1) {
        setStatementProgress({current: index + 1, total: records.length});
        records = records.map((record, recordIndex) => recordIndex === index
          ? {...record, status: 'RUNNING'}
          : record);
        setStatementExecutions(records);
        const startedAt = Date.now();
        try {
          const payload = index === 0 ? firstPayload : buildSqlConsolePayload({
            ...options,
            allowGlobalFlush: true,
            executionSql: statements[index],
            ignoreParameters: true,
            parameters: parameterSets?.[index],
          });
          if (!payload) throw new Error(t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'));
          const result = await post<SqlExecuteResult>(`${sqlConsoleConfig.baseUrl}/execute`, payload);
          if (!aliveRef.current) return;
          assertConsoleResponse(result, t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'));
          // Validate the discriminator before recording the statement as successful.
          if (!(result.type === 'QUERY' && result.queryResult)
            && !((result.type === 'DML' || result.type === 'DDL') && result.updateResult)
            && result.type !== 'FLUSH_CACHE') {
            throw new Error(t('dna.federation.sqlConsole.page.error.unsupportedExecuteResult', '不支持的执行结果类型'));
          }
          if (result.type === 'DDL' || result.type === 'FLUSH_CACHE') {
            tableStructureCache.current.clear();
            completionCatalogCache.current.clear();
          }
          const durationMs = result.queryResult?.executionTimeMs
            ?? result.updateResult?.executionTimeMs
            ?? Date.now() - startedAt;
          records = records.map((record, recordIndex) => recordIndex === index ? {
            ...record,
            status: 'SUCCESS',
            type: result.type,
            durationMs,
            summary: summarizeExecution(result),
            result,
          } : record);
        } catch (error) {
          const detail = resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'));
          records = records.map((record, recordIndex) => recordIndex === index ? {
            ...record,
            status: 'FAILED',
            durationMs: Date.now() - startedAt,
            summary: detail,
            error: detail,
          } : !continueOnError && recordIndex > index ? {...record, status: 'SKIPPED'} : record);
          setStatementExecutions(records);
          if (!continueOnError) break;
        }
        setStatementExecutions(records);
      }

      const failed = records.filter(record => record.status === 'FAILED').length;
      const skipped = records.filter(record => record.status === 'SKIPPED').length;
      const focus = records.find(record => record.status === 'FAILED')
        ?? [...records].reverse().find(record => record.status === 'SUCCESS');
      if (focus) selectStatementExecution(focus);
      const completion = t(
        'dna.federation.sqlConsole.page.batch.completed',
        '脚本执行完成：{success} 成功，{failed} 失败，{skipped} 跳过',
      )
        .replace('{success}', String(records.filter(record => record.status === 'SUCCESS').length))
        .replace('{failed}', String(failed))
        .replace('{skipped}', String(skipped));
      if (failed > 0) message.warning(completion);
      else message.success(completion);
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
      if (aliveRef.current) {
        setLoadingMode(null);
        setStatementProgress(null);
      }
    }
  }, [applyExecuteResult, buildSqlConsolePayload, clearExecutionDetail, continueOnError, message,
    resolveStatementParameters, selectStatementExecution, summarizeExecution, t, canExecute, canExplain]);

  const loadTableStructureByPath = useCallback(async (
    dataSourceId: string,
    path: readonly (MetadataPathSegment | SqlCompletionPathSegment)[],
  ) => {
    const cacheKey = `${dataSourceId}:${JSON.stringify(path)}`;
    let request = tableStructureCache.current.get(cacheKey);
    if (!request) {
      request = post<MetadataTableStructure>(`${metadataConfig.baseUrl}/${dataSourceId}/structure`, {path})
        .then(result => {
          assertConsoleResponse(result, translateRef.current('dna.federation.sqlConsole.page.error.loadStructure', '表结构加载失败'));
          return result;
        });
      tableStructureCache.current.set(cacheKey, request);
    }
    try {
      return await request;
    } catch (error) {
      tableStructureCache.current.delete(cacheKey);
      throw error;
    }
  }, []);

  const loadTableStructure = useCallback(async (node: MetadataTreeNode) => {
    if (!node.dataSourceId) {
      throw new Error(t('dna.federation.sqlConsole.page.error.loadStructure', '表结构加载失败'));
    }
    return loadTableStructureByPath(node.dataSourceId, node.path);
  }, [loadTableStructureByPath, t]);

  const loadCompletionCatalog = useCallback(async (dataSourceId: string) => {
    let request = completionCatalogCache.current.get(dataSourceId);
    if (!request) {
      request = get<SqlCompletionCatalog>(`${metadataConfig.baseUrl}/${dataSourceId}/completion-catalog`)
        .then(result => {
          assertConsoleResponse(result, translateRef.current(
            'dna.federation.sqlConsole.page.error.loadCompletion',
            'SQL 补全元数据加载失败',
          ));
          if (!Array.isArray(result.relations)) {
            throw new Error(translateRef.current(
              'dna.federation.sqlConsole.page.error.loadCompletion',
              'SQL 补全元数据加载失败',
            ));
          }
          return result;
        });
      completionCatalogCache.current.set(dataSourceId, request);
    }
    try {
      return await request;
    } catch (error) {
      completionCatalogCache.current.delete(dataSourceId);
      throw error;
    }
  }, []);

  const completionDataSources = useMemo<SqlCompletionDataSource[]>(() => dataSources.flatMap(source => source.code ? [{
    id: source.id,
    code: source.code,
    name: source.name,
    databaseProductName: source.databaseProductName,
  }] : []), [dataSources]);

  const sqlCompletion = useMemo<SqlCompletionMetadataProvider>(() => ({
    dataSources: completionDataSources,
    catalogCode,
    defaultSchema,
    loadCatalog: loadCompletionCatalog,
    loadStructure: loadTableStructureByPath,
  }), [catalogCode, completionDataSources, defaultSchema, loadCompletionCatalog, loadTableStructureByPath]);

  useEffect(() => {
    const selected = completionDataSources.find(source => source.code === catalogCode);
    if (selected) void loadCompletionCatalog(selected.id).catch(() => undefined);
  }, [catalogCode, completionDataSources, loadCompletionCatalog]);

  const revealMetadata = useCallback(async (target: SqlMetadataTarget) => {
    const root = treeData.find(node => node.type === 'DATA_SOURCE' && node.dataSourceId === target.dataSourceId);
    if (!root) {
      message.error(t('dna.federation.sqlConsole.page.error.revealMetadata', '定位元数据失败'));
      return;
    }

    try {
      setSearch('');
      let parent = root;
      const ancestorKeys: Key[] = [root.key];
      for (const segment of target.path) {
        let children = parent.loaded ? parent.children ?? [] : [];
        let child = children.find(node => node.type === segment.type && node.rawTitle === segment.name);
        if (!child) {
          children = await loadChildren(target.dataSourceId, parent.type === 'DATA_SOURCE' ? [] : parent.path);
          setTreeData(current => replaceMetadataTreeChildren(current, parent.key, children));
          child = children.find(node => node.type === segment.type && node.rawTitle === segment.name);
        }
        if (!child) {
          throw new Error(t('dna.federation.sqlConsole.page.error.revealMetadata', '定位元数据失败'));
        }
        parent = child;
        if (!parent.isLeaf) ancestorKeys.push(parent.key);
      }

      const selectedNode = parent;
      const source = dataSources.find(item => item.id === target.dataSourceId);
      if (source?.code) setCatalogCode(source.code);
      setSelectedKeys([selectedNode.key]);
      setSelectedTreeNode(selectedNode);
      setExpandedKeys(keys => [...new Set([...keys, ...ancestorKeys])]);
      window.requestAnimationFrame(() => window.requestAnimationFrame(() => {
        treeContainerRef.current?.querySelector<HTMLElement>('.ant-tree-node-selected')?.scrollIntoView({block: 'nearest'});
      }));
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('dna.federation.sqlConsole.page.error.revealMetadata', '定位元数据失败'),
      ));
    }
  }, [dataSources, loadChildren, message, t, treeData]);

  const handleRevealMetadata = useCallback((target: SqlMetadataTarget) => {
    void revealMetadata(target);
  }, [revealMetadata]);

  const selectMetadataNode = useCallback((node: MetadataTreeNode) => {
    setSelectedKeys([node.key]);
    setSelectedTreeNode(node);
  }, []);

  const generateTableSql = useCallback(async (node: MetadataTreeNode, kind: SqlTemplateKind) => {
    const target = resolveMetadataTableTarget(node);
    if (!target) return;
    selectMetadataNode(node);
    setCatalogCode(target.catalogCode);
    setDefaultSchema('');
    const messageKey = 'dna-sql-console-generate-template';
    try {
      let structure: MetadataTableStructure | undefined;
      if (kind !== 'SELECT') {
        message.open({
          key: messageKey,
          type: 'loading',
          duration: 0,
          content: t('dna.federation.sqlConsole.page.progress.generateSql', '正在读取表结构并生成 SQL...'),
        });
        structure = await loadTableStructure(node);
      }
      editorRef.current?.appendStatement(buildTableSqlTemplate(kind, target.path, structure, {
        missingPrimaryKeyComment: t(
          'dna.federation.sqlConsole.page.template.missingPrimaryKey',
          'TODO: 执行前补充精确的过滤条件',
        ),
      }));
      if (kind !== 'SELECT') {
        message.success({
          key: messageKey,
          content: t('dna.federation.sqlConsole.page.success.generateSql', 'SQL 模板已生成'),
        });
      }
    } catch (error) {
      message.error({
        key: messageKey,
        content: resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.generateSql', 'SQL 模板生成失败')),
      });
    }
  }, [loadTableStructure, message, resolveMetadataTableTarget, selectMetadataNode, t]);

  const previewTable = useCallback((node: MetadataTreeNode) => {
    if (node.type !== 'TABLE' && node.type !== 'VIEW') return;
    const target = resolveMetadataTableTarget(node);
    if (!target) return;
    const statement = buildTableSqlTemplate('SELECT', target.path);
    selectMetadataNode(node);
    setCatalogCode(target.catalogCode);
    setDefaultSchema('');
    editorRef.current?.appendStatement(statement);
    void submit('query', {
      catalogCode: target.catalogCode,
      executionSql: statement,
      ignoreParameters: true,
    });
  }, [resolveMetadataTableTarget, selectMetadataNode, submit]);

  const handleTreeNodeDoubleClick = useCallback((_event: React.MouseEvent, node: MetadataTreeNode) => {
    if (treeClickTimer.current !== null) {
      window.clearTimeout(treeClickTimer.current);
      treeClickTimer.current = null;
    }
    previewTable(node);
  }, [previewTable]);

  const renderTreeNodeTitle = useCallback((node: MetadataTreeNode) => {
    if (node.type !== 'TABLE' && node.type !== 'VIEW') return node.title;
    const items: MenuProps['items'] = [
      {
        key: 'SELECT',
        icon: <EyeOutlined />,
        label: t('dna.federation.sqlConsole.page.context.generateSelect', '生成 SELECT 语句'),
      },
      ...(node.type === 'TABLE' ? [
        {
          key: 'INSERT',
          icon: <PlusOutlined />,
          label: t('dna.federation.sqlConsole.page.context.generateInsert', '生成 INSERT 语句'),
        },
        {
          key: 'UPDATE',
          icon: <EditOutlined />,
          label: t('dna.federation.sqlConsole.page.context.generateUpdate', '生成 UPDATE 语句'),
        },
        {
          key: 'DELETE',
          danger: true,
          icon: <DeleteOutlined />,
          label: t('dna.federation.sqlConsole.page.context.generateDelete', '生成 DELETE 语句'),
        },
      ] satisfies MenuProps['items'] : []),
    ];
    // The metadata explorer is a tree, so the platform table action renderer does not cover this menu.
    return (
      <Dropdown trigger={['contextMenu']} menu={{
        items,
        onClick: ({key, domEvent}) => {
          domEvent.stopPropagation();
          void generateTableSql(node, key as SqlTemplateKind);
        },
      }}>
        <span className="dna-sql-tree-node-title" onContextMenu={() => selectMetadataNode(node)}>
          {node.title}
        </span>
      </Dropdown>
    );
  }, [generateTableSql, selectMetadataNode, t]);

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

  const statementExecutionColumns = useMemo<ColumnsType<StatementExecutionRecord>>(() => [
    {
      title: '#',
      dataIndex: 'sequence',
      key: 'sequence',
      width: 56,
    },
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
      children: renderScrollPane(
        <Space className="dna-sql-console-result-content" orientation="vertical" size={8} style={{display: 'flex'}}>
          {statementExecutions.length > 1 ? (
            <Table<StatementExecutionRecord>
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
              onRow={record => ({onClick: () => selectStatementExecution(record)})}
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
          <Table<ResultRow>
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
    },
  ], [analysisResult, executeMessage, executeType, queryResult, renderScrollPane, resultColumns, resultData,
    resultRevision, gridHeight, selectStatementExecution, selectedStatementKey, statementExecutionColumns,
    statementExecutions, t, updateResult]);

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
      <label className="dna-sql-option-switch">
        <span>{t('dna.federation.sqlConsole.page.option.continueOnError', '脚本失败后继续执行')}</span>
        <Switch checked={continueOnError} onChange={setContinueOnError} disabled={busy} />
      </label>
      <Typography.Text type="secondary">
        {t(
          'dna.federation.sqlConsole.page.batch.executionHint',
          '多条 SQL 按顺序独立执行，每条仍经过 Calcite、权限和审计；跨数据源不构成原子事务。',
        )}
      </Typography.Text>
    </div>
  );

  const handleFormatSql = useCallback(async () => {
    if (formattingSqlRef.current) return;
    formattingSqlRef.current = true;
    setFormattingSql(true);
    try {
      const result = await editorRef.current?.formatSql();
      if (!result || result === 'empty') {
        message.warning(t('dna.federation.sqlConsole.page.warning.formatEmpty', '请输入要格式化的 SQL'));
      } else if (result === 'unchanged') {
        message.success(t('dna.federation.sqlConsole.page.success.alreadyFormatted', 'SQL 已符合格式规范'));
      } else {
        message.success(t('dna.federation.sqlConsole.page.success.format', 'SQL 已格式化'));
      }
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        t('dna.federation.sqlConsole.page.error.format', 'SQL 格式化失败'),
      ));
    } finally {
      formattingSqlRef.current = false;
      if (aliveRef.current) setFormattingSql(false);
    }
  }, [message, t]);

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
              <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'refreshTree', sort: 0,
                type: 'text', size: 'small', icon: <ReloadOutlined/>, iconOnly: true, loading: treeLoading,
                text: t('dna.federation.sqlConsole.page.button.refreshTree', '刷新树'), onClick: () => void loadDataSources()}]}/>
            </div>
            <Input.Search allowClear value={search} onChange={event => setSearch(event.target.value)}
              placeholder={t('dna.federation.sqlConsole.page.search', '搜索已加载对象')} />
            <Typography.Text type="secondary" className="dna-sql-tree-hint">
              <CodeOutlined /> {t('dna.federation.sqlConsole.page.treeHint', '双击表查看数据，右键生成增删改查 SQL')}
            </Typography.Text>
            {treeError && <Alert type="error" showIcon closable onClose={() => setTreeError(null)} title={treeError}
              action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retryTree', sort: 0,
                size: 'small', loading: retryingTree, text: t('dna.federation.sqlConsole.page.retry', '重试'), onClick: () => {
                setRetryingTree(true);
                void (failedTreeNode ? handleLoadData(failedTreeNode) : loadDataSources())
                  .catch(() => undefined).finally(() => setRetryingTree(false));
              }}]}/>} />}
            <div className="dna-sql-tree" ref={treeContainerRef}>
              {treeLoading ? <Spin /> : treeData.length ? (
                <Tree key={treeRevision} blockNode showLine={{showLeafIcon: false}}
                  treeData={visibleTree} loadData={search.trim() ? undefined : handleLoadData} selectedKeys={selectedKeys}
                  expandedKeys={visibleExpandedKeys} onExpand={setExpandedKeys}
                  titleRender={node => renderTreeNodeTitle(node as MetadataTreeNode)}
                  filterTreeNode={node => Boolean(search.trim()) && (node.rawTitle ?? '').toLowerCase().includes(search.trim().toLowerCase())}
                  onSelect={handleSelectTreeNode}
                  onDoubleClick={(event, node) => handleTreeNodeDoubleClick(event, node as MetadataTreeNode)}
                />
              ) : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
                description={t('dna.federation.sqlConsole.page.empty.noDataSources', '当前没有可用的数据源')} />}
            </div>
            {selectedTreePath && <div className="dna-sql-object-path">
              <Typography.Text ellipsis={{tooltip: selectedTreePath}}>{selectedTreePath}</Typography.Text>
              {/* Editor insertion is a local text-editing command, not a server business operation. */}
              <Button size="small" type="text" onClick={() => editorRef.current?.insertText(selectedTreePath)}>
                {t('dna.federation.sqlConsole.page.button.insertPath', '插入路径')}
              </Button>
            </div>}
          </aside>
        </Splitter.Panel>
        <Splitter.Panel min={360}>
          <div className="dna-sql-main">
            <div className="dna-sql-file-tab"><span>console.sql</span><Typography.Text type="secondary">SQL</Typography.Text></div>
            {permissions.error && <Alert type="error" showIcon title={t('table.loadFail', '加载失败')}
              action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retryPermissions', sort: 0,
                text: t('table.retry', '重试'), onClick: () => void permissions.refetch()}]}/>}/>}
            <div className="dna-sql-toolbar">
              <TableActions selectedKeys={[]} selectedRows={[]} buttons={canExecute ? [
                {key: 'execute', sort: 0, type: 'text', className: 'dna-sql-run', icon: <PlayCircleOutlined/>,
                  loading: loadingMode === 'query' && statementProgress === null, disabled: busy || !catalogCode,
                  title: t('dna.federation.sqlConsole.page.runHint', '执行选中语句或光标所在语句；选中多条时顺序执行（Ctrl/⌘ + Enter）'),
                  text: t('dna.federation.sqlConsole.page.button.query', '执行 SQL'), onClick: () => void submit('query')},
                {key: 'runScript', sort: 1, type: 'text', icon: <CodeOutlined/>, loading: statementProgress !== null,
                  disabled: busy || !catalogCode, title: t('dna.federation.sqlConsole.page.button.runScriptHint', '顺序执行编辑器中的全部 SQL'),
                  text: t('dna.federation.sqlConsole.page.button.runScript', '执行脚本'),
                  onClick: () => void submit('query', {executionSql: editorRef.current?.getAllSql()})},
              ] : []}/>
              {/* Formatting is a local specialized editor command and retains the editor's shortcut. */}
              <Button type="text" icon={<FontSizeOutlined />} loading={formattingSql}
                disabled={busy || formattingSql}
                title={t(
                  'dna.federation.sqlConsole.page.button.formatHint',
                  '格式化选中 SQL；未选中时格式化全文（Ctrl/⌘ + Shift + F）',
                )}
                onClick={() => void handleFormatSql()}>
                {t('dna.federation.sqlConsole.page.button.format', '格式化 SQL')}
              </Button>
              <TableActions selectedKeys={[]} selectedRows={[]} buttons={canExplain ? [{key: 'explain', sort: 0,
                type: 'text', loading: loadingMode === 'explain', disabled: busy || !catalogCode,
                text: t('dna.federation.sqlConsole.page.button.explain', '查看执行计划'), onClick: () => void submit('explain')}] : []}/>
              <JdbcDataSourceSelect className="dna-sql-source-select"
                placeholder={t('dna.federation.sqlConsole.page.warning.selectCatalog', '请选择数据源')}
                value={catalogCode} disabled={busy} onChange={selectDataSource}/>
              <InputNumber size="small" min={1} max={1000000} precision={0} value={maxRows}
                aria-label={t('dna.federation.sqlConsole.page.field.maxRows', '最大返回行数')}
                title={t('dna.federation.sqlConsole.page.field.maxRows', '最大返回行数')}
                onChange={value => setMaxRows(value)} disabled={busy} />
              <Popover trigger="click" placement="bottomRight" content={controls} open={selected && optionsOpen} onOpenChange={setOptionsOpen}>
                {/* Local editor-options popover trigger, not a resource mutation. */}
                <Button type="text" icon={<SettingOutlined />}
                  aria-label={t('dna.federation.sqlConsole.page.options', '查询参数')} />
              </Popover>
            </div>
            <Splitter orientation="vertical" className="dna-sql-editor-split">
              <Splitter.Panel defaultSize="45%" min={100}>
                <div className="dna-sql-editor">
                  <SqlEditor ref={editorRef} value={sql} onChange={setSql}
                    completion={sqlCompletion}
                    onFormat={() => void handleFormatSql()}
                    onRevealMetadata={handleRevealMetadata}
                    revealMetadataLabel={t(
                      'dna.federation.sqlConsole.page.completion.reveal',
                      '在元数据树中定位',
                    )}
                    syntaxErrorMessage={t(
                      'dna.federation.sqlConsole.page.completion.syntaxError',
                      'SQL 语法错误',
                    )}
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
                      : statementProgress
                        ? t('dna.federation.sqlConsole.page.progress.batch', '正在执行第 {current}/{total} 条 SQL...')
                          .replace('{current}', String(statementProgress.current))
                          .replace('{total}', String(statementProgress.total))
                        : t('dna.federation.sqlConsole.page.progress.query', '正在执行 SQL...')}
                  </div>}
                  <Tabs className="dna-sql-console-tabs" size="small" activeKey={activeTabKey}
                    onChange={key => setActiveTabKey(key as ResultTabKey)}
                    items={[...resultTabs!.filter(item => item.key === 'result'), ...resultTabs!.filter(item => item.key !== 'result')]}
                    tabBarExtraContent={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
                      {key: 'exportCsv', sort: 0, type: 'text', size: 'small', icon: <DownloadOutlined/>,
                        text: 'CSV', disabled: busy || !queryResult, onClick: () => exportResults('CSV')},
                      {key: 'exportJson', sort: 1, type: 'text', size: 'small', text: 'JSON',
                        disabled: busy || !queryResult, onClick: () => exportResults('JSON')},
                    ]}/>} />
                </div>
              </Splitter.Panel>
            </Splitter>
            <div className="dna-sql-statusbar" role="status">
              <span>{catalogCode || '—'}</span>
              <span>{statementExecutions.length > 1
                ? `${statementExecutions.filter(record => record.status === 'SUCCESS').length}/${statementExecutions.length} statements`
                : queryResult ? `${queryResult.returnedRows} rows · ${queryResult.executionTimeMs} ms`
                : updateResult ? `${updateResult.affectedRows} rows · ${updateResult.executionTimeMs} ms`
                : t('dna.federation.sqlConsole.page.completion.shortcut', 'Ctrl/⌘ + Space 补全 · Ctrl/⌘ + Enter 执行')}</span>
            </div>
          </div>
        </Splitter.Panel>
      </Splitter>
    </div>
  );
};

export default function SqlConsole() {
  const scope = useQueryScope();
  return <App key={JSON.stringify(scope)}/>;
}
