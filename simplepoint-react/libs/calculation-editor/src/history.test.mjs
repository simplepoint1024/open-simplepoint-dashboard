import assert from 'node:assert/strict';
import test from 'node:test';
import {changeExpression, expressionHistory, reconcileHistory, traverseHistory} from './history.ts';
import {moveArgument} from './moveArgument.ts';

const accept = (history, value) => reconcileHistory(changeExpression(history, value), value);
test('undo and redo preserve invalid drafts and exact Unicode/decimal text; new edits replace the redo branch', () => {
  let h = expressionHistory('=SUM("汉😀",0.12345678901234567890)');
  h = accept(h, '=SUM('); h = accept(h, '=SUM(1,2)');
  h = traverseHistory(h, 'undo'); h = reconcileHistory(h, h.present);
  assert.equal(h.present, '=SUM(');
  h = traverseHistory(h, 'undo'); h = reconcileHistory(h, h.present);
  assert.equal(h.present, '=SUM("汉😀",0.12345678901234567890)');
  h = traverseHistory(h, 'redo'); h = reconcileHistory(h, h.present);
  assert.equal(h.present, '=SUM(');
  h = accept(h, '=4'); assert.deepEqual(h.future, []); assert.equal(traverseHistory(h, 'redo'), h);
});
test('external host replacement clears history while an unchanged controlled prop does not acknowledge a rejected edit', () => {
  const pending = changeExpression(expressionHistory('=1'), '=2');
  assert.equal(reconcileHistory(pending, '=1'), pending);
  assert.notEqual(pending.present, '=1');
  assert.deepEqual(changeExpression(pending, '=3', '=1').past, ['=1'], 'Rejected invisible edits never enter undo history');
  const acknowledged = reconcileHistory(pending, '=2'); assert.deepEqual(acknowledged.past, ['=1']);
  assert.deepEqual(reconcileHistory(acknowledged, '=host'), expressionHistory('=host'));
});
test('long editing sessions retain at most one hundred reversible steps', () => {
  let h = expressionHistory('=0'); for (let i = 1; i <= 150; i++) h = accept(h, '=' + i);
  assert.equal(h.past.length, 100); assert.equal(h.past[0], '=50');
  for (let i = 0; i < 100; i++) {h = traverseHistory(h, 'undo'); h = reconcileHistory(h, h.present);}
  assert.equal(h.present, '=50'); assert.equal(h.future.length, 100); assert.equal(traverseHistory(h, 'undo'), h);
});
test('keyboard argument moves preserve both nested operands, immutable input and boundary positions', () => {
  const leaf = text => ({kind: 'FIELD', text, children: []});
  const tree = {kind: 'CALL', text: 'ROUND', children: [{kind: 'BINARY', text: '/', children: [leaf('数量'), leaf('单价')]}, {kind: 'NUMBER', text: '2', children: []}]};
  const before = structuredClone(tree), moved = moveArgument(tree, [0, 0], 1);
  assert.deepEqual(moved.path, [0, 1]); assert.deepEqual(moved.tree.children[0].children.map(x => x.text), ['单价', '数量']);
  assert.deepEqual(tree, before); assert.deepEqual(moveArgument(moved.tree, moved.path, -1).tree, tree);
  for (const [path, direction] of [[[], 1], [[0, 0], -1], [[1], 1], [[0, 9], -1], [[-1], 1]]) assert.equal(moveArgument(tree, path, direction), undefined);
});
