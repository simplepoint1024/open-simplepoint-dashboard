/** One identity for menu items, selected keys and expanded ancestors, including pathless groups. */
export const getRouteKey = (route?: {
  uuid?: string;
  path?: string;
  id?: string | number;
  code?: string;
  title?: string;
  label?: string;
}): string | undefined => {
  if (!route) return undefined;
  return route.uuid || route.path || (route.id != null ? String(route.id) : undefined)
    || route.code || route.title || route.label || undefined;
};

/** Route data may arrive after the URL; retry synchronization when its identity chain changes. */
export const menuSyncIdentity = (path: string, keys: string[]): string => JSON.stringify([path, keys]);
