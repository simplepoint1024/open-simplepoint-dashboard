import assert from 'node:assert/strict';
import test from 'node:test';
import {applyRelationEdit, validateDesignRelations, fieldRelation, relationFieldKey, detailDesignDocument, businessReferenceField} from './model.ts';
import {changeRelationTarget, compileRelationDraft, relationDraft} from './edit.ts';
import {addScene, copyComponent, insertComponent, pasteComponent, removeComponent, recordHistory, undo, redo, validateStructure} from '../model.ts';
import {applyFieldEdit, fieldEdit, allowedFieldTypes} from '../dataModel.ts';

const descriptor = (type, container = false, types = []) => ({type, version: '1', container, fieldRequired: Boolean(types.length),
  supportedFieldTypes: types, allowedChildTypes: [], allowedParentTypes: [], propertySchema: {}});
const registry = [descriptor('layout.form', true), descriptor('layout.group', true), descriptor('field.reference', false, ['UUID']), descriptor('data.detail')];
const component = (componentId, componentType, extra = {}) => ({componentId, componentType, componentVersion: '1', columnSpan: 24,
  readOnly: false, properties: {}, children: [], ...extra});
const field = (fieldId, storageMode = 'REFERENCE') => ({fieldId, storageCode: fieldId, label: fieldId, storageLabel: fieldId,
  type: 'UUID', storageMode, storageRequired: false, submitRequired: false, choices: []});
const target = {applicationId: 'customers', formId: 'customer', accessResourceId: 'customer-page'};
const relation = (fieldId = 'customer', detailId) => ({contractVersion: '1', relationId: detailId ? `${detailId}-customer` : 'customer',
  cardinality: 'MANY_TO_ONE', sourceField: {fieldId, ...(detailId ? {detailId} : {})}, target: {...target},
  referenceView: {labelFieldId: 'name', searchFieldIds: ['name'], displayFieldIds: ['balance'], allowDrillThrough: true,
    candidateFilter: {operator: 'EQUALS', fieldId: 'name', values: [{kind: 'STRING', value: '客户・日本語😀'}], children: []}}});
const document = () => ({contractVersion: '1', fields: [field('customer')], indexes: [], rules: [], relations: [relation()], scenes: [{sceneId: 'main',
  code: 'main', name: 'Main', type: 'MAIN', root: component('root', 'layout.form', {children: [component('input', 'field.reference', {fieldId: 'customer'})]})}]});
const metadata = {target, pageId: 'page', formVersion: 1, fields: [
  {fieldId: 'name', label: '姓名', type: 'STRING', searchable: true}, {fieldId: 'balance', label: '金额', type: 'DECIMAL', searchable: false},
]};

test('new reference components use owned field initialization and can remain unconfigured drafts', () => {
  const initial = document(), next = insertComponent(initial, 'root', 0, registry[2], 'Customer', registry).document;
  assert.equal(next.fields[0].storageMode, 'REFERENCE');
  assert.equal(next.fields[0].type, 'UUID');
  assert.equal(next.relations.length, 1);
  assert.equal(initial.fields.length, 1);
});

test('copy uses clipboard relation snapshots and remaps only owning identities', () => {
  const original = document(), clipboard = copyComponent(original, 'input');
  const afterCopy = structuredClone(original); afterCopy.relations[0].target.formId = 'new-target';
  const pasted = pasteComponent(afterCopy, 'root', clipboard, registry).document;
  const copy = pasted.relations.find(item => item.relationId !== 'customer');
  assert.notEqual(copy.sourceField.fieldId, 'customer');
  assert.equal(copy.target.formId, 'customer');
  assert.deepEqual(copy.referenceView, original.relations[0].referenceView, 'target-side IDs/conditions are not source fields');
  assert.equal(pasted.fields.find(item => item.fieldId === copy.sourceField.fieldId).storageMode, 'REFERENCE');
  assert.equal(original.relations.length, 1);
});

test('detail and root same-name sources clone independently and inverse collections never duplicate', () => {
  const original = document();
  original.details = [{detailId: 'lines', storageCode: 'lines', label: 'Lines', fields: [field('customer')], indexes: []}];
  original.relations.push(relation('customer', 'lines'), {contractVersion: '1', relationId: 'members', cardinality: 'MANY_TO_MANY', target});
  original.scenes[0].root.children = [component('group', 'layout.group', {children: [original.scenes[0].root.children[0],
    component('detail', 'data.detail', {properties: {detailId: {kind: 'STRING', value: 'lines'}}})]})];
  const next = pasteComponent(original, 'root', copyComponent(original, 'group'), registry).document;
  assert.equal(next.relations.length, 5);
  assert.equal(next.relations.filter(item => item.cardinality === 'MANY_TO_MANY').length, 1);
  const copied = next.relations.filter(item => !original.relations.some(old => old.relationId === item.relationId));
  const detail = next.details.find(item => item.detailId !== 'lines');
  assert(copied.some(item => relationFieldKey(item.sourceField) === `${detail.detailId}.${detail.fields[0].fieldId}`));
  validateStructure(next, registry);
});

