import type {DesignDocument} from './contracts.ts';
import {designFields} from './fieldAnalysis.ts';
import {sameDesignValue} from './draftState.ts';

export type PublicationChange = {key: string; label: string; kind: 'added' | 'removed' | 'formula' | 'attributes'};
/** Compare field identities across main and detail models; labels/storage names are never join keys. */
export function publicationChanges(before: DesignDocument | undefined, after: DesignDocument): PublicationChange[] {
  const previous = new Map((before ? designFields(before) : []).map(field => [field.key, field]));
  const result: PublicationChange[] = [];
  for (const current of designFields(after)) {
    const old = previous.get(current.key); previous.delete(current.key);
    if (!old) result.push({key: current.key, label: current.label, kind: 'added'});
    else if (!sameDesignValue(old.field.calculation, current.field.calculation)) result.push({key: current.key, label: current.label, kind: 'formula'});
    else if (!sameDesignValue(old.field, current.field)) result.push({key: current.key, label: current.label, kind: 'attributes'});
  }
  previous.forEach(field => result.push({key: field.key, label: field.label, kind: 'removed'}));
  return result;
}
