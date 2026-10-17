import type {DesignDocument} from './contracts.ts';
import {choiceValue} from '../FormRuntime/choiceValues.ts';

/** Canonical comparison for lost-save reconciliation; scene order and rule precedence remain significant. */
export function sameDesignDocument(left: DesignDocument, right: DesignDocument) {
  const normalized = (document: DesignDocument) => ({...document,
    details: document.details?.length ? [...document.details].sort((a, b) => a.detailId.localeCompare(b.detailId))
      .map(detail => ({...detail, fields: [...detail.fields].sort((a, b) => a.fieldId.localeCompare(b.fieldId)),
        indexes: [...detail.indexes].sort((a, b) => a.indexId.localeCompare(b.indexId))})) : undefined,
    fields: [...document.fields].sort((a, b) => a.fieldId.localeCompare(b.fieldId)),
    indexes: [...document.indexes].sort((a, b) => a.indexId.localeCompare(b.indexId)),
  });
  return sameDesignValue(normalized(left), normalized(right));
}
/** Used by modal edit sessions to reject stale replacements while accepting server normalization. */
export function sameDesignValue(left: unknown, right: unknown) {
  return JSON.stringify(canonical(left)) === JSON.stringify(canonical(right));
}
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === 'object') {
    const record = {...value} as Record<string, unknown>;
    if (record.access && typeof record.access === 'object') record.access = {readOnly: false, create: 'WRITE', edit: 'WRITE', ...record.access};
    // The server canonicalizes typed numeric literals, including default and rule values.
    if ((record.kind === 'DECIMAL' || record.kind === 'INTEGER') && typeof record.value === 'string') {
      try {return {kind: record.kind, value: choiceValue('DECIMAL', record.value)};} catch {/* Invalid drafts remain distinct. */}
    }
    return Object.fromEntries(Object.entries(record).filter(([, child]) => child != null)
      .sort(([left], [right]) => left.localeCompare(right)).map(([key, child]) => [key, canonical(child)]));
  }
  return value;
}
