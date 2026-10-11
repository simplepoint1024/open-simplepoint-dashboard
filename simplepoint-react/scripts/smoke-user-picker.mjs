import assert from 'node:assert/strict';

export async function smokeUserPicker({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture}) {
  fixture.enable();
  const root = '.nb-tab-panel-active [data-resource="/common/platform/tenants"]';
  const form = '.sp-simple-table-drawer.ant-drawer-open';
  const popup = '.ant-select-dropdown:not(.ant-select-dropdown-hidden)';
  const options = popup + ' .ant-select-item-option';
  const owner = form + ' #root_ownerId', members = form + ' #root_userIds';
  const readCount = kind => fixture.reads.filter(read=>read.kind === kind).length;
  const open = async () => {
    await poll(()=>evaluate(`!!document.querySelector('${root} [data-row-key="picker-tenant"] .ant-checkbox-input')`), 'actual tenant picker row');
    if (!await evaluate(`document.querySelector('${root} [data-row-key="picker-tenant"] .ant-checkbox-input').checked`)) {
      await clickReady(root + ' [data-row-key="picker-tenant"] .ant-checkbox-input');
    }
    await clickText(root + ' button', '编辑');
    await poll(()=>evaluate(`!!document.querySelector('${owner}')`), 'real platform schema form');
  };
  const close = async (dirty = false) => {
    await clickReady(form + ' .ant-drawer-close');
    if (dirty) {await poll(()=>evaluate(`!!document.querySelector('.sp-simple-table-discard .ant-modal')`), 'actual optional picker draft discard'); await clickText('.sp-simple-table-discard button', '放弃修改');}
    await poll(()=>evaluate(`!document.querySelector('${form}')`), 'picker form closed');
  };
  const scope = async mode => {
    fixture.mode(mode);
    await evaluate(`localStorage.setItem('sp.contextId:__platform__',${JSON.stringify('picker-' + mode)});window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:${JSON.stringify('picker-' + mode)}}}));`);
    await poll(()=>evaluate(`!document.querySelector('${form}') && !!document.querySelector('${root} [data-row-key="picker-tenant"]')`), 'new authorization owns a fresh picker form');
  };
  const search = async (selector, keyword) => {
    await evaluate(`(()=>{document.querySelector('${selector}').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));return true;})()`);
    await poll(()=>evaluate(`!!document.querySelector('${popup}')`), 'actual platform selector popup');
    await fill(selector, keyword);
  };
  const option = label => poll(()=>evaluate(`[...document.querySelectorAll('${options}')].some(element=>element.textContent.includes(${JSON.stringify(label)}))`), 'real directory option ' + label);
  const shutPopup = async selector => {
    await evaluate(`document.querySelector('${selector}').dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true}))`);
    await poll(()=>evaluate(`!document.querySelector('${popup}')`), 'picker popup closes');
  };
  // Observe actual transport cancellation, not a test-created selector/query client.
  await evaluate(`(()=>{const original=window.fetch.bind(window);window.__pickerAborts=[];window.fetch=(input,options)=>{const url=typeof input==='string'?input:input.url;const signal=options?.signal??input?.signal;if(url?.includes('/users/picker/')&&signal)signal.addEventListener('abort',()=>window.__pickerAborts.push(url),{once:true});return original(input,options);};return true;})()`);
  await clickMenu('租户成员分配测试'); await open();
  await poll(()=>evaluate(`document.querySelector('${form}').textContent.includes('Candidate520')`), 'off-page persisted user resolves through real selected endpoint');
  assert.equal(readCount('search'), 0, 'opening a metadata form never downloads a directory');
  await search(owner, 'pi');
  await poll(()=>evaluate(`document.querySelector('${popup}').textContent.includes('至少 3')`), 'minimum search hint');
  assert.equal(readCount('search'), 0, 'two-character prefixes do not query accounts');
  await fill(owner, 'picker'); await option('Candidate000');
  for (let page = 1; page <= 26; page++) {
    await poll(()=>evaluate(`(()=>{const button=[...document.querySelectorAll('${popup} button')].find(element=>element.textContent.replaceAll(' ','')==='加载更多');return !!button && !button.disabled && !button.classList.contains('ant-btn-loading');})()`), 'actual next-page action settled');
    await clickText(popup + ' button', '加载更多');
    await poll(()=>fixture.reads.some(read=>read.kind === 'search' && read.params.get('keyword') === 'picker'
      && Number(read.params.get('page')) === page && read.completed), 'bounded directory page ' + page);
  }
  assert.ok(fixture.reads.filter(read=>read.kind === 'search').every(read=>read.params.get('size') === '20'));
  // Inspect the real host QueryClient's cache without adding a provider, global cache or test-only app component.
  const cached = await evaluate(`(()=>{const roots=[...document.querySelectorAll('*')].flatMap(element=>Object.keys(element).filter(key=>key.startsWith('__reactContainer$')).map(key=>element[key]));const seen=new Set(),stack=[...roots];while(stack.length){const fiber=stack.pop();if(!fiber||seen.has(fiber))continue;seen.add(fiber);const client=fiber.memoizedProps?.client;if(client&&typeof client.getQueryCache==='function')return client.getQueryCache().getAll().filter(query=>query.queryKey.includes('entity-select')&&query.queryKey.includes('/common/users/picker/items')).map(query=>({pages:query.state.data?.pages?.length??0,rows:query.state.data?.pages?.reduce((sum,page)=>sum+page.content.length,0)??0}));stack.push(fiber.child,fiber.sibling,fiber.stateNode?.current);}return null;})()`);
  assert.ok(cached?.some(query=>query.pages === 10 && query.rows <= 200), 'actual search cache evicts earlier pages after its 10-page budget');
  assert.ok(cached.every(query=>query.pages <= 10 && query.rows <= 200));
  assert.ok(await evaluate(`document.querySelectorAll('${options}').length <= 25`), 'only a virtualized option window mounts');
  await fill(owner, 'picker519'); await option('Candidate519');
  await clickText(options, 'Candidate519 · picker519@example.test · 13800000519');
  await fill(form + ' #root_description', 'Actual picker selection metadata'); await clickReady(form + ' button[type="submit"]');
  await poll(()=>evaluate(`!document.querySelector('${form}')`), 'real ID submits through platform form');
  assert.equal(fixture.writes.at(-1).data.ownerId, 'user-519', 'single-value callback changes the actual stored field, not its display label');
  console.log('PASS: actual UserPicker single field, persisted off-page identity, minimum prefix, 521 paged candidates, bounded cache/DOM and real ID submission');

  await open(); fixture.failRead('search'); await search(owner, '138');
  await poll(()=>evaluate(`document.querySelector('${popup}').textContent.includes('加载失败')`), 'search error is explicit');
  const failedSearch = readCount('search'); await clickText(popup + ' button', '重试'); await option('Candidate000');
  assert.equal(readCount('search'), failedSearch + 1, 'search retry makes exactly one actual read');
  fixture.delaySearch('picker51', 1400); await fill(owner, 'picker51');
  await poll(()=>fixture.reads.some(read=>read.params.get('keyword') === 'picker51' && !read.completed), 'delayed directory read in flight');
  await shutPopup(owner);
  await poll(()=>evaluate(`window.__pickerAborts.some(url=>url.includes('keyword=picker51'))`), 'closing selector aborts its unused HTTP read');
  await search(owner, 'picker520'); await option('Candidate520');
  await poll(()=>fixture.reads.filter(read=>read.params.get('keyword') === 'picker51').every(read=>read.completed), 'old delayed transport settles without affecting new query');
  assert.equal(await evaluate(`document.querySelector('${popup}').textContent.includes('Candidate510')`), false, 'late old search cannot overwrite current options');
  await shutPopup(owner); await close();

  await scope('scope-b'); await open();
  await poll(()=>evaluate(`document.querySelector('${form}').textContent.includes('New scope Candidate520')`), 'same ID resolves in the new authorization scope');
  await close();
  await scope('denied'); await open();
  await poll(()=>evaluate(`!!document.querySelector('${form} .entity-select-lookup-error')`), '403 selected identity is not a cached name');
  assert.equal(await evaluate(`document.querySelector('${form}').textContent.includes('Candidate520')`), false);
  assert.equal(await evaluate(`document.querySelector('${owner}').closest('.ant-select').classList.contains('ant-select-disabled')`), true);
  await fill(form + ' #root_description', 'No owner permission escalation'); await clickReady(form + ' button[type="submit"]');
  await poll(()=>evaluate(`!document.querySelector('${form}')`), 'other permitted metadata remains editable');
  assert.equal('ownerId' in fixture.writes.at(-1).data, false, 'protected owner is omitted even when readonly hydration fails');
  assert.equal(fixture.tenant().ownerId, 'user-520', 'failed readonly hydration cannot replace the persisted owner');

  await scope('readonly'); fixture.failRead('lookup'); await open();
  await poll(()=>evaluate(`!!document.querySelector('${form} .entity-select-lookup-error')`), 'readonly lookup failure retained');
  const failedLookup = readCount('lookup');
  await clickText(form + ' .entity-select-lookup-error button', '重试');
  await poll(()=>evaluate(`!document.querySelector('${form} .entity-select-lookup-error') && document.querySelector('${form}').textContent.includes('Candidate520')`), 'readonly read retry succeeds without enabling value changes');
  assert.equal(readCount('lookup'), failedLookup + 1);
  assert.equal(await evaluate(`document.querySelector('${owner}').closest('.ant-select').classList.contains('ant-select-disabled')`), true);
  await close();
  console.log('PASS: real failed search/read retry, unused-request cancellation, late-result isolation, same-ID scope isolation, readonly recovery and original-value preservation');

  await scope('multi-large'); const largeStart = fixture.reads.length; fixture.delayLookup(40); await open();
  await poll(()=>fixture.reads.slice(largeStart).filter(read=>read.kind === 'lookup').reduce((sum,read)=>sum + (read.completed ? read.params.get('ids').split(',').length : 0), 0) === 521
    && evaluate(`!!document.querySelector('${members}') && document.querySelector('${form}').textContent.includes('Candidate000') && !document.querySelector('${form} .entity-select-lookup-error')`), 'all 521 persisted multiple identities hydrate');
  const tags = await evaluate(`document.querySelector('${members}').closest('.ant-select').querySelectorAll('.ant-select-selection-item').length`);
  assert.ok(tags > 0 && tags < 521, 'responsive selection mounts a subset of tags, not all persisted identities');
  const largeReads = fixture.reads.slice(largeStart).filter(read=>read.kind === 'lookup');
  assert.deepEqual(largeReads.flatMap(read=>read.params.get('ids').split(',')), fixture.users.map(user=>user.id));
  assert.ok(largeReads.every(read=>read.params.get('ids').split(',').length <= 100 && read.params.toString().length <= 2500 && read.completed));
  for (let index = 1; index < largeReads.length; index++) assert.ok(largeReads[index].start >= largeReads[index - 1].end, 'large hydration is sequential, not a request burst');
  await fill(form + ' #root_description', 'Large persisted selection untouched'); await clickReady(form + ' button[type="submit"]');
  await poll(()=>evaluate(`!document.querySelector('${form}')`), 'large multiple values submit without truncation');
  assert.deepEqual(fixture.writes.at(-1).data.userIds, fixture.users.map(user=>user.id));

  await scope('multi-delayed'); fixture.delayLookup(650); const delayedStart = fixture.reads.length; await open();
  await poll(()=>fixture.reads.slice(delayedStart).some(read=>read.kind === 'lookup' && !read.completed), 'first large selected batch in flight');
  await evaluate(`location.hash='#/profile'`);
  await poll(()=>evaluate(`!!document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${form}')`), 'picker follows retained page activation');
  await poll(()=>fixture.reads.slice(delayedStart).some(read=>read.completed), 'inactive batch settles');
  assert.equal(fixture.reads.slice(delayedStart).filter(read=>read.kind === 'lookup').length, 1, 'inactive page abort stops future selected batches');
  assert.ok(await evaluate(`window.__pickerAborts.some(url=>new URL(url,location.origin).searchParams.get('ids')?.split(',').length > 1)`));
  fixture.delayLookup(0); await clickMenu('租户成员分配测试');
  await poll(()=>evaluate(`!!document.querySelector('${members}') && document.querySelector('${form}').textContent.includes('Candidate000')`), 'returning to owner resumes the retained form and selected query');
  await close();

  await scope('multi-edit'); await open();
  await poll(()=>evaluate(`!!document.querySelector('${members}') && document.querySelector('${form}').textContent.includes('Candidate520')`), 'editable actual DTO multiple widget');
  await search(members, 'picker51'); await option('Candidate510'); await clickText(options, 'Candidate510 · picker510@example.test · 13800000510');
  await option('Candidate511');
  await poll(()=>evaluate(`(()=>{const candidate=[...document.querySelectorAll('${options}')].find(element=>element.textContent.includes('Candidate511'));return !!candidate && candidate.classList.contains('ant-select-item-option-disabled');})()`), 'actual schema maxItems prevents a fourth user');
  await shutPopup(members); await fill(form + ' #root_description', 'Three real selected users'); await clickReady(form + ' button[type="submit"]');
  await poll(()=>evaluate(`!document.querySelector('${form}')`), 'array callback reaches real platform form');
  assert.deepEqual(fixture.writes.at(-1).data.userIds, ['user-000', 'user-520', 'user-510']);
  console.log('PASS: actual generated multiple field, all 521 persisted IDs in bounded sequential batches, no truncation, inactive cancellation/resume and actual array submission');
  await scope('required'); await open();
  await poll(()=>evaluate(`document.querySelector('${form}').textContent.includes('Candidate520')`), 'required field resolves before clearing checks');
  assert.equal(await evaluate(`!!document.querySelector('${owner}').closest('.ant-select').querySelector('.ant-select-clear')`), false, 'required picker has no clear action');
  await close(); await scope('all'); await open();
  await poll(()=>evaluate(`!!document.querySelector('${owner}').closest('.ant-select').querySelector('.ant-select-clear')`), 'optional real picker has its clear action');
  await evaluate(`document.querySelector('${owner}').closest('.ant-select').querySelector('.ant-select-clear').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await poll(()=>evaluate(`!document.querySelector('${owner}').closest('.ant-select').textContent.includes('Candidate520')`), 'optional clear callback removes only the actual field value');
  await close(true);
  console.log('PASS: required/optional clear semantics remain owned by the actual platform schema form');
}
