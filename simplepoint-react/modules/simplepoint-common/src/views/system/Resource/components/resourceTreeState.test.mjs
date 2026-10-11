import assert from 'node:assert/strict';
import test from 'node:test';
import {assignmentDelta, loadedBranchKeys, retainRootChildren} from './resourceTreeState.ts';

test('tree revalidation retains only matching parent children without discarding fresh metadata', () => {
  const children = [{key: 'child', resource: {hasChildren: false}}];
  const previous = [{key: 'parent', title: 'old', resource: {hasChildren: true}, children}];
  const incoming = [{key: 'parent', title: 'new', resource: {hasChildren: true}},
    {key: 'another', resource: {hasChildren: true}}];
  const result = retainRootChildren(previous, incoming, true);
  assert.equal(result[0].children, children); assert.equal(result[0].title, 'new');
  assert.equal(result[1].children, undefined); assert.equal(incoming[0].children, undefined);
});

test('page/search/revision changes and newly leaf roots cannot inherit a stale branch', () => {
  const previous = [{key: 'parent', resource: {hasChildren: true}, children: [{key: 'old-child'}]}];
  const incoming = [{key: 'parent', resource: {hasChildren: false}}];
  assert.equal(retainRootChildren(previous, incoming, true)[0].children, undefined);
  assert.equal(retainRootChildren(previous, incoming, false), incoming);
});

test('loaded tree keys come only from successfully materialized branches in one bounded traversal', () => {
  assert.deepEqual([...loadedBranchKeys([{key: 'root', children: [
    {key: 'loaded', children: [{key: 'leaf'}]}, {key: 'pending'}, {key: 'empty', children: []},
  ]}])].sort(), ['loaded', 'root']);
});

test('checking a subtree sends only actual additions and removing sends only its existing grants', () => {
  const baseline = ['already-granted', 'literal+code, value'], requested = ['new', 'already-granted', 'new', 'literal+code, value'];
  assert.deepEqual(assignmentDelta(baseline, requested, true), ['new']);
  assert.deepEqual(assignmentDelta(baseline, requested, false), ['already-granted', 'literal+code, value']);
  assert.deepEqual(baseline, ['already-granted', 'literal+code, value']);
});

test('unchanged assignment intent does not invent a mutation or truncate large legitimate code sets', () => {
  const codes = Array.from({length: 521}, (_, i) => 'resource.' + i);
  assert.deepEqual(assignmentDelta([], codes, false), []);
  assert.deepEqual(assignmentDelta(codes, codes, true), []);
  assert.equal(assignmentDelta([], codes, true).length, 521);
});
