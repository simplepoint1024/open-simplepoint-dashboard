import api from '@/api';
import {get, post} from '@simplepoint/shared/api/methods';
import {Page} from '@simplepoint/shared/types/request';

const {baseUrl} = api['platform.packages'];

export interface PackageRelevantVo {
  id: string;
  name: string;
  code: string;
  description: string;
}

export interface PackageApplicationsRelevanceDto {
  packageCode: string | null;
  applicationCodes?: string[];
}

export async function fetchItems(params: Record<string, string>) {
  return await get<Page<PackageRelevantVo>>(`${baseUrl}/items`, params);
}

export async function fetchAuthorized(params: Pick<PackageApplicationsRelevanceDto, 'packageCode'>) {
  return await get<string[]>(`${baseUrl}/authorized`, params as Record<string, string>);
}

export async function fetchAuthorize(data: PackageApplicationsRelevanceDto) {
  return await post<PackageApplicationsRelevanceDto>(`${baseUrl}/authorize`, data);
}

export async function fetchUnauthorized(data: PackageApplicationsRelevanceDto) {
  return await post<PackageApplicationsRelevanceDto>(`${baseUrl}/unauthorized`, data);
}
