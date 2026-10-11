import assert from 'node:assert/strict';
export async function smokeCatalog({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture, mode = 'writes', workspace}) {
  fixture.enable(); fixture.readonly(mode === 'readonly'); if (mode === 'permissions') fixture.failPermissions();
  const root = '.nb-tab-panel-active .ai-catalog', modal = '.catalog-import .ant-modal', base = '/ai/workbench/catalog';
  await clickMenu('扩展市场标准测试');
  await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===20 && !document.querySelector('${root} .ant-spin-spinning')`), 'catalog real server page');
  assert.equal(await evaluate(`document.querySelectorAll('${root} .ant-card').length`), 0, 'same platform layout without an extra card or duplicate heading');
  assert.equal(await evaluate(`document.querySelectorAll('${root} .sp-data-table').length`), 1);
  const search = async value => {
    await fill(`${root} .ant-input-search input`, value);
    await evaluate(`document.querySelector('${root} .ant-input-search input').focus()`);
    await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
    await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
    await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===1 && document.querySelector('${root} tr[data-row-key]')?.textContent.includes(${JSON.stringify(value)}) && !document.querySelector('${root} .ant-spin-spinning')`), 'server search beyond first 500: ' + value);
  };
  if (mode === 'permissions') {
    await poll(() => evaluate(`document.querySelector('${root} .ant-alert-error')?.textContent.includes('权限加载失败')`), 'permissions failure visible');
    await search('Official520');
    assert.equal(await evaluate(`[...document.querySelectorAll('${root} button')].some(button=>['导入','同步官方 Registry'].includes(button.textContent))`), false);
    await clickText(`${root} .ant-alert-error button`, '重试');
    await poll(() => evaluate(`!document.querySelector('${root} .ant-alert-error') && [...document.querySelectorAll('${root} tr button')].some(button=>button.textContent==='导入')`), 'permissions recovered without granting defaults');
    fixture.disable(); console.log('PASS: catalog failed permissions do not enable writes; explicit retry restores actual capabilities'); return;
  }
  if (mode === 'readonly') {
    await search('Official520');
    assert.equal(await evaluate(`document.querySelectorAll('${root} tr button').length`), 0);
    assert.equal(await evaluate(`[...document.querySelectorAll('${root} button')].some(button=>button.textContent==='同步官方 Registry')`), false);
    assert.equal(fixture.writes.length, 0);
    fixture.disable(); console.log('PASS: catalog view-only permissions retain search and hide both import and synchronization actions'); return;
  }
  await clickReady(`${root} .ant-pagination-item-53`);
  await poll(() => evaluate(`document.querySelector('${root} tr[data-row-key="official-520"]') && !document.querySelector('${root} .ant-spin-spinning')`), 'actual last catalog page beyond 1000');
  assert.equal(fixture.reads.findLast(read => read.path === base).params.get('page'), '52');
  const source = `${root} .sp-data-table-toolbar-extra .ant-select`;
  const chooseSource = async label => {
    await evaluate(`document.querySelector('${source}').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    await clickText('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', label);
  };
  await chooseSource('内部');
  await poll(() => evaluate(`document.querySelector('${root} tr[data-row-key="internal:mcp:0"]') && document.querySelector('${root} .ant-pagination-item-active')?.textContent==='1' && !document.querySelector('${root} .ant-spin-spinning')`), 'finite source facet resets actual server page');
  assert.equal(fixture.reads.findLast(read => read.path === base).params.get('source'), 'INTERNAL');
  await search('Internal520'); assert.equal(await evaluate(`document.querySelectorAll('${root} tr button').length`), 0);
  await chooseSource('官方 MCP Registry');
  await poll(() => evaluate(`document.querySelectorAll('${root} tr[data-row-key]').length===0 && !document.querySelector('${root} .ant-spin-spinning')`), 'server source and keyword intersect');
  await search('Official520');
  assert.equal(await evaluate(`document.querySelectorAll('${root} tr a').length`), 0, 'untrusted Registry URL is not a script link');
  await clickText(`${root} tr button`, '导入');
  await poll(() => evaluate(`!!document.querySelector('${modal}')`), 'captured remote import');
  await evaluate(`location.hash='#/profile'`);
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${modal}')`), 'inactive import confirmation hidden');
  await clickMenu('扩展市场标准测试'); await poll(() => evaluate(`document.querySelector('${modal}')?.textContent.includes('Official520')`), 'owner restores exact import target');
  fixture.failWrite(); const before = fixture.writes.length;
  await evaluate(`(() => {const button=document.querySelector('${modal} .ant-modal-footer .ant-btn-primary');button.click();button.click();})()`);
  await poll(() => fixture.writes.length === before + 1, 'catalog same-frame single flight');
  assert.equal(await evaluate(`!!document.querySelector('${modal} .ant-modal-close')`), false);
  await poll(() => evaluate(`document.querySelector('${modal} .ant-alert-error')?.textContent.includes('扩展市场操作失败')`), 'failed import keeps target for retry');
  await clickText(`${modal} .ant-modal-footer button`, '导入');
  await poll(() => evaluate(`!document.querySelector('${modal}') && document.querySelector('${root} .ant-alert-success')?.textContent.includes('created-official-520')`), 'successful import receipt identifies actual draft');
  assert.equal(fixture.writes.at(-1).path, base + '/official-520/import');
  await search('Official519');
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} tr button')].some(button=>button.textContent==='导入')`), false, 'package-only format is not sent to the remote import endpoint');
  await clickText(`${root} tr button`, '导入运行草稿');
  await poll(() => evaluate(`document.querySelector('${modal}')?.textContent.includes('管理接口审核配置')`), 'runtime boundary uses real APIs, not an invented management page');
  await clickText(`${modal} .ant-modal-footer button`, '导入');
  await poll(() => evaluate(`!document.querySelector('${modal}') && document.querySelector('${root} .ant-alert-success')?.textContent.includes('created-official-519')`), 'runtime draft receipt');
  assert.equal(fixture.writes.at(-1).path, base + '/official-519/import-runtime');
  fixture.failRead(); await clickReady(`${root} button[aria-label="刷新"]`);
  await poll(() => evaluate(`!!document.querySelector('${root} .ant-alert-error')`), 'failed read retains safe visible data');
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} tr button')].every(button=>button.disabled)`), true);
  await clickText(`${root} .ant-alert-error button`, '重试');
  await poll(() => evaluate(`!document.querySelector('${root} .ant-alert-error') && !document.querySelector('${root} .ant-spin-spinning')`), 'catalog retry');
  const syncBefore = fixture.writes.length;
  await evaluate(`(() => {const button=[...document.querySelectorAll('${root} button')].find(button=>button.textContent==='同步官方 Registry');button.click();button.click();})()`);
  await poll(() => fixture.writes.length === syncBefore + 1, 'synchronization single flight');
  await poll(() => evaluate(`![...document.querySelectorAll('${root} button')].find(button=>button.textContent==='同步官方 Registry')?.disabled`), 'synchronization complete');
  if (workspace) {
    workspace.enable();
    await clickText(`${root} tr button`, '导入运行草稿'); await poll(() => evaluate(`!!document.querySelector('${modal}')`), 'old-scope confirmation');
    await evaluate(`localStorage.setItem('sp.tenantId',${JSON.stringify(workspace.tenantId)});window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:${JSON.stringify(workspace.tenantId)}}));`);
    await poll(() => evaluate(`!document.querySelector('${modal}')`), 'scope switch discards old target');
    await clickMenu('扩展市场标准测试');
    await poll(() => evaluate(`document.querySelector('${root} tr[data-row-key="internal:mcp:0"]') && !document.querySelector('${root} .ant-spin-spinning')`), 'new workspace resets query and page');
    assert.equal(await evaluate(`[...document.querySelectorAll('${root} button')].some(button=>button.textContent==='同步官方 Registry')`), false, 'tenant cannot synchronize platform metadata');
    assert.equal(await evaluate(`document.querySelector('${root} .ant-input-search input').value`), '');
    await evaluate(`localStorage.setItem('sp.tenantId','__platform__');window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:'__platform__'}));`);
    await clickMenu('扩展市场标准测试'); await poll(() => evaluate(`!!document.querySelector('${root} tr[data-row-key="internal:mcp:0"]')`), 'return to platform scope');
    workspace.disable();
  }
  assert.ok(fixture.reads.filter(read => read.path === base).every(read => Number(read.params.get('size')) === 20));
  fixture.disable(); console.log('PASS: catalog bounded server search beyond 500, platform layout, real format/permissions, captured inactive confirmation, single flight/retry, actual receipts and workspace isolation');
}
