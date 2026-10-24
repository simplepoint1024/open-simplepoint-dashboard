import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';

/** Timeout at the DNA RPC boundary, followed by an identical durable command retry. */
export async function verifyCalculationRpcRecovery({call, request, runtime, formId, formVersion, withDnaUnavailable, state, save}) {
  if (state.passed) return;
  const records = runtime + '/records';
  state.command ??= {runtime: {formId, formVersion}, state: 'DRAFT', idempotencyKey: randomUUID(),
    values: {subject: 'RPC 恢复验收・' + randomUUID(), quantity: '4', rate: '5'}};
  await save();
  if (!state.failure) {
    await withDnaUnavailable(async () => {
      const started = Date.now();
      const response = await request(records, 'POST', state.command);
      state.failure = {status: response.status, elapsedMs: Date.now() - started};
      if (response.body) {
        const error = JSON.parse(response.body);
        state.failure.code = error.code; state.failure.messageKey = error.messageKey;
      }
      await save();
    });
  }
  assert([503, 504].includes(state.failure.status), 'Unavailable RPC requires an explicit temporary failure');
  assert(state.failure.messageKey?.startsWith('app.'), 'Stable translated domain failure');
  const result = await call(records, 'POST', state.command);
  assert.deepEqual(await call(records, 'POST', state.command), result);
  const record = await call(records + '/' + result.recordId);
  assert.equal(record.values.total, '21'); assert.equal(record.values.normalized_total, '31');
  assert.equal(record.version, 0, 'New DNA records start at version zero; retries must not advance it');
  assert.equal(record.version, result.version);
  const page = await call(records + '/query', 'POST', {page: 0, size: 100,
    filter: {fieldId: 'subject', operator: 'EQUALS', values: [state.command.values.subject], children: []}});
  assert.equal(page.total, 1); assert.equal(page.records[0].id, result.recordId);
  state.result = result; state.record = record; await save();
  state.deleteKey ??= randomUUID(); await save();
  const remove = records + '/' + result.recordId + '?' + new URLSearchParams({formId, formVersion: String(formVersion),
    expectedVersion: String(record.version), idempotencyKey: state.deleteKey});
  await call(remove, 'DELETE');
  assert.equal((await request(records + '/' + result.recordId)).status, 404);
  state.passed = true; state.temporaryRecordRemoved = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS: DNA RPC timeout, stable error, identical command recovery and one authoritative record');
}
