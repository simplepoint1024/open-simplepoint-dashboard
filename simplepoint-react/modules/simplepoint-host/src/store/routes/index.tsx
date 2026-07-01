import type {ItemType} from "antd/es/menu/interface";

/** Ant Design menu item extended with the component spec for route matching. */
export type MenuItemType = ItemType & { component?: string };

export interface MenuInfo {
  id?: string | number;
  path?: string;
  parent?: string;
  authority?: string;
  title?: string;
  label?: string;
  icon?: string;
  danger?: boolean;
  type?: 'group' | 'divider' | 'submenu' | 'item';
  disabled?: boolean;
  component?: string;
  sort?: number;
  children?: MenuInfo[];
  /** Server-side UUID (used as key in tree-structured data). */
  uuid?: string;
  /** When true, accessing this route under a personal tenant shows an error page. */
  requireOrgTenant?: boolean;
}

// 判断是否存在子节点
export const hasChildren = (node?: MenuInfo) => Array.isArray(node?.children) && node!.children!.length > 0;

// 扁平化树形菜单（返回所有叶子节点）
export const flattenMenus = (nodes: MenuInfo[] = []): MenuInfo[] => {
  const res: MenuInfo[] = [];
  const dfs = (arr: MenuInfo[]) => {
    arr.forEach(n => {
      if (hasChildren(n)) {
        dfs(n!.children!);
      } else {
        res.push(n);
      }
    });
  };
  dfs(nodes);
  return res;
};

// 获取菜单节点的唯一标识
export const getMenuKey = (menu?: MenuInfo): string | undefined => {
  if (!menu) return undefined;
  return menu.path || (menu.id != null ? String(menu.id) : undefined);
};

// 根据路径查找从根到叶的菜单链
export const findMenuChainByPath = (menus: MenuInfo[], path: string): MenuInfo[] => {
  const dfs = (nodes: MenuInfo[], chain: MenuInfo[]): MenuInfo[] | null => {
    for (const node of nodes) {
      const next = [...chain, node];
      if (node.path === path) return next;
      if (hasChildren(node)) {
        const found = dfs(node.children!, next);
        if (found) return found;
      }
    }
    return null;
  };
  return dfs(menus, []) || [];
};
