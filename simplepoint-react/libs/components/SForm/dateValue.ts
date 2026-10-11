import type {RJSFSchema} from '@rjsf/utils';

/** The theme date picker emits null on clear; optional strings need absence, nullable fields need null. */
export function dateFormValue(schema: RJSFSchema, value: unknown) {
  if (value != null && value !== '') return value;
  return Array.isArray(schema.type) && schema.type.includes('null') ? null : undefined;
}
