import assert from 'node:assert/strict';

/** Real runtime editor and SForm submit event in one browser task, without replacing the form engine. */
export async function smokeWorkflowFormCommit({clickMenu, clickText, poll, evaluate, fixture}) {
  const page = '.nb-tab-panel-active', drawer = '.workflow-task-editor.ant-drawer-open';
  const ready = scope => poll(() => evaluate(`(()=>{const button=document.querySelector('${scope} form.rjsf button[type="submit"]');return button&&!button.disabled;})()`), 'current runtime variables can submit');
  const edit = (scope, source, submit = false) => evaluate(`(()=>{const view=document.querySelector('${scope} .cm-content').cmTile.root.view;
    view.dispatch({changes:{from:0,to:view.state.doc.length,insert:${JSON.stringify(source)}}});
    ${submit ? `document.querySelector('${scope} form.rjsf button[type="submit"]').click();` : ''}})()`);
  const blocked = async (scope, source) => {
    const before = fixture.writes.length;
    await edit(scope, source, true);
    await poll(() => evaluate(`!!document.querySelector('${scope} .ant-alert-error')`), 'current invalid runtime draft diagnosed');
    await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    if (fixture.writes.length !== before) console.log('Unexpected runtime submission:', JSON.stringify(fixture.writes.slice(before)));
    assert.equal(fixture.writes.length, before, 'invalid current data followed immediately by real submission must not submit the previous valid values');
  };
  await clickMenu('流程任务测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="task1"]')`), 'native runtime task list');
  await clickText(page + ' tr[data-row-key="task1"] button', '办理');
  await poll(() => evaluate(`!!document.querySelector('${drawer} textarea#root_comment')`), 'real task SForm');
  await clickText(drawer + ' .ant-segmented-item', '高级 JSON');
  await poll(() => evaluate(`!!document.querySelector('${drawer} .cm-content')`), 'real runtime JSON editor');
  for (const source of ['{"approved":', '{"approved":"true"}', '{}', '{"approved":true,"approver":"member-1"}']) {
    await ready(drawer); await blocked(drawer, source); await edit(drawer, '{"approved":false}');
  }
  await ready(drawer); const taskBefore = fixture.writes.length;
  await edit(drawer, '{"approved":true}', true);
  await poll(() => fixture.writes.length === taskBefore + 1, 'corrected task commits once');
  assert.deepEqual(fixture.writes.at(-1).data.variables, {approved: true}, 'valid same-task edits submit the current value, not the old false value');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'corrected task closes');

  await clickMenu('流程定义测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="definition1"]')`), 'native definition list');
  await clickText(page + ' tr[data-row-key="definition1"] button', '发起流程');
  const start = '.ant-modal:not(.ant-modal-hidden)';
  await poll(() => evaluate(`!!document.querySelector('${start} .ant-segmented')`), 'actual versioned start form');
  await clickText(start + ' .ant-segmented-item', '高级 JSON');
  await poll(() => evaluate(`!!document.querySelector('${start} .cm-content')`), 'start JSON editor');
  await edit(start, '{"approver":"member-1"}');
  for (const source of ['{"approver":', '{}', '{"approver":false}', '{"approver":"member-1","approved":true}']) {
    await ready(start); await blocked(start, source); await edit(start, '{"approver":"member-1"}');
  }
  await ready(start); const startBefore = fixture.writes.length;
  await edit(start, '{"approver":"member-2"}', true);
  await poll(() => fixture.writes.length === startBefore + 1, 'corrected start commits once');
  assert.deepEqual(fixture.writes.at(-1).data.variables, {approver: 'member-2'});
  assert.equal(fixture.writes.at(-1).data.version, 2, 'runtime capture does not replace the selected immutable version');
}
