export interface OrganizationOption {
  id: string; name: string; code?: string | null; parentId?: string | null; type?: string | null;
  description?: string | null; sort?: number | null; enabled: boolean; hasChildren: boolean;
}
export type OrganizationOptionPage = {content: OrganizationOption[]; number: number; size: number; hasNext: boolean};
export type OptionWindow = {pages: OrganizationOptionPage[]};
export const ORGANIZATION_WINDOW_PAGES = 10;
export const ORGANIZATION_BRANCHES = 20;
export const rootChannel = 'browse:root';
export const searchChannel = 'browse:search';
export const branchChannel = (id: string) => 'browse:children:' + JSON.stringify(id) + ':';

export function organizationId(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() && !/^\*+$/.test(value.trim()) ? value : undefined;
}
const display = (value: unknown) => typeof value === 'string' && value.trim() && !/^\*+$/.test(value.trim()) ? value : '';
export function organizationLabel(option: OrganizationOption): string {
  const name = display(option.name), code = display(option.code);
  return name && code && name !== code ? `${name} (${code})` : name || code || option.id;
}
export const optionRows = (window?: OptionWindow): OrganizationOption[] => [...new Map(
  (window?.pages.flatMap(page => page.content) ?? []).map(option => [option.id, option])).values()];

/** Count-free page metadata must describe the requested slice, never silently advance on stale pages. */
export function organizationPage(raw: unknown, number: number): OrganizationOptionPage {
  const page = raw as OrganizationOptionPage | undefined;
  if (!page || page.number !== number || !Number.isSafeInteger(page.size) || page.size < 1 || page.size > 100
    || typeof page.hasNext !== 'boolean' || !Array.isArray(page.content) || page.content.length > page.size)
    throw Error('Organization options page unavailable');
  const content = page.content.flatMap(row => organizationId(row?.id) ? [{...row,
    name: display(row.name), code: display(row.code), enabled: row.enabled === true, hasChildren: row.hasChildren === true}] : []);
  return {...page, content};
}
export function mergeOptionWindow(previous: OptionWindow | undefined, page: OrganizationOptionPage,
  direction: 'replace' | 'next' | 'previous'): OptionWindow {
  const pages = (direction === 'replace' ? [] : previous?.pages ?? []).filter(value => value.number !== page.number);
  pages.push(page); pages.sort((left, right) => left.number - right.number);
  return {pages: direction === 'previous' ? pages.slice(0, ORGANIZATION_WINDOW_PAGES) : pages.slice(-ORGANIZATION_WINDOW_PAGES)};
}
export function putOptionWindow(cache: ReadonlyMap<string, OptionWindow>, channel: string, window: OptionWindow) {
  const next = new Map(cache); next.delete(channel); next.set(channel, window);
  const branches = [...next.keys()].filter(key => key.startsWith('browse:children:'));
  const evicted = branches.slice(0, Math.max(0, branches.length - ORGANIZATION_BRANCHES));
  evicted.forEach(key => next.delete(key));
  return {cache: next, evicted};
}

export type OrganizationProjection = {option: OrganizationOption; children?: OrganizationProjection[]};
/** Only reachable cached branches are projected; cycles/duplicate identities never create recursive UI trees. */
export function organizationProjection(cache: ReadonlyMap<string, OptionWindow>) {
  const seen = new Set<string>();
  const project = (rows: OrganizationOption[]): OrganizationProjection[] => rows.flatMap(option => {
    if (seen.has(option.id)) return []; seen.add(option.id);
    const branch = cache.get(branchChannel(option.id));
    return [{option, ...(branch?.pages.length ? {children: project(optionRows(branch))} : {})}];
  });
  return project(optionRows(cache.get(rootChannel)));
}
