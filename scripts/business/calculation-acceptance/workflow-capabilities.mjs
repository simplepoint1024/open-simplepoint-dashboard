import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {deliverySample} from './samples.mjs';
import {withAcceptanceDiagram} from './workflow-diagram.mjs';

const task = (id, name, role = 'manager', loop = '') => `<bpmn:userTask id="${id}" name="${name}" flowable:candidateGroups="ROLE_Office_${role}">${loop}</bpmn:userTask>`;
const edge = (id, from, to, condition) => `<bpmn:sequenceFlow id="${id}" sourceRef="${from}" targetRef="${to}">${condition
  ? `<bpmn:conditionExpression xsi:type="bpmn:tFormalExpression"><![CDATA[${condition}]]></bpmn:conditionExpression>` : ''}</bpmn:sequenceFlow>`;
const subprocess = (id, content, target) => `<bpmn:subProcess id="${id}"><bpmn:startEvent id="${id}_start"/>${content}<bpmn:endEvent id="${id}_end"/>`
  + edge(id + '_in', id + '_start', target) + edge(id + '_out', target, id + '_end') + '</bpmn:subProcess>';

export function workflowCapabilityCases(spec, suffix) {
  const cases = [];
  const add = (name, body, approvals, firstTaskCount = 1, extra = '') => {
    const key = 'calc_' + name.replaceAll('-', '_') + '_' + suffix;
    const laneEnd = body.startsWith('<bpmn:laneSet') ? body.indexOf('</bpmn:laneSet>') + '</bpmn:laneSet>'.length : 0;
    const lanes = body.slice(0, laneEnd); body = body.slice(laneEnd);
    const artifactStart = body.indexOf('<bpmn:group ');
    const artifacts = artifactStart < 0 ? '' : body.slice(artifactStart);
    if (artifactStart >= 0) body = body.slice(0, artifactStart);
    cases.push({name, approvals, firstTaskCount, definition: {key, name: '交付验收·' + name, category: '计算引擎验收',
      description: '计算结果、实际审批和流程能力验收。',
      bpmnXml: `<?xml version="1.0" encoding="UTF-8"?><bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://simplepoint.org/workflow"><bpmn:process id="${key}" isExecutable="true">${lanes}<bpmn:startEvent id="start"/>${body}<bpmn:endEvent id="end"/>${artifacts}</bpmn:process>${extra.replaceAll('PROCESS_KEY', key)}</bpmn:definitions>`,
      formContract: {schemaVersion: 1, variables: [{name: 'score', label: '可信验收评分', type: 'decimal', scope: 'start', required: true}],
        startBinding: {provider: 'simplepoint-app', formId: spec.formId, version: spec.version, sceneId: 'main',
          variableMappings: {weighted_score: 'score'}}, taskBindings: {}},
    }});
  };
  for (const sequential of [false, true]) {
    const name = (sequential ? 'sequential' : 'parallel') + '-multi-instance';
    add(name, task('review', '多实例验收复核', 'manager', `<bpmn:multiInstanceLoopCharacteristics isSequential="${sequential}"><bpmn:loopCardinality>2</bpmn:loopCardinality></bpmn:multiInstanceLoopCharacteristics>`)
      + edge('begin', 'start', 'review') + edge('finish', 'review', 'end'), [['manager', '多实例验收复核'], ['manager', '多实例验收复核']], sequential ? 1 : 2);
  }
  for (const before of [true, false]) {
    add('standard-loop-' + (before ? 'before' : 'after'), task('review', '循环复核', 'manager',
      `<bpmn:standardLoopCharacteristics testBefore="${before}" loopMaximum="3"><bpmn:loopCondition>${'${loopCounter &lt; 2}'}</bpmn:loopCondition></bpmn:standardLoopCharacteristics>`)
      + edge('begin', 'start', 'review') + edge('finish', 'review', 'end'), [['manager', '循环复核'], ['manager', '循环复核']]);
  }
  add('nested-subprocess', subprocess('outer', subprocess('inner', task('review', '嵌套阶段验收'), 'review'), 'inner')
    + edge('begin', 'start', 'outer') + edge('finish', 'outer', 'end'), [['manager', '嵌套阶段验收']]);
  add('inclusive-gateway', '<bpmn:inclusiveGateway id="split"/><bpmn:inclusiveGateway id="join"/>'
    + task('delivery', '包容分支交付复核') + task('quality', '包容分支质量复核', 'finance')
    + edge('begin', 'start', 'split') + edge('positive', 'split', 'delivery', '${score >= 0}')
    + edge('bounded', 'split', 'quality', '${score <= 100}') + edge('delivery_done', 'delivery', 'join')
    + edge('quality_done', 'quality', 'join') + edge('finish', 'join', 'end'),
  [['manager', '包容分支交付复核'], ['finance', '包容分支质量复核']], 1);
  add('descriptive-diagram', '<bpmn:laneSet id="lanes"><bpmn:lane id="finance" name="质量管理"><bpmn:flowNodeRef>start</bpmn:flowNodeRef><bpmn:flowNodeRef>review</bpmn:flowNodeRef><bpmn:flowNodeRef>end</bpmn:flowNodeRef></bpmn:lane></bpmn:laneSet>'
    + task('review', '图形说明验收') + edge('begin', 'start', 'review') + edge('finish', 'review', 'end')
    + '<bpmn:dataObject id="document" isCollection="true"/><bpmn:dataObjectReference id="document_ref" name="验收文档" dataObjectRef="document"/>'
    + '<bpmn:dataStoreReference id="archive_ref" name="验收归档"/><bpmn:group id="group" categoryValueRef="category_value"/>'
    + '<bpmn:textAnnotation id="note" textFormat="text/plain"><bpmn:text>交付说明 ${notExecuted} &amp; &lt;script&gt;日本語😀</bpmn:text></bpmn:textAnnotation>'
    + '<bpmn:association id="note_link" sourceRef="review" targetRef="note"/>', [['manager', '图形说明验收']], 1,
  '<bpmn:collaboration id="collaboration"><bpmn:participant id="pool" name="交付验收流程" processRef="PROCESS_KEY"/></bpmn:collaboration><bpmn:category id="category"><bpmn:categoryValue id="category_value" value="交付归档"/></bpmn:category>');
  return cases;
}

