import api from '@/api';
import DataTable, {type DataTableProps} from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {getQueryScopeSnapshot, scopedQueryKey} from '@simplepoint/shared/api/queryScope';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {useQueryClient} from '@tanstack/react-query';
import {Alert, App, Input, Modal, Select, Space, Spin, Tag, Typography} from 'antd';
import {useEffect, useMemo, useRef, useState} from 'react';
import {canImport, catalogLink, catalogParameters, type CatalogItem, type CatalogKind, type CatalogPage,
  type CatalogPermissions, type CatalogSource, type ImportOperation} from './model';
import {resolveCatalogItemStatus, resolveCatalogOperationError, resolveCatalogSyncMode, resolveCatalogSyncNotice,
  resolveCatalogSyncStatus} from './syncErrorCodes';

const config = api['ai-workbench.catalog'], {Link, Text} = Typography;
type SyncState = {syncMode?: string; status?: string; lastCompletedAt?: string; lastError?: string; syncedCount?: number};
type ImportTarget = {item: CatalogItem; operation: ImportOperation};
type Imported = {id?: string; name?: string; code?: string};
const formatTime = (value?: string) => {if (!value) return '—'; const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString();};

export default function Catalog() {
  const scope = useQueryScope();
  return <CatalogSession key={JSON.stringify(scope)}/>;
}
function CatalogSession() {
  const {t, ensure, locale} = useI18n(), {message} = App.useApp(), selected = usePageSelected();
  const scope = useQueryScope(), scopeKey = JSON.stringify(scope), client = useQueryClient();
  const [page, setPage] = useState(0), [size, setSize] = useState(20), [draftQuery, setDraftQuery] = useState(''), [query, setQuery] = useState('');
  const [source, setSource] = useState<CatalogSource>(), [kind, setKind] = useState<CatalogKind>();
  const [target, setTarget] = useState<ImportTarget>(), targetRef = useRef<ImportTarget | undefined>(undefined);
  const [busy, setBusy] = useState(false), [writeError, setWriteError] = useState('');
  const [receipt, setReceipt] = useState<Imported>();
  const flight = useRef(false), alive = useRef(true), controller = useRef<AbortController | undefined>(undefined);
  const params = useMemo(() => catalogParameters(page, size, query, source, kind), [page, size, query, source, kind]);
  const catalog = useData([config.baseUrl, params], ({signal}) => get<CatalogPage>(config.baseUrl, params, {signal}), {retry: false});
  const syncState = useData([config.syncStatusUrl], ({signal}) => get<SyncState>(config.syncStatusUrl, {}, {signal}), {retry: false});
  const permissions = useData([config.permissionsUrl], ({signal}) => get<CatalogPermissions>(config.permissionsUrl, {}, {signal}), {retry: false});
  const locked = busy || !!target, writable = selected && !catalog.isFetching && !catalog.error && !permissions.error && !!permissions.data;
  usePageRetention(false, locked);
  useEffect(() => {void ensure(config.i18nNamespaces);}, [ensure, locale]);
  useEffect(() => {alive.current = true; return () => {alive.current = false; controller.current?.abort();};}, []);
  useEffect(() => {
    if (catalog.data && !catalog.isFetching && page > 0 && page >= catalog.data.totalPages) setPage(Math.max(0, catalog.data.totalPages - 1));
  }, [catalog.data, catalog.isFetching, page]);
  const current = () => alive.current && scopeKey === getQueryScopeSnapshot();
  const operationError = (error: unknown, key: string, fallback: string) => {
    const descriptor = resolveCatalogOperationError(error, {key, fallback}); return t(descriptor.key, descriptor.fallback);
  };
  const selectImport = (item: CatalogItem, operation: ImportOperation) => {
    if (!current() || flight.current || targetRef.current || !writable || !canImport(item, operation, permissions.data)) return;
    const captured = {item: {...item}, operation}; targetRef.current = captured; setTarget(captured); setWriteError(''); setReceipt(undefined);
  };
  const cancel = () => {if (flight.current) return; targetRef.current = undefined; setTarget(undefined); setWriteError('');};
  const mutate = async (operation: 'sync' | ImportOperation) => {
    const captured = targetRef.current;
    if (!current() || !selected || flight.current || permissions.error
      || (operation === 'sync' ? permissions.data?.sync !== true || !!captured
        : !captured || captured.operation !== operation || !canImport(captured.item, operation, permissions.data))) return;
    flight.current = true; setBusy(true); setWriteError('');
    const request = new AbortController(); controller.current = request;
    try {
      if (operation === 'sync') {
        const result = await post<{status?: string; errorCode?: string}>(config.syncUrl, {}, {signal: request.signal});
        if (!current()) return;
        const notice = resolveCatalogSyncNotice(result.errorCode || (result.status === 'FAILED' ? 'UNKNOWN_SYNC_FAILURE' : undefined));
        if (notice) message[notice.tone === 'warning' ? 'warning' : 'error'](t(notice.key, notice.fallback));
        else message.success(t('ai.catalog.success.sync', '官方 MCP Registry 同步完成'));
      } else {
        const result = await post<Imported & {profile?: Imported}>(config.baseUrl + '/' + encodeURIComponent(captured!.item.id)
          + (operation === 'remote' ? '/import' : '/import-runtime'), {}, {signal: request.signal});
        if (!current()) return;
        setReceipt(operation === 'remote' ? result : result.profile);
        message.success(operation === 'remote' ? t('ai.catalog.success.import', 'MCP Server 已导入当前工作空间')
          : t('ai.catalog.success.importRuntime', 'Runtime Profile 草稿已导入当前工作空间'));
        targetRef.current = undefined; setTarget(undefined);
        const resource = operation === 'remote' ? api['ai-workbench.mcp-servers'].baseUrl : '/ai/workbench/runtime/profiles';
        void client.invalidateQueries({queryKey: scopedQueryKey(scope, [resource])});
      }
      void client.invalidateQueries({queryKey: scopedQueryKey(scope, [config.baseUrl])});
      void client.invalidateQueries({queryKey: scopedQueryKey(scope, [config.syncStatusUrl])});
    } catch (error) {
      if (current() && !request.signal.aborted) setWriteError(operationError(error,
        operation === 'sync' ? 'ai.catalog.error.syncUnknown' : 'ai.catalog.error.import',
        operation === 'sync' ? '官方 MCP Registry 同步失败' : 'MCP Server 导入失败'));
    } finally {flight.current = false; if (current()) setBusy(false); if (controller.current === request) controller.current = undefined;}
  };
  const columns = useMemo<DataTableProps<CatalogItem>['columns']>(() => [
    {title: t('ai.catalog.column.package', '扩展'), key: 'package', render: (_, item) => <Space orientation="vertical" size={2}>
      <Text strong>{item.title || item.code || item.registryName || '—'}</Text>
      <Text type="secondary" ellipsis style={{maxWidth: 480}}>{item.description || '—'}</Text></Space>},
    {title: t('ai.catalog.column.source', '来源'), dataIndex: 'source', width: 150, render: value => <Tag color={value === 'OFFICIAL_MCP' ? 'blue' : 'default'}>
      {value === 'OFFICIAL_MCP' ? t('ai.catalog.source.official', '官方 MCP Registry') : t('ai.catalog.source.internal', '内部')}</Tag>},
    {title: t('ai.catalog.column.kind', '类型'), dataIndex: 'kind', width: 120, render: () => <Tag color="cyan">MCP Server</Tag>},
    {title: t('ai.catalog.column.version', '版本'), dataIndex: 'version', width: 120, render: value => value || '—'},
    {title: t('ai.catalog.column.status', '状态'), dataIndex: 'status', width: 110, render: value => {
      const status = resolveCatalogItemStatus(value); return <Tag color={{success: 'green', error: 'red', warning: 'gold', default: 'default'}[status.tone]}>{t(status.key, status.fallback)}</Tag>;
    }},
    {title: t('ai.catalog.column.updatedAt', '更新时间'), dataIndex: 'updatedAt', width: 180, render: formatTime},
    {title: t('ai.catalog.column.action', '操作'), key: 'action', fixed: 'right', width: 230, render: (_, item) => {
      const link = catalogLink(item.repositoryUrl);
      return <Space>{link && <Link href={link} target="_blank" rel="noreferrer">{t('ai.catalog.action.source', '源码')}</Link>}
        <TableActions<CatalogItem> selectedKeys={[item.id]} selectedRows={[item]} buttons={[
          {key: 'remote', sort: 1, type: 'link' as const, text: t('ai.catalog.action.import', '导入'), argumentMinSize: 1, argumentMaxSize: 1},
          {key: 'runtime', sort: 2, type: 'link' as const, text: t('ai.catalog.action.importRuntime', '导入运行草稿'), argumentMinSize: 1, argumentMaxSize: 1},
        ].filter(button => canImport(item, button.key as ImportOperation, permissions.data))}
          isButtonDisabled={() => locked || !writable} onButtonEvents={{remote: () => selectImport(item, 'remote'), runtime: () => selectImport(item, 'runtime')}}/></Space>;
    }},
  ], [t, permissions.data, locked, writable]);
  const syncNotice = resolveCatalogSyncNotice(syncState.data?.lastError || (syncState.data?.status === 'FAILED' ? 'UNKNOWN_SYNC_FAILURE' : undefined));
  const syncStatus = resolveCatalogSyncStatus(syncState.data?.status), syncMode = resolveCatalogSyncMode(syncState.data?.syncMode);
  const retry = (key: string, action: () => void) => <TableActions selectedKeys={[]} selectedRows={[]}
    buttons={[{key, sort: 1, text: t('table.retry', '重试'), disabled: locked}]} onButtonEvents={{[key]: action}}/>;
  return <div className="ai-catalog" style={{height: '100%', minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column', gap: 12, overflow: 'auto'}}>
    <Alert type={syncNotice?.tone || 'info'} showIcon title={t('ai.catalog.notice.title', '隔离式 AI 扩展市场')}
      description={syncNotice ? t(syncNotice.key, syncNotice.fallback) : t('ai.catalog.notice.description',
        '内部扩展按当前工作空间实时展示；官方 Registry 仅同步元数据，导入后仍使用现有安全校验与 OAuth 流程。')}
      action={<Space wrap>{syncState.isPending && <Spin size="small"/>}{syncState.data && <>
        <Tag>{t(syncStatus.key, syncStatus.fallback)} · {t(syncMode.key, syncMode.fallback)}</Tag>
        <Text type="secondary">{t('ai.catalog.sync.last', '最近完成')}：{formatTime(syncState.data.lastCompletedAt)}</Text></>}
        {permissions.data?.sync === true && !permissions.error && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'sync', sort: 1,
          text: t('ai.catalog.action.sync', '同步官方 Registry'), loading: busy && !target, disabled: locked || !selected}]}
          onButtonEvents={{sync: () => void mutate('sync')}}/>}</Space>}/>
    {permissions.error && <Alert type="error" showIcon title={t('ai.catalog.error.permissions', '操作权限加载失败，业务操作已禁用')}
      action={retry('permissions', () => {void permissions.refetch();})}/>}
    {syncState.error && <Alert type="error" showIcon title={t('ai.catalog.error.syncStatus', '同步状态加载失败')}
      action={retry('syncState', () => {void syncState.refetch();})}/>}
    {catalog.error && <Alert type="error" showIcon title={operationError(catalog.error, 'ai.catalog.error.load', '扩展市场加载失败')}
      action={retry('catalog', () => {void catalog.refetch();})}/>}
    {writeError && !target && <Alert type="error" showIcon title={writeError}/>}
    {receipt?.id && <Alert type="success" showIcon title={t('ai.catalog.import.receipt', '已创建草稿')}
      description={<Space wrap><Text>{receipt.name || receipt.code || '—'}</Text><Text code>{receipt.id}</Text></Space>}/>}
    <DataTable<CatalogItem> storageKey={config.name} rowKey="id" loading={catalog.isFetching || permissions.isPending}
      columns={columns} dataSource={catalog.data?.content ?? []} refresh={() => {if (!locked) void catalog.refetch();}} scroll={{x: 1100}}
      toolbarExtra={<Space wrap><Input.Search allowClear maxLength={200} value={draftQuery} disabled={locked}
        placeholder={t('ai.catalog.search.placeholder', '搜索名称、编码或说明')} onChange={event => setDraftQuery(event.target.value)}
        onSearch={value => {if (locked) return; const next = value.trim(); setQuery(next); setPage(0);
          if (next === query && page === 0) void catalog.refetch();}} style={{width: 320}}/>
        <Select allowClear value={source} disabled={locked} placeholder={t('ai.catalog.filter.source', '全部来源')}
          onChange={value => {setSource(value); setPage(0);}} style={{width: 190}} options={[
            {value: 'INTERNAL', label: t('ai.catalog.source.internal', '内部')}, {value: 'OFFICIAL_MCP', label: t('ai.catalog.source.official', '官方 MCP Registry')},
          ]}/>
        <Select allowClear value={kind} disabled={locked} placeholder={t('ai.catalog.filter.kind', '全部类型')}
          onChange={value => {setKind(value); setPage(0);}} style={{width: 160}} options={[{value: 'MCP_SERVER', label: 'MCP Server'}]}/>
      </Space>}
      pagination={{current: page + 1, pageSize: size, total: catalog.data?.totalElements ?? 0, showSizeChanger: true, disabled: locked,
        onChange: (nextPage, nextSize) => {if (locked) return; setPage(nextSize === size ? nextPage - 1 : 0); setSize(nextSize);}}}/>
    <Modal wrapClassName="catalog-import" open={!!target && selected} title={target?.operation === 'runtime'
      ? t('ai.catalog.action.importRuntime', '导入运行草稿') : t('ai.catalog.import.title', '导入 MCP Server')}
      onCancel={cancel} closable={!busy} maskClosable={!busy} keyboard={!busy} destroyOnHidden
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 1, text: t('ai.catalog.action.cancel', '取消'), disabled: busy},
        {key: 'import', sort: 2, type: 'primary', text: t('ai.catalog.action.import', '导入'), loading: busy, disabled: busy},
      ]} onButtonEvents={{cancel, import: () => {if (targetRef.current) void mutate(targetRef.current.operation);}}}/>}>
      <Space orientation="vertical">{target && <Text strong>{target.item.title || target.item.code || target.item.registryName || '—'}</Text>}
        <Text>{target?.operation === 'runtime' ? t('ai.catalog.import.runtimeDescription',
          '按该描述的首个受支持发行包创建隔离 Runtime Profile 草稿，不启动容器或执行命令；后续须通过 Runtime Profile 管理接口审核配置并发布。')
          : t('ai.catalog.import.description', '将创建无凭据的远程 MCP Server 草稿；如服务要求 OAuth，请在 MCP Servers 页面继续授权。')}</Text>
        {writeError && <Alert type="error" showIcon title={writeError}/>}</Space>
    </Modal>
  </div>;
}
