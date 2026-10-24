import assert from 'node:assert/strict';

export async function smokeCalculationRuntime({clickMenu, clickReady, clickText, fill, poll, evaluate, calculations, nameId, writes}) {
  const editor = '.sp-simple-table-drawer.ant-drawer-open', input = `${editor} #form_main_${nameId}`;
  const result = `${editor} #form_main_${calculations.target}`;
  const shown = value => poll(() => evaluate(`document.querySelector('${result}')?.textContent===${JSON.stringify(value)}`), 'calculated display ' + value);
  await shown('preview:发布后客户');
  assert.equal(await evaluate(`document.querySelector('${result}').matches('.sp-form-display-value')`), true);
  const beforeWrites = writes.length;
  let release = calculations.holdNext(), before = calculations.reads.length;
  await fill(input, '迟到输入'); await poll(() => calculations.reads.length > before, 'older preview reaches server');
  await fill(input, '新输入😀'); await shown('preview:新输入😀');
  release(); await new Promise(resolve => setTimeout(resolve, 350));
  await shown('preview:新输入😀');
  release = calculations.holdNext(); before = calculations.reads.length;
  await fill(input, '页签保留输入'); await poll(() => calculations.reads.length > before, 'pending retained-page preview');
  await clickMenu('首页'); release();
  await poll(() => evaluate(`!document.querySelector('${editor}')`), 'background editor hidden');
  await clickMenu('应用工作台测试'); await shown('preview:页签保留输入');
  release = calculations.holdNext(); before = calculations.reads.length;
  await fill(input, '已关闭编辑'); await poll(() => calculations.reads.length > before, 'pending preview before close');
  await clickReady(editor + ' .ant-drawer-close');
  await clickText('.sp-simple-table-discard button', '放弃修改'); release();
  await poll(() => evaluate(`!document.querySelector('${editor}')`), 'discard closes editor');
  await clickText('.app-published-form-data.ant-drawer-open button', '新增记录');
  await shown('preview:');
  assert.equal(await evaluate(`document.querySelector('${input}').value`), '');
  await fill(input, '发布后客户'); await shown('preview:发布后客户');
  assert.equal(writes.length, beforeWrites, 'previews never mutate records');
  console.log('PASS: published calculator readonly output, newest-input wins, retained-page fencing, close/reopen isolation and no preview writes');
}
