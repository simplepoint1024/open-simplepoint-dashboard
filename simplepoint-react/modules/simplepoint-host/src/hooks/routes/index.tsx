import {createIcon} from "@simplepoint/shared/types/icon.ts";
import {RouteInfo, MenuItemType} from "@/store/routes";
import type {MenuProps} from "antd";
import I18nText from '@/i18n/Text';

/**
 * Build a single menu item from a resource route node.
 */
const toMenuItem = (
  route: RouteInfo,
  navigate: (path: string) => void,
  children?: MenuItemType[],
): MenuItemType => {
  const existsChildren = !!children && children.length > 0;
  const keyText = route.title ?? route.label ?? '';
  const component = route.component;
  const isExternal = typeof component === 'string' && component.startsWith('external:');
  const externalUrl = isExternal ? component!.slice('external:'.length) : undefined;
  const menuType = route.routeKind === 'group' || route.routeKind === 'divider'
    ? route.routeKind
    : undefined;

  return {
    key: route.uuid || route.path || (route.id != null ? String(route.id) : '') || keyText,
    label: <I18nText k={route.title || ''} fallback={route.label || ''} />,
    icon: route.icon ? createIcon(route.icon) : undefined,
    // Ant Design infers a collapsible SubMenu from children. Only visual groups
    // and dividers are represented through its `type` field.
    type: menuType,
    danger: route.danger ?? undefined,
    disabled: route.disabled ?? undefined,
    component,
    ...(existsChildren ? { children } : {}),
    ...(!existsChildren ? {
      onClick: () => {
        if (isExternal && externalUrl) {
          try { window.open(externalUrl, '_blank', 'noopener,noreferrer'); } catch { /* blocked */ }
          return;
        }
        if (route.path) navigate(route.path);
      }
    } : {}),
  } as MenuItemType;
};

/**
 * 基于拍平(parentId)关系构建导航（保持返回数据原始顺序）
 */
const buildNavigationFromFlat = (
  routes: RouteInfo[],
  navigate: (path: string) => void,
  parent: string | number | undefined = undefined,
): MenuItemType[] => {
  return routes
    .filter((route) => route.parentId === parent)
    .map((route) => {
      const children = buildNavigationFromFlat(routes, navigate, route.id);
      return toMenuItem(route, navigate, children.length > 0 ? children : undefined);
    });
};

/**
 * 基于树结构(children)构建导航（保持返回数据原始顺序）
 */
const buildNavigationFromTree = (
  nodes: RouteInfo[],
  navigate: (path: string) => void,
): MenuItemType[] => {
  return (nodes || []).map((route) => {
    const raw = route.children;
    const builtChildren = Array.isArray(raw) && raw.length > 0 ? buildNavigationFromTree(raw, navigate) : undefined;
    return toMenuItem(route, navigate, builtChildren);
  });
};

/**
 * 构建导航菜单：自动识别传入数据为拍平还是树结构
 */
export const buildNavigation = (
  routes: RouteInfo[],
  navigate: (path: string) => void,
): MenuItemType[] => {
  const isTreeData = Array.isArray(routes) && routes.some(route => Array.isArray(route.children));
  return isTreeData ? buildNavigationFromTree(routes, navigate) : buildNavigationFromFlat(routes, navigate, undefined);
};

/** 侧边菜单 */
export const useSideNavigation = (navigate: (path: string) => void, routeData: RouteInfo[]): MenuProps => {
  return { items: buildNavigation(routeData, navigate) };
};
