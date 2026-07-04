import {
  Alert,
  Button,
  Empty,
  GetProp,
  Input,
  message,
  Select,
  Space,
  TableColumnsType,
  TableProps,
  Tag,
  TransferProps,
  Typography,
} from 'antd';
import {
  CheckOutlined,
  KeyOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {useCallback, useEffect, useMemo, useState} from 'react';
import STableTransfer from '@simplepoint/components/STableTransfer';
import {useData, usePage} from '@simplepoint/shared/api/methods';
import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import api from '@/api';
import {
  AccessCenterRoleOverview,
  fetchRoleDetail,
  fetchRoleOverviews,
  saveRoleAuthorization,
} from '@/api/system/access-center';
import {fetchItems as fetchDataScopeItems, DataScopeRelevantVo} from '@/api/system/data-scope';
import {fetchItems as fetchFieldScopeItems, FieldScopeRelevantVo} from '@/api/system/field-scope';
import {fetchItems as fetchPermissionItems, fetchSelectedItems, PermissionRelevantVo} from '@/api/system/permission';
import './index.css';

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];

interface TableTransferProps extends TransferProps<TransferItem> {
  dataSource: PermissionRelevantVo[];
  leftColumns: TableColumnsType<PermissionRelevantVo>;
  rightColumns: TableColumnsType<PermissionRelevantVo>;
}

const baseConfig = api['rbac-access-center'];
const ROLE_PAGE_SIZE = 100;

function roleTitle(item?: AccessCenterRoleOverview) {
  const role = item?.role;
  return role?.name || role?.authority || role?.id || '-';
}

function userTitle(user: {name?: string | null; email?: string | null; phoneNumber?: string | null; id?: string}) {
  return user.name || user.email || user.phoneNumber || user.id || '-';
}

