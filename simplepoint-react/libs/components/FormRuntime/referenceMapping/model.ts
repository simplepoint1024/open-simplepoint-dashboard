import type {ApplicationFormDocument, FormFieldDefinition, FormValue} from '../contracts.ts';
import type {FormValues} from '../sceneState.ts';
import {sameFormValues} from '../sceneState.ts';
import {referenceDisplayValue} from '../liveReferenceValues.ts';
import {fieldWriteAllowed} from '../writePolicy.ts';
import type {RecordState} from '../recordTableModel.ts';

export type MappingObservation = {revision: string; targetRecordId: string | null; targetVersion: number | null};
export type MappingView = {observation: MappingObservation; snapshots: Record<string, FormValue>; defaults: Record<string, FormValue>};
export type MappingLocation = {fieldId: string; detailId?: string; rowKey?: string};
export type MappingConfirmation = {relationId: string; detailId?: string; rowIndex?: number; sourceRowId?: string;
  observation: MappingObservation; applyDefaultMappingIds: string[]};
export type MappingIntent = {contractVersion: '1'; confirmations: MappingConfirmation[]};
export type MappingEntry = {location: MappingLocation; relationId: string; observation: MappingObservation;
  snapshots: FormValues; defaults: Array<{mappingId: string; fieldId: string; value: unknown}>};
export type MappingDraft = readonly MappingEntry[];
type DetailRow = {id?: string; clientId?: string; version?: number; values: FormValues};
export type MappingSourceRow = {sourceRowId?: string; newDetailRow?: true};
export type MappingLoader = (targetRecordId: string | null, applyDefaultMappingIds: string[], signal: AbortSignal, sourceRow?: MappingSourceRow) => Promise<MappingView>;
const canonicalId = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const coordinate = (location: MappingLocation) => JSON.stringify([location.detailId, location.rowKey, location.fieldId]);
const target = (value: unknown) => value == null || value === '' ? null : value;

/** Coordinates use stable identities in the edit buffer; transport positions are resolved at submit. */
export function mappingRow(values: FormValues, location: MappingLocation) {
  if (!location.detailId) {
    if (location.rowKey) throw new Error('Unexpected parent row identity');
    return {values};
  }
  const rows = values[location.detailId];
  if (!location.rowKey || !canonicalId.test(location.rowKey) || !Array.isArray(rows) || rows.length > 40) throw new Error('Invalid mapping row');
  const found = rows.map((row, index) => ({row, index})).filter(({row}) =>
    (row?.id ?? row?.clientId) === location.rowKey);
  if (found.length !== 1 || !found[0].row.values || typeof found[0].row.values !== 'object' || Array.isArray(found[0].row.values)) throw new Error('Mapping row is missing or ambiguous');
  return {values: found[0].row.values as FormValues, index: found[0].index, id: found[0].row.id as string | undefined};
}

export function mappingFields(document: ApplicationFormDocument, location: MappingLocation) {
  const detail = location.detailId ? document.details?.find(item => item.detailId === location.detailId) : undefined;
  if (location.detailId && !detail) throw new Error('Unknown mapping detail');
  const fields = detail?.fields ?? document.fields;
  const source = fields.find(field => field.fieldId === location.fieldId);
  if (!source?.reference) throw new Error('Unknown mapping source');
  return {fields, source, detail};
}

export function mappingDefaultIds(document: ApplicationFormDocument, values: FormValues, location: MappingLocation, state?: RecordState) {
  const {fields, source, detail} = mappingFields(document, location), row = mappingRow(values, location);
  const currentState = location.detailId && !row.id ? undefined : state;
  return (source.reference!.mappings ?? []).filter(mapping => mapping.mode === 'EDITABLE_DEFAULT'
    && fields.some(field => field.fieldId === mapping.destinationFieldId && fieldWriteAllowed(field, currentState))
    && !detail?.readOnlyFieldIds?.includes(mapping.destinationFieldId)).map(mapping => mapping.mappingId).sort();
}

