import {createContext} from 'react';
import type {FormRuntimeReference, FormValue} from '../../FormRuntime/contracts.ts';
import {typedFormLiteral} from '../../FormRuntime/literals.ts';

export type ReferenceSearchContext = {fields: Record<string, FormValue>; row: Record<string, FormValue>};
export type ReferenceInputValues = {fields: Record<string, unknown>; row?: Record<string, unknown>; detailId?: string; rowKey?: string};
export const ReferenceInputContext = createContext<ReferenceInputValues>({fields: {}});

/** Only declared inputs leave the form. Keep decimal/int64 strings intact; never send whole rows. */
export function referenceSearchContext(reference: FormRuntimeReference, values: ReferenceInputValues): ReferenceSearchContext | undefined {
  if (!reference.contextual) return undefined;
  const result: ReferenceSearchContext = {fields: {}, row: {}};
  for (const input of reference.contextInputs ?? []) {
    const raw = (input.source === 'FORM' ? values.fields : values.row)?.[input.fieldId];
    if (raw == null || raw === '' || raw === '***') continue;
    if (typeof raw !== 'string' && typeof raw !== 'boolean' && typeof raw !== 'number') continue;
    if (typeof raw === 'number' && (!Number.isFinite(raw) || ['INT32', 'INT64'].includes(input.type) && !Number.isSafeInteger(raw))) continue;
    if (input.type === 'JSON') continue;
    const value = String(raw);
    if (value.length > 2000) continue;
    try {(input.source === 'FORM' ? result.fields : result.row)[input.fieldId] = typedFormLiteral(input.type, value);}
    catch { /* Partially typed values are missing inputs, never malformed server predicates. */ }
  }
  return result;
}

export function referenceContextReady(reference: FormRuntimeReference | undefined, context: ReferenceSearchContext | undefined) {
  return !reference?.contextual || (reference.contextInputs ?? []).every(input =>
    Boolean((input.source === 'FORM' ? context?.fields : context?.row)?.[input.fieldId]));
}
