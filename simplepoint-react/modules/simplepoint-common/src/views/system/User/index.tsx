import {useEffect, useMemo, useState, useSyncExternalStore} from 'react';
import {Alert, App, Button, Drawer, Form, Input, Modal, Select, Switch, Tag} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import type {TableButtonProps} from '@simplepoint/components/Table';
import DataTable, {type QuerySchema, type TableQueryState} from '@simplepoint/components/DataTable';
import {transportFilters} from '@simplepoint/components/Table/query';
import {resolveSimpleTableLoadError} from '@simplepoint/components/SimpleTable/errorMessage';
import OrganizationSelect from '@simplepoint/components/OrganizationSelect';
import {del, get, post, put, useData} from '@simplepoint/shared/api/methods';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {EMPTY_QUERY_SCOPE, getQueryScopeSnapshot, subscribeQueryScope} from '@simplepoint/shared/api/queryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {contextPath} from '@/services';
import RoleConfig from './config/role';

interface Member {
  userId: string;
  name: string;
  email: string;
  orgId?: string;
  orgName?: string;
  enabled: boolean;
  revision?: number;
  userType?: string;
}
interface MemberCommand {email?: string; orgId?: string | null; enabled?: boolean; revision?: number; userType?: string}
const base = `${contextPath}/tenant-members`;
const memberActions: TableButtonProps[] = [
  {key: 'add', sort: 1, icon: 'PlusOutlined', text: 'i18n:platform-accounts.addMember', type: 'primary', argumentMaxSize: -1},
  {key: 'edit', sort: 2, icon: 'EditOutlined', text: 'i18n:platform-accounts.editMember', argumentMinSize: 1, argumentMaxSize: 1},
  {key: 'configRole', sort: 3, icon: 'TeamOutlined', text: 'i18n:users.button.config.role', argumentMinSize: 1, argumentMaxSize: 1},
  {key: 'remove', sort: 4, icon: 'DeleteOutlined', text: 'i18n:platform-accounts.removeMember', danger: true,
    argumentMinSize: 1, argumentMaxSize: 1},
];

export default function TenantMembers() {
  const scope = useSyncExternalStore(subscribeQueryScope, getQueryScopeSnapshot, () => EMPTY_QUERY_SCOPE);
  return <TenantMembersContent key={scope} scope={scope} />;
}

