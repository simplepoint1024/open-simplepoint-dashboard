import assert from 'node:assert/strict';

export async function smokeApiKeys({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  fixture.enable(); const base = '/ai/workbench/api-keys', root = '.nb-tab-panel-active .ai-api-keys-page';
  const form = '.sp-simple-table-drawer.ant-drawer-open', secret = '.ai-api-key-secret:not([style*="display: none"]) .ant-modal';
  const rotate = '.ai-api-key-rotate:not([style*="display: none"]) .ant-modal', deletion = '.sp-simple-table-delete:not([style*="display: none"]) .ant-modal';
  const context = async (mode, label = mode) => {
    fixture.mode(mode); await evaluate(`localStorage.setItem('sp.contextId:__platform__',${JSON.stringify('api-key-' + label)});window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:${JSON.stringify('api-key-' + label)}}}));`);
  };
  const doubleReady = async selector => {
    await poll(()=>evaluate(`(()=>{const b=document.querySelector(${JSON.stringify(selector)});return !!b && b.getClientRects().length>0 && !b.disabled && !b.classList.contains('ant-btn-loading');})()`), 'enabled real mutation action');
    await evaluate(`(()=>{const b=document.querySelector(${JSON.stringify(selector)});b.click();b.click();})()`);
  };
  const select = async (container, id) => {
    const row = container + ` [data-row-key="${id}"]`;
    await poll(()=>evaluate(`!!document.querySelector('${row} .ant-checkbox-input')`), 'actual identifiable key row');
    if (!await evaluate(`document.querySelector('${row} .ant-checkbox-input').checked`)) await clickReady(row);
  };
  const closeSecret = async () => {await clickText(secret + ' button', '关闭'); await poll(()=>evaluate(`!document.querySelector('${secret}')`), 'one-time output cleared');};
  await clickMenu('API Key 生命周期测试'); await poll(()=>evaluate(`!!document.querySelector('${root} [data-row-key="api-key-0"]')`), 'actual key list');
  const unknownRow = root + ' [data-row-key="api-key-1"]';
  assert.equal(await evaluate(`[...document.querySelectorAll('${unknownRow} .ant-tag')].some(tag=>tag.classList.contains('ant-tag-green'))`), false, 'masked enabled is not success');
  assert.equal(await evaluate(`document.querySelector('${unknownRow}')?.textContent.includes('Invalid Date')`), false);
  await clickReady(root + ' .ant-pagination-item-27'); await poll(()=>evaluate(`!!document.querySelector('${root} [data-row-key="api-key-520"]')`), 'key beyond first 500');
  await clickReady(root + ' .ant-pagination-item-1'); await clickText(root + ' button', '新增'); await poll(()=>evaluate(`!!document.querySelector('${form} #root_name')`), 'standard issuance form');
  await fill(form + ' #root_name', '   '); await clickReady(form + ' button[type="submit"]'); await poll(()=>evaluate(`!!document.querySelector('${form} .ant-form-item-has-error')`), 'blank issuance rejected'); assert.equal(fixture.writes.length, 0);
  await fill(form + ' #root_name', 'Actual issued key');
  for (const value of ['0', '100001', '1.5']) {
    await fill(form + ' #root_rateLimitPerMinute', value); await clickReady(form + ' button[type="submit"]');
    await poll(()=>evaluate(`!!document.querySelector('${form} .ant-form-item-has-error')`), 'actual integer rate bounds'); assert.equal(fixture.writes.length, 0);
  }
  await fill(form + ' #root_rateLimitPerMinute', '17'); fixture.failWrite('POST', base); await doubleReady(form + ' button[type="submit"]');
  await poll(()=>evaluate(`!!document.querySelector('${form} .ant-alert-error')`), 'issuance failure inline'); assert.equal(fixture.writes.length, 1);
  assert.equal(await evaluate(`document.querySelector('${form} #root_name').value`), 'Actual issued key');
  await evaluate(`location.hash='#/profile'`); await poll(()=>evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${form}')`), 'issuance draft stays with inactive owner');
  await clickMenu('API Key 生命周期测试'); await poll(()=>evaluate(`document.querySelector('${form} #root_name')?.value==='Actual issued key'`), 'issuance draft retained');
  await doubleReady(form + ' button[type="submit"]'); await poll(()=>evaluate(`!!document.querySelector('${secret} .ant-typography-copy')`), 'response-only real one-time output'); assert.equal(fixture.writes.length, 2);
  const issued = fixture.writes.at(-1).issuedKey;
  assert.equal(await evaluate(`document.querySelector('${secret}')?.textContent.includes(${JSON.stringify(issued)})`), true);
  await evaluate(`Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async value=>{window.__isolatedCopiedKey=value;}}});const original=document.execCommand;document.execCommand=function(command,...args){if(command==='copy'){window.__isolatedCopiedKey=window.getSelection()?.toString();return true;}return original.call(this,command,...args);};`);
  await clickReady(secret + ' .ant-typography-copy'); await poll(()=>evaluate(`window.__isolatedCopiedKey===${JSON.stringify(issued)}`), 'copy actual dummy response only, no system clipboard');
  await evaluate(`location.hash='#/profile'`); await poll(()=>evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.body.textContent.includes(${JSON.stringify(issued)})`), 'inactive secret is removed from the DOM');
  await clickMenu('API Key 生命周期测试'); await poll(()=>evaluate(`document.querySelector('${secret}')?.textContent.includes(${JSON.stringify(issued)})`), 'one-time output retained only for its owner');
  await closeSecret(); assert.equal(await evaluate(`document.body.textContent.includes(${JSON.stringify(issued)})`), false);
  assert.equal(await evaluate(`JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)]).includes(${JSON.stringify(issued)})`), false, 'secret never persisted in browser storage');
  await select(root, 'api-key-0'); await clickText(root + ' button', '轮换 Key'); await poll(()=>evaluate(`document.querySelector('${rotate}')?.textContent.includes('Key000')`), 'captured key rotation confirmation');
  fixture.failWrite('POST', base + '/api-key-0/rotate'); await doubleReady(rotate + ' .ant-modal-footer .ant-btn-primary');
  await poll(()=>evaluate(`!!document.querySelector('${rotate} .ant-alert-error')`), 'rotation failure retains confirmation'); assert.equal(fixture.writes.length, 3);
  await evaluate(`location.hash='#/profile'`); await poll(()=>evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${rotate}')`), 'rotation confirmation hidden');
  await clickMenu('API Key 生命周期测试'); await poll(()=>evaluate(`document.querySelector('${rotate}')?.textContent.includes('Key000')`), 'captured rotation restored');
  await doubleReady(rotate + ' .ant-modal-footer .ant-btn-primary'); await poll(()=>fixture.writes.length === 4, 'rotation single flight retry');
  await evaluate(`location.hash='#/profile'`); await poll(()=>fixture.writes.at(-1).completed, 'started rotation completed in inactive owner');
  const rotated = fixture.writes.at(-1).issuedKey; assert.equal(await evaluate(`document.body.textContent.includes(${JSON.stringify(rotated)})`), false);
  await clickMenu('API Key 生命周期测试'); await poll(()=>evaluate(`document.querySelector('${secret}')?.textContent.includes(${JSON.stringify(rotated)})`), 'late rotated output restored only to owner');
  assert.equal(fixture.rows.find(row=>row.id==='api-key-0').enabled, false); assert.equal(fixture.rows.find(row=>row.id==='api-key-0').rateLimitPerMinute, 17);
  await closeSecret(); await select(root, 'api-key-0'); await clickText(root + ' button', '删除'); await poll(()=>evaluate(`!!document.querySelector('${deletion}')`), 'standard owned revocation');
  fixture.failWrite('DELETE', base); await doubleReady(deletion + ' .ant-modal-footer .ant-btn-primary'); await poll(()=>evaluate(`!!document.querySelector('${deletion} .ant-alert-error')`), 'standard delete retains failure for retry');
  await evaluate(`location.hash='#/profile'`); await poll(()=>evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${deletion}')`), 'standard delete hidden in inactive owner');
  await clickMenu('API Key 生命周期测试'); await poll(()=>evaluate(`!!document.querySelector('${deletion} .ant-alert-error')`), 'standard failure restored'); await clickReady(deletion + ' .ant-modal-footer .ant-btn-default');
  await context('fields'); await clickMenu('API Key 生命周期测试'); await select(root, 'api-key-0'); await clickText(root + ' button', '编辑');
  await poll(()=>evaluate(`(()=>{const field=document.querySelector('${form} #root_enabled');return field?.value==='***' && (field.disabled || field.readOnly);})()`), 'masked boolean is readonly text, not a checked box');
  await fill(form + ' #root_description', 'Metadata only'); await clickReady(form + ' button[type="submit"]'); await poll(()=>evaluate(`!document.querySelector('${form}')`), 'metadata-only edit with unreadable policy');
  assert.deepEqual(fixture.writes.at(-1).data, {id: 'api-key-0', description: 'Metadata only'});
  await context('rotate'); await select(root, 'api-key-0');
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} button')].some(b=>/新增|编辑|删除/.test(b.textContent))`), false, 'rotate authority is independent');
  await clickText(root + ' button', '轮换 Key'); await doubleReady(rotate + ' .ant-modal-footer .ant-btn-primary'); await poll(()=>fixture.writes.at(-1).path.endsWith('/api-key-0/rotate') && !fixture.writes.at(-1).completed, 'old-scope request captured');
  const pending = fixture.writes.at(-1); await context('view'); await poll(()=>pending.completed, 'old-scope transport settled');
  await poll(()=>evaluate(`!!document.querySelector('${root} [data-row-key="api-key-0"]')`), 'new readonly scope remains usable');
  assert.equal(await evaluate(`!!document.querySelector('${secret}') || !!document.querySelector('${rotate}')`), false, 'old scope cannot reveal a key in the new role');
  await context('masked'); await select(root, 'api-key-0'); await clickText(root + ' button', '轮换 Key'); await clickReady(rotate + ' .ant-modal-footer .ant-btn-primary');
  await poll(()=>evaluate(`!!document.querySelector('${secret} .ant-alert-warning')`), 'masked output is an explicit unavailable result'); assert.equal(await evaluate(`!!document.querySelector('${secret} .ant-typography-copy')`), false); await closeSecret();
  await context('identity'); await poll(()=>evaluate(`(()=>{const button=[...document.querySelectorAll('${root} button')].find(b=>b.textContent.replaceAll(' ','')==='轮换Key');return !!button && button.getClientRects().length>0 && [...document.querySelectorAll('${root} .ant-alert-info')].some(alert=>alert.textContent.includes('标识不可用'));})()`), 'partial identifier warning and actual record action rendered');
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} button')].find(b=>b.textContent.replaceAll(' ','')==='轮换Key')?.disabled`), true);
  const noWrite = fixture.writes.length; await context('denied'); await poll(()=>evaluate(`!!document.querySelector('${root} .ant-alert-error')`), 'schema failure denies writes'); assert.equal(fixture.writes.length, noWrite);

  // Exercise the actual Common page's default CRUD path, not the API-key-specific submit handler.
  fixture.dictionaryMode('all'); await context('all', 'default-first'); await clickMenu('契约测试 Dictionary');
  const dictionary = '.nb-tab-panel-active [data-resource="/common/platform/dictionaries"]';
  await select(dictionary, 'dict-scope-0'); await evaluate(`(()=>{window.__defaultCrudRoot=document.querySelector('${dictionary}');return true;})()`);
  await clickText(dictionary + ' button', '编辑'); await poll(()=>evaluate(`!!document.querySelector('${form} #root_name')`), 'default policy editor');
  await fill(form + ' #root_name', 'Old scope default write'); const defaultCount = fixture.writes.length;
  await doubleReady(form + ' button[type="submit"]'); await poll(()=>fixture.writes.length===defaultCount+1 && !fixture.writes.at(-1).completed, 'default write captured before scope change');
  const defaultPending = fixture.writes.at(-1); fixture.dictionaryMode('view'); await context('view', 'default-view');
  await poll(()=>defaultPending.completed && evaluate(`!document.querySelector('${form}') && !!document.querySelector('${dictionary}')`), 'actual auth transition clears the old default editor');
  fixture.dictionaryMode('all'); await context('all', 'default-fresh'); await select(dictionary, 'dict-scope-0');
  await clickText(dictionary + ' button', '编辑'); await poll(()=>evaluate(`!!document.querySelector('${form} #root_description')`), 'default controller not stuck busy after scope abort');
  await fill(form + ' #root_description', 'New scope default metadata'); await clickReady(form + ' button[type="submit"]'); await poll(()=>evaluate(`!document.querySelector('${form}')`), 'new-scope default write succeeds');
  assert.equal(fixture.writes.at(-1).mode, 'all'); assert.equal(fixture.writes.at(-1).data.description, 'New scope default metadata');
  await select(dictionary, 'dict-scope-0'); await clickText(dictionary + ' button', '删除'); await poll(()=>evaluate(`!!document.querySelector('${deletion}')`), 'default owned deletion captured');
  fixture.failWrite('DELETE', '/common/platform/dictionaries'); await doubleReady(deletion + ' .ant-modal-footer .ant-btn-primary'); await poll(()=>evaluate(`!!document.querySelector('${deletion} .ant-alert-error')`), 'default deletion failure inline');
  await clickReady(deletion + ' .ant-modal-footer .ant-btn-primary'); await poll(()=>evaluate(`!document.querySelector('${deletion}')`), 'default deletion retry');
  assert.equal(fixture.writes.at(-1).params.get('ids'), 'dict-scope-0'); assert.equal(fixture.dictionaries.length, 0);
  console.log('PASS: API Key response-only owned disclosure/copy, captured rotation, real CRUD validation/retry, disabled policy preservation, masked metadata edit and independent grants; shared default CRUD scoped abort/reset/owned delete');
}
