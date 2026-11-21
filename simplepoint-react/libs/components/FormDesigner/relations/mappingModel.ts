import type {DesignDocument} from '../contracts.ts';
import type {FormReferenceMapping, FormRelationField} from '../relationContracts.ts';
import type {FormFieldType} from '../../FormRuntime/contracts.ts';
import {RelationEditError, relationFieldKey, relationSourceField, relationIdentity as identity} from './identity.ts';

/** Local structural checks also run on undo/reload; publication rechecks authorized target metadata. */
export function validateMappingSources(document: DesignDocument) {
  const providers = new Set<string>(), references = new Set((document.relations ?? [])
    .flatMap(relation => relation.sourceField ? [relationFieldKey(relation.sourceField)] : []));
  let total = 0;
  for (const relation of document.relations ?? []) {
    if (relation.retiredByCollectionId) continue;
    const mappings = relation.mappings ?? [], ids = new Set<string>();
    if (!Array.isArray(mappings) || mappings.length > 32 || (total += mappings.length) > 128) throw new RelationEditError('limit');
    for (const mapping of mappings) {
      if (!relation.sourceField || !mapping || ![mapping.mappingId, mapping.targetFieldId, mapping.destinationFieldId].every(identity)
        || ids.has(mapping.mappingId) || !['LIVE', 'SERVER_SNAPSHOT', 'EDITABLE_DEFAULT'].includes(mapping.mode)
        || !['IDENTITY', 'STRING', 'INTEGER', 'DECIMAL'].includes(mapping.conversion)
        || !['CLEAR', 'PRESERVE', 'REJECT'].includes(mapping.nullPolicy)
        || mapping.mode === 'LIVE' && mapping.nullPolicy !== 'CLEAR'
        || mapping.mode === 'SERVER_SNAPSHOT' && mapping.nullPolicy === 'PRESERVE') throw new RelationEditError('definition');
      ids.add(mapping.mappingId);
      const source = {...relation.sourceField, fieldId: mapping.destinationFieldId}, key = relationFieldKey(source);
      const field = relationSourceField(document, source);
      if (!field || providers.has(key) || references.has(key) || field.calculation || field.type === 'JSON'
        || field.defaultValue && field.defaultValue.kind !== 'NULL') throw new RelationEditError('source');
      providers.add(key);
      if (mapping.mode === 'LIVE' ? field.storageMode !== 'TRANSIENT' || field.submitRequired || field.storageRequired
        : field.storageMode !== 'STORED') throw new RelationEditError('source');
    }
  }
}

/** No locale conversion, lossy date coercion or JSON flattening is implied by these policies. */
export function mappingTypesCompatible(conversion: FormReferenceMapping['conversion'], from: FormFieldType, to: FormFieldType): boolean {
  if (from === 'JSON' || to === 'JSON') return false;
  if (conversion === 'IDENTITY') return from === to;
  if (conversion === 'STRING') return ['STRING', 'TEXT'].includes(to);
  const numeric = ['STRING', 'TEXT', 'INT32', 'INT64', 'DECIMAL'].includes(from);
  if (conversion === 'INTEGER') return numeric && ['INT32', 'INT64'].includes(to);
  return conversion === 'DECIMAL' && numeric && to === 'DECIMAL';
}

/** Destinations stay in the owning row; selecting a mode never changes a physical field's storage. */
export function mappingDestinationFields(document: DesignDocument, source: FormRelationField,
  mode: FormReferenceMapping['mode'], ownRelationId?: string) {
  const fields = source.detailId ? document.details?.find(detail => detail.detailId === source.detailId)?.fields ?? [] : document.fields;
  const blocked = new Set<string>();
  for (const relation of document.relations ?? []) {
    if (!relation.sourceField) continue;
    blocked.add(relationFieldKey(relation.sourceField));
    if (!relation.retiredByCollectionId && relation.relationId !== ownRelationId) for (const mapping of relation.mappings ?? [])
      blocked.add(relationFieldKey({...relation.sourceField, fieldId: mapping.destinationFieldId}));
  }
  return fields.filter(field => field.fieldId !== source.fieldId && !blocked.has(relationFieldKey({...source, fieldId: field.fieldId}))
    && !field.calculation && field.type !== 'JSON' && (!field.defaultValue || field.defaultValue.kind === 'NULL')
    && (mode === 'LIVE' ? field.storageMode === 'TRANSIENT' && !field.submitRequired && !field.storageRequired : field.storageMode === 'STORED'));
}
