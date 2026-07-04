import {RouteInfo} from '@/store/routes';
import {get} from '@simplepoint/shared/api/methods';

export type ServiceResourceRouteResult = {
    services: ServiceEntry[];
    routes: RouteInfo[];
    entryPoint: string;
    authorizationContext?: AuthorizationContextInfo;
}

export type AuthorizationContextInfo = {
    scopeType?: 'PLATFORM' | 'TENANT' | 'PERSONAL' | string;
    actorRole?: 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'TENANT_OWNER' | 'TENANT_MEMBER' | 'PERSONAL_OWNER' | 'PERSONAL_MEMBER' | string;
    tenantId?: string;
    userId?: string;
    resources?: string[];
}

export type ServiceEntry = {
    name: string;
    entry?: string;
    alias?: string;
    remoteVersion?: string;
    pluginVersion?: string;
}

export function fetchServiceRoutes() {
    return get<ServiceResourceRouteResult>('/common/resources/service-routes');
}
