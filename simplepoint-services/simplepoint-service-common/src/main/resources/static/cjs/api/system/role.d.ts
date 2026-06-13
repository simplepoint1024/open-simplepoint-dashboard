import { Page } from "@simplepoint/shared/types/request";
/**
 * 角色下拉选项接口
 */
export interface RoleRelevantVo {
    name: string;
    description: string;
    id: string;
}
/**
 * 角色权限分配接口
 */
export interface RolePermissionRelevantDto {
    roleId: string | null;
    permissionAuthority?: string[];
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
export declare function fetchItems(params: Record<string, string>): Promise<Page<RoleRelevantVo>>;
/**
 * 获取已分配角色的权限下拉分页
 */
export declare function fetchAuthorized(roleId: string): Promise<string[]>;
/**
 * 取消分配角色权限
 * @param data 请求数据
 */
export declare function fetchUnauthorized(data: RolePermissionRelevantDto): Promise<RolePermissionRelevantDto>;
/**
 * 分配角色权限
 * @param data 请求数据
 */
export declare function fetchAuthorize(data: RolePermissionRelevantDto): Promise<RolePermissionRelevantDto>;
/**
 * 查询角色的数据权限和字段权限分配
 */
export declare function fetchScopeAssignment(roleId: string): Promise<RoleScopeAssignmentVo>;
/**
 * 更新角色的数据权限和字段权限分配
 */
export declare function updateScopeAssignment(vo: RoleScopeAssignmentVo): Promise<void>;
