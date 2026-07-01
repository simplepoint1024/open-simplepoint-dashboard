import {createIcon} from "@simplepoint/shared/types/icon.ts";
import {MenuInfo, MenuItemType} from "@/store/routes";
import type {MenuProps} from "antd";
import I18nText from '@/i18n/Text';

/**
 * Build a single menu item from a MenuInfo node.
 */
const toMenuItem = (
  menu: MenuInfo,
  navigate: (path: string) => void,
  children?: MenuItemType[],
): MenuItemType => {
  const existsChildren = !!children && children.length > 0;
  const keyText = menu.title ?? menu.label ?? '';
  const component = menu.component;
  const isExternal = typeof component === 'string' && component.startsWith('external:');
  const externalUrl = isExternal ? component!.slice('external:'.length) : undefined;

  return {
    key: menu.uuid || menu.path || (menu.id != null ? String(menu.id) : '') || keyText,
    label: <I18nText k={menu.title || ''} fallback={menu.label || ''} />,
    icon: menu.icon ? createIcon(menu.icon) : undefined,
    type: menu.type ?? undefined,
    danger: menu.danger ?? undefined,
    disabled: menu.disabled ?? undefined,
    component,
    ...(existsChildren ? { children } : {}),
    ...(!existsChildren ? {
      onClick: () => {
        if (isExternal && externalUrl) {
          try { window.open(externalUrl, '_blank', 'noopener,noreferrer'); } catch { /* blocked */ }
          return;
        }
        if (menu.path) navigate(menu.path);
      }
    } : {}),
  } as MenuItemType;
};

/**
 * 基于拍平(parent)关系构建菜单（保持返回数据原始顺序）
 */
const buildMenusFromFlat = (
  menus: MenuInfo[],
  navigate: (path: string) => void,
  parent: string | number | undefined = undefined,
): MenuItemType[] => {
  return menus
    .filter((m) => m.parent === parent)
    .map((menu) => {
      const children = buildMenusFromFlat(menus, navigate, menu.id);
      return toMenuItem(menu, navigate, children.length > 0 ? children : undefined);
    });
};

/**
 * 基于树结构(children)构建菜单（保持返回数据原始顺序）
 */
const buildMenusFromTree = (
  nodes: MenuInfo[],
  navigate: (path: string) => void,
): MenuItemType[] => {
  return (nodes || []).map((menu) => {
    const raw = menu.children;
    const builtChildren = Array.isArray(raw) && raw.length > 0 ? buildMenusFromTree(raw, navigate) : undefined;
    return toMenuItem(menu, navigate, builtChildren);
  });
};

/**
 * 构建路由菜单：自动识别传入数据为拍平还是树结构
 */
export const buildMenus = (
  menus: MenuInfo[],
  navigate: (path: string) => void,
): MenuItemType[] => {
  const isTreeData = Array.isArray(menus) && menus.some(m => Array.isArray(m.children));
  return isTreeData ? buildMenusFromTree(menus, navigate) : buildMenusFromFlat(menus, navigate, undefined);
};

/** 侧边菜单 */
export const useSideNavigation = (navigate: (path: string) => void, menuData: MenuInfo[]): MenuProps => {
  return { items: buildMenus(menuData, navigate) };
};