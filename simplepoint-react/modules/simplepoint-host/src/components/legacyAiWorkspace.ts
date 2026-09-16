export const AI_WORKBENCH_ROOT_PATH = '/ai/workbench';
export const LEGACY_AI_WORKSPACE_PATH = '/ai/workbench/workspace';
export const DASHBOARD_PATH = '/dashboard';

export interface AiWorkbenchRoute {
  path?: string;
  component?: string;
  disabled?: boolean;
  routeKind?: string;
  children?: AiWorkbenchRoute[];
}

function isInternalWorkbenchPage(route: AiWorkbenchRoute): route is AiWorkbenchRoute & {path: string} {
  const path = route.path?.trim();
  const component = route.component?.trim();
  return !!path
    && path.startsWith(`${AI_WORKBENCH_ROOT_PATH}/`)
    && path !== LEGACY_AI_WORKSPACE_PATH
    && route.disabled !== true
    && route.routeKind !== 'hidden'
    && !!component
    && !component.startsWith('external:')
    && !component.startsWith('iframe:');
}

/** Resolve the first AI page that is present in the current user's authorized route tree. */
export function resolveLegacyAiWorkspaceTarget(
  routes: readonly AiWorkbenchRoute[] = [],
): string {
  for (const route of routes) {
    if (route.disabled === true || route.routeKind === 'hidden') {
      continue;
    }
    if (isInternalWorkbenchPage(route)) {
      return route.path.trim();
    }
    const childTarget = resolveLegacyAiWorkspaceTarget(route.children ?? []);
    if (childTarget !== DASHBOARD_PATH) {
      return childTarget;
    }
  }
  return DASHBOARD_PATH;
}

export function migrateLegacyAiWorkspacePath(
  path: string,
  target: string,
  routesReady = true,
): string {
  if (!routesReady) {
    return path;
  }
  if (path === AI_WORKBENCH_ROOT_PATH || path === LEGACY_AI_WORKSPACE_PATH) {
    return target;
  }
  return path;
}

export interface NavigationTabLocation {
  key: string;
  location: string;
}

function routePathOf(value: string): string {
  const normalized = value.trim().replace(/^#/, '');
  const queryIndex = normalized.indexOf('?');
  const fragmentIndex = normalized.indexOf('#');
  const indexes = [queryIndex, fragmentIndex].filter(index => index >= 0);
  const end = indexes.length > 0 ? Math.min(...indexes) : normalized.length;
  return normalized.slice(0, end);
}

/** Atomically migrates persisted tab keys and locations, then removes collisions. */
export function migrateLegacyAiWorkspaceTabs<T extends NavigationTabLocation>(
  tabs: readonly T[],
  target: string,
  routesReady: boolean,
): T[] {
  const seen = new Set<string>();
  const migrated: T[] = [];

  for (const tab of tabs) {
    const currentKey = routePathOf(tab.key);
    const key = migrateLegacyAiWorkspacePath(currentKey, target, routesReady);
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);

    const currentLocationPath = routePathOf(tab.location || tab.key);
    const locationPath = migrateLegacyAiWorkspacePath(
      currentLocationPath,
      target,
      routesReady,
    );
    const location = key !== currentKey || locationPath !== currentLocationPath
      ? key
      : tab.location;
    migrated.push({...tab, key, location} as T);
  }
  return migrated;
}
