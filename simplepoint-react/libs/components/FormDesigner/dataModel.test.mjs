import assert from 'node:assert/strict';
import test from 'node:test';
import {newDataField} from './model.ts';
import {allowedFieldTypes, applyFieldEdit, applyIndexEdit, changeFieldType, fieldEdit, newIndex, removeIndex} from './dataModel.ts';

const component = (type, types = [], container = false) => ({type, version: '1', container, fieldRequired: types.length > 0,
  supportedFieldTypes: types, allowedParentTypes: [], allowedChildTypes: [], propertySchema: {}});
const components = [component('layout.form', [], true), component('field.number', ['INT32', 'INT64', 'DECIMAL']),
  component('field.decimal-only', ['DECIMAL']), component('field.text', ['STRING'])];
const root = (id = 'root') => ({componentId: id, componentType: 'layout.form', componentVersion: '1', columnSpan: 24,
  children: [], properties: {}, readOnly: false});
const empty = () => ({contractVersion: '1', fields: [], indexes: [], rules: [], scenes: [{sceneId: 'main', type: 'MAIN', code: 'main', name: 'Main', root: root()}]});
const source = (type = 'STRING') => {const document = empty(); document.fields.push(newDataField(document, type, '名称')); return document;};
const apply = (document, changes, base = document.fields[0]) => applyFieldEdit(document, base,
  {...fieldEdit(base), ...changes}, components, ['EDITABLE', 'CREATE_ONLY', 'UNTIL_SUBMITTED']);
const fails = (action, code) => assert.throws(action, error => error.code === code);

test('editing changes only the chosen field and preserves immutable storage identities', () => {
  const document = source(), base = structuredClone(document.fields[0]);
  const latest = {...document, scenes: [{...document.scenes[0], name: 'Changed independently'}]};
  const next = apply(latest, {label: '表示名', length: 80, writePolicy: 'CREATE_ONLY'}, base);
  assert.equal(next.fields[0].fieldId, base.fieldId);
  assert.equal(next.fields[0].storageCode, base.storageCode);
  assert.equal(next.fields[0].storageLabel, base.storageLabel);
  assert.equal(next.fields[0].writePolicy, 'CREATE_ONLY');
  assert.equal(next.scenes[0].name, 'Changed independently');
  assert.equal(document.fields[0].label, '名称');
  fails(() => apply(next, {label: 'Stale overwrite'}, base), 'conflict');
  fails(() => applyFieldEdit(document, base, {...fieldEdit(base), writePolicy: 'CREATE_ONLY'}, components, []), 'constraints');
});

test('type selection intersects every scene binding and protects existing rule literals', () => {
  const document = source('DECIMAL'), field = document.fields[0];
  document.scenes[0].root.children.push({...root('input'), componentType: 'field.number', fieldId: field.fieldId});
  assert.deepEqual(allowedFieldTypes(document, field, components), ['INT32', 'INT64', 'DECIMAL']);
  const next = apply(document, {...changeFieldType(fieldEdit(field), 'INT64'), defaultMode: 'VALUE', defaultText: '9223372036854775807'});
  assert.equal(next.fields[0].precision, null);
  assert.deepEqual(next.fields[0].defaultValue, {kind: 'INTEGER', value: '9223372036854775807'});
  document.scenes.push({sceneId: 'dialog', code: 'dialog', name: 'Details', type: 'DIALOG', root: {...root('dialog-root'),
    children: [{...root('decimal'), componentType: 'field.decimal-only', fieldId: field.fieldId}]}});
  assert.deepEqual(allowedFieldTypes(document, field, components), ['DECIMAL']);
  fails(() => apply(document, {type: 'INT64'}), 'fieldType');
  document.scenes.pop();
  document.rules.push({ruleId: 'condition', targetComponentId: 'input', effect: 'REQUIRE',
    condition: {operator: 'AND', values: [], children: [{operator: 'IS_NOT_EMPTY', fieldId: field.fieldId, values: [], children: []}]}});
  assert.deepEqual(allowedFieldTypes(document, field, components), ['DECIMAL']);
});

test('exact defaults honor precision, scale, integer bounds and null semantics', () => {
  const number = source('DECIMAL');
  const changes = {precision: 38, scale: 18, defaultMode: 'VALUE', defaultText: '12345678901234567890.123456789012345678'};
  assert.equal(apply(number, changes).fields[0].defaultValue.value, changes.defaultText);
  fails(() => apply(number, {...changes, precision: 37}), 'defaultValue');
  fails(() => apply(number, {...changes, scale: 17}), 'defaultValue');
  fails(() => apply(source('INT64'), {defaultMode: 'VALUE', defaultText: '9223372036854775808'}), 'defaultValue');
  assert.equal(apply(source('BOOLEAN'), {defaultMode: 'VALUE', defaultText: 'false'}).fields[0].defaultValue.value, 'false');
  fails(() => apply(number, {storageRequired: true, defaultMode: 'NULL'}), 'defaultValue');
  assert.deepEqual(apply(number, {storageRequired: false, defaultMode: 'NULL'}).fields[0].defaultValue, {kind: 'NULL'});
  assert.equal(apply(number, {defaultMode: 'NONE'}).fields[0].defaultValue, null);
  fails(() => apply(source(), {length: 2, defaultMode: 'VALUE', defaultText: '三个字'}), 'defaultValue');
  const json = '{"id":9223372036854775807,"amount":1.234567890123456789}';
  assert.equal(apply(source('JSON'), {defaultMode: 'VALUE', defaultText: json}).fields[0].defaultValue.value, json);
  fails(() => apply(source('JSON'), {defaultMode: 'VALUE', defaultText: '{bad}'}), 'defaultValue');
});

