import assert from 'node:assert/strict';
import {test} from 'node:test';
import {projectedFormSchema} from './formSchema.ts';

test('context-owned fields removed from the form do not leave impossible required inputs or mutate the server schema', () => {
  const original = {properties: {parent: {type: 'string'}, name: {type: 'string'}}, required: ['parent', 'name']};
  const transformed = {...original, properties: {name: original.properties.name}};
  assert.deepEqual(projectedFormSchema(original, transformed).required, ['name']);
  assert.deepEqual(original.required, ['parent', 'name']);
  assert.deepEqual(transformed.required, ['parent', 'name']);
  assert.equal(projectedFormSchema(original, original), original);
});
