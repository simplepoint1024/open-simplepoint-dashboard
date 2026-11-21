import test from 'node:test';
import assert from 'node:assert/strict';
import {referenceNavigation} from './navigation.ts';

const id = 'aaaaaaaa-1111-2222-3333-444444444444';

test('bounded drawer stack falls through to an explicitly owned page navigator', () => {
  assert.equal(referenceNavigation(0, id, true, false), 'drawer');
  assert.equal(referenceNavigation(1, id, true, true), 'drawer');
  assert.equal(referenceNavigation(2, id, true, false), undefined);
  assert.equal(referenceNavigation(2, id, true, true), 'page');
});

test('invalid or unavailable reads cannot become navigation actions', () => {
  for (const depth of [-1, 0.5, NaN, Infinity]) assert.equal(referenceNavigation(depth, id, true, true), undefined);
  for (const value of ['***', null, '', '/another/path']) assert.equal(referenceNavigation(0, value, true, true), undefined);
  assert.equal(referenceNavigation(0, id, false, true), undefined);
});