test('multiple copied views of one owned detail retain a single cloned relationship owner', () => {
  const original = document();
  original.details = [{detailId: 'lines', storageCode: 'lines', label: 'Lines', fields: [field('customer')], indexes: []}];
  original.relations.push(relation('customer', 'lines'));
  original.scenes[0].root.children = [component('group', 'layout.group', {children: ['first', 'second'].map(id =>
    component(id, 'data.detail', {properties: {detailId: {kind: 'STRING', value: 'lines'}}}))})];
  const next = pasteComponent(original, 'root', copyComponent(original, 'group'), registry).document;
  assert.equal(next.details.length, 2);
  assert.equal(next.relations.length, 3);
  const copiedViews = next.scenes[0].root.children.at(-1).children;
  assert.equal(copiedViews[0].properties.detailId.value, copiedViews[1].properties.detailId.value);
});

test('removing a visual control retains storage and relations and scene reuse shares one owner', () => {
  const initial = document(), removed = removeComponent(initial, 'input', registry);
  assert.deepEqual(removed.relations, initial.relations);
  const scene = addScene(initial, 'DIALOG', 'Dialog', registry);
  const reused = insertComponent(scene.document, scene.document.scenes[1].root.componentId, 0, registry[2], 'Customer', registry, 'customer').document;
  assert.equal(reused.relations.length, 1);
  assert.equal(reused.fields.length, 1);
});

test('root and owned-detail relation edits commit atomically and reject stale field or relation bases', () => {
  for (const detailId of [undefined, 'lines']) {
    const doc = document();
    const source = {fieldId: 'other', ...(detailId ? {detailId} : {})}, baseField = field('other', 'STORED');
    if (detailId) doc.details = [{detailId, storageCode: detailId, label: 'Lines', fields: [baseField], indexes: []}]; else doc.fields.push(baseField);
    const edit = {...relation('other', detailId), relationId: 'other-relation'};
    const next = applyRelationEdit(doc, source, baseField, undefined, edit);
    assert.equal(fieldRelation(next, source).relationId, 'other-relation');
    assert.equal((detailId ? next.details[0].fields : next.fields).find(field => field.fieldId === 'other').storageMode, 'REFERENCE');
    assert.equal(baseField.storageMode, 'STORED');
    assert.throws(() => applyRelationEdit(next, source, baseField, undefined, edit), error => error.code === 'conflict');
    const current = detailId ? next.details[0].fields[0] : next.fields.find(field => field.fieldId === 'other');
    assert.throws(() => applyRelationEdit(next, source, current, edit, {...edit, relationId: 'replacement'}), error => error.code === 'conflict');
    const history = recordHistory({past: [], present: doc, future: []}, next);
    assert.deepEqual(undo(history).present, doc);
    assert.deepEqual(redo(undo(history)).present, next);
  }
});

test('every structural command rejects dangling, duplicated and invalid source definitions', () => {
  for (const mutate of [
    doc => doc.relations.push({...relation(), relationId: 'duplicate-source'}),
    doc => doc.relations.push({...relation('missing'), relationId: 'missing'}),
    doc => {doc.relations[0].sourceField.detailId = 'missing-detail';},
    doc => {doc.relations[0].target.formId = '../target';},
    doc => {doc.fields[0].type = 'STRING';},
    doc => {doc.fields[0].defaultValue = {kind: 'UUID', value: 'aaaaaaaa-1111-2222-3333-000000000001'};},
    doc => {doc.fields[0].calculation = {languageVersion: '1', expression: 'NULL'};},
    doc => {doc.relations[0].referenceView.searchFieldIds = ['name', 'name'];},
  ]) {const doc = document(); mutate(doc); assert.throws(() => validateStructure(doc, registry));}
});

