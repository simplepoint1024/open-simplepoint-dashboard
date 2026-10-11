import {lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState, type Key} from 'react';
import {Alert, App, Descriptions, Empty, Modal, Space, Spin, Splitter, Tabs, Tag, Tree, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import type {TableButtonProps} from '@simplepoint/components/Table';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {DeleteOutlined, EyeOutlined, PlusOutlined, ReloadOutlined} from '@simplepoint/shared/types/icon';
import JdbcDataSourceSelect, {enabledSources, type JdbcSourceOption} from '../JdbcDataSource/select';
import {findMetadataTreeNodeByKey, normalizeMetadataTreeNodes, renderMetadataTreeTitle, replaceMetadataTreeChildren,
  type MetadataNodeType, type MetadataPathSegment, type MetadataTreeNode} from '../metadataTree';
import {constraintTypeTitle, metadataPayload, type DialogTarget, type FormKind, type MetadataDraft} from './forms';
import type {MetadataFormHandle} from './MetadataForm';
import api from '@/api';
import './index.css';

const MetadataForm = lazy(() => import('./MetadataForm'));
const base = api['platform.dna-metadata'];
const sources = api['platform.dna-data-sources'];
type Permissions = Record<'view' | 'preview' | 'createNamespace' | 'createTable' | 'createView' |
  'alterColumn' | 'dropColumn' | 'addConstraint' | 'dropConstraint' | 'drop', boolean>;
type Column = {name: string; typeName: string; size?: number | null; scale?: number | null; nullable?: boolean | null;
  defaultValue?: string | null; autoIncrement?: boolean | null; remarks?: string | null};
type Constraint = {name?: string; type: string; columns?: string[]; reference?: {tablePath?: MetadataPathSegment[]; columns?: string[]}; checkExpression?: string};
type Structure = {columns: Column[]; constraints: Constraint[]; dialectName?: string; dialectCode?: string};
type Preview = {columns: string[]; content: Record<string, unknown>[]; totalElements: number; pageNumber: number; pageSize: number};
const noPath: MetadataPathSegment[] = [];
const noNames: string[] = [];
const columnData = (column?: Column) => column ? {...column} : {name: '', typeName: '', nullable: true, autoIncrement: false};
const isDelete = (kind: FormKind) => ['drop', 'dropColumn', 'dropConstraint'].includes(kind);
const validSource = (row?: JdbcSourceOption) => !!row?.id && row.id !== '***' && !!row.code && row.code !== '***';

export default function JdbcMetadata() {
  const scope = useQueryScope();
  return <MetadataWorkbench key={JSON.stringify(scope)}/>;
}
function MetadataWorkbench() {
  const {t, ensure, locale} = useI18n();
  const [chosen, setChosen] = useState<JdbcSourceOption>();
  const [locked, setLocked] = useState(false);
  const lock = useRef(false);
  const permissions = useData(['dna-metadata-permissions', base.baseUrl], ({signal}) =>
    get<Permissions>(base.baseUrl + '/workbench-permissions', undefined, {signal}), {retry: false});
  const initial = useData(['dna-metadata-initial-source', sources.baseUrl], async ({signal}) =>
    normalizePage(await get<RawPage<JdbcSourceOption>>(sources.baseUrl,
      {page: 0, size: 1, sort: 'name,asc', ...enabledSources}, {signal})).content[0],
  {enabled: permissions.data?.view === true && !chosen, retry: false});
  const source = chosen ?? initial.data;
  useEffect(() => {void ensure(base.i18nNamespaces);}, [ensure, locale]);
  const onLock = useCallback((value: boolean) => {lock.current = value; setLocked(value);}, []);
  return <div className="dna-metadata-workbench">
    <div className="sp-table-toolbar">
      <div className="dna-metadata-source"><JdbcDataSourceSelect value={source?.code} disabled={locked || permissions.data?.view !== true}
        placeholder={t('dna.metadata.page.placeholder.selectDataSource', 'Select a data source')}
        onChange={(_code, row) => {if (!lock.current && validSource(row)) setChosen(row);}}/></div>
    </div>
    {(permissions.error || initial.error) && <Alert type="error" showIcon
      title={resolveApiErrorMessage(permissions.error ?? initial.error, t('table.loadFail', '加载失败'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0,
        text: t('table.retry', '重试'), onClick: () => void (permissions.error ? permissions.refetch() : initial.refetch())}]}/>}/>}
    {permissions.isLoading || initial.isLoading ? <Spin/> : permissions.data?.view && validSource(source)
      ? <MetadataSession key={source!.id} source={source!} permissions={permissions.data} onLock={onLock}/>
      : <Empty description={t('dna.metadata.page.empty.selectDataSource', 'Select a data source first.')}/>}
  </div>;
}

