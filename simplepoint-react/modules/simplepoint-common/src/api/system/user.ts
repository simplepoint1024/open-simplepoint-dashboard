import api from "@/api";
import {post} from "@simplepoint/shared/api/methods";

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
