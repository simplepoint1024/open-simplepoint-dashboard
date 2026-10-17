import assert from 'node:assert/strict';
import test from 'node:test';
import {resizeMatrix, validateMatrix} from '../FormComponents/Matrix/model.ts';
import {insertComponent, copyComponent, pasteComponent, removeComponent, moveComponent} from './model.ts';
import {runtimeFormModel} from '../FormRuntime/model.ts';
import {recordEditorUiSchema} from '../FormRuntime/recordEditorUiSchema.ts';
import {detailRowSchema} from '../FormComponents/Detail/schema.ts';
import {detailPayload} from '../FormComponents/Detail/payload.ts';
let counter = 0;
const id = () => `id-${++counter}`;
const node = type => ({componentId: id(), componentType: type, componentVersion: '1', columnSpan: 24, readOnly: false, properties: {}, children: []});
const descriptor = (type, container = false, propertySchema = {}, allowedParentTypes = [], allowedChildTypes = []) =>
  ({type, version: '1', container, propertySchema, allowedParentTypes, allowedChildTypes, fieldRequired: false, supportedFieldTypes: []});
const registry = [descriptor('layout.form', true), descriptor('layout.matrix', true, {
  rows: {defaultValue: {kind: 'INTEGER', value: '2'}}, columns: {defaultValue: {kind: 'INTEGER', value: '2'}},
}, [], ['layout.cell']), descriptor('layout.cell', true, {}, ['layout.matrix']), descriptor('data.detail')];
const document = () => ({contractVersion: '1', fields: [], indexes: [], rules: [], scenes: [{sceneId: 'main', code: 'main', name: 'Main', type: 'MAIN', root: node('layout.form')}]});

test('matrix resize preserves coordinates and refuses loss of occupied cells', () => {
  const matrix = resizeMatrix(node('layout.matrix'), 2, 3, id);
  matrix.children[4].children.push(node('layout.form'));
  const expanded = resizeMatrix(matrix, 3, 4, id);
  assert.equal(expanded.children[5].componentId, matrix.children[4].componentId);
  assert.deepEqual(expanded.children[5].children, matrix.children[4].children);
  assert.throws(() => resizeMatrix(matrix, 1, 3, id), /occupied/);
  assert.throws(() => resizeMatrix(matrix, 7, 3, id), /size/);
  assert.throws(() => validateMatrix({...matrix, children: matrix.children.slice(1)}), /cells/);
  assert.equal(matrix.children.length, 6);
});
test('matrix owns structural cells and copy creates fresh cell identities', () => {
  const original = document(), root = original.scenes[0].root.componentId;
  const added = insertComponent(original, root, 0, registry[1], 'Grid', registry, undefined, id);
  const matrix = added.document.scenes[0].root.children[0];
  assert.throws(() => removeComponent(added.document, matrix.children[0].componentId, registry), /parent/);
  assert.throws(() => moveComponent(added.document, matrix.children[0].componentId, root, 0, registry), /parent/);
  const copied = pasteComponent(added.document, root, copyComponent(added.document, matrix.componentId), registry, id);
  assert.notEqual(copied.document.scenes[0].root.children[1].children[0].componentId, matrix.children[0].componentId);
  assert.equal(runtimeFormModel(copied.document, 'main').layout[0].columns, 2);
});
test('detail data is independent from canvas lifetime and copies to a new child definition', () => {
  const original = document(), root = original.scenes[0].root.componentId;
  const added = insertComponent(original, root, 0, registry[3], 'Lines', registry, undefined, id);
  assert.equal(added.document.details.length, 1);
  const copied = pasteComponent(added.document, root, copyComponent(added.document, added.componentId), registry, id);
  assert.equal(copied.document.details.length, 2);
  assert.notEqual(copied.document.details[0].detailId, copied.document.details[1].detailId);
  assert.notEqual(copied.document.details[0].fields[0].fieldId, copied.document.details[1].fields[0].fieldId);
  assert.equal(removeComponent(copied.document, added.componentId, registry).details.length, 2);
  const compiled = runtimeFormModel(copied.document, 'main');
  assert.equal(compiled.schema.properties[copied.document.details[0].detailId].type, 'array');
  assert.equal(compiled.uiSchema[copied.document.details[0].detailId]['ui:field'], 'DetailRows');
});
test('detail payload keeps concurrency identity and removes protected values', () => {
  const doc = {details: [{detailId: 'lines', fields: [{fieldId: 'name'}, {fieldId: 'locked', writePolicy: 'CREATE_ONLY'}, {fieldId: 'secret'}], readOnlyFieldIds: ['secret']}]};
  assert.deepEqual(detailPayload(doc, {lines: [{id: 'r', version: 2, values: {name: 'A', locked: 'X', secret: 'S'}}]}, 'DRAFT').lines,
    [{id: 'r', version: 2, values: {name: 'A'}}]);
  assert.deepEqual(detailPayload(doc, {lines: undefined}), {lines: []});
});

test('detail editor retains persisted write policy after projected form values change', () => {
  const original = document(), root = original.scenes[0].root.componentId;
  const added = insertComponent(original, root, 0, registry[3], 'Lines', registry, undefined, id);
  const detail = added.document.details[0];
  detail.fields[0].writePolicy = 'UNTIL_SUBMITTED';
  const descriptor = {document: added.document, sceneId: 'main', capabilities: {canEdit: true, canCreate: true}};
  const current = {id: 'parent', version: 2, recordState: 'SUBMITTED'};
  for (const formData of [{}, {recordState: 'DRAFT', [detail.detailId]: []}]) {
    const ui = recordEditorUiSchema(descriptor, formData, {properties: {[detail.detailId]: {}}}, current);
    const options = ui[detail.detailId]['ui:options'];
    assert.equal(options.recordState, 'SUBMITTED');
    assert.equal(detailRowSchema(options.detail, options.recordState).properties[detail.fields[0].fieldId].readOnly, true);
  }
  // Definitions outside the main scene still use the registered aggregate editor.
  added.document.scenes[0].root.children = [];
  assert.equal(recordEditorUiSchema(descriptor, {}, {properties: {[detail.detailId]: {}}}, current)
    [detail.detailId]['ui:field'], 'DetailRows');
});
