import assert from 'node:assert/strict';
import test from 'node:test';
import {assignmentKey, assignmentPresentationKeys} from './identity.ts';

test('assignment payload keys reject missing/masked identities and preserve actual literal keys', () => {
  for (const value of [undefined, null, false, {}, '', '  ', '***', ' ***** ', NaN, Infinity]) assert.equal(assignmentKey(value), undefined);
  assert.equal(assignmentKey(0), '0'); assert.equal(assignmentKey(' id:with,comma '), ' id:with,comma ');
});
test('bounded presentation keys are unique, avoid real keys and never replace the underlying protected identifier', () => {
  const rows = [{id: '***'}, {}, {id: '*****'}, {id: '__assignment_presentation__0'}, {id: 'real'}, {id: 0}];
  const keys = assignmentPresentationKeys(rows, 'id');
  assert.equal(new Set(keys.values()).size, rows.length);
  assert.equal(keys.get(rows[3]), '__assignment_presentation__0'); assert.equal(keys.get(rows[5]), '0');
  assert.equal(rows[0].id, '***'); assert.equal(assignmentKey(rows[0].id), undefined);
  assert.deepEqual([...assignmentPresentationKeys(rows, 'id')], [...keys]);
});
