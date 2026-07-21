import type {ItemType} from "antd/es/menu/interface";

/** Ant Design menu item extended with the component spec for route matching. */
export type MenuItemType = ItemType & { component?: string };

export interface RouteInfo {
  id?: string | number;
  path?: string;
  parentId?: string;
  code?: string;
  title?: string;
  label?: string;
  icon?: string;
  danger?: boolean;
  routeKind?: 'group' | 'divider' | 'submenu' | 'item';
  type?: string;
  disabled?: boolean;
  component?: string;
  sort?: number;
  children?: RouteInfo[];
  /** Server-side UUID (used as key in tree-structured data). */
  uuid?: string;
  /** When true, accessing this route under a personal tenant shows an error page. */
  requireOrgTenant?: boolean;
  scopeTypes?: Array<'SYSTEM' | 'PLATFORM' | 'TENANT' | 'PERSONAL'>;
}

// 判断是否存在子节点
export const hasChildren = (node?: RouteInfo) => Array.isArray(node?.children) && node!.children!.length > 0;

// 扁平化树形路由（返回所有叶子节点）
export const flattenRoutes = (nodes: RouteInfo[] = []): RouteInfo[] => {
  const res: RouteInfo[] = [];
  const dfs = (arr: RouteInfo[]) => {
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
export const getRouteKey = (route?: RouteInfo): string | undefined => {
  if (!route) return undefined;
  return route.path || (route.id != null ? String(route.id) : undefined);
};

// 根据路径查找从根到叶的路由链
export const findRouteChainByPath = (routes: RouteInfo[], path: string): RouteInfo[] => {
  const dfs = (nodes: RouteInfo[], chain: RouteInfo[]): RouteInfo[] | null => {
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
  return dfs(routes, []) || [];
};
