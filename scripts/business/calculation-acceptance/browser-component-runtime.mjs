import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';

/** Exercises the real published registry, shared widgets, scene drafts and persisted aggregate. */
export async function verifyActualComponentRuntime({evaluate, poll, click, fill, send, writes, screenshot,
  call, spec, document, uploadPath, state, save}) {
  const root = '.nb-tab-panel-active', drawer = '.sp-simple-table-drawer.ant-drawer-open';
  const field = code => document.fields.find(item => item.storageCode === code);
  const input = (code, scope = drawer, scene = 'main') => scope + ' #form_' + scene + '_' + field(code).fieldId;
  const press = async (key, code, windowsVirtualKeyCode) => {
    for (const type of ['keyDown', 'keyUp']) await send('Input.dispatchKeyEvent', {type, key, code, windowsVirtualKeyCode});
  };
  const readyClick = async selector => {
    await poll(() => evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});return !!e?.getClientRects().length&&!e.disabled})()`), selector);
    await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  };
  const openSelect = async code => {
    const selector = input(code);
    await evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});e.scrollIntoView({block:'center'});e.closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));e.focus()})()`);
  };
  await click('.ant-menu-submenu-title', '计算引擎业务验收');
  await click('.ant-menu-item', '组件交付验收');
  if (await evaluate(`!!document.querySelector('${root} .sp-card-view')`)) await click(root + ' button', '切换为列表');
  if (!state.recordId) {
    await click(root + ' button', '新增');
    await fill(input('subject'), '应当重置的输入');
    await click(drawer + ' button', '重置');
    await poll(() => evaluate(`document.querySelector(${JSON.stringify(input('subject'))})?.value===''`), 'reset clears scene');
    state.subject ??= '组件真实交付・日本語😀-' + randomUUID().slice(0, 8); await save();
    for (const [code, value] of Object.entries({subject: state.subject, quantity: '3', rate: '4', field_6: '多行说明\n日本語と中文 😀',
      field_7: '{"project":"组件验收","items":[1,true,"日本語😀"]}', field_16: '分组字段', field_17: '网格字段'})) await fill(input(code), value);
    await evaluate(`document.querySelector('${drawer} .ant-cascader').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    await click('.ant-cascader-dropdown .ant-cascader-menu-item', '中文选项');
    await click('.ant-cascader-dropdown .ant-cascader-menu-item', '日本語');
    await readyClick(drawer + ' .ant-rate-star:nth-child(4) .ant-rate-star-second');
    await readyClick(drawer + ' .ant-switch');
    await click(drawer + ' .ant-transfer-list-content-item', '中文选项');
    await readyClick(drawer + ' .ant-transfer-actions button:not([disabled])');
    await readyClick(input('field_4'));
    for (const [index, number] of [[1, '09'], [2, '30'], [3, '15']]) {
      await click(`.ant-picker-dropdown:not(.ant-picker-dropdown-hidden) .ant-picker-time-panel-column:nth-child(${index}) .ant-picker-time-panel-cell-inner`, number);
    }
    await click('.ant-picker-dropdown:not(.ant-picker-dropdown-hidden) button', '确定');
    for (const [code, value] of [['field_9', '2026-10-19'], ['field_10', '2026-10-19 09:30:15']]) {
      await readyClick(input(code)); await fill(input(code), value); await press('Enter', 'Enter', 13);
      await evaluate(`document.querySelector(${JSON.stringify(input('subject'))}).focus()`);
    }
    await openSelect('field_8');
    await click('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option', '选项');
    for (const code of ['field_11', 'field_12', 'field_13', 'field_14']) {
      await openSelect(code);
      const options = `(()=>{const input=document.querySelector(${JSON.stringify(input(code))});const popup=${code === 'field_13' ? "[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden)')].find(e=>e.querySelector('.ant-select-tree'))" : "document.getElementById(input.getAttribute('aria-controls')||input.getAttribute('aria-owns'))?.closest('.ant-select-dropdown')"};return [...(popup?.querySelectorAll('.ant-select-item-option:not(.ant-select-item-option-disabled),.ant-select-tree-node-content-wrapper')??[])].filter(e=>e.getClientRects().length&&e.textContent.trim())})()`;
      await poll(() => evaluate(options + '.length>0'), 'authorized entity options ' + code);
      const option = await evaluate(`(()=>{const e=(${options})[0];const text=e.textContent;e.click();return text})()`);
      await poll(() => evaluate(`document.querySelector(${JSON.stringify(input(code))}).getAttribute('aria-expanded')==='false'`), 'entity option accepted ' + code);
      state.entityLabels ??= {}; state.entityLabels[code] = option; await save();
    }
    await send('DOM.enable');
    const dom = await send('DOM.getDocument');
    const file = await send('DOM.querySelector', {nodeId: dom.root.nodeId, selector: input('field_15')});
    await send('DOM.setFileInputFiles', {nodeId: file.nodeId, files: [uploadPath]});
    await poll(() => evaluate(`document.querySelector('${drawer}')?.textContent.includes('component-acceptance.txt')`), 'actual attachment upload');
    await click(drawer + ' .ant-tabs-tab', '标签页2');
    await click(drawer + ' .ant-tabs-tab', '标签页1');
    for (const [type, code, button] of [['DIALOG', 'field_18', '打开弹窗'], ['DRAWER', 'field_19', '打开抽屉']]) {
      const scene = document.scenes.find(item => item.type === type);
      const scope = type === 'DIALOG' ? '.sp-form-runtime-dialog .ant-modal' : '.sp-form-runtime-drawer.ant-drawer-open';
      await click(drawer + ' button', button);
      await fill(input(code, scope, scene.sceneId), '放弃的修改');
      await click(scope + ' button', '取消');
      await click('.sp-form-runtime-discard button', '放弃修改');
      await click(drawer + ' button', button);
      assert.equal(await evaluate(`document.querySelector(${JSON.stringify(input(code, scope, scene.sceneId))}).value`), '');
      await fill(input(code, scope, scene.sceneId), type + ' 中文・日本語😀');
      await click(scope + ' button', '确认');
      await poll(() => evaluate(`!document.querySelector('${scope}')||!document.querySelector('${scope}').getClientRects().length`), 'scene applied');
    }
    await click(drawer + ' button', '添加明细');
    await poll(() => evaluate(`!!document.querySelector('${drawer} .sp-form-detail input:not([type="checkbox"])')`), 'detail inline field');
    await fill(drawer + ' .sp-form-detail input:not([type="checkbox"])', '明细初稿');
    assert.equal(await evaluate(`!!window.__formUnsafe || !!document.querySelector('${drawer} .sp-form-markdown script')`), false);
    await poll(() => evaluate(`!document.querySelector('${drawer} .sp-form-calculation-status')`), 'trusted preview settled');
    await screenshot('calculation-component-complete-create');
    await click(drawer + ' button', '保存草稿');
    await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'component record committed');
    const page = await call(spec.runtime + '/records/query', 'POST', {page: 0, size: 20});
    const record = page.records.find(item => item.values.subject === state.subject);
    assert(record, 'Created component aggregate is queryable'); state.recordId = record.id; state.created = record; await save();
    const command = writes.find(write => write.method === 'POST' && write.path === spec.runtime + '/records');
    assert(command); assert(!Object.hasOwn(command.body.values, 'total')); assert(!Object.hasOwn(command.body.values, 'normalized_total'));
    state.createCommand = command.body; await save();
  }
  let record = await call(spec.runtime + '/records/' + state.recordId);
  if (!state.edited) {assert.equal(record.values.total, '13'); assert.equal(record.values.normalized_total, '23');}
  state.persisted = record; state.createdPassed = true; await save();
  const value = code => record.values[field(code).fieldId];
  assert.deepEqual(JSON.parse(value('field_1')), ['cn', 'jp']);
  assert.deepEqual(JSON.parse(value('field_5')), ['cn']);
  assert.equal(value('field_4'), '09:30:15'); assert.equal(value('field_9'), '2026-10-19');
  assert.equal(value('field_10'), '2026-10-19T01:30:15Z'); // Local Asia/Shanghai input represents this instant.
  for (const code of ['field_11', 'field_12', 'field_13', 'field_14']) assert(value(code), 'Reference actually persisted ' + code);
  assert.equal(JSON.parse(value('field_15'))[0].name, 'component-acceptance.txt');
  const detail = document.details[0], detailField = detail.fields[0].fieldId;
  const row = root + ` [data-row-key="${state.recordId}"]`;
  await poll(() => evaluate(`!!document.querySelector('${row} input[type="checkbox"]')`), 'created record');
  await readyClick(row + ' input[type="checkbox"]');
  if (!state.edited) {
    await click(root + ' button', '编辑');
    await fill(input('quantity'), '5');
    await fill(input('field_6'), '编辑后保留中文・日本語😀');
    await readyClick(drawer + ' .ant-rate-star:nth-child(5) .ant-rate-star-second');
    await readyClick(drawer + ' .ant-switch');
    await click(drawer + ' .sp-form-detail button', '编辑');
    await fill(drawer + ' .sp-form-detail input:not([type="checkbox"])', '明细修订');
    await click(drawer + ' .sp-form-detail button', '完成');
    await click(drawer + ' button', '添加明细');
    await fill(drawer + ' .sp-form-detail input:not([type="checkbox"])', '临时应删除');
    await click(drawer + ' .sp-form-detail button', '删除');
    await click(drawer + ' button', '添加明细');
    await fill(drawer + ' .sp-form-detail input:not([type="checkbox"])', '后添加应在前');
    assert.equal(await evaluate(`document.querySelector('${drawer} .sp-form-detail tbody tr input:not([type="checkbox"])')?.value`), '后添加应在前');
    await click(drawer + ' .sp-form-detail button', '完成');
    for (const width of [1440, 768, 390]) {
      await send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      await poll(() => evaluate(`(()=>{const e=document.querySelector('${drawer} .ant-drawer-body');return e&&e.scrollWidth<=e.clientWidth+1})()`), 'editable all-component form bounded at ' + width);
      await screenshot('calculation-all-components-edit-' + width);
    }
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    await click(drawer + ' button', '保存草稿');
    await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'edited aggregate saved');
    const updated = await call(spec.runtime + '/records/' + state.recordId);
    assert.equal(updated.version, record.version + 1);
    assert.equal(updated.values.total, '21'); assert.equal(updated.values.normalized_total, '31');
    assert.equal(updated.values[field('field_2').fieldId], 5); assert.equal(updated.values[field('field_3').fieldId], false);
    assert.deepEqual(updated.values[detail.detailId].map(row => row.values[detailField]), ['后添加应在前', '明细修订']);
    for (const code of ['field_1', 'field_4', 'field_5', 'field_7', 'field_8', 'field_9', 'field_10', 'field_11', 'field_12', 'field_13', 'field_14', 'field_15', 'field_16', 'field_17', 'field_18', 'field_19']) {
      assert.deepEqual(updated.values[field(code).fieldId], record.values[field(code).fieldId], 'Unedited control preserved ' + code);
    }
    state.edited = updated; record = updated; await save();
  }
  // Check after resize layout settles; checking synchronously can read the preceding desktop box.
  await click(root + ' button', '编辑');
  await poll(() => evaluate(`!!document.querySelector(${JSON.stringify(input('quantity'))})`), 'edited record reopened');
  for (const width of [1440, 768, 390]) {
    await send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    await evaluate(`document.querySelector('${drawer} .sp-form-transfer').scrollIntoView({block:'center'})`);
    await poll(() => evaluate(`(()=>{const e=document.querySelector('${drawer} .ant-drawer-body');return e&&e.scrollWidth<=e.clientWidth+1})()`), 'settled transfer stays inside editor at ' + width);
    await screenshot('calculation-all-components-transfer-' + width);
    const bounds = await evaluate(`(()=>{const body=document.querySelector('${drawer} .ant-drawer-body'),transfer=document.querySelector('${drawer} .sp-form-transfer');return {bodyWidth:body.clientWidth,bodyScroll:body.scrollWidth,transferWidth:transfer.clientWidth,transferScroll:transfer.scrollWidth}})()`);
    state.layouts ??= []; state.layouts.push({width, ...bounds}); await save();
  }
  await readyClick(drawer + ' .ant-drawer-close');
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
  await click(root + ' button', '详情');
  const view = '.app-record-detail.ant-drawer-open';
  await poll(() => evaluate(`document.querySelector('${view}')?.textContent.includes('明细修订')`), 'read-only detail');
  assert.equal(await evaluate(`[...document.querySelectorAll('${view} input,${view} textarea')].some(e=>!e.disabled&&!e.readOnly)`), false);
  assert.equal(await evaluate(`[...document.querySelectorAll('${view} button')].some(e=>['保存草稿','提交','重置','添加明细','编辑','删除'].includes(e.textContent.replaceAll(' ','')))`), false);
  assert.equal(await evaluate(`!!window.__formUnsafe || !!document.querySelector('${view} .sp-form-markdown script')`), false);
  await poll(() => evaluate(`(()=>{const e=document.querySelector('${view} .ant-image img');return e?.complete&&e.naturalWidth>0})()`), 'safe image loads');
  assert.equal(await evaluate(`document.querySelector('${view} #form_main_total')?.textContent`), '21');
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(input('field_1', view))})?.textContent`), '中文选项 / 日本語');
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(input('field_5', view))})?.textContent`), '中文选项');
  assert.equal(await evaluate(`document.querySelector(${JSON.stringify(input('field_3', view))})?.textContent`), '否');
  for (const width of [1440, 768, 390]) {
    await send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    await poll(() => evaluate(`(()=>{const e=document.querySelector('${view} .ant-drawer-body');return e&&e.scrollWidth<=e.clientWidth+1})()`), 'readonly all-component form bounded at ' + width);
    await screenshot('calculation-all-components-detail-' + width);
  }
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
  for (const [type, button] of [['DIALOG', '打开弹窗'], ['DRAWER', '打开抽屉']]) {
    await click(view + ' button', button);
    const scope = type === 'DIALOG' ? '.sp-form-runtime-dialog .ant-modal' : '.sp-form-runtime-drawer.ant-drawer-open';
    await poll(() => evaluate(`document.querySelector('${scope}')?.textContent.includes('${type} 中文・日本語😀')`), 'readonly secondary scene ' + type);
    assert.equal(await evaluate(`[...document.querySelectorAll('${scope} button')].some(e=>e.textContent==='确认')`), false);
    await click(scope + ' button', '取消');
  }
  await readyClick(view + ' .ant-drawer-close');
  if (!state.submitActionRecordId) {
    // The form's submit action persists SUBMITTED; starting a workflow remains the schema's explicit action.
    await evaluate(`document.querySelector('${root} .sp-table-selected-tag .ant-tag-close-icon')?.click()`);
    await poll(() => evaluate(`!document.querySelector('${root} .sp-table-selected-tag')`), 'standard controller clears selected record');
    await click(root + ' button', '新增');
    state.submitActionSubject ??= '组件提交动作-' + randomUUID().slice(0, 8); await save();
    for (const [code, value] of [['subject', state.submitActionSubject], ['quantity', '2'], ['rate', '6']]) await fill(input(code), value);
    await click(drawer + ' button', '提交');
    await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'declared submit action committed');
    const page = await call(spec.runtime + '/records/query', 'POST', {page: 0, size: 20});
    const submitted = page.records.find(item => item.values.subject === state.submitActionSubject); assert(submitted);
    assert.equal(submitted.state, 'SUBMITTED'); assert.equal(submitted.values.total, '13');
    state.submitActionRecordId = submitted.id; state.submitActionRecord = submitted; await save();
  }
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS published all-component create/edit/detail with exact types, scene state, bounded layouts and permissions');
}
