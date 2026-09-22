import assert from 'node:assert/strict';

export async function smokeMembers({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture, requests}) {
  const root = '.nb-tab-panel-active';
  const dropdown = '.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown';
  const row = id => `${root} tr[data-row-key="member-${id}"]`;
  const hasRow = id => evaluate(`!!document.querySelector('${row(id)}')`);
  const openFilter = async label => {
    await poll(() => evaluate(`!document.querySelector('${dropdown}')`), 'previous member filter closed');
    await evaluate(`[...document.querySelectorAll('${root} th')].find(e=>e.textContent.includes(${JSON.stringify(label)})).querySelector('.ant-table-filter-trigger').click()`);
    await poll(() => evaluate(`!!document.querySelector('${dropdown}')`), 'member header filter opens');
  };
  await clickMenu('成员查询测试');
  await poll(() => hasRow(0), 'members load with actual schema');
  assert.equal(await evaluate(`document.querySelector('${root} button[aria-label="筛选与视图"]')?.textContent`), '', 'query toolbar is icon only');
  await evaluate(`document.querySelector('${root} .sp-member-table-scroll').scrollTop=100000`);
  const geometry = await evaluate(`(() => {const p=document.querySelector('${root} .ant-pagination').getBoundingClientRect();const r=document.querySelector('${root}').getBoundingClientRect();const s=document.querySelector('${root} .sp-member-table-scroll');return {page:p.toJSON(),pane:r.toJSON(),scrollTop:s.scrollTop,client:s.clientHeight,total:s.scrollHeight};})()`);
  // scrollHeight/clientHeight round to integers while flex layout can retain subpixel heights.
  assert.ok(geometry.page.top>=geometry.pane.top-1 && geometry.page.bottom<=geometry.pane.bottom+1, 'pagination reachable inside member pane: '+JSON.stringify(geometry));
  assert.equal(await evaluate(`(() => {const e=document.querySelector('${root} .ant-pagination-next');const r=e.getBoundingClientRect();return e.contains(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2));})()`), true, 'pagination control is not covered or clipped');
  await clickReady(`${root} .ant-pagination-item-8`);
  await poll(() => hasRow(144), 'members beyond first 100 reachable');
  await evaluate(`document.querySelector('${root} .sp-member-table-scroll').scrollTop=0`);
  for (let i=0; i<2; i++) await evaluate(`[...document.querySelectorAll('${root} th')].find(e=>e.textContent.includes('姓名')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => evaluate(`document.querySelector('${root} tr[data-row-key]')?.dataset.rowKey==='member-144' && document.querySelector('${root} .ant-pagination-item-active')?.textContent==='1'`), 'member SQL sort resets the page');
  await openFilter('姓名');
  await fill(`${dropdown} input.ant-input`, 'Member144');
  await clickText(`${dropdown} button`, '应用');
  await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===1 && !!document.querySelector('${row(144)}')`), 'header filter is server-wide and resets page');
  await clickReady(`${root} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '添加条件');
  await fill('.sp-query-drawer input.ant-input:not([placeholder*="视图名称"])', 'member-143');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===0 && !document.querySelector('${root} .ant-spin-spinning')`), 'advanced ID filter intersects the member name header');
  await clickReady(`${root} button[aria-label="筛选与视图"]`);
  await fill('.sp-query-drawer input.ant-input:not([placeholder*="视图名称"])', 'member-144');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => hasRow(144), 'advanced ID and header both match');
  await clickReady(`${root} button[aria-label="筛选与视图"]`);
  await fill('.sp-query-drawer input[placeholder*="视图名称"]', '成员视图');
  await clickText('.sp-query-drawer button', '保存当前视图');
  await poll(() => evaluate(`[...document.querySelectorAll('.sp-query-drawer button')].some(e=>e.textContent==='成员视图')`), 'member view saved');
  await clickReady('.sp-query-drawer .ant-drawer-close');
  await clickReady(`${root} button[aria-label="清除全部筛选"]`);
  await poll(() => evaluate(`document.querySelector('${root} .ant-pagination-total-text')?.textContent==='共 145 条'`), 'reset member header preserves descending sort');
  await openFilter('成员启用');
  await poll(() => evaluate(`document.querySelectorAll('${dropdown} .ant-select').length===2`), 'boolean filter uses typed select');
  await evaluate(`document.querySelectorAll('${dropdown} input[role="combobox"]')[1].focus()`);
  await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40});
  await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40});
  await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '否');
  await clickText(`${dropdown} button`, '应用');
  await poll(() => evaluate(`document.querySelector('${root} .ant-pagination-total-text')?.textContent==='共 72 条'`), 'false filter counts entire member dataset');
  assert.ok(requests.some(r => {const url=new URL(r.url, 'http://fixture');const value=url.searchParams.get('enabled');return url.pathname==='/common/tenant-members' && value && JSON.parse(value).where.value===false;}), 'boolean is transported as JSON false, not text');
  await clickReady(`${root} button[aria-label="清除全部筛选"]`);
  await poll(() => evaluate(`document.querySelector('${root} .ant-pagination-total-text')?.textContent==='共 145 条'`), 'reset boolean preserves descending sort');
  await clickReady(`${root} button[aria-label="筛选与视图"]`);
  fixture.failNext();
  await clickText('.sp-query-drawer button', '成员视图');
  await poll(() => evaluate(`document.querySelector('${root} .ant-alert-error')?.textContent.includes('成员查询条件已失效')`), 'invalid restored view retains query tools and safe reason');
  assert.equal(await evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length`), 0, 'error does not expose stale results');
  await clickReady(`${root} button[aria-label="清除全部筛选"]`);
  await poll(() => hasRow(144), 'reset recovers failed member query');
  await clickReady(`${root} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '成员视图');
  await poll(() => hasRow(144), 'saved view restores server query successfully');
  await poll(() => evaluate(`!document.querySelector('.sp-query-drawer input')`), 'closed query drawer releases its form DOM');
  assert.ok(requests.filter(r=>r.url.startsWith('/common/tenant-members?')).every(r=>Number(new URL(r.url, 'http://fixture').searchParams.get('size'))<=100));
  console.log('PASS: actual member schema, reachable pagination beyond 100, server header/boolean filtering, saved views and invalid-query recovery');
}

/** Called after a full browser reload and a simulated server change in JSON property order. */
export async function smokeMemberViewPersistence({clickMenu, clickReady, clickText, poll, evaluate, fixture}) {
  const root = '.nb-tab-panel-active';
  await clickMenu('成员查询测试');
  await poll(() => evaluate(`!!document.querySelector('${root} tr[data-row-key="member-0"]')`), 'members reloaded in a new browser session');
  assert.ok(fixture.reorderedSchemas > 0, 'browser received the changed schema serialization');
  await clickReady(`${root} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '成员视图');
  await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===1 && !!document.querySelector('${root} tr[data-row-key="member-144"]')`), 'saved member view survives reload and schema property reordering');
  console.log('PASS: DataTable saved views survive browser reload and server schema property reordering');
}
