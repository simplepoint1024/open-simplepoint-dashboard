import assert from 'node:assert/strict';
import {test} from 'node:test';
import {billingPeriod, billingInvocationQuery, formatBillingAmount} from './model.ts';

test('billing periods retain exact exclusive midnight and reject invalid or over-366-day periods', () => {
  const from = '2026-10-01T00:00:00+08:00', to = '2026-11-01T00:00:00+08:00';
  assert.deepEqual(billingPeriod(from, to), {from, to});
  assert.ok(billingPeriod('2024-01-01T00:00:00Z', '2025-01-01T00:00:00Z'));
  for (const pair of [['bad', to], [to, from], [from, from], ['2024-01-01T00:00:00Z', '2025-01-01T00:00:00.001Z']]) {
    assert.equal(billingPeriod(...pair), undefined);
  }
});
test('invocation filters always intersect the immutable selected period', () => {
  const period = {from: '2026-10-01T00:00:00Z', to: '2026-10-11T00:00:00Z'};
  const filter = {op: 'or', children: [{field: 'startedAt', op: 'than:less', value: period.from}, {field: 'modelId', op: 'equals', value: 'x'}]};
  const query = JSON.parse(billingInvocationQuery(period, JSON.stringify({version: 1, where: filter})));
  assert.equal(query.where.op, 'and');
  assert.deepEqual(query.where.children, [
    {field: 'startedAt', op: 'than:equal:greater', value: period.from}, {field: 'startedAt', op: 'than:less', value: period.to}, filter,
  ]);
  assert.throws(() => billingInvocationQuery(period, '{invalid'));
  assert.throws(() => billingInvocationQuery(period, JSON.stringify({version: 2, where: filter})));
});
test('financial strings remain exact above Number.MAX_SAFE_INTEGER and invalid data is never zero', () => {
  assert.equal(formatBillingAmount('9007199254740993.01020304', 'USD', 'en-US'), '$9,007,199,254,740,993.01020304');
  assert.equal(formatBillingAmount('0.00000001', 'USD', 'en-US'), '$0.00000001');
  assert.equal(formatBillingAmount('0.000000000001', 'USD', 'en-US'), '$0.000000000001');
  assert.equal(formatBillingAmount('999999999999.123456789012', 'USD', 'en-US'), '$999,999,999,999.123456789012');
  assert.equal(formatBillingAmount('2.12345678', 'NOT_A_CURRENCY', 'en-US'), 'NOT_A_CURRENCY 2.12345678');
  for (const value of [undefined, null, '', 'NaN', Number.NaN, '***']) assert.equal(formatBillingAmount(value, 'USD', 'en-US'), '—');
});
