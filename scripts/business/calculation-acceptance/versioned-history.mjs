import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';

/** Public APIs only: publish changed calculation/layout while an old approval remains pending. */
export async function verifyVersionedHistory({call, request, appId, spec, completed, state, save}) {
  const base = `/app/applications/${appId}/forms/${spec.formId}`;
  const immutable = async () => (await call('admin', base + '/versions')).find(item => item.version === spec.version);
  const wait = async (read, accept, label) => {
    for (let i = 0; i < 150; i++) {const value = await read(); if (accept(value)) return value; await delay(500);}
    assert.fail('Timed out: ' + label);
  };
  if (!state.original) {
    state.original = await immutable(); assert(state.original);
    state.oldCompleted = await call('manager', '/app/workflow/instances/' + completed.instanceId + '/business-form');
    state.oldWorkflow = await call('admin', '/workflow/definitions/' + spec.workflowId + '/versions/' + spec.workflowVersion);
    await save();
  }
  const pending = state.pending ??= {}; await save();
  if (!pending.command) {
    const values = structuredClone(completed.snapshot.values);
    for (const field of state.original.document.fields) if (field.calculation) delete values[field.fieldId];
    for (const detail of state.original.document.details ?? []) {
      values[detail.detailId] = (values[detail.detailId] ?? []).map(row => ({values: row.values}));
    }
    values.subject = '跨版本待办・日本語😀-' + randomUUID().slice(0, 8);
    pending.command = {runtime: {formId: spec.formId, formVersion: spec.version}, idempotencyKey: randomUUID(),
      definitionId: spec.workflowId, definitionVersion: spec.workflowVersion, title: values.subject,
      values, variables: {}, ccUserIds: []}; await save();
  }
  if (!pending.submissionId) {
    pending.submissionId = (await call('employee', spec.runtime + '/submissions', 'POST', pending.command)).id; await save();
  }
  if (!pending.before) {
    const accepted = await wait(() => call('employee', spec.runtime + '/submissions/' + pending.submissionId),
      value => Boolean(value.processInstanceId), 'v1 pending workflow');
    pending.instanceId = accepted.processInstanceId; pending.recordId = accepted.recordId;
    const task = await wait(async () => (await call('manager', '/workflow/tasks/pending?size=100')).content
      .find(item => item.processInstanceId === pending.instanceId), Boolean, 'v1 pending approval');
    pending.taskId = task.id;
    pending.before = await call('manager', '/app/workflow/tasks/' + task.id + '/business-form');
    assert.equal(pending.before.record.values.total, '21'); assert.equal(pending.before.record.values.normalized_total, '31');
    await save();
  }
  if (!state.document) {
    state.document = structuredClone(state.original.document);
    const calculated = state.document.fields.find(field => field.fieldId === 'total');
    calculated.calculation.expression = '([quantity]*[rate]+1)*2';
    calculated.label = '计算结果（第二版）';
    const children = state.document.scenes.find(scene => scene.type === 'MAIN').root.children;
    const total = children.findIndex(node => node.fieldId === 'total');
    assert(total >= 0); children.unshift(...children.splice(total, 1));
    await save();
  }
  if (!state.version) {
    let draft = await call('admin', base + '/draft');
    if (JSON.stringify(draft.document) !== JSON.stringify(state.document)) {
      draft = await call('admin', base + '/draft', 'PUT', {expectedRevision: draft.revision, document: state.document});
    }
    state.version = (await call('admin', base + '/versions', 'POST', {expectedRevision: draft.revision})).version;
    state.key = randomUUID(); await save();
  }
  state.publications ??= [];
  if (!state.active) {
    await wait(async () => {
      const result = await call('admin', base + `/versions/${state.version}/publication`, 'POST', {
        pageId: spec.pageId, idempotencyKey: state.key});
      state.publications.push(result); await save();
      assert(!['FAILED', 'CANCELLED', 'ATTENTION'].includes(result.pageBinding?.status), 'Publication must remain recoverable');
      return result;
    }, value => value.pageBinding?.status === 'ACTIVE', 'v2 activation');
    state.active = true; await save();
  }
  const runtime = await call('employee', spec.runtime); assert.equal(runtime.formVersion, state.version);
  assert.equal(runtime.document.fields.find(field => field.fieldId === 'total').label, '计算结果（第二版）');
  assert.deepEqual(await immutable(), state.original);
  const records = await call('employee', spec.runtime + '/records/query', 'POST', {page: 0, size: 100});
  assert(records.total > 0 && records.total <= 100);
  for (const row of records.records) {
    assert.equal(Number(row.values.total), (Number(row.values.quantity) * Number(row.values.rate) + 1) * 2);
    assert.equal(Number(row.values.normalized_total), Number(row.values.total) + 10);
  }
  state.backfilled = records.records.map(row => ({id: row.id, version: row.version, total: row.values.total, normalized: row.values.normalized_total}));
  const frozen = async () => {
    const old = await call('manager', '/app/workflow/instances/' + completed.instanceId + '/business-form');
    assert(state.oldCompleted.form?.document); assert(pending.before.form?.document);
    assert.deepEqual(old.record, state.oldCompleted.record); assert.deepEqual(old.form, state.oldCompleted.form);
    const waiting = await call('manager', '/app/workflow/instances/' + pending.instanceId + '/business-form');
    assert.deepEqual(waiting.record, pending.before.record); assert.deepEqual(waiting.form, pending.before.form);
    assert.equal(waiting.form.formVersion, spec.version);
    assert.notEqual(waiting.form.document.fields.find(field => field.fieldId === 'total').label, '计算结果（第二版）');
    const snapshot = await call('employee', spec.runtime + '/submissions/' + pending.submissionId + '/snapshot');
    assert.deepEqual(snapshot.record, pending.before.record);
    return {old, waiting};
  };
  await frozen();
  if (!state.workflowVersion) {
    const wf = '/workflow/definitions/' + spec.workflowId;
    const draft = await call('admin', wf), contract = structuredClone(draft.formContract);
    contract.startBinding.version = state.version;
    await call('admin', wf + '/draft', 'PUT', {expectedRevision: draft.definition.draftRevision,
      name: draft.definition.name, category: draft.definition.category, description: draft.definition.description,
      bpmnXml: draft.bpmnXml, formContract: contract});
    state.workflowVersion = (await call('admin', wf + '/publish', 'POST', {
      expectedRevision: draft.definition.draftRevision + 1, comment: '验证跨版本待办与不可变公式结果'})).version; await save();
  }
  assert.deepEqual(await call('admin', '/workflow/definitions/' + spec.workflowId + '/versions/' + spec.workflowVersion), state.oldWorkflow);
  // Old pages must never be able to choose the original formula after activation.
  if (!state.oldPageAttempt) {
    state.oldPageCommand ??= {runtime: {formId: spec.formId, formVersion: spec.version}, state: 'DRAFT',
      idempotencyKey: randomUUID(), values: {...pending.command.values, subject: '旧页公式绕过探测', total: '999999', normalized_total: '999999'}};
    await save();
    const response = await request('employee', spec.runtime + '/records', 'POST', state.oldPageCommand);
    assert([200, 201, 400, 403, 409].includes(response.status));
    const payload = JSON.parse(response.body), value = payload.data ?? payload;
    state.oldPageAttempt = {status: response.status, code: value.code};
    if (response.status < 300) {
      const row = await call('employee', spec.runtime + '/records/' + value.recordId);
      assert.equal(row.values.total, '42'); assert.equal(row.values.normalized_total, '52');
      state.oldPageAttempt.recordId = row.id; state.oldPageAttempt.currentFormula = row.values.total;
    } else assert(value.messageKey);
    await save();
  }
  await frozen(); state.readyForBrowser = true; await save();
  console.log('PASS new formula backfill, old pending/completed snapshots, renamed field/layout and old-page guard');
}
