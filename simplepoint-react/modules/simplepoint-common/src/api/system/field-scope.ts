import {get, put} from '@simplepoint/shared/api/methods';
import api from '@/api';

const {baseUrl} = api['rbac-field-scopes'];

export async function fetchFieldCatalog() {
  return get<Record<string, string[]>>(`${baseUrl}/catalog`);
}

export interface FieldScopeEntryDto {
    resource: string;
    field: string;
    access: string;
}

export async function replaceEntries(fieldScopeId: string, entries: FieldScopeEntryDto[]) {
    return put(`${baseUrl}/entries?fieldScopeId=${encodeURIComponent(fieldScopeId)}`, entries);
}
