import assert from 'node:assert/strict';

/** Real personal routes and built shared runtime; no independent task-center navigation. */
export async function smokeWorkflowPersonal({clickText, clickReady, fill, poll, evaluate, send, reload, fixture, capturePreview}) {
  const page = '.nb-tab-panel-active', task = '.workflow-task-editor.ant-drawer-open';
  const progress = '.workflow-progress-detail.ant-drawer-open', reject = '.workflow-task-rejection .ant-modal';
  const operation = '.workflow-matter-operation .ant-modal';
  const visible = selector => evaluate(`!!document.querySelector(${JSON.stringify(selector)})?.getClientRects().length`);
  const route = async path => {await evaluate(`location.hash=${JSON.stringify('#' + path)}`);};
  const row = id => `${page} tr[data-row-key="${id}"]`;
  const outcome = async (id, label) => {
    await clickText(`${page} .ant-segmented-item`, '已办');
    await poll(() => evaluate(`document.querySelector('${row(id)}')?.textContent.includes('${label}')`), label + ' is in completed');
    assert.ok(await evaluate(`document.querySelector('${row(id)}').textContent.includes('李四')`), 'actual actor is a real name');
  };
  await route('/my/todos');
  await poll(() => visible(row('task1')), 'workflow owns personal inbox').catch(async error => {
    console.log(await evaluate(`({hash:location.hash,scope:localStorage.getItem('sp.tenantId'),body:document.body.innerText})`)); throw error;
  });
  assert.equal(await evaluate(`!!document.querySelector('${page} button[title="新建待办"]')`), false);
  assert.ok(await evaluate(`document.querySelector('${row('task1')}').textContent.includes('张三')`));
  assert.equal(await evaluate(`document.querySelector('${row('task1')}').textContent.includes('actor')`), false);
  assert.ok(await evaluate(`document.querySelectorAll('${page} th .ant-table-filter-trigger').length>=7`));
  await clickText(`${row('task1')} button`, '进度');
  await poll(() => evaluate(`document.querySelector('${progress} .workflow-node-history')?.textContent.includes('部门审批人')`), 'current candidates visible immediately');
  assert.ok(await evaluate(`document.querySelector('${progress} .workflow-node-history').textContent.includes('李四')`));
  if (capturePreview) await capturePreview();
  // A real SVG pointer event, followed by the accessible keyboard equivalent.
  const point = await poll(() => evaluate(`(()=>{if(document.querySelector('${progress} .process-status-diagram')?.getAttribute('aria-busy')!=='false'
    || document.getAnimations().some(a=>a.playState==='running' && a.effect?.getComputedTiming().iterations!==Infinity && a.effect?.target?.closest?.('.ant-drawer')))return;
    const hit=document.querySelector('${progress} .djs-element[data-element-id="start"] .djs-hit');
    if (!hit) return; const r=hit.getBoundingClientRect(), x=r.x+r.width/2,y=r.y+r.height/2;
    return document.elementFromPoint(x,y)?.closest('.djs-element')?.getAttribute('data-element-id')==='start' ? {x,y} : undefined;})()`), 'pointer-reachable BPMN node');
  await send('Input.dispatchMouseEvent', {...point, type: 'mouseMoved', buttons: 0});
  await send('Input.dispatchMouseEvent', {...point, type: 'mousePressed', button: 'left', buttons: 1, clickCount: 1});
  await send('Input.dispatchMouseEvent', {...point, type: 'mouseReleased', button: 'left', buttons: 0, clickCount: 1});
  await poll(() => fixture.requests.some(r => r.path.endsWith('/nodes/start')), 'clicking a BPMN node requests its actual history');
  await poll(() => evaluate(`document.querySelector('${progress} .workflow-node-history')?.textContent.includes('尚未产生执行记录')`), 'unexecuted node is not fabricated');
  await clickText(`${progress} .process-status-diagram__nodes button`, '审批');
  await poll(() => evaluate(`document.querySelector('${progress} .workflow-node-history')?.textContent.includes('部门审批人')`), 'node button restores selected execution');
  await clickText(`${progress} .ant-segmented-item`, '流程操作记录');
  await poll(() => evaluate(`document.querySelector('${progress} [aria-label="流程操作记录"]')?.textContent.includes('张三')`), 'separate immutable journal');
  await clickReady(`${progress} .ant-drawer-close`);

  await clickText(`${row('task1')} button`, '办理');
  await poll(() => visible(`${task} textarea#root_comment`), 'personal task form');
  await fill(`${task} textarea#root_comment`, '材料完整，同意');
  const beforeApprove = fixture.writes.length;
  await clickText(`${task} button`, '通过');
  await poll(() => fixture.writes.length === beforeApprove + 1 && visible(task).then(v => !v), 'approve closes current task');
  assert.deepEqual(fixture.writes.at(-1).data, {variables: {approved: false}, comment: '材料完整，同意', idempotencyKey: 'complete:task1'});
  await outcome('task1', '通过');
  await clickText(`${row('task1')} button`, '进度');
  await poll(() => evaluate(`document.querySelector('${progress} .workflow-node-history')?.textContent.includes('材料完整，同意')`), 'processed node shows actual comment');
  await clickReady(`${progress} .ant-drawer-close`);

  await clickText(`${page} .ant-segmented-item`, '待办');
  await clickText(`${row('taskreject')} button`, '办理');
  await poll(() => visible(`${task} textarea#root_comment`), 'rejectable task loaded');
  await clickText(`${task} button`, '驳回');
  await poll(() => visible(`${reject} textarea#root_reason`), 'bounded typed rejection reason');
  const beforeReject = fixture.writes.length;
  await clickText(`${reject} button`, '确认驳回');
  await poll(() => visible(`${reject} .ant-form-item-explain-error`), 'reason is mandatory');
  assert.equal(fixture.writes.length, beforeReject);
  await fill(`${reject} textarea#root_reason`, '请补充附件');
  await route('/profile'); await poll(async () => !await visible(reject) && !await visible(task), 'other page hides both owned dialogs');
  await route('/my/todos');
  await poll(async () => await visible(reject) && await evaluate(`document.querySelector('${reject} textarea#root_reason')?.value==='请补充附件'
    && !document.querySelector('${reject} form.rjsf button[type="submit"]')?.disabled`), 'reject draft retained and owner active');
  fixture.personal.fail('reject');
  await evaluate(`(()=>{const button=document.querySelector('${reject} form.rjsf button[type="submit"]');button.click();button.click();})()`);
  await poll(() => fixture.writes.length === beforeReject + 1, 'same-frame rejection single flight');
  await poll(() => evaluate(`document.querySelector('${reject} .ant-alert-error')?.textContent.includes('驳回失败')`), 'failed rejection keeps target and reason');
  assert.equal(await evaluate(`document.querySelector('${reject} textarea#root_reason').disabled`), true);
  await clickText(`${reject} button`, '重试');
  await poll(async () => !await visible(reject) && !await visible(task), 'rejection succeeds');
  assert.deepEqual(fixture.writes.at(-1).data, {reason: '请补充附件', idempotencyKey: 'reject:taskreject'});
  await outcome('taskreject', '驳回');
  await clickText(`${page} .ant-segmented-item`, '待办');
  await clickText(`${row('taskclaim')} button`, '认领');
  await poll(() => evaluate(`document.querySelector('${row('taskclaim')}')?.textContent.includes('李四')`), 'claim updates real assignee');
  await clickText(`${row('taskclaim')} button`, '办理');
  await poll(() => visible(task), 'claimed task can be handled in the same inbox');
  await clickReady(`${task} .ant-drawer-close`);

  await route('/my/items');
  await poll(() => visible(row('instancewithdraw')), 'personal matters own lifecycle operations');
  for (const [id, label, reason] of [['instancewithdraw', '撤回', '申请内容需修改'], ['instancecancel', '取消', '业务不再需要']]) {
    await clickText(`${row(id)} button`, label);
    await poll(() => visible(`${operation} textarea#root_reason`), label + ' uses bounded reason form');
    await fill(`${operation} textarea#root_reason`, reason);
    fixture.personal.fail(id === 'instancewithdraw' ? 'withdraw' : 'cancel');
    const before = fixture.writes.length;
    await clickText(`${operation} button`, '确认操作');
    await poll(() => evaluate(`document.querySelector('${operation} textarea#root_reason')?.disabled && !!document.querySelector('${operation} .ant-alert-error')`), 'uncertain operation freezes its original reason');
    const original = structuredClone(fixture.writes.at(-1).data);
    await clickText(`${operation} button`, '重试');
    await poll(async () => !await visible(operation), label + ' finished');
    assert.equal(fixture.writes.length, before + 2);
    assert.deepEqual(fixture.writes.at(-1).data, original);
    assert.deepEqual(original, {reason, idempotencyKey: `${id === 'instancewithdraw' ? 'withdraw' : 'cancel'}:${id}`});
  }
  await clickText(`${row('instancereject')} button`, '修改后重新发起');
  const start = '.workflow-process-start .ant-modal';
  await poll(() => evaluate(`document.querySelector('${start}')?.textContent.includes('原审批结果和处理历史保持不变')`), 'linked restart explains immutable original');
  await poll(() => visible(`${start} input#root_title`), 'original start values loaded');
  await fill(`${start} input#root_title`, '补齐材料重新申请');
  await clickText(`${start} button`, '修改后重新发起');
  await poll(() => fixture.writes.at(-1)?.path.endsWith('/resubmit') && visible(start).then(v => !v), 'linked resubmission committed');
  assert.equal(fixture.writes.at(-1).data.version, 1, 'resubmission pins the original published version');
  assert.equal(fixture.personal.matters.find(row => row.id === 'instancereject').status, 'REJECTED');
  await clickText(`${row('instancereject')} button`, '进度');
  await clickText(`${progress} button`, '查看重新发起的事项');
  await poll(() => evaluate(`document.querySelector('${progress}')?.textContent.includes('补齐材料重新申请')`), 'new related matter reachable');
  await clickText(`${progress} button`, '返回刚才的事项');
  await poll(() => evaluate(`document.querySelector('${progress}')?.textContent.includes('驳回')`), 'back restores historical outcome');
  await clickReady(`${progress} .ant-drawer-close`);
  await route('/my/todos?view=completed');
  await outcome('action-instancewithdraw', '撤回');
  await outcome('action-instancecancel', '取消');
  assert.ok(await visible(row('task1')) && await visible(row('taskreject')), 'all actual outcomes coexist in completed');
  await route('/my/todos?task=task1&instance=instance1');
  await poll(() => visible(progress), 'already-processed notification opens history instead of stale approval');
  assert.equal(await visible(task), false);
  await clickReady(`${progress} .ant-drawer-close`);
  assert.equal(fixture.requests.filter(r => r.path === '/workflow/tasks/pending' || r.path === '/workflow/instances/started').length, 0,
    'personal workflow never calls the obsolete task-center list contracts');
  await route('/my/todos?view=completed&task=task1&instance=instance1');
  await evaluate(`localStorage.removeItem('sp.nav.tabs')`);
  await reload();
  await poll(() => visible(progress), 'first visit without saved personal tabs preserves the processed deep link');
  assert.equal(await evaluate(`location.hash.includes('/my/todos?') && location.hash.includes('task=task1')
    && location.hash.includes('instance=instance1')`), true);
  await poll(() => evaluate(`document.querySelector('${progress} .workflow-node-history')?.textContent.includes('材料完整，同意')`),
    'fresh personal deep link resolves actual historical processing details');
  await send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, screenWidth: 390, screenHeight: 844, deviceScaleFactor: 1, mobile: true});
  await send('Emulation.setPageScaleFactor', {pageScaleFactor: 1});
  await poll(() => evaluate(`(()=>{
    const drawer=document.querySelector('${progress} .ant-drawer-content-wrapper')?.getBoundingClientRect();
    const canvas=document.querySelector('${progress} .djs-container')?.getBoundingClientRect();
    const node=document.querySelector('${progress} .djs-element[data-element-id="review"] .djs-visual')?.getBoundingClientRect();
    return drawer && canvas && node && drawer.left>=0 && drawer.right<=document.documentElement.clientWidth+1
      && drawer.bottom<=visualViewport.height+1 && node.left>=canvas.left-1 && node.right<=canvas.right+1;
  })()`), 'phone viewport contains the whole drawer and automatically refitted approval node');
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
  await clickReady(`${progress} .ant-drawer-close`);
  console.log('PASS: real mobile emulation keeps the drawer on screen and refits the BPMN canvas after resize');
  console.log('PASS: first-visit workflow deep links open without a saved tab and preserve task/instance parameters');
  console.log('PASS: personal inbox approval/rejection/claim; real names and clickable node/audit details; retained drafts and retry; withdraw/cancel completed records; linked version-pinned resubmission and processed deep links');
}