/** Keeps every definition/intent checkpoint so interrupted verification resumes the same work. */
export async function verifyWorkflowCapabilities({call, request, spec, attachment, state, save}) {
  state.suffix ??= randomUUID().replaceAll('-', '').slice(0, 10); state.cases ??= {}; await save();
  const wait = async (read, accept, label) => {
    for (let i = 0; i < 100; i++) {const value = await read(); if (accept(value)) return value; await delay(500);}
    assert.fail('Timed out ' + label);
  };
  for (const scenario of workflowCapabilityCases(spec, state.suffix)) {
    const item = state.cases[scenario.name] ??= {approvals: []}; await save();
    if (item.passed) continue;
    scenario.definition.bpmnXml = await withAcceptanceDiagram(scenario.definition.bpmnXml);
    if (!item.definitionId) {
      const created = await call('admin', '/workflow/definitions', 'POST', scenario.definition);
      item.definitionId = created.definition.id; item.revision = created.definition.draftRevision; await save();
    }
    const definition = '/workflow/definitions/' + item.definitionId;
    if (!item.draftSaved || !item.version && item.source !== scenario.definition.bpmnXml) {
      await call('admin', definition + '/draft', 'PUT', {...scenario.definition, expectedRevision: item.revision});
      item.revision++; item.draftSaved = true; item.source = scenario.definition.bpmnXml; await save();
    }
    if (!item.version) {
      item.version = (await call('admin', definition + '/publish', 'POST', {expectedRevision: item.revision,
        comment: '真实计算业务的流程能力验收'})).version; assert(item.version); await save();
    }
    item.command ??= {runtime: {formId: spec.formId, formVersion: spec.version}, idempotencyKey: randomUUID(),
      definitionId: item.definitionId, definitionVersion: item.version, title: '【计算验收】' + scenario.name,
      values: deliverySample(attachment), variables: {}, ccUserIds: []}; await save();
    if (!item.submissionId) {
      item.submissionId = (await call('employee', spec.runtime + '/submissions', 'POST', item.command)).id; await save();
    }
    const submission = await wait(() => call('employee', spec.runtime + '/submissions/' + item.submissionId),
      value => Boolean(value.processInstanceId), scenario.name + ' start');
    item.instanceId = submission.processInstanceId;
    const snapshot = await call('employee', spec.runtime + '/submissions/' + item.submissionId + '/snapshot');
    assert.equal(snapshot.record.values.weighted_score, '86'); item.snapshot ??= snapshot.record; await save();
    for (let index = 0; index < scenario.approvals.length; index++) {
      const [actor, name] = scenario.approvals[index];
      let approval = item.approvals[index];
      if (approval?.completed) continue;
      if (!approval) {
        const tasks = await wait(async () => (await call(actor, '/workflow/tasks/pending?size=100')).content
          .filter(t => t.processInstanceId === item.instanceId), tasks => tasks.some(t => t.name === name), scenario.name + ' task');
        if (index === 0) assert.equal(tasks.length, scenario.firstTaskCount);
        const task = tasks.find(t => t.name === name);
        approval = {actor, taskId: task.id, command: {variables: {}, comment: '实际验收评分与阶段任务核对通过', idempotencyKey: randomUUID()}};
        item.approvals.push(approval); await save();
      }
      const denied = await request('employee', '/workflow/tasks/' + approval.taskId + '/complete', 'POST',
        {variables: {}, comment: '越权申请人', idempotencyKey: randomUUID()}); assert([403, 404].includes(denied.status));
      const view = await call(actor, '/app/workflow/tasks/' + approval.taskId + '/business-form');
      assert.deepEqual(view.record.values, item.snapshot.values);
      await call(actor, '/workflow/tasks/' + approval.taskId + '/claim', 'POST');
      const completed = await call(actor, '/workflow/tasks/' + approval.taskId + '/complete', 'POST', approval.command);
      const replay = await call(actor, '/workflow/tasks/' + approval.taskId + '/complete', 'POST', approval.command);
      assert.equal(replay.status, completed.status); approval.completed = true; await save();
    }
    const final = await wait(() => call('employee', spec.runtime + '/submissions/' + item.submissionId),
      value => value.processStatus === 'COMPLETED', scenario.name + ' finish');
    const diagram = await call('employee', '/workflow/instances/' + item.instanceId + '/diagram');
    assert.equal(diagram.instance.status, 'COMPLETED');
    assert.equal(diagram.bpmnXml.trim(), scenario.definition.bpmnXml.trim());
    assert.deepEqual((await call('employee', spec.runtime + '/submissions/' + item.submissionId + '/snapshot')).record, item.snapshot);
    item.diagram = diagram; item.status = final.processStatus; item.passed = true; await save();
    console.log('PASS workflow capability ' + scenario.name);
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
