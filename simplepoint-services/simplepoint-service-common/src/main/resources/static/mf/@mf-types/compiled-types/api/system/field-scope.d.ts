import { Page } from '@simplepoint/shared/types/request';
export interface FieldScopeRelevantVo {
    id: string;
    name: string;
    description?: string;
}
export interface FieldScopeEntryDto {
    resource: string;
    field: string;
    access: string;
}
export declare function fetchItems(params?: Record<string, string>): Promise<Page<FieldScopeRelevantVo>>;
export declare function replaceEntries(fieldScopeId: string, entries: FieldScopeEntryDto[]): Promise<unknown>;
