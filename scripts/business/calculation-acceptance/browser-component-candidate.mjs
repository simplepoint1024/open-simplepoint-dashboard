import assert from 'node:assert/strict';

/** Build the remaining registered controls through the real palette; retain a publishable copy. */
export async function buildActualComponentCandidate({evaluate, poll, click, fill, screenshot, call,
  spec, appId, seedDocument, expectedTypes, state, save}) {
  if (state.passed) return;
  const base = `/app/applications/${appId}/forms/${spec.formId}`;
  let draft = await call(base + '/draft');
  state.originalDocument ??= structuredClone(draft.document); await save();
  if (!state.seedRevision) {
    assert.deepEqual(draft.document, state.originalDocument);
    draft = await call(base + '/draft', 'PUT', {expectedRevision: draft.revision, document: seedDocument});
    state.seedRevision = draft.revision; await save();
  }
  assert.deepEqual(draft.document, seedDocument, 'Resume only an unchanged isolated candidate seed');
  const root = '.nb-tab-panel-active', drawer = '.app-application-details.ant-drawer-open';
  await evaluate("location.hash='/app/workbench'");
  await click(root + ` [data-row-key="${appId}"] button`, '查看');
  await click(drawer + ` [data-row-key="${spec.pageId}"] button`, '设计表单');
  await poll(() => evaluate(`!!document.querySelector('${root} .sp-form-designer-toolbar')`), 'candidate designer');
  const clickReady = async selector => {
    await poll(() => evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});return !!e?.getClientRects().length&&!e.disabled})()`), 'ready ' + selector);
    await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  };
  const selectRoot = () => clickReady(root + ' .sp-form-canvas-node.is-root > .sp-form-canvas-title');
  const add = async (type, label, atRoot = true) => {
    if (atRoot) await selectRoot();
    await clickReady(root + ` [data-palette-type="${type}"] button`);
    await poll(() => evaluate(`document.querySelector('${root} .sp-form-canvas-node.is-selected')?.dataset.componentType===${JSON.stringify(type)}`), 'inserted ' + type);
    if (label) await fill(root + ' #design_property_' + (type.startsWith('field.') ? 'fieldLabel' : 'label'), label);
  };
  for (const [type, label] of [
    ['field.textarea', '交付说明'], ['field.json', '扩展资料'], ['field.select', '交付等级'],
    ['field.date', '计划交付日期'], ['field.datetime', '验收开始时间'], ['field.user', '项目负责人'],
    ['field.role', '执行角色'], ['field.organization', '负责部门'], ['field.tenant', '所属工作空间'],
    ['field.attachment', '验收附件'], ['display.text', '组件完整性验收'],
  ]) await add(type, label);
  await add('layout.grid', '补充资料');
  await add('layout.group', '实施说明', false);
  await add('field.text', '补充说明', false);
  await add('layout.matrix', '交付矩阵');
  await clickReady(root + ' [data-component-type="layout.cell"] > .sp-form-canvas-title');
  await add('field.text', '网格说明', false);
  await add('data.detail', '交付检查项');
  await add('action.submit', '提交'); await add('action.reset', '重置');
  for (const [type, name, fieldType, fieldLabel] of [
    ['action.open-dialog', '打开弹窗', 'field.text', '弹窗补充说明'],
    ['action.open-drawer', '打开抽屉', 'field.textarea', '抽屉复核意见'],
  ]) {
    await add(type);
    await click(root + ' .sp-form-designer-scenes .ant-tabs-tab', name);
    await add(fieldType, fieldLabel);
    await add('action.apply', '确认'); await add('action.close', '取消');
    await click(root + ' .sp-form-designer-scenes .ant-tabs-tab', '发布恢复验收');
  }
  await clickReady(root + ' [data-component-type="display.image"] > .sp-form-canvas-title');
  await click(root + ' .sp-form-property-tabs .ant-tabs-tab', '组件配置');
  await fill(root + ' #standard_property_src', '/svg.svg');
  await clickReady(root + ' [data-component-type="display.progress"] > .sp-form-canvas-title');
  await click(root + ' .sp-form-property-tabs .ant-tabs-tab', '组件配置');
  await fill(root + ' #standard_property_percent', '75');
  await clickReady(root + ' button[aria-label="表单属性"]');
  const properties = '.sp-form-properties-drawer.ant-drawer-open';
  await click(properties + ' .ant-tabs-tab', '字段属性');
  await clickReady(properties + ' input[aria-label="主表 评分 编辑 写"]');
  await clickReady(properties + ' .ant-drawer-close');
  await clickReady(root + ' button[aria-label="操作"]');
  await click('.ant-dropdown:not(.ant-dropdown-hidden) .ant-dropdown-menu-item', '保存');
  await poll(() => evaluate(`document.querySelector('${root} .app-form-editor-identity .ant-tag')?.textContent==='已保存'`), 'complete candidate saved');
  draft = await call(base + '/draft');
  const types = new Set();
  const walk = node => {types.add(node.componentType); node.children.forEach(walk);};
  draft.document.scenes.forEach(scene => walk(scene.root));
  assert.deepEqual([...types].sort(), [...expectedTypes].sort());
  assert.equal(draft.document.fields.find(field => field.label === '评分').access.edit, 'WRITE');
  state.candidate = draft.document; state.candidateRevision = draft.revision; state.types = [...types].sort(); await save();
  await screenshot('calculation-all-component-candidate');
  const restored = await call(base + '/draft', 'PUT', {expectedRevision: draft.revision, document: state.originalDocument});
  assert.deepEqual((await call(base + '/draft')).document, state.originalDocument);
  state.restoredRevision = restored.revision; state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
