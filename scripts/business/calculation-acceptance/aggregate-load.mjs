import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {expenseSample} from './samples.mjs';

/** Fixed maximum-sized aggregate and bounded concurrent users; records observations, not an SLA. */
export async function verifyAggregateLoad({call, request, spec, attachment, state, save}) {
  const base = spec.runtime + '/records', runtime = {formId: spec.formId, formVersion: spec.version};
  const values = expenseSample(attachment, '1', {subject: '【计算验收】40 行明细与并发试算・日本語😀'});
  values.expense_items = Array.from({length: 40}, (_, i) => ({values: {...values.expense_items[0].values,
    unit_price: String(i + 1), invoice_no: 'LOAD-' + i, description: '第 ' + (i + 1) + ' 行合成费用・明細😀'}}));
  if (!state.command) {state.command = {runtime, state: 'DRAFT', values, idempotencyKey: randomUUID()}; await save();}
  if (!state.recordId) {
    const started = performance.now();
    state.recordId = (await call('employee', base, 'POST', state.command)).recordId;
    state.createElapsedMs = Math.round(performance.now() - started); await save();
  }
  const read = () => call('employee', base + '/' + state.recordId);
  state.before ??= await read(); assert.equal(state.before.values.expense_items.length, 40);
  assert.equal(state.before.values.total_amount, '820'); await save();
  if (!state.previews) {
    const observations = [], jobs = Array.from({length: 24}, (_, i) => i);
    await Promise.all(Array.from({length: 4}, async () => {
      while (jobs.length) {
        const i = jobs.shift(), actor = i % 2 ? 'manager' : 'employee', rate = (i % 10 + 1) / 10;
        const started = performance.now();
        const result = actor === 'manager' ? await call(actor, base + '/' + state.recordId)
          : await call(actor, base + '/preview', 'POST', {runtime, state: 'DRAFT', recordId: state.recordId,
            expectedVersion: state.before.version, values: {discount_rate: String(rate)}});
        assert.equal(Number(result.values.total_amount), actor === 'manager' ? 820 : 820 * rate);
        assert.equal(actor === 'manager' ? result.values.expense_items.length : result.details.expense_items.length, 40);
        observations.push({actor, operation: actor === 'manager' ? 'authorized-read' : 'trusted-preview', discount: rate,
          total: result.values.total_amount, elapsedMs: Math.round(performance.now() - started)});
      }
    }));
    assert.deepEqual(await read(), state.before, 'Concurrent previews cannot change data, versions or audit metadata');
    state.previews = observations; await save();
  }
  if (!state.oversized) {
    const result = await request('employee', base + '/preview', 'POST', {runtime, state: 'DRAFT',
      values: {...values, expense_items: [...values.expense_items, values.expense_items[0]]}});
    assert.equal(result.status, 400); state.oversized = {status: result.status, error: JSON.parse(result.body)};
    assert.deepEqual(await read(), state.before); await save();
  }
  if (!state.race) {
    const first = state.before.values.expense_items[0];
    const lines = state.before.values.expense_items.map(row => ({id: row.id, version: row.version, values: {}}));
    const commands = [
      {...state.command, idempotencyKey: randomUUID(), expectedVersion: state.before.version,
        values: {expense_items: lines.map(row => row.id === first.id ? {...row, values: {quantity: '2'}} : row)}},
      {...state.command, idempotencyKey: randomUUID(), expectedVersion: state.before.version,
        values: {expense_items: lines.filter(row => row.id !== first.id)}},
    ];
    state.race = {commands}; await save();
  }
  if (!state.race.passed) {
    const responses = await Promise.all(state.race.commands.map(command => request('employee',
      base + '/' + state.recordId, 'PUT', command)));
    assert.deepEqual(responses.map(response => response.status).sort(), [200, 409]);
    const winner = responses.findIndex(response => response.status === 200), after = await read();
    assert.equal(after.version, state.before.version + 1);
    assert.equal(after.values.expense_items.length, winner === 0 ? 40 : 39);
    assert.equal(after.values.total_amount, winner === 0 ? '821' : '819');
    const repeat = await call('employee', base + '/' + state.recordId, 'PUT', state.race.commands[winner]);
    const payload = JSON.parse(responses[winner].body); assert.deepEqual(repeat, payload.data ?? payload);
    assert.deepEqual(await read(), after);
    Object.assign(state.race, {passed: true, statuses: responses.map(response => response.status), winner, after}); await save();
  }
  const times = state.previews.map(item => item.elapsedMs).sort((a, b) => a - b);
  Object.assign(state, {passed: true, concurrency: 4, operationCount: times.length,
    previewCount: state.previews.filter(item => item.operation === 'trusted-preview').length,
    latencyMs: {min: times[0], median: times[Math.floor(times.length / 2)], p95: times[Math.ceil(times.length * .95) - 1], max: times.at(-1)},
    completedAt: new Date().toISOString()}); await save();
  console.log('PASS 40-row aggregate, 24 reads/previews with two roles/four concurrent workers, row limit and edit/delete optimistic race');
}
