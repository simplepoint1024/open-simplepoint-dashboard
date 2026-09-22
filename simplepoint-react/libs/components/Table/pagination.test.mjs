import {test} from 'node:test';
import assert from 'node:assert/strict';
import {pageSizeOptions} from './pagination.ts';

test('remote page choices respect the declared budget, including small page limits', () => {
  assert.deepEqual(pageSizeOptions(50), [10, 20, 50]);
  assert.deepEqual(pageSizeOptions(25), [10, 20]);
  assert.deepEqual(pageSizeOptions(5), [5]);
  assert.deepEqual(pageSizeOptions(50, ['20', 20, 100, '10', 0, -1, 'invalid', 1.5]), [10, 20]);
});

test('local tables retain configured page sizes and invalid limits cannot break pagination', () => {
  const options = [10, 20, 100, 200];
  assert.deepEqual(pageSizeOptions(undefined, options), options);
  for (const max of [NaN, Infinity, -1, 0, 1.5]) assert.deepEqual(pageSizeOptions(max, options), options);
  assert.deepEqual(options, [10, 20, 100, 200]);
});
