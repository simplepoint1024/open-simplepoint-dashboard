import { useI18n } from '@simplepoint/shared/hooks/useI18n';
import { useEffect, useMemo, useState } from 'react';
import { Alert, GetProp, message, Select, TableColumnsType, TableProps, TransferProps, Typography } from 'antd';
import STableTransfer from '@simplepoint/components/STableTransfer';
import { useData, usePage } from '@simplepoint/shared/api/methods';
import { fetchItems, fetchSelectedItems, PermissionRelevantVo } from '@/api/system/permission.ts';
import {
    fetchAuthorize,
    fetchAuthorized,
    fetchScopeAssignment,
    fetchUnauthorized,
    updateScopeAssignment,
} from '@/api/system/role.ts';
import { fetchItems as fetchDataScopeItems, DataScopeRelevantVo } from '@/api/system/data-scope.ts';
import { fetchItems as fetchFieldScopeItems, FieldScopeRelevantVo } from '@/api/system/field-scope.ts';
import './index.css';

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];

export interface RoleSelectProps {
    roleId: string;
}

interface TableTransferProps extends TransferProps<TransferItem> {
    dataSource: PermissionRelevantVo[];
    leftColumns: TableColumnsType<PermissionRelevantVo>;
    rightColumns: TableColumnsType<PermissionRelevantVo>;
}

