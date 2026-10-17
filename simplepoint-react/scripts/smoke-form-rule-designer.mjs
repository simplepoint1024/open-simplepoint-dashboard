import assert from 'node:assert/strict';

export async function smokeFormRuleDesigner({action, clickText, clickReady, fill, poll, evaluate, save, draft, textField, addressField, countField}) {
  const root = '.nb-tab-panel-active', area = root + ' .sp-form-rule-editor';
  const modal = '.sp-form-rule-modal:not([style*="display: none"]) .ant-modal';
  const tree = modal + ' .sp-form-rule-condition';
  const select = async (selector, text) => {
    await poll(() => evaluate(`!!document.querySelector('${selector}')`), 'rule select ready');
    await evaluate(`document.querySelector('${selector}').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
    const listId = await evaluate(`document.querySelector('${selector}').getAttribute('aria-controls')`);
    await clickText(`.ant-select-dropdown:not(.ant-select-dropdown-hidden):has([id="${listId}"]) .ant-select-item-option`, text);
  };
  const leafSelect = async (index, part, text) => {
    const id = await evaluate(`document.querySelectorAll('${tree} [data-condition-field]')[${index}].querySelectorAll('.ant-select input')[${part}].id`);
    await select('#' + id, text);
  };
  const groupSelect = async text => {
    const id = await evaluate(`document.querySelector('${tree} [data-condition-group] .ant-select input').id`);
    await select('#' + id, text);
  };
  let targetLabel, targetId, sceneId;
  for (const scene of draft().document.scenes) {
    const visit = (node, parent) => {
      const f = draft().document.fields.find(field => field.fieldId === node.fieldId);
      const label = `${parent} / ${node.label || f?.label || node.componentType}`;
      if (node.fieldId === addressField.fieldId) {targetLabel = label; targetId = node.componentId; sceneId = scene.sceneId;}
      node.children.forEach(child => visit(child, label));
    }; visit(scene.root, scene.name);
  }
  const apply = async () => {
    await clickText(modal + ' .ant-modal-footer button', '应用修改');
    await poll(() => evaluate(`!document.querySelector('${modal}')`), 'rule applied'); await save();
  };
  await clickText(root + ' .sp-form-designer .ant-tabs-tab', '交互规则');
  await clickText(area + ' button', '新增规则');
  await select(modal + ' #form_rule_effect', '条件成立时隐藏，否则显示');
  await select(modal + ' #form_rule_targetComponentId', targetLabel);
  await leafSelect(0, 0, `${textField.label} (${textField.storageCode})`);
  await leafSelect(0, 1, '等于');
  await clickText(modal + ' .ant-modal-footer button', '应用修改');
  await poll(() => evaluate(`document.querySelector('${modal} .ant-alert-error')?.textContent.includes('条件值')`), 'incomplete rule is not applied');
  assert.equal(draft().document.rules.length, 0);
  await fill(`${tree} [data-condition-field="${textField.fieldId}"] textarea`, '隐藏地址');
  await clickText(tree + ' button', '添加条件');
  await leafSelect(1, 0, `${countField.label} (${countField.storageCode})`); await leafSelect(1, 1, '大于等于');
  await fill(`${tree} [data-condition-field="${countField.fieldId}"] input.ant-input-number-input`, '10.000000000000000001');
  await apply();
  const first = draft().document.rules[0];
  assert.equal(first.effect, 'HIDE'); assert.equal(first.targetComponentId, targetId);
  assert.equal(first.condition.children[1].values[0].value, '10.000000000000000001');
  await clickText(`${area} [data-row-key="${first.ruleId}"] button`, '编辑规则');
  await groupSelect('不满足'); await apply();
  assert.equal(draft().document.rules[0].condition.operator, 'NOT');
  assert.equal(draft().document.rules[0].condition.children[0].children.length, 2, 'negating preserves both conditions');
  await clickText(`${area} [data-row-key="${first.ruleId}"] button`, '编辑规则');
  await groupSelect('满足全部'); await apply();
  await clickText(area + ' button', '新增规则');
  await select(modal + ' #form_rule_targetComponentId', targetLabel);
  await leafSelect(0, 0, `${textField.label} (${textField.storageCode})`); await leafSelect(0, 1, '非空');
  await apply();
  const second = draft().document.rules[1];
  await clickText(`${area} [data-row-key="${first.ruleId}"] button`, '下移'); await save();
  assert.deepEqual(draft().document.rules.map(rule => rule.ruleId), [second.ruleId, first.ruleId]);
  await clickText(`${area} [data-row-key="${second.ruleId}"] button`, '移除规则');
  await clickText(modal + ' .ant-modal-footer button', '移除规则');
  await poll(() => evaluate(`!document.querySelector('${modal}')`), 'rule removed'); await save();
  assert.equal(draft().document.rules.length, 1);
  await action('撤销'); await save();
  assert.deepEqual(draft().document.rules.map(rule => rule.ruleId), [second.ruleId, first.ruleId]);
  await clickReady(root + ' .sp-form-designer-toolbar button[aria-label="预览"]');
  const preview = '.sp-form-designer-preview:not([style*="display: none"]) .ant-modal';
  const dialog = '.sp-form-runtime-dialog:not([style*="display: none"]) .ant-modal';
  await poll(() => evaluate(`!!document.querySelector('${preview} #form_main_${textField.fieldId}')`), 'preview input is mounted');
  await fill(`${preview} #form_main_${textField.fieldId}`, '隐藏地址');
  // Open the drawer once so its default value joins the shared preview record.
  await clickText(preview + ' button', '打开弹窗'); await clickText(dialog + ' button', '打开抽屉');
  await clickText('.sp-form-runtime-drawer.ant-drawer-open button', '确认');
  await poll(() => evaluate(`!document.querySelector('.sp-form-runtime-drawer.ant-drawer-open')`), 'exact decimal default can be applied without editing');
  await poll(() => evaluate(`!document.querySelector('${dialog} #form_${sceneId}_${addressField.fieldId}')`), 'cross-scene values apply the last visibility rule');
  await clickText(dialog + ' button', '确认');
  await fill(`${preview} #form_main_${textField.fieldId}`, '普通客户');
  await clickText(preview + ' button', '打开弹窗');
  await poll(() => evaluate(`!!document.querySelector('${dialog} #form_${sceneId}_${addressField.fieldId}')`), 'false condition restores the field');
  await clickText(dialog + ' button', '取消'); await clickReady(preview + ' .ant-modal-close');
  await clickText(root + ' .sp-form-designer .ant-tabs-tab', '表单设计');
  console.log('PASS: platform condition tree, exact rule literals, compound NOT preservation, precedence, remove/undo and cross-scene preview');
}
