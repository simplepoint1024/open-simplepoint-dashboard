import type {DesignDocument, DesignField, DesignDetail} from '../contracts.ts';
import type {FormCondition} from '../../FormRuntime/contracts.ts';
import type {FormRelationDefinition, FormRelationField, FormRelationTarget} from '../relationContracts.ts';
import {sameDesignValue} from '../draftState.ts';
import {validateMappingSources} from './mappingModel.ts';
import {validContextFilters} from './contextModel.ts';
import {validCollectionMapping} from './collectionReplacement.ts';
import {localizedTextValid} from '../../../shared/i18n/localizedText.ts';

import {RelationEditError, relationFieldKey, relationSourceField, relationIdentity as identity} from './identity.ts';
export {RelationEditError, relationFieldKey, relationSourceField} from './identity.ts';
export function fieldRelation(document: Pick<DesignDocument, 'relations'>, source: FormRelationField) {
  return document.relations?.find(relation => relation.sourceField && relationFieldKey(relation.sourceField) === relationFieldKey(source));
}
/** Legacy UUID/REFERENCE fields without a business relation keep their original editor semantics. */
export function businessReferenceField(document: DesignDocument, field: DesignField, detailId?: string): boolean {
  const bound = (node: DesignDocument['scenes'][number]['root']): boolean =>
    node.componentType === 'field.reference' && node.fieldId === field.fieldId || node.children.some(bound);
  return Boolean(fieldRelation(document, {detailId, fieldId: field.fieldId}))
    || detailId == null && document.scenes.some(scene => bound(scene.root));
}
/** Local detail field editors need their own relationship scope, never a parent's same-named field. */
export function detailDesignDocument(document: DesignDocument, detail: DesignDetail): DesignDocument {
  const relations = document.relations?.filter(relation => relation.sourceField?.detailId === detail.detailId)
    .map(relation => ({...relation, sourceField: {fieldId: relation.sourceField!.fieldId},
      // This synthetic document only edits detail fields. Root inputs remain in the owning document.
      ...(relation.referenceView?.contextFilters?.length ? {referenceView: {...relation.referenceView,
        contextFilters: relation.referenceView.contextFilters.filter(filter => filter.source !== 'FORM')
          .map(filter => filter.source === 'ROW' ? {...filter, source: 'FORM' as const} : filter)}} : {}),
    } as FormRelationDefinition));
  return {contractVersion: document.contractVersion, fields: detail.fields, indexes: detail.indexes, scenes: [], rules: [],
    ...(relations?.length ? {relations} : {})};
}
export function sameRelationTarget(left: FormRelationTarget, right: FormRelationTarget) {
  return left.applicationId === right.applicationId && left.formId === right.formId && left.accessResourceId === right.accessResourceId;
}

