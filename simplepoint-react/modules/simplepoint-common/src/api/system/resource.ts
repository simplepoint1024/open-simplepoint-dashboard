import {get, post} from '@simplepoint/shared/api/methods';
import {Page} from '@simplepoint/shared/types/request';
import api from '@/api';

const {baseUrl} = api['rbac-resources'];

export type ResourceType = 'GROUP' | 'MODULE' | 'PAGE' | 'FEATURE' | 'ACTION' | 'API';

export interface ResourceRelevantVo {
  id: string;
  code: string;
  name?: string;
  alias?: string | null;
  label?: string;
  title?: string;
  type: ResourceType;
  path?: string | null;
  component?: string | null;
  description?: string | null;
  grantable?: boolean;
  publicAccess?: boolean;
  disabled?: boolean;
  hasChildren?: boolean;
  children?: ResourceRelevantVo[];
}

export async function fetchItems(params: Record<string, string> = {}) {
  return await get<Page<ResourceRelevantVo>>(baseUrl, params);
}

export async function fetchChildren(params: Record<string, string> = {}) {
  return await get<Page<ResourceRelevantVo>>(`${baseUrl}/children`, params);
}

export async function fetchByCodes(codes: string[]) {
  return await post<ResourceRelevantVo[]>(`${baseUrl}/by-codes`, codes);
}
