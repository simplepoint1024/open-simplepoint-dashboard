import {timeClock, changedTime} from '../FormComponents/Standard/timeValue.ts';
import test from 'node:test';
import assert from 'node:assert/strict';
import {insertComponent} from './model.ts';
import {orderedListFields} from './formPresentation.ts';
import {fieldWriteAllowed, fieldOperationHidden} from '../FormRuntime/writePolicy.ts';
import {runtimeFormModel} from '../FormRuntime/model.ts';
import {safeDisplayUrl, selectedKeys} from '../FormComponents/Standard/model.ts';
const field = {fieldId: 'a', label: '甲', type: 'STRING', storageMode: 'STORED', choices: [], submitRequired: true};
const component = {componentId: 'a', componentType: 'field.text', componentVersion: '1', fieldId: 'a', columnSpan: 24, readOnly: false, properties: {}, children: []};
const document = access => ({contractVersion: '1', fields: [{...field, access}], rules: [], scenes: [{sceneId: 'main', name: '主表', type: 'MAIN', root: {...component, componentId: 'root', componentType: 'layout.form', fieldId: null, children: [component]}}]});
test('list field defaults preserve canvas, filter transient fields and append new fields', () => {
  const doc = {...document(), fields: [field, {...field, fieldId: 'b'}, {...field, fieldId: 'c'}, {...field, fieldId: 't', storageMode: 'TRANSIENT'}], presentation: {listFieldOrder: ['b', 'a']}};
  const before = structuredClone(doc);
  assert.deepEqual(orderedListFields(doc).map(f => f.fieldId), ['b', 'a', 'c']);
  assert.deepEqual(doc, before);
});
test('operation policy intersects state rules and readonly, hidden edit never becomes editable', () => {
  const access = {create: 'WRITE', edit: 'HIDDEN'};
  assert.equal(fieldWriteAllowed({...field, access}), true);
  assert.equal(fieldWriteAllowed({...field, access}, 'DRAFT'), false);
  assert.equal(fieldOperationHidden({...field, access}, 'DRAFT'), true);
  assert.equal(fieldWriteAllowed({...field, access: {...access, readOnly: true}}), false);
  const edit = runtimeFormModel(document(access), 'main', {}, {recordState: 'DRAFT'});
  assert.equal(edit.layout[0].hidden, true);
  assert.deepEqual(edit.editableFieldIds, []);
  assert.equal(runtimeFormModel(document(access), 'main', {}, {recordState: 'DRAFT', readOnly: true}).layout[0].hidden, false);
});
test('display URL policy rejects executable and ambiguous sources', () => {
  for (const url of ['javascript:alert(1)', 'data:image/svg+xml,a', '//evil.test/a', '/\\evil.test', 'https://user:pass@example.com/x', 'https://example.com/\nx']) assert.equal(safeDisplayUrl(url), '');
  assert.equal(safeDisplayUrl('/assets/a.png'), '/assets/a.png');
  assert.equal(safeDisplayUrl('https://example.com/a.png'), 'https://example.com/a.png');
  assert.deepEqual(selectedKeys('["日语","日本語"]'), ['日语', '日本語']);
  assert.deepEqual(selectedKeys('{"value":1}'), []);
});

test('required create fields that lock on edit cannot be saved as incomplete drafts', () => {
  const model = runtimeFormModel(document({create: 'WRITE', edit: 'READ'}), 'main', {}, {enforceRequired: false});
  assert.deepEqual(model.schema.required, ['a']);
});

test('reusing a field in another scene retains its configured component domain', () => {
  const doc = document();
  doc.fields[0].type = 'INT32';
  doc.scenes[0].root.children[0].componentType = 'field.rating';
  doc.scenes[0].root.children[0].properties = {count: {kind: 'INTEGER', value: '8'}};
  doc.scenes.push({sceneId: 'dialog', name: 'Dialog', type: 'DIALOG', root: {...doc.scenes[0].root, componentId: 'dialog-root', children: []}});
  const container = {type: 'layout.form', version: '1', container: true, fieldRequired: false, supportedFieldTypes: [], propertySchema: {}, allowedParentTypes: [], allowedChildTypes: []};
  const rating = {...container, type: 'field.rating', container: false, fieldRequired: true, supportedFieldTypes: ['INT32'], propertySchema: {count: {defaultValue: {kind: 'INTEGER', value: '5'}}}};
  const added = insertComponent(doc, 'dialog-root', 0, rating, 'Rating', [container, rating], 'a', () => 'reused');
  assert.equal(added.document.scenes[1].root.children[0].properties.count.value, '8');
});

test('time picker preserves existing microseconds when the clock is unchanged', () => {
  assert.equal(timeClock('09:30:15.123456'), '09:30:15');
  assert.equal(changedTime('09:30:15.123456', '09:30:15'), '09:30:15.123456');
  assert.equal(changedTime('09:30:15.123456', '10:30:15'), '10:30:15');
  assert.equal(changedTime('09:30:15.123456', undefined), undefined);
});
