import type {DesignDocument, DesignField} from '../contracts.ts';
import type {FormRelationField} from '../relationContracts.ts';

export class RelationEditError extends Error {
  readonly code: 'conflict' | 'source' | 'target' | 'definition' | 'limit';
  constructor(code: RelationEditError['code']) {super(code); this.code = code;}
}
export const relationIdentity = (value: unknown): value is string => typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(value);
export const relationFieldKey = (source: FormRelationField) => source.detailId ? `${source.detailId}.${source.fieldId}` : source.fieldId;
export function relationSourceField(document: DesignDocument, source: FormRelationField): DesignField | undefined {
  return (source.detailId ? document.details?.find(detail => detail.detailId === source.detailId)?.fields : document.fields)
    ?.find(field => field.fieldId === source.fieldId);
}
