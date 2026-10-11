export type ProviderRow = {id?: unknown; name?: unknown; enabled?: unknown; [field: string]: unknown};
export type ProviderAction = 'test' | 'discover' | 'sync';
export type ProviderTarget = {id: string; name?: string; enabled: true};
export type DiscoveredModel = {modelId: string; displayName?: string; modelType?: string; ownedBy?: string;
  pricing?: {currency?: string; inputTokenPrice?: string; outputTokenPrice?: string}};
export type TestResult = {providerId: string; success: boolean; discoveredModelCount: number; testedAt?: string; messageCode?: string};
export type SyncResult = {providerId: string; discovered: number; created: number; updated: number; unavailable: number};
const inputs = ['name', 'code', 'vendor', 'baseUrl', 'modelDiscoveryUrl', 'apiKey', 'allowPrivateNetwork', 'enabled', 'autoSyncEnabled', 'description'];

export const readableProviderText = (value: unknown): string | undefined =>
  typeof value === 'string' && value.trim() && !/^\*+$/.test(value.trim()) ? value : undefined;
export const providerIdentity = (row: ProviderRow | null | undefined) => readableProviderText(row?.id);
export function providerTarget(row: ProviderRow | null | undefined): ProviderTarget | undefined {
  const id = providerIdentity(row);
  return id && row?.enabled === true ? {id, name: readableProviderText(row.name), enabled: true} : undefined;
}
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;
const priceText = (value: unknown): string | undefined => {
  if (typeof value !== 'string' && typeof value !== 'number') return undefined;
  try {return exactDecimal(value).coefficient < 0 ? undefined : String(value);} catch {return undefined;}
};
export function providerTestResult(value: unknown, targetId: string): TestResult | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const result = value as TestResult;
  return result.providerId === targetId && typeof result.success === 'boolean' && count(result.discoveredModelCount)
    ? {providerId: targetId, success: result.success, discoveredModelCount: result.discoveredModelCount,
      testedAt: readableProviderText(result.testedAt), messageCode: readableProviderText(result.messageCode)} : undefined;
}
export function providerSyncResult(value: unknown, targetId: string): SyncResult | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const result = value as SyncResult;
  return result.providerId === targetId && [result.discovered, result.created, result.updated, result.unavailable].every(count)
    ? {providerId: targetId, discovered: result.discovered, created: result.created, updated: result.updated, unavailable: result.unavailable} : undefined;
}
/** A returned catalog is a single snapshot, not a new unbounded directory or mutation source. */
export function discoveredModels(value: unknown): DiscoveredModel[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const models = new Map<string, DiscoveredModel>();
  for (const row of value) {
    const id = readableProviderText(row?.modelId); if (!id) continue;
    const pricing = row.pricing && typeof row.pricing === 'object' ? row.pricing : undefined;
    models.set(id, {modelId: id, displayName: readableProviderText(row.displayName), modelType: readableProviderText(row.modelType),
      ownedBy: readableProviderText(row.ownedBy), pricing: pricing ? {currency: readableProviderText(pricing.currency),
        inputTokenPrice: priceText(pricing.inputTokenPrice), outputTokenPrice: priceText(pricing.outputTokenPrice)} : undefined});
  }
  return [...models.values()];
}
const writable = (field: any) => !!field && field.readOnly !== true && (field['x-field-access'] == null || field['x-field-access'] === 'EDITABLE');
export function providerInput(schema: any, values: Record<string, unknown>, editing: boolean) {
  return Object.fromEntries(inputs.flatMap(field => {
    if (!writable(schema?.properties?.[field]) || !Object.prototype.hasOwnProperty.call(values, field)) return [];
    if (editing && field === 'apiKey' && (typeof values[field] !== 'string' || !(values[field] as string).trim())) return [];
    return [[field, values[field]]];
  }));
}
export function providerEditorSchema(schema: any, record: ProviderRow | null, vendors: Record<string, string>, secretDescription: string) {
  const next = structuredClone(schema ?? {}), properties = next.properties ?? (next.properties = {});
  for (const field of Object.keys(properties)) if (!inputs.includes(field)) delete properties[field];
  if (properties.vendor) {properties.vendor.oneOf = Object.entries(vendors).map(([value, title]) => ({const: value, title})); delete properties.vendor.enum;}
  if (properties.apiKey) properties.apiKey.description = secretDescription;
  for (const [field, value] of Object.entries({enabled: true, autoSyncEnabled: true, allowPrivateNetwork: false})) {
    if (writable(properties[field])) properties[field].default ??= value;
  }
  next.required = ['name', 'code', 'vendor'].filter(field => writable(properties[field]));
  for (const field of ['name', 'code']) if (writable(properties[field])) properties[field] = {...properties[field], type: 'string', pattern: '\\S', minLength: 1};
  for (const [field, property] of Object.entries(properties) as [string, any][]) {
    if (property['x-field-access'] === 'MASKED' && record?.[field] === '***') properties[field] = {type: 'string',
      title: property.title, description: property.description, const: '***', readOnly: true, 'x-field-access': 'MASKED', 'x-ui': {widget: 'text'}};
  }
  return next;
}
import {exactDecimal} from '@simplepoint/shared/utils/decimal.ts';
