import assert from 'node:assert/strict';

export async function smokeDictionaryLifecycle({clickMenu, clickReady, clickText, fill, poll, evaluate, send, fixture}) {
  fixture.enable();
  const root = '.nb-tab-panel-active [data-resource="/common/platform/dictionaries"]';
  const panel = '.dictionary-item-config.ant-drawer-open', child = panel + ' [data-resource="/common/platform/dictionary-items"]';
  const form = '.sp-simple-table-drawer.ant-drawer-open';
  const discard = '.sp-simple-table-discard:not([style*="display: none"]) .ant-modal';
  const deletion = '.sp-simple-table-delete:not([style*="display: none"]) .ant-modal';
  const changeScope = async mode => {
    fixture.mode(mode); await evaluate(`localStorage.setItem('sp.contextId:__platform__',${JSON.stringify('dictionary-' + mode)});window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:${JSON.stringify('dictionary-' + mode)}}}));`);
  };
  const select = async (container, id) => {
    await poll(()=>evaluate(`!!document.querySelector('${container} [data-row-key="${id}"] .ant-checkbox-input')`), 'real selectable configuration record');
    if (!await evaluate(`document.querySelector('${container} [data-row-key="${id}"] .ant-checkbox-input').checked`)) await clickReady(`${container} [data-row-key="${id}"] .ant-checkbox-input`);
  };
  const open = async code => {
    await select(root, 'dictionary-' + code); await clickText(root + ' button', '配置字典项');
    await poll(()=>evaluate(`!!document.querySelector('${child} [data-row-key="${code}-item-0"]')`), 'real scoped child page');
  };
  const twice = async selector => {
    await poll(()=>evaluate(`(()=>{const b=document.querySelector('${selector}');return !!b && !!b.getClientRects().length && !b.disabled && !b.classList.contains('ant-btn-loading');})()`), 'actual enabled write action');
    await evaluate(`(()=>{const b=document.querySelector('${selector}');b.click();b.click();})()`);
  };
  await clickMenu('契约测试 Dictionary'); await open('alpha');
  assert.equal(await evaluate(`!!document.querySelector('${panel} .ant-drawer-close')`), true, 'real parent close action exists before a child interaction');
  assert.equal(fixture.reads.filter(read=>read.path.endsWith('/dictionaries/schema')).length, 1, 'permission and table subscribers share a single schema read');
  await clickReady(child + ' .ant-pagination-item-27');
  await poll(()=>evaluate(`!!document.querySelector('${child} [data-row-key="alpha-item-520"]')`), 'child item beyond first 500');
  await select(child, 'alpha-item-520'); await clickText(child + ' button', '删除'); await poll(()=>evaluate(`!!document.querySelector('${deletion}')`), 'owned captured child deletion');
  assert.equal(await evaluate(`!!document.querySelector('${panel} .ant-drawer-close')`), false, 'parent cannot close a child confirmation');
  fixture.failWrite('DELETE'); await twice(deletion + ' .ant-modal-footer .ant-btn-primary');
  await poll(()=>evaluate(`document.querySelector('${deletion} .ant-alert-error')?.textContent.includes('字典项保存失败')`), 'failed child delete retained');
  await evaluate(`location.hash='#/profile'`); await poll(()=>evaluate(`!!document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${panel}') && !document.querySelector('${deletion}')`), 'both owner overlays hidden on another tab');
  await clickMenu('契约测试 Dictionary'); await poll(()=>evaluate(`!!document.querySelector('${deletion} .ant-alert-error')`), 'child confirmation retained');
  await twice(deletion + ' .ant-modal-footer .ant-btn-primary');
  await poll(()=>evaluate(`!document.querySelector('${deletion}') && document.querySelector('${child} .ant-pagination-item-active')?.textContent==='26' && !!document.querySelector('${child} [data-row-key="alpha-item-519"]')`), 'last-row deletion clamps and refetches the actual remaining server page');
  assert.equal(fixture.writes.at(-1).params.get('ids'), 'alpha-item-520');
  await clickReady(child + ' .ant-pagination-item-1');
  await poll(()=>evaluate(`(()=>{const button=[...document.querySelectorAll('${child} button')].find(element=>element.textContent.replaceAll(' ','')==='新增');return !!button && !button.disabled;})()`), 'actual enabled nested add action');
  await evaluate(`(()=>{const add=[...document.querySelectorAll('${child} button')].find(element=>element.textContent.replaceAll(' ','')==='新增');const close=document.querySelector('${panel} .ant-drawer-close');add.click();close.click();})()`);
  await poll(()=>evaluate(`!!document.querySelector('${form} #root_name')`), 'nested platform item form');
  await fill(form + ' #root_name', ' '); await fill(form + ' #root_value', 'actual');
  const before = fixture.writes.length; await clickReady(form + ' button[type="submit"]');
  await poll(()=>evaluate(`!!document.querySelector('${form} .ant-form-item-has-error')`), 'backend-required nonblank item name validated'); assert.equal(fixture.writes.length, before);
  await fill(form + ' #root_name', 'Actual created item'); await fill(form + ' #root_description', 'Retained actual draft');
  assert.equal(await evaluate(`!!document.querySelector('${panel} .ant-drawer-close')`), false, 'parent cannot discard a nested form');
  await clickReady(form + ' .ant-drawer-close'); await poll(()=>evaluate(`!!document.querySelector('${discard}')`), 'child dirty close confirmation');
  await evaluate(`location.hash='#/profile'`); await poll(()=>evaluate(`!!document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${panel}') && !document.querySelector('${form}') && !document.querySelector('${discard}')`), 'all nested draft overlays belong to their page');
  await clickMenu('契约测试 Dictionary'); await poll(()=>evaluate(`!!document.querySelector('${discard}')`), 'dirty child draft retained'); await clickText(discard + ' button', '继续编辑');
  assert.equal(await evaluate(`document.querySelector('${form} #root_name').value`), 'Actual created item');
  fixture.failWrite('POST'); await twice(form + ' button[type="submit"]');
  await poll(()=>evaluate(`document.querySelector('${form} .ant-alert-error')?.textContent.includes('字典项保存失败')`), 'child save failure retains draft and parent lock');
  assert.equal(fixture.writes.length, before + 1); assert.equal(fixture.writes.at(-1).data.dictionaryCode, 'alpha');
  await twice(form + ' button[type="submit"]'); await poll(()=>evaluate(`!document.querySelector('${form}') && !!document.querySelector('${panel} .ant-drawer-close')`), 'successful child retry unlocks only its parent');
  assert.equal(fixture.writes.length, before + 2); assert.equal(fixture.writes.at(-1).data.dictionaryCode, 'alpha');
  assert.equal(await evaluate(`document.querySelector('${root} [data-row-key="dictionary-alpha"] .ant-checkbox-input').checked`), true, 'child mutation does not remount the parent controller');
  // Keyboard resizing uses the existing panel geometry; mouse listeners leave with their owner.
  const separator = panel + ' [role="separator"]'; const height = await evaluate(`Number(document.querySelector('${separator}').getAttribute('aria-valuenow'))`);
  await evaluate(`document.querySelector('${separator}').dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowUp',bubbles:true}))`);
  await poll(()=>evaluate(`Number(document.querySelector('${separator}').getAttribute('aria-valuenow'))===${height + 24}`), 'keyboard resize is effective');
  await evaluate(`(()=>{const add=window.addEventListener.bind(window),remove=window.removeEventListener.bind(window);window.__resizeListeners=new Map();window.addEventListener=(type,handler,...options)=>{if(['mousemove','mouseup'].includes(type))window.__resizeListeners.set(handler,type);return add(type,handler,...options);};window.removeEventListener=(type,handler,...options)=>{window.__resizeListeners.delete(handler);return remove(type,handler,...options);};document.querySelector('${separator}').dispatchEvent(new MouseEvent('mousedown',{clientY:600,bubbles:true}));return true;})()`);
  assert.equal(await evaluate(`window.__resizeListeners.size`), 2);
  await evaluate(`window.dispatchEvent(new MouseEvent('mousemove',{clientY:500}))`);
  await poll(()=>evaluate(`Number(document.querySelector('${separator}').getAttribute('aria-valuenow'))===${height + 124}`), 'pointer resize is effective');
  await evaluate(`location.hash='#/profile'`); await poll(()=>evaluate(`window.__resizeListeners.size===0 && !document.querySelector('${panel}')`), 'inactive owner releases its active drag listeners');
  await clickMenu('契约测试 Dictionary'); await clickReady(panel + ' .ant-drawer-close'); await poll(()=>evaluate(`!document.querySelector('${panel}')`), 'clean owner closes');
  await clickReady(root + ' [data-row-key="dictionary-alpha"] .ant-checkbox-input'); await open('beta');
  assert.ok(fixture.reads.filter(read=>read.path === '/common/platform/dictionary-items').some(read=>read.params.get('parentDictionaryCode') === 'beta'));
  await select(child, 'beta-item-0'); await clickText(child + ' button', '编辑'); await poll(()=>evaluate(`document.querySelector('${form} #root_name')?.value==='beta item 000'`), 'new dictionary has its own editor');
  await fill(form + ' #root_description', 'Old role pending edit'); await twice(form + ' button[type="submit"]');
  await poll(()=>fixture.writes.at(-1).method === 'PUT' && !fixture.writes.at(-1).completed, 'old-role child request captured');
  const old = fixture.writes.at(-1); await changeScope('view'); await poll(()=>old.completed && evaluate(`!document.querySelector('${panel}') && !document.querySelector('${form}') && !!document.querySelector('${root} [data-row-key="dictionary-alpha"]')`), 'scope transition drops old parent/child sessions');
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} button')].some(button=>button.textContent.includes('配置字典项'))`), false, 'view permission does not imply configuration');
  await changeScope('config'); await open('alpha');
  assert.equal(await evaluate(`[...document.querySelectorAll('${child} .sp-table-toolbar-main button')].some(button=>/新增|编辑|删除/.test(button.textContent))`), false, 'configuration permission does not imply child CRUD');
  await clickReady(panel + ' .ant-drawer-close'); await changeScope('readonly-code'); await open('alpha');
  await select(child, 'alpha-item-0'); await clickText(child + ' button', '编辑'); await poll(()=>evaluate(`!!document.querySelector('${form} #root_description')`), 'fresh role can open a new child editor');
  await fill(form + ' #root_description', 'New role real metadata'); await clickReady(form + ' button[type="submit"]');
  await poll(()=>evaluate(`!document.querySelector('${form}')`), 'fresh role child write is not blocked by old aborted request');
  assert.equal(fixture.writes.at(-1).data.id, 'alpha-item-0'); assert.equal(fixture.writes.at(-1).data.dictionaryCode, 'alpha');
  assert.equal(fixture.writes.at(-1).data.description, 'New role real metadata');
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 400, deviceScaleFactor: 1, mobile: false});
  await poll(()=>evaluate(`Number(document.querySelector('${separator}').getAttribute('aria-valuenow'))===320`), 'real viewport resize clamps the panel');
  try {
    await poll(()=>evaluate(`(()=>{const box=document.querySelector('${panel} .ant-drawer-content-wrapper').getBoundingClientRect();return box.height <= 320 && box.bottom <= innerHeight + 1;})()`), 'configuration panel settles within the real short viewport');
  } catch (error) {
    console.error('Short configuration geometry', await evaluate(`(()=>{const element=document.querySelector('${panel} .ant-drawer-content-wrapper');const box=element.getBoundingClientRect();return {height:box.height,bottom:box.bottom,viewport:innerHeight,style:element.getAttribute('style'),computedHeight:getComputedStyle(element).height,transform:getComputedStyle(element).transform};})()`));
    throw error;
  }
  await clickReady(panel + ' .ant-drawer-close');
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
  await changeScope('masked-code'); await poll(()=>evaluate(`document.querySelector('${root} [data-row-key="dictionary-alpha"]')?.textContent.includes('***')`), 'masked dictionary code rendered'); await select(root, 'dictionary-alpha');
  await poll(()=>evaluate(`(()=>{const button=[...document.querySelectorAll('${root} button')].find(b=>b.textContent.replaceAll(' ','')==='配置字典项');return !!button && button.getClientRects().length>0 && button.disabled;})()`), 'real configuration action disabled without a readable code');
  const end = fixture.writes.length; await changeScope('child-denied'); await select(root, 'dictionary-alpha'); await clickText(root + ' button', '配置字典项');
  await poll(()=>evaluate(`!!document.querySelector('${panel} .ant-alert-error')`), 'failed child schema is visible without weakening configuration permissions');
  assert.equal(fixture.writes.length, end); await clickReady(panel + ' .ant-drawer-close');
  await changeScope('denied'); await poll(()=>evaluate(`!!document.querySelector('${root} .ant-alert-error')`), 'failed parent permissions never enable configuration'); assert.equal(fixture.writes.length, end);
  await clickMenu('租户成员分配测试');
  const tenant = '.nb-tab-panel-active [data-resource="/common/platform/tenants"]';
  await select(tenant, fixture.tenant.id); await clickText(tenant + ' button', '编辑');
  await poll(()=>evaluate(`!!document.querySelector('${form} #root_ownerId')`), 'real tenant schema owner picker');
  await poll(()=>evaluate(`(()=>{const input=document.querySelector('${form} #root_ownerId');return !!input && input.closest('.ant-select')?.classList.contains('ant-select-disabled');})()`), 'the actual readonly owner remains a disabled UserPicker');
  await poll(()=>evaluate(`document.querySelector('${form}')?.textContent.includes('Actual owner')`), 'readonly owner resolves to the actual user name');
  const directoryReads = fixture.reads.filter(read=>read.path.includes('/users/picker/')).length;
  await evaluate(`document.querySelector('${form} #root_ownerId').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  assert.equal(await evaluate(`!!document.querySelector('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')`), false, 'readonly picker cannot open an editable directory');
  assert.equal(fixture.reads.filter(read=>read.path.includes('/users/picker/')).length, directoryReads);
  await fill(form + ' #root_description', 'Metadata without owner reassignment'); await clickReady(form + ' button[type="submit"]');
  await poll(()=>evaluate(`!document.querySelector('${form}')`), 'other writable tenant fields remain editable');
  assert.equal('ownerId' in fixture.writes.at(-1).data, false); assert.equal(fixture.tenant.ownerId, 'owner-real-id');
  assert.equal(fixture.tenant.description, 'Metadata without owner reassignment');
  console.log('PASS: Dictionary actual scoped pages beyond 500, captured nested CRUD/delete, last-page clamp, dirty/inactive retention, parent close barriers, retry/single flight, drag cleanup/keyboard resize, role isolation and independent configuration grants');
  console.log('PASS: Tenant actual HTTP schema readonly UserPicker, real user hydration and writable metadata without owner reassignment');
}
