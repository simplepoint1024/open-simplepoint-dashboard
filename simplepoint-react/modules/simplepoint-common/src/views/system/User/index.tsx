import {useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ComponentProps} from 'react';
import {Alert, Modal, Tag} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import type {TableButtonProps} from '@simplepoint/components/Table';
import DataTable, {type QuerySchema, type TableQueryState} from '@simplepoint/components/DataTable';
import {transportFilters} from '@simplepoint/components/Table/query';
import {resolveSimpleTableLoadError} from '@simplepoint/components/SimpleTable/errorMessage';
import SForm, {type SFormHandle} from '@simplepoint/components/SForm';
import ResourceDrawer from '@simplepoint/components/ResourceDrawer';
import {reachableServerPage} from '@simplepoint/components/SimpleTable/pagination';
import {assignmentPresentationKeys} from '@simplepoint/components/AssignmentTable/identity';
import {del, get, post, put} from '@simplepoint/shared/api/methods';
import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';
import {normalizePage, type RawPage} from '@simplepoint/shared/types/request';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {EMPTY_QUERY_SCOPE, getQueryScopeSnapshot, subscribeQueryScope} from '@simplepoint/shared/api/queryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useOwnedData} from '@simplepoint/shared/hooks/useOwnedData';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {usePageRetention} from '@simplepoint/shared/page/hooks';
import {contextPath} from '@/services';
import RoleConfig from './config/role';
import {identifiableMember, memberCommand, memberDraft, memberDraftChanged, type Member, type MemberCommand} from './memberForm';