const App = ({ roleId }: RoleSelectProps) => {
    const { t, messages, ensure, locale } = useI18n();

    const [leftPage, setLeftPage] = useState({ current: 1, pageSize: 10 });
    const [rightPage, setRightPage] = useState({ current: 1, pageSize: 10 });

    const [dataScopeId, setDataScopeId] = useState<string | null>(null);
    const [fieldScopeId, setFieldScopeId] = useState<string | null>(null);
    const [scopeSaving, setScopeSaving] = useState(false);
    const [movingPermissions, setMovingPermissions] = useState(false);

    useEffect(() => {
        void ensure(['roles', 'permissions', 'data-scopes', 'field-scopes', 'table', 'common']);
    }, [ensure, locale]);

    /** 1. 获取权限列表 */
    const { data: page, isFetching: permissionPageLoading, error: permissionPageError } = usePage(
        ['rolePermissionItems', leftPage.current, leftPage.pageSize],
        () => fetchItems({ page: String(leftPage.current - 1), size: String(leftPage.pageSize) })
    );
    const content = page?.content ?? [];

    /** 2. 列定义（依赖 messages 才能在语言切换时立即更新） */
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
                width: 220,
                ellipsis: true,
                render: (value: string) => (
                    <Typography.Text code className="role-permission-code">
                        {value || '-'}
                    </Typography.Text>
                ),
            },
            {
                key: 'description',
                dataIndex: 'description',
                title: t('permissions.title.description', '描述'),
                ellipsis: true,
            },
        ],
        [messages, t]
    );

    /** 3. 穿梭框状态 */
    const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);
    const [selectedItems, setSelectedItems] = useState<PermissionRelevantVo[]>([]);
    const selectedAuthorities = useMemo(
        () => (targetKeys ?? []).map((key) => String(key)),
        [targetKeys]
    );

    /** 4. 获取已分配权限 */
    const { data: authorized, isFetching: authorizedLoading, error: authorizedError } = useData<string[]>(
        roleId ? ['roleAuthorizedPermissions', roleId] : '',
        () => fetchAuthorized(roleId),
        { enabled: !!roleId }
    );

    const { data: selectedDetails, isFetching: selectedDetailsLoading } = useData<PermissionRelevantVo[]>(
        roleId && selectedAuthorities.length > 0
            ? ['roleSelectedPermissionItems', roleId, ...selectedAuthorities]
            : ['roleSelectedPermissionItems', roleId ?? 'empty'],
        () => fetchSelectedItems(selectedAuthorities),
        { enabled: !!roleId && selectedAuthorities.length > 0 }
    );

    /** 5. 获取当前角色的数据权限/字段权限分配 */
    const { data: scopeAssignment, isFetching: scopeAssignmentLoading, error: scopeAssignmentError } = useData(
        roleId ? ['roleScopeAssignment', roleId] : '',
        () => fetchScopeAssignment(roleId),
        { enabled: !!roleId }
    );

    /** 6. 获取数据权限下拉列表 */
    const { data: dataScopePage, isFetching: dataScopeLoading } = useData(
        ['roleDataScopeItems'],
        () => fetchDataScopeItems()
    );
    const dataScopeOptions = useMemo<{ label: string; value: string }[]>(
        () =>
            (dataScopePage?.content ?? []).map((item: DataScopeRelevantVo) => ({
                label: item.name,
                value: item.id,
            })),
        [dataScopePage]
    );

    /** 7. 获取字段权限下拉列表 */
    const { data: fieldScopePage, isFetching: fieldScopeLoading } = useData(
        ['roleFieldScopeItems'],
        () => fetchFieldScopeItems()
    );
    const fieldScopeOptions = useMemo<{ label: string; value: string }[]>(
        () =>
            (fieldScopePage?.content ?? []).map((item: FieldScopeRelevantVo) => ({
                label: item.name,
                value: item.id,
            })),
        [fieldScopePage]
    );

    /** 8. 切换角色时清空状态 */
    useEffect(() => {
        setTargetKeys([]);
        setSelectedItems([]);
        setLeftPage((prev) => ({ ...prev, current: 1 }));
        setRightPage((prev) => ({ ...prev, current: 1 }));
        setDataScopeId(null);
        setFieldScopeId(null);
    }, [roleId]);

    /** 9. 初始化/更新已分配权限 */
    useEffect(() => {
        if (authorized) {
            setTargetKeys(authorized);
        }
    }, [authorized]);

    useEffect(() => {
        if (!selectedAuthorities.length) {
            setSelectedItems([]);
            return;
        }
        if (selectedDetails) {
            const detailMap = new Map(selectedDetails.map((item) => [item.authority, item]));
            setSelectedItems(
                selectedAuthorities
                    .map((authority) => detailMap.get(authority))
                    .filter((item): item is PermissionRelevantVo => !!item)
            );
        }
    }, [selectedAuthorities, selectedDetails]);

    /** 10. 初始化范围选择 */
    useEffect(() => {
        if (scopeAssignment) {
            setDataScopeId(scopeAssignment.dataScopeId ?? null);
            setFieldScopeId(scopeAssignment.fieldScopeId ?? null);
        }
    }, [scopeAssignment]);

    const dataSource = useMemo(() => {
        const map = new Map<string, PermissionRelevantVo>();
        content.forEach((item) => map.set(item.authority, item));
        selectedItems.forEach((item) => map.set(item.authority, item));
        return Array.from(map.values());
    }, [content, selectedItems]);

    const leftPagination: TableProps<PermissionRelevantVo>['pagination'] = {
        current: leftPage.current,
        pageSize: leftPage.pageSize,
        total: page?.page.totalElements ?? 0,
        showSizeChanger: true,
        showQuickJumper: true,
        onChange: (current, pageSize) => {
            setLeftPage({ current, pageSize });
        },
    };

    const rightPagination: TableProps<PermissionRelevantVo>['pagination'] = {
        current: rightPage.current,
        pageSize: rightPage.pageSize,
        total: selectedItems.length,
        showSizeChanger: true,
        showQuickJumper: true,
        onChange: (current, pageSize) => {
            setRightPage({ current, pageSize });
        },
    };

    /** 11. 权限范围变更 */
    const handleDataScopeChange = async (value?: string | null) => {
        const nextValue = value ?? null;
        const previous = dataScopeId;
        setDataScopeId(nextValue);
        if (!roleId) return;

        try {
            setScopeSaving(true);
            await updateScopeAssignment({ roleId, dataScopeId: nextValue, fieldScopeId });
            message.success(t('roles.permissionConfig.scopeUpdateSuccess', '范围配置已保存'));
        } catch {
            setDataScopeId(previous);
            message.error(t('roles.permissionConfig.scopeUpdateFailed', '范围配置保存失败'));
        } finally {
            setScopeSaving(false);
        }
    };

    const handleFieldScopeChange = async (value?: string | null) => {
        const nextValue = value ?? null;
        const previous = fieldScopeId;
        setFieldScopeId(nextValue);
        if (!roleId) return;

        try {
            setScopeSaving(true);
            await updateScopeAssignment({ roleId, dataScopeId, fieldScopeId: nextValue });
            message.success(t('roles.permissionConfig.scopeUpdateSuccess', '范围配置已保存'));
        } catch {
            setFieldScopeId(previous);
            message.error(t('roles.permissionConfig.scopeUpdateFailed', '范围配置保存失败'));
        } finally {
            setScopeSaving(false);
        }
    };

    /** 12. 穿梭框变更事件 */
    const onChange: TableTransferProps['onChange'] = async (
        nextTargetKeys,
        direction,
        moveKeys
    ) => {
        const previousTargetKeys = targetKeys;
        const previousSelectedItems = selectedItems;

        setTargetKeys(nextTargetKeys);
        setRightPage((prev) => ({ ...prev, current: 1 }));

        const mergedItemMap = new Map<string, PermissionRelevantVo>();
        dataSource.forEach((item) => mergedItemMap.set(item.authority, item));
        selectedItems.forEach((item) => mergedItemMap.set(item.authority, item));
        setSelectedItems(
            (nextTargetKeys ?? [])
                .map((key) => mergedItemMap.get(String(key)))
                .filter((item): item is PermissionRelevantVo => !!item)
        );

        if (!roleId || moveKeys.length === 0) return;

        try {
            setMovingPermissions(true);
            if (direction === 'right') {
                await fetchAuthorize({
                    roleId,
                    permissionAuthority: moveKeys as string[],
                    dataScopeId,
                    fieldScopeId,
                });
            } else {
                await fetchUnauthorized({
                    roleId,
                    permissionAuthority: moveKeys as string[],
                });
            }
            message.success(t('roles.permissionConfig.permissionUpdateSuccess', '权限配置已更新'));
        } catch {
            setTargetKeys(previousTargetKeys);
            setSelectedItems(previousSelectedItems);
            message.error(t('roles.permissionConfig.permissionUpdateFailed', '权限配置更新失败'));
        } finally {
            setMovingPermissions(false);
        }
    };

    /** 13. roleId 为空时不渲染穿梭框（避免内部 DOM 计算报错） */
    if (!roleId) {
        return <div style={{ flex: 1, minHeight: 0 }} />;
    }

    const loadError = permissionPageError || authorizedError || scopeAssignmentError;
    const transferLoading = permissionPageLoading || authorizedLoading || selectedDetailsLoading || movingPermissions;
    const selectedCount = targetKeys?.length ?? 0;

    return (
        <div className="role-permission-config">
            <section className="role-permission-scope">
                <div className="role-permission-section-head">
                    <div>
                        <Typography.Text strong>{t('roles.permissionConfig.scopeTitle', '权限范围')}</Typography.Text>
                        <div className="role-permission-section-subtitle">
                            {scopeSaving
                                ? t('roles.permissionConfig.saving', '保存中...')
                                : t('roles.permissionConfig.scopeSummary', '数据范围与字段范围')}
                        </div>
                    </div>
                </div>
                <div className="role-permission-scope-grid">
                    <label className="role-permission-field">
                        <span>{t('roles.permissionConfig.dataScope', '数据权限')}</span>
                        <Select
                            allowClear
                            className="role-permission-select"
                            placeholder={t('roles.permissionConfig.noDataScope', '不限制数据范围')}
                            value={dataScopeId}
                            options={dataScopeOptions}
                            loading={dataScopeLoading || scopeAssignmentLoading}
                            disabled={scopeSaving}
                            notFoundContent={t('table.emptyText', '暂无数据')}
                            onChange={handleDataScopeChange}
                        />
                    </label>
                    <label className="role-permission-field">
                        <span>{t('roles.permissionConfig.fieldScope', '字段权限')}</span>
                        <Select
                            allowClear
                            className="role-permission-select"
                            placeholder={t('roles.permissionConfig.noFieldScope', '不限制字段范围')}
                            value={fieldScopeId}
                            options={fieldScopeOptions}
                            loading={fieldScopeLoading || scopeAssignmentLoading}
                            disabled={scopeSaving}
                            notFoundContent={t('table.emptyText', '暂无数据')}
                            onChange={handleFieldScopeChange}
                        />
                    </label>
                </div>
            </section>

            {loadError ? (
                <Alert
                    className="role-permission-alert"
                    type="error"
                    showIcon
                    message={t('roles.permissionConfig.loadFailed', '权限配置加载失败')}
                />
            ) : null}

            <section className="role-permission-transfer-section">
                <div className="role-permission-section-head">
                    <div>
                        <Typography.Text strong>{t('roles.permissionConfig.permissionTitle', '权限分配')}</Typography.Text>
                        <div className="role-permission-section-subtitle">
                            {t('roles.permissionConfig.selectedCount', '已授权 {count} 项', { count: selectedCount })}
                        </div>
                    </div>
                </div>
                <div className="role-permission-transfer-wrap">
                    <STableTransfer
                        className="role-permission-transfer"
                        dataSource={dataSource}
                        targetKeys={targetKeys}
                        showSelectAll={false}
                        disabled={transferLoading}
                        onChange={onChange}
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
                </div>
            </section>
        </div>
    );
};

export default App;
