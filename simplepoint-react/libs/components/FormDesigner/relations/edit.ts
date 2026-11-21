import type {DesignDocument} from '../contracts.ts';
import type {FormRelationDefinition, FormRelationField, FormRelationTarget, FormReferenceMapping} from '../relationContracts.ts';
import type {RelationTargetMetadata} from './catalog.ts';
import type {QueryNode} from '../../Table/query.ts';
import {compileCondition, ruleCondition} from '../ruleModel.ts';
import {RelationEditError, sameRelationTarget, validateRelationCondition} from './model.ts';
import {mappingTypesCompatible} from './mappingModel.ts';
import {relationSourceField} from './identity.ts';
import {validContextFilters, type ContextFilterDraft} from './contextModel.ts';

export type RelationDraft = {
  relationId: string; target?: FormRelationTarget; cardinality: 'MANY_TO_ONE' | 'ONE_TO_ONE';
  labelFieldId: string; searchFieldIds: string[]; displayFieldIds: string[]; allowDrillThrough: boolean;
  candidate?: QueryNode; qualification?: QueryNode; recheck: 'ON_CHANGE' | 'ON_SUBMIT' | 'ALWAYS';
  contextFilters: ContextFilterDraft[];
  mappings?: FormReferenceMapping[];
};
export function relationDraft(base?: FormRelationDefinition, identity: () => string = () => crypto.randomUUID()): RelationDraft {
  if (base?.cardinality === 'MANY_TO_MANY') throw new RelationEditError('source');
  return {...(base?.mappings?.length ? {mappings: structuredClone(base.mappings)} : {}), relationId: base?.relationId ?? identity(), target: base?.target && {...base.target}, cardinality: base?.cardinality ?? 'MANY_TO_ONE',
    labelFieldId: base?.referenceView?.labelFieldId ?? '', searchFieldIds: [...(base?.referenceView?.searchFieldIds ?? [])],
    displayFieldIds: [...(base?.referenceView?.displayFieldIds ?? [])], allowDrillThrough: base?.referenceView?.allowDrillThrough ?? false,
    candidate: base?.referenceView?.candidateFilter ? ruleCondition(base.referenceView.candidateFilter) : undefined,
    contextFilters: (base?.referenceView?.contextFilters ?? []).map(value => ({key: identity(), value: structuredClone(value)})),
    qualification: base?.qualification ? ruleCondition(base.qualification.condition) : undefined, recheck: base?.qualification?.recheck ?? 'ON_CHANGE'};
}
/** Target changes clear every target-field binding together, retaining the owning relation identity. */
export function changeRelationTarget(draft: RelationDraft, target?: FormRelationTarget): RelationDraft {
  if (draft.target && target && sameRelationTarget(draft.target, target)) return draft;
  return {...draft, target, labelFieldId: '', searchFieldIds: [], displayFieldIds: [], candidate: undefined, qualification: undefined, contextFilters: [], mappings: undefined};
}
/** Typed target-only condition adapter; storage names and executable expressions are never accepted. */
export function relationConditionDocument(metadata: RelationTargetMetadata): DesignDocument {
  return {contractVersion: '1', fields: metadata.fields.map(field => ({...field, storageMode: 'STORED', storageRequired: false,
    submitRequired: false, choices: []})), indexes: [], scenes: [], rules: []};
}
export function compileRelationDraft(draft: RelationDraft, sourceField: FormRelationField, metadata: RelationTargetMetadata, sourceDocument?: DesignDocument): FormRelationDefinition {
  if (!draft.target || !sameRelationTarget(draft.target, metadata.target)) throw new RelationEditError('target');
  const fields = new Map(metadata.fields.map(field => [field.fieldId, field]));
  if (!fields.has(draft.labelFieldId) || draft.searchFieldIds.some(id => !fields.get(id)?.searchable)
    || draft.displayFieldIds.some(id => !fields.has(id))) throw new RelationEditError('target');
  const document = relationConditionDocument(metadata);
  for (const mapping of draft.mappings ?? []) {
    const from = fields.get(mapping.targetFieldId);
    const to = sourceDocument && relationSourceField(sourceDocument, {...sourceField, fieldId: mapping.destinationFieldId});
    if (!from || !to || !mappingTypesCompatible(mapping.conversion, from.type, to.type)) throw new RelationEditError('target');
  }
  const contextFilters = draft.contextFilters.map(row => row.value);
  if (contextFilters.length && (!sourceDocument || !validContextFilters(sourceDocument, sourceField, contextFilters, metadata)))
    throw new RelationEditError('definition');
  const condition = (node: QueryNode) => {const result = compileCondition(node, document); validateRelationCondition(result); return result;};
  return {...(draft.mappings?.length ? {mappings: structuredClone(draft.mappings)} : {}), contractVersion: '1', relationId: draft.relationId, cardinality: draft.cardinality, sourceField: {...sourceField}, target: {...draft.target},
    referenceView: {labelFieldId: draft.labelFieldId, searchFieldIds: [...draft.searchFieldIds], displayFieldIds: [...draft.displayFieldIds],
      allowDrillThrough: draft.allowDrillThrough, ...(draft.candidate ? {candidateFilter: condition(draft.candidate)} : {}),
      ...(contextFilters.length ? {contextFilters: structuredClone(contextFilters)} : {})},
    ...(draft.qualification ? {qualification: {condition: condition(draft.qualification), recheck: draft.recheck}} : {})};
}
