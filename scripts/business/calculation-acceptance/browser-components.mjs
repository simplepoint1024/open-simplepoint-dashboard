import assert from 'node:assert/strict';
import {smokeFormInspectors} from '../../../simplepoint-react/scripts/smoke-form-inspectors.mjs';
import {smokeFormStandardComponents} from '../../../simplepoint-react/scripts/smoke-form-standard-components.mjs';

/** Reuse DOM interaction assertions against a real persisted draft, without fixture responses. */
export async function verifyActualComponentDesigner({evaluate, poll, click, fill, send, screenshot, call, spec, appId, state, save}) {
  const base = `/app/applications/${appId}/forms/${spec.formId}`;
  let current = await call(base + '/draft');
  state.originalDocument ??= structuredClone(current.document); await save();
  assert.deepEqual(current.document, state.originalDocument, 'Begin from the last restored draft');
  const root = '.nb-tab-panel-active', drawer = '.app-application-details.ant-drawer-open';
  await evaluate("location.hash='/app/workbench'");
  await click(root + ` [data-row-key="${appId}"] button`, '查看');
  await click(drawer + ` [data-row-key="${spec.pageId}"] button`, '设计表单');
  await poll(() => evaluate(`!!document.querySelector('${root} .sp-form-designer-toolbar')`), 'real component designer');
  const clickReady = async selector => {
    await poll(() => evaluate(`(()=>{const element=document.querySelector(${JSON.stringify(selector)});return !!element?.getClientRects().length&&!element.disabled})()`), 'ready ' + selector);
    await evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
  };
  const action = async label => {
    await clickReady(root + ' .sp-form-designer-toolbar button[aria-label="操作"]');
    await click('.ant-dropdown:not(.ant-dropdown-hidden) .ant-dropdown-menu-item', label);
  };
  const persist = async () => {
    if (!await evaluate(`document.querySelector('${root} .app-form-editor-identity .ant-tag')?.textContent==='已保存'`)) await action('保存');
    await poll(() => evaluate(`document.querySelector('${root} .app-form-editor-identity .ant-tag')?.textContent==='已保存'`), 'actual component draft saved');
    current = await call(base + '/draft');
    state.savedRevisions ??= []; state.savedRevisions.push(current.revision);
    if (current.document.presentation?.labelWidth === 120) state.configuredDocument = structuredClone(current.document);
    await save();
  };
  const helpers = {action, clickReady, clickText: click, fill, poll, evaluate, send, save: persist, draft: () => current};
  await smokeFormInspectors(helpers);
  state.inspectors = {passed: true, viewport: 1440}; await save();
  await smokeFormStandardComponents(helpers);
  assert.deepEqual((await call(base + '/draft')).document, state.originalDocument);
  state.standardComponents = {passed: true, count: 12, restored: true};
  await screenshot('calculation-real-component-designer');
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
