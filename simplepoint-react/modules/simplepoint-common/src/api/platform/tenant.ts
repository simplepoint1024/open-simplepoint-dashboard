import api from '@/api';
import {get, post} from '@simplepoint/shared/api/methods';
import {Page} from '@simplepoint/shared/types/request';

const {baseUrl} = api['platform.tenants'];

export interface TenantPackagesRelevanceDto {
  tenantId: string | null;
  packageCodes?: string[];
}

export interface TenantUsersRelevanceDto {
  tenantId: string | null;
  userIds?: string[];
}

export interface UserRelevanceVo {
  id: string;
  name: string;
  email?: string;
  phoneNumber?: string;
}

export async function fetchAuthorized(params: Pick<TenantPackagesRelevanceDto, 'tenantId'>) {
  return await get<string[]>(`${baseUrl}/authorized`, params as Record<string, string>);
}

export async function fetchAuthorize(data: TenantPackagesRelevanceDto) {
  return await post<TenantPackagesRelevanceDto>(`${baseUrl}/authorize`, data);
}

export async function fetchUnauthorized(data: TenantPackagesRelevanceDto) {
  return await post<TenantPackagesRelevanceDto>(`${baseUrl}/unauthorized`, data);
}

export async function fetchOwnerItems(params: {page: string; size: string}) {
  return await get<Page<UserRelevanceVo>>(`${baseUrl}/owners/items`, params as Record<string, string>);
}

export async function fetchUserItems(params: {tenantId: string; page: string; size: string; keyword?: string}) {
  return await get<Page<UserRelevanceVo>>(`${baseUrl}/users/items`, params as Record<string, string>);
}

export async function fetchAuthorizedUsers(params: Pick<TenantUsersRelevanceDto, 'tenantId'>) {
  return await get<string[]>(`${baseUrl}/users/authorized`, params as Record<string, string>);
}

export async function fetchAuthorizeUsers(data: TenantUsersRelevanceDto) {
  return await post<TenantUsersRelevanceDto>(`${baseUrl}/users/authorize`, data);
}

export async function fetchUnauthorizedUsers(data: TenantUsersRelevanceDto) {
  return await post<TenantUsersRelevanceDto>(`${baseUrl}/users/unauthorized`, data);
}
