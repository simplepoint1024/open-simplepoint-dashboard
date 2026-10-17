import type {DesignDocument} from './contracts.ts';
/** Missing fields append in document order; presentation never changes canvas or data identities. */
export function orderedListFields(document: DesignDocument) {
  const order = document.presentation?.listFieldOrder ?? [];
  return document.fields.filter(field => ['STORED', 'REFERENCE', 'ATTACHMENT'].includes(field.storageMode))
    .slice().sort((a, b) => {
      const index = (key: string) => {const found = order.indexOf(key); return found < 0 ? Number.MAX_SAFE_INTEGER : found;};
      return index(a.fieldId) - index(b.fieldId);
    });
}
