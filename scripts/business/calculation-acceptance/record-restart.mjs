import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {expenseSample} from './samples.mjs';

/** Aggregate receipts and owned row identities survive an actual APP process restart. */
export async function verifyAggregateRestart({call, request, spec, attachment, restartApplication, state, save}) {
  if (state.passed) return;
  const base = spec.runtime + '/records', runtime = {formId: spec.formId, formVersion: spec.version};
  state.command ??= {runtime, state: 'DRAFT', idempotencyKey: randomUUID(),
    values: expenseSample(attachment, '123.45', {subject: '【计算验收】重启后的主从幂等回执'})};
  await save();
  if (!state.created) {
    state.created = await call(base, 'POST', state.command);
    state.before = await call(base + '/' + state.created.recordId); await save();
    assert.equal(state.before.values.total_amount, '123.45');
  }
  if (!state.restarted) {state.restart = await restartApplication(); state.restarted = true; await save();}
  const repeated = await call(base, 'POST', state.command);
  assert.equal(repeated.recordId, state.created.recordId); assert.equal(repeated.version, state.created.version);
  const after = await call(base + '/' + repeated.recordId);
  assert.deepEqual(after, state.before, 'Restart/retry preserves main and detail identities, values, versions and audit fields');
  const changed = await request(base, 'POST', {...state.command, values: {...state.command.values, discount_rate: '0.5'}});
  assert.equal(changed.status, 409);
  assert.deepEqual(await call(base + '/' + repeated.recordId), state.before);
  state.after = after; state.changedBodyStatus = changed.status; await save();
  state.deleteKey ??= randomUUID(); await save();
  await call(base + '/' + repeated.recordId + '?' + new URLSearchParams({formId: spec.formId, formVersion: String(spec.version),
    expectedVersion: String(after.version), idempotencyKey: state.deleteKey}), 'DELETE');
  assert.equal((await request(base + '/' + repeated.recordId)).status, 404);
  state.passed = true; state.temporaryRecordRemoved = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS: APP restart preserves aggregate receipt, owned row versions and unchanged-body retries');
}
