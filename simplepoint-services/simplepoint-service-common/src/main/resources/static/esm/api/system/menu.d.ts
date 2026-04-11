/**
 * 菜单功能分配接口
 */
export interface MenuFeatureRelevantDto {
    menuId: string | null;
    featureCodes?: string[];
    permissionAuthority?: string[];
}
/**
 * 获取菜单已绑定功能
 */
export declare function fetchAuthorized(params: Pick<MenuFeatureRelevantDto, 'menuId'>): Promise<string[]>;
/**
 * 取消菜单功能绑定
 * @param data 请求数据
 */
export declare function fetchUnauthorized(data: MenuFeatureRelevantDto): Promise<MenuFeatureRelevantDto>;
/**
 * 绑定菜单功能
 * @param data 请求数据
 */
export declare function fetchAuthorize(data: MenuFeatureRelevantDto): Promise<MenuFeatureRelevantDto>;
