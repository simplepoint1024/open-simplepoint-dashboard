import type {FormFieldDefinition} from './contracts.ts';
import type {FormRecord, RecordState} from './recordTableModel.ts';
import {fieldWriteAllowed} from './writePolicy.ts';
import {referenceIdentity} from '../FormComponents/Reference/display.ts';

export type InverseUnlink = {targetRecordId: string; field: FormFieldDefinition; detailId?: string | null};
type DetailRow = {id: string; version: number; values: Record<string, unknown>};

export function canUnlinkRecord(relation: InverseUnlink | undefined, state?: RecordState) {
  return !!relation && !!state && fieldWriteAllowed(relation.field, state)
    && !(state === 'SUBMITTED' && relation.field.submitRequired);
}

function rows(record: FormRecord, detailId: string): DetailRow[] {
  const value = record.values[detailId];
  if (!Array.isArray(value) || value.some(row => !row || !referenceIdentity(row.id)
    || !Number.isSafeInteger(row.version) || row.version < 0 || !row.values || typeof row.values !== 'object')
    || new Set(value.map(row => row.id)).size !== value.length) throw Error('Invalid saved detail collection');
  return value;
}

export function unlinkCandidates(record: FormRecord, relation: InverseUnlink) {
  return relation.detailId ? rows(record, relation.detailId)
    .map((row, index) => ({...row, ordinal: index + 1}))
    .filter(row => referenceIdentity(row.values[relation.field.fieldId]) === relation.targetRecordId) : [];
}

/** Preserve the entire ordered aggregate. Only the selected reference is cleared. */
export function unlinkValues(record: FormRecord, relation: InverseUnlink, rowId?: string) {
  if (!canUnlinkRecord(relation, record.state)) throw Error('The reference is read-only');
  const field = relation.field.fieldId;
  if (!relation.detailId) {
    if (rowId || referenceIdentity(record.values[field]) !== relation.targetRecordId) throw Error('The reference changed');
    return {[field]: null};
  }
  if (!rowId || !unlinkCandidates(record, relation).some(row => row.id === rowId)) throw Error('Select a related detail row');
  return {[relation.detailId]: rows(record, relation.detailId).map(row => ({id: row.id, version: row.version,
    values: row.id === rowId ? {[field]: null} : {}}))};
}
