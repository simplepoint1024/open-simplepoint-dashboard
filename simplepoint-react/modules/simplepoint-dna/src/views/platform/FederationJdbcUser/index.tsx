import api from '@/api';
import SimpleTable from '@simplepoint/components/SimpleTable';
import EntitySelect, {type EntitySelectSource} from '@simplepoint/components/EntitySelect';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {Drawer, Modal, Space, Tag} from 'antd';
import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import DataSourceConfig from './config/dataSource';

const baseConfig = api['platform.dna-federation-jdbc-users'];
const endpoint = baseConfig.baseUrl;

type Grant = {
  id: string;
  userId: string;
  userDisplayName?: string;
  userEmail?: string;
  userPhoneNumber?: string;
  catalogName?: string;
  operationPermissions?: string[];
  enabled?: boolean;
};
type UserOption = {id: string; name?: string; email?: string; phoneNumber?: string};

const userLabel = (user: UserOption) => {
  const primary = user.name || user.email || user.phoneNumber || user.id;
  const secondary = [user.email, user.phoneNumber].filter(value => value && value !== primary);
  return secondary.length ? `${primary} · ${secondary.join(' · ')}` : primary;
};

function FederationJdbcUserSession() {
  const {t, ensure, locale} = useI18n();
  const {data: tableSchema} = useSchema(endpoint);
  const [revision, setRevision] = useState(0);
  const [selectedUser, setSelectedUser] = useState<UserOption>();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickedUser, setPickedUser] = useState<UserOption>();
  const [assignmentBusy, setAssignmentBusy] = useState(false);
  const assignmentBusyRef = useRef(false);
  const userSource = useMemo<EntitySelectSource>(() => ({key: 'dna-jdbc-user-options', minSearchLength: 3,
    searchParams: (keyword, page, size) => ({keyword, page, size}),
    option: row => typeof row.id === 'string' && row.id
      ? {value: row.id, label: userLabel(row as UserOption)} : undefined,
    // This create-only directory supports prefix searches, not lookup by arbitrary user ID.
    // Selected labels are retained by EntitySelect; an unknown persisted ID must not trigger a broad search.
    resolve: async () => [],
  }), []);

  useEffect(() => { void ensure(baseConfig.i18nNamespaces); }, [ensure, locale]);

  const actions = tableSchema?.buttons ?? [];
  const canCreate = actions.some(action => action.key === 'add');
  const canEdit = actions.some(action => action.key === 'edit');
  const canDelete = actions.some(action => action.key === 'delete');

  const openConfiguration = useCallback((user: UserOption) => {
    setSelectedUser(user);
    setDrawerOpen(true);
  }, []);

  const schemaTransform = useCallback((schema: any) => {
    const next = structuredClone(schema ?? {});
    const visible = new Set(['userDisplayName', 'userEmail', 'userPhoneNumber',
      'catalogName', 'operationPermissions', 'enabled']);
    for (const [key, property] of Object.entries(next.properties ?? {}) as Array<[string, any]>) {
      property['x-ui'] = {...property['x-ui'], 'x-list-visible': visible.has(key)};
    }
    return next;
  }, []);

  const permissionLabel = useCallback((operation: string) => {
    switch (operation) {
      case 'METADATA': return t('dna.federation.jdbcUsers.page.permission.METADATA', '元数据');
      case 'QUERY': return t('dna.federation.jdbcUsers.page.permission.QUERY', '查询');
      case 'EXPLAIN': return t('dna.federation.jdbcUsers.page.permission.EXPLAIN', '执行计划');
      case 'DDL': return t('dna.federation.jdbcUsers.page.permission.DDL', 'DDL');
      case 'DML': return t('dna.federation.jdbcUsers.page.permission.DML', 'DML');
      default: return operation;
    }
  }, [t]);

  const columns = useMemo(() => ({
    userDisplayName: {width: 180, render: (value?: string) => value || '—'},
    userEmail: {width: 200, render: (value?: string) => value || '—'},
    userPhoneNumber: {width: 150, render: (value?: string) => value || '—'},
    catalogName: {width: 200, render: (value?: string) => value || '—'},
    operationPermissions: {width: 250, render: (values?: string[]) =>
      <Space size={[4, 4]} wrap>{(values ?? []).map(value =>
        <Tag key={value} style={{marginInlineEnd: 0}}>{permissionLabel(value)}</Tag>)}</Space>},
    enabled: {width: 110, render: (value?: boolean) =>
      <Tag color={value === false ? 'default' : 'green'}>{value === false
        ? t('dna.federation.jdbcUsers.page.state.disabled', '已禁用')
        : t('dna.federation.jdbcUsers.page.state.enabled', '已启用')}</Tag>},
  }), [permissionLabel, t]);

  return <>
    <SimpleTable<Grant> {...baseConfig} queryContext={String(revision)} virtual={false}
      schemaTransform={schemaTransform} columnOverrides={columns}
      isButtonDisabled={(button, _keys, rows) => button.key === 'edit' && !rows[0]?.userId}
      customButtonEvents={{
        add: () => {if (canCreate) {setPickedUser(undefined); setPickerOpen(true);}},
        edit: (_keys, rows) => {if (rows[0]) openConfiguration({id: rows[0].userId,
          name: rows[0].userDisplayName, email: rows[0].userEmail,
          phoneNumber: rows[0].userPhoneNumber});},
      }}/>

    <Modal title={t('dna.federation.jdbcUsers.page.button.addUser', '添加连接用户')}
      open={pickerOpen} destroyOnHidden okText={t('dna.federation.jdbcUsers.page.button.assignDataSources', '分配数据源')}
      okButtonProps={{disabled: !pickedUser || !canCreate}}
      onCancel={() => setPickerOpen(false)}
      onOk={() => {
        if (!pickedUser || !canCreate) return;
        setPickerOpen(false);
        openConfiguration(pickedUser);
      }}>
      <EntitySelect endpoint={`${endpoint}/user-options`} source={userSource} disabled={!canCreate}
        placeholder={t('dna.federation.jdbcUsers.page.search.userPlaceholder', '输入邮箱或手机号前缀（至少 3 位）')}
        value={pickedUser?.id} onChange={(value, option) => setPickedUser(value && option ? {id: value, name: option.label} : undefined)}/>
    </Modal>

    <Drawer rootClassName="sp-simple-table-drawer"
      title={selectedUser ? t('dna.federation.jdbcUsers.page.drawer.title',
        '分配数据源 - {user}', {user: userLabel(selectedUser)}) : undefined}
      open={drawerOpen} onClose={() => {
        if (!assignmentBusyRef.current) {setDrawerOpen(false); setSelectedUser(undefined);}
      }} closable={!assignmentBusy} maskClosable={!assignmentBusy} keyboard={!assignmentBusy}
      width={900} destroyOnHidden>
      <DataSourceConfig key={selectedUser?.id} userId={selectedUser?.id ?? null}
        canAuthorize={canCreate || canEdit} canEditPermissions={canEdit}
        canUnauthorize={canEdit || canDelete}
        onBusyChange={busy => {assignmentBusyRef.current = busy; setAssignmentBusy(busy);}}
        onChanged={() => setRevision(value => value + 1)}/>
    </Drawer>
  </>;
}

export default function FederationJdbcUser() {
  const scope = useQueryScope();
  return <FederationJdbcUserSession key={JSON.stringify(scope)}/>;
}
