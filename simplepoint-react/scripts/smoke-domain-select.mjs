import assert from 'node:assert/strict';

export async function smokeDomainSelect({clickReady, clickText, fill, poll, evaluate, fixture, reload, send}) {
  fixture.enable(); await reload();
  const popup = '.ant-select-dropdown:not(.ant-select-dropdown-hidden)';
  const input = kind => `.ant-popover:not(.ant-popover-hidden) [data-domain-select="${kind}"] input`;
  const option = text => poll(() => evaluate(`[...document.querySelectorAll('${popup} .ant-select-item-option')].some(node=>(node.querySelector('.sp-tenant-name')?.textContent ?? node.textContent)===${JSON.stringify(text)})`), text);
  const chooseTenant = text => clickText(popup + ' .sp-tenant-name', text);
  const open = async (kind, button) => {
    await clickReady(button);
    try {await poll(()=>evaluate(`!!document.querySelector('${input(kind)}')`), kind + ' public selector');}
    catch (error) {
      console.error('Directory overlay diagnostics', await evaluate(`({buttons:[...document.querySelectorAll('.nb-context-switcher')].map(node=>node.outerHTML),popovers:[...document.querySelectorAll('.ant-popover')].map(node=>node.outerHTML),scope:localStorage.getItem('sp.tenantId')})`));
      throw error;
    }
  };
  await open('tenant', '.nb-tenant-switcher'); await option('Workspace000');
  await poll(() => evaluate(`!!document.querySelector('${popup} [data-tenant-type="PLATFORM"] .anticon-global')
    && document.querySelector('${popup} [data-tenant-type="PLATFORM"] .sp-tenant-type')?.textContent==='平台'`), 'platform icon and type');
  await poll(() => evaluate(`!![...document.querySelectorAll('${popup} img')].find(image=>image.complete && image.naturalWidth>0)`), 'actual custom tenant logo decodes');
  assert.equal(await evaluate(`document.querySelector('${popup} [data-tenant-type="ORGANIZATION"] .sp-tenant-type')?.textContent`), '组织');
  for (let page = 1; page <= 12; page++) {
    await clickText(popup + ' button', '加载更多');
    await poll(()=>fixture.reads.some(read=>read.path.endsWith('/tenants') && read.params.get('page') === String(page)), 'real tenant page ' + page);
    await poll(()=>evaluate(`!document.querySelector('${popup} .ant-btn-loading')`), 'page loaded');
  }
  assert.ok(await evaluate(`document.querySelectorAll('${popup} .ant-select-item-option').length < 40`), 'bounded virtual DOM');
  await fill(input('tenant'), 'Workspace240'); await option('Workspace240');
  await fill(input('tenant'), 'Workspace239'); await option('Workspace239');
  await poll(() => evaluate(`!!document.querySelector('${popup} .anticon-apartment') && !document.querySelector('${popup} img')`), 'broken logo falls back to the actual organization icon');
  await chooseTenant('Workspace239');
  await poll(()=>evaluate(`localStorage.getItem('sp.tenantId')==='workspace-239' && !document.querySelector('${input('tenant')}')`), 'actual workspace switch');
  await poll(()=>evaluate(`document.querySelector('.sp-home-eyebrow')?.textContent.includes('Workspace239')`), 'new workspace finishes host initialization');
  await poll(()=>evaluate(`!!document.querySelector('.nb-tenant-current-logo .anticon-apartment') && !document.querySelector('.nb-tenant-current-logo img')`), 'header shares the same broken-logo fallback');
  await poll(()=>evaluate(`!document.querySelector('.nb-role-switcher').disabled`), 'context roles ready');
  await open('role', '.nb-role-switcher'); await option('ContextRole000');
  await fill(input('role'), 'ContextRole124'); await option('ContextRole124');
  await clickText(popup + ' .ant-select-item-option', 'ContextRole124');
  await poll(()=>evaluate(`localStorage.getItem('sp.roleId:workspace-239')==='context-role-124'`), 'real role ID, not authority, is selected');
  await poll(()=>evaluate(`document.querySelector('.sp-home-eyebrow')?.textContent.includes('Workspace239') && document.querySelector('.nb-role-current-name')?.textContent==='ContextRole124'`), 'new role finishes host initialization');
  await open('role', '.nb-role-switcher');
  await poll(()=>evaluate(`document.querySelector('[data-domain-select="role"]').textContent.includes('ContextRole124')`), 'off-page selected role hydration');
  await clickText('.ant-popover:not(.ant-popover-hidden) button', '全部角色');
  await poll(()=>evaluate(`!localStorage.getItem('sp.roleId:workspace-239')`), 'all-roles sentinel never persisted as a role ID');
  await poll(()=>evaluate(`document.querySelector('.sp-home-eyebrow')?.textContent.includes('Workspace239') && document.querySelector('.nb-role-current-name')?.textContent==='全部角色'`), 'merged-role host initialization');
  await open('tenant', '.nb-tenant-switcher');
  await option('Workspace000'); fixture.failNext();
  await fill(input('tenant'), 'Workspace238');
  await poll(()=>evaluate(`document.querySelector('${popup}')?.textContent.includes('加载失败')`), 'failed directory read');
  assert.equal(await evaluate(`localStorage.getItem('sp.tenantId')`), 'workspace-239');
  await clickText(popup + ' button', '重试'); await option('Workspace238');
  fixture.failAuthorization();
  const oldContext = await evaluate(`localStorage.getItem('sp.contextId')`);
  await chooseTenant('Workspace238');
  await poll(()=>evaluate(`document.querySelector('.ant-message-error')?.textContent.includes('操作失败')`), 'authorization preflight failure is visible');
  assert.equal(await evaluate(`localStorage.getItem('sp.tenantId')`), 'workspace-239', 'failed authorization does not change active tenant');
  assert.equal(await evaluate(`localStorage.getItem('sp.contextId')`), oldContext, 'failed authorization does not change active context');
  await clickReady(input('tenant')); await fill(input('tenant'), 'Workspace238'); await option('Workspace238');
  await chooseTenant('Workspace238');
  await poll(()=>evaluate(`localStorage.getItem('sp.tenantId')==='workspace-238'`), 'retry and next workspace switch');
  assert.ok(fixture.reads.filter(read=>read.path.endsWith('/roles')).every(read=>read.params.get('tenantId')==='workspace-239'));
  assert.ok(fixture.reads.some(read=>read.path.endsWith('/roles/selected') && read.params.get('values')==='context-role-124'));
  await poll(()=>evaluate(`document.querySelector('.sp-home-eyebrow')?.textContent.includes('Workspace238')`), 'workspace initialization finishes before next switch');
  await open('tenant', '.nb-tenant-switcher');
  await fill(input('tenant'), '我的空间'); await option('我的空间');
  assert.equal(await evaluate(`document.querySelector('${popup} .sp-tenant-type')?.textContent`), '个人');
  assert.equal(await evaluate(`!!document.querySelector('${popup} .anticon-home')`), true);
  assert.equal(await evaluate(`document.querySelector('${popup}').textContent.includes(${JSON.stringify(fixture.personalName)})`), false);
  await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
  await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
  await poll(()=>evaluate(`localStorage.getItem('sp.tenantId')===${JSON.stringify(fixture.personalId)} && document.querySelector('.nb-tenant-current-name')?.textContent==='我的空间'`), 'short label preserves the real personal tenant ID');
  await poll(()=>evaluate(`document.querySelector('.sp-home-eyebrow')?.textContent.includes(${JSON.stringify(fixture.personalName)})`), 'personal workspace initialization');
  await open('tenant', '.nb-tenant-switcher');
  await poll(()=>evaluate(`document.querySelector('.ant-popover:not(.ant-popover-hidden) [data-domain-select="tenant"]')?.textContent.includes('我的空间')`), 'persisted personal selection hydrates with the same short label');
  assert.equal(await evaluate(`document.querySelector('.ant-popover:not(.ant-popover-hidden) [data-domain-select="tenant"]').textContent.includes(${JSON.stringify(fixture.personalName)})`), false);
  assert.ok(fixture.reads.some(read=>read.path.endsWith('/tenants/selected') && read.params.get('values')===fixture.personalId));
  assert.equal(await evaluate(`document.querySelector('[data-domain-select="tenant"] .sp-tenant-type')?.textContent`), '个人');
  await evaluate(`window.spI18n.setLocale('en-US')`);
  await poll(()=>evaluate(`document.querySelector('[data-domain-select="tenant"] .sp-tenant-name')?.textContent==='My Workspace'
    && document.querySelector('[data-domain-select="tenant"] .sp-tenant-type')?.textContent==='Personal'`), 'selected rich identity follows actual locale changes');
  assert.equal(await evaluate(`localStorage.getItem('sp.tenantId')`), fixture.personalId);
  await evaluate(`window.spI18n.setLocale('zh-CN')`);
  await poll(()=>evaluate(`document.querySelector('.nb-tenant-current-name')?.textContent==='我的空间'`), 'locale resets without changing workspace');
  await send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: false});
  await poll(()=>evaluate(`(()=>{const popup=document.querySelector('.ant-popover:not(.ant-popover-hidden)'),
    identity=popup?.querySelector('.sp-tenant-identity'); if(!identity)return false;
    const bounds=popup.getBoundingClientRect(), row=identity.getBoundingClientRect();
    return bounds.left>=0 && bounds.right<=innerWidth && row.width>0 && identity.scrollWidth<=identity.clientWidth+1;})()`), 'rich selected identity fits a narrow viewport').catch(async error => {
      console.error('Narrow tenant identity', JSON.stringify(await evaluate(`({width:innerWidth,popovers:[...document.querySelectorAll('.ant-popover')].map(node=>({
        className:node.className,rect:node.getBoundingClientRect().toJSON(),identities:[...node.querySelectorAll('.sp-tenant-identity')].map(item=>({
          rect:item.getBoundingClientRect().toJSON(),display:getComputedStyle(item).display,client:item.clientWidth,scroll:item.scrollWidth,text:item.textContent}))}))})`), null, 2));
      throw error;
    });
  await send('Emulation.clearDeviceMetricsOverride');
  console.log('PASS: shared tenant/role switchers, 243 workspaces, bounded pages/DOM, off-page identities, safe switching, all tenant types, actual logos and failed-image fallback, localized rich options/header/hydration without changing IDs');
}
