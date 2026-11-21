/** Explicit view identities create independent retained tabs; ordinary query strings keep one tab. */
export const PAGE_VIEW_PARAMETER = 'sp-view';
export type PageViewIdentity = {version: 1; kind: string; identity: readonly string[]};
export type PageViewSelection = {state: 'none'} | {state: 'invalid'} | {state: 'view'; view: PageViewIdentity};

export function pageRoutePath(value?: string): string {
  return (value ?? '').trim().replace(/^#/, '').split(/[?#]/, 1)[0] || '/';
}

function validView(value: unknown): value is PageViewIdentity {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const view = value as PageViewIdentity;
  return Object.keys(value).length === 3 && view.version === 1
    && typeof view.kind === 'string' && /^[a-z][a-z0-9.-]{0,47}$/.test(view.kind)
    && Array.isArray(view.identity) && view.identity.length > 0 && view.identity.length <= 6
    && view.identity.every(part => typeof part === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$/.test(part));
}

export function pageViewSelection(location: string): PageViewSelection {
  const raw = location.trim().replace(/^#/, '').split('#', 1)[0];
  const index = raw.indexOf('?');
  if (index < 0) return {state: 'none'};
  const parameters = new URLSearchParams(raw.slice(index + 1));
  const values = parameters.getAll(PAGE_VIEW_PARAMETER);
  if (!values.length) return {state: 'none'};
  if (values.length !== 1 || values[0].length > 1024) return {state: 'invalid'};
  try {
    const view = JSON.parse(values[0]);
    return validView(view) ? {state: 'view', view: {version: 1, kind: view.kind, identity: [...view.identity]}} : {state: 'invalid'};
  } catch {return {state: 'invalid'};}
}

export function pageViewLocation(route: string, view: PageViewIdentity): string {
  if (!/^\/(?!\/)[A-Za-z0-9/_-]+$/.test(route) || !validView(view)) throw new Error('Invalid page view identity');
  return `${route}?${new URLSearchParams({[PAGE_VIEW_PARAMETER]: JSON.stringify({version: 1, kind: view.kind, identity: view.identity})})}`;
}

/** Invalid view links never replace a resource's ordinary tab or its unsaved draft. */
export function pageTabIdentity(location: string): string {
  const route = pageRoutePath(location), selection = pageViewSelection(location);
  if (selection.state === 'none') return route;
  if (selection.state === 'invalid') return `${route}?${PAGE_VIEW_PARAMETER}=invalid`;
  return `${route}?${new URLSearchParams({[PAGE_VIEW_PARAMETER]: JSON.stringify(selection.view)})}`;
}
