import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {officeWorkflow} from '../hr-office/workflows.mjs';

const taskName = '关联验收交易审核';

async function wait(load, test, label) {
  for (let attempt = 0; attempt < 80; attempt++) {
    const result = await load();
    if (test(result)) return result;
    assert(!['FAILED', 'ATTENTION', 'REJECTED'].includes(result?.status), label);
    await delay(500);
  }
  throw new Error('Timed out: ' + label);
}

/** Runs an actual scoped approval using an existing synthetic approver and a separate applicant. */
export async function verifyRelationWorkflow({applicant, manager, managerRoleId, state, save, topology}) {
  const {rel_customer: customer, rel_trade: trade} = state.forms;
  const alpha = state.samples.customer_alpha;
  const record = state.samples[topology + '_trade'];
  const runtime = {formId: trade.formId, formVersion: trade.version};
  const parent = new URLSearchParams({parentFormId: customer.formId, parentFormVersion: customer.version});
  const scoped = `${customer.runtime}/records/${alpha.id}/relations/trades/submissions`;
  const route = (suffix = '') => `${scoped}${suffix}?${parent}`;
  const grants = `/common/role-resource-configuration/roles/${encodeURIComponent(managerRoleId)}`;
  const currentGrant = async () => {
    const page = await applicant.call(`${grants}/resources?` + new URLSearchParams({page: 0, size: 50, keyword: trade.resourceCode}));
    const row = page.content.find(item => item.code === trade.resourceCode);
    assert(row?.grantable, 'Expected the exact synthetic trade page');
    return row;
  };
  const setGrant = async (row, granted) => {
    if (row.granted !== granted) {
      const response = await applicant.request(`${grants}/resource-changes`, 'PUT', {changes: [{resourceId: row.id,
        resourceCode: row.code, revision: row.revision, granted}]});
      assert.equal(response.status, 204);
    }
  };

  if (!state.workflow?.version) {
    const key = 'rel_trade_' + state.appId.replaceAll('-', '').slice(0, 12);
    const roles = await applicant.call('/common/roles?size=50');
    const approver = roles.content.find(role => role.id === managerRoleId);
    assert(approver?.authority, 'The sample approver role must exist in the current workspace');
    const definition = officeWorkflow({code: 'office_daily_report', name: '关联验收交易', description: '合成交易审批验收'},
      trade.formId, trade.version);
    definition.category = '表单关联验收';
    definition.bpmnXml = definition.bpmnXml.replaceAll(definition.key, key)
      .replaceAll('ROLE_Office_manager', () => approver.authority)
      .replaceAll('部门负责人确认日报', taskName);
    definition.key = key;
    if (!state.workflow) {
      const created = (await applicant.call('/workflow/definitions', 'POST', definition)).definition;
      state.workflow = {id: created.id, revision: created.draftRevision}; await save();
    }
    const workflow = state.workflow;
    const base = '/workflow/definitions/' + workflow.id;
    if (!workflow.draftSaved) {
      await applicant.call(base + '/draft', 'PUT', {...definition, expectedRevision: workflow.revision});
      workflow.revision++; workflow.draftSaved = true; await save();
    }
    const published = await applicant.call(base + '/publish', 'POST', {expectedRevision: workflow.revision, comment: '关联样例审批'});
    workflow.version = published.version ?? published.versionNumber ?? 1; await save();
  }

  state.approvals ??= {};
  const flow = state.approvals[topology] ??= {command: {runtime, recordId: record.id, expectedVersion: record.version,
    idempotencyKey: randomUUID(), definitionId: state.workflow.id, definitionVersion: state.workflow.version,
    title: `【关联验收】${topology} 客户交易`, values: {}, variables: {}, ccUserIds: []}};
  await save();
  const submission = suffix => applicant.call(route('/' + flow.id + suffix));
  const snapshot = () => applicant.call(`${trade.runtime}/submissions/${flow.id}/snapshot`);
  if (!flow.completed) {
    const receipt = await applicant.call(route(), 'POST', flow.command);
    assert.equal(receipt.recordId ?? record.id, record.id);
    assert.equal((await applicant.call(route(), 'POST', flow.command)).id, receipt.id);
    flow.id = receipt.id; await save();
    const started = await wait(() => submission(''), value => Boolean(value.processInstanceId), 'related workflow started');
    assert.equal(started.recordId, record.id);
    const frozen = (await snapshot()).record;
    assert.equal(frozen.values.customer, alpha.id);
    assert.equal(frozen.values.snapshot_address, alpha.values.address);
    assert.equal(frozen.values.items.length, 40);
    assert.equal(frozen.referenceSnapshots.find(item => item.relationId === 'customer')?.label, alpha.values.name);
    assert.equal(frozen.referenceSnapshots.filter(item => item.relationId === 'product').length, 40);
    flow.snapshot = frozen;
    if (!flow.taskId) {
      const task = await wait(async () => {
        const pending = await manager.call('/workflow/tasks/pending?size=100');
        return pending.content.find(item => item.processInstanceId === started.processInstanceId && item.name === taskName);
      }, Boolean, 'manager task');
      flow.taskId = task.id;
      flow.completion = {variables: {}, comment: '已审核关联交易样例。', idempotencyKey: randomUUID()}; await save();
    }
    const taskUrl = '/workflow/tasks/' + flow.taskId;
    const taskView = '/app' + taskUrl + '/business-form';
    const initial = await currentGrant();
    if (!flow.restoreGrant) {
      assert.equal(initial.granted, false, 'The test approver must start without this page grant');
      if (!flow.approverVerified) {
        assert.equal((await manager.request(taskView)).status, 403);
      }
      flow.restoreGrant = true; await save();
    }
    try {
      await setGrant(initial, true);
      if (!flow.approverVerified) {
        const visible = await manager.request(taskView);
        assert.equal(visible.status, 200, visible.body.slice(0, 300));
        const view = JSON.parse(visible.body);
        const projected = view.data ?? view;
        assert.equal(projected.submission.id, flow.id);
        assert(!Object.hasOwn(projected.record.values, 'snapshot_address'), 'Related source data needs target permission');
        assert(!(projected.record.referenceSnapshots ?? []).some(item => item.relationId === 'customer'), 'Unprivileged target labels stay private');
        flow.approverVerified = true; await save();
      }
      if (!flow.claimed) {
        await manager.call(taskUrl + '/claim', 'POST');
        flow.claimed = true; await save();
      }
      await manager.call(taskUrl + '/complete', 'POST', flow.completion);
      await manager.call(taskUrl + '/complete', 'POST', flow.completion);
      const final = await wait(() => submission(''), value => value.processStatus === 'COMPLETED', 'related workflow completed');
      const pending = await manager.call('/workflow/tasks/pending?size=100');
      assert(!pending.content.some(item => item.id === flow.taskId));
      flow.processInstanceId = final.processInstanceId;
    } finally {
      await setGrant(await currentGrant(), false);
      assert.equal((await currentGrant()).granted, false);
      flow.restoreGrant = false; await save();
    }
    flow.completed = true; await save();
  }
  assert.deepEqual((await snapshot()).record, flow.snapshot);
  assert.equal((await applicant.call(trade.runtime + '/records/' + record.id)).state, 'SUBMITTED');
  state.checks[topology]['related-approval-immutable-and-idempotent'] = {passedAt: new Date().toISOString(),
    details: 40, managerWithoutTargetGrant: true, status: 'COMPLETED'};
  await save();
  console.log('PASS ' + topology + ' related-approval-immutable-and-idempotent');
}

