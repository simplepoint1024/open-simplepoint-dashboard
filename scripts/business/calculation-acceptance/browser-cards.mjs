import assert from 'node:assert/strict';

/** Tests the real resource controller's queries, responsive pagination and account preferences. */
export async function verifyActualCards({evaluate, send, poll, click, writes, screenshot, on, call, spec, state, save, restore = false}) {
  const root = '.nb-tab-panel-active';
  let preferenceTarget;
  on('Network.requestWillBeSent', ({request}) => {
    const url = new URL(request.url);
    if (url.pathname === '/common/users/preferences' && request.method === 'PUT') {
      const value = JSON.parse(JSON.parse(request.postData).value);
      if (value.viewMode === 'cards' && value.cardRows === 2) {
        preferenceTarget = url.pathname + url.search;
        state.preferenceTarget = preferenceTarget;
      }
    }
  });
  await click('.ant-menu-submenu-title', '计算引擎业务验收');
  await click('.ant-menu-item', '公式与发布运维验收台账');
  await poll(() => evaluate(`!!document.querySelector('${root} .sp-table-root')`), 'standard resource list');
  const latestQuery = () => writes.filter(write => write.path === spec.runtime + '/records/query').at(-1)?.body;
  const verify = async (label, rows = 2) => {
    const geometry = await poll(async () => {
      const value = await evaluate(`(()=>{const e=document.querySelector('${root} .sp-table-root'),g=document.querySelector('${root} .sp-card-grid');return e&&g?{width:e.clientWidth,columns:getComputedStyle(g).gridTemplateColumns.split(' ').length}:undefined})()`);
      if (!value) return;
      const expectedColumns = Math.max(1, Math.floor((value.width + 16) / 296));
      return value.columns === expectedColumns && latestQuery()?.size === expectedColumns * rows ? value : undefined;
    }, label + ' responsive server page size');
    const query = structuredClone(latestQuery());
    const page = await call(spec.runtime + '/records/query', 'POST', query);
    await poll(() => evaluate(`JSON.stringify([...document.querySelectorAll('${root} .sp-record-card-title')].map(e=>e.textContent))===${JSON.stringify(JSON.stringify(page.records.map(record => record.values.function_name)))}`), label + ' actual ordered card data');
    assert.equal(await evaluate(`document.querySelector('${root} .sp-card-pagination .ant-pagination-item-active')?.getAttribute('title')`), String(query.page + 1));
    assert.equal(await evaluate(`!!document.querySelector('${root} .sp-card-pagination .ant-pagination-options-size-changer')`), false);
    const bounds = await evaluate(`(()=>{const e=document.querySelector('${root} .sp-card-scroll');return {width:e.clientWidth,scrollWidth:e.scrollWidth,height:e.clientHeight,scrollHeight:e.scrollHeight}})()`);
    assert(bounds.scrollWidth <= bounds.width + 1); assert(bounds.height > 50);
    state.checks ??= []; state.checks.push({label, query, total: page.total, ids: page.records.map(record => record.id), geometry, bounds});
    await save(); return {query, page};
  };
  if (restore) {
    await poll(() => evaluate(`!!document.querySelector('${root} button[aria-label="切换为列表"]')`), 'server preference restores cards in new login and empty browser profile');
    await verify('new-login-account-preference');
    state.restoredInFreshLogin = true; state.passed = true; state.completedAt = new Date().toISOString(); await save();
    console.log('PASS account-synchronized card mode and page rows after fresh authentication/profile'); return;
  }
  if (await evaluate(`!!document.querySelector('${root} button[aria-label="切换为列表"]')`)) await click(root + ' button', '切换为列表');
  await poll(() => evaluate(`!!document.querySelector('${root} tbody [data-row-key]')`), 'table loaded');
  const tableSize = latestQuery().size;
  await click(root + ' button', '切换为卡片');
  await poll(() => evaluate(`!!document.querySelector('${root} [aria-label="每页卡片行数"]')`), 'card row selector');
  await evaluate(`document.querySelector('${root} [aria-label="每页卡片行数"]').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await click('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '2行/页');
  const first = await verify('desktop-first-page');
  await evaluate(`document.querySelector('${root} .sp-card-pagination .ant-pagination-next button').click()`);
  await poll(() => latestQuery()?.page === first.query.page + 1, 'next card page server query');
  const next = await verify('desktop-next-page');
  assert(!first.page.records.some(record => next.page.records.some(item => item.id === record.id)));
  for (const width of [768, 390, 1440]) {
    const before = structuredClone(latestQuery());
    await send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    if (width === 390) await poll(() => evaluate(`document.querySelector('.ant-layout-sider')?.classList.contains('ant-layout-sider-collapsed') && document.querySelector('${root} .sp-table-root')?.clientWidth>=260`), 'phone navigation leaves usable card width');
    const after = await verify('responsive-' + width);
    assert.equal(after.query.page, Math.floor(before.page * before.size / after.query.size), 'Resizing keeps the previous first record within the new page');
    await screenshot('calculation-cards-' + width);
  }
  await click(root + ' button', '切换为列表');
  await poll(() => latestQuery()?.size === tableSize && evaluate(`!!document.querySelector('${root} tbody [data-row-key]')`), 'restore original table page size');
  state.tableSizeRestored = tableSize;
  await click(root + ' button', '切换为卡片'); await verify('persisted-cards');
  await poll(async () => {
    if (!preferenceTarget) return false;
    const raw = await call(preferenceTarget); const value = typeof raw === 'string' ? JSON.parse(raw) : raw;
    state.preferenceReadback = raw; await save();
    return value?.viewMode === 'cards' && value?.cardRows === 2;
  }, 'final appearance preference committed before closing browser');
  state.configured = true; await save();
  console.log('PASS real list/cards switching, responsive server pagination, ordered records and bounded scrolling');
}
