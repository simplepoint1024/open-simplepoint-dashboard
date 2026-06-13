import { Page } from '@simplepoint/shared/types/request';
export interface DataScopeRelevantVo {
    id: string;
    name: string;
    scopeType: string;
    description?: string;
}
export declare function fetchItems(params?: Record<string, string>): Promise<Page<DataScopeRelevantVo>>;
