import assert from 'node:assert/strict';

export async function smokeRedisMutations({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  fixture.enableWrites(); await clickMenu('Redis 查询测试');
  const root = '.nb-tab-panel-active .monitoring-redis-page', modal = '.redis-entry-editor:not([style*="display: none"]) .ant-modal';
  const deletion = '.redis-entry-delete:not([style*="display: none"]) .ant-modal';
  const discard = '.redis-discard-draft:not([style*="display: none"]) .ant-modal';
  const key = ' a,b ', row = `${root} [data-row-key="${key}"]`;
  await clickText(`${root} .sp-data-table-toolbar-extra button`, '新增');
  await poll(() => evaluate(`!!document.querySelector('${modal} #root_key')`), 'real Redis SForm');
  await fill(`${modal} #root_key`, ' empty-key '); await fill(`${modal} #root_value`, '');
  await clickReady(`${modal} #root_persistent`);
  await poll(() => evaluate(`!!document.querySelector('${modal} #root_ttlSeconds')`), 'nonpersistent TTL field');
  await clickReady(`${modal} .ant-modal-footer .ant-btn-primary`);
  await poll(() => evaluate(`!!document.querySelector('${modal} .ant-form-item-has-error')`), 'empty TTL is required'); assert.equal(fixture.writes.length, 0);
  for (const invalid of ['0', '1.5']) {
    await fill(`${modal} #root_ttlSeconds`, invalid); await clickReady(`${modal} .ant-modal-footer .ant-btn-primary`);
    await poll(() => evaluate(`!!document.querySelector('${modal} .ant-form-item-has-error')`), 'positive integer TTL validation'); assert.equal(fixture.writes.length, 0);
  }
  await fill(`${modal} #root_ttlSeconds`, '120');
  assert.equal(await evaluate(`document.querySelector('${root} #redis-scan-pattern').disabled`), true, 'cannot remount and lose an editor by changing scan scope');
  await evaluate(`location.hash='#/profile'`); await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${modal}')`), 'inactive Redis editor hidden');
  await clickMenu('Redis 查询测试'); await poll(() => evaluate(`document.querySelector('${modal} #root_ttlSeconds')?.value==='120'`), 'TTL draft retained');
  fixture.failWrite(); await evaluate(`(() => {const b=document.querySelector('${modal} .ant-modal-footer .ant-btn-primary');b.click();b.click();})()`);
  await poll(() => evaluate(`document.querySelector('${modal} .ant-alert-error')?.textContent.includes('Redis 保存失败')`), 'inline create failure');
  assert.equal(fixture.writes.length, 1); assert.deepEqual(fixture.writes[0].data, {key: ' empty-key ', value: '', persistent: false, ttlSeconds: 120});
  await clickReady(`${modal} .ant-modal-footer .ant-btn-primary`); await poll(() => evaluate(`!document.querySelector('${modal}')`), 'Redis create retry');
  assert.equal(fixture.values.get(' empty-key '), '');
  await poll(() => evaluate(`!!document.querySelector('${row}')`), 'exact spaced comma key');
  await clickText(`${row} button`, '编辑'); await poll(() => evaluate(`document.querySelector('${modal} #root_key')?.value===${JSON.stringify(key)}`), 'immutable actual key');
  assert.equal(await evaluate(`document.querySelector('${modal} #root_key').disabled`), true);
  await fill(`${modal} #root_value`, 'captured value'); await clickReady(`${modal} .ant-modal-close`);
  await poll(() => evaluate(`!!document.querySelector('${discard}')`), 'owned discard confirmation');
  await evaluate(`location.hash='#/profile'`); await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${discard}')`), 'inactive discard hidden');
  await clickMenu('Redis 查询测试'); await poll(() => evaluate(`!!document.querySelector('${discard}')`), 'discard retained');
  await clickText(`${discard} button`, '取消'); await poll(() => evaluate(`!document.querySelector('${discard}')`), 'editing continues');
  fixture.failWrite(); await clickReady(`${modal} .ant-modal-footer .ant-btn-primary`);
  await poll(() => evaluate(`document.querySelector('${modal} .ant-alert-error')?.textContent.includes('Redis 保存失败')`), 'inline edit failure');
  assert.equal(await evaluate(`document.querySelector('${modal} #root_value').value`), 'captured value');
  await clickReady(`${modal} .ant-modal-footer .ant-btn-primary`); await poll(() => evaluate(`!document.querySelector('${modal}')`), 'Redis edit retry');
  assert.equal(fixture.writes.at(-1).method, 'PUT'); assert.equal(fixture.writes.at(-1).data.ttlSeconds, null); assert.equal(fixture.values.get(key), 'captured value');
  await clickText(`${row} button`, '删除'); await poll(() => evaluate(`document.querySelector('${deletion}')?.textContent.includes(${JSON.stringify(key)})`), 'captured exact delete key');
  await evaluate(`location.hash='#/profile'`); await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${deletion}')`), 'delete owner hidden');
  await clickMenu('Redis 查询测试'); await poll(() => evaluate(`!!document.querySelector('${deletion}')`), 'delete target retained');
  fixture.failWrite(); const beforeDelete = fixture.writes.length;
  await evaluate(`(() => {const b=document.querySelector('${deletion} .ant-modal-footer .ant-btn-primary');b.click();b.click();})()`);
  await poll(() => evaluate(`document.querySelector('${deletion} .ant-alert-error')?.textContent.includes('Redis 保存失败')`), 'delete failure keeps confirmation');
  assert.equal(fixture.writes.length, beforeDelete + 1); await clickReady(`${deletion} .ant-modal-footer .ant-btn-primary`);
  await poll(() => evaluate(`!document.querySelector('${deletion}')`), 'explicit delete retry'); assert.deepEqual(fixture.writes.at(-1).keys, [key]);
  assert.equal(fixture.values.has(key), false); assert.equal(fixture.values.has('a'), true); assert.equal(fixture.values.has('b'), true);
  fixture.readonly(true);
  await evaluate(`localStorage.setItem('sp.contextId:__platform__','redis-view-only');window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:'redis-view-only'}}));`);
  await clickMenu('Redis 查询测试'); await poll(() => evaluate(`!!document.querySelector('${root} [data-row-key]')`), 'readonly Redis scope');
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} button')].some(button=>/新增|编辑|删除/.test(button.textContent))`), false);
  console.log('PASS: Redis empty value/required positive TTL, permanent null TTL, owned editor/discard/delete, captured exact whitespace/comma keys, single flight/retry and readonly scope');
}
