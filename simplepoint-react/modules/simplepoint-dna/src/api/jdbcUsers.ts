import api from '@/api';
import {get, post, put} from '@simplepoint/shared/api/methods';
import type {Page} from '@simplepoint/shared/types/request';

const {baseUrl} = api['platform.dna-federation-jdbc-users'];

export interface JdbcUserDataSourceItem {
  id: string;
  code?: string;
  name?: string;
  databaseProductName?: string;
}

export interface JdbcUserDataSourceAssignDto {
  userId?: string | null;
  dataSourceIds?: string[];
}

export interface JdbcUserGrant {
  id: string;
  userId?: string;
  catalogId?: string;
  operationPermissions?: string[];
}

export async function fetchItems(params: Record<string, string | number | undefined>) {
  return await get<Page<JdbcUserDataSourceItem>>(`${baseUrl}/items`, params);
}

export async function fetchSelectedItems(dataSourceIds: string[]) {
  return await post<JdbcUserDataSourceItem[]>(`${baseUrl}/selected-items`, dataSourceIds);
}

export async function fetchAuthorized(userId: string) {
  return await get<string[]>(`${baseUrl}/authorized`, {userId});
}

export async function fetchGrants(userId: string) {
  return await get<JdbcUserGrant[]>(`${baseUrl}/grants`, {userId});
}

export async function fetchAuthorize(data: JdbcUserDataSourceAssignDto) {
  return await post<JdbcUserGrant[]>(`${baseUrl}/authorize`, data);
}

export async function fetchUnauthorized(data: JdbcUserDataSourceAssignDto) {
  return await post(`${baseUrl}/unauthorized`, data);
}

export async function updateGrantPermissions(grantId: string, permissions: string[]) {
  return await put(`${baseUrl}/permissions?grantId=${encodeURIComponent(grantId)}`, permissions);
}
