import React, {useCallback, useEffect, useState} from 'react';
import {App as AntdApp, Button, Card, Form, Input, Modal, Select, Space, Switch, Table, Tag, Typography} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import {ApartmentOutlined, DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined} from '@simplepoint/shared/types/icon';
import {get, post, put} from '@simplepoint/shared/api/methods';
import {request} from '@simplepoint/shared/api/client';
import {normalizePage} from '@simplepoint/shared/types/request';
import type {RawPage} from '@simplepoint/shared/types/request';
import {useI18n} from '@/layouts/i18n/useI18n.ts';

type ResourceType = 'GROUP' | 'MODULE' | 'PAGE' | 'FEATURE' | 'ACTION' | 'API';
type TenantResource = {
  id: string;
  platformResourceId?: string | null;
  targetResourceId?: string | null;
  parentId?: string | null;
  code: string;
  name: string;
  type: ResourceType;
  path?: string | null;
  icon?: string | null;
  sort?: number | null;
  description?: string | null;
  enabled: boolean;
  grantable: boolean;
};
type FormValue = Pick<TenantResource, 'name' | 'type' | 'path' | 'parentId' | 'icon' | 'sort' | 'description' | 'targetResourceId' | 'enabled'>;
type ResourcePage = RawPage<TenantResource>;
type SyncPreview = {
  added: number;
  refreshed: number;
  deactivated: number;
  additions: Array<{id: string; code: string; name: string}>;
  deactivations: Array<{id: string; code: string; name: string}>;
};

const endpoint = '/common/tenants/current-resources';

