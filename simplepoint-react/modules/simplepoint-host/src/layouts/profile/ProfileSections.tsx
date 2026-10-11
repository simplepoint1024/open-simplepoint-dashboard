import {resolveResourceLabel} from '@simplepoint/shared/i18n/resourceLabel';
import {useEffect, useMemo, useState} from 'react';
import {FileOutlined, FolderOutlined} from '@ant-design/icons';
import {Alert, Drawer, Empty, Input, Pagination, Spin, Tag, Typography} from 'antd';
import TableActions from '@simplepoint/components/Table/Actions';
import {hasRecordIdentity} from '@simplepoint/components/SimpleTable/recordIdentity';
import {usePageSelected} from '@simplepoint/shared/page/hooks';
import {ApartmentOutlined, ApiOutlined, AppstoreOutlined,
  SafetyCertificateOutlined, createIcon} from '@simplepoint/shared/types/icon';
import {useQueryScope} from '@simplepoint/shared/hooks/useQueryScope';
import {useI18n} from '@/layouts/i18n/useI18n.ts';
import {useCurrentRoles, useCurrentTenants} from '@/fetches/tenants.ts';
import {useProfileOrganization, useProfilePageDetail, useProfileResources} from '@/fetches/profileAccess.ts';
import type {ProfileResourceAccess} from '@/fetches/profileAccess.ts';

function permissionIcon(row: ProfileResourceAccess) {
  const configured = row.icon && createIcon(row.icon);
  if (configured) return configured;
  if (row.type === 'API') return <ApiOutlined/>;
  if (row.type === 'ACTION' || row.type === 'FEATURE') return <AppstoreOutlined/>;
  if (row.type === 'GROUP' || row.type === 'MODULE') return <FolderOutlined/>;
  return <FileOutlined/>;
}

