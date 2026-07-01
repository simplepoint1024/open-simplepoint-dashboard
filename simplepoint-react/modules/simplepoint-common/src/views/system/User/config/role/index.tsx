import {useI18n} from '@simplepoint/shared/hooks/useI18n';
import {useEffect, useMemo, useState} from "react";
import {GetProp, TableColumnsType, TransferProps} from 'antd';
import STableTransfer from "@simplepoint/components/STableTransfer";
import {useData, usePage} from '@simplepoint/shared/api/methods';
import {RoleRelevantVo} from "@/api/system/role";
import {fetchAuthorize, fetchAuthorized, fetchRoleCandidates, fetchUnauthorized} from "@/api/system/user";

type TransferItem = GetProp<TransferProps, 'dataSource'>[number];

export interface RoleSelectProps {
    userId: string | null;
}

interface TableTransferProps extends TransferProps<TransferItem> {
    dataSource: RoleRelevantVo[];
    leftColumns: TableColumnsType<RoleRelevantVo>;
    rightColumns: TableColumnsType<RoleRelevantVo>;
}

const App = ({userId}: RoleSelectProps) => {
    const {t, messages} = useI18n();

    /** 1. 加载角色列表 */
    const {data: page} = usePage(
        'fetchRoleCandidates',
        () => fetchRoleCandidates({page: '0', size: '10000000'})
    );
    const content = page?.content ?? [];

    /** 3. 列定义（依赖 messages 才能在语言切换时立即更新） */
    const columns: TableColumnsType<RoleRelevantVo> = useMemo(() => [
        {
            key: 'name',
            dataIndex: 'name',
            title: t('roles.title.roleName'),
        },
        {
            key: 'description',
            dataIndex: 'description',
            title: t('roles.title.description'),
        },
    ], [messages]); // ⭐ messages 是最稳定、最正确的依赖

    /** 4. 角色分配状态 */
    const [targetKeys, setTargetKeys] = useState<TransferProps['targetKeys']>([]);

    // 无 userId 时不启动查询
    const canQuery = !!userId;

    /** 5. 获取已分配角色 */
    const {data: authorized, isFetching} = useData<string[]>(
        canQuery ? ['fetchAuthorizedUserRoles', userId] : ['fetchAuthorizedUserRoles', 'pending'],
        () => fetchAuthorized({userId}),
        {enabled: canQuery}
    );

    /** 6. 切换用户时清空状态 */
    useEffect(() => {
        setTargetKeys([]);
    }, [userId]);

    /** 7. 初始化/更新已分配角色 */
    useEffect(() => {
        if (authorized) {
            setTargetKeys(authorized);
        }
    }, [authorized]);

    /** 8. 穿梭框变更事件 */
    const onChange: TableTransferProps['onChange'] = (nextTargetKeys, direction, moveKeys) => {
        setTargetKeys(nextTargetKeys);

        if (!canQuery) return;

        if (direction === 'right') {
            fetchAuthorize({
                userId,
                roleIds: moveKeys as string[],
            });
        } else {
            fetchUnauthorized({
                userId,
                roleIds: moveKeys as string[],
            });
        }
    };

    /** 9. userId 为空时不渲染穿梭框，避免内部 DOM 计算报错 */
    if (!canQuery) {
        return <div style={{flex: 1, minHeight: 0}}/>;
    }

    return (
        <div style={{display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0}}>
            <div style={{flex: 1, minHeight: 0}}>
                <STableTransfer
                    dataSource={content}
                    targetKeys={targetKeys}
                    showSelectAll={false}
                    onChange={onChange}
                    leftColumns={columns}
                    rightColumns={columns}
                    itemKey="id"
                    adaptiveHeight
                    searchable
                />
                {isFetching && <div style={{marginTop: 8}}>{t('loading', '加载中...')}</div>}
            </div>
        </div>
    );
};

export default App;
