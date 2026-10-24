import assert from 'node:assert/strict';

/** Opens the published immutable diagrams through the employee's real matters deep links. */
export async function verifyActualWorkflowDiagrams({evaluate, send, poll, click, screenshot, call, cases, state, save}) {
  state.cases ??= {};
  const drawer = '.workflow-progress-detail.ant-drawer-open';
  for (const item of cases) {
    if (state.cases[item.name]?.passed) continue;
    const source = await call('/workflow/instances/' + item.instanceId + '/diagram');
    assert(source.bpmnXml.includes('BPMNDiagram'));
    assert(source.activities.some(activity => activity.status === 'COMPLETED'));
    await evaluate(`location.hash=${JSON.stringify('#/my/items?instance=' + item.instanceId)}`);
    await poll(() => evaluate(`!!document.querySelector('${drawer} .ant-segmented')`), item.name + ' progress deep link');
    await click(drawer + ' .ant-segmented-item', '审批节点');
    await poll(() => evaluate(`!!document.querySelector('${drawer} .process-status-diagram[aria-busy="false"] .djs-shape')`), item.name + ' imported diagram');
    assert.equal(await evaluate(`document.querySelector('${drawer} .process-status-diagram .ant-alert')?.textContent ?? ''`), '');
    const ids = await evaluate(`[...document.querySelectorAll('${drawer} .djs-element[data-element-id]')].map(e=>e.getAttribute('data-element-id'))`);
    const activityIds = [...new Set(source.activities.map(activity => activity.activityId))];
    for (const id of activityIds) assert(ids.includes(id), item.name + ' executed node ' + id + ' is visible');
    if (item.name === 'nested-subprocess') for (const id of ['outer', 'inner', 'review']) assert(ids.includes(id));
    if (item.name === 'descriptive-diagram') {
      for (const id of ['pool', 'finance', 'document_ref', 'archive_ref', 'group', 'note', 'note_link']) assert(ids.includes(id));
      assert.equal(await evaluate(`!!document.querySelector('${drawer} .process-status-diagram script')`), false);
    }
    const node = source.activities.find(activity => activity.activityType === 'userTask') ?? source.activities.find(activity => /review|approve/i.test(activity.activityId));
    assert(node, item.name + ' has a real approval activity');
    const label = node.activityName || node.activityId;
    await click(drawer + ' .process-status-diagram__nodes button', label);
    await poll(() => evaluate(`document.querySelector('${drawer} .workflow-node-history')?.textContent.includes('实际操作人') && document.querySelector('${drawer} .workflow-node-history')?.textContent.includes('通过')`), item.name + ' node execution history');
    const sizes = [];
    for (const width of [1440, 768, 390]) {
      await send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      await poll(() => evaluate(`(()=>{const e=document.querySelector('${drawer} .ant-drawer-body');return e&&e.scrollWidth<=e.clientWidth+1})()`), item.name + ' bounded progress at ' + width);
      await click(drawer + ' .process-status-diagram button', '适配画布');
      const bounds = await evaluate(`(()=>{const e=document.querySelector('${drawer} .process-status-diagram__canvas');return {width:e.clientWidth,height:e.clientHeight}})()`);
      assert(bounds.width > 100 && bounds.height > 100); sizes.push({width, ...bounds});
      if (['nested-subprocess', 'descriptive-diagram', 'expense-6800'].includes(item.name)) await screenshot('calculation-workflow-' + item.name + '-' + width);
    }
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    state.cases[item.name] = {passed: true, instanceId: item.instanceId, activityIds, visibleIds: ids, sizes, at: new Date().toISOString()};
    await save(); console.log('PASS actual immutable diagram and node history ' + item.name);
    await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
    await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'progress closed');
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