/** Compares live and immutable projections after a target edit, then restores the synthetic target. */
export async function verifyRelatedSnapshotStability({sessions, state, save}) {
  const {rel_trade: trade, rel_customer: customer} = state.forms;
  const alpha = state.samples.customer_alpha;
  const {call} = sessions.single;
  const target = customer.runtime + '/records/' + alpha.id;
  const patch = async values => {
    const current = await call(target);
    if (Object.entries(values).every(([name, value]) => current.values[name] === value)) return;
    return call(target, 'PUT', {runtime: {formId: customer.formId, formVersion: customer.version},
      values, state: current.state, expectedVersion: current.version, idempotencyKey: randomUUID()});
  };
  const changed = {name: alpha.values.name + '（更新）', address: '合成验收新地址'};
  const original = {name: alpha.values.name, address: alpha.values.address};
  const runtime = {formId: trade.formId, formVersion: trade.version};
  const observations = {};
  for (const [topology, client] of Object.entries(sessions)) {
    const result = await client.call(trade.runtime + '/references/customer/mapping', 'POST', {
      runtime, targetRecordId: alpha.id, applyDefaultMappingIds: [],
    });
    observations[topology] = result.observation;
  }
  try {
    await patch(changed);
    for (const [topology, client] of Object.entries(sessions)) {
      const flow = state.approvals[topology];
      const live = await client.call(trade.runtime + '/references/customer/live', 'POST', {runtime, targetRecordId: alpha.id});
      assert.equal(live.values.live_address.value, changed.address);
      const resolved = await client.call(trade.runtime + '/references/customer/resolve?' + new URLSearchParams(runtime), 'POST', [alpha.id]);
      assert(resolved.some(item => item.id === alpha.id && item.label === changed.name));
      const current = await client.call(trade.runtime + '/records/' + flow.command.recordId);
      assert.equal(current.values.customer, alpha.id);
      assert.equal(current.values.snapshot_address, original.address);
      const history = await client.call(trade.runtime + '/submissions/' + flow.id + '/snapshot');
      assert.deepEqual(history.record, flow.snapshot);
      const stale = await client.request(trade.runtime + '/records', 'POST', {runtime, state: 'DRAFT',
        idempotencyKey: randomUUID(), values: {name: '已过期快照拒绝', customer: alpha.id}, referenceMappings: {
          contractVersion: '1', confirmations: [{relationId: 'customer', observation: observations[topology], applyDefaultMappingIds: []}],
        }});
      assert.equal(stale.status, 409);
      assert.equal(JSON.parse(stale.body).code, 'RECORD_REFERENCE_CONFLICT');
    }
  } finally {
    await patch(original);
    const restored = await call(target);
    for (const [name, value] of Object.entries(original)) assert.equal(restored.values[name], value);
  }
  for (const topology of Object.keys(sessions)) state.checks[topology]['live-target-edit-retains-history'] = {
    passedAt: new Date().toISOString(), labelStableInHistory: true, targetRestored: true, staleWrite: 409,
  };
  await save();
  console.log('PASS both topologies live-target-edit-retains-history');
}