test('choices and defaults use semantic equality without silently dropping incompatible values', () => {
  fails(() => apply(source('DECIMAL'), {choices: [{value: '1.0', label: 'A'}, {value: '1', label: 'B'}]}), 'choices');
  fails(() => apply(source('TIME'), {choices: [{value: '12:00', label: 'A'}, {value: '12:00:00', label: 'B'}]}), 'choices');
  fails(() => apply(source('DATETIME'), {choices: [{value: '2026-10-13T12:00:00Z', label: 'A'}, {value: '2026-10-13T20:00:00+08:00', label: 'B'}]}), 'choices');
  fails(() => apply(source('DATE'), {defaultMode: 'VALUE', defaultText: '2026-02-29'}), 'defaultValue');
  const next = apply(source('DECIMAL'), {choices: [{value: '1.00', label: 'One'}], defaultMode: 'VALUE', defaultText: '1.0'});
  assert.equal(next.fields[0].defaultValue.value, '1');
  fails(() => apply(next, {defaultText: '2'}), 'defaultValue');
  const edit = changeFieldType({...fieldEdit(next.fields[0]), defaultText: '1.25'}, 'INT64');
  assert.equal(edit.defaultText, '1.25');
  assert.equal(edit.choices.length, 1);
  fails(() => apply(next, edit), 'defaultValue');
});

test('new data fields keep identities on commit and respect the server field budget', () => {
  const document = empty(), field = newDataField(document, 'STRING', 'New');
  const next = applyFieldEdit(document, field, {...fieldEdit(field), label: 'New business field'}, components, [], true);
  assert.equal(next.fields[0].storageLabel, 'New business field');
  assert.equal(next.fields[0].fieldId, field.fieldId);
  assert.equal(newDataField(next, 'STRING', 'Other').storageCode, 'field_2');
  fails(() => applyFieldEdit(next, field, fieldEdit(field), components, [], true), 'conflict');
  const full = {...document, fields: Array.from({length: 300}, (_, i) => ({...field, fieldId: `field-${i}`, storageCode: `f_${i}`}))};
  fails(() => applyFieldEdit(full, field, fieldEdit(field), components, [], true), 'limit');
});

test('composite indexes preserve ordered field identities and reject invalid or stale changes', () => {
  const document = source(), second = newDataField(document, 'STRING', 'Second'); document.fields.push(second);
  const base = newIndex(), ids = document.fields.map(field => field.fieldId);
  const next = applyIndexEdit(document, base, {unique: true, fieldIds: ids}, true);
  assert.equal(document.indexes.length, 0);
  const reversed = applyIndexEdit(next, next.indexes[0], {unique: false, fieldIds: [...ids].reverse()});
  assert.deepEqual(reversed.indexes[0].fieldIds, [...ids].reverse());
  assert.equal(reversed.indexes[0].storageCode, base.storageCode);
  fails(() => removeIndex(reversed, next.indexes[0]), 'conflict');
  assert.equal(removeIndex(reversed, reversed.indexes[0]).indexes.length, 0);
  for (const fields of [[], ['missing'], [ids[0], ids[0]]]) fails(() => applyIndexEdit(document, base, {unique: false, fieldIds: fields}, true), 'indexFields');
  document.fields[1].storageMode = 'TRANSIENT';
  fails(() => applyIndexEdit(document, base, {unique: false, fieldIds: ids}, true), 'indexFields');
  const full = {...document, indexes: Array.from({length: 50}, () => newIndex())};
  fails(() => applyIndexEdit(full, base, {unique: false, fieldIds: [ids[0]]}, true), 'limit');
});


test('text defaults use the same Unicode length and pattern rules as submitted data', () => {
  const document = source();
  const rules = {minLength: 2, maxLength: 3, pattern: '^中.+$', patternMessage: '必须以中开头'};
  assert.equal(apply(document, {validation: rules, defaultMode: 'VALUE', defaultText: '中😀'}).fields[0].defaultValue.value, '中😀');
  fails(() => apply(document, {validation: rules, defaultMode: 'VALUE', defaultText: '中'}), 'defaultValue');
  fails(() => apply(document, {validation: rules, defaultMode: 'VALUE', defaultText: '日本語'}), 'defaultValue');
  fails(() => apply(document, {validation: rules, defaultMode: 'VALUE', defaultText: '中文日本語'}), 'defaultValue');
  fails(() => apply(document, {validation: {...rules, pattern: '(?=x)x'}}), 'constraints');
});


test('detail field edits validate their data model independently of form scenes', () => {
  const document = {...source(), scenes: []}, base = document.fields[0];
  const edit = {...fieldEdit(base), validation: {minLength: 2, maxLength: 12, pattern: '^[^0-9]+$', patternMessage: '不能包含数字'}};
  const next = applyFieldEdit(document, base, edit, components, [], false, 'detail');
  assert.deepEqual(next.fields[0].validation, edit.validation);
  assert.deepEqual(next.scenes, []);
  assert.throws(() => applyFieldEdit(document, base, edit, components, []), /limit/);
  const full = {...document, fields: Array.from({length: 48}, (_, i) => ({...base, fieldId: `detail-${i}`, storageCode: `d_${i}`}))};
  fails(() => applyFieldEdit(full, base, fieldEdit(base), components, [], true, 'detail'), 'limit');
});