/** Match the public contract's budgets without requiring network metadata during undo or reload. */
export function validateRelationCondition(condition: FormCondition) {
  let count = 0;
  const visit = (node: FormCondition, depth: number) => {
    if (++count > 32 || depth > 4) throw new RelationEditError('limit');
    if (!node || !Array.isArray(node.children) || !Array.isArray(node.values)) throw new RelationEditError('definition');
    if (['AND', 'OR', 'NOT'].includes(node.operator)) {
      if (node.fieldId != null || node.values.length || !node.children.length || node.operator === 'NOT' && node.children.length !== 1)
        throw new RelationEditError('definition');
      node.children.forEach(child => visit(child, depth + 1)); return;
    }
    if (!identity(node.fieldId) || node.children.length || !['EQUALS', 'NOT_EQUALS', 'IN', 'NOT_IN', 'IS_EMPTY', 'IS_NOT_EMPTY',
      'GREATER_THAN', 'GREATER_THAN_OR_EQUAL', 'LESS_THAN', 'LESS_THAN_OR_EQUAL'].includes(node.operator)) throw new RelationEditError('definition');
    const empty = ['IS_EMPTY', 'IS_NOT_EMPTY'].includes(node.operator), multiple = ['IN', 'NOT_IN'].includes(node.operator);
    if (empty ? node.values.length !== 0 : !node.values.length || node.values.length > (multiple ? 100 : 1)) throw new RelationEditError('definition');
    for (const value of node.values) if (!value || !['STRING', 'BOOLEAN', 'INTEGER', 'DECIMAL', 'DATE', 'TIME', 'DATETIME', 'UUID', 'JSON'].includes(value.kind)
      || typeof value.value !== 'string' || value.value.length > 2000) throw new RelationEditError('definition');
  };
  visit(condition, 1);
}
export function validateDesignRelations(document: DesignDocument) {
  validateMappingSources(document);
  const views = document.presentation?.inverseViews ?? [], viewIds = new Set<string>();
  if (views.length > 20) throw new RelationEditError('limit');
  for (const view of views) {
    if (!view || !identity(view.viewId) || viewIds.has(view.viewId) || !identity(view.sourceRelationId)
      || !view.source || ![view.source.applicationId, view.source.formId, view.source.accessResourceId].every(identity)
      || typeof view.label !== 'string' || !view.label.trim() || Array.from(view.label).length > 120
      || ![view.allowCreate, view.allowEdit, view.allowDelete, view.allowUnlink].every(value => typeof value === 'boolean')
      || view.labelLocalization && (!localizedTextValid(view.labelLocalization, 120) || view.labelLocalization.defaultValue !== view.label))
      throw new RelationEditError('definition');
    viewIds.add(view.viewId);
  }
  const relations = document.relations ?? [], ids = new Set<string>(), sources = new Set<string>();
  if (relations.length > 50) throw new RelationEditError('limit');
  for (const relation of relations) {
    const replacement = relation.collectionReplacement;
    if (relation.contractVersion !== (replacement ? '3' : relation.retiredByCollectionId == null ? '1' : '2') || !identity(relation.relationId) || ids.has(relation.relationId)
      || !['ONE_TO_ONE', 'MANY_TO_ONE', 'MANY_TO_MANY'].includes(relation.cardinality)) throw new RelationEditError('definition');
    ids.add(relation.relationId);
    if (!relation.target || ![relation.target.applicationId, relation.target.formId, relation.target.accessResourceId].every(identity))
      throw new RelationEditError('target');
    if (relation.cardinality === 'MANY_TO_MANY' && (relation.collectionLabel && !localizedTextValid(relation.collectionLabel, 120)
      || relation.qualification && relation.qualification.recheck !== 'ON_CHANGE' || relation.referenceView?.contextFilters?.length))
      throw new RelationEditError('definition');
    const source = relation.sourceField;
    if (replacement && (relation.cardinality !== 'MANY_TO_MANY' || !identity(replacement.sourceRelationId)
      || relations.some(item => item.relationId === replacement.sourceRelationId)
      || !validCollectionMapping(replacement.targetRecords))) throw new RelationEditError('definition');
    if (relation.retiredByCollectionId != null && (!identity(relation.retiredByCollectionId)
      || relation.retiredByCollectionId === relation.relationId || relation.cardinality !== 'MANY_TO_ONE'
      || !source || source.detailId != null)) throw new RelationEditError('source');
    if ((relation.cardinality === 'MANY_TO_MANY') !== (source == null)) throw new RelationEditError('source');
    if (source) {
      if (!identity(source.fieldId) || source.detailId != null && !identity(source.detailId) || sources.has(relationFieldKey(source)))
        throw new RelationEditError('source');
      sources.add(relationFieldKey(source));
      const field = relationSourceField(document, source);
      if (!field || field.type !== 'UUID' || field.storageMode !== 'REFERENCE' || field.calculation || field.choices.length
        || field.defaultValue != null && field.defaultValue.kind !== 'NULL') throw new RelationEditError('source');
    }
    const view = relation.referenceView;
    if (view && !relation.retiredByCollectionId) {
      if (!identity(view.labelFieldId) || typeof view.allowDrillThrough !== 'boolean') throw new RelationEditError('definition');
      for (const [values, limit] of [[view.searchFieldIds, 8], [view.displayFieldIds, 12]] as const)
        if (!Array.isArray(values) || values.length > limit || new Set(values).size !== values.length || !values.every(identity))
          throw new RelationEditError('definition');
      if (view.candidateFilter) validateRelationCondition(view.candidateFilter);
      if (!validContextFilters(document, source, view.contextFilters ?? [])) throw new RelationEditError('definition');
    }
    if (relation.qualification) {
      if (!['ON_CHANGE', 'ON_SUBMIT', 'ALWAYS'].includes(relation.qualification.recheck)) throw new RelationEditError('definition');
      validateRelationCondition(relation.qualification.condition);
    }
  }
}

