import type { Page } from '@simplepoint/shared/types/request';
export interface JdbcUserDataSourceItem {
    id: string;
    code?: string;
    name?: string;
    databaseProductName?: string;
}
export interface JdbcUserDataSourceAssignDto {
    userId?: string | null;
    dataSourceIds?: string[];
}
export interface JdbcUserGrant {
    id: string;
    userId?: string;
    catalogId?: string;
    operationPermissions?: string[];
}
export declare function fetchItems(params: Record<string, string>): Promise<Page<JdbcUserDataSourceItem>>;
export declare function fetchSelectedItems(dataSourceIds: string[]): Promise<JdbcUserDataSourceItem[]>;
export declare function fetchAuthorized(userId: string): Promise<string[]>;
export declare function fetchGrants(userId: string): Promise<JdbcUserGrant[]>;
export declare function fetchAuthorize(data: JdbcUserDataSourceAssignDto): Promise<unknown>;
export declare function fetchUnauthorized(data: JdbcUserDataSourceAssignDto): Promise<unknown>;
export declare function updateGrantPermissions(grantId: string, permissions: string[]): Promise<unknown>;
