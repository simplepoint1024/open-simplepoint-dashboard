import assert from 'node:assert/strict';
import test from 'node:test';
import {previewStatus, previewTree} from './resourcePreview.ts';

const row = (id, parentId, label = id) => ({id, parentId, label});
test('same-named actions retain their own page and directory context, without unrelated branches', () => {
  const rows = [row('root'), row('users', 'root'), row('roles', 'root'), row('other', 'root'),
    row('delete-user', 'users', '删除'), row('delete-role', 'roles', '删除')];
  const tree = previewTree(rows, new Set(['delete-user', 'delete-role']), row => row.label);
  assert.equal(tree.length, 1);
  assert.deepEqual(tree[0].children.map(node => [node.key, node.children[0].key]),
    [['roles', 'delete-role'], ['users', 'delete-user']]);
  assert.deepEqual(previewTree(rows, new Set(), row => row.label), []);
});

test('policy changes are not new grants; revocations remain distinguishable', () => {
  assert.equal(previewStatus({baseline: {granted: true}, granted: true, dataScopeId: null}), 'updated');
  assert.equal(previewStatus({baseline: {granted: false}, granted: true}), 'added');
  assert.equal(previewStatus({baseline: {granted: true}, granted: false}), 'removed');
  assert.equal(previewStatus(), 'unchanged');
});

test('orphan and cyclic legacy resources remain visible without hanging', () => {
  const orphan = previewTree([row('a', 'missing')], new Set(['a']), row => row.label);
  assert.equal(orphan[0].key, 'a');
  const cycle = previewTree([row('a', 'b'), row('b', 'a')], new Set(['a', 'b']), row => row.label);
  assert.equal(cycle.length, 1);
  assert.equal(cycle[0].children.length, 1);
});
