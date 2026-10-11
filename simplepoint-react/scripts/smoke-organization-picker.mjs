import assert from 'node:assert/strict';

export async function smokeOrganizationPicker({clickMenu, clickReady, clickText, fill, poll, evaluate, fixture, member}) {
  fixture.enable();
  const root = '.nb-tab-panel-active', editor = '.tenant-member-editor .ant-modal', input = editor + ' #root_orgId';
  const popup = '.ant-select-dropdown:not(.ant-select-dropdown-hidden)';
  const branch = 'browse:children:' + JSON.stringify('picker-root-0') + ':';
  const action = (channel, kind) => popup + ` [data-organization-navigation=${JSON.stringify(channel)}][data-organization-action="${kind}"] button`;
  const seen = (kind, identity, page) => fixture.reads.some(read => read.kind === kind
    && (identity == null || (read.params.get('parentId') ?? read.params.get('keyword') ?? read.params.get('ids')) === identity)
    && Number(read.params.get('page')) === page && read.completed);
  const option = id => popup + ` [data-organization-id="${id}"]`;
  const seek = async selector => {
    if (await evaluate(`!!document.querySelector(${JSON.stringify(selector)})`)) return;
    await evaluate(`(()=>{const holder=document.querySelector('${popup} .ant-select-tree-list-holder');if(holder){holder.scrollTop=0;holder.dispatchEvent(new Event('scroll',{bubbles:true}));}return true;})()`);
    await poll(() => evaluate(`(()=>{if(document.querySelector(${JSON.stringify(selector)}))return true;const holder=document.querySelector('${popup} .ant-select-tree-list-holder');if(holder){holder.scrollTop=holder.scrollTop+holder.clientHeight>=holder.scrollHeight-1?0:holder.scrollTop+144;holder.dispatchEvent(new Event('scroll',{bubbles:true}));}return false;})()`), 'virtual tree item becomes visible: ' + selector);
  };
  const find = id => seek(option(id));
  const openForm = async () => {
    await poll(() => evaluate(`!!document.querySelector('${root} tr[data-row-key="member-0"] input[type="radio"]') && !document.querySelector('${root} .ant-spin-spinning')`), 'real member row');
    await clickReady(root + ' tr[data-row-key="member-0"] input[type="radio"]');
    await clickText(root + ' .sp-member-table-scroll .sp-table-button-group button', '编辑成员');
    await poll(() => evaluate(`!!document.querySelector('${input}')`), 'actual SForm organization widget');
  };
  const openPicker = async () => {
    await evaluate(`(()=>{document.querySelector('${input}').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));return true;})()`);
    await poll(() => evaluate(`!!document.querySelector('${popup}')`), 'tree popup opens');
  };
  const closePicker = async () => {
    await evaluate(`document.querySelector('${input}').dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,which:27,bubbles:true}))`);
    await poll(() => evaluate(`!document.querySelector('${popup}')`), 'tree popup closes');
  };
  const expand = async id => {
    await poll(() => evaluate(`(()=>{const switcher=document.querySelector(${JSON.stringify(option(id))})?.closest('.ant-select-tree-treenode')?.querySelector('.ant-select-tree-switcher');if(switcher){switcher.click();return true;}const holder=document.querySelector('${popup} .ant-select-tree-list-holder');if(holder){holder.scrollTop=holder.scrollTop+holder.clientHeight>=holder.scrollHeight-1?0:holder.scrollTop+144;holder.dispatchEvent(new Event('scroll',{bubbles:true}));}return false;})()`), 'actual visible branch expands atomically: ' + id);
  };
  const browse = async (channel, kind, duplicate = false) => {
    const selector = action(channel, kind);
    await poll(() => evaluate(`(()=>{const button=document.querySelector(${JSON.stringify(selector)});if(button){if(button.disabled||button.classList.contains('ant-btn-loading'))return false;button.click();${duplicate ? 'button.click();' : ''}return true;}const holder=document.querySelector('${popup} .ant-select-tree-list-holder');if(holder){holder.scrollTop=holder.scrollTop+holder.clientHeight>=holder.scrollHeight-1?0:holder.scrollTop+144;holder.dispatchEvent(new Event('scroll',{bubbles:true}));}return false;})()`), 'real tree navigation ' + kind);
  };
  const scope = async mode => {
    fixture.mode(mode);
    await evaluate(`localStorage.setItem('sp.contextId:__platform__',${JSON.stringify('organization-' + mode)});window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:${JSON.stringify('organization-' + mode)}}}));`);
    await poll(() => evaluate(`!document.querySelector('${editor}') && !!document.querySelector('${root} tr[data-row-key="member-0"]')`), 'new scope owns a new form');
  };
  const cacheWindows = () => evaluate(`(()=>{let current=document.querySelector('${input}').closest('.ant-select')[Object.keys(document.querySelector('${input}').closest('.ant-select')).find(key=>key.startsWith('__reactFiber$'))];while(current){if(current.memoizedProps?.owner){let hook=current.memoizedState;while(hook){if(hook.memoizedState instanceof Map)return [...hook.memoizedState].map(([channel,window])=>({channel,pages:window.pages.length,rows:window.pages.reduce((sum,page)=>sum+page.content.length,0)}));hook=hook.next;}}current=current.return;}return null;})()`);
  await evaluate(`(()=>{const original=window.fetch.bind(window);window.__organizationAborts=[];window.fetch=(input,options)=>{const url=typeof input==='string'?input:input.url,signal=options?.signal??input?.signal;if(url?.includes('/organizations/options')&&signal)signal.addEventListener('abort',()=>window.__organizationAborts.push(url),{once:true});return original(input,options);};return true;})()`);
  await clickMenu('成员查询测试'); await openForm();
  await poll(() => evaluate(`document.querySelector('${editor}').textContent.includes('Child520 (C520)')`), 'off-page persisted organization resolves exactly');
  assert.equal(fixture.reads.filter(read => read.kind !== 'lookup').length, 0, 'closed picker never downloads a tree');
  await openPicker(); await find('picker-root-0');
  for (let page = 1; page <= 4; page++) {await browse('browse:root', 'more'); await poll(() => seen('root', null, page), 'root page ' + page);}
  await find('picker-root-120'); await expand('picker-root-0'); await find('picker-child-0');
  assert.ok(await evaluate(`document.querySelector(${JSON.stringify(option('picker-child-3'))}).closest('.ant-select-tree-treenode').classList.contains('ant-select-tree-treenode-disabled')`));
  assert.ok(await evaluate(`document.querySelector(${JSON.stringify(option('picker-child-4'))}).closest('.ant-select-tree-treenode').classList.contains('ant-select-tree-treenode-disabled')`));
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(option('picker-child-4'))}).textContent`), 'C4', 'masked names are never rendered as selectable truthy labels');
  fixture.failNext('children', 'picker-root-0', 1); await browse(branch, 'more', true);
  await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(action(branch, 'retry'))})`), 'child page failure has its own retry');
  assert.equal(fixture.reads.filter(read => read.kind === 'children' && read.params.get('parentId') === 'picker-root-0'
    && read.params.get('page') === '1').length, 1, 'same-frame repeated navigation starts only one HTTP read');
  await find('picker-child-0'); await browse(branch, 'retry'); await poll(() => seen('children', 'picker-root-0', 1), 'same child page retry');
  for (let page = 2; page <= 17; page++) {
    await browse(branch, 'more'); await poll(() => seen('children', 'picker-root-0', page), 'direct child page ' + page);
  }
  await poll(cacheWindows, 'actual component bounded cache is inspectable');
  const windows = await cacheWindows();
  assert.ok(windows.some(window => window.pages === 10)); assert.ok(windows.every(window => window.pages <= 10 && window.rows <= 300));
  assert.ok(await evaluate(`document.querySelectorAll('${popup} [data-organization-id]').length < 30`), 'real tree mounts a virtualized window');
  await find('picker-child-519'); await clickReady(option('picker-child-519'));
  await poll(() => evaluate(`document.querySelector('${input}').closest('.ant-select').textContent.includes('Child519 (C519)')`), 'nested organization has one correct label');
  await openPicker();
  await browse(branch, 'previous'); await poll(() => seen('children', 'picker-root-0', 7), 'evicted children remain reachable by previous page');
  await browse(branch, 'more'); await closePicker();
  await clickReady(editor + ' .ant-modal-footer .ant-btn-primary');
  await poll(() => evaluate(`!document.querySelector('${editor}')`), 'real field submits');
  assert.equal(member.writes.at(-1).body.orgId, 'picker-child-519');
  assert.equal(member.writes.at(-1).body.revision, 0);
  console.log('PASS: actual SForm organization tree, independent child retry, 521 children, bounded cache/DOM, earlier-page access and literal-ID submission');

  fixture.failNext('root'); await openForm(); await openPicker();
  await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(action('browse:root', 'retry'))})`), 'root failure is retryable without a global toast');
  await browse('browse:root', 'retry'); await find('picker-root-0');
  const releaseChild = fixture.hold('children', 'picker-root-1'); await expand('picker-root-1');
  await poll(() => fixture.reads.some(read => read.kind === 'children' && read.params.get('parentId') === 'picker-root-1' && !read.completed), 'held child read');
  await closePicker();
  await poll(() => evaluate(`window.__organizationAborts.some(url=>new URL(url,location.origin).searchParams.get('parentId')==='picker-root-1')`), 'closing aborts the actual branch HTTP request');
  releaseChild(); await openPicker();
  await poll(() => fixture.reads.filter(read => read.kind === 'children' && read.params.get('parentId') === 'picker-root-1').length === 2
    && seen('children', 'picker-root-1', 0), 'interrupted expanded branch resumes on reopening');
  for (let index = 2; index < 25; index++) {
    await expand('picker-root-' + index); await poll(() => seen('children', 'picker-root-' + index, 0), 'bounded branch ' + index);
  }
  await poll(async () => (await cacheWindows()).filter(window => window.channel.startsWith('browse:children:')).length === 20, 'actual failed and successful branch slots obey the 20-branch budget');
  assert.equal((await cacheWindows()).some(window => window.channel === 'browse:children:' + JSON.stringify('picker-root-1') + ':'), false);
  const childReads = fixture.reads.filter(read => read.params.get('parentId') === 'picker-root-1').length;
  await expand('picker-root-1'); await poll(() => fixture.reads.filter(read => read.params.get('parentId') === 'picker-root-1').length === childReads + 1
    && fixture.reads.at(-1).completed, 'evicted branch can be expanded and read again');
  console.log('PASS: actual root failure retry, twenty-branch cache budget and evicted-branch re-expansion');
  await closePicker(); await clickText(editor + ' .ant-modal-footer button', '取消');
  await poll(() => evaluate(`!document.querySelector('${editor}')`), 'unchanged form closes');

  fixture.failNext('lookup', 'picker-child-519'); await scope('readonly'); await openForm();
  await poll(() => evaluate(`!!document.querySelector('${editor} .organization-select-lookup-error')`), 'readonly selected lookup failure is explicit');
  assert.ok(await evaluate(`document.querySelector('${input}').closest('.ant-select').classList.contains('ant-select-disabled')`));
  await clickText(editor + ' .organization-select-lookup-error button', '重试');
  await poll(() => evaluate(`!document.querySelector('${editor} .organization-select-lookup-error') && document.querySelector('${editor}').textContent.includes('Child519 (C519)')`), 'readonly retry resolves without editing');
  await clickReady(editor + ' .ant-modal-footer .ant-btn-primary'); await poll(() => evaluate(`!document.querySelector('${editor}')`), 'readonly original value preserved on save');
  assert.equal(member.writes.at(-1).body.orgId, 'picker-child-519');

  await scope('all'); await openForm(); await openPicker(); await find('picker-root-0');
  const releaseOld = fixture.hold('children', 'picker-root-2'); await expand('picker-root-2');
  await poll(() => fixture.reads.some(read => read.params.get('parentId') === 'picker-root-2' && !read.completed), 'old owner child read in flight');
  await scope('scope-b'); releaseOld(); await openForm();
  await poll(() => evaluate(`document.querySelector('${editor}').textContent.includes('New scope Child519 (C519)')`), 'same selected ID resolves only in new scope');
  await openPicker(); await find('picker-root-0');
  assert.equal(await evaluate(`document.querySelector('${popup}').textContent.includes('Sibling of picker-root-2')`), false, 'old branch cannot commit into a new owner');
  const releaseInactive = fixture.hold('children', 'picker-root-3'); await expand('picker-root-3');
  await poll(() => fixture.reads.some(read => read.params.get('parentId') === 'picker-root-3' && !read.completed), 'retained owner read is in flight');
  await evaluate(`location.hash='#/profile'`);
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active .profile-nav-links') && !document.querySelector('${editor}')`), 'inactive page hides its real form');
  await poll(() => evaluate(`window.__organizationAborts.some(url=>new URL(url,location.origin).searchParams.get('parentId')==='picker-root-3')`), 'inactive page aborts its organization read');
  releaseInactive(); await clickMenu('成员查询测试');
  await poll(() => evaluate(`document.querySelector('${editor}')?.textContent.includes('New scope Child519 (C519)')`), 'returning retained draft resolves only its current owner');
  await openPicker(); await find('picker-root-0');
  fixture.failNext('search', 'Child519'); await fill(input, 'Child519');
  await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(action('browse:search', 'retry'))})`), 'keyword failure has its own retry');
  await browse('browse:search', 'retry'); await find('picker-child-519');
  await fill(input, 'Child51'); const releaseSearch = fixture.hold('search', 'Child510'); await fill(input, 'Child510');
  await poll(() => fixture.reads.some(read => read.params.get('keyword') === 'Child510' && !read.completed), 'old keyword read in flight');
  await fill(input, 'Child520'); await find('picker-child-520'); releaseSearch();
  await poll(() => fixture.reads.filter(read => read.params.get('keyword') === 'Child510').every(read => read.completed), 'old keyword transport settles');
  assert.equal(await evaluate(`!!document.querySelector(${JSON.stringify(option('picker-child-510'))})`), false);
  assert.ok(await evaluate(`window.__organizationAborts.some(url=>new URL(url,location.origin).searchParams.get('keyword')==='Child510')`));
  await closePicker(); await clickText(editor + ' .ant-modal-footer button', '取消');
  await scope('denied'); await openForm();
  await poll(() => evaluate(`!!document.querySelector('${editor} .organization-select-lookup-error')`), 'new denied owner cannot reuse old names');
  assert.equal(await evaluate(`document.querySelector('${editor}').textContent.includes('Child519 (C519)')`), false);
  await clickReady(editor + ' .ant-modal-footer .ant-btn-primary'); await poll(() => evaluate(`!document.querySelector('${editor}')`), 'denied hydration preserves editable-member command');
  assert.equal(member.writes.at(-1).body.orgId, 'picker-child-519');
  await scope('wrong-id'); await openForm();
  await poll(() => evaluate(`document.querySelector('${editor}').textContent.includes('原值已保留，请重新选择')`), 'a mismatched selected response is unavailable, not another identity');
  assert.equal(await evaluate(`document.querySelector('${input}').closest('.ant-select').textContent.includes('Child000')`), false);
  await clickReady(editor + ' .ant-modal-footer .ant-btn-primary'); await poll(() => evaluate(`!document.querySelector('${editor}')`), 'mismatched response does not replace the original field');
  assert.equal(member.writes.at(-1).body.orgId, 'picker-child-519');
  member.rows[0].orgId = 'picker-child-3'; await scope('historical'); await openForm();
  await poll(() => evaluate(`document.querySelector('${editor}').textContent.includes('Child003 (C3)')`), 'disabled persisted organization remains resolvable');
  await openPicker(); await expand('picker-root-0'); await find('picker-child-3');
  assert.ok(await evaluate(`document.querySelector(${JSON.stringify(option('picker-child-3'))}).closest('.ant-select-tree-treenode').classList.contains('ant-select-tree-treenode-disabled')`));
  await closePicker(); await clickReady(editor + ' .ant-modal-footer .ant-btn-primary');
  await poll(() => evaluate(`!document.querySelector('${editor}')`), 'historical disabled value is not silently cleared or made eligible');
  assert.equal(member.writes.at(-1).body.orgId, 'picker-child-3');
  console.log('PASS: real transport cancellation/resume, readonly retry, unchanged stored values, exact same-ID scope isolation and late child/search suppression');
  console.log('PASS: exact selected-identity matching and historical disabled-value hydration without browsing eligibility escalation');
}
