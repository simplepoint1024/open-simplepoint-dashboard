import api from "@/api";
import {get, post} from "@simplepoint/shared/api/methods";
import {Page} from "@simplepoint/shared/types/request";
import {RoleRelevantVo} from "@/api/system/role";

const {baseUrl} = api['rbac-users'];

/**
 * 用户角色分配接口
 */
export interface UserRoleRelevantDto {
    // 用户名
    userId: string | null;
    // 角色权限列表
    roleIds?: string[];
}

/**
 * 获取已分配角色下拉分页
 */
export async function fetchAuthorized(params: UserRoleRelevantDto) {
    // 避免 "authorized??userId=..." 的双问号，将查询参数交给 get 封装追加
    return await get<string[]>(`${baseUrl}/authorized`, params);
}

/**
 * 取消分配角色
 * @param data 请求数据
 */
export async function fetchUnauthorized(data: UserRoleRelevantDto) {
    return await post<UserRoleRelevantDto>(`${baseUrl}/unauthorized`, data);
}

/**
 * 分配角色
 * @param data
 */
export async function fetchAuthorize(data: UserRoleRelevantDto) {
    return await post<UserRoleRelevantDto>(`${baseUrl}/authorize`, data);
}

/**
 * 获取用户管理中可分配角色列表（始终使用默认租户范围）
 */
export async function fetchRoleCandidates(params: Record<string, string>) {
    return await get<Page<RoleRelevantVo>>(`${baseUrl}/role-candidates`, params);
}