import api from '@/api';
import {contextPath} from '@/services';
import {DownloadOutlined, ReloadOutlined} from '@ant-design/icons';
import {
  ensureContextId,
  getStoredContextId,
  getStoredRoleId,
  getStoredTenantId,
  shouldAutoEnsureContextId,
  shouldUseTenantContext,
} from '@simplepoint/shared/api/contextId';
import {get, post} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {Page} from '@simplepoint/shared/types/request';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Input,
  InputNumber,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Tree,
  Typography,
  message,
} from 'antd';
import type {TabsProps} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {Key} from 'react';
import {SqlEditor, type SqlEditorRef} from './SqlEditor';
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

const catalogBaseUrl = `${contextPath}/platform/dna/federation/catalogs`;
const dataSourceConfig = api['platform.dna-data-sources'];
const metadataConfig = api['platform.dna-metadata'];
const sqlConsoleConfig = api['platform.dna-federation-sql-console'];

type FederationCatalogOption = {
  id: string;
  code?: string;
  name?: string;
  enabled?: boolean;
};

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
};

type ResultRow = {
  key: number;
  values: unknown[];
};

type ResultTabKey = 'analysis' | 'pushedSql' | 'plan' | 'result';

const defaultSql = `select *
from data_source.database.schema.table
order by 1`;

const resolveCatalogLabel = (catalog: FederationCatalogOption) => {
  const primary = catalog.name || catalog.code || catalog.id;
  const secondary = catalog.code && catalog.code !== primary ? ` (${catalog.code})` : '';
  return `${primary}${secondary}`;
};

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
  dataSources: result.dataSources,
  planText: result.planText,
  pushedSqls: result.pushedSqls ?? [],
  pushdownSummary: result.pushdownSummary,
  schemaCacheHit: result.schemaCacheHit ?? false,
  schemaAssemblyTimeMs: result.schemaAssemblyTimeMs ?? 0,
  mountedDataSourceCount: result.mountedDataSourceCount ?? result.dataSources.length,
  pushedDownOperators: result.pushedDownOperators ?? [],
  platformJoin: result.platformJoin ?? false,
});

const formatPushedSqls = (pushedSqls: string[]) => pushedSqls
  .map((sql, index) => `-- Pushdown SQL ${index + 1}\n${sql}`)
  .join('\n\n');

const formatPushedSql = (sql: string, type: string) => `-- ${type} Pushdown SQL\n${sql}`;

const isFlushCacheSql = (value: string) => /^\s*FLUSH\s+CACHE\s*;?\s*$/i.test(value);

const resolveDownloadFileName = (contentDisposition: string | null, fallback: string) => {
  if (!contentDisposition) {
    return fallback;
  }
  const encodedMatch = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
  if (encodedMatch?.[1]) {
    try {
      return decodeURIComponent(encodedMatch[1]);
    } catch {
      return encodedMatch[1];
    }
  }
  const quotedMatch = contentDisposition.match(/filename="?([^";]+)"?/i);
  return quotedMatch?.[1] || fallback;
};

const saveBlob = (blob: Blob, fileName: string) => {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
};

const buildJsonHeaders = async (url: string) => {
  const headers: Record<string, string> = {'Content-Type': 'application/json'};
  if (!shouldUseTenantContext(url)) {
    return headers;
  }
  const tenantId = getStoredTenantId()?.trim();
  if (!tenantId) {
    throw new Error('Tenant context is required');
  }
  const roleId = getStoredRoleId(tenantId)?.trim();
  let contextId = getStoredContextId(tenantId, roleId);
  headers['X-Tenant-Id'] = tenantId;
  if (roleId) {
    headers['X-Role-Id'] = roleId;
  }
  if (shouldAutoEnsureContextId(url, contextId)) {
    contextId = contextId || await ensureContextId(tenantId, { roleId });
  }
  if (contextId) {
    headers['X-Context-Id'] = contextId;
  }
  return headers;
};

const pageContainerStyle = {
  display: 'flex',
  flexDirection: 'column' as const,
  gap: 16,
  height: '100%',
  minHeight: 0,
  overflow: 'hidden',
};

