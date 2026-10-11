import assert from 'node:assert/strict';

export async function smokeProfileAccess({clickMenu, clickReady, poll, evaluate, send, fixture}) {
  const root = '.nb-tab-panel-active', cards = root + ' .profile-permission-item';
  const changeScope = async key => {
    await evaluate(`window.__profileSmokePreviousLayout=document.querySelector('.nb-header');localStorage.setItem('sp.contextId:__platform__',${JSON.stringify(key)});window.dispatchEvent(new CustomEvent('sp-set-context-id',{detail:{tenantId:'__platform__',contextId:${JSON.stringify(key)}}}));`);
    // NotificationProvider and its navigation tree are keyed by queryScope.
    // Do not act on the previous scope's still-mounted navigation controls.
    await poll(() => evaluate("!!document.querySelector('.nb-header') && document.querySelector('.nb-header') !== window.__profileSmokePreviousLayout"), 'new scoped navigation header');
    await evaluate('delete window.__profileSmokePreviousLayout');
  };
  await clickMenu('首页');
  await evaluate("location.hash='#/profile'");
  await poll(() => evaluate(`!!document.querySelector('${root} .profile-nav-links')`), 'profile navigation');
  await clickReady(root + ' .profile-nav-links button:nth-child(2)');
  const scoped = async mode => {
    fixture.mode(mode);
    const before = fixture.reads.length, key = `profile-access-${mode}`;
    await changeScope(key);
    // Host discards scoped tabs on a context change; enter the profile again.
    await clickMenu('首页');
    await evaluate("location.hash='#/profile'");
    await poll(() => evaluate(`!!document.querySelector('${root} .profile-nav-links')`), mode + ' profile navigation');
    await clickReady(root + ' .profile-nav-links button:nth-child(2)');
    await poll(() => fixture.reads.slice(before).some(read => read.mode === mode && read.endpoint.endsWith('/resources'))
      && evaluate(`document.querySelectorAll('${cards}').length === 2 && [...document.querySelectorAll('${cards}')].every(button => button.disabled === ${mode !== 'readable'})`), 'native ' + mode + ' profile rows');
  };
  for (const mode of ['masked', 'hidden']) {
    await scoped(mode);
    assert.equal(await evaluate(`document.querySelector('${root} .profile-permission-grid').textContent.includes('原生权限页面')`), true);
    assert.equal(await evaluate(`document.querySelector('${root} .profile-permission-grid').textContent.includes('第二权限页面')`), true);
    const before = fixture.reads.filter(read => read.endpoint.endsWith('/page-detail')).length;
    // Real pointer events and a DOM disabled mutation must not turn protected
    // codes into public detail identities; React also retains disabled props.
    const point = await evaluate(`(()=>{const r=document.querySelector('${cards}').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2};})()`);
    await send('Input.dispatchMouseEvent', {type: 'mousePressed', ...point, button: 'left', clickCount: 1});
    await send('Input.dispatchMouseEvent', {type: 'mouseReleased', ...point, button: 'left', clickCount: 1});
    await evaluate(`(()=>{for(const button of document.querySelectorAll('${cards}')) {button.disabled=false;button.click();button.disabled=true;}return true;})()`);
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve(true))))');
    assert.equal(fixture.reads.filter(read => read.endpoint.endsWith('/page-detail')).length, before);
    assert.equal(await evaluate("!!document.querySelector('.profile-access-detail')"), false);
    assert.equal(await evaluate(`/profile-(page|second|edit)|i18n:|\\*\\*\\*/.test(document.querySelector('${root} .profile-permission-grid').textContent)`), false);
  }
  await scoped('readable');
  await clickReady(cards);
  await poll(() => evaluate("document.querySelector('.profile-access-detail')?.textContent.includes('编辑权限')"), 'native readable profile detail');
  assert.equal(await evaluate("document.querySelector('.profile-access-detail').textContent.includes('原生权限目录')"), true);
  assert.equal(await evaluate("document.querySelectorAll('.profile-access-operation').length"), 1);
  assert.equal(await evaluate("/profile-(page|second|edit)|i18n:/.test(document.querySelector('.profile-access-detail').textContent)"), false);
  assert.ok(fixture.reads.filter(read => read.endpoint.endsWith('/page-detail')).every(read => read.mode === 'readable' && read.params.get('code') === 'profile-page'));
  await clickReady('.ant-drawer-close');

  const department = root + ' .profile-organization-grid > .profile-organization-group:nth-child(2)';
  for (const mode of ['masked', 'hidden', 'readable', 'unassigned']) {
    fixture.organization(mode); const before = fixture.reads.length, key = `profile-organization-${mode}`;
    await changeScope(key);
    await clickMenu('首页'); await evaluate("location.hash='#/profile'");
    await poll(() => evaluate(`!!document.querySelector('${root} .profile-nav-links')`), mode + ' organization navigation');
    await clickReady(root + ' .profile-nav-links button:nth-child(3)');
    const title = mode === 'masked' ? '***' : mode === 'hidden' ? '名称暂不可用' : '原生所属部门';
    await poll(() => fixture.reads.slice(before).some(read => read.endpoint.endsWith('/organization') && read.mode === mode)
      && evaluate(mode === 'unassigned'
        ? `/尚未分配部门|不关联租户部门/.test(document.querySelector('${department}')?.textContent ?? '')`
        : `document.querySelector('${department} h4')?.textContent === ${JSON.stringify(title)}`), 'native ' + mode + ' organization state');
    const text = await evaluate(`document.querySelector('${department}').textContent`);
    assert.equal(/native-org-(root|leaf)|i18n:/.test(text), false, 'display never falls back to an internal ID or translation key');
    if (mode === 'hidden' || mode === 'masked') {
      assert.equal(/原生所属部门|原生上级部门|尚未分配部门|不关联租户部门/.test(text), false, 'protected assignment must not be mistaken for no assignment');
      if (mode === 'hidden') assert.ok(text.includes('名称暂不可用 / 名称暂不可用'));
      else assert.ok(text.includes('*** / ***'));
    }
    if (mode === 'readable') assert.ok(text.includes('原生上级部门 / 原生所属部门'));
  }
}