/** Validate the complete response before any field changes; malformed or partial observations cannot be confirmed. */
export function mappingEntry(document: ApplicationFormDocument, location: MappingLocation, targetId: string | null,
  defaultIds: readonly string[], response: MappingView): MappingEntry {
  const {source, fields} = mappingFields(document, location), observation = response?.observation;
  if (!observation || !/^[a-f0-9]{64}$/.test(observation.revision) || observation.targetRecordId !== targetId
    || (targetId === null ? observation.targetVersion !== null : !canonicalId.test(targetId)
      || !Number.isSafeInteger(observation.targetVersion) || observation.targetVersion! < 0)) throw new Error('Invalid mapping observation');
  const mappings = source.reference!.mappings ?? [];
  if (defaultIds.length > 32 || new Set(defaultIds).size !== defaultIds.length
    || defaultIds.some(id => !mappings.some(mapping => mapping.mappingId === id && mapping.mode === 'EDITABLE_DEFAULT')))
    throw new Error('Invalid default selection');
  const convert = (mode: 'SERVER_SNAPSHOT' | 'EDITABLE_DEFAULT', values: Record<string, FormValue>) => {
    const expected = mappings.filter(mapping => mapping.mode === mode && (mode !== 'EDITABLE_DEFAULT' || defaultIds.includes(mapping.mappingId)));
    if (!values || typeof values !== 'object' || Array.isArray(values) || Object.keys(values).length > expected.length
      || expected.some(mapping => !Object.prototype.hasOwnProperty.call(values, mapping.destinationFieldId)
        && (mode !== 'EDITABLE_DEFAULT' || mapping.nullPolicy !== 'PRESERVE'))
      || Object.keys(values).some(id => !expected.some(mapping => mapping.destinationFieldId === id))) throw new Error('Invalid mapping projection');
    return expected.filter(mapping => Object.prototype.hasOwnProperty.call(values, mapping.destinationFieldId)).map(mapping => {
      const field = fields.find(field => field.fieldId === mapping.destinationFieldId);
      const value = values[mapping.destinationFieldId];
      if (!field || !value || targetId === null && value.kind !== 'NULL') throw new Error('Invalid mapped value');
      return {mappingId: mapping.mappingId, fieldId: field.fieldId, value: referenceDisplayValue(field, value)};
    });
  };
  const snapshots = convert('SERVER_SNAPSHOT', response.snapshots), defaults = convert('EDITABLE_DEFAULT', response.defaults);
  if (snapshots.length + defaults.length > 32) throw new Error('Mapping budget exceeded');
  return {location: {...location}, relationId: source.reference!.relationId, observation: {...observation},
    snapshots: Object.fromEntries(snapshots.map(item => [item.fieldId, item.value])), defaults};
}

/** A subsequent user edit turns a default into an ordinary value. Never silently restore a suggestion. */
export function reconcileMappings(draft: MappingDraft, values: FormValues): MappingDraft {
  return draft.flatMap(entry => {
    try {
      const row = mappingRow(values, entry.location);
      if (target(row.values[entry.location.fieldId]) !== entry.observation.targetRecordId) return [];
      return [{...entry, defaults: entry.defaults.filter(item => sameFormValues(row.values[item.fieldId], item.value))}];
    } catch {return [];}
  });
}

export function applyMapping(values: FormValues, draft: MappingDraft, entry: MappingEntry, selected: readonly string[]) {
  const row = mappingRow(values, entry.location);
  if (new Set(selected).size !== selected.length || selected.some(id => !entry.defaults.some(item => item.mappingId === id))) throw new Error('Unknown selected default');
  const defaults = entry.defaults.filter(item => selected.includes(item.mappingId));
  const nextRow = {...row.values, [entry.location.fieldId]: entry.observation.targetRecordId,
    ...Object.fromEntries(defaults.map(item => [item.fieldId, item.value]))};
  const next = entry.location.detailId ? {...values, [entry.location.detailId]: (values[entry.location.detailId] as DetailRow[])
    .map((item, index) => index === row.index ? {...item, values: nextRow} : item)} : nextRow;
  const remaining = draft.filter(item => coordinate(item.location) !== coordinate(entry.location));
  return {values: next, draft: [...reconcileMappings(remaining, next), {...entry, defaults}] as MappingDraft};
}

/** Snapshot display is separate from business values and never travels through editable field payloads. */
export function mappingDisplay(values: FormValues, draft: MappingDraft): FormValues {
  let result = values;
  for (const entry of reconcileMappings(draft, values)) {
    const row = mappingRow(result, entry.location);
    const nextRow = {...row.values, ...entry.snapshots};
    result = entry.location.detailId ? {...result, [entry.location.detailId]: (result[entry.location.detailId] as DetailRow[])
      .map((item, index) => index === row.index ? {...item, values: nextRow} : item)} : nextRow;
  }
  return result;
}

export function mappingIntent(draft: MappingDraft, values: FormValues): MappingIntent | undefined {
  const current = reconcileMappings(draft, values);
  if (!current.length) return undefined;
  if (current.length > 10050 || new Set(current.map(entry => coordinate(entry.location))).size !== current.length)
    throw new Error('Invalid mapping confirmation set');
  const confirmations = current.map(entry => {
    const row = mappingRow(values, entry.location);
    return {relationId: entry.relationId, ...(entry.location.detailId ? {detailId: entry.location.detailId,
      rowIndex: row.index, ...(row.id ? {sourceRowId: row.id} : {})} : {}), observation: {...entry.observation},
      applyDefaultMappingIds: entry.defaults.map(item => item.mappingId).sort()};
  }).sort((a, b) => (a.detailId ?? '').localeCompare(b.detailId ?? '') || (a.rowIndex ?? -1) - (b.rowIndex ?? -1)
    || a.relationId.localeCompare(b.relationId));
  return {contractVersion: '1', confirmations};
}

export type MappingReviewRow = {key: string; field: FormFieldDefinition; current: unknown; suggested: unknown; snapshot: boolean};
export function mappingReview(document: ApplicationFormDocument, values: FormValues, entry: MappingEntry): MappingReviewRow[] {
  const {fields} = mappingFields(document, entry.location), row = mappingRow(values, entry.location);
  return [...Object.entries(entry.snapshots).map(([fieldId, value]) => ({fieldId, value, mappingId: JSON.stringify(['snapshot', fieldId]), snapshot: true})),
    ...entry.defaults.map(item => ({...item, snapshot: false}))].map(item => ({key: item.mappingId,
    field: fields.find(field => field.fieldId === item.fieldId)!, current: row.values[item.fieldId], suggested: item.value, snapshot: item.snapshot}));
}
