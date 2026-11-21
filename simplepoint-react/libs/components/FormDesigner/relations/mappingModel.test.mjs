import assert from 'node:assert/strict';
import test from 'node:test';
import {validateDesignRelations, cloneFieldRelations} from './model.ts';
import {mappingDestinationFields, mappingTypesCompatible} from './mappingModel.ts';
import {changeRelationTarget, compileRelationDraft, relationDraft} from './edit.ts';
const field = (id, type = 'STRING', storageMode = 'STORED') => ({fieldId: id, storageCode: id, label: id, storageLabel: id,
  type, storageMode, choices: [], submitRequired: false, storageRequired: false});
const mapping = (destinationFieldId = 'address', mode = 'SERVER_SNAPSHOT') => ({mappingId: `map-${destinationFieldId}`,
  targetFieldId: 'address', destinationFieldId, mode, conversion: 'IDENTITY', nullPolicy: 'CLEAR'});
const target = {applicationId: 'app', formId: 'customers', accessResourceId: 'customer-page'};
const relation = (source = 'customer', mappings = [mapping()]) => ({contractVersion: '1', relationId: `ref-${source}`,
  cardinality: 'MANY_TO_ONE', sourceField: {fieldId: source}, target, mappings,
  referenceView: {labelFieldId: 'name', searchFieldIds: [], displayFieldIds: [], allowDrillThrough: false}});
const document = () => ({contractVersion: '1', fields: [field('customer', 'UUID', 'REFERENCE'), field('address')],
  scenes: [], indexes: [], rules: [], relations: [relation()]});
const metadata = {target, formVersion: 1, pageId: 'page', fields: ['name', 'address'].map(fieldId => ({fieldId, label: fieldId, type: 'STRING', searchable: true}))};

test('mapping editor destinations retain ownership and never change physical storage when modes change', () => {
  const doc = document();
  doc.fields.push(field('live', 'STRING', 'TRANSIENT'), {...field('requiredLive', 'STRING', 'TRANSIENT'), submitRequired: true},
    {...field('formula'), calculation: {expression: '1'}}, {...field('fixed'), defaultValue: {kind: 'STRING', value: 'fixed'}},
    field('supplier', 'UUID', 'REFERENCE'), field('external'));
  doc.relations.push(relation('supplier', [mapping('external')]));
  doc.details = [{detailId: 'lines', fields: [field('customer', 'UUID', 'REFERENCE'), field('address'), field('lineOnly')]}];
  const snapshot = structuredClone(doc);
  assert.deepEqual(mappingDestinationFields(doc, {fieldId: 'customer'}, 'SERVER_SNAPSHOT', 'ref-customer').map(field => field.fieldId), ['address']);
  assert.deepEqual(mappingDestinationFields(doc, {fieldId: 'customer'}, 'LIVE', 'ref-customer').map(field => field.fieldId), ['live']);
  assert.deepEqual(mappingDestinationFields(doc, {fieldId: 'customer', detailId: 'lines'}, 'EDITABLE_DEFAULT', 'detail-relation').map(field => field.fieldId), ['address', 'lineOnly']);
  assert.deepEqual(mappingDestinationFields(doc, {fieldId: 'customer', detailId: 'missing'}, 'SERVER_SNAPSHOT'), []);
  assert.deepEqual(doc, snapshot);
});

test('mapping drafts survive ordinary edits and lose old target fields on rebinding', () => {
  const doc = document(), draft = relationDraft(doc.relations[0]);
  assert.deepEqual(compileRelationDraft(draft, {fieldId: 'customer'}, metadata, doc), doc.relations[0]);
  assert.equal(changeRelationTarget(draft, {...target}), draft);
  assert.equal(changeRelationTarget(draft, {...target, formId: 'suppliers'}).mappings, undefined);
  assert.throws(() => compileRelationDraft(draft, {fieldId: 'customer'}, {...metadata, fields: metadata.fields.slice(0, 1)}, doc));
  const legacy = relationDraft({...doc.relations[0], mappings: undefined});
  assert(!('mappings' in compileRelationDraft(legacy, {fieldId: 'customer'}, metadata, doc)));
});

test('one provider per row field prevents formula, default, reference and duplicate takeover', () => {
  validateDesignRelations(document());
  for (const replacement of [{calculation: {languageVersion: '1', expression: '1'}}, {defaultValue: {kind: 'STRING', value: 'fixed'}},
    {storageMode: 'REFERENCE'}, {type: 'JSON'}]) {
    const doc = document(); Object.assign(doc.fields[1], replacement); assert.throws(() => validateDesignRelations(doc));
  }
  const doc = document(); doc.fields.push(field('supplier', 'UUID', 'REFERENCE')); doc.relations.push(relation('supplier'));
  assert.throws(() => validateDesignRelations(doc));
  const own = document(); own.relations[0].mappings = [mapping('customer')]; assert.throws(() => validateDesignRelations(own));
});

test('live fields cannot persist values or claim old provenance and aggregate budgets are bounded', () => {
  const doc = document(); doc.fields[1].storageMode = 'TRANSIENT'; doc.relations[0].mappings[0].mode = 'LIVE';
  validateDesignRelations(doc);
  for (const policy of ['REJECT', 'PRESERVE']) {
    doc.relations[0].mappings[0].nullPolicy = policy; assert.throws(() => validateDesignRelations(doc));
  }
  const many = document(); many.relations[0].mappings = Array.from({length: 33}, (_, i) => mapping(`value${i}`));
  assert.throws(() => validateDesignRelations(many));
});

test('copied relation only owns copied destinations and preserves target field identity', () => {
  let sequence = 0;
  const original = document().relations;
  const inputOnly = cloneFieldRelations(original, new Map([['customer', 'newCustomer']]), () => `copy${++sequence}`)[0];
  assert(!('mappings' in inputOnly));
  const both = cloneFieldRelations(original, new Map([['customer', 'newCustomer'], ['address', 'newAddress']]), () => `copy${++sequence}`)[0];
  assert.equal(both.mappings[0].destinationFieldId, 'newAddress');
  assert.equal(both.mappings[0].targetFieldId, 'address');
  assert.notEqual(both.mappings[0].mappingId, original[0].mappings[0].mappingId);
  assert.equal(original[0].mappings[0].destinationFieldId, 'address');
});

test('root and owned detail use separate provider identities and explicit conversion families', () => {
  const doc = document(); doc.details = [{detailId: 'lines', fields: structuredClone(doc.fields), indexes: []}];
  doc.relations.push({...relation(), relationId: 'line-customer', sourceField: {fieldId: 'customer', detailId: 'lines'}});
  validateDesignRelations(doc);
  assert(mappingTypesCompatible('DECIMAL', 'STRING', 'DECIMAL'));
  assert(mappingTypesCompatible('INTEGER', 'DECIMAL', 'INT64'));
  assert(!mappingTypesCompatible('INTEGER', 'DATE', 'INT64'));
  assert(!mappingTypesCompatible('IDENTITY', 'INT64', 'INT32'));
  assert(!mappingTypesCompatible('STRING', 'JSON', 'STRING'));
});
