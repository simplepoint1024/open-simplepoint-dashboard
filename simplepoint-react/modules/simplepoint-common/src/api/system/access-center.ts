import {get, put} from '@simplepoint/shared/api/methods';
import {Page} from '@simplepoint/shared/types/request';
import api from '@/api';

const {baseUrl} = api['rbac-access-center'];

export interface AccessCenterRole {
  id: string;
  name: string;
  authority: string;
  description?: string;
}

export interface AccessCenterScope {
  id: string;
  name: string;
  type?: string | null;
  description?: string | null;
}

export interface AccessCenterUserImpact {
  id: string;
  name?: string | null;
  email?: string | null;
  phoneNumber?: string | null;
}

export interface AccessCenterRoleOverview {
  role: AccessCenterRole;
  resourceCount: number;
  assignedUserCount: number;
  dataScope?: AccessCenterScope | null;
  fieldScope?: AccessCenterScope | null;
}

export interface AccessCenterRoleDetail {
  role: AccessCenterRole;
  authorizedResources: string[];
  scopeAssignment?: {
    roleId: string;
    dataScopeId?: string | null;
    fieldScopeId?: string | null;
  };
  dataScope?: AccessCenterScope | null;
  fieldScope?: AccessCenterScope | null;
  assignedUserCount: number;
  assignedUsers: AccessCenterUserImpact[];
}

export type AccessCenterResourceNodeType = 'GROUP' | 'MODULE' | 'PAGE' | 'FEATURE' | 'ACTION' | 'API';

export interface AccessCenterResourceNode {
  id: string;
  type: AccessCenterResourceNodeType;
  label: string;
  code?: string | null;
  path?: string | null;
  description?: string | null;
  resourceCode?: string | null;
  grantable?: boolean | null;
  checked: boolean;
  partial: boolean;
  resourceCodes: string[];
  children: AccessCenterResourceNode[];
}

export interface AccessCenterRoleAuthorizationDto {
  roleId: string;
  resourceCodes: string[];
  dataScopeId?: string | null;
  fieldScopeId?: string | null;
}

export async function fetchRoleOverviews(params?: Record<string, string>) {
  return await get<Page<AccessCenterRoleOverview>>(`${baseUrl}/roles`, params);
}

export async function fetchRoleDetail(roleId: string) {
  return await get<AccessCenterRoleDetail>(`${baseUrl}/roles/${encodeURIComponent(roleId)}`);
}

export async function fetchResourceTree(roleId: string) {
  return await get<AccessCenterResourceNode[]>(`${baseUrl}/roles/${encodeURIComponent(roleId)}/resource-tree`);
}

export async function saveRoleAuthorization(data: AccessCenterRoleAuthorizationDto) {
  return await put<AccessCenterRoleDetail>(
    `${baseUrl}/roles/${encodeURIComponent(data.roleId)}/authorization`,
    data,
  );
}
