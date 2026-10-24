import assert from 'node:assert/strict';

/** Real bounded detail scrolling and rapid calculator input; no mocked preview response. */
export async function verifyActualLargeAggregate({evaluate, send, poll, click, fill, screenshot, call, spec, appId,
  recordId, state, save, writes}) {
  const base = spec.runtime + '/records', before = await call(base + '/' + recordId);
  const root = '.nb-tab-panel-active', drawer = '.sp-simple-table-drawer.ant-drawer-open';
  await evaluate(`location.hash=${JSON.stringify('#/app/runtime/' + appId + '/' + spec.pageId)}`);
  const row = root + ` [data-row-key="${recordId}"]`;
  await poll(() => evaluate(`!!document.querySelector('${row} input[type="checkbox"]')`), 'large aggregate visible');
  await evaluate(`document.querySelector('${row} input[type="checkbox"]').click()`); await click(root + ' button', '编辑');
  const detail = drawer + ' [data-detail-id="expense_items"]';
  await poll(() => evaluate(`!!document.querySelector('${detail} .ant-table-body')`), 'large detail editor');
  state.layouts = [];
  for (const width of [1440, 768, 390]) {
    await send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    const bounds = await evaluate(`(()=>{const d=document.querySelector('${drawer} .ant-drawer-body');const t=document.querySelector('${detail} .ant-table-body');
      t.scrollLeft=t.scrollWidth;t.scrollTop=t.scrollHeight;return {drawerWidth:d.clientWidth,drawerScroll:d.scrollWidth,
      tableWidth:t.clientWidth,tableScroll:t.scrollWidth,height:t.clientHeight,scrollHeight:t.scrollHeight,left:t.scrollLeft,top:t.scrollTop}})()`);
    assert(bounds.drawerWidth >= 260 && bounds.drawerScroll <= bounds.drawerWidth + 1);
    assert(bounds.height <= 321 && bounds.top > 0 && bounds.left > 0, 'Large table scrolls inside its own bounds');
    state.layouts.push({viewport: width, ...bounds}); await save();
  }
  await evaluate(`document.querySelector('${detail}').scrollIntoView({block:'center'})`);
  await screenshot('calculation-large-detail-390');
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
  const countBefore = writes.filter(write => write.path === base + '/preview').length;
  for (let i = 0; i < 20; i++) await fill(drawer + ' #form_main_discount_rate', String((i % 8 + 1) / 10));
  const finalRate = .8; await fill(drawer + ' #form_main_discount_rate', String(finalRate));
  const expected = (Number(before.values.total_amount) * finalRate).toFixed(2);
  await poll(() => evaluate(`Number(document.querySelector('${drawer} #form_main_total_amount')?.textContent)===${Number(expected)}`), 'last rapid input owns the trusted preview');
  state.preview = {inputChanges: 21, sentRequests: writes.filter(write => write.path === base + '/preview').length - countBefore, expected};
  assert(state.preview.sentRequests > 0 && state.preview.sentRequests < state.preview.inputChanges, 'Rapid edits are debounced');
  await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
  await click('.sp-simple-table-discard .ant-modal button', '放弃修改');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'unsaved aggregate discarded');
  assert.deepEqual(await call(base + '/' + recordId), before);
  assert(!writes.some(write => write.path === base + '/' + recordId && write.method === 'PUT'));
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS large detail bounded two-axis scrolling, real rapid preview and discard without mutations');
}
