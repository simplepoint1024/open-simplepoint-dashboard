import test from 'node:test';
import assert from 'node:assert/strict';
import {validContextFilters} from './contextModel.ts';
import {changeRelationTarget, compileRelationDraft, relationDraft} from './edit.ts';
import {cloneFieldRelations, detailDesignDocument, validateDesignRelations} from './model.ts';

const field = (fieldId, type = 'STRING', storageMode = 'STORED') => ({fieldId, storageCode: fieldId, label: fieldId,
  type, storageMode, storageRequired: false, submitRequired: false, choices: []});
const filters = [
  {targetFieldId: 'region', source: 'FORM', sourceFieldId: 'region', operator: 'EQUALS'},
  {targetFieldId: 'branch', source: 'ROW', sourceFieldId: 'region', operator: 'NOT_EQUALS'},
  {targetFieldId: 'organization', source: 'ORGANIZATION', operator: 'EQUALS'},
];
const target = {applicationId: 'customers', formId: 'customer', accessResourceId: 'customer-page'};
const relation = {contractVersion: '1', relationId: 'customer', cardinality: 'MANY_TO_ONE', sourceField: {detailId: 'lines', fieldId: 'customer'}, target,
  referenceView: {labelFieldId: 'name', searchFieldIds: [], displayFieldIds: [], allowDrillThrough: true, contextFilters: filters}};
const document = {contractVersion: '1', fields: [field('region')], indexes: [], rules: [], scenes: [], relations: [relation],
  details: [{detailId: 'lines', fields: [field('region'), field('customer', 'UUID', 'REFERENCE')], indexes: []}]};
const metadata = {target, fields: [field('name'), field('region', 'TEXT'), field('branch'), field('organization', 'UUID')]};

test('typed authoring roundtrips filters, validates missing sources and resets target dependencies together', () => {
  const draft = relationDraft(relation);
  assert.deepEqual(compileRelationDraft(draft, relation.sourceField, metadata, document), relation);
  const changed = changeRelationTarget(draft, {...target, formId: 'other'});
  assert.deepEqual(changed.contextFilters, []);
  const legacy = {...relation, referenceView: {...relation.referenceView}}; delete legacy.referenceView.contextFilters;
  assert(!('contextFilters' in compileRelationDraft(relationDraft(legacy), relation.sourceField, metadata).referenceView));
  assert.throws(() => compileRelationDraft(draft, relation.sourceField, metadata));
  assert.throws(() => compileRelationDraft(draft, relation.sourceField, {...metadata, fields: []}, document));
  validateDesignRelations(document);
  const local = detailDesignDocument(document, document.details[0]);
  validateDesignRelations(local);
  assert.deepEqual(local.relations[0].referenceView.contextFilters, [{...filters[1], source: 'FORM'}, filters[2]]);
  assert.equal(document.relations[0].sourceField.detailId, 'lines');
});

test('budget, duplicate, type, operator, transient source and cross-row violations fail closed', () => {
  for (const values of [[...filters, filters[0]], Array(17).fill(filters[0]), [{...filters[0], sourceFieldId: 'missing'}],
    [{...filters[0], operator: 'GREATER_THAN'}], [{...filters[0], targetFieldId: 'organization'}],
    [{...filters[2], sourceFieldId: 'injected'}], [{...filters[0], targetFieldId: 'missing'}]])
    assert.equal(validContextFilters(document, relation.sourceField, values, metadata), false);
  assert.equal(validContextFilters(document, {fieldId: 'customer'}, [filters[1]], metadata), false);
  assert.equal(validContextFilters({...document, fields: [field('region', 'STRING', 'TRANSIENT')]}, relation.sourceField, [filters[0]], metadata), false);
});

test('copy remaps owned input identities but never target IDs or unrelated parent fields', () => {
  const mapping = new Map([['lines.customer', 'cloned.ref'], ['lines.region', 'cloned.local'], ['region', 'parentCopy']]);
  const [copy] = cloneFieldRelations([relation], mapping, () => 'new');
  assert.deepEqual(copy.sourceField, {detailId: 'cloned', fieldId: 'ref'});
  assert.deepEqual(copy.referenceView.contextFilters, [{...filters[0], sourceFieldId: 'parentCopy'}, {...filters[1], sourceFieldId: 'local'}, filters[2]]);
  mapping.delete('region');
  assert.equal(cloneFieldRelations([relation], mapping, () => 'new')[0].referenceView.contextFilters[0].sourceFieldId, 'region');
  assert.deepEqual(relation.referenceView.contextFilters, filters);
});


test('inverse views preserve independent owning relations and reject ambiguous view identities', () => {
  const view = {viewId:'trades',label:'交易记录',source:target,sourceRelationId:'customer',
    allowCreate:true,allowEdit:true,allowDelete:false,allowUnlink:false};
  const doc = {...document,presentation:{listFieldOrder:[],inverseViews:[view]}};
  validateDesignRelations(doc);
  assert.deepEqual(doc.relations, document.relations);
  for (const inverseViews of [[view,view],Array(21).fill(view),[{...view,viewId:'../view'}],
    [{...view,allowCreate:'true'}],[{...view,label:''}],[{...view,sourceRelationId:''}]])
    assert.throws(() => validateDesignRelations({...doc,presentation:{listFieldOrder:[],inverseViews}}));
  validateDesignRelations({...doc,presentation:{...doc.presentation,labelWidth:180}});
});
