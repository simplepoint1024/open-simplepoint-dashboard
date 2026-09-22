import type {RoleResourceChange, RoleResourceRow} from '@/api/system/role-resource-configuration';

export type ResourceDraft = Record<string, {baseline: RoleResourceRow; granted: boolean}>;
export const MAX_RESOURCE_CHANGES = 5000;

/** Keep the original revision even when a page refreshes while editing. */
export function setResourceGrant(draft: ResourceDraft, row: RoleResourceRow, granted: boolean): ResourceDraft {
  const baseline = draft[row.id]?.baseline ?? row;
  if (!baseline.grantable) return draft;
  const next = {...draft};
  if (baseline.granted === granted) delete next[row.id];
  else next[row.id] = {baseline, granted};
  return next;
}

export function resourceChanges(draft: ResourceDraft): RoleResourceChange[] {
  return Object.values(draft).map(({baseline, granted}) => ({
    resourceId: baseline.id, resourceCode: baseline.code, revision: baseline.revision, granted,
  }));
}

export function displayedGrant(draft: ResourceDraft, row: RoleResourceRow): boolean {
  return draft[row.id]?.granted ?? row.granted;
}
