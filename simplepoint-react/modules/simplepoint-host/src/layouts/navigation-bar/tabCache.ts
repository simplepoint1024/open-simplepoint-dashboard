export const MAX_CACHED_PAGES = 8;

export function closeTargets(tabs: readonly {key: string; closable?: boolean}[], active: string, action: string) {
  if (action === 'refresh') return [active];
  const index = tabs.findIndex(tab => tab.key === active);
  return tabs.filter((tab, i) => {
    if (action === 'clear' || action === 'closeAll') return true;
    if (tab.key === active || tab.closable === false) return false;
    if (action === 'closeOthers') return true;
    if (index < 0) return false;
    return action === 'closeLeft' ? i < index : action === 'closeRight' && i > index;
  }).map(tab => tab.key);
}

/** Oldest first. Unknown/dirty/busy pages are protected, even above the soft budget. */
export function retainPages(
  previous: readonly string[],
  open: readonly string[],
  active: string,
  canEvict: (key: string) => boolean,
  limit = MAX_CACHED_PAGES,
): string[] {
  const openKeys = new Set(open);
  const retained = [...new Set(previous)].filter(key => openKeys.has(key) && key !== active);
  if (openKeys.has(active)) retained.push(active);
  for (let index = 0; retained.length > Math.max(1, limit) && index < retained.length;) {
    const key = retained[index];
    if (key !== active && canEvict(key)) retained.splice(index, 1);
    else index++;
  }
  return retained;
}

export function ownersAllowEviction(owners?: Record<string, boolean>): boolean {
  const permissions = Object.values(owners ?? {});
  return permissions.length > 0 && permissions.every(Boolean);
}
