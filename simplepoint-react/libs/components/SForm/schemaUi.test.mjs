import assert from 'node:assert/strict';
import {test} from 'node:test';
import {schemaUi, mergeUiSchema} from './schemaUi.ts';
import {displayOnlyProjection} from './displayOnly.ts';

test('display projection cannot erase automatic directory widgets, including nested starter inputs', () => {
  const person = {type: 'string', 'x-ui': {widget: 'UserSelect'}};
  const source = {type: 'object', properties: {
    approver: person, variables: {type: 'object', properties: {reviewer: person,
      roles: {type: 'array', items: {type: 'string'}, 'x-ui': {widget: 'RoleSelect', options: {multiple: true}}}}},
    masked: {...person, 'x-display-only': true},
  }};
  const original = structuredClone(source);
  const projected = displayOnlyProjection(source, {variables: {reviewer: {'ui:disabled': true}}});
  const result = mergeUiSchema(schemaUi(projected.schema), projected.uiSchema);
  assert.deepEqual(source, original);
  assert.equal(result.approver['ui:widget'], 'UserSelect');
  assert.equal(result.variables.reviewer['ui:widget'], 'UserSelect');
  assert.equal(result.variables.reviewer['ui:disabled'], true);
  assert.deepEqual(result.variables.roles['ui:options'], {multiple: true});
  assert.equal(result.masked['ui:field'], 'DisplayValue');
  assert.equal(result.masked['ui:widget'], undefined);
});

test('explicit nested widget choices win while unrelated array-item metadata stays intact', () => {
  const schema = {type: 'array', items: {type: 'object', properties: {note: {type: 'string', 'x-ui': {widget: 'textarea'}}}}};
  const automatic = schemaUi(schema);
  const result = mergeUiSchema(automatic, {items: {note: {'ui:options': {autoSize: {maxRows: 8}}}}});
  assert.equal(result.items.note['ui:widget'], 'textarea');
  assert.deepEqual(result.items.note['ui:options'].autoSize, {minRows: 4, maxRows: 8});
  assert.equal(automatic.items.note['ui:options'].autoSize.maxRows, 16);
});
