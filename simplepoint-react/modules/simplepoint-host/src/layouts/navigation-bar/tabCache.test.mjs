import assert from 'node:assert/strict';
import test from 'node:test';
import {closeTargets, ownersAllowEviction, retainPages} from './tabCache.ts';

test('restored labels do not eagerly mount unopened pages', () => {
  assert.deepEqual(retainPages([], ['a', 'b', 'c'], 'b', () => true), ['b']);
});
test('evicts least recently used safe pages, never the active page', () => {
  assert.deepEqual(retainPages(['a', 'b', 'c'], ['a', 'b', 'c', 'd'], 'd', () => true, 3), ['b', 'c', 'd']);
  assert.deepEqual(retainPages(['a', 'b', 'c'], ['a', 'b', 'c'], 'a', () => true, 2), ['c', 'a']);
});
test('unknown, editing and saving pages may exceed the soft limit without losing work', () => {
  assert.deepEqual(retainPages(['a', 'b'], ['a', 'b', 'c'], 'c', key => key === 'c', 1), ['a', 'b', 'c']);
  assert.equal(ownersAllowEviction(), false);
  assert.equal(ownersAllowEviction({}), false);
  assert.equal(ownersAllowEviction({table: true, editor: false}), false);
  assert.equal(ownersAllowEviction({table: true}), true);
});
test('closed pages release their cached instances and active page is not duplicated', () => {
  assert.deepEqual(retainPages(['a', 'a', 'b'], ['a', 'c'], 'c', () => false), ['a', 'c']);
});
test('bulk-close guards cover exactly the pages the action would discard', () => {
  const tabs = [{key: 'home', closable: false}, {key: 'a'}, {key: 'b'}, {key: 'c'}];
  assert.deepEqual(closeTargets(tabs, 'b', 'closeLeft'), ['a']);
  assert.deepEqual(closeTargets(tabs, 'b', 'closeRight'), ['c']);
  assert.deepEqual(closeTargets(tabs, 'b', 'closeOthers'), ['a', 'c']);
  assert.deepEqual(closeTargets(tabs, 'b', 'refresh'), ['b']);
  assert.deepEqual(closeTargets(tabs, 'b', 'closeAll'), ['home', 'a', 'b', 'c']);
});
