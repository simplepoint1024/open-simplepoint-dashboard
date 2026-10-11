import {useEffect, useLayoutEffect, useMemo, useRef, useState} from 'react';
import {Alert, App, Empty, Input, Modal, Space, Spin, Tag, Typography} from 'antd';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import TableActions from '@simplepoint/components/Table/Actions';
import type {TableButtonProps} from '@simplepoint/components/Table';
import DataTable, {type QuerySchema, type TableQueryState} from '@simplepoint/components/DataTable';
import {transportFilters} from '@simplepoint/components/Table/query';
import SForm from '@simplepoint/components/SForm/Lazy';
import type {SFormHandle} from '@simplepoint/components/SForm';
import {del, get, put, useData} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {getQueryScopeSnapshot} from '@simplepoint/shared/api/queryScope';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {usePageRetention, usePageSelected} from '@simplepoint/shared/page/hooks';
import type {OrganizationOption} from '@simplepoint/components/OrganizationSelect';
import {identifiableMember, membershipPayload, memberTypeSchema, type OrganizationMember as Member, type UserTypeDraft, type UserTypeOption} from './memberForms';

const base = '/common/tenant-members';
const actions: TableButtonProps[] = [
  {key: 'add', sort: 1, icon: 'PlusOutlined', text: 'i18n:tenant-members.addTenantUser', type: 'primary', argumentMaxSize: -1},
  {key: 'remove', sort: 2, icon: 'UserDeleteOutlined', text: 'i18n:tenant-members.removeFromOrganization', danger: true,
    argumentMinSize: 1, argumentMaxSize: 1},
];
type Props = {organization?: OrganizationOption; scope: string; onBlockedChange?: (blocked: boolean) => void};
export default function OrganizationMembersPanel(props: Props) {
  return <MemberSession key={JSON.stringify([props.scope, props.organization?.id])} {...props}/>;
}
function MemberSession({organization, scope, onBlockedChange}: Props) {
  const {t, ensure} = useI18n(), {message} = App.useApp(), selected = usePageSelected();
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]), [page, setPage] = useState(0);
  const [keywordInput, setKeywordInput] = useState(''), [keyword, setKeyword] = useState('');
  const [candidateOpen, setCandidateOpen] = useState(false), candidateOpenRef = useRef(false);
  const [candidatePage, setCandidatePage] = useState(0), [candidateInput, setCandidateInput] = useState(''), [candidateKeyword, setCandidateKeyword] = useState('');
  const [memberQuery, setMemberQuery] = useState<TableQueryState>({filters: {}});
  const [candidateQuery, setCandidateQuery] = useState<TableQueryState>({filters: {}});
  const [candidate, setCandidate] = useState<Member>(), candidateRef = useRef<Member | undefined>(undefined);
  const [candidateDraft, setCandidateDraft] = useState<UserTypeDraft>({userType: 'INTERNAL'});
  const [typeEditor, setTypeEditor] = useState<{row: Member; draft: UserTypeDraft} | null>(null), typeRef = useRef<typeof typeEditor>(null);
  const [removal, setRemoval] = useState<Member | null>(null), removalRef = useRef<Member | null>(null);
  const [saving, setSaving] = useState(false), [error, setError] = useState('');
  const flight = useRef(false), alive = useRef(true), request = useRef<AbortController | null>(null), candidateForm = useRef<SFormHandle>(null), typeForm = useRef<SFormHandle>(null);
  const schema = useSchema(`${base}/by-organization`, {enabled: !!organization?.id});
  const querySchema = schema.data?.schema?.['x-query'] ? schema.data.schema as unknown as QuerySchema : undefined;
  const blocked = candidateOpen || !!typeEditor || !!removal || saving;
  usePageRetention(false, blocked);
  useLayoutEffect(() => {onBlockedChange?.(blocked); return () => onBlockedChange?.(false);}, [blocked, onBlockedChange]);
  useEffect(() => {void ensure(['tenant-members', 'platform-accounts', 'common']);}, [ensure]);
  useEffect(() => {alive.current = true; return () => {alive.current = false; request.current?.abort();};}, []);
  useEffect(() => {setSelectedKeys([]);}, [page, keyword, memberQuery]);

  const members = useData(['organization-members', scope, organization?.id, keyword, page, memberQuery],
    ({signal}) => get<RawPage<Member>>(`${base}/by-organization`,
      {...transportFilters(memberQuery.filters), ...(memberQuery.sort ? {sort: memberQuery.sort} : {}), orgId: organization?.id, keyword, page, size: 20}, {signal}).then(raw => normalizePage(raw, 20)),
    {enabled: !!organization?.id && !!querySchema, retry: false});
  const candidates = useData(['organization-candidates', scope, organization?.id, candidateKeyword, candidatePage, candidateQuery],
    ({signal}) => get<RawPage<Member>>(`${base}/organization-candidates`,
      {...transportFilters(candidateQuery.filters), ...(candidateQuery.sort ? {sort: candidateQuery.sort} : {}), orgId: organization?.id, keyword: candidateKeyword, page: candidatePage, size: 10}, {signal}).then(raw => normalizePage(raw, 10)),
    {enabled: !!organization?.id && candidateOpen && !!querySchema, retry: false});
  const capabilities = useData(['organization-member-capabilities', scope], ({signal}) => get<{manage: boolean; remove: boolean}>(`${base}/by-organization/capabilities`, undefined, {signal}),
    {enabled: !!organization?.id});
  const dictionary = useData(['tenant-member-user-types'], ({signal}) =>
    get<UserTypeOption[]>('/common/platform/dictionaries/options', {dictionaryCode: 'tenant.member.user-type'}, {signal}), {enabled: !!organization?.id});
  const typeOptions = useMemo(() => (dictionary.data ?? []).map(option => ({value: option.value,
    label: option.label.startsWith('i18n:') ? t(option.label.slice(5), option.value) : option.label})), [dictionary.data, t]);
  const typeLabel = (value?: string) => typeOptions.find(option => option.value === value)?.label
    ?? t(`tenant-members.userType.${value ?? 'INTERNAL'}`, value ?? 'INTERNAL');
  const typeSchema = useMemo(() => memberTypeSchema(t, typeOptions), [t, typeOptions]);
  const fields = schema.data?.schema.properties;
  const visible = (field: string) => !!fields?.[field];
  const writable = (field: string) => {const definition = fields?.[field];
    return !!definition && typeof definition === 'object' && definition.readOnly !== true && definition['x-field-access'] !== 'MASKED';};
  const canIdentify = visible('userId') && typeof fields?.userId === 'object' && fields.userId['x-field-access'] !== 'MASKED'
    && visible('revision') && typeof fields.revision === 'object' && fields.revision['x-field-access'] !== 'MASKED';
  const canManage = capabilities.data?.manage === true && canIdentify, canAssign = canManage && writable('orgId');
  const canSearch = ['name', 'email', 'userId'].every(field => {const definition = fields?.[field];
    return !!definition && typeof definition === 'object' && definition['x-field-access'] !== 'MASKED';});
  const selectedMember = members.data?.content.find(row => selectedKeys.includes(row.userId));
  const current = () => alive.current && scope === getQueryScopeSnapshot();
  const canOpen = () => current() && !flight.current && !candidateOpenRef.current && !typeRef.current && !removalRef.current;
  const close = () => {if (flight.current) return; candidateOpenRef.current = false; candidateRef.current = undefined;
    typeRef.current = null; removalRef.current = null; setCandidateOpen(false); setCandidate(undefined); setTypeEditor(null); setRemoval(null); setError('');};
  const write = async (operation: (signal: AbortSignal) => Promise<unknown>, success: string) => {
    if (!current() || flight.current) return; flight.current = true; setSaving(true); setError('');
    const controller = new AbortController(); request.current = controller;
    try {await operation(controller.signal); if (!current()) return;
      const [refreshed] = await Promise.all([members.refetch(), candidateOpenRef.current ? candidates.refetch() : undefined]);
      if (!current()) return; message.success(success);
      if (refreshed.data && page >= refreshed.data.page.totalPages) setPage(Math.max(0, refreshed.data.page.totalPages - 1));
      candidateOpenRef.current = false; candidateRef.current = undefined; typeRef.current = null; removalRef.current = null;
      setCandidateOpen(false); setCandidate(undefined); setTypeEditor(null); setRemoval(null); setSelectedKeys([]);
    } catch (reason) {if (current()) setError(resolveApiErrorMessage(reason, t('tenant-members.saveFailed', '保存失败，请刷新后重试')));}
    finally {request.current = null; flight.current = false; if (alive.current) setSaving(false);}
  };
  const openCandidates = () => {
    if (!canAssign || !canOpen()) return; candidateOpenRef.current = true; candidateRef.current = undefined;
    setCandidateQuery({filters: {}});
    setCandidate(undefined); setCandidateDraft({userType: 'INTERNAL'}); setCandidatePage(0); setCandidateInput(''); setCandidateKeyword(''); setError(''); setCandidateOpen(true);
  };
  const chooseCandidate = (row?: Member) => {if (flight.current) return; candidateRef.current = row; setCandidate(row);
    setCandidateDraft({userType: row?.userType || 'INTERNAL'});};
  const assign = (draft: UserTypeDraft) => {
    const target = candidateRef.current, orgId = organization?.id;
    if (!target || !orgId || !canAssign || !identifiableMember(target)) return;
    void write(signal => put(`${base}/by-organization/${encodeURIComponent(target.userId)}`,
      membershipPayload(target, orgId, draft, writable('userType')), {signal}), t('tenant-members.assigned', '用户已加入所选组织'));
  };
  const openType = (row: Member) => {
    if (!canOpen() || !canManage || !writable('userType') || !identifiableMember(row)
      || !Object.prototype.hasOwnProperty.call(row, 'orgId') || row.orgId === '***' || !typeOptions.length) return;
    const next = {row: {...row}, draft: {userType: row.userType || 'INTERNAL'}};
    typeRef.current = next; setTypeEditor(next); setError('');
  };
  const saveType = (draft: UserTypeDraft) => {
    const target = typeRef.current; if (!target || !canManage || !writable('userType')) return;
    void write(signal => put(`${base}/by-organization/${encodeURIComponent(target.row.userId)}`,
      membershipPayload(target.row, target.row.orgId ?? null, draft, true), {signal}), t('tenant-members.typeSaved', '用户类型已更新'));
  };
  const remove = () => {
    if (!canOpen() || !canAssign || !capabilities.data?.remove || !identifiableMember(selectedMember)) return;
    const target = {...selectedMember!}; removalRef.current = target; setRemoval(target); setError('');
  };
  const confirmRemove = () => {
    const target = removalRef.current, orgId = organization?.id; if (!target || !orgId || !capabilities.data?.remove) return;
    void write(signal => del(`${base}/by-organization/${encodeURIComponent(target.userId)}`, [], {signal, json: {orgId, revision: target.revision}}),
      t('tenant-members.removedFromOrganization', '用户已移出组织'));
  };
  const columns = [
    {key: 'name', title: t('tenant-members.name', '用户'), dataIndex: 'name', width: 150},
    {key: 'email', title: t('platform-accounts.email', '邮箱'), dataIndex: 'email', width: 210},
    {key: 'orgName', title: t('tenant-members.organization', '所属组织'), dataIndex: 'orgName', width: 150,
      render: (_: unknown, row: Member) => row.orgName || '-'},
    {key: 'userType', title: t('tenant-members.userType', '用户类型'), dataIndex: 'userType', width: 130,
      render: (_: unknown, row: Member) => canManage && writable('userType') && visible('orgId')
        ? <TableActions selectedKeys={[row.userId]} selectedRows={[row]} buttons={[{key: 'type', sort: 0, type: 'link', size: 'small',
          icon: 'EditOutlined', text: typeLabel(row.userType), disabled: blocked || !identifiableMember(row) || !Object.prototype.hasOwnProperty.call(row, 'orgId')
            || row.orgId === '***' || !typeOptions.length, onClick: () => openType(row)}]}/>
        : <Tag>{typeLabel(row.userType)}</Tag>},
  ].filter(column => visible(column.key));
  const retry = (refetch: () => unknown) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 0,
    text: t('table.retry', '重试'), onClick: () => {void refetch();}}]}/>;
  const writeError = error && <Alert type="error" showIcon title={error}/>;
  return <section className="organization-members-panel" aria-label={t('tenant-members.panel', '组织用户')}>
    {!organization ? <Empty description={t('tenant-members.selectOrganization', '请从左侧选择组织，查看本级及下级用户')}/> : <>
      <div className="organization-members-header"><div><Typography.Title level={5}>{organization.name}</Typography.Title>
        <Typography.Text type="secondary">{t('tenant-members.descendantsHint', '显示当前组织及所有下级组织的用户')}</Typography.Text></div></div>
      <Input.Search allowClear disabled={!canSearch || blocked} value={keywordInput} placeholder={t('tenant-members.search', '搜索姓名、邮箱或用户 ID')}
        onChange={event => {setKeywordInput(event.target.value); if (!event.target.value) {setKeyword(''); setPage(0);}}}
        onSearch={value => {setKeyword(value.trim()); setPage(0);}}/>
      {schema.isError && <Alert type="error" showIcon title={t('table.schemaLoadFail', '页面结构加载失败，请稍后重试。')} action={retry(schema.refetch)}/>}
      {members.isError && <Alert type="error" showIcon title={t('tenant-members.loadFailed', '组织用户加载失败')} action={retry(members.refetch)}/>}
      <div className="organization-members-table"><DataTable<Member> rowKey={canIdentify ? 'userId' : (_row, index) => `redacted-member-${index}`} storageKey="organization-members"
        query={querySchema ? {mode: 'remote', schema: querySchema, state: memberQuery, onChange: next => {
          if (blocked) return; setMemberQuery(next); setPage(0); setSelectedKeys([]);
        }} : undefined}
        columns={columns} rowSelection={canIdentify ? {type: 'radio', selectedRowKeys: selectedKeys, onChange: setSelectedKeys,
          getCheckboxProps: row => ({disabled: blocked || !identifiableMember(row)})} : undefined}
        toolbarExtra={<TableActions<Member> buttons={actions.filter(action => action.key !== 'remove' || capabilities.data?.remove)}
          selectedKeys={selectedKeys} selectedRows={selectedMember ? [selectedMember] : []}
          isButtonDisabled={button => !canAssign || blocked || button.key === 'remove' && !identifiableMember(selectedMember)}
          onButtonEvents={{add: openCandidates, remove}}/>}
        dataSource={members.isError ? [] : members.data?.content ?? []} loading={members.isFetching} refresh={() => {if (!blocked) void members.refetch();}}
        pagination={{current: page + 1, pageSize: 20, total: members.data?.page.totalElements ?? 0, disabled: blocked,
          showSizeChanger: false, onChange: next => {if (!blocked) setPage(next - 1);}}} scroll={{x: 680}}/></div>
    </>}
    <Modal wrapClassName="organization-member-candidates" open={candidateOpen && selected} title={t('tenant-members.addTenantUser', '添加租户用户')} width={760}
      onCancel={close} onOk={() => {if (!flight.current) {if (writable('userType')) candidateForm.current?.submit(); else assign({});}}}
      closable={!saving} maskClosable={!saving} keyboard={!saving}
      okButtonProps={{disabled: !identifiableMember(candidate) || !canAssign || saving || writable('userType') && !typeOptions.length}} confirmLoading={saving} destroyOnHidden>
      <Space direction="vertical" style={{width: '100%'}} size={12}>
        {writeError}
        <Typography.Text type="secondary">{t('tenant-members.transferHint', '仅搜索当前租户已有用户；若用户属于其他组织，保存后会转移到所选组织。')}</Typography.Text>
        <Input.Search allowClear disabled={!canSearch || saving} value={candidateInput} placeholder={t('tenant-members.search', '搜索姓名、邮箱或用户 ID')}
          onChange={event => {setCandidateInput(event.target.value); if (!event.target.value) {setCandidateKeyword(''); setCandidatePage(0);}}}
          onSearch={value => {chooseCandidate(undefined); setCandidateKeyword(value.trim()); setCandidatePage(0);}}/>
        {candidates.isError && <Alert type="error" showIcon title={t('tenant-members.candidatesFailed', '可添加用户加载失败')} action={retry(candidates.refetch)}/>}
        <DataTable<Member> size="small" rowKey="userId" storageKey="organization-member-candidates" loading={candidates.isFetching}
          query={querySchema ? {mode: 'remote', schema: querySchema, state: candidateQuery, onChange: next => {
            if (saving) return; chooseCandidate(undefined); setCandidateQuery(next); setCandidatePage(0);
          }} : undefined}
          dataSource={candidates.isError ? [] : candidates.data?.content ?? []} columns={[
            {title: t('tenant-members.name', '用户'), dataIndex: 'name'}, {title: t('platform-accounts.email', '邮箱'), dataIndex: 'email'},
            {title: t('tenant-members.organization', '所属组织'), dataIndex: 'orgName', render: (value: unknown) => String(value || '-')},
          ].filter(column => visible(column.dataIndex))}
          rowSelection={{type: 'radio', selectedRowKeys: candidate ? [candidate.userId] : [],
            getCheckboxProps: row => ({disabled: saving || !identifiableMember(row)}),
            onChange: keys => chooseCandidate(candidates.data?.content.find(row => row.userId === keys[0]))}}
          onRow={row => ({onClick: () => {if (!saving && identifiableMember(row)) chooseCandidate(row);}})}
          pagination={{current: candidatePage + 1, pageSize: 10, total: candidates.data?.page.totalElements ?? 0, disabled: saving,
            showSizeChanger: false, onChange: next => {if (!saving) {chooseCandidate(undefined); setCandidatePage(next - 1);}}}}/>
        {dictionary.isError && <Alert type="error" showIcon title={t('table.loadFail', '加载失败')} action={retry(dictionary.refetch)}/>}
        {writable('userType') && (typeOptions.length ? <SForm ref={candidateForm} hideSubmit schema={typeSchema} formData={candidateDraft} disabled={saving}
          onChange={event => {if (!flight.current) setCandidateDraft(event.formData ?? {});}} onSubmit={event => assign(event.formData ?? {})}/>
          : dictionary.isFetching ? <Spin size="small"/> : <Alert type="warning" title={t('tenant-members.userTypesUnavailable', '当前没有可用的用户类型')}/>)}
      </Space>
    </Modal>
    <Modal wrapClassName="organization-member-type" open={!!typeEditor && selected} title={t('tenant-members.userType', '用户类型')} onCancel={close}
      onOk={() => {if (!flight.current) typeForm.current?.submit();}} confirmLoading={saving} closable={!saving} maskClosable={!saving} keyboard={!saving}
      okButtonProps={{disabled: saving}} destroyOnHidden>
      {writeError}
      {typeEditor && <SForm ref={typeForm} hideSubmit schema={typeSchema} formData={typeEditor.draft} disabled={saving}
        onChange={event => {if (flight.current || !typeRef.current) return; const next = {...typeRef.current, draft: event.formData ?? {}}; typeRef.current = next; setTypeEditor(next);}}
        onSubmit={event => saveType(event.formData ?? {})}/>}
    </Modal>
    <Modal wrapClassName="organization-member-removal" open={!!removal && selected} title={t('tenant-members.removeFromOrganization', '移出组织')} onCancel={close}
      onOk={confirmRemove} confirmLoading={saving} closable={!saving} maskClosable={!saving} keyboard={!saving} okButtonProps={{danger: true, disabled: saving}} destroyOnHidden>
      {writeError}<Typography.Paragraph>{t('tenant-members.removeOrganizationBoundary', '只解除用户与所属组织的关系，保留租户成员身份、角色与账号。')}</Typography.Paragraph>
      <Typography.Text>{removal?.name}</Typography.Text>
    </Modal>
  </section>;
}
