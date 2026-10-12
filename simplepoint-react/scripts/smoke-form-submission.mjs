import assert from 'node:assert/strict';

/** Real resource pages and RJSF instance: no test form, substitute controller or query provider. */
export async function smokeFormSubmission({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture, dictionaryFixture}) {
  fixture.enable();
  const base = '/common/platform/dictionaries', root = '.nb-tab-panel-active [data-resource="' + base + '"]';
  const form = '.sp-simple-table-drawer.ant-drawer-open', discard = '.sp-simple-table-discard:not([style*="display: none"]) .ant-modal';
  const openEdit = async () => {
    const checkbox = root + ' [data-row-key="dict-scope-0"] .ant-checkbox-input';
    await poll(() => evaluate(`!!document.querySelector('${checkbox}') && !document.querySelector('${root} .ant-spin-spinning')`), 'actual dictionary row');
    if (!await evaluate(`document.querySelector('${checkbox}').checked`)) await clickReady(checkbox);
    await clickText(root + ' button', '编辑');
    await poll(() => evaluate(`!!document.querySelector('${form} #root_description')`), 'real platform schema editor');
  };
  const submitAndClose = async (engine = false) => {
    await poll(() => evaluate(`(()=>{const button=document.querySelector('${form} button[type="submit"]');return !!button&&!button.disabled&&!button.classList.contains('ant-btn-loading');})()`), 'real submit button is ready');
    await evaluate(`(()=>{const element=document.querySelector('${form} form.rjsf'),close=document.querySelector('${form} .ant-drawer-close');
      ${engine ? `let fiber=element[Object.keys(element).find(key=>key.startsWith('__reactFiber$'))],instance;while(fiber){if(fiber.stateNode?.formElement?.current===element&&typeof fiber.stateNode.submit==='function'){instance=fiber.stateNode;break;}fiber=fiber.return;}if(!instance)throw Error('Actual RJSF submit() instance unavailable');instance.submit();instance.submit();`
        : 'element.requestSubmit();element.requestSubmit();'}
      close?.click();return true;})()`);
  };
  await clickMenu('契约测试 Dictionary'); await openEdit();
  await evaluate(`(()=>{window.__submissionTable=document.querySelector('${root}');return true;})()`);
  await fill(form + ' #root_description', 'Atomic real form intent'); fixture.failWrite('PUT', base);
  const before = fixture.writes.length; await submitAndClose();
  await poll(() => fixture.writes.length === before + 1 && fixture.writes.at(-1).completed, 'one default write completes');
  await poll(() => evaluate(`document.querySelector('${form} .ant-alert-error')?.textContent.includes('默认 CRUD 写入失败')`), 'same-frame close retains the real failed draft');
  assert.equal(await evaluate(`!!document.querySelector('${discard}')`), false, 'pending validation/write must not open a competing discard confirmation');
  assert.equal(await evaluate(`document.querySelector('${form} #root_description').value`), 'Atomic real form intent');
  assert.equal(fixture.writes.length, before + 1);
  fixture.failWrite('PUT', base); const imperative = fixture.writes.length; await submitAndClose(true);
  await poll(() => fixture.writes.length === imperative + 1 && fixture.writes.at(-1).completed, 'real imperative RJSF submission does not lock itself on its probe event');
  assert.equal(await evaluate(`!!document.querySelector('${discard}')`), false);
  await fill(form + ' #root_description', 'Atomic real retry'); await clickReady(form + ' button[type="submit"]');
  await poll(() => evaluate(`!document.querySelector('${form}')`), 'accepted retry closes itself after success');
  assert.equal(fixture.writes.at(-1).data.id, 'dict-scope-0');
  assert.equal(fixture.writes.at(-1).data.description, 'Atomic real retry');
  assert.equal(await evaluate(`window.__submissionTable===document.querySelector('${root}')`), true, 'successful save keeps the actual table controller mounted');
  console.log('PASS: native and actual imperative form submissions lock before validation, block same-frame close/discard and duplicate requests, retain errors/drafts and unlock on retry');

  await openEdit(); await fill(form + ' #root_description', 'x'.repeat(513));
  const invalid = fixture.writes.length; await clickReady(form + ' button[type="submit"]');
  await poll(() => evaluate(`!!document.querySelector('${form} .error-detail, ${form} .ant-form-item-has-error, ${form} .text-danger')`), 'real schema validation stays in the engine');
  assert.equal(fixture.writes.length, invalid, 'invalid validation never reaches the default writer');
  await poll(() => evaluate(`document.querySelector('${form} form.rjsf')?.textContent.includes('最多填写 512 个字符')`), 'edit validation uses Chinese platform translations');
  await evaluate(`window.spI18n.setLocale('en-US')`);
  await poll(() => evaluate(`document.querySelector('${form} form.rjsf')?.textContent.includes('Use at most 512 characters') && !document.querySelector('${form} form.rjsf')?.textContent.includes('最多填写') && !document.querySelector('${form} form.rjsf')?.textContent.includes('must NOT')`), 'existing validation errors translate without remounting');
  assert.equal(await evaluate(`document.querySelector('${form} #root_description').value.length`), 513, 'language switch retains the invalid draft');
  await evaluate(`window.spI18n.setLocale('zh-CN')`);
  await poll(() => evaluate(`document.querySelector('${form} form.rjsf')?.textContent.includes('最多填写 512 个字符')`), 'Chinese validation restored');
  await fill(form + ' #root_description', 'Valid after a validation error');
  await clickReady(form + ' .ant-drawer-close'); await poll(() => evaluate(`!!document.querySelector('${discard}')`), 'ordinary dirty close retains Common discard semantics');
  // The underlying form is genuinely present. A submission while discard owns the editor must be refused.
  await evaluate(`(()=>{document.querySelector('${form} form.rjsf').requestSubmit();return true;})()`);
  assert.equal(fixture.writes.length, invalid);
  assert.equal(await evaluate(`!!document.querySelector('${discard}')`), true);
  await clickText(discard + ' button', '继续编辑');
  await clickReady(form + ' button[type="submit"]'); await poll(() => evaluate(`!document.querySelector('${form}')`), 'validation failure releases the preflight lock for a later valid submit');
  assert.equal(fixture.writes.length, invalid + 1);
  assert.equal(fixture.writes.at(-1).data.description, 'Valid after a validation error');
  console.log('PASS: actual RJSF invalid-schema release, original validation UI, discard ownership and normal continued editing');

  await clickText(root + ' button', '新增'); await poll(() => evaluate(`!!document.querySelector('${form} #root_name')`), 'default add form');
  const missing = fixture.writes.length;
  await clickReady(form + ' button[type="submit"]');
  await poll(() => evaluate(`document.querySelector('${form} form.rjsf')?.textContent.includes('请填写此项')`), 'absent create fields enforce annotation-required rules');
  assert.equal(fixture.writes.length, missing);
  await fill(form + ' #root_name', '   '); await fill(form + ' #root_code', 'whitespace-check');
  await clickReady(form + ' button[type="submit"]');
  await poll(() => evaluate(`document.querySelector('${form} form.rjsf')?.textContent.includes('请填写此项')`), 'NotBlank blocks whitespace in actual add form');
  assert.equal(fixture.writes.length, missing);
  await fill(form + ' #root_name', 'Accepted default create'); await fill(form + ' #root_description', 'x'.repeat(513));
  await clickReady(form + ' button[type="submit"]');
  await poll(() => evaluate(`document.querySelector('${form} form.rjsf')?.textContent.includes('最多填写 512 个字符')`), 'add and edit share the same length rules and messages');
  assert.equal(fixture.writes.length, missing);
  await fill(form + ' #root_description', 'Valid new draft');
  await fill(form + ' #root_name', 'Accepted default create'); await fill(form + ' #root_code', 'accepted-default-create');
  fixture.failWrite('POST', base); const create = fixture.writes.length; await submitAndClose(true);
  await poll(() => evaluate(`document.querySelector('${form} .ant-alert-error')?.textContent.includes('默认 CRUD 写入失败')`), 'default addition keeps its original draft');
  assert.equal(fixture.writes.length, create + 1); assert.equal(fixture.writes.at(-1).method, 'POST');
  assert.equal(await evaluate(`!!document.querySelector('${discard}')`), false);
  assert.equal(await evaluate(`document.querySelector('${form} #root_name').value`), 'Accepted default create');
  await clickReady(form + ' button[type="submit"]'); await poll(() => evaluate(`!document.querySelector('${form}')`), 'default add retry releases the accepted intent');
  assert.equal(fixture.writes.length, create + 2); assert.equal(fixture.writes.at(-1).data.code, 'accepted-default-create');

  await openEdit(); const originalName = await evaluate(`document.querySelector('${form} #root_name').value`);
  const rejected = fixture.writes.length;
  await fill(form + ' #root_name', '   '); await clickReady(form + ' button[type="submit"]');
  await poll(() => evaluate(`document.querySelector('${form} form.rjsf')?.textContent.includes('请填写此项')`), 'NotBlank also blocks whitespace in actual edit form');
  assert.equal(fixture.writes.length, rejected);
  await fill(form + ' #root_name', originalName); await fill(form + ' #root_description', 'Server validation keeps this draft');
  fixture.rejectValidationOnce(); await clickReady(form + ' button[type="submit"]');
  await poll(() => evaluate(`document.querySelector('${form} .ant-alert-error')?.textContent.includes('格式不符合要求') && document.querySelector('${form} form.rjsf')?.textContent.includes('格式不符合要求')`), 'server validation maps to both localized summary and actual field');
  assert.equal(fixture.writes.length, rejected + 1);
  assert.equal(await evaluate(`document.querySelector('${form} #root_description').value`), 'Server validation keeps this draft');
  await fill(form + ' #root_code', 'server-valid-code'); await clickReady(form + ' button[type="submit"]');
  await poll(() => evaluate(`!document.querySelector('${form}')`), 'correcting a field clears server errors and permits retry');
  assert.equal(fixture.writes.length, rejected + 2);
  console.log('PASS: annotation-generated add/edit required, NotBlank and length parity, live bilingual errors, safe server field errors, preserved drafts and retry');

  await openEdit(); await fill(form + ' #root_description', 'Old validation scope must not write');
  const oldScope = fixture.writes.length; fixture.dictionaryMode('view');
  await evaluate(`(()=>{document.querySelector('${form} form.rjsf').requestSubmit();localStorage.setItem('sp.contextId:__platform__','preflight-view');window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:'preflight-view'}}));return true;})()`);
  await poll(() => evaluate(`!document.querySelector('${form}') && !!document.querySelector('${root} [data-row-key="dict-scope-0"]')
    && ![...document.querySelectorAll('${root} .sp-table-button-group button')].some(button=>button.textContent.includes('编辑'))`), 'scope changes before the queued validation callback');
  assert.equal(fixture.writes.length, oldScope, 'an accepted validation intent is not authority to write in a new scope');
  fixture.dictionaryMode('all');
  await evaluate(`localStorage.setItem('sp.contextId:__platform__','preflight-restored');window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:'preflight-restored'}}));`);
  await openEdit(); await fill(form + ' #root_description', 'New scope has its own submission');
  await clickReady(form + ' button[type="submit"]'); await poll(() => evaluate(`!document.querySelector('${form}')`), 'old validation cleanup cannot block the new scope');
  assert.equal(fixture.writes.length, oldScope + 1);
  assert.equal(fixture.writes.at(-1).data.description, 'New scope has its own submission');
  console.log('PASS: default add/edit distinction, same-frame prevalidation scope invalidation and fresh-scope lock recovery');

  await clickMenu('API Key 生命周期测试');
  const keys = '.nb-tab-panel-active .ai-api-keys-page', secret = '.ai-api-key-secret .ant-modal';
  await poll(() => evaluate(`!!document.querySelector('${keys} [data-row-key="api-key-0"]')`), 'real custom-submit resource page');
  await clickText(keys + ' button', '新增'); await poll(() => evaluate(`!!document.querySelector('${form} #root_name')`), 'custom writer actual SForm');
  await fill(form + ' #root_name', 'Accepted custom submit'); fixture.failWrite('POST', '/ai/workbench/api-keys');
  const custom = fixture.writes.length; await submitAndClose(true);
  await poll(() => fixture.writes.length === custom + 1 && fixture.writes.at(-1).completed
    && evaluate(`!!document.querySelector('${form} .ant-alert-error')`), 'custom writer failure is retained');
  assert.equal(await evaluate(`!!document.querySelector('${discard}') || !!document.querySelector('${secret}')`), false);
  await clickReady(form + ' button[type="submit"]');
  await poll(() => evaluate(`!document.querySelector('${form}') && !!document.querySelector('${secret} .ant-typography-copy')`), 'original afterSubmit disclosure runs once only after successful custom submission');
  assert.equal(fixture.writes.length, custom + 2);
  assert.equal(fixture.writes.at(-1).data.name, 'Accepted custom submit');
  await clickText(secret + ' button', '关闭');
  console.log('PASS: actual custom onSubmit/afterSubmit hooks, schema validation, one request, failure preservation and successful response-only disclosure');

  await clickMenu('契约测试 Dictionary');

  // A real nested editor retains the existing captured-parent beforeSubmit transform and parent close barrier.
  dictionaryFixture.enable();
  await evaluate(`localStorage.setItem('sp.contextId:__platform__','form-nested');window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:'form-nested'}}));`);
  const parent = root + ' [data-row-key="dictionary-alpha"] .ant-checkbox-input';
  await poll(() => evaluate(`!!document.querySelector('${parent}')`), 'fresh real dictionary owner'); await clickReady(parent);
  await clickText(root + ' button', '配置字典项');
  const panel = '.dictionary-item-config.ant-drawer-open', child = panel + ' [data-resource="/common/platform/dictionary-items"]';
  await poll(() => evaluate(`!!document.querySelector('${child} [data-row-key="alpha-item-0"] .ant-checkbox-input')`), 'actual nested dictionary item');
  await clickReady(child + ' [data-row-key="alpha-item-0"] .ant-checkbox-input'); await clickText(child + ' button', '编辑');
  await poll(() => evaluate(`!!document.querySelector('${form} #root_description')`), 'nested real SForm');
  await fill(form + ' #root_description', 'Captured parent with atomic submission'); dictionaryFixture.failWrite('PUT');
  const nested = dictionaryFixture.writes.length; await submitAndClose(true);
  await evaluate(`(()=>{document.querySelector('${panel} .ant-drawer-close')?.click();return true;})()`);
  await poll(() => evaluate(`document.querySelector('${form} .ant-alert-error')?.textContent.includes('字典项保存失败')`), 'nested failure and parent remain visible');
  assert.equal(dictionaryFixture.writes.length, nested + 1);
  assert.equal(dictionaryFixture.writes.at(-1).data.id, 'alpha-item-0');
  assert.equal(dictionaryFixture.writes.at(-1).data.dictionaryCode, 'alpha');
  assert.equal(await evaluate(`!!document.querySelector('${discard}')`), false);
  await clickReady(form + ' button[type="submit"]'); await poll(() => evaluate(`!document.querySelector('${form}')`), 'nested retry succeeds');
  await clickReady(panel + ' .ant-drawer-close'); await poll(() => evaluate(`!document.querySelector('${panel}')`), 'nested parent releases its editor normally');
  console.log('PASS: real nested SimpleTable beforeSubmit parent transform, imperative prevalidation ownership, failure/retry and ResourceDrawer close barrier');
}
