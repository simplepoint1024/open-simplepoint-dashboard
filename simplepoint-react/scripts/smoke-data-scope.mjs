import assert from 'node:assert/strict';

export async function smokeDataScope({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  fixture.enable();
  const root = '.nb-tab-panel-active [data-resource="/common/data-scopes"]', page = '.nb-tab-panel-active';
  const form = '.sp-simple-table-drawer.ant-drawer-open', preview = '.data-scope-effective-preview .ant-modal';
  const editor = form + ' .permission-expression-widget';
  const button = async (selector, text) => {
    await poll(() => evaluate(`[...document.querySelectorAll(${JSON.stringify(selector + ' button')})].some(e=>e.textContent.replaceAll(' ','')===${JSON.stringify(text)}&&!e.disabled&&!e.classList.contains('ant-btn-loading'))`), 'enabled DataScope button ' + text);
    await clickText(selector + ' button', text);
  };
  const ready = () => poll(() => evaluate(`!!document.querySelector('${root} [data-row-key="scope-policy"]') && !document.querySelector('${root} .ant-spin-spinning')`), 'genuine DataScope table settles');
  const hide = async () => {await clickMenu('首页'); await poll(() => evaluate(`!document.querySelector('${root}')`), 'home is the actual selected page');};
  const show = async () => {await clickMenu('契约测试 DataScope'); await ready();};
  const edit = async () => {
    await ready(); const checkbox = root + ' [data-row-key="scope-policy"] .ant-checkbox-input';
    if (!await evaluate(`document.querySelector('${checkbox}').checked`)) await clickReady(checkbox);
    await button(root, '编辑'); await poll(() => evaluate(`!!document.querySelector('${form} #root_description')`), 'actual RJSF DataScope form');
  };
  const scope = async mode => {
    fixture.mode(mode);
    await evaluate(`localStorage.setItem('sp.contextId:__platform__',${JSON.stringify('scope-' + mode)});window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:${JSON.stringify('scope-' + mode)}}}));`);
    await poll(() => evaluate(`!document.querySelector('${form}') && !document.querySelector('${preview}')`), 'old owner disposes its portals'); await ready();
  };
  await evaluate(`(()=>{const fetch=window.fetch.bind(window);window.__scopeAborts=[];window.fetch=(input,options)=>{const url=typeof input==='string'?input:input.url,signal=options?.signal??input?.signal;
    if(url?.includes('/data-scopes')&&signal)signal.addEventListener('abort',()=>window.__scopeAborts.push(url),{once:true});return fetch(input,options);};return true;})()`);
  await evaluate(`localStorage.setItem('sp.contextId:__platform__','scope-initial');window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:'scope-initial'}}));`);
  await show(); await button(page, '当前生效权限');
  await poll(() => evaluate(`[...document.querySelectorAll('.ant-modal')].some(e=>e.textContent.includes('当前生效权限'))`), 'effective preview opens');
  await hide();
  assert.equal(await evaluate(`[...document.querySelectorAll('.ant-modal')].some(e=>e.getClientRects().length&&e.textContent.includes('当前生效权限'))`), false, 'effective preview portal must disappear with its retained page');
  await show(); await poll(() => evaluate(`!!document.querySelector('${preview}')`), 'same retained owner resumes its own preview');
  await poll(() => evaluate(`document.querySelector('${preview}')?.textContent.includes('验证用户')`), 'genuine current-caller presentation');
  assert.equal(fixture.reads.filter(read => read.kind === 'effective').length, 1);
  assert.equal(fixture.reads.filter(read => read.kind === 'expression/catalog').length, 1, 'same scoped metadata cache is reused');
  assert.ok(await evaluate(`[...document.querySelectorAll('${root} .sp-table-button-group button')].filter(e=>['新增','编辑'].includes(e.textContent.replaceAll(' ',''))).every(e=>e.disabled)`));
  await button(preview, '关闭');
  await evaluate(`(()=>{const button=[...document.querySelectorAll('${page} button')].find(e=>e.textContent.replaceAll(' ','')==='当前生效权限');button.click();button.click();
    [...document.querySelectorAll('${root} button')].find(e=>e.textContent.replaceAll(' ','')==='新增')?.click();return true;})()`);
  await poll(() => fixture.reads.filter(read => read.kind === 'effective').length === 2, 'same-frame preview is single-flight');
  assert.equal(await evaluate(`!!document.querySelector('${form}')`), false, 'preview synchronously owns the CRUD barrier');
  const releasePreview = fixture.hold('effective'); await button(preview, '刷新');
  await poll(() => fixture.reads.some(read => read.kind === 'effective' && !read.completed), 'owned effective read is pending');
  await hide(); await poll(() => evaluate(`window.__scopeAborts.some(url=>url.includes('/effective'))`), 'hide aborts the real effective request');
  releasePreview(); await show();
  await poll(() => evaluate(`document.querySelector('${preview}')?.textContent.includes('读取已中断')`), 'canceled read has an explicit manual resume');
  const count = fixture.reads.filter(read => read.kind === 'effective').length;
  await button(preview, '刷新'); await poll(() => evaluate(`document.querySelector('${preview}')?.textContent.includes('验证用户')`), 'effective read can retry');
  assert.equal(fixture.reads.filter(read => read.kind === 'effective').length, count + 1);
  fixture.malformed(true); await button(preview, '刷新');
  await poll(() => evaluate(`document.querySelector('${preview}')?.textContent.includes('权限预览加载失败')`), 'truthy-string policy is rejected');
  assert.equal(await evaluate(`document.querySelector('${preview}')?.textContent.includes('验证用户')`), false);
  fixture.malformed(false); await button(preview, '刷新'); await poll(() => evaluate(`document.querySelector('${preview}')?.textContent.includes('验证用户')`), 'malformed response can recover');
  await button(preview, '关闭');
  console.log('PASS: current-caller effective contracts, selected-page portals, synchronous preview/CRUD ownership, cancel/manual retry and strict response flags');

  await edit(); await poll(() => evaluate(`!!document.querySelector('${editor} textarea')`), 'real lazy PermissionExpression widget');
  const initial = await evaluate(`document.querySelector('${editor} textarea').value`);
  await evaluate(`(()=>{const before=document.querySelector('${form} form.rjsf'),preview=[...document.querySelectorAll('${page} button')].find(e=>e.textContent.replaceAll(' ','')==='当前生效权限');preview?.click();return !!before;})()`);
  assert.equal(await evaluate(`!!document.querySelector('${preview}')`), false, 'CRUD editor owns the preview barrier');
  const releaseVisual = fixture.hold('validation'), validationCount = fixture.validations.length;
  await evaluate(`(()=>{const tab=[...document.querySelectorAll('${editor} .ant-tabs-tab')].find(e=>e.textContent==='可视化');tab.click();tab.click();document.querySelector('${form} form.rjsf').requestSubmit();return true;})()`);
  await poll(() => fixture.validations.length === validationCount + 1, 'text-to-visual switch submits exactly one readonly validation');
  assert.equal(fixture.writes.length, 0, 'same-frame submit is blocked by real native widget preparation');
  await poll(() => evaluate(`document.querySelector('${form} button[type="submit"]').disabled`), 'save is visibly disabled while formatting');
  await hide(); await poll(() => evaluate(`window.__scopeAborts.some(url=>url.includes('/expression/validate'))`), 'widget readonly POST cancels on hide');
  releaseVisual(); await show();
  assert.equal(await evaluate(`document.querySelector('${editor} textarea').value`), initial, 'late AST cannot replace the retained text draft');
  await button(editor, '校验并格式化'); await poll(() => fixture.validations.at(-1)?.completed && evaluate(`!document.querySelector('${form} button[type="submit"]').disabled`), 'format retry unlocks native save');
  await clickText(editor + ' .ant-tabs-tab', '可视化'); await poll(() => evaluate(`!!document.querySelector('${editor} fieldset input.ant-input')`), 'real ConditionEditor receives the backend AST');
  await fill(editor + ' fieldset input.ant-input', '临时组织编辑');
  await fill(editor + ' fieldset input.ant-input', '可见组织');
  const unapplied = fixture.validations.length; await clickText(editor + ' .ant-tabs-tab', '表达式');
  await poll(() => evaluate(`document.querySelector('${editor}')?.textContent.includes('请先应用可视化配置')`), 'unapplied visual draft cannot be discarded by switching');
  assert.equal(fixture.validations.length, unapplied);
  await button(editor, '应用可视化配置'); await poll(() => fixture.validations.at(-1)?.completed, 'real visual AST applies');
  await clickText(editor + ' .ant-tabs-tab', '表达式');
  await poll(() => evaluate(`document.querySelector('${editor} textarea')?.value===${JSON.stringify(initial)}`), 'text-visual-text roundtrip is lossless');
  await fill(editor + ' textarea', initial.slice(0, -1)); await clickText(editor + ' .ant-tabs-tab', '可视化');
  await poll(() => evaluate(`document.querySelector('${editor}')?.textContent.includes('请使用已翻译的字段')`), 'incomplete translated source stays in actual engine');
  assert.equal(await evaluate(`document.querySelector('${editor} textarea').value`), initial.slice(0, -1));
  await fill(editor + ' textarea', initial);
  await fill(form + ' #root_description', 'Retained genuine expression draft');
  const releasePrepare = fixture.hold('validation'), beforePrepare = fixture.validations.length, beforeWrite = fixture.writes.length;
  await evaluate(`(()=>{const form=document.querySelector('${form} form.rjsf');form.requestSubmit();form.requestSubmit();return true;})()`);
  await poll(() => fixture.validations.length === beforePrepare + 1, 'beforeSubmit readonly preparation is single-flight');
  await hide(); releasePrepare(); await show();
  assert.equal(fixture.writes.length, beforeWrite, 'leaving beforeSubmit never dispatches a later write');
  assert.equal(await evaluate(`document.querySelector('${form} #root_description').value`), 'Retained genuine expression draft');
  fixture.fail('write'); const releaseWrite = fixture.hold('write'); await clickReady(form + ' button[type="submit"]');
  await poll(() => fixture.writes.length === beforeWrite + 1, 'a retry reaches the actual default write');
  const abortedBefore = await evaluate(`window.__scopeAborts.length`); await hide();
  assert.equal(await evaluate(`window.__scopeAborts.length`), abortedBefore, 'already dispatched write is not canceled merely by hiding');
  releaseWrite(); await show();
  await poll(() => evaluate(`document.querySelector('${form}')?.textContent.includes('策略保存失败')`), 'started write failure retains the same draft');
  await clickReady(form + ' button[type="submit"]'); await poll(() => evaluate(`!document.querySelector('${form}')`), 'native retry saves once');
  assert.equal(fixture.writes.at(-1).data.expression, fixture.row.expression);
  assert.equal(fixture.writes.at(-1).data.description, 'Retained genuine expression draft');
  console.log('PASS: actual SForm widget preparation lock, readonly POST cancellation, unapplied draft barrier, lossless native editor roundtrip, cancelable beforeSubmit and retained started-write failures');

  await scope('protected'); await edit();
  assert.equal(await evaluate(`!!document.querySelector('${editor}')`), false, 'readonly expression uses native readonly text, not an active validating editor');
  const protectedCount = fixture.validations.length; await fill(form + ' #root_description', 'Protected type description only');
  await clickReady(form + ' button[type="submit"]'); await poll(() => evaluate(`!document.querySelector('${form}')`), 'masked required discriminator does not block a lawful description edit');
  assert.equal(fixture.validations.length, protectedCount);
  assert.equal(fixture.writes.at(-1).data.description, 'Protected type description only');
  for (const key of ['type', 'name', 'expression', 'resourceCode']) assert.ok(!(key in fixture.writes.at(-1).data));
  console.log('PASS: real masked-discriminator/readonly schema, lawful description-only submission and omission of all policy-protected rule fields');

  const releaseNames = fixture.hold('presentation'); await scope('shared-metadata');
  await poll(() => fixture.reads.some(read => read.kind === 'presentation' && !read.completed), 'shared authorized presentation is pending');
  const namesBefore = await evaluate(`window.__scopeAborts.filter(url=>url.includes('/presentation')).length`);
  await button(page, '当前生效权限'); await button(preview, '关闭');
  assert.equal(await evaluate(`window.__scopeAborts.filter(url=>url.includes('/presentation')).length`), namesBefore,
    'closing preview must not cancel the root table observer sharing the same names');
  await edit(); releaseNames();
  await poll(() => evaluate(`document.querySelector('${editor} textarea')?.value.includes('组织名称')`), 'same genuine catalog/presentation is shared with the lazy native widget');
  assert.equal(fixture.reads.filter(read => read.kind === 'presentation' && read.mode === 'shared-metadata').length, 1);
  assert.equal(fixture.reads.filter(read => read.kind === 'expression/catalog' && read.mode === 'shared-metadata').length, 1);
  await clickReady(form + ' .ant-drawer-close'); await poll(() => evaluate(`!document.querySelector('${form}')`), 'unchanged engine editor closes normally');
  const releaseOwner = fixture.hold('effective'); await button(page, '当前生效权限');
  await poll(() => fixture.reads.some(read => read.kind === 'effective' && !read.completed), 'old scope owns a pending preview');
  await scope('replacement'); releaseOwner();
  assert.equal(await evaluate(`!!document.querySelector('${preview}')`), false, 'late old-scope response cannot reopen a new owner');
  fixture.fail('expression/catalog'); await scope('catalog-error');
  await poll(() => evaluate(`document.querySelector('${page} .ant-alert-error')?.textContent.includes('字段元数据加载失败')`), 'catalog failure stays inline without auto retries');
  assert.equal(fixture.reads.filter(read => read.kind === 'expression/catalog' && read.mode === 'catalog-error').length, 1);
  await button(page + ' .ant-alert-error', '重试'); await poll(() => evaluate(`!document.querySelector('${page} .ant-alert-error')`), 'explicit same-catalog retry recovers');
  assert.equal(fixture.reads.filter(read => read.kind === 'expression/catalog' && read.mode === 'catalog-error').length, 2);
  console.log('PASS: shared metadata single-flight/cache, last-observer cancellation rules, fresh-scope portal disposal and inline explicit catalog retry');

  // No test-only component/provider: create the cold target case in the genuine DataScope form.
  fixture.fail('expression/catalog:all'); await scope('cold-target');
  await poll(() => evaluate(`document.querySelector('${page} .ant-alert-error')?.textContent.includes('字段元数据加载失败')`), 'whole directory read genuinely failed in the new authorization scope');
  await edit(); await poll(() => evaluate(`!!document.querySelector('${editor} textarea') && [...document.querySelectorAll('${editor} button')].some(e=>e.textContent==='校验并格式化'&&!e.disabled)`), 'native field loads only its current target');
  const cold = fixture.reads.filter(read => read.kind === 'expression/catalog' && read.mode === 'cold-target');
  assert.equal(cold.length, 2); assert.equal(cold[0].params.has('resourceCode'), false); assert.equal(cold[1].params.get('resourceCode'), 'organizations.view');
  assert.equal(fixture.reads.filter(read => read.kind === 'presentation' && read.mode === 'cold-target').length, 1,
    'opening an expression widget must not add a whole caller presentation-directory observer/read');
  const source = await evaluate(`document.querySelector('${editor} textarea').value`);
  assert.ok(source.includes('组织名称')); await button(editor, '校验并格式化');
  await poll(() => fixture.validations.at(-1)?.completed && evaluate(`!document.querySelector('${form} button[type="submit"]').disabled`), 'target metadata drives the actual server AST validator');
  assert.equal(await evaluate(`document.querySelector('${editor} textarea').value`), source);
  await clickReady(form + ' .ant-drawer-close');
  if (await evaluate(`!!document.querySelector('.sp-simple-table-discard .ant-modal')`)) await button('.sp-simple-table-discard .ant-modal', '放弃修改');
  await poll(() => evaluate(`!document.querySelector('${form}')`), 'genuine cold-target editor closes');

  fixture.fail('expression/catalog:all'); fixture.fail('expression/catalog:organizations.view'); await scope('target-error'); await edit();
  await poll(() => evaluate(`document.querySelector('${editor} .ant-alert-error')?.textContent.includes('字段元数据加载失败')`), 'target failure remains owned and retryable');
  const releaseTarget = fixture.hold('expression/catalog:organizations.view');
  await poll(() => evaluate(`[...document.querySelectorAll('${editor} button')].some(e=>e.textContent.replaceAll(' ','')==='重试'&&!e.disabled)`), 'real target retry is visible and enabled');
  await evaluate(`(()=>{const button=[...document.querySelectorAll('${editor} button')].find(e=>e.textContent.replaceAll(' ','')==='重试');button.click();button.click();return true;})()`);
  await poll(() => fixture.reads.filter(read => read.mode === 'target-error' && read.params.get('resourceCode') === 'organizations.view').length === 2, 'same-frame target retries share one real query');
  const aborts = await evaluate(`window.__scopeAborts.filter(url=>url.includes('/expression/catalog')&&url.includes('resourceCode=')).length`);
  await hide(); await poll(() => evaluate(`window.__scopeAborts.filter(url=>url.includes('/expression/catalog')&&url.includes('resourceCode=')).length>${aborts}`), 'last target observer hiding aborts its actual HTTP read');
  releaseTarget(); await show();
  await poll(() => evaluate(`!!document.querySelector('${editor} textarea') && [...document.querySelectorAll('${editor} button')].some(e=>e.textContent==='校验并格式化'&&!e.disabled)`), 'target transport can resume only for the same selected owner');
  assert.ok(fixture.reads.filter(read => read.mode === 'target-error' && read.params.get('resourceCode') === 'organizations.view').length <= 3);
  console.log('PASS: genuine cold-target metadata query, no full directory/presentation download by the widget, original text/AST contract and same-query retry/cancellation');

  await clickReady(form + ' .ant-drawer-close');
  if (await evaluate(`!!document.querySelector('.sp-simple-table-discard .ant-modal')`)) await button('.sp-simple-table-discard .ant-modal', '放弃修改');
  await poll(() => evaluate(`!document.querySelector('${form}')`), 'target editor closes before the protected preview audit');
  const writesBeforeNames = fixture.writes.length;
  for (const mode of ['masked', 'hidden']) {
    await scope(mode + '-presentation'); await button(page, '当前生效权限');
    await poll(() => fixture.reads.some(read => read.mode === mode + '-presentation' && read.kind === 'effective' && read.completed)
      && fixture.reads.some(read => read.mode === mode + '-presentation' && read.kind === 'presentation' && read.completed)
      && evaluate(`document.querySelectorAll('${preview} .ant-descriptions-item-content').length>=6`), 'native protected caller presentation settles');
    const descriptions = await evaluate(`[...document.querySelectorAll('${preview} .ant-descriptions-item-content')].map(element=>element.textContent.trim())`);
    assert.deepEqual(descriptions.slice(0, 3), mode === 'masked' ? ['***', '***', '***'] : ['当前用户', '当前工作空间', '当前角色']);
    assert.equal(descriptions[4], mode === 'masked' ? '***' : '名称暂不可用');
    const content = await evaluate(`document.querySelector('${preview}').textContent`);
    for (const secret of ['验证用户', '验证工作空间', 'Private role', 'Private field rule', '已授权组织', '真实表达式策略', 'private@example.invalid']) assert.ok(!content.includes(secret), secret);
    await button(preview, '关闭');
  }
  assert.equal(fixture.writes.length, writesBeforeNames, 'protected native summary reads cannot write policies');
  console.log('PASS: actual masked/hidden HTTP schemas, root pages and effective presentation preserve readable metadata without revealing protected source names');
}
