import {useQuery} from '@tanstack/react-query';
import {useEffect} from 'react';
import {get} from '@simplepoint/shared/api/methods';
import {getStoredTenantId} from '@simplepoint/shared/api/contextId';

export type CurrentTenant = {
    tenantId: string;
    tenantName: string;
    tenantType: 'PLATFORM' | 'PERSONAL' | 'ORGANIZATION';
};

export type CurrentRole = {
    roleId: string;
    roleName?: string;
    authority?: string;
};

function normalizeTenantType(tenant?: Partial<CurrentTenant>): CurrentTenant['tenantType'] {
    if (tenant?.tenantType === 'PLATFORM') return 'PLATFORM';
    return tenant?.tenantType === 'PERSONAL' ? 'PERSONAL' : 'ORGANIZATION';
}

/**
 * 获取当前用户可切换的租户列表（部分后端会按当前 tenant 返回不同的列表）
 */
export async function fetchCurrentTenants(): Promise<CurrentTenant[]> {
    const res = await get<CurrentTenant[]>('/common/tenants/current');
    // 兜底：避免后端偶发返回 null/{} 导致页面静默失败
    // 组织租户排在前面，个人租户排在最后；同类型按名称稳定排序
    if (!Array.isArray(res)) return [];
    return res.sort((a, b) => {
        const typeA = normalizeTenantType(a);
        const typeB = normalizeTenantType(b);
        if (typeA !== typeB) {
            const order = {PLATFORM: 0, ORGANIZATION: 1, PERSONAL: 2} as const;
            return order[typeA] - order[typeB];
        }
        return (a.tenantName || '').localeCompare(b.tenantName || '');
    });
}

export async function fetchCurrentRoles(tenantId?: string): Promise<CurrentRole[]> {
    const query = tenantId ? `?tenantId=${encodeURIComponent(tenantId)}` : '';
    const res = await get<Array<CurrentRole | {id?: string; name?: string; authority?: string}>>(`/common/tenants/current-roles${query}`);
    if (!Array.isArray(res)) return [];
    return res
        .map((role) => {
            const roleId = (role as CurrentRole).roleId ?? (role as {id?: string}).id;
            return {
                roleId: roleId?.trim() ?? '',
                roleName: (role as CurrentRole).roleName ?? (role as {name?: string}).name,
                authority: role.authority,
            };
        })
        .filter((role) => role.roleId);
}

/**
 * 获取当前租户上下文 ID（部分接口需要单独传递）
 *
 * 已收敛到 @simplepoint/shared/api/contextId.ensureContextId。
 */
// export async function fetchGetContextId(tenantId:string): Promise<string | undefined> {
//     const res = await get<string>('/common/tenants/authorization-context-id?tenantId='+tenantId);
//     return res ? res : undefined;
// }

export function useCurrentTenants() {
    // 会话级缓存，避免每次打开下拉都拉取
    const tenantId = getStoredTenantId();
    const cacheKey = tenantId ? `sp.currentTenants:${tenantId}` : 'sp.currentTenants';

    let cached: CurrentTenant[] | undefined;
    try {
        const raw = sessionStorage.getItem(cacheKey);
        cached = raw ? (JSON.parse(raw) as CurrentTenant[]) : undefined;
    } catch (e) {
        console.warn('[tenants] Failed to read cached tenants:', e);
    }

    const result = useQuery({
        queryKey: ['common', 'tenants', 'current', tenantId],
        queryFn: fetchCurrentTenants,
        staleTime: 2 * 60 * 1000,
        refetchOnWindowFocus: false,
        initialData: cached,
        initialDataUpdatedAt: cached ? 0 : undefined,
    });

    useEffect(() => {
        if (result.data) {
            try {
                sessionStorage.setItem(cacheKey, JSON.stringify(result.data));
            } catch (e) {
                console.warn('[tenants] Failed to cache tenants:', e);
            }
        }
    }, [cacheKey, result.data]);

    useEffect(() => {
        if (process.env.NODE_ENV === 'development' && result.isError) {
            // eslint-disable-next-line no-console
            console.warn('fetchCurrentTenants failed:', result.error);
        }
    }, [result.isError, result.error]);

    return result;
}

export function useCurrentRoles(tenantId?: string) {
    const cacheKey = tenantId ? `sp.currentRoles:${tenantId}` : 'sp.currentRoles';

    let cached: CurrentRole[] | undefined;
    try {
        const raw = sessionStorage.getItem(cacheKey);
        cached = raw ? (JSON.parse(raw) as CurrentRole[]) : undefined;
    } catch (e) {
        console.warn('[tenants] Failed to read cached roles:', e);
    }

    const result = useQuery({
        queryKey: ['common', 'tenants', 'current-roles', tenantId],
        queryFn: () => fetchCurrentRoles(tenantId),
        enabled: !!tenantId,
        staleTime: 2 * 60 * 1000,
        refetchOnWindowFocus: false,
        initialData: cached,
        initialDataUpdatedAt: cached ? 0 : undefined,
    });

    useEffect(() => {
        if (result.data) {
            try {
                sessionStorage.setItem(cacheKey, JSON.stringify(result.data));
            } catch (e) {
                console.warn('[tenants] Failed to cache roles:', e);
            }
        }
    }, [cacheKey, result.data]);

    useEffect(() => {
        if (process.env.NODE_ENV === 'development' && result.isError) {
            console.warn('fetchCurrentRoles failed:', result.error);
        }
    }, [result.isError, result.error]);

    return result;
}
