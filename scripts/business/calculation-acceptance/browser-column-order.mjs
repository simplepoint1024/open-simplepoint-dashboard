import assert from 'node:assert/strict';

/** Persist pointer and keyboard ordering through the real designer, then restore its exact draft. */
export async function verifyActualColumnOrder({evaluate, send, poll, click, call, appId, spec, state, save}) {
  const base = `/app/applications/${appId}/forms/${spec.formId}`;
  const original = await call(base + '/draft');
  state.originalDocument ??= structuredClone(original.document); await save();
  assert.deepEqual(original.document, state.originalDocument, 'Restore an interrupted verification before rerunning');
  const root = '.nb-tab-panel-active', drawer = '.sp-form-properties-drawer.ant-drawer-open';
  await evaluate("location.hash='/app/workbench'");
  await click(root + ` [data-row-key="${appId}"] button`, '查看');
  await click(`.app-application-details.ant-drawer-open [data-row-key="${spec.pageId}"] button`, '设计表单');
  await click(root + ' .sp-form-designer-toolbar button', '表单属性');
  await click(drawer + ' .ant-tabs-tab', '列表默认顺序');
  const rows = drawer + ' [role="tabpanel"][aria-hidden="false"] tr[data-row-key]';
  await poll(() => evaluate(`document.querySelectorAll('${rows}').length>2`), 'list default field rows');
  const order = () => evaluate(`[...document.querySelectorAll('${rows}')].map(e=>e.dataset.rowKey)`);
  const before = await order();
  await poll(() => evaluate(`!document.getAnimations().some(a=>a.playState==='running'&&a.effect?.getComputedTiming().iterations!==Infinity&&a.effect?.target?.closest?.('${drawer}'))`), 'column drawer transition settled');
  state.before = before; await save();
  const points = await evaluate(`(()=>{const rows=[...document.querySelectorAll('${rows}')];return [rows[0],rows[2]].map(row=>{const r=row.querySelector('[aria-label="拖拽调整列表列顺序"]').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})})()`);
  await send('Input.dispatchMouseEvent', {type: 'mouseMoved', ...points[0]});
  await send('Input.dispatchMouseEvent', {type: 'mousePressed', button: 'left', buttons: 1, clickCount: 1, ...points[0]});
  for (let step = 1; step <= 12; step++) {
    await send('Input.dispatchMouseEvent', {type: 'mouseMoved', button: 'left', buttons: 1,
      x: points[0].x, y: points[0].y + (points[1].y - points[0].y) * step / 12});
    await evaluate('new Promise(resolve=>requestAnimationFrame(resolve))');
  }
  await send('Input.dispatchMouseEvent', {type: 'mouseReleased', button: 'left', buttons: 0, clickCount: 1, ...points[1]});
  const pointer = [before[1], before[2], before[0], ...before.slice(3)];
  await poll(async () => JSON.stringify(await order()) === JSON.stringify(pointer), 'pointer list reorder').catch(async error => {
    state.pointerObserved = await order(); await save(); throw error;
  });
  await evaluate(`document.querySelector('${rows} [aria-label="拖拽调整列表列顺序"]').focus()`);
  for (const key of [{key: ' ', code: 'Space', windowsVirtualKeyCode: 32}, {key: 'ArrowDown', code: 'ArrowDown', windowsVirtualKeyCode: 40},
    {key: ' ', code: 'Space', windowsVirtualKeyCode: 32}]) {
    await send('Input.dispatchKeyEvent', {type: 'keyDown', ...key}); await send('Input.dispatchKeyEvent', {type: 'keyUp', ...key});
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
  }
  const keyboard = [pointer[1], pointer[0], ...pointer.slice(2)];
  await poll(async () => JSON.stringify(await order()) === JSON.stringify(keyboard), 'keyboard list reorder');
  await evaluate(`document.querySelector('${drawer} .ant-drawer-close').click()`);
  const action = async name => {
    await click(root + ' .sp-form-designer-toolbar button', '操作');
    await click('.ant-dropdown:not(.ant-dropdown-hidden) .ant-dropdown-menu-item', name);
  };
  const persist = async () => {
    await action('保存');
    await poll(() => evaluate(`document.querySelector('${root} .app-form-editor-identity .ant-tag')?.textContent==='已保存'`), 'persist ordered fields');
    return call(base + '/draft');
  };
  const reordered = await persist();
  assert.deepEqual(reordered.document.presentation.listFieldOrder, keyboard);
  assert.deepEqual(reordered.document.scenes, original.document.scenes);
  await action('撤销'); await action('撤销');
  const restored = await persist(); assert.deepEqual(restored.document, original.document);
  Object.assign(state, {passed: true, originalRevision: original.revision, savedRevision: reordered.revision,
    restoredRevision: restored.revision, before, pointer, keyboard, canvasUnchanged: true, completedAt: new Date().toISOString()});
  await save(); console.log('PASS real pointer and keyboard default column ordering, persisted result and exact draft restoration');
}
