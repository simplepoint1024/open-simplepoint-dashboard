import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {expenseSample, dailySample, deliverySample, assetsSample} from './samples.mjs';

/** Approves actual tasks under distinct actors; no direct engine-table writes or mocked completion. */
export async function verifyCalculationWorkflows({call, request, business, attachment, evidence, save, functionCases}) {
  evidence.flows ??= {}; evidence.checks ??= [];
  evidence.suffix ??= randomUUID().slice(0, 8); await save();
  const pass = async (name, detail = {}) => {evidence.checks.push({name, at: new Date().toISOString(), ...detail}); await save(); console.log('PASS ' + name);};
  const wait = async (load, accepted, label) => {
    for (let i = 0; i < 120; i++) {const value = await load(); if (accepted(value)) return value;
      assert(!['ATTENTION', 'FAILED'].includes(value.status), label + ' requires recovery: ' + JSON.stringify(value)); await delay(500);}
    throw new Error('Timed out: ' + label);
  };
  async function flow(label, code, values, approvals, expected) {
    const s = business.forms[code];
    const item = evidence.flows[label] ??= {command: {runtime: {formId: s.formId, formVersion: s.version}, idempotencyKey: randomUUID(),
      definitionId: s.workflowId, definitionVersion: s.workflowVersion, title: '【计算验收】' + label, values, variables: {}, ccUserIds: []}, approvals: []};
    item.before ??= Date.now();
    await save();
    if (item.status === 'COMPLETED') {
      const history = await call('employee', s.runtime + '/submissions/' + item.submissionId + '/snapshot');
      assert.deepEqual(history.record, item.snapshot);
      return item;
    }
    const first = await call('employee', s.runtime + '/submissions', 'POST', item.command);
    item.submissionId = first.id; await save();
    const repeated = await call('employee', s.runtime + '/submissions', 'POST', item.command); assert.equal(repeated.id, first.id);
    const submission = await wait(() => call('employee', s.runtime + '/submissions/' + first.id), value => Boolean(value.processInstanceId), label);
    item.processInstanceId = submission.processInstanceId; item.recordId = submission.recordId; await save();
    const snapshot = await call('employee', s.runtime + '/submissions/' + first.id + '/snapshot');
    for (const [field, value] of Object.entries(expected)) assert.equal(snapshot.record.values[field], value, label + ' final snapshot ' + field);
    item.snapshot = snapshot.record;
    for (let index = 0; index < approvals.length; index++) {
      const [actor, taskName] = approvals[index];
      let approval = item.approvals[index];
      if (!approval) {
        const task = await wait(async () => {
          const tasks = await call(actor, '/workflow/tasks/pending?size=100');
          return tasks.content.find(task => task.processInstanceId === submission.processInstanceId && task.name === taskName) ?? {};
        }, task => Boolean(task.id), label + ' / ' + taskName);
        approval = {actor, taskId: task.id, taskName: task.name, command: {variables: {}, comment: '已核对服务端计算结果、业务明细和验收凭证。', idempotencyKey: randomUUID()}};
        item.approvals.push(approval); await save();
      }
      // A verifier can stop after the server commits but before its local checkpoint is saved.
      // Replay only the persisted completion intent; a different actor/intent must still fail.
      let view;
      if (approval.completed) {
        view = await call(actor, '/app/workflow/instances/' + submission.processInstanceId + '/business-form');
      } else {
        const response = await request(actor, '/app/workflow/tasks/' + approval.taskId + '/business-form');
        if (response.status === 404) {
          const replay = await call(actor, '/workflow/tasks/' + approval.taskId + '/complete', 'POST', approval.command);
          assert(['RUNNING', 'COMPLETED'].includes(replay.status));
          approval.completed = true; approval.recoveredCommittedReceipt = true; await save();
          view = await call(actor, '/app/workflow/instances/' + submission.processInstanceId + '/business-form');
        } else {
          assert.equal(response.status, 200, response.body);
          const envelope = JSON.parse(response.body); view = Object.hasOwn(envelope, 'data') ? envelope.data : envelope;
        }
      }
      assert.equal(view.submission.id, submission.id);
      for (const [field, value] of Object.entries(expected)) assert.equal(view.record.values[field], value, 'approver committed ' + field);
      if (!approval.completed) {
        const denied = await request('employee', '/workflow/tasks/' + approval.taskId + '/complete', 'POST', {
          variables: {}, comment: '申请人不得绕过审批角色', idempotencyKey: randomUUID()});
        assert([403, 404].includes(denied.status), 'Applicant must not approve their own task');
        await call(actor, '/workflow/tasks/' + approval.taskId + '/claim', 'POST');
        const completed = await call(actor, '/workflow/tasks/' + approval.taskId + '/complete', 'POST', approval.command);
        assert(['RUNNING', 'COMPLETED'].includes(completed.status));
        const retried = await call(actor, '/workflow/tasks/' + approval.taskId + '/complete', 'POST', approval.command);
        assert.equal(retried.status, completed.status);
        approval.completed = true; await save();
      }
    }
    const final = await wait(() => call('employee', s.runtime + '/submissions/' + submission.id), value => value.processStatus === 'COMPLETED', label + ' completed');
    item.status = final.processStatus;
    const history = await call('employee', s.runtime + '/submissions/' + submission.id + '/snapshot');
    assert.deepEqual(history.record, item.snapshot, 'Approval must retain the accepted calculation snapshot');
    const record = await call('employee', s.runtime + '/records/' + final.recordId);
    assert.equal(record.state, 'SUBMITTED');
    for (const [field, value] of Object.entries(expected)) assert.equal(record.values[field], value);
    await pass(label, {submissionId: final.id, processInstanceId: final.processInstanceId, recordId: final.recordId,
      expected, approvals: item.approvals.map(({actor, taskId, taskName}) => ({actor, taskId, taskName}))});
    return item;
  }
  if (functionCases) {
    const queue = [...functionCases];
    const worker = async () => {for (let item; (item = queue.shift());) {
      const timed = ['TODAY', 'NOW'].includes(item.name), expected = item.type === 'BOOLEAN' ? item.expected.toUpperCase() : item.expected;
      const label = 'function-' + item.name;
      const before = evidence.flows[label]?.before ?? Date.now();
      const result = await flow(label, 'cal_ledger', {function_name: item.name, expression: item.expression,
        expected: timed ? '服务端提交事务时钟' : expected, result_type: item.type, reviewer: '公式验收负责人',
        conclusion: '逐函数核对可信提交结果、审批人待办与已办不可变快照。'}, [['manager', '公式验算确认']], timed ? {} : {actual: expected});
      result.before ??= before; result.after ??= Date.now();
      if (item.name === 'TODAY') assert.equal(result.snapshot.values.actual, new Date(before).toISOString().slice(0, 10));
      if (item.name === 'NOW') {
        const millis = Date.parse(result.snapshot.values.actual + 'Z');
        assert(millis >= before - 2000 && millis <= result.after + 2000);
      }
      await save();
    }};
    // Four independent aggregates exercise ordinary concurrent users without unbounded fan-out.
    const workers = await Promise.allSettled(Array.from({length: 4}, worker));
    const failure = workers.find(result => result.status === 'rejected');
    if (failure) throw failure.reason;
    return;
  }
  for (const amount of ['4800', '5000', '6800']) await flow('expense-' + amount, 'cal_expense', expenseSample(attachment, amount), [
    ['manager', '部门负责人审核'], ...(Number(amount) > 5000 ? [['director', '分管负责人审批']] : []), ['finance', '财务复核'],
  ], {total_amount: amount, base_amount: amount});
  await flow('expense-currency-route', 'cal_expense', expenseSample(attachment, '4800', {currency: 'USD', exchange_rate: '1.4'}),
    [['manager', '部门负责人审核'], ['director', '分管负责人审批'], ['finance', '财务复核']], {total_amount: '4800', base_amount: '6720'});
  await flow('daily-overtime', 'cal_daily', dailySample(), [['manager', '负责人确认日报'], ['director', '超时工作复核']],
    {total_hours: '8.75', overtime_hours: '0.75'});
  await flow('delivery-parallel', 'cal_delivery', deliverySample(attachment), [['manager', '交付负责人评审'], ['finance', '测试质量复核']], {weighted_score: '86'});
  await flow('assets-standard', 'cal_assets', assetsSample('FLOW-001-' + evidence.suffix), [['manager', '部门预算审核'], ['administration', '采购核实'], ['finance', '资产登记确认']],
    {total_amount: '2900', remaining_budget: '2100'});
  await flow('assets-budget-exception', 'cal_assets', {...assetsSample('FLOW-002-' + evidence.suffix), budget: '2000'},
    [['manager', '部门预算审核'], ['director', '超预算加签'], ['administration', '采购核实'], ['finance', '资产登记确认']], {total_amount: '2900', remaining_budget: '-900'});
  await flow('function-ledger-confirmation', 'cal_ledger', {function_name: 'SUM', expression: 'SUM(1,2,3)', expected: '6', result_type: 'NUMBER', reviewer: '测试负责人',
    conclusion: '核验公式台账实际入库值，确认受控定义、提交快照和审批结果一致。'}, [['manager', '公式验算确认']], {actual: '6'});
}
