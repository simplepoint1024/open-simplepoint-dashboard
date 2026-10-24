import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtemp, readdir, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';

/** Publishes a real aggregate submission and approves its immutable component snapshot in personal inbox. */
export async function submitActualComponentWorkflow({evaluate, poll, click, fill, writes, call, spec, record, state, save}) {
  if (state.submissionId) return;
  await click('.ant-menu-submenu-title', '计算引擎业务验收'); await click('.ant-menu-item', '组件交付验收');
  const root = '.nb-tab-panel-active', row = root + ` [data-row-key="${record.id}"]`;
  await poll(() => evaluate(`!!document.querySelector('${row} input[type="checkbox"]')`), 'component record in standard list');
  await evaluate(`document.querySelector('${row} input[type="checkbox"]').click()`);
  await click(root + ' button', '提交审批');
  const modal = '.app-workflow-submission .ant-modal';
  await poll(() => evaluate(`!!document.querySelector('${modal} input[aria-label="选择审批流程"]')`), 'bound workflow selector');
  await evaluate(`document.querySelector('${modal} input[aria-label="选择审批流程"]').closest('.ant-select').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}))`);
  const option = '.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option';
  await poll(() => evaluate(`[...document.querySelectorAll('${option}')].some(e=>e.textContent.includes('组件交付验收'))`), 'published component workflow');
  await evaluate(`[...document.querySelectorAll('${option}')].find(e=>e.textContent.includes('组件交付验收')).click()`);
  await fill(modal + ' #root_title', '38类组件完整审批・日本語😀');
  await click(modal + ' button', '提交审批');
  await poll(() => evaluate(`document.querySelector('${modal} .ant-alert')?.textContent.includes('提交已受理')||document.querySelector('${modal} .ant-alert')?.textContent.includes('审批已发起')`), 'submission accepted');
  const command = writes.find(write => write.method === 'POST' && write.path === spec.runtime + '/submissions');
  assert(command); assert(!Object.hasOwn(command.body.values, 'total')); assert(!Object.hasOwn(command.body.values, 'normalized_total'));
  state.command = command.body; await save();
  const submission = await call('employee', spec.runtime + '/submissions', 'POST', command.body);
  state.submissionId = submission.id; await save();
  await click(modal + ' button', '完成');
  const started = await poll(async () => {
    const value = await call('employee', spec.runtime + '/submissions/' + submission.id);
    return value.processInstanceId ? value : undefined;
  }, 'component workflow started');
  state.instanceId = started.processInstanceId; state.recordId = started.recordId;
  const snapshot = await call('employee', spec.runtime + '/submissions/' + submission.id + '/snapshot');
  assert.equal(snapshot.record.values.total, '21'); assert.equal(snapshot.record.values.normalized_total, '31');
  const businessValues = values => Object.fromEntries(Object.entries(values).map(([key, value]) => [key,
    Array.isArray(value) ? value.map(row => ({id: row.id, values: row.values})) : value]));
  assert.deepEqual(businessValues(snapshot.record.values), businessValues(record.values), 'Submission calculates and freezes the edited aggregate');
  state.snapshot = snapshot.record; await save();
  const task = await poll(async () => {
    const tasks = await call('manager', '/workflow/tasks/pending?size=100');
    return tasks.content.find(task => task.processInstanceId === started.processInstanceId);
  }, 'component approval task');
  state.taskId = task.id; state.submitted = true; await save();
}

