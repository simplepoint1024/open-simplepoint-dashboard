import assert from 'node:assert/strict';
import {test} from 'node:test';
import {isNumericQueryType, queryScalar} from './queryValue.ts';

test('decimal header and advanced inputs stay numeric but never coerce a fractional string to a safe integer', () => {
  assert.equal(isNumericQueryType('string', 'BigDecimal'), true);
  for (const value of ['99999999999.99999999', '0.999999999999999999', '0', '0.10000000', '1E-8'])
    assert.equal(queryScalar(value, 'string', 'BigDecimal'), value);
  assert.equal(queryScalar('99999999999.99999999', 'number'), '99999999999.99999999');
  assert.equal(queryScalar('0.999999999999999999', 'number'), '0.999999999999999999');
});
test('safe literal integers, large integers, text and booleans preserve their own query type contracts', () => {
  assert.equal(queryScalar('42', 'integer', 'Integer'), 42);
  assert.equal(queryScalar('9007199254740993', 'integer', 'Long'), '9007199254740993');
  assert.equal(queryScalar('42', 'string', 'String'), '42');
  assert.equal(queryScalar('false', 'boolean', 'Boolean'), false);
  assert.equal(queryScalar('true', 'boolean', 'Boolean'), true);
});
