import type {RJSFSchema} from '@rjsf/utils';
import type {FormComponentDefinition, FormDetailDefinition} from '../../FormRuntime/contracts.ts';
import type {RecordState} from '../../FormRuntime/recordTableModel.ts';
import {fieldSchema} from '../../FormRuntime/fieldSchema.ts';
import {fieldNullability} from '../../FormRuntime/fieldNullability.ts';
import {fieldWriteAllowed} from '../../FormRuntime/writePolicy.ts';

export function detailRowSchema(detail: FormDetailDefinition, state?: RecordState, enforceRequired = true): RJSFSchema {
  const required = detail.fields.filter(field => fieldWriteAllowed(field, state) && (field.submitRequired && enforceRequired
    || !fieldWriteAllowed(field, 'DRAFT') && field.submitRequired && state === undefined)).map(field => field.fieldId);
  const properties = Object.fromEntries(detail.fields.map(field => {
    const schema = fieldNullability(fieldSchema(field, {} as FormComponentDefinition), !required.includes(field.fieldId));
    if (detail.readOnlyFieldIds?.includes(field.fieldId) || !fieldWriteAllowed(field, state)) Object.assign(schema, {readOnly: true, 'x-display-only': true});
    return [field.fieldId, schema];
  }));
  return {type: 'object', additionalProperties: false, properties, required};
}
export function detailSchema(detail: FormDetailDefinition, state?: RecordState, enforceRequired = true): RJSFSchema {
  return {type: 'array', title: detail.label, maxItems: 40, default: [], items: {type: 'object', additionalProperties: false,
    required: ['values'], properties: {id: {type: 'string'}, version: {type: 'integer', minimum: 0}, values: detailRowSchema(detail, state, enforceRequired)}}};
}
