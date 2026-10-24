import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {expenseSample} from './samples.mjs';

/** Real aggregate writes, transaction rollback, retry and competing optimistic edits. */
export async function verifyCalculationCrud({call, request, spec, attachment, state, save}) {
  const endpoint = spec.runtime + '/records', runtime = {formId: spec.formId, formVersion: spec.version};
  state.steps ??= {};
  const read = () => call(endpoint + '/' + state.recordId);
  const checkpoint = async name => {state.checks ??= []; state.checks.push(name); await save(); console.log('PASS ' + name);};
  if (!state.recordId) {
    state.create ??= {runtime, state: 'DRAFT', idempotencyKey: randomUUID(),
      values: expenseSample(attachment, '100', {subject: '【计算验收】主从增删改与并发', notes: '中文・日本語・😀'})}; await save();
    state.recordId = (await call(endpoint, 'POST', state.create)).recordId; state.current = await read(); await save();
  }
  if (state.passed) return;
  const line = (row, values = {}) => ({id: row.id, version: row.version, values});
  async function update(name, values, expected) {
    if (state.steps[name]?.done) return;
    const step = state.steps[name] ??= {command: {runtime, state: 'DRAFT', expectedVersion: state.current.version,
      idempotencyKey: randomUUID(), values: typeof values === 'function' ? values() : values}}; await save();
    if (step.done) return;
    const result = await call(endpoint + '/' + state.recordId, 'PUT', step.command);
    const retry = await call(endpoint + '/' + state.recordId, 'PUT', step.command); assert.deepEqual(retry, result);
    const current = await read(); assert.equal(current.values.total_amount, expected);
    assert.equal(current.version, step.command.expectedVersion + 1);
    step.done = true; step.result = result; step.actual = current; state.current = current; await checkpoint(name);
  }
  await update('detail-edit', () => ({expense_items: [line(state.current.values.expense_items[0], {quantity: '3'})]}), '300');
  await update('detail-add', () => ({expense_items: [line(state.current.values.expense_items[0]),
    {values: {...state.create.values.expense_items[0].values, quantity: '2', unit_price: '5', description: '新增费用・明細😀'}}]}), '310');
  await update('root-recalculates-all-owned-rows', {discount_rate: '0.9'}, '279');
  await update('detail-remove', () => ({expense_items: [line(state.current.values.expense_items.find(r => r.values.unit_price === '100'))]}), '270');
  await update('draft-may-clear-detail-and-recalculate-empty-sum', {expense_items: []}, '0');
  await update('detail-readd-after-clear', {expense_items: [{values: {...state.create.values.expense_items[0].values, quantity: '3'}}]}, '270');
  async function rejected(name, values, targetState = 'DRAFT') {
    if (state.steps[name]?.done) return;
    const before = await read(), result = await request(endpoint + '/' + state.recordId, 'PUT', {
      runtime, state: targetState, expectedVersion: before.version, idempotencyKey: randomUUID(), values});
    assert([400, 403, 409].includes(result.status), name);
    const error = JSON.parse(result.body); assert(error.messageKey);
    assert.deepEqual(await read(), before, 'Rejected aggregate must retain every value/version/audit field');
    state.steps[name] = {done: true, status: result.status, code: error.code, messageKey: error.messageKey}; await checkpoint(name);
  }
  await rejected('submission-requires-detail-rows', {expense_items: []}, 'SUBMITTED');
  await rejected('invalid-submission-range-rolls-back-child-calculations', {discount_rate: '2'}, 'SUBMITTED');
  await rejected('invalid-child-type-rolls-back-aggregate', {expense_items: [line(state.current.values.expense_items[0], {quantity: 'invalid'})]});
  await rejected('computed-child-value-cannot-be-forged', {expense_items: [line(state.current.values.expense_items[0], {amount: '1'})]});
  if (!state.steps.concurrent?.done) {
    const step = state.steps.concurrent ??= {commands: ['A', 'B'].map(label => ({runtime, state: 'DRAFT',
      expectedVersion: state.current.version, idempotencyKey: randomUUID(), values: {notes: '并发编辑 ' + label}}))}; await save();
    const responses = await Promise.all(step.commands.map(command => request(endpoint + '/' + state.recordId, 'PUT', command)));
    assert.deepEqual(responses.map(r => r.status).sort(), [200, 409]);
    const current = await read(); assert.equal(current.version, state.current.version + 1);
    assert.equal(current.values.total_amount, state.current.values.total_amount);
    assert.deepEqual(current.values.expense_items.map(({id, values}) => ({id, values})),
      state.current.values.expense_items.map(({id, values}) => ({id, values})));
    for (const row of current.values.expense_items) {
      const old = state.current.values.expense_items.find(r => r.id === row.id);
      assert.equal(row.version, old.version + 1, 'One accepted aggregate recomputation advances each owned version once');
      assert.equal(row.systemValues.createdAt, old.systemValues.createdAt);
    }
    const winner = responses.findIndex(r => r.status === 200);
    assert.equal(current.values.notes, step.commands[winner].values.notes);
    step.statuses = responses.map(r => r.status); step.winner = winner; step.done = true; state.current = current;
    await checkpoint('concurrent-partial-edits-have-one-winner-and-no-lost-owned-rows');
  }
  if (!state.steps.changedRetry?.done) {
    const step = state.steps.concurrent, original = step.commands[step.winner];
    const response = await request(endpoint + '/' + state.recordId, 'PUT', {...original, values: {notes: 'changed retry'}});
    assert([400, 409].includes(response.status)); assert.deepEqual(await read(), state.current);
    state.steps.changedRetry = {done: true, status: response.status, code: JSON.parse(response.body).code};
    await checkpoint('same-idempotency-key-with-changed-body-is-rejected');
  }
  if (!state.steps.delete?.done) {
    const step = state.steps.delete ??= {key: randomUUID(), expectedVersion: state.current.version}; await save();
    const path = endpoint + '/' + state.recordId + '?' + new URLSearchParams({formId: runtime.formId,
      formVersion: String(runtime.formVersion), expectedVersion: String(step.expectedVersion), idempotencyKey: step.key});
    const deleted = await call(path, 'DELETE'); assert.deepEqual(await call(path, 'DELETE'), deleted);
    const missing = await request(endpoint + '/' + state.recordId); assert.equal(missing.status, 404);
    const page = await call(endpoint + '/query', 'POST', {page: 0, size: 100});
    assert(!page.records.some(r => r.id === state.recordId));
    step.done = true; step.result = deleted; await checkpoint('aggregate-delete-and-retry-remove-the-parent-and-owned-detail-projection');
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
