import {JSX} from 'react';
import {Navigate, Route} from 'react-router';
import {IframeView} from './IframeView';
import {withBoundaryAndSuspense} from './withBoundaryAndSuspense';
import {parseComponent} from '@/utils/parseComponent';
import {getLazyComponent} from '@/utils/lazyComponent';
import {Profile} from '@/layouts/profile';
import {Settings} from '@/layouts/settings';
import {NotFound} from '@/views/errors/NotFound';
import {OrgTenantRequired} from '@/views/errors/OrgTenantRequired';
import {Dashboard} from '@/views/dashboard';

export interface RouteItem {
    key: string;
    path: string;
    element: JSX.Element;
}

interface LeafRoute {
    uuid?: string;
    path?: string;
    component?: string;
    requireOrgTenant?: boolean;
}

type TranslateFn = (key: string, fallback?: string) => string;

/**
 * 生成路由
 * @param leafRoutes
 * @param refreshKeyMap
 * @param t
 * @param tenantType current tenant type; PERSONAL tenants are blocked from requireOrgTenant routes
 */
export function renderRoutes(
    leafRoutes: LeafRoute[],
    refreshKeyMap: Record<string, number>,
    t: TranslateFn,
    tenantType?: 'PLATFORM' | 'PERSONAL' | 'ORGANIZATION',
    remoteRegistryKey?: string,
) {
    // 1. 静态路由（结构统一）
    const staticRoutes: RouteItem[] = [
        {key: 'root', path: '/', element: <Navigate to="/dashboard" replace/>},
        {key: 'dashboard', path: '/dashboard', element: <Dashboard/>},
        {key: 'profile', path: '/profile', element: <Profile/>},
        {key: 'settings', path: '/settings', element: <Settings/>},
        {key: '404', path: '*', element: <NotFound/>}
    ];

    // 2. 动态路由（转换成统一结构）
    const dynamicRoutes: RouteItem[] = leafRoutes
        .filter((route): route is LeafRoute & { path: string } => !!route.path)
        .map((route, idx: number) => {
            const { uuid, path, component, requireOrgTenant } = route;
            const key = uuid || path || String(idx);

            // 个人租户访问需要组织租户的功能时，显示错误页
            if (requireOrgTenant && tenantType === 'PERSONAL') {
                return {key, path, element: <OrgTenantRequired/>};
            }

            const rk = path ? (refreshKeyMap[path] || 0) : 0;

            const {type, payload} = parseComponent(component);

            // iframe
            if (type === 'iframe') {
                return {
                    key,
                    path,
                    element: <IframeView key={`iframe-${path}-${rk}`} src={payload}/>
                };
            }

            // external link（不加入内部路由）
            if (type === 'external') {
                return null;
            }

            // lazy remote component
            const LazyComp = getLazyComponent(t, component, remoteRegistryKey);
            const Wrapped = withBoundaryAndSuspense(LazyComp, t, path, rk);

            return {
                key,
                path,
                element: <Wrapped/>
            };
        })
        .filter(Boolean) as RouteItem[];

    // 3. 合并
    const allRoutes: RouteItem[] = [...staticRoutes, ...dynamicRoutes];

    // 4. 渲染 <Route>
    return allRoutes.map(({key, path, element}) => (
        <Route key={key} path={path} element={element}/>
    ));
}
