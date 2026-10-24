import assert from 'node:assert/strict';
import {smokeFormDesignerLayout} from '../../../simplepoint-react/scripts/smoke-form-designer-layout.mjs';

/** Actual designer layout, pointer/context operations and device previews, restoring the draft. */
export async function verifyActualDesignerLayout({evaluate, poll, click, fill, send, screenshot, call, spec, appId, state, save}) {
  const base = `/app/applications/${appId}/forms/${spec.formId}`;
  const original = await call(base + '/draft');
  const root = '.nb-tab-panel-active', drawer = '.app-application-details.ant-drawer-open';
  await evaluate("location.hash='/app/workbench'");
  await click(root + ` [data-row-key="${appId}"] button`, '查看');
  await click(drawer + ` [data-row-key="${spec.pageId}"] button`, '设计表单');
  await poll(() => evaluate(`!!document.querySelector('${root} .sp-form-designer-toolbar')`), 'real layout designer');
  const clickReady = async selector => {
    await poll(() => evaluate(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});return !!e?.getClientRects().length&&!e.disabled})()`), 'ready ' + selector);
    await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  };
  const action = async label => {
    await clickReady(root + ' .sp-form-designer-toolbar button[aria-label="操作"]');
    await click('.ant-dropdown:not(.ant-dropdown-hidden) .ant-dropdown-menu-item', label);
  };
  await smokeFormDesignerLayout({action, clickReady, clickText: click, fill, poll, evaluate, send});
  if (!await evaluate(`document.querySelector('${root} .app-form-editor-identity .ant-tag')?.textContent==='已保存'`)) await action('保存');
  await poll(() => evaluate(`document.querySelector('${root} .app-form-editor-identity .ant-tag')?.textContent==='已保存'`), 'restored layout saved');
  const after = await call(base + '/draft');
  assert.deepEqual(after.document, original.document);
  await screenshot('calculation-real-designer-layout');
  state.passed = true; state.originalRevision = original.revision; state.restoredRevision = after.revision;
  state.designerViewports = [1600, 1024]; state.previewDevices = {PC: 1120, iPad: 768, Phone: 390};
  state.completedAt = new Date().toISOString(); await save();
}
