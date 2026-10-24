import assert from 'node:assert/strict';

/** Exercises actual platform CRUD controls. The API helper only reads persisted results. */
export async function verifyFunctionCrudUi({evaluate, poll, click, fill, writes, screenshot, call, spec, cases, state, save}) {
  await click('.ant-menu-submenu-title', '计算引擎业务验收');
  await click('.ant-menu-item', spec.name);
  const root = '.nb-tab-panel-active', drawer = '.sp-simple-table-drawer.ant-drawer-open';
  const input = id => drawer + ' #form_main_' + id;
  state.cases ??= {};
  for (const item of cases) {
    const evidence = state.cases[item.name] ??= {startedAt: new Date().toISOString()};
    if (evidence.passed) continue;
    const timed = ['NOW', 'TODAY'].includes(item.name);
    const expected = item.type === 'BOOLEAN' ? item.expected.toUpperCase() : item.expected;
    const marker = '浏览器验算 ' + item.name + ' ' + state.runId;
    if (!evidence.recordId) {
      await poll(async () => {
        await evaluate(`(() => {const checked=[...document.querySelectorAll('${root} tbody input[type="checkbox"]:checked')]
          .find(box => box.getClientRects().length); checked?.click();})()`);
        return evaluate(`[...document.querySelectorAll('${root} button')].some(button =>
          button.getClientRects().length && !button.disabled && button.textContent.replaceAll(' ','')==='新增')`);
      }, 'clear selected records before create');
      await click(root + ' button', '新增');
      await poll(() => evaluate(`!!document.querySelector('${input('function_name')}')`), 'function create control');
      await evaluate(`document.querySelector('${input('function_name')}').click()`);
      await fill(input('function_name'), item.name);
      await click('.ant-select-dropdown .ant-select-item-option-content', item.name);
      for (const [field, value] of Object.entries({expression: item.expression, expected: timed ? '服务端事务时间' : expected,
        result_type: item.type, reviewer: '浏览器验收负责人', conclusion: marker})) await fill(input(field), value);
      await poll(() => evaluate(`document.querySelector('${input('actual')}')?.textContent${timed ? '?.trim()' : '===' + JSON.stringify(expected)}`), 'trusted preview ' + item.name);
      const before = Date.now();
      await click(drawer + ' button', '保存草稿');
      await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'created ' + item.name);
      const page = await call(spec.runtime + '/records/query', 'POST', {page: 0, size: 20,
        filter: {operator: 'EQUALS', fieldId: 'conclusion', values: [marker], children: []}});
      assert.equal(page.total, 1); evidence.recordId = page.records[0].id;
      evidence.created = await call(spec.runtime + '/records/' + evidence.recordId);
      evidence.creationWindow = [before, Date.now()]; await save();
    }
    const row = root + ' [data-row-key="' + evidence.recordId + '"]';
    await poll(() => evaluate(`!!document.querySelector('${row}')`), 'newest created record in standard list');
    await evaluate(`(() => {const box=document.querySelector('${row} input[type="checkbox"]'); if(!box.checked) box.click();})()`);
    await click(root + ' button', '编辑');
    await poll(() => evaluate(`!!document.querySelector('${input('conclusion')}')`), 'persisted editable record');
    await fill(input('conclusion'), marker + ' 已复核・日本語😀');
    const before = Date.now();
    await click(drawer + ' button', '保存草稿');
    await poll(() => evaluate(`!document.querySelector('${drawer}')`), 'edited ' + item.name);
    evidence.edited = await call(spec.runtime + '/records/' + evidence.recordId);
    evidence.editWindow = [before, Date.now()];
    assert.equal(evidence.edited.version, evidence.created.version + 1);
    assert.equal(evidence.edited.values.conclusion, marker + ' 已复核・日本語😀');
    for (const [record, window] of [[evidence.created, evidence.creationWindow], [evidence.edited, evidence.editWindow]]) {
      if (!timed) assert.equal(record.values.actual, expected, item.name);
      else if (item.name === 'TODAY') assert.equal(record.values.actual, new Date(window[0]).toISOString().slice(0, 10));
      else {const actual = Date.parse(record.values.actual + 'Z'); assert(actual >= window[0] - 2000 && actual <= window[1] + 2000);}
    }
    const commits = writes.filter(w => w.path === spec.runtime + '/records' || w.path === spec.runtime + '/records/' + evidence.recordId);
    for (const commit of commits) {
      assert(!Object.hasOwn(commit.body.values, 'actual'));
      assert(!Object.keys(commit.body.values).some(key => /^result_\d+$/.test(key)));
    }
    evidence.passed = true; await save(); console.log('PASS browser function ' + item.name);
  }
  await screenshot('calculation-function-crud');
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
}
