import assert from 'node:assert/strict';

export async function smokeOrganizationSelection({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  const root = '.nb-tab-panel-active';
  const left = '.sp-assignment-panel[data-selected="false"]';
  const right = '.sp-assignment-panel[data-selected="true"]';
  const dropdown = '.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown';
  const hasRow = (panel, id) => evaluate(`!!document.querySelector('${panel} tr[data-row-key="${id}"]')`);
  const filter = async (panel, value) => {
    await poll(() => evaluate(`!document.querySelector('${dropdown}')`), 'old org draft filter closed');
    await evaluate(`[...document.querySelectorAll('${panel} th')].find(e=>e.textContent.includes('组织名称')).querySelector('.ant-table-filter-trigger').click()`);
    await poll(() => evaluate(`!!document.querySelector('${dropdown} input.ant-input')`), 'org draft name filter');
    await fill(`${dropdown} input.ant-input`, value);
    await clickText(`${dropdown} button`, '应用');
  };
  const open = async () => {
    await clickText(`${root} button`, '编辑');
    await poll(() => hasRow(right, 'org-000'), 'organization draft uses actual query schema');
  };
  const close = async () => {
    await clickReady('.ant-drawer-open > .ant-drawer-content-wrapper .ant-drawer-close');
    await poll(() => evaluate(`!document.querySelector('.sp-assignment-panel') || !!document.querySelector('.ant-modal-confirm, .ant-modal-wrap:not([style*="display: none"]) .ant-modal')`), 'draft close or discard confirmation');
    if (await evaluate(`!!document.querySelector('.sp-assignment-panel')`)) {
      await clickText('.ant-modal button', '放弃修改');
    }
    await poll(() => evaluate(`!document.querySelector('.sp-assignment-panel')`), 'draft form closed');
  };
  await clickMenu('组织选择测试');
  await poll(() => hasRow(root, 'scope-a'), 'scope host list loaded');
  await clickReady(`${root} tr[data-row-key="scope-a"] input[type="checkbox"]`);
  await open();
  await clickReady(`${right} .ant-pagination-item-6`);
  await poll(() => hasRow(right, 'org-119'), 'draft selected rows beyond one hundred');
  for (let i=0; i<2; i++) await evaluate(`[...document.querySelectorAll('${right} th')].find(e=>e.textContent.includes('组织名称')).querySelector('.ant-table-column-sorters').click()`);
  await poll(() => evaluate(`document.querySelector('${right} tr[data-row-key]')?.dataset.rowKey==='org-119' && document.querySelector('${right} .ant-pagination-item-active')?.textContent==='1'`), 'draft sort resets page');
  await filter(left, 'Organization239');
  await poll(() => hasRow(left, 'org-239'), 'inactive organization stays visible for explanation');
  assert.equal(await evaluate(`document.querySelector('${left} tr[data-row-key="org-239"] input[type="checkbox"]')?.disabled`), true,
    'inactive organization cannot be added to a policy that would fail server validation');
  await filter(left, 'Organization238');
  await poll(() => hasRow(left, 'org-238'), 'off-page draft search');
  await clickReady(`${left} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '添加条件');
  await fill('.sp-query-drawer input.ant-input:not([placeholder*="视图名称"])', 'org-237');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('${left} tr[data-row-key]').length===0 && !document.querySelector('${left} .ant-spin-spinning')`), 'draft advanced filter intersects header');
  await clickReady(`${left} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer .ant-drawer-footer button', '清空高级条件');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => hasRow(left, 'org-238'), 'draft header preserved');
  await clickReady(`${left} button[aria-label="筛选与视图"]`);
  await fill('.sp-query-drawer input[placeholder*="视图名称"]', '组织草稿视图');
  await clickText('.sp-query-drawer button', '保存当前视图');
  await poll(() => evaluate(`[...document.querySelectorAll('.sp-query-drawer button')].some(e=>e.textContent==='组织草稿视图')`), 'draft view saved');
  await clickReady('.sp-query-drawer .ant-drawer-close');
  await clickReady(`${left} button[aria-label="清除全部筛选"]`);
  await poll(() => hasRow(left, 'org-120'), 'draft filters cleared');
  await clickReady(`${left} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '组织草稿视图');
  await poll(() => hasRow(left, 'org-238'), 'draft view restores');
  await clickReady(`${left} tr[data-row-key="org-238"] input[type="checkbox"]`);
  await clickText(`${left} button`, '加入选择');
  await poll(() => evaluate(`document.querySelectorAll('${left} tr[data-row-key]').length===0`), 'draft move refreshes both sides');
  await filter(right, 'Organization238');
  await poll(() => hasRow(right, 'org-238'), 'selected draft contains newly chosen row');
  assert.equal(fixture.writes.length, 0, 'selection changes never persist permissions');
  assert.deepEqual(fixture.scopes[0].customDeptIds, fixture.initialIds, 'saved scope unchanged before submit');
  await close();
  await open();
  await filter(right, 'Organization238');
  await poll(() => evaluate(`document.querySelectorAll('${right} tr[data-row-key]').length===0 && !document.querySelector('${right} .ant-spin-spinning')`), 'discarding draft restores saved selection');
  await clickReady(`${right} button[aria-label="清除全部筛选"]`);
  await poll(() => hasRow(right, 'org-000'), 'restored selection visible');
  fixture.failNext();
  await filter(left, 'Organization238');
  await poll(() => evaluate(`!!document.querySelector('${left} .ant-alert-error')`), 'draft query failure visible');
  assert.equal(fixture.writes.length, 0, 'read failure does not overwrite saved selection');
  await clickText(`${left} .ant-alert button`, '重试');
  await poll(() => hasRow(left, 'org-238'), 'draft retry succeeds');
  await clickReady(`${left} tr[data-row-key="org-238"] input[type="checkbox"]`);
  await clickText(`${left} button`, '加入选择');
  await poll(() => evaluate(`document.querySelectorAll('${left} tr[data-row-key]').length===0`), 'retried draft move');
  await clickReady('.sp-simple-table-drawer form button[type="submit"]');
  await poll(() => fixture.writes.length===1, 'only explicit form submit saves selection');
  assert.equal(fixture.writes[0].customDeptIds.length, 121, 'all old selected IDs preserved, including those beyond page 100');
  assert.ok(fixture.writes[0].customDeptIds.includes('org-238'));
  await poll(() => evaluate(`!document.querySelector('.sp-assignment-panel')`), 'saved draft form closes');
  if (await evaluate(`document.querySelector('${root} tr[data-row-key="scope-a"] input[type="checkbox"]')?.checked`)) {
    await clickReady(`${root} tr[data-row-key="scope-a"] input[type="checkbox"]`);
  }
  await clickReady(`${root} tr[data-row-key="scope-b"] input[type="checkbox"]`);
  await clickText(`${root} button`, '编辑');
  await poll(() => hasRow(right, 'org-239'), 'previously selected inactive organization remains removable');
  assert.equal(await evaluate(`document.querySelector('${right} tr[data-row-key="org-239"] input[type="checkbox"]')?.disabled`), false);
  await clickReady(`${right} tr[data-row-key="org-239"] input[type="checkbox"]`);
  await clickText(`${right} button`, '移除选择');
  await poll(() => evaluate(`!document.querySelector('${right} tr[data-row-key="org-239"]')`), 'inactive selection removed from draft');
  await close();
  assert.deepEqual(fixture.scopes[1].customDeptIds, ['org-001', 'org-239'], 'cancelled removal preserves saved policy');
  await clickReady(`${root} tr[data-row-key="scope-b"] input[type="checkbox"]`);
  await clickReady(`${root} tr[data-row-key="scope-large"] input[type="checkbox"]`);
  await clickText(`${root} button`, '编辑');
  await poll(() => evaluate(`document.querySelector('.sp-simple-table-drawer .ant-alert')?.textContent.includes('2000')`), 'oversized legacy draft is reported, never truncated');
  assert.equal(fixture.scopes[2].customDeptIds.length, 2001);
  assert.equal(await evaluate(`document.querySelectorAll('.sp-assignment-panel').length`), 0, 'invalid draft never becomes a partial selection page');
  await clickText('.sp-simple-table-drawer button', '清空草稿选择');
  await poll(() => hasRow(left, 'org-000'), 'explicit draft clear recovers picker');
  await close();
  assert.equal(fixture.scopes[2].customDeptIds.length, 2001, 'discarding draft clear preserves oversized saved content');
  assert.equal(fixture.writes.length, 1);
  // Release this dedicated fixture page before the independent host cache-budget scenarios.
  await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  await poll(() => evaluate(`!document.querySelector('.nb-tab-panel-active')?.textContent.includes('Selection A')`), 'selection fixture tab closed');
  console.log('PASS: organization draft server pagination/query/views, discard, failure/retry and save-only-on-submit with all selected IDs preserved');
}

