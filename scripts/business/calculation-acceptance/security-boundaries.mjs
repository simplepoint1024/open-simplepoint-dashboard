import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {expenseSample} from './samples.mjs';

/** Actual published form/flow boundaries, without granting a test actor broader privileges. */
export async function verifySecurityBoundaries({call, request, foreignRequest, spec, appId, attachment, instanceId, directModel, state, save}) {
  const records = spec.runtime + '/records', submissions = spec.runtime + '/submissions';
  if (state.passed) return;
  if (state.deleted) {
    state.previousAttempts ??= [];
    state.previousAttempts.push({recordId: state.recordId, checks: state.checks, deleted: true});
    delete state.recordId; delete state.command; delete state.audit; delete state.deleted;
  }
  state.checks = [];
  const deny = async (name, response, statuses = [403, 404]) => {
    assert(statuses.includes(response.status), name + ': ' + response.status + ' ' + response.body);
    const error = response.body ? JSON.parse(response.body) : {};
    state.checks.push({name, status: response.status, code: error.code, messageKey: error.messageKey}); await save();
  };
  await deny('no-design-authority', await request('employee', `/app/applications/${appId}/forms/${spec.formId}/draft`));
  await deny('foreign-tenant-context', await foreignRequest(records + '/query', 'POST', {page: 0, size: 20}));
  const directRecord = (await call('employee', records + '/query', 'POST', {page: 0, size: 1})).records[0];
  assert(directRecord);
  const directBefore = await call('employee', records + '/' + directRecord.id);
  for (const endpoint of ['create', 'update', 'transaction']) {
    const common = {...directModel, writePolicyVersion: 1, idempotencyKey: randomUUID()};
    const update = {recordId: directRecord.id, expectedVersion: directBefore.version, values: {base_amount: '1'}};
    const command = endpoint === 'create' ? {...common, values: {subject: 'FORGED', base_amount: '1'}}
      : endpoint === 'update' ? {...common, ...update}
        : {...common, timeoutMs: 30_000, mutations: [{type: 'UPDATE', reference: 'forged-result', ...update}]};
    const response = await request('employee', '/dna/internal/dna/runtime/records/' + endpoint, 'POST', command);
    await deny('internal-dna-' + endpoint, response, [403]);
    assert.equal(JSON.parse(response.body).code, 'TENANT_CONTEXT_REQUIRED', 'External headers cannot manufacture the trusted internal context');
    assert.deepEqual(await call('employee', records + '/' + directRecord.id), directBefore);
  }
  await deny('nonparticipant-workflow-diagram', await request('administration', '/workflow/instances/' + instanceId + '/diagram'));
  await deny('nonparticipant-business-snapshot', await request('administration', '/app/workflow/instances/' + instanceId + '/business-form'));
  const historyBefore = await call('employee', submissions + '?size=100');
  const normal = expenseSample(attachment, '6800', {subject: 'SECURITY-REJECTED-INTENT'});
  const start = {runtime: {formId: spec.formId, formVersion: spec.version}, definitionId: spec.workflowId,
    definitionVersion: spec.workflowVersion, title: '非法选路输入必须拒绝', values: normal, variables: {}, ccUserIds: []};
  for (const [name, patch] of [['forged-mapped-process-amount', {variables: {amount: '1'}}],
    ['forged-root-result', {values: {...normal, base_amount: '1'}}],
    ['forged-child-result', {values: {...normal, expense_items: [{values: {...normal.expense_items[0].values, amount: '1'}}]}}]]) {
    await deny(name, await request('employee', submissions, 'POST', {...start, ...patch, idempotencyKey: randomUUID()}), [400]);
  }
  assert.deepEqual(await call('employee', submissions + '?size=100'), historyBefore, 'Invalid intent cannot create an accepted journal or workflow');
  const submitted = (await call('employee', '/app/workflow/instances/' + instanceId + '/business-form')).record;
  const current = await call('employee', records + '/' + submitted.id); assert.equal(current.state, 'SUBMITTED');
  for (const [name, values, targetState] of [
    ['submitted-parent-lock', {subject: 'MUST-NOT-CHANGE'}, 'SUBMITTED'],
    ['submitted-child-lock', {expense_items: [{id: current.values.expense_items[0].id, version: current.values.expense_items[0].version,
      values: {description: 'MUST-NOT-CHANGE'}}]}, 'SUBMITTED'],
    ['lifecycle-downgrade', {}, 'DRAFT'],
    ['system-audit-forgery', {createdBy: 'another-user'}, 'SUBMITTED'],
  ]) {
    await deny(name, await request('employee', records + '/' + current.id, 'PUT', {runtime: start.runtime,
      expectedVersion: current.version, state: targetState, idempotencyKey: randomUUID(), values}), [400, 403, 409]);
    assert.deepEqual(await call('employee', records + '/' + current.id), current);
  }
  if (!state.command) {
    state.command = {runtime: start.runtime, state: 'DRAFT', idempotencyKey: randomUUID(),
      values: expenseSample(attachment, '2', {subject: '【计算验收】异部门隔离临时记录', attachments: '[]', invoice_count: 0})}; await save();
  }
  if (!state.recordId) {state.recordId = (await call('admin', records, 'POST', state.command)).recordId; await save();}
  if (!state.deleted) {
    try {
      const record = await call('admin', records + '/' + state.recordId); state.audit = record.systemValues; await save();
      for (const actor of ['employee', 'manager']) {
        await deny(actor + '-foreign-owner-department-read', await request(actor, records + '/' + state.recordId));
        const query = await call(actor, records + '/query', 'POST', {page: 0, size: 20,
          filter: {operator: 'EQUALS', fieldId: 'subject', values: [state.command.values.subject], children: []}});
        assert.equal(query.total, 0); assert.deepEqual(query.records, []);
      }
    } finally {
      const record = await call('admin', records + '/' + state.recordId);
      await call('admin', records + '/' + state.recordId + '?' + new URLSearchParams({formId: spec.formId,
        formVersion: String(spec.version), expectedVersion: String(record.version), idempotencyKey: randomUUID()}), 'DELETE');
      state.deleted = true; await save();
    }
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS no design/internal/foreign tenant/department/nonparticipant access and no forged workflow routing');
}
