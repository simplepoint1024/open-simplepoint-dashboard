import assert from 'node:assert/strict';
import {test} from 'node:test';
import {requiredValueIssues} from './requiredValues.ts';
const document = {fields: [
  {fieldId: 'name', label: '姓名', type: 'STRING'},
  {fieldId: 'files', label: '附件', type: 'JSON'},
  {fieldId: 'accepted', label: '开关', type: 'BOOLEAN'},
  {fieldId: 'amount', label: '金额', type: 'INT32'},
], details: [{detailId: 'items', fields: [{fieldId: 'memo', label: '内容', type: 'STRING'}]}]};
const schema = {required: ['name', 'files', 'accepted', 'amount'], properties: {items: {items: {properties: {values: {required: ['memo']}}}}}};
test('required values reject whitespace and serialized empty JSON while retaining false and zero', () => {
  assert.deepEqual(requiredValueIssues(document, schema, {name: '  \n', files: '[]', accepted: false, amount: 0})
    .map(issue => issue.path), [['name'], ['files']]);
  assert.deepEqual(requiredValueIssues(document, schema, {name: '张三', files: '[{"id":"file"}]', accepted: false, amount: 0}), []);
});
test('detail failures target the owning aggregate field paths', () => {
  const data = {name: 'A', files: '[1]', accepted: true, amount: 1, items: [{values: {memo: '  '}}, {values: {memo: 'ok'}}]};
  assert.deepEqual(requiredValueIssues(document, schema, data), [{path: ['items', 0, 'values', 'memo'], label: '内容'}]);
});
test('draft/readonly schemas with no required fields do not invent submission constraints', () => {
  assert.deepEqual(requiredValueIssues(document, {properties: {}}, {}), []);
});

test('new create-only detail fields remain required when added while editing an existing draft', () => {
  const doc = structuredClone(document);
  Object.assign(doc.details[0].fields[0], {submitRequired: true, writePolicy: 'CREATE_ONLY'});
  const draftSchema = {properties: {items: {items: {properties: {values: {required: []}}}}}};
  assert.deepEqual(requiredValueIssues(doc, draftSchema, {items: [{values: {memo: ' '}}]}),
    [{path: ['items', 0, 'values', 'memo'], label: '内容'}]);
  assert.deepEqual(requiredValueIssues(doc, draftSchema, {items: [{id: 'existing', values: {memo: ' '}}]}), []);
});
