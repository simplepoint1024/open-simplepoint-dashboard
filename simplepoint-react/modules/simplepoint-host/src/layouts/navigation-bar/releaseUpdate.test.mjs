import assert from 'node:assert/strict';
import test from 'node:test';
import {hostEntry} from './releaseUpdate.ts';
test('release detection ignores API/login scripts and normalizes host entry URLs', () => {
  assert.equal(hostEntry(['https://host.test/assets/js/index.0123456789.js?v=2']), '/assets/js/index.0123456789.js');
  assert.equal(hostEntry(['/assets/js/async/component.0123456789.js', '/login.js', '/mf/common.0123456789.js']), undefined);
});
