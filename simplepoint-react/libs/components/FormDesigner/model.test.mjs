import assert from 'node:assert/strict';
import test from 'node:test';
import {addScene, changeNode, copyComponent, insertComponent, locate, moveComponent, pasteComponent,
  recordHistory, redo, removeComponent, removeScene, undo, validateStructure} from './model.ts';
import {sameDesignDocument} from './draftState.ts';

const component = (type, container = false, supportedFieldTypes = []) => ({type, version: '1', container,
  fieldRequired: supportedFieldTypes.length > 0, supportedFieldTypes, allowedParentTypes: [], allowedChildTypes: [], propertySchema: {}});
const registry = [component('layout.form', true), component('layout.group', true), component('field.text', false, ['STRING']),
  component('field.number', false, ['DECIMAL']), component('action.open-dialog'), component('action.open-drawer')];
const root = () => ({componentId: 'root', componentType: 'layout.form', componentVersion: '1', columnSpan: 24,
  readOnly: false, properties: {}, children: []});
const empty = () => ({contractVersion: '1', fields: [], indexes: [], rules: [],
  scenes: [{sceneId: 'main', code: 'main', name: 'Main', type: 'MAIN', root: root()}]});
const add = (document, type = 'field.text', parent = 'root', fieldId) => insertComponent(document, parent,
  locate(document, parent).node.children.length, registry.find(item => item.type === type), type, registry, fieldId);

test('field controls have independent stable identities and removal never drops storage definitions', () => {
  const source = empty(), created = add(source), field = created.document.fields[0];
  assert.equal(source.fields.length, 0);
  assert.notEqual(field.fieldId, created.componentId);
  assert.equal(field.storageCode, 'field_1');
  created.document.indexes.push({indexId: 'by-name', storageCode: 'by_name', unique: false, fieldIds: [field.fieldId]});
  const removed = removeComponent(created.document, created.componentId, registry);
  assert.deepEqual(removed.fields, created.document.fields);
  assert.deepEqual(removed.indexes, created.document.indexes);
  assert.equal(removed.scenes[0].root.children.length, 0);
  assert.equal(add(removed, 'field.text', 'root', field.fieldId).document.fields.length, 1);
});

test('fields can be reused across scenes and cannot occur twice in the same scene', () => {
  const first = add(empty());
  assert.throws(() => add(first.document, 'field.text', 'root', first.document.fields[0].fieldId), /field/);
  const dialog = addScene(first.document, 'DIALOG', 'Details', registry);
  const reused = add(dialog.document, 'field.text', dialog.document.scenes[1].root.componentId, first.document.fields[0].fieldId);
  assert.equal(reused.document.fields.length, 1);
  assert.equal(reused.document.scenes[1].root.children[0].fieldId, first.document.fields[0].fieldId);
});

test('scene actions create valid targets and navigation cycles are rejected before a mutation commits', () => {
  const open = add(empty(), 'action.open-dialog');
  const dialog = open.document.scenes[1];
  assert.equal(open.document.scenes[0].root.children[0].targetSceneId, dialog.sceneId);
  assert.throws(() => removeScene(open.document, dialog.sceneId, registry), /referenced/);
  const nested = add(open.document, 'action.open-dialog', dialog.root.componentId);
  const cycle = changeNode(nested.document, nested.componentId, node => {node.targetSceneId = dialog.sceneId;});
  assert.throws(() => validateStructure(cycle, registry), /cycle/);
  const removed = removeComponent(open.document, open.componentId, registry);
  assert.equal(removeScene(removed, dialog.sceneId, registry).scenes.length, 1);
});

test('moves use insertion slots consistently and reject self nesting', () => {
  const group = add(empty(), 'layout.group');
  const first = add(group.document), second = add(first.document);
  const reordered = moveComponent(second.document, first.componentId, 'root', 3, registry);
  assert.deepEqual(reordered.scenes[0].root.children.map(node => node.componentId),
    [group.componentId, second.componentId, first.componentId]);
  const nested = moveComponent(reordered, second.componentId, group.componentId, 0, registry);
  assert.equal(locate(nested, second.componentId).parent.componentId, group.componentId);
  assert.throws(() => moveComponent(nested, group.componentId, group.componentId, 0, registry), /cycle/);
  assert.throws(() => moveComponent(nested, 'root', group.componentId, 0, registry), /parent/);
});

test('copy remaps component, field and rule identities without mutating the source document', () => {
  const original = add(empty());
  original.document.rules.push({ruleId: 'required', targetComponentId: original.componentId, effect: 'REQUIRE',
    condition: {operator: 'IS_NOT_EMPTY', fieldId: original.document.fields[0].fieldId, values: [], children: []}});
  const clipboard = copyComponent(original.document, original.componentId);
  const pasted = pasteComponent(original.document, 'root', clipboard, registry);
  assert.equal(original.document.fields.length, 1);
  assert.equal(pasted.document.fields.length, 2);
  const copiedField = pasted.document.fields.find(field => field.fieldId !== original.document.fields[0].fieldId);
  assert.equal(copiedField.storageCode, 'field_2');
  assert.equal(pasted.document.fields[0].fieldId, copiedField.fieldId, 'new data definitions appear first');
  assert.equal(pasted.document.rules[1].targetComponentId, pasted.componentId);
  assert.equal(pasted.document.rules[1].condition.fieldId, copiedField.fieldId);
  assert.notEqual(pasted.document.rules[0].ruleId, pasted.document.rules[1].ruleId);
});

test('component versions and nesting capabilities are respected by every insertion path', () => {
  assert.throws(() => insertComponent(empty(), 'root', 0, {...registry[2], version: '2'}, 'Unknown', registry), /unsupported/);
  const restricted = registry.map(item => item.type === 'field.text' ? {...item, allowedParentTypes: ['layout.group']} : item);
  assert.throws(() => insertComponent(empty(), 'root', 0, restricted[2], 'Text', restricted), /parent/);
});

test('undo and redo preserve identities and clear the future after a new edit', () => {
  const initial = empty(), first = add(initial).document, second = add(first).document;
  let history = recordHistory({past: [], present: initial, future: []}, first);
  history = recordHistory(history, second);
  assert.deepEqual(undo(history).present, first);
  assert.deepEqual(redo(undo(history)).present, second);
  assert.equal(recordHistory(undo(history), add(first, 'field.number').document).future.length, 0);
  assert.equal(recordHistory(history, structuredClone(second)), history);
  const equivalent = structuredClone(second);
  equivalent.scenes[0].root.description = null;
  assert.equal(recordHistory(history, equivalent), history);
});

test('lost-save comparison ignores field order and canonical numeric spelling but preserves UI order', () => {
  const first = add(empty(), 'field.number'), second = add(first.document);
  second.document.fields[0].defaultValue = {kind: 'DECIMAL', value: '1.200'};
  const server = structuredClone(second.document);
  server.fields[0].defaultValue.value = '1.2'; server.fields.reverse();
  assert.equal(sameDesignDocument(server, second.document), true);
  server.scenes[0].root.children.reverse();
  assert.equal(sameDesignDocument(server, second.document), false);
});
