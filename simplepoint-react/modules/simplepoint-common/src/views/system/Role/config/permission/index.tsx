import { useI18n } from '@simplepoint/shared/hooks/useI18n';
import { useEffect, useMemo, useState } from 'react';
import { GetProp, Select, Space, TableColumnsType, TableProps, TransferProps } from 'antd';
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
    const { t, messages } = useI18n();

    const [leftPage, setLeftPage] = useState({ current: 1, pageSize: 10 });
    const [rightPage, setRightPage] = useState({ current: 1, pageSize: 10 });

    const [dataScopeId, setDataScopeId] = useState<string | null>(null);
    const [fieldScopeId, setFieldScopeId] = useState<string | null>(null);

    /** 1. 获取权限列表 */
    const { data: page } = usePage(['fetchItems', leftPage.current, leftPage.pageSize], () =>
        fetchItems({ page: String(leftPage.current - 1), size: String(leftPage.pageSize) })
    );
    const content = page?.content ?? [];

    /** 2. 列定义（依赖 messages 才能在语言切换时立即更新） */
    const columns: TableColumnsType<PermissionRelevantVo> = useMemo(
        () => [
            {
                key: 'name',
                dataIndex: 'name',
                title: t('permissions.title.name'),
            },
            {
                key: 'description',
                dataIndex: 'description',
                title: t('permissions.title.description'),
            },
        ],
        [messages] // ⭐ messages 是最稳定、最正确的依赖
    );

    /** 3. 穿梭框状态 */
    const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);
    const [selectedItems, setSelectedItems] = useState<PermissionRelevantVo[]>([]);
    const selectedAuthorities = useMemo(
        () => (targetKeys ?? []).map((key) => String(key)),
        [targetKeys]
    );

    /** 4. 获取已分配权限 */
    const { data: authorized } = useData<string[]>(
        roleId ? ['fetchAuthorized', roleId] : '',
        () => fetchAuthorized(roleId),
        { enabled: !!roleId }
    );

    const { data: selectedDetails } = useData<PermissionRelevantVo[]>(
        roleId && selectedAuthorities.length > 0
            ? ['fetchSelectedPermissionItems', ...selectedAuthorities]
            : ['fetchSelectedPermissionItems', roleId ?? 'empty'],
        () => fetchSelectedItems(selectedAuthorities),
        { enabled: !!roleId && selectedAuthorities.length > 0 }
    );

    /** 5. 获取当前角色的数据权限/字段权限分配 */
    const { data: scopeAssignment } = useData(
        roleId ? ['fetchScopeAssignment', roleId] : '',
        () => fetchScopeAssignment(roleId),
        { enabled: !!roleId }
    );

    /** 6. 获取数据权限下拉列表 */
    const { data: dataScopePage } = useData(
        ['fetchDataScopeItems'],
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
    const { data: fieldScopePage } = useData(
        ['fetchFieldScopeItems'],
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
    const handleDataScopeChange = (value: string | null) => {
        setDataScopeId(value);
        if (roleId) {
            updateScopeAssignment({ roleId, dataScopeId: value, fieldScopeId });
        }
    };

    const handleFieldScopeChange = (value: string | null) => {
        setFieldScopeId(value);
        if (roleId) {
            updateScopeAssignment({ roleId, dataScopeId, fieldScopeId: value });
        }
    };

    /** 12. 穿梭框变更事件 */
    const onChange: TableTransferProps['onChange'] = (
        nextTargetKeys,
        direction,
        moveKeys
    ) => {
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

        if (!roleId) return;

        if (direction === 'right') {
            fetchAuthorize({
                roleId,
                permissionAuthority: moveKeys as string[],
                dataScopeId,
                fieldScopeId,
            });
        } else {
            fetchUnauthorized({
                roleId,
                permissionAuthority: moveKeys as string[],
            });
        }
    };

    /** 13. roleId 为空时不渲染穿梭框（避免内部 DOM 计算报错） */
    if (!roleId) {
        return <div style={{ flex: 1, minHeight: 0 }} />;
    }

    return (
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, gap: 8 }}>
            <Space wrap style={{ flexShrink: 0 }}>
                <span>{t('data-scopes.title.name')}:</span>
                <Select
                    allowClear
                    style={{ minWidth: 200 }}
                    placeholder={t('data-scopes.title.name')}
                    value={dataScopeId}
                    options={dataScopeOptions}
                    onChange={handleDataScopeChange}
                />
                <span>{t('field-scopes.title.name')}:</span>
                <Select
                    allowClear
                    style={{ minWidth: 200 }}
                    placeholder={t('field-scopes.title.name')}
                    value={fieldScopeId}
                    options={fieldScopeOptions}
                    onChange={handleFieldScopeChange}
                />
            </Space>
            <div style={{ flex: 1, minHeight: 0 }}>
                <STableTransfer
                    dataSource={dataSource}
                    targetKeys={targetKeys}
                    showSelectAll={false}
                    onChange={onChange}
                    leftColumns={columns}
                    rightColumns={columns}
                    itemKey="authority"
                    adaptiveHeight
                    searchable
                    leftPagination={leftPagination}
                    rightPagination={rightPagination}
                />
            </div>
        </div>
    );
};

export default App;