export function ProfileAccessSection() {
  const scope = useQueryScope();
  return <ProfileAccessSession key={JSON.stringify(scope)}/>;
}
function ProfileAccessSession() {
  const {t, ensure} = useI18n();
  const selected = usePageSelected();
  const retry = (refetch: () => unknown) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
    {key: 'refresh', sort: 0, size: 'small', text: t('action.refresh', '刷新'), onClick: () => {void refetch();}},
  ]}/>;
  useEffect(() => {void ensure(['resources', 'table', 'common', 'permissions']);}, [ensure]);
  const label = (row: ProfileResourceAccess) => resolveResourceLabel(row.label === row.code ? undefined : row.label ?? undefined, t('permissions.unavailable', '名称暂不可用'), t);
  const directoryLabel = (directory?: string[]) => directory?.length
    ? directory.map(entry => resolveResourceLabel(entry, t('permissions.unavailable', '名称暂不可用'), t)).join(' / ')
    : t('profile.access.rootDirectory', '顶级页面');
  const scope = useQueryScope();
  const tenantId = scope[0] ?? undefined;
  const roles = useCurrentRoles(tenantId === '__platform__' ? undefined : tenantId);
  const [roleId, setRoleId] = useState<string>();
  const [searchInput, setSearchInput] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(1);
  const [selectedCode, setSelectedCode] = useState<string>();
  const [operationPage, setOperationPage] = useState(1);
  useEffect(() => {setRoleId(undefined); setSearchInput(''); setKeyword(''); setPage(1); setSelectedCode(undefined);}, [tenantId]);
  useEffect(() => {
    if (roleId && roles.data && !roles.data.some(role => role.roleId === roleId)) setRoleId(undefined);
  }, [roleId, roles.data]);
  const query = useProfileResources({roleId, keyword, type: 'PAGE', page: page - 1, size: 24}, true);
  const detail = useProfilePageDetail(selectedCode, roleId, !!selectedCode);
  const roleName = roles.data?.find(role => role.roleId === roleId)?.roleName;
  const typeLabels = useMemo(() => ({
    GROUP: t('resources.type.GROUP', '目录'),
    MODULE: t('resources.type.MODULE', '模块'),
    PAGE: t('resources.type.PAGE', '页面'),
    FEATURE: t('profile.access.featureType', '资源'),
    ACTION: t('resources.type.ACTION', '操作'),
    API: t('resources.type.API', '接口'),
    OTHER: t('profile.access.otherType', '其他权限'),
  }), [t]);
  return <section className="profile-content-section" aria-label={t('profile.nav.access', '资源权限')}>
    <div className="profile-role-picker" role="tablist" aria-label={t('profile.access.view', '权限视图')}>
      <button type="button" role="tab" aria-selected={!roleId} className={!roleId ? 'active' : ''}
        onClick={() => {setRoleId(undefined); setPage(1); setSelectedCode(undefined);}}>
        <SafetyCertificateOutlined/> {t('profile.access.effective', '当前生效')}
      </button>
      {tenantId !== '__platform__' && roles.data?.map(role => <button type="button" role="tab" aria-selected={roleId === role.roleId}
        key={role.roleId} className={roleId === role.roleId ? 'active' : ''}
        onClick={() => {setRoleId(role.roleId); setPage(1); setSelectedCode(undefined);}}>
        {resolveResourceLabel(role.roleName, t('permissions.unavailable', '名称暂不可用'), t)}
      </button>)}
    </div>
    {tenantId !== '__platform__' && roles.isError && <Alert type="warning" showIcon title={t('profile.access.rolesFailed', '角色列表加载失败')}
      action={retry(roles.refetch)}/>}
    <div className="profile-access-content">
      <div className="profile-access-toolbar">
        <div><Typography.Title level={5}>{roleId ? resolveResourceLabel(roleName, t('permissions.unavailable', '名称暂不可用'), t) : t('profile.access.effective', '当前生效')}
          <Tag color="blue" style={{marginInlineStart: 8}}>{t('profile.access.count', '共 {count} 项', {count: query.data?.page.totalElements ?? 0})}</Tag></Typography.Title>
          <Typography.Text type="secondary">{roleId
            ? t('profile.access.roleHint', '此处是该角色配置的授权；标记会说明它是否在当前会话生效。')
            : t('profile.access.effectiveHint', '包含角色、公共资源和平台身份在当前工作空间形成的权限。')}</Typography.Text>
          <Typography.Text type="secondary">{t('profile.access.pageOnlyHint', '仅展示页面资源；点击页面可查看其操作、数据和字段权限。')}</Typography.Text></div>
        <div className="profile-access-filters">
          <Input.Search allowClear value={searchInput}
            placeholder={t('profile.access.search', '搜索资源名称或编码')}
            onChange={event => {setSearchInput(event.target.value); if (!event.target.value) {setKeyword(''); setPage(1);}}}
            onSearch={value => {setKeyword(value.trim()); setPage(1);}}/>
        </div>
      </div>
      {query.isLoading && <div className="profile-access-loading"><Spin/></div>}
      {query.isError && <Alert type="error" showIcon title={t('profile.access.loadFailed', '资源权限加载失败')}
        action={retry(query.refetch)}/>}
      {!query.isLoading && !query.isError && (query.data?.content.length ?? 0) === 0 &&
        <Empty description={t('profile.access.empty', '当前筛选条件下没有资源权限')}/>}
      {!!query.data?.content.length && <div className="profile-permission-grid">
        {query.data.content.map((row, index) => <button type="button" className="profile-permission-item"
          key={hasRecordIdentity({id: row.code}) ? row.code! : `unavailable-${page}-${index}`} disabled={!hasRecordIdentity({id: row.code})}
          onClick={() => {if (hasRecordIdentity({id: row.code})) {setOperationPage(1); setSelectedCode(row.code!);}}}
          aria-label={t('profile.access.openDetail', '查看 {name} 的权限详情', {name: label(row)})}>
          <div className="profile-permission-icon">{permissionIcon(row)}</div>
          <div className="profile-permission-copy">
            <div className="profile-permission-top"><strong>{label(row)}</strong>
              <Tag>{typeLabels[row.type as keyof typeof typeLabels] || typeLabels.OTHER}</Tag></div>
            {roleId && <Tag className="profile-permission-status" color={row.effective ? 'success' : 'default'}>
              {row.effective ? t('profile.access.active', '当前会话生效') : t('profile.access.inactive', '当前会话未生效')}
            </Tag>}
            {!row.available && <Typography.Text type="secondary" className="profile-permission-unavailable">
              {roleId ? t('profile.access.unavailable', '资源当前不可用或没有展示元数据')
                : t('profile.access.systemCode', '系统权限编码')}
            </Typography.Text>}
          </div>
          <span className="profile-permission-directory"><FolderOutlined/>
            {directoryLabel(row.directory)}
          </span>
        </button>)}
      </div>}
      {(query.data?.page.totalElements ?? 0) > 24 && <Pagination className="profile-access-pagination"
        current={page} pageSize={24} total={query.data?.page.totalElements ?? 0}
        showSizeChanger={false} onChange={setPage}/>}
    </div>
    <Drawer open={!!selectedCode && selected} onClose={() => setSelectedCode(undefined)} width={620}
      title={detail.data?.page ? label(detail.data.page) : t('profile.access.detail', '页面权限详情')} destroyOnHidden>
      {detail.isLoading && <div className="profile-access-loading"><Spin/></div>}
      {detail.isError && <Alert type="error" showIcon title={t('profile.access.detailFailed', '权限详情加载失败')}
        action={retry(detail.refetch)}/>}
      {detail.data && <div className="profile-access-detail">
        <div className="profile-access-detail-header">
          <Typography.Text type="secondary"><FolderOutlined/> {directoryLabel(detail.data.page.directory)}</Typography.Text></div>
        <div className="profile-access-detail-section"><h3>{t('profile.access.operations', '操作权限')}
          <Tag>{detail.data.operations.length}</Tag></h3>
          {detail.data.operations.length ? <div className="profile-access-operations">
            {detail.data.operations.slice((operationPage - 1) * 50, operationPage * 50).map((operation, index) => <div className="profile-access-operation"
              key={hasRecordIdentity({id: operation.code}) ? operation.code! : `unavailable-operation-${operationPage}-${index}`}>
              <span className="profile-permission-icon">{permissionIcon(operation)}</span>
              <span><strong>{label(operation)}</strong></span>
              <Tag>{typeLabels[operation.type as keyof typeof typeLabels] || typeLabels.OTHER}</Tag>
              {roleId && <Tag color={operation.effective ? 'success' : 'default'}>{operation.effective
                ? t('profile.access.active', '当前会话生效') : t('profile.access.inactive', '当前会话未生效')}</Tag>}
            </div>)}</div> : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={t('profile.access.noOperations', '该页面没有已授权的操作资源')}/>}
          {detail.data.operations.length > 50 && <Pagination current={operationPage} pageSize={50}
            total={detail.data.operations.length} showSizeChanger={false} onChange={setOperationPage}/>}
        </div>
        <div className="profile-access-detail-section"><h3>{t('profile.access.data', '数据权限')}</h3>
          <Typography.Text type="secondary">{detail.data.dataPermissionSupported
            ? t('profile.access.noPolicy', '暂无已配置规则') : t('profile.access.policyPending', '规则能力暂未开放')}</Typography.Text></div>
        <div className="profile-access-detail-section"><h3>{t('profile.access.fields', '字段权限')}</h3>
          <Typography.Text type="secondary">{detail.data.fieldPermissionSupported
            ? t('profile.access.noPolicy', '暂无已配置规则') : t('profile.access.policyPending', '规则能力暂未开放')}</Typography.Text></div>
      </div>}
    </Drawer>
  </section>;
}

