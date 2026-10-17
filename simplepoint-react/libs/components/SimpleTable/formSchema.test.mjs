import assert from 'node:assert/strict';
import {test} from 'node:test';
import validator from '@rjsf/validator-ajv8';
import {projectedFormData, projectedFormSchema} from './formSchema.ts';

test('context-owned fields removed from the form do not leave impossible required inputs or mutate the server schema', () => {
  const original = {properties: {parent: {type: 'string'}, name: {type: 'string'}}, required: ['parent', 'name']};
  const transformed = {...original, properties: {name: original.properties.name}};
  assert.deepEqual(projectedFormSchema(original, transformed).required, ['name']);
  assert.deepEqual(original.required, ['parent', 'name']);
  assert.deepEqual(transformed.required, ['parent', 'name']);
  assert.equal(projectedFormSchema(original, original), original);
});


test('editing projects removed record metadata before strict schema validation while preserving stored null', () => {
  const original = {type: 'object', additionalProperties: false, properties: {
    id: {type: 'string'}, version: {type: 'integer'}, recordState: {type: 'string'},
    notes: {type: 'string'}, payload: {type: ['string', 'null'], default: '{}'},
  }};
  const transformed = {...original, properties: {notes: original.properties.notes, payload: original.properties.payload}};
  const row = {id: 'a', version: 2, recordState: 'DRAFT', notes: 'edited', payload: null};
  const input = projectedFormData(original, transformed, row);
  assert.deepEqual(input, {notes: 'edited', payload: null});
  assert.equal(validator.validateFormData(input, transformed).errors.length, 0);
  assert.equal(row.id, 'a');
  assert.equal(row.version, 2, 'immutable record identity still owns the optimistic write');
  assert.equal(input.payload, null, 'stored null must not apply the schema default');
  assert.equal(projectedFormData(original, transformed, input), input, 'stable values do not reset the editor');
});

test('projection does not silently discard undeclared input or alter untransformed resource forms', () => {
  const original = {type: 'object', additionalProperties: false, properties: {id: {type: 'string'}, name: {type: 'string'}}};
  const transformed = {...original, properties: {name: original.properties.name}};
  const row = {id: 'a', name: 'name', unexpected: true};
  assert.equal(projectedFormData(original, original, row), row);
  const input = projectedFormData(original, transformed, row);
  assert.equal(input.unexpected, true);
  assert(validator.validateFormData(input, transformed).errors.some(error => error.name === 'additionalProperties'));
});


test('moving a field into a conditional schema preserves the saved draft without reviving removed metadata', () => {
  const original = {properties: {id: {}, type: {}, departments: {type: 'array'}}};
  const transformed = {properties: {type: {}}, dependencies: {type: {oneOf: [
    {properties: {type: {const: 'CUSTOM'}, departments: {type: 'array'}}},
    {properties: {type: {const: 'ALL'}}},
  ]}}};
  const row = {id: 'policy', type: 'CUSTOM', departments: ['dept-1', 'dept-2']};
  assert.deepEqual(projectedFormData(original, transformed, row), {type: 'CUSTOM', departments: row.departments});
  assert.deepEqual(row.departments, ['dept-1', 'dept-2']);
  assert.deepEqual(projectedFormData(original, {properties: {type: {}, child: {properties: {departments: {}}}}}, row), {type: 'CUSTOM'});
});

test('local schema references can declare conditional fields without looping on cyclic references', () => {
  const original = {properties: {name: {}, payload: {}, removed: {}}};
  const transformed = {properties: {name: {}}, allOf: [{$ref: '#/$defs/branch'}], $defs: {
    branch: {properties: {payload: {}}, allOf: [{$ref: '#/$defs/branch'}]},
  }};
  assert.deepEqual(projectedFormData(original, transformed, {name: 'A', payload: null, removed: 1}), {name: 'A', payload: null});
});
