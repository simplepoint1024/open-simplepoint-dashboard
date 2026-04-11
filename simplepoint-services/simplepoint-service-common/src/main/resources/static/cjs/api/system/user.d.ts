/**
 * 用户角色分配接口
 */
export interface UserRoleRelevantDto {
    userId: string | null;
    roleIds?: string[];
}
/**
 * 获取已分配角色下拉分页
 */
export declare function fetchAuthorized(params: UserRoleRelevantDto): Promise<string[]>;
/**
 * 取消分配角色
 * @param data 请求数据
 */
export declare function fetchUnauthorized(data: UserRoleRelevantDto): Promise<UserRoleRelevantDto>;
/**
 * 分配角色
 * @param data
 */
export declare function fetchAuthorize(data: UserRoleRelevantDto): Promise<UserRoleRelevantDto>;
