import assert from 'node:assert/strict';
import {test} from 'node:test';
import {defaultCrudData} from './defaultCrudData.ts';

test('default CRUD omits protected typed masks and readonly ACL values while preserving exact price text and captured IDs', () => {
  const schema = {properties: {price: {'x-field-access': 'MASKED'}, count: {'x-field-access': 'VISIBLE'}, description: {}, enabled: {}, otherPrice: {}}};
  assert.deepEqual(defaultCrudData(schema, {id: 'spoofed', price: '***', count: 42, description: 'Actual', enabled: false,
    otherPrice: '99999999999.99999999'}, {id: 'literal/package?'}), {id: 'literal/package?', description: 'Actual', enabled: false, otherPrice: '99999999999.99999999'});
});
test('display-only contextual parent bindings and explicit null remain available to the genuine beforeSubmit contract', () => {
  assert.deepEqual(defaultCrudData({properties: {dictionaryCode: {readOnly: true}, description: {}}},
    {dictionaryCode: 'captured-parent', description: null}), {dictionaryCode: 'captured-parent', description: null});
});
