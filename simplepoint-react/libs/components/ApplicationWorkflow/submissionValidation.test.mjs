import assert from 'node:assert/strict';
import {test} from 'node:test';
import {missingSubmissionFields} from './submissionValidation.ts';
const field = {fieldId: 'name', label: '申请人', type: 'STRING', submitRequired: true, choices: [], length: 100};
const node = (id, type, fieldId, children = []) => ({componentId: id, componentType: type, componentVersion: '1',
  columnSpan: 24, fieldId, properties: {}, children});
const form = {document: {contractVersion: '1', fields: [field], rules: [], details: [], scenes: [
  {sceneId: 'main', type: 'MAIN', root: node('root', 'layout.form', null, [node('name', 'field.text', 'name')])},
  {sceneId: 'other', type: 'DIALOG', root: node('other-root', 'layout.form', null, [node('other-name', 'field.text', 'name')])},
]}};
test('readonly confirmation reports visible missing required inputs once across scenes', () => {
  assert.deepEqual(missingSubmissionFields(form, {state: 'DRAFT', values: {name: '  '}}), ['申请人']);
  assert.deepEqual(missingSubmissionFields(form, {state: 'DRAFT', values: {name: '员工'}}), []);
});
test('confirmation does not turn protected readonly values into editable requirements', () => {
  const restricted = structuredClone(form); restricted.document.fields[0].access = {readOnly: true};
  assert.deepEqual(missingSubmissionFields(restricted, {state: 'DRAFT', values: {}}), []);
});

test('confirmation does not infer persisted requirements from absent projected values', () => {
  assert.deepEqual(missingSubmissionFields(form, {state: 'DRAFT', values: {}}), []);
  assert.deepEqual(missingSubmissionFields(form, {state: 'DRAFT', values: {name: null}}), ['申请人']);
});