const AccessCenter = () => {
  const {t, messages, ensure, locale} = useI18n();
  const [roleSearch, setRoleSearch] = useState('');
  const [selectedRoleId, setSelectedRoleId] = useState<string>('');
  const [leftPage, setLeftPage] = useState({current: 1, pageSize: 10});
  const [rightPage, setRightPage] = useState({current: 1, pageSize: 10});
  const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);
  const [selectedItems, setSelectedItems] = useState<PermissionRelevantVo[]>([]);
  const [dataScopeId, setDataScopeId] = useState<string | null>(null);
  const [fieldScopeId, setFieldScopeId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void ensure(baseConfig.i18nNamespaces);
  }, [ensure, locale]);

  const roleQuery = usePage<AccessCenterRoleOverview>(
    ['accessCenterRoleOverviews'],
    () => fetchRoleOverviews({page: '0', size: String(ROLE_PAGE_SIZE)}),
  );
  const roleRows = roleQuery.data?.content ?? [];

  const filteredRoles = useMemo(() => {
    const keyword = roleSearch.trim().toLowerCase();
    if (!keyword) return roleRows;
    return roleRows.filter((item) => {
      const role = item.role;
      return [role?.name, role?.authority, role?.description]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(keyword));
    });
  }, [roleRows, roleSearch]);

  useEffect(() => {
    if (!filteredRoles.length) {
      setSelectedRoleId('');
      return;
    }
    if (!selectedRoleId || !filteredRoles.some((item) => item.role.id === selectedRoleId)) {
      setSelectedRoleId(filteredRoles[0].role.id);
    }
  }, [filteredRoles, selectedRoleId]);

  const detailQuery = useData(
    selectedRoleId ? ['accessCenterRoleDetail', selectedRoleId] : ['accessCenterRoleDetail', 'empty'],
    () => fetchRoleDetail(selectedRoleId),
    {enabled: !!selectedRoleId},
  );
  const detail = detailQuery.data;

  const selectedAuthorities = useMemo(
    () => (targetKeys ?? []).map((key) => String(key)),
    [targetKeys],
  );

  useEffect(() => {
    setTargetKeys([]);
    setSelectedItems([]);
    setDataScopeId(null);
    setFieldScopeId(null);
    setLeftPage((prev) => ({...prev, current: 1}));
    setRightPage((prev) => ({...prev, current: 1}));
  }, [selectedRoleId]);

  useEffect(() => {
    if (!detail) return;
    setTargetKeys(detail.authorizedPermissions ?? []);
    setDataScopeId(detail.scopeAssignment?.dataScopeId ?? null);
    setFieldScopeId(detail.scopeAssignment?.fieldScopeId ?? null);
  }, [detail]);

  const permissionQuery = usePage<PermissionRelevantVo>(
    ['accessCenterPermissionItems', leftPage.current, leftPage.pageSize],
    () => fetchPermissionItems({page: String(leftPage.current - 1), size: String(leftPage.pageSize)}),
  );
  const permissionContent = permissionQuery.data?.content ?? [];

  const selectedDetailsQuery = useData<PermissionRelevantVo[]>(
    selectedRoleId && selectedAuthorities.length > 0
      ? ['accessCenterSelectedPermissions', selectedRoleId, ...selectedAuthorities]
      : ['accessCenterSelectedPermissions', selectedRoleId || 'empty'],
    () => fetchSelectedItems(selectedAuthorities),
    {enabled: !!selectedRoleId && selectedAuthorities.length > 0},
  );

  useEffect(() => {
    if (!selectedAuthorities.length) {
      setSelectedItems([]);
      return;
    }
    if (!selectedDetailsQuery.data) return;
    const detailMap = new Map(selectedDetailsQuery.data.map((item) => [item.authority, item]));
    setSelectedItems(
      selectedAuthorities
        .map((authority) => detailMap.get(authority))
        .filter((item): item is PermissionRelevantVo => !!item),
    );
  }, [selectedAuthorities, selectedDetailsQuery.data]);

  const dataScopeQuery = useData(['accessCenterDataScopes'], () => fetchDataScopeItems());
  const fieldScopeQuery = useData(['accessCenterFieldScopes'], () => fetchFieldScopeItems());

  const dataScopeOptions = useMemo(
    () =>
      (dataScopeQuery.data?.content ?? []).map((item: DataScopeRelevantVo) => ({
        label: item.name,
        value: item.id,
      })),
    [dataScopeQuery.data],
  );

  const fieldScopeOptions = useMemo(
    () =>
      (fieldScopeQuery.data?.content ?? []).map((item: FieldScopeRelevantVo) => ({
        label: item.name,
        value: item.id,
      })),
    [fieldScopeQuery.data],
  );

  const columns: TableColumnsType<PermissionRelevantVo> = useMemo(
    () => [
      {
        key: 'name',
        dataIndex: 'name',
        title: t('permissions.title.name', '权限名称'),
        width: 160,
        ellipsis: true,
      },
      {
        key: 'authority',
        dataIndex: 'authority',
        title: t('permissions.title.authority', '权限标识'),
        width: 230,
        ellipsis: true,
        render: (value: string) => <Typography.Text code>{value || '-'}</Typography.Text>,
      },
      {
        key: 'description',
        dataIndex: 'description',
        title: t('permissions.title.description', '描述'),
        ellipsis: true,
      },
    ],
    [messages, t],
  );

  const transferDataSource = useMemo(() => {
    const map = new Map<string, PermissionRelevantVo>();
    permissionContent.forEach((item) => map.set(item.authority, item));
    selectedItems.forEach((item) => map.set(item.authority, item));
    return Array.from(map.values());
  }, [permissionContent, selectedItems]);

  const leftPagination: TableProps<PermissionRelevantVo>['pagination'] = {
    current: leftPage.current,
    pageSize: leftPage.pageSize,
    total: permissionQuery.data?.page.totalElements ?? 0,
    showSizeChanger: true,
    showQuickJumper: true,
    onChange: (current, pageSize) => setLeftPage({current, pageSize}),
  };

  const rightPagination: TableProps<PermissionRelevantVo>['pagination'] = {
    current: rightPage.current,
    pageSize: rightPage.pageSize,
    total: selectedItems.length,
    showSizeChanger: true,
    showQuickJumper: true,
    onChange: (current, pageSize) => setRightPage({current, pageSize}),
  };

  const persistAuthorization = useCallback(
    async (nextAuthorities: string[], nextDataScopeId: string | null, nextFieldScopeId: string | null) => {
      if (!selectedRoleId) return undefined;
      setSaving(true);
      try {
        const saved = await saveRoleAuthorization({
          roleId: selectedRoleId,
          permissionAuthorities: nextAuthorities,
          dataScopeId: nextDataScopeId,
          fieldScopeId: nextFieldScopeId,
        });
        setTargetKeys(saved.authorizedPermissions ?? []);
        setDataScopeId(saved.scopeAssignment?.dataScopeId ?? null);
        setFieldScopeId(saved.scopeAssignment?.fieldScopeId ?? null);
        await Promise.all([roleQuery.refetch(), detailQuery.refetch()]);
        message.success(t('accessCenter.message.saved', '授权已保存'));
        return saved;
      } catch (error) {
        message.error(t('accessCenter.message.saveFailed', '授权保存失败'));
        throw error;
      } finally {
        setSaving(false);
      }
    },
    [detailQuery, roleQuery, selectedRoleId, t],
  );

  const handleTransferChange: TableTransferProps['onChange'] = async (nextTargetKeys) => {
    const previousTargetKeys = targetKeys;
    const previousSelectedItems = selectedItems;
    const nextAuthorities = (nextTargetKeys ?? []).map((key) => String(key));

    setTargetKeys(nextTargetKeys);
    setRightPage((prev) => ({...prev, current: 1}));
    const mergedItemMap = new Map<string, PermissionRelevantVo>();
    transferDataSource.forEach((item) => mergedItemMap.set(item.authority, item));
    setSelectedItems(
      nextAuthorities
        .map((authority) => mergedItemMap.get(authority))
        .filter((item): item is PermissionRelevantVo => !!item),
    );

    try {
      await persistAuthorization(nextAuthorities, dataScopeId, fieldScopeId);
    } catch {
      setTargetKeys(previousTargetKeys);
      setSelectedItems(previousSelectedItems);
    }
  };

  const handleDataScopeChange = async (value?: string | null) => {
    const previous = dataScopeId;
    const nextValue = value ?? null;
    setDataScopeId(nextValue);
    try {
      await persistAuthorization(selectedAuthorities, nextValue, fieldScopeId);
    } catch {
      setDataScopeId(previous);
    }
  };

  const handleFieldScopeChange = async (value?: string | null) => {
    const previous = fieldScopeId;
    const nextValue = value ?? null;
    setFieldScopeId(nextValue);
    try {
      await persistAuthorization(selectedAuthorities, dataScopeId, nextValue);
    } catch {
      setFieldScopeId(previous);
    }
  };

  const selectedRole = roleRows.find((item) => item.role.id === selectedRoleId);
  const loading = roleQuery.isFetching || detailQuery.isFetching;
  const transferLoading = permissionQuery.isFetching || selectedDetailsQuery.isFetching || saving;

  return (
    <div className="access-center">
      <aside className="access-center-roles">
        <div className="access-center-toolbar">
          <Typography.Text strong>{t('accessCenter.roleList.title', '角色')}</Typography.Text>
          <Button
            icon={<ReloadOutlined />}
            size="small"
            title={t('common.refresh', '刷新')}
            aria-label={t('common.refresh', '刷新')}
            onClick={() => roleQuery.refetch()}
            loading={roleQuery.isFetching}
          />
        </div>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder={t('accessCenter.roleList.search', '搜索角色')}
          value={roleSearch}
          onChange={(event) => setRoleSearch(event.target.value)}
        />
        <div className="access-center-role-list">
          {filteredRoles.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('table.emptyText', '暂无数据')} />
          ) : (
            filteredRoles.map((item) => {
              const active = item.role.id === selectedRoleId;
              return (
                <button
                  key={item.role.id}
                  type="button"
                  className={`access-center-role-item${active ? ' access-center-role-item-active' : ''}`}
                  onClick={() => setSelectedRoleId(item.role.id)}
                >
                  <span className="access-center-role-name">{roleTitle(item)}</span>
                  <span className="access-center-role-code">{item.role.authority || item.role.id}</span>
                  <span className="access-center-role-meta">
                    <Tag icon={<KeyOutlined />} color="blue">{item.permissionCount}</Tag>
                    <Tag icon={<UserOutlined />} color="green">{item.assignedUserCount}</Tag>
                  </span>
                </button>
              );
            })
          )}
        </div>
      </aside>

      <main className="access-center-main">
        <div className="access-center-main-head">
          <div>
            <Typography.Title level={4}>{roleTitle(selectedRole)}</Typography.Title>
            <Typography.Text type="secondary">{selectedRole?.role.authority || '-'}</Typography.Text>
          </div>
          <Space>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => detailQuery.refetch()}
              loading={loading}
              disabled={!selectedRoleId}
            >
              {t('common.refresh', '刷新')}
            </Button>
          </Space>
        </div>

        {detailQuery.error ? (
          <Alert
            type="error"
            showIcon
            message={t('accessCenter.message.loadFailed', '权限中心加载失败')}
          />
        ) : null}

        <section className="access-center-scope-band">
          <label className="access-center-field">
            <span>{t('roles.permissionConfig.dataScope', '数据权限')}</span>
            <Select
              allowClear
              value={dataScopeId}
              options={dataScopeOptions}
              loading={dataScopeQuery.isFetching}
              disabled={!selectedRoleId || saving}
              placeholder={t('roles.permissionConfig.noDataScope', '不限制数据范围')}
              onChange={handleDataScopeChange}
            />
          </label>
          <label className="access-center-field">
            <span>{t('roles.permissionConfig.fieldScope', '字段权限')}</span>
            <Select
              allowClear
              value={fieldScopeId}
              options={fieldScopeOptions}
              loading={fieldScopeQuery.isFetching}
              disabled={!selectedRoleId || saving}
              placeholder={t('roles.permissionConfig.noFieldScope', '不限制字段范围')}
              onChange={handleFieldScopeChange}
            />
          </label>
          <div className="access-center-save-state">
            {saving ? t('roles.permissionConfig.saving', '保存中...') : t('accessCenter.state.synced', '已同步')}
          </div>
        </section>

        <section className="access-center-transfer">
          {selectedRoleId ? (
            <STableTransfer
              dataSource={transferDataSource}
              targetKeys={targetKeys}
              showSelectAll={false}
              disabled={transferLoading}
              loading={transferLoading}
              onChange={handleTransferChange}
              leftColumns={columns}
              rightColumns={columns}
              itemKey="authority"
              adaptiveHeight
              searchable
              titles={[
                t('roles.permissionConfig.availablePermissions', '可选权限'),
                t('roles.permissionConfig.authorizedPermissions', '已授权权限'),
              ]}
              operations={[
                t('roles.permissionConfig.assign', '授权'),
                t('roles.permissionConfig.revoke', '移除'),
              ]}
              locale={{
                itemUnit: t('roles.permissionConfig.itemUnit', '项'),
                itemsUnit: t('roles.permissionConfig.itemsUnit', '项'),
                searchPlaceholder: t('roles.permissionConfig.searchPlaceholder', '搜索权限'),
                notFoundContent: t('table.emptyText', '暂无数据'),
              }}
              leftPagination={leftPagination}
              rightPagination={rightPagination}
            />
          ) : (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('accessCenter.empty.role', '请选择角色')} />
          )}
        </section>
      </main>

      <aside className="access-center-impact">
        <div className="access-center-impact-head">
          <Typography.Text strong>{t('accessCenter.impact.title', '影响范围')}</Typography.Text>
          <SafetyCertificateOutlined />
        </div>
        <div className="access-center-metric-grid">
          <div className="access-center-metric">
            <KeyOutlined />
            <span>{selectedAuthorities.length}</span>
            <small>{t('accessCenter.metric.permissions', '权限')}</small>
          </div>
          <div className="access-center-metric">
            <TeamOutlined />
            <span>{detail?.assignedUserCount ?? 0}</span>
            <small>{t('accessCenter.metric.users', '用户')}</small>
          </div>
        </div>
        <div className="access-center-impact-section">
          <Typography.Text type="secondary">{t('roles.permissionConfig.dataScope', '数据权限')}</Typography.Text>
          <div>{detail?.dataScope?.name || t('roles.permissionConfig.noDataScope', '不限制数据范围')}</div>
        </div>
        <div className="access-center-impact-section">
          <Typography.Text type="secondary">{t('roles.permissionConfig.fieldScope', '字段权限')}</Typography.Text>
          <div>{detail?.fieldScope?.name || t('roles.permissionConfig.noFieldScope', '不限制字段范围')}</div>
        </div>
        <div className="access-center-impact-section access-center-user-impact">
          <Typography.Text type="secondary">{t('accessCenter.impact.users', '已分配用户')}</Typography.Text>
          {(detail?.assignedUsers ?? []).length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('table.emptyText', '暂无数据')} />
          ) : (
            detail?.assignedUsers.map((user) => (
              <div key={user.id} className="access-center-user-row">
                <span className="access-center-user-avatar"><UserOutlined /></span>
                <span>
                  <strong>{userTitle(user)}</strong>
                  <small>{user.email || user.phoneNumber || user.id}</small>
                </span>
              </div>
            ))
          )}
        </div>
        <div className="access-center-impact-section access-center-status-line">
          <CheckOutlined />
          <span>{t('accessCenter.impact.auditReady', '变更会写入权限审计')}</span>
        </div>
      </aside>
    </div>
  );
};

export default AccessCenter;
