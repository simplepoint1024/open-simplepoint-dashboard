import type {SimpleTableSubmitAction} from '@simplepoint/components/SimpleTable/types';

/** Match the service's mandatory nonblank fields without weakening any field-access metadata. */
export function dictionaryItemFormSchema(schema: any) {
  const next = structuredClone(schema);
  for (const field of ['dictionaryCode', 'name', 'value']) {
    const property = next.properties?.[field]; if (!property) continue;
    property.type = 'string';
    if (field !== 'dictionaryCode') property.allOf = [...(property.allOf ?? []), {pattern: '\\S'}];
  }
  next.required = [...new Set([...(next.required ?? []), ...['dictionaryCode', 'name', 'value'].filter(field => next.properties?.[field])])];
  return next;
}

/** Nested editing is never a dictionary move or a change of the captured record identity. */
export function dictionaryItemSubmission(dictionaryCode: string, action: SimpleTableSubmitAction, formData: any, editing: any) {
  return {...formData, dictionaryCode, ...(action === 'edit' ? {id: editing.id} : {})};
}
