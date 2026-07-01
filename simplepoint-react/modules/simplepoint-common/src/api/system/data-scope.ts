import { get } from '@simplepoint/shared/api/methods';
import { Page } from '@simplepoint/shared/types/request';
import api from '@/api';

const { baseUrl } = api['rbac-data-scopes'];

export interface DataScopeRelevantVo {
    id: string;
    name: string;
    scopeType: string;
    description?: string;
}

export async function fetchItems(params?: Record<string, string>) {
    return await get<Page<DataScopeRelevantVo>>(`${baseUrl}`, {
        size: '100',
        ...params,
    });
}
