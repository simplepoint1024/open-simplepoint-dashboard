import assert from 'node:assert/strict';
import {test} from 'node:test';
import {exactDecimal, compareDecimals} from '../../shared/utils/decimal.ts';
import {decimalIssue, validateDecimalFields} from './decimalValue.ts';

const rule = {precision: 19, scale: 8, minimum: '0', maximum: '99999999999.99999999'};
test('form decimals preserve the full database boundary, adjacent values and scientific precision', () => {
  for (const value of ['99999999999.99999999', '0', '0.00000001', '1E-8', '+0.12345678']) assert.equal(decimalIssue(value, rule), undefined);
  assert.equal(decimalIssue('100000000000', rule), 'maximum'); assert.equal(decimalIssue('-0.00000001', rule), 'minimum');
  assert.equal(decimalIssue('0.000000001', rule), 'scale'); assert.equal(decimalIssue('1.000000000', rule), undefined);
  assert.equal(decimalIssue('0.000000000000', rule), undefined); assert.equal(decimalIssue('100000000E-9', rule), undefined);
  assert.equal(decimalIssue('999999999999.123456789012', {scale: 12, maximum: '999999999999.999999999999'}), undefined);
  assert.equal(compareDecimals(exactDecimal('99999999999.99999998'), exactDecimal('99999999999.99999999')), -1);
  assert.equal(compareDecimals(exactDecimal('0.1'), exactDecimal('0.10000000')), 0);
});
test('inclusive/exclusive bounds, zero and nullable empties stay explicit', () => {
  assert.equal(decimalIssue(null, rule), undefined); assert.equal(decimalIssue(undefined, rule), undefined);
  assert.equal(decimalIssue('0', {...rule, exclusiveMinimum: true}), 'minimum');
  assert.equal(decimalIssue(rule.maximum, {...rule, exclusiveMaximum: true}), 'maximum');
  for (const value of ['', ' ', '***', 'NaN', 'Infinity', '1,25', '1E10000000', true, 0.25]) assert.equal(decimalIssue(value, rule), 'format');
  assert.throws(() => exactDecimal('1'.repeat(2001))); assert.throws(() => exactDecimal('1e1001'));
});
test('engine validation walks genuine nested objects/arrays/refs and ignores protected masks', () => {
  const schema = {definitions: {Price: {type: 'string', 'x-decimal': rule}}, properties: {
    protected: {type: 'string', readOnly: true, 'x-decimal': rule}, mask: {type: 'string', 'x-field-access': 'MASKED', 'x-decimal': rule},
    direct: {$ref: '#/definitions/Price'}, rows: {type: 'array', items: {properties: {price: {type: 'string', 'x-decimal': rule}}}},
  }};
  const failures = [], leaf = path => ({addError(message) {failures.push([path, message]);}});
  const errors = {protected: leaf('protected'), mask: leaf('mask'), direct: leaf('direct'), rows: [{price: leaf('rows.0.price')}]};
  validateDecimalFields(schema, {protected: '***', mask: '***', direct: '100000000000', rows: [{price: '-0.01'}]}, errors, issue => issue);
  assert.deepEqual(failures, [['direct', 'maximum'], ['rows.0.price', 'minimum']]);
});
test('cyclic local references terminate without hiding sibling decimal validation', () => {
  const schema = {definitions: {A: {$ref: '#/definitions/B'}, B: {$ref: '#/definitions/A'}}, properties: {
    cycle: {$ref: '#/definitions/A'}, amount: {'x-decimal': rule},
  }};
  const failures = [], leaf = {addError(issue) {failures.push(issue);}};
  validateDecimalFields(schema, {cycle: 'any', amount: '100000000000'}, {cycle: leaf, amount: leaf}, issue => issue);
  assert.deepEqual(failures, ['maximum']);
});
