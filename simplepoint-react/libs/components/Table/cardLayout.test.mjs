import assert from 'node:assert/strict';
import test from 'node:test';
import {cardGeometry, anchoredPage} from './cardLayout.ts';

test('card pages use complete rows and respect the server budget at every width', () => {
  for (const max of [1, 2, 5, 10, 20, 100]) {
    for (const width of [0, 180, 600, 1200, 10000]) {
      const {columns, pageSize} = cardGeometry(width, 3, max);
      assert.ok(pageSize >= 1 && pageSize <= max);
      assert.equal(pageSize % columns, 0);
    }
  }
  assert.deepEqual(cardGeometry(1200), {columns: 4, pageSize: 12});
});

test('view switch and resize retain the original first record within the new page', () => {
  assert.equal(anchoredPage(3, 20, 12, 100), 4);
  for (const oldSize of [3, 12, 20]) {
    for (const size of [2, 6, 15]) {
      const page = anchoredPage(4, oldSize, size, 200);
      const oldOffset = 3 * oldSize;
      assert.ok((page - 1) * size <= oldOffset && page * size > oldOffset);
    }
  }
});

test('last-page deletion and empty data produce reachable pages', () => {
  assert.equal(anchoredPage(10, 20, 12, 15), 2);
  assert.equal(anchoredPage(10, 20, 12, 0), 1);
});
