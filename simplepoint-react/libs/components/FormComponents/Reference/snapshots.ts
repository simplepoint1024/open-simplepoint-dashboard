import type {ReferenceProjection} from '../../ReferenceLabel/model.ts';
import {referenceIdentity, referenceProjection} from './display.ts';

export type FormReferenceSnapshot = {
  relationId: string;
  detailId?: string | null;
  sourceRecordId: string;
  fieldId: string;
  targetRecordId: string;
  targetVersion: number;
  labelFieldId: string;
  label: string | null;
};
export type ReferenceSnapshotLabels = ReadonlyMap<string, ReferenceProjection>;
export const snapshotLabelKey = (relationId: string, id: string) => JSON.stringify([relationId, id]);

/** One frozen aggregate has one locked version per relation/target; conflicting evidence stays unavailable. */
export function snapshotLabels(rows: readonly FormReferenceSnapshot[]): ReferenceSnapshotLabels {
  const result = new Map<string, ReferenceProjection>();
  const originals = new Map<string, {label: string | null; version: number}>();
  const conflicts = new Set<string>();
  for (const row of rows) {
    const id = referenceIdentity(row.targetRecordId);
    if (!id || typeof row.relationId !== 'string' || !row.relationId) continue;
    const key = snapshotLabelKey(row.relationId, id), prior = originals.get(key);
    if (prior && (prior.label !== row.label || prior.version !== row.targetVersion)) conflicts.add(key);
    originals.set(key, {label: row.label, version: row.targetVersion});
    result.set(key, conflicts.has(key) ? {id, label: null} : referenceProjection(id,
      [{value: id, label: typeof row.label === 'string' ? row.label : ''}]));
  }
  return result;
}
