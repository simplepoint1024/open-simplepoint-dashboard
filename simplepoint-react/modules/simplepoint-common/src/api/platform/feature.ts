import api from '@/api';
import {get, post} from '@simplepoint/shared/api/methods';
import {Page} from '@simplepoint/shared/types/request';

const {baseUrl} = api['platform.features'];

export interface FeatureRelevantVo {
  id: string;
  name: string;
  code: string;
  description: string;
}

export interface FeaturePermissionsRelevanceDto {
  featureCode: string | null;
  permissionAuthority?: string[];
}

export async function fetchItems(params: Record<string, string>) {
  return await get<Page<FeatureRelevantVo>>(`${baseUrl}/items`, params);
}

export async function fetchSelectedItems(codes: string[]) {
  if (!codes.length) {
    return [] as FeatureRelevantVo[];
  }
  return await get<FeatureRelevantVo[]>(`${baseUrl}/items/selected`, {
    codes: codes.join(','),
  });
}

export async function fetchAuthorized(params: Pick<FeaturePermissionsRelevanceDto, 'featureCode'>) {
  return await get<string[]>(`${baseUrl}/authorized`, params as Record<string, string>);
}

export async function fetchAuthorize(data: FeaturePermissionsRelevanceDto) {
  return await post<FeaturePermissionsRelevanceDto>(`${baseUrl}/authorize`, data);
}

export async function fetchUnauthorized(data: FeaturePermissionsRelevanceDto) {
  return await post<FeaturePermissionsRelevanceDto>(`${baseUrl}/unauthorized`, data);
}
