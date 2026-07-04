import {get, post, put} from "@simplepoint/shared/api/methods";
import {Page} from "@simplepoint/shared/types/request"
import api from "@/api";

const {baseUrl} = api['rbac-roles']

/**
 * 角色下拉选项接口
 */
export interface RoleRelevantVo {
  name: string;
  description: string;
  id: string;
}

/**
 * 角色资源授权接口
 */
export interface RoleResourceGrantDto {
  roleId: string | null;
  resourceCodes?: string[];
  dataScopeId?: string | null;
  fieldScopeId?: string | null;
}

/**
 * 角色数据/字段权限分配 VO
 */
export interface RoleScopeAssignmentVo {
  roleId: string;
  dataScopeId?: string | null;
  fieldScopeId?: string | null;
}

/**
 * 获取下拉角色分页
 * @param params 查询参数
 */
export async function fetchItems(params: Record<string, string>) {
  return await get<Page<RoleRelevantVo>>(`${baseUrl}/items`, params);
}

/**
 * 获取已分配角色的资源编码
 */
export async function fetchAuthorized(roleId: string) {
  return await get<string[]>(`${baseUrl}/authorized?roleId=${roleId}`);
}

/**
 * 取消分配角色权限
 * @param data 请求数据
 */
export async function fetchUnauthorized(data: RoleResourceGrantDto) {
  return await post<RoleResourceGrantDto>(`${baseUrl}/unauthorized`, data);
}

/**
 * 分配角色权限
 * @param data 请求数据
 */
export async function fetchAuthorize(data: RoleResourceGrantDto) {
  return await post<RoleResourceGrantDto>(`${baseUrl}/authorize`, data);
}

/**
 * 查询角色的数据权限和字段权限分配
 */
export async function fetchScopeAssignment(roleId: string) {
  return await get<RoleScopeAssignmentVo>(`${baseUrl}/scope-assignment?roleId=${encodeURIComponent(roleId)}`);
}

/**
 * 更新角色的数据权限和字段权限分配
 */
export async function updateScopeAssignment(vo: RoleScopeAssignmentVo) {
  return await put<void>(`${baseUrl}/scope-assignment`, vo);
}
