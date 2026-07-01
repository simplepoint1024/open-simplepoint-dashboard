import { useI18n } from '@simplepoint/shared/hooks/useI18n';
import { useEffect, useMemo, useState } from 'react';
import { GetProp, TableColumnsType, TableProps, TransferProps } from 'antd';
import STableTransfer from '@simplepoint/components/STableTransfer';
import { useData, usePage } from '@simplepoint/shared/api/methods';
import { FeatureRelevantVo, fetchItems, fetchSelectedItems } from '@/api/platform/feature';
import {
    fetchAuthorize,
    fetchAuthorized,
    fetchUnauthorized,
} from '@/api/system/menu';

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];

export interface RoleSelectProps {
    menuId: string | null;
}

interface TableTransferProps extends TransferProps<TransferItem> {
    dataSource: FeatureRelevantVo[];
    leftColumns: TableColumnsType<FeatureRelevantVo>;
    rightColumns: TableColumnsType<FeatureRelevantVo>;
}

const App = ({ menuId }: RoleSelectProps) => {
    const { t, messages, ensure, locale } = useI18n();
    const [leftPage, setLeftPage] = useState({ current: 1, pageSize: 10 });
    const [rightPage, setRightPage] = useState({ current: 1, pageSize: 10 });

    useEffect(() => {
        void ensure(['features']);
    }, [ensure, locale]);

    /** 2. 获取功能列表 */
    const { data: page } = usePage(['platform-feature-items', leftPage.current, leftPage.pageSize], () =>
        fetchItems({ page: String(leftPage.current - 1), size: String(leftPage.pageSize) })
    );
    const content = page?.content ?? [];

    /** 3. 列定义（依赖 messages 才能在语言切换时立即更新） */
    const columns: TableColumnsType<FeatureRelevantVo> = useMemo(
        () => [
            {
                key: 'name',
                dataIndex: 'name',
                title: t('features.title.name', '功能名称'),
            },
            {
                key: 'code',
                dataIndex: 'code',
                title: t('features.title.code', '功能编码'),
            },
            {
                key: 'description',
                dataIndex: 'description',
                title: t('features.title.description', '功能描述'),
            },
        ],
        [messages]
    );

    /** 4. 穿梭框状态 */
    const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);
    const [selectedItems, setSelectedItems] = useState<FeatureRelevantVo[]>([]);
    const selectedCodes = useMemo(
        () => (targetKeys ?? []).map((key) => String(key)),
        [targetKeys]
    );

    /** 5. 获取已绑定功能 */
    const { data: authorized } = useData<string[]>(
        menuId ? ['fetchAuthorizedMenuFeatures', menuId] : '',
        () => fetchAuthorized({ menuId }),
        { enabled: !!menuId }
    );

    const { data: selectedDetails } = useData<FeatureRelevantVo[]>(
        menuId && selectedCodes.length > 0
            ? ['fetchSelectedMenuFeatures', ...selectedCodes]
            : ['fetchSelectedMenuFeatures', menuId ?? 'empty'],
        () => fetchSelectedItems(selectedCodes),
        { enabled: !!menuId && selectedCodes.length > 0 }
    );

    /** 6. 切换菜单时清空状态 */
    useEffect(() => {
        setTargetKeys([]);
        setSelectedItems([]);
        setLeftPage((prev) => ({ ...prev, current: 1 }));
        setRightPage((prev) => ({ ...prev, current: 1 }));
    }, [menuId]);

    /** 7. 初始化/更新已分配权限 */
    useEffect(() => {
        if (authorized) {
            setTargetKeys(authorized);
        }
    }, [authorized]);

    useEffect(() => {
        if (!selectedCodes.length) {
            setSelectedItems([]);
            return;
        }
        if (selectedDetails) {
            const detailMap = new Map(selectedDetails.map((item) => [item.code, item]));
            setSelectedItems(
                selectedCodes
                    .map((code) => detailMap.get(code))
                    .filter((item): item is FeatureRelevantVo => !!item)
            );
        }
    }, [selectedCodes, selectedDetails]);

    const dataSource = useMemo(() => {
        const map = new Map<string, FeatureRelevantVo>();
        content.forEach((item) => map.set(item.code, item));
        selectedItems.forEach((item) => map.set(item.code, item));
        return Array.from(map.values());
    }, [content, selectedItems]);

    const leftPagination: TableProps<FeatureRelevantVo>['pagination'] = {
        current: leftPage.current,
        pageSize: leftPage.pageSize,
        total: page?.page.totalElements ?? 0,
        showSizeChanger: true,
        showQuickJumper: true,
        onChange: (current, pageSize) => {
            setLeftPage({ current, pageSize });
        },
    };

    const rightPagination: TableProps<FeatureRelevantVo>['pagination'] = {
        current: rightPage.current,
        pageSize: rightPage.pageSize,
        total: selectedItems.length,
        showSizeChanger: true,
        showQuickJumper: true,
        onChange: (current, pageSize) => {
            setRightPage({ current, pageSize });
        },
    };

    /** 8. 穿梭框变更事件 */
    const onChange: TableTransferProps['onChange'] = (
        nextTargetKeys,
        direction,
        moveKeys
    ) => {
        setTargetKeys(nextTargetKeys);
        setRightPage((prev) => ({ ...prev, current: 1 }));

        const mergedItemMap = new Map<string, FeatureRelevantVo>();
        dataSource.forEach((item) => mergedItemMap.set(item.code, item));
        selectedItems.forEach((item) => mergedItemMap.set(item.code, item));
        setSelectedItems(
            (nextTargetKeys ?? [])
                .map((key) => mergedItemMap.get(String(key)))
                .filter((item): item is FeatureRelevantVo => !!item)
        );

        if (direction === 'right') {
            void fetchAuthorize({
                menuId,
                featureCodes: moveKeys as string[],
            });
        } else {
            void fetchUnauthorized({
                menuId,
                featureCodes: moveKeys as string[],
            });
        }
    };

    /** 9. menuId 为空时不渲染穿梭框（避免内部 DOM 计算报错） */
    if (!menuId) {
        return <div style={{ flex: 1, minHeight: 0 }} />;
    }

    return (
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
            <div style={{ flex: 1, minHeight: 0 }}>
                <STableTransfer
                    dataSource={dataSource}
                    targetKeys={targetKeys}
                    showSelectAll={false}
                    onChange={onChange}
                    leftColumns={columns}
                    rightColumns={columns}
                    itemKey="code"
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
