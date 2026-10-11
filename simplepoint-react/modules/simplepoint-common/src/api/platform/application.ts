import api from '@/api';
import {get, post} from '@simplepoint/shared/api/methods';
import {Page} from '@simplepoint/shared/types/request';
import type {RequestOptions} from '@simplepoint/shared/api/client';

const {baseUrl} = api['platform.applications'];

export interface ApplicationRelevantVo {
  id: string;
  name: string;
  code: string;
  description: string;
}

export interface ApplicationResourcesRelevanceDto {
  applicationCode: string | null;
  resourceCodes?: string[];
}

export async function fetchItems(params: Record<string, string>) {
  return await get<Page<ApplicationRelevantVo>>(`${baseUrl}/items`, params);
}

export async function fetchAuthorized(params: Pick<ApplicationResourcesRelevanceDto, 'applicationCode'>, options?: RequestOptions) {
  return await get<string[]>(`${baseUrl}/authorized`, params as Record<string, string>, options);
}

export async function fetchAuthorize(data: ApplicationResourcesRelevanceDto, options?: RequestOptions) {
  return await post<ApplicationResourcesRelevanceDto>(`${baseUrl}/authorize`, data, options);
}

export async function fetchUnauthorized(data: ApplicationResourcesRelevanceDto, options?: RequestOptions) {
  return await post<ApplicationResourcesRelevanceDto>(`${baseUrl}/unauthorized`, data, options);
}
