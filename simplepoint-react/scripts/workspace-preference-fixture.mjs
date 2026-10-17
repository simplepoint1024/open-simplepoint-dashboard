import assert from 'node:assert/strict';

export async function smokeWorkspacePreference({send, evaluate, poll, clickReady, clickText, origin, setUser}) {
  const active = id => poll(() => evaluate(`localStorage.getItem('sp.tenantId') === ${JSON.stringify(id)} && !!document.querySelector('.nb-tab-panel-active .sp-home-eyebrow') && document.querySelector('.nb-tenant-switcher')?.textContent.includes(${JSON.stringify(id === 'workspace-1' ? 'Workspace001' : '平台')})`), 'active workspace ' + id);
  const logout = async () => {
    const point=await poll(()=>evaluate(`(()=>{const el=document.querySelector('.nb-header-user');if(!el)return;const r=el.getBoundingClientRect();const x=r.x+r.width/2,y=r.y+r.height/2;return el.contains(document.elementFromPoint(x,y))?{x,y}:undefined})()`),'profile menu reachable');
    await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:400,y:400});
    await send('Input.dispatchMouseEvent',{type:'mouseMoved',...point});
    await clickText('.ant-menu-submenu-popup .ant-menu-item', '退出登录');
    await poll(() => evaluate(`document.body.textContent === 'Logged out'`), 'actual logout form navigation');
  };
  const login = async () => {await send('Page.navigate', {url: origin});};
  await active('__platform__');
  await clickReady('.nb-tenant-switcher');
  await clickText('.ant-select-dropdown .ant-select-item-option .sp-tenant-name', 'Workspace001');
  await active('workspace-1');
  await poll(() => evaluate(`JSON.parse(localStorage.getItem('sp.preference.workspace.v1'))?.smoke === 'workspace-1'`), 'choice saved');
  await logout();
  await login(); await active('workspace-1');
  await poll(() => evaluate(`document.querySelector('.nb-tenant-switcher').textContent.includes('Workspace001')`), 'restored workspace label');
  await logout(); setUser('another-account'); await login(); await active('__platform__');
  await logout(); setUser('smoke'); await login(); await active('workspace-1');
  console.log('PASS: workspace switch, real logout POST/navigation, next-login restoration and separate-account isolation');
}
