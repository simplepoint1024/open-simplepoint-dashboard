import type {RJSFSchema} from '@rjsf/utils';
import type {DesignDocument, DesignField} from './contracts.ts';
import {fieldSchema} from '../FormRuntime/fieldSchema.ts';
import {fieldDependencies, fieldKey} from './fieldAnalysis.ts';

export type CalculationSamples = {values?: Record<string, unknown>; details?: Record<string, Record<string, unknown>[]>};
export const sampleInputFields = (fields: DesignField[]) => fields.filter(field => ['STORED', 'REFERENCE', 'ATTACHMENT'].includes(field.storageMode));
/** Every formula is evaluated, but unrelated optional business fields need no sample input. */
export function calculationSampleFields(document: DesignDocument) {
  const graph = fieldDependencies(document), required = new Set([...graph.edges.values()].flat());
  const select = (scope: string, fields: DesignField[]) => sampleInputFields(fields)
    .filter(field => field.storageRequired || required.has(fieldKey({scope, fieldId: field.fieldId})));
  return {fields: select('parent', document.fields), details: (document.details ?? []).map(detail => ({...detail, fields: select(detail.detailId, detail.fields)}))};
}
export function sampleObjectSchema(fields: DesignField[]): RJSFSchema {
  return {type: 'object', additionalProperties: false, required: sampleInputFields(fields).filter(field => field.storageRequired).map(field => field.fieldId),
    properties: Object.fromEntries(sampleInputFields(fields).map(field => [field.fieldId,
    fieldSchema(field, {componentId: field.fieldId, componentType: 'field.text', componentVersion: '1', label: field.label,
      columnSpan: 24, readOnly: false, properties: {}, children: []})]))};
}
export function calculationTrialInputs(document: DesignDocument, samples: CalculationSamples) {
  const inputs = (fields: DesignField[], values: Record<string, unknown> = {}) => Object.fromEntries(sampleInputFields(fields)
    .filter(field => Object.prototype.hasOwnProperty.call(values, field.fieldId)).map(field => [field.fieldId, values[field.fieldId]]));
  return {values: inputs(document.fields, samples.values), details: Object.fromEntries((document.details ?? []).map(detail => {
    const rows = samples.details?.[detail.detailId] ?? [];
    if (rows.length > 40) throw new Error('Sample row limit exceeded');
    return [detail.detailId, rows.map(row => inputs(detail.fields, row))];
  }))};
}
