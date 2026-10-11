import assert from 'node:assert/strict';

export async function smokeAppWorkbench({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture, workspace}) {
  workspace.enable(); fixture.enable(workspace.tenantId);
  await evaluate(`localStorage.setItem('sp.tenantId',${JSON.stringify(workspace.tenantId)});window.dispatchEvent(new CustomEvent('sp-set-tenant',{detail:${JSON.stringify(workspace.tenantId)}}));`);
  const root = '.nb-tab-panel-active .app-workbench';
  const create = '.app-application-create:not([style*="display: none"]) .ant-modal';
  const detail = '.app-application-details.ant-drawer-open';
  const add = '.app-page-create:not([style*="display: none"]) .ant-modal';
  const discard = '.app-discard-draft:not([style*="display: none"]) .ant-modal';
  const context = async mode => {
    fixture.mode(mode); await evaluate(`localStorage.setItem(${JSON.stringify('sp.contextId:' + workspace.tenantId)},${JSON.stringify('app-' + mode)});window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:${JSON.stringify(workspace.tenantId)},contextId:${JSON.stringify('app-' + mode)}}}));`);
  };
  await clickMenu('应用工作台测试');
  try {
    await poll(() => evaluate(`!!document.querySelector('${root} [data-row-key="app-0"]')`), 'real tenant-owned application page');
  } catch (error) {
    console.error('App initial page diagnostics', await evaluate(`({text:document.querySelector('.nb-tab-panel-active')?.textContent,rows:[...document.querySelectorAll('[data-row-key]')].map(row=>row.getAttribute('data-row-key')),appRoots:document.querySelectorAll('.app-workbench').length})`));
    throw error;
  }
  assert.equal(await evaluate(`!!document.querySelector('${root} .ant-card')`), false);
  await clickReady(root + ' .ant-pagination-item-27'); await poll(() => evaluate(`!!document.querySelector('${root} [data-row-key="app-520"]')`), 'application beyond first 500');
  await fill(root + ' input.ant-input', 'Application520'); await clickReady(root + ' .ant-input-search-btn');
  await poll(() => evaluate(`document.querySelectorAll('${root} [data-row-key]').length===1 && !!document.querySelector('${root} [data-row-key="app-520"]')`), 'server keyword search resets page');
  await fill(root + ' input.ant-input', ''); await clickReady(root + ' .ant-input-search-btn');
  await poll(() => evaluate(`!!document.querySelector('${root} [data-row-key="app-0"]')`), 'application search cleared');
  await clickText(root + ' .sp-data-table-toolbar-extra button', '新建应用');
  await poll(() => evaluate(`!!document.querySelector('${create} #root_name')`), 'actual new application SForm');
  await fill(create + ' #root_name', '   '); await clickReady(create + ' .ant-modal-footer .ant-btn-primary');
  await poll(() => evaluate(`!!document.querySelector('${create} .ant-form-item-has-error')`), 'blank app name validation'); assert.equal(fixture.writes.length, 0);
  await fill(create + ' #root_name', 'Actual Application'); await fill(create + ' #root_mainPageName', 'Actual Main');
  await clickReady(create + ' .ant-modal-close'); await poll(() => evaluate(`!!document.querySelector('${discard}')`), 'owned app draft discard');
  await clickText(discard + ' button', '继续编辑'); await poll(() => evaluate(`!document.querySelector('${discard}')`), 'draft kept');
  await evaluate(`location.hash='#/profile'`); await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${create}')`), 'inactive app editor hidden');
  await clickMenu('应用工作台测试'); await poll(() => evaluate(`document.querySelector('${create} #root_name')?.value==='Actual Application'`), 'application draft retained');
  fixture.failWrite('/app/applications');
  await evaluate(`(() => {const b=document.querySelector('${create} .ant-modal-footer .ant-btn-primary');b.click();b.click();})()`);
  await poll(() => evaluate(`document.querySelector('${create} .ant-alert-error')?.textContent.includes('应用保存失败')`), 'inline app failure');
  assert.equal(fixture.writes.length, 1); assert.equal(await evaluate(`document.querySelector('${create} #root_mainPageName').value`), 'Actual Main');
  await clickReady(create + ' .ant-modal-footer .ant-btn-primary');
  await poll(() => evaluate(`document.querySelector('${detail}')?.textContent.includes('Actual Main') && !document.querySelector('${create}')`), 'actual created metadata and main page');
  assert.equal(fixture.applications[0].name, 'Actual Application');
  await clickReady(detail + ' .ant-drawer-close'); await poll(() => evaluate(`!document.querySelector('${detail}')`), 'new application details closed');
  await clickText(root + ' [data-row-key="app-0"] button', '查看');
  await poll(() => evaluate(`!!document.querySelector('${detail} [data-row-key="app-0-page-0"]')`), 'selected actual app detail');
  assert.equal(await evaluate(`document.querySelectorAll('${detail} [data-row-key]').length`), 20, 'large complete metadata collection has bounded DOM pages');
  await clickReady(detail + ' .ant-pagination-item-27'); await poll(() => evaluate(`!!document.querySelector('${detail} [data-row-key="app-0-page-520"]')`), 'all attached pages remain accessible');
  await clickText(detail + ' button', '添加页面'); await poll(() => evaluate(`!!document.querySelector('${add} #root_name')`), 'actual attached page form');
  assert.equal(await evaluate(`!!document.querySelector('${detail} .ant-drawer-close')`), false, 'cannot close parent behind child draft');
  await fill(add + ' #root_name', 'Captured Page'); fixture.failWrite('/app/applications/app-0/pages');
  await evaluate(`location.hash='#/profile'`); await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${add}')`), 'child editor hidden in inactive owner');
  await clickMenu('应用工作台测试'); await poll(() => evaluate(`document.querySelector('${add} #root_name')?.value==='Captured Page'`), 'child draft retained');
  await evaluate(`(() => {const b=document.querySelector('${add} .ant-modal-footer .ant-btn-primary');b.click();b.click();})()`);
  await poll(() => evaluate(`document.querySelector('${add} .ant-alert-error')?.textContent.includes('应用保存失败')`), 'attached page failure retains captured target');
  assert.equal(fixture.writes.length, 3); await clickReady(add + ' .ant-modal-footer .ant-btn-primary');
  await poll(() => evaluate(`!document.querySelector('${add}')`), 'attached page retry');
  assert.equal(fixture.writes.at(-1).path, '/app/applications/app-0/pages'); assert.equal(fixture.pages.get('app-0').at(-1).name, 'Captured Page');
  await clickReady(detail + ' .ant-drawer-close');
  await context('page'); await clickMenu('应用工作台测试'); await poll(() => evaluate(`!!document.querySelector('${root} [data-row-key="app-0"]')`), 'independent add-page permission');
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} .sp-data-table-toolbar-extra button')].some(b=>b.textContent.includes('新建应用'))`), false);
  await clickText(root + ' [data-row-key="app-0"] button', '查看'); await poll(() => evaluate(`[...document.querySelectorAll('${detail} button')].some(b=>b.textContent.includes('添加页面'))`), 'add-page does not require application-create');
  await clickReady(detail + ' .ant-drawer-close'); await context('readonly'); await clickMenu('应用工作台测试');
  await poll(() => evaluate(`!!document.querySelector('${root} [data-row-key="app-0"]')`), 'view-only application list');
  await clickText(root + ' [data-row-key="app-0"] button', '查看'); await poll(() => evaluate(`!!document.querySelector('${detail} [data-row-key]')`), 'view-only application detail');
  assert.equal(await evaluate(`[...document.querySelectorAll('${detail} button')].some(b=>b.textContent.includes('添加页面'))`), false);
  await clickReady(detail + ' .ant-drawer-close'); const count = fixture.writes.length;
  await context('denied'); await poll(() => evaluate(`document.querySelector('${root} .ant-alert-error')?.textContent.includes('操作权限加载失败')`), 'permissions error does not invent write controls');
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} .sp-data-table-toolbar-extra button')].some(b=>b.textContent.includes('新建应用'))`), false); assert.equal(fixture.writes.length, count);
  console.log('PASS: App actual tenant context, server paging/search, owned nested SForms/discard, captured add-page target, single flight/retry and independent permissions');
}
