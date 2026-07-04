import {get} from '@simplepoint/shared/api/methods';
import {Page} from '@simplepoint/shared/types/request';
import api from '@/api';

const {baseUrl} = api['rbac-resources'];

export type ResourceType = 'GROUP' | 'MODULE' | 'PAGE' | 'FEATURE' | 'ACTION' | 'API';

export interface ResourceRelevantVo {
  id: string;
  code: string;
  name?: string;
  label?: string;
  title?: string;
  type: ResourceType;
  path?: string | null;
  component?: string | null;
  description?: string | null;
  grantable?: boolean;
  publicAccess?: boolean;
  disabled?: boolean;
  children?: ResourceRelevantVo[];
}

export async function fetchItems(params: Record<string, string> = {}) {
  return await get<Page<ResourceRelevantVo>>(baseUrl, params);
}
