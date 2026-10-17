import type {FormComponentDefinition as Node} from '../../FormRuntime/contracts.ts';
import type {DesignDocument, DesignDetail} from '../../FormDesigner/contracts.ts';

export const DETAIL = 'data.detail';
export function detailIdentity(node: Node) {return node.properties.detailId?.value ?? '';}
function storageCode(document: DesignDocument) {
  const used = new Set(document.details?.map(detail => detail.storageCode));
  let index = 1; while (used.has(`detail_${index}`)) index++;
  return `detail_${index}`;
}
export function initializeDetail(node: Node, identity: () => string, document: DesignDocument): Node {
  const detail: DesignDetail = {detailId: identity(), storageCode: storageCode(document), label: node.label ?? '', indexes: [], fields: [
    {fieldId: identity(), storageCode: 'value', storageLabel: node.label ?? '', label: node.label ?? '', type: 'STRING',
      storageMode: 'STORED', storageRequired: false, submitRequired: false, length: 255, choices: []},
  ]};
  document.details = [...(document.details ?? []), detail];
  return {...node, properties: {...node.properties, detailId: {kind: 'STRING', value: detail.detailId}}};
}
export function cloneDetail(node: Node, identity: () => string, document: DesignDocument, source: DesignDocument) {
  const detail = source.details?.find(item => item.detailId === detailIdentity(node));
  if (!detail) throw new Error('Missing detail definition');
  const next = structuredClone(detail), fields = new Map<string, string>();
  next.detailId = identity(); next.storageCode = storageCode(document);
  next.fields.forEach(field => {const id = identity(); fields.set(field.fieldId, id); field.fieldId = id;});
  next.indexes.forEach(index => {index.indexId = identity(); index.fieldIds = index.fieldIds.map(id => fields.get(id)!);});
  document.details = [...(document.details ?? []), next];
  node.properties.detailId = {kind: 'STRING', value: next.detailId};
}
export function validateDetail(node: Node, document: DesignDocument) {
  const details = document.details ?? [];
  if (details.length > 5 || !details.some(detail => detail.detailId === detailIdentity(node))) throw new Error('Invalid detail binding');
}
