import type {RJSFSchema} from '@rjsf/utils';
import type {TranslateFn} from '@simplepoint/shared/i18n/translator';
import type {FieldScopeConfiguration} from '@/api/system/field-scope';

export type FieldScopeDraft = Omit<FieldScopeConfiguration, 'id'>;
const metadataKeys = ['name', 'description'] as const;
const accessLevels = ['EDITABLE', 'VISIBLE', 'MASKED', 'HIDDEN'];
function writable(source: RJSFSchema | undefined, key: string): boolean {
  if (!source) return true;
  const field = source.properties?.[key];
  const access = field && typeof field === 'object' ? (field as RJSFSchema)['x-field-access'] : undefined;
  return field === true || !!field && typeof field === 'object' && !field.readOnly
    && (!access || access === 'EDITABLE');
}
function readable(field: RJSFSchema | boolean | undefined): boolean {
  return field === true || !!field && typeof field === 'object' && !['MASKED', 'HIDDEN'].includes(field['x-field-access']);
}
export function canCreateFieldScope(source?: RJSFSchema): boolean {return !!source && writable(source, 'name');}
export function canEditFieldScopeMetadata(row: unknown, source?: RJSFSchema): boolean {
  if (!row || typeof row !== 'object' || !source) return false;
  const id = (row as FieldScopeConfiguration).id;
  return typeof id === 'string' && !!id && id !== '***' && metadataKeys.some(key => writable(source, key));
}
export function usableConfiguration(row: unknown, source?: RJSFSchema): row is FieldScopeConfiguration {
  if (!row || typeof row !== 'object') return false;
  const data = row as Partial<FieldScopeConfiguration>;
  const entries = source?.properties?.entries;
  if (source && (!readable(entries) || typeof entries !== 'object' || entries.type !== 'array')) return false;
  const items = typeof entries === 'object' ? entries.items : undefined;
  if (items && !Array.isArray(items) && typeof items === 'object'
    && ['resource', 'field', 'access'].some(key => !readable(items.properties?.[key]))) return false;
  return typeof data.id === 'string' && !!data.id && data.id !== '***'
    && (!writable(source, 'name') || typeof data.name === 'string' && !!data.name.trim() && data.name !== '***')
    && Array.isArray(data.entries) && data.entries.length <= 500 && data.entries.every(entry => entry && typeof entry.resource === 'string' && !!entry.resource && entry.resource !== '***'
      && typeof entry.field === 'string' && !!entry.field && entry.field !== '***' && accessLevels.includes(entry.access));
}
export function fieldScopeDraft(row?: FieldScopeConfiguration, source?: RJSFSchema): FieldScopeDraft {
  if (row && !usableConfiguration(row, source)) throw new Error('Field configuration is unavailable');
  const metadata: Pick<FieldScopeDraft, 'name' | 'description'> = {};
  for (const key of metadataKeys) if (!source || source.properties?.[key]) metadata[key] = row?.[key] ?? '';
  return {...metadata, entries: (row?.entries ?? []).map(({resource, field, access}) => ({resource, field, access}))};
}
// Derive the actual projected schema. Hidden fields stay absent rather than becoming fabricated required values.
export function fieldScopeMetadataSchema(source: RJSFSchema, t: TranslateFn): RJSFSchema {
  const properties: RJSFSchema['properties'] = {};
  for (const key of metadataKeys) {
    const original = source.properties?.[key]; if (original === undefined || original === false) continue;
    const editable = writable(source, key);
    properties[key] = {...(typeof original === 'object' ? original : {}),
      type: key === 'name' && editable ? 'string' : ['string', 'null'],
      title: original && typeof original === 'object' && original.title || (key === 'name' ? t('field-scopes.title.name', '名称') : t('field-scopes.title.description', '描述')),
      readOnly: !editable, ...(editable ? {maxLength: key === 'name' ? 100 : 200, ...(key === 'name' ? {pattern: '\\S'} : {})} : {}),
    };
  }
  return {...source, type: 'object', properties,
    required: [...new Set([...(source.required ?? []).filter(key => key in properties && writable(source, key)), ...(writable(source, 'name') ? ['name'] : [])])],
  };
}
export function fieldScopeSchema(t: TranslateFn, source?: RJSFSchema): RJSFSchema {
  const native: RJSFSchema = source ?? {properties: {name: {type: 'string'}, description: {type: ['string', 'null']}, entries: {type: 'array'}}};
  const metadata = fieldScopeMetadataSchema(native, t), original = native.properties?.entries;
  if (!original || typeof original !== 'object') return metadata;
  const items = original.items, item = items && !Array.isArray(items) && typeof items === 'object' ? items : undefined;
  const properties: RJSFSchema['properties'] = {};
  for (const key of ['resource', 'field', 'access']) {
    const field = item?.properties?.[key];
    properties[key] = {...(typeof field === 'object' ? field : {}), type: 'string',
      readOnly: item ? !writable(item, key) : false,
      ...(key === 'access' ? {enum: accessLevels} : {minLength: 1, pattern: '\\S'}),
    };
  }
  return {...metadata, additionalProperties: false, required: [...(metadata.required ?? []), 'entries'], properties: {...metadata.properties,
    entries: {...original, type: 'array', maxItems: 500, uniqueItems: true, readOnly: !writable(native, 'entries'),
      items: {...item, type: 'object', additionalProperties: false, required: ['resource', 'field', 'access'], properties}},
  }};
}
// SimpleTable passes the captured entity, so explicitly whitelist metadata, not just rendered fields.
export function fieldScopeMetadataPayload(values: Partial<FieldScopeDraft>, id?: string, source?: RJSFSchema) {
  const result: {id?: string; name?: string; description?: string | null} = {...(id ? {id} : {})};
  if (writable(source, 'name')) result.name = values.name?.trim();
  if (writable(source, 'description')) result.description = values.description || null;
  return result;
}
export function fieldScopePayload(draft: FieldScopeDraft, id?: string, source?: RJSFSchema): FieldScopeConfiguration {
  return {...fieldScopeMetadataPayload(draft, id, source), entries: draft.entries.map(({resource, field, access}) => ({resource, field, access}))};
}
export function duplicateFieldEntries(draft: FieldScopeDraft): boolean {
  const keys = draft.entries.map(({resource, field}) => JSON.stringify([resource, field]));
  return new Set(keys).size !== keys.length;
}
