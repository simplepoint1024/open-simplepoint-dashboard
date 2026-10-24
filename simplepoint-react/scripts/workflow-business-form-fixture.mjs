/** Immutable submission evidence independent of the current form designer draft. */
export function workflowBusinessSnapshot() {
  const field = (fieldId, label, type = 'STRING') => ({fieldId, label, type, choices: [], submitRequired: false,
    ...(type === 'DECIMAL' ? {precision: 18, scale: 2} : {})});
  const component = (componentId, componentType, extra = {}) => ({componentId, componentType,
    componentVersion: '1', columnSpan: 24, readOnly: false, properties: {}, children: [], ...extra});
  return {
    submission: {id: 'frozen-submission', formVersion: 1},
    form: {applicationId: 'office', pageId: 'expense', pageName: '费用报销', formId: 'expense', formName: '费用报销',
      formVersion: 1, sceneId: 'main', resourceCode: 'office.expense', capabilities: {}, document: {
        contractVersion: '1', rules: [], fields: [field('applicant', '申请人'), field('purpose', '事由'), field('note', '补充说明'),
          {...field('total', '提交时计算总额', 'DECIMAL'), calculated: true}],
        details: [{detailId: 'lines', label: '费用明细', fields: [field('item', '费用项目'), {...field('amount', '金额', 'DECIMAL'), calculated: true},
          field('invoice', '发票号码'), field('department', '成本部门'), field('memo', '费用说明')]}],
        scenes: [{sceneId: 'main', code: 'main', name: '费用报销', type: 'MAIN', root: component('root', 'layout.form', {children: [
          component('applicant', 'field.text', {fieldId: 'applicant'}), component('purpose', 'field.text', {fieldId: 'purpose'}),
          component('total', 'field.number', {fieldId: 'total'}),
          component('detail', 'data.detail', {properties: {detailId: {kind: 'STRING', value: 'lines'}}}),
          component('supplement', 'action.open-dialog', {label: '补充说明', targetSceneId: 'supplement'}),
          component('save', 'action.save'), component('submit', 'action.submit'),
        ]})}, {sceneId: 'supplement', code: 'supplement', name: '补充说明', type: 'DIALOG', width: 640,
          root: component('supplement-root', 'layout.form', {children: [component('note', 'field.textarea', {fieldId: 'note'})]})}],
      }},
    record: {id: 'expense-record', version: 3, state: 'SUBMITTED', values: {
      applicant: '张三', purpose: '华东客户现场交付差旅（提交时版本）', note: '审批应保留提交时的行程与金额', total: '10242',
      lines: Array.from({length: 8}, (_, i) => ({id: 'line-' + i, version: 0, values: {
        item: '差旅费用 ' + (i + 1), amount: '1280.25', invoice: 'INV-20261017-' + i, department: '企业交付部', memo: '客户现场实施服务',
      }})),
    }},
  };
}

export async function smokeWorkflowBusinessForm({fixture, evaluate, poll, clickText, clickReady, send}) {
  const {default: assert} = await import('node:assert/strict');
  fixture.personal.businessForm(workflowBusinessSnapshot());
  const task = '.workflow-task-editor.ant-drawer-open', progress = '.workflow-progress-detail.ant-drawer-open';
  await evaluate(`location.hash='#/my/todos?task=task1'`);
  await poll(() => evaluate(`document.querySelector('${task}')?.textContent.includes('华东客户现场交付差旅')`), 'designed submission form in pending approval')
    .catch(async error => {console.log(await evaluate(`({hash:location.hash,body:document.body.innerText})`), fixture.requests.slice(-12)); throw error;});
  assert.ok(await evaluate(`document.querySelector('${task}')?.textContent.includes('差旅费用 8')`), 'all frozen detail rows are visible');
  assert.equal(await evaluate(`document.querySelector('${task} #form_main_total')?.textContent`), '10242', 'calculated parent result is the committed snapshot');
  assert.equal(fixture.requests.some(entry => entry.path.endsWith('/records/preview')), false, 'approvals never recalculate historical results');
  assert.ok(await evaluate(`document.querySelector('${task} .ant-drawer-content-wrapper').getBoundingClientRect().width>1000`), 'business form has room for the designed layout');
  assert.equal(await evaluate(`Array.from(document.querySelectorAll('${task} input[id^="form_"], ${task} textarea[id^="form_"]')).some(e=>!e.disabled && !e.readOnly)`), false,
    'snapshot fields cannot be edited');
  await clickText(`${task} button`, '补充说明');
  await poll(() => evaluate(`document.querySelector('.sp-form-runtime-dialog')?.textContent.includes('审批应保留提交时的行程与金额')`), 'secondary designed scene reads the same frozen values');
  await clickReady('.sp-form-runtime-dialog .ant-modal-close');
  for (const width of [768, 390]) {
    await send('Emulation.setDeviceMetricsOverride', {width, height: 900, deviceScaleFactor: 1, mobile: false});
    await poll(() => evaluate(`(()=>{const body=document.querySelector('${task} .ant-drawer-body');
      return body && body.scrollWidth<=body.clientWidth+1;})()`), 'detail scroll stays within the approval drawer at ' + width);
  }
  await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
  await clickText(`${task} button`, '通过');
  await poll(() => fixture.writes.some(entry => entry.path === '/workflow/tasks/task1/complete'), 'complete approval');
  assert.deepEqual(fixture.writes.at(-1).data.variables, {approved: false}, 'form data is never posted as editable workflow variables');
  await poll(() => evaluate(`!document.querySelector('${task}')`), 'task drawer closes');
  await evaluate(`location.hash='#/my/todos?view=completed&task=task1&instance=instance1'`);
  await poll(() => evaluate(`document.querySelector('${progress}')?.textContent.includes('华东客户现场交付差旅')`), 'completed task opens submitted form by default')
    .catch(async error => {console.log(await evaluate(`({hash:location.hash,body:document.body.innerText})`), fixture.requests.slice(-12)); throw error;});
  assert.ok(fixture.requests.some(entry => entry.path === '/app/workflow/instances/instance1/business-form'));
  await clickText(`${progress} .ant-segmented-item`, '审批节点');
  await poll(() => evaluate(`!!document.querySelector('${progress} .process-status-diagram')`), 'process diagram remains available');
  await clickText(`${progress} .ant-segmented-item`, '业务快照');
  await poll(() => evaluate(`document.querySelector('${progress}')?.textContent.includes('差旅费用 8')`), 'return to frozen detail rows');
  await clickReady(`${progress} .ant-drawer-close`);
  fixture.personal.denyBusinessForm(true);
  await evaluate(`location.hash='#/my/todos?instance=instance1'`);
  await poll(() => evaluate(`document.querySelector('${progress} .ant-alert-error')?.textContent.includes('业务快照不可用')`), 'revoked snapshot authorization fails closed');
  assert.equal(await evaluate(`document.querySelector('${progress}')?.textContent.includes('华东客户现场交付差旅')`), false,
    'previous authorized cache is not rendered after denial');
  console.log('PASS: pending/completed designed forms, immutable detail rows, secondary scenes, bounded tablet/phone layout and revoked snapshot authorization');
}
