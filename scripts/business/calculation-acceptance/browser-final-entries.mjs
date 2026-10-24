import assert from 'node:assert/strict';

/** Reload actual published resource and workflow deep links in the normal employee browser. */
export async function verifyFinalEntries({evaluate, send, poll, click, screenshot, call, appId, forms,
  instanceId, failedSubmissionId, state, save}) {
  const root = '.nb-tab-panel-active', detail = '.app-record-detail.ant-drawer-open';
  state.entries ??= [];
  for (const [name, spec] of Object.entries(forms)) {
    if (state.entries.some(entry => entry.name === name) && (name !== 'cal_assets' || state.failedHistoryVerified)) continue;
    const route = '#/app/runtime/' + appId + '/' + spec.pageId;
    const records = await call(spec.runtime + '/records/query', 'POST', {page: 0, size: 20});
    assert(records.records.length, name + ' has real records');
    await evaluate(`location.hash=${JSON.stringify(route)}`);
    const row = name === 'cal_ledger' ? root + ' .sp-record-card input[type="checkbox"]'
      : records.records.map(record => root + ` [data-row-key="${record.id}"] input[type="checkbox"]`).join(',');
    await poll(() => evaluate(`location.hash===${JSON.stringify(route)}&&!!document.querySelector('${row}')`), name + ' direct entry');
    // A real reload destroys retained React/query state; the saved URL must reconstruct the page.
    await evaluate(`document.documentElement.setAttribute('data-acceptance-before-reload','true')`);
    await send('Page.reload', {ignoreCache: true});
    await poll(async () => {try {return await evaluate(`!document.documentElement.hasAttribute('data-acceptance-before-reload')&&location.hash===${JSON.stringify(route)}&&!!document.querySelector('${row}')`);} catch {return false;}}, name + ' restored URL');
    await evaluate(`document.querySelector('${row}').click()`); await click(root + ' button', '详情');
    await poll(() => evaluate(`!!document.querySelector('${detail} .ant-drawer-body [id^="form_"]')`), name + ' detail');
    assert.equal(await evaluate(`document.querySelector('${detail} .ant-alert-error')?.textContent ?? ''`), '');
    await evaluate(`document.querySelector('${detail} .ant-drawer-close').click()`);
    await poll(() => evaluate(`!document.querySelector('${detail}')`), name + ' closed');
    const selected = `${root} [data-row-key] input[type="checkbox"]:checked,${root} .sp-record-card input[type="checkbox"]:checked`;
    await evaluate(`document.querySelector('${selected}')?.click()`);
    await poll(() => evaluate(`!document.querySelector('${selected}')`), name + ' selection cleared');
    if (name === 'cal_assets') {
      const history = '.app-record-history.ant-drawer-open', failed = history + ` [data-row-key="${failedSubmissionId}"]`;
      await click(root + ' button', '审批记录');
      await poll(() => evaluate(`document.querySelector('${failed}')?.textContent.includes('提交失败')`), 'terminal failed submission label');
      await evaluate(`document.querySelector('${failed} input[type="checkbox"]').click()`);
      state.failedHistoryButtons = await evaluate(`[...document.querySelectorAll('${history} button')].map(e=>({label:e.getAttribute('aria-label')||e.textContent.replace(/\\s/g,''),disabled:e.disabled}))`);
      for (const label of ['业务快照', '重试原提交', '修改后重新提交']) {
        const button = state.failedHistoryButtons.find(item => item.label === label);
        assert(button?.disabled, label + ' disabled for terminal rejection');
      }
      await screenshot('calculation-terminal-rejection');
      await evaluate(`document.querySelector('${history} .ant-drawer-close').click()`);
      await poll(() => evaluate(`!document.querySelector('${history}')`), 'history closed');
      state.failedHistoryVerified = true;
    }
    state.entries = state.entries.filter(entry => entry.name !== name);
    state.entries.push({name, pageId: spec.pageId, reload: true, detail: true}); await save();
  }
  const route = '#/my/items?instance=' + instanceId, progress = '.workflow-progress-detail.ant-drawer-open';
  await evaluate(`location.hash=${JSON.stringify(route)}`);
  await poll(() => evaluate(`!!document.querySelector('${progress} .ant-segmented')`), 'workflow direct link');
  await evaluate(`document.documentElement.setAttribute('data-acceptance-before-reload','true')`);
  await send('Page.reload', {ignoreCache: true});
  await poll(async () => {try {return await evaluate(`!document.documentElement.hasAttribute('data-acceptance-before-reload')&&location.hash===${JSON.stringify(route)}&&!!document.querySelector('${progress} .ant-segmented')`);} catch {return false;}}, 'workflow restored link');
  await click(progress + ' .ant-segmented-item', '审批节点');
  await poll(() => evaluate(`!!document.querySelector('${progress} .process-status-diagram[aria-busy="false"] .djs-shape')`), 'restored immutable workflow diagram');
  assert.equal(await evaluate(`document.querySelector('${progress} .process-status-diagram .ant-alert')?.textContent ?? ''`), '');
  await screenshot('calculation-restored-workflow');
  state.workflow = {instanceId, reload: true, diagram: true};
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS published resources, detail drawers, rejected submission actions and refreshed workflow deep link');
}
