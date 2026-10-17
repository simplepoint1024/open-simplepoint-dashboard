import assert from 'node:assert/strict';

/** Exercise the shared data editor through actual platform controls and persisted HTTP drafts. */
export async function smokeFormDataDesigner({action, clickText, clickReady, clickMenu, fill, poll, evaluate, save, draft, textField, countField}) {
  const root = '.nb-tab-panel-active', area = root + ' .sp-form-data-editor';
  const dialog = '.sp-form-data-modal:not([style*="display: none"]) .ant-modal';
  const field = name => `${dialog} #data_field_${name}`;
  const select = async (selector, text) => {
    await poll(() => evaluate(`!!document.querySelector('${selector}')`), 'data editor select ready');
    await evaluate(`document.querySelector('${selector}').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    const listId = await evaluate(`document.querySelector('${selector}').getAttribute('aria-controls')`);
    assert.ok(listId, 'platform select identifies its own popup');
    await clickText(`.ant-select-dropdown:not(.ant-select-dropdown-hidden):has([id="${listId}"]) .ant-select-item-option`, text);
  };
  const edit = async id => {
    await clickText(`${area} [data-row-key="${id}"] button`, '编辑字段');
    await poll(() => evaluate(`!!document.querySelector('${field('label')}')`), 'field editor open');
  };
  const closed = () => poll(() => evaluate(`!document.querySelector('${dialog}')`), 'data edit applied and closed');
  await clickText(root + ' .sp-form-designer > .sp-page-section .ant-tabs-tab, ' + root + ' .sp-form-designer .ant-tabs-tab', '数据设计');
  await edit(countField.fieldId);
  const storageCode = await evaluate(`document.querySelector('${field('storageCode')}').value`);
  assert.equal(await evaluate(`document.querySelector('${field('storageCode')}').readOnly || document.querySelector('${field('storageCode')}').disabled`), true);
  await select(field('type'), 'INT64'); await select(field('defaultMode'), '指定值');
  await fill(field('defaultText'), '9223372036854775808');
  await clickText(dialog + ' .ant-modal-footer button', '应用修改');
  await poll(() => evaluate(`document.querySelector('${dialog} .ant-alert-error')?.textContent.includes('默认值')`), 'integer overflow rejected locally');
  assert.equal(draft().document.fields.find(item => item.fieldId === countField.fieldId).type, 'DECIMAL');
  await fill(field('defaultText'), '9223372036854775807');
  await clickText(dialog + ' .ant-modal-footer button', '应用修改'); await closed(); await save();
  let saved = draft().document.fields.find(item => item.fieldId === countField.fieldId);
  assert.equal(saved.type, 'INT64'); assert.equal(saved.defaultValue.value, '9223372036854775807');
  assert.equal(saved.storageCode, storageCode); assert.equal(saved.precision, null);
  await edit(countField.fieldId); await select(field('type'), 'DECIMAL');
  await fill(field('precision'), '38'); await fill(field('scale'), '18');
  await fill(field('defaultText'), '12345678901234567890.123456789012345678');
  await clickReady(field('storageRequired'));
  await clickText(dialog + ' .ant-modal-footer button', '应用修改'); await closed(); await save();
  saved = draft().document.fields.find(item => item.fieldId === countField.fieldId);
  assert.equal(saved.defaultValue.value, '12345678901234567890.123456789012345678');
  assert.equal(saved.storageRequired, true); assert.equal(saved.writePolicy, 'CREATE_ONLY');

  await edit(textField.fieldId); await fill(field('label'), '尚未应用的字段');
  await clickMenu('首页');
  await poll(() => evaluate(`!document.querySelector('${dialog}')`), 'retained editor hides its modal after the close transition');
  await clickMenu('应用工作台测试');
  await poll(() => evaluate(`document.querySelector('${field('label')}')?.value==='尚未应用的字段'`), 'modal edit survives background retention');
  await clickText(dialog + ' .ant-modal-footer button', '取消');
  await clickText('.sp-form-data-discard .ant-modal-footer button', '放弃修改'); await closed();
  assert.equal(draft().document.fields.find(item => item.fieldId === textField.fieldId).label, textField.label);
  await clickText(area + ' button', '新增字段');
  await fill(field('label'), '独立数据字段');
  await clickText(dialog + ' .ant-modal-footer button', '添加'); await closed(); await save();
  const independent = draft().document.fields.find(item => item.label === '独立数据字段');
  assert.ok(independent?.fieldId); assert.equal(independent.storageLabel, independent.label);
  await action('撤销'); await save();
  assert.equal(draft().document.fields.length, 3, 'data commands participate in shared undo history');

  await clickText(area + ' .ant-tabs-tab', '索引'); await clickText(area + ' button', '新增索引');
  // SForm owns array add/reorder/remove interaction, including accessibility labels.
  const items = `${dialog} #data_index_fields`;
  if (!await evaluate(`!!document.querySelector('${dialog} #data_index_fields_0_fieldId')`)) await clickReady(items + ' .rjsf-array-item-add');
  await select(dialog + ' #data_index_fields_0_fieldId', `${textField.label} (${textField.storageCode})`);
  await clickReady(items + ' .rjsf-array-item-add');
  await select(dialog + ' #data_index_fields_1_fieldId', `${textField.label} (${textField.storageCode})`);
  await clickText(dialog + ' .ant-modal-footer button', '添加');
  await poll(() => evaluate(`document.querySelector('${dialog} .ant-alert-error')?.textContent.includes('不重复')`), 'duplicate index fields rejected');
  await select(dialog + ' #data_index_fields_1_fieldId', `${countField.label} (${countField.storageCode})`);
  await clickReady(dialog + ' #data_index_unique');
  await clickText(dialog + ' .ant-modal-footer button', '添加'); await closed(); await save();
  const index = draft().document.indexes[0];
  assert.equal(index.unique, true); assert.deepEqual(index.fieldIds, [textField.fieldId, countField.fieldId]);
  await clickText(`${area} [data-row-key="${index.indexId}"] button`, '编辑索引');
  const up = await evaluate(`(() => {const button=[...document.querySelectorAll('${items} button')].find(b=>b.querySelector('[aria-label="arrow-up"]')&&!b.disabled);return button?.id;})()`);
  assert.ok(up, 'platform array exposes its reorder button'); await clickReady(dialog + ' #' + up);
  await clickText(dialog + ' .ant-modal-footer button', '应用修改'); await closed(); await save();
  assert.deepEqual(draft().document.indexes[0].fieldIds, [countField.fieldId, textField.fieldId]);
  assert.equal(draft().document.indexes[0].storageCode, index.storageCode);
  await clickText(`${area} [data-row-key="${index.indexId}"] button`, '移除索引');
  await clickText(dialog + ' .ant-modal-footer button', '移除索引'); await closed(); await save();
  assert.equal(draft().document.indexes.length, 0);
  await action('撤销'); await save();
  assert.equal(draft().document.indexes[0].indexId, index.indexId);
  await clickText(root + ' .sp-form-designer .ant-tabs-tab', '表单设计');
  console.log('PASS: typed data editor, exact defaults, storage constraints, local invalid drafts, modal retention/discard, independent fields and ordered index lifecycle');
}