function TenantMembersContent({scope}: {scope: string}) {
  const {t, ensure} = useI18n();
  const {message, modal} = App.useApp();
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [mode, setMode] = useState<'add' | 'edit'>();
  const [selected, setSelected] = useState<Member>();
  const [roleUser, setRoleUser] = useState<string>();
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<MemberCommand>();
  const [tableQuery, setTableQuery] = useState<TableQueryState>({filters: {}});
  const schema = useSchema(base);
  const query = useData(
    ['tenantMembers', scope, page, pageSize, tableQuery],
    async ({signal}) => normalizePage(await get<RawPage<Member>>(base,
      {...transportFilters(tableQuery.filters), page, size: pageSize, ...(tableQuery.sort ? {sort: tableQuery.sort} : {})}, {signal})),
    {retry: false},
  );
  const capabilities = useData(['tenantMemberCapabilities'], () => get<{manage: boolean}>(`${base}/capabilities`));
  const userTypeDictionary = useData(['tenantMemberUserTypes'], () => get<Array<{value: string; label: string}>>(
    '/common/platform/dictionaries/options', {dictionaryCode: 'tenant.member.user-type'}));
  const userTypeOptions = useMemo(() => (userTypeDictionary.data ?? []).map(item => ({value: item.value,
    label: item.label.startsWith('i18n:') ? t(item.label.slice(5), item.value) : item.label})), [t, userTypeDictionary.data]);
  const selectedMember = query.data?.content.find(member => selectedKeys.includes(member.userId));
  const canManage = capabilities.data?.manage ?? false;
  useEffect(() => { void ensure(['platform-accounts', 'tenant-members', 'users', 'roles', 'common']); }, [ensure]);
  const open = (next: typeof mode, member?: Member) => {
    form.resetFields(); setMode(next); setSelected(member);
    form.setFieldsValue(member ? {orgId: member.orgId, enabled: member.enabled, userType: member.userType}
      : {userType: 'INTERNAL'});
  };
  const save = async () => {
    if (scope !== getQueryScopeSnapshot()) return;
    let values: MemberCommand;
    try { values = await form.validateFields(); } catch { return; }
    if (scope !== getQueryScopeSnapshot()) return;
    setSaving(true);
    try {
      if (mode === 'add') await post(base, values);
      else if (selected) await put(`${base}/${encodeURIComponent(selected.userId)}`, {...values, orgId: values.orgId ?? null, revision: selected.revision});
      setMode(undefined); setSelectedKeys([]); form.resetFields(); await query.refetch();
      message.success(t('platform-accounts.memberSaved', '成员配置已保存'));
    } catch { message.error(t('platform-accounts.memberFailed', '操作失败，请确认租户管理权限、账号和成员版本')); }
    finally { setSaving(false); }
  };
  const remove = async (member: Member) => {
    const confirmed = await modal.confirm({
      title: t('platform-accounts.removeMember', '移除租户成员'),
      content: t('platform-accounts.removeBoundary', '只移除当前租户成员关系和租户角色，不删除全局账号或影响其他租户。'),
    });
    if (!confirmed || scope !== getQueryScopeSnapshot()) return;
    try { await del(`${base}/${encodeURIComponent(member.userId)}`, []); setSelectedKeys([]); await query.refetch(); }
    catch { message.error(t('platform-accounts.memberFailed', '操作失败，请确认租户管理权限、账号和成员版本')); }
  };
  const columns = useMemo(() => [
    {key: 'name', title: t('platform-accounts.name', '姓名'), dataIndex: 'name', width: 180},
    {key: 'email', title: t('platform-accounts.email', '邮箱'), dataIndex: 'email', width: 260},
    {
      key: 'orgName',
      dataIndex: 'orgName',
      title: t('platform-accounts.memberOrg', '租户内组织'),
      width: 260,
      render: (_: unknown, row: Member) => row.orgName ?? row.orgId ?? '-',
    },
    {
      key: 'userType',
      title: t('tenant-members.userType', '用户类型'),
      dataIndex: 'userType',
      width: 130,
      render: (value: string) => userTypeOptions.find(option => option.value === value)?.label ?? value ?? '-',
    },
    {
      key: 'enabled',
      title: t('platform-accounts.memberEnabled', '成员启用'),
      width: 120,
      render: (_: unknown, row: Member) => row.enabled === true
        ? <Tag color="success">{t('common.enabled', '启用')}</Tag>
        : row.enabled === false ? <Tag>{t('common.disabled', '停用')}</Tag> : String(row.enabled ?? '-'),
    },
  ], [t, userTypeOptions]);

  return <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column', gap: 16}}>
    <Alert type="info" showIcon title={t('platform-accounts.memberBoundary', '这里管理当前租户的成员、组织和角色。全局账号、密码和平台管理员身份由平台账号管理及个人设置负责。')} />
    {query.error && <Alert type="error" title={resolveSimpleTableLoadError(query.error,
      t('platform-accounts.memberLoadFailed', '成员加载失败，请选择组织租户并确认查看权限'))}
      action={<Button onClick={() => void query.refetch()}>{t('table.retry', '重试')}</Button>}/>}
    {schema.error && <Alert type="error" title={t('table.schemaLoadFail', '页面结构加载失败，请稍后重试。')}
      action={<Button onClick={() => void schema.refetch()}>{t('table.retry', '重试')}</Button>}/>}
    <div className="sp-member-table-scroll" style={{flex: 1, minHeight: 0, overflow: 'auto'}}>
      <DataTable<Member>
        rowKey="userId"
        storageKey="tenant-members"
        dataSource={query.error ? [] : query.data?.content ?? []}
        loading={query.isFetching}
        columns={schema.data ? columns.filter(column => !!schema.data?.schema.properties?.[column.key]) : columns}
        rowSelection={{type: 'radio', selectedRowKeys: selectedKeys, onChange: keys => setSelectedKeys(keys)}}
        refresh={() => void query.refetch()}
        query={schema.data?.schema?.['x-query'] ? {mode: 'remote', schema: schema.data.schema as unknown as QuerySchema,
          state: tableQuery, contextKey: scope, onChange: next => {setTableQuery(next); setPage(0);}} : undefined}
        toolbarExtra={<TableActions<Member> buttons={memberActions} selectedKeys={selectedKeys}
          selectedRows={selectedMember ? [selectedMember] : []} isButtonDisabled={() => !canManage}
          onButtonEvents={{add: () => open('add'), edit: () => selectedMember && open('edit', selectedMember),
            configRole: () => selectedMember && setRoleUser(selectedMember.userId),
            remove: () => selectedMember && void remove(selectedMember)}}/>}
        pagination={{
          current: page + 1,
          pageSize,
          total: query.data?.page?.totalElements ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          onChange: (nextPage, nextSize) => {
            setPage(nextSize === pageSize ? nextPage - 1 : 0);
            setPageSize(nextSize);
          },
        }}
        scroll={{x: 1100}}
      />
    </div>
    <Modal open={!!mode} title={t('platform-accounts.memberTitle', '租户成员')} onOk={() => void save()} confirmLoading={saving}
      maskClosable={false} onCancel={() => {if (!saving) setMode(undefined);}} destroyOnHidden>
      <Form form={form} layout="vertical" preserve={false} disabled={saving}>
        {mode === 'add' && <Form.Item name="email" label={t('platform-accounts.email', '邮箱')} rules={[{required: true, type: 'email'}]}><Input /></Form.Item>}
        <Form.Item name="orgId" label={t('platform-accounts.memberOrg', '租户内组织')}>
          <OrganizationSelect onlyEnabled style={{width: '100%'}} />
        </Form.Item>
        <Form.Item name="userType" label={t('tenant-members.userType', '用户类型')} rules={[{required: true}]}>
          <Select options={userTypeOptions} loading={userTypeDictionary.isFetching}/>
        </Form.Item>
        {mode === 'edit' && <Form.Item name="enabled" label={t('platform-accounts.memberEnabled', '成员启用')} valuePropName="checked"><Switch /></Form.Item>}
      </Form>
    </Modal>
    <Drawer className="tenant-member-role-drawer" open={!!roleUser}
      onClose={() => setRoleUser(undefined)} placement="bottom" height="min(78vh, 760px)"
      destroyOnHidden title={t('users.button.config.role', '配置角色')}
      styles={{body: {minHeight: 0, overflow: 'auto'}}}>
      {roleUser && <RoleConfig key={roleUser} userId={roleUser} />}
    </Drawer>
  </div>;
}
