import assert from 'node:assert/strict';
import {setTimeout as delay} from 'node:timers/promises';

/** Resolve an actual previously accepted duplicate without changing its intent or prior business data. */
export async function verifyDurableSubmissionRejection({call, request, spec, negative, existingRecordId, state, save}) {
  const base = spec.runtime + '/submissions';
  state.before ??= await call(spec.runtime + '/records/' + existingRecordId);
  state.original ??= await call(base + '/' + negative.submissionId); await save();
  assert.equal(state.original.processInstanceId, null); assert.equal(state.original.recordId, null);
  const first = await call(base + '/' + negative.submissionId + '/retry', 'POST', {});
  let failed = first;
  for (let i = 0; i < 120 && failed.step !== 'TERMINAL'; i++) {
    await delay(500); failed = await call(base + '/' + negative.submissionId);
  }
  assert.equal(failed.step, 'TERMINAL'); assert.equal(failed.status, 'FAILED');
  assert.equal(failed.errorCode, 'RECORD_OPERATION_REJECTED');
  assert.equal(failed.processInstanceId, null); assert.equal(failed.recordId, null);
  assert.deepEqual(await call(base + '/' + negative.submissionId + '/retry', 'POST', {}), failed);
  assert.deepEqual(await call(base, 'POST', negative.command), failed);
  const changed = await request(base, 'POST', {...negative.command, title: negative.command.title + 'changed'});
  assert.equal(changed.status, 409);
  const snapshot = await request(base + '/' + negative.submissionId + '/snapshot'); assert.equal(snapshot.status, 404);
  assert.deepEqual(await call(spec.runtime + '/records/' + existingRecordId), state.before);
  const matches = await call(spec.runtime + '/records/query', 'POST', {page: 0, size: 100,
    filter: {operator: 'EQUALS', fieldId: 'request_no', values: [negative.command.values.request_no], children: []}});
  assert.equal(matches.total, 1); assert.equal(matches.records[0].id, existingRecordId);
  const history = await call(base + '?size=100');
  const row = history.content.find(row => row.id === failed.id); assert(row);
  assert.equal(row.status, 'FAILED'); assert(!row.canRetry && !row.canSnapshot && !row.canResubmit);
  Object.assign(state, {passed: true, terminal: failed, currentRecordUnchanged: true, existingRecordCount: matches.total,
    changedIntentStatus: changed.status, noSnapshotStatus: snapshot.status, catalog: row, completedAt: new Date().toISOString()});
  await save(); console.log('PASS durable terminal rejection, exact-intent replay, no workflow/snapshot/duplicate and unchanged original record');
}
