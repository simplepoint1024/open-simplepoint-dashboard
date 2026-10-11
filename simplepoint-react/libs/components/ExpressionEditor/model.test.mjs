import assert from 'node:assert/strict';
import {test} from 'node:test';
import {conditionExpression, parseConditionExpression, expressionIssue, expressionVariable, expressionConstant} from './model.ts';

test('visual conditions preserve precedence, scalar types, string escapes and null comparisons', () => {
  const expression = '${amount >= 120.5 && (approved == true || reason == "say \\"yes\\"") && note != null}';
  const ast = parseConditionExpression(expression);
  assert.deepEqual(parseConditionExpression(conditionExpression(ast)), ast);
  assert.equal(ast.children[0].value, '120.5');
  assert.equal(ast.children[0].valueType, 'number');
  assert.equal(ast.children[1].children[0].value, true);
  assert.equal(ast.children[2].op, 'is:not:null');
});

test('unsupported advanced syntax is rejected without silently rewriting its source', () => {
  for (const expression of ['${amount > threshold}', '${person.name.startsWith("a")}', '${list[0] == 1}', '${a == 1 trailing}']) {
    assert.throws(() => parseConditionExpression(expression));
  }
  assert.equal(expressionVariable('${initiator}'), 'initiator');
  assert.equal(expressionVariable('${a + b}'), undefined);
  assert.match(expressionIssue('${Runtime.exec("x")}'), /不允许/);
});

test('serializer requires complete conditions and bounded expression size', () => {
  assert.throws(() => conditionExpression({op: 'and', children: []}));
  assert.throws(() => conditionExpression({op: 'equals', field: 'approved'}));
  assert.throws(() => conditionExpression({op: 'equals', field: 'note', value: 'x'.repeat(500)}));
});

test('fixed values can round-trip through constant expressions without evaluation', () => {
  for (const value of ['member-123', 'a,b', '2026-10-10T01:00:00Z', 'say "yes"']) {
    assert.equal(expressionConstant('${' + JSON.stringify(value) + '}'), value);
  }
  for (const value of ['${initiator}', '${amount + 1}', '${Runtime.exec("x")}', '${null}', '${[1]}']) {
    assert.equal(expressionConstant(value), undefined);
  }
});

test('advanced diagnostics block engine objects and indirect invocation, not quoted text', () => {
  for (const value of ['${execution.processEngineConfiguration.jdbcPassword}', '${task.owner}', '${x[method]()}', '${x["class"]}']) {
    assert.ok(expressionIssue(value));
  }
  assert.equal(expressionIssue('${reason == "task" ? "member1" : "name()"}'), undefined);
});
