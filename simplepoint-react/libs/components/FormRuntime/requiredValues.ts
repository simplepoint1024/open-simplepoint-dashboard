import type {RJSFSchema} from '@rjsf/utils';
import type {ApplicationFormDocument} from './contracts.ts';
import {fieldWriteAllowed} from './writePolicy.ts';
import {emptyFormValue} from './conditions.ts';

/** JSON Schema presence is insufficient for whitespace text and serialized empty JSON collections. */
export function requiredValueIssues(document: ApplicationFormDocument, schema: RJSFSchema, values: Record<string, unknown>) {
  const issues: Array<{path: Array<string | number>; label: string}> = [];
  for (const field of document.fields) if (schema.required?.includes(field.fieldId)
    && emptyFormValue(values[field.fieldId], field.type)) issues.push({path: [field.fieldId], label: field.label});
  for (const detail of document.details ?? []) {
    const property = schema.properties?.[detail.detailId] as RJSFSchema | undefined;
    const rowSchema = (property?.items as RJSFSchema | undefined)?.properties?.values as RJSFSchema | undefined;
    if (!rowSchema) continue;
    const rows = values[detail.detailId];
    if (!Array.isArray(rows)) continue;
    rows.forEach((row, index) => {
      for (const field of detail.fields) if ((rowSchema?.required?.includes(field.fieldId)
        || !row?.id && field.submitRequired && fieldWriteAllowed(field, undefined) && !fieldWriteAllowed(field, 'DRAFT'))
        && emptyFormValue(row?.values?.[field.fieldId], field.type)) {
        issues.push({path: [detail.detailId, index, 'values', field.fieldId], label: field.label});
      }
    });
  }
  return issues;
}
