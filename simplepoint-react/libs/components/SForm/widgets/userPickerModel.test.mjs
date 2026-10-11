import assert from 'node:assert/strict';
import test from 'node:test';
import {userPickerConfiguration, userPickerIds, userPickerLookupBatches, userPickerOption} from './userPickerModel.ts';

test('legacy picker options retain transport names/static filters while page and search bounds match the service', () => {
  const config = userPickerConfiguration({endpoint: '/custom/users', resolveEndpoint: '/custom/selected', pageSize: 500,
    debounceMs: 400, minSearchLength: 1, pageParam: 'index', sizeParam: 'limit', searchParam: 'q', resolveParam: 'keys',
    staticParams: {tenant: 'actual', enabled: true, secretObject: {value: 'invalid'}}});
  assert.equal(config.searchEndpoint, '/custom/users'); assert.equal(config.resolveEndpoint, '/custom/selected');
  assert.equal(config.pageSize, 50); assert.equal(config.debounceMs, 400); assert.equal(config.minSearchLength, 3);
  assert.equal(config.pageParam, 'index'); assert.equal(config.sizeParam, 'limit'); assert.equal(config.searchParam, 'q');
  assert.deepEqual(config.staticParams, {enabled: 'true', tenant: 'actual'});
  const fallback = userPickerConfiguration({pageSize: NaN, debounceMs: 0});
  assert.equal(fallback.pageSize, 20); assert.equal(fallback.debounceMs, 350);
});

test('source identity includes endpoint/configuration and is stable across reordered static parameter metadata', () => {
  assert.equal(userPickerConfiguration({staticParams: {a: 1, b: 2}}).key, userPickerConfiguration({staticParams: {b: 2, a: 1}}).key);
  assert.notEqual(userPickerConfiguration({endpoint: '/users/one'}).key, userPickerConfiguration({endpoint: '/users/two'}).key);
  for (const endpoint of ['//foreign.test/users', '/\\foreign.test/users', 'https://foreign.test/users', '/bad\npath']) {
    assert.equal(userPickerConfiguration({endpoint}).searchEndpoint, '/common/users/picker/items');
  }
});

test('selected IDs preserve valid values and human labels never use a masked identity or masked name', () => {
  assert.deepEqual(userPickerIds([' a ', 0, 'a', null, false, Infinity], true), ['a', '0']);
  assert.deepEqual(userPickerIds('actual-id', false), ['actual-id']);
  assert.equal(userPickerOption({id: '***', name: 'raw name'}), undefined);
  assert.deepEqual(userPickerOption({id: 'u', name: 'Actual user', email: '***', phoneNumber: '138'}), {value: 'u', label: 'Actual user · 138'});
  assert.deepEqual(userPickerOption({id: 'u', name: '***', email: 'actual@example.test'}), {value: 'u', label: 'actual@example.test'});
});

test('large persisted selections hydrate every distinct ID in sequential request/URL-bounded batches', () => {
  const ids = Array.from({length: 521}, (_, index) => 'user-' + index);
  const batches = userPickerLookupBatches([...ids, ids[0]]);
  assert.ok(batches.every(values => values.length <= 100)); assert.deepEqual(batches.flat(), ids);
  const unicode = Array.from({length: 55}, (_, index) => index + '审核'.repeat(25));
  const encoded = userPickerLookupBatches(unicode, {tenant: 'actual'}, 'selected');
  assert.ok(encoded.every(values => new URLSearchParams({tenant: 'actual', selected: values.join(',')}).toString().length <= 2500));
  assert.deepEqual(encoded.flat(), unicode);
  assert.throws(() => userPickerLookupBatches(['id,with-comma']), /无法/);
  assert.throws(() => userPickerLookupBatches(['a'.repeat(3000)]), /有界/);
  assert.deepEqual(userPickerLookupBatches([]), []);
});
