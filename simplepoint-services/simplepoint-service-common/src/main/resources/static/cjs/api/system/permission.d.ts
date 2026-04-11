import { Page } from '@simplepoint/shared/types/request';
export interface PermissionRelevantVo {
    id: string;
    name: string;
    authority: string;
    description: string;
    type?: number;
}
export declare function fetchItems(params: Record<string, string>): Promise<Page<PermissionRelevantVo>>;
export declare function fetchSelectedItems(authorities: string[]): Promise<PermissionRelevantVo[]>;
