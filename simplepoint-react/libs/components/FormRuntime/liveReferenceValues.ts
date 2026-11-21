import type {ApplicationFormDocument, FormFieldDefinition, FormValue} from './contracts.ts';
import type {FormValues} from './sceneState.ts';

export type LiveReferenceView = {targetRecordId: string | null; targetVersion: number | null; values: Record<string, FormValue>};
export type LiveReferenceQuery = {relationId: string; ids: string[]};
type Placement = {relationId: string; targetId?: string; detailId?: string; rowIndex?: number; fields: FormFieldDefinition[]};
export type LiveReferencePlan = {queries: LiveReferenceQuery[]; placements: Placement[]};
export const liveReferenceKey = (relation: string, target: string) => JSON.stringify([relation, target]);
const canonicalId = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** Scene declarations only select display work. Runtime metadata and the server own authorization. */
export function liveSceneFields(document: ApplicationFormDocument, sceneId: string) {
  const fields = new Set<string>();
  const root = document.scenes.find(scene => scene.sceneId === sceneId)?.root;
  const visit = (node: NonNullable<typeof root>) => {
    if (node.fieldId) fields.add(node.fieldId);
    const detail = node.properties.detailId?.value;
    if (typeof detail === 'string') fields.add(detail);
    node.children.forEach(visit);
  };
  if (root) visit(root);
  return fields;
}

/** Group all selected rows of each relation; output placements retain the current draft order. */
export function planLiveReferences(document: ApplicationFormDocument, values: FormValues,
  visible?: ReadonlySet<string>): LiveReferencePlan {
  const placements: Placement[] = [], queries = new Map<string, Set<string>>();
  const append = (fields: FormFieldDefinition[], row: FormValues, detailId?: string, rowIndex?: number) => {
    for (const source of fields) {
      const reference = source.reference;
      if (!reference) continue;
      const destinations = (reference.mappings ?? []).filter(mapping => mapping.mode === 'LIVE')
        .map(mapping => fields.find(field => field.fieldId === mapping.destinationFieldId && field.referenceValueMode === 'LIVE'))
        .filter((field): field is FormFieldDefinition => Boolean(field) && (!visible || Boolean(detailId) || visible.has(field!.fieldId)));
      if (!destinations.length) continue;
      const raw = row[source.fieldId];
      const targetId = typeof raw === 'string' && canonicalId.test(raw) ? raw : undefined;
      placements.push({relationId: reference.relationId, targetId, detailId, rowIndex, fields: destinations});
      if (targetId) {
        const ids = queries.get(reference.relationId) ?? new Set<string>();
        ids.add(targetId); queries.set(reference.relationId, ids);
      }
    }
  };
  append(document.fields, values);
  for (const detail of document.details ?? []) {
    if (visible && !visible.has(detail.detailId)) continue;
    const rows = values[detail.detailId];
    if (!Array.isArray(rows)) continue;
    if (rows.length > 40) throw new Error('Live reference row budget exceeded');
    rows.forEach((row, index) => append(detail.fields, row?.values ?? {}, detail.detailId, index));
  }
  if (queries.size > 50 || placements.length > 10050) throw new Error('Live reference projection budget exceeded');
  return {placements, queries: [...queries].sort(([a], [b]) => a.localeCompare(b))
    .map(([relationId, ids]) => ({relationId, ids: [...ids].sort()}))};
}

/** Canonical numeric values remain exact; these are display outputs, never user-written inputs. */
export function referenceDisplayValue(field: FormFieldDefinition, value: FormValue | undefined): unknown {
  if (!value || value.kind === 'NULL') return null;
  const expected = ({STRING: 'STRING', TEXT: 'STRING', BOOLEAN: 'BOOLEAN', INT32: 'INTEGER', INT64: 'INTEGER',
    DECIMAL: 'DECIMAL', DATE: 'DATE', TIME: 'TIME', DATETIME: 'DATETIME', UUID: 'UUID', JSON: 'JSON'} as const)[field.type];
  if (value.kind !== expected || typeof value.value !== 'string' || value.value.length > 16384) throw new Error('Invalid reference value');
  if (field.type === 'BOOLEAN') {
    if (!['true', 'false'].includes(value.value)) throw new Error('Invalid reference boolean');
    return value.value === 'true';
  }
  if (field.type === 'INT32') {
    if (!/^-?\d+$/.test(value.value)) throw new Error('Invalid reference integer');
    const number = Number(value.value);
    if (!Number.isSafeInteger(number) || number < -2147483648 || number > 2147483647) throw new Error('Invalid reference integer');
    return number;
  }
  return value.value;
}

/** Always clear missing/revoked/pending outputs; preserve user values, row identity and ordering. */
export function mergeLiveReferences(values: FormValues, plan: LiveReferencePlan,
  results: ReadonlyMap<string, LiveReferenceView> = new Map()): FormValues {
  if (!plan.placements.length) return values;
  const result = {...values};
  const copied = new Set<string>();
  for (const placement of plan.placements) {
    let row = result;
    if (placement.detailId !== undefined && placement.rowIndex !== undefined) {
      const rows = values[placement.detailId];
      if (!Array.isArray(rows)) continue;
      if (!copied.has(placement.detailId)) {
        result[placement.detailId] = rows.map(item => ({...item, values: {...item.values}}));
        copied.add(placement.detailId);
      }
      row = (result[placement.detailId] as Array<{values: FormValues}>)[placement.rowIndex].values;
    }
    const found = placement.targetId ? results.get(liveReferenceKey(placement.relationId, placement.targetId)) : undefined;
    for (const field of placement.fields) row[field.fieldId] = referenceDisplayValue(field, found?.values[field.fieldId]);
  }
  return result;
}

export function validateLiveBatch(ids: readonly string[], rows: LiveReferenceView[]) {
  if (!Array.isArray(rows) || rows.length !== ids.length || new Set(rows.map(row => row.targetRecordId)).size !== rows.length
    || rows.some(row => !row || !row.targetRecordId || !ids.includes(row.targetRecordId)
      || row.targetVersion !== null && (!Number.isSafeInteger(row.targetVersion) || row.targetVersion < 0)
      || !row.values || typeof row.values !== 'object' || Array.isArray(row.values) || Object.keys(row.values).length > 32
      || row.targetVersion === null && Object.keys(row.values).length > 0)) throw new Error('Invalid live reference batch');
  return rows;
}

/** A malformed read plan must not fall back to stale reference values in the owning buffer. */
export function clearLiveReferences(document: ApplicationFormDocument, values: FormValues): FormValues {
  const clear = (fields: FormFieldDefinition[], row: FormValues) => ({...row,
    ...Object.fromEntries(fields.filter(field => field.referenceValueMode === 'LIVE').map(field => [field.fieldId, null]))});
  const result = clear(document.fields, values);
  for (const detail of document.details ?? []) {
    if (!detail.fields.some(field => field.referenceValueMode === 'LIVE')) continue;
    const rows = values[detail.detailId];
    if (!Array.isArray(rows)) continue;
    // Invalid oversized collections are withheld from display, never truncated in the edit buffer.
    result[detail.detailId] = rows.length > 40 ? [] : rows.map(row => ({...row, values: clear(detail.fields, row?.values ?? {})}));
  }
  return result;
}
