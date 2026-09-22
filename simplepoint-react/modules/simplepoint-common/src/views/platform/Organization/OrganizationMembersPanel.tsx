import {useEffect, useMemo, useState} from 'react';
import {Alert, App, Button, Empty, Input, Modal, Select, Space, Table, Tag, Typography} from 'antd';
import DataTable from '@simplepoint/components/DataTable';
import {get, put, useData} from '@simplepoint/shared/api/methods';
import {getQueryScopeSnapshot} from '@simplepoint/shared/api/queryScope';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import type {OrganizationOption} from '@simplepoint/components/OrganizationSelect';

type Member = {userId: string; name: string; email?: string; orgId?: string; orgName?: string;
  enabled: boolean; userType?: string; revision?: number};
type DictionaryOption = {value: string; label: string};
const base = '/common/tenant-members';

export default function OrganizationMembersPanel({organization, scope}: {organization?: OrganizationOption; scope: string}) {
  const {t, ensure} = useI18n();
  const {message} = App.useApp();
  const [page, setPage] = useState(0);
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [candidateOpen, setCandidateOpen] = useState(false);
  const [candidatePage, setCandidatePage] = useState(0);
  const [candidateInput, setCandidateInput] = useState('');
  const [candidateKeyword, setCandidateKeyword] = useState('');
  const [candidate, setCandidate] = useState<Member>();
  const [candidateType, setCandidateType] = useState('INTERNAL');
  const [saving, setSaving] = useState(false);
  const [editingUser, setEditingUser] = useState<string>();
  useEffect(() => {void ensure(['tenant-members', 'platform-accounts', 'common']);}, [ensure]);
  useEffect(() => {setPage(0); setKeyword(''); setKeywordInput(''); setCandidateOpen(false);}, [organization?.id]);

  const members = useData(['organization-members', scope, organization?.id, keyword, page],
    ({signal}) => get<RawPage<Member>>(`${base}/by-organization`,
      {orgId: organization?.id, keyword, page, size: 20}, {signal}).then(raw => normalizePage(raw, 20)),
    {enabled: !!organization?.id, retry: false});
  const candidates = useData(['organization-candidates', scope, organization?.id, candidateKeyword, candidatePage],
    ({signal}) => get<RawPage<Member>>(`${base}/organization-candidates`,
      {orgId: organization?.id, keyword: candidateKeyword, page: candidatePage, size: 10}, {signal})
      .then(raw => normalizePage(raw, 10)),
    {enabled: !!organization?.id && candidateOpen, retry: false});
  const capabilities = useData(['tenant-member-capabilities', scope], () => get<{manage: boolean}>(`${base}/capabilities`),
    {enabled: !!organization?.id});
  const dictionary = useData(['tenant-member-user-types'], () =>
    get<DictionaryOption[]>('/common/platform/dictionaries/options', {dictionaryCode: 'tenant.member.user-type'}));
  const typeOptions = useMemo(() => (dictionary.data ?? []).map(option => ({value: option.value,
    label: option.label.startsWith('i18n:') ? t(option.label.slice(5), option.value) : option.label})), [dictionary.data, t]);
  const typeLabel = (value?: string) => typeOptions.find(option => option.value === value)?.label
    ?? t(`tenant-members.userType.${value ?? 'INTERNAL'}`, value ?? 'INTERNAL');
  const canManage = capabilities.data?.manage === true;

  const openCandidates = () => {
    setCandidate(undefined); setCandidateType('INTERNAL'); setCandidatePage(0);
    setCandidateInput(''); setCandidateKeyword(''); setCandidateOpen(true);
  };
  const assign = async () => {
    if (!candidate || !organization || scope !== getQueryScopeSnapshot()) return;
    setSaving(true);
    try {
      await put(`${base}/${encodeURIComponent(candidate.userId)}`, {orgId: organization.id,
        revision: candidate.revision, userType: candidateType});
      setCandidateOpen(false); setCandidate(undefined);
      await Promise.all([members.refetch(), candidates.refetch()]);
      message.success(t('tenant-members.assigned', '用户已加入所选组织'));
    } catch {message.error(t('tenant-members.saveFailed', '保存失败，请刷新后重试'));}
    finally {setSaving(false);}
  };
  const changeType = async (member: Member, userType: string) => {
    if (scope !== getQueryScopeSnapshot()) return;
    setEditingUser(member.userId);
    try {
      await put(`${base}/${encodeURIComponent(member.userId)}`, {orgId: member.orgId ?? null,
        revision: member.revision, userType});
      await members.refetch();
      message.success(t('tenant-members.typeSaved', '用户类型已更新'));
    } catch {message.error(t('tenant-members.saveFailed', '保存失败，请刷新后重试'));}
    finally {setEditingUser(undefined);}
  };

  const columns = [
    {key: 'name', title: t('tenant-members.name', '用户'), dataIndex: 'name', width: 150},
    {key: 'email', title: t('platform-accounts.email', '邮箱'), dataIndex: 'email', width: 210},
    {key: 'orgName', title: t('tenant-members.organization', '所属组织'), dataIndex: 'orgName', width: 150,
      render: (_: unknown, row: Member) => row.orgName || '-'},
    {key: 'userType', title: t('tenant-members.userType', '用户类型'), dataIndex: 'userType', width: 130,
      render: (_: unknown, row: Member) => canManage
        ? <Select aria-label={t('tenant-members.userType', '用户类型')} size="small" style={{minWidth: 112}}
          value={row.userType || 'INTERNAL'} options={typeOptions} disabled={!typeOptions.length || !!editingUser}
          onChange={value => void changeType(row, value)}/>
        : <Tag>{typeLabel(row.userType)}</Tag>},
  ];

  return <section className="organization-members-panel" aria-label={t('tenant-members.panel', '组织用户')}>
    {!organization ? <Empty description={t('tenant-members.selectOrganization', '请从左侧选择组织，查看本级及下级用户')}/>
      : <>
        <div className="organization-members-header"><div><Typography.Title level={5}>{organization.name}</Typography.Title>
          <Typography.Text type="secondary">{t('tenant-members.descendantsHint', '显示当前组织及所有下级组织的用户')}</Typography.Text></div>
          <Button type="primary" disabled={!canManage} onClick={openCandidates}>
            {t('tenant-members.addTenantUser', '添加租户用户')}</Button></div>
        <Input.Search allowClear value={keywordInput} placeholder={t('tenant-members.search', '搜索姓名、邮箱或用户 ID')}
          onChange={event => {setKeywordInput(event.target.value); if (!event.target.value) {setKeyword(''); setPage(0);}}}
          onSearch={value => {setKeyword(value.trim()); setPage(0);}}/>
        {members.isError && <Alert type="error" showIcon title={t('tenant-members.loadFailed', '组织用户加载失败')}
          action={<Button onClick={() => void members.refetch()}>{t('table.retry', '重试')}</Button>}/>}
        <div className="organization-members-table"><DataTable<Member> rowKey="userId" storageKey="organization-members"
          columns={columns} dataSource={members.isError ? [] : members.data?.content ?? []}
          loading={members.isFetching} refresh={() => void members.refetch()}
          pagination={{current: page + 1, pageSize: 20, total: members.data?.page.totalElements ?? 0,
            showSizeChanger: false, onChange: next => setPage(next - 1)}} scroll={{x: 680}}/></div>
      </>}
    <Modal open={candidateOpen} title={t('tenant-members.addTenantUser', '添加租户用户')} width={760}
      onCancel={() => {if (!saving) setCandidateOpen(false);}} onOk={() => void assign()}
      okButtonProps={{disabled: !candidate || !candidateType || !canManage}} confirmLoading={saving} destroyOnHidden>
      <Space direction="vertical" style={{width: '100%'}} size={12}>
        <Typography.Text type="secondary">{t('tenant-members.transferHint', '仅搜索当前租户已有用户；若用户属于其他组织，保存后会转移到所选组织。')}</Typography.Text>
        <Input.Search allowClear value={candidateInput} placeholder={t('tenant-members.search', '搜索姓名、邮箱或用户 ID')}
          onChange={event => {setCandidateInput(event.target.value); if (!event.target.value) {setCandidateKeyword(''); setCandidatePage(0);}}}
          onSearch={value => {setCandidateKeyword(value.trim()); setCandidatePage(0);}}/>
        {candidates.isError && <Alert type="error" showIcon title={t('tenant-members.candidatesFailed', '可添加用户加载失败')}
          action={<Button onClick={() => void candidates.refetch()}>{t('table.retry', '重试')}</Button>}/>}
        <Table<Member> size="small" rowKey="userId" loading={candidates.isFetching}
          dataSource={candidates.isError ? [] : candidates.data?.content ?? []}
          columns={[{title: t('tenant-members.name', '用户'), dataIndex: 'name'},
            {title: t('platform-accounts.email', '邮箱'), dataIndex: 'email'},
            {title: t('tenant-members.organization', '所属组织'), dataIndex: 'orgName', render: value => value || '-'}]}
          rowSelection={{type: 'radio', selectedRowKeys: candidate ? [candidate.userId] : [], onChange: keys => {
            setCandidate(candidates.data?.content.find(row => row.userId === keys[0]));
          }}}
          onRow={row => ({onClick: () => setCandidate(row)})}
          pagination={{current: candidatePage + 1, pageSize: 10, total: candidates.data?.page.totalElements ?? 0,
            showSizeChanger: false, onChange: next => {setCandidate(undefined); setCandidatePage(next - 1);}}}/>
        <div className="organization-members-type"><Typography.Text>{t('tenant-members.userType', '用户类型')}</Typography.Text>
          <Select value={candidateType} options={typeOptions} onChange={setCandidateType} disabled={!typeOptions.length}
            loading={dictionary.isFetching} style={{minWidth: 180}}/></div>
      </Space>
    </Modal>
  </section>;
}
