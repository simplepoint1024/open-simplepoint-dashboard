// src/hooks/useLeafRoutes.ts
import {useMemo} from 'react';
import {flattenLeafRoutes} from '@/utils/MfRoutes';
import {MenuInfo} from '@/store/routes';

/**
 * 从菜单树中提取所有叶子路由（有 path + component）
 */
export function useLeafRoutes(routes?: MenuInfo[]) {
    return useMemo(() => {
        if (!routes || routes.length === 0) return [];

        // flattenLeafRoutes 已经负责把树拍平
        const flat = flattenLeafRoutes(routes);

        // 过滤掉没有 path 或 component 的节点
        return flat.filter((n: any) => n.path && n.component);
    }, [routes]);
}
