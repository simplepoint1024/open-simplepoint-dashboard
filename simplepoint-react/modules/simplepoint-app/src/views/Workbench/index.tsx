import ApplicationNavigation from './ApplicationNavigation';
import {localizedTextSchema, localizedDraft} from '@simplepoint/shared/i18n/localizedSchema';
import {resolveLocalizedText, type LocalizedText} from '@simplepoint/shared/i18n/localizedText';
import {formatDateTime} from '@simplepoint/shared/format/temporal';
import DataTable from '@simplepoint/components/DataTable';
import {useRemoteList} from '@simplepoint/components/DataTable/useRemoteList';
import TableActions from '@simplepoint/components/Table/Actions';
import DeleteConfirmation from '@simplepoint/components/Table/DeleteConfirmation';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App, Drawer, Modal, Space, Spin, Tag, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router';

const PageFormDesigner = lazy(() => import('./forms/PageFormDesigner'));
const ApplicationForms = lazy(() => import('./forms/ApplicationForms'));

type ApplicationSummary = {
  id: string;
  name: string;
  nameLocalization?: LocalizedText;
  description?: string;
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINING' | 'OFFLINE' | 'DELETING';
  mainPageName: string;
  mainPageNameLocalization?: LocalizedText;
  attachedPageCount: number;
  updatedAt: string;
};

type ApplicationPage = {
  id: string;
  name: string;
  nameLocalization?: LocalizedText;
  description?: string;
  kind: 'MAIN' | 'ATTACHED';
  status: 'DRAFT' | 'PUBLISHED' | 'OFFLINING' | 'OFFLINE';
  resourceRegistered: boolean;
  runtimePath?: string;
  formId?: string;
  updatedAt: string;
};

type ApplicationDetail = {application: ApplicationSummary; pages: ApplicationPage[]};
type Permissions = {canCreateApplication: boolean; canAddPage: boolean; canDesignForm: boolean;
  canDeleteApplication: boolean; canDeletePage: boolean; canManageForms: boolean};
type CreateApplicationValues = {name: string; description?: string; mainPageName: string; navigationGroup?: boolean; nameLocalization?: LocalizedText; mainPageNameLocalization?: LocalizedText};
type CreatePageValues = {name: string; description?: string; nameLocalization?: LocalizedText};
type DeleteTarget = {url: string; rows: {id: string; name: string; updatedAt: string}[]; kind: 'application' | 'page';
  removal?: {applicationId: string; expectedUpdatedAt: string; id: string}[]};
type Removal = {applicationId: string; id: string; phase: 'STORAGE' | 'AUTHORIZATION' | 'COMPLETED'};

const draftPage = (row: ApplicationPage) => row.kind === 'ATTACHED' && row.status === 'DRAFT'
  && !row.resourceRegistered && !row.formId && !!row.updatedAt;

const endpoint = '/app/applications';

export default function Workbench() {
  const scope = useQueryScope();
  return <WorkbenchSession key={JSON.stringify(scope)}/>;
}

