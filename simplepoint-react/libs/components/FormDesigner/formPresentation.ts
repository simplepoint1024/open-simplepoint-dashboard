import type {DesignDocument} from './contracts.ts';
import {persistentField} from './fieldCapabilities.ts';
/** Missing fields append in document order; presentation never changes canvas or data identities. */
export function orderedListFields(document: DesignDocument) {
  const order = document.presentation?.listFieldOrder ?? [];
  return document.fields.filter(persistentField)
    .slice().sort((a, b) => {
      const index = (key: string) => {const found = order.indexOf(key); return found < 0 ? Number.MAX_SAFE_INTEGER : found;};
      return index(a.fieldId) - index(b.fieldId);
    });
}
