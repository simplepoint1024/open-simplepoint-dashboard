export const LEGACY_WORKFLOW_TASKS = '/workflow/tasks';
export const LEGACY_WORKFLOW_INSTANCES = '/workflow/instances';
type Route = {path?: string; component?: string; disabled?: boolean; routeKind?: string; children?: Route[]};
export type WorkflowTargets = {tasks?: string; items?: string; management?: string};

/** Compatibility redirects only resolve pages present in the currently authorized route tree. */
export function resolveWorkflowTargets(routes: readonly Route[] = []): WorkflowTargets {
  const result: WorkflowTargets = {};
  const visit = (nodes: readonly Route[]) => nodes.forEach(node => {
    if (node.disabled || node.routeKind === 'hidden') return;
    if (node.path && /^\/(?!\/)[^?#\\\s]*$/.test(node.path)) {
      if (node.component === 'common/personal/Todos') result.tasks ??= node.path;
      if (node.component === 'common/personal/Items') result.items ??= node.path;
      if (node.component === 'workflow/Management') result.management ??= node.path;
    }
    visit(node.children ?? []);
  });
  visit(routes);
  return result;
}

export function migrateWorkflowLocation(location: string, targets: WorkflowTargets, ready = true): string {
  if (!ready) return location;
  const raw = location.replace(/^#/, ''), path = raw.split(/[?#]/, 1)[0];
  if (path !== LEGACY_WORKFLOW_TASKS && path !== LEGACY_WORKFLOW_INSTANCES) return location;
  const parsed = new URL(raw, 'https://workflow.invalid');
  const all = parsed.searchParams.get('view') === 'all' || parsed.searchParams.get('scope') === 'all';
  const target = path === LEGACY_WORKFLOW_TASKS ? targets.tasks : all && targets.management ? targets.management : targets.items;
  if (!target) return '/dashboard';
  const legacyId = parsed.searchParams.get('id');
  if (legacyId) {
    const parameter = path === LEGACY_WORKFLOW_TASKS ? 'task' : 'instance';
    if (!parsed.searchParams.has(parameter)) parsed.searchParams.set(parameter, legacyId);
    parsed.searchParams.delete('id');
  }
  const search = parsed.searchParams.toString();
  return `${target}${search ? `?${search}` : ''}${parsed.hash}`;
}

/** Preserve deep links when retired and personal tabs merge into a single route. */
export function migrateWorkflowTabs<T extends {key: string; location: string}>(tabs: readonly T[], targets: WorkflowTargets, ready: boolean): T[] {
  if (!ready) return [...tabs];
  const seen = new Set<string>();
  return tabs.flatMap(tab => {
    const location = migrateWorkflowLocation(tab.location || tab.key, targets, ready);
    const key = location.replace(/^#/, '').split(/[?#]/, 1)[0];
    if (seen.has(key)) return [];
    seen.add(key);
    return [{...tab, key, location}];
  });
}
