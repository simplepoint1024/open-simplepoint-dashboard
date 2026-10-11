import assert from 'node:assert/strict';
import test from 'node:test';
import {conditionExpression, parseConditionExpression, conditionAvailable, conditionLimits} from './model.ts';

const variables = new Map([
  ['amount', {name: 'amount', label: 'Amount', type: 'number'}],
  ['reason', {name: 'reason', label: 'Reason', type: 'string'}],
  ['approved', {name: 'approved', label: 'Approved', type: 'boolean'}],
]);
test('numeric expression literals keep their exact lexical values without JS Number coercion', () => {
  for (const value of ['9007199254740993', '0.10000000000000001', '1.0', '-0', '1e2', '1e-400']) {
    const source = '${amount == ' + value + '}';
    const node = parseConditionExpression(source);
    assert.equal(node.value, value);
    assert.equal(conditionExpression(node), source);
  }
});
test('shared numeric input strings serialize as numeric literals only for declared numeric fields', () => {
  const node = {field: 'amount', op: 'equals', value: '0.10000000000000001'};
  assert.equal(conditionExpression(node, variables), '${amount == 0.10000000000000001}');
  assert.equal(conditionExpression({...node, field: 'reason'}, variables), '${reason == "0.10000000000000001"}');
});
test('editing a sibling condition leaves precise numeric constants untouched', () => {
  const node = parseConditionExpression('${amount == 9007199254740993 && approved == true}');
  node.children[1].value = false;
  assert.equal(conditionExpression(node, variables), '${(amount == 9007199254740993 && approved == false)}');
});
test('ordinary eligibility distinguishes numeric literals from quoted numeric-looking strings', () => {
  const quoted = parseConditionExpression('${amount == "10"}');
  const numeric = parseConditionExpression('${reason == 10}');
  assert.equal(conditionAvailable(quoted, variables), false);
  assert.equal(conditionAvailable(numeric, variables), false);
  assert.equal(conditionExpression(quoted), '${amount == "10"}');
  assert.equal(conditionExpression(numeric), '${reason == 10}');
  assert.throws(() => conditionExpression(quoted, variables));
  assert.throws(() => conditionExpression(numeric, variables));
});
test('precise numeric drafts remain available and reject invalid raw text without expression injection', () => {
  for (const value of ['9007199254740993', '0.10000000000000001', undefined]) {
    assert.equal(conditionAvailable({field: 'amount', op: 'equals', value}, variables), true);
  }
  for (const value of ['', '1; approved == true', true, {}, Infinity, NaN, '1e400']) {
    assert.throws(() => conditionExpression({field: 'amount', op: 'equals', value}, variables));
  }
  assert.throws(() => parseConditionExpression('${amount == 1e400}'));
});
test('ordinary controls use a global node budget and bounded group depth', () => {
  const leaf = () => ({field: 'approved', op: 'equals', value: true});
  const exact = {op: 'and', children: Array.from({length: conditionLimits.maxNodes - 1}, leaf)};
  assert.equal(conditionAvailable(exact, variables), true);
  const overflow = {...exact, children: [...exact.children, leaf()]};
  assert.equal(conditionAvailable(overflow, variables), false);
  assert.throws(() => conditionExpression(overflow, variables));
  let deep = leaf();
  for (let i = 0; i <= conditionLimits.maxDepth; i++) deep = {op: 'not', children: [deep]};
  assert.equal(conditionAvailable(deep, variables), false);
  assert.throws(() => conditionExpression(deep, variables));
});
test('unknown variables, arrays and type-incompatible comparison operators cannot masquerade as ordinary conditions', () => {
  for (const node of [{field: 'removed', op: 'equals', value: true}, {field: 'reason', op: 'than:greater', value: 'x'},
    {field: 'approved', op: 'equals', value: 'true'}, {field: 'reason', op: 'equals', value: ['x']},
    {op: 'not', children: []}]) {
    assert.equal(conditionAvailable(node, variables), false);
    assert.throws(() => conditionExpression(node, variables));
  }
  assert.equal(conditionAvailable({field: 'reason', op: 'is:null'}, variables), true);
  assert.equal(conditionAvailable({field: 'approved', op: 'equals', value: false}, variables), true);
});
