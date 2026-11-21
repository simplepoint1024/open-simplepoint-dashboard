import test from 'node:test';
import assert from 'node:assert/strict';
import {referenceIdentity, referenceProjection} from './display.ts';

const id = 'aaaaaaaa-1111-2222-3333-444444444444';

test('masked, empty and invalid identities cannot initiate a reference read', () => {
  for (const value of [null, undefined, '', '***', {}, [id], ' ' + id, id + '/other', 'customer']) {
    assert.equal(referenceIdentity(value), undefined);
  }
  assert.equal(referenceIdentity(id.toUpperCase()), id);
});

test('labels bind to the exact requested record and unavailable values have one safe projection', () => {
  const other = 'bbbbbbbb-1111-2222-3333-444444444444';
  assert.deepEqual(referenceProjection(id, [{value: other, label: 'Other customer'}]), {id, label: null});
  assert.deepEqual(referenceProjection(id, []), {id, label: null});
  assert.deepEqual(referenceProjection(id, [{value: id, label: '   '}]), {id, label: null});
  assert.deepEqual(referenceProjection(id, [{value: id.toUpperCase(), label: '客户・日本語😀'}]), {id, label: '客户・日本語😀'});
});

test('long labels have a bounded Unicode presentation without changing the directory value', () => {
  const label = '😀'.repeat(256) + '客户';
  const rows = [{value: id, label}];
  assert.deepEqual(referenceProjection(id, rows), {id, label: '😀'.repeat(255) + '…'});
  assert.equal(rows[0].label, label);
});
