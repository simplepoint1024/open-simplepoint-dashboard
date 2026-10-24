import assert from 'node:assert/strict';

/** Real ordinary-user metadata projection, narrow layouts and retained page-owned detail drawers. */
export async function verifyActualAuditRetention({evaluate, send, poll, click, on, screenshot, call, appId, specs, state, save}) {
  const drawer = '.app-record-detail.ant-drawer-open', root = '.nb-tab-panel-active';
  const directoryReads = [];
  on('Network.requestWillBeSent', event => {
    const path = new URL(event.request.url).pathname;
    if (/\/common\/(tenant-directory|users|organizations|tenants)(\/|$)/.test(path) && !path.includes('/preferences')) directoryReads.push(path);
  });
  const opened = [];
  for (const spec of specs) {
    const page = await call(spec.runtime + '/records/query', 'POST', {page: 0, size: 20}); assert(page.records.length);
    const record = await call(spec.runtime + '/records/' + page.records[0].id);
    const labels = ['tenantId', 'createOrgDeptId', 'createdBy', 'updatedBy'].map(key => {
      const projected = record.systemLabels?.[key]; assert(projected?.label && projected.id === record.systemValues[key]);
      return {key, id: projected.id, label: projected.label};
    });
    const route = '#/app/runtime/' + appId + '/' + spec.pageId;
    await evaluate(`location.hash=${JSON.stringify(route)}`);
    await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'previous page owns hidden drawer');
    const row = root + ` [data-row-key="${record.id}"]`;
    await poll(() => evaluate(`!!document.querySelector('${row} input[type="checkbox"]')`), 'actual ordinary list row');
    await evaluate(`document.querySelector('${row} input[type="checkbox"]').click()`);
    await click(root + ' button', '详情');
    await poll(() => evaluate(`!!document.querySelector('${drawer} #root_createdBy')`), 'projected audit metadata');
    for (const item of labels) {
      assert.equal(await evaluate(`document.querySelector('${drawer} #root_${item.key}')?.textContent`), item.label);
    }
    const layouts = [];
    for (const width of [1440, 768, 390]) {
      await send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      const bounds = await evaluate(`(()=>{const e=document.querySelector('${drawer} .ant-drawer-body');return {width:e.clientWidth,scroll:e.scrollWidth}})()`);
      assert(bounds.width >= 260 && bounds.scroll <= bounds.width + 1); layouts.push({viewport: width, ...bounds});
    }
    await screenshot('calculation-audit-' + state.actor + '-' + spec.pageId + '-390');
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    opened.push({route, recordId: record.id, labels, layouts}); state.pages = opened; await save();
  }
  for (const item of opened) {
    await evaluate(`location.hash=${JSON.stringify(item.route)}`);
    await poll(() => evaluate(`!!document.querySelector('${drawer} #root_createdBy')`), 'retained detail restored for owner');
    for (const label of item.labels) assert.equal(await evaluate(`document.querySelector('${drawer} #root_${label.key}')?.textContent`), label.label);
    await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
    await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'detail closed');
  }
  assert.deepEqual(directoryReads, [], 'Server audit labels do not cause broader directory discovery');
  state.directoryReads = directoryReads; state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS actual audit labels, no directory expansion, three widths and retained detail ownership for ' + state.actor);
}
