import assert from 'node:assert/strict';
import test from 'node:test';
import {reachableServerPage} from './pagination.ts';

test('a settled empty last page clamps to the actual remaining server page, including zero records', () => {
  assert.equal(reachableServerPage(27, {number: 26, totalPages: 26}), 26);
  assert.equal(reachableServerPage(2, {number: 1, totalPages: 0}), 1);
  assert.equal(reachableServerPage(1, {number: 0, totalPages: 27}), 1);
});

test('previous-page placeholders and invalid page metadata cannot redirect the current request', () => {
  assert.equal(reachableServerPage(27, {number: 0, totalPages: 1}), 27);
  assert.equal(reachableServerPage(27), 27);
  for (const totalPages of [undefined, NaN, Infinity, -1, 1.5]) assert.equal(reachableServerPage(27, {number: 26, totalPages}), 27);
});
