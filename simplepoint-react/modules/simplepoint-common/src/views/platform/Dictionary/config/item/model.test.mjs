import assert from 'node:assert/strict';
import test from 'node:test';
import {dictionaryItemFormSchema, dictionaryItemSubmission} from './model.ts';

test('dictionary item validation preserves readonly fields and existing patterns without mutating the source', () => {
  const schema = {properties: {dictionaryCode: {type: ['string', 'null']}, name: {type: ['string', 'null'], readOnly: true,
    'x-field-access': 'VISIBLE', pattern: '^[a-z]+$'}, value: {type: ['string', 'null']}, description: {type: ['string', 'null']}}, required: ['description']};
  const result = dictionaryItemFormSchema(schema);
  assert.equal(result.properties.name.readOnly, true);
  assert.equal(result.properties.name['x-field-access'], 'VISIBLE');
  assert.equal(result.properties.name.pattern, '^[a-z]+$');
  assert.deepEqual(result.properties.name.allOf, [{pattern: '\\S'}]);
  assert.equal(result.properties.value.type, 'string');
  assert.deepEqual(result.required, ['description', 'dictionaryCode', 'name', 'value']);
  assert.deepEqual(schema.properties.name.type, ['string', 'null']);
  assert.equal(schema.properties.name.allOf, undefined);
  assert.deepEqual(dictionaryItemFormSchema({properties: {name: {type: 'string'}}}).required, ['name']);
});

test('the nested submission pins its dictionary and captured identity, including attempted hidden-field edits', () => {
  const draft = {id: 'injected-id', dictionaryCode: 'another-dictionary', name: 'real', value: 'x'};
  assert.deepEqual(dictionaryItemSubmission('actual-parent', 'edit', draft, {id: 'captured-id'}), {
    ...draft, id: 'captured-id', dictionaryCode: 'actual-parent',
  });
  assert.equal(dictionaryItemSubmission('actual-parent', 'add', {name: 'real'}, null).dictionaryCode, 'actual-parent');
  assert.equal(draft.dictionaryCode, 'another-dictionary');
});
