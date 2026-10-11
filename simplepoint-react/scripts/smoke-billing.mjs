import assert from 'node:assert/strict';
export async function smokeBilling({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture, workspace}) {
  fixture.enable(); fixture.delayNext(650);
  const root = '.nb-tab-panel-active .ai-billing', summary = root + ' .billing-summary', base = '/ai/workbench/billing';
  await clickMenu('计费标准测试');
  await poll(() => evaluate(`!!document.querySelector('${root} .ant-spin-spinning')`), 'billing loading');
  assert.equal(await evaluate(`document.querySelectorAll('${summary} .ant-statistic').length`), 0, 'pending reads do not invent zero totals');
  await poll(() => evaluate(`document.querySelectorAll('${summary} tr[data-row-key]').length===20 && !document.querySelector('${summary} .ant-spin-spinning')`), 'bounded aggregate');
  assert.equal(await evaluate(`document.querySelectorAll('${root} .ant-card').length`), 0);
  assert.equal(await evaluate(`document.querySelectorAll('${summary} .sp-data-table').length`), 1);
  assert.ok(await evaluate(`document.querySelector('${summary}').textContent.includes('前 20 个模型与币种分组')`));
  assert.ok(await evaluate(`document.querySelector('${summary} .billing-statistics').textContent.includes('9,007,199,254,740,993.010203040506')`));
  assert.ok(await evaluate(`document.querySelector('${summary} .billing-statistics').textContent.includes('0.000000000001')`));
  const input = index => root + ' .ant-picker-input:nth-child(' + (index === 0 ? 1 : 3) + ') input';
  const date = async (index, value) => {
    await evaluate(`document.querySelector(${JSON.stringify(input(index))}).focus()`); await fill(input(index), value);
    await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
    await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
  };
  await date(0, '2024-01-01'); await date(1, '2026-10-10');
  await poll(() => evaluate(`document.querySelector('${root} .ant-alert-error')?.textContent.includes('最长 366 天')`), 'invalid range is explicit');
  assert.equal(await evaluate(`document.querySelectorAll('${summary} .ant-statistic').length`), 0);
  const beforeInvalid = fixture.reads.length;
  await evaluate(`document.querySelector('${root} .ant-btn').click()`);
  assert.equal(fixture.reads.length, beforeInvalid, 'invalid period cannot bypass validation via refresh');
  await date(0, '2026-10-01'); await date(1, '2026-10-10');
  try {await poll(() => evaluate(`document.querySelector('${summary} tr[data-row-key]')?.textContent.includes('PlatformModel') && !document.querySelector('${summary} .ant-spin-spinning')`), 'valid date range');}
  catch (error) {console.log('Billing period diagnostics', await evaluate(`({inputs:[...document.querySelectorAll('${root} .ant-picker-input input')].map(input=>input.value),alerts:[...document.querySelectorAll('${root} .ant-alert')].map(a=>a.textContent)})`)); throw error;}
  const period = fixture.reads.findLast(read => read.path === base + '/summary').params;
  assert.equal(Date.parse(period.get('to')) - Date.parse(period.get('from')), 10 * 86400000, 'inclusive selected days map to exclusive next midnight');
  const dates = await evaluate(`[...document.querySelectorAll('${root} .ant-picker-input input')].map(input=>input.value)`);
  assert.deepEqual(dates, ['2026-10-01', '2026-10-10']);
  fixture.failNext();
  await clickText(`${root} > .ant-space button`, '刷新');
  await poll(() => evaluate(`document.querySelector('${summary} .ant-alert-error')?.textContent.includes('计费数据查询失败')`), 'billing failure visible');
  assert.equal(await evaluate(`document.querySelectorAll('${summary} .ant-statistic').length`), 0, 'failed reads do not present cached money as current totals');
  await clickText(`${summary} .ant-alert-error button`, '重试');
  await poll(() => evaluate(`!document.querySelector('${summary} .ant-alert-error') && document.querySelectorAll('${summary} tr[data-row-key]').length===20`), 'billing retry');
  await clickText(`${root} > .ant-space button`, '查看调用明细');
  const details = root + ' [role="tabpanel"]:not([aria-hidden="true"])';
  try {await poll(() => evaluate(`!!document.querySelector('${details} [data-row-key="invocation-0"]') && !document.querySelector('${details} .ant-spin-spinning')`), 'schema-driven invocation page');}
  catch (error) {console.log('Billing invocation diagnostics', await evaluate(`({panels:[...document.querySelectorAll('${root} [role="tabpanel"]')].map(p=>({class:p.className,hidden:p.getAttribute('aria-hidden'),text:p.textContent.slice(0,1000)})),keys:[...document.querySelectorAll('${root} [data-row-key]')].map(row=>row.getAttribute('data-row-key'))})`)); throw error;}
  await clickReady(`${details} .ant-pagination-item-27`);
  await poll(() => evaluate(`!!document.querySelector('${details} [data-row-key="invocation-520"]')`), 'complete invocations beyond first 500');
  assert.equal(await evaluate(`document.querySelectorAll('${details} [data-row-key]').length`), 1, 'exclusive boundary invocation is not included');
  const invocation = fixture.reads.findLast(read => read.path === base + '/invocations'), query = JSON.parse(invocation.params.get('$query'));
  assert.equal(invocation.params.get('size'), '20'); assert.equal(invocation.params.get('page'), '26');
  assert.equal(query.where.children[0].value, period.get('from')); assert.equal(query.where.children[1].value, period.get('to'));
  await clickText(`${root} .ant-tabs-tab`, '账单概览');
  await poll(() => evaluate(`!!document.querySelector('${summary} tr[data-row-key]')`), 'summary restored');
  if (workspace) {
    workspace.enable(); fixture.delayNext(700);
    await clickText(`${root} > .ant-space button`, '刷新');
    await poll(() => evaluate(`!!document.querySelector('${summary} .ant-spin-spinning')`), 'old workspace read pending');
    await evaluate(`localStorage.setItem('sp.tenantId',${JSON.stringify(workspace.tenantId)});window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:${JSON.stringify(workspace.tenantId)}}));`);
    await clickMenu('计费标准测试');
    await poll(() => evaluate(`document.querySelector('${summary} tr[data-row-key]')?.textContent.includes('TenantModel') && !document.querySelector('${summary} .ant-spin-spinning')`), 'actual scoped billing response');
    assert.equal(await evaluate(`document.querySelector('${summary} .billing-statistics .ant-statistic-content-value').textContent`), '37');
    assert.equal(await evaluate(`document.querySelector('${summary}').textContent.includes('PlatformModel')`), false);
    assert.equal(fixture.reads.findLast(read => read.path === base + '/summary').tenant, workspace.tenantId);
    await evaluate(`localStorage.setItem('sp.tenantId','__platform__');window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:'__platform__'}));`);
    await clickMenu('计费标准测试'); workspace.disable();
  }
  console.log('PASS: billing standard layout, actual pending/error states, bounded TOP20, exact string amounts, 366-day validation, inclusive days/exclusive boundary, 521 invocation pagination and scoped reads');
}
