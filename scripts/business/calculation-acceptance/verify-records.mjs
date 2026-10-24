import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {expenseSample, dailySample, deliverySample, assetsSample} from './samples.mjs';

/** Runs real HTTP reads/writes. The caller owns credentials and records evidence after each assertion. */
export async function verifyCalculationRecords({call, request, business, attachment, cases, evidence, save,
  assetCode = 'ACCEPTANCE-001'}) {
  evidence.records ??= {}; evidence.checks ??= [];
  const pass = async (name, detail) => {evidence.checks.push({name, at: new Date().toISOString(), ...detail}); await save(); console.log('PASS ' + name);};
  const runtime = s => ({formId: s.formId, formVersion: s.version});
  const create = async (code, label, values, expected) => {
    const s = business.forms[code];
    const saved = evidence.records[label] ??= {idempotencyKey: randomUUID(), values}; await save();
    saved.verified ||= Boolean(saved.recordId && evidence.checks.some(check => check.name === label && check.recordId === saved.recordId));
    saved.before ??= Date.now(); await save();
    const preview = await call(s.runtime + '/records/preview', 'POST', {runtime: runtime(s), state: 'DRAFT', values: saved.values});
    for (const [key, value] of Object.entries(expected)) assert.equal(preview.values[key], value, label + ' preview ' + key);
    const command = {runtime: runtime(s), state: 'DRAFT', values: saved.values, idempotencyKey: saved.idempotencyKey};
    const result = saved.verified ? {recordId: saved.recordId, version: saved.version} : await call(s.runtime + '/records', 'POST', command);
    saved.recordId = result.recordId; await save();
    const record = await call(s.runtime + '/records/' + result.recordId);
    for (const [key, value] of Object.entries(expected)) assert.equal(record.values[key], value, label + ' stored ' + key);
    if (!saved.verified) {
      const retry = await call(s.runtime + '/records', 'POST', command);
      assert.equal(retry.recordId, result.recordId); assert.equal(retry.version, result.version);
      saved.verified = true;
    }
    saved.actual = record.values; saved.version = record.version; saved.after ??= Date.now(); await save();
    await pass(label, {recordId: result.recordId, expected, actual: Object.fromEntries(Object.keys(expected).map(key => [key, record.values[key]]))});
    return {s, record, command};
  };
  const expense = expenseSample(attachment, '10.01', {subject: '【计算验收】精确小数与主从依赖', discount_rate: '0.95'});
  expense.expense_items[0].values.quantity = '3';
  expense.expense_items.push({values: {...expense.expense_items[0].values, quantity: '2', unit_price: '8.25', invoice_no: 'SIM-CAL-SECOND'}});
  const exact = await create('cal_expense', 'exact-aggregate', expense, {total_amount: '44.21', base_amount: '44.21', payable_amount: '44.21'});
  assert.deepEqual(exact.record.values.expense_items.map(row => row.values.amount), ['28.53', '15.68']);
  await pass('exact-owned-rows', {recordId: exact.record.id, expected: ['28.53', '15.68'], actual: exact.record.values.expense_items.map(row => row.values.amount)});
  for (const [label, values] of [['forged-parent', {...expense, total_amount: '1'}],
    ['forged-child', {...expense, expense_items: [{values: {...expense.expense_items[0].values, amount: '1'}}]}]]) {
    const result = await request(exact.s.runtime + '/records', 'POST', {runtime: runtime(exact.s), state: 'DRAFT', idempotencyKey: randomUUID(), values});
    assert.equal(result.status, 400, label); await pass(label, {status: result.status});
  }
  await create('cal_daily', 'daily-aggregate', dailySample(), {total_hours: '8.75', overtime_hours: '0.75', completion_rate: '90'});
  await create('cal_delivery', 'delivery-aggregate', deliverySample(attachment), {weighted_score: '86'});
  await create('cal_assets', 'asset-aggregate', assetsSample(assetCode), {total_quantity: '3', total_amount: '2900', remaining_budget: '2100'});
  for (const item of cases) {
    const time = ['NOW', 'TODAY'].includes(item.name);
    const expected = item.type === 'BOOLEAN' ? item.expected.toUpperCase() : item.expected;
    const values = {function_name: item.name, expression: item.expression, expected: time ? '提交事务服务端时钟' : expected,
      result_type: item.type, reviewer: '公式验收负责人', conclusion: '由已发布的不可变公式执行，核对入库结果与原请求重试。'};
    const {record} = await create('cal_ledger', 'function-' + item.name, values, time ? {} : {actual: expected});
    if (time) {
      const saved = evidence.records['function-' + item.name];
      if (item.name === 'TODAY') assert.equal(record.values.actual, new Date(saved.before).toISOString().slice(0, 10));
      else {
        const millis = Date.parse(record.values.actual + (/[zZ]|[+-]\d\d:\d\d$/.test(record.values.actual) ? '' : 'Z'));
        assert(millis >= saved.before - 2000 && millis <= saved.after + 2000, 'NOW comes from the write transaction clock');
      }
      await pass('server-clock-' + item.name, {recordId: record.id, actual: record.values.actual});
    }
  }
}
