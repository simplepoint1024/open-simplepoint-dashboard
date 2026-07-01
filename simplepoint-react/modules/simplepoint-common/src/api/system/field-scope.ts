import {get, put} from '@simplepoint/shared/api/methods';
import { Page } from '@simplepoint/shared/types/request';
import api from '@/api';

const {baseUrl} = api['rbac-field-scopes'];

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

export async function fetchItems(params?: Record<string, string>) {
    return await get<Page<FieldScopeRelevantVo>>(`${baseUrl}`, {
        size: '100',
        ...params,
    });
}

export async function replaceEntries(fieldScopeId: string, entries: FieldScopeEntryDto[]) {
    return put(`${baseUrl}/entries?fieldScopeId=${encodeURIComponent(fieldScopeId)}`, entries);
}