export async function smokeReadonlyOrganizationSelection({clickMenu, clickReady, clickText, poll, evaluate}) {
  const root = '.nb-tab-panel-active';
  await clickMenu('组织选择测试');
  await poll(() => evaluate(`!!document.querySelector('${root} tr[data-row-key="scope-a"]')`), 'scope after reload');
  await clickReady(`${root} tr[data-row-key="scope-a"] input[type="checkbox"]`);
  await clickText(`${root} button`, '编辑');
  await poll(() => evaluate(`!!document.querySelector('.sp-assignment-panel tr[data-row-key]')`), 'readonly organization draft loads');
  assert.equal(await evaluate(`[...document.querySelectorAll('.sp-assignment-panel input[type="checkbox"]')].every(e=>e.disabled)`), true,
    'conditional schema preserves readonly field permissions');
  assert.equal(await evaluate(`[...document.querySelectorAll('.ant-drawer-open button')].find(e=>e.textContent.includes('清空草稿选择'))?.disabled`), true);
  await clickReady('.ant-drawer-open > .ant-drawer-content-wrapper .ant-drawer-close');
  await poll(() => evaluate(`!document.querySelector('.sp-assignment-panel')`), 'readonly form closed');
  console.log('PASS: conditional organization widget preserves backend readonly permissions');
}
