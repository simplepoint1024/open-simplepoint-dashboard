import type {RoleResourceChange, RoleResourceRow} from '@/api/system/role-resource-configuration';

export type ResourceDraft = Record<string, {
  baseline: RoleResourceRow;
  granted: boolean;
  dataScopeId?: string | null;
  fieldScopeId?: string | null;
}>;
export const MAX_RESOURCE_CHANGES = 5000;
const has = (value: object, key: string) => Object.prototype.hasOwnProperty.call(value, key);

/** Keep the original revision even when a page refreshes while editing. */
function applyResourceGrant(next: ResourceDraft, row: RoleResourceRow, granted: boolean) {
  const baseline = next[row.id]?.baseline ?? row;
  if (baseline.grantable !== true || typeof baseline.granted !== 'boolean') return;
  const previous = next[row.id];
  if (!granted) {
    if (baseline.granted) next[row.id] = {baseline, granted};
    else delete next[row.id];
  } else if (baseline.granted && !has(previous ?? {}, 'dataScopeId')
      && !has(previous ?? {}, 'fieldScopeId')) {
    delete next[row.id];
  } else {
    next[row.id] = {...previous, baseline, granted};
  }
}

export function setResourceGrant(draft: ResourceDraft, row: RoleResourceRow, granted: boolean): ResourceDraft {
  if ((draft[row.id]?.baseline ?? row).grantable !== true) return draft;
  const next = {...draft}; applyResourceGrant(next, row, granted); return next;
}

/** Stage a complete real subtree with one copy, not one growing draft copy per resource. */
export function setResourceGrants(draft: ResourceDraft, rows: readonly RoleResourceRow[], granted: boolean): ResourceDraft {
  const next = {...draft};
  for (const row of rows) applyResourceGrant(next, row, granted);
  return next;
}

export function setResourcePolicy(draft: ResourceDraft, row: RoleResourceRow,
  kind: 'data' | 'field', policyId: string | null): ResourceDraft {
  const baseline = draft[row.id]?.baseline ?? row;
  const granted = draft[row.id]?.granted ?? row.granted;
  const supported = kind === 'data' ? baseline.dataPermission.supported : baseline.fieldPermission.supported;
  if (baseline.grantable !== true || granted !== true || supported !== true) return draft;
  const property = kind === 'data' ? 'dataScopeId' : 'fieldScopeId';
  const original = kind === 'data' ? baseline.dataPermission.policyId : baseline.fieldPermission.policyId;
  const next = {...draft};
  const entry: ResourceDraft[string] = {...next[row.id], baseline, granted};
  if (policyId === original) delete entry[property];
  else entry[property] = policyId;
  if (entry.granted === baseline.granted && !has(entry, 'dataScopeId')
      && !has(entry, 'fieldScopeId')) delete next[row.id];
  else next[row.id] = entry;
  return next;
}

export function resourceChanges(draft: ResourceDraft): RoleResourceChange[] {
  return Object.values(draft).map(entry => ({
    resourceId: entry.baseline.id, resourceCode: entry.baseline.code,
    revision: entry.baseline.revision, granted: entry.granted,
    ...(has(entry, 'dataScopeId')
      ? {dataPermission: {policyId: entry.dataScopeId ?? null}} : {}),
    ...(has(entry, 'fieldScopeId')
      ? {fieldPermission: {policyId: entry.fieldScopeId ?? null}} : {}),
  }));
}

export function displayedGrant(draft: ResourceDraft, row: RoleResourceRow): boolean {
  return (draft[row.id]?.granted ?? row.granted) === true;
}

export function displayedPolicy(draft: ResourceDraft, row: RoleResourceRow,
  kind: 'data' | 'field'): string | null {
  const entry = draft[row.id];
  if (kind === 'data') return entry && has(entry, 'dataScopeId')
    ? entry.dataScopeId ?? null : row.dataPermission.policyId;
  return entry && has(entry, 'fieldScopeId')
    ? entry.fieldScopeId ?? null : row.fieldPermission.policyId;
}