function MetadataSession({source, permissions, onLock}: {source: JdbcSourceOption; permissions: Permissions; onLock: (value: boolean) => void}) {
  const {t} = useI18n();
  const {message} = App.useApp();
  const selectedPage = usePageSelected();
  const [revision, setRevision] = useState(0);
  const [tree, setTree] = useState<MetadataTreeNode[]>([]);
  const [selectedKeys, setSelectedKeys] = useState<Key[]>([]);
  const [node, setNode] = useState<MetadataTreeNode | null>(null);
  const [tab, setTab] = useState('structure');
  const [previewPage, setPreviewPage] = useState({number: 0, size: 20});
  const [dialog, setDialog] = useState<DialogTarget>();
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string>();
  const [childError, setChildError] = useState<{node: MetadataTreeNode; message: string}>();
  const form = useRef<MetadataFormHandle>(null);
  const mutation = useRef(false);
  const mounted = useRef(true);
  const generation = useRef(0);
  const childRequests = useRef(new Map<string, AbortController>());
  const translation = useRef(t); translation.current = t;
  const label = useCallback((type: MetadataNodeType, fallback?: string | null) => {
    const t = translation.current;
    return t(`dna.metadata.page.nodeType.${type}`, fallback || type);
  }, []);
  const endpoint = base.baseUrl + '/' + encodeURIComponent(source.id);
  const root = useData(['dna-metadata-root', endpoint, revision], async ({signal}) => {
    const data = await post<MetadataTreeNode[]>(endpoint + '/children', {path: noPath}, {signal});
    if (!Array.isArray(data)) throw Error(translation.current('table.loadFail', '加载失败'));
    return normalizeMetadataTreeNodes(data, label, {dataSourceId: source.id});
  }, {retry: false});
  useEffect(() => {if (root.data) setTree(root.data);}, [root.data]);
  useEffect(() => {
    mounted.current = true;
    return () => {mounted.current = false; generation.current++; childRequests.current.forEach(request => request.abort());};
  }, []);
  usePageRetention(!dialog && !submitting, !!dialog || submitting);
  const relation = useMemo(() => node?.type === 'TABLE' || node?.type === 'VIEW' ? node.path
    : node?.type === 'COLUMN' ? node.path.slice(0, -1) : undefined, [node]);
  const tablePath = relation?.[relation.length - 1]?.type === 'TABLE' ? relation : undefined;
  const structure = useData(['dna-metadata-structure', endpoint, relation, revision], ({signal}) =>
    post<Structure>(endpoint + '/structure', {path: relation}, {signal}), {enabled: !!relation, retry: false});
  const preview = useData(['dna-metadata-preview', endpoint, relation, previewPage, revision], ({signal}) =>
    post<Preview>(endpoint + '/preview?page=' + previewPage.number + '&size=' + previewPage.size,
      {path: relation}, {signal}), {enabled: !!relation && tab === 'preview' && permissions.preview, retry: false});
  const rootType = ['DATABASE', 'CATALOG', 'SCHEMA'].includes(tree[0]?.type) ? tree[0].type : undefined;
  const namespaceTypes = useMemo(() => node?.type === 'DATABASE' || node?.type === 'CATALOG' ? ['SCHEMA']
    : !node && rootType ? [rootType] : [], [node, rootType]);
  const namespacePath = !node ? rootType ? undefined : noPath
    : ['DATABASE', 'CATALOG', 'SCHEMA'].includes(node.type) ? node.path : undefined;
  const columnNames = useMemo(() => structure.data?.columns?.map(column => column.name) ?? noNames, [structure.data]);
  const currentName = node?.type === 'COLUMN' ? node.path[node.path.length - 1]?.name : undefined;
  const currentColumn = structure.data?.columns?.find(column => column.name === currentName);
  const can = (kind: FormKind) => permissions[kind === 'namespace' ? 'createNamespace' : kind === 'table' ? 'createTable'
    : kind === 'view' ? 'createView' : kind === 'columnAdd' || kind === 'columnAlter' ? 'alterColumn'
      : kind === 'constraint' ? 'addConstraint' : kind];
  const open = (target: DialogTarget) => {
    if (mutation.current || dialog || !can(target.kind)) return;
    // Capture the exact source/path before opening: later tree selection cannot retarget a DDL request.
    onLock(true); setSubmitError(undefined); setDialog(structuredClone(target));
  };
  const close = () => {if (!mutation.current) {setDialog(undefined); setSubmitError(undefined); onLock(false);}};
  const refresh = () => {
    if (mutation.current || dialog) return;
    generation.current++; childRequests.current.forEach(request => request.abort()); childRequests.current.clear();
    setSelectedKeys([]); setNode(null); setTree([]); setChildError(undefined); setRevision(value => value + 1);
  };
  const loadChildren = async (treeNode: MetadataTreeNode) => {
    if (treeNode.isLeaf || treeNode.loaded || mutation.current || dialog) return;
    const key = String(treeNode.key);
    if (childRequests.current.has(key)) return;
    const abort = new AbortController(); childRequests.current.set(key, abort);
    const current = generation.current;
    try {
      const data = await post<MetadataTreeNode[]>(endpoint + '/children', {path: treeNode.path}, {signal: abort.signal});
      if (!mounted.current || abort.signal.aborted || current !== generation.current) return;
      if (!Array.isArray(data)) throw Error(t('table.loadFail', '加载失败'));
      const children = normalizeMetadataTreeNodes(data, label, {dataSourceId: source.id});
      setTree(previous => replaceMetadataTreeChildren(previous, treeNode.key, children)); setChildError(undefined);
    } catch (error) {
      if (mounted.current && !abort.signal.aborted && current === generation.current) {
        setChildError({node: treeNode, message: resolveApiErrorMessage(error, t('dna.metadata.page.error.loadChildren', 'Failed to load child nodes.'))});
        // Reject so Ant Tree does not mark a failed node as loaded; retry remains meaningful.
        throw error;
      }
    } finally {if (childRequests.current.get(key) === abort) childRequests.current.delete(key);}
  };
  const submit = async (data: MetadataDraft) => {
    if (!dialog || mutation.current || !can(dialog.kind)) return;
    mutation.current = true; setSubmitting(true); setSubmitError(undefined);
    try {
      const request = metadataPayload(dialog, data);
      await post(endpoint + '/' + request.suffix, request.payload);
      if (!mounted.current) return;
      message.success(isDelete(dialog.kind) ? t('table.deleteSuccess', '删除成功') : dialog.kind === 'columnAlter'
        ? t('table.editSuccess', '修改成功') : t('table.addSuccess', '新增成功'));
      generation.current++; childRequests.current.forEach(request => request.abort()); childRequests.current.clear();
      setDialog(undefined); setNode(null); setSelectedKeys([]); setTree([]); setRevision(value => value + 1); onLock(false);
    } catch (error) {if (mounted.current) setSubmitError(resolveApiErrorMessage(error, t('error.operationFailed', '操作失败，请重试')));}
    finally {mutation.current = false; if (mounted.current) setSubmitting(false);}
  };
  const action = (key: string, text: string, onClick: () => void, disabled = false, danger = false): TableButtonProps =>
    ({key, sort: 0, text, disabled: disabled || !!dialog || submitting || root.isFetching || !!root.error, danger,
      icon: danger ? <DeleteOutlined/> : key === 'refresh' ? <ReloadOutlined/> : <PlusOutlined/>, onClick});
  const buttons: TableButtonProps[] = [
    action('refresh', t('dna.metadata.page.button.refreshTree', 'Refresh Tree'), refresh),
    ...(permissions.createNamespace ? [action('createNamespace', t('dna.metadata.page.button.createNamespace', 'Create Namespace'),
      () => open({kind: 'namespace', path: namespacePath ?? noPath, data: {type: namespaceTypes[0], name: ''}}), !namespaceTypes.length)] : []),
    ...(permissions.createTable ? [action('createTable', t('dna.metadata.page.button.createTable', 'Create Table'),
      () => open({kind: 'table', path: namespacePath!, data: {name: '', columns: [], constraints: []}}), !namespacePath)] : []),
    ...(permissions.createView ? [action('createView', t('dna.metadata.page.button.createView', 'Create View'),
      () => open({kind: 'view', path: namespacePath!, data: {name: '', definitionSql: ''}}), !namespacePath)] : []),
    ...(permissions.alterColumn ? [
      action('addColumn', t('dna.metadata.page.button.addColumn', 'Add Column'),
        () => open({kind: 'columnAdd', path: tablePath!, data: columnData()}), !tablePath),
      action('alterColumn', t('dna.metadata.page.button.alterColumn', 'Alter Column'),
        () => open({kind: 'columnAlter', path: tablePath!, currentName, data: columnData(currentColumn)}),
        !tablePath || !currentColumn || structure.isFetching),
    ] : []),
    ...(permissions.addConstraint ? [action('addConstraint', t('dna.metadata.page.button.addConstraint', 'Add Constraint'),
      () => open({kind: 'constraint', path: tablePath!, data: {type: 'PRIMARY_KEY', columns: []}}),
      !tablePath || structure.isFetching || !!structure.error)] : []),
    ...(permissions.drop ? [action('drop', t('dna.metadata.page.button.dropObject', 'Delete Object'),
      () => open({kind: 'drop', path: node!.path, data: {cascade: false}}),
      !node || node.type === 'ROOT' || node.type === 'COLUMN', true)] : []),
    ...(permissions.dropColumn ? [action('dropColumn', t('dna.metadata.page.button.dropColumn', 'Delete Column'),
      () => open({kind: 'dropColumn', path: tablePath!, currentName, data: {}}), !tablePath || !currentName, true)] : []),
  ];
  const yesNo = (value?: boolean | null) => value == null ? '—'
    : <Tag color={value ? 'success' : 'default'}>{value ? t('dna.metadata.page.state.yes', 'Yes') : t('dna.metadata.page.state.no', 'No')}</Tag>;
  const columns: ColumnsType<Column> = [
    {key: 'name', dataIndex: 'name', title: t('dna.metadata.page.table.columns.name', 'Column Name'), width: 180},
    {key: 'type', dataIndex: 'typeName', title: t('dna.metadata.page.table.columns.type', 'Type'), width: 180,
      render: (_value, column) => column.typeName + (column.size != null && column.size > 0
        ? '(' + column.size + (column.scale != null && column.scale >= 0 ? ',' + column.scale : '') + ')' : '')},
    {key: 'nullable', dataIndex: 'nullable', title: t('dna.metadata.page.table.columns.nullable', 'Nullable'), width: 100, render: yesNo},
    {key: 'defaultValue', dataIndex: 'defaultValue', title: t('dna.metadata.page.table.columns.defaultValue', 'Default Value'), width: 160},
    {key: 'autoIncrement', dataIndex: 'autoIncrement', title: t('dna.metadata.page.table.columns.autoIncrement', 'Auto Increment'), width: 120, render: yesNo},
    {key: 'remarks', dataIndex: 'remarks', title: t('dna.metadata.page.table.columns.remarks', 'Remarks'), width: 180},
  ];
  const constraints: ColumnsType<Constraint> = [
    {key: 'name', dataIndex: 'name', title: t('dna.metadata.page.table.constraints.name', 'Constraint Name'), width: 180},
    {key: 'type', dataIndex: 'type', title: t('dna.metadata.page.table.constraints.type', 'Type'), width: 120,
      render: value => constraintTypeTitle(t, value)},
    {key: 'columns', dataIndex: 'columns', title: t('dna.metadata.page.table.constraints.columns', 'Columns'), width: 200,
      render: value => Array.isArray(value) ? value.join(', ') : '—'},
    {key: 'reference', dataIndex: 'reference', title: t('dna.metadata.page.table.constraints.reference', 'Reference'), width: 260,
      render: (value: Constraint['reference']) => value?.tablePath?.length
        ? value.tablePath.map(part => part.name).join(' / ') + (value.columns?.length ? ' (' + value.columns.join(', ') + ')' : '') : '—'},
    {key: 'checkExpression', dataIndex: 'checkExpression', title: t('dna.metadata.page.table.constraints.checkExpression', 'CHECK Expression'), width: 200},
    ...(permissions.dropConstraint ? [{key: 'actions', title: t('dna.metadata.page.table.constraints.actions', 'Actions'), width: 120,
      render: (_value: unknown, constraint: Constraint) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        action('dropConstraint', t('dna.metadata.page.table.constraints.drop', 'Delete'),
          () => open({kind: 'dropConstraint', path: tablePath!, currentName: constraint.name, constraintType: constraint.type, data: {}}),
          !tablePath || !constraint.name, true)]}/>,
    }] : []),
  ];
  const titles: Record<FormKind, string> = {
    namespace: t('dna.metadata.page.modal.namespace.title', 'Create Namespace'),
    table: t('dna.metadata.page.modal.table.title', 'Create Table'), view: t('dna.metadata.page.modal.view.title', 'Create View'),
    columnAdd: t('dna.metadata.page.modal.column.addTitle', 'Add Column'), columnAlter: t('dna.metadata.page.modal.column.alterTitle', 'Alter Column'),
    constraint: t('dna.metadata.page.modal.constraint.title', 'Add Constraint'),
    drop: t('dna.metadata.page.button.dropObject', 'Delete Object'), dropColumn: t('dna.metadata.page.button.dropColumn', 'Delete Column'),
    dropConstraint: t('dna.metadata.page.table.constraints.drop', 'Delete'),
  };
  const retry = (run: () => void) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{
    key: 'retry', sort: 0, text: t('table.retry', '重试'), disabled: submitting || !!dialog, onClick: run}]}/>;
  return <>
    <div className="sp-table-toolbar"><TableActions selectedKeys={[]} selectedRows={[]} buttons={buttons}/></div>
    <Splitter className="dna-metadata-split">
      <Splitter.Panel defaultSize="30%" min={180} max="60%">
        <div className="dna-metadata-pane">
          {root.error ? <Alert type="error" showIcon title={resolveApiErrorMessage(root.error, t('dna.metadata.page.error.loadTree', 'Failed to load metadata tree.'))}
            action={retry(() => void root.refetch())}/> : root.isFetching && !tree.length ? <Spin/> : <Tree blockNode
              treeData={tree} selectedKeys={selectedKeys} disabled={!!dialog || submitting}
              loadData={loadChildren} titleRender={node => renderMetadataTreeTitle(node.type, node.rawTitle ?? '', label)}
              onSelect={keys => {
                if (mutation.current || dialog) return;
                setSelectedKeys(keys); setNode(keys.length ? findMetadataTreeNodeByKey(tree, keys[0]) : null);
                setPreviewPage(previous => ({...previous, number: 0}));
              }}/>}
          {childError && <Alert type="error" showIcon title={childError.message} action={retry(() => {
            void loadChildren(childError.node).catch(() => undefined);
          })}/>}
          {!root.isFetching && !root.error && !tree.length && <Empty description={t('dna.metadata.page.empty.noObjects', 'No objects are available for this data source.')}/>}
        </div>
      </Splitter.Panel>
      <Splitter.Panel min={320}>
        <div className="dna-metadata-pane">
          {!node ? <Empty description={t('dna.metadata.page.empty.selectObject', 'Select an object from the left tree.')}/> : <>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label={t('dna.metadata.page.detail.name', 'Name')}>{node.path[node.path.length - 1]?.name ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('dna.metadata.page.detail.type', 'Type')}>{label(node.type, node.typeLabel)}</Descriptions.Item>
              <Descriptions.Item label={t('dna.metadata.page.detail.path', 'Path')} span={2}>{node.path.map(part => part.name).join(' / ')}</Descriptions.Item>
              <Descriptions.Item label={t('dna.metadata.page.detail.dataType', 'Data Type')}>{node.dataType ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('dna.metadata.page.detail.nullable', 'Nullable')}>{yesNo(node.nullable)}</Descriptions.Item>
              <Descriptions.Item label={t('dna.metadata.page.detail.defaultValue', 'Default Value')}>{node.defaultValue ?? '—'}</Descriptions.Item>
              <Descriptions.Item label={t('dna.metadata.page.detail.remarks', 'Remarks')}>{node.remarks ?? '—'}</Descriptions.Item>
            </Descriptions>
            {relation && <Tabs activeKey={tab} onChange={setTab} tabBarExtraContent={tab === 'preview' && permissions.preview
              ? <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'refreshPreview', sort: 0, icon: <EyeOutlined/>,
                text: t('dna.metadata.page.button.refreshPreview', 'Refresh Preview'), disabled: preview.isFetching || !!dialog,
                onClick: () => void preview.refetch()}]}/> : undefined}
              items={[{key: 'structure', label: t('dna.metadata.page.tab.structure', 'Structure'), children: <>
                {structure.error && <Alert type="error" showIcon title={resolveApiErrorMessage(structure.error, t('dna.metadata.page.error.loadStructure', 'Failed to load table structure.'))}
                  action={retry(() => void structure.refetch())}/>}
                <Typography.Paragraph>{t('dna.metadata.page.detail.dialect', 'Dialect') + ': '}
                  {structure.data?.dialectName ?? structure.data?.dialectCode ?? '—'}</Typography.Paragraph>
                <Space orientation="vertical" style={{width: '100%'}}>
                  <DataTable<Column> storageKey="dna.metadata.columns" rowKey="name" columns={columns}
                    loading={structure.isFetching} dataSource={structure.error ? [] : structure.data?.columns ?? []}
                    query={{mode: 'local', scope: 'complete', contextKey: JSON.stringify([source.id, relation]), fields: {
                      name: {type: 'string'}, typeName: {type: 'string', columnKey: 'type'}, nullable: {type: 'boolean'},
                      autoIncrement: {type: 'boolean'}, defaultValue: {type: 'string'}, remarks: {type: 'string'}}}}
                    pagination={false} size="small" scroll={{x: 960}}/>
                  <DataTable<Constraint> storageKey="dna.metadata.constraints" rowKey={row => JSON.stringify([row.type, row.name, row.columns])}
                    columns={constraints} loading={structure.isFetching} dataSource={structure.error ? [] : structure.data?.constraints ?? []}
                    query={{mode: 'local', scope: 'complete', contextKey: JSON.stringify([source.id, relation]), fields: {
                      name: {type: 'string'}, type: {type: 'string', enum: ['PRIMARY_KEY', 'FOREIGN_KEY', 'UNIQUE', 'CHECK']},
                      checkExpression: {type: 'string'}}}} pagination={false} size="small" scroll={{x: 1100}}/>
                </Space>
              </>}, ...(permissions.preview ? [{key: 'preview', label: t('dna.metadata.page.tab.preview', 'Data Preview'), children: <>
                {preview.error && <Alert type="error" showIcon title={resolveApiErrorMessage(preview.error, t('dna.metadata.page.error.loadPreview', 'Failed to load data preview.'))}
                  action={retry(() => void preview.refetch())}/>}
                <DataTable<Record<string, unknown>> rowKey={(_row, index) => previewPage.number + '-' + index}
                  data-query-exception="external-database-preview"
                  toolbarExtra={<Typography.Text type="secondary">{t('dna.metadata.page.preview.queryScope',
                    '外部数据库分页预览，不提供表头筛选或排序；全量条件查询请使用 SQL 工作台。')}</Typography.Text>}
                  loading={preview.isFetching} dataSource={preview.error ? [] : preview.data?.content ?? []}
                  columns={preview.data?.columns?.map(name => ({key: name, dataIndex: [name], title: name, width: 180, ellipsis: true,
                    render: value => value == null ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value)})) ?? []}
                  pagination={{current: previewPage.number + 1, pageSize: previewPage.size, total: preview.data?.totalElements ?? 0,
                    showSizeChanger: true, pageSizeOptions: [10, 20, 50]}}
                  onChange={page => setPreviewPage({number: page.pageSize !== previewPage.size ? 0 : (page.current ?? 1) - 1, size: page.pageSize ?? 20})}
                  size="small" scroll={{x: 'max-content'}}/>
              </>}] : [])]}/>}
          </>}
        </div>
      </Splitter.Panel>
    </Splitter>
    <Modal rootClassName="dna-metadata-dialog" open={!!dialog && selectedPage} title={dialog && titles[dialog.kind]}
      destroyOnHidden width={dialog?.kind === 'table' || dialog?.kind === 'view' ? 760 : 600}
      confirmLoading={submitting} closable={!submitting} maskClosable={!submitting} keyboard={!submitting}
      onCancel={close} onOk={() => form.current?.submit()} okButtonProps={{danger: dialog && isDelete(dialog.kind)}}
      afterClose={() => {if (!dialog) onLock(false);}}>
      {dialog && <Space orientation="vertical" style={{width: '100%'}}>
        <Typography.Text code>{source.name ?? source.code} / {dialog.path.map(part => part.name).join(' / ')}
          {dialog.currentName ? ' / ' + dialog.currentName : ''}</Typography.Text>
        {isDelete(dialog.kind) && <Alert type="warning" showIcon title={t('dna.metadata.page.delete.warning',
          'This operation changes the physical database and cannot be undone here. Confirm the source and target before proceeding.')}/>}
        {submitError && <Alert type="error" showIcon title={submitError}/>}
        <Suspense fallback={<Spin/>}><MetadataForm key={JSON.stringify([dialog.kind, dialog.path, dialog.currentName])}
          ref={form} target={dialog} namespaceTypes={namespaceTypes} columns={columnNames} disabled={submitting} onSubmit={data => void submit(data)}/></Suspense>
      </Space>}
    </Modal>
  </>;
}
