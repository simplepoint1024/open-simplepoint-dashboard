import assert from 'node:assert/strict';

/** Real typed controls and SForm submission in one browser task; no substitute form engine. */
export async function smokeWorkflowFormCommit({clickMenu, clickText, poll, evaluate, fixture}) {
  const page = '.nb-tab-panel-active', drawer = '.workflow-task-editor.ant-drawer-open';
  const ready = scope => poll(() => evaluate(`(()=>{const button=document.querySelector('${scope} form.rjsf button[type="submit"]');return button&&!button.disabled;})()`), 'current typed variables can submit');
  const formOnly = async scope => assert.equal(await evaluate(`!!document.querySelector('${scope} .ant-segmented, ${scope} .cm-content')`), false, 'workflow variables expose no JSON mode or toggle');
  await clickMenu('流程任务测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="task1"]')`), 'native runtime task list');
  await clickText(page + ' tr[data-row-key="task1"] button', '办理');
  await poll(() => evaluate(`!!document.querySelector('${drawer} input[type="checkbox"]')`), 'real typed task SForm');
  await formOnly(drawer); await ready(drawer);
  const taskBefore = fixture.writes.length;
  await evaluate(`(()=>{document.querySelector('${drawer} input[type="checkbox"]').click();
    document.querySelector('${drawer} form.rjsf button[type="submit"]').click();})()`);
  await poll(() => fixture.writes.length === taskBefore + 1, 'typed task commits once');
  assert.deepEqual(fixture.writes.at(-1).data.variables, {approved: true}, 'same-task checkbox edits submit the current value, not the previous false value');
  await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'typed task closes');

  await clickMenu('流程定义测试');
  await poll(() => evaluate(`!!document.querySelector('${page} tr[data-row-key="definition1"]')`), 'native definition list');
  await clickText(page + ' tr[data-row-key="definition1"] button', '发起流程');
  const start = '.ant-modal:not(.ant-modal-hidden)';
  await poll(() => evaluate(`!!document.querySelector('${start} #root_approver')`), 'actual versioned typed start form');
  await formOnly(start);
  const before = fixture.writes.length;
  await evaluate(`document.querySelector('${start} form.rjsf button[type="submit"]').click()`);
  assert.equal(fixture.writes.length, before, 'missing required approver cannot start a process');
  const open = () => evaluate(`document.querySelector('${start} #root_approver').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  await open();
  await clickText('.ant-select-dropdown .ant-select-item-option', '租户成员 1');
  await ready(start); await open();
  await poll(() => evaluate(`[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].some(option=>option.textContent==='租户成员 2')`), 'replacement approver option');
  await evaluate(`(()=>{[...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')].find(option=>option.textContent==='租户成员 2').click();
    document.querySelector('${start} form.rjsf button[type="submit"]').click();})()`);
  await poll(() => fixture.writes.length === before + 1, 'typed start commits once');
  assert.deepEqual(fixture.writes.at(-1).data.variables, {approver: 'member-2'}, 'same-task selection submits the current identity');
  assert.equal(fixture.writes.at(-1).data.version, 2, 'typed form retains the selected immutable version');
}
