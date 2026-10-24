import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

/** Ordinary read/write/preview admission while a real 65-aggregate publication owns maintenance. */
export async function verifyMaintenanceAccess({call, request, appId, spec, state, save}) {
  const base = `/app/applications/${appId}/forms/${spec.formId}`, records = spec.runtime + '/records';
  if (state.passed) return;
  if (!state.original) {
    state.original = await call(base + '/draft'); state.runtime = await call(spec.runtime);
    state.before = await call(records + '/query', 'POST', {page: 0, size: 100, sorts: [{fieldId: 'subject', direction: 'ASC'}]});
    assert.equal(state.before.total, 65); state.key = randomUUID(); await save();
  }
  if (!state.version) {
    const document = structuredClone(state.original.document);
    document.fields.find(field => field.fieldId === 'total').calculation.expression += '+1000';
    const draft = await call(base + '/draft', 'PUT', {expectedRevision: state.original.revision, document});
    state.version = (await call(base + '/versions', 'POST', {expectedRevision: draft.revision})).version; await save();
  }
  try {
    if (!state.releaseId) {
      const accepted = await call(base + `/versions/${state.version}/publication`, 'POST', {pageId: spec.pageId, idempotencyKey: state.key});
      assert(accepted.progress); assert.notEqual(accepted.pageBinding.status, 'ACTIVE');
      state.releaseId = accepted.pageBinding.id; state.accepted = accepted; await save();
    }
    if (!state.checks) {
      const record = state.before.records[0], runtime = {formId: spec.formId, formVersion: state.runtime.formVersion};
      const write = {runtime, expectedVersion: record.version, state: 'DRAFT', idempotencyKey: randomUUID(), values: {subject: 'MUST-NOT-WRITE'}};
      const cases = [
        ['detail-read', records + '/' + record.id, 'GET'],
        ['query-count', records + '/query', 'POST', {page: 0, size: 10}],
        ['update', records + '/' + record.id, 'PUT', write],
        ['create', records, 'POST', {...write, expectedVersion: undefined, idempotencyKey: randomUUID(), values: {subject: 'MUST-NOT-CREATE', quantity: '3', rate: '4'}}],
        ['preview', records + '/preview', 'POST', {...write, recordId: record.id}],
        ['delete', records + '/' + record.id + '?' + new URLSearchParams({formId: spec.formId, formVersion: String(runtime.formVersion),
          expectedVersion: String(record.version), idempotencyKey: randomUUID()}), 'DELETE'],
      ];
      const results = await Promise.all(cases.map(async ([name, path, method, body]) => {
        const response = await request(path, method, body);
        assert.equal(response.status, 409, name + ': ' + response.body);
        const error = JSON.parse(response.body); assert.equal(error.code, 'PUBLICATION_IN_PROGRESS');
        return {name, status: response.status, code: error.code, messageKey: error.messageKey};
      }));
      state.checks = results; await save();
    }
  } finally {
    if (state.releaseId && !state.cancelled) {
      for (let i = 0; i < 150; i++) {
        const result = await call(base + '/releases/' + state.releaseId + '/cancel', 'POST');
        state.cancellation = result; await save();
        if (result.pageBinding.status === 'CANCELLED') {state.cancelled = true; await save(); break;}
        assert.notEqual(result.pageBinding.status, 'ACTIVE'); await delay(100);
      }
      assert(state.cancelled);
    }
    if (state.cancelled && !state.restoredDraft) {
      const current = await call(base + '/draft');
      const restored = await call(base + '/draft', 'PUT', {expectedRevision: current.revision, document: state.original.document});
      assert.deepEqual(restored.document, state.original.document); state.restoredDraft = true; await save();
    }
  }
  assert.deepEqual(await call(records + '/query', 'POST', {page: 0, size: 100, sorts: [{fieldId: 'subject', direction: 'ASC'}]}), state.before);
  assert.equal((await call(spec.runtime)).formVersion, state.runtime.formVersion);
  const replay = await call(base + '/releases/' + state.releaseId + '/cancel', 'POST');
  assert.equal(replay.pageBinding.status, 'CANCELLED');
  assert.equal((await call(spec.runtime)).formVersion, state.runtime.formVersion);
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS concurrent reads/writes/previews denied during publication; cancel/replay restores exact aggregates and draft');
}