function WorkbenchSession() {
  const navigate = useNavigate();
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope();
  const appForm = useRef<SFormHandle>(null);
  const pageForm = useRef<SFormHandle>(null);
  const [applicationValues, setApplicationValues] = useState<Partial<CreateApplicationValues>>({});
  const [pageValues, setPageValues] = useState<Partial<CreatePageValues>>({});
  const [createOpen, setCreateOpen] = useState(false);
  const [navigationOpen, setNavigationOpen] = useState(false);
  const [addPageOpen, setAddPageOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [detailId, setDetailId] = useState<string>();
  const [workspace, setWorkspace] = useState<{id: string; name: string; page?: ApplicationPage}>();
  const [pageTarget, setPageTarget] = useState<{id: string; name: string}>();
  const [writeError, setWriteError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [discard, setDiscard] = useState<'app' | 'page'>();
  const [selectedApplications, setSelectedApplications] = useState<ApplicationSummary[]>([]);
  const [selectedPages, setSelectedPages] = useState<ApplicationPage[]>([]);
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget>();
  const [deleteError, setDeleteError] = useState('');
  const [removals, setRemovals] = useState<Removal[]>([]);
  const locked = operation.busy || createOpen || addPageOpen || !!detailId || !!workspace || !!deleteTarget;
  usePageRetention(!locked, createOpen || addPageOpen || operation.busy || !!deleteTarget);

  useEffect(() => { void ensure(['app-workbench']); }, [ensure, locale]);
  useEffect(() => {setSelectedPages([]);}, [detailId]);

  const permissionsQuery = useData(['app', 'permissions'], ({signal}) => get<Permissions>(endpoint + '/permissions', undefined, {signal}),
    {retry: false, staleTime: 30_000, enabled: !workspace});
  const permissions = permissionsQuery.data;
  const capability = useOwnedData(['app', 'application-removal-capability'],
    ({signal}) => get<{enabled: boolean}>(endpoint + '/removals/capability', undefined, {signal, notifyOnError: false}),
    {enabled: permissions?.canDeleteApplication === true, retry: false, staleTime: 30_000});
  const hasRemoval = capability.data?.enabled === true;
  const canRemove = useCallback((row: ApplicationSummary) => row.status === 'DRAFT'
    || hasRemoval && row.status === 'OFFLINE', [hasRemoval]);
  const table = useRemoteList<ApplicationSummary>(endpoint, operation.selected && !workspace);
  const error = !!table.error;
  const load = table.refresh;
  const progress = useOwnedData(['app', 'application-removals', removals.map(row => row.id)],
    ({signal}) => get<Removal[]>(endpoint + '/removals',
      {applicationIds: removals.map(row => row.applicationId)}, {signal, notifyOnError: false}),
    {enabled: removals.length > 0 && operation.selected, retry: false, refetchInterval: query =>
      query.state.data?.length === removals.length && query.state.data.every(row => row.phase === 'COMPLETED') ? false : 2000});
  const removed = (progress.data ?? removals).filter(row => row.phase === 'COMPLETED').length;
  const complete = removals.length > 0 && removed === removals.length;
  useEffect(() => {if (complete) void load();}, [complete, load]);
  const detailQuery = useData(['app', 'detail', detailId], ({signal}) => get<ApplicationDetail>(endpoint + '/' + encodeURIComponent(detailId!), undefined, {signal}),
    {enabled: Boolean(detailId), retry: false});
  const detailLoading = detailQuery.isFetching;
  const detail = useMemo(() => detailQuery.data ? {...detailQuery.data,
    application: {...detailQuery.data.application, name: resolveLocalizedText(detailQuery.data.application.nameLocalization, locale, undefined, 'TENANT', detailQuery.data.application.name).text},
    pages: detailQuery.data.pages.map(page => ({...page, name: resolveLocalizedText(page.nameLocalization, locale, undefined, 'TENANT', page.name).text})),
  } : undefined, [detailQuery.data, locale]);
  const openDetail = useCallback((id: string) => {if (!locked && operation.isSelected() && !error && id) setDetailId(id);},
    [locked, operation.isSelected, error]);
  const chooseDelete = useCallback((target: DeleteTarget) => {
    if (!operation.isSelected() || operation.isBusy()) return;
    setDeleteError('');
    setDeleteTarget(target);
  }, [operation.isSelected, operation.isBusy]);
  const chooseApplications = useCallback((rows: ApplicationSummary[]) => {
    if (!rows.every(canRemove)) return;
    chooseDelete({kind: 'application', url: endpoint, rows: rows.map(row => ({...row,
      name: resolveLocalizedText(row.nameLocalization, locale, undefined, 'TENANT', row.name).text})),
      removal: hasRemoval ? rows.map(row => ({applicationId: row.id, expectedUpdatedAt: row.updatedAt,
        id: crypto.randomUUID()})) : undefined});
  }, [canRemove, chooseDelete, hasRemoval, locale]);

  const confirmDelete = async () => {
    if (!deleteTarget || permissionsQuery.error
      || !(deleteTarget.kind === 'application' ? permissions?.canDeleteApplication : permissions?.canDeletePage)) return;
    const controller = operation.begin();
    if (!controller) return;
    setDeleteError('');
    try {
      const accepted = deleteTarget.removal
        ? await post<Removal[]>(endpoint + '/removals', deleteTarget.removal, {signal: controller.signal, notifyOnError: false})
        : await request<void>(deleteTarget.url, {method: 'DELETE',
        json: deleteTarget.rows.map(row => ({id: row.id, expectedUpdatedAt: row.updatedAt})),
        signal: controller.signal, notifyOnError: false});
      if (!operation.current(controller.signal)) return;
      if (accepted) setRemovals(accepted);
      setDeleteTarget(undefined);
      if (deleteTarget.kind === 'page') setSelectedPages([]);
      else setSelectedApplications([]);
      await Promise.all([load(), deleteTarget.kind === 'page' ? detailQuery.refetch() : undefined]);
      if (!accepted && operation.current(controller.signal)) message.success(t('table.deleteSuccess', '删除成功'));
    } catch (cause) {
      if (operation.current(controller.signal)) {
        setDeleteError(resolveApiErrorMessage(cause, t('app.deleteFailed', '删除失败')));
      }
    } finally {operation.finish(controller);}
  };

  const closeDraft = (target: 'app' | 'page', force = false) => {
    if (savingRef.current || !operation.isSelected()) return;
    if (dirty && !force) {setDiscard(target); return;}
    setDiscard(undefined); setDirty(false); setWriteError('');
    if (target === 'app') {setCreateOpen(false); setApplicationValues({});}
    else {setAddPageOpen(false); setPageValues({}); setPageTarget(undefined);}
  };

  const createApplication = async (values: CreateApplicationValues) => {
    if (!createOpen || !permissions?.canCreateApplication || permissionsQuery.error || savingRef.current || error) return;
    const controller = operation.begin(); if (!controller) return; setWriteError('');
    savingRef.current = true; setSaving(true);
    try {
      const created = await post<ApplicationDetail>(endpoint, {...values, name: values.nameLocalization?.defaultValue ?? values.name, mainPageName: values.mainPageNameLocalization?.defaultValue ?? values.mainPageName}, {notifyOnError: false, signal: controller.signal});
      if (!operation.current(controller.signal)) return;
      setCreateOpen(false);
      setApplicationValues({});
      setDirty(false);
      setDetailId(created.application.id);
      await load();
      message.success(t('app.application.created', '应用已创建'));
    } catch (cause) {
      if (operation.current(controller.signal)) setWriteError(resolveApiErrorMessage(cause, t('app.application.createFailed', '创建应用失败')));
    } finally {
      savingRef.current = false; operation.finish(controller); if (operation.current()) setSaving(false);
    }
  };

  const addPage = async (values: CreatePageValues) => {
    if (!addPageOpen || !pageTarget?.id || !detail || detail.application.id !== pageTarget.id
      || !permissions?.canAddPage || permissionsQuery.error || detailQuery.error || savingRef.current) return;
    const captured = pageTarget.id, controller = operation.begin(); if (!controller) return; setWriteError('');
    savingRef.current = true; setSaving(true);
    try {
      await post(endpoint + '/' + encodeURIComponent(captured) + '/pages', {...values, name: values.nameLocalization?.defaultValue ?? values.name}, {notifyOnError: false, signal: controller.signal});
      if (!operation.current(controller.signal)) return;
      setAddPageOpen(false);
      setPageValues({});
      setPageTarget(undefined); setDirty(false);
      await Promise.all([detailQuery.refetch(), load()]);
      message.success(t('app.page.created', '附属页面已添加'));
    } catch (cause) {
      if (operation.current(controller.signal)) setWriteError(resolveApiErrorMessage(cause, t('app.page.createFailed', '添加页面失败')));
    } finally {
      savingRef.current = false; operation.finish(controller); if (operation.current()) setSaving(false);
    }
  };

  const statusTag = (status: ApplicationSummary['status']) => status === 'DELETING'
    ? <Tag color="processing">{t('app.application.deleting', '删除中')}</Tag> : status === 'OFFLINING'
    ? <Tag color="processing">{t('app.resource.offlining', '下线中')}</Tag> : status === 'OFFLINE'
    ? <Tag>{t('app.resource.offline', '已下线')}</Tag> : status !== 'DRAFT' && status !== 'PUBLISHED' ? '—' : (
    <Tag color={status === 'PUBLISHED' ? 'green' : 'default'}>
      {status === 'PUBLISHED' ? t('app.status.published', '已发布') : t('app.status.draft', '草稿')}
    </Tag>
  );

  const columns: ColumnsType<ApplicationSummary> = useMemo(() => [
    {title: t('app.application.name', '应用名称'), dataIndex: 'name', key: 'name',
      render: (name: string, row) => resolveLocalizedText(row.nameLocalization, locale, undefined, 'TENANT', name).text},
    {title: t('app.application.description', '说明'), dataIndex: 'description',
      key: 'description', ellipsis: true, render: (value?: string) => value || '—'},
    {title: t('app.application.mainPage', '主页面'), dataIndex: 'mainPageName',
      key: 'mainPageName', width: 180, render: (name: string, row) => resolveLocalizedText(row.mainPageNameLocalization, locale, undefined, 'TENANT', name).text},
    {title: t('app.application.attachedPages', '附属页面'), dataIndex: 'attachedPageCount',
      key: 'attachedPageCount', width: 120},
    {title: t('app.application.status', '状态'), dataIndex: 'status',
      key: 'status', width: 110, render: statusTag},
    {title: t('app.application.updatedAt', '更新时间'), dataIndex: 'updatedAt',
      key: 'updatedAt', width: 190,
      render: (value: string) => value ? formatDateTime(value, locale) : '—'},
    {title: t('app.application.actions', '操作'), key: 'actions', width: 158,
      render: (_, record) => <TableActions<ApplicationSummary> appearance="row"
        selectedKeys={[record.id]} selectedRows={[record]}
        buttons={[
          {key: 'view', sort: 0, text: t('app.application.view', '查看'),
            argumentMinSize: 1, argumentMaxSize: 1},
          ...(permissions?.canDeleteApplication && !permissionsQuery.error ? [{
            key: 'delete', sort: 1, danger: true, argumentMinSize: 1, argumentMaxSize: 1,
            disabled: !canRemove(record),
          }] : []),
        ]}
        isButtonDisabled={() => locked || error || !operation.selected}
        onButtonEvents={{view: () => openDetail(record.id), delete: () => chooseApplications([record])}}/>},
  ], [locale, openDetail, chooseApplications, canRemove, t, locked, error, operation.selected, permissions?.canDeleteApplication, permissionsQuery.error]);

  const pageColumns: ColumnsType<ApplicationPage> = useMemo(() => [
    {title: t('app.page.name', '页面名称'), dataIndex: 'name', key: 'name'},
    {title: t('app.page.description', '说明'), dataIndex: 'description',
      key: 'description', ellipsis: true, render: (value?: string) => value || '—'},
    {title: t('app.page.kind', '类型'), dataIndex: 'kind', key: 'kind', width: 110,
      render: (kind: ApplicationPage['kind']) => kind === 'MAIN'
        ? t('app.page.main', '主页面') : kind === 'ATTACHED' ? t('app.page.attached', '附属页面') : '—'},
    {title: t('app.page.status', '状态'), dataIndex: 'status',
      key: 'status', width: 105, render: statusTag},
    {title: t('app.application.actions', '操作'), key: 'actions', width: 285,
      render: (_, record) => <TableActions<ApplicationPage> appearance="row" selectedKeys={[record.id]} selectedRows={[record]}
        buttons={[
          ...(permissions?.canDesignForm && !permissionsQuery.error ? [{key: 'design', sort: 0,
            text: t('app.designer.openForms', '设计表单'), icon: 'EditOutlined', type: 'link' as const,
            argumentMinSize: 1, argumentMaxSize: 1,
            disabled: !operation.selected || saving || addPageOpen || !!detailQuery.error}] : []),
          ...(record.status === 'PUBLISHED' && record.resourceRegistered && record.runtimePath ? [{key: 'open', sort: 1,
            text: t('app.page.open', '打开页面'), icon: 'ExportOutlined', type: 'link' as const,
            argumentMinSize: 1, argumentMaxSize: 1,
            disabled: !operation.selected || saving || addPageOpen || !!detailQuery.error}] : []),
          ...(permissions?.canDeletePage && !permissionsQuery.error && record.kind === 'ATTACHED' ? [{
            key: 'delete', sort: 2, danger: true, argumentMinSize: 1, argumentMaxSize: 1,
            disabled: !draftPage(record),
          }] : []),
        ]}
        isButtonDisabled={() => operation.busy || !!deleteTarget || !operation.selected || saving || addPageOpen || !!detailQuery.error}
        onButtonEvents={{
          design: () => {
            if (!permissions?.canDesignForm || permissionsQuery.error || !detail || detailQuery.error
              || !operation.isSelected() || savingRef.current || addPageOpen) return;
            setWorkspace({id: detail.application.id, name: detail.application.name, page: record});
            setDetailId(undefined);
          },
          open: () => {
            if (!operation.isSelected() || savingRef.current || addPageOpen || detailQuery.error || !record.runtimePath) return;
            setDetailId(undefined);
            navigate(record.runtimePath);
          },
          delete: () => {if (detail) chooseDelete({kind: 'page', rows: [record],
            url: endpoint + '/' + encodeURIComponent(detail.application.id) + '/pages'});},
        }}/>
    },
  ], [t, navigate, chooseDelete, operation.selected, operation.isSelected, operation.busy, deleteTarget,
    saving, addPageOpen, permissions, permissionsQuery.error, detail, detailQuery.error]);

  if (workspace) {
    const back = () => {setDetailId(workspace.id); setWorkspace(undefined);};
    return <Suspense fallback={<Spin/>}>{workspace.page
      ? <PageFormDesigner application={workspace} page={workspace.page} onClose={back}/>
      : <ApplicationForms application={workspace} onClose={back}/>}</Suspense>;
  }

  return (
    <section className="app-workbench" style={{height: '100%', minHeight: 0}}>
        {removals.length > 0 && <Alert showIcon type={complete ? 'success' : 'info'}
          title={t('app.application.removalProgress', '已永久删除 {completed}/{total} 个应用',
            {completed: removed, total: removals.length})}/>}
        {permissionsQuery.error && <Alert showIcon type="error" title={t('app.permissions.failed', '操作权限加载失败')}
          action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
            {key: 'retryPermissions', sort: 1, text: t('app.retry', '重试')},
          ]} onButtonEvents={{retryPermissions: () => void permissionsQuery.refetch()}}/>}/>}
        {error && <Alert showIcon type="error" style={{marginBottom: 16}}
          message={t('app.application.loadFailed', '应用列表加载失败')}
          action={<TableActions selectedKeys={[]} selectedRows={[]}
            buttons={[{key: 'retry', sort: 0, text: t('app.retry', '重试')}]}
            onButtonEvents={{retry: table.retry}}/>}/>}
        <DataTable<ApplicationSummary> {...table.tableProps} rowKey="id" storageKey="app.workbench.applications"
          columns={table.columns(columns)} refresh={() => {if (!locked) void load();}}
          rowSelection={{selectedRowKeys: selectedApplications.map(row => row.id), onChange: (_, rows) => setSelectedApplications(rows),
            getCheckboxProps: row => ({disabled: locked || !canRemove(row)})}}
          selectable={permissions?.canDeleteApplication === true && !permissionsQuery.error}
          toolbarExtra={<Space wrap>{permissions?.canCreateApplication && !permissionsQuery.error && <TableActions selectedKeys={[]} selectedRows={[]}
            buttons={[{key: 'add', sort: 0, text: t('app.application.create', '新建应用'),
              icon: 'PlusOutlined', type: 'primary', argumentMinSize: 0, argumentMaxSize: 0, disabled: locked || !!error || !operation.selected}]}
            onButtonEvents={{add: () => {
              if (locked || error || !operation.isSelected()) return;
              setDirty(false); setWriteError('');
              setApplicationValues({mainPageName: t('app.page.defaultMain', '首页')});
              setCreateOpen(true);
            }}}/>}
            {permissions?.canDeleteApplication && !permissionsQuery.error && <TableActions
              selectedKeys={selectedApplications.map(row => row.id)} selectedRows={selectedApplications}
              buttons={[{key: 'delete', icon: 'DeleteOutlined', danger: true, sort: 1, argumentMinSize: 1,
                argumentMaxSize: hasRemoval ? 100 : 500,
                disabled: locked || error || !operation.selected || selectedApplications.some(row => !canRemove(row))}]}
              onButtonEvents={{delete: (_, rows) => chooseApplications(rows)}}/>}
          </Space>}
          pagination={table.tableProps.pagination === false ? false : {...table.tableProps.pagination, disabled: locked}}/>

      <Modal wrapClassName="app-application-create" title={t('app.application.create', '新建应用')} open={createOpen && operation.selected}
        confirmLoading={saving} destroyOnHidden={!createOpen} closable={!saving} maskClosable={!saving} keyboard={!saving}
        onOk={() => appForm.current?.submit()}
        onCancel={() => closeDraft('app')} cancelButtonProps={{disabled: saving}}
        okButtonProps={{disabled: !permissions?.canCreateApplication || !!permissionsQuery.error || error}}
        okText={t('app.create', '创建')}>
        {writeError && <Alert showIcon type="error" title={writeError}/>}
        <SForm ref={appForm} hideSubmit disabled={saving} schema={{type: 'object', required: ['nameLocalization', 'mainPageNameLocalization'], properties: {
          nameLocalization: localizedTextSchema(t('app.application.name', '应用名称'), 100),
          description: {type: 'string', maxLength: 500, title: t('app.application.description', '说明')},
          mainPageNameLocalization: localizedTextSchema(t('app.application.mainPage', '主页面'), 100),
          navigationGroup: {type: 'boolean', default: false, title: t('app.application.navigationGroup', '独立菜单目录')},
        }}} uiSchema={{description: {'ui:widget': 'textarea'}}} formData={{...applicationValues, mainPageNameLocalization: localizedDraft(applicationValues.mainPageName ?? '', applicationValues.mainPageNameLocalization, locale)}}
          onChange={({formData}) => {if (!savingRef.current) {setApplicationValues(formData ?? {}); setDirty(true);}}}
          onSubmit={({formData}) => void createApplication(formData as CreateApplicationValues)}/>
      </Modal>

      <Drawer rootClassName="app-application-details" title={detail?.application.name ?? t('app.application.detail', '应用详情')}
        styles={{body: {display: 'flex', flexDirection: 'column', overflow: 'hidden'}}}
        open={Boolean(detailId) && operation.selected && !navigationOpen} width={720}
        onClose={() => {if (!savingRef.current && !addPageOpen && !deleteTarget) setDetailId(undefined);}}
        loading={detailLoading} closable={!saving && !addPageOpen && !deleteTarget}
        maskClosable={!saving && !addPageOpen && !deleteTarget} keyboard={!saving && !addPageOpen && !deleteTarget}>
        {detailQuery.isError && <Alert type="error" showIcon title={t('app.application.detailFailed', '应用详情加载失败')}
          action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retryDetail', sort: 10,
            text: t('app.retry', '重试'), onClick: () => void detailQuery.refetch()}]}/>}/>}
        {detail && <>
          <Space style={{marginBottom: 12}}>{statusTag(detail.application.status)}
            <Typography.Text type="secondary">{detail.application.description}</Typography.Text>
          </Space>
          <Space style={{width: '100%', justifyContent: 'space-between', marginBottom: 12}}>
            <Typography.Title level={5} style={{margin: 0}}>{t('app.page.list', '页面')}</Typography.Title>
            {permissions?.canManageForms && !permissionsQuery.error && <TableActions selectedKeys={[]} selectedRows={[]}
              buttons={[{key: 'forms', sort: 0, icon: 'FormOutlined', text: t('app.form.manage', '表单管理'),
                disabled: saving || addPageOpen || !!deleteTarget || !!detailQuery.error || !operation.selected}]}
              onButtonEvents={{forms: () => {
                setWorkspace(detail.application); setDetailId(undefined);
              }}}/>}
            {permissions?.canDeletePage && !permissionsQuery.error && <TableActions
              selectedKeys={selectedPages.map(row => row.id)} selectedRows={selectedPages}
              buttons={[{key: 'delete', icon: 'DeleteOutlined', danger: true, sort: 1, argumentMinSize: 1, argumentMaxSize: 500,
                disabled: operation.busy || !!deleteTarget || saving || addPageOpen || !!detailQuery.error
                  || !operation.selected || selectedPages.some(row => !draftPage(row))}]}
              onButtonEvents={{delete: (_, rows) => chooseDelete({kind: 'page', rows,
                url: endpoint + '/' + encodeURIComponent(detail.application.id) + '/pages'})}}/>}
            {permissions?.canDesignForm && !permissionsQuery.error && <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
              {key: 'navigation', sort: 0, icon: 'FolderOutlined', text: t('app.navigation.title', '应用目录与名称'), disabled: saving || addPageOpen || !!deleteTarget || !!detailQuery.error},
            ]} onButtonEvents={{navigation: () => {if (!savingRef.current && !addPageOpen && operation.isSelected()) setNavigationOpen(true);}}}/>}
            {permissions?.canAddPage && !permissionsQuery.error && <TableActions selectedKeys={[]} selectedRows={[]}
              buttons={[{key: 'add', sort: 0, text: t('app.page.create', '添加页面'),
                icon: 'PlusOutlined', argumentMinSize: 0, argumentMaxSize: 0, disabled: saving || addPageOpen || !!deleteTarget || !!detailQuery.error || !operation.selected}]}
              onButtonEvents={{add: () => {
                if (savingRef.current || addPageOpen || !operation.isSelected() || detailQuery.error || !detail.application.id) return;
                setPageTarget({id: detail.application.id, name: detail.application.name}); setPageValues({}); setDirty(false); setWriteError(''); setAddPageOpen(true);
              }}}/>}
          </Space>
          <DataTable<ApplicationPage> rowKey="id" storageKey="app.workbench.pages"
            query={{mode: 'local', scope: 'complete', contextKey: detail.application.id, fields: {
              name: {type: 'string'}, description: {type: 'string'}, kind: {type: 'string', enum: ['MAIN', 'ATTACHED']},
              status: {type: 'string', enum: ['DRAFT', 'PUBLISHED']},
            }}}
            columns={pageColumns} dataSource={detail.pages}
            rowSelection={{selectedRowKeys: selectedPages.map(row => row.id), onChange: (_, rows) => setSelectedPages(rows),
              getCheckboxProps: row => ({disabled: saving || addPageOpen || !!deleteTarget || !draftPage(row)})}}
            selectable={permissions?.canDeletePage === true && !permissionsQuery.error}
            pagination={{defaultPageSize: 50, disabled: saving || addPageOpen || !!deleteTarget}}/>

        </>}
      </Drawer>

      {navigationOpen && detailId && <ApplicationNavigation key={detailId} applicationId={detailId}
        onClose={() => setNavigationOpen(false)} onSaved={() => {void detailQuery.refetch(); void load();}}/>}

      <DeleteConfirmation open={!!deleteTarget && operation.selected} pending={operation.busy} error={deleteError}
        onConfirm={() => void confirmDelete()} onCancel={() => {if (!operation.isBusy()) setDeleteTarget(undefined);}}>
        {deleteTarget?.removal
          ? t('app.application.permanentRemovalConfirmation',
            '永久删除选中的 {count} 个应用及其页面、表单、业务数据和专属数据表？请先下线已发布页面并清理关联审批历史。', {count: deleteTarget.rows.length})
          : deleteTarget && (deleteTarget.rows.length > 1
          ? deleteTarget.kind === 'application'
            ? t('app.application.deleteSelection', '永久删除选中的 {count} 个应用及其草稿页面、表单？', {count: deleteTarget.rows.length})
            : t('table.confirmPermanentDeleteContent', '永久删除选中的 {count} 条记录？', {count: deleteTarget.rows.length})
          : deleteTarget.kind === 'application'
          ? t('app.application.deleteConfirmation', '永久删除应用「{name}」及其草稿页面、表单？', {name: deleteTarget.rows[0].name})
          : t('app.page.deleteConfirmation', '永久删除附属页面「{name}」？', {name: deleteTarget.rows[0].name}))}
      </DeleteConfirmation>

      <Modal wrapClassName="app-page-create" title={t('app.page.create', '添加页面')} open={addPageOpen && operation.selected}
        confirmLoading={saving} destroyOnHidden={!addPageOpen} closable={!saving} maskClosable={!saving} keyboard={!saving}
        onOk={() => pageForm.current?.submit()}
        onCancel={() => closeDraft('page')} cancelButtonProps={{disabled: saving}}
        okButtonProps={{disabled: !permissions?.canAddPage || !!permissionsQuery.error || !!detailQuery.error || !pageTarget}}
        okText={t('app.create', '创建')}>
        <Typography.Paragraph>{pageTarget?.name}</Typography.Paragraph>
        {writeError && <Alert showIcon type="error" title={writeError}/>}
        <SForm ref={pageForm} hideSubmit disabled={saving} schema={{type: 'object', required: ['nameLocalization'], properties: {
          nameLocalization: localizedTextSchema(t('app.page.name', '页面名称'), 100),
          description: {type: 'string', maxLength: 500, title: t('app.page.description', '说明')},
        }}} uiSchema={{description: {'ui:widget': 'textarea'}}} formData={pageValues}
          onChange={({formData}) => {if (!savingRef.current) {setPageValues(formData ?? {}); setDirty(true);}}}
          onSubmit={({formData}) => void addPage(formData as CreatePageValues)}/>
      </Modal>
      <Modal wrapClassName="app-discard-draft" open={!!discard && operation.selected} destroyOnHidden={!discard}
        title={t('app.draft.discardTitle', '放弃未保存的修改？')} onCancel={() => setDiscard(undefined)}
        footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
          {key: 'continue', sort: 1, text: t('app.draft.continue', '继续编辑')},
          {key: 'discard', sort: 2, text: t('app.draft.discard', '放弃修改'), danger: true, type: 'primary'},
        ]} onButtonEvents={{continue: () => setDiscard(undefined), discard: () => {if (discard) closeDraft(discard, true);}}}/>}/>
    </section>
  );
}
