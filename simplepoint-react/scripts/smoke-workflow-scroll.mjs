import assert from 'node:assert/strict';

/** Exercise actual built tables with real wheel events; fixtures supply data, never layout. */
export async function smokeWorkflowScroll({poll, evaluate, send, clickText, fixture}) {
  const page = '.nb-tab-panel-active .workflow-personal-page';
  const root = `${page} .sp-data-table-root`;
  const body = `${root} .ant-table-body`;
  for (let index = 0; index < 55; index++) {
    fixture.personal.matters.push({...fixture.personal.matters[0], id: `scroll-matter-${index}`,
      title: `滚动验收事项 ${index}：较长的业务说明用于验证自动换行后仍可到达最后一行`,
      canWithdraw: false, canCancel: false});
    fixture.personal.tasks.push({...fixture.personal.tasks[0], id: `scroll-task-${index}`, taskId: `scroll-task-${index}`,
      title: `滚动验收待办 ${index}：多行显示`, actionable: false});
  }
  const geometry = () => evaluate(`(() => {
    const root=document.querySelector('${root}'),body=root?.querySelector('.ant-table-body');
    const panel=document.querySelector('.nb-tab-panel-active'),pagination=root?.querySelector('.ant-pagination');
    const header=root?.querySelector('.ant-table-header'),toolbar=root?.querySelector('.sp-data-table-toolbar');
    return {body:!!body,height:body?.clientHeight,content:body?.scrollHeight,top:body?.scrollTop,
      paginationBottom:pagination?.getBoundingClientRect().bottom,panelBottom:panel?.getBoundingClientRect().bottom,
      headerTop:header?.getBoundingClientRect().top,toolbarTop:toolbar?.getBoundingClientRect().top,
      documentTop:document.scrollingElement.scrollTop,rootHeight:root?.getBoundingClientRect().height,
      layout:[root,...(root?.querySelectorAll('.ant-table-wrapper,.ant-spin-nested-loading,.ant-spin-container,.ant-table,.ant-table-container')??[])].filter(Boolean).map(e=>{
        const s=getComputedStyle(e);return {class:e.className,height:e.getBoundingClientRect().height,display:s.display,flex:s.flex,minHeight:s.minHeight};})};
  })()`);
  const route = async path => {
    await evaluate(`location.hash=${JSON.stringify(path)}`);
    const label = path === '/my/todos' ? '待办' : path === '/my/items' ? '我发起的' : '全部事项';
    await poll(() => evaluate(`document.querySelector('${page} .ant-segmented-item-selected')?.textContent===${JSON.stringify(label)}`), 'target workflow page activated');
    await poll(() => evaluate(`document.querySelectorAll('${page} tr[data-row-key]').length===20`), '20 actual workflow rows');
    const state = await geometry();
    assert.ok(state.body && state.height > 0 && state.content > state.height,
      `workflow table needs a bounded vertical scroll body: ${JSON.stringify(state)}`);
    assert.ok(state.paginationBottom <= state.panelBottom + 1, 'pagination must remain inside the page');
  };
  const wheel = async (deltaY, deltaX = 0) => {
    const point = await evaluate(`(() => {const r=document.querySelector('${body}').getBoundingClientRect();
      return {x:r.left+100,y:r.top+Math.min(100,r.height/2)};})()`);
    await send('Input.dispatchMouseEvent', {type: 'mouseWheel', ...point, deltaY, deltaX});
  };
  const scroll = async () => {
    const before = await geometry();
    await wheel(280);
    await poll(async () => (await geometry()).top > 0, 'actual wheel scrolls the workflow table');
    await wheel(10000);
    await poll(() => evaluate(`(()=>{const b=document.querySelector('${body}');
      return b.scrollTop+b.clientHeight>=b.scrollHeight-2;})()`), 'wheel reaches the final row');
    const after = await geometry();
    assert.equal(after.headerTop, before.headerTop, 'header stays fixed');
    assert.equal(after.toolbarTop, before.toolbarTop, 'toolbar stays fixed');
    assert.equal(after.documentTop, 0, 'table does not scroll the application shell');
  };
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
  await route('/smoke/workflow-management');
  await scroll();
  await wheel(0, 600);
  await poll(() => evaluate(`document.querySelector('${body}').scrollLeft>0`), 'horizontal scrolling remains available');
  assert.ok(await evaluate(`(()=>{const body=document.querySelector('${body}').getBoundingClientRect();
    const action=document.querySelector('${body} tr[data-row-key] td.ant-table-cell-fix-end')?.getBoundingClientRect();
    return action && action.right<=body.right+1 && action.left>=body.left;})()`), 'fixed row actions remain reachable');
  const headerBackground = () => evaluate(`getComputedStyle(document.querySelector('${root} th.ant-table-cell-fix-end')).backgroundColor`);
  assert.match(await headerBackground(), /^rgb\(/, 'light fixed header needs an opaque base, not only a translucent gradient');
  await evaluate(`document.querySelector('button[aria-label="toggle-theme"]').click()`);
  await poll(() => evaluate(`!!document.querySelector('.nb-root.theme-dark')`), 'actual dark theme enabled');
  await poll(async () => (await headerBackground()) === 'rgb(20, 20, 20)', 'dark fixed header uses an opaque themed base');
  await evaluate(`document.querySelector('button[aria-label="toggle-theme"]').click()`);
  await poll(() => evaluate(`localStorage.getItem('sp.theme')==='system'`), 'theme cycles through system');
  await evaluate(`document.querySelector('button[aria-label="toggle-theme"]').click()`);
  await poll(() => evaluate(`localStorage.getItem('sp.theme')==='light' && !!document.querySelector('.nb-root.theme-light')`), 'light theme restored');
  await clickText(`${root} .ant-pagination-item-2`, '2');
  await poll(() => fixture.requests.some(r => r.path === '/workflow/matters/all' && r.params.page === '1'), 'server page 2 requested');
  await poll(() => evaluate(`document.querySelector('${body} tr[data-row-key]')?.getAttribute('data-row-key')==='scroll-matter-15'`), 'page 2 data loaded');
  await poll(() => evaluate(`document.querySelector('${body}').scrollTop===0`), 'pagination scroll-to-first-row animation completed');
  await scroll();
  const saved = (await geometry()).top;
  await evaluate(`location.hash='/profile'`);
  await poll(() => evaluate(`!document.querySelector('${page}')`), 'workflow tab hidden');
  await send('Emulation.setDeviceMetricsOverride', {width: 1100, height: 620, deviceScaleFactor: 1, mobile: false});
  await evaluate(`location.hash='/smoke/workflow-management'`);
  await poll(() => evaluate(`!!document.querySelector('${body}')?.getClientRects().length`), 'cached workflow tab reactivated');
  assert.ok((await geometry()).top >= saved - 1, 'cached tab retains its scroll position');
  assert.ok((await geometry()).paginationBottom <= (await geometry()).panelBottom + 1, 'resize while hidden keeps pagination reachable');
  await scroll();
  await send('Runtime.evaluate', {expression: `document.querySelector('${root} button[aria-label="全屏"]').click()`, userGesture: true});
  await poll(() => evaluate(`document.fullscreenElement===document.querySelector('${root}')`), 'table enters real fullscreen');
  await poll(() => evaluate(`document.querySelector('${root} .ant-pagination').getBoundingClientRect().bottom<=innerHeight`), 'fullscreen pagination stays visible');
  await scroll();
  await evaluate('document.exitFullscreen()');
  await poll(() => evaluate('!document.fullscreenElement'), 'exit fullscreen');
  assert.ok((await geometry()).paginationBottom <= (await geometry()).panelBottom + 1, 'exit fullscreen restores constrained layout');
  await route('/my/items');
  await scroll();
  await route('/my/todos');
  await scroll();
  fixture.personal.delayLists(600);
  await evaluate(`document.querySelector('${root} button[aria-label="刷新"]').click()`);
  await poll(() => evaluate(`!!document.querySelector('${root} .ant-spin[aria-busy="true"]')`), 'real loading overlay');
  assert.ok((await geometry()).paginationBottom <= (await geometry()).panelBottom + 1, 'loading does not displace pagination');
  await poll(() => evaluate(`!document.querySelector('${root} .ant-spin[aria-busy="true"]')`), 'loading completed');
  fixture.personal.fail('/workflow/inbox/pending');
  await evaluate(`document.querySelector('${root} button[aria-label="刷新"]').click()`);
  await poll(() => evaluate(`!!document.querySelector('${page} > .ant-alert-error')`), 'failed list has a visible retry banner');
  assert.ok((await geometry()).paginationBottom <= (await geometry()).panelBottom + 1, 'error banner reserves height above the table');
  fixture.personal.delayLists(0);
  await clickText(`${page} > .ant-alert-error button`, '重试');
  await poll(() => evaluate(`document.querySelectorAll('${body} tr[data-row-key]').length===20`), 'retry restores scrollable results');
  await scroll();
  await clickText(`${page} .ant-segmented-item`, '已办');
  await poll(() => evaluate(`!!document.querySelector('${root} .sp-table-empty') && !document.querySelector('${root} .ant-spin[aria-busy="true"]')`), 'empty completed view');
  const empty = await geometry();
  assert.ok(empty.paginationBottom == null || empty.paginationBottom <= empty.panelBottom + 1,
    'empty list may omit pagination, but must never render it outside the page');
  await clickText(`${page} .ant-segmented-item`, '待办');
  await poll(() => evaluate(`document.querySelectorAll('${body} tr[data-row-key]').length===20`), 'switch back from empty list');
  await scroll();
  assert.equal(fixture.writes.length, 0, 'scrolling and navigation never mutate workflows');
  console.log('PASS: management/matters/inbox wheel scrolling, last row, fixed headers/actions, server pagination, cached resize, fullscreen, loading, error/retry and empty view');
}
