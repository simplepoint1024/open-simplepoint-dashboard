import api from '@/api/index';
import SimpleTable from '@simplepoint/components/SimpleTable';
import ResourceDrawer from '@simplepoint/components/ResourceDrawer';
import TableActions from '@simplepoint/components/Table/Actions';
import {hasRecordIdentity} from '@simplepoint/components/SimpleTable/recordIdentity';
import {useCallback, useEffect, useMemo, useRef, useState, type Key} from 'react';
import {Alert, Avatar, Space} from 'antd';
import {ApartmentOutlined, UserOutlined} from '@simplepoint/shared/types/icon';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useOperationScope} from '@simplepoint/shared/hooks/useOperationScope';
import {useSchema} from '@simplepoint/shared/hooks/useSchema';
import {get, useData} from '@simplepoint/shared/api/methods';
import PackageConfig from './config/package';
import UserConfig from './config/user';
import type {TableButtonProps} from '@simplepoint/components/Table';

const baseConfig = api['platform.tenants'];
type TenantRow = {id: string; name?: string; ownerId?: string};
type Configuration = {kind: 'package' | 'user'; row: TenantRow};
const readable = (value: unknown): value is string => typeof value === 'string' && !!value.trim() && !/^\*+$/.test(value.trim());

function TenantSession() {
  const operation = useOperationScope();
  const [configuration, setConfiguration] = useState<Configuration>();
  const [selectedRow, setSelectedRow] = useState<TenantRow>();
  const [formOpen, setFormOpen] = useState(false);
  const [childLocked, setChildLocked] = useState(false);
  const childLock = useRef(false);
  const configurationLock = useRef<Configuration | undefined>(undefined);
  const rootLock = useRef(false);
  const {t, ensure, locale} = useI18n();
  const permissions = useSchema(baseConfig.baseUrl, {staleTime: 30_000, retry: false});
  const selectedId = selectedRow?.id;
  const capability = useData([baseConfig.baseUrl, 'member-management-capability', selectedId], ({signal}) =>
    get<{canConfigureMembers: boolean}>(baseConfig.baseUrl + '/users/capabilities', {tenantId: selectedId}, {signal, notifyOnError: false}),
    {enabled: !!selectedId && !configuration && !formOpen && !!permissions.data && !permissions.error,
      staleTime: 30_000, gcTime: 120_000, retry: false});
  useEffect(() => {void ensure([...baseConfig.i18nNamespaces, 'packages', 'users', 'table', 'common']);}, [ensure, locale]);
  const interactionLock = useCallback((locked: boolean) => {childLock.current = locked; setChildLocked(locked);}, []);
  const rootInteraction = useCallback((locked: boolean) => {rootLock.current = locked;}, []);
  const close = useCallback(() => {
    if (!operation.current() || !operation.isSelected() || childLock.current) return;
    configurationLock.current = undefined; setConfiguration(undefined); interactionLock(false);
  }, [operation.current, operation.isSelected, interactionLock]);
  const selectionChanged = useCallback((_keys: Key[], rows: TenantRow[]) => {
    setSelectedRow(rows.length === 1 && hasRecordIdentity(rows[0]) && readable(rows[0].id) ? rows[0] : undefined);
  }, []);
  const open = useCallback((kind: Configuration['kind'], rows: TenantRow[]) => {
    if (!operation.current() || !operation.isSelected() || configurationLock.current || rootLock.current || formOpen || childLock.current || rows.length !== 1
      || !hasRecordIdentity(rows[0]) || !readable(rows[0].id) || permissions.error || !permissions.data) return;
    if (kind === 'user' ? capability.error || capability.isFetching || capability.data?.canConfigureMembers !== true || selectedId !== rows[0].id
      : !permissions.data.buttons.some(button => button.key === 'config.package')) return;
    const captured = {kind, row: structuredClone(rows[0])}; configurationLock.current = captured; setConfiguration(captured);
  }, [operation.current, operation.isSelected, configuration, formOpen, permissions.data, permissions.error,
    capability.data, capability.error, capability.isFetching, selectedId]);
  const customButtonEvents = useMemo(() => ({
    'config.package': (_keys: Key[], rows: TenantRow[]) => open('package', rows),
    'config.user': (_keys: Key[], rows: TenantRow[]) => open('user', rows),
  }), [open]);
  // Member management uses the selected tenant's actual owner/admin policy, not a fictional resource grant.
  const customButtons = useMemo<TableButtonProps[]>(() => capability.data?.canConfigureMembers === true && !capability.error ? [{
    key: 'config.user', title: 'i18n:tenants.button.config.user', text: 'i18n:tenants.button.config.user',
    icon: 'TeamOutlined', color: 'orange', sort: 4, argumentMinSize: 1, argumentMaxSize: 1,
  }] : [], [capability.data, capability.error]);
  const formUiSchema = useMemo(() => ({ownerName: {'ui:widget': 'hidden'}, ownerGender: {'ui:widget': 'hidden'},
    ownerPhoneNumber: {'ui:widget': 'hidden'}, ownerEmail: {'ui:widget': 'hidden'}}), []);
  const columnOverrides = useMemo(() => ({
    logo: {order: -100, width: 76, align: 'center' as const, sorter: false,
      render: (value?: string) => <Avatar shape="square" size={34} src={value} icon={!value ? <ApartmentOutlined/> : undefined}/>},
    ownerName: {width: 180, render: (value: string | undefined, row: any) => <Space size={8}>
      <Avatar size={28} src={row?.ownerPicture} icon={!row?.ownerPicture ? <UserOutlined/> : undefined}/>
      <span>{value || row?.ownerId || '-'}</span>
    </Space>},
    ownerGender: {width: 100, sorter: false}, ownerPhoneNumber: {width: 150, sorter: false}, ownerEmail: {width: 210, sorter: false},
  }), []);
  return <div style={{height: '100%', minHeight: 0, display: 'flex', flexDirection: 'column'}}>
    {capability.error && selectedId && !configuration && <Alert className="tenant-member-capability-error" type="error" showIcon title={t('table.schemaLoadFail', '页面结构加载失败，请稍后重试。')}
      action={<TableActions selectedKeys={[]} selectedRows={[]} buttons={[{key: 'retryCapability', sort: 0, argumentMinSize: 0,
        argumentMaxSize: 0, text: t('table.retry', '重试'), disabled: capability.isFetching || !operation.selected}]}
        onButtonEvents={{retryCapability: () => {if (operation.isSelected() && operation.current()) void capability.refetch();}}}/>}/>}
    <SimpleTable {...baseConfig} schemaStaleTime={30_000} allowTabEviction={!configuration} confirmDiscard
      customButtons={customButtons} customButtonEvents={customButtonEvents} onSelectionChange={selectionChanged}
      onInteractionLockChange={rootInteraction}
      drawerOpen={formOpen} onDrawerOpenChange={setFormOpen} formUiSchema={formUiSchema} columnOverrides={columnOverrides}
      isButtonDisabled={(button) => !!configurationLock.current || rootLock.current || childLocked || formOpen || !operation.selected || !permissions.data || !!permissions.error
        || button.key === 'config.user' && (capability.isFetching || !!capability.error || capability.data?.canConfigureMembers !== true)}
      rowSelection={{isRowSelectable: () => !configurationLock.current && !rootLock.current && !formOpen}}
      i18nNamespaces={[...baseConfig.i18nNamespaces, 'packages', 'users', 'table', 'common']}/>
    <ResourceDrawer rootClassName={configuration?.kind === 'package' ? 'tenant-package-config' : 'tenant-user-config'}
      title={configuration?.kind === 'package' ? t('tenants.button.config.package', '配置套餐') : t('tenants.button.config.user', '配置成员')}
      open={!!configuration} locked={childLocked} onClose={close}>
      {configuration?.kind === 'package' ? <PackageConfig tenantId={configuration.row.id} onInteractionLockChange={interactionLock}/>
        : configuration && <UserConfig tenantId={configuration.row.id}
          ownerId={readable(configuration.row.ownerId) ? configuration.row.ownerId : undefined} onInteractionLockChange={interactionLock}/>}
    </ResourceDrawer>
  </div>;
}

export default function Tenant() {return <TenantSession key={JSON.stringify(useQueryScope())}/>;}
