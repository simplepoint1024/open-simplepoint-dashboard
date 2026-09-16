import {useEffect, useMemo, useState, useSyncExternalStore} from 'react';
import {Alert, App, Button, Drawer, Form, Input, Modal, Space, Switch, Tag} from 'antd';
import DataTable from '@simplepoint/components/DataTable';
import OrganizationSelect from '@simplepoint/components/OrganizationSelect';
import {del, get, post, put, useData} from '@simplepoint/shared/api/methods';
import type {Page} from '@simplepoint/shared/types/request';
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
}
interface MemberCommand {email?: string; orgId?: string | null; enabled?: boolean; revision?: number}
const base = `${contextPath}/tenant-members`;

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
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<MemberCommand>();
  const query = useData(
    ['tenantMembers', scope, page, pageSize],
    () => get<Page<Member>>(base, {page, size: pageSize}),
  );
  const capabilities = useData(['tenantMemberCapabilities'], () => get<{manage: boolean}>(`${base}/capabilities`));
  const canManage = capabilities.data?.manage ?? false;
  useEffect(() => { void ensure(['platform-accounts', 'users', 'roles', 'common']); }, [ensure]);
  const open = (next: typeof mode, member?: Member) => {
    form.resetFields(); setMode(next); setSelected(member);
    form.setFieldsValue(member ? {orgId: member.orgId, enabled: member.enabled} : {});
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
      setMode(undefined); form.resetFields(); await query.refetch();
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
    try { await del(`${base}/${encodeURIComponent(member.userId)}`, []); await query.refetch(); }
    catch { message.error(t('platform-accounts.memberFailed', '操作失败，请确认租户管理权限、账号和成员版本')); }
  };
  const columns = useMemo(() => [
    {key: 'name', title: t('platform-accounts.name', '姓名'), dataIndex: 'name', width: 180},
    {key: 'email', title: t('platform-accounts.email', '邮箱'), dataIndex: 'email', width: 260},
    {
      key: 'organization',
      title: t('platform-accounts.memberOrg', '租户内组织'),
      width: 260,
      render: (_: unknown, row: Member) => row.orgName ?? row.orgId ?? '-',
    },
    {
      key: 'enabled',
      title: t('platform-accounts.memberEnabled', '成员启用'),
      width: 120,
      render: (_: unknown, row: Member) => row.enabled
        ? <Tag color="success">{t('common.enabled', '启用')}</Tag>
        : <Tag>{t('common.disabled', '停用')}</Tag>,
    },
    {
      key: 'actions',
      title: t('platform-accounts.actions', '操作'),
      fixed: 'right' as const,
      width: 280,
      render: (_: unknown, row: Member) => <Space size={4}>
        <Button size="small" disabled={!canManage} onClick={() => open('edit', row)}>
          {t('platform-accounts.editMember', '编辑成员')}
        </Button>
        <Button size="small" disabled={!canManage} onClick={() => setRoleUser(row.userId)}>
          {t('users.button.config.role', '配置角色')}
        </Button>
        <Button size="small" disabled={!canManage} danger onClick={() => void remove(row)}>
          {t('platform-accounts.removeMember', '移除租户成员')}
        </Button>
      </Space>,
    },
  ], [canManage, t]);

  return <div style={{height: '100%', display: 'flex', flexDirection: 'column', gap: 16}}>
    <Alert type="info" showIcon message={t('platform-accounts.memberBoundary', '这里管理当前租户的成员、组织和角色。全局账号、密码和平台管理员身份由平台账号管理及个人设置负责。')} />
    {query.error && <Alert type="error" message={t('platform-accounts.memberLoadFailed', '成员加载失败，请选择组织租户并确认查看权限')} />}
    <div style={{flex: 1, minHeight: 0}}>
      <DataTable<Member>
        rowKey="userId"
        storageKey="tenant-members"
        dataSource={query.data?.content ?? []}
        loading={query.isFetching}
        columns={columns}
        refresh={() => void query.refetch()}
        toolbarExtra={(
          <Button disabled={!canManage} type="primary" onClick={() => open('add')}>
            {t('platform-accounts.addMember', '添加已有账号')}
          </Button>
        )}
        pagination={{
          current: page + 1,
          pageSize,
          total: query.data?.page?.totalElements ?? 0,
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
        {mode === 'edit' && <Form.Item name="enabled" label={t('platform-accounts.memberEnabled', '成员启用')} valuePropName="checked"><Switch /></Form.Item>}
      </Form>
    </Modal>
    <Drawer open={!!roleUser} onClose={() => setRoleUser(undefined)} width={850} destroyOnHidden title={t('users.button.config.role', '配置角色')}>
      {roleUser && <RoleConfig key={roleUser} userId={roleUser} />}
    </Drawer>
  </div>;
}
