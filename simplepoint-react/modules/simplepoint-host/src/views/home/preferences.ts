import type {RouteInfo} from '../../store/routes/index';

export type HomeGroup = {id: string; name: string; paths: string[]};
export type HomePreferences = {version: 1; groups: HomeGroup[]; region: string; timeZone: string};
export const MAX_GROUPS = 12;
export const MAX_APPS = 24;
export const emptyPreferences = (): HomePreferences => ({version: 1, groups: [], region: '', timeZone: ''});
export const safePath = (value: unknown): value is string => typeof value === 'string'
  && value.startsWith('/') && !value.startsWith('//') && !/[\s\\?#:*]/.test(value) && value.length <= 512;
export function validTimeZone(value: string): boolean {
  try { new Intl.DateTimeFormat('en', {timeZone: value}).format(); return true; } catch { return false; }
}

/** Reject malformed stored documents instead of silently overwriting personal settings. */
export function parsePreferences(value: unknown): HomePreferences {
  if (value == null || value === '') return emptyPreferences();
  const data = typeof value === 'string' ? JSON.parse(value) : value;
  if (!data || data.version !== 1 || !Array.isArray(data.groups) || data.groups.length > MAX_GROUPS) throw new Error('Invalid home preferences');
  const ids = new Set<string>();
  const groups = data.groups.map((group: HomeGroup) => {
    if (!group || typeof group.id !== 'string' || !group.id || group.id.length > 80 || ids.has(group.id)
      || typeof group.name !== 'string' || !group.name.trim() || group.name.trim().length > 40
      || !Array.isArray(group.paths) || group.paths.length > MAX_APPS || !group.paths.every(safePath)) throw new Error('Invalid home group');
    ids.add(group.id);
    return {id: group.id, name: group.name.trim(), paths: [...new Set(group.paths)]};
  });
  if (typeof data.region !== 'string' || data.region.length > 60 || typeof data.timeZone !== 'string'
    || (data.timeZone && !validTimeZone(data.timeZone))) throw new Error('Invalid home location');
  return {version: 1, groups, region: data.region.trim(), timeZone: data.timeZone};
}

export function availableApps(routes: RouteInfo[], tenantType?: string): Array<RouteInfo & {path: string}> {
  const seen = new Set<string>();
  return routes.filter((route): route is RouteInfo & {path: string} => {
    if (!safePath(route.path) || route.path === '/dashboard' || !route.component || route.disabled
      || route.routeKind === 'hidden' || route.component.startsWith('external:')
      || (route.requireOrgTenant && tenantType === 'PERSONAL') || seen.has(route.path)) return false;
    seen.add(route.path);
    return true;
  });
}
