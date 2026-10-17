import assert from 'node:assert/strict';
import {workflowDesignerDriver} from './workflow-designer-driver.mjs';

/** Operates the actual BPMN canvas and properties; only HTTP business data is isolated. */
export async function smokeWorkflowMultiInstance({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture}) {
  fixture.multiInstanceConfiguration();
  const {drawer, properties, execution: multi, pointer, select, choose, save, open} =
    workflowDesignerDriver({clickReady, clickText, poll, evaluate, send, fixture});
  await clickMenu('流程定义测试');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active tr[data-row-key="definition1"]')`), 'definition ready');
  const model = xml => evaluate(`(()=>{const document=new DOMParser().parseFromString(${JSON.stringify(xml)},'application/xml');
    const task=[...document.getElementsByTagNameNS('*','userTask')].find(node=>node.id==='review');
    const loop=task.getElementsByTagNameNS('*','multiInstanceLoopCharacteristics')[0];
    return {assignee:task.getAttributeNS('http://flowable.org/bpmn','assignee'),
      roles:task.getAttributeNS('http://flowable.org/bpmn','candidateGroups'),
      mode:loop?.getAttribute('isSequential'), collection:loop?.getAttributeNS('http://flowable.org/bpmn','collection'),
      item:loop?.getAttributeNS('http://flowable.org/bpmn','elementVariable'),
      count:loop?.getElementsByTagNameNS('*','loopCardinality')[0]?.textContent,
      completion:loop?.getElementsByTagNameNS('*','completionCondition')[0]?.textContent};})()`);
  await open(); await select('review');
  await choose('执行方式', '并行多实例');
  await fill(`${multi} input[aria-label="执行次数"]`, '3');
  let saved = await model(await save());
  assert.equal(saved.count, '3'); assert.notEqual(saved.mode, 'true'); assert.equal(saved.assignee, '${approver}');
  await choose('执行方式', '串行多实例');
  saved = await model(await save());
  assert.equal(saved.mode, 'true'); assert.equal(saved.count, '3', 'mode does not discard the source');
  const countField = `${multi} .ant-form-item:has(label[title="执行次数"])`;
  await clickText(`${countField} .ant-segmented-item`, '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${countField} .cm-content')`), 'count expression editor loaded');
  await evaluate(`(()=>{const view=document.querySelector('${countField} .cm-content').cmTile.root.view;
    view.dispatch({changes:{from:0,to:view.state.doc.length,insert:'3'}});})()`);
  const beforeSourceDraft = fixture.writes.length;
  await select('end');
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.querySelector('${countField}')?.textContent.includes('表达式必须使用')`), 'invalid advanced count draft survives node switch');
  assert.equal(fixture.writes.length, beforeSourceDraft);
  await poll(() => evaluate(`!!document.querySelector('${countField} .cm-content')`), 'invalid count remains in advanced mode');
  await evaluate(`(()=>{const view=document.querySelector('${countField} .cm-content').cmTile.root.view;
    view.dispatch({changes:{from:0,to:view.state.doc.length,insert:'\u0024{3}'}});})()`);
  saved = await model(await save());
  assert.equal(saved.count, '${3}');

  // Empty source remains a repairable draft and blocks saves even after selecting another node.
  await choose('实例来源', '办理集合');
  await poll(() => evaluate(`document.querySelector('${multi}')?.textContent.includes('必须且只能选择')`), 'empty collection rejected');
  const beforeInvalid = fixture.writes.length;
  await select('end');
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.body.textContent.includes('请完成当前属性配置后保存')
    && document.querySelector('${properties} .workflow-designer__element-id')?.textContent.includes('review')`), 'save focuses invalid non-selected node');
  assert.equal(fixture.writes.length, beforeInvalid);
  await choose('办理集合', '审批人员集合');
  await fill(`${multi} input[aria-label="当前项变量"]`, 'reviewer');
  const assignee = `${properties} > form > .ant-form-item:has(label[title="办理人"])`;
  await pointer(`${assignee} .ant-select`);
  await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', 'reviewer · 当前实例变量');
  saved = await model(await save());
  assert.equal(saved.collection, 'reviewers'); assert.equal(saved.item, 'reviewer'); assert.equal(saved.assignee, '${reviewer}');
  assert.equal(saved.count, undefined, 'switching source removes the conflicting cardinality');

  const completion = `${multi} .ant-form-item:has(label[title="提前结束条件（可选）"])`;
  await clickText(`${completion} .ant-segmented-item`, '高级表达式');
  await poll(() => evaluate(`!!document.querySelector('${completion} .cm-content')`), 'completion editor loaded');
  await evaluate(`(()=>{const view=document.querySelector('${completion} .cm-content').cmTile.root.view;
    view.dispatch({changes:{from:0,to:view.state.doc.length,insert:'\u0024{nrOfCompletedInstances >= 1}'}});})()`);
  saved = await model(await save());
  assert.equal(saved.completion, '${nrOfCompletedInstances >= 1}');
  await clickText(`${completion} .ant-segmented-item`, '条件配置');
  await clickText(`${completion} button`, '添加条件');
  await poll(() => evaluate(`document.querySelector('${completion}')?.textContent.includes('请填写有效的条件值')`), 'completion has a transient incomplete condition');
  const beforeCompletionDraft = fixture.writes.length;
  await select('end');
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.querySelector('${completion}')?.textContent.includes('请填写有效的条件值')`), 'incomplete completion draft survives node switch');
  assert.equal(fixture.writes.length, beforeCompletionDraft);
  await evaluate(`document.querySelectorAll('${completion} button[aria-label="移除条件"]')[1].click()`);
  saved = await model(await save());
  assert.ok(saved.completion.includes('nrOfCompletedInstances >= 1'));
  // Keep the edited visual serialization for subsequent lossless reload/undo assertions.
  const completionSource = saved.completion;
  await clickReady(`${drawer} .ant-drawer-close`);
  await open(); await select('review');
  assert.equal(await evaluate(`document.querySelector('${multi} input[aria-label="当前项变量"]')?.value`), 'reviewer');
  assert.ok(await evaluate(`document.querySelector('${properties}')?.textContent.includes('reviewer · 当前实例变量')`), 'iteration variable survives reload');

  // An explicit edit is undoable, and read-only mode switches never erase the completion condition.
  await choose('执行方式', '并行多实例');
  await clickReady(`${drawer} button[aria-label="撤销"]`);
  saved = await model(await save());
  assert.equal(saved.mode, 'true'); assert.equal(saved.completion, completionSource);
  await choose('执行方式', '执行一次');
  const beforeDangling = fixture.writes.length;
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.body.textContent.includes('办理范围引用了未声明')`), 'removed loop cannot leave a dangling assignee');
  assert.equal(fixture.writes.length, beforeDangling);
  await clickText(`${assignee} .ant-segmented-item`, '流程变量');
  await pointer(`${assignee} .ant-select`);
  await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '流程发起人');
  saved = await model(await save());
  assert.equal(saved.collection, undefined); assert.equal(saved.count, undefined);
  await clickReady(`${drawer} .ant-drawer-close`);
  console.log('PASS: real multi-instance configuration, serialized sources and locals, full-canvas save validation, error focus, early completion, reload and undo');
}