export const TenantResourceManager: React.FC<{tenantId: string}> = ({tenantId}) => {
  const {message, modal} = AntdApp.useApp();
  const {t} = useI18n();
  const [rows, setRows] = useState<TenantResource[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [trail, setTrail] = useState<Array<{id: string; name: string}>>([]);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [editing, setEditing] = useState<TenantResource | null>(null);
  const [editorOpen, setEditorOpen] = useState(false);
  const [candidates, setCandidates] = useState<TenantResource[]>([]);
  const [form] = Form.useForm<FormValue>();
  const type = Form.useWatch('type', form);
  const headers = {'X-Tenant-Id': tenantId};

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const parentId = trail[trail.length - 1]?.id;
      const raw = await get<ResourcePage>(endpoint, {
        page: page - 1, size: 20, keyword: search,
        ...(search ? {} : parentId ? {parentId} : {rootsOnly: true}),
      }, {headers});
      const result = normalizePage(raw, 20);
      setRows(result.content);
      setTotal(result.page.totalElements);
    } catch (error: any) {
      message.error(error?.message ?? t('tenant.resources.loadFailed', '资源加载失败'));
    } finally {
      setLoading(false);
    }
  }, [tenantId, page, search, trail, message, t]);

  useEffect(() => { void load(); }, [load]);

  const loadCandidates = useCallback(async (query = '', selectedIds: string[] = []) => {
    try {
      const raw = await get<ResourcePage>(endpoint, {page: 0, size: 100, keyword: query}, {headers});
      const firstPage = normalizePage(raw, 100).content;
      const extras = await Promise.all(selectedIds.filter(Boolean).map(id =>
        get<TenantResource>(`${endpoint}/${encodeURIComponent(id)}`, undefined, {headers})
          .catch(() => null)));
      const combined = new Map<string, TenantResource>();
      [...firstPage, ...extras.filter((item): item is TenantResource => item !== null)]
        .forEach(item => combined.set(item.id, item));
      setCandidates([...combined.values()]);
    } catch {
      setCandidates([]);
    }
  }, [tenantId]);

  const openEditor = (row: TenantResource | null) => {
    setEditing(row);
    form.setFieldsValue(row ? {
      name: row.name, type: row.type, path: row.path, parentId: row.parentId,
      targetResourceId: row.targetResourceId, icon: row.icon, sort: row.sort,
      description: row.description, enabled: row.enabled,
    } : {name: '', type: 'GROUP', path: undefined, parentId: undefined,
      targetResourceId: undefined, icon: undefined, sort: 0, description: undefined, enabled: true});
    setEditorOpen(true);
    void loadCandidates('', [row?.parentId, row?.targetResourceId].filter((id): id is string => !!id));
  };

  const save = async () => {
    try {
      const values = await form.validateFields();
      setBusy(true);
      if (editing) await put(`${endpoint}/${encodeURIComponent(editing.id)}`, values, {headers});
      else await post(endpoint, values, {headers});
      message.success(t('tenant.resources.saved', '资源已保存'));
      setEditorOpen(false);
      if (!editing) {
        setTrail([]);
        setPage(1);
        setKeyword(values.name);
        setSearch(values.name);
      } else {
        await load();
      }
    } catch (error: any) {
      if (!error?.errorFields) message.error(error?.message ?? t('tenant.resources.saveFailed', '资源保存失败'));
    } finally {
      setBusy(false);
    }
  };

  const sync = async (overwrite: boolean) => {
    let preview: SyncPreview;
    try {
      setBusy(true);
      preview = await get<SyncPreview>(`${endpoint}/sync/preview`, {overwrite}, {headers});
    } catch (error: any) {
      message.error(error?.message ?? t('tenant.resources.syncFailed', '资源同步失败'));
      return;
    } finally {
      setBusy(false);
    }
    modal.confirm({
      title: overwrite ? t('tenant.resources.overwrite', '覆盖平台来源资源')
        : t('tenant.resources.update', '更新平台授权资源'),
      content: <Space direction="vertical" size={12}>
        <Typography.Text>{overwrite
          ? t('tenant.resources.overwriteHint', '平台来源项的本地名称、位置和图标会恢复平台设置；租户自建资源和角色授权会保留。')
          : t('tenant.resources.updateHint', '拉取新增授权，更新平台权限字段，并停用已被平台撤销的来源项；本地展示调整会保留。')}</Typography.Text>
        <ol className="tenant-resource-sync-steps">
          <li>{t('tenant.resources.stepDiscover', '比对平台授权')}：
            +{preview.added} / ↻{preview.refreshed} / −{preview.deactivated}</li>
          <li>{t('tenant.resources.stepMap', '按原平台 ID 自动映射新增资源')}
            {preview.additions.length > 0 && <ul>{preview.additions.slice(0, 8).map(item =>
              <li key={item.id}>{item.name} · {item.code}</li>)}</ul>}</li>
          <li>{t('tenant.resources.stepApply', '确认后更新租户目录及权限快照')}
            {preview.deactivations.length > 0 && <ul>{preview.deactivations.slice(0, 8).map(item =>
              <li key={item.id}>{item.name} · {item.code}</li>)}</ul>}</li>
        </ol>
      </Space>,
      okText: t('action.confirm', '确认'),
      onOk: async () => {
        setBusy(true);
        try {
          const result = await post<{added: number; refreshed: number; deactivated: number}>(
            `${endpoint}/sync?overwrite=${overwrite}`, {}, {headers});
          message.success(t('tenant.resources.syncDone', '同步完成')
            + `：+${result.added} / ↻${result.refreshed} / −${result.deactivated}`);
          await load();
          window.dispatchEvent(new Event('sp-tenant-resources-updated'));
        } catch (error: any) {
          message.error(error?.message ?? t('tenant.resources.syncFailed', '资源同步失败'));
        } finally {
          setBusy(false);
        }
      },
    });
  };

  const remove = (row: TenantResource) => {
    modal.confirm({
      title: t('tenant.resources.delete', '删除租户自建资源'),
      content: `${row.name} (${row.code})`,
      okButtonProps: {danger: true},
      onOk: async () => {
        setBusy(true);
        try {
          await request(`${endpoint}/${encodeURIComponent(row.id)}`, {method: 'DELETE', headers});
          message.success(t('tenant.resources.deleted', '资源已删除'));
          await load();
        } catch (error: any) {
          message.error(error?.message ?? t('tenant.resources.deleteFailed', '删除失败'));
        } finally {
          setBusy(false);
        }
      },
    });
  };

  const columns: ColumnsType<TenantResource> = [
    {title: t('tenant.resources.name', '资源'), key: 'name', render: (_, row) => (
      <Space direction="vertical" size={0}><strong>{row.name}</strong><Typography.Text type="secondary" copyable>{row.code}</Typography.Text></Space>
    )},
    {title: t('tenant.resources.type', '类型'), dataIndex: 'type', width: 110},
    {title: t('tenant.resources.origin', '来源'), width: 110, render: (_, row) => row.platformResourceId
      ? <Tag color="blue">{t('tenant.resources.platform', '平台')}</Tag>
      : <Tag color="green">{t('tenant.resources.tenant', '租户自建')}</Tag>},
    {title: t('tenant.resources.status', '状态'), width: 95, render: (_, row) => row.enabled
      ? <Tag color="success">{t('tenant.resources.active', '生效')}</Tag>
      : <Tag>{t('tenant.resources.inactive', '已停用')}</Tag>},
    {title: t('tenant.resources.actions', '操作'), key: 'actions', width: 250, render: (_, row) => (
      <Space>
        {row.type !== 'ACTION' && row.type !== 'API' && <Button size="small" icon={<ApartmentOutlined/>}
          onClick={() => {setSearch(''); setKeyword(''); setPage(1);
            setTrail(current => [...current, {id: row.id, name: row.name}]);}}>
          {t('tenant.resources.children', '下级')}
        </Button>}
        <Button size="small" icon={<EditOutlined/>} onClick={() => openEditor(row)}>{t('action.edit', '编辑')}</Button>
        {!row.platformResourceId && <Button size="small" danger icon={<DeleteOutlined/>} onClick={() => remove(row)}>
          {t('action.delete', '删除')}
        </Button>}
      </Space>
    )},
  ];

  const parentOptions = candidates.filter(item => item.enabled && item.id !== editing?.id
    && item.type !== 'ACTION' && item.type !== 'API'
    && (!['GROUP', 'MODULE', 'PAGE'].includes(type) || item.type !== 'FEATURE'));
  const pageTargets = candidates.filter(item => item.enabled && item.grantable
    && item.platformResourceId && item.type === 'PAGE');

  return <Card title={t('tenant.resources.title', '租户资源管理')}>
    <Space wrap style={{marginBottom: 16}}>
      <Input.Search value={keyword} onChange={event => setKeyword(event.target.value)}
        onSearch={value => {setPage(1); setTrail([]); setSearch(value.trim());}}
        placeholder={t('tenant.resources.search', '搜索资源名称或编码')} style={{width: 280}}/>
      <Button icon={<ReloadOutlined/>} loading={busy} onClick={() => void sync(false)}>
        {t('tenant.resources.update', '更新平台授权资源')}
      </Button>
      <Button loading={busy} onClick={() => void sync(true)}>{t('tenant.resources.overwrite', '覆盖平台来源资源')}</Button>
      <Button type="primary" icon={<PlusOutlined/>} onClick={() => openEditor(null)}>{t('tenant.resources.create', '新建租户资源')}</Button>
    </Space>
    {!search && <Space wrap className="tenant-resource-breadcrumb">
      <Button type="link" size="small" onClick={() => {setPage(1); setTrail([]);}}>
        {t('tenant.resources.root', '全部根资源')}
      </Button>
      {trail.map((item, index) => <React.Fragment key={item.id}>
        <span>/</span>
        <Button type="link" size="small" onClick={() => {setPage(1); setTrail(current => current.slice(0, index + 1));}}>
          {item.name}
        </Button>
      </React.Fragment>)}
    </Space>}
    <Table<TenantResource> rowKey="id" columns={columns} dataSource={rows} loading={loading}
      scroll={{x: 900}} pagination={{current: page, pageSize: 20, total, showSizeChanger: false,
        onChange: next => setPage(next)}}/>
    <Modal title={editing ? t('tenant.resources.edit', '编辑资源') : t('tenant.resources.create', '新建租户资源')}
      open={editorOpen} onCancel={() => !busy && setEditorOpen(false)} onOk={() => void save()}
      okButtonProps={{loading: busy}} destroyOnHidden>
      <Form<FormValue> form={form} layout="vertical">
        <Form.Item name="name" label={t('tenant.resources.name', '资源名称')} rules={[{required: true}, {max: 100}]}>
          <Input maxLength={100}/>
        </Form.Item>
        <Form.Item name="type" label={t('tenant.resources.type', '类型')} rules={[{required: true}]}>
          <Select disabled={!!editing?.platformResourceId} options={[
            {value: 'GROUP', label: t('tenant.resources.group', '目录')},
            {value: 'MODULE', label: t('tenant.resources.module', '模块')},
            {value: 'PAGE', label: t('tenant.resources.page', '页面')},
            ...(editing?.platformResourceId ? [
              {value: 'FEATURE', label: t('tenant.resources.feature', '资源')},
              {value: 'ACTION', label: t('tenant.resources.action', '操作')},
              {value: 'API', label: t('tenant.resources.api', '接口')},
            ] : []),
          ]}/>
        </Form.Item>
        <Form.Item name="parentId" label={t('tenant.resources.parent', '上级资源')}>
          <Select allowClear showSearch filterOption={false} onSearch={value => void loadCandidates(value)}
            onFocus={() => void loadCandidates('', [form.getFieldValue('parentId')].filter(Boolean))}
            options={parentOptions.map(item => ({value: item.id, label: `${item.name} · ${item.code}`}))}/>
        </Form.Item>
        {type === 'PAGE' && !editing?.platformResourceId && <>
          <Form.Item name="targetResourceId" label={t('tenant.resources.target', '绑定的平台页面')}
            rules={[{required: true}]}>
            <Select showSearch filterOption={false} onSearch={value => void loadCandidates(value)}
              onFocus={() => void loadCandidates('', [form.getFieldValue('targetResourceId')].filter(Boolean))}
              options={pageTargets.map(item => ({value: item.id, label: `${item.name} · ${item.code}`}))}/>
          </Form.Item>
          <Form.Item name="path" label={t('tenant.resources.path', '页面路径')} rules={[{required: true}]}>
            <Input placeholder="/workspace/example" maxLength={200}/>
          </Form.Item>
        </>}
        <Form.Item name="icon" label={t('tenant.resources.icon', '图标')}><Input maxLength={100}/></Form.Item>
        <Form.Item name="sort" label={t('tenant.resources.sort', '排序')}><Input type="number"/></Form.Item>
        <Form.Item name="description" label={t('tenant.resources.description', '说明')}>
          <Input.TextArea rows={2} maxLength={255}/>
        </Form.Item>
        <Form.Item name="enabled" label={t('tenant.resources.enabled', '启用资源')} valuePropName="checked">
          <Switch/>
        </Form.Item>
      </Form>
    </Modal>
  </Card>;
};
