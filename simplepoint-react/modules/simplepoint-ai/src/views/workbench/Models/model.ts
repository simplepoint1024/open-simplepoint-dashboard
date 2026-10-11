import type {DebugModel} from './ModelDebugDialog';

export type ModelRow = {id?: unknown; providerId?: unknown; providerName?: unknown; modelId?: unknown; displayName?: unknown;
  modelType?: unknown; enabled?: unknown; available?: unknown; [field: string]: unknown};
const inputs = ['providerId', 'modelId', 'displayName', 'modelType', 'enabled', 'billingEnabled', 'billingCurrency',
  'inputTokenPrice', 'cachedInputTokenPrice', 'outputTokenPrice', 'requestPrice', 'description'];
const readable = (value: unknown): string | undefined => typeof value === 'string' && value.trim() && !/^\*+$/.test(value.trim()) ? value : undefined;
const writable = (field: any) => !!field && field.readOnly !== true && (field['x-field-access'] == null || field['x-field-access'] === 'EDITABLE');
export const modelIdentity = (row: ModelRow | null | undefined) => readable(row?.id);
export function debugTarget(row: ModelRow | null | undefined): DebugModel | undefined {
  const id = modelIdentity(row);
  return id && row?.enabled === true && row.available === true && ['LLM', 'MULTIMODAL'].includes(String(row.modelType))
    ? {id, modelId: readable(row.modelId), displayName: readable(row.displayName)} : undefined;
}
export function providerOption(row: Record<string, unknown>, disabledLabel: string) {
  const id = readable(row.id), label = readable(row.name) || readable(row.code);
  return id && label ? {value: id, label: label + (row.enabled === false ? ` (${disabledLabel})` : '')} : undefined;
}
/** Actual selected-value endpoint is capped at 100 IDs; requests are bounded and sent sequentially. */
export function providerLookupBatches(ids: readonly string[]) {
  const batches: string[][] = []; let current: string[] = [];
  for (const id of new Set(ids.filter(value => !!readable(value)))) {
    const next = [...current, id], wire = new URLSearchParams({ids: next.join(',')}).toString();
    if (current.length && (next.length > 100 || wire.length > 2500)) {batches.push(current); current = [id];}
    else current = next;
  }
  if (current.length) batches.push(current);
  return batches;
}
export function modelInput(schema: any, values: Record<string, unknown>) {
  return Object.fromEntries(inputs.flatMap(field => writable(schema?.properties?.[field]) && Object.prototype.hasOwnProperty.call(values, field)
    ? [[field, values[field]]] : []));
}
export function modelEditorSchema(schema: any, record: ModelRow | null, types: {const: string; title: string}[]) {
  const next = structuredClone(schema ?? {}), properties = next.properties ?? (next.properties = {});
  for (const field of Object.keys(properties)) if (!inputs.includes(field)) delete properties[field];
  if (properties.providerId) {delete properties.providerId.enum; delete properties.providerId.oneOf;}
  if (properties.modelType) {properties.modelType.oneOf = types; delete properties.modelType.enum;}
  for (const [field, value] of Object.entries({enabled: true, billingEnabled: false, billingCurrency: 'USD'}))
    if (writable(properties[field])) properties[field].default ??= value;
  next.required = ['providerId', 'modelId', 'modelType'].filter(field => writable(properties[field]));
  for (const field of ['providerId', 'modelId']) if (writable(properties[field])) properties[field] = {...properties[field], type: 'string', minLength: 1, pattern: '\\S'};
  for (const [field, property] of Object.entries(properties) as [string, any][]) {
    if (property['x-field-access'] === 'MASKED' && typeof record?.[field] === 'string' && /^\*+$/.test((record[field] as string).trim()))
      properties[field] = {type: 'string', title: property.title, description: property.description, const: record[field],
        readOnly: true, 'x-field-access': 'MASKED', 'x-ui': {widget: 'text'}};
  }
  return next;
}
