import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {get, post, useData} from '@simplepoint/shared/api/methods';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {Alert, App, Drawer, Input, Modal, Space, Tag, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';

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
};

type ApplicationDetail = {application: ApplicationSummary; pages: ApplicationPage[]};
type Permissions = {canCreateApplication: boolean; canAddPage: boolean};
type CreateApplicationValues = {name: string; description?: string; mainPageName: string};
type CreatePageValues = {name: string; description?: string};

const endpoint = '/app/applications';

export default function Workbench() {
  const scope = useQueryScope();
  return <WorkbenchSession key={JSON.stringify(scope)}/>;
}

function WorkbenchSession() {
  const {t, ensure, locale} = useI18n();
  const {message} = App.useApp();
  const operation = useOperationScope();
  const appForm = useRef<SFormHandle>(null);
  const pageForm = useRef<SFormHandle>(null);
  const [applicationValues, setApplicationValues] = useState<Partial<CreateApplicationValues>>({});
  const [pageValues, setPageValues] = useState<Partial<CreatePageValues>>({});
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [addPageOpen, setAddPageOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [detailId, setDetailId] = useState<string>();
  const [pageTarget, setPageTarget] = useState<{id: string; name: string}>();
  const [writeError, setWriteError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [discard, setDiscard] = useState<'app' | 'page'>();
  const locked = operation.busy || createOpen || addPageOpen || !!detailId;
  usePageRetention(!locked, createOpen || addPageOpen || operation.busy);

  useEffect(() => { void ensure(['app-workbench']); }, [ensure, locale]);

  const permissionsQuery = useData(['app', 'permissions'], ({signal}) => get<Permissions>(endpoint + '/permissions', undefined, {signal}),
    {retry: false, staleTime: 30_000});
  const permissions = permissionsQuery.data;
  const listQuery = useData(['app', 'applications', search, page, size], async ({signal}) =>
    normalizePage(await get<RawPage<ApplicationSummary>>(endpoint, {keyword: search, page, size}, {signal})), {retry: false});
  const items = listQuery.data?.content ?? [], total = listQuery.data?.page.totalElements ?? 0;
  const error = listQuery.isError, loading = listQuery.isFetching;
  const load = useCallback(async () => {await listQuery.refetch();}, [listQuery.refetch]);
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
      if (page !== 0) setPage(0);
      else await load();
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
      render: (value: string) => value ? new Date(value).toLocaleString(locale) : '—'},
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
    {title: t('app.page.entry', '打开'), key: 'entry', width: 120,
      render: (_, record) => record.resourceRegistered && record.runtimePath
        ? <a href={record.runtimePath}>{t('app.page.open', '打开页面')}</a>
        : <Typography.Text type="secondary">{t('app.page.unavailable', '发布后可用')}</Typography.Text>},
  ], [t]);

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
            onButtonEvents={{retry: () => {void load();}}}/>}/>}
        <DataTable<ApplicationSummary> rowKey="id" storageKey="app.workbench.applications"
          columns={columns} dataSource={items} loading={loading} refresh={() => {if (!locked) void load();}}
          toolbarExtra={<Space wrap>{permissions?.canCreateApplication && !permissionsQuery.error && <TableActions selectedKeys={[]} selectedRows={[]}
            buttons={[{key: 'add', sort: 0, text: t('app.application.create', '新建应用'),
              icon: 'PlusOutlined', type: 'primary', argumentMinSize: 0, argumentMaxSize: 0, disabled: locked || !!error || !operation.selected}]}
            onButtonEvents={{add: () => {
              if (locked || error || !operation.isSelected()) return;
              setDirty(false); setWriteError('');
              setApplicationValues({mainPageName: t('app.page.defaultMain', '首页')});
              setCreateOpen(true);
            }}}/>}<Input.Search disabled={locked} value={keyword} onChange={event => {if (!locked) setKeyword(event.target.value);}}
              onSearch={value => {if (!locked) {setPage(0); setSearch(value.trim());}}}
              placeholder={t('app.application.search', '搜索应用名称或说明')}
              allowClear maxLength={100} style={{width: 250}}/></Space>}
          pagination={{current: page + 1, pageSize: size, total, showSizeChanger: true, disabled: locked,
            onChange: (nextPage, nextSize) => {if (!locked) {setPage(nextPage - 1); setSize(nextSize);}}}}/>

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
            columns={pageColumns} dataSource={detail.pages} pagination={{pageSize: 20, disabled: saving || addPageOpen}}/>
          <Typography.Paragraph type="secondary" style={{marginTop: 16}}>
            {t('app.page.draftHint', '主页面与附属页面先保存为草稿；表单设计、发布和资源授权将在下一阶段接入。')}
          </Typography.Paragraph>
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
