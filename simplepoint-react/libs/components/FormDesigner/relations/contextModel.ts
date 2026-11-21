import type {DesignDocument, DesignField} from '../contracts.ts';
import type {FormReferenceContextFilter, FormRelationField} from '../relationContracts.ts';
import type {FormFieldType} from '../../FormRuntime/contracts.ts';
import type {RelationTargetMetadata} from './catalog.ts';
import {persistentField} from '../fieldCapabilities.ts';

export type ContextFilterDraft = {key: string; value: FormReferenceContextFilter};
export const contextField = (field: DesignField) => persistentField(field) && field.type !== 'JSON';
const text = (type: FormFieldType) => type === 'STRING' || type === 'TEXT';
export const contextTypesMatch = (source: FormFieldType, target: FormFieldType) => source === target || text(source) && text(target);
export const contextOperators = (type?: FormFieldType): FormReferenceContextFilter['operator'][] =>
  ['EQUALS', 'NOT_EQUALS', ...(['INT32', 'INT64', 'DECIMAL', 'DATE', 'TIME', 'DATETIME'].includes(type ?? '')
    ? ['GREATER_THAN', 'GREATER_THAN_OR_EQUAL', 'LESS_THAN', 'LESS_THAN_OR_EQUAL'] as const : [])];

export function contextSourceFields(document: DesignDocument, source?: FormRelationField | null) {
  return [
    ...document.fields.filter(contextField).map(field => ({source: 'FORM' as const, field})),
    ...(source?.detailId ? document.details?.find(detail => detail.detailId === source.detailId)?.fields.filter(contextField)
      .map(field => ({source: 'ROW' as const, field})) ?? [] : []),
  ];
}

/** Local structure checks work offline; publication additionally checks authorized target metadata. */
export function validContextFilters(document: DesignDocument, source: FormRelationField | null | undefined,
  filters: readonly FormReferenceContextFilter[], metadata?: RelationTargetMetadata): boolean {
  if (!Array.isArray(filters) || filters.length > 16) return false;
  const inputs = contextSourceFields(document, source), seen = new Set<string>();
  for (const filter of filters) {
    if (!filter || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(filter.targetFieldId)
      || !['FORM', 'ROW', 'ORGANIZATION'].includes(filter.source)) return false;
    const key = JSON.stringify([filter.targetFieldId, filter.operator, filter.source, filter.sourceFieldId ?? null]);
    if (seen.has(key)) return false;
    seen.add(key);
    const input = filter.source === 'ORGANIZATION' ? undefined
      : inputs.find(input => input.source === filter.source && input.field.fieldId === filter.sourceFieldId)?.field;
    if (filter.source === 'ORGANIZATION' ? filter.sourceFieldId != null : !input) return false;
    const target = metadata?.fields.find(field => field.fieldId === filter.targetFieldId);
    if (metadata && (!target || target.type === 'JSON' || (input ? !contextTypesMatch(input.type, target.type)
      : !['STRING', 'TEXT', 'UUID'].includes(target.type)))) return false;
    if (!contextOperators(target?.type ?? input?.type).includes(filter.operator)) return false;
  }
  return true;
}
