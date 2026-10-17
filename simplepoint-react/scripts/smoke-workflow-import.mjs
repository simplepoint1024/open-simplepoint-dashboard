import assert from 'node:assert/strict';
import {workflowDesignerDriver} from './workflow-designer-driver.mjs';

export async function smokeWorkflowImport(context) {
  const {clickMenu, clickText, clickReady, evaluate, poll, fixture} = context;
  const {drawer, properties, open, close, select, save} = workflowDesignerDriver(context);
  const notice = `${drawer} .workflow-designer__import-problem`;
  await clickMenu('流程定义测试');
  await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active tr[data-row-key="definition1"]')`), 'definition ready');
  for (const kind of ['reference', 'unsupported', 'invalid', 'missingDiagram']) {
    const original = fixture.importConfiguration(kind);
    const before = fixture.writes.length;
    await open();
    await poll(() => evaluate(`!!document.querySelector('${notice}') && !document.querySelector('${drawer} .djs-palette')
      && document.querySelector('${drawer} .workflow-designer')?.getAttribute('aria-busy')==='false'`), 'unsafe import is a true viewer ' + kind);
    assert.ok(await evaluate(`document.querySelector('${notice}').textContent.includes('不能保存或发布')`));
    const downloadUrl = await poll(() => evaluate(`document.querySelector('${notice} a[download]')?.href`), 'original download ready');
    assert.equal(await evaluate(`fetch(${JSON.stringify(downloadUrl)}).then(r=>r.text())`), original, 'download preserves every original byte as UTF-8 text');
    assert.ok(await evaluate(`[...document.querySelectorAll('${drawer} button')].find(e=>e.textContent.replace(/\\s/g,'')==='发布')?.disabled`));
    assert.ok(await evaluate(`[...document.querySelectorAll('${drawer} button')].find(e=>e.textContent.replace(/\\s/g,'')==='配置变量')?.disabled`));
    if (kind === 'reference') {
      await select('review');
      assert.ok(await evaluate(`[...document.querySelectorAll('${properties} input,${properties} textarea')].every(e=>e.disabled)`));
      await evaluate(`document.querySelector('${notice} details').open=true`);
      assert.ok(await evaluate(`document.querySelector('${notice}').textContent.includes('missingCategory')`));
      await evaluate(`window.__importSvg=document.querySelector('${drawer} .workflow-designer__canvas svg');window.spI18n.setLocale('en-US')`);
      await poll(() => evaluate(`document.querySelector('${notice}')?.textContent.includes('preview-only')`), 'import protection is localized');
      assert.equal(await evaluate(`window.__importSvg===document.querySelector('${drawer} .workflow-designer__canvas svg')`), true);
      await evaluate(`delete window.__importSvg;window.spI18n.setLocale('zh-CN')`);
      await poll(() => evaluate(`document.querySelector('${notice}')?.textContent.includes('不能保存或发布')`), 'Chinese restored');
    }
    if (kind === 'invalid' || kind === 'missingDiagram') {
      assert.equal(await evaluate(`document.querySelectorAll('${drawer} .djs-element[data-element-id="review"]').length`), 0, 'failed import never displays an old task');
    }
    await clickText(`${drawer} button`, '保存草稿');
    await poll(() => evaluate(`document.querySelectorAll('${drawer} .ant-alert').length>=2
      && document.querySelector('${drawer} .workflow-designer')?.getAttribute('aria-busy')==='false'`), 'save rejected with recoverable explanation');
    assert.equal(fixture.writes.length, before, 'neither partial XML nor publish request reaches the server');
    await close();
    assert.equal(await evaluate(`fetch(${JSON.stringify(downloadUrl)}).then(()=>false,()=>true)`), true, 'download URL released when the drawer closes');
  }
  fixture.diagramConfiguration();
  await open(); await select('note');
  assert.equal(await evaluate(`!!document.querySelector('${notice}')`), false, 'clean source is not left in blocked mode');
  assert.ok(await evaluate(`!!document.querySelector('${drawer} .djs-palette')`));
  await save(); await close();
  await evaluate(`location.hash='#/my/todos'`);
  const progress = '.workflow-progress-detail.ant-drawer-open';
  for (const [kind, taskId] of [['reference', 'task1'], ['invalid', 'taskreject']]) {
    const original = fixture.importConfiguration(kind);
    fixture.personal.diagram(original);
    await poll(() => evaluate(`!!document.querySelector('.nb-tab-panel-active tr[data-row-key="${taskId}"]')`), 'personal inbox');
    await clickText(`.nb-tab-panel-active tr[data-row-key="${taskId}"] button`, '进度');
    await poll(() => evaluate(`document.querySelector('${progress} .workflow-import-problem')?.textContent.includes('以节点处理记录')
      && document.querySelector('${progress} .workflow-node-history')?.textContent.includes('李四')`), 'incomplete diagram never blocks real approval history');
    const originalUrl = await poll(() => evaluate(`document.querySelector('${progress} a[download]')?.href`), 'original version recovery');
    assert.equal(await evaluate(`fetch(${JSON.stringify(originalUrl)}).then(r=>r.text())`), original);
    if (kind === 'reference') {
      await clickText(`${progress} .process-status-diagram__nodes button`, '审批');
      await evaluate(`window.spI18n.setLocale('en-US')`);
      await poll(() => evaluate(`document.querySelector('${progress} .workflow-import-problem')?.textContent.includes('node history')
        && document.querySelector('${progress} .process-status-diagram__legend')?.textContent.includes('Current node')`), 'progress warning and legend are localized');
      await evaluate(`window.spI18n.setLocale('zh-CN')`);
      await poll(() => evaluate(`document.querySelector('${progress} .workflow-import-problem')?.textContent.includes('以节点处理记录')`), 'progress Chinese restored');
    }
    await clickText(`${progress} .ant-segmented-item`, '流程操作记录');
    await poll(() => evaluate(`document.querySelector('${progress} [aria-label="流程操作记录"]')?.textContent.includes('张三')`), 'independent immutable action journal remains usable');
    await clickReady(`${progress} .ant-drawer-close`);
    await poll(() => evaluate(`!document.querySelector('${progress}')`), 'progress closed');
  }
  console.log('PASS: real lossy/failed import protection, original-source download and cleanup, localized diagnostics, true read-only preview, no save/publish writes, clean-source recovery and independent runtime history');
}
