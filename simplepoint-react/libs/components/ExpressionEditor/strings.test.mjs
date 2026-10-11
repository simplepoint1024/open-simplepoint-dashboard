import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';
import {conditionExpression, parseConditionExpression, expressionConstant, expressionIssue} from './model.ts';
import {valueExpressionPresentation, fixedExpressionValue} from './valueModel.ts';
import {quoteExpressionString} from './literals.ts';

const cases = JSON.parse(readFileSync(new URL('./stringLiterals.fixture.json', import.meta.url), 'utf8'));
test('ordinary conditions encode the exact string literals consumed by the Flowable contract fixture', () => {
  for (const item of cases) assert.equal(conditionExpression({field: 'reason', op: 'equals', value: item.value}),
    '${reason == ' + item.literal + '}', item.id);
});
test('ordinary parsing and value constants decode Flowable literals, including raw XML whitespace', () => {
  for (const item of cases) {
    assert.equal(parseConditionExpression('${reason == ' + item.literal + '}').value, item.value, item.id);
    assert.equal(expressionConstant('${' + item.literal + '}'), item.value, item.id);
    assert.equal(expressionIssue('${reason == ' + item.literal + '}'), undefined, item.id);
  }
});
test('fixed-value advanced previews use the same language without changing the original value', () => {
  for (const item of cases.filter(item => item.value)) {
    const view = valueExpressionPresentation(fixedExpressionValue(item.value), new Set());
    assert.equal(view.advancedValue, '${' + item.literal + '}', item.id);
    assert.equal(view.literalValue, item.value, item.id);
  }
});
test('single-quoted constants and either escaped quote use Flowable decoding, not JSON decoding', () => {
  const literal = String.raw`'say \"yes\" and \'no\''`;
  const value = '${' + literal + '}';
  assert.equal(expressionConstant(value), 'say "yes" and \'no\'');
  assert.equal(parseConditionExpression('${reason == ' + value.slice(2, -1) + '}').value, 'say "yes" and \'no\'');
});
test('JSON-only escape sequences and malformed strings are diagnosed instead of silently interpreted', () => {
  for (const literal of ['"a\\n"', '"a\\t"', '"a\\r"', '"a\\u0041"', "'a\\n'", '"unterminated']) {
    const value = '${' + literal + '}';
    assert.ok(expressionIssue(value), literal);
    assert.equal(expressionConstant(value), undefined);
    assert.throws(() => parseConditionExpression('${reason == ' + literal + '}'));
  }
});
test('literal selections containing template markers cannot be evaluated as variable expressions', () => {
  for (const value of ['${literal-role}', 'prefix${role}suffix', '#{role}']) {
    assert.equal(expressionConstant(fixedExpressionValue(value)), value);
  }
  assert.equal(fixedExpressionValue('member-1'), 'member-1');
  assert.equal(fixedExpressionValue(undefined), undefined);
});
test('XML-invalid controls and unpaired surrogates cannot become saved expressions or crash draft previews', () => {
  for (const value of ['a\u0000b', 'a\bb', 'a\fb', '\uD800', '\uDFFF', '\uFFFE', '\uFFFF']) {
    assert.throws(() => quoteExpressionString(value));
    assert.throws(() => conditionExpression({field: 'reason', op: 'equals', value}));
    assert.ok(expressionIssue('${"' + value + '"}'));
    assert.doesNotThrow(() => valueExpressionPresentation(value, new Set()));
  }
});
