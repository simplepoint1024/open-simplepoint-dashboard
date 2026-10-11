import assert from 'node:assert/strict';

export async function smokeRoleAssignment({clickMenu, clickReady, clickText, fill, poll, evaluate, requests, fixture}) {
  const root = '.nb-tab-panel-active';
  const left = '.sp-assignment-panel[data-selected="false"]';
  const right = '.sp-assignment-panel[data-selected="true"]';
  const dropdown = '.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown';
  const hasRow = (panel, id) => evaluate(`!!document.querySelector('${panel} tr[data-row-key="${id}"]')`);
  const selectMember = async id => {
    const row = `${root} tr[data-row-key="${id}"]`;
    // A retained/just-returned row may render before its read settles. Cell clicks
    // correctly refuse selection while the real radio is disabled; wait for the
    // same availability contract as a user, then verify actual row-click selection.
    await poll(() => evaluate(`(()=>{const input=document.querySelector('${row} input[type="radio"]');return !!input&&!input.disabled;})()`), 'member selection is available ' + id);
    await clickReady(row + ' td:nth-child(2)');
    await poll(() => evaluate(`document.querySelector('${row} input[type="radio"]')?.checked===true`), 'actual row-click selection ' + id);
  };
  const filter = async (panel, value) => {
    await poll(() => evaluate(`!document.querySelector('${dropdown}')`), 'old role filter closed');
    await evaluate(`[...document.querySelectorAll('${panel} th')].find(e=>e.textContent.includes('角色名称')).querySelector('.ant-table-filter-trigger').click()`);
    await poll(() => evaluate(`!!document.querySelector('${dropdown} input.ant-input')`), 'role name filter');
    await fill(`${dropdown} input.ant-input`, value);
    await clickText(`${dropdown} button`, '应用');
  };
  await clickMenu('成员查询测试');
  await poll(() => hasRow(root, 'member-144'), 'member view retained');
  await selectMember('member-144');
  await clickText(`${root} .sp-member-table-scroll .sp-table-button-group button`, '配置角色');
  await poll(() => hasRow(right, 'role-000'), 'role assignment loaded with real schema');
  assert.equal(await evaluate(`!!document.querySelector('.tenant-member-role-drawer.ant-drawer-bottom.ant-drawer-open')`), true,
    'tenant member role configuration opens from the bottom');
  assert.equal(await evaluate(`document.querySelector('${left} button[aria-label="筛选与视图"]').textContent`), '', 'role query button is icon-only');
  await clickReady(`${right} .ant-pagination-item-6`);
  await poll(() => hasRow(right, 'role-119'), 'assigned roles beyond one hundred');
  for (let i = 0; i < 2; i++) await evaluate(`[...document.querySelectorAll('${right} th')].find(e=>e.textContent.includes('角色名称')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => evaluate(`document.querySelector('${right} tr[data-row-key]')?.dataset.rowKey==='role-119' && document.querySelector('${right} .ant-pagination-item-active')?.textContent==='1'`), 'role sorting resets page');
  await filter(left, 'Candidate239');
  await poll(() => hasRow(left, 'role-239'), 'off-page role name search');
  await clickReady(`${left} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '添加条件');
  await fill('.sp-query-drawer input.ant-input:not([placeholder*="视图名称"])', 'role-238');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('${left} tr[data-row-key]').length===0 && !document.querySelector('${left} .ant-spin-spinning')`), 'advanced role query intersects header');
  await clickReady(`${left} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer .ant-drawer-footer button', '清空高级条件');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => hasRow(left, 'role-239'), 'clear advanced preserves role header');
  await clickReady(`${left} button[aria-label="筛选与视图"]`);
  await fill('.sp-query-drawer input[placeholder*="视图名称"]', '角色分配视图');
  await clickText('.sp-query-drawer button', '保存当前视图');
  await poll(() => evaluate(`[...document.querySelectorAll('.sp-query-drawer button')].some(e=>e.textContent==='角色分配视图')`), 'role view saved');
  await clickReady('.sp-query-drawer .ant-drawer-close');
  await clickReady(`${left} button[aria-label="清除全部筛选"]`);
  await poll(() => hasRow(left, 'role-120'), 'role query cleared');
  await clickReady(`${left} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '角色分配视图');
  await poll(() => hasRow(left, 'role-239'), 'role view restores server query');
  await clickReady(`${left} tr[data-row-key="role-239"] input[type="checkbox"]`);
  fixture.failMove(); const before = fixture.moves.length;
  await evaluate(`(() => {const b=[...document.querySelectorAll('${left} button')].find(e=>e.textContent.replaceAll(' ','')==='分配');b.click();b.click();})()`);
  await poll(() => evaluate(`document.body.textContent.includes('角色分配失败（测试）')`), 'role failure visible');
  assert.equal(fixture.moves.length, before+1, 'double-click is one mutation');
  assert.equal(await evaluate(`document.querySelector('${left} tr[data-row-key="role-239"] input').checked`), true, 'failure preserves role selection');
  await clickText(`${left} button`, '分配');
  await poll(() => fixture.grants.get('member-144').has('role-239'), 'role assigned');
  await poll(() => evaluate(`document.querySelectorAll('${left} tr[data-row-key]').length===0`), 'available roles refreshed');
  await filter(right, 'Candidate239');
  await poll(() => hasRow(right, 'role-239'), 'assigned side discovers moved role');
  await clickReady(`${right} tr[data-row-key="role-239"] input[type="checkbox"]`);
  await clickText(`${right} button`, '移除分配');
  await poll(() => hasRow(left, 'role-239'), 'role removal refreshes available side');
  await clickReady('.ant-drawer-open .ant-drawer-close');
  await poll(() => evaluate(`!document.querySelector('.sp-assignment-panel')`), 'role drawer disposed');
  await clickReady(`${root} button[aria-label="清除全部筛选"]`);
  await poll(() => hasRow(root, 'member-143'), 'second member visible');
  await selectMember('member-143');
  await clickText(`${root} .sp-member-table-scroll .sp-table-button-group button`, '配置角色');
  await poll(() => hasRow(right, 'role-001'), 'second member role assignment');
  assert.equal(await evaluate(`document.querySelectorAll('${right} tr[data-row-key]').length`), 1, 'no prior member assignment data reused');
  assert.ok(requests.some(r => r.url.includes('/users/role-candidates/schema?userId=member-143')));
  assert.equal(requests.some(r => r.url.includes('/users/authorized')), false);
  assert.ok(requests.filter(r => r.url.includes('/users/role-candidates?')).every(r => Number(new URL(r.url,'http://fixture').searchParams.get('size')) <= 100));
  await clickReady('.ant-drawer-open .ant-drawer-close');
  await poll(() => evaluate(`!document.querySelector('.sp-assignment-panel')`), 'second role drawer closed');
  console.log('PASS: role assignment real schema, off-page filters/views, advanced intersection, sorting, retry, duplicate lock and member isolation');
}
