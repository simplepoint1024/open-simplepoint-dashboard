import assert from 'node:assert/strict';

/** Exercises production UI against a 240-row, independently paged assignment API. */
export async function smokeAssignment({evaluate, poll, clickReady, clickText, fill, fixture, requests, preferences}) {
  const left = '.sp-assignment-panel[data-selected="false"]';
  const right = '.sp-assignment-panel[data-selected="true"]';
  const dropdown = '.ant-dropdown:not(.ant-dropdown-hidden) .ant-table-filter-dropdown';
  const row = (panel, code) => `${panel} tr[data-row-key="app-${code}"]`;
  const hasRow = (panel, code) => evaluate(`!!document.querySelector(${JSON.stringify(row(panel, code))})`);
  const openCode = async panel => {
    await poll(() => evaluate(`!document.querySelector(${JSON.stringify(dropdown)})`), 'previous assignment filter closed');
    await evaluate(`document.querySelectorAll(${JSON.stringify(`${panel} .ant-table-filter-trigger`)})[1].click()`);
    await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(`${dropdown} input.ant-input`)})`), 'assignment code filter');
  };
  const filter = async (panel, value) => {
    await openCode(panel);
    await fill(`${dropdown} input.ant-input`, value);
    await clickText(`${dropdown} button`, '应用');
  };
  await clickReady('.nb-tab-panel-active .ant-table-tbody input[type="checkbox"]');
  await clickText('.nb-tab-panel-active button', '配置应用');
  await poll(() => hasRow(left, '120'), 'available assignment first page');
  await poll(() => hasRow(right, '000'), 'selected assignment first page');
  await clickReady(`${right} .ant-pagination-item-6`);
  await poll(() => hasRow(right, '119'), 'selected assignments beyond first 100');
  for (let i = 0; i < 2; i++) {
    await evaluate(`document.querySelectorAll('${right} .ant-table-column-sorters')[1].click()`);
  }
  await poll(() => evaluate(`document.querySelector('${right} tr[data-row-key]')?.dataset.rowKey==='app-119' && document.querySelector('${right} .ant-pagination-item-active')?.textContent==='1'`), 'server descending sort resets pagination before selecting rows');
  await filter(right, 'app-119');
  await poll(() => evaluate(`document.querySelectorAll('${right} tr[data-row-key]').length===1`), 'server selected filter resets page');
  await clickReady(`${right} button[aria-label="筛选与视图"]`);
  await fill('.sp-query-drawer input[placeholder*="视图名称"]', '分配视图');
  await clickText('.sp-query-drawer button', '保存当前视图');
  await poll(() => [...preferences.values()].some(value => JSON.parse(value).views?.some(view => view.name === '分配视图')), 'assignment view saved');
  await clickReady('.sp-query-drawer .ant-drawer-close');
  await openCode(right);
  await clickText(`${dropdown} button`, '重置');
  await poll(() => evaluate(`!document.querySelector(${JSON.stringify(dropdown)})`), 'reset assignment filter closed');
  await poll(() => hasRow(right, '118'), 'selected filter reset preserves descending sort');
  await clickReady(`${right} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '分配视图');
  await poll(() => hasRow(right, '119'), 'assignment view executes server query');
  await filter(left, 'app-239');
  try {
    await poll(() => hasRow(left, '239'), 'available filter finds off-page item');
  } catch (error) {
    console.error('Assignment query diagnostics', requests.filter(r => r.url.includes('/applications/items')).map(r => r.url),
      await evaluate(`document.querySelector('.sp-assignment-table')?.textContent`));
    throw error;
  }
  await clickReady(`${row(left, '239')} input[type="checkbox"]`);
  fixture.failMove();
  const previousMoves = fixture.moves;
  await evaluate(`(() => {const e=[...document.querySelectorAll('${left} button')].find(e=>e.textContent.replaceAll(' ','').trim()==='分配'); e.click();e.click();})()`);
  await poll(() => evaluate(`document.body.textContent.includes('分配失败（测试）')`), 'move failure feedback');
  assert.equal(fixture.moves, previousMoves + 1, 'synchronous lock blocks duplicate requests');
  assert.equal(fixture.assigned.has('app-239'), false, 'failed move does not mutate assignment');
  assert.equal(await evaluate(`document.querySelector('${row(left, '239')} input')?.checked`), true, 'failed move preserves selection');
  await clickText(`${left} button`, '分配');
  await poll(() => fixture.assigned.has('app-239'), 'assignment retry succeeds');
  await poll(() => evaluate(`document.querySelectorAll('${left} tr[data-row-key]').length===0`), 'available side refreshes after assignment');
  await poll(() => hasRow(right, '119'), 'other pane refetch completes with its independent filter');
  await filter(right, 'app-239');
  await poll(() => hasRow(right, '239'), 'new assignment discoverable by server search');
  await clickReady(`${row(right, '239')} input[type="checkbox"]`);
  await clickText(`${right} button`, '移除分配');
  await poll(() => hasRow(left, '239'), 'removal refreshes both sides');
  assert.equal(fixture.assigned.has('app-239'), false);
  await clickReady(`${left} button[aria-label="筛选与视图"]`);
  await clickText('.sp-query-drawer button', '添加条件');
  await fill('.sp-query-drawer input.ant-input:not([placeholder*="视图名称"])', '分配应用 238');
  await clickText('.sp-query-drawer .ant-drawer-footer button', '应用');
  await poll(() => evaluate(`document.querySelectorAll('${left} tr[data-row-key]').length===0 && !document.querySelector('${left} .ant-spin-spinning')`), 'advanced condition intersects header filter on the server');
  assert.ok(requests.some(r => r.url.includes('/applications/items?') && new URL(r.url, 'http://fixture').searchParams.has('$query')), 'advanced query actually sent');
  assert.ok(requests.filter(r => r.url.startsWith('/common/platform/applications/items?')).every(r => Number(new URL(r.url, 'http://fixture').searchParams.get('size')) <= 100));
  assert.equal(requests.some(r => r.url.includes('/packages/authorized')), false, 'never hydrates all assignments');
  await clickReady('.ant-drawer-open .ant-drawer-close');
  await poll(() => evaluate(`!document.querySelector('.ant-drawer-open')`), 'assignment drawer closes');
  await clickReady('.nb-tab-panel-active .ant-table-tbody input[type="checkbox"]');
  console.log('PASS: assignment server pagination beyond 100, off-page filters, independent saved views, duplicate lock, failed move/retry and two-way refresh');
}
