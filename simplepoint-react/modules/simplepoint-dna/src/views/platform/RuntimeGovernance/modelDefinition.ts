/** Persisted DNA schema contract. Parsing never treats definition text as executable content. */
export interface ModelField {
  fieldId: string;
  code: string;
  displayName: string;
  type: string;
  nullable: boolean;
  length?: number;
  precision?: number;
  scale?: number;
  defaultValue?: {kind: string; canonicalValue: string | null};
}
export interface ModelIndex {code: string; fieldCodes: string[]; unique: boolean}
export interface ModelSchema {
  contractVersion: string;
  fields: ModelField[];
  indexes: ModelIndex[];
  owner?: {parentModelId: string; parentFieldId: string};
}
const object = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value);
const text = (value: unknown): value is string => typeof value === 'string' && value.length > 0;

export function parseModelDefinition(source?: string): ModelSchema | undefined {
  if (!source?.trim()) return undefined;
  const schema: unknown = JSON.parse(source);
  if (!object(schema) || !text(schema.contractVersion) || !Array.isArray(schema.fields)
    || !Array.isArray(schema.indexes)) throw new Error('INVALID_MODEL_DEFINITION');
  const ids = new Set<string>(), codes = new Set<string>(), indexes = new Set<string>();
  for (const field of schema.fields) {
    if (!object(field) || !text(field.fieldId) || !text(field.code) || !text(field.displayName)
      || !text(field.type) || typeof field.nullable !== 'boolean'
      || ids.has(field.fieldId) || codes.has(field.code)) throw new Error('INVALID_MODEL_FIELD');
    for (const key of ['length', 'precision', 'scale']) {
      if (field[key] != null && (!Number.isSafeInteger(field[key]) || Number(field[key]) < 0)) throw new Error('INVALID_MODEL_FIELD');
    }
    if (field.defaultValue != null && (!object(field.defaultValue) || !text(field.defaultValue.kind)
      || (field.defaultValue.canonicalValue !== null && typeof field.defaultValue.canonicalValue !== 'string'))) {
      throw new Error('INVALID_MODEL_FIELD');
    }
    ids.add(field.fieldId); codes.add(field.code);
  }
  for (const index of schema.indexes) {
    if (!object(index) || !text(index.code) || indexes.has(index.code) || typeof index.unique !== 'boolean'
      || !Array.isArray(index.fieldCodes) || !index.fieldCodes.length
      || !index.fieldCodes.every(code => typeof code === 'string' && codes.has(code))) throw new Error('INVALID_MODEL_INDEX');
    indexes.add(index.code);
  }
  if (schema.owner != null && (!object(schema.owner) || !text(schema.owner.parentModelId)
    || !text(schema.owner.parentFieldId) || !ids.has(schema.owner.parentFieldId))) throw new Error('INVALID_MODEL_OWNER');
  return schema as unknown as ModelSchema;
}