const workspaceStyle = {
  flex: 1,
  minHeight: 0,
  display: 'grid',
  gridTemplateRows: 'minmax(320px, 3fr) minmax(260px, 2fr)',
  gap: 16,
  overflow: 'hidden',
};

const cardStyle = {
  height: '100%',
  display: 'flex',
  flexDirection: 'column' as const,
};

const cardBodyStyle = {
  flex: 1,
  minHeight: 0,
  display: 'flex',
  flexDirection: 'column' as const,
  overflow: 'hidden',
};

const scrollAreaStyle = {
  flex: 1,
  minHeight: 0,
  overflow: 'auto',
};

const renderCellValue = (value: unknown) => {
  if (value === null || value === undefined) {
    return <Tag>NULL</Tag>;
  }
  if (typeof value === 'boolean') {
    return <Tag color={value ? 'success' : 'default'}>{value ? 'true' : 'false'}</Tag>;
  }
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  }
  return String(value);
};

const App = () => {
  const {t, ensure, locale} = useI18n();
  const [catalogs, setCatalogs] = useState<FederationCatalogOption[]>([]);
  const [catalogCode, setCatalogCode] = useState<string>();
  const [dataSources, setDataSources] = useState<JdbcDataSourceOption[]>([]);
  const [treeData, setTreeData] = useState<MetadataTreeNode[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<Key[]>([]);
  const [selectedTreeNode, setSelectedTreeNode] = useState<MetadataTreeNode | null>(null);
  const [treeLoading, setTreeLoading] = useState(false);
  const [sql, setSql] = useState(defaultSql);
  const [defaultSchema, setDefaultSchema] = useState('');
  const [maxRows, setMaxRows] = useState<number | null>(null);
  const [parametersText, setParametersText] = useState('');
  const [loadingMode, setLoadingMode] = useState<'explain' | 'query' | null>(null);
  const [exportFormat, setExportFormat] = useState<'CSV' | 'JSON' | null>(null);
  const [activeTabKey, setActiveTabKey] = useState<ResultTabKey>('analysis');
  const [explainResult, setExplainResult] = useState<SqlExplainResult | null>(null);
  const [queryResult, setQueryResult] = useState<SqlQueryResult | null>(null);
  const [updateResult, setUpdateResult] = useState<SqlUpdateResult | null>(null);
  const [executeType, setExecuteType] = useState<SqlExecuteResult['type'] | null>(null);
  const [executeMessage, setExecuteMessage] = useState<string | null>(null);
  const editorRef = useRef<SqlEditorRef>(null);

  useEffect(() => {
    void ensure([...sqlConsoleConfig.i18nNamespaces, ...dataSourceConfig.i18nNamespaces]);
  }, [ensure, locale]);

  const resolveNodeTypeLabel = useCallback((type: MetadataNodeType, fallback?: string | null) => {
    return t(`dna.federation.sqlConsole.page.nodeType.${type}`, fallback || type);
  }, [t]);

  const loadCatalogs = useCallback(async () => {
    const page = await get<Page<FederationCatalogOption>>(catalogBaseUrl, {page: 0, size: 200});
    const enabledCatalogs = (page.content ?? []).filter((catalog) => catalog.enabled !== false);
    setCatalogs(enabledCatalogs);
    setCatalogCode((current) => current ?? enabledCatalogs[0]?.code);
  }, []);

  const loadDataSources = useCallback(async () => {
    setTreeLoading(true);
    try {
      const page = await get<Page<JdbcDataSourceOption>>(dataSourceConfig.baseUrl, {page: 0, size: 200});
      const enabledDataSources = (page.content ?? []).filter((dataSource) => dataSource.enabled !== false);
      setDataSources(enabledDataSources);
      setTreeData(buildDataSourceTreeNodes(enabledDataSources, resolveNodeTypeLabel));
      setSelectedKeys([]);
      setSelectedTreeNode(null);
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.loadDataSources', '数据源树加载失败')));
    } finally {
      setTreeLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadCatalogs().catch((error) => {
      message.error(resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.loadCatalogs', '数据目录列表加载失败')));
    });
  }, [loadCatalogs]);

  useEffect(() => {
    void loadDataSources();
  }, [loadDataSources]);

  const loadChildren = useCallback(async (dataSourceId: string, path: MetadataPathSegment[]) => {
    const data = await post<MetadataTreeNode[]>(`${metadataConfig.baseUrl}/${dataSourceId}/children`, {path});
    return normalizeMetadataTreeNodes(data ?? [], resolveNodeTypeLabel, {dataSourceId});
  }, [resolveNodeTypeLabel]);

  const handleLoadData = useCallback(async (treeNode: object) => {
    const node = treeNode as MetadataTreeNode;
    if (node.isLeaf || node.loaded || !node.dataSourceId) {
      return;
    }
    try {
      const children = await loadChildren(node.dataSourceId, node.type === 'DATA_SOURCE' ? [] : node.path);
      setTreeData((current) => replaceMetadataTreeChildren(current, node.key, children));
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.loadChildren', '树节点加载失败')));
    }
  }, [loadChildren]);

  const handleSelectTreeNode = useCallback((keys: Key[]) => {
    setSelectedKeys(keys);
    setSelectedTreeNode(keys.length > 0 ? findMetadataTreeNodeByKey(treeData, keys[0]) : null);
  }, [treeData]);

  const handleRefreshTree = useCallback(async () => {
    await loadDataSources();
    message.success(t('dna.federation.sqlConsole.page.success.refreshTree', '数据源树已刷新'));
  }, [loadDataSources]);

  const selectedTreePath = useMemo(() => {
    if (!selectedTreeNode?.dataSourceId) {
      return null;
    }
    const dataSource = dataSources.find((item) => item.id === selectedTreeNode.dataSourceId);
    const rootCode = dataSource?.code || dataSource?.name || selectedTreeNode.dataSourceId;
    if (selectedTreeNode.type === 'DATA_SOURCE') {
      return rootCode;
    }
    return [rootCode, ...selectedTreeNode.path.map((segment) => segment.name)].join('.');
  }, [dataSources, selectedTreeNode]);

  const buildSqlConsolePayload = useCallback((options?: BuildSqlConsolePayloadOptions): SqlConsolePayload | null => {
    const normalizedSql = sql.trim();
    if (!normalizedSql) {
      message.warning(t('dna.federation.sqlConsole.page.warning.enterSql', '请输入要执行的 SQL'));
      return null;
    }
    const globalFlush = options?.allowGlobalFlush === true && isFlushCacheSql(normalizedSql);
    if (!catalogCode && !globalFlush) {
      message.warning(t('dna.federation.sqlConsole.page.warning.selectCatalog', '请选择数据目录'));
      return null;
    }

    const payload: SqlConsolePayload = {sql};
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
  }, [catalogCode, defaultSchema, maxRows, parametersText, sql, t]);

  const submit = useCallback(async (mode: 'explain' | 'query') => {
    const payload = buildSqlConsolePayload({allowGlobalFlush: mode === 'query'});
    if (!payload) {
      return;
    }
    setLoadingMode(mode);
    const hide = message.loading(
      mode === 'explain'
        ? t('dna.federation.sqlConsole.page.progress.explain', '正在生成执行计划...')
        : t('dna.federation.sqlConsole.page.progress.query', '正在执行 SQL...'),
      0,
    );
    try {
      if (mode === 'explain') {
        const result = await post<SqlExplainResult>(`${sqlConsoleConfig.baseUrl}/explain`, payload);
        setExplainResult(result);
        setQueryResult(null);
        setUpdateResult(null);
        setExecuteType(null);
        setExecuteMessage(null);
        setActiveTabKey('analysis');
        message.success(t('dna.federation.sqlConsole.page.success.explain', '执行计划已生成'));
      } else {
        const result = await post<SqlExecuteResult>(`${sqlConsoleConfig.baseUrl}/execute`, payload);
        setExecuteType(result.type);
        setExecuteMessage(result.message ?? null);
        if (result.type === 'QUERY' && result.queryResult) {
          setExplainResult(toExplainSnapshot(result.queryResult));
          setQueryResult(result.queryResult);
          setUpdateResult(null);
          setActiveTabKey('result');
          message.success(t('dna.federation.sqlConsole.page.success.query', 'SQL 执行成功'));
        } else if ((result.type === 'DML' || result.type === 'DDL') && result.updateResult) {
          setExplainResult(null);
          setQueryResult(null);
          setUpdateResult(result.updateResult);
          setActiveTabKey('result');
          message.success(t('dna.federation.sqlConsole.page.success.update', 'SQL 已下推执行'));
        } else if (result.type === 'FLUSH_CACHE') {
          setExplainResult(null);
          setQueryResult(null);
          setUpdateResult(null);
          setActiveTabKey('result');
          message.success(result.message || t('dna.federation.sqlConsole.page.success.flushCache', '缓存已刷新'));
        } else {
          throw new Error(t('dna.federation.sqlConsole.page.error.unsupportedExecuteResult', '不支持的执行结果类型'));
        }
      }
    } catch (error) {
      message.error(resolveErrorMessage(
        error,
        mode === 'explain'
          ? t('dna.federation.sqlConsole.page.error.explain', '执行计划生成失败')
          : t('dna.federation.sqlConsole.page.error.query', 'SQL 执行失败'),
      ));
    } finally {
      hide();
      setLoadingMode(null);
    }
  }, [buildSqlConsolePayload, t]);

  const exportResults = useCallback(async (format: 'CSV' | 'JSON') => {
    const payload = buildSqlConsolePayload();
    if (!payload) {
      return;
    }
    const exportUrl = `${sqlConsoleConfig.baseUrl}/export`;
    setExportFormat(format);
    const hide = message.loading(t('dna.federation.sqlConsole.page.progress.export', '正在导出查询结果...'), 0);
    try {
      const response = await fetch(exportUrl, {
        method: 'POST',
        credentials: 'include',
        headers: await buildJsonHeaders(exportUrl),
        body: JSON.stringify({...payload, format}),
      });
      if (!response.ok) {
        const text = await response.text();
        throw new Error(text || `${response.status} ${response.statusText}`);
      }
      const blob = await response.blob();
      const fileName = resolveDownloadFileName(
        response.headers.get('content-disposition'),
        format === 'JSON' ? 'export.json' : 'export.csv',
      );
      saveBlob(blob, fileName);
      message.success(t('dna.federation.sqlConsole.page.success.export', '查询结果已导出'));
    } catch (error) {
      message.error(resolveErrorMessage(error, t('dna.federation.sqlConsole.page.error.export', '查询结果导出失败')));
    } finally {
      hide();
      setExportFormat(null);
    }
  }, [buildSqlConsolePayload, t]);

  const analysisResult = queryResult ?? explainResult;

  const resultColumns = useMemo(() => (queryResult?.columns ?? []).map((column, index) => ({
    title: column.typeName ? `${column.name} (${column.typeName})` : column.name,
    dataIndex: ['values', index],
    key: `${column.name}-${index}`,
    render: (value: unknown) => renderCellValue(value),
  })), [queryResult]);

  const resultData = useMemo<ResultRow[]>(() => (queryResult?.rows ?? []).map((row, index) => ({
    key: index,
    values: row ?? [],
  })), [queryResult]);

  const resultTabs = useMemo<TabsProps['items']>(() => [
    {
      key: 'analysis',
      label: t('dna.federation.sqlConsole.page.tab.analysis', '执行分析'),
      children: analysisResult ? (
        <Space direction="vertical" size={16} style={{display: 'flex'}}>
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
              {analysisResult.dataSources.length > 0
                ? analysisResult.dataSources.map((code) => <Tag key={code}>{code}</Tag>)
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
      ),
    },
    {
      key: 'pushedSql',
      label: t('dna.federation.sqlConsole.page.tab.pushedSql', 'JDBC 下推 SQL'),
      children: queryResult ? (
        queryResult.pushedSqls.length > 0 ? (
          <Paragraph style={{whiteSpace: 'pre-wrap', marginBottom: 0}}>
            {formatPushedSqls(queryResult.pushedSqls)}
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
      ),
    },
    {
      key: 'plan',
      label: t('dna.federation.sqlConsole.page.tab.plan', 'Calcite 计划'),
      children: analysisResult ? (
        <Paragraph style={{whiteSpace: 'pre-wrap', marginBottom: 0}}>
          {analysisResult.planText || t('dna.federation.sqlConsole.page.empty.noPlan', '暂无执行计划')}
        </Paragraph>
      ) : (
        <Empty description={t('dna.federation.sqlConsole.page.empty.planPending', '查看执行计划或执行 SQL 后展示 Calcite 执行计划')} />
      ),
    },
    {
      key: 'result',
      label: t('dna.federation.sqlConsole.page.tab.result', '执行结果'),
      children: queryResult ? (
        <Space direction="vertical" size={16} style={{display: 'flex'}}>
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
          <Descriptions bordered size="small" column={2}>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.returnedRows', '返回行数')}>{queryResult.returnedRows}</Descriptions.Item>
            <Descriptions.Item label={t('dna.federation.sqlConsole.page.field.resultStatus', '结果状态')}>
              <Tag color={queryResult.truncated ? 'warning' : 'success'}>
                {queryResult.truncated
                  ? t('dna.federation.sqlConsole.page.state.truncated', '已截断')
                  : t('dna.federation.sqlConsole.page.state.complete', '完整返回')}
              </Tag>
            </Descriptions.Item>
          </Descriptions>
          <Table<ResultRow>
            size="small"
            rowKey="key"
            scroll={{x: true}}
            pagination={{pageSize: 20, showSizeChanger: true}}
            columns={resultColumns}
            dataSource={resultData}
          />
        </Space>
      ) : updateResult ? (
        <Space direction="vertical" size={16} style={{display: 'flex'}}>
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
      ),
    },
  ], [analysisResult, executeMessage, executeType, queryResult, resultColumns, resultData, t, updateResult]);

  return (
    <div style={pageContainerStyle}>
      <div style={workspaceStyle}>
        <Row gutter={16} align="stretch" style={{height: '100%', minHeight: 0}}>
          <Col span={8} style={{height: '100%'}}>
            <Card
              title={t('dna.federation.sqlConsole.page.card.tree', '数据源树')}
              extra={(
                <Button icon={<ReloadOutlined />} onClick={() => void handleRefreshTree()}>
                  {t('dna.federation.sqlConsole.page.button.refreshTree', '刷新树')}
                </Button>
              )}
              style={cardStyle}
              bodyStyle={cardBodyStyle}
            >
              <div style={scrollAreaStyle}>
                {treeLoading ? (
                  <div style={{display: 'flex', justifyContent: 'center', paddingTop: 120}}>
                    <Spin />
                  </div>
                ) : treeData.length > 0 ? (
                  <Tree
                    blockNode
                    treeData={treeData}
                    loadData={handleLoadData}
                    selectedKeys={selectedKeys}
                    onSelect={(keys) => handleSelectTreeNode(keys)}
                  />
                ) : (
                  <Empty description={t('dna.federation.sqlConsole.page.empty.noDataSources', '当前没有可用的数据源')} />
                )}
              </div>
              {selectedTreePath ? (
                <Alert
                  type="info"
                  showIcon
                  style={{marginTop: 12}}
                  message={t('dna.federation.sqlConsole.page.message.selectedPath', '当前选中路径')}
                  description={selectedTreePath}
                />
              ) : null}
            </Card>
          </Col>
          <Col span={16} style={{height: '100%'}}>
            <Card title={t('dna.federation.sqlConsole.page.card.editor', 'SQL 编辑器')} style={cardStyle} bodyStyle={cardBodyStyle}>
              <Space direction="vertical" size={12} style={{display: 'flex', flex: 1, minHeight: 0}}>
                <Space.Compact style={{width: '100%'}}>
                  <Select
                    style={{flex: 1}}
                    placeholder={t('dna.federation.sqlConsole.page.placeholder.selectCatalog', '请选择数据目录')}
                    value={catalogCode}
                    onChange={setCatalogCode}
                    options={catalogs.map((catalog) => ({
                      label: resolveCatalogLabel(catalog),
                      value: catalog.code,
                    }))}
                  />
                  <Input
                    style={{width: 220}}
                    value={defaultSchema}
                    placeholder={t('dna.federation.sqlConsole.page.placeholder.defaultSchema', '默认 Schema')}
                    onChange={(event) => setDefaultSchema(event.target.value)}
                  />
                  <InputNumber
                    min={1}
                    max={1000000}
                    style={{width: 180}}
                    value={maxRows ?? undefined}
                    placeholder={t('dna.federation.sqlConsole.page.placeholder.maxRows', '本次行数上限')}
                    onChange={(value) => setMaxRows(typeof value === 'number' ? value : null)}
                  />
                </Space.Compact>

                <Input.TextArea
                  value={parametersText}
                  onChange={(event) => setParametersText(event.target.value)}
                  autoSize={{minRows: 1, maxRows: 3}}
                  placeholder={t(
                    'dna.federation.sqlConsole.page.placeholder.parameters',
                    '参数 JSON 数组，例如 [1, "Alice", null]',
                  )}
                  style={{fontFamily: 'monospace'}}
                />

                {selectedTreePath ? (
                  <Alert
                    type="info"
                    showIcon
                    message={t('dna.federation.sqlConsole.page.message.selectedObject', '左侧树当前选中对象')}
                    description={selectedTreePath}
                    action={
                      <Button
                        size="small"
                        onClick={() => editorRef.current?.insertText(selectedTreePath)}
                      >
                        {t('dna.federation.sqlConsole.page.button.insertPath', '插入路径')}
                      </Button>
                    }
                  />
                ) : null}

                <div style={{flex: 1, minHeight: 0, border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden'}}>
                  <SqlEditor
                    ref={editorRef}
                    value={sql}
                    onChange={setSql}
                    onExecute={() => {
                      void submit('query');
                    }}
                  />
                </div>

                <Space>
                  <Button
                    type="default"
                    loading={loadingMode === 'explain'}
                    disabled={loadingMode !== null || exportFormat !== null}
                    onClick={() => {
                      void submit('explain');
                    }}
                  >
                    {t('dna.federation.sqlConsole.page.button.explain', '查看执行计划')}
                  </Button>
                  <Button
                    type="primary"
                    loading={loadingMode === 'query'}
                    disabled={loadingMode !== null || exportFormat !== null}
                    onClick={() => {
                      void submit('query');
                    }}
                  >
                    {t('dna.federation.sqlConsole.page.button.query', '执行 SQL')}
                  </Button>
                  <Button
                    icon={<DownloadOutlined />}
                    loading={exportFormat === 'CSV'}
                    disabled={loadingMode !== null || exportFormat !== null}
                    onClick={() => {
                      void exportResults('CSV');
                    }}
                  >
                    {t('dna.federation.sqlConsole.page.button.exportCsv', '导出 CSV')}
                  </Button>
                  <Button
                    icon={<DownloadOutlined />}
                    loading={exportFormat === 'JSON'}
                    disabled={loadingMode !== null || exportFormat !== null}
                    onClick={() => {
                      void exportResults('JSON');
                    }}
                  >
                    {t('dna.federation.sqlConsole.page.button.exportJson', '导出 JSON')}
                  </Button>
                </Space>
              </Space>
            </Card>
          </Col>
        </Row>

        <Card title={t('dna.federation.sqlConsole.page.card.output', '执行输出')} style={cardStyle} bodyStyle={{...cardBodyStyle, paddingTop: 8}}>
          <div style={scrollAreaStyle}>
            <Tabs
              activeKey={activeTabKey}
              onChange={(key) => setActiveTabKey(key as ResultTabKey)}
              items={resultTabs}
            />
          </div>
        </Card>
      </div>
    </div>
  );
};

export default App;
