import assert from 'node:assert/strict';
import test from 'node:test';
import {getRouteKey, menuSyncIdentity} from './identity.ts';

test('generated pathless directories have the same identity as rendered menu items', () => {
  assert.equal(getRouteKey({uuid: 'managed-group', code: 'office', title: '人力办公'}), 'managed-group');
  assert.equal(getRouteKey({code: 'office', title: '人力办公'}), 'office');
  assert.equal(getRouteKey({title: '人力办公'}), '人力办公');
});

test('UUID routes, standard paths and numeric IDs retain stable distinct keys', () => {
  assert.equal(getRouteKey({uuid: 'leaf', path: '/office/report', id: 10}), 'leaf');
  assert.equal(getRouteKey({path: '/common/users', id: 10}), '/common/users');
  assert.equal(getRouteKey({id: 0}), '0');
  assert.equal(getRouteKey(), undefined);
});

test('async route arrival and tab changes resync while identical rerenders preserve manual collapse', () => {
  assert.notEqual(menuSyncIdentity('/report', []), menuSyncIdentity('/report', ['office']));
  assert.notEqual(menuSyncIdentity('/report', ['office']), menuSyncIdentity('/expense', ['office']));
  assert.equal(menuSyncIdentity('/report', ['office']), menuSyncIdentity('/report', ['office']));
});