test('field editing cannot turn root or detail references into free text or static choice/default fields', () => {
  for (const scope of ['form', 'detail']) {
    const doc = document(), base = doc.fields[0];
    assert.deepEqual(allowedFieldTypes(doc, base, registry), ['UUID']);
    for (const edit of [{...fieldEdit(base), type: 'STRING'}, {...fieldEdit(base), defaultMode: 'VALUE', defaultText: 'aaaaaaaa-1111-2222-3333-000000000001'},
      {...fieldEdit(base), choices: [{value: 'aaaaaaaa-1111-2222-3333-000000000001', label: 'Static customer'}]}])
      assert.throws(() => applyFieldEdit(doc, base, edit, registry, ['EDITABLE'], false, scope));
    const renamed = applyFieldEdit(doc, base, {...fieldEdit(base), label: '客户😀'}, registry, ['EDITABLE'], false, scope);
    assert.deepEqual(renamed.relations, doc.relations);
  }
});

test('changing target clears old target bindings but preserves relation identity and exact reselection', () => {
  const draft = relationDraft(relation()); draft.qualification = {field: 'name', op: 'equals', value: 'active'};
  assert.equal(changeRelationTarget(draft, {...target}), draft);
  const changed = changeRelationTarget(draft, {...target, accessResourceId: 'other-page'});
  assert.equal(changed.relationId, draft.relationId);
  assert.equal(changed.labelFieldId, '');
  assert.deepEqual(changed.searchFieldIds, []);
  assert.equal(changed.candidate, undefined);
  assert.equal(changed.qualification, undefined);
});

test('authoring compiles exact typed conditions and validates current authorized target fields', () => {
  const draft = relationDraft(relation());
  draft.qualification = {op: 'than:greater', field: 'balance', value: '9007199254740993.01'};
  draft.recheck = 'ON_SUBMIT';
  const result = compileRelationDraft(draft, {fieldId: 'customer'}, metadata);
  assert.deepEqual(result.qualification, {recheck: 'ON_SUBMIT', condition: {operator: 'GREATER_THAN', fieldId: 'balance',
    values: [{kind: 'DECIMAL', value: '9007199254740993.01'}], children: []}});
  validateDesignRelations({...document(), relations: [result]});
  for (const change of [{labelFieldId: 'masked'}, {searchFieldIds: ['balance']}, {displayFieldIds: ['hidden']},
    {qualification: {op: 'equals', field: 'missing', value: 'secret'}}, {candidate: {op: 'equals', field: 'name', value: 'x'.repeat(2001)}}])
    assert.throws(() => compileRelationDraft({...draft, ...change}, {fieldId: 'customer'}, metadata));
  assert.throws(() => compileRelationDraft(draft, {fieldId: 'customer'}, {...metadata, target: {...target, formId: 'rebound'}}));
});

test('legacy documents without relations keep metadata absence through unrelated copies', () => {
  const doc = document(); delete doc.relations;
  const next = pasteComponent(doc, 'root', copyComponent(doc, 'input'), registry).document;
  assert.equal(Object.hasOwn(next, 'relations'), false);
});

test('legacy UUID reference fields retain editable defaults without becoming governed business references', () => {
  const doc = document(); delete doc.relations; doc.scenes[0].root.children = [];
  const base = {...doc.fields[0], defaultValue: {kind: 'UUID', value: 'aaaaaaaa-1111-2222-3333-000000000001'}};
  doc.fields = [base];
  assert.equal(businessReferenceField(doc, base), false);
  assert.deepEqual(allowedFieldTypes(doc, base, registry), ['STRING', 'UUID']);
  const changed = applyFieldEdit(doc, base, {...fieldEdit(base), label: 'Legacy reference'}, registry, ['EDITABLE']);
  assert.deepEqual(changed.fields[0].defaultValue, base.defaultValue);
  assert.equal(Object.hasOwn(changed, 'relations'), false);
});

test('detail editing projects only its own relation even when parent and sibling fields share the same ID', () => {
  const doc = document();
  doc.details = ['lines', 'other'].map(detailId => ({detailId, storageCode: detailId, label: detailId, fields: [field('customer')], indexes: []}));
  doc.relations.push(relation('customer', 'lines'));
  const own = detailDesignDocument(doc, doc.details[0]), sibling = detailDesignDocument(doc, doc.details[1]);
  assert.equal(own.relations.length, 1);
  assert.equal(own.relations[0].relationId, 'lines-customer');
  assert.deepEqual(own.relations[0].sourceField, {fieldId: 'customer'});
  assert.equal(businessReferenceField(own, own.fields[0]), true);
  assert.equal(businessReferenceField(sibling, sibling.fields[0]), false);
  assert.throws(() => applyFieldEdit(own, own.fields[0], {...fieldEdit(own.fields[0]), defaultMode: 'VALUE',
    defaultText: 'aaaaaaaa-1111-2222-3333-000000000001'}, registry, ['EDITABLE'], false, 'detail'));
});
