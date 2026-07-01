import api from '@/api';
import {get, post} from '@simplepoint/shared/api/methods';
import {Page} from '@simplepoint/shared/types/request';

const {baseUrl} = api['platform.applications'];

export interface ApplicationRelevantVo {
  id: string;
  name: string;
  code: string;
  description: string;
}

export interface ApplicationFeaturesRelevanceDto {
  applicationCode: string | null;
  featureCodes?: string[];
}

export async function fetchItems(params: Record<string, string>) {
  return await get<Page<ApplicationRelevantVo>>(`${baseUrl}/items`, params);
}

export async function fetchAuthorized(params: Pick<ApplicationFeaturesRelevanceDto, 'applicationCode'>) {
  return await get<string[]>(`${baseUrl}/authorized`, params as Record<string, string>);
}

export async function fetchAuthorize(data: ApplicationFeaturesRelevanceDto) {
  return await post<ApplicationFeaturesRelevanceDto>(`${baseUrl}/authorize`, data);
}

export async function fetchUnauthorized(data: ApplicationFeaturesRelevanceDto) {
  return await post<ApplicationFeaturesRelevanceDto>(`${baseUrl}/unauthorized`, data);
}