export async function approveActualComponentWorkflow({evaluate, poll, click, fill, send, screenshot, writes, on, call, spec, state, save}) {
  if (state.passed) return;
  assert(state.submitted);
  const task = '.workflow-task-editor.ant-drawer-open', history = '.workflow-progress-detail.ant-drawer-open';
  const attachment = Object.values(state.snapshot.values).filter(value => typeof value === 'string').flatMap(value => {
    try {const data = JSON.parse(value); return Array.isArray(data) ? data.filter(file => file?.name === 'component-acceptance.txt') : [];} catch {return [];}
  })[0]; assert(attachment);
  const expectedHash = createHash('sha256').update('合成验收附件 中文・日本語😀\n').digest('hex');
  let downloadStatus;
  on('Network.responseReceived', event => {
    if (new URL(event.response.url).pathname === '/common/object-storage/objects/' + attachment.id + '/content') {
      downloadStatus = event.response.status;
    }
  });
  const verifyReadOnly = async (scope, kind) => {
    await poll(() => evaluate(`document.querySelector('${scope}')?.textContent.includes('明细修订')`), kind + ' business snapshot');
    assert.equal(await evaluate(`document.querySelector('${scope} #form_main_total')?.textContent`), '21');
    assert.equal(await evaluate(`[...document.querySelectorAll('${scope} input[id^="form_"],${scope} textarea[id^="form_"]')].some(e=>!e.disabled&&!e.readOnly)`), false);
    assert.equal(await evaluate(`[...document.querySelectorAll('${scope} button')].some(e=>['保存草稿','重置','提交','添加明细','编辑','删除'].includes(e.textContent.replaceAll(' ','')))`), false);
    assert.equal(await evaluate(`!!window.__formUnsafe || !!document.querySelector('${scope} .sp-form-markdown script')`), false);
    for (const [type, button] of [['DIALOG', '打开弹窗'], ['DRAWER', '打开抽屉']]) {
      await click(scope + ' button', button);
      const overlay = type === 'DIALOG' ? '.sp-form-runtime-dialog .ant-modal' : '.sp-form-runtime-drawer.ant-drawer-open';
      await poll(() => evaluate(`document.querySelector('${overlay}')?.textContent.includes('${type} 中文・日本語😀')`), kind + ' frozen secondary scene');
      await click(overlay + ' button', '取消');
    }
    const directory = await mkdtemp(path.join(tmpdir(), 'sp-component-download-'));
    try {
      await send('Browser.setDownloadBehavior', {behavior: 'allow', downloadPath: directory});
      downloadStatus = undefined;
      await click(scope + ' button', '下载附件');
      const name = await poll(async () => (await readdir(directory)).find(name => !name.endsWith('.crdownload')),
        kind + ' actual browser attachment file');
      assert.equal(name, attachment.name);
      const bytes = await readFile(path.join(directory, name));
      assert.equal(bytes.length, attachment.size);
      assert.equal(createHash('sha256').update(bytes).digest('hex'), expectedHash);
      assert.equal(downloadStatus, 200);
      state.downloads ??= []; state.downloads.push({kind, bytes: bytes.length, sha256: expectedHash}); await save();
    } finally {await rm(directory, {recursive: true, force: true});}
    state.layouts ??= [];
    for (const width of [1440, 768, 390]) {
      await send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
      await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
      await poll(() => evaluate(`(()=>{const e=document.querySelector('${scope} .ant-drawer-body');return e&&e.scrollWidth<=e.clientWidth+1})()`), kind + ' bounded at ' + width);
      await screenshot('calculation-all-components-' + kind + '-' + width);
      state.layouts.push({kind, width, bounded: true}); await save();
    }
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
  };
  if (!state.approved) {
    await evaluate(`location.hash=${JSON.stringify('#/my/todos?task=' + state.taskId)}`);
    await verifyReadOnly(task, 'pending');
    await fill(task + ' #root_comment', '已核对全部组件值、附件、计算结果和只读权限。');
    await click(task + ' button', '通过');
    await poll(() => evaluate(`!document.querySelector('${task}')`), 'actual task approved');
    const completion = writes.find(write => write.path === '/workflow/tasks/' + state.taskId + '/complete');
    assert(completion); assert.deepEqual(completion.body.variables, {});
    state.completion = completion.body; state.approved = true; await save();
  }
  await poll(async () => (await call('employee', spec.runtime + '/submissions/' + state.submissionId)).processStatus === 'COMPLETED', 'durable completed submission');
  await evaluate(`location.hash=${JSON.stringify('#/my/todos?view=completed&task=' + state.taskId + '&instance=' + state.instanceId)}`);
  await verifyReadOnly(history, 'completed');
  const after = await call('employee', spec.runtime + '/submissions/' + state.submissionId + '/snapshot');
  assert.deepEqual(after.record, state.snapshot);
  assert(!writes.some(write => write.path.endsWith('/records/preview')), 'Historical forms never recalculate');
  state.passed = true; state.completedAt = new Date().toISOString(); await save();
  console.log('PASS all component published values in actual pending/completed approvals, secondary scenes and three viewport sizes');
}
