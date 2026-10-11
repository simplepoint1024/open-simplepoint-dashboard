import assert from 'node:assert/strict';
import test from 'node:test';
import {valueExpressionPresentation, valueExpressionModeAllowed} from './valueModel.ts';
import {expressionConstant} from './model.ts';

const names = new Set(['initiator', 'approver']);
test('whitespace variable views preserve the exact saved expression and never quote it as a literal', () => {
  const value = '  ${approver}  ', view = valueExpressionPresentation(value, names);
  assert.equal(view.expression, true); assert.equal(view.declaredVariable, true); assert.equal(view.defaultMode, 'variable');
  assert.equal(view.advancedValue, value); assert.equal(view.literalValue, undefined);
});
test('literal advanced and variable views are previews, without a default variable assignment', () => {
  for (const value of ['member-1', 'a,b', 'say "yes"', '2026-10-10T01:00:00Z']) {
    const view = valueExpressionPresentation(value, names);
    assert.equal(view.literalValue, value); assert.equal(expressionConstant(view.advancedValue), value);
    assert.equal(view.variable, undefined); assert.equal(view.declaredVariable, false);
    assert.equal(valueExpressionModeAllowed('variable', view.simple, names.size), true);
  }
  assert.deepEqual([...names], ['initiator', 'approver']);
});
test('constant ordinary previews decode strings but retain the original advanced bytes', () => {
  for (const [value, literal] of [['  ${"tenant.approver"}  ', 'tenant.approver'], ['${false}', 'false'], ['${0}', '0'], ['${""}', '']]) {
    const view = valueExpressionPresentation(value, names); assert.equal(view.advancedValue, value);
    assert.equal(view.literalValue, literal); assert.equal(view.defaultMode, 'advanced'); assert.equal(view.simple, true);
  }
});
test('numeric constant previews retain precision, exponent notation and negative zero without Number rounding', () => {
  for (const literal of ['9007199254740993', '0.10000000000000001', '-0', '1e2']) {
    const value = '${' + literal + '}'; assert.equal(expressionConstant(value), literal);
    assert.equal(valueExpressionPresentation(value, names).advancedValue, value);
  }
});
test('numeric identity previews require native resolution, but quoted numeric identity text does not', () => {
  for (const literal of ['1e2', '1.00', '-0.0', '9007199254740993', '1e309']) {
    const source = '${' + literal + '}';
    assert.equal(valueExpressionPresentation(source, names).numericLiteral, true);
    assert.equal(valueExpressionPresentation(source, names).advancedValue, source);
    assert.equal(valueExpressionPresentation('${"' + literal + '"}', names).numericLiteral, false);
    assert.equal(valueExpressionPresentation(literal, names).numericLiteral, false);
  }
  for (const source of ['${true}', '${amount}', '${1 + 2}', '${null}']) {
    assert.equal(valueExpressionPresentation(source, names).numericLiteral, false);
  }
});
test('complex, malformed and deferred expressions cannot be silently replaced by ordinary values', () => {
  for (const value of ['${approved ? first : second}', '${list[0]}', '${broken', '#{approver}', '${null}', '${[1]}']) {
    const view = valueExpressionPresentation(value, names); assert.equal(view.expression, true); assert.equal(view.simple, false);
    assert.equal(view.advancedValue, value); assert.equal(valueExpressionModeAllowed('literal', view.simple, names.size), false);
    assert.equal(valueExpressionModeAllowed('variable', view.simple, names.size), false); assert.equal(valueExpressionModeAllowed('advanced', view.simple, 0), true);
  }
});
test('unavailable simple references stay intact without inventing a selected declared variable', () => {
  const view = valueExpressionPresentation('${removedUser}', names);
  assert.equal(view.defaultMode, 'advanced'); assert.equal(view.variable, 'removedUser'); assert.equal(view.declaredVariable, false);
  assert.equal(view.literalValue, undefined); assert.equal(view.advancedValue, '${removedUser}');
});
test('empty values and missing declarations remain empty rather than creating a placeholder assignment', () => {
  const view = valueExpressionPresentation('', new Set()); assert.equal(view.advancedValue, ''); assert.equal(view.literalValue, undefined);
  assert.equal(valueExpressionModeAllowed('variable', view.simple, 0), false); assert.equal(valueExpressionModeAllowed('literal', view.simple, 0), true);
});
