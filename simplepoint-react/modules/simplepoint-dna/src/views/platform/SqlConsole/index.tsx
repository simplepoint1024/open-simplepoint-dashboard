import api from '@/api';
import {CodeOutlined, DeleteOutlined, EditOutlined, EyeOutlined, FontSizeOutlined, PlusOutlined, PlayCircleOutlined, SettingOutlined} from '@simplepoint/shared/types/icon';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {assertConsoleResponse, buildTableSqlTemplate, quoteSqlPath, resultCsv, type SqlTemplateKind} from './sqlConsoleUtils';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {normalizePage, type Page} from '@simplepoint/shared/types/request';
import {Alert, App as AntApp, Button, Dropdown, Popover, Splitter, Input, InputNumber, Spin, Switch, Typography, theme} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import JdbcDataSourceSelect, {enabledSources, sourceCodeFilter, type JdbcSourceOption} from '../JdbcDataSource/select';
import type {MenuProps} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import type {Key} from 'react';
import {SqlEditor, type SqlEditorRef} from './SqlEditor';
import {SqlConsoleResults} from './SqlConsoleResults';
import {SqlMetadataExplorer} from './SqlMetadataExplorer';
import {useSqlExecution} from './useSqlExecution';
import type {
  JdbcDataSourceOption,
  MetadataTableStructure,
  MetadataTableTarget,
} from './types';
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

const dataSourceConfig = api['platform.dna-data-sources'];
const metadataConfig = api['platform.dna-metadata'];
const sqlConsoleConfig = api['platform.dna-federation-sql-console'];

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
  const [exportFormat, setExportFormat] = useState<'CSV' | 'JSON' | null>(null);
  const [formattingSql, setFormattingSql] = useState(false);
  const [continueOnError, setContinueOnError] = useState(false);
  const editorRef = useRef<SqlEditorRef>(null);
  const aliveRef = useRef(true);
  const treeGeneration = useRef(0);
  const sourceRequest = useRef<AbortController | undefined>(undefined);
  const [treeError, setTreeError] = useState<string | null>(null);
  const [failedTreeNode, setFailedTreeNode] = useState<MetadataTreeNode | null>(null);
  const [retryingTree, setRetryingTree] = useState(false);
  const pendingNodes = useRef(new Set<Key>());
  const tableStructureCache = useRef(new Map<string, Promise<MetadataTableStructure>>());
  const completionCatalogCache = useRef(new Map<string, Promise<SqlCompletionCatalog>>());
  const [treeRevision, setTreeRevision] = useState(0);
  const [search, setSearch] = useState('');
  const {token} = theme.useToken();
  const selected = usePageSelected();
  const [optionsOpen, setOptionsOpen] = useState(false);
  const outputRef = useRef<HTMLDivElement>(null);
  const treeContainerRef = useRef<HTMLDivElement>(null);
  const treeClickTimer = useRef<number | null>(null);
  const [gridHeight, setGridHeight] = useState(160);
  const formattingSqlRef = useRef(false);
  const invalidateMetadata = useCallback(() => {
    tableStructureCache.current.clear();
    completionCatalogCache.current.clear();
  }, []);
  const {
    activeTabKey,
    busyRef,
    executeMessage,
    executeType,
    executionError,
    explainResult,
    loadingMode,
    queryResult,
    resultRevision,
    selectedStatementKey,
    selectStatementExecution,
    setActiveTabKey,
    statementExecutions,
    statementProgress,
    submit,
    updateResult,
  } = useSqlExecution({
    aliveRef,
    canExecute,
    canExplain,
    catalogCode,
    continueOnError,
    defaultSchema,
    editorRef,
    maxRows,
    onInvalidateMetadata: invalidateMetadata,
    parametersText,
    sql,
  });
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

  const retryMetadataTree = useCallback(() => {
    setRetryingTree(true);
    void (failedTreeNode ? handleLoadData(failedTreeNode) : loadDataSources())
      .catch(() => undefined)
      .finally(() => setRetryingTree(false));
  }, [failedTreeNode, handleLoadData, loadDataSources]);

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
          <SqlMetadataExplorer
            error={treeError}
            expandedKeys={expandedKeys}
            loading={treeLoading}
            onDismissError={() => setTreeError(null)}
            onDoubleClick={handleTreeNodeDoubleClick}
            onExpand={setExpandedKeys}
            onInsertPath={() => selectedTreePath && editorRef.current?.insertText(selectedTreePath)}
            onLoadData={handleLoadData}
            onRefresh={() => void loadDataSources()}
            onRetry={retryMetadataTree}
            onSearchChange={setSearch}
            onSelect={handleSelectTreeNode}
            renderNodeTitle={renderTreeNodeTitle}
            retrying={retryingTree}
            revision={treeRevision}
            search={search}
            selectedKeys={selectedKeys}
            selectedPath={selectedTreePath}
            treeContainerRef={treeContainerRef}
            treeData={treeData}
          />
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
                  <SqlConsoleResults
                    activeTabKey={activeTabKey}
                    busy={busy}
                    executeMessage={executeMessage}
                    executeType={executeType}
                    explainResult={explainResult}
                    gridHeight={gridHeight}
                    onExport={exportResults}
                    onSelectStatement={selectStatementExecution}
                    onTabChange={setActiveTabKey}
                    queryResult={queryResult}
                    resultRevision={resultRevision}
                    selectedStatementKey={selectedStatementKey}
                    statementExecutions={statementExecutions}
                    updateResult={updateResult}
                  />
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
