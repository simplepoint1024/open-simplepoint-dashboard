type NavigationRoute = {
  routeKind?: string;
  type?: string;
  disabled?: boolean;
  component?: string;
  path?: string;
};

/** Hidden compatibility routes stay routable but cannot turn empty directories into menu links. */
export function hasVisibleNavigation(route: NavigationRoute, visibleChildren: number): boolean {
  if (route.routeKind === 'hidden' || route.disabled) return false;
  if (visibleChildren > 0) return true;
  if (route.routeKind === 'submenu' || route.routeKind === 'group'
    || route.type === 'MODULE' || route.type === 'GROUP') return false;
  return route.routeKind === 'divider' || Boolean(route.component || route.path);
}
