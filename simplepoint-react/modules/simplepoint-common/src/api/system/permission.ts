import {get} from '@simplepoint/shared/api/methods';
import {Page} from '@simplepoint/shared/types/request';
import api from '@/api';

const {baseUrl} = api['rbac-permissions'];

export interface PermissionRelevantVo {
  id: string;
  name: string;
  authority: string;
  description: string;
  type?: number;
}

export async function fetchItems(params: Record<string, string>) {
  return await get<Page<PermissionRelevantVo>>(`${baseUrl}/items`, params);
}

export async function fetchSelectedItems(authorities: string[]) {
  if (!authorities.length) {
    return [] as PermissionRelevantVo[];
  }
  return await get<PermissionRelevantVo[]>(`${baseUrl}/items/selected`, {
    authorities: authorities.join(','),
  });
}

