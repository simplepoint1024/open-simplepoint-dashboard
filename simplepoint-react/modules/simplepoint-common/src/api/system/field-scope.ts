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
  name: string;
  description?: string;
  entries: FieldScopeEntryDto[];
}

export interface FieldPageMetadata {
  title: string;
  models: Array<{model: string; properties: Record<string, {title: string; type: string}>}>;
}

export function fetchFieldPageCatalog() {
  return get<Record<string, FieldPageMetadata>>(`${baseUrl}/page-catalog`);
}

export function saveFieldConfiguration(configuration: FieldScopeConfiguration) {
  const {id, ...body} = configuration;
  return id ? put<FieldScopeConfiguration>(`${baseUrl}/configuration?id=${encodeURIComponent(id)}`, body)
    : post<FieldScopeConfiguration>(`${baseUrl}/configuration`, body);
}
