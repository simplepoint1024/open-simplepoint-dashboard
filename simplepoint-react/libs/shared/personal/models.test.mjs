import assert from 'node:assert/strict';
import {test} from 'node:test';
import {safePersonalPath} from './models.ts';

test('business links accept only same-origin router paths', () => {
  assert.equal(safePersonalPath('/my/items?item=a'), true);
  assert.equal(safePersonalPath('/workflow/task/1'), true);
  for (const path of [null, undefined, '', 'javascript:alert(1)', 'https://example.com', '//example.com', '/\\example.com', '/x\n', '/x\u007f']) {
    assert.equal(safePersonalPath(path), false, String(path));
  }
});
