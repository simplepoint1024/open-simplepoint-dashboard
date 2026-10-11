import test from 'node:test';
import assert from 'node:assert/strict';
import {visibleKeySignature} from './visibleKeys.ts';
test('translated/reconstructed metadata has the same preference identity', () => {
  const before = {id: {title: 'ID'}, name: {title: 'Name', type: 'string'}};
  assert.equal(visibleKeySignature(before), visibleKeySignature({id: {title: '标识'}, name: {title: '名称', type: 'string'}}));
  assert.deepEqual(JSON.parse(visibleKeySignature(before)), ['id', 'name']);
});
test('actual visible fields and their order still reinitialize applicable preferences', () => {
  const before = {name: {'x-ui': {'x-list-visible': true}}, secret: {'x-ui': {'x-list-visible': false}}};
  assert.deepEqual(JSON.parse(visibleKeySignature(before)), ['name']);
  assert.notEqual(visibleKeySignature(before), visibleKeySignature({...before, secret: {'x-ui': {'x-list-visible': true}}}));
  assert.notEqual(visibleKeySignature({id: {}, name: {}}), visibleKeySignature({name: {}, id: {}}));
});
test('legacy explicit tri-state metadata remains supported and absent declarations show all fields', () => {
  const values = ['true', '1', 1, true, 'false', '0', 0, false, undefined];
  const properties = Object.fromEntries(values.map((value, index) => ['field' + index, {'x-ui': {'x-list-visible': value}}]));
  assert.deepEqual(JSON.parse(visibleKeySignature(properties)), ['field0', 'field1', 'field2', 'field3']);
  assert.equal(visibleKeySignature({actual: {}}), '["actual"]');
  assert.equal(visibleKeySignature({}), '[]');
});
