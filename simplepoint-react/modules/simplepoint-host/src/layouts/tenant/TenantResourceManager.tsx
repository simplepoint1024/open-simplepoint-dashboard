import React, {useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {Alert, App, Breadcrumb, Input, Modal, Space, Tag, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import DataTable from '@simplepoint/components/DataTable';
import TableActions from '@simplepoint/components/Table/Actions';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {get, post, put, useData} from '@simplepoint/shared/api/methods';
import {request, resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import {useI18n} from '@/layouts/i18n/useI18n';
import TenantResourceSelect from './TenantResourceSelect';
import {resourceDraft, resourceFormSchema, resourcePayload, type ResourceDraft, type TenantResource} from './resourceForms';

type SyncPreview = {added: number; refreshed: number; deactivated: number;
  additions: Array<{id: string; code: string; name: string}>; deactivations: Array<{id: string; code: string; name: string}>};
type Confirmation = {kind: 'sync'; overwrite: boolean; preview: SyncPreview} | {kind: 'delete'; row: TenantResource};
const endpoint = '/common/tenants/current-resources';
const resourceWidgets = {TenantResource: TenantResourceSelect};

type ManagerProps = {tenantId: string; onBlockedChange?: (blocked: boolean) => void};
export const TenantResourceManager: React.FC<ManagerProps> = props => {
  const scope = useQueryScope();
  // A retained page must not read a previous workspace through the current workspace's context.
  if (scope[0] !== props.tenantId) return null;
  return <ResourceManagerSession key={JSON.stringify(scope)} {...props}/>;
};
function ResourceManagerSession({tenantId, onBlockedChange}: ManagerProps) {
  const {message} = App.useApp(), {t} = useI18n(), selected = usePageSelected();
  const [page, setPage] = useState(1), [keyword, setKeyword] = useState(''), [search, setSearch] = useState('');
  const [trail, setTrail] = useState<Array<{id: string; name: string}>>([]), [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false), flight = useRef(false);
  const [editor, setEditor] = useState<{row: TenantResource | null; draft: ResourceDraft} | null>(null);
  const editorRef = useRef<typeof editor>(null), form = useRef<SFormHandle>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null), confirmationRef = useRef<Confirmation | null>(null);
  const [writeError, setWriteError] = useState<string>();
  const alive = useRef(true);
  useEffect(() => {alive.current = true; return () => {alive.current = false;};}, []);
  const retentionBlocked = !!editor || !!confirmation || busy;
  usePageRetention(!retentionBlocked, retentionBlocked);
  useLayoutEffect(() => {onBlockedChange?.(retentionBlocked);
    return () => onBlockedChange?.(false);}, [onBlockedChange, retentionBlocked]);
  const headers = {'X-Tenant-Id': tenantId}, parentId = trail[trail.length - 1]?.id;
  const list = useData(['tenant-resources', tenantId, page, search, parentId, revision], async ({signal}) =>
    normalizePage(await get<RawPage<TenantResource>>(endpoint, {page: page - 1, size: 20, keyword: search,
      ...(search ? {} : parentId ? {parentId} : {rootsOnly: true})}, {headers, signal}), 20), {retry: false});
  const refresh = () => setRevision(value => value + 1);
  const blocked = busy || !!editor || !!confirmation;
  const canOpen = () => !flight.current && !editorRef.current && !confirmationRef.current;
  const start = () => {if (flight.current) return false; flight.current = true; setBusy(true); setWriteError(undefined); return true;};
  const finish = () => {flight.current = false; if (alive.current) setBusy(false);};
  const openEditor = (row: TenantResource | null) => {
    if (!canOpen()) return; const next = {row, draft: resourceDraft(row)};
    editorRef.current = next; setEditor(next); setWriteError(undefined);
  };
  const closeEditor = () => {if (flight.current) return; editorRef.current = null; setEditor(null); setWriteError(undefined);};
  const closeConfirmation = () => {if (flight.current) return; confirmationRef.current = null; setConfirmation(null); setWriteError(undefined);};
  const save = async (values: ResourceDraft) => {
    const target = editorRef.current; if (!target || !start()) return;
    try {
      const payload = resourcePayload(values);
      if (target.row) await put(`${endpoint}/${encodeURIComponent(target.row.id)}`, payload, {headers});
      else await post(endpoint, payload, {headers});
      if (!alive.current) return;
      message.success(t('tenant.resources.saved', '资源已保存')); editorRef.current = null; setEditor(null);
      if (!target.row) {setTrail([]); setPage(1); setKeyword(payload.name); setSearch(payload.name);} refresh();
      window.dispatchEvent(new Event('sp-tenant-resources-updated'));
    } catch (error) {if (alive.current) setWriteError(resolveApiErrorMessage(error, t('tenant.resources.saveFailed', '资源保存失败')));}
    finally {finish();}
  };
  const previewSync = async (overwrite: boolean) => {
    if (!canOpen() || !start()) return;
    try {
      const preview = await get<SyncPreview>(`${endpoint}/sync/preview`, {overwrite}, {headers});
      if (!alive.current) return;
      const next = {kind: 'sync' as const, overwrite, preview}; confirmationRef.current = next; setConfirmation(next);
    } catch (error) {if (alive.current) message.error(resolveApiErrorMessage(error, t('tenant.resources.syncFailed', '资源同步失败')));}
    finally {finish();}
  };
  const remove = (row: TenantResource) => {
    if (!canOpen()) return; const next = {kind: 'delete' as const, row};
    confirmationRef.current = next; setConfirmation(next); setWriteError(undefined);
  };
  const confirm = async () => {
    const target = confirmationRef.current; if (!target || !start()) return;
    try {
      if (target.kind === 'delete') {
        await request(`${endpoint}/${encodeURIComponent(target.row.id)}`, {method: 'DELETE', headers});
        if (!alive.current) return;
        message.success(t('tenant.resources.deleted', '资源已删除'));
      } else {
        const result = await post<{added: number; refreshed: number; deactivated: number}>(`${endpoint}/sync?overwrite=${target.overwrite}`, {}, {headers});
        if (!alive.current) return;
        message.success(t('tenant.resources.syncDone', '同步完成') + `：+${result.added} / ↻${result.refreshed} / −${result.deactivated}`);
      }
      confirmationRef.current = null; setConfirmation(null); refresh(); window.dispatchEvent(new Event('sp-tenant-resources-updated'));
    } catch (error) {if (alive.current) setWriteError(resolveApiErrorMessage(error, target.kind === 'delete'
      ? t('tenant.resources.deleteFailed', '删除失败') : t('tenant.resources.syncFailed', '资源同步失败')));}
    finally {finish();}
  };
  const columns: ColumnsType<TenantResource> = [
    {title: t('tenant.resources.name', '资源'), key: 'name', render: (_, row) => <Space direction="vertical" size={0}>
      <strong>{row.name}</strong><Typography.Text type="secondary" copyable>{row.code}</Typography.Text></Space>},
    {title: t('tenant.resources.type', '类型'), dataIndex: 'type', width: 110},
    {title: t('tenant.resources.origin', '来源'), width: 110, render: (_, row) => row.platformResourceId
      ? <Tag color="blue">{t('tenant.resources.platform', '平台')}</Tag> : <Tag color="green">{t('tenant.resources.tenant', '租户自建')}</Tag>},
    {title: t('tenant.resources.status', '状态'), width: 95, render: (_, row) => row.enabled
      ? <Tag color="success">{t('tenant.resources.active', '生效')}</Tag> : <Tag>{t('tenant.resources.inactive', '已停用')}</Tag>},
    {title: t('tenant.resources.actions', '操作'), key: 'actions', width: 250, render: (_, row) =>
      <TableActions<TenantResource> selectedKeys={[row.id]} selectedRows={[row]} buttons={[
        ...(row.type !== 'ACTION' && row.type !== 'API' ? [{key: 'children', sort: 0, size: 'small' as const, icon: 'ApartmentOutlined',
          text: t('tenant.resources.children', '下级'), disabled: blocked, onClick: () => {
            if (!canOpen()) return; setSearch(''); setKeyword(''); setPage(1); setTrail(current => [...current, {id: row.id, name: row.name}]);
          }}] : []),
        {key: 'edit', sort: 1, size: 'small', icon: 'EditOutlined', text: t('action.edit', '编辑'), disabled: blocked, onClick: () => openEditor(row)},
        ...(!row.platformResourceId ? [{key: 'delete', sort: 2, size: 'small' as const, danger: true, icon: 'DeleteOutlined',
          text: t('action.delete', '删除'), disabled: blocked, onClick: () => remove(row)}] : []),
      ]}/>},
  ];
  const schema = useMemo(() => resourceFormSchema(t, editor?.row ?? null, editor?.draft.type), [t, editor?.row, editor?.draft.type]);
  const localPage = editor?.draft.type === 'PAGE' && !editor.row?.platformResourceId;
  return <div className="tenant-resource-manager">
    <Space wrap>
      <Input.Search value={keyword} disabled={blocked} onChange={event => setKeyword(event.target.value)}
        onSearch={value => {if (!canOpen()) return; setPage(1); setTrail([]); setSearch(value.trim());}}
        placeholder={t('tenant.resources.search', '搜索资源名称或编码')} style={{width: 280}}/>
      <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'sync', sort: 0, icon: 'ReloadOutlined', disabled: blocked, loading: busy,
          text: t('tenant.resources.update', '更新平台授权资源'), onClick: () => void previewSync(false)},
        {key: 'overwrite', sort: 1, disabled: blocked, text: t('tenant.resources.overwrite', '覆盖平台来源资源'), onClick: () => void previewSync(true)},
        {key: 'add', sort: 2, type: 'primary', icon: 'PlusOutlined', disabled: blocked,
          text: t('tenant.resources.create', '新建租户资源'), onClick: () => openEditor(null)},
      ]}/>
    </Space>
    {!search && <Breadcrumb className="tenant-resource-breadcrumb" items={[
      {title: <a href="#" onClick={event => {event.preventDefault(); if (canOpen()) {setPage(1); setTrail([]);}}}>
        {t('tenant.resources.root', '全部根资源')}</a>},
      ...trail.map((item, index) => ({key: item.id, title: <a href="#" onClick={event => {event.preventDefault();
        if (canOpen()) {setPage(1); setTrail(current => current.slice(0, index + 1));}}}>{item.name}</a>})),
    ]}/>}
    {list.error && <Alert type="error" showIcon title={t('tenant.resources.loadFailed', '资源加载失败')}
      description={resolveApiErrorMessage(list.error, t('tenant.resources.loadFailed', '资源加载失败'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0,
        text: t('action.retry', '重试'), onClick: () => void list.refetch()}]}/>}/>}
    <DataTable<TenantResource> rowKey="id" storageKey="tenant-resource-manager" columns={columns}
      dataSource={list.data?.content ?? []} loading={list.isFetching} refresh={() => {if (canOpen()) refresh();}}
      scroll={{x: 900}} pagination={{current: page, pageSize: 20, total: list.data?.page.totalElements ?? 0,
        showSizeChanger: false, disabled: blocked, onChange: next => {if (canOpen()) setPage(next);}}}/>
    <Modal title={editor?.row ? t('tenant.resources.edit', '编辑资源') : t('tenant.resources.create', '新建租户资源')}
      wrapClassName="tenant-resource-editor" open={!!editor && selected} onCancel={closeEditor} onOk={() => {if (!flight.current) form.current?.submit();}}
      okButtonProps={{loading: busy, disabled: busy}} closable={!busy} maskClosable={!busy} keyboard={!busy} destroyOnHidden>
      {writeError && <Alert type="error" showIcon title={writeError}/>}
      {editor && <SForm ref={form} hideSubmit schema={schema} widgets={resourceWidgets} disabled={busy} formData={editor.draft}
        uiSchema={{parentId: {'ui:widget': 'TenantResource', 'ui:options': {tenantId, kind: 'PARENT',
          childType: editor.draft.type, excludedId: editor.row?.id}},
          targetResourceId: localPage ? {'ui:widget': 'TenantResource', 'ui:options': {tenantId, kind: 'PAGE_TARGET', childType: editor.draft.type}}
            : {'ui:widget': 'hidden'}, path: localPage ? {} : {'ui:widget': 'hidden'},
          description: {'ui:widget': 'textarea'},
        }} onChange={event => {const next = {...editor, draft: event.formData ?? {}}; editorRef.current = next; setEditor(next);}}
        onSubmit={event => void save(event.formData ?? {})}/>}
    </Modal>
    <Modal wrapClassName="tenant-resource-confirmation" open={!!confirmation && selected} onCancel={closeConfirmation} onOk={() => void confirm()}
      okButtonProps={{loading: busy, disabled: busy, danger: confirmation?.kind === 'delete'}} closable={!busy} maskClosable={!busy} keyboard={!busy}
      destroyOnHidden title={confirmation?.kind === 'delete' ? t('tenant.resources.delete', '删除租户自建资源')
        : confirmation?.overwrite ? t('tenant.resources.overwrite', '覆盖平台来源资源') : t('tenant.resources.update', '更新平台授权资源')}>
      {writeError && <Alert type="error" showIcon title={writeError}/>}
      {confirmation?.kind === 'delete' ? `${confirmation.row.name} (${confirmation.row.code})` : confirmation && <Space direction="vertical">
        <Typography.Text>{confirmation.overwrite
          ? t('tenant.resources.overwriteHint', '平台来源项的本地名称、位置和图标会恢复平台设置；租户自建资源和角色授权会保留。')
          : t('tenant.resources.updateHint', '拉取新增授权，更新平台权限字段，并停用已被平台撤销的来源项；本地展示调整会保留。')}</Typography.Text>
        <ol className="tenant-resource-sync-steps"><li>{t('tenant.resources.stepDiscover', '比对平台授权')}：
          +{confirmation.preview.added} / ↻{confirmation.preview.refreshed} / −{confirmation.preview.deactivated}</li>
          <li>{t('tenant.resources.stepMap', '按原平台 ID 自动映射新增资源')}
            {confirmation.preview.additions.length > 0 && <ul>{confirmation.preview.additions.slice(0, 8).map(item =>
              <li key={item.id}>{item.name} · {item.code}</li>)}</ul>}</li>
          <li>{t('tenant.resources.stepApply', '确认后更新租户目录及权限快照')}
            {confirmation.preview.deactivations.length > 0 && <ul>{confirmation.preview.deactivations.slice(0, 8).map(item =>
              <li key={item.id}>{item.name} · {item.code}</li>)}</ul>}</li></ol>
      </Space>}
    </Modal>
  </div>;
}
