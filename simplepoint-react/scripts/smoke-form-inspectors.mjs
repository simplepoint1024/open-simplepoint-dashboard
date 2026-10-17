import assert from 'node:assert/strict';

/** Exercise actual pointer geometry, not synthetic change events. No design document mutation. */
export async function smokeFormInspectors({clickReady, clickText, poll, evaluate, send}) {
  const root = '.nb-tab-panel-active', drawer = '.sp-form-properties-drawer.ant-drawer-open';
  const size = selector => evaluate(`document.querySelector('${selector}')?.getBoundingClientRect().width ?? 0`);
  const drag = async (selector, delta) => {
    await poll(() => evaluate("!document.getAnimations().some(a=>a.playState==='running'&&a.effect?.getTiming().iterations!==Infinity)"), 'settled drag target');
    const p = await evaluate(`(() => {const r=document.querySelector('${selector}').getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+Math.min(r.height/2,100)};})()`);
    await send('Input.dispatchMouseEvent', {type:'mouseMoved', ...p});
    await send('Input.dispatchMouseEvent', {type:'mousePressed', button:'left', clickCount:1, ...p});
    for (let i=1;i<=6;i++) await send('Input.dispatchMouseEvent', {type:'mouseMoved', buttons:1, x:p.x+delta*i/6,y:p.y});
    await send('Input.dispatchMouseEvent', {type:'mouseReleased', button:'left', clickCount:1,x:p.x+delta,y:p.y});
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    await poll(() => evaluate("!document.getAnimations().some(a=>a.playState==='running'&&a.effect?.getTiming().iterations!==Infinity)"), 'settled resized geometry');
  };
  assert.equal(await evaluate(`document.querySelectorAll('${root} .sp-form-designer-palette .sp-form-panel-heading').length`), 0);
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} .sp-form-component-group')].every(g=>g.querySelector('.anticon'))`), true);
  assert.equal(await evaluate(`[...document.querySelectorAll('${root} .sp-form-designer-toolbar button')].every(b=>b.classList.contains('ant-btn-text'))`), true);
  const panel=root+' .sp-resizable-sidebar-panel';
  const before=await size(panel);
  await drag(root+' .ant-splitter-bar-dragger',-90);
  await poll(async () => (await size(panel))>before+60, 'component inspector grows on real pointer drag');
  const expanded=await size(panel), canvas=await size(root+' .sp-form-designer-center');
  await clickReady(root+' .ant-splitter-bar-collapse-bar-end');
  await poll(async () => (await size(panel))<1, 'component inspector collapsed');
  assert.ok((await size(root+' .sp-form-designer-center'))>canvas+expanded-5);
  await clickReady(root+' .ant-splitter-bar-collapse-bar-start');
  await poll(async () => Math.abs((await size(panel))-expanded)<2, 'component inspector restores chosen width');
  await drag(root+' .ant-splitter-bar-dragger',90);
  await clickReady(root+' button[aria-label="表单属性"]');
  await poll(async () => (await size(drawer+' [role="dialog"]'))>600, 'form inspector visible');
  await clickText(drawer+' .ant-tabs-tab', '字段属性');
  const drawerBefore=await size(drawer+' [role="dialog"]');
  await drag(drawer+' [role="separator"]',110);
  await poll(async () => (await size(drawer+' [role="dialog"]'))<drawerBefore-80, 'form inspector shrinks on pointer drag');
  const narrowed=await size(drawer+' [role="dialog"]');
  await clickReady(drawer+' .ant-drawer-close');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'form inspector collapsed');
  await clickReady(root+' button[aria-label="表单属性"]');
  await poll(async () => Math.abs((await size(drawer+' [role="dialog"]'))-narrowed)<2, 'form inspector restores width');
  assert.equal(await evaluate(`document.querySelector('${drawer} .ant-tabs-tab-active')?.textContent`),'字段属性');
  await clickReady(drawer+' .ant-drawer-close');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'form inspector closes');
  console.log('PASS: borderless icon toolbar, group icons, draggable/collapsible inspectors and restored width/tab state');
}
