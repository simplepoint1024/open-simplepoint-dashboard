import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {dailySample} from './samples.mjs';

/** Actual rejection/withdrawal/cancellation and version-pinned APP resubmission with copied history. */
export async function verifyWorkflowLifecycle({call, request, spec, copiedUserId, state, save}) {
  assert(typeof copiedUserId === 'string' && copiedUserId.length > 0, 'An actual copied-user identity is required');
  state.cases ??= {};
  const base = spec.runtime + '/submissions';
  const wait = async (read, accept, name) => {
    for (let i = 0; i < 120; i++) {const value = await read(); if (accept(value)) return value; await delay(500);}
    assert.fail('Timed out ' + name);
  };
  const deny = async (actor, path, body) => {
    const response = await request(actor, path, 'POST', body); assert([400, 403, 404, 409].includes(response.status));
    if (!response.body) {
      assert.equal(response.status, 403, 'Only the outer platform authorization filter may return an empty denial');
      return {status: response.status, boundary: 'platform-authorization'};
    }
    const error = JSON.parse(response.body); assert(error.messageKey); return {status: response.status, code: error.code};
  };
  async function start(item, values) {
    item.command ??= {runtime: {formId: spec.formId, formVersion: spec.version}, idempotencyKey: randomUUID(),
      definitionId: spec.workflowId, definitionVersion: spec.workflowVersion, title: '【计算验收】流程生命周期',
      values, variables: {}, ccUserIds: [copiedUserId]}; await save();
    if (!item.submissionId) {item.submissionId = (await call('employee', base, 'POST', item.command)).id; await save();}
    const submission = await wait(() => call('employee', base + '/' + item.submissionId), value => Boolean(value.processInstanceId), 'start');
    item.instanceId = submission.processInstanceId; item.recordId = submission.recordId;
    item.snapshot ??= (await call('employee', base + '/' + item.submissionId + '/snapshot')).record; await save();
    return submission;
  }
  for (const [kind, actor, operation, status] of [
    ['reject', 'manager', 'reject', 'REJECTED'], ['withdraw', 'employee', 'withdraw', 'WITHDRAWN'], ['cancel', 'admin', 'cancel', 'CANCELED'],
  ]) {
    const item = state.cases[kind] ??= {};
    if (item.passed) continue;
    const sample = dailySample(); sample.work_items[1].values.hours = '1.5';
    await start(item, sample); assert.equal(item.snapshot.values.total_hours, '7');
    if (!item.terminal) {
      const task = await wait(async () => (await call('manager', '/workflow/tasks/pending?size=100')).content
        .find(t => t.processInstanceId === item.instanceId) ?? {}, value => Boolean(value.id), kind + ' pending');
      item.taskId = task.id;
      const path = kind === 'reject' ? '/workflow/tasks/' + task.id + '/reject' : '/workflow/instances/' + item.instanceId + '/' + operation;
      item.action ??= {reason: '补齐验收说明后重新提交・日本語😀', idempotencyKey: randomUUID()}; await save();
      item.denied = await deny(kind === 'withdraw' ? 'admin' : 'employee', path,
        {reason: '不符合当前角色的操作', idempotencyKey: randomUUID()});
      if (kind === 'reject') item.emptyReason = await deny('manager', path, {reason: ' ', idempotencyKey: randomUUID()});
      const completed = await call(actor, path, 'POST', item.action); assert.equal(completed.status, status);
      assert.equal((await call(actor, path, 'POST', item.action)).status, status);
      item.terminal = true; await save();
    }
    await wait(() => call('employee', base + '/' + item.submissionId), value => value.processStatus === status, kind + ' fact');
    const copied = await call('finance', '/workflow/instances/copied?size=100');
    assert(copied.content.some(instance => instance.id === item.instanceId));
    const copyView = await call('finance', '/app/workflow/instances/' + item.instanceId + '/business-form');
    assert.deepEqual(copyView.record.values, item.snapshot.values);
    const prepared = await call('employee', base + '/preparation?' + new URLSearchParams({definitionId: spec.workflowId,
      definitionVersion: String(spec.workflowVersion), previousSubmissionId: item.submissionId}));
    assert.equal(prepared.previousSubmissionId, item.submissionId);
    const next = item.resubmission ??= {}; await save();
    if (!next.command) {
      const revised = dailySample(); revised.work_items[1].values.hours = '2.5';
      let values;
      if (prepared.copyOnResubmission) values = revised;
      else values = {work_items: prepared.record.values.work_items.map((row, index) => ({id: row.id, version: row.version,
        values: {hours: index === 0 ? '5.5' : '2.5'}}))};
      next.command = {...item.command, idempotencyKey: randomUUID(), previousSubmissionId: item.submissionId,
        recordId: prepared.copyOnResubmission ? undefined : item.recordId, expectedVersion: prepared.copyOnResubmission ? undefined : prepared.record.version,
        title: '【计算验收】' + kind + '修订重提', values}; await save();
    }
    await start(next); assert.notEqual(next.instanceId, item.instanceId); assert.equal(next.snapshot.values.total_hours, '8');
    if (!next.approved) {
      const task = await wait(async () => (await call('manager', '/workflow/tasks/pending?size=100')).content
        .find(t => t.processInstanceId === next.instanceId) ?? {}, value => Boolean(value.id), kind + ' resubmitted task');
      next.taskId = task.id; next.approval ??= {variables: {}, comment: '已核对修订后 8 小时及原轮次历史', idempotencyKey: randomUUID()}; await save();
      await call('manager', '/workflow/tasks/' + task.id + '/claim', 'POST');
      assert.equal((await call('manager', '/workflow/tasks/' + task.id + '/complete', 'POST', next.approval)).status, 'COMPLETED');
      next.approved = true; await save();
    }
    await wait(() => call('employee', base + '/' + next.submissionId), value => value.processStatus === 'COMPLETED', kind + ' final');
    assert.deepEqual((await call('employee', base + '/' + item.submissionId + '/snapshot')).record, item.snapshot);
    assert.deepEqual((await call('employee', base + '/' + next.submissionId + '/snapshot')).record, next.snapshot);
    const oldDiagram = await call('employee', '/workflow/instances/' + item.instanceId + '/diagram');
    const nextDiagram = await call('employee', '/workflow/instances/' + next.instanceId + '/diagram');
    assert.equal(oldDiagram.instance.status, status); assert.equal(nextDiagram.instance.status, 'COMPLETED');
    item.oldDiagram = oldDiagram; item.newDiagram = nextDiagram; item.passed = true; await save();
    console.log('PASS workflow ' + kind + ', copied history and calculation-preserving resubmission');
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
