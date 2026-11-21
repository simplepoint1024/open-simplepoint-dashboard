import type {EntityOption} from '../../EntitySelect/query.ts';
import {projectedReference, type ReferenceProjection} from '../../ReferenceLabel/model.ts';

/** Only a visible canonical record identity can initiate a directory lookup. */
export function referenceIdentity(value: unknown): string | undefined {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value)
    ? value.toLowerCase() : undefined;
}

/** No result and an unreadable/deleted result have the same presentation. Never fall back to another ID. */
export function referenceProjection(id: string, rows: readonly EntityOption[]): ReferenceProjection {
  const row = rows.find(candidate => candidate.value.toLowerCase() === id);
  const points = row?.label ? Array.from(row.label) : [];
  const label = points.length > 256 ? points.slice(0, 255).join('') + '…' : row?.label;
  return projectedReference(id, {id, label}) ?? {id, label: null};
}
