import { Page } from '@simplepoint/shared/types/request';
export interface TenantPackagesRelevanceDto {
    tenantId: string | null;
    packageCodes?: string[];
}
export interface TenantUsersRelevanceDto {
    tenantId: string | null;
    userIds?: string[];
}
export interface UserRelevanceVo {
    id: string;
    name: string;
    email?: string;
    phoneNumber?: string;
}
export declare function fetchAuthorized(params: Pick<TenantPackagesRelevanceDto, 'tenantId'>): Promise<string[]>;
export declare function fetchAuthorize(data: TenantPackagesRelevanceDto): Promise<TenantPackagesRelevanceDto>;
export declare function fetchUnauthorized(data: TenantPackagesRelevanceDto): Promise<TenantPackagesRelevanceDto>;
export declare function fetchOwnerItems(params: {
    page: string;
    size: string;
}): Promise<Page<UserRelevanceVo>>;
export declare function fetchUserItems(params: {
    tenantId: string;
    page: string;
    size: string;
}): Promise<Page<UserRelevanceVo>>;
export declare function fetchAuthorizedUsers(params: Pick<TenantUsersRelevanceDto, 'tenantId'>): Promise<string[]>;
export declare function fetchAuthorizeUsers(data: TenantUsersRelevanceDto): Promise<TenantUsersRelevanceDto>;
export declare function fetchUnauthorizedUsers(data: TenantUsersRelevanceDto): Promise<TenantUsersRelevanceDto>;
