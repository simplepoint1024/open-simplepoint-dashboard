type NumericSchema = {type?: string | string[]; minimum?: number; maximum?: number; exclusiveMinimum?: number; exclusiveMaximum?: number};
export function isNumericSchema(schema: NumericSchema) {
  const types = Array.isArray(schema.type) ? schema.type : [schema.type];
  return types.includes('number') || types.includes('integer');
}
/** InputNumber must report the typed number; the unchanged root schema validates its bounds. */
export function numericInputSchema<T extends NumericSchema>(schema: T): T {
  if (!isNumericSchema(schema)) return schema;
  const copy = {...schema};
  delete copy.minimum; delete copy.maximum; delete copy.exclusiveMinimum; delete copy.exclusiveMaximum;
  return copy;
}
export function numericFormValue(schema: NumericSchema, value: unknown) {
  return value === null && !(Array.isArray(schema.type) && schema.type.includes('null')) ? undefined : value;
}
