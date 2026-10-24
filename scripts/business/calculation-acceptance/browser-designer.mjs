import assert from 'node:assert/strict';

/** Edit an isolated real form draft, use the independent editor and trusted preview, then restore. */
export async function verifyActualCalculationDesigner({evaluate, poll, click, fill, screenshot, call, spec, appId, state, save}) {
  const base = `/app/applications/${appId}/forms/${spec.formId}`;
  const original = await call(base + '/draft');
  const field = original.document.fields.find(field => field.fieldId === 'total'); assert(field?.calculation);
  state.originalExpression ??= field.calculation.expression; state.originalStorageCode ??= field.storageCode; await save();
  const expectedDocument = structuredClone(original.document);
  expectedDocument.fields.find(field => field.fieldId === 'total').calculation.expression = state.originalExpression;
  const root = '.nb-tab-panel-active', drawer = '.app-application-details.ant-drawer-open';
  await evaluate("location.hash='/app/workbench'");
  await click(root + ` [data-row-key="${appId}"] button`, '查看');
  await click(drawer + ` [data-row-key="${spec.pageId}"] button`, '设计表单');
  await poll(() => evaluate(`!!document.querySelector('${root} .sp-form-designer-toolbar')`), 'actual published form designer');
  const dialog = '.sp-form-data-modal:not([style*="display: none"]) .ant-modal';
  const edit = async () => {
    await click(root + ' .sp-form-designer-tabs .ant-tabs-tab', '数据设计');
    await click(root + ' .sp-form-data-editor .ant-tabs-tab', '字段');
    await click(root + ' .sp-form-data-editor [data-row-key="total"] button', '编辑字段');
    await click(dialog + ' .ant-tabs-tab', '计算公式');
    await poll(() => evaluate(`!!document.querySelector('${dialog} .sp-calculator')`), 'independent editor hosted by actual field');
  };
  const persist = async () => {
    if (!await evaluate(`document.querySelector('${root} .app-form-editor-identity .ant-tag')?.textContent==='已保存'`)) {
      await click(root + ' .sp-form-designer-toolbar button', '操作');
      await click('.ant-dropdown:not(.ant-dropdown-hidden) .ant-dropdown-menu-item', '保存');
    }
    await poll(() => evaluate(`document.querySelector('${root} .app-form-editor-identity .ant-tag')?.textContent==='已保存'`), 'real draft persisted');
  };
  await edit();
  await click(dialog + ' .sp-calculator [role="tab"]', 'ƒ表达式');
  await fill(dialog + ' textarea.sp-calculator-expression', '=MISSING_FUNCTION(1)');
  await click(dialog + ' .ant-modal-footer button', '应用修改');
  await poll(() => evaluate(`!!document.querySelector('${dialog} .ant-alert-error')`), 'unknown function rejected before save');
  const expression = '=ROUND([quantity]*[rate],2)+2';
  await fill(dialog + ' textarea.sp-calculator-expression', expression);
  await click(dialog + ' .sp-calculator button', '撤销');
  assert.equal(await evaluate(`document.querySelector('${dialog} textarea.sp-calculator-expression').value`), '=MISSING_FUNCTION(1)');
  await click(dialog + ' .sp-calculator button', '重做');
  assert.equal(await evaluate(`document.querySelector('${dialog} textarea.sp-calculator-expression').value`), expression);
  state.hostedUndoRedo = {invalidDraftPreserved: true, validExpressionRestored: true}; await save();
  await click(dialog + ' .sp-calculator [role="tab"]', '▦可视化');
  await poll(() => evaluate(`document.querySelector('${dialog} .sp-calculator-block')?.textContent.includes('ROUND')`), 'expression reversed to blocks');
  await click(dialog + ' .ant-modal-footer button', '应用修改');
  await poll(() => evaluate(`!document.querySelector('${dialog}')`), 'formula applied'); await persist();
  const saved = await call(base + '/draft'), stored = saved.document.fields.find(field => field.fieldId === 'total');
  assert.equal(stored.calculation.expression, expression); assert.equal(stored.storageCode, state.originalStorageCode);
  state.savedRevision = saved.revision; await save();
  await click(root + ' .sp-form-designer-tabs .ant-tabs-tab', '表单设计');
  await click(root + ' .sp-form-designer-toolbar button', '预览');
  const preview = '.sp-form-designer-preview:not([style*="display: none"]) .ant-modal';
  await fill(preview + ' #form_main_quantity', '4'); await fill(preview + ' #form_main_rate', '3');
  await poll(() => evaluate(`document.querySelector('${preview} #form_main_total')?.textContent==='14.00'`), 'draft formula evaluated by real backend');
  assert.equal(await evaluate(`document.querySelector('${preview} #form_main_normalized_total')?.textContent`), '24.00');
  assert.equal(await evaluate(`!!document.querySelector('${preview} input#form_main_total')`), false);
  await screenshot('calculation-real-designer-preview');
  await evaluate(`document.querySelector('${preview} .ant-modal-close').click()`);
  await edit(); await click(dialog + ' .sp-calculator [role="tab"]', 'ƒ表达式');
  assert.equal(await evaluate(`document.querySelector('${dialog} textarea.sp-calculator-expression').value`), expression);
  await fill(dialog + ' textarea.sp-calculator-expression', state.originalExpression);
  await click(dialog + ' .ant-modal-footer button', '应用修改');
  await poll(() => evaluate(`!document.querySelector('${dialog}')`), 'original formula restored'); await persist();
  const restored = await call(base + '/draft');
  assert.deepEqual(restored.document, expectedDocument);
  state.passed = true; state.restoredRevision = restored.revision; state.preview = {total: '14', normalized_total: '24'};
  state.finishedAt = new Date().toISOString(); await save();
}
