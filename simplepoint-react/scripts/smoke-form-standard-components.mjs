import assert from 'node:assert/strict';
export async function smokeFormStandardComponents({action, clickReady, clickText, fill, poll, evaluate, send, save, draft}) {
  const root = '.nb-tab-panel-active', drawer = '.sp-form-properties-drawer.ant-drawer-open', preview = '.sp-form-designer-preview .ant-modal';
  await save();
  const before = structuredClone(draft().document);
  let commands = 0;
  const types = ['display.divider', 'layout.tabs', 'field.cascader', 'field.rating', 'field.switch', 'field.time', 'field.transfer',
    'display.image', 'display.alert', 'display.progress', 'display.icon', 'display.markdown'];
  for (const type of types) {
    await clickReady(root + ' .sp-form-canvas-node.is-root > .sp-form-canvas-title');
    await clickReady(root + ` [data-palette-type="${type}"] button`); commands++;
    if (type === 'field.cascader' || type === 'field.transfer') {
      await clickText(root + ' .sp-form-property-tabs .ant-tabs-tab', '组件配置');
      await clickReady(root + ' #standard_property_options .rjsf-array-item-add'); commands++;
      await fill(root + ' #standard_property_options_0_value', 'cn'); commands++;
      await fill(root + ' #standard_property_options_0_label', '中文选项'); commands++;
      if (type === 'field.cascader') {
        await clickReady(root + ' #standard_property_options_0_children .rjsf-array-item-add'); commands++;
        await fill(root + ' #standard_property_options_0_children_0_value', 'jp'); commands++;
        await fill(root + ' #standard_property_options_0_children_0_label', '日本語'); commands++;
      }
    }
  }
  await clickText(root + ' .sp-form-property-tabs .ant-tabs-tab', '组件配置');
  await fill(root + ' #standard_property_text', '## 中文・日本語\n\n**安全 Markdown**\n\n<script>window.__formUnsafe=true</script>'); commands++;
  await clickReady(root + ' .sp-form-designer-toolbar button[aria-label="表单属性"]');
  await poll(() => evaluate(`!!document.querySelector('${drawer} #root_labelWidth')`), 'form properties right drawer ready');
  assert.equal(await evaluate(`[...document.querySelectorAll('${drawer} .ant-tabs-nav .ant-tabs-tab')].every(e=>e.querySelector('.anticon'))`), true);
  await fill(drawer + ' #root_labelWidth', '120'); commands++;
  await clickText(drawer + ' .ant-tabs-tab', '字段属性');
  await clickReady(drawer + ' input[aria-label="主表 评分 编辑 隐藏"]'); commands++;
  assert.equal(await evaluate(`document.querySelector('${drawer} input[aria-label="主表 评分 编辑 写"]').checked`), false);
  await clickText(drawer + ' .ant-tabs-tab', '列表默认顺序');
  await poll(() => evaluate(`document.querySelectorAll('${drawer} [role="tabpanel"][aria-hidden="false"] tr[data-row-key]').length >= 2`), 'default list rows ready').catch(async error => {console.log('DRAWER DOM', await evaluate(`document.querySelector('${drawer}').innerHTML.slice(-24000)`)); throw error;});
  const initialOrder = await evaluate(`[...document.querySelectorAll('${drawer} [role="tabpanel"][aria-hidden="false"] tr[data-row-key]')].map(e=>e.dataset.rowKey)`);
  await evaluate(`document.querySelector('${drawer} [aria-label="拖拽调整列表列顺序"]').focus()`);
  for (const key of [{key:' ',code:'Space',windowsVirtualKeyCode:32},{key:'ArrowDown',code:'ArrowDown',windowsVirtualKeyCode:40},{key:' ',code:'Space',windowsVirtualKeyCode:32}]) {
    await send('Input.dispatchKeyEvent',{type:'keyDown',...key}); await send('Input.dispatchKeyEvent',{type:'keyUp',...key});
    await evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
  }
  await poll(() => evaluate(`document.querySelector('${drawer} [role="tabpanel"][aria-hidden="false"] tr[data-row-key]')?.dataset.rowKey===${JSON.stringify(initialOrder[1])}`), 'keyboard drag reorders default list fields'); commands++;
  await clickReady(drawer + ' .ant-drawer-close'); await save();
  assert.deepEqual(draft().document.presentation.listFieldOrder.slice(0, 2), [initialOrder[1], initialOrder[0]]);
  assert.equal(draft().document.presentation.labelWidth, 120);
  assert.equal(draft().document.fields.find(f=>f.label==='评分').access.edit, 'HIDDEN');
  const sceneBefore = structuredClone(draft().document.scenes);
  await clickReady(root + ' .sp-form-designer-toolbar button[aria-label="预览"]');
  await poll(() => evaluate(`!!document.querySelector('${preview} .sp-form-markdown h2')`), 'Markdown loads as a separate runtime chunk');
  assert.equal(await evaluate(`document.querySelector('${preview} .sp-form-markdown h2').textContent`), '中文・日本語');
  assert.equal(await evaluate(`!!window.__formUnsafe || !!document.querySelector('${preview} .sp-form-markdown script')`), false);
  for (const selector of ['.ant-rate', '.ant-switch', '.ant-picker', '.ant-cascader', '.ant-transfer', '.ant-progress', '.ant-divider', '.ant-tabs']) {
    await poll(() => evaluate(`!!document.querySelector('${preview} ${selector}')`), selector + ' renders').catch(async error => {console.log('PREVIEW', await evaluate(`document.querySelector('${preview}').innerHTML.slice(-24000)`)); throw error;});
  }
  await evaluate(`document.querySelector('${preview} .ant-cascader').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await clickText('.ant-cascader-dropdown .ant-cascader-menu-item', '中文选项');
  await clickText('.ant-cascader-dropdown .ant-cascader-menu-item', '日本語');
  await clickText(preview + ' .ant-transfer-list-content-item', '中文选项');
  await clickReady(preview + ' .ant-transfer-actions button:not([disabled])');
  await poll(() => evaluate(`document.querySelectorAll('${preview} .ant-transfer-section')[1]?.textContent.includes('中文选项')`), 'transfer keeps selected keys');
  await clickReady(preview + ' .ant-switch');
  assert.equal(await evaluate(`document.querySelector('${preview} .ant-switch').getAttribute('aria-checked')`), 'true');
  await clickReady(preview + ' .ant-rate-star:nth-child(4) .ant-rate-star-second');
  assert.equal(await evaluate(`document.querySelectorAll('${preview} .ant-rate-star-full').length`), 4);
  await clickReady(preview + ' .ant-modal-close');
  assert.deepEqual(draft().document.scenes, sceneBefore, 'preview and list ordering preserve canvas');
  for (let i = 0; i < commands; i++) await action('撤销');
  await save();
  assert.deepEqual(draft().document, before, 'component and form property changes undo as complete document commands');
  console.log('PASS: 12 requested components, safe Markdown, runtime inputs, icon property drawer, field operation matrix and keyboard list ordering with complete undo');
}
