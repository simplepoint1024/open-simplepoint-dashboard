import {get, post, put} from '@simplepoint/shared/api/methods';
import api from '@/api';
import type {RequestOptions} from '@simplepoint/shared/api/client';

const {baseUrl} = api['rbac-role-resource-configuration'];

/** A supported page slot and the rule currently attached to this role grant. */
export interface ResourcePolicySlot {
  supported: boolean;
  policyId: string | null;
}

export interface RoleResourceRow {
  id: string;
  code: string;
  label?: string;
  type?: 'GROUP' | 'MODULE' | 'PAGE' | 'FEATURE' | 'ACTION' | 'API';
  path?: string;
  parentId?: string;
  icon?: string;
  hasChildren: boolean;
  hasNavigationChildren: boolean;
  description?: string;
  grantable: boolean;
  granted: boolean;
  revision: string;
  dataPermission: ResourcePolicySlot;
  fieldPermission: ResourcePolicySlot;
}

export interface RoleResourceChange {
  resourceId: string;
  resourceCode: string;
  revision: string;
  granted: boolean;
  dataPermission?: {policyId: string | null};
  fieldPermission?: {policyId: string | null};
}

export interface ResourcePolicyOption {id: string; name: string; detail?: string}

export async function fetchResourcePolicyOptions(roleId: string, resourceCode: string,
  kind: 'data' | 'field', keyword = '', signal?: AbortSignal) {
  const {normalizePage} = await import('@simplepoint/shared/types/request');
  const raw = await get<import('@simplepoint/shared/types/request').RawPage<ResourcePolicyOption>>(
    `${baseUrl}/roles/${encodeURIComponent(roleId)}/policy-options`,
    {resourceCode, kind, keyword, page: 0, size: 50}, {signal, notifyOnError: false});
  return normalizePage(raw, 50);
}

export async function fetchRoleResources(roleId: string, params: {
  page: number; size: number; keyword?: string; state?: string; type?: string;
  parentId?: string; rootsOnly?: boolean; navigationOnly?: boolean;
}, signal?: AbortSignal) {
  const {normalizePage} = await import('@simplepoint/shared/types/request');
  const page = await get<import('@simplepoint/shared/types/request').RawPage<RoleResourceRow>>(
    `${baseUrl}/roles/${encodeURIComponent(roleId)}/resources`, params, {signal, notifyOnError: false});
  return normalizePage(page, params.size);
}

export async function saveRoleResourceChanges(roleId: string, changes: RoleResourceChange[], options?: RequestOptions) {
  return put<void>(`${baseUrl}/roles/${encodeURIComponent(roleId)}/resource-changes`, {changes}, options);
}

export async function fetchRoleResourceContext(roleId: string, ids: string[], options?: RequestOptions) {
  return post<RoleResourceRow[]>(`${baseUrl}/roles/${encodeURIComponent(roleId)}/resource-context`, ids, options);
}

export interface RoleResourceBulkPreview {
  resourceId: string;
  granted: boolean;
  total: number;
  changed: number;
  revision: string;
}

export async function previewRoleResourceSubtree(roleId: string, resourceId: string, granted: boolean) {
  return get<RoleResourceBulkPreview>(`${baseUrl}/roles/${encodeURIComponent(roleId)}/resource-subtree`,
    {resourceId, granted});
}

export async function fetchRoleResourceSubtreeRows(roleId: string, resourceId: string, options?: RequestOptions) {
  return get<RoleResourceRow[]>(`${baseUrl}/roles/${encodeURIComponent(roleId)}/resource-subtree/resources`,
    {resourceId}, options);
}

export async function saveRoleResourceSubtree(roleId: string, preview: RoleResourceBulkPreview) {
  return put<void>(`${baseUrl}/roles/${encodeURIComponent(roleId)}/resource-subtree`, {
    resourceId: preview.resourceId, granted: preview.granted, revision: preview.revision,
  });
}
