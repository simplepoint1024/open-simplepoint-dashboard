import assert from 'node:assert/strict';
import {workflowDesignerDriver} from './workflow-designer-driver.mjs';

export async function smokeWorkflowStandardLoop(context) {
  const {clickMenu, clickReady, clickText, fill, poll, evaluate, fixture} = context;
  const {drawer, properties, pointer, select, choose, save, open, close, editExpression} = workflowDesignerDriver(context);
  const standard = `${properties} .workflow-standard-loop`;
  const condition = `${standard} .ant-form-item:has(label[title="继续循环条件（可选）"])`;
  await clickMenu('流程定义测试');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active tr[data-row-key="definition1"]')`), 'definition ready');
  const model = xml => evaluate(`(()=>{const d=new DOMParser().parseFromString(${JSON.stringify(xml)},'application/xml');
    const task=[...d.getElementsByTagNameNS('*','userTask')].find(node=>node.id==='review');
    const loop=task.getElementsByTagNameNS('*','standardLoopCharacteristics')[0];
    return {maximum:loop?.getAttribute('loopMaximum'), before:loop?.getAttribute('testBefore'),
      condition:loop?.getElementsByTagNameNS('*','loopCondition')[0]?.textContent,
      assignee:task.getAttributeNS('http://flowable.org/bpmn','assignee'),
      multi:task.getElementsByTagNameNS('*','multiInstanceLoopCharacteristics').length};})()`);
  await open(); await select('review');
  await choose('执行方式', '标准循环');
  await fill(`${standard} input[aria-label="最大执行次数"]`, '3');
  let saved = await model(await save());
  assert.equal(saved.maximum, '3'); assert.notEqual(saved.before, 'true');
  assert.equal(saved.multi, 0); assert.equal(saved.assignee, '${approver}');
  await choose('判断时机', '执行前判断（可不执行）');
  await clickText(`${condition} .ant-segmented-item`, '高级表达式');
  await editExpression(condition, '${approved}');
  saved = await model(await save());
  assert.equal(saved.before, 'true'); assert.equal(saved.condition, '${approved}');

  // Invalid literal and empty limits cannot be hidden by selecting another node.
  await editExpression(condition, '${1}');
  const invalid = fixture.writes.length;
  await select('end'); await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.querySelector('${standard}')?.textContent.includes('有效的布尔表达式')`), 'invalid condition focuses its node');
  assert.equal(fixture.writes.length, invalid);
  await editExpression(condition, '${approved}');
  await fill(`${standard} input[aria-label="最大执行次数"]`, '');
  await select('end'); await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.querySelector('${standard}')?.textContent.includes('1 至 50')`), 'missing maximum focuses its node');
  assert.equal(fixture.writes.length, invalid);
  await fill(`${standard} input[aria-label="最大执行次数"]`, '4');
  await clickText(`${condition} .ant-segmented-item`, '条件配置');
  await clickText(`${condition} button`, '添加条件');
  await select('end'); await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.querySelector('${condition}')?.textContent.includes('请填写有效的条件值')`), 'transient loop draft survives selection');
  assert.equal(fixture.writes.length, invalid);
  await evaluate(`document.querySelectorAll('${condition} button[aria-label="移除条件"]')[1].click()`);
  saved = await model(await save());
  const valid = {...saved};
  await close(); await open(); await select('review');
  assert.equal(await evaluate(`document.querySelector('${standard} input[aria-label="最大执行次数"]')?.value`), '4');
  assert.deepEqual(await model(await save()), valid, 'reload preserves all loop properties');
  await choose('执行方式', '并行多实例');
  await clickReady(`${drawer} button[aria-label="撤销"]`);
  assert.deepEqual(await model(await save()), valid, 'undo restores the previous loop kind and condition');
  await choose('执行方式', '执行一次');
  saved = await model(await save());
  assert.equal(saved.maximum, undefined); assert.equal(saved.multi, 0); assert.equal(saved.assignee, '${approver}');
  await close();

  // Native context-menu markers import with no max. They must remain repairable, never silently executable.
  fixture.standardLoopConfiguration('<bpmn:standardLoopCharacteristics/>');
  await open(); await select('end');
  const beforeImported = fixture.writes.length;
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.querySelector('${standard}')?.textContent.includes('1 至 50')`), 'unselected imported loop gets validated');
  assert.equal(fixture.writes.length, beforeImported);
  await fill(`${standard} input[aria-label="最大执行次数"]`, '2');
  saved = await model(await save());
  assert.equal(saved.maximum, '2');
  await clickText(`${condition} .ant-segmented-item`, '高级表达式');
  await editExpression(condition, '${loopCounter < 2}');
  await clickText(`${condition} .ant-segmented-item`, '条件配置');
  assert.ok(await evaluate(`document.querySelector('${condition}')?.textContent.includes('本节点已完成次数')`), 'loop-local counter is a usable typed visual variable');
  saved = await model(await save());
  assert.equal(saved.condition, '${loopCounter < 2}');
  await choose('执行方式', '执行一次');
  await save();
  await pointer(`${drawer} .djs-context-pad.open [data-action="replace"]`);
  await pointer(`${drawer} .djs-popup [data-id="toggle-loop"]`);
  await select('end');
  const beforeNative = fixture.writes.length;
  await clickText(`${drawer} button`, '保存草稿');
  await poll(() => evaluate(`document.querySelector('${standard}')?.textContent.includes('1 至 50')`), 'actual native popup loop requires configuration');
  assert.equal(fixture.writes.length, beforeNative);
  await fill(`${standard} input[aria-label="最大执行次数"]`, '3');
  assert.equal((await model(await save())).maximum, '3');
  await close();
  console.log('PASS: standard loop timing, limits, condition drafts, whole-canvas validation, source preservation, reload, undo and repairable native marker');
}
