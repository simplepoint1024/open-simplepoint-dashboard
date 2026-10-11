import {get, post, put} from '@simplepoint/shared/api/methods';
import api from '@/api';

const {baseUrl} = api['rbac-field-scopes'];

export interface FieldScopeEntryDto {
    resource: string;
    field: string;
    access: string;
}

export interface FieldScopeConfiguration {
  id?: string;
  name?: string;
  description?: string | null;
  entries: FieldScopeEntryDto[];
}

export interface FieldPageMetadata {
  title: string;
  models: Array<{model: string; properties: Record<string, {title: string; type: string}>}>;
}

export function fetchFieldPageCatalog() {
  return get<Record<string, FieldPageMetadata>>(`${baseUrl}/page-catalog`);
}

export function saveFieldConfiguration(configuration: FieldScopeConfiguration, signal?: AbortSignal) {
  const {id, ...body} = configuration;
  const options = {signal, notifyOnError: false};
  return id ? put<FieldScopeConfiguration>(`${baseUrl}/configuration?id=${encodeURIComponent(id)}`, body, options)
    : post<FieldScopeConfiguration>(`${baseUrl}/configuration`, body, options);
}

export interface FieldResourceNode {id: string; parentId?: string; title: string; icon?: string; pageCode?: string; hasChildren: boolean}
export async function fetchFieldResourceNodes(parentId: string | undefined, page: number, signal?: AbortSignal) {
  const {normalizePage} = await import('@simplepoint/shared/types/request');
  return normalizePage<FieldResourceNode>(await get<any>(`${baseUrl}/resources`, {parentId, page, size: 15}, {signal, notifyOnError: false}), 15);
}
export function fetchPageFields(code: string, signal?: AbortSignal) {
  return get<FieldPageMetadata>(`${baseUrl}/page-fields`, {code}, {signal, notifyOnError: false});
}
