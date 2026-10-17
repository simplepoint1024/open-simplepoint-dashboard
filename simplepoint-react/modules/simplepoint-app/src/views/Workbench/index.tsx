import {formatDateTime} from '@simplepoint/shared/format/temporal';
import DataTable from '@simplepoint/components/DataTable';
import {useRemoteList} from '@simplepoint/components/DataTable/useRemoteList';
import TableActions from '@simplepoint/components/Table/Actions';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App, Drawer, Modal, Space, Spin, Tag, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {lazy, Suspense, useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {useNavigate} from 'react-router';

const PageFormDesigner = lazy(() => import('./forms/PageFormDesigner'));

type ApplicationSummary = {
  id: string;
  name: string;
  description?: string;
  status: 'DRAFT' | 'PUBLISHED';
  mainPageName: string;
  attachedPageCount: number;
  updatedAt: string;
};

type ApplicationPage = {
  id: string;
  name: string;
  description?: string;
  kind: 'MAIN' | 'ATTACHED';
  status: 'DRAFT' | 'PUBLISHED';
  resourceRegistered: boolean;
  runtimePath?: string;
  formId?: string;
};

type ApplicationDetail = {application: ApplicationSummary; pages: ApplicationPage[]};
type Permissions = {canCreateApplication: boolean; canAddPage: boolean; canDesignForm: boolean};
type CreateApplicationValues = {name: string; description?: string; mainPageName: string; navigationGroup?: boolean};
type CreatePageValues = {name: string; description?: string};

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
  const [addPageOpen, setAddPageOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [detailId, setDetailId] = useState<string>();
  const [workspace, setWorkspace] = useState<{id: string; name: string; page: ApplicationPage}>();
  const [pageTarget, setPageTarget] = useState<{id: string; name: string}>();
  const [writeError, setWriteError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [discard, setDiscard] = useState<'app' | 'page'>();
  const locked = operation.busy || createOpen || addPageOpen || !!detailId || !!workspace;
  usePageRetention(!locked, createOpen || addPageOpen || operation.busy);

  useEffect(() => { void ensure(['app-workbench']); }, [ensure, locale]);

  const permissionsQuery = useData(['app', 'permissions'], ({signal}) => get<Permissions>(endpoint + '/permissions', undefined, {signal}),
    {retry: false, staleTime: 30_000, enabled: !workspace});
  const permissions = permissionsQuery.data;
  const table = useRemoteList<ApplicationSummary>(endpoint, operation.selected && !workspace);
  const error = !!table.error;
  const load = table.refresh;
  const detailQuery = useData(['app', 'detail', detailId], ({signal}) => get<ApplicationDetail>(endpoint + '/' + encodeURIComponent(detailId!), undefined, {signal}),
    {enabled: Boolean(detailId), retry: false});
  const detail = detailQuery.data, detailLoading = detailQuery.isFetching;
  const openDetail = useCallback((id: string) => {if (!locked && operation.isSelected() && !error && id) setDetailId(id);},
    [locked, operation.isSelected, error]);

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
      const created = await post<ApplicationDetail>(endpoint, values, {notifyOnError: false, signal: controller.signal});
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
      await post(endpoint + '/' + encodeURIComponent(captured) + '/pages', values, {notifyOnError: false, signal: controller.signal});
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

  const statusTag = (status: ApplicationSummary['status']) => status !== 'DRAFT' && status !== 'PUBLISHED' ? '—' : (
    <Tag color={status === 'PUBLISHED' ? 'green' : 'default'}>
      {status === 'PUBLISHED' ? t('app.status.published', '已发布') : t('app.status.draft', '草稿')}
    </Tag>
  );

  const columns: ColumnsType<ApplicationSummary> = useMemo(() => [
    {title: t('app.application.name', '应用名称'), dataIndex: 'name', key: 'name',
      render: (name: string) => name},
    {title: t('app.application.description', '说明'), dataIndex: 'description',
      key: 'description', ellipsis: true, render: (value?: string) => value || '—'},
    {title: t('app.application.mainPage', '主页面'), dataIndex: 'mainPageName',
      key: 'mainPageName', width: 180},
    {title: t('app.application.attachedPages', '附属页面'), dataIndex: 'attachedPageCount',
      key: 'attachedPageCount', width: 120},
    {title: t('app.application.status', '状态'), dataIndex: 'status',
      key: 'status', width: 110, render: statusTag},
    {title: t('app.application.updatedAt', '更新时间'), dataIndex: 'updatedAt',
      key: 'updatedAt', width: 190,
      render: (value: string) => value ? formatDateTime(value, locale) : '—'},
    {title: t('app.application.actions', '操作'), key: 'actions', width: 100,
      render: (_, record) => <TableActions<ApplicationSummary>
        selectedKeys={[record.id]} selectedRows={[record]}
        buttons={[{key: 'view', sort: 0, text: t('app.application.view', '查看'),
          type: 'link', argumentMinSize: 1, argumentMaxSize: 1, disabled: locked || !!error || !operation.selected}]}
        onButtonEvents={{view: () => {void openDetail(record.id);}}}/>},
  ], [locale, openDetail, t, locked, error, operation.selected]);

  const pageColumns: ColumnsType<ApplicationPage> = useMemo(() => [
    {title: t('app.page.name', '页面名称'), dataIndex: 'name', key: 'name'},
    {title: t('app.page.description', '说明'), dataIndex: 'description',
      key: 'description', ellipsis: true, render: (value?: string) => value || '—'},
    {title: t('app.page.kind', '类型'), dataIndex: 'kind', key: 'kind', width: 110,
      render: (kind: ApplicationPage['kind']) => kind === 'MAIN'
        ? t('app.page.main', '主页面') : kind === 'ATTACHED' ? t('app.page.attached', '附属页面') : '—'},
    {title: t('app.page.status', '状态'), dataIndex: 'status',
      key: 'status', width: 105, render: statusTag},
    {title: t('app.application.actions', '操作'), key: 'actions', width: 215,
      render: (_, record) => <TableActions<ApplicationPage> selectedKeys={[record.id]} selectedRows={[record]}
        buttons={[
          ...(permissions?.canDesignForm && !permissionsQuery.error ? [{key: 'design', sort: 0,
            text: t('app.designer.openForms', '设计表单'), icon: 'EditOutlined', type: 'link' as const,
            argumentMinSize: 1, argumentMaxSize: 1,
            disabled: !operation.selected || saving || addPageOpen || !!detailQuery.error}] : []),
          ...(record.resourceRegistered && record.runtimePath ? [{key: 'open', sort: 1,
            text: t('app.page.open', '打开页面'), icon: 'ExportOutlined', type: 'link' as const,
            argumentMinSize: 1, argumentMaxSize: 1,
            disabled: !operation.selected || saving || addPageOpen || !!detailQuery.error}] : []),
        ]}
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
        }}/>
    },
  ], [t, navigate, operation.selected, operation.isSelected, saving, addPageOpen, permissions, permissionsQuery.error, detail, detailQuery.error]);

  if (workspace) return <Suspense fallback={<Spin/>}><PageFormDesigner application={workspace} page={workspace.page}
    onClose={() => {setDetailId(workspace.id); setWorkspace(undefined);}}/></Suspense>;

  return (
    <section className="app-workbench" style={{height: '100%', minHeight: 0}}>
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
          toolbarExtra={<Space wrap>{permissions?.canCreateApplication && !permissionsQuery.error && <TableActions selectedKeys={[]} selectedRows={[]}
            buttons={[{key: 'add', sort: 0, text: t('app.application.create', '新建应用'),
              icon: 'PlusOutlined', type: 'primary', argumentMinSize: 0, argumentMaxSize: 0, disabled: locked || !!error || !operation.selected}]}
            onButtonEvents={{add: () => {
              if (locked || error || !operation.isSelected()) return;
              setDirty(false); setWriteError('');
              setApplicationValues({mainPageName: t('app.page.defaultMain', '首页')});
              setCreateOpen(true);
            }}}/>}</Space>}
          pagination={table.tableProps.pagination === false ? false : {...table.tableProps.pagination, disabled: locked}}/>

      <Modal wrapClassName="app-application-create" title={t('app.application.create', '新建应用')} open={createOpen && operation.selected}
        confirmLoading={saving} destroyOnHidden={!createOpen} closable={!saving} maskClosable={!saving} keyboard={!saving}
        onOk={() => appForm.current?.submit()}
        onCancel={() => closeDraft('app')} cancelButtonProps={{disabled: saving}}
        okButtonProps={{disabled: !permissions?.canCreateApplication || !!permissionsQuery.error || error}}
        okText={t('app.create', '创建')}>
        {writeError && <Alert showIcon type="error" title={writeError}/>}
        <SForm ref={appForm} hideSubmit disabled={saving} schema={{type: 'object', required: ['name', 'mainPageName'], properties: {
          name: {type: 'string', pattern: '\\S', maxLength: 120, title: t('app.application.name', '应用名称')},
          description: {type: 'string', maxLength: 500, title: t('app.application.description', '说明')},
          mainPageName: {type: 'string', pattern: '\\S', maxLength: 120, title: t('app.application.mainPage', '主页面')},
          navigationGroup: {type: 'boolean', default: false, title: t('app.application.navigationGroup', '独立菜单目录')},
        }}} uiSchema={{description: {'ui:widget': 'textarea'}}} formData={applicationValues}
          onChange={({formData}) => {if (!savingRef.current) {setApplicationValues(formData ?? {}); setDirty(true);}}}
          onSubmit={({formData}) => void createApplication(formData as CreateApplicationValues)}/>
      </Modal>

      <Drawer rootClassName="app-application-details" title={detail?.application.name ?? t('app.application.detail', '应用详情')}
        open={Boolean(detailId) && operation.selected} width={720} onClose={() => {if (!savingRef.current && !addPageOpen) setDetailId(undefined);}}
        loading={detailLoading} closable={!saving && !addPageOpen} maskClosable={!saving && !addPageOpen} keyboard={!saving && !addPageOpen}>
        {detailQuery.isError && <Alert type="error" showIcon title={t('app.application.detailFailed', '应用详情加载失败')}
          action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retryDetail', sort: 10,
            text: t('app.retry', '重试'), onClick: () => void detailQuery.refetch()}]}/>}/>}
        {detail && <>
          <Space style={{marginBottom: 12}}>{statusTag(detail.application.status)}
            <Typography.Text type="secondary">{detail.application.description}</Typography.Text>
          </Space>
          <Space style={{width: '100%', justifyContent: 'space-between', marginBottom: 12}}>
            <Typography.Title level={5} style={{margin: 0}}>{t('app.page.list', '页面')}</Typography.Title>
            {permissions?.canAddPage && !permissionsQuery.error && <TableActions selectedKeys={[]} selectedRows={[]}
              buttons={[{key: 'add', sort: 0, text: t('app.page.create', '添加页面'),
                icon: 'PlusOutlined', argumentMinSize: 0, argumentMaxSize: 0, disabled: saving || addPageOpen || !!detailQuery.error || !operation.selected}]}
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
            columns={pageColumns} dataSource={detail.pages} pagination={{pageSize: 20, disabled: saving || addPageOpen}}/>

        </>}
      </Drawer>

      <Modal wrapClassName="app-page-create" title={t('app.page.create', '添加页面')} open={addPageOpen && operation.selected}
        confirmLoading={saving} destroyOnHidden={!addPageOpen} closable={!saving} maskClosable={!saving} keyboard={!saving}
        onOk={() => pageForm.current?.submit()}
        onCancel={() => closeDraft('page')} cancelButtonProps={{disabled: saving}}
        okButtonProps={{disabled: !permissions?.canAddPage || !!permissionsQuery.error || !!detailQuery.error || !pageTarget}}
        okText={t('app.create', '创建')}>
        <Typography.Paragraph>{pageTarget?.name}</Typography.Paragraph>
        {writeError && <Alert showIcon type="error" title={writeError}/>}
        <SForm ref={pageForm} hideSubmit disabled={saving} schema={{type: 'object', required: ['name'], properties: {
          name: {type: 'string', pattern: '\\S', maxLength: 120, title: t('app.page.name', '页面名称')},
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