/** One atomic field/relationship command. Source identity and stale snapshots cannot be replaced. */
export function applyRelationEdit(document: DesignDocument, source: FormRelationField, baseField: DesignField,
  base: FormRelationDefinition | undefined, edit: FormRelationDefinition): DesignDocument {
  const current = relationSourceField(document, source);
  if (!sameDesignValue(current, baseField) || !sameDesignValue(fieldRelation(document, source), base)) throw new RelationEditError('conflict');
  if (!current || base?.retiredByCollectionId || current.fieldId !== source.fieldId || current.type !== 'UUID' || !['STORED', 'REFERENCE'].includes(current.storageMode)
    || !edit.sourceField || relationFieldKey(edit.sourceField) !== relationFieldKey(source)) throw new RelationEditError('source');
  if (base && edit.relationId !== base.relationId || !base && document.relations?.some(item => item.relationId === edit.relationId))
    throw new RelationEditError('conflict');
  const field = {...current, storageMode: 'REFERENCE' as const};
  const next: DesignDocument = {...document, relations: base ? document.relations!.map(item => item.relationId === base.relationId ? structuredClone(edit) : item)
    : [...(document.relations ?? []), structuredClone(edit)]};
  if (source.detailId) next.details = document.details?.map(detail => detail.detailId === source.detailId
    ? {...detail, fields: detail.fields.map(item => item.fieldId === source.fieldId ? field : item)} : detail);
  else next.fields = document.fields.map(item => item.fieldId === source.fieldId ? field : item);
  validateDesignRelations(next); return next;
}

/** Copy only owning fields, never inverse views or external target field/condition identities. */
export function cloneFieldRelations(relations: DesignDocument['relations'], fields: ReadonlyMap<string, string>, identity: () => string) {
  return (relations ?? []).flatMap(relation => {
    if (!relation.sourceField || relation.retiredByCollectionId) return [];
    const key = fields.get(relationFieldKey(relation.sourceField));
    if (!key) return [];
    const parts = key.split('.');
    const sourceField = parts.length === 1 ? {fieldId: parts[0]} : {detailId: parts[0], fieldId: parts[1]};
    const cloned = {...structuredClone(relation), relationId: identity(), sourceField} as FormRelationDefinition;
    if (cloned.referenceView?.contextFilters) cloned.referenceView.contextFilters = cloned.referenceView.contextFilters.map(filter => {
      if (filter.source === 'ORGANIZATION') return filter;
      const owner = filter.source === 'ROW' ? `${relation.sourceField!.detailId}.` : '';
      const mapped = fields.get(`${owner}${filter.sourceFieldId}`);
      return mapped ? {...filter, sourceFieldId: mapped.substring(mapped.lastIndexOf('.') + 1)} : filter;
    });
    if (cloned.mappings) {
      cloned.mappings = cloned.mappings.flatMap(mapping => {
        const owner = relation.sourceField!.detailId ? `${relation.sourceField!.detailId}.` : '';
        const destination = fields.get(`${owner}${mapping.destinationFieldId}`);
        // A copied input cannot take over the original field's provider. Only copied destinations follow.
        return destination ? [{...mapping, mappingId: identity(), destinationFieldId: destination.substring(destination.lastIndexOf('.') + 1)}] : [];
      });
      if (!cloned.mappings.length) delete cloned.mappings;
    }
    return [cloned];
  });
}