export function ProfileOrganizationSection() {
  const {t, ensure} = useI18n();
  useEffect(() => {void ensure(['permissions']);}, [ensure]);
  const organizationLabel = (name: string | null | undefined) => resolveResourceLabel(name, t('permissions.unavailable', '名称暂不可用'), t);
  const retry = (refetch: () => unknown) => <TableActions selectedKeys={[]} selectedRows={[]} buttons={[
    {key: 'refresh', sort: 0, size: 'small', text: t('action.refresh', '刷新'), onClick: () => {void refetch();}},
  ]}/>;
  const scope = useQueryScope();
  const tenants = useCurrentTenants();
  const organization = useProfileOrganization(true);
  const activeTenant = tenants.data?.find(tenant => tenant.tenantId === scope[0]);
  return <section className="profile-content-section" aria-label={t('profile.nav.organization', '所在组织')}>
    <div className="profile-organization-grid">
      <div className="profile-organization-group"><h3 className="profile-group-title"><ApartmentOutlined/> {t('profile.organization.current', '当前工作空间')}</h3>
        <Typography.Title level={4}>{activeTenant?.tenantName ||
          (scope[0] === '__platform__' ? t('profile.organization.platform', '平台工作空间') : '-')}</Typography.Title>
        <Typography.Text type="secondary">{activeTenant?.tenantType === 'PERSONAL'
          ? t('profile.organization.personal', '个人工作空间')
          : activeTenant?.tenantType === 'ORGANIZATION' ? t('profile.organization.tenant', '组织工作空间')
            : t('profile.organization.platform', '平台工作空间')}</Typography.Text>
      </div>
      <div className="profile-organization-group"><h3 className="profile-group-title"><ApartmentOutlined/> {t('profile.organization.department', '所属部门')}</h3>
        {organization.isLoading ? <Spin/> : organization.isError
          ? <Alert type="error" title={t('profile.organization.loadFailed', '组织信息加载失败')}
            action={retry(organization.refetch)}/>
          : organization.data && (organization.data.name || organization.data.path.length > 0) ? <>
            <Typography.Title level={4}>{organizationLabel(organization.data.name)}</Typography.Title>
            <Typography.Text type="secondary">{organization.data.path.map(organizationLabel).join(' / ')}</Typography.Text>
          </> : <Typography.Text type="secondary">{scope[0] === '__platform__'
            ? t('profile.organization.noPlatformDepartment', '平台工作空间不关联租户部门')
            : t('profile.organization.unassigned', '当前工作空间尚未分配部门')}</Typography.Text>}
      </div>
    </div>
    <div className="profile-workspaces-group"><h3 className="profile-group-title">{t('profile.organization.workspaces', '可访问工作空间')}</h3>
      {tenants.isLoading ? <Spin/> : tenants.isError
        ? <Alert type="error" title={t('profile.organization.tenantsFailed', '工作空间加载失败')}
          action={retry(tenants.refetch)}/>
        : !tenants.data?.length ? <Empty description={t('profile.organization.empty', '暂无可访问工作空间')}/>
          : <div className="profile-workspace-list">{tenants.data.map(tenant =>
            <div className="profile-workspace-item" key={tenant.tenantId}>
              <span className="profile-workspace-icon"><ApartmentOutlined/></span>
              <span><strong>{tenant.tenantName}</strong><small>{tenant.tenantType === 'PERSONAL'
                ? t('profile.organization.personal', '个人工作空间')
                : tenant.tenantType === 'PLATFORM' ? t('profile.organization.platform', '平台工作空间')
                  : t('profile.organization.tenant', '组织工作空间')}</small></span>
              {tenant.tenantId === scope[0] && <Tag color="blue">{t('profile.organization.active', '当前')}</Tag>}
            </div>)}</div>}
    </div>
  </section>;
}
