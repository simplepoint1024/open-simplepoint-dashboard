import assert from 'node:assert/strict';

export async function smokeStorageCrud({clickMenu, clickReady, clickText, fill, poll, evaluate, reload, fixture}) {
  fixture.enable();
  const page = '.nb-tab-panel-active', drawer = '.sp-simple-table-drawer.ant-drawer-open';
  await clickMenu('OSS CRUD 测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="storage-provider-old"]')`), 'storage SimpleTable controller');
  await clickReady(`${page} tr[data-row-key="storage-provider-old"] input[type="checkbox"]`);
  await clickText(`${page} button`, '编辑');
  await poll(() => evaluate(`!!document.querySelector('${drawer} #root_secretKey')`), 'provider uses shared schema form');
  assert.equal(await evaluate(`document.querySelector('${drawer} #root_secretKey').type`), 'password');
  assert.equal(await evaluate(`!!document.querySelector('${drawer} #root_secretKeyCiphertext')`), false);
  await fill(`${drawer} #root_description`, '只改说明');
  fixture.failWrite();
  await clickText(`${drawer} button`, '提交');
  await poll(() => evaluate(`document.querySelector('${drawer}')?.textContent.includes('OSS 保存失败（测试）')`), 'provider failure preserves schema editor');
  assert.equal(await evaluate(`document.querySelector('${drawer} #root_description').value`), '只改说明');
  assert.equal(fixture.writes[0].data.secretKey, null, 'blank secret preserves stored credentials');
  assert.equal('secretKeyCiphertext' in fixture.writes[0].data, false);
  await clickText(`${drawer} button`, '提交');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'provider retry closes after successful mutation');
  assert.equal(fixture.rows['provider-configs'][0].description, '只改说明');
  await clickMenu('配额 CRUD 测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="storage-quota-old"]')`), 'quota SQL projection');
  await clickReady(`${page} tr[data-row-key="storage-quota-old"] input[type="checkbox"]`);
  await clickText(`${page} button`, '编辑');
  await poll(() => evaluate(`!!document.querySelector('${drawer} #root_quotaMb')`), 'MB is a platform form presentation field');
  await fill(`${drawer} #root_description`, '只改配额说明');
  await clickText(`${drawer} button`, '提交');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'quota update completed');
  assert.equal(fixture.writes[2].data.quotaBytes, 12345, 'editing another field never rounds the quota');
  assert.equal('quotaMb' in fixture.writes[2].data, false, 'presentation field does not leak into backend DTO');
  await clickMenu('对象上传测试');
  await clickText(`${page} button`, '上传对象');
  const modal = '.object-storage-upload:not([style*="display: none"]) .ant-modal';
  await poll(() => evaluate(`!!document.querySelector('${modal} #root_directory')`), 'upload metadata uses SForm');
  await fill(`${modal} #root_directory`, 'folder');
  await evaluate(`(() => {const transfer=new DataTransfer();transfer.items.add(new File(['abc'],'fixture.txt',{type:'text/plain'}));
    const input=document.querySelector('${modal} input[type="file"]');input.files=transfer.files;input.dispatchEvent(new Event('change',{bubbles:true}));})()`);
  try {await poll(() => evaluate(`document.querySelector('${modal}')?.textContent.includes('fixture.txt')`), 'binary file selected');}
  catch (error) {console.log('Storage upload modal diagnostics', await evaluate(`({modals:[...document.querySelectorAll('.ant-modal')].map(m=>({visible:!!m.getClientRects().length,text:m.textContent.slice(0,600),inputs:[...m.querySelectorAll('input[type="file"]')].map(i=>({disabled:i.disabled,files:[...i.files].map(f=>f.name)}))})),scope:localStorage.getItem('sp.contextId:__platform__')})`)); throw error;}
  await evaluate(`location.hash='#/profile'`);
  await poll(() => evaluate(`document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${modal}')`), 'inactive upload is hidden');
  assert.equal(fixture.writes.length, 3, 'switching pages does not upload or abandon the captured binary');
  await clickMenu('对象上传测试');
  await poll(() => evaluate(`document.querySelector('${modal} #root_directory')?.value==='folder' && document.querySelector('${modal}')?.textContent.includes('fixture.txt')`), 'upload metadata and actual binary draft retained');
  await evaluate(`(() => {const button=document.querySelector('${modal} .ant-modal-footer button.ant-btn-primary');button.click();button.click();})()`);
  await poll(() => fixture.writes.length === 4, 'single multipart upload');
  assert.match(fixture.writes[3].contentType, /^multipart\/form-data; boundary=/);
  assert.match(fixture.writes[3].body, /name="directory"\r\n\r\nfolder/);
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="uploaded-1"]')`), 'upload refreshes the existing SimpleTable controller');
  assert.equal(fixture.writes.length, 4, 'same-frame submit does not duplicate upload');
  await clickMenu('OSS CRUD 测试');
  await poll(() => evaluate(`location.hash.includes('/smoke/system/object-storage/crud')`), 'provider route selected before permission reload');
  fixture.setReadonly(true);
  await reload();
  await clickMenu('OSS CRUD 测试');
  try {
    await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="storage-provider-old"]')`), 'readonly provider schema');
  } catch (error) {
    console.error('Storage restore diagnostics', await evaluate(`({hash:location.hash,
      text:document.querySelector('${page}')?.textContent.slice(0,1800),
      rows:[...document.querySelectorAll('${page} tr[data-row-key]')].map(row=>row.dataset.rowKey)})`),
      fixture.requests.slice(-12).map(request => ({path: request.path, method: request.method, params: String(request.params)})));
    throw error;
  }
  assert.equal(await evaluate(`[...document.querySelectorAll('${page} .sp-table-toolbar-main button')].some(button=>/新增|编辑|删除|测试连接/.test(button.textContent))`), false,
    'view-only authority never becomes a mutation capability');
  await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  fixture.setReadonly(false);
  for (const label of ['配额 CRUD 测试', '对象上传测试']) {
    await clickMenu(label); await clickReady('.nb-tabs-bar .ant-tabs-tab-active .ant-tabs-tab-remove');
  }
  fixture.disable();
  console.log('PASS: storage SimpleTable CRUD, password/blank-secret semantics, failed save/retry, byte-exact quota, multipart lock and readonly actions');
}
