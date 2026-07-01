import {get, post} from "@simplepoint/shared/api/methods";
import api from "@/api";

const {baseUrl} = api['rbac-menus']

/**
 * 菜单功能分配接口
 */
export interface MenuFeatureRelevantDto {
  // 所选菜单
  menuId: string | null;
  // 功能编码列表
  featureCodes?: string[];
  // 兼容历史权限点字段
  permissionAuthority?: string[];
}

/**
 * 获取菜单已绑定功能
 */
export async function fetchAuthorized(params: Pick<MenuFeatureRelevantDto, 'menuId'>) {
  return await get<string[]>(`${baseUrl}/authorized`, params);
}

/**
 * 取消菜单功能绑定
 * @param data 请求数据
 */
export async function fetchUnauthorized(data: MenuFeatureRelevantDto) {
  return await post<MenuFeatureRelevantDto>(`${baseUrl}/unauthorized`, data);
}

/**
 * 绑定菜单功能
 * @param data 请求数据
 */
export async function fetchAuthorize(data: MenuFeatureRelevantDto) {
  return await post<MenuFeatureRelevantDto>(`${baseUrl}/authorize`, data);
}