type Editor = {mode: 'add' | 'edit'; member?: Member; initial: MemberCommand};
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
  const operation = useOperationScope(scope);
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(20);
  const [editor, setEditor] = useState<Editor>();
  const editorRef = useRef<Editor | undefined>(undefined);
  const mode = editor?.mode;
  const [roleUser, setRoleUser] = useState<string>();
  const roleRef = useRef<string | undefined>(undefined);
  const [roleLocked, setRoleLocked] = useState(false), roleLockRef = useRef(false);
  const [removal, setRemoval] = useState<Member>(), removalRef = useRef<Member | undefined>(undefined);
  const [discard, setDiscard] = useState(false), discardRef = useRef(false);
  const [writeError, setWriteError] = useState<string>();
  const [notice, setNotice] = useState<string>();
  const [selectedKeys, setSelectedKeys] = useState<React.Key[]>([]);
  const formRef = useRef<SFormHandle>(null);
  const pendingSubmit = useRef<AbortController | undefined>(undefined);
  const [formData, setFormData] = useState<MemberCommand>({});
  const draftRef = useRef<MemberCommand>({});
  const [tableQuery, setTableQuery] = useState<TableQueryState>({filters: {}});
  const schema = useSchema(base, {retry: false});
  const query = useOwnedData(
    ['tenantMembers', scope, page, pageSize, tableQuery],
    async ({signal}) => normalizePage(await get<RawPage<Member>>(base,
      {...transportFilters(tableQuery.filters), page, size: pageSize, ...(tableQuery.sort ? {sort: tableQuery.sort} : {})}, {signal, notifyOnError: false})),
    {retry: false, enabled: !operation.busy},
  );
  const capabilities = useOwnedData(['tenantMemberCapabilities'], ({signal}) => get<{manage: boolean; configureRoles: boolean}>(`${base}/capabilities`,
    undefined, {signal, notifyOnError: false}), {retry: false});
  const userTypeDictionary = useOwnedData(['tenantMemberUserTypes'], ({signal}) => get<Array<{value: string; label: string}>>(
    '/common/platform/dictionaries/options', {dictionaryCode: 'tenant.member.user-type'}, {signal, notifyOnError: false}), {retry: false});
  const userTypeOptions = useMemo(() => (userTypeDictionary.data ?? []).map(item => ({value: item.value,
    label: item.label.startsWith('i18n:') ? t(item.label.slice(5), item.value) : item.label})), [t, userTypeDictionary.data]);
  const rows = query.error ? [] : query.data?.content ?? [];
  const presentationKeys = useMemo(() => assignmentPresentationKeys(rows, 'userId'), [rows]);
  const selectedMember = rows.find(member => selectedKeys.includes(member.userId) && identifiableMember(member));
  const properties = schema.data?.schema.properties;
  const readable = (field: string) => {
    const value = properties?.[field];
    return !!value && typeof value === 'object' && !['MASKED', 'HIDDEN'].includes(String(value['x-field-access']));
  };
  const writable = (field: string) => {
    const value = properties?.[field];
    return readable(field) && typeof value === 'object' && value.readOnly !== true;
  };
  const canIdentify = readable('userId') && readable('revision');
  const canManage = capabilities.data?.manage === true && !capabilities.error && !schema.error && canIdentify;
  const canConfigureRoles = canManage && capabilities.data?.configureRoles === true;
  const canEdit = readable('orgId') && ['orgId', 'enabled', 'userType'].some(writable)
    && !!selectedMember && Object.prototype.hasOwnProperty.call(selectedMember, 'orgId') && selectedMember.orgId !== '***';
  const blocked = !!editor || !!roleUser || !!removal || operation.busy || roleLocked;
  usePageRetention(!blocked, blocked);
  const canOpen = () => operation.current() && operation.isSelected() && !operation.isBusy()
    && !editorRef.current && !roleRef.current && !removalRef.current && !roleLockRef.current;
  const controlsDisabled = blocked || !operation.selected || query.isFetching || !!query.error
    || schema.isFetching || capabilities.isFetching;
  const roleInteraction = useCallback((locked: boolean) => {roleLockRef.current = locked; setRoleLocked(locked);}, []);
  const formSchema = useMemo<ComponentProps<typeof SForm>['schema']>(() => ({type: 'object', additionalProperties: false,
    required: [...(mode === 'add' ? ['email'] : []), ...(readable('userType') ? ['userType'] : [])],
    properties: {
      ...(mode === 'add' ? {email: {type: 'string', format: 'email', title: t('platform-accounts.email', '邮箱'), readOnly: !writable('email')}} : {}),
      ...(readable('orgId') ? {orgId: {type: 'string', title: t('platform-accounts.memberOrg', '租户内组织'), readOnly: !writable('orgId'),
        'x-ui': {widget: 'OrganizationSelect', options: {onlyEnabled: true}}}} : {}),
      ...(readable('userType') ? {userType: {type: 'string', title: t('tenant-members.userType', '用户类型'), readOnly: !writable('userType'),
        ...(userTypeOptions.length ? {oneOf: userTypeOptions.map(option => ({const: option.value, title: option.label}))} : {not: {}})}} : {}),
      ...(mode === 'edit' && readable('enabled') ? {enabled: {type: 'boolean', title: t('platform-accounts.memberEnabled', '成员启用'), readOnly: !writable('enabled')}} : {}),
    },
  }), [mode, properties, t, userTypeOptions]);
  useEffect(() => { void ensure(['platform-accounts', 'tenant-members', 'users', 'roles', 'common', 'personal']); }, [ensure]);
  useEffect(() => {
    if (query.isFetching || query.error) return;
    const reachable = reachableServerPage(page + 1, query.data?.page);
    if (reachable !== page + 1) {setPage(reachable - 1); setSelectedKeys([]);}
  }, [query.data, query.isFetching, query.error, page]);
  const open = (next: 'add' | 'edit', member?: Member) => {
    if (!canOpen() || !canManage || (next === 'add' ? !writable('email') : !canEdit || !identifiableMember(member))) return;
    const captured = member && structuredClone(member);
    const initial = Object.fromEntries(Object.entries(memberDraft(captured)).filter(([field]) => readable(field)));
    const session = {mode: next, member: captured, initial}; editorRef.current = session; setEditor(session);
    draftRef.current = {...initial}; setFormData({...initial}); setWriteError(undefined); setNotice(undefined);
  };
  const closeEditor = () => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy()) return;
    editorRef.current = undefined; discardRef.current = false; draftRef.current = {};
    setEditor(undefined); setDiscard(false); setFormData({}); setWriteError(undefined);
  };
  const cancelEditor = () => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy() || discardRef.current) return;
    if (editorRef.current && memberDraftChanged(editorRef.current.initial, draftRef.current)) {
      discardRef.current = true; setDiscard(true);
    } else closeEditor();
  };
  const save = async (values: MemberCommand) => {
    const request = pendingSubmit.current ?? operation.begin(); pendingSubmit.current = undefined;
    if (!request) return;
    const session = editorRef.current;
    if (!session || discardRef.current || !canManage || userTypeDictionary.isFetching || userTypeDictionary.error
      || (readable('userType') && !userTypeOptions.length)) {operation.finish(request); return;}
    if (!operation.current(request.signal)) {operation.finish(request); return;}
    draftRef.current = structuredClone(values); setFormData(draftRef.current);
    setWriteError(undefined);
    try {
      const changes = memberCommand(session.mode, session.member, structuredClone(values), writable);
      const options = {signal: request.signal, notifyOnError: false};
      if (session.mode === 'add') await post(base, changes, options);
      else await put(`${base}/${encodeURIComponent(session.member!.userId)}`, changes, options);
      if (!operation.current(request.signal) || editorRef.current !== session) return;
      editorRef.current = undefined; draftRef.current = {}; setEditor(undefined); setFormData({}); setSelectedKeys([]);
      setNotice(t('platform-accounts.memberSaved', '成员配置已保存')); void query.refetch();
    } catch (error) {if (operation.current(request.signal)) setWriteError(resolveApiErrorMessage(error,
      t('platform-accounts.memberFailed', '操作失败，请确认租户管理权限、账号和成员版本')));}
    finally {operation.finish(request);}
  };
  const remove = (member: Member) => {
    if (!canOpen() || !canManage || !identifiableMember(member)) return;
    const captured = structuredClone(member); removalRef.current = captured; setRemoval(captured);
    setWriteError(undefined); setNotice(undefined);
  };
  const closeRemoval = () => {
    if (!operation.current() || !operation.isSelected() || operation.isBusy()) return;
    removalRef.current = undefined; setRemoval(undefined); setWriteError(undefined);
  };
  const confirmRemove = async () => {
    const captured = removalRef.current; if (!captured || !canManage) return;
    const request = operation.begin(); if (!request) return; setWriteError(undefined);
    try {
      await del(`${base}/${encodeURIComponent(captured.userId)}`, [], {signal: request.signal, notifyOnError: false});
      if (!operation.current(request.signal) || removalRef.current !== captured) return;
      removalRef.current = undefined; setRemoval(undefined); setSelectedKeys([]); void query.refetch();
    } catch (error) {if (operation.current(request.signal)) setWriteError(resolveApiErrorMessage(error,
      t('platform-accounts.memberFailed', '操作失败，请确认租户管理权限、账号和成员版本')));}
    finally {operation.finish(request);}
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
    {notice && <Alert className="tenant-member-notice" type="success" showIcon title={notice}/>}
    {capabilities.error && <Alert className="tenant-member-capability-error" type="error" showIcon
      title={resolveSimpleTableLoadError(capabilities.error, t('platform-accounts.memberFailed', '操作失败，请确认租户管理权限、账号和成员版本'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1, text: t('table.retry', '重试'),
        disabled: !operation.selected, onClick: () => void capabilities.refetch()}]}/>}/>}
    {userTypeDictionary.error && <Alert className="tenant-member-dictionary-error" type="error" showIcon
      title={t('tenant-members.userTypesUnavailable', '当前没有可用的用户类型')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1, text: t('table.retry', '重试'),
        disabled: !operation.selected, onClick: () => void userTypeDictionary.refetch()}]}/>}/>}
    {query.error && <Alert type="error" title={resolveSimpleTableLoadError(query.error,
      t('platform-accounts.memberLoadFailed', '成员加载失败，请选择组织租户并确认查看权限'))}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1, text: t('table.retry', '重试')}]}
        onButtonEvents={{retry: () => void query.refetch()}}/>}/>}
    {schema.error && <Alert type="error" title={t('table.schemaLoadFail', '页面结构加载失败，请稍后重试。')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retry', sort: 1, text: t('table.retry', '重试')}]}
        onButtonEvents={{retry: () => void schema.refetch()}}/>}/>}
    <div className="sp-member-table-scroll" style={{flex: 1, minHeight: 0, overflow: 'auto'}}>
      <DataTable<Member>
        rowKey={canIdentify ? row => presentationKeys.get(row)! : (_row, index) => `redacted-member-${index}`}
        storageKey="tenant-members"
        dataSource={rows}
        loading={query.isFetching}
        columns={columns.filter(column => {const property = properties?.[column.key];
          return !!property && typeof property === 'object' && property['x-field-access'] !== 'HIDDEN';})}
        rowSelection={canIdentify ? {type: 'radio', selectedRowKeys: selectedKeys,
          getCheckboxProps: row => ({disabled: controlsDisabled || !identifiableMember(row)}),
          onChange: keys => {if (canOpen()) setSelectedKeys(keys);}} : undefined}
        refresh={() => void query.refetch()}
        query={schema.data?.schema?.['x-query'] ? {mode: 'remote', schema: schema.data.schema as unknown as QuerySchema,
          state: tableQuery, contextKey: scope, onChange: next => {setTableQuery(next); setPage(0); setSelectedKeys([]);}} : undefined}
        toolbarExtra={<TableActions<Member> buttons={memberActions.filter(button => button.key !== 'configRole' || canConfigureRoles)} selectedKeys={selectedKeys}
          selectedRows={selectedMember ? [selectedMember] : []} isButtonDisabled={button => controlsDisabled || !canManage || (button.key === 'edit' && !canEdit)
            || (button.key === 'add' && !writable('email'))}
          onButtonEvents={{add: () => open('add'), edit: () => selectedMember && open('edit', selectedMember),
            configRole: () => {if (!selectedMember || !canConfigureRoles || !canOpen()) return;
              roleRef.current = selectedMember.userId; setRoleUser(selectedMember.userId); setNotice(undefined);},
            remove: () => selectedMember && void remove(selectedMember)}}/>}
        pagination={{
          current: page + 1,
          pageSize,
          total: query.data?.page?.totalElements ?? 0,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50, 100],
          onChange: (nextPage, nextSize) => {
            setPage(nextSize === pageSize ? nextPage - 1 : 0); setSelectedKeys([]);
            setPageSize(nextSize);
          },
        }}
        scroll={{x: 1100}}
      />
    </div>
    {editor && operation.selected && <Modal rootClassName="tenant-member-editor" open title={t('platform-accounts.memberTitle', '租户成员')}
      maskClosable={false} onCancel={cancelEditor} closable={!operation.busy && !discard} keyboard={!operation.busy && !discard} destroyOnHidden
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, text: t('action.cancel', '取消'), disabled: operation.busy || discard, onClick: cancelEditor},
        {key: 'save', sort: 1, type: 'primary', text: t('action.save', '保存'), loading: operation.busy,
          disabled: !canManage || discard || userTypeDictionary.isFetching || !!userTypeDictionary.error
            || (readable('userType') && !userTypeOptions.length),
          onClick: () => {
            if (discardRef.current || !formRef.current || !canManage || userTypeDictionary.isFetching || userTypeDictionary.error) return;
            const request = operation.begin(); if (!request) return;
            // RJSF dispatches onSubmit after its validation state update; lock before that callback.
            pendingSubmit.current = request; formRef.current.submit();
          }},
      ]}/>}>
      {writeError && <Alert className="tenant-member-write-error" type="error" showIcon title={writeError}/>}
      <SForm key={`${mode}:${editor.member?.userId ?? 'new'}`} ref={formRef} hideSubmit schema={formSchema}
        formData={formData} disabled={operation.busy || discard || !canManage}
        onChange={event => {if (operation.isBusy() || discardRef.current || !operation.isSelected()) return;
          draftRef.current = event.formData ?? {}; setFormData(draftRef.current);}}
        onError={() => {const request = pendingSubmit.current; pendingSubmit.current = undefined;
          if (request) operation.finish(request);}}
        onSubmit={event => void save(event.formData ?? {})}/>
    </Modal>}
    {discard && operation.selected && <Modal rootClassName="tenant-member-discard" open destroyOnHidden maskClosable={false}
      title={t('personal.discard', '放弃未保存的修改？')} onCancel={() => {discardRef.current = false; setDiscard(false);}}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'continue', sort: 0, text: t('personal.continueEditing', '继续编辑'),
          onClick: () => {discardRef.current = false; setDiscard(false);}},
        {key: 'discard', sort: 1, danger: true, text: t('personal.discardConfirm', '放弃修改'), onClick: closeEditor},
      ]}/>}/>}
    {removal && operation.selected && <Modal rootClassName="tenant-member-removal" open destroyOnHidden maskClosable={false}
      title={t('platform-accounts.removeMember', '移除租户成员')} onCancel={closeRemoval}
      closable={!operation.busy} keyboard={!operation.busy}
      footer={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[
        {key: 'cancel', sort: 0, text: t('action.cancel', '取消'), disabled: operation.busy, onClick: closeRemoval},
        {key: 'remove', sort: 1, danger: true, text: t('platform-accounts.removeMember', '移除租户成员'),
          loading: operation.busy, disabled: !canManage, onClick: () => void confirmRemove()},
      ]}/>}>
      <p>{t('platform-accounts.removeBoundary', '只移除当前租户成员关系和租户角色，不删除全局账号或影响其他租户。')}</p>
      <p>{removal.name || removal.email || removal.userId}</p>
      {writeError && <Alert className="tenant-member-write-error" type="error" showIcon title={writeError}/>}
    </Modal>}
    <ResourceDrawer rootClassName="tenant-member-role-drawer" open={!!roleUser} locked={roleLocked}
      onClose={() => {if (!operation.current() || !operation.isSelected() || roleLockRef.current) return;
        roleRef.current = undefined; setRoleUser(undefined);}}
      defaultHeight={typeof window === 'undefined' ? 700 : Math.min(760, Math.round(window.innerHeight * .78))}
      minHeight={320} title={t('users.button.config.role', '配置角色')}>
      {roleUser && <RoleConfig key={roleUser} userId={roleUser} disabled={!canConfigureRoles} onInteractionLockChange={roleInteraction}/>}
    </ResourceDrawer>
  </div>;
}
